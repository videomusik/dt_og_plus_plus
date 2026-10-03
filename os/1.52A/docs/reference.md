# OS 1.52A reference values

The values the build and the scripts check for Digitakt OS 1.52A; how they are used: [docs/building.md](../../../docs/building.md) and [docs/extract_sections.md](../../../docs/extract_sections.md).

## The stock file

Download the Digitakt OS 1.52A update from Elektron's support pages for the Digitakt (the original model). The stock update file, `Digitakt_OS1.52A.syx` from Elektron, is saved as:

```
sysex/Digitakt_OS1.52A.syx
```

| | SHA-256 | size |
|---|---|---:|
| stock `.syx` | `01315133041dcdb8b432146190cc74fc8695c47d8466b0f31bd78cef96fa56a4` | 1,162,400 B |
| stock section 3 | `59278368fbe86c9877fad68a578987050e21fc4b418a289cfa1d1351d8e864ee` | 2,221,632 B |

**Check:**

```sh
shasum -a 256 sysex/Digitakt_OS1.52A.syx      # Linux: sha256sum sysex/Digitakt_OS1.52A.syx
```

must print `01315133041dcdb8b432146190cc74fc8695c47d8466b0f31bd78cef96fa56a4` (1,162,400 bytes).
The scripts and the build refuse any other file as the stock image, because every address in this OS folder belongs to exactly that file.

The same values are held three times, on purpose: the `.syx` hash and size as `OS_STOCK_SYX_*` in [profile.sh](../profile.sh), `STOCK_SYX_*` in [build.py](../build/build.py) and `stock.syx_*` in [patch.json](../build/patch.json); the section-3 hash and size as `OS_STOCK_MAIN_*` in `profile.sh` and `stock.section3_*` in `patch.json`. [scripts/check_os_folders.py](../../../scripts/check_os_folders.py) checks that they agree.

## What inspect.sh prints

`./scripts/inspect.sh 1.52A` checks the stock file's SHA-256 (`stock file ok`) and prints the tool's summary:

```
device    : Digitakt (0x0a)
version   : 1.52A
container : ELE3, 917072 B
sections  :
  id 5   meta            15 B (raw)
  id 2   DSP          26670 B
  id 3   MAIN OS    2221632 B
  id 4   updater      32776 B (raw)
checksums : ok
```

The `version` line is the value of `OS_REPORTED_VERSION` in [profile.sh](../profile.sh), and the section ids are its `OS_CONTAINER_SECTION_IDS`. `./scripts/inspect.sh -v 1.52A` prints the full report.

## The sections of the stock image

Digitakt OS 1.52A has no signature trailer; its integrity is the transport, container and section
checksums, all of which the tool recomputes on a rebuild. `report.txt` therefore holds no key
material, but it still describes Elektron's image: keep it in `work/`. [profile.sh](../profile.sh)
records this as `OS_SIGNATURE_TRAILER=none`.

`./scripts/extract.sh 1.52A` writes these sections to `work/dt_1.52A/`:

| id | File (pinned tool) | Size | `dst` in the container | Loads / runs at | SHA-256 |
|---|---|---:|---|---|---|
| 5 | `section_5_meta.raw` | 15 B | `0x00000000` | (metadata, stored raw) | `c30150cad153fcf94898d60e58b3995e9fd07370d3c3e80e5b03d35334856dba` |
| 2 | `section_2_DSP.bin` | 26,670 B | `0x03000900` | `0x80000ec0`, after stripping its 24-byte inner header | `de57e37e4ac803b0f3a2eef1348747684b61e06fc5e00add9126ab4c543bfced` |
| 3 | `section_3_MAIN_OS.bin` | 2,221,632 B | `0x40000400` | `0x40000400` (entry `0x400004e8`) | `59278368fbe86c9877fad68a578987050e21fc4b418a289cfa1d1351d8e864ee` |
| 4 | `section_4_updater.raw` | 32,776 B | `0x80000400` | `0x80000400` (stored raw) | `21a27be7c0b3d3fc0049c6919f3264215f20c757ba3fb9d992e9f1bc0cfa29dc` |

`dst` is the load address the container declares, which is the number disassembly needs, with one
exception: section 2 runs from SRAM at `0x80000ec0` (the base at which its calls resolve), not at its
`dst`; ⚠️ `0x03000900` is read as a staging address. Its run base is word 2 (big-endian) of its
24-byte inner header; see `os_section_meta()` in `os/1.52A/profile.sh`, and the recipe in
`scripts/common.sh`, for how each analysis base is derived. The container layout and the evidence for
these values: [notes/stock_image.md](../notes/stock_image.md#layer-2-the-ele3-container).

**MAIN OS addresses:** everything in this repo uses load addresses. For section 3,
file offset = load address − `0x40000400`.

## The round trip

```sh
./scripts/inspect.sh 1.52A      # summary of your stock file; must say "checksums : ok"
./scripts/extract.sh 1.52A      # sections + report.txt -> work/dt_1.52A/
./scripts/roundtrip.sh 1.52A    # rebuild unmodified -> verify -> re-extract -> cmp; must print "round-trip OK"
```

After `extract.sh`, `work/dt_1.52A/section_3_MAIN_OS.bin` exists (2,221,632 bytes, SHA-256
`59278368fbe86c9877fad68a578987050e21fc4b418a289cfa1d1351d8e864ee`; `extract.sh` checks it and
prints `MAIN OS ok`). `roundtrip.sh` rebuilds section 3 and writes `work/dt_1.52A/roundtrip/rt.syx`
and its re-extract. On the stock file with the pinned, capped tool:

- the container shrinks from 917,072 to 864,080 bytes and `rt.syx` is 1,095,200 bytes, because the
  tool's compressor packs tighter than Elektron's; the `.syx` files differ, so only the
  decompressed sections are compared;
- all four sections come back byte-identical: the script prints four `identical` lines and
  `round-trip OK`, and exits 0.

A derived file next to the sections, such as the `section_2_DSP.from24.bin` that the Ghidra wrapper
creates, is skipped by the comparison.

## Expected result

The stock input is fixed:

| | SHA-256 | size |
|---|---|---:|
| stock `.syx` | `01315133041dcdb8b432146190cc74fc8695c47d8466b0f31bd78cef96fa56a4` | 1,162,400 B |
| stock section 3 | `59278368fbe86c9877fad68a578987050e21fc4b418a289cfa1d1351d8e864ee` | 2,221,632 B |

The hashes of the result are recorded in [`os/1.52A/build/patch.json`](../build/patch.json) (`result`). `build.py` checks the patched section 3 against them in step 3 and prints both hashes at the end, and `verify.py` classifies a file by them.

| | SHA-256 | size |
|---|---|---:|
| patched section 3 (`result.section3_sha256`) | `34765cdf253546dca117c67db7e3a9c3d70cb8ebf3851bf981ec638c4888e864` | 2,221,632 B |
| reference `.syx` (`result.syx_sha256_reference`), pinned tool only | `4812c1691656faadd871e18441d11a90f9a5dcf41aea7c8f16ff211d571978d8` | 1,096,096 B |

With the pinned tool, `python3 os/1.52A/build/build.py` writes `out/1.52A/dt_og_plus_plus_v0.1_4812c169.syx`. `python3 os/1.52A/build/verify.py` labels the two files `stock Digitakt OS 1.52A` and `DT OG++ (reference build)`, and, with `--tool`, their MAIN OS sections `stock MAIN OS` and `DT OG++ MAIN OS`.

The **section-3 hash** identifies the firmware, whichever tool packed it. The **`.syx` hash** matches only when the file was packed by the pinned tool. Another tool version may compress differently and still carry exactly the same firmware; `build.py` then says so, and `verify.py --tool` confirms it by the section-3 hash.

The image still reports OS version 1.52A on the device; the build does not set a version string.

## Protected ranges

These hold the code that receives and flashes an OS update over MIDI or USB. Leaving them exactly as stock keeps the way back to stock firmware open. Addresses are load addresses; section 3 loads at `0x40000400`, so file offset = address − `0x40000400`.

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

Sections 2 (a boot-time loader, which the tool calls "DSP"), 4 (the flash updater) and 5 (metadata) are never modified. The round trip in step 6 of [What build.py checks](../../../docs/building.md#what-buildpy-checks) proves it for every build.

This table mirrors `PROTECTED` in [os/1.52A/build/build.py](../build/build.py); the reasons are in [notes/update_moat.md](../notes/update_moat.md); they change together.

## The capped tool on this image

The compressor in the upstream tool has no window limit: rebuilding the stock image, it emits back-references up to 2,157,404 bytes (about 2.1 MiB). The stock Digitakt OS 1.52A image stays under 1 MiB: in Elektron's own image, no back-reference in the compressed MAIN OS reaches further than 1,048,572 bytes (`0x0FFFFC`). `cap_window_1mb.patch` limits the compressor to offsets of at most `0x100000` (1 MiB, 4 bytes more than that), at a small cost in file size: on this image it costs 322 bytes (0.04 %).

How the window was measured: [notes/stock_image.md](../notes/stock_image.md#the-1-mb-back-reference-window). Why the build uses the capped tool: [docs/building.md](../../../docs/building.md#why-the-capped-tool).

## What patch.json holds

[`os/1.52A/build/patch.json`](../build/patch.json) holds these values, in the fields that [docs/building.md](../../../docs/building.md#what-patchjson-contains) describes:

- `format` and `format_version`: `dt-og-plus-plus-patch`, version 1.
- `target`, `section` and `load_base`: Digitakt OS 1.52A, section 3, `0x40000400`.
- `stock`: the SHA-256 and size of the stock `.syx` and of its section 3, as under [The stock file](#the-stock-file).
- `result`: the SHA-256 of the patched section 3, and the reference `.syx` SHA-256 and size, as under [Expected result](#expected-result). They hold only with the pinned tool that `pinned_tool` names: elektron-firmware-tool at commit `065d18f4195793e61891e387813488ee59f6d1ca` plus `build/tool_patches/cap_window_1mb.patch`.
- `features`: the 11 features of this build, each with its runs. [patch_listing.md](patch_listing.md) lists them with their runs and bytes, and shows every run, with the disassembly for code and a hex dump for data. What each feature does, and the notes that explain its code: [notes/README.md](../notes/README.md#the-features-in-this-build).
