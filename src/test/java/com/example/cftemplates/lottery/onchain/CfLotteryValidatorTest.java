package com.example.cftemplates.lottery.onchain;

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

class CfLotteryValidatorTest extends ContractTest {

    static final byte[] PLAYER1 = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
            11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
            21, 22, 23, 24, 25, 26, 27, 28};
    static final byte[] PLAYER2 = new byte[]{31, 32, 33, 34, 35, 36, 37, 38, 39, 40,
            41, 42, 43, 44, 45, 46, 47, 48, 49, 50,
            51, 52, 53, 54, 55, 56, 57, 58};
    static final byte[] EMPTY_BYTES = new byte[0];
    static final BigInteger GAME_INDEX = BigInteger.valueOf(42);
    static final BigInteger END_REVEAL = BigInteger.valueOf(1_700_000_000_000L);
    static final BigInteger DELTA = BigInteger.valueOf(3_600_000); // 1 hour
    static final BigInteger POT_AMOUNT = BigInteger.valueOf(20_000_000);

    // Secret values for commits
    static final byte[] SECRET1 = "player1_secret_value".getBytes();
    static final byte[] SECRET2 = "player2_secret_value".getBytes();
    static byte[] COMMIT1;
    static byte[] COMMIT2;

    // Script hash for the lottery script address
    static final byte[] SCRIPT_HASH = new byte[]{(byte) 0xB1, (byte) 0xB2, (byte) 0xB3, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0};

    @BeforeAll
    static void setup() {
        initCrypto();
        COMMIT1 = CryptoLib.blake2b_256(SECRET1);
        COMMIT2 = CryptoLib.blake2b_256(SECRET2);
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

    // LotteryDatum = Constr(0, [player1, player2, commit1, commit2, n1, n2, endReveal, delta])
    static PlutusData lotteryDatum(byte[] p1, byte[] p2, byte[] c1, byte[] c2,
                                   byte[] n1, byte[] n2, BigInteger endReveal, BigInteger delta) {
        return PlutusData.constr(0,
                PlutusData.bytes(p1), PlutusData.bytes(p2),
                PlutusData.bytes(c1), PlutusData.bytes(c2),
                PlutusData.bytes(n1), PlutusData.bytes(n2),
                PlutusData.integer(endReveal), PlutusData.integer(delta));
    }

    @Nested
    class MintCreateTests {

        @Test
        void create_bothSign_validCommits_passes() throws Exception {
            var compiled = compileValidator(CfLotteryValidator.class);
            var program = compiled.program()
                    .applyParams(PlutusData.integer(GAME_INDEX));

            // Create = tag 0
            var redeemer = PlutusData.constr(0);

            var policyId = new PolicyId(SCRIPT_HASH);

            // Datum with commitments, n1=n2=empty (not yet revealed)
            var datum = lotteryDatum(PLAYER1, PLAYER2, COMMIT1, COMMIT2,
                    EMPTY_BYTES, EMPTY_BYTES, END_REVEAL, DELTA);

            // Mint 1 LOTTERY_TOKEN
            var tokenName = new TokenName(new byte[]{0x4C, 0x4F, 0x54}); // "LOT"
            var mintValue = Value.singleton(policyId, tokenName, BigInteger.ONE);

            // Output to script address with the token and datum
            var outputValue = Value.lovelace(POT_AMOUNT)
                    .merge(Value.singleton(policyId, tokenName, BigInteger.ONE));
            var gameOutput = new TxOut(scriptAddress(),
                    outputValue,
                    new OutputDatum.OutputDatumInline(datum),
                    Optional.empty());

            var ctx = mintingContext(policyId)
                    .redeemer(redeemer)
                    .mint(mintValue)
                    .output(gameOutput)
                    .signer(PLAYER1)
                    .signer(PLAYER2)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("create_bothSign_validCommits_passes", result);
        }

        @Test
        void create_emptyCommit_fails() throws Exception {
            var compiled = compileValidator(CfLotteryValidator.class);
            var program = compiled.program()
                    .applyParams(PlutusData.integer(GAME_INDEX));

            var redeemer = PlutusData.constr(0);
            var policyId = new PolicyId(SCRIPT_HASH);

            // commit1 is empty - should fail
            var datum = lotteryDatum(PLAYER1, PLAYER2, EMPTY_BYTES, COMMIT2,
                    EMPTY_BYTES, EMPTY_BYTES, END_REVEAL, DELTA);

            var tokenName = new TokenName(new byte[]{0x4C, 0x4F, 0x54});
            var mintValue = Value.singleton(policyId, tokenName, BigInteger.ONE);

            var outputValue = Value.lovelace(POT_AMOUNT)
                    .merge(Value.singleton(policyId, tokenName, BigInteger.ONE));
            var gameOutput = new TxOut(scriptAddress(),
                    outputValue,
                    new OutputDatum.OutputDatumInline(datum),
                    Optional.empty());

            var ctx = mintingContext(policyId)
                    .redeemer(redeemer)
                    .mint(mintValue)
                    .output(gameOutput)
                    .signer(PLAYER1)
                    .signer(PLAYER2)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertFailure(result);
        }
    }

    @Nested
    class Reveal1Tests {

        @Test
        void reveal1_validHash_passes() throws Exception {
            var compiled = compileValidator(CfLotteryValidator.class);
            var program = compiled.program()
                    .applyParams(PlutusData.integer(GAME_INDEX));

            // Datum: n1=empty (not yet revealed), commit1=blake2b(SECRET1)
            var datum = lotteryDatum(PLAYER1, PLAYER2, COMMIT1, COMMIT2,
                    EMPTY_BYTES, EMPTY_BYTES, END_REVEAL, DELTA);
            // Reveal1(n1) = Constr(0, [n1])
            var redeemer = PlutusData.constr(0, PlutusData.bytes(SECRET1));

            var spentRef = TestDataBuilder.randomTxOutRef_typed();
            var policyId = new PolicyId(SCRIPT_HASH);
            var tokenName = new TokenName(new byte[]{0x4C, 0x4F, 0x54});
            var inputValue = Value.lovelace(POT_AMOUNT)
                    .merge(Value.singleton(policyId, tokenName, BigInteger.ONE));

            var spentInput = new TxInInfo(spentRef,
                    new TxOut(scriptAddress(),
                            inputValue,
                            new OutputDatum.OutputDatumInline(datum),
                            Optional.empty()));

            // Continuing output at same script address (not consumed)
            var newDatum = lotteryDatum(PLAYER1, PLAYER2, COMMIT1, COMMIT2,
                    SECRET1, EMPTY_BYTES, END_REVEAL, DELTA);
            var continuingOutput = new TxOut(scriptAddress(),
                    inputValue,
                    new OutputDatum.OutputDatumInline(newDatum),
                    Optional.empty());

            var ctx = spendingContext(spentRef, datum)
                    .redeemer(redeemer)
                    .input(spentInput)
                    .output(continuingOutput)
                    .signer(PLAYER1)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("reveal1_validHash_passes", result);
        }

        @Test
        void reveal1_wrongHash_fails() throws Exception {
            var compiled = compileValidator(CfLotteryValidator.class);
            var program = compiled.program()
                    .applyParams(PlutusData.integer(GAME_INDEX));

            var datum = lotteryDatum(PLAYER1, PLAYER2, COMMIT1, COMMIT2,
                    EMPTY_BYTES, EMPTY_BYTES, END_REVEAL, DELTA);
            // Reveal with wrong secret
            byte[] wrongSecret = "wrong_secret".getBytes();
            var redeemer = PlutusData.constr(0, PlutusData.bytes(wrongSecret));

            var spentRef = TestDataBuilder.randomTxOutRef_typed();
            var policyId = new PolicyId(SCRIPT_HASH);
            var tokenName = new TokenName(new byte[]{0x4C, 0x4F, 0x54});
            var inputValue = Value.lovelace(POT_AMOUNT)
                    .merge(Value.singleton(policyId, tokenName, BigInteger.ONE));

            var spentInput = new TxInInfo(spentRef,
                    new TxOut(scriptAddress(),
                            inputValue,
                            new OutputDatum.OutputDatumInline(datum),
                            Optional.empty()));

            var continuingOutput = new TxOut(scriptAddress(),
                    inputValue,
                    new OutputDatum.OutputDatumInline(datum),
                    Optional.empty());

            var ctx = spendingContext(spentRef, datum)
                    .redeemer(redeemer)
                    .input(spentInput)
                    .output(continuingOutput)
                    .signer(PLAYER1)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertFailure(result);
        }
    }

    @Nested
    class SettleTests {

        @Test
        void settle_bothRevealed_winnerGets_passes() throws Exception {
            var compiled = compileValidator(CfLotteryValidator.class);
            var program = compiled.program()
                    .applyParams(PlutusData.integer(GAME_INDEX));

            // Datum with both n1 and n2 revealed
            var datum = lotteryDatum(PLAYER1, PLAYER2, COMMIT1, COMMIT2,
                    SECRET1, SECRET2, END_REVEAL, DELTA);
            // Settle = Constr(4, [])
            var redeemer = PlutusData.constr(4);

            var spentRef = TestDataBuilder.randomTxOutRef_typed();
            var policyId = new PolicyId(SCRIPT_HASH);
            var tokenName = new TokenName(new byte[]{0x4C, 0x4F, 0x54});
            var inputValue = Value.lovelace(POT_AMOUNT)
                    .merge(Value.singleton(policyId, tokenName, BigInteger.ONE));

            var spentInput = new TxInInfo(spentRef,
                    new TxOut(scriptAddress(),
                            inputValue,
                            new OutputDatum.OutputDatumInline(datum),
                            Optional.empty()));

            // Determine winner using same logic as validator:
            // v1 = byteStringToInteger(false, n1), v2 = byteStringToInteger(false, n2)
            // (v1 + v2) % 2 == 1 => player1 wins, else player2
            BigInteger v1 = new BigInteger(1, SECRET1);
            BigInteger v2 = new BigInteger(1, SECRET2);
            BigInteger sum = v1.add(v2);
            boolean player1Wins = sum.remainder(BigInteger.TWO).equals(BigInteger.ONE);
            byte[] winner = player1Wins ? PLAYER1 : PLAYER2;

            // No output to script address (consumed) + burn token
            var winnerOutput = new TxOut(pubKeyAddress(winner),
                    Value.lovelace(POT_AMOUNT),
                    new OutputDatum.NoOutputDatum(), Optional.empty());

            var burnValue = Value.singleton(policyId, tokenName, BigInteger.ONE.negate());

            var ctx = spendingContext(spentRef, datum)
                    .redeemer(redeemer)
                    .input(spentInput)
                    .output(winnerOutput)
                    .signer(winner)
                    .mint(burnValue)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("settle_bothRevealed_winnerGets_passes", result);
        }
    }

    private static void logBudget(String testName, com.bloxbean.cardano.julc.vm.EvalResult result) {
        var budget = result.budgetConsumed();
        System.out.println("[" + testName + "] CPU: " + budget.cpuSteps() + ", Mem: " + budget.memoryUnits());
    }
}
