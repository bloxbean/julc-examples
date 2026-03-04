package com.example.swap.onchain;

import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;
import com.bloxbean.cardano.julc.stdlib.lib.ContextsLib;
import com.bloxbean.cardano.julc.stdlib.lib.OutputLib;
import com.bloxbean.cardano.julc.stdlib.lib.ValuesLib;

import java.math.BigInteger;
import java.util.Optional;

/**
 * Simple DEX swap order validator — token-to-token swap.
 * <p>
 * A maker locks offered tokens at the script address with an {@code OrderDatum}.
 * A taker can fill the order by paying the requested tokens to the maker.
 * The maker can cancel the order to reclaim their tokens.
 * <p>
 * Features demonstrated:
 * - Sealed interface redeemer (2 variants: FillOrder, CancelOrder)
 * - Switch expression pattern matching
 * - OutputLib.lovelacePaidTo() for ADA payment checks
 * - ValuesLib.assetOf() for native token payment checks
 * - For-each loop with BigInteger accumulator
 * - PubKeyHash.of() and Address construction
 * - ContextsLib.trace()
 */
@SpendingValidator
public class SwapOrder {

    public record OrderDatum(
            byte[] maker,
            byte[] offeredPolicy,
            byte[] offeredToken,
            BigInteger offeredAmount,
            byte[] requestedPolicy,
            byte[] requestedToken,
            BigInteger requestedAmount
    ) {}

    public sealed interface SwapAction permits FillOrder, CancelOrder {}
    public record FillOrder() implements SwapAction {}
    public record CancelOrder() implements SwapAction {}

    static Address makerAddress(byte[] makerPkh) {
        return new Address(
                new Credential.PubKeyCredential(PubKeyHash.of(makerPkh)),
                Optional.empty());
    }

    static boolean validateFillOrder(OrderDatum datum, TxInfo txInfo) {
        ContextsLib.trace("Fill order");
        Address makerAddr = makerAddress(datum.maker());

        // ADA swap: use OutputLib.lovelacePaidTo
        if (Builtins.lengthOfByteString(datum.requestedPolicy()) == 0) {
            BigInteger paidLovelace = OutputLib.lovelacePaidTo(txInfo.outputs(), makerAddr);
            return paidLovelace.compareTo(datum.requestedAmount()) >= 0;
        }

        // Native token swap: for-each accumulator with ValuesLib.assetOf
        BigInteger paidAmount = BigInteger.ZERO;
        for (var output : OutputLib.outputsAt(txInfo.outputs(), makerAddr)) {
            BigInteger amt = ValuesLib.assetOf(
                    output.value(),
                    datum.requestedPolicy(),
                    datum.requestedToken());
            paidAmount = paidAmount.add(amt);
        }
        return paidAmount.compareTo(datum.requestedAmount()) >= 0;
    }

    static boolean validateCancelOrder(OrderDatum datum, TxInfo txInfo) {
        ContextsLib.trace("Cancel order");
        return txInfo.signatories().contains(PubKeyHash.of(datum.maker()));
    }

    @Entrypoint
    public static boolean validate(OrderDatum datum, SwapAction redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        ContextsLib.trace("SwapOrder validate");
        return switch (redeemer) {
            case FillOrder f -> validateFillOrder(datum, txInfo);
            case CancelOrder c -> validateCancelOrder(datum, txInfo);
        };
    }
}
