package com.example.cftemplates.pricebet.offchain;

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
import com.example.cftemplates.pricebet.onchain.CfPriceBetValidator;
import com.example.offchain.YaciHelper;

import java.math.BigInteger;

/**
 * Off-chain demo for CfPriceBetValidator.
 * Step 1: Owner creates bet (lock ADA at script with PriceBetDatum, empty player).
 * Step 2: Player joins (spend from script, continuing output with doubled pot + player set).
 * Step 3: Timeout (after deadline, owner reclaims).
 */
public class PriceBetDemo {

    public static void main(String[] args) throws Exception {
        if (!YaciHelper.isYaciReachable()) {
            System.out.println("Yaci DevKit not reachable. Skipping demo.");
            System.exit(1);
        }

        var owner = new Account(Networks.testnet());
        var player = new Account(Networks.testnet());
        byte[] ownerPkh = owner.hdKeyPair().getPublicKey().getKeyHash();
        byte[] playerPkh = player.hdKeyPair().getPublicKey().getKeyHash();

        // Oracle VKH (dummy for demo — no oracle reference input needed for Join/Timeout)
        byte[] oracleVkh = new byte[28];

        // Deadline in the future for Join, then we'll use Timeout after
        // Use a deadline far enough in the future for Join step
        BigInteger betAmount = BigInteger.valueOf(5_000_000); // 5 ADA
        // Deadline: current time + 10 minutes (in POSIX milliseconds)
        BigInteger deadline = BigInteger.valueOf(System.currentTimeMillis() + 600_000);
        BigInteger targetRate = BigInteger.valueOf(100);

        YaciHelper.topUp(owner.baseAddress(), 1000);
        YaciHelper.topUp(player.baseAddress(), 1000);

        var script = JulcScriptLoader.load(CfPriceBetValidator.class);
        var scriptAddr = AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();

        var backend = YaciHelper.createBackendService();
        var quickTx = new QuickTxBuilder(backend);

        // Step 1: Owner creates bet
        System.out.println("Step 1: Owner creating bet...");
        // PriceBetDatum(owner, player(empty), oracleVkh, targetRate, deadline, betAmount)
        var priceBetDatum = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(
                        new BytesPlutusData(ownerPkh),
                        new BytesPlutusData(new byte[0]),   // empty player
                        new BytesPlutusData(oracleVkh),
                        BigIntPlutusData.of(targetRate),
                        BigIntPlutusData.of(deadline),
                        BigIntPlutusData.of(betAmount)))
                .build();

        var lockTx = new Tx()
                .payToContract(scriptAddr, Amount.lovelace(betAmount), priceBetDatum)
                .from(owner.baseAddress());

        var lockResult = quickTx.compose(lockTx)
                .withSigner(SignerProviders.signerFrom(owner))
                .complete();

        if (!lockResult.isSuccessful()) {
            System.out.println("Create bet failed: " + lockResult);
            System.exit(1);
        }
        var lockTxHash = lockResult.getValue();
        YaciHelper.waitForConfirmation(backend, lockTxHash);
        System.out.println("Bet created! Tx: " + lockTxHash);

        // Step 2: Player joins (spend from script, continuing output with doubled pot + player set)
        System.out.println("Step 2: Player joining bet...");
        var scriptUtxo = YaciHelper.findUtxo(backend, scriptAddr, lockTxHash);

        // Join = Constr(0)
        var joinRedeemer = ConstrPlutusData.of(0);

        // New datum with player set
        var joinedDatum = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(
                        new BytesPlutusData(ownerPkh),
                        new BytesPlutusData(playerPkh),     // player now set
                        new BytesPlutusData(oracleVkh),
                        BigIntPlutusData.of(targetRate),
                        BigIntPlutusData.of(deadline),
                        BigIntPlutusData.of(betAmount)))
                .build();

        // Doubled pot = 2 * betAmount
        BigInteger doubledPot = betAmount.multiply(BigInteger.TWO);

        var latestBlock = backend.getBlockService().getLatestBlock();
        long currentSlot = latestBlock.getValue().getSlot();

        var joinTx = new ScriptTx()
                .collectFrom(scriptUtxo, joinRedeemer)
                .payToContract(scriptAddr, Amount.lovelace(doubledPot), joinedDatum)
                .attachSpendingValidator(script);

        var joinResult = quickTx.compose(joinTx)
                .withSigner(SignerProviders.signerFrom(player))
                .feePayer(player.baseAddress())
                .collateralPayer(player.baseAddress())
                .withRequiredSigners(playerPkh)
                .validFrom(currentSlot)
                .complete();

        if (!joinResult.isSuccessful()) {
            System.out.println("Join failed: " + joinResult);
            System.exit(1);
        }
        var joinTxHash = joinResult.getValue();
        YaciHelper.waitForConfirmation(backend, joinTxHash);
        System.out.println("Player joined! Tx: " + joinTxHash);

        // Step 3: Timeout (owner reclaims after deadline)
        // For demo, create a NEW bet with deadline in the past for Timeout
        System.out.println("Step 3: Testing timeout (owner reclaims)...");
        BigInteger pastDeadline = BigInteger.valueOf(System.currentTimeMillis() - 60_000);

        var timeoutDatum = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(
                        new BytesPlutusData(ownerPkh),
                        new BytesPlutusData(playerPkh),
                        new BytesPlutusData(oracleVkh),
                        BigIntPlutusData.of(targetRate),
                        BigIntPlutusData.of(pastDeadline),
                        BigIntPlutusData.of(betAmount)))
                .build();

        var lockForTimeoutTx = new Tx()
                .payToContract(scriptAddr, Amount.lovelace(doubledPot), timeoutDatum)
                .from(owner.baseAddress());

        var lockForTimeoutResult = quickTx.compose(lockForTimeoutTx)
                .withSigner(SignerProviders.signerFrom(owner))
                .complete();

        if (!lockForTimeoutResult.isSuccessful()) {
            System.out.println("Lock for timeout failed: " + lockForTimeoutResult);
            System.exit(1);
        }
        var lockForTimeoutTxHash = lockForTimeoutResult.getValue();
        YaciHelper.waitForConfirmation(backend, lockForTimeoutTxHash);

        var timeoutUtxo = YaciHelper.findUtxo(backend, scriptAddr, lockForTimeoutTxHash);

        // Timeout = Constr(2)
        var timeoutRedeemer = ConstrPlutusData.of(2);

        latestBlock = backend.getBlockService().getLatestBlock();
        currentSlot = latestBlock.getValue().getSlot();

        var timeoutTx = new ScriptTx()
                .collectFrom(timeoutUtxo, timeoutRedeemer)
                .payToAddress(owner.baseAddress(), Amount.lovelace(doubledPot))
                .attachSpendingValidator(script);

        // Fee payer (owner) also receives the pot from the script.
        // Add a companion Tx to force inclusion of the owner's wallet UTXO,
        // so the fee is deducted from the wallet change output instead of the payout.
        var feeTx = new Tx()
                .payToAddress(owner.baseAddress(), Amount.ada(1))
                .from(owner.baseAddress());

        var timeoutResult = quickTx.compose(timeoutTx, feeTx)
                .withSigner(SignerProviders.signerFrom(owner))
                .feePayer(owner.baseAddress())
                .collateralPayer(owner.baseAddress())
                .withRequiredSigners(ownerPkh)
                .validFrom(currentSlot)
                .complete();

        if (!timeoutResult.isSuccessful()) {
            System.out.println("Timeout failed: " + timeoutResult);
            System.exit(1);
        }
        YaciHelper.waitForConfirmation(backend, timeoutResult.getValue());
        System.out.println("Timeout reclaim! Tx: " + timeoutResult.getValue());
        System.out.println("Price bet demo completed successfully!");
    }
}
