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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MultiSigTreasury validator.
 * <p>
 * Mode A: Direct Java calls with onchain-api types.
 * Mode B: UPLC compilation + evaluation.
 */
class MultiSigTreasuryTest extends ContractTest {

    // Use the SAME byte[] references for signatories and datum fields (Java reference equality)
    static final byte[] PKH1 = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
            11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
            21, 22, 23, 24, 25, 26, 27, 28};
    static final byte[] PKH2 = new byte[]{31, 32, 33, 34, 35, 36, 37, 38, 39, 40,
            41, 42, 43, 44, 45, 46, 47, 48, 49, 50,
            51, 52, 53, 54, 55, 56, 57, 58};
    static final byte[] PKH_OTHER = new byte[]{99, 98, 97, 96, 95, 94, 93, 92, 91, 90,
            89, 88, 87, 86, 85, 84, 83, 82, 81, 80,
            79, 78, 77, 76, 75, 74, 73, 72};

    @BeforeAll
    static void setup() {
        initCrypto();
    }

    private ScriptContext buildCtx(byte[]... signatories) {
        var sigList = JulcList.<PubKeyHash>empty();
        for (byte[] sig : signatories) {
            sigList = sigList.prepend(new PubKeyHash(sig));
        }
        var txInfo = new TxInfo(
                JulcList.of(), JulcList.of(), JulcList.of(),
                BigInteger.valueOf(200_000),
                Value.zero(),
                JulcList.of(), JulcMap.empty(),
                Interval.always(),
                sigList,
                JulcMap.empty(), JulcMap.empty(),
                new TxId(new byte[32]),
                JulcMap.empty(), JulcList.of(),
                Optional.empty(), Optional.empty());
        return new ScriptContext(txInfo, PlutusData.UNIT,
                new ScriptInfo.SpendingScript(new TxOutRef(new TxId(new byte[32]), BigInteger.ZERO), Optional.empty()));
    }

    // ---- Mode A: Direct Java tests ----

    @Nested
    class DirectJavaTests {

        @Test
        void bothSignersPresent_passes() {
            var datum = new MultiSigTreasury.TreasuryDatum(PKH1, PKH2);
            var ctx = buildCtx(PKH1, PKH2);
            boolean result = MultiSigTreasury.validate(datum, BigInteger.ZERO, ctx);
            assertTrue(result, "Both signers present should pass");
        }

        @Test
        void onlyOneSigner_fails() {
            var datum = new MultiSigTreasury.TreasuryDatum(PKH1, PKH2);
            var ctx = buildCtx(PKH1);
            boolean result = MultiSigTreasury.validate(datum, BigInteger.ZERO, ctx);
            assertFalse(result, "Only one signer should fail");
        }

        @Test
        void noSigners_fails() {
            var datum = new MultiSigTreasury.TreasuryDatum(PKH1, PKH2);
            var ctx = buildCtx();
            boolean result = MultiSigTreasury.validate(datum, BigInteger.ZERO, ctx);
            assertFalse(result, "No signers should fail");
        }
    }

    // ---- Mode B: UPLC compilation tests ----

    @Nested
    class UplcTests {

        // TreasuryDatum: Constr(0, [BData(signer1), BData(signer2)])
        private PlutusData buildDatum(byte[] s1, byte[] s2) {
            return PlutusData.constr(0, PlutusData.bytes(s1), PlutusData.bytes(s2));
        }

        // Build ScriptContext with datum in scriptInfo and redeemer in ctx
        private PlutusData buildCtxData(PlutusData datum, PlutusData redeemer, byte[]... signers) {
            var ref = TestDataBuilder.randomTxOutRef_typed();
            var builder = spendingContext(ref, datum).redeemer(redeemer);
            for (byte[] s : signers) {
                builder.signer(s);
            }
            return builder.buildPlutusData();
        }

        @Test
        void compilesAndEvaluates() throws Exception {
            var program = compileValidator(MultiSigTreasury.class).program();
            var datum = buildDatum(PKH1, PKH2);
            var redeemer = PlutusData.integer(0);
            var ctx = buildCtxData(datum, redeemer, PKH1, PKH2);

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("compilesAndEvaluates", result);
        }

        @Test
        void rejectsWhenSignerMissing() throws Exception {
            var program = compileValidator(MultiSigTreasury.class).program();
            var datum = buildDatum(PKH1, PKH2);
            var redeemer = PlutusData.integer(0);
            var ctx = buildCtxData(datum, redeemer, PKH1); // missing PKH2

            var result = evaluate(program, ctx);
            assertFailure(result);
            logBudget("rejectsWhenSignerMissing", result);
        }

        @Test
        void tracesAppearInOutput() throws Exception {
            var program = compileValidator(MultiSigTreasury.class).program();
            var datum = buildDatum(PKH1, PKH2);
            var redeemer = PlutusData.integer(0);
            var ctx = buildCtxData(datum, redeemer, PKH1, PKH2);

            var result = evaluate(program, ctx);
            assertSuccess(result);
            BudgetAssertions.assertTrace(result, "Checking signers");
        }
    }

    private static void logBudget(String testName, com.bloxbean.cardano.julc.vm.EvalResult result) {
        var budget = result.budgetConsumed();
        System.out.println("[" + testName + "] CPU: " + budget.cpuSteps() + ", Mem: " + budget.memoryUnits());
        System.out.println("[" + testName + "] Traces: " + result.traces());
    }
}
