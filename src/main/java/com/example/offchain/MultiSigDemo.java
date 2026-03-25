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
import com.example.validators.MultiSigTreasury;

/**
 * End-to-end demo: multi-signature treasury requiring two signers.
 * <p>
 * This demonstrates:
 * 1. Loading a compiled validator with a custom multi-field datum
 * 2. Locking ADA with a 2-signer datum
 * 3. Unlocking with both signatures required
 * <p>
 * Requires Yaci Devkit running locally.
 */
public class MultiSigDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("=== Multi-Sig Treasury E2E Demo ===\n");

        if (!YaciHelper.isYaciReachable()) {
            System.out.println("ERROR: Yaci Devkit not reachable at " + YaciHelper.YACI_BASE_URL);
            System.out.println("Start it with: yaci-cli devkit start");
            System.exit(1);
        }

        // 1. Load the pre-compiled validator
        var script = JulcScriptLoader.load(MultiSigTreasury.class);
        var scriptHash = JulcScriptLoader.scriptHash(MultiSigTreasury.class);
        var scriptAddr = AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();
        System.out.println("Script hash: " + scriptHash);
        System.out.println("Script address: " + scriptAddr);

        // 2. Create 2 signer accounts
        var signer1 = new Account(Networks.testnet());
        var signer2 = new Account(Networks.testnet());
        byte[] pkh1 = signer1.hdKeyPair().getPublicKey().getKeyHash();
        byte[] pkh2 = signer2.hdKeyPair().getPublicKey().getKeyHash();
        System.out.println("Signer 1: " + signer1.baseAddress().substring(0, 20) + "...");
        System.out.println("Signer 2: " + signer2.baseAddress().substring(0, 20) + "...");

        // Fund both signers
        YaciHelper.topUp(signer1.baseAddress(), 1000);
        YaciHelper.topUp(signer2.baseAddress(), 1000);

        var backend = YaciHelper.createBackendService();
        var quickTx = new QuickTxBuilder(backend);

        // 3. Create datum: TreasuryDatum(signer1, signer2)
        //    Datum = Constr(0, [BData(pkh1), BData(pkh2)])
        var datum = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(
                        new BytesPlutusData(pkh1),
                        new BytesPlutusData(pkh2)))
                .build();

        // 4. Lock 20 ADA to the script address
        System.out.println("\n--- Locking 20 ADA to treasury ---");
        var lockTx = new Tx()
                .payToContract(scriptAddr, Amount.ada(20), datum)
                .from(signer1.baseAddress());

        var lockResult = quickTx.compose(lockTx)
                .withSigner(SignerProviders.signerFrom(signer1))
                .complete();

        if (!lockResult.isSuccessful()) {
            System.out.println("FAILED to lock: " + lockResult);
            System.exit(1);
        }
        var lockTxHash = lockResult.getValue();
        System.out.println("Lock tx: " + lockTxHash);
        YaciHelper.waitForConfirmation(backend, lockTxHash);

        // 5. Find the script UTXO
        var scriptUtxo = YaciHelper.findUtxo(backend, scriptAddr, lockTxHash);
        System.out.println("Script UTXO: " + scriptUtxo.getTxHash() + "#" + scriptUtxo.getOutputIndex());

        // 6. Unlock: both signers must sign. Redeemer = BigInteger(0) (unused)
        System.out.println("\n--- Unlocking with both signers ---");
        var redeemer = BigIntPlutusData.of(0);

        var unlockTx = new ScriptTx()
                .collectFrom(scriptUtxo, redeemer)
                .payToAddress(signer1.baseAddress(), Amount.ada(19))
                .attachSpendingValidator(script);

        var unlockResult = quickTx.compose(unlockTx)
                .withSigner(SignerProviders.signerFrom(signer1))
                .withSigner(SignerProviders.signerFrom(signer2))
                .withRequiredSigners(pkh1, pkh2)
                .feePayer(signer1.baseAddress())
                .collateralPayer(signer1.baseAddress())
                .withTxEvaluator(YaciHelper.julcEvaluator(backend))
                .complete();

        if (!unlockResult.isSuccessful()) {
            System.out.println("FAILED to unlock: " + unlockResult);
            System.exit(1);
        }
        System.out.println("Unlock tx: " + unlockResult.getValue());
        YaciHelper.waitForConfirmation(backend, unlockResult.getValue());

        System.out.println("\n=== Multi-Sig Demo PASSED ===");
    }
}
