package com.bazi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Replays the Bazi golden rendered by the original RN modules
 * (tools/extract-js-data/render-bazi.mjs) through {@link BaziService}.
 *
 * <p>Comparison is RN-subset: every field present in the golden must exist in
 * the Java chart and match; Java-only additive fields are allowed. Numbers are
 * compared with a 1e-9 tolerance and ISO date-times by instant + offset.</p>
 *
 * <p>Documented exclusions (see the renderer header):</p>
 * <ul>
 *   <li>{@code stem.interactions} / {@code branch.interactions} depend on
 *       {@code DateTime.local()} and are asserted to be arrays only.</li>
 *   <li>{@code meta.counts.stems}/{@code .gods}/{@code .stemsCore}/
 *       {@code .godsCore} are excluded: the RN {@code calcCounts} uses stem
 *       objects as keys and is buggy; Java counts correctly (asserted below).</li>
 * </ul>
 */
class BaziServiceTest {

    private static final ObjectMapper OM = new ObjectMapper();

    private final DataStore store = new DataStore();
    private final ShenShaService shensha = new ShenShaService(store);
    private final BaziService bazi = new BaziService(store, shensha);

    private JsonNode golden() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/golden/bazi.json")) {
            assertNotNull(in, "missing test resource /golden/bazi.json");
            return OM.readTree(in);
        }
    }

    private JsonNode json(Object value) {
        return OM.valueToTree(value);
    }

    private BaziService.PlotOptions options(JsonNode options) {
        BaziService.PlotOptions o = new BaziService.PlotOptions();
        JsonNode chartName = options.get("chartName");
        if (chartName != null && chartName.isTextual()) o.chartName = chartName.asText();
        o.gender = options.path("gender").asText("male");
        o.genderUnknown = options.path("genderUnknown").asBoolean(false);
        o.hourUnknown = options.path("hourUnknown").asBoolean(false);
        o.lateZi = options.path("lateZi").asBoolean(false);
        o.showLuck = options.path("showLuck").asBoolean(false);
        o.showWeights = options.path("showWeights").asBoolean(false);
        o.simpleLuckCycle = options.path("simpleLuckCycle").asBoolean(true);
        o.weightsIncludeCombos = options.path("weightsIncludeCombos").asBoolean(true);
        o.extend = options.path("extend").asBoolean(false);
        JsonNode shift = options.get("shiftingPalace");
        if (shift != null && shift.isObject()) o.shiftingPalaceStem = shift.path("stem").asText(null);
        JsonNode root = options.get("rootTime");
        if (root != null && root.isTextual()) o.rootTime = root.asText();
        return o;
    }

    // -------- comparison helpers --------

    private static boolean isIsoDateTime(String s) {
        return s.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?([+-]\\d{2}:\\d{2}|Z)?");
    }

    private static OffsetDateTime parseIso(String s) {
        if (s.endsWith("Z")) s = s.substring(0, s.length() - 1) + "+00:00";
        return OffsetDateTime.parse(s);
    }

    /** Deep RN-subset compare. */
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
        // object
        assertNotNull(actual, path + ": expected object, got null");
        assertTrue(actual.isObject(), path + ": expected object, got " + actual);
        Iterator<Map.Entry<String, JsonNode>> it = expected.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            assertTrue(actual.has(e.getKey()), path + "." + e.getKey() + ": missing in Java chart");
            assertSubset(e.getValue(), actual.get(e.getKey()), path + "." + e.getKey());
        }
    }

    /** Time-dependent interaction arrays: compare the static part only. */
    private JsonNode withoutTimeDependentInteractions(JsonNode pillar) {
        if (pillar == null || !pillar.isObject()) return pillar;
        ObjectNode copy = ((ObjectNode) pillar).deepCopy();
        for (String part : List.of("stem", "branch")) {
            JsonNode node = copy.get(part);
            if (node instanceof ObjectNode o) o.remove("interactions");
        }
        return copy;
    }

    private void assertChartMatches(JsonNode expected, JsonNode actual, String name) {
        JsonNode ePillars = expected.get("pillars");
        JsonNode aPillars = actual.get("pillars");
        for (String key : List.of("year", "month", "day", "hour", "minute", "conception", "life")) {
            JsonNode e = ePillars.get(key);
            JsonNode a = aPillars.get(key);
            if (e == null || e.isNull()) {
                assertTrue(a == null || a.isNull(), name + "." + key + ": expected absent");
                continue;
            }
            assertNotNull(a, name + "." + key + ": missing pillar");
            assertSubset(withoutTimeDependentInteractions(e), withoutTimeDependentInteractions(a),
                    name + ".pillars." + key);
            // interactions exist (contents depend on DateTime.local())
            if (a.get("stem").has("interactions")) assertTrue(a.get("stem").get("interactions").isArray());
            if (a.get("branch").has("interactions")) assertTrue(a.get("branch").get("interactions").isArray());
        }
        JsonNode eMeta = expected.get("meta");
        JsonNode aMeta = actual.get("meta");
        assertSubset(eMeta.get("maps"), aMeta.get("maps"), name + ".meta.maps");
        assertSubset(eMeta.get("gua"), aMeta.get("gua"), name + ".meta.gua");
        for (String field : List.of("counts", "weightsPlain", "weightsCombo", "weights", "stats",
                "usefulGods", "favorable", "unfavorable", "summary")) {
            JsonNode e = eMeta.get(field);
            if (e == null) continue;
            if ("counts".equals(field)) {
                // only the element counts are correct in the RN source
                assertSubset(e.get("elements"), aMeta.get("counts").get("elements"), name + ".meta.counts.elements");
                assertSubset(e.get("elementsCore"), aMeta.get("counts").get("elementsCore"), name + ".meta.counts.elementsCore");
            } else {
                assertSubset(e, aMeta.get(field), name + ".meta." + field);
            }
        }
        assertSubset(expected.get("info"), actual.get("info"), name + ".info");
        // luck: golden stores the compact {stem, branch, time}
        JsonNode eLuck = expected.get("luck");
        JsonNode aLuck = actual.get("luck");
        assertEquals(eLuck.size(), aLuck.size(), name + ".luck size");
        for (int i = 0; i < eLuck.size(); i++) {
            JsonNode e = eLuck.get(i);
            JsonNode a = aLuck.get(i);
            assertEquals(e.get("stem").asText(),
                    a.get("pillar").get("stem").get("name").asText(), name + ".luck[" + i + "].stem");
            assertEquals(e.get("branch").asText(),
                    a.get("pillar").get("branch").get("name").asText(), name + ".luck[" + i + "].branch");
            assertSubset(e.get("time"), a.get("time"), name + ".luck[" + i + "].time");
        }
    }

    // -------- tests --------

    @Test
    void chartsMatchGolden() throws Exception {
        JsonNode g = golden();
        assertTrue(g.get("results").size() >= 12, "expected at least 12 fixtures");
        for (JsonNode fixture : g.get("results")) {
            String name = fixture.get("name").asText();
            BaziService.PlotOptions opts = options(fixture.get("options"));
            opts.time = fixture.get("time").asText();
            JsonNode actual = json(bazi.plot(opts));
            assertChartMatches(fixture.get("chart"), actual, name);
        }
    }

    @Test
    void referenceChartPillars() {
        BaziService.PlotOptions opts = new BaziService.PlotOptions();
        opts.time = "1990-05-15T14:30:00+08:00";
        opts.showLuck = true;
        opts.showWeights = true;
        Map<String, Object> chart = bazi.plot(opts);
        JsonNode j = json(chart);
        JsonNode pillars = j.get("pillars");
        assertEquals("geng", pillars.get("year").get("stem").get("name").asText());
        assertEquals("wu", pillars.get("year").get("branch").get("name").asText());
        assertEquals("xin", pillars.get("month").get("stem").get("name").asText());
        assertEquals("si", pillars.get("month").get("branch").get("name").asText());
        assertEquals("geng", pillars.get("day").get("stem").get("name").asText());
        assertEquals("chen", pillars.get("day").get("branch").get("name").asText());
        assertEquals("gui", pillars.get("hour").get("stem").get("name").asText());
        assertEquals("wei", pillars.get("hour").get("branch").get("name").asText());
        assertEquals("kan", j.get("meta").get("gua").get("pinyin").asText());
        // first luck pillar starts at the birth time, second 3 years later
        assertEquals("1990-05-15T14:30+08:00",
                OffsetDateTime.parse(j.get("luck").get(0).get("time").asText()).toString().replace(":00+", "+"));
        assertEquals("1997-08-04T01:36:40+08:00", j.get("luck").get(1).get("time").asText());
    }

    @Test
    void correctedCountsIncludeVisibleStems() {
        // RN calcCounts() keys stemCounts/godCounts by stem *objects*, so those
        // two maps are broken in RN; Java keys by name. The element counts are
        // correct in RN and match. Note the RN algorithm itself only counts the
        // year/month/hour visible stems (the day stem is intentionally not in
        // its `stems` array), which Java reproduces.
        BaziService.PlotOptions opts = new BaziService.PlotOptions();
        opts.time = "1990-05-15T14:30:00+08:00";
        opts.showWeights = true;
        JsonNode j = json(bazi.plot(opts));
        JsonNode counts = j.get("meta").get("counts");

        JsonNode elements = counts.get("elements");
        int total = 0;
        for (String el : List.of("wood", "fire", "earth", "metal", "water")) total += elements.get(el).asInt();
        int visible = 0;
        for (String key : List.of("year", "month", "hour")) {
            if (j.get("pillars").get(key) != null) visible++;
        }
        int hidden = 0;
        for (String key : List.of("year", "month", "day", "hour")) {
            JsonNode p = j.get("pillars").get(key);
            if (p == null) continue;
            for (JsonNode h : p.get("branch").get("hiddenStems")) if (!h.get("stem").isNull()) hidden++;
        }
        assertEquals(visible + hidden, total, "elements count visible + hidden stems");

        // stems map sums to the same total, and the counted visible stems are present
        JsonNode stems = counts.get("stems");
        int stemTotal = 0;
        Iterator<Map.Entry<String, JsonNode>> it = stems.fields();
        while (it.hasNext()) stemTotal += it.next().getValue().asInt();
        assertEquals(total, stemTotal);
        for (String key : List.of("year", "month", "hour")) {
            JsonNode p = j.get("pillars").get(key);
            if (p == null) continue;
            String s = p.get("stem").get("name").asText();
            assertTrue(stems.get(s).asInt() >= 1, "visible stem " + s + " counted");
        }
        // gods map keys are real god codes, never "[object Object]"/"undefined"
        assertFalse(counts.get("gods").has("[object Object]"));
        assertFalse(counts.get("gods").has("undefined"));
    }

    @Test
    void annualStarsRepeatBranchTogglesTieOrder() {
        // 2000-01-01T00:00:00Z has zi in both month and hour; the RN sort
        // mutates the shared table (ShenSha.annualStarAux.zi.zi) once per
        // pillar with that branch, and every pillar references the same array,
        // so both pillars end up showing the *final* sort state: sorted twice,
        // which restores the equal-rating order ("pristine").
        try {
            JsonNode g = golden();
            JsonNode utc = null;
            for (JsonNode f : g.get("results")) if ("utc".equals(f.get("name").asText())) utc = f;
            assertNotNull(utc);
            BaziService.PlotOptions opts = options(utc.get("options"));
            opts.time = utc.get("time").asText();
            JsonNode j = json(bazi.plot(opts));
            List<String> month = names(j.get("pillars").get("month").get("annualStars"));
            List<String> hour = names(j.get("pillars").get("hour").get("annualStars"));
            List<String> goldenMonth = names(utc.get("chart").get("pillars").get("month").get("annualStars"));
            List<String> goldenHour = names(utc.get("chart").get("pillars").get("hour").get("annualStars"));
            assertEquals(goldenMonth, month);
            assertEquals(goldenHour, hour);
            assertEquals(month, hour, "repeated branches share one array in RN (final sort state)");
        } catch (Exception e) {
            fail(e);
        }
    }

    private static List<String> names(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr != null) arr.forEach(n -> out.add(n.get("name").asText()));
        return out;
    }

    @Test
    void naiveInputUsesSystemZone() {
        // Goldens always use explicit offsets; naive inputs are rendered with
        // the server's zone (luxon DateTime.fromISO without offset -> local).
        BaziService.PlotOptions opts = new BaziService.PlotOptions();
        opts.time = "1990-05-15T14:30:00";
        Map<String, Object> chart = bazi.plot(opts);
        JsonNode j = json(chart);
        assertEquals("1990-05-15T14:30", j.get("info").get("time").asText().substring(0, 16));
        // the wall time is preserved; the offset matches the JVM zone
        assertEquals(java.time.ZoneId.systemDefault().getRules()
                        .getOffset(java.time.LocalDateTime.of(1990, 5, 15, 14, 30).atZone(java.time.ZoneId.systemDefault()).toInstant())
                        .getTotalSeconds() / 60,
                parseIso(j.get("info").get("time").asText()).getOffset().getTotalSeconds() / 60);
    }

    @Test
    void unknownReferenceStemRejected() {
        BaziService.PlotOptions opts = new BaziService.PlotOptions();
        opts.time = "1990-05-15T14:30:00+08:00";
        opts.shiftingPalaceStem = "nope";
        assertThrows(IllegalArgumentException.class, () -> bazi.plot(opts));
    }
}
