import { pathToFileURL } from 'node:url';
import { DateTime } from 'luxon';
const ROOT = 'C:/Temp/myTmp/bundle-sources/src/src/lib/cmtools';
const FourPillars = await import(pathToFileURL(ROOT + '/Plotters/FourPillars/index.js').href);
const time = DateTime.fromISO('1990-05-15T14:30:00+08:00', { setZone: true });
const chart = FourPillars.plot({ time, showLuck: true, showWeights: true });
console.log('luck[0]:', JSON.stringify(chart.luck[0]));
console.log('luck[1] time:', chart.luck[1].time.toISO());
console.log('luck[1]:', JSON.stringify({ p: chart.luck[1].pillar, t: chart.luck[1].time.toISO() }));
const proj = {
  info: chart.info,
  pillars: Object.fromEntries(Object.entries(chart.pillars).map(([k, p]) => [k, {
    stem: p.stem.name, branch: p.branch.name, name: p.name, jiaGroup: p.jiaGroup, voidStars: p.voidStars,
    naYin: p.naYin, stage: p.stage, interactions: p.interactions, stars: p.stars, annualStars: p.annualStars,
    specialMatch: p.specialMatch,
  }])),
  luck: chart.luck.map(l => ({ pillar: [l.pillar[0], l.pillar[1]], time: l.time.toISO() })),
  meta: chart.meta,
};
console.log('projection size:', JSON.stringify(proj).length);
console.log('meta keys sizes:', Object.fromEntries(Object.entries(chart.meta).map(([k, v]) => [k, JSON.stringify(v).length])));
console.log('pillars size:', JSON.stringify(proj.pillars).length, 'info:', JSON.stringify(proj.info).length, 'luck:', JSON.stringify(proj.luck).length);
