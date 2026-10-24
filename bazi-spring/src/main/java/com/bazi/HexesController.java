package com.bazi;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Yi Jing (I Ching) endpoints: hexagram catalog/detail, the Bazi pillar
 * mappings, and the Li Shee Yee string method.
 */
@RestController
@RequestMapping("/api")
public class HexesController {

    private final HexesService hexes;

    public HexesController(HexesService hexes) {
        this.hexes = hexes;
    }

    /** GET /api/hexagrams - all 64 hexagrams (raw Hexes.order entries). */
    @GetMapping("/hexagrams")
    public Object catalog() {
        return hexes.catalog();
    }

    /** GET /api/hexagrams/from-pillar?stem=jia&branch=wu&horary=false */
    @GetMapping("/hexagrams/from-pillar")
    public Map<String, Object> fromPillar(@RequestParam String stem,
                                          @RequestParam String branch,
                                          @RequestParam(defaultValue = "false") boolean horary) {
        return hexes.fromPillar(stem, branch, horary);
    }

    /**
     * GET /api/hexagrams/{binary} - full detail; optional {@code dayMaster}
     * adds the day-master line animals (SingularDetails in the RN UI).
     */
    @GetMapping("/hexagrams/{binary}")
    public Map<String, Object> detail(@PathVariable String binary,
                                      @RequestParam(required = false) String dayMaster) {
        if (!binary.matches("[01]{6}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "binary must be 6 characters of 0/1");
        }
        Map<String, Object> detail = hexes.detail(binary, dayMaster);
        if (detail == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "unknown hexagram " + binary);
        }
        return detail;
    }

    /** GET /api/lisheeyee?q=abc&time=2024-06-15T14:30:00%2B08:00 (time defaults to now). */
    @GetMapping("/lisheeyee")
    public Map<String, Object> liSheeYee(@RequestParam String q,
                                         @RequestParam(required = false) String time) {
        return hexes.liSheeYee(q, time == null || time.isEmpty()
                ? OffsetDateTime.now().toString() : time);
    }

    /** POST /api/lisheeyee - same as GET, for long inputs; body: {q, time?}. */
    @PostMapping("/lisheeyee")
    public Map<String, Object> liSheeYeePost(@RequestBody Map<String, String> body) {
        String q = body.getOrDefault("q", "");
        String time = body.get("time");
        return hexes.liSheeYee(q, time == null || time.isEmpty()
                ? OffsetDateTime.now().toString() : time);
    }
}
