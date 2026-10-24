#!/usr/bin/env python3
"""Merges the `qimen` section of raw-dump.json (produced by dump.mjs) into
the Spring Boot service's qimen-data.json (QMDJ / Qi Men Dun Jia tables).

Contains:
  - jusDay / jusHour   (year -> month -> day -> "dunFlag-ju" day/hour ju tables)
  - jusMonth / jusYear (year -> "dunFlag-ju" month/year ju tables)
  - solarJus           (solar term num -> [upper, middle, lower] ju numbers)
  - threeYuanTable     (day pillar stem/branch -> { leader, cycle })
  - pillarOrdering     (60 jia-zi [stem, branch] pairs; house-input lookup)
  - doors / stars      (meta layer: palace interactions + strength tables)
  - hours              (hour stem x door -> {rating})
  - formations         (heaven/earth stem pair -> formation summary)
  - structures         (structure matcher rules; `match` order is preserved
                        because the RN code serialises the matched rule with
                        JSON.stringify - key order must stay byte-identical)
  - usefulGodSymbols   (static topic -> [[symbol, meaning], ...] reference
                        table behind QiMenUsefulGodList/Details)
  - displayTerminology (0/1/2 naming conventions for spirit/star/door
                        display names; QiMenDestinySummary + palace cells)

Presentation-only keys ($dict_noun, $body, ...) are dropped recursively.

Keep in sync with dump.mjs. Writes a one-time backup under backup/.

Usage:
    python3 merge_qimen.py        # uses raw-dump.json next to this script
"""
import json
import os
import re
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(HERE, "raw-dump.json")
TARGET = os.path.abspath(os.path.join(
    HERE, "..", "..", "bazi-spring", "src", "main", "resources", "data", "qimen-data.json"))
BACKUP_DIR = os.path.join(HERE, "backup")

KEYS = ["jusHour", "jusDay", "jusMonth", "jusYear", "solarJus", "threeYuanTable",
        "pillarOrdering", "doors", "stars", "hours", "formations", "structures",
        "usefulGodSymbols", "displayTerminology"]

JUS_RE = re.compile(r"^[01]-\d+$")

STEMS = ["jia", "yi", "bing", "ding", "wu", "ji", "geng", "xin", "ren", "gui"]
BRANCHES = ["zi", "chou", "yin", "mao", "chen", "si",
            "wu", "wei", "shen", "you", "xu", "hai"]
DOORS = ["rest", "life", "harm", "delusion", "scenery", "death", "fear", "open"]
STARS = ["grass", "grain", "destructor", "assistant", "bird",
         "heart", "pillar", "ambassador", "hero"]
SPIRITS = ["chief", "snake", "moon", "harmony", "hook",
           "tiger", "phoenix", "tortoise", "earth", "heaven"]
PALACES8 = ["kan", "gen", "zhen", "xun", "li", "kun", "dui", "qian"]
PALACES9 = PALACES8 + ["center"]
STRENGTH_WORDS = {"peak", "strong", "weak", "trap", "dead"}
STEM_PLACEHOLDERS = {"$yearStem", "$monthStem", "$dayStem", "$hourStem"}
RULE_FIELDS = {"heavenStem", "earthStem", "door", "spirit", "palace",
               "isHeavenLeadStem", "isEarthLeadStem", "isLeadDoor"}

USEFUL_GOD_TOPICS = {
    "relationships", "childbirth", "health", "body", "legal", "crime",
    "mentors", "meetings", "lost", "information", "luck", "general",
    "finance", "sales", "deals", "partnerships", "industries", "parking",
    "property", "loans", "career", "examinations", "competition", "travel",
    "sports", "weather",
}

TERMINOLOGY_SPIRITS = {
    "chief", "heaven", "earth", "tortoise", "hook", "phoenix", "tiger",
    "harmony", "moon", "snake",
}

# RN data gap: jusDay has 8,339 literal "0-undefined" entries (a crawl miss:
# 2021-12-21 alone, then 2077-03-04 through 2099). Jus.zhiRun does NOT fail on
# them - it returns ["yin", "undefined"] - so they are preserved verbatim.
UNDEFINED = "0-undefined"


def drop_dollars(v):
    """Drop $dict_* / $body style presentation keys recursively."""
    if isinstance(v, list):
        return [drop_dollars(i) for i in v]
    if isinstance(v, dict):
        return {k: drop_dollars(val) for k, val in v.items() if not k.startswith("$")}
    return v


def check_jus_entry(value, label):
    assert isinstance(value, str), f"{label}: {value!r}"
    if value == UNDEFINED:
        return value
    assert JUS_RE.match(value), f"{label}: {value!r}"
    dun_flag, ju = value.split("-")
    assert dun_flag in ("0", "1"), f"{label} dun flag"
    assert 1 <= int(ju) <= 9, f"{label} ju range"
    return None


def check_jus_tables(qm):
    hour, day = qm["jusHour"], qm["jusDay"]
    month, year = qm["jusMonth"], qm["jusYear"]

    # Year coverage: day/month/year 1900-2099, hour 1900-2050 (the hour table
    # simply stops at 2050 - Jus.zhiRun returns null beyond it, kept as-is).
    assert list(year) == [str(y) for y in range(1900, 2100)], "yearJus years"
    assert list(month) == [str(y) for y in range(1900, 2100)], "monthJus years"
    assert list(day) == [str(y) for y in range(1900, 2100)], "dayJus years"
    assert list(hour) == [str(y) for y in range(1900, 2051)], "hourJus years"

    undefined = 0
    for y, v in year.items():
        check_jus_entry(v, f"yearJus[{y}]")
    for y, v in month.items():
        check_jus_entry(v, f"monthJus[{y}]")

    for table, name in ((day, "jusDay"), (hour, "jusHour")):
        for y, months in table.items():
            assert set(months) == {f"{m:02d}" for m in range(1, 13)}, f"{name}[{y}] months"
            for m, days in months.items():
                assert days, f"{name}[{y}][{m}] empty"
                for d, v in days.items():
                    assert len(d) == 2 and 1 <= int(d) <= 31, f"{name}[{y}][{m}] day {d}"
                    if check_jus_entry(v, f"{name}[{y}][{m}][{d}]") == UNDEFINED:
                        undefined += 1

    solar = qm["solarJus"]
    assert set(solar) == {str(n) for n in range(24)}, "solarJus terms"
    for n, tri in solar.items():
        assert len(tri) == 3 and all(1 <= int(x) <= 9 for x in tri), f"solarJus[{n}]"

    ty = qm["threeYuanTable"]
    assert len(ty) == 10, "threeYuanTable stems"
    entries = [(s, b, v) for s, brs in ty.items() for b, v in brs.items()]
    assert len(entries) == 60, "threeYuanTable entries"
    cycles = {}
    for s, b, v in entries:
        assert set(v) == {"leader", "cycle"}, f"threeYuanTable[{s}][{b}] fields"
        cycles[v["cycle"]] = cycles.get(v["cycle"], 0) + 1
    assert cycles == {"upper": 20, "middle": 20, "lower": 20}, f"cycle mix {cycles}"

    return hour, day, month, year, solar, entries, undefined


def check_mystic(qm):
    """Validates + trims the phase-4b QiMap meta tables."""
    clean = {k: drop_dollars(qm[k]) for k in
             ("pillarOrdering", "doors", "stars", "hours", "formations", "structures",
              "usefulGodSymbols", "displayTerminology")}
    qm = clean

    order = qm["pillarOrdering"]
    assert len(order) == 60, "pillarOrdering length"
    for i, pair in enumerate(order):
        assert pair == [STEMS[i % 10], BRANCHES[i % 12]], f"pillarOrdering[{i}] {pair}"

    doors = qm["doors"]
    assert list(doors) == DOORS, "door names / order"
    for name, door in doors.items():
        assert set(door) <= {"specialInteraction", "palaceInteraction", "strength",
                             "details", "desc"}, f"door {name} keys {set(door)}"
        for key in ("specialInteraction", "palaceInteraction"):
            m = door[key]
            assert set(m) <= set(PALACES8), f"door {name}.{key} palaces {set(m)}"
            assert all(isinstance(v, str) for v in m.values()), f"door {name}.{key} values"
        strength = door["strength"]
        assert list(strength) == [str(i) for i in range(24)], f"door {name} strength keys"
        assert all(4 <= v <= 8 for v in strength.values()), f"door {name} strength values"

    stars = qm["stars"]
    assert list(stars) == STARS, "star names / order"
    for name, star in stars.items():
        assert set(star) <= {"specialInteraction", "palaceInteraction", "strength",
                             "details"}, f"star {name} keys {set(star)}"
        for key in ("specialInteraction", "palaceInteraction"):
            m = star[key]
            assert set(m) <= set(PALACES8), f"star {name}.{key} palaces {set(m)}"
            assert all(isinstance(v, str) for v in m.values()), f"star {name}.{key} values"
        strength = star["strength"]
        assert set(strength) <= set(PALACES8), f"star {name} strength palaces"
        assert all(v in STRENGTH_WORDS for v in strength.values()), f"star {name} strength"

    hours = qm["hours"]
    assert list(hours) == STEMS, "hours stems"
    for stem, door_map in hours.items():
        assert list(door_map) == DOORS, f"hours[{stem}] doors"
        for door, v in door_map.items():
            assert set(v) == {"rating"}, f"hours[{stem}][{door}] fields"
            assert isinstance(v["rating"], int), f"hours[{stem}][{door}].rating"

    formations = qm["formations"]
    assert list(formations) == STEMS, "formations stems"
    count = 0
    for a, cols in formations.items():
        assert list(cols) == STEMS, f"formations[{a}] stems"
        for b, v in cols.items():
            assert set(v) == {"rating", "name", "nick", "note", "desc"}, \
                f"formations[{a}][{b}] fields {set(v)}"
            assert v["rating"] in (-1, 0, 1), f"formations[{a}][{b}] rating"
            count += 1
    assert count == 100, "formation count"

    structures = qm["structures"]
    assert len(structures) == 49, "structure count"
    rules = 0
    for key, v in structures.items():
        assert isinstance(v["rating"], int), f"structure {key} rating"
        assert isinstance(v["match"], list) and v["match"], f"structure {key} match"
        for rule in v["match"]:
            rules += 1
            assert isinstance(rule, dict) and rule, f"structure {key} rule"
            for field, val in rule.items():
                assert field in RULE_FIELDS, f"structure {key} rule field {field}"
                if field in ("isHeavenLeadStem", "isEarthLeadStem", "isLeadDoor"):
                    assert isinstance(val, bool), f"structure {key}.{field} type"
                else:
                    assert isinstance(val, str), f"structure {key}.{field} type"
                    if field in ("heavenStem", "earthStem"):
                        assert val in STEMS + sorted(STEM_PLACEHOLDERS), \
                            f"structure {key}.{field} = {val}"
                    elif field == "door":
                        assert val in DOORS, f"structure {key}.door = {val}"
                    elif field == "spirit":
                        assert val in SPIRITS, f"structure {key}.spirit = {val}"
                    elif field == "palace":
                        assert val in PALACES9, f"structure {key}.palace = {val}"

    symbols = qm["usefulGodSymbols"]
    assert set(symbols) == USEFUL_GOD_TOPICS, \
        f"usefulGod topics diff: {set(symbols) ^ USEFUL_GOD_TOPICS}"
    symbol_count = 0
    for topic, rows in symbols.items():
        assert isinstance(rows, list) and rows, f"usefulGods[{topic}] rows"
        for row in rows:
            assert isinstance(row, list) and len(row) == 2, f"usefulGods[{topic}] row {row!r}"
            assert all(isinstance(v, str) and v for v in row), \
                f"usefulGods[{topic}] row {row!r}"
            symbol_count += 1

    terminology = qm["displayTerminology"]
    assert set(terminology) == {"0", "1", "2"}, f"terminology conventions: {set(terminology)}"
    term_count = 0
    for conv, kinds in terminology.items():
        assert set(kinds) == {"spirit", "star", "door"}, f"terminology[{conv}] kinds"
        assert set(kinds["spirit"]) == TERMINOLOGY_SPIRITS, f"terminology[{conv}] spirits"
        assert set(kinds["star"]) == set(clean["stars"]), f"terminology[{conv}] stars"
        assert set(kinds["door"]) == set(clean["doors"]), f"terminology[{conv}] doors"
        for kind, entries in kinds.items():
            for key, pair in entries.items():
                assert isinstance(pair, list) and len(pair) == 2, \
                    f"terminology[{conv}][{kind}][{key}] = {pair!r}"
                assert all(isinstance(v, str) and v for v in pair), \
                    f"terminology[{conv}][{kind}][{key}] = {pair!r}"
                term_count += 1

    out = {k: qm[k] for k in
           ("pillarOrdering", "doors", "stars", "hours", "formations", "structures",
            "usefulGodSymbols", "displayTerminology")}
    return out, rules, len(symbols), symbol_count, len(terminology), term_count


def main():
    with open(RAW, encoding="utf-8") as f:
        raw = json.load(f)
    qm = raw.get("qimen")
    if qm is None:
        print("raw-dump.json has no `qimen` section - rerun dump.mjs", file=sys.stderr)
        return 1

    hour, day, month, year, solar, entries, undefined = check_jus_tables(qm)
    mystic, rules, topics, topic_symbols, conventions, term_entries = check_mystic(qm)

    os.makedirs(BACKUP_DIR, exist_ok=True)
    backup = os.path.join(BACKUP_DIR, "qimen-data.orig.json")
    if os.path.exists(TARGET) and not os.path.exists(backup):
        shutil.copy(TARGET, backup)
        print("backup written to", backup)

    out = {k: qm[k] for k in KEYS[:6]}
    out.update(mystic)

    with open(TARGET, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, separators=(",", ":"))
        f.write("\n")

    print("wrote", TARGET, os.path.getsize(TARGET), "bytes")
    print("hour years:", len(hour), "day years:", len(day),
          "month years:", len(month), "year years:", len(year),
          "solar terms:", len(solar), "three-yuan entries:", len(entries))
    print("jusDay \"0-undefined\" entries preserved:", undefined)
    print("pillarOrdering:", len(out["pillarOrdering"]),
          "doors:", len(out["doors"]), "stars:", len(out["stars"]),
          "hours:", len(out["hours"]), "formations:",
          len(out["formations"]) * len(out["formations"]["jia"]),
          "structures:", len(out["structures"]), "match rules:", rules,
          "usefulGod topics:", topics, "symbols:", topic_symbols,
          "naming conventions:", conventions, "terminology entries:", term_entries)


if __name__ == "__main__":
    sys.exit(main())
