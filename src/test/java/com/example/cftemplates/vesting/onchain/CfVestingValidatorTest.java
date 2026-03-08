package com.example.cftemplates.vesting.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.testkit.ContractTest;
import com.bloxbean.cardano.julc.testkit.TestDataBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

class CfVestingValidatorTest extends ContractTest {

    static final byte[] OWNER = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
            11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
            21, 22, 23, 24, 25, 26, 27, 28};
    static final byte[] BENEFICIARY = new byte[]{31, 32, 33, 34, 35, 36, 37, 38, 39, 40,
            41, 42, 43, 44, 45, 46, 47, 48, 49, 50,
            51, 52, 53, 54, 55, 56, 57, 58};
    static final byte[] OTHER = new byte[]{99, 98, 97, 96, 95, 94, 93, 92, 91, 90,
            89, 88, 87, 86, 85, 84, 83, 82, 81, 80,
            79, 78, 77, 76, 75, 74, 73, 72};

    static final BigInteger LOCK_UNTIL = BigInteger.valueOf(1_700_000_000_000L); // POSIX ms

    @BeforeAll
    static void setup() {
        initCrypto();
    }

    @Nested
    class DirectJavaTests {

        private ScriptContext buildCtx(byte[][] signers, BigInteger validFrom) {
            var sigList = com.bloxbean.cardano.julc.core.types.JulcList.<PubKeyHash>empty();
            for (byte[] sig : signers) {
                sigList = sigList.prepend(new PubKeyHash(sig));
            }
            var range = Interval.after(validFrom);
            var txInfo = new TxInfo(
                    com.bloxbean.cardano.julc.core.types.JulcList.of(),
                    com.bloxbean.cardano.julc.core.types.JulcList.of(),
                    com.bloxbean.cardano.julc.core.types.JulcList.of(),
                    BigInteger.valueOf(200_000),
                    Value.zero(),
                    com.bloxbean.cardano.julc.core.types.JulcList.of(),
                    com.bloxbean.cardano.julc.core.types.JulcMap.empty(),
                    range, sigList,
                    com.bloxbean.cardano.julc.core.types.JulcMap.empty(),
                    com.bloxbean.cardano.julc.core.types.JulcMap.empty(),
                    new TxId(new byte[32]),
                    com.bloxbean.cardano.julc.core.types.JulcMap.empty(),
                    com.bloxbean.cardano.julc.core.types.JulcList.of(),
                    java.util.Optional.empty(), java.util.Optional.empty());
            return new ScriptContext(txInfo, PlutusData.UNIT,
                    new ScriptInfo.SpendingScript(
                            new TxOutRef(new TxId(new byte[32]), BigInteger.ZERO),
                            java.util.Optional.empty()));
        }

        @Test
        void ownerCanUnlockAnytime() {
            var datum = new CfVestingValidator.VestingDatum(LOCK_UNTIL, OWNER, BENEFICIARY);
            var ctx = buildCtx(new byte[][]{OWNER}, BigInteger.ZERO);
            assertTrue(CfVestingValidator.validate(datum, PlutusData.UNIT, ctx));
        }

        @Test
        void beneficiaryCanUnlockAfterLockTime() {
            var datum = new CfVestingValidator.VestingDatum(LOCK_UNTIL, OWNER, BENEFICIARY);
            var ctx = buildCtx(new byte[][]{BENEFICIARY}, LOCK_UNTIL);
            assertTrue(CfVestingValidator.validate(datum, PlutusData.UNIT, ctx));
        }

        @Test
        void beneficiaryCannotUnlockBeforeLockTime() {
            var datum = new CfVestingValidator.VestingDatum(LOCK_UNTIL, OWNER, BENEFICIARY);
            var ctx = buildCtx(new byte[][]{BENEFICIARY}, LOCK_UNTIL.subtract(BigInteger.ONE));
            assertFalse(CfVestingValidator.validate(datum, PlutusData.UNIT, ctx));
        }

        @Test
        void otherSignerFails() {
            var datum = new CfVestingValidator.VestingDatum(LOCK_UNTIL, OWNER, BENEFICIARY);
            var ctx = buildCtx(new byte[][]{OTHER}, LOCK_UNTIL);
            assertFalse(CfVestingValidator.validate(datum, PlutusData.UNIT, ctx));
        }
    }

    @Nested
    class UplcTests {

        private PlutusData buildDatum() {
            return PlutusData.constr(0,
                    PlutusData.integer(LOCK_UNTIL),
                    PlutusData.bytes(OWNER),
                    PlutusData.bytes(BENEFICIARY));
        }

        @Test
        void ownerSigned_passes() throws Exception {
            var program = compileValidator(CfVestingValidator.class).program();

            var datum = buildDatum();
            var redeemer = PlutusData.constr(0);

            var ref = TestDataBuilder.randomTxOutRef_typed();
            var ctx = spendingContext(ref, datum)
                    .redeemer(redeemer)
                    .signer(OWNER)
                    .validRange(Interval.after(BigInteger.ZERO))
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("ownerSigned_passes", result);
        }

        @Test
        void beneficiaryAfterLockTime_passes() throws Exception {
            var program = compileValidator(CfVestingValidator.class).program();

            var datum = buildDatum();
            var redeemer = PlutusData.constr(0);

            var ref = TestDataBuilder.randomTxOutRef_typed();
            var ctx = spendingContext(ref, datum)
                    .redeemer(redeemer)
                    .signer(BENEFICIARY)
                    .validRange(Interval.after(LOCK_UNTIL))
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("beneficiaryAfterLockTime_passes", result);
        }

        @Test
        void beneficiaryBeforeLockTime_fails() throws Exception {
            var program = compileValidator(CfVestingValidator.class).program();

            var datum = buildDatum();
            var redeemer = PlutusData.constr(0);

            var ref = TestDataBuilder.randomTxOutRef_typed();
            var ctx = spendingContext(ref, datum)
                    .redeemer(redeemer)
                    .signer(BENEFICIARY)
                    .validRange(Interval.after(LOCK_UNTIL.subtract(BigInteger.ONE)))
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertFailure(result);
        }

        @Test
        void noValidSigner_fails() throws Exception {
            var program = compileValidator(CfVestingValidator.class).program();

            var datum = buildDatum();
            var redeemer = PlutusData.constr(0);

            var ref = TestDataBuilder.randomTxOutRef_typed();
            var ctx = spendingContext(ref, datum)
                    .redeemer(redeemer)
                    .signer(OTHER)
                    .validRange(Interval.after(LOCK_UNTIL))
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
