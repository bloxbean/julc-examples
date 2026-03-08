# Hash Time-Locked Contract

A spending validator where funds can be claimed by revealing a secret (preimage of a SHA-256 hash) before an expiration time, or reclaimed by the owner after expiration.

## Overview

This validator implements the HTLC pattern commonly used in cross-chain atomic swaps and payment channels. The script is parameterized with a SHA-256 hash of a secret, an expiration timestamp, and the owner's PKH. Before expiration, anyone who knows the secret preimage can claim the funds by providing it as the redeemer. After expiration, only the owner can withdraw the funds. This creates a trustless conditional payment mechanism.

## Protocol Flow

```
1. Deploy        Parameterize the validator with the secret hash,
                 expiration time, and owner PKH.

2. Lock          Owner sends ADA to the script address.

3. Claim         Either:
                 a) Claimer reveals the secret preimage before
                    expiration (Guess redeemer). The validator
                    hashes the answer and compares to secretHash.
                 b) Owner withdraws after expiration (Withdraw
                    redeemer). Requires owner's signature and
                    validity interval lower bound >= expiration.

4. Validation    Script verifies hash match + time, or signature
                 + time, depending on the redeemer variant.
```

## Data Types

### @Param Fields

| Field        | Type         | Description                                 |
|--------------|--------------|---------------------------------------------|
| `secretHash` | `byte[]`     | SHA-256 hash of the secret preimage         |
| `expiration` | `BigInteger` | POSIX timestamp (ms) when the lock expires  |
| `owner`      | `byte[]`     | Owner's public key hash                     |

### HtlcAction (sealed interface redeemer)

| Variant    | Fields          | Description                              |
|------------|-----------------|------------------------------------------|
| `Guess`    | `byte[] answer` | Claim by revealing the secret preimage   |
| `Withdraw` | (none)          | Owner reclaims funds after expiration    |

## Validator Logic

- **Guess**: Computes `CryptoLib.sha2_256(answer)` and checks it equals `secretHash`. Also verifies the transaction's upper bound (`IntervalLib.finiteUpperBound()`) is at or before `expiration`, ensuring the guess is submitted before the lock expires.
- **Withdraw**: Checks that the owner signed the transaction (`ContextsLib.signedBy()`) AND that the transaction's lower bound (`IntervalLib.finiteLowerBound()`) is at or after `expiration`, ensuring withdrawal only happens after the lock has expired.

## JuLC Features Demonstrated

- `@SpendingValidator` with multiple `@Param` fields
- Sealed interface with `switch` expression (2 variants: `Guess`, `Withdraw`)
- Record variant with a field (`Guess(byte[] answer)`)
- `CryptoLib.sha2_256()` for on-chain SHA-256 hashing
- `IntervalLib.finiteUpperBound()` and `IntervalLib.finiteLowerBound()` for time constraints
- `Optional<PlutusData>` datum (unused but declared for V3 compatibility)
- Helper methods for clean separation of validation logic

## Files

| File | Description |
|------|-------------|
| `onchain/CfHtlcValidator.java` | Validator (on-chain logic) |
| `offchain/HtlcDemo.java` | Off-chain demo (requires Yaci DevKit) |
| `../../test/.../htlc/onchain/CfHtlcValidatorTest.java` | Unit tests (UPLC evaluation) |
| `../../test/.../htlc/HtlcIntegrationTest.java` | Integration tests (requires Yaci DevKit) |

## Running

```bash
# Unit tests
./gradlew test --tests "com.example.cftemplates.htlc.onchain.*"

# Integration tests (requires Yaci DevKit)
./gradlew test --tests "com.example.cftemplates.htlc.*IntegrationTest"

# Off-chain demo (requires Yaci DevKit)
./gradlew run -PmainClass=com.example.cftemplates.htlc.offchain.HtlcDemo
```
