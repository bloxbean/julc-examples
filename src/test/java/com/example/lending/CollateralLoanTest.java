package com.example.lending;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.core.types.JulcMap;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.testkit.*;
import com.bloxbean.cardano.julc.testkit.JulcEval;
import com.example.lending.onchain.CollateralLoan;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CollateralLoan — 4-variant sealed interface, @Param, arithmetic, time checks.
 */
class CollateralLoanTest extends ContractTest {

    static final byte[] LENDER = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
            11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
            21, 22, 23, 24, 25, 26, 27, 28};
    static final byte[] BORROWER = new byte[]{31, 32, 33, 34, 35, 36, 37, 38, 39, 40,
            41, 42, 43, 44, 45, 46, 47, 48, 49, 50,
            51, 52, 53, 54, 55, 56, 57, 58};
    static final byte[] OTHER_PKH = new byte[]{99, 98, 97, 96, 95, 94, 93, 92, 91, 90,
            89, 88, 87, 86, 85, 84, 83, 82, 81, 80,
            79, 78, 77, 76, 75, 74, 73, 72};

    static final BigInteger PRINCIPAL = BigInteger.valueOf(100_000_000);
    static final BigInteger DEADLINE = BigInteger.valueOf(1_700_000_000_000L); // far future POSIX ms
    static final BigInteger COLLATERAL = BigInteger.valueOf(160_000_000);
    static final BigInteger INTEREST_BPS = BigInteger.valueOf(500);       // 5%
    static final BigInteger LIQUIDATION_BPS = BigInteger.valueOf(15000);  // 150%

    @BeforeAll
    static void setup() {
        initCrypto();
    }

    // ---- Mode A: Direct Java tests ----

    @Nested
    class DirectJavaTests {

        private CollateralLoan.LoanDatum datum() {
            return new CollateralLoan.LoanDatum(LENDER, BORROWER, PRINCIPAL, DEADLINE, COLLATERAL);
        }

        private Address lenderAddr() {
            return new Address(
                    new Credential.PubKeyCredential(new PubKeyHash(LENDER)),
                    Optional.empty());
        }

        private Address borrowerAddr() {
            return new Address(
                    new Credential.PubKeyCredential(new PubKeyHash(BORROWER)),
                    Optional.empty());
        }

        private TxOut adaOutput(Address addr, BigInteger lovelace) {
            return new TxOut(addr, Value.lovelace(lovelace),
                    new OutputDatum.NoOutputDatum(), Optional.empty());
        }

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
                    new ScriptInfo.SpendingScript(
                            new TxOutRef(new TxId(new byte[32]), BigInteger.ZERO),
                            Optional.empty()));
        }

        @Test
        void offerLoan_lenderSigned_futureDeadline_passes() {
            CollateralLoan.interestRateBps = INTEREST_BPS;
            CollateralLoan.liquidationThresholdBps = LIQUIDATION_BPS;

            var d = datum();
            var redeemer = new CollateralLoan.OfferLoan();
            // ValidRange before deadline
            var range = Interval.before(DEADLINE.subtract(BigInteger.ONE));
            var ctx = buildCtx(new byte[][]{LENDER}, range);

            assertTrue(CollateralLoan.validate(d, redeemer, ctx));
        }

        @Test
        void offerLoan_notLenderSigned_fails() {
            CollateralLoan.interestRateBps = INTEREST_BPS;
            CollateralLoan.liquidationThresholdBps = LIQUIDATION_BPS;

            var d = datum();
            var redeemer = new CollateralLoan.OfferLoan();
            var range = Interval.before(DEADLINE.subtract(BigInteger.ONE));
            var ctx = buildCtx(new byte[][]{OTHER_PKH}, range);

            assertFalse(CollateralLoan.validate(d, redeemer, ctx));
        }

        @Test
        void offerLoan_pastDeadline_fails() {
            CollateralLoan.interestRateBps = INTEREST_BPS;
            CollateralLoan.liquidationThresholdBps = LIQUIDATION_BPS;

            var d = datum();
            var redeemer = new CollateralLoan.OfferLoan();
            // ValidRange extends past deadline
            var range = Interval.after(DEADLINE.add(BigInteger.ONE));
            var ctx = buildCtx(new byte[][]{LENDER}, range);

            assertFalse(CollateralLoan.validate(d, redeemer, ctx));
        }

        @Test
        void takeLoan_borrowerSigned_enoughCollateral_principalPaid_passes() {
            CollateralLoan.interestRateBps = INTEREST_BPS;
            CollateralLoan.liquidationThresholdBps = LIQUIDATION_BPS;

            var d = datum();
            var redeemer = new CollateralLoan.TakeLoan();
            var output = adaOutput(borrowerAddr(), PRINCIPAL);
            var ctx = buildCtx(new byte[][]{BORROWER}, Interval.always(), output);

            assertTrue(CollateralLoan.validate(d, redeemer, ctx));
        }

        @Test
        void takeLoan_insufficientCollateral_fails() {
            CollateralLoan.interestRateBps = INTEREST_BPS;
            CollateralLoan.liquidationThresholdBps = LIQUIDATION_BPS;

            // Collateral below 150% threshold: 100M principal * 150% = 150M needed, only 100M
            var d = new CollateralLoan.LoanDatum(LENDER, BORROWER, PRINCIPAL, DEADLINE,
                    BigInteger.valueOf(100_000_000));
            var redeemer = new CollateralLoan.TakeLoan();
            var output = adaOutput(borrowerAddr(), PRINCIPAL);
            var ctx = buildCtx(new byte[][]{BORROWER}, Interval.always(), output);

            assertFalse(CollateralLoan.validate(d, redeemer, ctx));
        }

        @Test
        void takeLoan_principalNotPaidToBorrower_fails() {
            CollateralLoan.interestRateBps = INTEREST_BPS;
            CollateralLoan.liquidationThresholdBps = LIQUIDATION_BPS;

            var d = datum();
            var redeemer = new CollateralLoan.TakeLoan();
            // Pay to lender instead of borrower
            var output = adaOutput(lenderAddr(), PRINCIPAL);
            var ctx = buildCtx(new byte[][]{BORROWER}, Interval.always(), output);

            assertFalse(CollateralLoan.validate(d, redeemer, ctx));
        }

        @Test
        void repayLoan_borrowerSigned_beforeDeadline_fullRepayment_passes() {
            CollateralLoan.interestRateBps = INTEREST_BPS;
            CollateralLoan.liquidationThresholdBps = LIQUIDATION_BPS;

            var d = datum();
            var redeemer = new CollateralLoan.RepayLoan();
            // 100M + 5% = 105M
            BigInteger repayment = BigInteger.valueOf(105_000_000);
            var output = adaOutput(lenderAddr(), repayment);
            var range = Interval.before(DEADLINE.subtract(BigInteger.ONE));
            var ctx = buildCtx(new byte[][]{BORROWER}, range, output);

            assertTrue(CollateralLoan.validate(d, redeemer, ctx));
        }

        @Test
        void repayLoan_insufficientRepayment_fails() {
            CollateralLoan.interestRateBps = INTEREST_BPS;
            CollateralLoan.liquidationThresholdBps = LIQUIDATION_BPS;

            var d = datum();
            var redeemer = new CollateralLoan.RepayLoan();
            // Only pay principal, not interest
            var output = adaOutput(lenderAddr(), PRINCIPAL);
            var range = Interval.before(DEADLINE.subtract(BigInteger.ONE));
            var ctx = buildCtx(new byte[][]{BORROWER}, range, output);

            assertFalse(CollateralLoan.validate(d, redeemer, ctx));
        }

        @Test
        void repayLoan_afterDeadline_fails() {
            CollateralLoan.interestRateBps = INTEREST_BPS;
            CollateralLoan.liquidationThresholdBps = LIQUIDATION_BPS;

            var d = datum();
            var redeemer = new CollateralLoan.RepayLoan();
            BigInteger repayment = BigInteger.valueOf(105_000_000);
            var output = adaOutput(lenderAddr(), repayment);
            var range = Interval.after(DEADLINE.add(BigInteger.ONE));
            var ctx = buildCtx(new byte[][]{BORROWER}, range, output);

            assertFalse(CollateralLoan.validate(d, redeemer, ctx));
        }

        @Test
        void liquidate_lenderSigned_afterDeadline_passes() {
            CollateralLoan.interestRateBps = INTEREST_BPS;
            CollateralLoan.liquidationThresholdBps = LIQUIDATION_BPS;

            var d = datum();
            var redeemer = new CollateralLoan.Liquidate();
            var range = Interval.after(DEADLINE);
            var ctx = buildCtx(new byte[][]{LENDER}, range);

            assertTrue(CollateralLoan.validate(d, redeemer, ctx));
        }

        @Test
        void liquidate_beforeDeadline_fails() {
            CollateralLoan.interestRateBps = INTEREST_BPS;
            CollateralLoan.liquidationThresholdBps = LIQUIDATION_BPS;

            var d = datum();
            var redeemer = new CollateralLoan.Liquidate();
            var range = Interval.before(DEADLINE.subtract(BigInteger.ONE));
            var ctx = buildCtx(new byte[][]{LENDER}, range);

            assertFalse(CollateralLoan.validate(d, redeemer, ctx));
        }

        @Test
        void liquidate_notLenderSigned_fails() {
            CollateralLoan.interestRateBps = INTEREST_BPS;
            CollateralLoan.liquidationThresholdBps = LIQUIDATION_BPS;

            var d = datum();
            var redeemer = new CollateralLoan.Liquidate();
            var range = Interval.after(DEADLINE);
            var ctx = buildCtx(new byte[][]{OTHER_PKH}, range);

            assertFalse(CollateralLoan.validate(d, redeemer, ctx));
        }
    }

    // ---- Mode B: UPLC compilation tests ----

    @Nested
    class UplcTests {

        // LoanDatum = Constr(0, [BData(lender), BData(borrower), IData(principal),
        //                         IData(deadline), IData(collateral)])
        private PlutusData buildDatum() {
            return PlutusData.constr(0,
                    PlutusData.bytes(LENDER),
                    PlutusData.bytes(BORROWER),
                    PlutusData.integer(PRINCIPAL),
                    PlutusData.integer(DEADLINE),
                    PlutusData.integer(COLLATERAL));
        }

        // OfferLoan = Constr(0, []), TakeLoan = Constr(1, []),
        // RepayLoan = Constr(2, []), Liquidate = Constr(3, [])
        private PlutusData offerRedeemer() { return PlutusData.constr(0); }
        private PlutusData takeRedeemer() { return PlutusData.constr(1); }
        private PlutusData repayRedeemer() { return PlutusData.constr(2); }
        private PlutusData liquidateRedeemer() { return PlutusData.constr(3); }

        private com.bloxbean.cardano.julc.compiler.CompileResult compileWithParams() {
            var result = compileValidator(CollateralLoan.class);
            assertFalse(result.hasErrors(), "Should compile: " + result);
            return result;
        }

        @Test
        void offer_evaluatesSuccess() throws Exception {
            var program = compileWithParams().program().applyParams(
                    PlutusData.integer(INTEREST_BPS),
                    PlutusData.integer(LIQUIDATION_BPS));

            var datum = buildDatum();
            var redeemer = offerRedeemer();
            var range = Interval.before(DEADLINE.subtract(BigInteger.ONE));

            var ref = TestDataBuilder.randomTxOutRef_typed();
            var ctx = spendingContext(ref, datum)
                    .redeemer(redeemer)
                    .signer(LENDER)
                    .validRange(range)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("offer_evaluatesSuccess", result);
        }

        @Test
        void take_evaluatesSuccess() throws Exception {
            var program = compileWithParams().program().applyParams(
                    PlutusData.integer(INTEREST_BPS),
                    PlutusData.integer(LIQUIDATION_BPS));

            var datum = buildDatum();
            var redeemer = takeRedeemer();

            var borrowerAddr = TestDataBuilder.pubKeyAddress(new PubKeyHash(BORROWER));
            var output = TestDataBuilder.txOut(borrowerAddr, Value.lovelace(PRINCIPAL));

            var ref = TestDataBuilder.randomTxOutRef_typed();
            var ctx = spendingContext(ref, datum)
                    .redeemer(redeemer)
                    .signer(BORROWER)
                    .output(output)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("take_evaluatesSuccess", result);
        }

        @Test
        void repay_evaluatesSuccess() throws Exception {
            var program = compileWithParams().program().applyParams(
                    PlutusData.integer(INTEREST_BPS),
                    PlutusData.integer(LIQUIDATION_BPS));

            var datum = buildDatum();
            var redeemer = repayRedeemer();

            // 100M + 5% = 105M
            var lenderAddr = TestDataBuilder.pubKeyAddress(new PubKeyHash(LENDER));
            var output = TestDataBuilder.txOut(lenderAddr,
                    Value.lovelace(BigInteger.valueOf(105_000_000)));
            var range = Interval.before(DEADLINE.subtract(BigInteger.ONE));

            var ref = TestDataBuilder.randomTxOutRef_typed();
            var ctx = spendingContext(ref, datum)
                    .redeemer(redeemer)
                    .signer(BORROWER)
                    .output(output)
                    .validRange(range)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("repay_evaluatesSuccess", result);
        }

        @Test
        void repay_rejectsInsufficientPayment() throws Exception {
            var program = compileWithParams().program().applyParams(
                    PlutusData.integer(INTEREST_BPS),
                    PlutusData.integer(LIQUIDATION_BPS));

            var datum = buildDatum();
            var redeemer = repayRedeemer();

            var lenderAddr = TestDataBuilder.pubKeyAddress(new PubKeyHash(LENDER));
            var output = TestDataBuilder.txOut(lenderAddr, Value.lovelace(PRINCIPAL)); // no interest
            var range = Interval.before(DEADLINE.subtract(BigInteger.ONE));

            var ref = TestDataBuilder.randomTxOutRef_typed();
            var ctx = spendingContext(ref, datum)
                    .redeemer(redeemer)
                    .signer(BORROWER)
                    .output(output)
                    .validRange(range)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertFailure(result);
            logBudget("repay_rejectsInsufficientPayment", result);
        }

        @Test
        void liquidate_evaluatesSuccess() throws Exception {
            var program = compileWithParams().program().applyParams(
                    PlutusData.integer(INTEREST_BPS),
                    PlutusData.integer(LIQUIDATION_BPS));

            var datum = buildDatum();
            var redeemer = liquidateRedeemer();
            var range = Interval.after(DEADLINE);

            var ref = TestDataBuilder.randomTxOutRef_typed();
            var ctx = spendingContext(ref, datum)
                    .redeemer(redeemer)
                    .signer(LENDER)
                    .validRange(range)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("liquidate_evaluatesSuccess", result);
        }

        @Test
        void liquidate_rejectsBeforeDeadline() throws Exception {
            var program = compileWithParams().program().applyParams(
                    PlutusData.integer(INTEREST_BPS),
                    PlutusData.integer(LIQUIDATION_BPS));

            var datum = buildDatum();
            var redeemer = liquidateRedeemer();
            var range = Interval.before(DEADLINE.subtract(BigInteger.ONE));

            var ref = TestDataBuilder.randomTxOutRef_typed();
            var ctx = spendingContext(ref, datum)
                    .redeemer(redeemer)
                    .signer(LENDER)
                    .validRange(range)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertFailure(result);
            logBudget("liquidate_rejectsBeforeDeadline", result);
        }

        @Test
        void tracesAppear() throws Exception {
            var program = compileWithParams().program().applyParams(
                    PlutusData.integer(INTEREST_BPS),
                    PlutusData.integer(LIQUIDATION_BPS));

            var datum = buildDatum();
            var redeemer = offerRedeemer();
            var range = Interval.before(DEADLINE.subtract(BigInteger.ONE));

            var ref = TestDataBuilder.randomTxOutRef_typed();
            var ctx = spendingContext(ref, datum)
                    .redeemer(redeemer)
                    .signer(LENDER)
                    .validRange(range)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            BudgetAssertions.assertTrace(result, "CollateralLoan validate", "Offer loan");
        }

        @Test
        void budgetCheck() throws Exception {
            var program = compileWithParams().program().applyParams(
                    PlutusData.integer(INTEREST_BPS),
                    PlutusData.integer(LIQUIDATION_BPS));

            var datum = buildDatum();
            var redeemer = repayRedeemer();

            var lenderAddr = TestDataBuilder.pubKeyAddress(new PubKeyHash(LENDER));
            var output = TestDataBuilder.txOut(lenderAddr,
                    Value.lovelace(BigInteger.valueOf(105_000_000)));
            var range = Interval.before(DEADLINE.subtract(BigInteger.ONE));

            var ref = TestDataBuilder.randomTxOutRef_typed();
            var ctx = spendingContext(ref, datum)
                    .redeemer(redeemer)
                    .signer(BORROWER)
                    .output(output)
                    .validRange(range)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            assertBudgetUnder(result, 500_000_000L, 2_000_000L);
        }
    }

    // ---- Mode C: JulcEval proxy tests (test helper methods in isolation on UPLC VM) ----
    //
    // Limitation: JulcEval compiles a method within the full class context. When a class
    // has @Param fields, the compiler sees them as undefined variables even for methods
    // that don't reference them (like toAddress). This means JulcEval proxy tests cannot
    // be used with any validator that has @Param fields.
    //
    // For a working example of JulcEval proxy tests, see SwapOrderTest which has no @Param
    // fields. For validators with @Param, use UPLC compilation tests (Mode B) instead.

    @Nested
    @Disabled("JulcEval cannot compile methods from classes with @Param fields")
    class JulcEvalProxyTests {

        // Proxy interface — demonstrates the pattern even though it can't run here.
        // If CollateralLoan had no @Param fields, these tests would work.
        interface CollateralLoanProxy {
            PlutusData toAddress(byte[] pkh);
        }

        private final CollateralLoanProxy proxy =
                JulcEval.forClass(CollateralLoan.class).create(CollateralLoanProxy.class);

        @Test
        void toAddress_returnsCorrectAddress() {
            PlutusData result = proxy.toAddress(LENDER);
            assertNotNull(result);
            var expected = PlutusData.constr(0,
                    PlutusData.constr(0, PlutusData.bytes(LENDER)),
                    PlutusData.constr(1));
            assertEquals(expected, result);
        }

        @Test
        void toAddress_differentPkh_returnsDifferentAddress() {
            PlutusData result1 = proxy.toAddress(LENDER);
            PlutusData result2 = proxy.toAddress(BORROWER);
            assertNotEquals(result1, result2);
        }

        @Test
        void toAddress_fluentCall() {
            var eval = JulcEval.forClass(CollateralLoan.class);
            PlutusData result = eval.call("toAddress", LENDER).asData();
            assertNotNull(result);
            var expected = PlutusData.constr(0,
                    PlutusData.constr(0, PlutusData.bytes(LENDER)),
                    PlutusData.constr(1));
            assertEquals(expected, result);
        }
    }

    private static void logBudget(String testName, com.bloxbean.cardano.julc.vm.EvalResult result) {
        var budget = result.budgetConsumed();
        System.out.println("[" + testName + "] CPU: " + budget.cpuSteps() + ", Mem: " + budget.memoryUnits());
        System.out.println("[" + testName + "] Traces: " + result.traces());
    }
}
