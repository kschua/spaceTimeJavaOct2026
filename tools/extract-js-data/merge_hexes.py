#!/usr/bin/env python3
"""Merges the `hexes` section of raw-dump.json (produced by dump.mjs) into the
Spring Boot service's hexes-data.json (Yi Jing tables).

Contains everything Hexes.js exposes:
  - horary (stem/branch -> trigram tables; kept for completeness)
  - horaryTable (60 jiazi keyed day pillars -> 6-bit binary; the RN table has 59)
  - order (64 King Wen hexagrams)
  - pillars (stem -> branch -> hexagram numbers; Bazi pillar mapping)
  - animalOrder / stemAnimalMap / hexagramStartBranches (line derivation)

Keep in sync with dump.mjs. Writes a one-time backup under backup/.

Usage:
    python3 merge_hexes.py            # uses raw-dump.json next to this script
"""
import json
import os
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(HERE, "raw-dump.json")
TARGET = os.path.abspath(os.path.join(
    HERE, "..", "..", "bazi-spring", "src", "main", "resources", "data", "hexes-data.json"))
BACKUP_DIR = os.path.join(HERE, "backup")

KEYS = [
    "horary",
    "horaryTable",
    "order",
    "pillars",
    "animalOrder",
    "stemAnimalMap",
    "hexagramStartBranches",
]


def main():
    with open(RAW, encoding="utf-8") as f:
        raw = json.load(f)
    hexes = raw.get("hexes")
    if hexes is None:
        print("raw-dump.json has no `hexes` section - rerun dump.mjs", file=sys.stderr)
        return 1

    order = hexes["order"]
    assert len(order) == 64, "order length"
    assert [h["number"] for h in order] == list(range(1, 65)), "order numbers"
    binaries = {h["binary"] for h in order}
    assert len(binaries) == 64, "unique binaries"
    assert all(len(b) == 6 and set(b) <= {"0", "1"} for b in binaries), "binary shape"
    assert all(h["subjectLine"] in range(1, 7) for h in order), "subjectLine range"

    horary_table = hexes["horaryTable"]
    assert len(horary_table) == 59, "horaryTable keys"
    assert all(v in binaries for v in horary_table.values()), "horaryTable binaries"

    pillars = hexes["pillars"]
    assert len(pillars) == 10, "pillar stems"
    assert sum(len(v) for v in pillars.values()) == 60, "pillar entries"
    covered = set()
    for stem, branches in pillars.items():
        for branch, numbers in branches.items():
            assert 1 <= len(numbers) <= 2, f"pillars[{stem}][{branch}] count"
            assert all(1 <= n <= 64 for n in numbers), f"pillars[{stem}][{branch}] range"
            covered.update(numbers)
    assert covered == set(range(1, 65)), "pillars cover all 64 hexagrams"

    assert len(hexes["animalOrder"]) == 6, "animalOrder"
    assert len(hexes["stemAnimalMap"]) == 10, "stemAnimalMap"
    assert len(hexes["hexagramStartBranches"]) == 8, "hexagramStartBranches"

    out = {k: hexes[k] for k in KEYS}

    os.makedirs(BACKUP_DIR, exist_ok=True)
    backup = os.path.join(BACKUP_DIR, "hexes-data.orig.json")
    if os.path.exists(TARGET) and not os.path.exists(backup):
        shutil.copy(TARGET, backup)
        print("backup written to", backup)

    with open(TARGET, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
        f.write("\n")

    print("wrote", TARGET, os.path.getsize(TARGET), "bytes")
    print("hexagrams:", len(order), "horary keys:", len(horary_table),
          "pillar entries:", sum(len(v) for v in pillars.values()))


if __name__ == "__main__":
    sys.exit(main())
