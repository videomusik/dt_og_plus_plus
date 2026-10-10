#!/usr/bin/env python3
"""CFOO's wave pictures for OS 1.54, on top of the build before them (v0.2.2, FILTER page 2's S52):
assemble wavpic.s with wavpic.ld, check every site against the stock image and that build, and write the
emulator's load files; with --stages, build the stage images; with --write, merge the last stage, S55,
into os/1.54/build/patch.json as the feature wave_pictures.

    python3 os/1.54/src/wave_pictures/make_wavpic.py                    # check: is patch.json up to date?
    python3 os/1.54/src/wave_pictures/make_wavpic.py --stages           # also build the stage images
    python3 os/1.54/src/wave_pictures/make_wavpic.py --stages --write   # and rewrite patch.json

Needs m68k binutils (M68K_PREFIX, as make_cfo.py), the stock section 3 from ./scripts/extract.sh 1.54
and, for --stages, the stock .syx and the firmware tool (as build.py).

The build before this feature comes back from patch.json (build_without_wavpic: its features before
wave_pictures, and the CFO oscillator's bytes this feature rewrites given back) and must give
BUILD_SECTION3. Stages, written to out/1.54/stages/ (only with --stages):
  S53  the build + FUN_401044b6 filled with 'clrl %d0 ; rts': the fill test of the new pad
  S54  S53 + the CFO oscillator's picture hook jumps to the new pad at its knob's record, and the pad only
       replays the two instructions the jump replaced
  S55  S54 + WAV1, WAV2 and WAV3 (knobs A, C, D) draw the wave they play, 17 x 17
S55 is the build from v0.2.3: patch.json holds the build before it, then wave_pictures (S55 less that
build). Each byte is listed once, under the last feature that wrote it, so the CFO oscillator's 10 B at
the hook site are listed under wave_pictures."""
import json, os, re, shutil, subprocess, sys, tempfile
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", "..", ".."))
sys.path.insert(0, os.path.join(ROOT, "os", "1.54", "build"))
sys.path.insert(0, os.path.join(ROOT, "os", "1.54", "src", "cfo_oscillator"))
import build  # noqa: E402
import make_listing  # noqa: E402
import make_cfo as cfo  # noqa: E402
import make_waves  # noqa: E402
PFX = os.environ.get("M68K_PREFIX", "m68k-elf-")
BASE = 0x40000400
LD = os.path.join(HERE, "wavpic.ld")
BUILD_SECTION3 = "3b88fa95268a05cba3181c8765b3379a46237b00def0820d1c8c4da499506f37"   # the build before it, v0.2.2
FID = "wave_pictures"
TITLE = ("CFOO's wave pictures: WAV1, WAV2 and WAV3 on CFOO's SRC page show the wave each oscillator plays at "
         "the knob's value, 17 x 17, drawn from the oscillator's own waveform tables and blend")
# The new pad (vetted in notes/landing_pads.md): FUN_401044b6, a function of xxHash's 64-bit family,
# which the firmware links whole with LZ4 and never calls; extent from the stock rts.
PADS = {".wav_pad": (0x401044b6, 0x40104e7e)}
# The hook site: the CFO oscillator's cfo_pic, where it takes the knob's record (movel %sp@(8),%d0 ;
# subil #108,%d0), 10 B of cfo_oscillator's code in the build; the jump and two nops replace it.
# build_without_wavpic gives these bytes back once patch.json holds wave_pictures.
SITE = (0x400f7c5c, "202f00080480 0000006c".replace(" ", ""), "cfo_oscillator")
# What wavpic.s takes from the build: cfo_pic's continuation, the CFO oscillator's segfrac and tables.
USES = [(0x400f7c66, "e588", "cfo_pic's lsll #2,%d0 (BACK)"),
        (0x400f7a1e, "72000c800000002a", "the CFO oscillator's segfrac (SEGFRAC)"),
        (0x40252724, bytes(v & 0xff for _n, tb in make_waves.tables() for v in tb).hex(),
         "the CFO oscillator's four tables, as make_waves.py makes them (WAVES)")]
VARIANTS = {"S54": [], "S55": ["PIC"]}


def run(*cmd):
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode:
        sys.exit("%s\n%s%s" % (" ".join(cmd), r.stdout, r.stderr))
    return r.stdout


def assemble(tmp, opts):
    src = os.path.join(HERE, "wavpic.s")
    o, e = os.path.join(tmp, "w.o"), os.path.join(tmp, "w.elf")
    defs = [x for f in opts for x in ("--defsym", f + "=1")]
    run(PFX + "as", "-mcpu=5475", *defs, "-o", o, src)
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
        if ty not in "Uw":
            syms[n] = int(a, 16)
    return secs, syms


def branches_into(img, lo, hi):
    """Every instruction of the build's code windows whose operand names an address strictly inside
    lo..hi (a branch into the middle of the replaced instructions)."""
    tmp = tempfile.mkdtemp(prefix="wp-")
    try:
        b = os.path.join(tmp, "s3.bin")
        with open(b, "wb") as f:
            f.write(img)
        lst = run(PFX + "objdump", "-D", "-b", "binary", "-m", "m68k:cfv4e", "--adjust-vma=0x%x" % BASE,
                  "--start-address=0x400004b2", "--stop-address=0x4017cc64", b)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    hits = []
    for line in lst.splitlines():
        m = re.match(r"^\s*([0-9a-f]+):\t[0-9a-f ]+\t(.*)$", line)
        if m:
            for x in re.findall(r"0x([0-9a-f]{8})", m.group(2)):
                if lo < int(x, 16) < hi:
                    hits.append((m.group(1), m.group(2)))
    return hits


def build_without_wavpic(stock, patch):
    """The build before the wave pictures, from patch.json: its features before wave_pictures and, once
    that is merged, the hook site's bytes given back to cfo_oscillator. It must hash to BUILD_SECTION3.
    Returns the image and who owns each byte it changes ({address: (feature, kind)})."""
    ids = [f["id"] for f in patch["features"]]
    img, owner = bytearray(stock), {}
    for f in patch["features"][:ids.index(FID) if FID in ids else len(ids)]:
        for r in f["runs"]:
            a, d = int(r["addr"], 16), bytes.fromhex(r["bytes"])
            img[a - BASE:a - BASE + len(d)] = d
            for k in range(len(d)):
                owner[a + k] = (f["id"], r.get("kind", "code"))
    if FID in ids:
        a, want, fid = SITE
        d = bytes.fromhex(want)
        img[a - BASE:a - BASE + len(d)] = d
        for k in range(len(d)):
            if d[k] != stock[a - BASE + k]:
                owner[a + k] = (fid, "code")
    h = build.sha256_bytes(bytes(img))
    if h != BUILD_SECTION3:
        sys.exit("the build before the wave pictures does not come back from patch.json (section 3 %s)" % h)
    return img, owner


def merged_features(stock, patch, img, owner, s55):
    """patch.json's features before wave_pictures, and wave_pictures = S55 on the build before it (img,
    whose changed bytes owner attributes): each byte S55 changes, under the last feature that wrote it.
    Runs are maximal per feature and kind, as make_filt.py writes them; outside the code windows, data."""
    def kind(a):
        return "code" if any(lo <= a < hi for lo, hi in make_listing.CODE_WINDOWS) else "data"
    attr = {}
    for i in range(len(stock)):
        if s55[i] != stock[i]:
            a = BASE + i
            attr[a] = (FID, kind(a)) if s55[i] != img[i] else owner[a]
    runs, cur = {}, None
    for a in sorted(attr):
        fid, k = attr[a]
        if cur and cur["fid"] == fid and cur["kind"] == k and cur["end"] == a:
            cur["b"].append(s55[a - BASE]); cur["end"] = a + 1
        else:
            cur = {"fid": fid, "kind": k, "start": a, "end": a + 1, "b": [s55[a - BASE]]}
            runs.setdefault(fid, []).append(cur)
    ids = [f["id"] for f in patch["features"]]
    earlier = patch["features"][:ids.index(FID) if FID in ids else len(ids)]
    features = [(f["id"], f["title"]) for f in earlier] + [(FID, TITLE)]
    return [{"id": i, "title": t,
             "runs": [{"addr": "0x%08x" % x["start"], "bytes": bytes(x["b"]).hex(), "kind": x["kind"]}
                      for x in runs.get(i, [])]} for i, t in features]


def main():
    stages, write = "--stages" in sys.argv, "--write" in sys.argv
    if write and not stages:
        sys.exit("--write needs --stages (patch.json carries the .syx hash of the S55 build)")
    stock = build.read(cfo.STOCK3)
    with open(cfo.PATCH) as f:
        patch = json.load(f)
    assert build.sha256_bytes(stock) == patch["stock"]["section3_sha256"], "work/dt_1.54 is not the stock section 3"
    img, owner = build_without_wavpic(stock, patch)
    a, want, fid = SITE
    n = len(want) // 2
    assert img[a - BASE:a - BASE + n].hex() == want, "the hook site is not the build's cfo_pic"
    assert all(owner.get(x, ("",))[0] == fid for x in range(a, a + n) if img[x - BASE] != stock[x - BASE])
    print("%-10s 0x%08x..0x%08x  %s's" % ("site", a, a + n, fid))
    for addr, want, what in USES:
        assert img[addr - BASE:addr - BASE + len(want) // 2].hex() == want, "0x%08x is not %s" % (addr, what)
        print("uses       0x%08x  %s" % (addr, what))
    bad = branches_into(bytes(img), a, a + n)
    assert not bad, "branches into the hook site: %s" % bad
    print("no instruction branches into the hook site")
    for name, (lo, hi) in PADS.items():
        assert not any(a in owner for a in range(lo, hi)), "the build patches %s" % name
        assert stock[hi - BASE - 2:hi - BASE].hex() == "4e75", "%s does not end in the stock rts" % name
        assert stock[lo - BASE - 2:lo - BASE].hex() == "4e75", "%s does not follow an rts" % name
        print("%-10s 0x%08x..0x%08x  %d B, stock and untouched by the build" % (name, lo, hi, hi - lo))
    s53 = bytearray(img)
    for lo, hi in PADS.values():
        s53[lo - BASE:hi - BASE] = cfo.fill(lo, hi)
    built = {"S53": bytes(s53)}
    tmp = tempfile.mkdtemp(prefix="wavpic-")
    try:
        asm = {v: assemble(tmp, o) for v, o in VARIANTS.items()}
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    prev = s53
    for v, (secs, syms) in asm.items():
        assert set(secs) == set(PADS), secs.keys()
        im = bytearray(prev)
        for name, (vma, data) in secs.items():
            lo, hi = PADS[name]
            assert vma == lo and vma + len(data) <= hi, "%s does not fit its pad" % name
            im[lo - BASE:hi - BASE] = cfo.fill(lo, hi)
            im[vma - BASE:vma - BASE + len(data)] = data
            print("%-10s %s %d of %d B" % (name, v, len(data), hi - lo))
        assert syms["wav_sel"] == PADS[".wav_pad"][0]
        a, want, _f = SITE
        im[a - BASE:a - BASE + len(want) // 2] = bytes.fromhex("4ef9%08x4e714e71" % syms["wav_sel"])
        built[v] = bytes(im)
        prev = im
    out = os.path.join(ROOT, "work", "dt_1.54-wavpic")
    os.makedirs(out, exist_ok=True)
    for name, im in built.items():
        sy = asm[name][1] if name in asm else {}
        with open(os.path.join(out, name + ".load"), "w") as f:
            i = 0
            while i < len(stock):
                if im[i] != stock[i]:
                    j = i
                    while j < len(stock) and im[j] != stock[j]:
                        j += 1
                    f.write("%08x %s\n" % (BASE + i, im[i:j].hex()))
                    i = j
                else:
                    i += 1
            for n, a in sy.items():
                f.write("sym %s %08x\n" % (n, a))
    print("load files:", ", ".join(os.path.join("work", "dt_1.54-wavpic", n + ".load") for n in built))
    feats = merged_features(stock, patch, img, owner, built["S55"])
    s55 = build.sha256_bytes(built["S55"])
    same = feats == patch["features"] and s55 == patch["result"]["section3_sha256"]
    print("S55 section 3 %s; patch.json %s" % (s55, "is up to date" if same else
                                               "differs (%s)" % patch["result"]["section3_sha256"]))
    if not stages:
        print("no stage images built (--stages)")
        return
    dest = os.path.join(ROOT, "out", "1.54", "stages")
    os.makedirs(dest, exist_ok=True)
    syx, tool = build.DEFAULT_SYX, build.DEFAULT_TOOL
    res = {}
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
            h, size = build.sha256_file(packed), os.path.getsize(packed)
            final = os.path.join(dest, "dt_og_plus_plus_v0.2.2-%s_%s.syx" % (name, h[:8]))
            os.replace(packed, final)
        finally:
            shutil.rmtree(t2, ignore_errors=True)
        res[name] = (h, size)
        print("%s section 3 %s .syx %s -> %s" % (name, build.sha256_bytes(p3), h, final))
    if write:
        patch["features"] = feats
        patch["result"] = dict(patch["result"], section3_sha256=s55,
                               syx_sha256_reference=res["S55"][0], syx_size_reference=res["S55"][1])
        with open(cfo.PATCH, "w") as f:
            json.dump(patch, f, indent=1)
            f.write("\n")
        print("wrote", cfo.PATCH)
    print("Nothing was sent to a device.")


if __name__ == "__main__":
    main()
