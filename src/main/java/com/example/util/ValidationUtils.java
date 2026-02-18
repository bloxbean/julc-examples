package com.example.util;

import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;
import com.bloxbean.cardano.julc.ledger.TxInfo;
import com.bloxbean.cardano.julc.ledger.Value;
import com.bloxbean.cardano.julc.ledger.PubKeyHash;
import com.bloxbean.cardano.julc.stdlib.lib.IntervalLib;
import com.bloxbean.cardano.julc.stdlib.lib.ValuesLib;

import java.math.BigInteger;

@OnchainLibrary
public class ValidationUtils {

    public static boolean isAfterDeadline(TxInfo txInfo, BigInteger deadline) {
        return IntervalLib.contains(txInfo.validRange(), deadline);
    }

    public static boolean hasSigner(TxInfo txInfo, byte[] pkh) {
        return txInfo.signatories().contains(PubKeyHash.of(pkh));
    }

    public static boolean hasMinLovelace(Value value, BigInteger minLovelace) {
        BigInteger lovelace = ValuesLib.lovelaceOf(value);
        return lovelace.compareTo(minLovelace) >= 0;
    }
}
