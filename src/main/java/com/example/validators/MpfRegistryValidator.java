package com.example.validators;

import org.julclang.stdlib.annotation.SpendingValidator;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.ledger.ScriptContext;
import com.example.mpf.MerklePatriciaForestry;
import org.julclang.core.types.JulcList;
import com.example.mpf.ProofStep;

/**
 * MPF Registry spending validator.
 * <p>
 * A UTXO is locked with the trie root hash as datum.
 * To spend, provide a redeemer with:
 * - VerifyHas(key, value, proof): prove key-value pair exists in the trie
 * - VerifyMiss(key, proof): prove key is absent from the trie
 * <p>
 * Redeemer is a sealed interface (ADT) with two variants.
 * Uses the MerklePatriciaForestry @OnchainLibrary for on-chain proof verification.
 */
@SpendingValidator
public class MpfRegistryValidator {

    record MpfDatum(byte[] trieRoot) {}

    sealed interface MpfAction permits VerifyHas, VerifyMiss {}
    record VerifyHas(byte[] key, byte[] value, JulcList<ProofStep> proof) implements MpfAction {}
    record VerifyMiss(byte[] key, JulcList<ProofStep> proof) implements MpfAction {}

    @Entrypoint
    public static boolean validate(MpfDatum datum, MpfAction redeemer, ScriptContext ctx) {
        return switch (redeemer) {
            case VerifyHas has ->
                MerklePatriciaForestry.has(datum.trieRoot(), has.key(), has.value(), has.proof());
            case VerifyMiss miss ->
                MerklePatriciaForestry.miss(datum.trieRoot(), miss.key(), miss.proof());
        };
    }
}
