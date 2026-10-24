package com.bazi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compares the Java QMDJ ju port against golden outputs rendered by the
 * original RN code (Plotters/QiMap/Jus.js executed directly, no transcription)
 * via tools/extract-js-data/render-qimen.mjs.
 *
 * <p>Regenerate with:
 * <pre>
 *   cd tools/extract-js-data
 *   node --import ./register-hooks.mjs ./render-qimen.mjs
 * </pre>
 */
class QimenServiceTest {

    private static final ObjectMapper OM = new ObjectMapper();

    private final DataStore store = new DataStore();
    private final BaziService bazi = new BaziService(store, new ShenShaService(store));
    private final QimenService qimen = new QimenService(new ResourceJson());

    private JsonNode golden() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/golden/qimen.json")) {
            assertNotNull(in, "golden/qimen.json missing - run render-qimen.mjs");
            return OM.readTree(in);
        }
    }

    private Map<String, Object> plot(JsonNode fixture) {
        BaziService.PlotOptions o = new BaziService.PlotOptions();
        o.time = fixture.get("time").asText();
        o.lateZi = fixture.get("lateZi").asBoolean();
        o.hourUnknown = fixture.get("hourUnknown").asBoolean();
        return bazi.plot(o);
    }

    private static void assertSameResult(Map<String, Object> actual, JsonNode expected, String label) {
        if (expected == null || expected.isNull()) {
            assertNull(actual, label);
            return;
        }
        assertNotNull(actual, label);
        assertEquals(expected.get("dun").asText(), actual.get("dun"), label + ".dun");
        assertEquals(expected.get("ju").asText(), String.valueOf(actual.get("ju")), label + ".ju");
    }

    @Test
    void zhiRunAndChaiBuMatchGolden() throws Exception {
        JsonNode golden = golden();
        assertEquals(26, golden.get("results").size(), "fixture count");

        int checked = 0;
        for (JsonNode fixture : golden.get("results")) {
            String time = fixture.get("time").asText();
            Map<String, Object> chart = plot(fixture);

            assertEquals(fixture.get("isPreviousYear").asBoolean(),
                    ((Map<?, ?>) chart.get("info")).get("isPreviousYear"), "isPreviousYear " + time);

            for (String ref : QimenService.REFERENCES) {
                Map<String, Object> actual = qimen.zhiRun(ref, chart);
                assertSameResult(actual, fixture.get("zhiRun").get(ref), "zhiRun " + ref + " " + time);
                if (actual != null) {
                    assertInstanceOf(String.class, actual.get("ju"), "zhiRun ju type " + ref + " " + time);
                }
                checked++;
            }

            Map<String, Object> chai = qimen.chaiBu(chart);
            assertSameResult(chai, fixture.get("chaiBu"), "chaiBu " + time);
            assertNotNull(chai, "chaiBu never misses: " + time);
            assertInstanceOf(Integer.class, chai.get("ju"), "chaiBu ju type " + time);
            checked++;
        }
        assertEquals(26 * 6, checked, "total checks");
    }

    @Test
    void tableGapsKeepRnBehavior() {
        // The single "0-undefined" crawl day returns the literal string, dun yin.
        Map<String, Object> chart = plot(goldenFixture("2021-12-21T12:00:00+08:00", false, false));
        Map<String, Object> day = qimen.zhiRun("day", chart);
        assertNotNull(day);
        assertEquals("yin", day.get("dun"));
        assertEquals("undefined", day.get("ju"));

        // hourJus stops at 2050 -> hour/destiny miss, day/month/year still hit.
        Map<String, Object> chart2051 = plot(goldenFixture("2051-01-01T12:00:00+08:00", false, false));
        assertNull(qimen.zhiRun("hour", chart2051));
        assertNull(qimen.zhiRun("destiny", chart2051));
        assertNotNull(qimen.zhiRun("day", chart2051));
        assertNotNull(qimen.zhiRun("month", chart2051));

        // Pre-li-chun January 1900 is isPreviousYear, so month/year query 1899 and miss.
        Map<String, Object> chart1900 = plot(goldenFixture("1900-01-15T12:00:00+08:00", false, false));
        assertEquals(Boolean.TRUE, ((Map<?, ?>) chart1900.get("info")).get("isPreviousYear"));
        assertNull(qimen.zhiRun("month", chart1900));
        assertNull(qimen.zhiRun("year", chart1900));
        assertNotNull(qimen.zhiRun("hour", chart1900)); // hours are keyed by the same year, so they hit

        // Unknown references mirror the RN failing split -> null.
        assertNull(qimen.zhiRun("house", chart));
        assertNull(qimen.zhiRun("bogus", chart));

        // chaiBu: ju keeps the numeric type from the solarJus tables.
        Map<String, Object> ref = plot(goldenFixture("1990-05-15T14:30:00+08:00", false, false));
        Map<String, Object> chai = qimen.chaiBu(ref);
        assertEquals("yang", chai.get("dun"));
        assertEquals(4, chai.get("ju"));
    }

    @Test
    void displayTerminologyMatchesGolden() throws Exception {
        JsonNode expected = golden().get("terminology");
        assertNotNull(expected, "golden terminology missing - run render-qimen.mjs");
        assertEquals(3, expected.size(), "naming conventions");

        Map<String, Object> actual = qimen.displayTerminology();
        assertEquals(3, actual.size());
        for (String conv : new String[]{"0", "1", "2"}) {
            JsonNode e = expected.get(conv);
            Map<String, Object> a = castMap(actual.get(conv));
            assertEquals(strings(e.get("spirit")), OM.writeValueAsString(a.get("spirit")),
                    "conv " + conv + " spirit");
            assertEquals(strings(e.get("star")), OM.writeValueAsString(a.get("star")),
                    "conv " + conv + " star");
            assertEquals(strings(e.get("door")), OM.writeValueAsString(a.get("door")),
                    "conv " + conv + " door");
        }

        // spot-check the RN quirks: assistant's display word is "assist", conv 2 birds
        assertEquals("assist", stringValue(actual, "0", "star", "assistant", 0));
        assertEquals("CEO", stringValue(actual, "1", "spirit", "chief", 0));
        assertEquals("beast", stringValue(actual, "2", "star", "bird", 0));
        assertEquals("符", stringValue(actual, "0", "spirit", "chief", 1));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return (Map<String, Object>) o;
    }

    /** JSON.stringify of the golden subtree - compares order + values in one go. */
    private static String strings(JsonNode node) throws Exception {
        return OM.writeValueAsString(OM.treeToValue(node, Object.class));
    }

    @SuppressWarnings("unchecked")
    private static String stringValue(Map<String, Object> table, String conv, String kind,
                                      String key, int index) {
        Map<String, Object> c = (Map<String, Object>) table.get(conv);
        Map<String, Object> k = (Map<String, Object>) c.get(kind);
        return String.valueOf(((java.util.List<Object>) k.get(key)).get(index));
    }

    private JsonNode goldenFixture(String time, boolean lateZi, boolean hourUnknown) {
        try {
            for (JsonNode fixture : golden().get("results")) {
                if (time.equals(fixture.get("time").asText())
                        && lateZi == fixture.get("lateZi").asBoolean()
                        && hourUnknown == fixture.get("hourUnknown").asBoolean()) {
                    return fixture;
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        throw new IllegalArgumentException("no golden fixture for " + time);
    }
}
