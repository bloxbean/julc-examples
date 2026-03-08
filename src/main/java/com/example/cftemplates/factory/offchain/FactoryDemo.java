package com.example.cftemplates.factory.offchain;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.example.cftemplates.factory.onchain.CfFactoryValidator;
import com.example.cftemplates.factory.onchain.CfProductValidator;
import com.example.offchain.YaciHelper;

import java.math.BigInteger;
import java.util.List;

/**
 * Off-chain demo for CfFactoryValidator + CfProductValidator.
 * Step 1: Mint factory marker (one-shot NFT).
 * Step 2: Create product (spend factory, mint product, update datum).
 */
public class FactoryDemo {

    public static void main(String[] args) throws Exception {
        if (!YaciHelper.isYaciReachable()) {
            System.out.println("Yaci DevKit not reachable. Skipping demo.");
            System.exit(1);
        }

        var owner = new Account(Networks.testnet());
        byte[] ownerPkh = owner.hdKeyPair().getPublicKey().getKeyHash();

        YaciHelper.topUp(owner.baseAddress(), 1000);

        var backend = YaciHelper.createBackendService();
        var quickTx = new QuickTxBuilder(backend);

        // Find seed UTxO for one-shot factory marker
        var seedUtxo = YaciHelper.findAnyUtxo(backend, owner.baseAddress());
        byte[] seedTxHash = HexUtil.decodeHexString(seedUtxo.getTxHash());
        BigInteger seedIndex = BigInteger.valueOf(seedUtxo.getOutputIndex());
        System.out.println("Seed UTxO: " + seedUtxo.getTxHash() + "#" + seedUtxo.getOutputIndex());

        // Load factory script (parameterized by owner, seedTxHash, seedIndex)
        var factoryScript = JulcScriptLoader.load(CfFactoryValidator.class,
                new BytesPlutusData(ownerPkh),
                new BytesPlutusData(seedTxHash),
                BigIntPlutusData.of(seedIndex));
        var factoryScriptAddr = AddressProvider.getEntAddress(factoryScript, Networks.testnet()).toBech32();
        byte[] factoryPolicyBytes = factoryScript.getScriptHash();
        var factoryPolicyId = HexUtil.encodeHexString(factoryPolicyBytes);
        System.out.println("Factory policy: " + factoryPolicyId);

        // Step 1: Mint factory marker (one-shot NFT)
        System.out.println("Step 1: Minting factory marker...");

        String markerTokenHex = "0x" + HexUtil.encodeHexString("FACTORY_MARKER".getBytes());
        var markerAsset = new Asset(markerTokenHex, BigInteger.ONE);

        // Mint redeemer (unused PlutusData for factory mint)
        var factoryMintRedeemer = ConstrPlutusData.of(0);

        // FactoryDatum(products=[]) — empty products list
        var emptyProductsList = ListPlutusData.of();
        var factoryDatum = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(emptyProductsList))
                .build();

        var mintTx = new ScriptTx()
                .collectFrom(seedUtxo)  // consume seed for one-shot
                .mintAsset(factoryScript, List.of(markerAsset), factoryMintRedeemer, factoryScriptAddr, factoryDatum);

        var mintResult = quickTx.compose(mintTx)
                .withSigner(SignerProviders.signerFrom(owner))
                .feePayer(owner.baseAddress())
                .collateralPayer(owner.baseAddress())
                .withRequiredSigners(ownerPkh)
                .ignoreScriptCostEvaluationError(true)
                .complete();

        if (!mintResult.isSuccessful()) {
            System.out.println("Mint factory marker failed: " + mintResult);
            System.exit(1);
        }
        var mintTxHash = mintResult.getValue();
        YaciHelper.waitForConfirmation(backend, mintTxHash);
        System.out.println("Factory marker minted! Tx: " + mintTxHash);

        // Step 2: Create product (spend factory, mint product, update datum)
        System.out.println("Step 2: Creating product...");

        byte[] productId = "product-001".getBytes();
        String productIdHex = "0x" + HexUtil.encodeHexString(productId);

        // Load product script (parameterized by owner, factoryMarkerPolicy, productId)
        var productScript = JulcScriptLoader.load(CfProductValidator.class,
                new BytesPlutusData(ownerPkh),
                new BytesPlutusData(factoryPolicyBytes),
                new BytesPlutusData(productId));
        var productScriptAddr = AddressProvider.getEntAddress(productScript, Networks.testnet()).toBech32();
        byte[] productPolicyBytes = productScript.getScriptHash();
        var productPolicyId = HexUtil.encodeHexString(productPolicyBytes);
        System.out.println("Product policy: " + productPolicyId);

        var factoryUtxo = YaciHelper.findUtxo(backend, factoryScriptAddr, mintTxHash);

        // CreateProduct redeemer = Constr(0, [productPolicyId, productId])
        var createProductRedeemer = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(
                        new BytesPlutusData(productPolicyBytes),
                        new BytesPlutusData(productId)))
                .build();

        // Updated FactoryDatum with product added
        var updatedProductsList = ListPlutusData.of(new BytesPlutusData(productPolicyBytes));
        var updatedFactoryDatum = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(updatedProductsList))
                .build();

        // Product token
        var productAsset = new Asset(productIdHex, BigInteger.ONE);

        // ProductDatum(tag)
        var productDatum = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(new BytesPlutusData("product-001-tag".getBytes())))
                .build();

        // Product mint redeemer (unused)
        var productMintRedeemer = ConstrPlutusData.of(0);

        // Factory continuing output must include the factory marker token
        var factoryPolicyHex = HexUtil.encodeHexString(factoryPolicyBytes);

        // Spend factory UTxO, mint product token, send continuing output + product output
        var createTx = new ScriptTx()
                .collectFrom(factoryUtxo, createProductRedeemer)
                .payToContract(factoryScriptAddr,
                        List.of(Amount.ada(3),
                                new Amount(factoryPolicyHex + HexUtil.encodeHexString("FACTORY_MARKER".getBytes()), BigInteger.ONE)),
                        updatedFactoryDatum)
                .mintAsset(productScript, List.of(productAsset), productMintRedeemer, productScriptAddr, productDatum)
                .attachSpendingValidator(factoryScript);

        var createResult = quickTx.compose(createTx)
                .withSigner(SignerProviders.signerFrom(owner))
                .feePayer(owner.baseAddress())
                .collateralPayer(owner.baseAddress())
                .withRequiredSigners(ownerPkh)
                .ignoreScriptCostEvaluationError(true)
                .complete();

        if (!createResult.isSuccessful()) {
            System.out.println("Create product failed: " + createResult);
            System.exit(1);
        }
        YaciHelper.waitForConfirmation(backend, createResult.getValue());
        System.out.println("Product created! Tx: " + createResult.getValue());
        System.out.println("Factory demo completed successfully!");
    }
}
