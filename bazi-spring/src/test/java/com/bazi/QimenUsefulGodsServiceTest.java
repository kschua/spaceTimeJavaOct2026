package com.bazi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Replays the phase-4c useful-god golden rendered from the original RN
 * {@code MysticDoors.usefulGodSymbols} plus {@code lib/utils/strings.deCamel}
 * (see tools/extract-js-data/render-qimen.mjs).
 *
 * <p>The RN screens are presentation-only: QiMenUsefulGodList sorts the raw
 * topic keys, deCamel'ed for display, and QiMenUsefulGodDetails maps every
 * {@code [symbol, meaning]} row to {@code {title, body}}.</p>
 */
class QimenUsefulGodsServiceTest {

    private static final ObjectMapper OM = new ObjectMapper();

    private final QimenUsefulGodsService service =
            new QimenUsefulGodsService(new ResourceJson());

    private JsonNode golden() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/golden/qimen.json")) {
            assertNotNull(in, "golden/qimen.json missing - run render-qimen.mjs");
            return OM.readTree(in).get("usefulGods");
        }
    }

    @Test
    void topicsMatchGolden() throws Exception {
        JsonNode g = golden();
        JsonNode topics = g.get("topics");
        List<Map<String, Object>> actual = service.topics();

        assertEquals(topics.size(), actual.size());
        assertEquals(topics.size(), service.topicCount());
        for (int i = 0; i < topics.size(); i++) {
            String key = topics.get(i).asText();
            assertEquals(key, actual.get(i).get("topic"));
            assertEquals(g.get("titles").get(key).asText(), actual.get(i).get("title"),
                    "deCamel(" + key + ")");
        }
    }

    @Test
    void detailsMatchGoldenForEveryTopic() throws Exception {
        JsonNode g = golden();
        int rows = 0;
        for (JsonNode topic : g.get("topics")) {
            String key = topic.asText();
            Map<String, Object> details = service.details(key);
            assertEquals(key, details.get("topic"));
            assertEquals(g.get("titles").get(key).asText(), details.get("title"));

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> symbols = (List<Map<String, Object>>) details.get("symbols");
            JsonNode expected = g.get("data").get(key);
            assertEquals(expected.size(), symbols.size(), key + " symbol count");
            for (int i = 0; i < expected.size(); i++) {
                assertEquals(expected.get(i).get(0).asText(), symbols.get(i).get("title"),
                        key + "[" + i + "].title");
                assertEquals(expected.get(i).get(1).asText(), symbols.get(i).get("body"),
                        key + "[" + i + "].body");
                rows++;
            }
        }
        assertEquals(190, rows, "total symbol rows");
    }

    @Test
    void unknownTopicRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.details("bogus"));
        assertTrue(e.getMessage().contains("bogus"));
    }

    @Test
    void deCamelMatchesRnStringsHelper() {
        assertEquals("Relationships", QimenUsefulGodsService.deCamel("relationships"));
        assertEquals("Body", QimenUsefulGodsService.deCamel("body"));
        assertEquals("", QimenUsefulGodsService.deCamel(null));
        assertEquals("", QimenUsefulGodsService.deCamel(""));
        // JS: toTitle -> insert spaces before capitals, first _ and "  " only
        assertEquals("Foo Bar", QimenUsefulGodsService.deCamel("fooBar"));
        assertEquals("Foo Bar", QimenUsefulGodsService.deCamel("foo bar"));
        // JS replace('_', ' ') only replaces the first underscore
        assertEquals("Foo bar_baz", QimenUsefulGodsService.deCamel("foo_bar_baz"));
    }
}
