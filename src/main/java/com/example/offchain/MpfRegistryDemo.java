package com.example.offchain;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.*;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.bloxbean.cardano.vds.mpf.MpfTrie;
import com.bloxbean.cardano.vds.mpf.internal.TestNodeStore;
import com.bloxbean.cardano.vds.mpf.proof.ProofFormatter;
import com.example.validators.MpfRegistryValidator;

/**
 * End-to-end demo: MPF Registry with on-chain proof verification.
 * <p>
 * Demonstrates:
 * 1. Building an MPF trie off-chain with whitelisted entries
 * 2. Locking a UTXO with the trie root hash as datum
 * 3. Unlocking with an inclusion proof (VerifyHas)
 * <p>
 * Requires Yaci Devkit running locally.
 */
public class MpfRegistryDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("=== MPF Registry Validator E2E Demo ===\n");

        if (!YaciHelper.isYaciReachable()) {
            System.out.println("ERROR: Yaci Devkit not reachable at " + YaciHelper.YACI_BASE_URL);
            System.out.println("Start it with: yaci-cli devkit start");
            System.exit(1);
        }

        // 1. Build MPF trie with whitelisted entries
        System.out.println("--- Building MPF trie ---");
        var store = new TestNodeStore();
        var trie = new MpfTrie(store);

        byte[] address1 = "addr_test1_alice".getBytes();
        byte[] allocation1 = "1000000".getBytes();  // 1 ADA allocation
        byte[] address2 = "addr_test1_bob".getBytes();
        byte[] allocation2 = "2000000".getBytes();  // 2 ADA allocation

        trie.put(address1, allocation1);
        trie.put(address2, allocation2);
        byte[] trieRoot = trie.getRootHash();
        System.out.println("  Trie root: " + bytesToHex(trieRoot));
        System.out.println("  Entries: " + trie.getAllEntries().size());

        var s = ProofFormatter.toAiken(trie.getProofWire(address1).get());
        System.out.println(s);
        System.out.println("root:" + bytesToHex(trieRoot));
        System.out.println("key:" + bytesToHex(address1));
        System.out.println("value:" + bytesToHex(allocation1));

        // 2. Load the pre-compiled validator
        var script = JulcScriptLoader.load(MpfRegistryValidator.class);
        var scriptAddr = AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();
        System.out.println("  Script address: " + scriptAddr);

        // 3. Create test account and fund it
        var sender = new Account(Networks.testnet());
        byte[] senderPkh = sender.hdKeyPair().getPublicKey().getKeyHash();
        System.out.println("  Sender: " + sender.baseAddress().substring(0, 20) + "...");

        YaciHelper.topUp(sender.baseAddress(), 1000);
        var backend = YaciHelper.createBackendService();
        var quickTx = new QuickTxBuilder(backend);

        // 4. Lock UTXO with trie root as datum
        //    MpfDatum(trieRoot) = Constr(0, [BData(root)])
        System.out.println("\n--- Locking 10 ADA with trie root datum ---");
        var datum = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(new BytesPlutusData(trieRoot)))
                .build();

        var lockTx = new Tx()
                .payToContract(scriptAddr, Amount.ada(10), datum)
                .from(sender.baseAddress());

        var lockResult = quickTx.compose(lockTx)
                .withSigner(SignerProviders.signerFrom(sender))
                .complete();

        if (!lockResult.isSuccessful()) {
            System.out.println("FAILED to lock: " + lockResult);
            System.exit(1);
        }
        var lockTxHash = lockResult.getValue();
        System.out.println("  Lock tx: " + lockTxHash);
        YaciHelper.waitForConfirmation(backend, lockTxHash);

        // 5. Find the script UTXO
        var scriptUtxo = YaciHelper.findUtxo(backend, scriptAddr, lockTxHash);
        System.out.println("  Script UTXO: " + scriptUtxo.getTxHash() + "#" + scriptUtxo.getOutputIndex());

        // 6. Generate inclusion proof for alice
        System.out.println("\n--- Unlocking with inclusion proof for alice ---");
        ListPlutusData proof = trie.getProofPlutusData(address1).orElseThrow(
                () -> new RuntimeException("Failed to generate proof for alice"));
        System.out.println("  Proof steps: " + proof.getPlutusDataList().size());

        // VerifyHas(key, value, proof) = Constr(0, [BData(key), BData(value), ListData(proof)])
        var redeemer = ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(
                        new BytesPlutusData(address1),
                        new BytesPlutusData(allocation1),
                        proof))
                .build();

        var unlockTx = new ScriptTx()
                .collectFrom(scriptUtxo, redeemer)
                .payToAddress(sender.baseAddress(), Amount.ada(9))
                .attachSpendingValidator(script);

        var unlockResult = quickTx.compose(unlockTx)
                .withSigner(SignerProviders.signerFrom(sender))
                .withRequiredSigners(senderPkh)
                .feePayer(sender.baseAddress())
                .collateralPayer(sender.baseAddress())
                .withTxEvaluator(YaciHelper.julcEvaluator(backend))
                .complete();

        if (!unlockResult.isSuccessful()) {
            System.out.println("FAILED to unlock: " + unlockResult);
            System.exit(1);
        }
        System.out.println("  Unlock tx: " + unlockResult.getValue());
        YaciHelper.waitForConfirmation(backend, unlockResult.getValue());

        System.out.println("\n=== MPF Registry Demo PASSED ===");
    }

    private static String bytesToHex(byte[] bytes) {
        var sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
