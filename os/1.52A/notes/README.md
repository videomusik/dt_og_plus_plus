# OS 1.52A notes

## What these notes are for

These notes describe how the stock Digitakt OS 1.52A firmware works, as far as it matters for this
build, and exactly what this build changes. Every address, hash, range and unit result in this folder
is for OS 1.52A: its stock file, or the DT OG++ build made from it. Where these notes cite the
Digitakt manual, they cite its OS1.50 edition, the reference edition of the manual pipeline
([scripts/manual/README.md](../../../scripts/manual/README.md)).

The OS 1.52A build is on hold: it has not been developed past its last feature, and these notes
describe it as it stands. New features are developed for OS 1.54 only.

Conventions, marks and method: [notes/README.md](../../../notes/README.md).

## The test unit

In this folder, "the test unit" is one Digitakt (the original model) running OS 1.52A or a DT OG++
build made from it. A ✅ unit result here holds for OS 1.52A only.

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
  ([landing_pads.md](landing_pads.md), [docs/patch_listing.md](../docs/patch_listing.md#pad-fill)).

## Addresses

- **MAIN OS (section 3)** addresses are load addresses. Section 3 loads at `0x40000400` in DDR, so the
  file offset of a byte in the extracted section is **load − `0x40000400`**. Every address in the notes
  is a MAIN OS load address unless it says otherwise.
- **Section 2** runs from on-chip SRAM at **`0x80000ec0`**. Its inner header names `0x03000900` as its
  destination; ⚠️ what that value means is not settled ([stock_image.md](stock_image.md),
  [section2_map.md](section2_map.md)). Section 2 functions are always marked as such.
- **Section 4** (the updater) loads at **`0x80000400`** in SRAM, entry `0x80000492`.
- A `0x8000xxxx` address in a MAIN OS row is data in the 64 KB of on-chip SRAM that MAIN OS reads or
  writes, not section 2 code. [memory_map.md](memory_map.md) has both address spaces.
  The chip's own address map is in [hardware.md](../../../notes/hardware.md).
- The voice engine and the audio renderer have no vtables, so they never name themselves: only the
  ledger names them.

## Which Ghidra project a number came from

The notes cite these projects by their folder names under `work/ghidra/`. How each is made, and the
reference counts: [analysis_reference.md](analysis_reference.md#the-ghidra-projects) (the counts are
under [Reference numbers](analysis_reference.md#reference-numbers)); why counts differ between them is
in [analysis_method.md](../../../notes/analysis_method.md) (the method) and, for this image, in the bullets under [Reference numbers](analysis_reference.md#reference-numbers).

| Project | What it is |
|---|---|
| `dt_1.52A` | MAIN OS on Ghidra's stock ColdFire language: the control |
| `dt_1.52A_emac` | MAIN OS on the ColdFire EMAC language: use it for anything touching audio code, and for the emulator |
| `dt_1.52A_seed` | a copy of `_emac` with the code gaps seeded: the most complete MAIN OS map and the landing-pad candidate list |
| `dt_1.52A_sram` | the 64 KB on-chip SRAM as section 2 sees it, with section 2 and the updater in their real address space |

[analysis_reference.md](analysis_reference.md#the-ghidra-projects) also describes `dt_1.52A_dsp`
(section 2 alone) and `dt_1.52A_updater` (section 4 alone).

Decompile line numbers ("line 314") are locators in that project's own decompile of the named
function. They shift with the Ghidra version and the project state; the address is the reference.

## Reading order

This is the reading order for the OS 1.52A notes. It continues the shared reading order in
[notes/README.md](../../../notes/README.md#reading-order).

**To understand the firmware:**

1. [architecture.md](architecture.md): the map of MAIN OS to drill down from.
2. [function_ledger.md](function_ledger.md): address → job for every function worked out; search it
   by address or by behaviour.
3. The data model: [pattern_layout.md](pattern_layout.md), [parameters.md](parameters.md),
   [sysex_dump.md](sysex_dump.md).
4. Execution: [render_path.md](render_path.md), [section2_map.md](section2_map.md).
5. Hardware and image: [hardware.md](../../../notes/hardware.md), [stock_image.md](stock_image.md)
   (the file format: [firmware_image.md](../../../notes/firmware_image.md)),
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
8. [analysis_method.md](../../../notes/analysis_method.md): how the notes were produced, and how to
   re-check any of them; [analysis_reference.md](analysis_reference.md) holds the figures of this
   image to re-check against.

**Before writing a patch** (the update path first):

1. [update_moat_method.md](../../../notes/update_moat_method.md) and
   [landing_pad_method.md](../../../notes/landing_pad_method.md): the rules for the update path and
   for landing pads, which the next two steps apply to this image.
2. [update_moat.md](update_moat.md): the code and image sections that must never change, so the unit
   can always be re-flashed.
3. [memory_map.md](memory_map.md) and [landing_pads.md](landing_pads.md): where new code and data can
   go.
4. [flash_recovery.md](../../../notes/flash_recovery.md): the recovery routes;
   [startup_hooks.md](startup_hooks.md): which hooks run at startup.
5. [compatibility.md](compatibility.md): how new values behave on stock firmware and back.
6. [stock_image.md](stock_image.md): the image sections; only section 3 is patched.
7. [emulator.md](../../../notes/emulator.md): stepping inserted code before it is flashed;
   [emulator_runs.md](emulator_runs.md): the runs and instruction forms already verified for this
   build.
8. [analysis_method.md](../../../notes/analysis_method.md): decode twice, and never call anything
   unused on one method.

## Index

The shared notes are indexed in [notes/README.md](../../../notes/README.md#index).

- [README.md](README.md): this page: what these notes cover, the test unit, the features, addresses,
  the Ghidra projects, the reading order and this index.
- [analysis_reference.md](analysis_reference.md): the OS 1.52A figures behind the analysis method: the
  section-3 hashes, determinism, the Ghidra projects and their recipes, the reference numbers, the EMAC
  extension on this image, worked examples and positive controls.
- [architecture.md](architecture.md): the subsystem-level map of MAIN OS: boot, RTOS tasks, clocks, the
  audio ISR, where each kind of code lives, the C++ classes.
- [compatibility.md](compatibility.md): what happens to projects moved between this build and stock
  OS 1.52A, in both directions.
- [emulator_runs.md](emulator_runs.md): the emulator runs on this image and this build: what has been
  checked, examples, seeding the audio ISR, the instruction forms verified, and the hook use sites.
- [function_ledger.md](function_ledger.md): the per-function ledger: one row per function, with its
  evidence and confidence.
- [landing_pads.md](landing_pads.md): the stock functions that hold all new code, what is free in them,
  the candidate list and the vetting controls on this image.
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
- [startup_hooks.md](startup_hooks.md): the code this build runs at startup: the hooks on the startup
  path, their pass-through results, the static initialiser, and the hooks that may also run then.
- [stock_image.md](stock_image.md): every measured figure of the stock OS 1.52A image: the file, the
  SysEx transport, the ELE3 container, integrity, rebuilds with the firmware tool, the 1 MB
  back-reference window, and the image evidence for the hardware notes.
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
