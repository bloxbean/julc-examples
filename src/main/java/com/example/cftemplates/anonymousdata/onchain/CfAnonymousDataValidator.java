package com.example.cftemplates.anonymousdata.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.MultiValidator;
import com.bloxbean.cardano.julc.stdlib.annotation.Purpose;
import com.bloxbean.cardano.julc.core.types.AssetEntry;
import com.bloxbean.cardano.julc.stdlib.lib.ContextsLib;
import com.bloxbean.cardano.julc.stdlib.lib.CryptoLib;
import com.bloxbean.cardano.julc.stdlib.lib.OutputLib;
import com.bloxbean.cardano.julc.stdlib.lib.ValuesLib;

import java.math.BigInteger;
import java.util.Optional;

/**
 * Anonymous Data validator — commit-reveal scheme.
 * <p>
 * Mint (commit): ID = blake2b_256(pkh || nonce) used as asset name.
 *   Mints 1 token with that name, output must have inline datum.
 * Spend (reveal): Signer proves ownership by providing nonce.
 *   blake2b_256(signerPkh || nonce) must equal the token's asset name.
 * <p>
 * Based on: cardano-template-and-ecosystem-monitoring/anonymous-data
 */
@MultiValidator
public class CfAnonymousDataValidator {

    @Entrypoint(purpose = Purpose.MINT)
    public static boolean mint(PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        ScriptInfo.MintingScript mintInfo = (ScriptInfo.MintingScript) ctx.scriptInfo();
        byte[] policyBytes = (byte[])(Object) mintInfo.policyId();

        // redeemer is the id (ByteArray) - use unBData to extract from Data
        byte[] id = Builtins.unBData(redeemer);

        // Check exactly 1 token with that asset name is minted
        BigInteger mintedQty = ValuesLib.assetOf(txInfo.mint(), policyBytes, id);
        if (mintedQty.compareTo(BigInteger.ONE) != 0) return false;

        // Check that an output carries the minted token with inline datum
        return checkOutputHasTokenWithDatum(txInfo.outputs(), policyBytes, id);
    }

    @Entrypoint(purpose = Purpose.SPEND)
    public static boolean spend(Optional<PlutusData> datum, PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();

        // nonce is the redeemer (ByteArray) - use unBData to extract from Data
        byte[] nonce = Builtins.unBData(redeemer);

        // Find own input
        TxOut ownInput = ContextsLib.findOwnInput(ctx).get().resolved();

        // Enforce exactly 1 non-ADA token with quantity 1 (matches Aiken's single-token destructure)
        if (countNonAdaTokens(ownInput) != 1) return false;
        if (!checkNonAdaTokenQtyIsOne(ownInput)) return false;

        // Extract the committed ID from the spent UTXO's non-ADA token
        byte[] committedId = extractTokenAssetName(ownInput);

        // Check that at least one signer can reconstruct the ID
        return checkSignerCanReconstruct(txInfo.signatories(), nonce, committedId);
    }

    static boolean checkOutputHasTokenWithDatum(JulcList<TxOut> outputs, byte[] policy, byte[] assetName) {
        boolean found = false;
        for (var output : outputs) {
            BigInteger qty = ValuesLib.assetOf(output.value(), policy, assetName);
            if (qty.compareTo(BigInteger.ONE) == 0) {
                // Check inline datum exists
                OutputDatum od = output.datum();
                boolean hasDatum = checkHasInlineDatum(od);
                if (hasDatum) {
                    found = true;
                    break;
                }
            }
        }
        return found;
    }

    static boolean checkHasInlineDatum(OutputDatum od) {
        return switch (od) {
            case OutputDatum.OutputDatumInline inline -> true;
            case OutputDatum.NoOutputDatum ignored -> false;
            case OutputDatum.OutputDatumHash ignored -> false;
        };
    }

    static byte[] extractTokenAssetName(TxOut output) {
        // Get non-ADA tokens from the value using ValuesLib.flatten
        // The committed ID is the asset name of the single non-ADA token
        JulcList<PlutusData> flatEntries = (JulcList<PlutusData>)(Object) ValuesLib.flatten(output.value());
        byte[] result = Builtins.emptyByteString();
        for (var entry : flatEntries) {
            // Each entry is [policyId, tokenName, amount]
            var fields = Builtins.constrFields(entry);
            byte[] policy = Builtins.unBData(Builtins.headList(fields));
            // Skip ADA (empty policy)
            if (!policy.equals(Builtins.emptyByteString())) {
                byte[] tokenName = Builtins.unBData(Builtins.headList(Builtins.tailList(fields)));
                result = tokenName;
                break;
            }
        }
        return result;
    }

    // Verify the single non-ADA token has quantity exactly 1
    static boolean checkNonAdaTokenQtyIsOne(TxOut output) {
        boolean qtyIsOne = false;
        for (AssetEntry asset : ValuesLib.flattenTyped(output.value())) {
            if (!asset.policyId().equals(Builtins.emptyByteString())) {
                if (asset.amount().compareTo(BigInteger.ONE) == 0) {
                    qtyIsOne = true;
                }
                break;
            }
        }
        return qtyIsOne;
    }

    // Count non-ADA tokens in value (must be exactly 1 for anonymous data scheme)
    static long countNonAdaTokens(TxOut output) {
        long count = 0;
        for (AssetEntry asset : ValuesLib.flattenTyped(output.value())) {
            if (!asset.policyId().equals(Builtins.emptyByteString())) {
                count = count + 1;
            }
        }
        return count;
    }

    static boolean checkSignerCanReconstruct(JulcList<PubKeyHash> signatories, byte[] nonce, byte[] committedId) {
        boolean found = false;
        for (PubKeyHash signer : signatories) {
            byte[] signerBytes = signer.hash();
            byte[] reconstructed = CryptoLib.blake2b_256(Builtins.appendByteString(signerBytes, nonce));
            if (reconstructed.equals(committedId)) {
                found = true;
                break;
            }
        }
        return found;
    }
}
