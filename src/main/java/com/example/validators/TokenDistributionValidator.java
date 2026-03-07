package com.example.validators;

import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.Param;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.lib.ContextsLib;
import com.bloxbean.cardano.julc.stdlib.lib.ValuesLib;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.core.types.JulcList;

import java.math.BigInteger;

/**
 * Token Distribution Validator — a treasury that distributes tokens to beneficiaries.
 * <p>
 * The datum holds a list of beneficiary entries (pkh + required amount).
 * On spending, the validator checks that each beneficiary receives at least
 * the required amount in the transaction outputs.
 * <p>
 * This validator demonstrates the new HOF + lambda type inference features:
 * <ul>
 *   <li>{@code list.all(x -> ...)} — verify all beneficiaries are paid</li>
 *   <li>{@code list.any(x -> ...)} — check signatory membership</li>
 *   <li>{@code list.filter(x -> ...)} — find outputs for a given address</li>
 *   <li>Variable capture: outer-scoped variables used inside lambdas</li>
 *   <li>Block body lambdas: multi-statement lambda bodies</li>
 * </ul>
 */
@SpendingValidator
public class TokenDistributionValidator {

    /**
     * The admin PKH authorized to initiate distributions.
     */
    @Param
    static byte[] adminPkh;

    /**
     * Each beneficiary entry: their PKH and the minimum lovelace they must receive.
     */
    record BeneficiaryEntry(byte[] pkh, BigInteger amount) {}

    /**
     * Datum: list of beneficiary entries encoded as Plutus list of constr data.
     * In a real deployment this would be the on-chain distribution plan.
     */
    record DistributionDatum(JulcList<BeneficiaryEntry> beneficiaries) {}

    /**
     * Redeemer actions.
     */
    sealed interface DistributionAction permits Distribute, Cancel {}

    /**
     * Distribute: execute the distribution plan. Requires admin signature.
     */
    record Distribute() implements DistributionAction {}

    /**
     * Cancel: cancel the distribution and return funds. Requires admin signature.
     */
    record Cancel() implements DistributionAction {}

    @Entrypoint
    public static boolean validate(DistributionDatum datum, DistributionAction redeemer,
                                    ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        var sigs = txInfo.signatories();

        // Both actions require admin signature
        ContextsLib.trace("Checking admin signature");
        boolean isAdmin = hasSigner(sigs, adminPkh);

        if (!isAdmin) {
            return false;
        }

        return switch (redeemer) {
            case Distribute d -> {
                ContextsLib.trace("Distribute: checking all beneficiaries paid");
                var outputs = txInfo.outputs();
                yield allBeneficiariesPaid(datum.beneficiaries(), outputs);
            }
            case Cancel c -> {
                // Cancel just needs admin signature (already checked)
                ContextsLib.trace("Cancel: admin authorized");
                yield true;
            }
        };
    }

    /**
     * Verify that every beneficiary receives at least their required amount.
     * Uses {@code list.all()} with a block body lambda and variable capture.
     */
    static boolean allBeneficiariesPaid(JulcList<BeneficiaryEntry> beneficiaries,
                                         JulcList<TxOut> outputs) {
        // Demonstrates: list.all(entry -> { ... }) with captured variable + block body
        return beneficiaries.all(entry -> {
            BigInteger paid = totalPaidTo(outputs, entry.pkh());
            return paid.compareTo(entry.amount()) >= 0;
        });
    }

    /**
     * Calculate total lovelace paid to a specific credential across all outputs.
     * Uses {@code filter()} with a block body lambda to find matching outputs,
     * then sums their lovelace via a for-each loop.
     * <p>
     * Demonstrates: filter + variable capture + block body lambda + switch in lambda
     */
    static BigInteger totalPaidTo(JulcList<TxOut> outputs, byte[] targetPkh) {
        // filter: keep outputs where address matches target PKH
        JulcList<TxOut> matching = outputs.filter(out -> {
            Address addr = out.address();
            Credential cred = addr.credential();
            return switch (cred) {
                case Credential.PubKeyCredential pk ->
                        Builtins.equalsByteString(
                                (byte[])(Object) pk.hash(), targetPkh);
                case Credential.ScriptCredential sc -> false;
            };
        });

        // Sum lovelace amounts from matching outputs
        BigInteger total = BigInteger.ZERO;
        for (var out : matching) {
            BigInteger lovelace = ValuesLib.lovelaceOf(out.value());
            total = total.add(lovelace);
        }
        return total;
    }

    /**
     * Check if a given PKH is among the transaction signatories.
     * Uses {@code list.any()} with inferred (untyped) lambda — the HOF double-unwrap
     * fix now correctly handles ByteStringType params in HOF lambdas.
     */
    static boolean hasSigner(JulcList<PubKeyHash> signatories, byte[] pkh) {
        return signatories.any(sig -> Builtins.equalsByteString(
                (byte[])(Object) sig.hash(), pkh));
    }
}
