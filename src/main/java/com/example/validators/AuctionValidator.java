package com.example.validators;

import org.julclang.stdlib.annotation.SpendingValidator;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.ledger.ScriptContext;
import org.julclang.ledger.TxInfo;
import org.julclang.ledger.PubKeyHash;
import org.julclang.stdlib.lib.ContextsLib;

import java.math.BigInteger;

/**
 * Auction validator demonstrating sealed interface (ADT) redeemer dispatch.
 * <p>
 * Two redeemer variants:
 * - Bid(bidder, amount): bidder must sign, amount >= reservePrice
 * - Close(): seller must sign
 * <p>
 * Features demonstrated:
 * - Sealed interface as typed redeemer (2 variants)
 * - Switch expression pattern matching
 * - Different field counts per variant (2 fields vs 0 fields)
 */
@SpendingValidator
public class AuctionValidator {

    record AuctionDatum(byte[] seller, BigInteger reservePrice) {}

    sealed interface AuctionAction permits Bid, Close {}
    record Bid(byte[] bidder, BigInteger amount) implements AuctionAction {}
    record Close() implements AuctionAction {}

    @Entrypoint
    public static boolean validate(AuctionDatum datum, AuctionAction redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        ContextsLib.trace("Auction validate");
        return switch (redeemer) {
            case Bid b -> {
                ContextsLib.trace("Bid path");
                boolean bidderSigned = txInfo.signatories().contains(PubKeyHash.of(b.bidder()));
                boolean meetsReserve = b.amount().compareTo(datum.reservePrice()) >= 0;
                yield bidderSigned && meetsReserve;
            }
            case Close c -> {
                ContextsLib.trace("Close path");
                yield txInfo.signatories().contains(PubKeyHash.of(datum.seller()));
            }
        };
    }
}
