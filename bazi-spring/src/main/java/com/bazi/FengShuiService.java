package com.bazi;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Port of the Feng Shui engine in lib/cmtools/WindWater.js.
 *
 * <p>Ported 1:1 (quirks kept, see per-method notes): {@code degreeToMountain},
 * {@code yearToPeriod}, {@code getEightMansionsFromFacing},
 * {@code extendEightMansions}, {@code getPersonalGua} (delegates to
 * {@link BaziService#personalGua}), {@code getTimeFlyingStars},
 * {@code getHouseFlyingStars}, plus every static table in
 * {@code fengshui-data.json} (dumped from the RN module by
 * {@code tools/extract-js-data/dump.mjs}).
 *
 * <p>Agreed divergences (RN throws, this port returns {@code null} and the
 * controller answers 400):
 * <ul>
 *   <li>{@code degreeToMountain} has 0.1&deg; gaps between bands (e.g. 7.55).
 *       RN dereferences the undefined mountain and crashes; here the gap
 *       yields {@code null}.</li>
 *   <li>{@code getEightMansionsFromFacing} with an unknown direction crashes
 *       RN ({@code m} undefined); here it returns {@code null}.</li>
 *   <li>the day-center scan returns {@code null} if no jia-zi day is found
 *       within 61 days (RN would throw on {@code ziMark}; cannot happen with
 *       the 60-day cycle in practice).</li>
 * </ul>
 */
@Service
public class FengShuiService {

    /** WindWater.js flyStars() palace order (Tools.toProxy; wrap applied at use sites). */
    private static final List<String> PALACES =
            List.of("kan", "kun", "zhen", "xun", "center", "qian", "dui", "gen", "li");

    /** Ordinals.branchOrdering. */
    private static final List<String> BRANCHES =
            List.of("zi", "chou", "yin", "mao", "chen", "si", "wu", "wei", "shen", "you", "xu", "hai");

    /** monthCenterTable / ziHourCenterTable branch groups. */
    private static final List<String> QUARTERS = List.of("zi", "mao", "wu", "you");
    private static final List<String> CORNERS = List.of("yin", "si", "shen", "hai");

    /** WindWater.js monthCenterTable, keyed yin..chou, values [quarter, corner, storage]. */
    private static final Map<String, int[]> MONTH_CENTER = new LinkedHashMap<>();

    static {
        MONTH_CENTER.put("yin", new int[]{8, 2, 5});
        MONTH_CENTER.put("mao", new int[]{7, 1, 4});
        MONTH_CENTER.put("chen", new int[]{6, 9, 3});
        MONTH_CENTER.put("si", new int[]{5, 8, 2});
        MONTH_CENTER.put("wu", new int[]{4, 7, 1});
        MONTH_CENTER.put("wei", new int[]{3, 6, 9});
        MONTH_CENTER.put("shen", new int[]{2, 5, 8});
        MONTH_CENTER.put("you", new int[]{1, 4, 7});
        MONTH_CENTER.put("xu", new int[]{9, 3, 6});
        MONTH_CENTER.put("hai", new int[]{8, 2, 5});
        MONTH_CENTER.put("zi", new int[]{7, 1, 4});
        MONTH_CENTER.put("chou", new int[]{6, 9, 3});
    }

    private static final Map<String, Integer> ELEMENT_STRENGTH_SCORE = Map.of(
            "peak", 4, "strong", 3, "weak", 2, "trap", 1, "dead", 0);

    private final DataStore store;
    private final BaziService bazi;
    private final CycleService cycle;
    private final JsonNode fengshui;

    public FengShuiService(DataStore store, BaziService bazi, CycleService cycle, ResourceJson resources) {
        this.store = store;
        this.bazi = bazi;
        this.cycle = cycle;
        this.fengshui = resources.load("fengshui-data.json");
    }

    private JsonNode table(String key) {
        return fengshui.get(key);
    }

    /** Deep-converted copy of one static table (used by the reference endpoints). */
    public Object tableDeep(String key) {
        JsonNode node = fengshui.get(key);
        return node == null ? null : DataStore.deep(node);
    }

    /** WindWater.qiMenFengShuiMap ([ju][doorSector][facing] -> pillar number). */
    public JsonNode qiMenFengShuiMap() {
        return table("qiMenFengShuiMap");
    }

    // ---------------------------------------------------------------- lo pan

    /**
     * WindWater.degreeToMountain(degree): 24-mountain sector code (e.g. "N2").
     * Returns {@code null} in the 0.1&deg; gaps between bands (RN: undefined).
     * Negative degrees fall into the first {@code d <= 7.5} case in RN and are
     * preserved here.
     */
    public String degreeToMountain(double degree) {
        String sector = null;
        double d = degree;
        if (d >= 157.6 && d <= 172.5) sector = "s1";
        else if (d >= 172.6 && d <= 187.5) sector = "s2";
        else if (d >= 187.6 && d <= 202.5) sector = "s3";
        else if (d >= 202.6 && d <= 217.5) sector = "sw1";
        else if (d >= 217.6 && d <= 232.5) sector = "sw2";
        else if (d >= 232.6 && d <= 247.5) sector = "sw3";
        else if (d >= 247.6 && d <= 262.5) sector = "w1";
        else if (d >= 262.6 && d <= 277.5) sector = "w2";
        else if (d >= 277.6 && d <= 292.5) sector = "w3";
        else if (d >= 292.6 && d <= 307.5) sector = "nw1";
        else if (d >= 307.6 && d <= 322.5) sector = "nw2";
        else if (d >= 322.6 && d <= 337.5) sector = "nw3";
        else if (d >= 337.6 && d <= 352.5) sector = "n1";
        else if (d >= 352.6) sector = "n2";
        else if (d <= 7.5) sector = "n2";
        else if (d >= 7.6 && d <= 22.5) sector = "n3";
        else if (d >= 22.6 && d <= 37.5) sector = "ne1";
        else if (d >= 37.6 && d <= 52.5) sector = "ne2";
        else if (d >= 52.6 && d <= 67.5) sector = "ne3";
        else if (d >= 67.6 && d <= 82.5) sector = "e1";
        else if (d >= 82.6 && d <= 97.5) sector = "e2";
        else if (d >= 97.6 && d <= 112.5) sector = "e3";
        else if (d >= 112.6 && d <= 127.5) sector = "se1";
        else if (d >= 127.6 && d <= 142.5) sector = "se2";
        else if (d >= 142.6 && d <= 157.5) sector = "se3";
        if (sector == null) return null;
        return sector.toUpperCase(Locale.ROOT);
    }

    /** Degree plus mountain + attribute details (the /luopan endpoint payload). */
    public Map<String, Object> luopan(double degree) {
        String sector = degreeToMountain(degree);
        JsonNode mountain = sector == null ? null : table("mountains").get(sector);
        JsonNode attributes = sector == null ? null : table("twentyFourMountainAttributes").get(sector);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("degree", degree);
        out.put("sector", sector);
        out.put("mountain", mountain == null ? null : DataStore.deep(mountain));
        // NE3 (yin) has no attribute entry in the RN source; preserved as null.
        out.put("attributes", attributes == null ? null : DataStore.deep(attributes));
        return out;
    }

    /** WindWater.yearToPeriod(y); null outside 1844-2083. */
    public Integer yearToPeriod(int y) {
        if (y >= 1844 && y < 1864) return 9;
        if (y >= 1864 && y < 1884) return 1;
        if (y >= 1884 && y < 1904) return 2;
        if (y >= 1904 && y < 1924) return 3;
        if (y >= 1924 && y < 1944) return 4;
        if (y >= 1944 && y < 1964) return 5;
        if (y >= 1964 && y < 1984) return 6;
        if (y >= 1984 && y < 2004) return 7;
        if (y >= 2004 && y < 2024) return 8;
        if (y >= 2024 && y < 2044) return 9;
        if (y >= 2044 && y < 2064) return 1;
        if (y >= 2064 && y < 2084) return 2;
        return null;
    }

    // -------------------------------------------------------- eight mansions

    /** WindWater.getEightMansionsFromFacing(dir): palace -> mansion key, null for unknown dir. */
    public Map<String, Object> getEightMansionsFromFacing(String dir) {
        String[] m;
        switch (dir == null ? "" : dir) {
            case "S" -> m = new String[]{"yanNian", "jueMing", "huoHai", "liuSha", "fuWei", "wuGui", "tianYi", "shengQi"};
            case "W" -> m = new String[]{"shengQi", "huoHai", "jueMing", "wuGui", "tianYi", "liuSha", "fuWei", "yanNian"};
            case "NW" -> m = new String[]{"tianYi", "wuGui", "liuSha", "huoHai", "shengQi", "jueMing", "yanNian", "fuWei"};
            case "N" -> m = new String[]{"fuWei", "liuSha", "wuGui", "jueMing", "yanNian", "huoHai", "shengQi", "tianYi"};
            case "NE" -> m = new String[]{"liuSha", "fuWei", "tianYi", "yanNian", "jueMing", "shengQi", "huoHai", "wuGui"};
            case "SE" -> m = new String[]{"jueMing", "yanNian", "shengQi", "fuWei", "liuSha", "tianYi", "wuGui", "huoHai"};
            case "E" -> m = new String[]{"wuGui", "tianYi", "fuWei", "shengQi", "huoHai", "yanNian", "jueMing", "liuSha"};
            case "SW" -> m = new String[]{"huoHai", "shengQi", "yanNian", "tianYi", "wuGui", "fuWei", "liuSha", "jueMing"};
            default -> {
                return null; // RN: TypeError (m undefined)
            }
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("li", m[0]);
        map.put("kun", m[1]);
        map.put("dui", m[2]);
        map.put("qian", m[3]);
        map.put("kan", m[4]);
        map.put("gen", m[5]);
        map.put("zhen", m[6]);
        map.put("xun", m[7]);
        return map;
    }

    /** WindWater.extendEightMansions(map): palace -> {name, strength, rating, score}. */
    public Map<String, Object> extendEightMansions(Map<String, Object> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String pal = e.getKey();
            String mansion = String.valueOf(e.getValue());
            JsonNode palace = store.node("guas", pal);
            JsonNode mansionNode = table("eightMansions").get(mansion);
            if (palace == null || mansionNode == null) return null; // defensive; RN throws
            JsonNode strengthNode = store.node("elements", mansionNode.get("element").asText(),
                    "strength", palace.get("element").asText());
            if (strengthNode == null) return null;
            String strength = strengthNode.asText();
            int rating = List.of("shengQi", "tianYi", "yanNian", "fuWei").contains(mansion) ? 1 : -1;
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", mansion);
            entry.put("strength", strength);
            entry.put("rating", rating);
            entry.put("score", ELEMENT_STRENGTH_SCORE.getOrDefault(strength, 0) * rating);
            result.put(pal, entry);
        }
        return result;
    }

    /**
     * WindWater.getPersonalGua(year, isPreviousYear, gender) plus the
     * life-gua direction table (WindWater.guas[pinyin].eightMansions) and the
     * mansion details used by the RN EightMansions screen.
     */
    public Map<String, Object> personalGua(int year, boolean isPreviousYear, String gender) {
        Map<String, Object> gua = bazi.personalGua(year, isPreviousYear, gender);
        if (gua == null) return null;
        JsonNode lifeTable = table("guas").get((String) gua.get("pinyin"));
        JsonNode dirs = lifeTable == null ? null : lifeTable.get("eightMansions");
        Map<String, Object> directions = new LinkedHashMap<>();
        Map<String, Object> mansions = new LinkedHashMap<>();
        if (dirs != null) {
            for (Map.Entry<String, JsonNode> f : iterable(dirs.fields())) {
                String name = f.getValue().asText();
                directions.put(f.getKey(), name);
                JsonNode mansion = table("eightMansions").get(name);
                if (mansion != null) mansions.put(f.getKey(), DataStore.deep(mansion));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("personalGua", gua);
        out.put("directions", directions);
        out.put("mansions", mansions);
        return out;
    }

    // --------------------------------------------------------- flying stars

    /**
     * WindWater.getTimeFlyingStars(time): hour/day/month/year center plates.
     * Month/hour reuse the base chart like RN; the day center replays RN's
     * up-to-61-day scan for the jia-zi day after the solstice.
     */
    public Map<String, Object> timeFlyingStars(String timeIso) {
        Map<String, Object> base = plotAt(timeIso);
        OffsetDateTime odt = OffsetDateTime.parse((String) info(base).get("time"));
        LocalDateTime wall = odt.toLocalDateTime();
        int offsetMinutes = odt.getOffset().getTotalSeconds() / 60;
        boolean fixed = hasExplicitOffset(timeIso);

        int yearCenter = calcYearCenter(wall, offsetMinutes);
        int monthCenter = calcMonthCenter(base);
        Integer dayCenter = calcDayCenter(wall, offsetMinutes, fixed);
        int hourCenter = calcHourCenter(base);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("hour", flyStars(hourCenter, 1));
        payload.put("day", dayCenter == null ? null : flyStars(dayCenter, 1));
        payload.put("month", flyStars(monthCenter, 1));
        payload.put("year", flyStars(yearCenter, 1));
        return payload;
    }

    /** WindWater flyStars(center, flow). */
    private Map<String, Integer> flyStars(int center, int flow) {
        Map<String, Integer> data = new LinkedHashMap<>();
        for (int index = 0; index < PALACES.size(); index++) {
            if (flow == 0) {
                data.put(PALACES.get(index), 9 - Math.floorMod(index - 4 - center, 9));
            } else {
                data.put(PALACES.get(index), Math.floorMod(index + 4 + center, 9) + 1);
            }
        }
        return data;
    }

    private int calcYearCenter(LocalDateTime wall, int offsetMinutes) {
        SolarCalendar cal = new SolarCalendar(store, offsetMinutes);
        LocalDateTime jie = cal.getCurrentYearJie(new SolarCalendar.LocalDateTimeAt(wall, null)).lt;
        int year = wall.getYear();
        if (wall.isBefore(jie)) year -= 1;
        return Math.floorMod(2017 - year, 9) + 1; // nums = toProxy([1..9]); nums[2017-year]
    }

    private int calcMonthCenter(Map<String, Object> chart) {
        Map<String, Object> pillars = pillarMap(chart);
        String monthBranch = branchName(pillars.get("month"));
        String yearBranch = branchName(pillars.get("year"));
        int group = QUARTERS.contains(yearBranch) ? 0 : CORNERS.contains(yearBranch) ? 1 : 2;
        int[] row = MONTH_CENTER.get(monthBranch);
        return row == null ? 0 : row[group];
    }

    private Integer calcDayCenter(LocalDateTime wall, int offsetMinutes, boolean fixed) {
        // RN: time.set({hour:12}) keeps minutes/seconds and the input zone kind.
        String chart12Iso = fixed ? isoAt(wall.withHour(12), offsetMinutes) : wall.withHour(12).toString();
        Map<String, Object> chart12 = plotAt(chart12Iso);
        String chart12Time = (String) info(chart12).get("time");
        boolean yin = isYin(chart12);

        Map<String, Object> thisTerm = castMap(castMap(info(chart12).get("solarTerm")).get("thisTerm"));
        int termNum = ((Number) castMap(thisTerm.get("data")).get("num")).intValue();

        // SolarCal.getSolstice(bazi): when the current term is a solstice it
        // returns thisTerm.begins (chart-offset DateTime); otherwise the
        // backwards scan rebuilds the solstice with DateTime.fromObject() and
        // NO zone -> the server system zone (even for offset-qualified charts).
        // The jia-zi day is then found by plotting solstice+12:00 + i days.
        String ziMarkIso = null;
        if (termNum == 9 || termNum == 21) {
            OffsetDateTime begins = OffsetDateTime.parse(String.valueOf(thisTerm.get("begins")));
            for (int i = 0; i < 61 && ziMarkIso == null; i++) {
                ziMarkIso = jiaZiTime(plotAt(begins.withHour(12).plusDays(i).toString()));
            }
        } else {
            Map<String, Object> solstice = cycle.getSolstice(chart12);
            if (solstice == null) return null;
            LocalDateTime solsticeWall = LocalDateTime.parse((String) solstice.get("time"));
            for (int i = 0; i < 61 && ziMarkIso == null; i++) {
                ziMarkIso = jiaZiTime(plotAt(solsticeWall.withHour(12).plusDays(i).toString()));
            }
        }
        if (ziMarkIso == null) return null; // RN would TypeError on ziMark

        // RN: bazi.info.time.diff(ziMark.info.time, 'days').days — luxon calendar
        // diff across the two zones, not a raw millisecond division.
        boolean ziFixed = termNum == 9 || termNum == 21 ? fixed : false;
        double diffDays = BaziService.luxonDiffDays(chart12Time, fixed, ziMarkIso, ziFixed);
        int index = Math.floorMod((int) Math.round(diffDays), 9);
        return yin ? 9 - index : index + 1;
    }

    /** info.time of a plotted chart whose day pillar is jia-zi, else null. */
    private static String jiaZiTime(Map<String, Object> chart) {
        Map<String, Object> dayPillar = castMap(pillarMap(chart).get("day"));
        if ("jia".equals(stemName(dayPillar)) && "zi".equals(branchName(dayPillar))) {
            return (String) info(chart).get("time");
        }
        return null;
    }

    /** True when the ISO input carries an explicit offset (luxon FixedOffsetZone). */
    private static boolean hasExplicitOffset(String iso) {
        try {
            OffsetDateTime.parse(iso);
            return true;
        } catch (java.time.format.DateTimeParseException e) {
            return false;
        }
    }

    private int calcHourCenter(Map<String, Object> chart) {
        Map<String, Object> pillars = pillarMap(chart);
        boolean yin = isYin(chart);
        String dayBranch = branchName(pillars.get("day"));
        int ziHourCenter = ziHourCenterTable(dayBranch, yin);
        List<Integer> path = yin
                ? List.of(9, 8, 7, 6, 5, 4, 3, 2, 1)
                : List.of(1, 2, 3, 4, 5, 6, 7, 8, 9);
        int hourBranchIndex = BRANCHES.indexOf(branchName(pillars.get("hour")));
        int index = Math.floorMod(hourBranchIndex + path.indexOf(ziHourCenter), 9);
        return path.get(index);
    }

    /** RN: (term.data.num >= 9 && term.data.num < 21) ? 'yin' : 'yang'. */
    private boolean isYin(Map<String, Object> chart) {
        Map<String, Object> solarTerm = castMap(info(chart).get("solarTerm"));
        Map<String, Object> thisTerm = castMap(solarTerm.get("thisTerm"));
        Object num = castMap(thisTerm.get("data")).get("num");
        int n = ((Number) num).intValue();
        return n >= 9 && n < 21;
    }

    private int ziHourCenterTable(String dayBranch, boolean yin) {
        if (yin) {
            if (QUARTERS.contains(dayBranch)) return 9;
            if (CORNERS.contains(dayBranch)) return 3;
            return 6; // chou/chen/wei/xu
        }
        if (QUARTERS.contains(dayBranch)) return 1;
        if (CORNERS.contains(dayBranch)) return 7;
        return 4;
    }

    // -------------------------------------------------------- house flying

    /**
     * WindWater.getHouseFlyingStars({houseYear, houseDegree}).
     * Returns {@code null} for every case where RN returns null, and also for
     * degrees in the 0.1&deg; {@code degreeToMountain} gaps where RN crashes.
     */
    public Map<String, Object> houseFlyingStars(String houseYearRaw, String houseDegreeRaw) {
        Integer year = parseIntLoose(houseYearRaw);
        Double degree = parseFloatLoose(houseDegreeRaw);
        if (year == null || year == 0 || degree == null
                || year < 1864 || year > 2064 || degree < 0 || degree > 360) {
            return null;
        }
        String faceSector = degreeToMountain(degree);
        String sitSector = degreeToMountain((degree + 180) % 360);
        if (faceSector == null || sitSector == null) return null; // RN crashes in the gaps

        JsonNode faceNode = table("mountains").get(faceSector);
        JsonNode sitNode = table("mountains").get(sitSector);
        int period = yearToPeriod(year);
        Map<String, Integer> basePlate = flyStars(period, 1);

        String facePalace = faceNode.get("palace").asText();
        String sitPalace = sitNode.get("palace").asText();
        int facePath = flightPath(basePlate.get(facePalace), period, sectorDigits(faceNode.get("sector").asText()));
        int sitPath = flightPath(basePlate.get(sitPalace), period, sectorDigits(sitNode.get("sector").asText()));

        Map<String, Object> plates = new LinkedHashMap<>();
        plates.put("base", basePlate);
        plates.put("facing", flyStars(basePlate.get(facePalace), facePath));
        plates.put("sitting", flyStars(basePlate.get(sitPalace), sitPath));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("plates", plates);
        payload.put("period", period);
        payload.put("faceMountain", DataStore.deep(faceNode));
        payload.put("sitMountain", DataStore.deep(sitNode));
        payload.put("houseDegree", degree);
        payload.put("houseYear", year);
        return payload;
    }

    /** WindWater flightPath(gua, period, s). */
    private int flightPath(int gua, int period, int sector) {
        if (List.of(1, 3, 7, 9).contains(gua)) {
            return sector == 1 ? 1 : 0;
        } else if (List.of(2, 4, 6, 8).contains(gua)) {
            return sector == 1 ? 0 : 1;
        } else {
            if (period % 2 == 0) return sector == 1 ? 0 : 1;
            return sector == 1 ? 1 : 0;
        }
    }

    /** RN: parseInt(s.replace(/\D/g,'')) for sector codes like "N2"/"SW3". */
    private static int sectorDigits(String s) {
        String digits = s.replaceAll("\\D", "");
        return digits.isEmpty() ? 0 : Integer.parseInt(digits);
    }

    // ------------------------------------------------------------- helpers

    private Map<String, Object> plotAt(String iso) {
        BaziService.PlotOptions opts = new BaziService.PlotOptions();
        opts.time = iso;
        return bazi.plot(opts);
    }

    private static String isoAt(LocalDateTime wall, int offsetMinutes) {
        return OffsetDateTime.of(wall, ZoneOffset.ofTotalSeconds(offsetMinutes * 60)).toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> info(Map<String, Object> chart) {
        return (Map<String, Object>) chart.get("info");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> pillarMap(Map<String, Object> chart) {
        return (Map<String, Object>) chart.get("pillars");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return (Map<String, Object>) o;
    }

    private static String stemName(Object pillar) {
        return String.valueOf(castMap(castMap(pillar).get("stem")).get("name"));
    }

    private static String branchName(Object pillar) {
        return String.valueOf(castMap(castMap(pillar).get("branch")).get("name"));
    }

    private static <T> Iterable<T> iterable(java.util.Iterator<T> it) {
        return () -> it;
    }

    private static final Pattern INT_PREFIX = Pattern.compile("^\\s*[+-]?\\d+");
    private static final Pattern FLOAT_PREFIX =
            Pattern.compile("^\\s*[+-]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][+-]?\\d+)?");

    /** JS parseInt: leading integer prefix, null when there is none. */
    public static Integer parseIntLoose(String s) {
        if (s == null) return null;
        Matcher m = INT_PREFIX.matcher(s);
        if (!m.find()) return null;
        try {
            return Integer.parseInt(m.group());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** JS parseFloat: leading numeric prefix (NaN -> null). */
    public static Double parseFloatLoose(String s) {
        if (s == null) return null;
        Matcher m = FLOAT_PREFIX.matcher(s);
        if (!m.find()) return null;
        try {
            return Double.parseDouble(m.group());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
