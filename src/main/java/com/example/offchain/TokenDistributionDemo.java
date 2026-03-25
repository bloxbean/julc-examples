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
import com.example.validators.TokenDistributionValidator;

/**
 * End-to-end demo: Token Distribution Validator with Yaci Devkit.
 * <p>
 * Workflow:
 * 1. Load pre-compiled TokenDistributionValidator with admin @Param
 * 2. Lock treasury funds with a distribution datum (list of beneficiaries + amounts)
 * 3. Admin signs to distribute — validator checks all beneficiaries are paid
 * 4. Verify success on devnet
 * <p>
 * Demonstrates: HOF lambdas (list.all, list.any, list.filter) with untyped
 * ByteStringType params, variable capture, block-body lambdas, switch in lambda.
 * <p>
 * Requires Yaci Devkit running locally.
 */
public class TokenDistributionDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("=== Token Distribution Validator E2E Demo ===\n");

        if (!YaciHelper.isYaciReachable()) {
            System.out.println("ERROR: Yaci Devkit not reachable at " + YaciHelper.YACI_BASE_URL);
            System.out.println("Start it with: yaci-cli devkit start");
            System.exit(1);
        }

        // 1. Create accounts
        var admin = new Account(Networks.testnet());
        var beneficiaryA = new Account(Networks.testnet());
        var beneficiaryB = new Account(Networks.testnet());
        byte[] adminPkh = admin.hdKeyPair().getPublicKey().getKeyHash();
        byte[] pkhA = beneficiaryA.hdKeyPair().getPublicKey().getKeyHash();
        byte[] pkhB = beneficiaryB.hdKeyPair().getPublicKey().getKeyHash();

        System.out.println("Admin:         " + admin.baseAddress().substring(0, 30) + "...");
        System.out.println("Beneficiary A: " + beneficiaryA.baseAddress().substring(0, 30) + "...");
        System.out.println("Beneficiary B: " + beneficiaryB.baseAddress().substring(0, 30) + "...");

        // 2. Load the pre-compiled validator with admin @Param
        var script = JulcScriptLoader.load(TokenDistributionValidator.class,
                new BytesPlutusData(adminPkh));
        var scriptAddr = AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();
        System.out.println("Script address: " + scriptAddr);

        // 3. Fund admin account
        YaciHelper.topUp(admin.baseAddress(), 1000);

        var backend = YaciHelper.createBackendService();
        var quickTx = new QuickTxBuilder(backend);

        // 4. Build datum: DistributionDatum(beneficiaries)
        //    BeneficiaryEntry = Constr(0, [BData(pkh), IData(amount)])
        //    DistributionDatum = Constr(0, [ListData([entry1, entry2])])
        var entryA = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(
                        new BytesPlutusData(pkhA),
                        BigIntPlutusData.of(10_000_000))) // 10 ADA
                .build();
        var entryB = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(
                        new BytesPlutusData(pkhB),
                        BigIntPlutusData.of(5_000_000)))  // 5 ADA
                .build();
        var datum = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(
                        ListPlutusData.of(entryA, entryB)))
                .build();

        // 5. Lock 20 ADA to the script address (enough for both beneficiaries + fees)
        System.out.println("\n--- Locking 20 ADA to treasury ---");
        var lockTx = new Tx()
                .payToContract(scriptAddr, Amount.ada(20), datum)
                .from(admin.baseAddress());

        var lockResult = quickTx.compose(lockTx)
                .withSigner(SignerProviders.signerFrom(admin))
                .complete();

        if (!lockResult.isSuccessful()) {
            System.out.println("FAILED to lock: " + lockResult);
            System.exit(1);
        }
        var lockTxHash = lockResult.getValue();
        System.out.println("Lock tx: " + lockTxHash);
        YaciHelper.waitForConfirmation(backend, lockTxHash);

        // 6. Find the script UTXO
        var scriptUtxo = YaciHelper.findUtxo(backend, scriptAddr, lockTxHash);
        System.out.println("Script UTXO: " + scriptUtxo.getTxHash() + "#" + scriptUtxo.getOutputIndex());

        // 7. Distribute: admin signs, validator checks all beneficiaries are paid
        //    Redeemer: Distribute = Constr(0, [])
        System.out.println("\n--- Distributing (admin signs, beneficiaries paid) ---");
        var redeemer = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of())
                .build();

        var distributeTx = new ScriptTx()
                .collectFrom(scriptUtxo, redeemer)
                .payToAddress(beneficiaryA.baseAddress(), Amount.ada(10))
                .payToAddress(beneficiaryB.baseAddress(), Amount.ada(5))
                .attachSpendingValidator(script);

        var distributeResult = quickTx.compose(distributeTx)
                .withSigner(SignerProviders.signerFrom(admin))
                .withRequiredSigners(adminPkh)
                .feePayer(admin.baseAddress())
                .collateralPayer(admin.baseAddress())
                .withTxEvaluator(YaciHelper.julcEvaluator(backend))
                .complete();

        if (!distributeResult.isSuccessful()) {
            System.out.println("FAILED to distribute: " + distributeResult);
            System.exit(1);
        }
        System.out.println("Distribute tx: " + distributeResult.getValue());
        YaciHelper.waitForConfirmation(backend, distributeResult.getValue());

        System.out.println("\n=== Token Distribution Demo PASSED ===");
    }
}
