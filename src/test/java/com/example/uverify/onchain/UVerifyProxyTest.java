package com.example.uverify.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.testkit.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UPLC tests for UVerifyProxy — @MultiValidator with MINT + SPEND.
 * <p>
 * Note: Direct Java tests are not feasible because the validator uses raw Builtins
 * (unBData, constrTag, etc.) which return PlutusData types, not Java primitives.
 * The (byte[])(Object) casts needed for javac compatibility fail at runtime in JVM mode.
 * All tests run through UPLC compilation and VM evaluation.
 */
class UVerifyProxyTest extends ContractTest {

    // 32-byte txId for the UTxO ref parameter
    static final byte[] UTXO_REF_TX_ID = new byte[]{
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
            17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32};
    static final BigInteger UTXO_REF_IDX = BigInteger.ZERO;

    // 28-byte script hash (used as proxy policyId/script address)
    static final byte[] PROXY_SCRIPT_HASH = new byte[]{
            10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120, 11, 21,
            15, 25, 35, 45, 55, 65, 75, 85, 95, 105, 115, 125, 12, 22};

    // 28-byte V1 script hash (withdrawal target)
    static final byte[] V1_SCRIPT_HASH = new byte[]{
            51, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61, 62, 63, 64,
            65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76, 77, 78};

    // 28-byte PubKeyHashes
    static final byte[] ADMIN_PKH = new byte[]{
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14,
            15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28};
    static final byte[] OTHER_PKH = new byte[]{
            99, 98, 97, 96, 95, 94, 93, 92, 91, 90, 89, 88, 87, 86,
            85, 84, 83, 82, 81, 80, 79, 78, 77, 76, 75, 74, 73, 72};

    @BeforeAll
    static void setup() {
        initCrypto();
    }

    // Compute state token name = SHA2-256(utxoRefTxId ++ integerToByteString(true, 0, utxoRefIdx))
    // Note: JVM Builtins.integerToByteString(true, 0, 0) returns [0] (single byte) but UPLC returns []
    // (empty bytestring). We manually replicate the UPLC behavior here.
    static byte[] computeStateTokenName() {
        byte[] idxBytes = uplcIntegerToByteString(UTXO_REF_IDX);
        byte[] combined = Builtins.appendByteString(UTXO_REF_TX_ID, idxBytes);
        return com.bloxbean.cardano.julc.stdlib.lib.CryptoLib.sha2_256(combined);
    }

    // Replicate UPLC integerToByteString(true, 0, n) behavior: zero produces empty bytestring
    static byte[] uplcIntegerToByteString(BigInteger n) {
        if (n.signum() == 0) return new byte[0];
        return Builtins.integerToByteString(true, 0, n.longValue());
    }

    static Address scriptAddress() {
        return new Address(
                new Credential.ScriptCredential(new ScriptHash(PROXY_SCRIPT_HASH)),
                Optional.empty());
    }

    static Address pubKeyAddress(byte[] pkh) {
        return new Address(
                new Credential.PubKeyCredential(new PubKeyHash(pkh)),
                Optional.empty());
    }

    static PolicyId proxyPolicyId() {
        return new PolicyId(PROXY_SCRIPT_HASH);
    }

    // ProxyDatum = Constr(0, [BData(scriptPointer), BData(scriptOwner)])
    static PlutusData proxyDatumPlutus(byte[] scriptPointer, byte[] scriptOwner) {
        return PlutusData.constr(0, PlutusData.bytes(scriptPointer), PlutusData.bytes(scriptOwner));
    }

    @Test
    void compilesSuccessfully() {
        var result = compileValidator(UVerifyProxy.class);
        assertFalse(result.hasErrors(), "UVerifyProxy should compile without errors: " + result);
        assertNotNull(result.program(), "Compiled program should not be null");
        System.out.println("UVerifyProxy script size: " + result.scriptSizeFormatted());
    }

    @Test
    void mintAdmin_evaluatesSuccess() throws Exception {
        var result = compileValidator(UVerifyProxy.class);
        assertFalse(result.hasErrors());
        var program = result.program().applyParams(
                PlutusData.bytes(UTXO_REF_TX_ID),
                PlutusData.integer(UTXO_REF_IDX));

        byte[] stn = computeStateTokenName();

        // Admin redeemer: Constr(0, []) (first in sealed interface permits)
        var redeemer = PlutusData.constr(0);

        var utxoRefInput = new TxInInfo(
                new TxOutRef(new TxId(UTXO_REF_TX_ID), UTXO_REF_IDX),
                new TxOut(pubKeyAddress(ADMIN_PKH),
                        Value.lovelace(BigInteger.valueOf(5_000_000)),
                        new OutputDatum.NoOutputDatum(), Optional.empty()));

        var proxyDatum = proxyDatumPlutus(V1_SCRIPT_HASH, ADMIN_PKH);
        var outputValue = Value.lovelace(BigInteger.valueOf(2_000_000))
                .merge(Value.singleton(proxyPolicyId(), new TokenName(stn), BigInteger.ONE));
        var scriptOutput = new TxOut(scriptAddress(), outputValue,
                new OutputDatum.OutputDatumInline(proxyDatum), Optional.empty());

        var mintValue = Value.singleton(proxyPolicyId(), new TokenName(stn), BigInteger.ONE);

        var ctx = mintingContext(proxyPolicyId())
                .redeemer(redeemer)
                .input(utxoRefInput)
                .output(scriptOutput)
                .mint(mintValue)
                .signer(ADMIN_PKH)
                .buildPlutusData();

        var evalResult = evaluate(program, ctx);
        logBudget("mintAdmin_evaluatesSuccess", evalResult);
        assertSuccess(evalResult);
    }

    @Test
    void mintAdmin_rejectsNoUtxoRef() throws Exception {
        var result = compileValidator(UVerifyProxy.class);
        assertFalse(result.hasErrors());
        var program = result.program().applyParams(
                PlutusData.bytes(UTXO_REF_TX_ID),
                PlutusData.integer(UTXO_REF_IDX));

        byte[] stn = computeStateTokenName();
        var redeemer = PlutusData.constr(0);

        // Different input (not the utxo_ref)
        var otherInput = new TxInInfo(
                new TxOutRef(new TxId(new byte[32]), BigInteger.ZERO),
                new TxOut(pubKeyAddress(ADMIN_PKH),
                        Value.lovelace(BigInteger.valueOf(5_000_000)),
                        new OutputDatum.NoOutputDatum(), Optional.empty()));

        var proxyDatum = proxyDatumPlutus(V1_SCRIPT_HASH, ADMIN_PKH);
        var outputValue = Value.lovelace(BigInteger.valueOf(2_000_000))
                .merge(Value.singleton(proxyPolicyId(), new TokenName(stn), BigInteger.ONE));
        var scriptOutput = new TxOut(scriptAddress(), outputValue,
                new OutputDatum.OutputDatumInline(proxyDatum), Optional.empty());
        var mintValue = Value.singleton(proxyPolicyId(), new TokenName(stn), BigInteger.ONE);

        var ctx = mintingContext(proxyPolicyId())
                .redeemer(redeemer)
                .input(otherInput)
                .output(scriptOutput)
                .mint(mintValue)
                .signer(ADMIN_PKH)
                .buildPlutusData();

        var evalResult = evaluate(program, ctx);
        assertFailure(evalResult);
        logBudget("mintAdmin_rejectsNoUtxoRef", evalResult);
    }

    @Test
    void spendAdmin_evaluatesSuccess() throws Exception {
        var result = compileValidator(UVerifyProxy.class);
        assertFalse(result.hasErrors());
        var program = result.program().applyParams(
                PlutusData.bytes(UTXO_REF_TX_ID),
                PlutusData.integer(UTXO_REF_IDX));

        byte[] stn = computeStateTokenName();
        var redeemer = PlutusData.constr(0);
        var datum = proxyDatumPlutus(V1_SCRIPT_HASH, ADMIN_PKH);

        var spentRef = TestDataBuilder.randomTxOutRef_typed();
        var spentInputOutput = new TxOut(scriptAddress(),
                Value.lovelace(BigInteger.valueOf(2_000_000))
                        .merge(Value.singleton(proxyPolicyId(), new TokenName(stn), BigInteger.ONE)),
                new OutputDatum.OutputDatumInline(datum), Optional.empty());
        var spentInput = new TxInInfo(spentRef, spentInputOutput);

        var scriptOutput = new TxOut(scriptAddress(),
                Value.lovelace(BigInteger.valueOf(2_000_000))
                        .merge(Value.singleton(proxyPolicyId(), new TokenName(stn), BigInteger.ONE)),
                new OutputDatum.OutputDatumInline(datum), Optional.empty());

        var ctx = spendingContext(spentRef, datum)
                .redeemer(redeemer)
                .input(spentInput)
                .output(scriptOutput)
                .signer(ADMIN_PKH)
                .buildPlutusData();

        var evalResult = evaluate(program, ctx);
        assertSuccess(evalResult);
        logBudget("spendAdmin_evaluatesSuccess", evalResult);
    }

    @Test
    void spendAdmin_rejectsWrongSigner() throws Exception {
        var result = compileValidator(UVerifyProxy.class);
        assertFalse(result.hasErrors());
        var program = result.program().applyParams(
                PlutusData.bytes(UTXO_REF_TX_ID),
                PlutusData.integer(UTXO_REF_IDX));

        var redeemer = PlutusData.constr(0);
        var datum = proxyDatumPlutus(V1_SCRIPT_HASH, ADMIN_PKH);

        var spentRef = TestDataBuilder.randomTxOutRef_typed();
        var spentInputOutput = new TxOut(scriptAddress(),
                Value.lovelace(BigInteger.valueOf(2_000_000)),
                new OutputDatum.OutputDatumInline(datum), Optional.empty());
        var spentInput = new TxInInfo(spentRef, spentInputOutput);

        var ctx = spendingContext(spentRef, datum)
                .redeemer(redeemer)
                .input(spentInput)
                .signer(OTHER_PKH)
                .buildPlutusData();

        var evalResult = evaluate(program, ctx);
        assertFailure(evalResult);
        logBudget("spendAdmin_rejectsWrongSigner", evalResult);
    }

    @Test
    void spendUser_evaluatesSuccess() throws Exception {
        var result = compileValidator(UVerifyProxy.class);
        assertFalse(result.hasErrors());
        var program = result.program().applyParams(
                PlutusData.bytes(UTXO_REF_TX_ID),
                PlutusData.integer(UTXO_REF_IDX));

        byte[] stn = computeStateTokenName();
        // User redeemer: Constr(1, []) (second in sealed interface permits)
        var redeemer = PlutusData.constr(1);

        // Spent UTXO has a non-ProxyDatum (e.g. BootstrapDatum) — the User handler ignores datum
        var bootstrapDatum = PlutusData.constr(0,
                PlutusData.list(),
                PlutusData.bytes(new byte[]{1, 2, 3}),
                PlutusData.integer(1_000_000),
                PlutusData.integer(1),
                PlutusData.list(PlutusData.bytes(ADMIN_PKH)),
                PlutusData.integer(1_700_000_000_000L),
                PlutusData.integer(10),
                PlutusData.integer(5));

        var spentRef = TestDataBuilder.randomTxOutRef_typed();
        var spentInputOutput = new TxOut(scriptAddress(),
                Value.lovelace(BigInteger.valueOf(2_000_000)),
                new OutputDatum.OutputDatumInline(bootstrapDatum), Optional.empty());
        var spentInput = new TxInInfo(spentRef, spentInputOutput);

        // Reference input: proxy state token UTXO with ProxyDatum
        var proxyDatum = proxyDatumPlutus(V1_SCRIPT_HASH, ADMIN_PKH);
        var refInput = new TxInInfo(
                TestDataBuilder.randomTxOutRef_typed(),
                new TxOut(scriptAddress(),
                        Value.lovelace(BigInteger.valueOf(2_000_000))
                                .merge(Value.singleton(proxyPolicyId(), new TokenName(stn), BigInteger.ONE)),
                        new OutputDatum.OutputDatumInline(proxyDatum), Optional.empty()));

        // Withdrawal from V1 script credential
        var v1Cred = new Credential.ScriptCredential(new ScriptHash(V1_SCRIPT_HASH));

        var pubKeyOutput = new TxOut(pubKeyAddress(ADMIN_PKH),
                Value.lovelace(BigInteger.valueOf(2_000_000)),
                new OutputDatum.NoOutputDatum(), Optional.empty());

        var ctx = spendingContext(spentRef, bootstrapDatum)
                .redeemer(redeemer)
                .input(spentInput)
                .referenceInput(refInput)
                .output(pubKeyOutput)
                .withdrawal(v1Cred, BigInteger.ZERO)
                .signer(ADMIN_PKH)
                .buildPlutusData();

        var evalResult = evaluate(program, ctx);
        logBudget("spendUser_evaluatesSuccess", evalResult);
        assertSuccess(evalResult);
    }

    private static void logBudget(String testName, com.bloxbean.cardano.julc.vm.EvalResult result) {
        var budget = result.budgetConsumed();
        System.out.println("[" + testName + "] CPU: " + budget.cpuSteps() + ", Mem: " + budget.memoryUnits());
        System.out.println("[" + testName + "] Traces: " + result.traces());
        System.out.println("[" + testName + "] Success: " + result.isSuccess());
        if (result instanceof com.bloxbean.cardano.julc.vm.EvalResult.Failure f) {
            System.out.println("[" + testName + "] Error: " + f.error());
        }
        if (result instanceof com.bloxbean.cardano.julc.vm.EvalResult.Success s) {
            System.out.println("[" + testName + "] Result: " + s.resultTerm());
        }
    }
}
