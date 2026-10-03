# Firmware image

## What this is

The Digitakt OS 1.52A update is a single SysEx file. Inside it is an ELE3 container with four
sections, and section 3 (MAIN OS) is the only one this build changes. This note describes the three
layers of the file (SysEx transport, container, compressed section streams) with the exact figures
of the stock file, the checks that protect it, and the firmware tool that unpacks and repacks it.

## The stock file

| | |
|---|---|
| File | `Digitakt_OS1.52A.syx`, 1,162,400 B |
| SHA-256 | `01315133041dcdb8b432146190cc74fc8695c47d8466b0f31bd78cef96fa56a4` |
| Container | ELE3, build/model `0097`, version `1.52A`, 917,072 B |
| Section 3 (MAIN OS), decompressed | 2,221,632 B, SHA-256 `59278368fbe86c9877fad68a578987050e21fc4b418a289cfa1d1351d8e864ee` |
| Build timestamp (section 5) | `250709 11:17:03` (2025-07-09) |

[build/build.py](../build/build.py) refuses any other input file.

## Layer 1: SysEx transport

- **Messages.** 9,083 SysEx messages: a 14 B start marker, 9,081 data packets of 126 B (command
  `0x7E`), and a 14 B end marker.
- **Packet layout.** `00 20 3C <dev> 00 7E block[2] seq <116 B payload> cksum`. The device id is
  `0x0a` for the Digitakt. Each packet is a 9 B header plus 116 B of 8-in-7 payload, which decodes to
  101 bytes.
- **Blocks.** 72 blocks of 14 to 128 packets. The first block has id 1 and sequence numbers 114..127.
- **Per-packet checksum.** A 7-bit sum over body bytes 6..124, each XORed with `(base + i)`, plus
  `base`. `base` is byte 0 of the start marker's info field: `0x05` in this file. All 9,081 packets
  check.
- **Packet count.** The markers declare the number of data packets (info bytes 4..6): 9,081, which
  matches.
- **Decoded stream.** 917,181 B: an 8 B preamble `[u32 container size][u32 content checksum]`, the
  917,072 B container, and padding.

## Layer 2: the ELE3 container

- **Header.** Magic `ELE3`, then the build/model and version strings (the tool finds the version by
  scanning for `<digit>.<digit>` from offset `0x07`). The section count is at `0x1C` and the section
  table at `0x20`, 16 B per entry: `{id, offset, compressed length, dst}`. All fields are big-endian.
- **Content checksum.** `acc += (k+1) ^ word[k]` over the container. Stored `0x5a58985d`, calculated
  `0x5a58985d`.
- **Section ids.** The tool names them 1 FPGA, 2 DSP, 3 MAIN OS, 4 updater, 5 meta, 6 boot, 7 blob.
  OS 1.52A contains 2, 3, 4 and 5 only.

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
  ([hardware.md](hardware.md)). A 24 B inner header `{0x6826, 0x80010000, 0x80000ec0, 0x03000900, 0, 0}`
  comes first, so the code starts at file offset `0x18`. It runs from on-chip SRAM at `0x80000ec0`, the
  header's third word: imported at that base, 87 of its 97 `jsr` targets land inside the section,
  against 0 of 97 at `0x03000900` or `0x80010000`. ⚠️ What its `dst` of `0x03000900` means is not
  known. Its role is described in [section2_map.md](section2_map.md).
- **Section 3 (MAIN OS)** loads at `0x40000400` in DDR and occupies `0x40000400..0x4021ea40`. A file
  offset is the load address minus `0x40000400`. It starts with a 16 B header `[entry][0][0][0]`, entry
  `0x400004e8`, and its code begins with `move.w #$2700,SR`, the usual ColdFire reset prologue. It is
  C++ compiled with GCC, with RTTI class names still present ([hardware.md](hardware.md)).
- **Section 4 (updater)** loads at `0x80000400` in SRAM, entry `0x80000492`, with the same header
  shape and prologue. ⚠️ It is read as the stub that programs the flash during an OS update; see
  [update_moat.md](update_moat.md).

The runtime layout of these addresses is in [memory_map.md](memory_map.md).

## Layer 3: section streams

A compressed section is `[u32 stream length][u32 byte-sum of the stream]` followed by an
aPLib-compatible LZ77 / Elias-gamma stream (offset bias 767, last-offset reuse, end marker = raw
offset 767). The tool copies a section that does not depack as raw bytes.

## Integrity: checksums only

The checks on this image are:

1. the per-packet checksums;
2. the declared packet count;
3. the content checksum;
4. the per-section stream-sums.

All four are plain arithmetic sums. They catch corruption, not tampering, and a rebuild recomputes
every one of them.

**No signature trailer.** The tool also supports a 32 B HMAC-SHA256 trailer at the end of a container.
The 1.52A image has none: the last section ends at `0x0d7e40 + 0x8008 = 917,064`, which rounds up to
16 as 917,072, exactly the declared container size. A rebuild of the 1.52A image reports
`trailer : none`, and the report of a 1.52A image shows no trailer block.

✅ The device accepts a tool-built image: an unmodified round-trip rebuild flashed and ran on the test
unit.

## The firmware tool

This build uses [elektron-firmware-tool](https://github.com/mischa85/elektron-firmware-tool) by
mischa85 (MIT License), pinned to commit `065d18f4195793e61891e387813488ee59f6d1ca`. It is about 1.7 k
lines of C (`main.c`, `decompress.c`, `compress.c`, `integrity.c`, `format.h`) and works file in, file
out. It never talks to a device.

`build/build_tool.sh` builds it from your clone of that commit in `../elektron-firmware-tool`, next to
this repository ([docs/building.md](../docs/building.md)). It never fetches anything. It copies the
clone's sources into a build folder under `tool/`, checks the SHA-256 of every source file, applies
`build/tool_patches/cap_window_1mb.patch` to the copies (the clone itself is never modified), and
compiles them with `cc -O2 -Wall -Wextra` into `tool/bin/elektron-firmware-tool-capped`.

| Task | Command |
|---|---|
| Summary: device, version, container, sections, `checksums : ok / MISMATCH` | `-i <in.syx>` |
| Full report: transport statistics, packet checksums, markers, header, section table with `off / clen / dst`, stream-sum checks | `-i <in.syx> -v` |
| Extract every section | `-i <in.syx> -o <dir>` |
| Extract one section | `-i <in.syx> -d 3 -o <dir>` gives `section_3_MAIN_OS.bin` (`.raw` for a stored section) |
| Rebuild with a replaced section | `-i <in.syx> -c 3 <file.bin> -o <out.syx>` |

A rebuild recompresses the replaced section, rewrites the section table, recomputes the stream-sums,
and rebuilds the preamble and every packet. The other sections are copied unchanged. `-V <version>`
would rewrite the version field in place (space-padded, and it must fit). This build does not use it,
so the device still reports OS 1.52A ([compatibility.md](compatibility.md)). The tool caps
decompression buffers at 64 MB per section. The repo wraps these commands in `scripts/inspect.sh`,
`scripts/extract.sh` and `scripts/roundtrip.sh`.

**Rebuilt files never match Elektron's byte for byte.** The tool's compressor is a cost-optimal parse:
its output decodes to the same bytes, but the compressed stream differs from Elektron's packer.
Rebuilding the stock file with section 3 unchanged, using the upstream tool, gives a 1,094,816 B
`.syx` and a container of 863,760 B instead of 917,072 B. Compare decompressed sections, never containers. Re-extracting that
rebuild gives sections 2, 3, 4 and 5 identical to the stock extraction, and `scripts/roundtrip.sh`
performs exactly that check. For the same reason the build identifies the firmware by its section-3
hash (`34765cdf253546dca117c67db7e3a9c3d70cb8ebf3851bf981ec638c4888e864`). The reference `.syx` hash in
`build/patch.json` is reproduced only with the pinned, patched tool.

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

The patch adds `#define MAX_OFFSET 0x100000` and a `break` in the hash-chain walk (8 lines with their
comments). The chain is ordered by decreasing position, so stopping at the first candidate beyond the
window is correct, and it is also faster. It costs 322 B (0.04 %) of compressed size, 815,864 →
816,186 B.

⚠️ The cap is a precaution, not a known fix. An image built without it flashed and ran correctly
(✅ confirmed on the test unit), so the device demonstrably tolerates offsets above 1 MB. The build uses
the capped tool so that its output stays inside the envelope of Elektron's own images.

## Related notes

- [memory_map.md](memory_map.md): where each section lives at run time.
- [update_moat.md](update_moat.md): the code in section 3 that receives and writes an OS update, and
  why sections 2 and 4 stay byte-identical.
- [section2_map.md](section2_map.md): what section 2 does.
- [flash_recovery.md](flash_recovery.md): getting back to stock firmware.
- [analysis_method.md](analysis_method.md): how the sections are disassembled.
