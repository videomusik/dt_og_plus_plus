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

## Moving between the two versions

- **The version checks.** Both versions' bootstraps (section 2) and both MAIN OS sections compare a
  received image's build/model field with the same four-character thresholds: `"002/"`, `"0057"`,
  `"0067"`, `"0071"`, `"0090"`, `"009/"` in section 2, and the same set plus `"0021"` in MAIN OS
  (objdump of both images). OS 1.54 raised none of them. The two images' build/model fields, `0097`
  (OS 1.52A) and `0107` (OS 1.54), are above all of them. ⚠️ So the version check most likely treats
  an OS 1.52A image the same whichever of the two versions receives it. Not tried on a unit.
- **Stored kits.** The kit gate accepts version 9 in OS 1.52A and version 10 in OS 1.54
  (OS 1.54: `0x4007a4ce`; the OS 1.52A gate is described in its compatibility note). On a mismatch
  the OS 1.52A loader initialises a cleared kit. ⚠️ So a project saved under OS 1.54 most likely loses
  its kits when it is loaded under OS 1.52A.
- **The bootstrap.** Elektron's release notes say the bootstrap is upgraded after some OS upgrades, on
  the first restart. Section 2 differs between the two images. Whether flashing the OS 1.52A image
  onto a unit whose bootstrap came from OS 1.54 rewrites the bootstrap, and with which one, has not
  been traced.

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

## Appendix A: the translated regions

Every region of the OS 1.52A patch and where it went in OS 1.54, generated from the translation's own
record. A region is a run of `os/1.52A/build/patch.json`, widened to whole instructions; regions of one
feature that touch are one row. "Aligned stretch" regions passed the stock self-check; pad regions
replace dead code whole; hand-mapped regions are those of the table above.

| OS 1.52A | OS 1.54 | Feature | Kind | Size | Route |
|---|---|---|---|---:|---|
| `0x40014cb4` (OS 1.52A) | `0x400151ac` (OS 1.54) | `midi_loopback` | code | 54 B | aligned stretch, shift +0x4f8 |
| `0x40014cee` (OS 1.52A) | `0x400151e6` (OS 1.54) | `midi_loopback` | code | 142 B | aligned stretch, shift +0x4f8 |
| `0x40014d7c` (OS 1.52A) | `0x40015274` (OS 1.54) | `midi_loopback` | code | 76 B | aligned stretch, shift +0x4f8 |
| `0x40014dc8` (OS 1.52A) | `0x400152c0` (OS 1.54) | `pad_fill` | code | 14 B | aligned stretch, shift +0x4f8 |
| `0x40015060` (OS 1.52A) | `0x40015558` (OS 1.54) | `pool_cursors` | code | 34 B | aligned stretch, shift +0x4f8 |
| `0x40015082` (OS 1.52A) | `0x4001557a` (OS 1.54) | `pool_cursors` | code | 160 B | aligned stretch, shift +0x4f8 |
| `0x4001511c` (OS 1.52A) | `0x40015614` (OS 1.54) | `midi_loopback` | code | 202 B | aligned stretch, shift +0x4f8 |
| `0x400151e0` (OS 1.52A) | `0x400156d8` (OS 1.54) | `pad_fill` | code | 10 B | aligned stretch, shift +0x4f8 |
| `0x40021ff4` (OS 1.52A) | `0x400225f0` (OS 1.54) | `poly_engine` | code | 2 B | aligned stretch, shift +0x5fc |
| `0x40022970` (OS 1.52A) | `0x40022f7e` (OS 1.54) | `poly_engine` | code | 4 B | aligned stretch, shift +0x60e |
| `0x400229a0` (OS 1.52A) | `0x40022fae` (OS 1.54) | `poly_engine` | code | 6 B | aligned stretch, shift +0x60e |
| `0x400229d8` (OS 1.52A) | `0x40022fe6` (OS 1.54) | `poly_engine` | code | 2 B | aligned stretch, shift +0x60e |
| `0x40029804` (OS 1.52A) | `0x40029e80` (OS 1.54) | `poly_icon` | code | 2 B | aligned stretch, shift +0x67c |
| `0x4002980c` (OS 1.52A) | `0x40029e88` (OS 1.54) | `poly_icon` | code | 16 B | aligned stretch, shift +0x67c |
| `0x4002987a` (OS 1.52A) | `0x40029ef6` (OS 1.54) | `poly_icon` | code | 8 B | aligned stretch, shift +0x67c |
| `0x4002988a` (OS 1.52A) | `0x40029f06` (OS 1.54) | `poly_icon` | code | 6 B | aligned stretch, shift +0x67c |
| `0x40029f8c` (OS 1.52A) | `0x4002a608` (OS 1.54) | `poly_engine` | code | 6 B | aligned stretch, shift +0x67c |
| `0x4002a0f2` (OS 1.52A) | `0x4002a76e` (OS 1.54) | `poly_engine` | code | 4 B | aligned stretch, shift +0x67c |
| `0x4002a670` (OS 1.52A) | `0x4002acec` (OS 1.54) | `poly_engine` | code | 4 B | aligned stretch, shift +0x67c |
| `0x4002a674` (OS 1.52A) | `0x4002acf0` (OS 1.54) | `poly_engine` | code | 6 B | aligned stretch, shift +0x67c |
| `0x4002a70c` (OS 1.52A) | `0x4002ad88` (OS 1.54) | `poly_engine` | code | 4 B | aligned stretch, shift +0x67c |
| `0x4002a710` (OS 1.52A) | `0x4002ad8c` (OS 1.54) | `poly_engine` | code | 6 B | aligned stretch, shift +0x67c |
| `0x4002b012` (OS 1.52A) | `0x4002b68e` (OS 1.54) | `poly_ui` | code | 6 B | aligned stretch, shift +0x67c |
| `0x40030466` (OS 1.52A) | `0x40030daa` (OS 1.54) | `midi_loopback` | code | 6 B | aligned stretch, shift +0x944 |
| `0x40030d44` (OS 1.52A) | `0x400308da` (OS 1.54) | `poly_ui` | code | 6 B | by hand (function changed) |
| `0x40030e5a` (OS 1.52A) | `0x400309d0` (OS 1.54) | `poly_ui` | code | 6 B | by hand (function changed) |
| `0x40030e6e` (OS 1.52A) | `0x400309e4` (OS 1.54) | `poly_ui` | code | 2 B | by hand (function changed) |
| `0x40030eb8` (OS 1.52A) | `0x40030a2a` (OS 1.54) | `poly_ui` | code | 4 B | by hand (function changed) |
| `0x40032816` (OS 1.52A) | `0x40032d36` (OS 1.54) | `midi_loopback` | code | 6 B | by hand (function changed) |
| `0x4003771c` (OS 1.52A) | `0x40037a24` (OS 1.54) | `poly_engine` | code | 116 B | aligned stretch, shift +0x308 |
| `0x40037790` (OS 1.52A) | `0x40037a98` (OS 1.54) | `owner_latch` | code | 14 B | aligned stretch, shift +0x308 |
| `0x40037790` (OS 1.52A) | `0x40037a98` (OS 1.54) | `owner_latch` | code | 14 B | aligned stretch, shift +0x308 |
| `0x40037790` (OS 1.52A) | `0x40037a98` (OS 1.54) | `voice_allocation` | code | 14 B | aligned stretch, shift +0x308 |
| `0x40037790` (OS 1.52A) | `0x40037a98` (OS 1.54) | `voice_allocation` | code | 14 B | aligned stretch, shift +0x308 |
| `0x4003779e` (OS 1.52A) | `0x40037aa6` (OS 1.54) | `voice_allocation` | code | 22 B | aligned stretch, shift +0x308 |
| `0x400377ae` (OS 1.52A) | `0x40037ab6` (OS 1.54) | `poly_engine` | code | 34 B | aligned stretch, shift +0x308 |
| `0x40039f16` (OS 1.52A) | `0x4003ab12` (OS 1.54) | `poly_ui` | code | 6 B | by hand (function changed) |
| `0x40062fde` (OS 1.52A) | `0x40063222` (OS 1.54) | `midi_loopback` | code | 6 B | aligned stretch, shift +0x244 |
| `0x40065264` (OS 1.52A) | `0x400654a8` (OS 1.54) | `slice_round_robin` | code | 6 B | aligned stretch, shift +0x244 |
| `0x400652d6` (OS 1.52A) | `0x4006551a` (OS 1.54) | `slice_round_robin` | code | 6 B | aligned stretch, shift +0x244 |
| `0x40065652` (OS 1.52A) | `0x40065896` (OS 1.54) | `midi_loopback` | code | 6 B | aligned stretch, shift +0x244 |
| `0x40074b38` (OS 1.52A) | `0x40074e38` (OS 1.54) | `slice_round_robin` | code | 10 B | aligned stretch, shift +0x300 |
| `0x40076ee8` (OS 1.52A) | `0x400771e8` (OS 1.54) | `poly_engine` | code | 8 B | aligned stretch, shift +0x300 |
| `0x4007705a` (OS 1.52A) | `0x4007735a` (OS 1.54) | `poly_engine` | code | 8 B | aligned stretch, shift +0x300 |
| `0x400773e6` (OS 1.52A) | `0x400776e6` (OS 1.54) | `poly_engine` | code | 6 B | aligned stretch, shift +0x300 |
| `0x400774a2` (OS 1.52A) | `0x400777a2` (OS 1.54) | `poly_engine` | code | 6 B | aligned stretch, shift +0x300 |
| `0x400774ca` (OS 1.52A) | `0x400777ca` (OS 1.54) | `mute_by_origin` | code | 6 B | aligned stretch, shift +0x300 |
| `0x400774d6` (OS 1.52A) | `0x400777d6` (OS 1.54) | `mute_by_origin` | code | 8 B | aligned stretch, shift +0x300 |
| `0x40077a72` (OS 1.52A) | `0x40077d72` (OS 1.54) | `tick_wipe_fix` | code | 24 B | aligned stretch, shift +0x300 |
| `0x40078df4` (OS 1.52A) | `0x4007910c` (OS 1.54) | `poly_engine` | code | 2 B | aligned stretch, shift +0x318 |
| `0x40078e00` (OS 1.52A) | `0x40079118` (OS 1.54) | `poly_engine` | code | 6 B | aligned stretch, shift +0x318 |
| `0x40078e14` (OS 1.52A) | `0x4007912c` (OS 1.54) | `poly_engine` | code | 2 B | aligned stretch, shift +0x318 |
| `0x40078e20` (OS 1.52A) | `0x40079138` (OS 1.54) | `poly_engine` | code | 6 B | aligned stretch, shift +0x318 |
| `0x4007a994` (OS 1.52A) | `0x4007a2d0` (OS 1.54) | `poly_engine` | code | 2 B | by hand (function changed) |
| `0x400ae7f4` (OS 1.52A) | `0x400bd5b0` (OS 1.54) | `pool_cursors` | code | 6 B | aligned stretch, shift +0xedbc |
| `0x400aeafa` (OS 1.52A) | `0x400bd9ee` (OS 1.54) | `pool_cursors` | code | 6 B | by hand (function changed); stock bytes differ by design |
| `0x400afe46` (OS 1.52A) | `0x400bed3a` (OS 1.54) | `poly_ui` | code | 90 B | aligned stretch, shift +0xeef4 |
| `0x400afea0` (OS 1.52A) | `0x400bed94` (OS 1.54) | `pad_fill` | code | 6 B | aligned stretch, shift +0xeef4 |
| `0x400afea8` (OS 1.52A) | `0x400bed9c` (OS 1.54) | `poly_engine` | code | 86 B | aligned stretch, shift +0xeef4 |
| `0x400aff0e` (OS 1.52A) | `0x400bee02` (OS 1.54) | `poly_ui` | code | 32 B | aligned stretch, shift +0xeef4 |
| `0x400aff2e` (OS 1.52A) | `0x400bee22` (OS 1.54) | `pad_fill` | code | 2 B | aligned stretch, shift +0xeef4 |
| `0x400aff30` (OS 1.52A) | `0x400bee24` (OS 1.54) | `pad_fill` | code | 4 B | aligned stretch, shift +0xeef4 |
| `0x400aff30` (OS 1.52A) | `0x400bee24` (OS 1.54) | `poly_ui` | code | 4 B | aligned stretch, shift +0xeef4 |
| `0x400aff34` (OS 1.52A) | `0x400bee28` (OS 1.54) | `slice_round_robin` | code | 34 B | aligned stretch, shift +0xeef4 |
| `0x400aff4a` (OS 1.52A) | `0x400bee3e` (OS 1.54) | `poly_icon` | code | 22 B | aligned stretch, shift +0xeef4 |
| `0x400aff60` (OS 1.52A) | `0x400bee54` (OS 1.54) | `midi_loopback` | code | 16 B | aligned stretch, shift +0xeef4 |
| `0x400aff70` (OS 1.52A) | `0x400bee64` (OS 1.54) | `pad_fill` | code | 4 B | aligned stretch, shift +0xeef4 |
| `0x400b026e` (OS 1.52A) | `0x400bf16c` (OS 1.54) | `poly_engine` | code | 98 B | aligned stretch, shift +0xeefe |
| `0x400b20ac` (OS 1.52A) | `0x400c1034` (OS 1.54) | `poly_engine` | data | 45 B | aligned stretch, shift +0xef88 |
| `0x400b21b0` (OS 1.52A) | `0x400c1138` (OS 1.54) | `poly_engine` | code | 114 B | aligned stretch, shift +0xef88 |
| `0x400b221c` (OS 1.52A) | `0x400c11a4` (OS 1.54) | `owner_latch` | code | 6 B | aligned stretch, shift +0xef88 |
| `0x400b2222` (OS 1.52A) | `0x400c11aa` (OS 1.54) | `poly_engine` | code | 12 B | aligned stretch, shift +0xef88 |
| `0x400b2224` (OS 1.52A) | `0x400c11ac` (OS 1.54) | `owner_latch` | code | 10 B | aligned stretch, shift +0xef88 |
| `0x400b222e` (OS 1.52A) | `0x400c11b6` (OS 1.54) | `poly_engine` | code | 36 B | aligned stretch, shift +0xef88 |
| `0x400b23b0` (OS 1.52A) | `0x400c1338` (OS 1.54) | `slice_round_robin` | code | 34 B | aligned stretch, shift +0xef88 |
| `0x400b23d2` (OS 1.52A) | `0x400c135a` (OS 1.54) | `poly_engine` | code | 10 B | aligned stretch, shift +0xef88 |
| `0x400b23dc` (OS 1.52A) | `0x400c1364` (OS 1.54) | `slice_round_robin` | code | 14 B | aligned stretch, shift +0xef88 |
| `0x400b23ea` (OS 1.52A) | `0x400c1372` (OS 1.54) | `poly_engine` | code | 4 B | aligned stretch, shift +0xef88 |
| `0x400b23ea` (OS 1.52A) | `0x400c1372` (OS 1.54) | `slice_round_robin` | code | 24 B | aligned stretch, shift +0xef88 |
| `0x400b23fe` (OS 1.52A) | `0x400c1386` (OS 1.54) | `poly_engine` | code | 4 B | aligned stretch, shift +0xef88 |
| `0x400b23fe` (OS 1.52A) | `0x400c1386` (OS 1.54) | `slice_round_robin` | code | 14 B | aligned stretch, shift +0xef88 |
| `0x400b240e` (OS 1.52A) | `0x400c1396` (OS 1.54) | `slice_round_robin` | code | 80 B | aligned stretch, shift +0xef88 |
| `0x400b245e` (OS 1.52A) | `0x400c13e6` (OS 1.54) | `slice_round_robin` | data | 4 B | aligned stretch, shift +0xef88 |
| `0x400b2463` (OS 1.52A) | `0x400c13eb` (OS 1.54) | `slice_round_robin` | data | 5 B | aligned stretch, shift +0xef88 |
| `0x400c47ce` (OS 1.52A) | `0x400d49e0` (OS 1.54) | `midi_loopback` | code | 6 B | aligned stretch, shift +0x10212 |
| `0x400c4bda` (OS 1.52A) | `0x400d4dec` (OS 1.54) | `midi_loopback` | code | 8 B | aligned stretch, shift +0x10212 |
| `0x400cfd94` (OS 1.52A) | `0x400e0a00` (OS 1.54) | `midi_loopback` | code | 6 B | aligned stretch, shift +0x10c6c |
| `0x400d0396` (OS 1.52A) | `0x400e1002` (OS 1.54) | `midi_loopback` | code | 6 B | aligned stretch, shift +0x10c6c |
| `0x4013b914` (OS 1.52A) | `0x401524f2` (OS 1.54) | `slice_round_robin` | code | 6 B | aligned stretch, shift +0x16bde |
| `0x4015cb8a` (OS 1.52A) | `0x401770a6` (OS 1.54) | `voice_allocation` | code | 70 B | aligned stretch, shift +0x1a51c |
| `0x4015cbd0` (OS 1.52A) | `0x401770ec` (OS 1.54) | `mute_by_origin` | code | 24 B | aligned stretch, shift +0x1a51c |
| `0x4015cbe8` (OS 1.52A) | `0x40177104` (OS 1.54) | `pad_fill` | code | 142 B | aligned stretch, shift +0x1a51c |
| `0x40191b30` (OS 1.52A) | `0x401abc44` (OS 1.54) | `slice_round_robin` | data | 3 B | aligned stretch, shift +0x1a114 |
| `0x40191c00` (OS 1.52A) | `0x401abd14` (OS 1.54) | `midi_loopback` | data | 3 B | aligned stretch, shift +0x1a114 |
| `0x40213b55` (OS 1.52A) | `0x40252b55` (OS 1.54) | `slice_round_robin` | data | 1 B | aligned stretch, shift +0x3f000 |
| `0x40213b58` (OS 1.52A) | `0x40252b58` (OS 1.54) | `slice_round_robin` | data | 2 B | aligned stretch, shift +0x3f000 |
| `0x40213b5c` (OS 1.52A) | `0x40252b5c` (OS 1.54) | `slice_round_robin` | data | 2 B | aligned stretch, shift +0x3f000 |
| `0x40213b60` (OS 1.52A) | `0x40252b60` (OS 1.54) | `slice_round_robin` | data | 2 B | aligned stretch, shift +0x3f000 |
| `0x40213b64` (OS 1.52A) | `0x40252b64` (OS 1.54) | `slice_round_robin` | data | 2 B | aligned stretch, shift +0x3f000 |
| `0x40213b68` (OS 1.52A) | `0x40252b68` (OS 1.54) | `slice_round_robin` | data | 2 B | aligned stretch, shift +0x3f000 |
| `0x40213b6c` (OS 1.52A) | `0x40252b6c` (OS 1.54) | `slice_round_robin` | data | 2 B | aligned stretch, shift +0x3f000 |
| `0x40213b70` (OS 1.52A) | `0x40252b70` (OS 1.54) | `slice_round_robin` | data | 2 B | aligned stretch, shift +0x3f000 |
| `0x40213b74` (OS 1.52A) | `0x40252b74` (OS 1.54) | `slice_round_robin` | data | 2 B | aligned stretch, shift +0x3f000 |
| `0x40213b78` (OS 1.52A) | `0x40252b78` (OS 1.54) | `slice_round_robin` | data | 2 B | aligned stretch, shift +0x3f000 |
| `0x40213b7c` (OS 1.52A) | `0x40252b7c` (OS 1.54) | `slice_round_robin` | data | 2 B | aligned stretch, shift +0x3f000 |
| `0x40213b80` (OS 1.52A) | `0x40252b80` (OS 1.54) | `slice_round_robin` | data | 2 B | aligned stretch, shift +0x3f000 |
| `0x40213b84` (OS 1.52A) | `0x40252b84` (OS 1.54) | `slice_round_robin` | data | 1 B | aligned stretch, shift +0x3f000 |
| `0x40213b88` (OS 1.52A) | `0x40252b88` (OS 1.54) | `slice_round_robin` | data | 1 B | aligned stretch, shift +0x3f000 |
| `0x40213b8c` (OS 1.52A) | `0x40252b8c` (OS 1.54) | `slice_round_robin` | data | 1 B | aligned stretch, shift +0x3f000 |
| `0x40213b94` (OS 1.52A) | `0x40252b94` (OS 1.54) | `slice_round_robin` | data | 4 B | aligned stretch, shift +0x3f000 |
| `0x40213b9b` (OS 1.52A) | `0x40252b9b` (OS 1.54) | `slice_round_robin` | data | 1 B | aligned stretch, shift +0x3f000 |
| `0x40213b9f` (OS 1.52A) | `0x40252b9f` (OS 1.54) | `slice_round_robin` | data | 1 B | aligned stretch, shift +0x3f000 |
| `0x40213ba3` (OS 1.52A) | `0x40252ba3` (OS 1.54) | `slice_round_robin` | data | 9 B | aligned stretch, shift +0x3f000 |
| `0x40213bb0` (OS 1.52A) | `0x40252bb0` (OS 1.54) | `poly_icon` | data | 1 B | aligned stretch, shift +0x3f000 |
| `0x40213bb4` (OS 1.52A) | `0x40252bb4` (OS 1.54) | `poly_icon` | data | 1 B | aligned stretch, shift +0x3f000 |
| `0x40213bb8` (OS 1.52A) | `0x40252bb8` (OS 1.54) | `poly_icon` | data | 1 B | aligned stretch, shift +0x3f000 |
| `0x40213bc0` (OS 1.52A) | `0x40252bc0` (OS 1.54) | `poly_icon` | data | 1 B | aligned stretch, shift +0x3f000 |
| `0x40213bc4` (OS 1.52A) | `0x40252bc4` (OS 1.54) | `poly_icon` | data | 1 B | aligned stretch, shift +0x3f000 |
| `0x40213bc8` (OS 1.52A) | `0x40252bc8` (OS 1.54) | `poly_icon` | data | 1 B | aligned stretch, shift +0x3f000 |
| `0x40213bd0` (OS 1.52A) | `0x40252bd0` (OS 1.54) | `poly_icon` | data | 1 B | aligned stretch, shift +0x3f000 |
| `0x40213bd4` (OS 1.52A) | `0x40252bd4` (OS 1.54) | `poly_icon` | data | 1 B | aligned stretch, shift +0x3f000 |
| `0x40213bd8` (OS 1.52A) | `0x40252bd8` (OS 1.54) | `poly_icon` | data | 1 B | aligned stretch, shift +0x3f000 |
| `0x40213bdc` (OS 1.52A) | `0x40252bdc` (OS 1.54) | `poly_icon` | data | 4 B | aligned stretch, shift +0x3f000 |
| `0x40213be3` (OS 1.52A) | `0x40252be3` (OS 1.54) | `poly_icon` | data | 1 B | aligned stretch, shift +0x3f000 |
| `0x40213be7` (OS 1.52A) | `0x40252be7` (OS 1.54) | `poly_icon` | data | 1 B | aligned stretch, shift +0x3f000 |
| `0x40213beb` (OS 1.52A) | `0x40252beb` (OS 1.54) | `poly_icon` | data | 9 B | aligned stretch, shift +0x3f000 |
| `0x40213bf8` (OS 1.52A) | `0x40252bf8` (OS 1.54) | `poly_icon` | data | 8 B | aligned stretch, shift +0x3f000 |

After translation, one change to the build's own code (OS 1.54, `0x4001555e`): `2003` → `2002`, the pool-cursor marker stub's canvas register ([Caller registers](#caller-registers)).

## Appendix B: the addresses in the build's code and data

Every address operand of the build's code, and every address word of its data, as OS 1.52A has it and
as OS 1.54 has it in the same place, paired by disassembling both patched images; with the evidence for
each OS 1.54 value. Branch targets inside a pad count as the build's own code.

| OS 1.52A | OS 1.54 | Evidence |
|---|---|---|
| `0x40000e82` (OS 1.52A) | `0x40000e82` (OS 1.54) | inside an aligned stretch (also paired 127×) |
| `0x40001840` (OS 1.52A) | `0x40001840` (OS 1.54) | inside an aligned stretch (also paired 21×) |
| `0x40001b7a` (OS 1.52A) | `0x40001b7a` (OS 1.54) | inside an aligned stretch (also paired 60×) |
| `0x4000d514` (OS 1.52A) | `0x4000d9fc` (OS 1.54) | inside an aligned stretch (also paired 3×) |
| `0x4000f9a2` (OS 1.52A) | `0x4000fe8a` (OS 1.54) | inside an aligned stretch (also paired 1×) |
| `0x4000f9c4` (OS 1.52A) | `0x4000feac` (OS 1.54) | inside an aligned stretch |
| `0x4001488e` (OS 1.52A) | `0x40014d86` (OS 1.54) | inside an aligned stretch (also paired 240×) |
| `0x40014cb4` (OS 1.52A) | `0x400151ac` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x40014cd8` (OS 1.52A) | `0x400151d0` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x40014cda` (OS 1.52A) | `0x400151d2` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x40014ce0` (OS 1.52A) | `0x400151d8` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x40014d1e` (OS 1.52A) | `0x40015216` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x40014d5a` (OS 1.52A) | `0x40015252` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x40014d60` (OS 1.52A) | `0x40015258` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x40014db4` (OS 1.52A) | `0x400152ac` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x40014dbc` (OS 1.52A) | `0x400152b4` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x40015060` (OS 1.52A) | `0x40015558` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x4001506a` (OS 1.52A) | `0x40015562` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x40015074` (OS 1.52A) | `0x4001556c` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x400150ca` (OS 1.52A) | `0x400155c2` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x400150fe` (OS 1.52A) | `0x400155f6` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x4001510c` (OS 1.52A) | `0x40015604` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x4001511c` (OS 1.52A) | `0x40015614` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x4001511e` (OS 1.52A) | `0x40015616` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x4001512c` (OS 1.52A) | `0x40015624` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x40015134` (OS 1.52A) | `0x4001562c` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x4001514e` (OS 1.52A) | `0x40015646` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x40015154` (OS 1.52A) | `0x4001564c` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x40015180` (OS 1.52A) | `0x40015678` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x40015186` (OS 1.52A) | `0x4001567e` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x400151b8` (OS 1.52A) | `0x400156b0` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x400151d2` (OS 1.52A) | `0x400156ca` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x400151e0` (OS 1.52A) | `0x400156d8` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x40018cec` (OS 1.52A) | `0x400191fe` (OS 1.54) | inside an aligned stretch (also paired 1×) |
| `0x4001ccc4` (OS 1.52A) | `0x4001d24e` (OS 1.54) | inside an aligned stretch (also paired 54×) |
| `0x40029812` (OS 1.52A) | `0x40029e8e` (OS 1.54) | inside an aligned stretch |
| `0x40029890` (OS 1.52A) | `0x40029f0c` (OS 1.54) | inside an aligned stretch |
| `0x400298e4` (OS 1.52A) | `0x40029f60` (OS 1.54) | inside an aligned stretch |
| `0x40029f92` (OS 1.52A) | `0x4002a60e` (OS 1.54) | inside an aligned stretch |
| `0x40030eb6` (OS 1.52A) | `0x40030a28` (OS 1.54) | explicit (see the hand-mapped sites) |
| `0x4003771c` (OS 1.52A) | `0x40037a24` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x40037728` (OS 1.52A) | `0x40037a30` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x40037734` (OS 1.52A) | `0x40037a3c` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x40037746` (OS 1.52A) | `0x40037a4e` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x40037776` (OS 1.52A) | `0x40037a7e` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x40037790` (OS 1.52A) | `0x40037a98` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x400377b2` (OS 1.52A) | `0x40037aba` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x400377b6` (OS 1.52A) | `0x40037abe` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x400377be` (OS 1.52A) | `0x40037ac6` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x40062fe4` (OS 1.52A) | `0x40063228` (OS 1.54) | inside an aligned stretch |
| `0x4006526a` (OS 1.52A) | `0x400654ae` (OS 1.54) | inside an aligned stretch |
| `0x40074b42` (OS 1.52A) | `0x40074e42` (OS 1.54) | inside an aligned stretch |
| `0x40074b62` (OS 1.52A) | `0x40074e62` (OS 1.54) | inside an aligned stretch |
| `0x40075f58` (OS 1.52A) | `0x40076258` (OS 1.54) | inside an aligned stretch |
| `0x4007699e` (OS 1.52A) | `0x40076c9e` (OS 1.54) | inside an aligned stretch |
| `0x40076ef0` (OS 1.52A) | `0x400771f0` (OS 1.54) | inside an aligned stretch |
| `0x400afe46` (OS 1.52A) | `0x400bed3a` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x400afe7a` (OS 1.52A) | `0x400bed6e` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x400afe80` (OS 1.52A) | `0x400bed74` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x400afea0` (OS 1.52A) | `0x400bed94` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x400afea8` (OS 1.52A) | `0x400bed9c` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x400afeca` (OS 1.52A) | `0x400bedbe` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x400afee6` (OS 1.52A) | `0x400bedda` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x400afef0` (OS 1.52A) | `0x400bede4` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x400aff0e` (OS 1.52A) | `0x400bee02` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x400aff30` (OS 1.52A) | `0x400bee24` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x400aff34` (OS 1.52A) | `0x400bee28` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x400aff44` (OS 1.52A) | `0x400bee38` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x400aff50` (OS 1.52A) | `0x400bee44` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x400aff60` (OS 1.52A) | `0x400bee54` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x400aff66` (OS 1.52A) | `0x400bee5a` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x400b026e` (OS 1.52A) | `0x400bf16c` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x400b027e` (OS 1.52A) | `0x400bf17c` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x400b0286` (OS 1.52A) | `0x400bf184` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x400b028e` (OS 1.52A) | `0x400bf18c` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x400b02a0` (OS 1.52A) | `0x400bf19e` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x400b02b8` (OS 1.52A) | `0x400bf1b6` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x400b02ba` (OS 1.52A) | `0x400bf1b8` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x400b20ac` (OS 1.52A) | `0x400c1034` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x400b20b0` (OS 1.52A) | `0x400c1038` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x400b20d4` (OS 1.52A) | `0x400c105c` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x400b21b0` (OS 1.52A) | `0x400c1138` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x400b21d4` (OS 1.52A) | `0x400c115c` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x400b21e8` (OS 1.52A) | `0x400c1170` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x400b2214` (OS 1.52A) | `0x400c119c` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x400b222a` (OS 1.52A) | `0x400c11b2` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x400b224c` (OS 1.52A) | `0x400c11d4` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x400b22e0` (OS 1.52A) | `0x400c1268` (OS 1.54) | inside an aligned stretch (also paired 26×) |
| `0x400b23b0` (OS 1.52A) | `0x400c1338` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x400b23f6` (OS 1.52A) | `0x400c137e` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x400b2410` (OS 1.52A) | `0x400c1398` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x400b243a` (OS 1.52A) | `0x400c13c2` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x400b2442` (OS 1.52A) | `0x400c13ca` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x400b244a` (OS 1.52A) | `0x400c13d2` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x400b2450` (OS 1.52A) | `0x400c13d8` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x400b245e` (OS 1.52A) | `0x400c13e6` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x400b2463` (OS 1.52A) | `0x400c13eb` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x400ba31a` (OS 1.52A) | `0x400c9a3a` (OS 1.54) | inside an aligned stretch (also paired 164×) |
| `0x400c2c60` (OS 1.52A) | `0x400d2cf8` (OS 1.54) | inside an aligned stretch |
| `0x400c46da` (OS 1.52A) | `0x400d48e6` (OS 1.54) | inside an aligned stretch |
| `0x400c4be2` (OS 1.52A) | `0x400d4df4` (OS 1.54) | inside an aligned stretch |
| `0x400cfd9c` (OS 1.52A) | `0x400e0a08` (OS 1.54) | inside an aligned stretch |
| `0x400d0044` (OS 1.52A) | `0x400e0cb0` (OS 1.54) | inside an aligned stretch (also paired 3×) |
| `0x400d039c` (OS 1.52A) | `0x400e1008` (OS 1.54) | inside an aligned stretch |
| `0x4012198c` (OS 1.52A) | `0x40138882` (OS 1.54) | inside an aligned stretch (also paired 431×) |
| `0x4015cb8a` (OS 1.52A) | `0x401770a6` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x4015cba2` (OS 1.52A) | `0x401770be` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x4015cbb2` (OS 1.52A) | `0x401770ce` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x4015cbbe` (OS 1.52A) | `0x401770da` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x4015cbcc` (OS 1.52A) | `0x401770e8` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x4015cbce` (OS 1.52A) | `0x401770ea` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x4015cbd0` (OS 1.52A) | `0x401770ec` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x4015cbdc` (OS 1.52A) | `0x401770f8` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x4019a194` (OS 1.52A) | `0x401b7734` (OS 1.54) | inside an aligned stretch (also paired 5×) |
| `0x401a8937` (OS 1.52A) | `0x401c6975` (OS 1.54) | inside an aligned stretch (also paired 3×) |
| `0x401a893c` (OS 1.52A) | `0x401d69a1` (OS 1.54) | paired in common code (4×) |
| `0x401a8f20` (OS 1.52A) | `0x401c6f07` (OS 1.54) | paired in common code (5×) |
| `0x401a8f25` (OS 1.52A) | `0x401c6f0c` (OS 1.54) | paired in common code (1×) |
| `0x401a8f38` (OS 1.52A) | `0x401c6f1f` (OS 1.54) | paired in common code (1×) |
| `0x401ae9b6` (OS 1.52A) | `0x401cc9f2` (OS 1.54) | inside an aligned stretch (also paired 1×) |
| `0x401ae9be` (OS 1.52A) | `0x401cc9fa` (OS 1.54) | inside an aligned stretch (also paired 1×) |
| `0x401ae9c3` (OS 1.52A) | `0x401cc9ff` (OS 1.54) | inside an aligned stretch (also paired 2×) |
| `0x401aea03` (OS 1.52A) | `0x401cca3f` (OS 1.54) | inside an aligned stretch (also paired 2×) |
| `0x401b1a49` (OS 1.52A) | `0x401d031b` (OS 1.54) | inside an aligned stretch (also paired 37×) |
| `0x401b2401` (OS 1.52A) | `0x401d0d44` (OS 1.54) | the same string, found by content |
| `0x401ff5a0` (OS 1.52A) | `0x4023e0a0` (OS 1.54) | inside an aligned stretch (also paired 1×) |
| `0x4020f6f4` (OS 1.52A) | `0x4024de68` (OS 1.54) | inside an aligned stretch (also paired 1×) |
| `0x40213b50` (OS 1.52A) | `0x40252b50` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x40213b94` (OS 1.52A) | `0x40252b94` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x40213bb0` (OS 1.52A) | `0x40252bb0` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x40213bdc` (OS 1.52A) | `0x40252bdc` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x40213bf8` (OS 1.52A) | `0x40252bf8` (OS 1.54) | this build's own code or data (a pad or the `.rodata` padding) |
| `0x4193d70c` (OS 1.52A) | `0x4197c700` (OS 1.54) | paired in common code (3×) |
| `0x4196032d` (OS 1.52A) | `0x4199f47d` (OS 1.54) | between two paired neighbours with the same shift |
| `0x4215cf54` (OS 1.52A) | `0x4219cc1c` (OS 1.54) | paired in common code (3×) |
| `0x4216a06c` (OS 1.52A) | `0x421a9d34` (OS 1.54) | paired in common code (5×) |
| `0x4216a074` (OS 1.52A) | `0x421a9d3c` (OS 1.54) | paired in common code (5×) |
| `0x4216a078` (OS 1.52A) | `0x421a9d40` (OS 1.54) | between two paired neighbours with the same shift |
| `0x42176898` (OS 1.52A) | `0x421b6620` (OS 1.54) | paired in common code (4×) |
| `0x42189422` (OS 1.52A) | `0x421c91aa` (OS 1.54) | between two paired neighbours with the same shift |
| `0x421897dc` (OS 1.52A) | `0x421c9564` (OS 1.54) | between two paired neighbours with the same shift |
| `0x421b957c` (OS 1.52A) | `0x421fa35c` (OS 1.54) | paired in common code (1×) |
| `0x421b9c3c` (OS 1.52A) | `0x421faa04` (OS 1.54) | paired in common code (2×) |
| `0x4395ddf4` (OS 1.52A) | `0x4399eb54` (OS 1.54) | paired in common code (7×) |
| `0x439902a4` (OS 1.52A) | `0x439d1004` (OS 1.54) | this build's RAM: same offset above the `.bss` end |
| `0x439902b0` (OS 1.52A) | `0x439d1010` (OS 1.54) | this build's RAM: same offset above the `.bss` end |
| `0x439902f0` (OS 1.52A) | `0x439d1050` (OS 1.54) | this build's RAM: same offset above the `.bss` end |
| `0x439902f8` (OS 1.52A) | `0x439d1058` (OS 1.54) | this build's RAM: same offset above the `.bss` end |
| `0x43990300` (OS 1.52A) | `0x439d1060` (OS 1.54) | this build's RAM: same offset above the `.bss` end |
| `0x43990380` (OS 1.52A) | `0x439d10e0` (OS 1.54) | this build's RAM: same offset above the `.bss` end |
| `0x80001228` (OS 1.52A) | `0x80001228` (OS 1.54) | SRAM, unchanged; paired in common code (6×) |
| `0x800019ac` (OS 1.52A) | `0x800019ac` (OS 1.54) | SRAM, unchanged; paired in common code (12×) |


## Related notes

- [update_moat_method.md](update_moat_method.md), [landing_pad_method.md](landing_pad_method.md): the
  methods applied in each image.
- [analysis_method.md](analysis_method.md): the rule that no single method is trusted alone.
