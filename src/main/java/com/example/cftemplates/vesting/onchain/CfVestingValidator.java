package com.example.cftemplates.vesting.onchain;

import org.julclang.core.PlutusData;
import org.julclang.ledger.ScriptContext;
import org.julclang.ledger.TxInfo;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.SpendingValidator;
import org.julclang.stdlib.lib.ContextsLib;
import org.julclang.stdlib.lib.IntervalLib;

import java.math.BigInteger;

/**
 * Vesting validator — funds are locked until a time, then can be claimed
 * by the beneficiary. The owner can reclaim at any time.
 * <p>
 * Based on: cardano-template-and-ecosystem-monitoring/vesting
 */
@SpendingValidator
public class CfVestingValidator {

    public record VestingDatum(BigInteger lockUntil, byte[] owner, byte[] beneficiary) {}

    @Entrypoint
    public static boolean validate(VestingDatum datum, PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();

        boolean ownerSigned = ContextsLib.signedBy(txInfo, datum.owner());
        if (ownerSigned) return true;

        boolean beneficiarySigned = ContextsLib.signedBy(txInfo, datum.beneficiary());
        BigInteger lowerBound = IntervalLib.finiteLowerBound(txInfo.validRange());
        boolean afterLockTime = lowerBound.compareTo(datum.lockUntil()) >= 0;

        return beneficiarySigned && afterLockTime;
    }
}
