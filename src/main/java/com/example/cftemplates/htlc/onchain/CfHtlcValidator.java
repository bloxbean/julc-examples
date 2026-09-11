package com.example.cftemplates.htlc.onchain;

import org.julclang.ledger.ScriptContext;
import org.julclang.ledger.TxInfo;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.Param;
import org.julclang.stdlib.annotation.SpendingValidator;
import org.julclang.stdlib.lib.ContextsLib;
import org.julclang.stdlib.lib.CryptoLib;
import org.julclang.stdlib.lib.IntervalLib;

import java.math.BigInteger;
import java.util.Optional;

import org.julclang.core.PlutusData;

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