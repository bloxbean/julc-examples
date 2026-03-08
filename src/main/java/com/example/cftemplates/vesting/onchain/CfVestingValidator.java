package com.example.cftemplates.vesting.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.ledger.TxInfo;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;
import com.bloxbean.cardano.julc.stdlib.lib.ContextsLib;
import com.bloxbean.cardano.julc.stdlib.lib.IntervalLib;

import java.math.BigInteger;

/**
 * Vesting validator — funds are locked until a time, then can be claimed
 * by the beneficiary. The owner can reclaim at any time.
 * <p>
 * Based on: cardano-template-and-ecosystem-monitoring/vesting
 */
@SpendingValidator
public class CfVestingValidator {

    record VestingDatum(BigInteger lockUntil, byte[] owner, byte[] beneficiary) {}

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