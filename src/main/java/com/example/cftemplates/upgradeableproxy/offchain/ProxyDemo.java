package com.example.cftemplates.upgradeableproxy.offchain;

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
import com.example.cftemplates.upgradeableproxy.onchain.CfProxyValidator;
import com.example.offchain.YaciHelper;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Off-chain demo for CfProxyValidator.
 * Step 1: Init proxy (mint state token, output to script with ProxyDatum).
 * Step 2: Update (spend state token, update script pointer in datum).
 */
public class ProxyDemo {

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

        // Find seed UTxO for one-shot state token
        var seedUtxo = YaciHelper.findAnyUtxo(backend, owner.baseAddress());
        byte[] seedTxHash = HexUtil.decodeHexString(seedUtxo.getTxHash());
        BigInteger seedIndex = BigInteger.valueOf(seedUtxo.getOutputIndex());
        System.out.println("Seed UTxO: " + seedUtxo.getTxHash() + "#" + seedUtxo.getOutputIndex());

        // Load proxy script (parameterized by seedTxHash, seedIndex)
        var proxyScript = JulcScriptLoader.load(CfProxyValidator.class,
                new BytesPlutusData(seedTxHash),
                BigIntPlutusData.of(seedIndex));
        var proxyScriptAddr = AddressProvider.getEntAddress(proxyScript, Networks.testnet()).toBech32();
        byte[] proxyPolicyBytes = proxyScript.getScriptHash();
        var proxyPolicyId = HexUtil.encodeHexString(proxyPolicyBytes);
        System.out.println("Proxy policy: " + proxyPolicyId);

        // Compute state token name = sha3_256(seedTxHash || intToDecimalString(seedIndex))
        // intToDecimalString converts the integer to its decimal ASCII representation
        byte[] indexDecStr = seedIndex.toString().getBytes(StandardCharsets.UTF_8);
        byte[] combined = new byte[seedTxHash.length + indexDecStr.length];
        System.arraycopy(seedTxHash, 0, combined, 0, seedTxHash.length);
        System.arraycopy(indexDecStr, 0, combined, seedTxHash.length, indexDecStr.length);

        // SHA3-256 (Keccak-256 in BouncyCastle)
        MessageDigest sha3 = MessageDigest.getInstance("SHA3-256");
        byte[] stateTokenName = sha3.digest(combined);
        String stateTokenHex = "0x" + HexUtil.encodeHexString(stateTokenName);
        System.out.println("State token name: " + HexUtil.encodeHexString(stateTokenName));

        // Dummy script pointer (28 bytes — simulating a script hash for v1 logic)
        byte[] scriptPointerV1 = new byte[28];
        System.arraycopy("v1-logic-script-hash-padding".getBytes(), 0, scriptPointerV1, 0, 28);

        // Step 1: Init proxy (mint state token, output to script with ProxyDatum)
        System.out.println("Step 1: Initializing proxy...");

        var stateAsset = new Asset(stateTokenHex, BigInteger.ONE);

        // Init = tag 0 (mint redeemer)
        var initRedeemer = PlutusDataAdapter.convert(new CfProxyValidator.Init());

        // ProxyDatum(scriptPointer, scriptOwner)
        var proxyDatum = PlutusDataAdapter.convert(new CfProxyValidator.ProxyDatum(
                scriptPointerV1, ownerPkh));

        var mintTx = new ScriptTx()
                .collectFrom(seedUtxo)  // consume seed for one-shot
                .mintAsset(proxyScript, List.of(stateAsset), initRedeemer, proxyScriptAddr, proxyDatum);

        var initResult = quickTx.compose(mintTx)
                .withSigner(SignerProviders.signerFrom(owner))
                .feePayer(owner.baseAddress())
                .collateralPayer(owner.baseAddress())
                .withRequiredSigners(ownerPkh)
                .ignoreScriptCostEvaluationError(true)
                .withTxEvaluator(YaciHelper.julcEvaluator(backend))
                .complete();

        if (!initResult.isSuccessful()) {
            System.out.println("Init proxy failed: " + initResult);
            System.exit(1);
        }
        var initTxHash = initResult.getValue();
        YaciHelper.waitForConfirmation(backend, initTxHash);
        System.out.println("Proxy initialized! Tx: " + initTxHash);

        // Step 2: Update (spend state token, update script pointer in datum)
        System.out.println("Step 2: Updating proxy script pointer...");

        var stateUtxo = YaciHelper.findUtxo(backend, proxyScriptAddr, initTxHash);

        // New script pointer (simulating upgrade to v2)
        byte[] scriptPointerV2 = new byte[28];
        System.arraycopy("v2-logic-script-hash-padding".getBytes(), 0, scriptPointerV2, 0, 28);

        // Update = tag 0 (spend redeemer)
        var updateRedeemer = PlutusDataAdapter.convert(new CfProxyValidator.Update());

        // Updated ProxyDatum(newScriptPointer, sameOwner)
        var updatedDatum = PlutusDataAdapter.convert(new CfProxyValidator.ProxyDatum(
                scriptPointerV2, ownerPkh));

        // Continuing output must include the state token
        var proxyPolicyHex = HexUtil.encodeHexString(proxyPolicyBytes);
        var stateTokenNameHex = HexUtil.encodeHexString(stateTokenName);

        var updateTx = new ScriptTx()
                .collectFrom(stateUtxo, updateRedeemer)
                .payToContract(proxyScriptAddr,
                        List.of(Amount.ada(3),
                                new Amount(proxyPolicyHex + stateTokenNameHex, BigInteger.ONE)),
                        updatedDatum)
                .attachSpendingValidator(proxyScript);

        var updateResult = quickTx.compose(updateTx)
                .withSigner(SignerProviders.signerFrom(owner))
                .feePayer(owner.baseAddress())
                .collateralPayer(owner.baseAddress())
                .withRequiredSigners(ownerPkh)
                .withTxEvaluator(YaciHelper.julcEvaluator(backend))
                .complete();

        if (!updateResult.isSuccessful()) {
            System.out.println("Update failed: " + updateResult);
            System.exit(1);
        }
        YaciHelper.waitForConfirmation(backend, updateResult.getValue());
        System.out.println("Proxy updated! Tx: " + updateResult.getValue());
        System.out.println("Proxy demo completed successfully!");
    }
}
