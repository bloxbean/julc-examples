package com.example.cftemplates.bet;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.example.cftemplates.bet.onchain.CfBetValidator;
import com.example.offchain.YaciHelper;
import org.junit.jupiter.api.*;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BetIntegrationTest {

    static boolean yaciAvailable;
    static Account player1, player2, oracle;
    static PlutusV3Script script;
    static BackendService backend;
    static QuickTxBuilder quickTx;
    static String scriptAddr;
    static String policyId;
    static byte[] policyIdBytes;
    static String createTxHash;
    static String joinTxHash;
    static byte[] player1Pkh, player2Pkh, oraclePkh;
    static BigInteger betAmount = BigInteger.valueOf(10_000_000);
    static BigInteger expiration;
    static byte[] tokenNameBytes = "BET".getBytes();
    static String betTokenUnit;

    @BeforeAll
    static void setup() throws Exception {
        yaciAvailable = YaciHelper.isYaciReachable();
        if (!yaciAvailable) return;

        player1 = new Account(Networks.testnet());
        player2 = new Account(Networks.testnet());
        oracle = new Account(Networks.testnet());
        player1Pkh = player1.hdKeyPair().getPublicKey().getKeyHash();
        player2Pkh = player2.hdKeyPair().getPublicKey().getKeyHash();
        oraclePkh = oracle.hdKeyPair().getPublicKey().getKeyHash();

        // Expiration 30 seconds in the future — enough time for CREATE + JOIN
        expiration = BigInteger.valueOf(System.currentTimeMillis() + 30_000);

        script = JulcScriptLoader.load(CfBetValidator.class);
        scriptAddr = AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();
        policyIdBytes = script.getScriptHash();
        policyId = HexUtil.encodeHexString(policyIdBytes);
        betTokenUnit = policyId + HexUtil.encodeHexString(tokenNameBytes);

        YaciHelper.topUp(player1.baseAddress(), 1000);
        YaciHelper.topUp(player2.baseAddress(), 1000);
        YaciHelper.topUp(oracle.baseAddress(), 1000);

        backend = YaciHelper.createBackendService();
        quickTx = new QuickTxBuilder(backend);
    }

    @Test
    @Order(1)
    void step1_createBet() throws Exception {
        assumeTrue(yaciAvailable, "Yaci DevKit not available");

        var betDatum = PlutusDataAdapter.convert(new CfBetValidator.BetDatum(
                player1Pkh, new byte[0], oraclePkh, expiration));

        String tokenNameHex = "0x" + HexUtil.encodeHexString(tokenNameBytes);
        var betAsset = new Asset(tokenNameHex, BigInteger.ONE);
        var mintRedeemer = ConstrPlutusData.of(0);

        // Mint token and send betAmount + token to script address with datum
        var createTx = new ScriptTx()
                .mintAsset(script, List.of(betAsset), mintRedeemer)
                .payToContract(scriptAddr,
                        List.of(Amount.lovelace(betAmount), new Amount(betTokenUnit, BigInteger.ONE)),
                        betDatum);

        var latestBlock = backend.getBlockService().getLatestBlock();
        long currentSlot = latestBlock.getValue().getSlot();

        var result = quickTx.compose(createTx)
                .withSigner(SignerProviders.signerFrom(player1))
                .feePayer(player1.baseAddress())
                .collateralPayer(player1.baseAddress())
                .withRequiredSigners(player1Pkh)
                .validTo(currentSlot + 10)
                .complete();

        assertTrue(result.isSuccessful(), "Create bet should succeed: " + result);
        createTxHash = result.getValue();
        YaciHelper.waitForConfirmation(backend, createTxHash);
        System.out.println("Step 1 OK: Bet created, tx=" + createTxHash);
    }

    @Test
    @Order(2)
    void step2_player2Joins() throws Exception {
        assumeTrue(yaciAvailable, "Yaci DevKit not available");
        assertNotNull(createTxHash, "Step 1 must complete first");

        var scriptUtxo = YaciHelper.findUtxo(backend, scriptAddr, createTxHash);

        // Join = tag 0
        var joinRedeemer = PlutusDataAdapter.convert(new CfBetValidator.Join());

        var joinedDatum = PlutusDataAdapter.convert(new CfBetValidator.BetDatum(
                player1Pkh, player2Pkh, oraclePkh, expiration));

        var joinTx = new ScriptTx()
                .collectFrom(scriptUtxo, joinRedeemer)
                .payToContract(scriptAddr,
                        List.of(Amount.lovelace(betAmount.multiply(BigInteger.TWO)),
                                new Amount(betTokenUnit, BigInteger.ONE)),
                        joinedDatum)
                .attachSpendingValidator(script);

        var latestBlock = backend.getBlockService().getLatestBlock();
        long currentSlot = latestBlock.getValue().getSlot();

        var result = quickTx.compose(joinTx)
                .withSigner(SignerProviders.signerFrom(player2))
                .feePayer(player2.baseAddress())
                .collateralPayer(player2.baseAddress())
                .withRequiredSigners(player2Pkh)
                .validTo(currentSlot + 10)
                .complete();

        assertTrue(result.isSuccessful(), "Join should succeed: " + result);
        joinTxHash = result.getValue();
        YaciHelper.waitForConfirmation(backend, joinTxHash);
        System.out.println("Step 2 OK: Player2 joined, tx=" + joinTxHash);
    }

    @Test
    @Order(3)
    void step3_oracleAnnouncesWinner() throws Exception {
        assumeTrue(yaciAvailable, "Yaci DevKit not available");
        assertNotNull(joinTxHash, "Step 2 must complete first");

        // Wait for expiration to pass
        long waitMs = expiration.longValueExact() - System.currentTimeMillis() + 2000;
        if (waitMs > 0) {
            System.out.println("Waiting " + (waitMs / 1000) + "s for expiration...");
            Thread.sleep(waitMs);
        }

        var activeUtxo = YaciHelper.findUtxo(backend, scriptAddr, joinTxHash);

        // AnnounceWinner(player1) = tag 1
        var announceRedeemer = PlutusDataAdapter.convert(new CfBetValidator.AnnounceWinner(player1Pkh));

        var latestBlock = backend.getBlockService().getLatestBlock();
        long currentSlot = latestBlock.getValue().getSlot();

        var announceTx = new ScriptTx()
                .collectFrom(activeUtxo, announceRedeemer)
                .payToAddress(player1.baseAddress(), Amount.lovelace(betAmount.multiply(BigInteger.TWO)))
                .attachSpendingValidator(script);

        var result = quickTx.compose(announceTx)
                .withSigner(SignerProviders.signerFrom(oracle))
                .feePayer(oracle.baseAddress())
                .collateralPayer(oracle.baseAddress())
                .withRequiredSigners(oraclePkh)
                .validFrom(currentSlot)
                .complete();

        assertTrue(result.isSuccessful(), "Announce winner should succeed: " + result);
        YaciHelper.waitForConfirmation(backend, result.getValue());
        System.out.println("Step 3 OK: Winner announced, tx=" + result.getValue());
    }
}
