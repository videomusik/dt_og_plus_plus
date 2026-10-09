#!/usr/bin/env python3
"""Portamento for OS 1.54, on top of the CFO oscillator's last stage (S27): assemble port.s with
port.ld, check every site against the stock image, the current build and S27, and write the emulator's
load files; with --stages, build the stage images.

    python3 os/1.54/src/portamento/make_port.py            # check, write the load files
    python3 os/1.54/src/portamento/make_port.py --stages   # also build stage images

Needs what make_cfo.py needs (m68k binutils, M68K_PREFIX; the stock section 3 from
./scripts/extract.sh 1.54; for --stages the stock .syx and the firmware tool).

S27 is rebuilt with make_cfo.py's own routines and must give its recorded section-3 hash. Stages, written
to out/1.54/stages/ (only with --stages):
  S28  S27 + the two new pads filled with 'clrl %d0 ; rts' (their fill test)
  S29  S28 + both hooks, each pad only replaying the instructions its hook replaced
  S30  S29 + PORT and LEG on the TRIG page's knobs G and H: descriptor rows 4 and 5 (until now unused
       "Error" rows) as sound parameters in the free value slots 46 and 47, their names, their display
       objects, and the TRIG page's layout; the hooks still inert, so nothing glides yet
  S31  S30 + the glide: port_on and port_glide, and the CFO oscillator's note read pointed at the glided
       note
patch.json is not changed: the feature is a prototype."""
import os, shutil, subprocess, sys, tempfile
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", "..", ".."))
sys.path.insert(0, os.path.join(ROOT, "os", "1.54", "build"))
sys.path.insert(0, os.path.join(ROOT, "os", "1.54", "src", "cfo_oscillator"))
import build  # noqa: E402
import make_cfo as cfo  # noqa: E402
PFX = os.environ.get("M68K_PREFIX", "m68k-elf-")
BASE = 0x40000400
CODE_END = 0x4017cc64                   # the end of the code (the branch scan's range)
S27_SHA = "703cedf780d68ee9a4187956d6a5b87d46d284804fc962fa2950f0ae4c189ff0"   # S27's section 3
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
# S31: the CFO oscillator's note read (lea NOTES,%a0 then the indexed movel into %d2), in its pad.
CFO_PAD = (0x400f77da, 0x400f811e)
CFO_NOTE = "41f980001f28" "24300c00"


def run(*cmd):
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode:
        sys.exit("%s\n%s%s" % (" ".join(cmd), r.stdout, r.stderr))
    return r.stdout


def assemble(tmp, inert):
    src = os.path.join(HERE, "port.s")
    o, e = os.path.join(tmp, "port.o"), os.path.join(tmp, "port.elf")
    run(PFX + "as", "-mcpu=5475", *(["--defsym", "INERT=1"] if inert else []), "-o", o, src)
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
    import re
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


def apply_sites(im, syms):
    for addr, want, sym, tail in SITES:
        put(im, addr, want, "4eb9%08x" % syms[sym] + tail, sym)


def apply_data(im, syms, secs):
    for i in ROWS:
        r = DESC + 0x34 * i
        put(im, r, ROW_STOCK[i].replace(" ", ""), row_bytes(i, syms).hex(), "descriptor row %d" % i)
    for addr, want, new, what in DOBJ + LAYOUT:
        put(im, addr, want, new, what)
    vma, data = secs[".port_names"]
    im[vma - BASE:vma - BASE + len(data)] = data


def place_code(im, secs):
    for name, (lo, hi) in PADS.items():
        vma, data = secs[name]
        assert vma == lo and vma + len(data) <= hi, "%s does not fit its pad" % name
        im[lo - BASE:hi - BASE] = cfo.fill(lo, hi)
        im[vma - BASE:vma - BASE + len(data)] = data


def main():
    stages = "--stages" in sys.argv
    stock = build.read(cfo.STOCK3)
    patch, runs = build.load_patch(cfo.PATCH)
    img = bytearray(stock)
    owner = set()
    for a, d, _f, _k in runs:
        img[a - BASE:a - BASE + len(d)] = d
        owner.update(range(a, a + len(d)))
    if build.sha256_bytes(bytes(img)) != patch["result"]["section3_sha256"]:
        sys.exit("patch.json does not reproduce its own section-3 hash")
    tmp = tempfile.mkdtemp(prefix="port-")
    try:
        s27, h27 = s27_image(stock, img, tmp)
        print("S27 rebuilt: section 3 %s" % h27)
        secs_i, syms_i = assemble(tmp, inert=True)
        secs_f, syms_f = assemble(tmp, inert=False)
        # every byte this feature writes: stock in the stock image, untouched by the build and by S27
        touched = []
        for name, (lo, hi) in PADS.items():
            touched.append((lo, hi, name))
        for addr, want, sym, _t in SITES:
            touched.append((addr, addr + len(want) // 2, "site " + sym))
        for i in ROWS:
            touched.append((DESC + 0x34 * i, DESC + 0x34 * (i + 1), "row %d" % i))
        for addr, want, _n, what in DOBJ + LAYOUT:
            touched.append((addr, addr + len(want) // 2, what))
        vn, dn = secs_f[".port_names"]
        assert RODATA2[0] <= vn and vn + len(dn) <= RODATA2[1], ".port_names outside the .rodata padding"
        assert stock[vn - BASE:vn + len(dn) - BASE] == bytes(len(dn)), ".rodata padding not zero in stock"
        touched.append((vn, vn + len(dn), ".port_names"))
        for lo, hi, what in touched:
            assert not any(a in owner for a in range(lo, hi)), "the build patches %s" % what
            assert s27[lo - BASE:hi - BASE] == stock[lo - BASE:hi - BASE], "S27 changes %s" % what
            print("%-60s 0x%08x..0x%08x" % (what, lo, hi))
        for name in PADS:
            for s in (secs_i, secs_f):
                assert s[name][0] == PADS[name][0] and s[name][0] + len(s[name][1]) <= PADS[name][1], name
            print("%-12s inert %d B, feature %d B of %d" % (name, len(secs_i[name][1]), len(secs_f[name][1]),
                                                          PADS[name][1] - PADS[name][0]))
        bad = branch_targets_inside([(a, len(w) // 2) for a, w, _s, _t in SITES])
        assert not bad, "branches into a hook site: %s" % bad
        print("no instruction branches into a hook site's replaced bytes")
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
        apply_sites(s29, syms_i)
        s30 = bytearray(s29)
        apply_data(s30, syms_i, secs_i)
        s31 = bytearray(s30)
        place_code(s31, secs_f)
        for addr, _w, sym, tail in SITES:          # the sites call the same entries
            assert syms_f[sym] == syms_i[sym]
        off = cfo_note - BASE
        assert s31[off:off + 4].hex() == "80001f28"
        s31[off:off + 4] = syms_f["STATE"].to_bytes(4, "big")
        built = {"S28": bytes(s28), "S29": bytes(s29), "S30": bytes(s30), "S31": bytes(s31)}
        # load files for EmuPortamento: every byte that differs from stock in the feature's ranges
        out = os.path.join(ROOT, "work", "dt_1.54-port")
        os.makedirs(out, exist_ok=True)
        ranges = [(lo, hi) for lo, hi, _w in touched] + [(cfo_note, cfo_note + 4)]
        for name, im in built.items():
            sy = syms_f if name == "S31" else syms_i
            with open(os.path.join(out, name + ".load"), "w") as f:
                for lo, hi in ranges:
                    f.write("%08x %s\n" % (lo, im[lo - BASE:hi - BASE].hex()))
                for n, a in sy.items():
                    f.write("sym %s %08x\n" % (n, a))
                f.write("sym cfo_note %08x\n" % cfo_note)
        print("load files:", ", ".join(os.path.join("work", "dt_1.54-port", n + ".load") for n in built))
        if not stages:
            print("no stage images built (--stages)")
            return
        dest = os.path.join(ROOT, "out", "1.54", "stages")
        os.makedirs(dest, exist_ok=True)
        syx, tool = build.DEFAULT_SYX, build.DEFAULT_TOOL
        for name in ("S28", "S29", "S30", "S31"):
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
