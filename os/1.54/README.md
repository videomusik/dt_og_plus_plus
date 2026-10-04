# DT OG++ for OS 1.54

DT OG++ is a modified OS 1.54 for the Digitakt (the original model). It adds round-robin slice
selection for the Slice machine, a Poly machine that lets one Sound play several notes at the same
time, and a way for MIDI tracks to play the audio tracks without a cable. This repository contains
no Elektron firmware. You build DT OG++ yourself, on your own computer, from your own copy of
Elektron's stock OS 1.54 update file, and the build refuses any other input. DT OG++ is not
affiliated with or endorsed by Elektron.

The features are the same as in the OS 1.52A build, carried over to OS 1.54, plus Chain Recording,
which records a sample chain for the Slice machine one hit at a time. Everything else in OS 1.54,
including its Outbox 8 support, is Elektron's and unchanged.

Other OS versions and the shared documentation: [README.md](../../README.md).

## NEW FEATURES

### SLICE: RRBN

Slice Select (**SLICE**) on the SRC page of the Slice machine gains the value RRBN below NOTE. Turn
the knob down past NOTE to reach it. With RRBN, each note trig plays the next slice of the GRID and
starts over after the last one (with a GRID of 4: 1, 2, 3, 4, 1, …). Each track keeps its own order,
so two tracks that play the same sample do not affect each other. The trig's NOTE sets the pitch, as
it does with a numbered slice.

RRND (Round-Random) is work in progress: in this build it is identical to RRBN.

For RRBN the parameter shows a robin icon, where NOTE shows a piano keyboard. When the source track
of a Poly pool uses the Slice machine, the whole pool follows one slice order, and each voice keeps
its slice until its note ends. (RRND, RRBN, NOTE, 1–64)

### POLY MACHINE

The Poly machine turns an audio track into an extra voice for the audio track before it, so that one
Sound can play several notes at the same time. A Poly track belongs to its source track: the nearest
audio track before it that does not use the Poly machine. Several Poly tracks in a row belong to the
same source track, and together with it they form a voice pool. With the Poly machine on tracks 4
and 5, for example, track 3 can play three notes at once. A pattern can hold more than one pool, for
example tracks 3–5 and tracks 7–8.

Every note trig on the source track, and every note you play on it live or send to it over MIDI or
through a TRK value, plays on a voice of the pool with the source track's Sound, including the
parameter locks of the source track's trigs. Each voice runs its own envelopes, LFO and playback
position.

POLY is the fifth machine in the MACHINE select menu, after SLICE, and has a small keyboard icon.

The SRC, FLTR, AMP and LFO pages of a Poly track show the parameters of its source track, with the
source machine's layout and ranges, and any change you make there is made on the source track. SRC
page 2 shows the source track's waveform.

The Digitakt still has eight audio voices. A Poly track lends its own voice to the source track.

#### MUTE FOLLOWS THE TRACK A TRIG CAME FROM

Mute follows the track a trig comes from, not the voice it plays on. On a pattern without Poly
tracks, mute silences the same trigs as on stock. As on stock, notes you play live or send over
MIDI still sound on a muted track, and so do notes sent through a TRK value.

**Tip:** Mute the source track to silence all of its trigs, on whichever voice of the pool they play.

#### VOICE ALLOCATION

Every note trig on a source track, and every note you play on it live or send to it over MIDI or
through a TRK value, takes a free voice of the pool, trying the voices in turn from the one after the
voice used last. When all voices of the pool are busy, the new note takes over that next voice. A
sequencer trig is therefore not lost because notes you play live or send over MIDI hold voices of
the pool. A trigless lock trig on the source track changes the voice that was triggered last.

A note you hold on a voice of the pool (played live, sent over MIDI or sent through a TRK value)
stops when you release it, even after a change to a pattern that arranges the pools differently.

A voice counts as free as soon as its note is released, so a new note can cut a release tail that is
still sounding.

A track that is not part of a Poly pool is a pool of one voice.

### VIRTUAL MIDI LOOPBACK

Channel (**CHAN**) on the SRC page of a MIDI track gains eight values below 1. Turn the knob down
from 1 to go through TRK8, TRK7 … TRK1. A MIDI track set to TRK*n* plays audio track *n* with no
cable: its notes, chords of up to four notes and note-offs included, reach audio track *n* as they
would through a MIDI cable from MIDI OUT to MIDI IN. While CHAN is on a TRK value, the parameter
reads TRK and shows the track number 1–8, and the popup reads "Track" instead of "Channel".

- Audio track *n* plays the notes only if it listens to MIDI
- In SETTINGS > MIDI CONFIG > CHANNELS > TRACK *n* must be set to a channel, it must not be OFF.
  Which channel does not matter, but the audio track now _listens_ to that MIDI channel.
- External MIDI can play the same audio track at the same time.
- As through a cable, notes that another MIDI track sends on audio track *n*'s channel reach audio
  track *n* too while a MIDI track is set to TRK*n*.
- As through a cable, a note that the MIDI track plays again while it still holds it ends the earlier
  one instead of layering on it.
- Notes that reach an audio track through a TRK value **are not recorded as trigs when you record live**.
- If audio track *n* is the source track of a Poly pool, the notes spread over the pool.

The notes are also sent from MIDI OUT and USB on audio track *n*'s channel, so an external instrument
on that channel plays them too. With MIDI OUT cabled back to MIDI IN, or with a device that echoes
MIDI back, every note arrives twice.

### CHAIN RECORDING

Chain Recording records a sample chain one slot at a time, so that the Slice machine can play it with
one hit per slice. On the recorder page, data entry knob **D** sets the chain. Turned up from OFF it
sets the number of slots, 4, 8, 16, 32 or 64, and you arm each slot yourself. Turned down from OFF it
sets the same numbers with automatic arming, AUTO 4 to AUTO 64: the recorder arms itself again after
each slot. With a chain set and RLEN at a number of steps (not MAX), each recording is one slot of
RLEN steps:

1. Turn **D** to the number of slots, for example up to 8. The prompt reads `YES: ARM 1/8`, or
   `YES: AUTO 1/8` when turned down.
2. Press **[YES]** to arm (`ARMED 1/8`), and play the sound: the threshold starts the slot, as a stock
   recording starts. **[FUNC] + [YES]** records the slot at once instead.
3. After RLEN steps the recorder stops, keeping what it has recorded. When you arm each slot, it waits:
   the prompt reads `YES: ARM 2/8`; arm and play again for each slot. With AUTO it arms itself
   (`ARMED 2/8`), and the next sound over the threshold records the next slot.
4. After the last slot, the recording is normalised as a whole, and trimming and saving work as for
   any recording.

All slots have the same length, so on a Slice machine with GRID set to the number of slots, each
slice is one of your hits. MEM shows the length of the whole chain; as on stock, `!!` beside it means
that it does not fit in the sample memory, and the time reads as seconds and hundredths: `03'42"` is
3.42 seconds.

- **[YES]** while a slot records stops the chain there and goes to the save, with the slots so far.
- **[FUNC] + [NO]** drops the whole chain: while armed, as on stock, and also while the recorder waits
  between slots for you to arm the next one.
- Turning **D** between slots starts a new chain. D changes the chain only while the recorder is
  idle, not while it is armed or recording; on the trim screen it moves its trim point as on stock.
- With AUTO, a sound still over the threshold when a slot ends starts the next slot at once: set the
  threshold above the tail of your sounds.
- Keep RLEN and the tempo the same for all slots of a chain; otherwise the slots differ in length.
- With RLEN at MAX, or D at OFF, the recorder works as on stock.
- The chain setting is not saved with the project.

Chain Recording has been tested on a Digitakt with OS 1.54. One display glitch is known: after the
screen that asks whether to apply the new sample to a track, the top and bottom of the screen may stay
black, with only the sample showing.

For how each feature works inside the firmware, see the feature list in
[notes/README.md](notes/README.md#the-features-in-this-build).

## DIFFERENCES FROM STOCK YOU MAY NOTICE

The Digitakt still reports OS 1.54: the build does not change the version string. Use
`os/1.54/build/verify.py` to tell a DT OG++ file from the stock one.

## QUICK START

You need:

- your stock OS 1.54 update file;
- Python 3.7 or later (standard library only) and bash;
- to build the firmware tool: a C compiler (`cc`), `patch`, `shasum` or `sha256sum`, and git to
  clone the tool's source once.

The build runs on macOS and Linux; on Windows, use WSL2
([docs/toolchain.md](../../docs/toolchain.md#7-linux-and-windows), section 7). Full details are in
[docs/building.md](../../docs/building.md).

1. Save the Digitakt OS 1.54 `.syx` update file as
   `sysex/Digitakt_OS1.54.syx` in the repository root (create the `sysex` folder).
2. Clone [elektron-firmware-tool](https://github.com/mischa85/elektron-firmware-tool) next to this
   repository and check out the commit the build is pinned to. From the repository root, run:

   ```
   git clone https://github.com/mischa85/elektron-firmware-tool ../elektron-firmware-tool
   git -C ../elektron-firmware-tool checkout 065d18f
   ```

3. From the repository root, run:

   ```
   bash build/build_tool.sh
   python3 os/1.54/build/build.py
   python3 os/1.54/build/verify.py out/1.54/dt_og_plus_plus_v0.1_<hash8>.syx --tool tool/bin/elektron-firmware-tool-capped
   ```

   In the last command, use the file name that `build.py` prints at `[7/7] wrote`.

4. Check that `verify.py` reports `DT OG++ MAIN OS` for section 3 (and, with the pinned tool,
   `DT OG++ (reference build)` for the file).

What the three commands do:

- `build/build_tool.sh` builds `tool/bin/elektron-firmware-tool-capped` from your clone in
  `../elektron-firmware-tool`. It copies the tool's sources into a build folder under `tool/`, checks
  that they are exactly the files of the pinned commit, applies
  `build/tool_patches/cap_window_1mb.patch` there and compiles them. It never downloads anything and
  never changes your clone; if the clone is missing or not at the pinned commit, it stops and prints
  the two commands of step 2. `--src DIR` builds from a clone in another folder, and `--bin-dir DIR`
  writes the binary somewhere else.
- `os/1.54/build/build.py` checks your stock file, applies `os/1.54/build/patch.json`, packs and
  re-checks the result, and only then writes
  `out/1.54/dt_og_plus_plus_v0.1_<first 8 hex digits of its SHA-256>.syx`. This is the file you
  flash. It prints the name at `[7/7] wrote`. `--syx FILE`, `--tool FILE` and `--out DIR` override
  the default locations.
- `os/1.54/build/verify.py` tells you what a `.syx` file is: stock Digitakt OS 1.54, DT OG++
  (reference build), or unknown. With `--tool` it also checks the firmware inside the file (section
  3, MAIN OS), whichever tool packed it.

The stock file's hash, and the expected result: [docs/reference.md](docs/reference.md#expected-result);
how the build checks it: [docs/building.md](../../docs/building.md#what-buildpy-checks).

## BEFORE YOU FLASH

Building never talks to the Digitakt; flashing is a separate step and your own decision. Read
[SAFETY.md](../../SAFETY.md) first. It lists what to have ready (your stock file, Elektron Transfer, a
DIN MIDI interface, backups of your projects and samples), explains why you flash only the file
`os/1.54/build/build.py` names, and gives the recovery steps. Some of the patched code runs while
the Digitakt starts up, so a problem can show before the normal update route is available. The
recovery route through the STARTUP menu needs a DIN MIDI cable into the Digitakt's MIDI IN, not USB.

## GOING BACK TO STOCK

Flash your stock `Digitakt_OS1.54.syx` the same way as any OS update. Back up your projects before
you flash, in either direction.

| Saved with DT OG++ | Loaded on stock OS 1.54 |
|---|---|
| SLICE set to RRBN or RRND | Most likely the track plays with SLICE at NOTE. |
| A track with the Poly machine | The track most likely comes back with the Oneshot machine. |
| A MIDI track with CHAN at TRK1–TRK8 | Most likely the value is kept, and that MIDI track sends nothing. |
| A sample recorded with Chain Recording | An ordinary sample. |

A project made on stock firmware loads unchanged on DT OG++. Details:
[notes/compatibility.md](notes/compatibility.md).

## FOR DEVELOPERS AND AGENTS

- [AGENTS.md](../../AGENTS.md): instructions for coding agents working in this repository.
- [notes/README.md](notes/README.md): the OS 1.54 reverse-engineering notes.
- [notes/README.md](../../notes/README.md) (shared): the conventions and the method that the notes of
  every OS version follow.
- [docs/building.md](../../docs/building.md): the build and its checks.
- [docs/reference.md](docs/reference.md): the protected ranges and the expected hashes for OS 1.54.
- [docs/patch_listing.md](docs/patch_listing.md): every changed byte, with its disassembly.
- [docs/toolchain.md](../../docs/toolchain.md): the analysis toolchain (extraction, Ghidra,
  emulation).
- [os/README.md](../README.md): the OS folders, one per supported OS version.

## LICENCE

This repository's own content is dedicated to the public domain under CC0 1.0 Universal (see
[LICENSE](../../LICENSE)). Two parts keep their own terms:

- `build/tool_patches/cap_window_1mb.patch` contains context lines from elektron-firmware-tool. Those
  lines stay under the tool's MIT License.
- `scripts/ghidra_ext/` is derived from Ghidra's Motorola 68000 processor module and is distributed
  under the Apache License 2.0 ([scripts/ghidra_ext/NOTICE.md](../../scripts/ghidra_ext/NOTICE.md)).

What comes from where, and the licence texts: [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md).
