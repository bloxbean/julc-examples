package com.example.cftemplates.escrow;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.EvaluationResult;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.api.DefaultProtocolParamsSupplier;
import com.bloxbean.cardano.client.backend.api.DefaultScriptSupplier;
import com.bloxbean.cardano.client.backend.api.DefaultUtxoSupplier;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.bloxbean.cardano.julc.clientlib.eval.JulcTransactionEvaluator;
import com.example.cftemplates.escrow.onchain.CfEscrowValidator;
import com.example.offchain.YaciHelper;
import org.junit.jupiter.api.*;

import java.math.BigInteger;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Compares JulcTransactionEvaluator budgets against Ogmios/Haskell node budgets
 * for the CfEscrowValidator (CompleteTrade step).
 * <p>
 * This verifies that the Java VM produces budgets matching the Haskell CEK machine
 * when using the same protocol parameters from the chain.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EscrowBudgetComparisonTest {

    static boolean yaciAvailable;
    static Account initiator, recipient;
    static PlutusV3Script script;
    static BackendService backend;
    static QuickTxBuilder quickTx;
    static String scriptAddr;
    static String lockTxHash;
    static String depositTxHash;
    static byte[] initiatorPkh, recipientPkh;
    static BigInteger initiatorAmt = BigInteger.valueOf(10_000_000);
    static BigInteger recipientAmt = BigInteger.valueOf(5_000_000);

    @BeforeAll
    static void setup() throws Exception {
        yaciAvailable = YaciHelper.isYaciReachable();
        if (!yaciAvailable) return;

        initiator = new Account(Networks.testnet());
        recipient = new Account(Networks.testnet());
        initiatorPkh = initiator.hdKeyPair().getPublicKey().getKeyHash();
        recipientPkh = recipient.hdKeyPair().getPublicKey().getKeyHash();

        script = JulcScriptLoader.load(CfEscrowValidator.class);
        scriptAddr = AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();

        YaciHelper.topUp(initiator.baseAddress(), 1000);
        YaciHelper.topUp(recipient.baseAddress(), 1000);

        backend = YaciHelper.createBackendService();
        quickTx = new QuickTxBuilder(backend);
    }

    @Test
    @Order(1)
    void step1_initiatorLocks() throws Exception {
        assumeTrue(yaciAvailable, "Yaci DevKit not available");

        var initiationDatum = PlutusDataAdapter.convert(new CfEscrowValidator.Initiation(
                initiatorPkh, initiatorAmt));

        var lockTx = new Tx()
                .payToContract(scriptAddr, Amount.lovelace(initiatorAmt), initiationDatum)
                .from(initiator.baseAddress());

        var result = quickTx.compose(lockTx)
                .withSigner(SignerProviders.signerFrom(initiator))
                .complete();

        assertTrue(result.isSuccessful(), "Lock tx should succeed: " + result);
        lockTxHash = result.getValue();
        YaciHelper.waitForConfirmation(backend, lockTxHash);
        System.out.println("Step 1: Locked, tx=" + lockTxHash);
    }

    @Test
    @Order(2)
    void step2_recipientDeposits() throws Exception {
        assumeTrue(yaciAvailable, "Yaci DevKit not available");
        assertNotNull(lockTxHash, "Step 1 must complete first");

        var scriptUtxo = YaciHelper.findUtxo(backend, scriptAddr, lockTxHash);

        var depositRedeemer = PlutusDataAdapter.convert(new CfEscrowValidator.RecipientDeposit(
                recipientPkh, recipientAmt));

        var activeEscrowDatum = PlutusDataAdapter.convert(new CfEscrowValidator.ActiveEscrow(
                initiatorPkh, initiatorAmt, recipientPkh, recipientAmt));

        var depositTx = new ScriptTx()
                .collectFrom(scriptUtxo, depositRedeemer)
                .payToContract(scriptAddr, Amount.lovelace(initiatorAmt.add(recipientAmt)), activeEscrowDatum)
                .attachSpendingValidator(script);

        var result = quickTx.compose(depositTx)
                .withSigner(SignerProviders.signerFrom(recipient))
                .feePayer(recipient.baseAddress())
                .collateralPayer(recipient.baseAddress())
                .complete();

        assertTrue(result.isSuccessful(), "Deposit tx should succeed: " + result);
        depositTxHash = result.getValue();
        YaciHelper.waitForConfirmation(backend, depositTxHash);
        System.out.println("Step 2: Deposited, tx=" + depositTxHash);
    }

    @Test
    @Order(3)
    void step3_compareBudgets() throws Exception {
        assumeTrue(yaciAvailable, "Yaci DevKit not available");
        assertNotNull(depositTxHash, "Step 2 must complete first");

        var activeUtxo = YaciHelper.findUtxo(backend, scriptAddr, depositTxHash);

        var completeRedeemer = PlutusDataAdapter.convert(new CfEscrowValidator.CompleteTrade());

        var completeTx = new ScriptTx()
                .collectFrom(activeUtxo, completeRedeemer)
                .payToAddress(initiator.baseAddress(), Amount.lovelace(recipientAmt))
                .payToAddress(recipient.baseAddress(), Amount.lovelace(initiatorAmt))
                .attachSpendingValidator(script);

        var feeTx = new Tx()
                .payToAddress(initiator.baseAddress(), Amount.ada(1))
                .from(initiator.baseAddress());

        // Build the transaction with JulcTransactionEvaluator for budgets
        var julcEvaluator = new JulcTransactionEvaluator(
                new DefaultUtxoSupplier(backend.getUtxoService()),
                new DefaultProtocolParamsSupplier(backend.getEpochService()),
                new DefaultScriptSupplier(backend.getScriptService()));

        var txn = quickTx.compose(completeTx, feeTx)
                .withSigner(SignerProviders.signerFrom(initiator))
                .withSigner(SignerProviders.signerFrom(recipient))
                .feePayer(initiator.baseAddress())
                .collateralPayer(initiator.baseAddress())
                .withRequiredSigners(initiatorPkh)
                .withRequiredSigners(recipientPkh)
                .withTxEvaluator(julcEvaluator)
                .buildAndSign();

        assertNotNull(txn, "Transaction should be built successfully");
        byte[] txCbor = txn.serialize();

        // Get JuLC budget from the built transaction's redeemers
        var julcRedeemers = txn.getWitnessSet().getRedeemers();
        assertFalse(julcRedeemers.isEmpty(), "Should have at least one redeemer");
        var julcExUnits = julcRedeemers.get(0).getExUnits();
        long julcCpu = julcExUnits.getSteps().longValue();
        long julcMem = julcExUnits.getMem().longValue();

        System.out.println("=== Budget Comparison ===");
        System.out.println("JuLC (Java VM):  CPU=" + julcCpu + ", Mem=" + julcMem);

        // Now evaluate the same transaction CBOR with Ogmios (Haskell node)
        var ogmiosResult = backend.getTransactionService().evaluateTx(txCbor);
        assertTrue(ogmiosResult.isSuccessful(),
                "Ogmios evaluation should succeed: " + ogmiosResult.getResponse());

        List<EvaluationResult> ogmisResults = ogmiosResult.getValue();
        assertFalse(ogmisResults.isEmpty(), "Ogmios should return at least one result");
        var ogmiosExUnits = ogmisResults.get(0).getExUnits();
        long ogmisCpu = ogmiosExUnits.getSteps().longValue();
        long ogmisMem = ogmiosExUnits.getMem().longValue();

        System.out.println("Ogmios (Haskell): CPU=" + ogmisCpu + ", Mem=" + ogmisMem);
        System.out.println("Difference:       CPU=" + (julcCpu - ogmisCpu)
                + ", Mem=" + (julcMem - ogmisMem));

        // Assert budgets match exactly
        assertEquals(ogmisMem, julcMem, "Memory budget mismatch between JuLC and Ogmios");
        assertEquals(ogmisCpu, julcCpu, "CPU budget mismatch between JuLC and Ogmios");
    }
}
