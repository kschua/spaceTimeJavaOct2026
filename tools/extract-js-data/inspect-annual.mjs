import { pathToFileURL } from 'node:url';
const ROOT = 'C:/Temp/myTmp/bundle-sources/src/src/lib/cmtools';
const ShenSha = await import(pathToFileURL(ROOT + '/ShenSha.js').href);
for (const [b, arr] of Object.entries(ShenSha.annualStarAux.zi)) {
  const ratings = arr.map(x => x.rating);
  const ties = ratings.some((r,i) => ratings.indexOf(r) !== i);
  console.log(b, ratings.join(','), ties ? 'TIES' : '');
}
