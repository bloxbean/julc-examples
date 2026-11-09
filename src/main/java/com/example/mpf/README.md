# Merkle Patricia Forestry (MPF)

`MerklePatriciaForestry` is an `@OnchainLibrary` that verifies membership (`has`) and
non-membership (`miss`) proofs against a radix-16 Merkle Patricia Forestry root. `ProofStep`
and `Neighbor` are the proof types. `validators/MpfRegistryValidator` uses the library.

## Proof shape checks

`has` and `miss` validate proof shape; do not remove these checks.

Before comparing roots, both call `isWellFormed`, which checks every proof step:

- the step's branch nibble (`cursor + skip`) is within the 64-nibble path, and `skip >= 0`;
- `Branch`: `neighbors` is 128 bytes;
- `Fork`: the neighbor nibble is 0..15 and is not the path's nibble at the branch; every byte
  of the neighbor prefix is 0..15; the neighbor root is 32 bytes;
- `Leaf`: `key` and `value` are 32 bytes; the key has the same nibbles as the path before the
  branch and a different nibble at the branch.

A malformed proof makes `has`/`miss` return `false`. `including` and `excluding` compute
roots without these checks, so do not compare their result to a root yourself; call `has`
or `miss`.

Honest proofs must be in the canonical form, which uses `Leaf` and `Fork` steps for
two-child nodes. cardano-client-lib produces this form from PR 716 onward.

## Verifying a proof is not authorization

`has`/`miss` only say whether a key is in the trie with that root. A validator must still
bind the result to the transaction:
- take the root from the right state (datum, reference input or state token) and check how
  it changes in the outputs;
- check signatures or other authorization through `ScriptContext`;
- prevent replay, for example by consuming the state UTxO or binding the proof to a nonce
  or output reference.

`MpfRegistryValidator` is a minimal example that only checks the proof. Do not deploy it
as-is.
