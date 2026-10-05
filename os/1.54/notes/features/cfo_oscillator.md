# CFO oscillator: a 3-oscillator 8-bit wavetable synth with FM

## What this is

A synth voice for the Digitakt's audio tracks: three phase-accumulator oscillators that read 256-entry
8-bit wavetables, OSC1 frequency-modulated by OSC2 and/or OSC3, each oscillator's wave morphing
SIN → TRI → SAW → SQR. It writes into a track's audio buffer at the point where the sampler writes its
samples, so the rest of the track's chain applies to it unchanged:
- the level stage and SRR;
- the filters and their envelope;
- the amp envelope, the mix and the effects.

⚠️ **Not in the reference build.** `os/1.54/build/patch.json` does not contain it. The prototype exists as
test images S9–S11 ([Testing on the unit](#testing-on-the-unit)), which add it to the reference build in
three steps. Its code lives in a new pad, `FUN_400f77da` ([Code space](#code-space)). Nothing of it has
run on a unit yet.

**Prototype stage.** Until the synth is a machine of its own, it plays on any ONESHOT track whose SAMP
is OFF (sample slot 0), and reads its controls from that track's SRC page:

| SRC parameter | Prototype meaning |
|---|---|
| TUNE | pitch, as for a sample (the trig's note and TUNE, through the stock pitch table) |
| PLAY | FM source: 0 OSC2, 1 OSC2+3, 2 OSC3, 3 none |
| BR | oscillator mix, crossfaded: OSC1 → OSC1+2 → OSC1+2+3 → OSC2+3 |
| SAMP | must be OFF |
| STRT | OSC1 wave (morph position) |
| LEN | FM amount |
| LOOP | OSC2 and OSC3 wave |
| LEV | level, as for a sample (with velocity) |

OSC2 sits an octave below OSC1 and OSC3 an octave and a fifth above it. The FM depth scales with
OSC1's pitch, so the timbre stays the same across the keyboard.

## Where a track's samples come from

✅ Read in the code (objdump, decompile of the `dt_1.54_emac` project).

The audio ISR `FUN_40077420` renders 32 samples per tick into eight per-track buffers of 32 longs at
`0x80001a18 + 0x80 × track` (`a18` below). In order:

1. `FUN_40075184` and `FUN_400757fe` (called at `0x40077f8e` and `0x40077fa6`) are a two-stage software
   pipeline over the eight tracks. `FUN_400757fe`'s loop over tracks 0–7 does four things for each:
   - it starts the eDMA fetch of the next track's sample data into a ping-pong buffer;
   - it interpolates this track's data at its pitch, 64 points for 32 output samples (2× oversampled);
   - it decimates with a half-band filter;
   - it writes 32 samples to `a18` with a ramped level (the store loop at `0x40075ff4..0x4007606a`).

   So the lanes are the CPU code that fills `a18`; no other code writes it before the next stage.
2. `FUN_40072478(a18, engine)`, called at **`0x40077fc8`**, works on two tracks at a time. It
   squares and smooths a per-track 16-bit field at `engine + 0x64 + 0x6a × track` (slot 41). Where
   that field is 0, it writes that track's result to a scratch buffer at `0x8000f0b4`, which leaves the
   track's block as the lanes wrote it. ⚠️ Its arithmetic looks like a saturating drive; not
   established.
3. `FUN_4007269c` (SRR), then `FUN_400716c0`, `FUN_40073168`, `FUN_40073304`, `FUN_40072844` (filters,
   envelopes), `FUN_4007269c` again, and the mix `FUN_40071c20`.

`FUN_4007269c` is a sample-and-hold over the 32 samples. The hold length per track comes from a
table at `0x4018f344`, indexed by the high byte at engine block `+0x5a` (slot 36): the sample-rate
reduction. It is not a pitch resampler.

### The values the synth reads

| What | Where | Format |
|---|---|---|
| machine type per track, this tick | `0x4199f466 + track` (the lanes' second argument; 0 ONESHOT, 1 WERP, 2 REPITCH, 3 SLICE) | byte |
| a track's engine block | `0x80002760 + 0x6a × track` (the lanes' first argument) | the sound's 53-slot value array from `+0x12`, 8.8 fixed |
| the SRC page's slots 17–24 | engine block `+0x34`: TUNE, `+0x36` PLAY, `+0x38` BR, `+0x3a` SAMP, `+0x3c` STRT, `+0x3e` LEN, `+0x40` LOOP, `+0x42` LEV | 8.8; the sample slot in the high byte |
| the note | `0x80001f28 + 4 × track` | MIDI note × 65536 |
| the voice level | `0x8000edc4 + 0x5e × track + 0x10`, written by `FUN_400757fe` every tick from `FUN_40074c60(x, LEV)`, `x` from the per-track word array at `0x80001f18` | Q31: the square of LEV × `x`, each scaled so that 127 is 1.0. ⚠️ `x` is very probably the trig velocity |
| the pitch table | `0x4019b4c0`, 14,849 longs | entry `i` = `2^(24 + (i − 512) / 170.67 / 12)`; note 60 → `2^29`, note 72 → `2^30`, saturating at `0x7fffffff` from note 84 |

The stock pitch path, `FUN_40075184`'s loop over the tracks (`0x40075690..0x400757d0`):
1. `note + (TUNE − 0x4000) × 256 + 0x30000`, clamped to `0..0x570000`;
2. × `0x555556` in the EMAC's fractional mode, which divides by 384;
3. that indexes the table.

A sample's own rate factor (`voice + 0x18`) then scales it. That factor puts unity speed at note 60 for
a sample at its own rate, so the table's `2^29` at note 60 is the sampler's reference pitch.

✅ The SRC slot offsets match every field the stock render reads:
- the sample slot at `+0x3a` in the lanes' fetch;
- Slice Select at `+0x3c` and the grid at `+0x40` in the SLICE window `FUN_40074df2`;
- TUNE at `+0x34` in the pitch loop;
- LEV at `+0x42` in the level.

## How the code does it

Source: [src/cfo_oscillator/](../../src/cfo_oscillator/). It has four parts:
- `cfo.s`, the code;
- `make_waves.py`, the tables;
- `make_cfo.py`, which assembles and checks the code and builds the stage images;
- a linker script per placement.

**The hook.** At `0x40077fc8` the ISR calls `FUN_40072478` with `(a18, engine)` on the stack. The hook
replaces that `jsr` (6 B) with `jsr cfo_pad`. `cfo_pad` renders the synth tracks into their `a18`
blocks, restores every register it used, and jumps to `FUN_40072478` with the stack unchanged. That
call returns to the ISR.

**Per synth track, per tick** (the routines `track`, `pitch`, `wave`, `gains`, `osc32`, `osc1_mix`):

1. OSC1's note sum is computed as stock does: `note + (TUNE − 0x4000) × 256 + 0x30000`. OSC2 uses it
   − 12 semitones and OSC3 + 19 semitones.
2. For each oscillator: clamp, divide by 384, read the stock pitch table, then step =
   `(entry >> 13) × 357`. That is the phase step of a 32-bit accumulator at 48 kHz: note 60 gives
   23,396,352, 261.47 Hz (C4 is 261.63 Hz, so −1 cent).
3. A wave value `w` (0–127) gives `p = 3w`. The table pair is `p >> 7` and the next one, and the
   blend is `(p & 127) × 2 / 256`. Per sample: `A[i] + ((B[i] − A[i]) × blend >> 8)`, with
   `i = phase >> 24`. There is no interpolation between neighbouring entries, which keeps the raw
   8-bit sound.
4. OSC2 and OSC3 render 32 samples each into a word buffer on the stack.
5. OSC1: phase += step + FM, where:
   - FM = (OSC2 and/or OSC3, by PLAY) × `(step >> 13) × LEN`;
   - the mix is OSC1 × G1 + OSC2 × G2 + OSC3 × G3, the three gains summing to 256 and crossfaded by BR
     over the points (256, 0, 0), (128, 128, 0), (85, 85, 86), (0, 128, 128);
   - the result is × (voice level >> 16) into `a18`.

   Full scale is ±32,512 × 32,767, just under 2^30: a single oscillator at full level sits 6 dB below
   a full-scale sample.
6. The three phases persist per track in this build's RAM at `0x439d1100` (96 B,
   [memory_map.md](../memory_map.md)). Their start-up contents do not matter.

**The wavetables** (`make_waves.py`): SIN, TRI, SAW, SQR, 256 signed bytes each, with one phase
convention, so the morph blends shapes instead of cancelling them:
- every table starts with a zero crossing at index 0;
- SIN and TRI peak at 64 and bottom out at 192;
- SAW rises from 0 to 127, jumps to −128 at 128 and rises back to −1;
- SQR is +127 then −127.

The tables (1,024 B) and the mix points (24 B) go into the `.rodata` padding at
`0x40252724..0x40252b3c`, below the icons ([landing_pads.md](../landing_pads.md#rodata-the-constant-data-budget)).

## Checked in the emulator

`EmuCfoOscillator` ([scripts/emu/README.md](../../scripts/emu/README.md)) runs the assembled code in
Ghidra's p-code emulator. It runs over 3 to 200 consecutive ticks and compares every output sample with
a model of the same integer arithmetic. The model is written in the harness and reads the stock pitch
table and the wavetables from emulator memory. All cases pass:
- tracks that are not synth tracks keep their buffers exactly;
- OSC1 alone; TUNE ±; the three morphs; the four mix points;
- FM from OSC2, OSC2+3 and OSC3; half and zero level; notes 0 and 127;
- the phases carry over from tick to tick;
- `FUN_40072478` is reached with the stack as found and `%d2-%d7/%a2-%a6` intact.

A pure SIN at note 60 measures 261.47 Hz with a peak of 1,065,320,704. The worst tick, all eight tracks
on the synth, takes 15,623 instructions. ⚠️ How that compares with the ISR's free time per 667 µs tick
is not measured. The control: a variant with the pitch table one entry off and OSC3's mix gain swapped
fails 17 of the 19 cases.

## Code space

The code is 626 B in seven routines: dispatcher 82, `track` 268, `pitch` 56, `wave` 32, `gains` 58,
`osc32` 38, `osc1_mix` 92. The pads in use have 112 B free, in blocks of at most 18 B
([landing_pads.md](../landing_pads.md)). The code goes into `FUN_400f77da`, route 2 below, under the
vetting recipe's exception for an identified library function
([landing_pad_method.md](../../../../notes/landing_pad_method.md#vetting-a-new-pad)); it uses 626 B of
the pad's 2,372. The routes:

1. **Small leaf pads.** Eight candidates are true leaves (no indirect calls), not in the I/O region, the
   audio code or the second code window:

   | Candidate | Size |
   |---|---:|
   | `0x400ee05e` | 122 B |
   | `0x400e6d1c` | 108 B |
   | `0x400e8544` | 86 B |
   | `0x40178f20` | 86 B |
   | `0x40178e02` | 66 B |
   | `0x400d266e` | 60 B |
   | `0x400c2242` | 54 B |
   | `0x401778a4` | 52 B |

   Together that is 634 B, and the largest is 122 B. The code would have to be split into pieces of
   that size, with 6 B absolute calls between them. Each of the eight pads would need its own vetting
   and fill test.
2. **One library function.** `FUN_400f77da` (2,372 B) is LZ4's streaming compressor
   (`LZ4_compress_fast_continue`): its 0x7E000000 input limit and the stream-state offsets
   `0x4000`/`0x4008`/`0x4010` are LZ4's. It passes vetting steps 2–8 in this image
   ([landing_pads.md](../landing_pads.md#the-candidate-list-on-this-image)), but it calls two LZ4 helpers
   and `memcpy`, so it fails step 1, which admits only leaves. The placement
   [src/cfo_oscillator/lz4_stream.ld](../../src/cfo_oscillator/lz4_stream.ld) puts all the code there.
   `make_cfo.py` with that script assembles and checks it, and the harness passes at that address.

## Testing on the unit

`make_cfo.py lz4_stream.ld --stages` builds three images on top of the reference build (section 3
`efc90606…`, `.syx` `3fd4b0a3`), each adding one step:

| Stage | `.syx` | Section 3 | Contents |
|---|---|---|---|
| S9 | `9a75ccc7` | `af736dd4…` | the reference build + `FUN_400f77da` filled with `clrl %d0 ; rts` (the pad's fill test) |
| S10 | `3af01e48` | `7328128f…` | S9 + the hook at `0x40077fc8`, its pad only jumping on to `FUN_40072478` |
| S11 | `0e688a4d` | `ff134c73…` | S9 + the CFO oscillator prototype |

Checked on the built images:
- the build's own checks;
- the compressor window, whose largest back-reference is `0xffc6a`;
- the hook, code and data read back out of S11 equal the assembled sections;
- `EmuCfoOscillator` passes on S11's own bytes, and on S10's bytes (the inert control) it fails every
  synth case and passes the untouched ones;
- every other harness passes on S11.

What to check:
- **S9:** nothing changes anywhere. A live caller of the pad would now get 0 at once.
- **S10:** nothing changes. Every audio tick now passes through the hook.
- **S11:** a ONESHOT track with SAMP OFF plays the synth: an 8-bit sine at the trig's note with STRT, LEN,
  LOOP, BR and PLAY at 0. Listen for:
  - whether a trig on such a track sounds at all (the amp envelope opening without a sample);
  - the pitch against a sample at the same note;
  - the morph on STRT and LOOP, FM on LEN, the mix on BR, the FM source on PLAY;
  - the filter, the amp envelope and the effects acting on it.

  Also check that the other tracks and the rest of the firmware are unchanged, and that eight synth
  tracks at once neither click nor stall.

## Towards a machine of its own

The prototype's trigger (ONESHOT with SAMP OFF) and its borrowed controls stand in for a machine
index 5 ("CFO"). The sites a sixth machine extends are those this build already changes for POLY,
machine 4 ([docs/patch_listing.md](../../docs/patch_listing.md#poly-engine)):
- the machine-list builder;
- the per-sound deserializer's bound at `0x4007a2d0`;
- the MACHINE menu's navigation;
- the machine-name table and the picker icons;
- the machine-type query at `0x4002b68e`.

In the render, a machine-5 track takes the lanes' "no window" branch (silent), and `cfo_pad` would test
for machine 5 instead of ONESHOT with SAMP OFF. ⚠️ Not traced in this image: how the SRC page's layout
and parameter descriptors are chosen per machine, and so what a machine-5 track's SRC page would show.

## Related notes

- [landing_pads.md](../landing_pads.md): the pads and the candidates.
- [memory_map.md](../memory_map.md): the RAM.
- [function_ledger.md](../function_ledger.md): the render functions.
- [scripts/emu/README.md](../../scripts/emu/README.md): `EmuCfoOscillator`.
