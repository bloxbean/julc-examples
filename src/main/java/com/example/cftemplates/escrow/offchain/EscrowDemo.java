package com.example.cftemplates.escrow.offchain;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.example.cftemplates.escrow.onchain.CfEscrowValidator;
import com.example.offchain.YaciHelper;

/**
 * Off-chain demo for CfEscrowValidator.
 * Initiator locks ADA → Recipient deposits → Both complete trade (swap).
 */
public class EscrowDemo {

    public static void main(String[] args) throws Exception {
        if (!YaciHelper.isYaciReachable()) {
            System.out.println("Yaci DevKit not reachable. Skipping demo.");
            System.exit(1);
        }

        var initiator = new Account(Networks.testnet());
        var recipient = new Account(Networks.testnet());
        byte[] initiatorPkh = initiator.hdKeyPair().getPublicKey().getKeyHash();
        byte[] recipientPkh = recipient.hdKeyPair().getPublicKey().getKeyHash();

        java.math.BigInteger initiatorAmt = java.math.BigInteger.valueOf(10_000_000);
        java.math.BigInteger recipientAmt = java.math.BigInteger.valueOf(5_000_000);

        YaciHelper.topUp(initiator.baseAddress(), 1000);
        YaciHelper.topUp(recipient.baseAddress(), 1000);

        var script = JulcScriptLoader.load(CfEscrowValidator.class);
        var scriptAddr = AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();

        var backend = YaciHelper.createBackendService();
        var quickTx = new QuickTxBuilder(backend);

        // Step 1: Initiator locks ADA with Initiation datum (tag 0)
        System.out.println("Step 1: Initiator locking 10 ADA...");
        var initiationDatum = PlutusDataAdapter.convert(new CfEscrowValidator.Initiation(
                initiatorPkh, initiatorAmt));

        var lockTx = new Tx()
                .payToContract(scriptAddr, Amount.lovelace(initiatorAmt), initiationDatum)
                .from(initiator.baseAddress());

        var lockResult = quickTx.compose(lockTx)
                .withSigner(SignerProviders.signerFrom(initiator))
                .complete();

        if (!lockResult.isSuccessful()) {
            System.out.println("Lock failed: " + lockResult);
            System.exit(1);
        }
        var lockTxHash = lockResult.getValue();
        YaciHelper.waitForConfirmation(backend, lockTxHash);
        System.out.println("Locked! Tx: " + lockTxHash);

        // Step 2: Recipient deposits, transitioning to ActiveEscrow
        System.out.println("Step 2: Recipient depositing 5 ADA...");
        var scriptUtxo = YaciHelper.findUtxo(backend, scriptAddr, lockTxHash);

        // RecipientDeposit = tag 0
        var depositRedeemer = PlutusDataAdapter.convert(new CfEscrowValidator.RecipientDeposit(
                recipientPkh, recipientAmt));

        // ActiveEscrow datum (tag 1)
        var activeEscrowDatum = PlutusDataAdapter.convert(new CfEscrowValidator.ActiveEscrow(
                initiatorPkh, initiatorAmt, recipientPkh, recipientAmt));

        var depositTx = new ScriptTx()
                .collectFrom(scriptUtxo, depositRedeemer)
                .payToContract(scriptAddr, Amount.lovelace(initiatorAmt.add(recipientAmt)), activeEscrowDatum)
                .attachSpendingValidator(script);

        var depositResult = quickTx.compose(depositTx)
                .withSigner(SignerProviders.signerFrom(recipient))
                .feePayer(recipient.baseAddress())
                .collateralPayer(recipient.baseAddress())
                .complete();

        if (!depositResult.isSuccessful()) {
            System.out.println("Deposit failed: " + depositResult);
            System.exit(1);
        }
        var depositTxHash = depositResult.getValue();
        YaciHelper.waitForConfirmation(backend, depositTxHash);
        System.out.println("Deposited! Tx: " + depositTxHash);

        // Step 3: Both sign to complete trade (swap)
        System.out.println("Step 3: Completing trade (both sign)...");
        var activeUtxo = YaciHelper.findUtxo(backend, scriptAddr, depositTxHash);

        // CompleteTrade = tag 2
        var completeRedeemer = PlutusDataAdapter.convert(new CfEscrowValidator.CompleteTrade());

        var completeTx = new ScriptTx()
                .collectFrom(activeUtxo, completeRedeemer)
                .payToAddress(initiator.baseAddress(), Amount.lovelace(recipientAmt))
                .payToAddress(recipient.baseAddress(), Amount.lovelace(initiatorAmt))
                .attachSpendingValidator(script);

        // Fee payer (initiator) also receives recipientAmt from the script.
        // Add a companion Tx to force inclusion of the initiator's wallet UTXO,
        // so the fee is deducted from the wallet change output instead of the payout.
        var feeTx = new Tx()
                .payToAddress(initiator.baseAddress(), Amount.ada(1))
                .from(initiator.baseAddress());

        var completeResult = quickTx.compose(completeTx, feeTx)
                .withSigner(SignerProviders.signerFrom(initiator))
                .withSigner(SignerProviders.signerFrom(recipient))
                .feePayer(initiator.baseAddress())
                .collateralPayer(initiator.baseAddress())
                .withRequiredSigners(initiatorPkh)
                .withRequiredSigners(recipientPkh)
                .complete();

        if (!completeResult.isSuccessful()) {
            System.out.println("Complete trade failed: " + completeResult);
            System.exit(1);
        }
        YaciHelper.waitForConfirmation(backend, completeResult.getValue());
        System.out.println("Trade completed! Tx: " + completeResult.getValue());
        System.out.println("Escrow demo completed successfully!");
    }
}
