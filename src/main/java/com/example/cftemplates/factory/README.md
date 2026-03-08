# Factory

A factory pattern with two cooperating validators: a factory validator that manages a registry of products via a one-shot marker NFT, and a product validator that governs individual product tokens linked back to the factory.

## Overview

This example implements the factory design pattern where CfFactoryValidator acts as a registry that tracks created products. The factory marker NFT is minted once using a one-shot pattern (consuming a seed UTXO), and each product creation spends the factory UTXO to update the products list in its datum. CfProductValidator governs individual product tokens, requiring the factory marker to be spent during minting to prove authorization by the factory.

## Protocol Flow

```
1. Mint Factory       Owner mints a one-shot FACTORY_MARKER NFT by
                      consuming a specific seed UTXO. The marker is
                      sent to the factory script address with a
                      FactoryDatum containing an empty products list.

2. Create Product     Owner spends the factory UTXO (CreateProduct
                      redeemer). The factory marker must be in the
                      input and continue to an output at the same
                      script address. A product token is minted via
                      the product validator, and the factory datum is
                      updated to include the new product policy ID.

3. Product Mint       The product validator checks that the factory
                      marker is being spent (factory validates the
                      creation), exactly 1 product token is minted,
                      and the token is sent to a script address with
                      a ProductDatum. Owner must sign.

4. Product Spend      Owner can spend product UTXOs with signature
                      authorization only.
```

## Data Types

### Records

| Record | Fields | Validator | Description |
|--------|--------|-----------|-------------|
| `FactoryDatum` | `JulcList<byte[]> products` | CfFactoryValidator | Tracks list of created product policy IDs |
| `CreateProduct` | `byte[] productPolicyId`, `byte[] productId` | CfFactoryValidator | Spend redeemer for creating a new product |
| `ProductDatum` | `byte[] tag` | CfProductValidator | Metadata tag for a product instance |

### @Param Fields

**CfFactoryValidator**

| Field | Type | Description |
|-------|------|-------------|
| `owner` | `byte[]` | Public key hash of the factory owner |
| `seedTxHash` | `byte[]` | Transaction hash of the seed UTXO (one-shot) |
| `seedIndex` | `BigInteger` | Output index of the seed UTXO (one-shot) |

**CfProductValidator**

| Field | Type | Description |
|-------|------|-------------|
| `owner` | `byte[]` | Public key hash of the product owner |
| `factoryMarkerPolicy` | `byte[]` | Policy ID of the factory marker (links product to factory) |
| `productId` | `byte[]` | Unique identifier for this product token |

## Validator Logic

### CfFactoryValidator

- **MINT**: Owner must sign. Seed UTXO must be consumed (one-shot pattern). Exactly 1 token minted under the factory policy.
- **SPEND CreateProduct**: Owner must sign. Factory marker token must be present in the spent input. A continuing output at the same script address must contain the marker token. The product token (identified by `productPolicyId` and `productId` from the redeemer) must be minted (qty = 1). The continuing output's FactoryDatum must include the new product policy ID in its products list.

### CfProductValidator

- **MINT**: Owner must sign. Exactly 1 product token with the configured `productId` must be minted. The factory marker must be spent (an input contains the factory marker policy token). An output at a script address must contain the product token with an inline ProductDatum.
- **SPEND**: Owner signature check only.

## JuLC Features Demonstrated

- `@MultiValidator` with both `Purpose.MINT` and `Purpose.SPEND` entrypoints
- `@Param` for script parameterization with cross-validator linking
- One-shot minting pattern (seed UTXO consumption)
- `JulcList<byte[]>` for on-chain list storage in datum
- `ValuesLib.countTokensWithQty()` for mint quantity verification
- `ValuesLib.containsPolicy()` for marker token presence checks
- `ValuesLib.assetOf()` for specific asset quantity lookup
- `AddressLib.isScriptAddress()` for script address detection
- `AddressLib.credentialHash()` for credential-based matching
- `OutputLib.getInlineDatum()` for inline datum extraction
- `Builtins.equalsData()` for data-level byte comparison
- `Builtins.bData()` for wrapping bytes as PlutusData for comparison
- Record cast pattern: `(FactoryDatum)(Object) datumData`

## Files

| File | Description |
|------|-------------|
| `onchain/CfFactoryValidator.java` | Factory validator: one-shot marker minting + product creation |
| `onchain/CfProductValidator.java` | Product validator: product token minting + owner-gated spending |
| `offchain/FactoryDemo.java` | Off-chain demo (requires Yaci DevKit) |
| `../../test/.../factory/onchain/CfFactoryValidatorTest.java` | Unit tests for factory validator |
| `../../test/.../factory/onchain/CfProductValidatorTest.java` | Unit tests for product validator |
| `../../test/.../factory/FactoryIntegrationTest.java` | Integration tests (requires Yaci DevKit) |

## Running

```bash
# Unit tests
./gradlew test --tests "com.example.cftemplates.factory.onchain.*"

# Integration tests (requires Yaci DevKit)
./gradlew test --tests "com.example.cftemplates.factory.*IntegrationTest"

# Off-chain demo (requires Yaci DevKit)
./gradlew run -PmainClass=com.example.cftemplates.factory.offchain.FactoryDemo
```
