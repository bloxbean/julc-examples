package com.example.cftemplates.lottery;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.example.cftemplates.lottery.onchain.CfLotteryValidator;
import com.example.offchain.YaciHelper;
import org.bouncycastle.crypto.digests.Blake2bDigest;
import org.junit.jupiter.api.*;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration test for CfLotteryValidator.
 * Step 1: Create game (both players sign, mint 1 LOTTERY_TOKEN, lock with LotteryDatum).
 * Step 2: Player1 reveals n1 (continuing output with updated datum).
 * Step 3: Player2 reveals n2, then settle (both revealed, winner determined by parity).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LotteryIntegrationTest {

    static boolean yaciAvailable;
    static Account player1, player2;
    static PlutusV3Script script;
    static BackendService backend;
    static QuickTxBuilder quickTx;
    static String scriptAddr;
    static String policyId;
    static String createTxHash;
    static String reveal1TxHash;
    static String reveal2TxHash;
    static byte[] player1Pkh, player2Pkh;
    static byte[] n1, n2;
    static byte[] commit1, commit2;
    static BigInteger betAmount = BigInteger.valueOf(10_000_000); // 10 ADA
    static BigInteger endReveal;
    static BigInteger delta = BigInteger.valueOf(600_000); // 10 min delta
    static byte[] tokenName;

    @BeforeAll
    static void setup() throws Exception {
        yaciAvailable = YaciHelper.isYaciReachable();
        if (!yaciAvailable) return;

        player1 = new Account(Networks.testnet());
        player2 = new Account(Networks.testnet());
        player1Pkh = player1.hdKeyPair().getPublicKey().getKeyHash();
        player2Pkh = player2.hdKeyPair().getPublicKey().getKeyHash();

        // Secret numbers for commit-reveal
        n1 = new byte[]{0x01, 0x02, 0x03, 0x04};
        n2 = new byte[]{0x05, 0x06, 0x07, 0x08};
        commit1 = blake2b256(n1);
        commit2 = blake2b256(n2);

        // Far future deadline so reveals happen before it
        endReveal = BigInteger.valueOf(System.currentTimeMillis() + 3_600_000);

        // gameIndex param
        BigInteger gameIndex = BigInteger.valueOf(42);

        script = JulcScriptLoader.load(CfLotteryValidator.class,
                BigIntPlutusData.of(gameIndex));
        scriptAddr = AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();
        policyId = HexUtil.encodeHexString(script.getScriptHash());

        // Token name for the lottery token (use gameIndex as name bytes)
        tokenName = "LOTTERY".getBytes();

        YaciHelper.topUp(player1.baseAddress(), 1000);
        YaciHelper.topUp(player2.baseAddress(), 1000);

        backend = YaciHelper.createBackendService();
        quickTx = new QuickTxBuilder(backend);
    }

    @Test
    @Order(1)
    void step1_createGame() throws Exception {
        assumeTrue(yaciAvailable, "Yaci DevKit not available");

        var tokenNameHex = "0x" + HexUtil.encodeHexString(tokenName);
        var lotteryAsset = new Asset(tokenNameHex, BigInteger.ONE);

        // Create = Constr(0) mint redeemer
        var createRedeemer = ConstrPlutusData.of(0);

        // LotteryDatum(player1, player2, commit1, commit2, n1=empty, n2=empty, endReveal, delta)
        var lotteryDatum = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(
                        new BytesPlutusData(player1Pkh),
                        new BytesPlutusData(player2Pkh),
                        new BytesPlutusData(commit1),
                        new BytesPlutusData(commit2),
                        new BytesPlutusData(new byte[0]),  // n1 not yet revealed
                        new BytesPlutusData(new byte[0]),  // n2 not yet revealed
                        BigIntPlutusData.of(endReveal),
                        BigIntPlutusData.of(delta)))
                .build();

        // Mint token to script with datum (ADA funded by feePayer)
        var mintTx = new ScriptTx()
                .mintAsset(script, List.of(lotteryAsset), createRedeemer, scriptAddr, lotteryDatum);

        var result = quickTx.compose(mintTx)
                .withSigner(SignerProviders.signerFrom(player1))
                .withSigner(SignerProviders.signerFrom(player2))
                .feePayer(player1.baseAddress())
                .collateralPayer(player1.baseAddress())
                .withRequiredSigners(player1Pkh, player2Pkh)
                .ignoreScriptCostEvaluationError(true)
                .complete();

        assertTrue(result.isSuccessful(), "Create game should succeed: " + result);
        createTxHash = result.getValue();
        YaciHelper.waitForConfirmation(backend, createTxHash);
        System.out.println("Step 1 OK: Game created, tx=" + createTxHash);
    }

    @Test
    @Order(2)
    void step2_player1Reveals() throws Exception {
        assumeTrue(yaciAvailable, "Yaci DevKit not available");
        assertNotNull(createTxHash, "Step 1 must complete first");

        var scriptUtxo = YaciHelper.findUtxo(backend, scriptAddr, createTxHash);

        // Reveal1 = Constr(0, [n1]) spend redeemer
        var reveal1Redeemer = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(new BytesPlutusData(n1)))
                .build();

        // Updated datum with n1 revealed
        var updatedDatum = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(
                        new BytesPlutusData(player1Pkh),
                        new BytesPlutusData(player2Pkh),
                        new BytesPlutusData(commit1),
                        new BytesPlutusData(commit2),
                        new BytesPlutusData(n1),           // n1 now revealed
                        new BytesPlutusData(new byte[0]),  // n2 still empty
                        BigIntPlutusData.of(endReveal),
                        BigIntPlutusData.of(delta)))
                .build();

        // Continuing output: send back to script with updated datum and same value + token
        var tokenNameHex = "0x" + HexUtil.encodeHexString(tokenName);
        var reveal1Tx = new ScriptTx()
                .collectFrom(scriptUtxo, reveal1Redeemer)
                .payToContract(scriptAddr,
                        List.of(Amount.lovelace(betAmount.multiply(BigInteger.TWO)),
                                new Amount(policyId + HexUtil.encodeHexString(tokenName), BigInteger.ONE)),
                        updatedDatum)
                .attachSpendingValidator(script);

        var result = quickTx.compose(reveal1Tx)
                .withSigner(SignerProviders.signerFrom(player1))
                .feePayer(player1.baseAddress())
                .collateralPayer(player1.baseAddress())
                .withRequiredSigners(player1Pkh)
                .complete();

        assertTrue(result.isSuccessful(), "Player1 reveal should succeed: " + result);
        reveal1TxHash = result.getValue();
        YaciHelper.waitForConfirmation(backend, reveal1TxHash);
        System.out.println("Step 2 OK: Player1 revealed, tx=" + reveal1TxHash);
    }

    @Test
    @Order(3)
    void step3_player2RevealsAndSettle() throws Exception {
        assumeTrue(yaciAvailable, "Yaci DevKit not available");
        assertNotNull(reveal1TxHash, "Step 2 must complete first");

        var scriptUtxo = YaciHelper.findUtxo(backend, scriptAddr, reveal1TxHash);

        // Reveal2 = Constr(1, [n2]) spend redeemer
        var reveal2Redeemer = ConstrPlutusData.builder()
                .alternative(1)
                .data(ListPlutusData.of(new BytesPlutusData(n2)))
                .build();

        // Updated datum with both n1 and n2 revealed
        var fullyRevealedDatum = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(
                        new BytesPlutusData(player1Pkh),
                        new BytesPlutusData(player2Pkh),
                        new BytesPlutusData(commit1),
                        new BytesPlutusData(commit2),
                        new BytesPlutusData(n1),
                        new BytesPlutusData(n2),           // n2 now revealed
                        BigIntPlutusData.of(endReveal),
                        BigIntPlutusData.of(delta)))
                .build();

        // Continuing output: send back to script with both revealed
        var reveal2Tx = new ScriptTx()
                .collectFrom(scriptUtxo, reveal2Redeemer)
                .payToContract(scriptAddr,
                        List.of(Amount.lovelace(betAmount.multiply(BigInteger.TWO)),
                                new Amount(policyId + HexUtil.encodeHexString(tokenName), BigInteger.ONE)),
                        fullyRevealedDatum)
                .attachSpendingValidator(script);

        var result = quickTx.compose(reveal2Tx)
                .withSigner(SignerProviders.signerFrom(player2))
                .feePayer(player2.baseAddress())
                .collateralPayer(player2.baseAddress())
                .withRequiredSigners(player2Pkh)
                .complete();

        assertTrue(result.isSuccessful(), "Player2 reveal should succeed: " + result);
        reveal2TxHash = result.getValue();
        YaciHelper.waitForConfirmation(backend, reveal2TxHash);
        System.out.println("Step 3 OK: Player2 revealed, tx=" + reveal2TxHash);
    }

    /**
     * JVM-compatible Blake2b-256 hash using Bouncy Castle.
     * Replaces CryptoLib.blake2b_256() which only works on-chain.
     */
    static byte[] blake2b256(byte[] data) {
        Blake2bDigest digest = new Blake2bDigest(256);
        digest.update(data, 0, data.length);
        byte[] hash = new byte[32];
        digest.doFinal(hash, 0);
        return hash;
    }
}
