import { DateTime } from 'luxon';
const t = DateTime.fromISO('1990-05-15T14:30:00+08:00', { setZone: true });
console.log('zone type:', t.zone.type, '| name:', t.zone.name, '| isUniversal:', t.zone.isUniversal, '| offset:', t.offset);
const SolarCal = await import('file:///C:/Temp/myTmp/bundle-sources/src/src/lib/cmtools/SolarCal.js');
const terms = SolarCal.getYearMonthSolarTerm(1990, 5, t.zone, t.offset);
for (const [num, dt] of terms) console.log('term', num, dt.toISO(), 'ts', dt.toMillis());
const fwd = SolarCal.getForwardJie(t);
console.log('forward jie:', fwd.toISO(), fwd.toMillis());
const interval = Math.abs(t.diff(fwd, 'days', { conversionAccuracy: 'longterm' }).days);
const days = (interval / 3) * 365;
console.log('interval days:', interval, 'days:', days);
const first = t.plus({ days });
console.log('first:', first.toISO(), first.toMillis(), 'frac:', days - Math.floor(days), 'fracMs:', (days - Math.floor(days)) * 86400000);
// exact shiftTo simulation
const Dur = (await import('luxon')).Duration;
const d = Dur.fromObject({ days });
const shifted = d.shiftTo('days', 'milliseconds');
console.log('shiftTo:', JSON.stringify(shifted.toObject()));
console.log('as ms:', shifted.as('milliseconds'));
