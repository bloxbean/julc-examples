# Atomic Transaction

A multi-validator demonstrating Cardano's native transaction atomicity, combining a password-gated minting policy with an always-succeeding spending validator.

## Overview

This validator showcases Cardano's atomic transaction guarantee: if any part of a transaction fails, the entire transaction is rolled back. A single `@MultiValidator` defines both a minting policy (which requires a secret password) and a spending validator (which always succeeds). When both are used in the same transaction, a wrong mint password causes the entire transaction to fail atomically, even though the spend portion would have succeeded on its own.

## Protocol Flow

```
1. Lock          Send ADA to the script address (any datum).

2. Atomic Tx     Build a transaction that both:
                 a) Spends from the script (always succeeds).
                 b) Mints a token using the same script as a
                    minting policy (requires correct password).

3. Outcome       If the password is wrong, the mint fails and the
                 entire transaction is rejected atomically — the
                 spend does not go through either. If the password
                 is correct, both spend and mint succeed together.
```

## Data Types

### MintRedeemer (record)

| Field      | Type     | Description                        |
|------------|----------|------------------------------------|
| `password` | `String` | Password string to authorize minting |

### Spend Entrypoint Parameters

| Parameter  | Type               | Description               |
|------------|--------------------|---------------------------|
| `datum`    | `Optional<PlutusData>` | Unused (ignored)       |
| `redeemer` | `PlutusData`       | Unused (ignored)          |
| `ctx`      | `ScriptContext`    | Transaction context       |

## Validator Logic

- **Mint (handleMint)**: Checks that `redeemer.password()` equals the string `"super_secret_password"`. Returns `true` only on exact match.
- **Spend (handleSpend)**: Always returns `true` unconditionally. This demonstrates that even a passing spend validator is rolled back when the mint validator in the same transaction fails.

## JuLC Features Demonstrated

- `@MultiValidator` annotation for combined mint + spend validator
- `@Entrypoint(purpose = Purpose.MINT)` for minting policy entrypoint
- `@Entrypoint(purpose = Purpose.SPEND)` for spending entrypoint
- `String.equals()` for on-chain string comparison
- Record type as minting redeemer (`MintRedeemer`)
- `Optional<PlutusData>` datum for V3 spend compatibility

## Files

| File | Description |
|------|-------------|
| `onchain/CfAtomicTxValidator.java` | Validator (on-chain logic) |
| `offchain/AtomicTxDemo.java` | Off-chain demo (requires Yaci DevKit) |
| `../../test/.../atomictx/onchain/CfAtomicTxValidatorTest.java` | Unit tests (UPLC evaluation) |
| `../../test/.../atomictx/AtomicTxIntegrationTest.java` | Integration tests (requires Yaci DevKit) |

## Running

```bash
# Unit tests
./gradlew test --tests "com.example.cftemplates.atomictx.onchain.*"

# Integration tests (requires Yaci DevKit)
./gradlew test --tests "com.example.cftemplates.atomictx.*IntegrationTest"

# Off-chain demo (requires Yaci DevKit)
./gradlew run -PmainClass=com.example.cftemplates.atomictx.offchain.AtomicTxDemo
```
