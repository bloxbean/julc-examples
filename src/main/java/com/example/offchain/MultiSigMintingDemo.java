package com.example.offchain;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.example.validators.MultiSigMinting;

import java.math.BigInteger;

/**
 * End-to-end demo: multi-sig minting policy with 3-variant sealed interface redeemer.
 * <p>
 * This demonstrates:
 * 1. Loading a compiled minting policy that uses sealed interface ADTs
 * 2. Minting tokens with MintByAuthority redeemer (Constr tag 0)
 * 3. Minting tokens with MintByMultiSig redeemer (Constr tag 2)
 * <p>
 * Requires Yaci Devkit running locally.
 */
public class MultiSigMintingDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("=== MultiSig Minting E2E Demo ===\n");

        if (!YaciHelper.isYaciReachable()) {
            System.out.println("ERROR: Yaci Devkit not reachable at " + YaciHelper.YACI_BASE_URL);
            System.out.println("Start it with: yaci-cli devkit start");
            System.exit(1);
        }

        // 1. Load the pre-compiled minting policy (no params)
        var script = JulcScriptLoader.load(MultiSigMinting.class);
        var policyId = script.getPolicyId();
        System.out.println("Policy ID: " + policyId);

        // 2. Create authority and multisig accounts
        var authority = new Account(Networks.testnet());
        var signer1 = new Account(Networks.testnet());
        var signer2 = new Account(Networks.testnet());
        byte[] authPkh = authority.hdKeyPair().getPublicKey().getKeyHash();
        byte[] signer1Pkh = signer1.hdKeyPair().getPublicKey().getKeyHash();
        byte[] signer2Pkh = signer2.hdKeyPair().getPublicKey().getKeyHash();
        System.out.println("Authority: " + authority.baseAddress().substring(0, 20) + "...");
        System.out.println("Signer1:   " + signer1.baseAddress().substring(0, 20) + "...");
        System.out.println("Signer2:   " + signer2.baseAddress().substring(0, 20) + "...");

        // Fund accounts
        YaciHelper.topUp(authority.baseAddress(), 1000);
        YaciHelper.topUp(signer1.baseAddress(), 1000);

        var backend = YaciHelper.createBackendService();
        var quickTx = new QuickTxBuilder(backend);

        // 3. Mint tokens using MintByAuthority redeemer
        //    MintByAuthority(authPkh) = Constr(0, [BData(authPkh)])
        System.out.println("\n--- Minting via MintByAuthority ---");
        var authRedeemer = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(new BytesPlutusData(authPkh)))
                .build();

        var authAsset = new Asset("AuthToken", BigInteger.ONE);
        var mintAuthTx = new ScriptTx()
                .mintAsset(script, authAsset, authRedeemer, authority.baseAddress());

        var authResult = quickTx.compose(mintAuthTx)
                .withSigner(SignerProviders.signerFrom(authority))
                .withRequiredSigners(authPkh)
                .feePayer(authority.baseAddress())
                .collateralPayer(authority.baseAddress())
                .withTxEvaluator(YaciHelper.julcEvaluator(backend))
                .complete();

        if (!authResult.isSuccessful()) {
            System.out.println("FAILED to mint by authority: " + authResult);
            System.exit(1);
        }
        System.out.println("MintByAuthority tx: " + authResult.getValue());
        YaciHelper.waitForConfirmation(backend, authResult.getValue());

        // 4. Mint tokens using MintByMultiSig redeemer
        //    MintByMultiSig(signer1Pkh, signer2Pkh) = Constr(2, [BData(s1), BData(s2)])
        System.out.println("\n--- Minting via MintByMultiSig ---");
        var multiSigRedeemer = ConstrPlutusData.builder()
                .alternative(2)
                .data(ListPlutusData.of(
                        new BytesPlutusData(signer1Pkh),
                        new BytesPlutusData(signer2Pkh)))
                .build();

        var multiAsset = new Asset("MultiToken", BigInteger.ONE);
        var mintMultiTx = new ScriptTx()
                .mintAsset(script, multiAsset, multiSigRedeemer, signer1.baseAddress());

        var multiResult = quickTx.compose(mintMultiTx)
                .withSigner(SignerProviders.signerFrom(signer1))
                .withSigner(SignerProviders.signerFrom(signer2))
                .withRequiredSigners(signer1Pkh, signer2Pkh)
                .feePayer(signer1.baseAddress())
                .collateralPayer(signer1.baseAddress())
                .withTxEvaluator(YaciHelper.julcEvaluator(backend))
                .complete();

        if (!multiResult.isSuccessful()) {
            System.out.println("FAILED to mint by multi-sig: " + multiResult);
            System.exit(1);
        }
        System.out.println("MintByMultiSig tx: " + multiResult.getValue());
        YaciHelper.waitForConfirmation(backend, multiResult.getValue());

        System.out.println("\n=== MultiSig Minting Demo PASSED ===");
    }
}
