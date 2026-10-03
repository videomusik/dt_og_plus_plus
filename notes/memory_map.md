# Memory map

## What this is

Where the firmware lives at run time: DDR (the MAIN OS image, `.bss`, the heap, the stacks) and the
64 KB of on-chip SRAM, as MAIN OS's startup code sets them up. The note also lists the RAM this build
uses for its own state, the routes for adding new code that are closed and why, and the method rule
that decides whether a region is really unused. Addresses are load addresses.

## Two address spaces

- **The file.** Section 3 (MAIN OS) decompresses to 2,221,632 B (`0x21E640`) and loads at `0x40000400`,
  so it occupies `0x40000400..0x4021ea40`. A file offset is the load address minus `0x40000400`.
- **Run time.** Each section's `dst` ([firmware_image.md](firmware_image.md)):

| Section | `dst` | Where it runs |
|---|---|---|
| 5 meta, 15 B | `0x00000000` | build stamp, not code |
| 2, 26 KB | `0x03000900` | from SRAM at `0x80000ec0` |
| 3 MAIN OS, 2.2 MB | `0x40000400` | DDR |
| 4 updater, 32 KB | `0x80000400` | SRAM, during an OS update |

The standard linker sections apply: `.text` (code), `.rodata` (constants, including every string
literal), `.data` (initialised read-write data) and `.bss` (zero-filled read-write data). The
ColdFire stack is full-descending (SP = A7, pre-decrement).

## DDR

The startup chain is `entry_400004e8` → `0x40001c76` → `0x40001ecc` → `0x40001c94` → `0x4000045c` →
`FUN_400004b2` → the application at `0x40120b5a`.

| Range | Size | Contents | Set up by |
|---|---|---|---|
| `0x40000000..0x40000400` | 1 KB | Exception vector table: 256 vectors, default handler `0x40001536`; VBR = `0x40000000` | `entry_40001c76` / `entry_40001c94` |
| `0x40000400..0x40214000` | about 2.08 MB | `.text`, `.rodata`, `.data`: loaded, and never cleared | the OS-update loader |
| `0x40214000..0x4021ea40` | 42.6 KB | In the image: the two SRAM-init images (below). The startup code copies them to SRAM, then `.bss` covers them | `entry_4000045c`, then `FUN_400004b2` |
| `0x40214000..0x439902a0` | about 55.5 MB | `.bss`, zeroed at boot: `0x377c2a` × 16 B from `0x40214000`. It contains the 16 MB heap pool `0x4295d950..0x4395d950` (allocator `FUN_400c408c`; base and end come from the control words `DAT_401d1a48` and `DAT_401d1a50` in `.data`) | `FUN_400004b2` |
| `0x439902a0..` about `0x48000000` | about 70 MB | ⚠️ Very probably the sample memory. The Digitakt's specified 64 MB of sample memory cannot fit in `.bss` (about 55.5 MB, the 16 MB heap included), so it must lie mostly here; where it starts has not been located. A `FindAddressLiterals` sweep of `0x439902a0..0x48100000` finds only the stack top and numeric constants, and the highest static `.bss` reference is `0x439901ec`, so its base does not appear as a plain address literal (it may be computed, or be one of those constants). This build's state lives at the bottom of the region (below) | — |
| `0x405d07f4` downward | — | A second stack, inside `.bss`, set by `move.l #0x405d07f4,sp` in `entry_40001ecc`. ⚠️ Read as the interrupt/supervisor stack | `entry_40001ecc` |
| `0x48000000` downward | — | The main stack, set at `0x400004f2` | startup code |

The `.bss` boundary `0x40214000` is a linker boundary (the end of `.data`, rounded up to 16 KB) in the
middle of the one DDR chip. The chip boundaries are `0x40000000` (DDR, 128 MB), `0x80000000` (on-chip
SRAM, 64 KB) and `0xFC0xxxxx` (peripherals).

**Inside the stable region.** Code sits in two windows, `0x400004b2..0x40162748` and a small island at
`0x40210e4a..0x40211ef2`; the rest of `0x40000400..0x40214000` is `.rodata` and `.data`. The C++ static
initialiser table (`.init_array`, 116 entries) starts at `0x40213980` and ends at `0x40213b50`. What
follows up to `0x40214000` is linker padding, which this build uses for constant data
([landing_pads.md](landing_pads.md)).

⛔ Ruled out: putting data in the image tail at or above `0x40214000`. `FUN_400004b2` zeroes `.bss`
from there at boot, so a string placed at `0x4021da00` displays empty. ✅ Seen on the test unit.

## RAM used by this build

All of it lies above the `.bss` end at `0x439902a0` (the end the startup code uses, at `0x400004c2`),
so none of it is initialised at boot. Every use is masked or written before it is read, so start-up
garbage is harmless; a voice that has never been triggered is not playing. ✅ No problem from it on
the test unit. Each tenant was placed after an image-wide scan found its bytes unreferenced.

| Address | Size | Contents | Feature |
|---|---|---|---|
| `0x439902a4` | 8 B | `prev[8]`: the last trig bit seen per track (edge detect) | [SLICE round robin](features/slice_round_robin.md) |
| `0x439902b0` | 32 B | `counter[8]` (u32), indexed by pool source | SLICE round robin |
| `0x439902d0` | 8 B | `slice[8]`: the latched slice per voice. It is `counter + 32`, so one base register reaches both arrays | SLICE round robin |
| `0x439902d8` | 16 B | free | — |
| `0x439902e8` | 8 B | `ownerTrack[8]`: the track whose note-on took each voice. It is `groupSource − 8`, so one `lea 0x439902f0` reaches both arrays | [owner latch](features/owner_latch.md) |
| `0x439902f0` | 8 B | `groupSource[8]`: each track's pool source | POLY voice pool ([ledger](function_ledger.md#the-pool-map-build-and-refresh), [patch listing](../docs/patch_listing.md#poly-engine)) |
| `0x439902f8` | 8 B | `groupCursor[8]`: the voice used last, per pool source | POLY voice pool, [voice allocation](features/voice_allocation.md) |
| `0x43990300` | 128 B | The loopback byte ring: 32 slots × 4 B, each holding the 3 bytes of one note | [MIDI Loopback](features/midi_loopback.md) |
| `0x43990380` | 1 B | The ring index, masked to 0..31 | MIDI Loopback |
| `0x43990381` | 127 B | free | — |

The build's code names six of these addresses directly (`0x439902a4`, `0x439902b0`, `0x439902f0`,
`0x439902f8`, `0x43990300`, `0x43990380`); `slice[]` and `ownerTrack[]` are reached by displacement from
those base registers. The region above `.bss` is not free space: it very probably holds the sample memory
(table above), and ⚠️ that this build's few hundred bytes at its bottom do not overlap it rests on the literal
sweep and on no problem seen on the test unit, not on a located base
([open_questions.md](open_questions.md#where-the-sample-memory-starts)). Code space is the tighter constraint
([landing_pads.md](landing_pads.md)).

## On-chip SRAM

64 KB in two 32 KB banks, `0x80000000..0x80010000`. The MAIN OS startup code initialises all of it
from the image tail, in two copy-and-zero pairs:

| Step | Source | Destination |
|---|---|---|
| copy image 1 | DDR `0x40214000..0x40217350` (13,136 B, almost all zero) | SRAM from `0x80000000` |
| zero | — | the rest of bank 1, up to `0x80008000` |
| copy image 2 | DDR `0x40217350..0x4021ea40` (30,448 B; non-zero only in `0x4021b350..0x4021d9b3`) | SRAM from `0x80008000` |
| zero | — | the rest of bank 2, up to `0x80010000` |

The SRAM map while MAIN OS runs, with section 2's use of the same addresses (those rows hold only
while section 2 runs):

| Range | Occupant |
|---|---|
| `0x80000000..0x80003xxx` | MAIN OS's voice-parameter arrays: the u16 mirror at `0x80001502` (stride `0x6a`) and its 16.16 expansion at `0x80002B50` (stride `0xd4`), for eight tracks plus master ([render_path.md](render_path.md)). Section 2's code (`0x80000ec0..0x800076d6`, [section2_map.md](section2_map.md)) also loads across this range, so the two are not resident together. ⚠️ How and when section 2 is copied into bank 1 is open |
| up to `0x80007644`, downward | Section 2's stack (`move.l #0x80007644,sp` at `0x800065d2`) |
| `0x80008000..0x80008300` | Section 2's scalar variables (about 25, up to `0x800082f6`) |
| `0x80008000..0x8000bf80` | MAIN OS's USB/DMA descriptor rings and buffers (below) |
| `0x8000bf80..0x8000c000` | About 128 B: the only unclaimed SRAM |
| `0x8000c000..0x80010000` | MAIN OS fast data: a 16 KB-aligned table at `0x8000c000` (image address `0x4021b350`) and about 190 variables. Section 2 touches `0x8000f004..0x8000f010` |

**The descriptor rings.** The low driver cluster builds them at run time at fixed addresses:

| Range | Layout | Built by |
|---|---|---|
| `0x80008000..0x80008800` | 32 × `0x40` descriptor ring | `FUN_40002f30` |
| `0x80008800..0x80008c00` | 16 × `0x40` descriptor ring | `FUN_4000352a` |
| `0x80008c00..0x8000ba00` | 32 × `0x170` buffers | `FUN_400031f4` |
| `0x8000ba00..0x8000bf80` | 16 × `0x58` buffers | `FUN_4000352a` |

They are recognisable by a `0xdead0001` sentinel written to each descriptor head, length and flag
words (`0x1700080`, `0x580080`), page alignment (`(base + n*size) & 0xfffff000`), and submission through
`FUN_40005d48(queue, desc)`. The other functions in the cluster are `FUN_400032fc`, `FUN_40003664`,
`FUN_40003970`, `FUN_40003984`, `FUN_40003998`, `FUN_40003b6e`, `FUN_40003c46`, `FUN_40003e04` and
`FUN_40003fd2`. Fifteen instructions in total build addresses into the range; in a decompile they show
as negative constants, for example `-0x7fff8000` = `0x80008000`, `-0x7fff7400` = `0x80008c00`,
`-0x7fff4600` = `0x8000ba00`. In the image the region is all zeros, because the rings are built at
run time, not loaded.

The updater (section 4) also uses bank 2 heavily for its own variables (381 address literals, for
example `movea.l (0x8000b568).l,a0` and `clr.l (0x80008550).l`). It runs instead of MAIN OS during a
flash, so the two do not collide.

## The rule: reference queries are not enough

A Ghidra reference query reports memory *references*. Driver code often builds addresses from
*immediates* instead: `addi.l #0x8000ba00,d0` or `adda.l #0x80008800,a0` produce a scalar operand, not
a reference. A region owned by fixed-address DMA rings is therefore invisible to `DumpRefsInRange`,
`RefDensityMap` and `FindDeadSpace`, and a reference query over `0x80008300..0x8000c000` returns zero
from both MAIN OS and section 2.

⛔ **Never call a region unused on reference queries alone.** Run `FindAddressLiterals`, which scans
every instruction's operand scalars, first. Zeros in the image and a plausible explanation (such as
alignment padding before a 16 KB-aligned table) are not evidence either: a region built at run time
looks exactly like that.

Raw big-endian word scans of the section are a superset check only. On code they are noisy, because
opcode pairs such as `41f9` or `46fc` read as `0x4xxxxxxx` values, and at unaligned offsets they invent
hits. Use them to find candidates, then confirm in Ghidra or with `m68k-elf-objdump -m m68k:cfv4e`.

## `.bss` occupancy

- A reference map finds 2,116 static destinations spread over the whole 55 MB: lowest `0x40214000`,
  highest `0x439901ec`, in about 40 clusters. The linker lays `.bss` out in link order, so small
  scalar clusters alternate with giant buffers all the way up.
- The gaps between clusters are big static buffers whose interiors have no static reference (they are
  accessed as base plus offset): 16 MB (`0x4295d958..0x4395d94c`, the heap), 8.5 MB, 7.3 MB, 3.6 MB × 4,
  3.1 MB × 2, 1.8 MB, 1.5 MB and smaller, about 52 MB of the 55. For how invisible an interior can be:
  `FUN_4006753e` zeroes `0x80008` B (512 KB) from `0x40225e70` with one reference. ⚠️ `0x402506c0` is
  referenced inside that range; what sub-object it is has not been resolved.
- Low `.bss`, `0x40214000..0x40240000`, has 95 destinations:
  - `0x40214000..0x40215000`: a 4 KB structure walked at `0x40004f66`;
  - `0x40214184..0x402141a0`: driver scalars (`FUN_40004e44`, timer `0xfc0b014c`);
  - `0x40214e0c`: a pointer target, from `.data` at `0x4018dbc4`;
  - `0x40218518`: one reference, from `FUN_4011667c`;
  - `0x4021d000..0x4021d604`: a dense cluster belonging to the low driver cluster, plus
    `FUN_4006753e`'s heap-pointer global `DAT_4021d400` and a 512 B buffer at `0x4021d404..0x4021d604`;
  - `0x40225404..0x40225e78`.
- The same reference blind spot applies here. It can only make `.bss` more occupied, so the gap sizes
  above are not evidence of free space.

## Routes for new code

The firmware is not position-independent: globals are addressed with absolute operands, and the image
carries no relocation table. That closes most routes.

| Route | Verdict |
|---|---|
| Relink the image | ⛔ Needs the relocation table back, from source or by recovering it from the stripped binary: classifying every 32-bit word and immediate as pointer or integer, tens of thousands of them with no misses, in 2.2 MB of vtable-heavy C++. A miss shows up later, on the device. |
| Grow or append to the loaded image | ⛔ `.bss` starts right at the end of `.data`, so new bytes land on globals. |
| A new container section loading above `.bss` | ⚠️ Not examined: it depends on whether the update path accepts an extra section. This build keeps the stock section set, and its round-trip check refuses any other. |
| Shift all of `.bss` | ⛔ At least 2,116 edits, plus `.data` pointer words, the heap start and startup constants; a miss means silent aliasing. |
| Reuse "undefined and unreferenced" space | ⛔ It is code the analyser never reached, entered through C++ vtables and jump tables. The default exception handler `0x40001536` is flagged unreferenced, yet the vector table points at it, and 182 of 402 such code-to-code runs have their start address stored in a pointer word elsewhere. Genuine alignment padding is about 0 (2-byte alignment), except the 1,200 B of linker padding at `0x40213b50..0x40214000` (a row below); the three other uniform runs of 68 B or more (512, 396 and 314 B) are occupied ([landing_pads.md](landing_pads.md)). |
| Carve from the bottom of `.bss` | ⛔ Occupied, and it is also the source of the SRAM-init images. |
| SRAM bank 2 | ⛔ The USB/DMA rings. Code placed there is overwritten as soon as the driver initialises. About 128 B is really free. |
| Overwrite in place at equal length | ✅ Used for constants and descriptor limits. |
| Dead functions as landing pads | ✅ Used for all new code ([landing_pads.md](landing_pads.md)). |
| The `.rodata` padding after `.init_array` | ✅ Used for constant data such as icons ([landing_pads.md](landing_pads.md)). |
| RAM above `.bss` | ✅ Used for all new state (the table above). |

## Tools

The query scripts are in `scripts/ghidra/` and run through `scripts/ghidra_query.sh`. They only read
the project:

- `RefDensityMap <lo> <hi> [bucket] [gaps]`: the whole-`.bss` map is
  `./scripts/ghidra_query.sh dt main RefDensityMap 0x40214000 0x439902a0`.
- `DumpRefsInRange <lo> <hi> [maxSources] [bucket]`: the low-`.bss` detail is
  `./scripts/ghidra_query.sh dt main DumpRefsInRange 0x40214000 0x40240000`.
- `FindDeadSpace <start> <end> [minLen]`: the undefined-and-unreferenced pass is
  `./scripts/ghidra_query.sh dt main FindDeadSpace 0x40000400 0x40214000 16`.
- `FindAddressLiterals <lo> <hi> [bucket]`: the scan that finds the DMA rings, for example
  `./scripts/ghidra_query.sh dt main FindAddressLiterals 0x80000000 0x80010000 0x800`.

How complete the disassembly is, and how it was made so, is in [analysis_method.md](analysis_method.md).

## Related notes

- [firmware_image.md](firmware_image.md): the sections and their load addresses.
- [landing_pads.md](landing_pads.md): the code pads and the `.rodata` budget.
- [render_path.md](render_path.md): the SRAM voice arrays in use.
- [section2_map.md](section2_map.md): section 2's own use of SRAM.
- [update_moat.md](update_moat.md): the ranges no patch may touch.
