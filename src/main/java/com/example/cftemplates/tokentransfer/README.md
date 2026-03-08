# Token Transfer

A parameterized spending validator that guards UTXOs containing a specific native asset, requiring the designated receiver's signature and preventing token leakage to non-script addresses.

## Overview

This validator implements a token-guarded spending pattern. When a UTXO at the script address contains the configured policy's tokens, the receiver must sign the transaction and no tokens of that policy may leak to addresses outside the script. If the configured policy is not present in the input (e.g., the UTXO only holds ADA), an escape hatch allows free spending to prevent funds from being permanently locked.

## Protocol Flow

```
1. Lock              Owner sends ADA (or ADA + tokens) to the script
                     address parameterized with receiver, policy, and
                     asset name.

2. Spend (guarded)   If the input contains the configured policy:
                     - Receiver must sign the transaction.
                     - The specific asset must be present (amount > 0).
                     - No tokens of that policy may appear in outputs
                       sent to non-script addresses (anti-drain).

3. Spend (escape)    If the input does NOT contain the configured
                     policy, spending is freely allowed (escape hatch).
```

## Data Types

### @Param Fields

| Field       | Type     | Description                                |
|-------------|----------|--------------------------------------------|
| `receiver`  | `byte[]` | Public key hash of the authorized receiver |
| `policy`    | `byte[]` | Policy ID of the guarded native asset      |
| `assetName` | `byte[]` | Token name of the guarded native asset     |

### Datum / Redeemer

The validator uses `Optional<PlutusData>` as the datum and an untyped `PlutusData` redeemer. No custom datum or redeemer types are defined.

## Validator Logic

- **Escape hatch**: If the input value does not contain the configured `policy`, the validator returns `true` immediately, allowing unrestricted spending.
- **Guarded path** (policy present in input):
  - Checks the specific `assetName` quantity is greater than zero via `ValuesLib.assetOf()`.
  - Verifies the `receiver` signed the transaction via `ContextsLib.signedBy()`.
  - Iterates all transaction outputs; for any output NOT at the script address, checks that the output value does not contain the configured `policy` (anti-drain via `ValuesLib.containsPolicy()`).

## JuLC Features Demonstrated

- `@MultiValidator` with `Purpose.SPEND` entrypoint
- `@Param` for script parameterization (receiver, policy, assetName)
- `ValuesLib.containsPolicy()` for policy presence checks
- `ValuesLib.assetOf()` for specific asset quantity lookup
- `ContextsLib.signedBy()` for signature verification
- For-each loop with break + accumulator pattern (no-leak check)
- `Builtins.equalsData()` for address comparison
- Manual `findOwnInputOutput()` via input list iteration

## Files

| File | Description |
|------|-------------|
| `onchain/CfTokenTransferValidator.java` | Validator (on-chain logic) |
| `offchain/TokenTransferDemo.java` | Off-chain demo (requires Yaci DevKit) |
| `../../test/.../tokentransfer/onchain/CfTokenTransferValidatorTest.java` | Unit tests |
| `../../test/.../tokentransfer/TokenTransferIntegrationTest.java` | Integration tests (requires Yaci DevKit) |

## Running

```bash
# Unit tests
./gradlew test --tests "com.example.cftemplates.tokentransfer.onchain.*"

# Integration tests (requires Yaci DevKit)
./gradlew test --tests "com.example.cftemplates.tokentransfer.*IntegrationTest"

# Off-chain demo (requires Yaci DevKit)
./gradlew run -PmainClass=com.example.cftemplates.tokentransfer.offchain.TokenTransferDemo
```
