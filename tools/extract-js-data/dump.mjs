// Dumps the bazi data tables from the original React Native sources into a
// single JSON file (`raw-dump.json` by default), so they can be merged into
// the Spring Boot service's JSON data files.
//
// Requires a Windows/WSL-accessible Node.js (see README.md). Example:
//   BUNDLE_ROOT='C:/Temp/myTmp/bundle-sources/src/src/lib/cmtools' \
//   OUT='C:/Temp/space/tools/extract-js-data/raw-dump.json' \
//   node --import ./register-hooks.mjs ./dump.mjs

import fs from 'node:fs';
import { pathToFileURL } from 'node:url';
import { extractLibrarySources } from './library-source.mjs';
import { extractTutorials } from './tutorial-source.mjs';

const ROOT = process.env.BUNDLE_ROOT || 'C:/Temp/myTmp/bundle-sources/src/src/lib/cmtools';
const OUT = process.env.OUT || 'raw-dump.json';

const url = (rel) => pathToFileURL(ROOT.replace(/\/$/, '') + '/' + rel).href;

const Ordinals = await import(url('Ordinals.js'));
const ShenSha = await import(url('ShenSha.js'));
const Dict = await import(url('Compendium/Dictionary.js'));
const Timing = await import(url('Timing.js'));
const Hexes = await import(url('Hexes.js'));
const WindWater = await import(url('WindWater.js'));
const SolarCal = await import(url('SolarCal.js'));
const MysticDoors = await import(url('MysticDoors.js'));
const { default: Compendium } = await import(url('Compendium/index.js'));
const { default: Titles } = await import(url('Compendium/Titles.js'));
const { default: DongGong } = await import(url('Data/DongGong.js'));
const { tongShu } = await import(url('Data/tongShu.js'));
const { default: jusHour } = await import(url('Data/jusHour.js'));
const { default: jusDay } = await import(url('Data/jusDay.js'));
const { default: jusMonth } = await import(url('Data/jusMonth.js'));
const { default: jusYear } = await import(url('Data/jusYear.js'));

// Strip non-serializable / presentation-only fields ($icon points at image
// modules; function-valued keys - e.g. Dictionary's `$stem`/`$branch` helpers -
// are omitted, exactly as JSON.stringify would).
function strip(v) {
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
}

// annualStarAux entries are shared references into auxDetails / annualAuxDetails.
// Emit {table, group, name} refs instead of duplicating the objects.
const refMap = new Map();
for (const [group, entries] of Object.entries(ShenSha.auxDetails)) {
  for (const [name, entry] of Object.entries(entries)) {
    if (entry && typeof entry === 'object') refMap.set(entry, { table: 'auxDetails', group, name });
  }
}
for (const [name, entry] of Object.entries(ShenSha.annualAuxDetails)) {
  if (entry && typeof entry === 'object') refMap.set(entry, { table: 'annualAuxDetails', name });
}
function normalizeAnnual(starAux) {
  const out = {};
  const unresolved = [];
  for (const [g, branches] of Object.entries(starAux)) {
    out[g] = {};
    for (const [b, entries] of Object.entries(branches)) {
      out[g][b] = entries.map((e) => {
        const ref = refMap.get(e);
        if (!ref) {
          unresolved.push(`${g}.${b} -> ${e && e.name}`);
          return { table: 'unresolved', name: e && e.name };
        }
        return ref;
      });
    }
  }
  if (unresolved.length) {
    throw new Error('unmapped annualStarAux entries:\n' + unresolved.join('\n'));
  }
  return out;
}

const out = {
  meta: {
    source: ROOT,
    generated: new Date().toISOString(),
  },
  ordinals: {
    stems: strip(Ordinals.stems),
    branches: strip(Ordinals.branches),
    sixtyPillars: strip(Ordinals.sixtyPillars),
    elements: strip(Ordinals.elements),
    seasons: strip(Ordinals.seasons),
    twelveStagesData: strip(Ordinals.twelveStagesData),
    streams: strip(Ordinals.streams),
    guas: strip(Ordinals.guas),
    branchInteractionOrdering: Array.from(Ordinals.branchInteractionOrdering || []),
    stemInteractionOrdering: Array.from(Ordinals.stemInteractionOrdering || []),
    branchSpecialRelations: strip(Ordinals.branchSpecialRelations),
  },
  display: {
    aspects: strip(Dict.EightWords.aspects),
    gods: strip(Dict.EightWords.gods),
    pillars: strip(Dict.EightWords.pillars),
  },
  shensha: {
    auxDetails: strip(ShenSha.auxDetails),
    annualAuxDetails: strip(ShenSha.annualAuxDetails),
    annualStarAux: normalizeAnnual(ShenSha.annualStarAux),
  },
  timing: {
    twelveOfficerDesc: strip(Timing.twelveOfficerDesc),
    twentyEightConstellationsDesc: strip(Timing.twentyEightConstellationsDesc),
    threeKillingsDesc: strip(Timing.threeKillingsDesc),
    officers: Array.from(Timing.officers),
    bigDisasterDays: strip(Timing.bigDisasterDays),
    dongGong: strip(DongGong),
    tongShu: strip(tongShu),
  },
  hexes: {
    horary: strip(Hexes.horary),
    horaryTable: strip(Hexes.horaryTable),
    order: strip(Hexes.order),
    pillars: strip(Hexes.pillars),
    animalOrder: Array.from(Hexes.animalOrder),
    stemAnimalMap: strip(Hexes.stemAnimalMap),
    hexagramStartBranches: strip(Hexes.hexagramStartBranches),
  },
  windwater: {
    mountains: strip(WindWater.mountains),
    twentyFourMountainAttributes: strip(WindWater.twentyFourMountainAttributes),
    guas: strip(WindWater.guas),
    flyingStars: strip(WindWater.flyingStars),
    flyingStarCombinations: strip(WindWater.flyingStarCombinations),
    eightMansions: strip(WindWater.eightMansions),
    getEightMansionNaJia: strip(WindWater.getEightMansionNaJia),
    xuanKongNumberRingInner: strip(WindWater.xuanKongNumberRingInner),
    xuanKongNumberRingOuter: strip(WindWater.xuanKongNumberRingOuter),
    xuanKongHexagramInner: strip(WindWater.xuanKongHexagramInner),
    xuanKongHexagramOuter: strip(WindWater.xuanKongHexagramOuter),
    piercingMountain72Dragons: strip(WindWater.piercingMountain72Dragons),
    earthPenetrating60Dragons: strip(WindWater.earthPenetrating60Dragons),
    equalDivision60Dragons: strip(WindWater.equalDivision60Dragons),
    threeAuspiciousSixElegance: strip(WindWater.threeAuspiciousSixElegance),
    qiMenFengShuiMap: strip(WindWater.qiMenFengShuiMap),
  },
  qimen: {
    jusHour: strip(jusHour),
    jusDay: strip(jusDay),
    jusMonth: strip(jusMonth),
    jusYear: strip(jusYear),
    solarJus: strip(SolarCal.solarJus),
    threeYuanTable: strip(Ordinals.threeYuanTable),
    pillarOrdering: strip(Ordinals.pillarOrdering),
    doors: strip(MysticDoors.doors),
    stars: strip(MysticDoors.stars),
    hours: strip(MysticDoors.hours),
    formations: strip(MysticDoors.formations),
    structures: strip(MysticDoors.structures),
    usefulGodSymbols: strip(MysticDoors.usefulGodSymbols),
    displayTerminology: strip(MysticDoors.displayTerminology),
  },
  library: {
    compendium: strip(Compendium),
    titles: strip(Titles),
    ...extractLibrarySources(ROOT),
    tutorials: extractTutorials(ROOT),
  },
};

fs.writeFileSync(OUT, JSON.stringify(out, null, 1));
console.log('wrote', OUT, fs.statSync(OUT).size, 'bytes');
console.log('stems:', Object.keys(out.ordinals.stems).length,
  'branches:', Object.keys(out.ordinals.branches).length,
  'streams:', Object.keys(out.ordinals.streams).length,
  'guas:', Object.keys(out.ordinals.guas).length,
  'auxDetails groups:', Object.keys(out.shensha.auxDetails).length,
  'annualAuxDetails:', Object.keys(out.shensha.annualAuxDetails).length,
  'annualStarAux groups:', Object.keys(out.shensha.annualStarAux).length);
console.log('timing tongShu days:', Object.keys(out.timing.tongShu).length,
  'dongGong months:', Object.keys(out.timing.dongGong).length,
  'bigDisaster years:', Object.keys(out.timing.bigDisasterDays).length);
console.log('hexes order:', out.hexes.order.length,
  'horaryTable keys:', Object.keys(out.hexes.horaryTable).length,
  'pillar entries:', Object.values(out.hexes.pillars).reduce((a, o) => a + Object.keys(o).length, 0));
console.log('windwater mountains:', Object.keys(out.windwater.mountains).length,
  'mountains attributes:', Object.keys(out.windwater.twentyFourMountainAttributes).length,
  'flyingStars:', Object.keys(out.windwater.flyingStars).length,
  'qiMen ju:', Object.keys(out.windwater.qiMenFengShuiMap).length);
console.log('qimen jusHour years:', Object.keys(out.qimen.jusHour).length,
  'jusDay years:', Object.keys(out.qimen.jusDay).length,
  'jusMonth years:', Object.keys(out.qimen.jusMonth).length,
  'jusYear years:', Object.keys(out.qimen.jusYear).length,
  'solarJus terms:', Object.keys(out.qimen.solarJus).length,
  'threeYuan entries:', Object.values(out.qimen.threeYuanTable)
    .reduce((a, o) => a + Object.keys(o).length, 0));
console.log('qimen mysticdoors doors:', Object.keys(out.qimen.doors).length,
  'stars:', Object.keys(out.qimen.stars).length,
  'hours:', Object.keys(out.qimen.hours).length,
  'formations:', Object.keys(out.qimen.formations).length + 'x' + Object.keys(out.qimen.formations.jia).length,
  'structures:', Object.keys(out.qimen.structures).length,
  'pillarOrdering:', out.qimen.pillarOrdering.length,
  '\n  usefulGod topics:', Object.keys(out.qimen.usefulGodSymbols).length,
  'displayTerminology conventions:', Object.keys(out.qimen.displayTerminology).length);
console.log('library compendium keys:', Object.keys(out.library.compendium).length,
  'titles:', Object.keys(out.library.titles).length,
  'relationship sections:', out.library.relationships.length,
  'yinYang pairs:', out.library.yinYang.length,
  'heavenEarthMan lists:', Object.values(out.library.heavenEarthMan)
    .map((l) => l.length).join('/'),
  'tutorials:', out.library.tutorials.length,
  'tutorial pages:', out.library.tutorials.reduce((a, t) => a + t.pages.length, 0));
