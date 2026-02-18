package com.example.mpf;

import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.lib.CryptoLib;
import com.bloxbean.cardano.julc.testkit.JvmCryptoProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MerklePatriciaForestry — the on-chain MPF library.
 * <p>
 * Test vectors are derived from the Aiken merkle-patricia-forestry test suite.
 * DirectJavaTests call the executable stubs directly; UplcTests would compile to UPLC.
 */
class MerklePatriciaForestryTest {

    static final HexFormat HEX = HexFormat.of();

    @BeforeAll
    static void setup() {
        Builtins.setCryptoProvider(new JvmCryptoProvider());
    }

    // =========================================================================
    // Helper to parse hex strings
    // =========================================================================

    static byte[] hex(String s) {
        return HEX.parseHex(s);
    }

    // =========================================================================
    // Direct Java Tests — call methods via executable stubs
    // =========================================================================

    @Nested
    class NibbleTests {

        @Test
        void nibble_ab_index0_is_10() {
            // nibble(#"ab", 0) == 10  (0xa = 10)
            assertEquals(10, MerklePatriciaForestry.nibble(hex("ab"), 0));
        }

        @Test
        void nibble_ab_index1_is_11() {
            // nibble(#"ab", 1) == 11  (0xb = 11)
            assertEquals(11, MerklePatriciaForestry.nibble(hex("ab"), 1));
        }

        @Test
        void nibble_reconstructs_byte() {
            // msb * 16 + lsb == byte value
            byte[] bytes = hex("3f");
            int msb = MerklePatriciaForestry.nibble(bytes, 0);
            int lsb = MerklePatriciaForestry.nibble(bytes, 1);
            assertEquals(0x3f, msb * 16 + lsb);
        }

        @Test
        void nibble_range_0_to_15() {
            byte[] bytes = hex("f0");
            int high = MerklePatriciaForestry.nibble(bytes, 0);
            int low = MerklePatriciaForestry.nibble(bytes, 1);
            assertTrue(high >= 0 && high <= 15);
            assertTrue(low >= 0 && low <= 15);
            assertEquals(15, high);
            assertEquals(0, low);
        }
    }

    @Nested
    class NibblesTests {

        @Test
        void nibbles_empty_range() {
            // nibbles(#"0123456789", 2, 2) == #[]
            byte[] result = MerklePatriciaForestry.nibbles(hex("0123456789"), 2, 2);
            assertEquals(0, result.length);
        }

        @Test
        void nibbles_single_element() {
            // nibbles(#"0123456789", 2, 3) == #[2]
            byte[] result = MerklePatriciaForestry.nibbles(hex("0123456789"), 2, 3);
            assertArrayEquals(new byte[]{2}, result);
        }

        @Test
        void nibbles_four_elements() {
            // nibbles(#"0123456789", 4, 8) == #[4, 5, 6, 7]
            byte[] result = MerklePatriciaForestry.nibbles(hex("0123456789"), 4, 8);
            assertArrayEquals(new byte[]{4, 5, 6, 7}, result);
        }

        @Test
        void nibbles_three_elements() {
            // nibbles(#"0123456789", 3, 6) == #[3, 4, 5]
            byte[] result = MerklePatriciaForestry.nibbles(hex("0123456789"), 3, 6);
            assertArrayEquals(new byte[]{3, 4, 5}, result);
        }

        @Test
        void nibbles_six_elements() {
            // nibbles(#"0123456789", 1, 7) == #[1, 2, 3, 4, 5, 6]
            byte[] result = MerklePatriciaForestry.nibbles(hex("0123456789"), 1, 7);
            assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6}, result);
        }
    }

    @Nested
    class SuffixTests {

        @Test
        void suffix_even_cursor_0() {
            // suffix(#"abcd456789", 0) == #"ffabcd456789"
            byte[] result = MerklePatriciaForestry.suffix(hex("abcd456789"), 0);
            assertArrayEquals(hex("ffabcd456789"), result);
        }

        @Test
        void suffix_odd_cursor_1() {
            // suffix(#"abcd456789", 1) == #"000bcd456789"
            byte[] result = MerklePatriciaForestry.suffix(hex("abcd456789"), 1);
            assertArrayEquals(hex("000bcd456789"), result);
        }

        @Test
        void suffix_even_cursor_2() {
            // suffix(#"abcd456789", 2) == #"ffcd456789"
            byte[] result = MerklePatriciaForestry.suffix(hex("abcd456789"), 2);
            assertArrayEquals(hex("ffcd456789"), result);
        }

        @Test
        void suffix_even_cursor_4() {
            // suffix(#"abcd456789", 4) == #"ff456789"
            byte[] result = MerklePatriciaForestry.suffix(hex("abcd456789"), 4);
            assertArrayEquals(hex("ff456789"), result);
        }

        @Test
        void suffix_odd_cursor_5() {
            // suffix(#"abcd456789", 5) == #"00056789"
            byte[] result = MerklePatriciaForestry.suffix(hex("abcd456789"), 5);
            assertArrayEquals(hex("00056789"), result);
        }

        @Test
        void suffix_even_cursor_10_end() {
            // suffix(#"abcd456789", 10) == #"ff"
            byte[] result = MerklePatriciaForestry.suffix(hex("abcd456789"), 10);
            assertArrayEquals(hex("ff"), result);
        }

        @Test
        void suffix_prefix_byte_invariant() {
            // The first byte of suffix is either 0xff (even cursor) or 0x00 (odd cursor)
            byte[] path = hex("abcdef0123");
            for (int cursor = 0; cursor < 10; cursor++) {
                byte[] s = MerklePatriciaForestry.suffix(path, cursor);
                int head = Byte.toUnsignedInt(s[0]);
                if (cursor % 2 == 0) {
                    assertEquals(0xff, head, "Even cursor should produce 0xff prefix");
                } else {
                    assertEquals(0, head, "Odd cursor should produce 0x00 prefix");
                    int secondByte = Byte.toUnsignedInt(s[1]);
                    assertTrue(secondByte < 16, "After 0x00, nibble should be < 16");
                }
            }
        }
    }

    @Nested
    class CombineTests {

        @Test
        void combine_null_hashes() {
            // combine(nullHash, nullHash) == nullHash2
            byte[] nh = MerklePatriciaForestry.nullHash();
            byte[] nh2 = MerklePatriciaForestry.nullHash2();
            byte[] combined = MerklePatriciaForestry.combine(nh, nh);
            assertArrayEquals(nh2, combined);
        }

        @Test
        void nullHash2_to_nullHash4() {
            // combine(nullHash2, nullHash2) == nullHash4
            byte[] nh2 = MerklePatriciaForestry.nullHash2();
            byte[] nh4 = MerklePatriciaForestry.nullHash4();
            assertArrayEquals(nh4, MerklePatriciaForestry.combine(nh2, nh2));
        }

        @Test
        void nullHash4_to_nullHash8() {
            // combine(nullHash4, nullHash4) == nullHash8
            byte[] nh4 = MerklePatriciaForestry.nullHash4();
            byte[] nh8 = MerklePatriciaForestry.nullHash8();
            assertArrayEquals(nh8, MerklePatriciaForestry.combine(nh4, nh4));
        }

        @Test
        void combine_produces_32_bytes() {
            byte[] result = MerklePatriciaForestry.combine(new byte[32], new byte[32]);
            assertEquals(32, result.length);
        }
    }

    @Nested
    class Merkle4Tests {

        @Test
        void merkle4_places_node_correctly() {
            // From merkling.tests.ak:
            // root = combine(combine(a, b), combine(c, d))
            // merkle_4(0, a, combine(c,d), b) == root
            // merkle_4(1, b, combine(c,d), a) == root
            // merkle_4(2, c, combine(a,b), d) == root
            // merkle_4(3, d, combine(a,b), c) == root
            byte[] a = CryptoLib.blake2b_256(new byte[]{1});
            byte[] b = CryptoLib.blake2b_256(new byte[]{2});
            byte[] c = CryptoLib.blake2b_256(new byte[]{3});
            byte[] d = CryptoLib.blake2b_256(new byte[]{4});

            byte[] ab = MerklePatriciaForestry.combine(a, b);
            byte[] cd = MerklePatriciaForestry.combine(c, d);
            byte[] root = MerklePatriciaForestry.combine(ab, cd);

            assertArrayEquals(root, MerklePatriciaForestry.merkle4(0, a, cd, b));
            assertArrayEquals(root, MerklePatriciaForestry.merkle4(1, b, cd, a));
            assertArrayEquals(root, MerklePatriciaForestry.merkle4(2, c, ab, d));
            assertArrayEquals(root, MerklePatriciaForestry.merkle4(3, d, ab, c));
        }
    }

    @Nested
    class BitcoinBlockProofTests {

        // Test data from merkle-patricia-forestry.tests.ak
        static final byte[] ROOT = hex("225a4599b804ba53745538c83bfa699ecf8077201b61484c91171f5910a4a8f9");
        static final byte[] BLOCK_HASH = hex("00000000000000000002d79d6d49c114e174c22b8d8432432ce45a05fd6a4d7b");
        static final byte[] BLOCK_BODY = hex("f48fcceeac43babbf53a90023be2799a9d7617098b76ff229440ccbd1fd1b4d4");

        static final JulcList<ProofStep> PROOF_845999 = JulcList.of(
                new ProofStep.Branch(0, hex(
                        "bc13df27a19f8caf0bf922c900424025282a892ba8577095fd35256c9d553ca1" +
                        "3a589f00f97a417d07903d138b92f25f879f9462994bf0e69b51fa19a67faef9" +
                        "96c3f8196278c6ab196979911cc48b2d4a0d2a7aa5ef3f939eb056256d8efdfa" +
                        "0aa456963256af4fcb1ad43ef4e6323d1ca92c6d83ed4327904280228e1ba159")),
                new ProofStep.Branch(0, hex(
                        "eb63f921bd3ac576f979eba32490f8c0988f468d3308c2ed5480aaf6ff27cf9a" +
                        "0e610d8c38c17236104b995eb83aa062181525dccd72a755772004cc2bf4faaf" +
                        "3ac3518525f4b5dec498c8034c566a3539e524c6a2cd5fc8f19c6559a3226051" +
                        "3edca31960cd1f5cc6882b820ef57ca65d740734379781db22b479ae0e3bdef3")),
                new ProofStep.Branch(0, hex(
                        "e7bbc4fc5e5875f6f5469e8a016fa99a872075360e64d623f8b8688e6b63fee5" +
                        "091a7260d2a4fe1ca489c48020772e6d334c63115743e7c390450a139c6bc63b" +
                        "219aff62993846b5522bc1b1fffb5b485fc58d952a8f171bb6a000062fbdcb0e" +
                        "aa5637413d82489f0492c663ad0bac0a2a83b32e1b14e3940017cf830d47378e")),
                new ProofStep.Branch(0, hex(
                        "464f4d2211c7fe6e7e1b298be6cfa6fd35d562d3b37ce8b979df45fac9dbc5e0" +
                        "d4d93d0b14d7061351763cee1d878b8686c658cfca7ef69cfd58d50ffc3a4673" +
                        "40c3abc4067220f82f2dffe455038da3138859bffdb3d34fd7e84305de2ddfc6" +
                        "1630c97424469f6de887d42ca155069789fa1b843bdf26496d29222f33f8f6ae")),
                new ProofStep.Branch(0, hex(
                        "2170e155c04db534b1f0e27bb7604907d26b046e51dd7ca59f56693e8033b164" +
                        "03f9ff21fe66b6071042d35dcbad83950ffb1e3a2ad6673f96d043f67d58e820" +
                        "40e0c17f6230c44b857ed04dccd8ff1b84819abf26fa9e1e86d61fb08c80b74c" +
                        "0000000000000000000000000000000000000000000000000000000000000000"))
        );

        @Test
        void verify_bitcoin_block_845999_has() {
            boolean result = MerklePatriciaForestry.has(ROOT, BLOCK_HASH, BLOCK_BODY, PROOF_845999);
            assertTrue(result, "Bitcoin block 845999 proof should verify as present in the trie");
        }

        @Test
        void verify_wrong_value_fails() {
            byte[] wrongBody = hex("0000000000000000000000000000000000000000000000000000000000000000");
            boolean result = MerklePatriciaForestry.has(ROOT, BLOCK_HASH, wrongBody, PROOF_845999);
            assertFalse(result, "Wrong value should not verify");
        }

        @Test
        void verify_wrong_key_fails() {
            byte[] wrongKey = hex("0000000000000000000000000000000000000000000000000000000000000001");
            boolean result = MerklePatriciaForestry.has(ROOT, wrongKey, BLOCK_BODY, PROOF_845999);
            assertFalse(result, "Wrong key should not verify");
        }

        @Test
        void verify_wrong_root_fails() {
            byte[] wrongRoot = hex("0000000000000000000000000000000000000000000000000000000000000000");
            boolean result = MerklePatriciaForestry.has(wrongRoot, BLOCK_HASH, BLOCK_BODY, PROOF_845999);
            assertFalse(result, "Wrong root should not verify");
        }
    }

    @Nested
    class MissProofTests {

        // For miss proof, we use the insert test from Aiken.
        // In insert_bitcoin_block_845602, before insertion the key is NOT in the trie.
        // So excluding(block_hash_845602) should equal the original root.
        static final byte[] ROOT = hex("225a4599b804ba53745538c83bfa699ecf8077201b61484c91171f5910a4a8f9");
        static final byte[] BLOCK_HASH_845602 = hex("0000000000000000000261a131bf48cc5a19658ade8cfede99dc1c3933300d60");

        static final JulcList<ProofStep> PROOF_845602 = JulcList.of(
                new ProofStep.Branch(0, hex(
                        "bc13df27a19f8caf0bf922c900424025282a892ba8577095fd35256c9d553ca1" +
                        "20b8645121ebc9057f7b28fa4c0032b1f49e616dfb8dbd88e4bffd7c0844d29b" +
                        "011b1af0993ac88158342583053094590c66847acd7890c86f6de0fde0f7ae24" +
                        "79eafca17f9659f252fa13ee353c879373a65ca371093525cf359fae1704cf4a")),
                new ProofStep.Branch(0, hex(
                        "255753863960985679b4e752d4b133322ff567d210ffbb10ee56e51177db0574" +
                        "60b547fe42c6f44dfef8b3ecee35dfd4aa105d28b94778a3f1bb8211cf2679d7" +
                        "434b40848aebdd6565b59efdc781ffb5ca8a9f2b29f95a47d0bf01a09c38fa39" +
                        "359515ddb9d2d37a26bccb022968ef4c8e29a95c7c82edcbe561332ff79a51af")),
                new ProofStep.Branch(0, hex(
                        "9d95e34e6f74b59d4ea69943d2759c01fe9f986ff0c03c9e25ab561b23a413b7" +
                        "7792fa78d9fbcb98922a4eed2df0ed70a2852ae8dbac8cff54b9024f229e6662" +
                        "9136cfa60a569c464503a8b7779cb4a632ae052521750212848d1cc0ebed406e" +
                        "1ba4876c4fd168988c8fe9e226ed283f4d5f17134e811c3b5322bc9c494a598b")),
                new ProofStep.Branch(0, hex(
                        "b93c3b90e647f90beb9608aecf714e3fbafdb7f852cfebdbd8ff435df84a4116" +
                        "d10ccdbe4ea303efbf0f42f45d8dc4698c3890595be97e4b0f39001bde3f2ad9" +
                        "5b8f6f450b1e85d00dacbd732b0c5bc3e8c92fc13d43028777decb669060558" +
                        "821db21a9b01ba5ddf6932708cd96d45d41a1a4211412a46fe41870968389ec96")),
                new ProofStep.Branch(0, hex(
                        "f89f9d06b48ecc0e1ea2e6a43a9047e1ff02ecf9f79b357091ffc0a7104bbb26" +
                        "0908746f8e61ecc60dfe26b8d03bcc2f1318a2a95fa895e4d1aadbb917f9f293" +
                        "6b900c75ffe49081c265df9c7c329b9036a0efb46d5bac595a1dcb7c200e7d59" +
                        "0000000000000000000000000000000000000000000000000000000000000000"))
        );

        @Test
        void miss_block_845602_before_insert() {
            boolean result = MerklePatriciaForestry.miss(ROOT, BLOCK_HASH_845602, PROOF_845602);
            assertTrue(result, "Block 845602 should be absent from the trie (miss proof)");
        }

        @Test
        void miss_wrong_key_fails() {
            // A key that IS in the trie should not pass the miss proof
            byte[] blockHash845999 = hex("00000000000000000002d79d6d49c114e174c22b8d8432432ce45a05fd6a4d7b");
            boolean result = MerklePatriciaForestry.miss(ROOT, blockHash845999, PROOF_845602);
            assertFalse(result, "A different key should not pass with this miss proof");
        }
    }

    @Nested
    class IncludingExcludingTests {

        @Test
        void including_computes_root_for_single_element() {
            // For a trie with a single element, including should produce a deterministic root
            byte[] key = hex("deadbeef");
            byte[] value = hex("cafebabe");
            byte[] root = MerklePatriciaForestry.including(key, value, JulcList.of());
            assertNotNull(root);
            assertEquals(32, root.length);

            // Verify has() with empty proof for single-element trie
            assertTrue(MerklePatriciaForestry.has(root, key, value, JulcList.of()));
        }

        @Test
        void excluding_empty_proof_is_null_hash() {
            // Excluding with empty proof = nullHash (empty trie)
            byte[] key = hex("deadbeef");
            byte[] root = MerklePatriciaForestry.excluding(key, JulcList.of());
            assertArrayEquals(MerklePatriciaForestry.nullHash(), root);
        }

        @Test
        void miss_in_empty_trie() {
            // An empty trie (nullHash root) should report miss for any key
            byte[] emptyRoot = MerklePatriciaForestry.nullHash();
            byte[] key = hex("aabbccdd");
            assertTrue(MerklePatriciaForestry.miss(emptyRoot, key, JulcList.of()));
        }
    }
}
