package com.example.validators;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.ledger.PolicyId;
import com.bloxbean.cardano.julc.ledger.PubKeyHash;
import com.bloxbean.cardano.julc.testkit.BudgetAssertions;
import com.bloxbean.cardano.julc.testkit.ContractTest;
import com.bloxbean.cardano.julc.testkit.jqwik.BudgetCollector;
import com.bloxbean.cardano.julc.testkit.jqwik.CardanoArbitraries;
import net.jqwik.api.*;
import net.jqwik.api.lifecycle.AfterProperty;

import java.util.Arrays;

/**
 * Property-based tests for GuardedMinting.
 * <p>
 * Properties tested:
 * - Authorized signer always succeeds (for any random PKH)
 * - Unauthorized signer always fails (for any two distinct PKHs)
 * - Budget is bounded across all inputs
 */
class GuardedMintingPropertyTest extends ContractTest {

    static final Program PROGRAM;

    static {
        initCrypto();
        try {
            PROGRAM = new GuardedMintingPropertyTest().compileValidator(GuardedMinting.class).program();
        } catch (Exception e) {
            throw new RuntimeException("Failed to compile GuardedMinting", e);
        }
    }

    final BudgetCollector budgetCollector = new BudgetCollector();

    @Property(tries = 200)
    void authorizedSignerAlwaysSucceeds(@ForAll PubKeyHash signer) {
        var ctx = buildCtx(PlutusData.bytes(signer.hash()), signer);
        var result = evaluate(PROGRAM, ctx);
        BudgetAssertions.assertSuccess(result);
        budgetCollector.record(result);
    }

    @Property(tries = 200)
    void unauthorizedSignerAlwaysFails(
            @ForAll PubKeyHash requiredSigner,
            @ForAll PubKeyHash actualSigner) {

        Assume.that(!Arrays.equals(requiredSigner.hash(), actualSigner.hash()));

        var ctx = buildCtx(PlutusData.bytes(requiredSigner.hash()), actualSigner);
        var result = evaluate(PROGRAM, ctx);
        BudgetAssertions.assertFailure(result);
    }

    @Property(tries = 200)
    void budgetIsBounded(@ForAll PubKeyHash signer) {
        var ctx = buildCtx(PlutusData.bytes(signer.hash()), signer);
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

    private PlutusData buildCtx(PlutusData redeemer, PubKeyHash signer) {
        var policyId = new PolicyId(new byte[28]);
        return mintingContext(policyId)
                .redeemer(redeemer)
                .signer(signer)
                .buildPlutusData();
    }
}
