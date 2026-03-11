package com.example.cftemplates.crowdfund;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.bloxbean.cardano.julc.core.types.JulcMap;
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
    static Account donor1, donor2, beneficiary;
    static byte[] donor1Pkh, donor2Pkh, beneficiaryPkh;
    static PlutusV3Script script;
    static BackendService backend;
    static QuickTxBuilder quickTx;
    static String scriptAddr;
    static String initTxHash;
    static String donateTxHash;

    @BeforeAll
    static void setup() throws Exception {
        yaciAvailable = YaciHelper.isYaciReachable();
        if (!yaciAvailable) return;

        donor1 = new Account(Networks.testnet());
        donor2 = new Account(Networks.testnet());
        beneficiary = new Account(Networks.testnet());
        donor1Pkh = donor1.hdKeyPair().getPublicKey().getKeyHash();
        donor2Pkh = donor2.hdKeyPair().getPublicKey().getKeyHash();
        beneficiaryPkh = beneficiary.hdKeyPair().getPublicKey().getKeyHash();

        // Deadline in the past so beneficiary can withdraw in test
        BigInteger deadline = BigInteger.valueOf(System.currentTimeMillis() - 60_000);
        BigInteger goal = BigInteger.valueOf(5_000_000); // 5 ADA

        script = JulcScriptLoader.load(CfCrowdfundValidator.class,
                new BytesPlutusData(beneficiaryPkh),
                BigIntPlutusData.of(goal),
                BigIntPlutusData.of(deadline));
        scriptAddr = AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();

        YaciHelper.topUp(donor1.baseAddress(), 1000);
        YaciHelper.topUp(donor2.baseAddress(), 1000);
        YaciHelper.topUp(beneficiary.baseAddress(), 1000);

        backend = YaciHelper.createBackendService();
        quickTx = new QuickTxBuilder(backend);
    }

    @Test
    @Order(1)
    void step1_initialize() throws Exception {
        assumeTrue(yaciAvailable, "Yaci DevKit not available");

        // Initialize crowdfund: donor1 sends 5 ADA to script (plain Tx)
        JulcMap<byte[], BigInteger> initWallets = JulcMap.of(donor1Pkh, BigInteger.valueOf(5_000_000));
        var datum = PlutusDataAdapter.convert(new CfCrowdfundValidator.CrowdfundDatum(initWallets));

        var initTx = new Tx()
                .payToContract(scriptAddr, Amount.ada(5), datum)
                .from(donor1.baseAddress());

        var result = quickTx.compose(initTx)
                .withSigner(SignerProviders.signerFrom(donor1))
                .complete();

        assertTrue(result.isSuccessful(), "Initialize tx should succeed: " + result);
        initTxHash = result.getValue();
        YaciHelper.waitForConfirmation(backend, initTxHash);
        System.out.println("Step 1 OK: Initialized with 5 ADA from donor1, tx=" + initTxHash);
    }

    @Test
    @Order(2)
    void step2_donate() throws Exception {
        assumeTrue(yaciAvailable, "Yaci DevKit not available");
        assertNotNull(initTxHash, "Step 1 must complete first");

        // Donate: donor2 donates 5 ADA via ScriptTx
        var scriptUtxo = YaciHelper.findUtxo(backend, scriptAddr, initTxHash);

        JulcMap<byte[], BigInteger> updatedWallets = JulcMap.of(
                donor1Pkh, BigInteger.valueOf(5_000_000),
                donor2Pkh, BigInteger.valueOf(5_000_000));
        var updatedDatum = PlutusDataAdapter.convert(new CfCrowdfundValidator.CrowdfundDatum(updatedWallets));

        // DONATE redeemer = tag 0
        var donateTx = new ScriptTx()
                .collectFrom(scriptUtxo, PlutusDataAdapter.convert(new CfCrowdfundValidator.Donate()))
                .payToContract(scriptAddr, Amount.ada(10), updatedDatum)
                .attachSpendingValidator(script);

        var result = quickTx.compose(donateTx)
                .withSigner(SignerProviders.signerFrom(donor2))
                .feePayer(donor2.baseAddress())
                .collateralPayer(donor2.baseAddress())
                .complete();

        assertTrue(result.isSuccessful(), "Donate tx should succeed: " + result);
        donateTxHash = result.getValue();
        YaciHelper.waitForConfirmation(backend, donateTxHash);
        System.out.println("Step 2 OK: Donor2 donated 5 ADA, tx=" + donateTxHash);
    }

    @Test
    @Order(3)
    void step3_beneficiaryWithdraws() throws Exception {
        assumeTrue(yaciAvailable, "Yaci DevKit not available");
        assertNotNull(donateTxHash, "Step 2 must complete first");

        var scriptUtxo = YaciHelper.findUtxo(backend, scriptAddr, donateTxHash);

        var latestBlock = backend.getBlockService().getLatestBlock();
        long currentSlot = latestBlock.getValue().getSlot();

        // WITHDRAW redeemer = tag 1
        var withdrawTx = new ScriptTx()
                .collectFrom(scriptUtxo, PlutusDataAdapter.convert(new CfCrowdfundValidator.Withdraw()))
                .payToAddress(beneficiary.baseAddress(), Amount.ada(10))
                .attachSpendingValidator(script);

        var result = quickTx.compose(withdrawTx)
                .withSigner(SignerProviders.signerFrom(beneficiary))
                .feePayer(beneficiary.baseAddress())
                .collateralPayer(beneficiary.baseAddress())
                .withRequiredSigners(beneficiaryPkh)
                .validFrom(currentSlot)
                .complete();

        assertTrue(result.isSuccessful(), "Withdraw tx should succeed: " + result);
        YaciHelper.waitForConfirmation(backend, result.getValue());
        System.out.println("Step 3 OK: Beneficiary withdrew 10 ADA, tx=" + result.getValue());
    }
}
