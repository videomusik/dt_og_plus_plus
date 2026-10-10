# CFOO's wave pictures: WAV1–3

Not in the build: test images on top of it (the build of `os/1.54/build/patch.json`, v0.2.2, section 3
`3b88fa95…`). Source: [src/wave_pictures/](../../src/wave_pictures/).

## What it does

On a CFOO track's SRC page, the pictures of WAV1, WAV2 and WAV3 (knobs A, C and D: OSC1's, OSC2's and
OSC3's wave) show the wave the oscillator plays at the knob's value, in place of the plain knob the
CFO oscillator gave every knob ([cfo_oscillator.md](cfo_oscillator.md#cfoos-own-value-displays-in-s17)):

- 17 × 17 pixels, the size of a stock knob picture, as a line over one cycle;
- SIN at 0, TRI at 42, SAW at 85, SQR at 127, and between them the blend the oscillator plays, so the
  picture follows the knob as it turns;
- the value the picture is drawn for is the knob's, or a trig's lock, as for any picture.

The other five knobs, and every other machine, keep their pictures.

## How

✅ Read in the code (objdump, decompile):

### The picture call

- The parameter cell has the parameter set draw a value's picture: `ParameterSet::vfunc_23(set, id,
  value, flag, …, canvas, x, y)` (`0x4000f2bc`) takes the id's display object and jumps to its picture
  callable's invoker with `(storage, value, canvas, x, y, flag)`.
- The CFO oscillator hooks `vfunc_23`'s first 8 B (`cfo_pic`): for ids 108–115 on a set whose sound
  plays machine 5 it swaps in STRT's id (112) and the value rescaled onto 0–120.0, so the stock code
  draws STRT's plain knob.
- That knob's invoker (`0x400600a4`) takes frame 12 + round(value × 72 / 120.0) of the strip
  `0x421f94c4` and draws it with the stock bitmap drawer `0x400c2b88(canvas, frame, x, y, 0)`; it
  ignores the flag. On the screen a knob picture is 17 × 17 (a capture of FILTER page 2: SRR's knob and
  the empty knob's square).

### The bitmap drawer

`0x400c2b88(canvas, bitmap, x, y, centred)`: a `Bitmap` is `+0x00` the vtable `0x401b7734`, `+4` the
width, `+8` the height, `+12` words a column, `+16` the plane, `+20` the mask. With `centred`, x and y
are less half the size; the bitmap is clipped to the canvas, and in every word the canvas gets
`(canvas & ~mask) | (plane & mask)`. It reads no vtable. The MACHINE menu's icons go through it as well
(`0x40029f5a`). In a column word of an h-row bitmap, row r from the top is bit 31 − (h − 1 − r): the rule
of CFOO's and POLY's icons ([cfo_oscillator.md](cfo_oscillator.md#cfoos-picker-icon-in-s14)), confirmed
on the unit by the POLY icon and by these pictures (S55). ⚠️ Reading the drawer's shifts alone suggests
the opposite order.

### The hook

The CFO oscillator's `cfo_pic` (in the build at `0x400f7c3e`), once it has found a CFOO knob, takes the
knob's record at `0x400f7c5c`: `movel %sp@(8),%d0 ; subil #108,%d0` (10 B of `cfo_oscillator`'s code).
**S54** replaces them with `jmp wav_sel` and two `nop`s; `wav_sel` replays the two instructions and
goes on at `0x400f7c66`. No instruction of the build branches into the replaced bytes (`make_wavpic.py`
checks the build's whole code window).

**S55:** for knobs A, C and D (`%d0` 0, 2, 3) `wav_sel` goes to `wav_pic` instead, which draws the
picture and returns from `vfunc_23` itself: `cfo_pic` is reached by a jump from `vfunc_23`'s entry and
has left the stack as it was, so the arguments are at `%sp@(4)` (the set) … `%sp@(32)` (y).

### The picture

`wav_pic` builds a `Bitmap` on its stack frame (196 B: the saved registers, the object, the plane, the
mask) and calls the drawer with `(canvas, bitmap, x, y, 0)`, as the plain knob does:

- the value's whole part, at most 127, as the synth takes it;
- the CFO oscillator's own `segfrac` (`0x400f7a1e`): the segment (SIN–TRI, TRI–SAW, SAW–SQR) and the
  fraction towards the next table, 0..256; the tables are the CFO oscillator's (`0x40252724`, four of
  256 signed bytes, `make_waves.py`);
- 17 columns: column c samples index 16c mod 256 (column 16 is index 0 again and closes the cycle), the
  sample a + ((b − a) × fraction >> 8), as the oscillator's loop computes it;
- the row ((127 − sample) × 4112 + 0x8000) >> 16: (127 − sample) × 16 / 255 rounded, 0 at the top for
  127, 16 at the bottom for −128, 8 for a sample of 0;
- each column inked from the row before it (column 0: its own) to its own row, so a jump is a vertical
  line; the mask has every row of every column, so the picture replaces the 17 × 17 square.

It uses `%d0`, `%d1`, `%a0`, `%a1`, as an invoker may, and keeps the others. 266 B in all.

### Code space

The new pad `FUN_401044b6` (2,504 B), a function of xxHash's 64-bit family, which the firmware links
whole with LZ4 and never calls: vetted on this image with the recipe's library exception
([landing_pads.md](../landing_pads.md#the-wave-pictures-pad-fun_401044b6)). S55 uses 266 B; 2,238 B
remain, and the family around it has more.

## Checks

- **`make_wavpic.py`:** the build comes from `patch.json` and must give its hash; the pad is stock and
  untouched by the build, after an `rts` and ending in its stock `rts`; the hook site holds the build's
  `cfo_pic` bytes, owned by `cfo_oscillator`; the continuation, `segfrac` and the four tables
  (against `make_waves.py`) are where `wavpic.s` takes them; no instruction of the build branches into
  the hook site; the code fits its pad. Each stage image passes `build.py`'s checks.
- **`EmuWavePic`** ([scripts/emu/README.md](../../scripts/emu/README.md)) runs `vfunc_23` from its entry
  through `cfo_pic` and the hook, the machine query and the drawer stubbed: machines 5 and 0, ids
  106–117, five values each: S54 sends every call to the stock code as `cfo_pic` does (STRT's id for
  CFOO's knobs); S55 draws A, C and D on CFOO (one drawer call, the caller's return, the stack and
  `%d2`–`%d7`, `%a2`–`%a6` kept) and sends every other call on. For A, C and D at every value 0–255 (768
  pictures): the drawer's arguments (the canvas, x, y, 0), the `Bitmap` (17 × 17, a word a column, the
  mask words all `0xffff8000`) and every plane word against a model written from the description,
  with the tables read from the image; the model's rows held against (127 − sample) × 16 / 255 within
  1/2 for every sample. Six controls, one change each, fail their own cases: the row rounded down, the
  mask a row too high, B drawn in place of C, half a cycle, a 16 × 16 bitmap, S54's inert hook against
  the pictures' model.

## Stage images

Each stage adds one thing to the one before; flash them in order on a unit that runs the build (v0.2.2).

| Stage | `.syx` | Section 3 | Contents |
|---|---|---|---|
| S53 | `925e1784` | `50efd1d1…` | the build + `FUN_401044b6` filled with `clrl %d0 ; rts`: its fill test |
| S54 | `66457fc1` | `315a52d1…` | S53 + the CFO oscillator's picture hook through the new pad, which only replays what it replaced |
| S55 | `31b428ea` | `957bdc6f…` | S54 + WAV1, WAV2 and WAV3 draw the wave they play |

**What to check on the unit (OS 1.54):**

- **S53:** everything works as on the build for a while of ordinary use, above all what reads or
  writes files: loading and saving projects, loading samples and browsing +Drive, sampling, transfers
  over USB. A difference of any kind means the pad is live.
- **S54:** nothing changes: WAV1–3 still show the plain knob, every other knob as before.
- **S55:** on a CFOO track's SRC page, WAV1, WAV2 and WAV3 show SIN at 0, TRI at 42, SAW at 85 and SQR
  at 127, and the blends between as the knob turns; a locked trig shows the locked wave; the other
  knobs and machines as before.

## On the unit

✅ S53, S54 and S55 on the test unit with OS 1.54, as reported: S53 works, S54 changes nothing, and in
S55 the waves look right.

## Open points

- Not in `patch.json` yet.
- The rest of the pad (2,238 B) is free for later features, knob B's envelope destination on FILTER
  page 2 among them.
