package com.example.cftemplates.pricebet.onchain;

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

class CfPriceBetValidatorTest extends ContractTest {

    static final byte[] OWNER = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
            11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
            21, 22, 23, 24, 25, 26, 27, 28};
    static final byte[] PLAYER = new byte[]{31, 32, 33, 34, 35, 36, 37, 38, 39, 40,
            41, 42, 43, 44, 45, 46, 47, 48, 49, 50,
            51, 52, 53, 54, 55, 56, 57, 58};
    static final byte[] ORACLE_VKH = new byte[]{61, 62, 63, 64, 65, 66, 67, 68, 69, 70,
            71, 72, 73, 74, 75, 76, 77, 78, 79, 80,
            81, 82, 83, 84, 85, 86, 87, 88};
    static final byte[] EMPTY_BYTES = new byte[0];
    static final BigInteger DEADLINE = BigInteger.valueOf(1_700_000_000_000L);
    static final BigInteger TARGET_RATE = BigInteger.valueOf(50_000); // target price
    static final BigInteger BET_AMOUNT = BigInteger.valueOf(10_000_000);

    // Script hash for the price bet script address
    static final byte[] SCRIPT_HASH = new byte[]{(byte) 0xF1, (byte) 0xF2, (byte) 0xF3, 0, 0, 0, 0, 0, 0, 0,
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

    // PriceBetDatum = Constr(0, [owner, player, oracleVkh, targetRate, deadline, betAmount])
    static PlutusData priceBetDatum(byte[] owner, byte[] player, byte[] oracleVkh,
                                    BigInteger targetRate, BigInteger deadline, BigInteger betAmount) {
        return PlutusData.constr(0,
                PlutusData.bytes(owner), PlutusData.bytes(player), PlutusData.bytes(oracleVkh),
                PlutusData.integer(targetRate), PlutusData.integer(deadline), PlutusData.integer(betAmount));
    }

    /**
     * Build oracle datum: Constr(0, [Constr(2, [Map{0: price, 1: timestamp, 2: expiry}])])
     * OracleDatum = Constr(0, [priceData])
     * GenericData = Constr(2, [priceMap])
     */
    static PlutusData oracleDatum(BigInteger price, BigInteger timestamp, BigInteger expiry) {
        var priceMap = PlutusData.map(
                new PlutusData.Pair(PlutusData.integer(BigInteger.ZERO), PlutusData.integer(price)),
                new PlutusData.Pair(PlutusData.integer(BigInteger.ONE), PlutusData.integer(timestamp)),
                new PlutusData.Pair(PlutusData.integer(BigInteger.TWO), PlutusData.integer(expiry)));
        var genericData = PlutusData.constr(2, priceMap);
        return PlutusData.constr(0, genericData);
    }

    @Nested
    class JoinTests {

        @Test
        void join_valid_playerJoins_passes() throws Exception {
            var compiled = compileValidator(CfPriceBetValidator.class);
            var program = compiled.program();

            var datum = priceBetDatum(OWNER, EMPTY_BYTES, ORACLE_VKH, TARGET_RATE, DEADLINE, BET_AMOUNT);
            // Join = tag 0
            var redeemer = PlutusData.constr(0);

            // New datum with player set
            var newDatum = priceBetDatum(OWNER, PLAYER, ORACLE_VKH, TARGET_RATE, DEADLINE, BET_AMOUNT);

            var spentRef = TestDataBuilder.randomTxOutRef_typed();
            var spentInput = new TxInInfo(spentRef,
                    new TxOut(scriptAddress(),
                            Value.lovelace(BET_AMOUNT),
                            new OutputDatum.OutputDatumInline(datum),
                            Optional.empty()));

            // Continuing output with doubled pot and updated datum
            var continuingOutput = new TxOut(scriptAddress(),
                    Value.lovelace(BET_AMOUNT.multiply(BigInteger.TWO)),
                    new OutputDatum.OutputDatumInline(newDatum),
                    Optional.empty());

            var ctx = spendingContext(spentRef, datum)
                    .redeemer(redeemer)
                    .input(spentInput)
                    .output(continuingOutput)
                    .signer(PLAYER)
                    .validRange(Interval.before(DEADLINE))
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("join_valid_playerJoins_passes", result);
        }

        @Test
        void join_alreadyHasPlayer_fails() throws Exception {
            var compiled = compileValidator(CfPriceBetValidator.class);
            var program = compiled.program();

            // Datum already has a player set
            var datum = priceBetDatum(OWNER, PLAYER, ORACLE_VKH, TARGET_RATE, DEADLINE, BET_AMOUNT);
            var redeemer = PlutusData.constr(0);

            var newDatum = priceBetDatum(OWNER, PLAYER, ORACLE_VKH, TARGET_RATE, DEADLINE, BET_AMOUNT);

            var spentRef = TestDataBuilder.randomTxOutRef_typed();
            var spentInput = new TxInInfo(spentRef,
                    new TxOut(scriptAddress(),
                            Value.lovelace(BET_AMOUNT.multiply(BigInteger.TWO)),
                            new OutputDatum.OutputDatumInline(datum),
                            Optional.empty()));

            var continuingOutput = new TxOut(scriptAddress(),
                    Value.lovelace(BET_AMOUNT.multiply(BigInteger.TWO)),
                    new OutputDatum.OutputDatumInline(newDatum),
                    Optional.empty());

            var ctx = spendingContext(spentRef, datum)
                    .redeemer(redeemer)
                    .input(spentInput)
                    .output(continuingOutput)
                    .signer(PLAYER)
                    .validRange(Interval.before(DEADLINE))
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertFailure(result);
        }
    }

    @Nested
    class WinTests {

        @Test
        void win_priceAboveTarget_valid_passes() throws Exception {
            var compiled = compileValidator(CfPriceBetValidator.class);
            var program = compiled.program();

            var datum = priceBetDatum(OWNER, PLAYER, ORACLE_VKH, TARGET_RATE, DEADLINE, BET_AMOUNT);
            // Win = tag 1
            var redeemer = PlutusData.constr(1);

            BigInteger totalPot = BET_AMOUNT.multiply(BigInteger.TWO);
            var spentRef = TestDataBuilder.randomTxOutRef_typed();
            var spentInput = new TxInInfo(spentRef,
                    new TxOut(scriptAddress(),
                            Value.lovelace(totalPot),
                            new OutputDatum.OutputDatumInline(datum),
                            Optional.empty()));

            // Oracle reference input with price above target
            BigInteger oraclePrice = TARGET_RATE.add(BigInteger.valueOf(1000)); // above target
            BigInteger oracleTimestamp = BigInteger.valueOf(1_699_999_000_000L);
            BigInteger oracleExpiry = DEADLINE; // valid until deadline
            var oracleDatumData = oracleDatum(oraclePrice, oracleTimestamp, oracleExpiry);

            // Oracle address uses ORACLE_VKH as script credential
            var oracleAddress = new Address(
                    new Credential.ScriptCredential(new ScriptHash(ORACLE_VKH)),
                    Optional.empty());
            var oracleRef = TestDataBuilder.randomTxOutRef_typed();
            var oracleRefInput = new TxInInfo(oracleRef,
                    new TxOut(oracleAddress,
                            Value.lovelace(BigInteger.valueOf(2_000_000)),
                            new OutputDatum.OutputDatumInline(oracleDatumData),
                            Optional.empty()));

            // Player gets the full pot
            var playerOutput = new TxOut(pubKeyAddress(PLAYER),
                    Value.lovelace(totalPot),
                    new OutputDatum.NoOutputDatum(), Optional.empty());

            var ctx = spendingContext(spentRef, datum)
                    .redeemer(redeemer)
                    .input(spentInput)
                    .referenceInput(oracleRefInput)
                    .output(playerOutput)
                    .signer(PLAYER)
                    .validRange(Interval.before(DEADLINE))
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("win_priceAboveTarget_valid_passes", result);
        }

        @Test
        void win_priceBelowTarget_fails() throws Exception {
            var compiled = compileValidator(CfPriceBetValidator.class);
            var program = compiled.program();

            var datum = priceBetDatum(OWNER, PLAYER, ORACLE_VKH, TARGET_RATE, DEADLINE, BET_AMOUNT);
            var redeemer = PlutusData.constr(1);

            BigInteger totalPot = BET_AMOUNT.multiply(BigInteger.TWO);
            var spentRef = TestDataBuilder.randomTxOutRef_typed();
            var spentInput = new TxInInfo(spentRef,
                    new TxOut(scriptAddress(),
                            Value.lovelace(totalPot),
                            new OutputDatum.OutputDatumInline(datum),
                            Optional.empty()));

            // Oracle with price below target
            BigInteger oraclePrice = TARGET_RATE.subtract(BigInteger.valueOf(1000)); // below target
            BigInteger oracleTimestamp = BigInteger.valueOf(1_699_999_000_000L);
            BigInteger oracleExpiry = DEADLINE;
            var oracleDatumData = oracleDatum(oraclePrice, oracleTimestamp, oracleExpiry);

            var oracleAddress = new Address(
                    new Credential.ScriptCredential(new ScriptHash(ORACLE_VKH)),
                    Optional.empty());
            var oracleRef = TestDataBuilder.randomTxOutRef_typed();
            var oracleRefInput = new TxInInfo(oracleRef,
                    new TxOut(oracleAddress,
                            Value.lovelace(BigInteger.valueOf(2_000_000)),
                            new OutputDatum.OutputDatumInline(oracleDatumData),
                            Optional.empty()));

            var playerOutput = new TxOut(pubKeyAddress(PLAYER),
                    Value.lovelace(totalPot),
                    new OutputDatum.NoOutputDatum(), Optional.empty());

            var ctx = spendingContext(spentRef, datum)
                    .redeemer(redeemer)
                    .input(spentInput)
                    .referenceInput(oracleRefInput)
                    .output(playerOutput)
                    .signer(PLAYER)
                    .validRange(Interval.before(DEADLINE))
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertFailure(result);
        }
    }

    @Nested
    class TimeoutTests {

        @Test
        void timeout_afterDeadline_ownerReclaims_passes() throws Exception {
            var compiled = compileValidator(CfPriceBetValidator.class);
            var program = compiled.program();

            var datum = priceBetDatum(OWNER, PLAYER, ORACLE_VKH, TARGET_RATE, DEADLINE, BET_AMOUNT);
            // Timeout = tag 2
            var redeemer = PlutusData.constr(2);

            BigInteger totalPot = BET_AMOUNT.multiply(BigInteger.TWO);
            var spentRef = TestDataBuilder.randomTxOutRef_typed();
            var spentInput = new TxInInfo(spentRef,
                    new TxOut(scriptAddress(),
                            Value.lovelace(totalPot),
                            new OutputDatum.OutputDatumInline(datum),
                            Optional.empty()));

            // Owner gets the full pot
            var ownerOutput = new TxOut(pubKeyAddress(OWNER),
                    Value.lovelace(totalPot),
                    new OutputDatum.NoOutputDatum(), Optional.empty());

            var ctx = spendingContext(spentRef, datum)
                    .redeemer(redeemer)
                    .input(spentInput)
                    .output(ownerOutput)
                    .signer(OWNER)
                    .validRange(Interval.after(DEADLINE.add(BigInteger.ONE)))
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("timeout_afterDeadline_ownerReclaims_passes", result);
        }

        @Test
        void timeout_beforeDeadline_fails() throws Exception {
            var compiled = compileValidator(CfPriceBetValidator.class);
            var program = compiled.program();

            var datum = priceBetDatum(OWNER, PLAYER, ORACLE_VKH, TARGET_RATE, DEADLINE, BET_AMOUNT);
            var redeemer = PlutusData.constr(2);

            BigInteger totalPot = BET_AMOUNT.multiply(BigInteger.TWO);
            var spentRef = TestDataBuilder.randomTxOutRef_typed();
            var spentInput = new TxInInfo(spentRef,
                    new TxOut(scriptAddress(),
                            Value.lovelace(totalPot),
                            new OutputDatum.OutputDatumInline(datum),
                            Optional.empty()));

            var ownerOutput = new TxOut(pubKeyAddress(OWNER),
                    Value.lovelace(totalPot),
                    new OutputDatum.NoOutputDatum(), Optional.empty());

            var ctx = spendingContext(spentRef, datum)
                    .redeemer(redeemer)
                    .input(spentInput)
                    .output(ownerOutput)
                    .signer(OWNER)
                    .validRange(Interval.before(DEADLINE))
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
