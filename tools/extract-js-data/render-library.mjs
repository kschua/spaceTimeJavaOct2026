// Renders Library / Compendium golden outputs from the original RN sources.
// The Compendium tree and Titles map are imported directly; the inline
// component tables (RelationshipsTable / YinYangRef / HeavenEarthMan) are
// brace-extracted verbatim; the TwelveStagesList loop runs against the real
// Ordinals data (twelveStagesData + stems[stem].twelveStages.indexOf(branch)).
//
// Usage:
//   cd tools/extract-js-data
//   node --import ./register-hooks.mjs ./render-library.mjs
//
// Env overrides (defaults shown):
//   BUNDLE_ROOT = C:/Temp/myTmp/bundle-sources/src/src/lib/cmtools
//   OUT         = ../../bazi-spring/src/test/resources/golden/library.json
import fs from 'node:fs';
import { pathToFileURL } from 'node:url';
import { extractLibrarySources } from './library-source.mjs';
import { extractTutorials } from './tutorial-source.mjs';

const ROOT = (process.env.BUNDLE_ROOT || 'C:/Temp/myTmp/bundle-sources/src/src/lib/cmtools').replace(/\/$/, '');
const OUT = process.env.OUT || '../../bazi-spring/src/test/resources/golden/library.json';

const url = (rel) => pathToFileURL(ROOT + '/' + rel).href;
const Compendium = (await import(url('Compendium/index.js'))).default;
const Titles = (await import(url('Compendium/Titles.js'))).default;
const Ordinals = await import(url('Ordinals.js'));
const tables = extractLibrarySources(ROOT);
const tutorials = extractTutorials(ROOT);

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

// TwelveStagesList.componentDidMount: one row per branch of branchOrdering,
// stage = twelveStagesData[stems[stem].twelveStages.indexOf(branch)].
// (toProxy arrays throw on Symbol access, so read them via slice.)
const arr = (a) => Array.prototype.slice.call(a);
const twelveStages = {};
for (const stem of arr(Ordinals.stemOrdering)) {
  const info = Ordinals.stems[stem];
  twelveStages[stem] = {
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

const out = {
  meta: {
    source: ROOT,
    generated: new Date().toISOString(),
    compendiumKeys: Object.keys(Compendium).length,
    titleEntries: Object.keys(Titles).length,
    relationshipSections: tables.relationships.length,
    yinYangPairs: tables.yinYang.length,
    tutorialPages: tutorials.reduce((a, t) => a + t.pages.length, 0),
    description: 'Library goldens rendered from the original RN sources: the '
      + 'Compendium/index.js tree + Titles.js, the RelationshipsTable / '
      + 'YinYangRef / HeavenEarthMan literals brace-extracted from the Info '
      + 'components, the four Tutorial* Onboarding page arrays, and the '
      + 'TwelveStagesList loop over real Ordinals data',
  },
  compendium: strip(Compendium),
  titles: strip(Titles),
  relationships: tables.relationships,
  yinYang: tables.yinYang,
  heavenEarthMan: tables.heavenEarthMan,
  tutorials,
  twelveStages,
};

fs.writeFileSync(OUT, JSON.stringify(out, null, 1));
console.log('wrote', OUT, fs.statSync(OUT).size, 'bytes');
console.log('compendium keys:', Object.keys(out.compendium).length,
  '| titles:', Object.keys(out.titles).length,
  '| relationship sections:', out.relationships.length,
  '| yinYang pairs:', out.yinYang.length,
  '| heavenEarthMan:', Object.values(out.heavenEarthMan).map((l) => l.length).join('/'),
  '| tutorials:', out.tutorials.map((t) => t.key + ':' + t.pages.length).join(','),
  '| twelveStages stems:', Object.keys(out.twelveStages).length);
