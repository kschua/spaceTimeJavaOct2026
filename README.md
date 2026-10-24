# Metaphysics App — Java / Spring Boot port

A single Spring Boot service and static web UI for the React Native metaphysics
app: Four Pillars (BaZi), Tong Shu Almanac, Yi Jing, Feng Shui, Qi Men Dun Jia
and the Library/Compendium reference tables.

The port is behaviour-first: calculation services replay golden fixtures
rendered by running the **original RN modules in Node**, and live differential
scripts compare RN source output against the running Java API.

## Requirements

- Java 17
- Maven 3.9+
- Docker (optional, for the container image)

## Quick start

```bash
cd bazi-spring
mvn test                 # 74 JUnit tests, golden replays + MockMvc endpoints
mvn package -DskipTests
java -jar target/bazi-spring-1.0.0.jar
```

Open <http://localhost:8080>. The app probes its datasets at
`GET /api/health` (200 = all present, 503 = one is missing).

## Docker

```bash
docker build -t bazi-spring .
docker run --rm -p 8080:8080 bazi-spring
```

The image is multi-stage (Maven build → JRE 17), drops test compilation, runs as
UID 10001 and keeps a 75% max-RAM hint.

## HTTP API

All endpoints are `GET` unless noted; ISO-8601 times with offsets are accepted.

| Area | Endpoints |
| --- | --- |
| Health | `/api/health` |
| BaZi | `/api/bazi`, `/api/cycle/major`, `/api/cycle/minor`, `/api/solstice`, `/api/synastry` |
| Almanac | `/api/almanac`, `/api/almanac/reference` |
| Yi Jing | `/api/hexagrams`, `/api/hexagrams/{binary}`, `/api/hexagrams/from-pillar`, `/api/lisheeyee` (GET + POST) |
| Feng Shui | `/api/fengshui/luopan`, `/api/fengshui/luopan/mountains`, `/api/fengshui/dragons`, `/api/fengshui/reference`, `/api/fengshui/personal-gua`, `/api/fengshui/eight-mansions`, `/api/fengshui/time-flying-stars`, `/api/fengshui/house-flying-stars` |
| QMDJ | `/api/qimen/jus`, `/api/qimen/chart`, `/api/qimen/useful-gods[/{topic}]`, `/api/qimen/days`, `/api/qimen/tables`, `/api/qimen/terminology` |
| Library | `/api/library/compendium`, `/api/library/relationships`, `/api/library/twelve-stages`, `/api/library/yin-yang`, `/api/library/heaven-earth-man`, `/api/library/tutorials` |

Unknown or invalid inputs return `400` with `{"error": "..."}`; unexpected
failures return a generic `500` and are logged server-side.

### Static pages

`index.html` (BaZi), `almanac.html`, `hexagrams.html`, `fengshui.html`,
`qimen.html`, `qimen-symbols.html`, `qimen-days.html`, `library.html`,
`tutorials.html` — all share one navigation bar.

## Project layout

```
bazi-spring/                    Spring Boot module
  src/main/java/com/bazi/       services, controllers, data loaders
  src/main/resources/data/      extracted JSON tables (bazi, solar, almanac,
                                hexes, fengshui, qimen, library)
  src/main/resources/static/    the nine HTML pages
  src/test/resources/golden/    RN-rendered golden fixtures
tools/extract-js-data/          extraction + golden render + live diff harness
Dockerfile                      production image
MIGRATION_PLAN.md               phase-by-phase port record and architecture notes
```

## Verification

Java golden replays:

```bash
cd bazi-spring && mvn test
# 74 tests, 0 failures
```

Live differential harness (needs the RN bundle sources and a running app on
:8080; see `tools/extract-js-data/README.md`):

```bash
cd tools/extract-js-data
node --import ./register-hooks.mjs ./verify-almanac.mjs    # 380 checks
node --import ./register-hooks.mjs ./verify-yijing.mjs     # 548 checks
node --import ./register-hooks.mjs ./verify-fengshui.mjs   # 373 checks
node --import ./register-hooks.mjs ./verify-qimen.mjs      # 875 checks
node --import ./register-hooks.mjs ./verify-library.mjs    # 42 checks
```

All five report 0 failures. On this machine the commands are run with the
Windows Node binary (`/mnt/d/DevApps/node/node.exe`).

## Parity and deliberate divergences

RN quirks are preserved unless listed here:

- Java-correct synastry semantics where RN compared objects to strings.
- Null-safety guards where RN would crash (e.g. missing table rows).
- Hexagram branch lookup wraps around the 12 branches like the RN helper.
- Unknown twelve-stages stem returns `400` instead of crashing.
- `meta.counts` on the BaZi chart counts visible stems correctly (RN produced
  `NaN`-keyed counts).
- QMDJ's `styleQimenChart` block is ported as additive chart metadata,
  including its `undefined` quirks: `suitable`/`unsuitable` are absent,
  `meta.bird` is `{}`, `meta.spirit` is `{"style":{}}`.

## Notes

- Data is regenerated from the RN bundle, never hand-transcribed; the pipeline
  and file inventory live in `tools/extract-js-data/README.md`.
- Operational settings live in `bazi-spring/src/main/resources/application.properties`
  (compression, graceful shutdown, forwarded headers, error-detail suppression).
- The phase history, endpoint contracts and architecture rationale are in
  `MIGRATION_PLAN.md`.
