# Anonymous Data

A commit-reveal validator that allows anonymous data commitment using a blake2b hash of the committer's public key hash and a secret nonce, with later reveal proving ownership.

## Overview

This validator implements a two-phase commit-reveal scheme. In the commit phase, a user mints a token whose asset name is `blake2b_256(pkh || nonce)`, locking it at the script address with arbitrary inline datum. In the reveal phase, the user spends the UTxO by providing the nonce as the redeemer. The validator verifies that one of the transaction signers can reconstruct the committed ID by hashing their public key hash with the provided nonce.

## Protocol Flow

```
1. Commit (MINT)     User computes id = blake2b_256(pkh || nonce).
                     Mints exactly 1 token with asset name = id.
                     Token sent to script address with inline datum
                     containing the committed data.

2. Reveal (SPEND)    User spends the script UTxO with nonce as the
                     redeemer. Validator extracts the token's asset
                     name from the spent UTxO and checks that
                     blake2b_256(signerPkh || nonce) matches it.
```

## Data Types

### Datum / Redeemer

The validator uses untyped `PlutusData` for both datum and redeemer:

| Purpose | Type          | Description                                    |
|---------|---------------|------------------------------------------------|
| Mint    | `PlutusData` (redeemer) | The committed ID (`byte[]` via `unBData`)   |
| Spend   | `Optional<PlutusData>` (datum) | Arbitrary inline datum (committed data) |
| Spend   | `PlutusData` (redeemer) | The secret nonce (`byte[]` via `unBData`)   |

## Validator Logic

- **Mint** (commit phase):
  - Extracts the `id` from the redeemer as a byte array.
  - Checks that exactly 1 token with asset name = `id` is minted under this policy.
  - Verifies an output exists carrying that token with an inline datum.

- **Spend** (reveal phase):
  - Extracts the `nonce` from the redeemer as a byte array.
  - Finds the spent UTxO and extracts the non-ADA token's asset name (the committed ID).
  - Iterates transaction signatories; for each signer, computes `blake2b_256(signerPkh || nonce)`.
  - Returns true if any signer's reconstructed hash matches the committed ID.

## JuLC Features Demonstrated

- `@MultiValidator` with both `Purpose.MINT` and `Purpose.SPEND` entrypoints
- `CryptoLib.blake2b_256()` for on-chain hashing
- `Builtins.appendByteString()` for byte array concatenation
- `Builtins.unBData()` for extracting byte arrays from raw PlutusData
- `ValuesLib.assetOf()` for specific token quantity checks
- `ValuesLib.flatten()` for iterating multi-asset values
- `Builtins.unConstrData()`, `Builtins.headList()`, `Builtins.tailList()` for low-level field extraction
- `OutputDatum` sealed interface with `switch` expression for datum type checks
- `PubKeyHash.hash()` for signatory key extraction
- For-each loop with break + accumulator pattern

## Files

| File | Description |
|------|-------------|
| `onchain/CfAnonymousDataValidator.java` | Validator (on-chain logic) |
| `offchain/AnonymousDataDemo.java` | Off-chain demo (requires Yaci DevKit) |
| `../../test/.../anonymousdata/onchain/CfAnonymousDataValidatorTest.java` | Unit tests |
| `../../test/.../anonymousdata/AnonymousDataIntegrationTest.java` | Integration tests (requires Yaci DevKit) |

## Running

```bash
# Unit tests
./gradlew test --tests "com.example.cftemplates.anonymousdata.onchain.*"

# Integration tests (requires Yaci DevKit)
./gradlew test --tests "com.example.cftemplates.anonymousdata.*IntegrationTest"

# Off-chain demo (requires Yaci DevKit)
./gradlew run -PmainClass=com.example.cftemplates.anonymousdata.offchain.AnonymousDataDemo
```
