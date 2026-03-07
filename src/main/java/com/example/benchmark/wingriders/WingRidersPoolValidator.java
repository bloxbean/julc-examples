package com.example.benchmark.wingriders;

import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.Param;
import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.stdlib.lib.ByteStringLib;
import com.bloxbean.cardano.julc.stdlib.lib.ValuesLib;

import java.math.BigInteger;
import java.util.List;

/**
 * WingRiders Pool Validator.
 */
@SpendingValidator
public class WingRidersPoolValidator {

    @Param
    static byte[] validityPolicyId;

    @Param
    static byte[] stakingRewardsPolicyId;

    @Param
    static byte[] enforcedScriptOutputDatumHash;

    @Param
    static byte[] treasuryHolderScriptHash;

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

    // --- Pool datum ---
    record PoolDatum(byte[] requestHash, byte[] aPolicyId, byte[] aAssetName,
                     byte[] bPolicyId, byte[] bAssetName) {}

    // --- Pool redeemer ---
    record Evolve(List<BigInteger> requestIndices) {}

    @Entrypoint
    public static boolean validate(PoolDatum datum, Evolve redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        List<BigInteger> requestIndices = redeemer.requestIndices();

        // No duplicate request indices
        if (WingRidersUtils.containsDuplicate(requestIndices)) {
            return false;
        }

        // At least one request
        if (requestIndices.isEmpty()) {
            return false;
        }

        // Finite validity end
        BigInteger txValidityEnd = WingRidersUtils.finiteValidityEnd(txInfo.validRange());
        if (txValidityEnd.compareTo(BigInteger.ZERO) < 0) {
            return false;
        }

        // Pool datum fields
        byte[] requestHash = datum.requestHash();
        byte[] aPolicyId = datum.aPolicyId();
        byte[] aAssetName = datum.aAssetName();
        byte[] bPolicyId = datum.bPolicyId();
        byte[] bAssetName = datum.bAssetName();

        // Share asset name
        byte[] shareAssetName = WingRidersUtils.shareClassAssetName(aPolicyId, aAssetName, bPolicyId, bAssetName);

        JulcList<TxInInfo> inputs = txInfo.inputs();
        JulcList<TxOut> outputs = txInfo.outputs();

        // Validate each request
        boolean allValid = true;
        int i = 0;
        int numRequests = requestIndices.size();
        while (i < numRequests) {
            int reqIdx = requestIndices.get(i).intValue();
            TxInInfo requestInput = inputs.get(reqIdx);
            TxOut compensationOutput = outputs.get(i + 1);

            TxOut requestOutput = requestInput.resolved();
            byte[] checkHash = WingRidersUtils.credentialHash(requestOutput.address());
            boolean hashOk = ByteStringLib.equals(checkHash, requestHash);
            OutputDatum od = requestOutput.datum();
            boolean isInline = switch (od) {
                case OutputDatum.OutputDatumInline inl -> true;
                case OutputDatum.OutputDatumHash dh -> false;
                case OutputDatum.NoOutputDatum no -> false;
            };
            boolean valid = hashOk && isInline;

            allValid = allValid && valid;
            i = i + 1;
        }

        return allValid;
    }

    /**
     * Validate a single request.
     */
    static boolean validateSingleRequest(TxInInfo requestInput, TxOut compensationOutput,
                                         RequestDatum requestDatum,
                                         BigInteger txValidityEnd, byte[] requestHash,
                                         byte[] aPolicyId, byte[] aAssetName,
                                         byte[] bPolicyId, byte[] bAssetName,
                                         byte[] shareAssetName) {
        TxOut requestOutput = requestInput.resolved();

        // 1. Compensation address matches beneficiary
        byte[] compensationCredHash = WingRidersUtils.credentialHash(compensationOutput.address());
        boolean addressMatch = ByteStringLib.equals(requestDatum.beneficiaryHash(), compensationCredHash);

        // 2. Deadline not exceeded
        boolean deadlineOk = txValidityEnd.compareTo(requestDatum.deadline()) < 0;

        // 3. Input script hash matches request hash
        byte[] inputScriptHash = WingRidersUtils.credentialHash(requestOutput.address());
        boolean scriptHashMatch = ByteStringLib.equals(inputScriptHash, requestHash);

        // 4-5. Token policy/name matches
        boolean aPolicyMatch = ByteStringLib.equals(requestDatum.aPolicyId(), aPolicyId);
        boolean aNameMatch = ByteStringLib.equals(requestDatum.aAssetName(), aAssetName);
        boolean bPolicyMatch = ByteStringLib.equals(requestDatum.bPolicyId(), bPolicyId);
        boolean bNameMatch = ByteStringLib.equals(requestDatum.bAssetName(), bAssetName);

        // 6. Correct output datum
        boolean datumOk = checkOutputDatum(requestDatum.beneficiaryIsScript(), compensationOutput);

        // 7. Action-specific checks
        boolean actionOk = checkAction(requestDatum.action(), requestDatum.beneficiaryHash(),
                requestDatum.beneficiaryIsScript(),
                requestOutput.value(), compensationOutput.value(),
                aPolicyId, shareAssetName);

        return addressMatch && deadlineOk && scriptHashMatch
                && aPolicyMatch && aNameMatch && bPolicyMatch && bNameMatch
                && datumOk && actionOk;
    }

    /**
     * Action-specific validation.
     */
    static boolean checkAction(RequestAction action, byte[] beneficiaryHash, boolean beneficiaryIsScript,
                               Value requestValue, Value compensationValue,
                               byte[] poolAPolicyId, byte[] shareAssetName) {
        return switch (action) {
            case ExtractTreasury et -> {
                yield beneficiaryIsScript && ByteStringLib.equals(beneficiaryHash, treasuryHolderScriptHash);
            }
            case AddStakingRewards asr -> {
                boolean aIsAda = ByteStringLib.length(poolAPolicyId) == 0;
                BigInteger requestRewardQty = ValuesLib.assetOf(requestValue, stakingRewardsPolicyId, shareAssetName);
                BigInteger compensationRewardQty = ValuesLib.assetOf(compensationValue, stakingRewardsPolicyId, shareAssetName);
                yield aIsAda
                        && requestRewardQty.compareTo(BigInteger.ONE) == 0
                        && compensationRewardQty.compareTo(BigInteger.ONE) == 0;
            }
            case Swap s -> true;
            case AddLiquidity al -> true;
            case WithdrawLiquidity wl -> true;
        };
    }

    /**
     * Check compensation output datum based on beneficiary type.
     */
    static boolean checkOutputDatum(boolean beneficiaryIsScript, TxOut compensationOutput) {
        OutputDatum outDatum = compensationOutput.datum();
        if (beneficiaryIsScript) {
            return switch (outDatum) {
                case OutputDatum.OutputDatumHash dh -> ByteStringLib.equals((byte[])(Object) dh.hash(), enforcedScriptOutputDatumHash);
                case OutputDatum.NoOutputDatum no -> false;
                case OutputDatum.OutputDatumInline inl -> false;
            };
        } else {
            return switch (outDatum) {
                case OutputDatum.NoOutputDatum no -> true;
                case OutputDatum.OutputDatumHash dh -> false;
                case OutputDatum.OutputDatumInline inl -> false;
            };
        }
    }
}
