# Pool cursors: one playhead per sounding voice on SRC page 2

## What this is

On SRC page 2, the waveform view, stock firmware draws one playhead cursor: the selected track's own voice.
In this build, a track in a POLY voice pool shows one cursor for every voice of its pool that is sounding.
All the cursors are drawn alike, on both waveform widget variants. The patch changes only UI drawing: the
engine already publishes every voice's play position, whichever track is selected.

## What the user sees

- On SRC page 2 of a POLY Source **and** of each of its POLY tracks, every sounding voice of the pool draws
  its own 1-pixel vertical cursor at its own play position. The cursors look identical (no distinction
  between the selected track's voice and the others) and disappear as their voices finish.
  ✅ Confirmed on the test unit: four cursors on a ONESHOT waveform with three POLY tracks
  after it, and two on a SLICE Source with POLY tracks after it.
- A track with no POLY track after it has a pool of one and shows exactly one cursor, as in stock.
- Both widget variants are served: the marker widget (ONESHOT, REPITCH) and the slice-grid widget (WERP,
  SLICE). ✅ ONESHOT and SLICE were confirmed; ⚠️ REPITCH and WERP use the same two code paths but were not
  reported separately.
- Two voices at the same position briefly cancel each other out, because the cursors are XOR lines. This
  is an accepted cosmetic effect of identical cursors.
- There is still one slice highlight at most. With a numbered Select every voice of the pool plays the same
  slice, so one highlight is correct. With Select at NOTE or at the
  [SLICE round robin](slice_round_robin.md) values below NOTE, the stock clamp draws no highlight at all.

## The stock mechanism

### Per-voice playback status

The engine writes the playback state of all eight voices into on-chip SRAM, every audio tick, whether or not
anything reads it:

| address | meaning |
|---|---|
| `0x8000edc8 + voice*0x5e` | one 94 B status block per voice; exactly eight blocks, ending before `0x8000f0dc` |
| block + 0x00 | current play position, 8.8 fixed point |
| block + 0x10 | sample length, 8.8 (`>> 8` = whole samples) |
| block + 0x24 | playing flag (byte) |

| function | role |
|---|---|
| `FUN_40074e84` | the per-tick render pipeline's prologue ([render_path.md](../render_path.md)); writes the block (advances +0x00, sets +0x10 / +0x24) |
| `FUN_400754fe` | the pipeline's main loop over tracks 0–7; writes the block's upper fields (+0x5a onward) |
| `FUN_40074d40` (324 B) | initialiser: walks every block to `0x8000f0dc`, zeroes position and flag, sets length `0x120`. Single caller `FUN_40076b5a` (engine init), not a per-trig reset |
| `FUN_40075f58` (66 B) | **the cursor's data source**: `position / (length >> 8)` = the position as an 8.8 fraction of the sample, and **0 when the length or the playing flag is 0**, so a zero return doubles as "not playing". Preserves `d2`–`d6`. Stock caller: `SamplePageView::vfunc_11` |
| `FUN_40075fd8` (22 B) | the playing flag (block + 0x24) |
| `FUN_40075f42` (22 B) | the raw position (block + 0x00); caller `SamplerView::vfunc_4` |
| `FUN_40075f9a` (62 B) | the same fraction at 15-bit scale; caller the audio ISR `FUN_40077120`; purpose not known |

Because `FUN_40075f58` returns 0 for a silent voice, a missing cursor is a direct readout of the engine's
status block: that voice reads not-playing or zero-length.

### The UI tick and the redraw

- **`SamplePageView::vfunc_11` @`0x40039a0c` (272 B)** is the SRC page's tick. It checks that page id 5
  is shown (vfunc +0x6c) and resolves the **selected** track. It reads that voice's position
  (`FUN_40075f58`) and flag (`FUN_40075fd8`) and pushes them into both widgets (`FUN_400ae5de` for the
  marker widget at page + 0x1c4, `FUN_400ae8d8` for the grid widget at page + 0x1f8). It invalidates the
  page only if `FUN_400ae54c` reports the marker widget's dirty byte set.
- **Every 12th tick** (`_DAT_40609d98` counts 0 … 11) it toggles the slice-highlight blink
  (`FUN_400ae88c`) and tail-jumps to `FUN_400ba31a(page)`. That is an unconditional invalidate, outside the
  page-id test, so the SRC page repaints at least every 400 ms while it is shown.
- **`FUN_400ae5de` (72 B)** is the marker widget's cursor setter: `px = (width * pos) >> 8` → +0x14,
  visible flag → +0x18, and the dirty byte +0x31 is set only if either changed. `FUN_400ae8d8` does the same
  for the grid widget (+0x10, +0x14, dirty +0x2d). The stock cursor is therefore change-driven: while the
  selected voice plays, the page repaints on every pixel step of its cursor.
- **`FUN_400ba31a` (34 B)** is `View::invalidate`, not a paint. If the view's +0x17 byte is clear it sets
  the dirty byte +0x14 and walks to the parent (+0x2c). About 186 call sites use it. The paint runs later,
  once, however many invalidates came before it.
- **The tick rate is 30 Hz.** `ViewController::tick` @`0x400bbb30` calls vptr +0x2c (slot 11, `vfunc_11`)
  on every registered view. `Brain::Brain` registers it at 30 Hz (registration at `0x40009e3a`, invoker
  `0x40007b80`). The base rate comes from the DTIM3 interrupt: `DTRR3 = 275000` at bus/16, set up at
  `0x4005ef3a`, which is 30.000 Hz for a 132 MHz bus. ⚠️ The 132 MHz bus clock is inferred from
  UART-divisor use and from the scheduler tick landing on exactly 100 Hz; neither rate has been timed on the
  device. A free check: the slice highlight should blink at 400 ms per state.

### The two waveform widgets

`SamplePageView::vfunc_4` @`0x40039bec` draws the SRC page and picks one widget by machine type:

| machine | widget | draw function |
|---|---|---|
| 0 ONESHOT | marker | `FUN_400ae65e` (422 B) |
| 1 WERP | slice grid | `FUN_400ae958` (434 B) |
| 2 REPITCH | marker | `FUN_400ae65e` |
| 3 SLICE | slice grid | `FUN_400ae958` |
| 4 POLY | none in stock | in this build a POLY track reports its Source's machine, so its page draws the Source's widget ([ledger](../function_ledger.md#src-page-1-layout-and-the-machine-a-page-shows), [patch listing](../../docs/patch_listing.md#poly-ui)) |

Both draws paint the envelope first, then their markers or grid, and the cursor **last**, as a 1-pixel XOR
vertical line; then they clear their dirty byte. The two functions open with an identical 36 B frame (saving
`d2`–`d6`/`a2`–`a5`). The widget is in `a2` and the canvas is in `d3` (marker) or `d4` (grid). Every widget
field sits exactly 4 B higher in the marker layout:

| field | marker widget | grid widget |
|---|---|---|
| cursor x | +0x14 | +0x10 |
| cursor visible flag | +0x18 | +0x14 |
| x origin | +0x1c | +0x18 |
| y origin | +0x20 | +0x1c |
| width (px) | +0x24 | +0x20 |
| height | +0x28 | +0x24 |
| dirty byte | +0x31 | +0x2d |

Each draw ends with a 6 B clear of its dirty byte, at **`0x400ae7f4`** (marker) and **`0x400aeafa`**
(grid). Every path reaches it, including the no-waveform path, which rejoins before it. The clear is
followed immediately by the register restore and `rts`, so code called from there may clobber
`d2`–`d6`/`a2`–`a5`.

### The line primitive

`FUN_400b22e0(canvas, x, y1, y2, mode)` draws a vertical line. It takes five stack arguments; `mode > 0` is
OR, `mode < 0` is XOR (the cursors pass −1), and 0 draws nothing. It **clips**: it rejects `x < 0`,
`x ≥ canvas[4]` (width), `y2 < 0` and `y1 ≥ canvas[8]` (height), so a wrong x cannot corrupt memory. It
copies all five arguments into registers at entry and never writes them back, so a caller can build the
frame once and change only `x` between calls. It saves and restores `d2`–`d6`.

### The selected track

The tick resolves the selected track as `FUN_4012198c()` (no arguments; the project object) → + 0x30 (this
is `FUN_4001488e`, 12 B, safe to inline) → `FUN_4001ccc4(that)`, which takes one pushed stack argument and
returns the track.

## The patch

### The hooks

| site | size | replaces | with |
|---|---|---|---|
| `0x400ae7f4` | 6 B | the marker draw's dirty-byte clear | `jsr 0x40015060` (marker stub) |
| `0x400aeafa` | 6 B | the grid draw's dirty-byte clear | `jsr 0x4001506a` (grid stub) |

The 190 B of code at `0x40015060..0x4001511e` is a 10 B stub per variant plus one shared 170 B core.
Both stubs align the widget pointer so that one set of (marker) offsets serves both layouts:

```
40015060  204a            moveal %a2,%a0            | marker stub: a0 = widget
40015062  43ea 0031       lea   %a2@(49),%a1        | a1 = &marker dirty byte (+0x31)
40015066  2003            movel %d3,%d0             | canvas
40015068  600a            bras  0x40015074          | -> core
4001506a  41ea fffc       lea   %a2@(-4),%a0        | grid stub: a0 = widget - 4, marker offsets apply
4001506e  43ea fffd       lea   %a2@(-3),%a1        | a1 = &marker dirty byte = grid widget - 3
40015072  2004            movel %d4,%d0             | canvas; falls into the core at 0x40015074
```

The grid stub points `a1` at the **marker** widget's dirty byte: the marker widget is at page + 0x1c4 and
the grid widget at page + 0x1f8, so `0x1c4 + 0x31 = 0x1f8 − 3`. That byte is the one the tick tests.

### What the core does

1. It keeps the aligned widget in `a3`, the marker dirty-byte address in `a4`, the canvas in `d3`, and a
   "drew something" flag in `d4` (cleared).
2. It resolves the selected track with the tick's own chain: `jsr 0x4012198c`, add 0x30, push it,
   `jsr 0x4001ccc4`. If the track is above 7 (unsigned: a MIDI track, the master track or −1), it draws no
   extra cursors.
3. It reads `S = groupSource[selected]` from the pool map `groupSource[8]` at `0x439902f0`, which the
   [POLY voice pool](../function_ledger.md#the-pool-map-build-and-refresh) maintains
   ([patch listing](../../docs/patch_listing.md#poly-engine)).
4. It builds the line primitive's frame once: (canvas, x, y1 = y origin + 1, y2 = y origin + height − 2,
   mode = −1). This is the same vertical extent as the stock cursor.
5. For v = 0 … 7, it skips voices outside the pool (`groupSource[v] != S`) and the selected track's own
   voice (stock draws that one, and a second XOR line on the same pixel would erase it). It skips voices
   where `FUN_40075f58(v)` returns 0. For the rest it computes `x = x origin + ((width * pos) >> 8)` (the
   stock setter's arithmetic), patches the frame's `x` slot, calls `FUN_400b22e0` and sets the flag.
6. It drops the frame and performs the displaced instruction: it clears **this** widget's dirty byte
   (`a3` + 0x31, which is the grid's +0x2d through the aligned pointer). If anything was drawn, it then sets
   the **marker** widget's dirty byte to 1.

The loop and the tail:

```
400150ca  1032 3800       moveb %a2@(0,%d3:l),%d0   | groupSource[v]
400150ce  b400            cmpb  %d0,%d2             | in the selected track's pool?
400150d0  6600 002c       bnew  0x400150fe
400150d4  bbc3            cmpal %d3,%a5             | the selected track's own voice: stock draws it
400150d6  6700 0026       beqw  0x400150fe
400150da  2f03            movel %d3,%sp@-
400150dc  4eb9 40075f58   jsr   0x40075f58          | position as an 8.8 fraction, 0 if silent
400150e2  588f            addql #4,%sp
400150e4  4a80            tstl  %d0
400150e6  6700 0016       beqw  0x400150fe
400150ea  4c05 0800       mulsl %d5,%d0             | width * pos
400150ee  e080            asrl  #8,%d0
400150f0  d086            addl  %d6,%d0             | x = x origin + px
400150f2  2f40 0004       movel %d0,%sp@(4)         | patch the frame's x slot
400150f6  4eb9 400b22e0   jsr   0x400b22e0          | XOR v-line; clips, never writes its arguments
400150fc  7801            moveq #1,%d4              | drew something
400150fe  5283            addql #1,%d3              | next voice
40015100  7008            moveq #8,%d0
40015102  b083            cmpl  %d3,%d0
40015104  6600 ffc4       bnew  0x400150ca
40015108  4fef 0014       lea   %sp@(20),%sp        | drop the frame
4001510c  4200            clrb  %d0                 | the displaced clear:
4001510e  1740 0031       moveb %d0,%a3@(49)        |   this widget's dirty byte = 0  (49 = 0x31)
40015112  4a84            tstl  %d4
40015114  6700 0006       beqw  0x4001511c
40015118  7001            moveq #1,%d0
4001511a  1880            moveb %d0,%a4@            | marker dirty = 1: the next tick invalidates the page
4001511c  4e75            rts
```

Here `d2` holds S, `a5` the selected track, `d5` the width and `d6` the x origin. The whole listing is in
[docs/patch_listing.md](../../docs/patch_listing.md).

### Register and stack contract

- The draw function reloads `d2`–`d6`/`a2`–`a5` from its frame right after the hooked instruction, so the
  core may clobber them.
- `d7` and `a6` are never touched; `a0`/`a1`/`d0`/`d1` are scratch.
- The called functions preserve `d2`–`d7`/`a2`–`a6`, so `a3`, `a4`, `d3` and `d4` survive every call.
- The stack holds one 20 B frame, built once and dropped once; every call is balanced; the draw's saved
  register block above the return address is never written.
- `FUN_4001ccc4` is called normally, with one pushed argument.

The core was also stepped in the emulator for stack balance, the untouched frame, `d7`/`a6` preservation,
the set of voices drawn and their x, and both dirty bytes, for both variants (see
[emulator.md](../emulator.md)).

### Refresh

Extra cursors are drawn from live engine data on every paint of the page. Three things cause a paint:

- **Stock:** the selected voice's cursor moves a pixel (up to 30 Hz).
- **Stock:** the 400 ms unconditional invalidate.
- **This patch:** the core sets the marker dirty byte whenever it drew an extra cursor. The next tick's
  `FUN_400ae54c` test sees it and invalidates the page, and the repaint draws the cursors again. So while
  any pool sibling sounds, the page refreshes at the tick rate even if the selected track's own voice is
  silent, and the cycle stops by itself when the siblings stop.

The peak repaint rate is unchanged, since stock already repaints on every pixel step of its own cursor;
only the share of time at that rate grows.

## Patched bytes

| load address | file offset | size | new bytes | part |
|---|---|---|---|---|
| `0x400ae7f4` | `0x0ae3f4` | 6 B | `4eb9 40015060` | marker draw tail → marker stub |
| `0x400aeafa` | `0x0ae6fa` | 6 B | `4eb9 4001506a` | grid draw tail → grid stub |
| `0x40015060..0x4001511e` | `0x014c60` | 190 B | pad | marker stub (10) · grid stub (10) · core (170) |

The code occupies the head of the 396 B landing pad at `0x40015060..0x400151ec`, which it shares with parts
of [MIDI Loopback](midi_loopback.md) (see [landing_pads.md](../landing_pads.md)). It runs on the UI draw path:
every paint of SRC page 2, including the first paint after boot if that page is shown.

## ⛔ Ruled out and traps

- ⛔ Treating `FUN_400ba31a` as a paint. It only marks the view dirty and walks to its parent, so calling
  it extra times costs one repaint, not one per call.
- ⛔ Drawing the selected track's own voice again. Stock has already drawn it, and XOR would erase it.
- ⛔ Reading the View tick at vtable +0x34. The address in an RTTI listing is the vtable structure;
  an object's vptr is that address + 8, and Ghidra's `Class::vfunc_N` is vptr + 4N. The tick is vptr +
  0x2c (slot 11).
- ⛔ Calling `FUN_4001ccc4` through a wrapper that shifts its stack argument. Call it with one pushed
  argument.

## Open questions

- ⚠️ REPITCH and WERP pages have not been reported separately (see above).

See also [open_questions.md](../open_questions.md).
