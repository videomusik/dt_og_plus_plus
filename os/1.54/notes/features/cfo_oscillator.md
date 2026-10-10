# CFO oscillator: a 3-oscillator 8-bit FM synth

## What this is

A synth voice for the Digitakt's audio tracks: three phase-accumulator oscillators that read 256-entry
8-bit waveform tables, OSC1 frequency-modulated by OSC2 and/or OSC3, each oscillator's wave morphing
SIN → TRI → SAW → SQR. It writes into a track's audio buffer at the point where the sampler writes its
samples, so the rest of the track's chain applies to it unchanged:
- the level stage and SRR;
- the filters and their envelope;
- the amp envelope, the mix and the effects.

**In the build.** `os/1.54/build/patch.json` holds it as the feature `cfo_oscillator`: its last stage,
S27, less Chain Recording's build that the stages start from ([Testing on the unit](#testing-on-the-unit)).
It came into `patch.json` together with portamento, which points the synth's note read at the glided
note ([portamento.md](portamento.md)); `make_port.py` writes both. The build's bytes that CFOO rewrites
(the machine-5 bounds, the name and icon pointers, the label pads' jumps) are listed under
`cfo_oscillator`. Its code lives in a new pad, `FUN_400f77da` ([Code space](#code-space)). The stages
ran on the test unit ([Testing on the unit](#testing-on-the-unit)).

The sections below follow the stages. S11 was a prototype, and CFOO, machine 5, starts in S12
([below](#cfoo-machine-5-in-s12)); the build plays the synth on CFOO tracks only.

**The prototype, S11.** Before the synth became a machine of its own, it played on any ONESHOT track
whose SAMP was OFF (sample slot 0), and read its controls from that track's SRC page:

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

**A trig in the ISR.** The ISR acts on each trig event by its flags (decompile of `FUN_40077420`, read
around the calls of the lanes and of `FUN_40073304`):
- flag `0x80`: the track's level input `x` at `0x80001f18 + 2 × track` is set from the event (in one
  branch with an offset added and clamped to `0..0x7f00`; ⚠️ very probably the velocity), and the
  track's bit in the lanes' trig mask;
- flag `0x200`: the track's bit in the mask `FUN_40073304`, the envelope generator, restarts on;
- flag `0x10000`: the note at `0x80001f28 + 4 × track`.

Nothing there tests the track's sample or machine. ⚠️ Whether the code that builds a trig event sets
these flags for a track without a sample, or with machine 5, is not traced. That is what S11's first
listening check finds out.

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
| the voice level | `0x8000edc4 + 0x5e × track + 0x10`, set from `FUN_40074c60(x, LEV)` at a trig: the track's bit in the lanes' trig mask, which `FUN_40075184` keeps one tick at `0x80001228`. Track 0 is set by `FUN_40075184` (`0x4007521a`), tracks 1–7 by `FUN_400757fe` (`0x4007595c`), both before they branch on the machine, so a machine-5 track gets its level too. `x` comes from the per-track word array at `0x80001f18` | Q31: the square of LEV × `x`, each scaled so that 127 is 1.0. ⚠️ `x` is very probably the trig velocity; whether anything else writes the level between trigs is not checked |
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
   - the result is × (level >> 16) into `a18`.

   Full scale is ±32,512 × 32,767, just under 2^30: a single oscillator at full level sits 6 dB below
   a full-scale sample.
6. The three phases persist per track in this build's RAM at `0x439d1100` (96 B,
   [memory_map.md](../memory_map.md)). Their start-up contents do not matter.
7. The level is computed, not read from the voice ([below](#the-level)).

### The level

The code calls the stock `FUN_40074c60(x, LEV)` itself, with the track's word at `0x80001f18` and its
LEV, and applies the lanes' de-click: level 0 when the voice is on (`+0x28`) and the track's bit is set
in the lanes' next-tick trig mask `0x8000122c`. That is what `FUN_400757fe` computes for a track every
tick (`0x40076114..0x4007615c`, objdump), less one rule. With voice flag `+0x29` set and the play mode
not a loop, the lanes store a quarter of the previous level instead, so a voice fades out within a few
ticks. `+0x29` is set by the lanes' end-of-window test (`0x40075bbe..0x40075c30`), and "a loop" is
PLAY 1 or 2: the lanes set their direction flag `+0x2a` for PLAY 0 and 1 and test PLAY − 1 < 2 for the
loop, which makes the order FWD, FWD loop, REV loop, REV.

A synth track has no sample, and a machine-5 track takes the lanes' empty window, so the voice may count
as ended at once. ⚠️ Whether it does was not traced. CFOO uses the PLAY slot as FMSR, so reading the
voice's level would have tied the synth's loudness to the FM source: values 0 and 3 could have faded to
silence. Computing the level avoids the question. `FUN_40074c60` uses the EMAC in fractional mode,
which laneA sets and leaves set (`%macsr` `0x20` from `0x400757ee`), and nothing changes it before the
hook. It clobbers only `%d0`, `%d1` and `%acc0`.

Like the lanes' store loop, the synth ramps the level across a tick's 32 samples, from the level the
track ended its last tick on to the new one, in steps of 1/32 of the difference (5 fraction bits), so it
ends exactly on the new level. Without the ramp, the de-click would cut a sounding voice to silence
within one sample. The last level per track is kept in this build's RAM at `0x439d1160`
([memory_map.md](../memory_map.md)). A stored value above `0x7fff` cannot be a level: it is what the
RAM held at boot, and the track then starts at the new level with no ramp.

**The waveform tables** (`make_waves.py`): SIN, TRI, SAW, SQR, 256 signed bytes each, with one phase
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
table and the waveform tables from emulator memory. All cases pass:
- tracks that are not synth tracks keep their buffers exactly;
- OSC1 alone; TUNE ±; the three morphs; the four mix points;
- FM from OSC2, OSC2+3 and OSC3; half and zero level; notes 0 and 127;
- the level: each synth track calls `FUN_40074c60` once, with its own `x` and its LEV, and the voice's
  own level, set to a wrong value throughout, is not used; the de-click gives level 0 only to a voice
  that is on and has a trig next tick; the level ramps across each tick from the last one, through a
  run of level changes, and a start-up value in the level RAM gives no ramp;
- the phases carry over from tick to tick;
- `FUN_40072478` is reached with the stack as found and `%d2-%d7/%a2-%a6` intact.

The emulator's EMAC has no fractional mode ([notes/emulator.md](../../../../notes/emulator.md)), so the
harness stubs `FUN_40074c60`: it checks the arguments and returns the case's level.

A pure SIN at note 60 measures 261.47 Hz with a peak of 1,065,320,704. The worst tick, all eight tracks
on the synth, takes 16,836 instructions, plus about 17 per track inside the stubbed `FUN_40074c60`.
⚠️ How that compares with the ISR's free time per 667 µs tick is not measured.

Controls:
- a variant with the pitch table one entry off and OSC3's mix gain swapped fails every case except the
  two with no synth track and level 0;
- code that reads the voice's level makes no level call and fails every synth case;
- code that holds each tick's level without the ramp fails every synth case except level 0 and the
  start-up case, where the two agree.

## Code space

The code is 722 B in seven routines: dispatcher 82, `track` 352, `pitch` 56, `wave` 32, `gains` 58,
`osc32` 38, `osc1_mix` 104. The pads in use have 112 B free, in blocks of at most 18 B
([landing_pads.md](../landing_pads.md)). The code goes into `FUN_400f77da`, route 2 below, under the
vetting recipe's exception for an identified library function
([landing_pad_method.md](../../../../notes/landing_pad_method.md#vetting-a-new-pad)); it uses 722 B of
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

`make_cfo.py lz4_stream.ld --stages` builds nineteen images on top of Chain Recording's build (section 3
`efc90606…`, `.syx` `3fd4b0a3`), each adding one step to the stage named in its row. They are meant to
be flashed in order, S9 to S27. That build comes back from `patch.json` by `build_without_cfo`: the
features before `cfo_oscillator`, with the bytes CFOO rewrites given back their earlier values; it
must hash to `efc90606…`, and every stage rebuilds byte for byte from the merged `patch.json`.

| Stage | `.syx` | Section 3 | Contents |
|---|---|---|---|
| S9 | `9a75ccc7` | `af736dd4…` | the reference build + `FUN_400f77da` filled with `clrl %d0 ; rts` (the pad's fill test) |
| S10 | `3af01e48` | `7328128f…` | S9 + the hook at `0x40077fc8`, its pad only jumping on to `FUN_40072478` |
| S11 | `f6f7dd0d` | `b77f2c24…` | S9 + the CFO oscillator prototype, playing on ONESHOT with SAMP OFF |
| S12 | `3d258e52` | `a1637da2…` | S9 + the machine CFOO (machine 5, after POLY) with ONESHOT's SRC page, and the synth playing on it ([below](#cfoo-machine-5-in-s12)) |
| S13 | `b29c5ea7` | `49c9b6c2…` | S12 + CFOO's own parameter names on its SRC page ([below](#cfoos-own-parameter-names-in-s13)) |
| S14 | `2d8fc5fa` | `49f7fd62…` | S13 + a placeholder picker icon for CFOO ([below](#cfoos-picker-icon-in-s14)) |
| S15 | `7a8d8397` | `fafd7963…` | S14 + a machine change to CFOO reaches the engine at once ([below](#a-machine-change-to-cfoo-in-s15)) |
| S16 | `60f12e59` | `d6cc95c2…` | S15 + CFOO's own knobs: map, ranges, defaults, detune, names ([below](#cfoos-own-knobs-in-s16)) |
| S17 | `6f42dd47` | `b7ac2008…` | S16 + CFOO's own value displays: the knob pictures, the cell's text and the encoder popup ([below](#cfoos-own-value-displays-in-s17)) |
| S18 | `78cda1e3` | `97f6f22a…` | S17 + CFOO's knobs as agreed after S17: whole steps, three FM sources, ±24 semitone detunes, no sample picker, pitches above the stock table's top ([below](#cfoos-knobs-as-agreed-in-s18)) |
| S19 | `a069de54` | `3351306e…` | S18 + the ranges, pictures and cell texts find the machine through the set's sound holder; the validity test, the reset to default, MIDI CC and the all-tracks edit take CFOO's ranges ([below](#the-sound-behind-a-parameter-set-in-s19)) |
| S20 | `ee3c06ab` | `208a4972…` | S19 + the pure waves and the four mixes exactly at 0, 42, 85 and 127; the tables move to the .rodata padding ([below](#pure-points-in-s20)) |
| S21 | `23ad0daf` | `a87695d0…` | S20 + [FUNC] + knob steps between those points, and between nine detunes ([below](#func--knob-in-s21)) |
| S22 | `0e302722` | `3fd1a030…` | S21 + the [TRK] popup shows POLY and CFOO without a sample name ([below](#the-trk-popup-in-s22)) |
| S23 | `3d72e125` | `5c3ebf10…` | S22 + CFOO's names as LFO destinations ([below](#cfoo-as-an-lfo-destination-in-s23)) |
| S24 | `42f51b39` | `d4b5a241…` | S23 + the waves and the mix read SIN, TRI, SAW, SQR and OSC1, 1+2, 123, 2+3 at their four points ([below](#names-at-the-four-points-in-s24)) |
| S25 | `62765b5a` | `ac131e81…` | S24 + the SRC page's title and popup show POLY and CFOO without a sample name ([below](#the-src-pages-own-texts-in-s25)) |
| S26 | `346177af` | `fad9ee37…` | S25 + the [TRK] popup and the SRC page show a POLY track as POLY and its Source's machine ([below](#a-poly-tracks-source-in-s26)) |
| S27 | `3f81282f` | `703cedf7…` | S26 + the Source found from the machines, not POLY's pool map ([below](#the-source-from-the-machines-in-s27)) |

Checked on the built images (S12's own checks are [below](#cfoo-machine-5-in-s12)):
- the build's own checks;
- the compressor window, whose largest back-reference is `0xffc6a`;
- the hook, code and data read back out of S11 equal the assembled sections;
- `EmuCfoOscillator` passes on S11's own bytes, and on S10's bytes (the inert control) it fails every
  synth case and passes the untouched ones;
- every other harness passes on S11;
- S9 and S10 rebuild byte for byte whenever the synth code changes, since neither contains it.

Results on the unit, OS 1.54, each stage flashed on the one before from S8:
- ✅ **S9, S10:** ran; nothing found different.
- ✅ **S11:** a ONESHOT track with SAMP OFF plays the synth: a sine at the trig's note. With an infinite
  amp decay a note keeps sounding for minutes at every PLAY value, with no fade or click. With LEN up,
  PLAY changes the FM source as described, and PLAY 3 gives the plain sine. In a full pattern (two SLICE
  tracks playing recorded chains, the synth on T3 with T4–T6 as POLY voices playing four-note chords from
  a MIDI Loopback track, a third recorded chain and another POLY track), sweeping the synth's parameters gave
  no stutter and no audible dropped notes, and all four notes of each chord sound like the synth.
- ✅ **S12, S13, S14:** work as described under "What to check" below. One fault: right after CFOO is
  assigned to a track, the track stays silent, both for trigs and for incoming MIDI, until SAMP is moved;
  then it plays. ⚠️ Not traced. A likely cause: the per-tick machine byte comes from the voice's copy of
  the sound's machine (`0x800018bc`), which is refreshed only when a sound is applied to the voice
  (`FUN_40077282`), and moving SAMP very probably applies it. The cause is in the code and S15 fixes it
  ([below](#a-machine-change-to-cfoo-in-s15)).
- ✅ **S15:** on an empty pattern, track 1 set to CFOO and a trig key pressed without touching SAMP: it
  sounds, the sine.
- ⚠️ **S16:** the controls work, but the ranges seen on the unit are not those of the knob table. A screen
  dump of a CFOO track's SRC page shows the eight labels (WAV1 FMSR WAV2 WAV3 / MIX FM DET2 DET3) and
  CFOO's defaults, each value drawn as ONESHOT's parameter in that slot draws it: knobs for A, C, E, F
  and G, PLAY's picture for B, SAMP's box for D and LEV's fader for H. G's unison (48) sits at 40 % of
  LOOP's 0–120 sweep. That is what S16 leaves to ONESHOT ([below](#cfoos-own-knobs-in-s16)); S17 gives
  CFOO its own displays. ✅ The ranges are explained by the code (S19,
  [below](#the-sound-behind-a-parameter-set-in-s19)): `cfo_range`'s machine test failed at every edit
  site, so the knobs kept ONESHOT's ranges; only the machine-change reset, which passes a sound holder,
  gave CFOO's record, hence CFOO's defaults.
- ⚠️ **S17:** as reported. FMSR works. WAV2 steps through every number. A (WAV1) skips 2, 5, 8, 11,
  14, 17 and on: the 85 steps of TUNE's 4–88, shown as (A − 4) × 1.5. D (WAV3) looks right, but turning
  it opens the sample picker, while the waveform changes underneath. H (DET3) still looks like LEV's
  fader, though it controls the detune; at the bottom it shows the right values and the popup the right
  labels, and above a certain amount every tone sounds the same. The detunes step in fine fractions.
  "Every tone the same" fits the stock pitch table, which saturates at note 84: OSC3 above about 1 kHz
  stays at about 1 kHz; S18 takes it up. ✅ H's picture is explained by the code (S19,
  [below](#the-sound-behind-a-parameter-set-in-s19)): the picture and text hooks' machine test depends
  on the cell's row, not on the machine, and failed for the bottom row. A's skips are S17's text,
  (A − 4) × 1.5, over the stored range 4–88 that the failing range test left in place.
- ⚠️ **S18:** as reported, with a screen recording of a CFOO track's SRC page. B (FMSR) works. The
  popups show CFOO's names and values for every knob, the G and H popups `-24.00` … `+24.00`. The
  stored values keep ONESHOT's ranges: A 4–88, F 0–120 (LEN's), G 0–120 (LOOP's, `OFF` at 0) and H
  0–127 (LEV's), and the popups' detunes stop at ±24 while the value goes on. Under the bottom row's
  knobs the value shows as ONESHOT shows it (E `44.00`, F `47.39`, G `OFF`, `0.06` … `120.00`, H `0.00`
  … `127.00`), G with LOOP's knob and H with LEV's fader; the top row shows CFOO's (A `88`, B `OSC3`).
  A ONESHOT track shows CFOO's pictures and labels. ✅ The ranges, the pictures and the values under
  the knobs are the machine test above (S19), which can pass on a ONESHOT track as well. ⚠️ The names
  under the knobs come from S13's hook, which asks the page for its machine (`FUN_4002b5d4`); if a
  ONESHOT track's names change too, that is not explained.
- ✅ **S19:** works, as reported (no details).
- ✅ **S20 to S23:** work, as reported (no details).
- ⚠️ **S26:** as reported: a POLY track reads `POLY` with [TRK] and with [SRC], but without its
  Source's machine. ✅ Explained by the code (S27, [below](#the-source-from-the-machines-in-s27)): the
  pool map S26 reads can be behind the machines the pages show.
- ✅ **S24, S25:** do as intended, as reported. Seen with S25: [TRK] + a track key shows a POLY track as
  POLY and a CFOO track as CFOO, but the SRC page shows a POLY track's Source's machine instead (CFOO
  alone, or the machine and its sample for SLICE and the others): its machine comes through POLY's
  alias, `FUN_4002b5d4`. S26 makes the three texts agree.
- ⚠️ **S27:** its own change, the POLY track's text after a machine change, was not reported on its
  own. ✅ S27's code ran on the unit in every portamento stage built on it, and a CFOO track plays and
  glides there ([portamento.md](portamento.md#on-the-unit)).

What to check:
- **S9:** nothing changes anywhere. A live caller of the pad would now get 0 at once.
- **S10:** nothing changes. Every audio tick now passes through the hook.
- **S11:** a ONESHOT track with SAMP OFF plays the synth: an 8-bit sine at the trig's note with STRT, LEN,
  LOOP, BR and PLAY at 0. Listen for:
  - whether a trig on such a track sounds at all (the amp envelope opening without a sample);
  - whether a long note holds for as long as the amp envelope does, at every PLAY value. The synth
    computes its own level ([above](#the-level)), so PLAY should change only the FM source;
  - the pitch against a sample at the same note;
  - the morph on STRT and LOOP, FM on LEN, the mix on BR, the FM source on PLAY;
  - the filter, the amp envelope and the effects acting on it.

  Also check that the other tracks and the rest of the firmware are unchanged, and that eight synth
  tracks at once neither click nor stall.
- **S12:** the MACHINE menu ([FUNC] + [SRC]) lists CFOO after POLY, with no icon (see below). Assigning
  it to a track:
  - shows CFO OSCILLATOR in the confirmation;
  - gives the SRC page ONESHOT's eight parameters with ONESHOT's ranges;
  - makes the track play the synth with the same controls as S11. SAMP no longer matters.

  Then save the project, reload it, and check that the track is still CFOO. A ONESHOT track with SAMP
  OFF is silent again, as stock.

  Polyphony: set the track after the CFOO track to POLY and play overlapping notes on the CFOO track
  (or chords from a keyboard). Each note should take its own voice and every voice should be the synth,
  following the CFOO track's knobs ([above](#cfoo-machine-5-in-s12)).
- **S13:** a CFOO track's SRC page labels read TUNE, FMSR, MIX, SAMP, WAV1, FM, WAV2 and LEV, and the
  encoder popups read Tune, FM Source, Osc Mix, Sample Slot, OSC1 Wave, FM Amount, OSC2+3 Wave and Level.
  A ONESHOT track's page is unchanged, and so is a MIDI track's CHAN/TRK label. The values themselves
  still show as ONESHOT shows them, for example PLAY's play-mode pictures on FMSR.
- **S16:** a CFOO track's SRC page reads WAV1 FMSR WAV2 WAV3 MIX FM DET2 DET3. A fresh CFOO track is
  a plain sine (OSC1 SIN alone, no FM, both detunes at unison). Each knob stops at its own range (B at
  3, G and H at 98, E and F at 120). The detunes follow the table below: G down in semitones and up in
  cents, H down in cents and up in semitones. LEV is gone: the level follows velocity, and the AMP page
  sets the volume. The values still show as ONESHOT's numbers or pictures.
- **S18:** on a CFOO track:
  - A, C, D, E, F step in whole numbers from 0 to 127, every number shown, at the speed of BR;
  - B steps OSC2, 2+3, OSC3, at the speed of PLAY;
  - G and H step in fine steps, at the speed of TUNE, from -24.00 to +24.00 semitones, a hundredth a
    cent, with 0.00 (unison) in the middle and the knob at the top;
  - turning D changes OSC3's wave without opening the sample picker;
  - high notes and upward detunes keep rising above about 1 kHz (note 84) instead of sticking there;
  - all eight cells are plain knobs. ⚠️ If H still looks like LEV's fader, a screen dump of it would
    show what draws it.

  A ONESHOT track's SRC page, its sample picker on SAMP and its knobs behave as before.
- **S19:** on a CFOO track, everything listed for S18, and:
  - all eight cells are plain knobs, H included, each turning over its whole range;
  - the value under each knob, where the page shows one, is CFOO's (`127`, `OSC3`, `-12.50`), never
    ONESHOT's (`44.00`, `OFF`, `0.06`);
  - A, C, D, E, F reach both 0 and 127; G and H stop at −24.00 and +24.00, the stored value with them;
  - values left outside these ranges by S18 come back in on the first turn.

  On a ONESHOT track (and SLICE, POLY): TUNE, PLAY, BR, SAMP, STRT, LEN, LOOP and LEV with their own
  names, pictures, values and ranges, as stock, wherever the pattern's values sit. If a ONESHOT track
  still shows any of CFOO's names, a screen dump of it would show where.
- **S20:** on a CFOO track, a wave knob (A, C, D) at 0, 42, 85 and 127 plays a pure SIN, TRI, SAW and
  SQR, and the mix (E) at the same four values plays OSC1 alone, 1+2, 1+2+3 and 2+3. In between the
  morph is as before, its corners moved by under one step. CFOO's names, the machine list and every
  page read as in S19 (the tables now sit elsewhere).
- **S21:** on a CFOO track, hold [FUNC] and turn a knob:
  - A, C, D and E step to the next of 0, 42, 85, 127 in the turn's direction, and stay at the end;
  - G and H step through −24, −17, −12, −5, 0, +7, +12, +19, +24 semitones;
  - B and F do what [FUNC] did for them before.

  Without [FUNC] every knob turns as in S20. On a ONESHOT track [FUNC] + knob behaves as stock.
- **S22:** [TRK] + a track key: a POLY or CFOO track shows only POLY or CFOO; a ONESHOT, WERP, REPITCH
  or SLICE track still shows its machine and sample name.
- **S23:** on a CFOO track's LFO page, DEST: the knob's picture shows CFOO over the destination's short
  name (WAV1 … DET3), and the destination list reads `CFOO:OSC1 Wave` … `CFOO:OSC3 Detune` (or the
  short names where a long one is too wide). The FILTER, AMP and other destinations, and every other
  machine's list, are unchanged.
- **S24:** on a CFOO track, WAV1, WAV2 and WAV3 read `SIN`, `TRI`, `SAW`, `SQR` at 0, 42, 85, 127 (under
  the knob and in the popup, e.g. `OSC1 Wave=TRI`), and MIX `OSC1`, `1+2`, `123`, `2+3`; every other
  value is the number, FMSR, FM and the detunes as in S23.
- **S25:** the SRC page of a POLY or CFOO track shows no sample name where it showed one (its title, and
  the popup that shows the machine and the sample); ONESHOT, WERP, REPITCH and SLICE as before.
- **S26:** a POLY track reads `POLY: SAMP`, `POLY: SLIC`, `POLY: CFOO` and so on (its Source's machine,
  no sample), the same with [TRK] + a track key and on its SRC page; a POLY track with no Source before
  it reads `POLY`; a CFOO track `CFOO`; every other track its machine and sample as before. Everything
  else as S25 (this stage also shortens the code's internal calls).
- **S27:** as S26 asks: a POLY track reads `POLY: ` and its Source's machine (the nearest track before
  it that is not POLY) with [TRK] and on its SRC page, right after a machine change too; a POLY track
  with only POLY tracks (or none) before it reads `POLY`.
- **S17:** on a CFOO track all eight cells are plain knobs, each turning over its own range: A, C, D over
  the waves, B in four steps, E and F to 120, G and H to 98 with unison at the top. The encoder popup
  (and the cell's text, where the page shows one) reads, for example, `OSC1 Wave=0`, `FM Source=OFF`,
  `OSC2 Wave=64`, `Osc Mix=127`, `FM Amount=60`, `OSC2 Detune=-12st`, `OSC2 Detune=0`, `OSC3 Detune=+7st`,
  `OSC3 Detune=-25ct`: what the synth plays. A ONESHOT, WERP, REPITCH, SLICE or POLY track's page
  looks as before. A POLY track that follows a CFOO track should show CFOO's displays, as its page
  shows its source's parameters (not checked).
- **S15:** set a track to CFOO and trig it, or play it over MIDI, at once, without touching SAMP: it
  sounds. Its SRC page starts at ONESHOT's defaults (TUNE 0, FMSR on no FM, LEV 100), as a change to
  ONESHOT would. Changing machines between ONESHOT, SLICE and the others behaves as before.
- **S14:** the MACHINE menu shows CFOO's icon, two rising sawtooth ramps, left of its name, as POLY
  shows its keyboard. The other machines' icons are unchanged. The picture is a placeholder.

## CFOO, machine 5, in S12

The machine of its own is **CFOO / "CFO OSCILLATOR"**, machine 5, after POLY in the MACHINE menu. At list
position 5 the menu's "list position = machine number" assumption still holds, so no mapping is needed.
S12 builds it with `cfo.s` assembled with `--defsym MACHINE5=1`; S11's ONESHOT variant is unchanged byte
for byte.

### The sites a sixth machine extends

Each already carries an edit for POLY, machine 4
([docs/patch_listing.md](../../docs/patch_listing.md#poly-engine)); S12 raises each bound once more, after
checking that the site holds the build's POLY edit (`EDITS` in `make_cfo.py`):

| Site | What bounds it |
|---|---|
| `0x400225f0` | the machine setter rejects a machine above the bound |
| `0x40022f7e`, `0x40022fae`, `0x40022fe6` | the machine-list builder's capacity, end and count |
| `0x4007910c`, `0x4007912c` | the long-name and short-name readers, and the name table they index. S12 points both at a new table in the pad, the build's five pairs plus `CFO OSCILLATOR` / `CFOO` |
| `0x4007a2d0` | the per-sound deserializer, so a stored machine 5 survives a reload |
| `FUN_40029e80` | the group mapper: machine + 1 for 0..4, else 0. A machine above the bound gets code 0 and no picker icon (blank, safe). S12 leaves it; S14 raises it ([below](#cfoos-picker-icon-in-s14)) |

In the render, a machine-5 track takes the lanes' "no window" branch (in `FUN_400757fe`, a machine other
than 0–3 goes to `0x40075a8a`, which zeroes the window bounds), and `cfo_pad` overwrites whatever they
wrote: with `MACHINE5` it tests for machine 5 in place of ONESHOT with SAMP OFF.

✅ A machine 5 reaches the render as 5, and nothing there takes it for another machine (objdump):
- `FUN_4007725a(sound, track)` copies the sound's machine byte `+0x7e` to `0x800018bc + track` without a
  clamp, and the ISR copies that to the per-tick byte `0x4199f466 + track` (`0x40077eb6..0x40077ed8`).
- `FUN_400ece8e`, which the ISR also hands that array, tests it only for 1 (WERP, at `0x400eced4`).
- The build's POLY source map (`0x400bf17c`) makes a track a follower only when its machine is exactly
  4 (`cmpib #4` at `0x400bf1a0`), so a CFOO track is its own source and never a POLY voice.

**A polyphonic CFOO.** A POLY track right after a CFOO track takes the CFOO track as its source (the
map copies the source of the track before it), and its voices play the synth with no further code.
✅ Read in the code (objdump, decompile of `FUN_40077420`):
1. The build's voice allocation (`0x40037a4e`) puts the source's sound (`[0x800019ac] + 20 + 162 ×
   source`) into a grouped trig event (`+0x28`) before it picks the voice track.
2. The ISR applies an event's sound to the voice track when it differs from the track's current one:
   `FUN_40077282(sound, track)` records it (`0x800019b4 + 4 × track`), copies its 106 B of values to
   `0x80001502 + 106 × track` and its machine byte to `0x800018bc + track` (`FUN_4007725a`).
3. The same trig sets the track's bit in the lanes' trig mask, and the ISR refreshes the per-tick
   machine byte `0x4199f466 + track` for exactly those tracks.

So a voice track that takes a CFOO note has machine 5 and CFOO's values from that tick on, and
`cfo_pad` renders it with its own note, `x`, phases and level ramp. The build's POLY pad at
`0x400bed9c` repeats every later parameter write to the source on each track of its group, so the
voices follow the knobs. This is the same path that lets a POLY voice play a sample machine's sound.
`EmuCfoOscillator` runs the real `FUN_40077282` with a machine-5 sound on a track whose own machine is
4: the track gets machine 5, the 106 B of values and the sound as its current one. ⚠️ Not yet on the
unit.

**The SRC page.** For machines above 3, `FUN_400657cc` falls back to SLICE's layout record. S12 hooks
that fallback (8 B at `0x400657e6`, where the machine is still in `%d0`): machine 5 gets ONESHOT's record
and every other machine above 3 still gets SLICE's. A CFOO track's SRC page therefore shows TUNE, PLAY,
BR, SAMP, STRT, LEN, LOOP and LEV with ONESHOT's ranges. That is the first step towards its own names
(below). With SLICE's page instead, LOOP would be GRID, limited to 0–4.

**Checked:**
- the build's own checks and the compressor window;
- every edited byte and both table pointers read back out of S12, and the six name pairs decoded through
  them;
- every assembled section equal to S12's bytes;
- `EmuCfoOscillator` in machine-5 mode on S12's own bytes passes every case, including the layout lookup
  for machines 0–7, a ONESHOT track with SAMP OFF left alone, and a POLY voice given a CFOO sound;
- `EmuMachineList` running S12's list builder adds six machines, 0–5 in order;
- every other harness passes.

Controls: the ONESHOT build, run in machine-5 mode, fails every synth case and the layout case.

**Not yet:** an icon. The group mapper gives machine 5 code 0, which has no icon. S14 adds one.

## CFOO's own parameter names, in S13

Built with `--defsym NAMES=1` on top of `MACHINE5`. The descriptor table cannot grow (above), so the
names are given per machine at the two places they are read:

- the short label: `FUN_4000fe8a(set, id)`, the descriptor's `+0x30`;
- the long name: `FUN_4000feac(set, id)`, the descriptor's `+0x28`.

✅ Each accessor has exactly one caller (listing scan): `0x40030daa` in
`MachineParameterPageView::vfunc_37`, the parameter cell, and `0x40032d36` in
`ParameterPageView::vfunc_17`, the encoder popup. `SamplePageView`, the SRC page, does not override
`vfunc_37` (its overrides are `vfunc_36` and `vfunc_39`), and eleven vtables share it. This build's
MIDI Loopback pads already sit at both calls, and each ends in a jump to the stock accessor for every
parameter that is not its own (`0x40015646`, `0x40015678`).

S13 points those two jumps at two rename routines in the CFO pad. For ids 108–115, ONESHOT's SRC
parameters, a routine asks the page for the machine it shows:
- the page is in `%a4` at the label call, where `vfunc_37` keeps its `this`;
- it is in `%a2` at the popup call, as the loopback pad already relies on;
- the query is `FUN_4002b5d4(page)`, which already resolves a POLY follower to its source.

On a machine-5 page the routine returns this build's string. Every other case goes on to the stock
accessor with its arguments untouched. TUNE, SAMP and LEV keep the stock short labels; the other five
are named for what the synth does with them:

| Slot | Short | Long |
|---|---|---|
| 17 | TUNE | Tune |
| 18 | FMSR | FM Source |
| 19 | MIX | Osc Mix |
| 20 | SAMP | Sample Slot |
| 21 | WAV1 | OSC1 Wave |
| 22 | FM | FM Amount |
| 23 | WAV2 | OSC2+3 Wave |
| 24 | LEV | Level |

**Checked:**
- `EmuCfoOscillator` in names mode calls both label pads, as built, for ids 100–120 on pages showing
  machines 0, 3, 4 and 5, with the page's machine query stubbed:
  - CFOO's names come back only for ids 108–115 on a machine-5 page, and the stock names everywhere
    else, through the real stock accessors;
  - the query receives the page;
  - the stack and the callee-saved registers are kept;
  - MIDI Loopback's TRK label still works.
- `EmuChanLabel` passes on S13, following the fall-through through the rename routine to the stock
  accessor without a machine query.

Control: with the jumps left as the build has them, only the machine-5 page fails, with the stock
PLAY / Play Mode.

**Not yet:** the values. Their formatters belong to the descriptor, so FMSR still shows PLAY's
play-mode pictures and the others show ONESHOT's numbers. ✅ Read in the code: `FUN_400657ee(id, value)`
formats a value through a per-id formatter object at `0x4197e30c + 0x54 × id` (`FUN_40151eea`; the
objects are built at run time) into a shared buffer at `0x4197de98`, which it returns. It has five
callers and is not given the page. A CFOO-only value text would therefore take the current track's
machine from the project: `FUN_4001d24e` (the current track, from the project object `+0x30`), then
`FUN_4000d7be(object, track)` (`object + 0x60 + 200 × track`, the track clamped to 0..7), then
`FUN_4002200a` (the machine byte at `+0x7e` behind the handle). Not built.

✅ The SRC cell draws its value from the same per-id objects (objdump of
`MachineParameterPageView::vfunc_37` from `0x40030c0c`, and of `ParameterSet::vfunc_22` and
`vfunc_23`):
- `FUN_40065794(id)` returns the id's display object, `0x4197e2f8 + 0x54 × id`; the popup's
  formatter at `0x4197e30c + 0x54 × id` is that object's `+0x14`;
- the cell asks the page for the parameter set that owns the id (`vfunc` at `+0xa4`), and
  `FUN_4000fece(id)` returns the object's word `+0x00`;
- the cell then has the set draw a picture (`ParameterSet::vfunc_23` `0x4000f2bc`, through the
  callable at `+0x24..+0x30`, which does nothing when `+0x2c` is 0) and write text
  (`ParameterSet::vfunc_22` `0x4000f324`, through the formatter at `+0x14..+0x20`), which it draws with
  `%s`. Which of the two show depends on bits 1 and 2 of the word `+0x00` and on the page's state.

So two hooks would give CFOO its own value displays: `FUN_40065794`, which returns a CFOO object for
ids 108–115 when the current track is CFOO, and `FUN_400657ee` for the popup. The CFOO objects would
need callables that the stock calling code accepts: the formatter's manager word at `+0x1c` must not be
0, or `vfunc_22` calls `0x4017803e` instead (very probably `std::__throw_bad_function_call`). What each
value should show depends on CFOO's final parameters (open). Also not yet: the SAMP cell (CFOO does not use
it; hiding it needs CFOO's own layout record with id 0 there), and the page name (`+0x2c`, read at
`0x40065de6`).

### The SRC page: layout and names

✅ Read in the code and the image data.

**The layout record.** `FUN_400657cc(machine)` returns `0x4197ded8 + 0x2c × machine` for machine < 4 and
`0x4197df5c` otherwise, which is SLICE's own record: that is why a POLY track shows SLICE's page. A
record is 44 B: `+0x00` and `+0x04` the page's long and short name, `+0x08..+0x2c` nine parameter ids,
where 0 means "no parameter". The four records are built at run time by a static initialiser around
`0x40156df0..0x40156f60`, in a stretch Ghidra made no function of (nothing in
`0x40155000..0x40157000`). ⛔ There is no slack: `0x4197ded8 + 4 × 0x2c` is exactly where the next
table starts, the one built from `0x40156558`.

**The name fields hold one pointer each.** `FUN_4017af20(dst, cstr, alloc)` measures the C string
(`0x4012ad00`), builds the string through `FUN_4017aa72` and stores the single result word at `dst`:
a string object that is just a pointer to its character data. ⇒ a CFOO record does not need a forged
static object. The build can keep a 44 B record in its own RAM, fill it on the first call to the lookup,
and call `0x4017af20` twice with its own literals.

**The parameter descriptors.** A table of **164 records of 0x34 B**; the accessors check `id < 164` and
index from `0x401aa09c` (for example at `0x400790c8` and `0x400790ea`). Per record:

| Offset from `0x401aa09c` | Contents |
|---|---|
| `+0x00` | page id; `0..3` are the four machines' SRC pages, `0xc..0xe` the MIDI pages, `0xf`/`0x10` the LFOs; `-1` for the track-level records |
| `+0x04` | the value-array slot, `0x11..0x18` for an SRC page |
| `+0x28` | the parameter's long name, for example `Tune` |
| `+0x2c` | the page's name, for example `Sample`, `Werp`, `Repitch`, `Slice` |
| `+0x30` | the parameter's short label, for example `CHAN`, `VAL1` |

⭐ **Every machine already has its own eight records**, with its own names, all pointing at the same
value-array slots `0x11..0x18`: ONESHOT 108–115, WERP 116–123, REPITCH 124–131, SLICE 132–139. So CFOO's
own names are eight more records of the same shape, and the synth code needs no change: it already reads
those slots from the engine block.

⛔ The table has no free rows. Ids up to 163 are in use (the MIDI track's pages end the table), and the
records whose name reads `Error` are track-level records with page and slot `-1` and their own data in
`+0x18`, not spare rows. (The descriptor sorter skips page −1 rows, so the CC words of rows 4 and 5,
CC 7 and CC 10, reach no table; portamento reuses those two rows,
[portamento.md](portamento.md#port-and-leg-as-sound-parameters). Two rows are not eight.) So the eight
records have to come from somewhere else:

- ⛔ **Serve ids at or above 164 from a second table.** Out: twenty accessors index from the same base
  (`lea 0x401aa09c,%a0`, forty `id < 164` checks), so each would need its own branch.
- ⛔ **Extend the table in place.** Out: it ends at `0x401ac1ec` and other data begins there.
- ⛔ **Relocate the whole table.** Out: 8,528 B against a constant-data budget of 2,268 B.
- ⭐ **Rename for this machine only.** Point CFOO's layout record at ONESHOT's ids 108–115 and give the
  name readers this build's own strings when the track's machine is 5. The readers are few: `0x4000fea6`
  reads the short label, `0x4000fec8` the long name and `0x40065de6` the page name; `0x40060ba6` reads
  the short label as well, but never of an SRC parameter (below).

  This build already renames a parameter exactly this way: MIDI Loopback turns CHAN into TRK and
  "Channel" into "Track" through the same two readers, from pads reached at `0x40030daa` and
  `0x40032d36` ([function_ledger.md](../function_ledger.md)). Those hooks sit at the call sites in the
  parameter page, not in the readers; each accessor has only that one caller, so a machine-5 page passes
  through them too ([above](#cfoos-own-parameter-names-in-s13)).

  ✅ The two other sites a scan for `+0x30` turned up (objdump):
  - `0x4002351c` is not a descriptor read: it is `object + 0x30 + 200 × index` on a per-track object.
  - `0x40060ba6` (in a routine starting at `0x40060b80`) draws the short label of the id that
    `FUN_40078f8c(slot)` gives. That id comes from the table `0x4199f81c`, which the descriptor sorter
    at `0x40078b20` fills from descriptors with page ids 7–10 only, so it never names an SRC parameter.

  ✅ The same sorter puts the SRC pages' ids (page ids 0–3) into `0x4199f9c4`, eight per machine, and
  `FUN_40078f44(slot, machine)` reads them: for an SRC slot it returns `0x4199f9c4[8 × machine + slot −
  17]` for machines 0–3 and id 0 for any machine above 3. The build leaves that bound at 3, so POLY's
  and CFOO's SRC slots both map to id 0 there: a track-level record named `Error` / `ERR` whose fields
  `+0x08..+0x24` are all 0 or −1. Its callers (objdump):
  - `SoundParameterSet::vfunc_20(slot)` takes the machine from the sound's byte `+0x7e` and returns
    `FUN_40078f44(slot, machine)`: the descriptor id of a slot in a given sound.
  - `FUN_40015e00`, for one kind of event, takes the track's machine (`FUN_4000d7be`, `FUN_4002200a`),
    maps the slot, and passes the id to `FUN_4006cc38`. That routine sends a 6-byte message, tag
    `0x30`, made of the track, the descriptor's word at `+0x20` (`0x6e..0x75` for ONESHOT's SRC
    parameters, `0xc7..0xce` for SLICE's, 0 for id 0) and the value.
  - `FUN_400220fc` and `FUN_40084ef6` load its address into `%a4`; not read.

  ⚠️ What the message drives, and what the other callers show for an SRC slot on a machine above 3,
  is not traced. CFOO inherits whatever POLY already does there.

⚠️ What the page id at `+0x00` controls beyond the lookups above is not traced, so whether CFOO needs a
page id of its own (and what the 19-record generic page table at `0x4197df88` would then need) is open.

## CFOO's picker icon, in S14

Built with `--defsym ICON=1` on top of `MACHINE5` and `NAMES`. The MACHINE menu finds a row's icon in
two steps:
- the group mapper `FUN_40029e80` turns the machine into a code;
- the icon lookup at `0x40029ef0` sends codes 4 and up to this build's POLY icon pad `0x400bee44`, which
  loads a `Bitmap` object from a selector table indexed by code − 4.

Stock codes are 1–4 for machines 0–3 (the byte table at `0x401c318c`, read in the stock image); the
build's mapper gives machine + 1 for machines 0–4. S14 changes three things, each after checking that
the site holds the build's bytes (`ICON_EDITS` and `ICON_PTR` in `make_cfo.py`):

| Site | Build | S14 |
|---|---|---|
| `0x40029e81` | the mapper's bound 4 | 5: machine 5 gets code 6 |
| `0x40029ef9` | the pad's range 1 (codes 4–5) | 2: codes 4–6 |
| `0x400bee46` | the pad's table `0x40252bf8`: SLICE's icon, POLY's | `0x40252c2c`: SLICE's icon, POLY's, CFOO's |

The new table and CFOO's `Bitmap` object go into the `.rodata` padding at `0x40252c2c..0x40252c80`
([landing_pads.md](../landing_pads.md#rodata-the-constant-data-budget)). The object has POLY's shape:
the `Bitmap` vtable `0x401b7734`, 11 × 7, one plane, the plane pointer, POLY's stock mask `0x4023e0a0`
and a zero word. The plane is 11 words, one per column. Row r, counted from the top, is bit
31 − (6 − r), so the bottom row is bit 31. ✅ This is the rule the build's own POLY plane at
`0x40252bb0` follows: it decodes to the keyboard grid in
[icon_artwork.md](../../../../notes/icon_artwork.md#the-keyboard) under that rule, and upside down
under the other.

The picture is a placeholder: two rising sawtooth ramps, 52 ink pixels
([icon_artwork.md](../../../../notes/icon_artwork.md#the-cfoo-placeholder)).

**Checked:**
- the build's own checks and the compressor window;
- every assembled section equal to S14's bytes, and the three sites above read back;
- `EmuCfoOscillator` in icon mode runs the icon lookup from `0x40029e9c` for machines 0–6 and reads the
  `Bitmap` the draw call receives: CFOO's object for machine 5, POLY's for 4, SLICE's for 3, the stock
  icons for 0–2 and none for 6;
- the plane read back out of S14 decodes to the placeholder grid under the rule above and not under the
  flipped one, and so does POLY's plane to the keyboard; the master PNG decodes to the same grid;
- `EmuMachineList` running S14's list builder still adds machines 0–5, and every other harness passes.

Control: S13's own bytes over the same regions, with the build's icon data and old selector table, run
in icon mode. Machines 0–4 get the same icons as on S14, and machine 5 gets none, so only machine 5
fails.

## A machine change to CFOO, in S15

On the unit, a track just set to CFOO stayed silent until SAMP was moved (S12 to S14). ✅ Read in the
code (objdump): the machine setter (`FUN_400225ca`, `0x4002263c`) stores the new machine in the sound and
calls `FUN_400220fc(sound set, 1, old machine, 0)`, which walks the SRC slots 17–24
(`FUN_40078d62`/`FUN_40078d66`). For each it asks `FUN_40078f44(slot, new machine)` for the parameter id,
sets the slot to that descriptor's default (`FUN_40078f0c`) and posts a change through the set's vfunc
at `+0x10`; a slot whose id is 0 is skipped. `FUN_40078f44` gives id 0 for every SRC slot of a machine
above 3, so a change to machine 5 set no slot and posted nothing, and the engine kept the old machine
until a later change (SAMP) reached it.

S15 is built with `--defsym SLOTS=1` on top of S14. Its hook (6 B at `0x40078f72` in `FUN_40078f44`, the
target of the branch at `0x40078f64`, with the machine in `%d0` and `%d2` saved by the function) looks
machine 5 up as machine 0, so CFOO's SRC slots are ONESHOT's parameters 108–115, the ones its page shows.
Every other machine is looked up as before. A change to CFOO then sets the eight slots to ONESHOT's
defaults and posts eight changes, as a change to ONESHOT does. The other users of `FUN_40078f44`
(`SoundParameterSet::vfunc_20`, `FUN_40015e00`, `FUN_40084ef6`) get ONESHOT's ids for CFOO's SRC
parameters too, where they got the `Error` descriptor before.

**Checked:**
- `EmuCfoOscillator` runs the real `FUN_40078f44` with the hook for slots 17–24 on machines 0–7, its
  run-time table seeded: machines 0–3 get their own ids, machine 5 machine 0's, machines 4, 6 and 7 id 0.
  On S14's code the same case fails only for machine 5;
- every other case, the window, the image bytes and every other harness pass on S15's own bytes.

⚠️ That the posted changes make the engine take the new machine is inferred from S12–S14's behaviour
(any later change did), not traced: S15 tests it.

## CFOO's own knobs, in S16

Built with `--defsym KNOBS=1` on top of S15. CFOO's page keeps ONESHOT's parameter ids 108–115 (one per
SRC slot, knobs A–H), with its own meanings, names, ranges and defaults:

| Knob | Slot | Name | Range | Default | What the synth does |
|---|---|---|---|---|---|
| A | 17 (`+0x34`) | WAV1, OSC1 Wave | 4–88 | 4 | OSC1's wave, (A − 4) × 1.5: SIN → TRI → SAW → SQR |
| B | 18 (`+0x36`) | FMSR, FM Source | 0–3 | 0 | 0 off, 1 OSC2, 2 OSC2+3, 3 OSC3 |
| C | 19 (`+0x38`) | WAV2, OSC2 Wave | 0–127 | 0 | OSC2's wave |
| D | 20 (`+0x3a`) | WAV3, OSC3 Wave | 0–127 | 0 | OSC3's wave |
| E | 21 (`+0x3c`) | MIX, Osc Mix | 0–120 | 0 | the mix, E + E/16: OSC1 → 1+2 → 1+2+3 → 2+3 |
| F | 22 (`+0x3e`) | FM, FM Amount | 0–120 | 0 | FM depth, as LEN was |
| G | 23 (`+0x40`) | DET2, OSC2 Detune | 0–98 | 48 | 0–47: −48 … −1 semitones; 48 unison; 49–98: +1 … +50 cents |
| H | 24 (`+0x42`) | DET3, OSC3 Detune | 0–98 | 50 | 0–49: −50 … −1 cents; 50 unison; 51–98: +1 … +48 semitones |

A cent is 655 in the note sum (a semitone is `0x10000`), so ±50 cents is ±32,750; the pitch table
resolves about 0.6 cent. OSC1 follows the note alone (no TUNE). The level is `FUN_40074c60(x, 127)`:
velocity only, with the de-click and the ramp as before.

**Own ranges and defaults.** ✅ Read in the code (objdump): every reader of a parameter's range and
default gets it from `FUN_40078f0c(id)` (36 callers), or from its copy `FUN_4007927c` (6 callers in the
project loader), which copy the 12 B `{min, max, default}` from descriptor `+0x08` to `%a0`, given only
the id. Five callers hold the parameter set in `%a2` (loaded in their prologues, not changed before the
call): `ParameterSet::vfunc_8` (`0x4000f534`), the value setter `FUN_4000fef6` (`0x4000ff20`),
`ParameterSet::vfunc_11` (`0x400100c4`, the knob edit), `ParameterSet::vfunc_25` (`0x40010154`, a
14-bit outside value) and the machine-change reset `FUN_400220fc` (`lea` into `%a5` at `0x40022138`).
S16 points those five operands at `cfo_range`: for ids 108–115 on a set whose machine
(`FUN_4002200a`, which keeps `%a2`) is 5 it copies CFOO's record, and anything else goes on to
`FUN_40078f0c` untouched. ⛔ `FUN_4002200a` takes a sound holder, not a parameter set: the test works
only at `FUN_400220fc`, whose `%a2` is a holder, and fails at the four others, so S16–S18 kept
ONESHOT's ranges in every edit (S19, [below](#the-sound-behind-a-parameter-set-in-s19)). S16 also keeps each of CFOO's ranges inside ONESHOT's for the same slot (TUNE
4–88, PLAY 0–3, BR and SAMP 0–127, STRT, LEN and LOOP 0–120, LEV 0–127), on the assumption that the
readers not hooked would clamp a stored value to ONESHOT's range. ⛔ That assumption does not hold for
the project loader: its six range reads only fill defaults for fields of older formats, with fixed ids
none of which is an SRC parameter, and it copies stored values unchanged (S18,
[below](#cfoos-knobs-as-agreed-in-s18)). MIDI CC still clamps to ONESHOT's range on the way in, and the
synth clamps every knob itself.

**Checked:**
- `EmuCfoOscillator` in knobs mode (a load with `cfo_range`) runs 21 synth cases against a model written
  from the table above, not from the code: defaults, A across its range and below it, the mix points,
  each FM source, each detune region and its ends, values above the ranges, extreme notes with +48 st,
  the de-click, the level RAM at boot, half level. A pure SIN at note 60 measures 261.47 Hz.
- It runs `cfo_range` against a fake parameter set whose sound carries machine 5, 0 or 4, for ids
  106–117: CFOO's record only for 108–115 on machine 5, the stock record otherwise, `%a2`, `%d2` and the
  stack kept; and it checks that all five call operands point at `cfo_range`. ⛔ The fake set was laid
  out as a sound holder (its `+0x28` method returned the sound), so this case could not catch the
  fault above; S19's cases use the firmware's layout.
- The names case expects the new names; layout, icon, slots and the POLY voice pass; the image bytes,
  the window and every other harness pass on S16's own bytes. S9–S15 rebuild byte for byte.
- Control: OSC2's detune through OSC3's table fails exactly the cases in which OSC2 is heard.

⚠️ Not hooked, so still ONESHOT's ranges: the edit of one parameter on all eight tracks
(`MachineParameterPageView::vfunc_23`, `0x400326aa`), MIDI CC scaling (`FUN_40084650`, `FUN_40084760`,
`FUN_40084ef6`), the modulation and lock paths (`FUN_4001c85c` and its neighbours), and every value
display. What each does with CFOO's narrower ranges is listed under S17
([below](#cfoos-own-value-displays-in-s17)), which gives CFOO its own displays.

## CFOO's own value displays, in S17

Built with `--defsym DISPLAYS=1` on top of S16. In S16 every CFOO value is still shown as ONESHOT's
parameter in that slot shows it: the cell's picture and text and the encoder popup all go through the
id's display object, and the knob drawers scale by ONESHOT's ranges. ✅ Read in the code (objdump,
decompile):
- the cell, `MachineParameterPageView::vfunc_37`, has the parameter set draw the value's picture
  (`ParameterSet::vfunc_23`, `0x4000f2bc`) and write its text (`ParameterSet::vfunc_22`, `0x4000f324`),
  both with `(set, id, value, …)` on the stack;
- the encoder popup, `ParameterPageView::vfunc_17`, writes the value with `FUN_400657ee(id, value)` at
  `0x40032d16`, the page in `%a2` (loaded at `0x40032a84`, kept to the end);
- the display objects are built at start-up by `FUN_40152280`: STRT's and LEN's pictures are the same
  plain knob, whose drawer (`0x400600a4`) picks frame 12 + round(value × 72 / 120.0), with 120.0
  (`0x7800`) built in.

S17 hooks the two methods at their first 8 B (a `lea` and a `moveml`, which the hook routines replay)
and the popup at its call operand. For ids 108–115 on a CFOO sound (`FUN_4002200a(set)` for the cell,
`FUN_4002b5d4(page)` for the popup, as S13's names do; ⛔ the cell's test does not work, as for S16's
ranges, and passes or fails by the cell's row: S19):
- **The picture** is STRT's knob for every knob, the value rescaled from the knob's own range
  (`cfoo_ranges`, the S16 table) to 0–120.0, so each knob turns over its whole range: B in four steps,
  G and H with unison just left and right of the top.
- **The text**, in the cell and in the popup, is what the synth makes of the value:

  | Knob | Text | Example |
  |---|---|---|
  | A | the wave on the 0–127 scale of C and D: (A − 4) × 1.5 | `0` (SIN) … `126` |
  | B | `OFF`, `OSC2`, `2+3`, `OSC3`; above 3 `OFF`, as the synth takes no source | `2+3` |
  | C, D | the wave, 0–127 | `64` |
  | E | the mix as the synth uses it, E + E/16, at most 127 | `127` |
  | F | the FM amount, 0–120 | `60` |
  | G | `-48st` … `-1st`, `0`, `+1ct` … `+50ct`; above 98 as 98 | `-12st` |
  | H | `-50ct` … `-1ct`, `0`, `+1st` … `+48st`; above 98 as 98 | `+7st` |

  The routine writes at most 6 B and uses no stock formatting code. Every other id and machine goes on
  to the stock code with its arguments untouched.

**Checked:**
- `EmuCfoOscillator`'s displays mode, against a model of the knob table written in the harness: the
  popup routine and the cell's text hook for ids 106–117, values 0–255 with a fraction, on machines 5,
  0 and 4 (2,132 cases): CFOO's text only for 108–115 on machine 5, and otherwise the stock code reached
  with its stack, arguments and saved registers as they would be (the machine query stubbed: ⛔ see
  S16's checks);
- the picture hook for the same ids and values: STRT's id and the rescaled value only for CFOO, the
  replayed frame and the other arguments unchanged;
- G and H across: for every value 0–255, `detune_lo` and `detune_hi` give exactly the semitones
  (`0x10000` each) or cents (655 each) the text names;
- the S16 synth cases, names, icon, layout, slots, ranges and the POLY voice still pass; the image
  bytes, the window, `EmuMachineList` and every other harness pass on S17's own bytes. S9–S16 rebuild
  byte for byte. S17 differs from S16 in the three hooks, the new code and strings, and the operands of
  the name and range tables, which the larger code moves.

Controls, each a copy of S17's code with one instruction changed: G's text offset 48 → 50 fails the
text cases; the picture's id 112 → 113 fails the picture cases; `detune_lo`'s 48 → 47 fails the
cross-check and the S16 synth cases that play OSC2.

⚠️ Still ONESHOT's, not changed by S16 or S17 (objdump, decompile; S19 hooks the reset, MIDI CC and
the all-tracks edit):
- **A reset to a parameter's default**, `ParameterSet::vfunc_4` (`0x4000ff9a`), takes ONESHOT's
  default (`FUN_40078f0c` at `0x4000ffac`, the set not in `%a2`) and stores it through the hooked setter,
  which clamps it into CFOO's range: A 64, B 3, C 0, D 0, E 0, F 120, G 0 (−48 st), H 98 (+48 st). What
  triggers it is not traced (229 call sites through that vtable slot).
- **Randomising a sound**, `FUN_40021d26` (named RANDOM), draws every id's value from its own range and
  stores the last one per slot, SLICE's for slots 17–24; the synth clamps each knob.
- **MIDI CC** (`ParameterSet::vfunc_26`, 0–127 × 256) and `vfunc_25` (a 14-bit value, doubled) clamp to
  ONESHOT's range before the hooked setter clamps to CFOO's: a CC on G or H has a dead zone above 98, on
  A below 4 and above 88.
- **The all-tracks edit**, `MachineParameterPageView::vfunc_23`, steps with ONESHOT's range and stores
  through the hooked setter.

## CFOO's knobs as agreed, in S18

Built with `--defsym KNOBS2=1` on top of S17. The knob table after S17 (the knobs keep their places
and ids 108–115, so their slots):

| Knob | Range (8.8) | Default | Step like | Text | What the synth does |
|---|---|---|---|---|---|
| A | 0–127 | 0 | BR | `0`…`127` | OSC1's wave |
| B | 0–2 | 0 | PLAY | `OSC2`, `2+3`, `OSC3` | the FM source; above 2 as 2 |
| C, D | 0–127 | 0 | BR | `0`…`127` | OSC2's, OSC3's wave |
| E | 0–127 | 0 | BR | `0`…`127` | the mix, OSC1 → 1+2 → 1+2+3 → 2+3 |
| F | 0–127 | 0 | BR | `0`…`127` | the FM amount |
| G, H | 40.0–88.0 | 64.0 | TUNE | `-24.00`…`0.00`…`+24.00` | OSC2's, OSC3's detune: (v − 64.0) semitones, fraction included |

The synth clamps every knob to its range (a wave, the mix and FM to 127, B to 2, a detune to
40.0–88.0). A detune is the stock TUNE formula, `(v − 0x4000) × 256` on the note sum. A text's two
decimals are hundredths of a semitone, rounded: one is a cent.

✅ Read in the code (objdump, decompile) for S18:
- **The step.** `ParameterPageView::vfunc_17` is the encoder handler. It takes the knob's display object
  (`FUN_40065794` at `0x40032b74`), copies the 28 B step profile its template points to, and has
  `FUN_400c06ae` turn the encoder's ticks into a delta: whole steps (× 256) when the profile says so,
  fine steps (1/256) otherwise, with acceleration. TUNE, STRT, LEN, LOOP and LEV share one fine profile;
  BR, SAMP and PLAY have their own. The page then stores value + delta (`ParameterSet::vfunc_11`, then
  `vfunc_8`), clamped to the range S16's `cfo_range` gives. S18 hooks the call at `0x40032b74`: on a CFOO
  page the encoder takes the display object of BR (110) for A and C–F, of PLAY (109) for B and of TUNE
  (108) for G and H. The pushed id is not used again.
- **The sample picker.** The SRC page's own handler, `SamplePageView::vfunc_17`, opens the sample picker
  for the four machines' Sample Slot ids (111, 119, 127, 135; the test at `0x4003b5a0..0x4003b5be`).
  S18 replaces its first three instructions (6 B) with a call that replays them, except that for id 111
  on a CFOO page the test fails: D then takes the page's ordinary path, redraw included.
- **The pitch.** The stock pitch table ends at index 14,848 (note sum `0x570000`, note 84, about 1 kHz),
  where it saturates. S18's `pitch` takes a higher note sum down by octaves, looks it up, and doubles
  the step back up for each octave, at most `0x7fffffff` (half the sample rate). Below note 84 nothing
  changes.
- **The stored ranges.** ⛔ S16's limit, each CFOO range inside ONESHOT's for the slot, is not needed:
  - the project loader's range reads only fill defaults for fields of older formats, with fixed ids
    none of which is an SRC parameter (`FUN_4007b682`, `FUN_4007b81e`, `FUN_4007bc9c`, `FUN_4007bd86`,
    `FUN_4007be72`, `FUN_4007bf58`);
  - `FUN_40074b9e`, which turns each track's values into the engine block the synth reads, only smooths
    them (EMAC, one pole), with no range;
  - `FUN_4001c85c` and its neighbours handle global settings (ids 95–107), not SRC parameters.

  MIDI CC (`ParameterSet::vfunc_26`) still clamps to ONESHOT's range before CFOO's: on A a CC reaches
  4–88, on E and F 0–120, and on G and H CC 40–88 are the semitones −24 … +24 (S19 changes this).

**Checked** (`EmuCfoOscillator`, S18 mode, against a model of the table above):
- 19 synth cases: defaults, the waves, the mix points, each FM source and B above its range, the
  detunes at their ends, with fractions and beyond, values above 127, high notes with +24 semitones,
  extreme notes, the de-click, the level RAM at boot, half level; and `pitch` against the model for
  note sums across `0..0xa00000`;
- texts in the popup and the cell for every knob (A–F at every value, G and H every 0x0d across
  `0x2000..0x6000` and at their edges, 4,168 cases), on machines 5, 0 and 4: the stock code untouched
  for every other id and machine;
- the picture: the 8.8 value rescaled onto STRT's knob;
- the G and H texts against `detune24`, within half a cent;
- the encoder's display object for ids 104–120 on machines 5, 0 and 4, and the Sample Slot test for
  ids 108, 110–112, 115, 119, 127 and 135 on the same machines, with the registers kept;
- names, icon, layout, slots, ranges, the POLY voice; the image bytes, the window, `EmuMachineList` and
  every other harness on S18's own bytes. S9–S17 rebuild byte for byte.

Controls, each a copy of S18's code or tables with one change: the octave doubling removed fails the
high-note cases and the pitch sweep; the detune centre one semitone off fails every case that plays
OSC2 or OSC3 and the text cross-check; B with BR's step fails only the encoder case; a picker test that
never fails fails only the picker case; the cents truncated instead of rounded fail only the text
cases.

Worst tick: 18,001 instructions (all eight tracks on the synth), plus the stubbed level call.

## The sound behind a parameter set, in S19

Built with `--defsym SETS=1` on top of S18. On the unit, S18 kept ONESHOT's ranges, drew some knobs
and values as ONESHOT's and some as CFOO's, and gave a ONESHOT track CFOO's pictures. ✅ Read in the
code (objdump, decompile) and reproduced in the emulator on S18's bytes:
- `FUN_4002200a(object)` calls the object's method at `+0x28` with no other argument and takes the
  signed byte at `+0x7e` of what it returns as the machine. The stock callers read pass a per-track
  sound holder (`FUN_4000d7be(… + 0xec, track)`; the [ledger](../function_ledger.md) lists which). A
  parameter set is not one: in `SoundParameterSet`'s vtable
  (`0x4017ee58`) `+0x28` is the value getter `vfunc_10(set, id)`. Given a set, `FUN_4002200a` reads the
  value of the parameter whose id is whatever its caller holds in `%a2` (`FUN_40078d80` maps an id
  above 163 to entry 0, which has none: value 0, machine −1), and takes the byte at that value +
  `0x7e` for the machine.
- In the cell drawer, `MachineParameterPageView::vfunc_37`, `%a2` holds the cell's y coordinate. So the
  picture and text hooks' result depends on the row and on the sound's values, not on its machine:
  CFOO's top row passed and its bottom row failed, and a ONESHOT track could pass. At `cfo_range`'s four
  set sites `%a2` holds the set itself, so the test failed every time. Only `FUN_400220fc`, the
  machine-change reset, passes a holder.
- `SoundParameterSet` keeps its holder at `+0x10` and reaches its sound through it (`vfunc_10`, and
  `vfunc_20`, which maps a slot to its id by the sound's machine).
- The setter `FUN_4000fef6` stores a value only if the set's `vfunc_9` accepts it. For every SRC id that
  is `ParameterSet::vfunc_9`, a range test through `FUN_40078f0c` (`SoundParameterSet::vfunc_9` tests
  bits 16 and 17 of the descriptor's flags at `+0x24`, which are `0xe00` for these ids). With CFOO's
  range in the clamp alone, A still could not leave 4–88.

S19:
- `set_machine(object)`: for a `SoundParameterSet`, its holder's machine; for the other four
  parameter-set classes (vtables `0x4017edd8`, `0x4017eed8`, `0x4017ef58`, `0x4017efd8`, 0x80 B apart,
  Trig's among them), none; anything else is taken for a holder, as `FUN_400220fc` passes. `cfo_range`,
  the picture and the cell text use it.
- Four more range reads go to `cfo_range`, each to an entry for where its caller keeps the set:
  `ParameterSet::vfunc_9` (`0x4000f5fc`, a `lea` into `%a2`, the set at `%sp@(36)`), the reset to the
  default `ParameterSet::vfunc_4` (`0x4000ffac`, `%sp@(28)`), MIDI CC `ParameterSet::vfunc_26`
  (`0x40010d50`, `%a2`) and the all-tracks edit `MachineParameterPageView::vfunc_23` (`0x400326aa`,
  `%a3`). A CC spans CFOO's range, a reset gives CFOO's default.
- S16's and S17's text and detune routines, which S18 no longer reaches, are left out: the code is 138 B
  smaller, and the pad has 149 B left.

**Checked** (`EmuCfoOscillator`; with `set_machine` in the load it builds a sound's parameter set as the
firmware lays it out: the stock vtable, the holder at `+0x10`, the sound with its values from `+0x14`
and its machine at `+0x7e`; only the holder's accessor is a stub, and the stock code runs unstubbed):
- `cfo_range` by each entry on a sound's set, the holder, Trig's set and a set without a holder, on
  machines 5, 0 and 4, ids 106–117 (468 calls): CFOO's record only for 108–115 on a CFOO sound;
  `FUN_4002200a` gets the holder once, and never another set; registers and the caller's stack kept;
  the nine operands point at their entries;
- S18's display cases on such a set, the machine read by the stock `FUN_4002200a`;
- end to end through the stock code on machines 5 and 0, ids 107–116: the validity test
  (`SoundParameterSet::vfunc_9`, 265 values), the encoder's edit (`ParameterSet::vfunc_11` →
  `vfunc_8` → `FUN_4000fef6`, 840 edits from each range end and inside, by ±1, ±256 and further) and
  the reset (`ParameterSet::vfunc_4`, 20), with only the value write (`SoundParameterSet::vfunc_29`)
  and the notice after it (`ParameterSet::vfunc_24`) stubbed: each edit writes the value plus the
  step clamped to the machine's range, each reset the machine's default;
- S18's bytes in the same mode (the harness argument `realsets`) fail the range, picture, text and edit
  cases as the unit did: A clamped to TUNE's 4–88, the cells' text and picture ONESHOT's;
- the rest as S18; the image bytes, the window, `EmuMachineList` and every other harness on S19's own
  bytes. S9–S18 rebuild byte for byte. S19 differs from S18 in the four new operands, the code, and
  the operands that point into the code, which moves.

Controls, each a copy of S19's load with one change: `set_machine` comparing with Fx's vtable fails
every set case, as S18 does; `vfunc_9`'s `lea` back to the stock function fails the validity and edit
cases; the parameter-set bound narrowed to one vtable passes Trig's set to `FUN_4002200a`; the empty
holder's test removed calls through address 0; the all-tracks entry reading `%a2` fails its own cases.

⚠️ Still ONESHOT's ranges: the randomiser (SLICE's for slots 17–24, as before) and the modulation
paths. Not traced: which set a parameter lock on an SRC knob goes through.

## Pure points, in S20

Built with `--defsym PURE=1` on top of S19. `wave` and `gains` mapped a knob value v (0–127) to a
crossfade by 3v/128: segment 3v >> 7, fraction 3v & 127. SIN is pure at 0, but TRI and SAW fall
between two values (42.67, 85.33) and 127 is 98 % SQR; the mix's corners (OSC1, 1+2, 1+2+3, 2+3) the
same. S20's `segfrac` puts the four points at 0, 42, 85 and 127, segments of 42, 43 and 42 values,
the fraction (v − start) × 256 / length rounded, exactly ((v − start) × 512 + length) / (2 × length) by
`divu.w`, so 0..256 with 256 the next table alone (for 127 the last). The two pieces of code
(`osc32`'s and `osc1_mix`'s crossfade, `gains`' loop) take a fraction up to 256 unchanged.

The name, range and display tables (`.cfo_names`, 465 B) move from the pad to the zero .rodata
padding after the icon, `0x40252c80`: `make_cfo.py` places them there with `ld --section-start` from
S20 on, and the pad holds code only (576 B left in S20).

**Checked:** `wave` and `gains` for every value 0–127 against a model written from the points (tables,
fraction, the three gains), and the model gives one wave and one mix alone at the four points; the
synth cases, now against that model; names and everything else as S19, on S20's own bytes. Control:
the middle segment's rounding term removed fails the point cases and every synth case that plays a
value in it. Worst tick: 18,185 instructions (the divides).

## [FUNC] + knob, in S21

Built with `--defsym SNAP=1` on top of S20. ✅ Read in the code (objdump, decompile):
`ParameterPageView::vfunc_17` passes `FUN_400d399c(1) != 0` (key 1 held; ⚠️ [FUNC], from the effect)
to the page's write and on to `ParameterSet::vfunc_11` as its flag. With the flag, `vfunc_11` takes the
knob's display object (`FUN_40065794` through `%a4`, loaded at `0x40010052`) and, if its `+0x44`
callable is set (`+0x4c` not 0), stores what that returns for (value, delta, min, max, default)
instead of value + delta. The stock callables of this kind (`0x4005f7ec..0x4005f89a`) go by the
delta's sign to an end, to the default, to the middle or by ±12 semitones; which id has which is not
read.

S21 points that `lea` at `cfo_fobj`: for A, C, D, E, G and H on a CFOO sound (`set_machine(%a2)`) it
returns a display object of this build, of which only the callable (`+0x44..+0x53`) exists, in
.rodata; for anything else FUN_40065794's. Its invoker, `snap_inv`, goes to the first point above the
value on a turn up, the last below on a turn down, and stays past the last point:

| Knob | Points (8.8) |
|---|---|
| A, C, D | 0, 42, 85, 127: SIN, TRI, SAW, SQR |
| E | 0, 42, 85, 127: OSC1, 1+2, 1+2+3, 2+3 |
| G, H | −24, −17, −12, −5, 0, +7, +12, +19, +24 semitones |

B and F keep PLAY's and LEN's callables.

**Checked:** `cfo_fobj` for ids 104–120 on machines 5, 0 and 4: this build's objects only for A, C, D,
E, G, H on 5, FUN_40065794 with the id unchanged otherwise, registers kept; and end to end through the
stock `ParameterSet::vfunc_11` with the flag on a sound's set (S19's objects), from 9 to 11 starting
values per knob by ±1 and ±256 (232 edits): each writes the next point, or stays, and sets the
special-action flag. Control: one wave point moved fails those edits. ⚠️ Ghidra's emulator does not
set the condition codes after `mvs`/`mvz` ([emulator.md](../../../../notes/emulator.md)); the routine
tests the value itself.

## The [TRK] popup, in S22

Built with `--defsym TRKPOP=1` on top of S21. ✅ Read in the code (objdump): `FUN_4003bbfe(track)`
(callers `0x4003c56e` and `0x4002898e`; shown while key 2 is held, `0x4003bcd4`, ⚠️ [TRK]) formats
`FUN_40093ab0(popup, "%s: %.16s", the machine's short name, the sample name of SAMP's slot)` at
`0x4003bd6a`, the track's machine in `%d5` (`FUN_4002200a`, −1 for none), whatever the machine. S22
points that call at `cfo_trkpop`, which gives POLY (4) and CFOO (5) the format `"%s"`: the machine's
name alone.

**Checked:** `cfo_trkpop` for machines −1 to 6: `"%s"` only for 4 and 5, the other arguments, the
stack and the kept registers unchanged. Control: the test shifted by one machine fails it.

Two other places use the same format, on the SRC page; S25 takes them up
([below](#the-src-pages-own-texts-in-s25)).

## CFOO as an LFO destination, in S23

Built with `--defsym LFONAMES=1` on top of S22. A CFOO track's SRC destinations are ids 108–115
(`FUN_40078f44`, S15). ✅ Read in the code (objdump):
- the DEST parameters are ids 55 and 64 (flags `+0x24` `0x20000` and `0x10000`); their value is a slot;
- the DEST knob's picture, `0x40065d3e`, finds the current track's set (project, `FUN_4001d24e`,
  `FUN_4000d19c`), maps the slot to an id (the set's `vfunc_20`), and draws the descriptor's group
  (`+0x2c`, std::string at `0x40065dec`, 4 letters uppercased: SAMP) over its short name (`+0x30`,
  `0x40065e5e`);
- the destination list's label, the lambda `0x400a4448` (installed at `0x400a4c2a`), formats
  `"%.16s:%.32s"` of the page's prefix (`FUN_4007914c`, SAMP) and the long name (`+0x28`,
  `0x400a44d0`), or the short name (`+0x30`, `0x400a454c`) when that is too wide.

S23 hooks those four places. `cur_machine` finds the current track's machine as the picture finds the
track and the stock layout lookup its machine (`FUN_4000d9c8`, the holder at the kit's
`+0x60 + 200 × track`); for ids 108–115 on CFOO the group and prefix become `CFOO` and the names
CFOO's (short on the knob and in the fallback, long in the list).

**Checked:** `cur_machine` with the project lookups stubbed and the stock `FUN_4000d9c8` and
`FUN_4002200a` run on a holder (tracks 0, 3, 7; −1 for 8); the four routines for ids 106–117 and 0
with the machine 5 and 0: CFOO's strings only for 108–115 on 5, the descriptor's otherwise, the list's
stack (prefix, name) and its kept `%a0`; the four sites in place. Controls: the fallback's divide off
by one, and the kit lookup replaced by the wrong one, each fail their own cases.

## Names at the four points, in S24

Built with `--defsym CORNERS=1` on top of S23. In `cfo_text` (the popup and the cell's text), A, C and D
at 0, 42, 85 and 127 (the integer part, at most 127, as the synth reads it: S20's pure points) read
`SIN`, `TRI`, `SAW`, `SQR`, and E at the same values `OSC1`, `1+2`, `123`, `2+3`; any other value, and F
always, is the number.

**Checked:** S18's text cases (4,168 values, popup and cell) against the model with the names. Controls:
a name's letter changed, and the 42 test moved to 43, each fail those cases.

## The SRC page's own texts, in S25

Built with `--defsym SRCNAME=1` on top of S24. On the unit (OS 1.54), as reported: a POLY or CFOO
track's SRC page still shows a sample name. ✅ Read in the code (objdump): two SRC-page texts format
`"%s: %.16s"` of the machine's short name (`0x4007912c`) and a sample name, whatever the machine:
- the page's title, `FUN_4003a638`: SAMP's slot (id 111, on CFOO WAV3's value) through `0x4002072a`,
  `sprintf` into the page's `+0x1b0`, the machine `FUN_4002b5d4(page)`;
- a popup in `SamplePageView::vfunc_2` (`0x4003b512`): the slot of the machine's Sample Slot id (table
  `0x40184a0c`, id 0 above machine 3), the machine `FUN_4002200a` in `%d4`. Its `jsr 0x40093ab0` is
  shared with a `"Sound: %d %s"` text.

Each pushes the format with `pea 0x401c41c0` (`0x4003a6ce`, `0x4003b512`); S25 replaces both with a call
that pushes `"%s"` for machines 4 and 5 and the stock format otherwise.

**Checked:** both entries for machines −1 to 6: `"%s"` only for 4 and 5, where the `pea` put it, the rest
of the stack and the kept registers unchanged; the two sites in place. Control: the title's machine read
from the wrong stack slot fails it.

## A POLY track's Source, in S26

Built with `--defsym POLYTEXT=1 --defsym SHORT=1` on top of S25. The three texts of a track's machine
and sample, the [TRK] popup (`FUN_4003bbfe`), the SRC page's title (`FUN_4003a638`) and its popup
(`SamplePageView::vfunc_2`), each pass the format, the machine's short name and the sample name, in
that order, to their formatter (`FUN_40093ab0`; `sprintf`, `0x40000e82`, for the title). ✅ Read in the
code (objdump):
- the [TRK] popup's machine is the track's own (`%d5`), the track its argument (`%fp@(8)`);
- the SRC popup's machine is the track's own (`%d4`, the track `FUN_4001d24e(page +0x74)` in `%a3` for
  a machine above 3);
- the title's machine is `FUN_4002b5d4(page)`, which POLY's alias (its patch at `0x4002b691`) turns into
  the page's Source's: a POLY track's SRC page shows the Source's parameters, and so its machine. The
  page's own track is `FUN_4001d24e(page +0x74)` (stock at `0x4003a648`);
- POLY's pools: `groupSource[track]` at `0x439d1050` is the track's Source, itself for a track outside
  any pool, as its alias pads read it (`0x400bed74`: a track 0..7 → `groupSource[track]`).

S26 points the three formatter calls (`0x4003bd6a`, `0x4003b51c` — shared with a `"Sound: %d %s"` text,
which is left alone — and `0x4003a6d8`) at routines that hand the track's own machine and the track to
`src_fmt`: CFOO gets `"%s"`; POLY gets `"%s: %s"` of `POLY` and the short name of its Source's machine
(`trk_machine`, as `cur_machine` finds a machine; the short-name reader `0x4007912c`), or `"%s"` when
the track is its own Source; anything else is left as it is. S22's and S25's sites are stock again.
`cur_machine` now goes through `trk_machine`. The build's calls between its own routines become `bsr.w`
(2 B shorter each): the code is 2,370 B and the pad has 2 B left.

**Checked:** `src_fmt` for machines −1 to 6 on tracks 0–7 and 9, on a layout of pools (machines 4, 5, 4,
4, 3, 4, 2, 4; Sources 0, 1, 1, 1, 4, 4, 6, none), with `trk_machine` and the name reader stubbed by
argument (72 cases): `POLY: CFOO` for tracks 2 and 3, `POLY: SLIC` for 5, `POLY` alone for 0 and 7,
`CFOO` for 1, others unchanged; the three routines with their formatters stubbed, the SRC popup's other
format untouched; `trk_machine` on the stock `FUN_4000d9c8`; and every earlier case, on the `bsr.w`
code, on S26's own bytes. The changed runs outside the pad and the tables are the operands that follow
the code, S25's two sites back to stock, and the three new call operands. Controls: the pool table's
address, the popup's track slot and the Source name's slot, each one off, fail their own cases.

## The Source from the machines, in S27

Built with `--defsym POLYSRC=1` on top of S26. On the unit S26 showed POLY tracks without their Source
(above). ✅ Read in the code (objdump of the build): POLY's pool map, `groupSource` at `0x439d1050`, is
built by `0x400bf17c` from the engine's copy of the machines (`*(0x800019ac) + 0x9e + 0xa2 × track`): a
POLY track takes the Source of the track before it, any other track is its own. It runs after the
machine setter (POLY's return hook at `0x40037a24`) and after `FUN_400e0cb0` (`0x40037a3c`), and the
table lies above `.bss`, so start-up does not clear it: it can hold a track as its own Source while the
pages already show it as POLY. Run on S26's bytes with such a map and the expected texts below, the
emulator shows `POLY` alone, as the unit did.

S27's `src_fmt` follows the builder's rule on the machines the pages show (`trk_machine`): from the
track before, back past POLY tracks, to the first that is not POLY; past track 1 there is no Source. The
pool map is not read. 4 B smaller than S26's lookup.

**Checked:** S26's case set against the rule on the machines (POLY on tracks 0, 2, 3, 5, 7: no Source,
CFOO, CFOO, SLIC, PTCH), with the pool map filled with every track as its own; the three routines and
everything else as S26, on S27's own bytes. Controls: S26's bytes under that expectation (the argument
`polywalk`) show `POLY` alone, and S27 with machine 3 taken for POLY fails.

## Related notes

- [landing_pads.md](../landing_pads.md): the pads and the candidates.
- [memory_map.md](../memory_map.md): the RAM.
- [function_ledger.md](../function_ledger.md): the render functions.
- [scripts/emu/README.md](../../scripts/emu/README.md): `EmuCfoOscillator`.
