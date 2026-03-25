package com.example.cftemplates.atomictx;

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
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.example.cftemplates.atomictx.onchain.CfAtomicTxValidator;
import com.example.offchain.YaciHelper;
import org.junit.jupiter.api.*;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AtomicTxIntegrationTest {

    static boolean yaciAvailable;
    static Account account;
    static PlutusV3Script script;
    static BackendService backend;
    static QuickTxBuilder quickTx;
    static String scriptAddr;
    static String lockTxHash;

    @BeforeAll
    static void setup() throws Exception {
        yaciAvailable = YaciHelper.isYaciReachable();
        if (!yaciAvailable) return;

        account = new Account(Networks.testnet());

        script = JulcScriptLoader.load(CfAtomicTxValidator.class);
        scriptAddr = AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();

        YaciHelper.topUp(account.baseAddress(), 1000);

        backend = YaciHelper.createBackendService();
        quickTx = new QuickTxBuilder(backend);
    }

    @Test
    @Order(1)
    void step1_lockFunds() throws Exception {
        assumeTrue(yaciAvailable, "Yaci DevKit not available");

        var datum = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of())
                .build();

        var lockTx = new Tx()
                .payToContract(scriptAddr, Amount.ada(5), datum)
                .from(account.baseAddress());

        var result = quickTx.compose(lockTx)
                .withSigner(SignerProviders.signerFrom(account))
                .complete();

        assertTrue(result.isSuccessful(), "Lock tx should succeed: " + result);
        lockTxHash = result.getValue();
        YaciHelper.waitForConfirmation(backend, lockTxHash);
        System.out.println("Step 1 OK: Locked 5 ADA, tx=" + lockTxHash);
    }

    @Test
    @Order(2)
    void step2_wrongPasswordFails() throws Exception {
        assumeTrue(yaciAvailable, "Yaci DevKit not available");
        assertNotNull(lockTxHash, "Step 1 must complete first");

        var scriptUtxo = YaciHelper.findUtxo(backend, scriptAddr, lockTxHash);
        var spendRedeemer = ConstrPlutusData.of(0);
        var wrongMintRedeemer = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(new BytesPlutusData("wrong_password".getBytes())))
                .build();

        var tokenName = "0x" + "54657374"; // "Test"
        var asset = new Asset(tokenName, BigInteger.ONE);

        var failTx = new ScriptTx()
                .collectFrom(scriptUtxo, spendRedeemer)
                .mintAsset(script, List.of(asset), wrongMintRedeemer, account.baseAddress())
                .payToAddress(account.baseAddress(), Amount.ada(2))
                .attachSpendingValidator(script);

        var result = quickTx.compose(failTx)
                .withSigner(SignerProviders.signerFrom(account))
                .feePayer(account.baseAddress())
                .collateralPayer(account.baseAddress())
                .ignoreScriptCostEvaluationError(true)
                .complete();

        assertFalse(result.isSuccessful(), "Wrong password should fail atomically");
        System.out.println("Step 2 OK: Wrong password failed as expected");
    }

    @Test
    @Order(3)
    void step3_correctPasswordSucceeds() throws Exception {
        assumeTrue(yaciAvailable, "Yaci DevKit not available");
        assertNotNull(lockTxHash, "Step 1 must complete first");

        var scriptUtxo = YaciHelper.findUtxo(backend, scriptAddr, lockTxHash);
        var spendRedeemer = ConstrPlutusData.of(0);
        var correctMintRedeemer = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(new BytesPlutusData("super_secret_password".getBytes())))
                .build();

        var tokenName = "0x" + "54657374"; // "Test"
        var asset = new Asset(tokenName, BigInteger.ONE);

        // mintAsset 4-arg sends the minted token explicitly to account.baseAddress()
        var successTx = new ScriptTx()
                .collectFrom(scriptUtxo, spendRedeemer)
                .mintAsset(script, List.of(asset), correctMintRedeemer, account.baseAddress())
                .payToAddress(account.baseAddress(), Amount.ada(2))
                .attachSpendingValidator(script);

        var result = quickTx.compose(successTx)
                .withSigner(SignerProviders.signerFrom(account))
                .feePayer(account.baseAddress())
                .collateralPayer(account.baseAddress())
                .ignoreScriptCostEvaluationError(true)
                .complete();

        assertTrue(result.isSuccessful(), "Correct password should succeed: " + result);
        YaciHelper.waitForConfirmation(backend, result.getValue());
        System.out.println("Step 3 OK: Correct password succeeded, tx=" + result.getValue());
    }
}
