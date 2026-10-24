package com.bazi;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Library / Compendium endpoints (phase 5): the {@code Compendium/index.js}
 * tree with its {@code $link_*} URLs and {@code $push_*} app targets, plus the
 * static reference screens ({@code RelationshipsTable}, {@code TwelveStagesList},
 * {@code YinYangRef}, {@code HeavenEarthMan}).
 */
@RestController
@RequestMapping("/api/library")
public class LibraryController {

    private final LibraryService library;

    public LibraryController(LibraryService library) {
        this.library = library;
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, Object>> libraryError(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
    }

    /** GET /api/library/compendium - resolved tree + Compendium/Titles.js map. */
    @GetMapping("/compendium")
    public Map<String, Object> compendium() {
        return library.compendium();
    }

    /**
     * GET /api/library/relationships[?key=stemCombos|direction|harmony|
     * threePenalties|clashes|selfPenalties|sixCombos|harms|destructions]
     *
     * <p>RN's {@code RelationshipsTable} filters its static sections with
     * {@code data.filter(i => i.key === key)}: no key = every section, unknown
     * key = empty list (both preserved).
     */
    @GetMapping("/relationships")
    public Map<String, Object> relationships(@RequestParam(required = false) String key) {
        return library.relationships(key);
    }

    /** GET /api/library/twelve-stages?stem=jia - TwelveStagesList for one stem. */
    @GetMapping("/twelve-stages")
    public Map<String, Object> twelveStages(@RequestParam(required = false) String stem) {
        return library.twelveStages(stem);
    }

    /** GET /api/library/yin-yang - the YinYangRef reference table. */
    @GetMapping("/yin-yang")
    public Map<String, Object> yinYang() {
        return library.yinYang();
    }

    /** GET /api/library/heaven-earth-man - the HeavenEarthMan word lists. */
    @GetMapping("/heaven-earth-man")
    public Map<String, Object> heavenEarthMan() {
        return library.heavenEarthMan();
    }

    /** GET /api/library/tutorials - the four Onboarding tutorial screens. */
    @GetMapping("/tutorials")
    public Map<String, Object> tutorials() {
        return library.tutorials();
    }
}
