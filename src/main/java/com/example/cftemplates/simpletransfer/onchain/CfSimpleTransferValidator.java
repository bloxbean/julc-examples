package com.example.cftemplates.simpletransfer.onchain;

import org.julclang.core.PlutusData;
import org.julclang.ledger.ScriptContext;
import org.julclang.ledger.TxInfo;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.Param;
import org.julclang.stdlib.annotation.SpendingValidator;
import org.julclang.stdlib.lib.ContextsLib;

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