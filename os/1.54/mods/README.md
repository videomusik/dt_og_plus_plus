# Linkable mods for OS 1.54 (elekloader)

DT OG++'s features as linkable mods for [elekloader](https://github.com/irpina/elekloader) (its format 2,
`.elemod`). Each mod is built from its own source against the user's stock OS 1.54 file, and elekloader
links it with its core mod and whatever other mods the user picks. The build in
[build/patch.json](../build/patch.json) stays as it is; these are the same features, from source, so each
can be used on its own.

| Mod | What it adds | Needs |
|---|---|---|
| [dtogpp-params](dtogpp-params/) | four more sound parameters, saved with the sound and lockable per trig, for the mods below | core |
| [dtogpp-portamento](dtogpp-portamento/) | PORT and LEG on every audio track's TRIG page (knobs G and H) | core, dtogpp-params |
| [dtogpp-filter-page2](dtogpp-filter-page2/) | VED and KEY on every audio track's second FILTER page (knobs C and G) | core, dtogpp-params |
| [dtogpp-chain-record](dtogpp-chain-record/) | Chain Recording: the recorder fills a sample chain one slot at a time | core |
| [dtogpp-midi-loopback](dtogpp-midi-loopback/) | Virtual MIDI Loopback: CHAN TRK1–TRK8 on a MIDI track plays an audio track with no cable | core |
| [dtogpp-tick-wipe-fix](dtogpp-tick-wipe-fix/) | the fix for a note released and re-triggered in one audio tick | core |
| [dtogpp-poly](dtogpp-poly/) | the Poly machine (machine 40), and RRBN and RRND for SLICE on the Slice machine | core |
| [dtogpp-cfoo](dtogpp-cfoo/) | the CFO oscillator, machine 41 (CFOO): its SRC page with the wave pictures, [FUNC] + knob's snap points, its LFO destinations | core, machine-pages |

Each folder holds a `mod.json` (elekloader's SDK format) and its assembly source (the tick-wipe fix has
none: it rewrites stock instructions in place; dtogpp-cfoo's `waves.inc` is the output of
[src/cfo_oscillator/make_waves.py](../src/cfo_oscillator/make_waves.py)). What the features do is
in [README.md](../README.md); how they work, in [notes/features/portamento.md](../notes/features/portamento.md),
[notes/features/filter_page2.md](../notes/features/filter_page2.md),
[notes/features/cfo_oscillator.md](../notes/features/cfo_oscillator.md) and
[notes/features/wave_pictures.md](../notes/features/wave_pictures.md).

dtogpp-cfoo needs elekloader's machine-pages 1.1 beside core 3.0: the SRC page, its knobs and the LFO
page's names are described to it ([below](#dtogpp-cfoo-on-machine-pages)).

## Building

Needs an elekloader checkout (its SDK, linker and patcher), the m68k binutils (`m68k-linux-gnu-as`, `-ld`,
`-objcopy`) and the stock `.syx`. From the elekloader checkout:

```bash
python3 -m elekloader.sdk.build <repo>/os/1.54/mods/dtogpp-params --stock Digitakt_OS1.54.syx --out <repo>/out/1.54/mods
python3 -m elekloader.sdk.build <repo>/os/1.54/mods/dtogpp-portamento --stock Digitakt_OS1.54.syx --out <repo>/out/1.54/mods
python3 -m elekloader.sdk.build <repo>/os/1.54/mods/dtogpp-filter-page2 --stock Digitakt_OS1.54.syx --out <repo>/out/1.54/mods
python3 -m elekloader.lint <repo>/out/1.54/mods/dtogpp-params-0.2.4.elemod --stock Digitakt_OS1.54.syx \
    --with core-3.0-os1.54.elemod --with <repo>/out/1.54/mods/dtogpp-portamento-0.2.4.elemod \
    --with <repo>/out/1.54/mods/dtogpp-filter-page2-0.2.4.elemod
python3 -m elekloader.patch --stock Digitakt_OS1.54.syx --mod core-3.0-os1.54.elemod \
    --mod <repo>/out/1.54/mods/dtogpp-params-0.2.4.elemod --mod <repo>/out/1.54/mods/dtogpp-portamento-0.2.4.elemod \
    --mod <repo>/out/1.54/mods/dtogpp-filter-page2-0.2.4.elemod --out custom.syx --version XXXX
```

`core-3.0-os1.54.elemod` is elekloader's core mod built for OS 1.54. The SDK checks every site's stock
bytes against the stock file, and stores each run of 8 or more bytes of a mod's code or data that repeats
the stock image as a reference into the user's own file, so an `.elemod` carries no firmware bytes.

## The same code as the build

Chain Recording, MIDI loopback, the tick-wipe fix and POLY are the build's code, only placed elsewhere. Their
sources keep the build's blocks as sections (`.text.*`, `.rodata.*`, which the SDK puts into `.run`), and
their `mod.json` names, under `dtogpp_check`, the build's features they carry and where the build has each
section. [check_against_build.py](check_against_build.py) assembles a mod as the SDK does, links each
section at its build address, applies the mod's sites with their targets there, and compares with the
build in [build/patch.json](../build/patch.json): every byte the mod places must be the build's, and every
byte the build lists under those features must be one the mod places. Bytes the mod is meant to place
differently are listed with the reason, and printed.

```bash
python3 os/1.54/mods/check_against_build.py os/1.54/mods/dtogpp-chain-record --prefix m68k-linux-gnu-
```

| Mod | Against the build |
|---|---|
| dtogpp-chain-record | `chain_record`: 555 B of code and strings and 12 sites, every byte the build's |
| dtogpp-midi-loopback | `midi_loopback`: 488 B of code and 9 sites, every byte the build's but the two label jumps, which go to the stock label lookups (`0x4000fe8a`, `0x4000feac`) where the build's CFO oscillator points them at its own names |
| dtogpp-tick-wipe-fix | `tick_wipe_fix`: its one site, the build's bytes |
| dtogpp-poly | `slice_round_robin`, `poly_engine`, `poly_ui`, `pool_cursors`, `voice_allocation`, `mute_by_origin`, `owner_latch`: 1,218 B of code and data and 21 sites, every byte the build's but the machine number the voice-pool map tests (40 where the build has 4) and, at one site, the order of a call and a `nop`. The build's own MACHINE-menu entry is left out (POLY's name table, the picker's step and clamp, the list's row count), as core 3.0 adds machines to the menu from their descriptors; the descriptor (38 B) is the one section with no counterpart in the build |

dtogpp-cfoo carries no feature of the build whole, as its machine and page go through core and
machine-pages ([below](#dtogpp-cfoo-on-machine-pages)); the check places its synth (754 B) and its tables
(1,048 B) where the build has them, and every byte is the build's but the note read's opcode word.

Portamento and FILTER page 2 are not checked this way: dtogpp-params changes how their parameters are
loaded and saved, so their code is not the build's byte for byte ([below](#what-differs-from-the-build-in-patchjson)).

## Where the code and the state live

A mod's code and constant data are its section `.run`, its state its section `.bss`. elekloader's linker
places them in the RAM it gives all linked mods, 128 KB at `0x47be0000..0x47c00000`, and its core copies
`.run` there and zeroes `.bss` at start-up; nothing goes into the stock image's dead functions or padding.
That this RAM is unused on OS 1.54 is in [notes/memory_map.md](../notes/memory_map.md#ddr). Each mod changes
the stock image only at its sites in `mod.json`: hooks (`jsr`/`jmp` into its code), operands it redirects
(`keep2`, `ptr`) and data it rewrites (`bytes`).

## dtogpp-params: the sound-parameter table

A sound holds 53 value slots (the sound's `+0x14 + 2 x slot`, 8.8). The stock OS saves slots 0..45 to the
stored record's words (`+0x1c + 2 x index`) through two tables; slots 46..52 are free. dtogpp-params makes
slots 46..49 persistent, at the record's spare words 46, 47, 50 and 51 (`+0x78`, `+0x7a`, `+0x80`, `+0x82`;
48 and 49 are the machine bytes), and owns every site that this takes, once:

| Site | Stock | What it becomes |
|---|---|---|
| `0x4007a2aa` in the sound reader `FUN_4007a236` | `lea 0x401ac58c,%a0` before the value loop | `jsr dtogpp_params_load`: the four words back, then each described slot checked |
| `0x4007a5f8` in the sound writer `FUN_4007a5a0` | `lea 0x401ac4d4,%a0`, the slot → stored index table | the mod's table: the stock 46 entries, then 46, 47, 50, 51 |
| `0x4007a614` in the writer | `moveq #92,%d1`, 46 words | `moveq #100,%d1`, 50 words |
| `0x4007adea`, `0x4007af32` in the p-lock writers `FUN_4007adb2`, `FUN_4007aefa` | the same stock table | the mod's table |
| `0x40079738`, `0x40079772`: the lookups stored index → slot and slot → stored index | their first 8 B | `jmp` to the mod's version: a sound's index or slot above 45 answered there, everything else on in the stock function |

A mod that puts a knob on one of the slots describes it in the table `dtogpp_params`, one 12-byte entry
(`contribute` in its `mod.json`); elekloader's linker sorts the entries and ends the table with a zero one:

| Offset | Field |
|---|---|
| +0 | the slot, 46..49 |
| +2 | flags: bit 0, whole values only |
| +4, +6, +8 | min, max, default (8.8) |
| +10 | 0 |

When a sound is read, a value outside min..max, or with a fraction where only whole ones are allowed, gives
the default. A slot that no mod in the build describes keeps its stored word unchecked, so a project keeps it
through a save in a build without that mod. Each mod claims its slots as named resources (`sound-slot:46`
...), so two mods cannot take the same one.

| Slot | Stored | Mod | Range |
|---|---|---|---|
| 46 | 46 (`+0x78`) | dtogpp-portamento: PORT | 0..127, whole, default 0 |
| 47 | 47 (`+0x7a`) | dtogpp-portamento: LEG | 0..1, whole, default 0 |
| 48 | 50 (`+0x80`) | dtogpp-filter-page2: VED | 0..100.0 (`0x6400`), default 0 |
| 49 | 51 (`+0x82`) | dtogpp-filter-page2: KEY | 1..127, whole, default 64 |

## dtogpp-cfoo on machine-pages

The build's CFO oscillator hooks the machine list, the SRC page, its knobs, the LFO page's names and the
render itself. With elekloader, core 3.0 and machine-pages 1.1 own those places once for every added
machine, so dtogpp-cfoo describes its machine and its page to them, and keeps its own hooks only where
neither has one:

| The build | dtogpp-cfoo |
|---|---|
| machine 5, registered by edits of the machine list, the name readers and the icon mapper | machine 41 through core's machine slots: its names, its icon, ONESHOT's parameters (params 0: the SRC slots' ids 108-115) and its own render machine (an empty voice window it fills) |
| ONESHOT's SRC page layout for machine 5 | the page machine-pages draws, copied from ONESHOT's (page_from 0) |
| the knobs' names, the cells' and the pop-up's value texts, the cells' pictures | each knob's names, value text (`fmt`) and picture (`draw`: the wave on WAV1-3, STRT's knob rescaled from the knob's range on the others) |
| the ranges and defaults at the setter, a turn, the list stepper and the validator | each knob's range and default (`CM_RANGE`, `CM_DEFAULT`) |
| the ranges and defaults at five more lookups: ParameterSet's vfunc_25, vfunc_4 (the reset to the default) and vfunc_26 (a MIDI CC), the machine-change reset, the edit on all tracks | the same hooks, sites of the mod, reading the page's ranges: machine-pages leaves these stock |
| the LFO page's names | the page's group (CFOO) and the knobs' names |
| the render hook at `0x40077fc8`, before the level stage | machine-pages' `ev_render_voices` (order 30) at `0x40077fba`, the same point of the render: nothing runs between the two |
| the encoder's step (BR's whole steps for A, C, D, E, F, PLAY's for B, TUNE's fine ones for G, H), [FUNC] + knob's snap points, the [TRK] pop-up's, the SRC page title's and its pop-up's machine-and-sample texts, knob D's sample test | the same hooks, sites of the mod |

machine-pages' own way to give a knob another parameter's turn (`look`) gives it every use of the knob's
UI record; the build gives it to the encoder alone, and the two turn G and H differently (in digiemu, seven
detents of G reached +0.39 against the build's +0.45), so dtogpp-cfoo keeps the build's hook.

## What differs from the build in patch.json

The features' code is the build's (portamento's last stage, S36; FILTER page 2's, S52 with KEY on the
trig's note, S56), with these changes:

- **One owner per site.** In the build, FILTER page 2 took over portamento's reader hook and extended its
  lookups and its table; here dtogpp-params owns them, and neither feature mod touches the other's bytes.
- **PORT and LEG are checked one by one** when a sound is read: the build gives both 0 when either stored
  word is out of range, dtogpp-params only the one that is.
- **The glide hook is 6 bytes** (`0x40075690`, the rate loop's `lea NOTES,%a0` only): `port_glide` returns
  the address of the glided notes, and the loop's own `movel %a0@(0,%fp:l:4),%d6` that follows reads the
  track's. The build's site also took that `movel` and the `moveq` before it.
- **KEY reads the trig's note** (`0x80001f28`, as the build from S56), so dtogpp-filter-page2 does not
  depend on dtogpp-portamento. dtogpp-portamento exports `dtogpp_port_notes`, a long holding the address
  of its glided note sums, for a mod whose own oscillator should play the glided note (the build points
  the CFO oscillator's note read at them); a mod that imports it weakly gets 0 without dtogpp-portamento
  (elekloader resolves a missing weak import to its core's zeros).
- **The state is in `.bss`**, zeroed by elekloader's core at start-up, instead of the RAM above the OS's
  `.bss` that the build uses (`0x439d1180`).
- **KEY's text and the names** are in `.run` instead of Chain Recording's leftover pad bytes and the
  `.rodata` padding.
- **Chain Recording's chain word and the loopback's ring** are in `.bss`, zeroed at start-up, instead of
  the RAM above the OS's `.bss` (`0x439d1038`, `0x439d1060..0x439d10e1`), which start-up leaves as it is;
  the build's code already treats that RAM as garbage at start-up (the chain word's marker, the ring
  index masked to 0..31), so both start as they do in the build.
- **POLY is machine 40**, registered through core 3.0's machine slots, where the build's POLY is machine 4:
  in elekloader's registry, machines 4 to 9 and 20 to 29 belong to other mods. A project saved with the
  build's POLY therefore does not open its tracks as POLY with this mod.
- **CFOO is machine 41**, and its page goes through machine-pages ([above](#dtogpp-cfoo-on-machine-pages)).
  Its synth (the render of one track, 754 B) and its tables are the build's byte for byte; the render reads
  OSC1's notes through a word it sets each tick, to dtogpp-portamento's glided notes when that mod is in the
  build (`dtogpp_port_notes`, a weak import), else to the trig's notes, where the build's operand names
  portamento's notes. The synth's phases and levels are in `.bss`, zeroed at start-up, instead of the RAM above the OS's `.bss`
  (`0x439d1100..0x439d117f`). The texts that show a Poly track's Source machine (`POLY: CFOO`) answer for
  dtogpp-poly's machine 40.
- Every feature in patch.json is in one of these mods. Their code is in elekloader's RAM, so they leave
  stock the landing pads the build clears and fills (`pad_fill`, and the rest of each pad a feature's code
  takes).

## Checks

On files, with elekloader's tools and the stock OS 1.54 file:

- **Build.** The SDK builds all three, checking every site's stock bytes. Nothing in them repeats the
  stock image beyond what the SDK stores as references to it.
- **Link.** With core 3.0 for OS 1.54 they link in every combination users can pick: the three; params
  alone; params with either feature. Either feature without params is refused (`requires dtogpp-params`).
  The three use 2,360 B of the 128 KB RAM.
- **Against the build.** Linked with core, every byte the mods write in the image is the build's (S56)
  except where a site now names the mods' own code or data in RAM; every byte the build lists under
  portamento, filter_page2 and filter_page2_key_note outside the mods' sites is code or data that moved
  into `.run`, the 6 bytes of the glide site the mod leaves stock, or the CFO oscillator's note operand.
- **Protected ranges.** No site of core or of the three mods touches a range in
  [build/build.py](../build/build.py)'s `PROTECTED`, and the linked MAIN OS differs from stock only at the
  sites and in the block elekloader appends after the image. elekloader's own checks pass: the other
  sections byte for byte stock, the MAIN OS unpacks in place to the patched image.

In digiemu (core 3.0 and the three mods, against the build running the same key scripts; an emulator run is
not a run on the unit). Start-up and Pages are on the files as they are, against the build (S56). The other
lines are on a dtogpp-filter-page2 whose KEY read portamento's glided note through an import, as the build
did up to S55, against v0.2.4; KEY's note source is the only difference:

- **Start-up.** core with the three, with params alone, with params and portamento, and with params and
  FILTER page 2 each start to the live user interface.
- **Pages.** The parameter pages, the TRIG page with PORT and LEG and FILTER page 2 with VED and KEY
  included, and the pattern played and stopped: every frame identical to the build's.
- **Saving and loading.** PORT, VED and KEY set, the project saved to the +Drive, the values turned back
  down, the project loaded: the values come back. The save ran the sound writer 2,304 times and the load the
  sound reader 1,152 times, each read through `dtogpp_params_load`; the call counts of the writer, the
  reader, the p-lock writers and the two lookups are the build's.
- **P-locks.** In GRID RECORDING, PORT and KEY locked on a trig, the project saved, the PORT lock changed,
  the project loaded: both locks come back as set.
- **Timing.** `scripts/digiemu_check.py 1.54 … --script pages --timing`: PASS; the audio render's margin
  37.2 % against stock's 37.7 % in the same check (worst 104,611 against 103,850 of 166,685 cycles; mean
  101,095 against 100,498); against stock, the screens differ only on the TRIG page (222 pixels) and FILTER
  page 2 (188 pixels), as the build's do.
- **[FUNC] + knob** on FILTER page 2 steps KEY to its minimum, as in the build: the start-up hook that copies
  that callable in works from `.run`.
- **The glide.** A 440 Hz recording on track 1, four trigs, every other one's note locked 4 semitones up: with
  dtogpp-portamento each note glides toward its pitch at PORT's rate; with params alone each note starts on
  its pitch.
- A knob turned by a fast run of encoder events can reach a slightly different value in two builds: the
  firmware's encoder acceleration depends on the time between events, which the builds' different code
  shifts by a few milliseconds. The events themselves are the same (logged at `Brain::enc`, `0x40008550`).
  Comparisons of values across builds therefore use frames where the same value is shown, and each build's
  own values before and after a save.

dtogpp-poly, in digiemu with core 3.0, elekloader's machine-pages 1.1 and the other six mods, against the
build (S56), both running the same key script:

- **Start-up.** The image starts to the live user interface.
- **Assigning POLY.** Tracks 2 and 3 set to POLY through the MACHINE menu ([FUNC] + [SRC], the fifth entry,
  [YES]): after each, the voice-pool map (the source track of each track) is the build's, ending with
  tracks 1 to 3 in one pool.

dtogpp-cfoo, on files: the SDK builds it (4,046 B in `.run`, 132 B in `.bss`, 11 sites); it links with core
3.0 and machine-pages 1.1 alone, beside dtogpp-poly, and beside dtogpp-params and dtogpp-portamento, and is
refused without machine-pages. With the other seven mods all the mods take 13,460 B of the 128 KB.
[check_against_build.py](check_against_build.py) (with `--include` the SDK's include folder) places the
synth and its tables where the build has them: every byte is the build's but the note read's opcode word,
and its 11 sites point at its own routines where the build's point at the build's.

dtogpp-cfoo in digiemu, in the same build of all eight mods, against the build (S56) with the same keys:

- **The page.** CFOO picked in the MACHINE menu (the sixth entry); its SRC page; every knob turned, A-H, by
  single detents, the pop-up and the cells after each; the LFO page and its DEST list (`CFOO:OSC1 Wave`
  ...); POLY on track 2 following it, its SRC page showing CFOO's knobs; the four tracks of the `cfoo4`
  script held and played: every frame the build's, but where a value reached by a run of detents, or
  the time a turned knob's value stays in its cell, depends on when each event arrives (knob D 80 against
  81; G after seven detents +0.39 against +0.45, late in a long script; turned alone, seven detents of G
  give +0.45 in both, frame for frame).
- **The texts.** The SRC page's title, its pop-up and the [TRK] pop-up on the CFOO track (`CFOO`) and on
  the POLY track following it (`POLY: CFOO`): every frame the build's.
- **The sound.** One note held on CFOO with its default knobs (OSC1 alone, SIN): 261.48 Hz in both, the same
  peak in every 20 ms, and the samples equal once the two notes are aligned on their start (the mods' note
  starts one block, 32 samples, earlier: when the key press arrives).
- **[FUNC] + knob.** In digiemu a knob turned while [FUNC] is held moved nothing, in the build as with the
  mods, so the snap points are not checked there.
