import { pathToFileURL } from 'node:url';
const ROOT = 'C:/Temp/myTmp/bundle-sources/src/src/lib/cmtools';
const Ordinals = await import(pathToFileURL(ROOT + '/Ordinals.js').href);
for (const b of Object.keys(Ordinals.branches)) {
  const arr = Ordinals.branches[b]._hiddenStemsArr;
  console.log(b, arr.map(x => x === null ? 'null' : typeof x + ':' + (typeof x === 'object' ? x.name : x)).join(', '));
}
