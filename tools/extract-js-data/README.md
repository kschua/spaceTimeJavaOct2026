# RN → Java data extraction

The Spring Boot port ships per-system JSON files under
`bazi-spring/src/main/resources/data/` (`bazi-`, `solar-`, `almanac-`, `hexes-`,
`fengshui-`, `qimen-data.json`, `library-data.json`). Most tables were first transcribed by hand, but
the React Native app's own sources (`Ordinals.js`, `ShenSha.js`,
`Compendium/Dictionary.js`, `Timing.js`, `Hexes.js`, `WindWater.js`,
`Plotters/QiMap/Jus.js`, ...) are the source of truth. These scripts regenerate
the data directly from the original bundle sources so parity work does not
depend on manual copying.

## Files

| File | Purpose |
| --- | --- |
| `stub-loader.mjs` | ESM loader hooks that stub React Native / icon / asset / `md5` imports. |
| `register-hooks.mjs` | Registers `stub-loader.mjs` (`node --import`). |
| `dump.mjs` | Imports `Ordinals.js`, `ShenSha.js`, `Dictionary.js`, `Timing.js`, `Hexes.js`, `WindWater.js`, `MysticDoors.js`, `SolarCal.js`, `Compendium/index.js`, `Compendium/Titles.js`, the QiMap pillar-ordering table and the Timing/jus data files, and brace-extracts the Library component literals and the four tutorial page arrays; writes `raw-dump.json`. |
| `merge_data.py` | Merges the Bazi parts of `raw-dump.json` into `bazi-data.json`. |
| `merge_almanac.py` | Merges the `timing` part of `raw-dump.json` into `almanac-data.json`. |
| `merge_hexes.py` | Merges the `hexes` part of `raw-dump.json` into `hexes-data.json`. |
| `merge_fengshui.py` | Merges the `windwater` part of `raw-dump.json` into `fengshui-data.json`. |
| `merge_qimen.py` | Merges the `qimen` part of `raw-dump.json` into `qimen-data.json`: jus tables, `solarJus`, `threeYuanTable`, `pillarOrdering`, the MysticDoors `doors`/`stars`/`hours`/`formations`/`structures` tables (49 structures, 176 match rules), the `usefulGodSymbols` reference table (26 topics, 190 rows) and the `displayTerminology` naming conventions (3 × 27 entries); validates and filters the dump, and preserves the 8,339 `"0-undefined"` jusDay entries. |
| `merge_library.py` | Merges the `library` part of `raw-dump.json` into `library-data.json`: the resolved compendium tree + titles (30 top keys, 141 titles), the RelationshipsTable sections (9 sections, 44 `{type, val}` rows), the YinYangRef pairs (18), the HeavenEarthMan lists (11/11/11) and the four tutorial decks (29 pages with image/title/subtitle); validates shapes and keeps the `$header_*` / `$link_*` / `$push_*` markers verbatim. |
| `extract_vendor.py` | Extracts the app's exact luxon build from the JS source map into `vendor/luxon.cjs`. |
| `vendor/luxon-real.mjs` | ESM wrapper around the vendored luxon; used automatically when `vendor/luxon.cjs` exists. |
| `render.mjs` | Runs the original RN modules in Node (real luxon) and writes the Almanac goldens. |
| `render-bazi.mjs` | Same, for the Bazi goldens (15 fixtures; full chart + extended aux data, with pristine-table restore before each chart). |
| `render-yijing.mjs` | Same, for the Yi Jing goldens (catalog, line data, pillar/horary maps, Li Shee Yee). |
| `render-fengshui.mjs` | Same, for the Feng Shui goldens (executes the real `WindWater.js` functions directly; degree/year sweeps, facings, personal gua, time/house flying stars). |
| `render-qimen.mjs` | Same, for the QMDJ goldens: executes the real `Plotters/QiMap/Jus.js` (zhiRun + chaiBu) and `QiMap.plot` — 26 ju fixtures plus 26 chart fixtures across both duns, table edges, late-zi/hour-unknown, house input, the `"0-undefined"` crawl gap and RN-crash cases; chart bazi is stored as a compact digest. Also dumps `MysticDoors.usefulGodSymbols` with `strings.deCamel` topic titles for the 4c symbols screens, four 4d day-scan fixtures (nine days, 52 good hours, two victory days) rendered by `qimen-days.mjs`, and the raw `MysticDoors.displayTerminology` table for the 4e naming conventions. |
| `qimen-days.mjs` | Day/hour scan harness shared by the QMDJ renderer and verifier: the Qi Men loop from `components/Date/Almanac.js` (copied verbatim — the logic is embedded in the React component) run against the real `FourPillars.plot` + `QiMap.plot` + `QiMenAssist.isThreeVictoryDay`; also reproduces the `PreferencesContainer` defaults via the real `almanacQMDJLookups`. |
| `library-source.mjs` | Brace-extracts the inline component literals (`RelationshipsTable` / `YinYangRef` / `HeavenEarthMan`) verbatim from the RN sources; shared by the dumper, renderer and verifier. |
| `tutorial-source.mjs` | Brace-extracts the four `Tutorial*.js` Onboarding `pages={[...]}` arrays (image URI, title, subtitle, 29 pages) verbatim; shared by the dumper, renderer and verifier. |
| `render-library.mjs` | Same, for the Library goldens: imports the real `Compendium/index.js` + `Titles.js`, extracts the component literals and tutorial decks, and runs the `TwelveStagesList` loop over the real `Ordinals` data (all ten stems). |
| `verify-almanac.mjs` | Differential check: runs the real `Timing.js` + `FourPillars.plot` against the live Java API and the golden file. |
| `verify-yijing.mjs` | Differential check: executes the verbatim RN component functions (brace-extracted from source) and compares them against the live Java API and the golden file. |
| `verify-fengshui.mjs` | Differential check: runs the real `WindWater.js` module against the live Java API (tables, luopan degrees, facings, personal gua, time/house flying stars). |
| `verify-qimen.mjs` | Differential check: runs the real `Jus.js`, `QiMap.plot`, `MysticDoors.usefulGodSymbols`, `MysticDoors.displayTerminology` and the Almanac day-scan harness against the live `/api/qimen/jus`, `/api/qimen/chart`, `/api/qimen/useful-gods[/{topic}]`, `/api/qimen/days` and `/api/qimen/terminology` plus the golden file (ju + chart + symbols + day-scan + terminology sections; 875 checks). |
| `verify-library.mjs` | Differential check: compares the real compendium tree/titles, the verbatim component tables and the tutorial decks against the live `/api/library/*` endpoints plus the golden file (compendium + relationships + twelve-stages + word tables + tutorials; 42 checks). |
| `diff-bazi.mjs` | Live Bazi diff: RN plot vs `/api/bazi`, classified into java-only / rn-only / value diffs. |
| `diff-bazi-summary.mjs` | Grouped summary of the above. |
| `fixtures-almanac.json` | 53 fixture times for the Almanac goldens (incl. non-+08 offsets). |
| `fixtures-lisheeyee.json` | Li Shee Yee fixtures (letter/digit edge cases + chart times). |
| `raw-dump.json` | Last dump (committed for reference). |
| `backup/bazi-data.orig.json` | `bazi-data.json` before the full-table merge. |
| `backup/bazi-spring-1.0.0-before-gapfill.jar` | Previous verified build, used for side-by-side regression diffing. |

## Regenerate

`dump.mjs` needs a Node that can see the bundle sources. On this machine that is
the Windows Node, invoked from WSL with Windows-style paths:

```bash
cd tools/extract-js-data
/mnt/d/DevApps/node/node.exe --import ./register-hooks.mjs ./dump.mjs

python3 merge_data.py
```

`merge_almanac.py` uses the same `raw-dump.json`; run it after a dump whenever the
almanac tables change:

```bash
python3 merge_almanac.py
python3 merge_hexes.py
python3 merge_fengshui.py
python3 merge_qimen.py
python3 merge_library.py
```

Golden outputs are rendered by running the **original RN code** in Node with the
app's own luxon build (extracted from the bundle source map into `vendor/`). The
loader automatically prefers the vendored build; if it is missing, the luxon stub
is used and runtime date math is unavailable.

```bash
/mnt/d/DevApps/node/node.exe --import ./register-hooks.mjs ./render.mjs
/mnt/d/DevApps/node/node.exe --import ./register-hooks.mjs ./render-bazi.mjs
/mnt/d/DevApps/node/node.exe --import ./register-hooks.mjs ./render-yijing.mjs
/mnt/d/DevApps/node/node.exe --import ./register-hooks.mjs ./render-fengshui.mjs
/mnt/d/DevApps/node/node.exe --import ./register-hooks.mjs ./render-qimen.mjs
/mnt/d/DevApps/node/node.exe --import ./register-hooks.mjs ./render-library.mjs
# writes bazi-spring/src/test/resources/golden/{almanac,bazi,yijing,fengshui,qimen,library}.json
```

`verify-almanac.mjs` re-runs the real `Timing.js` (plus `FourPillars.plot`) against
the golden file and the **live Java API** — the live side covers `+08:00` fixtures
and ad-hoc hour-unknown / same-instant-different-offset edge cases:

```bash
/mnt/d/DevApps/node/node.exe --import ./register-hooks.mjs ./verify-almanac.mjs
# -> 380 checks, 0 failures when everything matches
```

`verify-yijing.mjs` is the independent cross-check for the Yi Jing port: it
brace-extracts the calculation functions from the RN component sources
(`dataToInfo`, `setToLineData`, `onHexagramChange`, `calculate`,
`finishInputMethod`, `calcData`), executes them in Node, and compares the results
against the golden file and the **live Java API** (the app must be running on
:8080):

```bash
/mnt/d/DevApps/node/node.exe --import ./register-hooks.mjs ./verify-yijing.mjs
# -> 548 checks, 0 failures when everything matches
```

`verify-fengshui.mjs` imports `WindWater.js` itself (no transcription at all)
and compares it against the live API — all 16 tables, luopan degrees (every
band boundary and the 0.1° gaps), all eight facings, personal-gua combinations,
and time/house flying stars (20 time charts incl. non-+08 offsets and both
solstice branches):

```bash
/mnt/d/DevApps/node/node.exe --import ./register-hooks.mjs ./verify-fengshui.mjs
# -> 373 checks, 0 failures when everything matches
```

`verify-qimen.mjs` re-runs the real `Plotters/QiMap/Jus.js` (zhiRun + chaiBu)
against the golden file and the live `/api/qimen/jus` endpoint (including the
table edges — hourJus ends at 2050, January 1900 queries 1899 — and the
`"0-undefined"` crawl gap), then executes `QiMap.plot` itself and compares the
full chart payload against `/api/qimen/chart` with RN-subset semantics (all RN
fields must be present/equal; Java-only extras are allowed). `QiMap.plot` is
called with `appendStyle` exactly like the RN screens, so the style block
(`meta.door/star.style`, interaction icon names, `meta.bird`, `meta.spirit`) is
part of the comparison. The chart section
strips `$icon`, skips only the meta bazi counts/interactions, and covers the
house reference, late-zi, hour-unknown and RN-crash→400 cases. Section [4]
re-runs `MysticDoors.usefulGodSymbols` + `strings.deCamel` and checks the
symbols list, every topic's rows and the unknown-topic 400 against
`/api/qimen/useful-gods[/{topic}]`. Section [5] runs the `qimen-days.mjs`
harness (the verbatim RN Almanac scan) against the committed day-scan fixtures
and `/api/qimen/days` with the same filters, honouring the documented
null-safety divergence for table-miss hours. Section [6] compares the raw
`MysticDoors.displayTerminology` table (3 conventions, 81 name pairs) against
`/api/qimen/terminology`:

```bash
/mnt/d/DevApps/node/node.exe --import ./register-hooks.mjs ./verify-qimen.mjs
# -> 875 checks, 0 failures when everything matches
```

`verify-library.mjs` imports the real `Compendium/index.js` + `Titles.js`, reuses
the verbatim component/tutorial extraction and runs the `TwelveStagesList` loop
against the live `/api/library/compendium|relationships|twelve-stages|yin-yang|heaven-earth-man|tutorials`
endpoints and the golden file (numbers compared by value, so Java's Long/Double
serialization cannot cause false mismatches):

```bash
/mnt/d/DevApps/node/node.exe --import ./register-hooks.mjs ./verify-library.mjs
# -> 42 checks, 0 failures when everything matches
```

Then verify parity (`mvn -q test` runs 74 tests: golden replays for Bazi/Almanac/
Yi Jing/Feng Shui/QMDJ ju + chart + symbols + day scans + naming conventions +
the Library compendium/tables/tutorials plus the MockMvc endpoint suite and the
error-contract tests):

```bash
cd ../../bazi-spring && mvn -q test
```

`merge_data.py`:

* replaces `stems`, `branches`, `sixtyPillars`, `branchSpecialRelations`,
  `auxDetails` and the shen-sha matching tables with the complete originals;
* adds `elements`, `seasons`, `twelveStagesData`, `streams`, `guas`,
  interaction orderings, `display`, `annualAuxDetails`, `annualStarAux`;
* leaves `dms`, `lifePalace*`, `scores` and `solarTerms` untouched;
* writes a timestamped backup under `backup/` on every run.

`$icon` fields (React Native image modules) are stripped — the Java/JSON API
uses the Material icon names in `icon` instead. The QMDJ style block is the one
presentation layer kept in the API: it mirrors `QiMap.styleQimenChart` exactly
(including its `undefined` quirks), see `QimenChartService.appendStyleLayer`.

## Data conventions

* `annualStarAux` entries are `{table, group?, name}` references into
  `auxDetails` / `annualAuxDetails`; `ShenShaService` resolves them.
* The shipped `annualStarAux` only has the `zi` group (the app hardcodes it).
* `dayStemAux`, `dayBranchAux`, `monthBranchAux`, `yearBranchAux`,
  `specialPillarAux` and `specialDayMasterAux` are matching tables at the top
  level; `auxDetails` holds the detail dictionaries (name/chinese/icon/rating/
  desc) keyed by the same group/star names.
* `naYin` is serialized as `[element, name, chinese]` like the RN source.

## Regression check (optional)

Run the preserved jar against a new build and diff overlapping fields:

```bash
java -jar backup/bazi-spring-1.0.0-before-gapfill.jar --server.port=8082
java -jar ../../bazi-spring/target/bazi-spring-1.0.0.jar --server.port=8080
# then compare /api/bazi, /api/cycle/* responses; expected intentional diffs:
#   info.solarTerm shape, ISO times with offsets, richer pillars
#   (stars/annualStars/interactions/stage/naYin/gua/summary), numeric number fields
```
