#!/usr/bin/env python3
"""Notes consistency helper: list every mention of a set of key addresses across a folder of
markdown notes, one line of context each, so contradictions between notes are easy to spot.

    python3 scripts/emu/audit.py [notes-folder] [address ...]

The folder defaults to notes/ in the repo root; the addresses default to the list below (the
engine, slice and render addresses the notes cite most). Reads only.
"""
import collections
import glob
import os
import sys

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
KEYS = ['4395df4c', '4395df20', '4395ddf4', '4395df48', '80001f28', '80001f2c', '80002760',
        '402bb3b0', '439902a0', '80001228', '8000122c', '8000ee20', '40074af2', '40074e84',
        '400754fe', '40077120', '4018ff88', '40191b28', '4005f7b0', '401a893c', 'fc0b8000', '80000ec0']

folder = sys.argv[1] if len(sys.argv) > 1 else os.path.join(REPO, "notes")
keys = [k.lower()[2:] if k.lower().startswith("0x") else k.lower() for k in sys.argv[2:]] or KEYS
files = sorted(glob.glob(os.path.join(folder, "**", "*.md"), recursive=True))
occ = collections.defaultdict(list)
for f in files:
    for i, line in enumerate(open(f, errors='ignore'), 1):
        low = line.lower()
        for k in keys:
            if k in low:
                # a short context window around the key
                idx = low.find(k)
                ctx = line[max(0, idx - 30):idx + len(k) + 45].strip().replace('\n', ' ')
                occ[k].append((os.path.relpath(f, folder), i, ctx))
for k in keys:
    print(f"\n########## {k}  ({len(occ[k])} refs) ##########")
    seen = set()
    for f, i, ctx in occ[k]:
        key = ctx.lower()
        if key in seen:
            continue
        seen.add(key)
        print(f"  {f}:{i}  {ctx[:95]}")
