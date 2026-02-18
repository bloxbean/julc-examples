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
 * Tests for MultiSigMinting — sealed interface redeemer with 3 variants.
 * <p>
 * DirectJavaTests: unit tests calling validator logic directly in Java.
 * UplcTests: compile to UPLC and evaluate on VM.
 */
class MultiSigMintingTest extends ContractTest {

    static final byte[] AUTHORITY = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
            11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
            21, 22, 23, 24, 25, 26, 27, 28};
    static final byte[] SIGNER1 = new byte[]{31, 32, 33, 34, 35, 36, 37, 38, 39, 40,
            41, 42, 43, 44, 45, 46, 47, 48, 49, 50,
            51, 52, 53, 54, 55, 56, 57, 58};
    static final byte[] SIGNER2 = new byte[]{61, 62, 63, 64, 65, 66, 67, 68, 69, 70,
            71, 72, 73, 74, 75, 76, 77, 78, 79, 80,
            81, 82, 83, 84, 85, 86, 87, 88};
    static final byte[] OTHER_PKH = new byte[]{99, 98, 97, 96, 95, 94, 93, 92, 91, 90,
            89, 88, 87, 86, 85, 84, 83, 82, 81, 80,
            79, 78, 77, 76, 75, 74, 73, 72};

    @BeforeAll
    static void setup() {
        initCrypto();
    }

    // ---- Mode A: Direct Java tests ----

    @Nested
    class DirectJavaTests {

        private ScriptContext buildCtx(byte[][] signers) {
            var sigList = JulcList.<PubKeyHash>empty();
            for (byte[] sig : signers) {
                sigList = sigList.prepend(new PubKeyHash(sig));
            }
            var txInfo = new TxInfo(
                    JulcList.of(), JulcList.of(),
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
                    new ScriptInfo.MintingScript(new PolicyId(new byte[28])));
        }

        @Test
        void mintByAuthority_passes() {
            var redeemer = new MultiSigMinting.MintByAuthority(AUTHORITY);
            var ctx = buildCtx(new byte[][]{AUTHORITY});

            boolean result = MultiSigMinting.validate(redeemer, ctx);
            assertTrue(result, "Authority signed should pass");
        }

        @Test
        void mintByAuthority_fails() {
            var redeemer = new MultiSigMinting.MintByAuthority(AUTHORITY);
            var ctx = buildCtx(new byte[][]{OTHER_PKH});

            boolean result = MultiSigMinting.validate(redeemer, ctx);
            assertFalse(result, "Authority not in signatories should fail");
        }

        @Test
        void burnByOwner_alwaysPasses() {
            var redeemer = new MultiSigMinting.BurnByOwner();
            var ctx = buildCtx(new byte[][]{});

            boolean result = MultiSigMinting.validate(redeemer, ctx);
            assertTrue(result, "BurnByOwner should always pass");
        }

        @Test
        void mintByMultiSig_bothSign_passes() {
            var redeemer = new MultiSigMinting.MintByMultiSig(SIGNER1, SIGNER2);
            var ctx = buildCtx(new byte[][]{SIGNER1, SIGNER2});

            boolean result = MultiSigMinting.validate(redeemer, ctx);
            assertTrue(result, "Both signers present should pass");
        }

        @Test
        void mintByMultiSig_onlyOneSigner_fails() {
            var redeemer = new MultiSigMinting.MintByMultiSig(SIGNER1, SIGNER2);
            var ctx = buildCtx(new byte[][]{SIGNER1}); // only signer1

            boolean result = MultiSigMinting.validate(redeemer, ctx);
            assertFalse(result, "Only one signer should fail");
        }
    }

    // ---- Mode B: UPLC compilation tests ----

    @Nested
    class UplcTests {

        private PlutusData buildMintingCtx(PlutusData redeemer, byte[]... signers) {
            var policyId = new PolicyId(new byte[28]);
            var builder = mintingContext(policyId).redeemer(redeemer);
            for (byte[] s : signers) {
                builder.signer(s);
            }
            return builder.buildPlutusData();
        }

        // MintByAuthority: Constr(0, [BData(authority)])
        private PlutusData buildMintByAuthority(byte[] authority) {
            return PlutusData.constr(0, PlutusData.bytes(authority));
        }

        // BurnByOwner: Constr(1, [])
        private PlutusData buildBurnByOwner() {
            return PlutusData.constr(1);
        }

        // MintByMultiSig: Constr(2, [BData(signer1), BData(signer2)])
        private PlutusData buildMintByMultiSig(byte[] s1, byte[] s2) {
            return PlutusData.constr(2, PlutusData.bytes(s1), PlutusData.bytes(s2));
        }

        @Test
        void mintByAuthority_evaluatesSuccess() throws Exception {
            var program = compileValidator(MultiSigMinting.class).program();

            var redeemer = buildMintByAuthority(AUTHORITY);
            var ctx = buildMintingCtx(redeemer, AUTHORITY);

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("mintByAuthority_evaluatesSuccess", result);
        }

        @Test
        void burnByOwner_evaluatesSuccess() throws Exception {
            var program = compileValidator(MultiSigMinting.class).program();

            var redeemer = buildBurnByOwner();
            var ctx = buildMintingCtx(redeemer); // no signers needed

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("burnByOwner_evaluatesSuccess", result);
        }

        @Test
        void mintByMultiSig_evaluatesSuccess() throws Exception {
            var program = compileValidator(MultiSigMinting.class).program();

            var redeemer = buildMintByMultiSig(SIGNER1, SIGNER2);
            var ctx = buildMintingCtx(redeemer, SIGNER1, SIGNER2);

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("mintByMultiSig_evaluatesSuccess", result);
        }

        @Test
        void mintByMultiSig_rejectsOneSigner() throws Exception {
            var program = compileValidator(MultiSigMinting.class).program();

            var redeemer = buildMintByMultiSig(SIGNER1, SIGNER2);
            var ctx = buildMintingCtx(redeemer, SIGNER1); // only signer1

            var result = evaluate(program, ctx);
            assertFailure(result);
            logBudget("mintByMultiSig_rejectsOneSigner", result);
        }
    }

    private static void logBudget(String testName, com.bloxbean.cardano.julc.vm.EvalResult result) {
        var budget = result.budgetConsumed();
        System.out.println("[" + testName + "] CPU: " + budget.cpuSteps() + ", Mem: " + budget.memoryUnits());
        System.out.println("[" + testName + "] Traces: " + result.traces());
    }
}
