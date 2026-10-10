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
7. the Chain Recording build (`.syx` `3fd4b0a3…`), which changes those two and adds auto re-arm,
   after its two stages (the new pad filled, `.syx` `4aae845e…`; every hook replaying stock code,
   `.syx` `ad7c9a72…`): the three images started and ran, and what was checked on them worked,
   reported as a whole. ⚠️ One
   display glitch is open: after the screen that asks whether to apply the new sample to a track, the
   top and bottom of the screen stay black
   ([features/chain_record.md](features/chain_record.md#open-the-screen-after-saving)).

Then the CFO oscillator and portamento, each stage flashed on the one before:

8. the CFO oscillator's stages S9 to S27 on that build
   ([features/cfo_oscillator.md](features/cfo_oscillator.md#testing-on-the-unit)): S9 to S25 worked as
   described, as reported, with the faults of S16 to S18 explained by the code and fixed in S19. S26
   showed a POLY track without its source track's machine; S27 changes that, and S27's own change was
   not reported on its own. S27's code ran in every portamento stage after it;
9. portamento's stages on S27 ([features/portamento.md](features/portamento.md#on-the-unit)): S31 to
   S33 and S36 worked as described, as reported; the legato tests of S34 and S35 failed and were
   replaced. S36 (`.syx` `9df62a0b…`) is the build v0.2.1, byte for byte.

Then FILTER page 2, each stage flashed on the one before:

10. FILTER page 2's stages S37 to S52 on S36
    ([features/filter_page2.md](features/filter_page2.md#on-the-unit)): each flashed in turn and
    checked on its own, and they work, as reported; S39 showed VED and KEY without pictures, which S40
    changes. Not yet tested against every other feature. S52 (`.syx` `701a6c28…`) is the build in this
    folder, v0.2.2, byte for byte.

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
- **CFO oscillator.** CFOO, machine 5: a 3-oscillator 8-bit FM synth, written into a track's audio
  buffer where the sampler's samples go, with its own SRC page (knobs, ranges, value names, [FUNC] +
  knob steps), picker icon, LFO destination names and track texts. ✅ Run on the test unit (S9 to
  S27, above). [features/cfo_oscillator.md](features/cfo_oscillator.md).
- **Portamento.** PORT and LEG on every audio track's TRIG page: the note glides to each new note, or
  with LEG on only to a legato one (its trig before the last note's LEN ends), for samples and the
  CFO oscillator; with LEG on a legato note does not restart the amp envelope; both are saved with the
  sound and their locks with the pattern. ✅ On the test unit: the glide, LEG and a lock on PORT
  (S31), PORT's OFF and LEG's cell (S32), saving and recall (S33), legato by LEN with the amp
  envelope held (S36). [features/portamento.md](features/portamento.md).
- **FILTER page 2: VED and KEY.** On every audio track's second FILTER page, VED (knob C, 0..100 %) sets
  how much the velocity decides the filter envelope's depth, around velocity 100, and KEY (knob G,
  −394..394 % in 6.25 % steps) moves the cutoff with the note; both are saved with the sound and their
  locks with the pattern; knob B is kept for an envelope destination. ✅ On the test unit, each stage
  checked on its own (S37 to S52, above). [features/filter_page2.md](features/filter_page2.md).

The CFO oscillator and portamento came into `patch.json` together, from their last stages:
`make_port.py --stages --write` (in [src/portamento/](../src/portamento/)) rebuilt the build without
them from `patch.json`, the CFO oscillator's S27 on it and portamento's S36 on that, and wrote the
runs: v0.2.1 is S36, byte for byte. FILTER page 2 came in after them, as v0.2.2:
`make_filt.py --stages --write` (in [src/filter_page2/](../src/filter_page2/)) rebuilds the build before
it from `patch.json` (giving back the bytes it rewrites), S52 on it, and writes the runs. The build is
S52, byte for byte. Each changed byte is listed once, under the last feature that wrote it
([docs/reference.md](../docs/reference.md#what-patchjson-holds)); `make_port.py` and `make_chain.py`
refuse `--write` once a later feature is merged.

The other features have run on the test unit ([The test unit](#the-test-unit)).

Where each feature's code sits in this image: [function_ledger.md](function_ledger.md) and
[docs/patch_listing.md](../docs/patch_listing.md).

✅ Read in the code by emulation: the ten harnesses in
[scripts/emu/README.md](../scripts/emu/README.md) pass on the reference build. They step the pad code
of pool cursors, the MIDI Loopback private lane and display pads, the SLICE latch, the three POLY
track aliases and the machine-list edit, run the recorder engine with Chain Recording's hooks, run the
CFO oscillator against a model of its arithmetic and its SRC page's code, and run portamento's hooks at
their sites in the audio ISR, in Ghidra's p-code emulator, with stubbed callees.

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
- [features/cfo_oscillator.md](features/cfo_oscillator.md): the CFO oscillator, and where a track's
  samples come from.
- [features/chain_record.md](features/chain_record.md): Chain Recording, and how the stock recorder
  works.
- [features/filter_page2.md](features/filter_page2.md): FILTER page 2 (VED, KEY), and how the filter
  stage forms its cutoff.
- [features/portamento.md](features/portamento.md): portamento and legato, and how a sequenced trig's
  LEN ends a note.
- [function_ledger.md](function_ledger.md): the per-function ledger.
- [landing_pads.md](landing_pads.md): the pads that hold the new code, why each is dead here, and the
  `.rodata` space.
- [memory_map.md](memory_map.md): DDR, the RAM this build uses, and the SRAM.
- [startup_hooks.md](startup_hooks.md): the build's code that runs at startup, and what checked it.
- [stock_image.md](stock_image.md): every measured figure of the stock image, section 2's run base
  and section 8.
- [update_moat.md](update_moat.md): the protected set, how it was fixed, and the routes it does not
  cover.
