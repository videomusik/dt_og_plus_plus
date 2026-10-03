# Parameters

## What this is

Every Digitakt parameter is described by one record in a single table in the MAIN OS image: its
page, the slot its value lives in, its range and default, its MIDI CC and its names. This note gives
the record layout, the full list of parameters by page, how the value arrays are laid out, and the
code that reads and writes them. Every range and default below was read from the stock descriptor
table of OS 1.52A.

## Conventions

- Values are **8.8 fixed point**: a stored `u16` / `s32` is the displayed value × 256. The ranges
  below are divided by 256. Where a limit is not a whole number the raw word is given.
- A **slot** is the index into a value array (`array + slot*2`). Slots are relative to the struct a
  page writes, not global: Filter, Amp, LFO and SRC write the per-track sound array, while FX, Delay,
  compressor, master and MIDI pages write their own structs. That is why low slot numbers recur.
- The **code** is the p-lock lane code for a slot (see [P-lock codes](#p-lock-codes)).
- Two orders matter and they differ: the **slot order** (the memory layout, which governs p-locks
  and saving) and the **screen order** (positions 1–8 on a page, a curated subset and reorder).

## The descriptor table

`0x4018ff88`: **164 records × `0x34` (52) bytes**. Code addresses a record by its index 0–163
(`table + index*0x34`, clamped `index < 0xa4`). In the decompile an accessor reads
`(&DAT_4018ff88)[id * 0xd]` (0xd words = one record), or the same form on one of the base addresses
below.

| Offset | Field |
|---|---|
| `+0x00` | Page id; `-1` for the track-level records |
| `+0x04` | Slot; `-1` for the track-level records |
| `+0x08` | Min (8.8, signed) |
| `+0x0c` | Max (8.8) |
| `+0x10` | Default (8.8) |
| `+0x14` | 0 or 1; not decoded |
| `+0x18` | MIDI CC number in the high half-word (`0x00xx_ffff`); `0xffffffff` when none. ⚠️ LFO Speed and Depth carry a second number in the low half-word; not decoded |
| `+0x1c` | Not decoded |
| `+0x20` | A parameter id. It is **not** the record index: Slice Select is record 136 with id `0xcb` |
| `+0x24` | Flags (for example `0x0e00`) |
| `+0x28` | Pointer to the long name ("Slice Select") |
| `+0x2c` | Pointer to the category name ("Slice") |
| `+0x30` | Pointer to the short name ("SLICE") |

### The code that reads it

| Function | What it does |
|---|---|
| `FUN_40078a68(index)` (34 B, 37 callers) | Record index → slot (`+0x04`) |
| `FUN_40078bf4(index)` (56 B, 32 callers) | Min and max: a thunk to `FUN_400d7e64` that copies them from `0x4018ff90 + index*0x34` (`+0x08`) into the caller's out-parameters |
| `FUN_4000f9a2` / `FUN_4000f9c4` | The short name (`+0x30`) and the long name (`+0x28`) |
| `FUN_40078808` | Runs once at boot. Walks all 164 records and builds two reverse tables in RAM: `0x419607a0` (slot → record, machine-independent) and `0x41960874` (the 8 SRC slots × 4 machines → record; it clears `0x80` B, 32 entries) |
| `FUN_40078c2c(slot, machine)` | Resolves a slot to a record. Slots `0x11`–`0x18` (the 8 SRC slots) go to `0x41960874 + ((slot − 0x11) + machine*8)*4`; every other slot to `0x419607a0 + slot*4`. It returns 0 for `machine >= 4`. This is why Slice Select and Sample Start are the same physical slot (21), read per machine |
| `FUN_40078df4(type)` | The machine-type name, from the table at `0x4018fc2c` (`ONESHOT`/`SAMP`, `WERP`/`WERP`, `REPITCH`/`PTCH`, `SLICE`/`SLIC`); `type < 4`, UI only |
| `FUN_40078cc2` | ⚠️ A page-order / enumeration list reader (bound `<= 0xce`) |

Code also reads the table through the base addresses `0x4018ff8c`, `0x4018ff9c`, `0x4018ffa8` and
`0x4018ffb8`: the same records, at `+0x04`, `+0x14`, `+0x20` and `+0x30`.

### The runtime twin

`0x4193f1a8` in `.bss` holds **164 runtime records × `0x54` B**, parallel to the descriptor table
(same index). `+0x00` flags, `+0x04` 16 B copied from `.rodata`, **`+0x14` the value-formatter
`std::function`**, **`+0x24` the cell-graphic `std::function`**, and two more at `+0x34` and `+0x44`.

- `FUN_4013b6a2` (19,842 B) fills all 164 records, unrolled.
- `FUN_40065550` (30 B) is the accessor: `0x4193f1a8 + index*0x54`, and an out-of-range index
  collapses to record 0.
- `ParameterSet::vfunc_23` (`0x4000edd4`) draws a cell's graphic through `+0x24`;
  `ParameterSet::vfunc_22` (`0x4000ee3c`) formats the value. A cell drawer may print its own number
  and never consult the formatter; see [features/midi_loopback.md](features/midi_loopback.md) and
  [visual_assets.md](visual_assets.md).

## Pages

| Page | Category | Records | Slots | Struct |
|---|---|---|---|---|
| 0 | Sample (ONESHOT) | 8 | 17–24 | sound |
| 1 | Werp | 8 | 17–24 | sound |
| 2 | Repitch | 8 | 17–24 | sound |
| 3 | Slice | 8 | 17–24 | sound |
| 4 | Amp | 8 | 38–45 | sound |
| 5 | — | 0 | — | no record uses page 5 |
| 6 | Filter | 14 | 25–37 | sound |
| 7 | Reverb | 8 | 25–32 | FX |
| 8 | Delay | 8 | 17–24 | FX |
| 9 | Master (compressor) | 9 | 33–41 | master |
| 10 | Master (input mixer) | 13 | 43–51 | master |
| 11 | Note 1–4 | 4 | 8–11 | trig |
| 12 | Src (MIDI track) | 8 | 17–24 | MIDI track |
| 13 | CC values | 8 | 25–32 | MIDI track |
| 14 | CC selects | 8 | 33–40 | MIDI track |
| 15 | LFO1 | 9 | 1–8 | sound |
| 16 | LFO2 | 9 | 9–16 | sound |
| 17 | Trig | 8 | 0–5, 7, 12 | trig |
| 18 | None | 1 | 0 | one record, "None", max 127 |
| −1 | track level | 17 | — | Machine Type, Solo, Mute, Pattern Mute, Track Level, Active Track, Global Mix Mode, and ten placeholder records named "Error" (records 0–5 and 11–14) |

## A. The per-track sound value array

A sound's values are 53 `u16` at **`sound + 0x14`**, running up to the machine-type byte at
`sound + 0x7e` (`(0x7e − 0x14) / 2 = 0x35` = 53). The stores bound the slot `< 0x35`. The sound
pages use slots 1–45.

### LFO1: page 15, slots 1–8

| Slot | Code | Parameter | Min | Max | Default |
|---|---|---|---|---|---|
| 1 | `0x01` | Speed | 0 | 127.99 (`0x7ffe`) | 112 |
| 2 | `0x03` | Multiplier | 0 | 23 | 4 |
| 3 | `0x05` | Fade In/Out | 0 | 127 | 64 |
| 4 | `0x07` | Destination | 0 | 127 | 0 |
| 5 | `0x09` | Waveform | 0 | 6 | 0 |
| 6 | `0x0b` | Start Phase | 0 | 127 | 0 |
| 7 | `0x0d` | Trig Mode | 0 | 4 | 0 |
| 8 | `0x0f` | Depth | 0 | 127.99 (`0x7ffe`) | 64 |

A ninth record (record 60, id `0x58`) is a second Multiplier on the same slot 2, with max 11 and
default 3. ⚠️ Which of the two applies when is not traced.

### LFO2: page 16, slots 9–16

| Slot | Code | Parameter | Min | Max | Default |
|---|---|---|---|---|---|
| 9 | `0x02` | Speed | 0 | 127.99 (`0x7ffe`) | 112 |
| 10 | `0x04` | Multiplier | 0 | 23 | 4 |
| 11 | `0x06` | Fade In/Out | 0 | 127 | 64 |
| 12 | `0x08` | Destination | 0 | 127 | 0 |
| 13 | `0x0a` | Waveform | 0 | 6 | 1 |
| 14 | `0x0c` | Start Phase | 0 | 127 | 0 |
| 15 | `0x0e` | Trig Mode | 0 | 4 | 0 |
| 16 | `0x10` | Depth | 0 | 127.99 (`0x7ffe`) | 64 |

As for LFO1, record 69 (id `0xa8`) is a second Multiplier on slot 10, max 11, default 3.

### SRC: pages 0–3, slots 17–24, per machine

The machine type (`sound + 0x7e`) selects which page's records describe slots 17–24. Slots 17–20
and 24 mean the same on every machine; 21–23 differ. Ranges are min–max, then the default.

| Slot | Code | ONESHOT (page 0) | WERP (page 1) | REPITCH (page 2) | SLICE (page 3) |
|---|---|---|---|---|---|
| 17 | `0x11` | Tune 4–88, 64 | Tune 4–88, 64 | Tune 4–88, 64 | Tune 4–88, 64 |
| 18 | `0x12` | Play Mode 0–3, 3 | Play Mode 0–3, 3 | Play Mode 0–3, 3 | Play Mode 0–3, 3 |
| 19 | `0x13` | Bit Reduction 0–127, 0 | Bit Reduction 0–127, 0 | Bit Reduction 0–127, 0 | Bit Reduction 0–127, 0 |
| 20 | `0x14` | Sample Slot 0–127, 0 | Sample Slot 0–127, 0 | Sample Slot 0–127, 0 | Sample Slot 0–127, 0 |
| 21 | `0x15` | Start 0–120, 0 | Segment Size 0–2, 1 | Start 0–120, 0 | **Slice Select 0–64, 0** |
| 22 | `0x16` | Length 0–120, 120 | Segment Mode 0–3, 3 | Length 0–120, 120 | Slice Length 0–63, 0 |
| 23 | `0x17` | Loop Pos 0–120.01 (`0x7802`), 0 | Bars 0–3, 2 | Bars 0–3, 2 | Slice Grid 0–4, 0 |
| 24 | `0x18` | Sample Level 0–127, 100 | Level 0–127, 100 | Level 0–127, 100 | Level 0–127, 100 |

Slice Select is record 136 at `0x40191b28` (id `0xcb`, CC `0x14`); Slice Length is id `0xcc` and
Slice Grid id `0xcd` (record 138). This build changes Slice Select's min (below).

### Filter: page 6, slots 25–37 (14 records on 13 slots)

| Slot | Code | Parameter | Min | Max | Default |
|---|---|---|---|---|---|
| 25 | `0x19` | Filter Type | 0 | 7 | 1 |
| 26 | `0x1a` | Frequency | 0 | 127 | 127 |
| 27 | `0x1b` | Gain and Resonance: two records share the slot | 0 | 127 | 0 |
| 28 | `0x1c` | Env. Depth | 0 | 127 | 64 |
| 29 | `0x1d` | Attack Time | 0 | 127 | 0 |
| 30 | `0x1e` | Decay Time | 0 | 127 | 64 |
| 31 | `0x1f` | Sustain Level | 0 | 127 | 0 |
| 32 | `0x20` | Release Time | 0 | 127 | 64 |
| 33 | `0x21` | Base | 0 | 127 | 0 |
| 34 | `0x22` | Width | 0 | 127 | 127 |
| 35 | `0x23` | Env. Delay | 0 | 127 | 0 |
| 36 | `0x24` | Sample-rate reduction (SRR) | 0 | 127 | 0 |
| 37 | `0x2d` | SRR Routing (pre/post filter) | 0 | 1 | 0 |

⚠️ Gain and Resonance are read as mutually exclusive by filter type. Memory is packed: slot 38 is
already Amp.

### Amp: page 4, slots 38–45

| Slot | Code | Parameter | Min | Max | Default |
|---|---|---|---|---|---|
| 38 | `0x25` | Attack Time | 0 | 127 | 0 |
| 39 | `0x26` | Hold Time | 0 | 127 | 127 |
| 40 | `0x27` | Decay Time | 0 | 127 | 64 |
| 41 | `0x28` | Overdrive | 0 | 127 | 0 |
| 42 | `0x29` | Delay Send | 0 | 127 | 0 |
| 43 | `0x2a` | Reverb Send | 0 | 127 | 0 |
| 44 | `0x2b` | Pan | 0 | 127 | 64 |
| 45 | `0x2c` | Volume | 0 | 127 | 110 |

### The rest of the array: slots 0 and 46–52

Every track uses the same 53-slot layout, but each kind of track fills a different range.

| Slot | Code | On an audio track | Used by |
|---|---|---|---|
| 0 | `0x00` | unused | no sound record (the slot-0 records are Trig Note, in the trig array, and "None") |
| 46 | `0x00` | unused | master input mixer: Input R Pan |
| 47 | `0x01` | unused | Input L Delay (and Stereo In Delay) |
| 48 | `0x09` | unused | Input R Delay |
| 49 | `0x02` | unused | Input L Reverb (and Stereo In Reverb) |
| 50 | `0x0a` | unused | Input R Reverb |
| 51 | `0x03` | unused | Dual Mono |
| 52 | `0x0b` | unused | no record at all |

The master mixer page covers slots 43–51; on an audio track, 43–45 are Amp's Reverb Send, Pan and
Volume. The rest of the pipeline is dimensioned for the same 53: the SRAM voice mirror (`0x6a` B per
voice), the 16.16 expansion (`0xd4` = 53 × 4), and the p-lock flag row (53 bytes;
[pattern_layout.md](pattern_layout.md)).

## B. Trig and note: the per-step arrays

A separate array in the track block, not the sound array ([pattern_layout.md](pattern_layout.md)).

| Slot | Parameter | Page | Min | Max | Default |
|---|---|---|---|---|---|
| 0 | Trig Note | 17 | 12 | 84 | 60 |
| 1 | Trig Velocity | 17 | 1 | 127 | 100 |
| 2 | Trig Length | 17 | 0 | 127 | 14 |
| 3 | Micro Timing | 17 | −23 | 23 | 0 |
| 4 | Filter Trig | 17 | 0 | 1 | 1 |
| 5 | Trig Condition | 17 | 0 | 64 | 0 |
| 7 | LFO Trig | 17 | 0 | 1 | 1 |
| 8–11 | Note 1–4 (chord) | 11 | 0 | 127 | 60, 64, 64, 64 |
| 12 | Trig Probability | 17 | 0 | 100 | 100 |

Micro Timing is the only negative minimum among the 164 stock records.

## C. Screen layouts: encoder positions 1–8

The screen order is a curated subset and reorder of the records, not the slot order.

**TRIG page** (✅ read off the test unit): 6 positions used, 2 free.

| Position | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 |
|---|---|---|---|---|---|---|---|---|
| Label | NOTE | VEL | LEN | PROB | FLT.T | LFO.T | free | free |
| Slot | 0 | 1 | 2 | 12 | 4 | 7 | — | — |

Micro Timing (slot 3) and Condition (slot 5) exist in the data but are set by other input, not
this encoder row.

**SRC, ONESHOT** (✅ read off the test unit)

| Position | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 |
|---|---|---|---|---|---|---|---|---|
| Label | TUNE | PLAY | BR | SAMP | STRT | LEN | LOOP | LEV |
| Slot | 17 | 18 | 19 | 20 | 21 | 22 | 23 | 24 |

**SRC, WERP** (✅ read off the test unit)

| Position | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 |
|---|---|---|---|---|---|---|---|---|
| Label | TUNE | PLAY | BR | SAMP | SEG | MODE | BARS | LEV |
| Slot | 17 | 18 | 19 | 20 | 21 | 22 | 23 | 24 |

⚠️ Predicted from the slot order, not checked on the device:

- LFO1 / LFO2: positions 1–8 = slots 1–8 / 9–16.
- AMP: positions 1–8 = slots 38–45.
- FILTER (two pages): page 1 ≈ slots 25–32, page 2 ≈ slots 33–37 and three blanks. The TRIG page
  shows that screen order can differ from slot order, so this one especially needs checking.

## D. The other parameter sets

### Reverb: page 7, slots 25–32

| Slot | Parameter | Min–max | Default |
|---|---|---|---|
| 25 | Pre-delay | 0–127 | 8 |
| 26 | Decay Time | 0–127 | 16 |
| 27 | FB Shelving Freq | 0–127 | 64 |
| 28 | FB Shelving Gain | 0–127 | 32 |
| 29 | Input HPF | 0–127 | 32 |
| 30 | Input LPF | 0–127 | 96 |
| 31 | Mix Volume | 0–127 | 110 |
| 32 | Rev | 0–1 | 1 |

### Delay: page 8, slots 17–24

| Slot | Parameter | Min–max | Default |
|---|---|---|---|
| 17 | Delay Time | 0–127 | 23 |
| 18 | Pingpong | 0–1 | 0 |
| 19 | Stereo Width | 0–127 | 64 |
| 20 | Feedback Gain | 0–127 | 32 |
| 21 | Feedback HPF | 0–127 | 32 |
| 22 | Feedback LPF | 0–127 | 96 |
| 23 | Reverb Send | 0–127 | 0 |
| 24 | Mix Volume | 0–127 | 110 |

### Compressor: page 9 ("Master"), slots 33–41

| Slot | Parameter | Min–max | Default |
|---|---|---|---|
| 33 | Threshold | 0–127 | 32 |
| 34 | Attack Time | 0–127 | 24 |
| 35 | Release Time | 0–127 | 32 |
| 36 | Makeup Gain | 0–127 | 64 |
| 37 | Ratio | 0–7 | 3 |
| 38 | Sidechain Src | 0–10 | 0 |
| 39 | Sidechain Filter | 0–127 | 80 |
| 40 | Dry/Wet Mix | 0–127 | 0 |
| 41 | Pattern Volume | 0–127 | 127 |

### Master input mixer: page 10, slots 43–51

| Slot | Parameter | Mono alternate on the same slot | Min–max | Default |
|---|---|---|---|---|
| 43 | Input L Volume | Stereo In Level | 0–127 | 0 |
| 44 | Input R Volume | | 0–127 | 0 |
| 45 | Input L Pan | Stereo In Balance | 0–127 | 64 |
| 46 | Input R Pan | | 0–127 | 64 |
| 47 | Input L Delay | Stereo In Delay | 0–127 | 0 |
| 48 | Input R Delay | | 0–127 | 0 |
| 49 | Input L Reverb | Stereo In Reverb | 0–127 | 0 |
| 50 | Input R Reverb | | 0–127 | 0 |
| 51 | Dual Mono | | 0–1 | 1 |

There is no standalone "Master" level record.

### MIDI track: pages 12–14

| Slot | Parameter | Page | Min–max | Default |
|---|---|---|---|---|
| 17 | Channel (record 140) | 12 | 0–15 | 0 |
| 18 | Bank | 12 | 0–127 | 0 |
| 19 | Program | 12 | 0–127 | 0 |
| 20 | Sub Bank | 12 | 0–127 | 0 |
| 21 | Pitch Bend | 12 | 0–64 | 32 |
| 22 | Aftertouch | 12 | 0–127 | 0 |
| 23 | Mod Wheel | 12 | 0–127 | 0 |
| 24 | Breath Controller | 12 | 0–127 | 0 |
| 25–32 | CC1–CC8 Value | 13 | 0–127 | 0 |
| 33–40 | CC1–CC8 Select | 14 | 1–119 | 70–77 |

The MIDI track never reaches past slot 40. Channel's cell drawer prints the stored value + 1, so
0–15 shows as channels 1–16 ([features/midi_loopback.md](features/midi_loopback.md)). This build
changes Channel's min (below).

## Where the values live

- **The sound struct.** `sound + 0x00`–`0x14` header, `+0x14`–`+0x7e` the 53 values, `+0x7e` the
  machine type byte. It exists in two forms with the same fields up to `+0x7e`: the kit-embedded
  sound (stride `0xa2` = 162 B) and a 200 B Sound object (`FUN_4000d2d6(base, track)` =
  `base + 0x60 + track*200`, clamping the track to 0–7).
- `FUN_40021a0e(sound)` returns the machine type (the byte at `+0x7e`, or −1).
- **Push to the engine.** `FUN_40076ee8(value, track, slot)` writes the `u16` into the SRAM voice
  mirror at `0x800014f2 + (slot + 8 + track*0x35)*2`, that is `0x80001502 + track*0x6a + slot*2`.
  Track `0x10` (master / FX) goes to `0x800014f2 + (slot + 0x1b0)*2`. A live edit reaches the
  running voice through this write alone.
- **The 16.16 form.** `FUN_400746cc(voice, block)` widens the 53 values to 32 bits (`<< 16`) at
  `0x80002B50 + voice*0xd4`, the form the render reads. Voice 8 is the master/FX block at
  `0x800031F0`.
- Two index schemes: the parameter system numbers tracks 0–15 (8 audio, 8 MIDI) with 16 = master/FX;
  the SRAM voice slots are 0–7 with 8 = master.
- Addresses of the SRAM blocks: [memory_map.md](memory_map.md). The render's use of them:
  [render_path.md](render_path.md).

## The encoder write path

Shared by `SoundParameterSet`, `FxParameterSet`, `TrigParameterSet` and `MidiParameterSet`:

1. `ParameterSet::vfunc_11` (`0x4000fb3e`) is the write entry. It reads the old value first
   (`vfunc_10`) and passes it on.
2. `ParameterSet::vfunc_8` (`0x4000f022`, 208 B): `new = old + delta`, clamped to the record's
   [min, max] from `FUN_40078bf4`. It then asks `vfunc_9` whether the value is permitted; if not, it
   scans up to `0x35` (53) steps in the delta's direction, then the other way, for the nearest
   permitted value. It returns the value and does not store it.
3. `vfunc_9` is the permission gate and the commit. For the MIDI parameters that is
   `MidiParameterSet::vfunc_9` (`0x40011420`), and the stores are `MidiParameterSet::vfunc_13` and
   `vfunc_29` (`*(u16*)(base + slot*2) = value`, slot `< 0x35`).

The chain was traced end to end for MIDI Channel. On it, the record's min and max are the only range
clamp, and a value that `vfunc_9` rejects is skipped by the scan. Per-class details and addresses:
[function_ledger.md](function_ledger.md).

## What saving copies

- `FUN_40079fa6` (262 B) serializes one sound: the magic `0xbeefbace`, the sound-blob version 2, the
  name, then the value array **keyed by code**: `dst[(code + 0xe)*2] = sound[0x14 + slot*2]`, for
  **slots 0–45 only** (loop bound `0x5c`). The machine byte goes from `src + 0x7e` to `dst + 0x7c`.
  `MmcFs::vfunc_13` calls it too, so the same blob is what persists to the eMMC.
- So slots 46–52 are never saved as sound values.
- Codes `0x00`–`0x2d` fill `dst + 0x1c`–`0x77` of a `0x60` B region, which leaves room for about two
  more codes before the machine byte.
- The machine byte is gated twice in stock: the machine setter `FUN_40021fce` rejects a value above 3
  (`moveq #3` at `0x40021ff4`), and the kit deserializer `FUN_4007a902` keeps only 0–3 and −1
  (`moveq #5` at `0x4007a994`). This build widens both for machine 4, POLY
  ([function_ledger.md](function_ledger.md#machine-selection-and-persistence),
  [docs/patch_listing.md](../docs/patch_listing.md#poly-engine)).

## P-lock codes

`DAT_401923c0[slot]` gives each slot its p-lock lane code (4-byte entries; the code is the low byte).
The pattern's p-lock pool keys a lane by `(code, track)` ([pattern_layout.md](pattern_layout.md)).

- LFO1's slots 1–8 get the odd codes `0x01`–`0x0f` and LFO2's slots 9–16 the even codes
  `0x02`–`0x10`.
- Slots 16–36 have code = slot (`0x10`–`0x24`).
- Slot 37 has `0x2d`, and slots 38–45 have `0x25`–`0x2c`.
- Slots 1–45 all have distinct codes, `0x01`–`0x2d`.
- Slots 0 and 46 both have `0x00`. Slots 47–52 repeat LFO codes: `0x01`, `0x09`, `0x02`, `0x0a`,
  `0x03`, `0x0b`.
- No slot 0–52 has a code above `0x2d`.

## Where this build changes the table

| Address | Record | Change | For |
|---|---|---|---|
| `0x40191b30` | 136, Slice Select, min | `0x00000000` → `0xfffffe00` (−2.0) | [SLICE round robin](features/slice_round_robin.md) |
| `0x40191c00` | 140, MIDI Channel, min | `0x00000000` → `0xfffff800` (−8.0) | [MIDI Loopback](features/midi_loopback.md) |

- Slice Select then dials −2 … 64: −2 is RRND, −1 is RRBN, 0 is NOTE, and 1–64 are the stock slice
  numbers. RRND is work in progress: in this build it is identical to RRBN.
- Channel then dials −8 … 15: −8 … −1 are TRK1–TRK8.
- Widening the min is the whole range change in both cases: the encoder path has no other clamp.

The byte runs are in [docs/patch_listing.md](../docs/patch_listing.md).

## Ruled out

- ⛔ **The descriptor has no min field.** Wrong: `+0x08` is the min. `FUN_40078bf4` hands the encoder
  its min and max from `+0x08` / `+0x0c`, and the `+0x08` values are the documented lower limits
  (Trig Note 12, Trig Velocity 1, Micro Timing −23, Tune 4, CC Select 1).
- ⛔ **`vfunc_25` and `vfunc_26` are not on the encoder write path.** Both are page gates: they test
  the record's page against the mask `0x1a07f` (pages 0–6, 13, 15, 16) before delegating to the base
  class. A hook there never fires for an encoder turn.
