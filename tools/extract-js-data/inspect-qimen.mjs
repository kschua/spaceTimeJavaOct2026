// Shape inspection for the QiMap / MysticDoors tables (phase 4b groundwork).
import { pathToFileURL } from 'node:url';

const ROOT = (process.env.BUNDLE_ROOT || 'C:/Temp/myTmp/bundle-sources/src/src/lib/cmtools').replace(/\/$/, '');
const MD = await import(pathToFileURL(ROOT + '/MysticDoors.js').href);
const Ordinals = await import(pathToFileURL(ROOT + '/Ordinals.js').href);
const WW = await import(pathToFileURL(ROOT + '/WindWater.js').href);

console.log('exports:', Object.keys(MD).join(', '));
console.log('doors keys:', Object.keys(MD.doors).join(','));
console.log('door open keys:', Object.keys(MD.doors.open).join(','));
console.log('door open strength:', JSON.stringify(MD.doors.open.strength));
console.log('door open specialInteraction:', JSON.stringify(MD.doors.open.specialInteraction));
console.log('door open palaceInteraction:', JSON.stringify(MD.doors.open.palaceInteraction));
console.log('door open desc:', JSON.stringify(MD.doors.open.desc));
console.log('stars keys:', Object.keys(MD.stars).join(','));
console.log('star grass keys:', Object.keys(MD.stars.grass).join(','));
console.log('star grass specialInteraction:', JSON.stringify(MD.stars.grass.specialInteraction));
console.log('star grass palaceInteraction:', JSON.stringify(MD.stars.grass.palaceInteraction));
console.log('star grass strength:', JSON.stringify(MD.stars.grass.strength));
console.log('spirits keys:', Object.keys(MD.spirits).join(','));
console.log('spirit chief keys:', Object.keys(MD.spirits.chief).join(','));
console.log('spirit chief suitable:', JSON.stringify(MD.spirits.chief.suitable));
console.log('victories:', JSON.stringify(MD.victories));
console.log('formations rows:', Object.keys(MD.formations).length, 'cols:', Object.keys(MD.formations.jia).join(','));
console.log('formations sample jia/jia:', JSON.stringify(MD.formations.jia.jia), '| jia/yi:', JSON.stringify(MD.formations.jia.yi));
const sk = Object.keys(MD.structures);
console.log('structures count:', sk.length);
console.log('structures sample:', JSON.stringify(MD.structures[sk[0]], null, 1).slice(0, 500));
console.log('hours keys:', Object.keys(MD.hours).join(','));
console.log('hours jia keys:', Object.keys(MD.hours.jia).slice(0, 12).join(','), '... count', Object.keys(MD.hours.jia).length);
console.log('hours jia open:', JSON.stringify(MD.hours.jia.open));
console.log('usefulGodSymbols keys:', Object.keys(MD.usefulGodSymbols).join(','));
console.log('pillarOrdering len:', Ordinals.pillarOrdering.length, 'first', JSON.stringify(Ordinals.pillarOrdering[0]), 'idx60=', JSON.stringify(Ordinals.pillarOrdering[60]));
// qiMenFengShuiMap value range
let min = Infinity, max = -Infinity, bad = 0;
for (const ju of Object.keys(WW.qiMenFengShuiMap)) {
  for (const sec of Object.keys(WW.qiMenFengShuiMap[ju])) {
    for (const dir of Object.keys(WW.qiMenFengShuiMap[ju][sec])) {
      const v = WW.qiMenFengShuiMap[ju][sec][dir];
      if (typeof v !== 'number') { bad++; continue; }
      min = Math.min(min, v); max = Math.max(max, v);
    }
  }
}
console.log('qiMenFengShuiMap value range:', min, '-', max, 'non-numbers:', bad);
