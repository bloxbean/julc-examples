package com.example.validators;

import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.MintingValidator;
import org.julclang.stdlib.Builtins;
import org.julclang.ledger.ScriptContext;
import org.julclang.ledger.TxInfo;
import org.julclang.core.PlutusData;

/**
 * Authorized minting policy requiring a specific signer.
 * <p>
 * The redeemer is the authorizer's PubKeyHash. The policy checks
 * that the transaction is signed by that key.
 * <p>
 * Features demonstrated:
 * - @MintingPolicy annotation (no datum)
 * - Typed ScriptContext access
 * - Signer check via signatories().any() with .hash().equals()
 */
@MintingValidator
public class GuardedMinting {

    @Entrypoint
    public static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        byte[] pkh = Builtins.unBData(redeemer);
        return txInfo.signatories().any(sig -> sig.hash().equals(pkh));
    }
}
