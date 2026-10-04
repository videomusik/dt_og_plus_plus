# OS 1.54 notes

## What these notes are for

These notes describe how the stock Digitakt OS 1.54 firmware works, as far as it matters for this
build, and exactly what this build changes. Every address, hash, range and unit result in this folder
is for OS 1.54: its stock file, or the DT OG++ build made from it. No manual edition has been checked
against these notes yet.

Conventions, marks and method: [notes/README.md](../../../notes/README.md).

## The test unit

In this folder, "the test unit" is one Digitakt (the original model) running OS 1.54 or a DT OG++
build made from it. A ✅ unit result here holds for OS 1.54 only. ⚠️ There are no unit results in this
folder yet: every ✅ here is read directly in the code, with its method.

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

- **Chain Recording.** Encoder D on the recorder page sets a slot count; the recorder then fills a
  sample chain one armed slot of RLEN steps at a time, for the SLICE machine's GRID.
  ⚠️ Not yet run on a unit. [features/chain_record.md](features/chain_record.md).

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
