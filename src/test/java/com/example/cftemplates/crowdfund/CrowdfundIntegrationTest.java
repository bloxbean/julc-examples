package com.example.cftemplates.crowdfund;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.*;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.example.cftemplates.crowdfund.onchain.CfCrowdfundValidator;
import com.example.offchain.YaciHelper;
import org.junit.jupiter.api.*;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CrowdfundIntegrationTest {

    static boolean yaciAvailable;
    static Account donor, beneficiary;
    static PlutusV3Script script;
    static BackendService backend;
    static QuickTxBuilder quickTx;
    static String scriptAddr;
    static String lockTxHash;

    @BeforeAll
    static void setup() throws Exception {
        yaciAvailable = YaciHelper.isYaciReachable();
        if (!yaciAvailable) return;

        donor = new Account(Networks.testnet());
        beneficiary = new Account(Networks.testnet());
        byte[] beneficiaryPkh = beneficiary.hdKeyPair().getPublicKey().getKeyHash();

        // Deadline in the past so beneficiary can withdraw in test
        BigInteger deadline = BigInteger.valueOf(System.currentTimeMillis() - 60_000);
        BigInteger goal = BigInteger.valueOf(5_000_000); // 5 ADA

        script = JulcScriptLoader.load(CfCrowdfundValidator.class,
                new BytesPlutusData(beneficiaryPkh),
                BigIntPlutusData.of(goal),
                BigIntPlutusData.of(deadline));
        scriptAddr = AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();

        YaciHelper.topUp(donor.baseAddress(), 1000);
        YaciHelper.topUp(beneficiary.baseAddress(), 1000);

        backend = YaciHelper.createBackendService();
        quickTx = new QuickTxBuilder(backend);
    }

    @Test
    @Order(1)
    void step1_donate() throws Exception {
        assumeTrue(yaciAvailable, "Yaci DevKit not available");

        byte[] donorPkh = donor.hdKeyPair().getPublicKey().getKeyHash();
        // CrowdfundDatum = Constr(0, [Map{donor -> 10_000_000}])
        var walletsMap = MapPlutusData.builder()
                .map(java.util.Map.of(
                        (com.bloxbean.cardano.client.plutus.spec.PlutusData) new BytesPlutusData(donorPkh),
                        (com.bloxbean.cardano.client.plutus.spec.PlutusData) BigIntPlutusData.of(10_000_000)))
                .build();
        var datum = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(walletsMap))
                .build();

        var lockTx = new Tx()
                .payToContract(scriptAddr, Amount.ada(10), datum)
                .from(donor.baseAddress());

        var result = quickTx.compose(lockTx)
                .withSigner(SignerProviders.signerFrom(donor))
                .complete();

        assertTrue(result.isSuccessful(), "Donate tx should succeed: " + result);
        lockTxHash = result.getValue();
        YaciHelper.waitForConfirmation(backend, lockTxHash);
        System.out.println("Step 1 OK: Donated 10 ADA, tx=" + lockTxHash);
    }

    @Test
    @Order(2)
    void step2_beneficiaryWithdraws() throws Exception {
        assumeTrue(yaciAvailable, "Yaci DevKit not available");
        assertNotNull(lockTxHash, "Step 1 must complete first");

        var scriptUtxo = YaciHelper.findUtxo(backend, scriptAddr, lockTxHash);

        // Withdraw = tag 1
        var redeemer = ConstrPlutusData.of(1);

        var latestBlock = backend.getBlockService().getLatestBlock();
        long currentSlot = latestBlock.getValue().getSlot();

        var withdrawTx = new ScriptTx()
                .collectFrom(scriptUtxo, redeemer)
                .payToAddress(beneficiary.baseAddress(), Amount.ada(5))
                .attachSpendingValidator(script);

        var result = quickTx.compose(withdrawTx)
                .withSigner(SignerProviders.signerFrom(beneficiary))
                .feePayer(beneficiary.baseAddress())
                .collateralPayer(beneficiary.baseAddress())
                .withRequiredSigners(beneficiary.hdKeyPair().getPublicKey().getKeyHash())
                .validFrom(currentSlot)
                .complete();

        assertTrue(result.isSuccessful(), "Withdraw tx should succeed: " + result);
        YaciHelper.waitForConfirmation(backend, result.getValue());
        System.out.println("Step 2 OK: Beneficiary withdrew, tx=" + result.getValue());
    }
}
