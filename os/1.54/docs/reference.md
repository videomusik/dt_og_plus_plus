# OS 1.54 reference values

The values the build and the scripts check for Digitakt OS 1.54; how they are used: [docs/building.md](../../../docs/building.md) and [docs/extract_sections.md](../../../docs/extract_sections.md).

## The stock file

Download the Digitakt OS 1.54 update from Elektron's support pages for the Digitakt (the original model). The stock update file, `Digitakt_OS1.54.syx` from Elektron, is saved as:

```
sysex/Digitakt_OS1.54.syx
```

| | SHA-256 | size |
|---|---|---:|
| stock `.syx` | `f78ba80fa7b1da5fb0e1ff61ad61e9e71aafe79f4364fc49679f3651353e3cf6` | 1,423,776 B |
| stock section 3 | `5c58bf9e3949ef09977c5fc007a61e8d026931f67f1621238379dfb8ee4d31a2` | 2,479,680 B |

**Check:**

```sh
shasum -a 256 sysex/Digitakt_OS1.54.syx      # Linux: sha256sum sysex/Digitakt_OS1.54.syx
```

must print `f78ba80fa7b1da5fb0e1ff61ad61e9e71aafe79f4364fc49679f3651353e3cf6` (1,423,776 bytes).
The scripts and the build refuse any other file as the stock image, because every address in this OS folder belongs to exactly that file.

The same values are held three times, on purpose: the `.syx` hash and size as `OS_STOCK_SYX_*` in [profile.sh](../profile.sh), `STOCK_SYX_*` in [build.py](../build/build.py) and `stock.syx_*` in [patch.json](../build/patch.json); the section-3 hash and size as `OS_STOCK_MAIN_*` in `profile.sh` and `stock.section3_*` in `patch.json`. [scripts/check_os_folders.py](../../../scripts/check_os_folders.py) checks that they agree.

## What inspect.sh prints

`./scripts/inspect.sh 1.54` checks the stock file's SHA-256 (`stock file ok`) and prints the tool's summary:

```
device    : Digitakt (0x0a)
version   : 1.54
container : ELE3, 1123360 B
sections  :
  id 5   meta            15 B (raw)
  id 2   DSP          26846 B
  id 3   MAIN OS    2479680 B
  id 4   updater      32776 B (raw)
  id 8   ?           159948 B
checksums : ok
```

The `version` line is the value of `OS_REPORTED_VERSION` in [profile.sh](../profile.sh), and the section ids are its `OS_CONTAINER_SECTION_IDS`. The pinned tool has no name for id 8. `./scripts/inspect.sh -v 1.54` prints the full report.

## The sections of the stock image

Digitakt OS 1.54 has no signature trailer ([notes/stock_image.md](../notes/stock_image.md#integrity)). `report.txt` therefore holds no key material, but it still describes Elektron's image: keep it in `work/`.

`./scripts/extract.sh 1.54` writes these sections to `work/dt_1.54/`:

| id | File (pinned tool) | Size | `dst` in the container | Loads / runs at |
|---|---|---:|---|---|
| 5 | `section_5_meta.raw` | 15 B | `0x00000000` | (metadata, stored raw) |
| 2 | `section_2_DSP.bin` | 26,846 B | `0x03000900` | code at `0x80000414`, after its 24-byte inner header |
| 3 | `section_3_MAIN_OS.bin` | 2,479,680 B | `0x40000400` | `0x40000400` (entry `0x400004e8`) |
| 4 | `section_4_updater.raw` | 32,776 B | `0x80000400` | `0x80000400` (stored raw) |
| 8 | `section_8_?.bin` | 159,948 B | `0x00000000` | not on the Digitakt's processor ([notes/stock_image.md](../notes/stock_image.md#section-8)) |

**MAIN OS addresses:** everything in this OS folder uses load addresses. For section 3, file offset = load address − `0x40000400`.

## The round trip

```sh
./scripts/inspect.sh 1.54      # summary of your stock file; must say "checksums : ok"
./scripts/extract.sh 1.54      # sections + report.txt -> work/dt_1.54/
./scripts/roundtrip.sh 1.54    # rebuild unmodified -> verify -> re-extract -> cmp; must print "round-trip OK"
```

On the stock file with the pinned, capped tool the container shrinks from 1,123,360 to 1,063,264 bytes and `rt.syx` is 1,347,616 bytes; all five sections come back byte-identical, and the script prints `round-trip OK`.

## Expected result

The stock input is fixed:

| | SHA-256 | size |
|---|---|---:|
| stock `.syx` | `f78ba80fa7b1da5fb0e1ff61ad61e9e71aafe79f4364fc49679f3651353e3cf6` | 1,423,776 B |
| stock section 3 | `5c58bf9e3949ef09977c5fc007a61e8d026931f67f1621238379dfb8ee4d31a2` | 2,479,680 B |

The hashes of the result are recorded in [`os/1.54/build/patch.json`](../build/patch.json) (`result`).

| | SHA-256 | size |
|---|---|---:|
| patched section 3 (`result.section3_sha256`) | `d6fac1a35cf565c37fc43ae51bd0c76f1ee7e23c0668ff1d245bb2839e51509c` | 2,479,680 B |
| reference `.syx` (`result.syx_sha256_reference`), pinned tool only | `9df62a0bccfc70598afceea169d048b16ca4cb81a500bf9e4e03a64d76418390` | 1,351,456 B |

With the pinned tool, `python3 os/1.54/build/build.py` writes `out/1.54/dt_og_plus_plus_v0.2.1_9df62a0b.syx`. `python3 os/1.54/build/verify.py` labels the two files `stock Digitakt OS 1.54` and `DT OG++ (reference build)`, and, with `--tool`, their MAIN OS sections `stock MAIN OS` and `DT OG++ MAIN OS`.

The image still reports OS version 1.54 on the device; the build does not set a version string.

## Protected ranges

These hold the code that receives and flashes an OS update. Leaving them exactly as stock keeps the way back to stock firmware open. Addresses are load addresses.

| range | what it is |
|---|---|
| `0x400e8c68`–`0x400ea596` | NOR flash driver (DSPI), with its timer wait/ISR and driver getters |
| `0x40068e54`–`0x40068e86` | DSPI initialization |
| `0x40066b26`–`0x4006735e` | mid-level flash operations |
| `0x40067782`–`0x40068d8a` | OS-update transfer task |
| `0x40081770`–`0x40081794` | `DigitaktSysex` destructor |
| `0x40059810`–`0x40059830`, `0x4005ac40`–`0x4005b1ac`, `0x4005b1ac`–`0x4005b2cc`, `0x40151454`–`0x40151504`, `0x40151504`–`0x40151544` | SysEx receive menu, with its entry thunks |
| `0x4008d6c2`–`0x4008dbce` | storage bulk transfer |
| `0x4006685c`–`0x40066b26`, `0x400673be`–`0x40067782`, `0x40068d8a`–`0x40068dd4`, `0x40068e06`–`0x40068e36`, `0x40068e86`–`0x40068ea8`, `0x400692d2`–`0x400692fe`, `0x40069636`–`0x40069b04`, `0x40069d88`–`0x4006aada`, `0x40081794`–`0x400817ae`, `0x4008d4c4`–`0x4008d6c2` | code the update path also uses: the transfer task's launcher and helpers, the update-mode branch of the init task, the result views and erase job, storage-transfer helpers |

Sections 2, 4, 5 and 8 are never modified. The round trip in step 6 of [What build.py checks](../../../docs/building.md#what-buildpy-checks) proves it for every build.

This table mirrors `PROTECTED` in [os/1.54/build/build.py](../build/build.py); the reasons are in [notes/update_moat.md](../notes/update_moat.md); they change together.

## The capped tool on this image

Elektron's own OS 1.54 image keeps every back-reference in the compressed MAIN OS within 1,048,324 bytes (`0xfff04`), under the capped tool's limit of `0x100000`. The capped tool's rebuild of stock and the reference build both stay within 1,047,658 bytes ([notes/stock_image.md](../notes/stock_image.md#the-1-mb-back-reference-window)).

## What patch.json holds

[`os/1.54/build/patch.json`](../build/patch.json) holds these values, in the fields that [docs/building.md](../../../docs/building.md#what-patchjson-contains) describes:

- `format` and `format_version`: `dt-og-plus-plus-patch`, version 1.
- `target`, `section` and `load_base`: Digitakt OS 1.54, section 3, `0x40000400`.
- `stock`: the SHA-256 and size of the stock `.syx` and of its section 3, as under [The stock file](#the-stock-file).
- `result`: the SHA-256 of the patched section 3, and the reference `.syx` SHA-256 and size, as under [Expected result](#expected-result). They hold only with the pinned tool that `pinned_tool` names.
- `features`: the 14 features of this build, each with its runs. [patch_listing.md](patch_listing.md) lists them with their runs and bytes, and shows every run, with the disassembly for code and a hex dump for data. Each changed byte is listed once, under the last feature that wrote it: the bytes of `poly_engine`, `poly_icon` and `midi_loopback` that the CFO oscillator rewrites (its sixth machine, its names and its icon) are listed under `cfo_oscillator`, and the CFO oscillator's note operand that portamento points at the glided note under `portamento`. The generators in `os/1.54/src/` write these runs ([features/portamento.md](../notes/features/portamento.md#checks)).
