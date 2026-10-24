package com.bazi;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Port of the Qi Men month scan behind the RN Almanac "Qi Men" tab and
 * QiMenDayList ({@code components/Date/Almanac.js} + {@code QiMenAssist}).
 *
 * <p>For every day it plots the hour charts (00:00 and every odd hour — RN's
 * {@code h === 0 || h % 2 === 1}, so zi appears at both 23:00 and 00:00) and
 * keeps a palace when its door/star/spirit pass the caller's filters, it has
 * no more than {@code maxNegativeStructures} negative structures, and one of
 * its matched structures is in the requested set. The per-day victory check
 * ports {@code QiMenAssist.isThreeVictoryDay} (birth chart victories vs the
 * day and month charts of that date).</p>
 *
 * <p>Parity notes / divergences:</p>
 * <ul>
 *   <li>Table misses (hourJus ends at 2050, the {@code "0-undefined"} days)
 *       yield no charts; those hours are skipped instead of throwing like the
 *       RN screen would.</li>
 *   <li>Default filters mirror the RN preferences: doors life/rest/open/
 *       scenery/delusion, all nine stars, spirits chief/harmony/heaven/moon/
 *       earth, positive structures with {@code almanacDefault}, and zero
 *       tolerated negative structures.</li>
 *   <li>RN scans one calendar month; this endpoint accepts an inclusive
 *       date range capped at {@link #MAX_DAYS} days.</li>
 * </ul>
 */
@Service
public class QimenDayListService {

    /** RN scans one month; keep the same bound for predictable latency. */
    public static final int MAX_DAYS = 31;

    /** 00:00 plus every odd hour (RN's scan): 13 charts, zi at 23:00 and 00:00. */
    static final int[] HOURS = {0, 1, 3, 5, 7, 9, 11, 13, 15, 17, 19, 21, 23};

    static final Set<String> DEFAULT_DOORS =
            Set.of("life", "rest", "open", "scenery", "delusion");
    static final Set<String> DEFAULT_STARS =
            Set.of("heart", "assistant", "ambassador", "destructor", "grass",
                    "grain", "bird", "hero", "pillar");
    static final Set<String> DEFAULT_SPIRITS =
            Set.of("chief", "harmony", "heaven", "moon", "earth");
    /** MysticDoors.spirits key order (RN preference lookup order). */
    static final List<String> SPIRIT_ORDER = List.of("chief", "snake", "moon",
            "harmony", "hook", "tiger", "phoenix", "tortoise", "earth", "heaven");
    static final Set<String> SPIRITS = Set.copyOf(SPIRIT_ORDER);

    public static final class Options {
        public boolean lateZi = false;
        public int method = 0;
        public int starPath = 0;
        public boolean shiftLateZiStream = true;
        /** null = RN defaults; an explicitly empty list matches nothing. */
        public List<String> doors = null;
        public List<String> stars = null;
        public List<String> spirits = null;
        public List<String> structures = null;
        public int maxNegativeStructures = 0;
        /** Optional birth time: enables the personal victory-day check. */
        public String birthTime = null;
        public String gender = "male";
    }

    private final BaziService bazi;
    private final QimenChartService charts;
    private final DataStore store;
    private final ResourceJson resources;

    public QimenDayListService(BaziService bazi, QimenChartService charts,
                               DataStore store, ResourceJson resources) {
        this.bazi = bazi;
        this.charts = charts;
        this.store = store;
        this.resources = resources;
    }

    public Map<String, Object> scan(String from, String to, Options o) {
        if (o.maxNegativeStructures < 0) {
            throw new IllegalArgumentException("maxNegativeStructures must be >= 0");
        }
        JsonNode tables = resources.load("qimen-data.json");
        List<String> doors = resolve(o.doors, fieldNames(tables.get("doors")),
                defaultNames(tables.get("doors"), DEFAULT_DOORS), "door");
        List<String> stars = resolve(o.stars, fieldNames(tables.get("stars")),
                defaultNames(tables.get("stars"), DEFAULT_STARS), "star");
        List<String> spirits = resolve(o.spirits, SPIRITS,
                SPIRIT_ORDER.stream().filter(DEFAULT_SPIRITS::contains).toList(), "spirit");
        List<String> structures = resolve(o.structures,
                fieldNames(tables.get("structures")),
                defaultStructures(tables.get("structures")), "structure");

        Range range = Range.parse(from, to);

        // Birth chart for the personal victory-day check (RN computes this once
        // per scan; the birth time is the chart time, not the scanned day).
        Map<String, Object> birthVictories = null;
        if (o.birthTime != null && !o.birthTime.isBlank()) {
            BaziService.PlotOptions bo = new BaziService.PlotOptions();
            bo.time = o.birthTime;
            bo.gender = o.gender;
            bo.lateZi = o.lateZi;
            Map<String, Object> birthBazi = bazi.plot(bo);

            QimenChartService.PlotOptions qo = plotOptions(o);
            Map<String, Object> birthQm = charts.plot(birthBazi, qo);
            if (birthQm == null) {
                throw new IllegalArgumentException("birthTime produces no Qi Men chart: " + o.birthTime);
            }
            birthVictories = castMap(castMap(birthQm.get("details")).get("victories"));
        }

        List<Map<String, Object>> days = new ArrayList<>();
        for (LocalDate d = range.start; !d.isAfter(range.end); d = d.plusDays(1)) {
            Map<String, Object> day = new LinkedHashMap<>();
            day.put("date", d.toString());
            day.put("victoryDay", birthVictories == null ? null
                    : victoryDay(birthVictories, d, range, o));
            day.put("goodHours", scanDay(d, range, o, doors, stars, spirits,
                    structures));
            days.add(day);
        }

        Map<String, Object> lookups = new LinkedHashMap<>();
        // RN almanacQMDJLookups key order (parity for the differential harness)
        lookups.put("structures", structures);
        lookups.put("spirits", spirits);
        lookups.put("stars", stars);
        lookups.put("doors", doors);
        lookups.put("maxNegativeStructures", o.maxNegativeStructures);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", range.start.toString());
        out.put("to", range.end.toString());
        out.put("lateZi", o.lateZi);
        out.put("method", o.method);
        out.put("starPath", o.starPath);
        out.put("shiftLateZiStream", o.shiftLateZiStream);
        out.put("hours", ints(HOURS));
        out.put("victory", birthVictories != null);
        out.put("lookups", lookups);
        out.put("days", days);
        return out;
    }

    /** Filter tables for the day-scan UI (doors/stars/spirits/structures). */
    public Map<String, Object> tables() {
        JsonNode qm = resources.load("qimen-data.json");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("doors", new ArrayList<>(fieldNames(qm.get("doors"))));
        out.put("stars", new ArrayList<>(fieldNames(qm.get("stars"))));
        out.put("spirits", SPIRIT_ORDER);

        List<Map<String, Object>> structures = new ArrayList<>();
        for (String key : fieldNames(qm.get("structures"))) {
            JsonNode s = qm.get("structures").get(key);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", key);
            row.put("abbr", s.path("abbr").isMissingNode() ? null : s.path("abbr").asText());
            row.put("rating", s.path("rating").asInt());
            row.put("almanacDefault", s.path("almanacDefault").asBoolean(false));
            structures.add(row);
        }
        out.put("structures", structures);
        return out;
    }

    private List<Map<String, Object>> scanDay(LocalDate d, Range range, Options o,
                                              List<String> doors, List<String> stars,
                                              List<String> spirits, List<String> structures) {
        List<Map<String, Object>> good = new ArrayList<>();
        for (int hour : HOURS) {
            BaziService.PlotOptions bo = new BaziService.PlotOptions();
            bo.time = range.timeAt(d, hour);
            bo.gender = o.gender;
            bo.lateZi = o.lateZi;
            Map<String, Object> baziChart = bazi.plot(bo);

            QimenChartService.PlotOptions qo = plotOptions(o);
            Map<String, Object> chart = charts.plot(baziChart, qo);
            if (chart == null) continue; // RN would throw on table misses

            Object time = castMap(castMap(chart.get("bazi")).get("info")).get("time");
            for (Map.Entry<String, Object> e : castMap(chart.get("palaces")).entrySet()) {
                if ("center".equals(e.getKey())) continue;
                Map<String, Object> palace = castMap(e.getValue());
                Map<String, Object> plot = castMap(palace.get("plot"));
                Map<String, Object> meta = castMap(palace.get("meta"));

                if (!doors.contains(str(plot.get("door")))) continue;
                if (!stars.contains(str(plot.get("star")))) continue;
                if (!spirits.contains(str(plot.get("spirit")))) continue;

                Map<String, Object> structuresMap = castMap(meta.get("structuresMap"));
                if (castList(structuresMap.get("negative")).size() > o.maxNegativeStructures) {
                    continue;
                }
                int positive = castList(structuresMap.get("positive")).size();
                for (Object candidate : castList(meta.get("structuresArray"))) {
                    Map<String, Object> structure = castMap(candidate);
                    if (!structures.contains(str(structure.get("name")))) continue;

                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("structure", structure);
                    item.put("time", time);
                    item.put("palace", e.getKey());
                    item.put("numPositiveStructures", positive);
                    good.add(item);
                    break;
                }
            }
        }
        return good;
    }

    /** QiMenAssist.isThreeVictoryDay, with null chart guards. */
    private Map<String, Object> victoryDay(Map<String, Object> victories,
                                           LocalDate d, Range range, Options o) {
        String light = str(victories.get("light"));
        String blessing = str(victories.get("blessing"));
        String force = str(victories.get("force"));

        BaziService.PlotOptions bo = new BaziService.PlotOptions();
        bo.time = range.timeAt(d, 12);
        bo.gender = o.gender;
        Map<String, Object> today = bazi.plot(bo);

        QimenChartService.PlotOptions dayOpts = plotOptions(o);
        dayOpts.reference = "day";
        Map<String, Object> dayChart = charts.plot(today, dayOpts);
        QimenChartService.PlotOptions monthOpts = plotOptions(o);
        monthOpts.reference = "month";
        Map<String, Object> monthChart = charts.plot(today, monthOpts);
        if (dayChart == null || monthChart == null) return null;

        if (light != null) {
            String dayDoor = plotValue(dayChart, light, "door");
            String daySpirit = plotValue(dayChart, light, "spirit");
            String monthDoor = plotValue(monthChart, light, "door");
            String monthSpirit = plotValue(monthChart, light, "spirit");
            if (("life".equals(dayDoor) && "heaven".equals(monthSpirit))
                    || ("heaven".equals(daySpirit) && "life".equals(monthDoor))
                    || ("heaven".equals(daySpirit) && "life".equals(dayDoor))) {
                return victory("light", light);
            }
        }
        if (blessing != null) {
            String dayDoor = plotValue(dayChart, blessing, "door");
            String daySpirit = plotValue(dayChart, blessing, "spirit");
            String monthDoor = plotValue(monthChart, blessing, "door");
            String monthSpirit = plotValue(monthChart, blessing, "spirit");
            if (("chief".equals(daySpirit) && "life".equals(monthDoor))
                    || ("life".equals(dayDoor) && "chief".equals(monthSpirit))
                    || ("life".equals(dayDoor) && "chief".equals(daySpirit))) {
                return victory("blessing", blessing);
            }
        }
        if (force != null) {
            String daySpirit = plotValue(dayChart, force, "spirit");
            String monthSpirit = plotValue(monthChart, force, "spirit");
            if (("heaven".equals(daySpirit) && "chief".equals(monthSpirit))
                    || ("chief".equals(daySpirit) && "heaven".equals(monthSpirit))) {
                return victory("force", force);
            }
        }
        return null;
    }

    private Map<String, Object> victory(String name, String palace) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("palace", palace);
        JsonNode direction = store.node("guas", palace, "direction");
        out.put("direction", direction == null ? null : direction.asText());
        return out;
    }

    private static QimenChartService.PlotOptions plotOptions(Options o) {
        QimenChartService.PlotOptions qo = new QimenChartService.PlotOptions();
        qo.reference = "hour";
        qo.method = o.method;
        qo.starPath = o.starPath;
        qo.shiftLateZiStream = o.shiftLateZiStream;
        qo.appendMeta = true;
        qo.appendDestiny = false;
        return qo;
    }

    private static String plotValue(Map<String, Object> chart, String palace, String field) {
        return str(castMap(castMap(castMap(chart.get("palaces")).get(palace))
                .get("plot")).get(field));
    }

    private static List<String> defaultStructures(JsonNode structures) {
        List<String> out = new ArrayList<>();
        for (String key : fieldNames(structures)) {
            JsonNode s = structures.get(key);
            if (s.path("rating").asInt() > 0 && s.path("almanacDefault").asBoolean(false)) {
                out.add(key);
            }
        }
        return out;
    }

    private static List<String> defaultNames(JsonNode table, Set<String> defaults) {
        List<String> out = new ArrayList<>();
        for (String key : fieldNames(table)) {
            if (defaults.contains(key)) out.add(key);
        }
        return out;
    }

    private static List<String> resolve(List<String> requested, Set<String> valid,
                                        List<String> defaults, String kind) {
        if (requested == null) return defaults;
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String raw : requested) {
            if (raw == null) continue;
            for (String piece : raw.split(",")) {
                String name = piece.trim();
                if (name.isEmpty()) continue;
                if (!valid.contains(name)) {
                    throw new IllegalArgumentException("unknown " + kind + ": " + name);
                }
                out.add(name);
            }
        }
        return new ArrayList<>(out);
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> out = new LinkedHashSet<>();
        if (node != null) node.fieldNames().forEachRemaining(out::add);
        return out;
    }

    private static List<Integer> ints(int[] values) {
        List<Integer> out = new ArrayList<>(values.length);
        for (int v : values) out.add(v);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> castList(Object o) {
        return o instanceof List ? (List<Object>) o : new ArrayList<>();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /**
     * Inclusive local-date range. Inputs may be dates, naive date-times or
     * full ISO date-times with an offset; the offset (when given) is held
     * constant for all generated hour times, otherwise the server zone is
     * used just like the RN app's local DateTime.fromObject calls.
     */
    static final class Range {
        final LocalDate start;
        final LocalDate end;
        final ZoneOffset offset; // null = system zone

        private Range(LocalDate start, LocalDate end, ZoneOffset offset) {
            this.start = start;
            this.end = end;
            this.offset = offset;
        }

        static Range parse(String from, String to) {
            if (from == null || from.isBlank()) {
                throw new IllegalArgumentException("from is required");
            }
            LocalDate start = dateOf(from);
            LocalDate end = to == null || to.isBlank() ? start.withDayOfMonth(start.lengthOfMonth())
                    : dateOf(to);
            if (end.isBefore(start)) {
                throw new IllegalArgumentException("to must be on/after from");
            }
            long days = ChronoUnit.DAYS.between(start, end) + 1;
            if (days > MAX_DAYS) {
                throw new IllegalArgumentException("range too large: " + days + " days (max " + MAX_DAYS + ")");
            }
            ZoneOffset offset = offsetOf(from);
            if (offset == null) offset = offsetOf(to);
            return new Range(start, end, offset);
        }

        String timeAt(LocalDate date, int hour) {
            if (offset != null) {
                return date.atTime(hour, 0).atOffset(offset).toString();
            }
            return date.atTime(hour, 0).atZone(ZoneId.systemDefault())
                    .toOffsetDateTime().toString();
        }

        private static LocalDate dateOf(String value) {
            try {
                return LocalDate.parse(value);
            } catch (DateTimeParseException ignored) {
                // fall through
            }
            try {
                return LocalDateTime.parse(value).toLocalDate();
            } catch (DateTimeParseException ignored) {
                // fall through
            }
            try {
                return OffsetDateTime.parse(value).toLocalDate();
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("invalid date: " + value);
            }
        }

        private static ZoneOffset offsetOf(String value) {
            if (value == null || value.isBlank()) return null;
            try {
                return OffsetDateTime.parse(value).getOffset();
            } catch (DateTimeParseException ignored) {
                return null;
            }
        }
    }
}
