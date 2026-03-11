package com.example.cftemplates.auction;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.example.cftemplates.auction.onchain.CfAuctionValidator;
import com.example.offchain.YaciHelper;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuctionIntegrationTest {

    static boolean yaciAvailable;
    static Account seller;
    static PlutusV3Script script;
    static BackendService backend;
    static QuickTxBuilder quickTx;
    static String scriptAddr;
    static String lockTxHash;
    static byte[] sellerPkh;

    @BeforeAll
    static void setup() throws Exception {
        yaciAvailable = YaciHelper.isYaciReachable();
        if (!yaciAvailable) return;

        seller = new Account(Networks.testnet());
        sellerPkh = seller.hdKeyPair().getPublicKey().getKeyHash();

        script = JulcScriptLoader.load(CfAuctionValidator.class);
        scriptAddr = AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();

        YaciHelper.topUp(seller.baseAddress(), 1000);

        backend = YaciHelper.createBackendService();
        quickTx = new QuickTxBuilder(backend);
    }

    @Test
    @Order(1)
    void step1_createAuction() throws Exception {
        assumeTrue(yaciAvailable, "Yaci DevKit not available");

        // Expiration in the past for testing end
        long expiration = System.currentTimeMillis() - 60_000;

        // AuctionDatum(seller, emptyBidder, bid=0, expiration, emptyPolicy, emptyName)
        var auctionDatum = PlutusDataAdapter.convert(new CfAuctionValidator.AuctionDatum(
                sellerPkh, new byte[0], java.math.BigInteger.ZERO,
                java.math.BigInteger.valueOf(expiration), new byte[0], new byte[0]));

        var lockTx = new Tx()
                .payToContract(scriptAddr, Amount.ada(5), auctionDatum)
                .from(seller.baseAddress());

        var result = quickTx.compose(lockTx)
                .withSigner(SignerProviders.signerFrom(seller))
                .complete();

        assertTrue(result.isSuccessful(), "Create auction should succeed: " + result);
        lockTxHash = result.getValue();
        YaciHelper.waitForConfirmation(backend, lockTxHash);
        System.out.println("Step 1 OK: Auction created, tx=" + lockTxHash);
    }

    @Test
    @Order(2)
    void step2_endAuctionNoBids() throws Exception {
        assumeTrue(yaciAvailable, "Yaci DevKit not available");
        assertNotNull(lockTxHash, "Step 1 must complete first");

        var scriptUtxo = YaciHelper.findUtxo(backend, scriptAddr, lockTxHash);

        // End = tag 2
        var endRedeemer = PlutusDataAdapter.convert(new CfAuctionValidator.End());

        var latestBlock = backend.getBlockService().getLatestBlock();
        long currentSlot = latestBlock.getValue().getSlot();

        var endTx = new ScriptTx()
                .collectFrom(scriptUtxo, endRedeemer)
                .payToAddress(seller.baseAddress(), Amount.ada(3))
                .attachSpendingValidator(script);

        var result = quickTx.compose(endTx)
                .withSigner(SignerProviders.signerFrom(seller))
                .feePayer(seller.baseAddress())
                .collateralPayer(seller.baseAddress())
                .withRequiredSigners(sellerPkh)
                .validFrom(currentSlot)
                .complete();

        assertTrue(result.isSuccessful(), "End auction should succeed: " + result);
        YaciHelper.waitForConfirmation(backend, result.getValue());
        System.out.println("Step 2 OK: Auction ended (no bids), tx=" + result.getValue());
    }
}
