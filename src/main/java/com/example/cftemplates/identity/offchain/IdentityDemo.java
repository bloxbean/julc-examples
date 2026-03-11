package com.example.cftemplates.identity.offchain;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.example.cftemplates.identity.onchain.CfIdentityValidator;
import com.example.offchain.YaciHelper;

/**
 * Off-chain demo for CfIdentityValidator.
 * Owner creates identity → adds delegate → removes delegate.
 */
public class IdentityDemo {

    public static void main(String[] args) throws Exception {
        if (!YaciHelper.isYaciReachable()) {
            System.out.println("Yaci DevKit not reachable. Skipping demo.");
            System.exit(1);
        }

        var owner = new Account(Networks.testnet());
        var delegate = new Account(Networks.testnet());
        byte[] ownerPkh = owner.hdKeyPair().getPublicKey().getKeyHash();
        byte[] delegatePkh = delegate.hdKeyPair().getPublicKey().getKeyHash();

        java.math.BigInteger expires = java.math.BigInteger.valueOf(System.currentTimeMillis() + 86_400_000); // 24h from now
        java.math.BigInteger lockValue = java.math.BigInteger.valueOf(5_000_000);

        YaciHelper.topUp(owner.baseAddress(), 1000);

        var script = JulcScriptLoader.load(CfIdentityValidator.class);
        var scriptAddr = AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();

        var backend = YaciHelper.createBackendService();
        var quickTx = new QuickTxBuilder(backend);

        // Step 1: Create identity with empty delegates
        System.out.println("Step 1: Creating identity...");
        // IdentityDatum(owner, empty delegates)
        var identityDatum = PlutusDataAdapter.convert(new CfIdentityValidator.IdentityDatum(
                ownerPkh, JulcList.empty()));

        var createTx = new Tx()
                .payToContract(scriptAddr, Amount.lovelace(lockValue), identityDatum)
                .from(owner.baseAddress());

        var createResult = quickTx.compose(createTx)
                .withSigner(SignerProviders.signerFrom(owner))
                .complete();

        if (!createResult.isSuccessful()) {
            System.out.println("Create identity failed: " + createResult);
            System.exit(1);
        }
        var createTxHash = createResult.getValue();
        YaciHelper.waitForConfirmation(backend, createTxHash);
        System.out.println("Identity created! Tx: " + createTxHash);

        // Step 2: Add delegate
        System.out.println("Step 2: Adding delegate...");
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

        var addResult = quickTx.compose(addTx)
                .withSigner(SignerProviders.signerFrom(owner))
                .feePayer(owner.baseAddress())
                .collateralPayer(owner.baseAddress())
                .withRequiredSigners(ownerPkh)
                .complete();

        if (!addResult.isSuccessful()) {
            System.out.println("Add delegate failed: " + addResult);
            System.exit(1);
        }
        var addTxHash = addResult.getValue();
        YaciHelper.waitForConfirmation(backend, addTxHash);
        System.out.println("Delegate added! Tx: " + addTxHash);

        // Step 3: Remove delegate
        System.out.println("Step 3: Removing delegate...");
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

        var removeResult = quickTx.compose(removeTx)
                .withSigner(SignerProviders.signerFrom(owner))
                .feePayer(owner.baseAddress())
                .collateralPayer(owner.baseAddress())
                .withRequiredSigners(ownerPkh)
                .complete();

        if (!removeResult.isSuccessful()) {
            System.out.println("Remove delegate failed: " + removeResult);
            System.exit(1);
        }
        YaciHelper.waitForConfirmation(backend, removeResult.getValue());
        System.out.println("Delegate removed! Tx: " + removeResult.getValue());
        System.out.println("Identity demo completed successfully!");
    }
}
