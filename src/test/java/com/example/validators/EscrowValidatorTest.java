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
 * Tests for EscrowValidator — two code paths (complete/refund), uses ValidationUtils library.
 */
class EscrowValidatorTest extends ContractTest {

    static final byte[] SELLER = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
            11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
            21, 22, 23, 24, 25, 26, 27, 28};
    static final byte[] BUYER = new byte[]{31, 32, 33, 34, 35, 36, 37, 38, 39, 40,
            41, 42, 43, 44, 45, 46, 47, 48, 49, 50,
            51, 52, 53, 54, 55, 56, 57, 58};
    static final BigInteger DEADLINE = BigInteger.valueOf(1000);
    static final BigInteger PRICE = BigInteger.valueOf(10_000_000);

    @BeforeAll
    static void setup() {
        initCrypto();
    }

    // ---- Mode A: Direct Java tests ----

    @Nested
    class DirectJavaTests {

        private ScriptContext buildCtx(byte[][] signers, Interval validRange, TxOut... outputs) {
            var sigList = JulcList.<PubKeyHash>empty();
            for (byte[] sig : signers) {
                sigList = sigList.prepend(new PubKeyHash(sig));
            }
            var txInfo = new TxInfo(
                    JulcList.of(), JulcList.of(),
                    JulcList.of(outputs),
                    BigInteger.valueOf(200_000),
                    Value.zero(),
                    JulcList.of(), JulcMap.empty(),
                    validRange,
                    sigList,
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
        void completeAction_bothSignAndPaid_passes() {
            var datum = new EscrowValidator.EscrowDatum(SELLER, BUYER, DEADLINE, PRICE);
            var redeemer = new EscrowValidator.EscrowRedeemer(BigInteger.ZERO); // complete
            var output = makeOutput(PRICE);
            var ctx = buildCtx(new byte[][]{SELLER, BUYER}, Interval.always(), output);

            boolean result = EscrowValidator.validate(datum, redeemer, ctx);
            assertTrue(result, "Both sign + seller paid should pass");
        }

        @Test
        void completeAction_sellerNotPaid_fails() {
            var datum = new EscrowValidator.EscrowDatum(SELLER, BUYER, DEADLINE, PRICE);
            var redeemer = new EscrowValidator.EscrowRedeemer(BigInteger.ZERO);
            var output = makeOutput(BigInteger.valueOf(1_000_000)); // too low
            var ctx = buildCtx(new byte[][]{SELLER, BUYER}, Interval.always(), output);

            boolean result = EscrowValidator.validate(datum, redeemer, ctx);
            assertFalse(result, "Seller not paid enough should fail");
        }

        @Test
        void refundAction_sellerSignedPastDeadline_passes() {
            var datum = new EscrowValidator.EscrowDatum(SELLER, BUYER, DEADLINE, PRICE);
            var redeemer = new EscrowValidator.EscrowRedeemer(BigInteger.ONE); // refund
            // Valid range that contains the deadline (past it)
            var validRange = Interval.after(BigInteger.valueOf(500));
            var ctx = buildCtx(new byte[][]{SELLER}, validRange);

            boolean result = EscrowValidator.validate(datum, redeemer, ctx);
            assertTrue(result, "Seller signed + past deadline should pass for refund");
        }

        @Test
        void refundAction_beforeDeadline_fails() {
            var datum = new EscrowValidator.EscrowDatum(SELLER, BUYER, DEADLINE, PRICE);
            var redeemer = new EscrowValidator.EscrowRedeemer(BigInteger.ONE);
            // Valid range that does NOT contain the deadline
            var validRange = Interval.before(BigInteger.valueOf(500));
            var ctx = buildCtx(new byte[][]{SELLER}, validRange);

            boolean result = EscrowValidator.validate(datum, redeemer, ctx);
            assertFalse(result, "Before deadline should fail for refund");
        }
    }

    // ---- Mode B: UPLC compilation tests ----

    @Nested
    class UplcTests {

        // EscrowDatum: Constr(0, [BData(seller), BData(buyer), IData(deadline), IData(price)])
        private PlutusData buildDatum() {
            return PlutusData.constr(0,
                    PlutusData.bytes(SELLER), PlutusData.bytes(BUYER),
                    PlutusData.integer(DEADLINE), PlutusData.integer(PRICE));
        }

        // EscrowRedeemer: Constr(0, [IData(action)])
        private PlutusData buildRedeemer(int action) {
            return PlutusData.constr(0, PlutusData.integer(action));
        }

        @Test
        void complete_evaluatesSuccess() throws Exception {
            var program = compileValidator(EscrowValidator.class).program();

            var datum = buildDatum();
            var redeemer = buildRedeemer(0); // complete

            var ref = TestDataBuilder.randomTxOutRef_typed();
            var address = TestDataBuilder.pubKeyAddress(new PubKeyHash(SELLER));
            var txOut = TestDataBuilder.txOut(address,
                    com.bloxbean.cardano.julc.ledger.Value.lovelace(PRICE));
            var ctx = spendingContext(ref, datum)
                    .redeemer(redeemer)
                    .signer(SELLER)
                    .signer(BUYER)
                    .output(txOut)
                    .validRange(com.bloxbean.cardano.julc.ledger.Interval.always())
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("complete_evaluatesSuccess", result);
        }

        @Test
        void refund_evaluatesSuccess() throws Exception {
            var program = compileValidator(EscrowValidator.class).program();

            var datum = buildDatum();
            var redeemer = buildRedeemer(1); // refund

            var ref = TestDataBuilder.randomTxOutRef_typed();
            // validRange [500, +inf) contains deadline 1000
            var ctx = spendingContext(ref, datum)
                    .redeemer(redeemer)
                    .signer(SELLER)
                    .validRange(com.bloxbean.cardano.julc.ledger.Interval.after(BigInteger.valueOf(500)))
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("refund_evaluatesSuccess", result);
        }

        @Test
        void tracesAppear() throws Exception {
            var program = compileValidator(EscrowValidator.class).program();

            var datum = buildDatum();
            var redeemer = buildRedeemer(0);

            var ref = TestDataBuilder.randomTxOutRef_typed();
            var address = TestDataBuilder.pubKeyAddress(new PubKeyHash(SELLER));
            var txOut = TestDataBuilder.txOut(address,
                    com.bloxbean.cardano.julc.ledger.Value.lovelace(PRICE));
            var ctx = spendingContext(ref, datum)
                    .redeemer(redeemer)
                    .signer(SELLER)
                    .signer(BUYER)
                    .output(txOut)
                    .validRange(com.bloxbean.cardano.julc.ledger.Interval.always())
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            BudgetAssertions.assertTrace(result, "Escrow validate", "Complete path");
        }
    }

    private static void logBudget(String testName, com.bloxbean.cardano.julc.vm.EvalResult result) {
        var budget = result.budgetConsumed();
        System.out.println("[" + testName + "] CPU: " + budget.cpuSteps() + ", Mem: " + budget.memoryUnits());
        System.out.println("[" + testName + "] Traces: " + result.traces());
    }
}
