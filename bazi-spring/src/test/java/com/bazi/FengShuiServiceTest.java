package com.bazi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compares the Java Feng Shui port against golden outputs rendered by the
 * original RN code (WindWater.js functions executed directly, no transcription)
 * via tools/extract-js-data/render-fengshui.mjs.
 *
 * <p>Regenerate with:
 * <pre>
 *   cd tools/extract-js-data
 *   node --import ./register-hooks.mjs ./render-fengshui.mjs
 * </pre>
 */
class FengShuiServiceTest {

    private static final ObjectMapper OM = new ObjectMapper();

    private final DataStore store = new DataStore();
    private final BaziService bazi = new BaziService(store, new ShenShaService(store));
    private final FengShuiService fengshui =
            new FengShuiService(store, bazi, new CycleService(store), new ResourceJson());
    private final FengShuiController controller = new FengShuiController(fengshui);

    private JsonNode golden() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/golden/fengshui.json")) {
            assertNotNull(in, "missing test resource /golden/fengshui.json");
            return OM.readTree(in);
        }
    }

    private JsonNode json(Object value) {
        return OM.valueToTree(value);
    }

    /** Deep compare with numeric normalization (int vs double node types). */
    private void assertDeepEquals(JsonNode expected, JsonNode actual, String path) {
        if (expected.isNumber() && actual.isNumber()) {
            assertEquals(expected.asDouble(), actual.asDouble(), 1e-9, path);
            return;
        }
        if (expected.isObject()) {
            assertTrue(actual.isObject(), path + ": expected object, got " + actual);
            Set<String> eKeys = new LinkedHashSet<>();
            expected.fieldNames().forEachRemaining(eKeys::add);
            Set<String> aKeys = new LinkedHashSet<>();
            actual.fieldNames().forEachRemaining(aKeys::add);
            assertEquals(eKeys, aKeys, path + ": key sets differ");
            for (String k : eKeys) assertDeepEquals(expected.get(k), actual.get(k), path + "." + k);
            return;
        }
        if (expected.isArray()) {
            assertTrue(actual.isArray(), path + ": expected array");
            assertEquals(expected.size(), actual.size(), path + ": array sizes differ");
            for (int i = 0; i < expected.size(); i++) {
                assertDeepEquals(expected.get(i), actual.get(i), path + "[" + i + "]");
            }
            return;
        }
        assertEquals(expected, actual, path);
    }

    @Test
    void tablesMatchGolden() throws Exception {
        JsonNode g = golden().get("tables");
        for (String key : new String[]{"mountains", "twentyFourMountainAttributes", "guas",
                "flyingStars", "flyingStarCombinations", "eightMansions", "getEightMansionNaJia",
                "xuanKongNumberRingInner", "xuanKongNumberRingOuter", "xuanKongHexagramInner",
                "xuanKongHexagramOuter", "piercingMountain72Dragons", "earthPenetrating60Dragons",
                "equalDivision60Dragons", "threeAuspiciousSixElegance", "qiMenFengShuiMap"}) {
            assertDeepEquals(g.get(key), json(fengshui.tableDeep(key)), key);
        }
        // RN source gap: NE3 (yin) has no attribute entry.
        assertFalse(json(fengshui.tableDeep("twentyFourMountainAttributes")).has("NE3"));
    }

    @Test
    void degreeToMountainSweep() throws Exception {
        JsonNode rows = golden().get("degreeToMountain");
        assertTrue(rows.size() > 700);
        for (JsonNode row : rows) {
            double degree = row.get("degree").asDouble();
            String actual = fengshui.degreeToMountain(degree);
            JsonNode expected = row.get("sector"); // key missing == RN undefined
            if (expected == null || expected.isNull()) {
                assertNull(actual, "degree " + degree + " expected no sector");
            } else {
                assertEquals(expected.asText(), actual, "degree " + degree);
            }
        }
    }

    @Test
    void yearToPeriodSweep() throws Exception {
        for (JsonNode row : golden().get("yearToPeriod")) {
            int year = row.get("year").asInt();
            JsonNode expected = row.get("period");
            Integer actual = fengshui.yearToPeriod(year);
            if (expected == null || expected.isNull()) {
                assertNull(actual, "year " + year);
            } else {
                assertEquals(expected.asInt(), actual, "year " + year);
            }
        }
    }

    @Test
    void eightMansionsFromFacing() throws Exception {
        JsonNode rows = golden().get("eightMansions");
        assertEquals(8, rows.size());
        for (JsonNode row : rows) {
            String facing = row.get("facing").asText();
            Map<String, Object> palaces = fengshui.getEightMansionsFromFacing(facing);
            assertDeepEquals(row.get("palaces"), json(palaces), facing);
            assertDeepEquals(row.get("extended"), json(fengshui.extendEightMansions(palaces)), facing + " extended");
        }
        // RN throws TypeError for unknown facing ('X'); Java returns null (agreed divergence).
        assertTrue(golden().get("eightMansionsUnknown").has("error"));
        assertNull(fengshui.getEightMansionsFromFacing("X"));
    }

    @Test
    void personalGuaMatchesGolden() throws Exception {
        JsonNode g = golden();
        for (JsonNode row : g.get("personalGua")) {
            int year = row.get("year").asInt();
            String gender = row.get("gender").asText();
            boolean previous = row.get("isPreviousYear").asBoolean();
            Map<String, Object> out = fengshui.personalGua(year, previous, gender);
            assertNotNull(out, "gua " + year);
            JsonNode gua = json(out.get("personalGua"));
            assertDeepEquals(row.get("gua"), gua, "gua " + year + " " + gender + " " + previous);
            // directions come from WindWater.guas[pinyin].eightMansions
            JsonNode dirs = g.get("tables").get("guas").get(gua.get("pinyin").asText()).get("eightMansions");
            assertDeepEquals(dirs, json(out.get("directions")), "directions " + year);
        }
        // unknown gender behaves like female (RN guaList[gender] undefined -> female branch)
        assertEquals(json(fengshui.personalGua(1990, false, "female").get("personalGua")).get("luoShu"),
                json(fengshui.personalGua(1990, false, "x").get("personalGua")).get("luoShu"));
    }

    @Test
    void timeFlyingStarsMatchesGolden() throws Exception {
        for (JsonNode row : golden().get("timeFlyingStars")) {
            String time = row.get("time").asText();
            Map<String, Object> payload = fengshui.timeFlyingStars(time);
            assertDeepEquals(row.get("hour"), json(payload.get("hour")), time + " hour");
            assertDeepEquals(row.get("day"), json(payload.get("day")), time + " day");
            assertDeepEquals(row.get("month"), json(payload.get("month")), time + " month");
            assertDeepEquals(row.get("year"), json(payload.get("year")), time + " year");
        }
    }

    @Test
    void houseFlyingStarsMatchesGolden() throws Exception {
        int sawError = 0;
        for (JsonNode row : golden().get("houseFlyingStars")) {
            JsonNode input = row.get("input");
            String year = input.get("houseYear") == null || input.get("houseYear").isNull()
                    ? null : input.get("houseYear").asText();
            String degree = input.get("houseDegree") == null || input.get("houseDegree").isNull()
                    ? null : input.get("houseDegree").asText();
            Map<String, Object> payload = fengshui.houseFlyingStars(year, degree);
            if (row.has("error")) {
                // RN crashes in the 0.1-degree mountain gaps; Java returns null.
                sawError++;
                assertNull(payload, "gap fixture " + input);
            } else if (row.get("payload").isNull()) {
                assertNull(payload, "invalid fixture " + input);
            } else {
                assertNotNull(payload, "fixture " + input);
                assertDeepEquals(row.get("payload"), json(payload), "house " + input);
            }
        }
        assertTrue(sawError >= 1, "expected at least one RN crash fixture (degree gap)");
    }

    @Test
    void timeFlyingStarsDayScanPerfGuard() {
        // calcDayCenter replays RN's up-to-61-day jia-zi scan (one chart plot
        // per day) on every call. Warm up, then keep 5 calls far below any
        // pathological bound so an unbounded/quadratic regression fails fast.
        String[] times = {
                "2024-06-15T14:30:00+08:00",
                "2024-12-25T09:00:00+08:00",
                "2024-01-15T08:00:00+08:00",
                "2023-06-22T00:30:00+08:00",
                "2023-12-22T23:30:00+08:00",
        };
        fengshui.timeFlyingStars(times[0]); // warm-up (lazy tables/JIT)

        long start = System.nanoTime();
        for (String t : times) {
            Map<String, Object> payload = fengshui.timeFlyingStars(t);
            assertNotNull(payload.get("hour"), t);
            assertNotNull(payload.get("month"), t);
            assertNotNull(payload.get("year"), t);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs < 10_000,
                "5 time-flying-stars calls took " + elapsedMs + " ms (day-scan regression?)");
    }

    @Test
    void luopanLookup() {
        JsonNode north = json(fengshui.luopan(0));
        assertEquals("N2", north.get("sector").asText());
        assertEquals("zi", north.get("mountain").get("pinyin").asText());
        assertEquals("kan", north.get("mountain").get("palace").asText());
        assertTrue(north.get("attributes").isObject());

        JsonNode gap = json(fengshui.luopan(7.55));
        assertTrue(gap.get("sector").isNull());
        assertTrue(gap.get("mountain").isNull());
        assertTrue(gap.get("attributes").isNull());

        // NE3 has no attribute entry in the RN source.
        JsonNode ne3 = json(fengshui.luopan(60));
        assertEquals("NE3", ne3.get("sector").asText());
        assertTrue(ne3.get("attributes").isNull());
    }

    @Test
    void controllerValidationAndDoorEcho() {
        JsonNode res = json(controller.houseFlyingStars("1984", "90", "45"));
        assertEquals(45.0, res.get("doorDegree").asDouble(), 1e-9);
        assertThrows(ResponseStatusException.class, () -> controller.houseFlyingStars("1984", "90", "abc"));
        assertThrows(ResponseStatusException.class, () -> controller.houseFlyingStars("1984", "90", "361"));
        assertThrows(ResponseStatusException.class, () -> controller.houseFlyingStars("1863", "90", null));
        assertThrows(ResponseStatusException.class, () -> controller.houseFlyingStars("1984", "157.55", null));
        assertThrows(ResponseStatusException.class, () -> controller.eightMansions("X"));
        assertThrows(ResponseStatusException.class, () -> controller.luopan("abc"));
        assertThrows(ResponseStatusException.class, () -> controller.timeFlyingStars("garbage"));

        JsonNode pg = json(controller.personalGua(1990, "male", false));
        assertEquals("kan", pg.get("personalGua").get("pinyin").asText());
        assertEquals(1, pg.get("personalGua").get("luoShu").asInt());
    }
}
