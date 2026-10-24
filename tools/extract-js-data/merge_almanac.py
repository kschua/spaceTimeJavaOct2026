#!/usr/bin/env python3
"""Merges the `timing` section of raw-dump.json (produced by dump.mjs) into the
Spring Boot service's almanac-data.json.

Contains everything Timing.js uses:
  - twelveOfficerDesc / twentyEightConstellationsDesc / threeKillingsDesc
  - officers (order array)
  - bigDisasterDays (year -> MM-dd list)
  - dongGong (month branch -> "stem-branch" -> 0..4)
  - tongShu (crawled yyyy-MM-dd entries)

Keep in sync with dump.mjs. Writes a one-time backup under backup/.

Usage:
    python3 merge_almanac.py           # uses raw-dump.json next to this script
"""
import json
import os
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(HERE, "raw-dump.json")
TARGET = os.path.abspath(os.path.join(
    HERE, "..", "..", "bazi-spring", "src", "main", "resources", "data", "almanac-data.json"))
BACKUP_DIR = os.path.join(HERE, "backup")

KEYS = [
    "twelveOfficerDesc",
    "twentyEightConstellationsDesc",
    "threeKillingsDesc",
    "officers",
    "bigDisasterDays",
    "dongGong",
    "tongShu",
]


def main():
    with open(RAW, encoding="utf-8") as f:
        raw = json.load(f)
    timing = raw.get("timing")
    if timing is None:
        print("raw-dump.json has no `timing` section - rerun dump.mjs", file=sys.stderr)
        return 1

    # ---- sanity checks on the dump ----
    assert len(timing["officers"]) == 12, "officers"
    assert len(timing["twelveOfficerDesc"]) == 12, "twelveOfficerDesc"
    assert len(timing["twentyEightConstellationsDesc"]) == 28, "constellations"
    assert len(timing["dongGong"]) == 12, "dongGong months"
    for month, entries in timing["dongGong"].items():
        assert len(entries) == 60, f"dongGong[{month}] count"
        assert set(entries.values()) <= {0, 1, 2, 3, 4}, f"dongGong[{month}] values"
    assert len(timing["tongShu"]) > 600, "tongShu entries"
    assert set(timing["bigDisasterDays"]) == {"2019", "2020", "2021", "2022"}, "bigDisaster years"

    out = {k: timing[k] for k in KEYS}

    os.makedirs(BACKUP_DIR, exist_ok=True)
    backup = os.path.join(BACKUP_DIR, "almanac-data.orig.json")
    if os.path.exists(TARGET) and not os.path.exists(backup):
        shutil.copy(TARGET, backup)
        print("backup written to", backup)

    with open(TARGET, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
        f.write("\n")

    print("wrote", TARGET, os.path.getsize(TARGET), "bytes")
    print("tongShu days:", len(out["tongShu"]),
          "dongGong entries:", sum(len(v) for v in out["dongGong"].values()))


if __name__ == "__main__":
    sys.exit(main())
