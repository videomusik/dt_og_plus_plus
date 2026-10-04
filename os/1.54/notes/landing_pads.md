# Landing pads

## What this is

All new code in this build lives in landing pads: stock functions that nothing in the firmware
calls, overwritten in place. This note gives the pad table for OS 1.54, the evidence that each pad is
dead in this image, and the `.rodata` space used for constant data. The method (where candidates come
from, the vetting recipe, the rules for code in a pad, the `.rodata` survey) is in
[landing_pad_method.md](../../../notes/landing_pad_method.md).

⚠️ A pad is trusted only once it has run on a unit ([AGENTS.md](../../../AGENTS.md)). No pad in this
note has yet run on a unit with OS 1.54, so every pad here is a vetted candidate, not a trusted pad.

## The pads

All pads are in section 3 and outside the protected ranges of [update_moat.md](update_moat.md). Each
is a stock function, or a span of adjacent stock functions, that this build overwrites. The
occupants are the same code as in the patch listing ([docs/patch_listing.md](../docs/patch_listing.md)).

| Pad | Extent | Size | Occupied by |
|---|---|---:|---|
| `FUN_400c1338` | `0x400c1338..0x400c13f2` | 186 B | SLICE round robin engine and value formatter, with its `RRND` / `RRBN` strings |
| `FUN_400c1138` | `0x400c1138..0x400c11dc` | 164 B | POLY machine-picker navigation, and the note-off region scan with the owner latch's edits |
| `FUN_400c1034` | `0x400c1034..0x400c1080` | 76 B | POLY machine-name pointer table and `"POLY\0"` |
| `0x400bf16c` | `0x400bf16c..0x400bf1e8` | 124 B | POLY kit-load detour and the shared pool-map build routine |
| `0x40037a24` | `0x40037a24..0x40037ade` | 186 B | POLY machine-assign hook, pattern-switch refresh, and the note routing pad of the audio ISR |
| `FUN_400bed3a`, `FUN_400bed9c`, `FUN_400bee02` | `0x400bed3a..0x400bee6a` | 304 B | POLY parameter-read alias, machine alias, knob-follow loop, parameter-write alias, SLICE robin selector, picker-icon lookup, MIDI Loopback dial-number pad |
| span at `0x40015558` | `0x40015558..0x400156e4` | 396 B | pool cursors, MIDI Loopback display pads, private-lane arm and record filter |
| span at `0x400151ac` | `0x400151ac..0x400152d0` | 292 B | MIDI Loopback channel hook and tap |
| the STL span | `0x401770a6..0x40177194` | 238 B | voice allocation, mute-by-origin detours |

Free code space: 270 B in ten blocks. The largest is 144 B contiguous, `0x40177104..0x40177194`,
the STL span's tail, filled with `clrl %d0 ; rts`.

## Why each pad is dead in this image

✅ Read directly in the code of the stock OS 1.54 image, for every pad above:

1. **What the pads hold in stock.** C++ `+=` operator-overload copies (the span at `0x400bed3a`),
   bounds-checked array-element address calculators (the spans at `0x40015558` and `0x400151ac`),
   three `std::list` template instantiations (the STL span) and the other five functions in the
   table (objdump). How these were picked from the image, by comparing it with the image the pads
   were first vetted in, is in the shared
   [version comparison](../../../notes/version_comparison_1.52A_1.54.md#the-landing-pads).
2. **Raw pointer scan.** Every byte offset of the stock section read as a big-endian 32-bit word:
   values pointing into a pad, from outside it. Seven pads: 0. Two pads have hits, and each hit is a
   word that straddles two instructions:
   - `FUN_400c1338`: the word `0x400c13c2` at `0x40001e4a`, inside
     `13c2 ec09 400c 13c2`, two `move.b %d2,0xec09400c` stores to a FlexBus register;
   - `FUN_400c1034`: the word `0x400c1039`, twice, at `0x400d5008` and `0x400d500e` in `FUN_400d4fc6`,
     inside `1039 ec07 400c 1039`, `move.b 0xec07400c,%d0` loads from a FlexBus register.

   Each is the low half of a peripheral address followed by the next opcode, not a pointer.
3. **Listing scan.** Every operand of every instruction in an objdump listing of the whole section
   (844,437 lines), PC-relative targets included as objdump resolves them, that names an address
   inside a pad from outside it: 0 for every pad.
4. **Positive controls**, the same scans on two live functions: the setter `FUN_400225ca` (raw 4,
   listing 5; the extra listing hit is a PC-relative `lea`, invisible to the raw scan) and the voice
   build `FUN_400772e6` (raw 6, listing 6).

## `.rodata`: the constant-data budget

**`0x40252724..0x40253000`, 2,268 B**, after the C++ static-initialiser table:

- The table's walker loads its count, 130, from `0x40252518` and walks 130 entries from `0x4025251c`;
  the last entry is at `0x40252720`, so the table ends at `0x40252724` (objdump of the walker at
  `0x40068fea`, and the count word).
- `0x40253000` is where `.bss` starts: the startup code zeroes from there
  ([memory_map.md](memory_map.md)).
- The whole range is `0x00`, and no word of the image, at any byte offset, points into it (raw scan).

This build puts its icons there:

| Address | Size | Contents |
|---|---|---|
| `0x40252b50` | 68 B | the robin's colour plane |
| `0x40252b94` | 28 B | the robin's `Bitmap` struct |
| `0x40252bb0` | 44 B | the POLY keyboard icon's colour plane |
| `0x40252bdc` | 28 B | the POLY icon's `Bitmap` struct |
| `0x40252bf8` | 8 B | the machine-picker bitmap selector table |

The `Bitmap` structs point at the `Bitmap` vtable (`0x401b7734`, the RTTI vtable `0x401b772c` + 8)
and at stock masks: `0x4024de68` for the robin, a 17 × 17 all-ones mask (`ffff8000` seventeen
times; hexdump), and `0x4023e0a0` for the POLY icon.

## Related notes

- [landing_pad_method.md](../../../notes/landing_pad_method.md): the method.
- [update_moat.md](update_moat.md): the ranges no pad may touch.
- [memory_map.md](memory_map.md): the RAM this build uses.
- [docs/patch_listing.md](../docs/patch_listing.md): every patched byte run, by feature.
