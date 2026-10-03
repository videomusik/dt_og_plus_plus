# Voice allocation: free voice first, then steal

## What this is

Every note-on that reaches the audio ISR is handed to one small allocator that chooses the voice it plays on. It
takes a free voice in the note's pool if there is one, and only when the pool is saturated does it steal the next
voice in the rotation. This is what keeps a sequencer trig from being dropped because a live or MIDI note is
holding a voice. The allocator also runs for tracks that are not in any pool; such a track is a pool of one.

Conventions for this note: addresses are MAIN OS load addresses. Voices and tracks are 0-7 in code and 1-8 (T1-T8)
in the test descriptions, as on the device. A *pool* (in code: a *region*) is a Source track S plus the consecutive
POLY followers after it; `groupSource[v] == S` for each of its voices, and a track outside any pool is a pool of
one (see the ledger's [pool map](../function_ledger.md#the-pool-map-build-and-refresh) and the
[patch listing](../../docs/patch_listing.md#poly-engine)). Decompile line numbers refer to the project's Ghidra decompile
of the named function and are only a locator ([../analysis_method.md](../analysis_method.md)).

## The stock priority gate the allocator works with

### Per-voice state

| Array | Address | Contents |
|---|---|---|
| `priority[8]` | `0x4395ddf4` (`DAT_4395ddf4`), 32-bit entries | 0 = free, else the `event[3]` of the note holding the voice |
| `held[8]` | `0x4395df20` (`DAT_4395df20`), 32-bit entries | the held note, -1 = free; the array sits at `priority[]` + `0x12c` |
| `groupSource[8]` | `0x439902f0`, bytes | the Source of each voice's pool (POLY voice pool) |
| `groupCursor[8]` | `0x439902f8`, bytes | per Source: the voice used **last** |
| `ownerTrack[8]` | `0x439902e8`, bytes | the track whose note-on took the voice ([owner_latch.md](owner_latch.md)) |

`DAT_4395ddf4` is also the per-track "voice state" that the stock mute primitive `FUN_40078248` reads
([mute_by_origin.md](mute_by_origin.md)); it is one array with two uses.

### Event fields used here

A note event is `0x4c` bytes; fields are 32-bit words.

| Field | Byte offset | Meaning |
|---|---|---|
| `event[1]` | `+0x04` | 1 = note-on, 2 = note-off |
| `event[2]` | `+0x08` | target track (the originating track; the pool remap never rewrites it) |
| `event[3]` | `+0x0c` | priority class (see below); the ISR drops an event with `event[3] == -1` (cancel test) |
| `event[4]` | `+0x10` | re-trigger stamp, compared only when `event[9] & 0x40000` |
| `event[6]` | `+0x18` | note |
| `event[9]` | `+0x24` | flags; `(event[9] & 0x81) == 1` = a trigless lock trig (parameter-only) |
| `event[10]` | `+0x28` | sound-block pointer |

### The gate

In the audio ISR `FUN_40077120` a note event may act on a voice only if `priority[voice] <= event[3]` (decompile
line 241, where it reads `DAT_4395ddf4[uVar8] <= event[3]`). A note-on that passes writes `priority[voice] = event[3]` and `held[voice] = event[6]`. The release
branch (line 368) releases a voice only if `priority == event[3]` and `held == event[6]`, and clears `priority` to 0
(line 370).

There are two priority classes, and both are hard-coded constants:

| Producer | What it is | `event[3]` |
|---|---|---|
| `FUN_4006f1be` (decompile line 116, `param_5[3] = 1`) | the audio-track trig evaluator: every sequencer trig | **1** |
| `FUN_4007683c` (byte offset `0xc`, `*(int *)(event + 0xc) = 2`) | the generic note-event emitter: live notes, external MIDI (DIN, USB) and Loopback notes | **2** |

What follows from the gate:

- A trig on a voice holding a trig reads `1 <= 1` and passes, so trigs displace each other freely.
- A live note passes over anything (`priority` is at most 2).
- A trig on a voice holding a live note reads `2 <= 1`, fails, and the trig is **silently dropped** until that
  note's own note-off clears the voice.

Only live and MIDI notes, at priority 2, can block a trig. A pool that only rotated would therefore lose one voice
for sequencer trigs per held live note, which is exactly what the measurements below show.

⛔ Ruled out: `FUN_400dd3a8` as the live-note producer. It also writes `event[3] = 2`, but it is fed by the ISR's
shared-SRAM message handoff (`FUN_40003664`). MIDI note input goes dispatcher → `FUN_400c5a24` / `FUN_400c58e8` →
`FUN_400c53f2` / `FUN_400c578e` → `FUN_4007683c` → `FUN_400ddd72`.

⛔ Ruled out: `FUN_4006eaf4` as a note producer. It is the transport start/continue handler, and its event is type 4.

⛔ Ruled out: `FUN_4006f546` as a second priority-1 note source. It writes `[3] = 1` on a type-3 event whose payload
is a separate inner event, and type 3 never reaches the voice gate.

## Where the allocator runs

The ISR's note handler calls the POLY remap pad at `0x40037746` from the splice at `0x400774a2`
([ledger](../function_ledger.md#audio-isr-event-dispatch-and-the-note-gate),
[patch listing](../../docs/patch_listing.md#poly-engine)). For a note-on that is not a trigless lock trig, the pad calls the
allocator with `jsr 0x4015cb8a` at `0x40037790`.

- **Entry:** `d0` = S (`groupSource[track]`), `a0` = `groupSource[]`, `a2` = the event.
- **Exit:** `d0` = `d2` = the chosen voice. `a1` is saved and restored; `d1` and `a4` are scratch.
- The `rts` returns to `0x40037796`, where the owner-latch write runs ([owner_latch.md](owner_latch.md)). Execution
  then falls through into `ident` (`movel %d2,%d0 ; rts` at `0x400377b2`), which returns to the ISR.

Every pooled or plain note-on passes through the allocator. Note-offs go through the owner-latch scan instead, and
lock trigs go to the last-triggered voice without it.

## The algorithm

1. `d1 = groupCursor[S]`, the voice used last. `adv` once gives the first candidate, which is the plain round-robin
   voice. Keep it in `d2` as the cycle marker.
2. Walk the pool from that candidate and take the first voice with `priority[v] == 0`.
3. If the walk comes back to the marker, nothing is free. `d1` is then on the round-robin candidate again, and
   `clrl priority[d1]` makes it look free. That is the **steal**: the gate then reads `0 <= event[3]` and passes.
4. `groupCursor[S]` = the chosen voice, and return it.

`adv` is `v + 1`, wrapping to S at 8 or as soon as `groupSource[v] != S`. On a pool of one it always returns S.

- **Why `priority[v] == 0` is the free test, not `held[v] == -1`:** `priority` is the value the gate reads, so a
  voice that passes the test also passes the gate.
- **The victim of a steal** is the round-robin candidate, the voice after the one used last. The allocator does not
  search for the oldest note.
- **After a steal**, the incoming note writes its own priority at the note-on commit: 1 for a trig, 2 for a live
  note. The displaced note's note-off, if it was a live note, looks for `priority == 2` with its own note and owner
  track. It normally finds nothing, and does nothing, because that note no longer owns the voice.
- A steal is an audible hard cut, and it happens only when no voice in the pool is free.
- ⚠️ "Free" means released, not silent. A voice in its amp release has `priority == 0` and is still sounding, so
  free-first can cut a release tail. The bookkeeping has no way to tell a releasing voice from an idle one.

The pad, 70 B in the STL-span landing pad ([../landing_pads.md](../landing_pads.md)):

```
4015cb8a  2f09            movel %a1,%sp@-              | save a1
4015cb8c  49f9 4399 02f8  lea 0x439902f8,%a4           | groupCursor[]
4015cb92  43f9 4395 ddf4  lea 0x4395ddf4,%a1           | priority[]
4015cb98  4281            clrl %d1
4015cb9a  1234 0800       moveb %a4@(0,%d0:l),%d1      | d1 = groupCursor[S] = last voice used
4015cb9e  611e            bsrs 0x4015cbbe              | adv -> first candidate (the round-robin voice)
4015cba0  2401            movel %d1,%d2                | cycle marker
4015cba2  4ab1 1c00       tstl %a1@(0,%d1:l:4)         | loop: priority[v] == 0 ?
4015cba6  670a            beqs 0x4015cbb2              |   free -> take
4015cba8  6114            bsrs 0x4015cbbe              | adv
4015cbaa  b282            cmpl %d2,%d1                 | back at the marker?
4015cbac  66f4            bnes 0x4015cba2
4015cbae  42b1 1c00       clrl %a1@(0,%d1:l:4)         | none free: steal the round-robin voice
4015cbb2  1981 0800       moveb %d1,%a4@(0,%d0:l)      | take: groupCursor[S] = voice used now
4015cbb6  2401            movel %d1,%d2
4015cbb8  2002            movel %d2,%d0
4015cbba  225f            moveal %sp@+,%a1
4015cbbc  4e75            rts
4015cbbe  5281            addql #1,%d1                 | adv:
4015cbc0  0c01 0008       cmpib #8,%d1
4015cbc4  6706            beqs 0x4015cbcc              |   past voice 7 -> wrap
4015cbc6  b030 1800       cmpb %a0@(0,%d1:l),%d0       |   still in S's pool?
4015cbca  6702            beqs 0x4015cbce
4015cbcc  2200            movel %d0,%d1                | wrap: d1 = S
4015cbce  4e75            rts
```

## The patch

| Address | Size | Content |
|---|---|---|
| `0x4015cb8a` | 70 B | the allocator above (start of the STL span) |
| `0x40037790` | 6 B | `jmp 0x4015cb8a` (4ef9 4015cb8a); the owner latch then changes it to `jsr` |
| `0x40037796` | 28 B | 14 × `nop` up to `ident`; the owner latch then uses the first 8 B |

In [../../build/patch.json](../../build/patch.json) the 34 B at `0x40037790..0x400377b2` are split between this
feature (`voice_allocation`) and `owner_latch`, which holds `0x40037791` and the 8 B at `0x40037796`. The runs list
only the bytes that differ from stock, and the bytes at `0x4003779e` and `0x400377a6` (each the first byte of a
`nop`) are already `0x4e` in stock, so the runs are shorter than the table above; see
[../../docs/patch_listing.md](../../docs/patch_listing.md).

- ⛔ `ident` at `0x400377b2` (the last 4 B of the pad's block at `0x40037790`) is a shared branch target. The
  note-off arm (`bras` at `0x400377bc`, displacement -12) and the lock-trig arm (`bras` at `0x400377cc`,
  displacement -28) both land on it. Never overwrite or move it.
- The filler is `nop`, not `rts`, on purpose. Anything that ever reached it would fall through into `ident`, which
  is the identity remap and the safest place to end up.

## Measured results

Pool of 3 (T3 Source + T4/T5 POLY), trigs on T3, N notes held on a MIDI track with CHAN = TRK3. The table counts
surviving trigs out of 3.

| Allocator | 0 held | 1 held | 2 held | 3 held |
|---|---|---|---|---|
| rotation only, no free test, no steal (✅ test unit) | 3/3 | 2/3 | 1/3 | 0/3 |
| free-first without the steal (✅ test unit) | 3/3 | 3/3 | 3/3 | 0/3 |
| free-first + steal, as built (✅ test unit) | 3/3 | 3/3 | 3/3 | 3/3 |

- Rotation only, pool of 2: 2/2, 1/2, 0/2 for 0/1/2 held (✅ test unit). At both pool sizes the loss is one voice
  per held note. It was the same for external MIDI, Loopback notes and chromatic play on the Source, and it needs
  no pattern switch.
- 2 held still gives 3/3 without the steal because a voice holding a trig sits at priority 1, and `1 <= 1` passes.
- ✅ Steal then release (test unit). Setup: pool of 3, one T3 trig with LEN = 1/16, three notes held on the MIDI
  track. The trig steals one voice, later trigs re-use that voice by free-first, and releasing the keys stops the
  other two.
- ✅ On a pattern change, the new pattern's notes take over whatever was still sounding on those tracks, including
  the Source's (test unit).
- ⚠️ No orphaned note-off has been observed after a steal. That means no regression was observed, not that one is
  proven impossible.

## ⚠️ Unverified risk: steal, then drop

Reasoned from the allocator bytes; not tested on the device.

The allocator runs at the splice `0x400774a2`, before three ISR tests that can still drop a note-on:

- the cancel test (`event[3] == -1`, `beqw` at `0x400774be`);
- the mute block, which checks priority-1 events only, by originating track ([mute_by_origin.md](mute_by_origin.md));
- the stale-retrigger test (`event[9] & 0x40000` with `DAT_4395df4c[voice] != event[4]`).

On a saturated pool, a note-on can therefore **steal** (clearing `priority[victim]`) and then be **dropped**. The
victim keeps sounding its previous note at priority 0, booked free. Neither the owner-latch scan nor the ISR's own
release test can then match its note-off, because both require `priority == event[3]`. The result is a drone.

⚠️ Inferred from the same code: such a voice would stop when a later note-on takes it (it is booked free, so
free-first can pick it), or on All Sound Off.

Likely reproduction: pool of 3 (T3 + T4/T5 POLY), trigs on T3, hold 3 notes on a MIDI track with CHAN = TRK3, mute
T3, then release the keys.

The owner latch does not make this worse. On the free path it writes to an idle voice. On the steal path the
victim's priority is already cleared.

## Related

- [function_ledger.md](../function_ledger.md#machine-assignment-the-pool-map-and-the-audio-isr) and
  [patch_listing.md](../../docs/patch_listing.md#poly-engine): pools, `groupSource[]`, the remap pad and
  the pool cursor.
- [owner_latch.md](owner_latch.md): how a note-off finds its voice.
- [tick_wipe_fix.md](tick_wipe_fix.md): same-tick voice re-use, where free-first's rotation order matters.
- [mute_by_origin.md](mute_by_origin.md): the mute block that runs after the allocator.
- [midi_loopback.md](midi_loopback.md): MIDI tracks playing audio tracks, the main source of priority-2 notes.
- [../function_ledger.md](../function_ledger.md), [../landing_pads.md](../landing_pads.md),
  [../memory_map.md](../memory_map.md).
