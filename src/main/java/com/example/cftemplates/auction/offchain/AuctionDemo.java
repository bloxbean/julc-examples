package com.example.cftemplates.auction.offchain;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.example.cftemplates.auction.onchain.CfAuctionValidator;
import com.example.offchain.YaciHelper;

/**
 * Off-chain demo for CfAuctionValidator.
 * Seller creates auction → Bidder bids → End auction (seller gets ADA, no-bid reclaim).
 */
public class AuctionDemo {

    public static void main(String[] args) throws Exception {
        if (!YaciHelper.isYaciReachable()) {
            System.out.println("Yaci DevKit not reachable. Skipping demo.");
            System.exit(1);
        }

        var seller = new Account(Networks.testnet());
        byte[] sellerPkh = seller.hdKeyPair().getPublicKey().getKeyHash();

        // Expiration in the past so we can end immediately
        java.math.BigInteger expiration = java.math.BigInteger.valueOf(System.currentTimeMillis() - 60_000);

        YaciHelper.topUp(seller.baseAddress(), 1000);

        var script = JulcScriptLoader.load(CfAuctionValidator.class);
        var scriptAddr = AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();

        var backend = YaciHelper.createBackendService();
        var quickTx = new QuickTxBuilder(backend);

        // Step 1: Create auction with no bidder (will test no-bid end)
        System.out.println("Step 1: Seller creating auction...");
        // AuctionDatum(seller, emptyBidder, bid=0, expiration, emptyPolicy, emptyName)
        var auctionDatum = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(
                        new BytesPlutusData(sellerPkh),
                        new BytesPlutusData(new byte[0]),   // no bidder
                        BigIntPlutusData.of(java.math.BigInteger.ZERO),              // no bid
                        BigIntPlutusData.of(expiration),
                        new BytesPlutusData(new byte[0]),    // no asset policy (ADA-only demo)
                        new BytesPlutusData(new byte[0])))   // no asset name
                .build();

        var lockTx = new Tx()
                .payToContract(scriptAddr, Amount.ada(5), auctionDatum)
                .from(seller.baseAddress());

        var lockResult = quickTx.compose(lockTx)
                .withSigner(SignerProviders.signerFrom(seller))
                .complete();

        if (!lockResult.isSuccessful()) {
            System.out.println("Create auction failed: " + lockResult);
            System.exit(1);
        }
        var lockTxHash = lockResult.getValue();
        YaciHelper.waitForConfirmation(backend, lockTxHash);
        System.out.println("Auction created! Tx: " + lockTxHash);

        // Step 2: End auction (no bids, seller reclaims)
        System.out.println("Step 2: Ending auction (no bids, seller reclaims)...");
        var scriptUtxo = YaciHelper.findUtxo(backend, scriptAddr, lockTxHash);

        // End = Constr(2)
        var endRedeemer = ConstrPlutusData.of(2);

        var latestBlock = backend.getBlockService().getLatestBlock();
        long currentSlot = latestBlock.getValue().getSlot();

        var endTx = new ScriptTx()
                .collectFrom(scriptUtxo, endRedeemer)
                .payToAddress(seller.baseAddress(), Amount.ada(3))
                .attachSpendingValidator(script);

        var endResult = quickTx.compose(endTx)
                .withSigner(SignerProviders.signerFrom(seller))
                .feePayer(seller.baseAddress())
                .collateralPayer(seller.baseAddress())
                .withRequiredSigners(sellerPkh)
                .validFrom(currentSlot)
                .complete();

        if (!endResult.isSuccessful()) {
            System.out.println("End auction failed: " + endResult);
            System.exit(1);
        }
        YaciHelper.waitForConfirmation(backend, endResult.getValue());
        System.out.println("Auction ended! Tx: " + endResult.getValue());
        System.out.println("Auction demo completed successfully!");
    }
}
