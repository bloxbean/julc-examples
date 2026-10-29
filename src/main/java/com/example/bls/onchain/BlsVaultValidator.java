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
 * BLS12-381 signature-locked vault.
 * <p>
 * Funds are released only when the redeemer carries a valid BLS signature (G1, compressed)
 * over the message in the datum, under the public key in the datum (G2, compressed).
 * Verification is the standard min-signature-size pairing check:
 * <pre>
 *   e(signature, G2generator) == e(hashToG1(message), publicKey)
 * </pre>
 * Exercises the BLS12-381 builtins available since Plutus V3 / PV10 (CIP-381):
 * {@code g1Uncompress}, {@code g2Uncompress}, {@code g1HashToGroup}, {@code millerLoop}
 * and {@code finalVerify}.
 */
@SpendingValidator
public class BlsVaultValidator {

    /** Compressed BLS12-381 G2 generator (96 bytes), applied as a script parameter. */
    @Param
    static byte[] g2Generator;

    /** Hash-to-curve domain separation tag, applied as a script parameter. */
    @Param
    static byte[] dst;

    /** Datum: the signer's public key (compressed G2, 96 bytes) and the message it must sign. */
    public record VaultDatum(byte[] publicKey, byte[] message) {}

    /** Redeemer: the signature over the datum's message (compressed G1, 48 bytes). */
    @NewType
    public record Signature(byte[] value) {}

    @Entrypoint
    public static boolean validate(VaultDatum datum, Signature redeemer, ScriptContext ctx) {
        JulcG1 signature = BlsLib.g1Uncompress(redeemer.value());
        JulcG1 messagePoint = BlsLib.g1HashToGroup(datum.message(), dst);
        JulcG2 publicKey = BlsLib.g2Uncompress(datum.publicKey());
        JulcG2 generator = BlsLib.g2Uncompress(g2Generator);

        return BlsLib.finalVerify(
                BlsLib.millerLoop(signature, generator),
                BlsLib.millerLoop(messagePoint, publicKey));
    }
}
