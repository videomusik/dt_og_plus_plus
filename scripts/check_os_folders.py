#!/usr/bin/env python3
"""Check the rules that keep each OS folder (os/<version>/) apart from the others and from the
shared tree. Read-only: it reads the tracked files and never writes, builds or extracts anything.

    python3 scripts/check_os_folders.py [--root DIR]

The file set is what git lists: tracked files plus untracked files that no ignore rule matches
(git ls-files --cached --others --exclude-standard), as far as they exist in the working tree.
The text rules skip binary files (those holding a null byte). An OS folder is every os/<v>/ whose
name does not start with '.' or '_'; everything else, os/README.md included, is shared. Profiles
are parsed with a regular expression and never sourced; build files are read with the ast module
and never imported or run.

  R1  Links. Every relative markdown link outside fenced code, inline code and HTML comments
      resolves to a listed file or folder, and its #anchor is a heading of the target (GitHub
      slugs; repeated headings get -1, -2, ...) or an explicit <a id|name>. Image references are
      not links and are skipped. A link written from the repository root ('/...') is an error.
  R2  No cross-OS references. A file inside os/A/ neither links to nor names a path inside os/B/.
  R3  Identity and layout agree, per OS folder: profile.sh sets OS_ID to the folder name and every
      required OS_* value; when build/ exists, the stock hashes and sizes, the MAIN load base, the
      section ids, the version in check_report and the DEFAULT_OUT folder agree with build.py and
      build/patch.json, and the MAIN OS section is section 3.
  R4  A build folder needs a vetted protected set: build.py's PROTECTED is not empty, and
      notes/update_moat.md exists in that OS folder.
  R5  Hashes stay home. The 8-hex prefixes of an OS folder's identity hashes (stock file, stock MAIN,
      patched MAIN, reference build) appear in no text file outside that folder.
  R6  Shared firmware literals are labelled. Outside the OS folders, a line holding an address
      literal or a FUN_/DAT_/LAB_/PTR_/switchD_/entry_ name also names an OS folder's id as a
      whole token (e.g. "OS <id>", "dt_<id>", "os/<id>/"). The chip constants are exempt.
  R7  No twin names. For a listed os/<v>/P other than a README.md, the shared path P is not listed.
  R8  Nothing inherited across OS folders (only with two or more): (a) no address literal of one
      folder's code and data files appears in another's, unless a rederived.md lists it; (b) copied
      builders hold the same functions and classes, identical once string constants are masked.
  R9  Nothing tracked matches an ignore rule, and no listed path uses a local-only folder name
      (sysex, manuals, tool, out, work) or a firmware file name (section_*, *.bin, *.raw, *.syx,
      *.pdf).
  R10 Every OS folder name is a valid OS id and the folder has a profile.sh.

Each error prints as 'path:line: R<n>: message' (line 0: the path as a whole); the exit code is 1
if there is any. Otherwise it prints 'ok: <n> files, <m> links checked' and exits 0.
The rules are explained in os/README.md and AGENTS.md. Python 3.7+, standard library only.
"""
import argparse, ast, copy, fnmatch, html, json, os, posixpath, re, subprocess, sys, unicodedata
from urllib.parse import unquote

PY38 = sys.version_info >= (3, 8)

OS_ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9.]*$")
# Fixed addresses of the chip's memory map, not of any OS version's code or data.
CHIP_CONSTANTS = {"0x40000000", "0x48000000", "0x80000000", "0x80008000", "0x80010000"}
# The one shared file allowed to hold unlabelled firmware literals (Apache-2.0 and hash-sensitive).
R6_EXEMPT_FILES = {"scripts/ghidra_ext/coldfire_emac.patch"}

REQUIRED_PROFILE_VARS = (
    "OS_ID", "OS_LABEL", "OS_REPORTED_VERSION", "OS_STOCK_SYX_DEFAULT", "OS_STOCK_SYX_SHA256",
    "OS_STOCK_SYX_SIZE", "OS_CONTAINER_SECTION_IDS", "OS_MAIN_ID", "OS_MAIN_BASE",
    "OS_STOCK_MAIN_SHA256", "OS_STOCK_MAIN_SIZE", "OS_ANALYSIS_SECTIONS", "OS_SIGNATURE_TRAILER",
)
BUILDER_FILES = ("build.py", "verify.py", "make_listing.py")
LOCAL_ONLY_DIRS = {"sysex", "manuals", "tool", "out", "work"}
FIRMWARE_NAME_GLOBS = ("section_*", "*.bin", "*.raw", "*.syx", "*.pdf")
MASK = "<string>"

R6_TOKEN = re.compile(r"0x[48][0-9A-Fa-f]{7}|(?:FUN|DAT|LAB|PTR|switchD|entry)_[48][0-9A-Fa-f]{7}")
R8_LITERAL = re.compile(r"0x[48][0-9a-f]{7}", re.I)
HEX_FOLLOWS = re.compile(r"[0-9A-Fa-f]")
SHA256_RE = re.compile(r"^[0-9a-fA-F]{64}$")
PROFILE_LINE = re.compile(r"^\s*(?:export\s+)?(OS_[A-Z0-9_]+)=(.*)$")
OS_PATH_IN_TEXT = re.compile(r"os/([A-Za-z0-9._\-]+)")
PATH_COMPONENT_STOP = " \t`'\"([=:,/"

FENCE_RE = re.compile(r"^(`{3,}|~{3,})(.*)$")
HEADING_RE = re.compile(r"^ {0,3}(#{1,6})(?:[ \t]+(.*?))?(?:[ \t]+#+)?[ \t]*$")
SETEXT_RE = re.compile(r"^ {0,3}(=+|-+)[ \t]*$")
ANCHOR_TAG_RE = re.compile(r"""<a\b[^>]*?\b(?:id|name)\s*=\s*["']([^"']+)["']""", re.I)
CODE_SPAN_RE = re.compile(r"(`+)(?:(?!\1).)+?\1")
HTML_COMMENT_RE = re.compile(r"<!--.*?-->")
LINK_RE = re.compile(
    r"(?<![!\\])\[((?:[^\[\]\\]|\\.|\[(?:[^\[\]\\]|\\.)*\])*)\]"   # [text], one level of nesting
    r"\(\s*(<[^<>\n]*>|[^()\s]*(?:\([^()\s]*\)[^()\s]*)*)"         # (href  or (<href>
    r"(?:\s+(?:\"[^\"]*\"|'[^']*'|\([^()]*\)))?\s*\)")               # optional title, )
REF_DEF_RE = re.compile(r"^ {0,3}\[(?!\^)[^\]]+\]:\s*(<[^<>]*>|\S+)")
SCHEME_RE = re.compile(r"^[A-Za-z][A-Za-z0-9+.-]*:")


class Checker:
    def __init__(self, root):
        self.root = root
        self.errors = []
        self.links = 0
        self.files = list_files(root)
        self.fileset = set(self.files)
        self.dirs = set()
        for f in self.files:
            parts = f.split("/")
            for i in range(1, len(parts)):
                self.dirs.add("/".join(parts[:i]))
        self.text_cache = {}
        self.anchor_cache = {}
        # OS folders: os/<v>/ for every v holding a listed path, unless v starts with '.' or '_'.
        names = set()
        for f in self.files:
            parts = f.split("/")
            if len(parts) >= 3 and parts[0] == "os" and not parts[1].startswith((".", "_")):
                names.add(parts[1])
        self.os_dirs = sorted(names)
        self.os_ids = [v for v in self.os_dirs if OS_ID_RE.match(v)]
        self.label_res = [re.compile(r"(?<![A-Za-z0-9.])" + re.escape(v) + r"(?![A-Za-z0-9])")
                          for v in self.os_ids]
        self.profiles = {}
        self.builds = {}
        self.patches = {}

    # ------------------------------------------------------------------ helpers
    def err(self, path, line, rule, msg):
        self.errors.append((path, line, rule, msg))

    def os_of(self, path):
        """The OS folder a listed path lies in, or None for a shared path."""
        parts = path.split("/")
        if len(parts) >= 3 and parts[0] == "os" and parts[1] in self.os_dirs:
            return parts[1]
        return None

    def lines(self, path):
        """The file's lines, or None for a binary file (one holding a null byte) or an unreadable one."""
        if path not in self.text_cache:
            full = os.path.join(self.root, path)
            data = None
            if os.path.isfile(full) and not os.path.islink(full):
                try:
                    with open(full, "rb") as f:
                        data = f.read()
                except OSError:
                    data = None
            if data is None or b"\0" in data:
                self.text_cache[path] = None
            else:
                text = data.decode("utf-8", "replace")
                self.text_cache[path] = [l[:-1] if l.endswith("\r") else l for l in text.split("\n")]
        return self.text_cache[path]

    def text_files(self):
        for f in self.files:
            ls = self.lines(f)
            if ls is not None:
                yield f, ls

    # ------------------------------------------------------------------ R1, R2: links
    def anchors(self, path):
        if path not in self.anchor_cache:
            found, seen = set(), {}

            def add_heading(text):
                s = slug(text)
                n = seen.get(s, 0)
                found.add(s if n == 0 else "%s-%d" % (s, n))
                seen[s] = n + 1

            prev = None          # the previous line, when it can be the text of a setext heading
            for kind, line in markdown_lines(self.lines(path) or []):
                if kind != "text":
                    prev = None
                    continue
                for m in ANCHOR_TAG_RE.finditer(line):
                    found.add(m.group(1))
                m = HEADING_RE.match(line)
                if m:
                    add_heading(m.group(2) or "")
                    prev = None
                    continue
                if prev is not None and SETEXT_RE.match(line):
                    add_heading(prev)
                    prev = None
                    continue
                stripped = line.strip()
                if (not stripped or line.startswith(("    ", "\t"))
                        or re.match(r"^\s*(?:[-*+>|<]|\d+[.)])", line)):
                    prev = None
                else:
                    prev = stripped
            self.anchor_cache[path] = found
        return self.anchor_cache[path]

    def check_links(self):
        for f in self.files:
            if not f.lower().endswith(".md"):
                continue
            ls = self.lines(f)
            if ls is None:
                continue
            src_os = self.os_of(f)
            in_comment = False
            for kind, line, i in numbered_markdown_lines(ls):
                if kind != "text":
                    continue
                visible = CODE_SPAN_RE.sub(lambda m: " " * len(m.group(0)), line)
                if in_comment:
                    end = visible.find("-->")
                    if end < 0:
                        continue
                    visible = " " * (end + 3) + visible[end + 3:]
                    in_comment = False
                visible = HTML_COMMENT_RE.sub(lambda m: " " * len(m.group(0)), visible)
                if visible.lstrip().startswith("<!--"):
                    in_comment = True
                    continue
                hrefs = [m.group(2) for m in LINK_RE.finditer(visible)]
                m = REF_DEF_RE.match(visible)
                if m:
                    hrefs.append(m.group(1))
                for href in hrefs:
                    self.check_link(f, i, href, src_os)

    def check_link(self, f, i, href, src_os):
        if href.startswith("<") and href.endswith(">"):
            href = href[1:-1].strip()
        if not href or SCHEME_RE.match(href) or href.startswith("//"):
            return
        self.links += 1
        path, _, anchor = href.partition("#")
        path = unquote(path.split("?", 1)[0])
        anchor = unquote(anchor)
        if path.startswith("/"):
            self.err(f, i, "R1", "link %s: write a relative path, not one from the repository root" % href)
            return
        tgt = posixpath.normpath(posixpath.join(posixpath.dirname(f), path)) if path else f
        if tgt == ".." or tgt.startswith("../"):
            self.err(f, i, "R1", "link %s leaves the repository" % href)
            return
        if src_os is not None:
            parts = tgt.split("/")
            if len(parts) >= 2 and parts[0] == "os" and \
                    self.other_os(parts[1], len(parts) >= 3 or path.endswith("/"), src_os):
                self.err(f, i, "R2", "link %s points into os/%s/ from os/%s/" % (href, parts[1], src_os))
        is_file = tgt in self.fileset
        is_dir = tgt == "." or tgt in self.dirs
        if not (is_file or is_dir):
            self.err(f, i, "R1", "link %s: no listed file or folder %s" % (href, tgt))
            return
        if anchor and is_file and tgt.lower().endswith(".md"):
            if self.lines(tgt) is None or anchor not in self.anchors(tgt):
                self.err(f, i, "R1", "link %s: %s has no heading or <a id> for #%s" % (href, tgt, anchor))

    def check_os_paths_in_text(self):
        """R2 for plain text: a file inside os/A/ names no path inside another os/B/."""
        for f, ls in self.text_files():
            src_os = self.os_of(f)
            if src_os is None:
                continue
            for i, line in enumerate(ls, 1):
                for m in OS_PATH_IN_TEXT.finditer(line):
                    if not starts_repo_path(line, m.start()):
                        continue
                    name = m.group(1).rstrip(".")
                    folder = line[m.start(1) + len(name):m.start(1) + len(name) + 1] == "/"
                    if self.other_os(name, folder, src_os):
                        self.err(f, i, "R2", "names os/%s/ inside os/%s/" % (name, src_os))

    def other_os(self, name, folder, src_os):
        """True when os/<name> (followed by '/' when folder is true) is inside an OS folder other
        than src_os. A listed file directly in os/ (os/README.md) and a bare os/<page>.md are shared."""
        if not name or name == src_os or name.startswith((".", "_")):
            return False
        if "os/" + name in self.fileset:
            return False
        if not folder and name.lower().endswith(".md"):
            return False
        return True

    # ------------------------------------------------------------------ R3, R4, R10: profiles and builds
    def load_profile(self, v):
        path = "os/%s/profile.sh" % v
        if path not in self.fileset:
            return None
        values = {}
        for i, line in enumerate(self.lines(path) or [], 1):
            m = PROFILE_LINE.match(line)
            if m:
                values[m.group(1)] = (shell_value(m.group(2)), i)
        return values

    def load_build(self, v):
        path = "os/%s/build/build.py" % v
        if path not in self.fileset:
            return None
        tree = parse_python(self, path, "R3")
        if tree is None:
            return {"tree": None, "consts": {}, "nodes": {}}
        consts, nodes = {}, {}
        for node in tree.body:
            targets = []
            if isinstance(node, ast.Assign):
                targets = node.targets
            elif isinstance(node, ast.AnnAssign) and node.value is not None:
                targets = [node.target]
            for t in targets:
                if isinstance(t, ast.Name):
                    nodes[t.id] = node
                    try:
                        consts[t.id] = (ast.literal_eval(node.value), node.lineno)
                    except (ValueError, TypeError, SyntaxError, RecursionError):
                        consts.pop(t.id, None)
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
                nodes[node.name] = node
        return {"tree": tree, "consts": consts, "nodes": nodes}

    def load_patch(self, v):
        """The parsed patch.json; None when there is none, False when it cannot be read."""
        path = "os/%s/build/patch.json" % v
        if path not in self.fileset:
            return None
        ls = self.lines(path)
        try:
            data = json.loads("\n".join(ls or []))
        except ValueError as e:
            self.err(path, 0, "R3", "cannot parse: %s" % e)
            return False
        if not isinstance(data, dict):
            self.err(path, 0, "R3", "is not a JSON object")
            return False
        return data

    def check_folders(self):
        for v in self.os_dirs:
            base = "os/%s" % v
            if not OS_ID_RE.match(v):
                self.err(base, 0, "R10", "OS folder name %r is not an OS id (letters, digits and '.', not starting with '.')" % v)
            prof = self.load_profile(v)
            if prof is None:
                self.err(base + "/profile.sh", 0, "R10", "OS folder os/%s/ has no profile.sh" % v)
            self.profiles[v] = prof
            self.builds[v] = self.load_build(v)
            self.patches[v] = self.load_patch(v)
            if prof is not None:
                self.check_profile(v, prof)
            self.check_protected(v)

    def check_profile(self, v, prof):
        ppath = "os/%s/profile.sh" % v

        def where(name):
            return prof[name][1] if name in prof else 0

        if "OS_ID" in prof and prof["OS_ID"][0] != v:
            self.err(ppath, where("OS_ID"), "R3", "OS_ID=%s, but the folder is os/%s/" % (prof["OS_ID"][0], v))
        for name in REQUIRED_PROFILE_VARS:
            if not prof.get(name, ("", 0))[0]:
                self.err(ppath, where(name), "R3", "%s is missing or empty" % name)
        for name in ("OS_STOCK_SYX_SHA256", "OS_STOCK_MAIN_SHA256"):
            val = prof.get(name, ("", 0))[0]
            if val and not SHA256_RE.match(val):
                self.err(ppath, where(name), "R3", "%s is not a 64-hex SHA-256" % name)

        has_build_dir = any(f.startswith("os/%s/build/" % v) for f in self.files)
        if not has_build_dir:
            return
        bpath, jpath = "os/%s/build/build.py" % v, "os/%s/build/patch.json" % v
        build, patch = self.builds[v], self.patches[v]
        if build is None:
            self.err("os/%s/build" % v, 0, "R3", "build/ exists but has no build.py")
        if patch is None:
            self.err("os/%s/build" % v, 0, "R3", "build/ exists but has no patch.json")
        build_ok = build is not None and build["tree"] is not None
        patch_ok = isinstance(patch, dict)
        consts = build["consts"] if build_ok else {}
        if not patch_ok:
            patch = {}
        stock = patch.get("stock")
        if patch_ok and not isinstance(stock, dict):
            self.err(jpath, 0, "R3", "patch.json has no stock object")
            stock = None

        def pval(name, conv):
            raw = prof.get(name, ("", 0))[0]
            if not raw:
                return None            # already reported as missing
            try:
                return conv(raw)
            except (ValueError, TypeError):
                self.err(ppath, where(name), "R3", "%s=%r cannot be read as a number" % (name, raw))
                return None

        def compare(name, mine, other, other_where):
            if mine is not None and other is not None and mine != other:
                self.err(ppath, where(name), "R3", "%s differs from %s" % (name, other_where))

        def bconst(name):
            if not build_ok:
                return None
            if name not in consts:
                self.err(bpath, 0, "R3", "no module-level literal %s" % name)
                return None
            return consts[name][0]

        def jget(d, key, label):
            if not patch_ok or not isinstance(d, dict):
                return None
            if key not in d:
                self.err(jpath, 0, "R3", "patch.json has no %s" % label)
                return None
            return d[key]

        syx_sha = lower(prof.get("OS_STOCK_SYX_SHA256", ("", 0))[0]) or None
        compare("OS_STOCK_SYX_SHA256", syx_sha, lower(bconst("STOCK_SYX_SHA256")), "STOCK_SYX_SHA256 in " + bpath)
        compare("OS_STOCK_SYX_SHA256", syx_sha, lower(jget(stock, "syx_sha256", "stock.syx_sha256")),
                "stock.syx_sha256 in " + jpath)
        syx_size = pval("OS_STOCK_SYX_SIZE", lambda s: int(s, 10))
        compare("OS_STOCK_SYX_SIZE", syx_size, bconst("STOCK_SYX_SIZE"), "STOCK_SYX_SIZE in " + bpath)
        compare("OS_STOCK_SYX_SIZE", syx_size, jget(stock, "syx_size", "stock.syx_size"),
                "stock.syx_size in " + jpath)
        main_sha = lower(prof.get("OS_STOCK_MAIN_SHA256", ("", 0))[0]) or None
        compare("OS_STOCK_MAIN_SHA256", main_sha, lower(jget(stock, "section3_sha256", "stock.section3_sha256")),
                "stock.section3_sha256 in " + jpath)
        main_size = pval("OS_STOCK_MAIN_SIZE", lambda s: int(s, 10))
        compare("OS_STOCK_MAIN_SIZE", main_size, jget(stock, "section3_size", "stock.section3_size"),
                "stock.section3_size in " + jpath)
        main_base = pval("OS_MAIN_BASE", lambda s: int(s, 0))
        compare("OS_MAIN_BASE", main_base, bconst("LOAD_BASE"), "LOAD_BASE in " + bpath)
        jbase = jget(patch, "load_base", "load_base")
        if jbase is not None:
            try:
                jbase = int(jbase, 16) if isinstance(jbase, str) else int(jbase)
            except (ValueError, TypeError):
                self.err(jpath, 0, "R3", "load_base %r is not a number" % (jbase,))
                jbase = None
        compare("OS_MAIN_BASE", main_base, jbase, "load_base in " + jpath)
        main_id = pval("OS_MAIN_ID", lambda s: int(s, 10))
        if main_id is not None and main_id != 3:
            self.err(ppath, where("OS_MAIN_ID"), "R3", "OS_MAIN_ID is %d; the builder patches section 3" % main_id)
        ids = pval("OS_CONTAINER_SECTION_IDS", lambda s: set(int(x, 10) for x in s.split()))
        unchanged = bconst("UNCHANGED_SECTIONS")
        if ids is not None and unchanged is not None and main_id is not None:
            try:
                got = set(unchanged) | {main_id}
            except TypeError:
                got = None
                self.err(bpath, consts["UNCHANGED_SECTIONS"][1], "R3", "UNCHANGED_SECTIONS is not a list of ids")
            if got is not None and got != ids:
                self.err(ppath, where("OS_CONTAINER_SECTION_IDS"), "R3",
                         "OS_CONTAINER_SECTION_IDS %s differ from UNCHANGED_SECTIONS + OS_MAIN_ID %s in %s"
                         % (sorted(ids), sorted(got), bpath))
        if not build_ok:
            return
        version = prof.get("OS_REPORTED_VERSION", ("", 0))[0]
        node = build["nodes"].get("check_report")
        if not isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            self.err(bpath, 0, "R3", "no function check_report")
        elif version:
            want = re.escape(version)
            # the escaped version as a whole token: no letter, digit or '.' before it and no letter
            # or digit after it, so a profile version that is only part of the one check_report
            # requires (a prefix such as 1.5, or a tail such as 52A) fails
            token = re.compile(r"(?<![0-9A-Za-z.])" + re.escape(want) + r"(?![0-9A-Za-z])")
            if not any(token.search(s) for s in string_constants(node)):
                self.err(bpath, node.lineno, "R3", "check_report does not require the version in "
                         "OS_REPORTED_VERSION (no string holding %s)" % want)
        os_id = prof.get("OS_ID", ("", 0))[0]
        out = build["nodes"].get("DEFAULT_OUT")
        if not isinstance(out, (ast.Assign, ast.AnnAssign)):
            self.err(bpath, 0, "R3", "no module-level DEFAULT_OUT assignment")
        elif os_id and os_id not in string_constants(out.value):
            self.err(bpath, out.lineno, "R3", "DEFAULT_OUT does not name the OS folder's own output "
                     "folder (no string constant %r, the profile's OS_ID)" % os_id)

    def check_protected(self, v):
        build = self.builds[v]
        if build is None:
            return
        bpath = "os/%s/build/build.py" % v
        if build["tree"] is None:
            pass                       # the parse error is reported under R3
        elif "PROTECTED" not in build["consts"]:
            self.err(bpath, 0, "R4", "no module-level literal PROTECTED")
        else:
            prot, line = build["consts"]["PROTECTED"]
            try:
                empty = len(prot) == 0
            except TypeError:
                empty = True
            if empty:
                self.err(bpath, line, "R4", "PROTECTED is empty: a build folder needs a vetted protected set")
        moat = "os/%s/notes/update_moat.md" % v
        if moat not in self.fileset:
            self.err(bpath, 0, "R4", "build/ needs %s (the vetted protected set)" % moat)

    # ------------------------------------------------------------------ R5: identity hashes
    def check_hashes(self):
        prefixes = {}            # (prefix, OS folder) -> what it is
        for v in self.os_dirs:
            found = []
            prof = self.profiles.get(v) or {}
            for name in ("OS_STOCK_SYX_SHA256", "OS_STOCK_MAIN_SHA256"):
                found.append((prof.get(name, ("", 0))[0], "profile.sh " + name))
            patch = self.patches.get(v) or {}
            for sect, key in (("stock", "syx_sha256"), ("stock", "section3_sha256"),
                              ("result", "section3_sha256"), ("result", "syx_sha256_reference")):
                d = patch.get(sect)
                if isinstance(d, dict):
                    found.append((d.get(key), "patch.json %s.%s" % (sect, key)))
            for val, what in found:
                if isinstance(val, str) and SHA256_RE.match(val):
                    prefixes.setdefault((val[:8].lower(), v), what)
        if not prefixes:
            return
        for f, ls in self.text_files():
            home = self.os_of(f)
            for i, line in enumerate(ls, 1):
                low = line.lower()
                for (p, v), what in sorted(prefixes.items()):
                    if v != home and p in low:
                        self.err(f, i, "R5", "holds the prefix of os/%s's identity hash (%s); "
                                 "keep it inside os/%s/" % (v, what, v))

    # ------------------------------------------------------------------ R6: labelled literals
    def check_labels(self):
        for f, ls in self.text_files():
            if self.os_of(f) is not None or f in R6_EXEMPT_FILES:
                continue
            for i, line in enumerate(ls, 1):
                bad = []
                for m in R6_TOKEN.finditer(line):
                    tok = m.group(0)
                    exact = not HEX_FOLLOWS.match(line, m.end())
                    if exact and tok.lower() in CHIP_CONSTANTS:
                        continue
                    bad.append(tok)
                if bad and not any(r.search(line) for r in self.label_res):
                    if self.os_ids:
                        hint = "name the OS on this line, e.g. 'OS %s'" % self.os_ids[0]
                    else:
                        hint = "there is no OS folder whose id could label it"
                    self.err(f, i, "R6", "unlabelled firmware literal %s; %s" % (", ".join(bad[:4]), hint))

    # ------------------------------------------------------------------ R7: twins
    def check_twins(self):
        for f in self.files:
            v = self.os_of(f)
            if v is None:
                continue
            rel = f[len("os/%s/" % v):]
            if posixpath.basename(rel) != "README.md" and rel in self.fileset:
                self.err(rel, 0, "R7", "twin of %s: only README.md may exist both in the shared tree "
                         "and inside an OS folder" % f)

    # ------------------------------------------------------------------ R8: nothing inherited
    def code_and_data_files(self, v):
        base = "os/%s/" % v
        out = []
        for f in self.files:
            if not f.startswith(base):
                continue
            rel = f[len(base):]
            parts = rel.split("/")
            if rel == "profile.sh":
                out.append(f)
            elif len(parts) == 2 and parts[0] == "build" and rel.endswith((".py", ".json")):
                out.append(f)
            elif parts[0] == "scripts" and len(parts) >= 2 and rel.endswith((".java", ".py", ".sh")):
                out.append(f)
            elif parts[0] == "analysis" and len(parts) >= 2:
                out.append(f)
        return out

    def rederived(self, v):
        path = "os/%s/rederived.md" % v
        vals = set()
        if path in self.fileset:
            for line in self.lines(path) or []:
                s = line.strip()
                if not s.startswith("|") or re.match(r"^\|[\s|:\-]*$", s):
                    continue
                first = s.split("|")[1]
                vals.update(t.lower() for t in re.findall(r"0x[0-9A-Fa-f]+", first))
        return vals

    def check_inheritance(self):
        if len(self.os_dirs) < 2:
            return
        literals, allowed = {}, {}
        for v in self.os_dirs:
            seen = {}
            for f in self.code_and_data_files(v):
                for i, line in enumerate(self.lines(f) or [], 1):
                    for m in R8_LITERAL.finditer(line):
                        seen.setdefault(m.group(0).lower(), (f, i))
            literals[v] = seen
            allowed[v] = self.rederived(v)
        for ai, a in enumerate(self.os_dirs):
            for b in self.os_dirs[ai + 1:]:
                ok = CHIP_CONSTANTS | allowed[a] | allowed[b]
                for lit in sorted(set(literals[a]) & set(literals[b])):
                    if lit in ok:
                        continue
                    fb, ib = literals[b][lit]
                    fa, ia = literals[a][lit]
                    self.err(fb, ib, "R8", "firmware literal %s also appears in %s:%d; a value found again "
                             "in another version goes into rederived.md with its evidence" % (lit, fa, ia))
                self.compare_builders(a, b)

    def compare_builders(self, a, b):
        for name in BUILDER_FILES:
            pa, pb = "os/%s/build/%s" % (a, name), "os/%s/build/%s" % (b, name)
            if pa not in self.fileset or pb not in self.fileset:
                continue
            ta, tb = parse_python(self, pa, "R8"), parse_python(self, pb, "R8")
            if ta is None or tb is None:
                continue
            da, db = top_level_defs(ta), top_level_defs(tb)
            for d in sorted(set(da) | set(db)):
                if d not in da:
                    self.err(pb, db[d][0][1], "R8", "%s is not in %s" % (d, pa))
                elif d not in db:
                    self.err(pa, da[d][0][1], "R8", "%s is not in %s" % (d, pb))
                elif [x[0] for x in da[d]] != [x[0] for x in db[d]]:
                    self.err(pb, db[d][0][1], "R8", "%s differs from %s (string constants masked)" % (d, pa))

    # ------------------------------------------------------------------ R9: nothing ignored or local-only
    def check_ignored(self):
        for f in git_list(self.root, ["ls-files", "-z", "--cached", "--ignored", "--exclude-standard"]):
            self.err(f, 0, "R9", "tracked file matches an ignore rule")
        for f in self.files:
            parts = f.split("/")
            for c in parts:
                if c.lower() in LOCAL_ONLY_DIRS:
                    self.err(f, 0, "R9", "path component %r is for local files only" % c)
                    break
            base = parts[-1].lower()
            for g in FIRMWARE_NAME_GLOBS:
                if fnmatch.fnmatchcase(base, g):
                    self.err(f, 0, "R9", "file name matches %s, a firmware or manual file kind" % g)
                    break

    # ------------------------------------------------------------------ run
    def run(self):
        self.check_folders()
        self.check_links()
        self.check_os_paths_in_text()
        self.check_hashes()
        self.check_labels()
        self.check_twins()
        self.check_inheritance()
        self.check_ignored()
        return sorted(set(self.errors), key=lambda e: (e[0], e[1], int(e[2][1:]), e[3]))


# ---------------------------------------------------------------------- module helpers
def git_list(root, args):
    try:
        out = subprocess.run(["git", "-C", root] + args, stdout=subprocess.PIPE,
                             stderr=subprocess.PIPE, check=True).stdout
    except (OSError, subprocess.CalledProcessError) as e:
        sys.stderr.write("error: git %s failed in %s: %s\n" % (" ".join(args), root, e))
        sys.exit(2)
    return [p.decode("utf-8", "surrogateescape") for p in out.split(b"\0") if p]


def list_files(root):
    listed = git_list(root, ["ls-files", "-z", "--cached", "--others", "--exclude-standard"])
    return sorted(set(f for f in listed if os.path.lexists(os.path.join(root, f))))


def starts_repo_path(line, pos):
    """True when the 'os/' at pos begins a path from the repository root: at the start of a word,
    or after '../', './', '~/', '/', '$VAR/' or '<placeholder>/'. Not inside another folder's path
    (for example a Ghidra install's .../os/<platform>/)."""
    if pos == 0:
        return True
    c = line[pos - 1]
    if c != "/":
        return not (c.isalnum() or c in "_.-")
    k = pos - 1
    while k > 0 and line[k - 1] not in PATH_COMPONENT_STOP:
        k -= 1
    comp = line[k:pos - 1]
    return comp in ("", ".", "..", "~") or comp.startswith(("$", "<"))


def markdown_lines(lines):
    for kind, line, _ in numbered_markdown_lines(lines):
        yield kind, line


def numbered_markdown_lines(lines):
    """Yield (kind, line, number); kind is 'fence' for fence lines and fenced code, else 'text'."""
    fence = None
    for i, line in enumerate(lines, 1):
        s = line.lstrip()
        if fence is None:
            m = FENCE_RE.match(s)
            if m and not (m.group(1)[0] == "`" and "`" in m.group(2)):
                fence = m.group(1)
                yield "fence", line, i
                continue
            yield "text", line, i
        else:
            m = FENCE_RE.match(s)
            if m and m.group(1)[0] == fence[0] and len(m.group(1)) >= len(fence) and not m.group(2).strip():
                fence = None
            yield "fence", line, i


def slug(heading):
    """GitHub's heading anchor: lowercase, keep letters, digits, spaces, '-' and '_', spaces to '-'."""
    h = heading.strip()
    h = re.sub(r"<[^>]+>", "", h)
    h = re.sub(r"!?\[([^\]]*)\]\([^)]*\)", r"\1", h)
    h = h.replace("`", "")
    h = html.unescape(h).lower()
    out = []
    for ch in h:
        if ch in "-_ ":
            out.append(ch)
        elif ch.isalnum() or unicodedata.category(ch)[0] in "LN":
            out.append(ch)
    return "".join(out).replace(" ", "-")


def shell_value(raw):
    """The value of a plain NAME=value line: quotes removed, a trailing comment dropped."""
    s = raw.strip()
    if s[:1] in ("'", '"'):
        end = s.find(s[0], 1)
        return s[1:end] if end > 0 else s[1:]
    m = re.match(r"^(.*?)(?:\s+#.*)?$", s)
    return m.group(1).strip()


def lower(x):
    return x.lower() if isinstance(x, str) else x


def str_value(node):
    if PY38:
        if isinstance(node, ast.Constant) and isinstance(node.value, str):
            return node.value
        return None
    if isinstance(node, ast.Str):
        return node.s
    return None


def string_constants(node):
    return [s for s in (str_value(n) for n in ast.walk(node)) if s is not None]


def masked_dump(node):
    node = copy.deepcopy(node)
    for n in ast.walk(node):
        if PY38:
            if isinstance(n, ast.Constant) and isinstance(n.value, str):
                n.value = MASK
                if hasattr(n, "kind"):
                    n.kind = None
        elif isinstance(n, ast.Str):
            n.s = MASK
    return ast.dump(node)


def top_level_defs(tree):
    defs = {}
    for node in tree.body:
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            defs.setdefault(node.name, []).append((masked_dump(node), node.lineno))
    return defs


def parse_python(checker, path, rule):
    ls = checker.lines(path)
    if ls is None:
        checker.err(path, 0, rule, "cannot read the file as text")
        return None
    try:
        return ast.parse("\n".join(ls), filename=path)
    except (SyntaxError, ValueError) as e:
        checker.err(path, getattr(e, "lineno", 0) or 0, rule, "cannot parse: %s" % e)
        return None


def main(argv=None):
    here = os.path.dirname(os.path.abspath(__file__))
    ap = argparse.ArgumentParser(description="Check the OS folder rules (R1-R10) of this repository.")
    ap.add_argument("--root", default=os.path.dirname(here),
                    help="repository to check (default: the one holding this script)")
    args = ap.parse_args(argv)
    root = os.path.abspath(args.root)
    checker = Checker(root)
    errors = checker.run()
    for path, line, rule, msg in errors:
        print("%s:%d: %s: %s" % (path, line, rule, msg))
    if errors:
        sys.stderr.write("check_os_folders: %d error(s)\n" % len(errors))
        return 1
    print("ok: %d files, %d links checked" % (len(checker.files), checker.links))
    return 0


if __name__ == "__main__":
    sys.exit(main())
