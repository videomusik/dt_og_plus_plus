#!/usr/bin/env python3
"""Build DT OG++ for the Digitakt (original model) from your own stock OS 1.54 file.

    python3 os/1.54/build/build.py [--syx sysex/Digitakt_OS1.54.syx]
                                   [--tool tool/bin/elektron-firmware-tool-capped]
                                   [--out out/1.54]

Defaults are relative to the repository root (three folders above this build/ folder); paths you pass
on the command line are taken relative to your current directory.

Steps, each one checked:
  1. The .syx must be the unmodified Elektron file, by SHA-256. There is no override.
  2. Extract it with the firmware tool; section 3 (MAIN OS) must hash to the stock value.
  3. Apply os/1.54/build/patch.json. Every run must lie inside section 3 and outside the protected
     ranges (the OS-update, flash, SysEx-receive and storage-transfer code), no two runs may
     overlap, and the result must hash to the published section-3 hash.
  4. Pack the patched section 3 into a copy of the stock container:  tool -i <stock> -c 3 ...
  5. The tool must report "checksums : ok" for the new file.
  6. Extract the new file again: section 3 must equal the patched bytes, and sections 2, 4, 5 and 8
     must be byte-identical to the stock ones.
  7. Only then is the file renamed to  <out>/dt_og_plus_plus_v0.2.2_<first 8 hex of its SHA-256>.syx .

On any failure the script exits non-zero and removes its temporary folder inside <out>, so no
file with the final name is left behind. Nothing here talks to a device; flashing is up to you.
Python 3 standard library only.
"""
import argparse, hashlib, json, os, re, shutil, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(HERE)))
PATCH_JSON = os.path.join(HERE, "patch.json")

DEFAULT_SYX = os.path.join(ROOT, "sysex", "Digitakt_OS1.54.syx")
DEFAULT_TOOL = os.path.join(ROOT, "tool", "bin", "elektron-firmware-tool-capped")
DEFAULT_OUT = os.path.join(ROOT, "out", "1.54")

PATCH_FORMAT = "dt-og-plus-plus-patch"
PATCH_FORMAT_VERSION = 1
LOAD_BASE = 0x40000400                  # section 3 file offset = load address - LOAD_BASE

# The only input this build accepts: Elektron's Digitakt OS 1.54 update file, unmodified.
STOCK_SYX_SHA256 = "f78ba80fa7b1da5fb0e1ff61ad61e9e71aafe79f4364fc49679f3651353e3cf6"
STOCK_SYX_SIZE = 1423776

# Load-address ranges [start, end) that no patch may touch. They hold the code that receives and
# flashes an OS update, so keeping them stock keeps the way back to stock firmware open.
PROTECTED = [
    (0x400e8c68, 0x400ea596, "NOR flash driver (DSPI), with its timer wait/ISR and driver getters"),
    (0x40068e54, 0x40068e86, "DSPI init"),
    (0x40066b26, 0x4006735e, "mid-level flash operations"),
    (0x40067782, 0x40068d8a, "OS-update transfer task"),
    (0x40081770, 0x40081794, "DigitaktSysex destructor"),
    (0x40059810, 0x40059830, "SysEx receive menu"),
    (0x4005ac40, 0x4005b1ac, "SysEx receive menu"),
    (0x4005b1ac, 0x4005b2cc, "SysEx receive menu (with its entry thunk)"),
    (0x40151454, 0x40151504, "SysEx receive menu (with its entry thunks)"),
    (0x40151504, 0x40151544, "SysEx receive menu (with its entry thunks)"),
    (0x4008d6c2, 0x4008dbce, "storage bulk transfer"),
    # Code the update path uses outside the ranges above (reachable from the transfer task, its launcher or
    # storage transfer; some of it is shared with normal operation, which is harmless to protect).
    (0x4006685c, 0x40066b26, "OS-update task helpers, incl. the shared CRC-32 at 0x40066910"),
    (0x400673be, 0x40067782, "OS-update task helpers, incl. the sample-verification flash-write step"),
    (0x40068d8a, 0x40068dd4, "OS-update transfer task launcher"),
    (0x40068e06, 0x40068e36, "root task (starts the init task)"),
    (0x40068e86, 0x40068ea8, "OS-update task helper"),
    (0x400692d2, 0x400692fe, "OS init task: update-mode branch and launcher call"),
    (0x40069636, 0x40069b04, "sample-verification helper"),
    (0x40069d88, 0x4006aada, "OS-update helper block (result views, erase job, factory tests)"),
    (0x40081794, 0x400817ae, "DigitaktSysex deleting destructor"),
    (0x4008d4c4, 0x4008d6c2, "storage bulk transfer helpers and reset hook"),
]
if not PROTECTED:
    sys.exit("os/1.54/build/build.py: PROTECTED is empty; refusing to build")
# Sections other than 3 must come back byte-identical. Matched by number prefix, so a tool
# version that names a section differently (section_2_<name>) still matches.
UNCHANGED_SECTIONS = (2, 4, 5, 8)


class BuildError(Exception):
    pass


def log(msg):
    print(msg, flush=True)


def sha256_bytes(b):
    return hashlib.sha256(b).hexdigest()


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def read(path):
    with open(path, "rb") as f:
        return f.read()


# --------------------------------------------------------------------------- patch.json
def load_patch(path=PATCH_JSON):
    """Parse and sanity-check patch.json. Returns (patch dict, runs) where runs is a list of
    (load address, bytes, feature id, kind), sorted by address."""
    try:
        with open(path, encoding="utf-8") as f:
            p = json.load(f)
    except (OSError, ValueError) as e:
        raise BuildError("cannot read %s: %s" % (path, e))
    if p.get("format") != PATCH_FORMAT or p.get("format_version") != PATCH_FORMAT_VERSION:
        raise BuildError("%s: unsupported format %r version %r" % (path, p.get("format"), p.get("format_version")))
    if int(p["load_base"], 16) != LOAD_BASE:
        raise BuildError("%s: load_base %s, expected 0x%08x" % (path, p["load_base"], LOAD_BASE))
    if p["stock"]["syx_sha256"] != STOCK_SYX_SHA256 or p["stock"]["syx_size"] != STOCK_SYX_SIZE:
        raise BuildError("%s: its stock hash does not match the one built into build.py" % path)
    runs = []
    for feat in p["features"]:
        for r in feat["runs"]:
            addr = int(r["addr"], 16)
            data = bytes.fromhex(r["bytes"])
            if not data:
                raise BuildError("%s: empty run at %s" % (path, r["addr"]))
            runs.append((addr, data, feat["id"], r.get("kind", "code")))
    runs.sort(key=lambda x: x[0])
    return p, runs


def check_runs(runs, section_size):
    """Every run inside section 3, outside every protected range, and no two runs overlapping."""
    end_prev, id_prev = None, None
    for addr, data, fid, _kind in runs:
        end = addr + len(data)
        if addr < LOAD_BASE or end > LOAD_BASE + section_size:
            raise BuildError("run 0x%08x (+%d, %s) lies outside section 3" % (addr, len(data), fid))
        for lo, hi, what in PROTECTED:
            if addr < hi and end > lo:
                raise BuildError("run 0x%08x (+%d, %s) touches a protected range: 0x%08x-0x%08x %s"
                                 % (addr, len(data), fid, lo, hi, what))
        if end_prev is not None and addr < end_prev:
            raise BuildError("run 0x%08x (%s) overlaps the run before it (%s)" % (addr, fid, id_prev))
        end_prev, id_prev = end, fid


def apply_runs(section, runs):
    buf = bytearray(section)
    for addr, data, _fid, _kind in runs:
        off = addr - LOAD_BASE
        buf[off:off + len(data)] = data
    return bytes(buf)


def patched_section3(stock_section3, patch, runs):
    """Check and apply the runs; the result must hash to patch.json's section-3 hash."""
    if len(stock_section3) != patch["stock"]["section3_size"] or \
            sha256_bytes(stock_section3) != patch["stock"]["section3_sha256"]:
        raise BuildError("section 3 of the input is not the stock MAIN OS (SHA-256 %s)" % sha256_bytes(stock_section3))
    check_runs(runs, len(stock_section3))
    out = apply_runs(stock_section3, runs)
    h = sha256_bytes(out)
    if h != patch["result"]["section3_sha256"]:
        raise BuildError("patched section 3 hashes to %s, expected %s" % (h, patch["result"]["section3_sha256"]))
    return out


# --------------------------------------------------------------------------- firmware tool
def run_tool(tool, args):
    try:
        cp = subprocess.run([tool] + args, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            universal_newlines=True)
    except OSError as e:
        raise BuildError("cannot run the firmware tool %s: %s" % (tool, e))
    if cp.returncode != 0:
        raise BuildError("firmware tool failed (%s, exit %d):\n%s%s"
                         % (" ".join(args[:2]), cp.returncode, cp.stdout, cp.stderr))
    return cp.stdout


def extract(tool, syx, dest):
    """Extract every section of syx into dest; returns {section number: file path}."""
    os.makedirs(dest)
    run_tool(tool, ["-i", syx, "-o", dest])
    found = {}
    for name in sorted(os.listdir(dest)):
        m = re.match(r"section_(\d+)_", name)
        if not m:
            continue
        n = int(m.group(1))
        if n in found:
            raise BuildError("two files for section %d in %s" % (n, dest))
        found[n] = os.path.join(dest, name)
    if 3 not in found:
        raise BuildError("the tool extracted no section 3 (MAIN OS) from %s" % syx)
    return found


def check_report(tool, syx):
    """The tool's report for syx must say the checksums are ok, for a Digitakt, version 1.54."""
    rep = run_tool(tool, ["-i", syx])
    if not re.search(r"^\s*checksums\s*:\s*ok\s*$", rep, re.M):
        raise BuildError("the tool does not report 'checksums : ok' for the packed file:\n" + rep)
    if not re.search(r"^\s*device\s*:\s*Digitakt\b", rep, re.M):
        raise BuildError("the tool does not report a Digitakt image for the packed file:\n" + rep)
    if not re.search(r"^\s*version\s*:\s*1\.54\s*$", rep, re.M):
        raise BuildError("the tool does not report version 1.54 for the packed file:\n" + rep)
    return rep


def pack(tool, stock_syx, patched_bin, out_syx):
    run_tool(tool, ["-i", stock_syx, "-c", "3", patched_bin, "-o", out_syx])
    if not os.path.isfile(out_syx):
        raise BuildError("the tool wrote no output file")


def roundtrip(tool, packed_syx, patched, stock_sections, dest):
    """Re-extract the packed file: section 3 must be the patched bytes, 2/4/5 the stock bytes."""
    got = extract(tool, packed_syx, dest)
    if read(got[3]) != patched:
        raise BuildError("round trip: section 3 of the packed file differs from the patched section 3")
    if sorted(got) != sorted(stock_sections):
        raise BuildError("round trip: sections %s, stock has %s" % (sorted(got), sorted(stock_sections)))
    for n in UNCHANGED_SECTIONS:
        if n not in stock_sections:
            raise BuildError("stock extract has no section %d" % n)
        if read(got[n]) != read(stock_sections[n]):
            raise BuildError("round trip: section %d differs from stock" % n)


def final_name(syx_sha256):
    return "dt_og_plus_plus_v0.2.2_%s.syx" % syx_sha256[:8]


# --------------------------------------------------------------------------- main
def build(syx, tool, out):
    log("DT OG++ build")
    # 1. the input must be the stock file
    if not os.path.isfile(syx):
        raise BuildError("no stock file at %s\nPut Elektron's Digitakt_OS1.54.syx there, or pass --syx." % syx)
    h, size = sha256_file(syx), os.path.getsize(syx)
    if h != STOCK_SYX_SHA256 or size != STOCK_SYX_SIZE:
        raise BuildError(
            "refusing %s:\n  SHA-256 %s (%d B)\n  expected %s (%d B), the unmodified Digitakt OS 1.54 file.\n"
            "DT OG++ is built only from that exact file; there is no override." % (syx, h, size, STOCK_SYX_SHA256, STOCK_SYX_SIZE))
    log("[1/7] stock .syx ok            %s" % h)
    if not (os.path.isfile(tool) and os.access(tool, os.X_OK)):
        raise BuildError("no firmware tool at %s\nBuild it with:  bash build/build_tool.sh   (or pass --tool)" % tool)
    patch, runs = load_patch()

    os.makedirs(out, exist_ok=True)
    tmp = tempfile.mkdtemp(prefix=".build-", dir=out)
    try:
        # 2. extract; section 3 must be stock
        stock_sections = extract(tool, syx, os.path.join(tmp, "stock"))
        stock3 = read(stock_sections[3])
        log("[2/7] extracted section 3      %s" % sha256_bytes(stock3))
        # 3. apply the patch
        patched = patched_section3(stock3, patch, runs)
        nbytes = sum(len(d) for _a, d, _f, _k in runs)
        log("[3/7] applied %d runs, %d B    %s" % (len(runs), nbytes, sha256_bytes(patched)))
        patched_bin = os.path.join(tmp, "section_3_patched.bin")
        with open(patched_bin, "wb") as f:
            f.write(patched)
        # 4. pack
        packed = os.path.join(tmp, "packed.syx")
        pack(tool, syx, patched_bin, packed)
        log("[4/7] packed                   %d B" % os.path.getsize(packed))
        # 5. container checksums
        check_report(tool, packed)
        log("[5/7] checksums : ok")
        # 6. round trip
        roundtrip(tool, packed, patched, stock_sections, os.path.join(tmp, "verify"))
        log("[6/7] round trip ok: section 3 = patched; sections %s = stock"
            % ", ".join(str(n) for n in UNCHANGED_SECTIONS))
        # 7. only now give it its final name
        syx_h = sha256_file(packed)
        syx_size = os.path.getsize(packed)
        dest = os.path.join(out, final_name(syx_h))
        os.replace(packed, dest)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)
    log("[7/7] wrote %s" % dest)
    log("")
    log("section 3 SHA-256  %s  = patch.json result (the tool-independent check)" % sha256_bytes(patched))
    ref = patch["result"]["syx_sha256_reference"]
    if syx_h == ref:
        log(".syx      SHA-256  %s  (%d B) = the reference build" % (syx_h, syx_size))
    else:
        log(".syx      SHA-256  %s  (%d B) differs from the reference %s" % (syx_h, syx_size, ref))
        log("                   expected unless you used the pinned tool (build/build_tool.sh); the section-3")
        log("                   hash above is what identifies the firmware.")
    log("Nothing was sent to a device.")
    return dest


def main(argv=None):
    ap = argparse.ArgumentParser(description="Build DT OG++ from the stock Digitakt OS 1.54 .syx")
    ap.add_argument("--syx", default=DEFAULT_SYX, help="stock Digitakt_OS1.54.syx (default: sysex/ in the repo)")
    ap.add_argument("--tool", default=DEFAULT_TOOL, help="firmware tool binary (default: tool/bin/ in the repo)")
    ap.add_argument("--out", default=DEFAULT_OUT, help="output folder (default: out/1.54/ in the repo)")
    a = ap.parse_args(argv)
    try:
        build(os.path.abspath(a.syx), os.path.abspath(a.tool), os.path.abspath(a.out))
    except BuildError as e:
        print("error: %s" % e, file=sys.stderr)
        print("build FAILED; no output file was written.", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("interrupted; no output file was written.", file=sys.stderr)
        return 130
    except Exception:
        import traceback
        traceback.print_exc()
        print("build FAILED (unexpected error); no output file was written.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
