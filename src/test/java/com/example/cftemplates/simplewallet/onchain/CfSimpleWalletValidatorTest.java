package com.example.cftemplates.simplewallet.onchain;

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

class CfSimpleWalletValidatorTest extends ContractTest {

    static final byte[] OWNER = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
            11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
            21, 22, 23, 24, 25, 26, 27, 28};
    static final byte[] OTHER = new byte[]{31, 32, 33, 34, 35, 36, 37, 38, 39, 40,
            41, 42, 43, 44, 45, 46, 47, 48, 49, 50,
            51, 52, 53, 54, 55, 56, 57, 58};
    static final byte[] RECIPIENT = new byte[]{61, 62, 63, 64, 65, 66, 67, 68, 69, 70,
            71, 72, 73, 74, 75, 76, 77, 78, 79, 80,
            81, 82, 83, 84, 85, 86, 87, 88};

    // Script hash for the wallet script address (= policyId in @MultiValidator)
    static final byte[] SCRIPT_HASH = new byte[]{(byte) 0xC1, (byte) 0xC2, (byte) 0xC3, 0, 0, 0, 0, 0, 0, 0,
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

    // PaymentIntent = Constr(0, [recipient, lovelaceAmt, data])
    // recipient is an Address PlutusData
    static PlutusData paymentIntentDatum(byte[] recipientPkh, BigInteger lovelaceAmt, byte[] data) {
        var recipientAddr = pubKeyAddress(recipientPkh).toPlutusData();
        return PlutusData.constr(0, recipientAddr, PlutusData.integer(lovelaceAmt), PlutusData.bytes(data));
    }

    @Nested
    class MintIntentTests {

        @Test
        void mintIntent_ownerSigns_passes() throws Exception {
            var compiled = compileValidator(CfSimpleWalletValidator.class);
            var program = compiled.program()
                    .applyParams(PlutusData.bytes(OWNER));

            // MintIntent = tag 0
            var redeemer = PlutusData.constr(0);

            var policyId = new PolicyId(SCRIPT_HASH);

            // 1 INTENT token minted
            var tokenName = new TokenName(new byte[]{0x49, 0x4E, 0x54}); // "INT"
            var mintValue = Value.singleton(policyId, tokenName, BigInteger.ONE);

            // PaymentIntent datum
            var intentDatum = paymentIntentDatum(RECIPIENT, BigInteger.valueOf(5_000_000), new byte[0]);

            // Output to own script address with intent token and datum
            var outputValue = Value.lovelace(BigInteger.valueOf(2_000_000))
                    .merge(Value.singleton(policyId, tokenName, BigInteger.ONE));
            var intentOutput = new TxOut(scriptAddress(),
                    outputValue,
                    new OutputDatum.OutputDatumInline(intentDatum),
                    Optional.empty());

            var ctx = mintingContext(policyId)
                    .redeemer(redeemer)
                    .mint(mintValue)
                    .output(intentOutput)
                    .signer(OWNER)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("mintIntent_ownerSigns_passes", result);
        }

        @Test
        void mintIntent_nonOwner_fails() throws Exception {
            var compiled = compileValidator(CfSimpleWalletValidator.class);
            var program = compiled.program()
                    .applyParams(PlutusData.bytes(OWNER));

            var redeemer = PlutusData.constr(0);
            var policyId = new PolicyId(SCRIPT_HASH);

            var tokenName = new TokenName(new byte[]{0x49, 0x4E, 0x54});
            var mintValue = Value.singleton(policyId, tokenName, BigInteger.ONE);

            var intentDatum = paymentIntentDatum(RECIPIENT, BigInteger.valueOf(5_000_000), new byte[0]);

            var outputValue = Value.lovelace(BigInteger.valueOf(2_000_000))
                    .merge(Value.singleton(policyId, tokenName, BigInteger.ONE));
            var intentOutput = new TxOut(scriptAddress(),
                    outputValue,
                    new OutputDatum.OutputDatumInline(intentDatum),
                    Optional.empty());

            var ctx = mintingContext(policyId)
                    .redeemer(redeemer)
                    .mint(mintValue)
                    .output(intentOutput)
                    .signer(OTHER) // not owner
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertFailure(result);
        }
    }

    @Nested
    class SpendIntentTests {

        @Test
        void spendIntent_ownerSigns_passes() throws Exception {
            var compiled = compileValidator(CfSimpleWalletValidator.class);
            var program = compiled.program()
                    .applyParams(PlutusData.bytes(OWNER));

            var datum = paymentIntentDatum(RECIPIENT, BigInteger.valueOf(5_000_000), new byte[0]);
            var redeemer = PlutusData.constr(0); // any redeemer, just needs owner signature

            var spentRef = TestDataBuilder.randomTxOutRef_typed();
            var spentInput = new TxInInfo(spentRef,
                    new TxOut(scriptAddress(),
                            Value.lovelace(BigInteger.valueOf(2_000_000)),
                            new OutputDatum.OutputDatumInline(datum),
                            Optional.empty()));

            var ctx = spendingContext(spentRef, datum)
                    .redeemer(redeemer)
                    .input(spentInput)
                    .signer(OWNER)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("spendIntent_ownerSigns_passes", result);
        }
    }

    @Nested
    class BurnIntentTests {

        @Test
        void burnIntent_ownerSigns_passes() throws Exception {
            var compiled = compileValidator(CfSimpleWalletValidator.class);
            var program = compiled.program()
                    .applyParams(PlutusData.bytes(OWNER));

            // BurnIntent = tag 1
            var redeemer = PlutusData.constr(1);
            var policyId = new PolicyId(SCRIPT_HASH);

            var tokenName = new TokenName(new byte[]{0x49, 0x4E, 0x54});
            // Burn: qty = -1
            var mintValue = Value.singleton(policyId, tokenName, BigInteger.ONE.negate());

            var ctx = mintingContext(policyId)
                    .redeemer(redeemer)
                    .mint(mintValue)
                    .signer(OWNER)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("burnIntent_ownerSigns_passes", result);
        }
    }

    private static void logBudget(String testName, com.bloxbean.cardano.julc.vm.EvalResult result) {
        var budget = result.budgetConsumed();
        System.out.println("[" + testName + "] CPU: " + budget.cpuSteps() + ", Mem: " + budget.memoryUnits());
    }
}
