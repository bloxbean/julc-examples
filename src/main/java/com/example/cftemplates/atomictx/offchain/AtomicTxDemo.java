package com.example.cftemplates.atomictx.offchain;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.example.cftemplates.atomictx.onchain.CfAtomicTxValidator;
import com.example.offchain.YaciHelper;

import java.math.BigInteger;
import java.util.List;

/**
 * Off-chain demo for CfAtomicTxValidator.
 * Demonstrates atomic transaction: a wrong mint password causes the entire tx to fail,
 * even though the spend validator always succeeds.
 */
public class AtomicTxDemo {

    public static void main(String[] args) throws Exception {
        if (!YaciHelper.isYaciReachable()) {
            System.out.println("Yaci DevKit not reachable. Skipping demo.");
            System.exit(1);
        }

        var account = new Account(Networks.testnet());
        YaciHelper.topUp(account.baseAddress(), 1000);

        var script = JulcScriptLoader.load(CfAtomicTxValidator.class);
        var scriptAddr = AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();
        var policyId = script.getPolicyId();

        var backend = YaciHelper.createBackendService();
        var quickTx = new QuickTxBuilder(backend);

        // Step 1: Lock ADA at the script
        System.out.println("Step 1: Locking 5 ADA at script...");
        var datum = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of())
                .build();

        var lockTx = new Tx()
                .payToContract(scriptAddr, Amount.ada(5), datum)
                .from(account.baseAddress());

        var lockResult = quickTx.compose(lockTx)
                .withSigner(SignerProviders.signerFrom(account))
                .complete();

        if (!lockResult.isSuccessful()) {
            System.out.println("Lock failed: " + lockResult);
            System.exit(1);
        }
        var lockTxHash = lockResult.getValue();
        YaciHelper.waitForConfirmation(backend, lockTxHash);
        System.out.println("Locked! Tx: " + lockTxHash);

        // Step 2: Try spend + mint with WRONG password (should fail atomically)
        System.out.println("Step 2: Attempting spend + mint with wrong password...");
        var scriptUtxo = YaciHelper.findUtxo(backend, scriptAddr, lockTxHash);
        var spendRedeemer = ConstrPlutusData.of(0);
        var wrongMintRedeemer = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(new BytesPlutusData("wrong_password".getBytes())))
                .build();

        var tokenName = "0x" + "54657374"; // "Test" in hex
        var asset = new Asset(tokenName, BigInteger.ONE);

        var failTx = new ScriptTx()
                .collectFrom(scriptUtxo, spendRedeemer)
                .mintAsset(script, List.of(asset), wrongMintRedeemer, account.baseAddress())
                .payToAddress(account.baseAddress(), Amount.ada(2))
                .attachSpendingValidator(script);

        var failResult = quickTx.compose(failTx)
                .withSigner(SignerProviders.signerFrom(account))
                .feePayer(account.baseAddress())
                .collateralPayer(account.baseAddress())
                .ignoreScriptCostEvaluationError(true)
                .withTxEvaluator(YaciHelper.julcEvaluator(backend))
                .complete();

        System.out.println("Wrong password result: " + (failResult.isSuccessful() ? "UNEXPECTED SUCCESS" : "Failed as expected"));

        // Step 3: Try spend + mint with CORRECT password (should succeed)
        System.out.println("Step 3: Attempting spend + mint with correct password...");
        // Re-fetch UTXO (it should still be there since step 2 failed)
        scriptUtxo = YaciHelper.findUtxo(backend, scriptAddr, lockTxHash);
        var correctMintRedeemer = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(new BytesPlutusData("super_secret_password".getBytes())))
                .build();

        // mintAsset 4-arg sends the minted token explicitly to account.baseAddress()
        var successTx = new ScriptTx()
                .collectFrom(scriptUtxo, spendRedeemer)
                .mintAsset(script, List.of(asset), correctMintRedeemer, account.baseAddress())
                .payToAddress(account.baseAddress(), Amount.ada(2))
                .attachSpendingValidator(script);

        var successResult = quickTx.compose(successTx)
                .withSigner(SignerProviders.signerFrom(account))
                .feePayer(account.baseAddress())
                .collateralPayer(account.baseAddress())
                .ignoreScriptCostEvaluationError(true)
                .withTxEvaluator(YaciHelper.julcEvaluator(backend))
                .complete();

        if (!successResult.isSuccessful()) {
            System.out.println("Correct password also failed: " + successResult);
            System.exit(1);
        }
        YaciHelper.waitForConfirmation(backend, successResult.getValue());
        System.out.println("Success! Tx: " + successResult.getValue());
        System.out.println("AtomicTransaction demo completed successfully!");
    }
}
