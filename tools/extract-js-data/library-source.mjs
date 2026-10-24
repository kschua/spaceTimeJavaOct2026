// Shared extraction for the RN Library/Compendium phase.
//
// Unlike the cmtools data modules (which are imported and dumped), the
// YinYangRef / HeavenEarthMan word lists and the RelationshipsTable sections
// live inline inside React components, so they are brace-extracted from the
// component sources - the same technique verify-yijing.mjs uses. Everything is
// copied verbatim; nothing is transcribed by hand.
//
//   const lib = extractLibrarySources(ROOT);
//   lib.relationships -> 10 [{title, key, data}] sections
//   lib.yinYang       -> 18 [positive, negative] pairs
//   lib.heavenEarthMan-> {heaven: [...], earth: [...], man: [...]}

import fs from 'node:fs';

export const balance = (src, from) => {
  const open = src[from];
  const close = open === '[' ? ']' : '}';
  let depth = 0;
  let inStr = null;
  for (let i = from; i < src.length; i++) {
    const c = src[i];
    if (inStr) {
      if (c === '\\') { i++; continue; }
      if (c === inStr) inStr = null;
      continue;
    }
    if (c === '"' || c === "'" || c === '`') { inStr = c; continue; }
    if (c === open) depth++;
    else if (c === close) {
      depth--;
      if (depth === 0) return src.slice(from, i + 1);
    }
  }
  throw new Error('unbalanced literal at ' + from);
};

// anchor must end with the opening bracket of the literal, e.g. 'const data = ['
export const extractLiteral = (src, anchor) => {
  const at = src.indexOf(anchor);
  if (at < 0) throw new Error('anchor not found: ' + JSON.stringify(anchor));
  const lit = balance(src, at + anchor.length - 1);
  return new Function('return (' + lit + ')')();
};

export function extractLibrarySources(cmtoolsRoot) {
  const infoDir = cmtoolsRoot.replace(/\/lib\/cmtools\/?$/, '/components/Info');
  const read = (f) => fs.readFileSync(infoDir + '/' + f, 'utf8');

  const relationships = extractLiteral(read('RelationshipsTable.js'), 'const data = [');
  const yinYang = extractLiteral(read('YinYangRef.js'), 'const words = [');
  const heavenEarthMan = extractLiteral(read('HeavenEarthMan.js'), 'const words = {');

  return { relationships, yinYang, heavenEarthMan };
}
