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
  S11  S9 + the CFO oscillator prototype
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
HOOK = (0x40077fc8, "4eb940072478")             # jsr 0x40072478, after the two render lanes
# Candidate pads: extent and member functions (for the fill). Only pads named here may hold code.
PADS = {
    "lz4_stream": [(0x400f77da, 0x400f811e)],    # FUN_400f77da, LZ4's streaming compressor (not a leaf)
}
RODATA = (0x40252724, 0x40252b50)               # the free .rodata padding below the icons


def fill(lo, hi):
    n = hi - lo
    return bytes.fromhex("42804e75") * (n // 4) + (bytes.fromhex("4e75") if n % 4 else b"")


def run(*cmd):
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode:
        sys.exit("%s\n%s%s" % (" ".join(cmd), r.stdout, r.stderr))
    return r.stdout


def assemble(ld, tmp, inert=False):
    run(sys.executable, os.path.join(HERE, "make_waves.py"), os.path.join(tmp, "waves.inc"))
    src = os.path.join(HERE, "cfo.s")
    if inert:
        src = os.path.join(tmp, "inert.s")
        with open(src, "w") as f:
            f.write("\t.section .hook_fill,\"ax\"\n\tjsr\tcfo_pad\n\t.section .cfo_main,\"ax\"\n"
                    "cfo_pad:\n\tjmp\t0x40072478\n")
    o, e = os.path.join(tmp, "cfo.o"), os.path.join(tmp, "cfo.elf")
    run(PFX + "as", "-mcpu=5475", "-I", tmp, "-o", o, src)
    run(PFX + "ld", "-T", ld, "-o", e, o)
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
        if t in "tT":
            syms[n] = int(a, 16)
    return secs, syms


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
        used = []
        for name, (vma, data) in sorted(secs.items(), key=lambda x: x[1][0]):
            end = vma + len(data)
            if name == ".hook_fill":
                assert vma == HOOK[0] and len(data) == 6, "the hook is not 6 B at 0x40077fc8"
                assert stock[vma - BASE:end - BASE] == bytes.fromhex(HOOK[1]), "the hook site is not stock"
                assert not any(a in owner for a in range(vma, end)), "another feature patches the hook site"
            elif name.startswith(".cfo_data"):
                assert RODATA[0] <= vma and end <= RODATA[1], "data outside the .rodata padding"
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
        load = os.path.join(out, os.path.splitext(os.path.basename(ld))[0] + ".load")
        with open(load, "w") as f:
            for name, (vma, data) in secs.items():
                f.write("%08x %s\n" % (vma, data.hex()))
            for n, a in syms.items():
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
        dest = os.path.join(ROOT, "out", "1.54", "stages")
        os.makedirs(dest, exist_ok=True)
        syx, tool = build.DEFAULT_SYX, build.DEFAULT_TOOL
        for name in ("S9", "S10", "S11"):
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
