package com.example.nft;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.example.nft.onchain.Cip68Nft;
import com.example.offchain.YaciHelper;
import org.junit.jupiter.api.*;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration test for Cip68Nft — requires Yaci DevKit running.
 * Tests are skipped (not failed) when Yaci is unavailable.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class Cip68NftIntegrationTest {

    private static final byte[] REF_PREFIX = HexUtil.decodeHexString("000643b0");
    private static final byte[] USER_PREFIX = HexUtil.decodeHexString("000de140");

    static boolean yaciAvailable;
    static Account minter;
    static PlutusV3Script script;
    static BackendService backend;
    static QuickTxBuilder quickTx;
    static String scriptAddr;
    static String policyId;
    static byte[] refTokenName;
    static byte[] userTokenName;
    static String mintTxHash;

    @BeforeAll
    static void setup() throws Exception {
        yaciAvailable = YaciHelper.isYaciReachable();
        if (!yaciAvailable) return;

        byte[] assetName = "TestNFT".getBytes();
        refTokenName = concat(REF_PREFIX, assetName);
        userTokenName = concat(USER_PREFIX, assetName);

        script = JulcScriptLoader.load(Cip68Nft.class,
                BytesPlutusData.of(refTokenName),
                BytesPlutusData.of(userTokenName));
        scriptAddr = AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();
        policyId = script.getPolicyId();

        minter = new Account(Networks.testnet());
        YaciHelper.topUp(minter.baseAddress(), 1000);

        backend = YaciHelper.createBackendService();
        quickTx = new QuickTxBuilder(backend);
    }

    @Test
    @Order(1)
    void step1_mintCip68Nft() throws Exception {
        assumeTrue(yaciAvailable, "Yaci DevKit not available");

        var metadataDatum = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(
                        ConstrPlutusData.of(0),
                        BigIntPlutusData.of(1)))
                .build();

        var refAsset = new Asset("0x" + HexUtil.encodeHexString(refTokenName), BigInteger.ONE);
        var userAsset = new Asset("0x" + HexUtil.encodeHexString(userTokenName), BigInteger.ONE);

        var mintRedeemer = ConstrPlutusData.of(0); // MintNft

        // Mint both tokens in a single mintAsset call — multiple mintAsset calls on the
        // same policy don't merge correctly. Both tokens go to the script address with
        // inline datum; the validator only requires the ref token to be there.
        var mintTx = new ScriptTx()
                .mintAsset(script, List.of(refAsset, userAsset), mintRedeemer, scriptAddr, metadataDatum);

        var result = quickTx.compose(mintTx)
                .withSigner(SignerProviders.signerFrom(minter))
                .feePayer(minter.baseAddress())
                .collateralPayer(minter.baseAddress())
                .complete();

        assertTrue(result.isSuccessful(), "Mint tx should succeed: " + result);
        mintTxHash = result.getValue();
        YaciHelper.waitForConfirmation(backend, mintTxHash);
        System.out.println("Step 1 OK: Minted CIP-68 NFT, tx=" + mintTxHash);
    }

    @Test
    @Order(2)
    void step2_verifyReferenceUtxoExists() throws Exception {
        assumeTrue(yaciAvailable, "Yaci DevKit not available");
        assertNotNull(mintTxHash, "Step 1 must complete first");

        var scriptUtxo = YaciHelper.findUtxo(backend, scriptAddr, mintTxHash);
        assertNotNull(scriptUtxo, "Reference UTxO should exist at script address");
        System.out.println("Step 2 OK: Reference UTxO found at " +
                scriptUtxo.getTxHash() + "#" + scriptUtxo.getOutputIndex());
    }

    @Test
    @Order(3)
    void step3_updateMetadata() throws Exception {
        assumeTrue(yaciAvailable, "Yaci DevKit not available");
        assertNotNull(mintTxHash, "Step 1 must complete first");

        var refUtxo = YaciHelper.findUtxo(backend, scriptAddr, mintTxHash);

        var newMetadataDatum = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(
                        ConstrPlutusData.of(0),
                        BigIntPlutusData.of(2))) // version 2
                .build();

        var updateRedeemer = ConstrPlutusData.of(0); // UpdateMetadata

        var updateTx = new ScriptTx()
                .collectFrom(refUtxo, updateRedeemer)
                .payToContract(scriptAddr,
                        List.of(Amount.ada(2),
                                new Amount(policyId + HexUtil.encodeHexString(refTokenName), BigInteger.ONE)),
                        newMetadataDatum)
                .attachSpendingValidator(script);

        var result = quickTx.compose(updateTx)
                .withSigner(SignerProviders.signerFrom(minter))
                .feePayer(minter.baseAddress())
                .collateralPayer(minter.baseAddress())
                .complete();

        assertTrue(result.isSuccessful(), "Update tx should succeed: " + result);
        YaciHelper.waitForConfirmation(backend, result.getValue());
        System.out.println("Step 3 OK: Updated metadata, tx=" + result.getValue());
    }

    @Test
    @Order(4)
    void step4_burnBothTokens() throws Exception {
        assumeTrue(yaciAvailable, "Yaci DevKit not available");

        // Find ref UTxO at script address (most recent from step3)
        var utxoResult = backend.getUtxoService().getUtxos(scriptAddr, 100, 1);
        assertTrue(utxoResult.isSuccessful() && !utxoResult.getValue().isEmpty(),
                "Should find ref UTxO at script address");
        var allUtxos = utxoResult.getValue();
        var refUtxo = allUtxos.get(allUtxos.size() - 1);

        var negOne = BigInteger.ONE.negate();
        var refAsset = new Asset("0x" + HexUtil.encodeHexString(refTokenName), negOne);
        var userAsset = new Asset("0x" + HexUtil.encodeHexString(userTokenName), negOne);

        var burnRedeemer = ConstrPlutusData.of(1); // BurnNft (mint) / BurnReference (spend)

        var burnTx = new ScriptTx()
                .collectFrom(refUtxo, burnRedeemer)
                .mintAsset(script, List.of(refAsset, userAsset), burnRedeemer)
                .payToAddress(minter.baseAddress(), Amount.ada(2))
                .attachSpendingValidator(script);

        var result = quickTx.compose(burnTx)
                .withSigner(SignerProviders.signerFrom(minter))
                .feePayer(minter.baseAddress())
                .collateralPayer(minter.baseAddress())
                .ignoreScriptCostEvaluationError(true)
                .complete();

        assertTrue(result.isSuccessful(), "Burn tx should succeed: " + result);
        YaciHelper.waitForConfirmation(backend, result.getValue());
        System.out.println("Step 4 OK: Burned both tokens, tx=" + result.getValue());
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }
}
