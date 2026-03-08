# Escrow

A two-phase escrow validator for peer-to-peer asset swaps with datum state transitions, cancellation support, and mutual-signature trade completion.

## Overview

This validator implements a two-phase escrow pattern. An initiator locks funds at the script address with an Initiation datum. A recipient then deposits their funds, transitioning the datum to ActiveEscrow. From the active state, either party can cancel (returning assets to their original owners) or both can sign to complete the trade, swapping the deposited amounts. The validator enforces correct datum transitions, value preservation, and authorization at each step.

## Protocol Flow

```
1. Initiate           Initiator locks ADA at the script address
                      with an Initiation datum containing their
                      PKH and deposited amount.

2. Recipient Deposit  Recipient adds their funds. Datum transitions
                      from Initiation to ActiveEscrow, recording
                      both parties and their amounts. Value must
                      increase by the recipient's deposit.

3a. Complete Trade    Both parties sign. Assets are swapped:
                      initiator receives recipientAmount, recipient
                      receives initiatorAmount. UTXO is consumed.

3b. Cancel Trade      Either party signs to cancel:
                      - From Initiation: only initiator can cancel.
                      - From ActiveEscrow: either party cancels,
                        both get their original amounts back.
```

## Data Types

### EscrowDatum (sealed interface)

| Variant        | Tag | Fields                                                     |
|----------------|-----|------------------------------------------------------------|
| `Initiation`   | 0   | `initiator: byte[]`, `initiatorAmount: BigInteger`         |
| `ActiveEscrow` | 1   | `initiator: byte[]`, `initiatorAmount: BigInteger`, `recipient: byte[]`, `recipientAmount: BigInteger` |

### EscrowAction (sealed interface redeemer)

| Variant            | Tag | Fields                                            |
|--------------------|-----|---------------------------------------------------|
| `RecipientDeposit` | 0   | `recipient: byte[]`, `recipientAmount: BigInteger` |
| `CancelTrade`      | 1   | (none)                                            |
| `CompleteTrade`    | 2   | (none)                                            |

## Validator Logic

- **RecipientDeposit**: Finds the continuing output at the script address. Reads the input's inline datum as `Initiation` and the output's inline datum as `ActiveEscrow`. Verifies the initiator and amounts are preserved correctly, the recipient fields match the redeemer, and the output lovelace increased by at least the recipient's deposit.
- **CancelTrade (Initiation)**: Checks no continuing output exists at the script address. Uses `Builtins.fstPair(Builtins.unConstrData(...))` to read the datum's constructor tag. If tag is 0 (Initiation), only the initiator's signature is required.
- **CancelTrade (ActiveEscrow)**: If tag is not 0 (ActiveEscrow), either party can sign. Verifies both the initiator and recipient each receive at least their original amounts back via `checkPayment()`.
- **CompleteTrade**: Requires both parties to sign. Verifies the swap: initiator receives `recipientAmount` and recipient receives `initiatorAmount`. No continuing output allowed at the script address.

## JuLC Features Demonstrated

- `@MultiValidator` with `Purpose.SPEND` entrypoint
- Sealed interface for datum state machine (`Initiation` -> `ActiveEscrow`)
- Sealed interface with `switch` expression for redeemer (3 variants)
- `OutputLib.getInlineDatum()` for reading inline datums
- Record cast pattern: `(Initiation)(Object) plutusData`
- `Builtins.fstPair()` + `Builtins.unConstrData()` for constructor tag inspection
- `AddressLib.credentialHash()` for address-to-PKH extraction
- `ValuesLib.lovelaceOf()` for safe lovelace extraction
- `ContextsLib.signedBy()` for authorization checks
- `Builtins.equalsData()` for address comparison via `addressEquals()` helper

## Files

| File | Description |
|------|-------------|
| `onchain/CfEscrowValidator.java` | Validator (on-chain logic) |
| `offchain/EscrowDemo.java` | Off-chain demo (requires Yaci DevKit) |
| `../../test/.../escrow/onchain/CfEscrowValidatorTest.java` | Unit tests |
| `../../test/.../escrow/EscrowIntegrationTest.java` | Integration tests (requires Yaci DevKit) |

## Running

```bash
# Unit tests
./gradlew test --tests "com.example.cftemplates.escrow.onchain.*"

# Integration tests (requires Yaci DevKit)
./gradlew test --tests "com.example.cftemplates.escrow.*IntegrationTest"

# Off-chain demo (requires Yaci DevKit)
./gradlew run -PmainClass=com.example.cftemplates.escrow.offchain.EscrowDemo
```
