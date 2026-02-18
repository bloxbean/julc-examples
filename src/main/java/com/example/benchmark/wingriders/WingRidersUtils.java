package com.example.benchmark.wingriders;

import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;
import com.bloxbean.cardano.julc.ledger.*;
import com.bloxbean.cardano.julc.stdlib.Builtins;
import com.bloxbean.cardano.julc.stdlib.lib.ByteStringLib;
import com.bloxbean.cardano.julc.stdlib.lib.CryptoLib;
import com.bloxbean.cardano.julc.stdlib.lib.ValuesLib;

import java.math.BigInteger;
import java.util.List;

/**
 * Shared utility functions for WingRiders validators.
 */
@OnchainLibrary
public class WingRidersUtils {

    /**
     * Extract the credential hash from an Address using switch expression.
     * Both PubKeyCredential and ScriptCredential have a hash field.
     */
    public static byte[] credentialHash(Address address) {
        Credential cred = address.credential();
        return switch (cred) {
            case Credential.PubKeyCredential pk -> Builtins.toByteString(pk.hash());
            case Credential.ScriptCredential sc -> Builtins.toByteString(sc.hash());
        };
    }

    /**
     * Check if an address has a ScriptCredential (tag 1).
     * Uses switch to pattern match the credential type.
     */
    public static boolean isScriptAddress(Address address) {
        Credential cred = address.credential();
        return switch (cred) {
            case Credential.PubKeyCredential pk -> false;
            case Credential.ScriptCredential sc -> true;
        };
    }

    /**
     * Check if a list of BigInteger indices contains any duplicate.
     */
    public static boolean containsDuplicate(List<BigInteger> indices) {
        int size = indices.size();
        int i = 0;
        while (i < size) {
            BigInteger current = indices.get(i);
            int j = i + 1;
            while (j < size) {
                if (current.compareTo(indices.get(j)) == 0) {
                    return true;
                }
                j = j + 1;
            }
            i = i + 1;
        }
        return false;
    }

    /**
     * Extract the finite upper bound time from a validity interval.
     * Returns the time value if the upper bound is Finite, otherwise returns -1.
     */
    public static BigInteger finiteValidityEnd(Interval validRange) {
        IntervalBound upperBound = validRange.to();
        IntervalBoundType boundType = upperBound.boundType();
        return switch (boundType) {
            case IntervalBoundType.Finite f -> f.time();
            case IntervalBoundType.NegInf ni -> BigInteger.valueOf(-1);
            case IntervalBoundType.PosInf pi -> BigInteger.valueOf(-1);
        };
    }

    /**
     * Compute the share asset name:
     * sha3_256(sha3_256(aPolicy) ++ sha3_256(aName) ++ sha3_256(bPolicy) ++ sha3_256(bName))
     */
    public static byte[] shareClassAssetName(byte[] aPolicyId, byte[] aAssetName,
                                             byte[] bPolicyId, byte[] bAssetName) {
        byte[] hashAPolicy = CryptoLib.sha3_256(aPolicyId);
        byte[] hashAName = CryptoLib.sha3_256(aAssetName);
        byte[] hashBPolicy = CryptoLib.sha3_256(bPolicyId);
        byte[] hashBName = CryptoLib.sha3_256(bAssetName);

        byte[] combined = ByteStringLib.append(
                ByteStringLib.append(hashAPolicy, hashAName),
                ByteStringLib.append(hashBPolicy, hashBName));
        return CryptoLib.sha3_256(combined);
    }
}
