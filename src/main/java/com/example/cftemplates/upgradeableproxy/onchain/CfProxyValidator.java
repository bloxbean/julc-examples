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
 * Upgradeable Proxy validator — state token pattern with withdrawal delegation.
 * <p>
 * MINT INIT: One-shot state token creation. Name = sha3_256(txHash || indexString).
 *   Output to own script with ProxyDatum (script pointer + owner).
 * MINT ProxyMint: Non-state-token minting. Delegates to script logic via withdrawal.
 *   Reference input must have state token. Withdrawal from script_pointer must exist.
 * <p>
 * SPEND Update: Update the state token's datum (change script pointer or owner).
 *   State token must be in this input. Old owner signs. Both sign if owner changes.
 * SPEND ProxySpend: Regular spending. Delegates to script logic via withdrawal.
 *   State token must NOT be in this input. Reference input with state token required.
 * <p>
 * Based on: cardano-template-and-ecosystem-monitoring/upgradable-proxy
 * <p>
 * NOTE: Redeemer tag ordering may differ from Aiken. JuLC sealed interface
 * ordering determines constructor tags, which is internally consistent
 * since the JuLC compiler generates its own script hash.
 */
@MultiValidator
public class CfProxyValidator {

    @Param static byte[] seedTxHash;
    @Param static BigInteger seedIndex;

    record ProxyDatum(byte[] scriptPointer, byte[] scriptOwner) {}

    sealed interface ProxyMintAction permits Init, ProxyMint {}
    record Init() implements ProxyMintAction {}
    record ProxyMint() implements ProxyMintAction {}

    sealed interface ProxySpendAction permits Update, ProxySpend {}
    record Update() implements ProxySpendAction {}
    record ProxySpend() implements ProxySpendAction {}

    @Entrypoint(purpose = Purpose.MINT)
    public static boolean mint(ProxyMintAction redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        ScriptInfo.MintingScript mintInfo = (ScriptInfo.MintingScript) ctx.scriptInfo();
        byte[] policyBytes = (byte[])(Object) mintInfo.policyId();
        byte[] stateTokenName = computeStateTokenName();

        return switch (redeemer) {
            case Init i -> handleInit(txInfo, policyBytes, stateTokenName);
            case ProxyMint pm -> handleProxyMint(txInfo, policyBytes, stateTokenName);
        };
    }

    @Entrypoint(purpose = Purpose.SPEND)
    public static boolean spend(Optional<PlutusData> datum, ProxySpendAction redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();

        TxOut ownInput = ContextsLib.findOwnInput(ctx).get().resolved();
        byte[] ownHash = ContextsLib.ownHash(ctx);
        byte[] stateTokenName = computeStateTokenName();

        return switch (redeemer) {
            case Update u -> handleUpdate(txInfo, ownInput, ownHash, stateTokenName);
            case ProxySpend ps -> handleProxySpend(txInfo, ownInput, ownHash, stateTokenName);
        };
    }

    // --- Mint handlers ---

    static boolean handleInit(TxInfo txInfo, byte[] policyBytes, byte[] stateTokenName) {
        // Seed consumed
        boolean consumesSeed = checkSeedConsumed(txInfo.inputs());

        // Exactly 1 token minted with state_token_name
        BigInteger mintedQty = ValuesLib.assetOf(txInfo.mint(), policyBytes, stateTokenName);
        boolean validMint = mintedQty.compareTo(BigInteger.ONE) == 0;

        // Only 1 token type minted under this policy
        BigInteger tokenCount = ValuesLib.countTokensWithQty(txInfo.mint(), policyBytes, BigInteger.ONE);
        boolean singleToken = tokenCount.compareTo(BigInteger.ONE) == 0;

        // Find output to own script with state token
        TxOut stateOutput = OutputLib.findOutputWithToken(txInfo.outputs(), policyBytes, policyBytes, stateTokenName);
        PlutusData datumData = OutputLib.getInlineDatum(stateOutput);
        ProxyDatum pd = (ProxyDatum)(Object) datumData;

        // Script owner signs
        boolean ownerSigned = ContextsLib.signedBy(txInfo, pd.scriptOwner());

        // Only 1 output to this script
        BigInteger outputCount = countOutputsToScript(txInfo.outputs(), policyBytes);
        boolean singleOutput = outputCount.compareTo(BigInteger.ONE) == 0;

        return consumesSeed && validMint && singleToken && ownerSigned && singleOutput;
    }

    static boolean handleProxyMint(TxInfo txInfo, byte[] policyBytes, byte[] stateTokenName) {
        // State token must NOT be minted
        BigInteger stateTokenMinted = ValuesLib.assetOf(txInfo.mint(), policyBytes, stateTokenName);
        boolean notMintingState = stateTokenMinted.compareTo(BigInteger.ZERO) == 0;

        // Reference input with state token at own script
        ProxyDatum proxyDatum = findStateTokenInRefInputs(txInfo.referenceInputs(), policyBytes, stateTokenName);

        // Withdrawal from script_pointer must exist
        boolean hasWithdrawal = withdrawalFromScriptExists(txInfo, proxyDatum.scriptPointer());

        return notMintingState && hasWithdrawal;
    }

    // --- Spend handlers ---

    static boolean handleUpdate(TxInfo txInfo, TxOut ownInput, byte[] policyBytes, byte[] stateTokenName) {
        // State token must be in this input
        BigInteger stateQty = ValuesLib.assetOf(ownInput.value(), policyBytes, stateTokenName);
        if (stateQty.compareTo(BigInteger.ONE) != 0) return false;

        // Find output with state token at own script
        TxOut stateOutput = OutputLib.findOutputWithToken(txInfo.outputs(), policyBytes, policyBytes, stateTokenName);

        // Extract old and new datum
        PlutusData oldDatumData = OutputLib.getInlineDatum(ownInput);
        ProxyDatum oldDatum = (ProxyDatum)(Object) oldDatumData;

        PlutusData newDatumData = OutputLib.getInlineDatum(stateOutput);
        ProxyDatum newDatum = (ProxyDatum)(Object) newDatumData;

        // Old owner must sign
        boolean oldOwnerSigned = ContextsLib.signedBy(txInfo, oldDatum.scriptOwner());

        // If owner changed, new owner must also sign
        boolean ownerChangeOk = oldDatum.scriptOwner().equals(newDatum.scriptOwner())
                || ContextsLib.signedBy(txInfo, newDatum.scriptOwner());

        return oldOwnerSigned && ownerChangeOk;
    }

    static boolean handleProxySpend(TxInfo txInfo, TxOut ownInput, byte[] policyBytes, byte[] stateTokenName) {
        // State token must NOT be in this input
        BigInteger stateQty = ValuesLib.assetOf(ownInput.value(), policyBytes, stateTokenName);
        boolean notStateToken = stateQty.compareTo(BigInteger.ZERO) == 0;

        // Reference input with state token at own script
        ProxyDatum proxyDatum = findStateTokenInRefInputs(txInfo.referenceInputs(), policyBytes, stateTokenName);

        // Withdrawal from script_pointer must exist
        boolean hasWithdrawal = withdrawalFromScriptExists(txInfo, proxyDatum.scriptPointer());

        return notStateToken && hasWithdrawal;
    }

    // --- Helpers ---

    static byte[] computeStateTokenName() {
        byte[] indexStr = ByteStringLib.intToDecimalString(seedIndex);
        byte[] combined = Builtins.appendByteString(seedTxHash, indexStr);
        return CryptoLib.sha3_256(combined);
    }

    static boolean checkSeedConsumed(JulcList<TxInInfo> inputs) {
        boolean found = false;
        for (var input : inputs) {
            byte[] txHash = (byte[])(Object) input.outRef().txId();
            BigInteger idx = input.outRef().index();
            if (txHash.equals(seedTxHash) && idx.compareTo(seedIndex) == 0) {
                found = true;
                break;
            }
        }
        return found;
    }

    static ProxyDatum findStateTokenInRefInputs(JulcList<TxInInfo> refInputs, byte[] policyBytes, byte[] stateTokenName) {
        ProxyDatum result = (ProxyDatum)(Object) Builtins.mkNilData();
        for (var refIn : refInputs) {
            byte[] credHash = AddressLib.credentialHash(refIn.resolved().address());
            if (credHash.equals(policyBytes)) {
                BigInteger tokenQty = ValuesLib.assetOf(refIn.resolved().value(), policyBytes, stateTokenName);
                if (tokenQty.compareTo(BigInteger.ONE) == 0) {
                    PlutusData datumData = OutputLib.getInlineDatum(refIn.resolved());
                    result = (ProxyDatum)(Object) datumData;
                    break;
                }
            }
        }
        return result;
    }

    static BigInteger countOutputsToScript(JulcList<TxOut> outputs, byte[] scriptHash) {
        BigInteger count = BigInteger.ZERO;
        for (var output : outputs) {
            byte[] credHash = AddressLib.credentialHash(output.address());
            if (credHash.equals(scriptHash)) {
                count = count.add(BigInteger.ONE);
            }
        }
        return count;
    }

    static boolean withdrawalFromScriptExists(TxInfo txInfo, byte[] scriptHash) {
        // Check if Script(scriptHash) credential exists in withdrawals map
        boolean found = false;
        for (var cred : txInfo.withdrawals().keys()) {
            long tag = Builtins.constrTag(cred);
            if (tag == 1L) { // ScriptCredential = tag 1
                var fields = Builtins.constrFields(cred);
                byte[] hash = Builtins.unBData(Builtins.headList(fields));
                if (hash.equals(scriptHash)) {
                    found = true;
                    break;
                }
            }
        }
        return found;
    }
}
