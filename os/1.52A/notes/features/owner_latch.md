# Owner latch: a note-off finds the voice its note-on took

## What this is

A live, external-MIDI or Loopback note that plays on a pooled voice is ended by a note-off event, and that event
has to find the voice again. The owner latch records, per voice, which track's note-on took it. The note-off scan
matches on that record rather than on the current pool map. A pattern switch that rebuilds the pool map between
note-on and note-off can therefore no longer strand a held note on a voice that nothing will release.

Conventions: MAIN OS load addresses. Voices and tracks are 0-7 in code and 1-8 (T1-T8) in the test descriptions.
Pools and `groupSource[]` are described in the ledger's
[pool map](../function_ledger.md#the-pool-map-build-and-refresh) section, and the remap pad in its
[audio ISR](../function_ledger.md#audio-isr-event-dispatch-and-the-note-gate) section; their byte runs are in
[the patch listing](../../docs/patch_listing.md#poly-engine). The per-voice
arrays and event fields are tabled in [voice_allocation.md](voice_allocation.md).

## Which notes need it

Only priority-2 notes (live, external MIDI, Loopback) queue a release event.

- `FUN_400ddd72`, the note-event poster, has exactly two callers: `FUN_400dd3a8` and `FUN_4007683c`. They are the
  only producers that can write `event[1] != 1`.
- The sequencer's trig evaluator `FUN_4006f1be` always sets `event[1] = 1`. The ISR's reschedule path is gated on
  flag `0x8000`, which audio trigs never set.
- A sequenced trig's LEN reaches the voice as a parameter. `event[0xc]` is written into the shared-SRAM slots
  `0x800014f0 + (voice + 0x127)*4` and `0x800014f0 + (voice + 0x11f)*4`, i.e. `0x8000198c[v]` and `0x8000196c[v]`.
  These are the same per-voice countdown timers that stock mute writes ([mute_by_origin.md](mute_by_origin.md)).
  The length is counted down per voice, and no release event is queued.

✅ Confirmed on the test unit. A stock ONESHOT, single-cycle, looped sound (AMP HOLD = NOTE, DEC = 0)
with one trig of LEN = 32 was switched to an empty pattern at step 16 → 17. It kept sounding exactly 16 more steps
and stopped at its full length. It behaved the same with a POLY follower added, and overlapping instances each ran
their own LEN.

A pattern switch cannot lose a sequenced trig's release, because nothing is queued to lose. The owner latch matters
only for the priority-2 path.

## The note-off path

For a note event with `event[1] != 1` that is not a lock trig, the POLY remap pad at `0x40037746` takes its
note-off arm at `0x400377b6`: `jsr 0x400b2214` (the region scan), then `bras` to `ident` at `0x400377b2`.

- The scan returns the matching voice in `d2`. If nothing matches it returns the track unchanged.
- The ISR's release branch (`FUN_40077120` decompile line 368) then releases that voice only if
  `priority == event[3]` and `held == event[6]`.

The scan's predicate in this build, over `v = 7..0`, first match wins:

```
ownerTrack[v] == event[2]  &&  priority[v] == event[3]  &&  held[v] == event[6]
```

The scan writes nothing. It fills its 64 B slot at `0x400b2214` (in the unused tail of `FUN_400b21b0`) exactly. It
fits because it reaches both `priority[]` and `held[]` through one pointer: `a4 = &priority[v]`, then
`a4@(300)` = `held[v]`, since `0x4395df20 - 0x4395ddf4 = 0x12c` fits a 16-bit displacement.

```
400b2214  2f02            movel %d2,%sp@-              | keep the incoming voice/track
400b2216  41f9 4399 02f0  lea 0x439902f0,%a0           | groupSource[]; ownerTrack[] = a0 - 8
400b221c  4280            clrl %d0
400b221e  2002            movel %d2,%d0                | d0 = the track            (owner-latch edit)
400b2220  4e71            nop                          |                           (owner-latch edit)
400b2222  7207            moveq #7,%d1                 | v = 7
400b2224  43f9 4395 ddf4  lea 0x4395ddf4,%a1           | priority[]
400b222a  b030 18f8       cmpb %a0@(-8,%d1:l),%d0      | lp: ownerTrack[v] == track ? (owner-latch edit)
400b222e  661c            bnes 0x400b224c
400b2230  49f1 1c00       lea %a1@(0,%d1:l:4),%a4      | a4 = &priority[v]
400b2234  2414            movel %a4@,%d2
400b2236  b4aa 000c       cmpl %a2@(12),%d2            | priority[v] == event[3] ?
400b223a  6610            bnes 0x400b224c
400b223c  242c 012c       movel %a4@(300),%d2          | held[v]
400b2240  b4aa 0018       cmpl %a2@(24),%d2            | held[v] == event[6] ?
400b2244  6606            bnes 0x400b224c
400b2246  2401            movel %d1,%d2                | match: return v in d2
400b2248  588f            addql #4,%sp
400b224a  4e75            rts
400b224c  5381            subql #1,%d1
400b224e  6ada            bpls 0x400b222a              | next v
400b2250  241f            movel %sp@+,%d2              | no match: d2 unchanged
400b2252  4e75            rts
```

## Where the latch lives

- `ownerTrack[8]` is 8 bytes of RAM at `0x439902e8`, which is `groupSource - 8`. There are no image bytes.
- One `lea 0x439902f0,%a0` reaches both arrays, as `%a0@(0,Xn)` and `%a0@(-8,Xn)`. The -8 fits the ColdFire brief
  extension word.
- It sits in a 24 B gap, `0x439902d8..0x439902f0`, that an image-wide scan found unreferenced. The SLICE
  round-robin latch `slice[8]` is the 8 bytes just below it, `0x439902d0..0x439902d8`
  ([slice_round_robin.md](slice_round_robin.md), [../memory_map.md](../memory_map.md)).

## The patch

Four runs, 14 B in all; no new landing pad.

| Address | Change | Purpose |
|---|---|---|
| `0x40037790` | `jmp 0x4015cb8a` → `jsr 0x4015cb8a` (one byte, `0x40037791`: `f9` → `b9`) | the voice allocator now **returns** here instead of to the ISR |
| `0x40037796` | 8 B `202a0008 118028f8` over the first four of the 14 `nop` that voice allocation leaves there | the latch write |
| `0x400b221e` | `moveb %a0@(0,%d2:l),%d0` → `movel %d2,%d0 ; nop` (`10302800` → `20024e71`) | the scan compares against the track, not `groupSource[track]` |
| `0x400b222a` | `cmpb %a0@(0,%d1:l),%d0` → `cmpb %a0@(-8,%d1:l),%d0` (`b0301800` → `b03018f8`; only `0x400b222d` changes) | the scan reads the latch, not the map |

The latch write, where the allocator returns (`d2` = chosen voice, `a0` = `groupSource[]` set by the pad's head
and only read by the allocator, `a2` = the event):

```
40037790  4eb9 4015 cb8a  jsr 0x4015cb8a               | voice allocation; returns d2 = voice
40037796  202a 0008       movel %a2@(8),%d0            | d0 = event[2], the note's track
4003779a  1180 28f8       moveb %d0,%a0@(-8,%d2:l)     | ownerTrack[voice] = track
4003779e  4e71 (x10)      nop                          | falls through
400377b2  2002            movel %d2,%d0                | ident (unchanged)
400377b4  4e75            rts
```

- `d0` is dead at the return point, because `ident` overwrites it with `d2` before anything reads it.
- The stack balances. The `jsr` pushes one return address and the allocator's own `rts` pops it. The allocator reads
  no stack arguments, so the shifted frame is harmless.
- The loop head at `0x400b222a` stays an instruction start of the same length, so `bpls` at `0x400b224e` is
  unaffected.
- Nothing in the image branches into the overwritten bytes. The only branches in these areas go to `0x40037790` and
  to the loop head.
- ⚠️ The 20 B of `nop` at `0x4003779e..0x400377b2` are reached only by fall-through from the latch write. Anything
  placed there must itself fall through into `ident`, so it is not general free space.

## Why the track is latched, not the Source

A note-on and its note-off carry the same `event[2]`, so a latch on the track stays valid whatever the pool map
does in between.

⛔ Ruled out: latching the pool's Source (`ownerSource[v] = S`). The note-off would have to compute S from the
**new** map to compare with it. That still misses a live note held on a follower track if the next pattern makes
that follower stand alone.

## Properties

- **Identity outside pools.** A plain track's remap is identity, so `ownerTrack[track] == track` and only
  `v == track` can match, the same result as without the latch.
- **Stale latches are harmless.** Every note-on that sets `priority[v]` passes through the allocator and rewrites
  `ownerTrack[v]`. A voice holding no note has `priority == 0` and fails the scan's priority test before the latch
  matters. Uninitialised RAM at boot is the same case, since all voices start idle.
- **Composes with voice allocation.** The allocator only ever hands over a voice at `priority == 0` (free, or just
  stolen), so the latch never relabels a voice whose current note could still be released.

## Measured results

All ✅ confirmed on the test unit. The test sound was ONESHOT, single-cycle, looped, AMP HOLD = NOTE,
DEC = 0, so the note's length is audible.

- **Pattern switch under a pooled note.** Pattern A: T3 Source + T4 POLY, one note from a MIDI track with
  CHAN = TRK3, LEN = 32. Pattern B: T4 not POLY. Switching in the first 16 steps, the note releases at its LEN.
- **Live notes on the follower.** Two notes were played live into the follower track (chromatic mode) and the
  pattern was switched while they were held. On release, both stop immediately.
- **Claps across a region change.** Pattern 1: T3 + T4 POLY + T5 POLY. Pattern 2: T3 alone, T4 as a Source with
  T5 POLY. A LEN = INF note came from the MIDI track, so only forced note-offs can end it.
  - Wherever the held note sat before the switch, pattern 2's clap trigs use exactly the voices left free:
    alternating when it sat on voice 3, voice 5 only when it sat on 4, voice 4 only when it sat on 5.
  - Every clap sounds.
  - A fast pattern that saturates the {4, 5} pool steals the held voice.
  - [STOP] stops everything.
- A LEN = INF note has no note-off to match, so it rings on until its voice is re-triggered or [STOP] is pressed.
  The latch does not change that.
- A same-note retrigger through the Loopback ends the previous instance, which is the MIDI behaviour
  ([tick_wipe_fix.md](tick_wipe_fix.md), "MIDI semantics"). ⚠️ The exact code path that produces this cut inside
  one pattern is not pinned.

Testing tips. With a one-shot sample a lost note-off is inaudible, because the sample ends by itself; use a looping
AMP HOLD = NOTE sound. The pool layout must differ between the two patterns: with no followers `groupSource[v] == v`
always, so a match on the pool map without the latch could not fail either. Reload the project between runs, because stuck voices
from a failed run accumulate.

## What it does not fix

- ⚠️ **Release pairing.** Two voices holding the same note from the same track look identical to the scan, and it
  releases whichever comes first in `v = 7..0`. The count of released voices is right; which instance goes first is
  not tied to ownership. Which voice releases in practice has not been modelled.
- The unverified steal-then-drop case in [voice_allocation.md](voice_allocation.md) leaves a voice at priority 0,
  and no latch can match that.

## Related

[voice_allocation.md](voice_allocation.md) ·
[POLY voice pool](../function_ledger.md#machine-assignment-the-pool-map-and-the-audio-isr)
([patch listing](../../docs/patch_listing.md#poly-engine)) ·
[tick_wipe_fix.md](tick_wipe_fix.md) · [midi_loopback.md](midi_loopback.md) ·
[../landing_pads.md](../landing_pads.md) · [../function_ledger.md](../function_ledger.md)
