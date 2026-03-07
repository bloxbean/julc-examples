package com.example.nft.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.MultiValidator;
import com.bloxbean.cardano.julc.stdlib.annotation.Param;
import com.bloxbean.cardano.julc.stdlib.annotation.Purpose;
import com.bloxbean.cardano.julc.stdlib.lib.ContextsLib;
import com.bloxbean.cardano.julc.stdlib.lib.ByteStringLib;
import com.bloxbean.cardano.julc.stdlib.lib.ValuesLib;

import java.math.BigInteger;
import java.util.Optional;

/**
 * CIP-68 NFT multi-validator — reference NFT + user NFT.
 * <p>
 * Minting produces a pair: one reference token (locked at script address with metadata)
 * and one user token (sent to the minter). The reference UTxO holds CIP-68 metadata
 * as an inline datum. The spend entrypoint guards metadata updates and burning.
 * <p>
 * Features demonstrated:
 * - @MultiValidator with MINT + SPEND entrypoints
 * - @Param (byte[] x2): pre-computed CIP-68 token names
 * - @Entrypoint(purpose = Purpose.MINT) and @Entrypoint(purpose = Purpose.SPEND)
 * - Two sealed interfaces (MintAction, RefAction)
 * - Value.assetOf() for mint quantity checks
 * - list.any() HOF lambda with block body
 * - OutputDatum switch (3 variants)
 * - Credential switch (ScriptCredential / PubKeyCredential)
 * - ScriptInfo.MintingScript and ScriptInfo.SpendingScript casts
 * - ContextsLib.findOwnInput() for spend entrypoint
 * - ContextsLib.trace()
 */
@MultiValidator
public class Cip68Nft {

    @Param public static byte[] refTokenName;    // 000643b0 ++ assetName
    @Param public static byte[] userTokenName;   // 000de140 ++ assetName

    // CIP-68 metadata stored as inline datum on reference UTxO
    public record NftMetadata(
            PlutusData metadata,
            BigInteger version
    ) {}

    // Minting actions
    public sealed interface MintAction permits MintNft, BurnNft {}
    public record MintNft() implements MintAction {}
    public record BurnNft() implements MintAction {}

    // Spending actions (for reference UTxO)
    public sealed interface RefAction permits UpdateMetadata, BurnReference {}
    public record UpdateMetadata() implements RefAction {}
    public record BurnReference() implements RefAction {}

    // ---- MINT entrypoint ----

    @Entrypoint(purpose = Purpose.MINT)
    public static boolean handleMint(MintAction redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        ScriptInfo.MintingScript mintInfo = (ScriptInfo.MintingScript) ctx.scriptInfo();
        byte[] ownPolicyId = (byte[])(Object) mintInfo.policyId();

        ContextsLib.trace("CIP-68 mint");
        return switch (redeemer) {
            case MintNft m -> validateMintNft(txInfo, ownPolicyId);
            case BurnNft b -> validateBurnNft(txInfo, ownPolicyId);
        };
    }

    // ---- SPEND entrypoint ----

    @Entrypoint(purpose = Purpose.SPEND)
    static boolean handleSpend(Optional<PlutusData> datum, RefAction redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        ScriptInfo.SpendingScript spendInfo = (ScriptInfo.SpendingScript) ctx.scriptInfo();
        byte[] ownScriptHash = getOwnScriptHash(txInfo, spendInfo.txOutRef());

        ContextsLib.trace("CIP-68 spend");
        return switch (redeemer) {
            case UpdateMetadata u -> validateUpdateMetadata(txInfo, ownScriptHash);
            case BurnReference b -> validateBurnReference(txInfo, ownScriptHash);
        };
    }

    // ---- Mint handlers ----

    public static boolean validateMintNft(TxInfo txInfo, byte[] ownPolicyId) {
        ContextsLib.trace("Mint NFT");

        // Check both tokens are minted with quantity 1
        BigInteger refQty = ValuesLib.assetOf(txInfo.mint(), ownPolicyId, refTokenName);
        BigInteger userQty = ValuesLib.assetOf(txInfo.mint(), ownPolicyId, userTokenName);
        if (!refQty.equals(BigInteger.ONE)) {
            ContextsLib.trace("ref qty bad");
            return false;
        }
        if (!userQty.equals(BigInteger.ONE)) {
            ContextsLib.trace("user qty bad");
            return false;
        }

        ContextsLib.trace("qty ok");

        // Check reference token is locked at script address with inline datum
        boolean refLockedCorrectly = txInfo.outputs().any(out -> {
            boolean atScript = isOwnScript(out.address().credential(), ownPolicyId);
            boolean hasRef = ValuesLib.assetOf(out.value(), ownPolicyId, refTokenName)
                    .equals(BigInteger.ONE);
            boolean hasInline = hasInlineDatum(out.datum());
            return atScript && hasRef && hasInline;
        });

        if (!refLockedCorrectly) {
            ContextsLib.trace("output fail");
        }

        return refLockedCorrectly;
    }

    public static boolean validateBurnNft(TxInfo txInfo, byte[] ownPolicyId) {
        ContextsLib.trace("Burn NFT");

        // Both tokens must be burned (quantity == -1)
        BigInteger refQty = ValuesLib.assetOf(txInfo.mint(), ownPolicyId, refTokenName);
        BigInteger userQty = ValuesLib.assetOf(txInfo.mint(), ownPolicyId, userTokenName);
        BigInteger negOne = BigInteger.ZERO.subtract(BigInteger.ONE);
        return refQty.equals(negOne) && userQty.equals(negOne);
    }

    // ---- Spend handlers ----

    static boolean validateUpdateMetadata(TxInfo txInfo, byte[] ownScriptHash) {
        ContextsLib.trace("Update metadata");

        // Check continuing output at same script address with ref token and inline datum
        boolean hasContinuingOutput = txInfo.outputs().any(out -> {
            boolean atScript = isOwnScript(out.address().credential(), ownScriptHash);
            boolean hasRef = ValuesLib.assetOf(out.value(), ownScriptHash, refTokenName)
                    .equals(BigInteger.ONE);
            boolean hasInline = hasInlineDatum(out.datum());
            return atScript && hasRef && hasInline;
        });

        return hasContinuingOutput;
    }

    static boolean validateBurnReference(TxInfo txInfo, byte[] ownScriptHash) {
        ContextsLib.trace("Burn reference");

        // Check ref token is burned in the mint field
        BigInteger refQty = ValuesLib.assetOf(txInfo.mint(), ownScriptHash, refTokenName);
        BigInteger negOne = BigInteger.ZERO.subtract(BigInteger.ONE);
        return refQty.equals(negOne);
    }

    // ---- Utilities ----

    // Helper: check if a credential is a script credential matching the given hash
    static boolean isOwnScript(Credential cred, byte[] expectedHash) {
        return switch (cred) {
            case Credential.ScriptCredential sc ->
                    ByteStringLib.equals(sc.hash().hash(), expectedHash);
            case Credential.PubKeyCredential pk -> false;
        };
    }

    // Helper: check if an output has an inline datum
    static boolean hasInlineDatum(OutputDatum datum) {
        return switch (datum) {
            case OutputDatum.OutputDatumInline i -> true;
            case OutputDatum.OutputDatumHash h -> false;
            case OutputDatum.NoOutputDatum n -> false;
        };
    }

    // Helper: extract own script hash from spending input
    static byte[] getOwnScriptHash(TxInfo txInfo, TxOutRef spentRef) {
        byte[] result = Builtins.emptyByteString();
        for (var input : txInfo.inputs()) {
            if (input.outRef().txId().equals(spentRef.txId())
                    && input.outRef().index().equals(spentRef.index())) {
                Credential cred = input.resolved().address().credential();
                result = switch (cred) {
                    case Credential.ScriptCredential sc -> (byte[])(Object) sc.hash();
                    case Credential.PubKeyCredential pk -> Builtins.emptyByteString();
                };
                break;
            }
        }
        return result;
    }
}
