# Section 2 map

## What this is

Section 2 of the OS image (the section the container calls "DSP", section id 2) is ColdFire code,
not code for a DSP chip: the Digitakt has none. It is a boot-time program that brings up the audio
hardware (the SSI codec link, eDMA, the DMA crossbar), runs self-tests with its own display and
encoder input, and loads code modules from the SPI NOR boot flash into SRAM. It is not the audio
renderer and does not run during playback. This build leaves section 2 byte-identical to stock
([update_moat.md](update_moat.md)); this note is a map for reading it.

## The blob

- 26,646 B of code after a 24-byte inner header `{0x6826, 0x80010000, 0x80000ec0, 0x03000900, 0, 0}`.
  Strip the header before import. The code runs from on-chip SRAM at the load base **`0x80000ec0`**
  (the header's third word): at that base 87 of its 97 `jsr` targets land inside the blob, against 0
  of 97 at `0x03000900` or `0x80010000`. ⚠️ `0x03000900` is read as a staging address, not where the
  code runs ([hardware.md](hardware.md)). All addresses in this note are load addresses.
- 132 functions when imported alone. None are named: section 2 has no RTTI.
- Where the image sections sit and how they are extracted: [firmware_image.md](firmware_image.md).

### The unified SRAM view

Imported alone, section 2 has 27 error bookmarks, because it calls into SRAM that other images
fill at run time. `scripts/build_sram_image.py` assembles the SRAM as it is at boot:

| SRAM | Contents |
|---|---|
| `0x80000000` | image 1: MAIN OS DDR `[0x40214000, 0x40217350)`, 13,136 B, almost all zero |
| `0x80000400` | section 4, the flash updater (32 KB) |
| `0x80000ec0` | section 2, 26,646 B, over image 1's tail |
| `0x80008000` | image 2: MAIN OS DDR `[0x40217350, 0x4021ea40)`, 30,448 B, non-zero in `0x8000c000`–`0x8000e663` |

Imported at `0x80000000` on the EMAC language (entry `0x80000ec0`) and curated with
`scripts/ghidra/CurateDsp.java` and `scripts/ghidra/FixDspResidual.java`, the unified project has
136 functions and **0 error bookmarks**. The recipe is in
[../scripts/ghidra_analyze.sh](../scripts/ghidra_analyze.sh) (`dt sram`).

- ⚠️ `CurateDsp.java`'s code window is `0x80000ec0`–`0x800076d6`. It marks everything else in SRAM
  as data, including the shared updater stub at `0x80000400`–`0x80000ec0` (below). A fresh import of
  the image with the updater layered in shows 27 auto-analysis phantoms before curation.
- ⛔ Ruled out: the error sites are EMAC decode gaps. They are real calls into SRAM that is filled at
  run time, plus phantom mid-instruction references that Ghidra's jump-table recovery lays over real
  indirect-call targets.
- ⛔ Ruled out: the `jsr` targets outside section 2 are phantoms because the SRAM there is zero in
  every static section. They are real routines (below); zero in the image is not zero at run time.

## What it drives

Across all 132 functions it touches 120 peripheral addresses (`0xFC…` / `0xFF…`), 186 SRAM addresses
(`0x8000xxxx`), a handful of DDR control words, and no broad sample-memory range. It is a hardware
driver that moves audio by DMA, not a math DSP.

| Base | Refs | Module (MCF54415) | Role here |
|---|---|---|---|
| `0xFC0B8xxx` | 43 | SSI0 (I²S/TDM audio) | The codec link. `+0xB4` = 48000 (48 kHz), `+0x24` = `0x1000100` (enable), `+0x6C & 8` = lock/ready, `+0xE8` = `0x40440000` (the DDR audio buffer), `+0xA8` = `0x41A00003` |
| `0xFC044xxx` / `0xFC045xxx` | 32 | eDMA (TCDs and channels) | The DMA that streams audio buffers to the SSI |
| `0xFC004xxx` | 12 | DMA crossbar / channel mux | 6 channels, priority `0x101`, routing `0x76543012` / `0x76543201` |
| `0xFC05Cxxx` | 6 | DSPI | The SPI NOR boot-flash controller (the loader below) |
| `0xFC0C8xxx` | 8 | ⚠️ SSI1 or a second eDMA group | Not examined |
| `0xFC03Cxxx` | 6 | ⚠️ DSPI0 | Not examined |
| `0xFC084xxx` | 2 | ⚠️ PIT / INTC | Timer / interrupt |
| `0xFC07xxxx` | 2 | ⚠️ | Not examined |
| `0xEC09xxxx` | — | ⚠️ A FlexBus-attached device | Control/status registers `0xec094060` / `68` / `6b` / `6c`, gated on `0xec090001 & 0x20` |

DDR addresses it touches, all control rather than sample streaming:

- `0x40000000`–`0x40000010` (vectors or a mailbox), `0x40000400` (the MAIN OS base), `0x40200000`,
  `0x40210000`, and `0x40440000` (the SSI audio buffer, through the DMA register above);
- `0x48000000`: the stack top and the handoff word. The init compares it with `0xB0B0DADA`.
- `0x4f4f2526`, `0x41a00003` and `0x4030003` look like addresses but are data.

## What it is

- **Audio bring-up.** SSI codec init at 48 kHz, eDMA, and the DMA crossbar.
- **Its own display and input.** Display drawing through FlexBus `0xEC07xxxx` with shared drawing
  primitives and a formatted-text draw; the only literal strings are `ENCODER_F` / `ENCODER_G`
  (`0x8000700d` / `0x80007017`), so it reads the encoders.
- **Self-test.** A 64 KB memory check with a progress bar (`FUN_80002eaa`), version strings
  (`FUN_8000578c`), and a status handshake (`FUN_800014da` → `0x8000765e`).
- **A boot loader.** `FUN_8000767c` loads a module from the NOR flash and runs it (below).
- It shares a low-SRAM boot library with the updater (below).
- ⚠️ Its SRAM overlaps the MAIN OS working RAM (the voice parameters at `0x80001502` and up), so it
  and the MAIN OS cannot run at the same time: section 2 runs at boot or in a special mode.
- ⚠️ Which it is — the normal power-on path, a held-key test/service mode, or an early boot stage —
  is open. Tracing `FUN_800014da`'s handoff tail would settle it
  ([open_questions.md](open_questions.md)).

## Loading code from the NOR flash

The board boots from a 16 MB S25FL128S SPI NOR flash on DSPI `0xfc05c000` ([hardware.md](hardware.md)).

- `FUN_8000758c(flash_addr, len, dst)` is the SPI read. Registers `+0x2c` = SR, `+0x34` = PUSHR,
  `+0x38` = POPR. It pushes a 4-byte address (with the SPI CONT bit set), then pops N bytes.
- `FUN_8000767c` calls `FUN_80005820(4, &0x8000f000)` to load a four-field descriptor at
  `0x8000f000`, reads a code module from `flash + 0x80000` with `FUN_8000758c`, and calls through the
  pointer at `0x8000f00c`.
- The loaded module holds `0x8000f010`, a read primitive `f(src, len, dst)` with 7 callers whose
  address is also taken. `FUN_800062cc` loops it as a keyed lookup over a table of 16-byte records.
  It is zero in every static section because it lives in the flash.

## The low-SRAM library shared with the updater

Section 2 calls real routines inside the updater's stub at `0x80000400`–`0x80000ec0`:

| Address | Role |
|---|---|
| `0x8000049a` | Cache and stack (re)init, plus FlexBus/GPIO setup. It also writes handoff-vector code addresses to `DAT_8000fffc`, near the top of SRAM |
| `0x800006c2` | A 1,044 B copy/utility routine |

So section 2 and section 4 are not mutually exclusive blobs; they share a boot-time library.

## Call-graph roots

⚠️ No static callers does not mean dead. Much of section 2 is reached through function pointers
(many indirect calls through `LAB_…` addresses) or installed as vectors, which the call graph cannot
see.

- **`FUN_800014da`** (301 B): the init / main (below). It ends in a jump Ghidra cannot follow, shown
  as `halt_unimplemented()`.
- `FUN_80002b9e` (572 B, 7 callees, 18 indirect calls): role not traced.
- `FUN_800058f8` (5 callees), `FUN_8000767c` and `FUN_800062cc` (2 callees each).
- About 50 leaf roots with no callees, mostly small: ⚠️ likely indirectly called handlers.

## Function map: the audio hardware

| Address | Role | Evidence |
|---|---|---|
| `FUN_800014da` | Section init / main: the DMA crossbar (`0xfc004xxx`), FlexBus (`0xec09xxxx`), the SSI init `FUN_800011c0`, and a codec-probe cascade through the indirect `LAB_80001380(idx)` over slots {2, 9, 10, 0xb, 0x22, 1, 0x1a, 0x1b, 0x21, 0x23}. Writes the boot status to `0x8000765e` and checks `0xB0B0DADA` at `0x48000000` | decompiled |
| `FUN_800011c0` | SSI0 full bring-up: about 43 codec registers; 48 kHz (`+0xB4`); DMA buffer `0x40440000` (`+0xE8`); polls lock (`+0x6C & 8`); enable (`+0x24` = `0x1000100`); waits about 1000 delay loops | decompiled |
| `FUN_800011c6` | SSI0 re-init: a near-identical copy of `FUN_800011c0` (codec re-sync). Called by `FUN_80002e5c` and `FUN_800016a6` | decompiled |
| `FUN_800012f6` | SSI partial re-configure: the tail registers (`0xfc0b8060` and up) plus enable. Called by `FUN_80001608` | decompiled |
| `FUN_800013b0` | SSI minimal kick: writes enable (`+0x24`), then waits for lock. Called by `FUN_80002902` and `FUN_80002b9e` | decompiled |
| `FUN_8000116e(n)` | ⚠️ Busy-delay (spins n times): the wait in the SSI lock loops | call pattern |
| `FUN_80005a42`, `FUN_80005c76` | ⚠️ eDMA setup (they touch the `0xfc044xxx` / `0xfc045xxx` TCDs): the channels that feed the SSI | references |
| `0x8000765e`, `0x8000765a` | Boot status / handshake words, written by the init with result codes | decompiled |

### The boot handshake

`FUN_800014da` writes result codes to `0x8000765e` (`0xC0180000`, `0x423C3`, `0x23E3`, `0x823C3`,
`0x23E3`) and to `0x8000765a`, depending on the codec-probe outcomes. ⚠️ A status word for whatever
runs after section 2 to read.

## Function clusters

131 of the 132 functions, classified by the memory they touch:

| Cluster | Functions | Role |
|---|---|---|
| SSI codec | `800011c0`, `800011c6`, `800012f6`, `800013b0` | Codec bring-up (48 kHz, lock wait, enable) |
| eDMA / DMA | `80005a42` (433 B), `80005c76`, `80005c04`, `80005bf8` | DMA channels that stream audio to the SSI |
| DMA crossbar | inside `800014da` | Channel priority and routing (`0xfc004xxx`) |
| Display | FlexBus `0xec07xxxx`; primitives `80001b5e` (10 callers), `80001a9e` (6), `80001b84` (7), `80001ad4` (5), `800017b0`, `80001796`; format/draw `LAB_80001c62`, `8000177e` | Screen drawing |
| DSPI (SPI bus) | the `0x80003xxx` cluster: `80003492`, `80003956`, `8000758c`, `80003650`, `80003590`, `8000343e`, `800033fc`, `80005dc8`, `80005d48`, `80005c1c` | SPI to an external chip, including the NOR read |
| FlexBus I/O (`0xec09xxxx`) | `80004946` (628 B, the largest), `80004754` (498 B), `800016a6`, `80001608`, `8000616a`, `800061e6` | Communication with a FlexBus device (mailbox `0x40000000`, `0x48000000`) |
| Application logic (SRAM only, function-pointer dispatch) | `80002b9e` (572 B, 18 indirect), `80002902` (564 B), `8000424e` (516 B), `800050d8` (452 B), `8000277a` (382 B), `80000f46`, `80001e5c`, `8000240a` | The program's state machines and command processing |
| Boot / self-test | `800014da` (init/main), `80002eaa` (64 KB check and progress bar), `80002e5c`, `8000578c` (version), `80002b72` (command processor) | Power-on self-test, bring-up, handoff |
| Delay / utility | `8000116e` (busy-delay), `80005810` and its thunk, `800058a0` | Primitives |

Not yet documented body by body: the application-logic hubs `FUN_80002b9e` and `FUN_80002902`, the
FlexBus I/O functions `FUN_80004946` and `FUN_80004754`, the DSPI protocol details, and the ~50 leaf
helpers.

## Generated table (largest first)

Columns: address, size, callers, callees, indirect calls, halts (H), peripheral modules, DDR
addresses. Regenerate with `SECTION=dsp ./scripts/ghidra_decompile.sh dt 're:.*'`, then
`python3 scripts/emu/dspmap.py`.

```
addr         sz clr cle ind h modules                dram
80004946    628   5   2   1 . FlexBus.io,INTC/DMA,periph 40000010,48000000
80002b9e    572   0   7  18 .                        (app-logic hub)
80002902    564   1   7  17 .                        (app-logic hub)
8000424e    516   0   2   0 .                        (app-logic)
80004754    498   1   6  20 . FlexBus.io,INTC/DMA,periph 40000000-10 (comm)
800050d8    452   1   1   0 .                        (app-logic)
80005a42    433   0   0   0 . DMAreq,FlexBus.io,SSI1?,eDMA  (audio DMA setup)
8000277a    382   1   6   9 .                        (app-logic)
80002eaa    339   1   5  35 .                        40200000/40210000 (mem check+UI)
800011c6    302   2   0   0 . SSI(audio)             40440000 (codec re-init)
800014da    301   0   9  35 H DMAxbar,FlexBus.io     48000000 (INIT/MAIN)
80003492    254   1   2   0 . DSPI                   (SPI)
```

## Ruled out

- ⛔ **Section 2 is the resident audio renderer.** Wrong: it does not run during playback; the render,
  including slice selection and the buffer fill, is MAIN OS code ([render_path.md](render_path.md)).
- ⛔ **Section 2 cannot reach sample memory because it references almost no DDR.** Wrong: a reference
  query cannot see DMA: section 2 writes buffer addresses into peripheral registers as data, and the
  CPU never loads samples.
