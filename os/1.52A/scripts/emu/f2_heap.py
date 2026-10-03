#!/usr/bin/env python3
"""Print the allocator's pool-control words in the MAIN OS .data (0x401d1a40..0x401d1a64) and say
where each points (into .bss/heap, or into .text/.data), then how far the highest static .bss
reference lies below 0x439902a0, the end of .bss (the crt0 clear boundary), just above which the
DT OG++ patches keep their state block (slice counters, latches, the POLY groupSource map). That
highest reference is a constant taken from a RefDensityMap / DumpRefsInRange run over .bss, not
computed from the file.

    python3 os/1.52A/scripts/emu/f2_heap.py [section_3 file]

The file defaults to the stock MAIN OS extracted by ./scripts/extract.sh 1.52A
(work/dt_1.52A/section_3_*). Reads only.
"""
import glob
import os
import struct
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
OS_DIR = os.path.dirname(os.path.dirname(HERE))     # os/<version>/, the OS folder this script sits in
OS_ID = os.path.basename(OS_DIR)
REPO = os.path.dirname(os.path.dirname(OS_DIR))
if len(sys.argv) > 1:
    path = sys.argv[1]
else:
    hits = [p for p in glob.glob(os.path.join(REPO, "work", f"dt_{OS_ID}", "section_3_*"))
            if ".from" not in os.path.basename(p)]
    if len(hits) != 1:
        sys.exit(f"error: no single work/dt_{OS_ID}/section_3_* file; run ./scripts/extract.sh {OS_ID} first")
    path = hits[0]
d = open(path, 'rb').read()
BASE = 0x40000400                # MAIN OS load base: file offset = load address - BASE


def w(load):
    off = load - BASE
    return struct.unpack('>I', d[off:off + 4])[0]


print("allocator pool-control words (.data @ 0x401d1a40..):")
for load in range(0x401d1a40, 0x401d1a64, 4):
    v = w(load)
    tag = ""
    if 0x40214000 <= v < 0x48000000: tag = " <- points INTO the .bss/heap region"
    if 0x40000400 <= v < 0x40214000: tag = " <- points into .text/.data"
    print(f"  0x{load:08x} = 0x{v:08x}{tag}")
print()
CNT = 0x439902a0
HIGHEST_BSS_REF = 0x439901ec     # highest static .bss reference (RefDensityMap / DumpRefsInRange on .bss)
print(f"the patches' state block starts at 0x{CNT:08x} (end of .bss / crt0 clear boundary)")
print(f"highest static .bss reference = 0x{HIGHEST_BSS_REF:08x}  => 0x{CNT - HIGHEST_BSS_REF:x} bytes below it")
