package com.example.util;

import org.julclang.stdlib.annotation.OnchainLibrary;
import org.julclang.ledger.TxInfo;
import org.julclang.ledger.Value;
import org.julclang.ledger.PubKeyHash;
import org.julclang.stdlib.lib.IntervalLib;
import org.julclang.stdlib.lib.ValuesLib;

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
