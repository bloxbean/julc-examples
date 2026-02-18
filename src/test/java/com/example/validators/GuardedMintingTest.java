package com.example.validators;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.testkit.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests for GuardedMinting — UPLC only.
 * <p>
 * Direct Java testing is skipped because {@code sigs.contains(redeemer)} compares
 * {@code byte[]} with {@code PlutusData}, which always returns false in Java.
 * Only Mode B (UPLC compilation) is meaningful.
 */
class GuardedMintingTest extends ContractTest {

    static final byte[] AUTH_PKH = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
            11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
            21, 22, 23, 24, 25, 26, 27, 28};
    static final byte[] OTHER_PKH = new byte[]{99, 98, 97, 96, 95, 94, 93, 92, 91, 90,
            89, 88, 87, 86, 85, 84, 83, 82, 81, 80,
            79, 78, 77, 76, 75, 74, 73, 72};

    @BeforeAll
    static void setup() {
        initCrypto();
    }

    private PlutusData buildMintingCtx(PlutusData redeemer, byte[]... signers) {
        var policyId = new com.bloxbean.cardano.julc.ledger.PolicyId(new byte[28]);
        var builder = mintingContext(policyId).redeemer(redeemer);
        for (byte[] s : signers) {
            builder.signer(s);
        }
        return builder.buildPlutusData();
    }

    @Test
    void authorizedSignerPresent_passes() throws Exception {
        var program = compileValidator(GuardedMinting.class).program();

        // redeemer = BData(pkh), signer = pkh
        var redeemer = PlutusData.bytes(AUTH_PKH);
        var ctx = buildMintingCtx(redeemer, AUTH_PKH);

        var result = evaluate(program, ctx);
        assertSuccess(result);
        logBudget("authorizedSignerPresent_passes", result);
    }

    @Test
    void unauthorizedSigner_fails() throws Exception {
        var program = compileValidator(GuardedMinting.class).program();

        // redeemer = BData(pkh), but no matching signer
        var redeemer = PlutusData.bytes(AUTH_PKH);
        var ctx = buildMintingCtx(redeemer, OTHER_PKH); // wrong signer

        var result = evaluate(program, ctx);
        assertFailure(result);
        logBudget("unauthorizedSigner_fails", result);
    }

    private static void logBudget(String testName, com.bloxbean.cardano.julc.vm.EvalResult result) {
        var budget = result.budgetConsumed();
        System.out.println("[" + testName + "] CPU: " + budget.cpuSteps() + ", Mem: " + budget.memoryUnits());
        System.out.println("[" + testName + "] Traces: " + result.traces());
    }
}
