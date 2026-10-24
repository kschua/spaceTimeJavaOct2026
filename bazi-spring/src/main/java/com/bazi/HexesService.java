package com.bazi;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Port of the RN Yi Jing logic: the 64 hexagram catalog ({@code Hexes.js}),
 * the Bazi pillar & horary mappings, the derived line data from
 * {@code HexagramSelector.js} ({@code dataToInfo} / {@code setToLineData}) and
 * the Li Shee Yee string method from {@code LiSheeYeeSelector.js}.
 *
 * <p>Parity notes (kept deliberately):
 * <ul>
 *   <li>{@code branchOrdering} is a {@code Tools.toProxy} array in RN, so line
 *       branch indices wrap around the 12 branches; Java reproduces the wrap
 *       with {@code Math.floorMod}.</li>
 *   <li>The RN Li Shee Yee "odd length" branch contains a no-op
 *       {@code num.substring(0, num.length)} before splitting; the split is
 *       therefore {@code floor(n/2)} / middle / trailing extra digit. Ported
 *       exactly.</li>
 *   <li>{@code horaryTable} has only 59 of the 60 jiazi keys (gui-you is
 *       missing in the RN data) - such lookups return an empty list instead of
 *       the RN undefined crash.</li>
 *   <li>Missing/unknown stems or branches return empty results (RN would throw
 *       a TypeError). This is the agreed null-safety divergence.</li>
 * </ul>
 */
@Service
public class HexesService {

    /** Ordinals.branchOrdering (proxy-wrapped in RN; wrap applied at use sites). */
    private static final List<String> BRANCH_ORDERING = List.of(
            "zi", "chou", "yin", "mao", "chen", "si", "wu", "wei", "shen", "you", "xu", "hai");

    /** LiSheeYeeSelector's preHeavenToTrigram digit -> gua key. */
    private static final List<String> PRE_HEAVEN_TO_TRIGRAM = List.of(
            "kun", "qian", "dui", "li", "zhen", "xun", "kan", "gen", "kun", "qian");

    private final DataStore store;
    private final BaziService bazi;
    private final JsonNode data;
    private final List<Map<String, Object>> order = new ArrayList<>();
    private final Map<String, Map<String, Object>> byBinary = new HashMap<>();

    public HexesService(DataStore store, BaziService bazi, ResourceJson resources) {
        this.store = store;
        this.bazi = bazi;
        this.data = resources.load("hexes-data.json");
        for (JsonNode n : data.get("order")) {
            Map<String, Object> hex = DataStore.deepMap(n);
            order.add(hex);
            byBinary.put((String) hex.get("binary"), hex);
        }
    }

    // ---------------------------------------------------------------- catalog

    /** All 64 King Wen hexagrams, in order (numbers 1..64). */
    public List<Map<String, Object>> catalog() {
        return Collections.unmodifiableList(order);
    }

    /** Full detail for a binary: hexagram, trigrams, derived lines, mutual. */
    public Map<String, Object> detail(String binary, String dayMaster) {
        Map<String, Object> hex = byBinary.get(binary);
        if (hex == null) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("hexagram", hex);
        out.put("topTrigram", guaByBinary(binary.substring(0, 3)));
        out.put("bottomTrigram", guaByBinary(binary.substring(3)));
        out.put("lineData", lineData(binary, dayMaster));
        Map<String, Object> mutual = new LinkedHashMap<>();
        String mutualBinary = mutualBinary(binary);
        mutual.put("binary", mutualBinary);
        mutual.put("hexagram", byBinary.get(mutualBinary));
        out.put("mutual", mutual);
        return out;
    }

    // ------------------------------------------------------------ pillar maps

    /**
     * BaziHexagram: {@code pillars[stem][branch]} -> hexagrams, or (horary)
     * {@code horaryTable[stem-branch]} -> a single hexagram.
     */
    public Map<String, Object> fromPillar(String stem, String branch, boolean horary) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stem", stem);
        out.put("branch", branch);
        out.put("horary", horary);
        if (horary) {
            String binary = textOrNull(data.path("horaryTable").path(stem + "-" + branch));
            out.put("binary", binary);
            List<Map<String, Object>> hexes = new ArrayList<>();
            if (binary != null && byBinary.containsKey(binary)) hexes.add(byBinary.get(binary));
            out.put("hexagrams", hexes);
        } else {
            JsonNode numbers = data.path("pillars").path(stem).path(branch);
            List<Integer> nums = new ArrayList<>();
            List<Map<String, Object>> hexes = new ArrayList<>();
            if (numbers.isArray()) {
                for (JsonNode n : numbers) {
                    int num = n.asInt();
                    nums.add(num);
                    if (num >= 1 && num <= order.size()) hexes.add(order.get(num - 1));
                }
            }
            out.put("numbers", nums);
            out.put("hexagrams", hexes);
        }
        return out;
    }

    // ------------------------------------------------------------ line data

    /** HexagramSelector.setToLineData: branches/elements/aspects for the 6 lines (top first). */
    public Map<String, Object> lineData(String binary, String dayMaster) {
        Map<String, Object> hex = byBinary.get(binary);
        if (hex == null) return null;
        int subjectLine = ((Number) hex.get("subjectLine")).intValue();
        int subject = Math.abs(subjectLine - 6);
        int object = subject < 3 ? subject + 3 : subject - 3;
        String element = (String) hex.get("element");

        Map<String, Object> topTrigram = guaByBinary(binary.substring(0, 3));
        Map<String, Object> bottomTrigram = guaByBinary(binary.substring(3));
        String bottomStart = startBranch((String) bottomTrigram.get("pinyin"), 0);
        String topStart = startBranch((String) topTrigram.get("pinyin"), 1);

        List<String> branches = new ArrayList<>();
        int bottomIndex = BRANCH_ORDERING.indexOf(bottomStart);
        branches.add(bottomStart);
        addLineBranches(branches, bottomIndex);
        int topIndex = BRANCH_ORDERING.indexOf(topStart);
        branches.add(topStart);
        addLineBranches(branches, topIndex);
        Collections.reverse(branches);

        List<String> elements = new ArrayList<>();
        for (String b : branches) elements.add(lineElement(b));
        List<String> aspects = new ArrayList<>();
        for (String el : elements) {
            aspects.add(store.node("elements", element, "elements", el).asText());
        }

        List<String> animals = new ArrayList<>();
        if (dayMaster != null && !dayMaster.isEmpty()) {
            List<Object> animalOrder = DataStore.deepList(data.get("animalOrder"));
            String startAnimal = textOrNull(data.path("stemAnimalMap").path(dayMaster));
            int startIndex = animalOrder.indexOf(startAnimal);
            for (int i = 0; i < 6; i++) {
                animals.add((String) animalOrder.get(Math.floorMod(startIndex + i, animalOrder.size())));
            }
        }
        Collections.reverse(animals);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("branches", branches);
        out.put("elements", elements);
        out.put("aspects", aspects);
        out.put("subject", subject);
        out.put("object", object);
        out.put("animals", animals);
        out.put("element", element);
        return out;
    }

    /** Two more line branches at +2/+4 (even start) or -2/-4 (odd start), proxy-wrapped. */
    private void addLineBranches(List<String> branches, int startIndex) {
        int step = startIndex % 2 == 0 ? 2 : -2;
        branches.add(BRANCH_ORDERING.get(Math.floorMod(startIndex + step, BRANCH_ORDERING.size())));
        branches.add(BRANCH_ORDERING.get(Math.floorMod(startIndex + step * 2, BRANCH_ORDERING.size())));
    }

    private String lineElement(String branch) {
        String stem = store.node("branches", branch, "_hiddenStemsArr").get(1).asText();
        return store.node("stems", stem, "element").asText();
    }

    private String startBranch(String trigramPinyin, int slot) {
        return data.path("hexagramStartBranches").path(trigramPinyin).get(slot).asText();
    }

    /** [line2,line3,line4,line3,line4,line5] of the top-first binary. */
    private static String mutualBinary(String binary) {
        return "" + binary.charAt(1) + binary.charAt(2) + binary.charAt(3)
                + binary.charAt(2) + binary.charAt(3) + binary.charAt(4);
    }

    // ----------------------------------------------------------- Li Shee Yee

    /** LiSheeYeeSelector.calcData with the given q and chart time. */
    public Map<String, Object> liSheeYee(String q, String timeIso) {
        String converted = convertLetters(q == null ? "" : q);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("q", q == null ? "" : q);
        out.put("converted", converted);
        out.put("time", timeIso);
        if (converted.length() < 2) return out; // RN shows no result

        String num = converted;
        String extra = "";
        String h1;
        String h2;
        int firstTrigramNum;
        int secondTrigramNum;
        if (num.length() == 2) {
            h1 = num.substring(0, 1);
            h2 = num.substring(1, 2);
            firstTrigramNum = num.charAt(0) - '0';
            secondTrigramNum = num.charAt(1) - '0';
        } else {
            if (num.length() % 2 == 1) {
                extra = num.substring(num.length() - 1);
                // RN: num = num.substring(0, num.length) - a no-op; keep num intact.
                h1 = num.substring(0, num.length() / 2);
                h2 = num.substring(num.length() / 2, num.length() - 1);
            } else {
                h1 = num.substring(0, num.length() / 2);
                h2 = num.substring(num.length() / 2);
            }
            firstTrigramNum = addNumbers(h1) % 8;
            secondTrigramNum = addNumbers(h2 + extra) % 8;
        }

        int numTotal = addNumbers(num);
        BaziService.PlotOptions opts = new BaziService.PlotOptions();
        opts.time = timeIso;
        Map<String, Object> chart = bazi.plot(opts);
        @SuppressWarnings("unchecked")
        Map<String, Object> hourPillar = (Map<String, Object>) ((Map<String, Object>) chart.get("pillars")).get("hour");
        @SuppressWarnings("unchecked")
        String hourBranch = (String) ((Map<String, Object>) hourPillar.get("branch")).get("name");
        int whichHour = store.node("branches", hourBranch, "number").asInt() + 1;

        int movingLine = (numTotal + whichHour) % 6;
        if (movingLine == 0) movingLine = 6;

        Map<String, Object> firstTrigram = guaByPinyin(PRE_HEAVEN_TO_TRIGRAM.get(firstTrigramNum));
        Map<String, Object> secondTrigram = guaByPinyin(PRE_HEAVEN_TO_TRIGRAM.get(secondTrigramNum));
        String binary = firstTrigram.get("binary") + (String) secondTrigram.get("binary");
        Map<String, Object> hex = byBinary.get(binary);

        // finishInputMethod(): 1 at movingLine-1, reversed; lines are top-first,
        // so the flipped index is 6 - movingLine.
        int flipIndex = 6 - movingLine;
        char[] changed = binary.toCharArray();
        changed[flipIndex] = changed[flipIndex] == '0' ? '1' : '0';
        String changedBinary = new String(changed);
        Map<String, Object> changedHex = byBinary.get(changedBinary);

        out.put("h1", h1);
        out.put("h2", h2);
        out.put("extra", extra);
        out.put("firstTrigram", firstTrigram.get("pinyin"));
        out.put("secondTrigram", secondTrigram.get("pinyin"));
        out.put("numTotal", numTotal);
        out.put("hourBranch", hourBranch);
        out.put("whichHour", whichHour);
        out.put("movingLine", movingLine);
        out.put("binary", binary);
        out.put("number", hex == null ? null : hex.get("number"));
        out.put("hexagram", hex);
        out.put("changedBinary", changedBinary);
        out.put("changedNumber", changedHex == null ? null : changedHex.get("number"));
        out.put("changedHexagram", changedHex);
        return out;
    }

    /** strings.liSheeYeeLetterConversion (spaces stripped, unmapped chars dropped). */
    static String convertLetters(String s) {
        StringBuilder result = new StringBuilder();
        String str = s.replace(" ", "");
        for (int i = 0; i < str.length(); i++) {
            char input = Character.toLowerCase(str.charAt(i));
            if (input >= '0' && input <= '9') {
                result.append(input);
            } else if ("ajs".indexOf(input) >= 0) {
                result.append('1');
            } else if ("bkt".indexOf(input) >= 0) {
                result.append('2');
            } else if ("clu".indexOf(input) >= 0) {
                result.append('3');
            } else if ("dmv".indexOf(input) >= 0) {
                result.append('4');
            } else if ("enw".indexOf(input) >= 0) {
                result.append('5');
            } else if ("fox".indexOf(input) >= 0) {
                result.append('6');
            } else if ("gpy".indexOf(input) >= 0) {
                result.append('7');
            } else if ("hqz".indexOf(input) >= 0) {
                result.append('8');
            } else if ("ir".indexOf(input) >= 0) {
                result.append('9');
            }
        }
        return result.toString();
    }

    /** strings.addNumbersInString for digit-only strings. */
    static int addNumbers(String str) {
        int total = 0;
        for (int i = 0; i < str.length(); i++) {
            total += str.charAt(i) - '0';
        }
        return total;
    }

    // --------------------------------------------------------------- helpers

    private Map<String, Object> guaByBinary(String binary) {
        for (JsonNode g : store.node("guas")) {
            if (binary.equals(textOrNull(g.get("binary")))) return DataStore.deepMap(g);
        }
        return null;
    }

    private Map<String, Object> guaByPinyin(String pinyin) {
        return DataStore.deepMap(store.node("guas").get(pinyin));
    }

    private static String textOrNull(JsonNode n) {
        return n == null || n.isMissingNode() || n.isNull() ? null : n.asText();
    }
}
