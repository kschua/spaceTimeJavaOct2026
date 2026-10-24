package com.bazi;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 5 Library / Compendium port. All content is static reference data
 * extracted from the RN sources (see tools/extract-js-data/library-source.mjs
 * and merge_library.py):
 *
 * <ul>
 *   <li>{@link #compendium()} - the resolved {@code Compendium/index.js} tree
 *       (kept verbatim, including the {@code $header_*} / {@code $link_*} /
 *       {@code $push_*} section markers that {@code components/Info/Library.js}
 *       renders) plus {@code Compendium/Titles.js} for display titles.</li>
 *   <li>{@link #relationships(String)} - the {@code RelationshipsTable}
 *       sections; RN filters with {@code data.filter(i => i.key === key)}, so
 *       no key returns everything and an unknown key returns an empty list.</li>
 *   <li>{@link #twelveStages(String)} - the {@code TwelveStagesList} screen:
 *       per branch, {@code twelveStagesData[stems[stem].twelveStages.indexOf(branch)]}.</li>
 *   <li>{@link #yinYang()} / {@link #heavenEarthMan()} - the static word lists
 *       embedded in the {@code YinYangRef} / {@code HeavenEarthMan} components.</li>
 *   <li>{@link #tutorials()} - the four {@code Tutorial*} Onboarding screens
 *       (Bazi / Calendar / Yi Jing / QMDJ), page text and image URIs.</li>
 * </ul>
 */
@Service
public class LibraryService {

    /** Ordinals.branchOrdering. */
    static final List<String> BRANCH_ORDERING = List.of(
            "zi", "chou", "yin", "mao", "chen", "si",
            "wu", "wei", "shen", "you", "xu", "hai");

    private final ResourceJson resources;
    private final DataStore store;

    public LibraryService(ResourceJson resources, DataStore store) {
        this.resources = resources;
        this.store = store;
    }

    /** GET /api/library/compendium - tree + titles. */
    public Map<String, Object> compendium() {
        JsonNode lib = resources.load("library-data.json");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tree", DataStore.deep(lib.get("compendium")));
        out.put("titles", DataStore.deep(lib.get("titles")));
        return out;
    }

    /** GET /api/library/relationships[?key=] - RN filter semantics preserved. */
    public Map<String, Object> relationships(String key) {
        JsonNode sections = resources.load("library-data.json").get("relationships");
        List<Object> filtered = new ArrayList<>();
        for (JsonNode section : sections) {
            if (key == null || key.equals(section.path("key").asText())) {
                filtered.add(DataStore.deep(section));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sections", filtered);
        return out;
    }

    /**
     * GET /api/library/twelve-stages?stem= - the TwelveStagesList screen.
     * RN would throw on an unknown stem (indexing {@code stems[stem]}), the
     * port answers 400 instead.
     */
    public Map<String, Object> twelveStages(String stem) {
        if (stem == null || stem.isEmpty()) {
            throw new IllegalArgumentException("stem is required");
        }
        JsonNode stemInfo = store.node("stems", stem);
        if (stemInfo == null) {
            throw new IllegalArgumentException("unknown stem: " + stem);
        }
        JsonNode stages = stemInfo.get("twelveStages");

        List<Map<String, Object>> rows = new ArrayList<>();
        for (String branch : BRANCH_ORDERING) {
            int index = -1;
            for (int i = 0; i < stages.size(); i++) {
                if (stages.get(i).asText().equals(branch)) {
                    index = i;
                    break;
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("branch", branch);
            row.put("stage", index < 0 ? null
                    : DataStore.deep(store.node("twelveStagesData").get(index)));
            row.put("animalName", text(branch, "animalName"));
            row.put("chinese", text(branch, "chinese"));
            row.put("element", text(branch, "element"));
            row.put("season", text(branch, "season"));
            row.put("polarity", text(branch, "polarity"));
            rows.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", stem);
        info.put("chinese", stemInfo.path("chinese").asText());
        info.put("natureName", stemInfo.path("natureName").asText());
        info.put("elementName", stemInfo.path("elementName").asText());
        out.put("stem", info);
        out.put("stages", rows);
        return out;
    }

    /** GET /api/library/yin-yang - YinYangRef word pairs. */
    public Map<String, Object> yinYang() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("words", DataStore.deep(resources.load("library-data.json").get("yinYang")));
        return out;
    }

    /** GET /api/library/heaven-earth-man - HeavenEarthMan word lists. */
    public Map<String, Object> heavenEarthMan() {
        return DataStore.deepMap(resources.load("library-data.json").get("heavenEarthMan"));
    }

    /** GET /api/library/tutorials - the four RN tutorial screens, verbatim. */
    public Map<String, Object> tutorials() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tutorials", DataStore.deep(resources.load("library-data.json").get("tutorials")));
        return out;
    }

    /** Display fields for a branch from bazi-data.json. */
    private String text(String branch, String field) {
        JsonNode node = store.node("branches", branch, field);
        return node == null ? null : node.asText();
    }
}
