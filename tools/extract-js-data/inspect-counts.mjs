import { pathToFileURL } from 'node:url';
import { DateTime } from 'luxon';
const ROOT = 'C:/Temp/myTmp/bundle-sources/src/src/lib/cmtools';
const FourPillars = await import(pathToFileURL(ROOT + '/Plotters/FourPillars/index.js').href);
const iso = '1990-05-15T14:30:00+08:00';
const chart = FourPillars.plot({ time: DateTime.fromISO(iso, { setZone: true }), showLuck: true, showWeights: true });
console.log('RN counts:', JSON.stringify(chart.meta.counts));
console.log('RN usefulGods:', JSON.stringify(chart.meta.usefulGods));
console.log('RN stats:', JSON.stringify(chart.meta.stats));
// inspect pillars hidden stems as seen by calcCounts
for (const k of ['year','month','day','hour']) {
  const p = chart.pillars[k];
  console.log(k, p.stem.name, p.branch.name, 'hidden:', p.branch._hiddenStemsArr, 'hsDict:', JSON.stringify(p.branch._hiddenStemsDict));
}
