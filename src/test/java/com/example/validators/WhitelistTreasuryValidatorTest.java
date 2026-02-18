package com.example.validators;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.testkit.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UPLC compilation tests for WhitelistTreasuryValidator — validates on-chain
 * execution of the ByteStringType HOF double-unwrap fix:
 * <ul>
 *   <li>Nested HOFs: filter(w -> signatories.any(sig -> ...))</li>
 *   <li>Untyped lambdas on JulcList&lt;PubKeyHash&gt;</li>
 *   <li>list.all() with nested list.any() (UpdateWhitelist path)</li>
 * </ul>
 */
class WhitelistTreasuryValidatorTest extends ContractTest {

    // 28-byte PubKeyHashes (realistic length)
    static final byte[] SIGNER_A = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
            11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
            21, 22, 23, 24, 25, 26, 27, 28};
    static final byte[] SIGNER_B = new byte[]{31, 32, 33, 34, 35, 36, 37, 38, 39, 40,
            41, 42, 43, 44, 45, 46, 47, 48, 49, 50,
            51, 52, 53, 54, 55, 56, 57, 58};
    static final byte[] SIGNER_C = new byte[]{61, 62, 63, 64, 65, 66, 67, 68, 69, 70,
            71, 72, 73, 74, 75, 76, 77, 78, 79, 80,
            81, 82, 83, 84, 85, 86, 87, 88};
    static final byte[] OUTSIDER = new byte[]{91, 92, 93, 94, 95, 96, 97, 98, 99, 100,
            101, 102, 103, 104, 105, 106, 107, 108, 109, 110,
            111, 112, 113, 114, 115, 116, 117, 118};

    @BeforeAll
    static void setup() {
        initCrypto();
    }

    // WhitelistDatum: Constr(0, [ListData([BData(pkh1), BData(pkh2), ...]), IData(threshold)])
    // PubKeyHash maps to ByteStringType — list elements are BData-wrapped bytes
    private PlutusData buildDatum(BigInteger threshold, byte[]... signers) {
        var signerList = new PlutusData[signers.length];
        for (int i = 0; i < signers.length; i++) {
            signerList[i] = PlutusData.bytes(signers[i]);
        }
        return PlutusData.constr(0, PlutusData.list(signerList), PlutusData.integer(threshold));
    }

    // Withdraw: Constr(0, [IData(amount)])
    private PlutusData withdrawRedeemer(long amount) {
        return PlutusData.constr(0, PlutusData.integer(BigInteger.valueOf(amount)));
    }

    // UpdateWhitelist: Constr(1, [])
    private PlutusData updateWhitelistRedeemer() {
        return PlutusData.constr(1);
    }

    // --- Withdraw tests ---

    @Test
    void withdraw_thresholdMet_passes() throws Exception {
        var result = compileValidator(WhitelistTreasuryValidator.class);
        assertFalse(result.hasErrors(), "Compilation should succeed: " + result);
        var program = result.program();

        // Whitelist: A, B, C — threshold: 2
        var datum = buildDatum(BigInteger.valueOf(2), SIGNER_A, SIGNER_B, SIGNER_C);
        var redeemer = withdrawRedeemer(5_000_000);

        var ref = TestDataBuilder.randomTxOutRef_typed();
        // Both A and B sign (2 >= threshold 2)
        var ctx = spendingContext(ref, datum)
                .redeemer(redeemer)
                .signer(SIGNER_A)
                .signer(SIGNER_B)
                .buildPlutusData();

        var evalResult = evaluate(program, ctx);
        assertSuccess(evalResult);
        logBudget("withdraw_thresholdMet_passes", evalResult);
    }

    @Test
    void withdraw_allThreeSign_passes() throws Exception {
        var result = compileValidator(WhitelistTreasuryValidator.class);
        var program = result.program();

        // Whitelist: A, B, C — threshold: 2
        var datum = buildDatum(BigInteger.valueOf(2), SIGNER_A, SIGNER_B, SIGNER_C);
        var redeemer = withdrawRedeemer(5_000_000);

        var ref = TestDataBuilder.randomTxOutRef_typed();
        // All three sign (3 >= threshold 2)
        var ctx = spendingContext(ref, datum)
                .redeemer(redeemer)
                .signer(SIGNER_A)
                .signer(SIGNER_B)
                .signer(SIGNER_C)
                .buildPlutusData();

        var evalResult = evaluate(program, ctx);
        assertSuccess(evalResult);
        logBudget("withdraw_allThreeSign_passes", evalResult);
    }

    @Test
    void withdraw_exactThreshold_passes() throws Exception {
        var result = compileValidator(WhitelistTreasuryValidator.class);
        var program = result.program();

        // Whitelist: A, B — threshold: 2 (exact)
        var datum = buildDatum(BigInteger.valueOf(2), SIGNER_A, SIGNER_B);
        var redeemer = withdrawRedeemer(10_000_000);

        var ref = TestDataBuilder.randomTxOutRef_typed();
        var ctx = spendingContext(ref, datum)
                .redeemer(redeemer)
                .signer(SIGNER_A)
                .signer(SIGNER_B)
                .buildPlutusData();

        var evalResult = evaluate(program, ctx);
        assertSuccess(evalResult);
        logBudget("withdraw_exactThreshold_passes", evalResult);
    }

    @Test
    void withdraw_belowThreshold_fails() throws Exception {
        var result = compileValidator(WhitelistTreasuryValidator.class);
        var program = result.program();

        // Whitelist: A, B, C — threshold: 2
        var datum = buildDatum(BigInteger.valueOf(2), SIGNER_A, SIGNER_B, SIGNER_C);
        var redeemer = withdrawRedeemer(5_000_000);

        var ref = TestDataBuilder.randomTxOutRef_typed();
        // Only A signs (1 < threshold 2)
        var ctx = spendingContext(ref, datum)
                .redeemer(redeemer)
                .signer(SIGNER_A)
                .buildPlutusData();

        var evalResult = evaluate(program, ctx);
        assertFailure(evalResult);
        logBudget("withdraw_belowThreshold_fails", evalResult);
    }

    @Test
    void withdraw_noSigners_fails() throws Exception {
        var result = compileValidator(WhitelistTreasuryValidator.class);
        var program = result.program();

        // Whitelist: A, B — threshold: 1
        var datum = buildDatum(BigInteger.ONE, SIGNER_A, SIGNER_B);
        var redeemer = withdrawRedeemer(5_000_000);

        var ref = TestDataBuilder.randomTxOutRef_typed();
        // Outsider signs (not in whitelist)
        var ctx = spendingContext(ref, datum)
                .redeemer(redeemer)
                .signer(OUTSIDER)
                .buildPlutusData();

        var evalResult = evaluate(program, ctx);
        assertFailure(evalResult);
        logBudget("withdraw_noSigners_fails", evalResult);
    }

    @Test
    void withdraw_singleSignerThreshold1_passes() throws Exception {
        var result = compileValidator(WhitelistTreasuryValidator.class);
        var program = result.program();

        // Whitelist: A, B, C — threshold: 1
        var datum = buildDatum(BigInteger.ONE, SIGNER_A, SIGNER_B, SIGNER_C);
        var redeemer = withdrawRedeemer(5_000_000);

        var ref = TestDataBuilder.randomTxOutRef_typed();
        // Only B signs (1 >= threshold 1)
        var ctx = spendingContext(ref, datum)
                .redeemer(redeemer)
                .signer(SIGNER_B)
                .buildPlutusData();

        var evalResult = evaluate(program, ctx);
        assertSuccess(evalResult);
        logBudget("withdraw_singleSignerThreshold1_passes", evalResult);
    }

    // --- UpdateWhitelist tests ---

    @Test
    void updateWhitelist_allSign_passes() throws Exception {
        var result = compileValidator(WhitelistTreasuryValidator.class);
        var program = result.program();

        // Whitelist: A, B — all must sign for update
        var datum = buildDatum(BigInteger.valueOf(2), SIGNER_A, SIGNER_B);
        var redeemer = updateWhitelistRedeemer();

        var ref = TestDataBuilder.randomTxOutRef_typed();
        var ctx = spendingContext(ref, datum)
                .redeemer(redeemer)
                .signer(SIGNER_A)
                .signer(SIGNER_B)
                .buildPlutusData();

        var evalResult = evaluate(program, ctx);
        assertSuccess(evalResult);
        logBudget("updateWhitelist_allSign_passes", evalResult);
    }

    @Test
    void updateWhitelist_missingSigner_fails() throws Exception {
        var result = compileValidator(WhitelistTreasuryValidator.class);
        var program = result.program();

        // Whitelist: A, B, C — all must sign for update
        var datum = buildDatum(BigInteger.valueOf(2), SIGNER_A, SIGNER_B, SIGNER_C);
        var redeemer = updateWhitelistRedeemer();

        var ref = TestDataBuilder.randomTxOutRef_typed();
        // Only A and B sign — C is missing
        var ctx = spendingContext(ref, datum)
                .redeemer(redeemer)
                .signer(SIGNER_A)
                .signer(SIGNER_B)
                .buildPlutusData();

        var evalResult = evaluate(program, ctx);
        assertFailure(evalResult);
        logBudget("updateWhitelist_missingSigner_fails", evalResult);
    }

    // --- Trace tests ---

    @Test
    void withdraw_traces_emitted() throws Exception {
        var result = compileValidator(WhitelistTreasuryValidator.class);
        var program = result.program();

        var datum = buildDatum(BigInteger.ONE, SIGNER_A);
        var redeemer = withdrawRedeemer(5_000_000);

        var ref = TestDataBuilder.randomTxOutRef_typed();
        var ctx = spendingContext(ref, datum)
                .redeemer(redeemer)
                .signer(SIGNER_A)
                .buildPlutusData();

        var evalResult = evaluate(program, ctx);
        assertSuccess(evalResult);
        BudgetAssertions.assertTrace(evalResult, "Withdraw: checking threshold signatures");
    }

    private static void logBudget(String testName, com.bloxbean.cardano.julc.vm.EvalResult result) {
        var budget = result.budgetConsumed();
        System.out.println("[" + testName + "] CPU: " + budget.cpuSteps() + ", Mem: " + budget.memoryUnits());
        System.out.println("[" + testName + "] Traces: " + result.traces());
    }
}
