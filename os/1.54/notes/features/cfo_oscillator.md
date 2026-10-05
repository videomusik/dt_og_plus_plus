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

⚠️ Not replicated: the store loop's ramp from one tick's level to the next. The synth holds each tick's
level for its 32 samples, so a level change is a step every 667 µs.

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
- the level: each synth track calls `FUN_40074c60` once, with its own `x` and its LEV, and the voice's
  own level, set to a wrong value throughout, is not used; the de-click gives level 0 only to a voice
  that is on and has a trig next tick;
- the phases carry over from tick to tick;
- `FUN_40072478` is reached with the stack as found and `%d2-%d7/%a2-%a6` intact.

The emulator's EMAC has no fractional mode ([notes/emulator.md](../../../../notes/emulator.md)), so the
harness stubs `FUN_40074c60`: it checks the arguments and returns the case's level.

A pure SIN at note 60 measures 261.47 Hz with a peak of 1,065,320,704. The worst tick, all eight tracks
on the synth, takes 15,724 instructions, plus about 17 per track inside the stubbed `FUN_40074c60`.
⚠️ How that compares with the ISR's free time per 667 µs tick is not measured.

Controls:
- a variant with the pitch table one entry off and OSC3's mix gain swapped fails every case except the
  two with no synth track and level 0;
- the earlier code, which read the voice's level, makes no level call and fails all 20 synth cases.

## Code space

The code is 672 B in seven routines: dispatcher 82, `track` 314, `pitch` 56, `wave` 32, `gains` 58,
`osc32` 38, `osc1_mix` 92. The pads in use have 112 B free, in blocks of at most 18 B
([landing_pads.md](../landing_pads.md)). The code goes into `FUN_400f77da`, route 2 below, under the
vetting recipe's exception for an identified library function
([landing_pad_method.md](../../../../notes/landing_pad_method.md#vetting-a-new-pad)); it uses 672 B of
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

`make_cfo.py lz4_stream.ld --stages` builds six images on top of the reference build (section 3
`efc90606…`, `.syx` `3fd4b0a3`), each adding one step to the stage named in its row. They are meant to
be flashed in order, S9 to S14:

| Stage | `.syx` | Section 3 | Contents |
|---|---|---|---|
| S9 | `9a75ccc7` | `af736dd4…` | the reference build + `FUN_400f77da` filled with `clrl %d0 ; rts` (the pad's fill test) |
| S10 | `3af01e48` | `7328128f…` | S9 + the hook at `0x40077fc8`, its pad only jumping on to `FUN_40072478` |
| S11 | `2122f403` | `57fb1aaa…` | S9 + the CFO oscillator prototype, playing on ONESHOT with SAMP OFF |
| S12 | `235a54ce` | `a22d0de9…` | S9 + the machine CFOO (machine 5, after POLY) with ONESHOT's SRC page, and the synth playing on it ([below](#cfoo-machine-5-in-s12)) |
| S13 | `8baba164` | `8eae5616…` | S12 + CFOO's own parameter names on its SRC page ([below](#cfoos-own-parameter-names-in-s13)) |
| S14 | `413bb231` | `412a760f…` | S13 + a placeholder picker icon for CFOO ([below](#cfoos-picker-icon-in-s14)) |

Checked on the built images (S12's own checks are [below](#cfoo-machine-5-in-s12)):
- the build's own checks;
- the compressor window, whose largest back-reference is `0xffc6a`;
- the hook, code and data read back out of S11 equal the assembled sections;
- `EmuCfoOscillator` passes on S11's own bytes, and on S10's bytes (the inert control) it fails all 20
  synth cases and passes the untouched ones;
- every other harness passes on S11;
- S9 and S10 rebuild byte for byte whenever the synth code changes, since neither contains it.

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
- **S13:** a CFOO track's SRC page labels read TUNE, FMSR, MIX, SAMP, WAV1, FM, WAV2 and LEV, and the
  encoder popups read Tune, FM Source, Osc Mix, Sample Slot, OSC1 Wave, FM Amount, OSC2+3 Wave and Level.
  A ONESHOT track's page is unchanged, and so is a MIDI track's CHAN/TRK label. The values themselves
  still show as ONESHOT shows them, for example PLAY's play-mode pictures on FMSR.
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

⚠️ The other way round is not covered: a POLY track right after a CFOO track takes the CFOO track as its
source (the map copies the source of the track before it). Its machine byte stays 4, so `cfo_pad`
leaves it alone, and it would play what the lanes make of its sample settings, not the synth. A
polyphonic CFOO would need `cfo_pad` to run for such followers too. Not traced further.

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
  for machines 0–7 and a ONESHOT track with SAMP OFF left alone;
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
`+0x18`, not spare rows. So the eight records have to come from somewhere else:

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

## Related notes

- [landing_pads.md](../landing_pads.md): the pads and the candidates.
- [memory_map.md](../memory_map.md): the RAM.
- [function_ledger.md](../function_ledger.md): the render functions.
- [scripts/emu/README.md](../../scripts/emu/README.md): `EmuCfoOscillator`.
