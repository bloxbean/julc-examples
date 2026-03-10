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
 * 3-step flow: Initialize → Donate (via ScriptTx) → Withdraw.
 */
public class CrowdfundDemo {

    public static void main(String[] args) throws Exception {
        if (!YaciHelper.isYaciReachable()) {
            System.out.println("Yaci DevKit not reachable. Skipping demo.");
            System.exit(1);
        }

        var donor1 = new Account(Networks.testnet());
        var donor2 = new Account(Networks.testnet());
        var beneficiary = new Account(Networks.testnet());
        byte[] beneficiaryPkh = beneficiary.hdKeyPair().getPublicKey().getKeyHash();
        byte[] donor1Pkh = donor1.hdKeyPair().getPublicKey().getKeyHash();
        byte[] donor2Pkh = donor2.hdKeyPair().getPublicKey().getKeyHash();

        // Deadline in the past so beneficiary can withdraw immediately
        BigInteger deadline = BigInteger.valueOf(System.currentTimeMillis() - 60_000);
        BigInteger goal = BigInteger.valueOf(5_000_000); // 5 ADA in lovelace

        YaciHelper.topUp(donor1.baseAddress(), 1000);
        YaciHelper.topUp(donor2.baseAddress(), 1000);
        YaciHelper.topUp(beneficiary.baseAddress(), 1000);

        var script = JulcScriptLoader.load(CfCrowdfundValidator.class,
                new BytesPlutusData(beneficiaryPkh),
                BigIntPlutusData.of(goal),
                BigIntPlutusData.of(deadline));
        var scriptAddr = AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();

        var backend = YaciHelper.createBackendService();
        var quickTx = new QuickTxBuilder(backend);

        // Step 1: Initialize — donor1 sends 5 ADA to script (plain Tx, no script execution)
        System.out.println("Step 1: Initializing crowdfund with 5 ADA from donor1...");
        var initWallets = MapPlutusData.builder().build();
        initWallets.put(new BytesPlutusData(donor1Pkh), BigIntPlutusData.of(5_000_000));
        var initDatum = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(initWallets))
                .build();

        var initTx = new Tx()
                .payToContract(scriptAddr, Amount.ada(5), initDatum)
                .from(donor1.baseAddress());

        var initResult = quickTx.compose(initTx)
                .withSigner(SignerProviders.signerFrom(donor1))
                .complete();

        if (!initResult.isSuccessful()) {
            System.out.println("Initialize failed: " + initResult);
            System.exit(1);
        }
        var initTxHash = initResult.getValue();
        YaciHelper.waitForConfirmation(backend, initTxHash);
        System.out.println("Initialized! Tx: " + initTxHash);

        // Step 2: Donate — donor2 donates 5 ADA via ScriptTx (spends existing UTXO, creates new one)
        System.out.println("Step 2: Donor2 donating 5 ADA via ScriptTx...");
        var scriptUtxo = YaciHelper.findUtxo(backend, scriptAddr, initTxHash);

        // Updated datum: both donors in the wallets map
        var updatedWallets = MapPlutusData.builder().build();
        updatedWallets.put(new BytesPlutusData(donor1Pkh), BigIntPlutusData.of(5_000_000));
        updatedWallets.put(new BytesPlutusData(donor2Pkh), BigIntPlutusData.of(5_000_000));
        var updatedDatum = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(updatedWallets))
                .build();

        // DONATE redeemer = tag 0
        var donateTx = new ScriptTx()
                .collectFrom(scriptUtxo, ConstrPlutusData.of(0))
                .payToContract(scriptAddr, Amount.ada(10), updatedDatum)
                .attachSpendingValidator(script);

        var donateResult = quickTx.compose(donateTx)
                .withSigner(SignerProviders.signerFrom(donor2))
                .feePayer(donor2.baseAddress())
                .collateralPayer(donor2.baseAddress())
                .complete();

        if (!donateResult.isSuccessful()) {
            System.out.println("Donate failed: " + donateResult);
            System.exit(1);
        }
        var donateTxHash = donateResult.getValue();
        YaciHelper.waitForConfirmation(backend, donateTxHash);
        System.out.println("Donated! Tx: " + donateTxHash);

        // Step 3: Beneficiary withdraws (deadline passed, goal met with 10 ADA > 5 ADA goal)
        System.out.println("Step 3: Beneficiary withdrawing...");
        var withdrawUtxo = YaciHelper.findUtxo(backend, scriptAddr, donateTxHash);

        var latestBlock = backend.getBlockService().getLatestBlock();
        long currentSlot = latestBlock.getValue().getSlot();

        // WITHDRAW redeemer = tag 1
        var withdrawTx = new ScriptTx()
                .collectFrom(withdrawUtxo, ConstrPlutusData.of(1))
                .payToAddress(beneficiary.baseAddress(), Amount.ada(10))
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
