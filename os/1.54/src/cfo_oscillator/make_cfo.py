#!/usr/bin/env python3
"""CFO oscillator for OS 1.54: generate the wavetables, assemble cfo.s with a placement linker script,
check it against the stock image and the current build, and write an emulator load file.

    python3 os/1.54/src/cfo_oscillator/make_cfo.py <placement.ld>            # check, write the load file
    python3 os/1.54/src/cfo_oscillator/make_cfo.py <placement.ld> --stages   # also build stage images

Needs: m68k binutils (M68K_PREFIX, default 'm68k-elf-', as in scripts/common.sh), the stock section 3
from ./scripts/extract.sh 1.54 (work/dt_1.54/section_3_MAIN_OS.bin) and, for --stages, what build.py
needs (the stock .syx in sysex/ and the firmware tool).

The placement linker script puts the hook at 0x40077fc8, the code sections in landing pads and the data
in the .rodata padding. The script checks:
- the hook site still holds the stock `jsr FUN_40072478`;
- each code section lies inside one pad named in PADS, and no other feature's run is there;
- the data lies in the free .rodata padding, which is all zero in stock;
- the current patch.json reproduces its own section-3 hash.

The load file (work/dt_1.54-cfo/<placement>.load) is the input of the EmuCfoOscillator harness.
Stages, written to out/1.54/stages/ (only with --stages):
  S9   the build + the pads filled with 'clrl %d0 ; rts' (their fill test)
  S10  S9 + the hook, its pad only replaying the stock call (jmp FUN_40072478)
  S11  S9 + the CFO oscillator prototype, playing on ONESHOT with SAMP OFF
  S12  S9 + the machine CFOO (machine 5, after POLY) and the synth playing on it
  S13  S12 + CFOO's own parameter names on its SRC page
  S14  S13 + a placeholder picker icon for CFOO
  S15  S14 + a machine change to CFOO resets its SRC slots (ONESHOT's ids for machine 5)
  S16  S15 + CFOO's own knobs, ranges and defaults
  S17  S16 + CFOO's own value displays: the cell's picture and text, and the encoder popup's value
  S18  S17 + CFOO's knobs as agreed after S17: integer steps, three FM sources, +-24 semitone detunes,
       no sample picker on D, pitches above the stock table's top
  S19  S18 + CFOO's ranges, cell pictures and cell texts find the machine through the parameter set's
       sound holder, and the validity test, the reset to default, MIDI CC and the all-tracks edit take
       CFOO's ranges
  S20  S19 + the pure waves and the four mixes exactly at 0, 42, 85 and 127; the name and range tables
       move to the .rodata padding after the icon, so the pad holds code only
  S21  S20 + [FUNC] + knob steps A, C, D, E to 0, 42, 85, 127 and G, H to -24, -17, -12, -5, 0, +7,
       +12, +19, +24 semitones
  S22  S21 + the [TRK] popup shows POLY and CFOO without a sample name
  S23  S22 + CFOO's names as LFO destinations, on the DEST knob and in the destination list
  S24  S23 + A, C, D and E read SIN, TRI, SAW, SQR and OSC1, 1+2, 123, 2+3 at 0, 42, 85, 127
  S25  S24 + the SRC page's title and popup show POLY and CFOO without a sample name
  S26  S25 + those three texts show a POLY track as POLY and its Source's machine; this build's calls
       between its own routines become bsr.w
patch.json is not changed: the feature is a prototype."""
import json, os, shutil, subprocess, sys, tempfile
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", "..", ".."))
sys.path.insert(0, os.path.join(ROOT, "os", "1.54", "build"))
import build  # noqa: E402
PFX = os.environ.get("M68K_PREFIX", "m68k-elf-")
BASE = 0x40000400
PATCH = os.path.join(ROOT, "os", "1.54", "build", "patch.json")
STOCK3 = os.path.join(ROOT, "work", "dt_1.54", "section_3_MAIN_OS.bin")
HOOKS = {
    ".hook_fill": (0x40077fc8, "4eb940072478"),          # jsr 0x40072478, after the two render lanes
    ".hook_layout": (0x400657e6, "203c4197df5c4e75"),    # FUN_400657cc's fallback: SLICE's record
    ".hook_slots": (0x40078f72, "7403b48065dc"),         # FUN_40078f44: machine > 3 gives id 0 (S15)
    ".hook_popup": (0x40032d16, "4eb9400657ee"),         # the encoder popup's jsr FUN_400657ee (S17)
    ".hook_pic": (0x4000f2bc, "4fefffec48d7007c"),       # ParameterSet::vfunc_23's first 8 B (S17)
    ".hook_ctext": (0x4000f324, "4fefffec48d70c1c"),     # ParameterSet::vfunc_22's first 8 B (S17)
    ".hook_encobj": (0x40032b74, "4eb940065794"),        # the encoder handler's jsr FUN_40065794 (S18)
    ".hook_samp": (0x4003b5a0, "726f240070ef"),          # the SRC page's Sample Slot test (S18)
}
# Candidate pads: extent and member functions (for the fill). Only pads named here may hold code.
PADS = {
    "lz4_stream": [(0x400f77da, 0x400f811e)],    # FUN_400f77da, LZ4's streaming compressor (not a leaf)
}
RODATA = (0x40252724, 0x40252b50)               # the free .rodata padding below the icons
RODATA2 = (0x40252c2c, 0x40253000)              # the free .rodata padding after Chain Recording's strings
NAMES_RO = 0x40252c80                           # S20 on: the name and range tables, after the icon
# S14: CFOO's icon. Expected bytes: the build's (the POLY edits of the mapper bound and the icon range,
# and the pad's selector-table pointer).
ICON_EDITS = [
    (0x40029e81, "04", "05", "the group mapper codes machine 5 as 6"),
    (0x40029ef9, "01", "02", "codes 4..6 take the icon pad"),
]
ICON_PTR = (0x400bee46, "40252bf8", "icon_table")
# Registering machine 5 (CFOO). Every site already carries the POLY build's edit, so the expected bytes
# are the build's, not stock. The two name-table pointers are filled in once the table is placed.
EDITS = [
    (0x400225f1, "04", "05", "the machine setter accepts machine 5"),
    (0x40022f81, "14", "18", "the machine-list vector holds 6 ints"),
    (0x40022fb3, "14", "18", "its end moves with it"),
    (0x40022fe7, "05", "06", "the builder pushes 6 machine numbers"),
    (0x4007910d, "04", "05", "the long-name reader accepts machine 5"),
    (0x4007912d, "04", "05", "the short-name reader accepts machine 5"),
    (0x4007a2d1, "06", "07", "a stored machine 5 survives a project reload"),
]
NAME_PTRS = [(0x4007911a, "400c1034", 0), (0x4007913a, "400c1038", 4)]
# CFOO's own parameter names (S13): MIDI Loopback's two label pads end in a jump to the stock accessor;
# the jump operands are pointed at the rename routines. Expected bytes: the build's.
RENAME_JMPS = [(0x40015648, "4000fe8a", "cfo_short"), (0x4001567a, "4000feac", "cfo_long")]
LABEL_PADS = (0x4001562c, 0x4001567e)          # the build's two label pads, for the harness
ICON_CODE = [(0x40029e80, 0x40029f6a), (0x400bee44, 0x400bee54)]   # the mapper, the icon routine, the pad
# S16: the operands of five calls of FUN_40078f0c made with the parameter set in %a2 (four jsr, one lea
# into %a5 in FUN_400220fc). Expected bytes: stock, and no feature of the build patches them.
RANGE_SITES = [0x4000f536, 0x4000ff22, 0x400100c6, 0x40010156, 0x4002213a]
# S19: four more FUN_40078f0c operands, each to the cfo_range entry for where its caller keeps the set:
# ParameterSet::vfunc_9 (a lea into %a2), vfunc_4 and vfunc_26 (jsr), the all-tracks edit in
# MachineParameterPageView::vfunc_23 (jsr). Expected bytes: stock.
RANGE_SITES2 = [(0x4000f5fe, "cfo_range9"), (0x4000ffae, "cfo_range4"), (0x40010d52, "cfo_range"),
                (0x400326ac, "cfo_range_a3")]
# S21 on: operands pointed at this build's routines, each applied when its symbol is in the assembly:
# (operand address, stock bytes, symbol). ParameterSet::vfunc_11's lea of FUN_40065794 into %a4 (the
# display object whose +0x44 callable [FUNC] + knob uses); the [TRK] popup's call of FUN_40093ab0; the
# LFO DEST picture's std::string of the destination's group.
OPERAND_SITES = [(0x40010054, "40065794", "cfo_fobj"), (0x4003bd6c, "40093ab0", "cfo_trkpop"),
                 (0x40065dee, "4017af20", "cfo_lfogrp"),
                 # S26: the three machine-and-sample texts' formatter calls (S22's and S25's sites left stock)
                 (0x4003bd6c, "40093ab0", "cfo_trkpop2"), (0x4003b51e, "40093ab0", "cfo_srcpop"),
                 (0x4003a6da, "40000e82", "cfo_srctitle2")]
# S23 on: instructions replaced by a jsr to this build's routine plus a tail, each applied when its symbol
# is in the assembly: (address, stock bytes, symbol, tail). The LFO DEST picture's short-name push, and
# the destination list's long-name and short-name pushes.
REPL_SITES = [(0x40065e5e, "70344c0038002f303830", "cfo_lfocell", "2f004e71"),
              (0x400a44d0, "2f3308282f06", "cfo_lfolist", ""),
              (0x400a454c, "2f3328302f06", "cfo_lfolist2", ""),
              (0x4003a6ce, "4879401c41c0", "cfo_srctitle", ""),   # S25: the SRC page title's format
              (0x4003b512, "4879401c41c0", "cfo_srcfmt", "")]     # S25: SamplePageView::vfunc_2's


def fill(lo, hi):
    n = hi - lo
    return bytes.fromhex("42804e75") * (n // 4) + (bytes.fromhex("4e75") if n % 4 else b"")


def run(*cmd):
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode:
        sys.exit("%s\n%s%s" % (" ".join(cmd), r.stdout, r.stderr))
    return r.stdout


def assemble(ld, tmp, inert=False, machine5=False, names=False, icon=False, slots=False, knobs=False,
             displays=False, knobs2=False, sets=False, pure=False, snap=False, trkpop=False, lfonames=False,
             corners=False, srcname=False, polytext=False):
    run(sys.executable, os.path.join(HERE, "make_waves.py"), os.path.join(tmp, "waves.inc"))
    src = os.path.join(HERE, "cfo.s")
    defs = (["--defsym", "MACHINE5=1"] if machine5 else []) + (["--defsym", "NAMES=1"] if names else []) + \
        (["--defsym", "ICON=1"] if icon else []) + (["--defsym", "SLOTS=1"] if slots else []) + \
        (["--defsym", "KNOBS=1"] if knobs else []) + (["--defsym", "DISPLAYS=1"] if displays else []) + \
        (["--defsym", "KNOBS2=1"] if knobs2 else []) + (["--defsym", "SETS=1"] if sets else []) + \
        (["--defsym", "PURE=1"] if pure else []) + (["--defsym", "SNAP=1"] if snap else []) + \
        (["--defsym", "TRKPOP=1"] if trkpop else []) + (["--defsym", "LFONAMES=1"] if lfonames else []) + \
        (["--defsym", "CORNERS=1"] if corners else []) + (["--defsym", "SRCNAME=1"] if srcname else []) + \
        (["--defsym", "POLYTEXT=1", "--defsym", "SHORT=1"] if polytext else [])
    ldefs = ["--section-start=.cfo_names=0x%08x" % NAMES_RO] if pure else []
    if inert:
        src = os.path.join(tmp, "inert.s")
        with open(src, "w") as f:
            f.write("\t.section .hook_fill,\"ax\"\n\tjsr\tcfo_pad\n\t.section .cfo_main,\"ax\"\n"
                    "cfo_pad:\n\tjmp\t0x40072478\n")
    o, e = os.path.join(tmp, "cfo.o"), os.path.join(tmp, "cfo.elf")
    run(PFX + "as", "-mcpu=5475", "-I", tmp, *defs, "-o", o, src)
    run(PFX + "ld", "-T", ld, *ldefs, "-o", e, o)
    secs = {}
    for line in run(PFX + "objdump", "-h", e).splitlines():
        p = line.split()
        if len(p) >= 4 and p[1].startswith(".") and int(p[2], 16):
            b = os.path.join(tmp, p[1] + ".bin")
            run(PFX + "objcopy", "-O", "binary", "-j", p[1], e, b)
            secs[p[1]] = (int(p[3], 16), build.read(b))
    syms = {}
    for line in run(PFX + "nm", e).splitlines():
        a, t, n = line.split()
        if t not in "Uw":
            syms[n] = int(a, 16)
    return secs, syms


def apply_edits(img, syms):
    """The machine-5 registration, checked byte for byte against the build before it is applied."""
    for addr, want, new, what in EDITS:
        off = addr - BASE
        got = img[off:off + len(want) // 2].hex()
        assert got == want, "0x%08x holds %s, expected %s (%s)" % (addr, got, want, what)
        img[off:off + len(new) // 2] = bytes.fromhex(new)
    for addr, want, delta in NAME_PTRS:
        off = addr - BASE
        got = img[off:off + 4].hex()
        assert got == want, "0x%08x holds %s, expected %s (the name table pointer)" % (addr, got, want)
        img[off:off + 4] = (syms["machnames"] + delta).to_bytes(4, "big")
    return img


def apply_renames(img, syms):
    """S13: the label pads' fall-through jumps point at the rename routines."""
    for addr, want, sym in RENAME_JMPS:
        off = addr - BASE
        got = img[off:off + 4].hex()
        assert got == want, "0x%08x holds %s, expected %s (a label pad's fall-through)" % (addr, got, want)
        img[off:off + 4] = syms[sym].to_bytes(4, "big")
    return img


def apply_ranges(img, syms):
    """S16: the five range calls go to cfo_range; S19 (a load with set_machine): four more, to their
    entries."""
    sites = [(a, "cfo_range") for a in RANGE_SITES] + (RANGE_SITES2 if "set_machine" in syms else [])
    for addr, sym in sites:
        off = addr - BASE
        assert img[off:off + 4].hex() == "40078f0c", "0x%08x is not a call operand of FUN_40078f0c" % addr
        img[off:off + 4] = syms[sym].to_bytes(4, "big")
    return img


def apply_icon(img, syms):
    """S14: the mapper bound, the icon range, and the pad's selector table."""
    for addr, want, new, what in ICON_EDITS:
        off = addr - BASE
        got = img[off:off + 1].hex()
        assert got == want, "0x%08x holds %s, expected %s (%s)" % (addr, got, want, what)
        img[off:off + 1] = bytes.fromhex(new)
    addr, want, sym = ICON_PTR
    off = addr - BASE
    assert img[off:off + 4].hex() == want, "0x%08x is not the icon pad's table pointer" % addr
    img[off:off + 4] = syms[sym].to_bytes(4, "big")
    return img


# The stages from S16 on, each on top of S15's assembly flags (machine5, names, icon, slots): the stage,
# its load-file suffix (<placement>_m5<suffix>.load) and its own flags.
KNOB_STAGES = [
    ("S16", "k", dict(knobs=True)),
    ("S17", "d", dict(knobs=True, displays=True)),
    ("S18", "e", dict(knobs=True, displays=True, knobs2=True)),
    ("S19", "f", dict(knobs=True, displays=True, knobs2=True, sets=True)),
    ("S20", "g", dict(knobs=True, displays=True, knobs2=True, sets=True, pure=True)),
    ("S21", "h", dict(knobs=True, displays=True, knobs2=True, sets=True, pure=True, snap=True)),
    ("S22", "i", dict(knobs=True, displays=True, knobs2=True, sets=True, pure=True, snap=True, trkpop=True)),
    ("S23", "j", dict(knobs=True, displays=True, knobs2=True, sets=True, pure=True, snap=True, trkpop=True,
                      lfonames=True)),
    ("S24", "l", dict(knobs=True, displays=True, knobs2=True, sets=True, pure=True, snap=True, trkpop=True,
                      lfonames=True, corners=True)),
    ("S25", "m", dict(knobs=True, displays=True, knobs2=True, sets=True, pure=True, snap=True, trkpop=True,
                      lfonames=True, corners=True, srcname=True)),
    ("S26", "n", dict(knobs=True, displays=True, knobs2=True, sets=True, pure=True, snap=True, trkpop=True,
                      lfonames=True, corners=True, srcname=True, polytext=True)),
]


def apply_operands(img, syms):
    """S21 on: the operand sites whose symbol the assembly has; S23 on: the replaced instructions."""
    for addr, want, sym in OPERAND_SITES:
        if sym in syms:
            off = addr - BASE
            assert img[off:off + 4].hex() == want, "0x%08x holds %s, expected %s" % (addr, img[off:off + 4].hex(), want)
            img[off:off + 4] = syms[sym].to_bytes(4, "big")
    for addr, want, sym, tail in REPL_SITES:
        if sym in syms:
            off, n = addr - BASE, len(want) // 2
            assert img[off:off + n].hex() == want, "0x%08x holds %s, expected %s" % (addr, img[off:off + n].hex(), want)
            new = bytes.fromhex("4eb9%08x" % syms[sym] + tail)
            assert len(new) == n, "0x%08x: the replacement is not %d B" % (addr, n)
            img[off:off + n] = new
    return img


def apply_later(img, syms):
    """S16 on: the renames, the icon, the range calls and the operand sites."""
    return apply_operands(apply_ranges(apply_icon(apply_renames(img, syms), syms), syms), syms)


def later_sites(syms):
    """S16 on: the patched stretches outside the hooks, for the emulator load file."""
    sites = [LABEL_PADS] + ICON_CODE + [(a - 2, a + 4) for a in RANGE_SITES]
    if "set_machine" in syms:
        sites += [(a - 2, a + 4) for a, _s in RANGE_SITES2]
    sites += [(a - 2, a + 4) for a, _w, s in OPERAND_SITES if s in syms]
    sites += [(a, a + len(w) // 2) for a, w, s, _t in REPL_SITES if s in syms]
    return sites


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    ld = os.path.abspath(sys.argv[1])
    stages = "--stages" in sys.argv
    stock = build.read(STOCK3)
    patch, runs = build.load_patch(PATCH)
    img = bytearray(stock)
    owner = set()
    for a, d, _f, _k in runs:
        img[a - BASE:a - BASE + len(d)] = d
        owner.update(range(a, a + len(d)))
    if build.sha256_bytes(bytes(img)) != patch["result"]["section3_sha256"]:
        sys.exit("patch.json does not reproduce its own section-3 hash")
    tmp = tempfile.mkdtemp(prefix="cfo-")
    try:
        secs, syms = assemble(ld, tmp)
        secs5, syms5 = assemble(ld, tmp, machine5=True)
        secs5n, syms5n = assemble(ld, tmp, machine5=True, names=True)
        secs5i, syms5i = assemble(ld, tmp, machine5=True, names=True, icon=True)
        secs5s, syms5s = assemble(ld, tmp, machine5=True, names=True, icon=True, slots=True)
        later = [(st, suf, assemble(ld, tmp, machine5=True, names=True, icon=True, slots=True, **fl))
                 for st, suf, fl in KNOB_STAGES]
        used = []
        groups = [secs, secs5, secs5n, secs5i, secs5s] + [ss for _st, _suf, (ss, _sy) in later]
        allsecs = [(k, v) for g in groups for k, v in g.items()]
        for name, (vma, data) in sorted(allsecs, key=lambda x: x[1][0]):
            end = vma + len(data)
            if name in HOOKS:
                a, disp = HOOKS[name]
                assert vma == a and len(data) == len(disp) // 2, "%s is not %d B at 0x%08x" % (name, len(disp) // 2, a)
                assert stock[vma - BASE:end - BASE] == bytes.fromhex(disp), "%s: the site is not stock" % name
                assert not any(x in owner for x in range(vma, end)), "another feature patches %s" % name
            elif name.startswith(".cfo_data") or name == ".cfo_icon" or (name == ".cfo_names" and RODATA2[0] <= vma < RODATA2[1]):
                rng = RODATA if name.startswith(".cfo_data") else RODATA2
                assert rng[0] <= vma and end <= rng[1], "%s outside the .rodata padding" % name
                assert stock[vma - BASE:end - BASE] == bytes(len(data)), ".rodata padding not zero in stock"
                assert not any(a in owner for a in range(vma, end)), "another feature's data is there"
            else:
                pad = [k for k, ext in PADS.items() if any(lo <= vma and end <= hi for lo, hi in ext)]
                assert pad, "%s at 0x%08x +%d lies in no candidate pad" % (name, vma, len(data))
                assert not any(a in owner for a in range(vma, end)), "%s: another feature's code is there" % name
                used.append(pad[0])
            print("%-12s 0x%08x +%d" % (name, vma, len(data)))
        out = os.path.join(ROOT, "work", "dt_1.54-cfo")
        os.makedirs(out, exist_ok=True)
        stem = os.path.splitext(os.path.basename(ld))[0]
        load = os.path.join(out, stem + ".load")
        im13 = apply_renames(bytearray(img), syms5n)
        pads13 = {".label_pads": (LABEL_PADS[0], bytes(im13[LABEL_PADS[0] - BASE:LABEL_PADS[1] - BASE]))}
        im14 = apply_icon(apply_renames(bytearray(img), syms5i), syms5i)
        pads14 = dict(pads13)
        for k, (lo, hi) in enumerate(ICON_CODE):
            pads14[".icon_code%d" % k] = (lo, bytes(im14[lo - BASE:hi - BASE]))
        im15 = apply_icon(apply_renames(bytearray(img), syms5s), syms5s)
        pads15 = dict(pads13)
        for k, (lo, hi) in enumerate(ICON_CODE):
            pads15[".icon_code%d" % k] = (lo, bytes(im15[lo - BASE:hi - BASE]))
        loads = [(load, (secs, syms)), (os.path.join(out, stem + "_m5.load"), (secs5, syms5)),
                 (os.path.join(out, stem + "_m5n.load"), (dict(secs5n, **pads13), syms5n)),
                 (os.path.join(out, stem + "_m5i.load"), (dict(secs5i, **pads14), syms5i)),
                 (os.path.join(out, stem + "_m5s.load"), (dict(secs5s, **pads15), syms5s))]
        for _st, suf, (ss, sy) in later:
            im = apply_later(bytearray(img), sy)
            pads = {}
            for k, (lo, hi) in enumerate(later_sites(sy)):
                pads[".site%d" % k] = (lo, bytes(im[lo - BASE:hi - BASE]))
            loads.append((os.path.join(out, stem + "_m5" + suf + ".load"), (dict(ss, **pads), sy)))
        for path, (ss, sy) in loads:
            with open(path, "w") as f:
                for name, (vma, data) in ss.items():
                    f.write("%08x %s\n" % (vma, data.hex()))
                for n, a in sy.items():
                    f.write("sym %s %08x\n" % (n, a))
        print("pads used:", sorted(set(used)), "; load file:", load)
        if not stages:
            print("no stage images built (--stages)")
            return
        img1 = bytearray(img)
        for p in sorted(set(used)):
            for lo, hi in PADS[p]:
                img1[lo - BASE:hi - BASE] = fill(lo, hi)
        built = {"S9": bytes(img1)}
        for name, inert in (("S10", True), ("S11", False)):
            s2, _ = assemble(ld, tmp, inert) if inert else (secs, syms)
            im = bytearray(img1)
            for _n, (vma, data) in s2.items():
                im[vma - BASE:vma - BASE + len(data)] = data
            built[name] = bytes(im)
        im = bytearray(img1)
        for _n, (vma, data) in secs5.items():
            im[vma - BASE:vma - BASE + len(data)] = data
        built["S12"] = bytes(apply_edits(im, syms5))
        im = bytearray(img1)
        for _n, (vma, data) in secs5n.items():
            im[vma - BASE:vma - BASE + len(data)] = data
        built["S13"] = bytes(apply_renames(apply_edits(im, syms5n), syms5n))
        im = bytearray(img1)
        for _n, (vma, data) in secs5i.items():
            im[vma - BASE:vma - BASE + len(data)] = data
        built["S14"] = bytes(apply_icon(apply_renames(apply_edits(im, syms5i), syms5i), syms5i))
        im = bytearray(img1)
        for _n, (vma, data) in secs5s.items():
            im[vma - BASE:vma - BASE + len(data)] = data
        built["S15"] = bytes(apply_icon(apply_renames(apply_edits(im, syms5s), syms5s), syms5s))
        for st, _suf, (ss, sy) in later:
            im = bytearray(img1)
            for _n, (vma, data) in ss.items():
                im[vma - BASE:vma - BASE + len(data)] = data
            built[st] = bytes(apply_later(apply_edits(im, sy), sy))
        dest = os.path.join(ROOT, "out", "1.54", "stages")
        os.makedirs(dest, exist_ok=True)
        syx, tool = build.DEFAULT_SYX, build.DEFAULT_TOOL
        for name in ["S9", "S10", "S11", "S12", "S13", "S14", "S15"] + [st for st, _suf, _a in later]:
            p3 = built[name]
            sel, i = [], 0
            while i < len(stock):
                if p3[i] != stock[i]:
                    j = i
                    while j < len(stock) and p3[j] != stock[j]:
                        j += 1
                    sel.append((BASE + i, p3[i:j], name, "code")); i = j
                else:
                    i += 1
            t2 = tempfile.mkdtemp(prefix=".stage-", dir=dest)
            try:
                st = build.extract(tool, syx, os.path.join(t2, "stock"))
                assert build.read(st[3]) == stock
                build.check_runs(sel, len(stock))
                pb = os.path.join(t2, "s3.bin")
                with open(pb, "wb") as f:
                    f.write(p3)
                packed = os.path.join(t2, "packed.syx")
                build.pack(tool, syx, pb, packed)
                build.check_report(tool, packed)
                build.roundtrip(tool, packed, p3, st, os.path.join(t2, "verify"))
                h = build.sha256_file(packed)
                final = os.path.join(dest, "dt_og_plus_plus_v0.1-%s_%s.syx" % (name, h[:8]))
                os.replace(packed, final)
            finally:
                shutil.rmtree(t2, ignore_errors=True)
            print("%s section 3 %s .syx %s -> %s" % (name, build.sha256_bytes(p3), h, final))
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    print("Nothing was sent to a device.")


if __name__ == "__main__":
    main()
