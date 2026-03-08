# Decentralized Identity

A spending validator that manages an on-chain identity record with an owner and a list of delegates, supporting ownership transfer and delegate management.

## Overview

This validator implements a decentralized identity pattern where an owner controls an identity UTxO stored at a script address. The owner can transfer ownership to a new public key hash, add delegates with expiration timestamps, or remove existing delegates. All operations require the current owner's signature and preserve the locked ADA value in a continuing output.

## Protocol Flow

```
1. Create Identity     Owner sends ADA to the script address with an
                       IdentityDatum containing the owner's pub key
                       hash and an empty delegates list.

2. Transfer Owner      Owner spends and re-locks the UTxO with a new
   (SPEND)             owner in the datum. Delegates list unchanged.

3. Add Delegate        Owner spends and re-locks, adding a new
   (SPEND)             delegate entry (key + expiration) to the list.
                       Delegate must not already exist and must not
                       be the owner.

4. Remove Delegate     Owner spends and re-locks, removing an
   (SPEND)             existing delegate from the list.
```

## Data Types

### Records

| Record           | Fields                                             |
|------------------|----------------------------------------------------|
| `Delegate`       | `key: byte[]`, `expires: BigInteger`               |
| `IdentityDatum`  | `owner: byte[]`, `delegates: JulcList<Delegate>`   |

### Sealed Interface: `IdentityAction` (Spend Redeemer)

| Variant          | Tag | Fields                                | Description                     |
|------------------|-----|---------------------------------------|---------------------------------|
| `TransferOwner`  | 0   | `newOwner: byte[]`                    | Transfer identity to new owner  |
| `AddDelegate`    | 1   | `delegate: byte[]`, `expires: BigInteger` | Add a delegate with expiration |
| `RemoveDelegate` | 2   | `delegate: byte[]`                    | Remove an existing delegate     |

## Validator Logic

- **Common checks** (all actions):
  - Current owner must sign the transaction.
  - A continuing output must exist at the same script address.
  - Input and output lovelace must be equal (value preserved).

- **TransferOwner**:
  - New owner in the continuing output datum must match the redeemer's `newOwner`.
  - Delegates list must remain unchanged.

- **AddDelegate**:
  - Owner must remain unchanged in the continuing output.
  - The delegate must not already exist in the current list.
  - The delegate must exist in the new list.
  - The delegate must not be the same as the owner.

- **RemoveDelegate**:
  - Owner must remain unchanged in the continuing output.
  - The delegate must exist in the current list.
  - The delegate must not exist in the new list.

## JuLC Features Demonstrated

- `@MultiValidator` with `Purpose.SPEND` entrypoint
- Sealed interface (`IdentityAction`) for typed redeemer with `switch` expression
- Nested record types (`Delegate` inside `IdentityDatum`)
- `JulcList<Delegate>` for on-chain list of records
- `JulcList.any()` lambda for delegate existence checks
- `ContextsLib.signedBy()` for owner signature verification
- `ValuesLib.lovelaceOf()` for value preservation check
- `OutputLib.getInlineDatum()` for inline datum extraction
- `Builtins.listData()` and `Builtins.equalsData()` for list and address comparison
- For-each loop with break + accumulator pattern

## Files

| File | Description |
|------|-------------|
| `onchain/CfIdentityValidator.java` | Validator (on-chain logic) |
| `offchain/IdentityDemo.java` | Off-chain demo (requires Yaci DevKit) |
| `../../test/.../identity/onchain/CfIdentityValidatorTest.java` | Unit tests |
| `../../test/.../identity/IdentityIntegrationTest.java` | Integration tests (requires Yaci DevKit) |

## Running

```bash
# Unit tests
./gradlew test --tests "com.example.cftemplates.identity.onchain.*"

# Integration tests (requires Yaci DevKit)
./gradlew test --tests "com.example.cftemplates.identity.*IntegrationTest"

# Off-chain demo (requires Yaci DevKit)
./gradlew run -PmainClass=com.example.cftemplates.identity.offchain.IdentityDemo
```
