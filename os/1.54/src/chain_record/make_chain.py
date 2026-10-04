#!/usr/bin/env python3
"""Chain Recording for OS 1.54: assemble chain_record.s, check it against the stock image, and merge it
into os/1.54/build/patch.json; optionally build the staged test images with build.py's own checks.

    python3 os/1.54/src/chain_record/make_chain.py              # check: is patch.json up to date?
    python3 os/1.54/src/chain_record/make_chain.py --stages     # also build the stage images
    python3 os/1.54/src/chain_record/make_chain.py --stages --write   # and rewrite patch.json

Needs: m68k binutils (M68K_PREFIX, default 'm68k-elf-', as in scripts/common.sh), the stock section 3
from ./scripts/extract.sh 1.54 (work/dt_1.54/section_3_MAIN_OS.bin) and, for --stages, what build.py
needs (the stock .syx in sysex/ and the firmware tool).

The build without Chain Recording is rebuilt from patch.json first (its chain_record runs dropped, the
landing pads it uses given back their earlier contents), and must hash to BASE_SECTION3, so the merge
always starts from the same image. Stages, written to out/1.54/stages/:
  S6   that build + the two pads Chain Recording adds, 0x40124a6c..0x40124b32 and
       0x40128244..0x401282e0, filled with 'clrl %d0 ; rts' (their fill test)
  S7   S6 + every Chain Recording hook, each pad only replaying what its hook displaced (INERT)
  S8   S6 + Chain Recording: the reference build, = patch.json"""
import json, os, shutil, subprocess, sys, tempfile
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", "..", "..", ".."))
sys.path.insert(0, os.path.join(ROOT, "os", "1.54", "build"))
import build  # noqa: E402
PFX = os.environ.get("M68K_PREFIX", "m68k-elf-")
BASE = 0x40000400
PATCH = os.path.join(ROOT, "os", "1.54", "build", "patch.json")
STOCK3 = os.path.join(ROOT, "work", "dt_1.54", "section_3_MAIN_OS.bin")
BASE_SECTION3 = "5a7eb2a4f84bea3a65cac570d9846c5250ae5ab14b0e415580043a079424c451"   # without Chain Recording
FID = "chain_record"
TITLE = ("Chain Recording: the recorder fills a sample chain one slot at a time (encoder D sets the slot "
         "count, armed by the user or re-armed automatically)")

# Each hook: the stock bytes it displaces (objdump of stock 1.54), which must be there.
HOOKS = {
    ".hook_thr":       (0x400767fc, "4ebafe1823c04199f100"),   # jsr %pc@(0x40076616) ; movel %d0,0x4199f100
    ".hook_stop":      (0x4007687a, "4fef00106000fcc0"),       # lea %sp@(16),%sp ; braw 0x40076540
    ".hook_arm":       (0x400768c2, "42b94199f104"),           # clrl 0x4199f104
    ".hook_rec_pos":   (0x400768fa, "42b94199f104"),           # clrl 0x4199f104
    ".hook_rec_len":   (0x40076900, "4ebafd1423c04199f100"),   # jsr %pc@(0x40076616) ; movel %d0,0x4199f100
    ".hook_enc":       (0x400a7f40, "4a0067000184"),           # tstb %d0 ; beqw 0x400a80c8
    ".hook_mem":       (0x400a8e7c, "4eb940076616"),           # jsr 0x40076616
    ".hook_fmt":       (0x400a8f48, "4879401d069648780002"),   # pea 0x401d0696 ; pea 0x2
    ".hook_fmt_pop":   (0x400a8f60, "4fef0020"),               # lea %sp@(32),%sp
    ".hook_armed":     (0x400a9026, "4879401ddbff48780002"),   # pea 0x401ddbff ; pea 0x2
    ".hook_armed_pop": (0x400a9044, "4fef001c"),               # lea %sp@(28),%sp
    ".hook_no":        (0x400a9878, "2f024eb9400c33cc"),       # movel %d2,%sp@- ; jsr 0x400c33cc
}
# Where the code may go, and what the build without Chain Recording holds there.
STL_SPAN = (0x401770a6, 0x40177194)          # its tail holds the span's fill, from the span's start
PADS = {".pad_stl": (0x40177104, 0x40177194, "fill"),
        ".pad_len": (0x400bf1cc, 0x400bf1e8, "stock"),
        ".pad_mem": (0x400c1062, 0x400c1080, "stock"),
        ".pad_flt": (0x40124a6c, 0x40124b32, "stock"),
        ".pad_frm": (0x40128244, 0x401282e0, "stock"),
        ".rodata_fmt": (0x40252c00, 0x40253000, "stock")}
NEWPAD = [(0x40124a6c, 0x40124ac4), (0x40124ac4, 0x40124b32),   # the two pads' member functions
          (0x40128244, 0x40128288), (0x40128288, 0x401282e0)]
VARIANTS = {"inert": ["--defsym", "INERT=1"], "full": []}


def fill(lo, hi):
    n = hi - lo
    return bytes.fromhex("42804e75") * (n // 4) + (bytes.fromhex("4e75") if n % 4 else b"")


def run(*cmd):
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode:
        sys.exit("%s\n%s%s" % (" ".join(cmd), r.stdout, r.stderr))
    return r.stdout


def assemble(variant, tmp):
    o, e = os.path.join(tmp, variant + ".o"), os.path.join(tmp, variant + ".elf")
    run(PFX + "as", "-mcpu=5475", *VARIANTS[variant], "-o", o, os.path.join(HERE, "chain_record.s"))
    run(PFX + "ld", "-T", os.path.join(HERE, "chain_record.ld"), "-o", e, o)
    secs = {}
    for line in run(PFX + "objdump", "-h", e).splitlines():
        p = line.split()
        if len(p) >= 4 and p[1].startswith(".") and int(p[2], 16):
            b = os.path.join(tmp, variant + p[1] + ".bin")
            run(PFX + "objcopy", "-O", "binary", "-j", p[1], e, b)
            secs[p[1]] = (int(p[3], 16), build.read(b))
    return secs


def base_image(stock, patch):
    """The build without Chain Recording, and who owns each changed byte of it."""
    img, owner = bytearray(stock), {}
    for f in patch["features"]:
        if f["id"] == FID:
            continue
        for r in f["runs"]:
            a, d = int(r["addr"], 16), bytes.fromhex(r["bytes"])
            if f["id"] == "pad_fill" and any(lo <= a < hi for lo, hi in NEWPAD):
                continue
            img[a - BASE:a - BASE + len(d)] = d
            for k in range(len(d)):
                owner[a + k] = (f["id"], r.get("kind", "code"))
    for lo, hi, was in PADS.values():
        if was == "fill":
            img[lo - BASE:hi - BASE] = fill(*STL_SPAN)[lo - STL_SPAN[0]:]
            for a in range(lo, hi):
                owner[a] = ("pad_fill", "code")
        else:
            img[lo - BASE:hi - BASE] = stock[lo - BASE:hi - BASE]
            for a in range(lo, hi):
                owner.pop(a, None)
    if build.sha256_bytes(bytes(img)) != BASE_SECTION3:
        sys.exit("the build without Chain Recording does not come back from patch.json (section 3 %s)"
                 % build.sha256_bytes(bytes(img)))
    return img, owner


def place(img, owner, stock, secs):
    out, mine = bytearray(img), {}
    for name, (vma, data) in sorted(secs.items(), key=lambda x: x[1][0]):
        if name in HOOKS:
            a, disp = HOOKS[name]
            disp = bytes.fromhex(disp)
            assert vma == a and len(data) == len(disp), "%s at 0x%08x is %d B" % (name, vma, len(data))
            assert stock[a - BASE:a - BASE + len(disp)] == disp, "%s: the stock bytes differ" % name
            assert all(x not in owner for x in range(a, a + len(disp))), "%s: another feature's run is there" % name
        elif name in PADS:
            lo, hi, _ = PADS[name]
            assert lo <= vma and vma + len(data) <= hi, "%s: 0x%08x +%d B leaves its pad" % (name, vma, len(data))
        else:
            sys.exit("section %s has no place" % name)
        out[vma - BASE:vma - BASE + len(data)] = data
        for k in range(len(data)):
            mine[vma + k] = "data" if name == ".rodata_fmt" else "code"
    return bytes(out), mine


def patch_runs(stock, img, owner, mine, features):
    attr = {}
    for i in range(len(stock)):
        if img[i] != stock[i]:
            a = BASE + i
            attr[a] = (FID, mine[a]) if a in mine else \
                ("pad_fill", "code") if any(lo <= a < hi for lo, hi in NEWPAD) else owner[a]
    runs, cur = {}, None
    for a in sorted(attr):
        fid, kind = attr[a]
        if cur and cur["fid"] == fid and cur["kind"] == kind and cur["end"] == a:
            cur["b"].append(img[a - BASE]); cur["end"] = a + 1
        else:
            cur = {"fid": fid, "kind": kind, "start": a, "end": a + 1, "b": [img[a - BASE]]}
            runs.setdefault(fid, []).append(cur)
    return [{"id": f["id"], "title": f["title"],
             "runs": [{"addr": "0x%08x" % x["start"], "bytes": bytes(x["b"]).hex(), "kind": x["kind"]}
                      for x in runs.get(f["id"], [])]} for f in features]


def build_stage(name, stock, img, out):
    syx, tool = build.DEFAULT_SYX, build.DEFAULT_TOOL
    if build.sha256_file(syx) != build.STOCK_SYX_SHA256:
        sys.exit("the stock .syx is not at %s" % syx)
    sel, i = [], 0
    while i < len(stock):
        if img[i] != stock[i]:
            j = i
            while j < len(stock) and img[j] != stock[j]:
                j += 1
            sel.append((BASE + i, img[i:j], name, "code")); i = j
        else:
            i += 1
    tmp = tempfile.mkdtemp(prefix=".stage-", dir=out)
    try:
        st = build.extract(tool, syx, os.path.join(tmp, "stock"))
        assert build.read(st[3]) == stock
        build.check_runs(sel, len(stock))
        pb = os.path.join(tmp, "s3.bin")
        with open(pb, "wb") as f:
            f.write(img)
        packed = os.path.join(tmp, "packed.syx")
        build.pack(tool, syx, pb, packed)
        build.check_report(tool, packed)
        build.roundtrip(tool, packed, img, st, os.path.join(tmp, "verify"))
        h, size = build.sha256_file(packed), os.path.getsize(packed)
        dest = os.path.join(out, "dt_og_plus_plus_v0.1-%s_%s.syx" % (name, h[:8]))
        os.replace(packed, dest)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    print("%-3s section 3 %s  .syx %s (%d B)\n    -> %s" % (name, build.sha256_bytes(img), h, size, dest))
    return h, size


def main():
    stages, write = "--stages" in sys.argv, "--write" in sys.argv
    if write and not stages:
        sys.exit("--write needs --stages (patch.json carries the .syx hash of the S5 build)")
    stock = build.read(STOCK3)
    patch = json.load(open(PATCH))
    assert build.sha256_bytes(stock) == patch["stock"]["section3_sha256"], "work/dt_1.54 is not the stock section 3"
    img2, owner = base_image(stock, patch)
    img3 = bytearray(img2)
    for lo, hi in NEWPAD:
        assert all(a not in owner for a in range(lo, hi))
        img3[lo - BASE:hi - BASE] = fill(lo, hi)
    imgs = {"S6": bytes(img3)}
    tmp = tempfile.mkdtemp(prefix="chain-")
    try:
        for variant, name in (("inert", "S7"), ("full", "S8")):
            secs = assemble(variant, tmp)
            imgs[name], mine = place(img3, owner, stock, secs)
            used = {n: len(d) for n, (v, d) in secs.items() if n in PADS}
            print("%-3s %-5s: %d sections; pad bytes %s" % (name, variant, len(secs), used))
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    features = [f for f in patch["features"] if f["id"] != FID] + [{"id": FID, "title": TITLE}]
    feats = patch_runs(stock, imgs["S8"], owner, mine, features)
    s8 = build.sha256_bytes(imgs["S8"])
    current = patch["result"]["section3_sha256"]
    same = feats == patch["features"] and s8 == current
    print("S8 section 3 %s; patch.json %s" % (s8, "is up to date" if same else "differs (%s)" % current))
    if stages:
        out = os.path.join(ROOT, "out", "1.54", "stages")
        os.makedirs(out, exist_ok=True)
        res = {n: build_stage(n, stock, imgs[n], out) for n in ("S6", "S7", "S8")}
        if write:
            patch["features"] = feats
            patch["result"] = dict(patch["result"], section3_sha256=s8,
                                   syx_sha256_reference=res["S8"][0], syx_size_reference=res["S8"][1])
            with open(PATCH, "w") as f:
                json.dump(patch, f, indent=1)
                f.write("\n")
            print("wrote", PATCH)
    print("Nothing was sent to a device.")


if __name__ == "__main__":
    main()
