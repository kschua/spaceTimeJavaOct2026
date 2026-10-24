package com.bazi;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Port of the RN QMDJ chart plotter ({@code Plotters/QiMap/index.js}):
 * {@code plot} (earth/heaven plates, doors, stars, spirits, period luck),
 * {@code appendQiMenMeta} (victories, formations, interactions, structures)
 * and {@code appendQiMenDestiny} (per-palace destiny lines).
 *
 * <p>RN quirks kept deliberately:</p>
 * <ul>
 *   <li>{@code plot.isLeadStar} compares {@code leadDoor} (not {@code leadStar})
 *       exactly like the RN source - the flag equals {@code isLeadDoor}.</li>
 *   <li>{@code details.grainIsBird} is {@code leadStemIsCenter} (RN naming).</li>
 *   <li>when the earth plate holds the reference stem in the center, the
 *       lookup substitutes {@code kun} and marks {@code heavenIsCenter} /
 *       {@code leadStemIsCenter} (center palaces map to Kun).</li>
 *   <li>late-zi stream shift: hour 23:00 charts remap the reference stem
 *       (jia-&gt;ren, bing-&gt;jia, wu-&gt;bing, geng-&gt;wu, ren-&gt;geng) when
 *       {@code shiftLateZiStream && starPath == 0}.</li>
 *   <li>the "0-undefined" ju string makes RN throw inside the earth plate
 *       (NaN index); the port returns {@code null} (no chart).</li>
 *   <li>house input with a missing field, out-of-range year (period null) or
 *       a degree in a mountain gap crashes RN; here the missing-field case
 *       keeps RN's {@code null} result while the other two throw
 *       {@link IllegalArgumentException} (controller answers 400).</li>
 *   <li>an hour-unknown chart (hour pillar {@code null}) crashes RN in the
 *       reference lookup or meta ({@code hours['']}); the port returns a null
 *       chart for {@code reference=hour} and omits the undefined
 *       {@code doorInteraction} elsewhere.</li>
 *   <li>{@code appendStyle} (RN styles/icons) is not ported; every field the
 *       style layer would add is {@code undefined} in the RN tables anyway
 *       ({@code suitable}/{@code unsuitable}/{@code desc} are not defined in
 *       MysticDoors).</li>
 * </ul>
 *
 * <p>When no destiny palace is found RN logs and then throws
 * ({@code counteringPalaces[null]}); the port logs and skips the obstruction
 * labels instead.</p>
 */
@Service
public class QimenChartService {

    private static final Logger log = LoggerFactory.getLogger(QimenChartService.class);

    public static final List<String> REFERENCES = List.of("hour", "day", "month", "year", "house");

    /** Tools.toProxy(['kan','kun','zhen','xun','center','qian','dui','gen','li']). */
    static final List<String> PALACES_LUO_SHU =
            List.of("kan", "kun", "zhen", "xun", "center", "qian", "dui", "gen", "li");

    /** Tools.toProxy(['kan','gen','zhen','xun','li','kun','dui','qian']). */
    static final List<String> PALACES_CIRCULAR =
            List.of("kan", "gen", "zhen", "xun", "li", "kun", "dui", "qian");

    static final List<String> EARTH_STEMS =
            List.of("wu", "ji", "geng", "xin", "ren", "gui", "ding", "bing", "yi");

    static final List<String> DOOR_CIRCULAR_FLOW =
            List.of("rest", "life", "harm", "delusion", "scenery", "death", "fear", "open");

    static final List<String> STAR_CIRCULAR_FLOW =
            List.of("grass", "ambassador", "destructor", "assistant", "hero", "grain", "pillar", "heart");

    static final List<String> STAR_LUOSHU_FLOW =
            List.of("grass", "grain", "destructor", "assistant", "bird", "heart", "pillar", "ambassador", "hero");

    static final List<String> SPIRIT_FORWARD =
            List.of("chief", "snake", "moon", "harmony", "hook", "phoenix", "earth", "heaven");
    static final List<String> SPIRIT_REVERSE =
            List.of("chief", "heaven", "earth", "tortoise", "tiger", "harmony", "moon", "snake");

    static final List<Integer> PERIOD_LUCK = List.of(6, 7, 8, 9, 1, 2, 3, 4, 5);
    static final List<Integer> ONE_TO_NINE = List.of(1, 2, 3, 4, 5, 6, 7, 8, 9);

    static final List<String> BRANCH_ORDERING =
            List.of("zi", "chou", "yin", "mao", "chen", "si", "wu", "wei", "shen", "you", "xu", "hai");

    /** appendQiMenMeta natal positions table (palace -> natal star). */
    static final Map<String, String> NATAL_STARS = Map.of(
            "qian", "heart", "kan", "grass", "gen", "ambassador", "zhen", "destructor",
            "xun", "assistant", "li", "hero", "kun", "grain", "dui", "pillar");

    static final Map<Integer, String> DOOR_STRENGTH_SCORE =
            Map.of(8, "peak", 7, "strong", 6, "weak", 5, "trap", 4, "dead");

    static final Map<String, List<String>> COUNTERING_PALACES = Map.of(
            "qian", List.of("li"),
            "kan", List.of("kun", "gen"),
            "gen", List.of("zhen", "xun"),
            "zhen", List.of("dui", "qian"),
            "xun", List.of("dui", "qian"),
            "li", List.of("kan"),
            "kun", List.of("zhen", "xun"),
            "dui", List.of("li"));

    /** Plot options mirroring the QiMap.plot arguments. */
    public static final class PlotOptions {
        public String reference = "hour";
        public int starPath = 0;
        public int method = 0;
        public boolean shiftLateZiStream = true;
        /**
         * RN {@code houseInput} object ({@code {houseYear, houseDegree, doorDegree}});
         * null when the caller did not pass one. Falsy fields disable the block
         * exactly like the RN optional chaining.
         */
        public Map<String, Object> houseInput = null;
        public boolean appendDestiny = false;
        public boolean appendMeta = true;
        /**
         * RN screens always pass {@code appendStyle: true}; the style block is
         * additive presentation metadata (strength fonts, interaction icons and
         * the spirit/bird blocks) and requires the meta layer.
         */
        public boolean appendStyle = true;
    }

    private final ResourceJson resources;
    private final QimenService qimen;
    private final FengShuiService fengshui;

    public QimenChartService(ResourceJson resources, QimenService qimen, FengShuiService fengshui) {
        this.resources = resources;
        this.qimen = qimen;
        this.fengshui = fengshui;
    }

    private JsonNode tables() {
        return resources.load("qimen-data.json");
    }

    private JsonNode baziData() {
        return resources.load("bazi-data.json");
    }

    // ------------------------------------------------------------------ plot

    /**
     * QiMap.plot(): the QMDJ chart payload ({@code {palaces, details, bazi}})
     * or {@code null} where RN returns undefined / crashes on invalid input.
     */
    public Map<String, Object> plot(Map<String, Object> bazi, PlotOptions o) {
        if (!REFERENCES.contains(o.reference)) {
            throw new IllegalArgumentException("unknown reference: " + o.reference);
        }
        if (o.starPath != 0 && o.starPath != 1) {
            // RN leaves every star undefined and then crashes in the meta layer.
            throw new IllegalArgumentException("starPath must be 0 or 1");
        }

        String ref = o.reference;
        boolean hasHouse = houseComplete(o.houseInput);

        if ("house".equals(ref) && !hasHouse) {
            return null; // RN: return null
        }

        String dun = null;
        Object ju = null;
        String refStemName = null;
        String refBranchName = null;
        boolean lateZiStreamShifted = false;
        Integer housePeriod = null;
        String houseStemBranch = null;

        if (!"house".equals(ref)) {
            Map<String, Object> refPillar = child(child(bazi, "pillars"), ref);
            if (refPillar == null) return null; // hour-unknown hour reference: RN TypeError
            refStemName = leafName(refPillar, "stem");
            refBranchName = leafName(refPillar, "branch");
            if (refStemName == null || refBranchName == null) return null;

            if (o.method == 1 && "hour".equals(ref)) {
                Map<String, Object> dat = qimen.chaiBu(bazi);
                if (dat == null) return null;
                dun = (String) dat.get("dun");
                ju = dat.get("ju");
            } else {
                Map<String, Object> dat = qimen.zhiRun(ref, bazi);
                if (dat == null) return null; // RN `if(!dat) return;`
                dun = (String) dat.get("dun");
                ju = dat.get("ju");
            }

            if (o.shiftLateZiStream && "hour".equals(ref) && o.starPath == 0) {
                if (baziHour(bazi) == 23) {
                    lateZiStreamShifted = true;
                    refStemName = switch (refStemName) {
                        case "jia" -> "ren";
                        case "bing" -> "jia";
                        case "wu" -> "bing";
                        case "geng" -> "wu";
                        case "ren" -> "geng";
                        default -> refStemName;
                    };
                }
            }
        }

        if (hasHouse) {
            dun = "yang";
            int year = ((Number) o.houseInput.get("houseYear")).intValue();
            double doorDegree = ((Number) o.houseInput.get("doorDegree")).doubleValue();
            double houseDegree = ((Number) o.houseInput.get("houseDegree")).doubleValue();

            Integer period = fengshui.yearToPeriod(year);
            if (period == null) {
                // RN: qiMenFengShuiMap[null] -> TypeError
                throw new IllegalArgumentException("house year outside periods 1844-2083: " + year);
            }
            ju = period;

            String doorSector = fengshui.degreeToMountain(doorDegree);
            String faceSector = fengshui.degreeToMountain(houseDegree);
            if (doorSector == null || faceSector == null) {
                // RN: undefined mountain -> TypeError
                throw new IllegalArgumentException(
                        "degree falls between mountains: door=" + doorDegree + " house=" + houseDegree);
            }
            String faceDir = faceSector.replaceAll("[0-9]", "");
            JsonNode branchNumNode = fengshui.qiMenFengShuiMap()
                    .path(String.valueOf(period)).path(doorSector).path(faceDir);
            if (branchNumNode.isMissingNode() || branchNumNode.isNull()) {
                throw new IllegalArgumentException(
                        "no qi men feng shui entry for period " + period + " / " + doorSector + " / " + faceDir);
            }
            JsonNode pillar = tables().get("pillarOrdering").get(wrapIndex(60, branchNumNode.asInt()));
            refStemName = pillar.get(0).asText();
            refBranchName = pillar.get(1).asText();
            housePeriod = period;
            houseStemBranch = refStemName + " " + refBranchName;
        }

        int juInt;
        try {
            juInt = ju instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(ju));
        } catch (NumberFormatException e) {
            return null; // "0-undefined" ju: RN builds NaN indices and crashes
        }

        JsonNode streams = baziData().get("streams");
        String jiaGroup = deepText(baziData().get("sixtyPillars"), refStemName, refBranchName, "stream");
        if (jiaGroup == null) return null; // RN TypeError on sixtyPillars[stem][branch]
        JsonNode stream = streams.get(jiaGroup);
        if (stream == null) return null;

        String horseBranch = baziData().get("dayBranchAux").get("skyHorse")
                .get(BRANCH_ORDERING.indexOf(refBranchName)).asText();
        String horsePalace = branchPalace(horseBranch);
        List<String> voidPalaces = new ArrayList<>();
        for (JsonNode b : stream.get("void")) {
            voidPalaces.add(branchPalace(b.asText()));
        }

        Map<String, Map<String, Object>> palaces = new LinkedHashMap<>();
        for (String palace : PALACES_LUO_SHU) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("plot", new LinkedHashMap<String, Object>());
            entry.put("meta", new LinkedHashMap<String, Object>());
            palaces.put(palace, entry);
        }

        // ---- earth plate ----
        int beginPalaceIndex = juInt - 1;
        for (int index = 0; index < EARTH_STEMS.size(); index++) {
            int palIndex = "yang".equals(dun)
                    ? index + beginPalaceIndex
                    : PALACES_LUO_SHU.size() - index + beginPalaceIndex;
            plotOf(palaces, wrap(PALACES_LUO_SHU, palIndex)).put("earthStem", EARTH_STEMS.get(index));
        }

        // ---- heaven plate ----
        String leadStem = stream.get("leadStem").asText();
        String refStemEarthPalace = null;
        String leadStemEarthPalace = null;
        for (String pal : PALACES_LUO_SHU) {
            if (Objects.equals(plotOf(palaces, pal).get("earthStem"), refStemName)) refStemEarthPalace = pal;
            if (Objects.equals(plotOf(palaces, pal).get("earthStem"), leadStem)) leadStemEarthPalace = pal;
        }
        boolean heavenIsCenter = false;
        boolean leadStemIsCenter = false;
        if ("center".equals(refStemEarthPalace)) {
            heavenIsCenter = true;
            refStemEarthPalace = "kun";
        }
        if ("center".equals(leadStemEarthPalace)) {
            leadStemIsCenter = true;
            leadStemEarthPalace = "kun";
        }
        if (refStemEarthPalace == null) {
            refStemEarthPalace = leadStemEarthPalace; // jia hour
        }

        int heavenLeadPalaceDiff = 8 - (PALACES_CIRCULAR.indexOf(refStemEarthPalace)
                - PALACES_CIRCULAR.indexOf(leadStemEarthPalace));
        for (int index = 0; index < PALACES_CIRCULAR.size(); index++) {
            String pal = PALACES_CIRCULAR.get(index);
            String heavenStem = (String) plotOf(palaces,
                    wrap(PALACES_CIRCULAR, index + heavenLeadPalaceDiff)).get("earthStem");
            plotOf(palaces, pal).put("heavenStem", heavenStem);
        }
        plotOf(palaces, "center").put("heavenStem", plotOf(palaces, "center").get("earthStem"));

        // ---- doors ----
        int leadStemPalaceLuoShuIndex = PALACES_LUO_SHU.indexOf(leadStemEarthPalace);
        int leadStemPalaceLuoShuTrueIndex = leadStemIsCenter ? 4 : leadStemPalaceLuoShuIndex;
        int leadStemPalaceCircularIndex = PALACES_CIRCULAR.indexOf(leadStemEarthPalace);
        String leadDoor = wrap(DOOR_CIRCULAR_FLOW, leadStemPalaceCircularIndex);

        List<JsonNode> pillars = new ArrayList<>();
        stream.get("pillars").forEach(pillars::add);
        int doorStreamEndpoint = -1;
        for (int i = 0; i < pillars.size(); i++) {
            if (Objects.equals(pillars.get(i).get(0).asText(), refStemName)) {
                doorStreamEndpoint = i;
                break;
            }
        }
        int leadDoorBeginPalaceLuoShuIndex = "yang".equals(dun)
                ? wrap(ONE_TO_NINE, leadStemPalaceLuoShuTrueIndex + doorStreamEndpoint)
                : wrap(ONE_TO_NINE, leadStemPalaceLuoShuTrueIndex - doorStreamEndpoint);
        String leadDoorStartingPalace = wrap(PALACES_LUO_SHU, leadDoorBeginPalaceLuoShuIndex - 1);
        int leadDoorStartingPalaceCircularIndex = PALACES_CIRCULAR.indexOf(leadDoorStartingPalace);
        if ("center".equals(leadDoorStartingPalace)) {
            leadDoorStartingPalace = "kun";
            leadDoorStartingPalaceCircularIndex = 5;
            leadDoorBeginPalaceLuoShuIndex = 2;
        }

        int hourStemEarthPalaceCircularIndex = PALACES_CIRCULAR.indexOf(refStemEarthPalace);
        int doorOffset = leadDoorStartingPalaceCircularIndex - leadStemPalaceCircularIndex;
        for (int index = 0; index < PALACES_CIRCULAR.size(); index++) {
            plotOf(palaces, PALACES_CIRCULAR.get(index)).put("door", wrap(DOOR_CIRCULAR_FLOW, index - doorOffset));
        }

        // ---- stars ----
        String leadStar = null;
        if (o.starPath == 0) {
            leadStar = wrap(STAR_CIRCULAR_FLOW, leadStemPalaceCircularIndex);
            for (int index = 0; index < PALACES_CIRCULAR.size(); index++) {
                String star = wrap(STAR_CIRCULAR_FLOW,
                        index - (hourStemEarthPalaceCircularIndex - leadStemPalaceCircularIndex));
                plotOf(palaces, PALACES_CIRCULAR.get(index)).put("star", star);
            }
            plotOf(palaces, "center").put("star", "bird");
        } else {
            leadStar = leadStemIsCenter ? "bird" : wrap(STAR_CIRCULAR_FLOW, leadStemPalaceCircularIndex);
            int leadStarIndex = STAR_LUOSHU_FLOW.indexOf(leadStar);
            Integer starPlacementIndex = null;
            Object lookup = leadStemIsCenter ? plotOf(palaces, "kun").get("earthStem") : leadStem;
            for (int index = 0; index < PALACES_LUO_SHU.size(); index++) {
                if (Objects.equals(plotOf(palaces, PALACES_LUO_SHU.get(index)).get("heavenStem"), lookup)) {
                    starPlacementIndex = index;
                }
            }
            if (Objects.equals(refStemName, plotOf(palaces, "center").get("heavenStem"))) {
                starPlacementIndex = 4;
            }
            if (starPlacementIndex == null) {
                // unreachable: every heaven stem is placed exactly once
                throw new IllegalStateException("starPath=1 could not place the lead star");
            }
            int shift = starPlacementIndex - leadStarIndex;
            for (int index = 0; index < PALACES_LUO_SHU.size(); index++) {
                plotOf(palaces, PALACES_LUO_SHU.get(index)).put("star", wrap(STAR_LUOSHU_FLOW, index - shift));
            }
        }

        // ---- spirits ----
        List<String> spiritFlow = "yang".equals(dun) ? SPIRIT_FORWARD : SPIRIT_REVERSE;
        for (int index = 0; index < PALACES_CIRCULAR.size(); index++) {
            String spirit = wrap(spiritFlow, index - hourStemEarthPalaceCircularIndex);
            plotOf(palaces, PALACES_CIRCULAR.get(index)).put("spirit", spirit);
        }

        // ---- period luck + palace decorations ----
        for (int index = 0; index < PALACES_LUO_SHU.size(); index++) {
            plotOf(palaces, PALACES_LUO_SHU.get(index)).put("periodNum", wrap(PERIOD_LUCK, juInt - 1 + index));
        }
        for (String pal : PALACES_LUO_SHU) {
            Map<String, Object> plot = plotOf(palaces, pal);
            boolean isLeadDoor = Objects.equals(leadDoor, plot.get("door"));
            plot.put("isLeadDoor", isLeadDoor);
            plot.put("isLeadStar", isLeadDoor); // RN quirk: compares leadDoor again
            plot.put("name", pal);
            plot.put("palace", pal);
            plot.put("number", PALACES_LUO_SHU.indexOf(pal) + 1);
            plot.put("void", voidPalaces.contains(pal));
            plot.put("horse", pal.equals(horsePalace));
            plot.put("hiddenEarthStem", "kun".equals(pal)
                    ? plotOf(palaces, "center").get("earthStem") : null);
            plot.put("hiddenHeavenStem", Objects.equals(plot.get("heavenStem"),
                    plotOf(palaces, "kun").get("earthStem"))
                    ? plotOf(palaces, "center").get("earthStem") : null);
            plot.put("isEarthLeadStem", Objects.equals(leadStem, plot.get("earthStem"))
                    || Objects.equals(leadStem, plot.get("hiddenEarthStem")));
            plot.put("isHeavenLeadStem", Objects.equals(leadStem, plot.get("heavenStem"))
                    || Objects.equals(leadStem, plot.get("hiddenHeavenStem")));
            plot.put("bird", "grain".equals(plot.get("star")));
        }

        // ---- details ----
        Map<String, Object> forecastStems = null;
        if ("hour".equals(ref)) {
            forecastStems = new LinkedHashMap<>();
            forecastStems.put("hour", forecastStem(bazi, "hour"));
            forecastStems.put("day", forecastStem(bazi, "day"));
        }

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("forecastStems", forecastStems);
        details.put("dun", dun);
        details.put("ju", ju);
        details.put("method", o.method);
        details.put("starPath", o.starPath);
        details.put("leadDoor", leadDoor);
        details.put("leadStar", leadStar);
        details.put("leadStem", leadStem);
        details.put("reference", ref);
        details.put("shiftLateZiStream", o.shiftLateZiStream);
        details.put("lateZiStreamShifted", lateZiStreamShifted);
        details.put("housePeriod", housePeriod);
        details.put("houseStemBranch", houseStemBranch);
        details.put("houseInput", o.houseInput);
        details.put("grainIsBird", leadStemIsCenter);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("palaces", palaces);
        payload.put("details", details);
        payload.put("bazi", bazi);

        if (o.appendMeta) appendQiMenMeta(payload);
        if (o.appendDestiny) appendQiMenDestiny(payload);
        if (o.appendStyle && o.appendMeta) appendStyleLayer(payload);
        return payload;
    }

    // ------------------------------------------------------------ meta layer

    /** QiMap.appendQiMenMeta(): victories, per-palace meta, structures. */
    public void appendQiMenMeta(Map<String, Object> chart) {
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> palaces = (Map<String, Map<String, Object>>) chart.get("palaces");
        Map<String, Object> details = detailsOf(chart);
        Map<String, Object> bazi = baziOf(chart);
        Map<String, Object> pillars = child(bazi, "pillars");

        // ---- victories ----
        Map<String, Object> victories = new LinkedHashMap<>();
        victories.put("light", null);
        victories.put("blessing", null);
        victories.put("force", null);
        String lightLookupStar = null;
        for (Map.Entry<String, Map<String, Object>> e : palaces.entrySet()) {
            String name = e.getKey();
            Map<String, Object> plot = plotOf(e.getValue());
            if ("life".equals(plot.get("door"))) victories.put("force", name);
            if ("heaven".equals(plot.get("spirit"))) victories.put("blessing", name);
            if ("chief".equals(plot.get("spirit"))) lightLookupStar = NATAL_STARS.get(name);
        }
        for (String name : palaces.keySet()) {
            if (Objects.equals(plotOf(palaces.get(name)).get("star"), lightLookupStar)) {
                victories.put("light", name);
            }
        }
        details.put("victories", victories);

        JsonNode doors = tables().get("doors");
        JsonNode stars = tables().get("stars");
        JsonNode hours = tables().get("hours");
        JsonNode formations = tables().get("formations");
        JsonNode structures = tables().get("structures");
        int solarTermNum = solarTermNum(bazi);
        String hourStem = leafName(child(pillars, "hour"), "stem");

        for (Map.Entry<String, Map<String, Object>> e : palaces.entrySet()) {
            String name = e.getKey();
            if ("center".equals(name)) continue;
            Map<String, Object> plot = plotOf(e.getValue());
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("score", 0);

            List<String> heavenStems = new ArrayList<>();
            heavenStems.add((String) plot.get("heavenStem"));
            if (Boolean.TRUE.equals(plot.get("isHeavenLeadStem"))) heavenStems.add("jia");
            if (plot.get("hiddenHeavenStem") != null) heavenStems.add((String) plot.get("hiddenHeavenStem"));

            List<String> earthStems = new ArrayList<>();
            earthStems.add((String) plot.get("earthStem"));
            if (Boolean.TRUE.equals(plot.get("isEarthLeadStem"))) earthStems.add("jia");
            if (plot.get("hiddenEarthStem") != null) earthStems.add((String) plot.get("hiddenEarthStem"));

            meta.put("heavenStems", heavenStems);
            meta.put("earthStems", earthStems);

            List<Map<String, Object>> metaFormations = new ArrayList<>();
            for (String hs : heavenStems) {
                for (String es : earthStems) {
                    Map<String, Object> formation = new LinkedHashMap<>();
                    formation.put("heavenStem", hs);
                    formation.put("earthStem", es);
                    formation.put("formation", DataStore.deep(formations.get(hs).get(es)));
                    metaFormations.add(formation);
                }
            }
            meta.put("formations", metaFormations);

            String door = (String) plot.get("door");
            JsonNode doorNode = doors.get(door);
            Map<String, Object> doorMeta = new LinkedHashMap<>();
            putIfPresent(doorMeta, "specialInteraction", text(doorNode.get("specialInteraction"), name));
            putIfPresent(doorMeta, "palaceInteraction", text(doorNode.get("palaceInteraction"), name));
            JsonNode doorStrength = doorNode.get("strength").get(String.valueOf(solarTermNum));
            putIfPresent(doorMeta, "strength",
                    doorStrength == null ? null : DOOR_STRENGTH_SCORE.get(doorStrength.asInt()));
            putIfPresent(doorMeta, "desc", text(doorNode.get("desc"), null));
            meta.put("door", doorMeta);

            String star = (String) plot.get("star");
            JsonNode starNode = stars.get(star);
            Map<String, Object> starMeta = new LinkedHashMap<>();
            putIfPresent(starMeta, "specialInteraction", text(starNode.get("specialInteraction"), name));
            putIfPresent(starMeta, "palaceInteraction", text(starNode.get("palaceInteraction"), name));
            putIfPresent(starMeta, "strength", text(starNode.get("strength"), name));
            meta.put("star", starMeta);

            Map<String, Object> hourMeta = new LinkedHashMap<>();
            JsonNode hourEntry = hourStem == null ? null : hours.get(hourStem);
            if (hourEntry != null) {
                JsonNode interaction = hourEntry.get(door);
                if (interaction != null) hourMeta.put("doorInteraction", DataStore.deep(interaction));
            }
            meta.put("hour", hourMeta);

            meta.put("structuresArray", new ArrayList<Map<String, Object>>());
            Map<String, List<Map<String, Object>>> structuresMap = new LinkedHashMap<>();
            structuresMap.put("positive", new ArrayList<>());
            structuresMap.put("neutral", new ArrayList<>());
            structuresMap.put("negative", new ArrayList<>());
            meta.put("structuresMap", structuresMap);

            appendStructures(meta, plot, pillars, structures);

            e.getValue().put("meta", meta);
        }
    }

    /** The structure matcher loop (matchedRule keeps the RN JSON.stringify form). */
    private void appendStructures(Map<String, Object> meta, Map<String, Object> plot,
                                  Map<String, Object> pillars, JsonNode structures) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> structuresArray = (List<Map<String, Object>>) meta.get("structuresArray");
        @SuppressWarnings("unchecked")
        Map<String, List<Map<String, Object>>> structuresMap =
                (Map<String, List<Map<String, Object>>>) meta.get("structuresMap");

        Iterator<Map.Entry<String, JsonNode>> it = structures.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            String sKey = e.getKey();
            JsonNode structure = e.getValue();

            String matchedRule = null;
            for (JsonNode rule : structure.get("match")) {
                boolean match = true;
                Iterator<Map.Entry<String, JsonNode>> fields = rule.fields();
                while (fields.hasNext() && match) {
                    Map.Entry<String, JsonNode> f = fields.next();
                    String field = f.getKey();
                    JsonNode raw = f.getValue();
                    if (raw.isBoolean()) {
                        match = Objects.equals(plot.get(field), raw.asBoolean());
                        continue;
                    }
                    String val = raw.asText();
                    val = switch (val) {
                        case "$yearStem" -> leafName(child(pillars, "year"), "stem");
                        case "$monthStem" -> leafName(child(pillars, "month"), "stem");
                        case "$dayStem" -> leafName(child(pillars, "day"), "stem");
                        case "$hourStem" -> leafName(child(pillars, "hour"), "stem");
                        default -> val;
                    };
                    if ("heavenStem".equals(field)) {
                        if (!Objects.equals(plot.get("heavenStem"), val)
                                && !Objects.equals(plot.get("hiddenHeavenStem"), val)) {
                            match = false;
                        }
                    } else if ("earthStem".equals(field)) {
                        if (!Objects.equals(plot.get("earthStem"), val)
                                && !Objects.equals(plot.get("hiddenEarthStem"), val)) {
                            match = false;
                        }
                    } else if (!Objects.equals(plot.get(field), val)) {
                        match = false;
                    }
                }
                if (match) {
                    // RN: JSON.stringify(rule) - key order and types are kept
                    matchedRule = rule.toString();
                    break;
                }
            }
            if (matchedRule == null) continue;

            int rating = structure.get("rating").asInt();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("name", sKey);
            result.put("rating", rating);
            result.put("matchedRule", matchedRule);
            structuresArray.add(result);
            if (rating > 0) structuresMap.get("positive").add(result);
            if (rating == 0) structuresMap.get("neutral").add(result);
            if (rating < 0) structuresMap.get("negative").add(result);
        }
    }

    // ----------------------------------------------------------- style layer

    /**
     * QiMap.styleQimenChart(): add the presentation block the RN screens render.
     *
     * <p>Quirks preserved: {@code suitable}/{@code unsuitable} are read from the
     * top level of the MysticDoors tables (they only exist under
     * {@code details}), so RN assigns {@code undefined} and JSON.stringify drops
     * the keys - {@code meta.bird} serialises as {@code {}} and
     * {@code meta.spirit} as {@code {"style":{}}}. The spirit block is created
     * here, after meta/destiny, exactly like RN.
     */
    private void appendStyleLayer(Map<String, Object> payload) {
        Map<String, Object> palaces = child(payload, "palaces");
        Map<String, Object> details = child(payload, "details");
        if (palaces == null || details == null) return;

        for (String name : palaces.keySet()) {
            if ("center".equals(name)) continue;

            Map<String, Object> palace = child(palaces, name);
            Map<String, Object> meta = child(palace, "meta");
            Map<String, Object> plot = child(palace, "plot");
            if (meta == null || plot == null) continue;

            Map<String, Object> doorMeta = child(meta, "door");
            if (doorMeta != null) {
                doorMeta.put("style", strengthStyle((String) doorMeta.get("strength")));
                doorMeta.put("palaceInteractionIcon",
                        palaceInteractionIcon((String) doorMeta.get("palaceInteraction")));
                doorMeta.put("specialInteractionIcon",
                        "grave".equals(doorMeta.get("specialInteraction")) ? "christianity" : null);
            }

            Map<String, Object> starMeta = child(meta, "star");
            if (starMeta != null) {
                starMeta.put("style", strengthStyle((String) starMeta.get("strength")));
                starMeta.put("palaceInteractionIcon",
                        palaceInteractionIcon((String) starMeta.get("palaceInteraction")));
            }

            // RN: if (plot.bird) meta.bird = { Suitable: undefined, Unsuitable: undefined } -> {}
            if (Boolean.TRUE.equals(plot.get("bird"))) {
                meta.put("bird", new LinkedHashMap<String, Object>());
            }

            Map<String, Object> spirit = new LinkedHashMap<>();
            spirit.put("style", new LinkedHashMap<String, Object>());
            meta.put("spirit", spirit);
        }
    }

    /** styleQimenChart's strength switch. */
    private static Map<String, Object> strengthStyle(String strength) {
        Map<String, Object> style = new LinkedHashMap<>();
        if (strength == null) return style;
        switch (strength) {
            case "dead":
                style.put("textDecorationLine", "line-through");
                break;
            case "trap":
                style.put("fontStyle", "italic");
                break;
            case "weak":
                style.put("fontWeight", "normal");
                break;
            case "strong":
                style.put("fontWeight", "500");
                break;
            case "peak":
                style.put("fontWeight", "800");
                break;
            default:
                break;
        }
        return style;
    }

    /** styleQimenChart's palace-interaction icon switch (Material icon names). */
    private static String palaceInteractionIcon(String interaction) {
        if (interaction == null) return "";
        switch (interaction) {
            case "harmony":
                return "sync";
            case "produce":
                return "redo";
            case "produced":
                return "undo";
            case "countered":
                return "arrow-bottom-left";
            case "compelled":
                return "arrow-top-right";
            default:
                return "";
        }
    }

    // --------------------------------------------------------- destiny layer

    /** QiMap.appendQiMenDestiny(): day-chart lead stem + per-palace destiny lines. */
    public void appendQiMenDestiny(Map<String, Object> hourChart) {
        Map<String, Object> bazi = baziOf(hourChart);
        PlotOptions dayOptions = new PlotOptions();
        dayOptions.reference = "day";
        Map<String, Object> dayChart = plot(bazi, dayOptions);
        if (dayChart == null) {
            // RN crashes on dayChart.details of undefined; only reachable for
            // pre-1900 charts which already fail the hour/day ju lookup.
            throw new IllegalStateException("no day chart for destiny");
        }
        String dayLeadStem = (String) detailsOf(dayChart).get("leadStem");

        Map<String, Object> details = detailsOf(hourChart);
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Object>> palaces = (Map<String, Map<String, Object>>) hourChart.get("palaces");
        Map<String, Object> pillars = child(bazi, "pillars");

        String hourStem = leafName(child(pillars, "hour"), "stem");
        String dayStem = leafName(child(pillars, "day"), "stem");
        String dayBranch = leafName(child(pillars, "day"), "branch");
        String monthStem = leafName(child(pillars, "month"), "stem");
        String yearStem = leafName(child(pillars, "year"), "stem");
        String dayBranchPalace = dayBranch == null ? null : branchPalace(dayBranch);

        String destinyPalace = null;
        for (Map.Entry<String, Map<String, Object>> e : palaces.entrySet()) {
            String name = e.getKey();
            if ("center".equals(name)) continue;
            Map<String, Object> plot = plotOf(e.getValue());
            Map<String, Object> destiny = new LinkedHashMap<>();
            List<String> labels = new ArrayList<>();
            destiny.put("labels", labels);

            switch (name) {
                case "qian" -> destiny.put("range", List.of(0, 15));
                case "kan" -> destiny.put("range", List.of(16, 30));
                case "gen" -> destiny.put("range", List.of(31, 45));
                case "zhen" -> destiny.put("range", List.of(46, 60));
                case "xun" -> destiny.put("range", List.of(61, 75));
                case "li" -> destiny.put("range", List.of(76, 90));
                case "kun" -> destiny.put("range", List.of(91, 105));
                case "dui" -> destiny.put("range", List.of(106, 120));
                default -> { }
            }

            switch ((String) plot.get("door")) {
                case "open" -> labels.add("career");
                case "life" -> { labels.add("wealth"); labels.add("business"); }
                case "rest" -> { labels.add("retirement"); labels.add("family"); }
                case "delusion" -> labels.add("knowledge");
                case "harm" -> { labels.add("risk"); labels.add("business"); }
                case "fear" -> labels.add("wellness");
                case "death" -> labels.add("assets");
                case "scenery" -> labels.add("reputation");
                default -> { }
            }
            switch ((String) plot.get("star")) {
                case "assistant" -> labels.add("education");
                case "grain" -> labels.add("health");
                default -> { }
            }
            switch ((String) plot.get("spirit")) {
                case "snake" -> labels.add("karmic");
                case "harmony" -> labels.add("relationship");
                default -> { }
            }

            if (Objects.equals(dayLeadStem, plot.get("heavenStem"))
                    || Objects.equals(dayLeadStem, plot.get("hiddenHeavenStem"))) {
                labels.add("soul");
            }
            if (Objects.equals(name, dayBranchPalace)) {
                labels.add("subconscious");
            }
            if (Objects.equals(plot.get("heavenStem"), hourStem)
                    || Objects.equals(plot.get("hiddenHeavenStem"), hourStem)
                    || ("jia".equals(hourStem) && Boolean.TRUE.equals(plot.get("isHeavenLeadStem")))) {
                labels.add("children");
            }
            if (Objects.equals(plot.get("heavenStem"), monthStem)
                    || Objects.equals(plot.get("hiddenHeavenStem"), monthStem)
                    || ("jia".equals(monthStem) && Boolean.TRUE.equals(plot.get("isHeavenLeadStem")))) {
                labels.add("siblings");
            }
            if (Objects.equals(plot.get("heavenStem"), yearStem)
                    || Objects.equals(plot.get("hiddenHeavenStem"), yearStem)
                    || ("jia".equals(yearStem) && Boolean.TRUE.equals(plot.get("isHeavenLeadStem")))) {
                labels.add("parent");
            }

            if (("jia".equals(dayStem) && (Objects.equals(dayLeadStem, plot.get("heavenStem"))
                    || Objects.equals(dayLeadStem, plot.get("hiddenHeavenStem"))))
                    || Objects.equals(dayStem, plot.get("heavenStem"))
                    || Objects.equals(dayStem, plot.get("hiddenHeavenStem"))) {
                destiny.put("isDestinyPalace", true);
                destinyPalace = name;
                details.put("destinyPalace", name);
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> meta = (Map<String, Object>) e.getValue().get("meta");
            meta.put("destiny", destiny);
        }

        if (destinyPalace == null) {
            // RN logs "No destiny palace" and then throws on counteringPalaces[null]
            log.warn("no destiny palace for {}", baziHourTime(bazi));
            return;
        }
        for (String name : COUNTERING_PALACES.get(destinyPalace)) {
            if ("center".equals(name)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> meta = (Map<String, Object>) palaces.get(name).get("meta");
            @SuppressWarnings("unchecked")
            Map<String, Object> destiny = (Map<String, Object>) meta.get("destiny");
            @SuppressWarnings("unchecked")
            List<String> labels = (List<String>) destiny.get("labels");
            labels.add("obstruction");
        }
    }

    // ---------------------------------------------------------------- helpers

    /** RN Tools.toProxy index wrap. */
    static <T> T wrap(List<T> arr, int index) {
        return arr.get(wrapIndex(arr.size(), index));
    }

    static int wrapIndex(int length, int index) {
        int m = index % length;
        if (m < 0) m += length;
        return m;
    }

    private static boolean houseComplete(Map<String, Object> houseInput) {
        if (houseInput == null) return false;
        return truthy(houseInput.get("houseYear"))
                && truthy(houseInput.get("houseDegree"))
                && truthy(houseInput.get("doorDegree"));
    }

    private static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Number n) return n.doubleValue() != 0;
        if (v instanceof Boolean b) return b;
        return !String.valueOf(v).isEmpty();
    }

    private String forecastStem(Map<String, Object> bazi, String key) {
        Map<String, Object> pillar = child(child(bazi, "pillars"), key);
        String stem = pillar == null ? null : leafName(pillar, "stem");
        if ("jia".equals(stem)) {
            String branch = leafName(pillar, "branch");
            String group = deepText(baziData().get("sixtyPillars"), stem, branch, "stream");
            return group == null ? null : baziData().get("streams").get(group).get("leadStem").asText();
        }
        return stem;
    }

    private String branchPalace(String branch) {
        JsonNode node = baziData().get("branches").get(branch);
        return node == null ? null : node.get("palace").asText();
    }

    private int solarTermNum(Map<String, Object> bazi) {
        Map<String, Object> data = child(child(child(child(bazi, "info"), "solarTerm"), "thisTerm"), "data");
        return data == null ? 0 : ((Number) data.get("num")).intValue();
    }

    private int baziHour(Map<String, Object> bazi) {
        return OffsetDateTime.parse((String) child(bazi, "info").get("time")).getHour();
    }

    private String baziHourTime(Map<String, Object> bazi) {
        return String.valueOf(child(bazi, "info").get("time"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> plotOf(Map<String, Map<String, Object>> palaces, String name) {
        return plotOf(palaces.get(name));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> plotOf(Map<String, Object> palace) {
        Map<String, Object> plot = (Map<String, Object>) palace.get("plot");
        return plot == null ? new LinkedHashMap<>() : plot;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> detailsOf(Map<String, Object> chart) {
        return (Map<String, Object>) chart.get("details");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> baziOf(Map<String, Object> chart) {
        return (Map<String, Object>) chart.get("bazi");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> child(Map<String, Object> map, String key) {
        if (map == null) return null;
        Object o = map.get(key);
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    private static String leafName(Map<String, Object> part, String key) {
        Map<String, Object> p = child(part, key);
        if (p == null) return null;
        Object name = p.get("name");
        return name == null ? null : String.valueOf(name);
    }

    private static String deepText(JsonNode root, String... path) {
        JsonNode n = root;
        for (String p : path) {
            if (n == null) return null;
            n = n.get(p);
        }
        return n == null || !n.isTextual() ? null : n.asText();
    }

    /** RN object lookup that keeps undefined (missing) distinct from null. */
    private static String text(JsonNode node, String key) {
        if (node == null) return null;
        JsonNode v = key == null ? node : node.get(key);
        return v == null || !v.isTextual() ? null : v.asText();
    }

    private static void putIfPresent(Map<String, Object> map, String key, String value) {
        if (value != null) map.put(key, value);
    }
}
