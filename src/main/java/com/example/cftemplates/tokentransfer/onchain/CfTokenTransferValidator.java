package com.example.cftemplates.tokentransfer.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.MultiValidator;
import com.bloxbean.cardano.julc.stdlib.annotation.Param;
import com.bloxbean.cardano.julc.stdlib.annotation.Purpose;
import com.bloxbean.cardano.julc.core.types.AssetEntry;
import com.bloxbean.cardano.julc.stdlib.lib.ContextsLib;
import com.bloxbean.cardano.julc.stdlib.lib.ValuesLib;

import java.math.BigInteger;
import java.util.Optional;

/**
 * Token Transfer validator — guards a UTXO containing a specific native asset.
 * <p>
 * If the expected policy is in the spent input, the receiver must sign and
 * no tokens of that policy may leak to non-script addresses (anti-drain).
 * If the policy is NOT in the input, spending is freely allowed (escape hatch).
 * <p>
 * Based on: cardano-template-and-ecosystem-monitoring/token-transfer
 */
@MultiValidator
public class CfTokenTransferValidator {

    @Param static byte[] receiver;
    @Param static byte[] policy;
    @Param static byte[] assetName;

    @Entrypoint(purpose = Purpose.SPEND)
    public static boolean validate(Optional<PlutusData> datum, PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        TxOut ownInput = ContextsLib.findOwnInput(ctx).get().resolved();
        Value inputValue = ownInput.value();

        boolean hasPolicy = ValuesLib.containsPolicy(inputValue, policy);
        if (!hasPolicy) {
            return true; // escape hatch
        }

        // Policy found: check asset exists, no token leaks, receiver signed
        BigInteger assetAmount = ValuesLib.assetOf(inputValue, policy, assetName);
        boolean hasAsset = assetAmount.compareTo(BigInteger.ZERO) > 0;
        boolean receiverSigned = ContextsLib.signedBy(txInfo, receiver);
        boolean noLeaks = checkNoTokenLeaks(txInfo.outputs(), ownInput.address());

        return hasAsset && receiverSigned && noLeaks;
    }

    // Check that no non-ADA tokens leak to non-script addresses (matches Aiken's
    // without_lovelace check that verifies ALL non-ADA tokens stay at script address)
    static boolean checkNoTokenLeaks(JulcList<TxOut> outputs, Address scriptAddress) {
        boolean clean = true;
        for (var output : outputs) {
            if (!Builtins.equalsData(output.address(), scriptAddress)) {
                // Check for ANY non-ADA token in the output (not just the specific policy)
                if (hasNonAdaTokens(output)) {
                    clean = false;
                    break;
                }
            }
        }
        return clean;
    }

    static boolean hasNonAdaTokens(TxOut output) {
        boolean found = false;
        for (AssetEntry asset : ValuesLib.flattenTyped(output.value())) {
            if (!asset.policyId().equals(Builtins.emptyByteString())) {
                found = true;
                break;
            }
        }
        return found;
    }

}
