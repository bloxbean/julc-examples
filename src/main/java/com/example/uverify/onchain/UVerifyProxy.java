package com.example.uverify.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.lib.ByteStringLib;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.MultiValidator;
import com.bloxbean.cardano.julc.stdlib.annotation.Param;
import com.bloxbean.cardano.julc.stdlib.annotation.Purpose;
import com.bloxbean.cardano.julc.stdlib.lib.AddressLib;
import com.bloxbean.cardano.julc.stdlib.lib.ContextsLib;
import com.bloxbean.cardano.julc.stdlib.lib.CryptoLib;
import com.bloxbean.cardano.julc.stdlib.lib.OutputLib;
import com.bloxbean.cardano.julc.stdlib.lib.ValuesLib;

import java.math.BigInteger;
import java.util.Optional;

/**
 * UVerify Proxy Validator — @MultiValidator with MINT + SPEND.
 * Produces ONE script hash serving as both policy ID and script address.
 *
 * NOTE: Methods must be defined BEFORE their callers (JuLC single-pass compiler).
 *
 * Idiomatic JuLC: uses typed record casts, AddressLib, ContextsLib, OutputLib, ValuesLib.
 */
@MultiValidator
public class UVerifyProxy {

    @Param static byte[] utxoRefTxId;
    @Param static BigInteger utxoRefIdx;

    record ProxyDatum(byte[] scriptPointer, byte[] scriptOwner) {}

    sealed interface ProxActionRedeemer permits Admin, User {}
    record Admin() implements ProxActionRedeemer {}
    record User() implements ProxActionRedeemer {}

    // === Level 0: Leaf utilities (no dependencies) ===

    static byte[] getStateTokenName() {
        byte[] idxBytes = ByteStringLib.integerToByteString(true, 0, utxoRefIdx.longValue());
        byte[] combined = ByteStringLib.append(utxoRefTxId, idxBytes);
        return CryptoLib.sha2_256(combined);
    }

    // === Level 1: Handler methods ===

    static boolean handleMintAdmin(TxInfo txInfo, byte[] ownPolicyId) {
        boolean utxoRefConsumed = false;
        for (var input : txInfo.inputs()) {
            if (input.outRef().txId().equals(utxoRefTxId)
                    && input.outRef().index().equals(utxoRefIdx)) {
                utxoRefConsumed = true;
                break;
            }
        }
        if (!utxoRefConsumed) return false;

        byte[] stn = getStateTokenName();

        BigInteger mintedQty = ValuesLib.assetOf(txInfo.mint(), ownPolicyId, stn);
        if (!mintedQty.equals(BigInteger.ONE)) return false;

        boolean foundValidOutput = false;
        for (var output : txInfo.outputs()) {
            if (AddressLib.isScriptAddress(output.address())) {
                byte[] scriptHash = AddressLib.credentialHash(output.address());
                if (scriptHash.equals(ownPolicyId)) {
                    BigInteger tokenQty = ValuesLib.assetOf(output.value(), ownPolicyId, stn);
                    if (tokenQty.equals(BigInteger.ONE)) {
                        PlutusData datumData = OutputLib.getInlineDatum(output);
                        ProxyDatum pd = (ProxyDatum)(Object) datumData;
                        if (ContextsLib.signedBy(txInfo, pd.scriptOwner())) {
                            foundValidOutput = true;
                            break;
                        }
                    }
                }
            }
        }
        return foundValidOutput;
    }

    static boolean handleMintUser(TxInfo txInfo, byte[] ownPolicyId) {
        byte[] stn = getStateTokenName();

        boolean foundRef = false;
        byte[] scriptPointer = Builtins.emptyByteString();
        for (var refInput : txInfo.referenceInputs()) {
            if (AddressLib.isScriptAddress(refInput.resolved().address())) {
                byte[] scriptHash = AddressLib.credentialHash(refInput.resolved().address());
                if (scriptHash.equals(ownPolicyId)) {
                    BigInteger tokenQty = ValuesLib.assetOf(refInput.resolved().value(), ownPolicyId, stn);
                    if (tokenQty.compareTo(BigInteger.ZERO) > 0) {
                        PlutusData datumData = OutputLib.getInlineDatum(refInput.resolved());
                        ProxyDatum pd = (ProxyDatum)(Object) datumData;
                        scriptPointer = pd.scriptPointer();
                        foundRef = true;
                        break;
                    }
                }
            }
        }
        if (!foundRef) return false;

        if (!UVerifyTxLib.hasWithdrawal(txInfo, scriptPointer)) return false;

        BigInteger mintedQty = ValuesLib.assetOf(txInfo.mint(), ownPolicyId, stn);
        return mintedQty.equals(BigInteger.ZERO);
    }

    static boolean handleSpendAdmin(TxInfo txInfo, Optional<PlutusData> datum, byte[] ownScriptHash) {
        if (datum.isEmpty()) return false;
        ProxyDatum pd = (ProxyDatum)(Object) datum.get();

        if (!ContextsLib.signedBy(txInfo, pd.scriptOwner())) return false;

        boolean ownerChangeBlocked = false;
        for (var output : txInfo.outputs()) {
            if (AddressLib.isScriptAddress(output.address())) {
                byte[] scriptHash = AddressLib.credentialHash(output.address());
                if (scriptHash.equals(ownScriptHash)) {
                    PlutusData datumData = OutputLib.getInlineDatum(output);
                    ProxyDatum newPd = (ProxyDatum)(Object) datumData;
                    if (!pd.scriptOwner().equals(newPd.scriptOwner())) {
                        if (!ContextsLib.signedBy(txInfo, newPd.scriptOwner())) {
                            ownerChangeBlocked = true;
                            break;
                        }
                    }
                }
            }
        }
        if (ownerChangeBlocked) return false;

        return true;
    }

    static boolean handleSpendUser(TxInfo txInfo, byte[] ownScriptHash) {
        byte[] stn = getStateTokenName();

        byte[] scriptPointer = Builtins.emptyByteString();
        boolean hasRefInput = false;
        for (var refInput : txInfo.referenceInputs()) {
            if (AddressLib.isScriptAddress(refInput.resolved().address())) {
                byte[] scriptHash = AddressLib.credentialHash(refInput.resolved().address());
                if (scriptHash.equals(ownScriptHash)) {
                    BigInteger tokenQty = ValuesLib.assetOf(refInput.resolved().value(), ownScriptHash, stn);
                    if (tokenQty.compareTo(BigInteger.ZERO) > 0) {
                        PlutusData datumData = OutputLib.getInlineDatum(refInput.resolved());
                        ProxyDatum pd = (ProxyDatum)(Object) datumData;
                        scriptPointer = pd.scriptPointer();
                        hasRefInput = true;
                        break;
                    }
                }
            }
        }
        if (!hasRefInput) return false;

        return UVerifyTxLib.hasWithdrawal(txInfo, scriptPointer);
    }

    // === Level 3: Entrypoints (call Level 2 handlers) ===

    @Entrypoint(purpose = Purpose.MINT)
    static boolean handleMint(ProxActionRedeemer redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        ScriptInfo.MintingScript mintInfo = (ScriptInfo.MintingScript) ctx.scriptInfo();
        byte[] ownPolicyId = (byte[])(Object) mintInfo.policyId();

        return switch (redeemer) {
            case Admin a -> handleMintAdmin(txInfo, ownPolicyId);
            case User u -> handleMintUser(txInfo, ownPolicyId);
        };
    }

    @Entrypoint(purpose = Purpose.SPEND)
    static boolean handleSpend(Optional<PlutusData> datum, ProxActionRedeemer redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        ScriptInfo.SpendingScript spendInfo = (ScriptInfo.SpendingScript) ctx.scriptInfo();
        byte[] ownScriptHash = UVerifyTxLib.getOwnScriptHash(txInfo, spendInfo.txOutRef());

        return switch (redeemer) {
            case Admin a -> handleSpendAdmin(txInfo, datum, ownScriptHash);
            case User u -> handleSpendUser(txInfo, ownScriptHash);
        };
    }
}
