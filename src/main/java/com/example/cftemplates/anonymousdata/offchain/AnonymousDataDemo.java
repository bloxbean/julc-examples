package com.example.cftemplates.anonymousdata.offchain;

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
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.example.cftemplates.anonymousdata.onchain.CfAnonymousDataValidator;
import com.example.offchain.YaciHelper;

import java.math.BigInteger;
import java.util.List;

/**
 * Off-chain demo for CfAnonymousDataValidator.
 * Commit: mint token with blake2b(pkh||nonce) as asset name.
 * Reveal: spend by providing nonce, proving ownership.
 */
public class AnonymousDataDemo {

    public static void main(String[] args) throws Exception {
        if (!YaciHelper.isYaciReachable()) {
            System.out.println("Yaci DevKit not reachable. Skipping demo.");
            System.exit(1);
        }

        var committer = new Account(Networks.testnet());
        byte[] committerPkh = committer.hdKeyPair().getPublicKey().getKeyHash();
        byte[] nonce = new byte[]{0x42, 0x43, 0x44, 0x45};

        // Compute ID = blake2b_256(pkh || nonce)
        byte[] combined = new byte[committerPkh.length + nonce.length];
        System.arraycopy(committerPkh, 0, combined, 0, committerPkh.length);
        System.arraycopy(nonce, 0, combined, committerPkh.length, nonce.length);
        byte[] id = Blake2bUtil.blake2bHash256(combined);

        YaciHelper.topUp(committer.baseAddress(), 1000);

        var script = JulcScriptLoader.load(CfAnonymousDataValidator.class);
        var scriptAddr = AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();
        var policyId = HexUtil.encodeHexString(script.getScriptHash());

        var backend = YaciHelper.createBackendService();
        var quickTx = new QuickTxBuilder(backend);

        // Step 1: Commit (mint token with id as asset name)
        System.out.println("Step 1: Committing (minting token)...");
        var idHex = "0x" + HexUtil.encodeHexString(id);
        var asset = new Asset(idHex, BigInteger.ONE);

        // Mint redeemer = ByteString(id)
        var mintRedeemer = new BytesPlutusData(id);

        // Datum for the committed data (any inline datum)
        var commitDatum = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(new BytesPlutusData("secret data".getBytes())))
                .build();

        // Mint token and send to script address with inline datum in a single output
        var mintTx = new ScriptTx()
                .mintAsset(script, List.of(asset), mintRedeemer, scriptAddr, commitDatum);

        var commitResult = quickTx.compose(mintTx)
                .withSigner(SignerProviders.signerFrom(committer))
                .feePayer(committer.baseAddress())
                .collateralPayer(committer.baseAddress())
                .withRequiredSigners(committerPkh)
                .ignoreScriptCostEvaluationError(true)
                .withTxEvaluator(YaciHelper.julcEvaluator(backend))
                .complete();

        if (!commitResult.isSuccessful()) {
            System.out.println("Commit failed: " + commitResult);
            System.exit(1);
        }
        var commitTxHash = commitResult.getValue();
        YaciHelper.waitForConfirmation(backend, commitTxHash);
        System.out.println("Committed! Tx: " + commitTxHash);

        // Step 2: Reveal (spend UTXO by providing nonce)
        System.out.println("Step 2: Revealing (spending with nonce)...");
        var scriptUtxo = YaciHelper.findUtxo(backend, scriptAddr, commitTxHash);

        // Spend redeemer = ByteString(nonce)
        var spendRedeemer = new BytesPlutusData(nonce);

        // Explicitly send the token back to the committer (matches Aiken off-chain behavior)
        var idHexReveal = HexUtil.encodeHexString(id);
        var tokenUnit = policyId + idHexReveal;
        var revealTx = new ScriptTx()
                .collectFrom(scriptUtxo, spendRedeemer)
                .payToAddress(committer.baseAddress(),
                        List.of(Amount.ada(2), new Amount(tokenUnit, BigInteger.ONE)))
                .attachSpendingValidator(script);

        var revealResult = quickTx.compose(revealTx)
                .withSigner(SignerProviders.signerFrom(committer))
                .feePayer(committer.baseAddress())
                .collateralPayer(committer.baseAddress())
                .withRequiredSigners(committerPkh)
                .withTxEvaluator(YaciHelper.julcEvaluator(backend))
                .complete();

        if (!revealResult.isSuccessful()) {
            System.out.println("Reveal failed: " + revealResult);
            System.exit(1);
        }
        YaciHelper.waitForConfirmation(backend, revealResult.getValue());
        System.out.println("Revealed! Tx: " + revealResult.getValue());
        System.out.println("Anonymous data demo completed successfully!");
    }

}
