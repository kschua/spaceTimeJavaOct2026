import { DateTime, Duration } from 'luxon';
const t = DateTime.fromISO('1990-05-15T14:30:00+08:00', { setZone: true });
console.log('t.c:', JSON.stringify(t.c), 't.o:', t.o);
const days = 2637.4629629629626;
const result = {};
for (const k of ['year','quarter','month','week','day','hour','minute','second','millisecond']) {
  if (k === 'day') result[k] = days + t.get(k);
  else result[k] = t.get(k);
}
console.log('result:', JSON.stringify(result));
const dur = Duration.fromObject(result);
const keys = Object.keys(dur.values); if (!keys.includes('milliseconds')) keys.push('milliseconds');
const shifted = dur.shiftTo(...keys);
console.log('shifted:', JSON.stringify(shifted.values));
const ms = Duration.fromObject({hours: shifted.hours, minutes: shifted.minutes, seconds: shifted.seconds, milliseconds: shifted.milliseconds}).as('milliseconds');
console.log('ms:', ms);
const localTS = Date.UTC(t.year, t.month - 1, Math.min(t.day, 31) + shifted.days, t.hour, t.minute, t.second, 0);
console.log('localTS:', localTS, new Date(localTS).toISOString());
console.log('sum:', localTS + ms, new Date(localTS + ms).toISOString());
const r = t.plus({ days });
console.log('r.ts:', r.ts, 'r.c:', JSON.stringify(r.c));
