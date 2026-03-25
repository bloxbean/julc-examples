package com.example.offchain;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.example.validators.GuardedMinting;

import java.math.BigInteger;

/**
 * End-to-end demo: mint tokens using an authorized minting policy.
 * <p>
 * This demonstrates:
 * 1. Loading a pre-compiled minting policy via JulcScriptLoader
 * 2. Minting tokens with an authorized signer (redeemer = PubKeyHash)
 * 3. Verifying the minting transaction succeeds
 * <p>
 * Requires Yaci Devkit running locally.
 */
public class MintingDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("=== Authorized Minting E2E Demo ===\n");

        if (!YaciHelper.isYaciReachable()) {
            System.out.println("ERROR: Yaci Devkit not reachable at " + YaciHelper.YACI_BASE_URL);
            System.out.println("Start it with: yaci-cli devkit start");
            System.exit(1);
        }

        // 1. Load the pre-compiled minting policy
        var policy = JulcScriptLoader.load(GuardedMinting.class);
        var policyId = JulcScriptLoader.scriptHash(GuardedMinting.class);
        System.out.println("Policy ID: " + policyId);

        // 2. Create and fund the authorizer account
        var authorizer = new Account(Networks.testnet());
        var authorizerAddr = authorizer.baseAddress();
        byte[] authorizerPkh = authorizer.hdKeyPair().getPublicKey().getKeyHash();
        System.out.println("Authorizer: " + authorizerAddr);
        YaciHelper.topUp(authorizerAddr, 1000);

        var backend = YaciHelper.createBackendService();
        var quickTx = new QuickTxBuilder(backend);

        // 3. Redeemer = BData(authorizerPkh) — the PubKeyHash of the authorizer
        var redeemer = new BytesPlutusData(authorizerPkh);

        // 4. Mint tokens
        System.out.println("\n--- Minting 100 MyToken ---");
        var asset = new Asset("MyToken", BigInteger.valueOf(100));

        var mintTx = new ScriptTx()
                .mintAsset(policy, asset, redeemer, authorizerAddr);

        var mintResult = quickTx.compose(mintTx)
                .withSigner(SignerProviders.signerFrom(authorizer))
                .withRequiredSigners(authorizerPkh)
                .feePayer(authorizerAddr)
                .collateralPayer(authorizerAddr)
                .withTxEvaluator(YaciHelper.julcEvaluator(backend))
                .complete();

        if (!mintResult.isSuccessful()) {
            System.out.println("FAILED to mint: " + mintResult);
            System.exit(1);
        }
        System.out.println("Mint tx: " + mintResult.getValue());
        YaciHelper.waitForConfirmation(backend, mintResult.getValue());

        System.out.println("\n=== Minting Demo PASSED ===");
    }
}
