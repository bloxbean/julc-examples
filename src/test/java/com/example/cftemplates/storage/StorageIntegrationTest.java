package com.example.cftemplates.storage;

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
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.example.cftemplates.storage.onchain.CfStorageValidator;
import com.example.offchain.YaciHelper;
import org.junit.jupiter.api.*;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration test for CfStorageValidator.
 * Step 1: Mint a storage NFT (one-shot with seed UTxO).
 *   - Find a seed UTxO, parameterize the script, mint 1 NFT token,
 *     send to script address with RegistryDatum inline datum.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StorageIntegrationTest {

    static boolean yaciAvailable;
    static Account minter;
    static PlutusV3Script script;
    static BackendService backend;
    static QuickTxBuilder quickTx;
    static String scriptAddr;
    static String mintTxHash;
    static byte[] minterPkh;
    static byte[] snapshotId;
    static byte[] commitmentHash;
    static byte[] assetName;

    @BeforeAll
    static void setup() throws Exception {
        yaciAvailable = YaciHelper.isYaciReachable();
        if (!yaciAvailable) return;

        minter = new Account(Networks.testnet());
        minterPkh = minter.hdKeyPair().getPublicKey().getKeyHash();

        YaciHelper.topUp(minter.baseAddress(), 1000);

        backend = YaciHelper.createBackendService();
        quickTx = new QuickTxBuilder(backend);

        // Find a seed UTxO from the minter's wallet
        Utxo seedUtxo = YaciHelper.findAnyUtxo(backend, minter.baseAddress());
        byte[] seedTxHash = HexUtil.decodeHexString(seedUtxo.getTxHash());
        BigInteger seedIndex = BigInteger.valueOf(seedUtxo.getOutputIndex());

        // Load parameterized script with seed UTxO params
        script = JulcScriptLoader.load(CfStorageValidator.class,
                BytesPlutusData.of(seedTxHash),
                BigIntPlutusData.of(seedIndex));
        scriptAddr = AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();

        // Prepare snapshot data
        snapshotId = "snapshot-2024-01-15".getBytes();
        // Commitment hash must be exactly 32 bytes
        commitmentHash = new byte[]{
                1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
                17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32};
        // Asset name = sha2_256(snapshotId)
        assetName = MessageDigest.getInstance("SHA-256").digest(snapshotId);
    }

    @Test
    @Order(1)
    void step1_mintStorageNft() throws Exception {
        assumeTrue(yaciAvailable, "Yaci DevKit not available");

        // We need to re-find the seed UTxO so we can consume it
        Utxo seedUtxo = YaciHelper.findAnyUtxo(backend, minter.baseAddress());

        var assetHex = "0x" + HexUtil.encodeHexString(assetName);
        var nftAsset = new Asset(assetHex, BigInteger.ONE);

        // StorageMintRedeemer = Constr(0, [snapshotId, snapshotType=Daily, commitmentHash])
        // Daily = Constr(0, [])
        var dailyType = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of())
                .build();

        var mintRedeemer = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(
                        new BytesPlutusData(snapshotId),
                        dailyType,
                        new BytesPlutusData(commitmentHash)))
                .build();

        // RegistryDatum = Constr(0, [snapshotId, snapshotType, commitmentHash, publishedAt])
        BigInteger publishedAt = BigInteger.valueOf(System.currentTimeMillis());
        var registryDatum = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(
                        new BytesPlutusData(snapshotId),
                        dailyType,
                        new BytesPlutusData(commitmentHash),
                        BigIntPlutusData.of(publishedAt)))
                .build();

        // Lock ADA + NFT at script address with RegistryDatum
        var lockTx = new Tx()
                .payToContract(scriptAddr, Amount.ada(3), registryDatum)
                .from(minter.baseAddress());

        // Mint the NFT, consume the seed UTxO
        var mintTx = new ScriptTx()
                .collectFrom(seedUtxo)
                .mintAsset(script, List.of(nftAsset), mintRedeemer, scriptAddr, registryDatum);

        var result = quickTx.compose(mintTx)
                .withSigner(SignerProviders.signerFrom(minter))
                .feePayer(minter.baseAddress())
                .collateralPayer(minter.baseAddress())
                .ignoreScriptCostEvaluationError(true)
                .complete();

        assertTrue(result.isSuccessful(), "Mint storage NFT should succeed: " + result);
        mintTxHash = result.getValue();
        YaciHelper.waitForConfirmation(backend, mintTxHash);
        System.out.println("Step 1 OK: Storage NFT minted, tx=" + mintTxHash);
    }
}
