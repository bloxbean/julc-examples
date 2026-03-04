package com.example.util;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.testkit.JulcEval;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.example.mpf.MerklePatriciaForestry;

import java.math.BigInteger;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that demonstrate JulcEval — type-safe proxies and fluent calls
 * for compiling and evaluating individual helper methods on the UPLC VM.
 * <p>
 * Each test auto-discovers the {@code .java} file from {@code src/main/java},
 * resolves library dependencies, compiles a single method to UPLC,
 * evaluates it, and checks the result.
 */
class MethodEvaluatorExampleTest {

    // -------------------------------------------------------------------------
    // SumTest — simple @OnchainLibrary with basic arithmetic
    // -------------------------------------------------------------------------

    @Nested
    class SumTestMethods {

        interface SumTestProxy {
            BigInteger sum(int a, int b);
        }

        private final SumTestProxy proxy =
                JulcEval.forClass(SumTest.class).create(SumTestProxy.class);

        @Test
        void sum_basic() {
            assertEquals(BigInteger.valueOf(7), proxy.sum(4, 3));
        }

        @Test
        void sum_zero() {
            assertEquals(BigInteger.ZERO, proxy.sum(0, 0));
        }

        @Test
        void sum_negative() {
            assertEquals(BigInteger.valueOf(-7), proxy.sum(-10, 3));
        }

        @Test
        void sum_large() {
            // int overflow in proxy arg → use fluent call with long args
            var eval = JulcEval.forClass(SumTest.class);
            assertEquals(
                    BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE),
                    eval.call("sum", Long.MAX_VALUE, 1L).asInteger());
        }
    }

    // -------------------------------------------------------------------------
    // ValidationUtils — @OnchainLibrary that uses stdlib (ValuesLib)
    // -------------------------------------------------------------------------

    @Nested
    class ValidationUtilsMethods {

        interface ValidationUtilsProxy {
            boolean hasMinLovelace(PlutusData value, long minLovelace);
        }

        private final ValidationUtilsProxy proxy =
                JulcEval.forClass(ValidationUtils.class).create(ValidationUtilsProxy.class);

        /**
         * Build a minimal Value PlutusData: Map([Pair(policyId, Map([Pair(tokenName, amount)]))])
         * ADA uses empty policy ID and empty token name.
         */
        static PlutusData lovelaceValue(long lovelace) {
            return PlutusData.map(
                    new PlutusData.Pair(PlutusData.bytes(new byte[0]),
                            PlutusData.map(
                                    new PlutusData.Pair(PlutusData.bytes(new byte[0]),
                                            PlutusData.integer(lovelace)))));
        }

        @Test
        void hasMinLovelace_sufficient() {
            // 5 ADA >= 2 ADA → true
            assertTrue(proxy.hasMinLovelace(lovelaceValue(5_000_000), 2_000_000));
        }

        @Test
        void hasMinLovelace_exact() {
            // 5 ADA >= 5 ADA → true
            assertTrue(proxy.hasMinLovelace(lovelaceValue(5_000_000), 5_000_000));
        }

        @Test
        void hasMinLovelace_insufficient() {
            // 1 ADA >= 5 ADA → false
            assertFalse(proxy.hasMinLovelace(lovelaceValue(1_000_000), 5_000_000));
        }

        @Test
        void hasMinLovelace_zero() {
            // 0 ADA >= 0 ADA → true
            assertTrue(proxy.hasMinLovelace(lovelaceValue(0), 0));
        }
    }

    // -------------------------------------------------------------------------
    // MerklePatriciaForestry — @OnchainLibrary with self-recursive methods
    // -------------------------------------------------------------------------

    @Nested
    class MpfMethods {

        interface MpfProxy {
            BigInteger nibble(byte[] bs, int index);
            byte[] nibbles(byte[] bs, int cursor, int end);
            byte[] combine(byte[] a, byte[] b);
            byte[] nullHash();
            byte[] combineAt(byte[] h, byte[] o, int position);
        }

        static final HexFormat HEX = HexFormat.of();

        static byte[] hex(String s) {
            return HEX.parseHex(s);
        }

        private final MpfProxy proxy =
                JulcEval.forClass(MerklePatriciaForestry.class).create(MpfProxy.class);

        @Test
        void nibble_highNibble() {
            // nibble(#"ab", 0) == 0xa == 10
            assertEquals(BigInteger.valueOf(10), proxy.nibble(hex("ab"), 0));
        }

        @Test
        void nibble_lowNibble() {
            // nibble(#"ab", 1) == 0xb == 11
            assertEquals(BigInteger.valueOf(11), proxy.nibble(hex("ab"), 1));
        }

        @Test
        void nibbles_singleNibble() {
            // nibbles(#"ab", 0, 1) extracts just the high nibble of 0xab = [0x0a]
            assertArrayEquals(new byte[]{0x0a}, proxy.nibbles(hex("ab"), 0, 1));
        }

        @Test
        void nibbles_twoNibbles() {
            // nibbles(#"ab", 0, 2) extracts both nibbles of 0xab = [0x0a, 0x0b]
            assertArrayEquals(new byte[]{0x0a, 0x0b}, proxy.nibbles(hex("ab"), 0, 2));
        }

        @Test
        void nibbles_emptyRange() {
            // nibbles(#"ab", 2, 2) == empty (start == end)
            assertArrayEquals(new byte[0], proxy.nibbles(hex("ab"), 2, 2));
        }

        @Test
        void combine_producesBlake2bHash() {
            // combine(a, b) = blake2b_256(a ++ b); result is always 32 bytes
            assertEquals(32, proxy.combine(new byte[32], new byte[32]).length);
        }

        @Test
        void nullHash_is32Bytes() {
            var result = proxy.nullHash();
            assertEquals(32, result.length);
            // All zeros
            assertArrayEquals(new byte[32], result);
        }

        @Test
        void combineAt_even() {
            // combineAt(h, o, 0) == combine(h, o) (even position)
            byte[] h = new byte[32];
            h[0] = 1;
            byte[] o = new byte[32];
            o[0] = 2;
            assertArrayEquals(proxy.combine(h, o), proxy.combineAt(h, o, 0));
        }

        @Test
        void combineAt_odd() {
            // combineAt(h, o, 1) == combine(o, h) (odd position — reversed)
            byte[] h = new byte[32];
            h[0] = 1;
            byte[] o = new byte[32];
            o[0] = 2;
            assertArrayEquals(proxy.combine(o, h), proxy.combineAt(h, o, 1));
        }
    }

    // -------------------------------------------------------------------------
    // Fluent call() API — alternative pattern without defining proxy interfaces
    // -------------------------------------------------------------------------

    @Nested
    class FluentCallExamples {

        @Test
        void sumFluent() {
            var eval = JulcEval.forClass(SumTest.class);
            assertEquals(BigInteger.valueOf(7), eval.call("sum", 4, 3).asInteger());
        }

        @Test
        void hasMinLovelaceFluent() {
            var eval = JulcEval.forClass(ValidationUtils.class);
            var value = ValidationUtilsMethods.lovelaceValue(5_000_000);
            assertTrue(eval.call("hasMinLovelace", value, 2_000_000L).asBoolean());
        }

        @Test
        void nibbleFluent() {
            var eval = JulcEval.forClass(MerklePatriciaForestry.class);
            assertEquals(BigInteger.valueOf(10),
                    eval.call("nibble", HexFormat.of().parseHex("ab"), 0).asInteger());
        }
    }
}
