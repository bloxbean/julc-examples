package com.example.util;

import com.bloxbean.cardano.julc.testkit.JulcEval;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Evaluates ByteStringLib.toHex() and intToDecimalString() on the UPLC VM
 * via JulcEval, verifying byte-level correctness with the scalus evaluator.
 */
class ByteStringLibEvalTest {

    private static final String WRAPPER = """
            import com.bloxbean.cardano.julc.stdlib.lib.ByteStringLib;
            import java.math.BigInteger;

            @OnchainLibrary
            class ByteStringWrapper {
                static byte[] toHex(byte[] bs) {
                    return ByteStringLib.toHex(bs);
                }
                static byte[] intToDecimalString(BigInteger n) {
                    return ByteStringLib.intToDecimalString(n);
                }
            }
            """;

    private final JulcEval eval = JulcEval.forSource(WRAPPER);

    // --- toHex ---

    @Test
    void toHex_dead() {
        byte[] result = eval.call("toHex", new byte[]{(byte) 0xDE, (byte) 0xAD}).asByteString();
        assertArrayEquals("dead".getBytes(), result);
    }

    @Test
    void toHex_zeroPadding() {
        byte[] result = eval.call("toHex", new byte[]{0x00, (byte) 0xFF}).asByteString();
        assertArrayEquals("00ff".getBytes(), result);
    }

    @Test
    void toHex_empty() {
        byte[] result = eval.call("toHex", new byte[]{}).asByteString();
        assertArrayEquals(new byte[]{}, result);
    }

    @Test
    void toHex_singleByte() {
        byte[] result = eval.call("toHex", new byte[]{(byte) 0xAB}).asByteString();
        assertArrayEquals("ab".getBytes(), result);
    }

    @Test
    void toHex_allZeros() {
        byte[] result = eval.call("toHex", new byte[]{0x00, 0x00, 0x00}).asByteString();
        assertArrayEquals("000000".getBytes(), result);
    }

    // --- intToDecimalString ---

    @Test
    void intToDecimalString_zero() {
        byte[] result = eval.call("intToDecimalString", BigInteger.ZERO).asByteString();
        assertArrayEquals("0".getBytes(), result);
    }

    @Test
    void intToDecimalString_42() {
        byte[] result = eval.call("intToDecimalString", BigInteger.valueOf(42)).asByteString();
        assertArrayEquals("42".getBytes(), result);
    }

    @Test
    void intToDecimalString_12345() {
        byte[] result = eval.call("intToDecimalString", BigInteger.valueOf(12345)).asByteString();
        assertArrayEquals("12345".getBytes(), result);
    }

    @Test
    void intToDecimalString_largeNumber() {
        byte[] result = eval.call("intToDecimalString", BigInteger.valueOf(999999999)).asByteString();
        assertArrayEquals("999999999".getBytes(), result);
    }

    @Test
    void intToDecimalString_one() {
        byte[] result = eval.call("intToDecimalString", BigInteger.ONE).asByteString();
        assertArrayEquals("1".getBytes(), result);
    }
}
