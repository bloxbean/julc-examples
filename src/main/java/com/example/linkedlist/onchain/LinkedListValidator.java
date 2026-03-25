package com.example.linkedlist.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.MultiValidator;
import com.bloxbean.cardano.julc.stdlib.annotation.Param;
import com.bloxbean.cardano.julc.stdlib.annotation.Purpose;
import com.bloxbean.cardano.julc.stdlib.lib.ContextsLib;

import java.math.BigInteger;
import java.util.Optional;

/**
 * On-chain linked list validator — MINT + SPEND.
 * <p>
 * Each list element is a UTXO holding a unique NFT. Nodes link via datum nextKey fields.
 * The MINT policy validates structural mutations (init/deinit/insert/remove).
 * The SPEND validator delegates to the MINT policy via the coupling pattern.
 */
@MultiValidator
public class LinkedListValidator {

    @Param static byte[] rootKey;       // e.g. "ROOT" — token name for the root NFT
    @Param static byte[] prefix;        // e.g. "NODE" — prefix for node token names
    @Param static BigInteger prefixLen; // e.g. 4 — byte length of prefix

    // --- Redeemer variants (tag order = position in permits) ---

    sealed interface ListAction permits InitList, DeinitList, InsertNode, RemoveNode {}
    record InitList(BigInteger rootOutputIndex) implements ListAction {}                     // tag 0
    record DeinitList(BigInteger rootInputIndex) implements ListAction {}                    // tag 1
    record InsertNode(BigInteger anchorInputIndex,
                      BigInteger contAnchorOutputIndex,
                      BigInteger newElementOutputIndex) implements ListAction {}             // tag 2
    record RemoveNode(BigInteger anchorInputIndex,
                      BigInteger removingInputIndex,
                      BigInteger contAnchorOutputIndex) implements ListAction {}             // tag 3

    @Entrypoint(purpose = Purpose.MINT)
    public static boolean mint(ListAction redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        ScriptInfo.MintingScript mintInfo = (ScriptInfo.MintingScript) ctx.scriptInfo();
        byte[] policyBytes = PlutusData.cast(mintInfo.policyId(), byte[].class);

        return switch (redeemer) {
            case InitList init -> {
                Address scriptAddr = new Address(
                        new Credential.ScriptCredential(PlutusData.cast(policyBytes, ScriptHash.class)),
                        Optional.empty());
                TxOut rootOutput = txInfo.outputs().get(init.rootOutputIndex().intValue());
                yield LinkedListLib.validateInit(
                        rootOutput, txInfo.mint(), policyBytes, rootKey, scriptAddr);
            }
            case DeinitList deinit -> {
                TxInInfo rootInput = txInfo.inputs().get(deinit.rootInputIndex().intValue());
                yield LinkedListLib.validateDeinit(
                        rootInput.resolved(), txInfo.mint(), policyBytes, rootKey);
            }
            case InsertNode insert -> {
                Address scriptAddr = new Address(
                        new Credential.ScriptCredential(PlutusData.cast(policyBytes, ScriptHash.class)),
                        Optional.empty());
                TxInInfo anchorInput = txInfo.inputs().get(insert.anchorInputIndex().intValue());
                TxOut contAnchorOutput = txInfo.outputs().get(insert.contAnchorOutputIndex().intValue());
                TxOut newElementOutput = txInfo.outputs().get(insert.newElementOutputIndex().intValue());
                yield LinkedListLib.validateInsert(
                        anchorInput.resolved(), contAnchorOutput, newElementOutput,
                        txInfo.mint(), policyBytes, rootKey, prefix, prefixLen.intValue(), scriptAddr);
            }
            case RemoveNode remove -> {
                Address scriptAddr = new Address(
                        new Credential.ScriptCredential(PlutusData.cast(policyBytes, ScriptHash.class)),
                        Optional.empty());
                TxInInfo anchorInput = txInfo.inputs().get(remove.anchorInputIndex().intValue());
                TxInInfo removingInput = txInfo.inputs().get(remove.removingInputIndex().intValue());
                TxOut contAnchorOutput = txInfo.outputs().get(remove.contAnchorOutputIndex().intValue());
                yield LinkedListLib.validateRemove(
                        anchorInput.resolved(), removingInput.resolved(), contAnchorOutput,
                        txInfo.mint(), policyBytes, rootKey, prefix, prefixLen.intValue(), scriptAddr);
            }
        };
    }

    @Entrypoint(purpose = Purpose.SPEND)
    public static boolean spend(Optional<PlutusData> datum, PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        byte[] ownHash = ContextsLib.ownHash(ctx);
        return LinkedListLib.requireListTokensMintedOrBurned(txInfo.mint(), ownHash);
    }
}
