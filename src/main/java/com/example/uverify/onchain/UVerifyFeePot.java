package com.example.uverify.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.MultiValidator;
import com.bloxbean.cardano.julc.stdlib.annotation.Param;
import com.bloxbean.cardano.julc.stdlib.annotation.Purpose;
import com.bloxbean.cardano.julc.stdlib.lib.AddressLib;
import com.bloxbean.cardano.julc.stdlib.lib.ByteStringLib;
import com.bloxbean.cardano.julc.stdlib.lib.CryptoLib;
import com.bloxbean.cardano.julc.stdlib.lib.IntervalLib;
import com.bloxbean.cardano.julc.stdlib.lib.ValuesLib;

import java.math.BigInteger;
import java.util.Optional;

/**
 * UVerify Fee Pot Validator — @MultiValidator with SPEND only.
 * Manages a lovelace reserve with tiered access control:
 * - Admins: unrestricted spending
 * - Users: spending constrained (lovelace must return as change or go to whitelisted scripts)
 * - Two redeemer variants: RELEASE (simple) and ON_BEHALF (with Ed25519 signature + TTL)
 *
 * Port of Aiken's uverify_fee_pot.ak
 */
@MultiValidator
public class UVerifyFeePot {

    @Param static JulcList<byte[]> admins;
    @Param static JulcList<byte[]> users;
    @Param static JulcList<byte[]> scriptWhitelist;

    // --- Data Types ---

    sealed interface Intent permits Release, OnBehalf {}
    record Release() implements Intent {}
    record OnBehalf(byte[] message, byte[] signature, byte[] signerPublicKey,
                    byte[] submitterKeyHash, BigInteger ttl) implements Intent {}

    // === Entrypoint ===

    @Entrypoint(purpose = Purpose.SPEND)
    static boolean handleSpend(Optional<PlutusData> datum, Intent redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        ScriptInfo.SpendingScript spendInfo = (ScriptInfo.SpendingScript) ctx.scriptInfo();
        TxOutRef utxo = spendInfo.txOutRef();

        JulcList<PubKeyHash> signatories = txInfo.signatories();

        if (UVerifyTxLib.oneOfKeysSigned(signatories, admins)) {
            return true;
        }

        if (!UVerifyTxLib.oneOfKeysSigned(signatories, users)) {
            return false;
        }

        return switch (redeemer) {
            case Release r -> handleRelease(txInfo, utxo);
            case OnBehalf ob -> handleOnBehalf(txInfo, utxo, ob);
        };
    }

    // === Handler methods ===

    static boolean handleRelease(TxInfo txInfo, TxOutRef utxo) {
        TxOut ownInput = UVerifyTxLib.findOwnInput(txInfo.inputs(), utxo);
        BigInteger usedLovelace = ValuesLib.lovelaceOf(ownInput.value());
        BigInteger changeLovelace = UVerifyTxLib.sumLovelaceAtAddress(txInfo.outputs(), ownInput.address());
        BigInteger whitelistedLovelace = sumLovelaceAtWhitelistedScripts(txInfo.outputs());
        BigInteger fee = txInfo.fee();

        return usedLovelace.compareTo(changeLovelace.add(whitelistedLovelace).add(fee)) <= 0;
    }

    static boolean handleOnBehalf(TxInfo txInfo, TxOutRef utxo, OnBehalf ob) {
        TxOut ownInput = UVerifyTxLib.findOwnInput(txInfo.inputs(), utxo);
        BigInteger usedLovelace = ValuesLib.lovelaceOf(ownInput.value());
        BigInteger changeLovelace = UVerifyTxLib.sumLovelaceAtAddress(txInfo.outputs(), ownInput.address());
        BigInteger whitelistedLovelace = sumLovelaceAtWhitelistedScripts(txInfo.outputs());
        BigInteger fee = txInfo.fee();

        if (usedLovelace.compareTo(changeLovelace.add(whitelistedLovelace).add(fee)) > 0) {
            return false;
        }

        byte[] signerPkh = CryptoLib.blake2b_224(ob.signerPublicKey());
        boolean signerAuthorized = admins.any(a -> a.equals(signerPkh))
                || users.any(u -> u.equals(signerPkh));
        if (!signerAuthorized) return false;

        byte[] signerPkhHex = ByteStringLib.toHex(signerPkh);
        byte[] submitterKeyHashHex = ByteStringLib.toHex(ob.submitterKeyHash());
        byte[] expectedMessage = buildExpectedMessage(signerPkhHex, submitterKeyHashHex, ob.ttl());

        if (!ob.message().equals(expectedMessage)) return false;

        if (!CryptoLib.verifyEd25519Signature(ob.signerPublicKey(), ob.message(), ob.signature())) {
            return false;
        }

        BigInteger upperBound = IntervalLib.finiteUpperBound(txInfo.validRange());
        if (upperBound.compareTo(BigInteger.ZERO) < 0) return false;
        return upperBound.compareTo(ob.ttl()) <= 0;
    }

    // === Utilities ===

    static byte[] buildExpectedMessage(byte[] signerPkhHex, byte[] submitterKeyHashHex, BigInteger ttl) {
        byte[] colon = ByteStringLib.cons(58, ByteStringLib.empty()); // ':'
        byte[] ttlStr = ByteStringLib.intToDecimalString(ttl);
        return ByteStringLib.append(
                ByteStringLib.append(
                        ByteStringLib.append(
                                ByteStringLib.append(signerPkhHex, colon),
                                submitterKeyHashHex),
                        colon),
                ttlStr);
    }

    static BigInteger sumLovelaceAtWhitelistedScripts(JulcList<TxOut> outputs) {
        BigInteger total = BigInteger.ZERO;
        for (var output : outputs) {
            if (AddressLib.isScriptAddress(output.address())) {
                byte[] sh = AddressLib.credentialHash(output.address());
                if (scriptWhitelist.any(w -> w.equals(sh))) {
                    total = total.add(ValuesLib.lovelaceOf(output.value()));
                }
            }
        }
        return total;
    }
}
