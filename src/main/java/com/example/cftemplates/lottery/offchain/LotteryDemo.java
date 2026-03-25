package com.example.cftemplates.lottery.offchain;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.example.cftemplates.lottery.onchain.CfLotteryValidator;
import com.example.offchain.YaciHelper;

import java.math.BigInteger;
import java.util.List;

/**
 * Off-chain demo for CfLotteryValidator.
 * Step 1: Create game (mint LOTTERY_TOKEN, both players sign, LotteryDatum with commits).
 * Step 2: Player1 reveals (spend with Reveal1 redeemer, continuing output with updated n1).
 * Step 3: Settle (both revealed — for demo, do Reveal2 then Settle).
 */
public class LotteryDemo {

    public static void main(String[] args) throws Exception {
        if (!YaciHelper.isYaciReachable()) {
            System.out.println("Yaci DevKit not reachable. Skipping demo.");
            System.exit(1);
        }

        var player1 = new Account(Networks.testnet());
        var player2 = new Account(Networks.testnet());
        byte[] p1Pkh = player1.hdKeyPair().getPublicKey().getKeyHash();
        byte[] p2Pkh = player2.hdKeyPair().getPublicKey().getKeyHash();

        YaciHelper.topUp(player1.baseAddress(), 1000);
        YaciHelper.topUp(player2.baseAddress(), 1000);

        // Game index param
        BigInteger gameIndex = BigInteger.valueOf(42);

        var script = JulcScriptLoader.load(CfLotteryValidator.class,
                BigIntPlutusData.of(gameIndex));
        var scriptAddr = AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();
        var policyId = HexUtil.encodeHexString(script.getScriptHash());
        System.out.println("Lottery policy: " + policyId);

        var backend = YaciHelper.createBackendService();
        var quickTx = new QuickTxBuilder(backend);

        // Secret values for commit-reveal — UTF-8 decimal strings
        // (validator parses these as integers via UTF-8 parsing, matching Aiken's int.from_utf8)
        byte[] secret1 = "137".getBytes(); // player1's secret (integer 137)
        byte[] secret2 = "42".getBytes();  // player2's secret (integer 42)
        byte[] commit1 = Blake2bUtil.blake2bHash256(secret1);
        byte[] commit2 = Blake2bUtil.blake2bHash256(secret2);

        // Deadline far in the future
        BigInteger endReveal = BigInteger.valueOf(System.currentTimeMillis() + 600_000);
        BigInteger delta = BigInteger.valueOf(300_000); // 5 min delta

        BigInteger potAmount = BigInteger.valueOf(10_000_000); // 10 ADA

        // Step 1: Create game (mint LOTTERY_TOKEN)
        System.out.println("Step 1: Creating lottery game...");

        // Token name must match on-chain LOTTERY_TOKEN_NAME constant
        byte[] tokenNameBytes = "LOTTERY_TOKEN".getBytes();
        String tokenNameHex = "0x" + HexUtil.encodeHexString(tokenNameBytes);
        String lotteryTokenUnit = policyId + HexUtil.encodeHexString(tokenNameBytes);
        var lotteryAsset = new Asset(tokenNameHex, BigInteger.ONE);

        // Create = tag 0 (mint redeemer)
        var createRedeemer = PlutusDataAdapter.convert(new CfLotteryValidator.Create());

        // LotteryDatum(p1, p2, commit1, commit2, n1=empty, n2=empty, endReveal, delta)
        var lotteryDatum = PlutusDataAdapter.convert(new CfLotteryValidator.LotteryDatum(
                p1Pkh, p2Pkh, commit1, commit2, new byte[0], new byte[0], endReveal, delta));

        // Mint token and lock at script with datum in a single output
        var mintTx = new ScriptTx()
                .mintAsset(script, List.of(lotteryAsset), createRedeemer, scriptAddr, lotteryDatum);

        var createResult = quickTx.compose(mintTx)
                .withSigner(SignerProviders.signerFrom(player1))
                .withSigner(SignerProviders.signerFrom(player2))
                .feePayer(player1.baseAddress())
                .collateralPayer(player1.baseAddress())
                .withRequiredSigners(p1Pkh)
                .withRequiredSigners(p2Pkh)
                .ignoreScriptCostEvaluationError(true)
                .withTxEvaluator(YaciHelper.julcEvaluator(backend))
                .complete();

        if (!createResult.isSuccessful()) {
            System.out.println("Create game failed: " + createResult);
            System.exit(1);
        }
        var createTxHash = createResult.getValue();
        YaciHelper.waitForConfirmation(backend, createTxHash);
        System.out.println("Game created! Tx: " + createTxHash);

        // Step 2: Player1 reveals
        System.out.println("Step 2: Player1 revealing...");
        var gameUtxo = YaciHelper.findUtxo(backend, scriptAddr, createTxHash);

        // Reveal1(n1=secret1) = tag 0
        var reveal1Redeemer = PlutusDataAdapter.convert(new CfLotteryValidator.Reveal1(secret1));

        // Updated datum with n1 revealed
        var reveal1Datum = PlutusDataAdapter.convert(new CfLotteryValidator.LotteryDatum(
                p1Pkh, p2Pkh, commit1, commit2, secret1, new byte[0], endReveal, delta));

        var reveal1Tx = new ScriptTx()
                .collectFrom(gameUtxo, reveal1Redeemer)
                .payToContract(scriptAddr,
                        List.of(Amount.lovelace(potAmount),
                                new Amount(lotteryTokenUnit, BigInteger.ONE)),
                        reveal1Datum)
                .attachSpendingValidator(script);

        var reveal1Result = quickTx.compose(reveal1Tx)
                .withSigner(SignerProviders.signerFrom(player1))
                .feePayer(player1.baseAddress())
                .collateralPayer(player1.baseAddress())
                .withRequiredSigners(p1Pkh)
                .withTxEvaluator(YaciHelper.julcEvaluator(backend))
                .complete();

        if (!reveal1Result.isSuccessful()) {
            System.out.println("Reveal1 failed: " + reveal1Result);
            System.exit(1);
        }
        var reveal1TxHash = reveal1Result.getValue();
        YaciHelper.waitForConfirmation(backend, reveal1TxHash);
        System.out.println("Player1 revealed! Tx: " + reveal1TxHash);

        // Step 3a: Player2 reveals
        System.out.println("Step 3a: Player2 revealing...");
        var reveal1Utxo = YaciHelper.findUtxo(backend, scriptAddr, reveal1TxHash);

        // Reveal2(n2=secret2) = tag 1
        var reveal2Redeemer = PlutusDataAdapter.convert(new CfLotteryValidator.Reveal2(secret2));

        // Updated datum with both revealed
        var reveal2Datum = PlutusDataAdapter.convert(new CfLotteryValidator.LotteryDatum(
                p1Pkh, p2Pkh, commit1, commit2, secret1, secret2, endReveal, delta));

        var reveal2Tx = new ScriptTx()
                .collectFrom(reveal1Utxo, reveal2Redeemer)
                .payToContract(scriptAddr,
                        List.of(Amount.lovelace(potAmount),
                                new Amount(lotteryTokenUnit, BigInteger.ONE)),
                        reveal2Datum)
                .attachSpendingValidator(script);

        var reveal2Result = quickTx.compose(reveal2Tx)
                .withSigner(SignerProviders.signerFrom(player2))
                .feePayer(player2.baseAddress())
                .collateralPayer(player2.baseAddress())
                .withRequiredSigners(p2Pkh)
                .withTxEvaluator(YaciHelper.julcEvaluator(backend))
                .complete();

        if (!reveal2Result.isSuccessful()) {
            System.out.println("Reveal2 failed: " + reveal2Result);
            System.exit(1);
        }
        var reveal2TxHash = reveal2Result.getValue();
        YaciHelper.waitForConfirmation(backend, reveal2TxHash);
        System.out.println("Player2 revealed! Tx: " + reveal2TxHash);

        // Step 3b: Settle (determine winner by parity)
        System.out.println("Step 3b: Settling...");
        var settleUtxo = YaciHelper.findUtxo(backend, scriptAddr, reveal2TxHash);

        // Determine winner off-chain: parse UTF-8 decimal strings then (v1 + v2) % 2 == 1 → player1 wins
        BigInteger v1 = new BigInteger(new String(secret1));  // "137" → 137
        BigInteger v2 = new BigInteger(new String(secret2));  // "42" → 42
        BigInteger sum = v1.add(v2);
        boolean player1Wins = sum.remainder(BigInteger.TWO).equals(BigInteger.ONE);
        Account winner = player1Wins ? player1 : player2;
        byte[] winnerPkh = player1Wins ? p1Pkh : p2Pkh;
        System.out.println("Winner: " + (player1Wins ? "Player1" : "Player2"));

        // Settle = tag 4
        var settleRedeemer = PlutusDataAdapter.convert(new CfLotteryValidator.Settle());

        // Burn token: BurnToken = tag 1 (mint redeemer)
        var burnRedeemer = PlutusDataAdapter.convert(new CfLotteryValidator.BurnToken());
        var burnAsset = new Asset(tokenNameHex, BigInteger.ONE.negate());

        var settleTx = new ScriptTx()
                .collectFrom(settleUtxo, settleRedeemer)
                .payToAddress(winner.baseAddress(), Amount.lovelace(potAmount))
                .mintAsset(script, List.of(burnAsset), burnRedeemer)
                .attachSpendingValidator(script);

        // Fee payer (winner) also receives the pot from the script.
        // Add a companion Tx to force inclusion of the winner's wallet UTXO,
        // so the fee is deducted from the wallet change output instead of the payout.
        var feeTx = new Tx()
                .payToAddress(winner.baseAddress(), Amount.ada(1))
                .from(winner.baseAddress());

        var settleResult = quickTx.compose(settleTx, feeTx)
                .withSigner(SignerProviders.signerFrom(winner))
                .feePayer(winner.baseAddress())
                .collateralPayer(winner.baseAddress())
                .withRequiredSigners(winnerPkh)
                .ignoreScriptCostEvaluationError(true)
                .withTxEvaluator(YaciHelper.julcEvaluator(backend))
                .complete();

        if (!settleResult.isSuccessful()) {
            System.out.println("Settle failed: " + settleResult);
            System.exit(1);
        }
        YaciHelper.waitForConfirmation(backend, settleResult.getValue());
        System.out.println("Settled! Tx: " + settleResult.getValue());
        System.out.println("Lottery demo completed successfully!");
    }
}
