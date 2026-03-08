package com.example.cftemplates.paymentsplitter;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.example.cftemplates.paymentsplitter.onchain.CfPaymentSplitterValidator;
import com.example.offchain.YaciHelper;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PaymentSplitterIntegrationTest {

    static boolean yaciAvailable;
    static Account funder, payee1, payee2;
    static PlutusV3Script script;
    static BackendService backend;
    static QuickTxBuilder quickTx;
    static String scriptAddr;
    static String lockTxHash;

    @BeforeAll
    static void setup() throws Exception {
        yaciAvailable = YaciHelper.isYaciReachable();
        if (!yaciAvailable) return;

        funder = new Account(Networks.testnet());
        payee1 = new Account(Networks.testnet());
        payee2 = new Account(Networks.testnet());
        byte[] pkh1 = payee1.hdKeyPair().getPublicKey().getKeyHash();
        byte[] pkh2 = payee2.hdKeyPair().getPublicKey().getKeyHash();

        var payeesList = ListPlutusData.of(
                new BytesPlutusData(pkh1),
                new BytesPlutusData(pkh2));

        script = JulcScriptLoader.load(CfPaymentSplitterValidator.class, payeesList);
        scriptAddr = AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();

        YaciHelper.topUp(funder.baseAddress(), 1000);
        YaciHelper.topUp(payee1.baseAddress(), 1000);

        backend = YaciHelper.createBackendService();
        quickTx = new QuickTxBuilder(backend);
    }

    @Test
    @Order(1)
    void step1_lockFunds() throws Exception {
        assumeTrue(yaciAvailable, "Yaci DevKit not available");

        var datum = ConstrPlutusData.builder()
                .alternative(1) // None
                .data(ListPlutusData.of())
                .build();

        var lockTx = new Tx()
                .payToContract(scriptAddr, Amount.ada(20), datum)
                .from(funder.baseAddress());

        var result = quickTx.compose(lockTx)
                .withSigner(SignerProviders.signerFrom(funder))
                .complete();

        assertTrue(result.isSuccessful(), "Lock tx should succeed: " + result);
        lockTxHash = result.getValue();
        YaciHelper.waitForConfirmation(backend, lockTxHash);
        System.out.println("Step 1 OK: Locked 20 ADA, tx=" + lockTxHash);
    }

    @Test
    @Order(2)
    void step2_splitToPayees() throws Exception {
        assumeTrue(yaciAvailable, "Yaci DevKit not available");
        assertNotNull(lockTxHash, "Step 1 must complete first");

        var scriptUtxo = YaciHelper.findUtxo(backend, scriptAddr, lockTxHash);
        var redeemer = ConstrPlutusData.of(0);

        var splitTx = new ScriptTx()
                .collectFrom(scriptUtxo, redeemer)
                .payToAddress(payee1.baseAddress(), Amount.ada(10))
                .payToAddress(payee2.baseAddress(), Amount.ada(10))
                .attachSpendingValidator(script);

        // payee1 must have a wallet UTXO as input for the validator's
        // net calculation (outputSum - (inputSum - fee)) to work correctly.
        // A dummy self-payment forces the builder to include payee1's UTXO.
        var feeTx = new Tx()
                .payToAddress(payee1.baseAddress(), Amount.ada(2))
                .from(payee1.baseAddress());

        var result = quickTx.compose(splitTx, feeTx)
                .withSigner(SignerProviders.signerFrom(payee1))
                .feePayer(payee1.baseAddress())
                .collateralPayer(payee1.baseAddress())
                .complete();

        assertTrue(result.isSuccessful(), "Split tx should succeed: " + result);
        YaciHelper.waitForConfirmation(backend, result.getValue());
        System.out.println("Step 2 OK: Split to payees, tx=" + result.getValue());
    }
}
