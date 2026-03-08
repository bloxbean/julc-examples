# Vault

A two-phase withdrawal validator with a configurable time lock, requiring the owner to initiate a withdrawal and wait before finalizing it.

## Overview

This validator implements a vault pattern for securing funds with a delayed withdrawal mechanism. Withdrawals require two steps: first, the owner initiates a withdrawal which records a lock time in the datum; then, after a configurable wait period has elapsed, the owner can finalize the withdrawal to actually move the funds. The owner can also cancel a pending withdrawal at any time. This pattern protects against key compromise by giving the owner a window to detect and cancel unauthorized withdrawal attempts.

## Protocol Flow

```
1. Deploy        Parameterize the validator with the owner's PKH
                 and a waitTime duration (in milliseconds).

2. Lock          Send ADA to the script address with a
                 WithdrawDatum containing a lockTime.

3. Withdraw      Owner initiates withdrawal. Funds are sent back
                 to the script with an updated WithdrawDatum
                 where lockTime >= current time. Total output
                 lovelace must be >= input lovelace.

4. Finalize      After waitTime has passed since lockTime, owner
                 signs to finalize and claim the funds.

5. Cancel        Owner can cancel a pending withdrawal at any
                 time. Funds remain at the script with output
                 lovelace >= input lovelace.
```

## Data Types

### @Param Fields

| Field      | Type         | Description                                   |
|------------|--------------|-----------------------------------------------|
| `owner`    | `byte[]`     | Owner's public key hash                       |
| `waitTime` | `BigInteger` | Required wait duration in milliseconds        |

### WithdrawDatum (record)

| Field      | Type         | Description                                   |
|------------|--------------|-----------------------------------------------|
| `lockTime` | `BigInteger` | POSIX timestamp (ms) when withdrawal was initiated |

### VaultAction (sealed interface redeemer)

| Variant    | Tag | Description                                     |
|------------|-----|-------------------------------------------------|
| `Withdraw` | 0   | Initiate withdrawal (lock funds with timestamp) |
| `Finalize` | 1   | Complete withdrawal after wait period            |
| `Cancel`   | 2   | Cancel pending withdrawal, keep funds at script  |

## Validator Logic

- **All actions**: Require the owner's signature (`ContextsLib.signedBy()`). Non-owner transactions are rejected immediately.
- **Withdraw**: Finds the current script input, then iterates over outputs at the same script address. Verifies that (1) total output lovelace >= input lovelace (funds stay locked), and (2) any inline datum on continuing outputs has a `lockTime` that is at or before the current time (`IntervalLib.finiteLowerBound()`).
- **Finalize**: Reads the `WithdrawDatum` from the spent input. Computes `unlockTime = waitTime + lockTime` and checks that the validity interval lower bound is >= `unlockTime`, ensuring the required waiting period has elapsed.
- **Cancel**: Finds the current script input and checks that total output lovelace at the script address >= input lovelace, ensuring funds remain locked.

## JuLC Features Demonstrated

- `@MultiValidator` with `@Entrypoint(purpose = Purpose.SPEND)`
- `@Param` for script parameterization (owner PKH + wait time)
- Sealed interface with `switch` expression (3 variants)
- `Optional<WithdrawDatum>` typed datum
- `ScriptInfo.SpendingScript` cast to access `TxOutRef`
- `ValuesLib.lovelaceOf()` for reading lovelace from `Value`
- `IntervalLib.finiteLowerBound()` for time-based checks
- `OutputDatum` pattern matching (`switch` on `Inline` / `NoOutputDatum` / `DatumHash`)
- `(WithdrawDatum)(Object) datumData` cast for datum deserialization
- For-each loop with accumulator pattern (`BigInteger` sum)
- `Builtins.equalsData()` for address equality comparison
- Helper methods for modular validation (`handleWithdraw`, `handleFinalize`, `handleCancel`)

## Files

| File | Description |
|------|-------------|
| `onchain/CfVaultValidator.java` | Validator (on-chain logic) |
| `offchain/VaultDemo.java` | Off-chain demo (requires Yaci DevKit) |
| `../../test/.../vault/onchain/CfVaultValidatorTest.java` | Unit tests (UPLC evaluation) |
| `../../test/.../vault/VaultIntegrationTest.java` | Integration tests (requires Yaci DevKit) |

## Running

```bash
# Unit tests
./gradlew test --tests "com.example.cftemplates.vault.onchain.*"

# Integration tests (requires Yaci DevKit)
./gradlew test --tests "com.example.cftemplates.vault.*IntegrationTest"

# Off-chain demo (requires Yaci DevKit)
./gradlew run -PmainClass=com.example.cftemplates.vault.offchain.VaultDemo
```
