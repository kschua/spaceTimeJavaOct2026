package com.bazi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Compares the Java almanac port against golden outputs rendered by the
 * original RN code (Timing.js) with the app's own luxon build.
 *
 * <p>Regenerate the golden file with:
 * <pre>
 *   cd tools/extract-js-data
 *   node --import ./register-hooks.mjs ./render.mjs
 * </pre>
 */
class AlmanacServiceTest {

    private static final ObjectMapper OM = new ObjectMapper();

    private static final List<String> PILLAR_KEYS = List.of("year", "month", "day", "hour");

    private final DataStore store = new DataStore();
    private final BaziService bazi = new BaziService(store, new ShenShaService(store));
    private final AlmanacService almanac = new AlmanacService(new ResourceJson());

    @Test
    void dayInfoMatchesRnGoldens() throws Exception {
        JsonNode golden = loadGolden();
        int checked = 0;

        for (JsonNode fixture : golden.get("results")) {
            String time = fixture.get("time").asText();
            boolean lateZi = fixture.get("lateZi").asBoolean();
            String label = time + (lateZi ? " lateZi" : "");

            BaziService.PlotOptions opts = new BaziService.PlotOptions();
            opts.time = time;
            opts.lateZi = lateZi;
            Map<String, Object> chart = bazi.plot(opts);

            // The chart itself must match what the RN plotter produced.
            @SuppressWarnings("unchecked")
            Map<String, Object> actualPillars = (Map<String, Object>) chart.get("pillars");
            for (String key : PILLAR_KEYS) {
                JsonNode expected = fixture.get("pillars").get(key);
                JsonNode actual = OM.valueToTree(compactPillar(actualPillars.get(key)));
                assertEquals(expected, actual, "pillar " + key + " for " + label);
            }

            JsonNode expectedInfo = fixture.get("dayInfo");
            JsonNode actualInfo = OM.valueToTree(almanac.getAllDayInfo(chart));
            assertEquals(expectedInfo, actualInfo, "dayInfo for " + label);
            checked++;
        }

        assertEquals(53, checked, "fixture count");
    }

    @Test
    void referenceExposesDescriptions() {
        Map<String, Object> ref = almanac.reference();
        assertEquals(12, ((List<?>) ref.get("officers")).size());
        assertEquals(12, ((Map<?, ?>) ref.get("twelveOfficerDesc")).size());
        assertEquals(28, ((Map<?, ?>) ref.get("twentyEightConstellationsDesc")).size());
        assertEquals(3, ((Map<?, ?>) ref.get("threeKillingsDesc")).size());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> compactPillar(Object pillar) {
        Map<String, Object> p = (Map<String, Object>) pillar;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stem", ((Map<String, Object>) p.get("stem")).get("name"));
        out.put("branch", ((Map<String, Object>) p.get("branch")).get("name"));
        return out;
    }

    private JsonNode loadGolden() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/golden/almanac.json")) {
            assertNotNull(in, "missing test resource /golden/almanac.json");
            return OM.readTree(in);
        }
    }
}
