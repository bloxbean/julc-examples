# Lottery

A parameterized commit-reveal lottery game between two players, with timeout handling and parity-based winner determination.

## Overview

This validator implements a two-player lottery using a commit-reveal scheme. Both players commit blake2b hashes of their secret numbers when creating the game. They then reveal their secrets in sequence (player1 first, then player2). Once both are revealed, the winner is determined by the parity of the sum of the revealed values: odd means player1 wins, even means player2 wins. Timeout mechanisms protect against non-cooperative players by allowing the opposing player to claim the pot if the other fails to reveal in time.

## Protocol Flow

```
1. Create (MINT)     Both players sign. Mint 1 LOTTERY_TOKEN.
                     Lock pot + token at script with LotteryDatum
                     containing commitments, empty reveals, and
                     deadline parameters.

2. Reveal1 (SPEND)   Player1 reveals secret n1. blake2b(n1) must
                     match commit1. Continuing output with n1 set.

3. Reveal2 (SPEND)   Player2 reveals secret n2 (after n1 revealed).
                     blake2b(n2) must match commit2. Continuing
                     output with n2 set.

4. Settle (SPEND)    Both revealed. Winner = player1 if
                     (v1 + v2) % 2 == 1, else player2. Winner
                     signs, claims pot, burns LOTTERY_TOKEN.

-- Timeout paths --

T1. Timeout1 (SPEND) Player1 failed to reveal before endReveal.
                     Player2 signs, claims pot, burns token.

T2. Timeout2 (SPEND) Player2 failed to reveal before
                     endReveal + delta. Player1 signs, claims pot,
                     burns token.
```

## Data Types

### @Param Fields

| Field       | Type         | Description                              |
|-------------|--------------|------------------------------------------|
| `gameIndex` | `BigInteger` | Unique game identifier for parameterization |

### Records

| Record         | Fields                                                                                                              |
|----------------|---------------------------------------------------------------------------------------------------------------------|
| `LotteryDatum` | `player1: byte[]`, `player2: byte[]`, `commit1: byte[]`, `commit2: byte[]`, `n1: byte[]`, `n2: byte[]`, `endReveal: BigInteger`, `delta: BigInteger` |

### Sealed Interface: `MintAction` (Mint Redeemer)

| Variant     | Tag | Fields | Description              |
|-------------|-----|--------|--------------------------|
| `Create`    | 0   | (none) | Create a new lottery game |
| `BurnToken` | 1   | (none) | Burn the LOTTERY_TOKEN   |

### Sealed Interface: `LotteryAction` (Spend Redeemer)

| Variant    | Tag | Fields        | Description                          |
|------------|-----|---------------|--------------------------------------|
| `Reveal1`  | 0   | `n1: byte[]`  | Player1 reveals secret               |
| `Reveal2`  | 1   | `n2: byte[]`  | Player2 reveals secret               |
| `Timeout1` | 2   | (none)        | Player1 timed out, player2 wins      |
| `Timeout2` | 3   | (none)        | Player2 timed out, player1 wins      |
| `Settle`   | 4   | (none)        | Both revealed, determine winner      |

## Validator Logic

- **Create** (mint):
  - Exactly 1 token minted under this policy.
  - Both player1 and player2 must sign.
  - Commitments (commit1, commit2) must be non-empty.

- **BurnToken** (mint):
  - Exactly 1 token burned (quantity = -1) under this policy.

- **Reveal1** (spend):
  - Player1 must sign.
  - `n1` must be empty (not yet revealed) and revealed value must be non-empty.
  - `blake2b_256(revealed)` must match `commit1`.
  - Script output must continue (not consumed).

- **Reveal2** (spend):
  - Player2 must sign.
  - `n1` must already be revealed; `n2` must be empty.
  - `blake2b_256(revealed)` must match `commit2`.
  - Script output must continue.

- **Timeout1** (spend):
  - `n1` must be empty (player1 failed to reveal).
  - Transaction lower bound must be at or after `endReveal`.
  - Player2 must sign. Script consumed. Token burned.

- **Timeout2** (spend):
  - `n1` revealed but `n2` empty (player2 failed to reveal).
  - Transaction lower bound must be at or after `endReveal + delta`.
  - Player1 must sign. Script consumed. Token burned.

- **Settle** (spend):
  - Both `n1` and `n2` must be revealed.
  - Winner determined by `(byteStringToInteger(n1) + byteStringToInteger(n2)) % 2`: odd = player1, even = player2.
  - Winner must sign. Script consumed. Token burned.

## JuLC Features Demonstrated

- `@MultiValidator` with both `Purpose.MINT` and `Purpose.SPEND` entrypoints
- `@Param` for script parameterization (gameIndex)
- Two sealed interfaces (`MintAction`, `LotteryAction`) for typed redeemers with `switch` expressions
- Record type for structured datum (`LotteryDatum`)
- `CryptoLib.blake2b_256()` for commit-reveal hash verification
- `ByteStringLib.byteStringToInteger()` for converting byte arrays to integers
- `ValuesLib.countTokensWithQty()` for verifying mint/burn quantities
- `ValuesLib.containsPolicy()` for finding outputs with a specific policy
- `ContextsLib.signedBy()` for signature verification
- `ContextsLib.ownHash()` for obtaining the script's own hash
- `IntervalLib.finiteLowerBound()` for timeout deadline checks
- `Builtins.unBData()` for extracting byte arrays from PlutusData
- For-each loop with break + accumulator pattern
- Ternary conditional for winner selection

## Files

| File | Description |
|------|-------------|
| `onchain/CfLotteryValidator.java` | Validator (on-chain logic) |
| `offchain/LotteryDemo.java` | Off-chain demo (requires Yaci DevKit) |
| `../../test/.../lottery/onchain/CfLotteryValidatorTest.java` | Unit tests |
| `../../test/.../lottery/LotteryIntegrationTest.java` | Integration tests (requires Yaci DevKit) |

## Running

```bash
# Unit tests
./gradlew test --tests "com.example.cftemplates.lottery.onchain.*"

# Integration tests (requires Yaci DevKit)
./gradlew test --tests "com.example.cftemplates.lottery.*IntegrationTest"

# Off-chain demo (requires Yaci DevKit)
./gradlew run -PmainClass=com.example.cftemplates.lottery.offchain.LotteryDemo
```
