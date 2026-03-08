# Upgradeable Proxy

A state-token-based upgradeable proxy pattern with three validators: a proxy validator that holds state and delegates validation to external script logic, and two interchangeable logic scripts (V1 and V2) that implement different validation rules via the withdrawal delegation pattern.

## Overview

This example implements an upgradeable smart contract using a proxy pattern. CfProxyValidator manages a state token NFT whose datum contains a pointer to the currently active script logic. Spending and minting operations are delegated to the logic script via the withdrawal mechanism -- the proxy checks that a withdrawal from the pointed-to script exists in the transaction. CfScriptLogicV1 and CfScriptLogicV2 are two versions of the logic that validate differently. The owner can update the script pointer in the proxy datum to upgrade from V1 to V2 without moving funds.

## Protocol Flow

```
1. Init Proxy         Owner mints a one-shot state token (name =
                      sha3_256(seedTxHash || indexString)). The
                      token is sent to the proxy script address with
                      a ProxyDatum containing the script pointer
                      (V1 logic hash) and the owner's PKH.

2. Proxy Spend        Non-state-token UTXOs at the proxy are spent
                      via ProxySpend. The state token must appear in
                      a reference input. A withdrawal from the
                      script_pointer address must exist (delegation).

3. Proxy Mint         Non-state-token minting via ProxyMint. The
                      state token must appear in a reference input.
                      A withdrawal from the script_pointer must exist.

4. Update             The state token UTXO is spent via Update.
                      Old owner must sign. If owner changes, new
                      owner must also sign. The state token continues
                      to an output with the updated ProxyDatum.

5. Logic Withdraw     V1/V2 validate via WITHDRAW entrypoint:
  (V1)                - Mint: 1 token, matching name, qty 1.
                      - Spend: password == "Hello, World!".
  (V2)                - Mint: 1 token, name != invalidTokenName.
                      - Spend: always valid (no password check).

6. Logic Certify      V1/V2 allow staking credential registration
                      (RegStaking) via CERTIFY entrypoint.
```

## Data Types

### Records

| Record | Fields | Validator | Description |
|--------|--------|-----------|-------------|
| `ProxyDatum` | `byte[] scriptPointer`, `byte[] scriptOwner` | CfProxyValidator | State token datum: points to active logic script + owner |
| `V1Redeemer` | `byte[] tokenName`, `byte[] password` | CfScriptLogicV1 | Withdrawal redeemer for V1 validation |
| `V2Redeemer` | `byte[] invalidTokenName` | CfScriptLogicV2 | Withdrawal redeemer for V2 validation |

### Sealed Interfaces

**ProxyMintAction** (CfProxyValidator mint redeemer)

| Variant | Tag | Description |
|---------|-----|-------------|
| `Init` | 0 | One-shot state token initialization |
| `ProxyMint` | 1 | Delegated minting (non-state-token) |

**ProxySpendAction** (CfProxyValidator spend redeemer)

| Variant | Tag | Description |
|---------|-----|-------------|
| `Update` | 0 | Update the proxy datum (script pointer or owner) |
| `ProxySpend` | 1 | Delegated spending (non-state-token UTXOs) |

### @Param Fields

**CfProxyValidator**

| Field | Type | Description |
|-------|------|-------------|
| `seedTxHash` | `byte[]` | Transaction hash of the seed UTXO (one-shot) |
| `seedIndex` | `BigInteger` | Output index of the seed UTXO (one-shot) |

**CfScriptLogicV1 / CfScriptLogicV2**

| Field | Type | Description |
|-------|------|-------------|
| `proxyPolicyId` | `byte[]` | Policy ID of the proxy validator (links logic to proxy) |

## Validator Logic

### CfProxyValidator

- **MINT Init**: Seed UTXO must be consumed (one-shot). Exactly 1 state token minted with the computed name (sha3_256 of seed). Only 1 token type under this policy. Output to own script with ProxyDatum. Script owner from datum must sign. Only 1 output to the script address.
- **MINT ProxyMint**: State token must NOT be minted. A reference input at own script address must contain the state token. A withdrawal from the ProxyDatum's `scriptPointer` must exist in the transaction.
- **SPEND Update**: State token must be in the spent input. Continuing output at own script with state token. Old owner must sign. If owner changes, new owner must also sign.
- **SPEND ProxySpend**: State token must NOT be in the spent input. A reference input at own script address must contain the state token. A withdrawal from the `scriptPointer` must exist.

### CfScriptLogicV1

- **WITHDRAW**: At least one of minting or spending must involve the proxy policy. Mint validation: exactly 1 token with matching name and qty 1. Spend validation: password field must equal "Hello, World!" (built byte-by-byte via `consByteString`).
- **CERTIFY**: Only allows `RegStaking` certificate actions.

### CfScriptLogicV2

- **WITHDRAW**: At least one of minting or spending must involve the proxy policy. Mint validation: exactly 1 token with qty 1, token name must NOT equal `invalidTokenName`. Spend validation: always passes (no password check).
- **CERTIFY**: Only allows `RegStaking` certificate actions.

## JuLC Features Demonstrated

- `@MultiValidator` with `Purpose.MINT`, `Purpose.SPEND`, `Purpose.WITHDRAW`, and `Purpose.CERTIFY`
- `@Param` for cross-validator linking (proxy policy ID)
- One-shot minting with deterministic token name (`CryptoLib.sha3_256`)
- `ByteStringLib.intToDecimalString()` for integer-to-string conversion
- State token pattern with reference inputs for delegation
- Withdrawal delegation pattern for upgradeable logic
- `ValuesLib.assetOf()` and `ValuesLib.countTokensWithQty()` for token checks
- `ValuesLib.containsPolicy()` and `ValuesLib.findTokenName()` for policy queries
- `ContextsLib.ownHash()` with `Builtins.unBData()` unwrapping
- `OutputLib.getInlineDatum()` for inline datum extraction
- `AddressLib.credentialHash()` for credential matching
- `Builtins.consByteString()` for byte-level string construction
- `Builtins.unConstrData()` / `Builtins.fstPair()` / `Builtins.sndPair()` for low-level data deconstruction (withdrawal credential check)
- Switch expression on `TxCert` variants for certificate validation

## Files

| File | Description |
|------|-------------|
| `onchain/CfProxyValidator.java` | Proxy validator: state token management + delegation |
| `onchain/CfScriptLogicV1.java` | Logic V1: withdrawal/certify validation (password-gated) |
| `onchain/CfScriptLogicV2.java` | Logic V2: withdrawal/certify validation (upgraded rules) |
| `offchain/ProxyDemo.java` | Off-chain demo (requires Yaci DevKit) |
| `../../test/.../upgradeableproxy/onchain/CfProxyValidatorTest.java` | Unit tests for proxy validator |
| `../../test/.../upgradeableproxy/ProxyIntegrationTest.java` | Integration tests (requires Yaci DevKit) |

## Running

```bash
# Unit tests
./gradlew test --tests "com.example.cftemplates.upgradeableproxy.onchain.*"

# Integration tests (requires Yaci DevKit)
./gradlew test --tests "com.example.cftemplates.upgradeableproxy.*IntegrationTest"

# Off-chain demo (requires Yaci DevKit)
./gradlew run -PmainClass=com.example.cftemplates.upgradeableproxy.offchain.ProxyDemo
```
