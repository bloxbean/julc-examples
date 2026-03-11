package com.example.cftemplates.pricebet;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.example.cftemplates.pricebet.onchain.CfPriceBetValidator;
import com.example.offchain.YaciHelper;
import org.junit.jupiter.api.*;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration test for CfPriceBetValidator.
 * Step 1: Owner creates a price bet (locks ADA with PriceBetDatum, deadline in the past).
 * Step 2: Owner reclaims via Timeout (after deadline, owner signs).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PriceBetIntegrationTest {

    static boolean yaciAvailable;
    static Account owner;
    static PlutusV3Script script;
    static BackendService backend;
    static QuickTxBuilder quickTx;
    static String scriptAddr;
    static String lockTxHash;
    static byte[] ownerPkh;
    static BigInteger betAmount = BigInteger.valueOf(10_000_000); // 10 ADA

    @BeforeAll
    static void setup() throws Exception {
        yaciAvailable = YaciHelper.isYaciReachable();
        if (!yaciAvailable) return;

        owner = new Account(Networks.testnet());
        ownerPkh = owner.hdKeyPair().getPublicKey().getKeyHash();

        script = JulcScriptLoader.load(CfPriceBetValidator.class);
        scriptAddr = AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();

        YaciHelper.topUp(owner.baseAddress(), 1000);

        backend = YaciHelper.createBackendService();
        quickTx = new QuickTxBuilder(backend);
    }

    @Test
    @Order(1)
    void step1_createBet() throws Exception {
        assumeTrue(yaciAvailable, "Yaci DevKit not available");

        // Deadline in the past so Timeout is valid immediately
        BigInteger deadline = BigInteger.valueOf(System.currentTimeMillis() - 60_000);

        // PriceBetDatum(owner, player(empty), oracleVkh, targetRate, deadline, betAmount)
        var priceBetDatum = PlutusDataAdapter.convert(new CfPriceBetValidator.PriceBetDatum(
                ownerPkh, new byte[0], new byte[28], BigInteger.valueOf(100), deadline, betAmount));

        var lockTx = new Tx()
                .payToContract(scriptAddr, Amount.lovelace(betAmount), priceBetDatum)
                .from(owner.baseAddress());

        var result = quickTx.compose(lockTx)
                .withSigner(SignerProviders.signerFrom(owner))
                .complete();

        assertTrue(result.isSuccessful(), "Create bet should succeed: " + result);
        lockTxHash = result.getValue();
        YaciHelper.waitForConfirmation(backend, lockTxHash);
        System.out.println("Step 1 OK: Price bet created, tx=" + lockTxHash);
    }

    @Test
    @Order(2)
    void step2_timeout() throws Exception {
        assumeTrue(yaciAvailable, "Yaci DevKit not available");
        assertNotNull(lockTxHash, "Step 1 must complete first");

        var scriptUtxo = YaciHelper.findUtxo(backend, scriptAddr, lockTxHash);

        // Timeout = tag 2
        var timeoutRedeemer = PlutusDataAdapter.convert(new CfPriceBetValidator.Timeout());

        var latestBlock = backend.getBlockService().getLatestBlock();
        long currentSlot = latestBlock.getValue().getSlot();

        var timeoutTx = new ScriptTx()
                .collectFrom(scriptUtxo, timeoutRedeemer)
                .payToAddress(owner.baseAddress(), Amount.lovelace(betAmount))
                .attachSpendingValidator(script);

        // Force inclusion of owner's wallet UTXO as input so fees don't
        // reduce the script payout below the validator's expected amount
        var feeTx = new Tx()
                .payToAddress(owner.baseAddress(), Amount.ada(1))
                .from(owner.baseAddress());

        var result = quickTx.compose(timeoutTx, feeTx)
                .withSigner(SignerProviders.signerFrom(owner))
                .feePayer(owner.baseAddress())
                .collateralPayer(owner.baseAddress())
                .withRequiredSigners(ownerPkh)
                .validFrom(currentSlot)
                .complete();

        assertTrue(result.isSuccessful(), "Timeout should succeed: " + result);
        YaciHelper.waitForConfirmation(backend, result.getValue());
        System.out.println("Step 2 OK: Owner reclaimed via timeout, tx=" + result.getValue());
    }
}
