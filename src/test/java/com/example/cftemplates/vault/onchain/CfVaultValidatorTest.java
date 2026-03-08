package com.example.cftemplates.vault.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.Interval;
import com.bloxbean.cardano.julc.ledger.PolicyId;
import com.bloxbean.cardano.julc.testkit.ContractTest;
import com.bloxbean.cardano.julc.testkit.TestDataBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

class CfVaultValidatorTest extends ContractTest {

    static final byte[] OWNER = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
            11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
            21, 22, 23, 24, 25, 26, 27, 28};
    static final byte[] OTHER = new byte[]{31, 32, 33, 34, 35, 36, 37, 38, 39, 40,
            41, 42, 43, 44, 45, 46, 47, 48, 49, 50,
            51, 52, 53, 54, 55, 56, 57, 58};
    static final BigInteger WAIT_TIME = BigInteger.valueOf(3_600_000); // 1 hour

    @BeforeAll
    static void setup() {
        initCrypto();
    }

    @Nested
    class UplcTests {

        @Test
        void finalize_ownerAfterWaitTime_passes() throws Exception {
            var compiled = compileValidator(CfVaultValidator.class);
            var program = compiled.program()
                    .applyParams(PlutusData.bytes(OWNER), PlutusData.integer(WAIT_TIME));

            BigInteger lockTime = BigInteger.valueOf(1_700_000_000_000L);
            // WithdrawDatum = Constr(0, [lockTime])
            var datum = PlutusData.constr(0, PlutusData.integer(lockTime));
            // Finalize = tag 1 (Withdraw=0, Finalize=1, Cancel=2)
            var redeemer = PlutusData.constr(1);

            var ref = TestDataBuilder.randomTxOutRef_typed();
            // After lockTime + waitTime
            BigInteger unlockTime = lockTime.add(WAIT_TIME);
            var ctx = spendingContext(ref, datum)
                    .redeemer(redeemer)
                    .signer(OWNER)
                    .validRange(Interval.after(unlockTime))
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("finalize_ownerAfterWaitTime_passes", result);
        }

        @Test
        void finalize_beforeWaitTime_fails() throws Exception {
            var compiled = compileValidator(CfVaultValidator.class);
            var program = compiled.program()
                    .applyParams(PlutusData.bytes(OWNER), PlutusData.integer(WAIT_TIME));

            BigInteger lockTime = BigInteger.valueOf(1_700_000_000_000L);
            var datum = PlutusData.constr(0, PlutusData.integer(lockTime));
            var redeemer = PlutusData.constr(1); // Finalize

            var ref = TestDataBuilder.randomTxOutRef_typed();
            // Before lockTime + waitTime
            var ctx = spendingContext(ref, datum)
                    .redeemer(redeemer)
                    .signer(OWNER)
                    .validRange(Interval.after(lockTime)) // too early
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertFailure(result);
        }

        @Test
        void finalize_nonOwner_fails() throws Exception {
            var compiled = compileValidator(CfVaultValidator.class);
            var program = compiled.program()
                    .applyParams(PlutusData.bytes(OWNER), PlutusData.integer(WAIT_TIME));

            BigInteger lockTime = BigInteger.valueOf(1_700_000_000_000L);
            var datum = PlutusData.constr(0, PlutusData.integer(lockTime));
            var redeemer = PlutusData.constr(1); // Finalize

            var ref = TestDataBuilder.randomTxOutRef_typed();
            BigInteger unlockTime = lockTime.add(WAIT_TIME);
            var ctx = spendingContext(ref, datum)
                    .redeemer(redeemer)
                    .signer(OTHER)
                    .validRange(Interval.after(unlockTime))
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertFailure(result);
        }
    }

    private static void logBudget(String testName, com.bloxbean.cardano.julc.vm.EvalResult result) {
        var budget = result.budgetConsumed();
        System.out.println("[" + testName + "] CPU: " + budget.cpuSteps() + ", Mem: " + budget.memoryUnits());
    }
}
