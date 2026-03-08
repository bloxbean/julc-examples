package com.example.cftemplates.paymentsplitter.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.Param;
import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;
import com.bloxbean.cardano.julc.stdlib.lib.AddressLib;
import com.bloxbean.cardano.julc.stdlib.lib.ValuesLib;

import java.math.BigInteger;
import java.util.Optional;

/**
 * Payment Splitter validator — distributes locked funds equally among payees.
 * <p>
 * All outputs must go to payees in the list, and each payee must receive
 * the same net amount (accounting for fee payer's change).
 * <p>
 * Based on: cardano-template-and-ecosystem-monitoring/payment-splitter
 */
@SpendingValidator
public class CfPaymentSplitterValidator {

    @Param static JulcList<byte[]> payees;

    record SplitterDatum(byte[] owner) {}
    record SplitterRedeemer(byte[] message) {}

    @Entrypoint
    public static boolean validate(Optional<PlutusData> datum, PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();

        // Check no additional payees: every output credential must be in payees list
        boolean noAdditional = checkNoAdditionalPayees(txInfo.outputs());
        if (!noAdditional) return false;

        // Compute first payee's net amount as reference
        byte[] firstPayee = payees.head();
        BigInteger expectedNet = computeNetForPayee(firstPayee, txInfo.outputs(), txInfo.inputs(), txInfo.fee());

        // Verify all payees receive the same net amount (recursive to avoid nested while)
        return verifyEqualPayments(payees.tail(), txInfo.outputs(), txInfo.inputs(), txInfo.fee(), expectedNet);
    }

    static boolean checkNoAdditionalPayees(JulcList<TxOut> outputs) {
        boolean valid = true;
        for (var output : outputs) {
            byte[] credHash = AddressLib.credentialHash(output.address());
            if (!payees.any(p -> p.equals(credHash))) {
                valid = false;
                break;
            }
        }
        return valid;
    }

    static boolean verifyEqualPayments(JulcList<byte[]> remaining, JulcList<TxOut> outputs,
                                       JulcList<TxInInfo> inputs, BigInteger fee, BigInteger expectedNet) {
        if (remaining.isEmpty()) return true;
        byte[] payee = remaining.head();
        BigInteger net = computeNetForPayee(payee, outputs, inputs, fee);
        if (!net.equals(expectedNet)) return false;
        return verifyEqualPayments(remaining.tail(), outputs, inputs, fee, expectedNet);
    }

    static BigInteger computeNetForPayee(byte[] payee, JulcList<TxOut> outputs,
                                          JulcList<TxInInfo> inputs, BigInteger fee) {
        BigInteger outputSum = sumOutputsForPayee(outputs, payee);
        BigInteger inputSum = sumInputsForPayee(inputs, payee);
        if (inputSum.compareTo(BigInteger.ZERO) > 0) {
            BigInteger change = inputSum.subtract(fee);
            return outputSum.subtract(change);
        }
        return outputSum;
    }

    static BigInteger sumOutputsForPayee(JulcList<TxOut> outputs, byte[] payeePkh) {
        BigInteger total = BigInteger.ZERO;
        for (var output : outputs) {
            byte[] credHash = AddressLib.credentialHash(output.address());
            if (credHash.equals(payeePkh)) {
                total = total.add(ValuesLib.lovelaceOf(output.value()));
            }
        }
        return total;
    }

    static BigInteger sumInputsForPayee(JulcList<TxInInfo> inputs, byte[] payeePkh) {
        BigInteger total = BigInteger.ZERO;
        for (var input : inputs) {
            byte[] credHash = AddressLib.credentialHash(input.resolved().address());
            if (credHash.equals(payeePkh)) {
                total = total.add(ValuesLib.lovelaceOf(input.resolved().value()));
            }
        }
        return total;
    }
}
