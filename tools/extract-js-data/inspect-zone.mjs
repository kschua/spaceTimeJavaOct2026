import { DateTime } from 'luxon';
const t = DateTime.fromISO('1990-05-15T14:30:00+08:00', { setZone: true });
const a = DateTime.fromObject({ zone: t.zone, year: 1990, month: 5, day: 6, hour: 2, minute: 35 });
console.log('fromObject:', a.toISO(), a.toMillis(), new Date(a.toMillis()).toISOString(), 'offset:', a.offset);
const b = a.plus({ minutes: 480 });
console.log('plus480:', b.toISO(), b.toMillis(), new Date(b.toMillis()).toISOString(), 'offset:', b.offset);
const c = DateTime.fromObject({ zone: t.zone, year: 1990, month: 5, day: 6, hour: 2, minute: 35 }).plus({ minutes: 0 });
console.log('plus0:', c.toISO(), c.toMillis());
console.log('t offset:', t.offset, 'zone offset at now:', t.zone.offset(Date.now()));
