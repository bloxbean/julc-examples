package com.example.cftemplates.simplewallet.onchain;

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
 * Simple Wallet validator — combines wallet minting + intent spending.
 * <p>
 * MINT: Creates/burns INTENT_MARKER token. Owner must sign.
 *   On mint: 1 token created, output to own script with PaymentIntent datum.
 * SPEND (intent validator): Owner signature check to spend intent UTxOs.
 * <p>
 * Works with CfWalletFundsValidator which holds the actual funds.
 * <p>
 * Based on: cardano-template-and-ecosystem-monitoring/simple-wallet
 */
@MultiValidator
public class CfSimpleWalletValidator {

    @Param static byte[] owner;

    record PaymentIntent(PlutusData recipient, BigInteger lovelaceAmt, byte[] data) {}

    sealed interface WalletAction permits MintIntent, BurnIntent {}
    record MintIntent() implements WalletAction {}
    record BurnIntent() implements WalletAction {}

    @Entrypoint(purpose = Purpose.MINT)
    public static boolean mint(WalletAction redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        boolean ownerSigned = ContextsLib.signedBy(txInfo, owner);
        if (!ownerSigned) return false;

        ScriptInfo.MintingScript mintInfo = (ScriptInfo.MintingScript) ctx.scriptInfo();
        byte[] policyBytes = (byte[])(Object) mintInfo.policyId();

        return switch (redeemer) {
            case MintIntent m -> handleMintIntent(txInfo, policyBytes);
            case BurnIntent b -> handleBurnIntent(txInfo, policyBytes);
        };
    }

    @Entrypoint(purpose = Purpose.SPEND)
    public static boolean spend(Optional<PlutusData> datum, PlutusData redeemer, ScriptContext ctx) {
        // Intent validator: owner signature check
        return ContextsLib.signedBy(ctx.txInfo(), owner);
    }

    static boolean handleMintIntent(TxInfo txInfo, byte[] policyBytes) {
        // Exactly 1 INTENT token minted
        BigInteger mintCount = ValuesLib.countTokensWithQty(txInfo.mint(), policyBytes, BigInteger.ONE);
        boolean oneMinted = mintCount.compareTo(BigInteger.ONE) == 0;

        // Output to own script (policy = script hash) with intent token and inline datum
        boolean validOutput = false;
        for (var output : txInfo.outputs()) {
            byte[] credHash = AddressLib.credentialHash(output.address());
            if (credHash.equals(policyBytes)) {
                // Check has the minted token
                if (ValuesLib.containsPolicy(output.value(), policyBytes)) {
                    // Check inline datum exists (PaymentIntent)
                    PlutusData datumData = OutputLib.getInlineDatum(output);
                    // If getInlineDatum succeeds without error, datum exists
                    validOutput = true;
                    break;
                }
            }
        }

        return oneMinted && validOutput;
    }

    static boolean handleBurnIntent(TxInfo txInfo, byte[] policyBytes) {
        // Exactly 1 INTENT token burned
        BigInteger burnCount = ValuesLib.countTokensWithQty(txInfo.mint(), policyBytes, BigInteger.ONE.negate());
        return burnCount.compareTo(BigInteger.ONE) == 0;
    }
}
