# Chain Recording: the recorder fills a sample chain one slot at a time

## What this is

On the recorder page, encoder D sets the chain: a slot count N of 4, 8, 16, 32 or 64, with manual
arming or auto re-arm, or off. Turning D up steps through AUTO 64, 32, 16, 8, 4, then off, then 4, 8,
16, 32, 64 with manual arming; it stops at both ends. D moves at the speed of encoders E, G and H, and
only while the recorder is idle. With a chain set and RLEN at a number of steps, each recording is one
slot of RLEN steps:

- the idle prompt reads `YES: ARM k/N` (manual arming) or `YES: AUTO k/N` (auto re-arm), and the armed
  line `ARMED k/N`, with k the slot to record next;
- after each slot the recorder keeps what it has recorded, and the next slot is written directly after
  it. With manual arming the recorder goes back to idle and the user arms each slot; with auto re-arm
  it arms itself, and the next threshold hit records the next slot;
- after slot N the stock end of recording runs: normalise (once, over the whole chain), trim, save.

All slots have the same length, so the saved sample divides into N equal slices: on a SLICE machine
with GRID = N, each slice is one slot. While a chain is set, the MEM line shows the length of the
whole chain (N × RLEN), with the stock mark when it does not fit in the 33 s of sample memory. At RLEN
MAX, or with the chain off, the recorder is stock, and so is the prompt.

FUNC+NO while the recorder is idle between slots drops the chain, as ABORT does while armed.

✅ This build has run on the test unit with OS 1.54, and it worked there
([Testing on the unit](#testing-on-the-unit)). ⚠️ One display glitch is open
([Open: the screen after saving](#open-the-screen-after-saving)). The code is read in objdump and
the decompile of the `dt_1.54_emac` project, and checked in the emulator (`EmuChainRecord`,
[scripts/emu/README.md](../../scripts/emu/README.md)).

Conventions: MAIN OS load addresses of OS 1.54. "Position" is the recorder's write position in
samples (48 kHz).

## The stock recorder

✅ Read in the code (objdump, decompile).

### Engine state

| Address | Contents |
|---|---|
| `0x4199f114` | state: 0 idle, 1 armed, 2 recording, 3 stopped (normalising), 4 trim |
| `0x4199f104` | the write position |
| `0x4199f100` | LEN, the position at which recording stops: an absolute position, compared with the write position |
| `0x4199f0fc` | RLEN in steps, `1 << index`; 0 is MAX |
| `0x4197cf98` | the source, 0..16 |
| `0x4237ef90 + 2 × position` | the upper 16 bits of each sample |
| `0x421fc410 + position` | bits 15..8 of each sample |

The cap is 1,584,000 samples (`0x182b80`, 33 s). All of the state lies in `.bss`, so start-up clears
it ([memory_map.md](../memory_map.md)).

### Engine functions

| Function | What it does |
|---|---|
| `FUN_40076540` | End of recording: state 3, then a message to the user interface (`0x401f8ce0` through `FUN_40001b7a`) |
| `FUN_400765d8` | Returns the state |
| `FUN_400765e0` | Sets RLEN from an index: `1 << index`, or 0 (MAX) above 7; only in states 0 and 4 |
| `FUN_40076616` | The slot length in samples, from RLEN and the tempo (`FUN_400770c8`); 1,584,000 at MAX |
| `FUN_40076650` | The per-block routine, called by the audio ISR `FUN_40077420` for every 32-sample block: source copy, level meter, threshold, write, stop (below) |
| `FUN_40076898` | Returns 1,584,000, the sample memory |
| `FUN_400768a0` | ARM: from state 0 or 4 to state 1, write position 0; returns 1 if it armed. Interrupts masked |
| `FUN_400768ce` | REC: from state 0, 1 or 4 to state 2, write position 0, LEN = `FUN_40076616()`. Interrupts masked |
| `FUN_40076918` | The STOP key: from state 2, `FUN_40076540` |
| `FUN_4007693c` | ABORT: from state 1, 2 or 4 to state 0, write position 0 |
| `FUN_40076a04` | The normaliser, after state 3: pads the recording to at least 144 samples, finds its peak over [0, position) (`FUN_4007699e`), and either returns to state 0 (all silent) or scales the whole recording and sets state 4 |
| `FUN_40076b02` | Returns the state and the write position together, with interrupts masked |

`FUN_40076616` computes RLEN × 5,760,000 / (4 × tempo word), rounded down, times 60; the tempo word
(`0x4020df10`, read by `FUN_400770c8`) is 14,400 in the stock image. ⚠️ So the tempo word is very
probably the tempo in BPM × 120, and a step is a sixteenth note: RLEN × 15 / BPM seconds.

### The per-block routine

`FUN_40076650` copies the selected source into the block buffer `0x800032c4` (32 words), updates
the level meter, and then:

1. **Threshold, state 1 only** (`0x400767c0..0x4007681a`). It scans the block for the first sample
   whose magnitude reaches the threshold level (`FUN_400e8a38`). At a hit it sets
   LEN = `FUN_40076616()` (`0x400767fc`) and state 2.
2. **Write, state 2** (`0x4007681c..0x4007686c`). It writes the block at the write position, at most
   up to the cap, and advances the position.
3. **Stop.** When the position passes the cap, or LEN > 0 and the position has reached LEN, it calls
   `FUN_40076540` as a tail call (`0x40076876..0x40076882`). The position is not cut back, so a
   recording ends on a block edge, up to 31 samples past LEN.

⚠️ A quirk of the stock threshold. When the hit is sample j of a block, the routine writes the first
32 − j samples of that block (the count left in `%d2` when the scan stops), starting at the block's
first sample. The following block is written right after. So a threshold recording starts with up
to j samples from before the hit, and loses the j samples at the end of the first block. For j ≥ 16
that includes the hit sample itself. Chain Recording keeps this behaviour for every slot.

### The recorder page

`SamplerView` (RTTI):

| Function | What it does |
|---|---|
| `vfunc_2` @`0x400a9784` | Keys. In state 0, YES arms (`FUN_400768a0`) and FUNC+YES records (`FUN_400768ce`); both clear the view's waveform cache. In state 1, FUNC+NO aborts. In state 2, YES stops. In state 4, the trim and save keys. NO in state 0 closes the page; with FUNC held it is passed on (`FUN_400c98f4`) and does nothing |
| `vfunc_4` @`0x400a8c94` | The draw. In state 0: the MEM line (`0x400a8e7c..0x400a8f10`, the length from `FUN_40076616` capped at the memory, and a mark when it does not fit), the two prompt lines (FUNC+YES at y `0x1d`, YES at y `0x16`, both centred at x `0x40`), and the E–H values and labels. In state 1: the ARMED line at y `0x1d` and the ABORT line. In state 2: the time recorded and the STOP line |
| `vfunc_11` @`0x400a7b68` | The update: reads the state and position through `FUN_40076b02`, extends the waveform cache (126 columns over the whole 33 s) up to the position, and redraws |
| `vfunc_17` @`0x400a7e38` | The encoders. In states 0 and 1: E sets RLEN (`FUN_400a786c`), F THR, G the source and H MON, tested in that order. A–D move the trim points in state 4 and do nothing in states 0 and 1 |

The MEM line prints the length as whole seconds and hundredths, two digits each, the seconds marked
`'` and the hundredths `''`. ✅ On the test unit with stock OS 1.54, RLEN 1, 2 and 64 steps showed
`00'10"`, `00'21"` and `06'85"` (0.10 s, 0.21 s and 6.85 s), and MAX `33'00"`.

Each of E–H passes its event through the step accumulator `FUN_400c0816` (the view's state block at
`+148`, the event, a speed table) and shifts the result right by 8 for whole steps. E, G and H use the
speed table `0x4208db80`, F `0x4208dbb8`. The accumulator keeps a 20 B slot per encoder in the block
([function_ledger.md](../function_ledger.md)).

A key event holds the key id at `+12` (YES is 12, NO 13) and flags at `+16`: bit 0 down, bit 1 FUNC
held, bit 3 (⚠️ very probably) auto-repeat. A press is bit 0 set and bit 3 clear. ABORT in state 1
takes FUNC held and a press.

`SamplerLedView::vfunc_2` @`0x40036548` calls the same engine functions (ARM, REC, STOP, ABORT), so
Chain Recording applies to recordings started there too. That view draws no slot count.

## How this build does it

### The chain word

One 32-bit word at `0x439d1038` ([memory_map.md](../memory_map.md)): `0xC4A1` in bits 31..16, the
chain setting in bits 15..8 as a signed byte (N for manual arming, −N for auto re-arm, 0 off), and k,
the number of slots done, in bits 7..0. The word is above `.bss`, so start-up does not clear it. The
marker in the upper half tells a word this build wrote from whatever the memory held: any other value
means chain mode is off.

A chain is **in progress** when k > 0 and the write position is still exactly where the last slot
ended: position = LEN > 0. A stop, an abort and a new recording each move the position away from
LEN. Start-up clears both to 0, while the chain word may survive. Either way the next arm starts a
new chain. Chain mode is **off** when the word has no marker, when the setting is 0, or when RLEN is
MAX.

### The hooks

| Site | Stock code | Now | Pad |
|---|---|---|---|
| `0x400767fc` (threshold hit) | `jsr FUN_40076616 ; movel %d0,LEN` | `jsr len_pad ; nop ; nop` | `len_pad` sets LEN = slot length + position. In stock the position is 0 there, so LEN is unchanged |
| `0x40076900` (REC) | the same | the same | `len_pad` |
| `0x400768c2` (ARM) | `clrl position` | `jsr arm_pad` | `arm_pad` keeps the position when a chain is in progress; otherwise it clears the position (stock) and sets k = 0. It keeps `%d0` (ARM's result) and `%d1` (the saved status register) |
| `0x400768fa` (REC) | `clrl position` | `jsr arm_pad` | `arm_pad` |
| `0x4007687a` (stop) | `lea 16(sp),sp ; bra FUN_40076540` | `jmp stop_pad ; nop` | `stop_pad`, below |
| `0x400a7f40` (`vfunc_17`, after the H test) | `tstb %d0 ; beqw exit` | `jmp enc_pad` | `enc_pad`: H takes the stock path. Encoder D (event id 4) in state 0 goes through the accumulator with G's speed table, as G does; each time it gives a whole step, the setting moves one place (up: AUTO 64 → … → AUTO 4 → off → 4 → … → 64, held at the ends), k = 0, and the view redraws (`0x400c9a3a`). Less than a whole step does nothing. Anything else exits as stock |
| `0x400a8e7c` (MEM line) | `jsr FUN_40076616` | `jsr mem_pad` | `mem_pad`: the slot length, × N when chain mode is on |
| `0x400a8f48` and `0x400a8f60` (YES prompt) | `pea <prompt> ; pea 2` … `lea 32(sp),sp` | `jsr fmt_arm ; nop ; nop` … `lea 40(sp),sp` | `fmt_arm` pushes (N, k + 1) below the format and the alignment. The format is `YES: ARM %d/%d` with manual arming, `YES: AUTO %d/%d` with auto re-arm, else the stock string, which ignores the two values |
| `0x400a9026` and `0x400a9044` (ARMED line) | `pea <armed> ; pea 2` … `lea 28(sp),sp` | `jsr fmt_armed ; nop ; nop` … `lea 36(sp),sp` | `fmt_armed`: the same, with `ARMED %d/%d` in both modes |
| `0x400a9878` (`vfunc_2`, state 0, the NO key) | `movel %d2,%sp@- ; jsr FUN_400c33cc` | `jmp no_pad ; nop` | `no_pad`: a press with FUNC held while a chain is in progress sets the position to 0 and k = 0, redraws, and continues at `0x400a984e`, the code after ARM: it clears the waveform cache, finishes the key and returns. Anything else replays the stock code and returns to `0x400a9880` |

`stop_pad` runs inside the audio ISR, where the block routine has already restored `%d2-%d4/%a2`. It
releases the routine's 16 B frame, then:

- If chain mode is off, or the slot ended short of LEN (the cap stopped it), it sets k = 0 and
  continues to the stock `FUN_40076540`.
- Otherwise it sets the position to LEN. This drops what the last block wrote past the slot's end;
  the next slot overwrites it.
- If that was slot N, it sets k = 0 and continues to `FUN_40076540`: the stock end of recording, with
  the recording exactly N slot lengths long.
- Otherwise it sets k + 1 and state 0 (idle) with manual arming, or state 1 (armed) with auto re-arm,
  and returns as the block routine does.

No hook site is a branch target except `0x400767fc`, which is the first instruction of its
replacement (listing scan). No hook or pad lies in a protected range. The call rule holds: no direct
target of a patched instruction lies in a protected range ([update_moat.md](../update_moat.md)).

In Ghidra's emulator, a branch right after `mvs.b` saw the condition codes of the instruction before
it, although the ColdFire manual has `mvs` set N and Z. So the pads take no branch on flags from
`mvs`: the signed setting is sign-extended with `extb.l`, which sets them in the emulator and in the
manual alike.

### Where the code lives

| Pad | Range used | Holds |
|---|---|---|
| the STL span's tail | `0x40177104..0x40177192` (142 B of 144) | `chain`, `chain_raw`/`chain_any` (the chain word's state), `stop_pad` |
| the POLY kit-load pad's tail | `0x400bf1cc..0x400bf1e0` (20 B of 28) | `len_pad` |
| the POLY machine-name pad's tail | `0x400c1062..0x400c107e` (28 B of 30) | `mem_pad` |
| the soft-float pad `FUN_40124a6c` + `FUN_40124ac4` | `0x40124a6c..0x40124b24` (184 B of 198) | `enc_pad`, `arm_pad`; the rest keeps its fill |
| the frame-registration pad `FUN_40128244` + `FUN_40128288` | `0x40128244..0x401282ce` (138 B of 156) | `fmt_arm`/`fmt_armed`, `no_pad`; the rest keeps its fill |
| `.rodata` padding | `0x40252c00..0x40252c2b` (43 B) | `YES: ARM %d/%d\0ARMED %d/%d\0YES: AUTO %d/%d\0` |

The pads and their vetting: [landing_pads.md](../landing_pads.md). Every changed byte:
[docs/patch_listing.md](../../docs/patch_listing.md#chain-record).

### Source

The code is written in assembly, in [src/chain_record/](../../src/chain_record/):

- `chain_record.s` holds the hooks and the pads;
- `chain_record.ld` places each section at its hook site or pad;
- `make_chain.py` assembles the code, checks it, and checks the `chain_record` runs in
  `os/1.54/build/patch.json`.

`make_chain.py` makes these checks:

- every hook site still holds the stock bytes it displaces;
- every pad stays inside its range;
- no other feature's run is at a hook site;
- the build without Chain Recording, rebuilt from `patch.json`, has section 3 `5a7eb2a4…`.

`patch.json` also holds the features merged after Chain Recording, the CFO oscillator and portamento.
`make_chain.py` takes them off first (`make_cfo.build_without_cfo`, which gives the bytes CFOO rewrites
their earlier values), then compares its own `chain_record` runs and S8's section 3 with that build
(`efc90606…`). Its `--write` is refused while features follow Chain Recording in `patch.json`;
`make_port.py --stages --write` writes the file ([portamento.md](portamento.md#checks)). `build.py`
itself needs only `patch.json`.

```
python3 os/1.54/src/chain_record/make_chain.py            # is patch.json up to date?
python3 os/1.54/src/chain_record/make_chain.py --stages   # build the stage images
```

Set `M68K_PREFIX` when the binutils are not `m68k-elf-` (Debian: `M68K_PREFIX=m68k-linux-gnu-`).

## What ends a chain

| Event | Result |
|---|---|
| slot N completes | the stock end of recording: normalise, trim, save; exactly N slot lengths |
| the STOP key during a slot | the stock end of recording, with the slots so far and the part of the current one; the next arm starts a new chain |
| ABORT (FUNC+NO) while armed | stock: everything recorded so far is dropped (position 0); the next arm starts a new chain. With auto re-arm the recorder is armed between slots, so this is how a chain is dropped there |
| FUNC+NO while idle between slots | the chain is dropped (position 0, k = 0) and the waveform cleared; the next arm starts a new chain |
| the 33 s cap during a slot | the stock end of recording, with what fits |
| encoder D while idle | a new setting and k = 0: the next arm starts a new chain |
| start-up | position and LEN are cleared; the next arm starts a new chain. ⚠️ Whether the chain word itself survives a restart without a power cycle is not known; if it does, the setting is kept |

Not prevented: changing RLEN or the tempo between slots gives slots of different lengths, which no
longer divide evenly. Leaving the recorder page between slots keeps the chain (state 0, the
recording kept). ⚠️ Not traced: whether anything else writes the recording buffer while the page is
closed. With auto re-arm, a sound still above the threshold when a slot ends starts the next slot at
once. ⚠️ After ABORT drops an auto re-arm chain, the page's waveform cache still shows the chain until
the next arm clears it, as the code reads; not seen on a unit.

## Testing on the unit

The port's three stages have run on the test unit ([README.md](../README.md#the-test-unit)):

| Stage | Contents |
|---|---|
| S0 `81d5a468` | the stock MAIN OS, repacked by the tool |
| S1 `5001c01f` | the fill test of the nine existing pads |
| S2 `6dec6823` | every feature of the port |

✅ Then, on the test unit with OS 1.54, one image at a time, each on top of the one before:

| Stage | `.syx` | Section 3 | Contents | Result |
|---|---|---|---|---|
| S3 | `2c292d2e` | `b94200d3…` | S2 + the soft-float pad `0x40124a6c..0x40124b32` filled with `clrl %d0 ; rts` | ✅ started and ran; nothing found wrong |
| S4 | `a2501d97` | `7988ef5f…` | S3 + every hook of a first Chain Recording build (the eleven above other than `0x400a9878`), each pad only replaying the stock code it displaced | ✅ started and ran; nothing found wrong |
| S5 | `688066c9` | `d90c19f6…` | S3 + that first build: manual arming only, encoder D stepping N once per encoder event, off → 4 → … → 64 | ✅ see below |

✅ S5 on the test unit: with N = 4 (`YES: ARM 1/4`) and RLEN 8 steps, MEM showed `03'42"`. Four
threshold-started slots, each armed with YES, recorded a chain that was normalised, trimmed and
saved; YES between slots armed the next slot. FUNC+NO between slots did nothing; it worked only once
the recorder was armed again. Encoder D stepped N much faster than encoder G steps its value. This
build changes those two: encoder D goes through the accumulator, and FUNC+NO works between slots.

This build adds three stages, written by `make_chain.py --stages` to `out/1.54/stages/`. Each one
changes one thing from an image that ran on the unit:

| Stage | `.syx` | Section 3 | Contents |
|---|---|---|---|
| S6 | `4aae845e` | `5ae51b30…` | S3 + the frame-registration pad `0x40128244..0x401282e0` filled with `clrl %d0 ; rts` (its fill test) |
| S7 | `ad7c9a72` | `aaedd690…` | S6 + every hook of this build, each pad only replaying the stock code it displaced (the first code through the new hook at `0x400a9878`) |
| S8 | `3fd4b0a3` | `efc90606…` | S6 + Chain Recording: the build before the CFO oscillator and portamento |

What to check:

- **S6:** nothing changes anywhere.
- **S7:** the recorder behaves exactly as stock: arm, threshold, REC, stop, abort, trim, save,
  encoders E–H, NO closing the page, and FUNC+NO; encoder D does nothing.
- **S8, chain off** (RLEN MAX, or D at off): the recorder and its prompt are stock.
- **S8, encoder D:** it moves at the speed of encoder G. Turning up from off steps the prompt through
  `YES: ARM 1/4` … `1/64`; turning down from off through `YES: AUTO 1/4` … `1/64`; MEM shows
  N × RLEN.
- **S8, manual arming:** as on S5: `ARMED k/N` after each YES, `YES: ARM k+1/N` after each slot, the
  stock end after slot N. FUNC+NO between slots drops the chain: the prompt goes back to
  `YES: ARM 1/N` and the waveform clears. FUNC+NO before the first slot does nothing.
- **S8, auto re-arm:** one YES arms the first slot; after each slot the line reads `ARMED k+1/N`
  with no key press; the stock end after slot N. FUNC+NO while armed between slots drops the chain.
- **S8, the result:** on a SLICE machine with GRID = N, each slice is one hit, starting at its attack.

✅ On the test unit with OS 1.54, S6, S7 and S8 each started and ran, and what was checked on them
worked; the result was reported for the three images as a whole, not check by check. One display
glitch was seen (below).

## Open: the screen after saving

⚠️ After the screen that asks whether to apply the new sample to a track, the top and bottom parts
of the screen stay black, and only the sample shows. Seen on the test unit with OS 1.54 while S6, S7
and S8 were tested. Not recorded: which of the three images show it, and whether stock OS 1.54 or the
earlier stages show it too. The cause is not traced.

## Related notes

- [landing_pads.md](../landing_pads.md): the two pads Chain Recording adds, and their vetting.
- [memory_map.md](../memory_map.md): the chain word.
- [function_ledger.md](../function_ledger.md): the recorder's functions.
- [startup_hooks.md](../startup_hooks.md): none of these hooks runs at startup.
