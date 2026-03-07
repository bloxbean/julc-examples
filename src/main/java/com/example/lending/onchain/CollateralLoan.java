package com.example.lending.onchain;

import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.Param;
import com.bloxbean.cardano.julc.stdlib.annotation.SpendingValidator;
import com.bloxbean.cardano.julc.stdlib.lib.ContextsLib;
import com.bloxbean.cardano.julc.stdlib.lib.IntervalLib;
import com.bloxbean.cardano.julc.stdlib.lib.OutputLib;

import java.math.BigInteger;
import java.util.Optional;

/**
 * Collateral loan validator — DeFi lending with interest and liquidation.
 * <p>
 * A lender offers a loan at the script address. A borrower takes it by depositing
 * collateral. The borrower must repay principal + interest before the deadline.
 * After the deadline, the lender can liquidate the collateral.
 * <p>
 * Features demonstrated:
 * - @Param (BigInteger x2): interest rate and liquidation threshold
 * - 4-variant sealed interface switch
 * - BigInteger arithmetic (multiply, divide, add)
 * - IntervalLib time checks (finiteLowerBound, finiteUpperBound)
 * - OutputLib.lovelacePaidTo() for payment verification
 * - PubKeyHash.of() and Address construction
 * - ContextsLib.trace()
 */
@SpendingValidator
public class CollateralLoan {

    @Param
    public static BigInteger interestRateBps;          // e.g. 500 = 5%

    @Param
    public static BigInteger liquidationThresholdBps;  // e.g. 15000 = 150%

    public record LoanDatum(
            byte[] lender,
            byte[] borrower,
            BigInteger principal,
            BigInteger deadline,
            BigInteger collateral
    ) {}

    public sealed interface LoanAction permits OfferLoan, TakeLoan, RepayLoan, Liquidate {}
    public record OfferLoan() implements LoanAction {}
    public record TakeLoan() implements LoanAction {}
    public record RepayLoan() implements LoanAction {}
    public record Liquidate() implements LoanAction {}

    @Entrypoint
    public static boolean validate(LoanDatum datum, LoanAction redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        ContextsLib.trace("CollateralLoan validate");
        return switch (redeemer) {
            case OfferLoan o -> validateOfferLoan(datum, txInfo);
            case TakeLoan t -> validateTakeLoan(datum, txInfo);
            case RepayLoan r -> validateRepayLoan(datum, txInfo);
            case Liquidate l -> validateLiquidate(datum, txInfo);
        };
    }

    static boolean validateOfferLoan(LoanDatum datum, TxInfo txInfo) {
        ContextsLib.trace("Offer loan");
        boolean lenderSigned = txInfo.signatories().contains(PubKeyHash.of(datum.lender()));
        BigInteger txUpperBound = IntervalLib.finiteUpperBound(txInfo.validRange());
        // -1 sentinel means upper bound is infinite (PosInf): deadline cannot be verified as in future
        boolean deadlineInFuture = txUpperBound.compareTo(BigInteger.ZERO) >= 0
                && txUpperBound.compareTo(datum.deadline()) < 0;
        return lenderSigned && deadlineInFuture;
    }

    static boolean validateTakeLoan(LoanDatum datum, TxInfo txInfo) {
        ContextsLib.trace("Take loan");
        boolean borrowerSigned = txInfo.signatories().contains(PubKeyHash.of(datum.borrower()));
        boolean collateralOk = hasEnoughCollateral(datum.collateral(), datum.principal());
        Address borrowerAddr = toAddress(datum.borrower());
        BigInteger paidToBorrower = OutputLib.lovelacePaidTo(txInfo.outputs(), borrowerAddr);
        boolean principalPaid = paidToBorrower.compareTo(datum.principal()) >= 0;
        return borrowerSigned && collateralOk && principalPaid;
    }

    static boolean validateRepayLoan(LoanDatum datum, TxInfo txInfo) {
        ContextsLib.trace("Repay loan");
        boolean borrowerSigned = txInfo.signatories().contains(PubKeyHash.of(datum.borrower()));
        BigInteger txLowerBound = IntervalLib.finiteLowerBound(txInfo.validRange());
        boolean beforeDeadline = txLowerBound.compareTo(datum.deadline()) < 0;
        Address lenderAddr = toAddress(datum.lender());
        BigInteger paidToLender = OutputLib.lovelacePaidTo(txInfo.outputs(), lenderAddr);
        BigInteger required = totalRepayment(datum.principal());
        boolean repaymentOk = paidToLender.compareTo(required) >= 0;
        return borrowerSigned && beforeDeadline && repaymentOk;
    }

    static boolean validateLiquidate(LoanDatum datum, TxInfo txInfo) {
        ContextsLib.trace("Liquidate");
        boolean lenderSigned = txInfo.signatories().contains(PubKeyHash.of(datum.lender()));
        BigInteger txLowerBound = IntervalLib.finiteLowerBound(txInfo.validRange());
        boolean afterDeadline = txLowerBound.compareTo(datum.deadline()) >= 0;
        return lenderSigned && afterDeadline;
    }

    static Address toAddress(byte[] pkh) {
        return new Address(
                new Credential.PubKeyCredential(PubKeyHash.of(pkh)),
                Optional.empty());
    }

    static boolean hasEnoughCollateral(BigInteger collateral, BigInteger principal) {
        // collateral * 10000 >= principal * liquidationThresholdBps
        return collateral.multiply(BigInteger.valueOf(10000)).compareTo(
                principal.multiply(liquidationThresholdBps)) >= 0;
    }

    static BigInteger totalRepayment(BigInteger principal) {
        return principal.add(calculateInterest(principal));
    }

    static BigInteger calculateInterest(BigInteger principal) {
        return principal.multiply(interestRateBps).divide(BigInteger.valueOf(10000));
    }
}
