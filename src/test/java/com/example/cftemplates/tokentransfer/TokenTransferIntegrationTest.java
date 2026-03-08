package com.example.cftemplates.tokentransfer;

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
import com.example.cftemplates.tokentransfer.onchain.CfTokenTransferValidator;
import com.example.offchain.YaciHelper;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TokenTransferIntegrationTest {

    static boolean yaciAvailable;
    static Account owner, receiver;
    static PlutusV3Script script;
    static BackendService backend;
    static QuickTxBuilder quickTx;
    static String scriptAddr;
    static String lockTxHash;

    @BeforeAll
    static void setup() throws Exception {
        yaciAvailable = YaciHelper.isYaciReachable();
        if (!yaciAvailable) return;

        owner = new Account(Networks.testnet());
        receiver = new Account(Networks.testnet());
        byte[] receiverPkh = receiver.hdKeyPair().getPublicKey().getKeyHash();
        byte[] fakePolicy = new byte[28]; // no matching policy → escape hatch
        byte[] fakeAssetName = "Token".getBytes();

        script = JulcScriptLoader.load(CfTokenTransferValidator.class,
                new BytesPlutusData(receiverPkh),
                new BytesPlutusData(fakePolicy),
                new BytesPlutusData(fakeAssetName));
        scriptAddr = AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();

        YaciHelper.topUp(owner.baseAddress(), 1000);
        YaciHelper.topUp(receiver.baseAddress(), 1000);

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
                .payToContract(scriptAddr, Amount.ada(10), datum)
                .from(owner.baseAddress());

        var result = quickTx.compose(lockTx)
                .withSigner(SignerProviders.signerFrom(owner))
                .complete();

        assertTrue(result.isSuccessful(), "Lock tx should succeed: " + result);
        lockTxHash = result.getValue();
        YaciHelper.waitForConfirmation(backend, lockTxHash);
        System.out.println("Step 1 OK: Locked 10 ADA, tx=" + lockTxHash);
    }

    @Test
    @Order(2)
    void step2_receiverUnlocks() throws Exception {
        assumeTrue(yaciAvailable, "Yaci DevKit not available");
        assertNotNull(lockTxHash, "Step 1 must complete first");

        var scriptUtxo = YaciHelper.findUtxo(backend, scriptAddr, lockTxHash);
        var redeemer = ConstrPlutusData.of(0);

        var unlockTx = new ScriptTx()
                .collectFrom(scriptUtxo, redeemer)
                .payToAddress(receiver.baseAddress(), Amount.ada(5))
                .attachSpendingValidator(script);

        var result = quickTx.compose(unlockTx)
                .withSigner(SignerProviders.signerFrom(receiver))
                .feePayer(receiver.baseAddress())
                .collateralPayer(receiver.baseAddress())
                .withRequiredSigners(receiver.hdKeyPair().getPublicKey().getKeyHash())
                .complete();

        assertTrue(result.isSuccessful(), "Unlock tx should succeed: " + result);
        YaciHelper.waitForConfirmation(backend, result.getValue());
        System.out.println("Step 2 OK: Receiver unlocked, tx=" + result.getValue());
    }
}
