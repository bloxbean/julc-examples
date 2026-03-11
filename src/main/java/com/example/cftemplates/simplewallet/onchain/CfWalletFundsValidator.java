package com.example.cftemplates.simplewallet.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.MultiValidator;
import com.bloxbean.cardano.julc.stdlib.annotation.Param;
import com.bloxbean.cardano.julc.stdlib.annotation.Purpose;
import com.bloxbean.cardano.julc.stdlib.lib.*;

import java.math.BigInteger;
import java.util.Optional;

/**
 * Wallet Funds validator — holds actual funds for the simple wallet.
 * <p>
 * SPEND ExecuteTx: Execute a payment intent. Finds the intent UTXO (from wallet script),
 *   extracts PaymentIntent datum, verifies payment to recipient, burns intent marker.
 * SPEND WithdrawFunds: Owner withdraws funds directly.
 * <p>
 * Based on: cardano-template-and-ecosystem-monitoring/simple-wallet (funds.ak)
 */
@MultiValidator
public class CfWalletFundsValidator {

    @Param static byte[] owner;
    @Param static byte[] walletPolicyId;

    // PaymentIntent must match CfSimpleWalletValidator's record layout
    record PaymentIntent(PlutusData recipient, BigInteger lovelaceAmt, byte[] data) {}

    sealed interface FundsAction permits ExecuteTx, WithdrawFunds {}
    record ExecuteTx() implements FundsAction {}
    record WithdrawFunds() implements FundsAction {}

    @Entrypoint(purpose = Purpose.SPEND)
    public static boolean spend(Optional<PlutusData> datum, FundsAction redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        boolean ownerSigned = ContextsLib.signedBy(txInfo, owner);
        if (!ownerSigned) return false;

        return switch (redeemer) {
            case ExecuteTx e -> handleExecuteTx(txInfo);
            case WithdrawFunds w -> true; // Owner signed is sufficient
        };
    }

    static boolean handleExecuteTx(TxInfo txInfo) {
        // Find intent input from wallet script
        TxInInfo intentInput = findIntentInput(txInfo.inputs(), walletPolicyId);
        PlutusData intentDatumData = OutputLib.getInlineDatum(intentInput.resolved());
        PaymentIntent intent = (PaymentIntent)(Object) intentDatumData;

        // Compute how much lovelace goes to recipient
        Address recipientAddr = (Address)(Object) intent.recipient();
        BigInteger amtToRecipient = sumLovelaceToAddress(txInfo.outputs(), recipientAddr);

        // Must pay exactly the specified amount (matches Aiken's equality check)
        boolean paymentOk = amtToRecipient.compareTo(intent.lovelaceAmt()) == 0;

        // Intent marker must be burned
        boolean markerBurned = ValuesLib.countTokensWithQty(
                txInfo.mint(), walletPolicyId, BigInteger.ONE.negate())
                .compareTo(BigInteger.ONE) == 0;

        return paymentOk && markerBurned;
    }

    static TxInInfo findIntentInput(JulcList<TxInInfo> inputs, byte[] walletPolicy) {
        TxInInfo result = (TxInInfo)(Object) Builtins.mkNilData();
        for (var input : inputs) {
            byte[] credHash = AddressLib.credentialHash(input.resolved().address());
            if (credHash.equals(walletPolicy)) {
                if (ValuesLib.containsPolicy(input.resolved().value(), walletPolicy)) {
                    result = input;
                    break;
                }
            }
        }
        return result;
    }

    static BigInteger sumLovelaceToAddress(JulcList<TxOut> outputs, Address addr) {
        BigInteger total = BigInteger.ZERO;
        for (var output : outputs) {
            if (Builtins.equalsData(output.address(), addr)) {
                total = total.add(ValuesLib.lovelaceOf(output.value()));
            }
        }
        return total;
    }

}
