# Vesting

A time-locked spending validator where funds can be claimed by a beneficiary after a specified time, or reclaimed by the owner at any time.

## Overview

This validator implements a vesting schedule pattern. Funds are locked at the script address with a datum containing a lock-until timestamp, an owner PKH, and a beneficiary PKH. The owner can reclaim the funds at any time by signing the transaction. The beneficiary can only claim the funds after the lock time has passed, verified using the transaction's validity interval lower bound.

## Protocol Flow

```
1. Lock          Owner sends ADA to the script address with a
                 VestingDatum specifying lockUntil time, owner PKH,
                 and beneficiary PKH.

2. Claim         Either:
                 a) Owner signs a transaction to reclaim funds
                    (allowed at any time).
                 b) Beneficiary signs a transaction after the
                    lockUntil time has passed (validity interval
                    lower bound >= lockUntil).

3. Validation    The validator checks signatories and time
                 constraints to authorize the spend.
```

## Data Types

### VestingDatum (record)

| Field         | Type         | Description                              |
|---------------|--------------|------------------------------------------|
| `lockUntil`   | `BigInteger` | POSIX timestamp (ms) until funds are locked |
| `owner`       | `byte[]`     | Owner's public key hash                  |
| `beneficiary` | `byte[]`     | Beneficiary's public key hash            |

## Validator Logic

- **Owner path**: If the owner signed the transaction (`ContextsLib.signedBy()`), validation immediately succeeds regardless of time.
- **Beneficiary path**: Checks that the beneficiary signed the transaction AND that the transaction's validity interval lower bound (`IntervalLib.finiteLowerBound()`) is greater than or equal to `lockUntil`.
- If neither the owner nor the beneficiary signed, or if the beneficiary signed before `lockUntil`, validation fails.

## JuLC Features Demonstrated

- `@SpendingValidator` annotation
- Record type as inline datum (`VestingDatum`)
- `ContextsLib.signedBy()` for signature verification
- `IntervalLib.finiteLowerBound()` for time-based validation
- `BigInteger.compareTo()` for on-chain numeric comparison
- Short-circuit return (`if (ownerSigned) return true`)

## Files

| File | Description |
|------|-------------|
| `onchain/CfVestingValidator.java` | Validator (on-chain logic) |
| `offchain/VestingDemo.java` | Off-chain demo (requires Yaci DevKit) |
| `../../test/.../vesting/onchain/CfVestingValidatorTest.java` | Unit tests (Direct Java + UPLC evaluation) |
| `../../test/.../vesting/VestingIntegrationTest.java` | Integration tests (requires Yaci DevKit) |

## Running

```bash
# Unit tests
./gradlew test --tests "com.example.cftemplates.vesting.onchain.*"

# Integration tests (requires Yaci DevKit)
./gradlew test --tests "com.example.cftemplates.vesting.*IntegrationTest"

# Off-chain demo (requires Yaci DevKit)
./gradlew run -PmainClass=com.example.cftemplates.vesting.offchain.VestingDemo
```
