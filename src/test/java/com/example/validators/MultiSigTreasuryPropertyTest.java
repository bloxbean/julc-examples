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

import java.util.Arrays;

/**
 * Property-based tests for MultiSigTreasury.
 * <p>
 * Properties tested:
 * - Both signers present always succeeds
 * - Only one of two signers always fails
 * - No matching signers always fails
 * - Budget is bounded
 */
class MultiSigTreasuryPropertyTest extends ContractTest {

    static final Program PROGRAM;

    static {
        initCrypto();
        try {
            PROGRAM = new MultiSigTreasuryPropertyTest().compileValidator(MultiSigTreasury.class).program();
        } catch (Exception e) {
            throw new RuntimeException("Failed to compile MultiSigTreasury", e);
        }
    }

    final BudgetCollector budgetCollector = new BudgetCollector();

    @Property(tries = 200)
    void bothSignersPresentAlwaysSucceeds(
            @ForAll PubKeyHash signer1,
            @ForAll PubKeyHash signer2) {

        Assume.that(!Arrays.equals(signer1.hash(), signer2.hash()));

        var ctx = buildCtx(signer1, signer2, signer1, signer2);
        var result = evaluate(PROGRAM, ctx);
        BudgetAssertions.assertSuccess(result);
        budgetCollector.record(result);
    }

    @Property(tries = 200)
    void onlyFirstSignerFails(
            @ForAll PubKeyHash signer1,
            @ForAll PubKeyHash signer2) {

        Assume.that(!Arrays.equals(signer1.hash(), signer2.hash()));

        var ctx = buildCtx(signer1, signer2, signer1); // missing signer2
        var result = evaluate(PROGRAM, ctx);
        BudgetAssertions.assertFailure(result);
    }

    @Property(tries = 200)
    void onlySecondSignerFails(
            @ForAll PubKeyHash signer1,
            @ForAll PubKeyHash signer2) {

        Assume.that(!Arrays.equals(signer1.hash(), signer2.hash()));

        var ctx = buildCtx(signer1, signer2, signer2); // missing signer1
        var result = evaluate(PROGRAM, ctx);
        BudgetAssertions.assertFailure(result);
    }

    @Property(tries = 200)
    void unrelatedSignerFails(
            @ForAll PubKeyHash signer1,
            @ForAll PubKeyHash signer2,
            @ForAll PubKeyHash intruder) {

        Assume.that(!Arrays.equals(signer1.hash(), signer2.hash()));
        Assume.that(!Arrays.equals(intruder.hash(), signer1.hash()));
        Assume.that(!Arrays.equals(intruder.hash(), signer2.hash()));

        var ctx = buildCtx(signer1, signer2, intruder); // wrong signer
        var result = evaluate(PROGRAM, ctx);
        BudgetAssertions.assertFailure(result);
    }

    @Property(tries = 200)
    void budgetIsBounded(
            @ForAll PubKeyHash signer1,
            @ForAll PubKeyHash signer2) {

        Assume.that(!Arrays.equals(signer1.hash(), signer2.hash()));

        var ctx = buildCtx(signer1, signer2, signer1, signer2);
        var result = evaluate(PROGRAM, ctx);
        BudgetAssertions.assertSuccess(result);
        BudgetAssertions.assertBudgetUnder(result, 100_000_000, 500_000);
    }

    @AfterProperty
    void reportBudget() {
        if (budgetCollector.count() > 0) {
            System.out.println(budgetCollector.summary());
        }
    }

    /** Build UPLC context: datum=TreasuryDatum(s1,s2), redeemer=0, signers=provided */
    private PlutusData buildCtx(PubKeyHash s1, PubKeyHash s2, PubKeyHash... signers) {
        var datum = PlutusData.constr(0, PlutusData.bytes(s1.hash()), PlutusData.bytes(s2.hash()));
        var ref = TestDataBuilder.randomTxOutRef_typed();
        var builder = spendingContext(ref, datum).redeemer(PlutusData.integer(0));
        for (var signer : signers) {
            builder.signer(signer);
        }
        return builder.buildPlutusData();
    }
}
