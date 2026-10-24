# Migration Plan — Remaining Metaphysics Systems to the Java Service

Scope: port the RN systems that are **not yet** in `bazi-spring`:

| # | System | RN sources | Est. size |
|---|--------|-----------|-----------|
| 1 | Almanac / Tong Shu 黄历通书 | `lib/cmtools/Timing.js` + `Data/tongShu` + `Data/DongGong` | S |
| 2 | Yi Jing / Hexagrams 易经 | `lib/cmtools/Hexes.js` + `components/YiJing/*` | S/M |
| 3 | Feng Shui 风水 | `lib/cmtools/WindWater.js` + `components/FengShui/*` | M/L |
| 4 | Qi Men Dun Jia 奇门遁甲 | `lib/cmtools/MysticDoors.js` + `Plotters/QiMap/*` + `Data/jus*` | L |
| 5 | Library / Compendium (reference) | `Compendium/*` + `components/Info/*` | S |

**Bottom line**

- Migration order: **Almanac → Yi Jing → Feng Shui → QMDJ → Library**, smallest/least-dependent first.
- Architecture: **keep the modular monolith** (one Spring Boot app, one package per system). Do **not** split into microservices.
- Kubernetes: **not warranted** at this stage. Single VM + systemd, Docker Compose, or a managed container runtime is enough. Rationale and extraction triggers in §11.

---

## 1. Current state

**Java (`bazi-spring`)** — Spring Boot, Java 17, single module:

- Ported: Four Pillars chart (`BaziService`), shen sha (`ShenShaService`), synastry (`SynastryService`), solar terms & cycles (`SolarCalendar`, `CycleService`), `personalGua` (already inside `BaziService`, used for `meta.gua`), Qi Men ju selection + display naming conventions (`QimenService`), the core chart plot with meta/destiny/style layers (`QimenChartService`), the useful-god symbol tables (`QimenUsefulGodsService`), the Almanac day/hour scan incl. victory days (`QimenDayListService`) and the Library / Compendium reference data incl. tutorials (`LibraryService`).
- Data: `data/bazi-data.json` (148 KB), `data/solar-data.json` (616 KB, years 1582–2500), `data/almanac-data.json`, `data/hexes-data.json`, `data/fengshui-data.json`, `data/qimen-data.json` (1.6 MB: jus tables, MysticDoors, pillar ordering), `data/library-data.json` (162 KB: compendium tree, titles, interaction/word tables, tutorial pages), loaded by `DataStore` / `ResourceJson`.
- Endpoints: `GET /api/bazi`, `/api/cycle/major|minor`, `/api/solstice`, `/api/synastry`, `/api/almanac[/reference]`, `/api/hexagrams*`, `/api/lisheeyee` (GET+POST), `/api/fengshui/*`, `/api/qimen/jus`, `/api/qimen/chart`, `/api/qimen/useful-gods[/{topic}]`, `/api/qimen/days`, `/api/qimen/tables`, `/api/qimen/terminology`, `/api/library/compendium`, `/api/library/relationships`, `/api/library/twelve-stages`, `/api/library/yin-yang`, `/api/library/heaven-earth-man`, `/api/library/tutorials`, `/api/health`; static UI `index.html`, `almanac.html`, `hexagrams.html`, `fengshui.html`, `qimen.html`, `qimen-symbols.html`, `qimen-days.html`, `library.html`, `tutorials.html`.
- Tooling: `tools/extract-js-data/` (`dump.mjs` + per-system `merge_*.py` / `render-*.mjs` / `verify-*.mjs` and the regression README) — the extraction + golden-diff workflow used by every phase.

**RN reference sources** (`bundle-sources/src/src`): all tables and algorithms listed above, plus `Ordinals.js` / `SolarCal.js` whose Java equivalents already exist.

---

## 2. Dependency map

```
Ordinals / SolarCal / DataStore                [done in Java]
   │
   ├── Almanac   Timing.js + tongShu + DongGong            (S)
   │
   ├── Yi Jing   Hexes.js (+ liSheeYeeLetterConversion)    (S/M)
   │
   ├── Feng Shui WindWater.js                              (M/L)
   │     uses: FourPillars.plot ✔, SolarCal.getCurrentYearJie ✔, getSolstice ✔, personalGua ✔
   │
   └── QMDJ      MysticDoors.js + QiMap/index.js + Jus.js  (L)
         uses: FourPillars.plot ✔, WindWater (phase 3), ShenSha ✔,
               Data/jusDay(872KB) + jusHour(608KB) + jusMonth/jusYear
```

Everything depends on code that already exists; QMDJ additionally depends on the Feng Shui phase (its `house` reference and star-path logic lean on `WindWater`).

---

## 3. Ground rules (parity + conventions)

1. **Parity first.** Port exact behaviour including quirks (`indexOf > 0` flags, null returns, reversed tie sorts, key order). Document every quirk in a comment, as done for the Bazi port. Deviations are bugs unless listed below.
2. **Deliberate divergences** (record in code):
   - Strip RN `$icon` fields from API output. Presentation data is ported only where RN puts it in the chart contract: QiMen `styleQimenChart` (`meta.door/star.style`, interaction icon names and the `bird`/`spirit` blocks) is served additively.
   - Keep existing Java synastry semantics where RN had object-vs-string bugs.
   - Guard crashes (e.g., divide-by-zero, empty arrays) with JS-equivalent fallbacks where the RN app would have crashed.
3. **JSON shapes.** Numbers stay numbers, `null` stays `null`, `LinkedHashMap` throughout to preserve JS key ordering.
4. **Time.** ISO strings with offset; compare instants (`LocalDateTimeAt` helpers).
5. **Data.** Extract via the existing Node harness into **per-system JSON files** (`almanac-data.json`, `hexes-data.json`, `fengshui-data.json`, `qimen-data.json`) so each loads independently and merges stay small. Keep the `${icon}` strip and ref-normalisation patterns from `dump.mjs`.
6. **Packages.** New code: `com.bazi.almanac`, `com.bazi.yijing`, `com.bazi.fengshui`, `com.bazi.qimen`, `com.bazi.library`. Move shared things to `com.bazi.core` only when a phase already touches them — don't churn working Bazi code.
7. **Tests.** Golden fixtures: a Node "render" script emits RN outputs for a fixture matrix; committed under `src/test/resources/golden/`; JUnit replays the same inputs through Java services and diffs. Extend the `tools/extract-js-data/README.md` workflow.
8. **Endpoints.** Additive only; keep the existing `/api/*` contracts untouched.

---

## 4. Phase 0 — Groundwork (shared) — **DONE**

> Implemented: `dump.mjs` now also dumps the `timing` section; `merge_almanac.py` writes
> `almanac-data.json`; `ResourceJson` (lazy `/data/*.json` loader) added; `render.mjs`
> runs the original RN modules in Node with the **app's own luxon build**
> (extracted from `index.android.bundle.map` by `extract_vendor.py`) and writes golden
> fixtures; harness fallback resolution now handles Metro extensionless/directory
> imports. `solar-data.json` coverage (1582–2500) confirmed sufficient.

1. Extend `dump.mjs` with new selectors:
   - Almanac: `twelveOfficerDesc`, `twentyEightConstellationsDesc`, `threeKillingsDesc`, `officers`, `bigDisasterDays`, `tongShu`, `DongGong`.
   - Hexes: `order`, `horaryTable`, `horary`, `pillars`, `animalOrder`, `stemAnimalMap`, `hexagramStartBranches`.
   - Feng Shui: `mountains`, `twentyFourMountainAttributes`, `flyingStars`, `flyingStarCombinations`, `xuanKong*`, `piercingMountain72Dragons`, `earthPenetrating60Dragons`, `equalDivision60Dragons`, `threeAuspiciousSixElegance`, `eightMansions`, `qiMenFengShuiMap` (strip any RN-color fields inside).
   - QMDJ: `doors`, `stars`, `spirits`, `displayTerminology`, `victories`, `formations`, `structures`, `hours`, `usefulGodSymbols` + `jusDay`, `jusHour`, `jusMonth`, `jusYear` (single-line minified files; JSON output only).
2. Add a generic resource loader (`ResourceJson.load("almanac-data.json")` with lazy cache) next to `DataStore`; keep `DataStore` as-is for Bazi/solar.
3. Add the golden-render Node script (`render.mjs`) + fixtures file; document the command.
4. Verify `solar-data.json` coverage is enough for the new systems (Feng Shui/QMDJ use the same year-jie/solstice math the Bazi port already covers; 1582–2500 is ample).

Deliverable: empty services wired in with data loading + one trivial endpoint each (proves the pipeline).

---

## 5. Phase 1 — Almanac / Tong Shu — **DONE**

> Implemented: `AlmanacService` (all `Timing.js` functions), `AlmanacController`
> (`/api/almanac`, `/api/almanac/reference`), `almanac.html` + nav link, and
> `AlmanacServiceTest` replaying **53 golden fixtures** (2017–2023, solar-term
> boundaries, tongShu in/out of range, pre-anchor constellation wrap, big-disaster
> index-0 quirk, late-Zi, and **non-+08 offsets** where the offset calendar date
> drives the constellation/tongShu lookups) against RN output — all passing.
> `verify-almanac.mjs` additionally re-runs the real `Timing.js` + `FourPillars.plot`
> against the live API and the golden file — 380 checks, 0 failures.
>
> Divergences (documented in the service): `/api/almanac` returns compact pillars
> (`{stem, branch, chinese}`) plus `time`; `dongGongNum` is omitted when the table
> has no entry (JS would drop the `undefined` key anyway); the luxon `dayDiff`
> semantics reduce to exact calendar-day differences (`ChronoUnit.DAYS`), which
> matches the app's target (Hong Kong, no DST).

**Scope:** `Timing.js` (438 lines) + `Data/tongShu.js` (104 KB crawled daily entries) + `Data/DongGong.js` (16 KB).

**Port list:**
- `getTwelveOfficer(monthBranch, dayBranch)` — 建除十二神 cycle.
- `getYearlyThreeKillings(yearBranch, dayBranch)` / `getMonthlyThreeKillings(monthBranch, dayBranch)`.
- `get28Constellation(time)` — 廿八宿; port luxon date math exactly (watch weekday/week boundary).
- `getYellowBlack(monthBranch, dayBranch)` — 黄黑道.
- `getAllDayInfo(bazi)` — composed result in JS key order: `officer, constellation, yellowBlack, m3k, y3k, bigDisaster, yearBreaker, monthBreaker, monthDestructor, other, dongGongNum`. Preserve the `indexOf(lookup) > 0` quirk (day at index 0 never flagged) and the `dongGongNumMap` mapping.
- Reference dicts: officer descriptions, constellation descriptions (rating/suitable/unsuitable), three-killings descriptions, `bigDisasterDays`.

**Endpoints:**
- `GET /api/almanac?time=…&lateZi=…` → `{ dayInfo, pillars }` (plots the day chart via `BaziService`).
- `GET /api/almanac/reference` → officers/constellation/three-killings dictionaries.

**UI:** `almanac.html` — date picker; blocks for officer, constellation + rating, yellow/black path, three killings, breakers, big-disaster, Dong Gong number, and the tongShu entry (`other`: 宜/忌) when present.

**Tests:** fixtures spanning 2 years incl. solar-term boundaries, leap year, and dates present/absent from the tongShu table.

**Size: S.** Main risk: luxon-specific date differences for the constellation lookup.

---

## 6. Phase 2 — Yi Jing / Hexagrams — **DONE**

> Implemented: `HexesService` (catalog, `fromPillar`/horary lookup, the
> `HexagramSelector.dataToInfo`/`setToLineData` line derivation, `mutual`, and the
> full Li Shee Yee method), `HexesController` (`/api/hexagrams`,
> `/api/hexagrams/{binary}`, `/api/hexagrams/from-pillar`, `/api/lisheeyee`
> GET+POST), `hexagrams.html` + nav links, and `HexesServiceTest` replaying
> RN-rendered goldens: full 64-hexagram catalog, per-hexagram line data (with and
> without day master), all 60 pillar entries, all 59 horary entries, and 22 Li Shee
> Yee edge cases — all passing.
>
> Findings vs. the plan: `order` entries carry only 15 short fields (no line
> texts) so they are dumped and served verbatim; the `horary` trigram constant is
> dead code but kept in the data file; `horaryTable` is missing the `gui-you`
> entry (59 keys) — kept as-is, such lookups return an empty list. The RN
> `branchOrdering` is a `Tools.toProxy` array, so line-branch indices wrap around
> the 12 branches; Java reproduces the wrap with `Math.floorMod` (without it, 16
> hexagrams would crash the RN single view). Divergence (agreed null-safety):
> unknown stems/branches and missing horary keys return empty results instead of
> the RN `TypeError`.

**Scope:** `Hexes.js` (1,273 lines, mostly 64-hexagram data), plus component logic: `HexagramSelector` (browse), `BaziHexagram` (pillar→hexagram), `LiSheeYeeSelector` (李氏易數), `strings.liSheeYeeLetterConversion`.

**Port list:**
- Catalog: `order` (64 hexagrams: binary, names, imagery/metaphor/line texts), lookups by binary/number. Dump as-is; render generically — don't hand-map fields.
- `pillars[stem][branch]` → hexagram index list (Bazi pillar mapping).
- `horaryTable[stem-branch]` → binary (cast hexagrams); check whether the `horary` casting helper is actually used by the UI and port only if so.
- `hexagramStartBranches` — verify usage before porting; keep as data if it feeds line assignment.
- Li Shee Yee: letter→digit conversion table (`a/j/s→1 … i/r→9`, digits pass through, others dropped), then the derivation from `LiSheeYeeSelector`: `preHeavenToTrigram` (0→kun, 1→qian, …, 9→qian), 1-digit→reject, 2-digit→single hexagram, longer→split; changing-line determination ported exactly.

**Endpoints:**
- `GET /api/hexagrams` (compact list), `GET /api/hexagrams/{binary}` (full),
- `GET /api/hexagrams/from-pillar?stem=&branch=&horary=`,
- `GET /api/lisheeyee?q=…` → `{ converted, hexagrams, changingLine, texts }` (+ optional `POST` for long inputs).

**UI:** `hexagrams.html` — 64-grid browser with detail view; Li Shee Yee input; pillar lookup.

**Tests:** all 64 binaries; every key of `pillars` and `horaryTable`; Li Shee Yee edge cases (empty, 1 digit, 2 digits, long, symbols, full-width chars).

**Size: S/M.** Main risk: identifying which text fields the UI consumes vs. dead data; dump-only is safe.

---

## 7. Phase 3 — Feng Shui — **DONE**

> Implemented: `FengShuiService` — `degreeToMountain`, `yearToPeriod`,
> `getEightMansionsFromFacing`, `extendEightMansions`, `getPersonalGua`
> (delegates to `BaziService.personalGua`, adds the life-gua direction table and
> mansion details), `getTimeFlyingStars` (year/month/day/hour centers, incl. the
> up-to-61-day jia-zi scan from the solstice) and `getHouseFlyingStars`
> (period/facing/sitting plates), plus all 16 static WindWater tables in
> `fengshui-data.json` (dumped from the RN module). `FengShuiController` exposes
> `/api/fengshui/{luopan, luopan/mountains, dragons, reference, personal-gua,
> eight-mansions, time-flying-stars, house-flying-stars}`; `fengshui.html` adds
> the SVG luo pan, time flying-star grids, house plates and the eight-mansion
> tables with nav links. `FengShuiServiceTest` replays RN-rendered goldens:
> 733-degree sweep, 251 years, all 8 facings (map + extended scores), 52
> personal-gua cases, 12 time charts and 20 house cases — 9/9 passing.
>
> Findings vs. the plan: `twentyFourMountainAttributes` is missing the NE3 (寅)
> entry in the RN source — preserved as a data gap. `degreeToMountain` leaves
> 0.1° gaps between bands; RN later dereferences the undefined mountain and
> crashes, Java returns `null` (luopan endpoint serves sector/mountain/attributes
> as null; house endpoint answers 400). Unknown facing likewise crashes RN
> (`m` undefined) and returns `null`/400 here — agreed null-safety divergences.
> luxon `set({hour:12})` keeps minutes, and the RN day-center index is
> `Math.round(diff.days)`. The day-center lookup has two zone quirks, both
> reproduced: when the current term is a solstice, `SolarCal.getSolstice` returns
> `thisTerm.begins` (chart offset); otherwise its backwards scan rebuilds the
> solstice with `DateTime.fromObject` **without a zone**, i.e. in the *server
> system zone* even for offset-qualified charts. The jia-zi distance is a luxon
> calendar `diff` across those two zones, not a millisecond division (the
> non-+08 goldens caught this). House `doorDegree` is validated (0-360) and
> echoed by the endpoint; the RN plot itself uses year + facing degree only.
> A perf guard test keeps the up-to-61-day scan bounded.
>
> Verification: goldens execute the real `WindWater.js` functions directly
> (`render-fengshui.mjs`, no transcription); `verify-fengshui.mjs` re-runs the RN
> module against the live API — 373 checks, 0 failures (all 16 tables, luopan
> boundaries/gaps, 8 facings, 28 personal-gua combinations, 20 time charts incl.
> non-+08 offsets and both solstice branches, 20 house fixtures + door
> validation).

**Scope:** `WindWater.js` (4,124 lines) + components: LuoPan, FlyingStars (+Palace/SquareChart), EightMansions, HouseSelector, CompassDegreeSelector.

**Already available:** `BaziService.personalGua` (reuse; optionally move to `FengShuiService` with a delegate so `meta.gua` output is unchanged), `SolarCalendar.getCurrentYearJie`, solstice math in `CycleService` (extract a shared helper carefully — regression test `/api/solstice`).

**Port groups:**
- `a. Static tables` (dump-only): mountains + 24-mountain attributes, flying stars + combinations, xuanKong rings (inner/outer numbers & hexagrams), 72/60/60 dragons, three auspicious six elegance, `qiMenFengShuiMap`, eight mansions, Na Jia.
- `b. Functions`: `degreeToMountain`, `yearToPeriod`, `getEightMansionsFromFacing`, `getEightMansionNaJia`, `extendEightMansions`, `getTimeFlyingStars` (year/month/day/hour centers using Bazi plot + year-jie + solstice; keep the `indexOf`/table quirks), `getHouseFlyingStars` (period/facing/degree).
- `c. House model`: shared `{houseYear, houseDegree, doorDegree}` type used by both Feng Shui and QMDJ house reference.

**Endpoints:**
- `GET /api/fengshui/personal-gua?year=&gender=&isPreviousYear=`
- `GET /api/fengshui/time-flying-stars?time=…` → four centers + palace numbers
- `GET /api/fengshui/house-flying-stars?houseYear=&houseDegree=&doorDegree=`
- `GET /api/fengshui/luopan?degree=` (+ `/api/fengshui/luopan/mountains` for the ring)
- `GET /api/fengshui/eight-mansions?facing=` (+ personal-gua variant)
- `GET /api/fengshui/dragons` (library data)

**UI:** `fengshui.html` — SVG compass rotated by degree slider; 3×3 flying-star chart with period/year/month/day/hour toggle; eight-mansions direction table; house inputs.

**Parity/perf notes:** `getTimeFlyingStars`'s day-center scans up to 61 days of charts per call (`calcDayCenter`) — port as-is first, add a per-date cache only if UI latency needs it.

**Tests:** degrees around ring boundaries (0°, 22.5°, 337.5°); flying stars for fixed years incl. year-jie boundary; all guas for eight mansions; house cases with/without door degree.

**Size: M/L.** Main risks: luxon `time.set(...)`/`plus({days})` semantics; hidden lodash `cloneDeep` mutation assumptions (in Java, treat tables as immutable).

---

## 8. Phase 4 — Qi Men Dun Jia

**Scope:** `MysticDoors.js` (3,598 lines, mostly data), `Plotters/QiMap/index.js` (734 lines, the algorithm), `Plotters/QiMap/Jus.js` (56), `Data/jusDay` (872 KB), `jusHour` (608 KB), `jusMonth`, `jusYear`; `QiMenAssist` helpers; `QiMenDestinySummary`.

Split into sub-phases:

**4a. Jus — DONE.** `Jus.js` + tables (`qimen-data.json`) + `SolarCal`: ju per reference (year/month/day/hour/destiny) and dun (阳遁/阴遁), plus the `chaiBu` (拆補) alternative. Endpoint `GET /api/qimen/jus?time=&reference=&method=0|1` (`method=1` with `reference=hour` switches to chaiBu, mirroring `QiMap.plot`). Golden: `render-qimen.mjs` (26 fixtures across both duns, term/table edges, late-zi, hour-unknown, non-+08 offsets); `QimenServiceTest` replays it; `verify-qimen.mjs` re-runs the real `Jus.js` against the live API — 656 checks including the 4b chart and 4c symbols sections, 0 failures.

> Findings kept for parity: `jusDay` has 8,339 literal `"0-undefined"` entries
> (2021-12-21 alone, then 2077-03-04 through 2099 — a crawl miss) and the app
> returns ju `"undefined"` for them; `hourJus` stops at 2050 (hour/destiny
> lookups return null beyond); a pre-li-chun January 1900 is `isPreviousYear`,
> so its month/year keys shift to 1899 and miss; `zhiRun` returns ju as a
> string while `chaiBu` returns a number — the endpoint preserves both types.
> Missing tables return `{dun:null, ju:null}` (RN catches and returns null);
> unknown references answer 400 (RN's failing split).

**4b. Core plot — DONE.** `QiMap.plot({ bazi, reference, starPath, method, shiftLateZiStream, houseInput, appendDestiny, appendMeta, appendStyle })` is ported in `QimenChartService`: earth-stem arrangement (ju + stem placement), rotating vs flying method, star-path variants, late-zi stream shift, the nine palaces with door/star/spirit/stem, and the house reference (`houseInput`). Meta adds door/star strength and palace/special interactions, formations, the 10×10 `structures` match rules, the hour door rating, and destiny lines (`appendDestiny` must run after meta; the center palace keeps `meta = {}`). Endpoint `GET /api/qimen/chart?time=&reference=hour|day|month|year|house&method=0|1&starPath=0|1&shiftLateZiStream=&houseYear=&houseDegree=&doorDegree=&destiny=&meta=&gender=&lateZi=&hourUnknown=` returns `{time, reference, chart|null}`: 200 with `chart:null` where RN yields nothing, 400 for invalid input or RN crash classes. `qimen.html` is live with the form, 3×3 palace grid, details/victories panels and optional destiny lines.

> Parity findings/divergences for 4b:
> * The RN style layer (`appendStyle`) is deliberately skipped. `suitable`/`unsuitable`/`desc` are `undefined` in the shipped `MysticDoors` data, so no payload fields are lost — only RN icons/styles.
> * `details.ju` keeps the RN type: string for zhiRun, number for chaiBu/house.
> * `isLeadStar` compares `leadDoor` (RN quirk), making it identical to `isLeadDoor`; preserved.
> * `houseInput` runs whenever all three house fields are truthy, regardless of the reference; `reference=house` with missing/falsy input → `null` chart. House years outside 1844–2083 or degrees that fall in mountain gaps → 400 (RN throws).
> * Missing zhiRun table or ju `"undefined"` (the 8,339 `0-undefined` days) → `null` chart; `starPath` outside 0/1 → 400 (RN leaves stars undefined and crashes); `hourUnknown` + `reference=hour` → `null` (RN TypeError); `hourUnknown` + meta omits `doorInteraction`.
> * `victories.light` may be `center`; with no destiny palace the obstruction is skipped with a log line (RN throws).
> * Chart bazi is the plain `BaziService` plot (not the extended aux payload); goldens store a compact bazi digest.
> * Goldens cover 26 chart fixtures (both duns, all references, methods, star paths, late-zi, house input, `0-undefined`, RN-crash cases); `verify-qimen.mjs` section [3] compares RN vs Java live with `$icon`-stripped subset semantics and skips only the meta bazi counts/interactions.

**4c. Useful gods — DONE.** `MysticDoors.usefulGodSymbols` (26 topics, 190 `[symbol, meaning]` rows) is merged into `qimen-data.json`. `QimenUsefulGodsService` ports the RN screens: raw topic keys sorted for the list, `strings.deCamel` titles, and each table row mapped to `{title, body}` for the details view. Endpoints `GET /api/qimen/useful-gods` → `{topics:[{topic,title}]}` and `GET /api/qimen/useful-gods/{topic}` → `{topic,title,symbols:[{title,body}]}` (unknown topic 400). `qimen-symbols.html` is live with the topic list + symbol table and is linked from `qimen.html`.

> Finding: QiMenUsefulGodList/Details are presentation-only — no chart screening exists behind them. The `QiMenAssist` helpers (`isThreeVictoryDay`, almanac lookups/cache key) belong to the Almanac/day flow and move to 4d. Goldens and live comparison cover the whole table via `render-qimen.mjs` / `verify-qimen.mjs` section [4].

**4d. Day list / search — DONE.** The RN Almanac Qi Men month scan (plus `QiMenDayList` and `QiMenAssist.isThreeVictoryDay`) is ported in `QimenDayListService`. `GET /api/qimen/days?from=&to=&lateZi=&method=&starPath=&shiftLateZiStream=&doors=&stars=&spirits=&structures=&maxNegativeStructures=&birthTime=&gender=` plots the 13 hour charts per day (00:00 plus every odd hour — RN's `h === 0 || h % 2 === 1`), keeps palaces whose door/star/spirit pass, whose negative structures are within the cap and whose matched structure is in the set, and returns `{from,to,options,hours,victory,lookups,days:[{date,victoryDay,goodHours:[{structure,time,palace,numPositiveStructures}]}]}`. `to` defaults to the end of the `from` month; the inclusive range is capped at 31 days. Filter defaults mirror the RN preferences (`PreferencesContainer` + `almanacQMDJLookups`): positive `almanacDefault` structures, doors rest/life/delusion/scenery/open, all nine stars, spirits chief/moon/harmony/earth/heaven, `maxNegativeStructures=0`, echoed in RN key order. `birthTime` enables the personal victory check (`light|blessing|force` + palace + direction). `GET /api/qimen/tables` serves the filter options for the UI. `qimen-days.html` is live: controls, filter fields, and per-day cards with good-hour rows and victory badges.

> Parity findings/divergences for 4d: table-miss hours (post-2050, the `0-undefined` crawl days) are skipped instead of throwing like the RN scanner; `QiMenSearch` is an empty RN screen (no server-side logic to port); `QiMenDayList` itself only renders the payload the Almanac scanner built, so there is no separate search algorithm. Deviating from the RN month-only scan, the endpoint accepts any ≤31-day range.

> Goldens cover four scan fixtures (defaults; custom filters + starPath=1 + shift off; victory days; late-zi) in `render-qimen.mjs` via the shared `qimen-days.mjs` harness, which runs the Almanac loop verbatim against the real `FourPillars`/`QiMap` modules. `QimenDayListServiceTest` replays them (52 good hours, 2 victory days) plus range/filter/`birthTime` error cases; `verify-qimen.mjs` section [5] compares golden and live.

**4e. Destiny summary — DONE.** `appendDestiny` itself was already ported in 4b (the `meta.destiny` labels/range/`isDestinyPalace` on every palace), so 4e covered the remaining display layer. `MysticDoors.displayTerminology` (3 naming conventions × 10 spirits + 9 stars + 8 doors = 81 `[english, chinese]` pairs, quirks kept: convention 1 renames assistant's word to `assist`, grass and grain both `seed`, convention 2 stars are `shower/trust/dash/.../beast`) is merged into `qimen-data.json` and served by `GET /api/qimen/terminology`. `qimen.html` gained a naming-convention selector (persisted) that re-labels door/star/spirit names in the palace grid, the details boxes and the new **Destiny Summary** card (destiny palace + its spirit/star/door + labels + age range), title-cased destiny labels (range now shown as ages — the old UI mislabelled it `°`), and victory directions (`NE · Gen`, matching `ThreeVictorySummary`'s `direction - palace`).

> Style layer still deliberately skipped (icons, `scolor`, RN components) as in 4b — the tabular content is complete. `QimenServiceTest` replays the 81-entry table against the golden and spot-checks the quirks; `verify-qimen.mjs` section [6] compares golden and live.

**Endpoints:**
- `GET /api/qimen/chart?time=&reference=hour|day|month|year|house&method=&starPath=&shiftLateZiStream=&houseYear=&houseDegree=&doorDegree=&destiny=` — **DONE**
- `GET /api/qimen/jus?time=&reference=` — **DONE**
- `GET /api/qimen/useful-gods` + `/api/qimen/useful-gods/{topic}` — **DONE**
- `GET /api/qimen/days?from=&to=&…` — **DONE**
- `GET /api/qimen/tables` — **DONE** (day-scan filter options)

**UI:** `qimen.html` — live: form (incl. naming convention), 3×3 palace grid, details/victories panels, destiny lines + destiny summary. `qimen-symbols.html` — live: useful-god topic list + symbol table. `qimen-days.html` — live: day scan with filters, good-hour rows and victory badges.

**Tests:** matrix over dates covering both duns and all four references; methods 0/1; starPath 0/1; late-zi on/off; house reference incl. missing inputs (null parity). 4b is covered by 26 chart fixtures in the QMDJ golden file (`QimenChartServiceTest`, 10 tests) plus the live RN-vs-Java chart differential in `verify-qimen.mjs` section [3]; 4c by the full 26-topic/190-row table (`QimenUsefulGodsServiceTest`, 4 tests) and section [4]; 4d by four scan fixtures (`QimenDayListServiceTest`, 5 tests) and section [5]; 4e by the 81-entry terminology table (`QimenServiceTest`, 3 tests) and section [6].

**Size: L** (plan ~3–4 focused sessions: 4a, 4b, 4c, 4d/e).

---

## 9. Phase 5 — Library / Compendium + polish — DONE

- `GET /api/library/compendium` returns `{tree, titles}`: the resolved `Compendium/index.js` tree plus `Compendium/Titles.js` (141 entries). The tree keeps RN's section markers verbatim — `$header_*`, `$link_*` (URIs left as URLs) and `$push_*` (`{title, path, params}` app targets). Every push target turned out to be one of five screens: `YinYangRef`, `HeavenEarthMan`, `TwelveStagesList` (10 stems), `RelationshipsTable` (9 keys + `key:null` = all) and `QiMenUsefulGodList` (mapped to the existing `qimen-symbols.html`); `library.html` resolves them to its own views.
- `GET /api/library/relationships[?key=]` — the nine static `RelationshipsTable` sections (44 `{type, val}` rows) with RN's exact filter semantics (`if(key)` filter by exact key, else all; unknown key → empty list, not an error).
- `GET /api/library/twelve-stages?stem=` — the `TwelveStagesList` loop: rows follow `Ordinals.branchOrdering` (zi…hai), stage = `twelveStagesData[stems[stem].twelveStages.indexOf(branch)]`, plus the branch display fields. Unknown/missing stem answers 400 where RN would throw.
- `GET /api/library/yin-yang` (18 word pairs) and `GET /api/library/heaven-earth-man` (11×3 lists) — the word lists embedded in the `YinYangRef` / `HeavenEarthMan` components, brace-extracted from source rather than transcribed.
- `GET /api/library/tutorials` — the four RN onboarding screens (`TutorialBazi` / `TutorialCalendar` / `TutorialHexagrams` / `TutorialQiMen`), 29 pages with their image URLs, titles and subtitles extracted verbatim from the `pages={[...]}` JSX arrays by `tutorial-source.mjs`.
- Data: `data/library-data.json` (162 KB) via `dump.mjs` → `merge_library.py`; goldens via `render-library.mjs` (`golden/library.json`).
- `library.html` — compendium browser mirroring `Library.js`'s item classification (`$header`/`$push`/`$body`/`$link_wiki`/`$link`/`$def`/arrays→tags/objects→buttons/scalars→content) plus the three reference views and the stem/key pickers. `tutorials.html` renders the four tutorial decks. A shared nav bar links all nine pages.
- Follow-ups completed after phase 5:
  - **RN style layer parity.** `QimenChartService.appendStyleLayer()` mirrors `QiMap.styleQimenChart()`: strength fonts, palace/special interaction icon names, `meta.bird` and `meta.spirit`. RN quirks preserved (`suitable`/`unsuitable` read from the wrong level → `undefined`; `bird` serialises `{}`, `spirit` as `{"style":{}}`). Enabled by default (`appendStyle`, requires `appendMeta`), covered by the regenerated chart golden and rendered in `qimen.html` (font weights/italic/strikethrough + interaction glyphs).
  - **Tutorial pages.** `/api/library/tutorials` + `tutorials.html` (verbatim text/images, lazy-loaded).
  - **Production hardening.** `GET /api/health` (200/503 dataset probe), `ApiExceptionHandler` (stable JSON 500 contract, keeps Spring MVC statuses incl. 404), `application.properties` (compression, graceful shutdown, forwarded headers, no error details), multi-stage `Dockerfile`, `.dockerignore` and a root `README.md`.

**Size: S.** — verified by `LibraryServiceTest` (5 tests: full-tree golden equality, filter semantics, all ten stems, both word tables, tutorials), `ApiEndpointsTest.library` and `verify-library.mjs` (42 checks: golden + live, RN source vs Java).

---

## 10. Cross-cutting engineering

- **Golden tests.** `render.mjs <fixtures.json>` (Node, RN bundle) → `src/test/resources/golden/<phase>.json`; JUnit diffs Java service output. Re-run after any RN data refresh.
- **Test suites (current).** `mvn test` runs 74 tests: `BaziServiceTest` (15-fixture golden replay with recursive RN-subset comparison), `AlmanacServiceTest` (53), `HexesServiceTest` (64 hexagrams, 60 pillars, 59 horary, Li Shee Yee), `FengShuiServiceTest` (733-degree sweep, 251 years, 52 gua cases, 20 time charts, day-scan perf guard), `QimenServiceTest` (26-fixture ju golden replay incl. table gaps/type preservation + the 81-entry naming-convention table), `QimenChartServiceTest` (26-fixture plot/meta/destiny/style golden replay, null parity, house/late-zi/hour-unknown quirks), `QimenUsefulGodsServiceTest` (26 topics / 190 rows + deCamel), `QimenDayListServiceTest` (four RN Almanac scan fixtures, filter/range/error cases), `LibraryServiceTest` (full compendium tree + titles, relationship filter semantics, all ten stem cycles, both word tables, tutorials), `ApiExceptionHandlerTest` (2) and `ApiEndpointsTest` (MockMvc: every endpoint incl. health, error paths, static pages). Live differential harness (app on :8080): `verify-almanac.mjs` 380 checks, `verify-yijing.mjs` 548, `verify-fengshui.mjs` 373, `verify-qimen.mjs` 875 (ju + chart + symbols + day scans + terminology), `verify-library.mjs` 42 (compendium + relationships + twelve-stages + word tables + tutorials), all 0 failures; `diff-bazi.mjs` reports only the documented divergences.
- **Bazi parity quirks kept** (found while hardening the port): `ShenSha.isPillarMatch` treats every JS-falsy entry — including `""` (the `thrivingDay` `[null, ""]` row) — as a wildcard, while the all-null early-out is a strict `=== null` check; `Process.extendPillar` derives `aspc` from `elementToAspect[stemElement]` (not the quirky `stems[ref].aspects` table); `meta.maps.aspectToElement` comes from `elements[mainElement].aspects`; `annualStarAux.zi[branch]` is one shared array sorted in place per pillar, so repeated branches end up showing the final sort state (per-chart `annualState` in Java). `meta.counts` is the one deliberate Bazi divergence: RN skips the day stem and keys stem/god counts by objects (`NaN` keys), Java counts the visible stems correctly.
- **Data loading.** Per-system JSON via `ResourceJson` (lazy, cached). Total added payload ≈ 2.5–3 MB parsed once — startup (~13–17 s today) is unaffected materially; if needed, lazy-load per system on first request.
- **Errors.** Clear 400s for invalid inputs; where RN returns `null` (QMDJ house), keep `null` but consider an `error` hint field only if clients ask (additive, not part of parity).
- **Limits.** Day-scan endpoints must cap ranges (e.g., ≤ 366 days) to bound compute.
- **Caching.** Optional per-(minute, options) cache for QMDJ plots and per-date cache for flying-star day centers; measure first.
- **Ops.** `GET /api/health` probes all four datasets (503 when one is missing); `ApiExceptionHandler` keeps a stable JSON error contract; compression, graceful shutdown and forwarded-header handling are configured in `application.properties`; a multi-stage `Dockerfile` runs the jar as UID 10001. Keep `/api` stable and additive.

---

## 11. Architecture advice — microservices? Kubernetes?

**Recommendation: stay a modular monolith; skip microservices; skip Kubernetes for now.**

### Why microservices are a poor fit here

1. **One shared core, pure functions.** Every system depends on the same calendar/ordinal/time tables (`Ordinals`, `SolarCal`), and Feng Shui/QMDJ call `BaziService.plot`. Split into services and you either duplicate the core (version drift) or turn local function calls into network hops — pure added latency and failure modes.
2. **No independent data or lifecycles.** One extraction pipeline, one JSON corpus, one deployable artifact. Bounded contexts exist in the code (packages), not in operations.
3. **Scaling profile is flat.** Stateless, CPU-cheap, ~5 MB in-memory tables. A single JVM handles orders of magnitude more traffic than this app will see. Microservice benefits (independent scaling, team autonomy, fault isolation) don't apply to a one-team project with single-digit RPS.
4. **Costs are real, benefits imaginary.** Contracts, tracing, retries, version skew — all overhead for a single team.

### Why Kubernetes is overkill now

- One process, one replica; no autoscaling need; one static UI behind it.
- K8s brings cluster cost, ingress/TLS, RBAC, observability, and CI/CD complexity that outweighs its value at this stage.
- Simpler equivalents: single VM + systemd; or Docker Compose behind Caddy/nginx; or a managed container runtime (Cloud Run / App Runner / ECS Fargate) if you want scale-to-zero and zero OS maintenance.
- If you *already* run a company-standard K8s platform, deploy this app as **one Deployment + one Service**. But don't split the app into microservices to "justify" running on K8s.

### When to revisit (extraction triggers)

| Signal | Action |
|--------|--------|
| QMDJ day-scan/search becomes a real bottleneck or needs different scaling | extract only `/api/qimen/*` into its own service (strangler) |
| Multiple teams with independent release cadence | split along package boundaries (almanac / fengshui / qimen) |
| A genuinely external API with different SLAs or languages | put that API in front; keep the compute core together |
| You already own a K8s platform | one Deployment for the monolith; no further splitting |

The extraction path is cheap because the plan already isolates packages and URL prefixes (`/api/almanac/*`, `/api/qimen/*`, …): a future reverse proxy can route one prefix to an extracted service with zero client change. Shared core stays a jar or a tiny calendar library.

---

## 12. Suggested sequencing

| Step | Work | Outcome |
|------|------|---------|
| 0 | Harness extension + per-system data files + golden render script | pipeline ready for all phases ✅ |
| 1 | Almanac service + controller + UI + goldens | proves the end-to-end pattern ✅ |
| 2 | Yi Jing service + controller + UI + goldens | standalone system done ✅ |
| 3 | Feng Shui (reuse personalGua, shared house model) + UI | medium system done ✅ |
| 4a–c | QMDJ Jus → core plot → useful gods (API + UI + goldens) | largest system's core done ✅ |
| 4d | day scan / victory days + day-scan UI | scans done ✅ |
| 4e | display naming conventions + destiny summary UI | QMDJ complete ✅ |
| 5 | Library endpoints + shared nav polish | migration complete ✅ |
| 6 | Follow-ups: QMDJ style layer + tutorials + production hardening | full parity + deployable ✅ |

First concrete commit of the plan: **Phase 0 + Phase 1 (Almanac)** — it is the smallest full slice and exercises every part of the new workflow (extraction, data loading, service, controller, golden tests, UI). *Phases 0–5 and the three follow-ups are now implemented and verified: 74 JUnit tests pass (Bazi 15-fixture golden replay, 53 almanac + Yi Jing + Feng Shui + 26 QMDJ-ju + 26 QMDJ-chart goldens including the style block, 26 useful-god topics/190 rows, four day-scan fixtures, 81 naming-convention entries, compendium tree + titles + tutorials, MockMvc endpoint suite incl. health); the live differential harness reports 380 (almanac) + 548 (Yi Jing) + 373 (feng shui) + 875 (QMDJ ju + chart + symbols + day scans + terminology) + 42 (library incl. tutorials) checks with 0 failures, and the Bazi live diff shows only the documented divergences. All nine pages (`index.html`, `almanac.html`, `hexagrams.html`, `fengshui.html`, `qimen.html`, `qimen-symbols.html`, `qimen-days.html`, `library.html`, `tutorials.html`) are live and share one nav bar; every endpoint listed in §1 is live, including `/api/health`. The migration as planned is complete.*
