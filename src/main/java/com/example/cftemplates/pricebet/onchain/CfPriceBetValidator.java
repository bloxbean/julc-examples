package com.example.cftemplates.pricebet.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.core.types.JulcMap;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.MultiValidator;
import com.bloxbean.cardano.julc.stdlib.annotation.Purpose;
import com.bloxbean.cardano.julc.stdlib.lib.*;

import java.math.BigInteger;
import java.util.Optional;

/**
 * Price Bet validator — oracle-based betting on asset price.
 * <p>
 * JOIN: Player joins a bet by doubling the pot (before deadline).
 * WIN:  Player wins if oracle price >= target rate (before deadline).
 * TIMEOUT: Owner reclaims after deadline.
 * <p>
 * Uses oracle reference input pattern for price feeds.
 * Player field: empty byte[] = no player (sentinel pattern).
 * NOTE: Deliberate simplification — uses sentinel byte[] instead of
 * Aiken's Option type for the player field. Internally consistent.
 * <p>
 * Based on: cardano-template-and-ecosystem-monitoring/pricebet
 */
@MultiValidator
public class CfPriceBetValidator {

    public record PriceBetDatum(byte[] owner, byte[] player, byte[] oracleVkh,
                         BigInteger targetRate, BigInteger deadline, BigInteger betAmount) {}

    public sealed interface PriceBetAction permits Join, Win, Timeout {}
    public record Join() implements PriceBetAction {}
    public record Win() implements PriceBetAction {}
    public record Timeout() implements PriceBetAction {}

    @Entrypoint(purpose = Purpose.SPEND)
    public static boolean spend(Optional<PlutusData> datum, PriceBetAction redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();

        TxOut ownInput = ContextsLib.findOwnInput(ctx).get().resolved();
        PlutusData datumData = OutputLib.getInlineDatum(ownInput);
        PriceBetDatum betDatum = (PriceBetDatum)(Object) datumData;

        return switch (redeemer) {
            case Join j -> handleJoin(txInfo, ownInput, betDatum);
            case Win w -> handleWin(txInfo, ownInput, betDatum);
            case Timeout t -> handleTimeout(txInfo, ownInput, betDatum);
        };
    }

    static boolean handleJoin(TxInfo txInfo, TxOut ownInput, PriceBetDatum datum) {
        // Must not have a player yet
        if (!datum.player().equals(Builtins.emptyByteString())) return false;

        Address scriptAddr = ownInput.address();

        // Find continuing output at same script address
        TxOut continuingOutput = findContinuingOutput(txInfo.outputs(), scriptAddr);
        PlutusData outDatumData = OutputLib.getInlineDatum(continuingOutput);
        PriceBetDatum newDatum = (PriceBetDatum)(Object) outDatumData;

        // Player must be set and must sign
        boolean hasPlayer = !newDatum.player().equals(Builtins.emptyByteString());
        boolean playerSigned = ContextsLib.signedBy(txInfo, newDatum.player());
        // Other fields must not change
        boolean ownerSame = newDatum.owner().equals(datum.owner());
        boolean oracleSame = newDatum.oracleVkh().equals(datum.oracleVkh());
        boolean rateSame = newDatum.targetRate().compareTo(datum.targetRate()) == 0;
        boolean deadlineSame = newDatum.deadline().compareTo(datum.deadline()) == 0;
        boolean amountSame = newDatum.betAmount().compareTo(datum.betAmount()) == 0;
        // Value must double (owner deposit + player deposit)
        BigInteger outLovelace = ValuesLib.lovelaceOf(continuingOutput.value());
        boolean valueDoubled = outLovelace.compareTo(datum.betAmount().multiply(BigInteger.TWO)) >= 0;
        // Must happen before deadline
        BigInteger upperBound = IntervalLib.finiteUpperBound(txInfo.validRange());
        boolean beforeDeadline = upperBound.compareTo(datum.deadline()) <= 0;

        return hasPlayer && playerSigned && ownerSame && oracleSame
                && rateSame && deadlineSame && amountSame && valueDoubled && beforeDeadline;
    }

    static boolean handleWin(TxInfo txInfo, TxOut ownInput, PriceBetDatum datum) {
        // Must have a player
        byte[] player = datum.player();
        if (player.equals(Builtins.emptyByteString())) return false;

        boolean playerSigned = ContextsLib.signedBy(txInfo, player);

        // Find oracle reference input and extract price + expiry
        PlutusData oracleDatumData = findOracleDatum(txInfo.referenceInputs(), datum.oracleVkh());
        BigInteger oraclePrice = extractPriceMapField(oracleDatumData, BigInteger.ZERO);
        BigInteger oracleExpiry = extractPriceMapField(oracleDatumData, BigInteger.TWO);

        // Price must meet or exceed target
        boolean priceOk = oraclePrice.compareTo(datum.targetRate()) >= 0;

        BigInteger upperBound = IntervalLib.finiteUpperBound(txInfo.validRange());
        // Oracle must still be valid
        boolean oracleValid = upperBound.compareTo(oracleExpiry) <= 0;
        // Must be before deadline
        boolean beforeDeadline = upperBound.compareTo(datum.deadline()) <= 0;

        // Full pot must go to player
        BigInteger totalPot = ValuesLib.lovelaceOf(ownInput.value());
        boolean payoutOk = checkPayment(txInfo.outputs(), player, totalPot);

        return playerSigned && priceOk && oracleValid && beforeDeadline && payoutOk;
    }

    static boolean handleTimeout(TxInfo txInfo, TxOut ownInput, PriceBetDatum datum) {
        // Must be after deadline
        BigInteger lowerBound = IntervalLib.finiteLowerBound(txInfo.validRange());
        boolean afterDeadline = lowerBound.compareTo(datum.deadline()) > 0;

        // Owner must sign
        boolean ownerSigned = ContextsLib.signedBy(txInfo, datum.owner());

        // Full pot must go to owner
        BigInteger totalPot = ValuesLib.lovelaceOf(ownInput.value());
        boolean payoutOk = checkPayment(txInfo.outputs(), datum.owner(), totalPot);

        return afterDeadline && ownerSigned && payoutOk;
    }

    // --- Oracle helpers ---

    static PlutusData findOracleDatum(JulcList<TxInInfo> refInputs, byte[] oracleVkh) {
        PlutusData result = Builtins.mkNilData();
        for (var refIn : refInputs) {
            if (AddressLib.isScriptAddress(refIn.resolved().address())) {
                byte[] credHash = AddressLib.credentialHash(refIn.resolved().address());
                if (credHash.equals(oracleVkh)) {
                    result = OutputLib.getInlineDatum(refIn.resolved());
                    break;
                }
            }
        }
        return result;
    }

    static BigInteger extractPriceMapField(PlutusData oracleDatumData, BigInteger key) {
        // OracleDatum = Constr(0, [priceData])
        var oracleFields = Builtins.constrFields(oracleDatumData);
        var priceData = Builtins.headList(oracleFields);
        // GenericData = Constr(2, [priceMap])
        var pdFields = Builtins.constrFields(priceData);
        var mapRaw = Builtins.headList(pdFields);
        JulcMap<BigInteger, BigInteger> priceMap = (JulcMap<BigInteger, BigInteger>)(Object) mapRaw;
        return priceMap.get(key);
    }

    // --- General helpers ---

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
