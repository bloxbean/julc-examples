package com.example.validators;

import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.MintingValidator;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.ledger.TxInfo;
import com.bloxbean.cardano.julc.core.PlutusData;

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
        byte[] pkh = (byte[])(Object) Builtins.unBData(redeemer);
        return txInfo.signatories().any(sig -> sig.hash().equals(pkh));
    }
}
