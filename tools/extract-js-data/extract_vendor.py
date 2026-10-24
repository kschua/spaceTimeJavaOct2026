#!/usr/bin/env python3
"""Extracts vendored third-party sources from the app's JS bundle source map.

Currently extracts the exact luxon build the app shipped
(node_modules/luxon/build/cjs-browser/luxon.js) into vendor/luxon.cjs, so the
golden-render harness can run the original RN code with the original datetime
semantics.

Usage:
    python3 extract_vendor.py            # uses the default map path
    SOURCE_MAP=/path/index.android.bundle.map python3 extract_vendor.py
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_MAP = (
    '/mnt/c/Temp/space/decoded/files/CodePush/'
    '858b9c9cc2bb3dcbfb3051f839b6f5696f0a993d3611173edec4df7b244a1a74/'
    'CodePush/index.android.bundle.map'
)
SOURCE_MAP = os.environ.get('SOURCE_MAP', DEFAULT_MAP)

TARGETS = {
    'node_modules/luxon/build/cjs-browser/luxon.js': 'vendor/luxon.cjs',
}


def main():
    if not os.path.exists(SOURCE_MAP):
        print(f'source map not found: {SOURCE_MAP}', file=sys.stderr)
        return 1
    with open(SOURCE_MAP, encoding='utf-8') as f:
        m = json.load(f)
    sources = m['sources']
    contents = m.get('sourcesContent') or []
    for suffix, out_rel in TARGETS.items():
        idx = [i for i, s in enumerate(sources) if s.endswith(suffix)]
        if len(idx) != 1:
            print(f'expected exactly one source ending with {suffix}, found {len(idx)}',
                  file=sys.stderr)
            return 1
        src = contents[idx[0]]
        if not src:
            print(f'no sourcesContent for {suffix}', file=sys.stderr)
            return 1
        out = os.path.join(HERE, out_rel)
        os.makedirs(os.path.dirname(out), exist_ok=True)
        with open(out, 'w', encoding='utf-8') as f:
            f.write(src)
        print(f'wrote {out} ({len(src)} chars)')
    return 0


if __name__ == '__main__':
    sys.exit(main())
