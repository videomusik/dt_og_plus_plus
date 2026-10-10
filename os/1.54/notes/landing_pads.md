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
✅ So have the CFO oscillator's `FUN_400f77da` (its fill test, S9, then the synth from S11) and
portamento's `FUN_400e6d1c` and `FUN_400ee05e` (their code from S31; their fill test, S28, was not run
on its own).

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
| `FUN_40124a6c` + `FUN_40124ac4` | `0x40124a6c..0x40124b32` | 198 B | Chain Recording: `enc_pad` and `arm_pad`, `0x40124a6c..0x40124b24`; the rest keeps its fill (from v0.2.2 FILTER page 2's `key_txt`, first part, `0x40124b24..0x40124b30`). Vetted below |
| `FUN_40128244` + `FUN_40128288` | `0x40128244..0x401282e0` | 156 B | Chain Recording: `fmt_arm`/`fmt_armed` and `no_pad`, `0x40128244..0x401282ce`; the rest keeps its fill (from v0.2.2 FILTER page 2's `key_txt`, second part: the rest). Vetted below |

The CFO oscillator ([features/cfo_oscillator.md](features/cfo_oscillator.md)), stage by stage; the
build holds S27's:

| Pad | Extent | Size | Occupied by |
|---|---|---:|---|
| `FUN_400f77da` | `0x400f77da..0x400f811e` | 2,372 B | the CFO oscillator: in S11 `0x400f77da..0x400f7aac`; in S12 the code to `0x400f7abe` and the machine name table with its strings `0x400f7ac0..0x400f7b09`; in S13 and S14 the code to `0x400f7b26` and the name tables `0x400f7b28..0x400f7c01`; in S15 the code to `0x400f7b40` and the name tables `0x400f7b40..0x400f7c19`; in S16 the code to `0x400f7bcc` and the name and range tables `0x400f7bcc..0x400f7d41`; in S17 the code to `0x400f7dda` and the name, range and display tables `0x400f7ddc..0x400f7f79`; in S18 the code to `0x400f7f40` and the tables `0x400f7f40..0x400f8111`, 13 B short of the pad's end; in S19 the code to `0x400f7eb6` and the tables `0x400f7eb8..0x400f8089`, 149 B short; from S20 on code only, the tables in the .rodata padding at `0x40252c80`: S20 to `0x400f7ede`, S21 to `0x400f7f52`, S22 to `0x400f7f70`, S23 to `0x400f8060`, S24 to `0x400f80a4`, S25 to `0x400f80c6`, S26 to `0x400f811c`, 2 B short, S27 to `0x400f8118`, 6 B short; the rest keeps its fill. LZ4's streaming compressor, admitted by the recipe's library exception (the candidate list below); its fill test is the stage image S9 |

Portamento ([features/portamento.md](features/portamento.md)), stage by stage on the CFO oscillator's
S27; the build holds S36's:

| Pad | Extent | Size | Occupied by |
|---|---|---:|---|
| `FUN_400e6d1c` | `0x400e6d1c..0x400e6d88` | 108 B | S28 its fill; S29 and S30 `port_on`'s inert replay, 8 B; S31 `port_on`, `0x400e6d1c..0x400e6d40`; S32 and `port_text` to `0x400e6d52`; S33 and `rd_hook` to `0x400e6d6e`; S34 and `amp_hook`: the whole pad; until then the rest keeps its fill. Vetted below |
| `FUN_400ee05e` | `0x400ee05e..0x400ee0d8` | 122 B | S28 its fill; S29 and S30 `port_glide`'s inert replay, 12 B; S31 to S34 `port_glide`, `0x400ee05e..0x400ee0ce`; from S35 to `0x400ee0d2`; the rest keeps its fill. Vetted below |

FILTER page 2's pads (in the build from v0.2.2; by stage in
[features/filter_page2.md](features/filter_page2.md)):

| Pad | Extent | Size | Occupied by |
|---|---|---:|---|
| `FUN_400d266e` | `0x400d266e..0x400d26aa` | 60 B | S37 its fill; S38 to S40 `filt_hook`'s inert jump, 6 B; S41 to S46 the hook's VED part, `0x400d266e..0x400d269c`; S47 52 B, S48 and S49 54 B; from S50 the whole pad, with the KEY part's first two instructions; until then the rest keeps its fill. Vetted below |
| `FUN_40178f20` | `0x40178f20..0x40178f76` | 86 B | S37 its fill; S41 to S43 the hook's KEY part, `0x40178f20..0x40178f5e`; from S44 60 B, to `0x40178f5c` (from S50 58 B and a `nop`); from S45 `filt_spc`, the start-up display build's hook, at `0x40178f5c`: S45 8 B, from S46 the whole pad. Vetted below |
| `FUN_40178e02` | `0x40178e02..0x40178e44` | 66 B | S37 its fill; S42 `rd_hook2`, PORT and LEG only, `0x40178e02..0x40178e1e`; from S43 `rd_hook2` with VED and KEY: the whole pad. Vetted below |
| `FUN_401778a4` | `0x401778a4..0x401778d8` | 52 B | S37 its fill; S42 `fwd_ext` and `inv_ext`, 20 B; from S43 44 B, to `0x401778d0`; the rest keeps its fill. Vetted below |

CFOO's wave pictures pad, in their test images only (on top of the build, not in `patch.json`;
[features/wave_pictures.md](features/wave_pictures.md)):

| Pad | Extent | Size | Occupied by |
|---|---|---:|---|
| `FUN_401044b6` | `0x401044b6..0x40104e7e` | 2,504 B | S53 its fill; S54 `wav_sel`, 16 B; S55 `wav_sel` and `wav_pic`, 266 B, to `0x401045c0`; the rest keeps its fill. Vetted below |

Free code space: 102 B in fourteen blocks, none larger than 16 B: 16 B at `0x400152c0`, 16 B at
`0x400bedf2`, 10 B at `0x400156da`, 8 B at `0x400bf1e0`, 8 B at `0x40037ad6`, 8 B at `0x401778d0`
(FILTER page 2's `FUN_401778a4`, fill), 6 B at `0x400c1392`, 6 B at `0x400bed96`, 6 B at
`0x400bee64`, 6 B at `0x400f8118` (the CFO oscillator's pad, fill), 6 B at `0x400ee0d2` (portamento's
`FUN_400ee05e`, fill), 2 B at `0x40124b30` (the soft-float pad's last `rts`), 2 B at `0x40177192` (the
STL span's tail, fill) and 2 B at `0x400c107e`. Chain Recording's code in the pads:
[features/chain_record.md](features/chain_record.md#where-the-code-lives). FILTER page 2's KEY text
took 12 B at `0x40124b24` and the 18 B at `0x401282ce` that v0.2.1 left free.

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

## The portamento pads: `FUN_400e6d1c` and `FUN_400ee05e`

Two leaf functions with no calls, no peripheral addresses and no MAC instructions (objdump):

- `FUN_400e6d1c` (108 B) packs its stack arguments into a 28 B record at `%a0`: a byte from its
  argument less 32, the constant 2, bytes, words and longs, and a word whose top bit is set from a
  flag; it returns `%a0`.
- `FUN_400ee05e` (122 B), while the counter `0x421f53d0` is positive, counts it down and swaps 32
  pairs of longs between two buffers (its arguments) and a ring of 90 entries at `0x421f5090`, whose
  index it keeps at `0x421f5360`. ⚠️ An unused branch of its module, by inference: no other code
  names the ring or its index; `FUN_400edaf4` (called from the audio ISR at `0x400775dc`) sets the
  counter to 128 or 0, and `FUN_400edf30` (called from the ISR at `0x400781ac` and `0x40078318`)
  returns at once while it is positive, so this routine would be the one that runs then, and nothing
  calls it.

✅ The vetting steps of [landing_pad_method.md](../../../notes/landing_pad_method.md#vetting-a-new-pad),
read in this image, with the controls of steps 3 and 4 giving their known counts in the same run
([Vetting controls on this image](#vetting-controls-on-this-image)):

1. **Candidate.** Both are leaves with no indirect call among the candidates below (`ghRefs=0`,
   `ptrWord=-`, `opLit=-`).
2. **objdump.** Extents `0x400e6d1c..0x400e6d88` and `0x400ee05e..0x400ee0d8`, from their `rts`.
3. **Raw pointer scan**, every byte offset: `FUN_400ee05e` 0; `FUN_400e6d1c` 1, the odd word at
   `0x40084d7f`, which straddles `moveb %d0,%a2@(3693)` (displacement `0x0e6d`) and the next opcode:
   not a pointer.
4. **Listing scan**, hex operands and decimal immediates: 0 for both.
5. **Switch tables.** Within ±32 KB there are 2 indexed `jmp`/`movew` around `FUN_400ee05e` and 3
   around `FUN_400e6d1c`; walking 512 table words from each base finds no entry landing in either.
6. **Live twins.** `FUN_400ee05e`'s ring and index appear nowhere else; its counter is the module's
   (above). `FUN_400e6d1c`'s record offsets and constants appear elsewhere only in different records.
7. **Fall-through.** Each is preceded by an `rts` (`0x400e6d1a`, `0x400ee05c`) and followed by a fresh
   function (`0x400e6d88`, `0x400ee0d8`).
8. **Moat.** Outside every protected range ([update_moat.md](update_moat.md)); the nearest, the flash
   driver module `0x400e8c68..0x400ea596`, lies between them.

✅ S31, with `port_on` and `port_glide` in them, ran on the test unit with OS 1.54, and its glide works
([features/portamento.md](features/portamento.md#on-the-unit)). Their fill test, the stage image S28,
is not reported on its own.

## The FILTER page 2 pads

Four leaf functions with no calls, no peripheral addresses and no MAC instructions (objdump):

- `FUN_400d266e` (60 B) counts the set bits of the 8 KB bitmap `0x426886b0..0x4268a6b0`. The bitmap's
  other users (`0x400d2214` … `0x400d2cdc`, and the end address as an immediate at `0x400cc958` …
  `0x400d2b06`) are its module's live functions, whose loops search it; none counts its bits.
- `FUN_40178f20` (86 B) and `FUN_40178e02` (66 B) search a `std::string` (its length at `-12`) for the
  last, and the first, character that differs from a given one: two of the library's search overloads.
  Their only "twins" are the representation's offset `-12`, which every string routine uses.
- `FUN_401778a4` (52 B) counts the nodes of a linked list, up to an end node, whose first word is 1: a
  container helper of the library code whose neighbours hold the STL span in use above.

✅ The vetting steps of [landing_pad_method.md](../../../notes/landing_pad_method.md#vetting-a-new-pad),
read in this image, with the controls of steps 3 and 4 giving their known counts in the same runs
([Vetting controls on this image](#vetting-controls-on-this-image)):

1. **Candidate.** Leaves, no indirect call (`ghRefs=0`, `ptrWord=-`, `opLit=-`).
2. **objdump.** Extents `0x400d266e..0x400d26aa`, `0x40178f20..0x40178f76`, `0x40178e02..0x40178e44` and
   `0x401778a4..0x401778d8`, from their `rts`.
3. **Raw pointer scan**, every byte offset: 0 for each.
4. **Listing scan**, hex operands and decimal immediates: 0 for each.
5. **Switch tables.** Within ±32 KB there are 6 indexed `jmp`/`movew` around `FUN_400d266e`, 1 around each
   of the others; walking 512 table words from each base finds no entry landing in any of them.
6. **Live twins.** As above.
7. **Fall-through.** Each is preceded by an `rts` or a `bra` (`0x400d266c`, `0x40178f1e`, `0x40178e00`,
   `0x401778a0`) and followed by a fresh function (`0x400d26aa`, `0x40178f76`, `0x40178e44`, `0x401778d8`).
8. **Moat.** Outside every protected range ([update_moat.md](update_moat.md)).

✅ Their fill test (S37) ran on the test unit with OS 1.54, as reported, and so did FILTER page 2's code
in them (S38 to S52).

Examined at the same time and not taken: `FUN_400c2242` (54 B), a leaf that inverts a rectangle of a
bitmap, with raw and listing scans 0, but five words of two switch tables within ±32 KB land in it
(their bounds not read), and it is drawing code, which a computed address could reach unseen (as
`0x400c2d00` below); and the two spans the list below marks "not examined", `0x40013a9e` and
`0x400214e8`, which call through a vtable (`jsr %a0@`, `jmp %a1@`), so step 1 does not admit them.

## The wave pictures pad: `FUN_401044b6`

A function of xxHash's 64-bit family (XXH64), 2,504 B. It is not a leaf, so step 1 admits it only by its
library exception ([landing_pad_method.md](../../../notes/landing_pad_method.md#vetting-a-new-pad)):

- **The library.** xxHash, identified by its constants: the 32-bit primes `0x9e3779b1`, `0x85ebca77`,
  `0xc2b2ae3d`, `0x27d4eb2f`, `0x165667b1`, as halves of the 64-bit ones, and XXH64's start values
  (seed + P1 + P2 = `0x60ea27ee_adc0b5d6`, seed − P1 = `0x61c8864e_7a143579`) in its siblings
  `FUN_401020e4` and `FUN_40103ba8`. LZ4's frame format checksums with the 32-bit XXH32, whose functions
  here are live (`FUN_401012d6`, `FUN_40101a28`, `FUN_40101e98`); the whole 64-bit family,
  `0x40102088..0x40104ee8` (eight functions, 11,872 B), is on the candidate list.
- **Its callees.** `FUN_40101280` (xxHash's 4-byte read through `memcpy` `0x400e8ae8`, which XXH32 shares),
  `0x40123fd4` (a 64-bit multiply) and `0x401241a4`/`0x401241dc` (32- and 64-bit byte swaps): the library's
  own and the C runtime's. Its calls through registers (`jsr %a2@`, `%a4@`, `%a5@`) go to those, whose
  addresses the function itself loads.

✅ The vetting steps, read in this image, with the controls of steps 3 and 4 giving their known counts in
the same run ([Vetting controls on this image](#vetting-controls-on-this-image)):

1. **Candidate.** `ghRefs=0`, `ptrWord=-`, `opLit=-`, 5 callees: the library exception, above.
2. **objdump.** No peripheral literal, no MAC instruction; extent `0x401044b6..0x40104e7e`, from its one
   `rts`.
3. **Raw pointer scan**, every byte offset: 7 words, each a byte sequence across two instructions, not an
   operand or a table word: at `0x400665d4`, `0x400665dc` and `0x40068f70` the peripheral address
   `0xec094010` of a `bset`/`bclr` and the next opcode; at `0x400f5ce2`, `0x400f77ce` and `0x400f8112` a
   displacement of 16,400 (`0x4010`) and the next opcode; at `0x40110c3e` the operand of
   `pea 0x40224010` and the next opcode.
4. **Listing scan**, hex operands and decimal immediates: 0.
5. **Switch tables.** None within ±32 KB.
6. **Live twins.** Its 64-bit constants (`0x27d4eb4f`, the start values) appear only inside the family;
   the 32-bit primes also in the live XXH32 functions and in LZ4, which share them.
7. **Fall-through.** An `rts` before it (`0x401044b4`) and a fresh function after it (`linkw` at
   `0x40104e7e`).
8. **Moat.** Outside every protected range ([update_moat.md](update_moat.md)).

✅ Its fill test (S53) ran on the test unit with OS 1.54, as reported, and so did the wave pictures' code
in it (S54, S55).

Examined at the same time and not taken: `FUN_401020e4` (6,798 B), in the same family, whose raw scan
finds 44 words (not each explained), whose listing scan finds one operand (`0x4023da70`, in `.rodata`
read as code), and into which entries 16 to 419 of the switch table at `0x400fae42` would land (its
bound not read). It stays a candidate.

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
| `0x40013a9e..0x40013b24` | 134 B | calls through a vtable (`jsr %a0@`), so not a leaf in fact |
| `0x400214e8..0x4002156c` | 132 B | calls and jumps through a vtable (`jsr %a0@`, `jmp %a1@`), so not a leaf in fact |
| `0x400f77da..0x400f811e` | 2,372 B | `FUN_400f77da`, LZ4's streaming compressor. Not a leaf (two LZ4 helpers and `memcpy`), so step 1 does not admit it; steps 2–8 pass: raw words 2 (one straddles a FlexBus access at `0x400e2170`, one is the odd value `0x400f7fe1` in a word table at `0x40198c6e`), listing 0, the only switch table within ±32 KB (`0x400fae42`, 15 entries) does not reach it, `rts` before and a fresh prologue after. The CFO oscillator's candidate ([features/cfo_oscillator.md](features/cfo_oscillator.md#code-space)) |
| `0x40071a16..0x40071b30` | 282 B | in the audio code |
| `0x4007b2e8..0x4007b56a` | 642 B | in the audio code |
| `0x4024f5be..0x4024f686`, `0x4024f7f0..0x4024f86c` | 200 B, 124 B | in the second code window; the first reads the peripheral registers `0xffff8010..0xffff8013` and data at `0x800xxx`, in SRAM. Not taken as a pad: I/O code |

Most of the smaller leaf candidates call through a vtable (`jsr %a0@`) and are not leaves in fact. Those
with no indirect call, outside the I/O region, the audio code and the second code window, are
`0x400ee05e` (122 B) and `0x400e6d1c` (108 B), vetted for portamento
([above](#the-portamento-pads-fun_400e6d1c-and-fun_400ee05e)); `0x40178f20` (86 B), `0x40178e02` (66 B),
`0x400d266e` (60 B) and `0x401778a4` (52 B), vetted for FILTER page 2
([above](#the-filter-page-2-pads)); `0x400c2242` (54 B), not taken (same section); and `0x400e8544`
(86 B), ⛔ live: `pea %pc@(0x400e8544)` at `0x400e85b4` registers it with `0x40002d58` (a timer
callback), which only the listing scan finds. Not leaves, admitted only by step 1's library exception:
LZ4's `FUN_400f77da` (the CFO oscillator's pad) and xxHash's 64-bit family `0x40102088..0x40104ee8`, of
which `FUN_401044b6` is vetted ([above](#the-wave-pictures-pad-fun_401044b6)).

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

The CFO oscillator, stage by stage (in the build: S27's;
[features/cfo_oscillator.md](features/cfo_oscillator.md)):

| Address | Size | Contents |
|---|---|---|
| `0x40252724` | 1,048 B | the four waveform tables (1,024 B) and the mix points (24 B); S11 to S14 |
| `0x40252c2c` | 12 B | the machine-picker selector table with CFOO's entry, replacing the one at `0x40252bf8`; S14 |
| `0x40252c38` | 28 B | CFOO's icon `Bitmap` struct, with POLY's mask `0x4023e0a0`; S14 |
| `0x40252c54` | 44 B | CFOO's icon colour plane; S14 |
| `0x40252c80` | 465–601 B | CFOO's name, range and display tables (`.cfo_names`), moved out of the code pad; S20 465 B, S21 529 B, S22 and S23 533 B, S24 and S25 597 B, S26 and S27 601 B |

That leaves `0x40252b3c..0x40252b50` (20 B), the byte at `0x40252c2b` and `0x40252c80..0x40253000`
(896 B) free in S14, and from `0x40252ed9` on (295 B) in S26. The build's own selector table at
`0x40252bf8` stays in place, unused.

Portamento, stage by stage on top of S27 (in the build: S36's;
[features/portamento.md](features/portamento.md)):
from S30 `0x40252f00`, 27 B, the names `PORT`, `Portamento`, `LEG` and `Legato`; from S32
`0x40252f1c`, 16 B, PORT's text object; from S33 `0x40252f2c`, 192 B, the 48-entry slot → stored index
table. That leaves `0x40252ed9..0x40252f00` (39 B) and, from S33, `0x40252fec..0x40253000` (20 B).

FILTER page 2 (in the build from v0.2.2; [features/filter_page2.md](features/filter_page2.md)):
from S39 `0x40252ed9`, 37 B, the names `VED`, `Vel to Env Depth`, `KEY` and `Keytracking`; from S43
`0x40252fec`, 8 B, the slot → stored index table's entries 48 and 49; from S51 `0x40252ff4`, 8 B, the
manager and invoker of KEY's text object (`0x40252fec`, its storage the two entries before them). That
leaves `0x40252efe..0x40252f00` (2 B) and `0x40252ffc..0x40253000` (4 B).

## Related notes

- [landing_pad_method.md](../../../notes/landing_pad_method.md): the method.
- [update_moat.md](update_moat.md): the ranges no pad may touch.
- [memory_map.md](memory_map.md): the RAM this build uses.
- [docs/patch_listing.md](../docs/patch_listing.md): every patched byte run, by feature.
