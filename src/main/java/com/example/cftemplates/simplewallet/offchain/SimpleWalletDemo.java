package com.example.cftemplates.simplewallet.offchain;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.example.cftemplates.simplewallet.onchain.CfSimpleWalletValidator;
import com.example.cftemplates.simplewallet.onchain.CfWalletFundsValidator;
import com.example.offchain.YaciHelper;

import java.math.BigInteger;
import java.util.List;

/**
 * Off-chain demo for CfSimpleWalletValidator + CfWalletFundsValidator.
 * Step 1: Deposit ADA to funds script, mint intent marker.
 * Step 2: Execute payment (spend funds + intent, burn marker, pay recipient).
 */
public class SimpleWalletDemo {

    public static void main(String[] args) throws Exception {
        if (!YaciHelper.isYaciReachable()) {
            System.out.println("Yaci DevKit not reachable. Skipping demo.");
            System.exit(1);
        }

        Account owner = new Account(Networks.testnet());
        Account recipient = new Account(Networks.testnet());
        byte[] ownerPkh = owner.hdKeyPair().getPublicKey().getKeyHash();

        YaciHelper.topUp(owner.baseAddress(), 1000);
        YaciHelper.topUp(recipient.baseAddress(), 1000);

        BackendService backend = YaciHelper.createBackendService();
        QuickTxBuilder quickTx = new QuickTxBuilder(backend);

        PlutusScript walletScript = JulcScriptLoader.load(CfSimpleWalletValidator.class,
                new BytesPlutusData(ownerPkh));
        String walletScriptAddr = AddressProvider.getEntAddress(walletScript, Networks.testnet()).toBech32();
        byte[] walletPolicyIdBytes = walletScript.getScriptHash();
        System.out.println("Wallet script policy: " + HexUtil.encodeHexString(walletPolicyIdBytes));

        PlutusScript fundsScript = JulcScriptLoader.load(CfWalletFundsValidator.class,
                new BytesPlutusData(ownerPkh),
                new BytesPlutusData(walletPolicyIdBytes));
        String fundsScriptAddr = AddressProvider.getEntAddress(fundsScript, Networks.testnet()).toBech32();
        System.out.println("Funds script addr: " + fundsScriptAddr);

        // Step 0: Deposit ADA to funds script
        String depositTxHash = deposit(quickTx, owner, fundsScriptAddr, backend);

        // Step 1: Mint intent marker with PaymentIntent datum
        String mintTxHash = mintIntent(quickTx, owner, recipient, walletScript,
                walletScriptAddr, ownerPkh, backend);

        System.out.println("Deposit tx: " + depositTxHash);
        System.out.println("Mint tx: " + mintTxHash);
        System.out.println("Simple wallet demo completed (step 1). See integration test for full flow.");
    }

    static String deposit(QuickTxBuilder quickTx, Account owner, String fundsScriptAddr,
                          BackendService backend) throws Exception {
        System.out.println("Depositing 50 ADA into funds script...");
        ConstrPlutusData noneDatum = ConstrPlutusData.builder()
                .alternative(1)
                .data(ListPlutusData.of())
                .build();

        Tx depositTx = new Tx()
                .payToContract(fundsScriptAddr, Amount.ada(50), noneDatum)
                .from(owner.baseAddress());

        var depositResult = quickTx.compose(depositTx)
                .withSigner(SignerProviders.signerFrom(owner))
                .complete();

        if (!depositResult.isSuccessful()) {
            throw new RuntimeException("Deposit failed: " + depositResult);
        }
        String txHash = depositResult.getValue();
        YaciHelper.waitForConfirmation(backend, txHash);
        System.out.println("Deposited! Tx: " + txHash);
        return txHash;
    }

    static String mintIntent(QuickTxBuilder quickTx, Account owner, Account recipient,
                             PlutusScript walletScript, String walletScriptAddr,
                             byte[] ownerPkh, BackendService backend) throws Exception {
        System.out.println("Step 1: Minting payment intent...");

        BigInteger paymentAmount = BigInteger.valueOf(20_000_000);
        String intentTokenHex = "0x" + HexUtil.encodeHexString("INTENT_MARKER".getBytes());
        Asset intentAsset = new Asset(intentTokenHex, BigInteger.ONE);
        var mintRedeemer = PlutusDataAdapter.convert(new CfSimpleWalletValidator.MintIntent());

        byte[] recipientPkh = recipient.hdKeyPair().getPublicKey().getKeyHash();
        ConstrPlutusData recipientCredential = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(new BytesPlutusData(recipientPkh)))
                .build();
        ConstrPlutusData noStake = ConstrPlutusData.builder()
                .alternative(1)
                .data(ListPlutusData.of())
                .build();
        ConstrPlutusData recipientAddrData = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(recipientCredential, noStake))
                .build();

        // PaymentIntent.recipient is JuLC PlutusData (not CCL ConstrPlutusData),
        // so we construct the full datum manually.
        var paymentIntent = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(recipientAddrData, BigIntPlutusData.of(paymentAmount), new BytesPlutusData(new byte[0])))
                .build();

        ScriptTx mintIntentTx = new ScriptTx()
                .mintAsset(walletScript, List.of(intentAsset), mintRedeemer, walletScriptAddr, paymentIntent);

        var mintResult = quickTx.compose(mintIntentTx)
                .withSigner(SignerProviders.signerFrom(owner))
                .feePayer(owner.baseAddress())
                .collateralPayer(owner.baseAddress())
                .withRequiredSigners(ownerPkh)
                .ignoreScriptCostEvaluationError(true)
                .withTxEvaluator(YaciHelper.julcEvaluator(backend))
                .complete();

        if (!mintResult.isSuccessful()) {
            throw new RuntimeException("Mint intent failed: " + mintResult);
        }
        String txHash = mintResult.getValue();
        YaciHelper.waitForConfirmation(backend, txHash);
        System.out.println("Intent minted! Tx: " + txHash);
        return txHash;
    }
}
