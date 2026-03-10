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
 * Factory validator — combines factory marker minting + factory management.
 * <p>
 * MINT: One-shot minting of FACTORY_MARKER NFT.
 *   Consumes seed UTxO, mints 1 token, owner signs.
 * SPEND CreateProduct: Create a new product.
 *   Marker continuity (input → output), product policy token minted,
 *   datum updated with new product, owner signs.
 * <p>
 * Based on: cardano-template-and-ecosystem-monitoring/factory
 */
@MultiValidator
public class CfFactoryValidator {

    @Param static byte[] owner;
    @Param static byte[] seedTxHash;
    @Param static BigInteger seedIndex;

    record FactoryDatum(JulcList<byte[]> products) {}

    record CreateProduct(byte[] productPolicyId, byte[] productId) {}

    @Entrypoint(purpose = Purpose.MINT)
    public static boolean mint(PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        boolean ownerSigned = ContextsLib.signedBy(txInfo, owner);

        ScriptInfo.MintingScript mintInfo = (ScriptInfo.MintingScript) ctx.scriptInfo();
        byte[] policyBytes = (byte[])(Object) mintInfo.policyId();

        // One-shot: seed UTxO consumed
        boolean consumesSeed = checkSeedConsumed(txInfo.inputs());

        // Exactly 1 token minted
        BigInteger mintCount = ValuesLib.countTokensWithQty(txInfo.mint(), policyBytes, BigInteger.ONE);
        boolean oneMinted = mintCount.compareTo(BigInteger.ONE) == 0;

        return ownerSigned && consumesSeed && oneMinted;
    }

    @Entrypoint(purpose = Purpose.SPEND)
    public static boolean spend(Optional<PlutusData> datum, CreateProduct redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        boolean ownerSigned = ContextsLib.signedBy(txInfo, owner);
        if (!ownerSigned) return false;

        TxOut ownInput = ContextsLib.findOwnInput(ctx).get().resolved();
        Address scriptAddr = ownInput.address();

        // Own hash = factory marker policy
        byte[] ownHash = ContextsLib.ownHash(ctx);

        // Marker token must be in input
        boolean inputHasMarker = ValuesLib.containsPolicy(ownInput.value(), ownHash);

        // Find continuing output with marker token
        TxOut continuingOutput = findContinuingWithPolicy(txInfo.outputs(), scriptAddr, ownHash);

        // Product must be minted
        boolean productMinted = ValuesLib.assetOf(txInfo.mint(),
                redeemer.productPolicyId(), redeemer.productId())
                .compareTo(BigInteger.ONE) == 0;

        // Datum updated: products list contains new product policy
        PlutusData newDatumData = OutputLib.getInlineDatum(continuingOutput);
        FactoryDatum newDatum = (FactoryDatum)(Object) newDatumData;
        boolean datumUpdated = listContainsBytes(newDatum.products(), redeemer.productPolicyId());

        return inputHasMarker && productMinted && datumUpdated;
    }

    static boolean checkSeedConsumed(JulcList<TxInInfo> inputs) {
        boolean found = false;
        for (var input : inputs) {
            byte[] txHash = (byte[])(Object) input.outRef().txId();
            BigInteger idx = input.outRef().index();
            if (txHash.equals(seedTxHash) && idx.compareTo(seedIndex) == 0) {
                found = true;
                break;
            }
        }
        return found;
    }

    static boolean listContainsBytes(JulcList<byte[]> list, byte[] target) {
        boolean found = false;
        for (var item : list) {
            if (item.equals(target)) {
                found = true;
                break;
            }
        }
        return found;
    }

    static TxOut findContinuingWithPolicy(JulcList<TxOut> outputs, Address scriptAddr, byte[] policy) {
        TxOut result = (TxOut)(Object) Builtins.mkNilData();
        for (var output : outputs) {
            if (Builtins.equalsData(output.address(), scriptAddr)) {
                if (ValuesLib.containsPolicy(output.value(), policy)) {
                    result = output;
                    break;
                }
            }
        }
        return result;
    }
}
