package com.example.bls.offchain;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusV3Script;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.util.HexUtil;
import com.example.bls.onchain.BlsAggregateVaultValidator;
import com.example.bls.onchain.BlsVaultValidator;
import com.example.offchain.YaciHelper;
import org.julclang.bls.BlsOperations;
import org.julclang.clientlib.JulcScriptLoader;
import org.julclang.clientlib.PlutusDataAdapter;

import java.math.BigInteger;

/**
 * Off-chain demo for the BLS12-381 vaults.
 * <p>
 * Step 1: lock ADA at {@link BlsVaultValidator} and unlock it with a single BLS signature.
 * Step 2: lock ADA at {@link BlsAggregateVaultValidator} and unlock it with a 2-of-2
 *         aggregate signature (exercises the PV11 multi-scalar multiplication builtin).
 * <p>
 * Signatures are produced off-chain with {@link BlsOperations} (blst), and verified on-chain
 * by the BLS12-381 builtins. Because the whole point is to exercise those builtins in the
 * evaluator, run it both ways:
 * <pre>
 *   ./gradlew run -PmainClass=com.example.bls.offchain.BlsDemo                        # local JuLC VM
 *   ./gradlew run -PmainClass=com.example.bls.offchain.BlsDemo -Pevaluator=backend    # Yaci Store /utils/txs/evaluate
 * </pre>
 */
public class BlsDemo {

    /** The compressed BLS12-381 G2 generator (96 bytes). */
    private static final byte[] G2_GENERATOR = HexUtil.decodeHexString(
            "93e02b6052719f607dacd3a088274f65596bd0d09920b61ab5da61bbdc7f5049"
            + "334cf11213945d57e5ac7d055d042b7e024aa2b2f08f0a91260805272dc51051"
            + "c6e47ad4fa403b02b4510b647ae3d1770bac0326a805bbefd48056c8c121bdb8");

    /** Hash-to-curve domain separation tag shared by the validators and this demo. */
    private static final byte[] DST = "JULC-BLS-DEMO-V1".getBytes();

    public static void main(String[] args) throws Exception {
        if (!YaciHelper.isYaciReachable()) {
            System.out.println("Yaci DevKit not reachable. Skipping demo.");
            System.exit(1);
        }

        var owner = new Account(Networks.testnet());
        byte[] ownerPkh = owner.hdKeyPair().getPublicKey().getKeyHash();
        YaciHelper.topUp(owner.baseAddress(), 1000);

        var backend = YaciHelper.createBackendService();
        var quickTx = new QuickTxBuilder(backend);

        // --- off-chain BLS key material -------------------------------------------------
        byte[] g2 = BlsOperations.g2Uncompress(G2_GENERATOR);
        byte[] message = "release the funds".getBytes();
        byte[] messagePoint = BlsOperations.g1HashToGroup(message, DST);

        BigInteger sk1 = new BigInteger("42424242424242424242424242424242424242424242");
        BigInteger sk2 = new BigInteger("77777777777777777777777777777777777777777");

        byte[] pk1 = BlsOperations.g2Compress(BlsOperations.g2ScalarMul(sk1, g2));
        byte[] pk2 = BlsOperations.g2Compress(BlsOperations.g2ScalarMul(sk2, g2));
        byte[] sig1 = BlsOperations.g1ScalarMul(sk1, messagePoint);
        byte[] sig2 = BlsOperations.g1ScalarMul(sk2, messagePoint);
        byte[] singleSig = BlsOperations.g1Compress(sig1);
        byte[] aggregateSig = BlsOperations.g1Compress(BlsOperations.g1Add(sig1, sig2));

        System.out.println("Script evaluation mode: " + YaciHelper.evalMode());
        System.out.println("BLS message: " + new String(message));

        // --- Step 1: single-signature vault ---------------------------------------------
        System.out.println("Step 1: single-signature BLS vault");
        var singleScript = JulcScriptLoader.load(BlsVaultValidator.class,
                new BytesPlutusData(G2_GENERATOR), new BytesPlutusData(DST));
        var singleDatum = PlutusDataAdapter.convert(
                new BlsVaultValidator.VaultDatum(pk1, message));
        var singleRedeemer = PlutusDataAdapter.convert(
                new BlsVaultValidator.Signature(singleSig));

        runVault(quickTx, backend, owner, ownerPkh, singleScript, singleDatum, singleRedeemer,
                "single-signature");

        // --- Step 2: 2-of-2 aggregate vault --------------------------------------------
        System.out.println("Step 2: 2-of-2 aggregate BLS vault (g1Add/g2Add + pairing)");
        var aggScript = JulcScriptLoader.load(BlsAggregateVaultValidator.class,
                new BytesPlutusData(G2_GENERATOR), new BytesPlutusData(DST));
        var aggDatum = PlutusDataAdapter.convert(
                new BlsAggregateVaultValidator.AggVaultDatum(pk1, pk2, message));
        var aggRedeemer = PlutusDataAdapter.convert(
                new BlsAggregateVaultValidator.AggSignature(aggregateSig));

        runVault(quickTx, backend, owner, ownerPkh, aggScript, aggDatum, aggRedeemer,
                "2-of-2 aggregate");

        System.out.println("BLS demo completed successfully!");
    }

    /** Lock 10 ADA at the script, then spend it with the given redeemer. */
    private static void runVault(QuickTxBuilder quickTx,
                                 BackendService backend,
                                 Account owner,
                                 byte[] ownerPkh,
                                 PlutusV3Script script,
                                 PlutusData datum,
                                 PlutusData redeemer,
                                 String label) throws Exception {
        var scriptAddr = AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();
        System.out.println("  script address: " + scriptAddr);

        var lockTx = new Tx()
                .payToContract(scriptAddr, Amount.ada(10), datum)
                .from(owner.baseAddress());

        var lockResult = quickTx.compose(lockTx)
                .withSigner(SignerProviders.signerFrom(owner))
                .complete();

        if (!lockResult.isSuccessful()) {
            System.out.println("  Lock failed (" + label + "): " + lockResult);
            System.exit(1);
        }
        var lockTxHash = lockResult.getValue();
        YaciHelper.waitForConfirmation(backend, lockTxHash);
        System.out.println("  Locked 10 ADA! Tx: " + lockTxHash);

        var scriptUtxo = YaciHelper.findUtxo(backend, scriptAddr, lockTxHash);

        var unlockTx = new ScriptTx()
                .collectFrom(scriptUtxo, redeemer)
                .payToAddress(owner.baseAddress(), Amount.ada(5))
                .attachSpendingValidator(script);

        var unlockResult = quickTx.compose(unlockTx)
                .withSigner(SignerProviders.signerFrom(owner))
                .feePayer(owner.baseAddress())
                .collateralPayer(owner.baseAddress())
                .withRequiredSigners(ownerPkh)
                .withTxEvaluator(YaciHelper.julcEvaluator(backend))
                .complete();

        if (!unlockResult.isSuccessful()) {
            System.out.println("  Unlock failed (" + label + "): " + unlockResult);
            System.exit(1);
        }
        YaciHelper.waitForConfirmation(backend, unlockResult.getValue());
        System.out.println("  BLS signature verified on-chain (" + label + ")! Tx: "
                + unlockResult.getValue());
    }
}
