package com.example.validators;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.testkit.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UPLC compilation tests for TokenDistributionValidator — validates on-chain
 * execution of the new HOF + lambda type inference features:
 * <ul>
 *   <li>list.all() — verify all beneficiaries paid</li>
 *   <li>list.any() — check signatory membership</li>
 *   <li>list.filter() — find outputs matching a credential</li>
 *   <li>Variable capture in lambdas</li>
 *   <li>Block body lambdas with switch expressions</li>
 * </ul>
 */
class TokenDistributionValidatorTest extends ContractTest {

    static final byte[] ADMIN = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
            11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
            21, 22, 23, 24, 25, 26, 27, 28};
    static final byte[] BENEFICIARY_A = new byte[]{21, 22, 23, 24, 25, 26, 27, 28, 29, 30,
            31, 32, 33, 34, 35, 36, 37, 38, 39, 40,
            41, 42, 43, 44, 45, 46, 47, 48};
    static final byte[] BENEFICIARY_B = new byte[]{51, 52, 53, 54, 55, 56, 57, 58, 59, 60,
            61, 62, 63, 64, 65, 66, 67, 68, 69, 70,
            71, 72, 73, 74, 75, 76, 77, 78};
    static final byte[] OUTSIDER = new byte[]{91, 92, 93, 94, 95, 96, 97, 98, 99, 100,
            101, 102, 103, 104, 105, 106, 107, 108, 109, 110,
            111, 112, 113, 114, 115, 116, 117, 118};

    static final BigInteger AMOUNT_A = BigInteger.valueOf(10_000_000);  // 10 ADA
    static final BigInteger AMOUNT_B = BigInteger.valueOf(5_000_000);   // 5 ADA

    @BeforeAll
    static void setup() {
        initCrypto();
    }

    // BeneficiaryEntry: Constr(0, [BData(pkh), IData(amount)])
    private PlutusData buildEntry(byte[] pkh, BigInteger amount) {
        return PlutusData.constr(0, PlutusData.bytes(pkh), PlutusData.integer(amount));
    }

    // DistributionDatum: Constr(0, [ListData([entry1, entry2, ...])])
    private PlutusData buildDatum(PlutusData... entries) {
        return PlutusData.constr(0, PlutusData.list(entries));
    }

    // Distribute: Constr(0, [])
    private PlutusData distributeRedeemer() {
        return PlutusData.constr(0);
    }

    // Cancel: Constr(1, [])
    private PlutusData cancelRedeemer() {
        return PlutusData.constr(1);
    }

    @Test
    void distribute_twoRecipients_passes() throws Exception {
        var result = compileValidator(TokenDistributionValidator.class);
        assertFalse(result.hasErrors(), "Compilation should succeed: " + result);
        var program = result.program();

        // Apply @Param: adminPkh = BData(ADMIN)
        var concrete = program.applyParams(PlutusData.bytes(ADMIN));

        var datum = buildDatum(
                buildEntry(BENEFICIARY_A, AMOUNT_A),
                buildEntry(BENEFICIARY_B, AMOUNT_B));
        var redeemer = distributeRedeemer();

        var ref = TestDataBuilder.randomTxOutRef_typed();
        var outputA = TestDataBuilder.txOut(
                TestDataBuilder.pubKeyAddress(new PubKeyHash(BENEFICIARY_A)),
                Value.lovelace(AMOUNT_A));
        var outputB = TestDataBuilder.txOut(
                TestDataBuilder.pubKeyAddress(new PubKeyHash(BENEFICIARY_B)),
                Value.lovelace(AMOUNT_B));

        var ctx = spendingContext(ref, datum)
                .redeemer(redeemer)
                .signer(ADMIN)
                .output(outputA)
                .output(outputB)
                .buildPlutusData();

        var evalResult = evaluate(concrete, ctx);
        assertSuccess(evalResult);
        logBudget("distribute_twoRecipients_passes", evalResult);
    }

    @Test
    void distribute_underpayment_fails() throws Exception {
        var result = compileValidator(TokenDistributionValidator.class);
        var program = result.program();
        var concrete = program.applyParams(PlutusData.bytes(ADMIN));

        var datum = buildDatum(buildEntry(BENEFICIARY_A, AMOUNT_A));
        var redeemer = distributeRedeemer();

        var ref = TestDataBuilder.randomTxOutRef_typed();
        // Pay only 5 ADA instead of required 10 ADA
        var outputA = TestDataBuilder.txOut(
                TestDataBuilder.pubKeyAddress(new PubKeyHash(BENEFICIARY_A)),
                Value.lovelace(BigInteger.valueOf(5_000_000)));

        var ctx = spendingContext(ref, datum)
                .redeemer(redeemer)
                .signer(ADMIN)
                .output(outputA)
                .buildPlutusData();

        var evalResult = evaluate(concrete, ctx);
        assertFailure(evalResult);
        logBudget("distribute_underpayment_fails", evalResult);
    }

    @Test
    void distribute_noAdminSig_fails() throws Exception {
        var result = compileValidator(TokenDistributionValidator.class);
        var program = result.program();
        var concrete = program.applyParams(PlutusData.bytes(ADMIN));

        var datum = buildDatum(buildEntry(BENEFICIARY_A, AMOUNT_A));
        var redeemer = distributeRedeemer();

        var ref = TestDataBuilder.randomTxOutRef_typed();
        var outputA = TestDataBuilder.txOut(
                TestDataBuilder.pubKeyAddress(new PubKeyHash(BENEFICIARY_A)),
                Value.lovelace(AMOUNT_A));

        // Outsider signs instead of admin
        var ctx = spendingContext(ref, datum)
                .redeemer(redeemer)
                .signer(OUTSIDER)
                .output(outputA)
                .buildPlutusData();

        var evalResult = evaluate(concrete, ctx);
        assertFailure(evalResult);
        logBudget("distribute_noAdminSig_fails", evalResult);
    }

    @Test
    void cancel_withAdminSig_passes() throws Exception {
        var result = compileValidator(TokenDistributionValidator.class);
        var program = result.program();
        var concrete = program.applyParams(PlutusData.bytes(ADMIN));

        var datum = buildDatum(buildEntry(BENEFICIARY_A, AMOUNT_A));
        var redeemer = cancelRedeemer();

        var ref = TestDataBuilder.randomTxOutRef_typed();
        var ctx = spendingContext(ref, datum)
                .redeemer(redeemer)
                .signer(ADMIN)
                .buildPlutusData();

        var evalResult = evaluate(concrete, ctx);
        assertSuccess(evalResult);
        logBudget("cancel_withAdminSig_passes", evalResult);
    }

    @Test
    void traces_emitted() throws Exception {
        var result = compileValidator(TokenDistributionValidator.class);
        var program = result.program();
        var concrete = program.applyParams(PlutusData.bytes(ADMIN));

        var datum = buildDatum(buildEntry(BENEFICIARY_A, AMOUNT_A));
        var redeemer = distributeRedeemer();

        var ref = TestDataBuilder.randomTxOutRef_typed();
        var outputA = TestDataBuilder.txOut(
                TestDataBuilder.pubKeyAddress(new PubKeyHash(BENEFICIARY_A)),
                Value.lovelace(AMOUNT_A));

        var ctx = spendingContext(ref, datum)
                .redeemer(redeemer)
                .signer(ADMIN)
                .output(outputA)
                .buildPlutusData();

        var evalResult = evaluate(concrete, ctx);
        assertSuccess(evalResult);
        BudgetAssertions.assertTrace(evalResult,
                "Checking admin signature",
                "Distribute: checking all beneficiaries paid");
    }

    private static void logBudget(String testName, com.bloxbean.cardano.julc.vm.EvalResult result) {
        var budget = result.budgetConsumed();
        System.out.println("[" + testName + "] CPU: " + budget.cpuSteps() + ", Mem: " + budget.memoryUnits());
        System.out.println("[" + testName + "] Traces: " + result.traces());
    }
}
