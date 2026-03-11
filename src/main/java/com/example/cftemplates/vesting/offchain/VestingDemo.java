package com.example.cftemplates.vesting.offchain;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.example.cftemplates.vesting.onchain.CfVestingValidator;
import com.example.offchain.YaciHelper;

import java.math.BigInteger;

/**
 * Off-chain demo for CfVestingValidator.
 * Locks ADA with a vesting datum, then the beneficiary unlocks after lock time.
 */
public class VestingDemo {

    public static void main(String[] args) throws Exception {
        if (!YaciHelper.isYaciReachable()) {
            System.out.println("Yaci DevKit not reachable. Skipping demo.");
            System.exit(1);
        }

        var owner = new Account(Networks.testnet());
        var beneficiary = new Account(Networks.testnet());
        byte[] ownerPkh = owner.hdKeyPair().getPublicKey().getKeyHash();
        byte[] beneficiaryPkh = beneficiary.hdKeyPair().getPublicKey().getKeyHash();

        YaciHelper.topUp(owner.baseAddress(), 1000);
        YaciHelper.topUp(beneficiary.baseAddress(), 1000);

        // Lock time in the past so beneficiary can immediately unlock in demo
        BigInteger lockUntil = BigInteger.valueOf(System.currentTimeMillis() - 60_000);

        var script = JulcScriptLoader.load(CfVestingValidator.class);
        var scriptAddr = AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();

        var backend = YaciHelper.createBackendService();
        var quickTx = new QuickTxBuilder(backend);

        // Step 1: Owner locks 10 ADA with vesting datum
        System.out.println("Step 1: Owner locking 10 ADA with vesting datum...");
        var datum = PlutusDataAdapter.convert(new CfVestingValidator.VestingDatum(
                lockUntil, ownerPkh, beneficiaryPkh));

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

        // Step 2: Beneficiary unlocks (lock time is in the past)
        System.out.println("Step 2: Beneficiary unlocking...");
        var scriptUtxo = YaciHelper.findUtxo(backend, scriptAddr, lockTxHash);
        var redeemer = ConstrPlutusData.of(0);

        // Get current slot for validFrom
        var latestBlock = backend.getBlockService().getLatestBlock();
        long currentSlot = latestBlock.getValue().getSlot();

        var unlockTx = new ScriptTx()
                .collectFrom(scriptUtxo, redeemer)
                .payToAddress(beneficiary.baseAddress(), Amount.ada(5))
                .attachSpendingValidator(script);

        var unlockResult = quickTx.compose(unlockTx)
                .withSigner(SignerProviders.signerFrom(beneficiary))
                .feePayer(beneficiary.baseAddress())
                .collateralPayer(beneficiary.baseAddress())
                .withRequiredSigners(beneficiaryPkh)
                .validFrom(currentSlot)
                .complete();

        if (!unlockResult.isSuccessful()) {
            System.out.println("Unlock failed: " + unlockResult);
            System.exit(1);
        }
        YaciHelper.waitForConfirmation(backend, unlockResult.getValue());
        System.out.println("Unlocked! Tx: " + unlockResult.getValue());
        System.out.println("Vesting demo completed successfully!");
    }
}
