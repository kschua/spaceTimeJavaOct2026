#!/usr/bin/env python3
"""Merges the `library` section of raw-dump.json (produced by dump.mjs) into
the Spring Boot service's library-data.json (Phase 5 Library / Compendium).

Contains:
  - compendium   (the resolved `lib/cmtools/Compendium/index.js` tree: headers,
                  `$link_*` URLs, `$push_*` app targets and the chapter data)
  - titles       (`Compendium/Titles.js`: path -> display title)
  - relationships (RelationshipsTable sections, brace-extracted from the
                  component source: 9 sections of {type, val} rows)
  - yinYang      (YinYangRef word pairs, brace-extracted)
  - heavenEarthMan (HeavenEarthMan word lists, brace-extracted)
  - tutorials    (the four Onboarding screens, brace-extracted verbatim:
                  TutorialBazi / TutorialCalendar / TutorialHexagrams / TutorialQiMen)

The compendium tree keeps its `$header_*` / `$link_*` / `$push_*` keys - they
are the RN app's own section markers, rendered by Library.js.

Keep in sync with dump.mjs. Writes a one-time backup under backup/.

Usage:
    python3 merge_library.py       # uses raw-dump.json next to this script
"""
import json
import os
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(HERE, "raw-dump.json")
TARGET = os.path.abspath(os.path.join(
    HERE, "..", "..", "bazi-spring", "src", "main", "resources", "data", "library-data.json"))
BACKUP_DIR = os.path.join(HERE, "backup")

TOP_KEYS = [
    "$header_fundamentals", "$link_1Way", "$link_taiji", "$push_yinYang",
    "$push_realms", "elements", "trigrams", "heavenlyStems", "earthlyBranches",
    "$header_systems", "fourPillars", "windWater", "qiMenDunJia",
    "dateSelection", "$link_yijin", "$header_concepts", "interactions",
    "$link_productionCycle", "$link_controllingCycle", "$link_elementalStrength",
    "twelveStagesOfGrowth", "$link_LuoShu", "$link_HeTu", "$link_kingWen",
    "$header__other", "threeTreasures", "$link_neidan", "$link_qi",
    "$link_qigong", "$link_taichi",
]

RELATIONSHIP_KEYS = ["stemCombos", "direction", "harmony", "threePenalties",
                     "clashes", "selfPenalties", "sixCombos", "harms",
                     "destructions"]

TUTORIAL_PAGES = {"bazi": 7, "calendar": 8, "hexagrams": 6, "qimen": 8}


def check_compendium(lib):
    tree = lib["compendium"]
    assert list(tree) == TOP_KEYS, f"compendium top keys diff: {list(tree)}"
    for key in TOP_KEYS:
        if key.startswith("$link"):
            assert set(tree[key]) == {"uri"} or "title" in tree[key], f"{key} link shape"
            assert isinstance(tree[key]["uri"], str) and tree[key]["uri"], f"{key}.uri"
        elif key.startswith("$header"):
            assert isinstance(tree[key].get("title"), str), f"{key} header title"
        elif key.startswith("$push"):
            assert isinstance(tree[key].get("title"), str), f"{key} push title"
            assert isinstance(tree[key].get("path"), str), f"{key} push path"

    # the chapter subtrees the RN screens navigate into
    assert list(tree["fourPillars"])[:2] == ["$link_wiki", "$header_heavenEarthMan"]
    assert set(tree["fourPillars"]).issuperset({"year", "month", "day", "hour"})
    assert set(tree["windWater"]).issuperset(
        {"twentyFourMountains", "eightMansions", "flyingStars"})
    assert set(tree["qiMenDunJia"]).issuperset(
        {"doors", "stars", "spirits", "structures", "victoryPalaces"})
    assert set(tree["dateSelection"]).issuperset(
        {"twelveOfficers", "twentyEightConstellations", "threeKillings"})
    assert len(tree["interactions"]) == 10, "interaction pushes"
    assert len(tree["twelveStagesOfGrowth"]) == 10, "twelveStages pushes"

    titles = lib["titles"]
    assert len(titles) == 141, f"titles count: {len(titles)}"
    assert all(isinstance(k, str) and k for k in titles), "title keys"
    assert all(isinstance(v, str) and v for v in titles.values()), "title values"
    return tree, titles


def check_tables(lib):
    sections = lib["relationships"]
    assert len(sections) == 9, f"relationship sections: {len(sections)}"
    assert [s["key"] for s in sections] == RELATIONSHIP_KEYS, \
        f"relationship keys: {[s['key'] for s in sections]}"
    rows = 0
    for s in sections:
        assert isinstance(s["title"], str) and s["title"], f"{s['key']} title"
        assert isinstance(s["data"], list) and s["data"], f"{s['key']} data"
        for row in s["data"]:
            assert isinstance(row, list) and row, f"{s['key']} row"
            for cell in row:
                assert set(cell) == {"type", "val"}, f"{s['key']} cell {cell}"
                assert cell["type"] in ("stem", "branch", "text"), \
                    f"{s['key']} cell type {cell}"
                assert isinstance(cell["val"], str) and cell["val"], \
                    f"{s['key']} cell val {cell}"
            rows += 1

    yin_yang = lib["yinYang"]
    assert len(yin_yang) == 18, f"yinYang pairs: {len(yin_yang)}"
    assert all(isinstance(p, list) and len(p) == 2
               and all(isinstance(w, str) and w for w in p) for p in yin_yang), \
        "yinYang shapes"

    hem = lib["heavenEarthMan"]
    assert list(hem) == ["heaven", "earth", "man"], f"heavenEarthMan keys: {list(hem)}"
    assert all(isinstance(hem[k], list) and len(hem[k]) == 11 for k in hem), \
        "heavenEarthMan lengths"
    assert all(isinstance(w, str) and w for k in hem for w in hem[k]), \
        "heavenEarthMan words"
    return sections, rows, yin_yang, hem


def check_tutorials(lib):
    tutorials = lib["tutorials"]
    assert [t["key"] for t in tutorials] == list(TUTORIAL_PAGES), \
        f"tutorial keys: {[t['key'] for t in tutorials]}"
    for t in tutorials:
        assert isinstance(t["title"], str) and t["title"], f"{t['key']} title"
        assert len(t["pages"]) == TUTORIAL_PAGES[t["key"]], \
            f"{t['key']} pages: {len(t['pages'])}"
        for page in t["pages"]:
            assert set(page) == {"image", "title", "subtitle"}, \
                f"{t['key']} page keys: {sorted(page)}"
            assert isinstance(page["image"], str) and page["image"].startswith("http"), \
                f"{t['key']} image: {page['image']}"
            assert isinstance(page["title"], str) and page["title"], \
                f"{t['key']} page title"
            assert isinstance(page["subtitle"], str), f"{t['key']} subtitle"
    return tutorials


def main():
    with open(RAW, encoding="utf-8") as f:
        raw = json.load(f)
    lib = raw.get("library")
    if lib is None:
        print("raw-dump.json has no `library` section - rerun dump.mjs", file=sys.stderr)
        return 1

    tree, titles = check_compendium(lib)
    sections, rows, yin_yang, hem = check_tables(lib)
    tutorials = check_tutorials(lib)

    os.makedirs(BACKUP_DIR, exist_ok=True)
    backup = os.path.join(BACKUP_DIR, "library-data.orig.json")
    if os.path.exists(TARGET) and not os.path.exists(backup):
        shutil.copy(TARGET, backup)
        print("backup written to", backup)

    out = {
        "compendium": tree,
        "titles": titles,
        "relationships": sections,
        "yinYang": yin_yang,
        "heavenEarthMan": hem,
        "tutorials": tutorials,
    }
    with open(TARGET, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, separators=(",", ":"))
        f.write("\n")

    print("wrote", TARGET, os.path.getsize(TARGET), "bytes")
    print("compendium keys:", len(tree), "titles:", len(titles),
          "relationship sections:", len(sections), "rows:", rows,
          "yinYang pairs:", len(yin_yang),
          "heavenEarthMan:", "/".join(str(len(hem[k])) for k in hem),
          "tutorials:", ",".join(f"{t['key']}:{len(t['pages'])}" for t in tutorials))


if __name__ == "__main__":
    sys.exit(main())
