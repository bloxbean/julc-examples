package com.example.cftemplates.escrow.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.testkit.ContractTest;
import com.bloxbean.cardano.julc.testkit.TestDataBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class CfEscrowValidatorTest extends ContractTest {

    static final byte[] INITIATOR = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
            11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
            21, 22, 23, 24, 25, 26, 27, 28};
    static final byte[] RECIPIENT = new byte[]{31, 32, 33, 34, 35, 36, 37, 38, 39, 40,
            41, 42, 43, 44, 45, 46, 47, 48, 49, 50,
            51, 52, 53, 54, 55, 56, 57, 58};
    static final byte[] OTHER = new byte[]{61, 62, 63, 64, 65, 66, 67, 68, 69, 70,
            71, 72, 73, 74, 75, 76, 77, 78, 79, 80,
            81, 82, 83, 84, 85, 86, 87, 88};

    static final BigInteger INITIATOR_AMT = BigInteger.valueOf(10_000_000);
    static final BigInteger RECIPIENT_AMT = BigInteger.valueOf(5_000_000);

    // Script hash for the escrow script address
    static final byte[] SCRIPT_HASH = new byte[]{(byte) 0xA1, (byte) 0xA2, (byte) 0xA3, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0};

    @BeforeAll
    static void setup() {
        initCrypto();
    }

    static Address scriptAddress() {
        return new Address(
                new Credential.ScriptCredential(new ScriptHash(SCRIPT_HASH)),
                Optional.empty());
    }

    static Address pubKeyAddress(byte[] pkh) {
        return new Address(
                new Credential.PubKeyCredential(new PubKeyHash(pkh)),
                Optional.empty());
    }

    // Initiation datum = Constr(0, [initiator, initiatorAmount])
    static PlutusData initiationDatum() {
        return PlutusData.constr(0, PlutusData.bytes(INITIATOR), PlutusData.integer(INITIATOR_AMT));
    }

    // ActiveEscrow datum = Constr(1, [initiator, initiatorAmount, recipient, recipientAmount])
    static PlutusData activeEscrowDatum() {
        return PlutusData.constr(1,
                PlutusData.bytes(INITIATOR), PlutusData.integer(INITIATOR_AMT),
                PlutusData.bytes(RECIPIENT), PlutusData.integer(RECIPIENT_AMT));
    }

    @Nested
    class CancelTradeTests {

        @Test
        void cancelInitiation_initiatorSigns_passes() throws Exception {
            var compiled = compileValidator(CfEscrowValidator.class);
            var program = compiled.program();

            var datum = initiationDatum();
            // CancelTrade = tag 1
            var redeemer = PlutusData.constr(1);

            // Build input at script address with the Initiation datum
            var spentRef = TestDataBuilder.randomTxOutRef_typed();
            var spentInput = new TxInInfo(spentRef,
                    new TxOut(scriptAddress(),
                            Value.lovelace(INITIATOR_AMT),
                            new OutputDatum.OutputDatumInline(datum),
                            Optional.empty()));

            // Output goes to initiator (NOT back to script, so noContinuing check passes)
            var initiatorOutput = new TxOut(pubKeyAddress(INITIATOR),
                    Value.lovelace(INITIATOR_AMT),
                    new OutputDatum.NoOutputDatum(), Optional.empty());

            var ctx = spendingContext(spentRef, datum)
                    .redeemer(redeemer)
                    .input(spentInput)
                    .output(initiatorOutput)
                    .signer(INITIATOR)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("cancelInitiation_initiatorSigns_passes", result);
        }

        @Test
        void cancelInitiation_nonInitiator_fails() throws Exception {
            var compiled = compileValidator(CfEscrowValidator.class);
            var program = compiled.program();

            var datum = initiationDatum();
            var redeemer = PlutusData.constr(1);

            var spentRef = TestDataBuilder.randomTxOutRef_typed();
            var spentInput = new TxInInfo(spentRef,
                    new TxOut(scriptAddress(),
                            Value.lovelace(INITIATOR_AMT),
                            new OutputDatum.OutputDatumInline(datum),
                            Optional.empty()));

            var otherOutput = new TxOut(pubKeyAddress(OTHER),
                    Value.lovelace(INITIATOR_AMT),
                    new OutputDatum.NoOutputDatum(), Optional.empty());

            var ctx = spendingContext(spentRef, datum)
                    .redeemer(redeemer)
                    .input(spentInput)
                    .output(otherOutput)
                    .signer(OTHER)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertFailure(result);
        }

        @Test
        void cancelActive_eitherPartySigns_passes() throws Exception {
            var compiled = compileValidator(CfEscrowValidator.class);
            var program = compiled.program();

            var datum = activeEscrowDatum();
            var redeemer = PlutusData.constr(1);

            var spentRef = TestDataBuilder.randomTxOutRef_typed();
            var spentInput = new TxInInfo(spentRef,
                    new TxOut(scriptAddress(),
                            Value.lovelace(INITIATOR_AMT.add(RECIPIENT_AMT)),
                            new OutputDatum.OutputDatumInline(datum),
                            Optional.empty()));

            // Both parties get their amounts back
            var initiatorOutput = new TxOut(pubKeyAddress(INITIATOR),
                    Value.lovelace(INITIATOR_AMT),
                    new OutputDatum.NoOutputDatum(), Optional.empty());
            var recipientOutput = new TxOut(pubKeyAddress(RECIPIENT),
                    Value.lovelace(RECIPIENT_AMT),
                    new OutputDatum.NoOutputDatum(), Optional.empty());

            var ctx = spendingContext(spentRef, datum)
                    .redeemer(redeemer)
                    .input(spentInput)
                    .output(initiatorOutput)
                    .output(recipientOutput)
                    .signer(INITIATOR)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("cancelActive_eitherPartySigns_passes", result);
        }
    }

    @Nested
    class CompleteTradeTests {

        @Test
        void completeTrade_bothSign_passes() throws Exception {
            var compiled = compileValidator(CfEscrowValidator.class);
            var program = compiled.program();

            var datum = activeEscrowDatum();
            // CompleteTrade = tag 2
            var redeemer = PlutusData.constr(2);

            var spentRef = TestDataBuilder.randomTxOutRef_typed();
            var spentInput = new TxInInfo(spentRef,
                    new TxOut(scriptAddress(),
                            Value.lovelace(INITIATOR_AMT.add(RECIPIENT_AMT)),
                            new OutputDatum.OutputDatumInline(datum),
                            Optional.empty()));

            // Swap: initiator gets recipientAmount, recipient gets initiatorAmount
            var initiatorOutput = new TxOut(pubKeyAddress(INITIATOR),
                    Value.lovelace(RECIPIENT_AMT),
                    new OutputDatum.NoOutputDatum(), Optional.empty());
            var recipientOutput = new TxOut(pubKeyAddress(RECIPIENT),
                    Value.lovelace(INITIATOR_AMT),
                    new OutputDatum.NoOutputDatum(), Optional.empty());

            var ctx = spendingContext(spentRef, datum)
                    .redeemer(redeemer)
                    .input(spentInput)
                    .output(initiatorOutput)
                    .output(recipientOutput)
                    .signer(INITIATOR)
                    .signer(RECIPIENT)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("completeTrade_bothSign_passes", result);
        }

        @Test
        void completeTrade_onlyOneSigns_fails() throws Exception {
            var compiled = compileValidator(CfEscrowValidator.class);
            var program = compiled.program();

            var datum = activeEscrowDatum();
            var redeemer = PlutusData.constr(2);

            var spentRef = TestDataBuilder.randomTxOutRef_typed();
            var spentInput = new TxInInfo(spentRef,
                    new TxOut(scriptAddress(),
                            Value.lovelace(INITIATOR_AMT.add(RECIPIENT_AMT)),
                            new OutputDatum.OutputDatumInline(datum),
                            Optional.empty()));

            var initiatorOutput = new TxOut(pubKeyAddress(INITIATOR),
                    Value.lovelace(RECIPIENT_AMT),
                    new OutputDatum.NoOutputDatum(), Optional.empty());
            var recipientOutput = new TxOut(pubKeyAddress(RECIPIENT),
                    Value.lovelace(INITIATOR_AMT),
                    new OutputDatum.NoOutputDatum(), Optional.empty());

            var ctx = spendingContext(spentRef, datum)
                    .redeemer(redeemer)
                    .input(spentInput)
                    .output(initiatorOutput)
                    .output(recipientOutput)
                    .signer(INITIATOR) // only initiator
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertFailure(result);
        }
    }

    @Nested
    class RecipientDepositTests {

        @Test
        void recipientDeposit_validTransition_passes() throws Exception {
            var compiled = compileValidator(CfEscrowValidator.class);
            var program = compiled.program();

            var datum = initiationDatum();
            // RecipientDeposit = Constr(0, [recipient, recipientAmount])
            var redeemer = PlutusData.constr(0,
                    PlutusData.bytes(RECIPIENT), PlutusData.integer(RECIPIENT_AMT));

            var spentRef = TestDataBuilder.randomTxOutRef_typed();
            var spentInput = new TxInInfo(spentRef,
                    new TxOut(scriptAddress(),
                            Value.lovelace(INITIATOR_AMT),
                            new OutputDatum.OutputDatumInline(datum),
                            Optional.empty()));

            // Continuing output at same script address with ActiveEscrow datum
            var continuingOutput = new TxOut(scriptAddress(),
                    Value.lovelace(INITIATOR_AMT.add(RECIPIENT_AMT)),
                    new OutputDatum.OutputDatumInline(activeEscrowDatum()),
                    Optional.empty());

            var ctx = spendingContext(spentRef, datum)
                    .redeemer(redeemer)
                    .input(spentInput)
                    .output(continuingOutput)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("recipientDeposit_validTransition_passes", result);
        }
    }

    private static void logBudget(String testName, com.bloxbean.cardano.julc.vm.EvalResult result) {
        var budget = result.budgetConsumed();
        System.out.println("[" + testName + "] CPU: " + budget.cpuSteps() + ", Mem: " + budget.memoryUnits());
    }
}
