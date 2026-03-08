package com.example.cftemplates.crowdfund.offchain;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.*;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.example.cftemplates.crowdfund.onchain.CfCrowdfundValidator;
import com.example.offchain.YaciHelper;

import java.math.BigInteger;

/**
 * Off-chain demo for CfCrowdfundValidator.
 * Sets up a crowdfund, donates, then beneficiary withdraws after deadline.
 */
public class CrowdfundDemo {

    public static void main(String[] args) throws Exception {
        if (!YaciHelper.isYaciReachable()) {
            System.out.println("Yaci DevKit not reachable. Skipping demo.");
            System.exit(1);
        }

        var donor = new Account(Networks.testnet());
        var beneficiary = new Account(Networks.testnet());
        byte[] beneficiaryPkh = beneficiary.hdKeyPair().getPublicKey().getKeyHash();
        byte[] donorPkh = donor.hdKeyPair().getPublicKey().getKeyHash();

        // Deadline in the past so beneficiary can withdraw immediately
        BigInteger deadline = BigInteger.valueOf(System.currentTimeMillis() - 60_000);
        BigInteger goal = BigInteger.valueOf(5_000_000); // 5 ADA in lovelace

        YaciHelper.topUp(donor.baseAddress(), 1000);
        YaciHelper.topUp(beneficiary.baseAddress(), 1000);

        var script = JulcScriptLoader.load(CfCrowdfundValidator.class,
                new BytesPlutusData(beneficiaryPkh),
                BigIntPlutusData.of(goal),
                BigIntPlutusData.of(deadline));
        var scriptAddr = AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();

        var backend = YaciHelper.createBackendService();
        var quickTx = new QuickTxBuilder(backend);

        // Step 1: Donate 10 ADA (lock at script with wallets datum)
        System.out.println("Step 1: Donating 10 ADA...");
        // CrowdfundDatum = Constr(0, [Map{donor -> 10_000_000}])
        var walletsMap = MapPlutusData.builder()
                .map(java.util.Map.of(
                        (com.bloxbean.cardano.client.plutus.spec.PlutusData) new BytesPlutusData(donorPkh),
                        (com.bloxbean.cardano.client.plutus.spec.PlutusData) BigIntPlutusData.of(10_000_000)))
                .build();
        var datum = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(walletsMap))
                .build();

        var lockTx = new Tx()
                .payToContract(scriptAddr, Amount.ada(10), datum)
                .from(donor.baseAddress());

        var lockResult = quickTx.compose(lockTx)
                .withSigner(SignerProviders.signerFrom(donor))
                .complete();

        if (!lockResult.isSuccessful()) {
            System.out.println("Donate failed: " + lockResult);
            System.exit(1);
        }
        var lockTxHash = lockResult.getValue();
        YaciHelper.waitForConfirmation(backend, lockTxHash);
        System.out.println("Donated! Tx: " + lockTxHash);

        // Step 2: Beneficiary withdraws (deadline passed, goal met)
        System.out.println("Step 2: Beneficiary withdrawing...");
        var scriptUtxo = YaciHelper.findUtxo(backend, scriptAddr, lockTxHash);

        // Withdraw = tag 1 (Donate=0, Withdraw=1, Reclaim=2)
        var redeemer = ConstrPlutusData.of(1);

        var latestBlock = backend.getBlockService().getLatestBlock();
        long currentSlot = latestBlock.getValue().getSlot();

        var withdrawTx = new ScriptTx()
                .collectFrom(scriptUtxo, redeemer)
                .payToAddress(beneficiary.baseAddress(), Amount.ada(5))
                .attachSpendingValidator(script);

        var withdrawResult = quickTx.compose(withdrawTx)
                .withSigner(SignerProviders.signerFrom(beneficiary))
                .feePayer(beneficiary.baseAddress())
                .collateralPayer(beneficiary.baseAddress())
                .withRequiredSigners(beneficiaryPkh)
                .validFrom(currentSlot)
                .complete();

        if (!withdrawResult.isSuccessful()) {
            System.out.println("Withdraw failed: " + withdrawResult);
            System.exit(1);
        }
        YaciHelper.waitForConfirmation(backend, withdrawResult.getValue());
        System.out.println("Withdrawn! Tx: " + withdrawResult.getValue());
        System.out.println("Crowdfund demo completed successfully!");
    }
}
