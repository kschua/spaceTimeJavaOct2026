// Differential verification for the Almanac (Tong Shu) port.
//
// Runs the original RN Timing.js + FourPillars.plot in Node (with the app's
// real luxon build) and compares three ways:
//
//   RN source (executed in Node)  <->  golden/almanac.json  <->  live Java API
//
// Usage (Java app must be running on :8080):
//   cd tools/extract-js-data
//   node --import ./register-hooks.mjs ./verify-almanac.mjs
//
// Env overrides (defaults shown):
//   BUNDLE_ROOT = C:/Temp/myTmp/bundle-sources/src/src/lib/cmtools
//   API         = http://localhost:8080
//   GOLDEN      = ../../bazi-spring/src/test/resources/golden/almanac.json
//   FIXTURES    = ./fixtures-almanac.json
import fs from 'node:fs';
import { pathToFileURL } from 'node:url';
import { DateTime } from 'luxon';

const ROOT = (process.env.BUNDLE_ROOT || 'C:/Temp/myTmp/bundle-sources/src/src/lib/cmtools').replace(/\/$/, '');
const API = (process.env.API || 'http://localhost:8080').replace(/\/$/, '');
const GOLDEN = process.env.GOLDEN || '../../bazi-spring/src/test/resources/golden/almanac.json';
const FIXTURES = process.env.FIXTURES || './fixtures-almanac.json';

const FourPillars = await import(pathToFileURL(ROOT + '/Plotters/FourPillars/index.js').href);
const Timing = await import(pathToFileURL(ROOT + '/Timing.js').href);

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

const clean = (v) => JSON.parse(JSON.stringify(v));
const canon = (v) => (Array.isArray(v) ? v.map(canon)
  : v && typeof v === 'object'
    ? Object.fromEntries(Object.keys(v).sort().map((k) => [k, canon(v[k])]))
    : v);
const same = (a, b) => JSON.stringify(canon(a)) === JSON.stringify(canon(b));

/** Every RN field must exist and match in Java (Java-only fields are allowed). */
function subset(a, b, path = '') {
  if (a === null || a === undefined) return b === null || b === undefined;
  if (typeof a === 'number') return typeof b === 'number' && Math.abs(a - b) < 1e-9;
  if (Array.isArray(a)) {
    return Array.isArray(b) && a.length === b.length
      && a.every((v, i) => subset(v, b[i], path + '[' + i + ']'));
  }
  if (typeof a === 'object') {
    if (b === null || typeof b !== 'object' || Array.isArray(b)) return false;
    return Object.keys(a).every((k) => k in b && subset(a[k], b[k], path + '.' + k));
  }
  return a === b;
}

const dt = (s) => DateTime.fromISO(String(s), { setZone: true });
const sameInstant = (a, b) => {
  const x = dt(a);
  const y = dt(b);
  return x.isValid && y.isValid && x.toMillis() === y.toMillis() && x.offset === y.offset;
};

async function jget(path) {
  const r = await fetch(API + path);
  if (!r.ok) throw new Error('HTTP ' + r.status + ' for ' + path);
  return r.json();
}

// -------------------------------------------------------- RN reference run

/** Plot + day info exactly like the RN almanac screen consumes it. */
function rnAlmanac(fixture) {
  const time = DateTime.fromISO(fixture.time, { setZone: true });
  if (!time.isValid) throw new Error('invalid fixture time: ' + fixture.time);
  const chart = FourPillars.plot({
    time,
    lateZi: !!fixture.lateZi,
    gender: fixture.gender || 'male',
    hourUnknown: !!fixture.hourUnknown,
  });
  if (!chart) throw new Error('plot returned null for ' + fixture.time);
  const compact = (p) => (p ? { stem: p.stem.name, branch: p.branch.name } : null);
  return {
    infoTime: chart.info.time.toISO(),
    pillars: {
      year: compact(chart.pillars.year),
      month: compact(chart.pillars.month),
      day: compact(chart.pillars.day),
      hour: compact(chart.pillars.hour),
    },
    dayInfo: clean(Timing.getAllDayInfo({ bazi: chart })),
  };
}

const label = (f) => f.time + (f.lateZi ? ' lateZi' : '') + (f.hourUnknown ? ' hourUnknown' : '');

// ------------------------------- [1] live API vs RN source (golden fixtures)

const fixtures = JSON.parse(fs.readFileSync(FIXTURES, 'utf8'));

let okTime = 0;
let okPillars = 0;
let okDay = 0;
for (const f of fixtures) {
  const rn = rnAlmanac(f);
  const java = await jget('/api/almanac?time=' + encodeURIComponent(f.time)
    + '&lateZi=' + !!f.lateZi + '&hourUnknown=' + !!f.hourUnknown);

  if (check(sameInstant(rn.infoTime, java.time), 'time ' + label(f), rn.infoTime, java.time)) okTime++;
  if (check(subset(rn.pillars, java.pillars), 'pillars ' + label(f), rn.pillars, java.pillars)) okPillars++;
  if (check(subset(rn.dayInfo, java.dayInfo), 'dayInfo ' + label(f), rn.dayInfo, java.dayInfo)) okDay++;
}

// ---------------------------- [2] live API vs RN source (extra edge cases)

// Extra fixtures outside the golden set: hour-unknown charts and the same
// instant expressed with different offsets (the RN code reads the *offset
// calendar date* from info.time for constellation/tongShu/bigDisaster).
const EXTRA = [
  { time: '2019-07-15T12:00:00+08:00', hourUnknown: true },
  { time: '2019-07-16T04:30:00+09:30' },           // same instant as the -05:00 fixture
  { time: '2019-07-16T04:30:00+08:00' },
  { time: '2020-02-14T04:00:00+09:30' },           // local 02-14, UTC still 02-13
  { time: '2020-08-20T00:30:00-05:00' },           // 08-20 is index 1 in its disaster list
];

let okExtra = 0;
for (const f of EXTRA) {
  const rn = rnAlmanac(f);
  const java = await jget('/api/almanac?time=' + encodeURIComponent(f.time)
    + '&lateZi=' + !!f.lateZi + '&hourUnknown=' + !!f.hourUnknown);
  const good = sameInstant(rn.infoTime, java.time)
    && subset(rn.pillars, java.pillars)
    && subset(rn.dayInfo, java.dayInfo);
  check(good, 'extra ' + label(f), { time: rn.infoTime, pillars: rn.pillars, dayInfo: rn.dayInfo },
    { time: java.time, pillars: java.pillars, dayInfo: java.dayInfo });
  if (good) okExtra++;
}

// ------------------------------------------- [3] /reference vs RN exports

let okRef = 0;
{
  const ref = await jget('/api/almanac/reference');
  const pairs = [
    ['officers', Timing.officers],
    ['twelveOfficerDesc', Timing.twelveOfficerDesc],
    ['twentyEightConstellationsDesc', Timing.twentyEightConstellationsDesc],
    ['threeKillingsDesc', Timing.threeKillingsDesc],
  ];
  for (const [key, rn] of pairs) {
    if (check(same(clean(rn), ref[key]), 'reference.' + key, rn, ref[key])) okRef++;
  }
}

// ------------------------------------- [4] golden fidelity (RN vs golden)

const golden = JSON.parse(fs.readFileSync(GOLDEN, 'utf8'));
const byKey = new Map(golden.results.map((r) => [r.time + (r.lateZi ? '|lateZi' : ''), r]));

let okGoldenPillars = 0;
let okGoldenDay = 0;
let okGoldenTime = 0;
for (const f of fixtures) {
  const g = byKey.get(f.time + (f.lateZi ? '|lateZi' : ''));
  if (!check(!!g, 'golden entry ' + label(f), null, null)) continue;
  const rn = rnAlmanac(f);
  if (check(same(rn.pillars, g.pillars), 'golden pillars ' + label(f), rn.pillars, g.pillars)) okGoldenPillars++;
  if (check(same(rn.dayInfo, g.dayInfo), 'golden dayInfo ' + label(f), rn.dayInfo, g.dayInfo)) okGoldenDay++;
  if (check(sameInstant(rn.infoTime, g.infoTime), 'golden infoTime ' + label(f), rn.infoTime, g.infoTime)) okGoldenTime++;
}

// ---------------------------------------------------------------- summary

const stats = [
  ['[1] live /api/almanac time', okTime, fixtures.length],
  ['[1] live /api/almanac pillars', okPillars, fixtures.length],
  ['[1] live /api/almanac dayInfo', okDay, fixtures.length],
  ['[2] live extra offset/hour-unknown', okExtra, EXTRA.length],
  ['[3] /reference vs RN exports', okRef, 4],
  ['[4] golden pillars vs RN source', okGoldenPillars, fixtures.length],
  ['[4] golden dayInfo vs RN source', okGoldenDay, fixtures.length],
  ['[4] golden infoTime vs RN source', okGoldenTime, fixtures.length],
];

console.log('');
console.log('=== Almanac differential verification: RN source vs Java ===');
console.log('    API: ' + API + '  |  golden: ' + GOLDEN);
console.log('');
for (const [name, good, total] of stats) {
  console.log('  ' + name.padEnd(38) + ' ' + good + '/' + total + ' ' + (good === total ? 'ok' : 'MISMATCH'));
}
console.log('');
console.log('    checks: ' + checks + '  failures: ' + failures);
console.log(failures === 0 ? '    RESULT: ALL MATCH' : '    RESULT: MISMATCHES FOUND');
if (failures > 0) console.log('    (first 8 mismatches printed above)');
process.exit(failures === 0 ? 0 : 1);
