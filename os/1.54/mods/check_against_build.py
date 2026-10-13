#!/usr/bin/env python3
"""A mod's code and sites against the build in os/1.54/build/patch.json: is the mod the build's feature,
only placed elsewhere?

    python3 os/1.54/mods/check_against_build.py os/1.54/mods/<mod> [--prefix m68k-linux-gnu-]
        [--include <elekloader>/elekloader/sdk/include]

The mod's mod.json names, under "dtogpp_check":
  "features"  the build's features (patch.json ids) the mod carries;
  "place"     where the build has each of the mod's sections: {".text.x": "0x40177104", ".bss": ...};
  "differ"    optional: [{"addr", "len", "why"}], bytes where the mod is meant to differ from the build, or
              bytes of the features it leaves out;
  "new"       optional: sections with no counterpart in the build (linked apart, not compared).
The mod's sources are assembled as elekloader's SDK assembles them (-mcpu=54455; --include gives the SDK's
include folder, for a mod that includes digitakt-mk1/core3.inc) and linked with each section at its build
address, what the mod imports from other mods as 0; its mod.json sites are applied with their targets at those addresses
(jsr/jmp: the opcode, the target and nops to the stock length; keep2: the stock opcode word and the
target; ptr: the target; bytes: the bytes). Then, against the build (stock section 3 with every feature of
patch.json applied):
  1. every byte the mod places (sections other than .bss, and sites) is the build's byte there;
  2. every byte patch.json lists under the named features is one the mod places.
Bytes in "differ" are left out of both, and printed. Exit status 0 when both hold.

Needs the stock section 3 (work/dt_1.54/section_3_MAIN_OS.bin, from ./scripts/extract.sh 1.54) and the m68k
binutils. Nothing is written outside a temporary folder."""
import json, os, shutil, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
STOCK3 = os.path.join(ROOT, "work", "dt_1.54", "section_3_MAIN_OS.bin")
PATCH = os.path.join(ROOT, "os", "1.54", "build", "patch.json")
BASE = 0x40000400
ASFLAGS = ["-mcpu=54455"]                 # elekloader's toolchain for the Digitakt mk1
OPS = {"jsr": "4eb9", "jmp": "4ef9"}


def run(cmd):
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode:
        sys.exit("%s\n%s%s" % (" ".join(cmd), r.stdout, r.stderr))
    return r.stdout


def num(x):
    return int(x, 16) if isinstance(x, str) else int(x)


def assemble(mdir, mod, place, prefix, include):
    """The mod's sources, each section linked at its place. -> ({section: (address, bytes)} without .bss,
    {symbol: address})."""
    secs, syms = {}, {}
    tmp = tempfile.mkdtemp(prefix="modcheck-")
    try:
        objs = []
        inc = ["-I", include] if include else []
        for src in mod["sources"]:
            o = os.path.join(tmp, os.path.basename(src) + ".o")
            run([prefix + "as"] + ASFLAGS + ["-I", mdir] + inc + ["-o", o, os.path.join(mdir, src)])
            objs.append(o)
        ld = os.path.join(tmp, "place.ld")
        with open(ld, "w") as fh:
            fh.write("SECTIONS\n{\n")
            for sec, at in sorted(place.items(), key=lambda x: x[1]):
                fh.write("    %s 0x%x %s: { *(%s) }\n" % (sec, at, "(NOLOAD) " if sec == ".bss" else "", sec))
            fh.write("    /DISCARD/ : { *(.comment) *(.note*) *(.gnu.attributes) }\n}\n")
        elf = os.path.join(tmp, "mod.elf")
        run([prefix + "ld", "-T", ld, "--unresolved-symbols=ignore-all", "-o", elf] + objs)
        for line in run([prefix + "objdump", "-h", elf]).splitlines():
            p = line.split()
            if len(p) >= 4 and p[1].startswith(".") and int(p[2], 16):
                if p[1] not in place:
                    sys.exit("section %s has no place in dtogpp_check" % p[1])
                if p[1] == ".bss":
                    continue
                b = os.path.join(tmp, p[1] + ".bin")
                run([prefix + "objcopy", "-O", "binary", "-j", p[1], elf, b])
                secs[p[1]] = (int(p[3], 16), open(b, "rb").read())
        for line in run([prefix + "nm", elf]).splitlines():
            p = line.split()
            if len(p) == 3 and p[1] not in "Uw":
                syms[p[2]] = int(p[0], 16)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    return secs, syms


def main():
    args = sys.argv[1:]
    prefix = os.environ.get("M68K_PREFIX", "m68k-elf-")
    if "--prefix" in args:
        i = args.index("--prefix")
        prefix = args[i + 1]
        del args[i:i + 2]
    include = None
    if "--include" in args:
        i = args.index("--include")
        include = args[i + 1]
        del args[i:i + 2]
    if len(args) != 1:
        sys.exit(__doc__)
    mdir = os.path.abspath(args[0])
    mod = json.load(open(os.path.join(mdir, "mod.json")))
    cfg = mod.get("dtogpp_check")
    if not cfg:
        sys.exit("%s: mod.json has no dtogpp_check" % mdir)
    place = {k: num(v) for k, v in cfg["place"].items()}
    new_secs = cfg.get("new", [])
    for k, sec in enumerate(new_secs):      # sections with no counterpart in the build: linked apart
        place[sec] = 0x50000000 + 0x10000 * k
    differ = set()
    for d in cfg.get("differ", []):
        differ.update(range(num(d["addr"]), num(d["addr"]) + d["len"]))

    stock = open(STOCK3, "rb").read()
    patch = json.load(open(PATCH))
    build = bytearray(stock)
    owner = {}
    for f in patch["features"]:
        for r in f["runs"]:
            a, b = int(r["addr"], 16), bytes.fromhex(r["bytes"])
            build[a - BASE:a - BASE + len(b)] = b
            for k in range(len(b)):
                owner[a + k] = f["id"]

    secs, syms = assemble(mdir, mod, place, prefix, include) if mod.get("sources") else ({}, {})

    recon = {}
    def put(a, data, what):
        for k, v in enumerate(data):
            if a + k in recon and recon[a + k] != v:
                sys.exit("0x%08x placed twice (%s)" % (a + k, what))
            recon[a + k] = v
    for name, (vma, data) in secs.items():
        if name not in new_secs:
            put(vma, data, name)
    for s in mod.get("sites", []):
        a, st, op = num(s["addr"]), s["stock"], s["op"]
        n = len(st) // 2
        if stock[a - BASE:a - BASE + n].hex() != st:
            sys.exit("site 0x%08x: the stock bytes are not %s" % (a, st))
        tgt = (syms[s["target"]] + int(s.get("addend", 0))) & 0xffffffff if "target" in s else None
        if op in OPS:
            new = OPS[op] + "%08x" % tgt + "4e71" * ((n - 6) // 2)
        elif op == "keep2":
            new = st[:4] + "%08x" % tgt
        elif op == "ptr":
            new = "%08x" % tgt
        elif op == "bytes":
            new = s["new"]
        else:
            sys.exit("site 0x%08x: op %s" % (a, op))
        put(a, bytes.fromhex(new), "site 0x%08x" % a)

    feats = ", ".join(cfg["features"]) or "build (no feature carried whole)"
    print("%s: %d B of code and data in %d sections (%d B new), %d sites; against patch.json's %s"
          % (mod["id"], sum(len(d) for n, d in secs.values()), len(secs),
             sum(len(d) for n, (_v, d) in secs.items() if n in new_secs), len(mod.get("sites", [])), feats))
    bad = [a for a in sorted(recon) if a not in differ and build[a - BASE] != recon[a]]
    uncovered = [a for a in sorted(owner) if owner[a] in cfg["features"] and a not in recon and a not in differ]
    for d in cfg.get("differ", []):
        a, n = num(d["addr"]), d["len"]
        print("  meant to differ 0x%08x +%d: the mod %s, the build %s (%s)" % (
            a, n, bytes(recon.get(a + k, stock[a - BASE + k]) for k in range(n)).hex(),
            build[a - BASE:a - BASE + n].hex(), d["why"]))

    def runs(addrs):
        out, cur = [], None
        for a in addrs:
            if cur and cur[1] == a:
                cur[1] += 1
            else:
                cur = [a, a + 1]
                out.append(cur)
        return out
    for lo, hi in runs(bad):
        print("  DIFFERS 0x%08x..0x%08x: the mod %s, the build %s (%s)" % (
            lo, hi, bytes(recon[a] for a in range(lo, hi)).hex(), build[lo - BASE:hi - BASE].hex(),
            ", ".join(sorted({owner.get(a, "stock") for a in range(lo, hi)}))))
    for lo, hi in runs(uncovered):
        print("  NOT IN THE MOD 0x%08x..0x%08x (%s): the build %s" % (
            lo, hi, owner[lo], build[lo - BASE:hi - BASE].hex()))
    if bad or uncovered:
        print("FAIL: %d B differ, %d B of the features not in the mod" % (len(bad), len(uncovered)))
        sys.exit(1)
    if cfg["features"]:
        print("OK: every byte the mod places is the build's, and every byte of %s is one it places" % feats)
    else:
        print("OK: every byte the mod places where the build has its code is the build's")


if __name__ == "__main__":
    main()
