package com.example.cftemplates.vault.offchain;

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
import com.bloxbean.cardano.julc.clientlib.PlutusDataAdapter;
import com.example.cftemplates.vault.onchain.CfVaultValidator;
import com.example.offchain.YaciHelper;

import java.math.BigInteger;

/**
 * Off-chain demo for CfVaultValidator.
 * Locks ADA, initiates withdrawal with lockTime, then finalizes after wait.
 */
public class VaultDemo {

    public static void main(String[] args) throws Exception {
        if (!YaciHelper.isYaciReachable()) {
            System.out.println("Yaci DevKit not reachable. Skipping demo.");
            System.exit(1);
        }

        var owner = new Account(Networks.testnet());
        byte[] ownerPkh = owner.hdKeyPair().getPublicKey().getKeyHash();

        // WaitTime = 0 for demo (instant finalization)
        BigInteger waitTime = BigInteger.ZERO;

        YaciHelper.topUp(owner.baseAddress(), 1000);

        var script = JulcScriptLoader.load(CfVaultValidator.class,
                new BytesPlutusData(ownerPkh),
                BigIntPlutusData.of(waitTime));
        var scriptAddr = AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();

        var backend = YaciHelper.createBackendService();
        var quickTx = new QuickTxBuilder(backend);

        // Step 1: Lock 10 ADA with WithdrawDatum(lockTime=0)
        // Single-field record is just the value at UPLC level.
        // Some(lockTime=0) = Constr(0, [IData(0)])
        System.out.println("Step 1: Locking 10 ADA with lock datum...");
        var datum = ConstrPlutusData.builder()
                .alternative(0) // Some
                .data(ListPlutusData.of(BigIntPlutusData.of(0)))
                .build();

        var lockTx = new Tx()
                .payToContract(scriptAddr, Amount.ada(10), datum)
                .from(owner.baseAddress());

        var lockResult = quickTx.compose(lockTx)
                .withSigner(SignerProviders.signerFrom(owner))
                .complete();

        if (!lockResult.isSuccessful()) {
            System.out.println("Lock failed: " + lockResult);
            System.exit(1);
        }
        var lockTxHash = lockResult.getValue();
        YaciHelper.waitForConfirmation(backend, lockTxHash);
        System.out.println("Locked! Tx: " + lockTxHash);

        // Step 2: Finalize (with waitTime=0, owner can finalize immediately)
        System.out.println("Step 2: Finalizing withdrawal...");
        var scriptUtxo = YaciHelper.findUtxo(backend, scriptAddr, lockTxHash);

        // Finalize = tag 1
        var redeemer = PlutusDataAdapter.convert(new CfVaultValidator.Finalize());

        var latestBlock = backend.getBlockService().getLatestBlock();
        long currentSlot = latestBlock.getValue().getSlot();

        var finalizeTx = new ScriptTx()
                .collectFrom(scriptUtxo, redeemer)
                .payToAddress(owner.baseAddress(), Amount.ada(5))
                .attachSpendingValidator(script);

        var finalizeResult = quickTx.compose(finalizeTx)
                .withSigner(SignerProviders.signerFrom(owner))
                .feePayer(owner.baseAddress())
                .collateralPayer(owner.baseAddress())
                .withRequiredSigners(ownerPkh)
                .validFrom(currentSlot)
                .complete();

        if (!finalizeResult.isSuccessful()) {
            System.out.println("Finalize failed: " + finalizeResult);
            System.exit(1);
        }
        YaciHelper.waitForConfirmation(backend, finalizeResult.getValue());
        System.out.println("Finalized! Tx: " + finalizeResult.getValue());
        System.out.println("Vault demo completed successfully!");
    }
}
