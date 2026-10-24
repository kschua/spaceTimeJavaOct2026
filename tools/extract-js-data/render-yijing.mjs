// Renders Yi Jing golden outputs from the original RN modules:
//   - Hexes.order / horaryTable / pillars / line derivation
//   - Li Shee Yee calculation (logic copied verbatim from
//     components/YiJing/LiSheeYeeSelector.js; the letter conversion itself is
//     imported from lib/utils/strings.js)
//
// Usage:
//   cd tools/extract-js-data
//   node --import ./register-hooks.mjs ./render-yijing.mjs
//
// Env overrides (defaults shown):
//   BUNDLE_ROOT   = C:/Temp/myTmp/bundle-sources/src/src/lib/cmtools
//   LSY_FIXTURES  = ./fixtures-lisheeyee.json
//   OUT           = ../../bazi-spring/src/test/resources/golden/yijing.json
import fs from 'node:fs';
import { pathToFileURL } from 'node:url';
import { DateTime } from 'luxon';

const ROOT = process.env.BUNDLE_ROOT || 'C:/Temp/myTmp/bundle-sources/src/src/lib/cmtools';
const SRC = ROOT.replace(/\/lib\/cmtools\/?$/, '');
const LSY_FIXTURES = process.env.LSY_FIXTURES || './fixtures-lisheeyee.json';
const OUT = process.env.OUT || '../../bazi-spring/src/test/resources/golden/yijing.json';

const cmUrl = (rel) => pathToFileURL(ROOT.replace(/\/$/, '') + '/' + rel).href;
const srcUrl = (rel) => pathToFileURL(SRC.replace(/\/$/, '') + '/' + rel).href;

const Hexes = await import(cmUrl('Hexes.js'));
const Ordinals = await import(cmUrl('Ordinals.js'));
const strings = await import(srcUrl('lib/utils/strings.js'));
const FourPillars = await import(cmUrl('Plotters/FourPillars/index.js'));

// ---- component logic (HexagramSelector.js) --------------------------------

const findGuaByBinary = (binary) =>
  Object.values(Ordinals.guas).find((g) => g.binary === binary) || null;

const dataToInfo = (data) => {
  const totalBinary = data.join('');
  const topBinary = String(data[0]) + String(data[1]) + String(data[2]);
  const bottomBinary = String(data[3]) + String(data[4]) + String(data[5]);
  const topTrigram = findGuaByBinary(topBinary);
  const bottomTrigram = findGuaByBinary(bottomBinary);
  const fullHexagram = Hexes.order.find((i) => i.binary === totalBinary);
  return { topTrigram, bottomTrigram, fullHexagram };
};

const setToLineData = (set, dm = null) => {
  if (set && set.indexOf(null) >= 0) return undefined;
  const { topTrigram, bottomTrigram, fullHexagram } = dataToInfo(set);
  const subject = Math.abs(fullHexagram.subjectLine - 6);
  const object = subject < 3 ? subject + 3 : subject - 3;
  const element = fullHexagram.element;

  const branchOrder = Ordinals.branchOrdering;
  const bottomStartBranch = Hexes.hexagramStartBranches[bottomTrigram.pinyin][0];
  const bottomBranchIndex = branchOrder.indexOf(bottomStartBranch);
  const topStartBranch = Hexes.hexagramStartBranches[topTrigram.pinyin][1];
  const topBranchIndex = branchOrder.indexOf(topStartBranch);

  const branches = [];
  branches.push(bottomStartBranch);
  if (bottomBranchIndex % 2 === 0) {
    for (let i = 1; i < 3; i++) branches.push(branchOrder[bottomBranchIndex + (i * 2)]);
  } else {
    for (let i = 1; i < 3; i++) branches.push(branchOrder[bottomBranchIndex - (i * 2)]);
  }
  branches.push(topStartBranch);
  if (topBranchIndex % 2 === 0) {
    for (let i = 1; i < 3; i++) branches.push(branchOrder[topBranchIndex + (i * 2)]);
  } else {
    for (let i = 1; i < 3; i++) branches.push(branchOrder[topBranchIndex - (i * 2)]);
  }
  branches.reverse();

  const elements = branches.map(
    (i) => Ordinals.stems[Ordinals.branches[i]._hiddenStemsArr[1]].element);
  const aspects = elements.map((el) => Ordinals.elements[element].elements[el]);

  const animals = [];
  if (dm) {
    const order = Hexes.animalOrder; // RN wraps it with Tools.toProxy
    const startAnimal = Hexes.stemAnimalMap[dm];
    const startIndex = order.indexOf(startAnimal);
    for (let i = 0; i < 6; i++) animals.push(order[(startIndex + i) % order.length]);
  }
  animals.reverse();
  return { branches, elements, aspects, subject, object, animals, element };
};

// ---- golden payloads ------------------------------------------------------

const mutualOf = (binary) =>
  binary.charAt(1) + binary.charAt(2) + binary.charAt(3)
  + binary.charAt(2) + binary.charAt(3) + binary.charAt(4);

const lineData = Hexes.order.map((h) => {
  const bits = h.binary.split('').map(Number);
  const mutualBinary = mutualOf(h.binary);
  const mutual = Hexes.order.find((i) => i.binary === mutualBinary);
  return {
    number: h.number,
    binary: h.binary,
    topTrigram: dataToInfo(bits).topTrigram.pinyin,
    bottomTrigram: dataToInfo(bits).bottomTrigram.pinyin,
    mutualBinary,
    mutualNumber: mutual ? mutual.number : null,
    noDm: setToLineData(bits, null),
    withDm: setToLineData(bits, 'geng'),
  };
});

const compactHex = (h) => ({
  number: h.number,
  binary: h.binary,
  chineseLong: h.chineseLong,
  englishName: h.englishName,
  hexagram: h.hexagram,
});

const pillars = [];
for (const stem of Object.keys(Hexes.pillars)) {
  for (const branch of Object.keys(Hexes.pillars[stem])) {
    const numbers = Hexes.pillars[stem][branch];
    pillars.push({
      stem,
      branch,
      numbers,
      hexagrams: numbers.map((n) => compactHex(Hexes.order[n - 1])),
    });
  }
}

const horary = Object.keys(Hexes.horaryTable).map((pillar) => {
  const binary = Hexes.horaryTable[pillar];
  const hex = Hexes.order.find((h) => h.binary === binary);
  return { pillar, binary, number: hex ? hex.number : null, hexagram: hex ? compactHex(hex) : null };
});

// ---- Li Shee Yee (LiSheeYeeSelector.js calcData) --------------------------

const preHeavenToTrigram = {
  0: Ordinals.guas.kun,
  1: Ordinals.guas.qian,
  2: Ordinals.guas.dui,
  3: Ordinals.guas.li,
  4: Ordinals.guas.zhen,
  5: Ordinals.guas.xun,
  6: Ordinals.guas.kan,
  7: Ordinals.guas.gen,
  8: Ordinals.guas.kun,
  9: Ordinals.guas.qian,
};

const renderLiSheeYee = (fixture) => {
  const time = DateTime.fromISO(fixture.time, { setZone: true });
  if (!time.isValid) throw new Error(`invalid time: ${fixture.time}`);
  let num = strings.liSheeYeeLetterConversion(fixture.q);
  const converted = num;
  let extra = '';
  let h1 = null;
  let h2 = null;
  let firstTrigramNum;
  let secondTrigramNum;

  if (num.length < 2) {
    return { q: fixture.q, time: fixture.time, converted, result: null };
  } else if (num.length === 2) {
    h1 = num.charAt(0);
    h2 = num.charAt(1);
    firstTrigramNum = num.charAt(0);
    secondTrigramNum = num.charAt(1);
    extra = '';
  } else {
    if (num.length % 2) {
      extra = num.charAt(num.length - 1);
      num = num.substring(0, num.length); // intentional no-op in the RN source
      h1 = num.substring(0, num.length / 2);
      h2 = num.substring(num.length / 2, num.length - 1);
    } else {
      h1 = num.substring(0, num.length / 2);
      h2 = num.substring(num.length / 2, num.length);
    }
    firstTrigramNum = strings.addNumbersInString(h1) % 8;
    secondTrigramNum = strings.addNumbersInString(h2 + extra) % 8;
  }

  const numTotal = strings.addNumbersInString(num);
  const chart = FourPillars.plot({ time });
  const whichHour = parseInt(chart.pillars.hour.branch.number) + 1;
  let movingLine = (numTotal + whichHour) % 6;
  if (movingLine === 0) movingLine = 6;

  const firstTri = preHeavenToTrigram[firstTrigramNum];
  const secondTri = preHeavenToTrigram[secondTrigramNum];
  const binary = firstTri.binary + secondTri.binary;
  const full = Hexes.order.find((h) => h.binary === binary);

  // finishInputMethod(): marks are 1 at movingLine-1, then reversed, and the
  // line array is top-first -> the flipped index is 6 - movingLine.
  const flipIndex = 6 - movingLine;
  const changedBinary = binary
    .split('')
    .map((bit, i) => (i === flipIndex ? (bit === '0' ? '1' : '0') : bit))
    .join('');
  const changed = Hexes.order.find((h) => h.binary === changedBinary);

  return {
    q: fixture.q,
    time: fixture.time,
    converted,
    h1,
    h2,
    extra,
    firstTrigram: firstTri.pinyin,
    secondTrigram: secondTri.pinyin,
    numTotal,
    hourBranch: chart.pillars.hour.branch.name,
    whichHour,
    movingLine,
    binary,
    number: full ? full.number : null,
    hexagram: full ? compactHex(full) : null,
    changedBinary,
    changedNumber: changed ? changed.number : null,
    changedHexagram: changed ? compactHex(changed) : null,
  };
};

const lsyFixtures = JSON.parse(fs.readFileSync(LSY_FIXTURES, 'utf8'));

fs.mkdirSync(OUT.replace(/[\\/][^\\/]*$/, ''), { recursive: true });
fs.writeFileSync(OUT, JSON.stringify({
  meta: {
    source: ROOT,
    generated: new Date().toISOString(),
    description: 'Yi Jing data + derived line info + Li Shee Yee outputs rendered by the original RN code',
  },
  order: Hexes.order,
  lineData,
  pillars,
  horary,
  lisheeyee: lsyFixtures.map(renderLiSheeYee),
}, null, 1));

console.log('wrote', OUT, fs.statSync(OUT).size, 'bytes');
console.log('hexagrams:', lineData.length, 'pillar entries:', pillars.length,
  'horary keys:', horary.length, 'Li Shee Yee fixtures:', lsyFixtures.length);
