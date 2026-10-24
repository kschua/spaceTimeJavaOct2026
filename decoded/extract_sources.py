#!/usr/bin/env python3
"""Re-extract original RN sources from the Metro source map.

Reads index.android.bundle.map (from decoded/files/CodePush/<hash>/CodePush/)
and writes the original sources out, keeping their relative path layout.

The map is the single source of truth: decoded/src/ is fully regenerable from
it, so this script can either reproduce decoded/src/ or verify that an existing
copy still matches.

usage:
  extract_sources.py                      # write everything to the output dir
  extract_sources.py --project-only       # write only app sources (no node_modules)
  extract_sources.py --verify             # write nothing; diff against decoded/src/
  extract_sources.py --out DIR --clean    # choose destination and wipe it first
"""

import argparse
import difflib
import json
import os
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
DECODED_ROOT = HERE
DEFAULT_OUT = "/mnt/c/Temp/myTmp/bundle-sources"
APP_ROOT_MARKERS = ("/src/",)
ASSET_SUFFIXES = (".png", ".jpg", ".jpeg", ".gif", ".svg", ".webp")


def find_bundle_map():
    fixed = (
        DECODED_ROOT / "files/CodePush"
        "/858b9c9cc2bb3dcbfb3051f839b6f5696f0a993d3611173edec4df7b244a1a74"
        "/CodePush/index.android.bundle.map"
    )
    if fixed.is_file():
        return fixed
    found = sorted(DECODED_ROOT.rglob("index.android.bundle.map"))
    if not found:
        sys.exit(f"bundle map not found under {DECODED_ROOT}")
    return found[0]


def guard_out(out: Path):
    """Refuse to write anywhere inside the repo's decoded/ tree."""
    repo = DECODED_ROOT.parent.resolve()
    target = out.resolve()
    for guard in (DECODED_ROOT.resolve(), repo):
        if target == guard or guard in target.parents:
            sys.exit(
                f"refusing to write inside the repository: {target}\n"
                "extraction output must go to a scratch directory"
            )


def classify(src, i):
    if not isinstance(src, str):
        return f"module_{i}.js", False
    is_project = any(m in src for m in APP_ROOT_MARKERS) and "/node_modules/" not in src
    return src.lstrip("/"), is_project


def norm(text):
    return "\n".join(text.rstrip().splitlines())


def whitespace_only(a: str, b: str) -> bool:
    """True if the two texts differ only by blank / whitespace-only lines."""
    al, bl = a.splitlines(), b.splitlines()
    sm = difflib.SequenceMatcher(None, al, bl, autojunk=False)
    for tag, i1, i2, j1, j2 in sm.get_opcodes():
        if tag == "equal":
            continue
        for line in al[i1:i2] + bl[j1:j2]:
            if line.strip():
                return False
    return True


def build_plan(sm):
    sources = sm.get("sources") or []
    contents = sm.get("sourcesContent") or []
    plan = []
    for i in range(min(len(sources), len(contents))):
        content = contents[i]
        if content is None:
            continue
        rel, is_project = classify(sources[i], i)
        plan.append((rel, content, is_project))
    return plan


def do_write(plan, out: Path, project_only: bool):
    written = 0
    for rel, content, is_project in plan:
        if project_only and not is_project:
            continue
        dest = out / rel
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_text(content, encoding="utf-8")
        written += 1
    return written


def do_verify(plan, decoded: Path):
    exact = blank_only = mismatched = 0
    problems = []
    expected = set()
    for rel, content, is_project in plan:
        if not is_project:
            continue
        expected.add(rel)
        target = decoded / rel
        if not target.is_file():
            problems.append(f"missing on disk: {rel}")
            mismatched += 1
            continue
        actual = target.read_text(encoding="utf-8", errors="replace")
        if actual == content:
            exact += 1
        elif whitespace_only(actual, content):
            blank_only += 1
        else:
            mismatched += 1
            problems.append(f"content differs: {rel}")

    on_disk = {
        str(p.relative_to(decoded))
        for p in (decoded / "src").rglob("*")
        if p.is_file()
    }
    extra = sorted(on_disk - expected)

    print(f"  exact match          : {exact}")
    print(f"  whitespace-only differences : {blank_only}")
    print(f"  genuinely different  : {mismatched}")
    print(f"  files on disk not produced by the map : {len(extra)}")
    for p in extra[:20]:
        problems.append(f"on disk but not generated: {p}")
    return exact, blank_only, mismatched, problems


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--out", default=os.environ.get("DECODED_EXTRACT_OUT", DEFAULT_OUT),
                    help=f"output directory (default {DEFAULT_OUT})")
    ap.add_argument("--project-only", action="store_true",
                    help="write only app sources, skipping node_modules")
    ap.add_argument("--clean", action="store_true", help="remove the output directory first")
    ap.add_argument("--verify", action="store_true",
                    help="write nothing; compare the map against decoded/src/")
    args = ap.parse_args()

    bmap = find_bundle_map()
    print(f"Reading {bmap.name} ({bmap.stat().st_size/1e6:.1f} MB) ...")
    with open(bmap, "r", encoding="utf-8") as f:
        sm = json.load(f)

    plan = build_plan(sm)
    n_project = sum(1 for _, _, p in plan if p)
    print(f"sources with content : {len(plan)}")
    print(f"  project sources    : {n_project}")

    if args.verify:
        src_tree = DECODED_ROOT / "src"
        if not src_tree.is_dir():
            print(f"\n{src_tree} does not exist - nothing to verify.")
            print(f"It is fully regenerable from this map ({n_project} project sources).")
            print("Restore with:")
            print(f"  {Path(sys.argv[0]).name} --project-only --clean")
            print(f"  cp -r {DEFAULT_OUT}/src {src_tree}")
            return 0
        print(f"\nVerifying {src_tree} against the map ...")
        exact, blank, bad, problems = do_verify(plan, DECODED_ROOT)
        for p in problems[:20]:
            print(f"  ! {p}")
        ok = bad == 0 and not any(x.startswith("on disk but not") for x in problems)
        print(f"\nRESULT: {'decoded/src is fully regenerable' if ok else 'MISMATCH'}")
        return 0 if ok else 1

    out = Path(args.out).expanduser().resolve()
    guard_out(out)
    if args.clean and out.is_dir():
        import shutil
        shutil.rmtree(out)
    out.mkdir(parents=True, exist_ok=True)

    written = do_write(plan, out, args.project_only)
    print(f"wrote {written} files to {out}")
    if args.project_only:
        print("  (project sources only; node_modules skipped)")
    return 0


if __name__ == "__main__":
    sys.exit(main())