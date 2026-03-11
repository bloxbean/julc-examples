package com.example.cftemplates.lottery.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.MultiValidator;
import com.bloxbean.cardano.julc.stdlib.annotation.Param;
import com.bloxbean.cardano.julc.stdlib.annotation.Purpose;
import com.bloxbean.cardano.julc.stdlib.lib.*;
import com.bloxbean.cardano.julc.stdlib.lib.ByteStringLib;

import java.math.BigInteger;
import java.util.Optional;

/**
 * Lottery validator — commit-reveal game between two players.
 * <p>
 * MINT Create: Both players sign, 1 LOTTERY_TOKEN minted, datum has commitments.
 * MINT BurnToken: Burn 1 LOTTERY_TOKEN.
 * <p>
 * SPEND Reveal1: Player1 reveals n1 (blake2b matches commit1), continuing output.
 * SPEND Reveal2: Player2 reveals n2 (after n1 revealed), continuing output.
 * SPEND Timeout1: Player1 didn't reveal in time, player2 wins.
 * SPEND Timeout2: Player2 didn't reveal after player1, player1 wins.
 * SPEND Settle: Both revealed, parity of sum determines winner.
 * <p>
 * Based on: cardano-template-and-ecosystem-monitoring/lottery
 */
@MultiValidator
public class CfLotteryValidator {

    @Param static BigInteger gameIndex;

    public record LotteryDatum(byte[] player1, byte[] player2,
                        byte[] commit1, byte[] commit2,
                        byte[] n1, byte[] n2,
                        BigInteger endReveal, BigInteger delta) {}

    // Mint redeemer
    public sealed interface MintAction permits Create, BurnToken {}
    public record Create() implements MintAction {}
    public record BurnToken() implements MintAction {}

    // Spend redeemer
    public sealed interface LotteryAction permits Reveal1, Reveal2, Timeout1, Timeout2, Settle {}
    public record Reveal1(byte[] n1) implements LotteryAction {}
    public record Reveal2(byte[] n2) implements LotteryAction {}
    public record Timeout1() implements LotteryAction {}
    public record Timeout2() implements LotteryAction {}
    public record Settle() implements LotteryAction {}

    @Entrypoint(purpose = Purpose.MINT)
    public static boolean mint(MintAction redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        ScriptInfo.MintingScript mintInfo = (ScriptInfo.MintingScript) ctx.scriptInfo();
        byte[] policyBytes = (byte[])(Object) mintInfo.policyId();

        return switch (redeemer) {
            case Create c -> handleCreate(txInfo, policyBytes);
            case BurnToken b -> handleBurn(txInfo, policyBytes);
        };
    }

    @Entrypoint(purpose = Purpose.SPEND)
    public static boolean spend(Optional<PlutusData> datum, LotteryAction redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();

        TxOut ownInput = ContextsLib.findOwnInput(ctx).get().resolved();
        PlutusData datumData = OutputLib.getInlineDatum(ownInput);
        LotteryDatum ld = (LotteryDatum)(Object) datumData;

        // Get own script hash (= policy ID for @MultiValidator)
        byte[] ownHash = ContextsLib.ownHash(ctx);

        return switch (redeemer) {
            case Reveal1 r -> handleReveal1(txInfo, ownInput, ld, r.n1());
            case Reveal2 r -> handleReveal2(txInfo, ownInput, ld, r.n2());
            case Timeout1 t -> handleTimeout1(txInfo, ownInput, ld, ownHash);
            case Timeout2 t -> handleTimeout2(txInfo, ownInput, ld, ownHash);
            case Settle s -> handleSettle(txInfo, ownInput, ld, ownHash);
        };
    }

    // --- Mint handlers ---

    static final byte[] LOTTERY_TOKEN = "LOTTERY_TOKEN".getBytes();

    static boolean handleCreate(TxInfo txInfo, byte[] policyBytes) {
        // Exactly 1 LOTTERY_TOKEN minted
        BigInteger qty = ValuesLib.assetOf(txInfo.mint(), policyBytes, LOTTERY_TOKEN);
        boolean oneMinted = qty.compareTo(BigInteger.ONE) == 0;

        // Find output with the minted token at any address
        // (off-chain sends it to the lottery script address)
        TxOut gameOutput = findOutputWithPolicy(txInfo.outputs(), policyBytes);
        PlutusData datumData = OutputLib.getInlineDatum(gameOutput);
        LotteryDatum ld = (LotteryDatum)(Object) datumData;

        // Both players must sign
        boolean p1Signed = ContextsLib.signedBy(txInfo, ld.player1());
        boolean p2Signed = ContextsLib.signedBy(txInfo, ld.player2());

        // Commitments must be non-empty
        boolean c1Valid = !ld.commit1().equals(Builtins.emptyByteString());
        boolean c2Valid = !ld.commit2().equals(Builtins.emptyByteString());

        return oneMinted && p1Signed && p2Signed && c1Valid && c2Valid;
    }

    static boolean handleBurn(TxInfo txInfo, byte[] policyBytes) {
        // Exactly 1 LOTTERY_TOKEN burned (qty = -1)
        boolean burned = ValuesLib.assetOf(txInfo.mint(), policyBytes, LOTTERY_TOKEN)
                .compareTo(BigInteger.ONE.negate()) == 0;
        return burned;
    }

    // --- Spend handlers ---

    static boolean handleReveal1(TxInfo txInfo, TxOut ownInput, LotteryDatum ld, byte[] revealed) {
        // Player1 must sign
        boolean p1Signed = ContextsLib.signedBy(txInfo, ld.player1());
        // n1 must be empty (not yet revealed)
        boolean n1Empty = ld.n1().equals(Builtins.emptyByteString());
        // Revealed value must be non-empty
        boolean revealNonEmpty = !revealed.equals(Builtins.emptyByteString());
        // blake2b(revealed) must match commit1
        boolean commitMatch = CryptoLib.blake2b_256(revealed).equals(ld.commit1());
        // Script output must continue (not consumed)
        boolean continues = !isScriptConsumed(txInfo.outputs(), ownInput.address());

        return p1Signed && n1Empty && revealNonEmpty && commitMatch && continues;
    }

    static boolean handleReveal2(TxInfo txInfo, TxOut ownInput, LotteryDatum ld, byte[] revealed) {
        // Player2 must sign
        boolean p2Signed = ContextsLib.signedBy(txInfo, ld.player2());
        // n1 must already be revealed
        boolean n1Revealed = !ld.n1().equals(Builtins.emptyByteString());
        // n2 must be empty (not yet revealed)
        boolean n2Empty = ld.n2().equals(Builtins.emptyByteString());
        // Revealed value must be non-empty
        boolean revealNonEmpty = !revealed.equals(Builtins.emptyByteString());
        // blake2b(revealed) must match commit2
        boolean commitMatch = CryptoLib.blake2b_256(revealed).equals(ld.commit2());
        // Script output must continue
        boolean continues = !isScriptConsumed(txInfo.outputs(), ownInput.address());

        return p2Signed && n1Revealed && n2Empty && revealNonEmpty && commitMatch && continues;
    }

    static boolean handleTimeout1(TxInfo txInfo, TxOut ownInput, LotteryDatum ld, byte[] ownHash) {
        // n1 not revealed (player1 timed out)
        boolean n1Empty = ld.n1().equals(Builtins.emptyByteString());
        // After end_reveal deadline
        BigInteger lowerBound = IntervalLib.finiteLowerBound(txInfo.validRange());
        boolean afterDeadline = lowerBound.compareTo(ld.endReveal()) >= 0;
        // Player2 must sign (they win)
        boolean p2Signed = ContextsLib.signedBy(txInfo, ld.player2());
        // Script consumed
        boolean consumed = isScriptConsumed(txInfo.outputs(), ownInput.address());
        // Token burned
        boolean burned = ValuesLib.assetOf(txInfo.mint(), ownHash, LOTTERY_TOKEN)
                .compareTo(BigInteger.ONE.negate()) == 0;

        return n1Empty && afterDeadline && p2Signed && consumed && burned;
    }

    static boolean handleTimeout2(TxInfo txInfo, TxOut ownInput, LotteryDatum ld, byte[] ownHash) {
        // n1 revealed but n2 not (player2 timed out)
        boolean n1Revealed = !ld.n1().equals(Builtins.emptyByteString());
        boolean n2Empty = ld.n2().equals(Builtins.emptyByteString());
        // After end_reveal + delta
        BigInteger timeoutDeadline = ld.endReveal().add(ld.delta());
        BigInteger lowerBound = IntervalLib.finiteLowerBound(txInfo.validRange());
        boolean afterDeadline = lowerBound.compareTo(timeoutDeadline) >= 0;
        // Player1 must sign (they win)
        boolean p1Signed = ContextsLib.signedBy(txInfo, ld.player1());
        // Script consumed
        boolean consumed = isScriptConsumed(txInfo.outputs(), ownInput.address());
        // Token burned
        boolean burned = ValuesLib.assetOf(txInfo.mint(), ownHash, LOTTERY_TOKEN)
                .compareTo(BigInteger.ONE.negate()) == 0;

        return n1Revealed && n2Empty && afterDeadline && p1Signed && consumed && burned;
    }

    static boolean handleSettle(TxInfo txInfo, TxOut ownInput, LotteryDatum ld, byte[] ownHash) {
        // Both must be revealed
        boolean n1Revealed = !ld.n1().equals(Builtins.emptyByteString());
        boolean n2Revealed = !ld.n2().equals(Builtins.emptyByteString());
        if (!n1Revealed || !n2Revealed) return false;

        // Determine winner by parity: (v1 + v2) % 2 == 1 → player1 wins
        BigInteger v1 = ByteStringLib.utf8ToInteger(ld.n1());
        BigInteger v2 = ByteStringLib.utf8ToInteger(ld.n2());
        BigInteger sum = v1.add(v2);
        boolean player1Wins = sum.remainder(BigInteger.TWO).equals(BigInteger.ONE);
        byte[] winner = player1Wins ? ld.player1() : ld.player2();

        // Winner must sign
        boolean winnerSigned = ContextsLib.signedBy(txInfo, winner);
        // Script consumed
        boolean consumed = isScriptConsumed(txInfo.outputs(), ownInput.address());
        // Token burned
        boolean burned = ValuesLib.assetOf(txInfo.mint(), ownHash, LOTTERY_TOKEN)
                .compareTo(BigInteger.ONE.negate()) == 0;

        return winnerSigned && consumed && burned;
    }

    // --- Helpers ---

    static boolean isScriptConsumed(JulcList<TxOut> outputs, Address scriptAddr) {
        // If no output goes to the script address, it's consumed
        boolean hasOutput = false;
        for (var output : outputs) {
            if (Builtins.equalsData(output.address(), scriptAddr)) {
                hasOutput = true;
                break;
            }
        }
        return !hasOutput;
    }

    static TxOut findOutputWithPolicy(JulcList<TxOut> outputs, byte[] policyBytes) {
        TxOut result = (TxOut)(Object) Builtins.mkNilData();
        for (var output : outputs) {
            if (ValuesLib.containsPolicy(output.value(), policyBytes)) {
                result = output;
                break;
            }
        }
        return result;
    }
}
