# Building DT OG++

DT OG++ is built on your own computer from your own copy of Elektron's Digitakt OS update, for an OS version that has an OS folder, `os/<os>/` ([os/README.md](../os/README.md)). This repository contains no Elektron firmware. It contains a list of byte changes per OS folder, `os/<os>/build/patch.json`, and the scripts that apply them and check the result. The build refuses any input that is not the unmodified stock file of that OS folder.

Building never talks to the Digitakt. Flashing the result is a separate step and your own decision. Before you flash, read [SAFETY.md](../SAFETY.md) and the recovery routes in [notes/flash_recovery.md](../notes/flash_recovery.md). The recovery route through the STARTUP menu needs the Digitakt's DIN MIDI port, not USB, so have a DIN MIDI interface ready.

## What you need

- **The stock update file** of the OS version you build, from Elektron, saved in `sysex/` under the name its OS folder gives. The name and the SHA-256 are on the OS folder's reference page (OS 1.52A: [os/1.52A/docs/reference.md](../os/1.52A/docs/reference.md#the-stock-file)).
- **Python 3**, standard library only.
- **Your own clone of [elektron-firmware-tool](https://github.com/mischa85/elektron-firmware-tool)** (MIT License) in `../elektron-firmware-tool`, next to this repository, at commit `065d18f4195793e61891e387813488ee59f6d1ca`. You need git once, to make it (see [Build](#build)).
- **To build the firmware tool:** a C compiler (`cc`), `patch`, and `sha256sum` or `shasum`.
- **Optional:** GNU binutils for ColdFire (`m68k-elf-objdump`), only to regenerate an OS folder's `docs/patch_listing.md` (OS 1.52A: [os/1.52A/docs/patch_listing.md](../os/1.52A/docs/patch_listing.md)).

How to install these on macOS, Linux or Windows, with a check for each, is in [`docs/toolchain.md`](toolchain.md) (sections 0–3; Windows, through WSL2, in [section 7](toolchain.md#7-linux-and-windows)).

`sysex/`, `tool/`, `out/`, `work/` and `manuals/` are ignored by git, so nothing you put there or build there is committed.

## Build

Once, from the repository root, clone the firmware tool next to this repository and check out the pinned commit:

```
git clone https://github.com/mischa85/elektron-firmware-tool ../elektron-firmware-tool
git -C ../elektron-firmware-tool checkout 065d18f
```

Then run these from the repository root, with `<os>` the OS folder of your stock file (the OS folders are listed in [os/README.md](../os/README.md#the-os-folders)) and `<version>` its build's version (v0.2.2 for OS 1.54, v0.1 for OS 1.52A):

```
bash build/build_tool.sh
python3 os/<os>/build/build.py
python3 os/<os>/build/verify.py out/<os>/dt_og_plus_plus_<version>_<hash8>.syx --tool tool/bin/elektron-firmware-tool-capped
```

OS 1.52A:

```
bash build/build_tool.sh
python3 os/1.52A/build/build.py
python3 os/1.52A/build/verify.py out/1.52A/dt_og_plus_plus_v0.1_<hash8>.syx --tool tool/bin/elektron-firmware-tool-capped
```

In the last command, use the file name that `build.py` prints at `[7/7] wrote`.

1. **`build/build_tool.sh`** builds the firmware tool the build is pinned to, from your clone in `../elektron-firmware-tool`. It copies the clone's source files into a build folder under `tool/` and checks the SHA-256 of every one against the files of commit `065d18f4195793e61891e387813488ee59f6d1ca`; that check is how it knows the clone is at the pinned commit. It then applies `build/tool_patches/cap_window_1mb.patch` to the copies and compiles `tool/bin/elektron-firmware-tool-capped`. The compiler may print warnings about the upstream sources (gcc prints two `-Wmisleading-indentation` warnings, in `decompress.c` and `main.c`); they are expected and do not stop the script. It never clones or downloads anything and never changes your clone. If the clone is missing or not at the pinned commit, it stops and prints the two git commands above. It is shared by every OS folder.
   - `--src DIR` builds from a clone in another folder.
   - `--bin-dir DIR` writes the binary somewhere other than `tool/bin`.
2. **`os/<os>/build/build.py`** builds the image for its own OS version. It writes `out/<os>/dt_og_plus_plus_<version>_<first 8 hex digits of its SHA-256>.syx` (the version is the OS folder's own, set in its `build.py`: v0.2.2 for OS 1.54, v0.1 for OS 1.52A) only after every check below has passed. It has no option to choose the OS: running the `build.py` of an OS folder is that choice.
   - `--syx FILE`, `--tool FILE` and `--out DIR` override the default locations (the OS folder's stock file in `sysex/`, `tool/bin/elektron-firmware-tool-capped` and `out/<os>/`).
3. **`os/<os>/build/verify.py`** tells you what any `.syx` file is, for its own OS version: that version's stock file, DT OG++ (reference build), or unknown. A file of another OS version is unknown to it. With `--tool` it also extracts the file and classifies its MAIN OS section, which does not depend on the tool that packed it; it does not compare the other sections.

## What build.py checks

Every step must pass, or the build stops. Each OS folder's `build.py` holds its own values (the stock hashes, the protected ranges, the sections that must stay stock); the steps are the same:

1. The input `.syx` must hash to the stock value of the OS folder's version. There is no override.
2. The tool extracts the file, and section 3 (MAIN OS) must hash to the stock value.
3. Every run in `patch.json` must lie inside section 3 and outside the OS folder's protected ranges (below), and no two runs may overlap. After the runs are applied, section 3 must hash to the published result.
4. The tool packs the patched section 3 into a copy of the stock container: `tool -i <stock.syx> -c 3 <patched section> -o <temporary file>`.
5. The tool must report `checksums : ok` for the new file, for a Digitakt, with the OS folder's version.
6. The new file is extracted again. Section 3 must be byte-identical to the patched section, and every section other than the MAIN OS section must be byte-identical to stock (OS 1.52A: 2, 4 and 5). Sections are matched by number (`section_2_*`), so a tool version that names them differently still works.
7. Only then is the temporary file renamed to its final name.

If any step fails, the script exits with an error and removes its temporary folder, so no file with the final name is left behind.

**Protected ranges.** Each OS folder has its own protected ranges. They hold the code that receives and flashes an OS update over MIDI or USB. Leaving them exactly as stock keeps the way back to stock firmware open. They are `PROTECTED` in the OS folder's `build.py`, which step 3 enforces; its reference page lists them, and its `notes/update_moat.md` gives the reasons (OS 1.52A: [os/1.52A/docs/reference.md](../os/1.52A/docs/reference.md#protected-ranges)). How a protected set is found and vetted, and the two rules every patch follows: [notes/update_moat_method.md](../notes/update_moat_method.md). An OS folder gets a build only once its protected set is vetted.

The sections other than the MAIN OS section are never modified (OS 1.52A: section 2, a boot-time loader, which the tool calls "DSP"; section 4, the flash updater; and section 5, metadata). The round trip in step 6 proves it for every build.

## Expected result

Each OS folder fixes its stock input, the `.syx` and its section 3, by SHA-256 and size. The hashes of the result are recorded in its `os/<os>/build/patch.json` (`result`). `build.py` checks the patched section 3 against them in step 3 and prints both hashes at the end, and `verify.py` classifies a file by them. Each OS folder's reference page gives the values (OS 1.52A: [os/1.52A/docs/reference.md](../os/1.52A/docs/reference.md#expected-result)).

The **section-3 hash** identifies the firmware, whichever tool packed it. The **`.syx` hash** matches only when the file was packed by the pinned tool. Another tool version may compress differently and still carry exactly the same firmware; `build.py` then says so, and `verify.py --tool` confirms it by the section-3 hash.

The image still reports the stock OS version it was built from on the device; the build does not set a version string.

## Why the capped tool

The compressor in the upstream tool has no window limit, so on a rebuild it may emit back-references longer than the Digitakt's own decompressor is known to handle. `cap_window_1mb.patch` limits the compressor to offsets of at most `0x100000` (1 MiB), at a small cost in file size, so a packed file stays inside what the Digitakt's own decompressor is known to handle. The patch changes nothing else.

The cap rests on a measurement of the stock image: Elektron's own image must keep every back-reference within it. Measured on OS 1.52A: [os/1.52A/docs/reference.md](../os/1.52A/docs/reference.md#the-capped-tool-on-this-image); measure again for a new OS before its first build.

## What patch.json contains

Each OS folder has one `os/<os>/build/patch.json`, with these fields. Its reference page gives the values (OS 1.52A: [os/1.52A/docs/reference.md](../os/1.52A/docs/reference.md#what-patchjson-holds)).

- `format` and `format_version`: `dt-og-plus-plus-patch`, version 1. `build.py` refuses any other.
- `target`, `section` and `load_base`: the OS version, the section the runs patch, and that section's load address.
- `stock`: the SHA-256 and size of the stock `.syx` and of its section 3.
- `result`: the SHA-256 of the patched section 3, and the reference `.syx` SHA-256 and size, which hold only with the pinned tool that `pinned_tool` names (the upstream commit plus `cap_window_1mb.patch`).
- `features`: one entry per feature, each with an `id`, a `title` and its `runs`. A run is a load address (`addr`), the new bytes in hex (`bytes`), and `kind`: `code`, or `data` for icons, tables and strings.

The runs are exactly the bytes in which the result differs from stock. They never overlap, so the order in which they are applied does not matter. Each changed byte is listed under one feature only, so where features share code, one feature's instructions can have bytes in another's runs. The features are there for reading; they cannot be applied on their own, and the build accepts only the full set (the section-3 hash in step 3). Each OS folder's `docs/patch_listing.md` lists the features with their runs and bytes, and shows every run, with the disassembly for code and a hex dump for data (OS 1.52A: [os/1.52A/docs/patch_listing.md](../os/1.52A/docs/patch_listing.md)).

What each feature does, and the notes that explain its code, are listed in each OS folder's notes (OS 1.52A: [os/1.52A/notes/README.md](../os/1.52A/notes/README.md#the-features-in-this-build)).

## If something goes wrong

- **`refusing ...` from build.py:** your `.syx` is not the unmodified stock file of that OS folder. Check that you ran the `build.py` of the OS folder your file belongs to (`python3 scripts/identify.py <your file>` names it, if there is one). Download the file again from Elektron and compare its SHA-256 with the one on the OS folder's reference page (OS 1.52A: [os/1.52A/docs/reference.md](../os/1.52A/docs/reference.md#the-stock-file)).
- **`no elektron-firmware-tool source at ../elektron-firmware-tool` from build_tool.sh:** the clone is missing. Make it with the two git commands under [Build](#build), or pass `--src DIR` if your clone is elsewhere.
- **`is not an unmodified checkout of commit 065d18f...` from build_tool.sh:** the clone is not at commit `065d18f`, or its files were changed. Run `git -C ../elektron-firmware-tool checkout 065d18f`; `git -C ../elektron-firmware-tool status` must then show no changed files. git's line-ending conversion (`core.autocrlf` on Windows) also changes them.
- **The `.syx` hash differs from the reference:** you packed with a different tool build. If the section-3 hash matches, the firmware is the same.
- **`checksums` or `round trip` errors:** the tool produced a file that does not survive its own checks. Rebuild the tool with `build/build_tool.sh` and try again.

## Licence

`build/` and the OS folders' `os/*/build/` (including each `patch.json`) are part of this repository's own content, which is dedicated to the public domain under CC0 1.0 Universal ([LICENSE](../LICENSE)). The exception is `build/tool_patches/cap_window_1mb.patch`: its context lines come from elektron-firmware-tool and stay under that tool's MIT License. Details: [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md).
