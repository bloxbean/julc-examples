package com.example.offchain;

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
import com.example.validators.AuctionValidator;

import java.math.BigInteger;

/**
 * End-to-end demo: auction validator with sealed interface redeemer dispatch.
 * <p>
 * This demonstrates:
 * 1. Loading a compiled validator that uses sealed interface ADTs
 * 2. Locking ADA with a datum (seller, reservePrice)
 * 3. Bidding with a Bid redeemer (Constr tag 0)
 * 4. Closing with a Close redeemer (Constr tag 1)
 * <p>
 * Requires Yaci Devkit running locally.
 */
public class AuctionDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("=== Auction Validator E2E Demo ===\n");

        if (!YaciHelper.isYaciReachable()) {
            System.out.println("ERROR: Yaci Devkit not reachable at " + YaciHelper.YACI_BASE_URL);
            System.out.println("Start it with: yaci-cli devkit start");
            System.exit(1);
        }

        // 1. Load the pre-compiled validator (no params)
        var script = JulcScriptLoader.load(AuctionValidator.class);
        var scriptAddr = AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();
        System.out.println("Script address: " + scriptAddr);

        // 2. Create seller and bidder accounts
        var seller = new Account(Networks.testnet());
        var bidder = new Account(Networks.testnet());
        byte[] sellerPkh = seller.hdKeyPair().getPublicKey().getKeyHash();
        byte[] bidderPkh = bidder.hdKeyPair().getPublicKey().getKeyHash();
        System.out.println("Seller: " + seller.baseAddress().substring(0, 20) + "...");
        System.out.println("Bidder: " + bidder.baseAddress().substring(0, 20) + "...");

        // Fund both
        YaciHelper.topUp(seller.baseAddress(), 1000);
        YaciHelper.topUp(bidder.baseAddress(), 1000);

        var backend = YaciHelper.createBackendService();
        var quickTx = new QuickTxBuilder(backend);

        // 3. Create datum: AuctionDatum(seller, reservePrice)
        //    reservePrice = 5 ADA = 5_000_000 lovelace
        BigInteger reservePrice = BigInteger.valueOf(5_000_000);

        // AuctionDatum = Constr(0, [BData(sellerPkh), IData(reservePrice)])
        var datum = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(
                        new BytesPlutusData(sellerPkh),
                        BigIntPlutusData.of(reservePrice)))
                .build();

        // 4. Lock 10 ADA to the script address
        System.out.println("\n--- Locking 10 ADA to auction ---");
        var lockTx = new Tx()
                .payToContract(scriptAddr, Amount.ada(10), datum)
                .from(seller.baseAddress());

        var lockResult = quickTx.compose(lockTx)
                .withSigner(SignerProviders.signerFrom(seller))
                .complete();

        if (!lockResult.isSuccessful()) {
            System.out.println("FAILED to lock: " + lockResult);
            System.exit(1);
        }
        var lockTxHash = lockResult.getValue();
        System.out.println("Lock tx: " + lockTxHash);
        YaciHelper.waitForConfirmation(backend, lockTxHash);

        // 5. Find the script UTXO
        var scriptUtxo = YaciHelper.findUtxo(backend, scriptAddr, lockTxHash);
        System.out.println("Script UTXO: " + scriptUtxo.getTxHash() + "#" + scriptUtxo.getOutputIndex());

        // 6. Bid: bidder spends from script with Bid(bidder, 7 ADA) redeemer
        //    Bid = Constr(0, [BData(bidderPkh), IData(7_000_000)])
        System.out.println("\n--- Bidding 7 ADA (bidder signs) ---");
        var redeemer = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(
                        new BytesPlutusData(bidderPkh),
                        BigIntPlutusData.of(7_000_000)))
                .build();

        var bidTx = new ScriptTx()
                .collectFrom(scriptUtxo, redeemer)
                .payToAddress(bidder.baseAddress(), Amount.ada(5))
                .attachSpendingValidator(script);

        var bidResult = quickTx.compose(bidTx)
                .withSigner(SignerProviders.signerFrom(bidder))
                .withRequiredSigners(bidderPkh)
                .feePayer(bidder.baseAddress())
                .collateralPayer(bidder.baseAddress())
                .withTxEvaluator(YaciHelper.julcEvaluator(backend))
                .complete();

        if (!bidResult.isSuccessful()) {
            System.out.println("FAILED to bid: " + bidResult);
            System.exit(1);
        }
        System.out.println("Bid tx: " + bidResult.getValue());
        YaciHelper.waitForConfirmation(backend, bidResult.getValue());

        System.out.println("\n=== Auction Demo PASSED ===");
    }
}
