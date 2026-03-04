package com.example.uverify.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.lib.CryptoLib;
import com.bloxbean.cardano.julc.testkit.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UPLC tests for UVerifyV1 — @MultiValidator with WITHDRAW + SPEND + CERTIFY.
 * <p>
 * Tests the centralized validation via "withdraw zero" trick.
 * WITHDRAW is the main entrypoint that validates everything.
 * SPEND and CERTIFY are pass-throughs.
 * <p>
 * Note: Direct Java tests are not feasible because the validator uses raw Builtins.
 * All tests run through UPLC compilation and VM evaluation.
 */
class UVerifyV1Test extends ContractTest {

    // 28-byte proxy policyId
    static final byte[] PROXY_POLICY_ID = new byte[]{
            10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120, 11, 21,
            15, 25, 35, 45, 55, 65, 75, 85, 95, 105, 115, 125, 12, 22};

    // State token name (32 bytes)
    static final byte[] STATE_TOKEN_NAME = new byte[]{
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
            17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32};

    // 28-byte admin keys
    static final byte[] ADMIN_KEY1 = new byte[]{
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14,
            15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28};
    static final byte[] ADMIN_KEY2 = new byte[]{
            31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44,
            45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58};

    static final byte[] OWNER_PKH = new byte[]{
            61, 62, 63, 64, 65, 66, 67, 68, 69, 70, 71, 72, 73, 74,
            75, 76, 77, 78, 79, 80, 81, 82, 83, 84, 85, 86, 87, 88};

    static final byte[] OTHER_PKH = new byte[]{
            99, 98, 97, 96, 95, 94, 93, 92, 91, 90, 89, 88, 87, 86,
            85, 84, 83, 82, 81, 80, 79, 78, 77, 76, 75, 74, 73, 72};

    static final byte[] FEE_RECEIVER = new byte[]{
            21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34,
            35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48};

    // Bootstrap token name (32 bytes)
    static final byte[] BOOTSTRAP_TOKEN_NAME = new byte[]{
            81, 82, 83, 84, 85, 86, 87, 88, 89, 90, 91, 92, 93, 94, 95, 96,
            97, 98, 99, 100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110, 111, 112};

    @BeforeAll
    static void setup() {
        initCrypto();
    }

    static PolicyId proxyPolicyId() {
        return new PolicyId(PROXY_POLICY_ID);
    }

    static Address scriptAddress() {
        return new Address(
                new Credential.ScriptCredential(new ScriptHash(PROXY_POLICY_ID)),
                Optional.empty());
    }

    static Address pubKeyAddress(byte[] pkh) {
        return new Address(
                new Credential.PubKeyCredential(new PubKeyHash(pkh)),
                Optional.empty());
    }

    // StatePurpose tags (sealed interface ordering):
    // BurnBootstrap=0, BurnState=1, UpdateState=2, MintState=3, MintBootstrap=4

    // UVerifyStateRedeemer = Constr(0, [StatePurpose, ListData(certificates)])
    static PlutusData redeemerPlutus(int purposeTag, PlutusData... certs) {
        return PlutusData.constr(0,
                PlutusData.constr(purposeTag),
                PlutusData.list(certs));
    }

    // BootstrapDatum = Constr(0, [allowedCreds, tokenName, fee, feeInterval,
    //                  feeReceivers, ttl, transactionLimit, batchSize])
    static PlutusData bootstrapDatumPlutus(byte[] tokenName, BigInteger fee,
                                           BigInteger feeInterval, BigInteger ttl,
                                           BigInteger transactionLimit, BigInteger batchSize) {
        return PlutusData.constr(0,
                PlutusData.list(),
                PlutusData.bytes(tokenName),
                PlutusData.integer(fee),
                PlutusData.integer(feeInterval),
                PlutusData.list(PlutusData.bytes(FEE_RECEIVER)),
                PlutusData.integer(ttl),
                PlutusData.integer(transactionLimit),
                PlutusData.integer(batchSize));
    }

    // StateDatum = Constr(0, [id, owner, fee, feeInterval, feeReceivers,
    //              ttl, countdown, certHash, batchSize, bootstrapName, keepAsOracle])
    static PlutusData stateDatumPlutus(byte[] id, byte[] owner, BigInteger fee,
                                       BigInteger feeInterval, BigInteger ttl,
                                       BigInteger countdown, byte[] certHash,
                                       BigInteger batchSize, byte[] bootstrapName,
                                       boolean keepAsOracle) {
        return PlutusData.constr(0,
                PlutusData.bytes(id),
                PlutusData.bytes(owner),
                PlutusData.integer(fee),
                PlutusData.integer(feeInterval),
                PlutusData.list(PlutusData.bytes(FEE_RECEIVER)),
                PlutusData.integer(ttl),
                PlutusData.integer(countdown),
                PlutusData.bytes(certHash),
                PlutusData.integer(batchSize),
                PlutusData.bytes(bootstrapName),
                keepAsOracle ? PlutusData.constr(1) : PlutusData.constr(0));
    }

    com.bloxbean.cardano.julc.core.Program compileAndApplyParams() {
        var result = compileValidator(UVerifyV1.class);
        assertFalse(result.hasErrors(), "UVerifyV1 should compile: " + result);
        return result.program().applyParams(
                PlutusData.bytes(PROXY_POLICY_ID),
                PlutusData.bytes(STATE_TOKEN_NAME),
                PlutusData.bytes(ADMIN_KEY1),
                PlutusData.bytes(ADMIN_KEY2));
    }

    @Test
    void compilesSuccessfully() {
        var result = compileValidator(UVerifyV1.class);
        assertFalse(result.hasErrors(), "UVerifyV1 should compile without errors: " + result);
        assertNotNull(result.program(), "Compiled program should not be null");
        System.out.println("UVerifyV1 script size: " + result.scriptSizeFormatted());
    }

    @Test
    void mintBootstrap_evaluatesSuccess() throws Exception {
        var program = compileAndApplyParams();

        var bsTokenName = BOOTSTRAP_TOKEN_NAME;
        // MintBootstrap tag = 4
        var redeemer = redeemerPlutus(4);

        var bootstrapDatum = bootstrapDatumPlutus(bsTokenName,
                BigInteger.valueOf(1_000_000), BigInteger.ONE,
                BigInteger.valueOf(1_700_000_000_000L), BigInteger.TEN,
                BigInteger.valueOf(5));

        var mintValue = Value.singleton(proxyPolicyId(), new TokenName(bsTokenName), BigInteger.ONE);
        var outputValue = Value.lovelace(BigInteger.valueOf(2_000_000))
                .merge(Value.singleton(proxyPolicyId(), new TokenName(bsTokenName), BigInteger.ONE));
        var scriptOutput = new TxOut(scriptAddress(), outputValue,
                new OutputDatum.OutputDatumInline(bootstrapDatum), Optional.empty());

        var v1Cred = new Credential.ScriptCredential(new ScriptHash(new byte[28]));
        var ctx = rewardingContext(v1Cred)
                .redeemer(redeemer)
                .output(scriptOutput)
                .mint(mintValue)
                .signer(ADMIN_KEY1)
                .buildPlutusData();

        var evalResult = evaluate(program, ctx);
        logBudget("mintBootstrap_evaluatesSuccess", evalResult);
        assertSuccess(evalResult);
    }

    @Test
    void mintBootstrap_rejectsNonAdmin() throws Exception {
        var program = compileAndApplyParams();

        var bsTokenName = BOOTSTRAP_TOKEN_NAME;
        var redeemer = redeemerPlutus(4);

        var bootstrapDatum = bootstrapDatumPlutus(bsTokenName,
                BigInteger.valueOf(1_000_000), BigInteger.ONE,
                BigInteger.valueOf(1_700_000_000_000L), BigInteger.TEN,
                BigInteger.valueOf(5));

        var mintValue = Value.singleton(proxyPolicyId(), new TokenName(bsTokenName), BigInteger.ONE);
        var outputValue = Value.lovelace(BigInteger.valueOf(2_000_000))
                .merge(Value.singleton(proxyPolicyId(), new TokenName(bsTokenName), BigInteger.ONE));
        var scriptOutput = new TxOut(scriptAddress(), outputValue,
                new OutputDatum.OutputDatumInline(bootstrapDatum), Optional.empty());

        var v1Cred = new Credential.ScriptCredential(new ScriptHash(new byte[28]));
        var ctx = rewardingContext(v1Cred)
                .redeemer(redeemer)
                .output(scriptOutput)
                .mint(mintValue)
                .signer(OTHER_PKH)
                .buildPlutusData();

        var evalResult = evaluate(program, ctx);
        assertFailure(evalResult);
        logBudget("mintBootstrap_rejectsNonAdmin", evalResult);
    }

    @Test
    void burnBootstrap_evaluatesSuccess() throws Exception {
        var program = compileAndApplyParams();

        var bsTokenName = BOOTSTRAP_TOKEN_NAME;
        // BurnBootstrap tag = 0
        var redeemer = redeemerPlutus(0);

        var bootstrapDatum = bootstrapDatumPlutus(bsTokenName,
                BigInteger.valueOf(1_000_000), BigInteger.ONE,
                BigInteger.valueOf(1_700_000_000_000L), BigInteger.TEN,
                BigInteger.valueOf(5));

        var mintValue = Value.singleton(proxyPolicyId(), new TokenName(bsTokenName), BigInteger.ONE.negate());

        var scriptInput = new TxInInfo(
                TestDataBuilder.randomTxOutRef_typed(),
                new TxOut(scriptAddress(),
                        Value.lovelace(BigInteger.valueOf(2_000_000))
                                .merge(Value.singleton(proxyPolicyId(), new TokenName(bsTokenName), BigInteger.ONE)),
                        new OutputDatum.OutputDatumInline(bootstrapDatum), Optional.empty()));

        var pubKeyOutput = new TxOut(pubKeyAddress(ADMIN_KEY1),
                Value.lovelace(BigInteger.valueOf(2_000_000)),
                new OutputDatum.NoOutputDatum(), Optional.empty());

        var v1Cred = new Credential.ScriptCredential(new ScriptHash(new byte[28]));
        var ctx = rewardingContext(v1Cred)
                .redeemer(redeemer)
                .input(scriptInput)
                .output(pubKeyOutput)
                .mint(mintValue)
                .signer(ADMIN_KEY1)
                .buildPlutusData();

        var evalResult = evaluate(program, ctx);
        logBudget("burnBootstrap_evaluatesSuccess", evalResult);
        assertSuccess(evalResult);
    }

    @Test
    void spendPassthrough_evaluatesSuccess() throws Exception {
        var program = compileAndApplyParams();

        var redeemer = PlutusData.UNIT;
        var datum = PlutusData.UNIT;
        var spentRef = TestDataBuilder.randomTxOutRef_typed();

        var ctx = spendingContext(spentRef, datum)
                .redeemer(redeemer)
                .buildPlutusData();

        var evalResult = evaluate(program, ctx);
        assertSuccess(evalResult);
        logBudget("spendPassthrough_evaluatesSuccess", evalResult);
    }

    @Test
    void certifyPassthrough_evaluatesSuccess() throws Exception {
        var program = compileAndApplyParams();

        var redeemer = PlutusData.UNIT;
        var v1Cred = new Credential.ScriptCredential(new ScriptHash(new byte[28]));
        var cert = new TxCert.RegStaking(v1Cred, Optional.empty());

        var ctx = certifyingContext(BigInteger.ZERO, cert)
                .redeemer(redeemer)
                .buildPlutusData();

        var evalResult = evaluate(program, ctx);
        assertSuccess(evalResult);
        logBudget("certifyPassthrough_evaluatesSuccess", evalResult);
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
