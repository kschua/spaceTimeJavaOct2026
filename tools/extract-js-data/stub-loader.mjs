// Node module-customization hooks that let us import the original RN (Metro)
// source modules unchanged:
//   - 'lodash' / 'luxon' are replaced by small stubs (the data modules never
//     call them at module-evaluation time),
//   - './Assets' (image imports) is replaced by a deep proxy so `$icon` fields
//     resolve without touching any real image files.
//
// Usage (from a bootstrap that calls module.register, see register-hooks.mjs):
//   node --import ./register-hooks.mjs ./dump.mjs

import fs from 'node:fs';

const LODASH_SRC = `
const cloneDeep = (o) => (o === undefined ? o : JSON.parse(JSON.stringify(o)));
const isEqual = (a, b) => JSON.stringify(a) === JSON.stringify(b);
const invert = (o) => { const r = {}; for (const k in o) r[String(o[k])] = k; return r; };
const mapValues = (o, f) => { const r = {}; for (const k in o) r[k] = f(o[k], k); return r; };
const first = (a) => (a == null || a.length === 0 ? undefined : a[0]);
const last = (a) => (a == null || a.length === 0 ? undefined : a[a.length - 1]);
const keys = (o) => Object.keys(o);
const values = (o) => Object.values(o);
const isArray = Array.isArray;
const compact = (a) => a.filter(Boolean);
const uniq = (a) => [...new Set(a)];
const flatten = (a) => a.flat();
const flatMap = (a, f) => a.flatMap(f);
const intersection = (...as) => (as.length ? as.reduce((acc, a) => acc.filter((v) => a.includes(v))) : []);
const union = (...as) => [...new Set(as.flat())];
const difference = (a, b) => a.filter((x) => !b.includes(x));
const includes = (a, v) => a.includes(v);
const map = (a, f) => a.map(f);
const filter = (a, f) => a.filter(f);
const reject = (a, f) => a.filter((v) => !f(v));
const reduce = (a, f, init) => a.reduce(f, init);
const find = (a, f) => a.find(f);
const findIndex = (a, f) => a.findIndex(f);
const isObject = (v) => v !== null && typeof v === 'object';
const isEmpty = (o) => o == null || (typeof o === 'object' && Object.keys(o).length === 0);
const sortBy = (a, it) => {
  const itf = typeof it === 'function' ? it : (x) => x[it];
  return [...a].sort((x, y) => { const fx = itf(x); const fy = itf(y); return fx < fy ? -1 : fx > fy ? 1 : 0; });
};
const orderBy = (a, its, dirs) => {
  const fs2 = [].concat(its).map((it) => (typeof it === 'function' ? it : (x) => x[it]));
  const ds = [].concat(dirs || 'asc');
  return [...a].sort((x, y) => {
    for (let i = 0; i < fs2.length; i++) {
      const fx = fs2[i](x); const fy = fs2[i](y);
      const d = ds[i] === 'desc' ? -1 : 1;
      if (fx < fy) return -1 * d;
      if (fx > fy) return 1 * d;
    }
    return 0;
  });
};
const sum = (a) => a.reduce((x, y) => x + y, 0);
const round = (n, p) => { const m = 10 ** (p || 0); return Math.round(n * m) / m; };
const range = (n) => [...Array(n).keys()];
const keysIn = (o) => Object.keys(o);
const pick = (o, ks) => ks.reduce((r, k) => { if (k in o) r[k] = o[k]; return r; }, {});
const omit = (o, ks) => { const r = { ...o }; for (const k of ks) delete r[k]; return r; };
const get = (o, p, d) => { const v = String(p).split('.').reduce((a, k) => (a == null ? undefined : a[k]), o); return v === undefined ? d : v; };
const set = (o, p, v) => { const ks = String(p).split('.'); let c = o; for (let i = 0; i < ks.length - 1; i++) { if (c[ks[i]] == null) c[ks[i]] = {}; c = c[ks[i]]; } c[ks[ks.length - 1]] = v; return o; };
const has = (o, p) => get(o, p) !== undefined;
const groupBy = (a, f) => { const r = {}; for (const v of a) { const k = f(v); (r[k] = r[k] || []).push(v); } return r; };
const toPairs = (o) => Object.entries(o);
const fromPairs = (p) => Object.fromEntries(p);
const isString = (v) => typeof v === 'string';
const isNumber = (v) => typeof v === 'number';
const isFunction = (v) => typeof v === 'function';
const isUndefined = (v) => v === undefined;
const isNull = (v) => v === null;
const initial = (a) => a.slice(0, -1);
const tail = (a) => a.slice(1);
const without = (a, ...vs) => a.filter((x) => !vs.includes(x));
const clone = (o) => (o === undefined ? o : JSON.parse(JSON.stringify(o)));
const stub = () => { throw new Error('lodash stub: unimplemented function'); };
const extra = new Proxy({}, { get: (t, p) => stub });
const lodash = new Proxy({ cloneDeep, isEqual, invert, mapValues, first, last, keys, values, isArray, compact, uniq, flatten, flatMap, intersection, union, difference, includes, map, filter, reject, reduce, find, findIndex, isObject, isEmpty, sortBy, orderBy, sum, round, range, keysIn, pick, omit, get, set, has, groupBy, toPairs, fromPairs, isString, isNumber, isFunction, isUndefined, isNull, initial, tail, without, clone }, {
  get: (t, p) => (p in t ? t[p] : (p === 'default' ? undefined : stub())),
});
export default lodash;
`;

const LUXON_SRC = `
class DateTimeStub {
  constructor() { throw new Error('luxon stub: DateTime instantiated at module-evaluation time'); }
}
export const DateTime = DateTimeStub;
export default { DateTime: DateTimeStub };
`;

const ASSETS_SRC = `
function makeLeaf() {
  const fn = function () { return makeLeaf(); };
  return new Proxy(fn, {
    get: (t, p) => (p === Symbol.toPrimitive ? () => '' : makeLeaf()),
    apply: () => makeLeaf(),
  });
}
export const color = makeLeaf();
export const mono = makeLeaf();
export const icons = makeLeaf();
export const images = makeLeaf();
export default makeLeaf();
`;

const REACT_NATIVE_SRC = `
export const TextInputComponent = {};
export const Dimensions = { get: () => ({ width: 375, height: 667 }) };
export const Platform = { OS: 'web', select: (o) => o.web || o.default };
export const StyleSheet = { create: (o) => o, flatten: (o) => o };
export default {};
`;

const MATERIAL_COLOR_SRC = `
function makeLeaf() {
  const fn = function () { return makeLeaf(); };
  return new Proxy(fn, {
    get: (t, p) => (p === Symbol.toPrimitive ? () => '' : makeLeaf()),
    apply: () => makeLeaf(),
  });
}
export default makeLeaf();
export const BLUEGREY = makeLeaf();
export const GREY = makeLeaf();
`;

const MD5_SRC = `
export default function md5() {
  throw new Error('md5 stub: only QiMenAssist cache keys call this');
}
`;

// 'luxon' resolves to the vendored build extracted from the app bundle
// (vendor/luxon-real.mjs) whenever it is available, so rendered outputs use the
// app's own datetime semantics. The light stub remains as a fallback only.
const LUXON_REAL_URL = new URL('./vendor/luxon-real.mjs', import.meta.url).href;
const HAS_REAL_LUXON = fs.existsSync(new URL('./vendor/luxon.cjs', import.meta.url));

export async function resolve(specifier, context, nextResolve) {
  if (specifier === 'lodash') return { url: 'stub:lodash', shortCircuit: true };
  if (specifier === 'luxon') {
    if (HAS_REAL_LUXON) return { url: LUXON_REAL_URL, shortCircuit: true };
    return { url: 'stub:luxon', shortCircuit: true };
  }
  if (specifier === 'react-native') return { url: 'stub:react-native', shortCircuit: true };
  if (specifier === 'react-native-material-color') return { url: 'stub:material-color', shortCircuit: true };
  if (specifier === 'md5') return { url: 'stub:md5', shortCircuit: true };
  if (/(^|\/)Assets(\/index(\.js)?)?$/.test(specifier)) return { url: 'stub:assets', shortCircuit: true };
  try {
    return await nextResolve(specifier, context);
  } catch (err) {
    if (specifier.startsWith('.')) {
      // Metro resolution: extensionless files and directory index files.
      const candidates = [];
      if (!specifier.endsWith('.js')) candidates.push(specifier + '.js');
      candidates.push(specifier.replace(/\/$/, '') + '/index.js');
      for (const c of candidates) {
        try {
          return await nextResolve(c, context);
        } catch {
          /* try next candidate */
        }
      }
    }
    throw err;
  }
}

export async function load(url, context, nextLoad) {
  if (url === 'stub:lodash') return { format: 'module', source: LODASH_SRC, shortCircuit: true };
  if (url === 'stub:luxon') return { format: 'module', source: LUXON_SRC, shortCircuit: true };
  if (url === 'stub:assets') return { format: 'module', source: ASSETS_SRC, shortCircuit: true };
  if (url === 'stub:react-native') return { format: 'module', source: REACT_NATIVE_SRC, shortCircuit: true };
  if (url === 'stub:material-color') return { format: 'module', source: MATERIAL_COLOR_SRC, shortCircuit: true };
  if (url === 'stub:md5') return { format: 'module', source: MD5_SRC, shortCircuit: true };
  // Force ESM parsing for the extracted RN sources (they are Metro/Babel ESM
  // but live in a directory without a package.json "type": "module" marker).
  if (url.startsWith('file:') && /\/bundle-sources\//.test(decodeURIComponent(new URL(url).pathname))) {
    const source = fs.readFileSync(new URL(url), 'utf8');
    return { format: 'module', source, shortCircuit: true };
  }
  return nextLoad(url, context);
}
