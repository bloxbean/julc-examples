package com.example.cftemplates.atomictx.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.MultiValidator;
import com.bloxbean.cardano.julc.stdlib.annotation.Purpose;
import java.util.Optional;

/**
 * Atomic Transaction validator — demonstrates Cardano's native atomicity.
 * <p>
 * A combined multi-validator: the minting policy requires a secret password,
 * while the spending validator always succeeds. If the mint fails (wrong password),
 * the entire transaction fails atomically.
 * <p>
 * Based on: cardano-template-and-ecosystem-monitoring/atomic-transaction
 */
@MultiValidator
public class CfAtomicTxValidator {

    record MintRedeemer(String password) {}

    @Entrypoint(purpose = Purpose.MINT)
    public static boolean handleMint(MintRedeemer redeemer, ScriptContext ctx) {
        return redeemer.password().equals("super_secret_password");
    }

    @Entrypoint(purpose = Purpose.SPEND)
    public static boolean handleSpend(Optional<PlutusData> datum, PlutusData redeemer, ScriptContext ctx) {
        return true;
    }
}