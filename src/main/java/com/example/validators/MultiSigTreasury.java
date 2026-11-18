package com.example.validators;

import org.julclang.stdlib.annotation.SpendingValidator;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.ledger.ScriptContext;
import org.julclang.ledger.TxInfo;
import org.julclang.ledger.PubKeyHash;
import org.julclang.stdlib.lib.ContextsLib;

import java.math.BigInteger;

/**
 * Multi-signature treasury spending validator.
 * <p>
 * Datum holds two signer PubKeyHashes.
 * Both signers must be present in the transaction signatories to unlock.
 * <p>
 * Features demonstrated:
 * - Custom datum record with byte[] fields
 * - Helper method with boolean logic
 * - Typed ScriptContext access (ctx.txInfo().signatories())
 * - Chained list method (.contains())
 * - Boolean && logic
 */
@SpendingValidator
public class MultiSigTreasury {

    record TreasuryDatum(byte[] signer1, byte[] signer2) {}

    @Entrypoint
    public static boolean validate(TreasuryDatum datum, BigInteger redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        ContextsLib.trace("Checking signers");
        return checkBothSigners(txInfo, datum.signer1(), datum.signer2());
    }

    static boolean checkBothSigners(TxInfo txInfo, byte[] s1, byte[] s2) {
        var sigs = txInfo.signatories();
        boolean hasSigner1 = sigs.contains(PubKeyHash.of(s1));
        boolean hasSigner2 = sigs.contains(PubKeyHash.of(s2));
        return hasSigner1 && hasSigner2;
    }
}
