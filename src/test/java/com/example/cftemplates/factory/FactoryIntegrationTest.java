package com.example.cftemplates.factory;

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
import com.example.cftemplates.factory.onchain.CfFactoryValidator;
import com.example.cftemplates.factory.onchain.CfProductValidator;
import com.example.offchain.YaciHelper;
import org.junit.jupiter.api.*;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration test for CfFactoryValidator + CfProductValidator.
 * Step 1: Mint factory marker (one-shot NFT, consume seed UTxO, lock at factory script).
 * Step 2: Create product (spend factory UTxO, mint product token, update datum).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FactoryIntegrationTest {

    static boolean yaciAvailable;
    static Account owner;
    static PlutusV3Script factoryScript;
    static PlutusV3Script productScript;
    static BackendService backend;
    static QuickTxBuilder quickTx;
    static String factoryScriptAddr;
    static String productScriptAddr;
    static String mintTxHash;
    static byte[] ownerPkh;
    static byte[] factoryPolicyId;
    static byte[] productPolicyId;
    static byte[] factoryTokenName;
    static byte[] productId;

    @BeforeAll
    static void setup() throws Exception {
        yaciAvailable = YaciHelper.isYaciReachable();
        if (!yaciAvailable) return;

        owner = new Account(Networks.testnet());
        ownerPkh = owner.hdKeyPair().getPublicKey().getKeyHash();

        YaciHelper.topUp(owner.baseAddress(), 1000);

        backend = YaciHelper.createBackendService();
        quickTx = new QuickTxBuilder(backend);

        // Find a seed UTxO
        Utxo seedUtxo = YaciHelper.findAnyUtxo(backend, owner.baseAddress());
        byte[] seedTxHash = HexUtil.decodeHexString(seedUtxo.getTxHash());
        BigInteger seedIndex = BigInteger.valueOf(seedUtxo.getOutputIndex());

        // Load factory script (parameterized with owner, seedTxHash, seedIndex)
        factoryScript = JulcScriptLoader.load(CfFactoryValidator.class,
                new BytesPlutusData(ownerPkh),
                BytesPlutusData.of(seedTxHash),
                BigIntPlutusData.of(seedIndex));
        factoryScriptAddr = AddressProvider.getEntAddress(factoryScript, Networks.testnet()).toBech32();
        factoryPolicyId = factoryScript.getScriptHash();

        // Factory token name
        factoryTokenName = "FACTORY_MARKER".getBytes();

        // Product ID
        productId = "PRODUCT_001".getBytes();

        // Load product script (parameterized with owner, factoryMarkerPolicy, productId)
        productScript = JulcScriptLoader.load(CfProductValidator.class,
                new BytesPlutusData(ownerPkh),
                new BytesPlutusData(factoryPolicyId),
                new BytesPlutusData(productId));
        productScriptAddr = AddressProvider.getEntAddress(productScript, Networks.testnet()).toBech32();
        productPolicyId = productScript.getScriptHash();
    }

    @Test
    @Order(1)
    void step1_mintFactoryMarker() throws Exception {
        assumeTrue(yaciAvailable, "Yaci DevKit not available");

        // Re-find the seed UTxO to consume it
        Utxo seedUtxo = YaciHelper.findAnyUtxo(backend, owner.baseAddress());

        var tokenNameHex = "0x" + HexUtil.encodeHexString(factoryTokenName);
        var factoryAsset = new Asset(tokenNameHex, BigInteger.ONE);

        // Mint redeemer (PlutusData, any value - factory mint just needs seed consumed + owner sig)
        var mintRedeemer = ConstrPlutusData.of(0);

        // FactoryDatum = Constr(0, [products=empty list])
        var factoryDatum = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(ListPlutusData.of()))
                .build();

        // Mint factory marker and send to script address with datum
        var mintTx = new ScriptTx()
                .collectFrom(seedUtxo)
                .mintAsset(factoryScript, List.of(factoryAsset), mintRedeemer, factoryScriptAddr, factoryDatum);

        var result = quickTx.compose(mintTx)
                .withSigner(SignerProviders.signerFrom(owner))
                .feePayer(owner.baseAddress())
                .collateralPayer(owner.baseAddress())
                .withRequiredSigners(ownerPkh)
                .ignoreScriptCostEvaluationError(true)
                .complete();

        assertTrue(result.isSuccessful(), "Mint factory marker should succeed: " + result);
        mintTxHash = result.getValue();
        YaciHelper.waitForConfirmation(backend, mintTxHash);
        System.out.println("Step 1 OK: Factory marker minted, tx=" + mintTxHash);
    }

    @Test
    @Order(2)
    void step2_createProduct() throws Exception {
        assumeTrue(yaciAvailable, "Yaci DevKit not available");
        assertNotNull(mintTxHash, "Step 1 must complete first");

        var factoryUtxo = YaciHelper.findUtxo(backend, factoryScriptAddr, mintTxHash);

        // CreateProduct spend redeemer = Constr(0, [productPolicyId, productId])
        var createProductRedeemer = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(
                        new BytesPlutusData(productPolicyId),
                        new BytesPlutusData(productId)))
                .build();

        // Updated FactoryDatum with new product in list
        // FactoryDatum = Constr(0, [products=[productPolicyId]])
        var updatedFactoryDatum = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(
                        ListPlutusData.of(new BytesPlutusData(productPolicyId))))
                .build();

        // Product token
        var productTokenNameHex = "0x" + HexUtil.encodeHexString(productId);
        var productAsset = new Asset(productTokenNameHex, BigInteger.ONE);

        // Product mint redeemer (PlutusData)
        var productMintRedeemer = ConstrPlutusData.of(0);

        // ProductDatum = Constr(0, [tag])
        var productDatum = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(new BytesPlutusData("product-tag".getBytes())))
                .build();

        // Factory continuing output: factory token + updated datum
        var factoryTokenNameHex = "0x" + HexUtil.encodeHexString(factoryTokenName);
        var factoryPolicyHex = HexUtil.encodeHexString(factoryPolicyId);

        // Spend factory UTxO (CreateProduct), mint product token, continuing factory output
        var spendTx = new ScriptTx()
                .collectFrom(factoryUtxo, createProductRedeemer)
                .payToContract(factoryScriptAddr,
                        List.of(Amount.ada(3),
                                new Amount(factoryPolicyHex + HexUtil.encodeHexString(factoryTokenName), BigInteger.ONE)),
                        updatedFactoryDatum)
                .mintAsset(productScript, List.of(productAsset), productMintRedeemer, productScriptAddr, productDatum)
                .attachSpendingValidator(factoryScript);

        var result = quickTx.compose(spendTx)
                .withSigner(SignerProviders.signerFrom(owner))
                .feePayer(owner.baseAddress())
                .collateralPayer(owner.baseAddress())
                .withRequiredSigners(ownerPkh)
                .ignoreScriptCostEvaluationError(true)
                .complete();

        assertTrue(result.isSuccessful(), "Create product should succeed: " + result);
        YaciHelper.waitForConfirmation(backend, result.getValue());
        System.out.println("Step 2 OK: Product created, tx=" + result.getValue());
    }
}
