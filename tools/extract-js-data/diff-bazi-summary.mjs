import { pathToFileURL } from 'node:url';
import { DateTime } from 'luxon';
const ROOT = 'C:/Temp/myTmp/bundle-sources/src/src/lib/cmtools';
const FourPillars = await import(pathToFileURL(ROOT + '/Plotters/FourPillars/index.js').href);
const EightWords = await import(pathToFileURL(ROOT + '/EightWords.js').href);
const strip = (v) => {
  if (Array.isArray(v)) return v.map(strip);
  if (v && typeof v === 'object') { const o = {}; for (const k of Object.keys(v)) { if (k === '$icon') continue; const x = strip(v[k]); if (typeof x !== 'function') o[k] = x; } return o; }
  return typeof v === 'function' ? undefined : v;
};
const isIso = (s) => typeof s === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?([+-]\d{2}:\d{2}|Z)?$/.test(s);
const norm = (s) => isIso(s) ? DateTime.fromISO(s, {setZone:true}).toMillis() : s;
function diffs(a, b, path = '', out = []) {
  if (a === undefined) { if (b !== undefined) out.push({path, kind:'java-only', b}); return out; }
  if (b === undefined) { out.push({path, kind:'rn-only', a}); return out; }
  if (typeof a === 'number' || typeof b === 'number') { if (Math.abs(Number(a)-Number(b)) > 1e-9) out.push({path, kind:'num', a, b}); return out; }
  if (typeof a === 'string' && typeof b === 'string') { if (norm(a) !== norm(b)) out.push({path, kind:'str', a, b}); return out; }
  if (Array.isArray(a)) { if (!Array.isArray(b)) { out.push({path, kind:'type', a:'array', b:typeof b}); return out; } if (a.length !== b.length) out.push({path, kind:'len', a:a.length, b:b.length}); for (let i=0;i<Math.min(a.length,b.length);i++) diffs(a[i],b[i],path+'['+i+']',out); return out; }
  if (a && typeof a === 'object') { if (!b || typeof b !== 'object' || Array.isArray(b)) { out.push({path, kind:'type', a:'object', b:typeof b}); return out; } for (const k of new Set([...Object.keys(a), ...Object.keys(b)])) diffs(a[k],b[k],path+'.'+k,out); return out; }
  if (a !== b) out.push({path, kind:'val', a, b});
  return out;
}
const iso = '1990-05-15T14:30:00+08:00';
const rnRaw = FourPillars.plot({ time: DateTime.fromISO(iso,{setZone:true}), showLuck: true, showWeights: true });
EightWords.extendAuxDataChart(rnRaw);
const rn = strip(JSON.parse(JSON.stringify(rnRaw)));
rn.luck = rn.luck.map(l => ({ pillar: { stem: { name: l.pillar.stem.name }, branch: { name: l.pillar.branch.name } }, time: l.time }));
const java = await (await fetch('http://localhost:8080/api/bazi?time=' + encodeURIComponent(iso) + '&extend=true')).json();
delete java.shensha;
java.luck = java.luck.map(l => ({ pillar: { stem: { name: l.pillar.stem.name }, branch: { name: l.pillar.branch.name } }, time: l.time }));
const d = diffs(rn, java);
const kinds = {};
d.forEach(x => kinds[x.kind] = (kinds[x.kind]||0)+1);
console.log('total:', d.length, kinds);
console.log('--- non java-only diffs:');
d.filter(x => x.kind !== 'java-only').forEach(x => console.log(' ', x.path, x.kind, JSON.stringify(x.a)?.slice(0,80), '|', JSON.stringify(x.b)?.slice(0,80)));
console.log('--- java-only paths (unique prefixes):');
const prefixes = new Set(d.filter(x=>x.kind==='java-only').map(x => x.path.replace(/\.(year|month|day|hour|minute|conception|life)\./g,'.<p>.').replace(/\[\d+\]/g,'[]')));
[...prefixes].slice(0,60).forEach(p => console.log(' ', p));
