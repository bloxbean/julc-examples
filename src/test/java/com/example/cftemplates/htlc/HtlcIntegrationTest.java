package com.example.cftemplates.htlc;

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
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.example.cftemplates.htlc.onchain.CfHtlcValidator;
import com.example.offchain.YaciHelper;
import org.junit.jupiter.api.*;

import java.math.BigInteger;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HtlcIntegrationTest {

    static boolean yaciAvailable;
    static Account owner, claimer;
    static PlutusV3Script script;
    static BackendService backend;
    static QuickTxBuilder quickTx;
    static String scriptAddr;
    static String lockTxHash;
    static byte[] secretAnswer;
    static byte[] secretHash;

    @BeforeAll
    static void setup() throws Exception {
        yaciAvailable = YaciHelper.isYaciReachable();
        if (!yaciAvailable) return;

        owner = new Account(Networks.testnet());
        claimer = new Account(Networks.testnet());
        byte[] ownerPkh = owner.hdKeyPair().getPublicKey().getKeyHash();

        secretAnswer = "my_secret_answer".getBytes();
        secretHash = MessageDigest.getInstance("SHA-256").digest(secretAnswer);

        // Expiration far in the future
        BigInteger expiration = BigInteger.valueOf(System.currentTimeMillis() + 3_600_000);

        script = JulcScriptLoader.load(CfHtlcValidator.class,
                new BytesPlutusData(secretHash),
                BigIntPlutusData.of(expiration),
                new BytesPlutusData(ownerPkh));
        scriptAddr = AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();

        YaciHelper.topUp(owner.baseAddress(), 1000);
        YaciHelper.topUp(claimer.baseAddress(), 1000);

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
    void step2_claimerGuessesSecret() throws Exception {
        assumeTrue(yaciAvailable, "Yaci DevKit not available");
        assertNotNull(lockTxHash, "Step 1 must complete first");

        var scriptUtxo = YaciHelper.findUtxo(backend, scriptAddr, lockTxHash);

        // Guess redeemer = tag 0
        var redeemer = PlutusDataAdapter.convert(new CfHtlcValidator.Guess(secretAnswer));

        var latestBlock = backend.getBlockService().getLatestBlock();
        long currentSlot = latestBlock.getValue().getSlot();

        var unlockTx = new ScriptTx()
                .collectFrom(scriptUtxo, redeemer)
                .payToAddress(claimer.baseAddress(), Amount.ada(5))
                .attachSpendingValidator(script);

        var result = quickTx.compose(unlockTx)
                .withSigner(SignerProviders.signerFrom(claimer))
                .feePayer(claimer.baseAddress())
                .collateralPayer(claimer.baseAddress())
                .validTo(currentSlot + 200)
                .complete();

        assertTrue(result.isSuccessful(), "Unlock tx should succeed: " + result);
        YaciHelper.waitForConfirmation(backend, result.getValue());
        System.out.println("Step 2 OK: Claimer guessed secret, tx=" + result.getValue());
    }
}
