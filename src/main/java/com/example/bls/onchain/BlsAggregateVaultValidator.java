package com.example.bls.onchain;

import org.julclang.ledger.ScriptContext;
import org.julclang.core.types.JulcG1;
import org.julclang.core.types.JulcG2;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.NewType;
import org.julclang.stdlib.annotation.Param;
import org.julclang.stdlib.annotation.SpendingValidator;
import org.julclang.stdlib.lib.BlsLib;

/**
 * BLS12-381 2-of-2 aggregate-signature vault.
 * <p>
 * Both public keys in the datum must have signed the datum's message; the redeemer carries the
 * aggregated signature (the sum of the two G1 signatures). The aggregate public key is the sum
 * of the two G2 keys:
 * <pre>
 *   aggKey = publicKey1 + publicKey2
 *   e(aggSignature, G2generator) == e(hashToG1(message), aggKey)
 * </pre>
 * Adds {@code g1Add}/{@code g2Add} to the builtins the single-key vault exercises.
 * <p>
 * Note: this is a builtin-coverage example, not a production aggregation scheme — it does not
 * defend against rogue-key attacks.
 */
@SpendingValidator
public class BlsAggregateVaultValidator {

    /** Compressed BLS12-381 G2 generator (96 bytes), applied as a script parameter. */
    @Param
    static byte[] g2Generator;

    /** Hash-to-curve domain separation tag, applied as a script parameter. */
    @Param
    static byte[] dst;

    /** Datum: two public keys (compressed G2, 96 bytes each) and the message both must sign. */
    public record AggVaultDatum(byte[] publicKey1, byte[] publicKey2, byte[] message) {}

    /** Redeemer: the aggregated signature over the datum's message (compressed G1, 48 bytes). */
    @NewType
    public record AggSignature(byte[] value) {}

    @Entrypoint
    public static boolean validate(AggVaultDatum datum, AggSignature redeemer, ScriptContext ctx) {
        JulcG2 aggregateKey = BlsLib.g2Add(
                BlsLib.g2Uncompress(datum.publicKey1()),
                BlsLib.g2Uncompress(datum.publicKey2()));

        JulcG1 signature = BlsLib.g1Uncompress(redeemer.value());
        JulcG1 messagePoint = BlsLib.g1HashToGroup(datum.message(), dst);
        JulcG2 generator = BlsLib.g2Uncompress(g2Generator);

        return BlsLib.finalVerify(
                BlsLib.millerLoop(signature, generator),
                BlsLib.millerLoop(messagePoint, aggregateKey));
    }
}
