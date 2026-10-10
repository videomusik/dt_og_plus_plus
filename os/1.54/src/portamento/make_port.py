#!/usr/bin/env python3
"""Portamento for OS 1.54, on top of the CFO oscillator's last stage (S27): assemble port.s with
port.ld, check every site against the stock image, the build and S27, and write the emulator's load
files; with --stages, build the stage images; with --write, merge the CFO oscillator and portamento into
os/1.54/build/patch.json.

    python3 os/1.54/src/portamento/make_port.py                    # check: is patch.json up to date?
    python3 os/1.54/src/portamento/make_port.py --stages           # also build the stage images
    python3 os/1.54/src/portamento/make_port.py --stages --write   # and rewrite patch.json

Needs what make_cfo.py needs (m68k binutils, M68K_PREFIX; the stock section 3 from
./scripts/extract.sh 1.54; for --stages the stock .syx and the firmware tool).

The build without the CFO oscillator comes back from patch.json (make_cfo.build_without_cfo), and S27 is
rebuilt on it with make_cfo.py's own routines and must give its recorded section-3 hash. Stages, written
to out/1.54/stages/ (only with --stages):
  S28  S27 + the two new pads filled with 'clrl %d0 ; rts' (their fill test)
  S29  S28 + both hooks, each pad only replaying the instructions its hook replaced
  S30  S29 + PORT and LEG on the TRIG page's knobs G and H: descriptor rows 4 and 5 (until now unused
       "Error" rows) as sound parameters in the free value slots 46 and 47, their names, their display
       objects, and the TRIG page's layout; the hooks still inert, so nothing glides yet
  S31  S30 + the glide: port_on and port_glide, and the CFO oscillator's note read pointed at the glided
       note
  S32  S31 + PORT shows OFF at 0; LEG's display object gets FLT.T's flag word (no value text while
       turned)
  S33  S32 + PORT and LEG saved with the sound and their p-locks with the pattern: the sound writer
       writes 48 words, the stored record's spare words carry them, the reader takes them back, and
       the stored-index lookups map them both ways
  S34  S33 + with LEG on, a legato note does not restart the amp envelope
  S35  S34 + a note is legato when the track's amp envelope is still in its attack or hold (the gate
       bit S31 to S34 test stays set after a sequenced trig's LEN, so every note was legato)
  S36  S35 + a note is legato when no NoteOff came since the track's last NoteOn (the release byte
       the amp envelope gets: LEN's countdown, note-off events, a stop), not by the envelope's state
S36 is the build: patch.json holds the build without the CFO oscillator, then cfo_oscillator (S27 less
that build) and portamento (S36 less S27). Each byte is listed once, under the last feature that wrote
it, so the build's bytes that the CFO oscillator rewrites are listed under cfo_oscillator, and the CFO
oscillator's note operand that S31 points at the glided note under portamento. Once patch.json holds a
feature merged after portamento (filter_page2, v0.2.2), S36 is checked against its recorded hash and
--write is refused: the last feature's generator (make_wavpic.py) rewrites patch.json."""
import json, os, re, shutil, subprocess, sys, tempfile
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", "..", ".."))
sys.path.insert(0, os.path.join(ROOT, "os", "1.54", "build"))
sys.path.insert(0, os.path.join(ROOT, "os", "1.54", "src", "cfo_oscillator"))
import build  # noqa: E402
import make_listing  # noqa: E402
import make_cfo as cfo  # noqa: E402
PFX = os.environ.get("M68K_PREFIX", "m68k-elf-")
BASE = 0x40000400
FID = "portamento"
TITLE = ("Portamento and legato: PORT and LEG on every audio track's TRIG page, saved with the sound and "
         "lockable per trig; with LEG on, a note before the last one's LEN ends glides and keeps the amp "
         "envelope running")
CODE_END = 0x4017cc64                   # the end of the code (the branch scan's range)
S27_SHA = "703cedf780d68ee9a4187956d6a5b87d46d284804fc962fa2950f0ae4c189ff0"   # S27's section 3
S36_SHA = "d6fac1a35cf565c37fc43ae51bd0c76f1ee7e23c0668ff1d245bb2839e51509c"   # S36's section 3, v0.2.1
CFO_LD = os.path.join(ROOT, "os", "1.54", "src", "cfo_oscillator", "lz4_stream.ld")
LD = os.path.join(HERE, "port.ld")
# The two new pads (vetted in notes/landing_pads.md): extent, from the stock rts.
PADS = {".port_on": (0x400e6d1c, 0x400e6d88),      # FUN_400e6d1c, a 28 B record packer, unreferenced
        ".port_glide": (0x400ee05e, 0x400ee0d8)}   # FUN_400ee05e, a ring routine, unreferenced
RODATA2 = (0x40252c2c, 0x40253000)                 # the .rodata padding after Chain Recording's strings
# The hook sites: address, stock bytes, the symbol called, and the bytes after the jsr.
SITES = [(0x400779f6, "43f980001f28", "port_on", ""),                   # lea NOTES,%a1 (note-on)
         (0x40075690, "41f980001f28" "7203" "2c30ec00", "port_glide",   # lea NOTES,%a0; moveq #3,%d1;
          "7203" "6002" "4e71")]                                         # movel %a0@(0,%fp:l:4),%d6
# S33: the sound reader FUN_4007a236 before its value loop (lea 0x401ac58c,%a0).
SAVE_SITES = [(0x4007a2aa, "41f9401ac58c", "rd_hook", "")]
# S34: the ISR's store of the amp envelope's masks (moveb %d3,%fp@(-36) ; moveb %d2,%fp@(-35)).
HOLD_SITES = [(0x40078070, "1d43ffdc" "1d42ffdd", "amp_hook", "4e71")]
# S30: descriptor rows 4 and 5 (0x401aa09c + 0x34 x id), stock words, and the new rows' fields.
DESC = 0x401aa09c
ROW_STOCK = {4: "ffffffff ffffffff 00000000 00000000 00000000 00000000 0007ffff ffffffff 00000004 00000000 401c2568 401c5bc7 401c20af",
             5: "ffffffff ffffffff 00000000 00000000 00000000 00000000 000affff ffffffff 00000005 00000000 401c2568 401c5bc7 401c20af"}
GROUP_NONE = 0x401c5bc7                            # the empty group string the TRIG rows use
# page 5 (a sound page no stock row uses), slot, min, max, default (8.8), the 0/1 word, no CC, no NRPN,
# the external number kept, flags 0 (as the TRIG rows: not an LFO destination), long name, group, short
ROWS = {4: (5, 46, 0, 0x7f00, 0, 0, 0xffffffff, 0xffffffff, 4, 0, "s_portl", GROUP_NONE, "s_port"),
        5: (5, 47, 0, 0x0100, 0, 0, 0xffffffff, 0xffffffff, 5, 0, "s_legl", GROUP_NONE, "s_leg")}
# S30: the display objects' start-up build (FUN_40152280) for ids 4 and 5: (address, stock, new, what).
DOBJ = [(0x40153410, "4197d7ec", "4197d7fc", "id 4 text: the '%d' text (VEL's, ATK's) for the error row's"),
        (0x40153432, "2f02", "2f03", "id 5 template: the selector step profile 0x4018e1ac (LFO.T's, PLAY's)"),
        (0x40153450, "4197d7ec", "4197d6ac", "id 5 text: OFF/ON (FLT.T's, LFO.T's)"),
        (0x4015345e, "4197d58c", "4197d37c", "id 5 picture: the switch (FLT.T's, LFO.T's)")]
# S30: the TRIG page's layout record (0x4197dfb4) gets ids 4 and 5 on knobs G and H. Its words are in
# .bss, zero before the one start-up run of FUN_40152280, so addq.l #4/#5 stores 4 and 5 in place of clr.l.
LAYOUT = [(0x401565f8, "42b94197dfd4", "58b94197dfd4", "TRIG knob G = id 4 (PORT)"),
          (0x40156612, "42b94197dfd8", "5ab94197dfd8", "TRIG knob H = id 5 (LEG)")]
# S32: id 4's text from this build's object (OFF at 0); id 5's flag word 4 as FLT.T's, by the same
# zeroed-.bss, run-once argument as the layout (the clr.l is the word's only writer).
OFF_OPS = [(0x40153410, "port_tobj", "id 4 text: OFF at 0, else %d")]
LEG_FLAG = [(0x40153446, "42b94197e49c", "58b94197e49c", "id 5 flags: 4, FLT.T's")]
# S33: the sound writer FUN_4007a5a0 (its table and its loop's 46 words -> 48), the p-lock writers
# FUN_4007adb2 and FUN_4007aefa (their table), operand by operand; and the two lookups rewritten in place.
SAVE_OPS = [(0x4007a5fa, "401ac4d4", "inv_slots", "sound writer: slot -> stored index table"),
            (0x4007adec, "401ac4d4", "inv_slots", "p-lock writer: slot -> stored index table"),
            (0x4007af34, "401ac4d4", "inv_slots", "p-lock store: slot -> stored index table")]
SAVE_EDITS = [(0x4007a614, "725c", "7260", "sound writer: 48 words (moveq #92 -> #96)")]
INPLACE = {".port_fwd": (0x40079738, "2f027410222f0008202f000cb481651066087223b280640c6006742db480640c"
                                     "4280601241f9401ac444600641f9401ac58c20300c00241f4e75"),
           ".port_inv": (0x40079772, "2f027410222f0008202f000cb481651066087233b2806508600a742db480640c"
                                     "4280601241f9401ac374600641f9401ac4d420300c00241f4e75")}
# S31: the CFO oscillator's note read (lea NOTES,%a0 then the indexed movel into %d2), in its pad.
CFO_PAD = (0x400f77da, 0x400f811e)
CFO_NOTE = "41f980001f28" "24300c00"
# The assembly options of each build: the inert hooks, and the stages from S31 on.
VARIANTS = {"inert": ["INERT"], "S31": [], "S32": ["OFFTEXT"], "S33": ["OFFTEXT", "SAVE"],
            "S34": ["OFFTEXT", "SAVE", "HOLDAMP"], "S35": ["OFFTEXT", "SAVE", "HOLDAMP", "LEGAMP"],
            "S36": ["OFFTEXT", "SAVE", "HOLDAMP", "LEGAMP", "LENCNT"]}


def run(*cmd):
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode:
        sys.exit("%s\n%s%s" % (" ".join(cmd), r.stdout, r.stderr))
    return r.stdout


def assemble(tmp, opts):
    src = os.path.join(HERE, "port.s")
    o, e = os.path.join(tmp, "port.o"), os.path.join(tmp, "port.elf")
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


def s27_image(stock, img, tmp):
    """S27 as make_cfo.py builds it: the build, the LZ4 pad filled, S27's sections and edits."""
    st, suf, fl = cfo.KNOB_STAGES[-1]
    assert st == "S27", "make_cfo.py's last stage is %s, not S27" % st
    ss, sy = cfo.assemble(CFO_LD, tmp, machine5=True, names=True, icon=True, slots=True, **fl)
    im = bytearray(img)
    for lo, hi in cfo.PADS["lz4_stream"]:
        im[lo - BASE:hi - BASE] = cfo.fill(lo, hi)
    for _n, (vma, data) in ss.items():
        im[vma - BASE:vma - BASE + len(data)] = data
    im = cfo.apply_later(cfo.apply_edits(im, sy), sy)
    h = build.sha256_bytes(bytes(im))
    assert h == S27_SHA, "S27 rebuilt gives %s, not %s" % (h, S27_SHA)
    return bytes(im), h


def branch_targets_inside(sites):
    """Operands of the stock code (objdump, notes/analysis_method.md) outside each site that name an
    address strictly inside it: a branch into the replaced bytes would break the hook."""
    lst = run(PFX + "objdump", "-D", "-b", "binary", "-m", "m68k:cfv4e", "--adjust-vma=0x%x" % BASE,
              "--start-address=0x%x" % BASE, "--stop-address=0x%x" % CODE_END, cfo.STOCK3)
    bad = []
    for line in lst.splitlines():
        m = re.match(r"^\s*([0-9a-f]+):\t[0-9a-f ]+\t(.*)$", line)
        if not m:
            continue
        a = int(m.group(1), 16)
        for x in re.findall(r"0x([0-9a-f]+)", m.group(2)):
            v = int(x, 16)
            for lo, n in sites:
                if lo < v < lo + n and not (lo <= a < lo + n):
                    bad.append((a, m.group(2).strip()))
    return bad


def row_bytes(i, syms):
    w = list(ROWS[i])
    for k in (10, 12):
        w[k] = syms[w[k]]
    return b"".join((x & 0xffffffff).to_bytes(4, "big") for x in w)


def put(im, addr, want, new, what):
    off = addr - BASE
    n = len(want) // 2
    got = im[off:off + n].hex()
    assert got == want, "0x%08x holds %s, expected %s (%s)" % (addr, got, want, what)
    assert len(new) // 2 == n, "0x%08x: %s is not %d B" % (addr, new, n)
    im[off:off + n] = bytes.fromhex(new)


def apply_sites(im, syms, sites):
    for addr, want, sym, tail in sites:
        put(im, addr, want, "4eb9%08x" % syms[sym] + tail, sym)


def repoint_sites(im, syms, sites):
    """A later stage's code can move inside its pad: point each hook site that an earlier stage made
    at this stage's entry (the site must already hold a jsr and its tail)."""
    for addr, _want, sym, tail in sites:
        off = addr - BASE
        n = 6 + len(tail) // 2
        got = im[off:off + n].hex()
        assert got[:4] == "4eb9" and got[12:] == tail, "0x%08x is not this build's jsr (%s)" % (addr, got)
        im[off + 2:off + 6] = syms[sym].to_bytes(4, "big")


def apply_data(im, syms, secs):
    for i in ROWS:
        r = DESC + 0x34 * i
        put(im, r, ROW_STOCK[i].replace(" ", ""), row_bytes(i, syms).hex(), "descriptor row %d" % i)
    for addr, want, new, what in DOBJ + LAYOUT:
        put(im, addr, want, new, what)
    place_names(im, secs)


def place_names(im, secs):
    vma, data = secs[".port_names"]
    im[vma - BASE:vma - BASE + len(data)] = data


def place_code(im, secs):
    for name, (lo, hi) in PADS.items():
        vma, data = secs[name]
        assert vma == lo and vma + len(data) <= hi, "%s does not fit its pad" % name
        im[lo - BASE:hi - BASE] = cfo.fill(lo, hi)
        im[vma - BASE:vma - BASE + len(data)] = data


def apply_offtext(im, syms, secs):
    """S32: PORT's text object and LEG's flag word."""
    place_names(im, secs)
    for addr, sym, what in OFF_OPS:
        put(im, addr, "4197d7fc", "%08x" % syms[sym], what)
    for addr, want, new, what in LEG_FLAG:
        put(im, addr, want, new, what)


def apply_save(im, syms, secs):
    """S33: the writers' table and length, the two lookups in place, the reader's hook."""
    place_names(im, secs)
    for addr, want, sym, what in SAVE_OPS:
        put(im, addr, want, "%08x" % syms[sym], what)
    for addr, want, new, what in SAVE_EDITS:
        put(im, addr, want, new, what)
    for name, (lo, stock_hex) in INPLACE.items():
        vma, data = secs[name]
        n = len(stock_hex) // 2
        assert vma == lo and len(data) <= n, "%s does not fit the function it replaces" % name
        new = data + bytes.fromhex("4e71") * ((n - len(data)) // 2)
        put(im, lo, stock_hex, new.hex(), name)
    apply_sites(im, syms, SAVE_SITES)


def merged_features(stock, patch, img, owner, s27, s36):
    """patch.json's features with S27 and S36 merged on the build without the CFO oscillator (img, whose
    changed bytes owner attributes): each byte S36 changes, under the last feature that wrote it. Runs
    are maximal per feature and kind, as make_chain.py writes them; outside the code windows, data."""
    def kind(a):
        return "code" if any(lo <= a < hi for lo, hi in make_listing.CODE_WINDOWS) else "data"
    attr = {}
    for i in range(len(stock)):
        if s36[i] != stock[i]:
            a = BASE + i
            attr[a] = (FID, kind(a)) if s36[i] != s27[i] else \
                (cfo.FID, kind(a)) if s27[i] != img[i] else owner[a]
    runs, cur = {}, None
    for a in sorted(attr):
        fid, k = attr[a]
        if cur and cur["fid"] == fid and cur["kind"] == k and cur["end"] == a:
            cur["b"].append(s36[a - BASE]); cur["end"] = a + 1
        else:
            cur = {"fid": fid, "kind": k, "start": a, "end": a + 1, "b": [s36[a - BASE]]}
            runs.setdefault(fid, []).append(cur)
    ids = [f["id"] for f in patch["features"]]
    earlier = patch["features"][:ids.index(cfo.FID) if cfo.FID in ids else len(ids)]
    features = [(f["id"], f["title"]) for f in earlier] + [(cfo.FID, cfo.TITLE), (FID, TITLE)]
    return [{"id": i, "title": t,
             "runs": [{"addr": "0x%08x" % x["start"], "bytes": bytes(x["b"]).hex(), "kind": x["kind"]}
                      for x in runs.get(i, [])]} for i, t in features]


def main():
    stages, write = "--stages" in sys.argv, "--write" in sys.argv
    if write and not stages:
        sys.exit("--write needs --stages (patch.json carries the .syx hash of the S36 build)")
    stock = build.read(cfo.STOCK3)
    patch, _runs = build.load_patch(cfo.PATCH)
    ids = [f["id"] for f in patch["features"]]
    later = ids[ids.index(FID) + 1:] if FID in ids else []
    if write and later:
        sys.exit("patch.json holds features merged after portamento (%s); the last one's generator "
                 "rewrites it" % ", ".join(later))
    img, owner = cfo.build_without_cfo(stock, patch)
    tmp = tempfile.mkdtemp(prefix="port-")
    try:
        s27, h27 = s27_image(stock, img, tmp)
        print("S27 rebuilt: section 3 %s" % h27)
        asm = {v: assemble(tmp, o) for v, o in VARIANTS.items()}
        secs_i, syms_i = asm["inert"]
        secs_l, syms_l = asm["S34"]
        # every byte this feature writes: stock in the stock image, untouched by the build and by S27
        touched = []
        for name, (lo, hi) in PADS.items():
            touched.append((lo, hi, name))
        for addr, want, sym, _t in SITES + SAVE_SITES + HOLD_SITES:
            touched.append((addr, addr + len(want) // 2, "site " + sym))
        for i in ROWS:
            touched.append((DESC + 0x34 * i, DESC + 0x34 * (i + 1), "row %d" % i))
        for addr, want, _n, what in DOBJ + LAYOUT + LEG_FLAG + SAVE_EDITS:
            touched.append((addr, addr + len(want) // 2, what))
        for addr, want, _s, what in SAVE_OPS:
            touched.append((addr, addr + 4, what))
        for name, (lo, stock_hex) in INPLACE.items():
            touched.append((lo, lo + len(stock_hex) // 2, name + " (in place)"))
        vn, dn = secs_l[".port_names"]
        assert RODATA2[0] <= vn and vn + len(dn) <= RODATA2[1], ".port_names outside the .rodata padding"
        assert stock[vn - BASE:vn + len(dn) - BASE] == bytes(len(dn)), ".rodata padding not zero in stock"
        touched.append((vn, vn + len(dn), ".port_names"))
        for lo, hi, what in touched:
            assert not any(a in owner for a in range(lo, hi)), "the build patches %s" % what
            assert s27[lo - BASE:hi - BASE] == stock[lo - BASE:hi - BASE], "S27 changes %s" % what
            print("%-60s 0x%08x..0x%08x" % (what, lo, hi))
        for name in PADS:
            for v, (s, _y) in asm.items():
                assert s[name][0] == PADS[name][0] and s[name][0] + len(s[name][1]) <= PADS[name][1], (v, name)
            print("%-12s %s of %d B" % (name, ", ".join("%s %d" % (v, len(s[name][1])) for v, (s, _y) in asm.items()),
                                       PADS[name][1] - PADS[name][0]))
        # the stock table the writers' new one extends
        inv = stock[0x401ac4d4 - BASE:0x401ac4d4 - BASE + 46 * 4]
        sy33 = asm["S33"][1]
        it = secs_l[".port_names"][1][sy33["inv_slots"] - vn:sy33["inv_slots"] - vn + 48 * 4]
        assert it[:46 * 4] == inv and it[46 * 4:] == bytes.fromhex("0000002e0000002f"), "inv_slots"
        print("inv_slots: the stock table's 46 entries, then 46 and 47")
        sites = [(a, len(w) // 2) for a, w, _s, _t in SITES + SAVE_SITES + HOLD_SITES]
        sites += [(lo, len(h) // 2) for lo, h in INPLACE.values()]
        bad = branch_targets_inside(sites)
        assert not bad, "branches into a hook site or a rewritten function: %s" % bad
        print("no instruction branches into a hook site's replaced bytes or into a rewritten function")
        # the CFO oscillator's note read: exactly one in its pad
        lo, hi = CFO_PAD
        pat = bytes.fromhex(CFO_NOTE)
        found = [i for i in range(lo - BASE, hi - BASE) if s27[i:i + len(pat)] == pat]
        assert len(found) == 1, "the CFO oscillator's note read found %d times" % len(found)
        cfo_note = BASE + found[0] + 2
        print("CFO oscillator's note operand at 0x%08x" % cfo_note)
        # the stages
        s28 = bytearray(s27)
        for lo, hi in PADS.values():
            s28[lo - BASE:hi - BASE] = cfo.fill(lo, hi)
        s29 = bytearray(s28)
        place_code(s29, secs_i)
        apply_sites(s29, syms_i, SITES)
        s30 = bytearray(s29)
        apply_data(s30, syms_i, secs_i)
        built = {"S28": bytes(s28), "S29": bytes(s29), "S30": bytes(s30)}
        prev = s30
        for v in ("S31", "S32", "S33", "S34", "S35", "S36"):
            secs, syms = asm[v]
            for _a, _w, sym, _t in SITES:          # the sites call the same entries
                assert syms[sym] == syms_i[sym]
            im = bytearray(prev)
            place_code(im, secs)
            if v == "S31":
                off = cfo_note - BASE
                assert im[off:off + 4].hex() == "80001f28"
                im[off:off + 4] = syms["STATE"].to_bytes(4, "big")
            if v == "S32":
                apply_offtext(im, syms, secs)
            if v == "S33":
                apply_save(im, syms, secs)
            if v in ("S35", "S36"):
                place_names(im, secs)
                repoint_sites(im, syms, SAVE_SITES + HOLD_SITES)
            if v == "S34":
                place_names(im, secs)
                for name, (lo, _h) in INPLACE.items():
                    assert bytes(im[lo - BASE:lo - BASE + len(secs[name][1])]) == secs[name][1], name
                apply_sites(im, syms, HOLD_SITES)
            for name in (".port_names",) + tuple(INPLACE):   # every later section as placed
                if name in secs:
                    vma, data = secs[name]
                    assert bytes(im[vma - BASE:vma - BASE + len(data)]) == data, (v, name)
            built[v] = bytes(im)
            prev = im
        # load files for EmuPortamento: every byte that differs from stock in the feature's ranges
        out = os.path.join(ROOT, "work", "dt_1.54-port")
        os.makedirs(out, exist_ok=True)
        ranges = [(lo, hi) for lo, hi, _w in touched] + [(cfo_note, cfo_note + 4)]
        for name, im in built.items():
            sy = asm[name][1] if name in asm else syms_i
            with open(os.path.join(out, name + ".load"), "w") as f:
                for lo, hi in ranges:
                    f.write("%08x %s\n" % (lo, im[lo - BASE:hi - BASE].hex()))
                for n, a in sy.items():
                    f.write("sym %s %08x\n" % (n, a))
                f.write("sym cfo_note %08x\n" % cfo_note)
        print("load files:", ", ".join(os.path.join("work", "dt_1.54-port", n + ".load") for n in built))
        feats = merged_features(stock, patch, img, owner, s27, built["S36"])
        s36 = build.sha256_bytes(built["S36"])
        current = patch["result"]["section3_sha256"]
        if later:
            print("S36 section 3 %s; %s; patch.json carries %s after it" % (
                s36, "as recorded (v0.2.1)" if s36 == S36_SHA else "NOT as recorded (%s)" % S36_SHA, ", ".join(later)))
        else:
            same = feats == patch["features"] and s36 == current
            print("S36 section 3 %s; patch.json %s" % (s36, "is up to date" if same else "differs (%s)" % current))
        if not stages:
            print("no stage images built (--stages)")
            return
        dest = os.path.join(ROOT, "out", "1.54", "stages")
        os.makedirs(dest, exist_ok=True)
        syx, tool = build.DEFAULT_SYX, build.DEFAULT_TOOL
        res = {}
        for name in built:
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
                h, size = build.sha256_file(packed), os.path.getsize(packed)
                final = os.path.join(dest, "dt_og_plus_plus_v0.1-%s_%s.syx" % (name, h[:8]))
                os.replace(packed, final)
            finally:
                shutil.rmtree(t2, ignore_errors=True)
            res[name] = (h, size)
            print("%s section 3 %s .syx %s -> %s" % (name, build.sha256_bytes(p3), h, final))
        if write:
            patch["features"] = feats
            patch["result"] = dict(patch["result"], section3_sha256=s36,
                                   syx_sha256_reference=res["S36"][0], syx_size_reference=res["S36"][1])
            with open(cfo.PATCH, "w") as f:
                json.dump(patch, f, indent=1)
                f.write("\n")
            print("wrote", cfo.PATCH)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    print("Nothing was sent to a device.")


if __name__ == "__main__":
    main()
