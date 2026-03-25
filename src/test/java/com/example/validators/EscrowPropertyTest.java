package com.example.validators;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.testkit.BudgetAssertions;
import com.bloxbean.cardano.julc.testkit.ContractTest;
import com.bloxbean.cardano.julc.testkit.TestDataBuilder;
import com.bloxbean.cardano.julc.testkit.jqwik.BudgetCollector;
import com.bloxbean.cardano.julc.testkit.jqwik.CardanoArbitraries;
import net.jqwik.api.*;
import net.jqwik.api.lifecycle.AfterProperty;

import java.math.BigInteger;
import java.util.Arrays;

/**
 * Property-based tests for EscrowValidator.
 * <p>
 * Properties tested:
 * - Complete path: both buyer+seller signed with sufficient payment always succeeds
 * - Complete path: missing buyer signature always fails
 * - Refund path: seller signed + past deadline always succeeds
 * - Refund path: seller signed but before deadline always fails
 */
class EscrowPropertyTest extends ContractTest {

    static final Program PROGRAM;

    static {
        initCrypto();
        try {
            PROGRAM = new EscrowPropertyTest().compileValidator(EscrowValidator.class).program();
        } catch (Exception e) {
            throw new RuntimeException("Failed to compile EscrowValidator", e);
        }
    }

    final BudgetCollector budgetCollector = new BudgetCollector();

    @Property(tries = 200)
    void completeWithBothSignersAndPaymentSucceeds(
            @ForAll PubKeyHash seller,
            @ForAll PubKeyHash buyer,
            @ForAll("price") BigInteger price) {

        Assume.that(!Arrays.equals(seller.hash(), buyer.hash()));

        // Complete: action=0, both signers, output with >= price
        var ctx = buildCompleteCtx(seller, buyer, price, price, seller, buyer);
        var result = evaluate(PROGRAM, ctx);
        BudgetAssertions.assertSuccess(result);
        budgetCollector.record(result);
    }

    @Property(tries = 200)
    void completeMissingBuyerSignatureFails(
            @ForAll PubKeyHash seller,
            @ForAll PubKeyHash buyer,
            @ForAll("price") BigInteger price) {

        Assume.that(!Arrays.equals(seller.hash(), buyer.hash()));

        // Only seller signed — buyer missing
        var ctx = buildCompleteCtx(seller, buyer, price, price, seller);
        var result = evaluate(PROGRAM, ctx);
        BudgetAssertions.assertFailure(result);
    }

    @Property(tries = 200)
    void completeInsufficientPaymentFails(
            @ForAll PubKeyHash seller,
            @ForAll PubKeyHash buyer,
            @ForAll("price") BigInteger price) {

        Assume.that(!Arrays.equals(seller.hash(), buyer.hash()));
        Assume.that(price.compareTo(BigInteger.ONE) > 0);

        // Both signed but output has less than price
        var insufficientPay = price.subtract(BigInteger.ONE);
        var ctx = buildCompleteCtx(seller, buyer, price, insufficientPay, seller, buyer);
        var result = evaluate(PROGRAM, ctx);
        BudgetAssertions.assertFailure(result);
    }

    @Property(tries = 200)
    void refundBySellerPastDeadlineSucceeds(
            @ForAll PubKeyHash seller,
            @ForAll PubKeyHash buyer,
            @ForAll("deadline") BigInteger deadline) {

        Assume.that(!Arrays.equals(seller.hash(), buyer.hash()));

        // Refund: action=1, seller signed, valid range CONTAINS the deadline
        // Interval.always() = (-inf, +inf) contains any deadline
        var ctx = buildRefundCtx(seller, buyer, deadline, Interval.always(), seller);
        var result = evaluate(PROGRAM, ctx);
        BudgetAssertions.assertSuccess(result);
        budgetCollector.record(result);
    }

    @Property(tries = 200)
    void refundBeforeDeadlineFails(
            @ForAll PubKeyHash seller,
            @ForAll PubKeyHash buyer,
            @ForAll("deadline") BigInteger deadline) {

        Assume.that(!Arrays.equals(seller.hash(), buyer.hash()));
        Assume.that(deadline.compareTo(BigInteger.TWO) > 0);

        // Refund: action=1, seller signed, but valid range does NOT contain deadline
        // Interval.before(deadline - 2) = (-inf, deadline-2] does NOT contain deadline
        var ctx = buildRefundCtx(seller, buyer, deadline,
                Interval.before(deadline.subtract(BigInteger.TWO)), seller);
        var result = evaluate(PROGRAM, ctx);
        BudgetAssertions.assertFailure(result);
    }

    @AfterProperty
    void reportBudget() {
        if (budgetCollector.count() > 0) {
            System.out.println(budgetCollector.summary());
        }
    }

    @Provide
    Arbitrary<BigInteger> price() {
        return Arbitraries.bigIntegers().between(
                BigInteger.valueOf(1_000_000), BigInteger.valueOf(100_000_000));
    }

    @Provide
    Arbitrary<BigInteger> deadline() {
        return Arbitraries.bigIntegers().between(
                BigInteger.valueOf(1000), BigInteger.valueOf(1_000_000_000));
    }

    /** Build a Complete (action=0) context with an output paying the specified amount. */
    private PlutusData buildCompleteCtx(PubKeyHash seller, PubKeyHash buyer,
                                         BigInteger price, BigInteger outputAmount,
                                         PubKeyHash... signers) {
        // EscrowDatum = Constr(0, [BData(seller), BData(buyer), IData(deadline), IData(price)])
        var datum = PlutusData.constr(0,
                PlutusData.bytes(seller.hash()),
                PlutusData.bytes(buyer.hash()),
                PlutusData.integer(1_000_000), // deadline (irrelevant for complete)
                PlutusData.integer(price));
        var redeemer = PlutusData.constr(0, PlutusData.integer(0)); // action=0 (complete)

        var ref = TestDataBuilder.randomTxOutRef_typed();
        var builder = spendingContext(ref, datum).redeemer(redeemer);
        for (var signer : signers) {
            builder.signer(signer);
        }
        // Add an output with the payment amount
        builder.output(TestDataBuilder.txOut(
                TestDataBuilder.pubKeyAddress(seller),
                Value.lovelace(outputAmount)));
        return builder.buildPlutusData();
    }

    /** Build a Refund (action=1) context with the specified valid range. */
    private PlutusData buildRefundCtx(PubKeyHash seller, PubKeyHash buyer,
                                       BigInteger deadline, Interval validRange,
                                       PubKeyHash... signers) {
        var datum = PlutusData.constr(0,
                PlutusData.bytes(seller.hash()),
                PlutusData.bytes(buyer.hash()),
                PlutusData.integer(deadline),
                PlutusData.integer(5_000_000)); // price (irrelevant for refund)
        var redeemer = PlutusData.constr(0, PlutusData.integer(1)); // action=1 (refund)

        var ref = TestDataBuilder.randomTxOutRef_typed();
        var builder = spendingContext(ref, datum)
                .redeemer(redeemer)
                .validRange(validRange);
        for (var signer : signers) {
            builder.signer(signer);
        }
        return builder.buildPlutusData();
    }
}
