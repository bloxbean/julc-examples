# Storage

A parameterized one-shot minting validator for immutable audit snapshot commitments, where each snapshot is anchored on-chain as an NFT with a registry datum that can never be spent.

## Overview

This validator implements an immutable on-chain storage pattern for audit data. A one-shot NFT is minted by consuming a specific seed UTxO (ensuring uniqueness), with the asset name derived from `sha2_256(snapshotId)`. The minted token is locked at the script address alongside a `RegistryDatum` containing the snapshot ID, type, commitment hash, and publication timestamp. The spend entrypoint always returns false, making these UTxOs permanently immutable.

## Protocol Flow

```
1. Mint Snapshot     Consume the seed UTxO (one-shot guarantee).
   (MINT)            Mint 1 NFT with asset name = sha2_256(snapshotId).
                     Lock NFT at script address with RegistryDatum
                     containing snapshot metadata.

2. Spend             Always fails. UTxOs at this script address are
   (SPEND)           permanently immutable and cannot be consumed.
```

## Data Types

### @Param Fields

| Field        | Type         | Description                           |
|--------------|--------------|---------------------------------------|
| `seedTxHash` | `byte[]`     | Transaction hash of the seed UTxO     |
| `seedIndex`  | `BigInteger` | Output index of the seed UTxO         |

### Records

| Record               | Fields                                                                                |
|----------------------|---------------------------------------------------------------------------------------|
| `RegistryDatum`      | `snapshotId: byte[]`, `snapshotType: SnapshotType`, `commitmentHash: byte[]`, `publishedAt: BigInteger` |
| `StorageMintRedeemer` | `snapshotId: byte[]`, `snapshotType: SnapshotType`, `commitmentHash: byte[]`         |

### Sealed Interface: `SnapshotType`

| Variant   | Tag | Fields | Description       |
|-----------|-----|--------|-------------------|
| `Daily`   | 0   | (none) | Daily snapshot    |
| `Monthly` | 1   | (none) | Monthly snapshot  |

## Validator Logic

- **Spend** (always fails):
  - Unconditionally returns `false`. Storage UTxOs are immutable.

- **Mint** (creates a snapshot NFT):
  - **Rule 1**: The seed UTxO (matching `seedTxHash` and `seedIndex`) must be consumed as a transaction input (one-shot guarantee).
  - **Rule 2**: The expected asset name is computed as `sha2_256(redeemer.snapshotId)`.
  - **Rule 3**: Exactly 1 token with that asset name must be minted under this policy.
  - **Rule 4**: An output at the script address must carry the NFT with a valid inline datum where:
    - `snapshotId` matches the redeemer.
    - `snapshotType` matches the redeemer.
    - `commitmentHash` matches the redeemer and is exactly 32 bytes.
    - `snapshotId` is non-empty.

## JuLC Features Demonstrated

- `@MultiValidator` with both `Purpose.MINT` and `Purpose.SPEND` entrypoints
- `@Param` for script parameterization (seedTxHash, seedIndex)
- Sealed interface (`SnapshotType`) for typed enum variants
- Record types for structured datum (`RegistryDatum`) and redeemer (`StorageMintRedeemer`)
- `CryptoLib.sha2_256()` for on-chain asset name derivation
- `ValuesLib.assetOf()` for minted token quantity checks
- `OutputLib.getInlineDatum()` for inline datum extraction
- `AddressLib.credentialHash()` for script address matching
- `Builtins.equalsData()` for comparing sealed interface variants
- `Builtins.lengthOfByteString()` for byte array length validation
- For-each loop with break + accumulator pattern

## Files

| File | Description |
|------|-------------|
| `onchain/CfStorageValidator.java` | Validator (on-chain logic) |
| `offchain/StorageDemo.java` | Off-chain demo (requires Yaci DevKit) |
| `../../test/.../storage/onchain/CfStorageValidatorTest.java` | Unit tests |
| `../../test/.../storage/StorageIntegrationTest.java` | Integration tests (requires Yaci DevKit) |

## Running

```bash
# Unit tests
./gradlew test --tests "com.example.cftemplates.storage.onchain.*"

# Integration tests (requires Yaci DevKit)
./gradlew test --tests "com.example.cftemplates.storage.*IntegrationTest"

# Off-chain demo (requires Yaci DevKit)
./gradlew run -PmainClass=com.example.cftemplates.storage.offchain.StorageDemo
```
