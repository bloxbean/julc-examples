package com.example.validators;

import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.MintingValidator;
import org.julclang.ledger.ScriptContext;
import org.julclang.ledger.TxInfo;
import org.julclang.ledger.PubKeyHash;
import org.julclang.stdlib.lib.ContextsLib;

/**
 * Multi-sig minting policy demonstrating a 3-variant sealed interface redeemer.
 * <p>
 * Three redeemer variants with different field counts:
 * - MintByAuthority(authority): 1 field, authority must sign
 * - BurnByOwner(): 0 fields, always passes
 * - MintByMultiSig(signer1, signer2): 2 fields, both must sign
 * <p>
 * Features demonstrated:
 * - @MintingPolicy annotation (no datum)
 * - Sealed interface with 3 variants
 * - Switch expression with 3 branches
 * - Varying field counts per variant (1, 0, 2)
 */
@MintingValidator
public class MultiSigMinting {

    sealed interface MintAction permits MintByAuthority, BurnByOwner, MintByMultiSig {}
    record MintByAuthority(byte[] authority) implements MintAction {}
    record BurnByOwner() implements MintAction {}
    record MintByMultiSig(byte[] signer1, byte[] signer2) implements MintAction {}

    @Entrypoint
    public static boolean validate(MintAction redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        ContextsLib.trace("MultiSigMinting validate");
        return switch (redeemer) {
            case MintByAuthority m -> {
                ContextsLib.trace("MintByAuthority path");
                yield txInfo.signatories().contains(PubKeyHash.of(m.authority()));
            }
            case BurnByOwner b -> {
                ContextsLib.trace("BurnByOwner path");
                yield true;
            }
            case MintByMultiSig ms -> {
                ContextsLib.trace("MintByMultiSig path");
                yield txInfo.signatories().contains(PubKeyHash.of(ms.signer1()))
                        && txInfo.signatories().contains(PubKeyHash.of(ms.signer2()));
            }
        };
    }
}
