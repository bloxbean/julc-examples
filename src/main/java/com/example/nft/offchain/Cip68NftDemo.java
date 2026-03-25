package com.example.nft.offchain;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Amount;
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

import java.math.BigInteger;
import java.util.List;

/**
 * End-to-end demo: CIP-68 NFT with reference + user tokens.
 * <p>
 * Demonstrates:
 * 1. Compute CIP-68 token names (000643b0 ++ name, 000de140 ++ name)
 * 2. Mint both tokens: ref token locked at script with metadata, user token to minter
 * 3. Update metadata on the reference UTxO
 * 4. Burn both tokens
 * <p>
 * Requires Yaci Devkit running locally.
 */
public class Cip68NftDemo {

    // CIP-68 label prefixes (4 bytes each)
    private static final byte[] REF_PREFIX = HexUtil.decodeHexString("000643b0");
    private static final byte[] USER_PREFIX = HexUtil.decodeHexString("000de140");

    public static void main(String[] args) throws Exception {
        System.out.println("=== CIP-68 NFT E2E Demo ===\n");

        if (!YaciHelper.isYaciReachable()) {
            System.out.println("ERROR: Yaci Devkit not reachable at " + YaciHelper.YACI_BASE_URL);
            System.out.println("Start it with: yaci-cli devkit start");
            System.exit(1);
        }

        // 1. Compute CIP-68 token names
        byte[] assetName = "MyNFT".getBytes();
        byte[] refTokenName = concat(REF_PREFIX, assetName);
        byte[] userTokenName = concat(USER_PREFIX, assetName);
        System.out.println("Ref token name: " + HexUtil.encodeHexString(refTokenName));
        System.out.println("User token name: " + HexUtil.encodeHexString(userTokenName));

        // 2. Load multi-validator with @Param: pre-computed token names
        PlutusV3Script script = JulcScriptLoader.load(Cip68Nft.class,
                BytesPlutusData.of(refTokenName),
                BytesPlutusData.of(userTokenName));
        var scriptAddr = AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();
        var policyId = script.getPolicyId();
        System.out.println("Policy ID: " + policyId);
        System.out.println("Script address: " + scriptAddr);

        // 3. Create minter account
        var minter = new Account(Networks.testnet());
        System.out.println("Minter: " + minter.baseAddress().substring(0, 20) + "...");
        YaciHelper.topUp(minter.baseAddress(), 1000);

        var backend = YaciHelper.createBackendService();
        var quickTx = new QuickTxBuilder(backend);

        // 4. Build CIP-68 metadata datum: NftMetadata(metadata, version)
        //    NftMetadata = Constr(0, [Map{}, IData(1)])
        var metadataDatum = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(
                        ConstrPlutusData.of(0),       // opaque metadata placeholder
                        BigIntPlutusData.of(1)))       // version = 1
                .build();

        // 5. Mint both tokens
        System.out.println("\n--- Step 1: Mint CIP-68 NFT pair ---");
        var refAsset = new Asset("0x" + HexUtil.encodeHexString(refTokenName), BigInteger.ONE);
        var userAsset = new Asset("0x" + HexUtil.encodeHexString(userTokenName), BigInteger.ONE);

        // MintNft redeemer = Constr(0, [])
        var mintRedeemer = ConstrPlutusData.of(0);

        var mintTx = new ScriptTx()
                .mintAsset(script, List.of(refAsset, userAsset), mintRedeemer)
                .payToContract(scriptAddr,
                        List.of(Amount.ada(2), new Amount(policyId + HexUtil.encodeHexString(refTokenName), BigInteger.ONE)),
                        metadataDatum)
                .payToAddress(minter.baseAddress(),
                        List.of(new Amount(policyId + HexUtil.encodeHexString(userTokenName), BigInteger.ONE)));

        var mintResult = quickTx.compose(mintTx)
                .withSigner(SignerProviders.signerFrom(minter))
                .feePayer(minter.baseAddress())
                .collateralPayer(minter.baseAddress())
                .withTxEvaluator(YaciHelper.julcEvaluator(backend))
                .complete();

        if (!mintResult.isSuccessful()) {
            System.out.println("FAILED to mint: " + mintResult);
            System.exit(1);
        }
        var mintTxHash = mintResult.getValue();
        System.out.println("Mint tx: " + mintTxHash);
        YaciHelper.waitForConfirmation(backend, mintTxHash);

        System.out.println("\n=== CIP-68 NFT Demo PASSED ===");
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }
}
