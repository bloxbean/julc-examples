# Simple Wallet

A two-validator wallet pattern combining intent-based payment minting with a separate funds-holding validator, enabling programmable payment intents that are executed atomically.

## Overview

This example implements a simple wallet using two cooperating validators. CfSimpleWalletValidator handles minting/burning of INTENT_MARKER tokens and guards intent UTXOs at the wallet script address. CfWalletFundsValidator holds the actual funds and validates payment execution by checking intent UTXOs, verifying recipient payments, and ensuring intent markers are burned. The owner controls both validators via signature checks.

## Protocol Flow

```
1. Deposit           Owner sends ADA to the funds validator address.

2. Mint Intent       Owner mints an INTENT_MARKER token via the wallet
                     validator. Exactly 1 token is minted and sent to
                     the wallet script address with a PaymentIntent
                     inline datum (recipient, amount, data).

3. Execute Payment   The funds validator spends a funds UTXO using the
                     ExecuteTx redeemer. It locates the intent UTXO
                     from the wallet script, extracts the PaymentIntent
                     datum, verifies the recipient receives at least the
                     specified amount, and confirms the intent marker
                     is burned.

4. Withdraw          Owner can directly withdraw funds using the
                     WithdrawFunds redeemer (owner signature only).

5. Burn Intent       Owner burns an intent marker via the wallet
                     validator's BurnIntent redeemer (1 token burned).
```

## Data Types

### Records

| Record | Fields | Description |
|--------|--------|-------------|
| `PaymentIntent` | `PlutusData recipient`, `BigInteger lovelaceAmt`, `byte[] data` | Payment intent datum locked at wallet script (defined in both validators) |

### Sealed Interfaces

**WalletAction** (CfSimpleWalletValidator mint redeemer)

| Variant | Tag | Description |
|---------|-----|-------------|
| `MintIntent` | 0 | Mint a new intent marker token |
| `BurnIntent` | 1 | Burn an existing intent marker token |

**FundsAction** (CfWalletFundsValidator spend redeemer)

| Variant | Tag | Description |
|---------|-----|-------------|
| `ExecuteTx` | 0 | Execute a payment intent |
| `WithdrawFunds` | 1 | Owner withdraws funds directly |

### @Param Fields

**CfSimpleWalletValidator**

| Field | Type | Description |
|-------|------|-------------|
| `owner` | `byte[]` | Public key hash of the wallet owner |

**CfWalletFundsValidator**

| Field | Type | Description |
|-------|------|-------------|
| `owner` | `byte[]` | Public key hash of the wallet owner |
| `walletPolicyId` | `byte[]` | Policy ID of the CfSimpleWalletValidator (links the two validators) |

## Validator Logic

### CfSimpleWalletValidator

- **MINT MintIntent**: Owner must sign. Exactly 1 INTENT token minted (qty = 1). An output at the own script address must contain the minted token and an inline datum (PaymentIntent).
- **MINT BurnIntent**: Owner must sign. Exactly 1 INTENT token burned (qty = -1).
- **SPEND**: Owner signature check only. Used when consuming intent UTXOs during payment execution.

### CfWalletFundsValidator

- **SPEND ExecuteTx**: Owner must sign. Finds the intent input from the wallet script (by checking credential hash matches walletPolicyId and value contains wallet policy tokens). Extracts PaymentIntent datum, sums lovelace paid to the recipient address across all outputs, verifies payment meets the specified amount. Intent marker must be burned (1 token with qty -1).
- **SPEND WithdrawFunds**: Owner signature check only. Allows direct fund withdrawal.

## JuLC Features Demonstrated

- `@MultiValidator` with both `Purpose.MINT` and `Purpose.SPEND` entrypoints
- `@Param` for script parameterization linking two validators
- Sealed interfaces for typed redeemer dispatch (`WalletAction`, `FundsAction`)
- `ValuesLib.countTokensWithQty()` for counting minted/burned tokens
- `ValuesLib.containsPolicy()` for policy presence checks
- `ValuesLib.lovelaceOf()` for safe lovelace extraction
- `ContextsLib.signedBy()` for owner signature verification
- `OutputLib.getInlineDatum()` for inline datum extraction
- `AddressLib.credentialHash()` for credential-based address matching
- Record cast pattern: `(PaymentIntent)(Object) datumData`
- `Builtins.equalsData()` for address equality comparison
- For-each loop with break + accumulator pattern

## Files

| File | Description |
|------|-------------|
| `onchain/CfSimpleWalletValidator.java` | Wallet validator: mint/burn intent tokens + spend intent UTXOs |
| `onchain/CfWalletFundsValidator.java` | Funds validator: execute payment intents + owner withdrawal |
| `offchain/SimpleWalletDemo.java` | Off-chain demo (requires Yaci DevKit) |
| `../../test/.../simplewallet/onchain/CfSimpleWalletValidatorTest.java` | Unit tests for wallet validator |
| `../../test/.../simplewallet/onchain/CfWalletFundsValidatorTest.java` | Unit tests for funds validator |
| `../../test/.../simplewallet/SimpleWalletIntegrationTest.java` | Integration tests (requires Yaci DevKit) |

## Running

```bash
# Unit tests
./gradlew test --tests "com.example.cftemplates.simplewallet.onchain.*"

# Integration tests (requires Yaci DevKit)
./gradlew test --tests "com.example.cftemplates.simplewallet.*IntegrationTest"

# Off-chain demo (requires Yaci DevKit)
./gradlew run -PmainClass=com.example.cftemplates.simplewallet.offchain.SimpleWalletDemo
```
