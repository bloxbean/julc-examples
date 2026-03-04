package com.example.lending.offchain;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.example.lending.onchain.CollateralLoan;
import com.example.offchain.YaciHelper;

import java.math.BigInteger;

/**
 * End-to-end demo: collateral loan validator with interest and liquidation.
 * <p>
 * Demonstrates:
 * 1. Lender offers loan (locks principal at script)
 * 2. Borrower takes loan (deposits collateral, receives principal)
 * 3. Borrower repays (pays principal + interest to lender, reclaims collateral)
 * <p>
 * Requires Yaci Devkit running locally.
 */
public class CollateralLoanDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("=== CollateralLoan E2E Demo ===\n");

        if (!YaciHelper.isYaciReachable()) {
            System.out.println("ERROR: Yaci Devkit not reachable at " + YaciHelper.YACI_BASE_URL);
            System.out.println("Start it with: yaci-cli devkit start");
            System.exit(1);
        }

        // 1. Load validator with @Param: 5% interest, 150% collateral threshold
        var script = JulcScriptLoader.load(CollateralLoan.class,
                BigIntPlutusData.of(500),    // interestRateBps = 5%
                BigIntPlutusData.of(15000)); // liquidationThresholdBps = 150%
        var scriptAddr = AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();
        System.out.println("Script address: " + scriptAddr);

        // 2. Create lender and borrower accounts
        var lender = new Account(Networks.testnet());
        var borrower = new Account(Networks.testnet());
        byte[] lenderPkh = lender.hdKeyPair().getPublicKey().getKeyHash();
        byte[] borrowerPkh = borrower.hdKeyPair().getPublicKey().getKeyHash();
        System.out.println("Lender: " + lender.baseAddress().substring(0, 20) + "...");
        System.out.println("Borrower: " + borrower.baseAddress().substring(0, 20) + "...");

        // Fund both
        YaciHelper.topUp(lender.baseAddress(), 1000);
        YaciHelper.topUp(borrower.baseAddress(), 1000);

        var backend = YaciHelper.createBackendService();
        var quickTx = new QuickTxBuilder(backend);

        // 3. Lender offers loan: 100 ADA principal, deadline far in future, 160 ADA collateral needed
        BigInteger principal = BigInteger.valueOf(100_000_000);
        BigInteger deadline = BigInteger.valueOf(System.currentTimeMillis() + 600_000); // +10 min
        BigInteger collateral = BigInteger.valueOf(160_000_000);

        // LoanDatum = Constr(0, [lender, borrower, principal, deadline, collateral])
        var datum = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(
                        new BytesPlutusData(lenderPkh),
                        new BytesPlutusData(borrowerPkh),
                        BigIntPlutusData.of(principal),
                        BigIntPlutusData.of(deadline),
                        BigIntPlutusData.of(collateral)))
                .build();

        System.out.println("\n--- Step 1: Lender offers 100 ADA loan ---");
        var lockTx = new Tx()
                .payToContract(scriptAddr, Amount.ada(100), datum)
                .from(lender.baseAddress());

        var lockResult = quickTx.compose(lockTx)
                .withSigner(SignerProviders.signerFrom(lender))
                .withRequiredSigners(lenderPkh)
                .complete();

        if (!lockResult.isSuccessful()) {
            System.out.println("FAILED to offer loan: " + lockResult);
            System.exit(1);
        }
        var lockTxHash = lockResult.getValue();
        System.out.println("Offer tx: " + lockTxHash);
        YaciHelper.waitForConfirmation(backend, lockTxHash);

        // 4. Borrower takes the loan
        var scriptUtxo = YaciHelper.findUtxo(backend, scriptAddr, lockTxHash);
        System.out.println("\n--- Step 2: Borrower takes loan (deposits 160 ADA collateral) ---");

        // TakeLoan = Constr(1, [])
        var takeRedeemer = ConstrPlutusData.of(1);

        var takeTx = new ScriptTx()
                .collectFrom(scriptUtxo, takeRedeemer)
                .payToAddress(borrower.enterpriseAddress(), Amount.ada(100))
                .payToContract(scriptAddr, Amount.ada(160), datum)
                .attachSpendingValidator(script);

        var takeResult = quickTx.compose(takeTx)
                .withSigner(SignerProviders.signerFrom(borrower))
                .withRequiredSigners(borrowerPkh)
                .feePayer(borrower.baseAddress())
                .collateralPayer(borrower.baseAddress())
                .complete();

        if (!takeResult.isSuccessful()) {
            System.out.println("FAILED to take loan: " + takeResult);
            System.exit(1);
        }
        System.out.println("Take tx: " + takeResult.getValue());
        YaciHelper.waitForConfirmation(backend, takeResult.getValue());

        // 5. Borrower repays: 105 ADA (principal + 5% interest)
        var collateralUtxo = YaciHelper.findUtxo(backend, scriptAddr, takeResult.getValue());
        System.out.println("\n--- Step 3: Borrower repays 105 ADA (100 + 5% interest) ---");

        // RepayLoan = Constr(2, [])
        var repayRedeemer = ConstrPlutusData.of(2);

        var repayTx = new ScriptTx()
                .collectFrom(collateralUtxo, repayRedeemer)
                .payToAddress(lender.enterpriseAddress(), Amount.ada(105))
                .attachSpendingValidator(script);

        var repayResult = quickTx.compose(repayTx)
                .withSigner(SignerProviders.signerFrom(borrower))
                .withRequiredSigners(borrowerPkh)
                .feePayer(borrower.baseAddress())
                .collateralPayer(borrower.baseAddress())
                .complete();

        if (!repayResult.isSuccessful()) {
            System.out.println("FAILED to repay: " + repayResult);
            System.exit(1);
        }
        System.out.println("Repay tx: " + repayResult.getValue());
        YaciHelper.waitForConfirmation(backend, repayResult.getValue());

        System.out.println("\n=== CollateralLoan Demo PASSED ===");
    }
}
