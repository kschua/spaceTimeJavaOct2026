// Qi Men day/hour scan harness for goldens + live differential verification.
//
// The loop below is the Qi Men branch of the RN Almanac's process()
// (components/Date/Almanac.js, lines ~197-245) plus the preference lookups
// from QiMenAssist.almanacQMDJLookups. It is copied verbatim because the RN
// logic is embedded in a React component; every chart is produced by the real
// RN FourPillars.plot + QiMap.plot modules (no reimplementation of the math).
import { pathToFileURL } from 'node:url';
import { DateTime } from 'luxon';

const ROOT = (process.env.BUNDLE_ROOT
  || 'C:/Temp/myTmp/bundle-sources/src/src/lib/cmtools').replace(/\/$/, '');
const UTILS = ROOT.replace(/\/cmtools$/, '') + '/utils';

const FourPillars = await import(pathToFileURL(ROOT + '/Plotters/FourPillars/index.js').href);
const QiMap = await import(pathToFileURL(ROOT + '/Plotters/QiMap/index.js').href);
const MysticDoors = await import(pathToFileURL(ROOT + '/MysticDoors.js').href);
const QiMenAssist = await import(pathToFileURL(UTILS + '/QiMenAssist.js').href);

/** PreferencesContainer defaults -> QiMenAssist.almanacQMDJLookups(). */
export function defaultLookups() {
  const structures = {};
  Object.keys(MysticDoors.structures).forEach((k) => {
    if (MysticDoors.structures[k].rating > 0) {
      structures[k] = MysticDoors.structures[k].almanacDefault ? true : false;
    }
  });
  const spirits = {};
  Object.keys(MysticDoors.spirits).forEach((k) => {
    spirits[k] = ['chief', 'harmony', 'heaven', 'moon', 'earth'].indexOf(k) >= 0;
  });
  const stars = {};
  Object.keys(MysticDoors.stars).forEach((k) => {
    stars[k] = ['heart', 'assistant', 'ambassador', 'destructor', 'grass',
      'grain', 'bird', 'hero', 'pillar'].indexOf(k) >= 0;
  });
  const doors = {};
  Object.keys(MysticDoors.doors).forEach((k) => {
    doors[k] = ['life', 'rest', 'open', 'scenery', 'delusion'].indexOf(k) >= 0;
  });
  return QiMenAssist.almanacQMDJLookups({
    almanac: { qmdj: { maxNegativeStructures: 0, structures, spirits, stars, doors } },
  });
}

/** Scans whole days (inclusive), mirroring the RN Almanac month scan. */
export function scanDays(fixture) {
  const lookups = fixture.lookups || defaultLookups();
  const start = DateTime.fromISO(fixture.from, { setZone: true }).startOf('day');
  const end = DateTime.fromISO(fixture.to || fixture.from, { setZone: true }).startOf('day');
  const lateZi = !!fixture.lateZi;
  const method = fixture.method || 0;
  const starPath = fixture.starPath || 0;
  const shiftLateZiStream = fixture.shiftLateZiStream !== false;

  let birthQmChart = null;
  if (fixture.birthTime) {
    const myBazi = FourPillars.plot({
      time: DateTime.fromISO(fixture.birthTime, { setZone: true }),
      lateZi,
    });
    birthQmChart = QiMap.plot({
      bazi: myBazi, reference: 'hour', starPath, method, shiftLateZiStream,
      appendMeta: true,
    });
  }

  const days = [];
  for (let d = start; d <= end; d = d.plus({ days: 1 })) {
    const theDay = d.set({ hour: 23, minute: 59, second: 0, millisecond: 0 });

    let victoryDay = null;
    if (birthQmChart) {
      const todayBazi = FourPillars.plot({ time: theDay.set({ hour: 12, minute: 0 }) });
      const victory = QiMenAssist.isThreeVictoryDay(birthQmChart, todayBazi,
        { starPath, method, shiftLateZiStream });
      if (victory) victoryDay = { name: victory.name, palace: victory.palace };
    }

    const goodHours = [];
    for (let h = 0; h < 24; h++) {
      if (!(h === 0 || h % 2 === 1)) continue;
      const time = theDay.set({ hour: h, minute: 0 });
      const bazi = FourPillars.plot({ time, lateZi });
      const chart = QiMap.plot({
        bazi, reference: 'hour', starPath, method, shiftLateZiStream,
        appendMeta: true,
      });
      if (!chart) continue; // RN would throw on table misses (agreed null-safety)

      const palaceNames = Object.keys(chart.palaces);
      for (const pal of palaceNames) {
        if (pal === 'center') continue;
        const structures = chart.palaces[pal].meta.structuresArray;
        const plot = chart.palaces[pal].plot;
        const meta = chart.palaces[pal].meta;
        if (lookups.doors.indexOf(plot.door) < 0) continue;
        if (lookups.stars.indexOf(plot.star) < 0) continue;
        if (lookups.spirits.indexOf(plot.spirit) < 0) continue;
        if (meta.structuresMap.negative.length > lookups.maxNegativeStructures) continue;
        for (const struct of structures) {
          if (lookups.structures.indexOf(struct.name) >= 0) {
            goodHours.push({
              structure: struct,
              time: chart.bazi.info.time.toISO({
                suppressMilliseconds: true,
                suppressSeconds: true,
              }),
              palace: pal,
              numPositiveStructures: meta.structuresMap.positive.length,
            });
            break;
          }
        }
      }
    }

    days.push({ date: d.toFormat('yyyy-MM-dd'), victoryDay, goodHours });
  }

  return { from: start.toFormat('yyyy-MM-dd'), to: end.toFormat('yyyy-MM-dd'), lookups, days };
}
