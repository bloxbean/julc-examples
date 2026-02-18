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
import com.example.validators.EscrowValidator;

import java.math.BigInteger;

/**
 * End-to-end demo: two-party escrow with time-locked refund.
 * <p>
 * This demonstrates:
 * 1. Loading a compiled validator that uses @OnchainLibrary helpers
 * 2. Locking ADA with a 4-field datum (seller, buyer, deadline, price)
 * 3. Completing the escrow with both seller and buyer signatures
 * <p>
 * Requires Yaci Devkit running locally.
 */
public class EscrowDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("=== Escrow Validator E2E Demo ===\n");

        if (!YaciHelper.isYaciReachable()) {
            System.out.println("ERROR: Yaci Devkit not reachable at " + YaciHelper.YACI_BASE_URL);
            System.out.println("Start it with: yaci-cli devkit start");
            System.exit(1);
        }

        // 1. Load the pre-compiled validator (no params)
        var script = JulcScriptLoader.load(EscrowValidator.class);
        var scriptAddr = AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();
        System.out.println("Script address: " + scriptAddr);

        // 2. Create seller and buyer accounts
        var seller = new Account(Networks.testnet());
        var buyer = new Account(Networks.testnet());
        byte[] sellerPkh = seller.hdKeyPair().getPublicKey().getKeyHash();
        byte[] buyerPkh = buyer.hdKeyPair().getPublicKey().getKeyHash();
        System.out.println("Seller: " + seller.baseAddress().substring(0, 20) + "...");
        System.out.println("Buyer:  " + buyer.baseAddress().substring(0, 20) + "...");

        // Fund both
        YaciHelper.topUp(seller.baseAddress(), 1000);
        YaciHelper.topUp(buyer.baseAddress(), 1000);

        var backend = YaciHelper.createBackendService();
        var quickTx = new QuickTxBuilder(backend);

        // 3. Create datum: EscrowDatum(seller, buyer, deadline, price)
        //    deadline = far-future slot so refund is not yet possible
        //    price = 10 ADA = 10_000_000 lovelace
        BigInteger deadline = BigInteger.valueOf(999999999);
        BigInteger price = BigInteger.valueOf(10_000_000);

        var datum = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(
                        new BytesPlutusData(sellerPkh),
                        new BytesPlutusData(buyerPkh),
                        BigIntPlutusData.of(deadline),
                        BigIntPlutusData.of(price)))
                .build();

        // 4. Lock 15 ADA to the script address
        System.out.println("\n--- Locking 15 ADA to escrow ---");
        var lockTx = new Tx()
                .payToContract(scriptAddr, Amount.ada(15), datum)
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

        // 6. Complete the escrow: buyer triggers, both sign
        //    Redeemer: EscrowRedeemer(action=0) = Constr(0, [IData(0)])
        System.out.println("\n--- Completing escrow (both sign, seller paid) ---");
        var redeemer = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(BigIntPlutusData.of(0)))
                .build();

        var unlockTx = new ScriptTx()
                .collectFrom(scriptUtxo, redeemer)
                .payToAddress(seller.baseAddress(), Amount.ada(10))
                .attachSpendingValidator(script);

        var unlockResult = quickTx.compose(unlockTx)
                .withSigner(SignerProviders.signerFrom(seller))
                .withSigner(SignerProviders.signerFrom(buyer))
                .withRequiredSigners(sellerPkh, buyerPkh)
                .feePayer(buyer.baseAddress())
                .collateralPayer(buyer.baseAddress())
                .complete();

        if (!unlockResult.isSuccessful()) {
            System.out.println("FAILED to complete escrow: " + unlockResult);
            System.exit(1);
        }
        System.out.println("Complete tx: " + unlockResult.getValue());
        YaciHelper.waitForConfirmation(backend, unlockResult.getValue());

        System.out.println("\n=== Escrow Demo PASSED ===");
    }
}
