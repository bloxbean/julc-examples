# Crowdfunding

A parameterized spending validator that collects donations toward a funding goal by a deadline, with withdrawal for the beneficiary or reclaim for donors depending on whether the goal is met.

## Overview

This validator implements a crowdfunding pattern with three actions. Donors contribute ADA which is tracked in an on-chain wallet map (donor PKH to lovelace amount). After the deadline, if the goal is met, the beneficiary can withdraw all funds. If the goal is not met, donors can reclaim their individual contributions. The datum transitions through donation updates while preserving the wallet map invariant that the total of all entries equals the UTXO's lovelace value.

## Protocol Flow

```
1. Initialize     First donor sends ADA to the script address with
                  a CrowdfundDatum containing the initial wallet map
                  entry {donor -> amount}.

2. Donate         Additional donors add funds. The validator checks
                  that the continuing output has more lovelace and
                  the wallet map total equals the output lovelace.

3a. Withdraw      After deadline, if goal is met, the beneficiary
                  signs to withdraw all funds.

3b. Reclaim       After deadline, if goal is NOT met, donors sign
                  to reclaim their share. Supports partial reclaim
                  (some donors reclaim, UTXO continues with
                  remaining donors).
```

## Data Types

### @Param Fields

| Field         | Type         | Description                             |
|---------------|--------------|-----------------------------------------|
| `beneficiary` | `byte[]`     | Beneficiary's public key hash           |
| `goal`        | `BigInteger` | Funding goal in lovelace                |
| `deadline`    | `BigInteger` | POSIX timestamp deadline (milliseconds) |

### CrowdfundDatum (record)

| Field     | Type                          | Description                          |
|-----------|-------------------------------|--------------------------------------|
| `wallets` | `JulcMap<byte[], BigInteger>` | Map of donor PKH to donated lovelace |

### CrowdfundAction (sealed interface redeemer)

| Variant    | Tag | Description                                          |
|------------|-----|------------------------------------------------------|
| `Donate`   | 0   | Add funds, update wallet map in continuing output    |
| `Withdraw` | 1   | Beneficiary withdraws all (goal met, after deadline) |
| `Reclaim`  | 2   | Donors reclaim their share (goal not met, after deadline) |

## Validator Logic

- **Donate**: Finds the continuing output at the script address. Extracts the new datum's wallet map via inline datum and verifies the output lovelace increased and the wallet map total equals the output lovelace.
- **Withdraw**: Checks the transaction's validity range lower bound is at or after the deadline, the script UTXO lovelace meets the goal, and the beneficiary signed the transaction.
- **Reclaim**: Checks the deadline has passed and the goal is NOT met. Sums each signing donor's contribution from the wallet map. If the signers' total equals the full script balance, the UTXO is fully consumed. Otherwise, verifies a continuing output exists with the reclaiming donors removed from the wallet map and sufficient remaining lovelace.
- **Escape hatch**: If no datum is present (`datumOpt.isEmpty()`), spending is freely allowed.

## JuLC Features Demonstrated

- `@MultiValidator` with `Purpose.SPEND` entrypoint
- `@Param` for script parameterization (beneficiary, goal, deadline)
- Sealed interface with `switch` expression (3 variants)
- `JulcMap<byte[], BigInteger>` for on-chain map operations (`.keys()`, `.values()`, `.get()`, `.containsKey()`)
- `IntervalLib.finiteLowerBound()` for deadline checking
- `ContextsLib.signedBy()` for signature verification
- `ValuesLib.lovelaceOf()` for safe lovelace extraction
- `OutputLib.getInlineDatum()` for reading continuing output datum
- Recursive helper method and for-each loops with accumulators
- `Optional<CrowdfundDatum>` typed datum with escape hatch

## Files

| File | Description |
|------|-------------|
| `onchain/CfCrowdfundValidator.java` | Validator (on-chain logic) |
| `offchain/CrowdfundDemo.java` | Off-chain demo (requires Yaci DevKit) |
| `../../test/.../crowdfund/onchain/CfCrowdfundValidatorTest.java` | Unit tests |
| `../../test/.../crowdfund/CrowdfundIntegrationTest.java` | Integration tests (requires Yaci DevKit) |

## Running

```bash
# Unit tests
./gradlew test --tests "com.example.cftemplates.crowdfund.onchain.*"

# Integration tests (requires Yaci DevKit)
./gradlew test --tests "com.example.cftemplates.crowdfund.*IntegrationTest"

# Off-chain demo (requires Yaci DevKit)
./gradlew run -PmainClass=com.example.cftemplates.crowdfund.offchain.CrowdfundDemo
```
