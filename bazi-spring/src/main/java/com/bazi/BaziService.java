package com.bazi;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.*;

import static com.bazi.DataStore.deep;
import static com.bazi.DataStore.deepList;
import static com.bazi.DataStore.deepMap;
import static com.bazi.Tables.*;

@Service
public class BaziService {

    private final DataStore store;
    private final SolarCalendar cal;
    private final ShenShaService shensha;

    public BaziService(DataStore store, ShenShaService shensha) {
        this.store = store;
        this.cal = new SolarCalendar(store); // offset applied per-chart via chartCal in plot()
        this.shensha = shensha;
    }

    private JsonNode stems() { return store.node("stems"); }
    private JsonNode branches() { return store.node("branches"); }
    private JsonNode dms() { return store.node("dms"); }

    /** Mirrors the RN Plotters/FourPillars plot() payload. */
    public static final class PlotOptions {
        public String time;
        public String chartName = null;
        public String gender = "male";
        public boolean genderUnknown = false;
        public boolean hourUnknown = false;
        public boolean lateZi = false;
        public boolean showLuck = false;
        public boolean showWeights = false;
        public boolean weightsIncludeCombos = true;
        public boolean simpleLuckCycle = true;
        /** RN `shiftingPalace?.stem` — overrides the day master used for the maps */
        public String shiftingPalaceStem = null;
        /** RN `root` chart — referenceStem is taken from that chart's day pillar */
        public String rootTime = null;
        /** run EightWords.extendAuxDataChart() on the result */
        public boolean extend = false;
    }

    /** -------- minute pillar (Process.calcMinutePillar) -------- */
    public List<String> calcMinutePillar(LocalDateTime current, String chineseTimeString, String hStem, String hBranch) {
        String[] t = chineseTimeString.split("-");
        LocalDateTime begins = LocalDateTime.of(Integer.parseInt(t[0]), Integer.parseInt(t[1]), Integer.parseInt(t[2]), Integer.parseInt(t[3]), 0);
        long minutes = java.time.Duration.between(begins, current).toMinutes();
        int branchNum = (int) (minutes / 10);
        String minB = BRANCHES.get(branchNum);
        Map<String, Integer> table = Map.of("jia", 0, "yi", 2, "bing", 4, "ding", 6, "wu", 8, "ji", 0, "geng", 2, "xin", 4, "ren", 6, "gui", 8);
        int stemNum = table.get(hStem) + branchNum;
        if (stemNum >= 10) stemNum -= 10;
        return List.of(STEMS.get(stemNum), minB);
    }

    public String chineseTimeString(LocalDateTime t) {
        if (t.getHour() % 2 == 0) t = t.minusHours(1);
        return String.format("%04d-%02d-%02d-%02d", t.getYear(), t.getMonthValue(), t.getDayOfMonth(), t.getHour());
    }

    /** -------- life & conception palace (EightWords) -------- */
    public List<String> getLifePalace(String monthBranch, String hourBranch, String yearStem, boolean appendMonth) {
        String month = monthBranch;
        if (appendMonth) {
            int i = BRANCHES.indexOf(monthBranch) + 1;
            month = BRANCHES.get(i >= BRANCHES.size() ? i - BRANCHES.size() : i);
        }
        String branch = store.node("lifePalaceBranch", month, hourBranch).asText();
        String stem = store.node("lifePalaceStem", yearStem, branch).asText();
        return List.of(stem, branch);
    }

    public List<String> getConceptionPalace(String stem, String branch) {
        int si = STEMS.indexOf(stem) + 1;
        String cs = si > STEMS.size() - 1 ? STEMS.get(0) : STEMS.get(si);
        int bi = BRANCHES.indexOf(branch) + 3;
        String cb = bi > BRANCHES.size() - 1 ? BRANCHES.get(bi - BRANCHES.size()) : BRANCHES.get(bi);
        return List.of(cs, cb);
    }

    /** Luxon math port: DateTimes built as DateTime.fromObject(wall).plus({minutes: chartOffset}),
     *  diff('days', {conversionAccuracy:'longterm'}) and plus({days}) — uses calendar days, not
     *  raw millis/Duration. Mirrors luxon's fix_offset/day-diff/adjust_time. */
    private static final class LuxDT {
        final long ts; // epoch millis (UTC instant)
        final int off; // zone offset minutes at ts
        /** non-null = luxon FixedOffsetZone (charts whose ISO input carried an
         *  explicit offset). null = system zone (naive ISO input / DateTime.local). */
        final Integer fixedOff;

        LuxDT(long ts, int off) { this(ts, off, null); }
        LuxDT(long ts, int off, Integer fixedOff) { this.ts = ts; this.off = off; this.fixedOff = fixedOff; }

        static LuxDT fromObject(int y, int mo, int d, int h, int mi, int s, Integer fixedOff) {
            long localTS = LocalDateTime.of(y, mo, d, h, mi, s).toEpochSecond(ZoneOffset.UTC) * 1000L;
            if (fixedOff != null) return new LuxDT(localTS - fixedOff * 60000L, fixedOff, fixedOff);
            int provisional = systemOffsetAt(System.currentTimeMillis());
            long[] r = systemFixOffset(localTS, provisional);
            return new LuxDT(r[0], (int) r[1], null);
        }
        static int systemOffsetAt(long ts) {
            return ZoneId.systemDefault().getRules().getOffset(Instant.ofEpochMilli(ts)).getTotalSeconds() / 60;
        }
        int offsetAt(long t) {
            return fixedOff != null ? fixedOff : systemOffsetAt(t);
        }
        long[] fixOffset(long localTS, long o) {
            if (fixedOff != null) return new long[]{localTS - fixedOff * 60000L, fixedOff};
            return systemFixOffset(localTS, o);
        }
        static long[] systemFixOffset(long localTS, long o) {
            long utcGuess = localTS - o * 60000L;
            int o2 = systemOffsetAt(utcGuess);
            if (o == o2) return new long[]{utcGuess, o};
            utcGuess -= (long) (o2 - o) * 60000L;
            int o3 = systemOffsetAt(utcGuess);
            if (o2 == o3) return new long[]{utcGuess, o2};
            return new long[]{localTS - Math.min(o2, o3) * 60000L, Math.max(o2, o3)};
        }
        LocalDateTime wall() {
            return Instant.ofEpochMilli(ts + off * 60000L).atZone(ZoneOffset.UTC).toLocalDateTime();
        }
        LuxDT plusMinutes(long m) {
            // luxon adjust_time for minutes: ts += minutes*60000; offset = zone.offset(ts)
            long ts2 = ts + m * 60000L;
            return new LuxDT(ts2, offsetAt(ts2), fixedOff);
        }
        LuxDT plusDays(double days) { // adjust_time: whole days add to calendar day; fraction as ms
            long intDays = (long) Math.floor(days);
            double frac = days - intDays;
            LocalDateTime c0 = wall();
            int y = c0.getYear();
            int mo = c0.getMonthValue();
            int dd = Math.min(c0.getDayOfMonth(), java.time.YearMonth.of(y, mo).lengthOfMonth()) + (int) intDays;
            LocalDateTime base = LocalDateTime.of(y, mo, 1, c0.getHour(), c0.getMinute(), c0.getSecond(), 0);
            LocalDateTime c = base.plusDays(dd - 1L);
            long localTS = LocalDateTime.of(c.getYear(), c.getMonthValue(), c.getDayOfMonth(), c.getHour(), c.getMinute(), c.getSecond())
                    .toEpochSecond(ZoneOffset.UTC) * 1000L;
            long[] r = fixOffset(localTS, off);
            long ts2 = r[0];
            int o = (int) r[1];
            if (frac != 0) {
                // luxon adds the fractional milliseconds as a JS float to the epoch
                // timestamp; at epoch magnitudes the double rounds to the nearest
                // millisecond. Truncating frac*86400000 separately loses 1 ms.
                ts2 = (long) (ts2 + frac * 86400000.0);
                o = offsetAt(ts2);
            }
            return new LuxDT(ts2, o, fixedOff);
        }
        static long utcDayStart(LuxDT dt) { // toUTC(0,{keepLocalTime:true}).startOf('day') → wall date as UTC midnight
            LocalDateTime w = dt.wall();
            return LocalDateTime.of(w.getYear(), w.getMonthValue(), w.getDayOfMonth(), 0, 0).toEpochSecond(ZoneOffset.UTC) * 1000L;
        }
        static double diffDays(LuxDT earlier, LuxDT later) { // luxon diff 'days' (highOrderDiffs)
            long ms = utcDayStart(later) - utcDayStart(earlier);
            int days = (int) Math.floor(ms / 86400000.0);
            LuxDT highWater = earlier.plusDays(days);
            LuxDT cursor;
            if (highWater.ts > later.ts) {
                days--;
                cursor = earlier.plusDays(days);
                if (cursor.ts > later.ts) {
                    highWater = cursor;
                    days--;
                    cursor = earlier.plusDays(days);
                }
            } else {
                cursor = highWater;
            }
            long remainingMillis = later.ts - cursor.ts;
            if (highWater.ts < later.ts) highWater = cursor.plusDays(1);
            if (highWater.ts != cursor.ts) {
                return days + (double) remainingMillis / (highWater.ts - cursor.ts);
            }
            return days;
        }
        static LuxDT minByTs(LuxDT a, LuxDT b) { return a.ts <= b.ts ? a : b; }
        static LuxDT maxByTs(LuxDT a, LuxDT b) { return a.ts >= b.ts ? a : b; }
    }

    private static final class TermLux {
        final int num;
        final LuxDT dt;
        TermLux(int num, LuxDT dt) { this.num = num; this.dt = dt; }
    }

    /** getYearMonthSolarTerm(y, m, null, chartOffset) using raw solarData walls + plus({minutes: offset}) */
    private List<TermLux> yearMonthTermLux(int y, int m, int chartOffset, Integer fixedOff) {
        int year = y, month = m;
        if (month > 12) { year++; month -= 12; }
        if (month < 1) { year--; month += 12; }
        List<TermLux> result = new ArrayList<>();
        JsonNode data = store.solar.get(String.valueOf(year)).get(String.valueOf(month));
        for (JsonNode term : data) {
            JsonNode t = term.get(1);
            LuxDT dt = LuxDT.fromObject(year, month, t.get(0).asInt(), t.get(1).asInt(), t.get(2).asInt(), 0, fixedOff)
                    .plusMinutes(chartOffset);
            result.add(new TermLux(term.get(0).asInt(), dt));
        }
        return result;
    }

    /** getForwardJie(current) with luxon-time (ts) comparisons */
    private LuxDT forwardJieLux(LuxDT current, int chartOffset) {
        List<TermLux> thisMonth = yearMonthTermLux(wallYear(current), wallMonth(current), chartOffset, current.fixedOff);
        List<TermLux> nextMonth = yearMonthTermLux(wallYear(current), wallMonth(current) + 1, chartOffset, current.fixedOff);
        if (current.ts < thisMonth.get(0).dt.ts) {
            return thisMonth.get(0).num % 2 == 0 ? thisMonth.get(0).dt : thisMonth.get(1).dt;
        } else if (current.ts < thisMonth.get(1).dt.ts) {
            return thisMonth.get(1).num % 2 == 0 ? thisMonth.get(1).dt : nextMonth.get(0).dt;
        } else {
            return nextMonth.get(0).num % 2 == 0 ? nextMonth.get(0).dt : nextMonth.get(1).dt;
        }
    }

    /** getReverseJie(current) with luxon-time (ts) comparisons */
    private LuxDT reverseJieLux(LuxDT current, int chartOffset) {
        List<TermLux> thisMonth = yearMonthTermLux(wallYear(current), wallMonth(current), chartOffset, current.fixedOff);
        List<TermLux> prevMonth = yearMonthTermLux(wallYear(current), wallMonth(current) - 1, chartOffset, current.fixedOff);
        if (current.ts > thisMonth.get(1).dt.ts) {
            return thisMonth.get(1).num % 2 == 0 ? thisMonth.get(1).dt : thisMonth.get(0).dt;
        } else if (current.ts > thisMonth.get(0).dt.ts) {
            return thisMonth.get(0).num % 2 == 0 ? thisMonth.get(0).dt : prevMonth.get(1).dt;
        } else {
            return prevMonth.get(1).num % 2 == 0 ? prevMonth.get(1).dt : prevMonth.get(0).dt;
        }
    }

    private static int wallYear(LuxDT d) { return d.wall().getYear(); }
    private static int wallMonth(LuxDT d) { return d.wall().getMonthValue(); }

    /** -------- main plot (Plotters/FourPillars/index.js) -------- */
    public Map<String, Object> plot(String timeIso, String chartName, String gender, boolean hourUnknown,
                                    boolean lateZi, boolean showLuck, boolean showWeights, boolean simpleLuckCycle) {
        PlotOptions o = new PlotOptions();
        o.time = timeIso;
        o.chartName = chartName;
        o.gender = gender;
        o.hourUnknown = hourUnknown;
        o.lateZi = lateZi;
        o.showLuck = showLuck;
        o.showWeights = showWeights;
        o.simpleLuckCycle = simpleLuckCycle;
        return plot(o);
    }

    public Map<String, Object> plot(PlotOptions opts) {
        String chartName = opts.chartName;
        String gender = opts.gender;
        boolean hourUnknown = opts.hourUnknown;
        boolean lateZi = opts.lateZi;
        boolean showLuck = opts.showLuck;
        boolean showWeights = opts.showWeights;
        boolean simpleLuckCycle = opts.simpleLuckCycle;

        Curr cur = resolveCurrent(opts.time);
        LocalDateTime current = cur.wall;
        int chartOffsetMinutes = cur.off;
        SolarCalendar chartCal = new SolarCalendar(store, chartOffsetMinutes);
        SolarCalendar.LocalDateTimeAt at = new SolarCalendar.LocalDateTimeAt(current, null);
        String chineseTimeString = chineseTimeString(current);

        int inceptionYear = 1900;
        LocalDateTime inceptionMidnight = LocalDateTime.of(inceptionYear, 2, 4, 0, 0);
        int inceptionYearIndex = pillarIndex("geng", "zi");
        int inceptionMonthIndex = pillarIndex("wu", "yin");
        int inceptionDayIndex = pillarIndex("wu", "shen");

        int yearDiff = current.getYear() - inceptionYear;
        int monthDiff = current.getMonthValue() - 2 + yearDiff * 12;
        int modifiedDayDiff = (int) Math.floor(java.time.Duration.between(inceptionMidnight, current).toMillis() / 86400000.0);

        List<String> yPillar = pillarAt(inceptionYearIndex + yearDiff);
        List<String> mPillar = pillarAt(inceptionMonthIndex + monthDiff);
        List<String> dPillar = pillarAt(inceptionDayIndex + modifiedDayDiff);

        int minutesSinceDayBegin = current.getHour() * 60 + current.getMinute();
        String hBranch = hourBranchOf(minutesSinceDayBegin);
        int hIndex = STEMS.indexOf(HOUR_TABLE.get(dPillar.get(0))) + BRANCHES.indexOf(hBranch);
        if (minutesSinceDayBegin >= 23 * 60) hIndex += 2;
        if (hIndex > 9) hIndex -= 10;
        List<String> hPillar = List.of(STEMS.get(hIndex), hBranch);

        List<String> minPillar = calcMinutePillar(current, chineseTimeString, hPillar.get(0), hPillar.get(1));

        List<SolarCalendar.Term> solarTerm = chartCal.getYearMonthSolarTerm(current.getYear(), current.getMonthValue());
        if (solarTerm.get(0).num % 2 == 0) {
            if (current.isBefore(solarTerm.get(0).at)) mPillar = shiftPillar(mPillar, -1);
        } else {
            if (!current.isBefore(solarTerm.get(0).at)) mPillar = shiftPillar(mPillar, 1);
        }

        boolean isPreviousYear = false;
        SolarCalendar.LocalDateTimeAt jieNow = new SolarCalendar.LocalDateTimeAt(
                chartCal.getCurrentYearJie(at).lt, null);
        if (current.isBefore(jieNow.lt)) {
            yPillar = shiftPillar(yPillar, -1);
            isPreviousYear = true;
        }

        if (lateZi && minutesSinceDayBegin >= 1380 && minutesSinceDayBegin < 1440) {
            dPillar = shiftPillar(dPillar, 1);
        }

        // ---- extend / decorate pillar data (maps shared by pillars and luck) ----
        // referenceStem = shiftInfo?.stem || root?.pillars?.day?.stem?.name || pillars[2][0]
        String referenceStem = opts.shiftingPalaceStem != null ? opts.shiftingPalaceStem
                : opts.rootTime != null ? dayStemOf(opts.rootTime, lateZi)
                : dPillar.get(0);
        JsonNode dayMaster = dms().get(referenceStem);
        if (dayMaster == null) throw new IllegalArgumentException("unknown reference stem: " + referenceStem);
        Map<String, String> stemToGod = new LinkedHashMap<>();
        dayMaster.get("stemsToGods").fields().forEachRemaining(e -> stemToGod.put(e.getKey(), e.getValue().asText()));
        Map<String, String> godToStem = new LinkedHashMap<>();
        dayMaster.get("godsToStems").fields().forEachRemaining(e -> godToStem.put(e.getKey(), e.getValue().asText()));
        Map<String, List<String>> aspectToStems = new LinkedHashMap<>();
        stems().get(referenceStem).get("aspects").fields().forEachRemaining(e -> {
            List<String> v = new ArrayList<>();
            e.getValue().forEach(n -> v.add(n.asText()));
            aspectToStems.put(e.getKey(), v);
        });
        // RN Process.formPayload: mainElement = Ordinals.stems[referenceStem].element
        String mainElement = stems().get(referenceStem).get("element").asText();
        Map<String, String> aspectToElement = new LinkedHashMap<>();
        store.node("elements", mainElement, "aspects").fields().forEachRemaining(e -> aspectToElement.put(e.getKey(), e.getValue().asText()));
        Map<String, String> elementToAspect = new LinkedHashMap<>();
        store.node("elements", mainElement, "elements")
                .fields().forEachRemaining(e -> elementToAspect.put(e.getKey(), e.getValue().asText()));

        // ---- luck pillars ----
        List<Map<String, Object>> luckPillars = new ArrayList<>();
        if (showLuck) {
            String yearStemPolarity = STEMS.indexOf(yPillar.get(0)) % 2 == 1 ? "yin" : "yang";
            boolean reverse = ("yin".equals(yearStemPolarity) && "male".equals(gender))
                    || ("yang".equals(yearStemPolarity) && "female".equals(gender));
            // Replicate JS luck (Plotters/FourPillars/index.js):
            //  - getForwardJie/getReverseJie select from luxon DateTimes built as
            //    DateTime.fromObject(termWall).plus({minutes: chartOffset})
            //  - interval = |current.diff(jie, 'days', {conversionAccuracy:'longterm'}).days|
            //  - firstLuckPillarTime = current.plus({days: (interval/3)*365})
            // LuxDT below mirrors luxon's wall→instant resolution, calendar-day diff and plus.
            LuxDT currentLux = new LuxDT(cur.ts, cur.off, cur.fixed ? cur.off : null);
            LuxDT theJie = reverse ? reverseJieLux(currentLux, chartOffsetMinutes)
                                   : forwardJieLux(currentLux, chartOffsetMinutes);
            LocalDateTime firstLuckTime;
            if (!simpleLuckCycle) {
                // Plotters/index.js: |current.diff(jie,'minutes').minutes| * 2 hours
                double intervalHours = Math.abs(theJie.ts - currentLux.ts) / 60000.0 * 2.0;
                double ts2 = currentLux.ts + intervalHours * 3600000.0;
                firstLuckTime = new LuxDT((long) ts2, currentLux.offsetAt((long) ts2), currentLux.fixedOff).wall();
            } else {
                double intervalToFirstLuckDays = Math.abs(LuxDT.diffDays(LuxDT.minByTs(currentLux, theJie), LuxDT.maxByTs(currentLux, theJie)));
                LuxDT firstLuck = currentLux.plusDays((intervalToFirstLuckDays / 3.0) * 365.0);
                firstLuckTime = firstLuck.wall();
            }
            luckPillars.add(luckEntry(extendPillar(mPillar, "luck", stemToGod, elementToAspect),
                    isoWithOffset(current, chartOffsetMinutes)));
            for (int i = 1; i < 11; i++) {
                List<String> p = shiftPillar(mPillar, reverse ? -i : i);
                LocalDateTime t = firstLuckTime.plusYears(10L * (i - 1));
                luckPillars.add(luckEntry(extendPillar(p, "luck", stemToGod, elementToAspect),
                        isoWithOffset(t, cur.fixed ? chartOffsetMinutes : offsetAtWall(t))));
            }
        }

        List<String> lifePillar = getLifePalace(mPillar.get(1), hPillar.get(1), yPillar.get(0),
                current.isBefore(solarTerm.get(0).at) || !current.isBefore(solarTerm.get(1).at));
        List<String> conceptionPillar = getConceptionPalace(mPillar.get(0), mPillar.get(1));

        LinkedHashMap<String, Object> pillars = new LinkedHashMap<>();
        pillars.put("year", extendPillar(yPillar, "year", stemToGod, elementToAspect));
        pillars.put("month", extendPillar(mPillar, "month", stemToGod, elementToAspect));
        pillars.put("day", extendPillar(dPillar, "day", stemToGod, elementToAspect));
        pillars.put("hour", hourUnknown ? null : extendPillar(hPillar, "hour", stemToGod, elementToAspect));
        pillars.put("minute", extendPillar(minPillar, "minute", stemToGod, elementToAspect));
        pillars.put("conception", extendPillar(conceptionPillar, "conception", stemToGod, elementToAspect));
        pillars.put("life", extendPillar(lifePillar, "life", stemToGod, elementToAspect));

        SolarCalendar.TermPair currentTerms = chartCal.getCurrentSolarTerm(at);
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("time", isoWithOffset(current, chartOffsetMinutes));
        info.put("gender", gender);
        info.put("genderUnknown", opts.genderUnknown);
        info.put("hourUnknown", hourUnknown);
        info.put("lateZi", lateZi);
        info.put("chartName", chartName);
        info.put("isPreviousYear", isPreviousYear);
        info.put("chineseTimeString", chineseTimeString);
        info.put("weightsIncludeCombos", opts.weightsIncludeCombos);
        info.put("shiftingPalace", opts.shiftingPalaceStem == null ? null : Map.of("stem", opts.shiftingPalaceStem));
        Map<String, Object> solarTermInfo = new LinkedHashMap<>();
        solarTermInfo.put("thisTerm", termInfo(currentTerms.thisTerm, chartOffsetMinutes));
        solarTermInfo.put("nextTerm", termInfo(currentTerms.nextTerm, chartOffsetMinutes));
        info.put("solarTerm", solarTermInfo);

        Map<String, Object> maps = new LinkedHashMap<>();
        maps.put("stemToGod", stemToGod);
        maps.put("godToStem", godToStem);
        maps.put("aspectToElement", aspectToElement);
        maps.put("elementToAspect", elementToAspect);
        maps.put("aspectToStems", aspectToStems);
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("maps", maps);
        meta.put("gua", personalGua(current.getYear(), isPreviousYear, gender));

        Map<String, Object> chart = new LinkedHashMap<>();
        chart.put("info", info);
        chart.put("pillars", pillars);
        chart.put("luck", luckPillars);
        chart.put("meta", meta);

        if (showWeights) {
            calcCounts(chart);
            calcWeights(chart, opts.weightsIncludeCombos);
            calcStats(chart);
            calcFavorableGods(chart);
            getSummary(chart, 0.1);
        }
        if (opts.extend) extendAuxDataChart(chart, null);
        return chart;
    }

    /** Luxon dtA.diff(dtB, 'days').days between two chart times (used by the
     *  Feng Shui day-center scan). Each side's zone kind matters: explicit-offset
     *  ISO inputs are FixedOffsetZones, naive times live in the server system zone.
     */
    static double luxonDiffDays(String laterIso, boolean laterFixed, String earlierIso, boolean earlierFixed) {
        Curr later = resolveCurrent(laterIso);
        Curr earlier = resolveCurrent(earlierIso);
        LuxDT l = new LuxDT(later.ts, later.off, laterFixed ? later.off : null);
        LuxDT e = new LuxDT(earlier.ts, earlier.off, earlierFixed ? earlier.off : null);
        return LuxDT.diffDays(e, l);
    }

    private Map<String, Object> luckEntry(Map<String, Object> pillar, String time) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pillar", pillar);
        m.put("time", time);
        return m;
    }

    private Map<String, Object> termInfo(SolarCalendar.Term t, int offsetMinutes) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("data", deep(store.node("solarTerms").get(t.num)));
        m.put("begins", isoWithOffset(t.at, offsetMinutes));
        return m;
    }

    private static String isoWithOffset(LocalDateTime wall, int offsetMinutes) {
        return OffsetDateTime.of(wall, ZoneOffset.ofTotalSeconds(offsetMinutes * 60)).toString();
    }

    private static int offsetAtWall(LocalDateTime wall) {
        ZoneId zone = ZoneId.systemDefault();
        return zone.getRules().getOffset(wall.atZone(zone).toInstant()).getTotalSeconds() / 60;
    }

    /** day stem of another chart (the RN plot({root}) referenceStem lookup) */
    public String dayStemOf(String timeIso, boolean lateZi) {
        Curr cur = resolveCurrent(timeIso);
        int inceptionDayIndex = pillarIndex("wu", "shen");
        int modifiedDayDiff = (int) Math.floor(java.time.Duration.between(
                LocalDateTime.of(1900, 2, 4, 0, 0), cur.wall).toMillis() / 86400000.0);
        List<String> dPillar = pillarAt(inceptionDayIndex + modifiedDayDiff);
        int mins = cur.wall.getHour() * 60 + cur.wall.getMinute();
        if (lateZi && mins >= 1380 && mins < 1440) dPillar = shiftPillar(dPillar, 1);
        return dPillar.get(0);
    }

    /** WindWater.getPersonalGua(year, isPreviousYear, gender) */
    public Map<String, Object> personalGua(int year, boolean isPreviousYear, String gender) {
        int y = isPreviousYear ? year - 1 : year;
        int diff = y - 1932;
        int[] guaList = "male".equals(gender)
                ? new int[]{5, 4, 3, 2, 1, 9, 8, 7, 6}
                : new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9};
        int guaNumber = guaList[Math.floorMod(diff, 9)];
        if (guaNumber == 5) guaNumber = "male".equals(gender) ? 2 : 8;
        JsonNode found = null;
        Iterator<Map.Entry<String, JsonNode>> it = store.node("guas").fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            JsonNode luoShu = e.getValue().get("luoShu");
            if (luoShu != null && luoShu.asInt() == guaNumber) { found = e.getValue(); break; }
        }
        if (found == null) return null;
        Map<String, Object> gua = deepMap(found);
        List<String> lifeStarDesc = switch (guaNumber) {
            case 1 -> List.of("white", "water");
            case 2 -> List.of("black", "earth");
            case 3, 4 -> List.of("green", "wood");
            case 5 -> List.of("yellow", "earth");
            case 6 -> List.of("white", "metal");
            case 7 -> List.of("red", "metal");
            case 8 -> List.of("white", "earth");
            default -> List.of("purple", "fire");
        };
        gua.put("lifeStarDescArr", lifeStarDesc);
        return gua;
    }

    private Map<String, Object> extendPillar(List<String> pill, String name, Map<String, String> stemToGod,
                                             Map<String, String> elementToAspect) {
        JsonNode s = stems().get(pill.get(0));
        Map<String, Object> stem = deepMap(s);
        String element = s.get("element").asText();
        // RN Process.extendPillar: aspects[stemElement] with aspects = elementToAspect
        String aspect = elementToAspect.get(element);
        String god = stemToGod.get(pill.get(0));
        stem.put("god", god);
        stem.put("aspect", aspect);
        stem.put("aspc", displayAspect(aspect));
        stem.put("gdd", displayGod(god));
        stem.put("elm", deepMap(store.node("elements").get(element)));
        stem.put("interactions", new ArrayList<>());

        JsonNode b = branches().get(pill.get(1));
        Map<String, Object> branch = deepMap(b);
        branch.put("ssn", deepMap(store.node("seasons", b.get("season").asText())));
        // branch.stems — hidden stems keyed by role (storage/main/tomb/growth), the RN shape
        Map<String, Object> branchStems = new LinkedHashMap<>();
        JsonNode hsDict = b.get("_hiddenStemsDict");
        if (hsDict != null) hsDict.fields().forEachRemaining(e -> {
            String hs = e.getValue().isNull() ? null : e.getValue().asText();
            branchStems.put(e.getKey(), hs == null ? null : enrichedStem(hs, stemToGod, elementToAspect));
        });
        branch.put("stems", branchStems);
        // flat list kept for the existing web client
        List<String> hidden = new ArrayList<>();
        for (JsonNode n : b.get("_hiddenStemsArr")) hidden.add(n.isNull() ? null : n.asText());
        List<Map<String, Object>> hid = new ArrayList<>();
        for (int i = 0; i < hidden.size(); i++) {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("stem", hidden.get(i));
            h.put("god", hidden.get(i) == null ? null : stemToGod.get(hidden.get(i)));
            h.put("core", i == 1);
            hid.add(h);
        }
        branch.put("hiddenStems", hid);
        branch.put("interactions", new ArrayList<>());

        JsonNode sixty = store.node("sixtyPillars", pill.get(0), pill.get(1));
        String stream = sixty.get("stream").asText();
        List<Object> voidStars = new ArrayList<>();
        JsonNode v = store.node("streams", stream, "void");
        if (v != null) v.forEach(n -> voidStars.add(n.asText()));
        Map<String, Object> naYin = new LinkedHashMap<>();
        JsonNode ny = sixty.get("naYin");
        if (ny != null && ny.size() == 3) {
            naYin.put("element", ny.get(0).asText());
            naYin.put("name", ny.get(1).asText());
            naYin.put("chinese", ny.get(2).asText());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stem", stem);
        out.put("branch", branch);
        out.put("name", name);
        out.put("jiaGroup", stream);
        out.put("voidStars", voidStars);
        out.put("naYin", naYin);
        out.put("stage", null);
        out.put("interactions", new ArrayList<>());
        out.put("stars", new ArrayList<>());
        out.put("annualStars", new ArrayList<>());
        out.put("specialMatch", new ArrayList<>());
        return out;
    }

    private Map<String, Object> enrichedStem(String stemName, Map<String, String> stemToGod, Map<String, String> elementToAspect) {
        JsonNode s = stems().get(stemName);
        Map<String, Object> m = deepMap(s);
        String element = s.get("element").asText();
        String god = stemToGod.get(stemName);
        String aspect = elementToAspect.get(element);
        m.put("god", god);
        m.put("aspect", aspect);
        m.put("gdd", displayGod(god));
        m.put("aspc", displayAspect(aspect));
        m.put("elm", deepMap(store.node("elements").get(element)));
        return m;
    }

    private Map<String, Object> displayGod(String code) {
        return code == null ? new LinkedHashMap<>() : deepMap(store.node("display", "gods", code));
    }

    private Map<String, Object> displayAspect(String name) {
        return name == null ? new LinkedHashMap<>() : deepMap(store.node("display", "aspects", name));
    }

    /** -------- EightWords extension layer -------- */

    /** EightWords.extendAuxDataChart — adds stars/annualStars/interactions/stage/naYin
     *  to the year/month/day/hour pillars. `root` defaults to the chart itself. */
    public void extendAuxDataChart(Map<String, Object> chart, Map<String, Object> root) {
        Map<String, Object> src = root != null ? root : chart;
        List<String> keys = new ArrayList<>(List.of("year", "month", "day", "hour"));
        if (Boolean.TRUE.equals(castMap(chart.get("info")).get("hourUnknown"))) keys.remove("hour");
        Instant now = Instant.now();
        // RN sorts `ShenSha.annualStarAux['zi'][branch]` in place, once per
        // pillar. A chart with the same branch in two pillars therefore sorts
        // the same array twice, which toggles the order of equal ratings
        // (the comparator never returns 0). Keep the per-chart state so the
        // nth occurrence of a branch applies the comparator n times.
        Map<String, List<Map<String, Object>>> annualState = new LinkedHashMap<>();
        for (String key : keys) {
            Map<String, Object> pillar = pillarOf(chart, key);
            if (pillar == null) continue;
            List<String> others = new ArrayList<>(keys);
            others.remove(key);
            extendAuxData(pillar, now, src, others, annualState);
        }
        // All pillars with the same branch share one annual-star array in RN
        // (ShenSha.annualStarAux.zi[branch]); the last sort wins for every
        // reference, so re-point each pillar at the final state.
        for (String key : keys) {
            Map<String, Object> pillar = pillarOf(chart, key);
            if (pillar == null) continue;
            String branch = String.valueOf(castMap(pillar.get("branch")).get("name"));
            List<Map<String, Object>> annual = annualState.get(branch);
            if (annual != null) pillar.put("annualStars", annual);
        }
    }

    public void extendAuxData(Map<String, Object> pillar, Instant now, Map<String, Object> root, List<String> pillarKeys) {
        extendAuxData(pillar, now, root, pillarKeys, new LinkedHashMap<>());
    }

    /** EightWords.extendAuxData — also called directly by the synastry view,
     *  which extends chart2's pillars against the full list of chart1 pillars.
     *  @param annualState per-chart sorted annual-star lists keyed by branch
     *         (the RN app sorts ShenSha.annualStarAux in place, once per pillar) */
    public void extendAuxData(Map<String, Object> pillar, Instant now, Map<String, Object> root, List<String> pillarKeys,
                              Map<String, List<Map<String, Object>>> annualState) {
        Map<String, Object> stem = castMap(pillar.get("stem"));
        Map<String, Object> branch = castMap(pillar.get("branch"));
        String stemName = String.valueOf(stem.get("name"));
        String branchName = String.valueOf(branch.get("name"));

        List<Map<String, Object>> branchInteractions = getBranchInteractions(root, branchName, now, pillarKeys);
        branchInteractions.addAll(getSpecialInteractions(root, branchName, now, pillarKeys, pillar));
        branchInteractions.sort((a, b) -> orderingIndex(String.valueOf(a.get("relation")))
                - orderingIndex(String.valueOf(b.get("relation"))));
        branch.put("interactions", branchInteractions);
        stem.put("interactions", getStemInteractions(root, stemName, now, pillarKeys));
        pillar.put("stars", sortByRatingDesc(shensha.starsFromPillar(root, pillar)));
        // RN: `annualStarAux['zi'][branch].sort((a, b) => (a.rating < b.rating) ? 1 : -1)`
        // The resolved stars carry `rating`, so this is the same never-equal
        // comparator as `stars` (rating desc, ties reversed). The sort mutates
        // the shared table in RN, so repeated branches alternate tie order.
        List<Map<String, Object>> annual = annualState.get(branchName);
        if (annual == null) annual = shensha.annualStarsForPillar(branchName);
        pillar.put("annualStars", sortByRatingDesc(annual));
        annualState.put(branchName, castListOfMaps(pillar.get("annualStars")));
        pillar.put("specialMatch", new ArrayList<>());
        pillar.put("interactions", getPillarInteractions(root, pillar, pillarKeys));

        Map<String, Object> stage = new LinkedHashMap<>();
        stage.put("pillar", stageFor(stemName, branchName));
        Map<String, Object> day = pillarOf(root, "day");
        stage.put("dm", day == null ? null : stageFor(String.valueOf(castMap(day.get("stem")).get("name")), branchName));
        pillar.put("stage", stage);
    }

    private int orderingIndex(String relation) {
        JsonNode arr = store.node("branchInteractionOrdering");
        for (int i = 0; i < arr.size(); i++) if (arr.get(i).asText().equals(relation)) return i;
        return -1;
    }

    private Map<String, Object> stageFor(String stemName, String branchName) {
        JsonNode stages = store.node("stems", stemName, "twelveStages");
        if (stages == null) return null;
        for (int i = 0; i < stages.size(); i++)
            if (stages.get(i).asText().equals(branchName)) return deepMap(store.node("twelveStagesData").get(i));
        return null;
    }

    private static List<Map<String, Object>> sortByRatingDesc(List<Map<String, Object>> list) {
        // EightWords uses `.sort((a, b) => (a.rating < b.rating) ? 1 : -1)`, a
        // comparator that never returns 0. V8 therefore sorts by rating
        // descending AND reverses items with equal ratings — stable-sorting the
        // reversed list reproduces that exactly.
        List<Map<String, Object>> reversed = new ArrayList<>(list);
        Collections.reverse(reversed);
        reversed.sort((a, b) -> Double.compare(ratingOf(b), ratingOf(a)));
        return reversed;
    }

    private static double ratingOf(Map<String, Object> e) {
        Object r = e.get("rating");
        return r instanceof Number ? ((Number) r).doubleValue() : 0;
    }

    /** EightWords.getCurrentLuckPillar — returns the luck pillar covering `point`. */
    public Map<String, Object> currentLuckPillar(Map<String, Object> chart, Instant point) {
        List<Map<String, Object>> lucks = castListOfMaps(chart.get("luck"));
        if (lucks.isEmpty()) return null;
        Map<String, Object> found = null;
        for (int i = 0; i < lucks.size(); i++) {
            Instant ti = luckInstant(lucks.get(i));
            if (ti == null || point.isBefore(ti)) continue;
            if (i + 1 < lucks.size()) {
                Instant tn = luckInstant(lucks.get(i + 1));
                if (tn == null || point.isBefore(tn)) found = lucks.get(i);
            } else {
                found = lucks.get(i);
            }
        }
        return found != null ? found : lucks.get(0);
    }

    private static Instant luckInstant(Map<String, Object> luck) {
        Object t = luck.get("time");
        return t == null ? null : parseInstant(String.valueOf(t));
    }

    private static Instant parseInstant(String iso) {
        try {
            return OffsetDateTime.parse(iso).toInstant();
        } catch (Exception e1) {
            try {
                return LocalDateTime.parse(iso).atZone(ZoneId.systemDefault()).toInstant();
            } catch (Exception e2) {
                return null;
            }
        }
    }

    private boolean timeDiffers(Map<String, Object> root, Instant now) {
        Object t = castMap(root.get("info")).get("time");
        if (t == null) return true;
        Instant chartInstant = parseInstant(String.valueOf(t));
        return chartInstant == null || !chartInstant.equals(now);
    }

    /** EightWords.getPillarInteractions (Fu Yin / Fan Yin / Pillar Combo) */
    public List<Map<String, Object>> getPillarInteractions(Map<String, Object> root, Map<String, Object> pillar, List<String> pillarKeys) {
        List<Map<String, Object>> results = new ArrayList<>();
        String s2 = String.valueOf(castMap(pillar.get("stem")).get("name"));
        String b2 = String.valueOf(castMap(pillar.get("branch")).get("name"));
        for (String key : pillarKeys) {
            Map<String, Object> p1 = pillarOf(root, key);
            if (p1 == null) continue;
            String s1 = String.valueOf(castMap(p1.get("stem")).get("name"));
            String b1 = String.valueOf(castMap(p1.get("branch")).get("name"));
            if (s1.equals(s2) && b1.equals(b2)) results.add(pillarInteraction("Fu Yin", "", key, s1, b1));
            String fan = fanyinOf(s1, b1, s2, b2);
            if (fan != null) results.add(pillarInteraction("Fan Yin", fan, key, s1, b1));
            if (stems().get(s2).get("combine").asText().equals(s1)
                    && branches().get(b2).get("combine").asText().equals(b1))
                results.add(pillarInteraction("Pillar Combo", "", key, s1, b1));
        }
        return results;
    }

    private Map<String, Object> pillarInteraction(String relation, String type, String pillar, String stem, String branch) {
        Map<String, Object> affect = new LinkedHashMap<>();
        affect.put("pillar", pillar);
        affect.put("stem", stem);
        affect.put("branch", branch);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("relation", relation);
        m.put("type", type);
        m.put("affects", List.of(affect));
        return m;
    }

    /** EightWords.getStemInteractions */
    public List<Map<String, Object>> getStemInteractions(Map<String, Object> root, String stemName, Instant point, List<String> pillarKeys) {
        List<Map<String, Object>> results = new ArrayList<>();
        Map<String, Object> luck = currentLuckPillar(root, point);
        Map<String, String> map = stemToGodOf(root);
        JsonNode stem = stems().get(stemName);
        List<String> clashes = new ArrayList<>();
        stem.get("clashes").forEach(n -> clashes.add(n.asText()));
        String combine = stem.get("combine").asText();
        String combineResult = stem.get("combineResult").asText();
        for (String key : pillarKeys) {
            String other = chartStemName(root, luck, key);
            if (other == null) continue;
            if (clashes.contains(other)) {
                Map<String, Object> affect = new LinkedHashMap<>();
                affect.put("pillar", key);
                affect.put("stem", other);
                affect.put("god", map.get(other));
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("relation", "conflict");
                m.put("affects", List.of(affect));
                results.add(m);
            }
            if (combine.equals(other)) {
                Map<String, Object> affect = new LinkedHashMap<>();
                affect.put("pillar", key);
                affect.put("stem", other);
                affect.put("god", map.get(other));
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("relation", "combo");
                m.put("relationElementResult", combineResult);
                m.put("affects", List.of(affect));
                results.add(m);
            }
        }
        return results;
    }

    /** EightWords.getBranchInteractions */
    public List<Map<String, Object>> getBranchInteractions(Map<String, Object> root, String branchName, Instant point, List<String> pillarKeys) {
        List<Map<String, Object>> results = new ArrayList<>();
        Map<String, Object> luck = currentLuckPillar(root, point);
        Map<String, String> map = stemToGodOf(root);
        JsonNode branch = branches().get(branchName);
        JsonNode relations = branch.get("relations");
        for (String key : pillarKeys) {
            String other = chartBranchName(root, luck, key);
            if (other == null) continue;
            JsonNode rels = relations == null ? null : relations.get(other);
            if (rels == null) continue;
            for (JsonNode nameNode : rels) {
                String name = nameNode.asText();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("relation", name);
                if (name.equals("6 combo")) {
                    m.put("relationElementResult", branch.get("combineResult").asText());
                } else if (name.equals("hidden combo")) {
                    JsonNode hcr = branch.get("hiddenCombineResult");
                    m.put("relationElementResult", hcr == null || hcr.isNull() ? null : hcr.asText());
                }
                m.put("affects", List.of(branchAffect(key, other, map)));
                results.add(m);
            }
        }
        return results;
    }

    /** EightWords.getSpecialInteractions (harmony / direction / penalties) */
    public List<Map<String, Object>> getSpecialInteractions(Map<String, Object> root, String branchName, Instant point,
                                                            List<String> pillarKeysOrig, Map<String, Object> pillar) {
        Map<String, Object> luck = currentLuckPillar(root, point);
        Map<String, String> map = stemToGodOf(root);
        List<String> pillarKeys = new ArrayList<>(pillarKeysOrig);
        boolean isLuck = "luck".equals(pillar.get("name"));
        // add the luck pillar to the comparison when the charts differ
        if (!isLuck && luck != null && timeDiffers(root, point)) pillarKeys.add(0, "luck");

        List<String> pillarBranches = new ArrayList<>();
        for (String key : pillarKeys) {
            if (key.equals("luck")) {
                pillarBranches.add(0, String.valueOf(castMap(castMap(luck.get("pillar")).get("branch")).get("name")));
            } else {
                Map<String, Object> p = pillarOf(root, key);
                pillarBranches.add(p == null ? null : String.valueOf(castMap(p.get("branch")).get("name")));
            }
        }

        List<Map<String, Object>> results = new ArrayList<>();
        store.node("branchSpecialRelations").fields().forEachRemaining(titleE -> {
            String title = titleE.getKey();
            titleE.getValue().fields().forEachRemaining(frameE -> {
                List<String> frame = new ArrayList<>();
                frameE.getValue().forEach(n -> frame.add(n.asText()));
                if (!frame.contains(branchName)) return;
                List<String> intersection = frame.stream()
                        .filter(v -> pillarBranches.contains(v) || v.equals(branchName)).toList();
                boolean needsThree = title.equals("direction") || title.equals("penalty");
                if (intersection.size() < 2) return;
                if (intersection.size() == 2 && needsThree) return; // doesn't count, must need 3
                List<String> withoutBranch = intersection.stream().filter(v -> !v.equals(branchName)).toList();
                String relation = title.equals("harmony")
                        ? (intersection.size() == 3 ? "harmony" : "⅔ harmony")
                        : title.equals("penalty") ? frameE.getKey() + " penalty" : title;
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("relation", relation);
                data.put("relationElementResult", title.equals("penalty") ? null : frameE.getKey());
                List<Map<String, Object>> affects = new ArrayList<>();
                for (String br : withoutBranch) {
                    for (int idx = 0; idx < pillarBranches.size(); idx++) {
                        if (br.equals(pillarBranches.get(idx))) {
                            Map<String, Object> a = new LinkedHashMap<>();
                            a.put("pillar", pillarKeys.get(idx));
                            a.put("branch", br);
                            a.put("gods", hiddenGods(br, map));
                            affects.add(a);
                        }
                    }
                }
                data.put("affects", affects);
                results.add(data);
            });
        });
        return results;
    }

    private Map<String, Object> branchAffect(String pillarKey, String branchName, Map<String, String> map) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("pillar", pillarKey);
        a.put("branch", branchName);
        a.put("gods", hiddenGods(branchName, map));
        return a;
    }

    private List<Object> hiddenGods(String branchName, Map<String, String> map) {
        List<Object> gods = new ArrayList<>();
        for (JsonNode n : branches().get(branchName).get("_hiddenStemsArr"))
            gods.add(n.isNull() ? null : map.get(n.asText()));
        return gods;
    }

    private String chartStemName(Map<String, Object> root, Map<String, Object> luck, String key) {
        if (key.equals("luck")) {
            return luck == null ? null : String.valueOf(castMap(castMap(luck.get("pillar")).get("stem")).get("name"));
        }
        Map<String, Object> p = pillarOf(root, key);
        return p == null ? null : String.valueOf(castMap(p.get("stem")).get("name"));
    }

    private String chartBranchName(Map<String, Object> root, Map<String, Object> luck, String key) {
        if (key.equals("luck")) {
            return luck == null ? null : String.valueOf(castMap(castMap(luck.get("pillar")).get("branch")).get("name"));
        }
        Map<String, Object> p = pillarOf(root, key);
        return p == null ? null : String.valueOf(castMap(p.get("branch")).get("name"));
    }

    private Map<String, String> stemToGodOf(Map<String, Object> root) {
        Map<String, Object> day = pillarOf(root, "day");
        String dm = day == null ? null : String.valueOf(castMap(day.get("stem")).get("name"));
        Map<String, String> m = new LinkedHashMap<>();
        JsonNode node = dm == null ? null : dms().get(dm);
        if (node != null) node.get("stemsToGods").fields().forEachRemaining(e -> m.put(e.getKey(), e.getValue().asText()));
        return m;
    }

    private String fanyinOf(String s1, String b1, String s2, String b2) {
        JsonNode p1 = store.node("sixtyPillars", s1, b1);
        if (p1 != null) {
            JsonNode host = p1.get("hostFanYin");
            JsonNode guest = p1.get("guestFanYin");
            if (host != null && host.size() == 2 && host.get(0).asText().equals(s2) && host.get(1).asText().equals(b2)) return "host";
            if (guest != null && guest.size() == 2 && guest.get(0).asText().equals(s2) && guest.get(1).asText().equals(b2)) return "guest";
        }
        return null;
    }

    /** EightWords.getSummary — writes chart.meta.summary */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getSummary(Map<String, Object> chart, double line) {
        Map<String, Object> meta = castMap(chart.get("meta"));
        if (!(meta.get("weights") instanceof Map)) return chart;
        Map<String, Object> weights = (Map<String, Object>) meta.get("weights");
        Map<String, Number> ratios = castMap(weights.get("ratio")).get("gods") instanceof Map
                ? (Map<String, Number>) castMap(weights.get("ratio")).get("gods") : new LinkedHashMap<>();
        Map<String, Number> elementRatios = castMap(weights.get("ratio")).get("elements") instanceof Map
                ? (Map<String, Number>) castMap(weights.get("ratio")).get("elements") : new LinkedHashMap<>();
        Map<String, Number> rawElements = castMap(weights.get("raw")).get("elements") instanceof Map
                ? (Map<String, Number>) castMap(weights.get("raw")).get("elements") : new LinkedHashMap<>();
        Map<String, Number> rawStems = castMap(weights.get("raw")).get("stems") instanceof Map
                ? (Map<String, Number>) castMap(weights.get("raw")).get("stems") : new LinkedHashMap<>();

        Map<String, List<String>> aspects = new LinkedHashMap<>();
        for (String a : List.of("resource", "companion", "output", "wealth", "power")) aspects.put(a, new ArrayList<>());
        compareAspect(aspects, ratios, "resource", "dr", "ir", line);
        compareAspect(aspects, ratios, "companion", "rw", "f", line);
        compareAspect(aspects, ratios, "output", "ho", "eg", line);
        compareAspect(aspects, ratios, "wealth", "dw", "iw", line);
        compareAspect(aspects, ratios, "power", "do", "7k", line);

        // orderedElements = _.mapValues(_.invert(_.invert(raw.elements)), parseInt)
        // The RN code never sorts; it relies on JS object property ordering:
        // array-index keys (canonical integers 0..2^32-2) enumerate first in
        // ascending order, then other keys in insertion order. So integer-valued
        // raw scores get sorted by strength while fractional ones do not.
        TreeMap<Long, String> intKeyed = new TreeMap<>();
        LinkedHashMap<String, String> stringKeyed = new LinkedHashMap<>();
        rawElements.forEach((element, value) -> {
            String key = numberKey(value);
            Long index = jsArrayIndex(key);
            if (index != null) intKeyed.put(index, element);   // last assignment wins, like JS
            else stringKeyed.put(key, element);                 // overwrite keeps first position
        });
        LinkedHashMap<String, Long> orderedElements = new LinkedHashMap<>();
        intKeyed.forEach((index, element) -> orderedElements.put(element, index));
        stringKeyed.forEach((key, element) -> orderedElements.put(element, jsParseInt(key)));
        List<String> ordered = new ArrayList<>(orderedElements.keySet());
        String weakElement = ordered.isEmpty() ? null : ordered.get(0);
        String strongElement = ordered.isEmpty() ? null : ordered.get(ordered.size() - 1);

        List<String> weakestStems = new ArrayList<>(elementStems(weakElement));
        weakestStems.sort((a, b) -> numberValue(rawStems, a) > numberValue(rawStems, b) ? 1 : -1);
        List<String> strongestStems = new ArrayList<>(elementStems(strongElement));
        strongestStems.sort((a, b) -> numberValue(rawStems, a) < numberValue(rawStems, b) ? 1 : -1);

        Map<String, String> aspectMap = new LinkedHashMap<>();
        Map<String, Object> maps = castMap(meta.get("maps"));
        castMap(maps.get("elementToAspect"))
                .forEach((k, v) -> aspectMap.put(String.valueOf(k), String.valueOf(v)));

        Map<String, Object> stemStrength = new LinkedHashMap<>();
        stemStrength.put("weak", weakestStems.isEmpty() ? null : weakestStems.get(0));
        stemStrength.put("strong", strongestStems.isEmpty() ? null : strongestStems.get(0));
        Map<String, Object> elementStrength = new LinkedHashMap<>();
        elementStrength.put("weak", weakElement);
        elementStrength.put("strong", strongElement);
        Map<String, Object> aspectStrength = new LinkedHashMap<>();
        aspectStrength.put("weak", aspectMap.get(weakElement));
        aspectStrength.put("strong", aspectMap.get(strongElement));
        Map<String, Object> ratioNum = new LinkedHashMap<>();
        ratioNum.put("strong", elementRatios.get(strongElement));
        ratioNum.put("weak", elementRatios.get(weakElement));

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("stemStrength", stemStrength);
        summary.put("elementStrength", elementStrength);
        summary.put("aspectStrength", aspectStrength);
        summary.put("aspectList", aspects);
        summary.put("ratioNum", ratioNum);
        meta.put("summary", summary);
        return chart;
    }

    @SuppressWarnings("unchecked")
    private void compareAspect(Map<String, List<String>> aspects, Map<String, Number> ratios,
                               String aspect, String first, String second, double line) {
        Number rf = ratios.get(first), rs = ratios.get(second);
        boolean truthyFirst = rf != null && rf.doubleValue() != 0;
        boolean truthySecond = rs != null && rs.doubleValue() != 0;
        if (!truthyFirst && !truthySecond) return;
        double f = rf == null ? Double.NaN : rf.doubleValue();
        double s = rs == null ? Double.NaN : rs.doubleValue();
        List<String> out = aspects.get(aspect);
        out.clear();
        if (f > s) {
            if ((f - s) / f > line) out.add(first);
            else { out.add(first); out.add(second); }
        } else {
            if ((s - f) / s > line) out.add(second);
            else { out.add(second); out.add(first); }
        }
    }

    private static String numberKey(Number v) {
        double d = v.doubleValue();
        if (!Double.isInfinite(d) && !Double.isNaN(d) && d == Math.rint(d)) return String.valueOf((long) d);
        return String.valueOf(d);
    }

    /** ECMAScript array-index key: canonical non-negative integer string < 2^32-1. */
    private static Long jsArrayIndex(String key) {
        if (!key.matches("0|[1-9][0-9]*")) return null;
        try {
            long n = Long.parseLong(key);
            return n <= 4294967294L ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** JS parseInt(numericString) — truncates toward zero. */
    private static long jsParseInt(String key) {
        try {
            return (long) Double.parseDouble(key);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double numberValue(Map<String, Number> m, String k) {
        Number n = m.get(k);
        return n == null ? Double.NaN : n.doubleValue();
    }

    private List<String> elementStems(String element) {
        List<String> out = new ArrayList<>();
        JsonNode e = element == null ? null : store.node("elements", element, "stems");
        if (e != null) e.forEach(n -> out.add(n.asText()));
        return out;
    }

    /** -------- counts/weights/stats/favorable gods (Process.js) -------- */
    @SuppressWarnings("unchecked")
    private void calcCounts(Map<String, Object> chart) {
        // port of Process.calcCounts
        Map<String, Object> pillars = (Map<String, Object>) chart.get("pillars");
        Map<String, String> gods = (Map<String, String>) ((Map<String, Object>) ((Map<String, Object>) chart.get("meta")).get("maps")).get("stemToGod");
        Map<String, Integer> stemCounts = zeroMap(STEMS), godCounts = new LinkedHashMap<>(), elemCounts = new LinkedHashMap<>();
        Map<String, Integer> stemCore = zeroMap(STEMS), godCore = new LinkedHashMap<>(), elemCore = new LinkedHashMap<>();
        for (String g : List.of("ir", "dr", "f", "rw", "eg", "ho", "iw", "dw", "7k", "do")) { godCounts.put(g, 0); godCore.put(g, 0); }
        for (String e : List.of("wood", "fire", "earth", "metal", "water")) { elemCounts.put(e, 0); elemCore.put(e, 0); }

        List<JsonNode> stemNodes = new ArrayList<>();
        List<Map<String, Object>> branchMaps = new ArrayList<>();
        for (String key : List.of("year", "month")) {
            Map<String, Object> p = (Map<String, Object>) pillars.get(key);
            JsonNode sn = stems().get(((Map<String, Object>) p.get("stem")).get("name").toString());
            stemNodes.add(sn);
            branchMaps.add((Map<String, Object>) p.get("branch"));
        }
        {
            Map<String, Object> p = (Map<String, Object>) pillars.get("day");
            branchMaps.add((Map<String, Object>) p.get("branch"));
        }
        if (pillars.get("hour") != null) {
            Map<String, Object> p = (Map<String, Object>) pillars.get("hour");
            JsonNode sn = stems().get(((Map<String, Object>) p.get("stem")).get("name").toString());
            stemNodes.add(sn);
            branchMaps.add((Map<String, Object>) p.get("branch"));
        }
        for (JsonNode sn : stemNodes) {
            String n = sn.get("name").asText();
            incr(stemCounts, n); incr(godCounts, gods.get(n)); incr(elemCounts, sn.get("element").asText());
            incr(stemCore, n); incr(godCore, gods.get(n)); incr(elemCore, sn.get("element").asText());
        }
        for (Map<String, Object> b : branchMaps) {
            List<Map<String, Object>> hs = (List<Map<String, Object>>) b.get("hiddenStems");
            for (int idx = 0; idx < hs.size(); idx++) {
                String s = (String) hs.get(idx).get("stem");
                if (s == null) continue;
                String el = stems().get(s).get("element").asText();
                incr(stemCounts, s); incr(godCounts, gods.get(s)); incr(elemCounts, el);
                if (idx == 1) { incr(stemCore, s); incr(godCore, gods.get(s)); incr(elemCore, el); }
            }
        }
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("gods", godCounts); counts.put("elements", elemCounts); counts.put("stems", stemCounts);
        counts.put("godsCore", godCore); counts.put("elementsCore", elemCore); counts.put("stemsCore", stemCore);
        ((Map<String, Object>) chart.get("meta")).put("counts", counts);
    }

    /** JS `reduce((a,b) => arr[a] > arr[b] ? a : b)` — ties resolve to the LAST key in iteration order */
    private <V extends Number> String lastMaxKey(Map<String, V> m) {
        String cur = null;
        double curVal = Double.NEGATIVE_INFINITY;
        for (Map.Entry<String, V> e : m.entrySet()) {
            if (cur == null || e.getValue().doubleValue() > curVal) { cur = e.getKey(); curVal = e.getValue().doubleValue(); }
            else if (e.getValue().doubleValue() == curVal) { cur = e.getKey(); }
        }
        return cur;
    }

    private Map<String, Integer> zeroMap(List<String> keys) {
        Map<String, Integer> m = new LinkedHashMap<>();
        keys.forEach(k -> m.put(k, 0));
        return m;
    }
    private void incr(Map<String, Integer> m, String k) { m.put(k, m.get(k) + 1); }

    @SuppressWarnings("unchecked")
    private void calcWeights(Map<String, Object> chart, boolean weightsIncludeCombos) {
        Map<String, Object> pillars = (Map<String, Object>) chart.get("pillars");
        JsonNode stemWeights = store.node("scores", "stemWeights");
        JsonNode branchMultipliers = store.node("scores", "branchMultipliers");
        String mBranch = nameOf(pillars, "month", "branch");

        List<String> stems = new ArrayList<>(List.of(nameOf(pillars, "year", "stem"), nameOf(pillars, "month", "stem"), nameOf(pillars, "day", "stem")));
        List<String> brs = new ArrayList<>(List.of(nameOf(pillars, "year", "branch"), nameOf(pillars, "month", "branch"), nameOf(pillars, "day", "branch")));
        if (pillars.get("hour") != null) { stems.add(nameOf(pillars, "hour", "stem")); brs.add(nameOf(pillars, "hour", "branch")); }

        Map<String, Double> raw = new LinkedHashMap<>();
        STEMS.forEach(s -> raw.put(s, 0.0));
        for (String s : stems) raw.merge(s, stemWeights.get(s).get(mBranch).asDouble() * 0.6, Double::sum);
        for (String b : brs) {
            JsonNode mults = branchMultipliers.get(b);
            if (mults == null) continue;
            mults.fields().forEachRemaining(e -> raw.merge(e.getKey(), stemWeights.get(e.getKey()).get(mBranch).asDouble() * e.getValue().asDouble(), Double::sum));
        }

        Map<String, Double> rawCombo = calcCombineWeights(pillars, raw);
        Map<String, Object> weightsPlain = rawToWeights(chart, raw);
        Map<String, Object> weightsCombo = rawToWeights(chart, rawCombo);
        // JS: processed.meta.weights = info.weightsIncludeCombos ? weightsCombo : weightsPlain
        Map<String, Object> weights = weightsIncludeCombos ? weightsCombo : weightsPlain;
        Map<String, Object> meta = (Map<String, Object>) chart.get("meta");
        meta.put("weightsPlain", weightsPlain);
        meta.put("weightsCombo", weightsCombo);
        meta.put("weights", weights);
    }

    private String nameOf(Map<String, Object> pillars, String pillar, String part) {
        return (String) ((Map<String, Object>) ((Map<String, Object>) pillars.get(pillar)).get(part)).get("name");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Double> calcCombineWeights(Map<String, Object> pillars, Map<String, Double> raw) {
        List<String> stems = new ArrayList<>(), branches = new ArrayList<>();
        for (String p : List.of("year", "month", "day", "hour")) {
            Map<String, Object> pil = (Map<String, Object>) pillars.get(p);
            if (pil == null) continue;
            stems.add(nameOf(pillars, p, "stem"));
            branches.add(nameOf(pillars, p, "branch"));
        }
        String mBranch = nameOf(pillars, "month", "branch");
        JsonNode branchSpecial = store.node("branchSpecialRelations");
        JsonNode comboMult = store.node("scores", "comboMultiplier");
        JsonNode stemWeights = store.node("scores", "stemWeights");

        List<Map<String, Object>> sCombos = new ArrayList<>(), bCombos = new ArrayList<>(), dirCombos = new ArrayList<>(), harCombos = new ArrayList<>();
        Set<Integer> usedStems = new HashSet<>(), usedBranches = new HashSet<>();
        for (int x = 0; x < stems.size(); x++)
            for (int y = x + 1; y < stems.size(); y++)
                if (stems().get(stems.get(x)).get("combine").asText().equals(stems.get(y))
                        && !usedStems.contains(x) && !usedStems.contains(y) && y - x == 1) {
                    sCombos.add(new LinkedHashMap<>(Map.of("indexes", List.of(x, y), "element", stems().get(stems.get(x)).get("combineResult").asText())));
                    usedStems.add(x); usedStems.add(y);
                }
        for (int x = 0; x < branches.size(); x++)
            for (int y = x + 1; y < branches.size(); y++)
                if (branches().get(branches.get(x)).get("combine").asText().equals(branches.get(y))
                        && !usedBranches.contains(x) && !usedBranches.contains(y) && y - x == 1) {
                    bCombos.add(new LinkedHashMap<>(Map.of("indexes", List.of(x, y), "element", branches().get(branches.get(x)).get("combineResult").asText())));
                    usedBranches.add(x); usedBranches.add(y);
                }
        branchSpecial.get("direction").fields().forEachRemaining(e -> {
            List<String> frame = new ArrayList<>();
            e.getValue().forEach(n -> frame.add(n.asText()));
            List<String> inter = intersection(frame, branches);
            if (inter.size() == 3) dirCombos.add(new LinkedHashMap<>(Map.of("indexes", inter.stream().map(branches::indexOf).toList(), "element", e.getKey())));
        });
        branchSpecial.get("harmony").fields().forEachRemaining(e -> {
            List<String> frame = new ArrayList<>();
            e.getValue().forEach(n -> frame.add(n.asText()));
            List<String> inter = intersection(frame, branches);
            if (inter.size() >= 2) harCombos.add(new LinkedHashMap<>(Map.of("indexes", inter.stream().map(branches::indexOf).toList(), "element", e.getKey())));
        });

        List<Map<String, Object>> added = new ArrayList<>();
        sCombos.forEach(c -> added.add(new LinkedHashMap<>(Map.of("axis", "stem", "type", "stem", "combo", c))));
        dirCombos.forEach(c -> added.add(new LinkedHashMap<>(Map.of("axis", "branch", "type", "direction", "combo", c))));
        harCombos.forEach(c -> {
            boolean exists = added.stream().anyMatch(i -> i.get("axis").equals("branch") && intersectIndexes((List<Integer>) ((Map<String, Object>) i.get("combo")).get("indexes"), (List<Integer>) c.get("indexes")));
            if (!exists) added.add(new LinkedHashMap<>(Map.of("axis", "branch", "type", ((List<Integer>) c.get("indexes")).size() == 3 ? "harmony" : "halfHarmony", "combo", c)));
        });
        bCombos.forEach(c -> {
            boolean exists = added.stream().anyMatch(i -> i.get("axis").equals("branch") && intersectIndexes((List<Integer>) ((Map<String, Object>) i.get("combo")).get("indexes"), (List<Integer>) c.get("indexes")));
            if (!exists) added.add(new LinkedHashMap<>(Map.of("axis", "branch", "type", "branchCombo", "combo", c)));
        });

        Map<String, Double> newRaw = new LinkedHashMap<>(raw);
        for (Map<String, Object> item : added) {
            String element = (String) ((Map<String, Object>) item.get("combo")).get("element");
            List<String> elementStems = perElementStems(element);
            String mBranchPolarity = branches().get(mBranch).get("polarity").asText();
            if (item.get("axis").equals("stem")) {
                String newStem = elementStems.get(0);
                newRaw.merge(newStem, comboMult.get("stem").asDouble() * stemWeights.get(newStem).get(mBranch).asDouble(), Double::sum);
            } else {
                String newStem = mBranchPolarity.equals("yang") ? elementStems.get(0) : elementStems.get(1);
                newRaw.merge(newStem, comboMult.get(item.get("type").toString()).asDouble() * stemWeights.get(newStem).get(mBranch).asDouble(), Double::sum);
            }
        }
        newRaw.replaceAll((k, v) -> (double) Math.round(v));
        return newRaw;
    }

    private boolean intersectIndexes(List<Integer> a, List<Integer> b) {
        return a.stream().anyMatch(b::contains);
    }
    private List<String> intersection(List<String> a, List<String> b) {
        List<String> r = new ArrayList<>(a);
        r.retainAll(b);
        return r;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> rawToWeights(Map<String, Object> chart, Map<String, Double> raw) {
        Map<String, Object> meta = (Map<String, Object>) chart.get("meta");
        Map<String, String> stemToGod = (Map<String, String>) ((Map<String, Object>) meta.get("maps")).get("stemToGod");
        double total = raw.values().stream().mapToDouble(Double::doubleValue).sum();
        Map<String, Integer> ratio = new LinkedHashMap<>();
        raw.forEach((k, v) -> ratio.put(k, total == 0 ? 0 : (int) Math.round(v / total * 100)));
        Map<String, Integer> godsRatio = new LinkedHashMap<>();
        Map<String, Integer> elemRatio = new LinkedHashMap<>(), aspectRatio = new LinkedHashMap<>();
        Map<String, Double> godsRaw = new LinkedHashMap<>();
        Map<String, Double> elemRaw = new LinkedHashMap<>(), aspectRaw = new LinkedHashMap<>();
        Map<String, List<String>> aspectToStems = (Map<String, List<String>>) ((Map<String, Object>) meta.get("maps")).get("aspectToStems");
        ratio.forEach((k, v) -> { godsRatio.put(stemToGod.get(k), v); godsRaw.put(stemToGod.get(k), raw.get(k)); });
        List.of("wood", "fire", "earth", "metal", "water").forEach(el -> {
            elemRatio.put(el, 0); elemRaw.put(el, 0.0);
            perElementStems(el).forEach(st -> {
                if (ratio.containsKey(st)) elemRatio.merge(el, ratio.get(st), Integer::sum);
                if (raw.containsKey(st)) elemRaw.merge(el, raw.get(st), Double::sum);
            });
        });
        aspectToStems.forEach((aspect, pair) -> {
            aspectRatio.put(aspect, 0); aspectRaw.put(aspect, 0.0);
            pair.forEach(st -> {
                if (ratio.containsKey(st)) aspectRatio.merge(aspect, ratio.get(st), Integer::sum);
                if (raw.containsKey(st)) aspectRaw.merge(aspect, raw.get(st), Double::sum);
            });
        });
        Map<String, Object> ratioMap = new LinkedHashMap<>(Map.of("gods", godsRatio, "stems", ratio, "elements", elemRatio, "aspects", aspectRatio));
        Map<String, Object> rawMap = new LinkedHashMap<>(Map.of("gods", godsRaw, "stems", raw, "elements", elemRaw, "aspects", aspectRaw));
        return new LinkedHashMap<>(Map.of("ratio", ratioMap, "raw", rawMap));
    }
    private List<String> perElementStems(String element) {
        List<String> out = new ArrayList<>();
        STEMS.forEach(st -> { if (stems().get(st).get("element").asText().equals(element)) out.add(st); });
        return out;
    }

    @SuppressWarnings("unchecked")
    private void calcStats(Map<String, Object> chart) {
        Map<String, Object> meta = (Map<String, Object>) chart.get("meta");
        Map<String, Object> maps = (Map<String, Object>) meta.get("maps");
        Map<String, List<String>> aspectToStems = (Map<String, List<String>>) maps.get("aspectToStems");
        Map<String, Object> weights = (Map<String, Object>) meta.get("weights");
        Map<String, Integer> ratioStems = (Map<String, Integer>) ((Map<String, Object>) weights.get("ratio")).get("stems");
        Map<String, Integer> ratioElements = (Map<String, Integer>) ((Map<String, Object>) weights.get("ratio")).get("elements");
        Map<String, Object> raw = (Map<String, Object>) weights.get("raw");
        Map<String, Double> rawAspects = (Map<String, Double>) raw.get("aspects");
        Map<String, Double> rawGods = (Map<String, Double>) raw.get("gods");

        List<String> supporters = new ArrayList<>();
        supporters.addAll(aspectToStems.get("companion"));
        supporters.addAll(aspectToStems.get("resource"));
        int supportScore = 0;
        for (String s : supporters) if (ratioStems.containsKey(s)) supportScore += ratioStems.get(s);
        String strength;
        if (supportScore <= 10) strength = "extreme weak";
        else if (supportScore <= 45) strength = "weak";
        else if (supportScore <= 50) strength = "balanced weak";
        else if (supportScore <= 55) strength = "balanced strong";
        else if (supportScore <= 90) strength = "strong";
        else strength = "extreme strong";

        String temperature = null;
        if (ratioElements.getOrDefault("fire", 0) - ratioElements.getOrDefault("water", 0) >= 7) temperature = "hot";
        else if (ratioElements.getOrDefault("water", 0) - ratioElements.getOrDefault("fire", 0) >= 7) temperature = "cold";

        String structure = lastMaxKey(rawAspects);
        String profile = lastMaxKey(rawGods);

        int yin = 0, yang = 0;
        Map<String, Object> pillars = (Map<String, Object>) chart.get("pillars");
        for (String key : List.of("year", "month", "day", "hour")) {
            Map<String, Object> p = (Map<String, Object>) pillars.get(key);
            if (p == null) continue;
            JsonNode sn = stems().get((String) ((Map<String, Object>) p.get("stem")).get("name"));
            int number = sn.get("number").asInt();
            if (number % 2 == 1) yin++; else yang++;
        }
        String polarityDesc = yin > yang ? "yin" : yang > yin ? "yang" : "balanced";
        Map<String, Object> stats = new LinkedHashMap<>(Map.of("temperature", temperature == null ? "" : temperature,
                "supportScore", supportScore, "strength", strength, "structure", structure, "profile", profile,
                "polarity", Map.of("yinCount", yin, "yangCount", yang, "desc", polarityDesc)));
        if (temperature == null) stats.put("temperature", null); // RN leaves it null when neither hot nor cold
        meta.put("stats", stats);
    }

    @SuppressWarnings("unchecked")
    private void calcFavorableGods(Map<String, Object> chart) {
        Map<String, Object> meta = (Map<String, Object>) chart.get("meta");
        Map<String, Object> maps = (Map<String, Object>) meta.get("maps");
        Map<String, String> stemToGod = (Map<String, String>) maps.get("stemToGod");
        Map<String, String> godToStem = (Map<String, String>) maps.get("godToStem");
        Map<String, String> aspectToElement = (Map<String, String>) maps.get("aspectToElement");
        Map<String, Object> weights = (Map<String, Object>) meta.get("weights");
        Map<String, Double> rawStemsD = (Map<String, Double>) ((Map<String, Object>) weights.get("raw")).get("stems");
        Map<String, Integer> rawStems = new LinkedHashMap<>();
        rawStemsD.forEach((k, v) -> rawStems.put(k, v.intValue()));
        Map<String, Integer> rawGods = new LinkedHashMap<>();
        ((Map<String, Double>) ((Map<String, Object>) weights.get("raw")).get("gods")).forEach((k, v) -> rawGods.put(k, v.intValue()));
        Map<String, Integer> rawElements = new LinkedHashMap<>();
        ((Map<String, Double>) ((Map<String, Object>) weights.get("raw")).get("elements")).forEach((k, v) -> rawElements.put(k, v.intValue()));
        Map<String, Integer> ratioAspects = (Map<String, Integer>) ((Map<String, Object>) weights.get("ratio")).get("aspects");
        int supportScore = (int) ((Map<String, Object>) meta.get("stats")).get("supportScore");
        String temperature = (String) ((Map<String, Object>) meta.get("stats")).get("temperature");

        String arbitratingGod = null, regulatingGod = null, medicatingGod = null;
        List<String> topElements = new ArrayList<>(rawElements.keySet());
        topElements.sort((a, b) -> rawElements.get(b) - rawElements.get(a));
        topElements = topElements.subList(0, Math.min(2, topElements.size())).stream().sorted().toList();
        String key = topElements.size() == 2 ? topElements.get(0) + "-" + topElements.get(1) : "";
        Map<String, List<String>> arbitratingMap = Map.of(
                "earth-wood", List.of("bing", "ding"),
                "fire-metal", List.of("wu", "ji"),
                "earth-water", List.of("geng", "xin"),
                "metal-wood", List.of("ren", "gui"),
                "fire-water", List.of("jia", "yi"));
        if (arbitratingMap.containsKey(key)) {
            List<String> set = arbitratingMap.get(key);
            if (rawStems.getOrDefault(set.get(0), 0) > 0 || rawStems.getOrDefault(set.get(1), 0) > 0)
                // JS quirk to preserve: rawGods is keyed by stem→god lookup tables and `rawGods[set[0]]` is undefined there,
                // so this always resolves to stemToGod[set[1]]
                arbitratingGod = stemToGod.get(set.get(1));
        }
        if ("hot".equals(temperature)) {
            if (rawStems.getOrDefault("ren", 0) > 0 || rawStems.getOrDefault("gui", 0) > 0)
                regulatingGod = rawStems.getOrDefault("ren", 0) > rawStems.getOrDefault("gui", 0) ? stemToGod.get("ren") : stemToGod.get("gui");
        } else if ("cold".equals(temperature)) {
            if (rawStems.getOrDefault("bing", 0) > 0 || rawStems.getOrDefault("ding", 0) > 0)
                regulatingGod = rawStems.getOrDefault("bing", 0) > rawStems.getOrDefault("ding", 0) ? stemToGod.get("bing") : stemToGod.get("ding");
        }
        java.util.function.Function<List<List<String>>, String> fetchGod = sets -> {
            for (List<String> set : sets) {
                if (rawGods.getOrDefault(set.get(0), 0) > 0 || rawGods.getOrDefault(set.get(1), 0) > 0)
                    return rawGods.getOrDefault(set.get(0), 0) > rawGods.getOrDefault(set.get(1), 0) ? set.get(0) : set.get(1);
            }
            return null;
        };
        if (supportScore >= 50) {
            if (ratioAspects.getOrDefault("companion", 0) > ratioAspects.getOrDefault("resource", 0))
                medicatingGod = fetchGod.apply(List.of(List.of("do", "7k"), List.of("eg", "ho"), List.of("dw", "iw")));
            else
                medicatingGod = fetchGod.apply(List.of(List.of("dw", "iw"), List.of("eg", "ho")));
        } else {
            if (ratioAspects.getOrDefault("power", 0) > ratioAspects.getOrDefault("wealth", 0) && ratioAspects.getOrDefault("power", 0) > ratioAspects.getOrDefault("output", 0))
                medicatingGod = fetchGod.apply(List.of(List.of("dr", "ir"), List.of("f", "rw")));
            else if (ratioAspects.getOrDefault("wealth", 0) > ratioAspects.getOrDefault("power", 0) && ratioAspects.getOrDefault("wealth", 0) > ratioAspects.getOrDefault("output", 0))
                medicatingGod = fetchGod.apply(List.of(List.of("f", "rw"), List.of("dr", "ir")));
            else
                medicatingGod = fetchGod.apply(List.of(List.of("dr", "ir"), List.of("f", "rw")));
        }
        Map<String, Object> usefulGods = new LinkedHashMap<>();
        usefulGods.put("regulating", regulatingGod); // RN keeps null when no god qualifies
        usefulGods.put("medicating", medicatingGod);
        usefulGods.put("arbitrating", arbitratingGod);
        meta.put("usefulGods", usefulGods);

        List<String> favAspects, unfavAspects;
        if (supportScore >= 50) { favAspects = List.of("output", "wealth", "power"); unfavAspects = List.of("resource", "companion"); }
        else { favAspects = List.of("resource", "companion"); unfavAspects = List.of("output", "wealth", "power"); }
        List<String> favGods = favAspects.size() == 3 ? List.of("eg", "ho", "iw", "dw", "7k", "do") : List.of("ir", "dr", "f", "rw");
        List<String> unfavGods = favGods.get(0).equals("ir") ? List.of("eg", "ho", "iw", "dw", "7k", "do") : List.of("ir", "dr", "f", "rw");
        meta.put("favorable", Map.of(
                "aspects", favAspects,
                "elements", favAspects.stream().map(aspectToElement::get).toList(),
                "gods", favGods,
                "stems", favGods.stream().map(godToStem::get).toList()));
        meta.put("unfavorable", Map.of(
                "aspects", unfavAspects,
                "elements", unfavAspects.stream().map(aspectToElement::get).toList(),
                "gods", unfavGods,
                "stems", unfavGods.stream().map(godToStem::get).toList()));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castListOfMaps(Object o) {
        return o instanceof List ? (List<Map<String, Object>>) o : new ArrayList<>();
    }

    private static Map<String, Object> pillarOf(Map<String, Object> chart, String key) {
        return castMap(castMap(chart.get("pillars")).get(key));
    }

    /** luxon-equivalent of DateTime.fromISO(iso, {setZone:true}):
     *  - naive wall parsed as-is; for a DST GAP (nonexistent local time) luxon does not error —
     *    it computes instant = localTS - offsetBefore, shifts the wall forward by the gap
     *    (offsetBefore - offsetAfter sign-adjusted) and reports the AFTER offset.
     *  - ambiguous (fold) times resolve to the earlier offset (matches java.time fold=0). */
    private static final class Curr {
        final LocalDateTime wall;
        final long ts; // epoch millis
        final int off; // zone offset minutes matching luxon current.offset
        /** true when the ISO input carried an explicit offset — luxon then uses a
         *  FixedOffsetZone that never changes offset (naive input = system zone). */
        final boolean fixed;
        Curr(LocalDateTime wall, long ts, int off, boolean fixed) {
            this.wall = wall; this.ts = ts; this.off = off; this.fixed = fixed;
        }
    }

    private static Curr resolveCurrent(String iso) {
        LocalDateTime naive;
        try {
            naive = LocalDateTime.parse(iso);
        } catch (Exception e) {
            OffsetDateTime odt = OffsetDateTime.parse(iso);
            return new Curr(odt.toLocalDateTime(), odt.toInstant().toEpochMilli(),
                    odt.getOffset().getTotalSeconds() / 60, true);
        }
        ZoneId zone = ZoneId.systemDefault();
        ZoneRules rules = zone.getRules();
        ZoneOffsetTransition trans = rules.getTransition(naive);
        if (trans != null && trans.isGap()) {
            LocalDateTime before = trans.getDateTimeBefore();
            LocalDateTime after = trans.getDateTimeAfter();
            if (!naive.isBefore(before) && naive.isBefore(after)) {
                int preOff = trans.getOffsetBefore().getTotalSeconds() / 60;
                int postOff = trans.getOffsetAfter().getTotalSeconds() / 60;
                long ts = naive.toEpochSecond(trans.getOffsetBefore()) * 1000L;
                return new Curr(naive.plusMinutes(postOff - preOff), ts, postOff, false);
            }
        }
        ZonedDateTime zdt = naive.atZone(zone);
        return new Curr(naive, zdt.toInstant().toEpochMilli(), zdt.getOffset().getTotalSeconds() / 60, false);
    }
}
