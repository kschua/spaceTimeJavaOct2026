package com.bazi;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Almanac (Tong Shu) endpoints. Plots the chart with {@link BaziService} and
 * feeds it to {@link AlmanacService}, mirroring the RN flow of
 * {@code Timing.getAllDayInfo({ bazi: FourPillars.plot(...) })}.
 */
@RestController
@RequestMapping("/api")
public class AlmanacController {

    private final BaziService bazi;
    private final AlmanacService almanac;

    public AlmanacController(BaziService bazi, AlmanacService almanac) {
        this.bazi = bazi;
        this.almanac = almanac;
    }

    /** Day info for an ISO time, e.g. {@code /api/almanac?time=2019-07-15T12:00:00%2B08:00}. */
    @GetMapping("/almanac")
    public Map<String, Object> almanac(
            @RequestParam String time,
            @RequestParam(defaultValue = "false") boolean lateZi,
            @RequestParam(defaultValue = "false") boolean hourUnknown,
            @RequestParam(required = false) String chartName) {
        BaziService.PlotOptions opts = new BaziService.PlotOptions();
        opts.time = time;
        opts.lateZi = lateZi;
        opts.hourUnknown = hourUnknown;
        opts.chartName = chartName;
        Map<String, Object> chart = bazi.plot(opts);
        return almanac.dayResponse(chart);
    }

    /** Officer / constellation / three-killings descriptions used by the UI. */
    @GetMapping("/almanac/reference")
    public Map<String, Object> reference() {
        return almanac.reference();
    }
}
