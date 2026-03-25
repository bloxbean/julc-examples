package com.example.swap.offchain;

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
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.example.offchain.YaciHelper;
import com.example.swap.onchain.SwapOrder;

import java.math.BigInteger;

/**
 * End-to-end demo: DEX swap order validator.
 * <p>
 * Demonstrates:
 * 1. Maker places an ADA swap order (offers 50 ADA, requests 45 ADA back)
 * 2. Taker fills the order by paying requested amount to maker
 * 3. Alternatively: maker cancels the order
 * <p>
 * Requires Yaci Devkit running locally.
 */
public class SwapOrderDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("=== SwapOrder E2E Demo ===\n");

        if (!YaciHelper.isYaciReachable()) {
            System.out.println("ERROR: Yaci Devkit not reachable at " + YaciHelper.YACI_BASE_URL);
            System.out.println("Start it with: yaci-cli devkit start");
            System.exit(1);
        }

        // 1. Load pre-compiled validator (no params)
        var loadResult = JulcScriptLoader.loadWithSourceMap(SwapOrder.class);
        var script = loadResult.script();
        var scriptAddr = AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();
        System.out.println("Script address: " + scriptAddr);

        // 2. Create maker and taker accounts
        var maker = new Account(Networks.testnet());
        var taker = new Account(Networks.testnet());
        byte[] makerPkh = maker.hdKeyPair().getPublicKey().getKeyHash();
        byte[] takerPkh = taker.hdKeyPair().getPublicKey().getKeyHash();
        System.out.println("Maker: " + maker.baseAddress().substring(0, 20) + "...");
        System.out.println("Taker: " + taker.baseAddress().substring(0, 20) + "...");

        // Fund both accounts
        YaciHelper.topUp(maker.baseAddress(), 1000);
        YaciHelper.topUp(taker.baseAddress(), 1000);

        var backend = YaciHelper.createBackendService();
        var quickTx = new QuickTxBuilder(backend);

        // 3. Maker places order: offers 50 ADA, requests 45 ADA back
        // OrderDatum = Constr(0, [maker, offeredPolicy, offeredToken, offeredAmount,
        //                         requestedPolicy, requestedToken, requestedAmount])
        // For ADA: policy = empty bytes, token = empty bytes
        BigInteger offeredAmount = BigInteger.valueOf(50_000_000);
        BigInteger requestedAmount = BigInteger.valueOf(45_000_000);

        var datum = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(
                        new BytesPlutusData(makerPkh),
                        BytesPlutusData.of(""),        // offeredPolicy (ADA)
                        BytesPlutusData.of(""),        // offeredToken (ADA)
                        BigIntPlutusData.of(offeredAmount),
                        BytesPlutusData.of(""),        // requestedPolicy (ADA)
                        BytesPlutusData.of(""),        // requestedToken (ADA)
                        BigIntPlutusData.of(requestedAmount)))
                .build();

        System.out.println("\n--- Step 1: Maker places swap order (50 ADA offered, 45 ADA requested) ---");
        var lockTx = new Tx()
                .payToContract(scriptAddr, Amount.ada(50), datum)
                .from(maker.baseAddress());

        var lockResult = quickTx.compose(lockTx)
                .withSigner(SignerProviders.signerFrom(maker))
                .complete();

        if (!lockResult.isSuccessful()) {
            System.out.println("FAILED to lock: " + lockResult);
            System.exit(1);
        }
        var lockTxHash = lockResult.getValue();
        System.out.println("Lock tx: " + lockTxHash);
        YaciHelper.waitForConfirmation(backend, lockTxHash);

        // 4. Find the script UTXO
        var scriptUtxo = YaciHelper.findUtxo(backend, scriptAddr, lockTxHash);
        System.out.println("Script UTXO: " + scriptUtxo.getTxHash() + "#" + scriptUtxo.getOutputIndex());

        // 5. Taker fills the order: pay 45 ADA to maker
        // FillOrder = Constr(0, [])
        System.out.println("\n--- Step 2: Taker fills order (pays 45 ADA to maker) ---");
        var redeemer = ConstrPlutusData.of(0);

        var fillTx = new ScriptTx()
                .collectFrom(scriptUtxo, redeemer)
                .payToAddress(maker.enterpriseAddress(), Amount.ada(45))
                .attachSpendingValidator(script);

        var julcEvaluator = YaciHelper.julcEvaluator(backend);
        julcEvaluator.enableTracing(true);
        julcEvaluator.registerScript(HexUtil.encodeHexString(script.getScriptHash()), loadResult);
        var fillResult = quickTx.compose(fillTx)
                .withSigner(SignerProviders.signerFrom(taker))
                .feePayer(taker.baseAddress())
                .collateralPayer(taker.baseAddress())
                .withTxEvaluator(julcEvaluator)
                .ignoreScriptCostEvaluationError(true)
                .complete();

        if (!fillResult.isSuccessful()) {
            System.out.println("FAILED to fill: " + fillResult);
            System.exit(1);
        }
        System.out.println("Fill tx: " + fillResult.getValue());
        YaciHelper.waitForConfirmation(backend, fillResult.getValue());

        System.out.println("\n=== SwapOrder Demo PASSED ===");
    }
}
