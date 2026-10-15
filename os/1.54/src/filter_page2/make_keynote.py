#!/usr/bin/env python3
"""FILTER page 2's KEY on the trig's note for OS 1.54, on top of the build before it (v0.2.4, the wave
pictures' S55): assemble filt.s with S52's options and with KEYNOTE, check that they differ only in the
operand of filt_key's lea and that the build holds S52's bytes, and write the emulator's load file; with
--stages, build the stage image; with --write, merge S56 into os/1.54/build/patch.json as the feature
filter_page2_key_note.

    python3 os/1.54/src/filter_page2/make_keynote.py                    # check: is patch.json up to date?
    python3 os/1.54/src/filter_page2/make_keynote.py --stages           # also build the stage image
    python3 os/1.54/src/filter_page2/make_keynote.py --stages --write   # and rewrite patch.json

Needs what make_filt.py needs (m68k binutils, M68K_PREFIX; the stock section 3 from
./scripts/extract.sh 1.54; for --stages the stock .syx and the firmware tool).

The build before this change comes back from patch.json (build_without_keynote: its features before
filter_page2_key_note, and, once that is merged, the 4 bytes it rewrites given back to filter_page2, and
the bytes of earlier features that a feature merged after it rewrites, LATER_SITES, given back) and must
give BUILD_SECTION3. Once patch.json holds a feature merged after it (mod_matrix, S69), S56 is checked
against its recorded hash and --write is refused: the last feature's generator (make_mm.py) rewrites
patch.json. The stage, written to out/1.54/stages/ (only with --stages):
  S56  the build + KEY reads the trig's note sum (0x80001f28: written at the NoteOn and by a note change
       without a trig) instead of portamento's glided one (0x439d1180): the 4-byte operand of the lea at
       0x40178f2a
S56 is the build from v0.2.4: patch.json holds the build before it, then filter_page2_key_note (S56 less
that build). Each byte is listed once, under the last feature that wrote it, so the 4 bytes are listed
under filter_page2_key_note."""
import json, os, shutil, sys, tempfile
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", "..", ".."))
sys.path.insert(0, os.path.join(ROOT, "os", "1.54", "build"))
sys.path.insert(0, os.path.join(ROOT, "os", "1.54", "src", "cfo_oscillator"))
sys.path.insert(0, HERE)
import build  # noqa: E402
import make_listing  # noqa: E402
import make_cfo as cfo  # noqa: E402
import make_filt as filt  # noqa: E402
BASE = 0x40000400
BUILD_SECTION3 = "957bdc6fa80cca3860615cf3baa11310db666da1eec3770fa1d2fbc70615ee45"   # the build before it, v0.2.4
FID = "filter_page2_key_note"
TITLE = ("FILTER page 2: KEY follows the trig's note, as the NoteOn sets it and a trigless NOTE lock changes "
         "it, not portamento's glided note: the cutoff moves at once while the pitch glides")
# The operand of filt_key's `lea PSTATE,%a0` (at 0x40178f2a), filter_page2's in the build.
SITE = (0x40178f2c, "439d1180", "filter_page2")
NEW = "80001f28"
LEA = (0x40178f2a, "41f9")
S56_SHA = "ce7a07b48ad053dce41e424ec86c1cb34e1cecbbb091a6320a31c8ed79f8011d"   # S56's section 3, v0.2.4
# Bytes of the features before this one that a feature merged after it rewrites, as those features hold
# them: the modulation matrix (mod_matrix, from v0.2.5) points the knob cell's name call, which MIDI
# Loopback had pointed at its own, at its own, and puts its code over the wave pictures' pad fill from
# 0x401045c0 (make_cfo.fill over the whole pad); patch.json then lists those bytes under mod_matrix.
# build_without_keynote gives them back.
LATER_SITES = [(0x40030dad, "01562c", "midi_loopback"),
               (0x401045c0, cfo.fill(0x401044b6, 0x40104e7e)[0x401045c0 - 0x401044b6:].hex(), "wave_pictures")]


def build_without_keynote(stock, patch):
    """The build before this change, from patch.json: its features before filter_page2_key_note and, once
    that is merged, the operand given back to filter_page2. It must hash to BUILD_SECTION3. Returns the
    image and who owns each byte it changes ({address: (feature, kind)})."""
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
    for a, want, fid in LATER_SITES:
        d = bytes.fromhex(want)
        img[a - BASE:a - BASE + len(d)] = d
        for k in range(len(d)):
            if d[k] != stock[a - BASE + k]:
                owner[a + k] = (fid, "code")
    h = build.sha256_bytes(bytes(img))
    if h != BUILD_SECTION3:
        sys.exit("the build before KEY's change does not come back from patch.json (section 3 %s)" % h)
    return img, owner


def merged_features(stock, patch, img, owner, s56):
    """patch.json's features before filter_page2_key_note, and filter_page2_key_note = S56 on the build
    before it (img, whose changed bytes owner attributes): each byte S56 changes, under the last feature
    that wrote it. Runs are maximal per feature and kind, as make_wavpic.py writes them."""
    def kind(a):
        return "code" if any(lo <= a < hi for lo, hi in make_listing.CODE_WINDOWS) else "data"
    attr = {}
    for i in range(len(stock)):
        if s56[i] != stock[i]:
            a = BASE + i
            attr[a] = (FID, kind(a)) if s56[i] != img[i] else owner[a]
    runs, cur = {}, None
    for a in sorted(attr):
        fid, k = attr[a]
        if cur and cur["fid"] == fid and cur["kind"] == k and cur["end"] == a:
            cur["b"].append(s56[a - BASE]); cur["end"] = a + 1
        else:
            cur = {"fid": fid, "kind": k, "start": a, "end": a + 1, "b": [s56[a - BASE]]}
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
        sys.exit("--write needs --stages (patch.json carries the .syx hash of the S56 build)")
    stock = build.read(cfo.STOCK3)
    with open(cfo.PATCH) as f:
        patch = json.load(f)
    ids = [f["id"] for f in patch["features"]]
    later = ids[ids.index(FID) + 1:] if FID in ids else []
    if write and later:
        sys.exit("patch.json holds features merged after %s (%s); the last one's generator rewrites it"
                 % (FID, ", ".join(later)))
    assert build.sha256_bytes(stock) == patch["stock"]["section3_sha256"], "work/dt_1.54 is not the stock section 3"
    img, owner = build_without_keynote(stock, patch)
    a, want, fid = SITE
    n = len(want) // 2
    assert img[a - BASE:a - BASE + n].hex() == want, "0x%08x is not portamento's state address" % a
    assert all(owner.get(x, ("",))[0] == fid for x in range(a, a + n)), "0x%08x is not %s's" % (a, fid)
    assert img[LEA[0] - BASE:LEA[0] - BASE + 2].hex() == LEA[1], "0x%08x is not a lea abs.l,%%a0" % LEA[0]
    print("site       0x%08x..0x%08x  %s's: the operand of the lea at 0x%08x" % (a, a + n, fid, LEA[0]))
    tmp = tempfile.mkdtemp(prefix="keynote-")
    try:
        s52 = filt.assemble(tmp, filt.VARIANTS["S52"])
        s56 = filt.assemble(tmp, filt.VARIANTS["S52"] + ["KEYNOTE"])
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    # S52 as the build holds it, and S56 S52 with the one operand changed
    for name, (vma, data) in s52[0].items():
        got = bytes(img[vma - BASE:vma - BASE + len(data)])
        assert got == data, "the build does not hold S52's %s" % name
    assert set(s56[0]) == set(s52[0]), "S56's sections are not S52's"
    for name, (vma, data) in s56[0].items():
        old = s52[0][name]
        assert vma == old[0] and len(data) == len(old[1]), "%s moved" % name
        diff = [vma + i for i in range(len(data)) if data[i] != old[1][i]]
        if name == ".filt_key":
            assert diff == list(range(a, a + n)), "S56's .filt_key differs from S52's at %s" % [hex(x) for x in diff]
            assert data[a - vma:a - vma + n].hex() == NEW, "S56's operand is %s" % data[a - vma:a - vma + n].hex()
        else:
            assert not diff, "S56 changes %s" % name
    for k, v in s52[1].items():
        assert s56[1].get(k) == v, "symbol %s moved" % k
    assert "key_note" in s56[1] and "key_note" not in s52[1]
    print("S56        filt.s with KEYNOTE: S52 but the operand at 0x%08x, %s -> %s" % (a, want, NEW))
    im = bytearray(img)
    im[a - BASE:a - BASE + n] = bytes.fromhex(NEW)
    built = {"S56": bytes(im)}
    # the load file for EmuFilter: every byte that differs from stock, and S56's symbols
    out = os.path.join(ROOT, "work", "dt_1.54-filt")
    os.makedirs(out, exist_ok=True)
    with open(os.path.join(out, "S56.load"), "w") as f:
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
        for nm, ad in s56[1].items():
            f.write("sym %s %08x\n" % (nm, ad))
    print("load file: work/dt_1.54-filt/S56.load")
    feats = merged_features(stock, patch, img, owner, built["S56"])
    h56 = build.sha256_bytes(built["S56"])
    if later:
        print("S56 section 3 %s; %s; patch.json carries %s after it" % (
            h56, "as recorded (v0.2.4)" if h56 == S56_SHA else "NOT as recorded (%s)" % S56_SHA, ", ".join(later)))
    else:
        same = feats == patch["features"] and h56 == patch["result"]["section3_sha256"]
        print("S56 section 3 %s; patch.json %s" % (h56, "is up to date" if same else
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
            final = os.path.join(dest, "dt_og_plus_plus_v0.2.4-%s_%s.syx" % (name, h[:8]))
            os.replace(packed, final)
        finally:
            shutil.rmtree(t2, ignore_errors=True)
        res[name] = (h, size)
        print("%s section 3 %s .syx %s -> %s" % (name, build.sha256_bytes(p3), h, final))
    if write:
        patch["features"] = feats
        patch["result"] = dict(patch["result"], section3_sha256=h56,
                               syx_sha256_reference=res["S56"][0], syx_size_reference=res["S56"][1])
        with open(cfo.PATCH, "w") as f:
            json.dump(patch, f, indent=1)
            f.write("\n")
        print("wrote", cfo.PATCH)
    print("Nothing was sent to a device.")


if __name__ == "__main__":
    main()
