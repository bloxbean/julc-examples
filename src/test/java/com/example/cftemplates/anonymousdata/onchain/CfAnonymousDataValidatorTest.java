package com.example.cftemplates.anonymousdata.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.stdlib.lib.CryptoLib;
import com.bloxbean.cardano.julc.testkit.ContractTest;
import com.bloxbean.cardano.julc.testkit.TestDataBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class CfAnonymousDataValidatorTest extends ContractTest {

    static final byte[] PKH = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
            11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
            21, 22, 23, 24, 25, 26, 27, 28};
    static final byte[] NONCE = new byte[]{0x42, 0x43, 0x44, 0x45};
    static final byte[] OTHER_PKH = new byte[]{31, 32, 33, 34, 35, 36, 37, 38, 39, 40,
            41, 42, 43, 44, 45, 46, 47, 48, 49, 50,
            51, 52, 53, 54, 55, 56, 57, 58};

    // Script hash for the anonymous data script address
    static final byte[] SCRIPT_HASH = new byte[]{(byte) 0xE1, (byte) 0xE2, (byte) 0xE3, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0};

    @BeforeAll
    static void setup() {
        initCrypto();
    }

    static byte[] computeId(byte[] pkh, byte[] nonce) {
        byte[] combined = new byte[pkh.length + nonce.length];
        System.arraycopy(pkh, 0, combined, 0, pkh.length);
        System.arraycopy(nonce, 0, combined, pkh.length, nonce.length);
        return CryptoLib.blake2b_256(combined);
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

    @Nested
    class MintTests {

        @Test
        void mint_correctId_passes() throws Exception {
            var compiled = compileValidator(CfAnonymousDataValidator.class);
            var program = compiled.program();

            byte[] id = computeId(PKH, NONCE);
            var redeemer = PlutusData.bytes(id);

            // Use SCRIPT_HASH as policyId so findOutputToScript/assetOf matches
            var policyId = new PolicyId(SCRIPT_HASH);

            // Build the minted value: 1 token with asset name = id under our policy
            var mintValue = Value.singleton(policyId, new TokenName(id), BigInteger.ONE);

            // Output to script address carrying the token with inline datum
            var outputValue = Value.lovelace(BigInteger.valueOf(2_000_000))
                    .merge(Value.singleton(policyId, new TokenName(id), BigInteger.ONE));
            var tokenOutput = new TxOut(scriptAddress(),
                    outputValue,
                    new OutputDatum.OutputDatumInline(PlutusData.constr(0)),
                    Optional.empty());

            var ctx = mintingContext(policyId)
                    .redeemer(redeemer)
                    .mint(mintValue)
                    .output(tokenOutput)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("mint_correctId_passes", result);
        }

        @Test
        void mint_wrongAssetName_fails() throws Exception {
            var compiled = compileValidator(CfAnonymousDataValidator.class);
            var program = compiled.program();

            byte[] id = computeId(PKH, NONCE);
            var redeemer = PlutusData.bytes(id);

            var policyId = new PolicyId(SCRIPT_HASH);

            // Mint with a DIFFERENT asset name than the redeemer id
            byte[] wrongId = computeId(OTHER_PKH, NONCE);
            var mintValue = Value.singleton(policyId, new TokenName(wrongId), BigInteger.ONE);

            var outputValue = Value.lovelace(BigInteger.valueOf(2_000_000))
                    .merge(Value.singleton(policyId, new TokenName(wrongId), BigInteger.ONE));
            var tokenOutput = new TxOut(scriptAddress(),
                    outputValue,
                    new OutputDatum.OutputDatumInline(PlutusData.constr(0)),
                    Optional.empty());

            var ctx = mintingContext(policyId)
                    .redeemer(redeemer)
                    .mint(mintValue)
                    .output(tokenOutput)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertFailure(result);
        }

        @Test
        void mint_wrongQuantity_fails() throws Exception {
            var compiled = compileValidator(CfAnonymousDataValidator.class);
            var program = compiled.program();

            byte[] id = computeId(PKH, NONCE);
            var redeemer = PlutusData.bytes(id);

            var policyId = new PolicyId(SCRIPT_HASH);

            // Mint 2 instead of 1 - should fail
            var mintValue = Value.singleton(policyId, new TokenName(id), BigInteger.TWO);

            var outputValue = Value.lovelace(BigInteger.valueOf(2_000_000))
                    .merge(Value.singleton(policyId, new TokenName(id), BigInteger.TWO));
            var tokenOutput = new TxOut(scriptAddress(),
                    outputValue,
                    new OutputDatum.OutputDatumInline(PlutusData.constr(0)),
                    Optional.empty());

            var ctx = mintingContext(policyId)
                    .redeemer(redeemer)
                    .mint(mintValue)
                    .output(tokenOutput)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertFailure(result);
        }
    }

    @Nested
    class SpendTests {

        @Test
        void spend_correctNonce_signerReconstructsId_passes() throws Exception {
            var compiled = compileValidator(CfAnonymousDataValidator.class);
            var program = compiled.program();

            byte[] id = computeId(PKH, NONCE);
            var datum = PlutusData.constr(0);
            var redeemer = PlutusData.bytes(NONCE);

            var policyId = new PolicyId(SCRIPT_HASH);

            // Build input with the token at script address
            var spentRef = TestDataBuilder.randomTxOutRef_typed();
            var inputValue = Value.lovelace(BigInteger.valueOf(2_000_000))
                    .merge(Value.singleton(policyId, new TokenName(id), BigInteger.ONE));
            var spentInput = new TxInInfo(spentRef,
                    new TxOut(scriptAddress(),
                            inputValue,
                            new OutputDatum.OutputDatumInline(datum),
                            Optional.empty()));

            var ctx = spendingContext(spentRef, datum)
                    .redeemer(redeemer)
                    .input(spentInput)
                    .signer(PKH)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("spend_correctNonce_signerReconstructsId_passes", result);
        }

        @Test
        void spend_wrongNonce_fails() throws Exception {
            var compiled = compileValidator(CfAnonymousDataValidator.class);
            var program = compiled.program();

            byte[] id = computeId(PKH, NONCE);
            var datum = PlutusData.constr(0);
            var redeemer = PlutusData.bytes(new byte[]{0x00, 0x01}); // wrong nonce

            var policyId = new PolicyId(SCRIPT_HASH);

            var spentRef = TestDataBuilder.randomTxOutRef_typed();
            var inputValue = Value.lovelace(BigInteger.valueOf(2_000_000))
                    .merge(Value.singleton(policyId, new TokenName(id), BigInteger.ONE));
            var spentInput = new TxInInfo(spentRef,
                    new TxOut(scriptAddress(),
                            inputValue,
                            new OutputDatum.OutputDatumInline(datum),
                            Optional.empty()));

            var ctx = spendingContext(spentRef, datum)
                    .redeemer(redeemer)
                    .input(spentInput)
                    .signer(PKH)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertFailure(result);
        }

        @Test
        void spend_missingIdToken_fails() throws Exception {
            var compiled = compileValidator(CfAnonymousDataValidator.class);
            var program = compiled.program();

            byte[] id = computeId(PKH, NONCE);
            var datum = PlutusData.constr(0);
            var redeemer = PlutusData.bytes(NONCE);

            var policyId = new PolicyId(SCRIPT_HASH);

            // Input has a DIFFERENT token (not the committed ID)
            byte[] differentId = computeId(OTHER_PKH, NONCE);
            var spentRef = TestDataBuilder.randomTxOutRef_typed();
            var inputValue = Value.lovelace(BigInteger.valueOf(2_000_000))
                    .merge(Value.singleton(policyId, new TokenName(differentId), BigInteger.ONE));
            var spentInput = new TxInInfo(spentRef,
                    new TxOut(scriptAddress(),
                            inputValue,
                            new OutputDatum.OutputDatumInline(datum),
                            Optional.empty()));

            var ctx = spendingContext(spentRef, datum)
                    .redeemer(redeemer)
                    .input(spentInput)
                    .signer(PKH)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertFailure(result);
        }

        @Test
        void spend_wrongSigner_fails() throws Exception {
            var compiled = compileValidator(CfAnonymousDataValidator.class);
            var program = compiled.program();

            byte[] id = computeId(PKH, NONCE);
            var datum = PlutusData.constr(0);
            var redeemer = PlutusData.bytes(NONCE);

            var policyId = new PolicyId(SCRIPT_HASH);

            var spentRef = TestDataBuilder.randomTxOutRef_typed();
            var inputValue = Value.lovelace(BigInteger.valueOf(2_000_000))
                    .merge(Value.singleton(policyId, new TokenName(id), BigInteger.ONE));
            var spentInput = new TxInInfo(spentRef,
                    new TxOut(scriptAddress(),
                            inputValue,
                            new OutputDatum.OutputDatumInline(datum),
                            Optional.empty()));

            var ctx = spendingContext(spentRef, datum)
                    .redeemer(redeemer)
                    .input(spentInput)
                    .signer(OTHER_PKH) // wrong signer
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
