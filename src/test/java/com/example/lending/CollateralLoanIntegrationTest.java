package com.example.lending;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.example.lending.onchain.CollateralLoan;
import com.example.offchain.YaciHelper;
import org.junit.jupiter.api.*;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration test for CollateralLoan — requires Yaci DevKit running.
 * Tests are skipped (not failed) when Yaci is unavailable.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CollateralLoanIntegrationTest {

    static boolean yaciAvailable;
    static Account lender, borrower;
    static PlutusV3Script script;
    static BackendService backend;
    static QuickTxBuilder quickTx;
    static String scriptAddr;
    static String offerTxHash;
    static String takeTxHash;

    static ConstrPlutusData datum;

    @BeforeAll
    static void setup() throws Exception {
        yaciAvailable = YaciHelper.isYaciReachable();
        if (!yaciAvailable) return;

        script = JulcScriptLoader.load(CollateralLoan.class,
                BigIntPlutusData.of(500),
                BigIntPlutusData.of(15000));
        scriptAddr = AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();

        lender = new Account(Networks.testnet());
        borrower = new Account(Networks.testnet());

        YaciHelper.topUp(lender.baseAddress(), 1000);
        YaciHelper.topUp(borrower.baseAddress(), 1000);

        backend = YaciHelper.createBackendService();
        quickTx = new QuickTxBuilder(backend);

        byte[] lenderPkh = lender.hdKeyPair().getPublicKey().getKeyHash();
        byte[] borrowerPkh = borrower.hdKeyPair().getPublicKey().getKeyHash();
        BigInteger deadline = BigInteger.valueOf(System.currentTimeMillis() + 600_000);

        datum = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(
                        new BytesPlutusData(lenderPkh),
                        new BytesPlutusData(borrowerPkh),
                        BigIntPlutusData.of(100_000_000),
                        BigIntPlutusData.of(deadline),
                        BigIntPlutusData.of(160_000_000)))
                .build();
    }

    @Test
    @Order(1)
    void step1_lenderOffersLoan() throws Exception {
        assumeTrue(yaciAvailable, "Yaci DevKit not available");

        byte[] lenderPkh = lender.hdKeyPair().getPublicKey().getKeyHash();

        var lockTx = new Tx()
                .payToContract(scriptAddr, Amount.ada(100), datum)
                .from(lender.baseAddress());

        var result = quickTx.compose(lockTx)
                .withSigner(SignerProviders.signerFrom(lender))
                .withRequiredSigners(lenderPkh)
                .complete();

        assertTrue(result.isSuccessful(), "Offer tx should succeed: " + result);
        offerTxHash = result.getValue();
        YaciHelper.waitForConfirmation(backend, offerTxHash);
        System.out.println("Step 1 OK: Lender offered loan, tx=" + offerTxHash);
    }

    @Test
    @Order(2)
    void step2_borrowerTakesLoan() throws Exception {
        assumeTrue(yaciAvailable, "Yaci DevKit not available");
        assertNotNull(offerTxHash, "Step 1 must complete first");

        byte[] borrowerPkh = borrower.hdKeyPair().getPublicKey().getKeyHash();
        // Use enterprise address (no staking cred) so validator's toAddress() comparison works
        String borrowerEntAddr = AddressProvider.getEntAddress(
                borrower.hdKeyPair().getPublicKey(), Networks.testnet()).toBech32();
        var scriptUtxo = YaciHelper.findUtxo(backend, scriptAddr, offerTxHash);
        var takeRedeemer = ConstrPlutusData.of(1); // TakeLoan

        var takeTx = new ScriptTx()
                .collectFrom(scriptUtxo, takeRedeemer)
                .payToAddress(borrowerEntAddr, Amount.ada(100))
                .payToContract(scriptAddr, Amount.ada(160), datum)
                .attachSpendingValidator(script);

        var result = quickTx.compose(takeTx)
                .withSigner(SignerProviders.signerFrom(borrower))
                .withRequiredSigners(borrowerPkh)
                .feePayer(borrower.baseAddress())
                .collateralPayer(borrower.baseAddress())
                .complete();

        assertTrue(result.isSuccessful(), "Take tx should succeed: " + result);
        takeTxHash = result.getValue();
        YaciHelper.waitForConfirmation(backend, takeTxHash);
        System.out.println("Step 2 OK: Borrower took loan, tx=" + takeTxHash);
    }

    @Test
    @Order(3)
    void step3_borrowerRepaysLoan() throws Exception {
        assumeTrue(yaciAvailable, "Yaci DevKit not available");
        assertNotNull(takeTxHash, "Step 2 must complete first");

        byte[] borrowerPkh = borrower.hdKeyPair().getPublicKey().getKeyHash();
        // Use enterprise address (no staking cred) so validator's toAddress() comparison works
        String lenderEntAddr = AddressProvider.getEntAddress(
                lender.hdKeyPair().getPublicKey(), Networks.testnet()).toBech32();
        var collateralUtxo = YaciHelper.findUtxo(backend, scriptAddr, takeTxHash);
        var repayRedeemer = ConstrPlutusData.of(2); // RepayLoan

        var repayTx = new ScriptTx()
                .collectFrom(collateralUtxo, repayRedeemer)
                .payToAddress(lenderEntAddr, Amount.ada(105))
                .attachSpendingValidator(script);

        var result = quickTx.compose(repayTx)
                .withSigner(SignerProviders.signerFrom(borrower))
                .withRequiredSigners(borrowerPkh)
                .feePayer(borrower.baseAddress())
                .collateralPayer(borrower.baseAddress())
                .complete();

        assertTrue(result.isSuccessful(), "Repay tx should succeed: " + result);
        YaciHelper.waitForConfirmation(backend, result.getValue());
        System.out.println("Step 3 OK: Borrower repaid loan, tx=" + result.getValue());
    }

    @Test
    @Order(4)
    void step4_verifyFinalBalances() throws Exception {
        assumeTrue(yaciAvailable, "Yaci DevKit not available");

        var lenderUtxos = backend.getUtxoService().getUtxos(lender.baseAddress(), 100, 1);
        assertTrue(lenderUtxos.isSuccessful(), "Should query lender UTxOs");
        assertFalse(lenderUtxos.getValue().isEmpty(), "Lender should have UTxOs (received repayment)");

        var borrowerUtxos = backend.getUtxoService().getUtxos(borrower.baseAddress(), 100, 1);
        assertTrue(borrowerUtxos.isSuccessful(), "Should query borrower UTxOs");
        assertFalse(borrowerUtxos.getValue().isEmpty(), "Borrower should have UTxOs (reclaimed collateral)");

        System.out.println("Step 4 OK: Lender has " + lenderUtxos.getValue().size() +
                " UTxOs, Borrower has " + borrowerUtxos.getValue().size() + " UTxOs");
    }
}
