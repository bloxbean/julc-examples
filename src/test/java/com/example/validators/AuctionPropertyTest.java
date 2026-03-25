package com.example.validators;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.ledger.PubKeyHash;
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
 * Property-based tests for AuctionValidator (sealed interface redeemer).
 * <p>
 * Properties tested:
 * - Bid: signed bidder with amount >= reservePrice always succeeds
 * - Bid: amount below reserve always fails
 * - Bid: unsigned bidder always fails
 * - Close: seller always succeeds
 * - Close: non-seller always fails
 */
class AuctionPropertyTest extends ContractTest {

    static final Program PROGRAM;

    static {
        initCrypto();
        try {
            PROGRAM = new AuctionPropertyTest().compileValidator(AuctionValidator.class).program();
        } catch (Exception e) {
            throw new RuntimeException("Failed to compile AuctionValidator", e);
        }
    }

    final BudgetCollector budgetCollector = new BudgetCollector();

    // Bid redeemer = Constr(0, [BData(bidder), IData(amount)])
    // Close redeemer = Constr(1, [])
    // AuctionDatum = Constr(0, [BData(seller), IData(reservePrice)])

    @Property(tries = 200)
    void bidWithSufficientAmountAlwaysSucceeds(
            @ForAll PubKeyHash seller,
            @ForAll PubKeyHash bidder,
            @ForAll("reservePrice") BigInteger reservePrice,
            @ForAll("bidAboveReserve") BigInteger bidExtra) {

        Assume.that(!Arrays.equals(seller.hash(), bidder.hash()));

        var bidAmount = reservePrice.add(bidExtra); // always >= reservePrice
        var ctx = buildBidCtx(seller, bidder, reservePrice, bidAmount, bidder);
        var result = evaluate(PROGRAM, ctx);
        BudgetAssertions.assertSuccess(result);
        budgetCollector.record(result);
    }

    @Property(tries = 200)
    void bidBelowReserveAlwaysFails(
            @ForAll PubKeyHash seller,
            @ForAll PubKeyHash bidder,
            @ForAll("reservePrice") BigInteger reservePrice) {

        Assume.that(reservePrice.compareTo(BigInteger.ONE) > 0);

        // Bid 1 lovelace less than reserve
        var bidAmount = reservePrice.subtract(BigInteger.ONE);
        var ctx = buildBidCtx(seller, bidder, reservePrice, bidAmount, bidder);
        var result = evaluate(PROGRAM, ctx);
        BudgetAssertions.assertFailure(result);
    }

    @Property(tries = 200)
    void bidByUnsignedBidderAlwaysFails(
            @ForAll PubKeyHash seller,
            @ForAll PubKeyHash bidder,
            @ForAll PubKeyHash otherSigner,
            @ForAll("reservePrice") BigInteger reservePrice) {

        Assume.that(!Arrays.equals(bidder.hash(), otherSigner.hash()));

        var bidAmount = reservePrice.add(BigInteger.valueOf(1_000_000));
        var ctx = buildBidCtx(seller, bidder, reservePrice, bidAmount, otherSigner);
        var result = evaluate(PROGRAM, ctx);
        BudgetAssertions.assertFailure(result);
    }

    @Property(tries = 200)
    void closeBySellerAlwaysSucceeds(
            @ForAll PubKeyHash seller) {

        var ctx = buildCloseCtx(seller, seller);
        var result = evaluate(PROGRAM, ctx);
        BudgetAssertions.assertSuccess(result);
        budgetCollector.record(result);
    }

    @Property(tries = 200)
    void closeByNonSellerAlwaysFails(
            @ForAll PubKeyHash seller,
            @ForAll PubKeyHash nonSeller) {

        Assume.that(!Arrays.equals(seller.hash(), nonSeller.hash()));

        var ctx = buildCloseCtx(seller, nonSeller);
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
    Arbitrary<BigInteger> reservePrice() {
        return Arbitraries.bigIntegers().between(
                BigInteger.valueOf(1_000_000), BigInteger.valueOf(100_000_000));
    }

    @Provide
    Arbitrary<BigInteger> bidAboveReserve() {
        return Arbitraries.bigIntegers().between(BigInteger.ZERO, BigInteger.valueOf(50_000_000));
    }

    private PlutusData buildBidCtx(PubKeyHash seller, PubKeyHash bidder,
                                    BigInteger reservePrice, BigInteger bidAmount,
                                    PubKeyHash signer) {
        var datum = PlutusData.constr(0,
                PlutusData.bytes(seller.hash()),
                PlutusData.integer(reservePrice));
        var redeemer = PlutusData.constr(0,
                PlutusData.bytes(bidder.hash()),
                PlutusData.integer(bidAmount));
        var ref = TestDataBuilder.randomTxOutRef_typed();
        return spendingContext(ref, datum)
                .redeemer(redeemer)
                .signer(signer)
                .buildPlutusData();
    }

    private PlutusData buildCloseCtx(PubKeyHash seller, PubKeyHash signer) {
        var datum = PlutusData.constr(0,
                PlutusData.bytes(seller.hash()),
                PlutusData.integer(5_000_000));
        var redeemer = PlutusData.constr(1); // Close variant
        var ref = TestDataBuilder.randomTxOutRef_typed();
        return spendingContext(ref, datum)
                .redeemer(redeemer)
                .signer(signer)
                .buildPlutusData();
    }
}
