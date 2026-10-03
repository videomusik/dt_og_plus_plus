# Toolchain: from a fresh clone to a built image, and to an analysed one

Follow the sections in order; each ends with a check. Everything here works on files. No step
connects to a Digitakt or changes one.

Written for **macOS on Apple Silicon**, where it is tested, with notes for Linux and for Windows
(through WSL2) in [section 7](#7-linux-and-windows). Tested with Ghidra 12.1.3, OpenJDK 21 and the
macOS system Python 3.

Building DT OG++ needs only sections 0–3. Sections 4–6 are for analysing the firmware yourself; the
notes that analysis produced, and how to re-check them, start at [`notes/README.md`](../notes/README.md).

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
| m68k binutils | the independent second decoder (`./scripts/disasm.sh`), and regenerating `docs/patch_listing.md` | section 5 |
| The emulator harnesses | stepping code in Ghidra's emulator | nothing extra; see `scripts/emu/README.md` |
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
sysex/      your stock OS file
tool/       the firmware tool, built here (section 2)
out/        build output
work/       extracted sections, Ghidra projects, analysis output
manuals/    manual PDFs you supply (section 6)
```

Elektron's OS updates are proprietary and are **not** in this repo. Download the Digitakt OS 1.52A
update from Elektron's support pages for the Digitakt (the original model) and save the `.syx` as:

```
sysex/Digitakt_OS1.52A.syx
```

Treat it as a read-only original: never overwrite it, never commit it, never share it, modified or
not. The same goes for everything built or extracted from it.

**Check:**

```sh
shasum -a 256 sysex/Digitakt_OS1.52A.syx      # Linux: sha256sum sysex/Digitakt_OS1.52A.syx
```

must print `01315133041dcdb8b432146190cc74fc8695c47d8466b0f31bd78cef96fa56a4` (1,162,400 bytes).
The scripts and the build refuse any other file as the stock image, because every address in this
repo belongs to exactly that file.

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

**Why a pinned commit.** The reference DT OG++ image was packed by this tool build. Later upstream
commits restructure the compressor (the patch no longer applies to them) and rename section 2 from
"DSP" to "bootstrap". The scripts here match section files by id prefix (`section_2_*`), so a
renamed section does not break them, but whether a newer tool packs the same bytes is untested.

**Why the capped build.** In Elektron's own Digitakt OS 1.52A image, no back-reference in the
compressed MAIN OS reaches further than 1,048,572 bytes (`0x0FFFFC`), just under 1 MB. The
upstream compressor's optimal parser is unbounded and reaches about 2.1 MB. The patch caps the
window at 1 MB (`#define MAX_OFFSET 0x100000` and a `break` in the match search), so the packed
file stays inside what the Digitakt's own decompressor is known to handle. It costs 322 bytes
(0.04 %).

The tool's commands, for reference:

| Goal | Command |
|---|---|
| Summary | `tool/bin/elektron-firmware-tool-capped -i <in.syx>` |
| Full report | `... -v -i <in.syx>` |
| Extract all sections | `... -i <in.syx> -o <outdir>` (one section: `-d 3 -o <outdir>`) |
| Rebuild with a replaced section | `... -i <in.syx> -c 3 <section.bin> -o <out.syx>` |

A rebuilt `.syx` never matches the original byte for byte: the tool's compressor packs tighter than
Elektron's (the stock container shrinks from 917,072 to 864,080 bytes on an unmodified rebuild).
Compare decompressed sections, never containers.

**Check:** `./scripts/inspect.sh` prints `checksums : ok` (section 3).

---

## 3. Build, inspect, extract, round trip

- **Building DT OG++**: [`building.md`](building.md) (`python3 build/build.py`, then
  `python3 build/verify.py`).
- **Inspecting, extracting and round-tripping** any image, stock or built:
  [`extract_sections.md`](extract_sections.md).

```sh
./scripts/inspect.sh            # summary of your stock file; must say "checksums : ok"
./scripts/extract.sh            # sections + report.txt -> work/dt_1.52A/
./scripts/roundtrip.sh          # rebuild unmodified -> verify -> re-extract -> cmp; must print "round-trip OK"
```

**Check:** `work/dt_1.52A/section_3_MAIN_OS.bin` exists (2,221,632 bytes, SHA-256
`59278368fbe86c9877fad68a578987050e21fc4b418a289cfa1d1351d8e864ee`; `extract.sh` checks it) and
`roundtrip.sh` exits 0. Do not modify firmware with a toolchain whose round trip fails.

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
by these folder names:

| Project | What it is | Made by |
|---|---|---|
| `dt_1.52A` | stock MAIN OS, stock ColdFire language: the control | `./scripts/ghidra_analyze.sh dt main` |
| `dt_1.52A_emac` | stock MAIN OS, ColdFire+EMAC language: **use it for anything touching audio code** | `GHIDRA_LANG_VARIANT=emac ./scripts/ghidra_analyze.sh dt main` |
| `dt_1.52A_seed` | a copy of `_emac` with the code gaps seeded: the most complete MAIN OS map, and the landing-pad candidate list | recipe below |
| `dt_1.52A_dsp` (`_dsp_emac`) | section 2 alone, header stripped, at its run base `0x80000ec0` | `./scripts/ghidra_analyze.sh dt dsp` (prefix `GHIDRA_LANG_VARIANT=emac` for `_dsp_emac`) |
| `dt_1.52A_sram` | the 64 KB on-chip SRAM as the section-2 code sees it (both crt0 init images, the updater, the DSP code) | recipe below |
| `dt_1.52A_updater` | section 4 at `0x80000400` | `./scripts/ghidra_analyze.sh dt updater` |

`ghidra_analyze.sh` imports the section at its load base (`section_meta()` in `scripts/common.sh`
holds file, base, header strip and entry per section), runs full auto-analysis, then three
post-scripts: `NameFromRtti.java` (recovers the C++ classes and vtables from GCC RTTI and names the
virtual functions `Class::vfunc_N`), `DumpDecompiled.java mkfunc:<entry>` (creates the entry
function, which a raw binary does not declare) and `DumpFunctions.java` (writes `summary.txt`,
`functions.tsv`, `strings.tsv` and `errors.tsv`; `NameFromRtti.java` writes `rtti_classes.tsv` next
to them). Later runs on an existing project skip the analysis and only re-run the dumps. Delete a
project folder to start over.
The MAIN OS import and analysis take a few minutes; the base analysis is deterministic, so a fresh
import gives the same counts.

`GHIDRA_PROJECT=<folder>` makes any wrapper use `work/ghidra/<folder>/` directly; that is how the two
projects below are addressed. A copied project keeps its original project file name inside the
folder (here `dt_1_52A_emac.gpr`), and the wrappers find it.

**`dt_1.52A_seed`**: seed disassembly at the start of every undefined range inside the two code
windows, validate each new function and undo it if it contains a bad instruction, repeat to a fixed
point. `SeedCodeGaps.java` changes the project, so it runs on a copy:

```sh
GHIDRA_LANG_VARIANT=emac ./scripts/ghidra_analyze.sh dt main          # the _emac project, if not made yet
cp -R work/ghidra/dt_1.52A_emac work/ghidra/dt_1.52A_seed
GHIDRA_PROJECT=dt_1.52A_seed ./scripts/ghidra_query.sh dt main SeedCodeGaps
GHIDRA_PROJECT=dt_1.52A_seed ./scripts/ghidra_query.sh dt main FindDeadFunctions
GHIDRA_PROJECT=dt_1.52A_seed ./scripts/ghidra_analyze.sh dt main      # optional: re-dump functions.tsv / summary.txt
```

**`dt_1.52A_sram`**: assemble the SRAM image, import it on the EMAC language at `0x80000000` with
entry `0x80000ec0`, then curate it (both curation scripts change the project):

```sh
./scripts/extract.sh
python3 scripts/build_sram_image.py                                    # -> work/dt_1.52A/sram_unified.bin
GHIDRA_LANG_VARIANT=emac GHIDRA_PROJECT=dt_1.52A_sram ./scripts/ghidra_analyze.sh dt sram
GHIDRA_PROJECT=dt_1.52A_sram ./scripts/ghidra_query.sh dt sram CurateDsp
GHIDRA_PROJECT=dt_1.52A_sram ./scripts/ghidra_query.sh dt sram FixDspResidual
GHIDRA_PROJECT=dt_1.52A_sram ./scripts/ghidra_analyze.sh dt sram      # re-dump: error bookmarks should now be 0
```

`CurateDsp.java` clears every instruction outside its code windows and removes the phantom
mid-instruction references that jump-table recovery creates. The windows are the updater stub that
section 2 shares, section 2's code, and section 2's last two routines; section 2's own data lies
between the last two ([`notes/section2_map.md`](../notes/section2_map.md#the-unified-sram-view)).
`FixDspResidual.java` re-forms the last few conflicting instructions one at a time.

**Reference numbers**, to compare your own run against (from `summary.txt` and the query outputs).
They count the saved project, as a later `ghidra_analyze.sh` run on it re-dumps them. For the two
MAIN OS imports the summary of the first run counts a little less: `dt_1.52A` 11,054 functions and
426,631 instructions, `dt_1.52A_emac` 11,063 and 431,212.

| Project | Functions | Instructions | In functions | Error bookmarks |
|---|---:|---:|---:|---:|
| `dt_1.52A` | 11,064 | 426,654 | 60.6 % | 47 |
| `dt_1.52A_emac` | 11,073 | 431,235 | 61.3 % | 1 |
| `dt_1.52A_seed` | 11,905 | | code windows 99.9 % disassembled | 1 |
| `dt_1.52A_dsp` | 132 | 5,557 | 61.7 % | 27 |
| `dt_1.52A_sram` | 150 | 6,486 | 29.2 % | 26 → 0 after curation |

- `dt_1.52A`: the RTTI walk finds 1,786 classes and 1,591 vtables; 46 of the 47 errors come from the
  EMAC gaps. The 47th, an unrelated `jsr`/`0x0000` data-in-code boundary at `0x40115fe6`, is the one
  error left on `_emac`.
- `dt_1.52A_seed`: `SeedCodeGaps` adds 832 functions and cuts the undefined bytes in the code windows
  (`0x400004b2`–`0x40162748` and `0x40210e4a`–`0x40211ef2`) from 47,238 to 1,234, rejecting one seed
  as data and adding no error bookmarks. `FindDeadFunctions` then lists 980 unreferenced functions
  (81,858 bytes), of which 204 are leaf functions of 16 bytes or more (10,186 bytes), and its check
  that ten known-live functions are classified live passes. That list is a set of candidates to read,
  never free space to use: code reached through a computed address is invisible to all three of its
  evidence sources.
- `dt_1.52A_dsp`: the 27 errors are not decoding gaps. They are calls into SRAM routines that the
  section alone does not contain (some live in the updater's low-SRAM stub, which the `_sram` image
  includes), and phantom mid-instruction references from jump-table recovery, which the curation
  scripts remove. That is why the `_sram` project exists.
- `dt_1.52A_sram`: straight after import it has 149 functions, 6,533 instructions and 26 error
  bookmarks, and the import's log says it could not create the entry function at `0x80000ec0`; the
  re-dump after curation creates it. Five of the 150 functions lie outside the code windows and have
  no code: the updater routine at `0x80000eaa`, three that analysis made in section 2's data, and
  `0x8000f010`, which is loaded from flash at run time.

### 4e. Decompiling and querying

```sh
./scripts/ghidra_decompile.sh dt class:MachineListView 'str:Slice Select' addr:0x4000b564
GHIDRA_LANG_VARIANT=emac ./scripts/ghidra_decompile.sh dt 're:.*'        # every function, EMAC project
SECTION=dsp ./scripts/ghidra_decompile.sh dt 're:.*'                     # every DSP function
```

writes one `.c` file per function (with callers, callees and referenced strings) plus an
`index.tsv` to `work/ghidra/out/<project>/decomp/`. Selectors: `class:`, `re:`, `str:`, `addr:`,
`xref:`, `callers:`, `mkfunc:` (see `scripts/ghidra/DumpDecompiled.java`). `mkfunc:` saves the new
function into the project.

```sh
./scripts/ghidra_query.sh dt main RefDensityMap       0x40214000 0x439902a0   # where .bss is referenced
./scripts/ghidra_query.sh dt main DumpRefsInRange     0x40214000 0x40240000   # zoom into a stretch
./scripts/ghidra_query.sh dt main FindDeadSpace       0x40000400 0x40214000 16
./scripts/ghidra_query.sh dt main FindAddressLiterals 0x8000edc8 0x8000f0b8   # immediates in a window
```

runs one script from `scripts/ghidra/` and writes its listing to
`work/ghidra/out/<project>/query/<Script>_<args>.txt`:

- `RefDensityMap`: where in a large data region references land, and the largest gaps between them;
- `DumpRefsInRange`: every referenced address in a stretch, with its sources and their functions;
- `FindDeadSpace`: undefined ranges that nothing references;
- `FindAddressLiterals`: every instruction whose *operand* holds a value inside a window;
- `FindDeadFunctions`, `SeedCodeGaps`, `CurateDsp`, `FixDspResidual`: see 4d.

⚠️ **The first three report memory REFERENCES only.** Code reached through vtables or jump tables,
buffer interiors reached through a base pointer, and, the trap, regions addressed by **immediates**
(`addi.l #0x8000ba00,d0`, `adda.l #0x80008800,a0`, which Ghidra records as scalar operands, not
references) all look completely unreferenced while being very much in use. **Run
`FindAddressLiterals` before concluding that any region is unused**, and read a zero from it as "no
literal in this window", not as proof.

### 4f. The emulator harnesses

`./scripts/ghidra_emu.sh <Harness> [args]` runs one of the Ghidra-emulator harnesses in
`scripts/emu/` against the `_emac` project, read-only. The pad harnesses step DT OG++ code (read
from your build's extracted MAIN OS, or built into the harness) and check its stack discipline,
registers and results; the audio-ISR probes explore stock code. What each one checks:
[`scripts/emu/README.md`](../scripts/emu/README.md).

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
- `build/make_listing.py` (regenerates `docs/patch_listing.md`) takes the command on its command
  line: `--objdump m68k-linux-gnu-objdump` with the Debian/Ubuntu package.
- `build/build.py`, `build/verify.py` and every other script here need no binutils.

```sh
./scripts/disasm.sh 0x4006a570 0x4006a590                  # stock MAIN OS, load addresses
./scripts/disasm.sh 0x80000ec0 0x80000f00 dsp              # the DSP section at its run address
```

is the same as

```sh
m68k-elf-objdump -D -b binary -m m68k:cfv4e --adjust-vma=0x40000400 \
    --start-address=0x4006a570 --stop-address=0x4006a590 \
    work/dt_1.52A/section_3_MAIN_OS.bin
```

⚠️ Use `-m m68k:cfv4e` for the MCF5441x (`m68k:isa-a:emac` also works; plain `m68k:cfv4` does not
decode EMAC). `objdump -i` lists the m68k variants unhelpfully as a column of bare `m68k`: just pass
`-m` and see whether it errors. Disassembly of a raw section is linear, so it is only right from a
known instruction boundary: start at an address Ghidra already reached, or at a function entry.

⚠️ objdump prints `remsl Dx,Dx,Dx` (the same register twice) for what is really the signed 32-bit
divide `divs.l`: ColdFire's divide and remainder share an encoding, and the remainder form requires
two different registers. The dump of the whole MAIN OS contains no `divsl` mnemonic at all.

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
`build/build.py`, `build/verify.py`) runs on Linux (Debian 12, arm64); the analysis scripts are
expected to work there but have not been run on Linux.

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
