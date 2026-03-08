package com.example.cftemplates.escrow.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.MultiValidator;
import com.bloxbean.cardano.julc.stdlib.annotation.Purpose;
import com.bloxbean.cardano.julc.stdlib.lib.AddressLib;
import com.bloxbean.cardano.julc.stdlib.lib.ContextsLib;
import com.bloxbean.cardano.julc.stdlib.lib.OutputLib;
import com.bloxbean.cardano.julc.stdlib.lib.ValuesLib;

import java.math.BigInteger;
import java.util.Optional;

/**
 * Escrow validator — two-phase escrow with asset swap.
 * <p>
 * Datum transitions: Initiation → ActiveEscrow
 * RecipientDeposit: recipient adds assets, datum transitions.
 * CancelTrade: either party cancels, assets returned.
 * CompleteTrade: both sign, assets swapped.
 * <p>
 * Based on: cardano-template-and-ecosystem-monitoring/escrow
 */
@MultiValidator
public class CfEscrowValidator {

    // Datum: sealed with Initiation (tag 0) and ActiveEscrow (tag 1)
    sealed interface EscrowDatum permits Initiation, ActiveEscrow {}
    record Initiation(byte[] initiator, BigInteger initiatorAmount) implements EscrowDatum {}
    record ActiveEscrow(byte[] initiator, BigInteger initiatorAmount,
                        byte[] recipient, BigInteger recipientAmount) implements EscrowDatum {}

    // Redeemer
    sealed interface EscrowAction permits RecipientDeposit, CancelTrade, CompleteTrade {}
    record RecipientDeposit(byte[] recipient, BigInteger recipientAmount) implements EscrowAction {}
    record CancelTrade() implements EscrowAction {}
    record CompleteTrade() implements EscrowAction {}

    @Entrypoint(purpose = Purpose.SPEND)
    public static boolean validate(Optional<PlutusData> datum, EscrowAction redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();

        TxOut ownInput = ContextsLib.findOwnInput(ctx).get().resolved();
        Address scriptAddress = ownInput.address();

        return switch (redeemer) {
            case RecipientDeposit rd -> handleRecipientDeposit(txInfo, ownInput, scriptAddress, rd);
            case CancelTrade ct -> handleCancelTrade(txInfo, ownInput, scriptAddress);
            case CompleteTrade t -> handleCompleteTrade(txInfo, ownInput, scriptAddress);
        };
    }

    static boolean handleRecipientDeposit(TxInfo txInfo, TxOut ownInput, Address scriptAddress,
                                           RecipientDeposit rd) {
        // Must have exactly 1 continuing output at script address
        TxOut continuingOutput = findContinuingOutput(txInfo.outputs(), scriptAddress);

        // Input datum must be Initiation
        PlutusData inputDatumData = OutputLib.getInlineDatum(ownInput);
        Initiation initDatum = (Initiation)(Object) inputDatumData;

        // Output datum must be ActiveEscrow
        PlutusData outputDatumData = OutputLib.getInlineDatum(continuingOutput);
        ActiveEscrow activeDatum = (ActiveEscrow)(Object) outputDatumData;

        // Verify datum fields preserved correctly
        boolean initiatorCorrect = activeDatum.initiator().equals(initDatum.initiator());
        boolean recipientCorrect = activeDatum.recipient().equals(rd.recipient());
        boolean initiatorAmtCorrect = activeDatum.initiatorAmount().compareTo(initDatum.initiatorAmount()) == 0;
        boolean recipientAmtCorrect = activeDatum.recipientAmount().compareTo(rd.recipientAmount()) == 0;

        // Value must increase (recipient deposited)
        BigInteger inputLovelace = ValuesLib.lovelaceOf(ownInput.value());
        BigInteger outputLovelace = ValuesLib.lovelaceOf(continuingOutput.value());
        boolean valueIncreased = outputLovelace.compareTo(inputLovelace.add(rd.recipientAmount())) >= 0;

        return initiatorCorrect && recipientCorrect && initiatorAmtCorrect
                && recipientAmtCorrect && valueIncreased;
    }

    static boolean handleCancelTrade(TxInfo txInfo, TxOut ownInput, Address scriptAddress) {
        // No continuing output at script address
        boolean noContinuing = !hasContinuingOutput(txInfo.outputs(), scriptAddress);
        if (!noContinuing) return false;

        PlutusData inputDatumData = OutputLib.getInlineDatum(ownInput);
        long tag = Builtins.constrTag(inputDatumData);

        if (tag == 0L) {
            // Initiation: initiator signs to cancel
            Initiation initDatum = (Initiation)(Object) inputDatumData;
            return ContextsLib.signedBy(txInfo, initDatum.initiator());
        }
        // ActiveEscrow: either party signs, both get assets back
        ActiveEscrow activeDatum = (ActiveEscrow)(Object) inputDatumData;
        boolean eitherSigned = ContextsLib.signedBy(txInfo, activeDatum.initiator())
                || ContextsLib.signedBy(txInfo, activeDatum.recipient());

        // Check initiator gets their amount back
        boolean initiatorPaid = checkPayment(txInfo.outputs(), activeDatum.initiator(), activeDatum.initiatorAmount());
        // Check recipient gets their amount back
        boolean recipientPaid = checkPayment(txInfo.outputs(), activeDatum.recipient(), activeDatum.recipientAmount());

        return eitherSigned && initiatorPaid && recipientPaid;
    }

    static boolean handleCompleteTrade(TxInfo txInfo, TxOut ownInput, Address scriptAddress) {
        // No continuing output
        boolean noContinuing = !hasContinuingOutput(txInfo.outputs(), scriptAddress);
        if (!noContinuing) return false;

        PlutusData inputDatumData = OutputLib.getInlineDatum(ownInput);
        ActiveEscrow activeDatum = (ActiveEscrow)(Object) inputDatumData;

        // Both must sign
        boolean initiatorSigned = ContextsLib.signedBy(txInfo, activeDatum.initiator());
        boolean recipientSigned = ContextsLib.signedBy(txInfo, activeDatum.recipient());
        if (!initiatorSigned || !recipientSigned) return false;

        // Swap: initiator gets recipient's amount, recipient gets initiator's amount
        boolean initiatorGetsRecipientAmt = checkPayment(txInfo.outputs(),
                activeDatum.initiator(), activeDatum.recipientAmount());
        boolean recipientGetsInitiatorAmt = checkPayment(txInfo.outputs(),
                activeDatum.recipient(), activeDatum.initiatorAmount());

        return initiatorGetsRecipientAmt && recipientGetsInitiatorAmt;
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

    static boolean hasContinuingOutput(JulcList<TxOut> outputs, Address scriptAddr) {
        boolean found = false;
        for (var output : outputs) {
            if (Builtins.equalsData(output.address(), scriptAddr)) {
                found = true;
                break;
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
