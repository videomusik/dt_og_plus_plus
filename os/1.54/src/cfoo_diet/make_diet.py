#!/usr/bin/env python3
"""The CFO oscillator's render, lighter, for OS 1.54, on top of the build before it (v0.2.5 with
poly_kit_change's S71): assemble diet.s with diet.ld, check its place against the stock image and that
build, and write the emulator harness's load files; with --stages, build the stage images.

    python3 os/1.54/src/cfoo_diet/make_diet.py            # check, and write the load files
    python3 os/1.54/src/cfoo_diet/make_diet.py --stages   # also build the stage images

Needs m68k binutils (M68K_PREFIX, as make_cfo.py), the stock section 3 from ./scripts/extract.sh 1.54,
the CFO oscillator's load file for S27 (work/dt_1.54-cfo/lz4_stream_m5o.load, from make_cfo.py
lz4_stream.ld) and, for --stages, the stock .syx and the firmware tool (as build.py). It does not change
patch.json.

The build before it comes back from patch.json (v0.2.5) with S71's change on it (make_polykit.py) and must
give S71's recorded section 3. The render's code there, 0x400f77da..0x400f7b18, is the CFO oscillator's
(S27) with portamento's note operand; the CFO oscillator's segfrac in it, 0x400f7a1e..0x400f7a5c, stays,
and the new code goes around it (.diet_a before, .diet_b after), the rest of each part filled with the
pad's fill. The stages, written to out/1.54/stages/ (only with --stages):
  S72  S71 + the render in fewer instructions (a multiply for the pitch's divide, one routine for OSC2
       and OSC3, a register for the oscillator loop's shift), and a CFOO track whose amp envelope is closed
       renders nothing (option IDLE)
  S73  S72 + OSC2 or OSC3 is not rendered when nothing hears it (option LEAN)
The load files, for EmuCfoOscillator (work/dt_1.54-cfo/diet_<name>.load): S27's load with the render
replaced by diet.s assembled with HNOTE (the trig's note, as S27 and the harness read it) and the stage's
options; diet_base.load has no option, the code the options are added to."""
import os, re, shutil, subprocess, sys, tempfile
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", "..", ".."))
sys.path.insert(0, os.path.join(ROOT, "os", "1.54", "build"))
sys.path.insert(0, os.path.join(ROOT, "os", "1.54", "src", "cfo_oscillator"))
sys.path.insert(0, os.path.join(ROOT, "os", "1.54", "src", "mod_matrix"))
sys.path.insert(0, os.path.join(ROOT, "os", "1.54", "src", "poly_kit_change"))
import json  # noqa: E402
import build  # noqa: E402
import make_cfo as cfo  # noqa: E402
import make_mm as mm  # noqa: E402
import make_polykit as pk  # noqa: E402
PFX = os.environ.get("M68K_PREFIX", "m68k-elf-")
BASE = 0x40000400
LD = os.path.join(HERE, "diet.ld")
BASE_STAGE = "S71"
# The region: the CFO oscillator's render, and in it its segfrac, which stays.
REGION = (0x400f77da, 0x400f7b18)
SEGFRAC = (0x400f7a1e, 0x400f7a5c)
PARTS = {".diet_a": (REGION[0], SEGFRAC[0]), ".diet_b": (SEGFRAC[1], REGION[1])}
FOREIGN = [(0x400f7840, "439d1180", "portamento")]      # portamento's note operand, in the render's track
# The build's bytes around the region and what the render calls, by their first bytes.
FACTS = [(0x40077fc8, "4eb9400f77da", "the audio ISR's call of cfo_pad"),
         (0x400f7a1e, "72000c800000002a", "the CFO oscillator's segfrac"),
         (0x400f7a5a, "4e75", "segfrac's rts"),
         (0x400f7b18, "7205b280", "the CFO oscillator's layout_hi, after the render"),
         (0x40072478, "4fefffb84280", "the level stage (FILL)"),
         (0x40074c60, "2f02", "the level (LEVEL)"),
         (0x400716ce, "45f94199ed64", "the amp envelope's lea of its state (AMPST)")]
# References from outside the region that may name it: the hook's call of cfo_pad and the wave
# pictures' calls of segfrac.
ALLOWED = {REGION[0], SEGFRAC[0]}
STAGES = {"S72": ["IDLE"], "S73": ["IDLE", "LEAN"]}
RECORDED = {"S72": "00d57d4c320a9ecd3faac43cdd3dc7c8b0145c6673ce85f6685663ab0a42a37e",
            "S73": "459d7595d382ef260de894c871e205cb6fecf71069334590ffa3aa523263ec69"}
S27_LOAD = os.path.join(ROOT, "work", "dt_1.54-cfo", "lz4_stream_m5o.load")
# The symbols diet.s defines that S27's load also names (the harness reads cfo_pad, pitch, detune24, wave
# and gains): the load's are replaced by the new ones; S27's render symbols that diet.s has not, removed.
S27_RENDER_SYMS = ["cfo_pad", "track", "detune24", "clamp127", "pitch", "wave", "gains", "osc32", "osc1_mix"]


def run(*cmd):
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode:
        sys.exit("%s\n%s%s" % (" ".join(cmd), r.stdout, r.stderr))
    return r.stdout


def assemble(tmp, opts):
    o, e = os.path.join(tmp, "diet.o"), os.path.join(tmp, "diet.elf")
    defs = [x for f in opts for x in ("--defsym", f + "=1")]
    run(PFX + "as", "-mcpu=5475", *defs, "-o", o, os.path.join(HERE, "diet.s"))
    run(PFX + "ld", "-T", LD, "-o", e, o)
    secs = {}
    for line in run(PFX + "objdump", "-h", e).splitlines():
        p = line.split()
        if len(p) >= 4 and p[1].startswith(".") and int(p[2], 16):
            b = os.path.join(tmp, p[1] + ".bin")
            run(PFX + "objcopy", "-O", "binary", "-j", p[1], e, b)
            secs[p[1]] = (int(p[3], 16), build.read(b))
    syms = {}
    for line in run(PFX + "nm", e).splitlines():
        a, ty, n = line.split()
        if ty not in "Uw" and not n.startswith("."):
            syms[n] = int(a, 16)
    return secs, syms


def place(img, secs):
    """The region with the parts written and the rest of each part filled."""
    im = bytearray(img)
    for name, (lo, hi) in PARTS.items():
        vma, data = secs[name]
        assert vma == lo and lo + len(data) <= hi, "%s is %d B at 0x%08x, room for %d" % (name, len(data), vma, hi - lo)
        im[lo - BASE:hi - BASE] = data + cfo.fill(lo + len(data), hi)
    return im


def base_build(stock, patch):
    """v0.2.5 from patch.json with S71's change: the build before this one. Returns the image and who owns
    each byte v0.2.5 changes."""
    img, owner = pk.build_without_pk(stock, patch)
    tmp = tempfile.mkdtemp(prefix="diet-")
    try:
        secs, _syms = pk.assemble(tmp)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    im = bytearray(img)
    for _n, (vma, data) in secs.items():
        im[vma - BASE:vma - BASE + len(data)] = data
    h = build.sha256_bytes(bytes(im))
    if h != pk.RECORDED[BASE_STAGE]:
        sys.exit("the build before the lighter render is not %s (section 3 %s)" % (BASE_STAGE, h))
    return im, owner


def words_into(img, lo, hi):
    """Every 32-bit word of the image, at any even offset outside lo..hi, whose value lies in lo..hi."""
    hits = []
    for o in range(0, len(img) - 3, 2):
        a = BASE + o
        if lo - 3 <= a < hi:
            continue
        v = int.from_bytes(img[o:o + 4], "big")
        if lo <= v < hi:
            hits.append((a, v))
    return hits


def write_load(path, img_load, syms_new, opts):
    """S27's load with the render replaced: the sections as S27's load has them, their bytes in the region
    from the new image, and the symbols: S27's render symbols out, diet.s's in."""
    secs, syms = [], {}
    with open(S27_LOAD) as f:
        for line in f:
            p = line.split()
            if len(p) == 3 and p[0] == "sym":
                syms[p[1]] = int(p[2], 16)
            elif len(p) == 2:
                secs.append([int(p[0], 16), bytearray(bytes.fromhex(p[1]))])
    covered = 0
    for s in secs:
        for k in range(len(s[1])):
            a = s[0] + k
            if REGION[0] <= a < REGION[1]:
                s[1][k] = img_load[a]
                covered += 1
    assert covered == REGION[1] - REGION[0], "S27's load does not hold the whole region (%d B)" % covered
    for n in S27_RENDER_SYMS:
        syms.pop(n, None)
    syms.update(syms_new)
    with open(path, "w") as f:
        for a, d in secs:
            f.write("%08x %s\n" % (a, bytes(d).hex()))
        for n, a in syms.items():
            f.write("sym %s %08x\n" % (n, a))
    print("load file %s (%s)" % (os.path.relpath(path, ROOT), ", ".join(opts)))


def main():
    stages = "--stages" in sys.argv
    stock = build.read(cfo.STOCK3)
    with open(cfo.PATCH) as f:
        patch = json.load(f)
    assert build.sha256_bytes(stock) == patch["stock"]["section3_sha256"], "work/dt_1.54 is not the stock section 3"
    img, owner = base_build(stock, patch)
    for a, want, what in FACTS:
        assert img[a - BASE:a - BASE + len(want) // 2].hex() == want, "0x%08x is not %s" % (a, what)
    for a in range(*REGION):
        o = owner.get(a, (None,))[0]
        if any(lo <= a < lo + len(w) // 2 for lo, w, _f in FOREIGN):
            assert o == "portamento", "0x%08x is not portamento's" % a
        else:
            assert o in ("cfo_oscillator", None), "0x%08x is %s's" % (a, o)
    for lo, want, fid in FOREIGN:
        assert img[lo - BASE:lo - BASE + len(want) // 2].hex() == want, "0x%08x is not %s's operand" % (lo, fid)
    hits = [h for h in mm.branches_into(img, REGION[0] - 1, REGION[1])
            if not REGION[0] <= int(h[0], 16) < REGION[1]
            and not all(int(x, 16) in ALLOWED for x in re.findall(r"0x([0-9a-f]{8})", h[1])
                        if REGION[0] <= int(x, 16) < REGION[1])]
    assert not hits, "code outside the region names it: %s" % hits
    words = [w for w in words_into(img, *REGION) if w[1] not in ALLOWED]
    print("checks: the build's region is the CFO oscillator's but portamento's operand; segfrac, the hook, "
          "layout_hi and the calls as the build holds them; nothing outside branches into the region but "
          "to cfo_pad and segfrac")
    print("words outside the region with a value in it, other than cfo_pad and segfrac: %d %s" % (
        len(words), " ".join("0x%08x=0x%08x" % w for w in words[:12])))
    tmp = tempfile.mkdtemp(prefix="diet-")
    try:
        built, loads = {}, {}
        for name, opts in [("base", [])] + list(STAGES.items()):
            secs, syms = assemble(tmp, opts)
            assert set(secs) == set(PARTS), "sections %s" % sorted(secs)
            sizes = ", ".join("%s %d of %d B" % (n, len(secs[n][1]), PARTS[n][1] - PARTS[n][0]) for n in sorted(PARTS))
            im = place(img, secs)
            assert im[SEGFRAC[0] - BASE:SEGFRAC[1] - BASE] == img[SEGFRAC[0] - BASE:SEGFRAC[1] - BASE]
            assert syms["cfo_pad"] == REGION[0], "cfo_pad is not at the region's start"
            if name in STAGES:
                built[name] = bytes(im)
                h = build.sha256_bytes(built[name])
                if name in RECORDED:
                    assert h == RECORDED[name], "%s does not give its recorded section 3 (%s)" % (name, h)
                print("%-5s (%s): %s; section 3 %s%s" % (name, ", ".join(opts), sizes, h,
                                                          ", as recorded" if name in RECORDED else ""))
            else:
                print("%-5s (no option): %s" % (name, sizes))
            hsecs, hsyms = assemble(tmp, opts + ["HNOTE"])
            loads[name] = (place(img, hsecs), {k: v for k, v in hsyms.items()}, opts + ["HNOTE"])
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    out = os.path.join(ROOT, "work", "dt_1.54-cfo")
    if not os.path.exists(S27_LOAD):
        sys.exit("no %s: run make_cfo.py lz4_stream.ld first" % os.path.relpath(S27_LOAD, ROOT))
    for name, (im, syms, opts) in loads.items():
        imd = {BASE + i: im[i] for i in range(REGION[0] - BASE, REGION[1] - BASE)}
        write_load(os.path.join(out, "diet_%s.load" % name), imd, syms, opts)
    if not stages:
        print("no stage images built (--stages)")
        return
    dest = os.path.join(ROOT, "out", "1.54", "stages")
    os.makedirs(dest, exist_ok=True)
    syx, tool = build.DEFAULT_SYX, build.DEFAULT_TOOL
    for name, p3 in built.items():
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
            final = os.path.join(dest, "dt_og_plus_plus_v0.2.5-%s_%s.syx" % (name, h[:8]))
            os.replace(packed, final)
        finally:
            shutil.rmtree(t2, ignore_errors=True)
        print("%s section 3 %s .syx %s -> %s" % (name, build.sha256_bytes(p3), h, final))
    print("Nothing was sent to a device.")


if __name__ == "__main__":
    main()
