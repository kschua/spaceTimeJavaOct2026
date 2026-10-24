package com.bazi;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Port of components/Bazi/Synastry.js — the full chart comparison.
 *
 * <p>The RN app opens the comparison with a re-plotted chart2
 * (`root: null, showWeights: true`) and then extends each chart2 pillar
 * against the full chart1 pillar list (`EightWords.extendAuxData(p, now,
 * chart1, chart1Keys)`). That extension is what feeds the interaction
 * sections.</p>
 *
 * <p>Known upstream bug fixed here: the RN interactionComparison() compares
 * `pillarInteractions.relation` (the result object) instead of
 * `interaction.relation`, so only "Fan Yin" ever renders. We keep all three
 * relations and emit the same row shape {left, right, label}.</p>
 */
@Service
public class SynastryService {

    private static final List<String> ELEMENTS = List.of("wood", "fire", "earth", "metal", "water");
    private static final List<String> ASPECTS = List.of("resource", "companion", "output", "wealth", "power");
    private static final List<String> GODS = List.of("ir", "dr", "f", "rw", "eg", "ho", "iw", "dw", "7k", "do");

    /** Graphics.colors.elements.light */
    private static final Map<String, String> ELEMENT_COLORS = Map.of(
            "wood", "#55EFC4",
            "fire", "#FFD2C8",
            "earth", "#FFECAF",
            "metal", "#F2F2F2",
            "water", "#D0E7FF");
    /** Color.BLUEGREY[100] */
    private static final String GOD_COLOR = "#CFD8DC";

    private static final Map<String, String> PILLAR_CONTEXT = Map.of(
            "year", "social life, friendship, and leisure",
            "month", "career, academics, and self improvement",
            "day", "home, personal, and daily life",
            "hour", "investments, aspirations, and dreams");

    private final DataStore store;
    private final BaziService bazi;
    private final ShenShaService shensha;

    public SynastryService(DataStore store, BaziService bazi, ShenShaService shensha) {
        this.store = store;
        this.bazi = bazi;
        this.shensha = shensha;
    }

    public Map<String, Object> compare(String time1, String time2, String gender1, String gender2) {
        return compare(time1, time2, gender1, gender2, false, false);
    }

    public Map<String, Object> compare(String time1, String time2, String gender1, String gender2,
                                       boolean genderUnknown1, boolean genderUnknown2) {
        BaziService.PlotOptions o1 = new BaziService.PlotOptions();
        o1.time = time1;
        o1.chartName = "chart1";
        o1.gender = gender1;
        o1.genderUnknown = genderUnknown1;
        o1.showLuck = true;
        o1.showWeights = true;
        Map<String, Object> c1 = bazi.plot(o1);

        // RN re-plots chart2 with root:null + showWeights:true before comparing
        BaziService.PlotOptions o2 = new BaziService.PlotOptions();
        o2.time = time2;
        o2.chartName = "chart2";
        o2.gender = gender2;
        o2.genderUnknown = genderUnknown2;
        o2.showWeights = true;
        Map<String, Object> c2 = bazi.plot(o2);

        List<String> keys1 = keysOf(c1);
        List<String> keys2 = keysOf(c2);

        // Synastry.js: pillarKeys2.forEach(key => EightWords.extendAuxData(chart2.pillars[key], now, chart1, pillarKeys1))
        Instant now = Instant.now();
        Map<String, List<Map<String, Object>>> annualState = new LinkedHashMap<>();
        for (String key : keys2) {
            Map<String, Object> pillar = pillarOf(c2, key);
            if (pillar != null) bazi.extendAuxData(pillar, now, c1, keys1, annualState);
        }

        Map<String, List<Map<String, Object>>> interactions = interactionComparison(c2, keys2);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("basic", basicComparison(c1, c2));
        out.put("streams", jiaGroupComparison(c1, c2, keys1, keys2));
        out.put("fuyin", interactions.get("fuyin"));
        out.put("fanyin", interactions.get("fanyin"));
        out.put("combo", interactions.get("combo"));
        out.put("balance", balanceComparison(c1, c2));
        out.put("elements", elementComparison(c1, c2));
        out.put("aspects", aspectComparison(c1, c2));
        out.put("gods", godComparison(c1, c2));
        out.put("shensha", shenshaComparison(c1, c2, keys1, keys2));
        // legacy key from the first Java port (list of shared star names)
        out.put("shenshaOverlap", shenshaOverlap(c1, c2, keys1, keys2));
        return out;
    }

    // ----- sections -----

    private List<Map<String, Object>> basicComparison(Map<String, Object> c1, Map<String, Object> c2) {
        Map<String, Object> i1 = map(c1.get("info"));
        Map<String, Object> i2 = map(c2.get("info"));
        Map<String, Object> s1 = map(map(c1.get("meta")).get("stats"));
        Map<String, Object> s2 = map(map(c2.get("meta")).get("stats"));

        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("Time", timeText(i1), timeText(i2)));
        rows.add(row("Gender",
                Boolean.TRUE.equals(i1.get("genderUnknown")) ? "—" : String.valueOf(i1.get("gender")),
                Boolean.TRUE.equals(i2.get("genderUnknown")) ? "—" : String.valueOf(i2.get("gender"))));
        rows.add(row("^ Aspect", String.valueOf(s1.get("structure")), String.valueOf(s2.get("structure"))));
        rows.add(row("^ God", godName(s1), godName(s2)));
        rows.add(row("Strength",
                s1.get("strength") + " (" + s1.get("supportScore") + "%)",
                s2.get("strength") + " (" + s2.get("supportScore") + "%)"));
        rows.add(row("Temp", temperature(s1), temperature(s2)));
        rows.add(row("Polarity",
                toTitle(String.valueOf(map(s1.get("polarity")).get("desc"))),
                toTitle(String.valueOf(map(s2.get("polarity")).get("desc")))));
        return rows;
    }

    private Map<String, Object> jiaGroupComparison(Map<String, Object> c1, Map<String, Object> c2,
                                                   List<String> keys1, List<String> keys2) {
        // chart2 groups are seeded first, mirroring Synastry.js iteration order
        Map<String, Map<String, List<String>>> groups = new LinkedHashMap<>();
        for (String key : keys2) {
            Map<String, Object> p = pillarOf(c2, key);
            if (p == null) continue;
            group(groups, String.valueOf(p.get("jiaGroup"))).get("chart2").add(toTitle(key));
        }
        for (String key : keys1) {
            Map<String, Object> p = pillarOf(c1, key);
            if (p == null) continue;
            group(groups, String.valueOf(p.get("jiaGroup"))).get("chart1").add(toTitle(key));
        }
        groups.entrySet().removeIf(e ->
                e.getValue().get("chart1").isEmpty() || e.getValue().get("chart2").isEmpty());

        List<Map<String, Object>> results = new ArrayList<>();
        int count = 0;
        for (Map.Entry<String, Map<String, List<String>>> e : groups.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stream", e.getKey());
            row.put("matches", e.getValue());
            results.add(row);
            count += e.getValue().get("chart1").size() + e.getValue().get("chart2").size();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", count);
        out.put("results", results);
        return out;
    }

    private Map<String, List<Map<String, Object>>> interactionComparison(Map<String, Object> chart2, List<String> keys2) {
        List<Map<String, Object>> fuyin = new ArrayList<>();
        List<Map<String, Object>> fanyin = new ArrayList<>();
        List<Map<String, Object>> combo = new ArrayList<>();

        for (String key : keys2) {
            Map<String, Object> pillar = pillarOf(chart2, key);
            if (pillar == null) continue;
            for (Map<String, Object> interaction : listOfMaps(pillar.get("interactions"))) {
                String relation = String.valueOf(interaction.get("relation"));
                for (Map<String, Object> affect : listOfMaps(interaction.get("affects"))) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("left", toTitle(String.valueOf(affect.get("pillar"))));
                    row.put("right", toTitle(key));
                    if ("Fan Yin".equals(relation)) {
                        row.put("label", "Clash");
                        fanyin.add(row);
                    } else if ("Fu Yin".equals(relation)) {
                        row.put("label", "Identical");
                        fuyin.add(row);
                    } else if ("Pillar Combo".equals(relation)) {
                        row.put("label", "Combination");
                        combo.add(row);
                    }
                }
            }
        }

        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        out.put("fuyin", fuyin);
        out.put("fanyin", fanyin);
        out.put("combo", combo);
        return out;
    }

    private List<Map<String, Object>> balanceComparison(Map<String, Object> c1, Map<String, Object> c2) {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("Have", map(c1.get("meta")).get("unfavorable") == null ? List.of() : map(map(c1.get("meta")).get("unfavorable")).get("elements"),
                map(c2.get("meta")).get("unfavorable") == null ? List.of() : map(map(c2.get("meta")).get("unfavorable")).get("elements")));
        rows.add(row("Need", map(c1.get("meta")).get("favorable") == null ? List.of() : map(map(c1.get("meta")).get("favorable")).get("elements"),
                map(c2.get("meta")).get("favorable") == null ? List.of() : map(map(c2.get("meta")).get("favorable")).get("elements")));
        return rows;
    }

    private Map<String, Object> elementComparison(Map<String, Object> c1, Map<String, Object> c2) {
        Map<String, Object> r1 = ratio(c1, "elements");
        Map<String, Object> r2 = ratio(c2, "elements");
        int overlap = 0;
        for (String key : ELEMENTS) overlap += Math.min(intOf(r1.get(key)), intOf(r2.get(key)));

        List<Map<String, Object>> arr1 = new ArrayList<>();
        List<Map<String, Object>> arr2 = new ArrayList<>();
        for (String key : ELEMENTS) {
            arr1.add(scoreRow(toTitle(key), intOf(r1.get(key)), ELEMENT_COLORS.get(key)));
            arr2.add(scoreRow(toTitle(key), intOf(r2.get(key)), ELEMENT_COLORS.get(key)));
        }
        return comparison(ELEMENTS.stream().map(SynastryService::toTitle).toList(), overlap, arr1, arr2);
    }

    private Map<String, Object> aspectComparison(Map<String, Object> c1, Map<String, Object> c2) {
        Map<String, Object> r1 = ratio(c1, "aspects");
        Map<String, Object> r2 = ratio(c2, "aspects");
        int overlap = 0;
        for (String key : ASPECTS) overlap += Math.min(intOf(r1.get(key)), intOf(r2.get(key)));

        Map<String, Object> a2el1 = map(map(map(c1.get("meta")).get("maps")).get("aspectToElement"));
        Map<String, Object> a2el2 = map(map(map(c2.get("meta")).get("maps")).get("aspectToElement"));

        List<Map<String, Object>> arr1 = new ArrayList<>();
        List<Map<String, Object>> arr2 = new ArrayList<>();
        for (String key : ASPECTS) {
            arr1.add(scoreRow(toTitle(key), intOf(r1.get(key)), ELEMENT_COLORS.get(String.valueOf(a2el1.get(key)))));
            arr2.add(scoreRow(toTitle(key), intOf(r2.get(key)), ELEMENT_COLORS.get(String.valueOf(a2el2.get(key)))));
        }
        return comparison(ASPECTS.stream().map(SynastryService::toTitle).toList(), overlap, arr1, arr2);
    }

    private Map<String, Object> godComparison(Map<String, Object> c1, Map<String, Object> c2) {
        Map<String, Object> r1 = ratio(c1, "gods");
        Map<String, Object> r2 = ratio(c2, "gods");
        int overlap = 0;
        for (String key : GODS) overlap += Math.min(intOf(r1.get(key)), intOf(r2.get(key)));

        List<Map<String, Object>> arr1 = new ArrayList<>();
        List<Map<String, Object>> arr2 = new ArrayList<>();
        for (String key : GODS) {
            arr1.add(scoreRow(toTitle(key).toUpperCase(), intOf(r1.get(key)), GOD_COLOR));
            arr2.add(scoreRow(toTitle(key).toUpperCase(), intOf(r2.get(key)), GOD_COLOR));
        }
        return comparison(GODS.stream().map(String::toUpperCase).toList(), overlap, arr1, arr2);
    }

    /**
     * Synastry.js shenshaComparison(). Note the shipped quirk: peachBlossom is
     * indexed by the *day stem* number (`dayBranchAux.peachBlossom[dm1]`), not
     * the day branch — preserved for parity.
     */
    private Map<String, List<Map<String, Object>>> shenshaComparison(Map<String, Object> c1, Map<String, Object> c2,
                                                                    List<String> keys1, List<String> keys2) {
        int dm1 = stemNumber(pillarOf(c1, "day"));
        int dm2 = stemNumber(pillarOf(c2, "day"));

        Map<String, StarRef> map1 = starRefs(dm1, true);
        Map<String, StarRef> map2 = starRefs(dm2, false);

        List<Map<String, Object>> mine = new ArrayList<>();
        List<Map<String, Object>> theirs = new ArrayList<>();

        for (Map.Entry<String, StarRef> e : map1.entrySet()) {
            StarRef ref1 = e.getValue();
            for (String key2 : keys2) {
                String branch = branchName(pillarOf(c2, key2));
                if (branch != null && branch.equals(ref1.branch)) mine.add(starRow(ref1, key2, true));
            }
            StarRef ref2 = map2.get(e.getKey());
            for (String key1 : keys1) {
                String branch = branchName(pillarOf(c1, key1));
                if (branch != null && branch.equals(ref2.branch)) theirs.add(starRow(ref2, key1, false));
            }
        }

        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        out.put("chart1", mine);
        out.put("chart2", theirs);
        return out;
    }

    /** Legacy first-port output: star names present in both charts. */
    private List<String> shenshaOverlap(Map<String, Object> c1, Map<String, Object> c2,
                                        List<String> keys1, List<String> keys2) {
        Set<String> names1 = new LinkedHashSet<>();
        for (String key : keys1) {
            Map<String, Object> p = pillarOf(c1, key);
            if (p == null) continue;
            for (Map<String, Object> star : shensha.starsFromPillar(c1, p)) {
                names1.add(String.valueOf(star.get("name")));
            }
        }
        Set<String> names2 = new HashSet<>();
        for (String key : keys2) {
            Map<String, Object> p = pillarOf(c2, key);
            if (p == null) continue;
            for (Map<String, Object> star : shensha.starsFromPillar(c2, p)) {
                names2.add(String.valueOf(star.get("name")));
            }
        }
        List<String> shared = new ArrayList<>();
        for (String name : names1) if (names2.contains(name)) shared.add(name);
        return shared;
    }

    // ----- shensha helpers -----

    private static final class StarRef {
        final String key;
        final String branch;
        final String group;
        final String auxName;
        final String desc;

        StarRef(String key, String branch, String group, String auxName, String desc) {
            this.key = key;
            this.branch = branch;
            this.group = group;
            this.auxName = auxName;
            this.desc = desc;
        }
    }

    private Map<String, StarRef> starRefs(int dm, boolean mine) {
        Map<String, StarRef> out = new LinkedHashMap<>();
        String noblemanDesc = mine
                ? "They can be helpers or mentors to me"
                : "I can be helpers or mentors to them";
        out.put("yangNobleman", new StarRef("yangNobleman", auxText("dayStemAux", "nobleman", dm, 0),
                "dayStemAux", "nobleman", noblemanDesc));
        out.put("yinNobleman", new StarRef("yinNobleman", auxText("dayStemAux", "nobleman", dm, 1),
                "dayStemAux", "nobleman", noblemanDesc));
        out.put("intelligence", new StarRef("intelligence", auxText("dayStemAux", "intelligence", dm, -1),
                "dayStemAux", "intelligence",
                mine ? "They can help me with ideas and insights" : "I can help them with ideas and insights"));
        out.put("studyRoom", new StarRef("studyRoom", auxText("dayStemAux", "studyRoom", dm, -1),
                "dayStemAux", "studyRoom",
                mine ? "They can help me with studies and knowledge" : "I can help them with studies and knowledge"));
        // RN quirk: indexed by the day stem number
        out.put("peachBlossom", new StarRef("peachBlossom", auxText("dayBranchAux", "peachBlossom", dm, -1),
                "dayBranchAux", "peachBlossom",
                mine ? "They will find me attractive and charismatic" : "I will find them attractive and charismatic"));
        return out;
    }

    private String auxText(String group, String name, int index, int subIndex) {
        JsonNode node = store.node(group, name);
        if (node == null || !node.isArray() || index < 0 || index >= node.size()) return null;
        JsonNode value = node.get(index);
        if (subIndex >= 0) {
            if (!value.isArray() || subIndex >= value.size()) return null;
            value = value.get(subIndex);
        }
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private String iconOf(String group, String auxName) {
        JsonNode node = store.node("auxDetails", group, auxName);
        if (node == null) return null;
        JsonNode icon = node.path("icon");
        return icon.isTextual() ? icon.asText() : null;
    }

    private Map<String, Object> starRow(StarRef ref, String pillarKey, boolean mine) {
        Map<String, Object> row = new LinkedHashMap<>();
        if (mine) {
            row.put("pillar", pillarKey);
            row.put("from", "my");
            row.put("to", "their");
        } else {
            row.put("from", "their");
            row.put("to", "my");
            row.put("pillar", pillarKey);
        }
        row.put("name", ref.key.replace("yangN", "n").replace("yinN", "n"));
        row.put("icon", iconOf(ref.group, ref.auxName));
        row.put("branch", ref.branch);
        row.put("desc", ref.desc);
        row.put("context", PILLAR_CONTEXT.get(pillarKey));
        return row;
    }

    // ----- small helpers -----

    private static Map<String, Object> row(String label, Object left, Object right) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("label", label);
        row.put("left", left);
        row.put("right", right);
        return row;
    }

    private static Map<String, Object> scoreRow(String name, int score, String color) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("score", score);
        row.put("color", color);
        return row;
    }

    private static Map<String, Object> comparison(List<String> displayLabels, int overlap,
                                                  List<Map<String, Object>> arr1,
                                                  List<Map<String, Object>> arr2) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("labels", displayLabels);
        out.put("overlap", overlap);
        out.put("chart1", arr1);
        out.put("chart2", arr2);
        return out;
    }

    private static Map<String, List<String>> group(Map<String, Map<String, List<String>>> groups, String stream) {
        return groups.computeIfAbsent(stream, k -> {
            Map<String, List<String>> g = new LinkedHashMap<>();
            g.put("chart1", new ArrayList<>());
            g.put("chart2", new ArrayList<>());
            return g;
        });
    }

    private static List<String> keysOf(Map<String, Object> chart) {
        List<String> keys = new ArrayList<>(List.of("year", "month", "day"));
        if (!Boolean.TRUE.equals(map(chart.get("info")).get("hourUnknown"))) keys.add("hour");
        return keys;
    }

    private static Map<String, Object> ratio(Map<String, Object> chart, String section) {
        Map<String, Object> weights = map(map(chart.get("meta")).get("weights"));
        return map(map(weights.get("ratio")).get(section));
    }

    private static String timeText(Map<String, Object> info) {
        Object raw = info.get("time");
        if (raw == null) return "";
        String text = String.valueOf(raw);
        LocalDateTime wall;
        try {
            wall = OffsetDateTime.parse(text).toLocalDateTime();
        } catch (Exception e) {
            wall = LocalDateTime.parse(text);
        }
        DateTimeFormatter fmt = Boolean.TRUE.equals(info.get("hourUnknown"))
                ? DateTimeFormatter.ofPattern("yyyy-M-d --")
                : DateTimeFormatter.ofPattern("yyyy-M-d, H:mm");
        return wall.format(fmt);
    }

    private static String temperature(Map<String, Object> stats) {
        Object t = stats.get("temperature");
        if (t == null) return "—";
        String s = String.valueOf(t);
        return s.isEmpty() ? "—" : s;
    }

    private String godName(Map<String, Object> stats) {
        String profile = String.valueOf(stats.get("profile"));
        JsonNode name = store.node("display", "gods", profile, "name");
        return name == null || name.isNull() ? profile : name.asText();
    }

    private static int stemNumber(Map<String, Object> pillar) {
        if (pillar == null) return -1;
        Object number = map(pillar.get("stem")).get("number");
        return number instanceof Number ? ((Number) number).intValue() : -1;
    }

    private static String branchName(Map<String, Object> pillar) {
        if (pillar == null) return null;
        Object name = map(pillar.get("branch")).get("name");
        return name == null ? null : String.valueOf(name);
    }

    private static Map<String, Object> pillarOf(Map<String, Object> chart, String key) {
        Object pillar = map(chart.get("pillars")).get(key);
        return pillar instanceof Map ? map(pillar) : null;
    }

    private static int intOf(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Collections.emptyMap();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(Object value) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map) out.add((Map<String, Object>) item);
            }
        }
        return out;
    }

    /** utils/strings.js toTitle */
    private static String toTitle(String text) {
        if (text == null || text.isEmpty()) return "";
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
