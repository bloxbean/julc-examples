package com.example.offchain;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.example.validators.WhitelistTreasuryValidator;

/**
 * End-to-end demo: Whitelist Treasury Validator with Yaci Devkit.
 * <p>
 * Workflow:
 * 1. Load pre-compiled WhitelistTreasuryValidator (no @Param)
 * 2. Create 3 whitelisted accounts + 1 outsider
 * 3. Lock treasury funds with whitelist datum (3 signers, threshold 2)
 * 4. Withdraw with 2 whitelisted signers — should succeed
 * 5. Verify on devnet
 * <p>
 * Demonstrates: nested HOFs with untyped ByteStringType lambdas,
 * the core feature enabled by the double-unwrap fix.
 * <p>
 * Requires Yaci Devkit running locally.
 */
public class WhitelistTreasuryDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("=== Whitelist Treasury Validator E2E Demo ===\n");

        if (!YaciHelper.isYaciReachable()) {
            System.out.println("ERROR: Yaci Devkit not reachable at " + YaciHelper.YACI_BASE_URL);
            System.out.println("Start it with: yaci-cli devkit start");
            System.exit(1);
        }

        // 1. Create accounts
        var signerA = new Account(Networks.testnet());
        var signerB = new Account(Networks.testnet());
        var signerC = new Account(Networks.testnet());
        var funder = new Account(Networks.testnet());
        byte[] pkhA = signerA.hdKeyPair().getPublicKey().getKeyHash();
        byte[] pkhB = signerB.hdKeyPair().getPublicKey().getKeyHash();
        byte[] pkhC = signerC.hdKeyPair().getPublicKey().getKeyHash();

        System.out.println("Signer A: " + signerA.baseAddress().substring(0, 30) + "...");
        System.out.println("Signer B: " + signerB.baseAddress().substring(0, 30) + "...");
        System.out.println("Signer C: " + signerC.baseAddress().substring(0, 30) + "...");
        System.out.println("Funder:   " + funder.baseAddress().substring(0, 30) + "...");

        // 2. Load the pre-compiled validator (no params)
        var script = JulcScriptLoader.load(WhitelistTreasuryValidator.class);
        var scriptAddr = AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();
        System.out.println("Script address: " + scriptAddr);

        // 3. Fund accounts
        YaciHelper.topUp(funder.baseAddress(), 1000);
        YaciHelper.topUp(signerA.baseAddress(), 100);
        YaciHelper.topUp(signerB.baseAddress(), 100);

        var backend = YaciHelper.createBackendService();
        var quickTx = new QuickTxBuilder(backend);

        // 4. Build datum: WhitelistDatum(authorizedSigners, threshold)
        //    PubKeyHash maps to ByteStringType → list elements are BData-wrapped bytes
        //    WhitelistDatum = Constr(0, [ListData([BData(pkhA), BData(pkhB), BData(pkhC)]), IData(2)])
        var datum = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(
                        ListPlutusData.of(
                                new BytesPlutusData(pkhA),
                                new BytesPlutusData(pkhB),
                                new BytesPlutusData(pkhC)),
                        BigIntPlutusData.of(2))) // threshold = 2 of 3
                .build();

        // 5. Lock 50 ADA to the script address
        System.out.println("\n--- Locking 50 ADA to whitelist treasury ---");
        var lockTx = new Tx()
                .payToContract(scriptAddr, Amount.ada(50), datum)
                .from(funder.baseAddress());

        var lockResult = quickTx.compose(lockTx)
                .withSigner(SignerProviders.signerFrom(funder))
                .complete();

        if (!lockResult.isSuccessful()) {
            System.out.println("FAILED to lock: " + lockResult);
            System.exit(1);
        }
        var lockTxHash = lockResult.getValue();
        System.out.println("Lock tx: " + lockTxHash);
        YaciHelper.waitForConfirmation(backend, lockTxHash);

        // 6. Find the script UTXO
        var scriptUtxo = YaciHelper.findUtxo(backend, scriptAddr, lockTxHash);
        System.out.println("Script UTXO: " + scriptUtxo.getTxHash() + "#" + scriptUtxo.getOutputIndex());

        // 7. Withdraw: signers A and B sign (2 >= threshold 2)
        //    Redeemer: Withdraw(amount) = Constr(0, [IData(20_000_000)])
        System.out.println("\n--- Withdrawing 20 ADA (signers A + B approve) ---");
        var redeemer = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(BigIntPlutusData.of(20_000_000)))
                .build();

        var withdrawTx = new ScriptTx()
                .collectFrom(scriptUtxo, redeemer)
                .payToAddress(signerA.baseAddress(), Amount.ada(20))
                .attachSpendingValidator(script);

        var withdrawResult = quickTx.compose(withdrawTx)
                .withSigner(SignerProviders.signerFrom(signerA))
                .withSigner(SignerProviders.signerFrom(signerB))
                .withRequiredSigners(pkhA, pkhB)
                .feePayer(signerA.baseAddress())
                .collateralPayer(signerA.baseAddress())
                .withTxEvaluator(YaciHelper.julcEvaluator(backend))
                .complete();

        if (!withdrawResult.isSuccessful()) {
            System.out.println("FAILED to withdraw: " + withdrawResult);
            System.exit(1);
        }
        System.out.println("Withdraw tx: " + withdrawResult.getValue());
        YaciHelper.waitForConfirmation(backend, withdrawResult.getValue());

        System.out.println("\n=== Whitelist Treasury Demo PASSED ===");
    }
}
