package com.bazi;

import java.util.List;
import java.util.Map;


public final class Tables {
    public static final List<String> STEMS = List.of("jia", "yi", "bing", "ding", "wu", "ji", "geng", "xin", "ren", "gui");
    public static final List<String> BRANCHES = List.of("zi", "chou", "yin", "mao", "chen", "si", "wu", "wei", "shen", "you", "xu", "hai");

    @SuppressWarnings("unchecked")
    public static final List<List<String>> PILLARS = List.of(
            List.of("jia", "zi"), List.of("yi", "chou"), List.of("bing", "yin"), List.of("ding", "mao"),
            List.of("wu", "chen"), List.of("ji", "si"), List.of("geng", "wu"), List.of("xin", "wei"),
            List.of("ren", "shen"), List.of("gui", "you"), List.of("jia", "xu"), List.of("yi", "hai"),
            List.of("bing", "zi"), List.of("ding", "chou"), List.of("wu", "yin"), List.of("ji", "mao"),
            List.of("geng", "chen"), List.of("xin", "si"), List.of("ren", "wu"), List.of("gui", "wei"),
            List.of("jia", "shen"), List.of("yi", "you"), List.of("bing", "xu"), List.of("ding", "hai"),
            List.of("wu", "zi"), List.of("ji", "chou"), List.of("geng", "yin"), List.of("xin", "mao"),
            List.of("ren", "chen"), List.of("gui", "si"), List.of("jia", "wu"), List.of("yi", "wei"),
            List.of("bing", "shen"), List.of("ding", "you"), List.of("wu", "xu"), List.of("ji", "hai"),
            List.of("geng", "zi"), List.of("xin", "chou"), List.of("ren", "yin"), List.of("gui", "mao"),
            List.of("jia", "chen"), List.of("yi", "si"), List.of("bing", "wu"), List.of("ding", "wei"),
            List.of("wu", "shen"), List.of("ji", "you"), List.of("geng", "xu"), List.of("xin", "hai"),
            List.of("ren", "zi"), List.of("gui", "chou"), List.of("jia", "yin"), List.of("yi", "mao"),
            List.of("bing", "chen"), List.of("ding", "si"), List.of("wu", "wu"), List.of("ji", "wei"),
            List.of("geng", "shen"), List.of("xin", "you"), List.of("ren", "xu"), List.of("gui", "hai"));

    public static List<String> pillarAt(int index) {
        int i = index % PILLARS.size();
        if (i < 0) i += PILLARS.size();
        return PILLARS.get(i);
    }

    public static int pillarIndex(String stem, String branch) {
        for (int i = 0; i < PILLARS.size(); i++) {
            List<String> p = PILLARS.get(i);
            if (p.get(0).equals(stem) && p.get(1).equals(branch)) return i;
        }
        throw new IllegalArgumentException(stem + branch);
    }

    public static List<String> shiftPillar(List<String> pillar, int num) {
        return pillarAt(pillarIndex(pillar.get(0), pillar.get(1)) + num);
    }

    public static String hourBranchOf(int minutesSinceDayBegin) {
        if (minutesSinceDayBegin >= 60 && minutesSinceDayBegin < 180) return "chou";
        if (minutesSinceDayBegin >= 180 && minutesSinceDayBegin < 300) return "yin";
        if (minutesSinceDayBegin >= 300 && minutesSinceDayBegin < 420) return "mao";
        if (minutesSinceDayBegin >= 420 && minutesSinceDayBegin < 540) return "chen";
        if (minutesSinceDayBegin >= 540 && minutesSinceDayBegin < 660) return "si";
        if (minutesSinceDayBegin >= 660 && minutesSinceDayBegin < 780) return "wu";
        if (minutesSinceDayBegin >= 780 && minutesSinceDayBegin < 900) return "wei";
        if (minutesSinceDayBegin >= 900 && minutesSinceDayBegin < 1020) return "shen";
        if (minutesSinceDayBegin >= 1020 && minutesSinceDayBegin < 1140) return "you";
        if (minutesSinceDayBegin >= 1140 && minutesSinceDayBegin < 1260) return "xu";
        if (minutesSinceDayBegin >= 1260 && minutesSinceDayBegin < 1380) return "hai";
        return "zi";
    }

    public static final Map<String, String> HOUR_TABLE = Map.ofEntries(
            Map.entry("jia", "jia"), Map.entry("yi", "bing"), Map.entry("bing", "wu"), Map.entry("ding", "geng"),
            Map.entry("wu", "ren"), Map.entry("ji", "jia"), Map.entry("geng", "bing"), Map.entry("xin", "wu"),
            Map.entry("ren", "geng"), Map.entry("gui", "ren"));
}
