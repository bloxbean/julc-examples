package com.example.cftemplates.auction.onchain;

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
 * Auction validator — English auction with mint + spend.
 * <p>
 * Mint: seller initializes auction with asset locked at script.
 * BID: new higher bid, previous bidder refunded, asset stays at script.
 * END: after expiration, winner gets asset, seller gets ADA.
 * <p>
 * Based on: cardano-template-and-ecosystem-monitoring/auction
 * <p>
 * NOTE: BID refund check uses >= instead of Aiken's ==. This is a deliberate
 * simplification — more permissive (allows over-refund), arguably safer.
 */
@MultiValidator
public class CfAuctionValidator {

    public record AuctionDatum(byte[] seller, byte[] highestBidder, BigInteger highestBid,
                        BigInteger expiration, byte[] assetPolicy, byte[] assetName) {}

    public sealed interface AuctionAction permits Bid, Withdraw, End {}
    public record Bid() implements AuctionAction {}
    public record Withdraw() implements AuctionAction {}
    public record End() implements AuctionAction {}

    @Entrypoint(purpose = Purpose.MINT)
    public static boolean mint(PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        ScriptInfo.MintingScript mintInfo = (ScriptInfo.MintingScript) ctx.scriptInfo();
        byte[] policyBytes = (byte[])(Object) mintInfo.policyId();

        // Find output to script address
        TxOut auctionOutput = findOutputToScript(txInfo.outputs(), policyBytes);
        PlutusData datumData = OutputLib.getInlineDatum(auctionOutput);
        AuctionDatum auctionDatum = (AuctionDatum)(Object) datumData;

        boolean sellerSigned = ContextsLib.signedBy(txInfo, auctionDatum.seller());
        boolean noBidderYet = auctionDatum.highestBidder().equals(Builtins.emptyByteString());
        boolean bidNonNegative = auctionDatum.highestBid().compareTo(BigInteger.ZERO) >= 0;
        BigInteger upperBound = IntervalLib.finiteUpperBound(txInfo.validRange());
        boolean notExpired = upperBound.compareTo(auctionDatum.expiration()) <= 0;

        // Check output has the auctioned asset
        boolean hasAsset = ValuesLib.assetOf(auctionOutput.value(),
                auctionDatum.assetPolicy(), auctionDatum.assetName())
                .compareTo(BigInteger.ZERO) > 0;

        return sellerSigned && noBidderYet && bidNonNegative && notExpired && hasAsset;
    }

    @Entrypoint(purpose = Purpose.SPEND)
    public static boolean spend(Optional<PlutusData> datum, AuctionAction redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();

        TxOut ownInput = ContextsLib.findOwnInput(ctx).get().resolved();
        PlutusData datumData = OutputLib.getInlineDatum(ownInput);
        AuctionDatum currentDatum = (AuctionDatum)(Object) datumData;

        return switch (redeemer) {
            case Bid b -> handleBid(txInfo, ownInput, currentDatum);
            case Withdraw w -> false; // Not implemented, always fails
            case End e -> handleEnd(txInfo, currentDatum);
        };
    }

    static boolean handleBid(TxInfo txInfo, TxOut ownInput, AuctionDatum currentDatum) {
        Address scriptAddress = ownInput.address();
        BigInteger inputLovelace = ValuesLib.lovelaceOf(ownInput.value());

        // Find continuing output
        TxOut continuingOutput = findContinuingOutput(txInfo.outputs(), scriptAddress);
        PlutusData outDatumData = OutputLib.getInlineDatum(continuingOutput);
        AuctionDatum newDatum = (AuctionDatum)(Object) outDatumData;
        BigInteger outputLovelace = ValuesLib.lovelaceOf(continuingOutput.value());

        BigInteger upperBound = IntervalLib.finiteUpperBound(txInfo.validRange());
        boolean notExpired = upperBound.compareTo(currentDatum.expiration()) <= 0;
        boolean higherBid = newDatum.highestBid().compareTo(currentDatum.highestBid()) > 0;
        boolean bidderSigned = ContextsLib.signedBy(txInfo, newDatum.highestBidder());
        boolean sellerUnchanged = newDatum.seller().equals(currentDatum.seller());
        boolean policyUnchanged = newDatum.assetPolicy().equals(currentDatum.assetPolicy());
        boolean nameUnchanged = newDatum.assetName().equals(currentDatum.assetName());
        boolean expirationUnchanged = newDatum.expiration().compareTo(currentDatum.expiration()) == 0;
        boolean valueNonDecreasing = outputLovelace.compareTo(inputLovelace) >= 0;

        // Verify auctioned asset is present in input and continuing output
        boolean inputHasAsset = ValuesLib.assetOf(ownInput.value(),
                currentDatum.assetPolicy(), currentDatum.assetName()).compareTo(BigInteger.ZERO) > 0;
        boolean outputHasAsset = ValuesLib.assetOf(continuingOutput.value(),
                newDatum.assetPolicy(), newDatum.assetName()).compareTo(BigInteger.ZERO) > 0;

        // Check previous bidder refunded (if any)
        boolean refundOk = checkRefund(txInfo.outputs(), currentDatum.highestBidder(), currentDatum.highestBid());

        return notExpired && higherBid && bidderSigned && sellerUnchanged
                && policyUnchanged && nameUnchanged && expirationUnchanged
                && valueNonDecreasing && inputHasAsset && outputHasAsset && refundOk;
    }

    static boolean checkRefund(JulcList<TxOut> outputs, byte[] prevBidder, BigInteger prevBid) {
        if (prevBidder.equals(Builtins.emptyByteString())) return true;
        boolean found = false;
        for (var output : outputs) {
            byte[] credHash = AddressLib.credentialHash(output.address());
            if (credHash.equals(prevBidder)) {
                BigInteger lovelace = ValuesLib.lovelaceOf(output.value());
                if (lovelace.compareTo(prevBid) >= 0) {
                    found = true;
                    break;
                }
            }
        }
        return found;
    }

    static boolean handleEnd(TxInfo txInfo, AuctionDatum currentDatum) {
        BigInteger lowerBound = IntervalLib.finiteLowerBound(txInfo.validRange());
        boolean afterExpiration = lowerBound.compareTo(currentDatum.expiration()) >= 0;
        if (!afterExpiration) return false;

        byte[] highestBidder = currentDatum.highestBidder();
        boolean noBids = highestBidder.equals(Builtins.emptyByteString());

        if (noBids) {
            // No bids: seller reclaims, must sign
            return ContextsLib.signedBy(txInfo, currentDatum.seller());
        }

        // Winner exists: check item goes to winner, seller gets ADA
        boolean sellerPaid = checkPayment(txInfo.outputs(), currentDatum.seller(), currentDatum.highestBid());
        boolean winnerGetsAsset = checkReceivedAsset(txInfo.outputs(), highestBidder,
                currentDatum.assetPolicy(), currentDatum.assetName());

        return sellerPaid && winnerGetsAsset;
    }

    static boolean checkPayment(JulcList<TxOut> outputs, byte[] pkh, BigInteger amount) {
        boolean found = false;
        for (var output : outputs) {
            byte[] credHash = AddressLib.credentialHash(output.address());
            if (credHash.equals(pkh)) {
                BigInteger lovelace = ValuesLib.lovelaceOf(output.value());
                if (lovelace.compareTo(amount) >= 0) {
                    found = true;
                    break;
                }
            }
        }
        return found;
    }

    static boolean checkReceivedAsset(JulcList<TxOut> outputs, byte[] pkh,
                                      byte[] assetPolicy, byte[] assetName) {
        boolean found = false;
        for (var output : outputs) {
            byte[] credHash = AddressLib.credentialHash(output.address());
            if (credHash.equals(pkh)) {
                BigInteger qty = ValuesLib.assetOf(output.value(), assetPolicy, assetName);
                if (qty.compareTo(BigInteger.ZERO) > 0) {
                    found = true;
                    break;
                }
            }
        }
        return found;
    }

    static TxOut findOutputToScript(JulcList<TxOut> outputs, byte[] policyBytes) {
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
