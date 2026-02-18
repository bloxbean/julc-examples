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
 * Tests for OneShotMintPolicy — @MintingPolicy with @Param and break.
 */
class OneShotMintPolicyTest extends ContractTest {

    static final byte[] TX_ID = new byte[]{10, 20, 30, 40, 50, 60, 70, 80, 90, 100,
            110, 120, 1, 2, 3, 4, 5, 6, 7, 8,
            9, 11, 12, 13, 14, 15, 16, 17, 18, 19,
            21, 22};
    static final byte[] OTHER_TX_ID = new byte[]{99, 98, 97, 96, 95, 94, 93, 92, 91, 90,
            89, 88, 87, 86, 85, 84, 83, 82, 81, 80,
            79, 78, 77, 76, 75, 74, 73, 72, 71, 70,
            69, 68};

    @BeforeAll
    static void setup() {
        initCrypto();
    }

    // ---- Mode A: Direct Java tests ----

    @Nested
    class DirectJavaTests {

        private ScriptContext buildCtx(TxInInfo... inputs) {
            var txInfo = new TxInfo(
                    JulcList.of(inputs), JulcList.of(), JulcList.of(),
                    BigInteger.valueOf(200_000),
                    Value.zero(),
                    JulcList.of(), JulcMap.empty(),
                    Interval.always(),
                    JulcList.of(),
                    JulcMap.empty(), JulcMap.empty(),
                    new TxId(new byte[32]),
                    JulcMap.empty(), JulcList.of(),
                    Optional.empty(), Optional.empty());
            return new ScriptContext(txInfo, PlutusData.UNIT,
                    new ScriptInfo.MintingScript(new PolicyId(new byte[28])));
        }

        private TxInInfo makeInput(byte[] txId, BigInteger index) {
            var ref = new TxOutRef(new TxId(txId), index);
            var output = new TxOut(
                    new Address(new Credential.PubKeyCredential(new PubKeyHash(new byte[28])), Optional.empty()),
                    Value.lovelace(BigInteger.valueOf(5_000_000)),
                    new OutputDatum.NoOutputDatum(),
                    Optional.empty());
            return new TxInInfo(ref, output);
        }

        @Test
        void matchingUtxoInput_passes() {
            // Set @Param — SAME byte[] reference for txId
            OneShotMintPolicy.utxoTxId = TX_ID;
            OneShotMintPolicy.utxoIndex = BigInteger.ZERO;

            var input = makeInput(TX_ID, BigInteger.ZERO); // same reference
            var ctx = buildCtx(input);

            boolean result = OneShotMintPolicy.validate(PlutusData.UNIT, ctx);
            assertTrue(result, "Matching UTXO input should pass");
        }

        @Test
        void noMatchingInput_fails() {
            OneShotMintPolicy.utxoTxId = TX_ID;
            OneShotMintPolicy.utxoIndex = BigInteger.ZERO;

            var input = makeInput(OTHER_TX_ID, BigInteger.ZERO); // different txId
            var ctx = buildCtx(input);

            boolean result = OneShotMintPolicy.validate(PlutusData.UNIT, ctx);
            assertFalse(result, "Non-matching UTXO should fail");
        }
    }

    // ---- Mode B: UPLC compilation tests ----

    @Nested
    class UplcTests {

        private PlutusData buildCtxWithInput(PlutusData redeemer, byte[] txId, int index) {
            var ref = new com.bloxbean.cardano.julc.ledger.TxOutRef(
                    new TxId(txId), BigInteger.valueOf(index));
            var address = TestDataBuilder.pubKeyAddress(TestDataBuilder.randomPubKeyHash_typed());
            var txOut = TestDataBuilder.txOut(address,
                    com.bloxbean.cardano.julc.ledger.Value.lovelace(BigInteger.valueOf(5_000_000)));
            var txIn = TestDataBuilder.txIn(ref, txOut);

            var policyId = new com.bloxbean.cardano.julc.ledger.PolicyId(new byte[28]);
            return mintingContext(policyId)
                    .redeemer(redeemer)
                    .input(txIn)
                    .buildPlutusData();
        }

        @Test
        void compilesWithParams_andEvaluates() throws Exception {
            var program = compileValidator(OneShotMintPolicy.class).program();

            // Apply @Param: utxoTxId, utxoIndex
            var concrete = program.applyParams(
                    PlutusData.bytes(TX_ID),
                    PlutusData.integer(0));

            var redeemer = PlutusData.integer(0);
            var ctx = buildCtxWithInput(redeemer, TX_ID, 0);

            var result = evaluate(concrete, ctx);
            assertSuccess(result);
            logBudget("compilesWithParams_andEvaluates", result);
        }

        @Test
        void rejectsWrongUtxo() throws Exception {
            var program = compileValidator(OneShotMintPolicy.class).program();

            var concrete = program.applyParams(
                    PlutusData.bytes(TX_ID),
                    PlutusData.integer(0));

            var redeemer = PlutusData.integer(0);
            var ctx = buildCtxWithInput(redeemer, OTHER_TX_ID, 0); // wrong txId

            var result = evaluate(concrete, ctx);
            assertFailure(result);
            logBudget("rejectsWrongUtxo", result);
        }

        @Test
        void tracesAppear() throws Exception {
            var program = compileValidator(OneShotMintPolicy.class).program();

            var concrete = program.applyParams(
                    PlutusData.bytes(TX_ID),
                    PlutusData.integer(0));

            var redeemer = PlutusData.integer(0);
            var ctx = buildCtxWithInput(redeemer, TX_ID, 0);

            var result = evaluate(concrete, ctx);
            assertSuccess(result);
            BudgetAssertions.assertTrace(result, "Checking UTXO input");
        }
    }

    private static void logBudget(String testName, com.bloxbean.cardano.julc.vm.EvalResult result) {
        var budget = result.budgetConsumed();
        System.out.println("[" + testName + "] CPU: " + budget.cpuSteps() + ", Mem: " + budget.memoryUnits());
        System.out.println("[" + testName + "] Traces: " + result.traces());
    }
}
