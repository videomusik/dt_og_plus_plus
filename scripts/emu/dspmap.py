#!/usr/bin/env python3
"""Tabulate the DSP section's functions by what hardware they touch: for every decompiled function,
its size, caller/callee counts, indirect calls, whether it halts, the peripheral modules it
addresses (MCF54415 map: SSI, eDMA, DMA crossbar, DSPI, PIT/INTC, FlexBus) and a few DDR addresses.

    SECTION=dsp ./scripts/ghidra_decompile.sh dt 're:.*'          # decompile every DSP function first
    python3 scripts/emu/dspmap.py [decomp-folder]

The folder defaults to work/ghidra/out/dt_1.52A_dsp/decomp. Prints a table, largest function first.
The table lists addresses and module names only; the decompiled C it reads stays in work/.
"""
import glob
import os
import re
import sys

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
D = sys.argv[1] if len(sys.argv) > 1 else os.path.join(REPO, "work", "ghidra", "out", "dt_1.52A_dsp", "decomp")


def mod(a):
    a = a.lower()
    if a.startswith("fc0b8"): return "SSI(audio)"
    if a.startswith("fc044") or a.startswith("fc045"): return "eDMA"
    if a.startswith("fc004"): return "DMAxbar"
    if a.startswith("fc0c8"): return "SSI1?"
    if a.startswith("fc04c"): return "INTC/DMA"
    if a.startswith("fc04002") or a.startswith("fc0400"): return "DMAreq"
    if a.startswith("fc03c") or a.startswith("fc05c"): return "DSPI"
    if a.startswith("fc084"): return "PIT/INTC"
    if a.startswith("fc"): return "periph"
    if a.startswith("ec07"): return "FlexBus.disp?"
    if a.startswith("ec09"): return "FlexBus.io"
    if a.startswith("ec"): return "FlexBus"
    return None


if not os.path.isdir(D):
    sys.exit(f"error: {D} not found; run SECTION=dsp ./scripts/ghidra_decompile.sh dt 're:.*' first")
rows = []
for f in sorted(glob.glob(os.path.join(D, "FUN_*.c"))):
    t = open(f, errors='ignore').read()
    m = re.search(r"@ ([0-9a-f]+)\s+size (\d+)", t)
    if not m:
        continue
    addr, size = m.group(1), int(m.group(2))
    ncallees = len(re.findall(r"FUN_[0-9a-f]+@", t.split("callees")[1].split("\n")[0])) if "callees" in t else 0
    ncallers = 0
    mc = re.search(r"callers \((\d+)\)", t)
    if mc:
        ncallers = int(mc.group(1))
    # peripheral / FlexBus modules touched
    mods = set()
    for a in re.findall(r"(?:DAT_|0x)((?:fc|ec)[0-9a-f]{4,6})", t):
        mm = mod(a)
        if mm:
            mods.add(mm)
    # DDR touched (0x40-0x4f; the section's own 0x8000xxxx SRAM is left out)
    dram = sorted(set(re.findall(r"(?:DAT_|0x)(4[0-9a-f]{6,7})", t)))
    dram = [d for d in dram if not d.startswith("4f4f")][:4]
    # indirect calls / halts
    ind = len(re.findall(r"\(\*\(code \*\)&LAB_", t)) + len(re.findall(r"func_0x[0-9a-f]+", t))
    halt = "halt" in t
    rows.append((addr, size, ncallers, ncallees, ind, halt, ",".join(sorted(mods)), ",".join(dram)))
rows.sort(key=lambda r: -r[1])
print(f"{'addr':10} {'sz':>4} {'clr':>3} {'cle':>3} {'ind':>3} h {'modules':22} dram")
for r in rows:
    print(f"{r[0]:10} {r[1]:4d} {r[2]:3d} {r[3]:3d} {r[4]:3d} {'H' if r[5] else '.'} {r[6]:22} {r[7]}")
print("total", len(rows), "functions")
