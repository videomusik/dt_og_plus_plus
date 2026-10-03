# Version comparison: OS 1.52A and OS 1.54

## What this is

A comparison of the stock Digitakt OS 1.52A and OS 1.54 images, made from both files, and the method
by which the OS 1.52A build was carried over to OS 1.54. Each OS folder's notes hold the facts of its
own image (OS 1.52A: [os/1.52A/notes/README.md](../os/1.52A/notes/README.md); OS 1.54:
[os/1.54/notes/README.md](../os/1.54/notes/README.md)); this note holds what is true only of the two
together. Every address names its OS on the same line.

## The two images

| | OS 1.52A | OS 1.54 |
|---|---|---|
| sections | 2, 3, 4, 5 | 2, 3, 4, 5, 8 |
| MAIN OS (section 3) | 2,221,632 B | 2,479,680 B (+258,048 B) |
| MAIN OS load address and entry | `0x40000400`, `0x400004e8` (OS 1.52A) | `0x40000400`, `0x400004e8` (OS 1.54) |
| `.bss` (from the startup code) | `0x40214000..0x439902a0` (OS 1.52A) | `0x40253000..0x439d1000` (OS 1.54) |
| C++ static initialisers | 116 | 130 |
| Ghidra functions (`_emac`) | 11,063 | 11,745 |
| section 4 (updater) | 32,776 B | 32,776 B, 56 % of bytes equal at the same offset |
| section 2 | 26,670 B | 26,846 B |

**Section 2 runs at `0x80000414` in both, OS 1.52A and OS 1.54** (the tests are in
[os/1.54/notes/stock_image.md](../os/1.54/notes/stock_image.md#section-2s-run-base)). Applied to the
OS 1.52A section 2, the same two tests give the same answer: at `0x80000414` (OS 1.52A), 75 of 95
absolute `jsr` targets follow an `rts` and 67 of 83 strings are referenced at their first byte; at
`0x80000ec0` (OS 1.52A), 2 and 0. The OS 1.52A notes give `0x80000ec0` as the run base; the OS 1.52A
header's third word is a function address there, as in OS 1.54.

## Where the new code went

The MAIN OS sections were aligned with every address-like word masked (any 32-bit value in DDR,
`0x40000000..0x48000000`, or in SRAM): 24-byte stretches that occur exactly once in each image serve
as anchors, a longest increasing chain keeps the ones in order, and runs of anchors with one constant
shift are joined and extended byte by byte. 93.2 % of the OS 1.52A section lies in 1,449 stretches
that occur unchanged in OS 1.54.

The shift grows step by step across the whole image, from `+0` at the start to about `+0x3f000` near
its end. OS 1.54 is one relinked program: the new code is spread through it, not appended. The
largest stretches of OS 1.54 with no OS 1.52A counterpart: 71,930 B at `0x401d159c` (OS 1.54),
36,932 B at `0x40202888` (OS 1.54), 31,408 B at `0x40098008` (OS 1.54). 2,089 OS 1.54 functions,
211,526 B, have no OS 1.52A counterpart; among them the Outbox 8 views (`BreakOutBox*`, RTTI).

**Changes inside shared code, seen while porting:**

- One family of parameter-page classes grew by 20 B: member offsets 380, 384, 392 and 128 (OS 1.52A)
  became 400, 404, 412 and 148 (OS 1.54).
- A per-track array of eight records in the audio ISR grew from a 64 B to an 80 B stride (OS 1.52A
  copy-out at `0x40077f14`, OS 1.54 at `0x40078214`). ⚠️ Probably the LFO state, which gained slew.
- An ISR status constant 6 (OS 1.52A, `0x4007724e`) is 5 (OS 1.54, `0x4007754e`).
- `MachineParameterPageView` caches two pointers in OS 1.54: `this@104`, the project object that
  OS 1.52A fetches through `FUN_4012198c` (OS 1.52A), and `this@116`, the object that OS 1.52A gets
  from `FUN_4001488e` (OS 1.52A) applied to it.

## Addresses

Within the aligned stretches, every address-like word of OS 1.52A was paired with the word at the
same place in OS 1.54: 31,550 distinct OS 1.52A values. This table translates addresses that the
stretches do not cover directly (data, `.bss`, strings).

- **SRAM is unchanged.** All 1,514 SRAM values pair with themselves, among them the two the build
  uses, `0x80001228` (6 pairs) and `0x800019ac` (12 pairs) (OS 1.52A and OS 1.54 alike).
- **`.bss` moved by different amounts in different places** (for example `+0x3efd0`, `+0x3afd0`,
  `+0x3fcc8`, `+0x40d60`), because buffers changed size. Each `.bss` address the build uses was taken
  from the pairs, or from two neighbouring pairs with the same shift.
- **Strings** moved by varying amounts. A string the pairs do not cover was found by content, as a
  whole NUL-terminated string that occurs once. The linker shares string tails: `NOTE` at
  `0x401a893c` (OS 1.52A) is the tail of `FOOTNOTE` at `0x401d69a1` (OS 1.54), and `TRK` at
  `0x401b2401` (OS 1.52A) the tail of `LOAD TO TRK` at `0x401d0d44` (OS 1.54).
- **This build's RAM** keeps its offsets above the `.bss` end: `0x439902a0 + k` (OS 1.52A) becomes
  `0x439d1000 + k` (OS 1.54).

## How the build was carried over

Each run of `os/1.52A/build/patch.json` was translated, region by region:

1. Runs are grouped and widened to whole instructions, in both the stock and the patched OS 1.52A
   disassembly.
2. The region's OS 1.54 address comes from the aligned stretch that holds it.
3. Each instruction is translated: absolute 32-bit operands through the address table (only words
   that objdump shows as an operand), PC-relative displacements recomputed from the translated source
   and target.
4. **The self-check.** The same translation, applied to the region's stock OS 1.52A bytes, must give
   the stock OS 1.54 bytes at the new address exactly. A region inside a landing pad is exempt, since
   its stock content is replaced whole; the whole pad must then lie in one aligned stretch and be
   identical with address words masked.

127 regions (2,340 B written, 2,091 B changed from stock OS 1.54, against 2,094 B in OS 1.52A;
the three bytes the same at their translated places are coincidences) passed. Overlapping regions
agree byte for byte.

**Sites in functions that changed** were mapped by hand, from an instruction diff of the host
function in the two images, each with the self-check:

| Site | OS 1.52A | OS 1.54 |
|---|---|---|
| `MachineParameterPageView::vfunc_41` tail `jmp` | `0x40030d44` (OS 1.52A) | `0x400308da` (OS 1.54) |
| `vfunc_22`: classifier call, guard, `pea -1` | `0x40030e5a`, `0x40030e6e`, `0x40030eb8` (OS 1.52A) | `0x400309d0`, `0x400309e4`, `0x40030a2a` (OS 1.54) |
| popup long name | `0x40032816` (OS 1.52A) | `0x40032d36` (OS 1.54) |
| `SamplePageView::vfunc_39` classifier call | `0x40039f16` (OS 1.52A) | `0x4003ab12` (OS 1.54) |
| per-sound deserializer machine bound | `0x4007a994` (OS 1.52A) | `0x4007a2d0` (OS 1.54) |
| grid widget dirty-byte clear (`clr.b %d6` / `%d7`) | `0x400aeafa` (OS 1.52A) | `0x400bd9ee` (OS 1.54) |

Every pad's way back into stock code (ten re-entry points) lands on the same instructions in both
images.

### Caller registers

Code in a pad that reads its caller's registers, rather than its stack arguments, depends on the
caller's register use, which a byte-level self-check at the hook cannot see. Every pad entry was
checked against the OS 1.54 caller:

- the ISR hooks: the ISR code around them is identical in both images;
- the pads that read only stack arguments (the parameter and machine aliases, the machine-assign
  hook): no register dependence;
- the trampolines that re-execute stock bytes: those bytes pass the self-check;
- `popupname` (`%a2`, `%d2`), `shortname` (the frame slot `%sp@(100)`), the private-lane pad (`%a4`,
  `%a2`, `%sp@(48)`) and the pool-cursor grid stub (`%a2`, `%d4`): the OS 1.54 callers use them the
  same way;
- **the pool-cursor marker stub: adapted.** The OS 1.52A marker draw keeps the canvas in `%d3`, the
  OS 1.54 marker draw `FUN_400bd254` (OS 1.54) in `%d2`. The stub's `move.l %d3,%d0` (OS 1.52A
  `0x40015066`) is `move.l %d2,%d0` in OS 1.54 (`0x4001555e`). This is the one change to the build's
  own code beyond address translation.

## The protected set

Each OS 1.52A range was located in OS 1.54 through the function map (functions that start in an
aligned stretch and whose bodies are identical with address words masked), the alignment, or a byte
pattern, and then fixed in OS 1.54 alone
([os/1.54/notes/update_moat.md](../os/1.54/notes/update_moat.md#how-the-ranges-were-fixed)). Each
range's first three and last three instructions are the same in both images (address operands
masked). The OS-update transfer task grew by 48 B (OS 1.52A 5,592 B to its last instruction,
OS 1.54 5,640 B) and the sample-verification helper by 82 B; every other range has the same size.

**The same scans on OS 1.52A** find that the MIDI RPC OS-upgrade route (`OsUpgradeMenuView`,
`OsUpgradeState`, the `MidiRpcOsUpgrade*` classes) and nine other functions that call the flash
driver lie outside the OS 1.52A protected set as well; no run of the OS 1.52A build touches them
either.

## The landing pads

Every OS 1.52A pad has a counterpart in OS 1.54 that lies in one aligned stretch and is identical with
address words masked: the same dead functions at new addresses (OS 1.52A `0x400b23b0` is OS 1.54
`0x400c1338`, OS 1.52A `0x4015cb8a` is OS 1.54 `0x401770a6`, and so on; the full list is the extents
in [os/1.54/notes/landing_pads.md](../os/1.54/notes/landing_pads.md#the-pads)). Their deadness was
then checked again in OS 1.54 with the same scans and controls as in OS 1.52A, which reproduce the
OS 1.52A figures on the OS 1.52A image (every pad 0, the setter control 4 and 5, the voice-build
control 6 and 6).

## Related notes

- [update_moat_method.md](update_moat_method.md), [landing_pad_method.md](landing_pad_method.md): the
  methods applied in each image.
- [analysis_method.md](analysis_method.md): the rule that no single method is trusted alone.
