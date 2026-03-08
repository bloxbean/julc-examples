package com.example.cftemplates.escrow;

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
import com.example.cftemplates.escrow.onchain.CfEscrowValidator;
import com.example.offchain.YaciHelper;
import org.junit.jupiter.api.*;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EscrowIntegrationTest {

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

        // Initiation datum (tag 0) = Constr(0, [initiatorPkh, initiatorAmt])
        var initiationDatum = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(
                        new BytesPlutusData(initiatorPkh),
                        BigIntPlutusData.of(initiatorAmt)))
                .build();

        var lockTx = new Tx()
                .payToContract(scriptAddr, Amount.lovelace(initiatorAmt), initiationDatum)
                .from(initiator.baseAddress());

        var result = quickTx.compose(lockTx)
                .withSigner(SignerProviders.signerFrom(initiator))
                .complete();

        assertTrue(result.isSuccessful(), "Lock tx should succeed: " + result);
        lockTxHash = result.getValue();
        YaciHelper.waitForConfirmation(backend, lockTxHash);
        System.out.println("Step 1 OK: Initiator locked " + initiatorAmt.divide(BigInteger.valueOf(1_000_000)) + " ADA, tx=" + lockTxHash);
    }

    @Test
    @Order(2)
    void step2_recipientDeposits() throws Exception {
        assumeTrue(yaciAvailable, "Yaci DevKit not available");
        assertNotNull(lockTxHash, "Step 1 must complete first");

        var scriptUtxo = YaciHelper.findUtxo(backend, scriptAddr, lockTxHash);

        // RecipientDeposit = Constr(0, [recipientPkh, recipientAmt])
        var depositRedeemer = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(
                        new BytesPlutusData(recipientPkh),
                        BigIntPlutusData.of(recipientAmt)))
                .build();

        // ActiveEscrow datum (tag 1)
        var activeEscrowDatum = ConstrPlutusData.builder()
                .alternative(1)
                .data(ListPlutusData.of(
                        new BytesPlutusData(initiatorPkh),
                        BigIntPlutusData.of(initiatorAmt),
                        new BytesPlutusData(recipientPkh),
                        BigIntPlutusData.of(recipientAmt)))
                .build();

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
        System.out.println("Step 2 OK: Recipient deposited, tx=" + depositTxHash);
    }

    @Test
    @Order(3)
    void step3_completeTrade() throws Exception {
        assumeTrue(yaciAvailable, "Yaci DevKit not available");
        assertNotNull(depositTxHash, "Step 2 must complete first");

        var activeUtxo = YaciHelper.findUtxo(backend, scriptAddr, depositTxHash);

        // CompleteTrade = Constr(2)
        var completeRedeemer = ConstrPlutusData.of(2);

        var completeTx = new ScriptTx()
                .collectFrom(activeUtxo, completeRedeemer)
                .payToAddress(initiator.baseAddress(), Amount.lovelace(recipientAmt))
                .payToAddress(recipient.baseAddress(), Amount.lovelace(initiatorAmt))
                .attachSpendingValidator(script);

        // Force inclusion of initiator's wallet UTXO so fees don't
        // reduce payouts below the validator's expected amounts
        var feeTx = new Tx()
                .payToAddress(initiator.baseAddress(), Amount.ada(1))
                .from(initiator.baseAddress());

        var result = quickTx.compose(completeTx, feeTx)
                .withSigner(SignerProviders.signerFrom(initiator))
                .withSigner(SignerProviders.signerFrom(recipient))
                .feePayer(initiator.baseAddress())
                .collateralPayer(initiator.baseAddress())
                .withRequiredSigners(initiatorPkh)
                .withRequiredSigners(recipientPkh)
                .complete();

        assertTrue(result.isSuccessful(), "Complete trade tx should succeed: " + result);
        YaciHelper.waitForConfirmation(backend, result.getValue());
        System.out.println("Step 3 OK: Trade completed, tx=" + result.getValue());
    }
}
