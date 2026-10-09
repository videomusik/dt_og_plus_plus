# Memory map

## What this is

Where the firmware lives at run time in OS 1.54, as far as this build needs it: the MAIN OS image,
`.bss` and the region above it in DDR, the RAM this build uses for its own state, and what is known of
the on-chip SRAM. Addresses are load addresses. The chip's address spaces are in
[hardware.md](../../../notes/hardware.md).

## Sections at run time

| Section | `dst` | Where it runs |
|---|---|---|
| 5 meta, 15 B | `0x00000000` | build stamp, not code |
| 2, 26,846 B | `0x03000900` | ⚠️ from SRAM, code at `0x80000414` ([stock_image.md](stock_image.md#section-2s-run-base)) |
| 3 MAIN OS, 2,479,680 B | `0x40000400` | DDR, `0x40000400..0x4025da40` |
| 4 updater, 32,776 B | `0x80000400` | SRAM, during an OS update |
| 8, 159,948 B | `0x00000000` | ⚠️ not on the Digitakt's processor ([stock_image.md](stock_image.md#section-8)) |

## DDR

| Range | Contents | Evidence |
|---|---|---|
| `0x40000400..0x40253000` | `.text`, `.rodata`, `.data`: loaded and never cleared | the `.bss` start below |
| `0x40252724..0x40253000` | linker padding after the C++ static-initialiser table, 2,268 B, all zero; this build puts its icons there ([landing_pads.md](landing_pads.md#rodata-the-constant-data-budget)) | the walker at `0x40068fea` and its count word `0x40252518` (130) |
| `0x40253000..0x439d1000` | `.bss`, zeroed at boot in 16 B steps | `FUN_400004b2`: `movea.l #0x40253000,%a0` and `move.l #0x439d1000,%d1` (objdump) |
| `0x439d1000..` | ⚠️ very probably the sample memory, whose base does not appear as a plain literal; this build's state lives at its bottom (below) | raw scan, below |

## RAM used by this build

All of it lies just above the `.bss` end `0x439d1000`, so none of it is initialised at boot. Every
use is masked or written before it is read, so start-up garbage is harmless; a voice that has never
been triggered is not playing. Chain Recording's word is used only when its upper half holds the
marker `0xC4A1`, which only this build writes
([features/chain_record.md](features/chain_record.md#the-chain-word)).

| Address | Size | Contents |
|---|---|---|
| `0x439d1004` | 8 B | SLICE round robin: `prev[8]`, the last trig bit seen per track |
| `0x439d1010` | 32 B | SLICE round robin: `counter[8]` (u32), indexed by pool source |
| `0x439d1030` | 8 B | SLICE round robin: `slice[8]`, the latched slice per voice (`counter + 32`) |
| `0x439d1038` | 4 B | Chain Recording: the chain word, marker `0xC4A1` in bits 31..16, the slot count N in bits 15..8 as a signed byte (negative for auto re-arm), the slots done k in bits 7..0 |
| `0x439d103c` | 12 B | free |
| `0x439d1048` | 8 B | owner latch: `ownerTrack[8]` (`groupSource − 8`) |
| `0x439d1050` | 8 B | POLY voice pool: `groupSource[8]` |
| `0x439d1058` | 8 B | POLY voice pool, voice allocation: `groupCursor[8]` |
| `0x439d1060` | 128 B | MIDI Loopback: the byte ring, 32 slots × 4 B |
| `0x439d10e0` | 1 B | MIDI Loopback: the ring index, masked to 0..31 |

Planned, not in the build (the CFO oscillator's test images S11–S14,
[features/cfo_oscillator.md](features/cfo_oscillator.md)):
- `0x439d1100`, 96 B: the phase accumulators, 8 tracks × 3 (u32). Their start-up contents do not
  matter: any phase is a valid start.
- `0x439d1160`, 32 B: the level each track ended its last tick on (u32, `0..0x7fff`). A start-up value
  above `0x7fff` is taken as "no level yet"; one below starts a single tick's ramp from it.

Planned, not in the build (the portamento test image S31, on top of the CFO oscillator's S27;
[features/portamento.md](features/portamento.md)): `0x439d1180`, 40 B, the portamento state:
- `+0`, 8 longs: the note sum each track plays;
- `+32`, long: the marker `0x504f5254` (`PORT`) once the block is initialised;
- `+36`, `+37`, `+38`, bytes, bit = track: a note-on not yet seen by the glide; that note-on is legato
  (S31–S34: the track's gate bit was set; from S35: the track's amp envelope was still in its attack
  or hold, and the glide clears the bit when it takes the note); the track's note sum is valid;
- `+39`, byte, bit = track (S34): this tick's note is legato with LEG on, so its amp envelope is not
  restarted; cleared every tick.

Until the marker is right no track's note sum is taken as valid (and from S34 no envelope held), and a
track whose sum is not valid starts on its target, so start-up garbage is harmless.

The code names `0x439d1004`, `0x439d1010`, `0x439d1038`, `0x439d1050`, `0x439d1058`, `0x439d1060` and
`0x439d10e0` directly ([docs/patch_listing.md](../docs/patch_listing.md)); `slice[]` and `ownerTrack[]` are reached
by displacement from those base registers.

✅ Read directly in the image: a raw scan of the stock section, every byte offset read as a
big-endian 32-bit word, finds exactly one value in `0x439d1000..0x439d2000`: `0x439d1000` itself, the
`.bss` end literal in the startup code. ⚠️ That the region above `.bss` holds the sample memory, and
that this build's few hundred bytes at its bottom do not overlap it, rests on that scan, not on a
located base.

## On-chip SRAM

⚠️ The SRAM layout MAIN OS uses appears unchanged from the image this build was first written for:
in the code the two images have in common, every SRAM address operand of one is the same value in the
other, 1,514 values in all
([version comparison](../../../notes/version_comparison_1.52A_1.54.md#addresses)). This build's code
uses two SRAM addresses, `0x80001228` and `0x800019ac`
([docs/patch_listing.md](../docs/patch_listing.md)).

## Related notes

- [stock_image.md](stock_image.md): the sections and their load addresses.
- [landing_pads.md](landing_pads.md): the code pads and the `.rodata` budget.
- [update_moat.md](update_moat.md): the ranges no patch may touch.
