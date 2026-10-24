import { pathToFileURL } from 'node:url';
const ROOT = 'C:/Temp/myTmp/bundle-sources/src/src/lib/cmtools';
const ShenSha = await import(pathToFileURL(ROOT + '/ShenSha.js').href);
for (const b of ['si', 'chen']) {
  const arr = ShenSha.annualStarAux.zi[b];
  console.log('branch', b, '->', arr.map(x => ({ name: x.name, chinese: x.chinese, rating: x.rating, ref: x.ref, icon: x.icon, keys: Object.keys(x).slice(0, 8) })));
}
