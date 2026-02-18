package com.example.benchmark.wingriders;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.core.types.JulcMap;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.testkit.*;
import com.example.benchmark.wingriders.WingRidersRequestValidator.*;
import com.example.benchmark.wingriders.WingRidersPoolValidator.PoolDatum;
import com.example.benchmark.wingriders.WingRidersPoolValidator.Evolve;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * WingRiders DEX Benchmark Tests.
 * <p>
 * Tests request and pool validators, then benchmarks pool validator
 * with increasing numbers of requests to measure CPU/Memory budgets.
 */
class WingRidersBenchmarkTest extends ContractTest {

    // --- Constants ---

    static final byte[] POOL_SCRIPT_HASH = fixedBytes(28, 1);
    static final byte[] REQUEST_SCRIPT_HASH = fixedBytes(28, 2);
    static final byte[] VALIDITY_POLICY_ID = fixedBytes(28, 3);
    static final byte[] STAKING_REWARDS_POLICY_ID = fixedBytes(28, 4);
    static final byte[] ENFORCED_DATUM_HASH = fixedBytes(32, 5);
    static final byte[] TREASURY_HOLDER_HASH = fixedBytes(28, 6);

    // Token A = ADA (empty policy/name)
    static final byte[] A_POLICY_ID = new byte[0];
    static final byte[] A_ASSET_NAME = new byte[0];

    // Token B = some native asset
    static final byte[] B_POLICY_ID = fixedBytes(28, 10);
    static final byte[] B_ASSET_NAME = "TokenB".getBytes(StandardCharsets.UTF_8);

    static final BigInteger REQUEST_OIL_ADA = BigInteger.valueOf(2_000_000);
    static final BigInteger AGENT_FEE_ADA = BigInteger.valueOf(2_000_000);
    static final BigInteger REQUEST_INPUT_ADA = REQUEST_OIL_ADA.add(AGENT_FEE_ADA); // 4 ADA
    static final BigInteger COMPENSATION_ADA = REQUEST_OIL_ADA; // 2 ADA

    static final byte[] OWNER_PKH = fixedBytes(28, 20);
    static final byte[] BENEFICIARY_PKH = fixedBytes(28, 30);
    static final BigInteger DEADLINE = BigInteger.valueOf(999_999_999);

    @BeforeAll
    static void setup() {
        initCrypto();
    }

    // --- Helper methods ---

    static byte[] fixedBytes(int length, int fillValue) {
        byte[] bytes = new byte[length];
        Arrays.fill(bytes, (byte) fillValue);
        return bytes;
    }

    // -------------------------------------------------------------------
    //  Request Validator Tests
    // -------------------------------------------------------------------

    @Nested
    class RequestValidatorTests {

        @Nested
        class DirectJavaTests {

            private ScriptContext buildRequestCtx(
                    JulcList<TxInInfo> inputs,
                    byte[][] signers) {
                var sigList = JulcList.<PubKeyHash>empty();
                for (byte[] sig : signers) {
                    sigList = sigList.prepend(new PubKeyHash(sig));
                }
                var txInfo = new TxInfo(
                        inputs, JulcList.of(),
                        JulcList.of(),
                        BigInteger.valueOf(200_000),
                        Value.zero(),
                        JulcList.of(), JulcMap.empty(),
                        Interval.always(),
                        sigList,
                        JulcMap.empty(), JulcMap.empty(),
                        new TxId(new byte[32]),
                        JulcMap.empty(), JulcList.of(),
                        Optional.empty(), Optional.empty());
                return new ScriptContext(txInfo, PlutusData.UNIT,
                        new ScriptInfo.SpendingScript(new TxOutRef(new TxId(new byte[32]), BigInteger.ZERO), Optional.empty()));
            }

            @Test
            void apply_poolAtIndex_passes() {
                WingRidersRequestValidator.poolHash = POOL_SCRIPT_HASH;

                var poolAddress = new Address(
                        new Credential.ScriptCredential(new ScriptHash(POOL_SCRIPT_HASH)), Optional.empty());
                var poolTxOut = new TxOut(
                        poolAddress,
                        Value.lovelace(BigInteger.valueOf(100_000_000)),
                        new OutputDatum.NoOutputDatum(), Optional.empty());
                var poolTxOutRef = new TxOutRef(new TxId(new byte[32]), BigInteger.ZERO);
                var poolInput = new TxInInfo(poolTxOutRef, poolTxOut);

                var requestDatum = new RequestDatum(BENEFICIARY_PKH, false,
                        OWNER_PKH, DEADLINE,
                        A_POLICY_ID, A_ASSET_NAME, B_POLICY_ID, B_ASSET_NAME,
                        new Swap(new SwapAToB(), BigInteger.valueOf(100)));
                var redeemer = new Apply(BigInteger.ZERO);

                var ctx = buildRequestCtx(JulcList.of(poolInput), new byte[][]{});
                boolean result = WingRidersRequestValidator.validate(requestDatum, redeemer, ctx);
                assertTrue(result, "Apply with correct pool hash should pass");
            }

            @Test
            void apply_wrongPoolHash_fails() {
                WingRidersRequestValidator.poolHash = POOL_SCRIPT_HASH;

                var wrongHash = fixedBytes(28, 99);
                var poolAddress = new Address(
                        new Credential.ScriptCredential(new ScriptHash(wrongHash)), Optional.empty());
                var poolTxOut = new TxOut(
                        poolAddress,
                        Value.lovelace(BigInteger.valueOf(100_000_000)),
                        new OutputDatum.NoOutputDatum(), Optional.empty());
                var poolTxOutRef = new TxOutRef(new TxId(new byte[32]), BigInteger.ZERO);
                var poolInput = new TxInInfo(poolTxOutRef, poolTxOut);

                var requestDatum = new RequestDatum(BENEFICIARY_PKH, false,
                        OWNER_PKH, DEADLINE,
                        A_POLICY_ID, A_ASSET_NAME, B_POLICY_ID, B_ASSET_NAME,
                        new Swap(new SwapAToB(), BigInteger.valueOf(100)));
                var redeemer = new Apply(BigInteger.ZERO);

                var ctx = buildRequestCtx(JulcList.of(poolInput), new byte[][]{});
                boolean result = WingRidersRequestValidator.validate(requestDatum, redeemer, ctx);
                assertFalse(result, "Apply with wrong pool hash should fail");
            }

            @Test
            void reclaim_ownerSigned_passes() {
                WingRidersRequestValidator.poolHash = POOL_SCRIPT_HASH;

                var requestDatum = new RequestDatum(BENEFICIARY_PKH, false,
                        OWNER_PKH, DEADLINE,
                        A_POLICY_ID, A_ASSET_NAME, B_POLICY_ID, B_ASSET_NAME,
                        new Swap(new SwapAToB(), BigInteger.valueOf(100)));
                var redeemer = new Reclaim();

                var ctx = buildRequestCtx(JulcList.of(), new byte[][]{OWNER_PKH});
                boolean result = WingRidersRequestValidator.validate(requestDatum, redeemer, ctx);
                assertTrue(result, "Reclaim with owner signed should pass");
            }

            @Test
            void reclaim_notSigned_fails() {
                WingRidersRequestValidator.poolHash = POOL_SCRIPT_HASH;

                var requestDatum = new RequestDatum(BENEFICIARY_PKH, false,
                        OWNER_PKH, DEADLINE,
                        A_POLICY_ID, A_ASSET_NAME, B_POLICY_ID, B_ASSET_NAME,
                        new Swap(new SwapAToB(), BigInteger.valueOf(100)));
                var redeemer = new Reclaim();

                var ctx = buildRequestCtx(JulcList.of(), new byte[][]{BENEFICIARY_PKH}); // not owner
                boolean result = WingRidersRequestValidator.validate(requestDatum, redeemer, ctx);
                assertFalse(result, "Reclaim without owner signature should fail");
            }
        }

        @Nested
        class UplcTests {

            @Test
            void apply_compilesAndEvaluates() throws Exception {
                var program = compileValidator(WingRidersRequestValidator.class).program();
                var concrete = program.applyParams(PlutusData.bytes(POOL_SCRIPT_HASH));

                var datumData = buildRequestDatumData(BENEFICIARY_PKH, false, OWNER_PKH, DEADLINE,
                        A_POLICY_ID, A_ASSET_NAME, B_POLICY_ID, B_ASSET_NAME,
                        buildSwapAction(true, 100));

                // Apply redeemer: Constr(0, [IData(0)])
                var redeemerData = PlutusData.constr(0, PlutusData.integer(0));

                // Build context with pool input at index 0
                var spendingRef = TestDataBuilder.randomTxOutRef_typed();
                var poolRef = TestDataBuilder.randomTxOutRef_typed();
                var poolAddr = new Address(
                        new Credential.ScriptCredential(new ScriptHash(POOL_SCRIPT_HASH)),
                        Optional.empty());
                var poolTxOut = new TxOut(poolAddr,
                        com.bloxbean.cardano.julc.ledger.Value.lovelace(BigInteger.valueOf(100_000_000)),
                        new OutputDatum.NoOutputDatum(), Optional.empty());
                var poolIn = TestDataBuilder.txIn(poolRef, poolTxOut);

                var ctx = spendingContext(spendingRef, datumData)
                        .redeemer(redeemerData)
                        .input(poolIn)
                        .buildPlutusData();

                var result = evaluate(concrete, ctx);
                assertSuccess(result);
                logBudget("request_apply", result);
            }

            @Test
            void reclaim_compilesAndEvaluates() throws Exception {
                var program = compileValidator(WingRidersRequestValidator.class).program();
                var concrete = program.applyParams(PlutusData.bytes(POOL_SCRIPT_HASH));

                var datumData = buildRequestDatumData(BENEFICIARY_PKH, false, OWNER_PKH, DEADLINE,
                        A_POLICY_ID, A_ASSET_NAME, B_POLICY_ID, B_ASSET_NAME,
                        buildSwapAction(true, 100));

                // Reclaim redeemer: Constr(1, [])
                var redeemerData = PlutusData.constr(1);

                var spendingRef = TestDataBuilder.randomTxOutRef_typed();
                var ctx = spendingContext(spendingRef, datumData)
                        .redeemer(redeemerData)
                        .signer(OWNER_PKH)
                        .buildPlutusData();

                var result = evaluate(concrete, ctx);
                assertSuccess(result);
                logBudget("request_reclaim", result);
            }
        }
    }

    // -------------------------------------------------------------------
    //  Pool Validator Tests
    // -------------------------------------------------------------------

    @Nested
    class PoolValidatorTests {

        @Nested
        class UplcTests {

            @Test
            void evolve_singleRequest_evaluates() throws Exception {
                var program = compileValidator(WingRidersPoolValidator.class).program();

                var concrete = program.applyParams(
                        PlutusData.bytes(VALIDITY_POLICY_ID),
                        PlutusData.bytes(STAKING_REWARDS_POLICY_ID),
                        PlutusData.bytes(ENFORCED_DATUM_HASH),
                        PlutusData.bytes(TREASURY_HOLDER_HASH));

                var ctxData = buildPoolBenchmarkContext(1);

                var result = evaluate(concrete, ctxData);
                if (!result.isSuccess()) {
                    System.out.println("[pool_evolve] FAILURE: " + result);
                    System.out.println("[pool_evolve] Traces: " + result.traces());
                }
                assertSuccess(result);
                logBudget("pool_evolve_1_request", result);
            }
        }
    }

    // -------------------------------------------------------------------
    //  Benchmark Suite
    // -------------------------------------------------------------------

    @Nested
    class BenchmarkSuite {

        @ParameterizedTest(name = "benchmark_{0}_requests")
        @ValueSource(ints = {1, 2, 4, 10, 20, 24, 30})
        void benchmarkPoolValidator(int numRequests) throws Exception {
            var program = compileValidator(WingRidersPoolValidator.class).program();

            var concrete = program.applyParams(
                    PlutusData.bytes(VALIDITY_POLICY_ID),
                    PlutusData.bytes(STAKING_REWARDS_POLICY_ID),
                    PlutusData.bytes(ENFORCED_DATUM_HASH),
                    PlutusData.bytes(TREASURY_HOLDER_HASH));

            var ctxData = buildPoolBenchmarkContext(numRequests);

            var result = evaluate(concrete, ctxData);
            assertSuccess(result);

            var budget = result.budgetConsumed();
            long cpu = budget.cpuSteps();
            long mem = budget.memoryUnits();
            long cpuPerReq = cpu / numRequests;
            long memPerReq = mem / numRequests;

            System.out.println("=== WingRiders Benchmark: " + numRequests + " requests ===");
            System.out.println("  Total CPU: " + formatNumber(cpu) + " | Mem: " + formatNumber(mem));
            System.out.println("  Per-Req CPU: " + formatNumber(cpuPerReq) + " | Mem: " + formatNumber(memPerReq));
            System.out.println("  Comparison:");
            System.out.println("    Plinth   per-req: CPU 723M, Mem 2.87M (max  4/tx)");
            System.out.println("    Aiken    per-req: CPU 164M, Mem 521K  (max 24/tx)");
            System.out.println("    Plutarch per-req: CPU 121M, Mem 356K  (max 38/tx)");
            System.out.println("    JuLC     per-req: CPU " + formatNumber(cpuPerReq) + ", Mem " + formatNumber(memPerReq));
        }
    }

    // -------------------------------------------------------------------
    //  PlutusData builders for UPLC tests
    // -------------------------------------------------------------------

    /**
     * Build a complete ScriptContext (as PlutusData) for the pool validator benchmark.
     */
    PlutusData buildPoolBenchmarkContext(int numRequests) {
        // Pool datum: Constr(0, [requestHash, aPolicyId, aAssetName, bPolicyId, bAssetName])
        var poolDatumData = PlutusData.constr(0,
                PlutusData.bytes(REQUEST_SCRIPT_HASH),
                PlutusData.bytes(A_POLICY_ID),
                PlutusData.bytes(A_ASSET_NAME),
                PlutusData.bytes(B_POLICY_ID),
                PlutusData.bytes(B_ASSET_NAME));

        // Evolve redeemer: Constr(0, [ListData([0, 1, ..., N-1])])
        var indices = new ArrayList<PlutusData>();
        for (int i = 0; i < numRequests; i++) {
            indices.add(PlutusData.integer(i));
        }
        var redeemerData = PlutusData.constr(0, new PlutusData.ListData(indices));

        // Build request inputs
        var inputTxIns = new ArrayList<TxInInfo>();
        for (int i = 0; i < numRequests; i++) {
            inputTxIns.add(buildRequestInput(i));
        }

        // Build outputs: [poolOutput, compensation1, compensation2, ...]
        var outputs = new ArrayList<TxOut>();

        // Pool output (first output, skipped by validator)
        var poolAddr = new Address(
                new Credential.ScriptCredential(new ScriptHash(POOL_SCRIPT_HASH)),
                Optional.empty());
        var poolOutput = new TxOut(poolAddr,
                Value.lovelace(BigInteger.valueOf(100_000_000)),
                new OutputDatum.NoOutputDatum(), Optional.empty());
        outputs.add(poolOutput);

        // Compensation outputs (one per request)
        for (int i = 0; i < numRequests; i++) {
            outputs.add(buildCompensationOutput());
        }

        // Finite validity range: [0, 500_000_000]
        var validRange = Interval.between(
                BigInteger.ZERO, BigInteger.valueOf(500_000_000));

        // Build spending context
        var spendingRef = TestDataBuilder.randomTxOutRef_typed();
        var builder = spendingContext(spendingRef, poolDatumData)
                .redeemer(redeemerData)
                .validRange(validRange);

        for (var txIn : inputTxIns) {
            builder.input(txIn);
        }

        for (var txOut : outputs) {
            builder.output(txOut);
        }

        return builder.buildPlutusData();
    }

    /**
     * Build a single request input (TxInInfo) with inline RequestDatum.
     */
    TxInInfo buildRequestInput(int index) {
        var requestAddr = new Address(
                new Credential.ScriptCredential(new ScriptHash(REQUEST_SCRIPT_HASH)),
                Optional.empty());

        // RequestDatum as PlutusData
        var inlineDatum = buildRequestDatumData(
                BENEFICIARY_PKH, false, OWNER_PKH, DEADLINE,
                A_POLICY_ID, A_ASSET_NAME, B_POLICY_ID, B_ASSET_NAME,
                buildSwapAction(true, 100 + index));

        var requestValue = Value.lovelace(REQUEST_INPUT_ADA);

        var requestTxOut = new TxOut(requestAddr, requestValue,
                new OutputDatum.OutputDatumInline(inlineDatum), Optional.empty());

        var txId = fixedBytes(32, 50 + index);
        var txOutRef = new TxOutRef(new TxId(txId), BigInteger.ZERO);

        return TestDataBuilder.txIn(txOutRef, requestTxOut);
    }

    /**
     * Build a compensation output for a pubkey beneficiary (NoDatum).
     */
    TxOut buildCompensationOutput() {
        var beneficiaryAddr = new Address(
                new Credential.PubKeyCredential(new PubKeyHash(BENEFICIARY_PKH)),
                Optional.empty());

        var compensationValue = Value.lovelace(COMPENSATION_ADA);

        return new TxOut(beneficiaryAddr, compensationValue,
                new OutputDatum.NoOutputDatum(), Optional.empty());
    }

    // -------------------------------------------------------------------
    //  PlutusData encoding helpers
    // -------------------------------------------------------------------

    /**
     * Build RequestDatum as PlutusData.
     * RequestDatum(beneficiaryHash, beneficiaryIsScript, ownerHash, deadline,
     *              aPolicyId, aAssetName, bPolicyId, bAssetName, action)
     */
    static PlutusData buildRequestDatumData(byte[] beneficiaryHash, boolean beneficiaryIsScript,
                                            byte[] ownerHash, BigInteger deadline,
                                            byte[] aPolicyId, byte[] aAssetName,
                                            byte[] bPolicyId, byte[] bAssetName,
                                            PlutusData actionData) {
        // Boolean: true = Constr(1, []), false = Constr(0, [])
        var isScriptData = beneficiaryIsScript ? PlutusData.constr(1) : PlutusData.constr(0);

        return PlutusData.constr(0,
                PlutusData.bytes(beneficiaryHash),
                isScriptData,
                PlutusData.bytes(ownerHash),
                PlutusData.integer(deadline),
                PlutusData.bytes(aPolicyId),
                PlutusData.bytes(aAssetName),
                PlutusData.bytes(bPolicyId),
                PlutusData.bytes(bAssetName),
                actionData);
    }

    /**
     * Build a Swap RequestAction as PlutusData.
     * Swap: Constr(0, [direction, minWantedTokens])
     * SwapAToB: Constr(0, [])
     * SwapBToA: Constr(1, [])
     */
    static PlutusData buildSwapAction(boolean aToB, long minWanted) {
        var direction = aToB ? PlutusData.constr(0) : PlutusData.constr(1);
        return PlutusData.constr(0, direction, PlutusData.integer(minWanted));
    }

    // -------------------------------------------------------------------
    //  Utility
    // -------------------------------------------------------------------

    static String formatNumber(long n) {
        if (n >= 1_000_000_000) {
            return String.format("%.1fB", n / 1_000_000_000.0);
        } else if (n >= 1_000_000) {
            return String.format("%.1fM", n / 1_000_000.0);
        } else if (n >= 1_000) {
            return String.format("%.1fK", n / 1_000.0);
        }
        return String.valueOf(n);
    }

    static void logBudget(String testName, com.bloxbean.cardano.julc.vm.EvalResult result) {
        var budget = result.budgetConsumed();
        System.out.println("[" + testName + "] CPU: " + budget.cpuSteps() + ", Mem: " + budget.memoryUnits());
        System.out.println("[" + testName + "] Traces: " + result.traces());
    }
}
