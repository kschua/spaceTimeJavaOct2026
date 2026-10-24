package com.bazi;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.zone.ZoneRules;
import java.util.*;

import static com.bazi.Tables.BRANCHES;

/**
 * Port of the cycle helpers in lib/cmtools/SolarCal.js:
 * getMonthlyTerms, getMajorCycle, getMinorCycle, findTerm, getSolstice.
 */
@Service
public class CycleService {

    private final DataStore store;

    public CycleService(DataStore store) {
        this.store = store;
    }

    private JsonNode solarTerms() { return store.data.get("solarTerms"); }

    /** A term entry as produced by getMonthlyTerms / getMajorCycle year/day/hour frames. */
    public static class TermEntry {
        public final int num;
        public final String chinese, english, element, timing, branch, desc;
        public final LocalDateTime time;
        public final List<String> markers = new ArrayList<>();

        TermEntry(JsonNode data, LocalDateTime time) {
            this.num = data.get("num").asInt();
            this.chinese = data.get("chinese").asText();
            this.english = data.get("english").asText();
            this.element = data.get("element").asText();
            this.timing = data.get("timing").asText();
            this.branch = data.get("branch").asText();
            this.desc = data.get("desc").asText();
            this.time = time;
        }

        /** year/day/hour frame entries only carry branch & time */
        TermEntry(String branch, LocalDateTime time, List<String> markers) {
            this.num = -1;
            this.chinese = null; this.english = null; this.element = null; this.timing = null;
            this.branch = branch; this.desc = null;
            this.time = time;
            this.markers.addAll(markers);
        }
    }

    public Map<String, Object> getMonthlyTerms(LocalDateTime time, int thisTermNum, boolean showMidTerm) {
        List<TermEntry> results = new ArrayList<>();
        for (int yearOff = -1; yearOff <= 1; yearOff++) {
            int year = time.getYear() + yearOff;
            for (int month = 1; month <= 12; month++) {
                JsonNode monthData = store.solar.get(String.valueOf(year)).get(String.valueOf(month));
                if (monthData == null) continue;
                for (int idx = 0; idx < monthData.size(); idx++) {
                    JsonNode term = monthData.get(idx);
                    int num = term.get(0).asInt();
                    JsonNode t = term.get(1);
                    LocalDateTime at = shiftTerm(year, month, asInt(t.get(0)), asInt(t.get(1)), asInt(t.get(2)), offsetMinutesFor(time));
                    TermEntry entry = new TermEntry(solarTerms().get(num), at);
                    if (idx == 0) results.add(entry);               // jie always shown
                    if (time.getYear() == year && num == thisTermNum) entry.markers.add("current");
                    if (idx == 1) {                                  // qi (mid-term)
                        if (showMidTerm) {
                            TermEntry mid = new TermEntry(solarTerms().get(num), at);
                            if (time.getYear() == year && num == thisTermNum) mid.markers.add("current");
                            results.add(mid);
                        } else if (time.getYear() == year && num == thisTermNum && !results.isEmpty()) {
                            // JS: the current mid-term marks the surrounding jie entry instead
                            results.get(results.size() - 1).markers.add("current");
                        }
                    }
                }
            }
        }
        int currentIndex = -1;
        for (int i = 0; i < results.size(); i++) if (results.get(i).markers.contains("current")) currentIndex = i;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("terms", results);
        out.put("currentIndex", currentIndex);
        return out;
    }

    /** exact port of getCurrentSolarTerm — needed for the month marker semantics in getMonthlyTerms */
    @SuppressWarnings("unchecked")
    public TermEntry currentTerm(LocalDateTime time) {
        Map<String, Object> pair = getCurrentTermPair(time);
        TermEntry t = (TermEntry) pair.get("this");
        return t;
    }

    private Map<String, Object> getCurrentTermPair(LocalDateTime time) {
        long off = offsetMinutesFor(time);
        List<TermEntry> thisMonth = monthTerms(time.getYear(), time.getMonthValue(), off);
        List<TermEntry> nextMonth = monthTerms(time.getYear(), time.getMonthValue() + 1, off);
        List<TermEntry> prevMonth = monthTerms(time.getYear(), time.getMonthValue() - 1, off);
        TermEntry term = prevMonth.get(1), nextTerm = thisMonth.get(0);
        if (!time.isBefore(thisMonth.get(1).time)) { term = thisMonth.get(1); nextTerm = nextMonth.get(0); }
        else if (!time.isBefore(thisMonth.get(0).time)) { term = thisMonth.get(0); nextTerm = thisMonth.get(1); }
        return Map.of("this", term, "next", nextTerm);
    }

    private List<TermEntry> monthTerms(int y, int m, long chartOffset) {
        int year = y, month = m;
        if (month > 12) { year++; month -= 12; }
        if (month < 1) { year--; month += 12; }
        JsonNode data = store.solar.get(String.valueOf(year)).get(String.valueOf(month));
        List<TermEntry> out = new ArrayList<>();
        for (JsonNode term : data) {
            int num = term.get(0).asInt();
            JsonNode t = term.get(1);
            LocalDateTime at = shiftTerm(year, month, asInt(t.get(0)), asInt(t.get(1)), asInt(t.get(2)), chartOffset);
            out.add(new TermEntry(solarTerms().get(num), at));
        }
        return out;
    }

    private long offsetMinutesFor(LocalDateTime t) {
        return ZoneId.systemDefault().getRules().getOffset(t.atZone(ZoneId.systemDefault()).toInstant()).getTotalSeconds() / 60;
    }

    /** Replicates luxon `DateTime.fromObject(wall).plus({ minutes: offset })`:
     *  interpret the raw wall-clock in the system zone, add `offset` REAL elapsed minutes,
     *  then re-render back in the zone (so DST transitions inside the shift are honored). */
    private LocalDateTime shiftTerm(int y, int mo, int d, int h, int mi, long offsetMinutes) {
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime raw = LocalDateTime.of(y, mo, d, h, mi).atZone(zone);
        return raw.plusMinutes(offsetMinutes).toLocalDateTime();
    }

    /** Replicates luxon `DateTime.plus({ hours })` — real elapsed hours through the zone. */
    private LocalDateTime plusRealHours(LocalDateTime t, long hours) {
        ZoneId zone = ZoneId.systemDefault();
        return t.atZone(zone).plusHours(hours).toLocalDateTime();
    }

    private int asInt(JsonNode n) { return n.isInt() ? n.asInt() : Integer.parseInt(n.asText()); }

    public TermEntry findTerm(List<TermEntry> terms, int current, String branch, Integer num, boolean backward) {
        int cur = current >= 0 ? current : indexOfCurrent(terms);
        for (int i = 0; i < 24; i++) {
            int idx = backward ? cur - i : cur + i;
            if (idx < 0 || idx >= terms.size()) continue;
            TermEntry item = terms.get(idx);
            if (branch != null && item.branch.equals(branch)) return item;
            if (num != null && item.num == num) return item;
        }
        return null;
    }

    private int indexOfCurrent(List<TermEntry> terms) {
        for (int i = 0; i < terms.size(); i++) if (terms.get(i).markers.contains("current")) return i;
        return 0;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getMajorCycle(Map<String, Object> chart, String type) {
        Map<String, Object> info = (Map<String, Object>) chart.get("info");
        Map<String, Object> solarTerm = (Map<String, Object>) info.get("solarTerm");
        int thisTermNum = thisTermNum(solarTerm);
        LocalDateTime time = chartTime(info);
        Map<String, Object> pillar = (Map<String, Object>) ((Map<String, Object>) chart.get("pillars")).get(type);
        Map<String, Object> branch = (Map<String, Object>) pillar.get("branch");

        List<TermEntry> terms;
        int currentIndex;
        switch (type) {
            case "month" -> {
                Map<String, Object> monthTerms = getMonthlyTerms(time, thisTermNum, false);
                terms = (List<TermEntry>) monthTerms.get("terms");
                currentIndex = (int) monthTerms.get("currentIndex");
            }
            case "year" -> {
                terms = new ArrayList<>();
                int bn = Integer.parseInt(branch.get("number").toString());
                long bo = offsetMinutesFor(time);
                for (int i = 12; i >= 1; i--)
                    terms.add(new TermEntry(BRANCHES.get(floorMod(bn - i, 12)), getCurrentYearJieAt(time.minusYears(i), bo), List.of()));
                terms.add(new TermEntry(branch.get("name").toString(), getCurrentYearJieAt(time, bo), List.of("current")));
                for (int i = 1; i < 13; i++)
                    terms.add(new TermEntry(BRANCHES.get(floorMod(bn + i, 12)), getCurrentYearJieAt(time.plusYears(i), bo), List.of()));
                currentIndex = 12;
            }
            case "day" -> {
                terms = new ArrayList<>();
                int bn = Integer.parseInt(branch.get("number").toString());
                for (int i = 12; i >= 1; i--)
                    terms.add(new TermEntry(BRANCHES.get(floorMod(bn - i, 12)), time.minusDays(i).toLocalDate().atStartOfDay(), List.of()));
                terms.add(new TermEntry(branch.get("name").toString(), time.toLocalDate().atStartOfDay(), List.of("current")));
                for (int i = 1; i < 13; i++)
                    terms.add(new TermEntry(BRANCHES.get(floorMod(bn + i, 12)), time.plusDays(i).toLocalDate().atStartOfDay(), List.of()));
                currentIndex = 12;
            }
            case "hour" -> {
                terms = new ArrayList<>();
                int bn = Integer.parseInt(branch.get("number").toString());
                LocalDateTime beginHour = (time.getHour() % 2 == 0)
                        ? time.minusHours(1).withMinute(0).withSecond(0).withNano(0)
                        : time.withMinute(0).withSecond(0).withNano(0);
                for (int i = 12; i >= 1; i--)
                    terms.add(new TermEntry(BRANCHES.get(floorMod(bn - i, 12)), plusRealHours(beginHour, (long) -i * 2), List.of()));
                terms.add(new TermEntry(branch.get("name").toString(), beginHour, List.of("current")));
                for (int i = 1; i < 13; i++)
                    terms.add(new TermEntry(BRANCHES.get(floorMod(bn + i, 12)), plusRealHours(beginHour, (long) i * 2), List.of()));
                currentIndex = 12;
            }
            default -> throw new IllegalArgumentException(type);
        }

        String season = branch.get("season").toString();
        List<String> cycleSeasons = switch (season) {
            case "spring" -> List.of("winter", "spring", "summer");
            case "summer" -> List.of("spring", "summer", "autumn");
            case "autumn" -> List.of("summer", "autumn", "winter");
            case "winter" -> List.of("autumn", "winter", "spring");
            default -> List.of();
        };
        Map<String, Map<String, String>> seasonBoundaries = Map.of(
                "winter", Map.of("begin", "shen", "end", "si"),
                "spring", Map.of("begin", "hai", "end", "shen"),
                "summer", Map.of("begin", "yin", "end", "hai"),
                "autumn", Map.of("begin", "si", "end", "yin"));

        Map<String, Object> cycles = new LinkedHashMap<>();
        for (String s : cycleSeasons) {
            TermEntry begin = findTerm(terms, currentIndex, seasonBoundaries.get(s).get("begin"), null, true);
            TermEntry end = findTerm(terms, currentIndex, seasonBoundaries.get(s).get("end"), null, false);
            Map<String, Object> cycle = new LinkedHashMap<>();
            cycle.put("begin", begin == null ? null : begin.time.toString());
            cycle.put("end", end == null ? null : end.time.toString());
            cycles.put(s, cycle);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cycles", cycles);
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("begin", currentIndex - 6 >= 0 && currentIndex - 6 < terms.size() ? termRef(terms.get(currentIndex - 6)) : null);
        frame.put("end", currentIndex + 6 < terms.size() ? termRef(terms.get(currentIndex + 6)) : null);
        out.put("frame", frame);
        List<Map<String, Object>> termList = new ArrayList<>();
        for (TermEntry t : terms) termList.add(termRef(t));
        out.put("terms", termList);
        return out;
    }

    private Map<String, Object> termRef(TermEntry t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("num", t.num);
        m.put("branch", t.branch);
        m.put("chinese", t.chinese);
        m.put("english", t.english);
        m.put("time", t.time == null ? null : t.time.toString());
        m.put("markers", t.markers);
        return m;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getMinorCycle(Map<String, Object> chart, String type) {
        Map<String, Object> info = (Map<String, Object>) chart.get("info");
        LocalDateTime time = chartTime(info);
        Map<String, Object> solarTerm = (Map<String, Object>) info.get("solarTerm");
        int thisTermNum = thisTermNum(solarTerm);
        boolean isPreviousYear = (boolean) info.get("isPreviousYear");
        Map<String, Object> pillar = (Map<String, Object>) ((Map<String, Object>) chart.get("pillars")).get(type);
        Map<String, Object> branch = (Map<String, Object>) pillar.get("branch");
        String group = branch.get("group").toString();

        LocalDateTime begin = null, end = null;
        switch (type) {
            case "month" -> {
                Map<String, Object> monthly = getMonthlyTerms(time, thisTermNum, true);
                List<TermEntry> terms = (List<TermEntry>) monthly.get("terms");
                int ci = (int) monthly.get("currentIndex");
                if (thisTermNum >= 0 && thisTermNum <= 5) { begin = terms.get(ci - thisTermNum).time; end = terms.get(ci + (6 - thisTermNum)).time; }
                else if (thisTermNum >= 6 && thisTermNum <= 11) { begin = terms.get(ci - (thisTermNum - 6)).time; end = terms.get(ci + (12 - thisTermNum)).time; }
                else if (thisTermNum >= 12 && thisTermNum <= 17) { begin = terms.get(ci - (thisTermNum - 12)).time; end = terms.get(ci + (18 - thisTermNum)).time; }
                else if (thisTermNum >= 18 && thisTermNum <= 23) { begin = terms.get(ci - (thisTermNum - 18)).time; end = terms.get(ci + (24 - thisTermNum)).time; }
            }
            case "year" -> {
                int py = isPreviousYear ? 1 : 0;
                long bo = offsetMinutesFor(time);
                if (group.equals("start")) { begin = getCurrentYearJieAt(time.minusYears(py), bo); end = getCurrentYearJieAt(time.plusYears(3 - py), bo); }
                else if (group.equals("peak")) { begin = getCurrentYearJieAt(time.minusYears(1 + py), bo); end = getCurrentYearJieAt(time.plusYears(2 - py), bo); }
                else if (group.equals("end")) { begin = getCurrentYearJieAt(time.minusYears(2 + py), bo); end = getCurrentYearJieAt(time.plusYears(1 - py), bo); }
            }
            case "day" -> {
                if (group.equals("start")) { begin = time.toLocalDate().atStartOfDay(); end = time.plusDays(2).toLocalDate().atTime(23, 59, 59); }
                else if (group.equals("peak")) { begin = time.minusDays(1).toLocalDate().atStartOfDay(); end = time.plusDays(1).toLocalDate().atTime(23, 59, 59); }
                else if (group.equals("end")) { begin = time.minusDays(2).toLocalDate().atStartOfDay(); end = time.toLocalDate().atTime(23, 59, 59); }
            }
            case "hour" -> {
                LocalDateTime beginHour = (time.getHour() % 2 == 0) ? time.minusHours(1) : time;
                LocalDateTime bh = beginHour.withMinute(0).withSecond(0).withNano(0);
                if (group.equals("start")) { begin = bh; end = plusRealHours(bh, 5).withMinute(59).withSecond(59); }
                else if (group.equals("peak")) { begin = plusRealHours(bh, -2); end = plusRealHours(bh, 3).withMinute(59).withSecond(59); }
                else if (group.equals("end")) { begin = plusRealHours(bh, -4); end = plusRealHours(bh, 1).withMinute(59).withSecond(59); }
            }
            default -> throw new IllegalArgumentException(type);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("begin", begin == null ? null : begin.toString());
        out.put("end", end == null ? null : end.toString());
        return out;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getSolstice(Map<String, Object> chart) {
        Map<String, Object> info = (Map<String, Object>) chart.get("info");
        LocalDateTime time = chartTime(info);
        Map<String, Object> solarTerm = (Map<String, Object>) info.get("solarTerm");
        int thisTermNum = thisTermNum(solarTerm);
        TermEntry cur = currentTerm(time);
        if (thisTermNum == 9) return Map.of("solstice", "summer", "time", cur.time.toString());
        if (thisTermNum == 21) return Map.of("solstice", "winter", "time", cur.time.toString());

        for (int i = 1; i < 99; i++) {
            LocalDateTime newTime = time.minusDays(i * 15L);
            JsonNode monthData = store.solar.get(String.valueOf(newTime.getYear())) == null ? null
                    : store.solar.get(String.valueOf(newTime.getYear())).get(String.valueOf(newTime.getMonthValue()));
            if (monthData == null) continue;
            for (JsonNode data : monthData) {
                int num = data.get(0).asInt();
                if (num == 9 || num == 21) {
                    JsonNode t = data.get(1);
                    LocalDateTime at = LocalDateTime.of(newTime.getYear(), newTime.getMonthValue(), asInt(t.get(0)), asInt(t.get(1)), asInt(t.get(2)));
                    return Map.of("solstice", num == 9 ? "summer" : "winter", "time", at.toString());
                }
            }
        }
        return null;
    }

    private long luxonOffsetForShifted(LocalDateTime shifted, long baseOffsetMinutes) {
        ZoneRules rules = ZoneId.systemDefault().getRules();
        List<ZoneOffset> valid = rules.getValidOffsets(shifted);
        if (valid.isEmpty())
            return offsetMinutesFor(shifted);
        for (ZoneOffset vo : valid)
            if (vo.getTotalSeconds() / 60 == baseOffsetMinutes)
                return baseOffsetMinutes;
        return valid.get(0).getTotalSeconds() / 60;
    }

    private LocalDateTime getCurrentYearJieAt(LocalDateTime t, long baseOffset) {
        long off = luxonOffsetForShifted(t, baseOffset);
        for (int m = 1; m < 6; m++) {
            JsonNode data = store.solar.get(String.valueOf(t.getYear())).get(String.valueOf(m));
            if (data != null) for (JsonNode term : data) {
                if (term.get(0).asInt() == 0) {
                    JsonNode tt = term.get(1);
                    return shiftTerm(t.getYear(), m, asInt(tt.get(0)), asInt(tt.get(1)), asInt(tt.get(2)), off);
                }
            }
        }
        for (int m = 12; m > 6; m--) {
            JsonNode data = store.solar.get(String.valueOf(t.getYear() - 1)).get(String.valueOf(m));
            if (data != null) for (JsonNode term : data) {
                if (term.get(0).asInt() == 0) {
                    JsonNode tt = term.get(1);
                    return shiftTerm(t.getYear() - 1, m, asInt(tt.get(0)), asInt(tt.get(1)), asInt(tt.get(2)), off);
                }
            }
        }
        throw new IllegalStateException();
    }

    /** info.time is ISO with an offset (RN used a luxon DateTime) — cycle math uses the wall clock. */
    private static LocalDateTime chartTime(Map<String, Object> info) {
        String raw = String.valueOf(info.get("time"));
        try {
            return java.time.OffsetDateTime.parse(raw).toLocalDateTime();
        } catch (java.time.format.DateTimeParseException e) {
            return LocalDateTime.parse(raw);
        }
    }

    /** info.solarTerm = {thisTerm: {data: {...}, begins}, nextTerm: {...}} — the marker number is thisTerm.data.num. */
    private static int thisTermNum(Map<String, Object> solarTerm) {
        Object term = solarTerm.get("thisTerm");
        if (term instanceof Map<?, ?> m) {
            Object data = m.get("data");
            if (data instanceof Map<?, ?> d) {
                Object num = d.get("num");
                if (num instanceof Number n) return n.intValue();
            }
        }
        return -1;
    }

    private static int floorMod(int x, int m) { int r = x % m; return r < 0 ? r + m : r; }
}
