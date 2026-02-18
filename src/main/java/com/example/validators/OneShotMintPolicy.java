package com.example.validators;

import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.MintingValidator;
import com.bloxbean.cardano.julc.stdlib.annotation.Param;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.ledger.TxInfo;
import com.bloxbean.cardano.julc.ledger.TxOutRef;
import com.bloxbean.cardano.julc.stdlib.lib.ContextsLib;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.core.PlutusData;

import java.math.BigInteger;

@MintingValidator
public class OneShotMintPolicy {

    @Param
    static byte[] utxoTxId;

    @Param
    static BigInteger utxoIndex;

    @Entrypoint
    public static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        ContextsLib.trace("Checking UTXO input");
        boolean found = false;
        for (var input : txInfo.inputs()) {
            TxOutRef ref = input.outRef();
            byte[] refTxIdBytes = Builtins.toByteString(ref.txId());
            found = Builtins.equalsByteString(refTxIdBytes, utxoTxId) && ref.index().compareTo(utxoIndex) == 0;
            if (found) {
                break;                        // ← break is separate from assignment
            }
        }
        return found;
    }
}
