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
import com.example.validators.OutputCheckValidator;

import java.math.BigInteger;

/**
 * End-to-end demo: payment check validator using OutputLib.
 * <p>
 * This demonstrates:
 * 1. Loading a compiled validator that uses @OnchainLibrary (OutputLib)
 * 2. Locking ADA with a datum containing recipient address + minimum amount
 * 3. Spending from script — OutputLib validates recipient receives minimum lovelace
 * <p>
 * Requires Yaci Devkit running locally.
 */
public class OutputCheckDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("=== OutputCheck Validator E2E Demo ===\n");

        if (!YaciHelper.isYaciReachable()) {
            System.out.println("ERROR: Yaci Devkit not reachable at " + YaciHelper.YACI_BASE_URL);
            System.out.println("Start it with: yaci-cli devkit start");
            System.exit(1);
        }

        // 1. Load the pre-compiled validator (no params)
        var script = JulcScriptLoader.load(OutputCheckValidator.class);
        var scriptAddr = AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();
        System.out.println("Script address: " + scriptAddr);

        // 2. Create sender and recipient accounts
        var sender = new Account(Networks.testnet());
        var recipient = new Account(Networks.testnet());
        byte[] recipientPkh = recipient.hdKeyPair().getPublicKey().getKeyHash();
        System.out.println("Sender:    " + sender.baseAddress().substring(0, 20) + "...");
        System.out.println("Recipient: " + recipient.baseAddress().substring(0, 20) + "...");

        // Fund sender
        YaciHelper.topUp(sender.baseAddress(), 1000);

        var backend = YaciHelper.createBackendService();
        var quickTx = new QuickTxBuilder(backend);

        // 3. Create datum: PaymentDatum(recipientAddress, minAmount)
        //    Address = Constr(0, [Constr(0, [BData(pkh)]), Constr(1, [])])
        //    PaymentDatum = Constr(0, [address, IData(minAmount)])
        BigInteger minAmount = BigInteger.valueOf(10_000_000); // 10 ADA

        var recipientAddress = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(
                        ConstrPlutusData.builder()
                                .alternative(0)
                                .data(ListPlutusData.of(new BytesPlutusData(recipientPkh)))
                                .build(),
                        ConstrPlutusData.builder()
                                .alternative(1)
                                .data(new ListPlutusData())
                                .build()))
                .build();

        var datum = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(recipientAddress, BigIntPlutusData.of(minAmount)))
                .build();

        // 4. Lock 20 ADA to the script address
        System.out.println("\n--- Locking 20 ADA to script ---");
        var lockTx = new Tx()
                .payToContract(scriptAddr, Amount.ada(20), datum)
                .from(sender.baseAddress());

        var lockResult = quickTx.compose(lockTx)
                .withSigner(SignerProviders.signerFrom(sender))
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

        // 6. Spend from script — pay recipient >= 10 ADA
        System.out.println("\n--- Spending from script (paying recipient 15 ADA) ---");
        var redeemer = ConstrPlutusData.builder()
                .alternative(0)
                .data(new ListPlutusData())
                .build();

        var unlockTx = new ScriptTx()
                .collectFrom(scriptUtxo, redeemer)
                .payToAddress(recipient.enterpriseAddress(), Amount.ada(15))
                .attachSpendingValidator(script);

        var unlockResult = quickTx.compose(unlockTx)
                .withSigner(SignerProviders.signerFrom(sender))
                .feePayer(sender.baseAddress())
                .collateralPayer(sender.baseAddress())
                .withTxEvaluator(YaciHelper.julcEvaluator(backend))
                .complete();

        if (!unlockResult.isSuccessful()) {
            System.out.println("FAILED to spend: " + unlockResult);
            System.exit(1);
        }
        System.out.println("Spend tx: " + unlockResult.getValue());
        YaciHelper.waitForConfirmation(backend, unlockResult.getValue());

        System.out.println("\n=== OutputCheck Demo PASSED ===");
    }
}
