package com.example.cftemplates.bet.offchain;

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
import com.example.cftemplates.bet.onchain.CfBetValidator;
import com.example.offchain.YaciHelper;

/**
 * Off-chain demo for CfBetValidator.
 * Player1 creates bet → Player2 joins → Oracle announces winner.
 */
public class BetDemo {

    public static void main(String[] args) throws Exception {
        if (!YaciHelper.isYaciReachable()) {
            System.out.println("Yaci DevKit not reachable. Skipping demo.");
            System.exit(1);
        }

        var player1 = new Account(Networks.testnet());
        var player2 = new Account(Networks.testnet());
        var oracle = new Account(Networks.testnet());
        byte[] player1Pkh = player1.hdKeyPair().getPublicKey().getKeyHash();
        byte[] player2Pkh = player2.hdKeyPair().getPublicKey().getKeyHash();
        byte[] oraclePkh = oracle.hdKeyPair().getPublicKey().getKeyHash();

        // Expiration in the past so oracle can announce immediately
        java.math.BigInteger expiration = java.math.BigInteger.valueOf(System.currentTimeMillis() - 60_000);
        java.math.BigInteger betAmount = java.math.BigInteger.valueOf(10_000_000); // 10 ADA

        YaciHelper.topUp(player1.baseAddress(), 1000);
        YaciHelper.topUp(player2.baseAddress(), 1000);
        YaciHelper.topUp(oracle.baseAddress(), 1000);

        var script = JulcScriptLoader.load(CfBetValidator.class);
        var scriptAddr = AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();

        var backend = YaciHelper.createBackendService();
        var quickTx = new QuickTxBuilder(backend);

        // Step 1: Player1 creates bet
        System.out.println("Step 1: Player1 creating bet...");
        var betDatum = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(
                        new BytesPlutusData(player1Pkh),
                        new BytesPlutusData(new byte[0]),  // no player2 yet
                        new BytesPlutusData(oraclePkh),
                        BigIntPlutusData.of(expiration)))
                .build();

        var createTx = new Tx()
                .payToContract(scriptAddr, Amount.lovelace(betAmount), betDatum)
                .from(player1.baseAddress());

        var createResult = quickTx.compose(createTx)
                .withSigner(SignerProviders.signerFrom(player1))
                .complete();

        if (!createResult.isSuccessful()) {
            System.out.println("Create bet failed: " + createResult);
            System.exit(1);
        }
        var createTxHash = createResult.getValue();
        YaciHelper.waitForConfirmation(backend, createTxHash);
        System.out.println("Bet created! Tx: " + createTxHash);

        // Step 2: Player2 joins (pot doubles)
        System.out.println("Step 2: Player2 joining bet...");
        var scriptUtxo = YaciHelper.findUtxo(backend, scriptAddr, createTxHash);

        // Join = Constr(0)
        var joinRedeemer = ConstrPlutusData.of(0);

        // Updated datum with player2
        var joinedDatum = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(
                        new BytesPlutusData(player1Pkh),
                        new BytesPlutusData(player2Pkh),
                        new BytesPlutusData(oraclePkh),
                        BigIntPlutusData.of(expiration)))
                .build();

        var joinTx = new ScriptTx()
                .collectFrom(scriptUtxo, joinRedeemer)
                .payToContract(scriptAddr, Amount.lovelace(betAmount.multiply(java.math.BigInteger.TWO)), joinedDatum)
                .attachSpendingValidator(script);

        var joinResult = quickTx.compose(joinTx)
                .withSigner(SignerProviders.signerFrom(player2))
                .feePayer(player2.baseAddress())
                .collateralPayer(player2.baseAddress())
                .withRequiredSigners(player2Pkh)
                .complete();

        if (!joinResult.isSuccessful()) {
            System.out.println("Join failed: " + joinResult);
            System.exit(1);
        }
        var joinTxHash = joinResult.getValue();
        YaciHelper.waitForConfirmation(backend, joinTxHash);
        System.out.println("Player2 joined! Tx: " + joinTxHash);

        // Step 3: Oracle announces player1 as winner
        System.out.println("Step 3: Oracle announcing winner (player1)...");
        var activeUtxo = YaciHelper.findUtxo(backend, scriptAddr, joinTxHash);

        // AnnounceWinner(player1) = Constr(1, [player1Pkh])
        var announceRedeemer = ConstrPlutusData.builder()
                .alternative(1)
                .data(ListPlutusData.of(new BytesPlutusData(player1Pkh)))
                .build();

        var latestBlock = backend.getBlockService().getLatestBlock();
        long currentSlot = latestBlock.getValue().getSlot();

        var announceTx = new ScriptTx()
                .collectFrom(activeUtxo, announceRedeemer)
                .payToAddress(player1.baseAddress(), Amount.lovelace(betAmount.multiply(java.math.BigInteger.TWO)))
                .attachSpendingValidator(script);

        var announceResult = quickTx.compose(announceTx)
                .withSigner(SignerProviders.signerFrom(oracle))
                .feePayer(oracle.baseAddress())
                .collateralPayer(oracle.baseAddress())
                .withRequiredSigners(oraclePkh)
                .validFrom(currentSlot)
                .complete();

        if (!announceResult.isSuccessful()) {
            System.out.println("Announce winner failed: " + announceResult);
            System.exit(1);
        }
        YaciHelper.waitForConfirmation(backend, announceResult.getValue());
        System.out.println("Winner announced! Tx: " + announceResult.getValue());
        System.out.println("Bet demo completed successfully!");
    }
}
