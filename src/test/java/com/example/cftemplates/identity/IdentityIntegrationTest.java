package com.example.cftemplates.identity;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.example.cftemplates.identity.onchain.CfIdentityValidator;
import com.example.offchain.YaciHelper;
import org.junit.jupiter.api.*;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IdentityIntegrationTest {

    static boolean yaciAvailable;
    static Account owner;
    static PlutusV3Script script;
    static BackendService backend;
    static QuickTxBuilder quickTx;
    static String scriptAddr;
    static String createTxHash;
    static String addTxHash;
    static byte[] ownerPkh, delegatePkh;
    static BigInteger lockValue = BigInteger.valueOf(5_000_000);
    static BigInteger expires;

    @BeforeAll
    static void setup() throws Exception {
        yaciAvailable = YaciHelper.isYaciReachable();
        if (!yaciAvailable) return;

        owner = new Account(Networks.testnet());
        var delegate = new Account(Networks.testnet());
        ownerPkh = owner.hdKeyPair().getPublicKey().getKeyHash();
        delegatePkh = delegate.hdKeyPair().getPublicKey().getKeyHash();
        expires = BigInteger.valueOf(System.currentTimeMillis() + 86_400_000);

        script = JulcScriptLoader.load(CfIdentityValidator.class);
        scriptAddr = AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();

        YaciHelper.topUp(owner.baseAddress(), 1000);

        backend = YaciHelper.createBackendService();
        quickTx = new QuickTxBuilder(backend);
    }

    @Test
    @Order(1)
    void step1_createIdentity() throws Exception {
        assumeTrue(yaciAvailable, "Yaci DevKit not available");

        // IdentityDatum(owner, empty delegates)
        var identityDatum = PlutusDataAdapter.convert(new CfIdentityValidator.IdentityDatum(
                ownerPkh, JulcList.empty()));

        var createTx = new Tx()
                .payToContract(scriptAddr, Amount.lovelace(lockValue), identityDatum)
                .from(owner.baseAddress());

        var result = quickTx.compose(createTx)
                .withSigner(SignerProviders.signerFrom(owner))
                .complete();

        assertTrue(result.isSuccessful(), "Create identity should succeed: " + result);
        createTxHash = result.getValue();
        YaciHelper.waitForConfirmation(backend, createTxHash);
        System.out.println("Step 1 OK: Identity created, tx=" + createTxHash);
    }

    @Test
    @Order(2)
    void step2_addDelegate() throws Exception {
        assumeTrue(yaciAvailable, "Yaci DevKit not available");
        assertNotNull(createTxHash, "Step 1 must complete first");

        var scriptUtxo = YaciHelper.findUtxo(backend, scriptAddr, createTxHash);

        // AddDelegate = tag 1
        var addRedeemer = PlutusDataAdapter.convert(new CfIdentityValidator.AddDelegate(
                delegatePkh, expires));

        // New datum with delegate added
        var newDatum = PlutusDataAdapter.convert(new CfIdentityValidator.IdentityDatum(
                ownerPkh, JulcList.of(new CfIdentityValidator.Delegate(delegatePkh, expires))));

        var addTx = new ScriptTx()
                .collectFrom(scriptUtxo, addRedeemer)
                .payToContract(scriptAddr, Amount.lovelace(lockValue), newDatum)
                .attachSpendingValidator(script);

        // Force inclusion of owner's wallet UTXO so fees don't
        // reduce the script output (validator checks exact value preservation)
        var feeTx = new Tx()
                .payToAddress(owner.baseAddress(), Amount.ada(1))
                .from(owner.baseAddress());

        var result = quickTx.compose(addTx, feeTx)
                .withSigner(SignerProviders.signerFrom(owner))
                .feePayer(owner.baseAddress())
                .collateralPayer(owner.baseAddress())
                .withRequiredSigners(ownerPkh)
                .complete();

        assertTrue(result.isSuccessful(), "Add delegate should succeed: " + result);
        addTxHash = result.getValue();
        YaciHelper.waitForConfirmation(backend, addTxHash);
        System.out.println("Step 2 OK: Delegate added, tx=" + addTxHash);
    }

    @Test
    @Order(3)
    void step3_removeDelegate() throws Exception {
        assumeTrue(yaciAvailable, "Yaci DevKit not available");
        assertNotNull(addTxHash, "Step 2 must complete first");

        var activeUtxo = YaciHelper.findUtxo(backend, scriptAddr, addTxHash);

        // RemoveDelegate = tag 2
        var removeRedeemer = PlutusDataAdapter.convert(new CfIdentityValidator.RemoveDelegate(delegatePkh));

        // Back to empty delegates
        var removedDatum = PlutusDataAdapter.convert(new CfIdentityValidator.IdentityDatum(
                ownerPkh, JulcList.empty()));

        var removeTx = new ScriptTx()
                .collectFrom(activeUtxo, removeRedeemer)
                .payToContract(scriptAddr, Amount.lovelace(lockValue), removedDatum)
                .attachSpendingValidator(script);

        // Force inclusion of owner's wallet UTXO so fees don't
        // reduce the script output (validator checks exact value preservation)
        var feeTx = new Tx()
                .payToAddress(owner.baseAddress(), Amount.ada(1))
                .from(owner.baseAddress());

        var result = quickTx.compose(removeTx, feeTx)
                .withSigner(SignerProviders.signerFrom(owner))
                .feePayer(owner.baseAddress())
                .collateralPayer(owner.baseAddress())
                .withRequiredSigners(ownerPkh)
                .complete();

        assertTrue(result.isSuccessful(), "Remove delegate should succeed: " + result);
        YaciHelper.waitForConfirmation(backend, result.getValue());
        System.out.println("Step 3 OK: Delegate removed, tx=" + result.getValue());
    }
}
