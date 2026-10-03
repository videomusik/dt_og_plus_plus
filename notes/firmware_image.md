# Firmware image

## What this is

A Digitakt OS update is a single SysEx file. Inside it is an ELE3 container with a few sections, and
the MAIN OS section (id 3) is the only one a DT OG++ build changes. This note describes the three
layers of the file (SysEx transport, container, compressed section streams), the checks that protect
it, and the firmware tool that unpacks and repacks it. It holds the format, not the figures of any one
file. Each OS folder records its stock file's figures (OS 1.52A: [stock_image.md](../os/1.52A/notes/stock_image.md)).

## The stock file

Each OS folder names its stock file and records its identity: the file name, size and SHA-256, the
container's build/model and version strings and its size, the size and SHA-256 of the decompressed
MAIN OS section, and the build timestamp where the container carries one (OS 1.52A: in section 5).
That folder's `build/build.py` refuses any other input file
(OS 1.52A: [stock_image.md](../os/1.52A/notes/stock_image.md#the-stock-file)).

## Layer 1: SysEx transport

- **Messages.** A 14 B start marker, the data packets of 126 B each (command `0x7E`), and a 14 B end
  marker.
- **Packet layout.** `00 20 3C <dev> 00 7E block[2] seq <116 B payload> cksum`. The device id is
  `0x0a` for the Digitakt. Each packet is a 9 B header plus 116 B of 8-in-7 payload, which decodes to
  101 bytes.
- **Blocks.** The packets come in blocks: each packet carries its block id (`block[2]`) and a sequence
  number (`seq`).
- **Per-packet checksum.** A 7-bit sum over body bytes 6..124, each XORed with `(base + i)`, plus
  `base`. `base` is byte 0 of the start marker's info field.
- **Packet count.** The markers declare the number of data packets (info bytes 4..6), which must
  match the packets present.
- **Decoded stream.** An 8 B preamble `[u32 container size][u32 content checksum]`, the container,
  and padding.

The counts of a given file (messages, blocks, `base`, decoded size) are in its OS folder
(OS 1.52A: [stock_image.md](../os/1.52A/notes/stock_image.md#layer-1-sysex-transport)).

## Layer 2: the ELE3 container

- **Header.** Magic `ELE3`, then the build/model and version strings (the tool finds the version by
  scanning for `<digit>.<digit>` from offset `0x07`). The section count is at `0x1C` and the section
  table at `0x20`, 16 B per entry: `{id, offset, compressed length, dst}`. All fields are big-endian.
- **Content checksum.** `acc += (k+1) ^ word[k]` over the container, compared with the value stored
  in the preamble.
- **Section ids.** The tool names them 1 FPGA, 2 DSP, 3 MAIN OS, 4 updater, 5 meta, 6 boot, 7 blob.
  An image contains only some of them; each OS folder records which, with its section table
  (OS 1.52A: [stock_image.md](../os/1.52A/notes/stock_image.md#layer-2-the-ele3-container)).
- **Section table.** Each entry gives the section's offset and stored length in the container and its
  `dst`. A section is either compressed (Layer 3), with a stream-sum, or stored raw.

`dst` is the section's load address. Whether it is also where the code runs, and so the base a
disassembler needs, is checked for each section of each image: it is not always so.

The tool's names are labels for the ids, not descriptions of the contents. The shapes below are
those of the OS 1.52A image; check each one again in any other image. The values of the headers, the
load ranges and entries of each section, and where each one runs are in the OS folder
(OS 1.52A: [stock_image.md](../os/1.52A/notes/stock_image.md#layer-2-the-ele3-container)).

What the sections of the OS 1.52A image hold, as an example of what to look for in another version:

- **Section 5 (meta)** holds the build timestamp.
- **Section 2 ("DSP")** is ColdFire code, not code for a DSP; the Digitakt has no DSP chip
  ([hardware.md](hardware.md)). It starts with a 24 B inner header of six words, so the code starts
  at file offset `0x18`. Where it runs is found by importing it at each candidate base (the header
  words and its `dst`) and counting how many of its `jsr` targets land inside the section.
- **Sections 3 (MAIN OS) and 4 (updater)** start with a 16 B header `[entry][0][0][0]`, and their code
  begins with `move.w #$2700,SR`, the usual ColdFire reset prologue.

The run-time layout of the addresses is in the OS folder's memory map
(OS 1.52A: [memory_map.md](../os/1.52A/notes/memory_map.md)).

## Layer 3: section streams

A compressed section is `[u32 stream length][u32 byte-sum of the stream]` followed by an
aPLib-compatible LZ77 / Elias-gamma stream (offset bias 767, last-offset reuse, end marker = raw
offset 767). The tool copies a section that does not depack as raw bytes.

## Integrity: checksums only

The checks on an image are:

1. the per-packet checksums;
2. the declared packet count;
3. the content checksum;
4. the per-section stream-sums.

All four are plain arithmetic sums. They catch corruption, not tampering, and a rebuild recomputes
every one of them.

**Signature trailer.** The tool also supports a 32 B HMAC-SHA256 trailer at the end of a container.
To test an image for one, add the offset and stored length of the last section, round up to 16, and
compare the result with the declared container size: a trailer would fill the difference. The tool
gives two more checks: the `trailer :` line it prints on a rebuild, and whether its report of the
image shows a trailer block. Each OS folder's `profile.sh` records the result in
`OS_SIGNATURE_TRAILER`, and `scripts/extract.sh` warns when it is not `none`. The arithmetic for
OS 1.52A, and the device's acceptance of a tool-built image, are in its folder
(OS 1.52A: [stock_image.md](../os/1.52A/notes/stock_image.md#integrity)).

## The firmware tool

DT OG++ uses [elektron-firmware-tool](https://github.com/mischa85/elektron-firmware-tool) by
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
would rewrite the version field in place (space-padded, and it must fit). DT OG++ does not use it, so
a Digitakt running a DT OG++ build still reports the stock OS version the build was made from
(OS 1.52A: [compatibility.md](../os/1.52A/notes/compatibility.md)). The tool caps decompression
buffers at 64 MB per section. The repo wraps these commands in `scripts/inspect.sh`,
`scripts/extract.sh` and `scripts/roundtrip.sh`.

**Rebuilt files never match Elektron's byte for byte.** The tool's compressor is a cost-optimal parse:
its output decodes to the same bytes, but the compressed stream differs from Elektron's packer, so
even a rebuild of an unmodified stock file differs from that file. Compare decompressed sections,
never containers. Re-extracting a rebuild must give every section identical to the stock extraction,
and `scripts/roundtrip.sh` performs exactly that check. For the same reason a build identifies the
firmware by its section-3 hash. The reference `.syx` hash in an OS folder's `build/patch.json` is
reproduced only with the pinned, patched tool. The rebuild figures and hashes of one OS are in its
folder (OS 1.52A: [stock_image.md](../os/1.52A/notes/stock_image.md#rebuilds-with-the-firmware-tool)).

## The 1 MB back-reference window

Parsing the aPLib token streams of a section gives, for each stream, its largest back-reference offset
and its longest match. In OS 1.52A, Elektron's compressor keeps every back-reference just under 1 MB,
the longest match equals the tool's own `MAX_MATCH`, and the upstream tool's parser, which has no
window limit, reaches far beyond 1 MB
(OS 1.52A figures: [stock_image.md](../os/1.52A/notes/stock_image.md#the-1-mb-back-reference-window)).
Such a stream asks the on-device depacker for something the stock image never asks of it. In OS 1.52A the tool's size
advantage over Elektron's packer comes from its cost-optimal parse, not from the window, so the
capped tool keeps almost all of it.

The patch adds `#define MAX_OFFSET 0x100000` and a `break` in the hash-chain walk (8 lines with their
comments). The chain is ordered by decreasing position, so stopping at the first candidate beyond the
window is correct, and it is also faster.

⚠️ The cap is a precaution, not a known fix: the device has tolerated offsets above 1 MB
(OS 1.52A unit result: [stock_image.md](../os/1.52A/notes/stock_image.md#the-1-mb-back-reference-window)).
The build uses the capped tool so that its output stays inside the envelope of Elektron's own images.

**Measure again for a new OS.** The cap rests on the measurement of one stock file. Before the first
build for another OS version, parse the token streams of its stock MAIN OS section and of a capped
rebuild again: Elektron's largest back-reference offset shows whether 1 MB is still its envelope, and
the rebuild shows what the cap costs.

## Related notes

- [hardware.md](hardware.md): the chip, its address spaces, and what runs where.
- [update_moat_method.md](update_moat_method.md): the code in the MAIN OS section that receives and
  writes an OS update, and why every other section stays byte-identical.
- [flash_recovery.md](flash_recovery.md): getting back to stock firmware.
- [analysis_method.md](analysis_method.md): how the sections are disassembled.
- OS 1.52A: [stock_image.md](../os/1.52A/notes/stock_image.md): every figure of the stock file.
- OS 1.52A: [memory_map.md](../os/1.52A/notes/memory_map.md): where each section lives at run time.
- OS 1.52A: [update_moat.md](../os/1.52A/notes/update_moat.md): the protected ranges.
- OS 1.52A: [section2_map.md](../os/1.52A/notes/section2_map.md): what section 2 does.
