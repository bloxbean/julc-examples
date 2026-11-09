package com.example.validators;

import org.julclang.stdlib.annotation.Param;
import org.julclang.stdlib.annotation.SpendingValidator;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.ledger.ScriptContext;
import org.julclang.ledger.TxInfo;
import org.julclang.ledger.PubKeyHash;
import org.julclang.stdlib.lib.ContextsLib;
import org.julclang.stdlib.lib.ValuesLib;
import com.example.util.SumTest;

import java.math.BigInteger;


@SpendingValidator
public class VestingValidator {

    @Param
    static BigInteger no;
    @Param
    static String msg;

    record VestingDatum(byte[] beneficiary) {}

    record NestedRedeemer(BigInteger no, String msg) {}
    record VestingRedeemer(NestedRedeemer nestedRedeemer) {}

    @Entrypoint
    public static boolean validate(VestingDatum datum, VestingRedeemer redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        BigInteger requiredNo = redeemer.nestedRedeemer().no();

        ContextsLib.trace("Checking outputs");
        boolean found = false;
        for (var output : txInfo.outputs()) {
            BigInteger lovelace = ValuesLib.lovelaceOf(output.value());
            found = found || lovelace.compareTo(new BigInteger("5000000")) == 0;
        }


        int k = SumTest.sum(4, 3);

        ContextsLib.trace("Checking beneficiary");
        return isBeneficiary(txInfo, PubKeyHash.of(datum.beneficiary()))
                && k == 7
                && requiredNo.equals(no)
                && redeemer.nestedRedeemer().msg().equals(msg)
                && txInfo.outputs().size() == 2 && found;
    }

    static boolean isBeneficiary(TxInfo txInfo, PubKeyHash pkh) {
        var sigs = txInfo.signatories();
        return sigs.contains(pkh);
    }
}
