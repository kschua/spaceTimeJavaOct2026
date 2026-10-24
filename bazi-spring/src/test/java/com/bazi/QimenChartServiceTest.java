package com.bazi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Replays the QMDJ core-plot golden rendered by the original RN code
 * (Plotters/QiMap/index.js executed directly via the real luxon build) with
 * tools/extract-js-data/render-qimen.mjs.
 *
 * <p>Regenerate with:</p>
 * <pre>
 *   cd tools/extract-js-data
 *   node --import ./register-hooks.mjs ./render-qimen.mjs
 * </pre>
 *
 * <p>Comparison is RN-subset: every field in the golden must exist and match
 * in the Java result (numbers with 1e-9, ISO times by instant+offset). The
 * golden stores a compact bazi digest instead of the full bazi payload (the
 * bazi chart itself is covered by the BaziService golden). Fixtures whose RN
 * run returned null or crashed carry {@code expect: "null"}: the port maps
 * them to a null chart (agreed null-safety, see {@link QimenChartService}).</p>
 */
class QimenChartServiceTest {

    private static final ObjectMapper OM = new ObjectMapper();

    private final DataStore store = new DataStore();
    private final ShenShaService shensha = new ShenShaService(store);
    private final BaziService bazi = new BaziService(store, shensha);
    private final ResourceJson resources = new ResourceJson();
    private final QimenChartService chart = new QimenChartService(
            resources, new QimenService(resources),
            new FengShuiService(store, bazi, new CycleService(store), resources));

    private JsonNode golden() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/golden/qimen.json")) {
            assertNotNull(in, "golden/qimen.json missing - run render-qimen.mjs");
            return OM.readTree(in);
        }
    }

    private Map<String, Object> baziChart(JsonNode fixture) {
        BaziService.PlotOptions o = new BaziService.PlotOptions();
        o.time = fixture.get("time").asText();
        o.lateZi = fixture.get("lateZi").asBoolean();
        o.hourUnknown = fixture.get("hourUnknown").asBoolean();
        return bazi.plot(o);
    }

    private QimenChartService.PlotOptions plotOptions(JsonNode fixture) {
        QimenChartService.PlotOptions o = new QimenChartService.PlotOptions();
        o.reference = fixture.get("reference").asText();
        o.method = fixture.get("method").asInt();
        o.starPath = fixture.get("starPath").asInt();
        o.shiftLateZiStream = fixture.get("shiftLateZiStream").asBoolean();
        o.appendDestiny = fixture.get("destiny").asBoolean();
        o.appendMeta = fixture.get("meta").asBoolean();
        JsonNode hi = fixture.get("houseInput");
        if (hi != null && !hi.isNull()) {
            Map<String, Object> houseInput = new LinkedHashMap<>();
            houseInput.put("houseYear", hi.get("houseYear").asInt());
            houseInput.put("houseDegree", hi.get("houseDegree").asDouble());
            houseInput.put("doorDegree", hi.get("doorDegree").asDouble());
            o.houseInput = houseInput;
        }
        return o;
    }

    private static String label(JsonNode f) {
        return f.get("time").asText() + " " + f.get("reference").asText()
                + " method=" + f.get("method").asInt() + " starPath=" + f.get("starPath").asInt();
    }

    // ------------------------------------------------------------- the replay

    @Test
    void chartsMatchGolden() throws Exception {
        JsonNode golden = golden();
        JsonNode charts = golden.get("charts");
        assertNotNull(charts, "golden charts missing - run render-qimen.mjs");
        assertEquals(26, charts.size(), "chart fixture count");

        int checked = 0;
        for (JsonNode fixture : charts) {
            Map<String, Object> baziChart = baziChart(fixture);
            Map<String, Object> result = chart.plot(baziChart, plotOptions(fixture));
            String label = label(fixture);

            if ("null".equals(fixture.get("expect").asText())) {
                assertNull(result, label + ": expected null chart (RN "
                        + (fixture.get("error").isNull() ? "null" : "crash: " + fixture.get("error").asText()) + ")");
                checked++;
                continue;
            }

            assertNotNull(result, label + ": expected a chart");
            JsonNode expected = fixture.get("chart");
            assertNotNull(expected, label + ": golden expects a chart");
            assertSubset(expected.get("palaces"), json(result.get("palaces")), label + ".palaces");
            assertSubset(expected.get("details"), json(result.get("details")), label + ".details");
            assertBaziDigest(expected.get("bazi"), (Map<?, ?>) result.get("bazi"), label + ".bazi");
            checked++;
        }
        assertEquals(26, checked, "total chart checks");
    }

    // --------------------------------------------------------- spot behaviors

    @Test
    void leadStarFlagKeepsRnQuirk() {
        Map<String, Object> result = plot("1990-05-15T14:30:00+08:00", "hour", null);
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> palaces = (Map<String, Map<String, Object>>) result.get("palaces");
        for (String name : QimenChartService.PALACES_LUO_SHU) {
            @SuppressWarnings("unchecked")
            Map<String, Object> plot = (Map<String, Object>) palaces.get(name).get("plot");
            assertEquals(plot.get("isLeadDoor"), plot.get("isLeadStar"),
                    "isLeadStar compares leadDoor in RN (" + name + ")");
        }
    }

    @Test
    void centerPalaceShape() {
        Map<String, Object> result = plot("1990-05-15T14:30:00+08:00", "hour", null);
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> palaces = (Map<String, Map<String, Object>>) result.get("palaces");
        @SuppressWarnings("unchecked")
        Map<String, Object> centerPlot = (Map<String, Object>) palaces.get("center").get("plot");
        assertFalse(centerPlot.containsKey("door"));
        assertFalse(centerPlot.containsKey("spirit"));
        assertEquals("bird", centerPlot.get("star")); // starPath 0 always birds the center
        assertEquals(5, centerPlot.get("number"));
        assertEquals(centerPlot.get("earthStem"), centerPlot.get("heavenStem"));
        assertTrue(((Map<?, ?>) palaces.get("center").get("meta")).isEmpty(), "center meta stays empty");
    }

    @Test
    void structuresMatchedRuleKeepsJsonStringifyForm() {
        // 1990-05-15 14:30 hour: xun matches moon_prosperity
        Map<String, Object> result = plot("1990-05-15T14:30:00+08:00", "hour", null);
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> palaces = (Map<String, Map<String, Object>>) result.get("palaces");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> structures = (List<Map<String, Object>>)
                ((Map<String, Object>) palaces.get("xun").get("meta")).get("structuresArray");
        assertEquals(1, structures.size());
        Map<String, Object> match = structures.get(0);
        assertEquals("moon_prosperity", match.get("name"));
        assertEquals(1, match.get("rating"));
        // JSON.stringify(rule): key order from the table, no spaces
        assertEquals("{\"heavenStem\":\"bing\",\"palace\":\"xun\"}", match.get("matchedRule"));

        @SuppressWarnings("unchecked")
        Map<String, List<Map<String, Object>>> map = (Map<String, List<Map<String, Object>>>)
                ((Map<String, Object>) palaces.get("xun").get("meta")).get("structuresMap");
        assertEquals(1, map.get("positive").size());
        assertSame(match, map.get("positive").get(0), "same object lands in array and bucket");
        assertTrue(map.get("neutral").isEmpty());
        assertTrue(map.get("negative").isEmpty());
    }

    @Test
    void juTypesAndHouseDetails() {
        // zhiRun keeps the string type, chaiBu the number (parity with phase 4a)
        Map<String, Object> hour = plot("1990-05-15T14:30:00+08:00", "hour", null);
        assertEquals("5", detailsOf(hour).get("ju"));

        QimenChartService.PlotOptions o = new QimenChartService.PlotOptions();
        o.reference = "hour";
        o.method = 1;
        Map<String, Object> chai = chart.plot(baziChart("1990-05-15T14:30:00+08:00", false), o);
        assertEquals(4, detailsOf(chai).get("ju"));

        QimenChartService.PlotOptions h = new QimenChartService.PlotOptions();
        h.reference = "house";
        Map<String, Object> houseInput = new LinkedHashMap<>();
        houseInput.put("houseYear", 2024);
        houseInput.put("houseDegree", 60.5);
        houseInput.put("doorDegree", 210.5);
        h.houseInput = houseInput;
        Map<String, Object> house = chart.plot(baziChart("1990-05-15T14:30:00+08:00", false), h);
        Map<String, Object> details = detailsOf(house);
        assertEquals(9, details.get("ju"));
        assertEquals(9, details.get("housePeriod"));
        assertEquals("yi wei", details.get("houseStemBranch"));
        assertEquals(houseInput, details.get("houseInput"));
        assertEquals("yang", details.get("dun"));
    }

    @Test
    void houseInputNeedsAllThreeFields() {
        QimenChartService.PlotOptions o = new QimenChartService.PlotOptions();
        o.reference = "house";
        assertNull(chart.plot(baziChart("1990-05-15T14:30:00+08:00", false), o));

        Map<String, Object> partial = new LinkedHashMap<>();
        partial.put("houseYear", 2024);
        partial.put("houseDegree", null);
        partial.put("doorDegree", 210.5);
        o.houseInput = partial;
        assertNull(chart.plot(baziChart("1990-05-15T14:30:00+08:00", false), o));

        Map<String, Object> zero = new LinkedHashMap<>();
        zero.put("houseYear", 2024);
        zero.put("houseDegree", 0.0);
        zero.put("doorDegree", 210.5);
        o.houseInput = zero;
        assertNull(chart.plot(baziChart("1990-05-15T14:30:00+08:00", false), o));
    }

    @Test
    void houseRangeAndGapDegreesReject() {
        QimenChartService.PlotOptions o = new QimenChartService.PlotOptions();
        o.reference = "house";
        Map<String, Object> houseInput = new LinkedHashMap<>();
        houseInput.put("houseYear", 1700); // outside 1844-2083
        houseInput.put("houseDegree", 60.5);
        houseInput.put("doorDegree", 210.5);
        o.houseInput = houseInput;
        IllegalArgumentException year = assertThrows(IllegalArgumentException.class,
                () -> chart.plot(baziChart("1990-05-15T14:30:00+08:00", false), o));
        assertTrue(year.getMessage().contains("1844-2083"));

        houseInput.put("houseYear", 2024);
        houseInput.put("doorDegree", 7.55); // 0.1 degree gap between N2 and N3
        IllegalArgumentException gap = assertThrows(IllegalArgumentException.class,
                () -> chart.plot(baziChart("1990-05-15T14:30:00+08:00", false), o));
        assertTrue(gap.getMessage().contains("between mountains"));
    }

    @Test
    void hourUnknownFallback() {
        // reference=hour: RN crashes on the null hour pillar -> null chart
        QimenChartService.PlotOptions o = new QimenChartService.PlotOptions();
        assertNull(chart.plot(baziChart("1990-05-15T14:30:00+08:00", true), o));

        // other references: RN crashes in meta -> port omits doorInteraction
        QimenChartService.PlotOptions day = new QimenChartService.PlotOptions();
        day.reference = "day";
        Map<String, Object> result = chart.plot(baziChart("1990-05-15T14:30:00+08:00", true), day);
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> palaces = (Map<String, Map<String, Object>>) result.get("palaces");
        @SuppressWarnings("unchecked")
        Map<String, Object> hourMeta = (Map<String, Object>)
                ((Map<String, Object>) palaces.get("kan").get("meta")).get("hour");
        assertFalse(hourMeta.containsKey("doorInteraction"));
    }

    @Test
    void destinyAddsRangesLabelsAndObstructions() {
        QimenChartService.PlotOptions o = new QimenChartService.PlotOptions();
        o.reference = "month";
        o.appendDestiny = true;
        Map<String, Object> result = chart.plot(baziChart("1990-05-15T14:30:00+08:00", false), o);
        Map<String, Object> details = detailsOf(result);
        assertEquals("xun", details.get("destinyPalace")); // golden fixture value

        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> palaces = (Map<String, Map<String, Object>>) result.get("palaces");
        @SuppressWarnings("unchecked")
        Map<String, Object> destiny = (Map<String, Object>)
                ((Map<String, Object>) palaces.get("xun").get("meta")).get("destiny");
        assertEquals(List.of(61, 75), destiny.get("range"));
        assertEquals(Boolean.TRUE, destiny.get("isDestinyPalace"));
        @SuppressWarnings("unchecked")
        List<String> labels = (List<String>) destiny.get("labels");
        assertEquals(List.of("retirement", "family", "health", "subconscious", "parent"), labels);

        // countering palaces for xun are dui/qian and get the obstruction label
        @SuppressWarnings("unchecked")
        Map<String, Object> dui = (Map<String, Object>) palaces.get("dui").get("meta");
        @SuppressWarnings("unchecked")
        List<String> duiLabels = (List<String>) ((Map<String, Object>) dui.get("destiny")).get("labels");
        assertTrue(duiLabels.contains("obstruction"));
        @SuppressWarnings("unchecked")
        List<String> qianLabels = (List<String>) ((Map<String, Object>)
                ((Map<String, Object>) palaces.get("qian").get("meta")).get("destiny")).get("labels");
        assertTrue(qianLabels.contains("obstruction"));
        assertFalse(((Map<?, ?>) palaces.get("center").get("meta")).containsKey("destiny"));
    }

    @Test
    void metaSwitches() {
        // meta=false: no victories, empty per-palace meta; destiny still works
        QimenChartService.PlotOptions o = new QimenChartService.PlotOptions();
        o.appendMeta = false;
        Map<String, Object> result = chart.plot(baziChart("1990-05-15T14:30:00+08:00", false), o);
        assertFalse(detailsOf(result).containsKey("victories"));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> palaces = (Map<String, Map<String, Object>>) result.get("palaces");
        assertTrue(((Map<?, ?>) palaces.get("kan").get("meta")).isEmpty());
    }

    // -------------------------------------------------------------- helpers

    private Map<String, Object> plot(String time, String reference, Map<String, Object> houseInput) {
        QimenChartService.PlotOptions o = new QimenChartService.PlotOptions();
        o.reference = reference;
        o.houseInput = houseInput;
        return chart.plot(baziChart(time, false), o);
    }

    private Map<String, Object> baziChart(String time, boolean hourUnknown) {
        BaziService.PlotOptions o = new BaziService.PlotOptions();
        o.time = time;
        o.hourUnknown = hourUnknown;
        return bazi.plot(o);
    }

    private JsonNode json(Object value) {
        return OM.valueToTree(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detailsOf(Map<String, Object> chart) {
        return (Map<String, Object>) chart.get("details");
    }

    // -------- comparison helpers (same RN-subset semantics as BaziServiceTest)

    private static boolean isIsoDateTime(String s) {
        return s.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?([+-]\\d{2}:\\d{2}|Z)?");
    }

    private static OffsetDateTime parseIso(String s) {
        if (s.endsWith("Z")) s = s.substring(0, s.length() - 1) + "+00:00";
        return OffsetDateTime.parse(s);
    }

    private void assertSubset(JsonNode expected, JsonNode actual, String path) {
        if (expected == null || expected.isNull()) {
            assertTrue(actual == null || actual.isNull(), path + ": expected null, got " + actual);
            return;
        }
        if (expected.isNumber()) {
            assertNotNull(actual, path + ": expected number " + expected + ", got null");
            assertTrue(actual.isNumber(), path + ": expected number " + expected + ", got " + actual);
            assertEquals(expected.asDouble(), actual.asDouble(), 1e-9, path);
            return;
        }
        if (expected.isTextual()) {
            assertNotNull(actual, path + ": expected \"" + expected.asText() + "\", got null");
            assertTrue(actual.isTextual(), path + ": expected string, got " + actual);
            if (isIsoDateTime(expected.asText()) && isIsoDateTime(actual.asText())) {
                OffsetDateTime e = parseIso(expected.asText());
                OffsetDateTime a = parseIso(actual.asText());
                assertEquals(e.toInstant(), a.toInstant(), path + ": instant");
                assertEquals(e.getOffset(), a.getOffset(), path + ": offset");
            } else {
                assertEquals(expected.asText(), actual.asText(), path);
            }
            return;
        }
        if (expected.isBoolean()) {
            assertNotNull(actual, path + ": expected " + expected + ", got null");
            assertTrue(actual.isBoolean(), path + ": expected boolean, got " + actual);
            assertEquals(expected.asBoolean(), actual.asBoolean(), path);
            return;
        }
        if (expected.isArray()) {
            assertNotNull(actual, path + ": expected array, got null");
            assertTrue(actual.isArray(), path + ": expected array, got " + actual);
            assertEquals(expected.size(), actual.size(), path + ": array sizes differ");
            for (int i = 0; i < expected.size(); i++) {
                assertSubset(expected.get(i), actual.get(i), path + "[" + i + "]");
            }
            return;
        }
        assertNotNull(actual, path + ": expected object, got null");
        assertTrue(actual.isObject(), path + ": expected object, got " + actual);
        Iterator<Map.Entry<String, JsonNode>> it = expected.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            assertTrue(actual.has(e.getKey()), path + "." + e.getKey() + ": missing in Java chart");
            assertSubset(e.getValue(), actual.get(e.getKey()), path + "." + e.getKey());
        }
    }

    /** The bazi digest stored in the golden vs the full Java bazi payload. */
    private void assertBaziDigest(JsonNode digest, Map<?, ?> bazi, String path) {
        @SuppressWarnings("unchecked")
        Map<String, Object> info = (Map<String, Object>) bazi.get("info");
        JsonNode infoTime = json(info.get("time"));
        assertSubset(digest.get("infoTime"), infoTime, path + ".infoTime");
        assertEquals(digest.get("isPreviousYear").asBoolean(), info.get("isPreviousYear"), path + ".isPreviousYear");
        @SuppressWarnings("unchecked")
        Map<String, Object> solarTerm = (Map<String, Object>) info.get("solarTerm");
        @SuppressWarnings("unchecked")
        Map<String, Object> thisTerm = (Map<String, Object>) solarTerm.get("thisTerm");
        @SuppressWarnings("unchecked")
        Map<String, Object> termData = (Map<String, Object>) thisTerm.get("data");
        assertEquals(digest.get("solarTermNum").asInt(),
                ((Number) termData.get("num")).intValue(), path + ".solarTermNum");

        @SuppressWarnings("unchecked")
        Map<String, Object> pillars = (Map<String, Object>) bazi.get("pillars");
        for (String key : List.of("year", "month", "day", "hour")) {
            JsonNode expected = digest.get("pillars").get(key);
            @SuppressWarnings("unchecked")
            Map<String, Object> pillar = (Map<String, Object>) pillars.get(key);
            if (expected == null || expected.isNull()) {
                assertNull(pillar, path + ".pillars." + key + ": expected null");
                continue;
            }
            assertNotNull(pillar, path + ".pillars." + key);
            assertEquals(expected.get("stem").asText(),
                    ((Map<?, ?>) pillar.get("stem")).get("name"), path + ".pillars." + key + ".stem");
            assertEquals(expected.get("branch").asText(),
                    ((Map<?, ?>) pillar.get("branch")).get("name"), path + ".pillars." + key + ".branch");
        }
    }
}
