#!/usr/bin/env python3
"""Tell what a Digitakt .syx file is.

    python3 os/1.52A/build/verify.py FILE.syx [FILE.syx ...] [--tool tool/bin/elektron-firmware-tool-capped]

By the SHA-256 of the whole file, each FILE is one of:
    stock Digitakt OS 1.52A      Elektron's unmodified update file
    DT OG++ (reference build)    the build this repository describes, packed by the pinned tool
    unknown                      anything else

A DT OG++ file packed by a different tool version has a different .syx hash but the same
firmware. With --tool the file is also extracted and classified by its section 3 (MAIN OS), which
does not depend on the tool:
    stock MAIN OS / DT OG++ MAIN OS / unknown MAIN OS
and the tool's own checksum report is shown.

Exit status: 0 when every file is recognised (by file hash, or by section 3 with --tool),
1 when any file is unknown, 2 on an error. Nothing here talks to a device.
"""
import argparse, hashlib, json, os, re, shutil, subprocess, sys, tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
PATCH_JSON = os.path.join(HERE, "patch.json")


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def classify_file(h, patch):
    if h == patch["stock"]["syx_sha256"]:
        return "stock Digitakt OS 1.52A"
    if h == patch["result"]["syx_sha256_reference"]:
        return "DT OG++ (reference build)"
    return "unknown"


def classify_section3(h, patch):
    if h == patch["stock"]["section3_sha256"]:
        return "stock MAIN OS"
    if h == patch["result"]["section3_sha256"]:
        return "DT OG++ MAIN OS"
    return "unknown MAIN OS"


def tool_section3(tool, syx):
    """Returns (checksum line of the tool's report, SHA-256 of section 3 or None)."""
    rep = subprocess.run([tool, "-i", syx], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                         universal_newlines=True)
    m = re.search(r"^\s*checksums\s*:.*$", rep.stdout, re.M)
    checks = m.group(0).strip() if m else "checksums : (not reported, tool exit %d)" % rep.returncode
    tmp = tempfile.mkdtemp(prefix="dt-og-pp-verify-")
    try:
        cp = subprocess.run([tool, "-i", syx, "-o", tmp], stdout=subprocess.PIPE,
                            stderr=subprocess.STDOUT, universal_newlines=True)
        s3 = [n for n in os.listdir(tmp) if n.startswith("section_3_")]
        if cp.returncode != 0 or len(s3) != 1:
            return checks, None
        return checks, sha256_file(os.path.join(tmp, s3[0]))
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


def main(argv=None):
    ap = argparse.ArgumentParser(description="Classify Digitakt .syx files")
    ap.add_argument("files", nargs="+", metavar="FILE.syx")
    ap.add_argument("--tool", help="firmware tool binary; also classify by section 3")
    a = ap.parse_args(argv)
    try:
        with open(PATCH_JSON, encoding="utf-8") as f:
            patch = json.load(f)
    except (OSError, ValueError) as e:
        print("error: cannot read %s: %s" % (PATCH_JSON, e), file=sys.stderr)
        return 2
    if a.tool and not (os.path.isfile(a.tool) and os.access(a.tool, os.X_OK)):
        print("error: no firmware tool at %s" % a.tool, file=sys.stderr)
        return 2
    status = 0
    for path in a.files:
        if not os.path.isfile(path):
            print("%s\n  error: no such file" % path)
            status = max(status, 2)
            continue
        h = sha256_file(path)
        what = classify_file(h, patch)
        print(path)
        print("  file       %s  %d B  -> %s" % (h, os.path.getsize(path), what))
        known = what != "unknown"
        if a.tool:
            checks, h3 = tool_section3(os.path.abspath(a.tool), os.path.abspath(path))
            print("  tool       %s" % checks)
            if h3 is None:
                print("  section 3  (could not be extracted)")
            else:
                w3 = classify_section3(h3, patch)
                print("  section 3  %s  -> %s" % (h3, w3))
                if w3 != "unknown MAIN OS" and not known:
                    print("  (same firmware as %s; the container was packed by a different tool)"
                          % ("stock" if w3 == "stock MAIN OS" else "DT OG++"))
                known = known or w3 != "unknown MAIN OS"
        if not known:
            status = max(status, 1)
    return status


if __name__ == "__main__":
    sys.exit(main())
