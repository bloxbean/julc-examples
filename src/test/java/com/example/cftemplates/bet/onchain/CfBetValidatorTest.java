package com.example.cftemplates.bet.onchain;

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

class CfBetValidatorTest extends ContractTest {

    static final byte[] PLAYER1 = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
            11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
            21, 22, 23, 24, 25, 26, 27, 28};
    static final byte[] PLAYER2 = new byte[]{31, 32, 33, 34, 35, 36, 37, 38, 39, 40,
            41, 42, 43, 44, 45, 46, 47, 48, 49, 50,
            51, 52, 53, 54, 55, 56, 57, 58};
    static final byte[] ORACLE = new byte[]{61, 62, 63, 64, 65, 66, 67, 68, 69, 70,
            71, 72, 73, 74, 75, 76, 77, 78, 79, 80,
            81, 82, 83, 84, 85, 86, 87, 88};
    static final byte[] EMPTY_BYTES = new byte[0];
    static final BigInteger EXPIRATION = BigInteger.valueOf(1_700_000_000_000L);
    static final BigInteger BET_AMOUNT = BigInteger.valueOf(10_000_000);

    // Script hash for the bet script address
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

    // BetDatum = Constr(0, [player1, player2, oracle, expiration])
    static PlutusData betDatum(byte[] p1, byte[] p2) {
        return PlutusData.constr(0,
                PlutusData.bytes(p1), PlutusData.bytes(p2),
                PlutusData.bytes(ORACLE), PlutusData.integer(EXPIRATION));
    }

    @Nested
    class MintTests {

        @Test
        void mint_player1Signs_noPlayer2_passes() throws Exception {
            var compiled = compileValidator(CfBetValidator.class);
            var program = compiled.program();

            var redeemer = PlutusData.constr(0);
            var datum = betDatum(PLAYER1, EMPTY_BYTES);

            // The mint entrypoint uses findOutputToScript which matches by policyId bytes == credentialHash
            var policyId = new PolicyId(SCRIPT_HASH);

            var betOutput = new TxOut(scriptAddress(),
                    Value.lovelace(BET_AMOUNT),
                    new OutputDatum.OutputDatumInline(datum),
                    Optional.empty());

            var ctx = mintingContext(policyId)
                    .redeemer(redeemer)
                    .signer(PLAYER1)
                    .output(betOutput)
                    .validRange(Interval.before(EXPIRATION))
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("mint_player1Signs_noPlayer2_passes", result);
        }

        @Test
        void mint_oracleSameAsPlayer1_fails() throws Exception {
            var compiled = compileValidator(CfBetValidator.class);
            var program = compiled.program();

            var redeemer = PlutusData.constr(0);
            // oracle == player1 should fail
            var datum = PlutusData.constr(0,
                    PlutusData.bytes(PLAYER1), PlutusData.bytes(EMPTY_BYTES),
                    PlutusData.bytes(PLAYER1), PlutusData.integer(EXPIRATION));

            var policyId = new PolicyId(SCRIPT_HASH);

            var betOutput = new TxOut(scriptAddress(),
                    Value.lovelace(BET_AMOUNT),
                    new OutputDatum.OutputDatumInline(datum),
                    Optional.empty());

            var ctx = mintingContext(policyId)
                    .redeemer(redeemer)
                    .signer(PLAYER1)
                    .output(betOutput)
                    .validRange(Interval.before(EXPIRATION))
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertFailure(result);
        }
    }

    @Nested
    class AnnounceWinnerTests {

        @Test
        void announceWinner_oracleSigns_afterExpiration_passes() throws Exception {
            var compiled = compileValidator(CfBetValidator.class);
            var program = compiled.program();

            var datum = betDatum(PLAYER1, PLAYER2);
            // AnnounceWinner(winner) = Constr(1, [winner])
            var redeemer = PlutusData.constr(1, PlutusData.bytes(PLAYER1));

            var spentRef = TestDataBuilder.randomTxOutRef_typed();
            var spentInput = new TxInInfo(spentRef,
                    new TxOut(scriptAddress(),
                            Value.lovelace(BET_AMOUNT.multiply(BigInteger.TWO)),
                            new OutputDatum.OutputDatumInline(datum),
                            Optional.empty()));

            // Winner gets the pot
            var winnerOutput = new TxOut(pubKeyAddress(PLAYER1),
                    Value.lovelace(BET_AMOUNT.multiply(BigInteger.TWO)),
                    new OutputDatum.NoOutputDatum(), Optional.empty());

            var ctx = spendingContext(spentRef, datum)
                    .redeemer(redeemer)
                    .input(spentInput)
                    .output(winnerOutput)
                    .signer(ORACLE)
                    .validRange(Interval.after(EXPIRATION))
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("announceWinner_oracleSigns_afterExpiration_passes", result);
        }

        @Test
        void announceWinner_beforeExpiration_fails() throws Exception {
            var compiled = compileValidator(CfBetValidator.class);
            var program = compiled.program();

            var datum = betDatum(PLAYER1, PLAYER2);
            var redeemer = PlutusData.constr(1, PlutusData.bytes(PLAYER1));

            var spentRef = TestDataBuilder.randomTxOutRef_typed();
            var spentInput = new TxInInfo(spentRef,
                    new TxOut(scriptAddress(),
                            Value.lovelace(BET_AMOUNT.multiply(BigInteger.TWO)),
                            new OutputDatum.OutputDatumInline(datum),
                            Optional.empty()));

            var winnerOutput = new TxOut(pubKeyAddress(PLAYER1),
                    Value.lovelace(BET_AMOUNT.multiply(BigInteger.TWO)),
                    new OutputDatum.NoOutputDatum(), Optional.empty());

            var ctx = spendingContext(spentRef, datum)
                    .redeemer(redeemer)
                    .input(spentInput)
                    .output(winnerOutput)
                    .signer(ORACLE)
                    .validRange(Interval.before(EXPIRATION))
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertFailure(result);
        }

        @Test
        void announceWinner_nonOracle_fails() throws Exception {
            var compiled = compileValidator(CfBetValidator.class);
            var program = compiled.program();

            var datum = betDatum(PLAYER1, PLAYER2);
            var redeemer = PlutusData.constr(1, PlutusData.bytes(PLAYER1));

            var spentRef = TestDataBuilder.randomTxOutRef_typed();
            var spentInput = new TxInInfo(spentRef,
                    new TxOut(scriptAddress(),
                            Value.lovelace(BET_AMOUNT.multiply(BigInteger.TWO)),
                            new OutputDatum.OutputDatumInline(datum),
                            Optional.empty()));

            var winnerOutput = new TxOut(pubKeyAddress(PLAYER1),
                    Value.lovelace(BET_AMOUNT.multiply(BigInteger.TWO)),
                    new OutputDatum.NoOutputDatum(), Optional.empty());

            var ctx = spendingContext(spentRef, datum)
                    .redeemer(redeemer)
                    .input(spentInput)
                    .output(winnerOutput)
                    .signer(PLAYER1) // not oracle
                    .validRange(Interval.after(EXPIRATION))
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertFailure(result);
        }
    }

    @Nested
    class JoinTests {

        @Test
        void join_player2Signs_potDoubled_passes() throws Exception {
            var compiled = compileValidator(CfBetValidator.class);
            var program = compiled.program();

            var datum = betDatum(PLAYER1, EMPTY_BYTES);
            // Join = tag 0
            var redeemer = PlutusData.constr(0);
            var newDatum = betDatum(PLAYER1, PLAYER2);

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
                    .signer(PLAYER2)
                    .validRange(Interval.before(EXPIRATION))
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("join_player2Signs_potDoubled_passes", result);
        }

        @Test
        void join_sameAsPlayer1_fails() throws Exception {
            var compiled = compileValidator(CfBetValidator.class);
            var program = compiled.program();

            var datum = betDatum(PLAYER1, EMPTY_BYTES);
            var redeemer = PlutusData.constr(0);
            var newDatum = betDatum(PLAYER1, PLAYER1); // same player

            var spentRef = TestDataBuilder.randomTxOutRef_typed();
            var spentInput = new TxInInfo(spentRef,
                    new TxOut(scriptAddress(),
                            Value.lovelace(BET_AMOUNT),
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
                    .signer(PLAYER1)
                    .validRange(Interval.before(EXPIRATION))
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
