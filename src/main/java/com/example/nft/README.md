# CIP-68 NFT (Reference NFT + User NFT)

A CIP-68 compliant NFT multi-validator that manages reference tokens (holding on-chain metadata) and user tokens.

## Overview

This validator implements the [CIP-68](https://cips.cardano.org/cip/CIP-0068) NFT standard. Minting produces a pair of tokens: a reference token locked at the script address with metadata as an inline datum, and a user token sent to the minter's wallet. The spend entrypoint guards metadata updates and reference token burning.

## Protocol Flow

```
1. Mint NFT       Mint both reference and user tokens in a single
                  transaction. The reference token must be locked at
                  the script address with an inline datum containing
                  CIP-68 metadata. Both quantities must be exactly 1.

2. Hold / Trade   The user token can be freely transferred. The
                  reference token stays locked at the script address,
                  serving as on-chain metadata storage.

3. Update         Update the reference UTxO's inline datum with new
   Metadata       metadata. A continuing output at the script address
                  must hold the reference token with an inline datum.

4. Burn           Burn both tokens. The mint field must show -1 for
                  both reference and user token names.
```

## Data Types

### NftMetadata (record)

| Field      | Type         | Description                     |
|------------|--------------|---------------------------------|
| `metadata` | `PlutusData` | Arbitrary metadata (CIP-68 map) |
| `version`  | `BigInteger` | Metadata schema version          |

### MintAction (sealed interface — MINT redeemer)

| Variant    | Description                                  |
|------------|----------------------------------------------|
| `MintNft`  | Mint a new CIP-68 NFT pair                   |
| `BurnNft`  | Burn both reference and user tokens           |

### RefAction (sealed interface — SPEND redeemer)

| Variant          | Description                              |
|------------------|------------------------------------------|
| `UpdateMetadata` | Update metadata on the reference UTxO    |
| `BurnReference`  | Burn the reference token                  |

### @Param Fields

| Parameter        | Type     | Description                                       |
|------------------|----------|---------------------------------------------------|
| `refTokenName`   | `byte[]` | Pre-computed CIP-68 reference token name (000643b0 ++ name) |
| `userTokenName`  | `byte[]` | Pre-computed CIP-68 user token name (000de140 ++ name)      |

## Validator Logic

- **MintNft**: Verifies both reference and user tokens are minted with quantity 1. Checks that at least one output is at the script address, holds the reference token, and has an inline datum.
- **BurnNft**: Verifies both tokens are burned (quantity -1 each in the mint field).
- **UpdateMetadata**: Checks that a continuing output exists at the script address holding the reference token with an inline datum.
- **BurnReference**: Checks the reference token is burned (quantity -1 in the mint field).

## JuLC Features Demonstrated

- `@MultiValidator` with separate MINT and SPEND entrypoints
- `@Entrypoint(purpose = Purpose.MINT)` and `@Entrypoint(purpose = Purpose.SPEND)`
- `@Param(byte[])` for pre-computed CIP-68 token names
- Two sealed interfaces (MintAction for minting, RefAction for spending)
- `Credential` switch (ScriptCredential / PubKeyCredential)
- `OutputDatum` switch (3 variants: NoOutputDatum, OutputDatumHash, OutputDatumInline)
- `list.any()` HOF lambda with block body
- `Value.assetOf()` via `ValuesLib.assetOf()` for mint quantity checks
- `ScriptInfo.MintingScript` / `ScriptInfo.SpendingScript` casts
- `ContextsLib.trace()` for on-chain debug traces

## Files

| File | Description |
|------|-------------|
| `onchain/Cip68Nft.java` | Multi-validator (on-chain logic) |
| `offchain/Cip68NftDemo.java` | Off-chain demo (requires Yaci Devkit) |
| `../../test/.../nft/Cip68NftTest.java` | Unit tests (UPLC + JulcEval proxy) |

## Running

```bash
# Unit tests (no external dependencies)
./gradlew test --tests "com.example.nft.*"

# Off-chain demo (requires Yaci Devkit)
./gradlew run -PmainClass=com.example.nft.offchain.Cip68NftDemo
```
