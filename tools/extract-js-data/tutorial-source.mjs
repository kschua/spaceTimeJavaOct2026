// Shared extraction for the RN tutorial screens (components/Other/Tutorial*.js).
//
// The four tutorials are `react-native-onboarding-swiper` page arrays embedded
// in JSX, so they cannot be imported as data. This module scans the `pages={[`
// block with the same quote-aware brace balancer used for the Library tables
// and extracts each page's image URI, title and subtitle verbatim - nothing is
// transcribed by hand.
//
//   const tutorials = extractTutorials(ROOT);
//   -> [{ key, title, pages: [{ image, title, subtitle }] }]

import fs from 'node:fs';
import { balance } from './library-source.mjs';

const FILES = [
  ['bazi', 'TutorialBazi.js'],
  ['calendar', 'TutorialCalendar.js'],
  ['hexagrams', 'TutorialHexagrams.js'],
  ['qimen', 'TutorialQiMen.js'],
];

const unescape = (s) => s.replace(/\\(['"\\nrt])/g,
  (_, c) => ({ "'": "'", '"': '"', '\\': '\\', n: '\n', r: '\r', t: '\t' }[c]));

export function extractTutorials(cmtoolsRoot) {
  const dir = cmtoolsRoot.replace(/\/lib\/cmtools\/?$/, '/components/Other');

  return FILES.map(([key, file]) => {
    const src = fs.readFileSync(dir + '/' + file, 'utf8');
    const nav = src.match(/navigationOptions\s*=\s*\{\s*title:\s*'([^']*)'/);

    const marker = 'pages={[';
    const at = src.indexOf(marker);
    if (at < 0) throw new Error('pages block not found in ' + file);
    const block = balance(src, at + marker.length - 1);

    const pages = [];
    for (let i = 1; i < block.length - 1; i++) {
      if (block[i] !== '{') continue;
      const page = balance(block, i);
      i += page.length - 1;

      const image = page.match(/source=\{\{\s*uri:\s*'([^']+)'\s*\}\}/);
      const title = page.match(/\btitle:\s*'((?:[^'\\]|\\.)*)'/);
      const subtitle = page.match(/\bsubtitle:\s*'((?:[^'\\]|\\.)*)'/);
      pages.push({
        image: image ? image[1] : null,
        title: title ? unescape(title[1]) : '',
        subtitle: subtitle ? unescape(subtitle[1]) : '',
      });
    }

    return { key, title: nav ? unescape(nav[1]) : key, pages };
  });
}
