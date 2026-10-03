# Tick-wipe fix: a voice released and re-triggered in one audio tick keeps its bookkeeping

## What this is

After the audio ISR has processed a tick's note events, it clears the bookkeeping of every voice released in that
tick. Stock code excludes voices re-triggered in the same tick from one of its two release sources but not from the
other. So a note-off followed by a note-on on the same voice within one tick leaves a voice that is sounding, is
booked free, and cannot be reached by any note-off. This build rewrites the 24-byte mask computation in place so
that both release sources are masked the same way. The defect is in stock code; MIDI Loopback makes it easy to hit.

Conventions: MAIN OS load addresses. `uVar2`, `uVar16`, `local_50` and friends are Ghidra's auto-names in the
decompile of the audio ISR `FUN_40077120`. Line numbers are a locator only. The per-voice arrays `priority[]` and
`held[]` are tabled in [voice_allocation.md](voice_allocation.md).

## The audio tick and the post-loop wipe

One ISR tick renders 32 samples at 48 kHz: 666.7 µs, i.e. 1500 ticks per second. The ISR adds `0x20` to the sample
counter `_DAT_4195ffdc` once per tick. During a tick the ISR accumulates three voice masks:

| Mask | Register at `0x40077a72` | Meaning |
|---|---|---|
| `uVar2` | `d3` | voices **triggered** this tick |
| `uVar16` | `d6` | voices released by a note-off **event** (decompile line 373) |
| `local_50` | `%fp@(-76)` | voices whose **LEN countdown** expired |

`d4`, `d5` and `d7` hold `uVar14`, `uVar15` and `uVar17`.

### The wipe

After the event loop (decompile lines 439-448; machine code `0x40077a72..0x40077aac`) the ISR builds a wipe mask in
`d2`. For every voice in it, it sets `priority[v] = 0` and `held[v] = -1`.

`d2` is **also the engine's release mask**. It survives to `0x40077d74`, where it is stored as `moveb %d2,%fp@(-35)`
(`local_27`), beside the trigger mask `moveb %d3,%fp@(-36)`, immediately before `jsr 0x400713c0`. The engine
receives both masks, and for a voice in both the trigger wins.

The only other write to `d2` on the way is the path taken when `0x4195ffd4 == 2`, at `0x40077c00`:

- it forces `d2 = d6 = d7 = -1` and `d3 = d4 = 0`, which releases every voice and triggers none;
- it calls `0x400dc828`;
- it sets `0x4195ffd4 = 1`.

⚠️ Inference, not traced: this is the engine-wide kill that All Sound Off (MIDI CC 120) reaches.

## The stock defect

The stock mask is:

```
d2 = (local_50 & ~uVar2) | uVar16          countdown releases masked by the triggers; event releases not
```

When a note-off releases voice v and a note-on re-uses v **in the same tick**, v is in both `uVar16` and `uVar2`.
The wipe then clears the new note's `priority` and `held`, while the engine, where the trigger wins, keeps it
sounding. The result is a **zombie**:

- it is sounding but booked free;
- no note-off can match it, since a live note's note-off needs `priority == 2`;
- only All Sound Off stops it.

✅ Confirmed on the test unit, running this build before the fix; the wipe that causes it is unchanged stock code
(read in the code). The isolating case was predicted from the code before the run: one note on a track with no
followers (P = 1) was re-triggered through the Loopback, then [STOP] was pressed, and it drones. With P = 1 there is
no pool and no round-robin, and in this sequence the note-off frees the voice before the new note-on, so no steal
takes place and this build's pool and allocation logic play no part in the result.

## Why MIDI Loopback exposes it

1. **No reordering.** The MIDI-input path posts each note event in wire order into an immediate FIFO bucket at the
   head of the event queue. The path is dispatcher → note cores `FUN_400c5a24` / `FUN_400c58e8` → per-track senders
   `FUN_400c53f2` / `FUN_400c578e` → `FUN_4007683c` → `FUN_400ddd72`. The audio side therefore sees each note's off
   before its on, as on the wire (`Off C3, On C3, Off D#3, On D#3` in a MIDI monitor log).
2. **One tick.** The Loopback posts a retrigger burst with no wire spacing, so the whole burst lands in one ISR
   tick. A DIN cable spaces messages about 1 ms apart at 31250 baud, so they usually fall into different ticks.
3. **Re-use within the tick.** Free-first walks the pool in rotation order from the voice used last
   ([voice_allocation.md](voice_allocation.md)). The first P − N new notes land on long-free voices and are safe.
   Every note after that re-uses a voice released earlier in the same tick.
4. **The wipe** then orphans each of those re-used voices.

For N notes re-triggered once on a pool of P voices, followed by [STOP]:

```
drones = max(0, 2N − P)
```

This formula matches every cell measured on the test unit (table below).

## The fix

Both release sources are now masked the way stock already masks the countdown term:

```
d2 = (uVar16 | local_50) & ~uVar2
```

The patch is 24 B rewritten in place at `0x40077a72..0x40077a8a`, with no landing pad. Source:
[../../analysis/tick_wipe_fix.s](../../analysis/tick_wipe_fix.s).

```
40077a72  2006            movel %d6,%d0                | d0 = uVar16    (event releases)
40077a74  80ae ffb4       orl %fp@(-76),%d0            | d0 |= local_50 (countdown expiries)
40077a78  2403            movel %d3,%d2                | d2 = uVar2     (triggers this tick)
40077a7a  4682            notl %d2
40077a7c  c480            andl %d0,%d2                 | d2 = (uVar16 | local_50) & ~uVar2
40077a7e  4280            clrl %d0                     | v = 0                        (as stock)
40077a80  41f9 4395 ddf4  lea 0x4395ddf4,%a0           | priority[]                   (as stock)
40077a86  43e8 012c       lea %a0@(300),%a1            | held[] = priority[] + 0x12c
```

- **Register state at `0x40077a8a` is identical to stock:** `d0 = 0`, `a0 = priority[]`, `a1 = held[]`,
  `d2` = the mask, and `d3` and `d6` unchanged. `d6` is overwritten by the very next instruction. `d0` is dead as a
  temporary until its own `clrl`, and the condition codes are reset by the next instruction.
- **The 2 B** the longer mask needs come from reaching `held[]` as `priority[] + 300`
  (`0x4395df20 - 0x4395ddf4 = 0x12c`), where stock loads it with an absolute `lea`. The owner-latch scan uses the
  same trick.
- **Branches.** `0x40077a72` is the target of `beqs` at `0x400779f4`, the only branch into the run, and it stays an
  instruction start. Three other boundaries, `0x40077a74`, `0x40077a7a` and `0x40077a7c`, happen to coincide with
  stock ones (objdump of the stock and the patched run); the rest move.
- **Build data.** In [../../build/patch.json](../../build/patch.json) (feature `tick_wipe_fix`) the rewrite appears
  as two runs, `0x40077a72` (16 B) and `0x40077a83` (7 B), because the byte at `0x40077a82` is the same before and
  after.

## Effect on the engine

A voice released and re-triggered in one tick now receives the trigger **without** the release. A voice steal
already delivers exactly that, and a steal was confirmed on the test unit to sound right. The LEN-countdown path is
unchanged, so sequenced trigs still release at their LEN.

## Design rule: MIDI semantics on MIDI tracks

MIDI tracks keep MIDI semantics, and the Loopback does too. Re-triggering a note that is already sounding sends a
note-off for the earlier instance first. That is stock behaviour and what external synths expect, because MIDI
cannot tell repeated note-offs for the same note number apart. Per-instance bookkeeping belongs only where the
stream is not MIDI.

On the input side, the stock note-on core `FUN_400c5a24` has the matching protection. On the auto channel, if the
note is already sounding (`DAT_4215d010[note] >= 0`), it first emits a note-off for it (decompile lines 23-25).

## Measured results

✅ Confirmed on the test unit. Procedure: play, let the notes re-trigger once through the Loopback,
press [STOP], no pattern switch.

| N notes | P voices | Drones, stock wipe | Drones, this build |
|---|---|---|---|
| 1 | 1 | 1 | 0 |
| 2 | 2 | 2 | 0 |
| 2 | 3 | 1 | 0 |
| 3 | 3 | 3 | 0 |
| 3 | 4 | 2 | 0 |
| 3 | 5 | 1 | 0 |

- ✅ **Phasing.** For N < P < 2N the stock wipe leaves doubled notes that are heard as phasing. These are gone in
  this build. This is heard while playing, before any [STOP], so it confirms the fix independently of the [STOP]
  path.
- ✅ **Six-voice pattern switch.**
  - Setup: 2 notes with LEN = INF, one retrigger, then a switch from a pattern with T3 + T4..T8 POLY to one with T3
    standalone and T4 + T5..T8 POLY, then one [STOP].
  - Result: both notes stop immediately. With the stock wipe only CC 120 stopped them.
  - ⚠️ Why six voices did not protect this case is not traced. Candidate only: the second pattern makes T3 a pool of
    one, so the retrigger lands where 2N − P > 0.
- ✅ **Regressions clean:**
  - steal then release ([voice_allocation.md](voice_allocation.md));
  - the pattern-switch and live-follower cases of [owner_latch.md](owner_latch.md);
  - live notes on tracks outside any pool;
  - mute by origin;
  - sequenced trigs at their LEN.

## What is still open

- **Zero-length note.** A note-on followed by its **own** note-off in one tick still leaves a voice triggered but
  booked free. The fix does not make this worse, and it has never been observed.
- **The simulator's limits.** [../../analysis/tick_wipe_sim.py](../../analysis/tick_wipe_sim.py) models the stock
  wipe and reproduces the drone counts.
  - It does not reproduce which note droned on the test unit (that changed with the retrigger count), nor the exact
    two-note phase patterns heard. It orphans the chord's last note with a period-3 layout cycle.
  - Candidates: how a burst splits across ticks, what the engine plays on a voice released and re-triggered in one
    tick, and where the cursor sits after [STOP].
  - The symptoms are gone in this build; only the model is incomplete.

## Ruled out

- ⛔ The MIDI side failing to send some note-offs. A MIDI monitor log shows every note-on answered by a note-off,
  [STOP]'s included.
- ⛔ The ISR's single-slot MIDI message stash (`FUN_40003664` → `FUN_400dd3a8`, ISR decompile lines 107-126). It has
  a loss path of its own, but it is not on the MIDI-input note path.
- ⛔ The voice steal as the source of the extra drones. They come from same-tick re-use of a voice released earlier
  in the same tick, which also happens without any steal.

## Related

[voice_allocation.md](voice_allocation.md) · [owner_latch.md](owner_latch.md) · [midi_loopback.md](midi_loopback.md) ·
[POLY voice pool](../function_ledger.md#machine-assignment-the-pool-map-and-the-audio-isr)
([patch listing](../../docs/patch_listing.md#poly-engine)) · [../render_path.md](../render_path.md) ·
[../function_ledger.md](../function_ledger.md)
