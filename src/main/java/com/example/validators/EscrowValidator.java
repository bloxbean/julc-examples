package com.example.validators;

import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;
import com.bloxbean.cardano.julc.stdlib.annotation.Validator;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.ledger.TxInfo;
import com.bloxbean.cardano.julc.stdlib.lib.ContextsLib;
import com.example.util.ValidationUtils;

import java.math.BigInteger;

@SpendingValidator
public class EscrowValidator {

    record EscrowDatum(byte[] seller, byte[] buyer, BigInteger deadline, BigInteger price) {}

    record EscrowRedeemer(BigInteger action) {}

    @Entrypoint
    public static boolean validate(EscrowDatum datum, EscrowRedeemer redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        ContextsLib.trace("Escrow validate");
        if (redeemer.action().compareTo(BigInteger.ZERO) == 0) {
            ContextsLib.trace("Complete path");
            return checkComplete(txInfo, datum);
        } else {
            ContextsLib.trace("Refund path");
            return checkRefund(txInfo, datum);
        }
    }

    static boolean checkComplete(TxInfo txInfo, EscrowDatum datum) {
        boolean buyerSigned = ValidationUtils.hasSigner(txInfo, datum.buyer());
        boolean sellerSigned = ValidationUtils.hasSigner(txInfo, datum.seller());

        boolean sellerPaid = false;
        for (var output : txInfo.outputs()) {
            sellerPaid = sellerPaid || ValidationUtils.hasMinLovelace(output.value(), datum.price());
        }

        return buyerSigned && sellerSigned && sellerPaid;
    }

    static boolean checkRefund(TxInfo txInfo, EscrowDatum datum) {
        boolean sellerSigned = ValidationUtils.hasSigner(txInfo, datum.seller());
        boolean pastDeadline = ValidationUtils.isAfterDeadline(txInfo, datum.deadline());
        return sellerSigned && pastDeadline;
    }
}
