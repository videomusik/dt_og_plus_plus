# SLICE round robin (RRBN / RRND)

## What this is

This build adds two values below NOTE to the SLICE machine's Slice Select parameter. With RRBN, each
note trig plays the next slice of the sample's grid. Each track keeps its own rotation (a POLY voice
pool shares one), and a sounding voice keeps its slice until its note ends. This note covers the
stock slice-selection code that the feature hooks, every byte run the feature changes, and the rules
that keep it free of clicks.

## What the player sees

The Slice Select range (the **SLICE** parameter on the SRC page) is RRND, RRBN, NOTE, 1–64. Turn the
knob down past NOTE to get RRBN, then RRND. ✅ confirmed on the test unit

| Value | Select | Behaviour |
|---|---|---|
| 1–64 | 1–64 | Stock. Plays the numbered slice. A value above the grid's slice count plays the last slice. |
| NOTE | 0 | Stock. The trig's NOTE picks the slice, and the sample plays at a fixed pitch. |
| RRBN | −1 | Each note trig on the track plays the next slice of the grid, wrapping after the last (grid 4: 1, 2, 3, 4, 1, …). ✅ confirmed on the test unit |
| RRND | −2 | RRND is work in progress: in this build it is identical to RRBN. |

- **Per track.** Two tracks playing the same sample keep separate rotations and do not disturb each
  other. ✅ confirmed on the test unit
- **On a POLY voice pool.** With SLICE on the pool's Source track, the whole pool follows one
  rotation: consecutive trigs anywhere in the pool play consecutive slices. ✅ confirmed on the test unit
- **The slice latch.** Each voice keeps the slice it started with until its note ends, however
  many trigs follow on other voices of the pool. ✅ confirmed on the test unit
- **Pitch.** As with numbered slices, the trig's NOTE sets the pitch. See [Pitch](#pitch).
- **Display.** The value reads RRBN or RRND, and the parameter cell shows a robin instead of the
  stock piano keyboard. Both values show the robin. ✅ confirmed on the test unit
- **No slice highlight.** SRC page 2 draws no slice highlight for RRBN or RRND. This is a stock code
  path; no patch touches it. ✅ confirmed on the test unit (see [Slice highlight](#slice-highlight))

## How stock firmware picks a slice

`FUN_40074af2` (204 B) is the SLICE machine's read-window function. It is one of four
per-machine window functions called by the audio render. [render_path.md](../render_path.md)
describes that pipeline. Its arguments are the track's engine block, the track's note (16.16), a
default end, and the **track index** (0–7). It keeps no state, and it runs on every audio tick for
every SLICE track, whether or not a trig arrived.

In words (⚠️ read from the decompile and objdump):

- It reads the track's sample slot byte at `0x8000ee20 + track*0x5e`. If the byte is ≥ 0x80 (no
  sample), it skips the table lookup.
- It reads Grid from block `+0xc`, clamped to 4. The slice count is `4 << grid` (4, 8, 16, 32, 64).
- It reads Select from block `+8` as a signed byte and clamps it with `max(Select, 0)`. The slice
  index is `min(Select, count) − 1`.
- If the index is negative (Select was 0, i.e. NOTE), NOTE mode applies: the index becomes
  `((note >> 16) − 12) & (count − 1)`.
- It reads Length from block `+0xa`, clamped to 0x3f.
- Slice points come from the table at `0x402bb3b0`, which holds 0x100 u32 entries per sample slot.
  The start is entry `(index << (4 − grid)) × 4`. The end is `start + ((Length + 1) << (4 − grid)) × 4`.
  If the end entry reaches 0x100, the caller's default end is used instead.
- If block byte `+2` is greater than 1 (reverse play), start and end are swapped.

Ghidra decompiler output (excerpt; variables renamed, clamps written as `min`), `FUN_40074af2`:

```c
grid  = min(*(char*)(state + 0xc), 4);        // Slice GRID, log2
count = 4 << grid;                             // 4 / 8 / 16 / 32 / 64 slices
sel   = min(SliceSelect, count);
idx   = sel - 1;                               // Select 1..N -> slice 0..N-1
if ((int)idx < 0)                              // Select == 0  =>  NOTE mode
    idx = ((note >> 16) - 0xc) & (count - 1);  // slice = (note - 12) mod count
len   = min(*(char*)(state + 10), 0x3f);       // Slice LENGTH
start = table[(sampleBase + (idx        << (4-grid))*4)]   // table @ 0x402bb3b0
end   = table[(sampleBase + (idx+len+1  << (4-grid))*4)]
```

The excerpt leaves out the `max(Select, 0)` clamp before the `min`, the sample-slot guard, the
default-end fallback and the reverse swap listed above.

It has exactly two call sites. The one at `0x40074f7a` in `FUN_40074e84` passes 0 (track 0). The one
at `0x400756d0`, inside `FUN_400754fe`'s per-track loop, passes `track + 1`. So the fourth argument
is the track, and a counter keyed on it is per track. ✅ confirmed on the test unit

The descriptor for Slice Select is record 136 of the parameter-descriptor table `0x4018ff88`, at
`0x40191b28`. It holds page 3, slot 0x15, CC 0x14 and id 0xcb. Its min is at `+8`, and its max at
`+0xc` is 0x4000 (64.0 in 8.8).

- The encoder step `ParameterSet::vfunc_8` (`0x4000f022`) computes old + delta and clamps the result
  to the parameter's [min, max].
- `FUN_40078bf4` reads min and max from the descriptor table (`0x4018ff90 + record*0x34`).

Moving the minimum is therefore the whole UI range change, with no encoder code touched. See
[parameters.md](../parameters.md).

## What the build changes

Every run of changed bytes, by purpose. The generated per-byte listing is in
[docs/patch_listing.md](../../docs/patch_listing.md).

| Where (load) | Raw (file offset) | Size | What |
|---|---|---|---|
| `0x40191b30` | `0x191730` | 4 B | Slice Select minimum `0x00000000` → `0xfffffe00` (−2.0 in 8.8). The maximum stays 0x4000. |
| `0x40074b38` | `0x74738` | 10 B | The hook in `FUN_40074af2`. It replaces the stock `max(Select, 0)` clamp (five instructions) with `tst.l %d4 ; bpl.s +6 ; jmp 0x400b23b0`. A negative Select jumps to the pad before the clamp can erase its sign. Zero and positive values fall through to the unchanged stock code, and on that path the clamp was a no-op. |
| `0x400b23b0..0x400b240a` | `0xb1fb0` | 90 B | The round-robin pad (below), inside the unused leaf `FUN_400b23b0` ([landing_pads.md](../landing_pads.md)). |
| `0x400b2410..0x400b2468` | `0xb2010` | 88 B | The value formatter, plus its strings `RRND\0` at `0x400b245e` and `RRBN\0` at `0x400b2463` (below). |
| `0x4013b916` | `0x13b516` | 4 B | The formatter binding: an immediate inside the builder `FUN_4013b6a2`, `0x4005f7b0` → `0x400b2410`. It is the special-case entry (`+0x0c`, at `0x4193e604`) of the formatter object `0x4193e5f8`, which is bound to Slice Select only. The stock formatter `entry_4005f7b0` stays in place, unreferenced. |
| `0x40065264`, `0x400652d6` | `0x64e64`, `0x64ed6` | 6 B + 6 B | The robin selector's two in-place edits in the cell drawer `entry_40065234` (below). |
| `0x400aff34..0x400aff50` | `0xafb34` | 28 B | The robin selector pad, in the free tail of the unused `FUN_400aff0e`. |
| `0x40213b50..0x40213bb0` | `0x213750` | 96 B | The robin artwork (a 68 B plane and a 28 B static `Bitmap`) in `.rodata` padding. See [visual_assets.md](../visual_assets.md). |

The pad reads the POLY pool map `groupSource[]` (`lea 0x439902f0,%a2 ; move.b %a2@(0,%d1:l),%d1` at
`0x400b23d2`). Those two instructions belong to the POLY voice pool
([ledger](../function_ledger.md#slice-round-robin-on-a-pool)). In the build data they sit in that
feature's byte runs ([patch listing](../../docs/patch_listing.md#poly-engine)), not in this one's, and so
do three bytes of the instructions after them: `0x400b23ea–0x400b23eb` (the opcode word of
`counter[S]++`) and `0x400b23ff` (the second byte of `subql #1,%d3`).

## The round-robin pad

The hook jumps here from `0x40074b3c` when Select < 0. The pad returns to `0x40074b62` with `%d2` set
to the 0-based slice index. From there the stock code turns the index into the read window exactly
as for a numbered slice. The pad never looks at the Select value itself: −1 and −2 reach the same
code.

```
400b23b0  222f 0028       movel  %sp@(40),%d1           | d1 = track: the host's 4th argument
400b23b4  2839 8000 1228  movel  0x80001228,%d4         | per-track trig mask
400b23ba  e2ac            lsrl   %d1,%d4
400b23bc  0284 0000 0001  andil  #1,%d4                 | d4 = this track's trig bit
400b23c2  45f9 4399 02a4  lea    0x439902a4,%a2         | prev[]
400b23c8  77b2 1800       mvzb   %a2@(0,%d1:l),%d3      | d3 = prev[track]
400b23cc  1584 1800       moveb  %d4,%a2@(0,%d1:l)      | prev[track] = bit (edge detect)
400b23d0  2641            moveal %d1,%a3                | a3 = track (kept for the latch)
400b23d2  45f9 4399 02f0  lea    0x439902f0,%a2         | groupSource[] (POLY voice pool)
400b23d8  1232 1800       moveb  %a2@(0,%d1:l),%d1      | d1 = S = groupSource[track]
400b23dc  45f9 4399 02b0  lea    0x439902b0,%a2         | counter[]; slice[] = counter + 32
400b23e2  4a83            tstl   %d3
400b23e4  6610            bnes   0x400b23f6             | bit was already set: no edge
400b23e6  4a84            tstl   %d4
400b23e8  670c            beqs   0x400b23f6             | no trig: no edge
400b23ea  52b2 1c00       addql  #1,%a2@(0,%d1:l:4)     | counter[S]++
400b23ee  2632 1c00       movel  %a2@(0,%d1:l:4),%d3
400b23f2  1583 b820       moveb  %d3,%a2@(20,%a3:l)     | slice[track] = counter[S] (the latch)
400b23f6  73b2 b820       mvzb   %a2@(20,%a3:l),%d1     | every tick: d1 = slice[track]
400b23fa  7604            moveq  #4,%d3
400b23fc  ebab            lsll   %d5,%d3                | d3 = count = 4 << grid
400b23fe  5383            subql  #1,%d3
400b2400  c283            andl   %d3,%d1                | slice[track] & (count - 1)
400b2402  2401            movel  %d1,%d2                | result
400b2404  4ef9 4007 4b62  jmp    0x40074b62             | back into FUN_40074af2
```

⚠️ objdump prints the indexed displacement in **hex**. The operand `%a2@(20,%a3:l)` is displacement
0x20 = 32 (extension word `b820`), which is `slice[]`. A displacement of decimal 20 would land inside
`counter[5]`.

**Register contract.** This was checked by reading all of `FUN_40074af2`
(`0x40074af2..0x40074bbe`).

- **Live across the pad; must not be written:**
  - `%d0`, the sample-slot base, used at `0x40074b7c` and `0x40074b84`;
  - `%d5`, the grid shift, used at `0x40074b66`;
  - `%a0` and `%a1`.
- **Free:**
  - `%d1`, `%d2`, `%d3` and `%d4`;
  - `%a2`;
  - `%a3`: the host first writes it at `0x40074b7e` and restores it with its own `moveml` at
    `0x40074bb4`.

The track index travels in `%a3` because `%d0`, the obvious choice, is live. The pad is entered by
`jmp`, so `%sp` is the host's own, and `%sp@(40)` is the host's fourth argument.

**Scratch RAM.** All of it lies above the `.bss` end at `0x439902a0`, so none of it is initialised
at boot ([memory_map.md](../memory_map.md)). A scan of the whole image for words pointing into
`0x43990200..0x43990400` finds only the stock `.bss`-end literal `0x439902a0` and this build's own
code. The table lists the addresses this feature uses (`slice[]` has no literal of its own); every
tenant of the range, including those of the POLY voice pool, the owner latch and MIDI Loopback, is in
[memory_map.md](../memory_map.md#ram-used-by-this-build).

| Address | Size | Contents |
|---|---|---|
| `0x439902a4` | 8 B | `prev[8]`: the last trig bit seen per track (edge detect) |
| `0x439902b0` | 32 B | `counter[8]` (u32), indexed by pool source `S` |
| `0x439902d0` | 8 B | `slice[8]`: the latched slice per voice. It sits at counter + 32, so one `lea` reaches both arrays. |
| `0x439902f0` | 8 B | `groupSource[8]`: each track's pool source, written by the POLY voice pool |

Storing only the low byte in `slice[]` is exact. The value is always used as `x & (count − 1)` with
count ≤ 64, so `(x & 0xff) & (count − 1) == x & (count − 1)`.

## Rules the pad obeys

**A sounding voice's slice changes only on that voice's own trig.** The window function runs on
every tick for every SLICE track. So the pad changes state only on the trig edge (the track's bit
in `0x80001228` set, and clear on the previous call). On every other tick each voice reads back its
own latched `slice[track]`. On the edge tick the result equals the fresh counter value, so a track
outside any pool behaves exactly as with a direct counter read.

**Counting is per pool source.** The counter index is `S = groupSource[track]`. For a track that is
not in a POLY pool, `groupSource[track] == track`, so it has its own counter. In a pool, every voice
counts on the Source's counter, so the pool walks one sequence, while the latch stays per voice.
See the ledger's [SLICE round robin on a pool](../function_ledger.md#slice-round-robin-on-a-pool) and
[pool map](../function_ledger.md#the-pool-map-build-and-refresh), and the
[patch listing](../../docs/patch_listing.md#poly-engine).

- ⛔ **Ruled out: a counter that advances inside the per-tick slice read.** It advances on every
  tick, so the slice moves under a playing voice and clicks.
- ⛔ **Ruled out: a counter keyed on the sample slot (the chain), or a single global counter.**
  Tracks that share it share one sequence: two tracks on one sample, or all tracks.
- ⛔ **Ruled out: reading a pool-shared counter on every tick without the latch.** A trig on another
  pool voice then moves the window of voices still sounding:
  - on a step from N to N+1, their end moves one slice later, which is heard as a delayed extra hit;
  - on the wrap, the window jumps behind the play position and the old voice restarts at the first
    slice on top of the new trig, which is heard as an accent once per cycle.

  The latch removes both. ✅ confirmed on the test unit
- ⛔ **Ruled out: "a slice is only a start point".** Stock SLICE stops at the slice end, with NOTE and
  with numbered slices. ✅ confirmed on the test unit (tested with a POLY track after the SLICE track, the only
  set-up in which the difference can be heard). A voice that runs past its slice end in this build
  is a bug, not stock behaviour.
- ⛔ **Ruled out: reusing an engine counter.** The engine keeps no per-trig counter for audio trigs.
  - The stride-4 per-track array `DAT_4395df20 + track*4` is the held note (−1 = free).
  - `DAT_4395df4c + track*0x14` advances only for flag-0x8000 (MIDI/chord) note starts.

**Start state.**

- ⚠️ `counter[]` and `slice[]` are uninitialised at boot, so the first slice after power-on is
  arbitrary. A voice that has never been trigged is not playing, so the garbage is never heard.
- ⚠️ Nothing else in the image writes these addresses, so the rotation is not reset when the
  sequencer stops. After STOP and PLAY it continues where it was. This is from the code, and it is
  consistent with the test unit, where the rotation's phase against the pattern moved after a stop
  and restart.
- ⚠️ Until the POLY voice pool first builds its map, `groupSource[]` is uninitialised like the rest
  of this RAM, so an RRBN track would count on an arbitrary word up to about 1 KB past `counter[]`
  (the index is a byte, 0–255). The map is built when a project loads, so normal use never reaches
  this state.

## Pitch

The pitch is set in the render's per-track pitch loop in `FUN_40074e84`, not in the window function.
For a SLICE track, the loop tests the Select byte at block `+8` (decompiled, variables renamed:
`if (Select==0) iVar6=0x3c0000` … `else iVar6=note[track]`):

- **Select == 0 (NOTE):** the pitch is fixed at `0x3c0000` (note 60 in 16.16), and the trig's note
  selects the slice instead.
- **Any other Select (1–64, RRBN, RRND):** the pitch is the track's note, `0x80001f28 + track*4`.

✅ Stock behaviour on the test unit: NOTE plays at one pitch, and numbered slices follow the trig's
NOTE. On the test unit, RRBN and a numbered slice on the same trigs played at the same pitch as each
other, both different from NOTE mode. ⚠️ So a chain that sounds right in NOTE mode plays
transposed under RRBN/RRND unless its trigs sit at note 60 (from the code).

## Display

### Value text

The stock Slice Select formatter `entry_4005f7b0` prints NOTE for 0 and the number otherwise. The
formatter is reached through a runtime formatter object and cannot see the grid. The build's
formatter at `0x400b2410` is a signed version:

```
400b2410  202f 0008       movel  %sp@(8),%d0            | value, 8.8
400b2414  e080            asrl   #8,%d0                 | signed integer
400b2416  222f 000c       movel  %sp@(12),%d1           | output buffer
400b241a  0c80 ffff fffe  cmpil  #-2,%d0
400b2420  6718            beqs   0x400b243a             | RRND
400b2422  0c80 ffff ffff  cmpil  #-1,%d0
400b2428  6718            beqs   0x400b2442             | RRBN
400b242a  4a80            tstl   %d0
400b242c  671c            beqs   0x400b244a             | NOTE
400b242e  2f40 000c       movel  %d0,%sp@(12)           | the number becomes the argument
400b2432  203c 401b 1a49  movel  #0x401b1a49,%d0        | stock "%d"
400b2438  6016            bras   0x400b2450
400b243a  203c 400b 245e  movel  #0x400b245e,%d0        | "RRND"
400b2440  600e            bras   0x400b2450
400b2442  203c 400b 2463  movel  #0x400b2463,%d0        | "RRBN"
400b2448  6006            bras   0x400b2450
400b244a  203c 401a 893c  movel  #0x401a893c,%d0        | stock "NOTE"
400b2450  2f40 0008       movel  %d0,%sp@(8)            | format
400b2454  2f41 0004       movel  %d1,%sp@(4)            | buffer
400b2458  4ef9 4000 0e82  jmp    0x40000e82             | stock sprintf, on the caller's frame
400b245e  "RRND\0"  400b2463  "RRBN\0"
```

- ✅ The formatter uses only `%d0` and `%d1` (read directly in the objdump listing above). Code
  injected here must preserve `%d2–%d7` and `%a2–%a6` (callee-saved). ⚠️ A clobbered `%d2` corrupts
  the parameter's name and view for every value ≥ 1, while value 0 still looks right.
- ⛔ **Ruled out: editing the stock `NOTE` string in place.** The string at `0x401a893c` (raw
  `0x1a853c`) is the only `NOTE\0` in the image, and four places reference it:
  - descriptor record 18, the Trig NOTE label (`0x40190360`);
  - `TrackNoteMenuView::vfunc_4`, the keyboard-mode overlay (`0x40095662`);
  - AMP HOLD's formatter `entry_400656ce` (`0x400656dc`);
  - Slice Select's formatter `entry_4005f7b0` (`0x4005f7c0`).

  The build leaves the string alone and adds its own.
- ⛔ **Ruled out: new strings in the image tail at or above `0x40214000`.** That range is covered by
  `.bss` and the SRAM-init images and is zeroed at boot, so a string placed there displays empty.
  ✅ seen on the test unit. See [memory_map.md](../memory_map.md).

### Cell icon

The Slice Select cell drawer is `entry_40065234` (186 B, runtime record 136). Stock clamps negative
values to 0. It then draws the NOTE bitmap `0x421b9c3c` for values ≤ 0, and the slice-ruler bitmap
`0x421b9b60` with the number for values ≥ 1. The build adds a third case: any value < 0 draws the
robin. The artwork and the `Bitmap` format are in [visual_assets.md](../visual_assets.md).

- **Edit 1, at `0x40065264`:** `movel %sp@(20),%d1 ; andl %d0,%d2` (6 B) becomes `jmp 0x400aff34`
  (6 B). It uses `jmp`, not `jsr`, so `%sp` is unchanged inside the pad.
- **Edit 2, at `0x400652d6`:** `movel #0x421b9c3c,%d0` (6 B) becomes `movel %a1,%d0 ; nop ; nop`.
- **The pad**, in the free tail of `FUN_400aff0e`:

```
400aff34  227c 421b 9c3c  moveal #0x421b9c3c,%a1        | default: the stock NOTE bitmap
400aff3a  4a80            tstl   %d0                    | d0 == 0  <=>  value < 0
400aff3c  6606            bnes   0x400aff44
400aff3e  227c 4021 3b94  moveal #0x40213b94,%a1        | the robin Bitmap
400aff44  222f 0014       movel  %sp@(20),%d1           | replay the displaced instructions,
400aff48  c480            andl   %d0,%d2                | including the stock clamp
400aff4a  4ef9 4006 526a  jmp    0x4006526a             | rejoin
```

The choice has to be made early and carried late. The stock code picks the bitmap at `0x400652d6`,
but by then `moveml` (at `0x400652ce`) has restored `%d2`, and the clamp at `0x40065268` has already
erased the sign. The pad reuses the sign that the stock code computed at `0x4006525c`: `%d0` is
`0xffffffff` for a value ≥ 0, and 0 for a value < 0. The pad carries the choice in `%a1`, which is
caller-saved, is not in the drawer's `moveml` set (`%d2–%d3/%a2–%a3`), and is untouched between the
two edits. The listing above is 28 B. ✅ confirmed on the test unit

### Slice highlight

In the SLICE branch of the widget feed `FUN_40039684`, `%a2` holds the highlighted slice index, where
−1 means none. Stock maps a negative Select to −1, so no highlight is drawn for RRBN or RRND, and no
patch is needed. The mechanism is read from the code; ✅ the missing highlight was seen on the test
unit.

## Compatibility with stock firmware

A project saved with RRBN or RRND loads and plays on stock 1.52A. The negative Select meets the stock
`max(Select, 0)` clamp in `FUN_40074af2` and plays as NOTE, with no crash. ✅ confirmed on the test
unit. The feature changes no stored format and no data version. It widens the range of one existing
value, which the engine reads as a signed byte. See [compatibility.md](../compatibility.md).

## Not tested

- RRBN with Slice Length set to span more than one slice.
- Parameter-locking Slice Select to RRBN or RRND.
- Where the rotation starts after a project load.
- ⚠️ Trigless (lock) trigs should not advance the counter, because the edge detect only sees a
  track's new-trig bit. This has not been tested on its own.

See [open_questions.md](../open_questions.md).

## Related notes

- [render_path.md](../render_path.md): the per-tick pipeline that calls the window function.
- [function_ledger.md](../function_ledger.md#the-pool-map-build-and-refresh) and
  [patch_listing.md](../../docs/patch_listing.md#poly-engine): `groupSource[]` and the pool map.
- [pool_cursors.md](pool_cursors.md): the SRC page 2 cursors, which show the latch at work.
- [visual_assets.md](../visual_assets.md): the robin's pixels and the `Bitmap` format.
- [landing_pads.md](../landing_pads.md): why `FUN_400b23b0` and `FUN_400aff0e` are safe to overwrite.
- [function_ledger.md](../function_ledger.md): every function named here.
