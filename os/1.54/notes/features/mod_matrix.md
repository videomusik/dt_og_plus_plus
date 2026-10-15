# The modulation matrix: MOD WHEEL and BREATH CTRL

In the build from v0.2.5: `os/1.54/build/patch.json` holds it as the feature `mod_matrix`, its last
stage, S69, less the build before it (v0.2.4, S56, section 3 `ce7a07b4…`), on which the stages are built.
The build is S69, byte for byte. Source: [src/mod_matrix/](../../src/mod_matrix/) (`mm.s`, `mm.ld`,
`make_mm.py`).

## What it does

Each audio track gets a modulation matrix for two performance controllers, as the track receives them
over MIDI:

- **MOD WHEEL**, CC 1 with CC 33 as its fine part, and **BREATH CTRL**, CC 2 with CC 34;
- per controller, four slots, each a destination (any LFO destination) and a depth (on LFO DEP's scale,
  −128.00..+127.99);
- every audio tick, before the LFOs, each slot moves its destination by the controller's value times its
  depth: at the controller's top as far as an LFO of that depth at its peak, at 0 not at all, clamped as
  the LFOs clamp. The LFOs add their own on top.

The page:

- [LFO] held + [UP] or [DOWN] on an audio track turns its LFO page into the matrix page of the controller
  last shown (MOD WHEEL at first), titled `MOD WHEEL` or `BREATH CTRL`. While it shows, [UP] and [DOWN]
  step between the two, unless a view is open above the screens (a menu, a list, the pattern selector),
  which then has them as on stock.
- Knobs A–D are the four slots' destinations, chosen in LFO DEST's list and confirmed with [YES], as
  LFO DEST is; E–H are their depths, with LFO DEP's steps, fine steps and popups.
- The level fader shows the controller's value for the track, named `MW` or `BC`, and follows each CC as
  it arrives; LEVEL/DATA sets the value by hand, as a CC would, with a popup (`MOD WHEEL=64`).
- A tap of [LFO] goes back to the LFO page as it was; [TRIG], [SRC], [FLTR] or [AMP] leave the matrix
  for their page. [TRK] + a track key keeps the matrix, with that track's slots; a MIDI track ends it.

A Poly track plays with its source track's slots and controllers, so every voice of a pool follows them.
The slots belong to the pattern's kit: a pattern change brings its own, and they are saved with the
project.

## How

✅ Read in the code (objdump, decompile, and the digiemu runs below where they say so).

### The controllers

- The MIDI CC dispatcher `0x400d6918` matches a message's channel against each track's
  (`0x4197c700`), the auto channel (`0x4197c6fc`) and the FX channel (`0x4197c740`). For CC 1 and 33 it
  keeps, per track, the last MSB and LSB at `0x421b20a4 + 2 × track` (`+0` LSB, `+1` MSB), for CC 2 and
  34 at `0x421b2084 + 2 × track`, and hands the value to a routine that does nothing with it. Both
  tables are in `.bss`, so they are 0 from start-up until a CC arrives.
- `mm_value` reads a pair as x = (MSB << 7 | LSB), plus x >> 7, at most 16384: the MSB alone at 127
  reaches the top.

### The pass

- The audio tick rebuilds the engine's values from the sound every tick (`0x40074b9e`), then calls the
  LFO tick `0x400ed766` at `0x40077eac`, which adds the LFOs and clamps. In the engine block, slot k of
  track t is the word at `0x80002772 + 0x6a × t + 2k`, a parameter's value << 8 (`0x7f00` the top).
  LFO1's destination is slot 4 and its depth slot 8 (LFO2's 12 and 16); FREQ is slot 26, TUNE slot 17
  (a TUNE step is `0x100` a semitone).
- `mm_pass` replaces that call: for each audio track it reads the controllers of the track's source
  (the track itself, or for a Poly track the nearest track before it whose machine is not 4, as POLY's
  pools are built, from the machines at `*(0x800019ac) + 0x9e + 0xa2 × track`), and for each slot whose
  destination is 1..52 adds Δ = (x × (depth − `0x4000`) + 4096) >> 13 to that engine slot, clamped to
  `0..0x7f00`. Then it jumps to the LFO tick with the stack as it came. Nothing carries over to the next
  tick, and a controller at 0 costs one read.
- A slot is two words, as LFO DEST and LFO DEP hold their values: the destination's engine slot in the
  high byte of the first (0 none), the depth in the second (`0x4000` none; at x = 16384 the destination
  moves by (depth − `0x4000`) × 2, as far as an LFO of that depth at its peak).

### The page

- `LfoPageView`'s vtable `0x401af858` is shared by the audio and the MIDI tracks' LFO pages. The build
  points seven of its words at the matrix: `+0x10` the drawing (`mm_draw`), `+0x2c` the tick
  (`mm_tick`), `+0x44` the encoder handler (`mm_enc`), `+0x4c` the title (`mm_title`), `+0x54` the get
  (`mm_pget`), `+0x9c` a knob's parameter (`mm_knob`) and `+0xa4` a parameter's set (`mm_set`); and the
  page's encoder interface's handler `0x401af938` (stock `0x40032d8c`, which takes 4 off the object and
  goes to the handler without the vtable, so the unit's knobs bypass `+0x44`). Each does the stock thing
  unless the matrix shows, on an audio track.
- `mm_draw` draws the eight knobs as the page draws its parameters: the page's cell (`+0x94`) with the
  parameter, the slot's word, the encoder state's touch and mark (`0x400c0686`, `0x400c0898`), x 24 + 26
  a column, y 27 for A–D and 0 for E–H; then the page's own drawing after the knobs (`+0x98`,
  `0x4003071e`), which draws the level fader.
- `mm_title` builds the controller's name as the title's `std::string`; `mm_update` shows it in the
  header (`0x40093a56`) as the page does when it turns to its other page.

### Editing through stand-in parameter sets

- `mm_knob` answers LFO1's DEST (id 55) for knobs A–D and LFO1's DEP (id 59) for E–H, and notes the
  knob. `mm_set` answers, for those two ids, a stand-in for the track's parameter set: the four words
  of the set stock gives, under `mm_vt`, a copy of `SoundParameterSet`'s 30-entry vtable (`0x4017ee58`)
  in which the get (`+0x28`), the store (`+0x74`) and the notify (`+0x60`, which would send the change as
  MIDI) are the matrix's.
- So the stock encoder handler (`0x40032a78`), its steps and fine steps, the popups, the DEST list
  (`FUN_40032746`, `FUN_400a4a78`, which needs [YES]) and the cell read and write the slots, and nothing
  of the sound, the pattern or MIDI out changes.
- A turn or a knob's key with [TRK] held (the all-tracks edit, `0x400d399c(2)`) or a trig held (the
  header's held-trig view and lock, `0x40093470`, `0x40093bb6`) is taken and does nothing.

### Keys

- The UI task's call of the key dispatcher (`0x4000b770`, `jsr 0x400084fe`) calls `mm_key`, which sees
  every key event first. An event holds the key at `+12` and its flags at `+16` (bit 0 pressed, bit 4 a
  short tap's release). `mm_key` notes [LFO] held, passes [LFO]'s release on without its tap bit after
  a chord with [UP]/[DOWN] or a tap that leaves the matrix (so the LFO page keeps its page), leaves the
  matrix on [TRIG]..[AMP], and takes a knob's key with [TRK] or a trig held. Key numbers: [TRK] 2, [YES]
  `0x0c`, [UP] `0x0e`, [DOWN] `0x0f`, [TRIG]..[AMP] `0x13..0x16`, [LFO] `0x17`, the knobs' keys
  `0x28..0x2f`.
- [UP] and [DOWN] are taken in `KeyboardView`'s key handler (its vtable `0x40182450`, `+8`
  `0x40182458` → `mm_kbkey`, stock `0x40029876`, where they shift the keyboard's octave). The view
  controller (the page's `+0x2c`; its views in a list at `+0x14`, the last node at `+0x18`, a node's
  view at `+8`) offers a key to its views from the top until one's `+8` handler returns true
  (`0x400cad2e`), so KeyboardView gets [UP] and [DOWN] only when no view above took them.
- ✅ On the test unit, a first design failed: S63 took [UP]/[DOWN] in `mm_key` only while the top view
  was the help bubble (`0x401af570`), which stays above the screens; but once the pattern selector has
  been used, `PatternAndBankSelectView` (`0x401af32c`) stays on top of the list (S63d showed it), so the
  matrix never opened. KeyboardView's handler (S65) needs no list of views.

### The level fader

- The page's drawing after the knobs (`0x4003071e`) draws knob 8's parameter: LEVEL/DATA's, Track
  Level (id 10), its value from the page's get (`+0x54`) and its cell. While the matrix shows,
  `mm_pget` gives id 10 the controller's value, MSB << 8 | LSB << 1, at most `0x7f00`.
- The cell (`0x40030c0c`) draws a knob's short name when the knob is not being turned, from a call at
  `0x40030daa`. Stock calls `0x4000fe8a(set, id)`, which returns the descriptor's name pointer
  (`0x401aa09c + 0x34 × id`, `+0x30`: `LEV` for id 10). In the build that call already goes to MIDI
  Loopback's name (`0x4001562c`, TRK for CHAN below 1), which goes on to the CFO oscillator's
  (`0x400f7b2e`, CFOO's names) and then to stock. From S68 it calls `mm_name`, which answers `MW` or `BC`
  for id 10 while the matrix shows and otherwise jumps to `0x4001562c` with the stack as it came (MIDI
  Loopback's name reads the cell's frame through it).
- A CC changes the pair and asks for no redraw, so up to S68 the fader moved only when something else
  redrew the page. The view controller's tick-all (`0x400cb250`) calls `+0x2c` of each view in its list;
  LfoPageView's own (`0x40030ee0`) counts a timer down. From S69 `mm_tick` compares the shown
  controller's pair with the one seen at the last tick and asks the page to redraw when it changed
  (`0x400c9a3a`, which sets the view's `+20` and the controller's `+32`). ✅ digiemu (S67): on the matrix
  page the LFO page is itself in the controller's list.
- LEVEL/DATA is encoder 9 (an encoder event: `+12` the encoder, 1..8 the knobs, 9 LEVEL/DATA; `+16` the
  signed detents; `+20` a fine flag). `mm_lev` moves the MSB by the detents, 0..127, sets the LSB to 0,
  shows `%s=%d` through the header's popup (`0x40093ab0`) and redraws.

### Saved with the kit

- The project object is `*0x421fab90`; its live kit (`KitActive`) at `+0xec` holds its index at
  `+0x44`, so `*(project + 0x130)` is the live kit's index, which a pattern change sets
  (`KitActive::vfunc_24`, `0x4000e2b4`). The project's 128 kits are at `0x4191f018 + 2334 × k` (the
  project data `0x409bbbfc` + `0xf6341c`).
- The matrix keeps 256 B of slots per kit in RAM ([memory_map.md](../memory_map.md)) and uses the live
  kit's.
- A stored kit is `0xa00` B. The kit writer `FUN_4007a6a6` ends with `movew %a3@(2332),%a2@(2290)` at
  `0x4007a76e`, and stock writes nothing of the stored kit from `+0x8f4` on, nor does its loader read
  it: 268 B. From S64 that copy calls `mm_kwrite`, which does the copy and, for one of the project's
  128 kits, writes `MMK1`, the kit's 256 B of slots and their sum (a long) there.
- The kit loader's gate (`0x4007a4ce`) takes a stored kit of version 10 to the loader `0x4007a386`. It
  jumps to `mm_kload`, which makes the same tests, calls the same loader, and when the loader took the
  kit, takes its slots from the tail (`mm_tailin`): a tail with the marker and the right sum gives its
  256 B, any other none. Every project save and load, and a kit's own save and reload, go through these
  two.
- ✅ On the test unit (S66): slots set on a pattern came back after the project was saved and the unit
  switched off and on.

### Code space

The wave pictures' pad `FUN_401044b6` after their code: `0x401045c0..0x40104e78`, 2,232 B in S69,
leaving 6 B of fill ([landing_pads.md](../landing_pads.md)).

### The RAM

The state, the stand-ins and every kit's slots are above `.bss`, uncleared at start-up
([memory_map.md](../memory_map.md)). The state's marker is the address of `mm_vt` plus a constant, set
by `mm_init` once the rest is set. ✅ On the test unit this RAM kept its contents across an OS update:
S63's first image, whose marker was S62's, took S62's stand-ins as its own; their vtable word pointed
at S62's `mm_vt`, 8 B off S63's, and the page drew no pictures and garbage text. From S63's second image
the marker follows the code's layout, and `mm_set` rewrites a stand-in's vtable and knob whenever it
hands one out. A digiemu run that poked the old state reproduced the fault and the fix.

## Checks

- `make_mm.py` assembles every stage and checks, against the stock section 3 and the build before it:
  each site's bytes (stock and untouched by the build; the cell's name call as MIDI Loopback's, the one
  site a feature of the build owns); that the pad holds the wave pictures' fill there; the first bytes of
  every routine the matrix calls and the kit facts it relies on (the loader's gate whole); `mm_vt`
  against `SoundParameterSet`'s vtable but for its three entries; that no instruction of the build
  branches into the replaced calls or into the gate from outside it; that no word of the build names the
  state's RAM; and each stage against its recorded section-3 hash. With `--write` it merges S69 into
  `patch.json` under the rule that each byte is listed under the last feature that wrote it, and gives
  back the bytes it takes from MIDI Loopback (3 B of the call's operand) and from the wave pictures (the
  pad's fill) when it rebuilds the build before it. The older generators give those bytes back too.
- ⛔ digiemu (emulator results, not unit results), each on its stage image: the page, its keys and the
  title; the knobs editing the slots through the stand-ins, and the DEST list; the pass, with the
  controllers poked into stock's pairs (the probe's MIDI input freezes the UI task in batch runs, so CCs
  were not sent); a kit's slots through a project save and load; the stale-RAM fault and its fix; the
  view list; LEVEL/DATA; the fader's name (MW, BC, LEV again on the LFO and TRIG pages; CFOO's names and
  MIDI Loopback's TRK unchanged) and the fader following a poked value within 100 ms on S69, where S67
  and S68 showed it only after 0.5 to 1.6 s. The repository's digiemu check
  ([docs/digiemu.md](../../../../docs/digiemu.md)) passes on S61, S62, S64, S66, S68 and S69, with the
  screens differing from stock as v0.2.4's do.
- The emulator harnesses that take a build's MAIN OS pass on v0.2.5
  ([scripts/emu/README.md](../../scripts/emu/README.md)). None of them runs the matrix's code.

## Stage images

Each on the one before it, on the build v0.2.4 (S56); `make_mm.py --stages` writes them to
`out/1.54/stages/`. The options are `mm.s`'s.

| Stage | `.syx` | Options | What it adds |
|---|---|---|---|
| S57 | `74908c66` | none | the page: [LFO] + [UP]/[DOWN] shows MOD WHEEL or BREATH CTRL with eight empty knobs |
| S58 | `58d94293` | ENC4 | the encoder interface's handler too, so the knobs no longer turn the LFO under the matrix |
| S59 | `ffa0a26e` | TRKSTAY | [TRK] keeps the matrix; it shows on audio tracks only |
| S60 | `f032d0a7` | PASS | the pass, with one fixed test assignment: MOD WHEEL closes FREQ over its range, BREATH CTRL raises TUNE up to an octave |
| S61 | `897bccaf` | MIDIEXIT | a MIDI track ends the matrix |
| S62 | `afd4d96e` | EDIT | the slots, in one set for all patterns, edited through the stand-ins |
| S63 | `2bfa38a9`, `570a9f10` | FRONT | [UP]/[DOWN] only with the help bubble on top; the second image with the layout marker and `mm_set`'s rewrite |
| S63d | `d8752fe2` | DIAG | a test image: the title shows the top view's vtable |
| S64 | `002b3d9f` | KITS | the slots per kit, on S63; not flashed |
| S65 | `21ea4e2c` | VIEWKEYS | on S62: [UP]/[DOWN] in KeyboardView's handler, with S63's marker and stand-in fixes |
| S66 | `5c908384` | KITS | the slots per kit, saved with the project |
| S67 | `aacdb1a4` | LEVTEST | the level fader shows the controller, LEVEL/DATA sets it |
| S68 | `460132e9` | LEVNAME | the fader named MW or BC |
| S69 | `2c32b3bb` | LEVTICK | the fader follows a CC as it comes; the build v0.2.5 |

## On the unit

On the test unit with OS 1.54, as reported, each stage flashed on the one before:

- S57: the page showed; the knobs still turned the LFO's values under it (S58 changes that). S58, S59
  and S61 worked as described.
- S60: through a DIN cable from MIDI OUT to MIDI IN, MOD WHEEL closed FREQ and BREATH CTRL raised the
  pitch, up to an octave.
- S62: the page, the slots, their editing and the sound worked; DEST takes [YES], as LFO DEST does. Every
  pattern had the same slots, which S66 changes. A matrix knob turned with [TRK] held does nothing.
- S63's first image: no pictures and garbage text on the page (the stale RAM, above). Its second image
  did not open the matrix; S63d's title showed `401AF32C`, the pattern selector's view on top.
- S65: the matrix opened and [UP]/[DOWN] stepped its controller.
- S66: slots set per pattern were kept through a project save and a power cycle, and a track was driven
  from a MIDI track on its channel through a cable from MIDI OUT to MIDI IN.
- S67: the fader showed the controller and LEVEL/DATA set it.
- S68 and S69: tested and working.

Seen on these images and on S56 alike, so not the matrix's: with USB set to AUDIO/MIDI, on a pattern
with CFOO and Poly tracks, the UI came to take tens of seconds to respond, until a restart. ⚠️ The load
of USB audio on top of CFOO is the likely cause; it was not measured. ⛔ digiemu (S56 and S66, CFOO on
four tracks, no USB): the UI loop ran at the same rate on both.

## Open points

- Not reported on their own: two patterns with different slots switched while playing; a project
  saved by an earlier build loading with no slots; another project loaded and back again.
- A new project, or a cleared kit, keeps the slots its kit index had in RAM: neither goes through the
  kit loader. Copying a pattern or a kit does not copy its slots.
- [TRK] or a trig held with a matrix knob: taken, with no all-tracks edit and no lock.
- Not tried on the unit: [FUNC] + [LFO] on the matrix; the controllers over USB MIDI.
- More sources (aftertouch, velocity) and more destinations (FILTER page 2's VED and KEY) are not in.
- MIDI Loopback passes only notes (`0x8n`, `0x9n`; its tap at `0x400151e6`), so a MIDI track set to a
  TRK value cannot drive the matrix.
- No emulator harness runs the matrix's code.
- The build has 108 B of free code space left, none of it more than 16 B in one place: a further
  feature needs a new pad.
