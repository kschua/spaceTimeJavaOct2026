package com.bazi;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.*;

import static com.bazi.DataStore.deepMap;

/**
 * Port of lib/cmtools/ShenSha.js — starsFromPillar() using the same aux tables,
 * plus the annual-star lookup consumed by EightWords.extendAuxData().
 *
 * <p>The RN app returns the full auxDetails dictionaries (name/ref/chinese/icon/
 * rating/desc/...), so this port does the same instead of the old slim
 * {name, ref} rows. Star order matches the JS object iteration order:
 * specialPillarAux → specialDayMasterAux (day only) → dayStemAux →
 * dayBranchAux → monthBranchAux → yearBranchAux.</p>
 */
@Service
public class ShenShaService {

    private final DataStore store;

    public ShenShaService(DataStore store) {
        this.store = store;
    }

    private JsonNode auxDetails() {
        return store.node("auxDetails");
    }

    /** chart: chart map from BaziService.plot; pillar: one of chart.pillars entries */
    public List<Map<String, Object>> starsFromPillar(Map<String, Object> chart, Map<String, Object> pillar) {
        Map<String, Object> pillars = cast(chart.get("pillars"));

        int dayStemIndex = Tables.STEMS.indexOf(stemName(pillars.get("day")));
        int dayBranchIndex = Tables.BRANCHES.indexOf(branchName(pillars.get("day")));
        int monthBranchIndex = Tables.BRANCHES.indexOf(branchName(pillars.get("month")));
        int yearBranchIndex = Tables.BRANCHES.indexOf(branchName(pillars.get("year")));

        String pillarStem = stemName(pillar);
        String pillarBranch = branchName(pillar);

        List<String[]> found = new ArrayList<>();

        // specialPillarAux: exact [stem, branch] pairs
        JsonNode specialPillar = store.node("specialPillarAux");
        for (String star : fieldNames(specialPillar)) {
            for (JsonNode pair : specialPillar.get(star)) {
                if (isPair(pair, pillarStem, pillarBranch)) {
                    found.add(new String[]{star, "specialPillarAux"});
                }
            }
        }

        // specialDayMasterAux: exact pairs, day pillar only
        if ("day".equals(String.valueOf(pillar.get("name")))) {
            JsonNode specialDm = store.node("specialDayMasterAux");
            for (String star : fieldNames(specialDm)) {
                for (JsonNode pair : specialDm.get(star)) {
                    if (isPair(pair, pillarStem, pillarBranch)) {
                        found.add(new String[]{star, "specialDayMasterAux"});
                    }
                }
            }
        }

        // dayStemAux: skyWealth is compared against the pillar stem, the rest
        // against the pillar branch (string or array of branches).
        JsonNode dayStemAux = store.node("dayStemAux");
        for (String star : fieldNames(dayStemAux)) {
            JsonNode column = dayStemAux.get(star);
            if (column == null || !column.isArray() || dayStemIndex < 0 || dayStemIndex >= column.size()) continue;
            JsonNode value = column.get(dayStemIndex);
            if (value == null || value.isNull()) continue;
            if ("skyWealth".equals(star)) {
                if (value.isTextual() && value.asText().equals(pillarStem)) {
                    found.add(new String[]{star, "dayStemAux"});
                }
            } else if ((value.isTextual() && value.asText().equals(pillarBranch))
                    || (value.isArray() && containsText(value, pillarBranch))) {
                found.add(new String[]{star, "dayStemAux"});
            }
        }

        // dayBranchAux: exact branch match
        JsonNode dayBranchAux = store.node("dayBranchAux");
        for (String star : fieldNames(dayBranchAux)) {
            JsonNode column = dayBranchAux.get(star);
            if (column == null || !column.isArray() || dayBranchIndex < 0 || dayBranchIndex >= column.size()) continue;
            JsonNode value = column.get(dayBranchIndex);
            if (value != null && value.isTextual() && value.asText().equals(pillarBranch)) {
                found.add(new String[]{star, "dayBranchAux"});
            }
        }

        // monthBranchAux: [stem, branch] pattern with null wildcards
        JsonNode monthBranchAux = store.node("monthBranchAux");
        for (String star : fieldNames(monthBranchAux)) {
            JsonNode column = monthBranchAux.get(star);
            if (column == null || !column.isArray() || monthBranchIndex < 0 || monthBranchIndex >= column.size()) continue;
            if (isPillarMatch(column.get(monthBranchIndex), pillarStem, pillarBranch)) {
                found.add(new String[]{star, "monthBranchAux"});
            }
        }

        // yearBranchAux: sittingSha/facingSha use indexOf(stem) > 0 (the first
        // element is intentionally skipped — JS quirk), the rest compare branch.
        JsonNode yearBranchAux = store.node("yearBranchAux");
        for (String star : fieldNames(yearBranchAux)) {
            JsonNode column = yearBranchAux.get(star);
            if (column == null || !column.isArray() || yearBranchIndex < 0 || yearBranchIndex >= column.size()) continue;
            JsonNode value = column.get(yearBranchIndex);
            if (value == null || value.isNull()) continue;
            if ("sittingSha".equals(star) || "facingSha".equals(star)) {
                if (value.isArray()) {
                    for (int i = 1; i < value.size(); i++) { // JS indexOf() > 0
                        JsonNode v = value.get(i);
                        if (v != null && v.isTextual() && v.asText().equals(pillarStem)) {
                            found.add(new String[]{star, "yearBranchAux"});
                            break;
                        }
                    }
                }
            } else if (value.isTextual() && value.asText().equals(pillarBranch)) {
                found.add(new String[]{star, "yearBranchAux"});
            }
        }

        // JS maps to auxDetails[ref][name] and keeps nulls; we drop missing rows
        // so sorting/JSON output stays sane (the shipped data has no gaps).
        List<Map<String, Object>> result = new ArrayList<>();
        for (String[] s : found) {
            JsonNode detail = auxDetails().path(s[1]).path(s[0]);
            if (detail != null && !detail.isNull()) result.add(deepMap(detail));
        }
        return result;
    }

    /**
     * ShenSha.annualStarAux['zi'][branch] — the shipped table only has the 'zi'
     * group (the app hardcodes it), so the group is a parameter with 'zi' as
     * the default for future annual-group support.
     */
    public List<Map<String, Object>> annualStarsForPillar(String branchName) {
        return annualStarsForPillar("zi", branchName);
    }

    public List<Map<String, Object>> annualStarsForPillar(String group, String branchName) {
        List<Map<String, Object>> out = new ArrayList<>();
        JsonNode refs = store.node("annualStarAux", group, branchName);
        if (refs == null || !refs.isArray()) return out;
        for (JsonNode ref : refs) {
            String table = ref.path("table").asText();
            JsonNode detail;
            if ("annualAuxDetails".equals(table)) {
                detail = store.node("annualAuxDetails", ref.path("name").asText());
            } else {
                detail = store.node("auxDetails", ref.path("group").asText(), ref.path("name").asText());
            }
            if (detail != null && !detail.isNull()) out.add(deepMap(detail));
        }
        return out;
    }

    // ----- matching helpers mirroring ShenSha.js -----

    private static boolean isPair(JsonNode arr, String stem, String branch) {
        if (arr == null || !arr.isArray() || arr.size() < 2) return false;
        JsonNode s = arr.get(0);
        JsonNode b = arr.get(1);
        return s != null && s.isTextual() && s.asText().equals(stem)
                && b != null && b.isTextual() && b.asText().equals(branch);
    }

    /**
     * JS isPillarMatch(a, [stem, branch]) — falsy entries are wildcards. Note
     * this includes the empty string: the thrivingDay row for 'wu' is
     * {@code [null, ""]}, and RN's {@code if(a[1])} treats "" as a wildcard.
     */
    private static boolean isPillarMatch(JsonNode a, String stem, String branch) {
        if (a == null || !a.isArray() || a.size() < 2) return false;
        JsonNode s = a.get(0);
        JsonNode b = a.get(1);
        // JS: `if (a[0] === null && a[1] === null) return false` (strict).
        if (isStrictNull(s) && isStrictNull(b)) return false;
        boolean sNull = isFalsy(s);
        boolean bNull = isFalsy(b);
        boolean stemMatch = sNull || (s.isTextual() && s.asText().equals(stem));
        boolean branchMatch = bNull || (b.isTextual() && b.asText().equals(branch));
        return stemMatch && branchMatch;
    }

    private static boolean isStrictNull(JsonNode n) {
        return n == null || n.isNull();
    }

    /** JS falsy: null, undefined, "" (the tables use "" as a wildcard). */
    private static boolean isFalsy(JsonNode n) {
        return n == null || n.isNull() || (n.isTextual() && n.asText().isEmpty());
    }

    private static boolean containsText(JsonNode arr, String value) {
        for (JsonNode n : arr) {
            if (n != null && n.isTextual() && n.asText().equals(value)) return true;
        }
        return false;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        if (node != null) node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Collections.emptyMap();
    }

    private static String stemName(Object pillar) {
        return String.valueOf(cast(cast(pillar).get("stem")).get("name"));
    }

    private static String branchName(Object pillar) {
        return String.valueOf(cast(cast(pillar).get("branch")).get("name"));
    }
}
