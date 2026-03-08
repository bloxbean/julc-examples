package com.example.cftemplates.simpletransfer.offchain;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.example.cftemplates.simpletransfer.onchain.CfSimpleTransferValidator;
import com.example.offchain.YaciHelper;

/**
 * Off-chain demo for CfSimpleTransferValidator.
 * Locks ADA at the script address and then the receiver unlocks it.
 */
public class SimpleTransferDemo {

    public static void main(String[] args) throws Exception {
        if (!YaciHelper.isYaciReachable()) {
            System.out.println("Yaci DevKit not reachable. Skipping demo.");
            System.exit(1);
        }

        // Create accounts
        var sender = new Account(Networks.testnet());
        var receiver = new Account(Networks.testnet());
        byte[] receiverPkh = receiver.hdKeyPair().getPublicKey().getKeyHash();

        // Fund accounts
        YaciHelper.topUp(sender.baseAddress(), 1000);
        YaciHelper.topUp(receiver.baseAddress(), 1000);

        // Load validator with receiver param
        var script = JulcScriptLoader.load(CfSimpleTransferValidator.class,
                new BytesPlutusData(receiverPkh));
        var scriptAddr = AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();

        var backend = YaciHelper.createBackendService();
        var quickTx = new QuickTxBuilder(backend);

        // Step 1: Lock 10 ADA at the script
        System.out.println("Step 1: Locking 10 ADA at script address...");
        var datum = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of())
                .build();

        var lockTx = new Tx()
                .payToContract(scriptAddr, Amount.ada(10), datum)
                .from(sender.baseAddress());

        var lockResult = quickTx.compose(lockTx)
                .withSigner(SignerProviders.signerFrom(sender))
                .complete();

        if (!lockResult.isSuccessful()) {
            System.out.println("Lock failed: " + lockResult);
            System.exit(1);
        }
        var lockTxHash = lockResult.getValue();
        YaciHelper.waitForConfirmation(backend, lockTxHash);
        System.out.println("Locked! Tx: " + lockTxHash);

        // Step 2: Receiver unlocks
        System.out.println("Step 2: Receiver unlocking...");
        var scriptUtxo = YaciHelper.findUtxo(backend, scriptAddr, lockTxHash);
        var redeemer = ConstrPlutusData.of(0);

        var unlockTx = new ScriptTx()
                .collectFrom(scriptUtxo, redeemer)
                .payToAddress(receiver.baseAddress(), Amount.ada(5))
                .attachSpendingValidator(script);

        var unlockResult = quickTx.compose(unlockTx)
                .withSigner(SignerProviders.signerFrom(receiver))
                .feePayer(receiver.baseAddress())
                .collateralPayer(receiver.baseAddress())
                .withRequiredSigners(receiverPkh)
                .complete();

        if (!unlockResult.isSuccessful()) {
            System.out.println("Unlock failed: " + unlockResult);
            System.exit(1);
        }
        YaciHelper.waitForConfirmation(backend, unlockResult.getValue());
        System.out.println("Unlocked! Tx: " + unlockResult.getValue());
        System.out.println("SimpleTransfer demo completed successfully!");
    }
}
