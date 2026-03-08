package com.example.cftemplates.bet.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.MultiValidator;
import com.bloxbean.cardano.julc.stdlib.annotation.Purpose;
import com.bloxbean.cardano.julc.stdlib.lib.AddressLib;
import com.bloxbean.cardano.julc.stdlib.lib.ContextsLib;
import com.bloxbean.cardano.julc.stdlib.lib.IntervalLib;
import com.bloxbean.cardano.julc.stdlib.lib.OutputLib;
import com.bloxbean.cardano.julc.stdlib.lib.ValuesLib;

import java.math.BigInteger;
import java.util.Optional;

/**
 * Bet validator — two-player oracle-resolved bet.
 * <p>
 * Mint: player1 creates bet, player2 slot empty, oracle set.
 * JOIN: player2 joins, matches player1's bet (pot doubles).
 * ANNOUNCE_WINNER: oracle signs after expiration, winner gets pot.
 * <p>
 * Based on: cardano-template-and-ecosystem-monitoring/bet
 */
@MultiValidator
public class CfBetValidator {

    record BetDatum(byte[] player1, byte[] player2, byte[] oracle, BigInteger expiration) {}

    sealed interface BetAction permits Join, AnnounceWinner {}
    record Join() implements BetAction {}
    record AnnounceWinner(byte[] winner) implements BetAction {}

    @Entrypoint(purpose = Purpose.MINT)
    public static boolean mint(PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        ScriptInfo.MintingScript mintInfo = (ScriptInfo.MintingScript) ctx.scriptInfo();
        PolicyId policyId = mintInfo.policyId();

        // Find output to script address
        TxOut betOutput = findOutputToScript(txInfo.outputs(), policyId);
        PlutusData datumData = OutputLib.getInlineDatum(betOutput);
        BetDatum betDatum = (BetDatum)(Object) datumData;

        boolean player1Signed = ContextsLib.signedBy(txInfo, betDatum.player1());
        boolean player2Empty = betDatum.player2().equals(Builtins.emptyByteString());
        boolean oracleNotPlayer1 = !betDatum.oracle().equals(betDatum.player1());
        BigInteger upperBound = IntervalLib.finiteUpperBound(txInfo.validRange());
        boolean notExpired = upperBound.compareTo(betDatum.expiration()) <= 0;

        return player1Signed && player2Empty && oracleNotPlayer1 && notExpired;
    }

    @Entrypoint(purpose = Purpose.SPEND)
    public static boolean spend(Optional<PlutusData> datum, BetAction redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();

        TxOut ownInput = ContextsLib.findOwnInput(ctx).get().resolved();
        BetDatum currentDatum = extractDatumFromInput(ownInput);

        return switch (redeemer) {
            case Join j -> handleJoin(txInfo, ownInput, currentDatum);
            case AnnounceWinner a -> handleAnnounceWinner(txInfo, currentDatum, a.winner());
        };
    }

    static boolean handleJoin(TxInfo txInfo, TxOut ownInput, BetDatum currentDatum) {
        Address scriptAddress = ownInput.address();
        BigInteger inputLovelace = ValuesLib.lovelaceOf(ownInput.value());

        // Find continuing output
        TxOut continuingOutput = findContinuingOutput(txInfo.outputs(), scriptAddress);
        PlutusData outDatumData = OutputLib.getInlineDatum(continuingOutput);
        BetDatum newDatum = (BetDatum)(Object) outDatumData;
        BigInteger outputLovelace = ValuesLib.lovelaceOf(continuingOutput.value());

        byte[] joiningPlayer = newDatum.player2();

        boolean noPlayer2Yet = currentDatum.player2().equals(Builtins.emptyByteString());
        boolean player2Signed = ContextsLib.signedBy(txInfo, joiningPlayer);
        boolean oracleUnchanged = newDatum.oracle().equals(currentDatum.oracle());
        boolean player1Unchanged = newDatum.player1().equals(currentDatum.player1());
        boolean notSameAsPlayer1 = !joiningPlayer.equals(currentDatum.player1());
        boolean notSameAsOracle = !joiningPlayer.equals(currentDatum.oracle());
        boolean potDoubled = outputLovelace.compareTo(inputLovelace.multiply(BigInteger.TWO)) == 0;
        boolean expirationUnchanged = newDatum.expiration().compareTo(currentDatum.expiration()) == 0;
        BigInteger upperBound = IntervalLib.finiteUpperBound(txInfo.validRange());
        boolean notExpired = upperBound.compareTo(newDatum.expiration()) <= 0;

        return noPlayer2Yet && player2Signed && oracleUnchanged
                && player1Unchanged && notSameAsPlayer1 && notSameAsOracle
                && potDoubled && expirationUnchanged && notExpired;
    }

    static boolean handleAnnounceWinner(TxInfo txInfo, BetDatum currentDatum, byte[] winner) {
        byte[] player1 = currentDatum.player1();
        byte[] player2 = currentDatum.player2();
        byte[] oracle = currentDatum.oracle();
        BigInteger expiration = currentDatum.expiration();

        boolean winnerIsPlayer = winner.equals(player1) || winner.equals(player2);
        boolean bothJoined = !player2.equals(Builtins.emptyByteString());
        boolean oracleSigned = ContextsLib.signedBy(txInfo, oracle);
        BigInteger lowerBound = IntervalLib.finiteLowerBound(txInfo.validRange());
        boolean afterExpiration = lowerBound.compareTo(expiration) >= 0;

        // Check payout goes to winner
        boolean payoutCorrect = checkPayoutToWinner(txInfo.outputs(), winner);

        return winnerIsPlayer && bothJoined && oracleSigned && afterExpiration && payoutCorrect;
    }

    static boolean checkPayoutToWinner(JulcList<TxOut> outputs, byte[] winner) {
        boolean found = false;
        for (var output : outputs) {
            byte[] credHash = AddressLib.credentialHash(output.address());
            if (credHash.equals(winner)) {
                found = true;
                break;
            }
        }
        return found;
    }

    static BetDatum extractDatumFromInput(TxOut input) {
        PlutusData datumData = OutputLib.getInlineDatum(input);
        return (BetDatum)(Object) datumData;
    }

    static TxOut findOutputToScript(JulcList<TxOut> outputs, PolicyId policyId) {
        byte[] policyBytes = (byte[])(Object) policyId;
        TxOut result = (TxOut)(Object) Builtins.mkNilData();
        for (var output : outputs) {
            byte[] credHash = AddressLib.credentialHash(output.address());
            if (credHash.equals(policyBytes)) {
                result = output;
                break;
            }
        }
        return result;
    }

    static TxOut findContinuingOutput(JulcList<TxOut> outputs, Address scriptAddr) {
        TxOut result = (TxOut)(Object) Builtins.mkNilData();
        for (var output : outputs) {
            if (Builtins.equalsData(output.address(), scriptAddr)) {
                result = output;
                break;
            }
        }
        return result;
    }
}
