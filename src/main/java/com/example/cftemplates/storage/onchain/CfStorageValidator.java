package com.example.cftemplates.storage.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.MultiValidator;
import com.bloxbean.cardano.julc.stdlib.annotation.Param;
import com.bloxbean.cardano.julc.stdlib.annotation.Purpose;
import com.bloxbean.cardano.julc.stdlib.lib.*;

import java.math.BigInteger;
import java.util.Optional;

/**
 * Storage validator — immutable audit snapshot commitments.
 * <p>
 * SPEND: Always fails. UTxOs locked here are immutable and cannot be spent.
 * MINT: One-shot NFT minting policy. Each snapshot gets a unique NFT marker.
 *   - Consumes seed UTxO (ensures one-shot)
 *   - Mints 1 token with asset name = sha2_256(snapshotId)
 *   - Token sent to own script address with matching inline datum
 *   - Validates commitment hash (32 bytes) and non-empty snapshot ID
 * <p>
 * Based on: cardano-template-and-ecosystem-monitoring/storage
 * <p>
 * NOTE: Combines what were separate Aiken scripts into a single JuLC @MultiValidator.
 * Architecturally different but functionally equivalent.
 */
@MultiValidator
public class CfStorageValidator {

    @Param static byte[] seedTxHash;
    @Param static BigInteger seedIndex;

    // Snapshot type: Daily = tag 0, Monthly = tag 1
    public sealed interface SnapshotType permits Daily, Monthly {}
    public record Daily() implements SnapshotType {}
    public record Monthly() implements SnapshotType {}

    public record RegistryDatum(byte[] snapshotId, SnapshotType snapshotType,
                         byte[] commitmentHash, BigInteger publishedAt) {}

    public record StorageMintRedeemer(byte[] snapshotId, SnapshotType snapshotType,
                               byte[] commitmentHash) {}

    @Entrypoint(purpose = Purpose.SPEND)
    public static boolean spend(Optional<PlutusData> datum, PlutusData redeemer, ScriptContext ctx) {
        // Storage UTxOs are immutable — always fail
        return false;
    }

    @Entrypoint(purpose = Purpose.MINT)
    public static boolean mint(StorageMintRedeemer redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        ScriptInfo.MintingScript mintInfo = (ScriptInfo.MintingScript) ctx.scriptInfo();
        byte[] policyBytes = (byte[])(Object) mintInfo.policyId();

        // Rule 1: Must consume the seed UTxO (ensures one-shot)
        boolean consumesSeed = checkSeedConsumed(txInfo.inputs());

        // Rule 2: Asset name = sha2_256(snapshotId)
        byte[] expectedAssetName = CryptoLib.sha2_256(redeemer.snapshotId());

        // Rule 3: Exactly 1 token minted with correct name
        BigInteger mintedQty = ValuesLib.assetOf(txInfo.mint(), policyBytes, expectedAssetName);
        boolean validMint = mintedQty.compareTo(BigInteger.ONE) == 0;

        // Rule 4: Token sent to own script address with valid datum
        boolean validOutput = checkOutputValid(txInfo.outputs(), policyBytes,
                expectedAssetName, redeemer);

        return consumesSeed && validMint && validOutput;
    }

    static boolean checkSeedConsumed(JulcList<TxInInfo> inputs) {
        boolean found = false;
        for (var input : inputs) {
            byte[] txHash = (byte[])(Object) input.outRef().txId();
            BigInteger idx = input.outRef().index();
            if (txHash.equals(seedTxHash) && idx.compareTo(seedIndex) == 0) {
                found = true;
                break;
            }
        }
        return found;
    }

    static boolean checkOutputValid(JulcList<TxOut> outputs, byte[] policyBytes,
                                    byte[] assetName, StorageMintRedeemer redeemer) {
        boolean found = false;
        for (var output : outputs) {
            byte[] credHash = AddressLib.credentialHash(output.address());
            if (credHash.equals(policyBytes)) {
                // Check output contains the NFT
                BigInteger nftQty = ValuesLib.assetOf(output.value(), policyBytes, assetName);
                if (nftQty.compareTo(BigInteger.ONE) == 0) {
                    // Check inline datum
                    PlutusData datumData = OutputLib.getInlineDatum(output);
                    RegistryDatum rd = (RegistryDatum)(Object) datumData;
                    // Datum must match redeemer
                    boolean idMatch = rd.snapshotId().equals(redeemer.snapshotId());
                    boolean typeMatch = Builtins.equalsData(rd.snapshotType(), redeemer.snapshotType());
                    boolean hashMatch = rd.commitmentHash().equals(redeemer.commitmentHash());
                    // Validate: commitment hash = 32 bytes, snapshot ID non-empty
                    long hashLen = Builtins.lengthOfByteString(rd.commitmentHash());
                    boolean validHash = hashLen == 32;
                    long idLen = Builtins.lengthOfByteString(rd.snapshotId());
                    boolean validId = idLen > 0;

                    if (idMatch && typeMatch && hashMatch && validHash && validId) {
                        found = true;
                        break;
                    }
                }
            }
        }
        return found;
    }

}
