package com.example.cftemplates.upgradeableproxy.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.testkit.ContractTest;
import com.bloxbean.cardano.julc.testkit.TestDataBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class CfProxyValidatorTest extends ContractTest {

    static final byte[] OWNER = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
            11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
            21, 22, 23, 24, 25, 26, 27, 28};
    static final byte[] NEW_OWNER = new byte[]{31, 32, 33, 34, 35, 36, 37, 38, 39, 40,
            41, 42, 43, 44, 45, 46, 47, 48, 49, 50,
            51, 52, 53, 54, 55, 56, 57, 58};
    static final byte[] OTHER = new byte[]{61, 62, 63, 64, 65, 66, 67, 68, 69, 70,
            71, 72, 73, 74, 75, 76, 77, 78, 79, 80,
            81, 82, 83, 84, 85, 86, 87, 88};

    static final byte[] SEED_TX_HASH = new byte[]{10, 20, 30, 40, 50, 60, 70, 80, 90, 100,
            110, 120, 1, 2, 3, 4, 5, 6, 7, 8,
            9, 11, 12, 13, 14, 15, 16, 17, 18, 19,
            21, 22};
    static final BigInteger SEED_INDEX = BigInteger.ZERO;

    // Script hash for the proxy script address (= policyId for minting)
    static final byte[] SCRIPT_HASH = new byte[]{(byte) 0xC1, (byte) 0xC2, (byte) 0xC3, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0};

    // Script pointer for ProxyDatum (a script hash that delegation withdraws from)
    static final byte[] SCRIPT_POINTER = new byte[]{(byte) 0xD1, (byte) 0xD2, (byte) 0xD3, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0};

    static byte[] STATE_TOKEN_NAME;

    @BeforeAll
    static void setup() throws Exception {
        initCrypto();
        STATE_TOKEN_NAME = computeStateTokenNameJava(SEED_TX_HASH, SEED_INDEX);
    }

    /**
     * Replicates CfProxyValidator.computeStateTokenName() in Java:
     * sha3_256(seedTxHash || intToDecimalString(seedIndex))
     */
    static byte[] computeStateTokenNameJava(byte[] seedTxHash, BigInteger seedIndex) throws Exception {
        // intToDecimalString: BigInteger -> decimal string bytes (e.g., 0 -> "0")
        byte[] indexStr = seedIndex.toString().getBytes(StandardCharsets.UTF_8);
        byte[] combined = new byte[seedTxHash.length + indexStr.length];
        System.arraycopy(seedTxHash, 0, combined, 0, seedTxHash.length);
        System.arraycopy(indexStr, 0, combined, seedTxHash.length, indexStr.length);
        return MessageDigest.getInstance("SHA3-256").digest(combined);
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

    // ProxyDatum = Constr(0, [BData(scriptPointer), BData(scriptOwner)])
    static PlutusData proxyDatum(byte[] scriptPointer, byte[] scriptOwner) {
        return PlutusData.constr(0, PlutusData.bytes(scriptPointer), PlutusData.bytes(scriptOwner));
    }

    @Nested
    class MintInitTests {

        @Test
        void init_seedConsumed_stateTokenMinted_ownerSigns_passes() throws Exception {
            var compiled = compileValidator(CfProxyValidator.class);
            var program = compiled.program()
                    .applyParams(
                            PlutusData.bytes(SEED_TX_HASH),
                            PlutusData.integer(SEED_INDEX));

            // Init = tag 0 in ProxyMintAction sealed interface
            var redeemer = PlutusData.constr(0);

            var policyId = new PolicyId(SCRIPT_HASH);

            // Seed UTxO input
            var seedRef = new TxOutRef(new TxId(SEED_TX_HASH), SEED_INDEX);
            var seedInput = new TxInInfo(seedRef,
                    new TxOut(pubKeyAddress(OWNER),
                            Value.lovelace(BigInteger.valueOf(5_000_000)),
                            new OutputDatum.NoOutputDatum(),
                            Optional.empty()));

            // Mint exactly 1 state token
            var mintValue = Value.singleton(policyId, new TokenName(STATE_TOKEN_NAME), BigInteger.ONE);

            // Output to own script address with state token and ProxyDatum
            var datum = proxyDatum(SCRIPT_POINTER, OWNER);
            var outputValue = Value.lovelace(BigInteger.valueOf(2_000_000))
                    .merge(Value.singleton(policyId, new TokenName(STATE_TOKEN_NAME), BigInteger.ONE));
            var stateOutput = new TxOut(scriptAddress(),
                    outputValue,
                    new OutputDatum.OutputDatumInline(datum),
                    Optional.empty());

            var ctx = mintingContext(policyId)
                    .redeemer(redeemer)
                    .input(seedInput)
                    .mint(mintValue)
                    .output(stateOutput)
                    .signer(OWNER)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("init_seedConsumed_stateTokenMinted_ownerSigns_passes", result);
        }

        @Test
        void init_noSeedConsumed_fails() throws Exception {
            var compiled = compileValidator(CfProxyValidator.class);
            var program = compiled.program()
                    .applyParams(
                            PlutusData.bytes(SEED_TX_HASH),
                            PlutusData.integer(SEED_INDEX));

            var redeemer = PlutusData.constr(0); // Init
            var policyId = new PolicyId(SCRIPT_HASH);

            // Input with a DIFFERENT tx hash (not the seed)
            var otherRef = TestDataBuilder.randomTxOutRef_typed();
            var otherInput = new TxInInfo(otherRef,
                    new TxOut(pubKeyAddress(OWNER),
                            Value.lovelace(BigInteger.valueOf(5_000_000)),
                            new OutputDatum.NoOutputDatum(),
                            Optional.empty()));

            var mintValue = Value.singleton(policyId, new TokenName(STATE_TOKEN_NAME), BigInteger.ONE);

            var datum = proxyDatum(SCRIPT_POINTER, OWNER);
            var outputValue = Value.lovelace(BigInteger.valueOf(2_000_000))
                    .merge(Value.singleton(policyId, new TokenName(STATE_TOKEN_NAME), BigInteger.ONE));
            var stateOutput = new TxOut(scriptAddress(),
                    outputValue,
                    new OutputDatum.OutputDatumInline(datum),
                    Optional.empty());

            var ctx = mintingContext(policyId)
                    .redeemer(redeemer)
                    .input(otherInput)
                    .mint(mintValue)
                    .output(stateOutput)
                    .signer(OWNER)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertFailure(result);
        }
    }

    @Nested
    class SpendUpdateTests {

        @Test
        void update_stateTokenInInput_oldOwnerSigns_passes() throws Exception {
            var compiled = compileValidator(CfProxyValidator.class);
            var program = compiled.program()
                    .applyParams(
                            PlutusData.bytes(SEED_TX_HASH),
                            PlutusData.integer(SEED_INDEX));

            // Update = tag 0 in ProxySpendAction sealed interface
            var redeemer = PlutusData.constr(0);

            var policyId = new PolicyId(SCRIPT_HASH);
            var oldDatum = proxyDatum(SCRIPT_POINTER, OWNER);

            // Spent input: script address with state token
            var spentRef = TestDataBuilder.randomTxOutRef_typed();
            var inputValue = Value.lovelace(BigInteger.valueOf(2_000_000))
                    .merge(Value.singleton(policyId, new TokenName(STATE_TOKEN_NAME), BigInteger.ONE));
            var spentInput = new TxInInfo(spentRef,
                    new TxOut(scriptAddress(),
                            inputValue,
                            new OutputDatum.OutputDatumInline(oldDatum),
                            Optional.empty()));

            // Continuing output with updated datum (new script pointer, same owner)
            byte[] newPointer = new byte[]{(byte) 0xE1, (byte) 0xE2, (byte) 0xE3, 0, 0, 0, 0, 0, 0, 0,
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    0, 0, 0, 0, 0, 0, 0, 0};
            var newDatum = proxyDatum(newPointer, OWNER);
            var outputValue = Value.lovelace(BigInteger.valueOf(2_000_000))
                    .merge(Value.singleton(policyId, new TokenName(STATE_TOKEN_NAME), BigInteger.ONE));
            var continuingOutput = new TxOut(scriptAddress(),
                    outputValue,
                    new OutputDatum.OutputDatumInline(newDatum),
                    Optional.empty());

            var ctx = spendingContext(spentRef, oldDatum)
                    .redeemer(redeemer)
                    .input(spentInput)
                    .output(continuingOutput)
                    .signer(OWNER)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("update_stateTokenInInput_oldOwnerSigns_passes", result);
        }

        @Test
        void update_nonOwnerSigns_fails() throws Exception {
            var compiled = compileValidator(CfProxyValidator.class);
            var program = compiled.program()
                    .applyParams(
                            PlutusData.bytes(SEED_TX_HASH),
                            PlutusData.integer(SEED_INDEX));

            var redeemer = PlutusData.constr(0); // Update
            var policyId = new PolicyId(SCRIPT_HASH);
            var oldDatum = proxyDatum(SCRIPT_POINTER, OWNER);

            // Spent input: script address with state token
            var spentRef = TestDataBuilder.randomTxOutRef_typed();
            var inputValue = Value.lovelace(BigInteger.valueOf(2_000_000))
                    .merge(Value.singleton(policyId, new TokenName(STATE_TOKEN_NAME), BigInteger.ONE));
            var spentInput = new TxInInfo(spentRef,
                    new TxOut(scriptAddress(),
                            inputValue,
                            new OutputDatum.OutputDatumInline(oldDatum),
                            Optional.empty()));

            // Continuing output with same datum (no change)
            var newDatum = proxyDatum(SCRIPT_POINTER, OWNER);
            var outputValue = Value.lovelace(BigInteger.valueOf(2_000_000))
                    .merge(Value.singleton(policyId, new TokenName(STATE_TOKEN_NAME), BigInteger.ONE));
            var continuingOutput = new TxOut(scriptAddress(),
                    outputValue,
                    new OutputDatum.OutputDatumInline(newDatum),
                    Optional.empty());

            // Signed by OTHER, not OWNER
            var ctx = spendingContext(spentRef, oldDatum)
                    .redeemer(redeemer)
                    .input(spentInput)
                    .output(continuingOutput)
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
