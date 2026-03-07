package com.example.validators;

import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.lib.ContextsLib;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.core.types.JulcList;

/**
 * Whitelist Treasury Validator — a multi-signature treasury with configurable threshold.
 * <p>
 * The datum holds a whitelist of authorized PubKeyHashes and a required signature
 * threshold. To spend, at least {@code threshold} signatories from the whitelist
 * must sign the transaction.
 * <p>
 * This validator demonstrates the ByteStringType HOF double-unwrap fix:
 * <ul>
 *   <li>Untyped lambdas on {@code JulcList<PubKeyHash>} — no explicit type needed</li>
 *   <li>Nested HOFs: {@code filter(w -> signatories.any(sig -> ...))} — outer + inner unwrap</li>
 *   <li>{@code list.filter()} with variable capture of ByteStringType-mapped vars</li>
 *   <li>{@code list.any()} with untyped lambda on ByteStringType elements</li>
 * </ul>
 */
@SpendingValidator
public class WhitelistTreasuryValidator {

    /**
     * Datum: the authorized signers and required threshold.
     */
    record WhitelistDatum(JulcList<PubKeyHash> authorizedSigners, long threshold) {}

    /**
     * Redeemer actions.
     */
    sealed interface TreasuryAction permits Withdraw, UpdateWhitelist {}

    /**
     * Withdraw: spend treasury funds if enough whitelisted signers approve.
     */
    record Withdraw(long amount) implements TreasuryAction {}

    /**
     * UpdateWhitelist: requires ALL current whitelisted signers to approve.
     */
    record UpdateWhitelist() implements TreasuryAction {}

    @Entrypoint
    public static boolean validate(WhitelistDatum datum, TreasuryAction redeemer,
                                    ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        var signatories = txInfo.signatories();

        return switch (redeemer) {
            case Withdraw w -> {
                ContextsLib.trace("Withdraw: checking threshold signatures");
                long matchCount = countMatchingSigners(
                        datum.authorizedSigners(), signatories);
                yield matchCount >= datum.threshold();
            }
            case UpdateWhitelist u -> {
                ContextsLib.trace("UpdateWhitelist: all signers must approve");
                yield allWhitelistedSigned(datum.authorizedSigners(), signatories);
            }
        };
    }

    /**
     * Count how many whitelisted signers are present in the transaction signatories.
     * <p>
     * Uses nested HOFs with untyped lambdas — the key feature being tested:
     * {@code whitelist.filter(w -> signatories.any(sig -> equalsByteString(w.hash(), sig.hash())))}
     * <p>
     * Both {@code w} and {@code sig} are ByteStringType (PubKeyHash), and both lambdas
     * are untyped — the HOF double-unwrap fix ensures each is unwrapped exactly once.
     */
    static long countMatchingSigners(JulcList<PubKeyHash> whitelist,
                                      JulcList<PubKeyHash> signatories) {
        // Nested HOF: filter whitelist entries that appear in signatories
        JulcList<PubKeyHash> matched = whitelist.filter(w ->
                signatories.any(sig -> Builtins.equalsByteString(
                        (byte[])(Object) w.hash(), (byte[])(Object) sig.hash()))
        );
        return matched.size();
    }

    /**
     * Check if a specific PKH is in the signatories list.
     * Uses untyped lambda on JulcList&lt;PubKeyHash&gt;.
     */
    static boolean isSigner(JulcList<PubKeyHash> signatories, byte[] pkh) {
        return signatories.any(sig -> Builtins.equalsByteString(
                (byte[])(Object) sig.hash(), pkh));
    }

    /**
     * Check if ALL whitelisted signers have signed.
     * Uses {@code list.all()} with an untyped lambda that captures {@code signatories}.
     */
    static boolean allWhitelistedSigned(JulcList<PubKeyHash> whitelist,
                                         JulcList<PubKeyHash> signatories) {
        return whitelist.all(w ->
                signatories.any(sig -> Builtins.equalsByteString(
                        (byte[])(Object) w.hash(), (byte[])(Object) sig.hash()))
        );
    }
}
