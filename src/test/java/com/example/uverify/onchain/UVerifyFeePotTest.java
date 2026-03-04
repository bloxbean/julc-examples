package com.example.uverify.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.testkit.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.security.*;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UPLC tests for UVerifyFeePot — @MultiValidator with SPEND only.
 * Tests tiered access control (admin bypass, user lovelace constraint)
 * and both redeemer variants (RELEASE, ON_BEHALF).
 */
class UVerifyFeePotTest extends ContractTest {

    // 28-byte admin key
    static final byte[] ADMIN_KEY = new byte[]{
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14,
            15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28};

    // Neither admin nor user
    static final byte[] OTHER_KEY = new byte[]{
            61, 62, 63, 64, 65, 66, 67, 68, 69, 70, 71, 72, 73, 74,
            75, 76, 77, 78, 79, 80, 81, 82, 83, 84, 85, 86, 87, 88};

    // Whitelisted script hash (28 bytes)
    static final byte[] WHITELISTED_SCRIPT = new byte[]{
            91, 92, 93, 94, 95, 96, 97, 98, 99, 100, 101, 102, 103, 104,
            105, 106, 107, 108, 109, 110, 111, 112, 113, 114, 115, 116, 117, 118};

    // Fee pot's own script hash (28 bytes)
    static final byte[] FEE_POT_SCRIPT_HASH = new byte[]{
            11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24,
            25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38};

    // Ed25519 key pair for ON_BEHALF tests — derived USER_KEY = blake2b_224(pubKey)
    static KeyPair userKeyPair;
    static byte[] USER_PUB_KEY;  // raw 32-byte Ed25519 public key
    static byte[] USER_KEY;      // 28-byte blake2b_224 hash of USER_PUB_KEY

    @BeforeAll
    static void setup() throws Exception {
        initCrypto();
        userKeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] encoded = userKeyPair.getPublic().getEncoded();
        USER_PUB_KEY = Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);
        USER_KEY = blake2b224(USER_PUB_KEY);
    }

    static byte[] blake2b224(byte[] input) {
        // Uses JvmCryptoProvider registered by initCrypto()
        return Builtins.blake2b_224(input);
    }

    static Address feePotAddress() {
        return new Address(
                new Credential.ScriptCredential(new ScriptHash(FEE_POT_SCRIPT_HASH)),
                Optional.empty());
    }

    static Address whitelistedScriptAddress() {
        return new Address(
                new Credential.ScriptCredential(new ScriptHash(WHITELISTED_SCRIPT)),
                Optional.empty());
    }

    static Address pubKeyAddress(byte[] pkh) {
        return new Address(
                new Credential.PubKeyCredential(new PubKeyHash(pkh)),
                Optional.empty());
    }

    com.bloxbean.cardano.julc.core.Program compileAndApplyParams() {
        var result = compileValidator(UVerifyFeePot.class);
        assertFalse(result.hasErrors(), "UVerifyFeePot should compile: " + result);
        return result.program().applyParams(
                PlutusData.list(PlutusData.bytes(ADMIN_KEY)),
                PlutusData.list(PlutusData.bytes(USER_KEY)),
                PlutusData.list(PlutusData.bytes(WHITELISTED_SCRIPT)));
    }

    // --- Compilation ---

    @Test
    void compilesSuccessfully() {
        var result = compileValidator(UVerifyFeePot.class);
        assertFalse(result.hasErrors(), "Should compile without errors: " + result);
        assertNotNull(result.program());
        System.out.println("UVerifyFeePot script size: " + result.scriptSizeFormatted());
    }

    // --- Admin bypass ---

    @Test
    void adminBypass_evaluatesSuccess() throws Exception {
        var program = compileAndApplyParams();

        // Release redeemer (tag 0)
        var redeemer = PlutusData.constr(0);
        var spentRef = TestDataBuilder.randomTxOutRef_typed();

        // Input: 10 ADA at fee pot
        var spentInput = new TxInInfo(spentRef,
                new TxOut(feePotAddress(),
                        Value.lovelace(BigInteger.valueOf(10_000_000)),
                        new OutputDatum.NoOutputDatum(), Optional.empty()));

        // Admin takes 9.5 ADA (no change to script), 0.5 fee
        var adminOutput = new TxOut(pubKeyAddress(ADMIN_KEY),
                Value.lovelace(BigInteger.valueOf(9_500_000)),
                new OutputDatum.NoOutputDatum(), Optional.empty());

        var ctx = spendingContext(spentRef)
                .redeemer(redeemer)
                .input(spentInput)
                .output(adminOutput)
                .fee(BigInteger.valueOf(500_000))
                .signer(ADMIN_KEY)
                .buildPlutusData();

        var evalResult = evaluate(program, ctx);
        logBudget("adminBypass_evaluatesSuccess", evalResult);
        assertSuccess(evalResult);
    }

    // --- User Release ---

    @Test
    void userRelease_evaluatesSuccess() throws Exception {
        var program = compileAndApplyParams();

        var redeemer = PlutusData.constr(0); // Release
        var spentRef = TestDataBuilder.randomTxOutRef_typed();

        // Input: 10 ADA at fee pot
        var spentInput = new TxInInfo(spentRef,
                new TxOut(feePotAddress(),
                        Value.lovelace(BigInteger.valueOf(10_000_000)),
                        new OutputDatum.NoOutputDatum(), Optional.empty()));

        // Change: 8 ADA back to fee pot
        var changeOutput = new TxOut(feePotAddress(),
                Value.lovelace(BigInteger.valueOf(8_000_000)),
                new OutputDatum.NoOutputDatum(), Optional.empty());

        // Whitelisted: 1.5 ADA to whitelisted script
        var whitelistedOutput = new TxOut(whitelistedScriptAddress(),
                Value.lovelace(BigInteger.valueOf(1_500_000)),
                new OutputDatum.NoOutputDatum(), Optional.empty());

        // 8 + 1.5 + 0.5 fee = 10 ADA = input → pass
        var ctx = spendingContext(spentRef)
                .redeemer(redeemer)
                .input(spentInput)
                .output(changeOutput)
                .output(whitelistedOutput)
                .fee(BigInteger.valueOf(500_000))
                .signer(USER_KEY)
                .buildPlutusData();

        var evalResult = evaluate(program, ctx);
        logBudget("userRelease_evaluatesSuccess", evalResult);
        assertSuccess(evalResult);
    }

    @Test
    void userRelease_rejectsOverspend() throws Exception {
        var program = compileAndApplyParams();

        var redeemer = PlutusData.constr(0); // Release
        var spentRef = TestDataBuilder.randomTxOutRef_typed();

        // Input: 10 ADA
        var spentInput = new TxInInfo(spentRef,
                new TxOut(feePotAddress(),
                        Value.lovelace(BigInteger.valueOf(10_000_000)),
                        new OutputDatum.NoOutputDatum(), Optional.empty()));

        // Only 5 ADA change + 0.5 fee = 5.5 < 10 → reject
        var changeOutput = new TxOut(feePotAddress(),
                Value.lovelace(BigInteger.valueOf(5_000_000)),
                new OutputDatum.NoOutputDatum(), Optional.empty());

        var ctx = spendingContext(spentRef)
                .redeemer(redeemer)
                .input(spentInput)
                .output(changeOutput)
                .fee(BigInteger.valueOf(500_000))
                .signer(USER_KEY)
                .buildPlutusData();

        var evalResult = evaluate(program, ctx);
        logBudget("userRelease_rejectsOverspend", evalResult);
        assertFailure(evalResult);
    }

    // --- Non-signer rejection ---

    @Test
    void nonSignerRejected() throws Exception {
        var program = compileAndApplyParams();

        var redeemer = PlutusData.constr(0); // Release
        var spentRef = TestDataBuilder.randomTxOutRef_typed();

        var spentInput = new TxInInfo(spentRef,
                new TxOut(feePotAddress(),
                        Value.lovelace(BigInteger.valueOf(10_000_000)),
                        new OutputDatum.NoOutputDatum(), Optional.empty()));

        // Even with full change, OTHER_KEY is neither admin nor user
        var changeOutput = new TxOut(feePotAddress(),
                Value.lovelace(BigInteger.valueOf(10_000_000)),
                new OutputDatum.NoOutputDatum(), Optional.empty());

        var ctx = spendingContext(spentRef)
                .redeemer(redeemer)
                .input(spentInput)
                .output(changeOutput)
                .fee(BigInteger.valueOf(500_000))
                .signer(OTHER_KEY)
                .buildPlutusData();

        var evalResult = evaluate(program, ctx);
        logBudget("nonSignerRejected", evalResult);
        assertFailure(evalResult);
    }

    // --- ON_BEHALF with Ed25519 signature ---

    @Test
    void handleOnBehalf_evaluatesSuccess() throws Exception {
        var program = compileAndApplyParams();

        var spentRef = TestDataBuilder.randomTxOutRef_typed();
        BigInteger ttl = BigInteger.valueOf(1_700_000_000_000L);

        // Build the expected message: signerPkhHex:submitterKeyHashHex:ttlStr
        HexFormat hex = HexFormat.of();
        String signerPkhHex = hex.formatHex(USER_KEY);
        String submitterKeyHashHex = hex.formatHex(USER_KEY); // same person
        String ttlStr = ttl.toString();
        byte[] message = (signerPkhHex + ":" + submitterKeyHashHex + ":" + ttlStr).getBytes();

        // Sign the message with Ed25519
        Signature sig = Signature.getInstance("Ed25519");
        sig.initSign(userKeyPair.getPrivate());
        sig.update(message);
        byte[] signature = sig.sign();

        // OnBehalf = Constr(1, [message, signature, signerPublicKey, submitterKeyHash, ttl])
        var redeemer = PlutusData.constr(1,
                PlutusData.bytes(message),
                PlutusData.bytes(signature),
                PlutusData.bytes(USER_PUB_KEY),
                PlutusData.bytes(USER_KEY),
                PlutusData.integer(ttl));

        // Input: 10 ADA
        var spentInput = new TxInInfo(spentRef,
                new TxOut(feePotAddress(),
                        Value.lovelace(BigInteger.valueOf(10_000_000)),
                        new OutputDatum.NoOutputDatum(), Optional.empty()));

        // Change: 8 ADA + whitelisted: 1.5 ADA + fee: 0.5 = 10 → pass
        var changeOutput = new TxOut(feePotAddress(),
                Value.lovelace(BigInteger.valueOf(8_000_000)),
                new OutputDatum.NoOutputDatum(), Optional.empty());
        var whitelistedOutput = new TxOut(whitelistedScriptAddress(),
                Value.lovelace(BigInteger.valueOf(1_500_000)),
                new OutputDatum.NoOutputDatum(), Optional.empty());

        // Valid range with finite upper bound ≤ ttl
        var validRange = Interval.between(BigInteger.ZERO, ttl);

        var ctx = spendingContext(spentRef)
                .redeemer(redeemer)
                .input(spentInput)
                .output(changeOutput)
                .output(whitelistedOutput)
                .fee(BigInteger.valueOf(500_000))
                .validRange(validRange)
                .signer(USER_KEY)
                .buildPlutusData();

        var evalResult = evaluate(program, ctx);
        logBudget("handleOnBehalf_evaluatesSuccess", evalResult);
        assertSuccess(evalResult);
    }

    @Test
    void handleOnBehalf_rejectsInvalidSignature() throws Exception {
        var program = compileAndApplyParams();

        var spentRef = TestDataBuilder.randomTxOutRef_typed();
        BigInteger ttl = BigInteger.valueOf(1_700_000_000_000L);

        HexFormat hex = HexFormat.of();
        String signerPkhHex = hex.formatHex(USER_KEY);
        String submitterKeyHashHex = hex.formatHex(USER_KEY);
        String ttlStr = ttl.toString();
        byte[] message = (signerPkhHex + ":" + submitterKeyHashHex + ":" + ttlStr).getBytes();

        // Corrupted signature (all zeros)
        byte[] badSignature = new byte[64];

        var redeemer = PlutusData.constr(1,
                PlutusData.bytes(message),
                PlutusData.bytes(badSignature),
                PlutusData.bytes(USER_PUB_KEY),
                PlutusData.bytes(USER_KEY),
                PlutusData.integer(ttl));

        var spentInput = new TxInInfo(spentRef,
                new TxOut(feePotAddress(),
                        Value.lovelace(BigInteger.valueOf(10_000_000)),
                        new OutputDatum.NoOutputDatum(), Optional.empty()));

        var changeOutput = new TxOut(feePotAddress(),
                Value.lovelace(BigInteger.valueOf(8_000_000)),
                new OutputDatum.NoOutputDatum(), Optional.empty());
        var whitelistedOutput = new TxOut(whitelistedScriptAddress(),
                Value.lovelace(BigInteger.valueOf(1_500_000)),
                new OutputDatum.NoOutputDatum(), Optional.empty());

        var validRange = Interval.between(BigInteger.ZERO, ttl);

        var ctx = spendingContext(spentRef)
                .redeemer(redeemer)
                .input(spentInput)
                .output(changeOutput)
                .output(whitelistedOutput)
                .fee(BigInteger.valueOf(500_000))
                .validRange(validRange)
                .signer(USER_KEY)
                .buildPlutusData();

        var evalResult = evaluate(program, ctx);
        logBudget("handleOnBehalf_rejectsInvalidSignature", evalResult);
        assertFailure(evalResult);
    }

    private static void logBudget(String testName, com.bloxbean.cardano.julc.vm.EvalResult result) {
        var budget = result.budgetConsumed();
        System.out.println("[" + testName + "] CPU: " + budget.cpuSteps() + ", Mem: " + budget.memoryUnits());
        System.out.println("[" + testName + "] Traces: " + result.traces());
        System.out.println("[" + testName + "] Success: " + result.isSuccess());
        if (result instanceof com.bloxbean.cardano.julc.vm.EvalResult.Failure f) {
            System.out.println("[" + testName + "] Error: " + f.error());
        }
    }
}
