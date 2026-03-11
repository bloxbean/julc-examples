package com.example.cftemplates.identity.onchain;

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

class CfIdentityValidatorTest extends ContractTest {

    static final byte[] OWNER = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
            11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
            21, 22, 23, 24, 25, 26, 27, 28};
    static final byte[] NEW_OWNER = new byte[]{31, 32, 33, 34, 35, 36, 37, 38, 39, 40,
            41, 42, 43, 44, 45, 46, 47, 48, 49, 50,
            51, 52, 53, 54, 55, 56, 57, 58};
    static final byte[] DELEGATE1 = new byte[]{61, 62, 63, 64, 65, 66, 67, 68, 69, 70,
            71, 72, 73, 74, 75, 76, 77, 78, 79, 80,
            81, 82, 83, 84, 85, 86, 87, 88};
    static final byte[] OTHER = new byte[]{91, 92, 93, 94, 95, 96, 97, 98, 99, 100,
            101, 102, 103, 104, 105, 106, 107, 108, 109, 110,
            111, 112, 113, 114, 115, 116, 117, 118};
    static final BigInteger VALUE = BigInteger.valueOf(5_000_000);
    static final BigInteger EXPIRES = BigInteger.valueOf(1_700_000_000_000L);

    // Script hash for the identity script address
    static final byte[] SCRIPT_HASH = new byte[]{(byte) 0xD1, (byte) 0xD2, (byte) 0xD3, 0, 0, 0, 0, 0, 0, 0,
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

    // Delegate = Constr(0, [key, expires])
    static PlutusData delegate(byte[] key, BigInteger expires) {
        return PlutusData.constr(0, PlutusData.bytes(key), PlutusData.integer(expires));
    }

    // IdentityDatum = Constr(0, [owner, delegates_list])
    static PlutusData identityDatum(byte[] owner, PlutusData... delegates) {
        return PlutusData.constr(0, PlutusData.bytes(owner), PlutusData.list(delegates));
    }

    @Nested
    class TransferOwnerTests {

        @Test
        void transferOwner_ownerSigns_passes() throws Exception {
            var compiled = compileValidator(CfIdentityValidator.class);
            var program = compiled.program();

            var datum = identityDatum(OWNER);
            // TransferOwner = Constr(0, [newOwner])
            var redeemer = PlutusData.constr(0, PlutusData.bytes(NEW_OWNER));

            var newStateDatum = identityDatum(NEW_OWNER); // same empty delegates

            var spentRef = TestDataBuilder.randomTxOutRef_typed();
            var spentInput = new TxInInfo(spentRef,
                    new TxOut(scriptAddress(),
                            Value.lovelace(VALUE),
                            new OutputDatum.OutputDatumInline(datum),
                            Optional.empty()));

            // Continuing output with same value and new datum
            var continuingOutput = new TxOut(scriptAddress(),
                    Value.lovelace(VALUE),
                    new OutputDatum.OutputDatumInline(newStateDatum),
                    Optional.empty());

            var ctx = spendingContext(spentRef, datum)
                    .redeemer(redeemer)
                    .input(spentInput)
                    .output(continuingOutput)
                    .signer(OWNER)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("transferOwner_ownerSigns_passes", result);
        }

        @Test
        void transferOwner_nonOwnerSigns_fails() throws Exception {
            var compiled = compileValidator(CfIdentityValidator.class);
            var program = compiled.program();

            var datum = identityDatum(OWNER);
            var redeemer = PlutusData.constr(0, PlutusData.bytes(NEW_OWNER));
            var newStateDatum = identityDatum(NEW_OWNER);

            var spentRef = TestDataBuilder.randomTxOutRef_typed();
            var spentInput = new TxInInfo(spentRef,
                    new TxOut(scriptAddress(),
                            Value.lovelace(VALUE),
                            new OutputDatum.OutputDatumInline(datum),
                            Optional.empty()));

            var continuingOutput = new TxOut(scriptAddress(),
                    Value.lovelace(VALUE),
                    new OutputDatum.OutputDatumInline(newStateDatum),
                    Optional.empty());

            var ctx = spendingContext(spentRef, datum)
                    .redeemer(redeemer)
                    .input(spentInput)
                    .output(continuingOutput)
                    .signer(OTHER) // not owner
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertFailure(result);
        }
    }

    @Nested
    class AddDelegateTests {

        @Test
        void addDelegate_ownerSigns_passes() throws Exception {
            var compiled = compileValidator(CfIdentityValidator.class);
            var program = compiled.program();

            var datum = identityDatum(OWNER); // no delegates
            // AddDelegate = Constr(1, [delegate, expires])
            var redeemer = PlutusData.constr(1, PlutusData.bytes(DELEGATE1), PlutusData.integer(EXPIRES));

            var newStateDatum = identityDatum(OWNER, delegate(DELEGATE1, EXPIRES));

            var spentRef = TestDataBuilder.randomTxOutRef_typed();
            var spentInput = new TxInInfo(spentRef,
                    new TxOut(scriptAddress(),
                            Value.lovelace(VALUE),
                            new OutputDatum.OutputDatumInline(datum),
                            Optional.empty()));

            var continuingOutput = new TxOut(scriptAddress(),
                    Value.lovelace(VALUE),
                    new OutputDatum.OutputDatumInline(newStateDatum),
                    Optional.empty());

            var ctx = spendingContext(spentRef, datum)
                    .redeemer(redeemer)
                    .input(spentInput)
                    .output(continuingOutput)
                    .signer(OWNER)
                    .validRange(Interval.before(EXPIRES)) // tx upper bound must be before delegate expires
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("addDelegate_ownerSigns_passes", result);
        }

        @Test
        void addDelegate_delegateIsOwner_fails() throws Exception {
            var compiled = compileValidator(CfIdentityValidator.class);
            var program = compiled.program();

            var datum = identityDatum(OWNER);
            // delegate == owner should fail
            var redeemer = PlutusData.constr(1, PlutusData.bytes(OWNER), PlutusData.integer(EXPIRES));
            var newStateDatum = identityDatum(OWNER, delegate(OWNER, EXPIRES));

            var spentRef = TestDataBuilder.randomTxOutRef_typed();
            var spentInput = new TxInInfo(spentRef,
                    new TxOut(scriptAddress(),
                            Value.lovelace(VALUE),
                            new OutputDatum.OutputDatumInline(datum),
                            Optional.empty()));

            var continuingOutput = new TxOut(scriptAddress(),
                    Value.lovelace(VALUE),
                    new OutputDatum.OutputDatumInline(newStateDatum),
                    Optional.empty());

            var ctx = spendingContext(spentRef, datum)
                    .redeemer(redeemer)
                    .input(spentInput)
                    .output(continuingOutput)
                    .signer(OWNER)
                    .validRange(Interval.before(EXPIRES))
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertFailure(result);
        }
    }

    @Nested
    class RemoveDelegateTests {

        @Test
        void removeDelegate_ownerSigns_passes() throws Exception {
            var compiled = compileValidator(CfIdentityValidator.class);
            var program = compiled.program();

            var datum = identityDatum(OWNER, delegate(DELEGATE1, EXPIRES));
            // RemoveDelegate = Constr(2, [delegate])
            var redeemer = PlutusData.constr(2, PlutusData.bytes(DELEGATE1));
            var newStateDatum = identityDatum(OWNER); // delegate removed

            var spentRef = TestDataBuilder.randomTxOutRef_typed();
            var spentInput = new TxInInfo(spentRef,
                    new TxOut(scriptAddress(),
                            Value.lovelace(VALUE),
                            new OutputDatum.OutputDatumInline(datum),
                            Optional.empty()));

            var continuingOutput = new TxOut(scriptAddress(),
                    Value.lovelace(VALUE),
                    new OutputDatum.OutputDatumInline(newStateDatum),
                    Optional.empty());

            var ctx = spendingContext(spentRef, datum)
                    .redeemer(redeemer)
                    .input(spentInput)
                    .output(continuingOutput)
                    .signer(OWNER)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("removeDelegate_ownerSigns_passes", result);
        }
    }

    private static void logBudget(String testName, com.bloxbean.cardano.julc.vm.EvalResult result) {
        var budget = result.budgetConsumed();
        System.out.println("[" + testName + "] CPU: " + budget.cpuSteps() + ", Mem: " + budget.memoryUnits());
    }
}
