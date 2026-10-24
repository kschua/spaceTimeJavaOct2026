import { pathToFileURL } from 'node:url';
import { DateTime } from 'luxon';
const ROOT = 'C:/Temp/myTmp/bundle-sources/src/src/lib/cmtools';
const FourPillars = await import(pathToFileURL(ROOT + '/Plotters/FourPillars/index.js').href);
const EightWords = await import(pathToFileURL(ROOT + '/EightWords.js').href);
const strip = (v) => {
  if (Array.isArray(v)) return v.map(strip);
  if (v && typeof v === 'object') { const o = {}; for (const k of Object.keys(v)) { if (k === '$icon') continue; const x = strip(v[k]); if (typeof x !== 'function' && x !== undefined) o[k] = x; } return o; }
  return typeof v === 'function' ? undefined : v;
};
const iso = '1990-05-15T14:30:00+08:00';
for (const [label, opts] of [
  ['default', {}],
  ['weights', {showWeights:true}],
  ['luck', {showLuck:true}],
  ['full', {showLuck:true, showWeights:true}],
]) {
  const c = FourPillars.plot({ time: DateTime.fromISO(iso,{setZone:true}), ...opts });
  if (opts.showWeights) EightWords.getSummary(c, 0.1);
  if (label === 'full') EightWords.extendAuxDataChart(c);
  const s = JSON.stringify(strip(JSON.parse(JSON.stringify(c))));
  console.log(label, s.length);
}
// projected size
const c = FourPillars.plot({ time: DateTime.fromISO(iso,{setZone:true}), showLuck:true, showWeights:true });
EightWords.getSummary(c, 0.1); EightWords.extendAuxDataChart(c);
const proj = (x) => {
  const projPillar = (p) => {
    if (!p) return null;
    const out = { name: p.name, jiaGroup: p.jiaGroup, voidStars: p.voidStars,
      stem: p.stem.name, branch: p.branch.name,
      branchStems: p.branch.stems ? Object.fromEntries(Object.entries(p.branch.stems).map(([k,v]) => [k, v ? v.name : null])) : undefined,
      branchHidden: p.branch.hiddenStems ? p.branch.hiddenStems.map(h => ({stem:h.stem, god:h.god, core:h.core})) : undefined,
    };
    for (const k of ['naYin','stage','interactions','stars','annualStars','specialMatch']) if (p[k] !== undefined) out[k] = p[k];
    if (p.stars) out.stars = p.stars.map(s => ({name:s.name, rating:s.rating, ref:s.ref}));
    if (p.annualStars) out.annualStars = p.annualStars.map(s => ({name:s.name, rating:s.rating, ref:s.ref}));
    if (p.stage) out.stage = {pillar: p.stage.pillar && p.stage.pillar.name, dm: p.stage.dm && p.stage.dm.name};
    return out;
  };
  return {
    info: x.info,
    luck: x.luck.map(l => ({stem: l.pillar.stem.name, branch: l.pillar.branch.name, time: l.time})),
    pillars: Object.fromEntries(Object.entries(x.pillars).map(([k,p]) => [k, projPillar(p)])),
    meta: x.meta,
  };
};
const ps = JSON.stringify(proj(strip(JSON.parse(JSON.stringify(c)))));
console.log('projected full:', ps.length);
console.log('meta size:', JSON.stringify(c.meta).length, 'info:', JSON.stringify(c.info).length);
