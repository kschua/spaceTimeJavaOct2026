// Renders the Bazi chart golden from the original RN modules.
// FourPillars.plot + EightWords.getSummary/extendAuxDataChart are imported and
// executed directly (no transcription); the result is projected to the fields
// the Java port is expected to reproduce.
//
// Usage:
//   cd tools/extract-js-data
//   node --import ./register-hooks.mjs ./render-bazi.mjs
//
// Env overrides (defaults shown):
//   BUNDLE_ROOT = C:/Temp/myTmp/bundle-sources/src/src/lib/cmtools
//   OUT         = ../../bazi-spring/src/test/resources/golden/bazi.json
//
// Notes / known RN quirks:
//  * `plot()` does not call EightWords.getSummary(); the app calls it for the
//    weights panel, so this renderer calls it when showWeights is on.
//  * extendAuxDataChart() sorts ShenSha.annualStarAux arrays IN PLACE and
//    getSummary() sorts Ordinals.elements[*].stems IN PLACE. Both comparators
//    never return 0, so a second call toggles tie order. We restore the
//    pristine arrays before every chart so each golden captures the first-call
//    order the Java port reproduces.
//  * stem.interactions / branch.interactions depend on DateTime.local()
//    (current luck pillar) and are intentionally not projected; the Java test
//    asserts they are arrays.
//  * calcCounts() in the RN source is buggy for stems/gods (it uses stem
//    objects as object keys -> "[object Object]"/"undefined" NaN keys). The
//    golden only keeps counts.elements / counts.elementsCore, which are
//    correct; the Java test asserts the corrected stem/god counts separately.
import fs from 'node:fs';
import { pathToFileURL } from 'node:url';
import { DateTime } from 'luxon';

const ROOT = (process.env.BUNDLE_ROOT || 'C:/Temp/myTmp/bundle-sources/src/src/lib/cmtools').replace(/\/$/, '');
const OUT = process.env.OUT || '../../bazi-spring/src/test/resources/golden/bazi.json';

const cmUrl = (rel) => pathToFileURL(ROOT + '/' + rel).href;
const FourPillars = await import(cmUrl('Plotters/FourPillars/index.js'));
const EightWords = await import(cmUrl('EightWords.js'));
const ShenSha = await import(cmUrl('ShenSha.js'));
const Ordinals = await import(cmUrl('Ordinals.js'));

// ---- pristine snapshots (see notes above) ---------------------------------
const annualStarSnapshot = {};
for (const branch of Object.keys(ShenSha.annualStarAux.zi)) {
  annualStarSnapshot[branch] = ShenSha.annualStarAux.zi[branch].slice();
}
const elementStemSnapshot = {};
for (const element of Object.keys(Ordinals.elements)) {
  elementStemSnapshot[element] = Ordinals.elements[element].stems.slice();
}
const restorePristine = () => {
  for (const branch of Object.keys(annualStarSnapshot)) {
    const arr = ShenSha.annualStarAux.zi[branch];
    arr.length = 0;
    arr.push(...annualStarSnapshot[branch]);
  }
  for (const element of Object.keys(elementStemSnapshot)) {
    const arr = Ordinals.elements[element].stems;
    arr.length = 0;
    arr.push(...elementStemSnapshot[element]);
  }
};

const strip = (v) => {
  if (Array.isArray(v)) return v.map(strip);
  if (v && typeof v === 'object') {
    const o = {};
    for (const k of Object.keys(v)) {
      if (k === '$icon') continue;
      const val = strip(v[k]);
      if (typeof val !== 'function' && val !== undefined) o[k] = val;
    }
    return o;
  }
  if (typeof v === 'function') return undefined;
  return v;
};

// ---- projection -----------------------------------------------------------
const projectStem = (s) => ({
  name: s.name,
  chinese: s.chinese,
  pinyin: s.pinyin,
  element: s.element,
  polarity: s.polarity,
  number: s.number,
  god: s.god,
  aspect: s.aspect,
  aspc: s.aspc ? { name: s.aspc.name } : null,
  gdd: s.gdd ? { name: s.gdd.name } : null,
  elm: s.elm ? { name: s.elm.name } : null,
});

const projectBranch = (b) => ({
  name: b.name,
  chinese: b.chinese,
  pinyin: b.pinyin,
  animalName: b.animalName,
  element: b.element,
  polarity: b.polarity,
  number: b.number,
  season: b.season,
  group: b.group,
  direction: b.direction,
  palace: b.palace,
  month: b.month,
  hour: b.hour,
  body: b.body,
  ssn: b.ssn,
  stems: Object.fromEntries(Object.entries(b.stems).map(([k, v]) => [k, v ? { name: v.name } : null])),
});

const projectStar = (s) => (s == null ? null : {
  name: s.name,
  ref: s.ref,
  chinese: s.chinese,
  rating: s.rating,
});

const projectPillar = (p) => {
  if (!p) return null;
  const out = {
    name: p.name,
    jiaGroup: p.jiaGroup,
    voidStars: p.voidStars,
    stem: projectStem(p.stem),
    branch: projectBranch(p.branch),
  };
  // Extended fields exist only after extendAuxDataChart(); non-extended charts
  // must not require them (the Java port always emits them, which is additive).
  if (p.naYin !== undefined) out.naYin = p.naYin;
  if (p.stage !== undefined) out.stage = p.stage;
  if (p.interactions !== undefined) out.interactions = p.interactions;
  if (p.stars !== undefined) out.stars = p.stars.map(projectStar);
  if (p.annualStars !== undefined) out.annualStars = p.annualStars.map(projectStar);
  if (p.specialMatch !== undefined) out.specialMatch = p.specialMatch;
  return out;
};

const projectMeta = (meta, showWeights) => {
  const out = { maps: meta.maps, gua: meta.gua };
  if (showWeights) {
    // counts.stems / counts.gods are intentionally omitted (RN bug).
    out.counts = { elements: meta.counts.elements, elementsCore: meta.counts.elementsCore };
    out.weightsPlain = meta.weightsPlain;
    out.weightsCombo = meta.weightsCombo;
    out.weights = meta.weights;
    out.stats = meta.stats;
    out.usefulGods = meta.usefulGods;
    out.favorable = meta.favorable;
    out.unfavorable = meta.unfavorable;
    out.summary = meta.summary;
  }
  return out;
};

const projectChart = (chart, fixture) => ({
  info: chart.info,
  luck: (chart.luck || []).map((l) => ({
    stem: l.pillar.stem.name,
    branch: l.pillar.branch.name,
    time: l.time,
  })),
  pillars: Object.fromEntries(Object.entries(chart.pillars).map(([k, p]) => [k, projectPillar(p)])),
  meta: projectMeta(chart.meta, fixture.showWeights),
});

// ---- fixtures -------------------------------------------------------------
// All times carry an explicit offset so the golden is independent of the
// render machine's system zone. Naive-input behaviour is covered by Java-only
// assertions in BaziServiceTest.
const fixtures = [
  {
    name: 'reference-male',
    time: '1990-05-15T14:30:00+08:00',
    gender: 'male',
    chartName: 'Reference',
    showLuck: true,
    showWeights: true,
    extend: true,
  },
  {
    name: 'reference-female',
    time: '1990-05-15T14:30:00+08:00',
    gender: 'female',
    showLuck: true,
    showWeights: true,
    extend: true,
  },
  {
    name: 'lichun-before',
    time: '2024-02-04T16:00:00+08:00',
    gender: 'male',
    showLuck: true,
    showWeights: true,
    extend: true,
  },
  {
    name: 'lichun-after',
    time: '2024-02-04T17:00:00+08:00',
    gender: 'male',
    showLuck: true,
    showWeights: true,
    extend: true,
  },
  {
    name: 'late-zi',
    time: '2024-06-15T23:30:00+08:00',
    gender: 'female',
    lateZi: true,
    showLuck: true,
    showWeights: true,
    extend: true,
  },
  {
    name: 'hour-unknown',
    time: '1990-05-15T14:30:00+08:00',
    gender: 'male',
    hourUnknown: true,
    showLuck: true,
    showWeights: true,
    extend: true,
  },
  {
    name: 'gender-unknown',
    time: '1990-05-15T14:30:00+08:00',
    gender: 'male',
    genderUnknown: true,
    showLuck: true,
    showWeights: true,
    extend: true,
  },
  {
    name: 'simple-luck-false',
    time: '1990-05-15T14:30:00+08:00',
    gender: 'male',
    simpleLuckCycle: false,
    showLuck: true,
    showWeights: true,
    extend: true,
  },
  {
    name: 'weights-plain',
    time: '1990-05-15T14:30:00+08:00',
    gender: 'male',
    weightsIncludeCombos: false,
    showLuck: true,
    showWeights: true,
    extend: true,
  },
  {
    name: 'shifting-palace',
    time: '1990-05-15T14:30:00+08:00',
    gender: 'male',
    shiftingPalace: { stem: 'jia' },
    showLuck: true,
    showWeights: true,
    extend: true,
  },
  {
    name: 'root-chart',
    time: '1990-05-15T14:30:00+08:00',
    gender: 'male',
    rootTime: '1985-03-02T09:00:00+08:00',
    showLuck: true,
    showWeights: true,
    extend: true,
  },
  {
    name: 'utc',
    time: '2000-01-01T00:00:00Z',
    gender: 'female',
    showLuck: true,
    showWeights: true,
    extend: true,
  },
  {
    name: 'offset-minus5',
    time: '1985-07-04T06:15:00-05:00',
    gender: 'male',
    showLuck: true,
    showWeights: true,
    extend: true,
  },
  {
    name: 'offset-plus11',
    time: '2012-11-06T21:45:00+11:00',
    gender: 'female',
    showLuck: true,
    showWeights: true,
    extend: true,
  },
  {
    name: 'minimal',
    time: '2000-01-01T12:00:00+08:00',
    gender: 'male',
    showLuck: false,
    showWeights: false,
    extend: false,
  },
];

// ---- render ---------------------------------------------------------------
const results = [];
for (const fixture of fixtures) {
  restorePristine();
  const time = DateTime.fromISO(fixture.time, { setZone: true });
  if (!time.isValid) throw new Error(`invalid fixture time: ${fixture.time}`);

  let root;
  if (fixture.rootTime) {
    root = FourPillars.plot({
      time: DateTime.fromISO(fixture.rootTime, { setZone: true }),
      showLuck: false,
      showWeights: false,
    });
    if (!root) throw new Error(`root plot returned null for ${fixture.rootTime}`);
  }

  const chart = FourPillars.plot({
    time,
    chartName: fixture.chartName ?? null,
    gender: fixture.gender || 'male',
    genderUnknown: !!fixture.genderUnknown,
    hourUnknown: !!fixture.hourUnknown,
    lateZi: !!fixture.lateZi,
    showLuck: !!fixture.showLuck,
    showWeights: !!fixture.showWeights,
    weightsIncludeCombos: fixture.weightsIncludeCombos !== false,
    simpleLuckCycle: fixture.simpleLuckCycle !== false,
    shiftingPalace: fixture.shiftingPalace,
    root,
  });
  if (!chart) throw new Error(`plot returned null for ${fixture.name}`);

  // The app calls getSummary() for the weights panel; plot() does not.
  if (fixture.showWeights) EightWords.getSummary(chart, 0.1);
  if (fixture.extend) EightWords.extendAuxDataChart(chart);

  const serialized = strip(JSON.parse(JSON.stringify(chart)));
  results.push({
    name: fixture.name,
    time: fixture.time,
    options: {
      chartName: fixture.chartName ?? null,
      gender: fixture.gender || 'male',
      genderUnknown: !!fixture.genderUnknown,
      hourUnknown: !!fixture.hourUnknown,
      lateZi: !!fixture.lateZi,
      showLuck: !!fixture.showLuck,
      showWeights: !!fixture.showWeights,
      simpleLuckCycle: fixture.simpleLuckCycle !== false,
      weightsIncludeCombos: fixture.weightsIncludeCombos !== false,
      shiftingPalace: fixture.shiftingPalace || null,
      rootTime: fixture.rootTime || null,
      extend: !!fixture.extend,
    },
    chart: projectChart(serialized, {
      showWeights: !!fixture.showWeights,
      extend: !!fixture.extend,
    }),
  });
}

fs.mkdirSync(OUT.replace(/[\\/][^\\/]*$/, ''), { recursive: true });
fs.writeFileSync(OUT, JSON.stringify({
  meta: {
    source: ROOT,
    generated: new Date().toISOString(),
    fixtures: fixtures.length,
    description: 'Bazi charts rendered by the original FourPillars.plot / EightWords via the real luxon build',
  },
  results,
}, null, 1));

console.log('wrote', OUT, fs.statSync(OUT).size, 'bytes');
console.log('fixtures:', results.length);
for (const r of results) {
  const p = r.chart.pillars;
  console.log(' ', r.name.padEnd(20),
    ['year', 'month', 'day', 'hour'].map(k => p[k] ? p[k].stem.name + '-' + p[k].branch.name : '--').join(' '),
    'luck', r.chart.luck.length,
    'stars', ['year', 'month', 'day', 'hour'].reduce((n, k) => n + (p[k]?.stars?.length || 0), 0));
}
