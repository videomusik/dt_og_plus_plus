# Landing pads

## What this is

All new code in this build lives in landing pads: stock functions that nothing in the firmware
calls, overwritten in place. This note gives the pad table for OS 1.54, the evidence that each pad is
dead in this image, the candidate list and vetting controls on this image, and the `.rodata` space
used for constant data. The method (where candidates come
from, the vetting recipe, the rules for code in a pad, the `.rodata` survey) is in
[landing_pad_method.md](../../../notes/landing_pad_method.md).

⚠️ A pad is trusted only once it has run on a unit ([AGENTS.md](../../../AGENTS.md)). ✅ The nine pads
that held the code before Chain Recording have run on the test unit with OS 1.54: an image with all nine
filled with `clrl %d0 ; rts` started and ran, and so did the build whose features live in them
([README.md](README.md#the-test-unit)). ✅ So have the tenth, `FUN_40124a6c` + `FUN_40124ac4`, and the
eleventh, `FUN_40128244` + `FUN_40128288`: each its fill test, and then Chain Recording's code in it.

## The pads

All pads are in section 3 and outside the protected ranges of [update_moat.md](update_moat.md). Each
is a stock function, or a span of adjacent stock functions, that this build overwrites. The
occupants are the same code as in the patch listing ([docs/patch_listing.md](../docs/patch_listing.md)).

| Pad | Extent | Size | Occupied by |
|---|---|---:|---|
| `FUN_400c1338` | `0x400c1338..0x400c13f2` | 186 B | SLICE round robin engine and value formatter, with its `RRND` / `RRBN` strings |
| `FUN_400c1138` | `0x400c1138..0x400c11dc` | 164 B | POLY machine-picker navigation, and the note-off region scan with the owner latch's edits |
| `FUN_400c1034` | `0x400c1034..0x400c1080` | 76 B | POLY machine-name pointer table and `"POLY\0"`; Chain Recording's `mem_pad` in the tail, `0x400c1062..0x400c107e` |
| `0x400bf16c` | `0x400bf16c..0x400bf1e8` | 124 B | POLY kit-load detour and the shared pool-map build routine; Chain Recording's `len_pad` in the tail, `0x400bf1cc..0x400bf1e0` |
| `0x40037a24` | `0x40037a24..0x40037ade` | 186 B | POLY machine-assign hook, pattern-switch refresh, and the note routing pad of the audio ISR |
| `FUN_400bed3a`, `FUN_400bed9c`, `FUN_400bee02` | `0x400bed3a..0x400bee6a` | 304 B | POLY parameter-read alias, machine alias, knob-follow loop, parameter-write alias, SLICE robin selector, picker-icon lookup, MIDI Loopback dial-number pad |
| span at `0x40015558` | `0x40015558..0x400156e4` | 396 B | pool cursors, MIDI Loopback display pads, private-lane arm and record filter |
| span at `0x400151ac` | `0x400151ac..0x400152d0` | 292 B | MIDI Loopback channel hook and tap |
| the STL span | `0x401770a6..0x40177194` | 238 B | voice allocation, mute-by-origin detours; Chain Recording's chain state and `stop_pad` in the tail, `0x40177104..0x40177192` |
| `FUN_40124a6c` + `FUN_40124ac4` | `0x40124a6c..0x40124b32` | 198 B | Chain Recording: `enc_pad` and `arm_pad`, `0x40124a6c..0x40124b24`; the rest keeps its fill. Vetted below |
| `FUN_40128244` + `FUN_40128288` | `0x40128244..0x401282e0` | 156 B | Chain Recording: `fmt_arm`/`fmt_armed` and `no_pad`, `0x40128244..0x401282ce`; the rest keeps its fill. Vetted below |

In the CFO oscillator's test images only (not in `patch.json`):

| Pad | Extent | Size | Occupied by |
|---|---|---:|---|
| `FUN_400f77da` | `0x400f77da..0x400f811e` | 2,372 B | the CFO oscillator: in S11 `0x400f77da..0x400f7aac`; in S12 the code to `0x400f7abe` and the machine name table with its strings `0x400f7ac0..0x400f7b09`; in S13 and S14 the code to `0x400f7b26` and the name tables `0x400f7b28..0x400f7c01`; in S15 the code to `0x400f7b40` and the name tables `0x400f7b40..0x400f7c19`; in S16 the code to `0x400f7bcc` and the name and range tables `0x400f7bcc..0x400f7d41`; in S17 the code to `0x400f7dda` and the name, range and display tables `0x400f7ddc..0x400f7f79`; in S18 the code to `0x400f7f40` and the tables `0x400f7f40..0x400f8111`, 13 B short of the pad's end; in S19 the code to `0x400f7eb6` and the tables `0x400f7eb8..0x400f8089`, 149 B short; from S20 on code only, the tables in the .rodata padding at `0x40252c80`: S20 to `0x400f7ede`, S21 to `0x400f7f52`, S22 to `0x400f7f70`, S23 to `0x400f8060`, 190 B short; the rest keeps its fill. LZ4's streaming compressor, admitted by the recipe's library exception (the candidate list below); its fill test is the stage image S9 |

Free code space: 112 B in twelve blocks, none larger than 18 B: 18 B at `0x401282ce` (fill), 16 B
at `0x400152c0`, 16 B at `0x400bedf2`, 14 B at `0x40124b24` (fill), 10 B at `0x400156da`, 8 B at
`0x400bf1e0`, 8 B at `0x40037ad6`, 6 B at `0x400c1392`, 6 B at `0x400bed96`, 6 B at `0x400bee64`,
2 B at `0x40177192` (the STL span's tail, fill) and 2 B at `0x400c107e`. Chain Recording's code in the
pads: [features/chain_record.md](features/chain_record.md#where-the-code-lives).

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

## The soft-float pad: `FUN_40124a6c` + `FUN_40124ac4`

Two adjacent leaf functions, 88 B and 110 B, with no calls, no peripheral addresses and no MAC
instructions (objdump):

- `FUN_40124a6c` returns 1 when either of its two 32-bit arguments has the IEEE single-precision
  pattern of a NaN: exponent `0xff` and a mantissa other than 0.
- `FUN_40124ac4` does the same for two double-precision arguments: exponent `0x7ff`.

⚠️ Very probably unused helpers from the soft-float support code (an inference from what they do and
from where they sit).

✅ The vetting steps of [landing_pad_method.md](../../../notes/landing_pad_method.md#vetting-a-new-pad),
read in this image:

1. **Candidate.** Both are rows of the candidate list below with `leafPad=PAD`, `ghRefs=0`,
   `ptrWord=-`, `opLit=-`; they are adjacent, so they are vetted as one span.
2. **objdump.** Extent `0x40124a6c..0x40124b32`, from the `rts` at `0x40124b30`.
3. **Raw pointer scan**, every byte offset: 0 words point into the span from outside it.
4. **Listing scan**, hex operands and decimal immediates: 0 instructions outside the span name an
   address inside it.
5. **Switch tables.** Within ±32 KB of the span there are 14 indexed `jmp`s. Walking 512 table words
   from each base finds one word that would land in the span: entry 467 of the table at `0x401258e4`.
   That table has 13 entries: the `cmpl #12` and `bcs` before the `jmp` at `0x401258e0` bound the
   index. So no table reaches the span.
6. **Live twins.** The masks `0x7fffff`, `0xfffff` and `0x7ff` occur in neighbouring routines of the
   same support code, which are separate functions, not inlined copies.
7. **Fall-through.** The instruction before the span is a `braw` (`0x40124a68`); the span is followed
   by a fresh function with a `linkw` (`0x40124b32`).
8. **Moat.** Outside every protected range ([update_moat.md](update_moat.md)).

✅ Its fill test, the stage image S3, started and ran on the test unit with OS 1.54, and so did the
stages with Chain Recording's code in it ([features/chain_record.md](features/chain_record.md#testing-on-the-unit)).

## The frame-registration pad: `FUN_40128244` + `FUN_40128288`

Two adjacent functions, 68 B and 88 B, with no peripheral addresses and no MAC instructions
(objdump). Each links an object record into the list whose head is the word `0x421faa74`;
`FUN_40128288` first allocates the 24 B record (`FUN_400d412c`), so it is not a leaf. Around them
lie two more functions of the same kind: `0x401281fc`, the same code as `FUN_40128244` with two more
fields stored, and the function at `0x401282e0`.

⚠️ Very probably the support library's frame registration (`__register_frame_info`,
`__register_frame` and their siblings), an inference from what they do. A firmware that never
registers frames this way leaves all of them unused.

✅ The vetting steps of [landing_pad_method.md](../../../notes/landing_pad_method.md#vetting-a-new-pad),
read in this image:

1. **Candidate.** Both are rows of the candidate list below with `ghRefs=0`, `ptrWord=-`, `opLit=-`;
   `FUN_40128244` has `leafPad=PAD`. `FUN_40128288` has one callee, the allocator, so it is not a leaf
   as step 1 asks; it is vetted with its neighbour as one span by the steps below.
2. **objdump.** Extent `0x40128244..0x401282e0`, from the `rts` at `0x40128286` and at `0x401282de`.
3. **Raw pointer scan**, every byte offset: 0 words point into the span from outside it.
4. **Listing scan**, hex operands and decimal immediates: 0 instructions outside the span name an
   address inside it.
5. **Switch tables.** Within ±32 KB of the span there are 14 indexed `jmp`s. Walking 512 table words
   from each base finds four words that would land in the span: entries 141 and 243 of the table at
   `0x40125a96`, and entries 64 and 291 of the table at `0x40125ff2`. Each table has 13 entries: the
   `cmpl #12` and `bcs` before its `jmp` (`0x40125a92`, `0x40125fee`) bound the index. So no table
   reaches the span.
6. **Live twins.** The other 13 words that name the list head `0x421faa74` all lie in the
   neighbouring functions of the same code (`0x4012821e` to `0x4012856e`). Neither `0x401281fc` nor
   `0x401282e0` has a reference either (raw and listing scans): the whole family of registration
   functions is unreferenced, as the method's heuristic expects of a dead family.
7. **Fall-through.** The instruction before the span is an `rts` (`0x40128242`); the span is followed
   by a fresh function (`0x401282e0`).
8. **Moat.** Outside every protected range ([update_moat.md](update_moat.md)).

The controls of steps 3 and 4 gave their known counts in the same run
([Vetting controls on this image](#vetting-controls-on-this-image)).

✅ Its fill test, the stage image S6, started and ran on the test unit with OS 1.54, and so did the
stages with Chain Recording's code in it ([features/chain_record.md](features/chain_record.md#testing-on-the-unit)).

## The candidate list on this image

`os/1.54/scripts/ghidra/FindDeadFunctions.java` is the method's script, with the ten known-live
functions of its `KNOWN_LIVE` table taken over through the function map: `0x40077420`, `0x4007041c`,
`0x4006f4be`, `0x40075184`, `0x40067782`, `0x400e11e4`, `0x4006f846`, `0x40076e5a`, `0x40074df2`,
`0x4006fb82`. Run on the `dt_1.54_emac` project (no seeded project of this image exists yet):

```
GHIDRA_LANG_VARIANT=emac ./scripts/ghidra_query.sh 1.54 main FindDeadFunctions
```

It passes 10 of 10 and reports 556 dead candidates of 11,755 functions (63,684 B), 471 of them of
16 B or more (62,748 B), and 150 leaf pads (8,198 B).

⚠️ Without the seeded project, code that Ghidra has not made into functions cannot add references.
Steps 3 and 4 of the vetting, which scan the raw image and a linear objdump listing, cover that gap
for any candidate used.

Spans of adjacent leaf candidates of 120 B or more, other than the pads in use:

| Span | Size | Note |
|---|---:|---|
| `0x40124a6c..0x40124b32` | 198 B | in use, above |
| `0x40128244..0x401282e0` | 156 B | in use, above; the second member is not a leaf |
| `0x400c2d00..0x400c2e12` | 274 B | a masked bitmap copy from the drawing code (it XORs a source through a mask into a destination); its sibling just before has the same body. Not taken as a pad: this build's icons are drawn with masks, and a drawing routine reached through a computed address would escape every scan |
| `0x4001e1d4..0x4001e27e` | 170 B | calls through a vtable (`jsr %a0@`), so not a leaf in fact |
| `0x401365ec..0x4013668a` | 158 B | its code goes on past Ghidra's extent and calls `0x40136538` |
| `0x400025f4..0x4000268e` | 154 B | in the I/O region |
| `0x4001df4e..0x4001dfd6` | 136 B | calls through a vtable |
| `0x40013a9e..0x40013b24` | 134 B | not examined |
| `0x400214e8..0x4002156c` | 132 B | not examined |
| `0x400f77da..0x400f811e` | 2,372 B | `FUN_400f77da`, LZ4's streaming compressor. Not a leaf (two LZ4 helpers and `memcpy`), so step 1 does not admit it; steps 2–8 pass: raw words 2 (one straddles a FlexBus access at `0x400e2170`, one is the odd value `0x400f7fe1` in a word table at `0x40198c6e`), listing 0, the only switch table within ±32 KB (`0x400fae42`, 15 entries) does not reach it, `rts` before and a fresh prologue after. The CFO oscillator's candidate ([features/cfo_oscillator.md](features/cfo_oscillator.md#code-space)) |
| `0x40071a16..0x40071b30` | 282 B | in the audio code |
| `0x4007b2e8..0x4007b56a` | 642 B | in the audio code |
| `0x4024f5be..0x4024f686`, `0x4024f7f0..0x4024f86c` | 200 B, 124 B | in the second code window; the first reads the peripheral registers `0xffff8010..0xffff8013` and data at `0x800xxx`, in SRAM. Not taken as a pad: I/O code |

Most of the smaller leaf candidates call through a vtable (`jsr %a0@`) and are not leaves in fact. Those
with no indirect call, outside the I/O region, the audio code and the second code window, are
`0x400ee05e` (122 B), `0x400e6d1c` (108 B), `0x400e8544` (86 B), `0x40178f20` (86 B), `0x40178e02`
(66 B), `0x400d266e` (60 B), `0x400c2242` (54 B) and `0x401778a4` (52 B): 634 B, not yet vetted.

⛔ A row here is a candidate, never a budget ([landing_pad_method.md](../../../notes/landing_pad_method.md#where-candidates-come-from)).

## Vetting controls on this image

The controls for steps 3 and 4, read in this image:

- the setter `FUN_400225ca`: raw 4, listing 5 (the extra one is PC-relative);
- the voice build `FUN_400772e6`: raw 6, listing 6;
- negative control, the STL span `0x401770a6..0x40177194`, dead and in use: raw 0, listing 0.

## `.rodata`: the constant-data budget

**`0x40252724..0x40253000`, 2,268 B**, after the C++ static-initialiser table:

- The table's walker loads its count, 130, from `0x40252518` and walks 130 entries from `0x4025251c`;
  the last entry is at `0x40252720`, so the table ends at `0x40252724` (objdump of the walker at
  `0x40068fea`, and the count word).
- `0x40253000` is where `.bss` starts: the startup code zeroes from there
  ([memory_map.md](memory_map.md)).
- The whole range is `0x00`, and no word of the image, at any byte offset, points into it (raw scan).

This build puts its icons and strings there:

| Address | Size | Contents |
|---|---|---|
| `0x40252b50` | 68 B | the robin's colour plane |
| `0x40252b94` | 28 B | the robin's `Bitmap` struct |
| `0x40252bb0` | 44 B | the POLY keyboard icon's colour plane |
| `0x40252bdc` | 28 B | the POLY icon's `Bitmap` struct |
| `0x40252bf8` | 8 B | the machine-picker bitmap selector table |
| `0x40252c00` | 43 B | Chain Recording's three line formats, `YES: ARM %d/%d`, `ARMED %d/%d` and `YES: AUTO %d/%d`, each ending in a NUL byte |

The `Bitmap` structs point at the `Bitmap` vtable (`0x401b7734`, the RTTI vtable `0x401b772c` + 8)
and at stock masks: `0x4024de68` for the robin, a 17 × 17 all-ones mask (`ffff8000` seventeen
times; hexdump), and `0x4023e0a0` for the POLY icon.

In the CFO oscillator's test images only (not in `patch.json`;
[features/cfo_oscillator.md](features/cfo_oscillator.md)):

| Address | Size | Contents |
|---|---|---|
| `0x40252724` | 1,048 B | the four wavetables (1,024 B) and the mix points (24 B); S11 to S14 |
| `0x40252c2c` | 12 B | the machine-picker selector table with CFOO's entry, replacing the one at `0x40252bf8`; S14 |
| `0x40252c38` | 28 B | CFOO's icon `Bitmap` struct, with POLY's mask `0x4023e0a0`; S14 |
| `0x40252c54` | 44 B | CFOO's icon colour plane; S14 |
| `0x40252c80` | 465–533 B | CFOO's name, range and display tables (`.cfo_names`), moved out of the code pad; S20 465 B, S21 529 B, S22 and S23 533 B |

That leaves `0x40252b3c..0x40252b50` (20 B), the byte at `0x40252c2b` and `0x40252c80..0x40253000`
(896 B) free in S14, and from `0x40252e95` on (363 B) in S23. The build's own selector table at
`0x40252bf8` stays in place, unused.

## Related notes

- [landing_pad_method.md](../../../notes/landing_pad_method.md): the method.
- [update_moat.md](update_moat.md): the ranges no pad may touch.
- [memory_map.md](memory_map.md): the RAM this build uses.
- [docs/patch_listing.md](../docs/patch_listing.md): every patched byte run, by feature.
