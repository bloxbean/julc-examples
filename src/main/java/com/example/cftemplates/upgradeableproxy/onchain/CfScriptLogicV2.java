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
 * Script Logic V2 — upgraded validation logic for the proxy.
 * <p>
 * WITHDRAW: Validates proxy operations with different rules than V1.
 *   Mint: exactly 1 token, name must NOT equal invalidTokenName.
 *   Spend: always valid (no password check).
 * CERTIFY: Allows staking credential registration (RegStaking).
 * <p>
 * Based on: cardano-template-and-ecosystem-monitoring/upgradable-proxy (script_logic_v_2.ak)
 */
@MultiValidator
public class CfScriptLogicV2 {

    @Param static byte[] proxyPolicyId;

    record V2Redeemer(byte[] invalidTokenName) {}

    @Entrypoint(purpose = Purpose.WITHDRAW)
    public static boolean withdraw(V2Redeemer redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();

        boolean invokeMint = ValuesLib.containsPolicy(txInfo.mint(), proxyPolicyId);
        boolean invokeSpend = hasInputFromScript(txInfo.inputs(), proxyPolicyId);

        // At least one must be happening
        if (!invokeMint && !invokeSpend) return false;

        // Validate mint if minting (V2 rules: name != invalidTokenName)
        boolean mintOk = !invokeMint || validateMintV2(redeemer, txInfo);

        // Spend validation always passes in V2
        return mintOk;
    }

    static boolean validateMintV2(V2Redeemer redeemer, TxInfo txInfo) {
        // Exactly 1 token under proxy policy with qty 1
        BigInteger tokenCount = ValuesLib.countTokensWithQty(txInfo.mint(), proxyPolicyId, BigInteger.ONE);
        boolean singleToken = tokenCount.compareTo(BigInteger.ONE) == 0;

        // Token name must NOT equal invalidTokenName
        byte[] tokenName = ValuesLib.findTokenName(txInfo.mint(), proxyPolicyId, BigInteger.ONE);
        boolean nameValid = !tokenName.equals(redeemer.invalidTokenName());

        return singleToken && nameValid;
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
        ScriptInfo.CertifyingScript certInfo = (ScriptInfo.CertifyingScript) ctx.scriptInfo();
        TxCert cert = certInfo.cert();
        return switch (cert) {
            case TxCert.RegStaking rs -> true;
            default -> false;
        };
    }
}
