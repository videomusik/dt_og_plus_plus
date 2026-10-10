# OS folders

Everything specific to one Digitakt OS version lives in that version's OS folder, `os/<version>/`:
its build, its docs and notes, its analysis files and its version-bound scripts. Outside `os/` live
the shared tooling, the method, the device notes and the conventions. Every firmware address, hash,
range and unit result belongs to exactly one OS version's stock file: that of the OS folder it is
in, or the one named on the same line.

The folder name is the version string the firmware tool reports for the stock file (its `version`
line). The same string is the OS id on the command line, and it names that version's local state:
the work folder `work/dt_<version>/`, the Ghidra projects `dt_<version>*` and the build folder
`out/<version>/`. The rules that keep versions apart are binding as [AGENTS.md](../AGENTS.md) states
them; this page describes the layout, the command line and how a new OS folder is started.

## The OS folders

| OS folder | Firmware | Page | Build |
|---|---|---|---|
| `1.52A` | Digitakt OS 1.52A, for the original Digitakt | [1.52A/README.md](1.52A/README.md) | `python3 os/1.52A/build/build.py` |
| `1.54` | Digitakt OS 1.54, for the original Digitakt | [1.54/README.md](1.54/README.md) | `python3 os/1.54/build/build.py` |

DT OG++ is developed in `1.54`. `1.52A` is on hold: its build has not been developed past its last
feature, and new features go into `1.54` only.

To find out which OS folder a `.syx` file belongs to, run
[`scripts/identify.py`](../scripts/identify.py) from the repo root:

```sh
python3 scripts/identify.py <file.syx> --tool tool/bin/elektron-firmware-tool-capped
```

It reads every OS folder and labels the file as an OS folder's stock file, as its reference build,
or as unknown. With `--tool` it also shows the tool's `device` and `version` lines and names the
next step: that OS folder's `verify.py`, or [Starting a new OS folder](#starting-a-new-os-folder).
It reads only: it never extracts and never writes anything.

## What an OS folder holds

```
os/<v>/
├── README.md        the OS page: features, quick start, going back to stock
├── profile.sh       identity and section layout, read by the shared scripts
├── build/           build.py, verify.py, make_listing.py, patch.json (once the protected set is vetted)
├── docs/            reference.md (stock file, results, protected ranges), patch_listing.md
├── notes/           README.md, function_ledger.md, update_moat.md, landing_pads.md, features/, ...
├── analysis/        sources and simulations behind single patches
├── scripts/         helpers bound to this version
│   ├── ghidra/      Ghidra scripts whose tables hold this version's addresses
│   └── emu/         emulator harnesses and their helpers
└── rederived.md     optional: values found again in this version
```

- **`README.md`** is the page for people who build and flash: what the build changes, how to build
  it and how to go back to stock. The root [README.md](../README.md) lists every OS page.
- **`profile.sh`** holds the version's identity and section layout: shell variables and one
  function, and it runs nothing. `scripts/common.sh` sources it when a command names the OS, after
  clearing every `OS_*` name, so the caller's environment cannot stand in for a profile.
  `scripts/check_os_folders.py` and `scripts/identify.py` parse it with a regular expression and
  never run it. Every value is measured from the stock file the profile names:

  | Name | What it holds |
  |---|---|
  | `OS_ID` | the folder name |
  | `OS_LABEL` | the version's name in messages, "Digitakt OS" and the version |
  | `OS_REPORTED_VERSION` | the `version` line of the tool's summary of the stock file |
  | `OS_STOCK_SYX_DEFAULT` | where the stock file is expected, under `sysex/` |
  | `OS_STOCK_SYX_SHA256`, `OS_STOCK_SYX_SIZE` | the stock file's SHA-256 and size |
  | `OS_CONTAINER_SECTION_IDS` | the ids of every section in the container |
  | `OS_MAIN_ID`, `OS_MAIN_BASE` | the MAIN OS section's id and load address |
  | `OS_STOCK_MAIN_SHA256`, `OS_STOCK_MAIN_SIZE` | the SHA-256 and size of the decompressed stock MAIN OS section |
  | `OS_ANALYSIS_SECTIONS` | the analysis sections imported straight from an extract; `main` is always one |
  | `OS_SIGNATURE_TRAILER` | whether the image carries a signature trailer (`none` when it has none) |
  | `os_section_meta` | a function: for an analysis section, its source (a section id or a file in the work folder), load base, header bytes to strip and entry (`-` when there is none) |

- **`build/`** exists only once the version's protected set is vetted (steps 6 and 7 of
  [Starting a new OS folder](#starting-a-new-os-folder)). `build.py` builds and checks, `verify.py`
  classifies any `.syx` by its hash (and, with `--tool`, by its MAIN OS section),
  `make_listing.py` writes `docs/patch_listing.md`, and `patch.json` holds the patch data. They
  have no `--os` flag: each knows only its own OS, and its defaults (the stock file, `out/<v>/`,
  the listing) belong to its own folder.
- **`docs/reference.md`** holds the version's figures that the shared docs leave out: the stock
  file and its hash, what `inspect.sh` prints, the sections, the round trip, the expected build
  result, the protected ranges, the capped tool on this image and the values in `patch.json`.
- **`notes/`** starts at its own `README.md` (scope, test unit, features, reading order) and holds
  the function ledger, the update path and its protected set (`update_moat.md`), the landing pads,
  the memory map and the feature notes. The conventions and the method are shared:
  [notes/README.md](../notes/README.md).
- **`scripts/`** holds code that carries this version's data. Ghidra scripts with address tables
  sit in `os/<v>/scripts/ghidra/`; the shared wrappers add that folder to Ghidra's script path.
  Emulator harnesses sit in `os/<v>/scripts/emu/` and run with
  `./scripts/ghidra_emu.sh <v> <Harness>`. A helper in an OS folder takes its OS from the folder it
  sits in.
- **`rederived.md`** is described under
  [Values found again in another version](#values-found-again-in-another-version).

Two naming rules keep the tree unambiguous:

- **No twin names.** A file inside an OS folder never has the same relative path as a shared file.
  Where a note was split, the halves have different names, for example the shared method
  `notes/landing_pad_method.md` and the per-version `os/<v>/notes/landing_pads.md`. `README.md` is
  the one name found at both levels.
- **No Python imports of an OS folder.** A builder imports the `build.py` next to it through
  `sys.path`, `profile.sh` is sourced by bash or parsed, and `patch.json` is read as JSON. No
  folder under `os/` gets an `__init__.py`: without one, `os/` never shadows Python's own `os`
  module, and a folder name such as `1.52A` need not be a valid Python name.

## Keeping versions apart

The rules are binding as written in [AGENTS.md](../AGENTS.md), section "One OS folder per version".
In brief:

- **Name the OS on every command.** Shared tooling has no default OS, and no environment variable
  picks one.
- **Nothing crosses between OS folders unchecked.** No address, hash, range, landing pad, RAM slot,
  ledger row, script table, harness constant, decompile line number or ✅ result moves from one OS
  folder to another. A value seen in another version is at most a ⚠️ hypothesis
  ([confidence marks](../notes/README.md#confidence-marks)) until it is found again in this
  version's own stock file and recorded with its own evidence.
- **No OS folder cites another as evidence.** A comparison of two versions is its own result, made
  from both files.
- **Shared files hold no firmware facts.** Where a shared file keeps a firmware address as an
  example, the same line names its OS.
- **Each version's local state stays apart:** `work/dt_<v>*`, the Ghidra projects `dt_<v>*` and
  `out/<v>/`.

[`scripts/check_os_folders.py`](../scripts/check_os_folders.py) checks what can be checked. Run
`python3 scripts/check_os_folders.py` from the repo root before handing work over. It reads the
tracked files and the untracked files that no ignore rule matches, prints
`path:line: R<n>: message` for each error and exits 1 on any:

| Rule | What it checks |
|---|---|
| R1 | Every relative markdown link outside fenced code resolves to a tracked file or folder, and its `#anchor` to a heading or an explicit anchor of the target. |
| R2 | A file inside one OS folder neither links to nor names a path inside another OS folder. |
| R3 | Per OS folder, `profile.sh` sets the required variables, and `OS_ID` is the folder name. With a build folder, the profile agrees with `build.py` and `patch.json` on the stock and MAIN OS identity, the load base and the section ids; `check_report` names the reported version, and the output folder names the OS. |
| R4 | An OS folder whose build folder holds a `build.py` has a non-empty `PROTECTED` in it and a `notes/update_moat.md`. |
| R5 | The 8-hex prefixes of an OS folder's identity hashes (stock file, stock MAIN OS, patched MAIN OS, reference build) occur in no tracked text file outside that folder. |
| R6 | In every file outside the OS folders (this page included), each firmware literal (`0x4` or `0x8` with seven more hex digits, or a `FUN_`, `DAT_`, `LAB_`, `PTR_`, `switchD_` or `entry_` name built on one) has a known OS id on the same line. The chip's address-space constants are exempt. |
| R7 | No twin names: for a file inside an OS folder other than `README.md`, the same path outside `os/` is not tracked. |
| R8 | With two or more OS folders: no firmware literal appears in the code and data of two folders unless a `rederived.md` lists it, and a copied builder's functions are identical in both apart from their strings. |
| R9 | Nothing tracked matches an ignore rule or uses a name reserved for local files (`sysex`, `manuals`, `tool`, `out`, `work`, `section_*`, `*.bin`, `*.raw`, `*.syx`, `*.pdf`). |
| R10 | Every OS folder name is a valid OS id, and the folder has a `profile.sh`. |

## Choosing the OS on the command line

Every command says which OS it works on:

- **Builders are chosen by path:** `python3 os/<v>/build/build.py`,
  `python3 os/<v>/build/verify.py FILE.syx...` and `python3 os/<v>/build/make_listing.py`. A file
  of another OS is refused by the stock-hash check of `build.py` and `make_listing.py`, and
  `verify.py` reports it as `unknown`.
- **Helpers in an OS folder** (its `scripts/` and `analysis/`) take their OS, and the names of its
  work folders, from where they sit.
- **Shared Python** (`scripts/identify.py`, `scripts/check_os_folders.py`) has no default OS: it
  reads every OS folder.
- **Shell wrappers** take an image argument first; `ghidra_emu.sh` takes an OS id. Run without
  arguments, each prints its usage and the list of OS folders, and exits 2. `build/build_tool.sh`,
  `scripts/ghidra_lang_ext.sh` and `scripts/manual_extract.sh` take no OS.

### Image arguments

| Form | What it means |
|---|---|
| `<os>` | That OS folder's stock file: `$STOCK_SYX`, by default `OS_STOCK_SYX_DEFAULT` from its profile. Its SHA-256 must be the profile's. Work folder `work/dt_<os>/`. |
| `<os>:<file.syx>` | Any other file of that OS, for example a build. Work folder `work/dt_<os>-<file name without .syx>/`. Refused when the file is that OS's stock file (use the key `<os>`), and unless the tool's `version` line equals the profile's `OS_REPORTED_VERSION`. |
| `dt` | Refused: the old image key is gone; the message lists the OS folders to name instead. |
| a bare path (`*.syx`, or anything containing `/`, without `<os>:`) | Refused: say which OS the file belongs to, as `<os>:<path>`. |

- `<os>` holds only letters, digits and `.`, and does not start with `.`; without `_` and `-` it can
  never be confused with the project suffixes (`dt_<os>_<section>`) or a build's work folder
  (`dt_<os>-<build>`). `os/<os>/profile.sh`
  must exist and set `OS_ID` to the same string. Otherwise the command stops before it opens any
  file, with "no OS folder os/<os>/" and the list of OS folders.
- The argument is split at the first `:`, so the path may itself contain `:`. A relative path is
  taken from the repo root when it exists there, else from the folder the command was started in.

### Guards

- **One OS per run.** A command that names two different OS ids is refused, and `inspect.sh` and
  `extract.sh` read every argument before they act.
- **`STOCK_SYX`** may point to the stock file elsewhere, but it must hash to the selected OS's stock
  SHA-256, so it can never pull in another version's file.
- **`GHIDRA_PROJECT`** must equal the image's work-folder name (`dt_<os>` or `dt_<os>-<name>`), or
  start with that name followed by `_`. It is checked before Ghidra starts.
- **Ghidra scripts** are looked up in `scripts/ghidra/` and in `os/<os>/scripts/ghidra/`. A script
  name found in both folders, or in neither, is refused before Ghidra starts. `ghidra_emu.sh` finds
  harnesses only in `os/<os>/scripts/emu/` and opens that OS's project read-only.
- **File arguments** to `disasm.sh` and to a harness that lie under `work/` must lie under
  `work/dt_<os>/` or `work/dt_<os>-*/`.

### Old commands and new

The image key `dt` and the default image are gone. The shell wrappers take the OS id as the first
argument:

| Wrapper | Old arguments | New arguments (OS 1.52A) |
|---|---|---|
| `./scripts/inspect.sh` | none, or `-v dt` | `1.52A`, or `-v 1.52A` |
| `./scripts/extract.sh` | none | `1.52A` |
| `./scripts/extract.sh` | `out/<b>.syx` | `1.52A:out/1.52A/<b>.syx` |
| `./scripts/roundtrip.sh` | none | `1.52A` |
| `./scripts/disasm.sh` | `A B [sec] [file]` | `1.52A A B [sec] [file]`, or `1.52A:out/1.52A/<b>.syx A B` |
| `./scripts/ghidra_analyze.sh` | `dt main` | `1.52A main` |
| `./scripts/ghidra_query.sh` | `dt main X …` | `1.52A main X …` |
| `./scripts/ghidra_decompile.sh` | `dt …` | `1.52A …` |
| `./scripts/ghidra_emu.sh` | `EmuX [args]` | `1.52A EmuX [args]` |

The Python tools moved into the OS folder:

| Old command | New command |
|---|---|
| `python3 build/build.py` | `python3 os/1.52A/build/build.py` |
| `python3 build/verify.py out/<b>.syx …` | `python3 os/1.52A/build/verify.py out/1.52A/<b>.syx …` |
| `python3 scripts/build_sram_image.py [folder]` | `python3 os/1.52A/scripts/build_sram_image.py [folder]` |

## Starting a new OS folder

This is the method only; every value comes from the new version's own stock file.

1. **The stock file.** Put Elektron's stock file in `sysex/` under the name Elektron gives it, and
   never commit it. Read it with the tool directly:
   `tool/bin/elektron-firmware-tool-capped -i <file>` for the summary, then
   `tool/bin/elektron-firmware-tool-capped -v -i <file>` for the full report. The `device` line
   must name the original Digitakt, and the checksums must be ok.
   `python3 scripts/identify.py <file> --tool tool/bin/elektron-firmware-tool-capped` must report
   the file as unknown; a file that an OS folder already knows needs no new folder.
2. **The profile.** Write `os/<v>/profile.sh` from measured values only:
   - the version string as the tool reports it, for `OS_ID` and `OS_REPORTED_VERSION`, and the
     folder name;
   - the stock file's SHA-256 and size;
   - the container section ids;
   - the MAIN OS section's id and load address, from the full report;
   - the SHA-256 and size of the decompressed MAIN OS section, from one extract made with the tool
     (`-i <file> -o <folder>`) into a temporary folder outside the repo or under `work/`;
   - the `os_section_meta` rows, derived by the recipe "How to derive a row" in
     `scripts/common.sh` (see
     [docs/toolchain.md](../docs/toolchain.md#4d-the-projects-and-how-to-make-them), section 4d);
   - the signature-trailer status.

   Never copy a value from another profile. `./scripts/inspect.sh <v>` must then print
   `stock file ok`.
3. **Extract and analyse.** Run `./scripts/extract.sh <v>` and `./scripts/roundtrip.sh <v>`; the
   round trip must pass before any change is made. Record what `inspect.sh` prints, the sections
   and the round trip in `os/<v>/docs/reference.md`. Make the Ghidra projects `dt_<v>*` by the
   recipes in [docs/toolchain.md](../docs/toolchain.md#4d-the-projects-and-how-to-make-them), and
   record them, with their reference numbers, in `os/<v>/notes/analysis_reference.md`.
4. **Notes.** Start `os/<v>/notes/README.md`: the scope, the test unit for that version, and which
   manual edition its notes are checked against
   ([scripts/manual/README.md](../scripts/manual/README.md)). The function ledger,
   `os/<v>/notes/function_ledger.md`, starts empty.
5. **Version-bound scripts.** Copy Ghidra table scripts, harnesses and helpers as code only. Every
   table starts empty, and the script refuses to run while it is empty. Fill each table from this
   version's own analysis.
6. **The update path.** Analyse this version's update path anew, in its own stock file, by
   [notes/update_moat_method.md](../notes/update_moat_method.md), and record it, with its protected
   set, in `os/<v>/notes/update_moat.md`. Until that set is vetted the folder gets no `build/`.
7. **The build folder.** Only then create `os/<v>/build/`:
   - Copy `build.py`, `verify.py` and `make_listing.py` as code. Their check code stays identical to
     the other folders' (R8).
   - Set every data constant from this version: the stock identity, `LOAD_BASE`, `PROTECTED`,
     `UNCHANGED_SECTIONS`, the version in `check_report`, `DEFAULT_SYX`, `DEFAULT_OUT = out/<v>` and
     `CODE_WINDOWS`. The output file label of a new OS folder is set in its build.py
     (`final_name`).
   - Put the guard `if not PROTECTED: sys.exit(...)` directly after `PROTECTED`.
   - Measure the compressor window again before the first build
     ([docs/building.md](../docs/building.md#why-the-capped-tool); how:
     [notes/firmware_image.md](../notes/firmware_image.md#the-1-mb-back-reference-window)).
8. **Patches and pads.** `os/<v>/build/patch.json` starts with no features. Vet every landing pad
   with the folder's own positive controls
   ([notes/landing_pad_method.md](../notes/landing_pad_method.md#vetting-a-new-pad)) and record it
   in `os/<v>/notes/landing_pads.md` before a feature uses it.
9. **Check.** Add the folder to the table under [The OS folders](#the-os-folders) and to the OS
   table of the root [README.md](../README.md), then run `python3 scripts/check_os_folders.py`. It
   must exit 0.

## Values found again in another version

A value of one version may also appear in another OS folder's code or data only once it has been
found again in that folder's own stock file. Such a value is listed in that folder's
`os/<v>/rederived.md`, one value per row:

```
| Value | What it is in this version | Evidence |
|---|---|---|
| `0x<eight hex digits>` | <what the value is in this version> | [<note>](notes/<note>.md#<heading>) |
```

- The first column holds the value as the folder's code writes it: `0x` and eight hex digits.
  `scripts/check_os_folders.py` reads these values and accepts them in both folders' code and data
  (R8).
- The evidence links into this folder's own notes, where the value was found in this version's
  stock file. A value seen in another version is no evidence, and no link points into another OS
  folder (R2).
- The file is optional. A folder that shares no value with another has none.
