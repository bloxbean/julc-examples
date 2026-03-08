package com.example.cftemplates.simpletransfer.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.testkit.ContractTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CfSimpleTransferValidatorTest extends ContractTest {

    static final byte[] RECEIVER = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
            11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
            21, 22, 23, 24, 25, 26, 27, 28};
    static final byte[] OTHER = new byte[]{31, 32, 33, 34, 35, 36, 37, 38, 39, 40,
            41, 42, 43, 44, 45, 46, 47, 48, 49, 50,
            51, 52, 53, 54, 55, 56, 57, 58};

    @BeforeAll
    static void setup() {
        initCrypto();
    }

    @Nested
    class UplcTests {

        @Test
        void receiverSigned_passes() throws Exception {
            var compiled = compileValidator(CfSimpleTransferValidator.class);
            var program = compiled.program()
                    .applyParams(PlutusData.bytes(RECEIVER));

            var datum = PlutusData.constr(1); // None (Optional empty)
            var redeemer = PlutusData.constr(0);

            var ref = com.bloxbean.cardano.julc.testkit.TestDataBuilder.randomTxOutRef_typed();
            var ctx = spendingContext(ref, datum)
                    .redeemer(redeemer)
                    .signer(RECEIVER)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("receiverSigned_passes", result);
        }

        @Test
        void nonReceiverSigned_fails() throws Exception {
            var compiled = compileValidator(CfSimpleTransferValidator.class);
            var program = compiled.program()
                    .applyParams(PlutusData.bytes(RECEIVER));

            var datum = PlutusData.constr(1); // None
            var redeemer = PlutusData.constr(0);

            var ref = com.bloxbean.cardano.julc.testkit.TestDataBuilder.randomTxOutRef_typed();
            var ctx = spendingContext(ref, datum)
                    .redeemer(redeemer)
                    .signer(OTHER)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertFailure(result);
            logBudget("nonReceiverSigned_fails", result);
        }

        @Test
        void noSignature_fails() throws Exception {
            var compiled = compileValidator(CfSimpleTransferValidator.class);
            var program = compiled.program()
                    .applyParams(PlutusData.bytes(RECEIVER));

            var datum = PlutusData.constr(1); // None
            var redeemer = PlutusData.constr(0);

            var ref = com.bloxbean.cardano.julc.testkit.TestDataBuilder.randomTxOutRef_typed();
            var ctx = spendingContext(ref, datum)
                    .redeemer(redeemer)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertFailure(result);
        }
    }

    private static void logBudget(String testName, com.bloxbean.cardano.julc.vm.EvalResult result) {
        var budget = result.budgetConsumed();
        System.out.println("[" + testName + "] CPU: " + budget.cpuSteps() + ", Mem: " + budget.memoryUnits());
    }
}
