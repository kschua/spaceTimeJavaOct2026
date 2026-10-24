#!/usr/bin/env python3
"""Merges the `windwater` section of raw-dump.json (produced by dump.mjs) into
the Spring Boot service's fengshui-data.json (WindWater.js tables).

Contains:
  - mountains / twentyFourMountainAttributes (24 mountains and their attributes)
  - guas (life-gua -> eight-mansion direction table)
  - flyingStars / flyingStarCombinations (star meanings + pair readings)
  - eightMansions / getEightMansionNaJia
  - xuanKong rings (inner/outer numbers + 64 hexagram binaries)
  - dragons (72 / 60 / 60) + threeAuspiciousSixElegance
  - qiMenFengShuiMap (used by the Qi Men Dun Jia plot in a later phase)

Keep in sync with dump.mjs. Writes a one-time backup under backup/.

Usage:
    python3 merge_fengshui.py        # uses raw-dump.json next to this script
"""
import json
import os
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(HERE, "raw-dump.json")
TARGET = os.path.abspath(os.path.join(
    HERE, "..", "..", "bazi-spring", "src", "main", "resources", "data", "fengshui-data.json"))
BACKUP_DIR = os.path.join(HERE, "backup")

KEYS = [
    "mountains",
    "twentyFourMountainAttributes",
    "guas",
    "flyingStars",
    "flyingStarCombinations",
    "eightMansions",
    "getEightMansionNaJia",
    "xuanKongNumberRingInner",
    "xuanKongNumberRingOuter",
    "xuanKongHexagramInner",
    "xuanKongHexagramOuter",
    "piercingMountain72Dragons",
    "earthPenetrating60Dragons",
    "equalDivision60Dragons",
    "threeAuspiciousSixElegance",
    "qiMenFengShuiMap",
]

SECTORS = set()
for _dir in ("N", "NE", "E", "SE", "S", "SW", "W", "NW"):
    for _n in (1, 2, 3):
        SECTORS.add(f"{_dir}{_n}")


def main():
    with open(RAW, encoding="utf-8") as f:
        raw = json.load(f)
    ww = raw.get("windwater")
    if ww is None:
        print("raw-dump.json has no `windwater` section - rerun dump.mjs", file=sys.stderr)
        return 1

    mountains = ww["mountains"]
    assert len(mountains) == 24, "mountains"
    assert set(mountains) == SECTORS, "mountain sectors"
    for sector in ("N2", "SW3", "NE1"):
        for field in ("sector", "direction", "pinyin", "chinese", "element", "type", "palace"):
            assert field in mountains[sector], f"mountains[{sector}].{field}"
        assert mountains[sector]["sector"] == sector, f"mountains[{sector}] sector field"

    attrs = ww["twentyFourMountainAttributes"]
    # RN data gap: the source is missing the NE3 (yin) attribute entry.
    # Preserved as-is for parity; consumers must treat NE3 as absent.
    assert set(attrs) == SECTORS - {"NE3"}, "mountain attributes sectors"
    assert attrs["N2"]["pinyin"] == mountains["N2"]["pinyin"], "attributes pinyin"

    assert len(ww["guas"]) == 8, "life gua table"
    for gua, data in ww["guas"].items():
        assert len(data["eightMansions"]) == 8, f"guas[{gua}].eightMansions"

    assert len(ww["flyingStars"]) == 9, "flyingStars"
    assert len(ww["flyingStarCombinations"]) == 45, "flyingStarCombinations"

    assert len(ww["eightMansions"]) == 8, "eightMansions"
    for name, mansion in ww["eightMansions"].items():
        for field in ("name", "nickname", "pinyin", "chinese", "element", "auspiciousness"):
            assert field in mansion, f"eightMansions[{name}].{field}"

    na_jia = ww["getEightMansionNaJia"]
    assert len(na_jia) == 8, "getEightMansionNaJia keys"
    for num, palaces in na_jia.items():
        assert num in list("123456789"), f"naJia key {num}"
        assert set(palaces.values()) <= set(ww["eightMansions"]), f"naJia {num} mansions"

    assert len(ww["xuanKongNumberRingInner"]) == 8 == len(ww["xuanKongNumberRingOuter"]), "xuanKong number rings"
    assert len(ww["xuanKongHexagramInner"]) == 64 == len(ww["xuanKongHexagramOuter"]), "xuanKong hexagram rings"
    for binary in ww["xuanKongHexagramInner"] + ww["xuanKongHexagramOuter"]:
        assert len(binary) == 6 and set(binary) <= {"0", "1"}, f"xuanKong binary {binary}"

    assert len(ww["piercingMountain72Dragons"]) == 72, "72 dragons"
    assert len(ww["earthPenetrating60Dragons"]) == 60, "earth 60 dragons"
    assert len(ww["equalDivision60Dragons"]) == 60, "equal 60 dragons"
    assert len(ww["threeAuspiciousSixElegance"]) == 24, "three auspicious six elegance"
    assert len(ww["qiMenFengShuiMap"]) == 9, "qiMen ju"

    out = {k: ww[k] for k in KEYS}

    os.makedirs(BACKUP_DIR, exist_ok=True)
    backup = os.path.join(BACKUP_DIR, "fengshui-data.orig.json")
    if os.path.exists(TARGET) and not os.path.exists(backup):
        shutil.copy(TARGET, backup)
        print("backup written to", backup)

    with open(TARGET, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
        f.write("\n")

    print("wrote", TARGET, os.path.getsize(TARGET), "bytes")
    print("mountains:", len(mountains), "stars:", len(ww["flyingStars"]),
          "mansions:", len(ww["eightMansions"]), "qiMen ju:", len(ww["qiMenFengShuiMap"]))


if __name__ == "__main__":
    sys.exit(main())
