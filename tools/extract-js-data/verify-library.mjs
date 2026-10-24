// Differential verification for the Library / Compendium port (phase 5).
//
// Runs the original RN sources in Node - the real Compendium/index.js and
// Titles.js, the RelationshipsTable / YinYangRef / HeavenEarthMan literals
// brace-extracted verbatim (library-source.mjs), the four Tutorial* onboarding
// page arrays (tutorial-source.mjs) and the TwelveStagesList loop over the real
// Ordinals data - then compares three ways:
//   1. RN vs the committed golden file,
//   2. RN vs the live Java endpoints (compendium / relationships /
//      twelve-stages / yin-yang / heaven-earth-man / tutorials).
//
// Usage:
//   node --import ./register-hooks.mjs ./verify-library.mjs
//
// Env overrides (defaults shown):
//   BUNDLE_ROOT = C:/Temp/myTmp/bundle-sources/src/src/lib/cmtools
//   API         = http://localhost:8080
//   GOLDEN      = ../../bazi-spring/src/test/resources/golden/library.json
import fs from 'node:fs';
import { pathToFileURL } from 'node:url';
import { extractLibrarySources } from './library-source.mjs';
import { extractTutorials } from './tutorial-source.mjs';

const ROOT = (process.env.BUNDLE_ROOT || 'C:/Temp/myTmp/bundle-sources/src/src/lib/cmtools').replace(/\/$/, '');
const API = (process.env.API || 'http://localhost:8080').replace(/\/$/, '');
const GOLDEN = process.env.GOLDEN || '../../bazi-spring/src/test/resources/golden/library.json';

const url = (rel) => pathToFileURL(ROOT + '/' + rel).href;
const Compendium = (await import(url('Compendium/index.js'))).default;
const Titles = (await import(url('Compendium/Titles.js'))).default;
const Ordinals = await import(url('Ordinals.js'));
const tables = extractLibrarySources(ROOT);
const tutorials = extractTutorials(ROOT);
const golden = JSON.parse(fs.readFileSync(GOLDEN, 'utf8'));

// ---------------------------------------------------------------- helpers

let checks = 0;
let failures = 0;
let printed = 0;

function check(cond, label, rn, java) {
  checks++;
  if (cond) return true;
  failures++;
  if (printed++ < 8) {
    console.log('  !! ' + label);
    console.log('     RN  : ' + JSON.stringify(rn));
    console.log('     Java: ' + JSON.stringify(java));
  }
  return false;
}

// Semantic deep equality (numbers by value, objects by key set) so Java's
// Long/Double serialization cannot produce false mismatches.
function sameJson(a, b) {
  if (a === b) return true;
  if (typeof a === 'number' || typeof b === 'number') {
    return typeof a === 'number' && typeof b === 'number' && Object.is(a, b);
  }
  if (typeof a !== typeof b || a === null || b === null) return false;
  if (Array.isArray(a) || Array.isArray(b)) {
    if (!Array.isArray(a) || !Array.isArray(b) || a.length !== b.length) return false;
    return a.every((v, i) => sameJson(v, b[i]));
  }
  if (typeof a === 'object') {
    const ka = Object.keys(a), kb = Object.keys(b);
    if (ka.length !== kb.length) return false;
    return ka.every((k) => Object.prototype.hasOwnProperty.call(b, k) && sameJson(a[k], b[k]));
  }
  return false;
}

const firstDiffPath = (a, b, path = '$') => {
  if (sameJson(a, b)) return null;
  if (typeof a === 'number' || typeof b === 'number') return path;
  if (Array.isArray(a) || Array.isArray(b)) {
    if (!Array.isArray(a) || !Array.isArray(b)) return path;
    for (let i = 0; i < Math.max(a.length, b.length); i++) {
      const d = firstDiffPath(a[i], b[i], path + '[' + i + ']');
      if (d) return d;
    }
    return path;
  }
  if (a && b && typeof a === 'object' && typeof b === 'object') {
    for (const k of new Set([...Object.keys(a), ...Object.keys(b)])) {
      const d = firstDiffPath(a[k], b[k], path + '.' + k);
      if (d) return d;
    }
  }
  return path;
};

async function jget(path) {
  const r = await fetch(API + path);
  if (!r.ok) throw new Error('HTTP ' + r.status + ' for ' + path);
  return r.json();
}

const strip = (v) => {
  if (Array.isArray(v)) return v.map(strip);
  if (v && typeof v === 'object') {
    const o = {};
    for (const k of Object.keys(v)) {
      if (k === '$icon') continue;
      if (typeof v[k] === 'function') continue;
      o[k] = strip(v[k]);
    }
    return o;
  }
  if (typeof v === 'function') return undefined;
  return v;
};

// ------------------------------------------- TwelveStagesList (RN reference)

const arr = (a) => Array.prototype.slice.call(a);
function rnTwelveStages(stem) {
  const info = Ordinals.stems[stem];
  return {
    stem: {
      name: stem,
      chinese: info.chinese,
      natureName: info.natureName,
      elementName: info.elementName,
    },
    stages: arr(Ordinals.branchOrdering).map((branch) => {
      const stage = Ordinals.twelveStagesData[arr(info.twelveStages).indexOf(branch)];
      const b = Ordinals.branches[branch];
      return {
        branch,
        stage,
        animalName: b.animalName,
        chinese: b.chinese,
        element: b.element,
        season: b.season,
        polarity: b.polarity,
      };
    }),
  };
}

// ------------------------------------------------------- section [1]: tree

let okCompendiumGolden = 0;
let okCompendiumLive = 0;

const rnTree = strip(Compendium);
let cOk = check(sameJson(golden.compendium, rnTree), 'compendium golden tree',
  firstDiffPath(golden.compendium, rnTree), null);
cOk = check(sameJson(golden.titles, Titles), 'compendium golden titles',
  firstDiffPath(golden.titles, Titles), null) && cOk;
if (cOk) okCompendiumGolden++;

const liveCompendium = await jget('/api/library/compendium');
let lOk = check(sameJson(liveCompendium.tree, rnTree), 'compendium live tree',
  firstDiffPath(rnTree, liveCompendium.tree), null);
lOk = check(sameJson(liveCompendium.titles, Titles), 'compendium live titles',
  firstDiffPath(Titles, liveCompendium.titles), null) && lOk;
if (lOk) okCompendiumLive++;

// ----------------------------------------------- section [2]: relationships

const RELATIONSHIP_KEYS = ['stemCombos', 'direction', 'harmony', 'threePenalties',
  'clashes', 'selfPenalties', 'sixCombos', 'harms', 'destructions'];

let okRelGolden = 0;
let okRelLive = 0;

let rOk = check(sameJson(golden.relationships, tables.relationships),
  'relationships golden sections', firstDiffPath(golden.relationships, tables.relationships), null);
if (rOk) okRelGolden++;

const liveAll = await jget('/api/library/relationships');
rOk = check(sameJson(liveAll.sections, tables.relationships),
  'relationships live all', firstDiffPath(tables.relationships, liveAll.sections), null);
for (const key of RELATIONSHIP_KEYS) {
  const rn = tables.relationships.filter((s) => s.key === key);
  const live = await jget('/api/library/relationships?key=' + key);
  rOk = check(sameJson(live.sections, rn), 'relationships live ' + key,
    firstDiffPath(rn, live.sections), null) && rOk;
}
const liveUnknown = await jget('/api/library/relationships?key=bogus');
rOk = check(Array.isArray(liveUnknown.sections) && liveUnknown.sections.length === 0,
  'relationships live unknown key', [], liveUnknown.sections) && rOk;
if (rOk) okRelLive++;

// ---------------------------------------------- section [3]: twelve stages

let okStagesGolden = 0;
let okStagesLive = 0;

let sOk = true;
for (const stem of arr(Ordinals.stemOrdering)) {
  const rn = rnTwelveStages(stem);
  sOk = check(sameJson(golden.twelveStages[stem], rn), 'stages golden ' + stem,
    firstDiffPath(golden.twelveStages[stem], rn), null) && sOk;
}
if (sOk) okStagesGolden++;

sOk = true;
for (const stem of arr(Ordinals.stemOrdering)) {
  const rn = rnTwelveStages(stem);
  const live = await jget('/api/library/twelve-stages?stem=' + stem);
  sOk = check(sameJson(live, rn), 'stages live ' + stem,
    firstDiffPath(rn, live), null) && sOk;
}
if (sOk) okStagesLive++;

// ------------------------------------------- section [4]: reference tables

let okRefGolden = 0;
let okRefLive = 0;

let refOk = check(sameJson(golden.yinYang, tables.yinYang), 'reference golden yinYang',
  firstDiffPath(golden.yinYang, tables.yinYang), null);
refOk = check(sameJson(golden.heavenEarthMan, tables.heavenEarthMan),
  'reference golden heavenEarthMan',
  firstDiffPath(golden.heavenEarthMan, tables.heavenEarthMan), null) && refOk;
if (refOk) okRefGolden++;

const liveYinYang = await jget('/api/library/yin-yang');
refOk = check(sameJson(liveYinYang.words, tables.yinYang), 'reference live yinYang',
  firstDiffPath(tables.yinYang, liveYinYang.words), null);
const liveHem = await jget('/api/library/heaven-earth-man');
refOk = check(sameJson(liveHem, tables.heavenEarthMan), 'reference live heavenEarthMan',
  firstDiffPath(tables.heavenEarthMan, liveHem), null) && refOk;
if (refOk) okRefLive++;

// ---------------------------------------------------- section [5]: tutorials

let okTutGolden = 0;
let okTutLive = 0;

let tOk = check(sameJson(golden.tutorials, tutorials), 'tutorials golden',
  firstDiffPath(golden.tutorials, tutorials), null);
if (tOk) okTutGolden++;

const liveTutorials = await jget('/api/library/tutorials');
tOk = check(sameJson(liveTutorials.tutorials, tutorials), 'tutorials live',
  firstDiffPath(tutorials, liveTutorials.tutorials), null);
if (tOk) okTutLive++;

// ---------------------------------------------------------------- summary

const stats = [
  ['[1] compendium golden fidelity', okCompendiumGolden, 1],
  ['[1] compendium live API (RN vs Java)', okCompendiumLive, 1],
  ['[2] relationships golden fidelity', okRelGolden, 1],
  ['[2] relationships live API (RN vs Java)', okRelLive, 1],
  ['[3] twelve-stages golden fidelity', okStagesGolden, 1],
  ['[3] twelve-stages live API (RN vs Java)', okStagesLive, 1],
  ['[4] reference golden fidelity', okRefGolden, 1],
  ['[4] reference live API (RN vs Java)', okRefLive, 1],
  ['[5] tutorials golden fidelity', okTutGolden, 1],
  ['[5] tutorials live API (RN vs Java)', okTutLive, 1],
];

console.log('');
console.log('=== Library differential verification: RN source vs Java ===');
console.log('    API: ' + API + '  |  golden: ' + GOLDEN);
console.log('');
for (const [name, good, total] of stats) {
  console.log('  ' + name.padEnd(42) + ' ' + good + '/' + total + ' ' + (good === total ? 'ok' : 'MISMATCH'));
}
console.log('');
console.log('    checks: ' + checks + '  failures: ' + failures);
console.log(failures === 0 ? '    RESULT: ALL MATCH' : '    RESULT: MISMATCHES FOUND');
if (failures > 0) console.log('    (first 8 mismatches printed above)');
process.exit(failures === 0 ? 0 : 1);
