#!/usr/bin/env python3
"""The modulation matrix for OS 1.54, on top of the build before it (v0.2.4, S56): assemble mm.s with mm.ld,
check every site against the stock image and that build; with --stages, build the stage images. The
feature is not merged into patch.json yet (no --write).

    python3 os/1.54/src/mod_matrix/make_mm.py                 # check
    python3 os/1.54/src/mod_matrix/make_mm.py --stages        # also build the stage images
    python3 os/1.54/src/mod_matrix/make_mm.py --stages S60    # only those named

Needs m68k binutils (M68K_PREFIX, as make_cfo.py), the stock section 3 from ./scripts/extract.sh 1.54
and, for --stages, the stock .syx and the firmware tool (as build.py).

The build before this feature is patch.json as it stands, and must give BUILD_SECTION3. Stages, written to
out/1.54/stages/ (only with --stages):
  S57  the build + the matrix page: [LFO] + [UP]/[DOWN] on an audio track shows MOD WHEEL or BREATH CTRL
       with eight empty knobs, [UP]/[DOWN] step between the two, a tap of [LFO] (or another page key, or
       [TRK]) leaves it; nothing stored. Meant to keep knobs A..H from the LFO under it, but the unit's
       knobs reach the page through its encoder interface, which S57 does not change
  S58  S57 + the encoder interface's entry (0x401af938) goes to the matrix too (option ENC4)
  S59  S58 + [TRK] no longer leaves the matrix: it stays through track changes, showing on audio tracks
       only (on a MIDI track the LFO page is its own) (option TRKSTAY)
  S60  S59 + the sources sound: each audio track's MOD WHEEL (CC 1/33) and BREATH CTRL (CC 2/34), as stock
       keeps them, change the engine's values every tick before the LFOs, with one fixed assignment for
       the test: MOD WHEEL closes FILTER FREQ over its range, BREATH CTRL raises TUNE up to 12 semitones; a
       POLY track follows its Source's (option PASS)
  S61  S60 + a MIDI track ends the matrix, so the next audio track shows its LFO page (option MIDIEXIT)
  S62  S61 + the slots: per audio track and source, four slots of a destination (knobs A..D, LFO1's DEST
       picker) and a depth (E..H, LFO1's DEP scale), in RAM, edited on the page through stand-ins of the
       track's parameter set; S60's fixed assignment gone (option EDIT)
  S63  S62 + [UP]/[DOWN] are the matrix's only while nothing is open over the screens (a menu, a list, a
       held key's view), which then has them as stock (option FRONT)
  S64  S63 + the slots per kit (a pattern's kit), saved in the stored kit's unused tail with the project
       and the kit's own save, taken back on load (option KITS)
  S63d S63 + its title shows the top view's vtable (option DIAG), a test image: on the unit the pattern
       selector stays on top after use, so S63 and S64 refused the matrix's [UP]/[DOWN]
  S65  S62 + [UP]/[DOWN] are the matrix's in KeyboardView's key handler, which gets them only when no
       view above took them (option VIEWKEYS, with FRONT's marker and stand-in fixes)
  S66  S65 + the slots per kit (KITS)
  S67  S66 + the source by hand: the level fader shows the shown source's value, LEVEL/DATA sets it as a
       CC would (LEVTEST)
  S68  S67 + the level fader is named after the source, MW or BC, while the matrix shows (LEVNAME)
  S69  S68 + the fader follows a CC as it comes: the page's tick redraws it when the source changed
       (LEVTICK)"""
import json, os, re, shutil, subprocess, sys, tempfile
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", "..", ".."))
sys.path.insert(0, os.path.join(ROOT, "os", "1.54", "build"))
sys.path.insert(0, os.path.join(ROOT, "os", "1.54", "src", "cfo_oscillator"))
import build  # noqa: E402
import make_cfo as cfo  # noqa: E402
PFX = os.environ.get("M68K_PREFIX", "m68k-elf-")
BASE = 0x40000400
LD = os.path.join(HERE, "mm.ld")
BUILD_SECTION3 = "ce7a07b48ad053dce41e424ec86c1cb34e1cecbbb091a6320a31c8ed79f8011d"   # the build before it, S56
# The pad: the wave pictures' pad FUN_401044b6 (vetted in notes/landing_pads.md), from the end of the wave
# pictures' code; in the build it holds the pad's fill, which patch.json lists under wave_pictures.
PAD = (0x401044b6, 0x40104e7e)
PAD_FROM = 0x401045c0
PAD_OWNER = "wave_pictures"
# The sites: (section, address, the stock bytes, what they are). No feature of the build changes them.
SITES = [(".hook_key", 0x4000b770, "4eb9400084fe", "the UI task's call of the key dispatcher"),
         (".vt_draw", 0x401af868, "4009743c", "LfoPageView's vtable +0x10, its drawing"),
         (".vt_enc", 0x401af89c, "40032a78", "LfoPageView's vtable +0x44, its encoder handler"),
         (".vt_title", 0x401af8a4, "40030930", "LfoPageView's vtable +0x4c, its title"),
         (".vt_enc4", 0x401af938, "40032d8c", "LfoPageView's encoder interface (the base at +4), its handler"),
         (".hook_pass", 0x40077eac, "4eb9400ed766", "the audio tick's call of the LFO tick"),
         (".vt_knob", 0x401af8f4, "400308fe", "LfoPageView's vtable +0x9c, a knob's parameter"),
         (".vt_set", 0x401af8fc, "400308d0", "LfoPageView's vtable +0xa4, a parameter's set"),
         (".hook_kwrite", 0x4007a76e, "356b091c08f2", "the kit writer's last copy of a word"),
         (".hook_kload", 0x4007a4ce, "202f0004206f", "the kit loader's gate"),
         (".vt_kbkey", 0x40182458, "40029876", "KeyboardView's vtable +8, its key handler"),
         (".vt_pget", 0x401af8ac, "40030a64", "LfoPageView's vtable +0x54, its get"),
         (".vt_tick", 0x401af884, "40030ee0", "LfoPageView's vtable +0x2c, its tick")]
# The sites a feature of the build already changed: (section, address, the stock bytes, the build's, the
# feature that owns every byte the build changes there, what they are). The matrix goes in front of that
# feature's code and on to it.
BUILD_SITES = [(".call_name", 0x40030daa, "4eb94000fe8a", "4eb94001562c", "midi_loopback",
                "the knob cell's call of a parameter's short name")]
# What S63 and S64 rely on, by the bytes that show it: HelpBubbleView's vtable (its type before it); the
# live kit's index at its +0x44, the project data at 0x409bbbfc and its 128 kits at +0xf6341c (RAMKITS
# 0x4191f018), the gate's whole body (which mm_kload replaces) and its loader's first bytes.
KIT_FACTS = [(0x401af56c, "401af538", "HelpBubbleView's type, before its vtable 0x401af570 (HELP_VT)"),
             (0x4018244c, "401823fc", "KeyboardView's type, before its vtable 0x40182450"),
             (0x40029876, "4fefff7448d7", "KeyboardView's key handler (KB_KEY)"),
             (0x40030a64, "4feffff4700a", "a page's get (PAGE_GET)"),
             (0x40093ab0, "4e56ffe048d7", "the header's popup (POPUP)"),
             (0x4013c124, "206f000420280044", "the live kit's index getter: +0x44"),
             (0x4005f1e6, "4879409bbbfc", "the project load's project data, 0x409bbbfc"),
             (0x4007f144, "068400f6341c", "the project load's kits at the data + 0xf6341c"),
             (0x4007a4ce, "202f0004206f00086" "70e4a88670a720ab2906604" "6000fea24200" "4e75",
              "the kit loader's gate, which mm_kload does again"),
             (0x4007a386, "4fefffe448d7", "the kit loader (LOADER)")]
GATE = (0x4007a4ce, 0x4007a4ea)
# The stages: the options mm.s is assembled with, and the section 3 of each stage built before.
VARIANTS = {"S57": [], "S58": ["ENC4"], "S59": ["ENC4", "TRKSTAY"], "S60": ["ENC4", "TRKSTAY", "PASS"],
            "S61": ["ENC4", "TRKSTAY", "PASS", "MIDIEXIT"],
            "S62": ["ENC4", "TRKSTAY", "PASS", "MIDIEXIT", "EDIT"],
            "S63": ["ENC4", "TRKSTAY", "PASS", "MIDIEXIT", "EDIT", "FRONT"],
            "S63d": ["ENC4", "TRKSTAY", "PASS", "MIDIEXIT", "EDIT", "FRONT", "DIAG"],
            "S64": ["ENC4", "TRKSTAY", "PASS", "MIDIEXIT", "EDIT", "FRONT", "KITS"],
            "S65": ["ENC4", "TRKSTAY", "PASS", "MIDIEXIT", "EDIT", "FRONT", "VIEWKEYS"],
            "S66": ["ENC4", "TRKSTAY", "PASS", "MIDIEXIT", "EDIT", "FRONT", "VIEWKEYS", "KITS"],
            "S67": ["ENC4", "TRKSTAY", "PASS", "MIDIEXIT", "EDIT", "FRONT", "VIEWKEYS", "KITS", "LEVTEST"],
            "S68": ["ENC4", "TRKSTAY", "PASS", "MIDIEXIT", "EDIT", "FRONT", "VIEWKEYS", "KITS", "LEVTEST",
                    "LEVNAME"],
            "S69": ["ENC4", "TRKSTAY", "PASS", "MIDIEXIT", "EDIT", "FRONT", "VIEWKEYS", "KITS", "LEVTEST",
                    "LEVNAME", "LEVTICK"]}
RECORDED = {"S57": "c1ce248991a2a02ea0fa2c23b0e452f25fe38d02f1a28d2292215b99b0bc36a6",
            "S58": "76b80599449ec8fb3340e098a757d20162d4dce27cf31c59739df4c1f98f6213",
            "S59": "4d7fab73636d165ab73c4ac75f767d7f44a1b83b3c62fcd9577b02499bbcf1e3",
            "S60": "caccbf608843cc18ff6294f6f93dc03255da9800bd1edcd93d7e4c24abe7fa08",
            "S61": "e4f34d2828ad73ea07ecea5d1cfc57c61b2c0dae23184c70f78076a3adecde57",
            "S62": "a1209b6d94962bbc0b73c0a24d6cb7bb46a0cbb1983c01f75d07579fc61e701f",
            "S65": "470b9d0a69585cddc2880f1b052e5f0567f65d37d12581040ec3b5194b654439",
            "S66": "268ea996c2128055bdaf906ecb046b1700b71ff90565dd7bc6993fa15b9427e1",
            "S67": "f084565e6343babef370893f39faf374b4d73da56725256f6e542e317e79d81b",
            "S68": "b390d7ab046f61c719bd24bc9cdae29b7bf52a56d3a2743f954d78f52494f634",
            "S69": "060aca3af421497dc7a6ab2d9ef8e275ed6b757c3473308a7ae94d4eefd86475"}
# What mm.s calls in the build, by its first bytes.
USES = [(0x400084fe, "4feffff048d7", "the key dispatcher (DISPATCH)"),
        (0x4009743c, "4fefffbc48d7", "LfoPageView's drawing (LFO_DRAW)"),
        (0x40032a78, "4e56ffc048d7", "its encoder handler (LFO_ENC)"),
        (0x40030930, "598f2f0b2f0a", "its title (LFO_TITLE)"),
        (0x400c9a3a, "206f00044a280017", "a view's redraw request (REDRAW)"),
        (0x4017af20, "4e5600002f02", "std::string from a C string (STRING)"),
        (0x400c2b88, "4fefffd048d7", "the Bitmap drawer (DRAW)"),
        (0x40138882, "4e56fff02f0a", "the project (PROJECT)"),
        (0x40014d86, "202f00040680", "PROJ_SEQ"),
        (0x4001d24e, "2f0a246f0008", "the current track (CUR_TRACK)"),
        (0x400ed766, "4fefffc048d7", "the LFO tick (LFO_TICK)"),
        (0x400308fe, "2f027008206f", "a page's knob -> parameter (KNOB_ID)"),
        (0x400308d0, "206f00042f68", "a page's parameter -> set (SET_OF)"),
        (0x400c0686, "2f027409202f", "the encoder state's turning (ENC_TOUCH)"),
        (0x400c0898, "2f2f00082f2f", "the encoder state's mark (ENC_MARK)"),
        (0x400d399c, "2f02202f0008", "a key held (KEY_HELD)"),
        (0x40093470, "206f00041028", "the header's held-trig view (TRIG_VIEW)"),
        (0x40093bb6, "2f0a2f02246f", "a held trig's locks (TRIG_LOCK)"),
        (0x401470de, "202f00045880", "a weak pointer expired (EXPIRED)"),
        (0x4001562c, "222f00080c810000008c", "midi_loopback's short name of a parameter (NAME_NEXT)"),
        (0x400f7b5c, "4ef94000fe8a", "cfo_oscillator's, after it, going on to stock's"),
        (0x40030ee0, "2f0a246f0008", "LfoPageView's tick (PAGE_TICK)")]
# The state's RAM: after portamento's state (notes/memory_map.md), from S62 with the slots and the stand-ins;
# from S64 the kits' slots, above 0x439d32fb (a value in a stock table of numbers, the one word of the build
# between the build's own state and 0x439e0000). No long word of the build names either range.
STATE = [(0x439d11a8, 0x439d13b0), (0x439d3400, 0x439db400)]
# SoundParameterSet's vtable (and the two words before it), which the stand-ins' vtable mm_vt copies but for
# its get (+0x28), notify (+0x60) and store (+0x74).
SET_VT = 0x4017ee58
OWN_VT = {0x28: "mm_get", 0x60: "mm_nop", 0x74: "mm_store"}


def run(*cmd):
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode:
        sys.exit("%s\n%s%s" % (" ".join(cmd), r.stdout, r.stderr))
    return r.stdout


def assemble(tmp, opts):
    o, e = os.path.join(tmp, "mm.o"), os.path.join(tmp, "mm.elf")
    defs = [x for f in opts for x in ("--defsym", f + "=1")]
    run(PFX + "as", "-mcpu=5475", *defs, "-o", o, os.path.join(HERE, "mm.s"))
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
    """Every instruction of the build's code whose operand names an address strictly inside lo..hi."""
    tmp = tempfile.mkdtemp(prefix="mm-")
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


def the_build(stock, patch):
    """patch.json applied to the stock section 3, and who owns each byte it changes."""
    img, owner = bytearray(stock), {}
    for f in patch["features"]:
        for r in f["runs"]:
            a, d = int(r["addr"], 16), bytes.fromhex(r["bytes"])
            img[a - BASE:a - BASE + len(d)] = d
            for k in range(len(d)):
                owner[a + k] = f["id"]
    h = build.sha256_bytes(bytes(img))
    if h != BUILD_SECTION3:
        sys.exit("patch.json does not give the build this stage is made on (section 3 %s)" % h)
    return img, owner


def main():
    stages = "--stages" in sys.argv
    only = sys.argv[sys.argv.index("--stages") + 1:] if stages else []
    for s in only:
        assert s in VARIANTS, "no stage %s" % s
    stock = build.read(cfo.STOCK3)
    with open(cfo.PATCH) as f:
        patch = json.load(f)
    assert build.sha256_bytes(stock) == patch["stock"]["section3_sha256"], "work/dt_1.54 is not the stock section 3"
    img, owner = the_build(stock, patch)
    for a, want, what in USES + KIT_FACTS:
        n = len(want) // 2
        assert img[a - BASE:a - BASE + n].hex() == want, "0x%08x is not %s" % (a, what)
    fill = cfo.fill(*PAD)
    built, pads = {}, {}
    for stage, opts in VARIANTS.items():
        tmp = tempfile.mkdtemp(prefix="mm-")
        try:
            secs, syms = assemble(tmp, opts)
        finally:
            shutil.rmtree(tmp, ignore_errors=True)
        if "EDIT" in opts:
            pvma, pdata = secs[".mm_pad"]
            o = syms["mm_vt"] - pvma
            assert pdata[o - 8:o] == img[SET_VT - 8 - BASE:SET_VT - BASE], "mm_vt's two words before are not SoundParameterSet's"
            assert img[SET_VT + 120 - BASE:SET_VT + 124 - BASE] == bytes(4), "SoundParameterSet's vtable is not 30 entries"
            for k in range(30):
                got = int.from_bytes(pdata[o + 4 * k:o + 4 * k + 4], "big")
                want = syms[OWN_VT[4 * k]] if 4 * k in OWN_VT else \
                    int.from_bytes(img[SET_VT + 4 * k - BASE:SET_VT + 4 * k + 4 - BASE], "big")
                assert got == want, "mm_vt +0x%02x is 0x%08x, not 0x%08x" % (4 * k, got, want)
        im = bytearray(img)
        print("%s (%s):" % (stage, ", ".join(opts) or "no option"))
        for name, (vma, data) in sorted(secs.items(), key=lambda x: x[1][0]):
            end = vma + len(data)
            site = [s for s in SITES if s[0] == name]
            bsite = [s for s in BUILD_SITES if s[0] == name]
            if site:
                _n, a, want, what = site[0]
                assert vma == a and len(data) == len(want) // 2, "%s is not %d B at 0x%08x" % (name, len(want) // 2, a)
                assert stock[a - BASE:end - BASE].hex() == want, "%s: 0x%08x is not stock" % (name, a)
                assert img[a - BASE:end - BASE].hex() == want, "%s: the build changes 0x%08x" % (name, a)
                print("  %-10s 0x%08x +%d  %s" % (name, vma, len(data), what))
            elif bsite:
                _n, a, swant, bwant, feat, what = bsite[0]
                assert vma == a and len(data) == len(bwant) // 2, "%s is not %d B at 0x%08x" % (name, len(bwant) // 2, a)
                assert stock[a - BASE:end - BASE].hex() == swant, "%s: 0x%08x is not stock's call" % (name, a)
                assert img[a - BASE:end - BASE].hex() == bwant, "%s: 0x%08x is not the build's call" % (name, a)
                changed = [x for x in range(a, end) if img[x - BASE] != stock[x - BASE]]
                assert changed and all(owner.get(x) == feat for x in changed), "%s: the build's bytes there are not %s's" % (name, feat)
                print("  %-10s 0x%08x +%d  %s (the build's, %s's)" % (name, vma, len(data), what, feat))
            elif name == ".mm_pad":
                assert vma == PAD_FROM and end <= PAD[1], ".mm_pad 0x%08x..0x%08x is not in the pad's free part" % (vma, end)
                want = fill[vma - PAD[0]:end - PAD[0]]
                assert bytes(img[vma - BASE:end - BASE]) == want, "the build does not hold the pad's fill there"
                assert all(owner.get(x) == PAD_OWNER or img[x - BASE] == stock[x - BASE] for x in range(vma, end)), \
                    "the fill there is not %s's" % PAD_OWNER
                print("  %-10s 0x%08x +%d  the pad, %d B left after it" % (name, vma, len(data), PAD[1] - end))
                pads[stage] = data
            else:
                sys.exit("section %s has no place" % name)
            im[vma - BASE:end - BASE] = data
        built[stage] = bytes(im)
        h = build.sha256_bytes(built[stage])
        if stage in RECORDED:
            assert h == RECORDED[stage], "%s does not give its recorded section 3 (%s)" % (stage, h)
        print("  section 3 %s%s" % (h, ", as recorded" if stage in RECORDED else ""))
    assert pads["S58"][:len(pads["S57"])] == pads["S57"], "S58's code does not start with S57's"
    assert len(pads["S59"]) > len(pads["S58"]), "S59 adds no code"
    assert pads["S60"][:len(pads["S59"])] == pads["S59"], "S60's code does not start with S59's"
    assert len(pads["S61"]) > len(pads["S60"]), "S61 adds no code"
    assert len(pads["S62"]) > len(pads["S61"]), "S62 adds no code"
    assert len(pads["S64"]) > len(pads["S63"]), "S64 adds no code"
    assert len(pads["S66"]) > len(pads["S65"]), "S66 adds no code"
    assert len(pads["S67"]) > len(pads["S66"]), "S67 adds no code"
    assert len(pads["S68"]) > len(pads["S67"]), "S68 adds no code"
    assert len(pads["S69"]) > len(pads["S68"]), "S69 adds no code"
    for a, what in [(s[1], s[3]) for s in (SITES[0], SITES[5], SITES[8])] + [(s[1], s[5]) for s in BUILD_SITES]:
        hits = branches_into(img, a, a + 6)
        assert not hits, "branches into %s: %s" % (what, hits)
    hits = [h for h in branches_into(img, GATE[0], GATE[1]) if not GATE[0] <= int(h[0], 16) < GATE[1]]
    assert not hits, "branches into the kit loader's gate from outside it: %s" % hits
    named = [BASE + o for o in range(0, len(img) - 3, 2)
             if any(lo <= int.from_bytes(img[o:o + 4], "big") < hi for lo, hi in STATE)]
    assert not named, "the build names the state's RAM at %s" % ["0x%08x" % x for x in named]
    print("checks: the sites and the pad as above; S58's code is S57's and more, S60's S59's; no branch into "
          "the hooks' calls or the kit loader's gate; nothing names %s; S62's mm_vt is the build's "
          "SoundParameterSet vtable but for its own get, notify and store"
          % ", ".join("0x%08x..0x%08x" % r for r in STATE))
    if not stages:
        print("no stage image built (--stages)")
        return
    dest = os.path.join(ROOT, "out", "1.54", "stages")
    os.makedirs(dest, exist_ok=True)
    syx, tool = build.DEFAULT_SYX, build.DEFAULT_TOOL
    for name, p3 in built.items():
        if only and name not in only:
            continue
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
            final = os.path.join(dest, "dt_og_plus_plus_v0.2.4-%s_%s.syx" % (name, h[:8]))
            os.replace(packed, final)
        finally:
            shutil.rmtree(t2, ignore_errors=True)
        print("%s section 3 %s .syx %s -> %s" % (name, build.sha256_bytes(p3), h, final))
    print("Nothing was sent to a device.")


if __name__ == "__main__":
    main()
