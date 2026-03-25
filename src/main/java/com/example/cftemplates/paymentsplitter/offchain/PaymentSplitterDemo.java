package com.example.cftemplates.paymentsplitter.offchain;

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
import com.example.cftemplates.paymentsplitter.onchain.CfPaymentSplitterValidator;
import com.example.offchain.YaciHelper;

/**
 * Off-chain demo for CfPaymentSplitterValidator.
 * Locks ADA, then distributes equally among payees.
 */
public class PaymentSplitterDemo {

    public static void main(String[] args) throws Exception {
        if (!YaciHelper.isYaciReachable()) {
            System.out.println("Yaci DevKit not reachable. Skipping demo.");
            System.exit(1);
        }

        var funder = new Account(Networks.testnet());
        var payee1 = new Account(Networks.testnet());
        var payee2 = new Account(Networks.testnet());
        byte[] pkh1 = payee1.hdKeyPair().getPublicKey().getKeyHash();
        byte[] pkh2 = payee2.hdKeyPair().getPublicKey().getKeyHash();

        YaciHelper.topUp(funder.baseAddress(), 1000);
        YaciHelper.topUp(payee1.baseAddress(), 1000);

        // Payees list as Plutus List<BData>
        var payeesList = ListPlutusData.of(
                new BytesPlutusData(pkh1),
                new BytesPlutusData(pkh2));

        var script = JulcScriptLoader.load(CfPaymentSplitterValidator.class, payeesList);
        var scriptAddr = AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();

        var backend = YaciHelper.createBackendService();
        var quickTx = new QuickTxBuilder(backend);

        // Step 1: Lock 20 ADA
        System.out.println("Step 1: Locking 20 ADA...");
        var datum = ConstrPlutusData.builder()
                .alternative(1) // None
                .data(ListPlutusData.of())
                .build();

        var lockTx = new Tx()
                .payToContract(scriptAddr, Amount.ada(20), datum)
                .from(funder.baseAddress());

        var lockResult = quickTx.compose(lockTx)
                .withSigner(SignerProviders.signerFrom(funder))
                .complete();

        if (!lockResult.isSuccessful()) {
            System.out.println("Lock failed: " + lockResult);
            System.exit(1);
        }
        var lockTxHash = lockResult.getValue();
        YaciHelper.waitForConfirmation(backend, lockTxHash);
        System.out.println("Locked! Tx: " + lockTxHash);

        // Step 2: Split — each payee gets 10 ADA
        System.out.println("Step 2: Splitting funds...");
        var scriptUtxo = YaciHelper.findUtxo(backend, scriptAddr, lockTxHash);
        var redeemer = ConstrPlutusData.of(0);

        var splitTx = new ScriptTx()
                .collectFrom(scriptUtxo, redeemer)
                .payToAddress(payee1.baseAddress(), Amount.ada(10))
                .payToAddress(payee2.baseAddress(), Amount.ada(10))
                .attachSpendingValidator(script);

        // payee1 must have a wallet UTXO as input for the validator's
        // net calculation (outputSum - (inputSum - fee)) to work correctly.
        var feeTx = new Tx()
                .payToAddress(payee1.baseAddress(), Amount.ada(2))
                .from(payee1.baseAddress());

        var splitResult = quickTx.compose(splitTx, feeTx)
                .withSigner(SignerProviders.signerFrom(payee1))
                .feePayer(payee1.baseAddress())
                .collateralPayer(payee1.baseAddress())
                .withTxEvaluator(YaciHelper.julcEvaluator(backend))
                .complete();

        if (!splitResult.isSuccessful()) {
            System.out.println("Split failed: " + splitResult);
            System.exit(1);
        }
        YaciHelper.waitForConfirmation(backend, splitResult.getValue());
        System.out.println("Split! Tx: " + splitResult.getValue());
        System.out.println("Payment splitter demo completed successfully!");
    }
}
