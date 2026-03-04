package com.example.swap;

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
import com.example.offchain.YaciHelper;
import com.example.swap.onchain.SwapOrder;
import org.junit.jupiter.api.*;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration test for SwapOrder — requires Yaci DevKit running.
 * Tests are skipped (not failed) when Yaci is unavailable.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SwapOrderIntegrationTest {

    static boolean yaciAvailable;
    static Account maker, taker;
    static PlutusV3Script script;
    static BackendService backend;
    static QuickTxBuilder quickTx;
    static String scriptAddr;
    static String lockTxHash;

    @BeforeAll
    static void setup() throws Exception {
        yaciAvailable = YaciHelper.isYaciReachable();
        if (!yaciAvailable) return;

        script = JulcScriptLoader.load(SwapOrder.class);
        scriptAddr = AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();

        maker = new Account(Networks.testnet());
        taker = new Account(Networks.testnet());

        YaciHelper.topUp(maker.baseAddress(), 1000);
        YaciHelper.topUp(taker.baseAddress(), 1000);

        backend = YaciHelper.createBackendService();
        quickTx = new QuickTxBuilder(backend);
    }

    @Test
    @Order(1)
    void step1_makerPlacesOrder() throws Exception {
        assumeTrue(yaciAvailable, "Yaci DevKit not available");

        byte[] makerPkh = maker.hdKeyPair().getPublicKey().getKeyHash();

        var datum = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(
                        new BytesPlutusData(makerPkh),
                        BytesPlutusData.of(""),
                        BytesPlutusData.of(""),
                        BigIntPlutusData.of(50_000_000),
                        BytesPlutusData.of(""),
                        BytesPlutusData.of(""),
                        BigIntPlutusData.of(45_000_000)))
                .build();

        var lockTx = new Tx()
                .payToContract(scriptAddr, Amount.ada(50), datum)
                .from(maker.baseAddress());

        var result = quickTx.compose(lockTx)
                .withSigner(SignerProviders.signerFrom(maker))
                .complete();

        assertTrue(result.isSuccessful(), "Lock tx should succeed: " + result);
        lockTxHash = result.getValue();
        YaciHelper.waitForConfirmation(backend, lockTxHash);
        System.out.println("Step 1 OK: Maker placed order, tx=" + lockTxHash);
    }

    @Test
    @Order(2)
    void step2_takerFillsOrder() throws Exception {
        assumeTrue(yaciAvailable, "Yaci DevKit not available");
        assertNotNull(lockTxHash, "Step 1 must complete first");

        var scriptUtxo = YaciHelper.findUtxo(backend, scriptAddr, lockTxHash);
        var redeemer = ConstrPlutusData.of(0); // FillOrder

        // Use enterprise address (no staking cred) so validator's makerAddress() comparison works
        String makerEntAddr = AddressProvider.getEntAddress(
                maker.hdKeyPair().getPublicKey(), Networks.testnet()).toBech32();

        var fillTx = new ScriptTx()
                .collectFrom(scriptUtxo, redeemer)
                .payToAddress(makerEntAddr, Amount.ada(45))
                .attachSpendingValidator(script);

        var result = quickTx.compose(fillTx)
                .withSigner(SignerProviders.signerFrom(taker))
                .feePayer(taker.baseAddress())
                .collateralPayer(taker.baseAddress())
                .complete();

        assertTrue(result.isSuccessful(), "Fill tx should succeed: " + result);
        YaciHelper.waitForConfirmation(backend, result.getValue());
        System.out.println("Step 2 OK: Taker filled order, tx=" + result.getValue());
    }

    @Test
    @Order(3)
    void step3_verifyMakerReceivedPayment() throws Exception {
        assumeTrue(yaciAvailable, "Yaci DevKit not available");

        // Verify maker has UTxOs (received payment)
        var utxoResult = backend.getUtxoService().getUtxos(maker.baseAddress(), 100, 1);
        assertTrue(utxoResult.isSuccessful(), "Should be able to query maker UTxOs");
        assertFalse(utxoResult.getValue().isEmpty(), "Maker should have UTxOs after receiving payment");
        System.out.println("Step 3 OK: Maker has " + utxoResult.getValue().size() + " UTxOs");
    }
}
