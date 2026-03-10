package com.example.cftemplates.crowdfund;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.testkit.JulcEval;
import com.example.cftemplates.crowdfund.onchain.CfCrowdfundValidator;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JulcEval unit tests for {@code sumMapValues} and {@code sumSignerDonations}
 * logic from CfCrowdfundValidator, compiled and run on the UPLC VM.
 * <p>
 * Uses {@code forClass()} with {@code @Param} support to compile the validator's
 * methods in their full context, avoiding the workarounds needed with
 * isolated {@code @OnchainLibrary} compilation.
 */
class CrowdfundMethodTest {

    private final JulcEval eval = JulcEval.forClass(CfCrowdfundValidator.class,
            PlutusData.bytes(new byte[28]),       // beneficiary (dummy)
            PlutusData.integer(10_000_000),       // goal (dummy)
            PlutusData.integer(1_000_000_000));   // deadline (dummy)

    // -- Test data --------------------------------------------------------

    private static final byte[] PKH1 = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
            11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28};
    private static final byte[] PKH2 = new byte[]{28, 27, 26, 25, 24, 23, 22, 21, 20, 19,
            18, 17, 16, 15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1};
    private static final byte[] PKH_UNKNOWN = new byte[]{99, 99, 99, 99, 99, 99, 99, 99, 99, 99,
            99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99};

    /** Map { pkh1 -> 5_000_000, pkh2 -> 3_000_000 } */
    private static PlutusData twoEntryMap() {
        return PlutusData.map(
                new PlutusData.Pair(PlutusData.bytes(PKH1), PlutusData.integer(5_000_000)),
                new PlutusData.Pair(PlutusData.bytes(PKH2), PlutusData.integer(3_000_000)));
    }

    /** Map { pkh1 -> 5_000_000 } */
    private static PlutusData singleEntryMap() {
        return PlutusData.map(
                new PlutusData.Pair(PlutusData.bytes(PKH1), PlutusData.integer(5_000_000)));
    }

    /** Empty map */
    private static PlutusData emptyMap() {
        return PlutusData.map();
    }

    private static PlutusData signersList(byte[]... pkhs) {
        PlutusData[] items = new PlutusData[pkhs.length];
        for (int i = 0; i < pkhs.length; i++) {
            items[i] = PlutusData.bytes(pkhs[i]);
        }
        return PlutusData.list(items);
    }

    // -- sumMapValues tests -----------------------------------------------

    @Test
    void sumMapValues_twoEntries() {
        BigInteger result = eval.call("sumMapValues", twoEntryMap()).asInteger();
        assertEquals(BigInteger.valueOf(8_000_000), result);
    }

    @Test
    void sumMapValues_singleEntry() {
        BigInteger result = eval.call("sumMapValues", singleEntryMap()).asInteger();
        assertEquals(BigInteger.valueOf(5_000_000), result);
    }

    @Test
    void sumMapValues_empty() {
        BigInteger result = eval.call("sumMapValues", emptyMap()).asInteger();
        assertEquals(BigInteger.ZERO, result);
    }

    // -- sumSignerDonations tests -----------------------------------------

    @Test
    void sumSignerDonations_oneSignerMatches() {
        BigInteger result = eval.call("sumSignerDonations",
                twoEntryMap(), signersList(PKH1)).asInteger();
        assertEquals(BigInteger.valueOf(5_000_000), result);
    }

    @Test
    void sumSignerDonations_noSignerMatches() {
        BigInteger result = eval.call("sumSignerDonations",
                twoEntryMap(), signersList(PKH_UNKNOWN)).asInteger();
        assertEquals(BigInteger.ZERO, result);
    }

    @Test
    void sumSignerDonations_allSignersMatch() {
        BigInteger result = eval.call("sumSignerDonations",
                twoEntryMap(), signersList(PKH1, PKH2)).asInteger();
        assertEquals(BigInteger.valueOf(8_000_000), result);
    }
}
