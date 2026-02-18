package com.example.validators;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.stdlib.lib.OutputLib;

import java.math.BigInteger;

/**
 * Spending validator that verifies a minimum lovelace payment is made to a recipient.
 * <p>
 * Uses OutputLib methods: lovelacePaidTo, countOutputsAt.
 * <p>
 * The datum stores the full recipient Address (as PlutusData) and minimum amount.
 * At UPLC level, Address is just a Constr — the datum contains it directly.
 */
@SpendingValidator
public class OutputCheckValidator {

    record PaymentDatum(Address recipientAddress, BigInteger minAmount) {}

    @Entrypoint
    public static boolean validate(PaymentDatum datum, PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        JulcList<TxOut> outputs = txInfo.outputs();
        Address recipientAddr =  datum.recipientAddress();

        BigInteger paid = OutputLib.lovelacePaidTo(outputs, recipientAddr);
        boolean enoughPaid = paid.compareTo(datum.minAmount()) >= 0;

        long outputCount = OutputLib.countOutputsAt(outputs, recipientAddr);
        boolean hasOutput = outputCount > 0;

        return enoughPaid && hasOutput;
    }
}
