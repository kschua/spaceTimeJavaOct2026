package com.bazi;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * QMDJ (Qi Men Dun Jia) endpoints: ju lookup (4a, {@code Plotters/QiMap/Jus.js}),
 * core chart plot (4b, {@code QiMap.plot}) and the static useful-god symbol
 * tables (4c, {@code MysticDoors.usefulGodSymbols}).
 */
@RestController
@RequestMapping("/api/qimen")
public class QimenController {

    private final BaziService bazi;
    private final QimenService qimen;
    private final QimenChartService qimenChart;
    private final QimenUsefulGodsService usefulGods;
    private final QimenDayListService dayList;

    public QimenController(BaziService bazi, QimenService qimen,
                           QimenChartService qimenChart,
                           QimenUsefulGodsService usefulGods,
                           QimenDayListService dayList) {
        this.bazi = bazi;
        this.qimen = qimen;
        this.qimenChart = qimenChart;
        this.usefulGods = usefulGods;
        this.dayList = dayList;
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class,
            java.time.format.DateTimeParseException.class})
    public ResponseEntity<Map<String, Object>> qimenError(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
    }

    /**
     * GET /api/qimen/jus?time=2024-01-15T12:00:00%2B08:00&reference=hour&method=0
     *
     * <p>{@code reference} is one of hour|day|month|year|destiny.
     * {@code method=1} with {@code reference=hour} switches to the chaiBu (拆補)
     * calculation, mirroring {@code QiMap.plot}'s method flag.
     */
    @GetMapping("/jus")
    public Map<String, Object> jus(@RequestParam String time,
                                   @RequestParam(defaultValue = "hour") String reference,
                                   @RequestParam(defaultValue = "0") int method) {
        if (!QimenService.REFERENCES.contains(reference)) {
            throw new IllegalArgumentException("unknown reference: " + reference);
        }
        BaziService.PlotOptions o = new BaziService.PlotOptions();
        o.time = time;
        Map<String, Object> chart = bazi.plot(o);

        Map<String, Object> res = method == 1 && "hour".equals(reference)
                ? qimen.chaiBu(chart)
                : qimen.zhiRun(reference, chart);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("time", infoValue(chart, "time"));
        out.put("reference", reference);
        out.put("method", method);
        // Table misses (e.g. hourJus beyond 2050) keep RN's null result.
        out.put("dun", res == null ? null : res.get("dun"));
        out.put("ju", res == null ? null : res.get("ju"));
        return out;
    }

    @GetMapping("/chart")
    public Map<String, Object> chart(@RequestParam String time,
                                     @RequestParam(defaultValue = "hour") String reference,
                                     @RequestParam(defaultValue = "0") int method,
                                     @RequestParam(defaultValue = "0") int starPath,
                                     @RequestParam(defaultValue = "true") boolean shiftLateZiStream,
                                     @RequestParam(required = false) Integer houseYear,
                                     @RequestParam(required = false) Double houseDegree,
                                     @RequestParam(required = false) Double doorDegree,
                                     @RequestParam(defaultValue = "false") boolean destiny,
                                     @RequestParam(defaultValue = "true") boolean meta,
                                     @RequestParam(defaultValue = "male") String gender,
                                     @RequestParam(defaultValue = "false") boolean lateZi,
                                     @RequestParam(defaultValue = "false") boolean hourUnknown) {
        if (!QimenChartService.REFERENCES.contains(reference)) {
            throw new IllegalArgumentException("unknown reference: " + reference);
        }
        BaziService.PlotOptions bo = new BaziService.PlotOptions();
        bo.time = time;
        bo.gender = gender;
        bo.lateZi = lateZi;
        bo.hourUnknown = hourUnknown;
        Map<String, Object> chart = bazi.plot(bo);

        QimenChartService.PlotOptions o = new QimenChartService.PlotOptions();
        o.reference = reference;
        o.method = method;
        o.starPath = starPath;
        o.shiftLateZiStream = shiftLateZiStream;
        o.appendDestiny = destiny;
        o.appendMeta = meta;
        if (houseYear != null || houseDegree != null || doorDegree != null) {
            // RN houseInput shape; falsy/absent fields disable the input exactly
            // like the RN optional chaining.
            Map<String, Object> houseInput = new LinkedHashMap<>();
            houseInput.put("houseYear", truthyNumber(houseYear));
            houseInput.put("houseDegree", truthyNumber(houseDegree));
            houseInput.put("doorDegree", truthyNumber(doorDegree));
            o.houseInput = houseInput;
        }

        Map<String, Object> result = qimenChart.plot(chart, o);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("time", infoValue(chart, "time"));
        out.put("reference", reference);
        // Null keeps RN parity: house reference without inputs, table misses
        // (e.g. hourJus beyond 2050) and the "0-undefined" ju days.
        out.put("chart", result);
        return out;
    }

    /**
     * GET /api/qimen/useful-gods - static topic list from
     * {@code MysticDoors.usefulGodSymbols} (RN QiMenUsefulGodList).
     */
    @GetMapping("/useful-gods")
    public Map<String, Object> usefulGods() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("topics", usefulGods.topics());
        return out;
    }

    /**
     * GET /api/qimen/useful-gods/{topic} - symbol rows for one topic
     * (RN QiMenUsefulGodDetails). Unknown topics answer 400.
     */
    @GetMapping("/useful-gods/{topic}")
    public Map<String, Object> usefulGodDetails(@PathVariable String topic) {
        return usefulGods.details(topic);
    }

    /**
     * GET /api/qimen/days?from=2024-01-01&to=2024-01-31 - Qi Men day/hour scan
     * ported from the RN Almanac Qi Men tab + QiMenDayList. Range is capped at
     * {@link QimenDayListService#MAX_DAYS} days; filters default to the RN
     * preferences and can be overridden with comma-separated lists.
     */
    @GetMapping("/days")
    public Map<String, Object> days(
            @RequestParam String from,
            @RequestParam(required = false) String to,
            @RequestParam(defaultValue = "false") boolean lateZi,
            @RequestParam(defaultValue = "0") int method,
            @RequestParam(defaultValue = "0") int starPath,
            @RequestParam(defaultValue = "true") boolean shiftLateZiStream,
            @RequestParam(required = false) List<String> doors,
            @RequestParam(required = false) List<String> stars,
            @RequestParam(required = false) List<String> spirits,
            @RequestParam(required = false) List<String> structures,
            @RequestParam(defaultValue = "0") int maxNegativeStructures,
            @RequestParam(required = false) String birthTime,
            @RequestParam(defaultValue = "male") String gender) {
        QimenDayListService.Options o = new QimenDayListService.Options();
        o.lateZi = lateZi;
        o.method = method;
        o.starPath = starPath;
        o.shiftLateZiStream = shiftLateZiStream;
        o.doors = doors;
        o.stars = stars;
        o.spirits = spirits;
        o.structures = structures;
        o.maxNegativeStructures = maxNegativeStructures;
        o.birthTime = birthTime;
        o.gender = gender;
        return dayList.scan(from, to, o);
    }

    /**
     * GET /api/qimen/tables - filter option tables for the day-scan UI
     * (door/star/spirit names in RN lookup order, structures with rating and
     * the almanacDefault flag).
     */
    @GetMapping("/tables")
    public Map<String, Object> tables() {
        return dayList.tables();
    }

    /**
     * GET /api/qimen/terminology - the 0/1/2 naming conventions from
     * {@code MysticDoors.displayTerminology} used by the destiny summary and
     * palace labels.
     */
    @GetMapping("/terminology")
    public Map<String, Object> terminology() {
        return qimen.displayTerminology();
    }

    /** 0 renders as null: RN treats 0 as a missing houseInput field. */
    private static Integer truthyNumber(Integer v) {
        return v == null || v == 0 ? null : v;
    }

    private static Double truthyNumber(Double v) {
        return v == null || v == 0.0 ? null : v;
    }

    @SuppressWarnings("unchecked")
    private static Object infoValue(Map<String, Object> chart, String key) {
        return ((Map<String, Object>) chart.get("info")).get(key);
    }
}
