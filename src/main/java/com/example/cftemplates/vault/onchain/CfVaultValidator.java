package com.example.cftemplates.vault.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.MultiValidator;
import com.bloxbean.cardano.julc.stdlib.annotation.Param;
import com.bloxbean.cardano.julc.stdlib.annotation.Purpose;
import com.bloxbean.cardano.julc.stdlib.lib.ContextsLib;
import com.bloxbean.cardano.julc.stdlib.lib.IntervalLib;
import com.bloxbean.cardano.julc.stdlib.lib.ValuesLib;

import java.math.BigInteger;
import java.util.Optional;

/**
 * Vault validator — two-phase withdrawal with time lock.
 * <p>
 * WITHDRAW: Initiates withdrawal by locking funds back with a lock_time datum.
 * FINALIZE: Completes withdrawal after waitTime has passed since lock_time.
 * CANCEL: Cancels pending withdrawal, returning funds to the contract.
 * <p>
 * Based on: cardano-template-and-ecosystem-monitoring/vault
 */
@MultiValidator
public class CfVaultValidator {

    @Param static byte[] owner;
    @Param static BigInteger waitTime;

    public record WithdrawDatum(BigInteger lockTime) {}

    public sealed interface VaultAction permits Withdraw, Finalize, Cancel {}
    public record Withdraw() implements VaultAction {}
    public record Finalize() implements VaultAction {}
    public record Cancel() implements VaultAction {}

    @Entrypoint(purpose = Purpose.SPEND)
    public static boolean validate(Optional<WithdrawDatum> datum, VaultAction redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        boolean ownerSigned = ContextsLib.signedBy(txInfo, owner);
        if (!ownerSigned) return false;

        return switch (redeemer) {
            case Withdraw w -> handleWithdraw(txInfo, ctx);
            case Finalize f -> handleFinalize(datum, txInfo);
            case Cancel c -> handleCancel(txInfo, ctx);
        };
    }

    static boolean handleWithdraw(TxInfo txInfo, ScriptContext ctx) {
        TxOut ownInput = ContextsLib.findOwnInput(ctx).get().resolved();
        Address scriptAddress = ownInput.address();
        BigInteger inputLovelace = ValuesLib.lovelaceOf(ownInput.value());

        BigInteger outputLovelace = BigInteger.ZERO;
        boolean allValid = true;
        for (var output : txInfo.outputs()) {
            if (Builtins.equalsData(output.address(), scriptAddress)) {
                outputLovelace = outputLovelace.add(ValuesLib.lovelaceOf(output.value()));
                if (!checkContinuingDatum(output, txInfo)) {
                    allValid = false;
                    break;
                }
            }
        }

        return allValid && outputLovelace.compareTo(inputLovelace) >= 0;
    }

    static boolean checkContinuingDatum(TxOut output, TxInfo txInfo) {
        OutputDatum od = output.datum();
        return switch (od) {
            case OutputDatum.OutputDatumInline inline -> checkLockTimeValid(inline.datum(), txInfo);
            case OutputDatum.NoOutputDatum ignored -> true;
            case OutputDatum.OutputDatumHash ignored -> true;
        };
    }

    static boolean checkLockTimeValid(PlutusData datumData, TxInfo txInfo) {
        WithdrawDatum wd = (WithdrawDatum)(Object) datumData;
        BigInteger lowerBound = IntervalLib.finiteLowerBound(txInfo.validRange());
        return lowerBound.compareTo(wd.lockTime()) >= 0;
    }

    static boolean handleFinalize(Optional<WithdrawDatum> datum, TxInfo txInfo) {
        WithdrawDatum wd = datum.get();
        BigInteger unlockTime = waitTime.add(wd.lockTime());
        BigInteger lowerBound = IntervalLib.finiteLowerBound(txInfo.validRange());
        return lowerBound.compareTo(unlockTime) >= 0;
    }

    static boolean handleCancel(TxInfo txInfo, ScriptContext ctx) {
        TxOut ownInput = ContextsLib.findOwnInput(ctx).get().resolved();
        Address scriptAddress = ownInput.address();
        BigInteger inputLovelace = ValuesLib.lovelaceOf(ownInput.value());

        BigInteger outputLovelace = BigInteger.ZERO;
        for (var output : txInfo.outputs()) {
            if (Builtins.equalsData(output.address(), scriptAddress)) {
                outputLovelace = outputLovelace.add(ValuesLib.lovelaceOf(output.value()));
            }
        }

        return outputLovelace.compareTo(inputLovelace) >= 0;
    }
}
