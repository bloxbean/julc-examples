package com.example.linkedlist.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;
import com.bloxbean.cardano.julc.stdlib.lib.ByteStringLib;
import com.bloxbean.cardano.julc.stdlib.lib.OutputLib;
import com.bloxbean.cardano.julc.stdlib.lib.ValuesLib;

import java.math.BigInteger;

/**
 * Reusable on-chain validation logic for a linked list stored as UTXOs.
 * Each node is a separate UTXO holding a unique NFT; nodes link via datum fields.
 * <p>
 * Methods are ordered bottom-up (helpers before callers) for single-pass compilation.
 */
@OnchainLibrary
public class LinkedListLib {

    record ListElement(PlutusData userData, byte[] nextKey) {}

    // === Helpers ===

    static byte[] extractNodeKey(byte[] tokenName, int prefixLen) {
        return ByteStringLib.drop(tokenName, prefixLen);
    }

    static byte[] buildTokenName(byte[] prefix, byte[] key) {
        return ByteStringLib.append(prefix, key);
    }

    static boolean isRootToken(byte[] tokenName, byte[] rootKey) {
        return tokenName.equals(rootKey);
    }

    static boolean requireListTokensMintedOrBurned(Value mint, byte[] policyId) {
        return ValuesLib.containsPolicy(mint, policyId);
    }

    // === Validation ===

    static boolean validateInit(TxOut rootOutput, Value mint, byte[] policyId,
                                byte[] rootKey, Address scriptAddr) {
        boolean atScript = Builtins.equalsData(rootOutput.address(), scriptAddr);

        ListElement datum = (ListElement)(Object) OutputLib.getInlineDatum(rootOutput);
        boolean emptyNext = datum.nextKey().equals(Builtins.emptyByteString());

        BigInteger rootQty = ValuesLib.assetOf(mint, policyId, rootKey);
        boolean rootMinted = rootQty.compareTo(BigInteger.ONE) == 0;

        BigInteger mintCount = ValuesLib.countTokensWithQty(mint, policyId, BigInteger.ONE);
        boolean onlyOne = mintCount.compareTo(BigInteger.ONE) == 0;

        return atScript && emptyNext && rootMinted && onlyOne;
    }

    static boolean validateDeinit(TxOut rootInputResolved, Value mint, byte[] policyId,
                                  byte[] rootKey) {
        ListElement datum = (ListElement)(Object) OutputLib.getInlineDatum(rootInputResolved);
        boolean emptyNext = datum.nextKey().equals(Builtins.emptyByteString());

        BigInteger rootQty = ValuesLib.assetOf(mint, policyId, rootKey);
        boolean rootBurned = rootQty.compareTo(BigInteger.ONE.negate()) == 0;

        BigInteger burnCount = ValuesLib.countTokensWithQty(mint, policyId, BigInteger.ONE.negate());
        boolean onlyOne = burnCount.compareTo(BigInteger.ONE) == 0;

        return emptyNext && rootBurned && onlyOne;
    }

    static boolean validateInsert(TxOut anchorInputResolved, TxOut contAnchorOutput,
                                  TxOut newElementOutput, Value mint, byte[] policyId,
                                  byte[] rootKey, byte[] prefix, int prefixLen,
                                  Address scriptAddr) {
        // Discover anchor's NFT token name
        byte[] anchorTokenName = ValuesLib.findTokenName(
                anchorInputResolved.value(), policyId, BigInteger.ONE);
        boolean anchorIsRoot = isRootToken(anchorTokenName, rootKey);

        // Discover newly minted NFT token name and extract key
        byte[] newTokenName = ValuesLib.findTokenName(mint, policyId, BigInteger.ONE);
        byte[] newKey = extractNodeKey(newTokenName, prefixLen);

        // Token name must be prefix + key
        byte[] expectedName = buildTokenName(prefix, newKey);
        boolean nameCorrect = newTokenName.equals(expectedName);

        // Anchor NFT preserved in continuing output
        BigInteger contQty = ValuesLib.assetOf(contAnchorOutput.value(), policyId, anchorTokenName);
        boolean anchorPreserved = contQty.compareTo(BigInteger.ONE) == 0;

        // Continuing anchor and new element must be at script address
        boolean contAtScript = Builtins.equalsData(contAnchorOutput.address(), scriptAddr);
        boolean newAtScript = Builtins.equalsData(newElementOutput.address(), scriptAddr);

        // Extract datums
        ListElement anchorOld = (ListElement)(Object) OutputLib.getInlineDatum(anchorInputResolved);
        ListElement contAnchor = (ListElement)(Object) OutputLib.getInlineDatum(contAnchorOutput);
        ListElement newElement = (ListElement)(Object) OutputLib.getInlineDatum(newElementOutput);

        // Anchor userData unchanged
        boolean dataUnchanged = Builtins.equalsData(anchorOld.userData(), contAnchor.userData());

        // Continuing anchor's nextKey = new node's key
        boolean contNextOk = contAnchor.nextKey().equals(newKey);

        // New element's nextKey = anchor's old nextKey
        boolean newNextOk = newElement.nextKey().equals(anchorOld.nextKey());

        // Ordering: anchorKey < newKey (skip if root), newKey < oldNextKey (skip if end)
        byte[] oldNextKey = anchorOld.nextKey();
        boolean insertAtEnd = oldNextKey.equals(Builtins.emptyByteString());
        byte[] anchorKey = extractNodeKey(anchorTokenName, prefixLen);
        boolean orderOk = (anchorIsRoot || ByteStringLib.lessThan(anchorKey, newKey))
                && (insertAtEnd || ByteStringLib.lessThan(newKey, oldNextKey));

        // Exactly 1 new NFT minted under this policy
        BigInteger mintCount = ValuesLib.countTokensWithQty(mint, policyId, BigInteger.ONE);
        boolean exactlyOne = mintCount.compareTo(BigInteger.ONE) == 0;

        return nameCorrect && anchorPreserved && contAtScript && newAtScript
                && dataUnchanged && contNextOk && newNextOk && orderOk && exactlyOne;
    }

    static boolean validateRemove(TxOut anchorInputResolved, TxOut removingInputResolved,
                                  TxOut contAnchorOutput, Value mint, byte[] policyId,
                                  byte[] rootKey, byte[] prefix, int prefixLen,
                                  Address scriptAddr) {
        // Discover token names
        byte[] anchorTokenName = ValuesLib.findTokenName(
                anchorInputResolved.value(), policyId, BigInteger.ONE);
        byte[] removingTokenName = ValuesLib.findTokenName(
                removingInputResolved.value(), policyId, BigInteger.ONE);
        byte[] removingKey = extractNodeKey(removingTokenName, prefixLen);

        // Extract datums
        ListElement anchorDatum = (ListElement)(Object) OutputLib.getInlineDatum(anchorInputResolved);
        ListElement removingDatum = (ListElement)(Object) OutputLib.getInlineDatum(removingInputResolved);
        ListElement contDatum = (ListElement)(Object) OutputLib.getInlineDatum(contAnchorOutput);

        // Anchor must link to the removing node
        boolean anchorLinksToRemoving = anchorDatum.nextKey().equals(removingKey);

        // Anchor NFT preserved in continuing output
        BigInteger contQty = ValuesLib.assetOf(contAnchorOutput.value(), policyId, anchorTokenName);
        boolean anchorPreserved = contQty.compareTo(BigInteger.ONE) == 0;

        // Continuing anchor at script address
        boolean contAtScript = Builtins.equalsData(contAnchorOutput.address(), scriptAddr);

        // Anchor userData unchanged
        boolean dataUnchanged = Builtins.equalsData(anchorDatum.userData(), contDatum.userData());

        // Continuing anchor's nextKey = removing node's nextKey (skip over)
        boolean skipCorrect = contDatum.nextKey().equals(removingDatum.nextKey());

        // Removing node's NFT burned
        BigInteger burnQty = ValuesLib.assetOf(mint, policyId, removingTokenName);
        boolean nftBurned = burnQty.compareTo(BigInteger.ONE.negate()) == 0;

        // Exactly 1 token burned
        BigInteger burnCount = ValuesLib.countTokensWithQty(mint, policyId, BigInteger.ONE.negate());
        boolean exactlyOneBurned = burnCount.compareTo(BigInteger.ONE) == 0;

        return anchorLinksToRemoving && anchorPreserved && contAtScript && dataUnchanged
                && skipCorrect && nftBurned && exactlyOneBurned;
    }
}
