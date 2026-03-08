package com.example.cftemplates.crowdfund.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.core.types.JulcMap;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.MultiValidator;
import com.bloxbean.cardano.julc.stdlib.annotation.Param;
import com.bloxbean.cardano.julc.stdlib.annotation.Purpose;
import com.bloxbean.cardano.julc.stdlib.lib.ContextsLib;
import com.bloxbean.cardano.julc.stdlib.lib.IntervalLib;
import com.bloxbean.cardano.julc.stdlib.lib.OutputLib;
import com.bloxbean.cardano.julc.stdlib.lib.ValuesLib;

import java.math.BigInteger;
import java.util.Optional;

/**
 * Crowdfund validator — collects donations toward a goal by a deadline.
 * <p>
 * DONATE: Add funds, update donor map in continuing output.
 * WITHDRAW: After deadline + goal met, beneficiary takes all.
 * RECLAIM: After deadline + goal NOT met, donors reclaim their share.
 * <p>
 * Based on: cardano-template-and-ecosystem-monitoring/crowdfund
 */
@MultiValidator
public class CfCrowdfundValidator {

    @Param static byte[] beneficiary;
    @Param static BigInteger goal;
    @Param static BigInteger deadline;

    record CrowdfundDatum(JulcMap<byte[], BigInteger> wallets) {}

    sealed interface CrowdfundAction permits Donate, Withdraw, Reclaim {}
    record Donate() implements CrowdfundAction {}
    record Withdraw() implements CrowdfundAction {}
    record Reclaim() implements CrowdfundAction {}

    @Entrypoint(purpose = Purpose.SPEND)
    public static boolean validate(Optional<CrowdfundDatum> datumOpt, CrowdfundAction redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();

        // If no datum, allow spending (escape hatch to prevent locking forever)
        if (datumOpt.isEmpty()) return true;
        CrowdfundDatum datum = datumOpt.get();

        TxOut ownInput = ContextsLib.findOwnInput(ctx).get().resolved();

        return switch (redeemer) {
            case Donate d -> handleDonate(datum, ownInput, txInfo);
            case Withdraw w -> handleWithdraw(ownInput, txInfo);
            case Reclaim r -> handleReclaim(datum, ownInput, txInfo);
        };
    }

    static boolean handleDonate(CrowdfundDatum datum, TxOut ownInput, TxInfo txInfo) {
        // Find continuing output at script address
        TxOut scriptOutput = findContinuingOutput(txInfo.outputs(), ownInput.address());
        BigInteger inputLovelace = ValuesLib.lovelaceOf(ownInput.value());
        BigInteger outputLovelace = ValuesLib.lovelaceOf(scriptOutput.value());

        // Get continuing datum wallets and sum donations
        PlutusData newDatumData = OutputLib.getInlineDatum(scriptOutput);
        CrowdfundDatum newDatum = (CrowdfundDatum)(Object) newDatumData;
        BigInteger walletTotal = sumMapValues(newDatum.wallets());

        // Output must have more lovelace, and wallet total must equal output lovelace
        return inputLovelace.compareTo(outputLovelace) < 0
                && walletTotal.equals(outputLovelace);
    }

    static boolean handleWithdraw(TxOut ownInput, TxInfo txInfo) {
        BigInteger lowerBound = IntervalLib.finiteLowerBound(txInfo.validRange());
        boolean afterDeadline = lowerBound.compareTo(deadline) >= 0;
        BigInteger scriptLovelace = ValuesLib.lovelaceOf(ownInput.value());
        boolean goalMet = scriptLovelace.compareTo(goal) >= 0;
        boolean beneficiarySigned = ContextsLib.signedBy(txInfo, beneficiary);

        return afterDeadline && goalMet && beneficiarySigned;
    }

    static boolean handleReclaim(CrowdfundDatum datum, TxOut ownInput, TxInfo txInfo) {
        BigInteger lowerBound = IntervalLib.finiteLowerBound(txInfo.validRange());
        boolean afterDeadline = lowerBound.compareTo(deadline) >= 0;
        BigInteger scriptLovelace = ValuesLib.lovelaceOf(ownInput.value());
        boolean goalNotMet = scriptLovelace.compareTo(goal) < 0;
        if (!afterDeadline || !goalNotMet) return false;

        // Compute donated amount from signers
        BigInteger donatedAmount = sumSignerDonations(datum.wallets(), txInfo.signatories());

        if (donatedAmount.equals(scriptLovelace)) {
            // Signers reclaim all — UTXO is fully consumed
            return true;
        }

        // Partial reclaim: continuing output must exist with donors removed
        TxOut scriptOutput = findContinuingOutput(txInfo.outputs(), ownInput.address());
        PlutusData newDatumData = OutputLib.getInlineDatum(scriptOutput);
        CrowdfundDatum newDatum = (CrowdfundDatum)(Object) newDatumData;

        // Verify reclaiming donors are NOT in the new wallet map
        boolean donorsRemoved = checkDonorsRemoved(newDatum.wallets(), txInfo.signatories());

        // Verify remaining lovelace is sufficient
        BigInteger remainingLovelace = ValuesLib.lovelaceOf(scriptOutput.value());
        boolean valuePreserved = scriptLovelace.compareTo(remainingLovelace.add(donatedAmount)) <= 0;

        return donorsRemoved && valuePreserved;
    }

    static BigInteger sumSignerDonations(JulcMap<byte[], BigInteger> wallets, JulcList<PubKeyHash> signers) {
        BigInteger total = BigInteger.ZERO;
        JulcList<byte[]> walletKeys = wallets.keys();
        for (var key : walletKeys) {
            if (isSignedByKey(signers, key)) {
                total = total.add(wallets.get(key));
            }
        }
        return total;
    }

    static boolean isSignedByKey(JulcList<PubKeyHash> signers, byte[] key) {
        return signers.any(sig -> ((byte[])(Object) sig).equals(key));
    }

    static boolean checkDonorsRemoved(JulcMap<byte[], BigInteger> newWallets, JulcList<PubKeyHash> signers) {
        boolean valid = true;
        for (var sig : signers) {
            byte[] sigBytes = (byte[])(Object) sig;
            if (newWallets.containsKey(sigBytes)) {
                valid = false;
                break;
            }
        }
        return valid;
    }

    static BigInteger sumMapValues(JulcMap<byte[], BigInteger> map) {
        BigInteger total = BigInteger.ZERO;
        JulcList<BigInteger> values = map.values();
        for (var v : values) {
            total = total.add(v);
        }
        return total;
    }

    static TxOut findContinuingOutput(JulcList<TxOut> outputs, Address scriptAddress) {
        TxOut result = (TxOut)(Object) Builtins.mkNilData();
        for (var output : outputs) {
            if (Builtins.equalsData(output.address(), scriptAddress)) {
                result = output;
                break;
            }
        }
        return result;
    }

}
