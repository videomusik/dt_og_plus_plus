# FILTER page 2: VED and KEY

Not in the build: test images on top of it (the build of `os/1.54/build/patch.json`, section 3
`d6fac1a3…`). Source: [src/filter_page2/](../../src/filter_page2/). This note describes the last stage,
S52, and what each stage before it changed.

## What it does

Every audio track's second FILTER page gets two knobs, C and G, which are empty in stock:

| Knob | Name | Range | What it does |
|---|---|---|---|
| C | VED, Vel to Env Depth | 0–100 %, default 0 % | how much the velocity decides the filter envelope's depth, around velocity 100 |
| G | KEY, Keytracking | −394 % to 394 % in steps of 6.25 %, default 0 % | moves the cutoff with the note's distance from C4 (note 60) |

Knob B stays empty, kept for an envelope destination of its own.

- **VED.** The envelope's depth (ENV's) is multiplied by 1 − VED / 100 × (100 − velocity) / 127. At
  0 % every note gets ENV's depth, exactly as stock. At velocity 100, the trigs' default, every VED
  leaves the depth as it is; a softer note gets less of it and a harder one more. At 100 %: velocity
  127 gives 1.21 × the depth, 64 gives 0.72 ×, 0 gives 0.21 ×; at 50 % half as far from 1. The depth
  cannot go beyond what ENV ±64 gives on its own, so with ENV beyond about ±52 the boost above velocity
  100 stops there. It works the same on a negative ENV.
- **KEY.** Each step is 6.25 % keytracking: at +16 (100 %) the cutoff follows the note, at +32 (200 %)
  it moves two octaves for an octave played, at +63 (394 %) about four, the other way for negative
  values. 100 % is 0.875 FREQ steps per semitone. The stock cutoff follows about 5 Hz × 2^(0.0947 × FREQ)
  (333 Hz at FREQ 64, 13.1 kHz at 120, 20.8 kHz at 127, from the filter's coefficient table), 1.136
  semitones per FREQ step, so exact tracking would be 0.880: 100 % is 99.4 % of that fit. The note is
  the one the track plays: portamento's glided note sum, so the cutoff glides with the pitch. Not the
  trig's TUNE. The cutoff stops at FREQ's range. At 0, or at C4, the cutoff is stock's.
- **Display.** VED shows as Trig Probability does: its percentage in the picture's box, and its name
  under the picture also while the knob turns. KEY shows ENV's picture (a knob with a centre mark) and
  its percentage, rounded half up (12.5 shows 13 %, −12.5 −12 %).
- **[FUNC] + knob** steps VED to the next of 0, 50 and 100 % and KEY to the next of −394 %, 0 and
  394 %, in the turn's direction, as GAIN and Stereo Width step.
- Both are sound parameters, like ENV: in the sound's free value slots 48 and 49, read from the
  engine's smoothed copy as FREQ and ENV are, lockable per trig, saved with the sound.

## How

### VED and KEY as sound parameters

✅ Read in the code (objdump, decompile, the listing scans named):

- **Rows 1 and 2** of the 164 parameter descriptors (`0x401aa09c + 0x34 × id`) read `Error`, page −1,
  with the CC numbers 1/33 and 2/34 and the external numbers 1 and 2. The CC-table build `FUN_40078b20`
  skips page −1 rows ([portamento.md](portamento.md#port-and-leg-as-sound-parameters)), so no CC reaches
  them. They become VED and KEY: page 6 (the filter's, as ENV and the page's other rows), slots 48 and
  49, the `+0x14` word 1 as ENV's (it is passed to the random-value callable, which ids 1 and 2 do not
  have), no CC and no NRPN (`0xffffffff`), flags 0 (no LFO destination), the group string `Filter`
  (`0x401c699d`, ENV's), the names `VED` / `Vel to Env Depth` and `KEY` / `Keytracking` in the `.rodata`
  padding at `0x40252ed9`. The external numbers stay. KEY's range is 1.0..127.0 (`0x0100..0x7f00`),
  default 64.0, shown −63..63 steps. VED's was the same until S46; from S47 it is 0..100.0
  (`0x0000..0x6400`), default 0, as Trig Probability's (id 29).
- **KEY's range starts at 1** so that a stored 0 never means −64: a sound whose slots 48 and 49 were
  never written holds 0 there, and the hook takes a KEY of 0, as 64, as no effect. A VED of 0 is 0 %.
- **Slots 48–52** are unused in stock: no row names them (the row scan of portamento's research), the
  stored sound holds slots 0–45 only, and no absolute operand names them in the per-track copy
  `0x80001502 + 106t` or the engine's smoothed copy `0x80002772 + 106t` (`work/port/slot_uses2.py`). In the
  audio code (the ISR, both render stages, the filter, both envelopes, the drive stage, SRR, the mix,
  the smoothing and the sound apply), every displacement that would address slots 48–52 in one of the
  three layouts was read by hand: they are the engine block's slots 41–43 (`FUN_40072478`'s drive field,
  the mix's sends) or other structures (`work/port/slot_audio.py`).
- **The smoothing** `FUN_40074b9e` runs a one-pole filter over the whole engine block, 486 words, every
  slot of every track (objdump), so slots 48 and 49 reach the engine block smoothed, as ENV does.
- **Display objects** (`0x4197e2f8 + 0x54 × id`, built once at start-up by `FUN_40152280`): ids 1 and 2
  copy the whole-step knob template (`%d2` = `0x4018e18c` there, VEL's), the numeric text
  `0x4197d7ec` and a plain picture `0x4197d58c`, and no random or [FUNC] callable. The text operands
  (`0x40153354`, `0x40153394`) become ENV's text `0x4197d7cc`, whose invoker `0x40065842` (installed at
  `0x40152356`) subtracts 64.0 and formats the rest through `0x4197d7ec`; the picture operands
  (`0x40153362`, `0x401533a2`) ENV's picture `0x4197d4dc`. VED's change in S47
  ([VED's display](#veds-display-s47-s49)), KEY's text in S51 and S52 ([KEY's text](#keys-text-s51-s52)).
- **FILTER page 2's layout record** `0x4197e090 + 44` = `0x4197e0bc` (DEL, –, –, SRR, BASE, WDTH, –,
  ROUT, level): its start-up stores include `clr.l` for the three empty knobs B, C and G (`0x401568c4`,
  `0x401568ca`, `0x401568e4`). As for the TRIG page ([portamento.md](portamento.md)), the record is in
  zeroed `.bss` and written once, so `clr.l` of C and G becomes `addq.l #1` and `addq.l #2` (same size):
  C = id 1, G = id 2. B's `clr.l` stays.
- **FILTER page 2 draws its own knobs** (S40). `FilterPageView::vfunc_4` (`0x400379ca`) draws FILTER page
  1 with `0x4003774a`, page 2 with `FUN_40037564` and any other page with the parameter pages' common
  loop `ParameterPageView::vfunc_4` (`0x40031802`). `FUN_40037564`'s loop over the eight knobs draws an
  empty knob as a dotted square, and calls the page's cell drawer (`MachineParameterPageView::vfunc_37`,
  vtable `+0x94`) for the others with a last argument that says "no picture" (the cell then draws the
  name alone). It takes that argument from a five-entry table for the ids 39..43: DEL, SRR and ROUT get a
  picture, BASE and WDTH none (the page draws the two as one box after the loop, `0x4003768c`); any
  other id gets none. In stock no other id appears on the page, so VED and KEY showed their names and
  no picture (S39 on the unit). S40 changes five words of the routine: the table's bound `moveq #4` →
  `#5` with `bcc` → `bhi` (the same range, and %d1 odd at the table's targets), `moveq #1` → `#0` for any
  other id (a picture), and BASE's and WDTH's branches past that `moveq` (still none). The routine is
  reached only from `FilterPageView::vfunc_4`, for page 2; the `moveq` it changes is the target of those
  two branches only (listing scan).

### [FUNC] + knob (S45, S46)

✅ Read in the code (objdump):

- `ParameterSet::vfunc_11` (`0x40010026`), the knob edit, asks the knob's display object for a new value
  when [FUNC] is held and the object's `+0x44` callable is set (`+0x4c` not 0), with `(storage, value,
  delta, min, max, default)`, min and max the descriptor's. GAIN (31) and WID (72) copy theirs from the
  shared object `0x4197d1dc`, whose invoker `0x4005f830` gives the next of min, middle and max in the
  delta's direction, the middle min + (max − min + 1) / 2 in whole steps: −63, 0 and 63 for 1..127, 0,
  50 and 100 % for VED's 0..100.
- The start-up build gives ids 1 and 2 none. **S45** replaces a `clrl 0x4197e3ec` (id 2's `+0x4c`, in id
  3's block, `0x401533c0`, 6 B) with a call of `filt_spc`, which only replays the `clrl` (the first code
  through the new site). **S46:** `filt_spc` copies `0x4197d1dc`'s callable into ids 1 and 2 (`+0x44`)
  through the build's own copier `0x40151f6c`, which `%a4` holds there: it is loaded once at
  `0x401522b2` and not written again before the site (`make_filt.py` checks this in the image). The
  copier clears the destination's manager first, which the replaced `clrl` did for id 2.

### VED's display (S47, S49)

✅ Read in the code (objdump; the listing scans named):

- No stock knob picture fits 0..100: the plain knob's invoker `0x4005ffd4` draws frame (value × 72 +
  0x3f80) / 0x7f00 of a 73-frame strip whatever the parameter's range, so 100 % would stop at 79 % of
  the arc. Trig Probability (id 29, the only stock row of 0..100) has its own text and picture: the
  text `0x4197d59c`, invoker `0x4005fd14`, prints `%d%%` (`0x401c690e`) of the value's whole part, and
  the picture `0x4197d3cc`, invoker `0x40066544`, prints the same into the picture's box in the font
  `0x402002b8`. **S47** gives VED both (the operands at `0x40153354` and `0x40153362`).
- The cell then showed the percentage twice while the knob turned: the cell draws the value's text in
  place of the name when the knob is touched (`0x40030d20..0x40030d34`), unless the display object's
  flags (`+0x00`, `FUN_4000fece`) have bit 2 set and the page's type is not 5 (`0x40030ce2..0x40030d06`),
  as FLT.T's, LFO.T's and ROUT's are; then it draws the name (`0x40030da6`, `FUN_4000fe8a`). The flags
  are read only there (bit 2, and bit 1 at `0x40030c6e` for a different picture when untouched) and by
  five tests of bit 0 through the getter `0x40065794`; `0x4000fee0`, a mask test, has no reference.
  **S49:** the build's `clrl 0x4197e34c` (id 1's flags, `0x4015334a`) becomes `addql #4` (same size; the
  `.bss` is zero before the one run).

### KEY's text (S51, S52)

✅ Read in the code (objdump):

- No stock text prints a scaled, signed percentage, so KEY gets its own invoker, `key_txt`. A text
  callable is 16 B: storage (8 B), the manager (`+8`) and the invoker (`+12`); the build copies it from a
  shared object with the copier, which reads the source's manager and invoker and calls the manager to
  clone. ENV's text manager `0x40060d88` clones by allocating a fresh byte (`0x400d43a8(1)`) and reads
  nothing of the source's storage.
- **S51:** id 2's text operand (`0x40153394`) points at a constant object at `0x40252fec` in the `.rodata`
  padding: its storage is the slot → stored index table's two entries already there (S43), then
  ENV's manager and invoker at `0x40252ff4`. KEY's text is ENV's, through the new path.
- **S52:** that invoker becomes `key_txt`: (value × 25 − 0x4000 × 25 + 0x200) / 4 is the percentage ×
  256 plus a half, and Trig Probability's invoker `0x4005fd14` prints its whole part rounded down, so
  the percentage rounded half up.

### The hook in the filter stage

✅ Read in the code (objdump of `FUN_40072844`):

The filter stage `FUN_40072844(engine block + 0x44, samples, trig bit, track)` starts by forming the
cutoff: `%sp@(68)` = FREQ << 16 (`0x4007285c..0x40072864`), `%d5` = (ENV − 0x4000) << 17 (the depth,
`0x40072892..0x400728a0`), then it pushes the track and calls the level getter `FUN_40073412`
(`0x400728a2`, 6 B), which returns the filter envelope's level (`0x4199ef58 + 12 × track`) in `%d0`.
After the call: `msac.l %d0,%d5` (level × depth, EMAC fractional), the product kept per track at
`0x4399ead4 + 4 × track`, FREQ << 16 + product, `sats.l`, clamped to `0..0x7f000000`.

The hook replaces that `jsr` with a `jsr filt_hook`:

- Seen from the hook, the caller's FREQ << 16 is at `%sp@(76)` and its engine-block pointer at
  `%sp@(112)` (slot s at `+ 2s − 0x32`; FREQ, 26, at `+2`, as the stock code reads it).
- **VED** (slot 48) changes `%d5`, which is what a different ENV would do: the stock code after reads
  `%d5` only in the `msac.l` before it is written again (`0x400729aa` reads it after `0x40072954` writes
  it), and any read would see the value an ENV of that depth would give. The product the stock code
  keeps per track is therefore the velocity-adjusted one.
  - S41 to S46: if not 0 or 64.0, `%d5` gains ((VED − 0x4000) × velocity) << 2, `sats.l`.
  - From S47: `%d5` less `%d5` × (pivot − velocity) × VED % / 12700, with no divide: (velocity − pivot)
    << 8 times VED is (velocity − pivot) × VED % << 16; its high word times 21136 = 2^32 / (100 × 127 ×
    16) has the share in steps of 1/4096 in its high word (rounded up); that times the depth's high word
    (the depth's low 17 bits are 0), << 4, is the part of the depth to take off. Three 16 × 16
    multiplies. The pivot is 127 in S47 (the depth only shrinks); from S48 100, and `sats.l` after the
    sum, since a velocity above 100 makes the share negative. At VED 0, or the pivot's velocity, the
    depth is exactly stock's.
- **KEY** (slot 49): if not 0 or 64.0, the caller's FREQ << 16 gains ((note sum − 0x3c0000) >> 8) ×
  (KEY − 0x4000) × 7 >> 1, `sats.l` (S41 to S43); × 7 from S44; from S50 × 7 doubled with `add.l` and
  `sats.l` before the sum. The note sum is portamento's `CUR[track]` (`0x439d1180`), which its
  rate-loop hook writes for every track every tick before the filter runs
  ([portamento.md](portamento.md#the-glide-in-the-audio-isr)).
- Then the hook jumps to `FUN_40073412`, whose `rts` returns to the filter stage. It uses `%d0`, `%d1`,
  `%a0`, `%a1` (the getter's own scratch registers) and `%d5` as above.
- From S50 the KEY part's first two instructions (`mvs.w` of KEY, `tst.l`) sit at the end of the VED
  part, before its `jmp`; the KEY part starts with the `beq` on their flags (a `jmp` leaves the
  condition codes). A `nop` after the KEY part keeps `filt_spc` where the start-up build's call names it.
- `mvs.w` sets N and Z on the device (ColdFire manual), but Ghidra's emulator leaves them, so each
  `mvs.w` before a branch is followed by a `tst.l`.

**Velocity.** ✅ `0x80001f18 + 2 × track` is written at a note-on with the event's flag `0x80`: the
event's word `+20`, or, with the event's flag bit 15, that word plus an offset (`+60` × a factor, EMAC),
clamped to `0..0x7f00` (`0x40077a12..0x40077a5c`); the amp level takes it as its velocity input
(`FUN_40074c60`). ✅ That it is the trig's velocity: VED acts on it on the test unit with OS 1.54, as
reported (S41 on).

### Saving VED and KEY (S42, S43, S47)

✅ Read in the code (objdump, decompile):

- **The stored sound** (portamento's note, [portamento.md](portamento.md#saving-port-and-leg-s33)) has
  no room for more values in its value area. The stock writer `FUN_4007a5a0` writes `+0x7c..+0x7e` (the
  machine and two bytes), the sample reference at `+0x84` and a name at `+0x94`; the reader reads
  `+0x7c..+0x7e`, `+0x84` and `+0x94`. `+0x7f..+0x83` are read by neither and written by neither: the
  writer does not clear them either, so a record written by the stock firmware holds whatever its buffer
  held there.
- **The mirror.** `Sound::vfunc_17` keeps a stored copy of the sound up to date one value at a time: it
  writes the slot's 16-bit word at `+0x1c + 2 × FUN_40079772(0, slot)`. So whatever is stored must be a
  whole word at an index: `+0x80` and `+0x82` are the indices 50 and 51. VED and KEY go there; the byte
  `+0x7f` stays unused.
- **S42** moves the save path into this build's pads without changing what it does: portamento's reader
  hook (the `jsr` at `0x4007a2aa`) is re-pointed at `rd_hook2`, which loads PORT and LEG exactly as
  `rd_hook` does; and the two stored-index lookups' test for 46 and 47 (`moveq #47,%d1 ; cmpl %d0,%d1 ;
  bccs`, 6 B at `0x4007974c` and `0x40079786`) becomes a jump to `fwd_ext` / `inv_ext`, which do that test.
  The jump's targets and the reader's call are re-pointed at each stage's own entries.
- **S43:** the writer's loop takes 50 words (`moveq #96` → `#100` at `0x4007a614`); portamento's slot →
  stored index table (`0x40252f2c`) gets entries 48 and 49 = 50 and 51 (`0x40252fec`), so the sound
  writer and both p-lock writers store slot 48 at `+0x80` and 49 at `+0x82`; the lookups map, for a
  kind below 16, the indices 50 and 51 to the slots 48 and 49 and back; the reader loads slots 48 and 49
  from `+0x80` and `+0x82`, keeping a whole 1..128 (128, which no knob sets, slips through the test) and
  taking anything else, a stock record's 0 among it, as 64.
- **S47:** the reader keeps a VED up to `0x6400` (100 %, a fraction too) and takes anything above as 0;
  a KEY a whole 1..127, anything else (0 among it) as 64.
- **Compatibility.** A sound saved by the stock firmware gives VED 0 % and KEY 0 (64), unless its
  leftover bytes at `+0x80..+0x83` happen to hold values the reader keeps. A sound saved with S43 to S46
  holds VED in the bipolar form, which S47 on reads as a percentage (that VED's 0, stored as 64, comes
  back as 64 %). A project saved with these images and opened on the stock firmware: the stock reader
  ignores those words, and its lookup gives a lock with index 50 or 51 slot 0, which no parameter uses
  (as for PORT and LEG).

### Code space

Four new pads, vetted on this image
([landing_pads.md](../landing_pads.md#the-filter-page-2-pads)), and two leftovers of Chain Recording's:

| Pad | Size | Holds (S52) |
|---|---:|---|
| `FUN_400d266e` | 60 B | the hook's VED part and the KEY part's first two instructions: full |
| `FUN_40178f20` | 86 B | the hook's KEY part (58 B and a `nop`) and `filt_spc` (26 B): full |
| `FUN_40178e02` | 66 B | `rd_hook2`: full |
| `FUN_401778a4` | 52 B | `fwd_ext` and `inv_ext`, 44 B |
| `0x40124b24` (Chain Recording's) | 14 B | `key_txt`'s first part, 12 B, with a `bra.w` to the second |
| `0x401282ce` (Chain Recording's) | 18 B | `key_txt`'s second part: full |

The two leftovers hold the build's pad fill (`make_filt.py` checks it), which ran on the unit with
Chain Recording. `.rodata` padding: the names (37 B) at `0x40252ed9`, from S43 the table's two entries
(8 B) at `0x40252fec`, from S51 KEY's text object's manager and invoker (8 B) at `0x40252ff4`. Left: 2 B
at `0x40252efe`, 4 B at `0x40252ffc`, 8 B at `0x401778d0`. Knob B's destination will need a new pad.

## Checks

- **`make_filt.py`:** the build comes from `patch.json` and must give its hash; every range this
  feature writes is stock and untouched by the build, but for the three sites it takes over from
  portamento (the reader's call, the two lookups' tests), the writer's loop count and table, each
  holding the build's expected bytes, and the two leftovers, which must hold the build's pad fill; each
  section lies in its pad, its leftover or the zero `.rodata` padding; no stock instruction branches
  into a replaced site; `%a4` holds the copier at the start-up site; each later stage keeps the entries
  the sites name. Each stage image passes `build.py`'s checks. The compressor window of S43 is unchanged
  (largest back-reference `0xffc6a`).
- **The display objects on a whole start-up.** An emulation of the whole display builder
  `FUN_40152280` (a bump allocator in place of `0x400d43a8`) on S39 left ids 1 and 2 with ENV's text and
  picture callables, and `ParameterSet::vfunc_23` drew the same frame for them as for ENV; that ruled
  out the objects as the cause of S39's missing pictures. `EmuFilter` now runs it on every stage.
- **`EmuFilter`** ([scripts/emu/README.md](../../scripts/emu/README.md)) runs the hook at its site with the
  caller's frame, for tracks 0, 3 and 7, against a model written from the description, with `%d0`, the
  stack, the frame and every register but the getter's own checked:
  - VED, S41 to S46: 525 runs and the scale check (VED +63 at velocity 127: +62.5 ENV steps). From S47:
    1,344 runs (VED 0, 0.5, 1, 25.5, 50, 64, 99.5 and 100 %; velocity 0, 1, 63, 64, 100, 126, 127; depths
    at both ends and between) against the model to the bit, and against the exact law within 1/3000 of
    the depth (worst 0.00026); the scale points: S47 the whole depth at VED 0 or velocity 127, none at
    100 % and velocity 0, 0.504 at 100 % and velocity 64; from S48 the whole depth at velocity 100,
    1.212 at 100 % and velocity 127, 0.212 at velocity 0, 0.716 at 64, and the depth's top at ENV +63.
  - KEY: 720 runs (KEY 0, 64, 1, 127 and between; notes 0..127, a glided note sum; FREQ at both ends
    and between); KEY +63 an octave up: 10.34 FREQ steps (S41–S43), 20.67 (S44–S49), 41.34 (from S50).
  - The inert hook (S38, S39, S40) leaves the depth and FREQ as they were.
  - With the data (S39 on): the rows and names; the stock CC-table build on the stock rows and on the
    stage's, equal but for the slot map's 48 → 1 and 49 → 2; FILTER page 2's layout stores; the display
    build's sources for ids 1 and 2; FILTER page 2's draw routine from its per-knob decision to the cell
    drawer for DEL, BASE, WDTH, SRR, ROUT, VED and KEY (S39: no picture for VED and KEY, as on the unit;
    from S40 a picture for both, none for BASE and WDTH); the whole start-up build: ids 1 and 2's flags,
    text, picture and [FUNC] callables, the [FUNC] callable run on values and directions (from S46), and
    KEY's text on −63..63 (from S51: ENV's call in S51, the percentage from S52).
  - With the save path: both lookups against the stock code for 567 inputs each; a sound through the
    stock writer and reader (slots 0..47 in S42, 0..49 from S43); the reader on PORT and LEG's and on
    VED and KEY's malformed words; p-locks through the stock lock writer and reader (VED and KEY as
    indices 50 and 51 from S43).
  - 30 controls, one change each, fail their own cases.
- ⚠️ **The emulator's `sats.l`** gives `0x7fffffff` for any overflow (a probe in the harness shows V set
  on both); the ColdFire manual, and the device, give `0x80000000` for a sum below −2^31. Where a run
  overflows downward (27 VED runs in S41–S46, 84 from S48, 30 KEY runs from S50), the harness expects the
  emulator's result, and the device saturates them to the bottom instead.

## Stage images

Each stage adds one thing to the one before; flash them in order on a unit that runs the build (v0.2.1).

| Stage | `.syx` | Section 3 | Contents |
|---|---|---|---|
| S37 | `cead9a67` | `41102d66…` | the build + `FUN_400d266e`, `FUN_40178f20`, `FUN_40178e02` and `FUN_401778a4` filled with `clrl %d0 ; rts`: their fill test |
| S38 | `bc5acdb6` | `5c1f5521…` | S37 + the filter stage's hook, only jumping on to the level getter |
| S39 | `44838bcf` | `ade4dcf5…` | S38 + VED and KEY on FILTER page 2 (rows, names, display objects, layout); nothing sounds different yet |
| S40 | `27db5b8d` | `9cb92ff6…` | S39 + VED's and KEY's pictures on FILTER page 2 |
| S41 | `8af77b53` | `dd0ff3e3…` | S40 + VED and KEY act |
| S42 | `99f27c2c` | `c781709c…` | S41 + the save path moved into this build's pads, PORT and LEG only, as before |
| S43 | `a2c59d9c` | `0edfbbb4…` | S42 + VED and KEY saved with the sound, and their p-locks with the pattern |
| S44 | `4024a163` | `91559a22…` | S43 + KEY's effect doubled |
| S45 | `5a8e46fc` | `76bf2217…` | S44 + a call in the start-up display build, only replaying the `clrl` it replaced |
| S46 | `e73898ae` | `300a933b…` | S45 + [FUNC] + knob steps VED and KEY to the next of −63, 0 and 63 |
| S47 | `bbb857c3` | `79ac1867…` | S46 + VED 0..100 %, the share of the depth the velocity decides, shown as Trig Probability |
| S48 | `c803a557` | `3c5b6562…` | S47 + VED's pivot at velocity 100 |
| S49 | `523afd98` | `9da82fad…` | S48 + VED's name stays under its picture when turned |
| S50 | `7d8eb8d1` | `3be1cf03…` | S49 + KEY's effect doubled again: 6.25 % a step |
| S51 | `6bd55222` | `c81c42dc…` | S50 + KEY's text through a constant copy of ENV's text object: still −63..63 |
| S52 | `701a6c28` | `3b88fa95…` | S51 + KEY's text in percent |

**What to check on the unit (OS 1.54):**

- **S37:** everything works as on the build, for a while of ordinary use: patterns on every machine,
  POLY and CFOO tracks, PORT and LEG, the sampler, MIDI, saving and loading. A difference of any kind
  means one of the four pads is live.
- **S38:** as S37; the filter sounds exactly as before, ENV and FREQ included.
- **S39:** an audio track's FILTER page 2 shows DEL, an empty B, VED on C, SRR, BASE, WDTH, KEY on G and
  ROUT. VED and KEY read 0 on a track whose sound was loaded or made, turn −63..63 in whole steps, show
  as ENV shows, reset to 0, and lock on a trig. Nothing sounds different. MIDI CC 1 and CC 2 still do
  nothing. The LFO destination list has no VED or KEY.
- **S40:** VED and KEY have pictures, as ENV's (a knob with a centre mark at 0); DEL, SRR and ROUT as
  before; BASE and WDTH still one box. Nothing else changes.
- **S41:** a sound with a filter envelope and a low cutoff, notes of different velocities: with ENV at 0,
  VED +63 gives soft notes almost no envelope and hard notes a deep one; with ENV at +63, VED −63 gives
  soft notes the whole envelope and hard notes almost none. KEY +63: higher notes brighter, the cutoff
  following the pitch; KEY −63 the other way; at C4 nothing changes. With PORT on, the cutoff glides
  with the pitch. VED and KEY at 0 sound as S40.
- **S42:** as S41; PORT and LEG and their locks still come back with a project.
- **S43:** set VED and KEY on a few tracks and lock both on some trigs; save the project, load another
  and load it again (and power off and on): the values and locks come back. PORT and LEG and their
  locks too. Projects saved before S43 open with VED and KEY at 0.
- **S44:** KEY +63: about two semitones of cutoff per semitone of note; C4 unchanged.
- **S45:** nothing changes.
- **S46:** [FUNC] + C or G steps to the next of −63, 0 and 63 in the direction turned.
- **S47:** VED shows 0..100 % in whole steps, 0 % on a new sound; [FUNC] + C steps 0, 50, 100 %. With
  ENV +63 and a low cutoff: at 100 % soft notes get almost no sweep and hard ones the whole; at 50 %
  soft ones half; at 0 % as S46.
- **S48:** VED 100 %, ENV about +32: velocity-100 trigs sound as at VED 0 %; harder ones sweep more,
  softer ones less.
- **S49:** VED's name stays under its picture while turning; the picture shows the percentage.
- **S50:** KEY +16 tracks about 1:1, +63 about 4 ×; the display still reads −63..63.
- **S51:** nothing changes; KEY still shows −63..63.
- **S52:** KEY shows −394 % to 394 %, 100 % at the former +16.

## On the unit

✅ S37, S38 and S39 on the test unit with OS 1.54, as reported: all worked; in S39 VED and KEY are on
FILTER page 2 and their ranges work, but they show their names without a picture (a screen dump of the
page: DEL's picture, B's dotted square, VED's name alone, SRR's knob, the BASE/WDTH box, KEY's name
alone, ROUT's text). Cause and change: [FILTER page 2 draws its own knobs](#ved-and-key-as-sound-parameters)
above, S40.

✅ S40 to S43 on the test unit with OS 1.54, as reported: they work. ✅ The later stages, up to S52, on
the test unit with OS 1.54, as reported: they work.

## Open points

- ⚠️ A sound whose KEY slot holds 0 (never written: a sound made by a path that does not set the
  descriptors' defaults) shows −400 % while it acts as 0; the first turn takes it into range. Which
  paths make a sound without the defaults is not traced; a reloaded sound gets 64 from the reader. A
  VED of 0 is 0 %, as it acts.
- Randomising does nothing for VED and KEY: ids 1 and 2 have no random callable.
- VED and KEY are not LFO destinations (flags 0).
- Knob B is reserved for an envelope destination of its own: picking a destination as the LFO does, and
  modulating it with the filter envelope after depth and velocity (the product the stock filter stage
  keeps per track, from the tick before).
- Code space: the four pads are full but for 8 B, and KEY's text took two of Chain Recording's
  leftovers; knob B's destination will need a new pad.
