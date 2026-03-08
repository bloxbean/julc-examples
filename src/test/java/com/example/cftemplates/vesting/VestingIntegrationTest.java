package com.example.cftemplates.vesting;

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
import com.example.cftemplates.vesting.onchain.CfVestingValidator;
import com.example.offchain.YaciHelper;
import org.junit.jupiter.api.*;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class VestingIntegrationTest {

    static boolean yaciAvailable;
    static Account owner, beneficiary;
    static PlutusV3Script script;
    static BackendService backend;
    static QuickTxBuilder quickTx;
    static String scriptAddr;
    static String lockTxHash;
    static BigInteger lockUntil;

    @BeforeAll
    static void setup() throws Exception {
        yaciAvailable = YaciHelper.isYaciReachable();
        if (!yaciAvailable) return;

        owner = new Account(Networks.testnet());
        beneficiary = new Account(Networks.testnet());

        // Lock time in the past so beneficiary can unlock in test
        lockUntil = BigInteger.valueOf(System.currentTimeMillis() - 60_000);

        script = JulcScriptLoader.load(CfVestingValidator.class);
        scriptAddr = AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();

        YaciHelper.topUp(owner.baseAddress(), 1000);
        YaciHelper.topUp(beneficiary.baseAddress(), 1000);

        backend = YaciHelper.createBackendService();
        quickTx = new QuickTxBuilder(backend);
    }

    @Test
    @Order(1)
    void step1_ownerLocksWithVestingDatum() throws Exception {
        assumeTrue(yaciAvailable, "Yaci DevKit not available");

        byte[] ownerPkh = owner.hdKeyPair().getPublicKey().getKeyHash();
        byte[] beneficiaryPkh = beneficiary.hdKeyPair().getPublicKey().getKeyHash();

        var datum = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(
                        BigIntPlutusData.of(lockUntil),
                        new BytesPlutusData(ownerPkh),
                        new BytesPlutusData(beneficiaryPkh)))
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
        System.out.println("Step 1 OK: Locked 10 ADA with vesting datum, tx=" + lockTxHash);
    }

    @Test
    @Order(2)
    void step2_beneficiaryUnlocksAfterLockTime() throws Exception {
        assumeTrue(yaciAvailable, "Yaci DevKit not available");
        assertNotNull(lockTxHash, "Step 1 must complete first");

        byte[] beneficiaryPkh = beneficiary.hdKeyPair().getPublicKey().getKeyHash();
        var scriptUtxo = YaciHelper.findUtxo(backend, scriptAddr, lockTxHash);
        var redeemer = ConstrPlutusData.of(0);

        var latestBlock = backend.getBlockService().getLatestBlock();
        long currentSlot = latestBlock.getValue().getSlot();

        var unlockTx = new ScriptTx()
                .collectFrom(scriptUtxo, redeemer)
                .payToAddress(beneficiary.baseAddress(), Amount.ada(5))
                .attachSpendingValidator(script);

        var result = quickTx.compose(unlockTx)
                .withSigner(SignerProviders.signerFrom(beneficiary))
                .feePayer(beneficiary.baseAddress())
                .collateralPayer(beneficiary.baseAddress())
                .withRequiredSigners(beneficiaryPkh)
                .validFrom(currentSlot)
                .complete();

        assertTrue(result.isSuccessful(), "Unlock tx should succeed: " + result);
        YaciHelper.waitForConfirmation(backend, result.getValue());
        System.out.println("Step 2 OK: Beneficiary unlocked, tx=" + result.getValue());
    }
}
