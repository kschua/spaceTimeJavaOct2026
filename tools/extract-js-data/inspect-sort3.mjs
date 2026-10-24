import { pathToFileURL } from 'node:url';
const ROOT = 'C:/Temp/myTmp/bundle-sources/src/src/lib/cmtools';
const ShenSha = await import(pathToFileURL(ROOT + '/ShenSha.js').href);
const cmp = (a, b) => (a.rating < b.rating) ? 1 : -1;
for (const b of ['zi','si','wu','chen']) {
  const arr = ShenSha.annualStarAux.zi[b].slice();
  const once = arr.slice().sort(cmp).map(x => x.name + ':' + x.rating);
  const twice = arr.slice().sort(cmp).sort(cmp).map(x => x.name + ':' + x.rating);
  console.log(b);
  console.log(' pristine', arr.map(x => x.name + ':' + x.rating).join(','));
  console.log(' once    ', once.join(','));
  console.log(' twice   ', twice.join(','));
}
