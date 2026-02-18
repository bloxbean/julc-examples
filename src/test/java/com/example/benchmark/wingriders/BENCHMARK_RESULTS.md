# WingRiders DEX Benchmark — JuLC vs Plinth / Aiken / Plutarch

This benchmark re-implements the [WingRiders parsing-education](https://github.com/WingRiders/parsing-education)
request-parsing pool validator in JuLC.

## What is measured

The pool validator parses N request inputs, extracts inline datums, validates
script hashes, and checks output datum types — the same logic across all four
languages. The benchmark scales from 1 to 30 requests per transaction.

## How to reproduce

```bash
cd julc-examples
./gradlew test --tests "com.example.benchmark.wingriders.*"
```

## Notes

- Reference numbers for Plinth, Aiken, and Plutarch are from the
  [WingRiders parsing-education repository](https://github.com/WingRiders/parsing-education).
- JuLC per-request CPU increases at higher request counts due to O(n) list
  indexing (each `inputs.get(i)` walks the list from the head). This is
  inherent to the UPLC list representation shared by all Plutus languages.
- The benchmark validator performs a simplified subset of the full WingRiders
  pool validation (script hash check + inline datum check). The relative
  performance ranking is representative of real-world parsing overhead.
