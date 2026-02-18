package com.example.validators;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.PubKeyHash;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.stdlib.lib.OutputLib;
import com.bloxbean.cardano.julc.testkit.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for OutputCheckValidator — verifies minimum lovelace payment to a recipient.
 * <p>
 * Mode A: Direct Java tests exercise OutputLib methods (lovelacePaidTo, countOutputsAt, etc.)
 * against onchain-api types.
 * <p>
 * Mode B: UPLC tests compile the full OutputCheckValidator and evaluate it against PlutusData.
 */
class OutputCheckValidatorTest extends ContractTest {

    static final byte[] RECIPIENT_PKH = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
            11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
            21, 22, 23, 24, 25, 26, 27, 28};
    static final byte[] OTHER_PKH = new byte[]{31, 32, 33, 34, 35, 36, 37, 38, 39, 40,
            41, 42, 43, 44, 45, 46, 47, 48, 49, 50,
            51, 52, 53, 54, 55, 56, 57, 58};
    static final BigInteger MIN_AMOUNT = BigInteger.valueOf(8_000_000);

    @BeforeAll
    static void setup() {
        initCrypto();
    }

    // ---- Helpers for onchain-api types ----

    private static Address makeOnchainAddress(byte[] pkh) {
        return new Address(new Credential.PubKeyCredential(new PubKeyHash(pkh)), Optional.empty());
    }

    private static TxOut makeOnchainOutput(byte[] pkh, BigInteger lovelace) {
        return new TxOut(
                makeOnchainAddress(pkh),
                Value.lovelace(lovelace),
                new OutputDatum.NoOutputDatum(),
                Optional.empty());
    }

    // ---- Mode A: Direct Java tests (test OutputLib methods directly) ----

    @Nested
    class DirectJavaTests {

        @Test
        void lovelacePaidTo_sumsMatchingOutputs() {
            var recipientAddr = makeOnchainAddress(RECIPIENT_PKH);
            var outputs = JulcList.of(
                    makeOnchainOutput(RECIPIENT_PKH, BigInteger.valueOf(6_000_000)),
                    makeOnchainOutput(OTHER_PKH, BigInteger.valueOf(2_000_000)),
                    makeOnchainOutput(RECIPIENT_PKH, BigInteger.valueOf(4_000_000)));

            BigInteger paid = OutputLib.lovelacePaidTo(outputs, recipientAddr);
            assertEquals(BigInteger.valueOf(10_000_000), paid, "Should sum 6M + 4M = 10M to recipient");
        }

        @Test
        void paidAtLeast_sufficientPayment_passes() {
            var recipientAddr = makeOnchainAddress(RECIPIENT_PKH);
            var outputs = JulcList.of(
                    makeOnchainOutput(RECIPIENT_PKH, BigInteger.valueOf(6_000_000)),
                    makeOnchainOutput(OTHER_PKH, BigInteger.valueOf(2_000_000)),
                    makeOnchainOutput(RECIPIENT_PKH, BigInteger.valueOf(4_000_000)));

            assertTrue(OutputLib.paidAtLeast(outputs, recipientAddr, BigInteger.valueOf(8_000_000)),
                    "10M >= 8M should pass");
        }

        @Test
        void paidAtLeast_insufficientPayment_fails() {
            var recipientAddr = makeOnchainAddress(RECIPIENT_PKH);
            var outputs = JulcList.of(
                    makeOnchainOutput(RECIPIENT_PKH, BigInteger.valueOf(3_000_000)));

            assertFalse(OutputLib.paidAtLeast(outputs, recipientAddr, BigInteger.valueOf(8_000_000)),
                    "3M < 8M should fail");
        }

        @Test
        void countOutputsAt_countsCorrectly() {
            var recipientAddr = makeOnchainAddress(RECIPIENT_PKH);
            var outputs = JulcList.of(
                    makeOnchainOutput(RECIPIENT_PKH, BigInteger.valueOf(5_000_000)),
                    makeOnchainOutput(OTHER_PKH, BigInteger.valueOf(2_000_000)),
                    makeOnchainOutput(RECIPIENT_PKH, BigInteger.valueOf(3_000_000)));

            assertEquals(2, OutputLib.countOutputsAt(outputs, recipientAddr));
        }

        @Test
        void countOutputsAt_noMatchingOutputs_returnsZero() {
            var recipientAddr = makeOnchainAddress(RECIPIENT_PKH);
            var outputs = JulcList.of(
                    makeOnchainOutput(OTHER_PKH, BigInteger.valueOf(10_000_000)));

            assertEquals(0, OutputLib.countOutputsAt(outputs, recipientAddr));
        }

        @Test
        void outputsAt_filtersCorrectly() {
            var recipientAddr = makeOnchainAddress(RECIPIENT_PKH);
            var outputs = JulcList.of(
                    makeOnchainOutput(RECIPIENT_PKH, BigInteger.valueOf(5_000_000)),
                    makeOnchainOutput(OTHER_PKH, BigInteger.valueOf(2_000_000)),
                    makeOnchainOutput(RECIPIENT_PKH, BigInteger.valueOf(3_000_000)));

            var filtered = OutputLib.outputsAt(outputs, recipientAddr);
            assertEquals(2, filtered.size());
        }

        @Test
        void uniqueOutputAt_exactlyOneMatch_succeeds() {
            var recipientAddr = makeOnchainAddress(RECIPIENT_PKH);
            var outputs = JulcList.of(
                    makeOnchainOutput(RECIPIENT_PKH, BigInteger.valueOf(5_000_000)),
                    makeOnchainOutput(OTHER_PKH, BigInteger.valueOf(2_000_000)));

            var unique = OutputLib.uniqueOutputAt(outputs, recipientAddr);
            assertNotNull(unique);
        }

        @Test
        void uniqueOutputAt_multipleMatches_throws() {
            var recipientAddr = makeOnchainAddress(RECIPIENT_PKH);
            var outputs = JulcList.of(
                    makeOnchainOutput(RECIPIENT_PKH, BigInteger.valueOf(5_000_000)),
                    makeOnchainOutput(RECIPIENT_PKH, BigInteger.valueOf(3_000_000)));

            assertThrows(RuntimeException.class,
                    () -> OutputLib.uniqueOutputAt(outputs, recipientAddr));
        }

        @Test
        void txOutAccessors_work() {
            var out = makeOnchainOutput(RECIPIENT_PKH, BigInteger.valueOf(5_000_000));
            assertNotNull(OutputLib.txOutAddress(out));
            assertNotNull(OutputLib.txOutValue(out));
            assertNotNull(OutputLib.txOutDatum(out));
        }

        @Test
        void getInlineDatum_extractsDatum() {
            var datum = PlutusData.integer(42);
            var out = new TxOut(
                    makeOnchainAddress(RECIPIENT_PKH),
                    Value.lovelace(BigInteger.valueOf(5_000_000)),
                    new OutputDatum.OutputDatumInline(datum),
                    Optional.empty());

            assertEquals(datum, OutputLib.getInlineDatum(out));
        }

        @Test
        void getInlineDatum_noDatum_throws() {
            var out = makeOnchainOutput(RECIPIENT_PKH, BigInteger.valueOf(5_000_000));
            assertThrows(RuntimeException.class, () -> OutputLib.getInlineDatum(out));
        }
    }

    // ---- Mode B: UPLC compilation tests ----

    @Nested
    class UplcTests {

        // Address as PlutusData: Constr(0, [Constr(0, [BData(pkh)]), Constr(1, [])])
        //   — PubKeyCredential=Constr(0,[BData]), no staking=Constr(1,[])
        private PlutusData addressData(byte[] pkh) {
            return PlutusData.constr(0,
                    PlutusData.constr(0, PlutusData.bytes(pkh)),
                    PlutusData.constr(1));  // Nothing (no staking credential)
        }

        // PaymentDatum: Constr(0, [addressData, IData(minAmount)])
        private PlutusData buildDatum(byte[] recipientPkh, BigInteger minAmount) {
            return PlutusData.constr(0, addressData(recipientPkh), PlutusData.integer(minAmount));
        }

        @Test
        void paysEnough_evaluatesSuccess() throws Exception {
            var program = compileValidator(OutputCheckValidator.class).program();

            var datum = buildDatum(RECIPIENT_PKH, MIN_AMOUNT);
            var redeemer = PlutusData.UNIT;

            var ref = TestDataBuilder.randomTxOutRef_typed();
            var recipientAddr = TestDataBuilder.pubKeyAddress(new PubKeyHash(RECIPIENT_PKH));
            var txOut = TestDataBuilder.txOut(recipientAddr,
                    com.bloxbean.cardano.julc.ledger.Value.lovelace(BigInteger.valueOf(10_000_000)));
            var ctx = spendingContext(ref, datum)
                    .redeemer(redeemer)
                    .output(txOut)
                    .validRange(com.bloxbean.cardano.julc.ledger.Interval.always())
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("paysEnough_evaluatesSuccess", result);
        }

        @Test
        void paysInsufficient_evaluatesFailure() throws Exception {
            var program = compileValidator(OutputCheckValidator.class).program();

            var datum = buildDatum(RECIPIENT_PKH, MIN_AMOUNT);
            var redeemer = PlutusData.UNIT;

            var ref = TestDataBuilder.randomTxOutRef_typed();
            var recipientAddr = TestDataBuilder.pubKeyAddress(new PubKeyHash(RECIPIENT_PKH));
            var txOut = TestDataBuilder.txOut(recipientAddr,
                    com.bloxbean.cardano.julc.ledger.Value.lovelace(BigInteger.valueOf(3_000_000)));
            var ctx = spendingContext(ref, datum)
                    .redeemer(redeemer)
                    .output(txOut)
                    .validRange(com.bloxbean.cardano.julc.ledger.Interval.always())
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertFailure(result);
            logBudget("paysInsufficient_evaluatesFailure", result);
        }

        @Test
        void multipleOutputsSummed_evaluatesSuccess() throws Exception {
            var program = compileValidator(OutputCheckValidator.class).program();

            var datum = buildDatum(RECIPIENT_PKH, MIN_AMOUNT);
            var redeemer = PlutusData.UNIT;

            var ref = TestDataBuilder.randomTxOutRef_typed();
            var recipientAddr = TestDataBuilder.pubKeyAddress(new PubKeyHash(RECIPIENT_PKH));
            var otherAddr = TestDataBuilder.pubKeyAddress(new PubKeyHash(OTHER_PKH));
            var txOut1 = TestDataBuilder.txOut(recipientAddr,
                    com.bloxbean.cardano.julc.ledger.Value.lovelace(BigInteger.valueOf(5_000_000)));
            var txOut2 = TestDataBuilder.txOut(otherAddr,
                    com.bloxbean.cardano.julc.ledger.Value.lovelace(BigInteger.valueOf(2_000_000)));
            var txOut3 = TestDataBuilder.txOut(recipientAddr,
                    com.bloxbean.cardano.julc.ledger.Value.lovelace(BigInteger.valueOf(4_000_000)));

            var ctx = spendingContext(ref, datum)
                    .redeemer(redeemer)
                    .output(txOut1)
                    .output(txOut2)
                    .output(txOut3)
                    .validRange(com.bloxbean.cardano.julc.ledger.Interval.always())
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("multipleOutputsSummed_evaluatesSuccess", result);
        }
    }

    private static void logBudget(String testName, com.bloxbean.cardano.julc.vm.EvalResult result) {
        var budget = result.budgetConsumed();
        System.out.println("[" + testName + "] CPU: " + budget.cpuSteps() + ", Mem: " + budget.memoryUnits());
        System.out.println("[" + testName + "] Traces: " + result.traces());
    }
}
