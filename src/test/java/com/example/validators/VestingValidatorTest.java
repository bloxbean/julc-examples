package com.example.validators;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.core.types.JulcMap;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.testkit.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for VestingValidator — the most complex validator with @Param, nested records,
 * for-each loops, and multi-file compilation (uses SumTest library).
 */
class VestingValidatorTest extends ContractTest {

    static final byte[] BENEFICIARY = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
            11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
            21, 22, 23, 24, 25, 26, 27, 28};
    static final byte[] OTHER_PKH = new byte[]{99, 98, 97, 96, 95, 94, 93, 92, 91, 90,
            89, 88, 87, 86, 85, 84, 83, 82, 81, 80,
            79, 78, 77, 76, 75, 74, 73, 72};

    @BeforeAll
    static void setup() {
        initCrypto();
    }

    // ---- Mode A: Direct Java tests ----

    @Nested
    class DirectJavaTests {

        private ScriptContext buildCtx(byte[] signer, TxOut... outputs) {
            var txInfo = new TxInfo(
                    JulcList.of(), JulcList.of(),
                    JulcList.of(outputs),
                    BigInteger.valueOf(200_000),
                    Value.zero(),
                    JulcList.of(), JulcMap.empty(),
                    Interval.always(),
                    JulcList.of(new PubKeyHash(signer)),
                    JulcMap.empty(), JulcMap.empty(),
                    new TxId(new byte[32]),
                    JulcMap.empty(), JulcList.of(),
                    Optional.empty(), Optional.empty());
            return new ScriptContext(txInfo, PlutusData.UNIT,
                    new ScriptInfo.SpendingScript(new TxOutRef(new TxId(new byte[32]), BigInteger.ZERO), Optional.empty()));
        }

        private TxOut makeOutput(BigInteger lovelace) {
            return new TxOut(
                    new Address(new Credential.PubKeyCredential(new PubKeyHash(new byte[28])), Optional.empty()),
                    Value.lovelace(lovelace),
                    new OutputDatum.NoOutputDatum(),
                    Optional.empty());
        }

        @Test
        void beneficiaryCanUnlock() {
            // Set @Param fields
            VestingValidator.no = BigInteger.valueOf(42);
            VestingValidator.msg = "hello";

            var datum = new VestingValidator.VestingDatum(BENEFICIARY);
            var nested = new VestingValidator.NestedRedeemer(BigInteger.valueOf(42), "hello");
            var redeemer = new VestingValidator.VestingRedeemer(nested);

            // 2 outputs, one with exactly 5M lovelace
            var output1 = makeOutput(BigInteger.valueOf(5_000_000));
            var output2 = makeOutput(BigInteger.valueOf(2_000_000));
            var ctx = buildCtx(BENEFICIARY, output1, output2);

            boolean result = VestingValidator.validate(datum, redeemer, ctx);
            assertTrue(result, "Beneficiary with correct params should pass");
        }

        @Test
        void wrongSigner_fails() {
            VestingValidator.no = BigInteger.valueOf(42);
            VestingValidator.msg = "hello";

            var datum = new VestingValidator.VestingDatum(BENEFICIARY);
            var nested = new VestingValidator.NestedRedeemer(BigInteger.valueOf(42), "hello");
            var redeemer = new VestingValidator.VestingRedeemer(nested);

            var output1 = makeOutput(BigInteger.valueOf(5_000_000));
            var output2 = makeOutput(BigInteger.valueOf(2_000_000));
            var ctx = buildCtx(OTHER_PKH, output1, output2); // wrong signer

            boolean result = VestingValidator.validate(datum, redeemer, ctx);
            assertFalse(result, "Wrong signer should fail");
        }

        @Test
        void wrongParamNo_fails() {
            VestingValidator.no = BigInteger.valueOf(42);
            VestingValidator.msg = "hello";

            var datum = new VestingValidator.VestingDatum(BENEFICIARY);
            var nested = new VestingValidator.NestedRedeemer(BigInteger.valueOf(99), "hello"); // wrong no
            var redeemer = new VestingValidator.VestingRedeemer(nested);

            var output1 = makeOutput(BigInteger.valueOf(5_000_000));
            var output2 = makeOutput(BigInteger.valueOf(2_000_000));
            var ctx = buildCtx(BENEFICIARY, output1, output2);

            boolean result = VestingValidator.validate(datum, redeemer, ctx);
            assertFalse(result, "Wrong param no should fail");
        }

        @Test
        void notEnoughOutputs_fails() {
            VestingValidator.no = BigInteger.valueOf(42);
            VestingValidator.msg = "hello";

            var datum = new VestingValidator.VestingDatum(BENEFICIARY);
            var nested = new VestingValidator.NestedRedeemer(BigInteger.valueOf(42), "hello");
            var redeemer = new VestingValidator.VestingRedeemer(nested);

            // Only 1 output (validator requires size == 2)
            var output1 = makeOutput(BigInteger.valueOf(5_000_000));
            var ctx = buildCtx(BENEFICIARY, output1);

            boolean result = VestingValidator.validate(datum, redeemer, ctx);
            assertFalse(result, "Only 1 output should fail (need 2)");
        }

        @Test
        void noOutputWithRequiredLovelace_fails() {
            VestingValidator.no = BigInteger.valueOf(42);
            VestingValidator.msg = "hello";

            var datum = new VestingValidator.VestingDatum(BENEFICIARY);
            var nested = new VestingValidator.NestedRedeemer(BigInteger.valueOf(42), "hello");
            var redeemer = new VestingValidator.VestingRedeemer(nested);

            // 2 outputs but none with 5M lovelace
            var output1 = makeOutput(BigInteger.valueOf(1_000_000));
            var output2 = makeOutput(BigInteger.valueOf(2_000_000));
            var ctx = buildCtx(BENEFICIARY, output1, output2);

            boolean result = VestingValidator.validate(datum, redeemer, ctx);
            assertFalse(result, "No output with 5M lovelace should fail");
        }
    }

    // ---- Mode B: UPLC compilation tests ----

    @Nested
    class UplcTests {

        // VestingDatum: Constr(0, [BData(beneficiary)])
        private PlutusData buildDatum(byte[] beneficiary) {
            return PlutusData.constr(0, PlutusData.bytes(beneficiary));
        }

        // NestedRedeemer: Constr(0, [IData(no), BData(EncodeUtf8(msg))])
        // VestingRedeemer: Constr(0, [nestedRedeemer])
        private PlutusData buildRedeemer(long no, String msg) {
            var nested = PlutusData.constr(0,
                    PlutusData.integer(no),
                    PlutusData.bytes(msg.getBytes(StandardCharsets.UTF_8)));
            return PlutusData.constr(0, nested);
        }

        // Build ScriptContext with datum in scriptInfo, redeemer in ctx
        private PlutusData buildCtxData(PlutusData datum, PlutusData redeemer,
                                        byte[] signer, BigInteger... outputLovelaces) {
            var ref = TestDataBuilder.randomTxOutRef_typed();
            var builder = spendingContext(ref, datum).redeemer(redeemer).signer(signer);
            for (BigInteger lovelace : outputLovelaces) {
                var address = TestDataBuilder.pubKeyAddress(new PubKeyHash(signer));
                var txOut = TestDataBuilder.txOut(address,
                        com.bloxbean.cardano.julc.ledger.Value.lovelace(lovelace));
                builder.output(txOut);
            }
            return builder.buildPlutusData();
        }

        @Test
        void compilesWithParams_andEvaluates() throws Exception {
            var program = compileValidator(VestingValidator.class).program();

            // Apply @Param: no=42, msg="hello" (String encoded as BData(UTF-8 bytes))
            var concrete = program.applyParams(
                    PlutusData.integer(42),
                    PlutusData.bytes("hello".getBytes(StandardCharsets.UTF_8)));

            var datum = buildDatum(BENEFICIARY);
            var redeemer = buildRedeemer(42, "hello");
            var ctx = buildCtxData(datum, redeemer, BENEFICIARY,
                    BigInteger.valueOf(5_000_000),
                    BigInteger.valueOf(2_000_000));

            var result = evaluate(concrete, ctx);
            assertSuccess(result);
            logBudget("compilesWithParams_andEvaluates", result);
        }

        @Test
        void rejectsWrongParam() throws Exception {
            var program = compileValidator(VestingValidator.class).program();

            var concrete = program.applyParams(
                    PlutusData.integer(42),
                    PlutusData.bytes("hello".getBytes(StandardCharsets.UTF_8)));

            var datum = buildDatum(BENEFICIARY);
            var redeemer = buildRedeemer(99, "hello"); // wrong no
            var ctx = buildCtxData(datum, redeemer, BENEFICIARY,
                    BigInteger.valueOf(5_000_000),
                    BigInteger.valueOf(2_000_000));

            var result = evaluate(concrete, ctx);
            assertFailure(result);
            logBudget("rejectsWrongParam", result);
        }

        @Test
        void tracesAreEmitted() throws Exception {
            var program = compileValidator(VestingValidator.class).program();

            var concrete = program.applyParams(
                    PlutusData.integer(42),
                    PlutusData.bytes("hello".getBytes(StandardCharsets.UTF_8)));

            var datum = buildDatum(BENEFICIARY);
            var redeemer = buildRedeemer(42, "hello");
            var ctx = buildCtxData(datum, redeemer, BENEFICIARY,
                    BigInteger.valueOf(5_000_000),
                    BigInteger.valueOf(2_000_000));

            var result = evaluate(concrete, ctx);
            assertSuccess(result);
            BudgetAssertions.assertTrace(result, "Checking outputs", "Checking beneficiary");
        }
    }

    private static void logBudget(String testName, com.bloxbean.cardano.julc.vm.EvalResult result) {
        var budget = result.budgetConsumed();
        System.out.println("[" + testName + "] CPU: " + budget.cpuSteps() + ", Mem: " + budget.memoryUnits());
        System.out.println("[" + testName + "] Traces: " + result.traces());
    }
}
