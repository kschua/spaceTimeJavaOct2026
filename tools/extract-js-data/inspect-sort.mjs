// V8 sort behavior for the RN annualStars comparator
const cmp = (a, b) => (a.rating < b.rating) ? 1 : -1;
const make = (arr) => arr.map(([name, rating]) => ({ name, rating }));
for (const input of [
  [['A',1],['B',0],['C',0],['D',0],['E',0],['F',0]],
  [['A',1],['B',1],['C',1],['D',0],['E',0],['F',0]],
  [['A',0],['B',1],['C',0]],
]) {
  const arr = make(input);
  arr.sort(cmp);
  console.log(JSON.stringify(input), '->', JSON.stringify(arr.map(x => x.name)));
}
// 9-element case (chen)
const nine = [['threeStages',1],['elegantSeal',1],['nationalTreasure',1],['fiveGhost',0],['litigation',0],['flyingCharm',0],['yearCharm',0],['yellowFlag',0],['peelingHead',0]];
const a9 = make(nine); a9.sort(cmp);
console.log('nine ->', JSON.stringify(a9.map(x => x.name)));
