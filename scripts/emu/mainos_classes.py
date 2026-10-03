#!/usr/bin/env python3
"""Group the MAIN OS's named functions by class: count functions and bytes per name group (the part
of a Class::method name before the last ::), with the C++ standard-library templates collapsed into
one row. The names come from the RTTI walk (scripts/ghidra/NameFromRtti.java).

    python3 scripts/emu/mainos_classes.py [functions.tsv]

The file defaults to work/ghidra/out/dt_1.52A_emac/functions.tsv (written by ghidra_analyze.sh).
Reads only.
"""
import collections
import os
import re
import sys

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
path = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    REPO, "work", "ghidra", "out", "dt_1.52A_emac", "functions.tsv")
if not os.path.isfile(path):
    sys.exit(f"error: {path} not found; run GHIDRA_LANG_VARIANT=emac ./scripts/ghidra_analyze.sh dt main first")

cls_funcs = collections.Counter(); cls_bytes = collections.Counter()
named = 0; total = 0
for line in open(path):
    p = line.rstrip('\n').split('\t')
    if len(p) < 3 or p[0] == 'address':
        continue
    total += 1
    addr, size, name = p[0], int(p[1]), p[2]
    if name.startswith(('FUN_', 'entry_', 'thunk_FUN', 'LAB_')):
        continue
    named += 1
    # class = everything up to the last :: (strip the method)
    m = re.match(r'^([A-Za-z_][\w:<>,~ ]*?)::[~A-Za-z_]', name)
    c = m.group(1) if m else name      # a free function with a real name
    # collapse std/gnu noise
    if c.startswith(('std::', '__gnu', '_Sp_', '_Rb_', '_Hashtable', '_Vector', '_List')):
        c = '<stdlib>'
    cls_funcs[c] += 1; cls_bytes[c] += size
print(f"{named} named / {total} total functions")
print(f"{len(cls_funcs)} distinct name-groups")
print("=== top name-groups by function count ===")
for c, n in cls_funcs.most_common(80):
    if c == '<stdlib>':
        print(f"{n:4d}  {cls_bytes[c]:7d}B  <stdlib/templates>")
        continue
    print(f"{n:4d}  {cls_bytes[c]:7d}B  {c}")
