package com.example.offchain;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.example.validators.OneShotMintPolicy;

import java.math.BigInteger;

/**
 * End-to-end demo: one-shot NFT minting that guarantees uniqueness.
 * <p>
 * This demonstrates:
 * 1. Loading a parameterized minting policy (UTXO params baked in)
 * 2. Finding a specific UTXO to consume for uniqueness guarantee
 * 3. Minting a single NFT token
 * <p>
 * Requires Yaci Devkit running locally.
 */
public class OneShotMintDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("=== One-Shot NFT Minting E2E Demo ===\n");

        if (!YaciHelper.isYaciReachable()) {
            System.out.println("ERROR: Yaci Devkit not reachable at " + YaciHelper.YACI_BASE_URL);
            System.out.println("Start it with: yaci-cli devkit start");
            System.exit(1);
        }

        // 1. Create and fund minter account
        var minter = new Account(Networks.testnet());
        var minterAddr = minter.baseAddress();
        byte[] minterPkh = minter.hdKeyPair().getPublicKey().getKeyHash();
        System.out.println("Minter: " + minterAddr);

        YaciHelper.topUp(minterAddr, 1000);

        var backend = YaciHelper.createBackendService();
        var quickTx = new QuickTxBuilder(backend);

        // 2. Find a UTXO owned by the minter — this will be the one-shot param
        Utxo guardUtxo = YaciHelper.findAnyUtxo(backend, minterAddr);
        byte[] utxoTxId = HexUtil.decodeHexString(guardUtxo.getTxHash());
        BigInteger utxoIndex = BigInteger.valueOf(guardUtxo.getOutputIndex());
        System.out.println("Guard UTXO: " + guardUtxo.getTxHash() + "#" + guardUtxo.getOutputIndex());

        // 3. Load the parameterized minting policy with UTXO params baked in
        var policy = JulcScriptLoader.load(OneShotMintPolicy.class,
                BytesPlutusData.of(utxoTxId),
                BigIntPlutusData.of(utxoIndex));
        System.out.println("Policy loaded (parameterized)");

        // 4. Mint 1 UniqueNFT token
        System.out.println("\n--- Minting 1 UniqueNFT ---");
        var asset = new Asset("UniqueNFT", BigInteger.ONE);
        var redeemer = BigIntPlutusData.of(0); // unused redeemer

        var mintTx = new ScriptTx()
                .collectFrom(guardUtxo) // consume the guard UTXO
                .mintAsset(policy, asset, redeemer, minterAddr);

        var mintResult = quickTx.compose(mintTx)
                .withSigner(SignerProviders.signerFrom(minter))
                .feePayer(minterAddr)
                .collateralPayer(minterAddr)
                .withTxEvaluator(YaciHelper.julcEvaluator(backend))
                .complete();

        if (!mintResult.isSuccessful()) {
            System.out.println("FAILED to mint: " + mintResult);
            System.exit(1);
        }
        System.out.println("Mint tx: " + mintResult.getValue());
        YaciHelper.waitForConfirmation(backend, mintResult.getValue());

        System.out.println("\n=== One-Shot Mint Demo PASSED ===");
    }
}
