#!/usr/bin/env python3
"""POLY's voice pools on every kit change, for OS 1.54, on top of the build before it (v0.2.5, the
modulation matrix's S70): assemble polykit.s with polykit.ld, check both sites against the stock image
and that build; with --stages, build the stage image; with --write, merge S71 into
os/1.54/build/patch.json as the feature poly_kit_change.

    python3 os/1.54/src/poly_kit_change/make_polykit.py                    # check: is patch.json up to date?
    python3 os/1.54/src/poly_kit_change/make_polykit.py --stages           # also build the stage image
    python3 os/1.54/src/poly_kit_change/make_polykit.py --stages --write   # and rewrite patch.json

Needs m68k binutils (M68K_PREFIX, as make_cfo.py), the stock section 3 from ./scripts/extract.sh 1.54 and,
for --stages, the stock .syx and the firmware tool (as build.py).

The build before this change comes back from patch.json (build_without_pk: its features before
poly_kit_change, and the bytes of earlier features that a feature merged after it rewrites, LATER_SITES,
given back; the change itself rewrites no byte of an earlier feature) and must give BUILD_SECTION3. Once
patch.json holds a feature merged after it (cfoo_diet, from v0.2.6), S71 is checked against its recorded
hash and --write is refused: the last feature's generator (make_diet.py) rewrites patch.json. The stage,
written to out/1.54/stages/ (only with --stages):
  S71  the build + the kit pointer's setter (0x4007739a), which a pattern change made while stopped goes
       through, rebuilds POLY's pool map from the new kit, as the set-active-kit event already does: its
       last 6 B jump to pk_follow (16 B of POLY's pad span), which calls the build's map rebuild between
       the setter's last two steps
S71 on v0.2.5: patch.json holds the build before it, then poly_kit_change (S71 less that build)."""
import json, os, shutil, subprocess, sys, tempfile
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", "..", ".."))
sys.path.insert(0, os.path.join(ROOT, "os", "1.54", "build"))
sys.path.insert(0, os.path.join(ROOT, "os", "1.54", "src", "cfo_oscillator"))
sys.path.insert(0, os.path.join(ROOT, "os", "1.54", "src", "mod_matrix"))
sys.path.insert(0, os.path.join(ROOT, "os", "1.54", "src", "cfoo_diet"))
import build  # noqa: E402
import make_listing  # noqa: E402
import make_cfo as cfo  # noqa: E402
import make_mm as mm  # noqa: E402
import region  # noqa: E402
PFX = os.environ.get("M68K_PREFIX", "m68k-elf-")
BASE = 0x40000400
LD = os.path.join(HERE, "polykit.ld")
BUILD_SECTION3 = "fc33939bfae3fa586840ee85a5f75c36d8fec207fc14293958e80e84e08db15b"   # the build before it, S70
FID = "poly_kit_change"
TITLE = ("POLY machine: the voice pools follow every change of the engine's kit, so a pattern chosen while "
         "the sequencer is stopped plays its own pools from its first step")
LAST = "S71"
RECORDED = {"S71": "00817e747065bcddc17d62cb70c065e2509b9c8f9a80332ff90c7c04970f99ef"}
# The site: the kit pointer's setter at 0x4007739a, whose last two instructions (store the kit back as
# the argument, branch to the kit tail's reader) are replaced. No feature of the build changes the setter.
SETTER = (0x4007739a, "202f000441f9800019b423c0800019ac",
          "the kit pointer's setter: the argument, then the pointer 0x800019ac")
SITE = (".kit_set", 0x400773be, "2f40000460bc", "the setter's last two instructions")
SETTER_END = 0x400773c4
# The pad: 16 B of POLY's pad span 0x400bed3a..0x400bee6a (vetted in notes/landing_pads.md), stock bytes
# no feature of the build changes, after the jump that ends the knob-follow loop's pad.
SPAN = (0x400bed3a, 0x400bee6a)
PAD = (".pk_pad", 0x400bedf2, 16)
BEFORE_PAD = (0x400bedec, "4ef9400771f0", "the jump that ends the code before the pad")
# What polykit.s calls in the build, by its bytes.
USES = [(0x400bf18c, "2079800019ac41e8009e43f9439d1050", "poly_engine's map rebuild, the source half (MAP_SRC)"),
        (0x400bf1ca, "4e75", "its rts"),
        (0x40077380, "206f000443e8091a", "the kit tail's reader (KIT_TAIL)")]
# Bytes of the features before this one that a feature merged after it rewrites, as those features hold
# them: the lighter render (cfoo_diet, from v0.2.6) rewrites the CFO oscillator's render, portamento's note
# operand in it included; patch.json then lists those bytes under cfoo_diet. build_without_pk gives them back.
LATER_SITES = region.give_back()


def run(*cmd):
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode:
        sys.exit("%s\n%s%s" % (" ".join(cmd), r.stdout, r.stderr))
    return r.stdout


def assemble(tmp):
    o, e = os.path.join(tmp, "polykit.o"), os.path.join(tmp, "polykit.elf")
    run(PFX + "as", "-mcpu=5475", "-o", o, os.path.join(HERE, "polykit.s"))
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


def build_without_pk(stock, patch):
    """The build before this change, from patch.json: its features before poly_kit_change, and LATER_SITES
    given back. It must hash to BUILD_SECTION3. Returns the image and who owns each byte it changes
    ({address: (feature, kind)})."""
    ids = [f["id"] for f in patch["features"]]
    img, owner = bytearray(stock), {}
    for f in patch["features"][:ids.index(FID) if FID in ids else len(ids)]:
        for r in f["runs"]:
            a, d = int(r["addr"], 16), bytes.fromhex(r["bytes"])
            img[a - BASE:a - BASE + len(d)] = d
            for k in range(len(d)):
                owner[a + k] = (f["id"], r.get("kind", "code"))
    for a, want, fid in LATER_SITES:
        d = bytes.fromhex(want)
        img[a - BASE:a - BASE + len(d)] = d
        for k in range(len(d)):
            if d[k] != stock[a - BASE + k]:
                owner[a + k] = (fid, "code")
    h = build.sha256_bytes(bytes(img))
    if h != BUILD_SECTION3:
        sys.exit("the build before poly_kit_change does not come back from patch.json (section 3 %s)" % h)
    return img, owner


def merged_features(stock, patch, img, owner, last):
    """patch.json's features before poly_kit_change, and poly_kit_change = S71 on the build before it (img,
    whose changed bytes owner attributes): each byte S71 changes, under the last feature that wrote it.
    Runs are maximal per feature and kind, as make_wavpic.py writes them."""
    def kind(a):
        return "code" if any(lo <= a < hi for lo, hi in make_listing.CODE_WINDOWS) else "data"
    attr = {}
    for i in range(len(stock)):
        if last[i] != stock[i]:
            a = BASE + i
            attr[a] = (FID, kind(a)) if last[i] != img[i] else owner[a]
    runs, cur = {}, None
    for a in sorted(attr):
        fid, k = attr[a]
        if cur and cur["fid"] == fid and cur["kind"] == k and cur["end"] == a:
            cur["b"].append(last[a - BASE]); cur["end"] = a + 1
        else:
            cur = {"fid": fid, "kind": k, "start": a, "end": a + 1, "b": [last[a - BASE]]}
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
        sys.exit("--write needs --stages (patch.json carries the .syx hash of the %s build)" % LAST)
    stock = build.read(cfo.STOCK3)
    with open(cfo.PATCH) as f:
        patch = json.load(f)
    ids = [f["id"] for f in patch["features"]]
    later = ids[ids.index(FID) + 1:] if FID in ids else []
    if write and later:
        sys.exit("patch.json holds features merged after %s (%s); the last one's generator rewrites it"
                 % (FID, ", ".join(later)))
    assert build.sha256_bytes(stock) == patch["stock"]["section3_sha256"], "work/dt_1.54 is not the stock section 3"
    img, owner = build_without_pk(stock, patch)
    a, want, what = SETTER
    assert stock[a - BASE:a - BASE + len(want) // 2].hex() == want, "0x%08x is not %s" % (a, what)
    for a, want, what in USES + [BEFORE_PAD, SETTER]:
        assert img[a - BASE:a - BASE + len(want) // 2].hex() == want, "0x%08x is not %s in the build" % (a, what)
    tmp = tempfile.mkdtemp(prefix="polykit-")
    try:
        secs, syms = assemble(tmp)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    assert set(secs) == {SITE[0], PAD[0]}, "sections %s" % sorted(secs)
    im = bytearray(img)
    print("%s:" % LAST)
    name, a, want, what = SITE
    vma, data = secs[name]
    n = len(want) // 2
    assert vma == a and len(data) == n, "%s is not %d B at 0x%08x" % (name, n, a)
    assert a + n == SETTER_END, "%s does not end the setter" % name
    assert stock[a - BASE:a - BASE + n].hex() == want, "%s: 0x%08x is not stock" % (name, a)
    assert img[a - BASE:a - BASE + n].hex() == want, "%s: the build changes 0x%08x" % (name, a)
    assert data == bytes.fromhex("4ef9") + syms["pk_follow"].to_bytes(4, "big"), "%s is not jmp pk_follow" % name
    print("  %-9s 0x%08x +%d  %s -> jmp 0x%08x" % (name, a, n, what, syms["pk_follow"]))
    im[a - BASE:a - BASE + n] = data
    name, a, size = PAD
    vma, data = secs[name]
    assert vma == a and len(data) <= size, "%s is not at most %d B at 0x%08x" % (name, size, a)
    assert SPAN[0] <= a and a + size <= SPAN[1], "%s is outside POLY's pad span" % name
    assert img[a - BASE:a - BASE + size] == stock[a - BASE:a - BASE + size], "the build changes the pad's bytes"
    assert not any(x in owner for x in range(a, a + size)), "a feature owns bytes of the pad"
    print("  %-9s 0x%08x +%d  POLY's pad span, %d B left" % (name, a, len(data), size - len(data)))
    im[a - BASE:a - BASE + len(data)] = data
    # Nothing in the build's code may branch into the replaced bytes after their first instruction, or name
    # any byte of the pad from outside it (the pad's stock leftovers branch within themselves).
    for lo, hi, what in [(SITE[1], SETTER_END, "the setter's replaced end"), (PAD[1] - 1, PAD[1] + PAD[2], "the pad")]:
        hits = [h for h in mm.branches_into(img, lo, hi) if not PAD[1] <= int(h[0], 16) < PAD[1] + PAD[2]]
        assert not hits, "branches into %s: %s" % (what, hits)
    built = {LAST: bytes(im)}
    hl = build.sha256_bytes(built[LAST])
    if LAST in RECORDED:
        assert hl == RECORDED[LAST], "%s does not give its recorded section 3 (%s)" % (LAST, hl)
    print("  section 3 %s%s" % (hl, ", as recorded" if LAST in RECORDED else ""))
    print("checks: the setter and the pad as above, the build's bytes there stock; MAP_SRC, its rts and "
          "KIT_TAIL as the build holds them; no branch into the setter's replaced end or the pad")
    feats = merged_features(stock, patch, img, owner, built[LAST])
    if later:
        print("%s section 3 %s; patch.json carries %s after it" % (LAST, hl, ", ".join(later)))
    else:
        same = feats == patch["features"] and hl == patch["result"]["section3_sha256"]
        print("%s section 3 %s; patch.json %s" % (LAST, hl, "is up to date" if same else
                                                  "differs (%s)" % patch["result"]["section3_sha256"]))
    if not stages:
        print("no stage image built (--stages)")
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
            final = os.path.join(dest, "dt_og_plus_plus_v0.2.5-%s_%s.syx" % (name, h[:8]))
            os.replace(packed, final)
        finally:
            shutil.rmtree(t2, ignore_errors=True)
        res[name] = (h, size)
        print("%s section 3 %s .syx %s -> %s" % (name, build.sha256_bytes(p3), h, final))
    if write:
        patch["features"] = feats
        patch["result"] = dict(patch["result"], section3_sha256=hl,
                               syx_sha256_reference=res[LAST][0], syx_size_reference=res[LAST][1])
        with open(cfo.PATCH, "w") as f:
            json.dump(patch, f, indent=1)
            f.write("\n")
        print("wrote", cfo.PATCH)
    print("Nothing was sent to a device.")


if __name__ == "__main__":
    main()
