# Simple DEX Swap Order

A peer-to-peer swap order validator where a maker locks tokens at a script address, and a taker fills the order by paying the requested tokens to the maker.

## Overview

This validator implements a simple DEX (decentralized exchange) order-book pattern. A maker creates a swap order specifying what they offer and what they want in return. Anyone can fill the order by paying the requested tokens to the maker's address. The maker can also cancel the order to reclaim their tokens.

## Protocol Flow

```
1. Place Order    Maker locks offered tokens at the script address
                  with an OrderDatum specifying requested tokens.

2. Fill / Cancel  Either:
                  a) Taker fills the order by paying requested tokens
                     to the maker's address (FillOrder redeemer).
                  b) Maker cancels and reclaims tokens (CancelOrder
                     redeemer, requires maker's signature).

3. Settlement     Script validates the payment amount and recipient,
                  then releases the locked tokens.
```

## Data Types

### OrderDatum (record)

| Field             | Type         | Description                            |
|-------------------|--------------|----------------------------------------|
| `maker`           | `byte[]`     | Maker's public key hash                |
| `offeredPolicy`   | `byte[]`     | Policy ID of offered token (empty = ADA) |
| `offeredToken`    | `byte[]`     | Token name of offered token            |
| `offeredAmount`   | `BigInteger` | Amount of offered tokens               |
| `requestedPolicy` | `byte[]`     | Policy ID of requested token           |
| `requestedToken`  | `byte[]`     | Token name of requested token          |
| `requestedAmount` | `BigInteger` | Amount of requested tokens             |

### SwapAction (sealed interface redeemer)

| Variant       | Description                                    |
|---------------|------------------------------------------------|
| `FillOrder`   | Taker fills the order by paying requested tokens |
| `CancelOrder` | Maker cancels and reclaims locked tokens        |

## Validator Logic

- **FillOrder (ADA path)**: If the requested token is ADA (empty policy), uses `OutputLib.lovelacePaidTo()` to check that the maker receives at least `requestedAmount` lovelace.
- **FillOrder (native token path)**: For native tokens, iterates over outputs at the maker's address using a for-each loop with a `BigInteger` accumulator, summing asset quantities via `ValuesLib.assetOf()`.
- **CancelOrder**: Checks that the maker's PKH is in the transaction signatories.

## JuLC Features Demonstrated

- Sealed interface with `switch` expression (2 variants)
- `OutputLib.lovelacePaidTo()` for ADA payment verification
- `ValuesLib.assetOf()` for native token quantity checks
- `OutputLib.outputsAt()` for filtering outputs by address
- For-each loop with `BigInteger` accumulator
- `PubKeyHash.of()` and `Address` construction
- `ContextsLib.trace()` for on-chain debug traces

## Files

| File | Description |
|------|-------------|
| `onchain/SwapOrder.java` | Validator (on-chain logic) |
| `offchain/SwapOrderDemo.java` | Off-chain demo (requires Yaci Devkit) |
| `../../test/.../swap/SwapOrderTest.java` | Unit tests (Direct Java + UPLC + JulcEval proxy) |

## Running

```bash
# Unit tests (no external dependencies)
./gradlew test --tests "com.example.swap.*"

# Off-chain demo (requires Yaci Devkit)
./gradlew run -PmainClass=com.example.swap.offchain.SwapOrderDemo
```
