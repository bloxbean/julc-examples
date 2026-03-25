package com.example.cftemplates.tokentransfer.offchain;

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
import com.example.cftemplates.tokentransfer.onchain.CfTokenTransferValidator;
import com.example.offchain.YaciHelper;

/**
 * Off-chain demo for CfTokenTransferValidator.
 * Locks ADA at script, then receiver signs to unlock.
 */
public class TokenTransferDemo {

    public static void main(String[] args) throws Exception {
        if (!YaciHelper.isYaciReachable()) {
            System.out.println("Yaci DevKit not reachable. Skipping demo.");
            System.exit(1);
        }

        var owner = new Account(Networks.testnet());
        var receiver = new Account(Networks.testnet());
        byte[] receiverPkh = receiver.hdKeyPair().getPublicKey().getKeyHash();
        byte[] fakePolicy = new byte[28]; // no policy in input → escape hatch allows spending
        byte[] fakeAssetName = "Token".getBytes();

        YaciHelper.topUp(owner.baseAddress(), 1000);
        YaciHelper.topUp(receiver.baseAddress(), 1000);

        var script = JulcScriptLoader.load(CfTokenTransferValidator.class,
                new BytesPlutusData(receiverPkh),
                new BytesPlutusData(fakePolicy),
                new BytesPlutusData(fakeAssetName));
        var scriptAddr = AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();

        var backend = YaciHelper.createBackendService();
        var quickTx = new QuickTxBuilder(backend);

        // Step 1: Lock 10 ADA
        System.out.println("Step 1: Locking 10 ADA...");
        var datum = ConstrPlutusData.builder()
                .alternative(1) // None
                .data(ListPlutusData.of())
                .build();

        var lockTx = new Tx()
                .payToContract(scriptAddr, Amount.ada(10), datum)
                .from(owner.baseAddress());

        var lockResult = quickTx.compose(lockTx)
                .withSigner(SignerProviders.signerFrom(owner))
                .complete();

        if (!lockResult.isSuccessful()) {
            System.out.println("Lock failed: " + lockResult);
            System.exit(1);
        }
        var lockTxHash = lockResult.getValue();
        YaciHelper.waitForConfirmation(backend, lockTxHash);
        System.out.println("Locked! Tx: " + lockTxHash);

        // Step 2: Receiver unlocks (no policy in input → escape hatch)
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
                .withTxEvaluator(YaciHelper.julcEvaluator(backend))
                .complete();

        if (!unlockResult.isSuccessful()) {
            System.out.println("Unlock failed: " + unlockResult);
            System.exit(1);
        }
        YaciHelper.waitForConfirmation(backend, unlockResult.getValue());
        System.out.println("Unlocked! Tx: " + unlockResult.getValue());
        System.out.println("Token transfer demo completed successfully!");
    }
}
