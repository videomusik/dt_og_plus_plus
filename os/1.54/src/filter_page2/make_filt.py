#!/usr/bin/env python3
"""FILTER page 2 (VED, KEY) for OS 1.54, on top of the build before it (v0.2.1, portamento's S36):
assemble filt.s with filt.ld, check every site against the stock image and that build, and write the
emulator's load files; with --stages, build the stage images; with --write, merge the last stage, S52,
into os/1.54/build/patch.json as the feature filter_page2.

    python3 os/1.54/src/filter_page2/make_filt.py                    # check: is patch.json up to date?
    python3 os/1.54/src/filter_page2/make_filt.py --stages           # also build the stage images
    python3 os/1.54/src/filter_page2/make_filt.py --stages --write   # and rewrite patch.json

Needs what make_port.py needs (m68k binutils, M68K_PREFIX; the stock section 3 from
./scripts/extract.sh 1.54; for --stages the stock .syx and the firmware tool).

The build before this feature comes back from patch.json (build_without_filt: its features before
filter_page2, and the bytes this feature rewrites given back) and must give BUILD_SECTION3. Stages,
written to out/1.54/stages/ (only with --stages):
  S37  the build + the four new pads filled with 'clrl %d0 ; rts' (their fill test)
  S38  S37 + the filter stage's hook, its pad only jumping on to the envelope level getter
  S39  S38 + VED and KEY on FILTER page 2's knobs C and G: descriptor rows 1 and 2 (unused "Error" rows)
       as sound parameters in the free value slots 48 and 49, their names, their display objects as
       ENV's, and the page's layout; the hook still inert, so nothing sounds different yet
  S40  S39 + VED's and KEY's pictures: FILTER page 2's own draw routine FUN_40037564 asks the cell for a
       picture only for DEL, SRR and ROUT, so VED and KEY showed their names alone; any other id now
       gets its picture, and BASE and WDTH (drawn as one box after the knobs) still none
  S41  S40 + VED and KEY act
  S42  S41 + the sound reader's hook (portamento's PORT and LEG) and the two stored-index lookups' test
       for PORT and LEG moved into this build's pads, doing what they did
  S43  S42 + VED and KEY saved with the sound and their p-locks with the pattern
  S44  S43 + KEY's effect doubled, over the same range
  S45  S44 + a hook in the start-up display build, only replaying the clrl it replaced
  S46  S45 + [FUNC] + knob steps VED and KEY to the next of -63, 0 and 63: the hook copies GAIN's [FUNC]
       callable into their display objects
  S47  S46 + VED 0..100 %, default 0: how much of the envelope depth the velocity decides (at 100 % a
       note's depth goes from none at velocity 0 to the whole at 127); shown as Trig Probability is,
       [FUNC] + knob stepping it to 0, 50 and 100 %
  S48  S47 + VED's pivot at velocity 100: the depth x (1 - VED / 100 x (100 - velocity) / 127), so a
       velocity above 100 deepens it
  S49  S48 + VED's knob keeps its name under the picture when turned (its display object's flag 4, as
       ROUT's), the picture showing the value
  S50  S49 + KEY's effect doubled again: about 6.25 % keytracking a step, 100 % at 16
  S51  S50 + KEY's text callable copied at start-up from a constant copy of ENV's text object in the
       .rodata padding: KEY still shows -63..63 (the first run of that path)
  S52  S51 + that object's invoker becomes key_txt: KEY shows its keytracking in percent
S52 is the build v0.2.2: patch.json holds the build before it, then filter_page2 (S52 less that
build). Once a later feature is merged (the wave pictures, v0.2.3), S52 is checked against its recorded
hash and --write is refused; LATER_SITES gives back the bytes that feature rewrites. Each byte is listed once, under the last feature that wrote it, so portamento's three sites and
the writer's loop count that this feature rewrites, and the two leftovers of Chain Recording's pad fill
that hold KEY's text, are listed under filter_page2."""
import json, os, shutil, subprocess, sys, tempfile
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", "..", ".."))
sys.path.insert(0, os.path.join(ROOT, "os", "1.54", "build"))
sys.path.insert(0, os.path.join(ROOT, "os", "1.54", "src", "cfo_oscillator"))
sys.path.insert(0, os.path.join(ROOT, "os", "1.54", "src", "portamento"))
import build  # noqa: E402
import make_listing  # noqa: E402
import make_cfo as cfo  # noqa: E402
import make_port as port  # noqa: E402
PFX = os.environ.get("M68K_PREFIX", "m68k-elf-")
BASE = 0x40000400
BUILD_SECTION3 = "d6fac1a35cf565c37fc43ae51bd0c76f1ee7e23c0668ff1d245bb2839e51509c"   # the build before it, v0.2.1
FID = "filter_page2"
TITLE = ("FILTER page 2: VED and KEY on every audio track's second FILTER page, saved with the sound and "
         "lockable per trig; VED (0-100 %) sets how much the velocity decides the filter envelope's depth "
         "around velocity 100, KEY (-394..394 % in 6.25 % steps) moves the cutoff with the note")
# The build's bytes that S52 rewrites, as the build before it holds them, and their feature: portamento's
# reader call and lookup tests and the writer's loop count, and Chain Recording's pad fill where KEY's
# text goes. build_without_filt gives them back once patch.json holds filter_page2.
REWRITES = [(0x4007a2ac, "400e6d52", "portamento"), (0x4007974c, "722fb2806402", "portamento"),
            (0x40079786, "722fb2806402", "portamento"), (0x4007a614, "7260", "portamento"),
            (0x40124b24, "42804e7542804e7542804e754e75", "pad_fill"),
            (0x401282ce, "4e7542804e7542804e7542804e7542804e75", "pad_fill")]
# Bytes of the features before this one that a feature merged after it rewrites, as those features hold
# them: the wave pictures (from v0.2.3) replace 10 B of the CFO oscillator's picture hook, which
# patch.json then lists under wave_pictures. build_without_filt gives them back.
LATER_SITES = [(0x400f7c5c, "202f000804800000006c", "cfo_oscillator")]
S52_SHA = "3b88fa95268a05cba3181c8765b3379a46237b00def0820d1c8c4da499506f37"   # S52's section 3, v0.2.2
LD = os.path.join(HERE, "filt.ld")
# The four new pads (vetted in notes/landing_pads.md): extent, from the stock rts.
PADS = {".filt_ved": (0x400d266e, 0x400d26aa),     # FUN_400d266e, counts the set bits of a bitmap
        ".filt_key": (0x40178f20, 0x40178f76),     # FUN_40178f20, a string search (last not of)
        ".filt_rd": (0x40178e02, 0x40178e44),      # FUN_40178e02, a string search (first not of)
        ".filt_ext": (0x401778a4, 0x401778d8)}     # FUN_401778a4, counts a list's nodes
RODATA = (0x40252ed9, 0x40252f00)                  # the .rodata padding before portamento's names
# The hook: address, stock bytes (jsr FUN_40073412, the filter envelope's level).
HOOK = (0x400728a2, "4eb940073412", "filt_hook")
# S39: descriptor rows 1 and 2 (0x401aa09c + 0x34 x id), stock words, and the new rows' fields.
DESC = 0x401aa09c
ROW_STOCK = {1: "ffffffff ffffffff 00000000 00000000 00000000 00000001 00010021 ffffffff 00000001 00000000 401c2568 401c5bc7 401c20af",
             2: "ffffffff ffffffff 00000000 00000000 00000000 00000001 00020022 ffffffff 00000002 00000000 401c2568 401c5bc7 401c20af"}
GROUP_FILTER = 0x401c699d                          # the "Filter" group string, ENV's and DEL's
# page 6 (the filter's, as ENV and the page's other rows), slot, min 1.0, max 127.0, default 64.0, the
# 0/1 word as ENV's, no CC, no NRPN, the external number kept, flags 0 (no LFO destination), long name,
# group, short name
ROWS = {1: (6, 48, 0x0100, 0x7f00, 0x4000, 1, 0xffffffff, 0xffffffff, 1, 0, "s_vedl", GROUP_FILTER, "s_ved"),
        2: (6, 49, 0x0100, 0x7f00, 0x4000, 1, 0xffffffff, 0xffffffff, 2, 0, "s_keyl", GROUP_FILTER, "s_key")}
# S47: VED's row as a percentage, 0..100.0 (0x0000..0x6400) as Trig Probability's (id 29), default 0
ROW1_PCT = (6, 48, 0, 0x6400, 0, 1, 0xffffffff, 0xffffffff, 1, 0, "s_vedl", GROUP_FILTER, "s_ved")
# S39: the display objects' start-up build (FUN_40152280) for ids 1 and 2: text and picture as ENV's
# (id 38: text 0x4197d7cc, picture 0x4197d4dc); the template stays the whole-step knob's.
DOBJ = [(0x40153354, "4197d7ec", "4197d7cc", "id 1 text: ENV's"),
        (0x40153362, "4197d58c", "4197d4dc", "id 1 picture: ENV's"),
        (0x40153394, "4197d7ec", "4197d7cc", "id 2 text: ENV's"),
        (0x401533a2, "4197d58c", "4197d4dc", "id 2 picture: ENV's")]
# S49: id 1's flags word, cleared by the build (in .bss, zero before its one run), becomes 4: addq.l #4.
# The parameter cell (MachineParameterPageView::vfunc_37) then draws the name, not the value's text,
# when the knob is touched, on any page type but 5 (0x40030ce2..0x40030d22), as for ROUT.
LABEL = (0x4015334a, "42b94197e34c", "58b94197e34c", "id 1 (VED) flags: 4, the name under the picture")
# S51: id 2's (KEY's) text is copied from a constant object at 0x40252fec in the .rodata padding: its
# storage (+0, 8 B) is the slot -> stored index table's two entries (S43), which ENV's text manager
# 0x40060d88 never reads (its clone allocates an empty functor); +8 the manager, +12 the invoker:
# ENV's 0x40065842 in S51, key_txt in S52.
KEYOBJ = 0x40252fec
KEY_TEXT_OP = (0x40153394, "4197d7cc", "%08x" % KEYOBJ, "id 2 text: the constant object")
ENV_TEXT = (0x40060d88, 0x40065842)                # ENV's text object 0x4197d7cc: manager, invoker
# S52: KEY's text routine in two leftovers of Chain Recording's pads (the build's fill there)
FRAGS = {".filt_txt1": (0x40124b24, 0x40124b32), ".filt_txt2": (0x401282ce, 0x401282e0)}
# S47: id 1 (VED) shown as Trig Probability (id 29): the text 0x4197d59c (invoker 0x4005fd14, `%d%%` of the
# whole part) and the picture 0x4197d3cc (invoker 0x40066544, the same number drawn in the picture's box).
DOBJ_PCT = [(0x40153354, "4197d7cc", "4197d59c", "id 1 text: PROB's"),
            (0x40153362, "4197d4dc", "4197d3cc", "id 1 picture: PROB's")]
# S39: FILTER page 2's layout record (0x4197e0bc) gets ids 1 and 2 on knobs C and G. Its words are in
# .bss, zero before the one start-up run of FUN_40152280, so addq.l #1/#2 stores 1 and 2 in place of
# clr.l. Knob B (0x4197e0c8) stays empty.
LAYOUT = [(0x401568ca, "42b94197e0cc", "52b94197e0cc", "FILTER page 2 knob C = id 1 (VED)"),
          (0x401568e4, "42b94197e0dc", "54b94197e0dc", "FILTER page 2 knob G = id 2 (KEY)")]
# S40: FILTER page 2's draw routine FUN_40037564. For a knob's id it takes id - 39 through a five-entry
# table (DEL 39, SRR 42 and ROUT 43 clear %d1: the cell draws the picture; BASE 40 and WDTH 41 keep it:
# no picture, the page draws them as one box) and gives any other id %d1 = 1, no picture; the cell
# drawer gets %d1 & 1 as its last argument. Here the bound becomes `moveq #5` with `bhi` (the same
# range, and %d1 odd at the table's targets), the other ids get `moveq #0`, and BASE's and WDTH's
# branches skip that moveq. Stock bytes.
PICTURE = [(0x400376b2, "7204", "7205", "the table's bound: moveq #4 -> #5"),
           (0x400376bc, "6466", "6266", "bcc -> bhi: the same range, ids 39..43"),
           (0x400376be, "7201", "7200", "any other id: moveq #1 -> #0, the picture"),
           (0x4003773c, "6080", "6082", "WDTH: bra to the cell call, %d1 still 5"),
           (0x40037746, "6000ff76", "6000ff78", "BASE: bra to the cell call, %d1 still 5")]
# S42: the sound reader's jsr (portamento's rd_hook) and the two lookups' 46/47 test. Expected: the
# build's bytes.
READER_OP = (0x4007a2ac, "400e6d52", "rd_hook2", "the sound reader's hook: portamento's rd_hook")
EXT_SITES = [(0x4007974c, "722fb2806402", "fwd_ext", "FUN_40079738's test for 46, 47"),
             (0x40079786, "722fb2806402", "inv_ext", "FUN_40079772's test for 46, 47")]
# S43: the sound writer's 48 words -> 50 (portamento's moveq #96), and the slot -> stored index table
# (portamento's, 48 entries at 0x40252f2c) gets slots 48 and 49 at indices 50 and 51.
WRITER = (0x4007a614, "7260", "7264", "sound writer: 50 words (moveq #96 -> #100)")
INV_EXT = (0x40252fec, "0000000000000000", "0000003200000033", "slot -> stored index: 48 -> 50, 49 -> 51")
# S45: the display build's clrl of id 2's [FUNC] callable manager, in id 3's block, becomes the call of
# filt_spc; %a4 must hold the callable copier there (written once, at 0x401522b2, before the site).
SNAP_SITE = (0x401533c0, "42b94197e3ec", "filt_spc", "the display build: clrl of id 2's +0x4c")
A4_LOAD = (0x401522b2, "49f940151f6c")
VARIANTS = {"inert": ["INERT"], "S41": ["ACTIVE"], "S42": ["ACTIVE", "SAVEMOVE"],
            "S43": ["ACTIVE", "SAVEMOVE", "SAVE"], "S44": ["ACTIVE", "SAVEMOVE", "SAVE", "KEY2"],
            "S45": ["ACTIVE", "SAVEMOVE", "SAVE", "KEY2", "SNAP", "SNAPINERT"],
            "S46": ["ACTIVE", "SAVEMOVE", "SAVE", "KEY2", "SNAP"],
            "S47": ["ACTIVE", "SAVEMOVE", "SAVE", "KEY2", "SNAP", "VEDPCT"],
            "S48": ["ACTIVE", "SAVEMOVE", "SAVE", "KEY2", "SNAP", "VEDPCT", "VED100"],
            "S49": ["ACTIVE", "SAVEMOVE", "SAVE", "KEY2", "SNAP", "VEDPCT", "VED100", "VEDLABEL"],
            "S50": ["ACTIVE", "SAVEMOVE", "SAVE", "KEY2", "SNAP", "VEDPCT", "VED100", "VEDLABEL", "KEY4X"],
            "S51": ["ACTIVE", "SAVEMOVE", "SAVE", "KEY2", "SNAP", "VEDPCT", "VED100", "VEDLABEL", "KEY4X", "KEYOBJ"],
            "S52": ["ACTIVE", "SAVEMOVE", "SAVE", "KEY2", "SNAP", "VEDPCT", "VED100", "VEDLABEL", "KEY4X", "KEYOBJ", "KEYTXT"]}


def run(*cmd):
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode:
        sys.exit("%s\n%s%s" % (" ".join(cmd), r.stdout, r.stderr))
    return r.stdout


def assemble(tmp, opts):
    src = os.path.join(HERE, "filt.s")
    o, e = os.path.join(tmp, "filt.o"), os.path.join(tmp, "filt.elf")
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
        a, t, n = line.split()
        if t not in "Uw":
            syms[n] = int(a, 16)
    return secs, syms


def row_bytes(i, syms, row=None):
    w = list(row or ROWS[i])
    for k in (10, 12):
        w[k] = syms[w[k]]
    return b"".join((x & 0xffffffff).to_bytes(4, "big") for x in w)


def build_without_filt(stock, patch):
    """The build before FILTER page 2, from patch.json: its features before filter_page2 and, once that
    is merged, the bytes of REWRITES given back. It must hash to BUILD_SECTION3. Returns the image and
    who owns each byte it changes ({address: (feature, kind)})."""
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
    if FID in ids:
        for a, want, fid in REWRITES:
            d = bytes.fromhex(want)
            img[a - BASE:a - BASE + len(d)] = d
            for k in range(len(d)):
                if d[k] != stock[a - BASE + k]:
                    owner[a + k] = (fid, "code")
    h = build.sha256_bytes(bytes(img))
    if h != BUILD_SECTION3:
        sys.exit("the build before FILTER page 2 does not come back from patch.json (section 3 %s)" % h)
    return img, owner


def merged_features(stock, patch, img, owner, s52):
    """patch.json's features before filter_page2, and filter_page2 = S52 on the build before it (img,
    whose changed bytes owner attributes): each byte S52 changes, under the last feature that wrote it.
    Runs are maximal per feature and kind, as make_port.py writes them; outside the code windows, data."""
    def kind(a):
        return "code" if any(lo <= a < hi for lo, hi in make_listing.CODE_WINDOWS) else "data"
    attr = {}
    for i in range(len(stock)):
        if s52[i] != stock[i]:
            a = BASE + i
            attr[a] = (FID, kind(a)) if s52[i] != img[i] else owner[a]
    runs, cur = {}, None
    for a in sorted(attr):
        fid, k = attr[a]
        if cur and cur["fid"] == fid and cur["kind"] == k and cur["end"] == a:
            cur["b"].append(s52[a - BASE]); cur["end"] = a + 1
        else:
            cur = {"fid": fid, "kind": k, "start": a, "end": a + 1, "b": [s52[a - BASE]]}
            runs.setdefault(fid, []).append(cur)
    ids = [f["id"] for f in patch["features"]]
    earlier = patch["features"][:ids.index(FID) if FID in ids else len(ids)]
    features = [(f["id"], f["title"]) for f in earlier] + [(FID, TITLE)]
    return [{"id": i, "title": t,
             "runs": [{"addr": "0x%08x" % x["start"], "bytes": bytes(x["b"]).hex(), "kind": x["kind"]}
                      for x in runs.get(i, [])]} for i, t in features]


def place(im, secs):
    """Each code section into its pad (the rest of the pad keeps its fill), the names into the
    .rodata padding."""
    for name, (vma, data) in secs.items():
        if name in PADS or name in FRAGS:
            lo, hi = PADS.get(name) or FRAGS[name]
            assert vma == lo and vma + len(data) <= hi, "%s does not fit its pad" % name
        else:
            assert name == ".filt_names" and RODATA[0] <= vma and vma + len(data) <= RODATA[1], name
        im[vma - BASE:vma - BASE + len(data)] = data


def main():
    stages, write = "--stages" in sys.argv, "--write" in sys.argv
    if write and not stages:
        sys.exit("--write needs --stages (patch.json carries the .syx hash of the S52 build)")
    stock = build.read(cfo.STOCK3)
    with open(cfo.PATCH) as f:
        patch = json.load(f)
    ids = [f["id"] for f in patch["features"]]
    later = ids[ids.index(FID) + 1:] if FID in ids else []
    if write and later:
        sys.exit("patch.json holds features merged after filter_page2 (%s); the last one's generator rewrites it"
                 % ", ".join(later))
    assert build.sha256_bytes(stock) == patch["stock"]["section3_sha256"], "work/dt_1.54 is not the stock section 3"
    img, owner = build_without_filt(stock, patch)
    tmp = tempfile.mkdtemp(prefix="filt-")
    try:
        asm = {v: assemble(tmp, o) for v, o in VARIANTS.items()}
        secs_i, syms_i = asm["inert"]
        secs_f, syms_f = asm["S46"]
        # every byte this feature writes on stock bytes: stock, and untouched by the build
        touched = [(lo, hi, name) for name, (lo, hi) in PADS.items()]
        touched.append((HOOK[0], HOOK[0] + 6, "site filt_hook"))
        touched.append((SNAP_SITE[0], SNAP_SITE[0] + 6, "site filt_spc"))
        touched.append((LABEL[0], LABEL[0] + 6, LABEL[3]))
        touched.append((KEYOBJ + 8, KEYOBJ + 16, ".rodata: the KEY text object's manager, invoker"))
        for i in ROWS:
            touched.append((DESC + 0x34 * i, DESC + 0x34 * (i + 1), "row %d" % i))
        for addr, want, _n, what in DOBJ + LAYOUT + PICTURE:
            touched.append((addr, addr + len(want) // 2, what))
        vn, dn = secs_f[".filt_names"]
        touched.append((vn, vn + len(dn), ".filt_names"))
        for lo, hi, what in touched:
            assert not any(a in owner for a in range(lo, hi)), "the build patches %s" % what
            if what == ".filt_names" or what.startswith(".rodata"):
                assert stock[lo - BASE:hi - BASE] == bytes(hi - lo), ".rodata padding not zero in stock"
            print("%-50s 0x%08x..0x%08x  stock" % (what, lo, hi))
        assert stock[HOOK[0] - BASE:HOOK[0] - BASE + 6].hex() == HOOK[1], "the hook site is not stock"
        # the two leftovers: the build's pad fill, or stock bytes the build leaves (the pads' last rts)
        for name, (lo, hi) in FRAGS.items():
            assert all(owner.get(a, ("",))[0] == "pad_fill" or (a not in owner and img[a - BASE] == stock[a - BASE])
                       for a in range(lo, hi)), "%s is not the build's leftover fill" % name
            print("%-50s 0x%08x..0x%08x  the build's fill" % (name, lo, hi))
        assert stock[SNAP_SITE[0] - BASE:SNAP_SITE[0] - BASE + 6].hex() == SNAP_SITE[1], "the display build's site is not stock"
        lst = run(PFX + "objdump", "-D", "-b", "binary", "-m", "m68k:cfv4e", "--adjust-vma=0x%x" % BASE,
                  "--start-address=0x40152280", "--stop-address=0x%x" % SNAP_SITE[0], cfo.STOCK3)
        a4w = [l for l in lst.splitlines() if l.rstrip().endswith(",%a4") or "moveml" in l]
        assert stock[A4_LOAD[0] - BASE:A4_LOAD[0] - BASE + 6].hex() == A4_LOAD[1] and \
            [l.split(":")[0].strip() for l in a4w if "moveml" not in l] == ["%x" % A4_LOAD[0]], a4w
        print("%a4 = the callable copier 0x40151f6c at the display build's site (its one write before it)")
        # the build's bytes this feature rewrites: portamento's, as the build holds them
        for addr, want, sym, what in [READER_OP] + EXT_SITES:
            n = len(want) // 2
            assert img[addr - BASE:addr - BASE + n].hex() == want, "0x%08x is not %s" % (addr, what)
            assert all(owner.get(a, ("",))[0] == "portamento" for a in range(addr, addr + n) if img[a - BASE] != stock[a - BASE])
            print("%-50s 0x%08x..0x%08x  portamento's" % (what, addr, addr + n))
        for addr, want, new, what in (WRITER, INV_EXT):
            n = len(want) // 2
            assert img[addr - BASE:addr - BASE + n].hex() == want, "0x%08x is not %s" % (addr, what)
            print("%-50s 0x%08x..0x%08x  the build's" % (what, addr, addr + n))
        for name, (lo, hi) in list(PADS.items()) + list(FRAGS.items()):
            used = ", ".join("%s %d" % (v, len(s[name][1])) for v, (s, _y) in asm.items() if name in s)
            print("%-10s %s of %d B" % (name, used or "-", hi - lo))
        sites = [(HOOK[0], 6)] + [(a, len(w) // 2) for a, w, _s, _t in EXT_SITES]
        bad = [b for b in port.branch_targets_inside([(0x400376be, 2)]) if b[0] not in (0x4003773c, 0x40037746)]
        assert not bad, "branches into the moveq the picture fix changes: %s" % bad
        bad = port.branch_targets_inside(sites)
        assert not bad, "branches into a hook site: %s" % bad
        print("no instruction branches into a replaced site")
        # the stages
        s37 = bytearray(img)
        for lo, hi in PADS.values():
            s37[lo - BASE:hi - BASE] = cfo.fill(lo, hi)
        s38 = bytearray(s37)
        place(s38, {k: v for k, v in secs_i.items() if k in PADS})
        port.put(s38, HOOK[0], HOOK[1], "4eb9%08x" % syms_i["filt_hook"], "filt_hook")
        s39 = bytearray(s38)
        for i in ROWS:
            port.put(s39, DESC + 0x34 * i, ROW_STOCK[i].replace(" ", ""), row_bytes(i, syms_i).hex(), "row %d" % i)
        for addr, want, new, what in DOBJ + LAYOUT:
            port.put(s39, addr, want, new, what)
        place(s39, {".filt_names": secs_i[".filt_names"]})
        s40 = bytearray(s39)
        for addr, want, new, what in PICTURE:
            port.put(s40, addr, want, new, what)
        built = {"S37": bytes(s37), "S38": bytes(s38), "S39": bytes(s39), "S40": bytes(s40)}
        prev = s40
        for v in ("S41", "S42", "S43", "S44", "S45", "S46", "S47", "S48", "S49", "S50", "S51", "S52"):
            secs, syms = asm[v]
            assert syms["filt_hook"] == syms_i["filt_hook"] and secs[".filt_names"] == secs_i[".filt_names"]
            im = bytearray(prev)
            place(im, secs)
            if v == "S42":
                addr, want, sym, what = READER_OP
                port.put(im, addr, want, "%08x" % syms[sym], what)
                for addr, want, sym, what in EXT_SITES:
                    port.put(im, addr, want, "4ef9%08x" % syms[sym], what)
            if v == "S43":
                for addr, want, new, what in (WRITER, INV_EXT):
                    port.put(im, addr, want, new, what)
                # the code moved inside its pads: point the reader's call and both jumps at this
                # stage's entries (each site must hold this build's call or jump)
                off = READER_OP[0] - BASE
                assert im[off - 2:off].hex() == "4eb9", "the reader's site is not this build's call"
                im[off:off + 4] = syms["rd_hook2"].to_bytes(4, "big")
                for addr, _want, sym, what in EXT_SITES:
                    off = addr - BASE
                    assert im[off:off + 2].hex() == "4ef9", "%s is not this build's jump" % what
                    im[off + 2:off + 6] = syms[sym].to_bytes(4, "big")
            if v in ("S44", "S45", "S46"):
                for name in (".filt_ved", ".filt_rd", ".filt_ext"):
                    assert secs[name] == asm["S43"][0][name], "%s moved in %s" % (name, v)
            if v == "S45":
                port.put(im, SNAP_SITE[0], SNAP_SITE[1], "4eb9%08x" % syms["filt_spc"], "filt_spc")
            if v == "S46":
                port.put(im, SNAP_SITE[0], "4eb9%08x" % asm["S45"][1]["filt_spc"], "4eb9%08x" % syms["filt_spc"], "filt_spc")
            if v == "S47":
                # the VED part and the reader change in place: their entries, which the hook site and
                # the reader's call name, stay; the KEY part (with filt_spc) and the lookups do not move
                s46 = asm["S46"]
                for name in (".filt_key", ".filt_ext"):
                    assert secs[name] == s46[0][name], "%s moved in S47" % name
                for n in ("filt_key", "filt_spc", "rd_hook2"):
                    assert syms[n] == s46[1][n], "%s moved in S47" % n
                port.put(im, DESC + 0x34, row_bytes(1, syms).hex(), row_bytes(1, syms, ROW1_PCT).hex(), "row 1: VED in %")
                for addr, want, new, what in DOBJ_PCT:
                    port.put(im, addr, want, new, what)
            if v in ("S48", "S49", "S50", "S51", "S52"):
                # every later change keeps the entries the sites name, and the reader and lookups
                for n in ("filt_hook", "filt_spc", "rd_hook2", "fwd_ext", "inv_ext", "filt_key"):
                    assert syms[n] == asm["S47"][1][n], "%s moved in %s" % (n, v)
                for name in (".filt_rd", ".filt_ext"):
                    assert secs[name] == asm["S47"][0][name], "%s changed in %s" % (name, v)
            if v == "S48":
                assert secs[".filt_key"] == asm["S47"][0][".filt_key"], ".filt_key changed in S48"
            if v == "S49":
                assert secs == asm["S48"][0]
                port.put(im, *LABEL)
            if v == "S50":
                assert secs[".filt_key"][1][-26:] == asm["S47"][0][".filt_key"][1][-26:], "filt_spc changed"
            if v == "S51":
                assert secs == asm["S50"][0]
                port.put(im, *KEY_TEXT_OP)
                port.put(im, KEYOBJ + 8, "00" * 8, "%08x%08x" % ENV_TEXT, "the KEY text object: ENV's text")
            if v == "S52":
                port.put(im, KEYOBJ + 12, "%08x" % ENV_TEXT[1], "%08x" % syms["key_txt"], "the KEY text object: key_txt")
            built[v] = bytes(im)
            prev = im
        # load files for the emulator harness: every byte the stage changes from stock (the build's
        # features included, since the save path runs portamento's code with this feature's)
        out = os.path.join(ROOT, "work", "dt_1.54-filt")
        os.makedirs(out, exist_ok=True)
        for name, im in built.items():
            sy = asm[name][1] if name in asm else syms_i
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
        print("load files:", ", ".join(os.path.join("work", "dt_1.54-filt", n + ".load") for n in built))
        feats = merged_features(stock, patch, img, owner, built["S52"])
        s52 = build.sha256_bytes(built["S52"])
        if later:
            print("S52 section 3 %s; %s; patch.json carries %s after it" % (
                s52, "as recorded (v0.2.2)" if s52 == S52_SHA else "NOT as recorded (%s)" % S52_SHA, ", ".join(later)))
        else:
            same = feats == patch["features"] and s52 == patch["result"]["section3_sha256"]
            print("S52 section 3 %s; patch.json %s" % (s52, "is up to date" if same else
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
                final = os.path.join(dest, "dt_og_plus_plus_v0.2.1-%s_%s.syx" % (name, h[:8]))
                os.replace(packed, final)
            finally:
                shutil.rmtree(t2, ignore_errors=True)
            res[name] = (h, size)
            print("%s section 3 %s .syx %s -> %s" % (name, build.sha256_bytes(p3), h, final))
        if write:
            patch["features"] = feats
            patch["result"] = dict(patch["result"], section3_sha256=s52,
                                   syx_sha256_reference=res["S52"][0], syx_size_reference=res["S52"][1])
            with open(cfo.PATCH, "w") as f:
                json.dump(patch, f, indent=1)
                f.write("\n")
            print("wrote", cfo.PATCH)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    print("Nothing was sent to a device.")


if __name__ == "__main__":
    main()
