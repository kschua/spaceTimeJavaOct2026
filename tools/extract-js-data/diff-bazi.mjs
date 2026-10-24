import { pathToFileURL } from 'node:url';
import { DateTime } from 'luxon';
const ROOT = 'C:/Temp/myTmp/bundle-sources/src/src/lib/cmtools';
const FourPillars = await import(pathToFileURL(ROOT + '/Plotters/FourPillars/index.js').href);
const EightWords = await import(pathToFileURL(ROOT + '/EightWords.js').href);

const strip = (v) => {
  if (Array.isArray(v)) return v.map(strip);
  if (v && typeof v === 'object') {
    const o = {};
    for (const k of Object.keys(v)) {
      if (k === '$icon') continue;
      const val = strip(v[k]);
      if (typeof val !== 'function') o[k] = val;
    }
    return o;
  }
  if (typeof v === 'function') return undefined;
  return v;
};
const ISO_RE = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?([+-]\d{2}:\d{2}|Z)?$/;
// strip milliseconds, then a zero seconds field, then normalize Z -> +00:00
const normIso = (s) => (typeof s === 'string' && ISO_RE.test(s)
  ? s.replace(/\.\d+(?=[+-]|Z|$)/, '').replace(/(:\d{2}):00(?=[+-]|Z|$)/, '$1').replace(/Z$/, '+00:00')
  : s);

function diffs(a, b, path = '', out = []) {
  if (a === null || a === undefined) { if (!(b === null || b === undefined)) out.push(path + ': rn=' + String(JSON.stringify(a)).slice(0,70) + ' | java=' + String(JSON.stringify(b)).slice(0,70)); return out; }
  if (b === null || b === undefined) { out.push(path + ': rn=' + String(JSON.stringify(a)).slice(0,70) + ' | java=' + String(JSON.stringify(b)).slice(0,70)); return out; }
  if (typeof a === 'number' || typeof b === 'number') { if (Math.abs(Number(a) - Number(b)) > 1e-9) out.push(path + ': rn=' + a + ' java=' + b); return out; }
  if (typeof a === 'string' && typeof b === 'string') { if (normIso(a) !== normIso(b)) out.push(path + ': rn=' + a + ' | java=' + b); return out; }
  if (Array.isArray(a)) {
    if (!Array.isArray(b)) { out.push(path + ': rn array, java ' + typeof b); return out; }
    if (a.length !== b.length) out.push(path + ': length rn=' + a.length + ' java=' + b.length);
    for (let i = 0; i < Math.min(a.length, b.length); i++) diffs(a[i], b[i], path + '[' + i + ']', out);
    return out;
  }
  if (typeof a === 'object') {
    if (typeof b !== 'object' || Array.isArray(b)) { out.push(path + ': type mismatch'); return out; }
    const keys = new Set([...Object.keys(a), ...Object.keys(b)]);
    for (const k of keys) diffs(a[k], b[k], path + '.' + k, out);
    return out;
  }
  if (a !== b) out.push(path + ': rn=' + JSON.stringify(a) + ' java=' + JSON.stringify(b));
  return out;
}

const iso = '1990-05-15T14:30:00+08:00';
const rnRaw = FourPillars.plot({ time: DateTime.fromISO(iso, { setZone: true }), showLuck: true, showWeights: true });
const rn = strip(JSON.parse(JSON.stringify(rnRaw)));
// Java adds richer luck pillars (god/naYin/hiddenStems/...); compare the RN shape only.
rn.luck = rn.luck.map(l => ({ pillar: { stem: { name: l.pillar.stem.name }, branch: { name: l.pillar.branch.name } }, time: l.time }));
const java = await (await fetch('http://localhost:8080/api/bazi?time=' + encodeURIComponent(iso) + '&extend=false')).json();
delete java.shensha;
java.luck = java.luck.map(l => ({ pillar: { stem: { name: l.pillar.stem.name }, branch: { name: l.pillar.branch.name } }, time: l.time }));
const classify = (d) => {
  const javaOnly = d.filter(x => x.includes('rn=undefined'));
  const rnOnly = d.filter(x => x.includes('java=undefined'));
  const rest = d.filter(x => !javaOnly.includes(x) && !rnOnly.includes(x));
  return { javaOnly, rnOnly, rest };
};
let d = diffs(rn, java);
let c = classify(d);
console.log('non-extended diffs:', d.length, '| java-only:', c.javaOnly.length, '| rn-only:', c.rnOnly.length, '| value:', c.rest.length);
c.rest.forEach(x => console.log('  VALUE', x));

// extended: RN mutates and returns undefined
const rnExtRaw = FourPillars.plot({ time: DateTime.fromISO(iso, { setZone: true }), showLuck: true, showWeights: true });
EightWords.extendAuxDataChart(rnExtRaw);
const rnExt = strip(JSON.parse(JSON.stringify(rnExtRaw)));
rnExt.luck = rnExt.luck.map(l => ({ pillar: { stem: { name: l.pillar.stem.name }, branch: { name: l.pillar.branch.name } }, time: l.time }));
const javaExt = await (await fetch('http://localhost:8080/api/bazi?time=' + encodeURIComponent(iso) + '&extend=true')).json();
delete javaExt.shensha;
javaExt.luck = javaExt.luck.map(l => ({ pillar: { stem: { name: l.pillar.stem.name }, branch: { name: l.pillar.branch.name } }, time: l.time }));
const d2 = diffs(rnExt, javaExt);
const c2 = classify(d2);
console.log('extended diffs:', d2.length, '| java-only:', c2.javaOnly.length, '| rn-only:', c2.rnOnly.length, '| value:', c2.rest.length);
c2.rest.forEach(x => console.log('  VALUE', x));
