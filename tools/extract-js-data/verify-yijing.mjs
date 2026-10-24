// Differential verification for the Yi Jing port.
//
// Executes the *verbatim source text* of the RN calculation functions
// (brace-extracted from components/YiJing/*.js, so there is no hand copy)
// and compares the results three ways:
//
//   RN source (executed in Node)  <->  golden/yijing.json  <->  live Java API
//
// Usage (Java app must be running on :8080):
//   cd tools/extract-js-data
//   node --import ./register-hooks.mjs ./verify-yijing.mjs
//
// Env overrides (defaults shown):
//   BUNDLE_ROOT   = C:/Temp/myTmp/bundle-sources/src/src/lib/cmtools
//   API           = http://localhost:8080
//   GOLDEN        = ../../bazi-spring/src/test/resources/golden/yijing.json
//   LSY_FIXTURES  = ./fixtures-lisheeyee.json
import fs from 'node:fs';
import { pathToFileURL } from 'node:url';
import { DateTime } from 'luxon';

const ROOT = (process.env.BUNDLE_ROOT || 'C:/Temp/myTmp/bundle-sources/src/src/lib/cmtools').replace(/\/$/, '');
const SRC = ROOT.replace(/\/lib\/cmtools$/, '');
const API = (process.env.API || 'http://localhost:8080').replace(/\/$/, '');
const GOLDEN = process.env.GOLDEN || '../../bazi-spring/src/test/resources/golden/yijing.json';
const LSY_FIXTURES = process.env.LSY_FIXTURES || './fixtures-lisheeyee.json';

const cmUrl = (rel) => pathToFileURL(ROOT + '/' + rel).href;
const srcUrl = (rel) => pathToFileURL(SRC + '/' + rel).href;

const Hexes = await import(cmUrl('Hexes.js'));
const Ordinals = await import(cmUrl('Ordinals.js'));
const Tools = await import(cmUrl('Tools.js'));
const strings = await import(srcUrl('lib/utils/strings.js'));
const objects = await import(srcUrl('lib/utils/objects.js'));
const FourPillars = await import(cmUrl('Plotters/FourPillars/index.js'));

// ---------------------------------------------------------------- extraction

/** Brace-match a block starting at the first '{' at/after `marker`. */
function extractBlock(src, marker, { contains = null } = {}) {
  let from = 0;
  for (;;) {
    const at = src.indexOf(marker, from);
    if (at < 0) throw new Error('marker not found: ' + marker);
    const open = src.indexOf('{', at);
    if (open < 0) throw new Error('no { after marker: ' + marker);
    const end = matchBrace(src, open);
    const block = src.slice(at, end + 1);
    if (!contains || block.includes(contains)) return block;
    from = at + marker.length;
  }
}

/** Index of the '}' matching the '{' at `open`, string/comment aware. */
function matchBrace(src, open) {
  let depth = 0;
  let mode = null; // null|'sq'|'dq'|'tpl'|'line'|'block'
  for (let i = open; i < src.length; i++) {
    const c = src[i];
    const n = src[i + 1];
    if (mode === null) {
      if (c === "'") mode = 'sq';
      else if (c === '"') mode = 'dq';
      else if (c === '`') mode = 'tpl';
      else if (c === '/' && n === '/') { mode = 'line'; i++; }
      else if (c === '/' && n === '*') { mode = 'block'; i++; }
      else if (c === '{') depth++;
      else if (c === '}') { depth--; if (depth === 0) return i; }
    } else if (mode === 'line') {
      if (c === '\n') mode = null;
    } else if (mode === 'block') {
      if (c === '*' && n === '/') { mode = null; i++; }
    } else { // string-ish
      if (c === '\\') i++;
      else if ((mode === 'sq' && c === "'") || (mode === 'dq' && c === '"') || (mode === 'tpl' && c === '`')) mode = null;
    }
  }
  throw new Error('unbalanced braces');
}

const hexSrc = fs.readFileSync(SRC + '/components/YiJing/HexagramSelector.js', 'utf8');
const lsySrc = fs.readFileSync(SRC + '/components/YiJing/LiSheeYeeSelector.js', 'utf8');

const dataToInfoSrc = extractBlock(hexSrc, 'const dataToInfo = (data) =>');
const setToLineDataSrc = extractBlock(hexSrc, 'const setToLineData = (set, dm=null) =>');
const onChangedSrc = extractBlock(hexSrc, 'onHexagramChange(data){');
const calculateSrc = extractBlock(hexSrc, 'calculate(){', { contains: 'originalFilled' });
const finishSrc = extractBlock(hexSrc, 'finishInputMethod(data, movingLine){');

const { dataToInfo, setToLineData } = new Function(
  'objects', 'Ordinals', 'Hexes', 'Tools',
  dataToInfoSrc + '\n' + setToLineDataSrc + '\nreturn { dataToInfo, setToLineData };',
)(objects, Ordinals, Hexes, Tools);

const methods = new Function(
  'nullSet',
  'return {' + onChangedSrc + ',\n' + calculateSrc + ',\n' + finishSrc + '\n};',
)([null, null, null, null, null, null]);

/** finishInputMethod -> onHexagramChange -> calculate, executed on a fake `this`. */
function runFinish(bits, movingLine) {
  const self = {
    state: {
      inputForm: null,
      marks: [null, null, null, null, null, null],
      original: [null, null, null, null, null, null],
      changed: null,
      mutual: null,
    },
    setState(patch, cb) { Object.assign(this.state, patch); if (cb) cb(); },
    props: { navigation: { setParams() {} } },
  };
  Object.assign(self, methods);
  self.finishInputMethod(bits, movingLine);
  return self.state;
}

const phtSrc = extractBlock(lsySrc, 'const preHeavenToTrigram = {');
const calcSrc = extractBlock(lsySrc, 'const calcData = () => {');
const preHeavenToTrigram = new Function(
  'Ordinals', phtSrc + '\nreturn preHeavenToTrigram;')(Ordinals);

/** Execute the verbatim calcData() with a fixed chart time. */
function rnLiSheeYee(q, timeIso) {
  let captured;
  const dateTimeProxy = { local: () => DateTime.fromISO(timeIso, { setZone: true }) };
  const calc = new Function(
    'strings', 'FourPillars', 'DateTime', 'inputVal', 'setHexResult', 'preHeavenToTrigram',
    calcSrc + '\nreturn calcData;',
  )(strings, FourPillars, dateTimeProxy, q, (r) => { captured = r; }, preHeavenToTrigram);
  calc();
  return captured; // null when < 2 digits
}

// ---------------------------------------------------------------- helpers

const canon = (v) => (Array.isArray(v) ? v.map(canon)
  : v && typeof v === 'object'
    ? Object.fromEntries(Object.keys(v).sort().map((k) => [k, canon(v[k])]))
    : v);
const same = (a, b) => JSON.stringify(canon(a)) === JSON.stringify(canon(b));

async function jget(path) {
  const r = await fetch(API + path);
  if (!r.ok) throw new Error('HTTP ' + r.status + ' for ' + path);
  return r.json();
}

let checks = 0;
let failures = 0;
let printed = 0;
const stats = [];
function report(name, ok, total) {
  checks += total;
  failures += total - ok;
  stats.push(`  ${name.padEnd(34)} ${ok}/${total} ${ok === total ? 'ok' : 'MISMATCH'}`);
}
function mismatch(label, rn, java) {
  if (printed++ < 8) {
    console.log('  !! ' + label);
    console.log('     RN  : ' + JSON.stringify(rn));
    console.log('     Java: ' + JSON.stringify(java));
  }
}

// ---------------------------------------------------------------- [1] catalog

const catalog = await jget('/api/hexagrams');
let ok = 0;
for (let i = 0; i < Hexes.order.length; i++) {
  if (same(Hexes.order[i], catalog[i])) ok++; else mismatch('catalog[' + i + ']', Hexes.order[i], catalog[i]);
}
report('[1] catalog vs Hexes.order', ok, Hexes.order.length);

// ------------------------------------------- [2..4] line data, trigrams, mutual

const binaries = Hexes.order.map((h) => h.binary);
let okNoDm = 0;
let okDm = 0;
let okTri = 0;
let okMutual = 0;
for (const binary of binaries) {
  const bits = binary.split('').map(Number);
  const rnNo = setToLineData(bits, null);
  const rnDm = setToLineData(bits, 'geng');
  const javaNo = (await jget('/api/hexagrams/' + binary)).lineData;
  const java = await jget('/api/hexagrams/' + binary + '?dayMaster=geng');
  const rnInfo = dataToInfo(bits);

  if (same(rnNo, javaNo)) okNoDm++; else mismatch('lineData(noDm) ' + binary, rnNo, javaNo);
  if (same(rnDm, java.lineData)) okDm++; else mismatch('lineData(dm) ' + binary, rnDm, java.lineData);

  const triOk = rnInfo.topTrigram.pinyin === java.topTrigram.pinyin
    && rnInfo.bottomTrigram.pinyin === java.bottomTrigram.pinyin
    && rnInfo.fullHexagram.number === java.hexagram.number;
  if (triOk) okTri++; else mismatch('trigrams ' + binary, { top: rnInfo.topTrigram.pinyin, bottom: rnInfo.bottomTrigram.pinyin }, java.topTrigram);

  const state = runFinish(bits, 6);
  const mutualBinary = state.mutual.join('');
  if (mutualBinary === java.mutual.binary) okMutual++;
  else mismatch('mutual ' + binary, mutualBinary, java.mutual.binary);
}
report('[2] line data (no day master)', okNoDm, binaries.length);
report('[3] line data (dm=geng)', okDm, binaries.length);
report('[4a] trigrams', okTri, binaries.length);
report('[4b] mutual', okMutual, binaries.length);

// ---------------------------------------------------------------- [5] pillars

let okPillars = 0;
let pillarTotal = 0;
for (const stem of Object.keys(Hexes.pillars)) {
  for (const branch of Object.keys(Hexes.pillars[stem])) {
    pillarTotal++;
    const rnNumbers = Hexes.pillars[stem][branch];
    const rnHexes = rnNumbers.map((n) => Hexes.order[n - 1]);
    const java = await jget(`/api/hexagrams/from-pillar?stem=${stem}&branch=${branch}&horary=false`);
    const javaNumbers = java.numbers;
    const javaHexes = java.hexagrams;
    const good = same(rnNumbers, javaNumbers)
      && rnHexes.length === javaHexes.length
      && rnHexes.every((h, i) => h.number === javaHexes[i].number && h.binary === javaHexes[i].binary);
    if (good) okPillars++; else mismatch(`pillar ${stem}-${branch}`, { rnNumbers, rn: rnHexes.map((h) => h.number) }, { javaNumbers, java: javaHexes.map((h) => h.number) });
  }
}
report('[5] pillar mappings', okPillars, pillarTotal);

// ---------------------------------------------------------------- [6] horary

let okHorary = 0;
let horaryTotal = 0;
for (const stem of Object.keys(Hexes.pillars)) {
  for (const branch of Object.keys(Hexes.pillars[stem])) {
    horaryTotal++;
    const key = stem + '-' + branch;
    const rnBinary = Hexes.horaryTable[key] || null;
    const rnHex = rnBinary ? Hexes.order.find((h) => h.binary === rnBinary) : null;
    const java = await jget(`/api/hexagrams/from-pillar?stem=${stem}&branch=${branch}&horary=true`);
    const good = (java.binary || null) === rnBinary
      && (rnHex ? java.hexagrams.length === 1 && java.hexagrams[0].number === rnHex.number
                : java.hexagrams.length === 0);
    if (good) okHorary++; else mismatch(`horary ${key}`, { rnBinary, rn: rnHex && rnHex.number }, java);
  }
}
report('[6] horary mappings (60 jiazi)', okHorary, horaryTotal);

// ---------------------------------------------------------------- [7] LSY

const fixtures = JSON.parse(fs.readFileSync(LSY_FIXTURES, 'utf8'));
let okLsy = 0;
for (const f of fixtures) {
  const converted = strings.liSheeYeeLetterConversion(f.q);
  const rn = rnLiSheeYee(f.q, f.time);
  const java = await jget('/api/lisheeyee?q=' + encodeURIComponent(f.q) + '&time=' + encodeURIComponent(f.time));

  let good;
  if (!rn) {
    good = converted.length < 2 && converted === java.converted && !('h1' in java);
    if (!good) mismatch(`lsy short "${f.q}"`, { converted }, java);
  } else {
    const dataBits = rn.firstTri.binary + rn.secondTri.binary;
    const state = runFinish(dataBits.split('').map(Number), rn.movingLine);
    const changedBinary = state.changed.join('');
    const changedHex = Hexes.order.find((h) => h.binary === changedBinary);
    const full = Hexes.order.find((h) => h.binary === dataBits);
    good = java.converted === converted
      && java.h1 === rn.h1 && java.h2 === rn.h2 && java.extra === rn.extra
      && java.firstTrigram === rn.firstTri.pinyin
      && java.secondTrigram === rn.secondTri.pinyin
      && java.whichHour === rn.whichHour
      && java.movingLine === rn.movingLine
      && java.binary === dataBits
      && java.number === (full && full.number)
      && java.changedBinary === changedBinary
      && java.changedNumber === (changedHex && changedHex.number);
    if (!good) {
      mismatch(`lsy "${f.q}" @ ${f.time}`, {
        converted, h1: rn.h1, h2: rn.h2, extra: rn.extra,
        first: rn.firstTri.pinyin, second: rn.secondTri.pinyin,
        whichHour: rn.whichHour, movingLine: rn.movingLine,
        binary: dataBits, changedBinary,
      }, java);
    }
  }
  if (good) okLsy++;
}
report('[7] Li Shee Yee endpoints', okLsy, fixtures.length);

// ------------------------------------------------- [8] golden file fidelity

const golden = JSON.parse(fs.readFileSync(GOLDEN, 'utf8'));
let okGolden = 0;
let goldenTotal = 0;

for (const g of golden.lineData) {
  goldenTotal++;
  const bits = g.binary.split('').map(Number);
  const state = runFinish(bits, 6);
  const mut = state.mutual.join('');
  const mutHex = Hexes.order.find((h) => h.binary === mut);
  const good = same(setToLineData(bits, null), g.noDm)
    && same(setToLineData(bits, 'geng'), g.withDm)
    && mut === g.mutualBinary && (mutHex && mutHex.number) === g.mutualNumber
    && dataToInfo(bits).topTrigram.pinyin === g.topTrigram
    && dataToInfo(bits).bottomTrigram.pinyin === g.bottomTrigram;
  if (good) okGolden++; else mismatch('golden lineData #' + g.number, 'RN-source execution differs from golden', g);
}
report('[8] golden lineData vs RN source', okGolden, goldenTotal);

let okGoldenLsy = 0;
for (const g of golden.lisheeyee) {
  goldenTotal++;
  const rn = rnLiSheeYee(g.q, g.time);
  let good;
  if (!rn) {
    good = g.result === null && g.converted === strings.liSheeYeeLetterConversion(g.q);
  } else {
    const dataBits = rn.firstTri.binary + rn.secondTri.binary;
    const state = runFinish(dataBits.split('').map(Number), rn.movingLine);
    good = g.converted === strings.liSheeYeeLetterConversion(g.q)
      && g.h1 === rn.h1 && g.h2 === rn.h2 && g.extra === rn.extra
      && g.firstTrigram === rn.firstTri.pinyin && g.secondTrigram === rn.secondTri.pinyin
      && g.whichHour === rn.whichHour && g.movingLine === rn.movingLine
      && g.binary === dataBits && g.changedBinary === state.changed.join('');
  }
  if (good) okGoldenLsy++; else mismatch('golden lsy "' + g.q + '"', 'RN-source execution differs from golden', g);
}
report('[9] golden Li Shee Yee vs RN source', okGoldenLsy, golden.lisheeyee.length);

// ---------------------------------------------------------------- summary

console.log('');
console.log('=== Yi Jing differential verification: RN source vs Java ===');
console.log('    API: ' + API + '  |  golden: ' + GOLDEN);
console.log('');
for (const line of stats) console.log(line);
console.log('');
console.log('    checks: ' + checks + '  failures: ' + failures);
console.log(failures === 0 ? '    RESULT: ALL MATCH' : '    RESULT: MISMATCHES FOUND');
if (failures > 0) {
  console.log('');
  console.log('    (first 8 mismatches printed above)');
}
process.exit(failures === 0 ? 0 : 1);
