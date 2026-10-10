# DT OG++

DT OG++ is a modified OS for the Digitakt (the original model). It adds round-robin slice selection
for the Slice machine, a Poly machine that lets one Sound play several notes at the same time, a way
for MIDI tracks to play the audio tracks without a cable, Chain Recording, CFOO (an 8-bit FM synth
machine), portamento and legato on every audio track, and velocity to filter envelope depth and filter
keytracking on every audio track's FILTER page. This repository contains no Elektron
firmware. You build DT OG++ yourself, on your own computer, from your own copy of Elektron's stock
update file of a supported OS version, and the build refuses any other input. DT OG++ is not
affiliated with or endorsed by Elektron.

Each supported OS version has its own OS folder, with its own build, documentation and notes:

| OS version | Page | Build command | Status |
|---|---|---|---|
| 1.54 | [os/1.54/README.md](os/1.54/README.md) | `python3 os/1.54/build/build.py` | developed: every feature below |
| 1.52A | [os/1.52A/README.md](os/1.52A/README.md) | `python3 os/1.52A/build/build.py` | on hold: SLICE: RRBN, the Poly machine and Virtual MIDI Loopback |

DT OG++ is developed on OS 1.54. The OS 1.52A build has not been developed past its last feature and
is on hold: it still builds and is documented as it is, with SLICE: RRBN, the Poly machine and Virtual
MIDI Loopback, but new features go into the OS 1.54 build only.

## NEW FEATURES

Each OS page describes the features in full, as they are in the build for that OS version.

### SLICE: RRBN

Slice Select (**SLICE**) on the SRC page of the Slice machine gains the value RRBN below NOTE. With
RRBN, each note trig plays the next slice of the GRID and starts over after the last one (with a GRID
of 4: 1, 2, 3, 4, 1, …). Details: [OS 1.54](os/1.54/README.md#slice-rrbn),
[OS 1.52A](os/1.52A/README.md#slice-rrbn)

### POLY MACHINE

The Poly machine turns an audio track into an extra voice for the audio track before it, so that one
Sound can play several notes at the same time. With the Poly machine on tracks 4 and 5, for example,
track 3 can play three notes at once. Details: [OS 1.54](os/1.54/README.md#poly-machine),
[OS 1.52A](os/1.52A/README.md#poly-machine)

#### MUTE FOLLOWS THE TRACK A TRIG CAME FROM

Mute follows the track a trig comes from, not the voice it plays on. On a pattern without Poly
tracks, mute silences the same trigs as on stock.
Details: [OS 1.54](os/1.54/README.md#mute-follows-the-track-a-trig-came-from),
[OS 1.52A](os/1.52A/README.md#mute-follows-the-track-a-trig-came-from)

#### VOICE ALLOCATION

Every note trig on a source track, and every note you play on it live or send to it over MIDI or
through a TRK value, takes a free voice of the pool, trying the voices in turn from the one after the
voice used last. When all voices of the pool are busy, the new note takes over that next voice.
Details: [OS 1.54](os/1.54/README.md#voice-allocation), [OS 1.52A](os/1.52A/README.md#voice-allocation)

### VIRTUAL MIDI LOOPBACK

Channel (**CHAN**) on the SRC page of a MIDI track gains eight values below 1: TRK8, TRK7 … TRK1. A
MIDI track set to TRK*n* plays audio track *n* with no cable: its notes, chords of up to four notes
and note-offs included, reach audio track *n* as they would through a MIDI cable from MIDI OUT to
MIDI IN. Details: [OS 1.54](os/1.54/README.md#virtual-midi-loopback),
[OS 1.52A](os/1.52A/README.md#virtual-midi-loopback)

### CHAIN RECORDING

OS 1.54 only. On the recorder page, data entry knob D sets a number of slots (4 to 64), armed by you
for each slot or, turned the other way, automatically. Each slot records RLEN steps, until the chain
is full and goes to the normal save. All slots have the same length, so the Slice machine with GRID
set to the number of slots plays one hit per slice.
Details: [OS 1.54](os/1.54/README.md#chain-recording)

### CFO OSCILLATOR

OS 1.54 only. CFOO is a sixth machine, after POLY: a synth voice of three oscillators with 8-bit
waveforms, where OSC2, OSC3 or both frequency-modulate OSC1. Its SRC page sets each oscillator's
waveform (SIN, TRI, SAW, SQR and the blends between them), the FM source and amount, the mix of the
three oscillators and the detunes of OSC2 and OSC3 (±24 semitones); the three waveform knobs show
the wave they play as a picture. It plays where a sample would, so the filter, the amp envelope, the
LFOs and the effects act on it, and a Poly track can follow it.
Details: [OS 1.54](os/1.54/README.md#cfo-oscillator)

### PORTAMENTO AND LEGATO

OS 1.54 only. Every audio track's TRIG page gets PORT, the glide time (OFF, 1–127), and LEG, a legato
switch. With PORT above OFF a new note glides from the pitch the track is playing to its own. With LEG
ON only a legato note glides, one whose trig comes before the previous note's LEN has ended, and it
leaves the amp envelope running without a new attack. PORT and LEG are saved with the sound and can
be locked per trig. Details: [OS 1.54](os/1.54/README.md#portamento-and-legato)

### FILTER: VED AND KEY

OS 1.54 only. Every audio track's second FILTER page gets VED and KEY. VED (Vel to Env Depth, 0–100 %)
sets how much the velocity decides the filter envelope's depth: at 0 % every note gets ENV's whole
depth, and the higher VED, the less a note softer than velocity 100 gets and the more a harder one
gets. KEY (Keytracking, −394 % to 394 % in steps of 6.25 %) moves the cutoff with the note's distance
from C4: at 100 % the cutoff follows the pitch. VED and KEY are saved with the sound and can be locked
per trig. Details: [OS 1.54](os/1.54/README.md#filter-ved-and-key)

For how each feature works inside the firmware, see the feature list in the OS folder's notes
(OS 1.54: [os/1.54/notes/README.md](os/1.54/notes/README.md#the-features-in-this-build)).

## DIFFERENCES FROM STOCK YOU MAY NOTICE

A Digitakt running DT OG++ still reports the stock OS version it was built from: the build does not
change the version string. The OS folder's `verify.py` (`python3 os/<os>/build/verify.py`) tells a
DT OG++ file from the stock one.

## QUICK START

You need:

- your stock update file of an OS version that has an OS folder (see the table above);
- Python 3.7 or later (standard library only) and bash;
- to build the firmware tool: a C compiler (`cc`), `patch`, `shasum` or `sha256sum`, and git to
  clone the tool's source once.

The build runs on macOS and Linux; on Windows, use WSL2
([docs/toolchain.md](docs/toolchain.md#7-linux-and-windows), section 7). Full details are in
[docs/building.md](docs/building.md), and the exact steps for each OS version are on its OS page.

1. Save your stock `.syx` update file in the `sysex` folder in the repository root (create the
   folder), under the name its OS page gives (OS 1.54: `sysex/Digitakt_OS1.54.syx`).
2. Clone [elektron-firmware-tool](https://github.com/mischa85/elektron-firmware-tool) next to this
   repository and check out the commit the build is pinned to. From the repository root, run:

   ```
   git clone https://github.com/mischa85/elektron-firmware-tool ../elektron-firmware-tool
   git -C ../elektron-firmware-tool checkout 065d18f
   ```

3. From the repository root, run, with `<os>` the OS folder of your stock file and `<version>` its
   build's version (v0.2.3 for OS 1.54, v0.1 for OS 1.52A):

   ```
   bash build/build_tool.sh
   python3 os/<os>/build/build.py
   python3 os/<os>/build/verify.py out/<os>/dt_og_plus_plus_<version>_<hash8>.syx --tool tool/bin/elektron-firmware-tool-capped
   ```

   OS 1.54:

   ```
   bash build/build_tool.sh
   python3 os/1.54/build/build.py
   python3 os/1.54/build/verify.py out/1.54/dt_og_plus_plus_v0.2.3_<hash8>.syx --tool tool/bin/elektron-firmware-tool-capped
   ```

   In the last command, use the file name that `build.py` prints at `[7/7] wrote`.

4. Check that `verify.py` reports the file as DT OG++ (OS 1.54: `DT OG++ MAIN OS` for section 3
   and, with the pinned tool, `DT OG++ (reference build)` for the file).

What the three commands do, and their options: the OS page
(OS 1.54: [os/1.54/README.md](os/1.54/README.md#quick-start)).

## BEFORE YOU FLASH

Building never talks to the Digitakt; flashing is a separate step and your own decision. Read
[SAFETY.md](SAFETY.md) first. It lists what to have ready (your stock file, Elektron Transfer, a DIN
MIDI interface, backups of your projects and samples), explains why you flash only the file that the
OS folder's `build.py` names, and gives the recovery steps. Some of the patched code can run while
the Digitakt starts up, so a problem can show before the normal update route is available. The
recovery route through the STARTUP menu needs a DIN MIDI cable into the Digitakt's MIDI IN, not USB.

## GOING BACK TO STOCK

Flash your stock update file of the same OS version, the one your build was made from, the same way
as any OS update. Back up your projects before you flash, in either direction.

What happens to a project saved with DT OG++ when you load it on stock firmware, and to a stock
project on DT OG++, is on the OS page
(OS 1.54: [os/1.54/README.md](os/1.54/README.md#going-back-to-stock)).

## FOR DEVELOPERS AND AGENTS

- [AGENTS.md](AGENTS.md): instructions for coding agents working in this repository.
- [os/README.md](os/README.md): the OS folders: what each holds, how a command names its OS, and
  how a new one is started.
- [notes/README.md](notes/README.md): the conventions of the notes, and the notes on method, the
  hardware and recovery that every OS version shares.
- [docs/building.md](docs/building.md): the build and its checks.
- [docs/toolchain.md](docs/toolchain.md): the analysis toolchain (extraction, Ghidra, emulation).
- [scripts/manual/README.md](scripts/manual/README.md): turning your own copy of the Digitakt manual
  into searchable text.
- OS 1.54: [os/1.54/notes/README.md](os/1.54/notes/README.md): the reverse-engineering notes,
  including the notes on the features.
- OS 1.54: [os/1.54/docs/patch_listing.md](os/1.54/docs/patch_listing.md): every changed byte,
  with its disassembly.
- OS 1.54: [os/1.54/docs/reference.md](os/1.54/docs/reference.md): the protected ranges and the
  expected hashes.
- OS 1.54: [os/1.54/src/](os/1.54/src/): the source of Chain Recording, the CFO oscillator and
  portamento, with the generators that check them and write their runs into `patch.json`.
- OS 1.52A (on hold): [os/1.52A/notes/README.md](os/1.52A/notes/README.md),
  [os/1.52A/docs/patch_listing.md](os/1.52A/docs/patch_listing.md) and
  [os/1.52A/docs/reference.md](os/1.52A/docs/reference.md).

## LICENCE

This repository's own content is dedicated to the public domain under CC0 1.0 Universal (see
[LICENSE](LICENSE)). Two parts keep their own terms:

- `build/tool_patches/cap_window_1mb.patch` contains context lines from elektron-firmware-tool. Those
  lines stay under the tool's MIT License.
- `scripts/ghidra_ext/` is derived from Ghidra's Motorola 68000 processor module and is distributed
  under the Apache License 2.0 ([scripts/ghidra_ext/NOTICE.md](scripts/ghidra_ext/NOTICE.md)).

What comes from where, and the licence texts: [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
