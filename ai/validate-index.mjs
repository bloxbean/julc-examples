// Validate that examples-index.json references real, existing source files
// in this repo, that every concept tag is declared in the concepts dictionary,
// and that ids are unique. Run with:
//
//   node ai/validate-index.mjs
//
// Exits non-zero on any inconsistency.

import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = path.resolve(__dirname, '..');
const INDEX_FILE = path.join(__dirname, 'examples-index.json');

// Detect raw-PlutusData usage so we can verify it is tagged. Looks for
// `PlutusData.cast(`, `PlutusData.ConstrData(`, `PlutusData.IntegerData(`,
// `PlutusData.BytesData(`, `PlutusData.MapData(`, `PlutusData.ListData(`.
// Reading raw `PlutusData redeemer` parameters in the entrypoint is fine
// (boundary), so we look only for *constructions*.
const RAW_PD_RE = /PlutusData\.(?:cast|ConstrData|IntegerData|BytesData|MapData|ListData)\s*\(/;

async function usesRawPlutusData(absSrc) {
  try {
    const code = await fs.readFile(absSrc, 'utf8');
    return RAW_PD_RE.test(code);
  } catch {
    return false;
  }
}

async function main() {
  const raw = await fs.readFile(INDEX_FILE, 'utf8');
  const data = JSON.parse(raw);

  const errors = [];
  const warnings = [];
  const seenIds = new Set();
  const declaredConcepts = new Set(Object.keys(data.concepts || {}));
  let canonicalCount = 0;

  for (const ex of data.examples) {
    if (seenIds.has(ex.id)) {
      errors.push(`duplicate id: ${ex.id}`);
    }
    seenIds.add(ex.id);

    const src = path.join(REPO_ROOT, ex.source);
    try {
      await fs.access(src);
    } catch {
      errors.push(`${ex.id}: source not found — ${ex.source}`);
      continue;
    }

    for (const c of ex.concepts || []) {
      if (!declaredConcepts.has(c)) {
        errors.push(`${ex.id}: undeclared concept "${c}" (add to top-level concepts dict)`);
      }
    }

    if (!['beginner', 'intermediate', 'advanced'].includes(ex.difficulty)) {
      errors.push(`${ex.id}: invalid difficulty "${ex.difficulty}"`);
    }

    if (ex.canonical === true) canonicalCount++;
    if (ex.canonical !== undefined && ex.canonical !== true) {
      errors.push(`${ex.id}: canonical must be true or absent, got ${JSON.stringify(ex.canonical)}`);
    }

    // Catch the Phase B review issue: examples that construct raw PlutusData
    // (and therefore are not the recommended idiom) must carry the
    // raw-plutusdata-interop concept tag so the AI catalog reader knows to
    // treat them as advanced/exceptional rather than canonical patterns.
    const usesPd = await usesRawPlutusData(src);
    const tagged = (ex.concepts || []).includes('raw-plutusdata-interop');
    if (usesPd && !tagged) {
      errors.push(
        `${ex.id}: source uses raw PlutusData (\`${ex.source}\`) ` +
        `but is missing the "raw-plutusdata-interop" concept tag.`
      );
    }
    if (!usesPd && tagged) {
      warnings.push(
        `${ex.id}: tagged "raw-plutusdata-interop" but no raw PlutusData ` +
        `construction was found in the source — tag may be stale.`
      );
    }
  }

  if (errors.length > 0) {
    console.error(`examples-index.json validation failed (${errors.length} errors):`);
    for (const e of errors) console.error('  - ' + e);
    if (warnings.length > 0) {
      console.error(`(plus ${warnings.length} warnings)`);
      for (const w of warnings) console.error('  - ' + w);
    }
    process.exit(1);
  }
  if (warnings.length > 0) {
    console.warn(`examples-index.json validation passed with ${warnings.length} warning(s):`);
    for (const w of warnings) console.warn('  - ' + w);
  }
  console.log(
    `examples-index.json OK: ${data.examples.length} examples, ` +
    `${declaredConcepts.size} concepts, ${canonicalCount} canonical.`
  );
}

main().catch((err) => {
  console.error(err);
  process.exit(1);
});
