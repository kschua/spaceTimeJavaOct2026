import { pathToFileURL } from 'node:url';
const ROOT = 'C:/Temp/myTmp/bundle-sources/src/src/lib/cmtools';
const ShenSha = await import(pathToFileURL(ROOT + '/ShenSha.js').href);
const m = ShenSha.monthBranchAux;
console.log('keys', Object.keys(m).join(','));
console.log('thrivingDay', JSON.stringify(m.thrivingDay));
