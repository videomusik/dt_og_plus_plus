#!/usr/bin/env python3
"""Generate docs/patch_listing.md: every run of build/patch.json, by feature.

    python3 build/make_listing.py [--syx sysex/Digitakt_OS1.52A.syx]
                                  [--tool tool/bin/elektron-firmware-tool-capped]
                                  [--objdump m68k-elf-objdump] [--out docs/patch_listing.md]
                                  [--work work]

Needs the same inputs as build/build.py (your stock .syx and the firmware tool) plus GNU binutils
for ColdFire (m68k-elf-objdump). The stock file is checked by SHA-256 and patched in memory exactly
as build.py does; nothing is packed and nothing is written except the listing and a temporary
folder inside --work, which is removed again.

Code runs are disassembled from a linear sweep of each code window of the patched section 3, so a
run that starts or ends inside an instruction still shows the whole instruction. Where a jump,
branch, call or address operand of patched code points into patched bytes that the sweep did not
reach as an instruction start, the sweep is restarted at that target. Data runs (inside
.rodata/.data, or data the patch places inside a code pad) are shown as hex + ASCII, patched
bytes only. The output is deterministic for a given patch.json and objdump version.
"""
import argparse, bisect, os, re, shutil, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)
import build  # noqa: E402  (build/build.py: the same checks and the same patching)

# Code windows of section 3 (load addresses, end exclusive); everything else is .rodata/.data.
CODE_WINDOWS = [(0x400004b2, 0x40162748), (0x40210e4a, 0x40211ef2)]
LISTING = os.path.join(ROOT, "docs", "patch_listing.md")
DATA_GROUP_GAP = 16      # data runs of one feature closer than this share one dump

LINE = re.compile(r"^\s*([0-9a-f]+):\t([0-9a-f ]+?)\s*(?:\t(.*))?$")
FLOW = re.compile(r"^(?:j[a-z]+|b(?!tst|set|clr|chg)[a-z]+|lea|pea)\b")
ADDR = re.compile(r"0x([0-9a-f]{8})\b")


def objdump_range(objdump, binfile, lo, hi):
    """Disassemble [lo, hi): list of (addr, nbytes, words, text)."""
    cmd = [objdump, "-D", "-b", "binary", "-m", "m68k:cfv4e", "--adjust-vma=0x%x" % build.LOAD_BASE,
           "--start-address=0x%x" % lo, "--stop-address=0x%x" % hi, binfile]
    try:
        cp = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True)
    except OSError as e:
        raise build.BuildError("cannot run %s: %s" % (objdump, e))
    if cp.returncode != 0:
        raise build.BuildError("%s failed:\n%s" % (objdump, cp.stderr))
    out = []
    for ln in cp.stdout.splitlines():
        m = LINE.match(ln)
        if not m:
            continue
        addr = int(m.group(1), 16)
        words = m.group(2).split()
        n = sum(len(w) // 2 for w in words)
        if m.group(3) is None and out and out[-1][0] + out[-1][1] == addr:
            a, nb, ws, txt = out[-1]           # continuation line of a long instruction
            out[-1] = (a, nb + n, ws + words, txt)
            continue
        out.append((addr, n, words, (m.group(3) or "").strip()))
    return out


class Listing:
    def __init__(self, objdump, binfile, final, changed):
        self.objdump, self.binfile, self.final, self.changed = objdump, binfile, final, changed
        self.ins = {}
        for lo, hi in CODE_WINDOWS:
            for x in objdump_range(objdump, binfile, lo, hi):
                self.ins[x[0]] = x
        self.restarts = []
        self._index()

    def _index(self):
        self.starts = sorted(self.ins)

    def covering(self, lo, hi):
        i = max(bisect.bisect_right(self.starts, lo) - 1, 0)
        out = []
        while i < len(self.starts) and self.starts[i] < hi:
            x = self.ins[self.starts[i]]
            if x[0] + x[1] > lo:
                out.append(x)
            i += 1
        return out

    def _window(self, a):
        return next(((lo, hi) for lo, hi in CODE_WINDOWS if lo <= a < hi), None)

    def restart_at(self, t):
        """Make t an instruction start: re-disassemble from t until the old sweep agrees again."""
        win = self._window(t)
        new = objdump_range(self.objdump, self.binfile, t, min(t + 1024, win[1]))
        old_starts = set(self.starts)
        keep, stop = [], None
        for x in new:
            if x[0] > t and x[0] in old_starts:
                stop = x[0]
                break
            keep.append(x)
        if stop is None:
            stop = keep[-1][0] + keep[-1][1] if keep else t
        for a in [a for a in self.starts if a < stop and a + self.ins[a][1] > t]:
            n = self.ins.pop(a)[1]
            for b in range(a, min(a + n, t), 2):         # bytes before t: shown as data words
                w = "%02x%02x" % (self.final[b - build.LOAD_BASE], self.final[b + 1 - build.LOAD_BASE])
                self.ins[b] = (b, 2, [w], ".short 0x%s" % w)
        for x in keep:
            self.ins[x[0]] = x
        self._index()
        self.restarts.append(t)

    def fix_entry_points(self):
        """Restart the sweep at every flow/address target of patched code that lands inside patched
        bytes but is not an instruction start of the sweep. Repeats until nothing changes."""
        for _ in range(50):
            todo = set()
            for a in self.starts:
                n, t = self.ins[a][1], self.ins[a][3]
                if not any(o in self.changed for o in range(a, a + n)) or not FLOW.match(t):
                    continue
                for h in ADDR.findall(t):
                    tgt = int(h, 16)
                    if tgt % 2 == 0 and tgt in self.changed and self._window(tgt) and tgt not in self.ins:
                        todo.add(tgt)
            if not todo:
                return
            for tgt in sorted(todo):
                if tgt not in self.ins:
                    self.restart_at(tgt)
        raise build.BuildError("disassembly did not settle")


def dump_lines(runs, final):
    """Hex + ASCII of the patched bytes of a group of data runs, 16 per line; '--' = unpatched."""
    patched = {}
    for addr, data in runs:
        for i, b in enumerate(data):
            patched[addr + i] = b
    lo = min(patched) & ~15
    hi = max(patched) + 1
    out = []
    for base in range(lo, hi, 16):
        cells = [patched.get(a) for a in range(base, base + 16)]
        if all(c is None for c in cells):
            continue
        hx = " ".join("--" if c is None else "%02x" % c for c in cells)
        asc = "".join(" " if c is None else (chr(c) if 0x20 <= c < 0x7f else ".") for c in cells)
        out.append("%08x  %s  |%s|" % (base, hx, asc))
    return out


def objdump_version(objdump):
    try:
        cp = subprocess.run([objdump, "--version"], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            universal_newlines=True)
    except OSError as e:
        raise build.BuildError("cannot run %s: %s" % (objdump, e))
    return cp.stdout.splitlines()[0].strip() if cp.stdout else "unknown"


def generate(syx, tool, objdump, work):
    h = build.sha256_file(syx)
    if h != build.STOCK_SYX_SHA256:
        raise build.BuildError("refusing %s: SHA-256 %s is not the stock Digitakt OS 1.52A file" % (syx, h))
    ver = objdump_version(objdump)
    patch, runs = build.load_patch()
    changed = set()
    for addr, data, _f, _k in runs:
        changed.update(range(addr, addr + len(data)))
    os.makedirs(work, exist_ok=True)
    tmp = tempfile.mkdtemp(prefix=".listing-", dir=work)
    try:
        sections = build.extract(tool, syx, os.path.join(tmp, "stock"))
        final = build.patched_section3(build.read(sections[3]), patch, runs)
        binfile = os.path.join(tmp, "section_3_patched.bin")
        with open(binfile, "wb") as f:
            f.write(final)
        lst = Listing(objdump, binfile, final, changed)
        lst.fix_entry_points()
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    L = []
    L.append("# Patch listing")
    L.append("")
    L.append("Generated by `build/make_listing.py` from `build/patch.json`; do not edit by hand. "
             "It needs only `patch.json`, your stock `sysex/Digitakt_OS1.52A.syx`, the firmware tool "
             "and GNU binutils for ColdFire. To regenerate it:")
    L.append("")
    L.append("```")
    L.append("python3 build/make_listing.py")
    L.append("```")
    L.append("")
    L.append("Addresses are load addresses: section 3 (MAIN OS) loads at 0x%08x, so file offset = "
             "address - 0x%08x." % (build.LOAD_BASE, build.LOAD_BASE))
    L.append("")
    L.append("- **Code** is disassembled as ColdFire (`m68k-elf-objdump -m m68k:cfv4e`) from a linear sweep "
             "of the patched section 3, so a run that starts or ends inside an instruction still shows the "
             "whole instruction. Where patched code jumps, branches, calls or points into patched bytes the "
             "sweep did not reach as an instruction start, the sweep is restarted there "
             "(%s). `+` marks an instruction whose bytes are all patched, `~` one that "
             "also contains unpatched bytes. Runs of one feature whose instructions touch are shown together."
             % (", ".join("0x%08x" % t for t in lst.restarts) or "none needed"))
    L.append("- **Data** (icons, tables, strings) is shown as hex and ASCII, patched bytes only; `--` is an "
             "unpatched byte. Data runs of one feature less than %d bytes apart share one dump." % DATA_GROUP_GAP)
    L.append("- The `+`/`~` marks count every patched byte, whichever feature it belongs to.")
    L.append("")
    L.append("Section 3: stock `%s` -> patched `%s`." % (patch["stock"]["section3_sha256"],
                                                          patch["result"]["section3_sha256"]))
    L.append("Disassembler: %s." % ver)
    L.append("")
    L.append("| feature | runs | bytes | |")
    L.append("|---|---:|---:|---|")
    total_r = total_b = 0
    for ft in patch["features"]:
        nb = sum(len(r["bytes"]) // 2 for r in ft["runs"])
        total_r += len(ft["runs"])
        total_b += nb
        L.append("| [`%s`](#%s) | %d | %d | %s |" % (ft["id"], ft["id"].replace("_", "-"), len(ft["runs"]), nb,
                                                    ft["title"]))
    L.append("| **total** | %d | %d | |" % (total_r, total_b))
    L.append("")

    for ft in patch["features"]:
        L.append('<a id="%s"></a>' % ft["id"].replace("_", "-"))
        L.append("")
        L.append("## %s" % ft["id"])
        L.append("")
        L.append("%s. %d runs, %d bytes." % (ft["title"], len(ft["runs"]),
                                             sum(len(r["bytes"]) // 2 for r in ft["runs"])))
        L.append("")
        rr = sorted(((int(r["addr"], 16), bytes.fromhex(r["bytes"]), r.get("kind", "code")) for r in ft["runs"]),
                    key=lambda x: x[0])
        blocks = []
        for addr, data, kind in rr:
            last = blocks[-1] if blocks else None
            if kind == "code":
                ins = lst.covering(addr, addr + len(data))
                if last and last["kind"] == "code" and last["ins"] and ins and \
                        ins[0][0] <= last["ins"][-1][0] + last["ins"][-1][1]:
                    last["runs"].append((addr, data))
                    last["ins"].extend(x for x in ins if x[0] > last["ins"][-1][0])
                    continue
                blocks.append({"kind": "code", "runs": [(addr, data)], "ins": ins})
            else:
                if last and last["kind"] == "data" and \
                        addr - (last["runs"][-1][0] + len(last["runs"][-1][1])) < DATA_GROUP_GAP:
                    last["runs"].append((addr, data))
                    continue
                blocks.append({"kind": "data", "runs": [(addr, data)]})
        for b in blocks:
            lo = b["runs"][0][0]
            hi = b["runs"][-1][0] + len(b["runs"][-1][1])
            nbytes = sum(len(d) for _a, d in b["runs"])
            if len(b["runs"]) == 1:
                L.append("### 0x%08x, %d B (%s)" % (lo, nbytes, b["kind"]))
            else:
                L.append("### 0x%08x-0x%08x, %d runs, %d B (%s)" % (lo, hi, len(b["runs"]), nbytes, b["kind"]))
                L.append("")
                L.append("Runs: " + ", ".join("0x%08x +%d" % (a, len(d)) for a, d in b["runs"]) + ".")
            L.append("")
            L.append("```")
            if b["kind"] == "code":
                for a, n, w, t in b["ins"]:
                    k = sum(1 for o in range(a, a + n) if o in changed)
                    mark = "+" if k == n else ("~" if k else " ")
                    L.append("%s %08x:  %-20s %s" % (mark, a, " ".join(w), t))
            else:
                L.extend(dump_lines(b["runs"], final))
            L.append("```")
            L.append("")
    return "\n".join(L).rstrip() + "\n"


def main(argv=None):
    ap = argparse.ArgumentParser(description="Generate docs/patch_listing.md from build/patch.json")
    ap.add_argument("--syx", default=build.DEFAULT_SYX)
    ap.add_argument("--tool", default=build.DEFAULT_TOOL)
    ap.add_argument("--objdump", default="m68k-elf-objdump")
    ap.add_argument("--out", default=LISTING)
    ap.add_argument("--work", default=os.path.join(ROOT, "work"), help="folder for the temporary extract")
    a = ap.parse_args(argv)
    try:
        text = generate(os.path.abspath(a.syx), os.path.abspath(a.tool), a.objdump, os.path.abspath(a.work))
    except build.BuildError as e:
        print("error: %s" % e, file=sys.stderr)
        return 1
    with open(a.out, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    print("wrote %s (%d lines)" % (a.out, text.count("\n")))
    return 0


if __name__ == "__main__":
    sys.exit(main())
