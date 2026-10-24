package com.bazi;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Feng Shui endpoints (WindWater.js port): lo pan degree lookup, static
 * reference tables, personal gua / eight mansions, time and house flying stars.
 */
@RestController
@RequestMapping("/api/fengshui")
public class FengShuiController {

    private final FengShuiService fengshui;

    public FengShuiController(FengShuiService fengshui) {
        this.fengshui = fengshui;
    }

    /** GET /api/fengshui/luopan?degree=180 - sector + mountain + attributes. */
    @GetMapping("/luopan")
    public Map<String, Object> luopan(@RequestParam String degree) {
        Double d = FengShuiService.parseFloatLoose(degree);
        if (d == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "degree must be numeric");
        }
        return fengshui.luopan(d);
    }

    /** GET /api/fengshui/luopan/mountains - 24 mountains, attributes and xuan kong rings. */
    @GetMapping("/luopan/mountains")
    public Map<String, Object> luopanMountains() {
        Map<String, Object> rings = new LinkedHashMap<>();
        rings.put("numberInner", fengshui.tableDeep("xuanKongNumberRingInner"));
        rings.put("numberOuter", fengshui.tableDeep("xuanKongNumberRingOuter"));
        rings.put("hexagramInner", fengshui.tableDeep("xuanKongHexagramInner"));
        rings.put("hexagramOuter", fengshui.tableDeep("xuanKongHexagramOuter"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mountains", fengshui.tableDeep("mountains"));
        // NE3 (yin) has no attribute entry in the RN source.
        out.put("attributes", fengshui.tableDeep("twentyFourMountainAttributes"));
        out.put("rings", rings);
        return out;
    }

    /** GET /api/fengshui/dragons - 72/60/60 dragon rings + three auspicious six elegance. */
    @GetMapping("/dragons")
    public Map<String, Object> dragons() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("piercingMountain72", fengshui.tableDeep("piercingMountain72Dragons"));
        out.put("earthPenetrating60", fengshui.tableDeep("earthPenetrating60Dragons"));
        out.put("equalDivision60", fengshui.tableDeep("equalDivision60Dragons"));
        out.put("threeAuspiciousSixElegance", fengshui.tableDeep("threeAuspiciousSixElegance"));
        return out;
    }

    /** GET /api/fengshui/reference - star meanings and other UI tables. */
    @GetMapping("/reference")
    public Map<String, Object> reference() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mountains", fengshui.tableDeep("mountains"));
        out.put("attributes", fengshui.tableDeep("twentyFourMountainAttributes"));
        out.put("lifeGuas", fengshui.tableDeep("guas"));
        out.put("flyingStars", fengshui.tableDeep("flyingStars"));
        out.put("flyingStarCombinations", fengshui.tableDeep("flyingStarCombinations"));
        out.put("eightMansions", fengshui.tableDeep("eightMansions"));
        out.put("eightMansionNaJia", fengshui.tableDeep("getEightMansionNaJia"));
        return out;
    }

    /** GET /api/fengshui/personal-gua?year=1990&gender=male&isPreviousYear=false */
    @GetMapping("/personal-gua")
    public Map<String, Object> personalGua(@RequestParam int year,
                                           @RequestParam(defaultValue = "male") String gender,
                                           @RequestParam(defaultValue = "false") boolean isPreviousYear) {
        Map<String, Object> out = fengshui.personalGua(year, isPreviousYear, gender);
        if (out == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "no gua for year " + year);
        }
        return out;
    }

    /** GET /api/fengshui/eight-mansions?facing=E - palace scores for a house facing. */
    @GetMapping("/eight-mansions")
    public Map<String, Object> eightMansions(@RequestParam String facing) {
        Map<String, Object> palaces = fengshui.getEightMansionsFromFacing(facing);
        if (palaces == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "facing must be one of N/NE/E/SE/S/SW/W/NW");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("facing", facing);
        out.put("palaces", fengshui.extendEightMansions(palaces));
        return out;
    }

    /** GET /api/fengshui/time-flying-stars?time=2024-06-15T14:30:00%2B08:00 (defaults to now). */
    @GetMapping("/time-flying-stars")
    public Map<String, Object> timeFlyingStars(@RequestParam(required = false) String time) {
        String t = (time == null || time.isBlank()) ? OffsetDateTime.now().toString() : time;
        try {
            return fengshui.timeFlyingStars(t);
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "time must be an ISO date-time, got: " + t);
        }
    }

    /**
     * GET /api/fengshui/house-flying-stars?houseYear=2024&houseDegree=90&doorDegree=0
     * doorDegree is validated (HouseSelector rules) and echoed only; the RN
     * house plot itself uses year + facing degree only.
     */
    @GetMapping("/house-flying-stars")
    public Map<String, Object> houseFlyingStars(@RequestParam String houseYear,
                                                @RequestParam String houseDegree,
                                                @RequestParam(required = false) String doorDegree) {
        Double door = null;
        if (doorDegree != null && !doorDegree.isBlank()) {
            door = FengShuiService.parseFloatLoose(doorDegree);
            if (door == null || door < 0 || door > 360) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "doorDegree must be a number between 0 and 360");
            }
        }
        Map<String, Object> payload = fengshui.houseFlyingStars(houseYear, houseDegree);
        if (payload == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "houseYear must be 1864-2064 and houseDegree 0-360 "
                            + "(degrees inside a 0.1-degree mountain gap are also rejected; RN crashes there)");
        }
        if (door != null) payload.put("doorDegree", door);
        return payload;
    }
}
