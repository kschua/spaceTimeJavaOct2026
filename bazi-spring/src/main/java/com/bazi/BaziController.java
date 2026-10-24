package com.bazi;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
public class BaziController {

    private final BaziService bazi;
    private final ShenShaService shensha;
    private final SynastryService synastry;
    private final CycleService cycle;

    public BaziController(BaziService bazi, ShenShaService shensha, SynastryService synastry, CycleService cycle) {
        this.bazi = bazi;
        this.shensha = shensha;
        this.synastry = synastry;
        this.cycle = cycle;
    }


    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class,
            java.time.format.DateTimeParseException.class})
    public ResponseEntity<Map<String, Object>> chartError(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
    }

    /**
     * GET /api/bazi?time=2000-01-01T12:00:00&gender=male&lateZi=false&hourUnknown=false
     *
     * <p>New optional parameters mirror the RN plot() payload: genderUnknown,
     * simpleLuckCycle, weightsIncludeCombos, shiftingPalace (stem name), root
     * (ISO time of the reference chart) and extend (run extendAuxDataChart).</p>
     */
    @GetMapping("/bazi")
    @SuppressWarnings("unchecked")
    public Map<String, Object> bazi(@RequestParam String time,
                                    @RequestParam(defaultValue = "male") String gender,
                                    @RequestParam(defaultValue = "false") boolean genderUnknown,
                                    @RequestParam(defaultValue = "false") boolean lateZi,
                                    @RequestParam(defaultValue = "false") boolean hourUnknown,
                                    @RequestParam(defaultValue = "true") boolean showLuck,
                                    @RequestParam(defaultValue = "true") boolean showWeights,
                                    @RequestParam(defaultValue = "true") boolean simpleLuckCycle,
                                    @RequestParam(defaultValue = "true") boolean weightsIncludeCombos,
                                    @RequestParam(required = false) String shiftingPalace,
                                    @RequestParam(required = false) String root,
                                    @RequestParam(defaultValue = "true") boolean extend) {
        BaziService.PlotOptions opts = new BaziService.PlotOptions();
        opts.time = time;
        opts.gender = gender;
        opts.genderUnknown = genderUnknown;
        opts.lateZi = lateZi;
        opts.hourUnknown = hourUnknown;
        opts.showLuck = showLuck;
        opts.showWeights = showWeights;
        opts.simpleLuckCycle = simpleLuckCycle;
        opts.weightsIncludeCombos = weightsIncludeCombos;
        opts.shiftingPalaceStem = shiftingPalace;
        opts.rootTime = root;
        opts.extend = extend;

        Map<String, Object> chart = bazi.plot(opts);
        Map<String, Object> pillars = (Map<String, Object>) chart.get("pillars");
        List<Map<String, Object>> starResults = new ArrayList<>();
        for (String key : List.of("year", "month", "day", "hour", "minute", "conception", "life")) {
            Map<String, Object> p = (Map<String, Object>) pillars.get(key);
            if (p == null) continue;
            for (Map<String, Object> st : shensha.starsFromPillar(chart, p)) {
                st.put("pillar", key);
                starResults.add(st);
            }
        }
        chart.put("shensha", starResults);
        return chart;
    }

    /** GET /api/cycle/major?time=...&type=month|year|day|hour */
    @GetMapping("/cycle/major")
    public Map<String, Object> majorCycle(@RequestParam String time,
                                          @RequestParam String type,
                                          @RequestParam(defaultValue = "male") String gender) {
        Map<String, Object> chart = bazi.plot(time, null, gender, false, false, false, false, true);
        return cycle.getMajorCycle(chart, type);
    }

    /** GET /api/cycle/minor?time=...&type=month|year|day|hour */
    @GetMapping("/cycle/minor")
    public Map<String, Object> minorCycle(@RequestParam String time,
                                          @RequestParam String type,
                                          @RequestParam(defaultValue = "male") String gender) {
        Map<String, Object> chart = bazi.plot(time, null, gender, false, false, false, false, true);
        return cycle.getMinorCycle(chart, type);
    }

    /** GET /api/solstice?time=... */
    @GetMapping("/solstice")
    public Map<String, Object> solstice(@RequestParam String time,
                                        @RequestParam(defaultValue = "male") String gender) {
        Map<String, Object> chart = bazi.plot(time, null, gender, false, false, false, false, true);
        Map<String, Object> s = cycle.getSolstice(chart);
        return s == null ? Map.of() : s;
    }

    /** GET /api/synastry?t1=...&t2=...&g1=male&g2=female */
    @GetMapping("/synastry")
    public Map<String, Object> synastry(@RequestParam String t1, @RequestParam String t2,
                                        @RequestParam(defaultValue = "male") String g1,
                                        @RequestParam(defaultValue = "female") String g2,
                                        @RequestParam(defaultValue = "false") boolean genderUnknown1,
                                        @RequestParam(defaultValue = "false") boolean genderUnknown2) {
        return synastry.compare(t1, t2, g1, g2, genderUnknown1, genderUnknown2);
    }
}
