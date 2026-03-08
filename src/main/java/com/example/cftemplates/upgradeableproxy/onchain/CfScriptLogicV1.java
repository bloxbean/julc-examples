package com.example.cftemplates.upgradeableproxy.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.MultiValidator;
import com.bloxbean.cardano.julc.stdlib.annotation.Param;
import com.bloxbean.cardano.julc.stdlib.annotation.Purpose;
import com.bloxbean.cardano.julc.stdlib.lib.*;

import java.math.BigInteger;
import java.util.Optional;

/**
 * Script Logic V1 — validation logic for the upgradeable proxy.
 * <p>
 * WITHDRAW: Validates proxy mint and/or spend operations.
 *   Mint: exactly 1 token with matching name, qty 1.
 *   Spend: password must match "Hello, World!".
 * CERTIFY: Allows staking credential registration (RegStaking).
 * <p>
 * Based on: cardano-template-and-ecosystem-monitoring/upgradable-proxy (script_logic_v_1.ak)
 */
@MultiValidator
public class CfScriptLogicV1 {

    @Param static byte[] proxyPolicyId;

    record V1Redeemer(byte[] tokenName, byte[] password) {}

    @Entrypoint(purpose = Purpose.WITHDRAW)
    public static boolean withdraw(V1Redeemer redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();

        // Check if minting or spending is happening for the proxy policy
        boolean invokeMint = ValuesLib.containsPolicy(txInfo.mint(), proxyPolicyId);
        boolean invokeSpend = hasInputFromScript(txInfo.inputs(), proxyPolicyId);

        // At least one must be happening
        if (!invokeMint && !invokeSpend) return false;

        // Validate mint if minting
        boolean mintOk = !invokeMint || validateMint(redeemer, txInfo);

        // Validate spend if spending
        boolean spendOk = !invokeSpend || validateSpend(redeemer);

        return mintOk && spendOk;
    }

    static boolean validateMint(V1Redeemer redeemer, TxInfo txInfo) {
        // Exactly 1 token under proxy policy with matching name and qty 1
        BigInteger mintedQty = ValuesLib.assetOf(txInfo.mint(), proxyPolicyId, redeemer.tokenName());
        boolean validQty = mintedQty.compareTo(BigInteger.ONE) == 0;

        BigInteger tokenCount = ValuesLib.countTokensWithQty(txInfo.mint(), proxyPolicyId, BigInteger.ONE);
        boolean singleToken = tokenCount.compareTo(BigInteger.ONE) == 0;

        return validQty && singleToken;
    }

    static boolean validateSpend(V1Redeemer redeemer) {
        // Password must equal "Hello, World!" (UTF-8 bytes)
        byte[] expected = helloWorldBytes();
        return redeemer.password().equals(expected);
    }

    static byte[] helloWorldBytes() {
        // "Hello, World!" as chained consByteString (no variable reassignment)
        return Builtins.consByteString(72,   // H
               Builtins.consByteString(101,  // e
               Builtins.consByteString(108,  // l
               Builtins.consByteString(108,  // l
               Builtins.consByteString(111,  // o
               Builtins.consByteString(44,   // ,
               Builtins.consByteString(32,   // space
               Builtins.consByteString(87,   // W
               Builtins.consByteString(111,  // o
               Builtins.consByteString(114,  // r
               Builtins.consByteString(108,  // l
               Builtins.consByteString(100,  // d
               Builtins.consByteString(33,   // !
               Builtins.emptyByteString())))))))))))));
    }

    static boolean hasInputFromScript(JulcList<TxInInfo> inputs, byte[] scriptHash) {
        boolean found = false;
        for (var input : inputs) {
            byte[] credHash = AddressLib.credentialHash(input.resolved().address());
            if (credHash.equals(scriptHash)) {
                found = true;
                break;
            }
        }
        return found;
    }

    @Entrypoint(purpose = Purpose.CERTIFY)
    public static boolean certify(PlutusData redeemer, ScriptContext ctx) {
        // Allow only staking credential registration
        ScriptInfo.CertifyingScript certInfo = (ScriptInfo.CertifyingScript) ctx.scriptInfo();
        TxCert cert = certInfo.cert();
        return switch (cert) {
            case TxCert.RegStaking rs -> true;
            default -> false;
        };
    }
}
