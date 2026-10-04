# Chain Recording: the recorder fills a sample chain one slot at a time

## What this is

On the recorder page, encoder D sets a slot count N: off, 4, 8, 16, 32 or 64. With N set and RLEN at
a number of steps, each recording is one slot of RLEN steps:

- the idle prompt reads `YES: ARM k/N` and the armed line `ARMED k/N`, with k the slot to record
  next;
- after each slot the recorder goes back to idle and keeps what it has recorded; the next arm writes
  the next slot directly after it;
- after slot N the stock end of recording runs: normalise (once, over the whole chain), trim, save.

All slots have the same length, so the saved sample divides into N equal slices: on a SLICE machine
with GRID = N, each slice is one slot. While N is set, the MEM line shows the length of the whole
chain (N × RLEN), with the stock mark when it does not fit in the 33 s of sample memory. At RLEN MAX,
or with N off, the recorder is stock.

⚠️ Chain Recording has not yet run on a unit. Everything below is read in the code (objdump, the
decompile of the `dt_1.54_emac` project) and checked in the emulator (`EmuChainRecord`,
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
| `vfunc_2` @`0x400a9784` | Keys. In state 0, YES arms (`FUN_400768a0`) and FUNC+YES records (`FUN_400768ce`); both clear the view's waveform cache. In state 1, FUNC+NO aborts. In state 2, YES stops. In state 4, the trim and save keys. NO in state 0 closes the page |
| `vfunc_4` @`0x400a8c94` | The draw. In state 0: the MEM line (`0x400a8e7c..0x400a8f10`, the length from `FUN_40076616` capped at the memory, and a mark when it does not fit), the two prompt lines (FUNC+YES at y `0x1d`, YES at y `0x16`, both centred at x `0x40`), and the E–H values and labels. In state 1: the ARMED line at y `0x1d` and the ABORT line. In state 2: the time recorded and the STOP line |
| `vfunc_11` @`0x400a7b68` | The update: reads the state and position through `FUN_40076b02`, extends the waveform cache (126 columns over the whole 33 s) up to the position, and redraws |
| `vfunc_17` @`0x400a7e38` | The encoders. In states 0 and 1: E sets RLEN (`FUN_400a786c`), F THR, G the source and H MON, tested in that order. A–D move the trim points in state 4 and do nothing in states 0 and 1 |

`SamplerLedView::vfunc_2` @`0x40036548` calls the same engine functions (ARM, REC, STOP, ABORT), so
Chain Recording applies to recordings started there too. That view draws no slot count.

## How this build does it

### The chain word

One 32-bit word at `0x439d1038` ([memory_map.md](../memory_map.md)): `0xC4A1` in bits 31..16, N in
bits 15..8, and k, the number of slots done, in bits 7..0. The word is above `.bss`, so start-up does
not clear it. The marker in the upper half tells a word this build wrote from whatever the memory
held: any other value means chain mode is off.

A chain is **in progress** when k > 0 and the write position is still exactly where the last slot
ended: position = LEN > 0. A stop, an abort and a new recording each move the position away from
LEN. Start-up clears both to 0, while the chain word may survive. Either way the next arm starts a
new chain. Chain mode is **off** when the word has no marker, when N = 0, or when RLEN is MAX.

### The hooks

| Site | Stock code | Now | Pad |
|---|---|---|---|
| `0x400767fc` (threshold hit) | `jsr FUN_40076616 ; movel %d0,LEN` | `jsr len_pad ; nop ; nop` | `len_pad` sets LEN = slot length + position. In stock the position is 0 there, so LEN is unchanged |
| `0x40076900` (REC) | the same | the same | `len_pad` |
| `0x400768c2` (ARM) | `clrl position` | `jsr arm_pad` | `arm_pad` keeps the position when a chain is in progress; otherwise it clears the position (stock) and sets k = 0. It keeps `%d0` (ARM's result) and `%d1` (the saved status register) |
| `0x400768fa` (REC) | `clrl position` | `jsr arm_pad` | `arm_pad` |
| `0x4007687a` (stop) | `lea 16(sp),sp ; bra FUN_40076540` | `jmp stop_pad ; nop` | `stop_pad`, below |
| `0x400a7f40` (`vfunc_17`, after the H test) | `tstb %d0 ; beqw exit` | `jmp enc_pad` | `enc_pad`: H takes the stock path. Encoder D (event id 4) in state 0 doubles or halves N: off → 4 → … → 64, held at 64, and below 4 off. One step per event, k = 0, then a redraw (`0x400c9a3a`). Anything else exits as stock |
| `0x400a8e7c` (MEM line) | `jsr FUN_40076616` | `jsr mem_pad` | `mem_pad`: the slot length, × N when chain mode is on |
| `0x400a8f48` and `0x400a8f60` (YES prompt) | `pea <prompt> ; pea 2` … `lea 32(sp),sp` | `jsr fmt_arm ; nop ; nop` … `lea 40(sp),sp` | `fmt_arm` pushes (N, k + 1) below the format and the alignment. The format is `YES: ARM %d/%d` when chain mode is on, else the stock string, which ignores the two values |
| `0x400a9026` and `0x400a9044` (ARMED line) | `pea <armed> ; pea 2` … `lea 28(sp),sp` | `jsr fmt_armed ; nop ; nop` … `lea 36(sp),sp` | `fmt_armed`: the same, with `ARMED %d/%d` |

`stop_pad` runs inside the audio ISR, where the block routine has already restored `%d2-%d4/%a2`. It
releases the routine's 16 B frame, then:

- If chain mode is off, or the slot ended short of LEN (the cap stopped it), it sets k = 0 and
  continues to the stock `FUN_40076540`.
- Otherwise it sets the position to LEN. This drops what the last block wrote past the slot's end;
  the next slot overwrites it.
- If that was slot N, it sets k = 0 and continues to `FUN_40076540`: the stock end of recording, with
  the recording exactly N slot lengths long.
- Otherwise it sets k + 1 and state 0 (idle), and returns as the block routine does.

No hook site is a branch target except `0x400767fc`, which is the first instruction of its
replacement (listing scan). No hook or pad lies in a protected range. The call rule holds: no direct
target of a patched instruction lies in a protected range ([update_moat.md](../update_moat.md)).

### Where the code lives

| Pad | Range used | Holds |
|---|---|---|
| the STL span's tail | `0x40177104..0x40177182` (126 B of 144) | `chain`, `chain_raw`/`chain_any` (the chain word's state), `stop_pad` |
| the POLY kit-load pad's tail | `0x400bf1cc..0x400bf1e0` (20 B of 28) | `len_pad` |
| the POLY machine-name pad's tail | `0x400c1062..0x400c107e` (28 B of 30) | `mem_pad` |
| the new pad `FUN_40124a6c` + `FUN_40124ac4` | `0x40124a6c..0x40124b28` (188 B of 198) | `enc_pad`, `fmt_arm`/`fmt_armed`, `arm_pad`; the rest keeps its fill |
| `.rodata` padding | `0x40252c00..0x40252c1b` (27 B) | `YES: ARM %d/%d\0ARMED %d/%d\0` |

The pads and their vetting: [landing_pads.md](../landing_pads.md). Every changed byte:
[docs/patch_listing.md](../../docs/patch_listing.md#chain-record).

### Source

The code is written in assembly, in [src/chain_record/](../../src/chain_record/):

- `chain_record.s` holds the hooks and the pads;
- `chain_record.ld` places each section at its hook site or pad;
- `make_chain.py` assembles the code, checks it, and writes the `chain_record` runs into
  `os/1.54/build/patch.json`.

`make_chain.py` makes these checks:

- every hook site still holds the stock bytes it displaces;
- every pad stays inside its range;
- no other feature's run is at a hook site;
- the build without Chain Recording, rebuilt from `patch.json`, has section 3 `5a7eb2a4…`.

`build.py` itself needs only `patch.json`.

```
M68K_PREFIX=m68k-linux-gnu- python3 os/1.54/src/chain_record/make_chain.py            # is patch.json up to date?
M68K_PREFIX=m68k-linux-gnu- python3 os/1.54/src/chain_record/make_chain.py --stages   # build the stage images
```

## What ends a chain

| Event | Result |
|---|---|
| slot N completes | the stock end of recording: normalise, trim, save; exactly N slot lengths |
| the STOP key during a slot | the stock end of recording, with the slots so far and the part of the current one; the next arm starts a new chain |
| ABORT (FUNC+NO) while armed | stock: everything recorded so far is dropped (position 0); the next arm starts a new chain |
| the 33 s cap during a slot | the stock end of recording, with what fits |
| encoder D while idle | a new N and k = 0: the next arm starts a new chain |
| start-up | position and LEN are cleared; the next arm starts a new chain. ⚠️ Whether the chain word itself survives a restart without a power cycle is not known; if it does, N is kept |

Not prevented: changing RLEN or the tempo between slots gives slots of different lengths, which no
longer divide evenly. Leaving the recorder page between slots keeps the chain (state 0, the
recording kept). ⚠️ Not traced: whether anything else writes the recording buffer while the page is
closed.

## Variant: auto re-arm

Built from the same source with `--defsym AUTOARM=1`: after a slot that is not the last, `stop_pad`
sets state 1 (armed) instead of 0, so the next hit records the next slot without a key press. It is
the stage image S5a only, not `patch.json`. Encoder D works only while idle, so it cannot change N
between slots in this variant. If the sound is still above the threshold when a slot ends, the next
slot starts on it at once. In the reference build the user arms each slot.

## Testing on the unit

No OS 1.54 image of this build has run on a unit yet. The port's stages come first:

| Stage | Contents |
|---|---|
| S0 `81d5a468` | the stock MAIN OS, repacked by the tool |
| S1 `5001c01f` | the fill test of the nine existing pads |
| S2 `6dec6823` | every feature of the port |

Chain Recording adds four stages, written by `make_chain.py --stages` to `out/1.54/stages/`. Each one
builds on the one before, so a unit can try one change at a time:

| Stage | `.syx` | Section 3 | Contents |
|---|---|---|---|
| S3 | `2c292d2e` | `b94200d3…` | S2 + the new pad `0x40124a6c..0x40124b32` filled with `clrl %d0 ; rts` (its fill test) |
| S4 | `a2501d97` | `7988ef5f…` | S3 + every hook, each pad only replaying the stock code it displaced |
| S5a | `e7e789b7` | `79a7fb1e…` | S3 + Chain Recording with auto re-arm |
| S5 | `688066c9` | `d90c19f6…` | S3 + Chain Recording: the reference build, `patch.json` |

What to check:

- **S3:** nothing changes anywhere.
- **S4:** the recorder behaves exactly as stock: arm, threshold, REC, stop, abort, trim, save,
  encoders E–H, and encoder D does nothing.
- **S5, chain mode off** (N off, or RLEN MAX): the recorder is stock.
- **S5, a chain:**
  - Turning D steps the prompt through `YES: ARM 1/4` … `1/64` and back to `YES: ARM`, and MEM shows N × RLEN.
  - Set N = 4 and RLEN to a few steps. Each YES shows `ARMED k/4`. Each hit records one slot and
    returns to `YES: ARM k+1/4`. After the fourth slot: normalising, trim, save.
  - On a SLICE machine with GRID 4, each slice should be one hit, starting at its attack.
  - FUNC+YES mid-chain records the next slot at once.
  - The STOP key, ABORT and a new N each start a new chain.
- **S5a:** the same, with no YES between slots.

## Related notes

- [landing_pads.md](../landing_pads.md): the new pad and its vetting.
- [memory_map.md](../memory_map.md): the chain word.
- [function_ledger.md](../function_ledger.md): the recorder's functions.
- [startup_hooks.md](../startup_hooks.md): none of these hooks runs at startup.
