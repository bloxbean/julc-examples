# Collateral Loan

A DeFi lending protocol validator with interest calculation, collateral requirements, and time-based liquidation.

## Overview

This validator implements a peer-to-peer lending protocol. A lender offers a loan by locking principal at the script address. A borrower takes the loan by depositing sufficient collateral. The borrower must repay principal plus interest before a deadline. After the deadline, the lender can liquidate the collateral.

## Protocol Flow

```
1. Offer Loan     Lender locks principal at the script address with
                  a LoanDatum. Requires lender's signature and the
                  deadline must be in the future.

2. Take Loan      Borrower takes the loan. Must have enough collateral
                  (>= liquidation threshold %), borrower signs, and
                  principal is paid to borrower's address.

3. Repay Loan     Borrower repays principal + interest to the lender
                  before the deadline. Requires borrower's signature.

4. Liquidate      After the deadline, lender can liquidate the
                  collateral. Requires lender's signature.
```

## Data Types

### LoanDatum (record)

| Field        | Type         | Description                         |
|--------------|--------------|-------------------------------------|
| `lender`     | `byte[]`     | Lender's public key hash            |
| `borrower`   | `byte[]`     | Borrower's public key hash          |
| `principal`  | `BigInteger` | Loan amount in lovelace             |
| `deadline`   | `BigInteger` | Repayment deadline (POSIX time ms)  |
| `collateral` | `BigInteger` | Collateral amount in lovelace       |

### LoanAction (sealed interface redeemer)

| Variant      | Description                                      |
|--------------|--------------------------------------------------|
| `OfferLoan`  | Lender offers the loan (locks principal)          |
| `TakeLoan`   | Borrower takes the loan (deposits collateral)     |
| `RepayLoan`  | Borrower repays principal + interest              |
| `Liquidate`  | Lender liquidates collateral after deadline        |

### @Param Fields

| Parameter                | Type         | Description                      |
|--------------------------|--------------|----------------------------------|
| `interestRateBps`        | `BigInteger` | Interest rate in basis points (e.g. 500 = 5%) |
| `liquidationThresholdBps`| `BigInteger` | Collateral threshold in basis points (e.g. 15000 = 150%) |

## Validator Logic

- **OfferLoan**: Verifies the lender signed the transaction and the deadline is in the future (upper bound of validity range < deadline).
- **TakeLoan**: Checks borrower's signature, that collateral meets the threshold (`collateral * 10000 >= principal * liquidationThresholdBps`), and that principal is paid to the borrower's address.
- **RepayLoan**: Verifies borrower's signature, the transaction is before the deadline, and the lender receives at least `principal + interest` (where `interest = principal * interestRateBps / 10000`).
- **Liquidate**: Checks lender's signature and that the transaction is at or after the deadline.

## JuLC Features Demonstrated

- `@Param` for parameterized validators (interest rate + liquidation threshold)
- 4-variant sealed interface with `switch` expression
- `BigInteger` arithmetic (`.multiply()`, `.divide()`, `.add()`, `.compareTo()`)
- `IntervalLib.finiteLowerBound()` / `IntervalLib.finiteUpperBound()` for time checks
- `OutputLib.lovelacePaidTo()` for payment verification
- `PubKeyHash.of()` and `Address` construction
- `ContextsLib.trace()` for on-chain debug traces

## Files

| File | Description |
|------|-------------|
| `onchain/CollateralLoan.java` | Validator (on-chain logic) |
| `offchain/CollateralLoanDemo.java` | Off-chain demo (requires Yaci Devkit) |
| `../../test/.../lending/CollateralLoanTest.java` | Unit tests (Direct Java + UPLC + JulcEval proxy) |

## Running

```bash
# Unit tests (no external dependencies)
./gradlew test --tests "com.example.lending.*"

# Off-chain demo (requires Yaci Devkit)
./gradlew run -PmainClass=com.example.lending.offchain.CollateralLoanDemo
```
