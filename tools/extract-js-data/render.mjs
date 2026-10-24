// Renders golden outputs from the original RN modules by running them in Node
// with the vendored (real) luxon build. Currently renders the Almanac day info
// (Timing.getAllDayInfo) for every fixture time.
//
// Usage:
//   cd tools/extract-js-data
//   node --import ./register-hooks.mjs ./render.mjs
//
// Env overrides (defaults shown):
//   BUNDLE_ROOT = C:/Temp/myTmp/bundle-sources/src/src/lib/cmtools
//   FIXTURES    = ./fixtures-almanac.json
//   OUT         = ../../bazi-spring/src/test/resources/golden/almanac.json
import fs from 'node:fs';
import { pathToFileURL } from 'node:url';
import { DateTime } from 'luxon';

const ROOT = process.env.BUNDLE_ROOT || 'C:/Temp/myTmp/bundle-sources/src/src/lib/cmtools';
const FIXTURES = process.env.FIXTURES || './fixtures-almanac.json';
const OUT = process.env.OUT || '../../bazi-spring/src/test/resources/golden/almanac.json';

const url = (rel) => pathToFileURL(ROOT.replace(/\/$/, '') + '/' + rel).href;

const FourPillars = await import(url('Plotters/FourPillars/index.js'));
const Timing = await import(url('Timing.js'));

const compactPillar = (p) => ({ stem: p.stem.name, branch: p.branch.name });

const fixtures = JSON.parse(fs.readFileSync(FIXTURES, 'utf8'));
const results = [];

for (const fixture of fixtures) {
  const time = DateTime.fromISO(fixture.time, { setZone: true });
  if (!time.isValid) throw new Error(`invalid fixture time: ${fixture.time}`);
  const chart = FourPillars.plot({
    time,
    lateZi: !!fixture.lateZi,
    gender: fixture.gender || 'male',
    hourUnknown: !!fixture.hourUnknown,
  });
  if (!chart) throw new Error(`plot returned null for ${fixture.time}`);
  const dayInfo = JSON.parse(JSON.stringify(Timing.getAllDayInfo({ bazi: chart })));
  results.push({
    time: fixture.time,
    lateZi: !!fixture.lateZi,
    hourUnknown: !!fixture.hourUnknown,
    pillars: {
      year: compactPillar(chart.pillars.year),
      month: compactPillar(chart.pillars.month),
      day: compactPillar(chart.pillars.day),
      hour: compactPillar(chart.pillars.hour),
    },
    infoTime: chart.info.time.toISO(),
    dayInfo,
  });
}

fs.mkdirSync(OUT.replace(/[\\/][^\\/]*$/, ''), { recursive: true });
fs.writeFileSync(OUT, JSON.stringify({
  meta: {
    source: ROOT,
    generated: new Date().toISOString(),
    fixtures: fixtures.length,
    description: 'Almanac day info rendered by the original Timing.js via the real luxon build',
  },
  results,
}, null, 1));

console.log('wrote', OUT, fs.statSync(OUT).size, 'bytes');
console.log('fixtures:', results.length,
  'with tongShu entry:', results.filter(r => r.dayInfo.other).length,
  'bigDisaster flagged:', results.filter(r => r.dayInfo.bigDisaster).length,
  'yearBreaker:', results.filter(r => r.dayInfo.yearBreaker).length,
  'monthBreaker:', results.filter(r => r.dayInfo.monthBreaker).length,
  'monthDestructor:', results.filter(r => r.dayInfo.monthDestructor).length);
