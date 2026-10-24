// Renders Feng Shui golden outputs from the original RN modules.
// WindWater.js functions are imported and executed directly (no transcription):
// degreeToMountain, yearToPeriod, getEightMansionsFromFacing,
// extendEightMansions, getPersonalGua, getTimeFlyingStars, getHouseFlyingStars,
// plus the static tables.
//
// Usage:
//   cd tools/extract-js-data
//   node --import ./register-hooks.mjs ./render-fengshui.mjs
//
// Env overrides (defaults shown):
//   BUNDLE_ROOT = C:/Temp/myTmp/bundle-sources/src/src/lib/cmtools
//   OUT         = ../../bazi-spring/src/test/resources/golden/fengshui.json
import fs from 'node:fs';
import { pathToFileURL } from 'node:url';
import { DateTime } from 'luxon';

const ROOT = (process.env.BUNDLE_ROOT || 'C:/Temp/myTmp/bundle-sources/src/src/lib/cmtools').replace(/\/$/, '');
const OUT = process.env.OUT || '../../bazi-spring/src/test/resources/golden/fengshui.json';

const cmUrl = (rel) => pathToFileURL(ROOT + '/' + rel).href;
const WindWater = await import(cmUrl('WindWater.js'));

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
  if (typeof v === 'function') return undefined;
  return v;
};

// ---- degree sweep (0.5 degree steps + all range boundaries + quirks) -------
const degreeFixtures = [];
for (let d = 0; d <= 360; d += 0.5) degreeFixtures.push(Math.round(d * 10) / 10);
for (const d of [7.5, 7.55, 7.6, 22.5, 22.55, 22.6, 157.5, 157.55, 157.6,
  337.5, 352.5, 352.55, 352.6, 359.9, 360, 360.5, -0.1, -5]) {
  if (!degreeFixtures.includes(d)) degreeFixtures.push(d);
}
const degreeToMountain = degreeFixtures.map((degree) => ({
  degree,
  sector: WindWater.degreeToMountain(degree),
}));

// ---- yearToPeriod sweep ----------------------------------------------------
const yearToPeriod = [];
for (let y = 1840; y <= 2090; y++) yearToPeriod.push({ year: y, period: WindWater.yearToPeriod(y) });

// ---- eight mansions (facing map + extended scores) -------------------------
const directions = ['N', 'NE', 'E', 'SE', 'S', 'SW', 'W', 'NW'];
const eightMansions = directions.map((dir) => {
  const raw = WindWater.getEightMansionsFromFacing(dir);
  return { facing: dir, palaces: raw, extended: WindWater.extendEightMansions(raw) };
});
let eightMansionsUnknown;
try {
  eightMansionsUnknown = { palaces: WindWater.getEightMansionsFromFacing('X') };
} catch (err) {
  // RN: m is undefined -> TypeError; Java returns null (safe divergence)
  eightMansionsUnknown = { error: err.message };
}

// ---- personal gua ----------------------------------------------------------
const personalGua = [];
for (const year of [1864, 1900, 1932, 1950, 1978, 1984, 1990, 2000, 2012, 2024, 2044, 2064, 2080]) {
  for (const gender of ['male', 'female']) {
    for (const isPreviousYear of [false, true]) {
      personalGua.push({
        year,
        gender,
        isPreviousYear,
        gua: WindWater.getPersonalGua(year, isPreviousYear, gender),
      });
    }
  }
}

// ---- time flying stars -----------------------------------------------------
const timeFixtures = [
  '2024-06-15T14:30:00+08:00',
  '2024-07-01T10:00:00+08:00',
  '2024-12-25T09:00:00+08:00',
  '2024-01-15T08:00:00+08:00',
  '2024-02-10T12:00:00+08:00',
  '2025-02-03T23:30:00+08:00',
  '2025-02-04T10:00:00+08:00',
  '2023-06-22T00:30:00+08:00',
  '2023-12-22T23:30:00+08:00',
  '1990-05-15T14:30:00+08:00',
  '2000-01-01T00:00:00+08:00',
  '2044-01-01T12:00:00+08:00',
  // non-+08 offsets: the wall time + offset drive every center calculation
  '2024-06-15T14:30:00-05:00',
  '2024-06-15T14:30:00+05:30',
  '2024-06-15T14:30:00+11:00',
  '2024-06-15T14:30:00Z',
  '2024-06-15T23:30:00-08:00',
  '2025-02-03T23:30:00+05:30',
  // current term is a solstice -> SolarCal.getSolstice returns term.begins
  '2023-06-22T00:30:00-05:00',
  '2024-12-25T09:00:00-05:00',
];
const timeFlyingStars = timeFixtures.map((iso) => {
  const time = DateTime.fromISO(iso, { setZone: true });
  if (!time.isValid) throw new Error('invalid fixture time ' + iso);
  return { time: iso, ...WindWater.getTimeFlyingStars(time) };
});

// ---- house flying stars ----------------------------------------------------
const houseInputs = [
  { houseYear: 1984, houseDegree: 0 },
  { houseYear: 1984, houseDegree: 45 },
  { houseYear: 2024, houseDegree: 90 },
  { houseYear: 2024, houseDegree: 180 },
  { houseYear: 1864, houseDegree: 7.5 },
  { houseYear: 1864, houseDegree: 7.6 },
  { houseYear: 2064, houseDegree: 337.5 },
  { houseYear: 2064, houseDegree: 352.6 },
  { houseYear: 1984, houseDegree: 157.5 },
  { houseYear: 1984, houseDegree: 157.6 },
  { houseYear: 1990, houseDegree: 157.55 }, // falls in the RN range gap
  { houseYear: 1984, houseDegree: 359.9 },
  { houseYear: 1863, houseDegree: 90 },
  { houseYear: 2065, houseDegree: 90 },
  { houseYear: 1984, houseDegree: -1 },
  { houseYear: 1984, houseDegree: 361 },
  { houseYear: 1984, houseDegree: null },
  { houseYear: null, houseDegree: 90 },
  { houseYear: 1984, houseDegree: 'abc' },
  { houseYear: 0, houseDegree: 90 },
];
const houseFlyingStars = houseInputs.map((input) => {
  try {
    const payload = WindWater.getHouseFlyingStars(input);
    return { input, payload: payload === undefined ? null : payload };
  } catch (err) {
    return { input, error: err.message };
  }
});

// ---- tables ----------------------------------------------------------------
const tables = strip({
  mountains: WindWater.mountains,
  twentyFourMountainAttributes: WindWater.twentyFourMountainAttributes,
  guas: WindWater.guas,
  flyingStars: WindWater.flyingStars,
  flyingStarCombinations: WindWater.flyingStarCombinations,
  eightMansions: WindWater.eightMansions,
  getEightMansionNaJia: WindWater.getEightMansionNaJia,
  xuanKongNumberRingInner: WindWater.xuanKongNumberRingInner,
  xuanKongNumberRingOuter: WindWater.xuanKongNumberRingOuter,
  xuanKongHexagramInner: WindWater.xuanKongHexagramInner,
  xuanKongHexagramOuter: WindWater.xuanKongHexagramOuter,
  piercingMountain72Dragons: WindWater.piercingMountain72Dragons,
  earthPenetrating60Dragons: WindWater.earthPenetrating60Dragons,
  equalDivision60Dragons: WindWater.equalDivision60Dragons,
  threeAuspiciousSixElegance: WindWater.threeAuspiciousSixElegance,
  qiMenFengShuiMap: WindWater.qiMenFengShuiMap,
});

// ---- write -----------------------------------------------------------------
fs.mkdirSync(OUT.replace(/[\\/][^\\/]*$/, ''), { recursive: true });
fs.writeFileSync(OUT, JSON.stringify({
  meta: {
    source: ROOT,
    generated: new Date().toISOString(),
    description: 'Feng Shui goldens rendered by the original RN WindWater.js functions',
  },
  tables,
  degreeToMountain,
  yearToPeriod,
  eightMansions,
  eightMansionsUnknown,
  personalGua,
  timeFlyingStars,
  houseFlyingStars,
}, null, 1));

console.log('wrote', OUT, fs.statSync(OUT).size, 'bytes');
console.log('degrees:', degreeToMountain.length, 'years:', yearToPeriod.length,
  'personal gua:', personalGua.length, 'time fixtures:', timeFlyingStars.length,
  'house fixtures:', houseFlyingStars.length);
