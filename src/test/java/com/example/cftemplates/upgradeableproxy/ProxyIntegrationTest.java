package com.example.cftemplates.upgradeableproxy;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.example.cftemplates.upgradeableproxy.onchain.CfProxyValidator;
import com.example.cftemplates.upgradeableproxy.onchain.CfScriptLogicV1;
import com.example.cftemplates.upgradeableproxy.onchain.CfScriptLogicV2;
import com.example.offchain.YaciHelper;
import org.junit.jupiter.api.*;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration test for CfProxyValidator (Upgradeable Proxy).
 * Step 1: Init proxy (mint state token, one-shot, lock at proxy script with ProxyDatum).
 * Step 2: Update (change script pointer from V1 to V2 in ProxyDatum).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ProxyIntegrationTest {

    static boolean yaciAvailable;
    static Account owner;
    static PlutusV3Script proxyScript;
    static PlutusV3Script v1Script;
    static PlutusV3Script v2Script;
    static BackendService backend;
    static QuickTxBuilder quickTx;
    static String proxyScriptAddr;
    static String initTxHash;
    static byte[] ownerPkh;
    static byte[] proxyPolicyId;
    static byte[] v1ScriptHash;
    static byte[] v2ScriptHash;
    static byte[] stateTokenName;

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

        // Load proxy script (parameterized with seedTxHash, seedIndex)
        proxyScript = JulcScriptLoader.load(CfProxyValidator.class,
                BytesPlutusData.of(seedTxHash),
                BigIntPlutusData.of(seedIndex));
        proxyScriptAddr = AddressProvider.getEntAddress(proxyScript, Networks.testnet()).toBech32();
        proxyPolicyId = proxyScript.getScriptHash();

        // Compute state token name: sha3_256(seedTxHash || intToDecimalString(seedIndex))
        byte[] indexStr = seedIndex.toString().getBytes(StandardCharsets.UTF_8);
        byte[] combined = new byte[seedTxHash.length + indexStr.length];
        System.arraycopy(seedTxHash, 0, combined, 0, seedTxHash.length);
        System.arraycopy(indexStr, 0, combined, seedTxHash.length, indexStr.length);
        stateTokenName = MessageDigest.getInstance("SHA3-256").digest(combined);

        // Load V1 and V2 script logic (parameterized with proxyPolicyId)
        v1Script = JulcScriptLoader.load(CfScriptLogicV1.class,
                new BytesPlutusData(proxyPolicyId));
        v1ScriptHash = v1Script.getScriptHash();

        v2Script = JulcScriptLoader.load(CfScriptLogicV2.class,
                new BytesPlutusData(proxyPolicyId));
        v2ScriptHash = v2Script.getScriptHash();
    }

    @Test
    @Order(1)
    void step1_initProxy() throws Exception {
        assumeTrue(yaciAvailable, "Yaci DevKit not available");

        // Re-find the seed UTxO to consume it
        Utxo seedUtxo = YaciHelper.findAnyUtxo(backend, owner.baseAddress());

        var tokenNameHex = "0x" + HexUtil.encodeHexString(stateTokenName);
        var stateAsset = new Asset(tokenNameHex, BigInteger.ONE);

        // Init = tag 0 (mint redeemer)
        var initRedeemer = PlutusDataAdapter.convert(new CfProxyValidator.Init());

        // ProxyDatum(scriptPointer=v1ScriptHash, scriptOwner=ownerPkh)
        var proxyDatum = PlutusDataAdapter.convert(new CfProxyValidator.ProxyDatum(
                v1ScriptHash, ownerPkh));

        // Mint state token, consume seed, send to proxy script with datum
        var mintTx = new ScriptTx()
                .collectFrom(seedUtxo)
                .mintAsset(proxyScript, List.of(stateAsset), initRedeemer, proxyScriptAddr, proxyDatum);

        var result = quickTx.compose(mintTx)
                .withSigner(SignerProviders.signerFrom(owner))
                .feePayer(owner.baseAddress())
                .collateralPayer(owner.baseAddress())
                .withRequiredSigners(ownerPkh)
                .ignoreScriptCostEvaluationError(true)
                .complete();

        assertTrue(result.isSuccessful(), "Init proxy should succeed: " + result);
        initTxHash = result.getValue();
        YaciHelper.waitForConfirmation(backend, initTxHash);
        System.out.println("Step 1 OK: Proxy initialized with V1, tx=" + initTxHash);
    }

    @Test
    @Order(2)
    void step2_updateScriptPointer() throws Exception {
        assumeTrue(yaciAvailable, "Yaci DevKit not available");
        assertNotNull(initTxHash, "Step 1 must complete first");

        var stateUtxo = YaciHelper.findUtxo(backend, proxyScriptAddr, initTxHash);

        // Update = tag 0 (spend redeemer, sealed: Update=0, ProxySpend=1)
        var updateRedeemer = PlutusDataAdapter.convert(new CfProxyValidator.Update());

        // New ProxyDatum pointing to V2
        var newProxyDatum = PlutusDataAdapter.convert(new CfProxyValidator.ProxyDatum(
                v2ScriptHash, ownerPkh));

        var proxyPolicyHex = HexUtil.encodeHexString(proxyPolicyId);
        var tokenNameHex = HexUtil.encodeHexString(stateTokenName);

        // Spend state UTxO, output with updated datum and same state token
        var updateTx = new ScriptTx()
                .collectFrom(stateUtxo, updateRedeemer)
                .payToContract(proxyScriptAddr,
                        List.of(Amount.ada(3),
                                new Amount(proxyPolicyHex + tokenNameHex, BigInteger.ONE)),
                        newProxyDatum)
                .attachSpendingValidator(proxyScript);

        var result = quickTx.compose(updateTx)
                .withSigner(SignerProviders.signerFrom(owner))
                .feePayer(owner.baseAddress())
                .collateralPayer(owner.baseAddress())
                .withRequiredSigners(ownerPkh)
                .complete();

        assertTrue(result.isSuccessful(), "Update script pointer should succeed: " + result);
        YaciHelper.waitForConfirmation(backend, result.getValue());
        System.out.println("Step 2 OK: Script pointer updated to V2, tx=" + result.getValue());
    }
}
