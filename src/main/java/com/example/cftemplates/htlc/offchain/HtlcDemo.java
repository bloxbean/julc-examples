package com.example.cftemplates.htlc.offchain;

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
import com.example.cftemplates.htlc.onchain.CfHtlcValidator;
import com.example.offchain.YaciHelper;

import java.math.BigInteger;
import java.security.MessageDigest;

/**
 * Off-chain demo for CfHtlcValidator.
 * Locks ADA with a hash-locked contract, then guesses the secret to unlock.
 */
public class HtlcDemo {

    public static void main(String[] args) throws Exception {
        if (!YaciHelper.isYaciReachable()) {
            System.out.println("Yaci DevKit not reachable. Skipping demo.");
            System.exit(1);
        }

        var owner = new Account(Networks.testnet());
        var claimer = new Account(Networks.testnet());
        byte[] ownerPkh = owner.hdKeyPair().getPublicKey().getKeyHash();

        YaciHelper.topUp(owner.baseAddress(), 1000);
        YaciHelper.topUp(claimer.baseAddress(), 1000);

        // Secret and its hash
        byte[] secretAnswer = "my_secret_answer".getBytes();
        byte[] secretHash = MessageDigest.getInstance("SHA-256").digest(secretAnswer);

        // Expiration far in the future
        BigInteger expiration = BigInteger.valueOf(System.currentTimeMillis() + 3_600_000); // +1 hour

        var script = JulcScriptLoader.load(CfHtlcValidator.class,
                new BytesPlutusData(secretHash),
                BigIntPlutusData.of(expiration),
                new BytesPlutusData(ownerPkh));
        var scriptAddr = AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();

        var backend = YaciHelper.createBackendService();
        var quickTx = new QuickTxBuilder(backend);

        // Step 1: Lock 10 ADA
        System.out.println("Step 1: Locking 10 ADA with HTLC...");
        var datum = ConstrPlutusData.builder()
                .alternative(0)
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

        // Step 2: Claimer guesses the secret before expiration
        System.out.println("Step 2: Claimer guessing secret...");
        var scriptUtxo = YaciHelper.findUtxo(backend, scriptAddr, lockTxHash);

        // Guess redeemer = Constr(0, [BData(answer)])
        var redeemer = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(new BytesPlutusData(secretAnswer)))
                .build();

        // Get current slot for validTo (before expiration)
        var latestBlock = backend.getBlockService().getLatestBlock();
        long currentSlot = latestBlock.getValue().getSlot();

        var unlockTx = new ScriptTx()
                .collectFrom(scriptUtxo, redeemer)
                .payToAddress(claimer.baseAddress(), Amount.ada(5))
                .attachSpendingValidator(script);

        var unlockResult = quickTx.compose(unlockTx)
                .withSigner(SignerProviders.signerFrom(claimer))
                .feePayer(claimer.baseAddress())
                .collateralPayer(claimer.baseAddress())
                .validTo(currentSlot + 200)
                .complete();

        if (!unlockResult.isSuccessful()) {
            System.out.println("Unlock failed: " + unlockResult);
            System.exit(1);
        }
        YaciHelper.waitForConfirmation(backend, unlockResult.getValue());
        System.out.println("Unlocked! Tx: " + unlockResult.getValue());
        System.out.println("HTLC demo completed successfully!");
    }
}
