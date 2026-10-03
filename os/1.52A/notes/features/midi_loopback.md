# Virtual MIDI Loopback: MIDI track to audio track without a cable

## What this is

A MIDI track's CHAN parameter gains eight values below channel 1, TRK1–TRK8. A MIDI track set to TRKn
transmits on audio track n's own listening channel, and every note it sends is also fed back into the
Digitakt's own MIDI input on a private internal lane, so audio track n (and its POLY voice pool, if it has
one) plays the notes with no cable. Notes arriving on that lane are not recorded as trigs. This note covers
what the user sees, every patched site, the stock code each part relies on, and what is still unverified.

## What the user sees

- On a MIDI track's SRC page, **CHAN** turns below 1: 1 → TRK8 → TRK7 … → TRK1, stopping at TRK1. Turning
  up retraces TRK1 … TRK8 → 1 … 16. Turning the encoder in the positive direction always increases the
  track number.
- While CHAN is on a TRK value the cell label reads **TRK** (otherwise CHAN), the number in the dial reads
  1–8, and the encoder popup reads "Track=N" (otherwise "Channel=N"). ✅ label, popup and dial number
  confirmed on the test unit.
- The route sits behind the stock per-track enable of CHAN: while CHAN is disabled the parameter shows OFF
  and the track neither sends nor routes. CHAN is enabled and disabled with [FUNC] + press DATA ENTRY knob
  A, the stock gesture for every parameter on this page (Digitakt User Manual, OS 1.50 edition, section
  12.3 SRC PAGE).
- A MIDI track set to TRKn plays audio track n, chords (up to four notes per trig) and note-offs included.
  If track n is a POLY Source, the notes spread over its pool. ✅ confirmed on the test unit, no cable connected.
- The destination decides whether it listens: if audio track n's channel in SETTINGS > MIDI CONFIG >
  CHANNELS is OFF, nothing plays and no note is left hanging. ✅ confirmed on the test unit.
- Internal and external MIDI can play one audio track at the same time. ✅ confirmed on the test unit: sustained notes sent
  from a computer over USB and into the DIN input, together with notes played on a routed MIDI track.
- With live recording on, notes that reach an audio track through the route are **not** written as trigs.
  External MIDI over USB and the Digitakt's own pads still record on the destination track. ✅ confirmed on the test unit.
  ⏳ DIN MIDI recording and grid recording have not been tested with this build.
- Routed notes also leave the Digitakt through MIDI OUT / USB on the destination's channel (see
  [Caveats](#caveats)).

## How it works

```
MIDI track trig (sequencer or live)
  -> MIDI-out task FUN_400d0578
       |- channel lookup FUN_400cfd6c    [channel hook @0x400cfd94: TRKn -> audio track n's listening channel]
       |- the stock emit block runs on that channel: note-on now, note-off scheduled
       '- note byte appender FUN_400d0396 [loopback tap @0x400d0396: copy 0x8n/0x9n to the private lane]
            '- (the bytes also leave by DIN / USB MIDI OUT, as in stock)
  private lane = USB-MIDI cable-1 input queue 0x4216a074
  -> MIDI input task FUN_400c465e       [lane arm, spliced @0x400c47ce: dispatch with origin tag 0x20]
       -> note handler -> channel fan-out -> audio track voice (FUN_400c53f2 -> FUN_4007683c)
       -> recorder feed FUN_400c4bda    [record filter: drop a recorder event whose origin is 0x20]
```

The feature keeps **no state of its own**. The destination lives on the sender (the MIDI track's CHAN) and
the channel lives on the receiver (the audio track's listening channel), exactly as with a physical MIDI
OUT → IN cable: the output gate is the MIDI track's destination, the input gate is the audio track's
listening channel. Because the channel hook substitutes a real channel, the whole stock output block runs,
so the stock note-off scheduling serves routed notes with no extra code.

## CHAN: how the TRK values are stored

| stored integer part (value >> 8) | shown as | meaning |
|---|---|---|
| 0 … 15 | 1 … 16 | stock MIDI channel (0-based) |
| −1 | TRK8 | audio track 8 (index 7) |
| −2 … −7 | TRK7 … TRK2 | audio tracks 7 … 2 |
| −8 | TRK1 | audio track 1 (index 0) |

Rule: stored `s` in −8 … −1 → audio track `s + 9` (1-based), index `s + 8` (0-based).

| item | address / fact |
|---|---|
| descriptor | record **140** at `0x40191BF8` in the 164 × `0x34` descriptor table at `0x4018ff88`: page 12, slot 17, parameter id `0x78`, max `0x0f00` (15.0). Its min at +0x08 (`0x40191C00`) is the field this build changes, 0.0 → −8.0 |
| live value | u16 8.8 at `0x42189422 + m*0x6a` (m = MIDI track 0 … 7). The high byte is the integer part and is read sign-extended |
| kit copy | `kit + 0x59a + m*0x70 + 0x22`. The per-track MIDI setup is `0x70` B: 53 × u16, padding, then a u32 enable mask at +0x6c |
| enable mask | u32 per MIDI track at `0x421897dc + m*4`; bit = slot − 17, so bit 0 is CHAN ("this track sends"). Toggled by `MidiParameterPageView::vfunc_0` @`0x4002fec4` → `FUN_4000e9d2` (the XOR at `0x4000eb8e`) |
| clamp | the only clamp on the encoder path is `ParameterSet::vfunc_8` @`0x4000f022`, which limits `old + delta` to the descriptor min/max fetched with `FUN_40078bf4`. Widening the min is all the range change needs (see [parameters.md](../parameters.md)) |
| persistence | no patch needed: the kit deserializer `FUN_4007aaba` clamps only track levels, and `FUN_40079de4` / `FUN_40079e44` store and load a negative 8.8 value unchanged |
| parameter locks | the MIDI-out task `FUN_400d0578` saves slot 17 before it applies locks and writes it back afterwards, so a lock cannot move CHAN at runtime |

⛔ Ruled out: splicing the shared encoder setter (`MachineParameterPageView::vfunc_22` → ParameterSet vtable
+0x2c) to reach negative values — the descriptor min is the only clamp, and that setter is shared by many
page classes.

## The display

### Label, popup and value text

A value reaches the screen by three routes: (1) the encoder popup, through the parameter's value-formatter
`std::function` (called by `FUN_400655aa`); (2) the cell's text line, through `ParameterSet::vfunc_22`
@`0x4000ee3c` (reached from `MachineParameterPageView::vfunc_37` as vfunc +0x58), which uses the same
formatter; (3) the picture in the cell, drawn by the parameter's own cell-graphic drawer, which prints its
own number (next section). The names come from two descriptor accessors: `FUN_4000f9a2` reads the short
name (+0x30) and `FUN_4000f9c4` the long name (+0x28), each `0x4018ff88 + index*0x34`.

| site | size | replaces | with |
|---|---|---|---|
| `0x40191C00` | 4 B | CHAN's descriptor min, 0.0 | `fffff800` (−8.0 in 8.8) |
| `0x40065652` | 6 B | the first instruction of CHAN's value formatter (it loads the +1.0 addend) | `jsr 0x4001511e` (`addend`) |
| `0x40030466` | 6 B | `jsr FUN_4000f9a2`, the short name for the grid cell | `jsr 0x40015134` (`shortname`) |
| `0x40032816` | 6 B | `jsr FUN_4000f9c4`, the long name for the popup | `jsr 0x40015154` (`popupname`) |

The three pads sit together at `0x4001511e..0x40015186` (104 B):

- **`addend`** @`0x4001511e` (22 B). The formatter's value argument is at sp@(12) inside the call. The pad
  returns +1.0 (`0x100`) for a value ≥ 0 and +9.0 (`0x900`) for a negative one, so −1 shows 8 … −8 shows 1.
  The stock formatter at `0x40065652` (26 B) adds its addend and tail-jumps into a plain-number formatter
  (`0x4013b30c`). Only its first instruction is replaced; the rest of the body, its `std::function` object
  and its binding stay byte-identical. The body is shared by Slice Length, CHAN, Bank and Program; the other
  three have min 0, never take the negative branch, and get exactly the stock addend.
- **`shortname`** @`0x40015134` (32 B). If the descriptor index at sp@(8) is 140 and the raw value at
  sp@(100) is negative, it returns `0x401b2401`, the string `TRK` (the tail of the stock string
  `LOAD TO TRK`); otherwise it tail-jumps to `FUN_4000f9a2`. The value's position: `vfunc_37` @`0x400302c8`
  reserves 64 B and saves 11 registers there, so an argument at entry offset N sits at SP+64+N; the value
  argument (entry +24) is at SP+88, and the two pushes at `0x40030462` plus the return address put it at
  sp@(100) in the callee.
- **`popupname`** @`0x40015154` (50 B). The popup is composed once per encoder event, `"%s=%s"`
  (`0x401a5dab`) pushed at `0x40032824` in `SoundPageView::vfunc_17` @`0x40032542`, which is the MIDI page's
  encoder handler. The raw value is no longer on the stack there, so for index 140 the pad replays the value
  fetch the stock code made just before: the page view's vptr +0x54 getter, called as (this = `a2`,
  id = `d2`, −1) in the stock push order. Negative → `0x401aea03`, the string `Track`; otherwise it
  tail-jumps to `FUN_4000f9c4`. The formatted text cannot be used for this test: the formatter already maps
  negatives to 1 … 8, so no minus sign ever appears.

Both strings already exist in the image, so the display adds no string bytes.

### The number in the dial

The cell's picture is drawn by a per-parameter drawer that prints its own number and never calls the
formatter, so the formatter patch cannot reach it.

- The runtime parameter-record table at `0x4193f1a8` holds 164 records of `0x54` B, parallel to the
  descriptor table (same index). Record +0x24 holds the cell-graphic `std::function`, which
  `ParameterSet::vfunc_23` @`0x4000edd4` dispatches (reached from `vfunc_37` as vfunc +0x5c). The builder
  `FUN_4013b6a2` fills every record.
- CHAN's runtime record is `0x41941f98` (`0x4193f1a8 + 140*0x54`). Its cell-graphic functor `0x4193e458`
  is installed at +0x24 (site `0x4013f1de`), and that functor's +0x0c holds the drawer **`0x40062fc4`**
  (`movel #0x40062fc4,%d0` at `0x4013bd0a`; the only pointer to the drawer in the image is at
  `0x4013bd0c`). Control for this recipe: Slice Select's functor `0x4193e1c8` yields its known drawer
  `0x40065234` (at `0x4013c32c`).
- The drawer reads the value at sp@(20), shifts it right by 8, and pushes value + 1 with `pea %a0@(1)` at
  `0x40062fe0`.

The fix replaces the 6 B at `0x40062fde` (`moveal %d0,%a0 ; pea %a0@(1)`) with `jmp 0x400aff60` and adds a
16 B pad that applies the same rule as the formatter:

```
400aff60  4a80            tstl  %d0            | d0 = value >> 8, sign-extended by the drawer
400aff62  6c02            bges  .pos
400aff64  5080            addql #8,%d0         | negative: +8 here, +1 below = +9
400aff66  5280     .pos:  addql #1,%d0         | the stock +1
400aff68  2f00            movel %d0,%sp@-      | push exactly what the displaced pea pushed
400aff6a  4ef9 40062fe4   jmp   0x40062fe4
```

`d0` and `a0` are dead at the site (reloaded at `0x40063012` and `0x40062fee`), nothing branches to
`0x40062fde` or `0x40062fe4`, and the pad is entered and left by `jmp`, so no return address is added.

**The drawer is shared.** Functor `0x4193e458` is installed for four page-12 records: 140 CHAN (slot
17), 141 BANK (18), 142 SBNK (19) and 147 PROG (20). All four have min 0 in stock and only CHAN's is
widened, so the negative branch is unreachable for the other three. The only negative min among the 164
stock descriptors is record 21 (Micro Timing, −23), which does not use this drawer. Any change that widens
the min of record 141, 142 or 147 must revisit this pad. ✅ confirmed on the test unit: the dial agrees with the popup on
every value, and BANK, SBNK and PROG are unchanged at their minimum.

## The channel hook (`0x400cfd94`)

Stock `FUN_400cfd6c` (52 B) is the per-MIDI-track channel lookup. It is called only from the MIDI-out task
`FUN_400d0578`, at six `jsr` sites (`0x400d06ce`, `0x400d0752`, `0x400d0896`, `0x400d0932`, `0x400d0c3a`,
`0x400d10fa`). If enable bit 0 is clear it returns −1; otherwise it returns the sign-extended channel byte,
read with `a0 = 0x421893dc` and `d0 = m*0x6a`. Every emitter tests the result for negativity; the gate at
`0x400d0ca4` (`tstl %d6 ; bltw`) skips the whole emit block on a negative channel, which is why a TRK value
on stock code simply sends nothing.

The patch replaces the 6 B at `0x400cfd94` (the channel-byte read and the `bra.s` after it) with
`jmp 0x40014cb4`. The splice ends exactly at `0x400cfd9a` (`moveq #-1,%d0`), the target of the `beqs` at
`0x400cfd86`, which stays untouched. The hook sits after the stock enable test, so a disabled track still
returns −1 from stock code and the TRK values never collide with "disabled".

```
40014cb4  7130 0846       mvsb  %a0@(0x46,%d0:l),%d0  | the stock read (a0 = 0x421893dc, d0 = m*0x6a)
40014cb8  4a80            tstl  %d0
40014cba  6a1e            bpls  done                  | 0..15: stock channel, returned unchanged
40014cbc  5080            addql #8,%d0                | TRK: audio track index t = CHAN + 8
40014cbe  0c80 00000007   cmpil #7,%d0
40014cc4  6212            bhis  none                  | unsigned: rejects t > 7 and t < 0 in one test
40014cc6  41f9 4193d70c   lea   0x4193d70c,%a0        | the per-track listening-channel table
40014ccc  2030 0c00       movel %a0@(0,%d0:l:4),%d0   | audio track t's own channel
40014cd0  0c80 0000000f   cmpil #15,%d0
40014cd6  6302            blss  done                  | a real channel: route
40014cd8  70ff     none:  moveq #-1,%d0               | OFF or out of range: no route (stock's own value)
40014cda  4ef9 400cfd9c done: jmp 0x400cfd9c          | the stock epilogue
```

- **Why a real channel.** With a channel in 0 … 15 the stock emit block runs: the note-on now, and the
  release scheduled as a 24 B node in the time-sorted list at `_DAT_421790a8` (free list `_DAT_421790ac`),
  due at `desc[8] + desc[6]*2`, fired by the audio ISR as a type-3 message and emitted as a velocity-0
  note-on at `0x400d0f7a`. The owner table `0x439888f8[chan*0x80 + note] = midiTrack + 1` is kept as for
  any channel. ✅ confirmed on the test unit: many repeated trigs with no parked voices and no refused trigs.
- **Why the 0 … 15 check is mandatory.** After the gate the stock code builds the status byte as
  `0x90 | channel` and indexes the owner table (and the node table `0x421770a0`) by `channel*0x80`; an
  out-of-range value would corrupt the status byte and index outside both arrays. The check also means the
  hook does not depend on how OFF is encoded (it is −1).
- **Registers.** The host saves only `d2` (restored at `0x400cfd9c`); `d0` is the return value and `a0` is
  dead after the read. The hook touches only `d0`/`a0` and is entered by `jmp`, so the frame is unchanged.

The listening-channel table `((int32*)0x4193d70c)[16]` has entries 0 … 7 for the audio tracks and 8 … 15
for the MIDI tracks; a value of 0 … 15 is channel 1 … 16 and −1 is OFF. `FUN_40083b2a` writes the defaults:
audio track i → channel i, MIDI tracks OFF, auto channel 9 (the auto channel lives at `0x4193d708`). The
MIDI-in matcher at `0x400c5928` walks all 16 entries (`cmpl %a1@+,%d1`), sets bit i for each match and
splits at i > 7.

⛔ Trap: objdump prints the `(d8,An,Xn)` displacement in hex without a prefix. `mvsb %a0@(46,%d0:l),%d0`
(extension word `0x0846`) is +0x46 = 70, so the channel byte is at `0x42189422 + m*0x6a`, not
`0x4218940a`. Decode the extension word; do not trust the printed number.

## The loopback tap (`0x400d0396`)

Stock `FUN_400d0396` (48 B) is the note byte appender: it appends (length, bytes) to the buffer at
`0x42176899`, with a one-byte length at `0x42176898`. It is the only producer of the MIDI note byte stream
and sees every note-on, retrigger release, scheduled release and panic. All six references enter at its
first instruction: two `jsr %pc@` (`0x400d0d10`, `0x400d0d32`) and four `lea %pc@` that take it as a function
pointer (`0x400d0872`, `0x400d0f22`, `0x400d0fc4`, `0x400d1052`). CC and parameter messages go through its
twin `FUN_400d0140` (buffer `0x42176c9a`), which is not hooked.

The patch is an entry trampoline. The first instruction (`mvz.b 0x42176898,%d1`, 6 B, absolute, not
PC-relative) becomes `jmp 0x40014ce0`; the tap replays it at its end and rejoins at `0x400d039c`. At entry
sp@(4) is the length and sp@(8) the byte pointer.

The tap (232 B, `0x40014ce0..0x40014dc8`) does, in order:

1. **Filter.** Only a 3-byte message whose type nibble is 8 or 9 goes on (a release is sent as a note-on
   with velocity 0, so both types matter). Anything else goes straight to the stock body.
2. **Rate guard.** It reads the private lane's pending count at `0x4216a078` (queue + 4) and skips the copy
   if the count is above 64, because the enqueue cannot refuse a message.
3. **Route scan**, stateless, for m = 7 down to 0: enable bit 0 set at `0x421897dc + m*4`; stored CHAN at
   `0x42189422 + m*0x6a` negative; t = CHAN + 8 within 0 … 7 (unsigned test); and `0x4193d70c[t]` equal to
   this note's channel (`status & 0x0F`). The first match posts; any match resolves to the same channel.
4. **Post.** It takes the next slot of a 32 × 4 B byte ring at `0x43990300` (one-byte index at
   `0x43990380`, incremented and masked to 0 … 31) and copies the 3 bytes there. It takes a descriptor from
   the firmware's own ring (`FUN_400c2c60`) and fills it with {length 3, pointer to the slot}. It calls
   `FUN_40001b7a(0x4216a074, descriptor)`, pushing the message first and then the queue, then
   `FUN_40001840(0x4216a06c)` to wake the input task.
5. **Rejoin.** It restores `d2`/`d3`/`a2` (saved with `movem` in a 12 B frame), replays the displaced
   instruction and jumps to `0x400d039c`. `d0`/`d1`/`a0`/`a1` are caller-saved scratch at entry, and the
   stack is back in its entry shape, because the stock body reads its own sp@(4)/sp@(8).

The scan decides per note from the live CHAN, enable and listening-channel values. It does not use a flag
set by the channel hook, so the two hooks do not depend on each other's call order.

| stock primitive | fact |
|---|---|
| `FUN_400c2c60` (42 B) | the MIDI-message descriptor **ring**, not an allocator: returns `0x4395E484 + 8*i` and advances the index at `0x420b31b8`, wrapping at 1023 (1024 slots of `{u32 length; u8 *bytes}`). No free list and no ownership: producers take the next slot. Clobbers only `d0`/`d1` |
| `FUN_40001b7a` (78 B) | queue post `(queue, msg)`, message pushed first so the queue lands at sp@(4); caller cleans. Masks to IPL 7 for the whole enqueue, so it is safe from any context. It has **no capacity check**: past the ring size it overwrites silently. Signals the queue's own semaphore at queue + 8 |
| `FUN_40001840` | semaphore signal. Needed after a post, because the MIDI input task waits on the shared semaphore `0x4216a06c`, not on the queue's own |
| `0x43990300` (32 × 4 B) and `0x43990380` (1 B) | the tap's byte ring and its index, in free `.bss` scratch (see [memory_map.md](../memory_map.md)). Uninitialised at boot is harmless: the index is masked, and a slot is read only after it is written |

⚠️ No feedback loop is expected: a copied note reaches an audio track, and the design rests on audio tracks
not transmitting MIDI, so a posted note does not come back through the tap. The rate guard bounds a runaway
either way.

## The private lane (`0x400c47ce` → `0x40015186`)

The stock MIDI input task `FUN_400c465e` binds three ports to the shared semaphore `0x4216a06c`, via
`FUN_400c31d8` (`0x400c4666..0x400c46b0`): port 0 DIN `0x4216a0b4`, port 1 USB cable 0 `0x4216a094`, port 2
USB cable 1 `0x4216a074`. It serves them round-robin, one message per port per pass. It dispatches through
the table `0x4019b750`, indexed by `status >> 4`, as `handler(bytes, length, origin)`. Each arm stamps an
**origin tag** chosen by queue: DIN 0x08 (0x10 on the auto channel), USB 0x02 (0x04 on the auto channel);
the Digitakt's own pads are 0x40; 0x01 and 0x20 are unused in stock. The DIN and USB arms are gated by
INPUT FROM (`0x4193d760` bit 0 / bit 1). Port 2's arm at `0x400c47ce` is an 18 B stub that receives a message
and discards it; it runs only when its queue is non-empty (the `blew` at `0x400c47ca`).

✅ The Digitakt exposes one MIDI port in every USB CONFIG mode (test unit), so no host can
address cable 1. The tap is the lane's only producer.

⛔ Ruled out: reaching cable 1 from a computer. One configuration descriptor declares `bNumEmbMIDIJack = 2`
(MS bulk endpoints at `0x402134fb` / `0x4021350a`), but the device never presents a second port. Do not
infer a port count from the descriptors.

⛔ Ruled out: posting the copy into the DIN queue `0x4216a0b4`. The note would carry DIN's origin 0x08, so
recording could not tell it from cable input, and it would depend on the INPUT FROM setting.

The patch replaces the stub's first 6 B with `jmp 0x40015186`. `0x400c47e0` (`pea 0x4216a094`), the target
of the `blew` at `0x400c46ec`, is not touched; the remaining 12 B of the stub become unreachable and are
left unchanged. The arm (50 B, `0x40015186..0x400151b8`) mirrors the DIN arm at `0x400c46fe..0x400c4750`
instruction for instruction, with three differences: the queue is `0x4216a074`; there is no INPUT FROM gate
(there is no bit for port 2, and a port setting should not disable an internal route); and there is no
auto-channel branch, just a fixed origin 0x20. It reuses the task's live registers exactly as the other
arms do: `a4` = `FUN_40001c0c` (queue receive), `a2` = the dispatch table.

```
40015186  4879 4216a074   pea   0x4216a074          | the private lane (USB cable 1)
4001518c  4e94            jsr   %a4@                | FUN_40001c0c -> d0 = descriptor {len, bytes*}
4001518e  2240            moveal %d0,%a1
40015190  2069 0004       moveal %a1@(4),%a0        | the message bytes
40015194  2f48 0030       movel %a0,%sp@(48)        | the task's local slot, as the DIN arm spills it
40015198  1210            moveb %a0@,%d1            | status byte
4001519a  588f            addql #4,%sp              | drop the pea
4001519c  7181            mvzb  %d1,%d0
4001519e  e888            lsrl  #4,%d0              | dispatch index = status >> 4
400151a0  4878 0020       pea   0x20                | origin tag of the private lane
400151a4  2f11            movel %a1@,%sp@-          | length
400151a6  2f08            movel %a0,%sp@-           | bytes
400151a8  2072 0c00       moveal %a2@(0,%d0:l:4),%a0
400151ac  4e90            jsr   %a0@                | handler(bytes, length, 0x20)
400151ae  4fef 000c       lea   %sp@(12),%sp
400151b2  4ef9 400c46da   jmp   0x400c46da          | back to the top of the task's loop
```

- **Why 0x20.** It is unused in stock, and it lies outside `FUN_400c53c6`'s "playable" set
  {0x04, 0x10, 0x40, 0x80}, so a routed note takes the plain per-track path and does not inherit a held
  trig's Sound lock. ⚠️ Read from the code; not tested separately.
- **Downstream gates.** RECEIVE NOTES (`0x4193d784`) is tested inside the note handler `FUN_400c5bda`, so
  it still applies; INPUT FROM does not. ⚠️ From the code, not tested on the device.
- **Delivery.** The note reaches the tracks through the stock channel fan-out (`FUN_400c5a24`), the same
  path as a note arriving on a cable.

## The record filter (`0x400c4bda`)

**Stock path.** Both live note paths converge on `FUN_400c53f2` (note-on; its twin `FUN_400c578e` handles
note-off). In program order it triggers the voice (`jsr 0x4007683c` at `0x400c562e`), pushes to MIDI out
(`jsr 0x400d129c` at `0x400c5642`), quantises (`0x400711a4`), and last of all calls the recorder feed
(`jsr 0x400c4bda` at `0x400c56da`).

**The feed.** `FUN_400c4bda` copies a `0x20`-byte event into the 16-slot recorder ring at `0x4215d218`,
posts it to the main task's queue (pointer at `0x401d1fb8`) and advances the ring index at `0x4215cf54`. It
has three callers (`0x400c4c8e`, `0x400c56da`, `0x400c58d8`), all entering at its start. The main task
`FUN_4000ae46` hands message type 12 (`case 0xc`) to the recorder `FUN_40008a42` (live-record test at
`0x40008acc`), which writes trigs through `FUN_4001bbd6`.

**Why playback is not recorded.** The sequencer's own playback posts engine events only and never calls the
feed, which is why playback does not re-record itself.

**Record state.** Live record is `*(u8*)(*(u32*)0x421b9db0 + 0x169)` (getter `FUN_4008f2e0`, which reads
true only while the transport runs); grid record is +0x16a.

**Event fields the filter reads:**

- `event[0]` is a **message type**: the producer writes 12 (`moveq #12`) for a recorder message.
- `event + 0x0c` is the **origin** (a long). This comes from the producer: the feed's argument is built at
  `0x400c5486` as `%fp−48`, and the origin argument (`%fp@(20)`, also passed to `FUN_400c53c6` at
  `0x400c5424`) is stored at `%fp−36`, which is base + 12. Every event write falls inside the 32 B window
  at `%fp−48`.

**Origin classes.** `FUN_400c570e(tag)` returns 1 for {0x02, 0x04, 0x08, 0x10}, the external ports; the
recorder calls it at `0x40008bec`. `FUN_400c56ea(tag)` returns 1 for {0x20, 0x40, 0x80}, the local origins.
The filter tests for exactly 0x20: testing the local class would also stop pad recording (0x40).

The patch turns the feed's first 8 B (`move.l %d2,-(sp)` and `move.l 0x4215cf54,%d2`) into
`jmp 0x400151b8 ; nop`:

```
400151b8  206f 0004       moveal %sp@(4),%a0        | the event the feed is about to copy
400151bc  7190            mvzb  %a0@,%d0            | event[0] = message type
400151be  0c80 0000000c   cmpil #12,%d0
400151c4  660c            bnes  pass                | another type: layout unknown, leave it alone
400151c6  2028 000c       movel %a0@(12),%d0        | event + 0x0c = origin
400151ca  0c80 00000020   cmpil #32,%d0
400151d0  670e            beqs  drop                | 0x20 = the private lane
400151d2  2f02     pass:  movel %d2,%sp@-           | replay the 8 displaced bytes ...
400151d4  2439 4215cf54   movel 0x4215cf54,%d2
400151da  4ef9 400c4be2   jmp   0x400c4be2          | ... and rejoin the feed
400151e0  4e75     drop:  rts                       | nothing pushed yet: the caller's frame is intact
```

The pad touches only `d0`/`a0`. The drop path returns a different `d0` than the normal path would, which is
harmless: on the normal path `d0` holds a leftover ring index, not a return value.

**Why it is safe.** The feed call is the last action of `FUN_400c53f2`, after the voice trigger and the
MIDI-out push. Dropping the event can therefore only stop recording; it cannot affect what is heard.

⚠️ The filter drops only origin 0x20, so recording of what is played on the MIDI track itself is not
affected by it. This holds by construction and has not been tested separately.

⛔ Trap: a search for the ring base `0x4215d218` finds nothing, because the code builds it as a decimal
immediate (`addil #1108726296`). The evidence that the feed is the ring's only producer is the index
`0x4215cf54`, which is referenced only inside `FUN_400c4bda` (3 references).

⚠️ Unreconciled, and not needed by this filter: on the recorder side `FUN_400b6186(x)` returns `x + 12`,
so the recorder's origin-shaped read `%a2@(12)` is at arg2 + 24, not + 0x0c. The recorder's arg2 is
evidently a larger object than the ring event (its only caller is `0x4000b660`, passing `FUN_40122d70()` and
`d2`). Do not reuse +0x0c in a recorder-side patch without resolving this.

## Caveats

- **Routed notes also go out.** The channel hook runs the stock emit block on the destination's listening
  channel, so every routed note and its note-off is also sent from MIDI OUT / USB on that channel. External
  gear on that channel plays it too. With a physical MIDI OUT → IN loop connected (or a device that echoes
  MIDI back), every note arrives twice.
- **Copying is keyed on channel.** The tap copies any note whose channel an active Loopback route resolves to.
  That includes notes from a MIDI track with an ordinary CHAN that happens to use the same channel, exactly
  as a cable would.
- ⚠️ **Every listener on that channel receives the note.** The stock matcher sets a bit for every track
  whose listening channel matches, so any other track listening on the destination's channel gets the
  routed note as well. This follows from the code and has not been tested.
- **A same-note retrigger follows MIDI semantics.** A MIDI track that re-triggers a note it is still
  holding sends the note-off for the old instance first; this is stock behaviour, tracked per (channel,
  note) by the owner table. The loopback passes that stream on unchanged. On the audio track the new note
  cuts the previous instance instead of layering on it. ✅ Cut observed on the test unit.
  ⚠️ The audio-side code path that produces the cut has not been traced.
- **The destination's mute does not silence routed notes.** Routed notes reach the audio track as
  priority-2 (live) events, and the audio ISR's mute test only examines priority-1 sequencer trigs. The
  destination therefore behaves as it does for live MIDI played into a muted track. ✅ Observed on the test
  unit. This is intended; see [mute_by_origin.md](mute_by_origin.md). ⚠️ Muting the *MIDI
  track* should silence the route, because the MIDI-out task's mute/solo gate (`0x400d07ce`, masks
  `0x421897d0` / `0x421897d4`) runs before the channel gate. This is read from the code, not tested.
- ⚠️ **Only notes are looped back.** The tap copies 3-byte `0x8n` / `0x9n` messages from the note appender,
  and CC and parameter messages use the unhooked twin `FUN_400d0140`. So nothing but notes reaches the
  audio track internally. This is inferred from the code.
- ⚠️ **Several MIDI tracks on one audio track.** Each transmits on the same substituted channel, and the
  stock per-(channel, note) bookkeeping serialises them. This is expected but has not been tested.
- **No wire delay.** The loopback delivers a burst (for example a chord's note-offs followed by its
  note-ons) with no spacing, so it can land in a single audio tick. This exposed a stock bookkeeping defect,
  which this build fixes separately; see [tick_wipe_fix.md](tick_wipe_fix.md).
- **Voices.** Routed notes enter the voice gate as priority-2 events (`FUN_4007683c` writes `event[3] = 2`),
  like any live MIDI note; see [voice_allocation.md](voice_allocation.md). A routed note held on a POLY
  voice across a pattern switch releases at its length; ✅ confirmed on the test unit, see
  [owner_latch.md](owner_latch.md).
- ⚠️ **Stock firmware.** A project saved with a TRK value keeps it on stock firmware: the kit loader does
  not clamp MIDI values. Stock treats a negative channel as "do not send", so that MIDI track would be
  silent there. This is inferred and has not been tested on stock; see
  [compatibility.md](../compatibility.md).

## Patched bytes

All edits are in section 3 (MAIN OS). The generated per-run listing is
[docs/patch_listing.md](../../docs/patch_listing.md); the build data is
[build/patch.json](../../build/patch.json).

| load address | file offset | size | new bytes | part |
|---|---|---|---|---|
| `0x40191C00` | `0x191800` | 4 B | `fffff800` | CHAN descriptor min |
| `0x40065652` | `0x065252` | 6 B | `4eb9 4001511e` | formatter → `addend` |
| `0x40030466` | `0x030066` | 6 B | `4eb9 40015134` | short name → `shortname` |
| `0x40032816` | `0x032416` | 6 B | `4eb9 40015154` | long name → `popupname` |
| `0x40062fde` | `0x062bde` | 6 B | `4ef9 400aff60` | dial number → pad |
| `0x400cfd94` | `0x0cf994` | 6 B | `4ef9 40014cb4` | channel hook |
| `0x400d0396` | `0x0cff96` | 6 B | `4ef9 40014ce0` | loopback tap (entry trampoline) |
| `0x400c47ce` | `0x0c43ce` | 6 B | `4ef9 40015186` | private-lane arm |
| `0x400c4bda` | `0x0c47da` | 8 B | `4ef9 400151b8 4e71` | record filter (entry trampoline) |
| `0x4001511e..0x40015186` | `0x014d1e` | 104 B | pad | `addend` (22) · `shortname` (32) · `popupname` (50) |
| `0x400aff60..0x400aff70` | `0x0afb60` | 16 B | pad | dial number |
| `0x40014cb4..0x40014dc8` | `0x0148b4` | 276 B | pad | channel hook (44) · loopback tap (232) |
| `0x40015186..0x400151b8` | `0x014d86` | 50 B | pad | private-lane arm |
| `0x400151b8..0x400151e2` | `0x014db8` | 42 B | pad | record filter |

The pads live in three device-proven landing pads: `0x40014cb4..0x40014dd8` (292 B, 16 B left after this
feature), `0x40015060..0x400151ec` (396 B, shared with [pool cursors](pool_cursors.md), 10 B left) and
`0x400aff0e..0x400aff76` (6 B left). See [landing_pads.md](../landing_pads.md).

Where each piece runs, which is also where a fault would show:

| piece | runs in | when |
|---|---|---|
| display pads | UI draw of the MIDI SRC page and the encoder popup | the grid draw runs at first paint of that page |
| dial pad | the cell drawer of CHAN / BANK / SBNK / PROG | whenever one of those cells is drawn |
| channel hook, loopback tap | the MIDI-out task | only when a MIDI track sends |
| private-lane arm | the MIDI input task (started at boot) | only when the private lane holds a message |
| record filter | the recorder feed | every recorder event: live notes from any source, pads included |

Verification: the display pads and the private-lane arm were also stepped in the emulator (see
[emulator.md](../emulator.md)); the channel hook, the tap and the record filter were verified from the
disassembly and on the test unit.

## Open questions

- ⏳ DIN MIDI recording and grid recording have not been tested with the record filter in place. Both take
  the same fall-through path as the confirmed USB and pad cases.
- ⏳ Several MIDI tracks pointed at one audio track have not been tested.
- ⏳ Changing CHAN (to another TRK value or to a channel), or switching to a pattern where the MIDI track
  points elsewhere, while its routed notes are held, has not been tested with this build. ⚠️ From the
  code, a scheduled note-off keeps its note-on's channel (node + 0x0c, read at `0x400d0f44`), but the tap
  decides from the live CHAN. Unless another route still resolves to that channel, the note-off is not
  looped back, and the note may keep sounding on the old destination.
- ⏳ Which audio-side code path makes a same-note retrigger cut the previous instance.
- ⏳ The recorder-side event structure (the arg2 + 24 reading above).

See also [open_questions.md](../open_questions.md).
