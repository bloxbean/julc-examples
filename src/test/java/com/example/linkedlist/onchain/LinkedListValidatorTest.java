package com.example.linkedlist.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.Program;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.testkit.ContractTest;
import com.bloxbean.cardano.julc.testkit.TestDataBuilder;
import com.bloxbean.cardano.julc.vm.EvalResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class LinkedListValidatorTest extends ContractTest {

    // 28-byte script hash (= policy ID for minting)
    static final byte[] SCRIPT_HASH = new byte[]{
            (byte) 0xAA, (byte) 0xBB, (byte) 0xCC, 0x01, 0x02, 0x03, 0x04, 0x05,
            0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D,
            0x0E, 0x0F, 0x10, 0x11, 0x12, 0x13, 0x14, 0x15,
            0x16, 0x17, 0x18, 0x19};

    static final byte[] ROOT_KEY = new byte[]{0x52, 0x4F, 0x4F, 0x54};  // "ROOT"
    static final byte[] PREFIX   = new byte[]{0x4E, 0x4F, 0x44, 0x45};  // "NODE"
    static final BigInteger PREFIX_LEN = BigInteger.valueOf(4);

    static final PlutusData UNIT_DATA = PlutusData.constr(0);

    @BeforeAll
    static void setup() {
        initCrypto();
    }

    // --- Address helpers ---

    static Address scriptAddress() {
        return new Address(
                new Credential.ScriptCredential(new ScriptHash(SCRIPT_HASH)),
                Optional.empty());
    }

    static Address pubKeyAddress(byte[] pkh) {
        return new Address(
                new Credential.PubKeyCredential(new PubKeyHash(pkh)),
                Optional.empty());
    }

    // --- Datum/Redeemer construction ---

    // ListElement = Constr(0, [userData, BData(nextKey)])
    static PlutusData listElementDatum(PlutusData userData, byte[] nextKey) {
        return PlutusData.constr(0, userData, PlutusData.bytes(nextKey));
    }

    // Shorthand: unit userData, given nextKey
    static PlutusData listElementDatum(byte[] nextKey) {
        return listElementDatum(UNIT_DATA, nextKey);
    }

    static byte[] emptyKey() {
        return new byte[0];
    }

    // Build token name: prefix + key
    static byte[] nodeTokenName(byte[] key) {
        byte[] result = new byte[PREFIX.length + key.length];
        System.arraycopy(PREFIX, 0, result, 0, PREFIX.length);
        System.arraycopy(key, 0, result, PREFIX.length, key.length);
        return result;
    }

    // ListAction redeemers
    static PlutusData initListRedeemer(int rootOutputIndex) {
        return PlutusData.constr(0, PlutusData.integer(BigInteger.valueOf(rootOutputIndex)));
    }

    static PlutusData deinitListRedeemer(int rootInputIndex) {
        return PlutusData.constr(1, PlutusData.integer(BigInteger.valueOf(rootInputIndex)));
    }

    static PlutusData insertNodeRedeemer(int anchorInputIndex, int contAnchorOutputIndex,
                                         int newElementOutputIndex) {
        return PlutusData.constr(2,
                PlutusData.integer(BigInteger.valueOf(anchorInputIndex)),
                PlutusData.integer(BigInteger.valueOf(contAnchorOutputIndex)),
                PlutusData.integer(BigInteger.valueOf(newElementOutputIndex)));
    }

    static PlutusData removeNodeRedeemer(int anchorInputIndex, int removingInputIndex,
                                         int contAnchorOutputIndex) {
        return PlutusData.constr(3,
                PlutusData.integer(BigInteger.valueOf(anchorInputIndex)),
                PlutusData.integer(BigInteger.valueOf(removingInputIndex)),
                PlutusData.integer(BigInteger.valueOf(contAnchorOutputIndex)));
    }

    // --- Compile helper ---

    Program compileAndApply() {
        var result = compileValidator(LinkedListValidator.class);
        assertFalse(result.hasErrors(), "Compilation failed: " + result);
        return result.program().applyParams(
                PlutusData.bytes(ROOT_KEY),
                PlutusData.bytes(PREFIX),
                PlutusData.integer(PREFIX_LEN));
    }

    // --- Value helpers ---

    static PolicyId policyId() {
        return new PolicyId(SCRIPT_HASH);
    }

    static Value lovelace(long amount) {
        return Value.lovelace(BigInteger.valueOf(amount));
    }

    static Value withRootNft(Value base) {
        return base.merge(Value.singleton(policyId(), new TokenName(ROOT_KEY), BigInteger.ONE));
    }

    static Value withNodeNft(Value base, byte[] key) {
        return base.merge(Value.singleton(policyId(), new TokenName(nodeTokenName(key)), BigInteger.ONE));
    }

    // --- Budget logging ---

    static void logBudget(String testName, EvalResult result) {
        var budget = result.budgetConsumed();
        System.out.println("[" + testName + "] CPU: " + budget.cpuSteps() + ", Mem: " + budget.memoryUnits());
    }

    // =======================================================================
    // TESTS
    // =======================================================================

    @Test
    void compilesSuccessfully() {
        var result = compileValidator(LinkedListValidator.class);
        assertFalse(result.hasErrors(), "Compilation failed: " + result);
        System.out.println("Script size: " + result.scriptSizeFormatted());
    }

    @Nested
    class InitTests {

        @Test
        void init_emptyList_passes() throws Exception {
            var program = compileAndApply();

            var rootDatum = listElementDatum(emptyKey());
            var rootOutput = new TxOut(scriptAddress(),
                    withRootNft(lovelace(2_000_000)),
                    new OutputDatum.OutputDatumInline(rootDatum),
                    Optional.empty());

            var mintValue = Value.singleton(policyId(), new TokenName(ROOT_KEY), BigInteger.ONE);
            var redeemer = initListRedeemer(0);

            var ctx = mintingContext(policyId())
                    .redeemer(redeemer)
                    .output(rootOutput)
                    .mint(mintValue)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("init_emptyList_passes", result);
        }

        @Test
        void init_nonEmptyLink_fails() throws Exception {
            var program = compileAndApply();

            // Root datum with non-empty nextKey — should fail
            byte[] keyB = new byte[]{0x42};
            var rootDatum = listElementDatum(keyB);
            var rootOutput = new TxOut(scriptAddress(),
                    withRootNft(lovelace(2_000_000)),
                    new OutputDatum.OutputDatumInline(rootDatum),
                    Optional.empty());

            var mintValue = Value.singleton(policyId(), new TokenName(ROOT_KEY), BigInteger.ONE);
            var redeemer = initListRedeemer(0);

            var ctx = mintingContext(policyId())
                    .redeemer(redeemer)
                    .output(rootOutput)
                    .mint(mintValue)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertFailure(result);
        }
    }

    @Nested
    class InsertTests {

        @Test
        void insertFirst_afterRoot_passes() throws Exception {
            var program = compileAndApply();

            byte[] keyB = new byte[]{0x42}; // "B"

            // Root input: root→(empty)
            var rootRef = TestDataBuilder.randomTxOutRef_typed();
            var rootInputValue = withRootNft(lovelace(2_000_000));
            var rootDatum = listElementDatum(emptyKey());
            var rootInput = new TxInInfo(rootRef,
                    new TxOut(scriptAddress(), rootInputValue,
                            new OutputDatum.OutputDatumInline(rootDatum),
                            Optional.empty()));

            // Continuing root output: root→B
            var contRootDatum = listElementDatum(UNIT_DATA, keyB);
            var contRootOutput = new TxOut(scriptAddress(),
                    withRootNft(lovelace(2_000_000)),
                    new OutputDatum.OutputDatumInline(contRootDatum),
                    Optional.empty());

            // New element output: B→(empty)
            var newElemDatum = listElementDatum(emptyKey());
            var newElemOutput = new TxOut(scriptAddress(),
                    withNodeNft(lovelace(2_000_000), keyB),
                    new OutputDatum.OutputDatumInline(newElemDatum),
                    Optional.empty());

            // Mint the new node NFT
            var mintValue = Value.singleton(policyId(), new TokenName(nodeTokenName(keyB)), BigInteger.ONE);
            var redeemer = insertNodeRedeemer(0, 0, 1);

            var ctx = mintingContext(policyId())
                    .redeemer(redeemer)
                    .input(rootInput)
                    .output(contRootOutput)
                    .output(newElemOutput)
                    .mint(mintValue)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("insertFirst_afterRoot_passes", result);
        }

        @Test
        void insertOrdered_passes() throws Exception {
            var program = compileAndApply();

            byte[] keyA = new byte[]{0x41}; // "A"
            byte[] keyC = new byte[]{0x43}; // "C"

            // Current state: root→C
            // Insert "A": result should be root→A→C

            // Root input: root→C
            var rootRef = TestDataBuilder.randomTxOutRef_typed();
            var rootDatum = listElementDatum(UNIT_DATA, keyC);
            var rootInput = new TxInInfo(rootRef,
                    new TxOut(scriptAddress(), withRootNft(lovelace(2_000_000)),
                            new OutputDatum.OutputDatumInline(rootDatum),
                            Optional.empty()));

            // Continuing root output: root→A
            var contRootDatum = listElementDatum(UNIT_DATA, keyA);
            var contRootOutput = new TxOut(scriptAddress(),
                    withRootNft(lovelace(2_000_000)),
                    new OutputDatum.OutputDatumInline(contRootDatum),
                    Optional.empty());

            // New element output: A→C
            var newElemDatum = listElementDatum(UNIT_DATA, keyC);
            var newElemOutput = new TxOut(scriptAddress(),
                    withNodeNft(lovelace(2_000_000), keyA),
                    new OutputDatum.OutputDatumInline(newElemDatum),
                    Optional.empty());

            var mintValue = Value.singleton(policyId(), new TokenName(nodeTokenName(keyA)), BigInteger.ONE);
            var redeemer = insertNodeRedeemer(0, 0, 1);

            var ctx = mintingContext(policyId())
                    .redeemer(redeemer)
                    .input(rootInput)
                    .output(contRootOutput)
                    .output(newElemOutput)
                    .mint(mintValue)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("insertOrdered_passes", result);
        }

        @Test
        void insertWrongOrder_fails() throws Exception {
            var program = compileAndApply();

            byte[] keyA = new byte[]{0x41}; // "A"

            // Current state: root→A
            // Try inserting "A" again (duplicate key) — should fail ordering check

            var rootRef = TestDataBuilder.randomTxOutRef_typed();
            var rootDatum = listElementDatum(UNIT_DATA, keyA);
            var rootInput = new TxInInfo(rootRef,
                    new TxOut(scriptAddress(), withRootNft(lovelace(2_000_000)),
                            new OutputDatum.OutputDatumInline(rootDatum),
                            Optional.empty()));

            // Continuing root: root→A (same — pointing to new duplicate)
            var contRootDatum = listElementDatum(UNIT_DATA, keyA);
            var contRootOutput = new TxOut(scriptAddress(),
                    withRootNft(lovelace(2_000_000)),
                    new OutputDatum.OutputDatumInline(contRootDatum),
                    Optional.empty());

            // New element: A→A (old nextKey was "A")
            var newElemDatum = listElementDatum(UNIT_DATA, keyA);
            var newElemOutput = new TxOut(scriptAddress(),
                    withNodeNft(lovelace(2_000_000), keyA),
                    new OutputDatum.OutputDatumInline(newElemDatum),
                    Optional.empty());

            var mintValue = Value.singleton(policyId(), new TokenName(nodeTokenName(keyA)), BigInteger.ONE);
            var redeemer = insertNodeRedeemer(0, 0, 1);

            var ctx = mintingContext(policyId())
                    .redeemer(redeemer)
                    .input(rootInput)
                    .output(contRootOutput)
                    .output(newElemOutput)
                    .mint(mintValue)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertFailure(result);
        }

        @Test
        void insertAnchorDataChanged_fails() throws Exception {
            var program = compileAndApply();

            byte[] keyB = new byte[]{0x42};

            var rootRef = TestDataBuilder.randomTxOutRef_typed();
            var rootDatum = listElementDatum(UNIT_DATA, emptyKey());
            var rootInput = new TxInInfo(rootRef,
                    new TxOut(scriptAddress(), withRootNft(lovelace(2_000_000)),
                            new OutputDatum.OutputDatumInline(rootDatum),
                            Optional.empty()));

            // Continuing root with CHANGED userData — should fail
            PlutusData changedData = PlutusData.constr(1);
            var contRootDatum = listElementDatum(changedData, keyB);
            var contRootOutput = new TxOut(scriptAddress(),
                    withRootNft(lovelace(2_000_000)),
                    new OutputDatum.OutputDatumInline(contRootDatum),
                    Optional.empty());

            var newElemDatum = listElementDatum(emptyKey());
            var newElemOutput = new TxOut(scriptAddress(),
                    withNodeNft(lovelace(2_000_000), keyB),
                    new OutputDatum.OutputDatumInline(newElemDatum),
                    Optional.empty());

            var mintValue = Value.singleton(policyId(), new TokenName(nodeTokenName(keyB)), BigInteger.ONE);
            var redeemer = insertNodeRedeemer(0, 0, 1);

            var ctx = mintingContext(policyId())
                    .redeemer(redeemer)
                    .input(rootInput)
                    .output(contRootOutput)
                    .output(newElemOutput)
                    .mint(mintValue)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertFailure(result);
        }
    }

    @Nested
    class RemoveTests {

        @Test
        void remove_singleNode_passes() throws Exception {
            var program = compileAndApply();

            byte[] keyB = new byte[]{0x42};

            // Current state: root→B→(empty)
            // Remove B: root→(empty)

            // Root input
            var rootRef = TestDataBuilder.randomTxOutRef_typed();
            var rootDatum = listElementDatum(UNIT_DATA, keyB);
            var rootInput = new TxInInfo(rootRef,
                    new TxOut(scriptAddress(), withRootNft(lovelace(2_000_000)),
                            new OutputDatum.OutputDatumInline(rootDatum),
                            Optional.empty()));

            // Removing input: B→(empty)
            var removingRef = TestDataBuilder.randomTxOutRef_typed();
            var removingDatum = listElementDatum(UNIT_DATA, emptyKey());
            var removingInput = new TxInInfo(removingRef,
                    new TxOut(scriptAddress(), withNodeNft(lovelace(2_000_000), keyB),
                            new OutputDatum.OutputDatumInline(removingDatum),
                            Optional.empty()));

            // Continuing root output: root→(empty)
            var contRootDatum = listElementDatum(UNIT_DATA, emptyKey());
            var contRootOutput = new TxOut(scriptAddress(),
                    withRootNft(lovelace(2_000_000)),
                    new OutputDatum.OutputDatumInline(contRootDatum),
                    Optional.empty());

            // Burn the removing node's NFT
            var mintValue = Value.singleton(policyId(), new TokenName(nodeTokenName(keyB)),
                    BigInteger.ONE.negate());
            var redeemer = removeNodeRedeemer(0, 1, 0);

            var ctx = mintingContext(policyId())
                    .redeemer(redeemer)
                    .input(rootInput)
                    .input(removingInput)
                    .output(contRootOutput)
                    .mint(mintValue)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("remove_singleNode_passes", result);
        }

        @Test
        void remove_middleNode_passes() throws Exception {
            var program = compileAndApply();

            byte[] keyA = new byte[]{0x41};
            byte[] keyB = new byte[]{0x42};
            byte[] keyC = new byte[]{0x43};

            // Current state: root→A→B→C→(empty)
            // Remove B: A→C (anchor=A, removing=B)

            // Anchor input: A→B
            var anchorRef = TestDataBuilder.randomTxOutRef_typed();
            var anchorDatum = listElementDatum(UNIT_DATA, keyB);
            var anchorInput = new TxInInfo(anchorRef,
                    new TxOut(scriptAddress(), withNodeNft(lovelace(2_000_000), keyA),
                            new OutputDatum.OutputDatumInline(anchorDatum),
                            Optional.empty()));

            // Removing input: B→C
            var removingRef = TestDataBuilder.randomTxOutRef_typed();
            var removingDatum = listElementDatum(UNIT_DATA, keyC);
            var removingInput = new TxInInfo(removingRef,
                    new TxOut(scriptAddress(), withNodeNft(lovelace(2_000_000), keyB),
                            new OutputDatum.OutputDatumInline(removingDatum),
                            Optional.empty()));

            // Continuing anchor output: A→C
            var contAnchorDatum = listElementDatum(UNIT_DATA, keyC);
            var contAnchorOutput = new TxOut(scriptAddress(),
                    withNodeNft(lovelace(2_000_000), keyA),
                    new OutputDatum.OutputDatumInline(contAnchorDatum),
                    Optional.empty());

            var mintValue = Value.singleton(policyId(), new TokenName(nodeTokenName(keyB)),
                    BigInteger.ONE.negate());
            var redeemer = removeNodeRedeemer(0, 1, 0);

            var ctx = mintingContext(policyId())
                    .redeemer(redeemer)
                    .input(anchorInput)
                    .input(removingInput)
                    .output(contAnchorOutput)
                    .mint(mintValue)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("remove_middleNode_passes", result);
        }
    }

    @Nested
    class DeinitTests {

        @Test
        void deinit_emptyList_passes() throws Exception {
            var program = compileAndApply();

            // Root input with empty nextKey
            var rootRef = TestDataBuilder.randomTxOutRef_typed();
            var rootDatum = listElementDatum(emptyKey());
            var rootInput = new TxInInfo(rootRef,
                    new TxOut(scriptAddress(), withRootNft(lovelace(2_000_000)),
                            new OutputDatum.OutputDatumInline(rootDatum),
                            Optional.empty()));

            // Burn root NFT
            var mintValue = Value.singleton(policyId(), new TokenName(ROOT_KEY),
                    BigInteger.ONE.negate());
            var redeemer = deinitListRedeemer(0);

            var ctx = mintingContext(policyId())
                    .redeemer(redeemer)
                    .input(rootInput)
                    .mint(mintValue)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("deinit_emptyList_passes", result);
        }

        @Test
        void deinit_nonEmpty_fails() throws Exception {
            var program = compileAndApply();

            byte[] keyB = new byte[]{0x42};

            // Root input with non-empty nextKey (list not empty) — should fail
            var rootRef = TestDataBuilder.randomTxOutRef_typed();
            var rootDatum = listElementDatum(keyB);
            var rootInput = new TxInInfo(rootRef,
                    new TxOut(scriptAddress(), withRootNft(lovelace(2_000_000)),
                            new OutputDatum.OutputDatumInline(rootDatum),
                            Optional.empty()));

            var mintValue = Value.singleton(policyId(), new TokenName(ROOT_KEY),
                    BigInteger.ONE.negate());
            var redeemer = deinitListRedeemer(0);

            var ctx = mintingContext(policyId())
                    .redeemer(redeemer)
                    .input(rootInput)
                    .mint(mintValue)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertFailure(result);
        }
    }

    @Nested
    class SpendTests {

        @Test
        void spend_withMint_passes() throws Exception {
            var program = compileAndApply();

            var spentRef = TestDataBuilder.randomTxOutRef_typed();
            var datum = listElementDatum(emptyKey());
            var spentInput = new TxInInfo(spentRef,
                    new TxOut(scriptAddress(), withRootNft(lovelace(2_000_000)),
                            new OutputDatum.OutputDatumInline(datum),
                            Optional.empty()));

            // Some policy tokens in mint (spend validator just checks containsPolicy)
            var mintValue = Value.singleton(policyId(), new TokenName(ROOT_KEY),
                    BigInteger.ONE.negate());

            var ctx = spendingContext(spentRef, datum)
                    .redeemer(PlutusData.constr(0))
                    .input(spentInput)
                    .mint(mintValue)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("spend_withMint_passes", result);
        }

        @Test
        void spend_noMint_fails() throws Exception {
            var program = compileAndApply();

            var spentRef = TestDataBuilder.randomTxOutRef_typed();
            var datum = listElementDatum(emptyKey());
            var spentInput = new TxInInfo(spentRef,
                    new TxOut(scriptAddress(), withRootNft(lovelace(2_000_000)),
                            new OutputDatum.OutputDatumInline(datum),
                            Optional.empty()));

            // No policy tokens in mint — spend guard should fail
            var ctx = spendingContext(spentRef, datum)
                    .redeemer(PlutusData.constr(0))
                    .input(spentInput)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertFailure(result);
        }
    }
}
