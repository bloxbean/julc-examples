package com.example.cftemplates.htlc.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.Interval;
import com.bloxbean.cardano.julc.testkit.ContractTest;
import com.bloxbean.cardano.julc.testkit.TestDataBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.*;

class CfHtlcValidatorTest extends ContractTest {

    static final byte[] OWNER = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
            11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
            21, 22, 23, 24, 25, 26, 27, 28};
    static final byte[] OTHER = new byte[]{31, 32, 33, 34, 35, 36, 37, 38, 39, 40,
            41, 42, 43, 44, 45, 46, 47, 48, 49, 50,
            51, 52, 53, 54, 55, 56, 57, 58};

    static final byte[] SECRET_ANSWER = "my_secret_answer".getBytes();
    static byte[] SECRET_HASH;
    static final BigInteger EXPIRATION = BigInteger.valueOf(1_700_000_000_000L);

    @BeforeAll
    static void setup() throws Exception {
        initCrypto();
        SECRET_HASH = MessageDigest.getInstance("SHA-256").digest(SECRET_ANSWER);
    }

    @Nested
    class UplcTests {

        @Test
        void guessCorrectAnswer_beforeExpiration_passes() throws Exception {
            var compiled = compileValidator(CfHtlcValidator.class);
            var program = compiled.program()
                    .applyParams(
                            PlutusData.bytes(SECRET_HASH),
                            PlutusData.integer(EXPIRATION),
                            PlutusData.bytes(OWNER));

            var datum = PlutusData.constr(1); // None (Optional empty)
            // Guess = Constr(0, [BData(answer)])
            var redeemer = PlutusData.constr(0, PlutusData.bytes(SECRET_ANSWER));

            var ref = TestDataBuilder.randomTxOutRef_typed();
            var ctx = spendingContext(ref, datum)
                    .redeemer(redeemer)
                    .validRange(Interval.before(EXPIRATION))
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("guessCorrectAnswer_beforeExpiration_passes", result);
        }

        @Test
        void guessWrongAnswer_fails() throws Exception {
            var compiled = compileValidator(CfHtlcValidator.class);
            var program = compiled.program()
                    .applyParams(
                            PlutusData.bytes(SECRET_HASH),
                            PlutusData.integer(EXPIRATION),
                            PlutusData.bytes(OWNER));

            var datum = PlutusData.constr(1);
            var redeemer = PlutusData.constr(0, PlutusData.bytes("wrong_answer".getBytes()));

            var ref = TestDataBuilder.randomTxOutRef_typed();
            var ctx = spendingContext(ref, datum)
                    .redeemer(redeemer)
                    .validRange(Interval.before(EXPIRATION))
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertFailure(result);
        }

        @Test
        void guessCorrectAnswer_afterExpiration_fails() throws Exception {
            var compiled = compileValidator(CfHtlcValidator.class);
            var program = compiled.program()
                    .applyParams(
                            PlutusData.bytes(SECRET_HASH),
                            PlutusData.integer(EXPIRATION),
                            PlutusData.bytes(OWNER));

            var datum = PlutusData.constr(1);
            var redeemer = PlutusData.constr(0, PlutusData.bytes(SECRET_ANSWER));

            var ref = TestDataBuilder.randomTxOutRef_typed();
            // After expiration: both lower and upper bounds > expiration
            var ctx = spendingContext(ref, datum)
                    .redeemer(redeemer)
                    .validRange(Interval.between(EXPIRATION.add(BigInteger.ONE),
                            EXPIRATION.add(BigInteger.valueOf(1000))))
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertFailure(result);
        }

        @Test
        void withdrawByOwner_afterExpiration_passes() throws Exception {
            var compiled = compileValidator(CfHtlcValidator.class);
            var program = compiled.program()
                    .applyParams(
                            PlutusData.bytes(SECRET_HASH),
                            PlutusData.integer(EXPIRATION),
                            PlutusData.bytes(OWNER));

            var datum = PlutusData.constr(1);
            // Withdraw = Constr(1, [])
            var redeemer = PlutusData.constr(1);

            var ref = TestDataBuilder.randomTxOutRef_typed();
            var ctx = spendingContext(ref, datum)
                    .redeemer(redeemer)
                    .signer(OWNER)
                    .validRange(Interval.after(EXPIRATION))
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("withdrawByOwner_afterExpiration_passes", result);
        }

        @Test
        void withdrawByOwner_beforeExpiration_fails() throws Exception {
            var compiled = compileValidator(CfHtlcValidator.class);
            var program = compiled.program()
                    .applyParams(
                            PlutusData.bytes(SECRET_HASH),
                            PlutusData.integer(EXPIRATION),
                            PlutusData.bytes(OWNER));

            var datum = PlutusData.constr(1);
            var redeemer = PlutusData.constr(1);

            var ref = TestDataBuilder.randomTxOutRef_typed();
            var ctx = spendingContext(ref, datum)
                    .redeemer(redeemer)
                    .signer(OWNER)
                    .validRange(Interval.before(EXPIRATION.subtract(BigInteger.ONE)))
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertFailure(result);
        }

        @Test
        void withdrawByNonOwner_fails() throws Exception {
            var compiled = compileValidator(CfHtlcValidator.class);
            var program = compiled.program()
                    .applyParams(
                            PlutusData.bytes(SECRET_HASH),
                            PlutusData.integer(EXPIRATION),
                            PlutusData.bytes(OWNER));

            var datum = PlutusData.constr(1);
            var redeemer = PlutusData.constr(1);

            var ref = TestDataBuilder.randomTxOutRef_typed();
            var ctx = spendingContext(ref, datum)
                    .redeemer(redeemer)
                    .signer(OTHER)
                    .validRange(Interval.after(EXPIRATION))
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
