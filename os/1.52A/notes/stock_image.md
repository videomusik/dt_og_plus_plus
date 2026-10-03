# The stock OS 1.52A image

## What this is

The Digitakt OS 1.52A update is a single SysEx file. Inside it is an ELE3 container with four
sections, and section 3 (MAIN OS) is the only one this build changes. This note holds the exact
figures of the stock file, layer by layer, the unit results that rest on them, and the image evidence
behind [notes/hardware.md](../../../notes/hardware.md). The format of each layer, the checks and the
firmware tool are described in [notes/firmware_image.md](../../../notes/firmware_image.md).

## The stock file

| | |
|---|---|
| File | `Digitakt_OS1.52A.syx`, 1,162,400 B |
| SHA-256 | `01315133041dcdb8b432146190cc74fc8695c47d8466b0f31bd78cef96fa56a4` |
| Container | ELE3, build/model `0097`, version `1.52A`, 917,072 B |
| Section 3 (MAIN OS), decompressed | 2,221,632 B, SHA-256 `59278368fbe86c9877fad68a578987050e21fc4b418a289cfa1d1351d8e864ee` |
| Build timestamp (section 5) | `250709 11:17:03` (2025-07-09) |

[os/1.52A/build/build.py](../build/build.py) refuses any other input file. The values the build and
the scripts check are listed in [reference.md](../docs/reference.md#the-stock-file).

## Layer 1: SysEx transport

- **Messages.** 9,083 SysEx messages: a 14 B start marker, 9,081 data packets of 126 B (command
  `0x7E`), and a 14 B end marker.
- **Blocks.** 72 blocks of 14 to 128 packets. The first block has id 1 and sequence numbers 114..127.
- **Per-packet checksum.** `base`, byte 0 of the start marker's info field, is `0x05` in this file.
  All 9,081 packets check.
- **Packet count.** The markers declare 9,081 data packets (info bytes 4..6), which matches.
- **Decoded stream.** 917,181 B: the 8 B preamble `[u32 container size][u32 content checksum]`, the
  917,072 B container, and padding.

## Layer 2: the ELE3 container

- **Content checksum.** Stored `0x5a58985d`, calculated `0x5a58985d`.
- **Section ids.** OS 1.52A contains 2, 3, 4 and 5 only.

| id | Tool name | Offset | Stored length | Contents | `dst` | Stream-sum |
|---|---|---|---|---|---|---|
| 5 | meta | `0x000070` | `0x00000f` | 15 B, stored raw | `0x00000000` | — |
| 2 | DSP | `0x000080` | `0x003a80` | 26,670 B after decompression | `0x03000900` | `0x0018d157` |
| 3 | MAIN OS | `0x003b00` | `0x0d4334` | 2,221,632 B after decompression | `0x40000400` | `0x058bac61` |
| 4 | updater | `0x0d7e40` | `0x008008` | 32,776 B, stored raw | `0x80000400` | — |

`dst` is the section's load address. For sections 3 and 4 it is also where the code runs, and so the
base a disassembler needs; section 2 is the exception (below).

- **Section 5 (meta)** holds the build timestamp.
- **Section 2 ("DSP")** is ColdFire code, not code for a DSP; the Digitakt has no DSP chip
  ([hardware.md](../../../notes/hardware.md)). A 24 B inner header
  `{0x6826, 0x80010000, 0x80000ec0, 0x03000900, 0, 0}` comes first, so the code starts at file offset
  `0x18`. It runs from on-chip SRAM at `0x80000ec0`, the
  header's third word: imported at that base, 87 of its 97 `jsr` targets land inside the section,
  against 0 of 97 at `0x03000900` or `0x80010000`. ⚠️ What its `dst` of `0x03000900` means is not
  known. Its role is described in [section2_map.md](section2_map.md).
- **Section 3 (MAIN OS)** loads at `0x40000400` in DDR and occupies `0x40000400..0x4021ea40`. A file
  offset is the load address minus `0x40000400`. It starts with a 16 B header `[entry][0][0][0]`, entry
  `0x400004e8`, and its code begins with `move.w #$2700,SR`, the usual ColdFire reset prologue. It is
  C++ compiled with GCC, with RTTI class names still present ([hardware.md](../../../notes/hardware.md)).
- **Section 4 (updater)** loads at `0x80000400` in SRAM, entry `0x80000492`, with the same header
  shape and prologue. ⚠️ It is read as the stub that programs the flash during an OS update; see
  [update_moat.md](update_moat.md).

The runtime layout of these addresses is in [memory_map.md](memory_map.md).

## Integrity

The checks are those of [firmware_image.md](../../../notes/firmware_image.md#integrity-checksums-only).
On the stock file the tool's summary reports `checksums : ok`
([reference.md](../docs/reference.md#what-inspectsh-prints)).

**No signature trailer.** The 1.52A image has none: the last section ends at
`0x0d7e40 + 0x8008 = 917,064`, which rounds up to 16 as 917,072, exactly the declared container size. A rebuild of the 1.52A image reports
`trailer : none`, and the report of a 1.52A image shows no trailer block. `OS_SIGNATURE_TRAILER` in
`os/1.52A/profile.sh` records this as `none`.

✅ The device accepts a tool-built image: an unmodified round-trip rebuild flashed and ran on the test
unit.

## Rebuilds with the firmware tool

The tool's `-V <version>` option would rewrite the version field. This build does not use it, so the
device still reports OS 1.52A ([compatibility.md](compatibility.md)).

Rebuilt files never match Elektron's byte for byte
([firmware_image.md](../../../notes/firmware_image.md#the-firmware-tool)). Rebuilding the stock file
with section 3 unchanged, using the upstream tool, gives a 1,094,816 B `.syx` and a container of
863,760 B instead of 917,072 B. Re-extracting that rebuild gives sections 2, 3, 4 and 5 identical to
the stock extraction; `./scripts/roundtrip.sh 1.52A` performs exactly that check
([reference.md](../docs/reference.md#the-round-trip)).

Because a rebuilt file never matches byte for byte, the build identifies its result by the patched
section-3 hash, `result.section3_sha256` in [patch.json](../build/patch.json)
(`34765cdf253546dca117c67db7e3a9c3d70cb8ebf3851bf981ec638c4888e864`), not by the `.syx` hash. The
reference `.syx` hash in
[os/1.52A/build/patch.json](../build/patch.json) is reproduced only with the pinned, patched tool
([reference.md](../docs/reference.md#expected-result)).

## The 1 MB back-reference window

Parsing the aPLib token streams gives these figures for section 3:

| Stream | Decompresses to | Largest back-reference offset | Longest match |
|---|---|---|---|
| Elektron's, in the stock file | 2,221,632 B | 1,048,572 (`0x0FFFFC`) | 2,048 |
| The upstream tool, rebuilding stock | 2,221,632 B | 2,157,404 | 2,048 |
| The patched tool, rebuilding stock | 2,221,632 B | 1,046,544 | — |

Elektron's compressor keeps every back-reference just under 1 MB. The longest match, 2,048, equals
the tool's own `MAX_MATCH`, so of these two limits the window is the only difference. The upstream
tool's parser has no window limit and reaches 2.1 MB, which asks the on-device depacker for something
the stock image never asks of it. The tool's advantage of about 53 KB over Elektron's packer comes
from its cost-optimal parse, not from the window: the capped tool keeps almost all of it (a container
of 864,080 B instead of 917,072 B on an unmodified rebuild).

The cap ([firmware_image.md](../../../notes/firmware_image.md#the-1-mb-back-reference-window)) costs
322 B (0.04 %) of compressed size, 815,864 → 816,186 B.

⚠️ The cap is a precaution, not a known fix. An image built without it flashed and ran correctly
(✅ confirmed on the test unit), so the device demonstrably tolerates offsets above 1 MB. The build uses
the capped tool so that its output stays inside the envelope of Elektron's own images. The figures
the build docs use are in [reference.md](../docs/reference.md#the-capped-tool-on-this-image).

## Image evidence for the hardware notes

The facts in [notes/hardware.md](../../../notes/hardware.md) that rest on this image.

**Section 2 is ColdFire code.** Its first instructions decode as ordinary ColdFire instructions (RTE,
LEA, JSR, a move to SR, RTS). It has a 24 B inner header
`{0x6826, 0x80010000, 0x80000ec0, 0x03000900, 0, 0}` and references SRAM (`0x8000xxxx`), DDR and the
`0xFC0x_xxxx` / `0xEC0x_xxxx` peripheral windows. What it does is in [section2_map.md](section2_map.md).

**The address windows**, as this image uses them:

| Address | What | Evidence |
|---|---|---|
| `0x4000_0000` | DDR2 SDRAM, 128 MB | MAIN OS `dst 0x40000400`, entry `0x400004e8` (header word 0); code starts with `move.w #$2700,SR`, the ColdFire reset prologue |
| `0x8000_0000` | On-chip SRAM, 64 KB in two 32 KB banks (`0x80000000..0x80010000`) | Updater `dst 0x80000400`, entry `0x80000492`, same prologue. Word 1 of section 2's inner header, `0x80010000`, is the SRAM end. MAIN OS's startup code fills both banks at boot ([memory_map.md](memory_map.md)) |
| `0xFC00_0000`, `0xEC00_0000` | Peripheral space | Absolute references in section 2 and the updater, for example `0xfc04002d`, `0xfc044000`, `0xfc050014`, `0xec070000` |
| `0x0300_0900` | Section 2's `dst` | ⚠️ Not a memory region of the MCF5441x and not where the code runs. Unexplained; possibly a FlexBus mapping or a staging value |

**Other image evidence in the hardware note.** The 132 MHz bus-clock literal and the code that uses
it, the codec's 48 kHz on SSI0 ([section2_map.md](section2_map.md)), and the Ghidra base addresses
are cited in [notes/hardware.md](../../../notes/hardware.md), each labelled OS 1.52A.

## Related notes

- [notes/firmware_image.md](../../../notes/firmware_image.md): the format of each layer, the checks
  and the firmware tool.
- [notes/hardware.md](../../../notes/hardware.md): the chip and its address spaces.
- [memory_map.md](memory_map.md): where each section lives at run time.
- [update_moat.md](update_moat.md): the code in section 3 that receives and writes an OS update, and
  the ranges the build protects.
- [section2_map.md](section2_map.md): what section 2 does.
- [compatibility.md](compatibility.md): what the device reports, and projects moved between this
  build and stock.
- [reference.md](../docs/reference.md): the values the build and the scripts check.
