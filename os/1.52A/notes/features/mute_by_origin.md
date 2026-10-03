# Mute by origin: mute follows the track a note came from

## What this is

Stock Digitakt mute is a gate on new voice triggers in the audio ISR. It is tested against the voice a note event
is about to play on. In a POLY voice pool that voice is often not the track that produced the note, so this build
re-reads the originating track from the event at both mute tests. Muting a Source now silences all of its trigs,
whichever voice they land on. On a pattern without POLY tracks, the mute tests drop the same trigs as in stock.

Conventions: MAIN OS load addresses. Decompile line numbers refer to the project's Ghidra decompile and are a
locator only. Event fields and per-voice arrays are tabled in [voice_allocation.md](voice_allocation.md).

## How stock mute works

### Mute state

- **The mask.** Mute state is a per-track 16-bit mask at offset `+0x1c` of the active PatternSettings object.
  `FUN_4001d068(patternSettings, track)` resolves the active PatternSettings through an indirect call and returns
  that track's bit, `return *(int*)(obj + 0x1c) >> (track & 0x3f) & 1;` (decompiled). It returns 0 for a track
  above `0xf` or when there is no object (`if (obj==0 || track>0xf) return 0;`).
- **Descriptor records.** The page-less system parameters are Solo `0x8`, Mute `0x9` and Pattern Mute `0x10`, at
  table `0x4018ff88`, records #7, #8 and #9.
- **Toggles.** Mute is toggled by `QuickMuteMenuView`, the [FUNC] + [BANK] mute mode. Its key-event handler
  `vfunc_2` at `0x40036074` calls the setter `FUN_4001fb7e`, which calls `FUN_4001fa34`. It is also toggled by
  `KitVoiceMuteConfigMenuView`.
- **Change events.** A change fires observer events of the RTTI classes `PatternSettingsTrackMutedInfo`,
  `ProjectSettingsTrackMuteChangedInfo` and `ProjectSettingsTrackSoloChangedInfo`.
- **Strings.** The UI strings include `"%s mutes"` (`0x401a600b`), `"MUTE"` and `"MUTE DEST"`.

### The trigger gate in the audio ISR

The mute test in `FUN_40077120` sits inside the branch for `event[3] == 1` (decompile lines 220-239). The `bnew` at
`0x400774c6` skips the whole block for any other priority, so **only priority-1 events** are mute-checked. Those are
the sequencer trigs from `FUN_4006f1be`. Two masks are tested, and in stock both are indexed by the voice in `%d2`:

| Mask | Where it comes from | Stock test |
|---|---|---|
| live mute mask | a local at `%fp@(-80)` (`local_54`) | loaded at `0x400774ca`, shifted by `%d2` (`asrl`) at `0x400774ce` |
| pattern/kit mute mask | a 16-bit global at `0x4196032c` (`_DAT_4196032c`), loaded from the set-active-kit event (decompile line 163) | loaded (`mvzw`) at `0x400774d6`, tested (`btst %d2,%d0`) at `0x400774dc` |

If either bit is set, the note is dropped and never triggers a voice.

Voices that were already triggered keep rendering, so **tails ring out**. There is no audio-stream cut and no
gain-zeroing of a playing voice, and the sequencer keeps running.

### Applying a mute to voices already sounding

`FUN_4001d4a2(patternSettings)` walks all 16 tracks and calls `FUN_40078248(track, 1)` for each muted one.

`FUN_40078248(track, reason)` is a general voice-control primitive, not specific to mute:

- `reason` 1 = mute, `0x40` = all-track reset. `track = 0xffffffff` means all 16 tracks, and
  `FUN_40078248(0xffffffff, 0x40)` is called from `FUN_4004d5c2`, `FUN_4004b960` and `FUN_400c6788` (pattern and
  kit-change resets).
- It writes 1 into two per-track countdown timers in shared SRAM, `0x8000196c[t]` and `0x8000198c[t]`.
- It writes only while the track's entry in `DAT_4395ddf4` (the same array the ISR uses as voice priority) is at
  most 1 for a mute, and at most 2 for any other reason. The bound is computed at `0x40078256..0x40078262`:
  `sne %d1` gives −1 when the reason is not 1, `mvsb %d1,%d1` sign-extends it, and `subl %d1,%d2` subtracts it
  from 1, so the bound is 1 − (−1) = 2 (objdump).

Condensed from Ghidra decompiler output (excerpt), `FUN_40078248`, the two timer writes:

```c
*(u32*)(&DAT_800014f0 + (track+0x11f)*4) = 1;   // countdown timer 0x8000196c[t]: expire on the next ISR tick
*(u32*)(&DAT_800014f0 + (track+0x127)*4) = 1;   // countdown timer 0x8000198c[t]
```

The audio ISR decrements both timers by elapsed time every tick and expires them when they drop below 1,
`t = t - elapsed; if (t<1) → expire` (decompile lines 78 and 94). Writing 1 therefore means "expire on the next
tick": the note stops now, without gain-zeroing, and its release tail still rings.

### Where this happens

- **Section 2 is not involved.** It is the boot-time audio-hardware layer (SSI codec and eDMA), and it does not
  reference the mute timers.
- **Only MAIN OS reads them**, in the voice-manager / voice-upload cluster `0x40076xxx-0x40078xxx`. `FUN_4007699e`
  uploads voice parameters, and its callers `FUN_40076dfc` and `FUN_40076fe6` are on the voice-trigger path.
- **The renderer is MAIN OS code** too ([../render_path.md](../render_path.md), [../section2_map.md](../section2_map.md)).

Mute is therefore entirely a MAIN OS gate on starting voices.

### Live and MIDI notes bypass mute

Priority-2 events never enter the mute block. These are live notes, external MIDI and Loopback notes, all from
`FUN_4007683c`, plus the events `FUN_400dd3a8` emits. An audio track's mute therefore does not silence notes played
live or routed to it from a MIDI track. This build keeps that behaviour on purpose.

## What this build changes

Both stock tests index `%d2`, and the POLY remap pad overwrites that register with the remapped voice
([ledger](../function_ledger.md#audio-isr-event-dispatch-and-the-note-gate),
[patch listing](../../docs/patch_listing.md#poly-engine)). Left alone, a Source's mute would catch only the trigs that happen to
land on the Source's own voice. This build instead reads the originating track from `event[2]` (`%a2@(8)`), which
the remap never touches.

Two 12 B detours sit in the STL-span landing pad, directly after the voice allocator
([../landing_pads.md](../landing_pads.md)):

```
4015cbd0  222e ffb0       movel %fp@(-80),%d1          | mute_live: the live mute mask
4015cbd4  202a 0008       movel %a2@(8),%d0            | d0 = originating track
4015cbd8  e0a1            asrl %d0,%d1                 | was: asrl %d2,%d1
4015cbda  4e75            rts
4015cbdc  202a 0008       movel %a2@(8),%d0            | mute_kit: d0 = originating track
4015cbe0  0139 4196 032d  btst %d0,0x4196032d          | kit mask, low byte -> Z
4015cbe6  4e75            rts                          | CCR is the return value
```

| Address | Size | New bytes | Replaces |
|---|---|---|---|
| `0x4015cbd0` | 24 B | `222effb0202a0008e0a14e75202a000801394196032d4e75` | unused landing-pad bytes |
| `0x400774ca` | 6 B | `4eb94015cbd0` (`jsr mute_live`) | the live-mask load and its `asrl` |
| `0x400774d6` | 8 B | `4e714eb94015cbdc` (`nop ; jsr mute_kit`) | the kit-mask `mvzw` and its `btst` |

⚠️ Not changed by this build: the stock stop path `FUN_40078248` indexes `DAT_4395ddf4` and the two countdowns by its
track argument, while the ISR indexes the same arrays by voice. In a pool the two differ, so when `FUN_4001d4a2`
applies a Source's mute it can stop only the Source's own voice, not the follower voices sounding the Source's trigs,
and for a muted POLY track it stops a trig holding that track's voice, whichever track the trig came from. Inferred
from the code (objdump of `0x40078248..0x400782c0`, and of the ISR's countdown stores at `0x40077772` and
`0x400775be`, indexed by the voice in `%d2`); when `FUN_4001d4a2` runs is not traced.

### Details that make it correct

- **CCR is the return value of `mute_kit`.** The caller's next instruction is `beqs` at `0x400774de`, so `btst` must
  be the last flag-setting instruction. `rts` does not change the condition codes. The `nop` goes **before** the
  `jsr` so that the `rts` lands directly on the `beqs`. The CCR of `mute_live` does not matter, because the caller
  recomputes it with `andl` before branching.
- **The byte-address form.** `btst Dn,<mem>` tests bit (Dn mod 8) of one byte. The kit mask is a big-endian 16-bit
  word at `0x4196032c`, so bits 0-7 are in the byte at `0x4196032d`.
  - Only tracks 0-7 can reach this code. The block is gated on `event[3] == 1`, and the only producer of priority-1
    note events is `FUN_4006f1be`, which the sequencer engine `FUN_4007011c` calls only for tracks below 8
    (decompile line 295).
  - `FUN_4006f546` also writes `[3] = 1`, but on a type-3 event that never reaches the note branch.
- **No spare register, so two detours.** Each detour loads the track into `%d0` locally, and nothing is carried
  between them.
  - `%d3`-`%d7` are OR-accumulators the ISR builds across the whole tick: `orl %d0,%dN` at `0x4007777a`,
    `0x400775ac`, `0x40077596`, `0x4007793a` and `0x40077940`.
  - `%d1` carries the first mute result forward to `tstl %d1` at `0x400774f8`.
  - `%d0` is dead at both sites. `moveq #1,%d0` at `0x400774d0` overwrites it two bytes after the first splice,
    and the stock `mvzw` at `0x400774d6` overwrote it immediately before the second test.
  - ⚠️ When checking liveness, classify instructions by opcode semantics, not operand position. `or`, `add` and
    `and` read their destination, and `tst` reads its only operand. A scan that treats the last operand as a plain
    write reports all five accumulators as dead.
- **Splice safety.** A branch-target scan of the whole section found no branch into either replaced run, either at
  its entry or inside it. `bnes` at `0x4007747e` branches to `0x400774a2`, the pool-remap splice at the head of
  the note handler, so that address is a branch target. That is harmless here, because no value is carried across
  it into the mute tests.
- **Identity outside pools, by construction.** On a pattern with no POLY tracks the remap is identity, so the
  originating track and the voice are the same number and the mute tests are bit-identical to stock. The voice
  allocator runs before them on every note-on ([voice_allocation.md](voice_allocation.md)).
- ⛔ **Mute is not a check on the pool map.** Both tests read the originating track from `event[2]`, never the
  remapped voice, so a wrong pool map does not change which trigs mute drops.

## Status

- ✅ Confirmed on the test unit. Muting the Source silences all its trigs, wherever their voices
  landed, and unmuting brings them back.
- ✅ Also confirmed together with the other voice features of this build.
- Live and MIDI notes into a muted audio track still sound, as intended.

## Interaction with voice allocation

The mute block runs **after** the voice allocator. On a saturated pool, a trig from a muted track can therefore
steal a voice and then be dropped here. That case is unverified; see "Steal, then drop" in
[voice_allocation.md](voice_allocation.md).

## Related

[POLY voice pool](../function_ledger.md#machine-assignment-the-pool-map-and-the-audio-isr)
([patch listing](../../docs/patch_listing.md#poly-engine)) · [voice_allocation.md](voice_allocation.md) ·
[../parameters.md](../parameters.md) · [../render_path.md](../render_path.md) ·
[../function_ledger.md](../function_ledger.md)
