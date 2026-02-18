package com.example.validators;

import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.core.types.JulcMap;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.stdlib.lib.CryptoLib;
import com.bloxbean.cardano.julc.testkit.*;
import com.bloxbean.cardano.julc.vm.EvalResult;
import com.bloxbean.cardano.vds.mpf.MpfTrie;
import com.bloxbean.cardano.vds.mpf.internal.TestNodeStore;
import com.example.mpf.MerklePatriciaForestry;
import com.example.mpf.Neighbor;
import com.example.mpf.ProofStep;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MpfRegistryValidator — sealed interface redeemer (VerifyHas / VerifyMiss).
 * <p>
 * DirectJavaTests: call validator logic directly using bitcoin block test vectors.
 * UplcTests: compile to UPLC and evaluate with MpfTrie-generated proofs.
 */
class MpfRegistryValidatorTest extends ContractTest {

    static final HexFormat HEX = HexFormat.of();

    // Bitcoin block test vectors from MerklePatriciaForestryTest
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

    // Miss proof test vectors
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

    static byte[] hex(String s) {
        return HEX.parseHex(s);
    }

    @BeforeAll
    static void setup() {
        initCrypto();
    }

    // ---- Mode A: Direct Java tests ----

    @Nested
    class DirectJavaTests {

        private ScriptContext buildCtx(byte[][] signers) {
            PubKeyHash[] pkhSigners = new PubKeyHash[signers.length];
            for (int i = 0; i < signers.length; i++) pkhSigners[i] = new PubKeyHash(signers[i]);
            var txInfo = new TxInfo(
                    JulcList.of(), JulcList.of(),
                    JulcList.of(),
                    BigInteger.valueOf(200_000),
                    Value.zero(),
                    JulcList.of(), JulcMap.empty(),
                    Interval.always(),
                    JulcList.of(pkhSigners),
                    JulcMap.empty(), JulcMap.empty(),
                    new TxId(new byte[32]),
                    JulcMap.empty(), JulcList.of(),
                    Optional.empty(), Optional.empty());
            return new ScriptContext(txInfo, PlutusData.UNIT,
                    new ScriptInfo.SpendingScript(new TxOutRef(new TxId(new byte[32]), BigInteger.ZERO), Optional.empty()));
        }

        @Test
        void verifyHas_withValidProof_passes() {
            var datum = new MpfRegistryValidator.MpfDatum(ROOT);
            var redeemer = new MpfRegistryValidator.VerifyHas(BLOCK_HASH, BLOCK_BODY, PROOF_845999);
            var ctx = buildCtx(new byte[][]{});

            boolean result = MpfRegistryValidator.validate(datum, redeemer, ctx);
            assertTrue(result, "Valid inclusion proof should pass");
        }

        @Test
        void verifyHas_withWrongValue_fails() {
            var datum = new MpfRegistryValidator.MpfDatum(ROOT);
            byte[] wrongBody = new byte[32]; // all zeros
            var redeemer = new MpfRegistryValidator.VerifyHas(BLOCK_HASH, wrongBody, PROOF_845999);
            var ctx = buildCtx(new byte[][]{});

            boolean result = MpfRegistryValidator.validate(datum, redeemer, ctx);
            assertFalse(result, "Wrong value should not verify");
        }

        @Test
        void verifyHas_withWrongKey_fails() {
            var datum = new MpfRegistryValidator.MpfDatum(ROOT);
            byte[] wrongKey = hex("0000000000000000000000000000000000000000000000000000000000000001");
            var redeemer = new MpfRegistryValidator.VerifyHas(wrongKey, BLOCK_BODY, PROOF_845999);
            var ctx = buildCtx(new byte[][]{});

            boolean result = MpfRegistryValidator.validate(datum, redeemer, ctx);
            assertFalse(result, "Wrong key should not verify");
        }

        @Test
        void verifyMiss_withValidProof_passes() {
            var datum = new MpfRegistryValidator.MpfDatum(ROOT);
            var redeemer = new MpfRegistryValidator.VerifyMiss(BLOCK_HASH_845602, PROOF_845602);
            var ctx = buildCtx(new byte[][]{});

            boolean result = MpfRegistryValidator.validate(datum, redeemer, ctx);
            assertTrue(result, "Valid exclusion proof should pass");
        }

        @Test
        void verifyMiss_withWrongKey_fails() {
            var datum = new MpfRegistryValidator.MpfDatum(ROOT);
            // Use a key that IS in the trie (845999) with a miss proof for 845602
            var redeemer = new MpfRegistryValidator.VerifyMiss(BLOCK_HASH, PROOF_845602);
            var ctx = buildCtx(new byte[][]{});

            boolean result = MpfRegistryValidator.validate(datum, redeemer, ctx);
            assertFalse(result, "A different key should not pass with this miss proof");
        }
    }

    // ---- Mode B: UPLC compilation tests ----

    @Nested
    class UplcTests {

        /**
         * Build the MpfDatum as PlutusData: Constr(0, [BData(trieRoot)])
         */
        private PlutusData buildDatum(byte[] trieRoot) {
            return PlutusData.constr(0, PlutusData.bytes(trieRoot));
        }

        /**
         * Build VerifyHas redeemer: Constr(0, [BData(key), BData(value), ListData(proof)])
         */
        private PlutusData buildVerifyHasRedeemer(byte[] key, byte[] value,
                                                   com.bloxbean.cardano.client.plutus.spec.ListPlutusData proof) {
            PlutusData proofData = PlutusDataAdapter.fromClientLib(proof);
            return PlutusData.constr(0,
                    PlutusData.bytes(key),
                    PlutusData.bytes(value),
                    proofData);
        }

        /**
         * Build VerifyMiss redeemer: Constr(1, [BData(key), ListData(proof)])
         */
        private PlutusData buildVerifyMissRedeemer(byte[] key,
                                                    com.bloxbean.cardano.client.plutus.spec.ListPlutusData proof) {
            PlutusData proofData = PlutusDataAdapter.fromClientLib(proof);
            return PlutusData.constr(1,
                    PlutusData.bytes(key),
                    proofData);
        }

        @Test
        void verifyHas_javaWithMpfTrie() throws Exception {
            // Test: verify the MPF library works in Java with MpfTrie-generated proofs
            var store = new TestNodeStore();
            var trie = new MpfTrie(store);
            byte[] key1 = "alice".getBytes();
            byte[] val1 = "100".getBytes();
            byte[] key2 = "bob".getBytes();
            byte[] val2 = "200".getBytes();
            trie.put(key1, val1);
            trie.put(key2, val2);
            byte[] trieRoot = trie.getRootHash();

            // Get inclusion proof and convert to our ProofStep types
            var proof = trie.getProofPlutusData(key1).orElseThrow();
            System.out.println("=== Proof structure ===");
            System.out.println("Proof items: " + proof.getPlutusDataList().size());
            for (int i = 0; i < proof.getPlutusDataList().size(); i++) {
                var item = proof.getPlutusDataList().get(i);
                if (item instanceof com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData constr) {
                    System.out.println("  Step " + i + ": alt=" + constr.getAlternative()
                            + " fields=" + constr.getData().getPlutusDataList().size());
                    for (int j = 0; j < constr.getData().getPlutusDataList().size(); j++) {
                        var field = constr.getData().getPlutusDataList().get(j);
                        System.out.println("    field " + j + ": " + field.getClass().getSimpleName()
                                + " = " + field.serialize());
                    }
                }
            }

            // Test Java path: convert proof to our types and verify
            PlutusData proofData = PlutusDataAdapter.fromClientLib(proof);
            var datum = new MpfRegistryValidator.MpfDatum(trieRoot);
            // Build VerifyHas — extract proof steps from converted data
            var proofSteps = convertProofSteps(proofData);
            var redeemer = new MpfRegistryValidator.VerifyHas(key1, val1, proofSteps);
            var ctx = new DirectJavaTests().buildCtx(new byte[][]{});
            boolean result = MpfRegistryValidator.validate(datum, redeemer, ctx);
            assertTrue(result, "MpfTrie-generated proof should pass in Java");
        }

        private JulcList<ProofStep> convertProofSteps(PlutusData proofData) {
            if (!(proofData instanceof PlutusData.ListData ld)) {
                throw new IllegalArgumentException("Expected ListData, got: " + proofData.getClass());
            }
            var steps = new java.util.ArrayList<ProofStep>();
            for (var item : ld.items()) {
                if (item instanceof PlutusData.ConstrData c) {
                    var fields = c.fields();
                    if (c.tag() == 0) {
                        // Branch(skip, neighbors)
                        int skip = ((PlutusData.IntData) fields.get(0)).value().intValue();
                        byte[] neighbors = ((PlutusData.BytesData) fields.get(1)).value();
                        steps.add(new ProofStep.Branch(skip, neighbors));
                    } else if (c.tag() == 1) {
                        // Fork(skip, neighbor)
                        int skip = ((PlutusData.IntData) fields.get(0)).value().intValue();
                        var neighborConstr = (PlutusData.ConstrData) fields.get(1);
                        var nf = neighborConstr.fields();
                        int nibble = ((PlutusData.IntData) nf.get(0)).value().intValue();
                        byte[] prefix = ((PlutusData.BytesData) nf.get(1)).value();
                        byte[] root = ((PlutusData.BytesData) nf.get(2)).value();
                        steps.add(new ProofStep.Fork(skip, new Neighbor(nibble, prefix, root)));
                    } else if (c.tag() == 2) {
                        // Leaf(skip, key, value)
                        int skip = ((PlutusData.IntData) fields.get(0)).value().intValue();
                        byte[] key = ((PlutusData.BytesData) fields.get(1)).value();
                        byte[] value = ((PlutusData.BytesData) fields.get(2)).value();
                        steps.add(new ProofStep.Leaf(skip, key, value));
                    }
                }
            }
            return JulcList.of(steps.toArray(new ProofStep[0]));
        }

        /** Diagnostic: test with N proof steps to isolate recursion depth causing crash */
        private void testWithNSteps(int n) throws Exception {
            var program = compileValidator(MpfRegistryValidator.class).program();
            // Use JulcList.take() to get first n elements
            var subset = PROOF_845999.take(n);
            var proofSteps = new PlutusData[(int)subset.size()];
            for (int i = 0; i < (int)subset.size(); i++) {
                var step = subset.get(i);
                if (step instanceof ProofStep.Branch b) {
                    proofSteps[i] = PlutusData.constr(0,
                            PlutusData.integer(java.math.BigInteger.valueOf(b.skip())),
                            PlutusData.bytes(b.neighbors()));
                }
            }
            var datum = buildDatum(ROOT);
            var proofData = PlutusData.list(proofSteps);
            // VerifyHas = Constr(0, [BData(key), BData(value), ListData(proof)])
            var redeemer = PlutusData.constr(0,
                    PlutusData.bytes(BLOCK_HASH),
                    PlutusData.bytes(BLOCK_BODY),
                    proofData);
            var ref = TestDataBuilder.randomTxOutRef_typed();
            var ctx = spendingContext(ref, datum)
                    .redeemer(redeemer)
                    .buildPlutusData();
            var result = evaluate(program, ctx);
            boolean isHeadListError = (result instanceof EvalResult.Failure f)
                    && f.error().contains("head of empty list");
            System.out.println("[" + n + "-step] " + (isHeadListError ? "HEADLIST_CRASH" : "OK")
                    + " budget=" + result.budgetConsumed()
                    + " error=" + (result instanceof EvalResult.Failure f ? f.error().substring(0, Math.min(80, f.error().length())) : "none"));
        }

        @Test
        void diagnose_recursionDepth() throws Exception {
            for (int n = 0; n <= 5; n++) {
                testWithNSteps(n);
            }
        }

        @Test
        void verifyHas_staticVectors_uplc() throws Exception {
            // Use the SAME static vectors as DirectJava tests to isolate UPLC issues
            var program = compileValidator(MpfRegistryValidator.class).program();

            // Build proof as PlutusData from static vectors
            var proofSteps = new PlutusData[(int)PROOF_845999.size()];
            for (int i = 0; i < (int)PROOF_845999.size(); i++) {
                var step = PROOF_845999.get(i);
                if (step instanceof ProofStep.Branch b) {
                    proofSteps[i] = PlutusData.constr(0,
                            PlutusData.integer(java.math.BigInteger.valueOf(b.skip())),
                            PlutusData.bytes(b.neighbors()));
                }
            }

            var datum = buildDatum(ROOT);
            var proofData = PlutusData.list(proofSteps);
            // VerifyHas = Constr(0, [BData(key), BData(value), ListData(proof)])
            var redeemer = PlutusData.constr(0,
                    PlutusData.bytes(BLOCK_HASH),
                    PlutusData.bytes(BLOCK_BODY),
                    proofData);
            var ref = TestDataBuilder.randomTxOutRef_typed();
            var ctx = spendingContext(ref, datum)
                    .redeemer(redeemer)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            System.out.println("[staticVectors_uplc] " + result);
            if (result instanceof EvalResult.Failure f) {
                System.out.println("[staticVectors_uplc] error: " + f.error());
                System.out.println("[staticVectors_uplc] budget: " + f.budgetConsumed());
            } else {
                logBudget("staticVectors_uplc", result);
            }
            assertSuccess(result);
        }

        @Test
        void verifyHas_evaluatesSuccess() throws Exception {
            // Build trie with MpfTrie and generate proof
            var store = new TestNodeStore();
            var trie = new MpfTrie(store);
            byte[] key1 = "alice".getBytes();
            byte[] val1 = "100".getBytes();
            byte[] key2 = "bob".getBytes();
            byte[] val2 = "200".getBytes();
            trie.put(key1, val1);
            trie.put(key2, val2);
            byte[] trieRoot = trie.getRootHash();

            // Get inclusion proof for alice
            var proof = trie.getProofPlutusData(key1).orElseThrow();

            // Compile validator
            var program = compileValidator(MpfRegistryValidator.class).program();

            // Build context
            var datum = buildDatum(trieRoot);
            var redeemer = buildVerifyHasRedeemer(key1, val1, proof);
            var ref = TestDataBuilder.randomTxOutRef_typed();
            var ctx = spendingContext(ref, datum)
                    .redeemer(redeemer)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("verifyHas_evaluatesSuccess", result);
        }

        @Test
        void verifyHas_wrongValue_fails() throws Exception {
            var store = new TestNodeStore();
            var trie = new MpfTrie(store);
            byte[] key1 = "alice".getBytes();
            byte[] val1 = "100".getBytes();
            trie.put(key1, val1);
            byte[] trieRoot = trie.getRootHash();

            var proof = trie.getProofPlutusData(key1).orElseThrow();

            var program = compileValidator(MpfRegistryValidator.class).program();

            var datum = buildDatum(trieRoot);
            byte[] wrongValue = "999".getBytes();
            var redeemer = buildVerifyHasRedeemer(key1, wrongValue, proof);
            var ref = TestDataBuilder.randomTxOutRef_typed();
            var ctx = spendingContext(ref, datum)
                    .redeemer(redeemer)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertFailure(result);
            logBudget("verifyHas_wrongValue_fails", result);
        }

        @Test
        void verifyMiss_evaluatesSuccess() throws Exception {
            var store = new TestNodeStore();
            var trie = new MpfTrie(store);
            byte[] key1 = "alice".getBytes();
            byte[] val1 = "100".getBytes();
            trie.put(key1, val1);
            byte[] trieRoot = trie.getRootHash();

            // Get proof for a key NOT in the trie
            byte[] missingKey = "charlie".getBytes();
            var proof = trie.getProofPlutusData(missingKey).orElseThrow();

            var program = compileValidator(MpfRegistryValidator.class).program();

            var datum = buildDatum(trieRoot);
            var redeemer = buildVerifyMissRedeemer(missingKey, proof);
            var ref = TestDataBuilder.randomTxOutRef_typed();
            var ctx = spendingContext(ref, datum)
                    .redeemer(redeemer)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("verifyMiss_evaluatesSuccess", result);
        }

        @Test
        void verifyMiss_keyExists_fails() throws Exception {
            var store = new TestNodeStore();
            var trie = new MpfTrie(store);
            byte[] key1 = "alice".getBytes();
            byte[] val1 = "100".getBytes();
            byte[] key2 = "bob".getBytes();
            byte[] val2 = "200".getBytes();
            trie.put(key1, val1);
            trie.put(key2, val2);
            byte[] trieRoot = trie.getRootHash();

            // Get proof for charlie (NOT in trie)
            byte[] missingKey = "charlie".getBytes();
            var proof = trie.getProofPlutusData(missingKey).orElseThrow();

            var program = compileValidator(MpfRegistryValidator.class).program();

            // Try to prove alice is missing, using charlie's miss proof — should fail
            var datum = buildDatum(trieRoot);
            var redeemer = buildVerifyMissRedeemer(key1, proof);
            var ref = TestDataBuilder.randomTxOutRef_typed();
            var ctx = spendingContext(ref, datum)
                    .redeemer(redeemer)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertFailure(result);
            logBudget("verifyMiss_keyExists_fails", result);
        }
    }

    private static void logBudget(String testName, EvalResult result) {
        var budget = result.budgetConsumed();
        System.out.println("[" + testName + "] CPU: " + budget.cpuSteps() + ", Mem: " + budget.memoryUnits());
        System.out.println("[" + testName + "] Traces: " + result.traces());
    }
}
