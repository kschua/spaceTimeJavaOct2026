import { DateTime } from 'luxon';
const t = DateTime.fromISO('1990-05-15T14:30:00+08:00', { setZone: true });
console.log('current ts:', t.toMillis());
const days = 2637.4629629629626;
const r = t.plus({ days });
console.log('r.ts raw:', r.ts, 'typeof', typeof r.ts, 'toMillis:', r.toMillis());
console.log('r.c:', JSON.stringify(r.c));
// direct internal adjustTime replication
const Dur = (await import('luxon')).Duration;
const dur = Dur.fromObject({ days });
const keys = Object.keys(dur.values); keys.push('milliseconds');
const shifted = dur.shiftTo(...keys);
console.log('shifted values:', JSON.stringify(shifted.values));
const ms = Dur.fromObject({hours: shifted.hours, minutes: shifted.minutes, seconds: shifted.seconds, milliseconds: shifted.milliseconds}).as('milliseconds');
console.log('ms:', ms);
