#!/usr/bin/env python3
"""Check a DT OG++ build in digiemu, the Digitakt emulator, against its OS's stock file.

    python3 scripts/digiemu_check.py <os> BUILD.syx --digiemu DIR [--script NAME|FILE] [--timing]
                                     [--stock STOCK.syx] [--out DIR]

digiemu (docs/digiemu.md) runs the firmware's own code on an emulated ColdFire. Its firmware check,
emu.fwcheck, checks the container, runs the build's bootstrap, boots it to a settled screen under strict
mode (the chip's memory map, instruction set and exceptions), drives it with a key script and compares
every screen and the audio with the stock build driven by the same keys. This script runs that check
with a key script of the OS folder (os/<os>/scripts/digiemu/<NAME>.txt, default "pages"), then judges
the result itself:

  - every stage of the build must pass, except for the known false positives of digiemu's decoder
    (docs/digiemu.md#known-false-positives): a strict-mode violation "not ColdFire ISA_C" at an
    instruction that is EXTB.L (0x49C0..0x49C7 in the build's MAIN OS) is reported as such and not
    counted;
  - the stock build's own violations are digiemu's to mark ("in_stock") and are not counted either;
  - screens and audio that differ from stock are listed, not judged: a build is meant to differ.

--timing adds digiemu's cycle clock: the audio render's worst case against its deadline, for the
build and for stock (several times slower). --digiemu is the digiemu checkout, with its .venv and
patched Unicorn set up as docs/digiemu.md says (or set DIGIEMU). --stock defaults to the OS folder's
own stock file (profile.sh OS_STOCK_SYX_DEFAULT) and must have its hash. The report goes to --out
(default out/<os>/digiemu/<build name>-<script>/): digiemu's report.json, the screens that differ, the
log, and verdict.txt.

Exit status: 0 the build passes, 1 it fails, 2 an error (no such OS folder, wrong stock file, digiemu
missing, the check did not finish). Nothing here talks to a device.
"""
import argparse, glob, hashlib, json, os, re, struct, subprocess, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSIGN_RE = re.compile(r"^\s*(OS_[A-Z0-9_]+)=(.*)$")
EXTB_MASK, EXTB = 0xFFF8, 0x49C0          # EXTB.L Dn: 0100 1001 1100 0nnn


def die(msg, code=2):
    print("error: " + msg, file=sys.stderr)
    sys.exit(code)


def profile(os_id):
    path = os.path.join(ROOT, "os", os_id, "profile.sh")
    if not os.path.isfile(path):
        die("no OS folder os/%s (os/README.md lists them)" % os_id)
    vals = {}
    with open(path) as fh:
        for line in fh:
            m = ASSIGN_RE.match(line)
            if m:
                vals[m.group(1)] = m.group(2).strip().strip('"')
    return vals


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def run_logged(cmd, cwd, log):
    """Run cmd, echo and keep its output; -> exit status."""
    with open(log, "w") as out:
        p = subprocess.Popen(cmd, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                             universal_newlines=True, bufsize=1)
        for line in p.stdout:
            sys.stdout.write(line)
            out.write(line)
        return p.wait()


def main_image(digiemu_py, digiemu, build, out):
    """-> the build's MAIN OS bytes, extracted with digiemu's own extractor."""
    dest = os.path.join(out, "build_sections")
    if not glob.glob(os.path.join(dest, "section_3_*")):
        subprocess.run([digiemu_py, "-m", "emu.extract", build, "-o", dest], cwd=digiemu,
                       stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT, check=False)
    found = glob.glob(os.path.join(dest, "section_3_*"))
    if not found:
        return None
    with open(found[0], "rb") as fh:
        return fh.read()


def judge(report, main, base):
    """-> (passed, lines) for the build, with the known false positives taken out."""
    lines, passed = [], True
    for name, st in report["build"]["stages"].items():
        if st.get("skipped"):
            lines.append("  %-10s skipped: %s" % (name, st["skipped"]))
            continue
        known, real = [], []
        for err in st.get("errors", []):
            m = re.search(r"not ColdFire ISA_C at (0x[0-9a-fA-F]+)", err)
            word = None
            if m and main is not None:
                off = int(m.group(1), 16) - base
                if 0 <= off < len(main) - 1:
                    word = struct.unpack(">H", main[off:off + 2])[0]
            if word is not None and word & EXTB_MASK == EXTB:
                known.append("%s: EXTB.L (0x%04x), digiemu's decoder's known false positive" % (
                    m.group(1), word))
            else:
                real.append(err)
        ok = not real
        passed = passed and ok
        lines.append("  %-10s %s" % (name, "pass" if ok else "FAIL"))
        lines += ["      - " + e for e in real]
        lines += ["      = " + k for k in known]
        lines += ["      ! " + w for w in st.get("warnings", [])]
    return passed, lines


def margin_lines(report):
    lines = []
    for who in ("build", "baseline"):
        st = (report.get(who) or {}).get("stages", {}).get("run", {})
        d = st.get("facts", {}).get("audio_deadlines", {}) if st else {}
        for vec, v in sorted(d.items()):
            if v.get("runs", 0) and v.get("worst_fraction", 0) > 0.1:
                lines.append("  %-8s vector %s: worst %d / mean %.0f / p99 %d of %d cycles, margin %.1f%%, "
                             "late %d" % (who, vec, v["worst_cycles"], v["mean_cycles"],
                                          v["p99_cycles"], v["period_cycles"], 100 * v["margin"],
                                          v.get("late", 0)))
    return lines


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("os", help="the OS folder: os/<os>/")
    ap.add_argument("build", help="the build .syx")
    ap.add_argument("--digiemu", default=os.environ.get("DIGIEMU"), help="the digiemu checkout")
    ap.add_argument("--script", default="pages",
                    help="a key script: a name in os/<os>/scripts/digiemu/ or a file")
    ap.add_argument("--timing", action="store_true", help="also time the audio render")
    ap.add_argument("--stock", help="the stock .syx (default: the OS folder's own)")
    ap.add_argument("--out", help="where the report goes")
    a = ap.parse_args()

    prof = profile(a.os)
    stock = a.stock or os.path.join(ROOT, prof["OS_STOCK_SYX_DEFAULT"])
    if not os.path.isfile(stock):
        die("no stock file %s (--stock)" % stock)
    if sha256(stock) != prof["OS_STOCK_SYX_SHA256"]:
        die("%s is not %s's stock file (its hash differs from profile.sh)" % (stock, prof["OS_LABEL"]))
    if not os.path.isfile(a.build):
        die("no build %s" % a.build)
    script = a.script
    if not os.path.isfile(script):
        script = os.path.join(ROOT, "os", a.os, "scripts", "digiemu", a.script + ".txt")
    if not os.path.isfile(script):
        die("no key script %s" % a.script)
    if not a.digiemu:
        die("where is digiemu? --digiemu DIR or DIGIEMU (docs/digiemu.md)")
    digiemu = os.path.abspath(a.digiemu)
    py = os.path.join(digiemu, ".venv", "bin", "python")
    if not os.path.isfile(os.path.join(digiemu, "emu", "fwcheck.py")) or not os.access(py, os.X_OK):
        die("%s is not a digiemu checkout with its .venv (docs/digiemu.md)" % digiemu)
    stem = os.path.splitext(os.path.basename(a.build))[0]
    name = os.path.splitext(os.path.basename(script))[0]
    out = os.path.abspath(a.out or os.path.join(ROOT, "out", a.os, "digiemu", "%s-%s" % (stem, name)))
    os.makedirs(out, exist_ok=True)

    cmd = [py, "-m", "emu.fwcheck", os.path.abspath(a.build), "--baseline", os.path.abspath(stock),
           "--script", os.path.abspath(script), "--out", out]
    if not a.timing:
        cmd.append("--no-timing")
    print("digiemu check of %s (%s), key script %s%s" % (a.build, prof["OS_LABEL"], name,
                                                        ", timed" if a.timing else ""))
    run_logged(cmd, digiemu, os.path.join(out, "fwcheck.log"))
    rpath = os.path.join(out, "report.json")
    if not os.path.isfile(rpath):
        die("digiemu wrote no report (see %s)" % os.path.join(out, "fwcheck.log"))
    with open(rpath) as fh:
        report = json.load(fh)

    main_os = main_image(py, digiemu, os.path.abspath(a.build), out)
    passed, lines = judge(report, main_os, int(prof["OS_MAIN_BASE"], 16))
    verdict = ["build: %s (judged by scripts/digiemu_check.py)" % ("PASS" if passed else "FAIL")] + lines
    if a.timing:
        verdict += ["audio render (cycle estimate, digiemu's assumptions: docs/digiemu.md):"] + margin_lines(report)
    cmp_ = report.get("compare") or {}
    verdict.append("compared with stock: %s" % ("identical" if cmp_.get("identical") else "the builds differ"))
    for s in cmp_.get("snaps", []):
        verdict.append("    snap %-14s %s" % (s["snap"], "same screen" if s.get("identical") else
                                              "%d pixels differ in %s" % (s.get("pixels_differing", 0),
                                                                         tuple(s.get("box") or ()))))
    if main_os is None:
        verdict.append("! the build's MAIN OS could not be extracted: violations not classified")
    text = "\n".join(verdict) + "\n"
    with open(os.path.join(out, "verdict.txt"), "w") as fh:
        fh.write(text)
    print("\n" + text + "report: %s" % out)
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main())
