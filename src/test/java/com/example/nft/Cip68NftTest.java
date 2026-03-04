package com.example.nft;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.core.types.JulcMap;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.testkit.*;
import com.bloxbean.cardano.julc.testkit.JulcEval;
import com.example.nft.onchain.Cip68Nft;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for Cip68Nft — @MultiValidator with MINT + SPEND, two sealed interfaces,
 * HOF lambdas, OutputDatum/Credential switches, @Param.
 */
class Cip68NftTest extends ContractTest {

    // Pre-computed CIP-68 token names
    static final byte[] REF_TOKEN_NAME = new byte[]{0, 6, 67, -80, 77, 121, 78, 70, 84}; // 000643b0 ++ "MyNFT"
    static final byte[] USER_TOKEN_NAME = new byte[]{0, 13, -31, 64, 77, 121, 78, 70, 84}; // 000de140 ++ "MyNFT"

    static final byte[] SCRIPT_HASH = new byte[]{10, 20, 30, 40, 50, 60, 70, 80, 90, 100,
            110, 120, 11, 21, 15, 25, 35, 45, 55, 65,
            75, 85, 95, 105, 115, 125, 12, 22};
    static final byte[] MINTER_PKH = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
            11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
            21, 22, 23, 24, 25, 26, 27, 28};

    @BeforeAll
    static void setup() {
        initCrypto();
    }

    static Address scriptAddress() {
        return new Address(
                new Credential.ScriptCredential(new ScriptHash(SCRIPT_HASH)),
                Optional.empty());
    }

    static Address minterAddress() {
        return new Address(
                new Credential.PubKeyCredential(new PubKeyHash(MINTER_PKH)),
                Optional.empty());
    }

    static PolicyId policyId() {
        return new PolicyId(SCRIPT_HASH);
    }

    static PlutusData metadataDatum() {
        // NftMetadata = Constr(0, [Constr(0, []), IData(1)])
        return PlutusData.constr(0, PlutusData.constr(0), PlutusData.integer(1));
    }

    // ---- Mode B: UPLC compilation tests ----

    @Nested
    class UplcTests {

        private com.bloxbean.cardano.julc.compiler.CompileResult compileWithParams() {
            var result = compileValidator(Cip68Nft.class);
            assertFalse(result.hasErrors(), "Should compile: " + result);
            return result;
        }

        @Test
        void compilesSuccessfully() {
            var result = compileWithParams();
            assertNotNull(result.program());
            System.out.println("Cip68Nft script size: " + result.scriptSizeFormatted());
        }

        @Test
        void mint_evaluatesSuccess() throws Exception {
            var program = compileWithParams().program().applyParams(
                    PlutusData.bytes(REF_TOKEN_NAME),
                    PlutusData.bytes(USER_TOKEN_NAME));

            var redeemer = PlutusData.constr(0); // MintNft

            var refOutputValue = Value.lovelace(BigInteger.valueOf(2_000_000))
                    .merge(Value.singleton(policyId(), new TokenName(REF_TOKEN_NAME), BigInteger.ONE));
            var refOutput = new TxOut(scriptAddress(), refOutputValue,
                    new OutputDatum.OutputDatumInline(metadataDatum()),
                    Optional.empty());

            var mintVal = Value.singleton(policyId(), new TokenName(REF_TOKEN_NAME), BigInteger.ONE)
                    .merge(Value.singleton(policyId(), new TokenName(USER_TOKEN_NAME), BigInteger.ONE));

            var ctx = mintingContext(policyId())
                    .redeemer(redeemer)
                    .output(refOutput)
                    .mint(mintVal)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            logBudget("mint_evaluatesSuccess", result);
            assertSuccess(result);
        }

        @Test
        void mint_rejectsIncomplete() throws Exception {
            var program = compileWithParams().program().applyParams(
                    PlutusData.bytes(REF_TOKEN_NAME),
                    PlutusData.bytes(USER_TOKEN_NAME));

            var redeemer = PlutusData.constr(0); // MintNft

            // Only user token minted, no ref
            var mintVal = Value.singleton(policyId(), new TokenName(USER_TOKEN_NAME), BigInteger.ONE);

            var ctx = mintingContext(policyId())
                    .redeemer(redeemer)
                    .mint(mintVal)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertFailure(result);
            logBudget("mint_rejectsIncomplete", result);
        }

        @Test
        void burn_evaluatesSuccess() throws Exception {
            var program = compileWithParams().program().applyParams(
                    PlutusData.bytes(REF_TOKEN_NAME),
                    PlutusData.bytes(USER_TOKEN_NAME));

            var redeemer = PlutusData.constr(1); // BurnNft
            var negOne = BigInteger.ZERO.subtract(BigInteger.ONE);

            var mintVal = Value.singleton(policyId(), new TokenName(REF_TOKEN_NAME), negOne)
                    .merge(Value.singleton(policyId(), new TokenName(USER_TOKEN_NAME), negOne));

            var ctx = mintingContext(policyId())
                    .redeemer(redeemer)
                    .mint(mintVal)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("burn_evaluatesSuccess", result);
        }

        @Test
        void spend_updateMetadata_evaluatesSuccess() throws Exception {
            var program = compileWithParams().program().applyParams(
                    PlutusData.bytes(REF_TOKEN_NAME),
                    PlutusData.bytes(USER_TOKEN_NAME));

            var redeemer = PlutusData.constr(0); // UpdateMetadata
            var datum = metadataDatum();

            var spentRef = TestDataBuilder.randomTxOutRef_typed();
            var spentOutput = new TxOut(scriptAddress(),
                    Value.lovelace(BigInteger.valueOf(2_000_000))
                            .merge(Value.singleton(policyId(), new TokenName(REF_TOKEN_NAME), BigInteger.ONE)),
                    new OutputDatum.OutputDatumInline(datum), Optional.empty());
            var spentInput = new TxInInfo(spentRef, spentOutput);

            // Continuing output with updated metadata
            var newDatum = PlutusData.constr(0, PlutusData.constr(0), PlutusData.integer(2));
            var continuingOutput = new TxOut(scriptAddress(),
                    Value.lovelace(BigInteger.valueOf(2_000_000))
                            .merge(Value.singleton(policyId(), new TokenName(REF_TOKEN_NAME), BigInteger.ONE)),
                    new OutputDatum.OutputDatumInline(newDatum), Optional.empty());

            var ctx = spendingContext(spentRef, datum)
                    .redeemer(redeemer)
                    .input(spentInput)
                    .output(continuingOutput)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            logBudget("spend_updateMetadata_evaluatesSuccess", result);
            assertSuccess(result);
        }

        @Test
        void spend_burnReference_evaluatesSuccess() throws Exception {
            var program = compileWithParams().program().applyParams(
                    PlutusData.bytes(REF_TOKEN_NAME),
                    PlutusData.bytes(USER_TOKEN_NAME));

            var redeemer = PlutusData.constr(1); // BurnReference
            var datum = metadataDatum();
            var negOne = BigInteger.ZERO.subtract(BigInteger.ONE);

            var spentRef = TestDataBuilder.randomTxOutRef_typed();
            var spentOutput = new TxOut(scriptAddress(),
                    Value.lovelace(BigInteger.valueOf(2_000_000))
                            .merge(Value.singleton(policyId(), new TokenName(REF_TOKEN_NAME), BigInteger.ONE)),
                    new OutputDatum.OutputDatumInline(datum), Optional.empty());
            var spentInput = new TxInInfo(spentRef, spentOutput);

            var mintVal = Value.singleton(policyId(), new TokenName(REF_TOKEN_NAME), negOne);

            var ctx = spendingContext(spentRef, datum)
                    .redeemer(redeemer)
                    .input(spentInput)
                    .mint(mintVal)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("spend_burnReference_evaluatesSuccess", result);
        }

        @Test
        void tracesAppear() throws Exception {
            var program = compileWithParams().program().applyParams(
                    PlutusData.bytes(REF_TOKEN_NAME),
                    PlutusData.bytes(USER_TOKEN_NAME));

            var redeemer = PlutusData.constr(0); // MintNft

            var refOutputValue = Value.lovelace(BigInteger.valueOf(2_000_000))
                    .merge(Value.singleton(policyId(), new TokenName(REF_TOKEN_NAME), BigInteger.ONE));
            var refOutput = new TxOut(scriptAddress(), refOutputValue,
                    new OutputDatum.OutputDatumInline(metadataDatum()), Optional.empty());

            var mintVal = Value.singleton(policyId(), new TokenName(REF_TOKEN_NAME), BigInteger.ONE)
                    .merge(Value.singleton(policyId(), new TokenName(USER_TOKEN_NAME), BigInteger.ONE));

            var ctx = mintingContext(policyId())
                    .redeemer(redeemer)
                    .output(refOutput)
                    .mint(mintVal)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            BudgetAssertions.assertTrace(result, "CIP-68 mint", "Mint NFT");
        }
    }

    // ---- Mode C: JulcEval proxy tests (test helper methods in isolation on UPLC VM) ----
    //
    // Limitation: JulcEval compiles a method within the full class context. When a class
    // has @Param fields, the compiler sees them as undefined variables even for methods
    // that don't reference them (like isOwnScript, hasInlineDatum). This means JulcEval
    // proxy tests cannot be used with any validator that has @Param fields.
    //
    // For a working example of JulcEval proxy tests, see SwapOrderTest which has no @Param
    // fields. For validators with @Param, use UPLC compilation tests (Mode B) instead.

    @Nested
    @Disabled("JulcEval cannot compile methods from classes with @Param fields")
    class JulcEvalProxyTests {

        // Proxy interface — demonstrates the pattern even though it can't run here.
        // SumType parameters (Credential, OutputDatum) use PlutusData with Constr encoding:
        //   Credential.ScriptCredential = Constr(1, [BData(hash)])
        //   Credential.PubKeyCredential = Constr(0, [BData(pkh)])
        //   OutputDatum.NoOutputDatum   = Constr(0, [])
        //   OutputDatum.OutputDatumHash = Constr(1, [BData(hash)])
        //   OutputDatum.OutputDatumInline = Constr(2, [datum])
        interface Cip68NftProxy {
            boolean isOwnScript(PlutusData credential, byte[] hash);
            boolean hasInlineDatum(PlutusData datum);
        }

        private final Cip68NftProxy proxy =
                JulcEval.forClass(Cip68Nft.class).create(Cip68NftProxy.class);

        // --- isOwnScript tests ---

        @Test
        void isOwnScript_scriptCredentialMatches_true() {
            var cred = PlutusData.constr(1, PlutusData.bytes(SCRIPT_HASH));
            assertTrue(proxy.isOwnScript(cred, SCRIPT_HASH));
        }

        @Test
        void isOwnScript_scriptCredentialMismatch_false() {
            var cred = PlutusData.constr(1, PlutusData.bytes(SCRIPT_HASH));
            byte[] otherHash = new byte[]{99, 98, 97, 96, 95, 94, 93, 92, 91, 90,
                    89, 88, 87, 86, 85, 84, 83, 82, 81, 80,
                    79, 78, 77, 76, 75, 74, 73, 72};
            assertFalse(proxy.isOwnScript(cred, otherHash));
        }

        @Test
        void isOwnScript_pubKeyCredential_false() {
            var cred = PlutusData.constr(0, PlutusData.bytes(MINTER_PKH));
            assertFalse(proxy.isOwnScript(cred, MINTER_PKH));
        }

        // --- hasInlineDatum tests ---

        @Test
        void hasInlineDatum_inlineDatum_true() {
            var datum = PlutusData.constr(2, PlutusData.integer(42));
            assertTrue(proxy.hasInlineDatum(datum));
        }

        @Test
        void hasInlineDatum_datumHash_false() {
            var datum = PlutusData.constr(1, PlutusData.bytes(new byte[32]));
            assertFalse(proxy.hasInlineDatum(datum));
        }

        @Test
        void hasInlineDatum_noDatum_false() {
            var datum = PlutusData.constr(0);
            assertFalse(proxy.hasInlineDatum(datum));
        }

        // --- Fluent call alternatives ---

        @Test
        void isOwnScript_fluentCall() {
            var eval = JulcEval.forClass(Cip68Nft.class);
            var cred = PlutusData.constr(1, PlutusData.bytes(SCRIPT_HASH));
            assertTrue(eval.call("isOwnScript", cred, SCRIPT_HASH).asBoolean());
        }

        @Test
        void hasInlineDatum_fluentCall() {
            var eval = JulcEval.forClass(Cip68Nft.class);
            var datum = PlutusData.constr(2, PlutusData.integer(42));
            assertTrue(eval.call("hasInlineDatum", datum).asBoolean());
        }
    }

    private static void logBudget(String testName, com.bloxbean.cardano.julc.vm.EvalResult result) {
        var budget = result.budgetConsumed();
        System.out.println("[" + testName + "] CPU: " + budget.cpuSteps() + ", Mem: " + budget.memoryUnits());
        System.out.println("[" + testName + "] Traces: " + result.traces());
        System.out.println("[" + testName + "] Success: " + result.isSuccess());
        if (result instanceof com.bloxbean.cardano.julc.vm.EvalResult.Failure f) {
            System.out.println("[" + testName + "] Error: " + f.error());
        }
    }
}
