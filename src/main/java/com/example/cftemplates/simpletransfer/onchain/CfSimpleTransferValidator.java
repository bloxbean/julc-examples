package com.example.cftemplates.simpletransfer.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.ledger.TxInfo;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.Param;
import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;
import com.bloxbean.cardano.julc.stdlib.lib.ContextsLib;

import java.util.Optional;

/**
 * Simple Transfer validator — parameterized by a receiver's pub key hash.
 * Funds locked at this script can only be unlocked by the designated receiver.
 * <p>
 * Datum and redeemer are unused/ignored.
 * <p>
 * Based on: cardano-template-and-ecosystem-monitoring/simple-transfer
 */
@SpendingValidator
public class CfSimpleTransferValidator {

    @Param
    static byte[] receiver;

    @Entrypoint
    public static boolean validate(Optional<PlutusData> datum, PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        return ContextsLib.signedBy(txInfo, receiver);
    }
}