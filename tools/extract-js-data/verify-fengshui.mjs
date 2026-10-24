// Differential check: original RN WindWater.js (executed directly) vs the
// live Java Feng Shui endpoints. Run with the app up on :8080.
//
//   cd tools/extract-js-data
//   node --import ./register-hooks.mjs ./verify-fengshui.mjs
//
// Env overrides (defaults shown):
//   BUNDLE_ROOT = C:/Temp/myTmp/bundle-sources/src/src/lib/cmtools
//   API         = http://localhost:8080
//
// Expected divergences (RN throws / returns null, Java answers 400):
//   - degreeToMountain gap degrees: RN crash -> HTTP 400
//   - unknown facing: RN TypeError -> HTTP 400
//   - invalid house inputs: RN null  -> HTTP 400
import { pathToFileURL } from 'node:url';
import { DateTime } from 'luxon';

const ROOT = (process.env.BUNDLE_ROOT || 'C:/Temp/myTmp/bundle-sources/src/src/lib/cmtools').replace(/\/$/, '');
const API = process.env.API || 'http://localhost:8080';

const WindWater = await import(pathToFileURL(ROOT + '/WindWater.js').href);

let checks = 0, failures = 0;
const fail = (msg) => { failures++; console.log('FAIL', msg); };
const ok = (cond, msg) => { checks++; if (!cond) fail(msg); };

function deepEq(a, b, path = '') {
  if (typeof a === 'number' && typeof b === 'number') return Math.abs(a - b) < 1e-9;
  if (a === null || a === undefined) return (b === null || b === undefined);
  if (Array.isArray(a)) {
    if (!Array.isArray(b) || a.length !== b.length) return false;
    return a.every((v, i) => deepEq(v, b[i], path + '[' + i + ']'));
  }
  if (typeof a === 'object') {
    if (typeof b !== 'object' || b === null || Array.isArray(b)) return false;
    const ka = Object.keys(a).filter(k => !k.startsWith('$')).sort();
    const kb = Object.keys(b).filter(k => !k.startsWith('$')).sort();
    if (ka.join('|') !== kb.join('|')) return false;
    return ka.every(k => deepEq(a[k], b[k], path + '.' + k));
  }
  return a === b;
}

async function get(path) {
  const res = await fetch(API + path);
  let body = null;
  try { body = await res.json(); } catch (e) { /* ignore */ }
  return { status: res.status, body };
}

function strip(v) {
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
}

// ---- reference tables -------------------------------------------------------
{
  const { status, body } = await get('/api/fengshui/reference');
  ok(status === 200, '/reference status ' + status);
  const pairs = [
    ['mountains', WindWater.mountains],
    ['attributes', WindWater.twentyFourMountainAttributes],
    ['lifeGuas', WindWater.guas],
    ['flyingStars', WindWater.flyingStars],
    ['flyingStarCombinations', WindWater.flyingStarCombinations],
    ['eightMansions', WindWater.eightMansions],
    ['eightMansionNaJia', WindWater.getEightMansionNaJia],
  ];
  for (const [key, rn] of pairs) ok(deepEq(strip(rn), body[key]), 'reference.' + key);
}

// ---- luopan sweep (boundaries + 15 degree steps) ---------------------------
{
  const degrees = [];
  for (let d = 0; d < 360; d += 15) degrees.push(d);
  for (const d of [7.5, 7.55, 7.6, 22.5, 22.6, 52.6, 60, 157.5, 157.55, 157.6,
    337.5, 352.5, 352.55, 352.6, 359.9, 360, 360.5, -0.1, -5]) degrees.push(d);
  for (const degree of degrees) {
    const { status, body } = await get('/api/fengshui/luopan?degree=' + encodeURIComponent(degree));
    if (status !== 200) { ok(false, 'luopan ' + degree + ' status ' + status); continue; }
    const sector = WindWater.degreeToMountain(degree);
    const expectedSector = sector === undefined ? null : sector;
    ok(body.sector === expectedSector, 'luopan ' + degree + ' sector ' + body.sector + ' != ' + expectedSector);
    const mountain = expectedSector ? WindWater.mountains[expectedSector] : null;
    ok(deepEq(mountain && strip(mountain), body.mountain || null), 'luopan ' + degree + ' mountain');
    const attributes = expectedSector ? WindWater.twentyFourMountainAttributes[expectedSector] : null;
    ok(deepEq(attributes && strip(attributes), body.attributes || null), 'luopan ' + degree + ' attributes');
  }
  const { status, body } = await get('/api/fengshui/luopan/mountains');
  ok(status === 200 && deepEq(strip(WindWater.mountains), body.mountains), 'luopan/mountains mountains');
  ok(deepEq(WindWater.xuanKongHexagramInner, body.rings.hexagramInner), 'rings.hexagramInner');
  ok(deepEq(WindWater.xuanKongNumberRingOuter, body.rings.numberOuter), 'rings.numberOuter');
  const dr = await get('/api/fengshui/dragons');
  ok(dr.status === 200, 'dragons status');
  ok(deepEq(WindWater.piercingMountain72Dragons, dr.body.piercingMountain72), 'dragons 72');
  ok(deepEq(WindWater.earthPenetrating60Dragons, dr.body.earthPenetrating60), 'dragons earth 60');
  ok(deepEq(WindWater.equalDivision60Dragons, dr.body.equalDivision60), 'dragons equal 60');
  ok(deepEq(WindWater.threeAuspiciousSixElegance, dr.body.threeAuspiciousSixElegance), 'three auspicious');
}

// ---- eight mansions ---------------------------------------------------------
{
  for (const dir of ['N', 'NE', 'E', 'SE', 'S', 'SW', 'W', 'NW']) {
    const { status, body } = await get('/api/fengshui/eight-mansions?facing=' + dir);
    const rn = WindWater.getEightMansionsFromFacing(dir);
    ok(status === 200, 'eight-mansions ' + dir + ' status');
    // API exposes the extended palace map (RN extendEightMansions).
    ok(deepEq(WindWater.extendEightMansions(rn), body.palaces), 'eight-mansions ' + dir + ' extended');
  }
  const { status } = await get('/api/fengshui/eight-mansions?facing=X');
  ok(status === 400, 'unknown facing should be 400, got ' + status);
}

// ---- personal gua -----------------------------------------------------------
{
  for (const year of [1864, 1932, 1978, 1990, 2000, 2024, 2064]) {
    for (const gender of ['male', 'female']) {
      for (const prev of [false, true]) {
        const { status, body } = await get('/api/fengshui/personal-gua?year=' + year +
          '&gender=' + gender + '&isPreviousYear=' + prev);
        const rn = WindWater.getPersonalGua(year, prev, gender);
        ok(status === 200, 'personal-gua ' + year + ' status');
        ok(deepEq(rn, body.personalGua), 'personal-gua ' + year + ' ' + gender + ' ' + prev);
        ok(deepEq(WindWater.guas[rn.pinyin].eightMansions, body.directions), 'personal-gua directions ' + year);
      }
    }
  }
}

// ---- time flying stars ------------------------------------------------------
{
  const times = [
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
  for (const iso of times) {
    const { status, body } = await get('/api/fengshui/time-flying-stars?time=' + encodeURIComponent(iso));
    const rn = WindWater.getTimeFlyingStars(DateTime.fromISO(iso, { setZone: true }));
    ok(status === 200, 'time-flying-stars ' + iso + ' status ' + status);
    ok(deepEq(rn.hour, body.hour), 'time ' + iso + ' hour');
    ok(deepEq(rn.day, body.day), 'time ' + iso + ' day');
    ok(deepEq(rn.month, body.month), 'time ' + iso + ' month');
    ok(deepEq(rn.year, body.year), 'time ' + iso + ' year');
  }
}

// ---- house flying stars -----------------------------------------------------
{
  const inputs = [
    { houseYear: 1984, houseDegree: 0 },
    { houseYear: 1984, houseDegree: 45 },
    { houseYear: 2024, houseDegree: 90 },
    { houseYear: 2024, houseDegree: 180 },
    { houseYear: 1864, houseDegree: 7.5 },
    { houseYear: 1864, houseDegree: 7.6 },
    { houseYear: 2064, houseDegree: 337.5 },
    { houseYear: 2064, houseDegree: 352.6 },
    { houseYear: 1984, houseDegree: 157.5 },
    { houseYear: 1984, houseDegree: 359.9 },
    { houseYear: 1990, houseDegree: 157.55 }, // RN crash (gap)
    { houseYear: 1863, houseDegree: 90 },     // RN null
    { houseYear: 2065, houseDegree: 90 },     // RN null
    { houseYear: 1984, houseDegree: -1 },     // RN null
    { houseYear: 1984, houseDegree: 361 },    // RN null
  ];
  for (const input of inputs) {
    const url = '/api/fengshui/house-flying-stars?houseYear=' + encodeURIComponent(input.houseYear) +
      '&houseDegree=' + encodeURIComponent(input.houseDegree);
    const { status, body } = await get(url);
    let rn = null, crash = false;
    try { rn = WindWater.getHouseFlyingStars(input); } catch (e) { crash = true; }
    if (rn === null || crash) {
      ok(status === 400, 'house ' + JSON.stringify(input) + ' expected 400 (RN ' + (crash ? 'crash' : 'null') + '), got ' + status);
    } else {
      ok(status === 200, 'house ' + JSON.stringify(input) + ' status ' + status);
      ok(deepEq(rn, body), 'house ' + JSON.stringify(input));
    }
  }
  // door degree echo + validation
  const good = await get('/api/fengshui/house-flying-stars?houseYear=1984&houseDegree=90&doorDegree=45');
  ok(good.status === 200 && good.body.doorDegree === 45, 'door echo');
  const bad = await get('/api/fengshui/house-flying-stars?houseYear=1984&houseDegree=90&doorDegree=abc');
  ok(bad.status === 400, 'door invalid should be 400, got ' + bad.status);
}

console.log(checks + ' checks, ' + failures + ' failures');
process.exit(failures === 0 ? 0 : 1);
