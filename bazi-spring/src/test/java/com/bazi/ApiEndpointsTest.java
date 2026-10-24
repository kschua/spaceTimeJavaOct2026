package com.bazi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * MockMvc smoke tests for every HTTP endpoint (happy path + error paths) and
 * the static pages. Complements the golden-replay service tests, which cover
 * the算命 values; this suite covers wiring, parameters, status codes and the
 * agreed null-safety/validation behavior at the HTTP boundary.
 *
 * <p>Small-hole coverage: POST /api/lisheeyee, invalid binary (400), unknown
 * pillar lookups (empty), JS-style trailing-text number parsing, non-+08
 * offsets (via the Feng Shui time endpoint), and validation errors.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiEndpointsTest {

    private static final ObjectMapper OM = new ObjectMapper();
    private static final String TIME = "1990-05-15T14:30:00+08:00";

    @Autowired
    private MockMvc mvc;

    private JsonNode json(MvcResult r) throws Exception {
        return OM.readTree(r.getResponse().getContentAsString());
    }

    // ------------------------------------------------------------- Bazi

    @Test
    void baziChart() throws Exception {
        mvc.perform(get("/api/bazi").param("time", TIME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pillars.year.stem.name").value("geng"))
                .andExpect(jsonPath("$.pillars.year.branch.name").value("wu"))
                .andExpect(jsonPath("$.pillars.day.stem.name").value("geng"))
                .andExpect(jsonPath("$.pillars.day.branch.name").value("chen"))
                .andExpect(jsonPath("$.luck").isArray())
                .andExpect(jsonPath("$.meta.counts").exists())
                .andExpect(jsonPath("$.shensha").isArray())
                .andExpect(jsonPath("$.shensha[0].name").exists());

        // flags off -> lighter payload, still valid
        mvc.perform(get("/api/bazi").param("time", TIME)
                        .param("extend", "false")
                        .param("showLuck", "false")
                        .param("showWeights", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pillars.year.stem.name").value("geng"));
    }

    @Test
    void baziInvalidTimeIs400() throws Exception {
        mvc.perform(get("/api/bazi").param("time", "not-a-time"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
        mvc.perform(get("/api/bazi"))
                .andExpect(status().isBadRequest()); // missing required param
    }

    @Test
    void cyclesAndSolstice() throws Exception {
        mvc.perform(get("/api/cycle/major").param("time", TIME).param("type", "month"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/cycle/minor").param("time", TIME).param("type", "month"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.begin").exists())
                .andExpect(jsonPath("$.end").exists());
        mvc.perform(get("/api/solstice").param("time", TIME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.solstice").exists())
                .andExpect(jsonPath("$.time").exists());
    }

    @Test
    void synastry() throws Exception {
        mvc.perform(get("/api/synastry")
                        .param("t1", TIME)
                        .param("t2", "1992-08-20T08:00:00+08:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.basic").exists())
                .andExpect(jsonPath("$.streams").exists())
                .andExpect(jsonPath("$.fuyin").isArray())
                .andExpect(jsonPath("$.fanyin").isArray())
                .andExpect(jsonPath("$.combo").isArray())
                .andExpect(jsonPath("$.balance").exists())
                .andExpect(jsonPath("$.elements").exists());

        mvc.perform(get("/api/synastry").param("t1", "nope").param("t2", TIME))
                .andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------- Almanac

    @Test
    void almanacDayInfo() throws Exception {
        mvc.perform(get("/api/almanac").param("time", TIME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.time").exists())
                .andExpect(jsonPath("$.pillars.year.stem").value("geng"))
                .andExpect(jsonPath("$.pillars.day.branch").value("chen"))
                .andExpect(jsonPath("$.dayInfo.officer").exists())
                .andExpect(jsonPath("$.dayInfo.constellation").exists())
                .andExpect(jsonPath("$.dayInfo.yellowBlack").exists());

        mvc.perform(get("/api/almanac").param("time", TIME).param("lateZi", "true"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/almanac"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void almanacReference() throws Exception {
        mvc.perform(get("/api/almanac/reference"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.officers", hasSize(12)))
                .andExpect(jsonPath("$.twelveOfficerDesc", aMapWithSize(12)))
                .andExpect(jsonPath("$.twentyEightConstellationsDesc", aMapWithSize(28)))
                .andExpect(jsonPath("$.threeKillingsDesc", aMapWithSize(3)));
    }

    // ------------------------------------------------------------ Yi Jing

    @Test
    void hexagramCatalogAndDetail() throws Exception {
        mvc.perform(get("/api/hexagrams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(64)));

        mvc.perform(get("/api/hexagrams/111111"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hexagram.number").value(1))
                .andExpect(jsonPath("$.lineData.branches", hasSize(6)))
                .andExpect(jsonPath("$.mutual.binary").isString());

        mvc.perform(get("/api/hexagrams/111111").param("dayMaster", "geng"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lineData.animals", hasSize(6)));
    }

    @Test
    void invalidBinaryIs400() throws Exception {
        mvc.perform(get("/api/hexagrams/abc123"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/hexagrams/0101"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/hexagrams/0101012"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void pillarMappingAndUnknownPillarEmpty() throws Exception {
        mvc.perform(get("/api/hexagrams/from-pillar").param("stem", "jia").param("branch", "wu"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numbers", not(empty())))
                .andExpect(jsonPath("$.hexagrams", not(empty())));

        // agreed null-safety divergence: RN throws for unknown stems/branches
        mvc.perform(get("/api/hexagrams/from-pillar").param("stem", "zzz").param("branch", "wu"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numbers", empty()))
                .andExpect(jsonPath("$.hexagrams", empty()));
        mvc.perform(get("/api/hexagrams/from-pillar").param("stem", "jia").param("branch", "zzz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.numbers", empty()));
        mvc.perform(get("/api/hexagrams/from-pillar").param("stem", "zzz").param("branch", "zzz").param("horary", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.binary").value(nullValue()))
                .andExpect(jsonPath("$.hexagrams", empty()));

        // horaryTable is missing the gui-you key in the RN data (59/60 keys)
        mvc.perform(get("/api/hexagrams/from-pillar").param("stem", "gui").param("branch", "you").param("horary", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.binary").value(nullValue()))
                .andExpect(jsonPath("$.hexagrams", empty()));
    }

    @Test
    void liSheeYeeGetAndPost() throws Exception {
        MvcResult get = mvc.perform(get("/api/lisheeyee")
                        .param("q", "abc").param("time", TIME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.converted").value("123"))
                .andExpect(jsonPath("$.binary").isString())
                .andReturn();

        MvcResult post = mvc.perform(post("/api/lisheeyee")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"q\":\"abc\",\"time\":\"" + TIME + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(json(get), json(post), "POST must match GET for the same input");

        // no time -> defaults to now; still returns a result
        mvc.perform(post("/api/lisheeyee")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"q\":\"abc\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.time").exists());

        // too-short input: converted only, no hexagram fields
        mvc.perform(get("/api/lisheeyee").param("q", "a").param("time", TIME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.converted").value("1"))
                .andExpect(jsonPath("$.h1").doesNotExist());
    }

    // --------------------------------------------------------- Feng Shui

    @Test
    void fengShuiReference() throws Exception {
        mvc.perform(get("/api/fengshui/reference"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mountains").isMap())
                .andExpect(jsonPath("$.attributes").isMap())
                .andExpect(jsonPath("$.lifeGuas").isMap())
                .andExpect(jsonPath("$.flyingStars").isMap())
                .andExpect(jsonPath("$.flyingStarCombinations").isMap())
                .andExpect(jsonPath("$.eightMansions").isMap())
                .andExpect(jsonPath("$.eightMansionNaJia").exists());
    }

    @Test
    void luopanDegreesAndTrailingText() throws Exception {
        mvc.perform(get("/api/fengshui/luopan").param("degree", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sector").value("N2"));

        // RN parseFloat semantics: trailing text is ignored
        MvcResult plain = mvc.perform(get("/api/fengshui/luopan").param("degree", "90"))
                .andExpect(status().isOk()).andReturn();
        MvcResult trailing = mvc.perform(get("/api/fengshui/luopan").param("degree", "90abc"))
                .andExpect(status().isOk()).andReturn();
        assertEquals(OM.readTree(plain.getResponse().getContentAsByteArray()),
                OM.readTree(trailing.getResponse().getContentAsByteArray()),
                "degree=90abc must equal degree=90");

        // gap band: 200 with nulls (RN crash) - agreed divergence
        mvc.perform(get("/api/fengshui/luopan").param("degree", "7.55"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sector").value(nullValue()))
                .andExpect(jsonPath("$.mountain").value(nullValue()))
                .andExpect(jsonPath("$.attributes").value(nullValue()));

        mvc.perform(get("/api/fengshui/luopan").param("degree", "abc"))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/fengshui/luopan/mountains"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mountains").isMap())
                .andExpect(jsonPath("$.rings.numberInner").isArray());
        mvc.perform(get("/api/fengshui/dragons"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.piercingMountain72").exists())
                .andExpect(jsonPath("$.threeAuspiciousSixElegance").exists());
    }

    @Test
    void personalGuaAndEightMansions() throws Exception {
        mvc.perform(get("/api/fengshui/personal-gua").param("year", "1990"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.personalGua.pinyin").value("kan"))
                .andExpect(jsonPath("$.directions").isMap());

        mvc.perform(get("/api/fengshui/eight-mansions").param("facing", "E"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.facing").value("E"))
                .andExpect(jsonPath("$.palaces", aMapWithSize(8)));
        mvc.perform(get("/api/fengshui/eight-mansions").param("facing", "X"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void timeFlyingStars() throws Exception {
        mvc.perform(get("/api/fengshui/time-flying-stars").param("time", TIME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hour").isMap())
                .andExpect(jsonPath("$.day").isMap())
                .andExpect(jsonPath("$.month").isMap())
                .andExpect(jsonPath("$.year").isMap());

        // non-+08 offsets are accepted (fixed-offset zone semantics)
        mvc.perform(get("/api/fengshui/time-flying-stars").param("time", "2024-06-15T14:30:00-05:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.day").isMap());

        mvc.perform(get("/api/fengshui/time-flying-stars"))
                .andExpect(status().isOk()); // defaults to now
        mvc.perform(get("/api/fengshui/time-flying-stars").param("time", "garbage"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void houseFlyingStarsAndDoorEcho() throws Exception {
        mvc.perform(get("/api/fengshui/house-flying-stars")
                        .param("houseYear", "2024").param("houseDegree", "90"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").value(9))
                .andExpect(jsonPath("$.plates.base").isMap());

        mvc.perform(get("/api/fengshui/house-flying-stars")
                        .param("houseYear", "2024").param("houseDegree", "90").param("doorDegree", "45"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.doorDegree").value(45.0));

        // RN parseInt/parseFloat semantics: trailing text ignored
        mvc.perform(get("/api/fengshui/house-flying-stars")
                        .param("houseYear", "2024abc").param("houseDegree", "90.5xyz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.period").value(9));

        mvc.perform(get("/api/fengshui/house-flying-stars")
                        .param("houseYear", "1984").param("houseDegree", "90").param("doorDegree", "abc"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/fengshui/house-flying-stars")
                        .param("houseYear", "1984").param("houseDegree", "90").param("doorDegree", "361"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/fengshui/house-flying-stars")
                        .param("houseYear", "1863").param("houseDegree", "90"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/fengshui/house-flying-stars")
                        .param("houseYear", "1984").param("houseDegree", "157.55"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------- QMDJ

    @Test
    void qimenJus() throws Exception {
        mvc.perform(get("/api/qimen/jus").param("time", TIME).param("reference", "hour"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.time").exists())
                .andExpect(jsonPath("$.reference").value("hour"))
                .andExpect(jsonPath("$.method").value(0))
                .andExpect(jsonPath("$.dun").value("yang"))
                .andExpect(jsonPath("$.ju").value("5"));
        mvc.perform(get("/api/qimen/jus").param("time", TIME).param("reference", "day"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ju").value("2"));
        mvc.perform(get("/api/qimen/jus").param("time", TIME).param("reference", "year"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dun").value("yin"))
                .andExpect(jsonPath("$.ju").value("1"));

        // chaiBu (method=1, hour) keeps the numeric ju type from the RN tables
        mvc.perform(get("/api/qimen/jus").param("time", TIME)
                        .param("reference", "hour").param("method", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dun").value("yang"))
                .andExpect(jsonPath("$.ju").value(4))
                .andExpect(jsonPath("$.ju").isNumber());

        // table miss: hourJus ends at 2050, so the endpoint answers with nulls
        mvc.perform(get("/api/qimen/jus")
                        .param("time", "2051-01-01T12:00:00+08:00").param("reference", "hour"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dun").value(nullValue()))
                .andExpect(jsonPath("$.ju").value(nullValue()));

        mvc.perform(get("/api/qimen/jus").param("time", TIME).param("reference", "house"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/qimen/jus").param("time", "garbage"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void qimenChart() throws Exception {
        // hour plot, zhiRun ju (string type kept in the payload)
        mvc.perform(get("/api/qimen/chart").param("time", TIME).param("reference", "hour"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.time").exists())
                .andExpect(jsonPath("$.reference").value("hour"))
                .andExpect(jsonPath("$.chart.details.dun").value("yang"))
                .andExpect(jsonPath("$.chart.details.ju").value("5"))
                .andExpect(jsonPath("$.chart.details.leadDoor").value("open"))
                .andExpect(jsonPath("$.chart.details.leadStar").value("heart"))
                .andExpect(jsonPath("$.chart.details.victories.light").value("gen"))
                .andExpect(jsonPath("$.chart.palaces.center.plot.number").value(5))
                .andExpect(jsonPath("$.chart.palaces.center.meta").isEmpty())
                .andExpect(jsonPath("$.chart.palaces.kan.plot.door").value("rest"))
                .andExpect(jsonPath("$.chart.palaces.kan.meta.star.strength").value("peak"))
                .andExpect(jsonPath("$.chart.bazi.pillars.day.stem.name").value("geng"));

        // both star paths and the chaiBu ju type
        mvc.perform(get("/api/qimen/chart").param("time", TIME)
                        .param("reference", "hour").param("starPath", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chart.details.starPath").value(1));
        mvc.perform(get("/api/qimen/chart").param("time", TIME)
                        .param("reference", "hour").param("method", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chart.details.ju").value(4))
                .andExpect(jsonPath("$.chart.details.ju").isNumber());

        // destiny adds per-palace lines, meta=false strips the meta layer
        mvc.perform(get("/api/qimen/chart").param("time", TIME)
                        .param("reference", "month").param("destiny", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chart.details.destinyPalace").value("xun"))
                .andExpect(jsonPath("$.chart.palaces.xun.meta.destiny.isDestinyPalace").value(true));
        mvc.perform(get("/api/qimen/chart").param("time", TIME).param("reference", "hour").param("meta", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chart.details.victories").doesNotExist())
                .andExpect(jsonPath("$.chart.palaces.kan.meta").isEmpty());

        // house input: echoes the RN houseInput object, uses the house ju
        mvc.perform(get("/api/qimen/chart").param("time", TIME).param("reference", "house")
                        .param("houseYear", "2024").param("houseDegree", "60.5").param("doorDegree", "210.5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chart.details.ju").value(9))
                .andExpect(jsonPath("$.chart.details.housePeriod").value(9))
                .andExpect(jsonPath("$.chart.details.houseStemBranch").value("yi wei"))
                .andExpect(jsonPath("$.chart.details.houseInput.houseYear").value(2024));

        // null parity: missing house input, table misses, "0-undefined" ju
        mvc.perform(get("/api/qimen/chart").param("time", TIME).param("reference", "house"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chart").value(nullValue()));
        mvc.perform(get("/api/qimen/chart")
                        .param("time", "2051-01-01T12:00:00+08:00").param("reference", "hour"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chart").value(nullValue()));
        mvc.perform(get("/api/qimen/chart")
                        .param("time", "2021-12-21T12:00:00+08:00").param("reference", "day"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chart").value(nullValue()));

        // bad input
        mvc.perform(get("/api/qimen/chart").param("time", TIME).param("reference", "bogus"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/qimen/chart").param("time", TIME).param("starPath", "2"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/qimen/chart").param("time", TIME).param("reference", "house")
                        .param("houseYear", "1700").param("houseDegree", "60.5").param("doorDegree", "210.5"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/qimen/chart").param("time", TIME).param("reference", "house")
                        .param("houseYear", "2024").param("houseDegree", "60.5").param("doorDegree", "7.55"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/qimen/chart").param("time", "garbage"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void qimenUsefulGods() throws Exception {
        mvc.perform(get("/api/qimen/useful-gods"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topics.length()").value(26))
                .andExpect(jsonPath("$.topics[0].topic").value("body"))
                .andExpect(jsonPath("$.topics[0].title").value("Body"))
                .andExpect(jsonPath("$.topics[25].topic").value("weather"))
                .andExpect(jsonPath("$.topics[25].title").value("Weather"));

        mvc.perform(get("/api/qimen/useful-gods/career"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topic").value("career"))
                .andExpect(jsonPath("$.title").value("Career"))
                .andExpect(jsonPath("$.symbols.length()").value(8))
                .andExpect(jsonPath("$.symbols[0].title").value("Year Stem"))
                .andExpect(jsonPath("$.symbols[0].body").value("Superior / Boss"));

        mvc.perform(get("/api/qimen/useful-gods/bogus"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void qimenDays() throws Exception {
        mvc.perform(get("/api/qimen/days").param("from", "2024-01-01").param("to", "2024-01-03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2024-01-01"))
                .andExpect(jsonPath("$.to").value("2024-01-03"))
                .andExpect(jsonPath("$.days.length()").value(3))
                .andExpect(jsonPath("$.victory").value(false))
                .andExpect(jsonPath("$.hours.length()").value(13))
                .andExpect(jsonPath("$.lookups.doors[0]").value("rest"))
                .andExpect(jsonPath("$.lookups.doors.length()").value(5))
                .andExpect(jsonPath("$.lookups.stars.length()").value(9))
                .andExpect(jsonPath("$.lookups.spirits.length()").value(5))
                .andExpect(jsonPath("$.lookups.structures.length()").value(18))
                .andExpect(jsonPath("$.days[0].date").value("2024-01-01"))
                .andExpect(jsonPath("$.days[0].victoryDay").value(nullValue()))
                .andExpect(jsonPath("$.days[0].goodHours").isArray());

        // default `to` is the last day of the `from` month
        mvc.perform(get("/api/qimen/days").param("from", "2024-02-10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.to").value("2024-02-29"))
                .andExpect(jsonPath("$.days.length()").value(20));

        // birth time enables the RN victory-day check
        mvc.perform(get("/api/qimen/days")
                        .param("from", "1990-05-17T00:00:00+08:00")
                        .param("to", "1990-05-18T23:59:00+08:00")
                        .param("birthTime", "1990-05-15T14:30:00+08:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.victory").value(true))
                .andExpect(jsonPath("$.days[0].victoryDay.name").value("force"))
                .andExpect(jsonPath("$.days[0].victoryDay.palace").value("gen"))
                .andExpect(jsonPath("$.days[0].victoryDay.direction").value("NE"))
                .andExpect(jsonPath("$.days[1].victoryDay.name").value("light"));

        // filter overrides are echoed (and membership applies)
        mvc.perform(get("/api/qimen/days")
                        .param("from", "2024-01-01").param("to", "2024-01-01")
                        .param("doors", "life,open").param("stars", "heart")
                        .param("spirits", "chief")
                        .param("structures", "green_dragon_returns")
                        .param("maxNegativeStructures", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lookups.doors.length()").value(2))
                .andExpect(jsonPath("$.lookups.doors[0]").value("life"))
                .andExpect(jsonPath("$.lookups.doors[1]").value("open"))
                .andExpect(jsonPath("$.lookups.stars[0]").value("heart"))
                .andExpect(jsonPath("$.lookups.spirits[0]").value("chief"))
                .andExpect(jsonPath("$.lookups.structures[0]").value("green_dragon_returns"))
                .andExpect(jsonPath("$.lookups.maxNegativeStructures").value(2));

        // filter tables for the UI
        mvc.perform(get("/api/qimen/tables"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.doors.length()").value(8))
                .andExpect(jsonPath("$.stars.length()").value(9))
                .andExpect(jsonPath("$.spirits.length()").value(10))
                .andExpect(jsonPath("$.spirits[0]").value("chief"))
                .andExpect(jsonPath("$.structures.length()").value(49))
                .andExpect(jsonPath("$.structures[0].name").value("green_dragon_returns"))
                .andExpect(jsonPath("$.structures[0].abbr").value("GDR"))
                .andExpect(jsonPath("$.structures[0].rating").value(2))
                .andExpect(jsonPath("$.structures[0].almanacDefault").value(true));

        // display naming conventions (destiny summary / palace labels)
        mvc.perform(get("/api/qimen/terminology"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.*", hasSize(3)))
                .andExpect(jsonPath("$.['0'].spirit.chief[0]").value("chief"))
                .andExpect(jsonPath("$.['0'].spirit.chief[1]").value("符"))
                .andExpect(jsonPath("$.['0'].star.assistant[0]").value("assist"))
                .andExpect(jsonPath("$.['1'].spirit.chief[0]").value("CEO"))
                .andExpect(jsonPath("$.['2'].star.bird[0]").value("beast"))
                .andExpect(jsonPath("$.['2'].door.open[0]").value("open"));

        // bad input
        mvc.perform(get("/api/qimen/days").param("from", "2024-01-01")
                        .param("to", "2024-02-15"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/qimen/days").param("from", "not-a-date"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/qimen/days").param("from", "2024-01-01")
                        .param("doors", "bogus"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/qimen/days").param("from", "2024-01-01")
                        .param("maxNegativeStructures", "-1"))
                .andExpect(status().isBadRequest());
    }

    // ----------------------------------------------------------  library

    @Test
    void library() throws Exception {
        mvc.perform(get("/api/library/compendium"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tree['$push_yinYang'].title").value("2 Yin Yang"))
                .andExpect(jsonPath("$.tree['$push_yinYang'].path").value("YinYangRef"))
                .andExpect(jsonPath("$.tree['$link_taiji'].uri").value(
                        "https://en.wikipedia.org/wiki/Wuji_(philosophy)"))
                .andExpect(jsonPath("$.tree.fourPillars['$header_heavenEarthMan'].title").value("Heaven Earth Man"))
                .andExpect(jsonPath("$.titles.elements").value("5 Elements"))
                .andExpect(jsonPath("$.titles['windWater.twentyFourMountains']").value("24 Mountains"));

        mvc.perform(get("/api/library/relationships"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections", hasSize(9)))
                .andExpect(jsonPath("$.sections[0].key").value("stemCombos"))
                .andExpect(jsonPath("$.sections[8].key").value("destructions"));
        mvc.perform(get("/api/library/relationships").param("key", "harms"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections", hasSize(1)))
                .andExpect(jsonPath("$.sections[0].title").value("Harms"));
        mvc.perform(get("/api/library/relationships").param("key", "bogus"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections", hasSize(0)));

        mvc.perform(get("/api/library/twelve-stages").param("stem", "ren"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stem.chinese").value("壬"))
                .andExpect(jsonPath("$.stem.natureName").value("ocean"))
                .andExpect(jsonPath("$.stages", hasSize(12)))
                .andExpect(jsonPath("$.stages[0].branch").value("zi"))
                .andExpect(jsonPath("$.stages[0].animalName").value("rat"))
                .andExpect(jsonPath("$.stages[11].branch").value("hai"));
        mvc.perform(get("/api/library/twelve-stages"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/library/twelve-stages").param("stem", "bogus"))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/library/yin-yang"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.words", hasSize(18)))
                .andExpect(jsonPath("$.words[0][0]").value("Positive"))
                .andExpect(jsonPath("$.words[0][1]").value("Negative"));

        mvc.perform(get("/api/library/heaven-earth-man"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.heaven", hasSize(11)))
                .andExpect(jsonPath("$.heaven[0]").value("Source"))
                .andExpect(jsonPath("$.earth[10]").value("Branches"))
                .andExpect(jsonPath("$.man[0]").value("Form"));

        mvc.perform(get("/api/library/tutorials"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tutorials", hasSize(4)))
                .andExpect(jsonPath("$.tutorials[0].key").value("bazi"))
                .andExpect(jsonPath("$.tutorials[0].pages", hasSize(7)))
                .andExpect(jsonPath("$.tutorials[2].title").value("Yi Jing"))
                .andExpect(jsonPath("$.tutorials[3].key").value("qimen"))
                .andExpect(jsonPath("$.tutorials[3].pages", hasSize(8)));
    }

    @Test
    void health() throws Exception {
        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.app").value("bazi-spring"))
                .andExpect(jsonPath("$.time").exists())
                .andExpect(jsonPath("$.data.bazi").value(true))
                .andExpect(jsonPath("$.data.qimen").value(true))
                .andExpect(jsonPath("$.data.library").value(true));
    }

    // ------------------------------------------------------- static pages

    @Test
    void staticPages() throws Exception {
        // "/" is the Boot welcome page: MockMvc sees the forward to index.html.
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("index.html"));
        for (String path : new String[]{"/index.html", "/almanac.html", "/hexagrams.html",
                "/fengshui.html", "/qimen.html", "/qimen-symbols.html", "/qimen-days.html",
                "/library.html", "/tutorials.html"}) {
            MvcResult r = mvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andReturn();
            String contentType = r.getResponse().getContentType();
            assertTrue(contentType != null && contentType.contains("text/html"), path + " content type: " + contentType);
        }

        // unknown static paths keep their 404 through the global advice
        mvc.perform(get("/no-such-page.html"))
                .andExpect(status().isNotFound());
    }
}
