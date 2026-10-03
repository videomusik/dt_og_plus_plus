# The stock OS 1.54 image

## What this is

The Digitakt OS 1.54 update is a single SysEx file. Inside it is an ELE3 container with five
sections, and section 3 (MAIN OS) is the only one this build changes. This note holds the exact
figures of the stock file, layer by layer, and what each section is. The format of each layer, the
checks and the firmware tool are described in
[notes/firmware_image.md](../../../notes/firmware_image.md).

## The stock file

| | |
|---|---|
| File | `Digitakt_OS1.54.syx`, 1,423,776 B |
| SHA-256 | `f78ba80fa7b1da5fb0e1ff61ad61e9e71aafe79f4364fc49679f3651353e3cf6` |
| Container | ELE3, build/model `0107`, version `1.54`, 1,123,360 B |
| Section 3 (MAIN OS), decompressed | 2,479,680 B, SHA-256 `5c58bf9e3949ef09977c5fc007a61e8d026931f67f1621238379dfb8ee4d31a2` |
| Build timestamp (section 5) | `260930 11:05:04` |

[os/1.54/build/build.py](../build/build.py) refuses any other input file. The values the build and
the scripts check are listed in [reference.md](../docs/reference.md#the-stock-file).

## Layer 1: SysEx transport

- **Messages.** 11,125 SysEx messages: a 14 B start marker, 11,123 data packets of 126 B (command
  `0x7E`), and a 14 B end marker.
- **Blocks.** 88 blocks of 14 to 128 packets. The first block has id 1 and sequence numbers 114..127.
- **Per-packet checksum.** `base` is `0x05`. All 11,123 packets check.
- **Packet count.** The markers declare 11,123 data packets, which matches.
- **Decoded stream.** 1,123,423 B: the 8 B preamble, the 1,123,360 B container, and padding.

## Layer 2: the ELE3 container

- **Content checksum.** Stored `0x731e4a9f`, calculated `0x731e4a9f`.
- **Section ids.** OS 1.54 contains 2, 3, 4, 5 and 8.

| id | Tool name | Offset | Stored length | Contents | `dst` | Stream-sum |
|---|---|---|---|---|---|---|
| 5 | meta | `0x000080` | `0x00000f` | 15 B, stored raw | `0x00000000` | — |
| 2 | DSP | `0x000090` | `0x003b18` | 26,846 B after decompression | `0x03000900` | `0x00193043` |
| 3 | MAIN OS | `0x003bb0` | `0x0ed454` | 2,479,680 B after decompression | `0x40000400` | `0x06322e66` |
| 4 | updater | `0x0f1010` | `0x008008` | 32,776 B, stored raw | `0x80000400` | — |
| 8 | (none) | `0x0f9020` | `0x0193f8` | 159,948 B after decompression | `0x00000000` | `0x00b543ef` |

The pinned firmware tool has no name for id 8 and prints `?`; its extract is `section_8_?.bin`.

- **Section 5 (meta)** holds the build timestamp.
- **Section 3 (MAIN OS)** loads at `0x40000400` in DDR and occupies `0x40000400..0x4025da40`. A file
  offset is the load address minus `0x40000400`. It starts with a 16 B header `[entry][0][0][0]`, entry
  `0x400004e8`, and its code begins with `move.w #$2700,SR`. It is C++ compiled with GCC, with RTTI
  class names still present.
- **Section 4 (updater)** has the 16 B header `[0x80000492][0][0][0]`, the same size as before
  (32,776 B), and loads at `0x80000400`.
- **Section 2** is ColdFire code after a 24 B inner header `{0x68d6, 0x80010000, 0x80000eaa,
  0x03000900, 0, 0}`. Its first word is the section size − 8. It runs at `0x80000414`
  ([below](#section-2s-run-base)).
- **Section 8** is not ColdFire code ([below](#section-8)).

## Section 2's run base

✅ Read directly in the code: the code after the 24 B inner header runs at **`0x80000414`**. Two
independent tests over the whole section, each run at every even candidate base in SRAM
(`0x80000000..0x80010000`):

1. **Absolute call targets.** 95 `jsr abs.l` instructions. At `0x80000414`, 75 of their targets land
   on the byte right after an `rts` or `rte`, a function start. No other base comes close (the next
   best: 14).
2. **String references.** 83 NUL-terminated strings in the code. At `0x80000414`, 67 of them are
   referenced by a 32-bit literal elsewhere in the section at exactly their first byte, among them
   `STARTUP MENU`, `TEST MODE`, `KEY TEST`, `ENCODER TEST` and `ENCODER A`. At the header's third
   word `0x80000eaa`, and at `0x80000ec0`, the count is 0.

At `0x80000414`, the header's third word `0x80000eaa` is the start of an ordinary function (it follows
an `rts`), not the base. A test that counts `jsr` targets that merely land inside the section cannot
tell these bases apart: for any base within a few KB, 87 of 97 targets land inside. Calls made with
PC-relative addressing look like function starts at any base, so they cannot either.

⚠️ So section 2 is the bootstrap that holds the STARTUP menu and its test views (strings above). How
its 24 B header maps onto SRAM when it is loaded (byte 0 at `0x800003fc`, or the code at
`0x80000414` after an 8 B prefix at `0x80000404`) needs the loader, which is not in this image.

## Section 8

The pinned tool decompresses it to 159,948 B. It is code for an ARM processor in Thumb mode, not
ColdFire, and it carries an NXP i.MX RT boot header:

- **Thumb code.** Read as little-endian halfwords, section 8 holds 331 `bx lr`, 465 `push {…, lr}` and
  695 `pop {…, pc}`, 2 to 4.5 per KB. The ColdFire sections 2 and 3 of the same image hold 0 to 0.3
  per KB of the same patterns (a count over every even offset).
- **The boot header.** The first 4 KB are `0xff`. At offset `0x1000` the little-endian words are
  `0x412000d1`, `0x60042000`, `0`, `0`, `0x60041020`, `0x60041000`, `0`, `0`, then `0x60000000`,
  `0x100000`, `0`. That is the layout of an i.MX RT Image Vector Table: the header word (tag `0xD1`,
  length `0x20`, version `0x41`), the entry point, two empty fields, the boot-data pointer, the
  table's own address, two empty fields, and the boot data it points to (start `0x60000000`, size
  1 MB, no plugin). `0x60000000` is where the i.MX RT parts map their FlexSPI NOR flash, which they run
  code from directly (hexdump).
- Its last word, read little-endian, is `0x2000a3dc`, an address in the range i.MX RT parts use for
  their tightly coupled data RAM.

⚠️ So section 8 is the firmware of an i.MX RT microcontroller, linked to run from its own flash at
`0x60041000`, with entry `0x60042000`. The Digitakt itself has no such part
([hardware.md](../../../notes/hardware.md)).

⚠️ It is most likely the firmware of the Outbox 8, an external box that OS 1.54 adds support for.
Evidence:

- MAIN OS `FUN_4008e696` reads an 8 B header and a blob from the NOR flash, checksums it, and sends it
  out in 101-byte SysEx chunks with 7-bit packing, a per-chunk checksum and a closing `0xF7`
  (objdump). Its message header is `F0 00 20 3C 17 00 7E`: Elektron's manufacturer id, device id
  `0x17`, and command `0x7E`, the command the Digitakt's own OS packets use (hexdump of the string at
  `0x401cf6b5`).
- The image's strings include `OUTBOX 8 UPDATE REQUIRED`, `OUTBOX 8 CONNECTED` and the
  `BreakOutBox*` view classes (RTTI).

Where the update stores section 8 in the NOR flash, and what reads it there, has not been traced.

## Integrity

The checks are those of [firmware_image.md](../../../notes/firmware_image.md#integrity-checksums-only).
On the stock file the tool's summary reports `checksums : ok`
([reference.md](../docs/reference.md#what-inspectsh-prints)).

**No signature trailer.** The last section, id 8, ends at `0x0f9020 + 0x0193f8 = 1,123,352`, which
rounds up to 16 as 1,123,360, exactly the declared container size. A rebuild reports `trailer :
none`. `OS_SIGNATURE_TRAILER` in `os/1.54/profile.sh` records this as `none`.

## Rebuilds with the firmware tool

The round trip with the pinned, capped tool (`./scripts/roundtrip.sh 1.54`): the container shrinks
from 1,123,360 to 1,063,264 B and `rt.syx` is 1,347,616 B. All five sections come back byte-identical,
section 8 included: the tool copies the sections it does not replace unchanged.

## The 1 MB back-reference window

Parsing the aPLib token streams of section 3 (a parser that follows the pinned tool's `ap_depack`, and
whose output equals the tool's extract byte for byte):

| Stream | Decompresses to | Largest back-reference offset | Longest match |
|---|---|---|---|
| Elektron's, in the stock file | 2,479,680 B | 1,048,324 (`0xfff04`) | 2,048 |
| The capped tool, rebuilding stock | 2,479,680 B | 1,047,658 (`0xffc6a`) | 2,048 |
| The capped tool, the reference build | 2,479,680 B | 1,047,658 (`0xffc6a`) | 2,048 |

Elektron's compressor keeps OS 1.54 under 1 MB as well, so the 1 MB cap of the build's tool keeps the
build inside the envelope of Elektron's own image
([firmware_image.md](../../../notes/firmware_image.md#the-1-mb-back-reference-window)). The streams end
at their last token, with no end marker; the tool depacks with truncation allowed, which treats the
end of the stream as the end.

## Related notes

- [notes/firmware_image.md](../../../notes/firmware_image.md): the format of each layer, the checks
  and the firmware tool.
- [memory_map.md](memory_map.md): where each section lives at run time.
- [update_moat.md](update_moat.md): the code in section 3 that receives and writes an OS update.
- [compatibility.md](compatibility.md): what the device reports.
- [reference.md](../docs/reference.md): the values the build and the scripts check.
