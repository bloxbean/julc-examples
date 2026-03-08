# Bet

A two-player oracle-resolved betting validator where players wager ADA and a trusted oracle announces the winner after an expiration deadline.

## Overview

This validator implements a peer-to-peer betting pattern. Player1 creates a bet by locking ADA at the script address with an oracle and expiration configured in the datum. Player2 joins by matching the bet amount (doubling the pot). After the expiration deadline, the designated oracle signs the transaction to announce the winner, who receives the entire pot.

## Protocol Flow

```
1. Create Bet       Player1 mints a bet token and locks ADA at the
                    script address. Datum records player1, oracle, and
                    expiration. Player2 slot is empty.

2. Join (SPEND)     Player2 spends the script UTXO and re-locks with
                    doubled ADA. Datum updated with player2's pub key
                    hash. Must happen before expiration.

3. Announce Winner  After expiration, the oracle signs and spends the
   (SPEND)          script UTXO. Redeemer specifies the winner. Pot
                    is paid to the winner's address.
```

## Data Types

### Records

| Record     | Fields                                                                 |
|------------|------------------------------------------------------------------------|
| `BetDatum` | `player1: byte[]`, `player2: byte[]`, `oracle: byte[]`, `expiration: BigInteger` |

### Sealed Interface: `BetAction` (Spend Redeemer)

| Variant           | Tag | Fields           | Description                |
|-------------------|-----|------------------|----------------------------|
| `Join`            | 0   | (none)           | Player2 joins the bet      |
| `AnnounceWinner`  | 1   | `winner: byte[]` | Oracle declares the winner |

## Validator Logic

- **Mint** (creates the bet):
  - Player1 must sign the transaction.
  - Player2 must be empty (no one has joined yet).
  - Oracle must differ from player1.
  - Transaction validity upper bound must be at or before the expiration.

- **Join** (player2 enters):
  - Player2 slot must be empty in current datum.
  - Joining player must sign.
  - Player1, oracle, and expiration must remain unchanged in the continuing output.
  - Joining player must not be player1 or the oracle.
  - Output lovelace must be exactly double the input lovelace.
  - Transaction must occur before expiration.

- **AnnounceWinner** (oracle resolves):
  - Winner must be either player1 or player2.
  - Both players must have joined (player2 not empty).
  - Oracle must sign the transaction.
  - Transaction validity lower bound must be at or after expiration.
  - An output must be paid to the winner's address.

## JuLC Features Demonstrated

- `@MultiValidator` with both `Purpose.MINT` and `Purpose.SPEND` entrypoints
- Sealed interface (`BetAction`) for typed redeemer with `switch` expression
- Record types for structured datum (`BetDatum`)
- `ContextsLib.signedBy()` for signature verification
- `ValuesLib.lovelaceOf()` for ADA amount comparison
- `IntervalLib.finiteUpperBound()` / `finiteLowerBound()` for deadline checks
- `OutputLib.getInlineDatum()` for inline datum extraction
- `AddressLib.credentialHash()` for address credential extraction
- For-each loop with break + accumulator pattern
- `Builtins.equalsData()` for address comparison
- `Builtins.emptyByteString()` as sentinel for "no player2"

## Files

| File | Description |
|------|-------------|
| `onchain/CfBetValidator.java` | Validator (on-chain logic) |
| `offchain/BetDemo.java` | Off-chain demo (requires Yaci DevKit) |
| `../../test/.../bet/onchain/CfBetValidatorTest.java` | Unit tests |
| `../../test/.../bet/BetIntegrationTest.java` | Integration tests (requires Yaci DevKit) |

## Running

```bash
# Unit tests
./gradlew test --tests "com.example.cftemplates.bet.onchain.*"

# Integration tests (requires Yaci DevKit)
./gradlew test --tests "com.example.cftemplates.bet.*IntegrationTest"

# Off-chain demo (requires Yaci DevKit)
./gradlew run -PmainClass=com.example.cftemplates.bet.offchain.BetDemo
```
