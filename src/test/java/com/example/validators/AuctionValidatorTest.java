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
 * Tests for AuctionValidator — sealed interface redeemer with 2 variants (Bid, Close).
 * <p>
 * DirectJavaTests: unit tests calling validator logic directly in Java.
 * UplcTests: compile to UPLC and evaluate on VM.
 */
class AuctionValidatorTest extends ContractTest {

    static final byte[] SELLER = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
            11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
            21, 22, 23, 24, 25, 26, 27, 28};
    static final byte[] BIDDER = new byte[]{31, 32, 33, 34, 35, 36, 37, 38, 39, 40,
            41, 42, 43, 44, 45, 46, 47, 48, 49, 50,
            51, 52, 53, 54, 55, 56, 57, 58};
    static final byte[] OTHER_PKH = new byte[]{99, 98, 97, 96, 95, 94, 93, 92, 91, 90,
            89, 88, 87, 86, 85, 84, 83, 82, 81, 80,
            79, 78, 77, 76, 75, 74, 73, 72};
    static final BigInteger RESERVE_PRICE = BigInteger.valueOf(5_000_000);

    @BeforeAll
    static void setup() {
        initCrypto();
    }

    // ---- Mode A: Direct Java tests ----

    @Nested
    class DirectJavaTests {

        private ScriptContext buildCtx(byte[][] signers) {
            var sigList = JulcList.<PubKeyHash>empty();
            for (byte[] sig : signers) {
                sigList = sigList.prepend(new PubKeyHash(sig));
            }
            var txInfo = new TxInfo(
                    JulcList.of(), JulcList.of(),
                    JulcList.of(),
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

        @Test
        void bidWithSufficientAmount_passes() {
            var datum = new AuctionValidator.AuctionDatum(SELLER, RESERVE_PRICE);
            var redeemer = new AuctionValidator.Bid(BIDDER, BigInteger.valueOf(7_000_000));
            var ctx = buildCtx(new byte[][]{BIDDER});

            boolean result = AuctionValidator.validate(datum, redeemer, ctx);
            assertTrue(result, "Bidder signed + amount >= reservePrice should pass");
        }

        @Test
        void bidWithInsufficientAmount_fails() {
            var datum = new AuctionValidator.AuctionDatum(SELLER, RESERVE_PRICE);
            var redeemer = new AuctionValidator.Bid(BIDDER, BigInteger.valueOf(1_000_000));
            var ctx = buildCtx(new byte[][]{BIDDER});

            boolean result = AuctionValidator.validate(datum, redeemer, ctx);
            assertFalse(result, "Amount < reservePrice should fail");
        }

        @Test
        void bidByUnsignedBidder_fails() {
            var datum = new AuctionValidator.AuctionDatum(SELLER, RESERVE_PRICE);
            var redeemer = new AuctionValidator.Bid(BIDDER, BigInteger.valueOf(7_000_000));
            var ctx = buildCtx(new byte[][]{OTHER_PKH}); // bidder not in signatories

            boolean result = AuctionValidator.validate(datum, redeemer, ctx);
            assertFalse(result, "Bidder not in signatories should fail");
        }

        @Test
        void closeBySeller_passes() {
            var datum = new AuctionValidator.AuctionDatum(SELLER, RESERVE_PRICE);
            var redeemer = new AuctionValidator.Close();
            var ctx = buildCtx(new byte[][]{SELLER});

            boolean result = AuctionValidator.validate(datum, redeemer, ctx);
            assertTrue(result, "Seller signed should pass for close");
        }

        @Test
        void closeByNonSeller_fails() {
            var datum = new AuctionValidator.AuctionDatum(SELLER, RESERVE_PRICE);
            var redeemer = new AuctionValidator.Close();
            var ctx = buildCtx(new byte[][]{OTHER_PKH}); // seller not in signatories

            boolean result = AuctionValidator.validate(datum, redeemer, ctx);
            assertFalse(result, "Non-seller should fail for close");
        }
    }

    // ---- Mode B: UPLC compilation tests ----

    @Nested
    class UplcTests {

        // AuctionDatum: Constr(0, [BData(seller), IData(reservePrice)])
        private PlutusData buildDatum() {
            return PlutusData.constr(0,
                    PlutusData.bytes(SELLER), PlutusData.integer(RESERVE_PRICE));
        }

        // Bid: Constr(0, [BData(bidder), IData(amount)])
        private PlutusData buildBidRedeemer(byte[] bidder, BigInteger amount) {
            return PlutusData.constr(0,
                    PlutusData.bytes(bidder), PlutusData.integer(amount));
        }

        // Close: Constr(1, [])
        private PlutusData buildCloseRedeemer() {
            return PlutusData.constr(1);
        }

        @Test
        void bid_evaluatesSuccess() throws Exception {
            var program = compileValidator(AuctionValidator.class).program();

            var datum = buildDatum();
            var redeemer = buildBidRedeemer(BIDDER, BigInteger.valueOf(7_000_000));

            var ref = TestDataBuilder.randomTxOutRef_typed();
            var ctx = spendingContext(ref, datum)
                    .redeemer(redeemer)
                    .signer(BIDDER)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("bid_evaluatesSuccess", result);
        }

        @Test
        void bid_rejectsUnsigned() throws Exception {
            var program = compileValidator(AuctionValidator.class).program();

            var datum = buildDatum();
            var redeemer = buildBidRedeemer(BIDDER, BigInteger.valueOf(7_000_000));

            var ref = TestDataBuilder.randomTxOutRef_typed();
            var ctx = spendingContext(ref, datum)
                    .redeemer(redeemer)
                    .signer(OTHER_PKH) // bidder not in signatories
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertFailure(result);
            logBudget("bid_rejectsUnsigned", result);
        }

        @Test
        void close_evaluatesSuccess() throws Exception {
            var program = compileValidator(AuctionValidator.class).program();

            var datum = buildDatum();
            var redeemer = buildCloseRedeemer();

            var ref = TestDataBuilder.randomTxOutRef_typed();
            var ctx = spendingContext(ref, datum)
                    .redeemer(redeemer)
                    .signer(SELLER)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("close_evaluatesSuccess", result);
        }

        @Test
        void tracesAppear() throws Exception {
            var program = compileValidator(AuctionValidator.class).program();

            var datum = buildDatum();
            var redeemer = buildBidRedeemer(BIDDER, BigInteger.valueOf(7_000_000));

            var ref = TestDataBuilder.randomTxOutRef_typed();
            var ctx = spendingContext(ref, datum)
                    .redeemer(redeemer)
                    .signer(BIDDER)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            BudgetAssertions.assertTrace(result, "Auction validate", "Bid path");
        }
    }

    private static void logBudget(String testName, com.bloxbean.cardano.julc.vm.EvalResult result) {
        var budget = result.budgetConsumed();
        System.out.println("[" + testName + "] CPU: " + budget.cpuSteps() + ", Mem: " + budget.memoryUnits());
        System.out.println("[" + testName + "] Traces: " + result.traces());
    }
}
