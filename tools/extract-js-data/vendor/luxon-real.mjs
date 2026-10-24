// Real luxon for the golden-render harness: the exact `cjs-browser` build that
// the original app bundled, extracted from index.android.bundle.map
// (see extract_vendor.py). Loaded only when REAL_LUXON is set so the data
// dumpers keep using the light stub.
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const luxon = require('./luxon.cjs');

export const DateTime = luxon.DateTime;
export const Duration = luxon.Duration;
export const Interval = luxon.Interval;
export const Info = luxon.Info;
export const Settings = luxon.Settings;
export const Zone = luxon.Zone;
export const FixedOffsetZone = luxon.FixedOffsetZone;
export const IANAZone = luxon.IANAZone;
export const InvalidZone = luxon.InvalidZone;
export const SystemZone = luxon.SystemZone;
export default luxon;
