# JuLC Best Practices & Developer Guide

A comprehensive reference for writing Cardano smart contracts (validators) in JuLC — the Java-to-UPLC compiler. Covers language constraints, idiomatic patterns, standard library usage, testing, and common pitfalls.

---

## 1. Language Constraints — What Works On-Chain

JuLC compiles a subset of Java to Plutus UPLC. Not all Java features are available.

### 1.1 Supported

| Feature | Notes |
|---------|-------|
| `record` types | Primary way to define data types. Fields map to Constr fields. |
| `sealed interface` + `permits` | Enum-like variants. Position in `permits` = Constr tag (0, 1, 2...). |
| `switch` expressions on sealed interfaces | Exhaustiveness checked. Java 21 syntax. `default` branches are supported. |
| `for (var x : list)` | Iterates `JulcList<T>`. |
| `while (cond)` | Supported with accumulator pattern. |
| `if / else` | Standard conditionals. |
| `boolean`, `BigInteger`, `byte[]`, `long` | Primitive on-chain types. |
| `Optional<T>` | Maps to `Constr(0, [value])` / `Constr(1, [])`. |
| Lambda expressions | Single-expression lambdas for `JulcList.any()`, `.find()`, `.filter()`, `.map()`, etc. |
| Static methods | All validator/library methods must be static. |
| `BigInteger` arithmetic | `.add()`, `.subtract()`, `.multiply()`, `.divide()`, `.remainder()`, `.compareTo()` |
| Nested loops | while-in-while, for-each-in-for-each, and mixed nesting all supported. |

### 1.2 Not Supported

| Feature | Workaround |
|---------|------------|
| `null` | Use `Optional<T>`, sentinel values (`Builtins.mkNilData()`), or boolean flags. |
| `new byte[0]` or `new byte[n]` | Use `Builtins.emptyByteString()` or `ByteStringLib.empty()`. |
| `return` inside loops | Use `break` + accumulator variable. |
| `instanceof` pattern matching | Use `switch` on sealed interfaces, or `Builtins.constrTag()` for raw Data. |
| Variable reassignment in loops | Variables are effectively immutable inside loop bodies. Use accumulators that accumulate via `consByteString` / list prepend. For complex accumulation, extract to recursive helper methods. |
| `String` type | Very limited on-chain — only usable for trace messages. Use `byte[]` for all data. |
| `int` / `long` arithmetic | Use `BigInteger` for on-chain integers. `long` is only for Builtins that take machine integers (e.g., `indexByteString`). |
| Object instantiation (`new Foo()`) | Records are auto-constructed from Data. Don't use `new` for on-chain types (except ledger types like `Address`, `Credential`). |
| Exception throwing | Use `return false` or `Builtins.traceError()`. |
| Arrays (other than `byte[]`) | Use `JulcList<T>`. |
| Generics (user-defined) | Only built-in generics like `JulcList<T>`, `Optional<T>`, `JulcMap<K,V>`. |


---

## 2. Validator Structure & Annotations

### 2.1 Single Validator

```java
@SpendingValidator
public class MyValidator {
    @Param static byte[] ownerPkh;

    @Entrypoint
    static boolean validate(MyDatum datum, MyRedeemer redeemer, ScriptContext ctx) {
        return true;
    }
}
```

Single-purpose annotations: `@SpendingValidator`, `@MintingValidator`, `@WithdrawValidator`, `@CertifyingValidator`, `@VotingValidator`, `@ProposingValidator`. The generic `@Validator` annotation still exists for backward compatibility but is deprecated.

### 2.2 Multi-Validator (Multiple Entrypoints)

```java
@MultiValidator
public class MyMultiValidator {
    @Param static byte[] policyId;

    @Entrypoint(purpose = Purpose.MINT)
    static boolean handleMint(MyRedeemer redeemer, ScriptContext ctx) { ... }

    @Entrypoint(purpose = Purpose.SPEND)
    static boolean handleSpend(Optional<PlutusData> datum, MyRedeemer redeemer, ScriptContext ctx) { ... }

    @Entrypoint(purpose = Purpose.WITHDRAW)
    static boolean handleWithdraw(MyRedeemer redeemer, ScriptContext ctx) { ... }
}
```

**Purpose values**: `DEFAULT`, `MINT`, `SPEND`, `WITHDRAW`, `CERTIFY`, `VOTE`, `PROPOSE` (map to ScriptInfo constructor tags 0-5). Use `Purpose.DEFAULT` for manual dispatch with a single entrypoint.

### 2.3 On-Chain Library

Reusable utility methods compiled to UPLC and inlined at use sites. Source is bundled in JAR under `META-INF/plutus-sources/` for auto-discovery by consuming projects.

```java
@OnchainLibrary
public class MyLib {
    public static boolean isValid(byte[] hash) {
        return hash.length > 0;
    }
}
```

### 2.4 NewType — Zero-Cost Type Aliases

`@NewType` marks a single-field record as a zero-cost wrapper. Constructor compiles to identity (no ConstrData wrapping).

```java
@NewType
public record TokenName(byte[] value) {}
```

Supported underlying types: `byte[]`, `BigInteger`, `String`, `boolean`.

---

## 3. Data Types

### 3.1 Records (Product Types)

```java
record StateDatum(byte[] id, byte[] owner, BigInteger fee,
                  JulcList<byte[]> receivers, boolean active) {}
```

- Field order = Constr field order at UPLC level.
- Access via `datum.id()`, `datum.owner()`, etc.
- Compiler auto-generates `headList`/`tailList` chains for field extraction.

### 3.2 Sealed Interfaces (Sum Types / Variants)

```java
sealed interface Action permits Mint, Burn, Update {}
record Mint() implements Action {}
record Burn(byte[] tokenName) implements Action {}
record Update(BigInteger newValue) implements Action {}
```

- **Order in `permits` = Constr tag**: `Mint` = tag 0, `Burn` = tag 1, `Update` = tag 2.
- Use `switch` expressions for dispatch:

```java
return switch (action) {
    case Mint m -> handleMint();
    case Burn b -> handleBurn(b.tokenName());
    case Update u -> handleUpdate(u.newValue());
};
```

### 3.3 Casting PlutusData to Records

When extracting datum from outputs/inputs, cast through `Object`:

```java
PlutusData datumData = OutputLib.getInlineDatum(output);
StateDatum sd = (StateDatum)(Object) datumData;
// Now use sd.id(), sd.owner(), etc.
```

This tells the compiler to destructure the Data value as the target record type.

### 3.4 Sentinel Initialization (No Null)

When a variable must be declared before a loop that assigns it:

```java
StateDatum found = (StateDatum)(Object) Builtins.mkNilData();
boolean wasFound = false;
for (var input : inputs) {
    if (someCondition(input)) {
        found = extractDatum(input);
        wasFound = true;
        break;
    }
}
if (!wasFound) return false;
// Safe to use 'found' here
```

### 3.5 JulcList&lt;T&gt; — On-Chain List

```java
import com.bloxbean.cardano.julc.core.types.JulcList;

// Iteration
for (var item : list) { ... }

// Predicates
list.any(item -> item.equals(target))       // true if any match
list.all(item -> item.length > 0)           // true if all match
list.find(item -> item.equals(target))      // first matching element

// Transformation
list.map(item -> transform(item))           // apply function to each
list.filter(item -> condition(item))        // keep matching elements

// Access
list.head()                                 // first element
list.tail()                                 // all but first
list.get(index)                             // element at index (O(n))
list.size()                                 // count elements
list.isEmpty()                              // check if empty
list.contains(element)                      // check membership

// Construction
list.prepend(element)                       // new list with element at front
list.concat(otherList)                      // concatenate two lists
list.reverse()                              // reversed copy
list.take(n)                                // first n elements
list.drop(n)                                // skip first n elements
JulcList.empty()                            // empty list
JulcList.of(a, b, c)                        // list from elements
```

### 3.6 JulcMap&lt;K, V&gt; — On-Chain Associative Map

```java
import com.bloxbean.cardano.julc.core.types.JulcMap;

map.get(key)                                // lookup value (null if missing)
map.getOrDefault(key, defaultValue)         // lookup with fallback
map.containsKey(key)                        // check if key exists
map.insert(key, value)                      // new map with entry added
map.delete(key)                             // new map without key
map.keys()                                  // all keys as JulcList
map.values()                                // all values as JulcList
map.size()                                  // number of entries
map.isEmpty()                               // check if empty
JulcMap.empty()                             // empty map
JulcMap.of(k1, v1)                          // single-entry map
JulcMap.of(k1, v1, k2, v2)                 // two-entry map
JulcMap.of(k1, v1, k2, v2, k3, v3)         // three-entry map
```

---

## 4. Cast Rules — When Needed vs Removable

### 4.1 LEDGER_HASH Types

`TxId`, `PolicyId`, `ScriptHash`, `PubKeyHash`, `TokenName`, `DatumHash` all resolve to `ByteStringType` at UPLC level.

| Pattern | Cast Needed? | Why |
|---------|-------------|-----|
| `txId.equals(otherTxId)` | **No** | `.equals()` takes `Object`, both are ByteStringType |
| `byte[] x = input.outRef().txId()` | **Yes** | javac needs explicit `(byte[])(Object)` for type conversion |
| `method(input.outRef().txId())` where method takes `byte[]` | **Yes** | javac enforces parameter types |
| `new ScriptCredential(scriptHash)` where scriptHash is `byte[]` | **Yes** | Constructor expects `ScriptHash` type |

**Rule of thumb**: Cast is removable only when using `.equals()` between two LEDGER_HASH types. For variable assignment or method parameter passing across types, javac requires the cast.

### 4.2 Casts That Are Always Needed

| Cast | Purpose |
|------|---------|
| `(RecordType)(Object) plutusData` | Destructure Data as a specific record |
| `(PlutusData)(Object) address` | Upcast record to Data for `Builtins.equalsData()` |
| `(TxOut)(Object) Builtins.mkNilData()` | Sentinel initialization |
| `(ScriptInfo.SpendingScript) ctx.scriptInfo()` | Sealed interface downcast |
| `(byte[])(Object) ledgerHashType` | When assigning to `byte[]` or passing to `byte[]` parameter |

---

## 5. Standard Library Reference

Prefer stdlib methods over raw `Builtins` for readability and safety.

### 5.1 ByteStringLib — Byte Array Operations

```java
import com.bloxbean.cardano.julc.stdlib.lib.ByteStringLib;

// Basic operations
ByteStringLib.append(a, b)                  // concatenate two byte arrays
ByteStringLib.empty()                       // empty byte array
ByteStringLib.cons(byteVal, bs)             // prepend a byte (0-255)
ByteStringLib.length(bs)                    // byte length
ByteStringLib.at(bs, index)                 // byte at index (0-255)
ByteStringLib.equals(a, b)                  // byte equality

// Slicing
ByteStringLib.slice(bs, start, len)         // extract substring
ByteStringLib.drop(bs, n)                   // drop first n bytes
ByteStringLib.take(bs, n)                   // take first n bytes
ByteStringLib.zeros(n)                      // n zero bytes

// Comparison
ByteStringLib.lessThan(a, b)                // lexicographic a < b
ByteStringLib.lessThanEquals(a, b)          // lexicographic a <= b

// Conversion
ByteStringLib.integerToByteString(endian, width, value)  // integer -> bytes
ByteStringLib.byteStringToInteger(endian, bs)            // bytes -> BigInteger
ByteStringLib.toHex(bs)                     // byte[] -> lowercase hex as bytes
ByteStringLib.intToDecimalString(n)         // BigInteger -> decimal string as bytes
ByteStringLib.encodeUtf8(s)                 // PlutusData string -> UTF-8 bytes
ByteStringLib.decodeUtf8(bs)                // UTF-8 bytes -> PlutusData string
ByteStringLib.serialiseData(d)              // Data -> CBOR bytes
```

### 5.2 ValuesLib — Value/Token Operations

```java
import com.bloxbean.cardano.julc.stdlib.lib.ValuesLib;

// Extraction
ValuesLib.lovelaceOf(value)                              // extract lovelace amount
ValuesLib.assetOf(value, policyId, tokenName)            // quantity of specific asset
ValuesLib.containsPolicy(value, policyId)                // check if policy exists
ValuesLib.countTokensWithQty(mint, policyId, qty)        // count tokens with exact qty
ValuesLib.findTokenName(mint, policyId, qty)             // find token name with exact qty
ValuesLib.flatten(value)                                 // flatten to list of (policy, name, amount) triples

// Construction
ValuesLib.singleton(policyId, tokenName, amount)         // single-asset value

// Arithmetic
ValuesLib.add(a, b)                                      // add two values (union, sum amounts)
ValuesLib.subtract(a, b)                                 // subtract value b from a
ValuesLib.negate(value)                                  // negate all amounts

// Comparison
ValuesLib.geq(a, b)                                      // value a >= b (lovelace only)
ValuesLib.geqMultiAsset(a, b)                            // a >= b for ALL policy/token pairs
ValuesLib.leq(a, b)                                      // value a <= b
ValuesLib.eq(a, b)                                       // value a == b
ValuesLib.isZero(value)                                  // all amounts are zero
```

**Important**: Use `value.lovelaceOf()` (instance method via named dispatch) or `ValuesLib.lovelaceOf(value)` (static call) — both work on-chain. Do **not** use `.lovelace()` (different method name, not registered for on-chain dispatch).

### 5.3 AddressLib — Address Inspection

```java
import com.bloxbean.cardano.julc.stdlib.lib.AddressLib;

AddressLib.isScriptAddress(addr)            // true if ScriptCredential
AddressLib.isPubKeyAddress(addr)            // true if PubKeyCredential
AddressLib.credentialHash(addr)             // extract 28-byte credential hash
AddressLib.paymentCredential(addr)          // extract payment credential
```

### 5.4 OutputLib — Output/Input Helpers

```java
import com.bloxbean.cardano.julc.stdlib.lib.OutputLib;

// Datum extraction
OutputLib.getInlineDatum(output)                         // extract inline datum as PlutusData
OutputLib.resolveDatum(txOut, datumsMap)                  // handle both inline and hashed datums

// Output queries
OutputLib.outputsAt(outputs, address)                    // filter outputs to address -> JulcList<TxOut>
OutputLib.countOutputsAt(outputs, address)               // count outputs to address
OutputLib.uniqueOutputAt(outputs, address)               // assert exactly one output, abort if not
OutputLib.outputsWithToken(outputs, policyId, tokenName) // filter outputs containing token

// Value queries
OutputLib.lovelacePaidTo(outputs, address)               // sum lovelace to address
OutputLib.paidAtLeast(outputs, address, minLovelace)     // verify minimum paid
OutputLib.valueHasToken(value, policyId, tokenName)      // check if value has token

// Token-based search
OutputLib.findOutputWithToken(outputs, scriptHash, policyId, tokenName)  // find output at script with token
OutputLib.findInputWithToken(inputs, scriptHash, policyId, tokenName)    // find input at script with token

// Field accessors
OutputLib.txOutAddress(txOut)                            // output address
OutputLib.txOutValue(txOut)                              // output value
OutputLib.txOutDatum(txOut)                              // output datum
```

### 5.5 ContextsLib — ScriptContext Helpers

```java
import com.bloxbean.cardano.julc.stdlib.lib.ContextsLib;

// Signature check
ContextsLib.signedBy(txInfo, pubKeyHash)                 // check if tx signed by key

// Input helpers
ContextsLib.findOwnInput(ctx)                            // find own input (returns Optional-like)
ContextsLib.getContinuingOutputs(ctx)                    // outputs to same address as own input

// Script hash extraction
ContextsLib.ownHash(ctx)                                 // auto-extract script hash (all 6 ScriptInfo variants)
ContextsLib.ownInputScriptHash(ctx)                      // script hash from own input

// Datum lookup
ContextsLib.getSpendingDatum(ctx)                        // optional datum from spending script
ContextsLib.findDatum(txInfo, hash)                      // search datums map by hash

// Output queries
ContextsLib.scriptOutputsAt(txInfo, scriptHash)          // filter outputs by script credential
ContextsLib.valueSpent(txInfo)                           // collect all input values
ContextsLib.valuePaid(txInfo, address)                   // filter output values by address

// TxInfo field accessors
ContextsLib.txInfoRefInputs(txInfo)                      // reference inputs
ContextsLib.txInfoWithdrawals(txInfo)                    // withdrawals map
ContextsLib.txInfoRedeemers(txInfo)                      // redeemers map

// Tracing
ContextsLib.trace(message)                               // emit trace message
```

### 5.6 CryptoLib — Hashing & Signatures

```java
import com.bloxbean.cardano.julc.stdlib.lib.CryptoLib;

// Hashing
CryptoLib.sha2_256(bs)                                   // SHA2-256
CryptoLib.sha3_256(bs)                                   // SHA3-256
CryptoLib.blake2b_224(bs)                                // Blake2b-224 (key hashes)
CryptoLib.blake2b_256(bs)                                // Blake2b-256
CryptoLib.keccak_256(bs)                                 // Keccak-256
CryptoLib.ripemd_160(bs)                                 // RIPEMD-160

// Signature verification
CryptoLib.verifyEd25519Signature(pubKey, msg, sig)       // Ed25519
CryptoLib.verifyEcdsaSecp256k1(vk, msg, sig)             // ECDSA secp256k1
CryptoLib.verifySchnorrSecp256k1(vk, msg, sig)           // Schnorr secp256k1
```

### 5.7 IntervalLib — Time Range Operations

```java
import com.bloxbean.cardano.julc.stdlib.lib.IntervalLib;

// Bound extraction
IntervalLib.finiteUpperBound(validRange)                 // extract upper bound time
IntervalLib.finiteLowerBound(validRange)                 // extract lower bound time
IntervalLib.contains(interval, time)                     // check if time is in interval

// Factory methods
IntervalLib.always()                                     // (-inf, +inf)
IntervalLib.never()                                      // empty interval
IntervalLib.after(time)                                  // [time, +inf)
IntervalLib.before(time)                                 // (-inf, time]
IntervalLib.between(low, high)                           // [low, high]
IntervalLib.isEmpty(interval)                            // check if empty
```

Or use typed switch on `IntervalBoundType`:

```java
IntervalBound upperBound = validRange.to();
return switch (upperBound.boundType()) {
    case IntervalBoundType.Finite f -> f.time().compareTo(ttl) < 0;
    case IntervalBoundType.NegInf ignored -> false;
    case IntervalBoundType.PosInf ignored -> false;
};
```

### 5.8 ListsLib — List Operations

```java
import com.bloxbean.cardano.julc.stdlib.lib.ListsLib;

ListsLib.empty()                            // empty list
ListsLib.prepend(list, element)             // prepend element
ListsLib.length(list)                       // count elements
ListsLib.isEmpty(list)                      // check if empty
ListsLib.head(list)                         // first element
ListsLib.tail(list)                         // rest of list
ListsLib.reverse(list)                      // reverse a list
ListsLib.concat(a, b)                       // concatenate two lists
ListsLib.nth(list, n)                       // element at index
ListsLib.take(list, n)                      // first n elements
ListsLib.drop(list, n)                      // skip first n elements
ListsLib.contains(list, target)             // membership check (uses EqualsData)
ListsLib.containsInt(list, target)          // membership for integer lists
ListsLib.containsBytes(list, target)        // membership for byte[] lists
ListsLib.hasDuplicateInts(list)             // O(n^2) duplicate check for ints
ListsLib.hasDuplicateBytes(list)            // O(n^2) duplicate check for bytes
```

### 5.9 MapLib — Map Operations

```java
import com.bloxbean.cardano.julc.stdlib.lib.MapLib;

MapLib.lookup(map, key)                     // returns Optional-like Constr
MapLib.member(map, key)                     // check if key exists
MapLib.insert(map, key, value)              // insert entry
MapLib.delete(map, key)                     // remove entry
MapLib.keys(map)                            // extract all keys as list
MapLib.values(map)                          // extract all values as list
MapLib.toList(map)                          // convert to pair list
MapLib.fromList(list)                       // construct from pair list
MapLib.size(map)                            // count entries
```

### 5.10 MathLib — Integer Math

```java
import com.bloxbean.cardano.julc.stdlib.lib.MathLib;

MathLib.abs(x)                              // absolute value
MathLib.max(a, b)                           // maximum
MathLib.min(a, b)                           // minimum
MathLib.divMod(a, b)                        // (quotient, remainder) tuple
MathLib.pow(base, exp)                      // integer exponentiation
MathLib.expMod(base, exp, mod)              // modular exponentiation
MathLib.sign(x)                             // returns -1, 0, or 1
```

### 5.11 BitwiseLib — Bitwise Operations

```java
import com.bloxbean.cardano.julc.stdlib.lib.BitwiseLib;

BitwiseLib.andByteString(padding, a, b)     // bitwise AND
BitwiseLib.orByteString(padding, a, b)      // bitwise OR
BitwiseLib.xorByteString(padding, a, b)     // bitwise XOR
BitwiseLib.complementByteString(bs)         // bitwise NOT
BitwiseLib.readBit(bs, index)               // read bit at index
BitwiseLib.writeBits(bs, indices, value)     // write bits at indices
BitwiseLib.shiftByteString(bs, n)           // shift by n bits
BitwiseLib.rotateByteString(bs, n)          // rotate by n bits
BitwiseLib.countSetBits(bs)                 // population count
BitwiseLib.findFirstSetBit(bs)              // find first set bit index
```

### 5.12 When to Use Builtins Directly

Some operations have no higher-level wrapper and require `Builtins` directly:

```java
Builtins.mkNilData()                        // Sentinel/placeholder initialization
Builtins.equalsData(a, b)                   // Deep structural equality on Data
Builtins.constrTag(data)                    // Get constructor tag (prefer sealed interface switch)
Builtins.traceError(msg)                    // Abort with error message
Builtins.trace(msg, value)                  // Emit trace message and return value
Builtins.error()                            // Abort script
```

---

## 6. Ledger Types Reference

### 6.1 Core Types

| Type | Description |
|------|-------------|
| `ScriptContext` | Contains `txInfo()`, `redeemer()`, `scriptInfo()` |
| `TxInfo` | Transaction fields: `inputs()`, `referenceInputs()`, `outputs()`, `fee()`, `mint()`, `certificates()`, `withdrawals()`, `validRange()`, `signatories()`, `redeemers()`, `datums()`, `txId()`, `votes()`, `proposalProcedures()`, `currentTreasuryAmount()`, `treasuryDonation()` |
| `TxInInfo` | Input: `outRef()` (TxOutRef), `resolved()` (TxOut) |
| `TxOut` | Output: `address()`, `value()`, `datum()`, `referenceScript()` |
| `TxOutRef` | UTXO reference: `txId()`, `index()` |
| `Value` | Multi-asset value (lovelace + tokens) |
| `Address` | Payment credential + optional staking credential |
| `Interval` | Time range: `from()`, `to()` as `IntervalBound` |
| `IntervalBound` | Bound: `boundType()` + `isInclusive()` |

### 6.2 LEDGER_HASH Types (all resolve to ByteStringType)

`TxId`, `PolicyId`, `ScriptHash`, `PubKeyHash`, `TokenName`, `DatumHash`

### 6.3 Sealed Interface Variants

**Credential** (2 variants):
| Variant | Tag | Fields |
|---------|-----|--------|
| `PubKeyCredential(PubKeyHash hash)` | 0 | public key hash |
| `ScriptCredential(ScriptHash hash)` | 1 | script hash |

**IntervalBoundType** (3 variants):
| Variant | Tag | Fields |
|---------|-----|--------|
| `NegInf()` | 0 | none |
| `Finite(BigInteger time)` | 1 | POSIX time |
| `PosInf()` | 2 | none |

**OutputDatum** (3 variants):
| Variant | Tag | Fields |
|---------|-----|--------|
| `NoOutputDatum()` | 0 | none |
| `OutputDatumHash(DatumHash hash)` | 1 | datum hash |
| `OutputDatumInline(PlutusData datum)` | 2 | inline datum |

**ScriptInfo** (6 variants):
| Variant | Tag | Fields |
|---------|-----|--------|
| `MintingScript(PolicyId policyId)` | 0 | policy ID |
| `SpendingScript(TxOutRef txOutRef, Optional<PlutusData> datum)` | 1 | spent UTXO ref + optional datum |
| `RewardingScript(Credential credential)` | 2 | staking credential |
| `CertifyingScript(BigInteger index, TxCert cert)` | 3 | cert index + certificate |
| `VotingScript(Voter voter)` | 4 | voter |
| `ProposingScript(BigInteger index, ProposalProcedure procedure)` | 5 | proposal index + procedure |

**TxCert** (11 variants — Conway era):
| Variant | Tag |
|---------|-----|
| `RegStaking(Credential, Optional<BigInteger> deposit)` | 0 |
| `UnRegStaking(Credential, Optional<BigInteger> refund)` | 1 |
| `DelegStaking(Credential, Delegatee)` | 2 |
| `RegDeleg(Credential, Delegatee, BigInteger)` | 3 |
| `RegDRep(Credential, BigInteger deposit)` | 4 |
| `UpdateDRep(Credential)` | 5 |
| `UnRegDRep(Credential, BigInteger refund)` | 6 |
| `PoolRegister(PubKeyHash, PubKeyHash)` | 7 |
| `PoolRetire(PubKeyHash, BigInteger epoch)` | 8 |
| `AuthHotCommittee(Credential cold, Credential hot)` | 9 |
| `ResignColdCommittee(Credential cold)` | 10 |

**Vote** (3 variants):
| Variant | Tag |
|---------|-----|
| `VoteNo()` | 0 |
| `VoteYes()` | 1 |
| `Abstain()` | 2 |

**GovernanceAction** (7 variants):
| Variant | Tag |
|---------|-----|
| `ParameterChange(Optional<GovernanceActionId>, PlutusData, Optional<ScriptHash>)` | 0 |
| `HardForkInitiation(Optional<GovernanceActionId>, ProtocolVersion)` | 1 |
| `TreasuryWithdrawals(JulcMap<Credential, BigInteger>, Optional<ScriptHash>)` | 2 |
| `NoConfidence(Optional<GovernanceActionId>)` | 3 |
| `UpdateCommittee(Optional<GovernanceActionId>, JulcList<Credential>, JulcMap<Credential, BigInteger>, Rational)` | 4 |
| `NewConstitution(Optional<GovernanceActionId>, Optional<ScriptHash>)` | 5 |
| `InfoAction()` | 6 |

---

## 7. Idiomatic Patterns

### 7.1 Search Pattern — Find Item in List

```java
boolean found = false;
MyRecord result = (MyRecord)(Object) Builtins.mkNilData();
for (var item : items) {
    if (matchCondition(item)) {
        result = item;
        found = true;
        break;
    }
}
if (!found) return false;
```

### 7.2 Accumulation Pattern — Aggregate Over List

```java
BigInteger total = BigInteger.ZERO;
for (var output : outputs) {
    if (matchCondition(output)) {
        total = total.add(ValuesLib.lovelaceOf(output.value()));
    }
}
```

### 7.3 All-Match Pattern — Verify All Items

```java
boolean allValid = true;
for (var cert : certs) {
    if (!isValid(cert)) {
        allValid = false;
        break;
    }
}
if (!allValid) return false;
```

### 7.4 Script Output Search Pattern

```java
for (var output : outputs) {
    if (AddressLib.isScriptAddress(output.address())) {
        byte[] sh = AddressLib.credentialHash(output.address());
        if (sh.equals(expectedScriptHash)) {
            PlutusData datumData = OutputLib.getInlineDatum(output);
            MyDatum datum = (MyDatum)(Object) datumData;
            // Use datum...
            break;
        }
    }
}
```

### 7.5 Recursive Helpers (for Complex Accumulation)

When loop accumulation is too complex (nested loops, function calls inside loops):

```java
public static byte[] toHex(byte[] bs) {
    long len = Builtins.lengthOfByteString(bs);
    if (len == 0) return Builtins.emptyByteString();
    return toHexStep(bs, len - 1, Builtins.emptyByteString());
}

public static byte[] toHexStep(byte[] bs, long idx, byte[] acc) {
    long b = Builtins.indexByteString(bs, idx);
    byte[] updated = Builtins.consByteString(hexNibble(b / 16),
            Builtins.consByteString(hexNibble(b % 16), acc));
    if (idx == 0) return updated;
    return toHexStep(bs, idx - 1, updated);
}
```

### 7.6 Address Equality via equalsData

For comparing `Address` values (complex record types), use structural equality:

```java
PlutusData addrA = (PlutusData)(Object) output.address();
PlutusData addrB = (PlutusData)(Object) expectedAddress;
if (Builtins.equalsData(addrA, addrB)) {
    // Addresses match
}
```

---

## 8. Testing

### 8.1 Library Method Tests — JulcEval

For testing `@OnchainLibrary` methods (compiled and evaluated on UPLC VM):

```java
class MyLibTest {
    private static final String WRAPPER = """
            import com.bloxbean.cardano.julc.stdlib.lib.ByteStringLib;
            import java.math.BigInteger;

            @OnchainLibrary
            class TestWrapper {
                static byte[] myMethod(byte[] input) {
                    return ByteStringLib.toHex(input);
                }
            }
            """;

    private final JulcEval eval = JulcEval.forSource(WRAPPER);

    @Test
    void testMyMethod() {
        byte[] result = eval.call("myMethod", new byte[]{(byte) 0xDE, (byte) 0xAD})
                            .asByteString();
        assertArrayEquals("dead".getBytes(), result);
    }
}
```

**When to use**: Testing stdlib methods, pure utility functions, any `@OnchainLibrary` code.

**Cannot use JulcEval for validators** — JulcEval compiles individual methods via `compileMethod()`, not parameterized validators with `@Param` fields and `ScriptContext`.

### 8.2 Validator Tests — ContractTest

For testing `@Validator` or `@MultiValidator` classes:

```java
class MyValidatorTest extends ContractTest {

    @BeforeAll
    static void setup() {
        initCrypto();  // Required for CryptoLib functions
    }

    com.bloxbean.cardano.julc.core.Program compileAndApplyParams() {
        var result = compileValidator(MyValidator.class);
        assertFalse(result.hasErrors(), "Should compile: " + result);
        return result.program().applyParams(
                PlutusData.bytes(PARAM1),
                PlutusData.list(PlutusData.bytes(PARAM2)));
    }

    @Test
    void compilesSuccessfully() {
        var result = compileValidator(MyValidator.class);
        assertFalse(result.hasErrors(), "Should compile: " + result);
        System.out.println("Script size: " + result.scriptSizeFormatted());
    }

    @Test
    void validTransaction_succeeds() throws Exception {
        var program = compileAndApplyParams();
        var spentRef = TestDataBuilder.randomTxOutRef_typed();

        var input = new TxInInfo(spentRef,
                new TxOut(scriptAddress(), Value.lovelace(BigInteger.valueOf(10_000_000)),
                        new OutputDatum.NoOutputDatum(), Optional.empty()));

        var ctx = spendingContext(spentRef)
                .redeemer(PlutusData.constr(0))
                .input(input)
                .output(changeOutput)
                .fee(BigInteger.valueOf(500_000))
                .signer(SIGNER_KEY)
                .buildPlutusData();

        var evalResult = evaluate(program, ctx);
        assertSuccess(evalResult);
    }
}
```

### 8.3 Context Builder API

```java
// Spending context
spendingContext(txOutRef)
spendingContext(txOutRef, datum)

// Other context types
mintingContext(policyId)
rewardingContext(credential)
certifyingContext(index, txCert)
votingContext(voter)

// Fluent builder
    .redeemer(plutusData)
    .input(txInInfo)
    .referenceInput(txInInfo)
    .output(txOut)
    .fee(bigInteger)
    .signer(byte[])
    .mint(value)
    .validRange(interval)
    .txId(txId)
    .certificate(txCert)
    .withdrawal(credential, amount)
    .datum(datumHash, plutusData)
    .build()                              // returns ledger-api ScriptContext
    .buildOnchain()                       // returns onchain-api ScriptContext
    .buildPlutusData()                    // returns PlutusData for evaluation
```

### 8.4 Test Data Construction

```java
// Random test data
TestDataBuilder.randomTxOutRef_typed()
TestDataBuilder.randomPubKeyHash_typed()
TestDataBuilder.randomScriptHash()
TestDataBuilder.randomDatumHash()
TestDataBuilder.randomBytes(length)

// PlutusData factories
TestDataBuilder.constrData(tag, fields...)
TestDataBuilder.intData(value)
TestDataBuilder.bytesData(bytes)
TestDataBuilder.listData(items...)
TestDataBuilder.mapData(keysAndValues...)    // alternating K, V
TestDataBuilder.unitData()
TestDataBuilder.boolData(value)

// Typed factories
TestDataBuilder.pubKeyAddress(pkh)
TestDataBuilder.txOut(address, value)
TestDataBuilder.txIn(outRef, resolved)

// Addresses
new Address(new Credential.ScriptCredential(new ScriptHash(hash)), Optional.empty())
new Address(new Credential.PubKeyCredential(new PubKeyHash(pkh)), Optional.empty())

// Values
Value.lovelace(BigInteger.valueOf(2_000_000))
Value.singleton(policyId, tokenName, BigInteger.ONE)
value1.merge(value2)

// Intervals
Interval.between(BigInteger.ZERO, BigInteger.valueOf(1_700_000_000_000L))
Interval.always()
Interval.after(time)
Interval.before(time)
```

### 8.5 Assertions

```java
// Success/failure
assertSuccess(evalResult)
assertFailure(evalResult)

// Budget limits
assertBudgetUnder(evalResult, maxCpu, maxMem)

// Script size
assertScriptSizeUnder(compileResult, maxBytes)

// Trace messages
assertTrace(evalResult, "expected substring")       // substring match
assertTraceExact(evalResult, "exact", "messages")   // exact match
assertNoTraces(evalResult)

// Budget logging
var budget = evalResult.budgetConsumed();
System.out.println("CPU: " + budget.cpuSteps() + ", Mem: " + budget.memoryUnits());
```

---

## 9. Parameters

### 9.1 Declaring Parameters

```java
@Param static byte[] policyId;
@Param static BigInteger threshold;
@Param static JulcList<byte[]> allowedKeys;
```

- Applied via UPLC partial application at deploy time.
- Each unique param set produces a different script hash/address.
- Parameters applied in **declaration order**.

### 9.2 Applying Parameters in Tests

```java
program.applyParams(
    PlutusData.bytes(policyIdBytes),              // first @Param
    PlutusData.integer(BigInteger.valueOf(100)),   // second @Param
    PlutusData.list(PlutusData.bytes(key1), PlutusData.bytes(key2))  // third @Param (list)
);
```

---

## 10. Common Gotchas

### 10.1 `lovelace()` vs `ValuesLib.lovelaceOf()`

```java
// WRONG — causes "Unbound variable: compareTo" at UPLC level
BigInteger amount = output.value().lovelace();

// CORRECT
BigInteger amount = ValuesLib.lovelaceOf(output.value());
```

### 10.2 BigInteger Comparison

```java
// WRONG — reference equality in Java, undefined behavior in UPLC
if (a == BigInteger.ZERO)

// CORRECT
if (a.equals(BigInteger.ZERO))
if (a.compareTo(BigInteger.ZERO) > 0)
```

### 10.3 JVM vs UPLC Behavior Difference

`Builtins.integerToByteString(true, 0, 0)`:
- **JVM**: Returns `[0]` (1 byte) — BigInteger.ZERO.toByteArray() bug
- **UPLC**: Returns `[]` (empty)

**Fix in tests**: Manually handle zero: `if (n.signum() == 0) return new byte[0];`

### 10.4 `contains()` on ByteString Lists

```java
// May not work correctly when element type resolution differs
list.contains(byteArrayValue)

// Safer — always works
list.any(item -> item.equals(byteArrayValue))
```

### 10.5 Sealed Interface Tag Assignment

Tags are assigned by **position in `permits`**, not alphabetically:

```java
sealed interface Action permits Mint, Burn, Update {}
// Mint = tag 0, Burn = tag 1, Update = tag 2

// In tests, construct redeemers by tag:
PlutusData.constr(0)  // Mint
PlutusData.constr(1, PlutusData.bytes(name))  // Burn(tokenName)
PlutusData.constr(2, PlutusData.integer(val))  // Update(newValue)
```

### 10.6 Test Package Must Match Validator Package

Tests accessing package-private members (like `@Param` fields) must be in the same package:

```
src/main/java/com/example/myapp/onchain/MyValidator.java
src/test/java/com/example/myapp/onchain/MyValidatorTest.java  // Same package
```

### 10.7 JuLC Version Includes Commit Hash

```groovy
julcVersion = '0.1.0-904996b-SNAPSHOT'  // Updates with each julc commit
```

After changes in the julc repo, update the version to match the new commit hash.

### 10.8 Nested While Loops

Nested loops (while-in-while, for-each-in-for-each, mixed) are supported. The compiler assigns unique names to each loop function to prevent collisions. For deeply nested while+while patterns with many accumulators, consider extracting inner loops into separate methods for readability.

---

## 11. Off-Chain Integration (cardano-client-lib)

### 11.1 Asset Names Need "0x" Prefix

```java
// WRONG — treated as UTF-8 text
new Asset("000643b0abcdef", qty)

// CORRECT — decoded as hex bytes
new Asset("0x" + HexUtil.encodeHexString(tokenName), qty)
```

### 11.2 Minting — Always Use 4-Arg Version

```java
scriptTx.mintAsset(script, asset, redeemer, receiverAddr)  // CORRECT
scriptTx.mintAsset(script, asset, redeemer)                // AVOID
```

### 11.3 Script Outputs with Datum

```java
scriptTx.payToContract(scriptAddr, Amount.ada(5), datumPlutusData)  // CORRECT
```

### 11.4 MultiAsset Zero-Filtering Bug

`MultiAsset.add()` doesn't filter zero-valued assets. When burning tokens, zero-valued entries cause node rejection. **Fix**: Patch `MultiAsset.add()` to filter zeros.

---

## 12. Project Structure

```
src/main/java/com/example/myapp/
    onchain/
        MyValidator.java          # @Validator or @MultiValidator
        MyLib.java                # @OnchainLibrary (optional)
src/test/java/com/example/myapp/
    onchain/
        MyValidatorTest.java      # extends ContractTest
    util/
        MyLibTest.java            # uses JulcEval
```

### Gradle Dependencies

```groovy
dependencies {
    implementation "com.bloxbean.cardano:julc-stdlib:${julcVersion}"
    implementation "com.bloxbean.cardano:julc-ledger-api:${julcVersion}"
    annotationProcessor "com.bloxbean.cardano:julc-annotation-processor:${julcVersion}"

    testImplementation "com.bloxbean.cardano:julc-testkit:${julcVersion}"
    testImplementation 'org.junit.jupiter:junit-jupiter:5.10.2'
}
```

---

## 13. Performance Tips

- **Admin bypass early return**: Check admin signatures first to short-circuit expensive validation.
- **`break` on first match**: Don't iterate the entire list when looking for a single item.
- **Ed25519 verification is expensive**: ~300M CPU steps. Structure validation so cheaper checks happen first.
- **`equalsData` for deep equality**: More efficient than field-by-field comparison for complex nested types.
- **Minimize `toHex()` / string operations**: On-chain string manipulation is byte-by-byte and costs significant CPU.
- **Use `OutputLib` helpers**: `lovelacePaidTo()`, `outputsAt()` are optimized and reduce boilerplate.
- **Prefer `JulcList.any()` over manual loops**: For simple predicates, lambda-based methods are more concise and equally efficient.

---

## 14. Quick Reference — Import Cheat Sheet

```java
// Core
import com.bloxbean.cardano.julc.core.PlutusData;
import com.bloxbean.cardano.julc.core.types.JulcList;
import com.bloxbean.cardano.julc.core.types.JulcMap;

// Ledger types
import com.bloxbean.cardano.julc.ledger.*;

// Annotations
import com.bloxbean.cardano.julc.stdlib.annotation.Validator;
import com.bloxbean.cardano.julc.stdlib.annotation.MultiValidator;
import com.bloxbean.cardano.julc.stdlib.annotation.Entrypoint;
import com.bloxbean.cardano.julc.stdlib.annotation.Param;
import com.bloxbean.cardano.julc.stdlib.annotation.Purpose;
import com.bloxbean.cardano.julc.stdlib.annotation.OnchainLibrary;
import com.bloxbean.cardano.julc.stdlib.annotation.NewType;

// Builtins (use sparingly)
import com.bloxbean.cardano.julc.stdlib.Builtins;

// Standard library (prefer over Builtins)
import com.bloxbean.cardano.julc.stdlib.lib.ByteStringLib;
import com.bloxbean.cardano.julc.stdlib.lib.ValuesLib;
import com.bloxbean.cardano.julc.stdlib.lib.AddressLib;
import com.bloxbean.cardano.julc.stdlib.lib.OutputLib;
import com.bloxbean.cardano.julc.stdlib.lib.CryptoLib;
import com.bloxbean.cardano.julc.stdlib.lib.IntervalLib;
import com.bloxbean.cardano.julc.stdlib.lib.ContextsLib;
import com.bloxbean.cardano.julc.stdlib.lib.ListsLib;
import com.bloxbean.cardano.julc.stdlib.lib.MapLib;
import com.bloxbean.cardano.julc.stdlib.lib.MathLib;
import com.bloxbean.cardano.julc.stdlib.lib.BitwiseLib;

// Testing
import com.bloxbean.cardano.julc.testkit.ContractTest;
import com.bloxbean.cardano.julc.testkit.JulcEval;
import com.bloxbean.cardano.julc.testkit.TestDataBuilder;
```
