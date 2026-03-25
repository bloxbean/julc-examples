package com.example.cftemplates.auction.offchain;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.example.cftemplates.auction.onchain.CfAuctionValidator;
import com.example.offchain.YaciHelper;

import java.math.BigInteger;
import java.util.List;

/**
 * Off-chain demo for CfAuctionValidator.
 * Step 1: Seller creates auction (mint auction token with NFT).
 * Step 2: Bidder places a BID.
 * Step 3: END auction — winner gets NFT, seller gets ADA.
 */
public class AuctionDemo {

    public static void main(String[] args) throws Exception {
        if (!YaciHelper.isYaciReachable()) {
            System.out.println("Yaci DevKit not reachable. Skipping demo.");
            System.exit(1);
        }

        var seller = new Account(Networks.testnet());
        var bidder = new Account(Networks.testnet());
        byte[] sellerPkh = seller.hdKeyPair().getPublicKey().getKeyHash();
        byte[] bidderPkh = bidder.hdKeyPair().getPublicKey().getKeyHash();

        // Expiration 120 seconds in the future — enough headroom for CREATE + BID
        // (topups + tx building consume ~10s, and validTo adds ~10s to upper bound)
        BigInteger expiration = BigInteger.valueOf(System.currentTimeMillis() + 120_000);

        YaciHelper.topUp(seller.baseAddress(), 1000);
        YaciHelper.topUp(bidder.baseAddress(), 1000);

        var script = JulcScriptLoader.load(CfAuctionValidator.class);
        var scriptAddr = AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();
        var policyId = HexUtil.encodeHexString(script.getScriptHash());
        byte[] policyIdBytes = script.getScriptHash();
        System.out.println("Auction policy: " + policyId);

        var backend = YaciHelper.createBackendService();
        var quickTx = new QuickTxBuilder(backend);

        // Use the auction script's own policy as the "auctioned asset" policy for demo.
        // The auction token itself serves as the NFT being auctioned.
        byte[] assetNameBytes = "AUCTION_NFT".getBytes();
        String assetNameHex = "0x" + HexUtil.encodeHexString(assetNameBytes);
        String auctionTokenUnit = policyId + HexUtil.encodeHexString(assetNameBytes);
        var auctionAsset = new Asset(assetNameHex, BigInteger.ONE);

        BigInteger startingBid = BigInteger.ZERO;

        // Step 1: Seller creates auction (mint auction NFT)
        System.out.println("Step 1: Seller creating auction (minting auction NFT)...");

        // AuctionDatum(seller, emptyBidder, bid=0, expiration, assetPolicy, assetName)
        var auctionDatum = PlutusDataAdapter.convert(new CfAuctionValidator.AuctionDatum(
                sellerPkh, new byte[0], startingBid, expiration, policyIdBytes, assetNameBytes));

        var mintRedeemer = PlutusDataAdapter.convert(new CfAuctionValidator.Bid());
        var createTx = new ScriptTx()
                .mintAsset(script, List.of(auctionAsset), mintRedeemer, scriptAddr, auctionDatum);

        var latestBlock = backend.getBlockService().getLatestBlock();
        long currentSlot = latestBlock.getValue().getSlot();

        var createResult = quickTx.compose(createTx)
                .withSigner(SignerProviders.signerFrom(seller))
                .feePayer(seller.baseAddress())
                .collateralPayer(seller.baseAddress())
                .withRequiredSigners(sellerPkh)
                .validTo(currentSlot + 10) // before expiration
                .ignoreScriptCostEvaluationError(true)
                .withTxEvaluator(YaciHelper.julcEvaluator(backend))
                .complete();

        if (!createResult.isSuccessful()) {
            System.out.println("Create auction failed: " + createResult);
            System.exit(1);
        }
        var createTxHash = createResult.getValue();
        YaciHelper.waitForConfirmation(backend, createTxHash);
        System.out.println("Auction created! Tx: " + createTxHash);

        // Step 2: Bidder places a BID
        System.out.println("Step 2: Bidder placing bid of 5 ADA...");
        var scriptUtxo = YaciHelper.findUtxo(backend, scriptAddr, createTxHash);

        BigInteger bidAmount = BigInteger.valueOf(5_000_000); // 5 ADA

        // Bid = tag 0
        var bidRedeemer = PlutusDataAdapter.convert(new CfAuctionValidator.Bid());

        // Updated datum with bidder and bid amount
        var bidDatum = PlutusDataAdapter.convert(new CfAuctionValidator.AuctionDatum(
                sellerPkh, bidderPkh, bidAmount, expiration, policyIdBytes, assetNameBytes));

        var bidTx = new ScriptTx()
                .collectFrom(scriptUtxo, bidRedeemer)
                .payToContract(scriptAddr,
                        List.of(Amount.lovelace(bidAmount),
                                new Amount(auctionTokenUnit, BigInteger.ONE)),
                        bidDatum)
                .attachSpendingValidator(script);

        latestBlock = backend.getBlockService().getLatestBlock();
        currentSlot = latestBlock.getValue().getSlot();

        var bidResult = quickTx.compose(bidTx)
                .withSigner(SignerProviders.signerFrom(bidder))
                .feePayer(bidder.baseAddress())
                .collateralPayer(bidder.baseAddress())
                .withRequiredSigners(bidderPkh)
                .validTo(currentSlot + 10) // before expiration
                .withTxEvaluator(YaciHelper.julcEvaluator(backend))
                .complete();

        if (!bidResult.isSuccessful()) {
            System.out.println("Bid failed: " + bidResult);
            System.exit(1);
        }
        var bidTxHash = bidResult.getValue();
        YaciHelper.waitForConfirmation(backend, bidTxHash);
        System.out.println("Bid placed! Tx: " + bidTxHash);

        // Wait for expiration to pass
        long waitMs = expiration.longValueExact() - System.currentTimeMillis() + 2000;
        if (waitMs > 0) {
            System.out.println("Waiting " + (waitMs / 1000) + "s for expiration...");
            Thread.sleep(waitMs);
        }

        // Step 3: END auction — winner (bidder) gets NFT, seller gets ADA
        System.out.println("Step 3: Ending auction (winner gets NFT, seller gets ADA)...");
        var auctionUtxo = YaciHelper.findUtxo(backend, scriptAddr, bidTxHash);

        // End = tag 2
        var endRedeemer = PlutusDataAdapter.convert(new CfAuctionValidator.End());

        latestBlock = backend.getBlockService().getLatestBlock();
        currentSlot = latestBlock.getValue().getSlot();

        var endTx = new ScriptTx()
                .collectFrom(auctionUtxo, endRedeemer)
                .payToAddress(seller.baseAddress(), Amount.lovelace(bidAmount))     // seller gets ADA
                .payToAddress(bidder.baseAddress(), new Amount(auctionTokenUnit, BigInteger.ONE)) // winner gets NFT
                .attachSpendingValidator(script);

        var endResult = quickTx.compose(endTx)
                .withSigner(SignerProviders.signerFrom(seller))
                .feePayer(seller.baseAddress())
                .collateralPayer(seller.baseAddress())
                .validFrom(currentSlot) // after expiration
                .withTxEvaluator(YaciHelper.julcEvaluator(backend))
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
