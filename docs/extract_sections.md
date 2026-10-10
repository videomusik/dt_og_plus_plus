# Inspecting, extracting and round-tripping a Digitakt OS image

Everything here works on files: no device is connected, nothing is flashed. All output lands in
`work/` (gitignored). The commands live in `scripts/`; they find the repo root themselves, so you can
run them from anywhere, but paths you pass are easiest to give relative to the repo root.

Prerequisites: your stock file in `sysex/`, under the name its OS folder gives
(OS 1.52A: `sysex/Digitakt_OS1.52A.syx`, see [os/1.52A/docs/reference.md](../os/1.52A/docs/reference.md#the-stock-file)),
and the firmware tool built with `bash build/build_tool.sh` from your clone of elektron-firmware-tool
([`toolchain.md`](toolchain.md) sections 1 and 2).

## Which image

Every script takes an image argument, and every image argument names its OS folder, `os/<os>/`
([os/README.md](../os/README.md#choosing-the-os-on-the-command-line)). There is no default image: a
script run without one prints its usage and the list of OS folders.

| Form | What it means |
|---|---|
| `<os>` | That OS's stock file, for example `1.52A`. The scripts check its SHA-256 against the OS folder's `profile.sh` and refuse any other file. Output folder: `work/dt_<os>/`. |
| `<os>:<file.syx>` | Any other `.syx` of that OS, for example a build output: `1.52A:out/1.52A/dt_og_plus_plus_v0.1_<hash8>.syx`. Output folder: `work/dt_<os>-<file name without .syx>/`. Refused if it is that OS's stock file (use the key `<os>`), and refused unless the tool reports that OS's version for it. No hash is enforced; the file's SHA-256 is printed. |
| `dt` | Refused: the old image key is gone; name the OS folder, for example `1.52A`. |
| a path without `<os>:` (`*.syx`, or anything containing `/`) | Refused: say which OS the file belongs to, as `<os>:<path>`. |

- `<os>` contains only letters, digits, `.`, `_` and `-`, and does not start with `.` or `_`. It must
  name a folder `os/<os>/` whose `profile.sh` sets `OS_ID` to the same string. Otherwise the script
  stops before it opens any file, and lists the OS folders.
- The argument is split at the first `:`, so the path may itself contain `:`. A relative path is read
  from the repo root when the file is there, and otherwise from the folder you run the script in.
- One OS per run: a run given images of two different OS folders is refused. `inspect.sh` and
  `extract.sh` read every argument before they act, so nothing is left half done.

The stock file's location can be changed with `STOCK_SYX=...`; the file must still hash to the
selected OS's stock SHA-256. The tool's location can be changed with `FIRMWARE_TOOL=...`.

## The steps

| Step | Script | What it does, and what to expect |
|---|---|---|
| 1 | `./scripts/inspect.sh [-v] <image>...` | Prints the summary; writes nothing. Expect `checksums : ok`; the full summary of each OS folder's stock file is on its reference page (below). `-v` prints the full report: transport statistics, the container header and the section table. |
| 2 | `./scripts/extract.sh <image>...` | Extracts every section to `section_<id>_<NAME>.bin` in the image's output folder (`.raw` when a section is stored uncompressed) and saves the `-v` report next to them as `report.txt`; then prints the section table and, for a stock image, checks the MAIN OS hash against the OS folder's `profile.sh`. |
| 3 | `./scripts/roundtrip.sh <image>` | Rebuilds the image from its *unmodified* MAIN OS section, verifies the rebuilt `.syx`, re-extracts it and `cmp`s every section. Expect `checksums : ok`, one `identical` line per section and `round-trip OK`. **Passing this is the precondition for any modification.** |

What `inspect.sh` prints on each OS folder's stock file is on that folder's reference page
(OS 1.52A: [What inspect.sh prints](../os/1.52A/docs/reference.md#what-inspectsh-prints)).

Whether an OS image carries a signature trailer is recorded in its OS folder's `profile.sh`
(`OS_SIGNATURE_TRAILER`), and `extract.sh` prints a note when it is not `none` (OS 1.52A: `none`).
Without a trailer, the image's integrity is the transport, container and section checksums, all of
which the tool recomputes on a rebuild. `report.txt` describes Elektron's image either way: keep it
in `work/`, never publish it.

## Sections and addresses

The section table of each OS folder's stock image (file names, sizes, `dst`, where each section
loads and runs, SHA-256) is on its reference page
(OS 1.52A: [The sections of the stock image](../os/1.52A/docs/reference.md#the-sections-of-the-stock-image)).
The analysis base, header strip and entry of each section are `os_section_meta()` in the OS folder's
`profile.sh`; `scripts/common.sh` describes how a row is derived.

`dst` is the load address the container declares, which is the number disassembly needs, except
where the OS folder records that a section runs from another base (OS 1.52A: section 2).

**MAIN OS addresses:** everything in this repo uses load addresses. For the MAIN OS section, file
offset = load address − the section's load base (`OS_MAIN_BASE` in the OS folder's `profile.sh`).

**Match sections by id, not by name.** Newer upstream versions of the tool call section 2
"bootstrap" and so write `section_2_bootstrap.bin`. The scripts look for `section_2_*`,
`section_3_*` and so on, and so should anything you write.

## The round trip

`roundtrip.sh` rebuilds the image from the MAIN OS section its OS folder's `profile.sh` names
(`OS_MAIN_ID`), and writes `roundtrip/rt.syx` and its re-extract into the image's output folder (for
a stock image, `work/dt_<os>/roundtrip/rt.syx`). The tool's compressor packs differently from
Elektron's, so the `.syx` files differ and only the decompressed sections are compared; every
section must come back byte-identical. The figures for each OS folder's stock file are on its
reference page (OS 1.52A: [The round trip](../os/1.52A/docs/reference.md#the-round-trip)).

A derived file next to the sections, such as a header-stripped `section_<id>_<NAME>.from<N>.bin`
that the Ghidra wrapper creates, is skipped by the comparison.

## Checking a build

```sh
python3 os/<os>/build/verify.py out/<os>/dt_og_plus_plus_<version>_<hash8>.syx --tool tool/bin/elektron-firmware-tool-capped
```

classifies the file (the stock file of that OS version, DT OG++ reference build, or unknown); see
[`building.md`](building.md). `<version>` is the build's version, which the OS folder's `build.py` puts
in the file name (v0.2.2 for OS 1.54, v0.1 for OS 1.52A). To look inside a build with the scripts
here, name its OS:

```sh
./scripts/extract.sh <os>:out/<os>/dt_og_plus_plus_<version>_<hash8>.syx    # -> work/dt_<os>-dt_og_plus_plus_<version>_<hash8>/
./scripts/roundtrip.sh <os>:out/<os>/dt_og_plus_plus_<version>_<hash8>.syx
```

OS 1.52A:

```sh
python3 os/1.52A/build/verify.py out/1.52A/dt_og_plus_plus_v0.1_<hash8>.syx --tool tool/bin/elektron-firmware-tool-capped
./scripts/extract.sh 1.52A:out/1.52A/dt_og_plus_plus_v0.1_<hash8>.syx    # -> work/dt_1.52A-dt_og_plus_plus_v0.1_<hash8>/
./scripts/roundtrip.sh 1.52A:out/1.52A/dt_og_plus_plus_v0.1_<hash8>.syx
```

In the extract of a correct build, section 3 has the SHA-256 recorded in the OS folder's
`os/<os>/build/patch.json` (`result.section3_sha256`) whichever tool packed it, and every other
section is byte-identical to the stock one (`cmp` them against `work/dt_<os>/`;
OS 1.52A: sections 2, 4 and 5, against `work/dt_1.52A/`). The `.syx` hash recorded there holds only
for the pinned tool. The extracted section 3 is also what the OS folder's emulator harnesses read
their pad bytes from (OS 1.52A: [os/1.52A/scripts/emu/README.md](../os/1.52A/scripts/emu/README.md))
and what `./scripts/disasm.sh <os>:out/<os>/<build>.syx <start> <stop>` disassembles
(OS 1.52A: `./scripts/disasm.sh 1.52A:out/1.52A/<build>.syx <start> <stop>`).

## Housekeeping

`work/` is gitignored and can be deleted and regenerated at will. `sysex/` holds your only local copy
of the original: never delete or overwrite it.
