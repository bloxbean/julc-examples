package com.example.validators;

import com.bloxbean.cardano.julc.stdlib.annotation.Param;
import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;
import com.bloxbean.cardano.julc.stdlib.annotation.Validator;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.ledger.ScriptContext;
import com.bloxbean.cardano.julc.ledger.TxInfo;
import com.bloxbean.cardano.julc.ledger.PubKeyHash;
import com.bloxbean.cardano.julc.stdlib.lib.ContextsLib;
//import com.bloxbean.cardano.julc.stdlib.lib.ValuesLib;
import com.bloxbean.cardano.julc.stdlib.lib.ValuesLib;
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
