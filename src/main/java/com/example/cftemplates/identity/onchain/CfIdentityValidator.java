package com.example.cftemplates.identity.onchain;

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
 * Decentralized Identity validator — manages owner + delegates list.
 * <p>
 * TransferOwner: owner signs, updates owner in continuing output.
 * AddDelegate: owner signs, adds delegate with expiration.
 * RemoveDelegate: owner signs, removes delegate from list.
 * <p>
 * Based on: cardano-template-and-ecosystem-monitoring/decentralized-identity
 */
@MultiValidator
public class CfIdentityValidator {

    public record Delegate(byte[] key, BigInteger expires) {}

    public record IdentityDatum(byte[] owner, JulcList<Delegate> delegates) {}

    public sealed interface IdentityAction permits TransferOwner, AddDelegate, RemoveDelegate {}
    public record TransferOwner(byte[] newOwner) implements IdentityAction {}
    public record AddDelegate(byte[] delegate, BigInteger expires) implements IdentityAction {}
    public record RemoveDelegate(byte[] delegate) implements IdentityAction {}

    @Entrypoint(purpose = Purpose.SPEND)
    public static boolean validate(Optional<PlutusData> datum, IdentityAction redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();

        // Find own input
        TxOut ownInput = ContextsLib.findOwnInput(ctx).get().resolved();
        Address scriptAddress = ownInput.address();

        // Extract inline datum from own input
        IdentityDatum currentState = extractInlineDatum(ownInput);
        byte[] owner = currentState.owner();
        JulcList<Delegate> delegates = currentState.delegates();

        // Owner must sign
        boolean ownerSigned = ContextsLib.signedBy(txInfo, owner);
        if (!ownerSigned) return false;

        // Find exactly 1 continuing output
        TxOut continuingOutput = findContinuingOutput(txInfo.outputs(), scriptAddress);

        // Extract new state
        IdentityDatum newState = extractInlineDatum(continuingOutput);

        // Value must be preserved
        BigInteger inputLovelace = ValuesLib.lovelaceOf(ownInput.value());
        BigInteger outputLovelace = ValuesLib.lovelaceOf(continuingOutput.value());
        if (inputLovelace.compareTo(outputLovelace) != 0) return false;

        return switch (redeemer) {
            case TransferOwner t -> t.newOwner().equals(newState.owner())
                    && delegatesEqual(delegates, newState.delegates());
            case AddDelegate a -> owner.equals(newState.owner())
                    && !hasDelegate(delegates, a.delegate())
                    && hasDelegate(newState.delegates(), a.delegate())
                    && !a.delegate().equals(owner)
                    // Prevent adding already-expired delegates (matches Aiken's valid_before check)
                    && IntervalLib.finiteUpperBound(txInfo.validRange()).compareTo(a.expires()) <= 0
                    // Ensure exactly 1 delegate was added
                    && newState.delegates().size() == delegates.size() + 1;
            case RemoveDelegate r -> owner.equals(newState.owner())
                    && hasDelegate(delegates, r.delegate())
                    && !hasDelegate(newState.delegates(), r.delegate())
                    // Ensure exactly 1 delegate was removed
                    && delegates.size() == newState.delegates().size() + 1;
        };
    }

    static boolean hasDelegate(JulcList<Delegate> delegates, byte[] key) {
        return delegates.any(d -> d.key().equals(key));
    }

    static boolean delegatesEqual(JulcList<Delegate> a, JulcList<Delegate> b) {
        // Wrap back to ListData before comparing, since extracted JulcList is
        // a native UPLC list (not Data) and equalsData requires Data arguments.
        PlutusData pa = Builtins.listData((PlutusData)(Object) a);
        PlutusData pb = Builtins.listData((PlutusData)(Object) b);
        return Builtins.equalsData(pa, pb);
    }

    static IdentityDatum extractInlineDatum(TxOut output) {
        PlutusData raw = OutputLib.getInlineDatum(output);
        return (IdentityDatum)(Object) raw;
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
