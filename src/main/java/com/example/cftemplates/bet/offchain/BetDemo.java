package com.example.cftemplates.bet.offchain;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.example.cftemplates.bet.onchain.CfBetValidator;
import com.example.offchain.YaciHelper;

import java.math.BigInteger;
import java.util.List;

/**
 * Off-chain demo for CfBetValidator.
 * Step 1: Player1 creates bet (mint bet token via ScriptTx).
 * Step 2: Player2 joins (validTo before expiration).
 * Step 3: Oracle announces winner (validFrom after expiration).
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

        // Expiration 15 seconds in the future — enough time for CREATE + JOIN,
        // then we wait for it to pass before ANNOUNCE_WINNER
        BigInteger expiration = BigInteger.valueOf(System.currentTimeMillis() + 15_000);
        BigInteger betAmount = BigInteger.valueOf(10_000_000); // 10 ADA

        YaciHelper.topUp(player1.baseAddress(), 1000);
        YaciHelper.topUp(player2.baseAddress(), 1000);
        YaciHelper.topUp(oracle.baseAddress(), 1000);

        var script = JulcScriptLoader.load(CfBetValidator.class);
        var scriptAddr = AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();
        var policyId = HexUtil.encodeHexString(script.getScriptHash());
        System.out.println("Bet policy: " + policyId);

        var backend = YaciHelper.createBackendService();
        var quickTx = new QuickTxBuilder(backend);

        // Step 1: Player1 creates bet (mint bet token)
        System.out.println("Step 1: Player1 creating bet (minting bet token)...");

        byte[] tokenNameBytes = "BET".getBytes();
        String tokenNameHex = "0x" + HexUtil.encodeHexString(tokenNameBytes);
        var betAsset = new Asset(tokenNameHex, BigInteger.ONE);

        // Mint redeemer (raw PlutusData — the mint handler takes PlutusData redeemer)
        var mintRedeemer = ConstrPlutusData.of(0);

        // BetDatum(player1, empty player2, oracle, expiration)
        var betDatum = PlutusDataAdapter.convert(new CfBetValidator.BetDatum(
                player1Pkh, new byte[0], oraclePkh, expiration));

        // Mint bet token and send betAmount + token to script address with datum
        String betTokenUnit = policyId + HexUtil.encodeHexString(tokenNameBytes);
        var createTx = new ScriptTx()
                .mintAsset(script, List.of(betAsset), mintRedeemer)
                .payToContract(scriptAddr,
                        List.of(Amount.lovelace(betAmount), new Amount(betTokenUnit, BigInteger.ONE)),
                        betDatum);

        // Get current slot for validity range (must be before expiration)
        var latestBlock = backend.getBlockService().getLatestBlock();
        long currentSlot = latestBlock.getValue().getSlot();

        var createResult = quickTx.compose(createTx)
                .withSigner(SignerProviders.signerFrom(player1))
                .feePayer(player1.baseAddress())
                .collateralPayer(player1.baseAddress())
                .withRequiredSigners(player1Pkh)
                .validTo(currentSlot + 10) // upper bound before expiration
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

        // Join = tag 0
        var joinRedeemer = PlutusDataAdapter.convert(new CfBetValidator.Join());

        // Updated datum with player2
        var joinedDatum = PlutusDataAdapter.convert(new CfBetValidator.BetDatum(
                player1Pkh, player2Pkh, oraclePkh, expiration));

        var joinTx = new ScriptTx()
                .collectFrom(scriptUtxo, joinRedeemer)
                .payToContract(scriptAddr,
                        List.of(Amount.lovelace(betAmount.multiply(BigInteger.TWO)),
                                new Amount(betTokenUnit, BigInteger.ONE)),
                        joinedDatum)
                .attachSpendingValidator(script);

        latestBlock = backend.getBlockService().getLatestBlock();
        currentSlot = latestBlock.getValue().getSlot();

        var joinResult = quickTx.compose(joinTx)
                .withSigner(SignerProviders.signerFrom(player2))
                .feePayer(player2.baseAddress())
                .collateralPayer(player2.baseAddress())
                .withRequiredSigners(player2Pkh)
                .validTo(currentSlot + 10) // upper bound before expiration
                .complete();

        if (!joinResult.isSuccessful()) {
            System.out.println("Join failed: " + joinResult);
            System.exit(1);
        }
        var joinTxHash = joinResult.getValue();
        YaciHelper.waitForConfirmation(backend, joinTxHash);
        System.out.println("Player2 joined! Tx: " + joinTxHash);

        // Wait for expiration to pass
        long waitMs = expiration.longValueExact() - System.currentTimeMillis() + 2000;
        if (waitMs > 0) {
            System.out.println("Waiting " + (waitMs / 1000) + "s for expiration...");
            Thread.sleep(waitMs);
        }

        // Step 3: Oracle announces player1 as winner
        System.out.println("Step 3: Oracle announcing winner (player1)...");
        var activeUtxo = YaciHelper.findUtxo(backend, scriptAddr, joinTxHash);

        // AnnounceWinner(player1) = tag 1
        var announceRedeemer = PlutusDataAdapter.convert(new CfBetValidator.AnnounceWinner(player1Pkh));

        latestBlock = backend.getBlockService().getLatestBlock();
        currentSlot = latestBlock.getValue().getSlot();

        var announceTx = new ScriptTx()
                .collectFrom(activeUtxo, announceRedeemer)
                .payToAddress(player1.baseAddress(), Amount.lovelace(betAmount.multiply(BigInteger.TWO)))
                .attachSpendingValidator(script);

        var announceResult = quickTx.compose(announceTx)
                .withSigner(SignerProviders.signerFrom(oracle))
                .feePayer(oracle.baseAddress())
                .collateralPayer(oracle.baseAddress())
                .withRequiredSigners(oraclePkh)
                .validFrom(currentSlot) // lower bound after expiration
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
