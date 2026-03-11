package com.example.cftemplates.simplewallet;

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
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.example.cftemplates.simplewallet.onchain.CfSimpleWalletValidator;
import com.example.cftemplates.simplewallet.onchain.CfWalletFundsValidator;
import com.example.offchain.YaciHelper;
import org.junit.jupiter.api.*;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Integration test for CfSimpleWalletValidator + CfWalletFundsValidator.
 * Step 1: Mint intent marker (owner signs, 1 INTENT token minted, output to wallet script).
 * Step 2: Execute payment intent (consume intent UTxO, pay recipient, burn marker).
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SimpleWalletIntegrationTest {

    static boolean yaciAvailable;
    static Account owner, recipient;
    static PlutusV3Script walletScript;
    static PlutusV3Script fundsScript;
    static BackendService backend;
    static QuickTxBuilder quickTx;
    static String walletScriptAddr;
    static String fundsScriptAddr;
    static String mintTxHash;
    static String fundsTxHash;
    static byte[] ownerPkh;
    static byte[] walletPolicyId;
    static BigInteger paymentAmount = BigInteger.valueOf(5_000_000); // 5 ADA

    @BeforeAll
    static void setup() throws Exception {
        yaciAvailable = YaciHelper.isYaciReachable();
        if (!yaciAvailable) return;

        owner = new Account(Networks.testnet());
        recipient = new Account(Networks.testnet());
        ownerPkh = owner.hdKeyPair().getPublicKey().getKeyHash();

        // Load wallet script (parameterized with owner)
        walletScript = JulcScriptLoader.load(CfSimpleWalletValidator.class,
                new BytesPlutusData(ownerPkh));
        walletScriptAddr = AddressProvider.getEntAddress(walletScript, Networks.testnet()).toBech32();
        walletPolicyId = walletScript.getScriptHash();

        // Load funds script (parameterized with owner + wallet policy)
        fundsScript = JulcScriptLoader.load(CfWalletFundsValidator.class,
                new BytesPlutusData(ownerPkh),
                new BytesPlutusData(walletPolicyId));
        fundsScriptAddr = AddressProvider.getEntAddress(fundsScript, Networks.testnet()).toBech32();

        YaciHelper.topUp(owner.baseAddress(), 1000);

        backend = YaciHelper.createBackendService();
        quickTx = new QuickTxBuilder(backend);
    }

    @Test
    @Order(1)
    void step1_mintIntentMarker() throws Exception {
        assumeTrue(yaciAvailable, "Yaci DevKit not available");

        var tokenName = "INTENT_MARKER".getBytes();
        var tokenNameHex = "0x" + HexUtil.encodeHexString(tokenName);
        var intentAsset = new Asset(tokenNameHex, BigInteger.ONE);

        // MintIntent = tag 0 (sealed: MintIntent=0, BurnIntent=1)
        var mintRedeemer = PlutusDataAdapter.convert(new CfSimpleWalletValidator.MintIntent());

        // PaymentIntent datum: Constr(0, [recipient_address_as_plutusdata, lovelaceAmt, data])
        // For the recipient address, we use the bech32 address bytes as PlutusData
        // The recipient field is PlutusData (address structure)
        // Build a Cardano address as PlutusData: Constr(0, [Constr(0, [BData(pkh)]), Constr(1, [])])
        byte[] recipientPkh = recipient.hdKeyPair().getPublicKey().getKeyHash();
        var recipientAddrData = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(
                        ConstrPlutusData.builder()
                                .alternative(0)
                                .data(ListPlutusData.of(new BytesPlutusData(recipientPkh)))
                                .build(),
                        ConstrPlutusData.builder()
                                .alternative(1) // None for staking credential
                                .data(ListPlutusData.of())
                                .build()))
                .build();

        // PaymentIntent(recipient, lovelaceAmt, data) — recipient is JuLC PlutusData,
        // so we construct manually since recipientAddrData is CCL ConstrPlutusData.
        var paymentIntentDatum = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(recipientAddrData, BigIntPlutusData.of(paymentAmount), new BytesPlutusData("test payment".getBytes())))
                .build();

        // Mint intent token and send to wallet script with datum
        var mintTx = new ScriptTx()
                .mintAsset(walletScript, List.of(intentAsset), mintRedeemer, walletScriptAddr, paymentIntentDatum);

        var result = quickTx.compose(mintTx)
                .withSigner(SignerProviders.signerFrom(owner))
                .feePayer(owner.baseAddress())
                .collateralPayer(owner.baseAddress())
                .withRequiredSigners(ownerPkh)
                .ignoreScriptCostEvaluationError(true)
                .complete();

        assertTrue(result.isSuccessful(), "Mint intent marker should succeed: " + result);
        mintTxHash = result.getValue();
        YaciHelper.waitForConfirmation(backend, mintTxHash);
        System.out.println("Step 1 OK: Intent marker minted, tx=" + mintTxHash);
    }

    @Test
    @Order(2)
    void step2_executePaymentIntent() throws Exception {
        assumeTrue(yaciAvailable, "Yaci DevKit not available");
        assertNotNull(mintTxHash, "Step 1 must complete first");

        // First, fund the funds validator with some ADA
        var fundTx = new Tx()
                .payToContract(fundsScriptAddr, Amount.ada(20),
                        ConstrPlutusData.builder()
                                .alternative(0)
                                .data(ListPlutusData.of())
                                .build())
                .from(owner.baseAddress());

        var fundResult = quickTx.compose(fundTx)
                .withSigner(SignerProviders.signerFrom(owner))
                .complete();

        assertTrue(fundResult.isSuccessful(), "Fund tx should succeed: " + fundResult);
        fundsTxHash = fundResult.getValue();
        YaciHelper.waitForConfirmation(backend, fundsTxHash);
        System.out.println("Step 2a: Funds deposited to funds validator, tx=" + fundsTxHash);

        // Find the intent UTxO at the wallet script address
        var intentUtxo = YaciHelper.findUtxo(backend, walletScriptAddr, mintTxHash);
        // Find the funds UTxO at the funds script address
        var fundsUtxo = YaciHelper.findUtxo(backend, fundsScriptAddr, fundsTxHash);

        // ExecuteTx = tag 0 for funds validator spend redeemer
        var executeTxRedeemer = PlutusDataAdapter.convert(new CfWalletFundsValidator.ExecuteTx());

        // Spend redeemer for wallet script (any PlutusData, just needs owner signature)
        var walletSpendRedeemer = ConstrPlutusData.of(0);

        var tokenName = "INTENT_MARKER".getBytes();
        var tokenNameHex = "0x" + HexUtil.encodeHexString(tokenName);
        var burnAsset = new Asset(tokenNameHex, BigInteger.ONE.negate());

        // BurnIntent = tag 1 for wallet mint redeemer
        var burnRedeemer = PlutusDataAdapter.convert(new CfSimpleWalletValidator.BurnIntent());

        // Build the execute transaction:
        // 1. Collect from funds UTxO (ExecuteTx redeemer)
        // 2. Collect from intent UTxO (owner sig spend)
        // 3. Pay recipient (enterprise address to match the datum's address structure with no staking credential)
        // 4. Burn intent marker
        String recipientEntAddr = AddressProvider.getEntAddress(
                recipient.hdKeyPair().getPublicKey(), Networks.testnet()).toBech32();
        var executeTx = new ScriptTx()
                .collectFrom(fundsUtxo, executeTxRedeemer)
                .collectFrom(intentUtxo, walletSpendRedeemer)
                .payToAddress(recipientEntAddr, Amount.lovelace(paymentAmount))
                .mintAsset(walletScript, List.of(burnAsset), burnRedeemer)
                .attachSpendingValidator(fundsScript)
                .attachSpendingValidator(walletScript);

        var result = quickTx.compose(executeTx)
                .withSigner(SignerProviders.signerFrom(owner))
                .feePayer(owner.baseAddress())
                .collateralPayer(owner.baseAddress())
                .withRequiredSigners(ownerPkh)
                .ignoreScriptCostEvaluationError(true)
                .complete();

        assertTrue(result.isSuccessful(), "Execute payment intent should succeed: " + result);
        YaciHelper.waitForConfirmation(backend, result.getValue());
        System.out.println("Step 2 OK: Payment intent executed, tx=" + result.getValue());
    }
}
