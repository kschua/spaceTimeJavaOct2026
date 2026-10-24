// Differential verification for the QMDJ port (phase 4a ju lookups,
// phase 4b core plot, phase 4c useful-god symbols, phase 4d day/hour scan,
// phase 4e display naming conventions).
//
// Runs the original RN Plotters/QiMap/Jus.js + Plotters/QiMap/index.js +
// MysticDoors.usefulGodSymbols + MysticDoors.displayTerminology + the Almanac
// day-scan loop (qimen-days.mjs) in Node (real luxon from the bundle sources)
// and compares three ways:
//
//   RN source (executed in Node)  <->  golden/qimen.json  <->  live Java API
//
// Usage (Java app must be running on :8080):
//   cd tools/extract-js-data
//   node --import ./register-hooks.mjs ./verify-qimen.mjs
//
// Env overrides (defaults shown):
//   BUNDLE_ROOT = C:/Temp/myTmp/bundle-sources/src/src/lib/cmtools
//   API         = http://localhost:8080
//   GOLDEN      = ../../bazi-spring/src/test/resources/golden/qimen.json
import fs from 'node:fs';
import { pathToFileURL } from 'node:url';
import { DateTime } from 'luxon';

const ROOT = (process.env.BUNDLE_ROOT || 'C:/Temp/myTmp/bundle-sources/src/src/lib/cmtools').replace(/\/$/, '');
const API = (process.env.API || 'http://localhost:8080').replace(/\/$/, '');
const GOLDEN = process.env.GOLDEN || '../../bazi-spring/src/test/resources/golden/qimen.json';

const FourPillars = await import(pathToFileURL(ROOT + '/Plotters/FourPillars/index.js').href);
const Jus = await import(pathToFileURL(ROOT + '/Plotters/QiMap/Jus.js').href);
const QiMap = await import(pathToFileURL(ROOT + '/Plotters/QiMap/index.js').href);
const MysticDoors = await import(pathToFileURL(ROOT + '/MysticDoors.js').href);
const Strings = await import(pathToFileURL(ROOT.replace(/\/cmtools$/, '') + '/utils/strings.js').href);
const DayScan = await import('./qimen-days.mjs');

const REFERENCES = ['hour', 'day', 'month', 'year', 'destiny'];

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

function rnJus(fixture) {
  const time = DateTime.fromISO(fixture.time, { setZone: true });
  if (!time.isValid) throw new Error('invalid fixture time: ' + fixture.time);
  const chart = FourPillars.plot({
    time,
    lateZi: !!fixture.lateZi,
    gender: fixture.gender || 'male',
    hourUnknown: !!fixture.hourUnknown,
  });
  if (!chart) throw new Error('plot returned null for ' + fixture.time);

  const zhiRun = {};
  for (const ref of REFERENCES) {
    let r = null;
    try {
      r = Jus.zhiRun(ref, chart);
    } catch (e) {
      r = null;
    }
    zhiRun[ref] = r ? { dun: r[0], ju: r[1] } : null;
  }

  const log = console.log;
  console.log = () => {};
  let chai;
  try {
    chai = Jus.chaiBu(chart);
  } catch (e) {
    chai = null;
  } finally {
    console.log = log;
  }

  return {
    infoTime: chart.info.time.toISO(),
    isPreviousYear: !!chart.info.isPreviousYear,
    zhiRun,
    chaiBu: chai ? { dun: chai[0], ju: chai[1] } : null,
  };
}

const label = (f) => f.time + (f.lateZi ? ' lateZi' : '') + (f.hourUnknown ? ' hourUnknown' : '');

/** RN result {dun,ju}|null vs live API payload {dun,ju}|null (types preserved). */
function sameResult(rn, live) {
  if (rn === null || rn === undefined) return live.dun === null && live.ju === null;
  if (live.dun === null) return false;
  return rn.dun === live.dun && String(rn.ju) === String(live.ju);
}

// ------------------------------------ [1] golden fidelity + live (fixtures)

const golden = JSON.parse(fs.readFileSync(GOLDEN, 'utf8'));
const byKey = new Map(golden.results.map((r) => [r.time + (r.lateZi ? '|lateZi' : '') + (r.hourUnknown ? '|hu' : ''), r]));

let okGolden = 0;
let okLive = 0;
for (const entry of golden.results) {
  const f = { time: entry.time, lateZi: entry.lateZi, hourUnknown: entry.hourUnknown };
  const rn = rnJus(f);
  const g = byKey.get(f.time + (f.lateZi ? '|lateZi' : '') + (f.hourUnknown ? '|hu' : ''));

  // ---- golden fidelity (recompute RN, compare to the committed golden)
  let gOk = check(!!g, 'golden entry ' + label(f), null, null);
  if (gOk) {
    gOk = check(rn.isPreviousYear === g.isPreviousYear, 'golden isPreviousYear ' + label(f), rn.isPreviousYear, g.isPreviousYear);
    for (const ref of REFERENCES) {
      gOk = check(sameResult(rn.zhiRun[ref], g.zhiRun[ref] || { dun: null, ju: null }),
        'golden zhiRun.' + ref + ' ' + label(f), rn.zhiRun[ref], g.zhiRun[ref]) && gOk;
    }
    gOk = check(sameResult(rn.chaiBu, g.chaiBu || { dun: null, ju: null }),
      'golden chaiBu ' + label(f), rn.chaiBu, g.chaiBu) && gOk;
  }
  if (gOk) okGolden++;

  // ---- live API (zhiRun per reference + chaiBu via method=1 hour)
  let lOk = check(sameInstant(rn.infoTime, (await jget('/api/qimen/jus?time=' + encodeURIComponent(f.time))).time),
    'live time ' + label(f), rn.infoTime, null);
  for (const ref of REFERENCES) {
    const live = await jget('/api/qimen/jus?time=' + encodeURIComponent(f.time) + '&reference=' + ref);
    lOk = check(sameResult(rn.zhiRun[ref], live), 'live zhiRun.' + ref + ' ' + label(f), rn.zhiRun[ref], live) && lOk;
  }
  const liveChai = await jget('/api/qimen/jus?time=' + encodeURIComponent(f.time) + '&reference=hour&method=1');
  lOk = check(sameResult(rn.chaiBu, liveChai), 'live chaiBu ' + label(f), rn.chaiBu, liveChai) && lOk;
  if (lOk) okLive++;
}

// ------------------------------- [2] extra live edge cases (not in golden)

const EXTRA = [
  { time: '2024-06-15T23:30:00-08:00', lateZi: true },   // late-zi + negative offset
  { time: '2024-06-15T14:30:00-05:00', hourUnknown: true },
];

let okExtra = 0;
for (const f of EXTRA) {
  const rn = rnJus(f);
  let eOk = true;
  for (const ref of REFERENCES) {
    const live = await jget('/api/qimen/jus?time=' + encodeURIComponent(f.time) + '&reference=' + ref);
    eOk = check(sameResult(rn.zhiRun[ref], live), 'extra zhiRun.' + ref + ' ' + label(f), rn.zhiRun[ref], live) && eOk;
  }
  const liveChai = await jget('/api/qimen/jus?time=' + encodeURIComponent(f.time) + '&reference=hour&method=1');
  eOk = check(sameResult(rn.chaiBu, liveChai), 'extra chaiBu ' + label(f), rn.chaiBu, liveChai) && eOk;
  if (eOk) okExtra++;
}

// ------------------------------------- [3] chart differential (phase 4b)

async function jgetRaw(path) {
  const r = await fetch(API + path);
  let body = null;
  try {
    body = await r.json();
  } catch (e) {
    body = null;
  }
  return { status: r.status, body };
}

function runRnChart(f) {
  const time = DateTime.fromISO(f.time, { setZone: true });
  if (!time.isValid) throw new Error('invalid fixture time: ' + f.time);
  const bazi = FourPillars.plot({
    time,
    lateZi: !!f.lateZi,
    gender: f.gender || 'male',
    hourUnknown: !!f.hourUnknown,
  });
  if (!bazi) throw new Error('bazi plot returned null for ' + f.time);

  const log = console.log;
  const err = console.error;
  console.log = () => {};
  console.error = () => {};
  let chart = null;
  let error = null;
  try {
    chart = QiMap.plot({
      bazi,
      reference: f.reference || 'hour',
      method: f.method || 0,
      starPath: f.starPath || 0,
      shiftLateZiStream: f.shiftLateZiStream !== undefined ? !!f.shiftLateZiStream : true,
      houseInput: f.houseInput || null,
      appendDestiny: !!f.destiny,
      appendMeta: f.meta !== undefined ? !!f.meta : true,
      appendStyle: f.meta !== undefined ? !!f.meta : true,
    });
  } catch (e) {
    error = String((e && e.message) || e);
  } finally {
    console.log = log;
    console.error = err;
  }

  const normalized = chart === undefined || chart === null ? null : JSON.parse(JSON.stringify(chart));
  return { chart: normalized, error, bazi, infoTime: bazi.info.time.toISO() };
}

/** Drops $icon asset references and functions (Java never emits those). */
const strip = (v) => {
  if (Array.isArray(v)) return v.map(strip);
  if (v && typeof v === 'object') {
    const o = {};
    for (const k of Object.keys(v)) {
      if (k === '$icon') continue;
      const val = strip(v[k]);
      if (typeof val !== 'function') o[k] = val;
    }
    return o;
  }
  return v;
};

function chartQuery(f) {
  let q = '/api/qimen/chart?time=' + encodeURIComponent(f.time)
    + '&reference=' + (f.reference || 'hour')
    + '&method=' + (f.method || 0)
    + '&starPath=' + (f.starPath || 0)
    + '&shiftLateZiStream=' + (f.shiftLateZiStream !== undefined ? !!f.shiftLateZiStream : true)
    + '&destiny=' + !!f.destiny
    + '&meta=' + (f.meta !== undefined ? !!f.meta : true);
  if (f.lateZi) q += '&lateZi=true';
  if (f.hourUnknown) q += '&hourUnknown=true';
  if (f.houseInput) {
    q += '&houseYear=' + f.houseInput.houseYear
      + '&houseDegree=' + f.houseInput.houseDegree
      + '&doorDegree=' + f.houseInput.doorDegree;
  }
  return q;
}

function digestOf(b) {
  const pd = (p) => (p ? { stem: p.stem.name, branch: p.branch.name } : null);
  return {
    infoTime: b.info.time.toISO(),
    isPreviousYear: !!b.info.isPreviousYear,
    solarTermNum: b.info.solarTerm.thisTerm.data.num,
    pillars: {
      year: pd(b.pillars.year),
      month: pd(b.pillars.month),
      day: pd(b.pillars.day),
      hour: pd(b.pillars.hour),
    },
  };
}

const clabel = (f) => f.time + ' ' + (f.reference || 'hour')
  + (f.destiny ? ' destiny' : '') + (f.meta === false ? ' meta=false' : '')
  + (f.houseInput ? ' house' : '') + (f.hourUnknown ? ' hourUnknown' : '');

// Documented divergences inside the echoed bazi payload (see MIGRATION_PLAN):
// meta.counts stem/god keys are NaN-ish in RN (Java counts correctly),
// stem/branch interactions depend on DateTime.local().
const CHART_SKIP = [
  /^\.bazi\.meta\.counts\.(stems|stemsCore|gods|godsCore)(\.|$)/,
  /^\.bazi\.pillars\.[a-z]+\.(stem|branch)\.interactions(\.|$)/,
];

const ISO_RE = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?([+-]\d{2}:\d{2}|Z)?$/;

function subsetDiffs(rn, live, path, out) {
  if (CHART_SKIP.some((re) => re.test(path))) return;
  if (rn === undefined) return;
  if (rn === null) {
    if (live !== null) out.push(path + ': rn=null java=' + JSON.stringify(live));
    return;
  }
  if (Array.isArray(rn)) {
    if (!Array.isArray(live)) {
      out.push(path + ': rn=array java=' + typeof live);
      return;
    }
    if (rn.length !== live.length) {
      out.push(path + ': length rn=' + rn.length + ' java=' + live.length);
      return;
    }
    rn.forEach((v, i) => subsetDiffs(v, live[i], path + '[' + i + ']', out));
    return;
  }
  if (typeof rn === 'object') {
    if (!live || typeof live !== 'object' || Array.isArray(live)) {
      out.push(path + ': rn=object java=' + typeof live);
      return;
    }
    for (const k of Object.keys(rn)) subsetDiffs(rn[k], live[k], path + '.' + k, out);
    return;
  }
  if (typeof rn === 'number') {
    if (typeof live !== 'number' || Math.abs(rn - live) > 1e-9) out.push(path + ': rn=' + rn + ' java=' + live);
    return;
  }
  if (typeof rn === 'string') {
    if (typeof live !== 'string') {
      out.push(path + ': rn=string java=' + typeof live);
      return;
    }
    if (ISO_RE.test(rn) && ISO_RE.test(live)) {
      if (!sameInstant(rn, live)) out.push(path + ': rn=' + rn + ' java=' + live);
      return;
    }
    if (rn !== live) out.push(path + ': rn=' + JSON.stringify(rn).slice(0, 90) + ' java=' + JSON.stringify(live).slice(0, 90));
    return;
  }
  if (rn !== live) out.push(path + ': rn=' + rn + ' java=' + live);
}

const chartEntries = golden.charts || [];
let okChartGolden = 0;
let okChartLive = 0;
for (const entry of chartEntries) {
  const rn = runRnChart(entry);

  // ---- golden fidelity (recompute RN, compare to the committed golden)
  let gOk = check((entry.error || null) === (rn.error || null),
    'chart golden error ' + clabel(entry), rn.error, entry.error);
  if (entry.chart) {
    const normalized = JSON.parse(JSON.stringify(rn.chart));
    normalized.bazi = digestOf(rn.bazi);
    gOk = check(JSON.stringify(normalized) === JSON.stringify(entry.chart),
      'chart golden payload ' + clabel(entry), null, null) && gOk;
  } else {
    gOk = check(rn.chart === null, 'chart golden null ' + clabel(entry), rn.chart, null) && gOk;
  }
  if (gOk) okChartGolden++;

  // ---- live API
  const got = await jgetRaw(chartQuery(entry));
  let lOk = check(got.status === 200, 'chart live status ' + clabel(entry), 200, got.status);
  if (lOk && entry.expect === 'null') {
    lOk = check(got.body && got.body.chart === null, 'chart live null ' + clabel(entry),
      null, got.body && got.body.chart) && lOk;
  } else if (lOk) {
    lOk = check(got.body && got.body.chart !== null && got.body.chart !== undefined,
      'chart live chart present ' + clabel(entry), 'chart', null) && lOk;
    if (lOk) {
      const diffs = [];
      subsetDiffs(strip(rn.chart), got.body.chart, '', diffs);
      lOk = check(diffs.length === 0, 'chart live payload ' + clabel(entry), diffs.slice(0, 6), null) && lOk;
    }
  }
  if (lOk) {
    lOk = check(sameInstant(rn.infoTime, got.body && got.body.time),
      'chart live time ' + clabel(entry), rn.infoTime, got.body && got.body.time) && lOk;
  }
  if (lOk) okChartLive++;
}

// extra chart cases not committed to the golden (incl. RN crashes -> 400)
const CHART_EXTRA = [
  { time: '1999-12-31T23:30:00+08:00', reference: 'hour' },
  { time: '2024-12-21T12:00:00+08:00', reference: 'month', destiny: true },
  { time: '1990-05-15T14:30:00+08:00', reference: 'day', method: 1 },
  { time: '1990-05-15T14:30:00+08:00', reference: 'year', destiny: true, meta: false },
  { time: '1990-05-15T14:30:00+08:00', reference: 'hour', starPath: 1, shiftLateZiStream: false },
  { time: '1990-05-15T14:30:00+08:00', reference: 'house',
    houseInput: { houseYear: 2024, houseDegree: 60.5, doorDegree: 7.55 }, expect: '400' },
  { time: '1990-05-15T14:30:00+08:00', reference: 'house',
    houseInput: { houseYear: 1700, houseDegree: 60.5, doorDegree: 210.5 }, expect: '400' },
];

let okChartExtra = 0;
for (const f of CHART_EXTRA) {
  const rn = runRnChart(f);
  const got = await jgetRaw(chartQuery(f));
  let eOk = true;
  if (f.expect === '400') {
    // RN crashes (TypeError); agreed divergence: 400 with an error body
    eOk = check(!!rn.error, 'extra chart rn crash ' + clabel(f), rn.error, null) && eOk;
    eOk = check(got.status === 400, 'extra chart status ' + clabel(f), 400, got.status) && eOk;
    eOk = check(got.body && typeof got.body.error === 'string',
      'extra chart error body ' + clabel(f), 'error', got.body) && eOk;
  } else {
    eOk = check(got.status === 200, 'extra chart status ' + clabel(f), 200, got.status) && eOk;
    eOk = check((rn.error || null) === null, 'extra chart rn ok ' + clabel(f), rn.error, null) && eOk;
    if (eOk && !rn.chart) {
      eOk = check(got.body && got.body.chart === null, 'extra chart null ' + clabel(f), null, got.body) && eOk;
    } else if (eOk) {
      const diffs = [];
      subsetDiffs(strip(rn.chart), got.body.chart, '', diffs);
      eOk = check(diffs.length === 0, 'extra chart payload ' + clabel(f), diffs.slice(0, 6), null) && eOk;
    }
  }
  if (eOk) okChartExtra++;
}

// ---------------------------------------------------------- useful gods (4c)

// The RN screens read MysticDoors.usefulGodSymbols directly: the list sorts
// the raw topic keys and deCamel's them for display; details map each
// [symbol, meaning] row to {title, body}. No chart math is involved.
const RN_SYMBOLS = MysticDoors.usefulGodSymbols;
const rnSymbolTopics = Object.keys(RN_SYMBOLS).sort();
const rnSymbolList = rnSymbolTopics.map((topic) => ({
  topic,
  title: Strings.deCamel(topic),
}));

let okSymbolsGolden = 0;
let sGolden = true;
sGolden = check(golden.usefulGods && JSON.stringify(golden.usefulGods.topics) === JSON.stringify(rnSymbolTopics),
  'symbols golden topics', rnSymbolTopics, golden.usefulGods && golden.usefulGods.topics) && sGolden;
sGolden = check(golden.usefulGods && JSON.stringify(golden.usefulGods.data) === JSON.stringify(RN_SYMBOLS),
  'symbols golden table', RN_SYMBOLS, golden.usefulGods && golden.usefulGods.data) && sGolden;
for (const topic of rnSymbolTopics) {
  sGolden = check(golden.usefulGods && golden.usefulGods.titles[topic] === Strings.deCamel(topic),
    'symbols golden title ' + topic, Strings.deCamel(topic),
    golden.usefulGods && golden.usefulGods.titles[topic]) && sGolden;
}
if (sGolden) okSymbolsGolden = 1;

let okSymbolsLive = 0;
let sLive = true;
const liveList = await jget('/api/qimen/useful-gods');
sLive = check(liveList && JSON.stringify(liveList.topics) === JSON.stringify(rnSymbolList),
  'symbols live list', rnSymbolList, liveList && liveList.topics) && sLive;

for (const topic of rnSymbolTopics) {
  const rows = RN_SYMBOLS[topic].map(([title, body]) => ({ title, body }));
  const live = await jget('/api/qimen/useful-gods/' + encodeURIComponent(topic));
  sLive = check(live.topic === topic && live.title === Strings.deCamel(topic),
    'symbols live title ' + topic, { topic, title: Strings.deCamel(topic) },
    { topic: live.topic, title: live.title }) && sLive;
  sLive = check(JSON.stringify(live.symbols) === JSON.stringify(rows),
    'symbols live rows ' + topic, rows, live.symbols) && sLive;
}

// unknown topic: RN screens would throw indexing undefined; endpoint answers 400
const bogus = await jgetRaw('/api/qimen/useful-gods/bogus');
sLive = check(bogus.status === 400 && bogus.body && typeof bogus.body.error === 'string',
  'symbols live unknown topic', 400, bogus.status) && sLive;
if (sLive) okSymbolsLive = 1;

// ---------------------------------------------------------- day scans (4d)

const dayScanFixtures = golden.dayScans || [];
let okDayGolden = 0;
let okDayLive = 0;

// live query mirroring a fixture (repeated list params for the filters)
function dayQuery(f) {
  const q = new URLSearchParams();
  q.set('from', f.from);
  q.set('to', f.to);
  q.set('lateZi', String(!!f.lateZi));
  q.set('method', String(f.method || 0));
  q.set('starPath', String(f.starPath || 0));
  q.set('shiftLateZiStream', String(f.shiftLateZiStream !== false));
  for (const d of f.lookups.doors) q.append('doors', d);
  for (const d of f.lookups.stars) q.append('stars', d);
  for (const d of f.lookups.spirits) q.append('spirits', d);
  for (const d of f.lookups.structures) q.append('structures', d);
  q.set('maxNegativeStructures', String(f.lookups.maxNegativeStructures));
  if (f.birthTime) q.set('birthTime', f.birthTime);
  return '/api/qimen/days?' + q.toString();
}

for (const f of dayScanFixtures) {
  const label = f.from + '..' + f.to;
  const rn = DayScan.scanDays({
    from: f.from,
    to: f.to,
    lateZi: f.lateZi,
    method: f.method,
    starPath: f.starPath,
    shiftLateZiStream: f.shiftLateZiStream,
    lookups: f.lookups,
    birthTime: f.birthTime,
  });

  // ---- golden fidelity (recompute RN, compare to the committed golden)
  let gOk = check(JSON.stringify(rn.lookups) === JSON.stringify(f.lookups),
    'days golden lookups ' + label, f.lookups, rn.lookups);
  gOk = check(JSON.stringify(rn.days) === JSON.stringify(f.days),
    'days golden payload ' + label + ' (' + rn.days.length + ' days)', null, null) && gOk;
  if (gOk) okDayGolden++;

  // ---- live API (RN result vs Java payload)
  let lOk = true;
  const live = await jget(dayQuery(f));
  lOk = check(live.from === rn.from && live.to === rn.to,
    'days live range ' + label, { from: rn.from, to: rn.to },
    { from: live.from, to: live.to }) && lOk;
  lOk = check(!!live.lateZi === !!f.lateZi && (live.method || 0) === (f.method || 0)
      && (live.starPath || 0) === (f.starPath || 0)
      && live.shiftLateZiStream === (f.shiftLateZiStream !== false),
    'days live options ' + label,
    { lateZi: !!f.lateZi, method: f.method || 0, starPath: f.starPath || 0,
      shiftLateZiStream: f.shiftLateZiStream !== false },
    { lateZi: live.lateZi, method: live.method, starPath: live.starPath,
      shiftLateZiStream: live.shiftLateZiStream }) && lOk;
  lOk = check(JSON.stringify(live.lookups) === JSON.stringify(rn.lookups),
    'days live lookups ' + label, rn.lookups, live.lookups) && lOk;
  lOk = check(!!live.victory === !!f.birthTime,
    'days live victory flag ' + label, !!f.birthTime, live.victory) && lOk;
  lOk = check(Array.isArray(live.days) && live.days.length === rn.days.length,
    'days live count ' + label, rn.days.length, live.days && live.days.length) && lOk;

  if (Array.isArray(live.days)) {
    for (let i = 0; i < rn.days.length && i < live.days.length; i++) {
      const e = rn.days[i];
      const a = live.days[i];
      const dayLabel = label + ' ' + e.date;
      lOk = check(a.date === e.date, 'days live date ' + dayLabel, e.date, a.date) && lOk;

      if (!e.victoryDay) {
        lOk = check(a.victoryDay === null || a.victoryDay === undefined,
          'days live victory null ' + dayLabel, null, a.victoryDay) && lOk;
      } else {
        lOk = check(a.victoryDay && a.victoryDay.name === e.victoryDay.name
            && a.victoryDay.palace === e.victoryDay.palace
            && typeof a.victoryDay.direction === 'string',
          'days live victory ' + dayLabel, e.victoryDay, a.victoryDay) && lOk;
      }

      const eh = e.goodHours || [];
      const ah = a.goodHours || [];
      lOk = check(ah.length === eh.length, 'days live good hours ' + dayLabel,
        eh.length, ah.length) && lOk;
      for (let j = 0; j < eh.length && j < ah.length; j++) {
        const he = eh[j];
        const ha = ah[j];
        const hl = dayLabel + ' good[' + j + ']';
        lOk = check(sameInstant(he.time, ha.time), 'days live time ' + hl, he.time, ha.time) && lOk;
        lOk = check(ha.palace === he.palace && ha.numPositiveStructures === he.numPositiveStructures,
          'days live palace ' + hl, { palace: he.palace, pos: he.numPositiveStructures },
          { palace: ha.palace, pos: ha.numPositiveStructures }) && lOk;
        lOk = check(ha.structure && ha.structure.name === he.structure.name
            && ha.structure.rating === he.structure.rating
            && ha.structure.matchedRule === he.structure.matchedRule,
          'days live structure ' + hl, he.structure, ha.structure) && lOk;
      }
    }
  }
  if (lOk) okDayLive++;
}

// ------------------------------------------------------ terminology (4e)

// The destiny-summary / palace label naming conventions come straight from
// MysticDoors.displayTerminology; both checks compare the full table.
let okTermGolden = 0;
let okTermLive = 0;

function terminologyCheck(label, actual) {
  let ok = check(JSON.stringify(actual) === JSON.stringify(MysticDoors.displayTerminology),
    label, MysticDoors.displayTerminology, actual);
  for (const conv of ['0', '1', '2']) {
    ok = check(actual && actual[conv] && Object.keys(actual[conv]).length === 3,
      label + ' conv ' + conv, 3, actual && actual[conv] && Object.keys(actual[conv]).length) && ok;
  }
  return ok;
}

if (terminologyCheck('terminology golden table', golden.terminology)) okTermGolden++;
if (terminologyCheck('terminology live table', await jget('/api/qimen/terminology'))) okTermLive++;

// ---------------------------------------------------------------- summary

const stats = [
  ['[1] golden fidelity (RN vs golden)', okGolden, golden.results.length],
  ['[1] live API (zhiRun + chaiBu)', okLive, golden.results.length],
  ['[2] extra live edge cases', okExtra, EXTRA.length],
  ['[3] chart golden fidelity (RN vs golden)', okChartGolden, chartEntries.length],
  ['[3] chart live API (RN vs Java)', okChartLive, chartEntries.length],
  ['[3] extra chart cases', okChartExtra, CHART_EXTRA.length],
  ['[4] symbols golden fidelity', okSymbolsGolden, 1],
  ['[4] symbols live API (RN vs Java)', okSymbolsLive, 1],
  ['[5] day-scan golden fidelity', okDayGolden, dayScanFixtures.length],
  ['[5] day-scan live API (RN vs Java)', okDayLive, dayScanFixtures.length],
  ['[6] terminology golden fidelity', okTermGolden, 1],
  ['[6] terminology live API (RN vs Java)', okTermLive, 1],
];

console.log('');
console.log('=== QMDJ differential verification: RN source vs Java ===');
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
