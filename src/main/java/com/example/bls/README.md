# BLS12-381 Signature Vaults

Two spending validators that release funds only against a valid BLS12-381 signature, plus an
off-chain demo that produces the signatures with blst and spends the locked UTxOs.

## Overview

These examples exist to exercise the BLS12-381 builtins (CIP-381) end to end: the script is compiled from Java by JuLC,
the signature is produced off-chain with `org.julclang.bls.BlsOperations` (blst), and the
pairing check runs inside whichever Plutus evaluator the demo is pointed at.

- `BlsVaultValidator` — one public key, one signature. Uses `g1Uncompress`, `g2Uncompress`,
  `g1HashToGroup`, `millerLoop` and `finalVerify`, all available on Plutus V3 / PV10.
- `BlsAggregateVaultValidator` — two public keys and their aggregated signature. Adds `g2Add`
  on top of the same pairing check.

Both use the min-signature-size pairing check:

```
e(signature, G2generator) == e(hashToG1(message), publicKey)
```

which holds because `signature = sk · hashToG1(message)` and `publicKey = sk · G2generator`.
For the aggregate case both sides sum: `aggSignature = sig1 + sig2` and `aggKey = pk1 + pk2`.

## Data Types

### @Param Fields (both validators)

| Field         | Type     | Description                                        |
|---------------|----------|----------------------------------------------------|
| `g2Generator` | `byte[]` | Compressed BLS12-381 G2 generator (96 bytes)       |
| `dst`         | `byte[]` | Hash-to-curve domain separation tag                |

### Datum

`BlsVaultValidator.VaultDatum(byte[] publicKey, byte[] message)` — compressed G2 key (96 bytes)
and the message that must be signed.

`BlsAggregateVaultValidator.AggVaultDatum(byte[] publicKey1, byte[] publicKey2, byte[] message)`
— two compressed G2 keys and the message both must sign.

### Redeemer

A `@NewType` wrapper over the compressed G1 signature (48 bytes), so it is encoded as a plain
bytestring rather than a `Constr`:

```java
@NewType
public record Signature(byte[] value) {}
```

## Running

Yaci DevKit must be running. The demo locks 10 ADA at each vault and spends it again.

```bash
# In-process JuLC VM (default)
./gradlew run -PmainClass=com.example.bls.offchain.BlsDemo

# Yaci Store's /utils/txs/evaluate endpoint
./gradlew run -PmainClass=com.example.bls.offchain.BlsDemo -Pevaluator=backend
```

`-Pevaluator=backend` (or `JULC_EVALUATOR=backend`) switches every demo in this repo from the
in-process `JulcTransactionEvaluator` to the backend's evaluation endpoint — see
`YaciHelper.evalMode()`. The demo prints the mode it used, so the two runs can be compared.

## Notes

- The compressed G2 generator is the standard one:
  `93e02b6052719f607dacd3a088274f65596bd0d09920b61ab5da61bbdc7f5049334cf11213945d57e5ac7d055d042b7e024aa2b2f08f0a91260805272dc51051c6e47ad4fa403b02b4510b647ae3d1770bac0326a805bbefd48056c8c121bdb8`
- `BlsOperations` is a JVM (blst) implementation used off-chain only. The `BlsLib` methods the
  validators call throw on the JVM — they exist to be compiled to UPLC builtins.
- The aggregate validator does not check for rogue-key attacks; it is a builtin-coverage
  example, not a production aggregation scheme.
