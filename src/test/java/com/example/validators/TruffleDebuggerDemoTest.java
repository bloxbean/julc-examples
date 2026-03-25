package com.example.validators;

import com.bloxbean.cardano.julc.compiler.CompileResult;
import com.bloxbean.cardano.julc.core.Constant;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Term;
import com.bloxbean.cardano.julc.ledger.PubKeyHash;
import com.bloxbean.cardano.julc.ledger.TxOutRef;
import com.bloxbean.cardano.julc.ledger.Value;
import com.bloxbean.cardano.julc.testkit.ContractTest;
import com.bloxbean.cardano.julc.testkit.TestDataBuilder;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.julc.vm.truffle.debug.JulcDebugger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Demonstrates the Truffle JIT and step-through debugger with a real validator.
 *
 * <h3>JIT Quick Wins</h3>
 * When julc-vm-truffle is on the classpath, the Truffle VM is auto-selected
 * (priority 200 vs Java's 100). The JIT annotations ({@code @CompilationFinal},
 * {@code @TruffleBoundary}) are transparent — all existing tests exercise them.
 *
 * <h3>Step-Through Debugger</h3>
 * Uses the Truffle Debugger API (same infrastructure as VS Code DAP / IntelliJ
 * GraalVM Tools). Steps through UPLC execution at Java source line granularity.
 */
class TruffleDebuggerDemoTest extends ContractTest {

    @BeforeAll
    static void setup() {
        initCrypto();
    }

    // ---- JIT: verify Truffle VM is active ----

    @Test
    void truffleVmIsActive() {
        String provider = vm().providerName();
        System.out.println("VM provider: " + provider);
        assertEquals("Truffle", provider,
                "Truffle VM should be auto-selected (highest priority)");
    }

    // ---- JIT: existing validator works identically under Truffle ----

    @Test
    void escrowValidator_passingPath_underTruffle() {
        CompileResult compiled = compileValidator(EscrowValidator.class);
        EvalResult result = evaluate(compiled.program(), passingCompleteCtx());
        assertSuccess(result);

        System.out.println("EscrowValidator (complete, pass) — Truffle VM");
        System.out.println("  CPU: " + result.budgetConsumed().cpuSteps());
        System.out.println("  Mem: " + result.budgetConsumed().memoryUnits());
    }

    @Test
    void escrowValidator_failingPath_underTruffle() {
        CompileResult compiled = compileValidator(EscrowValidator.class);
        EvalResult result = evaluate(compiled.program(), failingRefundCtx());
        assertFailure(result);

        System.out.println("EscrowValidator (refund, fail) — Truffle VM");
        System.out.println("  CPU: " + result.budgetConsumed().cpuSteps());
    }

    // ---- JIT: warmup benchmark ----

    @Test
    void jitWarmup_escrowValidator() {
        CompileResult compiled = compileValidator(EscrowValidator.class);
        PlutusData ctx = passingCompleteCtx();

        // Cold run (interpreted)
        long coldStart = System.nanoTime();
        for (int i = 0; i < 10; i++) {
            evaluate(compiled.program(), ctx);
        }
        long coldMs = (System.nanoTime() - coldStart) / 1_000_000;

        // Warmup — Truffle JIT compiles hot CallTargets after ~1000 iterations
        for (int i = 0; i < 2000; i++) {
            evaluate(compiled.program(), ctx);
        }

        // Hot run (JIT-compiled if GraalVM compiler is available)
        long hotStart = System.nanoTime();
        for (int i = 0; i < 10; i++) {
            evaluate(compiled.program(), ctx);
        }
        long hotMs = (System.nanoTime() - hotStart) / 1_000_000;

        System.out.println();
        System.out.println("=== JIT WARMUP BENCHMARK: EscrowValidator ===");
        System.out.println("  Cold (first 10):        " + coldMs + " ms");
        System.out.println("  Warmup:                 2000 iterations");
        System.out.println("  Hot  (next 10):         " + hotMs + " ms");
        if (coldMs > 0) {
            System.out.println("  Speedup:                " +
                    String.format("%.1fx", (double) coldMs / Math.max(hotMs, 1)));
        }
        System.out.println();
    }

    // ---- Debugger: step through every statement ----

    @Test
    void stepThrough_escrowValidator_completePath() {
        CompileResult compiled = compileValidatorWithSourceMap(EscrowValidator.class);
        Term term = applyArg(compiled.program().term(), passingCompleteCtx());

        var steps = new ArrayList<String>();

        try (var debugger = JulcDebugger.create()) {
            debugger.sourceMap(compiled.sourceMap());
            EvalResult result = debugger.stepThrough(term, event -> {
                steps.add(String.format("%-30s line %3d  CPU=%,d  Mem=%,d",
                        event.fileName(), event.line(),
                        event.cpuConsumed(), event.memConsumed()));
                event.stepOver();
            });

            assertTrue(result.isSuccess(),
                    "Expected success but got: " + describeResult(result));
        }

        System.out.println();
        System.out.println("=== DEBUGGER STEP-THROUGH: EscrowValidator (complete path) ===");
        steps.forEach(s -> System.out.println("  " + s));
        System.out.println("  Total steps: " + steps.size());
        System.out.println();

        assertFalse(steps.isEmpty(), "Should have step events with source map");
    }

    // ---- Debugger: step through failing path ----

    @Test
    void stepThrough_escrowValidator_failingRefund() {
        CompileResult compiled = compileValidatorWithSourceMap(EscrowValidator.class);
        Term term = applyArg(compiled.program().term(), failingRefundCtx());

        var steps = new ArrayList<String>();

        try (var debugger = JulcDebugger.create()) {
            debugger.sourceMap(compiled.sourceMap());
            EvalResult result = debugger.stepThrough(term, event -> {
                steps.add(String.format("%-30s line %3d  CPU=%,d",
                        event.fileName(), event.line(), event.cpuConsumed()));
                event.stepOver();
            });

            // Refund path fails (deadline not passed)
            assertFalse(result.isSuccess());
        }

        System.out.println();
        System.out.println("=== DEBUGGER STEP-THROUGH: EscrowValidator (refund path, FAILS) ===");
        steps.forEach(s -> System.out.println("  " + s));
        System.out.println("  Total steps: " + steps.size());
        System.out.println();
    }

    // ---- Debugger: breakpoints ----

    @Test
    void breakAt_escrowValidator() {
        CompileResult compiled = compileValidatorWithSourceMap(EscrowValidator.class);
        Term term = applyArg(compiled.program().term(), passingCompleteCtx());

        var hits = new ArrayList<String>();

        try (var debugger = JulcDebugger.create()) {
            debugger.sourceMap(compiled.sourceMap())
                    .breakAt("EscrowValidator.java", 24)   // if (redeemer.action()...)
                    .breakAt("EscrowValidator.java", 34);   // checkComplete: buyerSigned

            EvalResult result = debugger.run(term, event -> {
                hits.add(String.format("BREAK %s:%d  CPU=%,d  Mem=%,d",
                        event.fileName(), event.line(),
                        event.cpuConsumed(), event.memConsumed()));
                event.resume();
            });

            assertTrue(result.isSuccess(),
                    "Expected success but got: " + describeResult(result));
        }

        System.out.println();
        System.out.println("=== BREAKPOINT HITS: EscrowValidator ===");
        hits.forEach(h -> System.out.println("  " + h));
        System.out.println();
    }

    // ---- Debugger: budget parity with non-debug evaluation ----

    @Test
    void budgetParity_debugVsNormal() {
        CompileResult compiled = compileValidatorWithSourceMap(EscrowValidator.class);
        PlutusData ctx = passingCompleteCtx();

        // Normal evaluation (via TruffleVmProvider)
        EvalResult normalResult = evaluate(compiled.program(), ctx);
        assertSuccess(normalResult);

        // Debug evaluation (via JulcDebugger with Truffle Debugger API)
        Term term = applyArg(compiled.program().term(), ctx);
        try (var debugger = JulcDebugger.create()) {
            debugger.sourceMap(compiled.sourceMap());
            EvalResult debugResult = debugger.stepThrough(term, event -> event.stepOver());

            assertTrue(debugResult.isSuccess(),
                    "Debug eval should succeed: " + describeResult(debugResult));

            System.out.println();
            System.out.println("=== BUDGET PARITY ===");
            System.out.println("  Normal: CPU=" + normalResult.budgetConsumed().cpuSteps()
                    + "  Mem=" + normalResult.budgetConsumed().memoryUnits());
            System.out.println("  Debug:  CPU=" + debugResult.budgetConsumed().cpuSteps()
                    + "  Mem=" + debugResult.budgetConsumed().memoryUnits());

            assertEquals(normalResult.budgetConsumed().cpuSteps(),
                    debugResult.budgetConsumed().cpuSteps(),
                    "CPU budget must match between debug and normal evaluation");
            assertEquals(normalResult.budgetConsumed().memoryUnits(),
                    debugResult.budgetConsumed().memoryUnits(),
                    "Memory budget must match between debug and normal evaluation");
        }
    }

    // ---- Debugger: kill stops execution ----

    @Test
    void kill_abortsExecution() {
        CompileResult compiled = compileValidatorWithSourceMap(EscrowValidator.class);
        Term term = applyArg(compiled.program().term(), passingCompleteCtx());

        int[] stepCount = {0};

        try (var debugger = JulcDebugger.create()) {
            debugger.sourceMap(compiled.sourceMap());
            EvalResult result = debugger.stepThrough(term, event -> {
                stepCount[0]++;
                if (stepCount[0] >= 3) {
                    event.kill(); // Kill after 3 steps
                } else {
                    event.stepOver();
                }
            });

            assertFalse(result.isSuccess(), "Kill should abort execution");
            System.out.println("Killed after " + stepCount[0] + " steps");
        }
    }

    // --- helpers ---

    private static Term applyArg(Term program, PlutusData arg) {
        return new Term.Apply(program, Term.const_(Constant.data(arg)));
    }

    private static String describeResult(EvalResult result) {
        return switch (result) {
            case EvalResult.Success s -> "Success";
            case EvalResult.Failure f -> "Failure: " + f.error();
            case EvalResult.BudgetExhausted b -> "BudgetExhausted";
        };
    }

    private PlutusData escrowDatum() {
        return PlutusData.constr(0,
                PlutusData.bytes(new byte[28]),
                PlutusData.bytes(new byte[28]),
                PlutusData.integer(1000),
                PlutusData.integer(10_000_000));
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
                .validRange(com.bloxbean.cardano.julc.ledger.Interval.before(BigInteger.valueOf(500)))
                .buildPlutusData();
    }
}
