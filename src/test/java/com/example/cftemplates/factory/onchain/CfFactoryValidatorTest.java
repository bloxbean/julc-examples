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

class CfFactoryValidatorTest extends ContractTest {

    static final byte[] OWNER = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
            11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
            21, 22, 23, 24, 25, 26, 27, 28};
    static final byte[] OTHER = new byte[]{31, 32, 33, 34, 35, 36, 37, 38, 39, 40,
            41, 42, 43, 44, 45, 46, 47, 48, 49, 50,
            51, 52, 53, 54, 55, 56, 57, 58};

    static final byte[] SEED_TX_HASH = new byte[]{10, 20, 30, 40, 50, 60, 70, 80, 90, 100,
            110, 120, 1, 2, 3, 4, 5, 6, 7, 8,
            9, 11, 12, 13, 14, 15, 16, 17, 18, 19,
            21, 22};
    static final BigInteger SEED_INDEX = BigInteger.ZERO;

    // Script hash for the factory script address (= policyId for minting)
    static final byte[] SCRIPT_HASH = new byte[]{(byte) 0xF1, (byte) 0xF2, (byte) 0xF3, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0};

    // A product policy ID for testing
    static final byte[] PRODUCT_POLICY = new byte[]{(byte) 0xA1, (byte) 0xA2, (byte) 0xA3, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0};

    static final byte[] PRODUCT_ID = new byte[]{0x50, 0x52, 0x4F, 0x44}; // "PROD"
    static final byte[] MARKER_NAME = "FACTORY_MARKER".getBytes();

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

    // FactoryDatum = Constr(0, [ListData(products...)])
    static PlutusData factoryDatum(byte[]... products) {
        PlutusData[] productBytes = new PlutusData[products.length];
        for (int i = 0; i < products.length; i++) {
            productBytes[i] = PlutusData.bytes(products[i]);
        }
        return PlutusData.constr(0, PlutusData.list(productBytes));
    }

    // CreateProduct redeemer = Constr(0, [BData(productPolicyId), BData(productId)])
    static PlutusData createProductRedeemer(byte[] productPolicyId, byte[] productId) {
        return PlutusData.constr(0, PlutusData.bytes(productPolicyId), PlutusData.bytes(productId));
    }

    @Nested
    class MintTests {

        @Test
        void mint_seedConsumed_oneMinted_ownerSigns_passes() throws Exception {
            var compiled = compileValidator(CfFactoryValidator.class);
            var program = compiled.program()
                    .applyParams(
                            PlutusData.bytes(OWNER),
                            PlutusData.bytes(SEED_TX_HASH),
                            PlutusData.integer(SEED_INDEX));

            var redeemer = PlutusData.constr(0); // generic PlutusData redeemer for mint

            var policyId = new PolicyId(SCRIPT_HASH);

            // Seed UTxO input
            var seedRef = new TxOutRef(new TxId(SEED_TX_HASH), SEED_INDEX);
            var seedInput = new TxInInfo(seedRef,
                    new TxOut(pubKeyAddress(OWNER),
                            Value.lovelace(BigInteger.valueOf(5_000_000)),
                            new OutputDatum.NoOutputDatum(),
                            Optional.empty()));

            // Mint exactly 1 token under our policy
            var mintValue = Value.singleton(policyId, new TokenName(MARKER_NAME), BigInteger.ONE);

            var ctx = mintingContext(policyId)
                    .redeemer(redeemer)
                    .input(seedInput)
                    .mint(mintValue)
                    .signer(OWNER)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("mint_seedConsumed_oneMinted_ownerSigns_passes", result);
        }

        @Test
        void mint_noSeedConsumed_fails() throws Exception {
            var compiled = compileValidator(CfFactoryValidator.class);
            var program = compiled.program()
                    .applyParams(
                            PlutusData.bytes(OWNER),
                            PlutusData.bytes(SEED_TX_HASH),
                            PlutusData.integer(SEED_INDEX));

            var redeemer = PlutusData.constr(0);
            var policyId = new PolicyId(SCRIPT_HASH);

            // Input with a DIFFERENT tx hash (not the seed)
            var otherRef = TestDataBuilder.randomTxOutRef_typed();
            var otherInput = new TxInInfo(otherRef,
                    new TxOut(pubKeyAddress(OWNER),
                            Value.lovelace(BigInteger.valueOf(5_000_000)),
                            new OutputDatum.NoOutputDatum(),
                            Optional.empty()));

            var mintValue = Value.singleton(policyId, new TokenName(MARKER_NAME), BigInteger.ONE);

            var ctx = mintingContext(policyId)
                    .redeemer(redeemer)
                    .input(otherInput)
                    .mint(mintValue)
                    .signer(OWNER)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertFailure(result);
        }
    }

    @Nested
    class SpendCreateProductTests {

        @Test
        void spend_createProduct_valid_passes() throws Exception {
            var compiled = compileValidator(CfFactoryValidator.class);
            var program = compiled.program()
                    .applyParams(
                            PlutusData.bytes(OWNER),
                            PlutusData.bytes(SEED_TX_HASH),
                            PlutusData.integer(SEED_INDEX));

            // Old datum: empty products list
            var oldDatum = factoryDatum();
            // CreateProduct redeemer = Constr(0, [productPolicyId, productId])
            var redeemer = createProductRedeemer(PRODUCT_POLICY, PRODUCT_ID);

            // Spent input: script address with marker token
            var spentRef = TestDataBuilder.randomTxOutRef_typed();
            var inputValue = Value.lovelace(BigInteger.valueOf(5_000_000))
                    .merge(Value.singleton(new PolicyId(SCRIPT_HASH),
                            new TokenName(MARKER_NAME), BigInteger.ONE));
            var spentInput = new TxInInfo(spentRef,
                    new TxOut(scriptAddress(),
                            inputValue,
                            new OutputDatum.OutputDatumInline(oldDatum),
                            Optional.empty()));

            // Continuing output: same script address with marker token, updated datum
            var newDatum = factoryDatum(PRODUCT_POLICY);
            var outputValue = Value.lovelace(BigInteger.valueOf(5_000_000))
                    .merge(Value.singleton(new PolicyId(SCRIPT_HASH),
                            new TokenName(MARKER_NAME), BigInteger.ONE));
            var continuingOutput = new TxOut(scriptAddress(),
                    outputValue,
                    new OutputDatum.OutputDatumInline(newDatum),
                    Optional.empty());

            // Product mint value
            var mintValue = Value.singleton(new PolicyId(PRODUCT_POLICY),
                    new TokenName(PRODUCT_ID), BigInteger.ONE);

            var ctx = spendingContext(spentRef, oldDatum)
                    .redeemer(redeemer)
                    .input(spentInput)
                    .output(continuingOutput)
                    .mint(mintValue)
                    .signer(OWNER)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("spend_createProduct_valid_passes", result);
        }

        @Test
        void spend_createProduct_noMarkerInInput_fails() throws Exception {
            var compiled = compileValidator(CfFactoryValidator.class);
            var program = compiled.program()
                    .applyParams(
                            PlutusData.bytes(OWNER),
                            PlutusData.bytes(SEED_TX_HASH),
                            PlutusData.integer(SEED_INDEX));

            var oldDatum = factoryDatum();
            var redeemer = createProductRedeemer(PRODUCT_POLICY, PRODUCT_ID);

            // Spent input WITHOUT marker token (just lovelace)
            var spentRef = TestDataBuilder.randomTxOutRef_typed();
            var inputValue = Value.lovelace(BigInteger.valueOf(5_000_000));
            var spentInput = new TxInInfo(spentRef,
                    new TxOut(scriptAddress(),
                            inputValue,
                            new OutputDatum.OutputDatumInline(oldDatum),
                            Optional.empty()));

            // Continuing output still has marker (inconsistent, but testing input check)
            var newDatum = factoryDatum(PRODUCT_POLICY);
            var outputValue = Value.lovelace(BigInteger.valueOf(5_000_000))
                    .merge(Value.singleton(new PolicyId(SCRIPT_HASH),
                            new TokenName(MARKER_NAME), BigInteger.ONE));
            var continuingOutput = new TxOut(scriptAddress(),
                    outputValue,
                    new OutputDatum.OutputDatumInline(newDatum),
                    Optional.empty());

            var mintValue = Value.singleton(new PolicyId(PRODUCT_POLICY),
                    new TokenName(PRODUCT_ID), BigInteger.ONE);

            var ctx = spendingContext(spentRef, oldDatum)
                    .redeemer(redeemer)
                    .input(spentInput)
                    .output(continuingOutput)
                    .mint(mintValue)
                    .signer(OWNER)
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
