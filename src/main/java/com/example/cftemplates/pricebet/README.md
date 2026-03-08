# Price Bet

An oracle-based betting validator where a player wagers against a target asset price, using reference inputs for oracle price feeds and time-bounded validity intervals.

## Overview

This validator implements a price betting pattern. An owner creates a bet by locking ADA at the script with a PriceBetDatum specifying a target price, deadline, and oracle. A player joins by doubling the pot before the deadline. If the oracle price meets or exceeds the target rate, the player claims the full pot (Win). If the deadline passes without a winning claim, the owner reclaims everything (Timeout). The oracle data is read via reference inputs using a map-based datum structure.

## Protocol Flow

```
1. Create Bet        Owner locks betAmount of ADA at the script
                     address with a PriceBetDatum. The player field
                     is set to empty bytes (no player yet).

2. Join              A player spends the bet UTXO and creates a
                     continuing output with doubled pot (2x betAmount).
                     The player field is set in the new datum. Player
                     must sign. All other datum fields must remain
                     unchanged. Must happen before the deadline.

3. Win               Player spends the bet UTXO after an oracle
                     reference input confirms the price >= targetRate.
                     The oracle must still be valid (not expired).
                     The full pot is paid to the player. Must happen
                     before the deadline.

4. Timeout           Owner spends the bet UTXO after the deadline
                     has passed. Owner must sign. The full pot is
                     paid to the owner.
```

## Data Types

### Records

| Record | Fields | Description |
|--------|--------|-------------|
| `PriceBetDatum` | `byte[] owner`, `byte[] player`, `byte[] oracleVkh`, `BigInteger targetRate`, `BigInteger deadline`, `BigInteger betAmount` | Bet parameters stored as inline datum |

### Sealed Interfaces

**PriceBetAction** (spend redeemer)

| Variant | Tag | Description |
|---------|-----|-------------|
| `Join` | 0 | Player joins the bet by doubling the pot |
| `Win` | 1 | Player claims the pot when oracle price >= target |
| `Timeout` | 2 | Owner reclaims after deadline passes |

### @Param Fields

This validator has no `@Param` fields -- all configuration is stored in the inline datum.

## Validator Logic

- **SPEND Join**:
  - Datum must have no player yet (player field equals empty byte string).
  - Continuing output at same script address with updated datum.
  - Player field must be set (non-empty) and player must sign.
  - All other datum fields (owner, oracleVkh, targetRate, deadline, betAmount) must remain unchanged.
  - Continuing output lovelace must be at least 2x betAmount (doubled pot).
  - Transaction validity upper bound must be at or before the deadline.

- **SPEND Win**:
  - Must have a player (non-empty player field).
  - Player must sign.
  - Oracle reference input found by matching credential hash to oracleVkh at a script address.
  - Oracle datum parsed as nested structure: `Constr(0, [Constr(2, [Map{0:price, 1:timestamp, 2:expiry}])])`.
  - Oracle price (map key 0) must be >= targetRate.
  - Oracle must not be expired (tx upper bound <= oracle expiry at map key 2).
  - Transaction must be before the deadline.
  - Full pot (input lovelace) must be paid to the player's address.

- **SPEND Timeout**:
  - Transaction validity lower bound must be after the deadline.
  - Owner must sign.
  - Full pot (input lovelace) must be paid to the owner's address.

## JuLC Features Demonstrated

- `@MultiValidator` with `Purpose.SPEND` entrypoint
- Sealed interface for typed redeemer dispatch (`PriceBetAction`)
- Oracle reference input pattern (`referenceInputs` iteration)
- `JulcMap<BigInteger, BigInteger>` for map-based oracle data access
- `IntervalLib.finiteUpperBound()` and `IntervalLib.finiteLowerBound()` for time-bounded validation
- `ValuesLib.lovelaceOf()` for safe lovelace extraction
- `ContextsLib.signedBy()` for signature verification
- `OutputLib.getInlineDatum()` for inline datum extraction
- `AddressLib.credentialHash()` and `AddressLib.isScriptAddress()` for address matching
- `Builtins.emptyByteString()` as sentinel for "no player"
- `Builtins.unConstrData()` / `Builtins.sndPair()` / `Builtins.headList()` for low-level oracle datum parsing
- `Builtins.equalsData()` for address comparison
- Record cast pattern: `(PriceBetDatum)(Object) datumData`

## Files

| File | Description |
|------|-------------|
| `onchain/CfPriceBetValidator.java` | Validator (on-chain logic) |
| `offchain/PriceBetDemo.java` | Off-chain demo (requires Yaci DevKit) |
| `../../test/.../pricebet/onchain/CfPriceBetValidatorTest.java` | Unit tests |
| `../../test/.../pricebet/PriceBetIntegrationTest.java` | Integration tests (requires Yaci DevKit) |

## Running

```bash
# Unit tests
./gradlew test --tests "com.example.cftemplates.pricebet.onchain.*"

# Integration tests (requires Yaci DevKit)
./gradlew test --tests "com.example.cftemplates.pricebet.*IntegrationTest"

# Off-chain demo (requires Yaci DevKit)
./gradlew run -PmainClass=com.example.cftemplates.pricebet.offchain.PriceBetDemo
```
