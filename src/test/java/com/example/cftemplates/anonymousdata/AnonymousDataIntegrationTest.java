package com.example.cftemplates.anonymousdata;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.example.cftemplates.anonymousdata.onchain.CfAnonymousDataValidator;
import com.example.offchain.YaciHelper;
import org.junit.jupiter.api.*;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AnonymousDataIntegrationTest {

    static boolean yaciAvailable;
    static Account committer;
    static PlutusV3Script script;
    static BackendService backend;
    static QuickTxBuilder quickTx;
    static String scriptAddr;
    static String commitTxHash;
    static byte[] committerPkh;
    static byte[] nonce = new byte[]{0x42, 0x43, 0x44, 0x45};
    static byte[] id;

    @BeforeAll
    static void setup() throws Exception {
        yaciAvailable = YaciHelper.isYaciReachable();
        if (!yaciAvailable) return;

        committer = new Account(Networks.testnet());
        committerPkh = committer.hdKeyPair().getPublicKey().getKeyHash();

        // Compute ID = blake2b_256(pkh || nonce)
        byte[] combined = new byte[committerPkh.length + nonce.length];
        System.arraycopy(committerPkh, 0, combined, 0, committerPkh.length);
        System.arraycopy(nonce, 0, combined, committerPkh.length, nonce.length);
        id = Blake2bUtil.blake2bHash256(combined);

        script = JulcScriptLoader.load(CfAnonymousDataValidator.class);
        scriptAddr = AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();

        YaciHelper.topUp(committer.baseAddress(), 1000);

        backend = YaciHelper.createBackendService();
        quickTx = new QuickTxBuilder(backend);
    }

    @Test
    @Order(1)
    void step1_commit() throws Exception {
        assumeTrue(yaciAvailable, "Yaci DevKit not available");

        var idHex = "0x" + HexUtil.encodeHexString(id);
        var asset = new Asset(idHex, BigInteger.ONE);
        var mintRedeemer = new BytesPlutusData(id);

        var commitDatum = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(new BytesPlutusData("secret data".getBytes())))
                .build();

        var mintTx = new ScriptTx()
                .mintAsset(script, List.of(asset), mintRedeemer, scriptAddr, commitDatum);

        var result = quickTx.compose(mintTx)
                .withSigner(SignerProviders.signerFrom(committer))
                .feePayer(committer.baseAddress())
                .collateralPayer(committer.baseAddress())
                .withRequiredSigners(committerPkh)
                .ignoreScriptCostEvaluationError(true)
                .complete();

        assertTrue(result.isSuccessful(), "Commit should succeed: " + result);
        commitTxHash = result.getValue();
        YaciHelper.waitForConfirmation(backend, commitTxHash);
        System.out.println("Step 1 OK: Committed, tx=" + commitTxHash);
    }

    @Test
    @Order(2)
    void step2_reveal() throws Exception {
        assumeTrue(yaciAvailable, "Yaci DevKit not available");
        assertNotNull(commitTxHash, "Step 1 must complete first");

        var scriptUtxo = YaciHelper.findUtxo(backend, scriptAddr, commitTxHash);
        var spendRedeemer = new BytesPlutusData(nonce);

        // Explicitly send the token back to the committer (matches Aiken off-chain behavior)
        var tokenUnit = HexUtil.encodeHexString(script.getScriptHash()) + HexUtil.encodeHexString(id);
        var revealTx = new ScriptTx()
                .collectFrom(scriptUtxo, spendRedeemer)
                .payToAddress(committer.baseAddress(),
                        List.of(Amount.ada(2), new Amount(tokenUnit, BigInteger.ONE)))
                .attachSpendingValidator(script);

        var result = quickTx.compose(revealTx)
                .withSigner(SignerProviders.signerFrom(committer))
                .feePayer(committer.baseAddress())
                .collateralPayer(committer.baseAddress())
                .withRequiredSigners(committerPkh)
                .complete();

        assertTrue(result.isSuccessful(), "Reveal should succeed: " + result);
        YaciHelper.waitForConfirmation(backend, result.getValue());
        System.out.println("Step 2 OK: Revealed, tx=" + result.getValue());
    }

}
