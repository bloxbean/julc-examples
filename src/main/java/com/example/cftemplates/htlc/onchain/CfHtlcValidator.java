package com.example.cftemplates.htlc.onchain;

import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.ledger.TxInfo;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.Param;
import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;
import com.bloxbean.cardano.julc.stdlib.lib.ContextsLib;
import com.bloxbean.cardano.julc.stdlib.lib.CryptoLib;
import com.bloxbean.cardano.julc.stdlib.lib.IntervalLib;

import java.math.BigInteger;
import java.util.Optional;

import com.bloxbean.cardano.julc.core.PlutusData;

/**
 * Hash Time-Locked Contract (HTLC) — funds can be claimed by revealing
 * a secret (preimage of a SHA-256 hash) before expiration, or reclaimed
 * by the owner after expiration.
 * <p>
 * Based on: cardano-template-and-ecosystem-monitoring/htlc
 */
@SpendingValidator
public class CfHtlcValidator {

    @Param
    static byte[] secretHash;

    @Param
    static BigInteger expiration;

    @Param
    static byte[] owner;

    public sealed interface HtlcAction permits Guess, Withdraw {}
    public record Guess(byte[] answer) implements HtlcAction {}
    public record Withdraw() implements HtlcAction {}

    @Entrypoint
    public static boolean validate(Optional<PlutusData> datum, HtlcAction redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        return switch (redeemer) {
            case Guess g -> validateGuess(g, txInfo);
            case Withdraw w -> validateWithdraw(txInfo);
        };
    }

    static boolean validateGuess(Guess guess, TxInfo txInfo) {
        boolean hashMatches = CryptoLib.sha2_256(guess.answer()).equals(secretHash);
        BigInteger upperBound = IntervalLib.finiteUpperBound(txInfo.validRange());
        boolean beforeExpiration = upperBound.compareTo(expiration) <= 0;
        return hashMatches && beforeExpiration;
    }

    static boolean validateWithdraw(TxInfo txInfo) {
        boolean ownerSigned = ContextsLib.signedBy(txInfo, owner);
        BigInteger lowerBound = IntervalLib.finiteLowerBound(txInfo.validRange());
        boolean afterExpiration = lowerBound.compareTo(expiration) >= 0;
        return ownerSigned && afterExpiration;
    }
}