# Portamento: PORT and LEG on the TRIG page

Not in the build: test images on top of the CFO oscillator's last stage, S27
([cfo_oscillator.md](cfo_oscillator.md)). Source: [src/portamento/](../../src/portamento/).

## What it does

Every audio track's TRIG page gets two knobs, G and H, which are empty in stock:

| Knob | Name | Range | What it does |
|---|---|---|---|
| G | PORT, Portamento | 0–127, default 0 | the glide time; 0 is off |
| H | LEG, Legato | OFF/ON, default OFF | ON: only a legato note glides |

- With PORT above 0 a new note starts from the pitch the track is playing and glides to its own pitch.
  With LEG OFF every note glides. With LEG ON only a legato note glides, one whose trig comes while the
  last note still sounds (its gate still open, so its LEN reaches the new trig); any other note starts
  on its own pitch.
- A note change without a trig (a trigless lock with a NOTE lock) glides as well.
- PORT and LEG belong to the sound, like the AMP page's parameters: they are saved with it and can be
  locked per trig.
- The glide applies wherever the stock rate loop takes the pitch from the trig's note: SAMP, WERP,
  SLICE when its pitch follows the note, each POLY voice (from that voice's last note), and the CFO
  oscillator's OSC1 (OSC2 and OSC3 follow at their detunes). Where the stock code ignores the note,
  nothing glides: REPITCH while its rate comes from the tempo, and SLICE when its Slice Select is 0
  (then the rate loop plays note 60, ⚠️ very probably because the note picks the slice).

**The glide.** Every audio tick (32 samples, 0.67 ms at 48 kHz) the note moves by 1/k of the distance
left, with k = 1 + PORT² / 8, and arrives when that step rounds to 0 (less than k / 65,536 of a
semitone). It is an exponential approach in semitones, with the time constant k ticks:

| PORT | 8 | 16 | 32 | 48 | 64 | 96 | 127 |
|---|---|---|---|---|---|---|---|
| time constant | 6 ms | 22 ms | 86 ms | 193 ms | 342 ms | 769 ms | 1.34 s |

About 2.5 time constants bring an octave's glide within a semitone of its target.

## How

### PORT and LEG as sound parameters

✅ Read in the code (objdump), unless marked:

- **The rows.** The 164 parameter descriptors (the table:
  [cfo_oscillator.md](cfo_oscillator.md#the-src-page-layout-and-names)) have no spare row, but rows 4
  and 5 are not used as parameters. They read `Error`/`ERR`, page and slot −1, and carry the MIDI words
  of CC 7 and CC 10. The descriptor sorter `FUN_40078b20`, which builds every CC → id table, skips a
  page −1 row entirely (its page tests fall through to the loop's end), so CC 7 and CC 10 reach VOL
  (id 51) and PAN (id 50) through those two rows' own words. ✅
  Emulated: running the stock sorter on the stock rows and on this build's rows gives the same tables
  except two words (below).
- **The slots.** A sound has 53 value slots (`SoundParameterSet::vfunc_10`); the sound's rows use 1–45
  and the master rows' 43–51 belong to another set (pages 7–10). Slots 0 and 46–52 of a sound are named
  by no sound row. No instruction names slot 46 or 47 of any track in the ISR's copy of the values
  (`0x80001502 + 106 × track + 92/94`) or in the engine's smoothed copy (`0x80002772 + 106 × track +
  92/94`), and the displacements 112 and 114 (`+0x70`, `+0x72`, the slots in a sound) appear only on
  the stack, in other objects and in copy loops (a scan of the whole listing, read by hand). ⚠️ That
  stock sounds and projects hold 0 there rests on that; checked on the unit with S30.
- **Rows 4 and 5 become** page 5 (a sound page no stock row uses: `FUN_400191fe` gives a page 0–6
  row the sound's set, and the sorter files pages 4–6 by slot into `0x4199f8f0`), slots 46 and 47,
  ranges 0–127.0 and 0–1.0, defaults 0, no CC and no NRPN (`0xffffffff`, as Micro Timing's and PROB's;
  the CC readers `0x40079084` and `0x400790a6` then give none), flags 0 (as the TRIG rows: no LFO
  destination), no group, the names `PORT`/`Portamento` and `LEG`/`Legato` in the `.rodata` padding at
  `0x40252f00`. The word at `+0x20` keeps 4 and 5: it is the
  number `FUN_4006cc38` sends in a 6-byte message (⚠️ to what, not traced). The `+0x14` word stays 0
  (it is 0 on the TRIG rows).
- **The slot map.** The sorter now puts 4 and 5 into `0x4199f8f0` at slots 46 and 47: the map
  `FUN_40078f44(slot, machine)` uses for every slot outside the SRC slots, so a value in slot 46 or 47
  (a p-lock among them) finds its parameter.
- **The display objects** (`0x4197e2f8 + 0x54 × id`, built once at start-up by `FUN_40152280`). Each id
  copies a 16 B template (pointing to the encoder's 28 B step profile) and callables for its text and
  picture from shared objects. Rows 4 and 5 had the knob template `0x4018e18c` (whole steps, as VEL,
  ATK and BR), the text `0x4197d7ec` (`%s%d.%02d`) and the picture `0x4197d58c` (the plain knob). Four
  operand edits in that routine:
  - id 4's text → `0x4197d7fc`, `%d` of the integer (VEL's, ATK's);
  - id 5's template → `0x4018e1ac`, the selector profile (FLT.T's, LFO.T's, PLAY's): its push
    `movel %d2,%sp@-` becomes `movel %d3,%sp@-`; `%d3` holds `0x4018e1ac` there (the routine has no
    branch, its one earlier write to `%d3` is `movel #0x4018e1ac,%d3` at `0x401522ba`, and the
    routines it calls keep `%d3`);
  - id 5's text → `0x4197d6ac` (`OFF`/`ON`) and picture → `0x4197d37c` (the switch), FLT.T's and
    LFO.T's.

  ⚠️ LEG keeps its object's word `+0x00` at 0; FLT.T and LFO.T have 4 there. The cell drawer
  (`MachineParameterPageView::vfunc_37`) tests that bit 2 and, when set, skips the value text it would
  otherwise draw while the knob is touched (on pages whose type is not 5). Writing 4 there takes a
  longer instruction than the `clrl` it would replace, so LEG may show `OFF`/`ON` text where FLT.T
  shows none.
- **The TRIG page.** Its layout record `0x4197dfb4` (NOTE, VEL, LEN, PROB, FLT.T, LFO.T, then 0, 0,
  and Track Level) is written once at start-up by `FUN_40152280`, which runs only from the C++
  static-initialiser table (its one pointer, `0x40252604`, walked once by the init task's loop at
  `0x40068fea`). The record lies in `.bss`, zeroed before the initialisers run (`FUN_400004b2`). So
  `clrl 0x4197dfd4` and `clrl 0x4197dfd8` become `addql #4` and `addql #5` of the same size: knobs G and H
  get ids 4 and 5. MIDI tracks have their own TRIG record (`0x4197e21c`) and are not touched.

### The glide in the audio ISR

✅ Read in the code (objdump):

- **Where the note is.** The note-on (`FUN_40077420`, event type 1, flag `0x10000`) stores the trig's
  note sum (note << 16) in `NOTES[track]` (`0x80001f28`), before it sets the track's gate bit
  (`0x800019f4`, at `0x40077b0c`) and before it copies the trig's sound values (`FUN_40077282` at
  `0x40077b36`, then the p-locks through `FUN_40074b0a`). So at the store the gate bit is still the
  last note's: set if it is still sounding. A second store (`0x4007787e`, an event with flag bit 0 and
  no velocity) changes the note without a trig; its flag `0x200` clears the gate.
- **Where the pitch is made.** The first render stage `FUN_40075184`, called after the event loop,
  computes every track's rate every tick: its loop (`0x40075690`) reads `NOTES[track]` into `%d6`, adds
  TUNE and looks the sum up in the pitch table. `%a2` walks the engine's smoothed copy of the values
  (TUNE's slot, `0x80002794 + 106 × track`); the ISR's own copy, which p-locks write and which is not
  smoothed, is a fixed distance away (`−0x1236`).
- **The note-on hook** (`0x400779f6`, `lea NOTES,%a1` → `jsr port_on`, 6 B): sets the track's bit in
  the new-note byte and copies its gate bit into the legato byte, then does the `lea`. It uses no
  register: bit operations on memory, the gate's low byte `0x800019f7`.
- **The rate-loop hook** (`0x40075690`, 12 B: `lea NOTES,%a0`, `moveq #3,%d1` and the `movel` into
  `%d6` → `jsr port_glide`, `moveq #3,%d1`, `bras` over a filler word): returns the glided note sum in
  `%d6`. It uses `%d0`, `%d4`, `%d5` and `%a0`, which the loop loads again before reading; `%d2` and
  `%d3` (read in track 0's pass before the loop sets them) and `%d7` (read after the loop) are kept, as
  is every other register. No instruction branches into either site's replaced bytes (the build's own
  scan).
- **The CFO oscillator** reads its note through `lea NOTES,%a0` (`0x400f783e` in S27); its operand
  points at the glided notes. The rate loop runs before the oscillator in the same tick.
- **State** (40 B at `0x439d1180`, above `.bss`, [memory_map.md](../memory_map.md)): the note sum
  each track plays (8 longs), a marker word, and the new-note, legato and valid bytes. The start-up
  contents are garbage: until the marker reads `PORT` no track's note is valid, and a track whose note
  is not valid starts on its target.

### Code space

Two new pads, vetted on this image ([landing_pads.md](../landing_pads.md#the-portamento-pads-fun_400e6d1c-and-fun_400ee05e)):
`port_on` in `FUN_400e6d1c` (36 of 108 B), `port_glide` in `FUN_400ee05e` (112 of 122 B). The rest of
each pad keeps its fill. Neither hook calls anything.

## Checks

- **The build** (`make_port.py`): S27 rebuilt with `make_cfo.py`'s own routines gives its recorded
  section-3 hash; every touched range is stock in the stock image, untouched by `patch.json` and by
  S27; each section lies in its pad or in the zero `.rodata` padding; no stock instruction branches
  into a hook site; the CFO oscillator's note read is found exactly once. Each stage image passes
  `build.py`'s checks (protected ranges, the MAIN OS section only, round trip). Extracted again, S28
  to S31 differ from S27 only in the ranges listed, and every other section equals S27's. Rebuilding
  gives every image byte for byte.
- **`EmuPortamento`** ([scripts/emu/README.md](../../scripts/emu/README.md)) runs each hook at its site
  in the stock ISR code and compares the rate loop's `%d6` with a model, every track, every tick:
  PORT 0 at once; a detached note glides up, and down; PORT 16 arrives exactly (347 ticks) and stays;
  LEG ON: legato glides, detached starts on its pitch, the target note again detached mid-glide
  starts on it; a note change without a trig glides; PORT set to 0 mid-glide jumps; eight tracks at
  once with their own settings; power-up garbage. Registers and stack kept at both sites. With the
  TRIG data: the rows and names; the stock sorter's tables, the same as from the stock rows but for
  the slot map's 46 → 4 and 47 → 5 (CC 7 still VOL, CC 10 still PAN); the layout's stores (G = 4,
  H = 5); the display build for ids 4 and 5 with its copy routines stubbed (template, text and picture
  sources). The inert stages replay the stock instructions and leave the state alone. Ten one-change
  controls each fail their own cases and pass the others: the legato test inverted, PORT read from
  slot 45, no start on the target at power-up, every note taken as legato, k with PORT² / 4, the site's
  `moveq #2`, LEG's step profile left as the knob's, LEG in slot 46, knob H = id 4, and the CFO
  oscillator's note read left on the trig's.

## Stage images

Each stage adds one thing to the one before; flash them in order on a unit that runs S27.

| Stage | `.syx` | Section 3 | Contents |
|---|---|---|---|
| S28 | `1aeeaf16` | `500a52e8…` | S27 + `FUN_400e6d1c` and `FUN_400ee05e` filled with `clrl %d0 ; rts`: their fill test |
| S29 | `40945186` | `fdf804d3…` | S28 + both hooks, each pad only replaying what its hook replaced |
| S30 | `16e4e6a5` | `8cde020f…` | S29 + PORT and LEG on the TRIG page (rows, names, display objects, layout); nothing glides yet |
| S31 | `31292184` | `ad0e5c03…` | S30 + the glide, and the CFO oscillator's note read on the glided note |

**What to check on the unit (OS 1.54):**

- **S28:** everything works as on S27, for a while of ordinary use: patterns on every machine, POLY
  and CFOO tracks, the sampler, MIDI in and out, USB, saving and loading. A difference of any kind
  means one of the two pads is live.
- **S29:** as S28; every note plays its own pitch, nothing glides, the TRIG page is unchanged.
- **S30:** on an audio track's TRIG page, G shows PORT (0–127, whole steps) and H shows LEG (OFF/ON, a
  switch). Both turn, reset to 0/OFF, lock on a trig, and survive saving and reloading the project and
  changing sounds. Existing projects and a new sound show PORT 0 and LEG OFF. Nothing sounds different.
  MIDI CC 7 and CC 10 still set VOL and PAN, and turning PORT sends no CC. A MIDI track's TRIG page is
  unchanged; the LFO destination list has no PORT or LEG. Note how LEG's cell looks next to FLT.T's.
- **S31:** PORT 0 plays as before. PORT 32 with LEG OFF: every new note glides (time constant about
  0.1 s), slower as PORT rises. LEG ON: notes whose LEN reaches the next trig glide, the others start on their pitch.
  A PORT lock on one trig changes that note's glide only. On a POLY track each voice glides from its
  own last note. On a CFOO track OSC1 glides and OSC2 and OSC3 keep their detunes.

## Open points

- ⚠️ The two new pads are dead by every scan of the recipe, but only S28 on the unit settles it.
- ⚠️ Slots 46 and 47 unused in stock: by the scans above; S30 shows what existing projects hold.
- ⚠️ A lock on PORT or LEG is expected to work as for the AMP page's parameters (the slot map, and
  `FUN_40074b0a` writing the ISR's copy by slot); the sequencer's lock store is not traced.
- The glide is stepped once per tick (0.67 ms); on the shortest glides that is 1,500 steps a second.
- ⚠️ LEG's `+0x00` word (above).
- Code space left: 10 B in `FUN_400ee05e`, 72 B in `FUN_400e6d1c`.
- The feature is not in `patch.json`.
