# Notes

## What these notes are for

These notes describe how the stock Digitakt OS 1.52A firmware works, as far as it matters for this
build, and exactly what this build changes. They are the reference for three jobs: understanding a
part of the firmware, checking a claim in this repo against the firmware yourself, and designing a
new change safely. They are not a user manual.

Every fact carries its evidence and a confidence mark, and a count that differs between Ghidra projects
names the project it came from. Anything read from the code can be re-checked without a Digitakt: an
address, a stock `.syx` of your own and the toolchain in [docs/toolchain.md](../docs/toolchain.md) are
enough. The notes contain no manual text. They describe stock code in words and give addresses, with a
short expression or a single instruction as a locator; where a few lines of decompiled code explain a
site better than words, a note quotes a short, labelled excerpt
([Decompiler excerpts](#decompiler-excerpts)). The instruction listings in the notes are of the code
this build adds. Building the image is described in
[docs/building.md](../docs/building.md); a searchable copy of your own Digitakt manual, to keep beside
the notes, is made with [scripts/manual/README.md](../scripts/manual/README.md).

## The features in this build

In the words a Digitakt user would use:

- **SLICE round robin.** The SLICE machine's Slice Select parameter gets two values below NOTE. With
  RRBN, each note trig plays the next slice of the sample's grid, in turn; each track keeps its own
  order. The cell shows a robin icon for these values. RRND is work in progress: in this build it is
  identical to RRBN.
  [features/slice_round_robin.md](features/slice_round_robin.md)
- **POLY voice pool.** A fifth machine, POLY, in the MACHINE menu ([FUNC] + [SRC]). A POLY track lends
  its voice to the nearest audio track before it that does not use POLY (its Source), so the Source's
  Sound can play several notes at the same time. The Digitakt still has eight audio voices. The
  parameter pages of a POLY track show and edit the Source's parameters. Its code sites are in the
  ledger, from
  [machine assignment, the pool map and the audio ISR](function_ledger.md#machine-assignment-the-pool-map-and-the-audio-isr)
  on; the byte runs are listed in [docs/patch_listing.md](../docs/patch_listing.md)
  ([`poly_engine`](../docs/patch_listing.md#poly-engine), [`poly_ui`](../docs/patch_listing.md#poly-ui),
  [`poly_icon`](../docs/patch_listing.md#poly-icon)).
- **Pool cursors.** SRC page 2 of a track in a POLY voice pool shows one playhead cursor for every voice
  of the pool that is sounding. [features/pool_cursors.md](features/pool_cursors.md)
- **Voice allocation.** A note takes a free voice of its pool first, and takes over a playing voice only
  when every voice of the pool is busy. [features/voice_allocation.md](features/voice_allocation.md)
- **Owner latch.** A live, external-MIDI or Loopback note held on a pool voice is released by its
  note-off, even across a pattern switch. [features/owner_latch.md](features/owner_latch.md)
- **Mute by origin.** Muting a POLY Source silences all of its trigs, on whichever voice of its pool they
  play. [features/mute_by_origin.md](features/mute_by_origin.md)
- **Virtual MIDI Loopback.** A MIDI track's **CHAN** parameter gets eight values below 1, TRK1–TRK8: the MIDI
  track then plays audio track 1–8 with no cable. Notes routed this way are not recorded as trigs.
  [features/midi_loopback.md](features/midi_loopback.md)
- **Tick-wipe fix.** A fix for a stock defect: a note released and re-triggered within one audio tick
  could keep sounding with nothing but All Sound Off to stop it.
  [features/tick_wipe_fix.md](features/tick_wipe_fix.md)
- **Pad fill.** Not a feature you can use: the fill bytes left in the landing pads that were cleared
  for a fill test before the new code went in; three of its `rts` also end two POLY pads
  ([landing_pads.md](landing_pads.md), [docs/building.md](../docs/building.md)).

## How to read a note

Each note opens with a short "What this is". Facts about code are mostly in tables of the form
*address · what it does · evidence*. Search by the bare hex digits of an address (for example
`40076ee8`), which finds both the `FUN_40076ee8` name and the `0x40076ee8` form; a few lists shorten a
run of nearby addresses to their last digits. The per-function record is
[function_ledger.md](function_ledger.md); the other notes explain a subsystem or a feature and point
into it.

"The test unit" is one Digitakt (the original model) running OS 1.52A. A result confirmed on it says
nothing about other units or other OS versions.

### Confidence marks

| Mark | Meaning |
|---|---|
| ✅ | Confirmed. The note always says which kind: **confirmed on the test unit**, or **read directly in the code**, with the method in the evidence (decompiled, objdump, hexdump, reference or literal scan, emulator run) |
| ⚠️ | Inference: plausible but not confirmed. The note says what it rests on |
| ⛔ | Checked and ruled out, or a thing never to do. Kept on purpose, so the next reader does not walk the same path |

A statement with no mark describes what the code does, read by the method named in its evidence, with
no further claim. An inference stays marked ⚠️ until a check settles it; it never becomes a fact by
being repeated.

### Addresses

- **MAIN OS (section 3)** addresses are load addresses. Section 3 loads at `0x40000400` in DDR, so the
  file offset of a byte in the extracted section is **load − `0x40000400`**. Every address in the notes
  is a MAIN OS load address unless it says otherwise.
- **Section 2** runs from on-chip SRAM at **`0x80000ec0`**. Its inner header names `0x03000900` as its
  destination; ⚠️ what that value means is not settled ([firmware_image.md](firmware_image.md),
  [section2_map.md](section2_map.md)). Section 2 functions are always marked as such.
- **Section 4** (the updater) loads at **`0x80000400`** in SRAM, entry `0x80000492`.
- A `0x8000xxxx` address in a MAIN OS row is data in the 64 KB of on-chip SRAM that MAIN OS reads or
  writes, not section 2 code. [memory_map.md](memory_map.md) has both address spaces.

### Names

- **Ghidra auto-names** are addresses with no known name: `FUN_` (a function), `DAT_` and `_DAT_` (data;
  the underscore marks a global that overlaps smaller symbols), `LAB_` (a code label), `switchD_` (a
  switch dispatch) and `entry_` (a function), plus `PTR_` (a pointer) and `s_` (a string) for labelled
  data. A fresh import may not start a function at exactly the same address.
- **`Class::vfunc_N`** names come from the RTTI walk (`NameFromRtti.java`), which recovers the C++
  classes and vtables and names each virtual function after its class and slot. Slot *N* is at
  `vptr + 4N`. The vtable address in an RTTI listing is the start of the vtable structure, and the
  pointer an object stores is that address + 8, so reading slots from the listed address is off by two.
  One implementation often serves several classes; it carries one class's name, and the decompiler's
  "also …" footer lists the rest. A few real member names come from lambda type-info.
- The voice engine and the audio renderer have no vtables, so they never name themselves: only the
  ledger names them.

### Decompiler excerpts

Some notes quote short excerpts of Ghidra's decompiler output. That output is Ghidra's reconstruction
of Elektron's stock firmware code, not Elektron's source: names such as `uVar8`, `param_4` or
`local_54` are Ghidra's auto-names, and the types and casts are its guesses.

- **Fenced excerpts** are labelled on the line above with what they are and the function they come
  from, for example "Ghidra decompiler output (excerpt), `FUN_40077120`:". "Variables renamed" in the
  label means some auto-named variables were given readable names; "condensed" means statements were
  shortened (for example a virtual call written as a method call). `…` marks code left out, and the
  comments are the notes' own.
- **Inline excerpts** are single expressions or statements in backticks, taken from the decompile
  (sometimes with variables renamed); Ghidra's auto-names, or the text, say so. Other inline code is
  the notes' own notation for what the code computes.
- An excerpt is a few lines that explain one site, never a whole function. The full decompile is made
  with the toolchain ([docs/toolchain.md](../docs/toolchain.md)) and is not part of this repository.

### Which Ghidra project a number came from

The notes cite these projects by their folder names under `work/ghidra/`. How to make each one, and the
reference counts to compare a run against, are in [docs/toolchain.md](../docs/toolchain.md) (section
4d); why counts differ between them is in [analysis_method.md](analysis_method.md).

| Project | What it is |
|---|---|
| `dt_1.52A` | MAIN OS on Ghidra's stock ColdFire language: the control |
| `dt_1.52A_emac` | MAIN OS on the ColdFire EMAC language: use it for anything touching audio code, and for the emulator |
| `dt_1.52A_seed` | a copy of `_emac` with the code gaps seeded: the most complete MAIN OS map and the landing-pad candidate list |
| `dt_1.52A_sram` | the 64 KB on-chip SRAM as section 2 sees it, with section 2 and the updater in their real address space |

[docs/toolchain.md](../docs/toolchain.md) also describes `dt_1.52A_dsp` (section 2 alone) and
`dt_1.52A_updater` (section 4 alone).

Decompile line numbers ("line 314") are locators in that project's own decompile of the named
function. They shift with the Ghidra version and the project state; the address is the reference.

## Reading order

This is the one reading order for the notes.

**To understand the firmware:**

1. [architecture.md](architecture.md): the map of MAIN OS to drill down from.
2. [function_ledger.md](function_ledger.md): address → job for every function worked out; search it
   by address or by behaviour.
3. The data model: [pattern_layout.md](pattern_layout.md), [parameters.md](parameters.md),
   [sysex_dump.md](sysex_dump.md).
4. Execution: [render_path.md](render_path.md), [section2_map.md](section2_map.md).
5. Hardware and image: [hardware.md](hardware.md), [firmware_image.md](firmware_image.md),
   [memory_map.md](memory_map.md).
6. The features: [SLICE round robin](features/slice_round_robin.md),
   [MIDI Loopback](features/midi_loopback.md), [pool cursors](features/pool_cursors.md),
   [voice allocation](features/voice_allocation.md), [owner latch](features/owner_latch.md),
   [mute by origin](features/mute_by_origin.md), [tick-wipe fix](features/tick_wipe_fix.md), and the
   two new images in [visual_assets.md](visual_assets.md). The POLY voice pool's hook sites are in the
   ledger
   ([machine assignment, the pool map and the audio ISR](function_ledger.md#machine-assignment-the-pool-map-and-the-audio-isr))
   and in [docs/patch_listing.md](../docs/patch_listing.md#poly-engine).
7. [open_questions.md](open_questions.md): what is still unknown.
8. [analysis_method.md](analysis_method.md): how the notes were produced, and how to re-check any of
   them.

**Before writing a patch** (the update path first):

1. [update_moat.md](update_moat.md): the code and image sections that must never change, so the unit
   can always be re-flashed.
2. [memory_map.md](memory_map.md) and [landing_pads.md](landing_pads.md): where new code and data can
   go.
3. [flash_recovery.md](flash_recovery.md): the recovery routes, and which hooks run at startup.
4. [compatibility.md](compatibility.md): how new values behave on stock firmware and back.
5. [firmware_image.md](firmware_image.md): the image sections; only section 3 is patched.
6. [emulator.md](emulator.md): stepping inserted code before it is flashed.
7. [analysis_method.md](analysis_method.md): decode twice, and never call anything unused on one
   method.

## Index

- [analysis_method.md](analysis_method.md): how the analysis was produced and kept reproducible: the
  Ghidra projects, decoding twice with objdump, the EMAC language extension, the deadness rules.
- [architecture.md](architecture.md): the subsystem-level map of MAIN OS: boot, RTOS tasks, clocks, the
  audio ISR, where each kind of code lives, the C++ classes.
- [compatibility.md](compatibility.md): what happens to projects moved between this build and stock
  OS 1.52A, in both directions.
- [emulator.md](emulator.md): running stock code and this build's inserted code in Ghidra's p-code
  emulator, and the assembler rules for hand-written patch code.
- [firmware_image.md](firmware_image.md): the OS update file: SysEx transport, the container, the four
  sections and the firmware tool.
- [flash_recovery.md](flash_recovery.md): what to do when a unit will not take an image, will not start
  or hangs, in the order to try things.
- [function_ledger.md](function_ledger.md): the per-function ledger: one row per function, with its
  evidence and confidence.
- [hardware.md](hardware.md): what is inside a Digitakt, from public teardown photographs and the
  image.
- [landing_pads.md](landing_pads.md): the stock functions that hold all new code, what is free in them,
  and how a new pad is vetted.
- [memory_map.md](memory_map.md): DDR and on-chip SRAM at run time, the RAM this build uses, and the
  routes for new code that are closed.
- [open_questions.md](open_questions.md): what is still unknown about the stock firmware, and what would
  answer it.
- [parameters.md](parameters.md): the parameter descriptor table: record layout, every parameter by
  page, the value arrays and the code that reads and writes them.
- [pattern_layout.md](pattern_layout.md): the byte map of a pattern and its kit, as the pattern dump
  carries them.
- [render_path.md](render_path.md): the audio render, from the event queue to the codec buffer, and the
  rules for code that hooks it.
- [section2_map.md](section2_map.md): a map of section 2, the boot-time hardware bring-up program in
  SRAM.
- [sysex_dump.md](sysex_dump.md): the Digitakt's own SysEx project and pattern dumps: envelope, 8-in-7
  encoding and message types.
- [update_moat.md](update_moat.md): the update path, as address ranges that no patch may touch.
- [visual_assets.md](visual_assets.md): the stock `Bitmap` format, its row order, and the two new
  images.
- [features/mute_by_origin.md](features/mute_by_origin.md): mute follows the track a note came from.
- [features/owner_latch.md](features/owner_latch.md): a note-off finds the voice its note-on took.
- [features/pool_cursors.md](features/pool_cursors.md): one playhead per sounding voice on SRC page 2.
- [features/slice_round_robin.md](features/slice_round_robin.md): the RRBN and RRND values of SLICE
  Select.
- [features/tick_wipe_fix.md](features/tick_wipe_fix.md): a voice released and re-triggered in one audio
  tick keeps its bookkeeping.
- [features/midi_loopback.md](features/midi_loopback.md): a MIDI track plays an audio track without a
  cable.
- [features/voice_allocation.md](features/voice_allocation.md): a note takes a free voice first, then
  steals.
