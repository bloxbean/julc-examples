package com.example.cftemplates.factory.onchain;

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

class CfProductValidatorTest extends ContractTest {

    static final byte[] OWNER = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
            11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
            21, 22, 23, 24, 25, 26, 27, 28};
    static final byte[] OTHER = new byte[]{31, 32, 33, 34, 35, 36, 37, 38, 39, 40,
            41, 42, 43, 44, 45, 46, 47, 48, 49, 50,
            51, 52, 53, 54, 55, 56, 57, 58};

    // Factory marker policy (28 bytes)
    static final byte[] FACTORY_MARKER_POLICY = new byte[]{(byte) 0xF1, (byte) 0xF2, (byte) 0xF3, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0};

    static final byte[] PRODUCT_ID = new byte[]{0x50, 0x52, 0x4F, 0x44}; // "PROD"
    static final byte[] FACTORY_MARKER_NAME = new byte[]{0x4D, 0x41, 0x52, 0x4B}; // "MARK"

    // Product script hash (= product policy for minting)
    static final byte[] PRODUCT_SCRIPT_HASH = new byte[]{(byte) 0xA1, (byte) 0xA2, (byte) 0xA3, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0};

    @BeforeAll
    static void setup() {
        initCrypto();
    }

    static Address scriptAddress(byte[] hash) {
        return new Address(
                new Credential.ScriptCredential(new ScriptHash(hash)),
                Optional.empty());
    }

    static Address pubKeyAddress(byte[] pkh) {
        return new Address(
                new Credential.PubKeyCredential(new PubKeyHash(pkh)),
                Optional.empty());
    }

    // ProductDatum = Constr(0, [BData(tag)])
    static PlutusData productDatum(byte[] tag) {
        return PlutusData.constr(0, PlutusData.bytes(tag));
    }

    @Nested
    class MintTests {

        @Test
        void mint_factoryMarkerSpent_productMinted_ownerSigns_passes() throws Exception {
            var compiled = compileValidator(CfProductValidator.class);
            var program = compiled.program()
                    .applyParams(
                            PlutusData.bytes(OWNER),
                            PlutusData.bytes(FACTORY_MARKER_POLICY),
                            PlutusData.bytes(PRODUCT_ID));

            var redeemer = PlutusData.constr(0); // generic PlutusData redeemer for mint

            var policyId = new PolicyId(PRODUCT_SCRIPT_HASH);

            // Input that contains the factory marker token
            var factoryRef = TestDataBuilder.randomTxOutRef_typed();
            var factoryInputValue = Value.lovelace(BigInteger.valueOf(5_000_000))
                    .merge(Value.singleton(new PolicyId(FACTORY_MARKER_POLICY),
                            new TokenName(FACTORY_MARKER_NAME), BigInteger.ONE));
            var factoryInput = new TxInInfo(factoryRef,
                    new TxOut(scriptAddress(FACTORY_MARKER_POLICY),
                            factoryInputValue,
                            new OutputDatum.OutputDatumInline(PlutusData.constr(0, PlutusData.list())),
                            Optional.empty()));

            // Mint exactly 1 product token
            var mintValue = Value.singleton(policyId, new TokenName(PRODUCT_ID), BigInteger.ONE);

            // Output to product script address with ProductDatum
            var datum = productDatum(new byte[]{0x01});
            var outputValue = Value.lovelace(BigInteger.valueOf(2_000_000))
                    .merge(Value.singleton(policyId, new TokenName(PRODUCT_ID), BigInteger.ONE));
            var productOutput = new TxOut(scriptAddress(PRODUCT_SCRIPT_HASH),
                    outputValue,
                    new OutputDatum.OutputDatumInline(datum),
                    Optional.empty());

            var ctx = mintingContext(policyId)
                    .redeemer(redeemer)
                    .input(factoryInput)
                    .mint(mintValue)
                    .output(productOutput)
                    .signer(OWNER)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("mint_factoryMarkerSpent_productMinted_ownerSigns_passes", result);
        }

        @Test
        void mint_noFactoryMarkerInInput_fails() throws Exception {
            var compiled = compileValidator(CfProductValidator.class);
            var program = compiled.program()
                    .applyParams(
                            PlutusData.bytes(OWNER),
                            PlutusData.bytes(FACTORY_MARKER_POLICY),
                            PlutusData.bytes(PRODUCT_ID));

            var redeemer = PlutusData.constr(0);
            var policyId = new PolicyId(PRODUCT_SCRIPT_HASH);

            // Input WITHOUT factory marker token (just lovelace)
            var normalRef = TestDataBuilder.randomTxOutRef_typed();
            var normalInput = new TxInInfo(normalRef,
                    new TxOut(pubKeyAddress(OWNER),
                            Value.lovelace(BigInteger.valueOf(5_000_000)),
                            new OutputDatum.NoOutputDatum(),
                            Optional.empty()));

            var mintValue = Value.singleton(policyId, new TokenName(PRODUCT_ID), BigInteger.ONE);

            var datum = productDatum(new byte[]{0x01});
            var outputValue = Value.lovelace(BigInteger.valueOf(2_000_000))
                    .merge(Value.singleton(policyId, new TokenName(PRODUCT_ID), BigInteger.ONE));
            var productOutput = new TxOut(scriptAddress(PRODUCT_SCRIPT_HASH),
                    outputValue,
                    new OutputDatum.OutputDatumInline(datum),
                    Optional.empty());

            var ctx = mintingContext(policyId)
                    .redeemer(redeemer)
                    .input(normalInput)
                    .mint(mintValue)
                    .output(productOutput)
                    .signer(OWNER)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertFailure(result);
        }
    }

    @Nested
    class SpendTests {

        @Test
        void spend_ownerSigns_passes() throws Exception {
            var compiled = compileValidator(CfProductValidator.class);
            var program = compiled.program()
                    .applyParams(
                            PlutusData.bytes(OWNER),
                            PlutusData.bytes(FACTORY_MARKER_POLICY),
                            PlutusData.bytes(PRODUCT_ID));

            var datum = productDatum(new byte[]{0x01});
            var redeemer = PlutusData.constr(0);

            var spentRef = TestDataBuilder.randomTxOutRef_typed();
            var inputValue = Value.lovelace(BigInteger.valueOf(2_000_000))
                    .merge(Value.singleton(new PolicyId(PRODUCT_SCRIPT_HASH),
                            new TokenName(PRODUCT_ID), BigInteger.ONE));
            var spentInput = new TxInInfo(spentRef,
                    new TxOut(scriptAddress(PRODUCT_SCRIPT_HASH),
                            inputValue,
                            new OutputDatum.OutputDatumInline(datum),
                            Optional.empty()));

            var ctx = spendingContext(spentRef, datum)
                    .redeemer(redeemer)
                    .input(spentInput)
                    .signer(OWNER)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("spend_ownerSigns_passes", result);
        }

        @Test
        void spend_nonOwnerSigns_fails() throws Exception {
            var compiled = compileValidator(CfProductValidator.class);
            var program = compiled.program()
                    .applyParams(
                            PlutusData.bytes(OWNER),
                            PlutusData.bytes(FACTORY_MARKER_POLICY),
                            PlutusData.bytes(PRODUCT_ID));

            var datum = productDatum(new byte[]{0x01});
            var redeemer = PlutusData.constr(0);

            var spentRef = TestDataBuilder.randomTxOutRef_typed();
            var inputValue = Value.lovelace(BigInteger.valueOf(2_000_000))
                    .merge(Value.singleton(new PolicyId(PRODUCT_SCRIPT_HASH),
                            new TokenName(PRODUCT_ID), BigInteger.ONE));
            var spentInput = new TxInInfo(spentRef,
                    new TxOut(scriptAddress(PRODUCT_SCRIPT_HASH),
                            inputValue,
                            new OutputDatum.OutputDatumInline(datum),
                            Optional.empty()));

            var ctx = spendingContext(spentRef, datum)
                    .redeemer(redeemer)
                    .input(spentInput)
                    .signer(OTHER) // wrong signer
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
