# OS 1.54 notes

## What these notes are for

These notes describe how the stock Digitakt OS 1.54 firmware works, as far as it matters for this
build, and exactly what this build changes. Every address, hash, range and unit result in this folder
is for OS 1.54: its stock file, or the DT OG++ build made from it. No manual edition has been checked
against these notes yet.

Conventions, marks and method: [notes/README.md](../../../notes/README.md).

## The test unit

In this folder, "the test unit" is one Digitakt (the original model) running OS 1.54 or a DT OG++
build made from it. A ✅ unit result here holds for OS 1.54 only. Every other ✅ here is read directly in
the code, with its method.

✅ On the test unit, one image at a time, each started and ran:

1. the stock OS 1.54 update file;
2. the stock MAIN OS repacked by the pinned tool (`.syx` `81d5a468…`);
3. the nine landing pads filled with `clrl %d0 ; rts` and no feature code (`.syx` `5001c01f…`). Which
   areas of the firmware were exercised on it was not recorded;
4. the build with every feature of the OS 1.52A build (section 3 `5a7eb2a4…`, `.syx` `6dec6823…`). In a
   quick check these work: RRBN, the POLY voice pools, mute by origin, voice allocation, the pool cursors,
   the POLY picker icon and the robin, and MIDI tracks playing audio tracks through TRK values. No stuck
   note was seen; the stuck-note test itself was not run.

Then, for Chain Recording, which the build in this folder adds on top of that
([features/chain_record.md](features/chain_record.md#testing-on-the-unit)):

5. that build with the soft-float pad filled (`.syx` `2c292d2e…`), and the same with a first Chain
   Recording build's hooks, each replaying only the stock code it displaced (`.syx` `a2501d97…`): each
   started and ran, with nothing found wrong;
6. that first Chain Recording build (`.syx` `688066c9…`): a chain of four slots of 8 steps, each armed
   with YES and started by the threshold, was recorded, normalised, trimmed and saved. FUNC+NO between
   slots did nothing, and encoder D moved much faster than encoder G;
7. the build in this folder (`.syx` `3fd4b0a3…`), which changes those two and adds auto re-arm,
   after its two stages (the new pad filled, `.syx` `4aae845e…`; every hook replaying stock code,
   `.syx` `ad7c9a72…`): the three images started and ran, and what was checked on them worked,
   reported as a whole. ⚠️ One
   display glitch is open: after the screen that asks whether to apply the new sample to a track, the
   top and bottom of the screen stay black
   ([features/chain_record.md](features/chain_record.md#open-the-screen-after-saving)).

## The features in this build

The same features as the OS 1.52A build, carried over to this image by the method in the
[version comparison](../../../notes/version_comparison_1.52A_1.54.md):

- **SLICE round robin.** The SLICE machine's Slice Select parameter gets RRBN and RRND below NOTE;
  each note trig plays the next slice of the grid. RRND is work in progress and identical to RRBN.
- **POLY voice pool.** A fifth machine, POLY, that lends a track's voice to the nearest audio track
  before it that does not use POLY (its Source).
- **Pool cursors.** SRC page 2 of a track in a POLY voice pool shows one playhead per sounding voice.
- **Voice allocation.** A note takes a free voice of its pool first, and steals only when all are busy.
- **Owner latch.** A held note on a pool voice is released by its note-off, even across a pattern
  switch.
- **Mute by origin.** Muting a POLY Source silences all of its trigs, on whichever voice they play.
- **Virtual MIDI Loopback.** A MIDI track's CHAN gets TRK1–TRK8 below 1: the track plays that audio
  track with no cable.
- **Tick-wipe fix.** A note released and re-triggered within one audio tick no longer hangs.
- **Pad fill.** The fill bytes left in the cleared landing pads.

New in this image, not in the OS 1.52A build:

- **Chain Recording.** Encoder D on the recorder page sets a slot count, with arming by the user or
  auto re-arm; the recorder then fills a sample chain one slot of RLEN steps at a time, for the SLICE
  machine's GRID. ✅ Run on the test unit; ⚠️ one display glitch open.
  [features/chain_record.md](features/chain_record.md).

In progress, not in the build:

- **CFO oscillator.** A 3-oscillator 8-bit FM synth, written into a track's audio
  buffer where the sampler's samples go. The code is checked in the emulator; it is waiting for a
  landing pad. [features/cfo_oscillator.md](features/cfo_oscillator.md).
- **Portamento.** PORT and LEG on every audio track's TRIG page: the note glides to each new note, or
  with LEG on only to a legato one, for samples and the CFO oscillator. Checked in the emulator; test
  images on top of the CFO oscillator's, not yet run on the unit.
  [features/portamento.md](features/portamento.md).

The other features have run on the test unit ([The test unit](#the-test-unit)).

Where each feature's code sits in this image: [function_ledger.md](function_ledger.md) and
[docs/patch_listing.md](../docs/patch_listing.md).

✅ Read in the code by emulation: the eight harnesses in
[scripts/emu/README.md](../scripts/emu/README.md) pass on the reference build. They step the pad code
of pool cursors, the MIDI Loopback private lane and display pads, the SLICE latch, the three POLY
track aliases and the machine-list edit, and run the recorder engine with Chain Recording's hooks, in
Ghidra's p-code emulator, with stubbed callees.

## Addresses

- **MAIN OS (section 3)** addresses are load addresses. Section 3 loads at `0x40000400` in DDR, so
  the file offset of a byte in the extracted section is **load − `0x40000400`**. Every address in the
  notes is a MAIN OS load address unless it says otherwise.
- **Section 2** runs from on-chip SRAM, its code at **`0x80000414`**
  ([stock_image.md](stock_image.md#section-2s-run-base)).
- **Section 4** (the updater) loads at **`0x80000400`** in SRAM, entry `0x80000492`.
- **Section 8** is not code for the Digitakt's processor ([stock_image.md](stock_image.md#section-8)).
- A `0x8000xxxx` address in a MAIN OS row is data in the 64 KB of on-chip SRAM that MAIN OS reads or
  writes ([memory_map.md](memory_map.md)).

## Which Ghidra project a number came from

| Project | What it is |
|---|---|
| `dt_1.54_emac` | MAIN OS on the ColdFire EMAC language; every Ghidra figure in this folder comes from it |

How it is made, and its reference numbers: [analysis_reference.md](analysis_reference.md).

## Reading order

**To understand the firmware:** [stock_image.md](stock_image.md), [memory_map.md](memory_map.md),
[function_ledger.md](function_ledger.md).

**Before writing a patch** (the update path first): [update_moat.md](update_moat.md),
[landing_pads.md](landing_pads.md), [memory_map.md](memory_map.md),
[startup_hooks.md](startup_hooks.md), [compatibility.md](compatibility.md).

## Index

- [README.md](README.md): this page.
- [analysis_reference.md](analysis_reference.md): the Ghidra project of this image and its reference
  numbers.
- [compatibility.md](compatibility.md): projects moved between this build and stock OS 1.54.
- [features/cfo_oscillator.md](features/cfo_oscillator.md): the CFO oscillator, not in the build yet,
  and where a track's samples come from.
- [features/chain_record.md](features/chain_record.md): Chain Recording, and how the stock recorder
  works.
- [function_ledger.md](function_ledger.md): the per-function ledger.
- [landing_pads.md](landing_pads.md): the pads that hold the new code, why each is dead here, and the
  `.rodata` space.
- [memory_map.md](memory_map.md): DDR, the RAM this build uses, and the SRAM.
- [startup_hooks.md](startup_hooks.md): the build's code that runs at startup, and what checked it.
- [stock_image.md](stock_image.md): every measured figure of the stock image, section 2's run base
  and section 8.
- [update_moat.md](update_moat.md): the protected set, how it was fixed, and the routes it does not
  cover.
