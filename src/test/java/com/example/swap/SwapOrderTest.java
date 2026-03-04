package com.example.swap;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.core.types.JulcMap;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.testkit.*;
import com.bloxbean.cardano.julc.testkit.JulcEval;
import com.example.swap.onchain.SwapOrder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for SwapOrder — sealed interface redeemer with FillOrder + CancelOrder.
 * <p>
 * DirectJavaTests: call validator logic directly in JVM.
 * UplcTests: compile to UPLC and evaluate on VM.
 */
class SwapOrderTest extends ContractTest {

    static final byte[] MAKER = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
            11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
            21, 22, 23, 24, 25, 26, 27, 28};
    static final byte[] TAKER = new byte[]{31, 32, 33, 34, 35, 36, 37, 38, 39, 40,
            41, 42, 43, 44, 45, 46, 47, 48, 49, 50,
            51, 52, 53, 54, 55, 56, 57, 58};
    static final byte[] OTHER_PKH = new byte[]{99, 98, 97, 96, 95, 94, 93, 92, 91, 90,
            89, 88, 87, 86, 85, 84, 83, 82, 81, 80,
            79, 78, 77, 76, 75, 74, 73, 72};

    // ADA token identifiers (empty bytes)
    static final byte[] ADA_POLICY = new byte[0];
    static final byte[] ADA_TOKEN = new byte[0];

    // Test native token
    static final byte[] TOKEN_POLICY = new byte[]{10, 20, 30, 40, 50, 60, 70, 80, 90, 100,
            110, 120, 11, 21, 31, 41, 51, 61, 71, 81,
            91, 101, 111, 121, 12, 22, 32, 42};
    static final byte[] TOKEN_NAME = new byte[]{1, 2, 3, 4, 5};

    static final BigInteger OFFERED_AMOUNT = BigInteger.valueOf(50_000_000);
    static final BigInteger REQUESTED_AMOUNT = BigInteger.valueOf(45_000_000);

    @BeforeAll
    static void setup() {
        initCrypto();
    }

    // ---- Mode A: Direct Java tests ----

    @Nested
    class DirectJavaTests {

        private Address makerAddr() {
            return new Address(
                    new Credential.PubKeyCredential(new PubKeyHash(MAKER)),
                    Optional.empty());
        }

        private TxOut adaOutput(Address addr, BigInteger lovelace) {
            return new TxOut(addr, Value.lovelace(lovelace),
                    new OutputDatum.NoOutputDatum(), Optional.empty());
        }

        private TxOut tokenOutput(Address addr, BigInteger lovelace, byte[] policy, byte[] name, BigInteger qty) {
            var value = Value.lovelace(lovelace)
                    .merge(Value.singleton(new PolicyId(policy), new TokenName(name), qty));
            return new TxOut(addr, value, new OutputDatum.NoOutputDatum(), Optional.empty());
        }

        private ScriptContext buildCtx(byte[][] signers, TxOut... outputs) {
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
                    Interval.always(),
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

        private SwapOrder.OrderDatum adaSwapDatum() {
            return new SwapOrder.OrderDatum(MAKER,
                    ADA_POLICY, ADA_TOKEN, OFFERED_AMOUNT,
                    ADA_POLICY, ADA_TOKEN, REQUESTED_AMOUNT);
        }

        private SwapOrder.OrderDatum tokenSwapDatum() {
            return new SwapOrder.OrderDatum(MAKER,
                    ADA_POLICY, ADA_TOKEN, OFFERED_AMOUNT,
                    TOKEN_POLICY, TOKEN_NAME, BigInteger.valueOf(100));
        }

        @Test
        void fillOrder_makerReceivesEnoughAda_passes() {
            var datum = adaSwapDatum();
            var redeemer = new SwapOrder.FillOrder();
            var output = adaOutput(makerAddr(), REQUESTED_AMOUNT);
            var ctx = buildCtx(new byte[0][], output);

            boolean result = SwapOrder.validate(datum, redeemer, ctx);
            assertTrue(result, "Maker receives >= requested ADA should pass");
        }

        @Test
        void fillOrder_makerReceivesInsufficientAmount_fails() {
            var datum = adaSwapDatum();
            var redeemer = new SwapOrder.FillOrder();
            var output = adaOutput(makerAddr(), BigInteger.valueOf(10_000_000));
            var ctx = buildCtx(new byte[0][], output);

            boolean result = SwapOrder.validate(datum, redeemer, ctx);
            assertFalse(result, "Maker receives < requested ADA should fail");
        }

        @Test
        void fillOrder_paymentToWrongAddress_fails() {
            var datum = adaSwapDatum();
            var redeemer = new SwapOrder.FillOrder();
            // Pay to OTHER, not to maker
            var otherAddr = new Address(
                    new Credential.PubKeyCredential(new PubKeyHash(OTHER_PKH)),
                    Optional.empty());
            var output = adaOutput(otherAddr, REQUESTED_AMOUNT);
            var ctx = buildCtx(new byte[0][], output);

            boolean result = SwapOrder.validate(datum, redeemer, ctx);
            assertFalse(result, "Payment to wrong address should fail");
        }

        @Test
        void fillOrder_exactAmount_passes() {
            var datum = adaSwapDatum();
            var redeemer = new SwapOrder.FillOrder();
            var output = adaOutput(makerAddr(), REQUESTED_AMOUNT);
            var ctx = buildCtx(new byte[0][], output);

            boolean result = SwapOrder.validate(datum, redeemer, ctx);
            assertTrue(result, "Exact requested amount should pass");
        }

        @Test
        void fillOrder_nativeTokenSwap_passes() {
            var datum = tokenSwapDatum();
            var redeemer = new SwapOrder.FillOrder();
            var output = tokenOutput(makerAddr(), BigInteger.valueOf(2_000_000),
                    TOKEN_POLICY, TOKEN_NAME, BigInteger.valueOf(100));
            var ctx = buildCtx(new byte[0][], output);

            boolean result = SwapOrder.validate(datum, redeemer, ctx);
            assertTrue(result, "Correct native token payment should pass");
        }

        @Test
        void fillOrder_nativeTokenInsufficientAmount_fails() {
            var datum = tokenSwapDatum();
            var redeemer = new SwapOrder.FillOrder();
            var output = tokenOutput(makerAddr(), BigInteger.valueOf(2_000_000),
                    TOKEN_POLICY, TOKEN_NAME, BigInteger.valueOf(50));
            var ctx = buildCtx(new byte[0][], output);

            boolean result = SwapOrder.validate(datum, redeemer, ctx);
            assertFalse(result, "Insufficient native token payment should fail");
        }

        @Test
        void cancelOrder_makerSigned_passes() {
            var datum = adaSwapDatum();
            var redeemer = new SwapOrder.CancelOrder();
            var ctx = buildCtx(new byte[][]{MAKER});

            boolean result = SwapOrder.validate(datum, redeemer, ctx);
            assertTrue(result, "Maker signed cancel should pass");
        }

        @Test
        void cancelOrder_nonMakerSigned_fails() {
            var datum = adaSwapDatum();
            var redeemer = new SwapOrder.CancelOrder();
            var ctx = buildCtx(new byte[][]{OTHER_PKH});

            boolean result = SwapOrder.validate(datum, redeemer, ctx);
            assertFalse(result, "Non-maker signed cancel should fail");
        }
    }

    // ---- Mode B: UPLC compilation tests ----

    @Nested
    class UplcTests {

        // OrderDatum = Constr(0, [BData(maker), BData(offeredPolicy), BData(offeredToken),
        //   IData(offeredAmount), BData(requestedPolicy), BData(requestedToken), IData(requestedAmount)])
        private PlutusData buildDatum(byte[] maker, byte[] offPolicy, byte[] offToken,
                                      BigInteger offAmount, byte[] reqPolicy, byte[] reqToken,
                                      BigInteger reqAmount) {
            return PlutusData.constr(0,
                    PlutusData.bytes(maker),
                    PlutusData.bytes(offPolicy),
                    PlutusData.bytes(offToken),
                    PlutusData.integer(offAmount),
                    PlutusData.bytes(reqPolicy),
                    PlutusData.bytes(reqToken),
                    PlutusData.integer(reqAmount));
        }

        private PlutusData adaDatum() {
            return buildDatum(MAKER, ADA_POLICY, ADA_TOKEN, OFFERED_AMOUNT,
                    ADA_POLICY, ADA_TOKEN, REQUESTED_AMOUNT);
        }

        // FillOrder = Constr(0, [])
        private PlutusData fillRedeemer() {
            return PlutusData.constr(0);
        }

        // CancelOrder = Constr(1, [])
        private PlutusData cancelRedeemer() {
            return PlutusData.constr(1);
        }

        @Test
        void fill_evaluatesSuccess() throws Exception {
            var program = compileValidator(SwapOrder.class).program();

            var datum = adaDatum();
            var redeemer = fillRedeemer();

            // Create output paying requested amount to maker
            var makerAddr = TestDataBuilder.pubKeyAddress(new PubKeyHash(MAKER));
            var output = TestDataBuilder.txOut(makerAddr, Value.lovelace(REQUESTED_AMOUNT));

            var ref = TestDataBuilder.randomTxOutRef_typed();
            var ctx = spendingContext(ref, datum)
                    .redeemer(redeemer)
                    .output(output)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("fill_evaluatesSuccess", result);
        }

        @Test
        void fill_rejectsInsufficientPayment() throws Exception {
            var program = compileValidator(SwapOrder.class).program();

            var datum = adaDatum();
            var redeemer = fillRedeemer();

            var makerAddr = TestDataBuilder.pubKeyAddress(new PubKeyHash(MAKER));
            var output = TestDataBuilder.txOut(makerAddr, Value.lovelace(BigInteger.valueOf(10_000_000)));

            var ref = TestDataBuilder.randomTxOutRef_typed();
            var ctx = spendingContext(ref, datum)
                    .redeemer(redeemer)
                    .output(output)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertFailure(result);
            logBudget("fill_rejectsInsufficientPayment", result);
        }

        @Test
        void cancel_evaluatesSuccess() throws Exception {
            var program = compileValidator(SwapOrder.class).program();

            var datum = adaDatum();
            var redeemer = cancelRedeemer();

            var ref = TestDataBuilder.randomTxOutRef_typed();
            var ctx = spendingContext(ref, datum)
                    .redeemer(redeemer)
                    .signer(MAKER)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("cancel_evaluatesSuccess", result);
        }

        @Test
        void cancel_rejectsNonMaker() throws Exception {
            var program = compileValidator(SwapOrder.class).program();

            var datum = adaDatum();
            var redeemer = cancelRedeemer();

            var ref = TestDataBuilder.randomTxOutRef_typed();
            var ctx = spendingContext(ref, datum)
                    .redeemer(redeemer)
                    .signer(OTHER_PKH)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertFailure(result);
            logBudget("cancel_rejectsNonMaker", result);
        }

        @Test
        void tracesAppear() throws Exception {
            var program = compileValidator(SwapOrder.class).program();

            var datum = adaDatum();
            var redeemer = fillRedeemer();

            var makerAddr = TestDataBuilder.pubKeyAddress(new PubKeyHash(MAKER));
            var output = TestDataBuilder.txOut(makerAddr, Value.lovelace(REQUESTED_AMOUNT));

            var ref = TestDataBuilder.randomTxOutRef_typed();
            var ctx = spendingContext(ref, datum)
                    .redeemer(redeemer)
                    .output(output)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            BudgetAssertions.assertTrace(result, "SwapOrder validate", "Fill order");
        }

        @Test
        void budgetCheck() throws Exception {
            var program = compileValidator(SwapOrder.class).program();

            var datum = adaDatum();
            var redeemer = fillRedeemer();

            var makerAddr = TestDataBuilder.pubKeyAddress(new PubKeyHash(MAKER));
            var output = TestDataBuilder.txOut(makerAddr, Value.lovelace(REQUESTED_AMOUNT));

            var ref = TestDataBuilder.randomTxOutRef_typed();
            var ctx = spendingContext(ref, datum)
                    .redeemer(redeemer)
                    .output(output)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            // Generous budget limit — mainly for regression detection
            assertBudgetUnder(result, 500_000_000L, 2_000_000L);
        }
    }

    // ---- Mode C: JulcEval proxy tests (test helper methods in isolation on UPLC VM) ----

    @Nested
    class JulcEvalProxyTests {

        // Proxy interface for testing individual SwapOrder helper methods.
        // makerAddress(byte[]) constructs an Address from a PKH — no @Param dependency.
        interface SwapOrderProxy {
            PlutusData makerAddress(byte[] makerPkh);
        }

        private final SwapOrderProxy proxy =
                JulcEval.forClass(SwapOrder.class).create(SwapOrderProxy.class);

        @Test
        void makerAddress_returnsCorrectAddress() {
            PlutusData result = proxy.makerAddress(MAKER);
            assertNotNull(result);
            // Address = Constr(0, [Credential, Optional])
            // Credential.PubKeyCredential = Constr(0, [BData(pkh)])
            // Optional.empty() = Constr(1, [])
            var expected = PlutusData.constr(0,
                    PlutusData.constr(0, PlutusData.bytes(MAKER)),
                    PlutusData.constr(1));
            assertEquals(expected, result);
        }

        @Test
        void makerAddress_differentPkh_returnsDifferentAddress() {
            PlutusData result1 = proxy.makerAddress(MAKER);
            PlutusData result2 = proxy.makerAddress(TAKER);
            assertNotEquals(result1, result2);
        }

        // Fluent call alternative — no proxy interface needed
        @Test
        void makerAddress_fluentCall() {
            var eval = JulcEval.forClass(SwapOrder.class);
            PlutusData result = eval.call("makerAddress", MAKER).asData();
            assertNotNull(result);
            var expected = PlutusData.constr(0,
                    PlutusData.constr(0, PlutusData.bytes(MAKER)),
                    PlutusData.constr(1));
            assertEquals(expected, result);
        }

        // Note: validateFillOrder and validateCancelOrder reference TxInfo (the ScriptContext),
        // which is only available at the entrypoint level. They cannot be tested via JulcEval
        // proxy because they depend on full transaction context.
    }

    private static void logBudget(String testName, com.bloxbean.cardano.julc.vm.EvalResult result) {
        var budget = result.budgetConsumed();
        System.out.println("[" + testName + "] CPU: " + budget.cpuSteps() + ", Mem: " + budget.memoryUnits());
        System.out.println("[" + testName + "] Traces: " + result.traces());
    }
}
