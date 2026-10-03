#!/usr/bin/env python3
"""Assemble the Digitakt's on-chip SRAM into one 64 KB image, as it looks when the section-2 "DSP"
boot code runs, so Ghidra can analyse that code in its true address space and resolve its SRAM
references.

The SRAM (0x80000000-0x80010000, two 32 KB banks) is filled at boot by the crt0 from two init images
in the MAIN OS tail; then the section-2 blob runs from 0x80000ec0:

    image 1 : DDR [0x40214000,0x40217350) -> SRAM 0x80000000  (13,136 B, almost all zero)
    updater : section 4                    -> SRAM 0x80000400  (the low-SRAM stub the DSP code also calls)
    image 2 : DDR [0x40217350,0x4021ea40) -> SRAM 0x80008000  (30,448 B; nonzero 0x8000c000-0x8000e663)
    DSP blob: section 2 minus its 24-B header -> SRAM 0x80000ec0 (26,646 B; overlays image 1's tail)

Everything else is zeroed by the crt0. The result is written to work/dt_1.52A/sram_unified.bin; import
it into Ghidra at base 0x80000000 on the ColdFire EMAC language, entry 0x80000ec0:

    python3 os/1.52A/scripts/build_sram_image.py
    GHIDRA_LANG_VARIANT=emac GHIDRA_PROJECT=dt_1.52A_sram ./scripts/ghidra_analyze.sh 1.52A sram

It is regenerable and gitignored: this script is the record of how, not the bytes.
Needs ./scripts/extract.sh 1.52A first. An optional argument names another extract folder under work/.
"""
import glob
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
OS_DIR = os.path.dirname(HERE)                  # os/<version>/, the OS folder this script sits in
OS_ID = os.path.basename(OS_DIR)
REPO = os.path.dirname(os.path.dirname(OS_DIR))

DDR_BASE = 0x40000400           # MAIN OS load base -> file offset = addr - DDR_BASE
SRAM_BASE = 0x80000000
SRAM_SIZE = 0x10000             # 64 KB, two 32 KB banks
DSP_HEADER = 24                 # section 2's inner header; word 2 of it is the run base 0x80000ec0

# (ddr_lo, ddr_hi, dst_sram): the two crt0 init images, taken from the MAIN OS tail
IMG1 = (0x40214000, 0x40217350, 0x80000000)     # low SRAM (vector table 0x80000000-0x400)
IMG2 = (0x40217350, 0x4021ea40, 0x80008000)     # bank 2 (fast data 0x8000c000+)
UPD_DST = 0x80000400                            # section-4 updater load base
DSP_DST = 0x80000ec0                            # the section-2 blob runs here
# Section 2 (DSP) calls real routines in the 0x80000400-0x80000ec0 low-SRAM stub that ships in
# section 4 (updater): 0x8000049a = cache/stack (re)init, 0x800006c2 = a byte-copy loop. The two
# sections share a low-SRAM boot library, so the updater is layered UNDER the DSP to make those calls
# resolve. Routines loaded at run time (e.g. 0x8000f010) are still zero here: that is code that is not
# in the image, not spare space.


def section(workdir, sid):
    """The one extracted section_<sid>_* file (matched by id prefix, derived .fromN.bin copies skipped)."""
    hits = [p for p in glob.glob(os.path.join(workdir, f"section_{sid}_*"))
            if os.path.isfile(p) and ".from" not in os.path.basename(p)]
    if len(hits) != 1:
        sys.exit(f"error: expected one section_{sid}_* file in {workdir}, found {len(hits)}; "
                 f"run ./scripts/extract.sh {OS_ID} first")
    return hits[0]


def main():
    workdir = os.path.join(REPO, "work", sys.argv[1] if len(sys.argv) > 1 else f"dt_{OS_ID}")
    main_img = open(section(workdir, 3), "rb").read()
    dsp = open(section(workdir, 2), "rb").read()[DSP_HEADER:]
    upd = open(section(workdir, 4), "rb").read()

    def ddr(a):
        return a - DDR_BASE

    sram = bytearray(SRAM_SIZE)                  # the crt0 zeroes what the images do not cover

    def put(dst, blob):
        off = dst - SRAM_BASE
        sram[off:off + len(blob)] = blob

    # Refuse an extract too short for the two init images before anything is layered.
    if len(main_img) < ddr(IMG2[1]):
        sys.exit(f"error: the MAIN OS (section 3) is short ({len(main_img)} of {ddr(IMG2[1])} B); "
                 f"wrong extract?")
    for layer, (lo, hi, _dst) in (("image 1", IMG1), ("image 2", IMG2)):
        n = len(main_img[ddr(lo):ddr(hi)])
        if n != hi - lo:
            sys.exit(f"error: {layer} is short ({n} of {hi - lo} B); wrong extract?")

    # Layer low to high; later layers win where they overlap.
    put(IMG1[2], main_img[ddr(IMG1[0]):ddr(IMG1[1])])   # 1. low SRAM / vectors (mostly zero)
    put(UPD_DST, upd)                                   # 2. updater = the shared low-SRAM stub (+ its own code)
    put(IMG2[2], main_img[ddr(IMG2[0]):ddr(IMG2[1])])   # 3. bank-2 fast data (wins over the updater tail)
    put(DSP_DST, dsp)                                   # 4. DSP code overlays the stub above 0x80000ec0
    if len(sram) != SRAM_SIZE:
        sys.exit("error: a layer ran past the end of SRAM")

    out = os.path.join(workdir, "sram_unified.bin")
    with open(out, "wb") as f:
        f.write(sram)
    end = DSP_DST + len(dsp)
    print(f"wrote {os.path.relpath(out, REPO)} ({len(sram)} B)")
    print(f"  image1 {IMG1[2]:#010x}, updater {UPD_DST:#010x} ({len(upd)} B), "
          f"image2 {IMG2[2]:#010x}, DSP {DSP_DST:#010x}-{end:#010x}")
    print("  import at 0x80000000 on 68000:BE:32:ColdfireEMAC, entry 0x80000ec0")


if __name__ == "__main__":
    # Refuse a folder that is not a work folder of this OS (work/dt_<OS_ID>/ or work/dt_<OS_ID>-<build>/)
    # before anything is read.
    if len(sys.argv) > 1:
        name = sys.argv[1].rstrip("/")
        own = f"dt_{OS_ID}"
        if "/" in name or "\\" in name or not (name == own or name.startswith(own + "-")):
            sys.exit(f"error: refusing work/{sys.argv[1]}: not a work folder of OS {OS_ID}")
    main()
