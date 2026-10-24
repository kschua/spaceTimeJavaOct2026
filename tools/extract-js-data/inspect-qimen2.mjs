// Deeper shape inspection for the QiMap meta layer (phase 4b).
import { pathToFileURL } from 'node:url';

const ROOT = (process.env.BUNDLE_ROOT || 'C:/Temp/myTmp/bundle-sources/src/src/lib/cmtools').replace(/\/$/, '');
const MD = await import(pathToFileURL(ROOT + '/MysticDoors.js').href);

const keysOf = (o) => Object.keys(o).join(',');
console.log('== doors ==');
for (const [n, d] of Object.entries(MD.doors)) {
  console.log(n, '| keys:', keysOf(d), '| suitable:', JSON.stringify(d.suitable), '| unsuitable:', JSON.stringify(d.unsuitable),
    '| desc:', JSON.stringify(d.desc), '| strengthLen:', Object.keys(d.strength).length,
    '| strength:', Object.values(d.strength).join(','));
}
console.log('== stars ==');
for (const [n, s] of Object.entries(MD.stars)) {
  console.log(n, '| keys:', keysOf(s), '| suitable:', JSON.stringify(s.suitable), '| unsuitable:', JSON.stringify(s.unsuitable),
    '| strength palaces:', keysOf(s.strength), '| strength:', Object.values(s.strength).join(','));
}
console.log('== spirits ==');
for (const [n, s] of Object.entries(MD.spirits)) {
  console.log(n, '| keys:', keysOf(s), '| suitable:', JSON.stringify(s.suitable), '| unsuitable:', JSON.stringify(s.unsuitable));
}
console.log('== hours ==');
for (const [s, doors] of Object.entries(MD.hours)) {
  console.log(s, '| doors:', keysOf(doors), '| open:', JSON.stringify(doors.open), '| life:', JSON.stringify(doors.life));
}
console.log('== formations ==');
const f = MD.formations;
console.log('rows:', keysOf(f), 'cols:', keysOf(f.jia));
console.log('jia.jia keys:', keysOf(f.jia.jia));
console.log('gui.gui:', JSON.stringify(f.gui.gui));
// full scan: every formation has same keys / rating types
let fkeys = new Set(); let ratings = new Set();
for (const a of Object.keys(f)) for (const b of Object.keys(f[a])) { fkeys.add(keysOf(f[a][b])); ratings.add(f[a][b].rating); }
console.log('distinct formation key sets:', [...fkeys], 'ratings:', [...ratings].sort());
console.log('== structures ==');
const st = MD.structures;
console.log('count:', Object.keys(st).length);
let skeys = new Set(); let ruleShapes = new Set(); let ruleTypes = new Set();
for (const [k, v] of Object.entries(st)) {
  const own = Object.keys(v).join(',');
  skeys.add(own);
  console.log('-', k, '| rating', v.rating, '| abbr', v.abbr, '| name', JSON.stringify(v.name), '| chinese', v.chinese, '| pinyin', v.pinyin, '| almanacDefault', v.almanacDefault, '| match:', JSON.stringify(v.match));
  for (const rule of v.match) {
    ruleShapes.add(JSON.stringify(rule));
    for (const [fk, fv] of Object.entries(rule)) ruleTypes.add(fk + ':' + typeof fv);
  }
}
console.log('distinct structure key sets:', [...skeys]);
console.log('rule field types:', [...ruleTypes].sort());
console.log('distinct rules count:', ruleShapes.size);
console.log('== victories ==');
console.log(JSON.stringify(MD.victories));
