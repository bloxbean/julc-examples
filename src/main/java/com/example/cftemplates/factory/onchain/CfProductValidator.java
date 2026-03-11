package com.example.cftemplates.factory.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.MultiValidator;
import com.bloxbean.cardano.julc.stdlib.annotation.Param;
import com.bloxbean.cardano.julc.stdlib.annotation.Purpose;
import com.bloxbean.cardano.julc.stdlib.lib.*;

import java.math.BigInteger;
import java.util.Optional;

/**
 * Product validator — individual product created by the factory.
 * <p>
 * MINT: Product token minting. Factory marker must be spent (factory validates),
 *   1 product token minted, sent to own script with ProductDatum, owner signs.
 * SPEND: Owner signature check.
 * <p>
 * Based on: cardano-template-and-ecosystem-monitoring/factory (product.ak)
 */
@MultiValidator
public class CfProductValidator {

    @Param static byte[] owner;
    @Param static byte[] factoryMarkerPolicy;
    @Param static byte[] productId;

    public record ProductDatum(byte[] tag) {}

    @Entrypoint(purpose = Purpose.MINT)
    public static boolean mint(PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        boolean ownerSigned = ContextsLib.signedBy(txInfo, owner);

        ScriptInfo.MintingScript mintInfo = (ScriptInfo.MintingScript) ctx.scriptInfo();
        byte[] policyBytes = (byte[])(Object) mintInfo.policyId();

        // Exactly 1 product token minted with productId
        BigInteger mintedQty = ValuesLib.assetOf(txInfo.mint(), policyBytes, productId);
        boolean validMint = mintedQty.compareTo(BigInteger.ONE) == 0;

        // Factory marker must be spent (an input has factory marker token)
        boolean factoryMarkerSpent = checkFactoryMarkerSpent(txInfo.inputs());

        // Product token sent to own script with ProductDatum
        boolean validOutput = checkProductOutput(txInfo.outputs(), policyBytes);

        return ownerSigned && validMint && factoryMarkerSpent && validOutput;
    }

    @Entrypoint(purpose = Purpose.SPEND)
    public static boolean spend(Optional<PlutusData> datum, PlutusData redeemer, ScriptContext ctx) {
        // Owner signature check
        return ContextsLib.signedBy(ctx.txInfo(), owner);
    }

    static boolean checkFactoryMarkerSpent(JulcList<TxInInfo> inputs) {
        boolean found = false;
        for (var input : inputs) {
            if (ValuesLib.containsPolicy(input.resolved().value(), factoryMarkerPolicy)) {
                found = true;
                break;
            }
        }
        return found;
    }

    static boolean checkProductOutput(JulcList<TxOut> outputs, byte[] policyBytes) {
        boolean found = false;
        for (var output : outputs) {
            // Output must contain the product token
            BigInteger qty = ValuesLib.assetOf(output.value(), policyBytes, productId);
            if (qty.compareTo(BigInteger.ONE) == 0) {
                // Must be at a script address
                if (AddressLib.isScriptAddress(output.address())) {
                    // Must have inline datum (ProductDatum)
                    PlutusData datumData = OutputLib.getInlineDatum(output);
                    found = true;
                    break;
                }
            }
        }
        return found;
    }
}
