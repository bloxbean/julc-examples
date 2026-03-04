package com.example.uverify.offchain;

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
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.example.offchain.YaciHelper;
import com.example.uverify.onchain.UVerifyProxy;
import com.example.uverify.onchain.UVerifyV1;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * End-to-end UVerify lifecycle demo against Yaci Devkit.
 * <p>
 * Architecture (faithful to the Aiken implementation):
 * <pre>
 * UVerifyProxy (@MultiValidator: MINT + SPEND) — Gateway, forces V1 withdrawal
 *     ↓ USER mode requires withdrawal from V1 ↓
 * UVerifyV1 (@MultiValidator: WITHDRAW + SPEND + CERTIFY) — Centralized validation
 * </pre>
 * <p>
 * The proxy produces ONE script hash serving as both policy ID and script address.
 * This guarantees tokens minted under the proxy policy can only be spent at the proxy address.
 * <p>
 * Lifecycle:
 * <ol>
 *   <li>Register V1 stake credential (required for "withdraw zero" trick)</li>
 *   <li>Admin initializes proxy (mint state token via proxy MINT ADMIN)</li>
 *   <li>Admin mints bootstrap token (proxy MINT USER + V1 WITHDRAW MintBootstrap)</li>
 *   <li>Admin burns bootstrap token (proxy SPEND USER + MINT USER burn + V1 WITHDRAW BurnBootstrap)</li>
 * </ol>
 */
public class UVerifyDemo {

    /**
     * Replicate UPLC integerToByteString(true, 0, n) semantics.
     * Big-endian encoding, minimum width, zero produces empty byte array.
     */
    static byte[] integerToBytesBE(BigInteger n) {
        if (n.signum() == 0) return new byte[0];
        byte[] raw = n.toByteArray();
        // Strip leading zero byte (sign byte for positive numbers)
        if (raw.length > 1 && raw[0] == 0) {
            byte[] trimmed = new byte[raw.length - 1];
            System.arraycopy(raw, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return raw;
    }

    /**
     * Compute the proxy state token name: SHA-256(txId ++ integerToByteString(true, 0, idx)).
     * Must match the on-chain getStateTokenName() computation.
     */
    static byte[] computeStateTokenName(byte[] txId, BigInteger idx) throws Exception {
        byte[] idxBytes = integerToBytesBE(idx);
        byte[] combined = new byte[txId.length + idxBytes.length];
        System.arraycopy(txId, 0, combined, 0, txId.length);
        System.arraycopy(idxBytes, 0, combined, txId.length, idxBytes.length);
        return MessageDigest.getInstance("SHA-256").digest(combined);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== UVerify JuLC E2E Demo (Proxy + Withdrawal Architecture) ===\n");

        if (!YaciHelper.isYaciReachable()) {
            System.out.println("ERROR: Yaci Devkit not reachable at " + YaciHelper.YACI_BASE_URL);
            System.out.println("Start it with: yaci-cli devkit start");
            System.exit(1);
        }

        // --- Setup accounts ---
        var admin = new Account(Networks.testnet());
        byte[] adminPkh = admin.hdKeyPair().getPublicKey().getKeyHash();
        System.out.println("Admin: " + admin.baseAddress().substring(0, 20) + "...");

        // Separate funder for registration tx so admin's UTxO stays intact for proxy init
        var funder = new Account(Networks.testnet());

        YaciHelper.topUp(admin.baseAddress(), 1000);
        YaciHelper.topUp(funder.baseAddress(), 100);

        var backend = YaciHelper.createBackendService();
        var quickTx = new QuickTxBuilder(backend);

        // --- Find UTxO for one-time proxy initialization ---
        var adminUtxo = YaciHelper.findAnyUtxo(backend, admin.baseAddress());
        byte[] utxoRefTxId = HexUtil.decodeHexString(adminUtxo.getTxHash());
        BigInteger utxoRefIdx = BigInteger.valueOf(adminUtxo.getOutputIndex());
        System.out.println("UTxO ref: " + adminUtxo.getTxHash() + "#" + adminUtxo.getOutputIndex());

        // --- Compile proxy script (one hash for both MINT + SPEND) ---
        var proxyScript = JulcScriptLoader.load(UVerifyProxy.class,
                BytesPlutusData.of(utxoRefTxId),
                BigIntPlutusData.of(utxoRefIdx));
        var proxyPolicyIdHex = proxyScript.getPolicyId();
        byte[] proxyPolicyIdBytes = HexUtil.decodeHexString(proxyPolicyIdHex);
        var proxyScriptAddr = AddressProvider.getEntAddress(proxyScript, Networks.testnet()).toBech32();

        // --- Compute state token name (SHA-256 of utxoRef) ---
        byte[] stateTokenNameBytes = computeStateTokenName(utxoRefTxId, utxoRefIdx);
        String stateTokenNameHex = HexUtil.encodeHexString(stateTokenNameBytes);

        // --- Compile V1 script (WITHDRAW + SPEND + CERTIFY) ---
        // Using adminPkh for both admin keys in this demo
        var v1Script = JulcScriptLoader.load(UVerifyV1.class,
                BytesPlutusData.of(proxyPolicyIdBytes),
                BytesPlutusData.of(stateTokenNameBytes),
                BytesPlutusData.of(adminPkh),
                BytesPlutusData.of(adminPkh));
        var v1ScriptHashHex = v1Script.getPolicyId();
        byte[] v1ScriptHashBytes = HexUtil.decodeHexString(v1ScriptHashHex);
        var v1RewardAddr = AddressProvider.getRewardAddress(v1Script, Networks.testnet()).toBech32();

        System.out.println("Proxy policyId:     " + proxyPolicyIdHex);
        System.out.println("Proxy script addr:  " + proxyScriptAddr.substring(0, 30) + "...");
        System.out.println("State token name:   " + stateTokenNameHex);
        System.out.println("V1 script hash:     " + v1ScriptHashHex);
        System.out.println("V1 reward addr:     " + v1RewardAddr);

        // ============================================================
        // Step 0: Register V1 stake credential
        // ============================================================
        // The "withdraw zero" trick requires V1's script hash to be registered
        // as a stake credential on-chain. Without this, the Cardano ledger
        // won't recognize the withdrawal.
        System.out.println("\n--- Step 0: Register V1 Stake Credential ---");

        var regTx = new Tx()
                .registerStakeAddress(v1RewardAddr)
                .from(funder.baseAddress());

        var regResult = quickTx.compose(regTx)
                .withSigner(SignerProviders.signerFrom(funder))
                .feePayer(funder.baseAddress())
                .complete();

        if (!regResult.isSuccessful()) {
            System.out.println("FAILED to register V1 stake: " + regResult);
            System.exit(1);
        }
        System.out.println("Registration tx: " + regResult.getValue());
        YaciHelper.waitForConfirmation(backend, regResult.getValue());

        // ============================================================
        // Step 1: Admin initializes proxy (MINT ADMIN)
        // ============================================================
        // The proxy is initialized by consuming a specific UTxO (utxoRef)
        // and minting a unique state token. This can only happen once.
        // The state token locks at the proxy script address with a ProxyDatum
        // containing the V1 script hash (scriptPointer) and admin's key (scriptOwner).
        System.out.println("\n--- Step 1: Initialize Proxy (MINT ADMIN) ---");

        // Admin redeemer: Constr(0, []) — first in sealed interface permits
        var adminRedeemer = ConstrPlutusData.of(0);

        // ProxyDatum = Constr(0, [BData(scriptPointer), BData(scriptOwner)])
        var proxyDatum = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(
                        BytesPlutusData.of(v1ScriptHashBytes),
                        BytesPlutusData.of(adminPkh)))
                .build();

        var stateTokenAsset = new Asset("0x" + stateTokenNameHex, BigInteger.ONE);

        // Must consume the utxoRef input (one-time initialization)
        // State token must be sent to proxy script address WITH datum (not admin address)
        var mintInitTx = new ScriptTx()
                .collectFrom(adminUtxo)
                .mintAsset(proxyScript, List.of(stateTokenAsset), adminRedeemer)
                .payToContract(proxyScriptAddr,
                        List.of(Amount.ada(5), new Amount(proxyPolicyIdHex + stateTokenNameHex, BigInteger.ONE)),
                        proxyDatum);

        var mintInitResult = quickTx.compose(mintInitTx)
                .withSigner(SignerProviders.signerFrom(admin))
                .withRequiredSigners(adminPkh)
                .feePayer(admin.baseAddress())
                .collateralPayer(admin.baseAddress())
                .complete();

        if (!mintInitResult.isSuccessful()) {
            System.out.println("FAILED to initialize proxy: " + mintInitResult);
            System.exit(1);
        }
        var initTxHash = mintInitResult.getValue();
        System.out.println("Proxy init tx: " + initTxHash);
        YaciHelper.waitForConfirmation(backend, initTxHash);

        var proxyStateUtxo = YaciHelper.findUtxo(backend, proxyScriptAddr, initTxHash);
        System.out.println("Proxy state UTxO: " + proxyStateUtxo.getTxHash() + "#" + proxyStateUtxo.getOutputIndex());

        // ============================================================
        // Step 2: Admin mints bootstrap token
        //   (proxy MINT USER + V1 WITHDRAW MintBootstrap)
        // ============================================================
        // USER mode on the proxy requires:
        //   1. Reference input with proxy state token (ProxyDatum)
        //   2. Withdrawal from V1 (scriptPointer in ProxyDatum)
        // V1's WITHDRAW handler validates the MintBootstrap business logic.
        System.out.println("\n--- Step 2: Mint Bootstrap Token ---");

        byte[] bootstrapTokenName = "UVerify_Default".getBytes(StandardCharsets.UTF_8);
        String bootstrapTokenNameHex = HexUtil.encodeHexString(bootstrapTokenName);

        // User redeemer for proxy: Constr(1, []) — second in sealed interface permits
        var userRedeemer = ConstrPlutusData.of(1);

        // V1 MintBootstrap redeemer: UVerifyStateRedeemer = Constr(0, [Constr(4), List()])
        // StatePurpose ordering: BurnBootstrap=0, BurnState=1, UpdateState=2, MintState=3, MintBootstrap=4
        var v1MintBootstrapRedeemer = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(
                        ConstrPlutusData.of(4),   // MintBootstrap tag
                        ListPlutusData.of()))      // empty certificates list
                .build();

        // BootstrapDatum = Constr(0, [allowedCreds, tokenName, fee, feeInterval,
        //                  feeReceivers, ttl, transactionLimit, batchSize])
        var bootstrapDatum = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(
                        ListPlutusData.of(),                             // allowedCreds = empty
                        BytesPlutusData.of(bootstrapTokenName),          // tokenName
                        BigIntPlutusData.of(1_000_000),                  // fee (1 ADA)
                        BigIntPlutusData.of(1),                          // feeInterval
                        ListPlutusData.of(BytesPlutusData.of(adminPkh)), // feeReceivers
                        BigIntPlutusData.of(1_700_000_000_000L),         // ttl (far future)
                        BigIntPlutusData.of(10),                         // transactionLimit
                        BigIntPlutusData.of(5)))                         // batchSize
                .build();

        var bootstrapAsset = new Asset("0x" + bootstrapTokenNameHex, BigInteger.ONE);

        // Proxy mint tx (USER redeemer): mint bootstrap token to script address WITH datum
        // readFrom() adds the proxy state UTxO as a reference input (not consumed)
        var mintBootstrapTx = new ScriptTx()
                .readFrom(proxyStateUtxo)
                .mintAsset(proxyScript, List.of(bootstrapAsset), userRedeemer)
                .payToContract(proxyScriptAddr,
                        List.of(Amount.ada(5), new Amount(proxyPolicyIdHex + bootstrapTokenNameHex, BigInteger.ONE)),
                        bootstrapDatum);

        // V1 withdrawal tx: zero-amount withdrawal to trigger V1's WITHDRAW handler
        var v1WithdrawMintBsTx = new ScriptTx()
                .withdraw(v1RewardAddr, BigInteger.ZERO, v1MintBootstrapRedeemer)
                .attachRewardValidator(v1Script);

        var mintBootstrapResult = quickTx.compose(mintBootstrapTx, v1WithdrawMintBsTx)
                .withSigner(SignerProviders.signerFrom(admin))
                .withRequiredSigners(adminPkh)
                .feePayer(admin.baseAddress())
                .collateralPayer(admin.baseAddress())
                .complete();

        if (!mintBootstrapResult.isSuccessful()) {
            System.out.println("FAILED to mint bootstrap: " + mintBootstrapResult);
            System.exit(1);
        }
        var bootstrapTxHash = mintBootstrapResult.getValue();
        System.out.println("Bootstrap mint tx: " + bootstrapTxHash);
        YaciHelper.waitForConfirmation(backend, bootstrapTxHash);

        var bootstrapUtxo = YaciHelper.findUtxo(backend, proxyScriptAddr, bootstrapTxHash);
        System.out.println("Bootstrap UTxO: " + bootstrapUtxo.getTxHash() + "#" + bootstrapUtxo.getOutputIndex());

        // ============================================================
        // Step 3: Admin burns bootstrap token
        //   (proxy SPEND USER + proxy MINT USER burn + V1 WITHDRAW BurnBootstrap)
        // ============================================================
        // This demonstrates the full compose pattern with 3 ScriptTx objects:
        //   1. Spend the bootstrap UTxO from proxy script address
        //   2. Burn the bootstrap token via proxy mint (negative qty)
        //   3. V1 withdrawal validates the BurnBootstrap logic
        //
        // Note: Split spend and mint into separate ScriptTx to avoid CBOR
        // serialization issues (see MEMORY.md note #1).
        System.out.println("\n--- Step 3: Burn Bootstrap Token ---");

        // V1 BurnBootstrap redeemer: UVerifyStateRedeemer = Constr(0, [Constr(0), List()])
        var v1BurnBootstrapRedeemer = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(
                        ConstrPlutusData.of(0),    // BurnBootstrap tag
                        ListPlutusData.of()))       // empty certificates list
                .build();

        var burnBootstrapAsset = new Asset("0x" + bootstrapTokenNameHex, BigInteger.ONE.negate());

        // Combined spend + mint: collect bootstrap UTxO and burn token in one ScriptTx
        var burnSpendMintTx = new ScriptTx()
                .readFrom(proxyStateUtxo)
                .collectFrom(bootstrapUtxo, userRedeemer)
                .mintAsset(proxyScript, List.of(burnBootstrapAsset), userRedeemer)
                .payToAddress(admin.baseAddress(), Amount.ada(1))
                .attachSpendingValidator(proxyScript);

        // V1 withdrawal for BurnBootstrap validation
        var v1WithdrawBurnBsTx = new ScriptTx()
                .withdraw(v1RewardAddr, BigInteger.ZERO, v1BurnBootstrapRedeemer)
                .attachRewardValidator(v1Script);

        var burnResult = quickTx.compose(burnSpendMintTx, v1WithdrawBurnBsTx)
                .withSigner(SignerProviders.signerFrom(admin))
                .withRequiredSigners(adminPkh)
                .feePayer(admin.baseAddress())
                .collateralPayer(admin.baseAddress())
                .complete();

        if (!burnResult.isSuccessful()) {
            System.out.println("FAILED to burn bootstrap: " + burnResult);
            System.exit(1);
        }
        System.out.println("Bootstrap burn tx: " + burnResult.getValue());
        YaciHelper.waitForConfirmation(backend, burnResult.getValue());

        System.out.println("\n=== UVerify Demo PASSED ===");
    }
}
