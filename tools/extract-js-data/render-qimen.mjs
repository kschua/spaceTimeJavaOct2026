// Renders QMDJ (Qi Men Dun Jia) golden outputs from the original RN modules.
// Plotters/QiMap/Jus.js is imported and executed directly (no transcription):
// zhiRun (crawled ju tables) and chaiBu (solar-term + three-yuan ju).
//
// Usage:
//   cd tools/extract-js-data
//   node --import ./register-hooks.mjs ./render-qimen.mjs
//
// Env overrides (defaults shown):
//   BUNDLE_ROOT = C:/Temp/myTmp/bundle-sources/src/src/lib/cmtools
//   OUT         = ../../bazi-spring/src/test/resources/golden/qimen.json
import fs from 'node:fs';
import { pathToFileURL } from 'node:url';
import { DateTime } from 'luxon';

const ROOT = (process.env.BUNDLE_ROOT || 'C:/Temp/myTmp/bundle-sources/src/src/lib/cmtools').replace(/\/$/, '');
const OUT = process.env.OUT || '../../bazi-spring/src/test/resources/golden/qimen.json';

const FourPillars = await import(pathToFileURL(ROOT + '/Plotters/FourPillars/index.js').href);
const Jus = await import(pathToFileURL(ROOT + '/Plotters/QiMap/Jus.js').href);
const QiMap = await import(pathToFileURL(ROOT + '/Plotters/QiMap/index.js').href);
const MysticDoors = await import(pathToFileURL(ROOT + '/MysticDoors.js').href);
const Strings = await import(pathToFileURL(ROOT.replace(/\/cmtools$/, '') + '/utils/strings.js').href);
const DayScan = await import('./qimen-days.mjs');

// Fixture matrix: both duns, term boundaries (li chun drives isPreviousYear),
// solstices, table edges (1900 / 2050 hour / 2099 / out of range), the
// "0-undefined" crawl gap, late-zi, hour-unknown and non-+08 offsets.
const FIXTURES = [
  { time: '1900-01-15T12:00:00+08:00' },
  { time: '1900-02-05T12:00:00+08:00' },
  { time: '1901-06-15T12:00:00+08:00' },
  { time: '1950-12-31T23:30:00+08:00', lateZi: true },
  { time: '1990-05-15T14:30:00+08:00' },
  { time: '1990-05-15T14:30:00+08:00', hourUnknown: true },
  { time: '1999-12-31T23:30:00+08:00', lateZi: true },
  { time: '2000-02-04T08:00:00+08:00' },
  { time: '2000-02-05T08:00:00+08:00' },
  { time: '2019-06-21T12:00:00+08:00' },
  { time: '2021-12-21T12:00:00+08:00' }, // single "0-undefined" jusDay entry
  { time: '2023-06-21T23:30:00+08:00', lateZi: true },
  { time: '2024-01-15T12:00:00+08:00' }, // isPreviousYear (before li chun)
  { time: '2024-01-15T12:00:00-05:00' },
  { time: '2024-02-03T12:00:00+08:00' },
  { time: '2024-02-05T12:00:00+08:00' },
  { time: '2024-06-15T14:30:00-05:00' },
  { time: '2024-06-15T14:30:00+05:30' },
  { time: '2024-08-01T00:30:00+11:00' },
  { time: '2024-12-21T12:00:00+08:00' },
  { time: '2050-12-31T12:00:00+08:00' }, // last hourJus year
  { time: '2051-01-01T12:00:00+08:00' }, // hourJus ends -> zhiRun null for hour/destiny
  { time: '2077-10-15T12:00:00+08:00' }, // circa-2077 "0-undefined" block + no hour table
  { time: '2099-12-31T12:00:00+08:00' },
  { time: '2100-06-01T12:00:00+08:00' }, // past all tables -> null
  { time: '1899-06-01T12:00:00+08:00' }, // before all tables -> null
];

const REFERENCES = ['hour', 'day', 'month', 'year', 'destiny'];

function run(fixture) {
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

  // chaiBu logs the dun to the console in the RN source; silence it here.
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
    time: fixture.time,
    lateZi: !!fixture.lateZi,
    hourUnknown: !!fixture.hourUnknown,
    infoTime: chart.info.time.toISO(),
    isPreviousYear: !!chart.info.isPreviousYear,
    zhiRun,
    chaiBu: chai ? { dun: chai[0], ju: chai[1] } : null,
  };
}

// ---------------------------------------------------------------- charts (4b)

// Chart fixture matrix: all references, both duns, methods 0/1, starPath 0/1,
// late-zi on/off, house input (incl. the non-house-reference override quirk),
// destiny/meta switches, non-+08 offsets, plus the RN null / crash cases:
//   - house reference without inputs -> plot returns null
//   - table misses (2051 hourJus end, 2100, pre-1900) -> undefined
//   - "0-undefined" ju -> RN throws in the earth plate (NaN index)
//   - hour-unknown hour reference -> RN throws on the null hour pillar
//   - hour-unknown other references -> only succeed with meta:false (meta
//     reads pillars.hour.stem.name and throws)
// `expect` records what the Java port does for the entry (`chart` or `null`);
// for RN crashes it is the documented null-safety outcome.
const CHARTS = [
  { time: '1990-05-15T14:30:00+08:00', reference: 'hour' },
  { time: '1990-05-15T14:30:00+08:00', reference: 'day' },
  { time: '1990-05-15T14:30:00+08:00', reference: 'month' },
  { time: '1990-05-15T14:30:00+08:00', reference: 'year' },
  { time: '1990-05-15T14:30:00+08:00', reference: 'hour', method: 1 },
  { time: '1990-05-15T14:30:00+08:00', reference: 'hour', starPath: 1 },
  { time: '1990-05-15T14:30:00+08:00', reference: 'hour', meta: false },
  { time: '1990-05-15T14:30:00+08:00', reference: 'hour', destiny: true, meta: false },
  { time: '1990-05-15T14:30:00+08:00', reference: 'month', destiny: true },
  { time: '2023-06-21T23:30:00+08:00', lateZi: true, reference: 'hour' },
  { time: '2023-06-21T23:30:00+08:00', lateZi: true, reference: 'hour', shiftLateZiStream: false },
  { time: '2023-06-21T23:30:00+08:00', lateZi: true, reference: 'hour', starPath: 1 },
  { time: '2000-02-04T08:00:00+08:00', reference: 'hour' },
  { time: '2000-02-04T08:00:00+08:00', reference: 'day' },
  { time: '2024-02-05T12:00:00+08:00', reference: 'hour' },
  { time: '2024-06-15T14:30:00-05:00', reference: 'hour', starPath: 1 },
  { time: '2024-06-15T14:30:00+05:30', reference: 'hour' },
  { time: '1990-05-15T14:30:00+08:00', reference: 'house',
    houseInput: { houseYear: 2024, houseDegree: 60.5, doorDegree: 210.5 } },
  { time: '1990-05-15T14:30:00+08:00', reference: 'house',
    houseInput: { houseYear: 1984, houseDegree: 120.5, doorDegree: 300.5 } },
  { time: '1990-05-15T14:30:00+08:00', reference: 'day',
    houseInput: { houseYear: 2024, houseDegree: 60.5, doorDegree: 210.5 } },
  { time: '1990-05-15T14:30:00+08:00', reference: 'house', expect: 'null' },
  { time: '2051-01-01T12:00:00+08:00', reference: 'hour', expect: 'null' },
  { time: '2100-06-01T12:00:00+08:00', reference: 'hour', expect: 'null' },
  { time: '2021-12-21T12:00:00+08:00', reference: 'day', expect: 'null' },
  { time: '1990-05-15T14:30:00+08:00', hourUnknown: true, reference: 'hour', expect: 'null' },
  { time: '1990-05-15T14:30:00+08:00', hourUnknown: true, reference: 'day', meta: false },
];

function pillarDigest(p) {
  return p ? { stem: p.stem.name, branch: p.branch.name } : null;
}

function baziDigest(b) {
  return {
    infoTime: b.info.time.toISO(),
    isPreviousYear: !!b.info.isPreviousYear,
    solarTermNum: b.info.solarTerm.thisTerm.data.num,
    pillars: {
      year: pillarDigest(b.pillars.year),
      month: pillarDigest(b.pillars.month),
      day: pillarDigest(b.pillars.day),
      hour: pillarDigest(b.pillars.hour),
    },
  };
}

function runChart(f) {
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
    error = String(e && e.message || e);
  } finally {
    console.log = log;
    console.error = err;
  }

  const entry = {
    time: f.time,
    lateZi: !!f.lateZi,
    hourUnknown: !!f.hourUnknown,
    reference: f.reference || 'hour',
    method: f.method || 0,
    starPath: f.starPath || 0,
    shiftLateZiStream: f.shiftLateZiStream !== undefined ? !!f.shiftLateZiStream : true,
    houseInput: f.houseInput || null,
    destiny: !!f.destiny,
    meta: f.meta !== undefined ? !!f.meta : true,
    expect: f.expect || 'chart',
    error,
    chart: null,
  };
  if (chart !== undefined && chart !== null) {
    const normalized = JSON.parse(JSON.stringify(chart));
    normalized.bazi = baziDigest(bazi);
    entry.chart = normalized;
  } else if (!error) {
    entry.expect = 'null'; // plot returned null/undefined without throwing
  }
  return entry;
}

const charts = CHARTS.map(runChart);

// ------------------------------------------------------------- useful gods (4c)

// QiMenUsefulGodList sorts the raw topic keys and displays strings.deCamel;
// QiMenUsefulGodDetails maps each [symbol, meaning] row to {title, body}.
const usefulGods = {
  topics: Object.keys(MysticDoors.usefulGodSymbols).sort(),
  titles: Object.fromEntries(Object.keys(MysticDoors.usefulGodSymbols)
    .map((key) => [key, Strings.deCamel(key)])),
  data: MysticDoors.usefulGodSymbols,
};

// ------------------------------------------------------------- day scan (4d)

const customLookups = DayScan.defaultLookups();
customLookups.doors = ['life', 'open'];
customLookups.stars = ['heart', 'assistant'];
customLookups.spirits = ['chief', 'heaven'];
customLookups.structures = customLookups.structures.slice(0, 5);
customLookups.maxNegativeStructures = 2;

const DAY_SCANS = [
  {
    from: '2024-01-01T00:00:00+08:00',
    to: '2024-01-03T23:59:00+08:00',
    lookups: DayScan.defaultLookups(),
  },
  {
    from: '2024-06-15T00:00:00+08:00',
    to: '2024-06-16T23:59:00+08:00',
    starPath: 1,
    shiftLateZiStream: false,
    lookups: customLookups,
  },
  {
    from: '1990-05-17T00:00:00+08:00',
    to: '1990-05-18T23:59:00+08:00',
    birthTime: '1990-05-15T14:30:00+08:00',
    lookups: DayScan.defaultLookups(),
  },
  {
    from: '2024-12-21T00:00:00+08:00',
    to: '2024-12-22T23:59:00+08:00',
    lateZi: true,
    lookups: DayScan.defaultLookups(),
  },
];

const dayScans = DAY_SCANS.map((f) => {
  const result = DayScan.scanDays(f);
  return {
    from: f.from,
    to: f.to,
    lateZi: !!f.lateZi,
    method: f.method || 0,
    starPath: f.starPath || 0,
    shiftLateZiStream: f.shiftLateZiStream !== false,
    birthTime: f.birthTime || null,
    lookups: result.lookups,
    days: result.days,
  };
});

const results = FIXTURES.map(run);
const out = {
  meta: {
    source: ROOT,
    generated: new Date().toISOString(),
    fixtures: results.length,
    charts: charts.length,
    usefulGodTopics: usefulGods.topics.length,
    dayScanFixtures: dayScans.length,
    namingConventions: Object.keys(MysticDoors.displayTerminology).length,
    description: 'QMDJ ju lookups + core plot rendered by the original '
      + 'Plotters/QiMap/Jus.js + Plotters/QiMap/index.js via the real luxon build; '
      + 'useful-god symbols from MysticDoors.usefulGodSymbols; day/hour scans '
      + 'from the RN Almanac loop + QiMenAssist; display naming conventions '
      + 'from MysticDoors.displayTerminology',
  },
  results,
  charts,
  usefulGods,
  dayScans,
  terminology: MysticDoors.displayTerminology,
};

fs.writeFileSync(OUT, JSON.stringify(out, null, 1));
console.log('wrote', OUT, fs.statSync(OUT).size, 'bytes');

const named = {};
for (const r of results) {
  for (const ref of REFERENCES) {
    const v = r.zhiRun[ref];
    const key = ref + (v ? ':' + v.dun : ':null');
    named[key] = (named[key] || 0) + 1;
  }
}
console.log('fixtures:', results.length,
  '| zhiRun nulls:', results.reduce((a, r) => a + REFERENCES.filter((ref) => !r.zhiRun[ref]).length, 0),
  '| chaiBu nulls:', results.filter((r) => !r.chaiBu).length);
console.log('zhiRun mix:', Object.entries(named).sort().map(([k, v]) => k + '=' + v).join(' '));
console.log('charts:', charts.length,
  '| with chart:', charts.filter((c) => c.chart).length,
  '| expect null:', charts.filter((c) => c.expect === 'null').length,
  '| rn errors:', charts.filter((c) => c.error).length);
for (const c of charts.filter((c) => c.error)) {
  console.log('  rn error [' + c.reference + ' ' + c.time + '] expect=' + c.expect + ':', c.error.slice(0, 80));
}
console.log('useful gods:', usefulGods.topics.length, 'topics,',
  Object.values(usefulGods.data).reduce((a, rows) => a + rows.length, 0), 'symbols');
console.log('day scans:', dayScans.length, 'fixtures,',
  dayScans.reduce((a, s) => a + s.days.length, 0), 'days,',
  dayScans.reduce((a, s) => a + s.days.reduce((b, d) => b + d.goodHours.length, 0), 0),
  'good hours,',
  dayScans.reduce((a, s) => a + s.days.filter((d) => d.victoryDay).length, 0), 'victory days');
console.log('naming conventions:', Object.keys(MysticDoors.displayTerminology).length, '->',
  Object.values(MysticDoors.displayTerminology)
    .reduce((a, c) => a + Object.values(c).reduce((b, k) => b + Object.keys(k).length, 0), 0),
  'entries');
