package com.example.uverify.onchain;

import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.lib.ByteStringLib;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.MultiValidator;
import com.bloxbean.cardano.julc.stdlib.annotation.Param;
import com.bloxbean.cardano.julc.stdlib.annotation.Purpose;
import com.bloxbean.cardano.julc.stdlib.lib.AddressLib;
import com.bloxbean.cardano.julc.stdlib.lib.CryptoLib;
import com.bloxbean.cardano.julc.stdlib.lib.OutputLib;
import com.bloxbean.cardano.julc.stdlib.lib.ValuesLib;

import java.math.BigInteger;
import java.util.Optional;

/**
 * UVerify V1 Validator — @MultiValidator with WITHDRAW + SPEND + CERTIFY.
 *
 * NOTE: Methods must be defined BEFORE their callers (JuLC single-pass compiler).
 * Order: data types → leaf utilities → shared validators → handlers → dispatch → entrypoints
 *
 * Idiomatic JuLC: uses typed record casts, AddressLib, ContextsLib, OutputLib, ValuesLib.
 */
@MultiValidator
public class UVerifyV1 {

    @Param static byte[] proxyPolicyId;
    @Param static byte[] stateTokenName;
    @Param static byte[] adminKey1;
    @Param static byte[] adminKey2;

    // --- Data Types ---

    record UVerifyCertificate(byte[] hash, byte[] algorithm, byte[] issuer, JulcList<byte[]> extra) {}

    sealed interface StatePurpose permits BurnBootstrap, BurnState, UpdateState, MintState, MintBootstrap {}
    record BurnBootstrap() implements StatePurpose {}
    record BurnState() implements StatePurpose {}
    record UpdateState() implements StatePurpose {}
    record MintState() implements StatePurpose {}
    record MintBootstrap() implements StatePurpose {}

    record UVerifyStateRedeemer(StatePurpose purpose, JulcList<UVerifyCertificate> certificates) {}

    // Typed datum records — eliminates 22 manual accessor methods
    record StateDatum(byte[] id, byte[] owner, BigInteger fee, BigInteger feeInterval,
                      JulcList<byte[]> feeReceivers, BigInteger ttl, BigInteger countdown,
                      byte[] certHash, BigInteger batchSize, byte[] bootstrapName,
                      boolean keepAsOracle) {}

    record BootstrapDatum(JulcList<byte[]> allowedCreds, byte[] tokenName, BigInteger fee,
                          BigInteger feeInterval, JulcList<byte[]> feeReceivers,
                          BigInteger ttl, BigInteger transactionLimit, BigInteger batchSize) {}

    // =====================================================================
    // Level 0: Leaf utilities (no internal method dependencies)
    // =====================================================================

    static boolean oneOfAdminKeysSigned(JulcList<PubKeyHash> signatories) {
        return signatories.any(sig ->
                sig.hash().equals(adminKey1) || sig.hash().equals(adminKey2));
    }

    // =====================================================================
    // Level 1: Certificate & state validators
    // =====================================================================

    static byte[] certificateToByteArray(UVerifyCertificate cert) {
        byte[] base = ByteStringLib.append(
                ByteStringLib.append(cert.hash(), cert.algorithm()), cert.issuer());
        byte[] result = base;
        for (var extra : cert.extra()) {
            result = ByteStringLib.append(result, extra);
        }
        return result;
    }

    static boolean certificateIsValid(UVerifyCertificate cert, JulcList<PubKeyHash> signatories) {
        if (cert.hash().length == 0) return false;
        if (cert.issuer().length == 0) return false;
        return signatories.any(sig -> sig.hash().equals(cert.issuer()));
    }

    static boolean certificatesAreValid(byte[] expectedHash, JulcList<UVerifyCertificate> certs,
                                        JulcList<PubKeyHash> signatories) {
        if (certs.isEmpty()) {
            return expectedHash.length == 0;
        }

        byte[] aggregate = ByteStringLib.empty();
        boolean allValid = true;
        for (var cert : certs) {
            if (!certificateIsValid(cert, signatories)) {
                allValid = false;
                break;
            }
            aggregate = ByteStringLib.append(aggregate, certificateToByteArray(cert));
        }
        if (!allValid) return false;

        byte[] computedHash = CryptoLib.sha2_256(aggregate);
        return computedHash.equals(expectedHash);
    }

    static boolean stateDatumIsValid(StateDatum sd, byte[] owner, BootstrapDatum bd) {
        if (!sd.owner().equals(owner)) return false;
        if (!sd.fee().equals(bd.fee())) return false;
        if (!sd.feeInterval().equals(bd.feeInterval())) return false;
        if (!Builtins.equalsData(sd.feeReceivers(), bd.feeReceivers())) return false;
        if (!sd.ttl().equals(bd.ttl())) return false;
        if (!sd.countdown().equals(bd.transactionLimit().subtract(BigInteger.ONE))) return false;
        if (!sd.batchSize().equals(bd.batchSize())) return false;
        if (!sd.bootstrapName().equals(bd.tokenName())) return false;
        return true;
    }

    static boolean verifyStateMutation(StateDatum next, StateDatum cur) {
        if (cur.keepAsOracle()) return false;
        if (!next.id().equals(cur.id())) return false;
        if (!next.owner().equals(cur.owner())) return false;
        if (!next.fee().equals(cur.fee())) return false;
        if (!next.feeInterval().equals(cur.feeInterval())) return false;
        if (!Builtins.equalsData(next.feeReceivers(), cur.feeReceivers())) return false;
        if (!next.ttl().equals(cur.ttl())) return false;
        if (!next.batchSize().equals(cur.batchSize())) return false;
        if (!next.bootstrapName().equals(cur.bootstrapName())) return false;
        if (next.keepAsOracle() != cur.keepAsOracle()) return false;
        return next.countdown().equals(cur.countdown().subtract(BigInteger.ONE));
    }

    static boolean isStateAlive(StateDatum sd, Interval validRange) {
        return sd.countdown().compareTo(BigInteger.ZERO) > 0
                && UVerifyTxLib.tokenNotExpired(validRange, sd.ttl());
    }

    static boolean containsServiceFee(JulcList<TxOut> outputs, StateDatum sd) {
        BigInteger fee = sd.fee();
        BigInteger feeInterval = sd.feeInterval();
        if (fee.equals(BigInteger.ZERO)) return true;
        if (feeInterval.equals(BigInteger.ZERO)) return true;

        BigInteger nextCountdown = sd.countdown().add(BigInteger.ONE);
        if (!nextCountdown.remainder(feeInterval).equals(BigInteger.ZERO)) return true;

        JulcList<byte[]> receivers = sd.feeReceivers();
        if (receivers.isEmpty()) return true;

        long numReceivers = receivers.size();
        BigInteger feePerRecv = fee.divide(BigInteger.valueOf(numReceivers));

        boolean allPaid = true;
        for (var recv : receivers) {
            if (!UVerifyTxLib.receiverPaid(recv, feePerRecv, outputs)) {
                allPaid = false;
            }
        }
        return allPaid;
    }

    static boolean noScriptOutputWithPolicy(JulcList<TxOut> outputs) {
        boolean clean = true;
        for (var output : outputs) {
            if (AddressLib.isScriptAddress(output.address())) {
                byte[] sh = AddressLib.credentialHash(output.address());
                if (sh.equals(proxyPolicyId)
                        && ValuesLib.containsPolicy(output.value(), proxyPolicyId)) {
                    clean = false;
                    break;
                }
            }
        }
        return clean;
    }

    // =====================================================================
    // Level 2: Handler methods (call Level 0 + Level 1)
    // =====================================================================

    static boolean handleMintBootstrap(JulcList<TxOut> outputs, Value mint,
                                       JulcList<PubKeyHash> signatories) {
        if (!oneOfAdminKeysSigned(signatories)) return false;

        BigInteger mintedCount = ValuesLib.countTokensWithQty(mint, proxyPolicyId, BigInteger.ONE);
        if (!mintedCount.equals(BigInteger.ONE)) return false;

        byte[] mintedTokenName = ValuesLib.findTokenName(mint, proxyPolicyId, BigInteger.ONE);

        boolean found = false;
        for (var output : outputs) {
            if (AddressLib.isScriptAddress(output.address())) {
                byte[] sh = AddressLib.credentialHash(output.address());
                if (sh.equals(proxyPolicyId)) {
                    BigInteger qty = ValuesLib.assetOf(output.value(), proxyPolicyId, mintedTokenName);
                    if (qty.compareTo(BigInteger.ZERO) > 0) {
                        PlutusData datumData = OutputLib.getInlineDatum(output);
                        BootstrapDatum bd = (BootstrapDatum)(Object) datumData;
                        if (bd.tokenName().equals(mintedTokenName)) {
                            found = true;
                            break;
                        }
                    }
                }
            }
        }
        return found;
    }

    static boolean handleMintState(JulcList<TxInInfo> inputs, JulcList<TxInInfo> refInputs,
                                   JulcList<TxOut> outputs, Value mint,
                                   JulcList<PubKeyHash> signatories, Interval validRange,
                                   JulcList<UVerifyCertificate> certificates) {
        boolean foundState = false;
        StateDatum stateDatum = (StateDatum)(Object) Builtins.mkNilData();
        byte[] newTokenName = ByteStringLib.empty();

        for (var output : outputs) {
            if (AddressLib.isScriptAddress(output.address())) {
                byte[] sh = AddressLib.credentialHash(output.address());
                if (sh.equals(proxyPolicyId)) {
                    PlutusData datumData = OutputLib.getInlineDatum(output);
                    StateDatum candidate = (StateDatum)(Object) datumData;
                    byte[] candidateId = candidate.id();
                    BigInteger qty = ValuesLib.assetOf(output.value(), proxyPolicyId, candidateId);
                    if (qty.compareTo(BigInteger.ZERO) > 0) {
                        stateDatum = candidate;
                        newTokenName = candidateId;
                        foundState = true;
                        break;
                    }
                }
            }
        }
        if (!foundState) return false;

        boolean foundBootstrap = false;
        BootstrapDatum bootstrapDatum = (BootstrapDatum)(Object) Builtins.mkNilData();

        for (var refInput : refInputs) {
            if (AddressLib.isScriptAddress(refInput.resolved().address())) {
                byte[] sh = AddressLib.credentialHash(refInput.resolved().address());
                if (sh.equals(proxyPolicyId)) {
                    BigInteger bsTokenQty = ValuesLib.assetOf(
                            refInput.resolved().value(), proxyPolicyId, stateDatum.bootstrapName());
                    if (bsTokenQty.compareTo(BigInteger.ZERO) > 0) {
                        PlutusData bdData = OutputLib.getInlineDatum(refInput.resolved());
                        bootstrapDatum = (BootstrapDatum)(Object) bdData;
                        foundBootstrap = true;
                        break;
                    }
                }
            }
        }
        if (!foundBootstrap) return false;

        BigInteger createdCount = ValuesLib.countTokensWithQty(mint, proxyPolicyId, BigInteger.ONE);
        BigInteger burnedCount = ValuesLib.countTokensWithQty(mint, proxyPolicyId, BigInteger.ONE.negate());
        if (!createdCount.equals(BigInteger.ONE)) return false;
        if (burnedCount.compareTo(BigInteger.ONE) > 0) return false;

        byte[] stateId = stateDatum.id();
        if (!stateId.equals(newTokenName)) return false;
        if (!UVerifyTxLib.hasUniqueId(stateId, inputs)) return false;

        long certCount = certificates.size();
        if (BigInteger.valueOf(certCount).compareTo(stateDatum.batchSize()) > 0) return false;
        if (!certificatesAreValid(stateDatum.certHash(), certificates, signatories)) return false;

        byte[] owner = stateDatum.owner();
        JulcList<byte[]> allowedCreds = bootstrapDatum.allowedCreds();
        if (!allowedCreds.isEmpty()) {
            if (!allowedCreds.contains(owner)) return false;
        }

        if (!stateDatumIsValid(stateDatum, owner, bootstrapDatum)) return false;
        if (!containsServiceFee(outputs, stateDatum)) return false;
        if (!UVerifyTxLib.tokenNotExpired(validRange, stateDatum.ttl())) return false;

        return signatories.any(sig -> sig.hash().equals(owner));
    }

    static boolean handleBurnBootstrap(JulcList<TxInInfo> inputs, JulcList<TxOut> outputs,
                                       Value mint, JulcList<PubKeyHash> signatories) {
        if (!oneOfAdminKeysSigned(signatories)) return false;

        BigInteger burnedCount = ValuesLib.countTokensWithQty(mint, proxyPolicyId, BigInteger.ONE.negate());
        if (!burnedCount.equals(BigInteger.ONE)) return false;

        byte[] burnedTokenName = ValuesLib.findTokenName(mint, proxyPolicyId, BigInteger.ONE.negate());

        boolean foundInput = false;
        for (var input : inputs) {
            if (AddressLib.isScriptAddress(input.resolved().address())) {
                byte[] sh = AddressLib.credentialHash(input.resolved().address());
                if (sh.equals(proxyPolicyId)) {
                    PlutusData datumData = OutputLib.getInlineDatum(input.resolved());
                    BootstrapDatum bd = (BootstrapDatum)(Object) datumData;
                    if (bd.tokenName().equals(burnedTokenName)) {
                        foundInput = true;
                        break;
                    }
                }
            }
        }
        if (!foundInput) return false;

        return noScriptOutputWithPolicy(outputs);
    }

    static boolean handleBurnState(JulcList<TxInInfo> inputs, JulcList<TxOut> outputs,
                                   Value mint, JulcList<PubKeyHash> signatories,
                                   JulcList<UVerifyCertificate> certificates) {
        boolean foundState = false;
        StateDatum stateDatum = (StateDatum)(Object) Builtins.mkNilData();

        for (var input : inputs) {
            if (AddressLib.isScriptAddress(input.resolved().address())) {
                byte[] sh = AddressLib.credentialHash(input.resolved().address());
                if (sh.equals(proxyPolicyId)) {
                    PlutusData datumData = OutputLib.getInlineDatum(input.resolved());
                    stateDatum = (StateDatum)(Object) datumData;
                    foundState = true;
                    break;
                }
            }
        }
        if (!foundState) return false;

        BigInteger burnedCount = ValuesLib.countTokensWithQty(mint, proxyPolicyId, BigInteger.ONE.negate());
        BigInteger createdCount = ValuesLib.countTokensWithQty(mint, proxyPolicyId, BigInteger.ONE);
        if (!burnedCount.equals(BigInteger.ONE)) return false;
        if (createdCount.compareTo(BigInteger.ZERO) > 0) return false;

        byte[] burnedTokenName = ValuesLib.findTokenName(mint, proxyPolicyId, BigInteger.ONE.negate());
        if (!burnedTokenName.equals(stateDatum.id())) return false;
        byte[] stateOwner = stateDatum.owner();
        if (!signatories.any(sig -> sig.hash().equals(stateOwner))) return false;
        if (stateDatum.keepAsOracle()) return false;
        if (!certificates.isEmpty()) return false;

        return noScriptOutputWithPolicy(outputs);
    }

    static boolean handleUpdateState(JulcList<TxInInfo> inputs, JulcList<TxOut> outputs,
                                     Value mint, JulcList<PubKeyHash> signatories,
                                     Interval validRange, JulcList<UVerifyCertificate> certificates) {
        boolean foundCurrent = false;
        StateDatum curDatum = (StateDatum)(Object) Builtins.mkNilData();

        for (var input : inputs) {
            if (AddressLib.isScriptAddress(input.resolved().address())) {
                byte[] sh = AddressLib.credentialHash(input.resolved().address());
                if (sh.equals(proxyPolicyId)) {
                    PlutusData datumData = OutputLib.getInlineDatum(input.resolved());
                    curDatum = (StateDatum)(Object) datumData;
                    foundCurrent = true;
                    break;
                }
            }
        }
        if (!foundCurrent) return false;
        byte[] curOwner = curDatum.owner();
        if (!signatories.any(sig -> sig.hash().equals(curOwner))) return false;

        boolean foundNext = false;
        StateDatum nextDatum = (StateDatum)(Object) Builtins.mkNilData();
        long scriptOutputCount = 0;

        for (var output : outputs) {
            if (AddressLib.isScriptAddress(output.address())) {
                byte[] sh = AddressLib.credentialHash(output.address());
                if (sh.equals(proxyPolicyId)) {
                    scriptOutputCount = scriptOutputCount + 1;
                    BigInteger tokenQty = ValuesLib.assetOf(output.value(), proxyPolicyId, curDatum.id());
                    if (tokenQty.compareTo(BigInteger.ZERO) > 0) {
                        PlutusData datumData = OutputLib.getInlineDatum(output);
                        nextDatum = (StateDatum)(Object) datumData;
                        foundNext = true;
                    }
                }
            }
        }

        if (scriptOutputCount != 1) return false;
        if (!foundNext) return false;
        if (!isStateAlive(curDatum, validRange)) return false;
        if (!verifyStateMutation(nextDatum, curDatum)) return false;
        if (!containsServiceFee(outputs, curDatum)) return false;

        long certCount = certificates.size();
        if (BigInteger.valueOf(certCount).compareTo(curDatum.batchSize()) > 0) return false;

        return certificatesAreValid(nextDatum.certHash(), certificates, signatories);
    }

    // =====================================================================
    // Level 3: Dispatch (calls Level 2 handlers)
    // =====================================================================

    static boolean checkMint(boolean isMintOrBurn, StatePurpose purpose,
                             JulcList<TxInInfo> inputs, JulcList<TxInInfo> refInputs,
                             JulcList<TxOut> outputs, Value mint,
                             JulcList<PubKeyHash> signatories, Interval validRange,
                             JulcList<UVerifyCertificate> certificates) {
        if (!isMintOrBurn) return true;
        return switch (purpose) {
            case MintBootstrap mb -> handleMintBootstrap(outputs, mint, signatories);
            case MintState ms -> handleMintState(inputs, refInputs, outputs, mint, signatories,
                    validRange, certificates);
            case BurnBootstrap bb -> handleBurnBootstrap(inputs, outputs, mint, signatories);
            case BurnState bs -> handleBurnState(inputs, outputs, mint, signatories, certificates);
            case UpdateState us -> true;
        };
    }

    static boolean checkSpend(boolean hasScriptIn, StatePurpose purpose, boolean isMintOrBurn,
                              JulcList<TxInInfo> inputs, JulcList<TxOut> outputs, Value mint,
                              JulcList<PubKeyHash> signatories, Interval validRange,
                              JulcList<UVerifyCertificate> certificates) {
        if (!hasScriptIn) return true;
        return switch (purpose) {
            case BurnBootstrap bb -> isMintOrBurn;
            case BurnState bs -> isMintOrBurn;
            case UpdateState us -> handleUpdateState(inputs, outputs, mint, signatories,
                    validRange, certificates);
            case MintState ms -> true;
            case MintBootstrap mb -> true;
        };
    }

    // =====================================================================
    // Level 4: Entrypoints (call Level 3 dispatch)
    // =====================================================================

    @Entrypoint(purpose = Purpose.WITHDRAW)
    static boolean handleWithdraw(UVerifyStateRedeemer redeemer, ScriptContext ctx) {
        TxInfo txInfo = ctx.txInfo();
        JulcList<TxInInfo> inputs = txInfo.inputs();
        JulcList<TxInInfo> refInputs = txInfo.referenceInputs();
        JulcList<TxOut> outputs = txInfo.outputs();
        Value mint = txInfo.mint();
        JulcList<PubKeyHash> signatories = txInfo.signatories();
        Interval validRange = txInfo.validRange();

        StatePurpose purpose = redeemer.purpose();
        JulcList<UVerifyCertificate> certificates = redeemer.certificates();

        boolean isMintOrBurn = ValuesLib.containsPolicy(mint, proxyPolicyId);
        boolean hasScriptIn = UVerifyTxLib.hasScriptInput(inputs, proxyPolicyId);

        boolean passMint = checkMint(isMintOrBurn, purpose, inputs, refInputs, outputs,
                mint, signatories, validRange, certificates);
        boolean passSpend = checkSpend(hasScriptIn, purpose, isMintOrBurn, inputs, outputs,
                mint, signatories, validRange, certificates);

        return passMint && passSpend;
    }

    @Entrypoint(purpose = Purpose.SPEND)
    static boolean handleSpend(Optional<PlutusData> datum, PlutusData rdmr, ScriptContext ctx) {
        return true;
    }

    @Entrypoint(purpose = Purpose.CERTIFY)
    static boolean handleCertify(PlutusData redeemer, ScriptContext ctx) {
        return true;
    }
}
