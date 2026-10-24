#!/usr/bin/env python3
"""Merge raw-dump.json (produced by dump.mjs) into the Spring Boot service's
bazi-data.json.

- Replaces stems / branches / sixtyPillars / branchSpecialRelations / auxDetails
  with the complete originals from the RN sources (they are strict supersets of
  the hand-trimmed versions that were in the file).
- Adds the tables the Java port was still missing:
  elements, seasons, twelveStagesData, streams, guas, interaction orderings,
  display dicts (aspects/gods/pillars), annualAuxDetails, annualStarAux.
- Keeps everything else untouched (dms, lifePalace*, scores, solarTerms and the
  shen-sha matching tables dayStemAux, dayBranchAux, monthBranchAux,
  yearBranchAux, specialDayMasterAux, specialPillarAux).

Usage:
    python3 merge_data.py            # uses raw-dump.json next to this script
"""
import json
import os
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(HERE, "raw-dump.json")
TARGET = os.path.abspath(os.path.join(
    HERE, "..", "..", "bazi-spring", "src", "main", "resources", "data", "bazi-data.json"))
BACKUP_DIR = os.path.join(HERE, "backup")


def main():
    with open(RAW, encoding="utf-8") as f:
        raw = json.load(f)
    with open(TARGET, encoding="utf-8") as f:
        data = json.load(f)

    o = raw["ordinals"]
    s = raw["shensha"]

    # ---- sanity checks on the dump ----
    assert len(o["stems"]) == 10, "stems"
    assert len(o["branches"]) == 12, "branches"
    assert all("relations" in b for b in o["branches"].values()), "branch relations"
    assert all("_hiddenStemsDict" in b for b in o["branches"].values()), "branch hidden stem dicts"
    pillars = [p for stems in o["sixtyPillars"].values() for p in stems.values()]
    assert len(pillars) == 60, "sixtyPillars"
    assert all(isinstance(p["naYin"], list) and len(p["naYin"]) == 3 for p in pillars), "naYin arrays"
    assert len(o["streams"]) == 6 and all("void" in v for v in o["streams"].values()), "streams"
    assert len(o["elements"]) == 5 and all("elements" in v for v in o["elements"].values()), "elements"
    assert len(o["guas"]) == 9, "guas"
    assert len(o["twelveStagesData"]) == 12, "twelveStagesData"
    assert len(s["auxDetails"]) == 6, "auxDetails groups"
    assert len(s["annualAuxDetails"]) == 49, "annualAuxDetails"
    assert list(s["annualStarAux"].keys()) == ["zi"], "annualStarAux groups"
    assert len(s["annualStarAux"]["zi"]) == 12, "annualStarAux branches"
    # auxDetails must keep the same groups as the file already had
    assert set(s["auxDetails"]) == set(data["auxDetails"]), "auxDetails group mismatch"

    os.makedirs(BACKUP_DIR, exist_ok=True)
    backup = os.path.join(BACKUP_DIR, "bazi-data.orig.json")
    if not os.path.exists(backup):
        shutil.copy(TARGET, backup)
        print("backup written to", backup)

    # ---- merge ----
    data["stems"] = o["stems"]
    data["branches"] = o["branches"]
    data["sixtyPillars"] = o["sixtyPillars"]
    data["branchSpecialRelations"] = o["branchSpecialRelations"]
    data["elements"] = o["elements"]
    data["seasons"] = o["seasons"]
    data["twelveStagesData"] = o["twelveStagesData"]
    data["streams"] = o["streams"]
    data["guas"] = o["guas"]
    data["branchInteractionOrdering"] = o["branchInteractionOrdering"]
    data["stemInteractionOrdering"] = o["stemInteractionOrdering"]
    data["display"] = raw["display"]
    data["auxDetails"] = s["auxDetails"]
    data["annualAuxDetails"] = s["annualAuxDetails"]
    data["annualStarAux"] = s["annualStarAux"]

    with open(TARGET, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
        f.write("\n")

    print("merged into", TARGET, os.path.getsize(TARGET), "bytes")
    print("top-level keys:", ", ".join(data.keys()))


if __name__ == "__main__":
    sys.exit(main())
