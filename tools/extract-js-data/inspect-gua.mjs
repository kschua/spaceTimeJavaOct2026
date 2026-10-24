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
const c = FourPillars.plot({ time: DateTime.fromISO('1990-05-15T14:30:00+08:00',{setZone:true}), showLuck:true, showWeights:true });
EightWords.getSummary(c, 0.1); EightWords.extendAuxDataChart(c);
const j = strip(JSON.parse(JSON.stringify(c)));
console.log('gua:', JSON.stringify(j.meta.gua));
console.log('info:', JSON.stringify(j.info));
console.log('stage serialized:', JSON.stringify(j.pillars.year.stage).length);
console.log('naYin:', JSON.stringify(j.pillars.year.naYin));
console.log('year.interactions:', JSON.stringify(j.pillars.year.interactions));
console.log('specialMatch:', JSON.stringify(j.pillars.year.specialMatch));
console.log('pillar.name:', j.pillars.year.name);
console.log('hiddenStems:', JSON.stringify(j.pillars.year.branch.hiddenStems));
console.log('branch.stems names:', JSON.stringify(Object.fromEntries(Object.entries(j.pillars.year.branch.stems).map(([k,v])=>[k, v?v.name:null]))));
console.log('stem subset:', JSON.stringify({name:j.pillars.year.stem.name,chinese:j.pillars.year.stem.chinese,pinyin:j.pillars.year.stem.pinyin,element:j.pillars.year.stem.element,polarity:j.pillars.year.stem.polarity,number:j.pillars.year.stem.number,god:j.pillars.year.stem.god,aspect:j.pillars.year.stem.aspect,aspc:j.pillars.year.stem.aspc.name,gdd:j.pillars.year.stem.gdd.name,elm:j.pillars.year.stem.elm.name}));
console.log('branch subset:', JSON.stringify({name:j.pillars.year.branch.name,chinese:j.pillars.year.branch.chinese,pinyin:j.pillars.year.branch.pinyin,animalName:j.pillars.year.branch.animalName,element:j.pillars.year.branch.element,polarity:j.pillars.year.branch.polarity,number:j.pillars.year.branch.number,season:j.pillars.year.branch.season,group:j.pillars.year.branch.group,direction:j.pillars.year.branch.direction,palace:j.pillars.year.branch.palace,month:j.pillars.year.branch.month,hour:j.pillars.year.branch.hour,body:j.pillars.year.branch.body,ssn:j.pillars.year.branch.ssn}));
