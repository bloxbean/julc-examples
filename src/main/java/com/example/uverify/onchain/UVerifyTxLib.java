package com.example.uverify.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;
import com.bloxbean.cardano.julc.stdlib.lib.AddressLib;
import com.bloxbean.cardano.julc.stdlib.lib.ByteStringLib;
import com.bloxbean.cardano.julc.stdlib.lib.CryptoLib;
import com.bloxbean.cardano.julc.stdlib.lib.ValuesLib;

import java.math.BigInteger;

/**
 * Shared on-chain utilities for UVerify validators.
 * Contains generic transaction helpers that use only ledger/JDK types.
 *
 * NOTE: Methods must be defined BEFORE their callers (JuLC single-pass compiler).
 */
@OnchainLibrary
public class UVerifyTxLib {

    // === Signing ===

    static boolean oneOfKeysSigned(JulcList<PubKeyHash> signatories, JulcList<byte[]> keys) {
        return signatories.any(sig ->
                keys.any(k -> sig.hash().equals(k)));
    }

    // === Input lookup ===

    static TxOut findOwnInput(JulcList<TxInInfo> inputs, TxOutRef utxo) {
        TxOut result = (TxOut)(Object) Builtins.mkNilData();
        for (var input : inputs) {
            if (input.outRef().txId().equals(utxo.txId())
                    && input.outRef().index().equals(utxo.index())) {
                result = input.resolved();
                break;
            }
        }
        return result;
    }

    static boolean hasScriptInput(JulcList<TxInInfo> inputs, byte[] scriptHash) {
        boolean found = false;
        for (var input : inputs) {
            byte[] sh = AddressLib.credentialHash(input.resolved().address());
            if (sh.length > 0 && AddressLib.isScriptAddress(input.resolved().address())
                    && sh.equals(scriptHash)) {
                found = true;
                break;
            }
        }
        return found;
    }

    // === Script hash resolution ===

    static byte[] getOwnScriptHash(TxInfo txInfo, TxOutRef spentRef) {
        byte[] result = Builtins.emptyByteString();
        for (var input : txInfo.inputs()) {
            if (input.outRef().txId().equals(spentRef.txId())
                    && input.outRef().index().equals(spentRef.index())) {
                if (AddressLib.isScriptAddress(input.resolved().address())) {
                    result = AddressLib.credentialHash(input.resolved().address());
                    break;
                }
            }
        }
        return result;
    }

    // === Withdrawal check ===

    static boolean hasWithdrawal(TxInfo txInfo, byte[] scriptHash) {
        Credential cred = new Credential.ScriptCredential((ScriptHash)(Object) scriptHash);
        return txInfo.withdrawals().containsKey(cred);
    }

    // === Lovelace helpers ===

    static BigInteger sumLovelaceAtAddress(JulcList<TxOut> outputs, Address addr) {
        BigInteger total = BigInteger.ZERO;
        for (var output : outputs) {
            if (Builtins.equalsData(output.address(), addr)) {
                total = total.add(ValuesLib.lovelaceOf(output.value()));
            }
        }
        return total;
    }

    static boolean receiverPaid(byte[] recv, BigInteger minFee, JulcList<TxOut> outputs) {
        boolean paid = false;
        for (var output : outputs) {
            byte[] pkh = AddressLib.credentialHash(output.address());
            if (pkh.length > 0 && AddressLib.isPubKeyAddress(output.address())
                    && pkh.equals(recv)) {
                BigInteger lovelace = ValuesLib.lovelaceOf(output.value());
                if (lovelace.compareTo(minFee) >= 0) {
                    paid = true;
                    break;
                }
            }
        }
        return paid;
    }

    // === Time / interval ===

    static boolean tokenNotExpired(Interval validRange, BigInteger ttl) {
        IntervalBound upperBound = validRange.to();
        return switch (upperBound.boundType()) {
            case IntervalBoundType.Finite f -> f.time().compareTo(ttl) < 0;
            case IntervalBoundType.NegInf ignored -> false;
            case IntervalBoundType.PosInf ignored -> false;
        };
    }

    // === Unique ID ===

    static boolean hasUniqueId(byte[] expectedId, JulcList<TxInInfo> inputs) {
        boolean found = false;
        for (var input : inputs) {
            byte[] txId = (byte[])(Object) input.outRef().txId();
            BigInteger idx = input.outRef().index();
            byte[] idxBytes = ByteStringLib.integerToByteString(false, 2, idx.longValue());
            byte[] computed = CryptoLib.sha2_256(ByteStringLib.append(txId, idxBytes));
            if (computed.equals(expectedId)) {
                found = true;
                break;
            }
        }
        return found;
    }
}
