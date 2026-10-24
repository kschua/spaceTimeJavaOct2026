package com.bazi;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Port of the RN QMDJ ju lookup ({@code Plotters/QiMap/Jus.js}):
 * {@code zhiRun} reads the crawled year/month/day/hour "dunFlag-ju" tables,
 * {@code chaiBu} (拆補) derives the ju from the current solar term plus the
 * three-yuan table instead.
 *
 * <p>Parity notes (kept deliberately):
 * <ul>
 *   <li>{@code jusDay} contains 8,339 literal {@code "0-undefined"} entries
 *       (2021-12-21 alone, then 2077-03-04 through 2099 - a crawl miss). The RN
 *       app returns ju {@code "undefined"} for those days, and so does the port.</li>
 *   <li>{@code hourJus} stops at 2050: hour/destiny lookups return {@code null}
 *       beyond it. Month/year lookups on a pre-li-chun January of 1900 shift the
 *       key to 1899 and miss - also reproduced.</li>
 *   <li>{@code zhiRun} returns ju as a string, {@code chaiBu} as a number (the
 *       RN types come straight from the tables); the endpoint preserves both.</li>
 *   <li>Unknown references (e.g. {@code house}) return {@code null} like RN's
 *       failing split; missing table rows return {@code null} instead of the RN
 *       TypeError (agreed null-safety).</li>
 * </ul>
 */
@Service
public class QimenService {

    /** References accepted by {@code zhiRun}; {@code destiny} is an alias of hour. */
    public static final List<String> REFERENCES = List.of("hour", "day", "month", "year", "destiny");

    private final ResourceJson resources;

    public QimenService(ResourceJson resources) {
        this.resources = resources;
    }

    /** Jus.zhiRun(reference, bazi): {dun, ju} or null when the table has no entry. */
    public Map<String, Object> zhiRun(String reference, Map<String, Object> chart) {
        if (!REFERENCES.contains(reference)) return null; // RN: [].split() TypeError -> null

        OffsetDateTime at = OffsetDateTime.parse((String) info(chart).get("time"));
        String year = String.format("%04d", at.getYear());
        String month = String.format("%02d", at.getMonthValue());
        String day = String.format("%02d", at.getDayOfMonth());
        boolean prevYear = Boolean.TRUE.equals(info(chart).get("isPreviousYear"));

        JsonNode tables = resources.load("qimen-data.json");
        String raw = switch (reference) {
            case "hour", "destiny" -> text(deep(tables.get("jusHour"), year, month, day));
            case "day" -> text(deep(tables.get("jusDay"), year, month, day));
            case "month" -> text(deep(tables.get("jusMonth"),
                    prevYear ? String.format("%04d", at.getYear() - 1) : year));
            case "year" -> text(deep(tables.get("jusYear"),
                    prevYear ? String.format("%04d", at.getYear() - 1) : year));
            default -> null;
        };
        if (raw == null) return null;

        String[] parts = raw.split("-", 2);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dun", "0".equals(parts[0]) ? "yin" : "yang");
        out.put("ju", parts.length > 1 ? parts[1] : "");
        return out;
    }

    /** Jus.chaiBu(bazi): {dun, ju} from solarJus + threeYuanTable; ju is a number. */
    public Map<String, Object> chaiBu(Map<String, Object> chart) {
        Map<String, Object> dayPillar = castMap(castMap(chart.get("pillars")).get("day"));
        String stem = (String) castMap(dayPillar.get("stem")).get("name");
        String branch = (String) castMap(dayPillar.get("branch")).get("name");

        int termNum = ((Number) castMap(castMap(castMap(info(chart).get("solarTerm"))
                .get("thisTerm")).get("data")).get("num")).intValue();

        JsonNode tables = resources.load("qimen-data.json");
        JsonNode tri = tables.get("solarJus").get(String.valueOf(termNum));
        JsonNode yuan = deep(tables.get("threeYuanTable"), stem, branch);
        if (tri == null || yuan == null) return null; // RN would TypeError on the cycle lookup

        String cycle = yuan.get("cycle").asText();
        int idx = "upper".equals(cycle) ? 0 : "middle".equals(cycle) ? 1 : 2;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dun", termNum >= 9 && termNum < 21 ? "yin" : "yang");
        out.put("ju", tri.get(idx).asInt());
        return out;
    }

    // ------------------------------------------------------------- helpers

    /**
     * MysticDoors.displayTerminology: the 0/1/2 naming conventions for
     * spirit/star/door display names (QiMenDestinySummary + palace cells).
     */
    public Map<String, Object> displayTerminology() {
        JsonNode node = resources.load("qimen-data.json").get("displayTerminology");
        if (node == null) {
            throw new IllegalStateException("qimen-data.json has no displayTerminology");
        }
        return castMap(DataStore.deep(node));
    }

    private static JsonNode deep(JsonNode root, String... path) {
        JsonNode n = root;
        for (String p : path) {
            if (n == null) return null;
            n = n.get(p);
        }
        return n;
    }

    private static String text(JsonNode n) {
        return n == null || !n.isTextual() ? null : n.asText();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> info(Map<String, Object> chart) {
        return (Map<String, Object>) chart.get("info");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
    }
}
