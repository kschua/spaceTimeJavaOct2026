// Bootstrap: registers the stub loader with Node's ESM loader before dump.mjs
// runs. Used as:  node --import ./register-hooks.mjs ./dump.mjs
import { register } from 'node:module';

register('./stub-loader.mjs', import.meta.url);
