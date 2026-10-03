# Building DT OG++

DT OG++ is built on your own computer from your own copy of Elektron's Digitakt OS 1.52A update. This repository contains no Elektron firmware. It contains a list of byte changes, `build/patch.json`, and the scripts that apply them and check the result. The build refuses any input that is not the unmodified stock file.

Building never talks to the Digitakt. Flashing the result is a separate step and your own decision. Before you flash, read [SAFETY.md](../SAFETY.md) and the recovery routes in [notes/flash_recovery.md](../notes/flash_recovery.md). The recovery route through the STARTUP menu needs the Digitakt's DIN MIDI port, not USB, so have a DIN MIDI interface ready.

## What you need

- **The stock update file**, `Digitakt_OS1.52A.syx` from Elektron, saved as `sysex/Digitakt_OS1.52A.syx`. SHA-256 `01315133041dcdb8b432146190cc74fc8695c47d8466b0f31bd78cef96fa56a4`, 1,162,400 bytes.
- **Python 3**, standard library only.
- **Your own clone of [elektron-firmware-tool](https://github.com/mischa85/elektron-firmware-tool)** (MIT License) in `../elektron-firmware-tool`, next to this repository, at commit `065d18f4195793e61891e387813488ee59f6d1ca`. You need git once, to make it (see [Build](#build)).
- **To build the firmware tool:** a C compiler (`cc`), `patch`, and `sha256sum` or `shasum`.
- **Optional:** GNU binutils for ColdFire (`m68k-elf-objdump`), only to regenerate [`docs/patch_listing.md`](patch_listing.md).

How to install these on macOS, Linux or Windows, with a check for each, is in [`docs/toolchain.md`](toolchain.md) (sections 0–3; Windows, through WSL2, in [section 7](toolchain.md#7-linux-and-windows)).

`sysex/`, `tool/`, `out/`, `work/` and `manuals/` are ignored by git, so nothing you put there or build there is committed.

## Build

Once, from the repository root, clone the firmware tool next to this repository and check out the pinned commit:

```
git clone https://github.com/mischa85/elektron-firmware-tool ../elektron-firmware-tool
git -C ../elektron-firmware-tool checkout 065d18f
```

Then run these from the repository root:

```
bash build/build_tool.sh
python3 build/build.py
python3 build/verify.py out/dt_og_plus_plus_v0.1_<hash8>.syx --tool tool/bin/elektron-firmware-tool-capped
```

In the last command, use the file name that `build.py` prints at `[7/7] wrote`.

1. **`build/build_tool.sh`** builds the firmware tool the build is pinned to, from your clone in `../elektron-firmware-tool`. It copies the clone's source files into a build folder under `tool/` and checks the SHA-256 of every one against the files of commit `065d18f4195793e61891e387813488ee59f6d1ca`; that check is how it knows the clone is at the pinned commit. It then applies `build/tool_patches/cap_window_1mb.patch` to the copies and compiles `tool/bin/elektron-firmware-tool-capped`. The compiler may print warnings about the upstream sources (gcc prints two `-Wmisleading-indentation` warnings, in `decompress.c` and `main.c`); they are expected and do not stop the script. It never clones or downloads anything and never changes your clone. If the clone is missing or not at the pinned commit, it stops and prints the two git commands above.
   - `--src DIR` builds from a clone in another folder.
   - `--bin-dir DIR` writes the binary somewhere other than `tool/bin`.
2. **`build/build.py`** builds the image. It writes `out/dt_og_plus_plus_v0.1_<first 8 hex digits of its SHA-256>.syx` only after every check below has passed.
   - `--syx FILE`, `--tool FILE` and `--out DIR` override the default locations.
3. **`build/verify.py`** tells you what any `.syx` file is: stock Digitakt OS 1.52A, DT OG++ (reference build), or unknown. With `--tool` it also extracts the file and classifies its MAIN OS section, which does not depend on the tool that packed it; it does not compare the other sections.

## What build.py checks

Every step must pass, or the build stops:

1. The input `.syx` must hash to the stock value. There is no override.
2. The tool extracts the file, and section 3 (MAIN OS) must hash to the stock value.
3. Every run in `patch.json` must lie inside section 3 and outside the protected ranges below, and no two runs may overlap. After the runs are applied, section 3 must hash to the published result.
4. The tool packs the patched section 3 into a copy of the stock container: `tool -i <stock.syx> -c 3 <patched section> -o <temporary file>`.
5. The tool must report `checksums : ok` for the new file, for a Digitakt, version 1.52A.
6. The new file is extracted again. Section 3 must be byte-identical to the patched section, and sections 2, 4 and 5 must be byte-identical to the stock ones. Sections are matched by number (`section_2_*`), so a tool version that names them differently still works.
7. Only then is the temporary file renamed to its final name.

If any step fails, the script exits with an error and removes its temporary folder, so no file with the final name is left behind.

**Protected ranges.** These hold the code that receives and flashes an OS update over MIDI or USB. Leaving them exactly as stock keeps the way back to stock firmware open. Addresses are load addresses; section 3 loads at `0x40000400`, so file offset = address − `0x40000400`.

| range | what it is |
|---|---|
| `0x400d7fc8`–`0x400d98f6` | NOR flash driver (DSPI), with its timer wait/ISR and driver getters |
| `0x40068be0`–`0x40068c12` | DSPI initialization |
| `0x400668e2`–`0x4006711a` | mid-level flash operations |
| `0x4006753e`–`0x40068b16` | OS-update transfer task |
| `0x40080868`–`0x4008088c` | `DigitaktSysex` destructor |
| `0x40058b48`–`0x40058b68`, `0x40059f6a`–`0x4005a4d6`, `0x4005a4d6`–`0x4005a5f6`, `0x4013a77a`–`0x4013a82a`, `0x4013a82a`–`0x4013a86a` | SysEx receive menu, with its entry thunks |
| `0x4008b722`–`0x4008bc2e` | storage bulk transfer |
| `0x40066618`–`0x400668e2`, `0x4006717a`–`0x4006753e`, `0x40068b16`–`0x40068b60`, `0x40068b92`–`0x40068bc2`, `0x40068c12`–`0x40068c34`, `0x40069040`–`0x4006906c`, `0x400693a0`–`0x4006981c`, `0x40069aa0`–`0x4006a7f2`, `0x4008088c`–`0x400808a6`, `0x4008b524`–`0x4008b722` | code the update path also uses: the transfer task's launcher and helpers, the update-mode branch of the init task, the result views and erase job, storage-transfer helpers |

Sections 2 (a boot-time loader, which the tool calls "DSP"), 4 (the flash updater) and 5 (metadata) are never modified. The round trip in step 6 proves it for every build.

## Expected result

The stock input is fixed:

| | SHA-256 | size |
|---|---|---:|
| stock `.syx` | `01315133041dcdb8b432146190cc74fc8695c47d8466b0f31bd78cef96fa56a4` | 1,162,400 B |
| stock section 3 | `59278368fbe86c9877fad68a578987050e21fc4b418a289cfa1d1351d8e864ee` | 2,221,632 B |

The hashes of the result are recorded in `build/patch.json` (`result`). `build.py` checks the patched section 3 against them in step 3 and prints both hashes at the end, and `verify.py` classifies a file by them.

The **section-3 hash** identifies the firmware, whichever tool packed it. The **`.syx` hash** matches only when the file was packed by the pinned tool. Another tool version may compress differently and still carry exactly the same firmware; `build.py` then says so, and `verify.py --tool` confirms it by the section-3 hash.

The image still reports OS version 1.52A on the device; the build does not set a version string.

## Why the capped tool

The compressor in the upstream tool has no window limit: rebuilding the stock image, it emits back-references up to 2,157,404 bytes (about 2.1 MiB). The stock Digitakt OS 1.52A image stays under 1 MiB: its largest offset is 1,048,572 (`0x0FFFFC`). `cap_window_1mb.patch` limits the compressor to offsets of at most `0x100000` (1 MiB, 4 bytes more than that), at a small cost in file size, so a packed file stays inside what the Digitakt's own decompressor is known to handle. The patch changes nothing else.

## What patch.json contains

- `format` and `format_version`: `dt-og-plus-plus-patch`, version 1. `build.py` refuses any other.
- `target`, `section` and `load_base`: Digitakt OS 1.52A, section 3, `0x40000400`.
- `stock`: the SHA-256 and size of the stock `.syx` and of its section 3.
- `result`: the SHA-256 of the patched section 3, and the reference `.syx` SHA-256 and size, which hold only with the pinned tool that `pinned_tool` names (the upstream commit plus `cap_window_1mb.patch`).
- `features`: one entry per feature, each with an `id`, a `title` and its `runs`. A run is a load address (`addr`), the new bytes in hex (`bytes`), and `kind`: `code`, or `data` for icons, tables and strings.

The runs are exactly the bytes in which the result differs from stock. They never overlap, so the order in which they are applied does not matter. Each changed byte is listed under one feature only, so where features share code, one feature's instructions can have bytes in another's runs. The features are there for reading; they cannot be applied on their own, and the build accepts only the full set (the section-3 hash in step 3). [`docs/patch_listing.md`](patch_listing.md) lists the features with their runs and bytes, and shows every run, with the disassembly for code and a hex dump for data.

What each feature does, and the notes that explain its code, are listed in [notes/README.md](../notes/README.md#the-features-in-this-build).

## If something goes wrong

- **`refusing ...` from build.py:** your `.syx` is not the unmodified Digitakt OS 1.52A file. Download it again from Elektron and compare its SHA-256 with the one above.
- **`no elektron-firmware-tool source at ../elektron-firmware-tool` from build_tool.sh:** the clone is missing. Make it with the two git commands under [Build](#build), or pass `--src DIR` if your clone is elsewhere.
- **`is not an unmodified checkout of commit 065d18f...` from build_tool.sh:** the clone is not at commit `065d18f`, or its files were changed. Run `git -C ../elektron-firmware-tool checkout 065d18f`; `git -C ../elektron-firmware-tool status` must then show no changed files. git's line-ending conversion (`core.autocrlf` on Windows) also changes them.
- **The `.syx` hash differs from the reference:** you packed with a different tool build. If the section-3 hash matches, the firmware is the same.
- **`checksums` or `round trip` errors:** the tool produced a file that does not survive its own checks. Rebuild the tool with `build/build_tool.sh` and try again.

## Licence

`build/`, including `build/patch.json`, is part of this repository's own content, which is dedicated to the public domain under CC0 1.0 Universal ([LICENSE](../LICENSE)). The exception is `build/tool_patches/cap_window_1mb.patch`: its context lines come from elektron-firmware-tool and stay under that tool's MIT License. Details: [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md).
