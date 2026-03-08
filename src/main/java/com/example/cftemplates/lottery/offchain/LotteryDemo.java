package com.example.cftemplates.lottery.offchain;

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
import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
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

        // Secret values for commit-reveal
        byte[] secret1 = new byte[]{0x01, 0x02, 0x03, 0x04}; // player1's secret
        byte[] secret2 = new byte[]{0x05, 0x06, 0x07, 0x08}; // player2's secret
        byte[] commit1 = Blake2bUtil.blake2bHash256(secret1);
        byte[] commit2 = Blake2bUtil.blake2bHash256(secret2);

        // Deadline far in the future
        BigInteger endReveal = BigInteger.valueOf(System.currentTimeMillis() + 600_000);
        BigInteger delta = BigInteger.valueOf(300_000); // 5 min delta

        BigInteger potAmount = BigInteger.valueOf(10_000_000); // 10 ADA

        // Step 1: Create game (mint LOTTERY_TOKEN)
        System.out.println("Step 1: Creating lottery game...");

        // Token name = "LOTTERY" (or any name under the policy)
        byte[] tokenNameBytes = "LOTTERY".getBytes();
        String tokenNameHex = "0x" + HexUtil.encodeHexString(tokenNameBytes);
        String lotteryTokenUnit = policyId + HexUtil.encodeHexString(tokenNameBytes);
        var lotteryAsset = new Asset(tokenNameHex, BigInteger.ONE);

        // Create = Constr(0) (mint redeemer)
        var createRedeemer = ConstrPlutusData.of(0);

        // LotteryDatum(p1, p2, commit1, commit2, n1=empty, n2=empty, endReveal, delta)
        var lotteryDatum = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(
                        new BytesPlutusData(p1Pkh),
                        new BytesPlutusData(p2Pkh),
                        new BytesPlutusData(commit1),
                        new BytesPlutusData(commit2),
                        new BytesPlutusData(new byte[0]),   // n1 empty
                        new BytesPlutusData(new byte[0]),   // n2 empty
                        BigIntPlutusData.of(endReveal),
                        BigIntPlutusData.of(delta)))
                .build();

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

        // Reveal1(n1=secret1) = Constr(0, [secret1])
        var reveal1Redeemer = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(new BytesPlutusData(secret1)))
                .build();

        // Updated datum with n1 revealed
        var reveal1Datum = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(
                        new BytesPlutusData(p1Pkh),
                        new BytesPlutusData(p2Pkh),
                        new BytesPlutusData(commit1),
                        new BytesPlutusData(commit2),
                        new BytesPlutusData(secret1),       // n1 = revealed
                        new BytesPlutusData(new byte[0]),   // n2 still empty
                        BigIntPlutusData.of(endReveal),
                        BigIntPlutusData.of(delta)))
                .build();

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

        // Reveal2(n2=secret2) = Constr(1, [secret2])
        var reveal2Redeemer = ConstrPlutusData.builder()
                .alternative(1)
                .data(ListPlutusData.of(new BytesPlutusData(secret2)))
                .build();

        // Updated datum with both revealed
        var reveal2Datum = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(
                        new BytesPlutusData(p1Pkh),
                        new BytesPlutusData(p2Pkh),
                        new BytesPlutusData(commit1),
                        new BytesPlutusData(commit2),
                        new BytesPlutusData(secret1),       // n1 revealed
                        new BytesPlutusData(secret2),       // n2 revealed
                        BigIntPlutusData.of(endReveal),
                        BigIntPlutusData.of(delta)))
                .build();

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

        // Determine winner off-chain: (v1 + v2) % 2 == 1 → player1 wins
        BigInteger v1 = new BigInteger(1, secret1);
        BigInteger v2 = new BigInteger(1, secret2);
        BigInteger sum = v1.add(v2);
        boolean player1Wins = sum.remainder(BigInteger.TWO).equals(BigInteger.ONE);
        Account winner = player1Wins ? player1 : player2;
        byte[] winnerPkh = player1Wins ? p1Pkh : p2Pkh;
        System.out.println("Winner: " + (player1Wins ? "Player1" : "Player2"));

        // Settle = Constr(4) — 5th variant: Reveal1(0), Reveal2(1), Timeout1(2), Timeout2(3), Settle(4)
        var settleRedeemer = ConstrPlutusData.of(4);

        // Burn token: BurnToken = Constr(1) (mint redeemer)
        var burnRedeemer = ConstrPlutusData.of(1);
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
