package com.bazi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Replays the phase-4d day/hour scan golden. The golden is produced by
 * tools/extract-js-data/qimen-days.mjs, which runs the exact Qi Men loop from
 * the RN Almanac component with the real FourPillars + QiMap modules, and
 * QiMenAssist.isThreeVictoryDay.
 */
class QimenDayListServiceTest {

    private static final ObjectMapper OM = new ObjectMapper();

    private final DataStore store = new DataStore();
    private final ShenShaService shensha = new ShenShaService(store);
    private final BaziService bazi = new BaziService(store, shensha);
    private final ResourceJson resources = new ResourceJson();
    private final QimenChartService charts = new QimenChartService(
            resources, new QimenService(resources),
            new FengShuiService(store, bazi, new CycleService(store), resources));
    private final QimenDayListService days =
            new QimenDayListService(bazi, charts, store, resources);

    private JsonNode golden() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/golden/qimen.json")) {
            assertNotNull(in, "golden/qimen.json missing - run render-qimen.mjs");
            return OM.readTree(in).get("dayScans");
        }
    }

    @Test
    void dayScansMatchGolden() throws Exception {
        JsonNode fixtures = golden();
        assertTrue(fixtures.size() > 0, "dayScans missing from golden");

        int goodHours = 0;
        int victories = 0;
        for (JsonNode f : fixtures) {
            QimenDayListService.Options o = new QimenDayListService.Options();
            o.lateZi = f.get("lateZi").asBoolean();
            o.method = f.get("method").asInt();
            o.starPath = f.get("starPath").asInt();
            o.shiftLateZiStream = f.get("shiftLateZiStream").asBoolean();
            o.birthTime = f.get("birthTime").isNull() ? null : f.get("birthTime").asText();
            JsonNode lookups = f.get("lookups");
            o.doors = strings(lookups.get("doors"));
            o.stars = strings(lookups.get("stars"));
            o.spirits = strings(lookups.get("spirits"));
            o.structures = strings(lookups.get("structures"));
            o.maxNegativeStructures = lookups.get("maxNegativeStructures").asInt();

            Map<String, Object> result = days.scan(f.get("from").asText(), f.get("to").asText(), o);
            String label = f.get("from").asText() + ".." + f.get("to").asText();

            assertEquals(localDate(f.get("from").asText()), result.get("from"), label);
            assertEquals(localDate(f.get("to").asText()), result.get("to"), label);
            assertEquals(o.lateZi, result.get("lateZi"), label);
            assertEquals(o.method, result.get("method"), label);
            assertEquals(o.starPath, result.get("starPath"), label);
            assertEquals(o.shiftLateZiStream, result.get("shiftLateZiStream"), label);
            assertEquals(o.birthTime != null, result.get("victory"), label);
            assertLookups(lookups, castMap(result.get("lookups")), label);

            List<Object> actualDays = castList(result.get("days"));
            assertEquals(f.get("days").size(), actualDays.size(), label + " day count");
            for (int i = 0; i < f.get("days").size(); i++) {
                JsonNode expectedDay = f.get("days").get(i);
                Map<String, Object> actualDay = castMap(actualDays.get(i));
                String dayLabel = label + " " + expectedDay.get("date").asText();
                assertEquals(expectedDay.get("date").asText(), actualDay.get("date"), dayLabel);

                JsonNode expectedVictory = expectedDay.get("victoryDay");
                Object actualVictory = actualDay.get("victoryDay");
                if (expectedVictory.isNull()) {
                    assertNull(actualVictory, dayLabel + " victory");
                } else {
                    victories++;
                    Map<String, Object> v = castMap(actualVictory);
                    assertEquals(expectedVictory.get("name").asText(), v.get("name"), dayLabel + " victory name");
                    assertEquals(expectedVictory.get("palace").asText(), v.get("palace"), dayLabel + " victory palace");
                    assertNotNull(v.get("direction"), dayLabel + " victory direction");
                }

                List<Object> actualHours = castList(actualDay.get("goodHours"));
                assertEquals(expectedDay.get("goodHours").size(), actualHours.size(),
                        dayLabel + " good hours");
                for (int j = 0; j < actualHours.size(); j++) {
                    JsonNode expected = expectedDay.get("goodHours").get(j);
                    Map<String, Object> actual = castMap(actualHours.get(j));
                    String hourLabel = dayLabel + " goodHours[" + j + "]";

                    assertSameInstant(expected.get("time").asText(),
                            String.valueOf(actual.get("time")), hourLabel);
                    assertEquals(expected.get("palace").asText(), actual.get("palace"), hourLabel);
                    assertEquals(expected.get("numPositiveStructures").asInt(),
                            actual.get("numPositiveStructures"), hourLabel);

                    JsonNode es = expected.get("structure");
                    Map<String, Object> as = castMap(actual.get("structure"));
                    assertEquals(es.get("name").asText(), as.get("name"), hourLabel + " structure");
                    assertEquals(es.get("rating").asInt(), as.get("rating"), hourLabel + " rating");
                    assertEquals(es.get("matchedRule").asText(), as.get("matchedRule"),
                            hourLabel + " matchedRule");
                    goodHours++;
                }
            }
        }
        assertEquals(52, goodHours);
        assertEquals(2, victories);
    }

    @Test
    void invalidRangesAndFiltersRejected() {
        QimenDayListService.Options o = new QimenDayListService.Options();
        assertThrows(IllegalArgumentException.class,
                () -> days.scan("2024-01-01", "2024-02-15", o));
        assertThrows(IllegalArgumentException.class,
                () -> days.scan("2024-02-15", "2024-01-01", o));
        assertThrows(IllegalArgumentException.class,
                () -> days.scan("not-a-date", null, o));
        assertThrows(IllegalArgumentException.class,
                () -> days.scan("2024-01-01", "2024-01-02", options(op -> op.maxNegativeStructures = -1)));
        assertThrows(IllegalArgumentException.class,
                () -> days.scan("2024-01-01", "2024-01-02", options(o2 -> o2.doors = List.of("bogus"))));
        assertThrows(IllegalArgumentException.class,
                () -> days.scan("2024-01-01", "2024-01-02", options(o2 -> o2.stars = List.of("bogus"))));
        assertThrows(IllegalArgumentException.class,
                () -> days.scan("2024-01-01", "2024-01-02", options(o2 -> o2.spirits = List.of("bogus"))));
        assertThrows(IllegalArgumentException.class,
                () -> days.scan("2024-01-01", "2024-01-02", options(o2 -> o2.structures = List.of("bogus"))));
    }

    @Test
    void filterTablesExposeRnOptions() {
        Map<String, Object> tables = days.tables();
        assertEquals(8, castList(tables.get("doors")).size());
        assertEquals(9, castList(tables.get("stars")).size());
        assertEquals(10, castList(tables.get("spirits")).size());
        assertEquals("chief", castList(tables.get("spirits")).get(0));

        List<Object> structures = castList(tables.get("structures"));
        assertEquals(49, structures.size());
        Map<String, Object> first = castMap(structures.get(0));
        assertEquals("green_dragon_returns", first.get("name"));
        assertEquals("GDR", first.get("abbr"));
        assertEquals(2, first.get("rating"));
        assertEquals(true, first.get("almanacDefault"));
    }

    @Test
    void birthTimeWithoutChartRejected() {
        QimenDayListService.Options o = new QimenDayListService.Options();
        o.birthTime = "2051-01-01T12:00:00+08:00"; // hourJus ends at 2050
        assertThrows(IllegalArgumentException.class,
                () -> days.scan("2050-01-01", "2050-01-01", o));
    }

    @Test
    void emptyFilterListMatchesNothing() {
        QimenDayListService.Options o = new QimenDayListService.Options();
        o.doors = List.of(); // explicitly empty, unlike null = RN defaults
        Map<String, Object> result = days.scan("2024-01-01", "2024-01-01", o);
        assertEquals(List.of(), castMap(result.get("lookups")).get("doors"));
        Map<String, Object> day = castMap(castList(result.get("days")).get(0));
        assertEquals(List.of(), day.get("goodHours"));
    }

    // ------------------------------------------------------------- helpers

    private static QimenDayListService.Options options(
            java.util.function.Consumer<QimenDayListService.Options> change) {
        QimenDayListService.Options o = new QimenDayListService.Options();
        change.accept(o);
        return o;
    }

    private static String localDate(String iso) {
        return QimenDayListService.Range.parse(iso, null).start.toString();
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static void assertLookups(JsonNode expected, Map<String, Object> actual, String label) {
        assertEquals(strings(expected.get("doors")), actual.get("doors"), label + " doors");
        assertEquals(strings(expected.get("stars")), actual.get("stars"), label + " stars");
        assertEquals(strings(expected.get("spirits")), actual.get("spirits"), label + " spirits");
        assertEquals(strings(expected.get("structures")), actual.get("structures"), label + " structures");
        assertEquals(expected.get("maxNegativeStructures").asInt(),
                actual.get("maxNegativeStructures"), label + " max negative");
    }

    private static void assertSameInstant(String expected, String actual, String label) {
        OffsetDateTime e = OffsetDateTime.parse(expected);
        OffsetDateTime a = OffsetDateTime.parse(actual);
        assertEquals(e.toInstant(), a.toInstant(), label + " instant: " + expected + " vs " + actual);
        assertEquals(e.getOffset(), a.getOffset(), label + " offset");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castList(Object o) {
        return o instanceof List ? (List<Object>) o : List.of();
    }
}
