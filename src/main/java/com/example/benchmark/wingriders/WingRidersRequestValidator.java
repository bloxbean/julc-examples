package com.example.benchmark.wingriders;

import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.Param;
import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.stdlib.lib.ByteStringLib;

import java.math.BigInteger;

/**
 * WingRiders Request Validator.
 */
@SpendingValidator
public class WingRidersRequestValidator {

    @Param
    static byte[] poolHash;

    // --- Swap direction ---
    sealed interface SwapDirection permits SwapAToB, SwapBToA {}
    record SwapAToB() implements SwapDirection {}
    record SwapBToA() implements SwapDirection {}

    // --- Request action ---
    sealed interface RequestAction permits Swap, AddLiquidity, WithdrawLiquidity, ExtractTreasury, AddStakingRewards {}
    record Swap(SwapDirection direction, BigInteger minWantedTokens) implements RequestAction {}
    record AddLiquidity(BigInteger minWantedShares) implements RequestAction {}
    record WithdrawLiquidity(BigInteger minWantedA, BigInteger minWantedB) implements RequestAction {}
    record ExtractTreasury() implements RequestAction {}
    record AddStakingRewards() implements RequestAction {}

    // --- Request datum ---
    record RequestDatum(byte[] beneficiaryHash, boolean beneficiaryIsScript,
                        byte[] ownerHash, BigInteger deadline,
                        byte[] aPolicyId, byte[] aAssetName, byte[] bPolicyId, byte[] bAssetName,
                        RequestAction action) {}

    // --- Request redeemer ---
    sealed interface RequestRedeemer permits Apply, Reclaim {}
    record Apply(BigInteger poolIndex) implements RequestRedeemer {}
    record Reclaim() implements RequestRedeemer {}

    @Entrypoint
    public static boolean validate(RequestDatum datum, RequestRedeemer redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();

        return switch (redeemer) {
            case Apply apply -> {
                int poolIdx = apply.poolIndex().intValue();
                TxInInfo poolInput = txInfo.inputs().get(poolIdx);
                byte[] inputCredHash = WingRidersUtils.credentialHash(poolInput.resolved().address());
                yield ByteStringLib.equals(inputCredHash, poolHash);
            }
            case Reclaim reclaim -> {
                yield txInfo.signatories().contains(PubKeyHash.of(datum.ownerHash()));
            }
        };
    }
}
