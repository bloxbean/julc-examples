package com.example.linkedlist.offchain;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.ScriptTx;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.util.HexUtil;
import com.bloxbean.cardano.julc.clientlib.JulcScriptLoader;
import com.example.linkedlist.onchain.LinkedListValidator;
import com.example.offchain.YaciHelper;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * End-to-end demo of the on-chain linked list against Yaci DevKit.
 * <p>
 * Flow:
 * 1. Init  — create empty list (root→∅)
 * 2. Insert BB — root→BB
 * 3. Insert AA — root→AA→BB (ordered insert)
 * 4. Remove BB — root→AA
 * 5. Remove AA — root→∅
 * 6. Deinit — destroy empty list
 */
public class LinkedListDemo {

    static final byte[] ROOT_KEY = "ROOT".getBytes();
    static final byte[] PREFIX   = "NODE".getBytes();
    static final BigInteger PREFIX_LEN = BigInteger.valueOf(4);

    public static void main(String[] args) throws Exception {
        System.out.println("=== On-Chain Linked List Demo ===\n");

        if (!YaciHelper.isYaciReachable()) {
            System.out.println("ERROR: Yaci DevKit not reachable at " + YaciHelper.YACI_BASE_URL);
            System.out.println("Start it with: yaci-cli devkit start");
            System.exit(1);
        }

        // Reset devnet for a clean slate
        resetDevnet();

        // Setup
        var user = new Account(Networks.testnet());
        System.out.println("User: " + user.baseAddress().substring(0, 30) + "...");
        YaciHelper.topUp(user.baseAddress(), 1000);

        var backend = YaciHelper.createBackendService();
        var quickTx = new QuickTxBuilder(backend);

        // Load script with params
        var script = JulcScriptLoader.load(LinkedListValidator.class,
                new BytesPlutusData(ROOT_KEY),
                new BytesPlutusData(PREFIX),
                BigIntPlutusData.of(PREFIX_LEN));
        var scriptAddr = AddressProvider.getEntAddress(script, Networks.testnet()).toBech32();
        var policyHex = HexUtil.encodeHexString(script.getScriptHash());
        System.out.println("Policy ID: " + policyHex);
        System.out.println("Script addr: " + scriptAddr.substring(0, 30) + "...");

        // Pre-compute token name hex strings
        String rootTokenHex = HexUtil.encodeHexString(ROOT_KEY);
        byte[] keyBB = "BB".getBytes();
        byte[] keyAA = "AA".getBytes();
        byte[] nodeBB = concat(PREFIX, keyBB);
        byte[] nodeAA = concat(PREFIX, keyAA);
        String nodeBBHex = HexUtil.encodeHexString(nodeBB);
        String nodeAAHex = HexUtil.encodeHexString(nodeAA);

        // Spend redeemer — the spend validator doesn't check it, just verifies policy in mint
        var spendRedeemer = ConstrPlutusData.of(0);

        // =====================================================================
        // Step 1: Init — create empty list (root→∅)
        // =====================================================================
        System.out.println("\n--- Step 1: Init (root→∅) ---");

        var rootAsset = new Asset("0x" + rootTokenHex, BigInteger.ONE);
        var rootDatum = listElementDatum(new byte[0]);
        var initMintRedeemer = initRedeemer(0); // root output at index 0

        var initTx = new ScriptTx()
                .mintAsset(script, List.of(rootAsset), initMintRedeemer)
                .payToContract(scriptAddr,
                        List.of(Amount.ada(2), new Amount(policyHex + rootTokenHex, BigInteger.ONE)),
                        rootDatum);

        var initResult = quickTx.compose(initTx)
                .withSigner(SignerProviders.signerFrom(user))
                .feePayer(user.baseAddress())
                .collateralPayer(user.baseAddress())
                .ignoreScriptCostEvaluationError(true)
                .complete();

        checkResult(initResult, "Init");
        var initTxHash = initResult.getValue();
        YaciHelper.waitForConfirmation(backend, initTxHash);
        // rootUtxo at (initTxHash, 0)

        // =====================================================================
        // Step 2: Insert BB — root→BB→∅
        // =====================================================================
        System.out.println("\n--- Step 2: Insert BB (root→BB) ---");

        var rootUtxo = findUtxoAtIndex(backend, scriptAddr, initTxHash, 0);
        var walletUtxo = YaciHelper.findAnyUtxo(backend, user.baseAddress());
        int rootIdx = computeInputIndex(List.of(rootUtxo, walletUtxo), rootUtxo);

        var bbAsset = new Asset("0x" + nodeBBHex, BigInteger.ONE);
        var insertBBMintRedeemer = insertRedeemer(rootIdx, 0, 1);
        var contRootDatumBB = listElementDatum(keyBB);   // root→BB
        var newBBDatum = listElementDatum(new byte[0]);   // BB→∅

        var insertBBTx = new ScriptTx()
                .collectFrom(rootUtxo, spendRedeemer)
                .collectFrom(walletUtxo)
                .mintAsset(script, List.of(bbAsset), insertBBMintRedeemer)
                .payToContract(scriptAddr,
                        List.of(Amount.ada(2), new Amount(policyHex + rootTokenHex, BigInteger.ONE)),
                        contRootDatumBB)
                .payToContract(scriptAddr,
                        List.of(Amount.ada(2), new Amount(policyHex + nodeBBHex, BigInteger.ONE)),
                        newBBDatum)
                .attachSpendingValidator(script);

        var insertBBResult = quickTx.compose(insertBBTx)
                .withSigner(SignerProviders.signerFrom(user))
                .feePayer(user.baseAddress())
                .collateralPayer(user.baseAddress())
                .ignoreScriptCostEvaluationError(true)
                .complete();

        checkResult(insertBBResult, "Insert BB");
        var insertBBTxHash = insertBBResult.getValue();
        YaciHelper.waitForConfirmation(backend, insertBBTxHash);
        // contRoot at (insertBBTxHash, 0), newBB at (insertBBTxHash, 1)

        // =====================================================================
        // Step 3: Insert AA — root→AA→BB→∅
        // =====================================================================
        System.out.println("\n--- Step 3: Insert AA (root→AA→BB) ---");

        rootUtxo = findUtxoAtIndex(backend, scriptAddr, insertBBTxHash, 0);
        walletUtxo = YaciHelper.findAnyUtxo(backend, user.baseAddress());
        rootIdx = computeInputIndex(List.of(rootUtxo, walletUtxo), rootUtxo);

        var aaAsset = new Asset("0x" + nodeAAHex, BigInteger.ONE);
        var insertAAMintRedeemer = insertRedeemer(rootIdx, 0, 1);
        var contRootDatumAA = listElementDatum(keyAA);    // root→AA
        var newAADatum = listElementDatum(keyBB);          // AA→BB

        var insertAATx = new ScriptTx()
                .collectFrom(rootUtxo, spendRedeemer)
                .collectFrom(walletUtxo)
                .mintAsset(script, List.of(aaAsset), insertAAMintRedeemer)
                .payToContract(scriptAddr,
                        List.of(Amount.ada(2), new Amount(policyHex + rootTokenHex, BigInteger.ONE)),
                        contRootDatumAA)
                .payToContract(scriptAddr,
                        List.of(Amount.ada(2), new Amount(policyHex + nodeAAHex, BigInteger.ONE)),
                        newAADatum)
                .attachSpendingValidator(script);

        var insertAAResult = quickTx.compose(insertAATx)
                .withSigner(SignerProviders.signerFrom(user))
                .feePayer(user.baseAddress())
                .collateralPayer(user.baseAddress())
                .ignoreScriptCostEvaluationError(true)
                .complete();

        checkResult(insertAAResult, "Insert AA");
        var insertAATxHash = insertAAResult.getValue();
        YaciHelper.waitForConfirmation(backend, insertAATxHash);
        // contRoot at (insertAATxHash, 0), newAA at (insertAATxHash, 1)
        // BB still at (insertBBTxHash, 1)

        // =====================================================================
        // Step 4: Remove BB — root→AA→∅  (anchor=AA, removing=BB)
        // =====================================================================
        System.out.println("\n--- Step 4: Remove BB (root→AA) ---");

        var aaUtxo = findUtxoAtIndex(backend, scriptAddr, insertAATxHash, 1);
        var bbUtxo = findUtxoAtIndex(backend, scriptAddr, insertBBTxHash, 1);
        walletUtxo = YaciHelper.findAnyUtxo(backend, user.baseAddress());
        int aaIdx = computeInputIndex(List.of(aaUtxo, bbUtxo, walletUtxo), aaUtxo);
        int bbIdx = computeInputIndex(List.of(aaUtxo, bbUtxo, walletUtxo), bbUtxo);

        var burnBBAsset = new Asset("0x" + nodeBBHex, BigInteger.ONE.negate());
        var removeBBMintRedeemer = removeRedeemer(aaIdx, bbIdx, 0);
        var contAADatum = listElementDatum(new byte[0]); // AA→∅ (BB's old nextKey was ∅)

        var removeBBTx = new ScriptTx()
                .collectFrom(aaUtxo, spendRedeemer)
                .collectFrom(bbUtxo, spendRedeemer)
                .collectFrom(walletUtxo)
                .mintAsset(script, List.of(burnBBAsset), removeBBMintRedeemer)
                .payToContract(scriptAddr,
                        List.of(Amount.ada(2), new Amount(policyHex + nodeAAHex, BigInteger.ONE)),
                        contAADatum)
                .attachSpendingValidator(script);

        var removeBBResult = quickTx.compose(removeBBTx)
                .withSigner(SignerProviders.signerFrom(user))
                .feePayer(user.baseAddress())
                .collateralPayer(user.baseAddress())
                .ignoreScriptCostEvaluationError(true)
                .complete();

        checkResult(removeBBResult, "Remove BB");
        var removeBBTxHash = removeBBResult.getValue();
        YaciHelper.waitForConfirmation(backend, removeBBTxHash);
        // contAA at (removeBBTxHash, 0)
        // root still at (insertAATxHash, 0)

        // =====================================================================
        // Step 5: Remove AA — root→∅  (anchor=root, removing=AA)
        // =====================================================================
        System.out.println("\n--- Step 5: Remove AA (root→∅) ---");

        rootUtxo = findUtxoAtIndex(backend, scriptAddr, insertAATxHash, 0);
        aaUtxo = findUtxoAtIndex(backend, scriptAddr, removeBBTxHash, 0);
        walletUtxo = YaciHelper.findAnyUtxo(backend, user.baseAddress());
        rootIdx = computeInputIndex(List.of(rootUtxo, aaUtxo, walletUtxo), rootUtxo);
        aaIdx = computeInputIndex(List.of(rootUtxo, aaUtxo, walletUtxo), aaUtxo);

        var burnAAAsset = new Asset("0x" + nodeAAHex, BigInteger.ONE.negate());
        var removeAAMintRedeemer = removeRedeemer(rootIdx, aaIdx, 0);
        var contRootEmpty = listElementDatum(new byte[0]); // root→∅

        var removeAATx = new ScriptTx()
                .collectFrom(rootUtxo, spendRedeemer)
                .collectFrom(aaUtxo, spendRedeemer)
                .collectFrom(walletUtxo)
                .mintAsset(script, List.of(burnAAAsset), removeAAMintRedeemer)
                .payToContract(scriptAddr,
                        List.of(Amount.ada(2), new Amount(policyHex + rootTokenHex, BigInteger.ONE)),
                        contRootEmpty)
                .attachSpendingValidator(script);

        var removeAAResult = quickTx.compose(removeAATx)
                .withSigner(SignerProviders.signerFrom(user))
                .feePayer(user.baseAddress())
                .collateralPayer(user.baseAddress())
                .ignoreScriptCostEvaluationError(true)
                .complete();

        checkResult(removeAAResult, "Remove AA");
        var removeAATxHash = removeAAResult.getValue();
        YaciHelper.waitForConfirmation(backend, removeAATxHash);
        // contRoot at (removeAATxHash, 0) with empty nextKey

        // =====================================================================
        // Step 6: Deinit — destroy empty list
        // =====================================================================
        System.out.println("\n--- Step 6: Deinit (destroy list) ---");

        rootUtxo = findUtxoAtIndex(backend, scriptAddr, removeAATxHash, 0);
        walletUtxo = YaciHelper.findAnyUtxo(backend, user.baseAddress());
        rootIdx = computeInputIndex(List.of(rootUtxo, walletUtxo), rootUtxo);

        var burnRootAsset = new Asset("0x" + rootTokenHex, BigInteger.ONE.negate());
        var deinitMintRedeemer = deinitRedeemer(rootIdx);

        var deinitTx = new ScriptTx()
                .collectFrom(rootUtxo, spendRedeemer)
                .collectFrom(walletUtxo)
                .mintAsset(script, List.of(burnRootAsset), deinitMintRedeemer)
                .payToAddress(user.baseAddress(), Amount.ada(1))
                .attachSpendingValidator(script);

        var deinitResult = quickTx.compose(deinitTx)
                .withSigner(SignerProviders.signerFrom(user))
                .feePayer(user.baseAddress())
                .collateralPayer(user.baseAddress())
                .ignoreScriptCostEvaluationError(true)
                .complete();

        checkResult(deinitResult, "Deinit");
        YaciHelper.waitForConfirmation(backend, deinitResult.getValue());

        System.out.println("\n=== On-Chain Linked List Demo PASSED ===");
    }

    // =========================================================================
    // Datum & Redeemer construction
    // =========================================================================

    // ListElement = Constr(0, [userData, BData(nextKey)])
    static ConstrPlutusData listElementDatum(byte[] nextKey) {
        return ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(
                        ConstrPlutusData.of(0),         // unit userData
                        new BytesPlutusData(nextKey)))
                .build();
    }

    // InitList(rootOutputIndex) = Constr(0, [IData])
    static ConstrPlutusData initRedeemer(int rootOutputIndex) {
        return ConstrPlutusData.builder()
                .alternative(0)
                .data(ListPlutusData.of(BigIntPlutusData.of(rootOutputIndex)))
                .build();
    }

    // DeinitList(rootInputIndex) = Constr(1, [IData])
    static ConstrPlutusData deinitRedeemer(int rootInputIndex) {
        return ConstrPlutusData.builder()
                .alternative(1)
                .data(ListPlutusData.of(BigIntPlutusData.of(rootInputIndex)))
                .build();
    }

    // InsertNode(anchorIdx, contAnchorIdx, newElemIdx) = Constr(2, [IData, IData, IData])
    static ConstrPlutusData insertRedeemer(int anchorIdx, int contAnchorIdx, int newElemIdx) {
        return ConstrPlutusData.builder()
                .alternative(2)
                .data(ListPlutusData.of(
                        BigIntPlutusData.of(anchorIdx),
                        BigIntPlutusData.of(contAnchorIdx),
                        BigIntPlutusData.of(newElemIdx)))
                .build();
    }

    // RemoveNode(anchorIdx, removingIdx, contAnchorIdx) = Constr(3, [IData, IData, IData])
    static ConstrPlutusData removeRedeemer(int anchorIdx, int removingIdx, int contAnchorIdx) {
        return ConstrPlutusData.builder()
                .alternative(3)
                .data(ListPlutusData.of(
                        BigIntPlutusData.of(anchorIdx),
                        BigIntPlutusData.of(removingIdx),
                        BigIntPlutusData.of(contAnchorIdx)))
                .build();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Compute the index of a target UTXO in the sorted input list.
     * Inputs in txInfo are sorted by TxOutRef (txHash lexicographic, then outputIndex).
     */
    static int computeInputIndex(List<Utxo> allInputs, Utxo target) {
        var sorted = new ArrayList<>(allInputs);
        sorted.sort(Comparator.comparing(Utxo::getTxHash)
                .thenComparingInt(Utxo::getOutputIndex));
        for (int i = 0; i < sorted.size(); i++) {
            var u = sorted.get(i);
            if (u.getTxHash().equals(target.getTxHash())
                    && u.getOutputIndex() == target.getOutputIndex()) {
                return i;
            }
        }
        throw new RuntimeException("UTXO not found in input list: "
                + target.getTxHash() + "#" + target.getOutputIndex());
    }

    /**
     * Find a UTXO at a specific address, txHash, and output index.
     */
    static Utxo findUtxoAtIndex(BackendService backend, String address,
                                String txHash, int outputIndex) throws Exception {
        for (int attempt = 0; attempt < 15; attempt++) {
            var utxoResult = backend.getUtxoService().getUtxos(address, 100, 1);
            if (utxoResult.isSuccessful() && utxoResult.getValue() != null) {
                var match = utxoResult.getValue().stream()
                        .filter(u -> u.getTxHash().equals(txHash)
                                && u.getOutputIndex() == outputIndex)
                        .findFirst();
                if (match.isPresent()) return match.get();
            }
            Thread.sleep(2000);
        }
        throw new RuntimeException("UTXO not found: " + txHash + "#" + outputIndex
                + " at " + address.substring(0, 20) + "...");
    }

    static void resetDevnet() {
        try {
            System.out.println("Resetting devnet...");
            var client = HttpClient.newHttpClient();
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(YaciHelper.YACI_ADMIN_URL + "/local-cluster/api/admin/devnet/reset"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                System.out.println("WARNING: Reset returned " + response.statusCode());
            }
            Thread.sleep(5000); // Wait for reset to stabilize
            System.out.println("Devnet reset complete.");
        } catch (Exception e) {
            System.out.println("WARNING: Could not reset devnet: " + e.getMessage());
        }
    }

    static void checkResult(Result<String> result, String step) {
        if (!result.isSuccessful()) {
            System.out.println("FAILED at " + step + ": " + result.getResponse());
            System.exit(1);
        }
        System.out.println(step + " tx: " + result.getValue());
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }
}
