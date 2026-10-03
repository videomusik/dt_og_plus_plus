#!/usr/bin/env python3
"""Tell which OS folder a Digitakt .syx file belongs to.

    python3 scripts/identify.py FILE [FILE ...] [--tool tool/bin/elektron-firmware-tool-capped]

The known files come from every OS folder os/<v>/ (every folder under os/ whose name does not start
with . or _):
    os/<v>/profile.sh          OS_STOCK_SYX_SHA256: the stock file (parsed, never sourced)
    os/<v>/build/patch.json    stock.syx_sha256 and result.syx_sha256_reference, when the folder
                               has a build

By the SHA-256 of the whole file, each FILE is one of:
    stock <OS_LABEL> (os/<v>)                          that OS folder's unmodified stock file
    DT OG++ reference build for <OS_LABEL> (os/<v>)    that OS folder's reference build
    unknown                                            anything else

With --tool, the tool's read-only summary (T -i FILE) is run as well, and its device and version
lines are shown. When the version names an OS folder, the next step is that folder's verifier,
python3 os/<v>/build/verify.py, which also tells a build packed by another tool by its MAIN OS
section. Otherwise it is "Starting a new OS folder" in os/README.md.

Exit status: 0 when every file is known, 1 when any file is unknown, 2 on an error: an OS folder
whose profile.sh and patch.json disagree, two OS folders (or two kinds of file) that claim the same
hash, a missing file, or no tool at the --tool path.

Reads only: it never extracts and never writes anything. Nothing here talks to a device.
"""
import argparse, hashlib, json, os, re, subprocess, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OS_TOP = os.path.join(ROOT, "os")
OS_ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9.]*$")
ASSIGN_RE = re.compile(r"^\s*(?:export\s+)?(OS_[A-Z0-9_]+)=(.*)$")
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
REQUIRED = ("OS_ID", "OS_LABEL", "OS_REPORTED_VERSION", "OS_STOCK_SYX_SHA256")
# profile.sh variable -> the same value in patch.json's "stock" object
AGREE = (("OS_STOCK_SYX_SHA256", "syx_sha256"), ("OS_STOCK_SYX_SIZE", "syx_size"),
         ("OS_STOCK_MAIN_SHA256", "section3_sha256"), ("OS_STOCK_MAIN_SIZE", "section3_size"))
NEW_FOLDER = "see os/README.md, Starting a new OS folder"


class DataError(Exception):
    """The OS folders' own data is missing or inconsistent (exit 2)."""


def rel(path):
    return os.path.relpath(path, ROOT)


def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def shell_value(raw):
    """The value of a simple shell assignment: quotes stripped, a trailing comment dropped.
    Returns None for an unterminated quote."""
    raw = raw.rstrip("\r\n")
    if raw[:1] in ("'", '"'):
        end = raw.find(raw[0], 1)
        return None if end < 0 else raw[1:end]
    words = raw.split(None, 1)
    return words[0] if words else ""


def read_profile(path):
    vals = {}
    with open(path, encoding="utf-8") as f:
        for n, line in enumerate(f, 1):
            m = ASSIGN_RE.match(line)
            if not m:
                continue
            name, value = m.group(1), shell_value(m.group(2))
            if value is None:
                raise DataError("%s:%d: unterminated quote in %s" % (rel(path), n, name))
            if name in vals and vals[name] != value:
                raise DataError("%s:%d: %s is set twice, to different values" % (rel(path), n, name))
            vals[name] = value
    return vals


def hash_value(value, where):
    v = str(value).strip().lower()
    if not SHA256_RE.match(v):
        raise DataError("%s: not a SHA-256: %r" % (where, value))
    return v


def load_os_folders():
    """Returns (known, versions): known maps a SHA-256 to (kind, OS id, label); versions maps a
    reported version to its OS id."""
    known, versions = {}, {}

    def claim(h, kind, os_id, label):
        if h in known and known[h][:2] != (kind, os_id):
            k0, o0 = known[h][:2]
            raise DataError("SHA-256 %s... is claimed twice: %s of os/%s/ and %s of os/%s/"
                            % (h[:16], k0, o0, kind, os_id))
        known[h] = (kind, os_id, label)

    names = sorted(os.listdir(OS_TOP)) if os.path.isdir(OS_TOP) else []
    for name in names:
        folder = os.path.join(OS_TOP, name)
        if name.startswith((".", "_")) or not os.path.isdir(folder):
            continue
        if not OS_ID_RE.match(name):
            raise DataError("os/%s/ is not a valid OS folder name" % name)
        profile_path = os.path.join(folder, "profile.sh")
        if not os.path.isfile(profile_path):
            raise DataError("os/%s/ has no profile.sh" % name)
        prof = read_profile(profile_path)
        missing = [v for v in REQUIRED if not prof.get(v)]
        if missing:
            raise DataError("%s does not set %s" % (rel(profile_path), ", ".join(missing)))
        if prof["OS_ID"] != name:
            raise DataError("%s sets OS_ID=%s, not the folder name %s"
                            % (rel(profile_path), prof["OS_ID"], name))
        label = prof["OS_LABEL"]
        stock = hash_value(prof["OS_STOCK_SYX_SHA256"], rel(profile_path))
        claim(stock, "stock", name, label)

        version = prof["OS_REPORTED_VERSION"]
        if version in versions:
            raise DataError("os/%s/ and os/%s/ both report version %s"
                            % (versions[version], name, version))
        versions[version] = name

        patch_path = os.path.join(folder, "build", "patch.json")
        if not os.path.isfile(patch_path):
            continue
        try:
            with open(patch_path, encoding="utf-8") as f:
                patch = json.load(f)
        except (OSError, ValueError) as e:
            raise DataError("cannot read %s: %s" % (rel(patch_path), e))
        pstock = patch.get("stock") if isinstance(patch, dict) else None
        if not isinstance(pstock, dict) or "syx_sha256" not in pstock:
            raise DataError("%s has no stock.syx_sha256" % rel(patch_path))
        for var, key in AGREE:
            if var in prof and key in pstock:
                a, b = prof[var].strip().lower(), str(pstock[key]).strip().lower()
                if a != b:
                    raise DataError("%s %s=%s disagrees with %s stock.%s=%s"
                                    % (rel(profile_path), var, prof[var], rel(patch_path), key,
                                       pstock[key]))
        claim(hash_value(pstock["syx_sha256"], rel(patch_path) + " stock.syx_sha256"),
              "stock", name, label)
        result = patch.get("result")
        if isinstance(result, dict) and result.get("syx_sha256_reference"):
            ref = hash_value(result["syx_sha256_reference"],
                             rel(patch_path) + " result.syx_sha256_reference")
            claim(ref, "reference build", name, label)
    return known, versions


def describe(entry):
    if entry is None:
        return "unknown"
    kind, os_id, label = entry
    if kind == "stock":
        return "stock %s (os/%s)" % (label, os_id)
    return "DT OG++ reference build for %s (os/%s)" % (label, os_id)


def tool_summary(tool, path):
    """Returns (device line, version line, version string or None) from the tool's -i summary."""
    rep = subprocess.run([tool, "-i", path], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                         universal_newlines=True, errors="replace")
    missing = "(not reported, tool exit %d)" % rep.returncode
    dev = re.search(r"^[ \t]*device[ \t]*:.*$", rep.stdout, re.M)
    ver = re.search(r"^[ \t]*version[ \t]*:(.*)$", rep.stdout, re.M)
    version = ver.group(1).strip() if ver else ""
    return (dev.group(0).strip() if dev else "device    : " + missing,
            ver.group(0).strip() if ver else "version   : " + missing,
            version or None)


def main(argv=None):
    ap = argparse.ArgumentParser(
        description="Tell which OS folder a Digitakt .syx file belongs to (reads only)")
    ap.add_argument("files", nargs="+", metavar="FILE")
    ap.add_argument("--tool", help="firmware tool binary; also show its device and version lines")
    a = ap.parse_args(argv)
    try:
        known, versions = load_os_folders()
    except (DataError, OSError, UnicodeDecodeError) as e:
        print("error: %s" % e, file=sys.stderr)
        return 2
    if a.tool and not (os.path.isfile(a.tool) and os.access(a.tool, os.X_OK)):
        print("error: no firmware tool at %s" % a.tool, file=sys.stderr)
        return 2
    status = 0
    for path in a.files:
        print(path)
        if not os.path.isfile(path):
            print("  error: no such file")
            status = max(status, 2)
            continue
        h = sha256_file(path)
        entry = known.get(h)
        print("  file       %s  %d B  -> %s" % (h, os.path.getsize(path), describe(entry)))
        if entry is None:
            status = max(status, 1)
        if not a.tool:
            continue
        dev, ver, version = tool_summary(os.path.abspath(a.tool), os.path.abspath(path))
        print("  tool       %s" % dev)
        print("  tool       %s" % ver)
        if version is None:
            print("  next       the tool reports no version; %s" % NEW_FOLDER)
            continue
        os_id = versions.get(version)
        if os_id is None:
            print("  next       no OS folder reports version %s; %s" % (version, NEW_FOLDER))
            continue
        if entry is not None and entry[1] != os_id:
            print("  warning    the file hash names os/%s/, the tool's version names os/%s/"
                  % (entry[1], os_id))
        verifier = os.path.join(OS_TOP, os_id, "build", "verify.py")
        if os.path.isfile(verifier):
            print("  next       python3 %s %s --tool %s" % (os.path.relpath(verifier), path, a.tool))
        else:
            print("  next       os/%s/ has no verify.py yet; %s" % (os_id, NEW_FOLDER))
    return status


if __name__ == "__main__":
    sys.exit(main())
