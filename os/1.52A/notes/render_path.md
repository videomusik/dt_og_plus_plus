# Audio render path

## What this is

The Digitakt renders all audio in one interrupt handler. Once per block of 32 samples per track, the
handler turns queued note events into per-track state, runs a two-function software pipeline over
all eight tracks, filters and mixes the result, and hands it to the codec. This note maps that pass
from the event queue to the output buffer. Any code that hooks the render must respect the rules at
the end.

## Where it runs

- **The audio ISR** is `FUN_40077120` (4,280 B). Its vector is `0x400002fc`, installed by the engine
  init `FUN_40076b5a`. It runs above every RTOS task; the tasks only feed it (see
  [architecture.md](architecture.md)).
- **Block size.** Each pass renders 32 samples per track into eight per-track buffers at
  `DAT_80001a18 + track*0x80` (0x80 B each).
- **Output.** eDMA streams the rendered output to the codec on SSI0 (`0xFC0B8000`, 48 kHz). Section 2
  configures the codec at boot; see [section2_map.md](section2_map.md).
- **Eight voices.** The render has exactly eight voices, one per audio track. ✅ All eight tracks
  play SLICE at the same time with different samples (confirmed on the test unit). The POLY voice
  pool lets a Source track borrow the voices of the POLY tracks after it; it adds no voices
  ([function_ledger.md](function_ledger.md#machine-assignment-the-pool-map-and-the-audio-isr),
  [docs/patch_listing.md](../docs/patch_listing.md#poly-engine)).

## One pass, in call order

Roles in this note are read from the decompile and objdump (⚠️ static) unless marked ✅.

| # | Call | What it does |
|---|---|---|
| 1 | event queue loop | For each note-on, `track = event[2]` (`uVar8 = event[2]` in the decompile). The ISR writes the note into `0x80001f28 + track*4` (16.16), the priority into `DAT_4395ddf4[track]`, and the held note into `DAT_4395df20[track]` (−1 = free). Only for flag-0x8000 (MIDI/chord) events does it also bump `DAT_4395df4c + track*0x14`. At note-on, `FUN_40076f82(sound, track)` copies a Sound's parameters into the track's voice mirror at `0x80001502 + track*0x6a` and sets the track's machine byte `0x800018bc[track]`. |
| 2 | `FUN_4007489e(&DAT_800014f0)` (146 B) | Builds the engine object and returns its fixed address `0x80002760`: eight per-track blocks, stride 0x6a, taken from the voice mirror at `0x800014f0 + track*0x6a`. The window functions receive the block at `0x80002794 + track*0x6a`. |
| 3 | `FUN_400dd094(engine, DAT_401d3820, …)` | Called with the free-running tick `DAT_401d3820`. Role not read. |
| 4 | machine-type loop | For each track whose bit is set in this pass's trig mask, copies `0x800018bc[track]` into `DAT_41960316[track]`, the machine-type array the render dispatches on. |
| 5 | `FUN_400dc846(engine, DAT_41960316, tick, trigmask)` | Loops over the eight tracks and acts only on machine type 1 (WERP), writing `DAT_4398bbec` / `DAT_4398bbfc + track*0x14`. It returns a per-track mask, which the ISR ORs with the trig mask to form the **active mask**. |
| 6 | `FUN_4007442a(engine)` | Per-pass DSP preparation: an EMAC sine-oscillator recurrence (`DAT_8000d0d0/d0d4`), delay-line pointer updates, and programming eDMA channel 30 (TCD `0xFC0453C0`). |
| 7 | `FUN_40074e84(engine, DAT_41960316, active, …)` | Pipeline prologue plus the eight-track pitch loop (below). |
| 8 | `FUN_400754fe(engine, DAT_41960316, active, …)` | Pipeline main loop over tracks 0–7 (below). |
| 9 | `FUN_40072178(&DAT_80001a18, engine)` | A per-track stage over the buffers, working on `DAT_4395dd54`. Read as a gain stage; not verified. |
| 10 | per track: `FUN_4007239c(track, DAT_80001a18 + track*0x80, …)` or `FUN_4007236e(track)` | A field in the engine object selects which of the two is called. `FUN_4007239c` is the per-track fractional resampler: a 32-sample loop that reads the track's buffer as its source and writes it back interpolated, with state at `DAT_8000f134 + track*0x14`. `FUN_4007236e` is not verified. |
| 11 | `FUN_400713c0(&DAT_80001a18, …)` | Amp envelope **apply**: MAC-scales each track's buffer by its envelope. |
| 12 | `FUN_40072e68(&DAT_80001a18, engine, …)` | A second per-track filter stage (MAC biquad; coefficients at `0x401c7e68`). |
| 13 | `FUN_40073004(engine, …)` | Amp envelope **generator** (ADSR state machine; level accessor `FUN_40073112`). |
| 14 | per track: `FUN_40072544(…, DAT_80001a18 + track*0x80, bit, track)`, then `FUN_4007239c` again | The multimode filter: 8 types selected by `DAT_8000f1e4[track]`, coefficient tables at `0x4017xxxx`, 32-sample loop. |
| 15 | `FUN_4007204c`, then `FUN_40071920(out, …, engine)` (1,732 B) | The master mix, described after this table. |
| 16 | `FUN_400dd7e4` / `FUN_400032fc` on `DAT_80002160`; `FUN_40076350(…, &DAT_80001a18)` | Streams the block out. |

`FUN_40071920` works in this order:

- a coefficient loop over 10 channels: tracks 0–7, then 8 and 9 for master and send;
- per-track level: `FUN_40071fe4` × 8 on the track buffers;
- the FX functions `FUN_40073354`, `FUN_40073900` and `FUN_40073f5e`;
- a wait on eDMA status `0xfc0453de & 0x10`;
- the interleaved output, written to `DAT_80002160`.

## The pipeline: `FUN_40074e84` and `FUN_400754fe`

The two functions are **one software pipeline over the eight tracks**. `FUN_40074e84` is the
prologue: it computes track 0's read window. `FUN_400754fe` loops over track = 0..7. Iteration
`track` fetches the read window for `track + 1` and emits the audio of `track`. The first iteration
is peeled off into the prologue, which is why the code looks like "1 + 7".

- ✅ The window function's fourth argument is the track index, and it is called once per track per
  pass. A counter keyed on that argument is per track on the device (see
  [features/slice_round_robin.md](features/slice_round_robin.md)).
- Each function dispatches on machine type with an if/else chain (0, 3, 1, 2). An unknown machine
  type gets no window: the track is silent, and nothing crashes. ⚠️ Read for `FUN_40074e84`; the
  chain in `FUN_400754fe` has the same shape.

### `FUN_40074e84` (1,658 B): the prologue and the pitch loop

1. **The trig masks.** On entry it rotates them: `0x80001228 ← 0x8000122c`, then
   `0x8000122c ← active mask`.
   - Bit `t` of `0x80001228` is the render's "track t has a new trig" test for voice start. It is
     also passed to the ONESHOT window function, and the SLICE round-robin pad reads it.
   - The emit step's envelope gate reads `0x8000122c`.
2. **Track 0's window.**
   - If bit 0 of `0x80001228` is set, it does the one-time voice-start set-up.
   - It dispatches on `DAT_41960316[0]` to the window function, fourth argument 0.
   - It turns the returned window into the read position and the lengths. The arithmetic uses fixed
     overlap constants such as 0x8d (141) at the window edges (⚠️ read as a short crossfade).
   - It programs the two sample-fetch TCDs (see [Sample fetch](#sample-fetch-edma)).
3. **The pitch loop** (track 0..7). It computes each track's pitch and level from
   `0x80001f28 + track*4` and the track's engine block.
   - For a SLICE track whose Select byte (block `+8`) is 0 (NOTE), the pitch is replaced by the
     fixed `0x3c0000` (note 60 in 16.16).
   - Any other Select keeps the trig's note.

### `FUN_400754fe` (2,614 B): the main loop

Before the loop, the function waits for bit 0x80 of the word at `+0x1e` of the channel-32 TCD
(`_DAT_80001200`), then copies the fetched samples through the buffer at `0x80001208`. Then, for
track = 0..7:

1. **Ping-pong flip.** It flips `_DAT_402dbe00 ← 0x25e0 − _DAT_402dbe00`, once per track (objdump:
   the store at `0x400755da` is inside the loop, whose back-edge at `0x40075f34` returns to
   `0x400755b8`).
2. **Fetch, for track + 1** (skipped when track = 7).
   - `next = track + 1`. The new-trig bit is `(0x80001228 >> next) & 1`.
   - On a new trig it seeds the next track's voice state from the table at `0x402db3d0`, keyed by
     the block's sample byte. It computes the playback rate with `FUN_40074960` and clears 0x30 B
     with `FUN_400d7890`.
   - It dispatches on `DAT_41960316[next]` to the window function for block
     `0x80002794 + next*0x6a`. It passes the next track's note (`0x80001f2c + track*4`), its default
     end, and `next` as the fourth argument.
   - It updates the window bookkeeping and programs the fetch.
3. **Emit, for track.**
   - It waits on the fetch TCDs again (the same `+0x1e` bit).
   - It computes the rate with `FUN_40074960`. When the voice-state flag at `+0x28` is set, it gates
     the rate on the track's bit in `0x8000122c`.
   - It interpolates and filters the track's audio, and writes the track's 32 samples (16 × 2 words)
     to `DAT_80001a18 + track*0x80` through a pointer computed per track.

### The four window functions

Each window function returns the start and end of a sample read window; the engine around them is
machine-agnostic. They all read the engine block's `+2` (reverse), `+8` (Select or start), `+0xa`
(length) and `+0xc` (grid). Each has exactly two call sites. The counts were checked with a byte scan
that includes PC-relative calls (`jsr %pc@`, `0x4eba`), which absolute-address searches miss.

| Machine (type) | Window function | Call site in `FUN_40074e84` | Call site in `FUN_400754fe` |
|---|---|---|---|
| ONESHOT (0) | `FUN_4007499c` | `0x40074f50` | `0x4007569a` |
| WERP (1) | `FUN_40074bbe` | `0x40074fc0` | `0x4007573a` |
| REPITCH (2) | `FUN_40074a72` | `0x40075008` | `0x40075774` |
| SLICE (3) | `FUN_40074af2` | `0x40074f7a` | `0x400756d0` |

Only `FUN_40074af2` (at `0x40074b8c`) and `FUN_40074bbe` (at `0x40074c48`) read the slice-point
table at `0x402bb3b0`. The other three references to the table are its builders. The SLICE window
function is described in [features/slice_round_robin.md](features/slice_round_robin.md).

## Sample fetch (eDMA)

The MAIN OS drives eDMA directly. The TCD base is `0xFC045000`, at 0x20 bytes per channel.

| Channel | TCD | Used for |
|---|---|---|
| 30 | `0xFC0453C0` | Programmed every pass by `FUN_4007442a`, the oscillator path. |
| 31 | `0xFC0453E0` | Sample fetch. Its TCD pointer is `_DAT_80001204`. |
| 32 | `0xFC045400` | Sample fetch. Its TCD pointer is `_DAT_80001200`. |
| 42 | `0xFC045540` | Delay/FX, via `FUN_400732c6`. |

- **The fetch pointers.** No MAIN OS function assigns `_DAT_80001200` or `_DAT_80001204`. Their
  values come from the SRAM-init image data at `0x40215200` (`0x40214000 + 0x1200`), which
  `entry_4000045c` copies at boot.
- **What the pipeline writes.** The two pipeline functions write each fetch TCD's source (the sample
  in sample RAM, plus the window offset) and its destination, the fetch buffer `0x800013a0`.

## What the render publishes

The render keeps an eight-entry per-track playback status in on-chip SRAM, at
`0x8000edc8 + track*0x5e`. It is published for every track, whichever track is selected.

| Offset | Meaning |
|---|---|
| `+0x00` | Play position, 8.8 |
| `+0x10` | Sample length / end, 8.8. This is the default end handed to the window functions. |
| `+0x24` | Playing flag (byte) |

- `FUN_40074d40` initialises all eight blocks during engine init, from the table at `0x402db3d0`
  (length 0x120). It is not a per-trig reset.
- The per-track sample slot byte is at `0x8000ee20 + track*0x5e`. A value of 0x80 or more means no
  sample.
- UI readers:
  - `FUN_40075f42` returns the raw position.
  - `FUN_40075f58` returns position as a fraction of length, or 0 when the flag or the length is 0.
    It is the cursor's source.
  - `FUN_40075fd8` returns the flag.
  - `FUN_40075f9a` (called from the ISR) returns the same fraction at 15-bit scale.

These blocks are what the pool cursors draw; see
[features/pool_cursors.md](features/pool_cursors.md).

## Where this build hooks the audio path

| Address | Feature |
|---|---|
| `0x40074b38` | [SLICE round robin](features/slice_round_robin.md): negative Select jumps to the round-robin pad. |
| `0x400773e6` | POLY voice pool ([ledger](function_ledger.md#the-pool-map-build-and-refresh), [patch listing](../docs/patch_listing.md#poly-engine)): the set-active-kit call is redirected so the pool map is rebuilt on a pattern switch. |
| `0x400774a2` | POLY voice pool ([ledger](function_ledger.md#audio-isr-event-dispatch-and-the-note-gate), [patch listing](../docs/patch_listing.md#poly-engine)) and [voice allocation](features/voice_allocation.md): the note-on handling calls the pool's event code. |
| `0x400774ca`, `0x400774d6` | [Mute by origin](features/mute_by_origin.md). |
| `0x40077a72` | [Tick-wipe fix](features/tick_wipe_fix.md): 24 B, in place. |

The byte runs are in [docs/patch_listing.md](../docs/patch_listing.md).

## Rules for code in the render

- **Change state only on a trig edge.** The window functions run on every pass for every track with
  that machine type, whether or not a trig arrived. Stock NOTE mode, `(note − 12) & (count − 1)`, is
  pure and gives the same slice on every pass. Code added here may change state only on the track's
  new-trig edge, and it must return the same result on every other pass. See the latch in
  [features/slice_round_robin.md](features/slice_round_robin.md).
- **Read the whole host function before choosing a scratch register.** Registers that look free are
  often live across a hook. In `FUN_40074af2`, `%d0` carries the sample-slot base past the hook.
- **Expect a mistake to be heard at once.** An error here gives a wrong or stuck slice, silence, or a
  fault inside the audio interrupt. It is not a boot-path change, though: the render only runs once
  the engine is up. See [startup_hooks.md](startup_hooks.md#this-build-runs-code-at-startup) for the code this build runs at startup.

## Ruled out

- ⛔ **Ruled out: the two functions are two voice slots.** The ruled-out forms are: only tracks 0 and
  1 can slice; a hidden allocator cycles tracks through two slots; the second function is a crossfade
  engine. The two functions are one pipeline over all eight tracks. The two call sites per window
  function are real; the inference drawn from them was the error.
- ⛔ **Ruled out: nothing on the CPU writes the per-track buffers.** The emit step of `FUN_400754fe`
  writes each track's block of `DAT_80001a18` through a pointer computed per track (objdump: `%fp` =
  `0x80001a18` + track × `0x80` at `0x40075e70`, then 16 passes of two `movel %a0,%fp@+` up to `0x40075f0c`).
- ⛔ **Ruled out: `FUN_40072xxx` is a level meter or display code.** These functions are the
  per-track DSP chain.
- ⛔ **Ruled out: an emulator run shows the running state.** An idle run, with no voice started,
  shows only the defaults. See [emulator.md](../../../notes/emulator.md) and [analysis_method.md](../../../notes/analysis_method.md).
