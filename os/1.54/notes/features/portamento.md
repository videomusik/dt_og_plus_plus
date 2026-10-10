# Portamento: PORT and LEG on the TRIG page

In the build: `os/1.54/build/patch.json` holds it as the feature `portamento`, its last stage, S36,
less the CFO oscillator's last stage, S27, on which the stages are built
([cfo_oscillator.md](cfo_oscillator.md)). v0.2.1 is S36, byte for byte; from v0.2.2 FILTER page 2
follows it and rewrites the reader's call, the two lookups' tests and the writer's loop count, which
`patch.json` then lists under `filter_page2` ([filter_page2.md](filter_page2.md#checks)). Source:
[src/portamento/](../../src/portamento/).

## What it does

Every audio track's TRIG page gets two knobs, G and H, which are empty in stock:

| Knob | Name | Range | What it does |
|---|---|---|---|
| G | PORT, Portamento | OFF, 1–127, default OFF | the glide time; OFF (0) bypasses the glide |
| H | LEG, Legato | OFF/ON, default OFF | ON: only a legato note glides, and it does not restart the amp envelope |

- With PORT above 0 a new note starts from the pitch the track is playing and glides to its own pitch.
  With LEG OFF every note glides. With LEG ON only a legato note glides, one whose trig comes before
  the last note's NoteOff, the end of its LEN (from S36; a LEN that ends in the very tick of the new
  trig counts as reaching it, and LEN INF, which has no end, counts as ended); any other note starts
  on its own pitch. S31 to S34 took a note as legato when the track's gate bit was set, which a
  sequenced trig leaves set after its LEN: there, every note after the first was legato. S35 took it
  from the amp envelope's state, which a legato note itself keeps from restarting: notes of equal
  LEN then alternated.
- With LEG ON a legato note also leaves the amp envelope running, as on a mono synth in legato mode
  (from S34): its attack does not restart. That holds whatever PORT is. The filter envelope keeps its
  own control, FLT.T.
- At PORT OFF every tick plays the trig's own note sum, exactly what the stock code plays.
- A note change without a trig (a trigless lock with a NOTE lock) glides as well.
- PORT and LEG belong to the sound, like the AMP page's parameters: they are saved with it and can be
  locked per trig (saved with the project from S33; S31 and S32 lose them on a reload).
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

  LEG keeps its object's word `+0x00` at 0; FLT.T and LFO.T have 4 there. The cell drawer
  (`MachineParameterPageView::vfunc_37`) tests that bit 2 and, when set, skips the value text it would
  otherwise draw while the knob is touched (on pages whose type is not 5). Writing 4 there takes a
  longer instruction than the `clrl` it would replace. ✅ On the unit (S31, as reported) LEG's cell
  shows `OFF`/`ON` text while it is turned, as this predicts.
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
  `0x40077b36`, then the p-locks through `FUN_40074b0a`). A second store (`0x4007787e`, an event with
  flag bit 0 and no velocity) changes the note without a trig; its flag `0x200` clears the gate.
- **When a note ends.** ⛔ The gate bit does not say whether the last note still sounds. Only the event
  paths clear it (`0x400778b0`, and a note-off whose stored id and note match, `0x40077c34..0x40077c46`).
  A sequenced trig's length is a countdown instead: the note-on loads `0x8000196c + 4 × track` (amp)
  and `0x8000198c + 4 × track` (filter envelope) from the event's `+48`; at the start of each tick the
  ISR counts both down (`0x400774dc..0x40077532`) and, where one crosses zero, sets the track's bit in
  the release masks `%fp@(-76)` and `%fp@(-72)`, which reach the amp and the filter envelope. The
  gate bit stays set. So S31 to S34, which took the gate bit as "the last note still sounds", took
  every sequenced note after the first as legato (on the unit with S34: LEN 1/8, HOLD at NOTE, LEG ON,
  silence). LEN INF loads 0 (`0x40191590[128]`), a countdown that never runs out; a finite LEN loads a
  positive count (168,750 per table step), which stays at its first value at or below 0 once it has
  run out. S35 used the amp envelope's state (`0x4199ed64 + 8 × track`: 2 attack, 1 hold, 0 decay;
  `FUN_400716c0`) instead; but a legato note does not restart the envelope, so the next decision
  depends on the last one: on the unit, notes of equal LEN alternated. ✅ S36 reads the NoteOn/NoteOff
  pattern itself, the countdown, at the NoteOn (after this tick's countdown pass, before the store of
  the new note's): above 0, the last note still runs; at or below 0, it has ended, LEN INF included
  (so no note can hold an envelope forever). One exception keeps a LEN equal to the trig distance
  steady: at 120 BPM a step is 187.5 ticks, trigs come 187 and 188 ticks apart and a one-step countdown
  runs out after 188, in the very tick of every second trig; stock then drops that release for the
  track (the release mask is `~%d3 & %fp@(-76)`). So a countdown that ran out in this tick (its bit in
  `%fp@(-76)`) counts as running.
- **Where the pitch is made.** The first render stage `FUN_40075184`, called after the event loop,
  computes every track's rate every tick: its loop (`0x40075690`) reads `NOTES[track]` into `%d6`, adds
  TUNE and looks the sum up in the pitch table. `%a2` walks the engine's smoothed copy of the values
  (TUNE's slot, `0x80002794 + 106 × track`); the ISR's own copy, which p-locks write and which is not
  smoothed, is a fixed distance away (`−0x1236`).
- **The note-on hook** (`0x400779f6`, `lea NOTES,%a1` → `jsr port_on`, 6 B): sets the track's bit in
  the new-note byte and marks the note legato, then does the `lea`. From S36 it marks it legato when
  the countdown `0x8000196c + 4 × track` is above 0 or its bit is set in the low byte of the ISR
  frame's `%fp@(-76)` (`%fp@(-73)`), and only sets the legato bit, which `port_glide` takes with the
  new-note bit (as in S35, which read the amp envelope's state); S31 to S34 copied the gate bit (the
  low byte `0x800019f7`). It changes only `%a1`, which the replaced `lea` loads.
- **The rate-loop hook** (`0x40075690`, 12 B: `lea NOTES,%a0`, `moveq #3,%d1` and the `movel` into
  `%d6` → `jsr port_glide`, `moveq #3,%d1`, `bras` over a filler word): returns the glided note sum in
  `%d6`. It uses `%d0`, `%d4`, `%d5` and `%a0`, which the loop loads again before reading; `%d2` and
  `%d3` (read in track 0's pass before the loop sets them) and `%d7` (read after the loop) are kept, as
  is every other register. No instruction branches into either site's replaced bytes (the build's own
  scan).
- **The CFO oscillator** reads its note through `lea NOTES,%a0` (`0x400f783e` in S27); its operand
  points at the glided notes. The rate loop runs before the oscillator in the same tick.
- **State** (40 B at `0x439d1180`, above `.bss`, [memory_map.md](../memory_map.md)): the note sum
  each track plays (8 longs), a marker word, and the new-note, legato and valid bytes, and from S34
  the held byte. The start-up contents are garbage: until the marker reads `PORT` no track's note is
  valid (and, from S34, no envelope held), and a track whose note is not valid starts on its target.

### PORT's OFF and LEG's cell (S32)

✅ Read in the code (objdump):

- **The texts.** No stock text prints `OFF` at 0 and the number above it. The sends' text
  (`0x4197d76c`, invoker `0x400658d0`) prints `OFF` at 0 but `%s%d.%02d` otherwise, the text of
  `0x4197d7ec`. So PORT gets its own text callable: a 16 B object in the `.rodata` padding (storage 0,
  the `%d` text's manager `0x40060c92`, the invoker `port_text`), whose invoker tail-jumps to
  `0x400658d0` for 0 and to the `%d` invoker `0x4005f8ce` otherwise. The display build copies a source
  with `0x40151f6c`, which copies the manager and the invoker and calls the manager to clone the
  storage; this manager's clone only allocates a fresh byte (`new(1)` through `0x400d43a8`) and reads
  nothing of the source. Id 4's text operand (`0x40153410`) points at the object. The popup formatter
  `FUN_400657ee` reaches the invoker through `FUN_40151eea` with `(object, value, buffer)`.
- **LEG's flag word.** `clrl 0x4197e49c` (`0x40153446`) is that word's only writer, the word lies in
  `.bss` and the build runs once ([above](#port-and-leg-as-sound-parameters)), so `addql #4` writes
  FLT.T's 4: the cell drawer then shows no value text while LEG is turned, as for FLT.T.

### Saving PORT and LEG (S33)

✅ Read in the code (objdump, decompile):

- **The stored sound.** A saved sound is a 160 B record (`0xbeefbace`, version 3, its name, then its
  values at `+0x1c` as 46 words, the machine at `+0x7c`, …, `0xbacef00c` at `+0x9c`). The writer
  `FUN_4007a5a0` clears `+0x1c..+0x7b` and stores slot s at `+0x1c + 2 × I[s]` for the slots 0–45,
  through the table `I` at `0x401ac4d4` (slot → stored index); the reader `FUN_4007a236` clears the
  sound's 106 B of values and loads them back through `F` at `0x401ac58c` (stored index → slot). So
  slots 46–52 are never written: in S31 and S32, PORT and LEG come back as 0 after a reload (as the
  unit showed). `+0x78..+0x7b`, two words, lie inside the writer's clear and outside everything the
  reader reads.
- **The stored p-locks.** A pattern's locks are kept in RAM per track, step and slot (16 tracks of
  `0x1b35` B: 64 steps × 53 words and a count, then a byte per slot). They are stored as up to 80
  records of `0x82` B: the stored index, the track, 64 words. The writers `FUN_4007adb2` (all) and
  `FUN_4007aefa` (one) take the index from `I` for any slot up to 52; past its 46 entries they read
  the start of `F`, 0 and 1, so in stock a lock on slot 46 is stored as a lock on slot 0 and one on
  slot 47 as a lock on slot 1, LFO1's speed. The reader `FUN_4007abb2` maps an index through
  `FUN_40079738(track, index)`, which gives slot 0 for an index above 45.
- **The mirror.** `Sound::vfunc_17` keeps a stored copy of the sound up to date as values change:
  for one changed slot through `FUN_40079772(0, slot)` (slot → stored index, 0 above 45), else by a
  full rewrite through the writer. MIDI presets (kind 8, slots up to 40) and the FX setup (kind 16,
  its own tables) use the same function.
- **S33:** a 48-entry `I` in the `.rodata` padding (the stock 46, then 46 and 47) for the three writers
  (`0x4007a5fa`, `0x4007adec`, `0x4007af34`); the sound writer's loop takes 48 words (`moveq #92` →
  `#96` at `0x4007a614`), so PORT and LEG go to the spare words `+0x78` and `+0x7a`; `FUN_40079738`
  and `FUN_40079772` rewritten in place (56 of their 58 B, the rest `nop`): as stock for every input,
  and for a kind below 16 the index or slot 46 and 47 maps to itself; and the reader's `lea` of `F`
  (`0x4007a2aa`) calls `rd_hook`, which first fills slots 46 and 47 from the spare words. The value
  loop that follows writes slots 0–45 only. Spare words that are not a whole PORT of 0–127 and a LEG
  of 0 or 1 give 0 for both. ⚠️ The converter from the oldest record format (`FUN_4007b962`) copies
  every field but these two words, so a converted record may hold leftovers there; that is why the
  reader checks them.
- **Compatibility.** A sound saved by the stock firmware has zeros there: PORT OFF and LEG OFF. A
  project saved with S33 and opened on the stock firmware: the stock reader ignores the spare words,
  and its `FUN_40079738` gives a lock with index 46 or 47 slot 0, which no parameter uses.

### The amp envelope on legato notes (S34)

✅ Read in the code (objdump, decompile):

- **Which code is the amp envelope.** After the render the ISR builds a block at `%fp@(-60)` of each
  track's AMP ATK, HOLD and DEC (engine `+0x5e`, `+0x60`, `+0x62`, slots 38–40), then the note-on mask
  `%d3` at `%fp@(-36)` and a release mask at `%fp@(-35)` (`0x40078040..0x40078074`), and calls
  `FUN_400716c0`. That routine runs a three-state envelope per track over the track's 32 samples in
  place (state at `0x4199ed64 + 8 × track`: 2 attack, 1 hold, 0 decay), and restarts a track's attack
  for its bit in the note-on byte; with HOLD 127 the release byte ends the hold. The filter envelope is
  `FUN_40073304` (engine `+0x4c..+0x58`, slots 29–35), restarted by another mask (`%d4`, trigs with
  flag `0x200`); `FUN_40072844` is the filter.
- **S34:** `port_glide`, which already decides for each new note whether it is legato with LEG on,
  sets that track's bit in a held byte (state `+39`); the ISR's two `moveb`s into the block
  (`0x40078070`, 8 B) call `amp_hook`, which stores the note-on mask less the held tracks and the
  release byte, and clears the held byte. `%d3` stays as it is for the code after. The power-up
  initialisation clears the held byte with the valid byte (`clrw`), so garbage there cannot hold a
  first note's envelope; from S35 it clears all four flag bytes (`clrl`).
- A sample track's voice still restarts its sample at a legato note (the lanes' trig, flag `0x80`),
  with the lanes' own de-click; only the amp envelope carries on.

### Code space

Two new pads, vetted on this image ([landing_pads.md](../landing_pads.md#the-portamento-pads-fun_400e6d1c-and-fun_400ee05e)):
`FUN_400e6d1c` holds `port_on` (36 B), and from S32 on `port_text` (18 B), from S33 on `rd_hook` (28 B)
and from S34 on `amp_hook` (26 B): 108 of 108 B. `FUN_400ee05e` holds `port_glide` (112 of 122 B; 116 from S35).
The rest of each pad keeps its fill. No hook calls anything but the stock routines it jumps to.
`.rodata` padding: the names (27 B), from S32 PORT's text object (16 B), from S33 the 48-entry table
(192 B), `0x40252f00..0x40252fec`.

## Checks

- **The build** (`make_port.py`): the build without the CFO oscillator comes back from `patch.json`
  (`make_cfo.build_without_cfo`, Chain Recording's build, section 3 `efc90606…`); S27 rebuilt on it
  with `make_cfo.py`'s own routines gives its recorded section-3 hash; every touched range is stock in
  the stock image, untouched by that build and by S27; each section lies in its pad or in the zero
  `.rodata` padding; no stock instruction branches into a hook site; the CFO oscillator's note read is
  found exactly once. Each stage image passes `build.py`'s checks (protected ranges, the MAIN OS
  section only, round trip). Extracted again, S28 to S31 differ from S27 only in the ranges listed, and
  every other section equals S27's. Rebuilding gives every image byte for byte.
- **The merge** (`make_port.py --stages --write`): `patch.json` gets the CFO oscillator and portamento
  from S27 and S36. Every byte that S36 changes from stock is listed once, under the last feature that
  wrote it: portamento's, else the CFO oscillator's, else the earlier feature's. So the 23 bytes of
  `poly_engine`, `poly_icon` and `midi_loopback` that CFOO rewrites are listed under `cfo_oscillator`,
  and the CFO oscillator's 4-byte note operand (`0x400f7840`) under `portamento`; no other run of the
  earlier features changes. Runs are maximal per feature and kind; a run outside the code windows is
  data. `build.py` on the result gave `.syx` `9df62a0b…`, section 3 `d6fac1a3…`: S36, v0.2.1. Without
  `--write` the script reports whether `patch.json` is up to date; once a later feature is merged
  (FILTER page 2, v0.2.2) it checks S36 against that recorded hash instead and refuses `--write`. `make_chain.py` and `make_cfo.py`
  rebuild their own stages from the merged `patch.json` byte for byte, and `make_chain.py --write` is
  refused once features follow Chain Recording.
- **`EmuPortamento`** ([scripts/emu/README.md](../../scripts/emu/README.md)) runs each hook at its site
  in the stock ISR code and compares the rate loop's `%d6` with a model, every track, every tick:
  PORT 0 at once; a detached note glides up, and down; PORT 16 arrives exactly (347 ticks) and stays;
  LEG ON: legato glides, detached starts on its pitch, the target note again detached mid-glide
  starts on it; a note change without a trig glides; PORT set to 0 mid-glide jumps; eight tracks at
  once with their own settings; power-up garbage. Registers and stack kept at both sites. With the
  TRIG data: the rows and names; the stock sorter's tables, the same as from the stock rows but for
  the slot map's 46 → 4 and 47 → 5 (CC 7 still VOL, CC 10 still PAN); the layout's stores (G = 4,
  H = 5); the display build for ids 4 and 5 with its copy routines stubbed (template, text and picture
  sources). The inert stages replay the stock instructions and leave the state alone.
  From S32: the display build gives id 4 this build's text object and id 5 the flag word 4; PORT's
  text through the stock popup formatter reads `OFF`, `1`, `2`, `9`, `64`, `100`, `127`. From S33: both
  rewritten lookups against the stock code (copied and run beside them) for 567 inputs each, equal but
  for the 10 extended ones; a sound written by the stock writer and read back by the stock reader with
  the edits, slots 0–47 equal, PORT and LEG in the spare words; the reader on a stock record's zeros, on
  PORT 127 with LEG ON, and on four malformed spare pairs (0); p-locks on PORT, LEG and two other
  slots written and read back (records 7, 30, 46, 47); the single store of a LEG lock as index 47. From
  S34: every tick of every case runs the ISR's amp-mask site after the rate loop and compares the
  note-on byte with the model (this tick's note-ons less its legato notes with LEG on), the release
  byte, the cleared held byte and the registers; and a case of its own: legato with PORT 0 and LEG on
  held, legato with LEG off and detached notes restarted. From S35 the harness sets the amp
  envelope's state at each note-on as `FUN_400716c0` leaves it, and a case of its own: two tracks whose
  gate bits are left set, one whose last note ended (detached: restarted, no glide) and one whose
  last note still holds (legato: held, glides). From S36 a note-on finds the last note's countdown and
  this tick's run-out byte in the ISR frame, and a case of its own: a countdown running, ended, ended
  in this tick, 0 (LEN INF), 0 reached in this tick, and 1.
  Twenty-four one-change controls each fail their own cases and pass the others: the legato test inverted,
  PORT read from slot 45, no start on the target at power-up, every note taken as legato, k with
  PORT² / 4, the site's `moveq #2`, LEG's step profile left as the knob's, LEG in slot 46, knob H =
  id 4, the CFO oscillator's note read left on the trig's; PORT's text test inverted, LEG's flag word
  left 0; each lookup without 46 and 47, the sound writer's 46 words, the p-lock writer's stock table
  (which stores the PORT lock as index 0 and the LEG lock as index 1, as stock does), the reader taking
  any spare words; no legato note held, the held mask not inverted, the held byte not cleared at
  power-up; every note-on taken as legato (S34's fault on the unit), the legato bit not taken by the
  glide; a countdown that runs out in the NoteOn's tick taken as a NoteOff, a countdown of 0 taken as
  legato.

## Stage images

Each stage adds one thing to the one before; flash them in order on a unit that runs S27.

| Stage | `.syx` | Section 3 | Contents |
|---|---|---|---|
| S28 | `1aeeaf16` | `500a52e8…` | S27 + `FUN_400e6d1c` and `FUN_400ee05e` filled with `clrl %d0 ; rts`: their fill test |
| S29 | `40945186` | `fdf804d3…` | S28 + both hooks, each pad only replaying what its hook replaced |
| S30 | `16e4e6a5` | `8cde020f…` | S29 + PORT and LEG on the TRIG page (rows, names, display objects, layout); nothing glides yet |
| S31 | `31292184` | `ad0e5c03…` | S30 + the glide, and the CFO oscillator's note read on the glided note |
| S32 | `d01d3596` | `4c50980d…` | S31 + PORT reads OFF at 0; LEG's cell as FLT.T's |
| S33 | `d0d6ff03` | `da64862f…` | S32 + PORT and LEG saved with the sound, and their p-locks with the pattern |
| S34 | `0047c55d` | `de034548…` | S33 + with LEG ON a legato note does not restart the amp envelope |
| S35 | `78d8af35` | `f8a304d3…` | S34 + a note is legato when the last note's amp envelope is still in its attack or hold |
| S36 | `9df62a0b` | `d6fac1a3…` | S35 + a note is legato when the last note's LEN has not ended at the NoteOn (or ends in its tick) |

**What to check on the unit (OS 1.54):**

- **S28:** everything works as on S27, for a while of ordinary use: patterns on every machine, POLY
  and CFOO tracks, the sampler, MIDI in and out, USB, saving and loading. A difference of any kind
  means one of the two pads is live.
- **S29:** as S28; every note plays its own pitch, nothing glides, the TRIG page is unchanged.
- **S30:** on an audio track's TRIG page, G shows PORT (0–127, whole steps) and H shows LEG (OFF/ON, a
  switch). Both turn, reset to 0/OFF, lock on a trig and follow the sound when it changes (a reload
  loses them until S33). Existing projects and a new sound show PORT 0 and LEG OFF. Nothing sounds different.
  MIDI CC 7 and CC 10 still set VOL and PAN, and turning PORT sends no CC. A MIDI track's TRIG page is
  unchanged; the LFO destination list has no PORT or LEG. Note how LEG's cell looks next to FLT.T's.
- **S31:** PORT 0 plays as before. PORT 32 with LEG OFF: every new note glides (time constant about
  0.1 s), slower as PORT rises. LEG ON: notes whose LEN reaches the next trig glide, the others start on their pitch.
  A PORT lock on one trig changes that note's glide only. On a POLY track each voice glides from its
  own last note. On a CFOO track OSC1 glides and OSC2 and OSC3 keep their detunes.
- **S32:** PORT reads `OFF` at 0 and the number above; LEG's cell shows no `OFF`/`ON` text while it is
  turned, as FLT.T's. Nothing else changes.
- **S33:** set PORT and LEG on a few tracks and lock both on some trigs; save the project, load another
  and load it again (and power off and on): the values and locks come back. Copying and pasting a
  sound carries them. A lock on another parameter in the same pattern still comes back on its own
  parameter; LFO1's speed is untouched. Projects saved before S33 open with PORT OFF and LEG OFF.
- **S34:** LEG ON, a sound with a slow attack: notes whose LEN reaches the next trig carry on without a
  new attack (a CFOO track just changes pitch; a sample track restarts its sample, at the running
  level); detached notes start with their attack. LEG OFF: every note restarts the envelope as
  before. The filter envelope follows FLT.T as before.
- **S35:** as S34, with short notes: HOLD at NOTE, LEN 1/8 and LEG ON: every note sounds and starts
  with its attack, and none glides; LEN longer than the trig distance (or INF): the notes glide and
  carry on. With PORT on and LEG ON, a detached note no longer glides.
- **S36:** as S35, now by LEN alone: LEN shorter than the trig distance, every note restarts and none
  glides; LEN equal to it (1 step on every step) or longer, every note after the first is legato,
  steadily, with any HOLD; LEN INF: every note restarts. HOLD and DEC only shape the sound.

## On the unit

✅ S31 on the test unit with OS 1.54, as reported:
- a CFOO track glides;
- a sample track glides;
- LEG ON works;
- a lock on PORT works;
- LEG's cell shows `OFF`/`ON` text while it is turned, which FLT.T's does not (above; S32 changes it);
- PORT and LEG set on a project are not there after it is saved and loaded again (S33 changes it).

✅ S32: confirmed (PORT reads OFF at 0, LEG's cell as FLT.T's). ✅ S33: confirmed for PORT and LEG,
their recall with the project and their p-locks.

⛔ S34: with HOLD at NOTE, a short AMP DEC, LEN 1/8 (no locks) and LEG ON, no note sounds; with the same
settings PORT had glided whether LEG was on or off (seen before S34). Cause: the legato test (above,
"When a note ends"); S35 changes it.

⚠️ S35: notes of equal LEN alternate between restarting and not (one runs into the other); cause: the
amp envelope's state as the legato test (above); S36 changes it.

✅ S36 on the test unit with OS 1.54, as reported: legato by LEN works as intended, "so far it seems
to work".

Not reported yet: S28 to S30 on their own, and what existing projects hold in PORT and LEG.

## Open points

- The two new pads ran this build's code on the unit (S31, above); S28's fill test on its own is not
  reported.
- ⚠️ Slots 46 and 47 unused in stock: by the scans above; S30 shows what existing projects hold.
- ✅ A lock on LEG works as the lock on PORT does: S33 on the unit, above.
- ⚠️ Other places that store or copy a sound's values outside the record above (a kit's copy, a sound
  pool) are not traced beyond the writer, the reader and the mirror; S33's reload test covers the
  common path.
- The glide is stepped once per tick (0.67 ms); on the shortest glides that is 1,500 steps a second.
- At a legato note with LEG ON a sample still restarts; only the amp envelope carries on (S34).
- Code space left: 6 B in `FUN_400ee05e` (from S35; 10 B in S31 to S34); `FUN_400e6d1c` is full from
  S34; `.rodata` padding `0x40252fec..0x40253000` (20 B) and `0x40252ed9..0x40252f00` (39 B).
