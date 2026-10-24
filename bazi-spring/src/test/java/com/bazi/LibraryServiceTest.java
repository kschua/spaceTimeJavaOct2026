package com.bazi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compares the Java Library port against golden outputs rendered from the
 * original RN sources (Compendium/index.js + Titles.js imported directly; the
 * Info component tables brace-extracted; the TwelveStagesList loop run over
 * the real Ordinals data) via tools/extract-js-data/render-library.mjs.
 *
 * <p>Regenerate with:
 * <pre>
 *   cd tools/extract-js-data
 *   node --import ./register-hooks.mjs ./render-library.mjs
 * </pre>
 */
class LibraryServiceTest {

    private static final ObjectMapper OM = new ObjectMapper();

    private final LibraryService library = new LibraryService(new ResourceJson(), new DataStore());

    private JsonNode golden() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/golden/library.json")) {
            assertNotNull(in, "golden/library.json missing - run render-library.mjs");
            return OM.readTree(in);
        }
    }

    /** Round-trips the Java values through JSON so number types compare cleanly. */
    private void assertSameTree(JsonNode expected, Object actual, String label) {
        try {
            JsonNode actualNode = OM.readTree(OM.writeValueAsString(actual));
            assertEquals(OM.writeValueAsString(expected), OM.writeValueAsString(actualNode), label);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void compendiumMatchesGolden() throws Exception {
        JsonNode golden = golden();
        Map<String, Object> live = library.compendium();
        assertSameTree(golden.get("compendium"), live.get("tree"), "compendium tree");
        assertSameTree(golden.get("titles"), live.get("titles"), "titles");

        // the tree keeps RN's section markers verbatim
        @SuppressWarnings("unchecked")
        Map<String, Object> tree = (Map<String, Object>) live.get("tree");
        assertEquals(30, tree.size());
        assertTrue(tree.containsKey("$header_fundamentals"));
        assertTrue(tree.containsKey("$push_yinYang"));
        assertEquals("2 Yin Yang", ((Map<?, ?>) tree.get("$push_yinYang")).get("title"));
        assertSameTree(golden.get("compendium").get("qiMenDunJia"),
                tree.get("qiMenDunJia"), "qiMenDunJia subtree");
    }

    @Test
    void relationshipsMatchGolden() throws Exception {
        JsonNode golden = golden();

        Map<String, Object> all = library.relationships(null);
        assertSameTree(golden.get("relationships"), all.get("sections"), "all sections");

        // RN filter semantics: exact key -> one section, unknown -> empty
        Map<String, Object> one = library.relationships("clashes");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sections = (List<Map<String, Object>>) one.get("sections");
        assertEquals(1, sections.size());
        assertEquals("Clashes", sections.get(0).get("title"));
        assertEquals(List.of(), ((List<?>) library.relationships("bogus").get("sections")));

        // spot-check the row shape: first clash is zi + wu
        @SuppressWarnings("unchecked")
        List<List<Map<String, Object>>> rows = (List<List<Map<String, Object>>>) sections.get(0).get("data");
        assertEquals(6, rows.size());
        assertEquals("zi", rows.get(0).get(0).get("val"));
        assertEquals("branch", rows.get(0).get(0).get("type"));
        assertEquals("wu", rows.get(0).get(1).get("val"));
    }

    @Test
    void twelveStagesMatchGolden() throws Exception {
        JsonNode golden = golden();
        assertEquals(10, golden.get("twelveStages").size());

        for (JsonNode stem : golden.get("twelveStages")) {
            String name = stem.get("stem").get("name").asText();
            Map<String, Object> live = library.twelveStages(name);
            assertSameTree(stem, live, "twelve stages " + name);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> stages = (List<Map<String, Object>>) live.get("stages");
            assertEquals(12, stages.size(), name + " stage count");
        }

        // rows follow branchOrdering (zi first), not the stem's own cycle order:
        // jia's twelveStages starts at hai, so zi is its second stage (bath)
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> jia = (List<Map<String, Object>>) library.twelveStages("jia").get("stages");
        assertEquals("zi", jia.get(0).get("branch"));
        assertEquals("bath", ((Map<?, ?>) jia.get(0).get("stage")).get("name"));
        assertEquals("chou", jia.get(1).get("branch"));
        assertEquals("youth", ((Map<?, ?>) jia.get(1).get("stage")).get("name"));

        assertThrows(IllegalArgumentException.class, () -> library.twelveStages(null));
        assertThrows(IllegalArgumentException.class, () -> library.twelveStages("bogus"));
    }

    @Test
    void referenceTablesMatchGolden() throws Exception {
        JsonNode golden = golden();

        Map<String, Object> yinYang = library.yinYang();
        assertSameTree(golden.get("yinYang"), yinYang.get("words"), "yinYang");
        @SuppressWarnings("unchecked")
        List<Object> words = (List<Object>) yinYang.get("words");
        assertEquals(18, words.size());
        assertEquals(List.of("Positive", "Negative"), words.get(0));

        Map<String, Object> hem = library.heavenEarthMan();
        assertSameTree(golden.get("heavenEarthMan"), hem, "heavenEarthMan");
        assertEquals(11, ((List<?>) hem.get("heaven")).size());
        assertEquals("Source", ((List<?>) hem.get("heaven")).get(0));
        assertEquals("Branches", ((List<?>) hem.get("earth")).get(10));
        assertEquals("Roots", ((List<?>) hem.get("man")).get(10));
    }

    @Test
    void tutorialsMatchGolden() throws Exception {
        JsonNode golden = golden();
        Map<String, Object> live = library.tutorials();
        assertSameTree(golden.get("tutorials"), live.get("tutorials"), "tutorials");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tutorials = (List<Map<String, Object>>) live.get("tutorials");
        assertEquals(4, tutorials.size());
        assertEquals(List.of("bazi", "calendar", "hexagrams", "qimen"),
                tutorials.stream().map(t -> t.get("key")).toList());
        assertEquals(List.of("Bazi", "Calendar", "Yi Jing", "QMDJ"),
                tutorials.stream().map(t -> t.get("title")).toList());
        assertEquals(7, ((List<?>) tutorials.get(0).get("pages")).size());
        assertEquals(8, ((List<?>) tutorials.get(3).get("pages")).size());

        @SuppressWarnings("unchecked")
        Map<String, Object> first = (Map<String, Object>) ((List<?>) tutorials.get(0).get("pages")).get(0);
        assertEquals("Top section is your chart.", first.get("title"));
        assertEquals("https://imgur.com/JwJ1hyG.jpg", first.get("image"));
        assertEquals("", first.get("subtitle"));
    }
}
