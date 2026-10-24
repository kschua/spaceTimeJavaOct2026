import { DateTime } from 'luxon';
const t = DateTime.fromISO('1990-05-15T14:30:00+08:00', { setZone: true });
// find the interval RN uses
const dt = t.plus({ days: (0.1) * 365 });
console.log('plus fractional:', dt.toISO(), '| millis:', dt.toMillis());
const t2 = DateTime.fromISO('1990-05-15T14:30:00.000+08:00', { setZone: true });
for (const d of [0.123456789, 1.23456789, 2.3456789]) {
  const x = t2.plus({ days: d });
  console.log('days', d, '->', x.toISO(), '| wall ms of day:', x.hour*3600000 + x.minute*60000 + x.second*1000 + x.millisecond);
}
// actual luck case: replicate RN computation for 1990-05-15
const SolarCal = await import('file:///C:/Temp/myTmp/bundle-sources/src/src/lib/cmtools/SolarCal.js');
const fwd = SolarCal.getForwardJie(t);
const interval = Math.abs(t.diff(fwd, 'days', { conversionAccuracy: 'longterm' }).days);
const first = t.plus({ days: (interval / 3) * 365 });
console.log('jie:', fwd.toISO(), 'interval:', interval, 'first luck:', first.toISO(), 'millis:', first.toMillis());
