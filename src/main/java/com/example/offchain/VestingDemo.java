package com.example.offchain;

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
import com.example.validators.VestingValidator;

/**
 * End-to-end demo: lock ADA in a vesting contract, then unlock as the beneficiary.
 * <p>
 * This demonstrates the full developer workflow:
 * 1. Load a pre-compiled validator via JulcScriptLoader
 * 2. Lock ADA with an inline datum (beneficiary PubKeyHash)
 * 3. Unlock by proving the beneficiary's signature is present
 * <p>
 * Requires Yaci Devkit running locally.
 */
public class VestingDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("=== Vesting Validator E2E Demo ===\n");

        if (!YaciHelper.isYaciReachable()) {
            System.out.println("ERROR: Yaci Devkit not reachable at " + YaciHelper.YACI_BASE_URL);
            System.out.println("Start it with: yaci-cli devkit start");
            System.exit(1);
        }

        var param1 = BigIntPlutusData.of(600);
        var param2 = BytesPlutusData.of("Vesting Demo");
        // 1. Load the pre-compiled validator
        var script = JulcScriptLoader.load(VestingValidator.class, param1, param2);
        var scriptHash = script.getScriptHash();
        var scriptAddr = AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();
        System.out.println("Script hash: " + scriptHash);
        System.out.println("Script address: " + scriptAddr);

        // 2. Create and fund beneficiary account
        var beneficiary = new Account(Networks.testnet());
        var beneficiaryAddr = beneficiary.baseAddress();
        byte[] beneficiaryPkh = beneficiary.hdKeyPair().getPublicKey().getKeyHash();
        System.out.println("Beneficiary: " + beneficiaryAddr);
        YaciHelper.topUp(beneficiaryAddr, 1000);

        var backend = YaciHelper.createBackendService();
        var quickTx = new QuickTxBuilder(backend);

        // 3. Create datum: VestingDatum(beneficiaryPkh)
        //    Datum = Constr(0, [BData(pkh)])
        var datum = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(
                        new BytesPlutusData(beneficiaryPkh)))
                .build();

        // 4. Lock 10 ADA to the script address
        System.out.println("\n--- Locking 10 ADA ---");
        var lockTx = new Tx()
                .payToContract(scriptAddr, Amount.ada(10), datum)
                .from(beneficiaryAddr);

        var lockResult = quickTx.compose(lockTx)
                .withSigner(SignerProviders.signerFrom(beneficiary))
                .complete();

        if (!lockResult.isSuccessful()) {
            System.out.println("FAILED to lock: " + lockResult);
            System.exit(1);
        }
        var lockTxHash = lockResult.getValue();
        System.out.println("Lock tx: " + lockTxHash);
        YaciHelper.waitForConfirmation(backend, lockTxHash);

        // 5. Find the script UTXO
        var scriptUtxo = YaciHelper.findUtxo(backend, scriptAddr, lockTxHash);
        System.out.println("Script UTXO: " + scriptUtxo.getTxHash() + "#" + scriptUtxo.getOutputIndex());

        // 6. Unlock: beneficiary claims (redeemer unused, just need signature)
        System.out.println("\n--- Unlocking (beneficiary claims) ---");
        var nestdRedeemer = ConstrPlutusData.of(0, BigIntPlutusData.of(600), BytesPlutusData.of("Vesting Demo"));
        var redeemer = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(nestdRedeemer))
                .build();

        // Validator requires exactly 2 outputs, one with 5 ADA
        // Change output to feePayer (beneficiary) is the 2nd output
        var unlockTx = new ScriptTx()
                .collectFrom(scriptUtxo, redeemer)
                .payToAddress(new Account(Networks.testnet()).baseAddress(), Amount.ada(5))
                .attachSpendingValidator(script);

        var unlockResult = quickTx.compose(unlockTx)
                .withSigner(SignerProviders.signerFrom(beneficiary))
                .withRequiredSigners(beneficiaryPkh)
                .feePayer(beneficiaryAddr)
                .collateralPayer(beneficiaryAddr)
                .withTxEvaluator(YaciHelper.julcEvaluator(backend))
                .complete();

        if (!unlockResult.isSuccessful()) {
            System.out.println("FAILED to unlock: " + unlockResult);
            System.exit(1);
        }
        System.out.println("Unlock tx: " + unlockResult.getValue());
        YaciHelper.waitForConfirmation(backend, unlockResult.getValue());

        System.out.println("\n=== Vesting Demo PASSED ===");
    }
}
