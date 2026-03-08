# Payment Splitter

A parameterized spending validator that distributes locked funds equally among a predefined list of payees.

## Overview

This validator implements a fair payment splitting pattern. Funds are locked at a script address parameterized with a list of payee public key hashes. When the UTXO is spent, the validator ensures every transaction output goes to one of the registered payees and that each payee receives the same net amount. The net amount calculation accounts for the fee payer's change by subtracting their input contribution minus the transaction fee.

## Protocol Flow

```
1. Lock           A funder sends ADA to the script address
                  parameterized with a list of payee PKHs.

2. Split          A payee (who also pays the fee) builds a
                  transaction that:
                  a) Spends the script UTXO.
                  b) Pays equal amounts to each payee.
                  c) Includes their own wallet UTXO as input
                     so the net calculation works correctly.

3. Validation     The validator checks:
                  - Every output credential is in the payees list.
                  - All payees receive the same net lovelace amount.
```

## Data Types

### @Param Fields

| Field    | Type              | Description                          |
|----------|-------------------|--------------------------------------|
| `payees` | `JulcList<byte[]>` | List of payee public key hashes     |

### SplitterDatum (record)

| Field   | Type     | Description           |
|---------|----------|-----------------------|
| `owner` | `byte[]` | Owner's public key hash |

### SplitterRedeemer (record)

| Field     | Type     | Description   |
|-----------|----------|---------------|
| `message` | `byte[]` | Arbitrary message bytes |

Note: The datum and redeemer types are defined but the validator accepts `Optional<PlutusData>` and `PlutusData` generically -- the records serve as documentation of the intended schema.

## Validator Logic

- **No additional payees**: Iterates all transaction outputs and checks that each output's credential hash is present in the `payees` list using `JulcList.any()`.
- **Equal net payments**: Computes the first payee's net amount as a reference, then recursively verifies each remaining payee receives the same net amount via `verifyEqualPayments()`.
- **Net calculation**: For each payee, sums their output lovelace and their input lovelace. If a payee has inputs (the fee payer), the net is `outputSum - (inputSum - fee)`. Otherwise the net is simply `outputSum`.

## JuLC Features Demonstrated

- `@SpendingValidator` annotation
- `@Param` with `JulcList<byte[]>` for list parameterization
- `AddressLib.credentialHash()` for extracting credential hashes from addresses
- `ValuesLib.lovelaceOf()` for safe lovelace extraction
- `JulcList.any()` with lambda predicate
- Recursive helper method (`verifyEqualPayments`) to avoid nested while loops
- For-each loop with `BigInteger` accumulator for summing values

## Files

| File | Description |
|------|-------------|
| `onchain/CfPaymentSplitterValidator.java` | Validator (on-chain logic) |
| `offchain/PaymentSplitterDemo.java` | Off-chain demo (requires Yaci DevKit) |
| `../../test/.../paymentsplitter/onchain/CfPaymentSplitterValidatorTest.java` | Unit tests |
| `../../test/.../paymentsplitter/PaymentSplitterIntegrationTest.java` | Integration tests (requires Yaci DevKit) |

## Running

```bash
# Unit tests
./gradlew test --tests "com.example.cftemplates.paymentsplitter.onchain.*"

# Integration tests (requires Yaci DevKit)
./gradlew test --tests "com.example.cftemplates.paymentsplitter.*IntegrationTest"

# Off-chain demo (requires Yaci DevKit)
./gradlew run -PmainClass=com.example.cftemplates.paymentsplitter.offchain.PaymentSplitterDemo
```
