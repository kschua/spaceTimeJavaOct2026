import { DateTime, Duration } from 'luxon';
const t = DateTime.fromISO('1990-05-15T14:30:00+08:00', { setZone: true });
const days = 2637.4629629629626;
const r = t.plus({ days });
console.log('values:', JSON.stringify(t.values));
console.log('result obj:', JSON.stringify(r.toObject()));
console.log('ts:', r.ts, 'toMillis:', r.toMillis());
const res = {};
const orderedUnits = ['year','quarter','month','week','day','hour','minute','second','millisecond'];
const dur = Duration.fromObject({ days });
for (const k of orderedUnits) {
  if (Object.prototype.hasOwnProperty.call(dur.values, k) || Object.prototype.hasOwnProperty.call(t.values, k)) res[k] = dur.get(k) + t.get(k);
}
console.log('res:', JSON.stringify(res));
const shifted = Duration.fromObject(res).shiftTo('year','month','day','hour','minute','second','millisecond');
console.log('shifted:', JSON.stringify(shifted.toObject()));
const millis = Duration.fromObject({hours: shifted.hours, minutes: shifted.minutes, seconds: shifted.seconds, milliseconds: shifted.milliseconds}).as('milliseconds');
console.log('millisToAdd:', millis);
const cday = Math.min(t.day, 31) + shifted.days;
console.log('cday:', cday);
const localTS = Date.UTC(t.year, t.month - 1, cday, 0,0,0,0);
console.log('localTS:', localTS, 'plus millis:', localTS + millis);
console.log('Date of sum:', new Date(localTS + millis).toISOString());
