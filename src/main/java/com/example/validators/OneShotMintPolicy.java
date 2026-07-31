package com.example.validators;

import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.MintingValidator;
import com.bloxbean.cardano.julc.stdlib.annotation.Param;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.ledger.ScriptInfo;
import com.bloxbean.cardano.julc.ledger.TxInfo;
import com.bloxbean.cardano.julc.ledger.TxOutRef;
import com.bloxbean.cardano.julc.ledger.Value;
import com.bloxbean.cardano.julc.stdlib.lib.ContextsLib;
import com.bloxbean.cardano.julc.stdlib.lib.ValuesLib;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.core.PlutusData;

import java.math.BigInteger;

@MintingValidator
public class OneShotMintPolicy {

    @Param
    static byte[] utxoTxId;

    @Param
    static BigInteger utxoIndex;

    @Entrypoint
    public static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        ScriptInfo.MintingScript minting = (ScriptInfo.MintingScript) ctx.scriptInfo();
        byte[] ownPolicyId = Builtins.toByteString(minting.policyId());

        ContextsLib.trace("Checking UTXO input");
        boolean validMint = false;
        for (var input : txInfo.inputs()) {
            TxOutRef ref = input.outRef();
            byte[] refTxIdBytes = Builtins.toByteString(ref.txId());
            boolean consumesSeed = Builtins.equalsByteString(refTxIdBytes, utxoTxId)
                    && ref.index().compareTo(utxoIndex) == 0;
            if (consumesSeed) {
                byte[] expectedTokenName = ValuesLib.uniqueTokenName(ref);
                validMint = mintsOnlyCanonicalToken(
                        txInfo.mint(), ownPolicyId, expectedTokenName);
                break;                        // ← break is separate from assignment
            }
        }
        return validMint;
    }

    /**
     * Require exactly one asset under this policy: the canonical token derived
     * from the consumed seed reference, with quantity one.
     */
    static boolean mintsOnlyCanonicalToken(
            Value mint, byte[] ownPolicyId, byte[] expectedTokenName) {
        PlutusData policies = Builtins.unMapData(mint);
        PlutusData ownPolicyData = Builtins.bData(ownPolicyId);
        boolean validMint = false;

        while (!Builtins.nullList(policies)) {
            var policy = Builtins.headList(policies);
            if (Builtins.equalsData(Builtins.fstPair(policy), ownPolicyData)) {
                PlutusData assets = Builtins.unMapData(
                        (PlutusData.MapData) Builtins.sndPair(policy));

                if (!Builtins.nullList(assets)) {
                    var asset = Builtins.headList(assets);
                    boolean exactlyOneAsset =
                            Builtins.nullList(Builtins.tailList(assets));
                    boolean expectedName = Builtins.equalsData(
                            Builtins.fstPair(asset), Builtins.bData(expectedTokenName));
                    boolean expectedQuantity = Builtins.unIData(
                            Builtins.sndPair(asset)).compareTo(BigInteger.ONE) == 0;

                    validMint = exactlyOneAsset && expectedName && expectedQuantity;
                }
                break;
            }
            policies = Builtins.tailList(policies);
        }

        return validMint;
    }
}
