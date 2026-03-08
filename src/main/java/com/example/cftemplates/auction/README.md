# Auction

An English auction validator with both mint and spend entrypoints, supporting seller initialization, competitive bidding with automatic refunds, and post-expiration settlement.

## Overview

This validator implements an English auction pattern using a multi-validator with separate mint and spend entrypoints. The seller initializes the auction by minting an auction token and locking the auctioned asset at the script address with an AuctionDatum. Bidders place increasingly higher bids, with each new bid automatically refunding the previous bidder. After the expiration time, the auction ends: if bids exist, the winner receives the asset and the seller receives the highest bid in ADA; if no bids were placed, the seller reclaims the asset.

## Protocol Flow

```
1. Create Auction   Seller mints an auction token (mint entrypoint).
                    The auctioned asset and AuctionDatum are locked
                    at the script address. Seller must sign, no
                    bidder yet, auction not expired.

2. Bid              A bidder places a higher bid (spend entrypoint,
                    Bid redeemer). The continuing output at the
                    script address has an updated datum with the
                    new highest bidder/bid. The previous bidder
                    is refunded their bid amount. Auction must
                    not be expired.

3a. End (with bids) After expiration, anyone can end the auction.
                    Seller receives the highest bid in ADA.
                    Winner receives the auctioned asset.

3b. End (no bids)   After expiration, seller signs to reclaim
                    the asset (no bids were placed).
```

## Data Types

### AuctionDatum (record)

| Field           | Type         | Description                                |
|-----------------|--------------|--------------------------------------------|
| `seller`        | `byte[]`     | Seller's public key hash                   |
| `highestBidder` | `byte[]`     | Current highest bidder's PKH (empty = none) |
| `highestBid`    | `BigInteger` | Current highest bid in lovelace            |
| `expiration`    | `BigInteger` | Auction expiration POSIX timestamp (ms)    |
| `assetPolicy`   | `byte[]`     | Policy ID of the auctioned asset           |
| `assetName`     | `byte[]`     | Token name of the auctioned asset          |

### AuctionAction (sealed interface redeemer)

| Variant    | Tag | Description                                     |
|------------|-----|-------------------------------------------------|
| `Bid`      | 0   | Place a new higher bid                          |
| `Withdraw` | 1   | Not implemented (always returns false)          |
| `End`      | 2   | End the auction after expiration                |

## Validator Logic

### Mint Entrypoint
- Finds the output sent to the script address (matched by policy ID bytes).
- Reads the inline AuctionDatum and verifies: seller signed, no bidder yet (empty bytes), bid is non-negative, auction not expired (upper bound <= expiration), and the auctioned asset is present in the output.

### Spend Entrypoint
- **Bid**: Finds the continuing output at the script address. Verifies the auction is not expired, the new bid is higher, the new bidder signed, and datum fields (seller, assetPolicy, assetName, expiration) are unchanged. Checks value is non-decreasing and the previous bidder (if any) is refunded at least their bid amount.
- **End (no bids)**: After expiration (lower bound >= expiration), if `highestBidder` is empty bytes, only requires the seller's signature to reclaim.
- **End (with bids)**: After expiration, verifies the seller receives at least `highestBid` in lovelace and the winner receives the auctioned asset (checked via `ValuesLib.assetOf()`).
- **Withdraw**: Always returns `false` (not implemented).

## JuLC Features Demonstrated

- `@MultiValidator` with both `Purpose.MINT` and `Purpose.SPEND` entrypoints
- Sealed interface with `switch` expression (3 variants)
- `ScriptInfo.MintingScript` for accessing policy ID in mint entrypoint
- `IntervalLib.finiteUpperBound()` and `IntervalLib.finiteLowerBound()` for time-based validation
- `ValuesLib.assetOf()` for checking specific asset quantities
- `ValuesLib.lovelaceOf()` for lovelace extraction
- `OutputLib.getInlineDatum()` for reading inline datums
- `ContextsLib.signedBy()` for authorization checks
- `AddressLib.credentialHash()` for PKH extraction from addresses
- `Builtins.emptyByteString()` for sentinel "no bidder" value
- Record cast pattern: `(AuctionDatum)(Object) plutusData`
- For-each loops with break + accumulator pattern

## Files

| File | Description |
|------|-------------|
| `onchain/CfAuctionValidator.java` | Validator (on-chain logic) |
| `offchain/AuctionDemo.java` | Off-chain demo (requires Yaci DevKit) |
| `../../test/.../auction/onchain/CfAuctionValidatorTest.java` | Unit tests |
| `../../test/.../auction/AuctionIntegrationTest.java` | Integration tests (requires Yaci DevKit) |

## Running

```bash
# Unit tests
./gradlew test --tests "com.example.cftemplates.auction.onchain.*"

# Integration tests (requires Yaci DevKit)
./gradlew test --tests "com.example.cftemplates.auction.*IntegrationTest"

# Off-chain demo (requires Yaci DevKit)
./gradlew run -PmainClass=com.example.cftemplates.auction.offchain.AuctionDemo
```
