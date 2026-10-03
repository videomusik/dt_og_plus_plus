# Toolchain: from a fresh clone to a built image, and to an analysed one

Follow the sections in order; each ends with a check. Everything here works on files. No step
connects to a Digitakt or changes one.

Written for **macOS on Apple Silicon**, where it is tested, with notes for Linux and for Windows
(through WSL2) in [section 7](#7-linux-and-windows). Tested with Ghidra 12.1.3, OpenJDK 21 and the
macOS system Python 3.

Building DT OG++ needs only sections 0–3. Sections 4–6 are for analysing the firmware yourself; the
notes that analysis produced, and how to re-check them, start at [`notes/README.md`](../notes/README.md).

Everything recorded about one OS version (the name and hashes of its stock file, its Ghidra projects
and their reference numbers, and worked examples) lives in that version's OS folder, `os/<os>/`
([os/README.md](../os/README.md)); the stock file itself stays in `sysex/` and the projects in
`work/ghidra/`. In the commands below, `<os>` is an OS folder name, and
`<image>` is either `<os>` (that OS's stock file) or `<os>:<file>.syx` (any other file of that OS,
such as a build); the grammar is in [os/README.md](../os/README.md#choosing-the-os-on-the-command-line).

---

## 0. What you need

### To build

| Need | macOS | Linux (Debian/Ubuntu) | Check |
|---|---|---|---|
| bash (3.2 or later), `cmp`, `tail`, `wc`, `grep`, `sed` | built in | built in | `bash --version` |
| C compiler `cc`, `patch`, `git` | **Xcode Command Line Tools**: `xcode-select --install` | `sudo apt install build-essential git` | `cc --version`, `patch --version`, `git --version` |
| `python3`, 3.7 or later, standard library only | built in (`/usr/bin/python3`) | `sudo apt install python3` | `python3 --version` |
| `shasum` or `sha256sum` | `shasum` is built in | `sha256sum` is built in | `shasum -a 256 --version` |

`git` is needed once, to clone the firmware-tool source at its pinned commit (section 2); that is the
only step that needs network access. The build itself needs no binutils and no Ghidra.

### To analyse (optional)

| Need | For | How to get it |
|---|---|---|
| Ghidra 12.1.3 + OpenJDK 21 | disassembly, decompilation, queries, emulation | section 4 |
| The ColdFire EMAC language extension | decoding the MAC-heavy audio code | `./scripts/ghidra_lang_ext.sh` (section 4c) |
| m68k binutils | the independent second decoder (`./scripts/disasm.sh`), and regenerating an OS folder's `docs/patch_listing.md` | section 5 |
| The emulator harnesses | stepping code in Ghidra's emulator | nothing extra; see `os/<os>/scripts/emu/README.md` |
| Swift (`swiftc`) with PDFKit, **macOS only** | the manual → markdown pipeline | the Xcode Command Line Tools; section 6 |
| Homebrew | installing Ghidra and binutils on macOS | https://brew.sh (installs to `/opt/homebrew`) |

⚠️ macOS: `/opt/homebrew/bin` is on the `PATH` of interactive shells only. Scripts and agents that run
non-interactively must use absolute paths or add it themselves; the Ghidra wrappers here default to
the Homebrew locations.

---

## 1. The repo and your stock OS file

Every path in this repo is relative to the repo root, and every script finds the root from its own
location, so you can run them from anywhere. These folders are gitignored and yours alone:

```
sysex/      your stock OS files (one per OS folder)
tool/       the firmware tool, built here (section 2)
out/        build output, one folder per OS (out/<os>/)
work/       extracted sections, Ghidra projects, analysis output (work/dt_<os>*/, work/ghidra/dt_<os>*/)
manuals/    manual PDFs you supply (section 6)
```

Elektron's OS updates are proprietary and are **not** in this repo. Download the Digitakt OS update
of a version that has an OS folder ([os/README.md](../os/README.md#the-os-folders)) from Elektron's
support pages for the Digitakt (the original model), and save the `.syx` under the name its OS folder
gives (`OS_STOCK_SYX_DEFAULT` in `os/<os>/profile.sh`). OS 1.52A:

```
sysex/Digitakt_OS1.52A.syx
```

Treat it as a read-only original: never overwrite it, never commit it, never share it, modified or
not. The same goes for everything built or extracted from it.

**Check:**

```sh
shasum -a 256 <file>      # Linux: sha256sum <file>
```

must print the SHA-256 given on the OS folder's reference page, for a file of the size given there
(OS 1.52A: [os/1.52A/docs/reference.md](../os/1.52A/docs/reference.md#the-stock-file)).
`./scripts/inspect.sh <os>` checks the same hash and prints `stock file ok`. The scripts and the
build refuse any other file as the stock image, because every address in an OS folder belongs to
exactly that OS's stock file.

---

## 2. The firmware tool

[elektron-firmware-tool](https://github.com/mischa85/elektron-firmware-tool) (MIT License) is a
small C tool that decodes the SysEx transport of an OS update, verifies every checksum, decompresses
the sections, and rebuilds a `.syx` from modified sections. It never talks to a device.

Clone it once, next to this repository, and check out the pinned commit. From the repo root:

```sh
git clone https://github.com/mischa85/elektron-firmware-tool ../elektron-firmware-tool
git -C ../elektron-firmware-tool checkout 065d18f
```

Then

```sh
bash build/build_tool.sh
```

copies the clone's source files into a build folder under `tool/`, checks the SHA-256 of every one
against the pinned commit, applies `build/tool_patches/cap_window_1mb.patch` to the copies, and
compiles `tool/bin/elektron-firmware-tool-capped`. It never clones or downloads anything and never
changes your clone; if the clone is missing or not at `065d18f`, it stops and prints the two git
commands above. `--src DIR` builds from a clone in another folder. Details: [`building.md`](building.md).

**Why a pinned commit.** Each OS folder's reference DT OG++ image is packed by this tool build. Later
upstream commits restructure the compressor (the patch no longer applies to them) and rename section 2
from "DSP" to "bootstrap". The scripts here match section files by id prefix (`section_2_*`), so a
renamed section does not break them, but whether a newer tool packs the same bytes is untested.

**Why the capped build.** The upstream compressor's optimal parser is unbounded, so its
back-references can reach further than any in Elektron's own image. The patch caps the window
at 1 MB (`#define MAX_OFFSET 0x100000` and a `break` in the match search), so the packed file stays
inside what the Digitakt's own decompressor is known to handle (in OS 1.52A, Elektron's own
compressed MAIN OS keeps its furthest back-reference just under that cap). That reach, the uncapped
one and what the cap costs were measured
on OS 1.52A: [os/1.52A/docs/reference.md](../os/1.52A/docs/reference.md#the-capped-tool-on-this-image).
Measure again for a new OS before its first build: the cap is backed only by what that OS's own stock
image is seen to use.

The tool's commands, for reference:

| Goal | Command |
|---|---|
| Summary | `tool/bin/elektron-firmware-tool-capped -i <in.syx>` |
| Full report | `... -v -i <in.syx>` |
| Extract all sections | `... -i <in.syx> -o <outdir>` (one section: `-d 3 -o <outdir>`) |
| Rebuild with a replaced section | `... -i <in.syx> -c 3 <section.bin> -o <out.syx>` |

A rebuilt `.syx` never matches the original byte for byte: the tool's compressor packs tighter than
Elektron's, so even an unmodified rebuild has a smaller container (OS 1.52A sizes:
[os/1.52A/docs/reference.md](../os/1.52A/docs/reference.md#the-round-trip)). Compare decompressed
sections, never containers.

**Check:** `./scripts/inspect.sh <os>` prints `checksums : ok` (see section 3).

---

## 3. Build, inspect, extract, round trip

- **Building DT OG++**: [`building.md`](building.md) (`python3 os/<os>/build/build.py`, then
  `python3 os/<os>/build/verify.py`).
- **Inspecting, extracting and round-tripping** any image, stock or built:
  [`extract_sections.md`](extract_sections.md).

```sh
./scripts/inspect.sh <os>       # summary of your stock file; must say "checksums : ok"
./scripts/extract.sh <os>       # sections + report.txt -> work/dt_<os>/
./scripts/roundtrip.sh <os>     # rebuild unmodified -> verify -> re-extract -> cmp; must print "round-trip OK"
```

OS 1.52A: `python3 os/1.52A/build/build.py`, `./scripts/inspect.sh 1.52A`, `./scripts/extract.sh 1.52A`
(into `work/dt_1.52A/`) and `./scripts/roundtrip.sh 1.52A`.

**Check:** the MAIN OS section file exists in `work/dt_<os>/`, with the size and SHA-256 given on the
OS folder's reference page (`extract.sh` checks the hash and prints `MAIN OS ok`; OS 1.52A:
`work/dt_1.52A/section_3_MAIN_OS.bin`, [os/1.52A/docs/reference.md](../os/1.52A/docs/reference.md#the-stock-file)),
and `roundtrip.sh` exits 0. Do not modify firmware with a toolchain whose round trip fails.

---

## 4. Ghidra: disassembly, decompilation, queries, emulation

The Digitakt's processor is a Freescale (NXP) ColdFire MCF54415: 68k family, big-endian, with the
EMAC multiply-accumulate unit. Ghidra is the reverse-engineering suite used here, driven headless by
the wrappers in `scripts/`. It needs JDK 21. Nothing the build does depends on it.

The decompiled C and the Ghidra projects contain Elektron's firmware. They stay in `work/`; never
commit them. The notes quote only short, labelled excerpts, never whole functions
([notes/README.md](../notes/README.md#decompiler-excerpts)).

### 4a. Install (JDK included)

macOS: Ghidra is a Homebrew **formula** (not a cask); it depends on `openjdk@21` and installs it.

```sh
brew install ghidra
```

Install folder: `/opt/homebrew/opt/ghidra/libexec/`. Entry points: `ghidraRun` (GUI, also linked
into `/opt/homebrew/bin`) and `support/analyzeHeadless` (headless; **not** linked, so the wrappers
call it by full path).

Linux: `sudo apt install openjdk-21-jdk`, download the 12.1.3 release zip from
https://github.com/NationalSecurityAgency/ghidra/releases, unzip it, for example to
`$HOME/opt/ghidra_12.1.3_PUBLIC`, and use that as the install folder below.

### 4b. Environment

The wrappers default to the Homebrew locations; set these to use others:

```sh
export GHIDRA_INSTALL_DIR=/opt/homebrew/opt/ghidra/libexec     # Linux: e.g. $HOME/opt/ghidra_12.1.3_PUBLIC
export JAVA_HOME=/opt/homebrew/opt/openjdk@21                  # Linux: e.g. /usr/lib/jvm/java-21-openjdk-amd64
```

⚠️ Without `JAVA_HOME`, headless Ghidra cannot find Homebrew's keg-only JDK and, having no terminal to
ask, exits with "Unable to locate a Java Runtime".

First GUI launch on macOS: if Gatekeeper blocks it, run
`xattr -dr com.apple.quarantine "$GHIDRA_INSTALL_DIR"`. If the GUI asks for a JDK path, give it
`$JAVA_HOME`.

PyGhidra is not needed: every script here is Java, which Ghidra compiles itself.

**Check:** `"$GHIDRA_INSTALL_DIR"/support/analyzeHeadless` with no arguments prints the Java version
and "Headless Analyzer Usage", and
`grep -c Coldfire "$GHIDRA_INSTALL_DIR"/Ghidra/Processors/68000/data/languages/68000.ldefs` prints a
non-zero count.

### 4c. The ColdFire EMAC language extension (install it before analysing audio code)

Ghidra's stock ColdFire spec cannot decode `movclr.l ACCx,Rx` (the instruction that drains and
clears an accumulator, which is how most MAC audio loops end), cannot match the `mac.l`-with-load
form whose `Rw` is an address register, and mis-sizes the `(d16,An)` forms of `mac.l` and `msac.l`.
Disassembly stops or derails there. `scripts/ghidra_ext/` holds a **four-hunk** patch that fixes
all four gaps; build and install it with:

```sh
./scripts/ghidra_lang_ext.sh            # build + install
./scripts/ghidra_lang_ext.sh --check    # report what is installed
```

It patches a **copy** of Ghidra's `68000.sinc`, compiles it with Ghidra's own `support/sleigh`, and
installs a separate language `68000:BE:32:ColdfireEMAC` into Ghidra's **user** extension folder, not
the install tree, so a Ghidra upgrade cannot silently revert it. Re-run it after upgrading Ghidra; if
the spec changed upstream, the patch fails loudly. What the patch does, its deliberate limits, and
its Apache-2.0 notice: [`scripts/ghidra_ext/README.md`](../scripts/ghidra_ext/README.md).

`ghidra_langid()` in `scripts/common.sh` picks the language: `GHIDRA_LANG_VARIANT=emac` selects the
extension, and also gives the analysis its own project folder, so the stock one stays as a control.
A program is bound to the language it was imported with, so a variant always means a fresh import.

### 4d. The projects and how to make them

All projects live in `work/ghidra/<project>/` and their text output in `work/ghidra/out/<project>/`.
The notes ([`notes/README.md`](../notes/README.md#which-ghidra-project-a-number-came-from)) name them
by these folder names, with `<os>` the OS folder name:

| Project | What it is | Made by |
|---|---|---|
| `dt_<os>` | the stock MAIN OS, stock ColdFire language: the control | `./scripts/ghidra_analyze.sh <os> main` |
| `dt_<os>_emac` | the stock MAIN OS, ColdFire+EMAC language: **use it for anything touching audio code** | `GHIDRA_LANG_VARIANT=emac ./scripts/ghidra_analyze.sh <os> main` |
| `dt_<os>_seed` | a copy of `_emac` with the code gaps seeded: the most complete MAIN OS map, and the landing-pad candidate list | recipe below |
| `dt_<os>_dsp` (`_dsp_emac`) | the `dsp` section alone, header stripped, at its run base | `./scripts/ghidra_analyze.sh <os> dsp` (prefix `GHIDRA_LANG_VARIANT=emac` for `_dsp_emac`) |
| `dt_<os>_sram` | the on-chip SRAM as the `dsp` section's code sees it | recipe below |
| `dt_<os>_updater` | the `updater` section at its load base | `./scripts/ghidra_analyze.sh <os> updater` |
| `dt_<os>-<build>` (and the same suffixes) | another file of that OS, such as a build: the image `<os>:<path>/<build>.syx` | `./scripts/ghidra_analyze.sh <os>:out/<os>/<build>.syx main`, after `./scripts/extract.sh` on the same image |

The derived name is the image's work-folder name (`dt_<os>` for the stock file, `dt_<os>-<name>` for
`<os>:<path>/<name>.syx`), then `_<section>` for every section other than `main`, then `_<variant>`
when `GHIDRA_LANG_VARIANT` is set. `_seed` and `_sram` are made by hand, as below. Each OS folder's
projects, recipes and reference numbers: `os/<os>/notes/analysis_reference.md` (OS 1.52A:
[os/1.52A/notes/analysis_reference.md](../os/1.52A/notes/analysis_reference.md#the-ghidra-projects)).

`ghidra_analyze.sh` imports the section at its load base (`os_section_meta()` in the OS folder's
`profile.sh` holds file, base, header strip and entry per section; `section_meta()` in
`scripts/common.sh` reads it), runs full auto-analysis, then three post-scripts: `NameFromRtti.java`
(recovers the C++ classes and vtables from GCC RTTI and names the virtual functions
`Class::vfunc_N`), `DumpDecompiled.java mkfunc:<entry>` (creates the entry function, which a raw
binary does not declare) and `DumpFunctions.java` (writes `summary.txt`, `functions.tsv`,
`strings.tsv` and `errors.tsv`; `NameFromRtti.java` writes `rtti_classes.tsv` next to them). Later
runs on an existing project skip the analysis and only re-run the dumps. Delete a project folder to
start over.
The MAIN OS import and analysis take a few minutes; the base analysis is deterministic, so a fresh
import gives the same counts (checked on OS 1.52A:
[os/1.52A/notes/analysis_reference.md](../os/1.52A/notes/analysis_reference.md#determinism)).

**The section rows.** Each row of `os_section_meta` reads `<section id, or a file name> <load base>
<strip bytes> <entry, or ->`. Derive every value from generated data, without guessing (the same
recipe is in `scripts/common.sh`):

- base = the section's `dst=` in `work/dt_<os>/report.txt` (the `extract.sh` `-v` output). For a blob
  that is copied elsewhere to run, `dst` is read as a staging address, and the real run base is the one
  its own header names. OS 1.52A: the DSP section, word 2 (big-endian) of its 24-byte inner header,
  `xxd -l 24 work/dt_1.52A/section_2_*.bin`.
- strip = the size of such an inner header; 0 when the section runs where it loads.
- entry = the entry word of the section's header where it has one (OS 1.52A: the first 32-bit word,
  `xxd -l 4 ...`, for `main` and `updater`); `-` when there is none.
- `sram`, where an OS folder defines it, is not a section but an on-chip SRAM image assembled by that
  OS folder's `scripts/build_sram_image.py` (OS 1.52A: base the start of the on-chip SRAM, entry the
  DSP code's run base).

`ghidra_analyze.sh`, `ghidra_query.sh` and `ghidra_decompile.sh` look for a Ghidra script in
`scripts/ghidra/` (the shared scripts) and in the OS folder's `os/<os>/scripts/ghidra/`; a name found
in both folders, or in neither, is refused before Ghidra starts. `SeedCodeGaps.java`,
`CurateDsp.java` and `FindDeadFunctions.java` live in the OS folder because their tables (code
windows, known-live functions) belong to that OS's image. The curation scripts (`SeedCodeGaps`,
`CurateDsp`, `FixDspResidual`) change the project they run on, so run them only on a project made
for them (the `_seed` copy, the `_sram` import), never on `dt_<os>` or `dt_<os>_emac`; to re-check a
curated project, run them on a copy of it.

`GHIDRA_PROJECT=<folder>` makes any wrapper use `work/ghidra/<folder>/` directly; that is how the two
projects below are addressed. It must be the image's project name (`dt_<os>`, or `dt_<os>-<name>` for
a file image) or start with that name followed by `_`, such as `dt_<os>_seed`; any other value is
refused before Ghidra starts. A copied project keeps its original project file name inside the
folder (a copy of `dt_<os>_emac` keeps that project's `.gpr`, whose name has `_` for every `.` and
`-`; OS 1.52A: `dt_1_52A_emac.gpr`), and the wrappers find it.

**`dt_<os>_seed`**: seed disassembly at the start of every undefined range inside the code windows
(the table `WINDOWS` in the OS folder's `SeedCodeGaps.java`), validate each new function and undo it
if it contains a bad instruction, repeat to a fixed point. `SeedCodeGaps.java` changes the project,
so it runs on a copy:

```sh
GHIDRA_LANG_VARIANT=emac ./scripts/ghidra_analyze.sh <os> main          # the _emac project, if not made yet
cp -R work/ghidra/dt_<os>_emac work/ghidra/dt_<os>_seed
GHIDRA_PROJECT=dt_<os>_seed ./scripts/ghidra_query.sh <os> main SeedCodeGaps
GHIDRA_PROJECT=dt_<os>_seed ./scripts/ghidra_query.sh <os> main FindDeadFunctions
GHIDRA_PROJECT=dt_<os>_seed ./scripts/ghidra_analyze.sh <os> main      # optional: re-dump functions.tsv / summary.txt
```

`FindDeadFunctions` lists the unreferenced functions and checks that the known-live functions in
its table (`KNOWN_LIVE`) are classified live. That list is a set of candidates to read, never free
space to use: code reached through a computed address is invisible to all three of its evidence
sources.

**`dt_<os>_sram`**: assemble the SRAM image with the OS folder's builder, import it on the EMAC
language at the base and entry of the profile's `sram` row, then curate it (both curation scripts
change the project):

```sh
./scripts/extract.sh <os>
python3 os/<os>/scripts/build_sram_image.py                            # -> work/dt_<os>/sram_unified.bin
GHIDRA_LANG_VARIANT=emac GHIDRA_PROJECT=dt_<os>_sram ./scripts/ghidra_analyze.sh <os> sram
GHIDRA_PROJECT=dt_<os>_sram ./scripts/ghidra_query.sh <os> sram CurateDsp
GHIDRA_PROJECT=dt_<os>_sram ./scripts/ghidra_query.sh <os> sram FixDspResidual
GHIDRA_PROJECT=dt_<os>_sram ./scripts/ghidra_analyze.sh <os> sram      # re-dump: compare the error bookmarks
```

`CurateDsp.java` clears every instruction outside its code windows (its table `CODE`) and removes the
phantom mid-instruction references that jump-table recovery creates. In OS 1.52A the windows are the
updater stub that section 2 shares, section 2's code, and section 2's last two routines; section 2's
own data lies between the last two
([os/1.52A/notes/section2_map.md](../os/1.52A/notes/section2_map.md#the-unified-sram-view)).
`FixDspResidual.java` re-forms the last few conflicting instructions one at a time.

**Reference numbers.** Compare your own run against the counts of the saved project (from
`summary.txt` and the query outputs), as a later `ghidra_analyze.sh` run on it re-dumps them; the
summary of an import's first run can count a little less (it did for both OS 1.52A MAIN OS imports).
Each OS folder's numbers: OS 1.52A:
[os/1.52A/notes/analysis_reference.md](../os/1.52A/notes/analysis_reference.md#reference-numbers).

### 4e. Decompiling and querying

```sh
./scripts/ghidra_decompile.sh <image> <selector>...                    # e.g. class:<Class> 'str:<text>' addr:<address>
GHIDRA_LANG_VARIANT=emac ./scripts/ghidra_decompile.sh <image> 're:.*'  # every function, EMAC project
SECTION=dsp ./scripts/ghidra_decompile.sh <image> 're:.*'               # every DSP function
```

writes one `.c` file per function (with callers, callees and referenced strings) plus an
`index.tsv` to `work/ghidra/out/<project>/decomp/`. Selectors: `class:`, `re:`, `str:`, `addr:`,
`xref:`, `callers:`, `mkfunc:` (see `scripts/ghidra/DumpDecompiled.java`). `mkfunc:` saves the new
function into the project.

```sh
./scripts/ghidra_query.sh <image> <section> <Script> [args]
./scripts/ghidra_query.sh <os> main RefDensityMap       <lo> <hi>          # where a large data region (.bss) is referenced
./scripts/ghidra_query.sh <os> main DumpRefsInRange     <lo> <hi>          # zoom into a stretch
./scripts/ghidra_query.sh <os> main FindDeadSpace       <lo> <hi> [min]    # undefined ranges of at least min bytes
./scripts/ghidra_query.sh <os> main FindAddressLiterals <lo> <hi>          # immediates in a window
```

runs one script from `scripts/ghidra/` or from the OS folder's `os/<os>/scripts/ghidra/` and writes
its listing to `work/ghidra/out/<project>/query/<Script>_<args>.txt`:

- `RefDensityMap`: where in a large data region references land, and the largest gaps between them;
- `DumpRefsInRange`: every referenced address in a stretch, with its sources and their functions;
- `FindDeadSpace`: undefined ranges that nothing references;
- `FindAddressLiterals`: every instruction whose *operand* holds a value inside a window;
- `FindDeadFunctions`, `SeedCodeGaps`, `CurateDsp`, `FixDspResidual`: see 4d.

Worked examples with real addresses, for decompiling, querying and disassembling: OS 1.52A:
[os/1.52A/notes/analysis_reference.md](../os/1.52A/notes/analysis_reference.md#worked-examples).

⚠️ **The first three report memory REFERENCES only.** Code reached through vtables or jump tables,
buffer interiors reached through a base pointer, and, the trap, regions addressed by **immediates**
(OS 1.52A examples: `addi.l #0x8000ba00,d0`, `adda.l #0x80008800,a0`; Ghidra records such values
as scalar operands, not references) all look completely unreferenced while being very much in use.
**Run `FindAddressLiterals` before concluding that any region is unused**, and read a zero from it as
"no literal in this window", not as proof.

### 4f. The emulator harnesses

`./scripts/ghidra_emu.sh <os> <Harness> [args]` runs one of the Ghidra-emulator harnesses in the OS
folder's `os/<os>/scripts/emu/` against that OS's `_emac` project, read-only (`dt_<os>_emac`; a
`GHIDRA_PROJECT` of the same OS, such as `dt_<os>_seed`, also works). It takes an OS id, not a file
image: a build's extracted MAIN OS (`work/dt_<os>-<build>/`) is passed as a harness argument.
The pad harnesses step DT OG++ code (read from your build's extracted MAIN OS, or built into the
harness) and check its stack discipline, registers and results; the audio-ISR probes explore stock
code. What each one checks: the OS folder's `scripts/emu/README.md` (OS 1.52A:
[os/1.52A/scripts/emu/README.md](../os/1.52A/scripts/emu/README.md)).

Close the Ghidra GUI on a project before running any wrapper on it: a project is locked while it is
open, for headless runs as well.

---

## 5. m68k binutils: the independent decoder

Install it even if you have Ghidra: **a second opinion catches what Ghidra alone cannot**. Ghidra
being self-consistent is not evidence that a decoding is right. GNU binutils implements ColdFire EMAC
completely, so it is the ground truth for anything load-bearing, and the thing to check a suspicious
instruction against before writing SLEIGH.

| | macOS | Debian/Ubuntu |
|---|---|---|
| Package | `brew install m68k-elf-binutils` | `sudo apt install binutils-m68k-linux-gnu` |
| Command names | `m68k-elf-objdump`, `m68k-elf-as`, ... | `m68k-linux-gnu-objdump`, `m68k-linux-gnu-as`, ... |
| Set | nothing (the default) | `export M68K_PREFIX=m68k-linux-gnu-` |

⚠️ Debian publishes `binutils-m68k-linux-gnu` for amd64 but not for arm64, so on an arm64 Linux machine
(for example a container on an Apple Silicon Mac) the package cannot be installed. There, build GNU
binutils from the GNU source release for the `m68k-elf` target instead (tested with 2.47 on Debian 12,
arm64). It gives the default `m68k-elf-*` names, so with `<dir>/bin` on the `PATH` nothing needs to
be set:

```sh
./configure --target=m68k-elf --prefix=<dir> --disable-nls --disable-werror --disable-gdb \
    --disable-gdbserver --disable-sim --disable-gprofng --disable-readline MAKEINFO=true
make MAKEINFO=true
make install MAKEINFO=true
```

Which scripts run binutils, and how they find it:

- `scripts/disasm.sh` uses `$M68K_PREFIX`, through the `M68K_AS`, `M68K_OBJCOPY` and `M68K_OBJDUMP`
  variables that `scripts/common.sh` defines. Any new script that runs binutils must use those
  variables too.
- `os/<os>/build/make_listing.py` (regenerates that OS folder's `docs/patch_listing.md`) takes the command on its command
  line: `--objdump m68k-linux-gnu-objdump` with the Debian/Ubuntu package.
- `os/<os>/build/build.py`, `os/<os>/build/verify.py` and every other script here need no binutils.

```sh
./scripts/disasm.sh <image> <start> <stop> [section=main] [file]
./scripts/disasm.sh <os> <start> <stop>                         # stock MAIN OS, load addresses
./scripts/disasm.sh <os> <start> <stop> dsp                     # the DSP section at its run address
./scripts/disasm.sh <os>:out/<os>/<build>.syx <start> <stop>    # a build's MAIN OS (extract it first)
```

The stock MAIN OS form is the same as

```sh
m68k-elf-objdump -D -b binary -m m68k:cfv4e --adjust-vma=<load base> \
    --start-address=<start> --stop-address=<stop> \
    work/dt_<os>/section_3_MAIN_OS.bin
```

with `<load base>` the MAIN OS load base from the OS folder's profile (`OS_MAIN_BASE`). Worked
examples with real addresses: OS 1.52A:
[os/1.52A/notes/analysis_reference.md](../os/1.52A/notes/analysis_reference.md#worked-examples).

⚠️ Use `-m m68k:cfv4e` for the MCF5441x (`m68k:isa-a:emac` also works; plain `m68k:cfv4` does not
decode EMAC). `objdump -i` lists the m68k variants unhelpfully as a column of bare `m68k`: just pass
`-m` and see whether it errors. Disassembly of a raw section is linear, so it is only right from a
known instruction boundary: start at an address Ghidra already reached, or at a function entry.

⚠️ objdump prints `remsl Dx,Dx,Dx` (the same register twice) for what is really the signed 32-bit
divide `divs.l`: ColdFire's divide and remainder share an encoding, and the remainder form requires
two different registers. objdump never prints a `divsl` mnemonic for this encoding.

---

## 6. The manual pipeline (macOS only)

`./scripts/manual_extract.sh` plus one agent per chapter turns a Digitakt manual PDF you own into a
greppable markdown corpus. It needs macOS: the tools are Swift programs using PDFKit, CoreGraphics
and ImageIO, and `swiftc` comes with the Xcode Command Line Tools. The runbook, the reference manual
edition (Digitakt User Manual OS1.50, `Digitakt_User_Manual_ENG_OS1.50_230301.pdf`, SHA-256
`8d085bac9be47d3c9fc639e43f54b1a2a413d0f9b1b73e9557f579b6f933a48e`) and the agent permissions are
in [`scripts/manual/README.md`](../scripts/manual/README.md).

---

## 7. Linux and Windows

**Linux.** The build and the analysis scripts use only bash, POSIX tools, Python's standard library,
`cc` and `patch`; none needs GNU-only or macOS-only options. The build (`build/build_tool.sh`,
`os/<os>/build/build.py`, `os/<os>/build/verify.py`) runs on Linux (Debian 12, arm64); the analysis
scripts are expected to work there but have not been run on Linux.

- Ghidra: the release zip and `openjdk-21-jdk` (section 4a); set `GHIDRA_INSTALL_DIR` and `JAVA_HOME`.
  The EMAC extension installs into Ghidra's user settings folder,
  `${XDG_CONFIG_HOME:-$HOME/.config}/ghidra/ghidra_12.1.3_PUBLIC/Extensions/`.
- binutils: with the Debian/Ubuntu package, set `M68K_PREFIX=m68k-linux-gnu-`; a source build for
  `m68k-elf` needs nothing set (section 5).
- Hashes: `sha256sum` instead of `shasum -a 256`.
- The manual pipeline does not run (no PDFKit).

**Windows.** Use WSL2 with Ubuntu and follow the Linux notes inside it; the scripts are bash and
expect POSIX paths. Clone this repo and elektron-firmware-tool inside the WSL file system, not under
`/mnt/c`, and make sure git does not convert line endings (clone both with
`git clone -c core.autocrlf=false <url> <folder>`): converted files break both the scripts and the
tool-source hash check in `build/build_tool.sh`. The Ghidra wrappers are expected to work headless
inside WSL (not tested). The manual pipeline does not run.

---

## 8. Out of scope: the instrument

Nothing in this document needs a Digitakt. Flashing a `.syx` (with Elektron Transfer, the device in
OS-upgrade mode) and hardware debugging (a BDM/JTAG probe on the CPU board's 26-pin header) are
deliberate, separate acts with real risk to the hardware. Never flash an image whose build failed a
check, never without your stock `.syx` at hand, and never without a recovery plan: read
[`SAFETY.md`](../SAFETY.md) and [`notes/flash_recovery.md`](../notes/flash_recovery.md) first. The
recovery route through the STARTUP menu needs the Digitakt's DIN MIDI port, not USB, so have a DIN
MIDI interface ready.
