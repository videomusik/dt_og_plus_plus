# Pattern layout

## What this is

The byte map of a Digitakt pattern and its kit, as the pattern SysEx dump carries them. The dump is
a fixed-size message, so its decoded payload is the raw serialized storage struct, written straight
out; the same struct, with the same per-track stride, is what the sequencer reads in RAM. The layout
below is read from the firmware's builder functions and checked byte for byte against real dumps.
The message envelope and the 8-in-7 decoding are in [sysex_dump.md](sysex_dump.md).

Offsets are into the decoded (post-8-in-7) payload unless stated.

## Top level

The dump body is **`0x6c00` = 27,648 B**, built by `FUN_400811be` as `[pattern 0x6200]` followed by
`[kit 0xa00]`.

| Offset | Size | Region | Built by |
|---|---|---|---|
| `0x0000` | 4 | Version, `0x00000009` | `FUN_4007a61a` (`*dst = 9`) |
| `0x0004` | 16 × `0x38f` = `0x38f0` | **16 track blocks**, `track[n]` at `4 + n*0x38f`. Blocks 0–7 are the audio tracks and 8–15 the MIDI tracks; block `n` is the UI's track `n + 1` | `FUN_4007a30e` |
| `0x38f4` | `0x28a0` (10,400) | **The p-lock pool**: 80 lanes × 130 B | `FUN_4007a61a` / `FUN_4007a55e` |
| `0x6194` | `0x24` (36) | Pattern trailer: the name (`UNTITLED` in the captures) and a few settings, copied from `runtime + 0x1ec40` | |
| `0x61b8` | to `0x6200` | Zero padding | |
| `0x6200` | `0xa00` (2,560) | **The kit**: version 9, the name (`KIT 1` in the captures, at `0x6204`), 8 sounds, FX and a tail | `FUN_4007a0ac` |
| `0x6c00` | — | End | |

- The kit holds only 8 sounds, for the audio tracks, while the pattern has 16 trig blocks: MIDI
  tracks have trigs but no sound.
- ⚠️ Which trailer bytes hold length, scale, swing and tempo is not mapped.

## The track block: `0x38f` (911 B), structure of arrays

`FUN_4007a30e` copies field arrays, not interleaved records, so one step is spread across parallel
arrays at fixed offsets. The per-step arrays are indexed by step 0–63.

| Offset in block | Size | Region |
|---|---|---|
| `0x000` | 64 × `u16` = 128 B | The per-step trig word (`dst[step*2]`) |
| `0x080` | 12 × 64 B = `0x300` | 12 per-step byte arrays (`dst[0x80 + k*0x40 + step]`, k = 0–11): the per-step trig parameters |
| `0x380` | 14 B | Per-track trailer: bytes `0x380`, `0x381`, `0x382`, a `u16` at `0x383`, bytes `0x385`–`0x387`, a `u16` at `0x388`, bytes `0x38a`–`0x38d` |
| `0x38e` | 1 B | Not written (padding) |

- Only steps flagged in a 64-bit active-step mask are written; the others keep the buffer default.
  That is where the sparse `0xff` runs in raw dumps come from.
- The 12 byte arrays are copied in a **fixed permutation** of their runtime order: the dump's array
  at `0x80` comes from runtime `0x280`, `0xc0` from `0x80`, …, `0x340` from `0x240`.
- ⚠️ Which byte array holds which trig parameter (velocity, length, micro timing, condition,
  probability, filter / LFO trig, …) is not mapped. Twelve matches the size of the trig-page
  parameter set ([parameters.md](parameters.md)).

### The trig word

- Bit **`0x0200`** is the note / audio trig.
- Bit `0x0010` is a per-step default set on about 32 steps of every track, touched or not. Do not
  treat a non-zero word as a trig; test `0x0200`.
- Other bits seen on real trigs: `0x0080`, `0x2000`, `0x0001`, `0x8000`. ⚠️ Their meaning (accent,
  condition, retrig, lock-trig, …) is not mapped.
- An empty track contains only `0x0000` and `0x0010`.

### Validated against a known pattern

✅ Confirmed on the test unit. A test pattern with trigs on audio and MIDI tracks was written on
the device, and its dump decoded to exactly its contents:

- every trig at its programmed step (steps counted from 0);
- 16 blocks, 8 audio then 8 MIDI, with block `n` = track `n + 1`;
- a MIDI track's trigs in its own block, so MIDI tracks use the same `0x38f` block.

## The p-lock pool

The pool is pattern-level: `PatternParamLocks` is a member of `Pattern` (`Pattern::vfunc_0` calls
the `PatternParamLocks::vfunc_0` destructor). It is **80 lanes × 130 B** (`0x50` × `0x82`).

### In the dump

- A lane is `[code : 1][track : 1]` followed by 64 × `u16` step values.
- **Empty lane**: header `ff ff`, then 128 B of `0x00`.
- **Occupied lane**: header `[code][track]`, with the track 0–15 as a plain byte. An unlocked step is
  `0xffff`; a locked step is the value.
- `code = DAT_401923c0[slot]`, the slot's p-lock code ([parameters.md](parameters.md#p-lock-codes)).
  For slots 16–36 the code equals the slot number.
- Autocorrelation of the pool region shows period 130, the lane size.
- A pattern with no locks is 80 empty lanes. A lane changes to `[code][track]` and values as locks
  are added.

Checked on a single-pattern dump and a whole-project dump: every empty lane reads `ff ff`, every
version word is `0x00000009`, and the test pattern's occupied lanes are byte-identical in both, so a
single-pattern dump is the same bytes as the pattern inside a project dump.

The test pattern's occupied lanes decode as:

| Parameter | Code | Slot | Track byte | Example lock value |
|---|---|---|---|---|
| Pitch Bend, on a MIDI track | `0x15` | 21 | the track's 0-based index (8–15) | `0x1F8F` (31.56) |
| Filter Frequency, on an audio track | `0x1a` | 26 | the track's 0-based index (0–7) | `0x64C0` (100.75) |

### Values are 8.8

A lock value is the raw internal parameter value, **8.8 fixed point** (`u16 = value × 256`), the
same encoding as the live value array and the descriptor min/max/default. The test pattern's Filter
Frequency locks all fall inside its 0–127 range with their fractional part kept, and its Pitch Bend lock
reads 31.56 on Pitch Bend's 0–64. There is no separate scaling table.

⚠️ The signed case (a negative Pan or Pitch Bend lock, two's complement) is not confirmed: the
validated pattern has no negative lock.

### At run time

- The lanes key on `(code, track)`: `FUN_4007a6a6` matches a lane by `code == header[0] && track ==
  header[1]`. Two parameters with the same code on one track would share a lane; slots 1–45 all have
  distinct codes.
- **The source table** (getter at the `PatternParamLocks` vtable `+0x28`) has a per-track stride of
  **`0x1b35`**: 64 steps × `0x6c` (108) B of parameter values (54 × `u16`, indexed `param*2`), then 53
  lock-flag bytes at `+0x1b00`. A locked `(param, track)` sets the byte at
  `base + track*0x1b35 + 0x1b00 + param`.
- `FUN_4007a6a6` sets one `(param, track)`: it finds or reuses a lane and copies that parameter's 64
  step values (source step stride `0x6c`) into it.
- `FUN_4007a55e` rebuilds the whole pool from the flag table: it clears every lane to `ff ff`, then
  writes one lane per set flag. It caps at 80 (guard `< 0x4f`) and then shows "Lock mem full!"
  (string at `0x401a4113`).
- `PatternParamLocks::vfunc_17` (`0x40013bd8`, an Observer) reads a
  `PatternParamLocksParamChangedInfo` (param at `+4`, track at `+8`). It applies a single change with
  `FUN_4007a6a6`, or rebuilds everything with `FUN_4007a55e` when param < 0 or track > 15.
- So the track blocks' arrays are the track's own per-step values, and p-locks are a separate
  pattern-level pool.

### Two serialization paths

1. **SysEx**: `FUN_400811be` → `FUN_4007a61a` / `FUN_4007a55e`, the `ff ff` form above.
2. **eMMC**: a `ValueWithMirror<Digitakt::patternParamLocks_t,
   Digitakt::patternParamLocksStorage_v0_t>`, whose `vfunc_9` is called by `Pattern::vfunc_9`. It is a
   live member of the Pattern object and belongs to the project-persistence path (ValueWithMirror →
   LZ4 → `MmcStreamWriter`). ⚠️ Its byte layout has not been seen, since no eMMC image has been read:
   it may match the SysEx layout or be a compacted `_v0_t`.

## The kit block: `0xa00` at `0x6200`

`FUN_4007a0ac` (224 B) writes:

- the version (`*dst = 9`) and the name;
- **8 sounds** through `FUN_40079fa6`, from a runtime stride of `0xa2` (162 B) per sound. Each sound
  blob starts with the magic `0xbeefbace`, the blob version 2 and the name, then the values keyed by
  p-lock code for slots 0–45 ([parameters.md](parameters.md#what-saving-copies));
- the FX (`FUN_40079d9a`);
- 8 blocks through `FUN_40079e44` (stride `0x70`);
- a tail of two `u16`.

The reverse, stored to runtime, is `FUN_4007aaba`.

## Finding the pattern in RAM

The sequencer reads the playing pattern through the global pointer `_DAT_4195fae8`: `FUN_4006fb8e`
(set active pattern) stores it at `0x4006fbbe`, and the step driver `FUN_4006f882` reads track `n`'s
block at `*(0x4195fae8) + n*0x38f` (objdump). The pointer leads into the project data's pattern array
([function_ledger.md](function_ledger.md#sequencer-and-pattern-playback)). ⚠️ The exact offset and
stride of that array inside the project data are not pinned ([open_questions.md](open_questions.md)).
The decoded dump is the struct's memory image, and the `0x38f` per-track stride is the anchor:
profiling a decoded pattern by `0x38f` gives exactly 16 dense track blocks and then a sparse tail.

## Related

- The project-meta dump (type `0x54`) is a separate, smaller struct and not part of the pattern
  ([sysex_dump.md](sysex_dump.md)).
- Every parameter's slot, code and range: [parameters.md](parameters.md).

## Ruled out

- ⛔ **A separate `7f 7f` storage form of the pool** (empty lanes `7f 7f`, occupied lanes
  `[code][0x80 | track]`, `0x8000` in empty lanes). It is what an LSB-first 8-in-7 decode produces;
  the dump has one form, the `ff ff` form. The tell is the version word decoding as `00 80 00 09`
  instead of `00 00 00 09` ([sysex_dump.md](sysex_dump.md#8-in-7-encoding)).
