package com.example.validators;

import com.bloxbean.cardano.julc.compiler.CompileResult;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.source.SourceLocation;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.testkit.*;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.trace.ExecutionTraceEntry;
import com.example.util.SumTest;
import com.example.util.ValidationUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Demonstrates source map debugging and execution tracing using the testkit API.
 * <p>
 * No direct TruffleVmProvider references needed — everything goes through
 * {@link ContractTest} helpers and {@link ValidatorTest} statics.
 */
class SourceMapDebugDemoTest extends ContractTest {

    @BeforeAll
    static void setup() {
        initCrypto();
    }

    // ---- Demo 1: resolveErrorLocation pinpoints the failing Java line ----

    @Test
    void resolveErrorLocation_pinpointsFailingJavaLine() {
        CompileResult compiled = compileValidatorWithSourceMap(EscrowValidator.class);

        EvalResult result = evaluate(compiled.program(), failingCompleteCtx());
        assertFailure(result);

        SourceLocation location = resolveErrorLocation(result, compiled.sourceMap());
        System.out.println();
        System.out.println("=== SOURCE MAP ERROR LOCATION ===");
        System.out.println("  " + location);
        System.out.println();
    }

    // ---- Demo 2: evaluateWithTrace — one call, trace included ----

    @Test
    void evaluateWithTrace_escrowCompletePath() {
        CompileResult compiled = compileValidatorWithSourceMap(EscrowValidator.class);

        // evaluateWithTrace handles sourceMap + tracingEnabled for you
        EvalResult result = evaluateWithTrace(compiled, passingCompleteCtx());
        assertSuccess(result);

        System.out.println();
        System.out.println("=== EXECUTION TRACE: EscrowValidator (complete path, success) ===");
        System.out.print(formatExecutionTrace());
        System.out.println();
    }

    @Test
    void evaluateWithTrace_escrowRefundFails() {
        CompileResult compiled = compileValidatorWithSourceMap(EscrowValidator.class);

        EvalResult result = evaluateWithTrace(compiled, failingRefundCtx());
        assertFailure(result);

        System.out.println();
        System.out.println("=== EXECUTION TRACE: EscrowValidator (refund path, FAILURE) ===");
        System.out.print(formatExecutionTrace());
        logResult("escrow-refund-fail", result, compiled.sourceMap());
        System.out.println();
    }

    // ---- Demo 3: Static API via ValidatorTest.evaluateWithTrace ----

    @Test
    void staticApi_evaluateWithTrace() {
        CompileResult compiled = ValidatorTest.compileValidatorWithSourceMap(EscrowValidator.class);

        var evalWithTrace = ValidatorTest.evaluateWithTrace(compiled, passingCompleteCtx());
        assertSuccess(evalWithTrace.result());

        System.out.println();
        System.out.println("=== ValidatorTest.evaluateWithTrace (static API) ===");
        System.out.print(evalWithTrace.formatTrace());
        System.out.println();
    }

    // ---- Demo 3b: Budget summary via static API ----

    @Test
    void staticApi_budgetSummary() {
        CompileResult compiled = ValidatorTest.compileValidatorWithSourceMap(EscrowValidator.class);

        var evalWithTrace = ValidatorTest.evaluateWithTrace(compiled, passingCompleteCtx());
        assertSuccess(evalWithTrace.result());

        System.out.println();
        System.out.println("=== BUDGET SUMMARY: EscrowValidator (static API) ===");
        System.out.print(evalWithTrace.formatBudgetSummary());
        System.out.println();
    }

    // ---- Demo 3c: Budget-attributed trace via ContractTest helper ----

    @Test
    void evaluateWithTrace_budgetSummary() {
        CompileResult compiled = compileValidatorWithSourceMap(EscrowValidator.class);

        EvalResult result = evaluateWithTrace(compiled, passingCompleteCtx());
        assertSuccess(result);

        System.out.println();
        System.out.println("=== BUDGET-ATTRIBUTED TRACE: EscrowValidator ===");
        System.out.print(formatExecutionTrace());
        System.out.println();
        System.out.println("=== BUDGET SUMMARY: EscrowValidator ===");
        System.out.print(formatBudgetSummary());
        System.out.println();
    }

    // ---- Demo 4: VestingValidator execution trace ----

    @Test
    void executionTrace_vestingValidator() {
        CompileResult compiled = compileValidatorWithSourceMap(VestingValidator.class);

        var program = compiled.program().applyParams(
                PlutusData.integer(42),
                PlutusData.bytes("hello".getBytes(StandardCharsets.UTF_8)));

        // Use a new CompileResult with the parameterized program
        var parameterized = new CompileResult(program, compiled.diagnostics(),
                compiled.params(), compiled.pirTerm(), compiled.uplcTerm(), compiled.sourceMap());

        var datum = PlutusData.constr(0, PlutusData.bytes(new byte[28]));
        var redeemer = PlutusData.constr(0,
                PlutusData.constr(0,
                        PlutusData.integer(42),
                        PlutusData.bytes("hello".getBytes(StandardCharsets.UTF_8))));

        var ref = TestDataBuilder.randomTxOutRef_typed();
        var ctx = spendingContext(ref, datum)
                .redeemer(redeemer)
                .signer(new byte[28])
                .output(TestDataBuilder.txOut(
                        TestDataBuilder.pubKeyAddress(new PubKeyHash(new byte[28])),
                        Value.lovelace(BigInteger.valueOf(5_000_000))))
                .output(TestDataBuilder.txOut(
                        TestDataBuilder.pubKeyAddress(new PubKeyHash(new byte[28])),
                        Value.lovelace(BigInteger.valueOf(2_000_000))))
                .buildPlutusData();

        EvalResult result = evaluateWithTrace(parameterized, ctx);

        System.out.println();
        System.out.println("=== EXECUTION TRACE: VestingValidator (success) ===");
        System.out.print(formatExecutionTrace());
        System.out.println("  Budget: CPU=" + result.budgetConsumed().cpuSteps()
                + ", Mem=" + result.budgetConsumed().memoryUnits());
        System.out.println();
    }

    // ---- Demo 5: assertFailure with source map ----

    @Test
    void assertFailureWithSourceMap_richErrorMessages() {
        CompileResult compiled = compileValidatorWithSourceMap(EscrowValidator.class);

        EvalResult result = evaluate(compiled.program(), failingRefundCtx());
        assertFailure(result, compiled.sourceMap());

        System.out.println();
        System.out.println("=== assertFailure WITH SOURCE MAP ===");
        System.out.println("  " + BudgetAssertions.describeResult(result, compiled.sourceMap()));
        System.out.println();
    }

    // ---- Demo 6: JulcEval.trace() — method-level execution trace ----

    @Test
    void julcEval_traceShowsCallChain() {
        // SumTest.sum() is simple — but demonstrates the API
        var eval = JulcEval.forClass(SumTest.class).trace();
        var result = eval.call("sum", 4, 3).asInteger();
        assertEquals(java.math.BigInteger.valueOf(7), result);

        System.out.println();
        System.out.println("=== JulcEval.trace(): SumTest.sum(4, 3) ===");
        System.out.print(eval.formatLastTrace());
        System.out.println();
    }

    @Test
    void julcEval_traceBudgetSummary() {
        var eval = JulcEval.forClass(SumTest.class).trace();
        eval.call("sum", 4, 3).asInteger();

        System.out.println();
        System.out.println("=== JulcEval: Budget summary for SumTest.sum(4, 3) ===");
        System.out.print(eval.formatLastBudgetSummary());
        System.out.println();
    }

    @Test
    void julcEval_traceOnProxy() {
        // Proxy interface with trace
        interface SumProxy {
            java.math.BigInteger sum(int a, int b);
        }

        var eval = JulcEval.forClass(SumTest.class).trace();
        var proxy = eval.create(SumProxy.class);

        assertEquals(java.math.BigInteger.valueOf(7), proxy.sum(4, 3));

        System.out.println();
        System.out.println("=== JulcEval.trace() via proxy: SumTest.sum(4, 3) ===");
        System.out.print(eval.formatLastTrace());
        System.out.println();
    }

    // --- helpers ---

    private PlutusData escrowDatum() {
        return PlutusData.constr(0,
                PlutusData.bytes(new byte[28]),
                PlutusData.bytes(new byte[28]),
                PlutusData.integer(1000),
                PlutusData.integer(10_000_000));
    }

    private PlutusData failingCompleteCtx() {
        var ref = TestDataBuilder.randomTxOutRef_typed();
        return spendingContext(ref, escrowDatum())
                .redeemer(PlutusData.constr(0, PlutusData.integer(0)))
                .signer(new byte[28])
                .output(TestDataBuilder.txOut(
                        TestDataBuilder.pubKeyAddress(new PubKeyHash(new byte[28])),
                        Value.lovelace(BigInteger.valueOf(1_000_000))))
                .buildPlutusData();
    }

    private PlutusData passingCompleteCtx() {
        var ref = TestDataBuilder.randomTxOutRef_typed();
        return spendingContext(ref, escrowDatum())
                .redeemer(PlutusData.constr(0, PlutusData.integer(0)))
                .signer(new byte[28])
                .output(TestDataBuilder.txOut(
                        TestDataBuilder.pubKeyAddress(new PubKeyHash(new byte[28])),
                        Value.lovelace(BigInteger.valueOf(10_000_000))))
                .buildPlutusData();
    }

    private PlutusData failingRefundCtx() {
        var ref = TestDataBuilder.randomTxOutRef_typed();
        return spendingContext(ref, escrowDatum())
                .redeemer(PlutusData.constr(0, PlutusData.integer(1)))
                .signer(new byte[28])
                .validRange(Interval.before(BigInteger.valueOf(500)))
                .buildPlutusData();
    }
}
