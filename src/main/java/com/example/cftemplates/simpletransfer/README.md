# Simple Transfer

A parameterized spending validator where funds locked at a script address can only be unlocked by a designated receiver.

## Overview

This validator implements the simplest possible guarded transfer pattern. The script is parameterized with a receiver's public key hash at deployment time. Any funds sent to the resulting script address can only be spent when the transaction is signed by that receiver. The datum and redeemer are unused.

## Protocol Flow

```
1. Deploy        Parameterize the validator with the receiver's
                 public key hash to produce a unique script address.

2. Lock          Anyone sends ADA to the script address.

3. Unlock        The designated receiver signs a transaction to
                 spend the locked funds. The validator checks that
                 the receiver's PKH is in the transaction signatories.
```

## Data Types

### @Param Fields

| Field      | Type     | Description                        |
|------------|----------|------------------------------------|
| `receiver` | `byte[]` | Receiver's public key hash (28 bytes) |

### Entrypoint Parameters

| Parameter  | Type               | Description               |
|------------|--------------------|---------------------------|
| `datum`    | `Optional<PlutusData>` | Unused (ignored)       |
| `redeemer` | `PlutusData`       | Unused (ignored)          |
| `ctx`      | `ScriptContext`    | Transaction context       |

## Validator Logic

- Checks that the parameterized `receiver` PKH is present in the transaction signatories using `ContextsLib.signedBy()`.
- If the receiver signed the transaction, validation succeeds; otherwise it fails.
- Datum and redeemer are completely ignored.

## JuLC Features Demonstrated

- `@SpendingValidator` annotation for spending validator declaration
- `@Param` for compile-time script parameterization
- `Optional<PlutusData>` datum (V3 spending validator pattern)
- `ContextsLib.signedBy()` for signature verification

## Files

| File | Description |
|------|-------------|
| `onchain/CfSimpleTransferValidator.java` | Validator (on-chain logic) |
| `offchain/SimpleTransferDemo.java` | Off-chain demo (requires Yaci DevKit) |
| `../../test/.../simpletransfer/onchain/CfSimpleTransferValidatorTest.java` | Unit tests (UPLC evaluation) |
| `../../test/.../simpletransfer/SimpleTransferIntegrationTest.java` | Integration tests (requires Yaci DevKit) |

## Running

```bash
# Unit tests
./gradlew test --tests "com.example.cftemplates.simpletransfer.onchain.*"

# Integration tests (requires Yaci DevKit)
./gradlew test --tests "com.example.cftemplates.simpletransfer.*IntegrationTest"

# Off-chain demo (requires Yaci DevKit)
./gradlew run -PmainClass=com.example.cftemplates.simpletransfer.offchain.SimpleTransferDemo
```
