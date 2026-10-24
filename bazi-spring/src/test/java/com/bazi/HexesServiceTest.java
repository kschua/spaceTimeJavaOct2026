package com.bazi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compares the Java Yi Jing port against golden outputs rendered by the
 * original RN code (Hexes.js + HexagramSelector.js logic).
 *
 * <p>Regenerate with:
 * <pre>
 *   cd tools/extract-js-data
 *   node --import ./register-hooks.mjs ./render-yijing.mjs
 * </pre>
 */
class HexesServiceTest {

    private static final ObjectMapper OM = new ObjectMapper();

    private final DataStore store = new DataStore();
    private final HexesService hexes = new HexesService(store,
            new BaziService(store, new ShenShaService(store)), new ResourceJson());

    private JsonNode golden() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/golden/yijing.json")) {
            assertNotNull(in, "missing test resource /golden/yijing.json");
            return OM.readTree(in);
        }
    }

    private JsonNode node(Object value) {
        return OM.valueToTree(value);
    }

    /** JSON round-trip so int/long node types match regardless of source map types. */
    private JsonNode normalized(JsonNode n) {
        try {
            return OM.readTree(n.toString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void catalogMatchesRnOrder() throws Exception {
        JsonNode g = golden();
        List<Map<String, Object>> catalog = hexes.catalog();
        assertEquals(g.get("order").size(), catalog.size());
        for (int i = 0; i < catalog.size(); i++) {
            assertEquals(normalized(g.get("order").get(i)), normalized(node(catalog.get(i))),
                    "order[" + i + "]");
        }
    }

    @Test
    void lineDataMatchesRn() throws Exception {
        JsonNode g = golden();
        for (JsonNode fixture : g.get("lineData")) {
            String binary = fixture.get("binary").asText();
            Map<String, Object> noDm = hexes.detail(binary, null);
            Map<String, Object> withDm = hexes.detail(binary, "geng");

            assertEquals(fixture.get("noDm"), node(noDm.get("lineData")),
                    "noDm line data for " + binary);
            assertEquals(fixture.get("withDm"), node(withDm.get("lineData")),
                    "withDm line data for " + binary);
            assertEquals(fixture.get("topTrigram").asText(),
                    ((Map<?, ?>) noDm.get("topTrigram")).get("pinyin"), "top trigram " + binary);
            assertEquals(fixture.get("bottomTrigram").asText(),
                    ((Map<?, ?>) noDm.get("bottomTrigram")).get("pinyin"), "bottom trigram " + binary);

            Map<?, ?> mutual = (Map<?, ?>) noDm.get("mutual");
            assertEquals(fixture.get("mutualBinary").asText(), mutual.get("binary"),
                    "mutual binary for " + binary);
            Map<?, ?> mutualHex = (Map<?, ?>) mutual.get("hexagram");
            assertEquals(fixture.get("mutualNumber").asInt(),
                    ((Number) mutualHex.get("number")).intValue(), "mutual number for " + binary);
        }
    }

    @Test
    void pillarMappingsMatchRn() throws Exception {
        JsonNode g = golden();
        for (JsonNode fixture : g.get("pillars")) {
            String stem = fixture.get("stem").asText();
            String branch = fixture.get("branch").asText();
            Map<String, Object> result = hexes.fromPillar(stem, branch, false);
            assertEquals(fixture.get("numbers"), node(result.get("numbers")),
                    "numbers for " + stem + "-" + branch);
            List<?> hexes = (List<?>) result.get("hexagrams");
            assertEquals(fixture.get("hexagrams").size(), hexes.size(),
                    "hexagram count for " + stem + "-" + branch);
            for (int i = 0; i < hexes.size(); i++) {
                Map<?, ?> h = (Map<?, ?>) hexes.get(i);
                assertEquals(fixture.get("hexagrams").get(i).get("number").asInt(),
                        ((Number) h.get("number")).intValue(), stem + "-" + branch + " #" + i);
                assertEquals(fixture.get("hexagrams").get(i).get("binary").asText(), h.get("binary"),
                        stem + "-" + branch + " #" + i + " binary");
            }
        }
    }

    @Test
    void horaryMappingsMatchRn() throws Exception {
        JsonNode g = golden();
        for (JsonNode fixture : g.get("horary")) {
            String pillar = fixture.get("pillar").asText();
            int dash = pillar.indexOf('-');
            Map<String, Object> result = hexes.fromPillar(
                    pillar.substring(0, dash), pillar.substring(dash + 1), true);
            assertEquals(fixture.get("binary").asText(), result.get("binary"),
                    "horary binary for " + pillar);
            List<?> hexes = (List<?>) result.get("hexagrams");
            assertEquals(1, hexes.size(), "horary hexagram count for " + pillar);
            assertEquals(fixture.get("number").asInt(),
                    ((Number) ((Map<?, ?>) hexes.get(0)).get("number")).intValue(),
                    "horary number for " + pillar);
        }
        // gui-you is absent from the RN horaryTable
        Map<String, Object> missing = hexes.fromPillar("gui", "you", true);
        assertNull(missing.get("binary"));
        assertTrue(((List<?>) missing.get("hexagrams")).isEmpty());
    }

    @Test
    void liSheeYeeMatchesRn() throws Exception {
        JsonNode g = golden();
        for (JsonNode fixture : g.get("lisheeyee")) {
            String q = fixture.get("q").asText();
            String time = fixture.get("time").asText();
            Map<String, Object> result = hexes.liSheeYee(q, time);
            String label = "lisheeyee(" + q + " @ " + time + ")";

            assertEquals(fixture.get("converted").asText(), result.get("converted"), label);
            if (fixture.has("result")) {
                assertFalse(result.containsKey("h1"), label + " should have no result");
                continue;
            }
            assertEquals(fixture.get("h1").asText(), result.get("h1"), label);
            assertEquals(fixture.get("h2").asText(), result.get("h2"), label);
            assertEquals(fixture.get("extra").asText(), result.get("extra"), label);
            assertEquals(fixture.get("firstTrigram").asText(), result.get("firstTrigram"), label);
            assertEquals(fixture.get("secondTrigram").asText(), result.get("secondTrigram"), label);
            assertEquals(fixture.get("numTotal").asInt(), ((Number) result.get("numTotal")).intValue(), label);
            assertEquals(fixture.get("hourBranch").asText(), result.get("hourBranch"), label);
            assertEquals(fixture.get("whichHour").asInt(), ((Number) result.get("whichHour")).intValue(), label);
            assertEquals(fixture.get("movingLine").asInt(), ((Number) result.get("movingLine")).intValue(), label);
            assertEquals(fixture.get("binary").asText(), result.get("binary"), label);
            assertEquals(fixture.get("number").asInt(), ((Number) result.get("number")).intValue(), label);
            assertEquals(fixture.get("changedBinary").asText(), result.get("changedBinary"), label);
            assertEquals(fixture.get("changedNumber").asInt(),
                    ((Number) result.get("changedNumber")).intValue(), label);
        }
    }
}
