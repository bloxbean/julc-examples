package com.example.cftemplates.atomictx.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.PolicyId;
import com.bloxbean.cardano.julc.testkit.ContractTest;
import com.bloxbean.cardano.julc.testkit.TestDataBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CfAtomicTxValidatorTest extends ContractTest {

    @BeforeAll
    static void setup() {
        initCrypto();
    }

    @Nested
    class MintTests {

        @Test
        void correctPassword_passes() throws Exception {
            var compiled = compileValidator(CfAtomicTxValidator.class);
            var program = compiled.program();

            // MintRedeemer = Constr(0, [BData("super_secret_password")])
            var redeemer = PlutusData.constr(0,
                    PlutusData.bytes("super_secret_password".getBytes()));

            var policyId = new PolicyId(new byte[28]);
            var ctx = mintingContext(policyId)
                    .redeemer(redeemer)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("correctPassword_passes", result);
        }

        @Test
        void wrongPassword_fails() throws Exception {
            var compiled = compileValidator(CfAtomicTxValidator.class);
            var program = compiled.program();

            var redeemer = PlutusData.constr(0,
                    PlutusData.bytes("wrong_password".getBytes()));

            var policyId = new PolicyId(new byte[28]);
            var ctx = mintingContext(policyId)
                    .redeemer(redeemer)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertFailure(result);
        }

        @Test
        void emptyPassword_fails() throws Exception {
            var compiled = compileValidator(CfAtomicTxValidator.class);
            var program = compiled.program();

            var redeemer = PlutusData.constr(0,
                    PlutusData.bytes(new byte[0]));

            var policyId = new PolicyId(new byte[28]);
            var ctx = mintingContext(policyId)
                    .redeemer(redeemer)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertFailure(result);
        }
    }

    @Nested
    class SpendTests {

        @Test
        void spendAlwaysPasses() throws Exception {
            var compiled = compileValidator(CfAtomicTxValidator.class);
            var program = compiled.program();

            var datum = PlutusData.constr(1); // None
            var redeemer = PlutusData.constr(0);

            var ref = TestDataBuilder.randomTxOutRef_typed();
            var ctx = spendingContext(ref, datum)
                    .redeemer(redeemer)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
        }

        @Test
        void spendWithSomeDatum_passes() throws Exception {
            var compiled = compileValidator(CfAtomicTxValidator.class);
            var program = compiled.program();

            // Some(Constr(0)) — datum with a value, confirming spend ignores it
            var datum = PlutusData.constr(0, PlutusData.constr(0));
            var redeemer = PlutusData.constr(0);

            var ref = TestDataBuilder.randomTxOutRef_typed();
            var ctx = spendingContext(ref, datum)
                    .redeemer(redeemer)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
        }
    }

    private static void logBudget(String testName, com.bloxbean.cardano.julc.vm.EvalResult result) {
        var budget = result.budgetConsumed();
        System.out.println("[" + testName + "] CPU: " + budget.cpuSteps() + ", Mem: " + budget.memoryUnits());
    }
}
