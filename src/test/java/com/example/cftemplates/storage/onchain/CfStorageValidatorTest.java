package com.example.cftemplates.storage.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.testkit.ContractTest;
import com.bloxbean.cardano.julc.testkit.TestDataBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class CfStorageValidatorTest extends ContractTest {

    // Seed UTXO parameters (32-byte txHash, index)
    static final byte[] SEED_TX_HASH = new byte[]{10, 20, 30, 40, 50, 60, 70, 80, 90, 100,
            110, 120, 1, 2, 3, 4, 5, 6, 7, 8,
            9, 11, 12, 13, 14, 15, 16, 17, 18, 19,
            21, 22};
    static final BigInteger SEED_INDEX = BigInteger.ZERO;

    // Script hash for the storage script address (same as policyId in @MultiValidator)
    static final byte[] SCRIPT_HASH = new byte[]{(byte) 0xA1, (byte) 0xA2, (byte) 0xA3, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0};

    static final byte[] SNAPSHOT_ID = "snapshot-2024-01-15".getBytes();
    static final byte[] COMMITMENT_HASH = new byte[]{
            1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
            17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32};
    static final BigInteger PUBLISHED_AT = BigInteger.valueOf(1_700_000_000_000L);

    @BeforeAll
    static void setup() {
        initCrypto();
    }

    static Address scriptAddress() {
        return new Address(
                new Credential.ScriptCredential(new ScriptHash(SCRIPT_HASH)),
                Optional.empty());
    }

    static byte[] sha256(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    // RegistryDatum = Constr(0, [snapshotId, snapshotType, commitmentHash, publishedAt])
    // SnapshotType: Daily = Constr(0, []), Monthly = Constr(1, [])
    static PlutusData registryDatum(byte[] snapshotId, PlutusData snapshotType,
                                    byte[] commitmentHash, BigInteger publishedAt) {
        return PlutusData.constr(0,
                PlutusData.bytes(snapshotId), snapshotType,
                PlutusData.bytes(commitmentHash), PlutusData.integer(publishedAt));
    }

    // StorageMintRedeemer = Constr(0, [snapshotId, snapshotType, commitmentHash])
    static PlutusData storageMintRedeemer(byte[] snapshotId, PlutusData snapshotType, byte[] commitmentHash) {
        return PlutusData.constr(0,
                PlutusData.bytes(snapshotId), snapshotType, PlutusData.bytes(commitmentHash));
    }

    @Test
    void compilesSuccessfully() throws Exception {
        var compiled = compileValidator(CfStorageValidator.class);
        assertFalse(compiled.hasErrors(), "Should compile: " + compiled);
        System.out.println("Script size: " + compiled.scriptSizeFormatted());
    }

    @Nested
    class SpendTests {

        @Test
        void spend_alwaysFails() throws Exception {
            var compiled = compileValidator(CfStorageValidator.class);
            var program = compiled.program()
                    .applyParams(PlutusData.bytes(SEED_TX_HASH), PlutusData.integer(SEED_INDEX));

            var datum = PlutusData.constr(0);
            var redeemer = PlutusData.constr(0);

            var ref = TestDataBuilder.randomTxOutRef_typed();
            var ctx = spendingContext(ref, datum)
                    .redeemer(redeemer)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertFailure(result);
        }
    }

    @Nested
    class MintTests {

        @Test
        void mint_validSnapshot_passes() throws Exception {
            var compiled = compileValidator(CfStorageValidator.class);
            var program = compiled.program()
                    .applyParams(PlutusData.bytes(SEED_TX_HASH), PlutusData.integer(SEED_INDEX));

            // Daily = Constr(0, [])
            var dailyType = PlutusData.constr(0);
            var redeemer = storageMintRedeemer(SNAPSHOT_ID, dailyType, COMMITMENT_HASH);

            var policyId = new PolicyId(SCRIPT_HASH);

            // Compute expected asset name = sha2_256(snapshotId)
            byte[] expectedAssetName = sha256(SNAPSHOT_ID);

            // Mint 1 token with correct name
            var mintValue = Value.singleton(policyId, new TokenName(expectedAssetName), BigInteger.ONE);

            // Seed UTXO as input
            var seedRef = new TxOutRef(new TxId(SEED_TX_HASH), SEED_INDEX);
            var seedInput = new TxInInfo(seedRef,
                    new TxOut(new Address(new Credential.PubKeyCredential(
                            new PubKeyHash(new byte[28])), Optional.empty()),
                            Value.lovelace(BigInteger.valueOf(5_000_000)),
                            new OutputDatum.NoOutputDatum(), Optional.empty()));

            // Output to script address with NFT + matching inline datum
            var datum = registryDatum(SNAPSHOT_ID, dailyType, COMMITMENT_HASH, PUBLISHED_AT);
            var outputValue = Value.lovelace(BigInteger.valueOf(2_000_000))
                    .merge(Value.singleton(policyId, new TokenName(expectedAssetName), BigInteger.ONE));
            var nftOutput = new TxOut(scriptAddress(),
                    outputValue,
                    new OutputDatum.OutputDatumInline(datum),
                    Optional.empty());

            var ctx = mintingContext(policyId)
                    .redeemer(redeemer)
                    .mint(mintValue)
                    .input(seedInput)
                    .output(nftOutput)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertSuccess(result);
            logBudget("mint_validSnapshot_passes", result);
        }

        @Test
        void mint_wrongHashLength_fails() throws Exception {
            var compiled = compileValidator(CfStorageValidator.class);
            var program = compiled.program()
                    .applyParams(PlutusData.bytes(SEED_TX_HASH), PlutusData.integer(SEED_INDEX));

            var dailyType = PlutusData.constr(0);

            // Wrong commitment hash: only 16 bytes instead of 32
            byte[] wrongHash = new byte[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};
            var redeemer = storageMintRedeemer(SNAPSHOT_ID, dailyType, wrongHash);

            var policyId = new PolicyId(SCRIPT_HASH);
            byte[] expectedAssetName = sha256(SNAPSHOT_ID);

            var mintValue = Value.singleton(policyId, new TokenName(expectedAssetName), BigInteger.ONE);

            var seedRef = new TxOutRef(new TxId(SEED_TX_HASH), SEED_INDEX);
            var seedInput = new TxInInfo(seedRef,
                    new TxOut(new Address(new Credential.PubKeyCredential(
                            new PubKeyHash(new byte[28])), Optional.empty()),
                            Value.lovelace(BigInteger.valueOf(5_000_000)),
                            new OutputDatum.NoOutputDatum(), Optional.empty()));

            // Output with wrong hash in datum
            var datum = registryDatum(SNAPSHOT_ID, dailyType, wrongHash, PUBLISHED_AT);
            var outputValue = Value.lovelace(BigInteger.valueOf(2_000_000))
                    .merge(Value.singleton(policyId, new TokenName(expectedAssetName), BigInteger.ONE));
            var nftOutput = new TxOut(scriptAddress(),
                    outputValue,
                    new OutputDatum.OutputDatumInline(datum),
                    Optional.empty());

            var ctx = mintingContext(policyId)
                    .redeemer(redeemer)
                    .mint(mintValue)
                    .input(seedInput)
                    .output(nftOutput)
                    .buildPlutusData();

            var result = evaluate(program, ctx);
            assertFailure(result);
        }
    }

    private static void logBudget(String testName, com.bloxbean.cardano.julc.vm.EvalResult result) {
        var budget = result.budgetConsumed();
        System.out.println("[" + testName + "] CPU: " + budget.cpuSteps() + ", Mem: " + budget.memoryUnits());
    }
}
