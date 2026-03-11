package com.example.cftemplates.storage.offchain;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.example.cftemplates.storage.onchain.CfStorageValidator;
import com.example.offchain.YaciHelper;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.List;

/**
 * Off-chain demo for CfStorageValidator.
 * Step 1: Mint storage NFT (one-shot with seed UTxO, send to own script with RegistryDatum).
 * Asset name = sha2_256(snapshotId).
 */
public class StorageDemo {

    public static void main(String[] args) throws Exception {
        if (!YaciHelper.isYaciReachable()) {
            System.out.println("Yaci DevKit not reachable. Skipping demo.");
            System.exit(1);
        }

        var minter = new Account(Networks.testnet());
        byte[] minterPkh = minter.hdKeyPair().getPublicKey().getKeyHash();

        YaciHelper.topUp(minter.baseAddress(), 1000);

        var backend = YaciHelper.createBackendService();
        var quickTx = new QuickTxBuilder(backend);

        // Find seed UTxO for parameterization
        var seedUtxo = YaciHelper.findAnyUtxo(backend, minter.baseAddress());
        byte[] seedTxHash = HexUtil.decodeHexString(seedUtxo.getTxHash());
        BigInteger seedIndex = BigInteger.valueOf(seedUtxo.getOutputIndex());
        System.out.println("Seed UTxO: " + seedUtxo.getTxHash() + "#" + seedUtxo.getOutputIndex());

        // Load parameterized script
        var script = JulcScriptLoader.load(CfStorageValidator.class,
                new BytesPlutusData(seedTxHash),
                BigIntPlutusData.of(seedIndex));
        var scriptAddr = AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();
        var policyId = HexUtil.encodeHexString(script.getScriptHash());
        System.out.println("Policy ID: " + policyId);

        // Prepare snapshot data
        byte[] snapshotId = "daily-snapshot-2024-01-15".getBytes();
        // Commitment hash must be exactly 32 bytes
        byte[] commitmentHash = MessageDigest.getInstance("SHA-256").digest("audit-data-hash-content".getBytes());
        BigInteger publishedAt = BigInteger.valueOf(System.currentTimeMillis());

        // Asset name = sha2_256(snapshotId)
        byte[] assetNameBytes = MessageDigest.getInstance("SHA-256").digest(snapshotId);
        String assetNameHex = "0x" + HexUtil.encodeHexString(assetNameBytes);

        // Step 1: Mint storage NFT
        System.out.println("Step 1: Minting storage NFT...");

        var asset = new Asset(assetNameHex, BigInteger.ONE);

        // StorageMintRedeemer(snapshotId, snapshotType=Daily, commitmentHash)
        var mintRedeemer = PlutusDataAdapter.convert(new CfStorageValidator.StorageMintRedeemer(
                snapshotId, new CfStorageValidator.Daily(), commitmentHash));

        // RegistryDatum(snapshotId, snapshotType=Daily, commitmentHash, publishedAt)
        var registryDatum = PlutusDataAdapter.convert(new CfStorageValidator.RegistryDatum(
                snapshotId, new CfStorageValidator.Daily(), commitmentHash, publishedAt));

        var mintTx = new ScriptTx()
                .collectFrom(seedUtxo)  // consume seed UTxO for one-shot
                .mintAsset(script, List.of(asset), mintRedeemer, scriptAddr, registryDatum);

        var mintResult = quickTx.compose(mintTx)
                .withSigner(SignerProviders.signerFrom(minter))
                .feePayer(minter.baseAddress())
                .collateralPayer(minter.baseAddress())
                .ignoreScriptCostEvaluationError(true)
                .complete();

        if (!mintResult.isSuccessful()) {
            System.out.println("Mint failed: " + mintResult);
            System.exit(1);
        }
        var mintTxHash = mintResult.getValue();
        YaciHelper.waitForConfirmation(backend, mintTxHash);
        System.out.println("Storage NFT minted! Tx: " + mintTxHash);
        System.out.println("Storage demo completed successfully!");
    }
}
