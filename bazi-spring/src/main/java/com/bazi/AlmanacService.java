package com.bazi;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Port of the RN almanac logic (lib/cmtools/Timing.js): 建除十二神 twelve
 * officers, 廿八宿 twenty-eight constellations, 黄黑道 yellow/black path,
 * 三煞 three killings, 董公 Dong Gong numbers, big-disaster days and the
 * crawled Tong Shu day entries.
 *
 * <p>Parity notes (kept deliberately):
 * <ul>
 *   <li>{@code bigDisaster} uses the JS {@code indexOf(lookup) > 0} check, so a
 *       date stored at index 0 of {@code bigDisasterDays[year]} is never
 *       flagged (it stays {@code null}, like the RN app).</li>
 *   <li>Constellation lookup runs through {@code Tools.toProxy}, whose get
 *       handler wraps negative and overflowing indices - equivalent to
 *       {@code Math.floorMod(dayDiff, 28)}.</li>
 *   <li>{@code dongGongNum} is omitted when the Dong Gong table has no entry
 *       (JS would produce {@code undefined}, which JSON.stringify drops).</li>
 *   <li>Day-year/month/day fields come from the chart's {@code info.time} offset
 *       calendar date, matching the luxon DateTime the RN code read.</li>
 * </ul>
 */
@Service
public class AlmanacService {

    private final JsonNode data;

    public AlmanacService(ResourceJson resources) {
        this.data = resources.load("almanac-data.json");
    }

    /** Timing.js `officers` - the 12 officers in cycle order. */
    private static final List<String> OFFICERS = List.of(
            "establish", "remove", "full", "balance", "stable", "initiate",
            "destruction", "danger", "success", "receive", "open", "close");

    /** Timing.js `branchOrder` - day branches keyed by the officer table. */
    private static final List<String> BRANCH_ORDER = List.of(
            "yin", "mao", "chen", "si", "wu", "wei", "shen", "you", "xu", "hai", "zi", "chou");

    /** Timing.js `monthOfficerStartDay`. */
    private static final Map<String, String> MONTH_OFFICER_START_DAY = Map.ofEntries(
            Map.entry("yin", "establish"),
            Map.entry("mao", "close"),
            Map.entry("chen", "open"),
            Map.entry("si", "receive"),
            Map.entry("wu", "success"),
            Map.entry("wei", "danger"),
            Map.entry("shen", "destruction"),
            Map.entry("you", "initiate"),
            Map.entry("xu", "stable"),
            Map.entry("hai", "balance"),
            Map.entry("zi", "full"),
            Map.entry("chou", "remove"));

    /** Timing.js `yearlyThreeKillings` / `monthlyThreeKillings` (identical tables). */
    private static final Map<String, List<String>> THREE_KILLINGS = Map.ofEntries(
            Map.entry("zi", List.of("si", "wu", "wei")),
            Map.entry("chou", List.of("yin", "mao", "chen")),
            Map.entry("yin", List.of("hai", "zi", "chou")),
            Map.entry("mao", List.of("shen", "you", "xu")),
            Map.entry("chen", List.of("si", "wu", "wei")),
            Map.entry("si", List.of("yin", "mao", "chen")),
            Map.entry("wu", List.of("hai", "zi", "chou")),
            Map.entry("wei", List.of("shen", "you", "xu")),
            Map.entry("shen", List.of("si", "wu", "wei")),
            Map.entry("you", List.of("yin", "mao", "chen")),
            Map.entry("xu", List.of("hai", "zi", "chou")),
            Map.entry("hai", List.of("shen", "you", "xu")));

    /** Timing.js `getYellowBlack` items (12 auspicious/inauspicious spirits). */
    private static final List<String> YB_ITEMS = List.of(
            "greenDragon", "brightHall", "heavenPunishment", "redPhoenix", "goldLock",
            "preciousLight", "whiteTiger", "jadeHall", "heavenJail", "blackTortoise",
            "lifeGovernor", "grapplingHook");

    /** Timing.js `getYellowBlack` table: month branch -> day branches by slot. */
    private static final Map<String, List<String>> YB_TABLE = Map.ofEntries(
            Map.entry("yin", List.of("zi", "chou", "yin", "mao", "chen", "si", "wu", "wei", "shen", "you", "xu", "hai")),
            Map.entry("shen", List.of("zi", "chou", "yin", "mao", "chen", "si", "wu", "wei", "shen", "you", "xu", "hai")),
            Map.entry("mao", List.of("yin", "mao", "chen", "si", "wu", "wei", "shen", "you", "xu", "hai", "zi", "chou")),
            Map.entry("you", List.of("yin", "mao", "chen", "si", "wu", "wei", "shen", "you", "xu", "hai", "zi", "chou")),
            Map.entry("chen", List.of("chen", "si", "wu", "wei", "shen", "you", "xu", "hai", "zi", "chou", "yin", "mao")),
            Map.entry("xu", List.of("chen", "si", "wu", "wei", "shen", "you", "xu", "hai", "zi", "chou", "yin", "mao")),
            Map.entry("si", List.of("wu", "wei", "shen", "you", "xu", "hai", "zi", "chou", "yin", "mao", "chen", "si")),
            Map.entry("hai", List.of("wu", "wei", "shen", "you", "xu", "hai", "zi", "chou", "yin", "mao", "chen", "si")),
            Map.entry("wu", List.of("shen", "you", "xu", "hai", "zi", "chou", "yin", "mao", "chen", "si", "wu", "wei")),
            Map.entry("zi", List.of("shen", "you", "xu", "hai", "zi", "chou", "yin", "mao", "chen", "si", "wu", "wei")),
            Map.entry("wei", List.of("xu", "hai", "zi", "chou", "yin", "mao", "chen", "si", "wu", "wei", "shen", "you")),
            Map.entry("chou", List.of("xu", "hai", "zi", "chou", "yin", "mao", "chen", "si", "wu", "wei", "shen", "you")));

    /** Timing.js `get28Constellation` items, in lunar-mansion order. */
    private static final List<String> CONSTELLATIONS = List.of(
            "horn", "neck", "foundation", "house", "heart", "tail", "basket", "dipper",
            "ox", "weaving", "void", "danger", "room", "wall", "astride", "mound",
            "stomach", "pleiades", "net", "beak", "orion", "well", "ghost", "willow",
            "star", "bow", "wing", "carriage");

    /** Timing.js anchor: 2020-02-13 is `horn` (index 0). */
    private static final LocalDate CONSTELLATION_ANCHOR = LocalDate.of(2020, 2, 13);

    /** Timing.js `dongGongNumMap` (0..4). */
    private static final List<String> DONG_GONG_NUM = List.of("\u2715\u2715", "\u2715", "\u2605", "\u2605\u2605", "\u2605\u2605\u2605");

    // ---------------------------------------------------------------- public

    /** Compact /api/almanac payload: chart time, pillar names, day info. */
    public Map<String, Object> dayResponse(Map<String, Object> chart) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("time", map(chart.get("info")).get("time"));
        out.put("pillars", compactPillars(chart));
        out.put("dayInfo", getAllDayInfo(chart));
        return out;
    }

    /** Mirrors Timing.getAllDayInfo({ bazi }) including its key order. */
    public Map<String, Object> getAllDayInfo(Map<String, Object> chart) {
        Map<String, Object> pillars = map(chart.get("pillars"));
        Map<String, Object> yearP = map(pillars.get("year"));
        Map<String, Object> monthP = map(pillars.get("month"));
        Map<String, Object> dayP = map(pillars.get("day"));

        String yearBranch = branchName(yearP);
        String monthBranch = branchName(monthP);
        String dayBranch = branchName(dayP);
        String dayStem = stemName(dayP);

        OffsetDateTime at = OffsetDateTime.parse((String) map(chart.get("info")).get("time"));
        LocalDate date = at.toLocalDate();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("officer", getTwelveOfficer(monthBranch, dayBranch));
        out.put("constellation", get28Constellation(date));
        out.put("yellowBlack", getYellowBlack(monthBranch, dayBranch));
        out.put("m3k", threeKillings(monthBranch, dayBranch));
        out.put("y3k", threeKillings(yearBranch, dayBranch));

        // bigDisaster: the RN check is `bigDisasterDays[year].indexOf(lookup) > 0`,
        // so an entry at index 0 never flags and the value stays null otherwise.
        Object bigDisaster = null;
        JsonNode yearDays = data.get("bigDisasterDays").get(String.valueOf(at.getYear()));
        if (yearDays != null) {
            String lookup = String.format("%02d-%02d", at.getMonthValue(), at.getDayOfMonth());
            int index = 0;
            for (JsonNode n : yearDays) {
                if (n.asText().equals(lookup)) {
                    if (index > 0) bigDisaster = true;
                    break;
                }
                index++;
            }
        }
        out.put("bigDisaster", bigDisaster);

        out.put("yearBreaker", Objects.equals(branchField(yearP, "clash"), dayBranch));
        out.put("monthBreaker", Objects.equals(branchField(monthP, "clash"), dayBranch));
        out.put("monthDestructor", Objects.equals(branchField(monthP, "destruction"), dayBranch));

        JsonNode other = data.get("tongShu").get(date.toString());
        out.put("other", other == null ? null : DataStore.deep(other));

        JsonNode dongGong = data.get("dongGong").get(monthBranch).get(dayStem + "-" + dayBranch);
        if (dongGong != null && !dongGong.isNull()) {
            out.put("dongGongNum", DONG_GONG_NUM.get(dongGong.asInt()));
        }
        // else: JS yields undefined and JSON.stringify drops the key; keep it dropped.
        return out;
    }

    /** Reference dictionaries for the UI (descriptions of officers/constellations). */
    public Map<String, Object> reference() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("officers", DataStore.deepList(data.get("officers")));
        out.put("twelveOfficerDesc", DataStore.deepMap(data.get("twelveOfficerDesc")));
        out.put("twentyEightConstellationsDesc", DataStore.deepMap(data.get("twentyEightConstellationsDesc")));
        out.put("threeKillingsDesc", DataStore.deepMap(data.get("threeKillingsDesc")));
        return out;
    }

    // ------------------------------------------------------------- algorithm

    /** Timing.js `getTwelveOfficer`: rotate the officer cycle from the month's start day. */
    String getTwelveOfficer(String monthBranch, String dayBranch) {
        String start = MONTH_OFFICER_START_DAY.get(monthBranch);
        int index = OFFICERS.indexOf(start);
        List<String> list = new ArrayList<>(OFFICERS.subList(index, OFFICERS.size()));
        list.addAll(OFFICERS.subList(0, index));
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < BRANCH_ORDER.size(); i++) {
            result.put(BRANCH_ORDER.get(i), list.get(i));
        }
        return result.get(dayBranch);
    }

    /** Timing.js `getYearly/MonthlyThreeKillings`: slot 0..2 maps to robbery/calamity/annual. */
    String threeKillings(String scopeBranch, String dayBranch) {
        int index = THREE_KILLINGS.get(scopeBranch).indexOf(dayBranch);
        return switch (index) {
            case 0 -> "robbery";
            case 1 -> "calamity";
            case 2 -> "annual";
            default -> null;
        };
    }

    /** Timing.js `get28Constellation` (with the toProxy wrap-around quirk). */
    String get28Constellation(LocalDate date) {
        long dayDiff = ChronoUnit.DAYS.between(CONSTELLATION_ANCHOR, date);
        return CONSTELLATIONS.get((int) Math.floorMod(dayDiff, CONSTELLATIONS.size()));
    }

    /** Timing.js `getYellowBlack`: day branch slot within the month-branch belt. */
    String getYellowBlack(String monthBranch, String dayBranch) {
        return YB_ITEMS.get(YB_TABLE.get(monthBranch).indexOf(dayBranch));
    }

    // --------------------------------------------------------------- helpers

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    private static String stemName(Map<String, Object> pillar) {
        return (String) map(pillar.get("stem")).get("name");
    }

    private static String branchName(Map<String, Object> pillar) {
        return (String) map(pillar.get("branch")).get("name");
    }

    private static Object branchField(Map<String, Object> pillar, String field) {
        return map(pillar.get("branch")).get(field);
    }

    /** year/month/day/hour -> { stem, branch, chinese } for the UI. */
    private Map<String, Object> compactPillars(Map<String, Object> chart) {
        Map<String, Object> pillars = map(chart.get("pillars"));
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : List.of("year", "month", "day", "hour")) {
            Object p = pillars.get(key);
            if (p == null) {
                out.put(key, null);
                continue;
            }
            Map<String, Object> pm = map(p);
            Map<String, Object> stem = map(pm.get("stem"));
            Map<String, Object> branch = map(pm.get("branch"));
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("stem", stem.get("name"));
            c.put("branch", branch.get("name"));
            c.put("chinese", String.valueOf(stem.get("chinese")) + branch.get("chinese"));
            out.put(key, c);
        }
        return out;
    }
}
