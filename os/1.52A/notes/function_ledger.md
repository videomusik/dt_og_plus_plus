# Function ledger — Digitakt OS 1.52A MAIN OS

## What this is / how to read it

This is the per-function ledger for the MAIN OS section of Digitakt OS 1.52A. Each row pairs an
address with what the function or data object does, how that is known, and how sure it is. It is
the raw material for naming: only about a third of MAIN OS functions (31.4 % in the first-pass
import) get real names from the RTTI walk, and the voice engine and audio renderer have no vtables,
so they never name themselves. Ghidra renames made inside one project do not survive a fresh import;
this file does.

**Conventions**

- **Addresses** are MAIN OS (section 3) load addresses; file offset = load − `0x40000400`. Section 2
  addresses (base `0x80000ec0`) are marked where they appear; its functions are mapped in
  [section2_map.md](section2_map.md).
- **Names.** `FUN_`, `DAT_`, `LAB_`, `entry_` and `switchD_` are Ghidra auto-names, i.e. addresses
  with no known name. A fresh import may not start a function at exactly the same place.
  `Class::vfunc_N` names come from the RTTI walk (C++ classes with vtables).
- **Confidence marks.** ✅ has two meanings, and each row says which. With "confirmed on the test
  unit", it was checked on the device. Without it, it was read directly in the code, and
  the evidence column names how (decompiled, objdump, hexdump, reference scan). ⚠️ is an inference,
  plausible but unconfirmed; the row says what it rests on. ⛔ means checked and ruled out, or never
  do. A row with no mark records what the code shows, read by the method in its evidence column, with
  no further claim. "Confirmed on the test unit" means one Digitakt running OS 1.52A; it says nothing
  about other units or OS versions. The marks are explained for all notes in [notes/README.md](../../../notes/README.md#confidence-marks).
- **Line numbers** ("line N") next to the audio ISR (`FUN_40077120`) are line numbers in Ghidra's
  decompiler output for that function. They move with Ghidra version and settings: use them to find
  the spot, then confirm by address.
- **Voice vs track.** There are 8 audio voices. In stock firmware voice *n* always plays audio track
  *n*, so the per-voice arrays below look per-track. With the POLY voice pool the ISR can send a
  track's trig to another voice of its pool, and these arrays are then indexed by that voice
  ([voice allocation](features/voice_allocation.md)).
- ⛔ **Section 2 is not the audio renderer.** It is the boot-time audio-hardware layer (SSI codec at
  `0xFC0B8000` plus eDMA). The resident renderer is MAIN OS code: the audio ISR `FUN_40077120`, its
  pipeline `FUN_40074e84` / `FUN_400754fe` and the DSP stages it calls at `0x400713c0`–`0x4007442a`
  ([render_path.md](render_path.md)). Writes into SRAM below are read by that MAIN OS code.
- **Where the analysis comes from.** A Ghidra import of section 3 with the EMAC extension
  (`dt_1.52A_emac`), and a copy with code seeded at every undefined range in the code windows
  (`dt_1.52A_seed`, 99.91 % of the code region disassembled), plus a separate import of section 2 in
  its real SRAM address space (`dt_1.52A_sram`). How to rebuild them:
  [analysis_reference.md](analysis_reference.md#the-ghidra-projects) and [docs/toolchain.md](../../../docs/toolchain.md). The
  dead-function scan over the seeded import lists 980 candidates (81,858 B); only 204 of them
  (10,186 B) are leaf functions of 16 B or more, and even those are candidates to read, not free
  space: most "dead" code is reached through dispatch ([landing_pads.md](landing_pads.md)).


**How to add a row**

- Grep this ledger for the *behaviour* you are looking for before you trace, not only for an address
  you already have.
- One row per function, address first, so a grep for the bare hex finds it. Then what it does, then
  the evidence (decompiled, objdump, call pattern, strings, reference query, device), with a
  confidence mark.
- Record how you know, not only what you concluded; the evidence is what lets the next reader
  re-verify instead of re-deriving.
- Keep negative results as ⛔ rows. "Checked, it is not X" stops the next reader walking the same
  path.
- A correction edits the existing row. Never add a second row for the same function.
- Build code placed in a pad is a function of its own: it gets its own row, named by its role (for
  example **voice allocator** `0x4015cb8a`), and the pad keeps the row for the stock code it overwrote.
- Facts that span several functions (data layouts, index schemes) get a short section of their own.

## Startup, crt0 and the RTOS

| address | what it does | evidence |
|---|---|---|
| `0x400004e8` | ✅ **Entry point**, the first header word: `move.w #$2700,SR`, then the init chain | code read; [memory_map.md](memory_map.md) |
| `entry_40001c76` / `entry_40001c94` | ✅ Vector-table setup, VBR = `0x40000000` | crt0 trace |
| `entry_40001ecc` | ✅ Peripheral/interrupt init; sets a second stack with `move.l #0x405d07f4,sp` (⚠️ interrupt/supervisor stack, inference) | crt0 trace |
| `entry_4000045c` | ✅ **SRAM initialiser**: two copy+zero pairs fill both 32 KB SRAM banks from the image tail. The source of the whole SRAM map | decompiled |
| `FUN_400004b2` | ✅ **`.bss` clear**: zero-fills `0x377c2a` × 16 B from `0x40214000`. ⛔ Data a patch places at or above `0x40214000` does not survive at that DDR address: the SRAM-init images there are copied out first (`entry_4000045c`), then the whole range is zeroed. New DDR-resident data must go below it | decompiled; [memory_map.md](memory_map.md) |
| `0x40001536` | ✅ Default exception handler (`move #0x2700,sr`). ⛔ Ghidra flags it unreferenced because only the vector table points at it: unreferenced is not dead | code read |
| `FUN_40120b5a` | ✅ **App entry, end of crt0.** Creates the idle-bootstrap task, installs the scheduler `0x40001566` (TRAP #0, vecs `0x0`/`0x60`/`0x100`/`0x200`), starts the RTOS tick (`FUN_40001574`: PIT `0xfc080000`, vector `0x40000410`), then `trap #0` into the scheduler | decompiled |
| `FUN_400015ac(tcb, entry, prio, stack, size)` | ✅ **RTOS task_create**: builds an initial stack frame (entry + SR `0x407c2000` + exit-return `LAB_40001628`) so the scheduler can switch to it. 16 call sites, one per task: 15 in functions plus one in the bootstrap code `LAB_40068b92`, for the 16 tasks of the task table ([architecture.md](architecture.md)) | decompiled; objdump call-site scan |
| `FUN_400015f8(tcb)` | ✅ RTOS task start / make ready | decompiled |
| `0x40001566` | ✅ Scheduler / TRAP #0 context switch (installed by `FUN_40120b5a`) | crt0 trace |
| `FUN_401218cc` / `FUN_4015bec0` | ✅ Task-object dispatch wrappers (refcount, then call the object's vtable method), so many tasks are C++ worker objects | decompiled |
| `FUN_40068c42` | ✅ **Main app-init task** (prio 1, ~56 callees): initialises all subsystems and spawns the worker tasks | decompiled |
| `FUN_4006753e` (5,592 B to its last instruction at `0x40068b12`; Ghidra's body count is 5,574 B) | ✅ **Host-comm / factory-test / sample-transfer task** (prio 2): `#HELLO`, `#SAMPLE_UPLOAD`, `#DUMP_AUDIO`, `#RECEIVE_AUDIO`, `#RECORD_START`; CODEC/MMC/DRAM/SUPERCAP self-tests; `CPU4101`, the same protocol as the section-4 updater ([section 4](#section-4-updater-the-factory-bring-up-and-flash-programmer)). It allocates the 2 MB buffers through `FUN_400c408c` (freed with `FUN_400c4292`), zeroes **512 KB from `0x40225e70`**, keeps its heap pointer in `DAT_4021d400` and uses small structures at `0x40225404`+ | strings + task trace; decompiled |
| `FUN_4000ae46` | ✅ **The Brain message pump**: an RTOS task (prio 6, 160 KB stack) on queue `0x405d1c58`. `case 5` at `0x4000b3aa` calls `TimerManager::tick`; that message is posted at 30 Hz by the DTIM3 ISR `0x4005ef0c` (see "Pool-cursor hook sites and the 30 Hz UI clock"). Its strings tie it to maintenance mode, factory reset and the MMC caches ("MAINTENANCE MODE", "Factory reset", "Update MMC Caches") | strings + objdump |
| `FUN_400d0578` | ✅ **The MIDI-out task** (prio 8), part of the `0x400d0xxx` cluster. The audio ISR posts the MIDI evaluator's type-3 events to its queue `0x42175468`; it gates them, appends the note bytes (`FUN_400d0396`) and sends them through `FUN_400cfd26` to DIN (`FUN_400028c6`) or USB (`FUN_40004a02`). Its three gates and the scheduled note-offs are in "The MIDI-track send chain" | task trace + objdump |
| full task table | See [architecture.md](architecture.md) (RTOS, boot and task table) | — |

## USB/DMA descriptor-ring driver

⛔ SRAM `0x80008000`–`0x8000bf80` is not free: this cluster owns it. It builds its rings from
immediates (`addi.l #imm`), which reference queries do not see, so the region looks unreferenced.
Scan instruction operands for address literals before calling any region unused
([memory_map.md](memory_map.md)).

| address | what it does | evidence |
|---|---|---|
| `FUN_40002f30` | ✅ Builds a 32 × `0x40` descriptor ring at `0x80008000`; writes the sentinel `0xdead0001` | decompiled |
| `FUN_400031f4` | ✅ Builds 32 × `0x170` buffers at `0x80008c00`; length/flag word `0x1700080`; page-aligns with `& 0xfffff000` | decompiled |
| `FUN_4000352a` | ✅ Builds a 16 × `0x40` ring at `0x80008800` and 16 × `0x58` buffers at `0x8000ba00`; flag word `0x580080` | decompiled |
| `FUN_40005d48(queue_id, desc)` | ⚠️ Submit a descriptor to a queue; called at the end of each ring build | call pattern |
| `FUN_400032fc`, `FUN_40003664`, `FUN_40003970`, `FUN_40003984`, `FUN_40003998`, `FUN_40003b6e`, `FUN_40003c46`, `FUN_40003e04`, `FUN_40003fd2` | ⚠️ Same cluster: all build addresses into `0x80008000`–`0x8000ba00` | address-literal scan |
| `FUN_40004e44` | ✅ Driver tick: reads a peripheral timer at `0xfc0b014c`, touches low-`.bss` scalars from `0x40214184` | decompiled |

## Storage drivers: SPI NOR and eMMC

| address | what it does | evidence |
|---|---|---|
| `0x400d7fc8..0x400d98f6`, the driver module ([update_moat.md](update_moat.md); three functions in `0x400d870a..0x400d8a7a` are not in Ghidra's function table) | ✅ **SPI-NOR flash driver** on DSPI `0xfc05c000` (SR `+0x2c`, PUSHR `+0x34`, POPR `+0x38`); read/write/erase command set, many small functions that each send one opcode. The core transfer primitive `FUN_400d85b0` (31 refs, 13 PUSHR) is called by `FUN_4006753e`, so the NOR path is driven by host-comm/transfer | 314 DSPI refs, decompiled |
| `FUN_40068be0` | ✅ DSPI init: MCR `+0x00`, CTAR `+0x0c` for the NOR controller `0xfc05c000` | decompiled |
| `0x4008bxxx` (`FUN_4008b600`, `FUN_4008b722`) | ✅ **eMMC driver**, controller `0xfc0cc000` = eSDHC (128 refs; registers `+0x04/+0x08/+0x0c/+0x24/+0x2c/+0x30/+0x44`). `FUN_4008b722` is a bulk DDR↔eMMC transfer: copies 3.45 MB in 32 KB chunks via eDMA `0xfc045760`+, also touches NOR, has a `while(true)` halt path. It is the boot/OS/sample bulk load. ⛔ Not the per-change project writer | decompiled |

## Serialization and persistence

Project state, saved and working, is written to eMMC, not NOR, LZ4-compressed. There is an MMC/file
stream writer but no NOR/flash stream writer.

| class / address | what it does | evidence |
|---|---|---|
| `ValueWithMirror<live_t, StorageV_N_t>` | ✅ **The data model.** Each entity has a live form and a versioned storage form; virtual functions serialize and migrate. Seen: `trackStorage_v5_t`, `kitStorage_v9_t` (as `ArrayValueWithMirror…kitStorage_v9_t_128_` = 128 slots), `fxSetupStorage_v3_t`, `soundStorageContainer_t`, `patternKitStorageContainer_t` | RTTI names |
| `Elektron::AbstractOutputStream` → `Lz4StreamCompressor` → `BufferedStreamWriter` → `MmcStreamWriter` / `SafeMmcStreamWriter` | ✅ **The write pipeline**: serialize → LZ4 compress → buffer → eMMC via eSDHC. `MmcStreamWriter::write_impl` at `0x40193d64`; `SafeMmcStreamWriter` vtable at `0x40193dc0`, `vfunc_2` at `0x4008ab32` (860 B, crash-safe). Also `MemoryStream*`, `FileOutputStream`, `BackupFile{Export,Import}Adapter` | RTTI + decompiled |
| `Project` / `ProjectCache` / `PlusDriveFormatMenuView` | ✅ Project container and cache; `PlusDriveFormatMenuView` shows that +Drive is the eMMC. `ProjectCache` extends `Observer` (`ProjectCache::vfunc_0` at `0x40146b50` chains `Observer::vfunc_0`), so persistence is change-driven: the `ValueWithMirror` model notifies the cache on edits, and `SafeMmcStreamWriter` is the power-safe working-state writer. ⚠️ Open: whether the eMMC flush happens per change or is debounced ([open_questions.md](open_questions.md)) | RTTI + decompiled |
| `DigitaktSysex` destructor `0x40080868` (deleting destructor `0x4008088c`), `SysexSendMenuView::vfunc_4` at `0x4005bea0` | ✅ `0x40080868` and `0x4008088c` are the two slots of the `DigitaktSysex` vtable at `0x40192c54` (typeinfo `0x40192c38`): the destructor, and the deleting destructor, which calls it and then tail-jumps to `0x400c4314` with the object. The destructor is in the protected set ([update_moat.md](update_moat.md)). ⚠️ Otherwise located only: the SysEx envelope / 8-in-7 layer ([sysex_dump.md](sysex_dump.md)). The dump body builders are below | RTTI names; vtable words + objdump |
| `FUN_40009182` (`0x40009182..0x40009710`) | ⚠️ Most likely **`Brain::processSysexDumpReceivedMessage`**, the receiver for kit, sound and pattern dumps. It builds the `std::function` of that method's lambda (at `0x400095c0` it loads the lambda's manager `0x40007fe4`, which returns the lambda's typeinfo `0x40162bbc`) and prints the "Received KIT/SOUND/PATTERN" strings. It calls the kit voice build `FUN_40076fe6` at `0x40009358`. Not on the OS-update path ([update_moat.md](update_moat.md)) | strings + objdump + pointer-word scan |

### Pattern and kit serializer (the SysEx dump body)

A pattern dump body is `0x6c00` (27,648 B) before 8-in-7 and the envelope: the pattern (`0x6200`)
followed by its kit (`0xa00`). The pattern name sits at body `+0x6194`, the kit name at `+0x6204`.
Full byte map: [pattern_layout.md](pattern_layout.md).

The dump file carries the runtime form: an empty p-lock lane is `ff ff` plus zero fill; an occupied
lane is `[code][track]` (track 0–15, plain) followed by 64 × u16; an unlocked step is `0xffff`; a
locked step is the 8.8 value. ✅ Checked against a single-pattern dump and a full 128-pattern project
dump: every empty lane is `ff ff`, none is `7f 7f`, and every version word is `0x00000009`.

⛔ Ruled out: decoding Elektron 8-in-7 LSB-first. It is MSB-first (the first data byte's high bit is
header bit 6). The LSB-first error flips only each byte's MSB, so trig bit `0x0200` and ASCII names
still decode and hide it, while the lane headers turn into a fake second "`7f 7f`" form. The tell is
a version word that reads `00 80 00 09` instead of `00 00 00 09`.

⚠️ There are two p-lock serialization paths. Besides the SysEx one, a
`ValueWithMirror<patternParamLocks_t, patternParamLocksStorage_v0_t>` is a member of `Pattern`
(`vfunc_9` ← `Pattern::vfunc_9`): that is the eMMC persistence form (ValueWithMirror → LZ4 →
`MmcStreamWriter`). Its on-disk byte layout has not been seen.

| address | what it does | evidence |
|---|---|---|
| `FUN_400811be` (324 B) | ✅ **Pattern dump body builder.** Builds the pattern (`FUN_4007a61a`) and kit (`FUN_4007a0ac`), copies `[0x6200]` + `[0xa00]` = `0x6c00`, and hands it to the encoder `FUN_40080dc2` → `FUN_40080bd0` → `FUN_400f4e22` (8-in-7, no transform). This is the dump-to-file path. `FUN_40080f9a` is the single-pattern (`0x6200`) variant | disassembly (dataflow) |
| `FUN_4007a61a` (140 B) | ✅ **Pattern serialize**: writes 9 at `+0`; 16 × `FUN_4007a30e` track blocks from `+4` (stride `0x38f`); `FUN_4007a55e(dst + 0x38f4, …)` builds the `ff ff`-keyed p-lock pool at `+0x38f4`; copies the `0x24`-byte name/trailer to `+0x6194`; total `0x6200` | decompiled |
| `FUN_4007a30e` (288 B) | ✅ **Track serialize.** The `0x38f` block is a structure of arrays: `[64 × u16 at 0x00]` + 12 × 64-byte per-step arrays from `0x80`, copied from the runtime block in a fixed permutation (e.g. dst `0x80` ← src `0x280`), + a 14-byte per-track trailer at `0x380`. The loop is gated per step by a 64-bit active-step mask (`FUN_401119d4`) | decompiled |
| `FUN_4007a0ac` (224 B) | ✅ **Kit serialize** into the `0xa00` block: writes 9, the name, then 8 sounds (`FUN_40079fa6`, runtime stride `0xa2` = 162 B per sound), the FX block (`FUN_40079d9a`), 8 × `FUN_40079e44` (stride `0x70`) and a 2 × u16 tail | decompiled |
| `FUN_40079fa6` (262 B) | ✅ **Sound serialize** into the ~`0xa0` sound blob: magic `0xbeefbace`, sound-blob version 2, the name, then the value array copied **code-keyed**, `dst[(DAT_401923c0[slot] + 0xe) * 2] = src_sound[0x14 + slot * 2]`, for slots 0–45 only (loop bound `0x5c`; slots 46–52 are master-only and not serialized). Machine byte `src + 0x7e` → `dst + 0x7c`; sample/loop state via `FUN_40079f58` / `FUN_40079aec`. The destination value region is `0x60` B and codes `0x00`–`0x2d` fill `dst + 0x1c`…`+0x77`, leaving about 2 spare code slots before the machine byte. Also called by `MmcFs::vfunc_13`, so the same blob feeds eMMC persistence | decompiled |
| `FUN_40079e44` (86 B) | ⚠️ **Kit-tail block serialize**, one of 8 × `0x70` blocks after the FX (`kit + 0x59a + n * 0x70`). Copies code-keyed via `DAT_40192118`…`401921bc` (41 entries) into `dst[(code + 2) * 2]`, plus `src[0x37]`; version 1. The 41 codes are sound codes `0x00`–`0x24` (slots 0–36) and `0x25`–`0x28` (Amp 38–41): a sound subset that leaves out the AMP mix (`0x29`–`0x2c`, sends/pan/volume) and the SRR routing (`0x2d`), so these are not full Sounds. In a real kit dump the 8 blocks hold near-default, sound-like values that differ from the kit's 8 main sounds (at code `0x04`) and from each other. Serialized as a ValueWithMirror entity (`FUN_4000e0f4` fetches it via vtable `+0x28`). ⚠️ Purpose unidentified: nothing found yet reads `kit + 0x59a`, and a pointer+offset access is invisible to reference queries ([open_questions.md](open_questions.md)) | decompiled |
| `FUN_40079d9a` (74 B) | ✅ **FX/mixer serialize**: the kit's single FX block (`kit + 0x530`), code-keyed via `DAT_40192260`…`40192330` (52 entries) into `dst[(code + 2) * 2]`; version 3 = `fxSetupStorage_v3_t` (delay, reverb, compressor, master mixer) | decompiled |
| `FUN_40079de4` (96 B) | ✅ **`0x70`-block deserialize**, the reverse of `FUN_40079e44`: version gate `stored[0] == 1`, code-keyed via the reverse table `DAT_401921bc`…`40192260` (41) into `runtime_block[code * 2]`, plus `[0x6c]` ← `stored[0x19]`. Destination `kit + 0x59a + n * 0x70` | decompiled |

### Kit load and the Project object

| address | what it does | evidence |
|---|---|---|
| `FUN_4007aaba` (320 B) | ✅ **Kit deserialize** (stored → runtime; reverse of `FUN_4007a0ac`). Gives the runtime kit layout: name at `+0x00` (16 B, default "CLEAR KIT", `s_CLEAR_KIT_401a6644`); 8 track LEVELs at `+0x10` (8 × u16, values above `0x7f00` become `0x6400` = 100); 8 sounds at `+0x20`, stride `0xa2` (`FUN_4007a902` deserializes each, `FUN_40084062` default-inits per track); FX at `+0x530` (`FUN_40079d42`); 8 × `0x70` blocks at `+0x59a` (`FUN_40079de4`, default-init `FUN_40083858`); 2 × u16 tail at `+0x91a` / `+0x91c` (a value outside 0–`0x3ff` / 0–`0x1ff` becomes −1). Caller: `FUN_4007abfa` | decompiled |
| `FUN_4007a902` | ✅ **Per-sound deserialize.** Copies the machine byte `stored + 0x7c` → `runtime + 0x7e` through a clamp at `0x4007a994` (`moveq #5,%d2` …), `runtime[0x7e] = (machine + 1 < 5) ? machine : 0`: stock keeps 0–3 and the `0xff` "no sound" value (−1) and turns anything else into 0. The serializer `FUN_40079fa6` has no such check (`dst[0x7c] = src[0x7e]`), so the byte is saved as it is and only the reload clamps it. In this build (POLY voice pool) the clamp is `moveq #6` (`7405` → `7406`), so machine 4 survives a project reload. A sibling clamp at `0x4007a9d6` (`moveq #3`, the `+0x80` field) is not machine-related and is left alone. ⚠️ By the same clamp, stock firmware would turn a saved POLY track back into machine 0 (inferred from the code, not tested on stock firmware; [compatibility.md](compatibility.md)) | objdump + decompiled; ✅ confirmed on the test unit (POLY survives a project save and reload) |
| `FUN_4001b3e8` (530 B) | ✅ **Reload/load a kit from +Drive** ([FUNC]+reload; strings "Track sound reloaded", "Params reloaded", "Reload Error"). Reads `0xa00` B from eMMC at block `(kit * 0xa00 + 0x310200) >> 9` (`FUN_400d30fa`): the eMMC kit store has base `0x310200`, stride `0xa00`, 128 kits. `FUN_4007abfa` → `FUN_4007aaba` deserializes, then the result is copied into the runtime kit array at `[Project → vtable+0x28 base] + 0xf6343c`, 128 kits × `0x91e` (track sound at `+track * 0xa2`, value array at `+0x14`; the single-parameter path uses u16 strides `0x48f` per kit and `0x51` per track from u16 index `0x7b1a28`). `param_1` holds `ArrayValueWithMirror<kit_t,kitStorage_v9_t>[128]` at `+0x1d`. Callers: the `TrackSelectionView` / `SamplePageView` reload paths | decompiled |
| `FUN_4012198c` | ✅ **Get-current-Project singleton** (424 callers). Lazily allocates the Project C++ object (`0x39ff8` = 237 KB), constructs it with `FUN_400183fa` (vtable at `0x401661e8`; lays out a 128 × `0x64c` pattern-manager array at `+0xa84`), caches the pointer in `_DAT_421b9dc8` (`0x421b9dc8`) and returns it | decompiled |
| `Value_Digitakt::project_t_::vfunc_10` at `0x40127b02` | ✅ **Base-region getter** (Project vtable index 10): returns `*(project + 0x10)`, the live `project_t` data region (`ValueWithMirror<project_t, projectStorage_v14_t>`). RAM chain to the live kits: `base = *(*(0x421b9dc8) + 0x10)`; 128-kit array at `base + 0xf6343c` (× `0x91e`). ⚠️ The 128 patterns precede it (a runtime pattern is about `0x1ec6d` B, matching the sequencer's `pat + 0x1ec5x` fields). `project_t` is heap-allocated, so there is no fixed address, but this chain finds it | decompiled |

### Parameter locks

| address | what it does | evidence |
|---|---|---|
| `FUN_4007a6a6` (272 B) | ✅ **Set/allocate one p-lock lane (param, track)**: walks the 80-lane pool (stride `0x82`), reuses or allocates a lane and copies that parameter's 64 step values from the runtime table. Args `(pool, runtime_tbl, 0, param_idx ≤ 0x34, track ≤ 0xf)` | decompiled |
| `FUN_4007a55e` (188 B) | ✅ **Full p-lock rebuild**: clears all 80 lanes to `ff ff`, then scans every (param 0–52, track 0–15) lock flag at `runtime + track * 0x1b35 + 0x1b00 + param` and allocates a lane per set flag (at most 80, guard `< 0x4f`). A runtime lane is `[code:1][track:1][64 × u16]` = 130 B | decompiled |
| `DAT_401923c0` | ✅ **param_idx → lane code table** (53 records × 4, code = byte `+3`): LFO1 = odd `0x01`–`0x0f`, LFO2 = even `0x02`–`0x0e` (interleaved), then contiguous `0x10`–`0x2d` | read from image |
| `PatternParamLocks::vfunc_17` at `0x40013bd8` | ✅ Observer apply-on-change: reads `PatternParamLocksParamChangedInfo` (param at `+4`, track at `+8`); param < 0 or track > 15 → full rebuild (`FUN_4007a55e`), otherwise an incremental set (`FUN_4007a6a6`); a full pool shows "Lock mem full!" | decompiled |
| runtime p-lock table | ✅ The p-lock source (getter at `PatternParamLocks` vtable `+0x28`). Per-track stride `0x1b35`: 64 steps × `0x6c` (108) B of parameter values (54 × u16), then 53 lock-flag bytes at `+0x1b00`. It starts right after the 16 × `0x38f` trig blocks (`pat + 0x38f0`); `FUN_4006f16e` / `FUN_4006f4fa` read it | decompiled |

## Runtime parameter system

The chain, end to end:

1. **Descriptor table `0x4018ff88`**: static metadata, 164 records × `0x34`. `smalls[0]` = page /
   category, `smalls[1]` = value index, then max / default / CC / id and three name pointers (full,
   category, short). It is also the key from a p-lock lane to its parameter. Per-page tables:
   [parameters.md](parameters.md).
2. **`FUN_40078808`** builds the reverse tables at boot.
3. **`FUN_40078c2c(slot, machine)`** resolves (machine, SRC slot) → parameter id.
4. The per-sound **u16 value array at `sound + 0x14`**, indexed by slot.
5. **`FUN_40076ee8`** writes a value into the SRAM parameter mirror.

| address | what it does | evidence |
|---|---|---|
| `FUN_40078808` | ✅ **Boot-time index builder**: walks all 164 descriptors and fills `0x419607a0` (machine-independent slot → id) and `0x41960874` (8 SRC slots × 4 machine types → id). It clears `0x80` B for the latter = 32 entries, confirming the 8 × 4 shape | decompiled |
| `FUN_40078c2c(slot, machine)` | ✅ **The machine dispatch for parameters.** `slot < 0x35`; slots `0x11`–`0x18` are the 8 SRC slots → `0x41960874 + ((slot − 0x11) + machine * 8) * 4`; all other slots → `0x419607a0 + slot * 4`. This is why SLICE Select and Sample STRT are the same physical slot (`0x15`), reinterpreted per machine. Bounded: `machine < 4`, otherwise it returns 0, so an unknown machine byte gives a null parameter, not an out-of-bounds read ([compatibility.md](compatibility.md)) | decompiled |
| `FUN_40078cc2` | ⚠️ Page-order / enumeration list reader (bound `<= 0xce`) | code read (bound only) |
| `FUN_400152fc` | ✅ **Observer callback for sound-parameter changes**: dynamic-casts the change info (`SoundParamChangedInfo` / `MultipleSoundParamsChangedInfo` / `SoundConfigChangedInfo`) and pushes each changed value to the engine via `FUN_40076ee8`, reading from `sound + 0x14 + slot * 2` | decompiled |
| `FUN_40076ee8(value, track, slot)` (94 B) | ✅ **Engine parameter setter, the live knob-turn push.** Writes the per-voice u16 mirror, `*(u16 *)(0x800014f2 + (slot + 8 + track * 0x35) * 2) = value` (= `0x80001502 + track * 0x6a + slot * 2`); stride `0x35` entries = `0x6a` B per track, matching the voice block. Track `0x10` (master/FX) takes a separate branch, `*(u16*)(0x800014f2 + (slot+0x1b0)*2) = value`. For an audio track it then clears `curSound[track]` (`0x800019b4 + track * 4`) when the voice holds another Sound than the track's own kit Sound, `if (kitbase + track*0xa2 + 0x20 != curSound[track]) curSound[track] = 0`, which forces a full Sound load at the next trig. It writes **no** dirty bits and nothing to the 16.16 array: a live edit reaches a sounding voice through this mirror write alone. 6 callers: `SoundParameterSet::vfunc_13` / `vfunc_29`, `FxParameterSet::vfunc_13` / `vfunc_29`, `FUN_400152fc` (above) and `FUN_4001548e`. ⛔ A plain copy: MAIN OS does not interpret the SLICE value here. In this build (POLY voice pool) an entry trampoline, `jmp` + `nop` over its first 8 B (three whole instructions), leads to an 86 B pad at `0x400afea8`: for the Source of a POLY pool the pad runs the displaced stock instructions once per pool voice; for any other track it runs them once, which is the stock behaviour | decompiled; ✅ confirmed on the test unit: live Source edits reach every pool voice, and parameter locks land on the right voice; [patch listing](../docs/patch_listing.md#poly-engine) |
| `SoundParameterSet::vfunc_20` at `0x4000eecc` | ✅ Calls `FUN_40078c2c`: the class-side machine-slot resolver | decompiled |
| `FUN_4015bd9c` | ⚠️ RTTI dynamic-cast helper, `(obj, &From::typeinfo, &To::typeinfo, 0)`; the idiom behind the Observer change-info dispatch | call pattern |
| `FUN_4013b38e(slot, &obj)` | ✅ Binds a value-formatter object to a parameter; 162 calls = 162 encoder parameters, in descriptor order | code read |
| `FUN_4000d2d6(base, track)` | ✅ **Get the Sound for a track.** Clamps track to 0–7 and returns `base + 0x60 + track * 200`: Sound = 200 B, array from `base + 0x60`. 35 callers; the standard track → sound accessor | decompiled |
| `FUN_40021a0e(sound)` | ✅ **Get the machine type**: virtual slot `+0x28` fetches the storage/mirror, then reads a `char` at `+0x7e`; returns −1 if absent | decompiled |
| `FUN_400747ea` | ⚠️ In the same `0x40074xxx` cluster; one of the six MAIN OS functions that reference the 16.16 array at `0x80002B50`. Not read further. The other five (`FUN_400746cc`, `FUN_40074700`, `FUN_40074740`, `FUN_4007480a`, `FUN_4007489e`) have their rows in "Parameter values into the engine" | address grep |
| `FxParameterSet::vfunc_29` at `0x4000f5fe` | ✅ Pushes FX/master parameters with a hard-coded track index `0x10` (16), the one place a track index is fixed in code, because the master is not interchangeable | decompiled |
| `FxParameterSet::vfunc_13` at `0x40010c56`, `SoundParameterSet::vfunc_29` at `0x400101a0` | ⚠️ Push parameters with the track passed as a variable (contrast the master above). `SoundParameterSet::vfunc_13` (`0x40010ba0`), the absolute setter, has its own row in "Parameter read and write paths" | call sites |

In this build the Slice Select descriptor (record 136 at `0x40191b28`) has its minimum (at
`0x40191b30`) lowered from `0x00000000` to `0xfffffe00` (−2 in 8.8), which makes room for the two
SLICE round robin values RRBN (−1) and RRND (−2) below NOTE (0). The Slice Select formatter address
(an immediate at `0x4013b916`, stock `entry_4005f7b0`) points at SLICE round robin's own formatter at
`0x400b2410`. RRND is work in progress: in this build it is identical to RRBN. See
[features/slice_round_robin.md](features/slice_round_robin.md).

### Sound layout and slot usage

Two sound representations share the same field layout up to `+0x7e` but differ in stride: the
compact sound embedded in the kit (`kit + 0x20`, stride `0xa2` = 162 B, from `FUN_4007aaba`) and the
200-B Sound object (`FUN_4000d2d6`, `base + 0x60 + track * 200`), which has about 38 B more tail.

| offset | content |
|---|---|
| `+0x00`–`+0x13` | header |
| `+0x14`–`+0x7d` | 53 × u16 parameter values (`0x6a` B), indexed by slot |
| `+0x7e` | machine type (byte) |

`0x14 + 53 × 2 = 0x7e` exactly: the machine byte abuts the end of the parameter array. Two offsets
derived independently meeting here is strong evidence that both are right.

⚠️ Which object holds the 200-B Sound array is not fully pinned. The grid-edit code reaches it from
the Project object as `FUN_4000d2d6(FUN_4001489a(project), track)`, with
`FUN_4001489a(project) = project + 0x74` (see the grid-edit section). ⛔ It is not inside the kit: a
200-B array at `kit + 0xd4` would overlap the kit's `0xa2`-stride sound array
(`kit + 0x20`…`+0x530`, where the FX block starts).

**Slot usage.** Of the 53 slots, sound pages use only 1–45: LFO1 1–8, LFO2 9–16, SRC 17–24
(machine-specific), Filter 25–37, Amp 38–45. Filter Gain and Reso share slot 27, mutually exclusive
by filter type. Slots 0 and 46–52 are unused by sound pages (8 spare). Slots 46–51 hold the master
track's input-mixer parameters (page 10 spans slots 43–51), and slot 52 has no record: every track
uses the same 53-slot layout, but each track type fills a different range, so on an audio track
slots 46–52 are free. ⛔ They are not leftover LFO
duplicates. The 53-slot width runs through the whole pipeline: sound struct (53), SRAM mirror (`0x6a`
per track), 16.16 expansion (`0xd4` = 53 × 4), p-lock flag row (`0x35` = 53). Parameters are
slot-addressed, not positional, so a new sound parameter can claim a free slot through a new
descriptor record without shifting anything ([parameters.md](parameters.md),
[pattern_layout.md](pattern_layout.md)).

### The SRAM parameter mirror and the two index spaces

The parameter array is mirrored to SRAM verbatim. `FUN_40076f82` bulk-copies `0x6a` B from
`sound + 0x14`; `FUN_40076ee8` pokes one value. Their address arithmetic agrees exactly
(`0x800014f2 + (slot + 8 + track * 0x35) * 2` ≡ `0x80001502 + track * 0x6a + slot * 2`), so a bulk
rebuild and a single-parameter edit land in the same place.

There are two index spaces, and the mismatch is not a bug. Tracks are labelled 1–8 in the UI, and
there is also a separate master track, reached with [FUNC]+[LFO]:

| | tracks | master / FX |
|---|---|---|
| **parameter system** (what callers pass) | 0–15: 8 audio + 8 MIDI | 16 (`0x10`) |
| **SRAM voice slots** (the arrays) | 0–7: audio only; MIDI tracks have no voice | 8 |

- u16 mirror: track *t* at `0x80001502 + t * 0x6a`; master at `0x80001852` = `0x80001502 + 8 * 0x6a`.
- 16.16 array: track *t* at `0x80002B50 + t * 0xd4`; master at `0x800031F0` = `0x80002B50 + 8 * 0xd4`.
- `FUN_40078248` (mute/stop) covers `param_1 < 0x10`, with `0xffffffff` = all: mute applies to the 16
  tracks including MIDI, while the voice arrays cover only the 8 audio ones. Both counts are right;
  they count different things.
- `FUN_40076ee8`'s `param_2 == 0x10` branch (track 16) is pure index translation: it writes `0x80001852 + slot * 2`, the
  address the normal formula gives for SRAM slot 8. The only real difference is that it skips the
  voice-block cache invalidation, since the master has no per-track voice block.

When code looks structurally odd (one hard-coded track among interchangeable ones), asking what it
corresponds to on the instrument is often faster than more disassembly.

## Voice and trigger path

| address | what it does | evidence |
|---|---|---|
| `FUN_4007699e(block)` | ✅ **Voice upload**: copies `0x6a` B to SRAM `0x80001852` (SRAM slot 8, the master block) plus sub-blocks at `0x80001874` / `0x80001884` / `0x80001894` / `0x800018a8`, gated on the mode bits in `_DAT_41960328` | decompiled |
| `FUN_40076fe6(base)` (124 B) | ✅ **Kit voice build**: loops the 8 tracks (stride `0xa2`), calling `FUN_40076f82(block, track)`; stores the kit base (`_DAT_800019ac = base`); tail-calls `FUN_4007699e` (`braw`, not `jsr` + `rts`). ⛔ It runs when a project loads, not on a pattern switch (✅ confirmed on the test unit): a pattern switch copies the new pattern's kit into the same active-kit buffer, and the ISR's set-active-kit handler skips its guarded body when the address in `_DAT_800019ac` is unchanged (compare at `0x400773c4`). In this build (POLY voice pool) the tail branch at `0x4007705a` goes to `0x400b026e`, which replays the displaced stack adjustment, calls the pool-map builder's `mapFull` entry and jumps on to `FUN_4007699e` (see "The pool map: build and refresh") | decompiled; ✅ confirmed on the test unit; [patch listing](../docs/patch_listing.md#poly-engine) |
| `FUN_40076f82(block, track)` | ✅ **Sound load**: stores `block` at `0x800019b4 + track * 4` (`curSound`), copies the `0x6a`-byte value array from `block + 0x14` into the voice mirror at `0x80001502 + track * 0x6a`, then calls `FUN_40076f5a` (the machine byte) and `FUN_400746cc` (the 16.16 expansion). `FUN_40076fe6` calls it for every track at project load. The ISR calls it at note-on (decompile lines 336–362) with the event's parameter-block pointer `[10]` and the event's voice (its track `[2]` in stock), which loads that Sound onto the voice (see "ISR per-trig Sound load") | decompiled |
| `FUN_40076f5a(block, track)` | ✅ **Render machine byte**: `if (block && track < 8) 0x800018bc[track] = block[+0x7e]`. It has **no gate**, so a voice renders whatever machine its loaded block holds. Called by `FUN_40076f82` and on `SoundConfigChangedInfo` | decompiled + call sites |
| `FUN_40076dfc(base)` | ⚠️ **Kit/global-level upload**: clamps and writes globals (`+0x900`, `+0x904`, `+0x8fc`, `+0x90a`, `+0x90e`) to `0x80001964`…`0x80001a16`, and the mode bits `_DAT_41960328`; uploads the mute mask (`0x80001964`, from `base + 0x1c`) and solo mask (`0x80001968`, from `base + 0x20`) | decompiled |
| `FUN_40077062` | ⚠️ Voice upload/refresh helper | call sites |
| `FUN_40077120` | ✅ **The audio ISR** (4,280 B, 52 callees), installed as an interrupt vector by `FUN_40076b5a` (`_DAT_400002fc = FUN_40077120`). Runs the per-voice state machine and consumes the scheduled-event queue (next section). In this build it is spliced at `0x400773e6` and `0x400774a2` (POLY voice pool), `0x400774ca` and `0x400774d6` (mute by origin) and `0x40077a72` (tick-wipe fix); [docs/patch_listing.md](../docs/patch_listing.md) lists every run | decompiled |
| `FUN_40076b5a` | ✅ **Audio subsystem init**: calls 8 sub-inits, clears the two per-voice arrays (`DAT_4395ddf4` → 0, `DAT_4395df20` → −1, `0x20` B = 8 × 4 each), installs the ISR at vector `0x400002fc`, writes peripheral `0xfc04c07f = 5`, registers `FUN_40076938` via `FUN_4000116a` | decompiled |
| `FUN_4001d068` | ✅ **Mute-mask reader**: per-track 16-bit mask at `PatternSettings + 0x1c` | code read; [features/mute_by_origin.md](features/mute_by_origin.md) |
| `FUN_4001d4a2` → `FUN_40078248(track, mode)` | ✅ The voice **stop/flush** path. `FUN_40078248` takes `track < 0x10` (`0xffffffff` = all 16) and, when that entry of `DAT_4395ddf4` (the voice priority) is at most 1 (`mode == 1`) or at most 2 (any other mode), writes 1 to the countdowns `0x8000196c[t]` and `0x8000198c[t]` so they expire on the next tick (see "Countdown timers" below). Its callers are mute (`FUN_4001d4a2`) and kit changes. ⛔ Not note-on | decompiled + callers; [features/mute_by_origin.md](features/mute_by_origin.md) |
| `FUN_4001a0d4`, `FUN_4001a3d8`, `FUN_4001ac18`, `FUN_40016040`, `FUN_4000d5e4` | ⚠️ Sequencer-side callers of the voice upload; not yet read. The `Brain` constructor (`0x4000989a`, row in "Pool-cursor hook sites and the 30 Hz UI clock") and the SysEx dump receiver `FUN_40009182` (row in "Serialization and persistence") call it too | caller lists |

## The trig-to-voice event scheduler

The sequencer does not start voices directly. It schedules them, and the audio ISR plays them
sample-accurately:

```
FUN_4006f1be / FUN_4006f546   (per step, per track)        sequencer side
   read the step's u16 trig word + trig condition
   → FUN_400ddb12() allocates an event node, the evaluator fills it
   → linked into a time-slot bucket list
                    ↓
FUN_40077120   (the audio ISR, vector 0x400002fc)          audio side
   walks buckets while bucket[1] (timestamp) is due
   for each event in the bucket:  switch on event[0] (the type)
```

| address | what it does | evidence |
|---|---|---|
| `FUN_400ddb12()` (54 B) | ✅ **The note-event allocator**: pops the free list `_DAT_421b794c` (link at `[0x12]` = `+0x48`, the one `FUN_400ddb48` pushes onto) and zeroes `[0]`, `[10]`, `[0xb]`, `[0x11]` and the link `[0x12]`; returns 0 when the list is empty. So **`event[10] == 0` is the default state of every event**; only the audio evaluator `FUN_4006f1be` writes it. Its 9 callers are the event producers: `FUN_4007011c` (the sequencer engine), `FUN_4006eaf4`, `FUN_4006e950`, `FUN_40076be0`, `FUN_4006f546`, `FUN_4007683c`, `FUN_400dd3a8`, `FUN_4006f1be` and the ISR `FUN_40077120` itself. The ISR makes retrig copies with `FUN_400d7e64(copy, event, 0x4c)`, so a changed `event[10]` carries into the copies | decompiled |
| `FUN_400d0044()` (38 B) / `FUN_400d000e(ev)` | ✅ **A second event pool**, separate from `FUN_400ddb12`'s. `FUN_400d0044` allocates with no empty check: it pops the free list `_DAT_421850b0` (link at `[0xb]` = `+0x2c`) and zeroes `[0]`, `[9]`, `[0xb]`. `FUN_400d000e` frees: it releases `ev[9]` through `FUN_400cfff0`, then pushes the node back. Callers: the ISR, `FUN_400d129c` and the MIDI evaluator `FUN_4006f546`, which takes its note object from it (the event's `[4]` points at that object). ⛔ Do not confuse it with the note-event pool at `_DAT_421b794c` (link at `[0x12]`, `0x4c` B records) | decompiled |
| `FUN_400dd9dc` | ✅ **Queue-head getter**: returns `_DAT_421b7940`, the head of the time-slot bucket list the ISR walks. The setter is `FUN_400dd9e4` (`_DAT_421b7940 = next`) | decompiled |
| `FUN_400ddbc0(bucket)` | ✅ **Free a bucket**: pushes onto the free list `_DAT_421b7948` (link at bucket `+0x10`) | decompiled |
| `FUN_400ddb48(event)` | ✅ **Free an event node**: pushes onto the free list `_DAT_421b794c` (link at node `+0x48`); if `event[0] == 0 && event[0x11] != 0` it also calls `FUN_400ddae4(event[0x11])` | decompiled |
| `FUN_400d0122`, `FUN_400cfff0`, `FUN_400d129c` | ⚠️ The rest of that second pool's API, used with the MIDI-out task `FUN_400d0578`: from the call sites, link and enqueue; `FUN_400cfff0` is the release that `FUN_400d000e` calls on `ev[9]`. `FUN_400d129c` is also the MIDI-track branch of the per-track note senders (see "MIDI note input to audio tracks") | call sites |

**Time-slot bucket** (the outer list, head `_DAT_421b7940`): `[0]` (`+0x00`) always-due flag ·
`[1]` (`+0x04`) timestamp; the bucket is due when `[1] − now < 0` · `[2]` (`+0x08`) head of its
event list · `[4]` (`+0x10`) next bucket. The ISR (around decompile line 150) loops over buckets while
they are due, then over each bucket's events via `[0x12]`, switching on `event[0]`. The emulator
injects a synthetic note-on by seeding one bucket and one event ([emulator_runs.md](emulator_runs.md)).

**Event node**, as the ISR's note branch reads it (word indexes):

| field | meaning |
|---|---|
| `[0]` | type: 2 = set active kit (sound/kit change); 3, 4, 5 = other (the MIDI evaluator writes 3); anything else = note/trig |
| `[1]` | sub-kind: 1 = note-on, 2 = note-off (as the generic note emitter `FUN_4007683c` writes them). The audio evaluator sets 1 for every trig, including trigless lock trigs, which the ISR recognises by `(event[9] & 0x81) == 1` and tests first |
| `[2]` | track. The ISR resolves the voice from it (the same number in stock) |
| `[3]` | priority, compared against `DAT_4395ddf4[voice]` (the voice-steal test below); 1 for sequencer trigs, 2 for live and external-MIDI notes |
| `[5]` | velocity as `vel << 8` (8.8), a u16 |
| `[6]` | the note (audio trigs); the ISR writes it `<< 16` to `0x80001f28 + voice * 4`, the note array the render reads |
| `[9]` | flags word (bits `0x1`, `0x80`, `0x200`, `0x400`, `0x10000`, `0x40000`, `0x80000000`; `0x8000`, see the serial below) |
| `[10]` | pointer to the `0xa2` parameter block: the track's live block (`base + 0x20 + track * 0xa2`) or a sound-locked one at `0x41938472 + n * 0xa2`. Only the audio evaluator sets it; it is 0 for every other producer, and the ISR then falls back to the voice's own kit sound (kit base + `0x20` + voice × `0xa2`) |
| `[0xc]` | length, looked up through the table at `0x40177480` (the ISR reads the same table as `0x4017747c + (n + 1) * 4`) |
| `[4]`, `[0xd]`–`[0xf]`, `[0x11]` | payload; `[0xe]` indexes the timing tables `0x401c8e70` / `0x4017747c`; `[4]` carries the generation serial on rescheduled copies (below) |
| `[0x12]` (`+0x48`) | next-event pointer within a bucket |

⛔ Ruled out: reading `[6]` as the trig length. That is the layout of the MIDI evaluator's separate
note object (next section), not of the event node the ISR consumes.

⛔ Ruled out: "one-shot audio trigs get no note-off". Every machine's trig is followed by a note-off;
it starts the AMP envelope release.

**The stock voice-steal decision** is `DAT_4395ddf4[voice] <= event.priority`; on taking it the ISR
writes `DAT_4395ddf4[voice] = priority`. How this build allocates voices:
[features/voice_allocation.md](features/voice_allocation.md).

**Per-voice runtime block, `0x14` B at `DAT_4395df48 + voice * 0x14`:**

| offset | address (voice 0) | meaning |
|---|---|---|
| `+0x00` | `0x4395df48` | active flag: set to 1 on a fresh `0x8000` note-on (ISR line 268), cleared at line 291 when the countdown expires; the first half of the staleness test at line 238 |
| `+0x04` | `0x4395df4c` | generation serial (its row is in the call-frequency section) |
| `+0x08` | `0x4395df50` | countdown, decremented by the elapsed time on each ISR |
| `+0x0c` | `0x4395df54` | ramp accumulator: `+= (elapsed * rate) >> 2`, saturating at `0x7fffffff`; zeroed on a fresh note-on, later multiplied into the level (line 300) |
| `+0x10` | `0x4395df58` | ramp rate, loaded from `DAT_401c8e70[event[0xe] + 1]` |

**Countdown timers.** `0x8000196c` and `0x8000198c` are two per-voice countdown arrays (8 × 4 B
each), not flags. On every tick the ISR subtracts the elapsed time from each positive entry and, when
one reaches 0 or below, sets that voice's bit in a local mask (`local_50` for `0x8000196c`,
`local_4c` for `0x8000198c`). `0x8000196c` is where a sequenced trig's LEN takes effect (its row is
in "Note length, release and the post-loop wipe"). `FUN_40078248` writes 1 to both, meaning "expire
on the very next tick", i.e. stop now: that is how a mute stops a voice that is already sounding,
while new trigs on a muted track are dropped by the ISR's mute test ("the mute gate", below). ⚠️ What
the `0x8000198c` mask drives is not pinned.

**Mute and solo masks.** The ISR takes the mute mask `0x80001964`; if the solo mask `0x80001968` is
non-zero, it uses the complement of the solo mask instead; then it tests the bit for the event's
track (in stock the same number as its voice). In the decompile:
`local_54 = _DAT_80001964; if (_DAT_80001968 != 0) local_54 = ~_DAT_80001968;`, then
`(local_54 >> track) & 1`. Any solo flips the sense: solo overrides mute. Both masks are uploaded by
`FUN_40076dfc`. How this build indexes that test: [features/mute_by_origin.md](features/mute_by_origin.md).

## How a trig's note is resolved: the audio and MIDI evaluators

`FUN_4007011c` and `FUN_4006f882` dispatch per track: `track < 8` → `FUN_4006f1be` (audio),
otherwise `FUN_4006f546` (MIDI). Audio tracks are 0–7 and MIDI tracks 8–15.

- Audio tracks 0–7, `FUN_4006f1be`: one note, the per-step note lock at `+0x280`, else the
  per-track default at `+0x384`; it sets flag `0x10000` when locked.
- MIDI tracks 8–15, `FUN_4006f546`: four notes (NOT1–NOT4, the trig chord), relative to a base of
  `0x40`; out-of-range results are rejected.

Both evaluators have their rows in "Sequencer and pattern playback".

**Audio event fields** (`FUN_4006f1be`) match the ISR's table above: `[1] = 1`, `[2]` = track,
`[3]` = priority 1, `[6]` = the note, `[9]` = flags, `[0xc]` = length via `0x40177480`,
`[0xd]` / `[0xe]` / `[0xf]` = the per-step bytes at `+0x180` / `+0x140` / `+0x1c0`, velocity × 256 in
`[5]`, and `[10]` = the parameter-block pointer.

**MIDI chord resolution** (`FUN_4006f546`). For each of the 4 chord voices it takes the per-step note
lock at `pat + trk * 0x38f + voice * 0x40 + 0x280 + step`; if that is −1 (not locked) it uses the
per-track default at `pat + trk * 0x38f + 0x384 + voice`. Notes are stored relative to `0x40`. On
voices 1–3 a stored `0x40` means "unused" and is skipped. A note that would fall outside 0–127 is
dropped, not clamped: the decompile's `uVar4 == uVar5` test, written out in the last `if` below.

Ghidra decompiler output (excerpt, variables renamed), `FUN_4006f546`:

```c
local_10[0] = 0x40;                            // 0x40 (64) = the base / centre note
for (voice = 0; voice < 4; voice++) {
    n = *(char*)(pat + trk*0x38f + step + voice*0x40 + 0x280);   // per-step note lock
    if (n == -1)                                                  // -1 = not locked
        n = *(char*)(pat + trk*0x38f + voice + 0x384);            // per-track default note
    if ((voice == 0 || n != 0x40) && ((local_10[0] + n - 0x40) & 0x7f) == local_10[0] + n - 0x40)
        local_10[count++] = (local_10[0] + n - 0x40) & 0x7f;      // 0..127, overflow rejected
}
```

The MIDI evaluator fills a separate **note object** (`puVar9` in the decompile, allocated by
`FUN_400d0044`; the event's `[4]` points at it):

| field | meaning |
|---|---|
| `[1]` | `puVar9[1] = track − 8`: the MIDI track normalised to 0–7 |
| `[2]`, `[10]` | set to 1 |
| `[3]` | flags (`uVar11`), merged from the step's u16 trig word and per-track defaults |
| `[4]` (`+0x10`) | number of valid chord notes (0–4) |
| `+0x14`…`+0x17` | the notes, one byte per chord voice (not a longword) |
| `[6]` | length, via the table at `0x40177480` |
| `[7]` | velocity: per-step `+0x80`, falling back to per-track `+0x380` |

The scheduled event itself (`param_5`) is separate: `param_5[0] = 3` (event type 3),
`param_5[2] = track`, `[3] = 1` (priority), and `param_5[4]` = pointer to the note object.

## Pattern data (runtime, per track, stride `0x38f`)

The playing pattern holds 16 track blocks of `0x38f` B (8 audio, then 8 MIDI), followed from
`pat + 0x38f0` by the runtime p-lock table (stride `0x1b35` per track, above). Stored form and
bit-level detail: [pattern_layout.md](pattern_layout.md).

| offset | content |
|---|---|
| `+0x000` | per-step u16 trig word. `0x0200` = the trig (✅ checked against a pattern dumped from the test unit). Placing a trig from the grid sets `0x0001` and `0x0200` and clears `0x0800` / `0x2000` / `0x4000`; bit `0x0010` feeds the swing term (⚠️ below). Other bits: see [pattern_layout.md](pattern_layout.md) |
| `+0x080` | per-step velocity (−1 → per-track default) |
| `+0x0c0` | per-step length (−1 → default) |
| `+0x100` | per-step micro-timing (feeds the sample-offset calculation) |
| `+0x140`, `+0x180`, `+0x1c0` | per-step bytes copied into audio event `[0xe]`, `[0xd]`, `[0xf]` |
| `+0x200` | per-step trig condition (−1 = none; feeds `FUN_400da098`) |
| `+0x240` | per-step byte (−1 = none), read by `FUN_40023632`; ⚠️ which attribute it holds is not pinned |
| `+0x280`, `+0x2c0`, `+0x300`, `+0x340` | per-step note locks, one 64-byte array per chord voice (audio tracks use the first) |
| `+0x380`, `+0x381` | per-track default velocity, length |
| `+0x382` | per-track flags (u16) |
| `+0x384` | per-track default notes × 4 |
| `+0x388` | track length in steps (`FUN_40024040`, default `0x10`) |
| `+0x38a`, `+0x38b`, `+0x38c` | per-track bytes (`+0x38a` feeds the timing maths) |
| pattern level | `+0x1ec59`, `+0x1ec5a`, `+0x1ec5b` (the last is a tempo/scale term in the timing maths); tempo/scale read at `+0x1ec50` / `+0x1ec52` by `FUN_4006fb8e` |

The event's sample offset is computed from the per-step micro-timing `+0x100`, the per-track
`+0x38a` and the pattern-level `+0x1ec5b`, and returned to the caller as `*param_6 = offset * 0x5dc`.
`FUN_4006e8fc` adds a further term when trig-word bit `0x10` is set (⚠️ swing, inference).

## Sequencer and pattern playback

| address | what it does | evidence |
|---|---|---|
| `FUN_4007011c` (4,218 B) | ✅ **Callback step-advance / sequencer step dispatcher**: per track, `track < 8` → `FUN_4006f1be` (audio), else `FUN_4006f546` (MIDI); owns the pattern swap. ⛔ It has no direct callers and its address never appears as a literal in the image: `FUN_4006fd5e` takes it PC-relatively (`lea (d16,PC)`) and installs it as a callback. Unreferenced is not dead | decompiled + reference query |
| `FUN_4006fd5e` | ⚠️ Takes the address of `FUN_4007011c` PC-relatively at `0x4006fe64` (a DATA reference), i.e. installs it as a callback. Its own role is unread | reference query |
| `FUN_4006f882` (516 B) | ✅ **The step driver: one sequencer step for one track.** ⛔ It does not loop over the 16 tracks: it takes the track as `param_1` (guarded `param_1 < 0x10`), and the loop is in its caller. It dispatches `FUN_4006f1be` (< 8) / `FUN_4006f546` (≥ 8); the order of its writes and how it uses the evaluator's result are in "FUN_4006f882: one sequencer step for one track". Reads the playing pattern through the global `_DAT_4195fae8` (track block at `+track * 0x38f`, pattern level at `+0x1ec59`); companion pointer `_DAT_4195faf4`, timing counter `_DAT_4195fb14`, per-track working state around `0x421baxxx` / `0x4195fbxx`. To find the playing pattern in RAM, read `*(0x4195fae8)`. Callers include `PatternGridView::vfunc_2` (UI/preview) and the set-active-pattern path. ✅ Not nested with `FUN_4007011c`: neither calls the other | decompiled |
| `FUN_4006fb8e` (276 B) | ✅ **Set active pattern**: stores the pattern pointer into `_DAT_4195fae8` (and companion `_DAT_4195faf4`), reads the pattern-level tempo/scale at `pat + 0x1ec50` / `+0x1ec52`, then runs the step driver. Called from PLAY/transport (`TransportView::vfunc_2` → `FUN_40070070`) and the pattern-change paths. So `_DAT_4195fae8` is a live pointer into `project_t`'s pattern array: the playing view and the storage view are linked, not separate copies. ⚠️ The pattern's exact offset/stride inside `project_t` flows through stack structs across the callers and is not pinned | decompiled |
| `FUN_4006f1be(track, pat, ?, step, ev, &off)` (828 B) | ✅ **Audio-track trig evaluator** (tracks 0–7): one note, as described above; the full field list is under "Audio event fields" above. It sets **`event[1] = 1` unconditionally** for every sequenced trig, trigless lock trigs included, and `event[3] = 1`. `event[9]` bit 7 (`0x80`, "has a note") comes from the trig's flag word (`trig + 0x382 & 0x80`); bit 0 is the lock flag. `event[10]` = `kitbase + 0x20 + track * 0xa2` (the **trigging** track's own kit Sound), or `iVar10*0xa2 + 0x41938472` (decompile line 132) for a Sound lock; it never writes a pool Source's Sound there | decompiled |
| `FUN_4006f546(track, pat, ?, step, …)` (828 B) | ✅ **MIDI-track trig evaluator** (tracks 8–15): reads the per-step u16 trig word, tests the trig bits, evaluates trig conditions, allocates the scheduled event via `FUN_400ddb12` and its note object via `FUN_400d0044`; chord resolution above. The event is **type 3** (`*param_5 = 3`, `[3] = 1`), and its payload is the separate note object at `param_5[4]` with its own fields, so it never reaches the voice gate: the audio ISR hands it to the MIDI-out task ("The MIDI-track send chain") | decompiled |
| `FUN_4006f16e`, `FUN_4006f4fa` | ⚠️ Per-track helpers called by the two evaluators with `pat + track * 0x1b35 + 0x38f0` (the runtime p-lock table) and `param_3 + 0x34 + track * 0xa2` | call sites |
| `FUN_400da050`, `FUN_400da098(track, step, cond, …)` | ⚠️ Trig condition / probability evaluation: gates whether an event is produced at all | call sites |
| `FUN_4006e8fc` | ⚠️ Extra timing term added when trig-word bit `0x10` is set (swing) | call site |

## Grid edit and trig entry

The path from a TRIG key press to a trig written into the runtime pattern.

| address | what it does | evidence |
|---|---|---|
| `PatternGridView::vfunc_2` at `0x40034112` (4,358 B) | ✅ **The grid key handler** (`consumeKeyEvent`; class vtable `0x40169ae8` = View \| Observer \| LedHandler). A `switch` on `FUN_400b42cc(key)`: cases `9` / `0xa` / `0xb` = copy / paste / clear (strings "COPY/PASTE/CLEAR PAGE", "%d TRIG%s"); cases `0x18`–`0x27` = the 16 TRIG step keys (place, hold, p-lock). Resolves the current track's manager, places a trig via `FUN_40025b02`, queries via `FUN_40023a2a`. Step-hold overlay strings include "Sound: %d %s" and "Sample: %.16s" | decompiled |
| `FUN_40025990(trackObj, step, mask:u16, val:char)` (370 B) | ✅ **The per-step trig/flag writer.** Clamps step ≤ `0x3f` and bails if `step >= FUN_40024040()` (track length); gets the per-step u16 array via `trackObj → (vtable + 0x28)`. Placing (`val != 0`, `mask & 1`) sets `mask` and then ORs in `0x0200` (the trig bit); clearing ANDs `~mask`. Fires a change-info via `trackObj → (vtable + 0x10)` (`PTR_vfunc_0_401676ac` → `TrackStepChangedInfo` / `StepMaskCopy`). 18 callers, all edit paths, none in playback | decompiled |
| `FUN_40025b02(trackObj, step, val)` | ✅ **Place a trig**: if `step < FUN_40024040()`, calls `FUN_40025990(…, step, 1, val)` and clears `0x800` / `0x2000` / `0x4000`. Called from `PatternGridView::vfunc_2` and copy/paste | decompiled |
| `FUN_40023a2a(trackObj, step)` | ✅ **Is there a trig**: tests bit `1`, then `0x800` → `0x80`; returns the trig kind. 12 callers (draw/edit) | decompiled |
| `FUN_40024040(trackObj)` | ✅ **Track step count**: `trackObj → (vtable + 0x28) → +0x388`, default `0x10`; the loop/clamp bound for trig writes | decompiled |
| `FUN_40023632(trackObj, step)` | ✅ Per-step byte reader: `*(char *)(storage + step + 0x240)` (−1 = none) | decompiled |

**Resolving the current track in the views** (✅ decompiled):

- `project = FUN_4012198c()`.
- Current pattern manager: `FUN_40015696(project)` = `project + selPattern * 0x64c + 0xa84` (the
  128 × `0x64c` array at `+0xa84`; `selPattern` from `FUN_4008e460`).
- Current track index: `FUN_4001ccc4(project + 0x30)` (object → vtable `+0x28` → `+8`, 0–15).
- Current track manager: `FUN_40012512(patMgr, track)` = `patMgr + 0x70 + track * 0x58` (16 × `0x58`).
- `FUN_400156cc(project)` wraps all three.
- Current track's machine: `FUN_40021a0e(FUN_4000d2d6(project + 0x74, track))`, i.e. `sound + 0x7e`,
  with `FUN_4001489a(project) = project + 0x74` the sound-array base.

## Per-machine sample windows and SLICE selection

The render functions that call these, `FUN_40074e84` and `FUN_400754fe`, have their rows in
"Per-tick sample render" below. The four per-machine **window calculators** each read fields of the
track's engine block (`+2` reverse, `+8` Select or start, `+0xa` length, `+0xc` grid) and return a
packed (start, end) sample window. Each has exactly two call sites, one in each render function.

| address | what it does | evidence |
|---|---|---|
| `FUN_4007499c` | Machine 0 = ONESHOT window calculator | decompiled |
| `FUN_40074bbe` | Machine 1 = WERP window calculator; also reads slot 21 at `+0x3e` | decompiled |
| `FUN_40074a72` | Machine 2 = REPITCH window calculator | decompiled |
| `FUN_40074af2(paramblock, note << 16, defaultEnd, track)` (204 B) | ✅ **Machine 3 = SLICE: picks the slice window.** Two call sites, `FUN_40074e84` and `FUN_400754fe`; between them it runs once per track, and its fourth argument is the track index 0–7 (literal 0 from `FUN_40074e84`, `track + 1` inside `FUN_400754fe`'s loop; ✅ confirmed on the test unit). Parameter-block bytes: `+2` reverse, `+8` Select (`*(char*)(param_1 + 8)`), `+0xa` Length, `+0xc` Grid (`*(char*)(block + 0xc)`). Guard: sample slot `< 0x80`. Returns start and end packed into 64 bits (`CONCAT44(start,end)`), swapped when `paramblock + 2 > 1` (reverse). The algorithm is below. In this build a 10-byte run at `0x40074b38` replaces the stock `max(Select, 0)` clamp (five instructions) with `tstl %d4 ; bpls ; jmp 0x400b23b0`, so a negative Select (RRBN / RRND) reaches SLICE round robin's landing pad with its sign intact; Select ≥ 0 falls through to the stock code, where the clamp was a no-op | decompiled + objdump; [features/slice_round_robin.md](features/slice_round_robin.md) |
| `0x402bb3b0` | ✅ **Slice-point table**: `0x100` 32-bit entries per sample, indexed `sampleBase + (idx << (4 − grid)) × 4`. Read only by `FUN_40074af2` and `FUN_40074bbe`; `FUN_40074cd0` and `FUN_400760b4` build and clear it per sample at load, and neither selects a slice. ⚠️ It lives in `.bss`, not in the image: it is built at runtime when a sample is loaded, so it cannot be inspected statically or pre-seeded by a patch | decompiled + address range |
| `DAT_8000ee20 + track * 0x5e` | ⚠️ Per-track state in SRAM, stride `0x5e`. Its first byte is the track's sample slot, which `FUN_40074af2` reads as `(&DAT_8000ee20)[param_4 * 0x5e]` and turns into `sampleBase = slot * 0x100` | decompiled |

**How `FUN_40074af2` picks a slice** (stock), in words (a decompiler excerpt is in
[features/slice_round_robin.md](features/slice_round_robin.md#how-stock-firmware-picks-a-slice)):

- Grid: the byte at `paramblock + 0xc`, capped at 4, is log2 of the slice count; count = `4 << grid`
  (4, 8, 16, 32 or 64 slices).
- Select is capped at the count; Select 1…N picks slice 0…N−1. Stock lets the encoder take Select up
  to 64 whatever the grid, and every value above the count plays the last slice.
- Select = 0 is **NOTE mode**, a single branch: slice = (trig note − 12) mod count, computed as
  `((note >> 16) − 0xc) & (count − 1)`. Note 12 is slice 0; the mask is a true modulo because the count
  is always a power of two.
- Length: the byte at `paramblock + 0xa`, capped at `0x3f`.
- Start = table entry `sampleBase + (idx << (4 − grid)) × 4`; end = table entry
  `sampleBase + ((idx + len + 1) << (4 − grid)) × 4` (`lsll #2,%d2` at `0x40074b70`). The `<< (4 − grid)` maps every grid onto the 64-slot
  table.

**Where this code lives.** `FUN_40074af2` is at raw offset `0x746f2` in the section-3 file, MAIN OS
`.text`, inside the stable in-image region ([memory_map.md](memory_map.md)), as are `FUN_40074e84`
and the audio ISR. Only the arrays it reads (`0x80001f28` notes, `0x8000ee20` per-track state,
`0x80002b50` parameters) are in SRAM, and the slice table is in `.bss`. So changing slice selection
is an in-image edit: no SRAM placement, no crt0 change, no `.bss` involvement.

## The engine's per-voice state and counters

How often the render path runs, and why code on it must stay pure, is at the top of "Per-tick
sample render".

| address | what it does | evidence |
|---|---|---|
| `DAT_4395df4c + voice * 0x14` | ✅ **Generation serial** (`+0x04` of the per-voice block). The ISR does `+= 1` at line 267, but only when `(short)flags < 0`, i.e. flag bit `0x8000`. The audio evaluator builds `[9]` from `trigword \| {0x10000, 0x80, 0x200, 0x400}` and never sets `0x8000`, which gates MIDI / chord / retrig voice allocation. ⛔ So it is not a per-audio-trig counter: for plain audio trigs it stays 0 (✅ confirmed on the test unit). For the `0x8000` class it is a stamp-and-check handle: on a fresh note-on the ISR increments `serial[voice]`, copies the whole `0x4c`-byte event, writes `event[4] = serial[voice]` into the copy (line 278) and sets `event[9] \|= 0x40000`; when the copy arrives, line 238 drops it if the voice is no longer active (`*piVar22 == 0`) or `serial[voice] != event[4]` (the voice has been re-taken since). How this build pairs note-offs with their voice: [features/owner_latch.md](features/owner_latch.md) | decompiled + device |
| `DAT_4395ddf4 + voice * 4` | ✅ Per-voice priority (stride 4), set to the event priority when the ISR takes the voice | decompiled |
| `DAT_4395df20 + voice * 4` | ✅ **The currently held note** per voice (−1 = free). Written with the note at note-on (ISR line 296, under the same guard as the `0x80001f28` write), compared against the incoming note to match a note-off (line 369), cleared to −1 on a match (line 371) and by `FUN_40076b5a` at init. ⛔ Not a counter | decompiled |
| `_DAT_41960338` | ⛔ Per-tick counter, not per trig: `+= 1` at the ISR tail (line 696), unconditionally on every call | decompiled |
| `_DAT_41960270` | ⛔ Periodic-refresh counter, not per trig (line 605, in a block that toggles `_DAT_4196026c` and walks 8 tracks) | decompiled |
| (none) | ⛔ **The stock engine keeps no per-audio-trig counter.** The only per-index `+1` in the audio path is the serial above (line 267, gated on `0x8000`); every other `+1` in the ISR is a loop iterator or one of the two globals above. SLICE round robin therefore keeps its own counters ([features/slice_round_robin.md](features/slice_round_robin.md)) | exhaustive grep of ISR increments |

## Per-tick sample render: `FUN_40074e84` and `FUN_400754fe`

The audio ISR `FUN_40077120` calls these two back to back on every audio tick, both with the arguments
`(engineObj, &DAT_41960316, uVar15, uVar9, 0x80001f18)`: the engine object (`0x80002760`, returned by
`FUN_4007489e(&DAT_800014f0)` just before), the per-track machine-type array `&DAT_41960316` and
`0x80001f18` among them. Together they are a two-phase software pipeline over the
eight tracks. `FUN_40074e84` is the prologue: it fetches track 0's sample window and runs the pitch loop for all
eight tracks. `FUN_400754fe` is the main loop over tracks 0..7. Each pass fetches the window for track + 1 and
emits the audio for the current track. Machine types are 0 ONESHOT, 1 WERP, 2 REPITCH and 3 SLICE; this build
adds 4 POLY.

✅ All eight tracks play SLICE at the same time, each with a different sample (confirmed on the test unit).
⛔ Ruled out: reading the two functions as two voices or a 2-voice limit. Each per-machine
window function does have exactly two call sites, one in each function. But `FUN_40074af2` runs once per track,
and its fourth argument is the track index.
⛔ `FUN_40077120` calls the two back to back on every audio tick (decompile lines 519–521), and between them they
call `FUN_40074af2` for every track, with no note-started guard. So everything on this path runs every tick for
every track, whether or not a note started, and code added here must stay pure. The stock `(note − 12) & mask` is
idempotent, which is why recomputing it every tick is harmless. A counter bumped per call runs every tick, not once
per trig, and moves the slice under a voice that is already playing, which clicks.

| Address | What it does | Evidence |
|---|---|---|
| `FUN_40074e84` (1658 B) | **Pipeline prologue.** Stashes the per-track trig masks: `_DAT_80001228` = the previous mask, `_DAT_8000122c` = the current one, bit t = track t. Dispatches on track 0's machine byte `DAT_41960316[0]` (`*param_2`) with the engine block at `engineObj + 0x34`; for SLICE it passes note[0] = `_DAT_80001f28` and track 0. From the returned window it computes the read window with fixed `0x8d`/`0x8c` overlaps (a 141-sample crossfade) and 8-sample alignment (`& 0xfffffff8`), then writes the two fetch TCDs `_DAT_80001200` (control `0xc000`) and `_DAT_80001204` (control `0xbe00`). Then the **8-track pitch loop** (`iVar16` = 0..7): for each track it takes the note base `DAT_80001f28 + track*4` (`note[iVar16]`) and the block (stride `0x6a`) and computes a pitch and level coefficient into the per-track state near `DAT_8000eddc` (stride `0x5e`), using the table at `0x401813ac` (`resample[0x401813ac + pitch] * *piVar15 >> 1`). On a SLICE track with Select = 0 (NOTE) the pitch is forced to `0x3c0000` (`if (Select==0) iVar6=0x3c0000` … `else iVar6=note[track]`), so NOTE mode plays at a fixed pitch and every other Select value takes its pitch from the note. The machine dispatch is an if/else chain on 0/1/2/3 (`if (cVar2 != 2) { uVar17 = 0; … }`) with no jump table, so stock sends an unknown machine value (4 or more) to a null, silent voice, not a crash ([compatibility.md](compatibility.md)); ⚠️ `FUN_400754fe` mirrors this dispatch and is presumed to have the same bound, not separately re-read. It reads slot 21 (SLICE Select / Sample STRT) as the high byte of the u16 at `block + 0x3e` (`block + 0x14 + 21 * 2`), `*(short*)(block + 0x3e) >> 8`, which confirms that parameters are 8.8 fixed point. It also **writes the per-voice status blocks** (see "SRC page 2"): it holds 43 of the 58 literal references into the block range, advances `+0x00` and sets `+0x10` / `+0x24` | decompiled + literal-reference scan |
| `FUN_400754fe` (2614 B) | **Pipelined main loop, track = 0..7.** First, housekeeping. When the flag byte `0x402db3b1` is set, it busy-waits until bit 7 of the halfword at `+0x1e` of the TCD at `_DAT_80001200` is set (the hardware ready bit), `while ((*(_DAT_80001200+0x1e) & 0x80)==0)`. It copies a run of halfwords from the fetch buffer (pointer at `0x402dbe00`) to `0x80001208` and arms the TCD at `_DAT_80001204`. Inside the track loop (head `0x400755b8`, back-edge at `0x40075f34`) it flips that pointer once per track (`_DAT_402dbe00 = 0x25e0 - _DAT_402dbe00`, stored at `0x400755da`). **Fetch phase** (tracks 1..7): it dispatches on `DAT_41960316[t+1]` with the block at `engineObj + 0x9e + t*0x6a`, passes note `DAT_80001f28 + (t+1)*4` and track t+1 to `FUN_40074af2`, and tests a new trig as bit t+1 of `_DAT_80001228`. It then does the read-window bookkeeping with the same `0x8d`/`0x8c` overlaps and programs the fetch TCD. **Emit phase**: it computes the rate (`FUN_40074960`), gated by this track's bit in `_DAT_8000122c`, then MAC-computes this track's 32 output values and stores them at `DAT_80001a18 + track*0x80` (the loop at `0x40075e64..0x40075f0c`: `lsll #7` and `addil #0x80001a18` put the track's block address in `%fp` at `0x40075e64..0x40075e70`, and each of the 16 passes ending at `0x40075f0c` stores two longwords with `movel %a0,%fp@+`, 128 B per track). The per-track state is walked by pointer from `0x8000edc4` (stride `0x5e`). It writes the status blocks' upper fields (`+0x5a` on) by literal address. The per-machine window calculators it calls, and the slice-point table, are in "Per-machine sample windows and SLICE selection" | objdump (EMAC) + decompiled + literal-reference scan |
| `DAT_41960316[0..7]` | Per-track machine type as the render sees it. The ISR copies it from `0x800018bc[track]` for every track whose bit is set in the tick's trig mask | decompiled |
| `DAT_80001f28 + track*4` | Per-track note base (note `<< 16`), passed to the SLICE window function and the pitch loop | decompiled |

## Per-track DSP chain after the render

The audio block is **32 samples per tick**. Each track has a 32-sample buffer at `DAT_80001a18 + track*0x80`
(0x80 B = 32 × u32). After the two render calls, the ISR runs this chain, in this order as traced:
`FUN_4007442a` (before the render) → `FUN_40074e84` / `FUN_400754fe` → `FUN_40072178` → `FUN_4007239c` × 8 →
`FUN_400713c0` / `FUN_40072e68` / `FUN_40073004` → `FUN_40072544` × 8 → `FUN_4007239c` × 8 → `FUN_40071920`.
The full picture is in [render_path.md](render_path.md).

| Address | What it does | Evidence |
|---|---|---|
| `FUN_40071920(outbuf, sbuf, engineObj)` (1732 B) | **Master mix and output.** A 10-iteration amp/envelope coefficient loop: tracks 0..7, then 8 and 9 for master and send. It reads the eight track buffers (`FUN_4007167e` × 6) and applies each track's LEVEL (`FUN_40071fe4(&DAT_80001a18 + track*0x80, …)` × 8). It then runs three effect functions, `FUN_40073354` / `FUN_40073900` / `FUN_40073f5e` (parameters from `param_3 + 0x1c2` on), waits for eDMA `0xfc0453de & 0x10`, and writes the interleaved output to the codec buffer `DAT_80002160`. ⚠️ Which effect function is which has not been mapped | decompiled |
| `FUN_40071fe4` | Per-track LEVEL: scales the track buffer in place (sample × level) | MAC arithmetic read |
| `FUN_40072544(block, buf, mask, track)` (2340 B) | **Per-track multimode filter.** A state-variable filter with 8 types selected by `DAT_8000f1e4[track]`, coefficient tables at `0x4017xxxx` (`LZCOUNT`-normalised), a 32-sample loop over `buf = DAT_80001a18 + track*0x80` and coefficient smoothing `>> 5`. It reads the track buffer as input, writes the filtered output to `DAT_8000f5e4` / `DAT_8000f664`, then clears the track buffer. Calls `FUN_40073112` | decompiled |
| `FUN_4007239c(track, buf, block)` (180 B) | **Per-track fractional resampler (pitch).** A 32-sample loop that reads the track buffer as source samples and writes interpolated values back. The rate comes from `0x40175230[block + 0x16]` (high part = step, low part = fraction); the state is at `DAT_8000f134 + track*0x14` | decompiled |
| `FUN_40072178(buf, engineObj)` | ⚠️ Partly read. The first loop sets `DAT_4395dd54[t]` = 2 × v² from a u16 field of each of the 8 engine blocks (stride `0x6a`). The rest is MAC code working in 32-sample loops over the track buffers, two tracks per pass, and is not described further. ⛔ The `nbcd` that some decodes show at `0x400721ce` is not real: with EMAC decoding, `0x400721c6..0x400721d1` are two `mac.l` instructions | objdump (EMAC) |
| `FUN_40073004(engineObj, m1, m2)` (270 B) | **Per-track amp envelope generator.** An 8-track loop (stride `0x6a`) running an ADSR state machine (states 3/2/1 = attack/decay/…). It reads block fields `+0x4c` / `+0x58` / `+0x4e` / `+0x50` / `+0x52` and writes envelope state to the arrays at `0x4195fe04` / `0x4195fe08` (stride `0xc` per track). MAC-based | objdump (EMAC) |
| `FUN_40073112(track)` (24 B) | Returns the envelope level word at `0x4195fe08 + track*0xc`. Called by `FUN_40072544` | objdump |
| `FUN_40072e68(buf, engineObj, mask)` | **Per-track state-variable filter stage.** A MAC biquad (`macl`/`msacl` taps) with its coefficient table at `0x401c7e68` and a 32-sample loop over the track buffer. ⛔ It is not the sample reader: it processes a buffer that is already filled | objdump (EMAC) |
| `FUN_400713c0(engineObj, buf)` | **Per-track amp envelope apply (VCA).** An inner 32-sample loop MAC-scales the track buffer (`param + track*0x80`) by the envelope (state at `DAT_4195fc14` / `DAT_4195fc18`). The ADSR generator is `FUN_40073004`. ⛔ Not mix or pan. `FUN_4007236e(track)` is a separate coefficient step, ⚠️ unverified | decompiled |
| `DAT_80001a18 + track*0x80` | **Per-track 32-sample audio buffer**, 8 tracks. Filled by `FUN_400754fe`'s emit loop. Processed in place by `FUN_4007239c`, `FUN_400713c0` and `FUN_40071fe4`; `FUN_40072544` reads it as filter input and then clears it. ⛔ Not a meter or display buffer | decompiled + objdump |
| `DAT_8000f1e4 + track*4` | Per-track filter type (0–7), read by `FUN_40072544` | decompiled |
| `DAT_80002160` | Codec output buffer. `FUN_40071920` writes the interleaved mix here, and the ISR streams it (`FUN_400032fc` / `FUN_400dd7e4`) | decompiled |
| `FUN_4007442a(engineObj)` | **Per-tick oscillator/LFO and delay update**, run just before the two render calls: a sine-oscillator EMAC recurrence (`DAT_8000d0d0` / `d0d4`), two delay-line pointer loops (8 and 9 taps into `DAT_8000d460` / `DAT_8000d560`), then it programs **eDMA ch30** `0xfc0453c0`, which reads the wavetable at `0x4fc00000 + phase` into `0x8000e270`, 16 B (scatter-gather `&DAT_8000e640`): MAIN OS drives eDMA directly. ⛔ Not the sample fetch | decompiled + objdump |
| `FUN_400732c6` / `FUN_4007312c` | **eDMA ch42** `0xfc045540`: delay/FX, SRAM → SRAM (`0x8000e250` → `0x8000e5f0`), scatter-gather `&DAT_8000d240`. Not the sample fetch | decompiled |
| `_DAT_80001200` / `_DAT_80001204` | **eDMA TCD pointers**: `0xfc045400` (ch32) and `0xfc0453e0` (ch31). They are set at boot from the SRAM-init image (`0x40215200`, which `entry_4000045c` copies from `0x40214000 + 0x1200`); no MAIN OS function assigns them. The render writes these TCDs with SADDR = sample RAM and DADDR = `0x800013a0`, so the sample fetch is eDMA ch31/ch32 | image + decompiled |
| eDMA channel map | ch30 `0x3c0` oscillator · ch31/32 `0x3e0`/`0x400` sample fetch → `0x800013a0` · ch34/36 `0x440`/`0x480` display (FlexBus `0xec07…`) · ch42 `0x540` delay · ch52/54 `0x680`/`0x6c0` peripheral `0xfc0c8…` → `0x80001000`. Setup functions: `FUN_40000f64`, `FUN_400024c0`, `FUN_400026f2`, `FUN_400028c6`, `FUN_40002af8`, `FUN_4007442a`, `FUN_400732c6` | decompiled |
| EMAC decode gaps | Ghidra's stock ColdFire language implements EMAC but has a narrow hole: `movclr.l ACCx,Rx`, and the load forms of `mac.l` / `msac.l`, including those with a `(d16,An)` operand (mode 5, where the displacement is the third word). Mode-5 examples: `0x40075cfa` in `FUN_400754fe` (`a8ee c800 0004`), and `0x400716a2`, where the `0x00fc` word is the displacement and not a trap. Once constructors for these forms are added, the render functions decompile without `halt_baddata`, and the only decode error left in the image is an unrelated `jsr` / `0x0000` boundary at `0x40115fe6`. See [analysis_reference.md](analysis_reference.md#the-emac-language-extension-on-this-image) | objdump + re-import |

## Unusual code regions and landing-pad screening

| Address | What it does | Evidence |
|---|---|---|
| `0x40210e4a`–`0x40211ef2` | A small island of 23 functions that address a **foreign address space**: `DAT_ffff8010`, `_DAT_00800628`, `0x800532`, `DAT_008012b8`, `DAT_008005da`. These are `0x00800xxx` and `0xffff8xxx`, neither DDR (`0x4xxxxxxx`) nor SRAM (`0x8000xxxx`). Purpose unknown. ⛔ Do not use it as a landing pad, and distrust any "dead" verdict on it | decompiled |
| `FUN_40023134` | Shows up in zero-reference lists, but it sets vtable pointers: it is a **constructor** reached by a factory or placement path that static analysis misses. ⛔ Constructors and island functions make poor pads. Prefer a dead leaf (no callers and no callees) in the main block, and read it before use | decompiled |
| `FUN_40001fb2` (382 B) | ⛔ **Live, although a dead-function scan lists it as a pad candidate** (0 Ghidra references, leaf). objdump shows FlexBus encoder/display I/O (`moveb 0xec070004,%d0 … moveb %d1,0xec07000c`) and a ring buffer at `0x405d0864` / `0x405d084c`. It is reached through a vector or dispatch. In the crt0/low region, zero references prove nothing: vet with objdump (peripheral literals, real work) and an absolute-reference scan | objdump |
| `FUN_400b21b0` (164 B), `FUN_400b20ac` (76 B) | Pads used by the POLY voice pool ([patch listing](../docs/patch_listing.md#poly-engine)). Both are preceded by an `rts`, have zero absolute references (so they are in no vtable) and contain no peripheral literals. They sit in the same `0x400bxxxx` cluster as the SLICE round robin pad `FUN_400b23b0`. Current use is in [landing_pads.md](landing_pads.md) | objdump + reference scan; ✅ confirmed on the test unit (in use by the POLY voice pool) |

## Value formatters

| Address | What it does | Evidence |
|---|---|---|
| `entry_4005f7b0` | **Stock SLICE Select value formatter**: shows NOTE when `value >> 8` is 0, otherwise the number. The `NOTE` immediate is at `0x4005f7c0` (file `0x5f3c0`). In this build the formatter binding at `0x4013b916` (file `0x13b516`, inside `FUN_4013b6a2`) points instead at SLICE round robin's signed formatter, in pad `FUN_400b23b0` at `0x400b2410..0x400b2468`: RRND for −2, RRBN for −1, NOTE for 0, the number otherwise. The stock function stays in the image, unused. ✅ confirmed on the test unit | decompiled |
| `entry_400656ce` | AMP HOLD value formatter; it also shows NOTE. ⛔ Not the SLICE Select formatter | decompiled |
| `0x40095662` | `TrackNoteMenuView::vfunc_4`: the keyboard-mode overlay, one of the four users of the shared `NOTE` string | decompiled |
| `0x4000b564` | 2,270 B function that is the target of a slot in hundreds of vtables. ⚠️ Taken to be the shared not-implemented / base handler, on the strength of that pattern alone | vtable scan |

## MACHINE menu ([FUNC] + [SRC])

The MACHINE menu is a `MachineListView` overlay on the SRC page. In stock the item count is unbounded (it grows
with the enum builder), but the menu has four visible rows, hard-coded `machine < 4` bounds and a cursor clamp at
index 3, so a fifth item is built but cannot be reached. In this build POLY (machine 4) is the fifth entry. The
enum builder is grown, the visible count drops from 6 to 4 so the list's own scrolling engages, and cursor moves
go through the list's count-aware `vfunc_7`. ✅ POLY selectable and assignable on the test unit. The
bytes are in [../docs/patch_listing.md](../docs/patch_listing.md#poly-engine) (`poly_engine`).

| Address | What it does | Evidence |
|---|---|---|
| `MachineParameterPageView::vfunc_2` @`0x4002b32e` | SRC-page key handler. With machine 3 (SLICE) it opens a SLICE-specific view; the machine-menu branch goes to `FUN_4002b2f8` → `FUN_4002b040`; anything else goes to `SoundPageView::vfunc_2` | decompiled |
| `FUN_4002b040` (696 B) | **Builds the MACHINE menu.** Resolves the current track and Sound, caches the built `MachineListView` at `page + 0x194` (and reuses it if present), calls `FUN_4012e052`, wires the callbacks `FUN_4002b520` / `FUN_4002ac18` / `FUN_4002aef2` and sets the selection (`FUN_40029e4a`) | decompiled |
| `FUN_4012e052` (144 B) | `make_shared<MachineListView>` wrapper; calls the constructor `FUN_4002a0ba(view + 4, byte, track, machine)` | decompiled |
| `FUN_4002a0ba` (1022 B) | **`MachineListView` constructor.** Calls the base constructor `FUN_400ba262` and the list-widget init `FUN_400b4aea(list, 6, 1, 0x35, −1)`, wires the callbacks, runs the enum builder `FUN_4002295c` and the add loop, and wires navigation (`FUN_4008d8d6`); items are 0x54 B each. Pre-highlight bound `machine + 1 < 5` at `0x4002a36c` (`moveq #4,%d4`). In this build the visible-count argument (the `pea` at `0x4002a0f2`) is 4 instead of 6 | decompiled + objdump |
| `FUN_4002295c` (142 B) | **Machine-list enum builder**: allocates 0x10 B (4 ints) and pushes {0,1,2,3} into a `std::vector`. In this build it allocates 0x14 B and pushes {0,1,2,3,4}; emulating the edited function shows it adding 5 items ([emulator_runs.md](emulator_runs.md)). This change alone is not enough, because of the fixed four-row UI | decompiled + emulated |
| `MachineListView::vfunc_4` @`0x40029b6e` (732 B) | **Paints the list.** The row loop stops at 4 rows (`iVar10==4`); item = scroll offset + row (`vfunc_38`). An inverted box marks the assigned machine (`view + 0x1a0`) and a border marks the cursor (`vfunc_3c`). Rows sit at y = 47/35/23/11 (12 px pitch) and fill the 64 px screen. It is also the second caller of `FUN_40029804`, which it uses only to spot a change of group between consecutive rows (it draws `FUN_400b5134` on a change), never as an index, so widening that mapper's range does not affect it. ✅ picker unchanged on the test unit after the mapper widening | decompiled |
| `MachineListView::vfunc_2` @`0x4002a5a4` (232 B) | Key navigation: UP/DOWN (keys `0xe`/`0xf`) → `FUN_4012dcf6(view + 0x1d4, ±1)`; YES and the rest → `FUN_40029ef2` (the commit). In this build the cursor-move call goes to the POLY voice pool's navigation pad `FUN_400b21b0`, with list = `view + 0x4c` | decompiled |
| `MachineListView::vfunc_17` @`0x4002a69e` | Encoder navigation: `FUN_400b1726` (accelerated delta) → `FUN_4012dcf6(view + 0x1d4, delta)`. In this build it is rerouted the same way as `vfunc_2` | decompiled |
| `FUN_4012dcf6` (34 B) | **The cursor move that clamps the stock menu.** It calls the `std::function` at `view + 0x1d4`: it throws `std::bad_function_call` if that is empty (`if (*(p+8)==0) throw std::bad_function_call`), otherwise it calls `p + 0xc`. Its lambda is wired by the base class, is shared with `SampleListView` / `MultiChoiceWindow`, cannot be resolved statically, and caps the cursor at index 3. ✅ On the test unit, with 4 visible rows, the list showed a scrollbar, so it held at least five items, yet the fifth could not be reached. In this build the MACHINE menu does not call it | decompiled + test unit |
| `FUN_40029e4a` (168 B) | Selection wiring after the build: `view+0x1b4 = machine; if (machine+1 < 5) list->vfunc_8(machine)`, which is a `machine < 4` bound on the initial highlight | decompiled |
| `FUN_40078df4` (long) / `FUN_40078e14` (short) | **Machine-name accessors**, UI only: `if (type < 4) return (&PTR_s_ONESHOT_4018fc2c)[type*2];` (the long one, decompiled), else "ERROR" (long) or a default (short). The long one uses `lea 0x4018fc2c`, the short one `addil #0x4018fc30` (= table + 4). Together with the name callback `FUN_40029a76` they are the only code that turns a machine type into its on-screen name, so there is no second renderer to patch. The third accessor, `0x40078e34`, computes its default and is untouched. In this build (POLY voice pool) both bounds admit type 4 and both read a relocated 10-pointer table at `0x400b20ac` (long) / `0x400b20b0` (short), in pad `FUN_400b20ac`, with the string `POLY` at `0x400b20d4` | decompiled + objdump + string-reference scan; ✅ confirmed on the test unit (the menu shows POLY) |
| machine-name table `0x4018fc2c` | Four machines × {long, short} pointers, 32 B: ONESHOT/SAMP, WERP/WERP, REPITCH/PTCH, SLICE/SLIC. It is followed directly by the page-order list at `0x4018fc4c`, with no slack, so a fifth name needs a relocation. Read only by `FUN_40078df4` (+0, long) and `FUN_40078e14` (+4, short) | hexdump + string-reference scan |
| `KitVoiceMuteConfigMenuView::vfunc_2` @`0x400b4ba6` (168 B) | List add, shared list widget: appends unconditionally (grow or push) with no machine-validity gate, so a fifth item is added | decompiled |
| `KitVoiceMuteConfigMenuView::vfunc_3` @`0x400b43d4` | Item count = `(list[8] − list[4]) >> 3`, a pure count with no bound. `vfunc_16` @`0x400b460e` = `list[0x1c]` (max visible, 6); `vfunc_14` / `vfunc_15` = scroll offset `list[0x14]` / cursor `list[0x18]` | decompiled |
| `KitVoiceMuteConfigMenuView::vfunc_7` @`0x400b4870` (284 B) | **Set selection with scroll**, shared by about 30 menus: `setSel(index, mode)` skips unselectable items (`FUN_400b482e`) and writes `list[4]` = cursor and `list[6]` = scroll offset so the item stays visible. In this build the MACHINE menu's navigation goes through it | decompiled |
| `FUN_400b482e` / `FUN_400b480e` | Is-selectable = 0 ≤ index < `FUN_400b480e(list)`, which tail-calls `list->vfunc_3()`, the live item count. ⛔ Not the clamp: selectability already allows index 4 | decompiled |
| `KitVoiceMuteConfigMenuView::vfunc_18` @`0x400b4624` | Has-scrollbar = max visible < item count (strict). A visible scrollbar with four visible rows therefore proves at least five items | decompiled |
| `FUN_400b4aea` (188 B) | List-widget init: `list[7]` = 6 (`vfunc_16`), `list[8]` = 1, reserves `param_4` × 8 B, sets vtable `PTR_vfunc_0_4019a1ec` | decompiled |

## SRC page 2: waveform view and playhead cursor

The engine publishes all eight voices' playback state continuously into on-chip SRAM, and the stock UI reads just
one of them, the selected track's. Nothing on the engine side depends on the selection. Per-voice status block
= **`0x8000edc8 + voice*0x5e`** (94 B stride, exactly 8 blocks, ending just before `0x8000f0dc`).
[Pool cursors](features/pool_cursors.md) reads all of them. The per-tick render writes them: see the rows for
`FUN_40074e84` and `FUN_400754fe` in "Per-tick sample render".

| Field | Meaning |
|---|---|
| `+0x00` | Current play position, 8.8 fixed point |
| `+0x10` | Sample length, 8.8 (`>> 8` = whole samples) |
| `+0x24` | Playing flag (byte); also decides whether a cursor is shown |

| Address | What it does | Evidence |
|---|---|---|
| `FUN_40074d40` (324 B) | **Status-block initialiser.** Calls `FUN_40074cd0(i)` for i = 0..130, then walks every block up to `0x8000f0dc` (which proves there are 8). It zeroes position and flag and sets the other fields, length included, partly from a 16 B-per-block table at `0x402db3d0`. It also zero-fills four buffers, clears the trig masks `_DAT_80001228` / `_DAT_8000122c` and initialises both fetch TCDs. Single caller: `FUN_40076b5a` (engine init). ⛔ Not a per-trig reset | decompiled |
| `FUN_40075f42` (22 B) | Accessor: raw position (`+0x00`). Caller `SamplerView::vfunc_4` | decompiled |
| `FUN_40075f58` (66 B) | Accessor: position / (length >> 8), i.e. the position as an 8.8 fraction of the sample; 0 if the length or the flag is 0. Caller `SamplePageView::vfunc_11`. **This is the cursor's data source**; pool cursors also calls it once per voice | decompiled |
| `FUN_40075f9a` (62 B) | Accessor: (position << 7) / (length >> 8), the same fraction at 15-bit scale. Caller: the ISR `FUN_40077120`. ⚠️ Purpose unknown; see [open_questions.md](open_questions.md) | decompiled |
| `FUN_40075fd8` (22 B) | Accessor: the playing flag (`+0x24`). Callers `SamplePageView::vfunc_11` and `SamplerView::vfunc_4` | decompiled |
| `SamplePageView::vfunc_11` @`0x40039a0c` (272 B) | **The UI tick that drives the cursor.** It is View slot 11 (`vptr + 0x2c`), called at 30 Hz by `ViewController::tick`. It calls `KeyboardView::vfunc_11` and checks that the page id (vfunc at `+0x6c`) is 5. It resolves the selected track (`FUN_4001ccc4(FUN_4001488e(FUN_4012198c()))`), reads position and flag, and pushes them into both waveform widgets (`FUN_400ae5de` on int index `0x71` = `page + 0x1c4`, `FUN_400ae8d8` on index `0x7e` = `page + 0x1f8`). It invalidates the page (`FUN_400ba31a`) only if `FUN_400ae54c` reports that the cursor moved. On every 12th tick (`_DAT_40609d98` counts 0..11) it toggles the slice-highlight blink (`FUN_400ae88c(page + 0x7e)`) and tail-jumps to `FUN_400ba31a(page)`. That is an unconditional invalidate outside the page-id check, so the SRC page repaints at least every 400 ms while it is shown. The base `KeyboardView::vfunc_11` @`0x400ba1c6` is a bare `rts` | decompiled + objdump |
| `FUN_400ae5de` (72 B) | **Marker-widget cursor setter.** px = (widget `+0x24`, the pixel width, × pos) >> 8 → `+0x14`; visible flag → `+0x18`; sets the dirty byte `+0x31` only if either value changed. So the position arrives as an 8.8 fraction | decompiled |
| `FUN_400ae8d8` (72 B) | The slice-grid widget's cursor setter; same shape (`+0x10`, flag `+0x14`, dirty `+0x2d`) | decompiled |
| `FUN_400ae54c` (10 B) | Reads the dirty byte, so the cursor redraw is change-driven, not done every tick | decompiled |
| `SamplePageView::vfunc_4` @`0x40039bec` (344 B) | **SRC page draw.** Clears a `0x7f × 0x34` area and draws the 8-parameter grid (4 columns × 2 rows, x += `0x1a`, y ± `0x2d`) through the page's vfunc at `+0x9c` (slot list) and `+0x94` (draw one parameter). It then calls `+0x98` and draws **one** waveform widget, chosen by machine type (table below). Marker widget = `page + 0x1c4`, grid widget = `page + 0x1f8`. It falls back to `MachineParameterPageView::vfunc_4` when `page[0x1f]` is 0 or `FUN_4008f60a(project)` is true | decompiled |
| `FUN_400ae65e` (422 B) | **Waveform widget draw, marker variant** (ONESHOT, REPITCH). Fields by int index: `[0]` waveform id; `[1]`/`[2]`/`[3]` marker x positions (`[3]` gated by byte `+0x10`); `[5]` cursor x, gated by byte `+0x18`; `[7]` x origin; `[8]` y origin; `[10]` height. It draws 120 columns of min/max envelope, then the markers, then the cursor **last**, as a 1 px XOR vertical line, and clears dirty `+0x31` | decompiled |
| `FUN_400ae958` (434 B) | **Waveform widget draw, slice-grid variant** (WERP, SLICE). Same envelope. Then it draws the `[1]` division lines evenly over `[8]` px, fills the selected slice `[2]` for `[3] + 1` slices (`FUN_400b2ed8`), and draws the `[4]` cursor x, gated by byte `+0x14`. Clears dirty `+0x2d` | decompiled |
| waveform data | One **256-byte record per waveform** at `id*0x100 + *(factory + 4)`, taken from the `SampleWaveformsFactory` singleton (`FUN_4012396e`) under a mutex (`FUN_400018ec` / `FUN_40001a0e`). It holds two parallel 120-byte min/max arrays at `+0x10` and `+0x88`, scaled by (height − 2) × v / `0xfe`. `DAT_401d13b4` in field `[0]` is the "no waveform" sentinel, drawn as a flat line | decompiled |
| drawing primitives | `FUN_400b2bc6` fill/clear rect · `FUN_400b20f8` horizontal line · `FUN_400b246a` vertical line (marker style) · `FUN_400b2ed8` filled rect · **`FUN_400b22e0(canvas, x, y1, y2, mode)`** vertical line: 5 stack arguments; `mode` > 0 = OR, < 0 = XOR (the cursors pass −1), 0 = nothing. It **clips** (rejects x < 0, x ≥ `canvas[4]` width, y2 < 0, y1 ≥ `canvas[8]` height), so a bad pixel cannot corrupt memory. It reads all five arguments into registers on entry and never writes them back, so a caller can build the frame once and patch x per call. Saves and restores d2–d6 | objdump |
| `FUN_400ba31a` (34 B) | **`View::invalidate`, not a paint.** Returns if byte `view + 0x17` is set; otherwise sets `view + 0x14` = 1 (dirty) and, if there is a parent (`view + 0x2c`), tail-jumps to `FUN_400bae5e(parent)`. It has about 186 direct call sites; it is the framework's standard "needs redraw". The siblings `0x400ba33c` (read `+0x14`) and `0x400ba346` (clear it) are reached only through vtables. The paint pass runs once, later, however many invalidates came before it | objdump |

**Per-machine widget selection and feed.** `SamplePageView::vfunc_4` picks the widget with the predicate
`(machine & ~2) == 1`, which is true for 1 and 3. `FUN_40039684` feeds it. It runs on parameter change or
encoder turn (callers `FUN_40039942`, `SamplePageView::vfunc_17`, `FUN_40039e1e`), reads the machine through
`FUN_4002af58` (its row is in "SRC page 1 layout and the machine a page shows"), resolves the sample id and feeds
one widget. It reads parameters with
`FUN_40031608(page, paramId, &flag)`, as 8.8 values (hence the `>> 8`).

| Machine | Widget drawn | What `FUN_40039684` feeds |
|---|---|---|
| 0 ONESHOT | marker `FUN_400ae65e` | `FUN_400ae52e(w, sampleId)` + `FUN_400ae556(w, p0x70, p0x71, p0x72 − 0x100, p0x72 > 0x100)` = start / end / loop / loop enabled |
| 1 WERP | grid `FUN_400ae958` | `FUN_400ae864(w, sampleId)`, `FUN_400ae89e(w, −1)` (grid −1: no division lines), `FUN_400ae8b0(w, −1)`, `FUN_400ae8c6(w, −1)`, so the widget shows only the envelope and the cursor |
| 2 REPITCH | marker | `FUN_400ae52e(w, sampleId)` + `FUN_400ae556(w, p0x80, p0x81, 0, 0)` (no loop) |
| 3 SLICE | grid | sample id; grid = `4 << (p0x8a & 0x3f)` (4/8/16/32/64); selected slice from parameter `0x88`; `FUN_40078bf4(0x89)` / parameter `0x89` → `FUN_400ae8c6` |
| 4 POLY | stock code has no case: nothing is drawn and no widget is fed | not reached in this build: `FUN_4002af58` reports the Source's machine |

| Address | What it does | Evidence |
|---|---|---|
| `FUN_40039684` (514 B) | The per-machine widget feed (table above) | decompiled |
| `DAT_4016a5d4` | 4-entry byte table, indexed by machine, giving the parameter slot that holds the sample id. Bounds-checked (machine < 4, otherwise slot 0) | decompiled |
| `FUN_400ae804` (96 B) | Slice-widget init: grid `[1]` = `0x10`, slice `[2]` = 0, span `[3]` = 1, geometry `[6..9]`, height = `FUN_4008f020()` × 10, dirty `+0x2d` = 0, highlight enable `+0x2e` = 0. Caller `FUN_40039fd0` | decompiled |
| `FUN_400ae864` / `FUN_400ae89e` / `FUN_400ae8b0` / `FUN_400ae8c6` | Slice-widget setters: sample id · grid count · selected slice (which also sets `+0x2e` = 1) · span | decompiled |
| `FUN_400ae52e` / `FUN_400ae556` | Marker-widget setters: sample id · (start, end, loop, loop enabled) | decompiled |

**Why no slice is highlighted for NOTE, RRBN and RRND.** The feed computes slice = min(Select − 1, grid − 1)
and forces −1 when the result is negative. The draw loop compares each index 0..grid − 1 with `widget[2]`, which
never equals −1, so no highlight is drawn. Select = N (1..64) highlights slice N − 1. NOTE (0), RRBN (−1) and
RRND (−2) all give −1, so nothing is highlighted. ✅ confirmed on the test unit. The `+0x2e` enable
byte plays no part here, because `FUN_400ae8b0` sets it unconditionally. SLICE round robin's negative Select
values land in the same stock clamp as NOTE, so no highlight code is patched. With a numbered Select, every
voice of a pool plays the same slice, so one highlight is correct. Pool cursors changes cursors only.

## Pool-cursor hook sites and the 30 Hz UI clock

The two widget draws share one hook shape. Both open with `lea -36(sp),sp ; moveml d2-d6/a2-a5,(sp)`, identical
36 B frames. The widget is in `a2` (from `sp@(40)`); the canvas is in `d3` for the marker widget and `d4` for the
grid widget (from `sp@(44)`). **Every widget field sits exactly 4 B further on in the marker layout than in the
grid layout**: cursor x `+0x14`/`+0x10`, visible flag `+0x18`/`+0x14`, width `+0x24`/`+0x20`, dirty `+0x31`/`+0x2d`,
x origin `[7]`/`[6]`, y origin `[8]`/`[7]`, height `[10]`/`[9]`. One routine plus a 0 or −4 pointer offset
therefore serves both. Both draws end in a 6 B dirty-byte clear that every path reaches, including the
no-waveform path: `0x400ae7f4` (`clrb %d0 ; moveb %d0,%a2@(49)`) and `0x400aeafa` (`clrb %d6 ; moveb %d6,%a2@(45)`).
Each clear is followed directly by `moveml (sp),d2-d6/a2-a5 ; lea 36(sp),sp ; rts`, so code called from either
tail may clobber d2–d6 and a2–a5 freely. In this build, [pool cursors](features/pool_cursors.md) replaces the two
6 B clears with `jsr 0x40015060` (marker) and `jsr 0x4001506a` (grid). The pad performs the displaced clear
itself. ✅ confirmed on the test unit.

The selected-track chain the tick uses: `FUN_4012198c()` (no arguments; the project/Brain object) →
`FUN_4001488e` = argument + `0x30` (12 B, simple enough to inline) → `FUN_4001ccc4(that)` (one stack argument,
returns the track; 83 callers). Call `FUN_4001ccc4` normally with one pushed argument; do not wrap it.

| Address | What it does | Evidence |
|---|---|---|
| `ViewController::tick` @`0x400bbb30` | **The view tick driver.** Locks `this + 4` and walks the circular list at `this + 0x14`, newest entry first. It calls `view->vptr[+0x2c]` (slot 11, `vfunc_11`) on every registered View, then `removeDeadViews` @`0x400bb560` if `this + 0x22` is set. The `ViewController` lives at `Brain + 64` (vptr `0x4019b458`). There is exactly one reference to it in the image: the lambda invoker `0x40007b80` | objdump |
| `Brain::Brain` @`0x4000989a`, registration @`0x40009e3a` | Registers five `Timer::addCallback` callbacks on Brain's `Timer` base (`Brain + 32`): `0x4000a45a` at 30 Hz, `0x40007b80` (→ `ViewController::tick`) at 30 Hz, `0x400097d4` at 30 Hz, `0x40008f3c` at 15 Hz, `0x40008f4c` at 30 Hz | objdump |
| `Timer::addCallback` @`0x400d9d46` · `Timer::Object::tick` @`0x400d9984` · `Timer::tick` @`0x400d9b6a` · `TimerManager::tick` @`0x400d9bf0` | divisor = 30 / hz (base rate 30); an Object fires when `++counter ≥ divisor`. ⚠️ objdump prints that divide as `remsl`: a `rems.l` form with Dw = Dx is really `divs.l` ([analysis_method.md](../../../notes/analysis_method.md)) | objdump |
| DTIM3 ISR `0x4005ef0c` (vector 99) | **The 30 Hz UI clock.** It posts the message that the Brain message pump `FUN_4000ae46` (row in "Startup, crt0 and the RTOS") handles as `case 5` at `0x4000b3aa` → `TimerManager::tick`. Setup at `0x4005ef3a`: DTRR3 = 275000, DTMR3 = `0x1D` (bus/16, restart, reference interrupt), so 275000 / (132 MHz / 16) = 33.333 ms = **30.000 Hz** | objdump |
| `FUN_40001574` | RTOS tick, PIT0: PMR = `0xa121` (41250 counts), PCSR = `0x053f` (÷32, EN/RLD/PIE), so **10.000 ms = 100 Hz** at 132 MHz. This is the scheduler tick (vector `0x40000410` = the context switcher), not the UI clock | objdump |
| bus clock 132 MHz | Literal `0x07DE2900` at `0x40002758` in the UART init `FUN_400026f2` (divider = 132e6 / (32 × baud)); also at `0x40002b5e` and `0x40068c12`. Both timers land on exact round rates, which supports it. ⚠️ Section 3 does not program the PLL/clock module, so the clock is inferred from how it is used and has not been timed on the device. A check: the slice-highlight blink should last 400 ms per state. See [hardware.md](../../../notes/hardware.md) | objdump + inference |
| `FUN_40014e4a` (322 B, 6 callers) | **Project-storage loader/validator.** Walks 127 × 2560 B records at `base + 0x310200`, the 128 × 160 B array at `base + 0x360200` and the 25088 B records at `base + 0x200`, and returns an AND-accumulated validity flag. It is the live, inlined twin of the eight unreferenced accessor overloads at `0x40015060` | objdump |
| `0x40015060 … 0x400151b4` (8 × 40–56 B) | Stock: eight `base + idx × stride + offset` accessors (strides 160 / 2560 / 25088 / 3072, bounds 128 / 128 / 128 / 16) in two overload groups, with zero references by every route. ✅ fill test on the test unit. In this build this is the 396 B landing pad `0x40015060..0x400151ec`, holding pool-cursor and MIDI Loopback code ([landing_pads.md](landing_pads.md)) | reference scan + test unit |
| `FUN_400151ec` (30 B) | Track-class helper: t ≤ 7 → 1, t = 16 → 2, otherwise 0. The first live function after that pad, and so its upper boundary | objdump |

⛔ **Vtable offsets.** The vtable address in an RTTI listing is the start of the vtable **structure**
(offset-to-top, typeinfo pointer, then the slots). The pointer an object stores is that address + 8. Ghidra's
`Class::vfunc_N` is at `vptr + 4N`. Reading slots from the structure address shifts every index by two. For
example, the View tick is at `vptr + 0x2c` (9 indirect call sites in the image), not at `+0x34` (56 sites, all in
other hierarchies). Check against a constructor's `movel #vtbl,%a?@` store: View's constructor @`0x400ba298`
stores `0x4019b064` = `0x4019b05c` + 8. Prefer named symbols and the decompiler's "also …" footer over walking
vtables by hand.

## UI bitmaps: the Bitmap object, the blit and cell graphics

A `Bitmap` is a **28 B object in `.bss`**, constructed at boot. Only its pixels are in the image. More in
[visual_assets.md](visual_assets.md).

| Field | Meaning |
|---|---|
| `+0x00` | vptr (`0x4019a194`) |
| `+0x04` | width (px) |
| `+0x08` | height (px) |
| `+0x0c` | stride = words per column = ceil(height / 32) |
| `+0x10` | plane A: the colour bits |
| `+0x14` | plane B: the mask (all ones = an opaque tile, not a sprite) |
| `+0x18` | byte, 0 |

**Pixel format.** The data is **column-major**: `width` columns, each made of ceil(height / 32) big-endian 32-bit
words, so any bitmap up to 32 px tall costs exactly 4 B per column. **Row order: row j (0 = top) → bit
(32 − height + j), so the bottom row is at bit 31.** ✅ confirmed on the test unit; the asymmetric
POLY picker icon also rendered right way up. Each plane is width × ceil(height / 32) × 4 bytes, and
plane B sits immediately below plane A, so the difference between the two pointers is a free check on the
dimensions. The canvas uses the same field layout. That is why the blit indexes canvas storage by x
(`canvas[+0x10] + canvas[+0x0c] × x × 4`), and why the framework has a fast vertical-line primitive
(`FUN_400b22e0`).
⛔ A vertical flip is invisible on symmetric artwork. All four stock machine-picker icons are vertically
symmetric (they decode identically both ways up), so they cannot reveal a flip. Use text as the control instead.
The 46 × 31 badge with plane A at `0x4020f1e4` reads "TRK" only under the correct rule, and the 46 × 31 badge
with plane A at `0x401feec8` starts with an "F". The stock NOTE keyboard is asymmetric too: black keys at the
top, solid edge at the bottom. Check that a new tile is asymmetric before trusting it as an orientation check.
⛔ A row-major reading renders every glyph transposed.

| Address | What it does | Evidence |
|---|---|---|
| `FUN_400b3b80(canvas, bmp, x, y, centre)` (376 B) | **The generic blit**: 58 callers, zero callees. It reads the bitmap only through `+0x04` / `+0x08` / `+0x0c` / `+0x10` / `+0x14` and never touches `+0x00`, so a `Bitmap` can be plain static const data with no constructor call. It clips on both axes against `canvas + 0x04` / `+0x08` and composites dst ^= (dst ^ A) & B, so plane B gates which pixels change. A non-zero `centre` subtracts w/2 and h/2. Ghidra inlines it into some callers (for example `entry_40065234`), which makes those decompiles hard to read | decompiled + objdump |
| `FUN_400f5c2c` (67.9 KB, the largest function in the image) | **UI bitmap resource constructor.** ⛔ Neither audio code nor graphics data. Runs `ctor(this, width, height, planeA, planeB)` over the whole `Bitmap` set, then a second pass at `0x4010651c` walks the same list. Objects include `0x421b9b60..0x421b9c58`, mostly `0x1c` apart, and the picker icons at `0x421b957c..0x421b991c`. ⛔ It is code, not graphics or font data. A new constructor call cannot be inserted here without shifting everything after it | objdump |
| `0x400b1f4e` | **The `Bitmap` constructor** `ctor(this, w, h, planeA, planeB)`: `+0x00` = `0x4019a194`, `+0x04` = w, `+0x08` = h, `+0x0c` = (h + 31) >> 5, `+0x10` = planeA, `+0x14` = planeB, `+0x18` = 0 (byte). This fixes the argument order as (w, h) and confirms the stride rule | objdump |
| bitmap pixel region | ⚠️ The UI bitmap planes cluster around `0x4020e000..0x4020f800`, below the start of `.bss` (`0x40214000`) and the image end (`0x4021ea40`). This region has not been surveyed for free space | address range |

**Adding an image.** A constructed `Bitmap` like NOTE would cost 136 B of `.rodata` (two 68 B planes), 28 B of
`.text` for the constructor call (5 × `pea` + `jsr`) and 28 B of `.bss`. Because the blit never dispatches
through the vptr, a new image can instead be **static const data**: a 68 B colour plane plus a 28 B struct in
`.rodata`, sharing an existing mask, with no code. That is 96 B, and it is how this build adds the robin icon.
✅ confirmed on the test unit.

### Machine-picker icons

The picker icons are **11 × 7**, not the 17 × 17 of a parameter-cell tile, and the picker's row pitch is 12 px
(`iVar12 += -0xc` in the decompile), so a 17 px tile would not fit. These sizes come from the constructor immediates and match the plane spacing
(stride 1 × 11 × 4 = 44 B = the observed `0x2c` gap).

| Address | What it does | Evidence |
|---|---|---|
| `FUN_40029804(type)` (26 B) | **Machine type → icon id.** In stock it reads a bounds-checked 4-entry byte table at `DAT_401a5133` = `01 02 03 04` (id = type + 1) and returns 0 for any type ≥ 4. ⛔ The table cannot grow in place: `0x401a5137` is the `%` of the `"%.15s"` string that draws every machine name. In this build the function is rewritten in place (18 B within its 26 B) to compute type + 1, so POLY gets id 5. Two callers: the icon callback below and `MachineListView::vfunc_4` (group separators). ✅ confirmed on the test unit | objdump + hexdump; test unit |
| `FUN_40029820(…, row)` (206 B) | **Icon draw callback.** `FUN_400b50ec(row)` → `FUN_40029804` → a switch that accepts only ids 1–4 and silently returns on anything else (a blank column, no error). Each case adds 2 to x, subtracts 1 from y, clears the centre flag, loads its Bitmap and **tail-jumps** (`jmp`) to the blit. Bitmaps: id 1 `0x421b991c` · 2 `0x421b983c` · 3 `0x421b9640` · 4 `0x421b957c`, all 11 × 7, constructed by calls whose Bitmap-address immediates sit at `0x400f81dc` / `0x400f82cc` / `0x400f890c` / `0x400f89dc` (inside the `pea` at `0x400f81da` / `0x400f82ca` / `0x400f890a` / `0x400f89da`). In this build an 8 B range test at `0x4002987a` and a 6 B `jmp` at `0x4002988a` pass the id to a 16 B lookup at `0x400aff50` (`lea TABLE,%a0 ; movel %a0@(0,%d0:l:4),%d1 ; jmp 0x40029890`) that reads an 8 B selector table in `.rodata`. Id 5 therefore draws the POLY icon (artwork and `Bitmap` struct at `0x40213bb0`, 44 B + 28 B). ✅ confirmed on the test unit | objdump; test unit |
| `FUN_40029a76(…, row)` (92 B) | **Name draw callback**: `FUN_40078df4(type)` → `FUN_400f5770(0x10, "%.15s", name)`. With the relocated name table it prints "POLY" for type 4 | decompiled |

### Parameter cell graphics

Every parameter carries its own drawer. `MachineParameterPageView::vfunc_37` draws only the cell's text. The
picture comes from a `std::function` held in the parameter's runtime record. See
[parameters.md](parameters.md) for the descriptor tables.

| Address | What it does | Evidence |
|---|---|---|
| `0x4193f1a8` | **Runtime parameter-record table**: 164 records × `0x54` B in `.bss`, in the same order as the static descriptor table at `0x4018ff88` (stride `0x34`). Layout: `+0x00` flags · `+0x04` 16 B copied from `.rodata` · **`+0x14` value-formatter `std::function`** · **`+0x24` cell-graphic `std::function`** (its non-null test is at `+0x2c` and its invoker at `+0x30`) · two more at `+0x34` and `+0x44` | decompiled |
| `FUN_40065550` (30 B) | Record accessor: `0x4193f1a8 + idx × 0x54`; an index ≥ `0xa4` falls back to record 0 | decompiled |
| `FUN_400655aa` (58 B) | The popup's formatter call; reads record `+0x14` | decompiled |
| `ParameterSet::vfunc_23` @`0x4000edd4` (104 B) | **Cell-graphic dispatch**: if record `+0x2c` is set, call record `+0x30`. Shared by `SoundParameterSet`, `FxParameterSet`, `TrigParameterSet` and `MidiParameterSet`; `vfunc_37` reaches it as `paramSet->vfunc` at `+0x5c` | decompiled |
| `ParameterSet::vfunc_22` @`0x4000ee3c` (144 B) | Sibling at `+0x58`: formats the value into `vfunc_37`'s local buffer | decompiled |
| `FUN_4013b6a2` (19,842 B) | **The builder** (formatter / UI-object init): fills all 164 records and every `std::function` slot, unrolled, and builds the formatter objects at `0x4193exxx` (bound through `FUN_4013b38e`, row in "Runtime parameter system"). To find a parameter's drawer, find its `FUN_4013b3c0(<record> + 0x24, &DAT_…)` call; that object's `+0x0c` is the drawer address. To confirm you have the right record, check that its `+0x14` formatter is the expected one (in stock, Slice Select's is `entry_4005f7b0`). This method was checked on a known case: Slice Select's functor `0x4193e1c8` yields `0x40065234` at `0x4013c32c` | decompiled + objdump |
| `entry_40065234` (186 B) | **SLICE Select cell drawer** (record 136). It fetches the range of parameter `0x88` (`FUN_40078bf4`), clamps negative values to 0 with a `not`/`add`/`subx` mask, and clamps to the maximum. Value ≤ 0 → it blits the NOTE bitmap and returns. Value ≥ 1 → it blits the slice-ruler bitmap `0x421b9b60` (17 × 17) and prints the number itself (`FUN_400b39ec`, value >> 8). In this build `0x40065264` jumps (`jmp`, not `jsr`, so `%sp` stays put for the replayed `movel %sp@(20),%d1`) to SLICE round robin's 28 B **robin selector** at `0x400aff34`. The selector hands its choice back in `%a1` at `0x400652d6`, where the stock NOTE-bitmap load becomes `movel %a1,%d0`. It needs no arithmetic, because at `0x40065264` the stock code has already set `%d0` = −1 for value ≥ 0 and 0 for value < 0. Result: value 0 shows NOTE, and values < 0 (RRBN, RRND) show the robin. ✅ confirmed on the test unit | objdump; test unit |
| `0x421b9c3c` | **The NOTE bitmap**: 17 × 17 px (11 × 11 artwork, a three-white-key keyboard; the mask is all ones). Planes: colour `0x4020f738`, mask `0x4020f6f4`, 68 B each. Constructed at `0x400f5e62` | objdump |
| CHAN's record | Descriptor 140 at `0x40191bf8` (page 12, slot 17, id `0x78`, max `0xf00`); runtime record `0x41941f98`; cell-graphic functor `0x4193e458`, installed at `+0x24` from `0x4013f1de`; that functor's `+0x0c` is the drawer. The index is cross-checked two ways: MIDI Loopback's widened minimum sits at record 140 `+0x08` (`0x40191c00`), and slot 17 matches the enable-mask bit index (bit = slot − 17) | objdump + hexdump |

⛔ A formatter patch cannot change the number printed in a cell graphic, because cell drawers print their own.
Patch the drawer, found through `FUN_4013b6a2` as described above. The drawer shared by MIDI CHAN, BANK, SBNK
and PROG, `0x40062fc4`, has its row in "The CHAN and TRK display paths".

## Encoder → parameter write chain

Traced end to end for MIDI CHAN (descriptor record 140 = `0x8c`, slot 17; its `+0x20` id is `0x78`). The
`paramId` passed along the chain is the record index. The `ParameterSet` functions are
shared by the Sound, Fx, Trig and Midi parameter sets. The chain enters through the page's encoder handler
`MachineParameterPageView::vfunc_17`, the page write entry `vfunc_22` and the delta applier
`ParameterSet::vfunc_11`; their rows are in "Parameter read and write paths". From there it continues here.

| Address | What it does | Evidence |
|---|---|---|
| `MidiParameterSet::vfunc_8` @`0x40011576` (82 B) | Special-cases records `0x37` and `0x40` (55 and 64); every other record, CHAN (140 = `0x8c`) included, falls through to the base | decompiled + objdump |
| `ParameterSet::vfunc_8` @`0x4000f022` (208 B) | **Encoder arithmetic and clamp**: new = old + delta, clamped to [min, max]. It then asks `vfunc_9` whether the value is allowed; if not, it scans up to `0x35` (53) steps in the delta's direction, then the other way, for the nearest allowed value. It returns the resolved value and does not store it. Arguments: (this, paramId, oldValue, delta). It runs before the store, with both values in hand. Because it is shared by all four sets, and slot numbers repeat between them, code acting here must test `paramId`, not the slot | decompiled |
| `MidiParameterSet::vfunc_9` @`0x40011420` (154 B) | **Permission gate and commit.** It branches on the descriptor flag `FUN_40078dd2(rec) & 0x20000`: clear → the base `ParameterSet::vfunc_9` @`0x4000f0f2`; set → a second route through the vfunc at `+0x50` with value >> 8, which is why there are two stores. To take values out of a range, make `vfunc_9` reject them; `vfunc_8`'s scan then skips them with no further change | decompiled |
| `MidiParameterSet::vfunc_25` @`0x4000fe9a` · `vfunc_26` @`0x40010b0c` | ⛔ **Not on the write path.** Both are page gates: they test the descriptor's page field against the mask `0x1a07f` (pages 0–6, 13, 15, 16) and only then delegate to the base. CHAN is on page 12, so both return 0 early. A hook here never fires for an encoder edit | decompiled |
| `MidiParameterSet::vfunc_13` @`0x4000ed3c` (152 B) | **A value store**: writes the u16 at base + slot × 2, `*(u16*)(base + slot*2) = value` (slot from `FUN_40078a68`, bounded < `0x35`), then sends a change notification carrying the slot index through the backing store's vfunc at `+0x10` (vptr `PTR_vfunc_0_401647d8`) | decompiled |
| `MidiParameterSet::vfunc_29` @`0x40010608` (190 B) | **The second value store**, same formula: `*(short*)(base + slot*2) = value`. ⚠️ There are two write sites; check every caller of the slot resolver before assuming only one | decompiled |
| `MidiParameterSet::vfunc_10` @`0x4000f8d0` (102 B) | Value read: the u16 at base + slot × 2, `return *(short*)(base + slot*2)` | decompiled |
| `MidiParameterSet::vfunc_16` / `vfunc_17` / `vfunc_18` | Resolve a slot but never store: the min / max / default accessors | decompiled |
| `FUN_40078a68(rec)` (34 B, 37 callers) | **Record index → slot**: descriptor record `+0x04`, read as `(&DAT_4018ff8c)[(rec & mask)*0xd]` (stride `0xd` words; the mask sends an index ≥ 164 to record 0). CHAN (record 140) → slot 17 | decompiled + objdump |
| `FUN_40078bf4(rec)` (56 B, 32 callers) | **Min/max fetch**: a thunk to `FUN_400d7e64` that copies the range from `0x4018ff90 + rec × 0x34` into the caller's out-parameters, which is why its decompile looks empty. `ParameterSet::vfunc_8` takes its range from this call. ⛔ It is not an idle thunk | decompiled |
| `MidiParameterPageView` | Derives from `ParameterPageView`, not from `MachineParameterPageView` (the two are siblings). It overrides only `vfunc_0..3` (`0x4002fec4` / `0x4002ffa8` / `0x401303d2` / `0x401304a6`). As `vfunc_22` shows, shared code crosses the class boundary anyway | RTTI + decompiled |

## MIDI tracks: the send chain, the input task and the CHAN display paths

These functions are stock except where a row names a patch. [MIDI Loopback](features/midi_loopback.md) hooks two of them (the per-track channel lookup and the note byte appender). It also adds a dispatch arm to the input task and a filter on the recorder feed.

### The MIDI-track send chain

`FUN_4007011c` (the sequencer engine) calls **`FUN_4006f546`**, the MIDI trig evaluator for tracks ≥ 8, which handles up to four chord notes. The evaluator builds a timeline event with `event[0] = 3`. `event[4]` holds a note descriptor from `FUN_400d0044`, which has no NULL check. `FUN_400ddc98` inserts the event in time order. The audio ISR `FUN_40077120` pops it at `0x4007741a` and posts an RTOS message to queue `0x42175468`. **`FUN_400d0578` is the MIDI-out task.** It calls the byte appender `FUN_400d0396`, then `FUN_400cfd26`, then DIN (`FUN_400028c6`) or USB (`FUN_40004a02`).

**Note descriptor fields** (the MIDI evaluator's note object; how the evaluator fills it is in "How a trig's
note is resolved"):
- `[1]`: MIDI track 0..7.
- `[3]`: flags; bit 7 means "track sends notes".
- `[4]`: note count; the notes are at `+0x14+i`.
- `[6]`: length (table `0x40177480`); ×2 gives the note-off delay.
- `+0x1f`: velocity.
- `[8]`: note-on timestamp.
- `[10]`: 1 = sequencer, 2 = live.

**Three gates inside `FUN_400d0578`, in this order:**
1. Mute/solo at `0x400d07ce` (masks `0x421897d0` / `0x421897d4`).
2. The "sends notes" bit 7 at `0x400d0b62`.
3. CHAN at `0x400d0ca4` (`tstl %d6 ; bltw`).

So a negative channel suppresses all external output at no cost, and a hook at gate 3 inherits gates 1 and 2.

**The release is scheduled, not sent with the note.** A 24 B node (free list `_DAT_421790ac`, sorted list `_DAT_421790a8`) falls due at `desc[8] + desc[6]*2`. The task also keeps an owner table `0x439888f8[chan*0x80 + note] = midiTrack + 1` and a node table `0x421770a0[...]`. The ISR fires the node as a type-3 message, and the task emits a velocity-0 note-on at `0x400d0f7a`. ⛔ Both tables are indexed `chan*0x80 + note`, so a negative channel must never reach them.

| address | what it does | evidence |
|---|---|---|
| `FUN_400cfd6c` (52 B) | **The per-MIDI-track channel lookup.** It reads `enableMask[m] & 1` at `0x421893bc + (m + 0x108)*4` (= `0x421897dc + m*4`); if the bit is clear it returns −1. Otherwise it returns the sign-extended byte `mvs.b %a0@(0x46,%d0:l),%d0` with `a0 = 0x421893dc` and `d0 = m*0x6a`, i.e. **`0x42189422 + m*0x6a`**, the high byte of the u16 8.8 CHAN mirror. It is called only from `FUN_400d0578`, by six `jsr %pc@` sites (`0x400d06ce/0752/0896/0932/0c3a/10fa`), and every emitter re-tests the result for a negative value. ⛔ objdump prints indexed displacements in hex (extension word `0x0846`), so the offset is `+0x46`, not decimal 46 (`0x4218940a`). Decode the encoding; never trust the printed text. **MIDI Loopback:** the 6 B at `0x400cfd94` become a `jmp` to the channel hook at `0x40014cb4` (44 B). The hook returns the stock byte when CHAN ≥ 0. When CHAN is negative it returns `0x4193d70c[CHAN+8]` if that value is 0..15, else −1; the range check is mandatory because the consumer builds `0x90\|chan` and indexes by `chan*0x80`. Why the splice is exactly 6 B: `0x400cfd9a` (`moveq #-1`) is the `beqs` target from `0x400cfd86`, and nothing else references `0x400cfd94..98`. Note-off scheduling needs no extra work, because the whole stock emit block runs on the substituted channel | objdump + encoding decode; ✅ confirmed on the test unit |
| `FUN_400d0396` (48 B) | **The note byte appender.** It appends `(len, bytes)` to the buffer at `0x42176899`. The length at `0x42176898` is a **single byte** (⚠️ it can wrap if traffic is added). This is the only producer of the note byte stream: it carries note-on, retrigger release, scheduled release and panic. It is **entered only at its first instruction** (`mvz.b 0x42176898,%d1`, 6 B, absolute): 2 `jsr %pc@` (`0x400d0d10`, `0x400d0d32`) and 4 `lea %pc@(FUN_400d0396),%a3` function-pointer uses (`0x400d0872/0f22/0fc4/1052`). Nothing targets `0x400d039c`, so an entry trampoline covers every caller. At entry `sp@(4)` = len and `sp@(8)` = bytes; `d0/d1/a0/a1` are free. Its twin `FUN_400d0140` serves the CC/parameter stream at `0x42176c9a`. **MIDI Loopback:** an entry trampoline over the first 6 B leads to the note-byte hook at `0x40014ce0`. For a `0x9n`/`0x8n` whose channel an **active** Loopback route resolves to, the hook does four things. (1) It copies the 3 bytes into a 32 × 4 B ring at `0x43990300` (index byte at `0x43990380`). (2) It takes a descriptor from `FUN_400c2c60`. (3) It posts that descriptor to the private input lane (queue `0x4216a074`) with `FUN_40001b7a`, under IPL 7. (4) It wakes the input task with `FUN_40001840` on `0x4216a06c`. It skips the post when the lane's pending count (`0x4216a078`) is above 64. The route test is stateless: for each MIDI track it requires the enable bit, a stored CHAN < 0, and `0x4193d70c[CHAN+8]` equal to the note's channel. ⚠️ The copy is keyed on the **channel**, so a normal-CHAN MIDI track that sends on that same channel is copied too | objdump; ✅ confirmed on the test unit |
| `FUN_40001b7a` (78 B) | **Queue post `(queue, msg)`.** Push `msg` first (the queue lands at `sp@(4)`); the caller cleans up. It masks **IPL 7** (`movew #0x2700,%sr`) for the whole enqueue, so it is safe from any context. ⛔ **There is no capacity check:** past the ring size it silently overwrites, and the consumer reads recycled pointers. It signals the queue's own semaphore at `queue+8` | objdump |
| `FUN_40001840` | Semaphore signal. ⚠️ Required after a post to a MIDI input queue: the input task blocks on the **shared** semaphore `0x4216a06c`, not on the queue's own | objdump |
| `FUN_400c2c60` (42 B) | **The MIDI-message descriptor ring, not an allocator.** It returns `0x4395E484 + 8*i` and advances the global index `0x420b31b8`, which wraps at 1023. That gives **1024 slots** of the 8 B `{u32 length; u8 *bytes}` descriptor. There is no free list and no ownership: a producer takes the next slot and overwrites whatever was there, which is why the missing capacity check in `FUN_40001b7a` is survivable in stock. It clobbers only `d0/d1`. The read-modify-write on the index is unmasked in stock too. The MIDI Loopback note-byte hook calls this function instead of keeping a descriptor ring of its own | objdump; ✅ in use on the test unit (the note-byte hook) |
| `0x4216a0b4` / `0x4216a0b8` | The DIN input queue object and its **pending count** (`queue+4`). The MIDI Loopback rate guard reads the same field of the private lane, `0x4216a078` | objdump |
| `FUN_400c465e` | **The MIDI input task.** It binds three ports via `FUN_400c31d8`: DIN `0x4216a0b4`, USB cable 0 `0x4216a094` and **USB cable 1 `0x4216a074`**, all on the shared semaphore `0x4216a06c`. It works round-robin, one message per port per pass. Dispatch goes through table **`0x4019b750`**, indexed by `status >> 4`, as `handler(bytes, length, origin)`. The task stamps an **origin tag** chosen by queue: DIN `0x08` (`0x10` when the channel equals the auto channel `0x4193d708`), USB `0x02` / `0x04`, local pads `0x40`. Stock never uses `0x01` or `0x20`. Gates: INPUT FROM at `0x4193d760` (bit 0 DIN, bit 1 USB; `0xF0` bypasses it), and RECEIVE NOTES at `0x4193d784` further downstream | objdump |
| port-2 arm @`0x400c47ce` (18 B) | In stock it **receives and discards**: `pea queue ; jsr recv ; movel %d0,%sp@(48) ; addql #4,%sp ; braw`, with no dispatch and no free. It runs only when its queue is non-empty (`blew` at `0x400c47ca`). No host can address USB cable 1 in any USB CONFIG mode (see below), so in stock the queue has no producer. **MIDI Loopback (the private lane):** the first 6 B become `jmp 0x40015186`, a 50 B arm that mirrors the DIN arm (`0x400c46fe..0x400c4750`) instruction for instruction, with three differences: (a) it uses queue `0x4216a074`; (b) it has no INPUT FROM gate, because no gate bit exists for port 2 (RECEIVE NOTES still applies downstream); (c) it stamps a fixed origin tag **`0x20`**. `0x20` is outside the "playable" origin set `{0x04,0x10,0x40,0x80}` tested by `FUN_400c53c6`, so an internal note cannot inherit a held trig's Sound lock. The splice ends at `0x400c47d4`. `0x400c47e0` (`pea 0x4216a094`) is a live `blew` target from `0x400c46ec` and is untouched | objdump; ✅ confirmed on the test unit |
| `((int32*)0x4193d70c)[track]` | **The per-track listening channel**: 16 entries, tracks 0..15, audio tracks 0..7; `−1` = OFF. `FUN_40083b2a` writes the defaults: audio track *i* → channel *i*, MIDI tracks → OFF, and **auto channel = 9**. Read by the channel→track fan-out functions `FUN_400c5a24`, `FUN_400c58e8` and `FUN_400c4aa6`. MIDI Loopback takes the destination audio track's channel from here | objdump |
| `FUN_400c4bda` | **The recorder feed.** It is the last action of the per-track note-on sender `FUN_400c53f2`, and it has three callers. **MIDI Loopback:** an entry trampoline over its first 8 B leads to a 42 B pad at `0x400151b8`. The pad drops the event when `event[0] == 12` (the recorder message type) **and** `event+0x0c == 0x20` (the private lane's origin). By then the voice has played and the MIDI has gone out, so dropping the event can only stop the recording | ✅ confirmed on the test unit: routed notes are not recorded; USB MIDI and the Digitakt's own pads still record. ⚠️ DIN input and grid recording were not tested |

### The CHAN and TRK display paths

A parameter value reaches the screen by **three separate routes**: the grid-cell label, the encoder popup, and the number drawn inside the cell graphic. None of them calls the others. The CHAN descriptor is record 140 at `0x40191bf8` in the 164 × `0x34` table at `0x4018ff88` (page 12, slot 17, max `0x0f00`). MIDI Loopback widens its min at `0x40191c00` from `00000000` to `fffff800` (−8.0 in 8.8); stored values −8..−1 mean audio track 1..8 (`s + 9`). See [parameters](parameters.md).

| address | what it does | evidence |
|---|---|---|
| `FUN_4000f9a2` / `FUN_4000f9c4` | The descriptor's **short name (`+0x30`)** and **long name (`+0x28`)** accessors, at `0x4018ff88 + id*0x34`. Each is fetched once per use. The short name is fetched by the call at `0x40030466`, inside `MachineParameterPageView::vfunc_37` (`0x400302c8`); there the raw value is live at `sp@(100)`. The long name is fetched by the call at `0x40032816` in the encoder popup, which is built with the format string `"%s=%s"` at `0x401a5dab` inside `SoundPageView::vfunc_17` (`0x40032542`). That function is also the MIDI page's encoder handler. ⛔ No code holds a name string as an immediate. **MIDI Loopback:** both calls go to pads in the landing-pad span at `0x40015060..0x400151ec` (`0x40015134` and `0x40015154`). For CHAN (record 140) with a negative value, the pads return the existing strings `"TRK"` (`0x401b2401`, the tail of "LOAD TO TRK") and `"Track"` (`0x401aea03`); otherwise they tail-jump to the stock accessor. The popup pad repeats the stock value fetch (`(**(*a2+0x54))(a2, d2, -1)`), because the raw value is no longer live at that point | objdump; ✅ confirmed on the test unit |
| value formatter @`0x40065652` | The stock body adds 1.0 to the stored value and renders it as a plain number. **MIDI Loopback:** only the first instruction is replaced, by a call that returns the addend: +1.0 as in stock, or +9.0 when the value is negative. The formatter is shared with SLICE's Slice Length and MIDI Bank/Program; all of those have min 0, so they never reach the negative branch | objdump; ✅ confirmed on the test unit |
| cell-graphic drawer `0x40062fc4` | **The third route: the cell drawer shared by MIDI CHAN, BANK, SBNK and PROG** (the TRK number display). It is a per-parameter `std::function` in the runtime record (see "Parameter cell graphics"). CHAN's runtime record is `0x4193f1a8 + 140*0x54 = 0x41941f98`; the drawer is installed at `+0x24` from functor `0x4193e458`, whose `+0x0c` holds it (`movel #0x40062fc4,%d0` at `0x4013bd0a`). The drawer paints the dial and prints its own number without consulting the formatter at record `+0x14`: it takes the value from `sp@(20)`, shifts it right by 8, pushes (value >> 8) + 1 (its `pea %a0@(1)` is the +1) and calls `0x400b37e0`. It is **shared by four page-12 records**: 140 CHAN (slot 17), 141 BANK, 142 SBNK and 147 PROG. Exactly one pointer to it exists in the image (`0x4013bd0c`), so that is the whole blast radius. The same lookup applied to Slice Select's functor `0x4193e1c8` yields `0x40065234`. **MIDI Loopback:** the 6 B at `0x40062fde` (`moveal %d0,%a0 ; pea %a0@(1)`) become a `jmp` to a 16 B pad at `0x400aff60`: `tstl %d0 ; bges ; addql #8,%d0 ; addql #1,%d0 ; movel %d0,%sp@- ; jmp 0x40062fe4`, where the `bges` skips the `addql #8`. So it adds 9 to a negative value and 1 otherwise. `d0`/`a0` are dead at the site, and nothing branches into the window. BANK/SBNK/PROG have min 0 (only CHAN is widened, to −8 for TRK1–TRK8), so the negative branch cannot be reached for them | objdump; ✅ confirmed on the test unit; BANK, SBNK and PROG are unchanged at their minimum |

### USB descriptors and MIDI cables

There are six descriptor sets, at `0x402131xx..0x402139xx`. One configuration, the class-compliant audio one (MS bulk endpoints at `0x402134fb` / `0x4021350a`), declares **`bNumEmbMIDIJack = 2`**; every other declares 1. The device nevertheless exposes **one MIDI port in all three USB CONFIG modes** (Overbridge, USB MIDI, USB AUDIO/MIDI): ✅ confirmed on the test unit. So USB cable 1 cannot be reached from any host, which is what makes queue `0x4216a074` a private lane.

⛔ Do not infer a mode's cable count from the descriptors.

⚠️ Host hazard: all modes share one product string. A computer's MIDI registry can therefore create a second, stale entry ("… #2"), and after a mode switch the device can be invisible to Transfer and to a DAW until the entries are renamed or the computer is restarted.

## FUN_4006f882: one sequencer step for one track

⛔ `FUN_4006f882` does **not** loop over the 16 tracks. It takes the track as `param_1` (guarded by `param_1 < 0x10`), and the loop is in its caller. It derives these per-track bases:
- `_DAT_4195fae8 + track*0x38f` (the playing pattern's track block);
- `track*0x4c + 0x421ba904`;
- `track*0x30 + 0x421bb504`;
- `track*0xdc`.

Its audio/MIDI dispatch works as follows (decompiled):
1. It **first** writes the step and phase: `DAT_4195fbbb[track]` and `DAT_4195fbcb[track]`.
2. For `track < 8` it calls **the audio trig evaluator `FUN_4006f1be`** (track, pattern, …, step, …, out). Otherwise it calls the MIDI evaluator `FUN_4006f546`.
3. The evaluator's return value is used. A zero result clears the `+4` field of a per-track structure; a non-zero result stores the out value into `DAT_421badc4[…]`.

Ghidra decompiler output (excerpt, some variables renamed), `FUN_4006f882`:

```c
(&DAT_4195fbbb)[track] = step;  (&DAT_4195fbcb)[track] = phase;   // step advance happens first
if ((int)track < 8) {                                              // audio vs MIDI dispatch
    *(iVar12 + 0x44) = …;
    result = FUN_4006f1be(track, pattern, …, step, …, &local_4);   // the audio trig evaluator
} else { … FUN_4006f546(…) … }                                     // MIDI tracks
if (result == 0) *(iVar12 + 4) = 0; else DAT_421badc4[…] = local_4;
```

Consequences for code at this site:
- The track's sequencer position advances whether or not the evaluator runs.
- The caller reads the evaluator's result, so "no trig" is a zero result, not a skipped call.

This runs once per sequencer step, not once per audio tick. No feature in this build patches it.

## Parameter read and write paths

Every parameter page reads and writes values through **two shared virtual slots**. `MachineParameterPageView::vfunc_21` reads and `::vfunc_22` writes. The decompiler lists each as the single implementation behind `SoundPageView`, `LfoPageView`, `AmpPageView`, `FilterPageView`, `SamplePageView`, `ParametersSeqNoteView` (the TRIG page) and `ParameterPageView`; `FxPageView` and `MasterPageView` call them too. Both funnel through `vfunc_41`, and from there into the parameter-set resolver.

The **stored value of a sound parameter is at `sound + 0x14 + slot*2`** (u16 per slot). `(0x7e − 0x14) / 2 = 0x35` = 53 slots, which matches the `slot < 0x35` bound in `vfunc_13`, `FUN_40078c2c` and `FUN_40078a68`. The array runs right up to the machine byte at `+0x7e`. It is also the range a sound load copies into the voice mirror.

| address | what it does | evidence |
|---|---|---|
| `MachineParameterPageView::vfunc_22` @`0x40030e2a` (200 B), args `(page, paramId, delta, flag, out)` | **The parameter write entry for every page.** A single implementation serves many classes: the decompiler lists it as *also* `ParameterPageView::vfunc_22`, `SoundPageView`, `LfoPageView`, `AmpPageView`, `FilterPageView`, `SamplePageView` and `ParametersSeqNoteView`, so it is on the MIDI page's write path too (⛔ sibling classes do not imply separate code paths). It resolves the current track **once**, as `FUN_4001ccc4(FUN_4001488e(FUN_4012198c()))`, via the call at `0x40030e5a`. For `paramId == 10 && track < 8` it runs a track-level special case; the guard is `moveq #7,d1 ; cmpl d2,d1 ; blt` at `0x40030e6e`. It then gets the parameter set from `this->vfunc_41(paramId, track)`; in stock that track is pushed as the literal `pea 0xffffffff` at `0x40030eb8`, meaning "use the current track". Finally it calls `paramSet->vfunc_11(paramId, delta, track, flag, out, 1, 1)` (`+0x2c`). It applies a **delta, not an absolute value** (computed by the accelerated encoder helper `FUN_400b1726`), so the read-modify-write happens on the resolved set's own value. **POLY voice pool:** three edits. (1) The `jsr FUN_4001ccc4` at `0x40030e5a` calls a 38 B pad at `0x400aff0e` instead. The pad re-pushes the caller's stack argument, calls `FUN_4001ccc4`, and maps the result through `groupSource[]`, returning −1 for anything outside 0..7. (2) `pea 0xffffffff` at `0x40030eb8` becomes `movel %d2,%sp@- ; nop` (`2f02 4e71`), which pushes that mapped track. (3) The guard at `0x40030e6e` changes from `blts` to `blos` (`6d46` → `6546`), an unsigned compare, so the −1 skips the `paramId == 10` branch; for every stock value (0..0x10) the signed and unsigned tests agree. For the parameter-set lookup the `vfunc_41` alias below already covers writes. These edits are consistent with it, because an explicit track passes through that pad unchanged and `groupSource[S] == S`. ⚠️ The `paramId == 10` branch is not covered by that alias: with the pad it acts on the Source, while `vfunc_21`'s read of that parameter uses the unmapped track; whether a POLY track's pages reach it is not traced ([landing_pads.md](landing_pads.md)). On a MIDI track the pad returns −1, the same "current track" value stock pushes, so MIDI-page edits take the stock path through `vfunc_41` and `FUN_40018cec` | decompiled; ✅ confirmed on the test unit |
| `ParameterSet::vfunc_11` @`0x4000fb3e` (236 B) | **The delta applier, the ParameterSet write entry**, shared by `SoundParameterSet`, `FxParameterSet`, `TrigParameterSet` and `MidiParameterSet`. It reads the current value first, through the object (`vfunc_10`, `+0x28`), and passes it with the delta to `vfunc_8` (`+0x20`), so the previous value is an argument on the write path and no latch is needed to know it. Two arms: when runtime record `+0x4c` is non-null it calls that record's `+0x50` functor; otherwise it takes the normal `vfunc_8` path ("Encoder → parameter write chain"). **Its `track` argument (`param_4`) is read in neither arm.** ⛔ Remapping the track passed to `vfunc_11` changes nothing; the parameter-set **object** decides which track is edited, so the remap belongs where that object is resolved | decompiled; ✅ confirmed on the test unit |
| `MachineParameterPageView::vfunc_21` @`0x40030d4a` (224 B), args `(page, paramId, lockIdx)` | **The parameter read entry for every page.** For `paramId == 10` it returns a track-level value (`FUN_4001f7a8`). When `lockIdx >= 0` (a Sound lock is active) and the page id is not 1 or `0xb`, it resolves the locked Sound with `FUN_400148a6(project, lockIdx)`, gets `slot = FUN_40078a68(paramId)` and returns `*(short *)(sound + 0x14 + slot*2)`. Otherwise it calls `vfunc_41(paramId, -1)` (its own call site, `0x40030dc6..0x40030dd4`) and then `paramSet->vfunc@+0x28()`. Here `d2` is the paramId, and no register holds the track. ⚠️ It is on the **draw path** (`FUN_40031608` ← every page draw), so a fault in it can appear at the first screen draw; see [startup_hooks.md](startup_hooks.md) for the hooks that run at startup | decompiled |
| `MachineParameterPageView::vfunc_41` @`0x40030d22` (40 B) | A thin shim, `FUN_40018cec(FUN_4012198c(), paramId, track)`, ending in a tail `jmp` at `0x40030d44`. All page subclasses share it, including the FX and master pages. **POLY voice pool (the "transparent window"):** the tail `jmp` goes to a 58 B pad at `0x400afe46`. An explicit track passes through untouched, with no calls made. For −1 the pad computes `t = FUN_4001ccc4(FUN_4001488e(project))` and writes `groupSource[t]` into the track stack slot **in place** when 0 ≤ t ≤ 7; otherwise it leaves −1, so MIDI tracks, the master and the FX pages stay on the stock path. Then it `jmp`s on to `FUN_40018cec`. No return address is pushed, so the frame cannot shift. Because both `vfunc_21` and `vfunc_22` come through here, a POLY track shows and edits the Source's values. ⚠️ On the boot/draw path | decompiled; ✅ confirmed on the test unit |
| `FUN_40018cec(project, paramId, track)` (140 B) | **The parameter-set resolver.** When `track > 0x10` it substitutes the current track (`FUN_4001ccc4(project+0x30)`); callers pass −1 to mean "current". It then picks the set by parameter class: `FUN_40078b86(paramId)` → `FUN_400157ac`, else `FUN_40078b56(paramId)` → `FUN_4000ccf6`, else `FUN_4000ccb4`. It has exactly **2 callers**: `MachineParameterPageView::vfunc_41` and `KeyboardView::vfunc_17` | decompiled |
| `FUN_4000ccb4(project, track)` (66 B) | Indexes the parameter-set array. **Audio tracks 0–7 → `project + 0x6a0 + track*0x14`**; tracks 8–15 → `project + (track + 0x12f)*8`. `track < 0` is treated as 0, and `track >= 0x10` is clamped to `0xf`. So passing a real 0–7 resolves **that** track's set | decompiled; ✅ behaviour confirmed on the test unit |
| `SoundParameterSet::vfunc_13(set, paramId, value, track, flag)` @`0x40010ba0` | **The absolute setter.** `slot = FUN_40078a68(paramId)`, which requires `0 <= slot < 0x35`. If `flag` is set it first calls `FUN_400159f8(project, track, paramId)` (parameter-lock creation). It then calls `FUN_40076ee8((short)value, track, slot)`, the live push into the voice mirror, and finally the global notifications `FUN_40121ca4` and `FUN_4008c672` | decompiled |
| `FUN_400159f8(project, track, paramId)` (186 B) | **Parameter-lock creation, not a value store**: it never receives the value. It is gated on `FUN_4008f2e0(project)` (record / held-trig mode). It resolves the pattern manager `FUN_40015696`, then the track manager `FUN_40012512(patMgr, track)`, queries `FUN_40023a2a` / `FUN_40023a86`, and places the entry with `FUN_40025b6a(trackMgr, step, 1)`. Callers: `SoundParameterSet::vfunc_13` / `vfunc_29` and `MidiParameterSet::vfunc_29`. It takes the **same `track`** as the value write, so a lock follows the value to whichever track the write resolved to | decompiled |
| `FUN_40031608(page, paramId, &flag)` (232 B) | **The "value to show" resolver.** If `FUN_4008f0a0(project)` is set, it iterates held trigs via `FUN_4008f980` and a lambda (`FUN_40030722` / `FUN_40030106`) to find a parameter-locked value. If none is found it falls back to `page->vfunc_21(paramId, x)`. It has 10 callers, one per page draw path, and values are 8.8. ⚠️ Very likely `ParameterPageView::getParamValueToShow(logicalParamID_t, bool&)`: the RTTI name exists and both the signature and the behaviour match, but no symbol ties the two together | decompiled + RTTI name |
| `FUN_400b1726(encHandler, …)` | **The accelerated encoder delta**, the same helper the machine list uses for navigation | decompiled |
| `SoundPageView::vfunc_17` @`0x40032542` | Computes the delta from the page's `EncoderHandler` sub-object at `page+0x20` and returns early if it is 0. Otherwise it dispatches to `vfunc_22` (apply the delta) or `vfunc_23`, then re-reads via `vfunc_21`. It is also the MIDI page's encoder handler | decompiled |
| `MachineParameterPageView::vfunc_17` @`0x4002aefe` (84 B) | The page encoder handler shim; slot 17 is the encoder entry on a page view (the same holds for `SamplePageView::vfunc_17`). It passes to `SoundPageView::vfunc_17` behind a `FUN_4008f60a` / `FUN_400b144e` mode gate | decompiled + function table |
| `FUN_40039e58(page)` (130 B) | Returns a **Sound-lock index**. `SamplePageView::vfunc_39` branches on `< 0x80`: below that it resolves the locked Sound via `FUN_400148a6(project, idx)`; otherwise it uses the current track's Sound via `FUN_4001ccc4` + `FUN_4000d2d6`. `FUN_4002af58` has the same two-branch shape | decompiled |

The stock write path, as two excerpts. Condensed from Ghidra decompiler output (virtual calls written as
method calls, `…` for lines left out), `MachineParameterPageView::vfunc_22` @`0x40030e2a`:

```c
track = FUN_4001ccc4(FUN_4001488e(FUN_4012198c()));      // the current track, resolved once
if (paramId == 10 && track < 8) { … track-level param-10 special case … }
paramSet = page->vfunc_41(paramId, -1);                   // -> FUN_40018cec
paramSet->vfunc_11(paramId, delta, track, flag, out, 1, 1);   // +0x2c: applies the delta
```

Ghidra decompiler output (excerpt, some variables renamed), `ParameterSet::vfunc_11` @`0x4000fb3e`:

```c
// (paramSet, paramId, delta, track, flag, out)   <-- `track` is param_4
pcVar1 = *(code **)(*paramSet + 0x20);                       // vfunc_8  = apply
uVar3  = (**(code **)(*paramSet + 0x28))(paramSet, paramId); // vfunc_10 = read the current value
(*pcVar1)(paramSet, paramId, uVar3, delta);                  // read and write go through the object
```

### The parameter-page classes (from the RTTI walk)

`ParameterPageView` is the base class, with 52 virtual functions (View | Observer). `MachineParameterPageView` (52) derives from it. Per the RTTI walk, these derive from `MachineParameterPageView`, with 52 each: `SamplePageView` (the SRC page), `SoundPageView`, `ParametersSeqNoteView` (the TRIG page), `AmpPageView`, `FilterPageView`, `FxPageView` and `MasterPageView`.

`MidiParameterPageView` derives directly from `ParameterPageView`, as a **sibling** of `MachineParameterPageView`, and overrides only `vfunc_0..3` (its row is in "Encoder → parameter write chain"). ⚠️ Shared code crosses the class boundary: the implementation behind `vfunc_22` also serves the MIDI page. So sibling classes do not imply independent code paths.

**Vtable slots used by the page draw:**
- `+0x9c` = slot 39 (slot → paramId).
- `+0x94` = slot 37 (draw one parameter).
- `+0x98` (post-grid draw).
- `+0x68` / `+0x6c` (page id / mode).

`AmpPageView`, `FilterPageView`, `MasterPageView` and `ParametersSeqNoteView` route `vfunc_39` through the base `MachineParameterPageView::vfunc_39`: a generic table via `FUN_4006556e`, bounded `param_2 < 9`. They are therefore machine-independent.

**RTTI-named members** (names recovered from lambda type-info):
- `MachineParameterPageView::getMachineTypeToShow`, which is `FUN_4002af58`;
- `…::consumeKeyEvent` (`vfunc_2`);
- `…::showAndUpdateMachineList`;
- `ParameterPageView::getParamValueToShow(logicalParamID_t, bool&)`;
- the parameter-lock trio `createParameterLock`, `changeParameterLockValue` and `removeParameterLocks`;
- `…::showAndUpdateModDestList`;
- `SamplePageView::showAndUpdateSampleList` and `getFirstSoundSlotLock`.

Enum and typedef names also appear: `Digitakt::machineType_t`, `Digitakt::synthParams_t`, `paramPageID_enum`, `logicalParamID_t`, `keyId_enum` and `Digitakt::trackID_enum`.

| address | what it does | evidence |
|---|---|---|
| `LedManager` (typeinfo `0x40162cc4`) | StaticSingleton \| Timer \| noncopyable, with 1 primary virtual function. ctor/dtor `FUN_40121746` / `FUN_40121752`; `LedManager::vfunc_0` at `0x40121710`; vtables `PTR_vfunc_0_40162e4c` / `40162e5c` / `40162e6c`. It is Timer-driven | RTTI + decompiled |
| `LedHandler` (typeinfo `0x40167e1c`) | The LED mixin, with 3 virtual functions (`0x4012d89e` / `0x4012d8ac` / `0x4012d8aa`, ctor/dtor shims). It is mixed into about 20 views, including `QuickMuteMenuView`, `TrackSelectionView`, `PatternGridView`, `KeyboardView`, `MainScreenView`, `SamplerLedView` and `TempoLedView` | RTTI |

### SRC page 1 layout and the machine a page shows

`SamplePageView::vfunc_4` draws the 8 parameter boxes in a 4 × 2 loop. It resolves each slot through `vfunc@+0x9c` (slot 39) and **skips any slot whose param id is 0**.

| address | what it does | evidence |
|---|---|---|
| `SamplePageView::vfunc_39` @`0x40039eda` (148 B), `(page, slot)` | **Slot → paramId.** It gets `machine = FUN_40021a0e(sound)`. The Sound comes from the Sound-lock branch when `FUN_40039e58(page) < 0x80` (at `0x40039f46`, via `FUN_400148a6`); otherwise it is the current track's Sound, via `FUN_4001ccc4` → `FUN_4000d2d6`. Then `layout = FUN_40065588(machine)`, and it returns `*(int *)(layout + 8 + slot*4)`. **POLY voice pool:** the `jsr FUN_4001ccc4` at `0x40039f16` in the current-track branch calls the machine-alias pad at `0x400afe80` (below). A POLY track's page 1 therefore gets the Source's layout, and so the Source's paramIds, descriptors, ranges and formatters. The Sound-lock branch is left alone, so under a Sound lock the layout follows the locked Sound | decompiled; ✅ confirmed on the test unit |
| `FUN_40065588(machine)` (34 B) | **Machine → layout record.** For `machine < 4` it returns `machine*0x2c + 0x4193edb4` (44 B stride). Otherwise it returns `0x4193ee38`, and `0x4193edb4 + 3*0x2c = 0x4193ee38`, so **the fallback is machine 3's record, the SLICE layout**. The table has no slack: `0x4193edb4 + 4*0x2c = 0x4193ee64` is exactly the base of the generic page table used by `FUN_4006556e` (19 records; an index `> 0x12` gives −1). There is no room for a fifth machine record in place. Without an alias, a machine-4 track gets SLICE's parameters on SRC page 1 | decompiled; ✅ the machine-4 fallback confirmed on the test unit |
| layout record (44 B) at `0x4193edb4 + machine*0x2c` | Seen from its other reader, `MachineParameterPageView::vfunc_23` (`0x40032004`). `+0x00` and `+0x04` are two objects passed to `FUN_401600fa` (string-shaped: the page's long and short name). `+0x08..+0x2c` hold **9 param ids**, copied as `0x24` bytes (`FUN_400d7e64(dst, rec+8, 0x24)`); an id of **0 means "no parameter"**. `0x4193edb4` is above the image end (`0x4021ea40`), so the records are in `.bss` and built at runtime | decompiled |
| `FUN_4002af58` (232 B) | **`MachineParameterPageView::getMachineTypeToShow()`: the machine this page shows.** The RTTI lambda `ZN24MachineParameterPageView20getMachineTypeToShowEvEUliRbE_` has the signature `(int, bool&)`, exactly the `FUN_4002aade` + `&local_15` lambda this function passes to `FUN_4008f980`. It tail-calls `FUN_40021a0e` (the machine byte at `sound + 0x7e`), so it returns the machine type even though the decompiler shows `void`. Two branches: a Sound-lock path (`FUN_4008f0a0` true → the lambda `FUN_4002aade` over `FUN_4008f980`, then `FUN_400148a6`) and the plain current-track path (`FUN_4001ccc4` → `FUN_4000d2d6`). It feeds **all three waveform sites**: the `SamplePageView::vfunc_4` draw, the per-machine feed `FUN_40039684`, and `FUN_40039d44`. Page 1's layout comes instead from `vfunc_39`'s own machine read. **POLY voice pool:** the `jsr FUN_4001ccc4` at `0x4002b012` (exactly 6 B) calls a 34 B pad at `0x400afe80`. The pad re-pushes the caller's stack argument, calls `FUN_4001ccc4`, and maps the track through `groupSource[]`. Its out-of-range rule is **identity**, because this value feeds `FUN_4000d2d6`, which wants a real track and clamps 0..7 itself. A POLY track therefore reports the Source's machine, and SRC page 2 draws the Source's waveform. ⛔ `FUN_4001ccc4` (83 callers) dereferences its stack argument and calls through its vtable. A pad that wraps this `jsr` without re-pushing the argument makes it read the pad's return address instead. The result is a vector-4 fault on the **first screen draw**, before the unit is usable, and recovery then needs the bootloader ([flash recovery](../../../notes/flash_recovery.md)) | decompiled + RTTI; ✅ confirmed on the test unit |

**Machine-indexed UI sites all degrade safely for machine 4:**
- `FUN_40078c2c` (`machine < 4`, else param id 0);
- `FUN_40078df4` / `FUN_40078e14` (bounds widened to `< 5` by the POLY voice pool);
- `DAT_4016a5d4` (`< 4`, else slot 0);
- `FUN_40065588` (`< 4`, else the SLICE record);
- the waveform widget-selection switch (a whitelist, so it draws nothing);
- the `FUN_40039684` feed switch (falls through).

No out-of-bounds read was found on any of them. "Safe" is not "correct", though: `FUN_40065588` degrades to a visibly wrong page. ✅ Confirmed on the test unit: without an alias, SRC page 2 of a machine-4 track draws no waveform and no cursor.

⚠️ Method: the accessor family `FUN_40075f42` / `FUN_40075f9a` shows up only in a scan for address literals; a references-in-range query misses it completely. Index-off-an-immediate access is invisible to reference queries. See [analysis method](../../../notes/analysis_method.md).

## The stock SysEx RPC command set (MidiRpc)

The stock firmware answers a SysEx request/response protocol (MidiRpc). No part of the build patches this code.

### Envelope and message header

The frame is `F0 00 20 3C 10 00 <seven-bit-encoded body> F7`. All seven framing bytes are literals in the image: the trailer `F7` at `0x4019b9dc` and the six header bytes at `0x4019b9dd`. `00 20 3C` is Elektron's manufacturer ID and `10 00` the product/unit. Body = `msgId:u16be · replyTo:u16be · cmd:u8 · payload`. The **response command is the request command `| 0x80`**; Ping, for example, is `0x01`.

| address | what it does | evidence |
|---|---|---|
| `FUN_400e0e34` | **The `MidiRpcMessage` base constructor.** It writes the 5 B header by appending `this+4..this+8`: `+4` u16 `msgId` (auto-assigned from the global counter `0x421b7958`, **skipping 0**), `+6` u16 `replyTo`, `+8` u8 **command id**. It reserves `5 + extra` | objdump |
| `FUN_400e0cd8` | The **deserialising** constructor: `msgId = (b0<<8)\|b1`, `replyTo = (b2<<8)\|b3`, `cmd = b4`, with the read cursor at `+0x24`. The exact inverse of the above | objdump |
| `FUN_400e0c50` / `0c5a` / `0c64` | The three base accessors: `*(u16*)(this+4)` (msgId), `*(u16*)(this+6)` (replyTo) and `*(u8*)(this+8)` (command id). The first two are `vfunc_2` / `vfunc_3`, shared by every message class | objdump |
| `FUN_400e0faa` | The entry parser. It returns null if `len < 5`; otherwise it wraps the bytes and builds a generic `MidiRpcMessage` | decompiled |
| `FUN_400de0dc` (10,678 B) | **The MidiRpc request factory**: `switch (cmd - 1)`, with one arm per command, each `make_shared<XRequest>(msg)`. ⚠️ The case label is `cmd − 1`, so `case 3` is `cmd == 4`. `case 0` → `MidiRpcPingRequest` | decompiled |
| `FUN_400c8d42` (3,220 B) | `MidiRpcDispatcher::handleMessageAndCreateResponse`: a chain of `__dynamic_cast` tests (`FUN_4015bd9c`), one per request class | decompiled |
| `MidiRpcDispatcher::vfunc_0` @`0x400c8c64` (182 B) | ⛔ **Not the command entry**: the complete-object destructor. It stores the class's own vtable `0x4019bc74` (typeinfo `0x4019bb38`, `MidiRpcDispatcher`) and tears down the members; `vfunc_1` at `0x400c8d28` is the deleting destructor (it calls `vfunc_0`, then tail-jumps to `0x400c4314` with the object). The dispatch is `FUN_400c8d42` (above). The RTTI names `DigitaktSysexRpc`, `elektron::MidiRpcMessage` and `MidiRpcDeviceUIDRequest` belong to this cluster | RTTI + objdump |
| `FUN_400c8bb6` | **Transmit**: it writes the 6 header bytes, wraps the stream in the encoder, serialises the body through it, flushes and writes `F7` | decompiled |
| `FUN_400f4ef0` | **The seven-bit encoder core** (`SevenBitEncoderAdapterStream`, flushed from its `vfunc_3`). It is MSB-first with 7 data bytes per group. It emits a leader whose **bit `6−i`** is the MSB of byte *i*, then the 7 stripped bytes; a partial tail of *n* bytes emits `1 + n`. ⚠️ objdump prints the indexed displacement in hex (`%sp@(29,%d1:l)` = sp+0x29 = sp+41) | objdump |
| `FUN_400c4862` | ⚠️ The SysEx entry hook. It **ignores every port index except 2 and 4**, and is registered in a 128-entry table at `0x401d1ab4` (all entries identical). Which physical interface is 2 and which is 4 has not been tested | decompiled |
| `FUN_400cc914` (9.8 KB) | ⚠️ The RPC sample-assign handler (its strings include "rpc sample assign"; MMC and heavy fixed-point code) | strings |

### The command set

The RTTI names survive in the image, so the set can be listed from the strings of section 3 that contain `MidiRpc`. There are about **61 request classes**, each with a matching `Response`. The main groups:

| group | commands |
|---|---|
| identity / status | `Ping` · `DeviceUID` · `SoftwareVersion` · `StorageSpace` |
| tempo | `TempoRead` · `TempoWrite` |
| data store | `Data{List,Copy,Move,Swap,Clear,Rename}` · `Data{Read,Write}{Open,Partial,Close}` |
| generic files | `ReadFile` · `WriteFile` · `EnumerateFiles` |
| raw FS | `FsRaw{ReadDir,CreateDir,DeleteDir,DeleteFile,RenameFile}` · `FsRaw{Read,Write}FileV1/V2` · `FsRawOpenFileFor{Read,Write}` · `FsRawCloseFile{Reader,Writer}` · `FsRawGetFileInfoFromPathV1/V2` · `FsRawGetFileInfoFromHashAndSize` |
| sample FS | the same set as `FsSample*`, plus `FsSampleAssign` · `FsSampleListRam` · `FsSampleClearRam` · `FsSampleMemoryCompaction` |
| firmware | `OsUpgrade{Start,Write,End}` |

⛔ **There is no command for keys, buttons, encoders, trigs, pads or LEDs**, and none for reading or writing arbitrary memory. The set is a companion-app API: move samples and projects, report identity and free space, push firmware. More than three quarters of it is filesystem plumbing. `TempoWrite` shows it is not read-only.

## Machine assignment, the pool map and the audio ISR

This section covers the stock functions the POLY voice pool builds on (its byte runs: [patch listing](../docs/patch_listing.md#poly-engine)), and the ISR machinery behind [voice allocation](features/voice_allocation.md), the [owner latch](features/owner_latch.md), the [tick-wipe fix](features/tick_wipe_fix.md) and [mute by origin](features/mute_by_origin.md).

**Notation:** `event[n]` is the n-th 32-bit word of a note event (byte offset `4·n`), and an event is `0x4c` B. Line numbers refer to a Ghidra decompile of `FUN_40077120` and are approximate locators only.

**Per-voice arrays (8 entries each):**
- `priority` at `0x4395ddf4`;
- `held` at `0x4395df20` (−1 = free);
- the 64-bit dirty mask at `0x4395ddb4`;
- `curSound` at `0x800019b4`.

**This build's scratch RAM** (above `.bss`, uninitialised at boot; see [memory map](memory_map.md)):
- `groupSource[8]` at `0x439902f0`;
- `groupCursor[8]` at `0x439902f8`;
- `ownerTrack[8]` at `0x439902e8`;
- `slice[8]` at `0x439902d0`;
- the round-robin counters at `0x439902b0`;
- `prev[8]` at `0x439902a4`.

### Machine selection and persistence

| address | what it does | evidence |
|---|---|---|
| `MachineListView::vfunc_9` @`0x400b452e` | Returns the current **absolute** selected index (`list[4]`, clamped to a valid entry). The POLY picker's navigation pad (`0x400b21b0`) reads it, applies ±delta, clamps and calls `vfunc_7` | decompiled |
| `FUN_40029ef2` (230 B) | The machine menu's YES/select handler. It applies `view+0x1b4` (the **pending** machine) via `FUN_4000d514`; the other branch reads `view+0x1a0` (the assigned machine). ⇒ Picker navigation must write `view+0x1b4`, or YES commits a stale machine. **POLY voice pool:** the `jsr FUN_4000d514` at `0x40029f8c` calls `0x4003771c` instead. The pad overwrites the return slot so that `FUN_4000d514`'s four stack arguments stay aligned, `jmp`s to it, and on its return rebuilds the whole pool map (sources and cursors). It then jumps back to `0x40029f92`. An assignment therefore takes effect immediately | decompiled; ✅ confirmed on the test unit |
| `FUN_4000d514` (64 B) | The apply wrapper. It clamps the track to [0,7], resolves `base + 0x60 + track*200` (the 200 B Sound object) and forwards to `FUN_40021fce` | decompiled |
| `FUN_40021fce` (332 B) | **The machine setter**: it writes the machine byte `sound+0x7e`. In stock the gate `3 < param_2` at `0x40021ff4` (`moveq #3`) rejects any machine above 3. **POLY voice pool:** `moveq #3` → `moveq #4` (`7003` → `7004`), so machine 4 (POLY) can be assigned | decompiled; ✅ confirmed on the test unit |
| `FUN_400c6e00` | ⛔ Not persistence and not an engine push. It builds an **NRPN MIDI-out** message (CC 99/98/6/38 via `FUN_400c6c68`), the outbound MIDI reflection of a parameter change; `FUN_4001e27c` returns the track's MIDI channel | decompiled |

The other machine-byte sites have their rows elsewhere: the name accessors `FUN_40078df4` / `FUN_40078e14`
in "MACHINE menu", the kit deserializer clamp in `FUN_4007a902` in "Kit load and the Project object", and the
render machine byte `FUN_40076f5a` in "Voice and trigger path".

### The pool map: build and refresh

The map is `groupSource[t] = (machine[t] == 4 && t > 0) ? groupSource[t−1] : t`, and each pool's `groupCursor` holds the last voice used. A track with no POLY followers maps to itself, so its notes stay on its own voice, as in stock. ⛔ Pool membership must read the **stable kit machine byte** (`kitbase + 0x20 + t*0xa2 + 0x7e`), never the render mirror `0x800018bc`, which every sound load overwrites.

The shared build routine sits in the pad at `0x400b026e` and has two entries:
- `mapFull` at `0x400b027e` sets the cursors to `i`, then falls through;
- `mapSrc` at `0x400b028e` sets the sources only.

Three callers enter it: the kit voice build `FUN_40076fe6` through `mapFull` at project load (its row is in
"Voice and trigger path"), the machine menu's commit `FUN_40029ef2` (above) after an assignment, and the
set-active-kit handler below through `mapSrc` on every pattern switch.

| address | what it does | evidence |
|---|---|---|
| ISR set-active-kit handler | `event[0] == 2`: it updates `_DAT_800019ac`, clears the per-track Sound-pointer table `0x800019b4..0x800019d4` and calls `FUN_40077080`. Its body is guarded by `cmpl 0x800019ac,d0 ; beqs` at `0x400773c4`. ⛔ **The kit buffer address does not change on a pattern switch** (the new pattern's kit is copied into the same active-kit buffer), so a hook inside that guard fires only at project load. Both guard branches converge on `jsr 0x400d0044` at `0x400773e6`. **POLY voice pool:** that call goes to an 18 B stub at `0x40037734`. The stub replays the call, keeps its `d0` (the handler does `moveal d0,a0` next) and calls `mapSrc`. The sources are rebuilt on every pattern switch, and the cursors keep their phase | decompiled; ✅ confirmed on the test unit |

### Audio ISR: event dispatch and the note gate

`FUN_40077120` (4280 B) is the audio ISR, and it is where every note event is handled. Events are typed by `event[0]`: 2 = set-active-kit, 3/4/5 = other types, and anything else = a note event. `event[3] == −1` means **cancelled** for every type: the note branch is guarded by `if (iVar3 != -1)`, where the decompile's `iVar3` is `event[3]`.

A **type-3** event with `[3] == −1` is handed to `FUN_400d000e` and freed, never dispatched. Any other type-3 event gets the bucket time written to `inner+0x20`, and its inner MIDI event (`event[4]`) is forwarded.

**Under the priority gate** `priority[v] <= event[3]` (around line 241), a note event takes one of three branches:
- **(a)** `(event[9] & 0x81) == 1`: a **param-only** update (bit 0 = lock, bit 7 = has a note), tested first.
- **(b)** `event[1] == 1`: **note start**. It sets `priority[v] = event[3]` and `held[v] = event[6]`.
- **(c)** Otherwise: the **note-off matcher**. It releases iff `priority[v] == event[3] && held[v] == event[6]`.

**Two note producers, two hard-coded priorities:**
- Sequencer trigs are **1** (`FUN_4006f1be` writes `event[3] = 1`).
- Live, external-MIDI and Loopback notes are **2** (`FUN_4007683c`; `FUN_400dd3a8` writes 2 as well).

So a priority-2 note passes the gate over a free or sequencer-held voice and parks it at 2. A later sequencer trig on that voice then fails the gate.

**An earlier, independent skip** (around lines 237–239, jumping to `LAB_4007795c`) drops a priority-1 event before the gate in two cases: when the voice is **muted** (see mute by origin below), or when the event is a **stale retrig** (`event[9] & 0x40000` with `DAT_4395df4c[voice] != event[4]`).

| address | what it does | evidence |
|---|---|---|
| splice @`0x400774a2` | In the note handler's `else` block, `movel a2@(8),d2 ; movel d2,d0` (`d2 = event[2]` = track). Everything downstream keys on this value, called `uVar8` in the decompile. **POLY voice pool:** these 6 B become `jsr 0x40037746`, the 136 B ISR remap pad, which returns the chosen voice in `d0/d2`. In order, the pad does the following. (1) The lock predicate `(event[9] & 0x81) == 1` is tested **first**; such an event goes to the pool's last-triggered voice (`groupCursor[S]`), and the cursor is not advanced. (2) On a note start, the pad makes the note play the Source's Sound: it writes the Source's kit sound `kitbase + 0x20 + S*0xa2` into `event[10]`. A Source-track note that already carries a non-zero `event[10]` (its own Sound, or a Sound lock) keeps it. It then calls the allocator (see voice allocation below). (3) Any other note event goes to the note-off region scan at `0x400b2214`. On a track with no POLY followers the pad returns the track itself; there, the `event[10] == 0` override writes the track's own kit sound, which is exactly what the stock zero-fallback loads | decompiled; ✅ confirmed on the test unit (remap and Sound follow) |
| ISR per-trig Sound load | When `event[11] == 0`: if `event[10] != 0`, `FUN_40076f82(event[10], v)` runs; if `event[10] == 0`, it loads **voice `v`'s own** kit sound (`_DAT_800019ac + v*0xa2 + 0x20`). In stock `v` is the track, so this is harmless. Under pooling, `v` can be the voice of a POLY track, whose own Sound has machine 4, for which the render has no case: that load would click. The pad's `event[10]` override (row above) is what keeps it from happening. ⚠️ The load is **skipped when `event[10] == curSound[v]`** (`0x800019b4 + v*4`, written by `FUN_40076f82`). A **content** change behind the same Sound pointer is therefore not reloaded until that table is cleared, which the set-active-kit event does | decompiled; ✅ confirmed on the test unit (no click with the override) |
| lock trigs on a pool | Because `event[1] == 1` for every sequenced trig, a trigless lock trig differs from a note only in `event[9]`, and the ISR tests `(event[9] & 0x81) == 1` **before** `event[1]`. The POLY pad tests it first too, so a lock trig on a Source modulates the **last-triggered** pool voice and leaves the rotation alone. For that to be a single read, `groupCursor[S]` holds the **last** voice used (advance, use, store) | decompiled; ✅ confirmed on the test unit |
| ⚠️ `event[2]` range in the POLY pad | The pad reads `groupSource[event[2]]` with no bounds check. `groupSource[8]` at `0x439902f0` is followed directly by `groupCursor[8]` at `0x439902f8`, so an `event[2] ≥ 8` would read a cursor byte as a source. The parameter system indexes tracks 0–15 (+16 = master), while voices are 0–7. No misfire has been observed; whether `event[2]` is always 0–7 at this point is **unverified** | inference from code |


What the audio evaluator itself writes into `event[1]`, `event[3]`, `event[9]` and `event[10]` is in its row
(`FUN_4006f1be`, "Sequencer and pattern playback").

### Events: allocation, queue and producers

The note-event allocator `FUN_400ddb12`, the free functions and the queue-head getter have their rows in "The
trig-to-voice event scheduler".

| address | what it does | evidence |
|---|---|---|
| `FUN_4006eaf4` (1192 B) | **The transport START/CONTINUE handler.** It reloads every track's step counter and pattern position, seeds the per-track working state at `0x4195fbXX` / `0x421ba8XX`, and sends MIDI clock `0xFA` / `0xFB` via `FUN_400c6c22`. Its event is type 4 (`*event = 4`), so it never reaches the voice gate. The `switchD_400c4420` cases that reach it are MIDI transport messages. ⛔ It is not a note producer | decompiled |
| event queue | A list of buckets headed by **`_DAT_421b7940`** and linked by `bucket[4]`. A bucket is `{[0] immediate flag, [1] time, [2] first event, [3] last event, [4] next}`, and its events are chained through `event[0x12]` (`+0x48`). **`FUN_400ddd72(event)`** appends FIFO to the immediate bucket, creating one at the head if the head is not immediate; it has **exactly two** callers, `FUN_400dd3a8` and `FUN_4007683c`, the only producers that can write `event[1] != 1`. **`FUN_400ddc98(event, time)`** inserts into the time-sorted buckets (FIFO within equal times); it has 5 callers: `FUN_4006e950`, `FUN_40076be0`, `FUN_4006eaf4`, `FUN_4007011c` and `FUN_40077120`. `FUN_400dd9ac` pops a bucket from the free list `_DAT_421b7948` and **spins forever if that list is empty**; `FUN_400dd9dc` returns the head. The ISR consumes buckets from the head while they are immediate or due, FIFO within each. ⇒ Posted events are processed in the order they were posted, and a burst posted between two ticks is processed within **one** tick | decompiled |
| `FUN_4007683c(desc)` (112 B) | **The generic note-event emitter**, used by the MIDI input path and therefore by MIDI Loopback. It allocates via `FUN_400ddb12`, fills the event from a descriptor and posts it with `FUN_400ddd72`. Fields: `event[3] = 2` (hard-coded priority 2, `*(int *)(event + 0xc) = 2` in the decompile); `event[1] = desc[3]` (1 = note on, 2 = note off); `event[2] = desc[0]` (track); `event[6] = desc[1]` (note); `event[5].w = desc[2] << 8` (velocity); `event[9] = desc[4]` (flags); `event[0xc] = DAT_4017747c[desc[0xb]]` (length); `event[10] = 0`. It **never writes `event[4]`**, so that word holds stale bytes from the slot's previous use; it is read only when `event[9] & 0x40000` is set, which this path does not set. Callers: `FUN_400c578e`, `FUN_400c53f2`, `FUN_4000844c`, `FUN_400084b8`, `FUN_40098f8a` and `FUN_40098f12` | decompiled |
| `FUN_400dd3a8(msg, timebase)` (904 B) | Called **only by the audio ISR**, from its shared-SRAM message handoff (below). ⛔ **Not the live or external MIDI note path**: DIN, USB and Loopback note input never reach it. **Ingest:** when `msg[1] & 0x10`, it walks up to `msg[1] & 0xf` packed **4-byte note records** from `msg + 0x10`. It stops at the first byte with the top bit set, the firmware's usual `0xFF` empty-slot sentinel; the records carry no MIDI status byte. Each record becomes a node (free list `_DAT_4398c19c`, pending list `_DAT_4398c198`): `node[1]` = byte 0; `node[2]` = byte 1 = velocity; `node[3]` = `(u16 @ rec+2) >> 0xc` = channel/track selector 0–15; `node[4]` = `(that u16 & 0xfff) + timebase` = an absolute due time. **`_DAT_4195ffdc` is a sample counter.** The ISR tail adds `0x20` per tick (`_DAT_4195ffdc += 0x20`), and a tick renders 32 samples at 48 kHz (666.7 µs, 1500 Hz), so the 12-bit offset is sample-accurate (range 4095 samples ≈ 85 ms). A note-off arrives as its own record with velocity 0. **Emit:** for each node that is due and whose channel bit is not yet set in this pass's mask `1 << (node[3] & 0x3f)` (**at most one event per channel per pass**), it allocates an event: `event[3] = 2`; `event[1] = 1 + (velocity < 1)`; track and note by a dual encoding (if `node[1] < 8` it is an audio track, `event[2] = node[1]`, and the note is read from the pattern as `*(char *)(node[1]*0x38f + FUN_4006f11e() + 0x384)`; otherwise `event[2] = node[3]` and `event[6] = node[1]`); `+0x14` = velocity << 8; `event[9]` = `0x30681`. It posts with `FUN_400ddd72` and never writes `event[10]` | decompiled |
| ISR shared-SRAM message handoff (around lines 107–126) | `FUN_40003664(buf, &local_44, local_40, 0x20)` fetches **one** message per tick from a double-buffered shared-SRAM block at `0x80001f60` / `0x80002060` (selected by `_DAT_4195ffe4 ^ 1`), with a relative time in `local_40[0]`. A message that is not yet due (`local_40[0] >= 0`) is parked in a **single-slot** stash `_DAT_41960324` (time in `_DAT_41960320`) and handed to `FUN_400dd3a8` on the next tick; a due one (`< 0`) is handed over at once, with `iVar13 = local_40[0] + 0x20`. ⚠️ Visible in the decompile: if a message is already stashed **and** the newly fetched one is due, `iVar3` is overwritten with the new message after the stash was cleared, so the stashed message is never processed. The stashed time is relative and is not re-based a tick later. ⛔ This handoff is not on the MIDI input path, so it does not cause the tick-wipe drones | decompiled |


### MIDI note input to audio tracks

This path serves DIN, USB and Loopback notes alike; MIDI Loopback posts into an input queue, so its notes arrive here too.

| address | what it does | evidence |
|---|---|---|
| `0x4019b750[8]` = `FUN_400c5bb6` · `[9]` = `FUN_400c5bda` | The note-off and note-on handlers in the input dispatch table. Velocity 0 is routed to the note-off handler | decompiled |
| `FUN_400c5a24(chan, note, vel, origin)` | **The note-on core.** On the **auto channel** (`_DAT_4193d708`), if the note is already sounding (`DAT_4215d010[note] >= 0`), it first sends a note-off for it. That is built-in retrigger protection. It then fans out to every track whose channel `DAT_4193d70c[t]` matches; on the auto channel only the active track `_DAT_4193d6c0` counts. It calls `FUN_400c53f2` per track and records `DAT_4215d010[note] = track` | decompiled |
| `FUN_400c58e8(chan, note, origin)` | **The note-off core.** It uses the same fan-out, clears `DAT_4215d010[note] = −1` on the auto channel and calls `FUN_400c578e` per track | decompiled |
| `FUN_400c53f2(track, note, vel, origin, …)` / `FUN_400c578e(track, note, origin)` | **The per-track note-on and note-off senders.** Both keep a per-(track, note) reference count at `DAT_4215d418[track*128 + note]` (+1 / −1) and a per-track held count at `DAT_4215cfd0[track]`. Both build a descriptor (`[3]` = 1 on, 2 off; the note-on flags include `0x10001`, so `held[]` is written). They call `FUN_4007683c` for audio tracks (< 8) or `FUN_400d129c` for MIDI tracks, and both feed the recorder `FUN_400c4bda`. Both emit their audio event unconditionally and in call order, so the audio side receives notes in **wire order** | decompiled |

### Note length, release and the post-loop wipe

| address | what it does | evidence |
|---|---|---|
| per-voice LEN countdown `0x8000196c + voice*4` (= `0x800014f0 + (voice + 0x11f)*4`) | **Where a sequenced trig's LEN takes effect.** The note-on commit writes `event[0xc]` here (around line 314). The ISR's pre-loop subtracts the elapsed time each tick, and on expiry sets the voice's bit in **`local_50`** (`%fp@(-76)`). The reconciliation turns that bit into a release **unless the voice was re-triggered this tick** (`local_50 & ~uVar2`). A value of 0 means no countdown: the voice holds until a note-off | decompiled |
| sequenced trigs have no note-off event | `FUN_4006f1be` sets `event[1] = 1` for every trig, only the two `FUN_400ddd72` callers can write `event[1] != 1`, and the ISR's reschedule path is gated on flag `0x8000`, which audio trigs never set. ⇒ A sequenced audio trig's release is never queued as an event. The ISR's note-off branch, and so the POLY region scan, only ever serves priority-2 notes (live, MIDI and Loopback). A pattern switch cannot lose a sequenced trig's release, because nothing is queued to lose. ✅ Confirmed on the test unit, with a stock machine (ONESHOT, looped single-cycle, AMP HOLD = NOTE, DEC = 0). A LEN = 32 note kept sounding across a pattern switch for its full length. Two overlapping generations of the same trig each ran their own full LEN and released independently, 16 steps apart | decompiled; ✅ confirmed on the test unit |
| priority-1 flush (around lines 407–438, gated on `_DAT_4195ffe0`) | When the flag is set, every voice at `priority == 1` (a sequencer note) that was not triggered this tick is released, and its masks are cleared; then the flag is cleared. **Priority-2 voices are exempt** and wait for their own note-offs. ⚠️ This is consistent with [STOP] silencing sequenced audio while MIDI notes are released by the note-offs [STOP] sends | decompiled |
| **post-loop bookkeeping wipe** `0x40077a72..0x40077aac` | After the event loop, the ISR builds a wipe mask and sets `priority = 0, held = −1` for every voice in it. Register map: `d3` = triggers this tick (`uVar2`), `d6` = event releases (`uVar16`), `d4` / `d5` / `d7` = `uVar14` / `uVar15` / `uVar17`. In stock the mask is `(local_50 & ~d3) \| d6`: the countdown term is masked with the triggers, but **the event-release term is not**. `d2`, the wipe mask, is also the engine's release mask. It survives to `moveb %d2,%fp@(-35)` at `0x40077d74` (`local_27`), stored beside `moveb %d3,%fp@(-36)` right before `jsr 0x400713c0`. The only other write to `d2` on the way is the `0x4195ffd4 == 2` path at `0x40077c00`. That path forces `d2 = d6 = d7 = −1` and `d3 = d4 = 0` (release every voice, trigger none), calls `0x400dc828` and sets `0x4195ffd4 = 1`. ⚠️ Inference, not traced: this is the engine-wide kill that All Sound Off reaches. **The stock defect:** suppose a note-off releases a voice and a note-on re-uses **that voice in the same tick**. The engine receives both masks, and the trigger wins, so the voice keeps sounding. Yet the wipe clears its bookkeeping. The voice is left sounding but booked as free, no note-off can match it (every match needs `priority == 2`), and only All Sound Off stops it. MIDI Loopback exposes this, because it posts a retrigger burst with no wire spacing, so `off X, on X'` pairs land in one tick; a DIN cable spaces messages about 1 ms apart at 31250 baud, usually across ticks. With free-first allocation, the drone count is `max(0, 2N − P)` for N retriggered notes on a pool of P voices, as measured. ✅ Confirmed on the test unit: a single note retriggered through MIDI Loopback on a track with no followers (P = 1) drones, as predicted from the code. **Tick-wipe fix:** 24 B in place, no pad, making the mask `(d6 \| local_50) & ~d3`. The new bytes are `2006 80aeffb4 2403 4682 c480 4280 41f94395ddf4 43e8012c`. The 2 B needed come from `lea %a0@(300),%a1` (held = priority + `0x12c`). `0x40077a72` is a branch target (`beqs` at `0x400779f4`) and stays an instruction start. `d0` is dead until its own `clrl`, and the next instruction still starts at `0x40077a8a`. A voice released and re-triggered in one tick now gets the trigger without the release, which is what a steal delivers anyway. ⚠️ Residual: a note-on followed by its **own** note-off in one tick (a zero-length note) still leaves a voice triggered but booked as free | machine code + decompile; ✅ confirmed on the test unit: 0 drones in every tested cell (2 notes on P = 2/3; 3 notes on P = 3/4/5), the phasing while playing gone, the drone after a retrigger, a pattern switch and [STOP] gone, and no regressions in steal-then-release, owner-latch releases, non-POLY live notes, mute by origin, or sequenced trigs at their LEN |

### Parameter values into the engine

The live knob-turn setter `FUN_40076ee8` has its row in "Runtime parameter system", and the Sound load
`FUN_40076f82` in "Voice and trigger path".

| address | what it does | evidence |
|---|---|---|
| `FUN_4007480a(voice, paramObj, mirrorBase)` (148 B, called only from the ISR) | **The parameter applicator, and the only setter of the dirty mask.** `paramObj+8` is the entry count; entries start at `paramObj+0x14` with an 8 B stride (`[0]` signed parameter index, `[1]` u16 value). Per entry it sets `dirty[voice] \|= 1 << (idx & 31)` (the word is `idx >> 5`; a negative idx is biased by `+0x1f`). It writes the value into the voice mirror at `mirror + 2 + (idx + 8 + voice*0x35)*2` and into shared SRAM at `0x80002b2c + (idx + (voice*0x6a + 0x12)/2)*4` as `value << 16`. `0x4395ddb4` appears as an immediate in exactly three functions: this one (`0x40074842`), the flush `FUN_40074740` and the clear `FUN_400747d4`. There are two ISR call sites, both with `d2` = the voice and `d0 = event[0x11]`: **`0x400775d4`**, the param-only branch, and `0x400778e4`, the note-on parameter-lock site. Parameter locks that ride a note event are applied at `0x400778e4` to the voice the note landed on. ⛔ **Live knob turns do not come through here** (✅ confirmed on the test unit: running this applicator over a pool at `0x400775d4` does not change what SRC/FLTR/AMP knob turns do). "Only setter of the dirty mask" does not mean "on the knob path": knob turns go through `FUN_40076ee8` | decompiled + literal scan |
| `FUN_400746cc(voice, mirrorSrc)` (52 B) | ✅ **Expand u16 → 16.16**: widens one voice's whole u16 mirror (`0x6a` B = 53 parameters) to u32, shifted left 16, into the **16.16 render array** at `0x80002b50 + voice*0xd4` (`0xd4` = 53 × 4). This is the form the renderer consumes. Callers: `FUN_40076f82` (as its last step) and `FUN_4007699e` | decompiled |
| `FUN_40074700(voice, src, startIdx, count)` (64 B) | ✅ Expands a range of parameters for one voice into the same array, at `0x80002b2c + (startIdx + (voice*0x6a + 0x12)/2)*4`. Called only by `FUN_4007699e`, and only ever with voice 8: the master/FX block at `0x800031f0` (`= 0x80002b50 + 8*0xd4`) | decompiled |
| `FUN_4007489e(voiceMirror)` (146 B) | ✅ **Returns the engine object**, the fixed literal `0x80002760` that the two render functions take; called only from the ISR, right before them, as `FUN_4007489e(&DAT_800014f0)`. A first loop runs EMAC arithmetic (`× 0x3d7`) over `0x8000275c..` and `0x80002b2c..` (a render-side coefficient pass; ⚠️ what it computes is not pinned). A second loop copies two u16 fields (`+0x1a`, `+0x2a`) of each of the 8 blocks of its argument, the per-track voice mirror at `0x800014f0` (stride `0x6a`), into the engine blocks from `0x8000277a` (stride `0x6a`), block *n* from track *n*, on every tick. ⛔ Not a per-kit FX pass: its argument is the voice mirror ([render_path.md](render_path.md)) | decompiled + objdump |
| `FUN_400747d4(voice)` (22 B, 1 caller) | Zeroes the per-voice dirty mask. It is called only on the Sound **load** path, because a full load copies every parameter | decompiled |
| `FUN_40074740(voice, mirrorBase, soundPtr)` (148 B, 1 caller) | **The dirty-parameter flush**, called on the Sound-load **skip** path. It walks the 64-bit dirty mask lowest set bit first. For each parameter `i` it copies `*(u16*)(soundPtr + 0x14 + i*2)` into the voice mirror at `mirrorBase + 2 + (i + 8 + voice*0x35)*2` and into shared SRAM at `0x80002b2c + ((voice*0x6a + 0x12)/2 + i)*4` as `value << 16`, then clears the bit | decompiled |
| `DAT_4395ddb4 + voice*8` | The per-voice **64-bit Sound-parameter dirty mask** (8 voices × 8 B = `0x40`, directly before the priority array `DAT_4395ddf4`). It is set by `FUN_4007480a`, consumed by `FUN_40074740` and cleared wholesale by `FUN_400747d4` | decompiled |
| `0x4395de14` | ⚠️ A counter, probably an audio tick or block counter. It is incremented (`addq.l #1`) by `FUN_40076938` and `FUN_400bca1c` and read by `FUN_400032fc`, `FUN_40003664` and `FUN_400060d2` | literal scan |

### SLICE round robin on a pool

The SLICE round-robin pad at `0x400b23b0` is entered from the per-tick render `FUN_40074af2` (at `0x40074b3c`, returning to `0x40074b62`). After its **per-voice** edge detect (`prev[]` / the `0x80001228` new-trig bit, which must stay per voice), it indexes the round-robin counter by `groupSource[voice]`. So a POLY pool shares one slice sequence. ⚠️ From the code, the same edge detect is what keeps trigless trigs from advancing the counter; this has not been tested on its own.

On that same edge it latches the counter's low byte into `slice[voice]` (`0x439902d0`). The voice reads the latch for the rest of its note, so a new pool trig cannot move a playing voice's slice. One `lea 0x439902b0,%a2` reaches both the counters (`%a2@(0,%d1:l:4)`) and the latch (`%a2@(32,%a3:l)`). The track index rides in `a3`, which is dead from the function's entry until `moveal %d2,%a3` at `0x40074b7e`.

⚠️ This depends on the pool map, which is built at project load. See [SLICE round robin](features/slice_round_robin.md). ✅ Confirmed on the test unit (shared sequence and latch).

### Voice allocation, owner latch and mute by origin

| address | what it does | evidence |
|---|---|---|
| **voice allocator** `0x4015cb8a` (70 B, STL span) | This build's code, over the head of the unused stock `FUN_4015cb8a` (the STL span row below). Called by `jsr 0x4015cb8a` at `0x40037790` in the ISR remap pad. The first 34 B of the pad's block at `0x40037790` hold this `jsr`, the owner-latch write and `nop` filler. ⛔ The block's last 4 B, `ident` at `0x400377b2` (`movel %d2,%d0 ; rts`), are a branch target shared by the note-off and lock-trig arms, and must not move or be overwritten. **Free-first:** walk the pool from the cursor and take the first voice with `priority[v] == 0`. The walk is a cycle, so falling out of it lands back on the plain round-robin voice. **Steal:** on that fall-through, `clrl %a1@(0,%d1:l:4)` clears the chosen voice's priority, so the gate reads `0 <= 1` and passes. The allocator stores the chosen voice in `groupCursor[S]`. A pool of one takes S, so a track with no followers keeps its own voice. ⚠️ `priority == 0` means **released, not silent**: free-first can cut a release tail. ⚠️ Known risk: a steal followed by a drop of the same trig (for example on a muted track) may leave the victim droning (⚠️ reasoned from code, not observed). See [voice allocation](features/voice_allocation.md) | ✅ confirmed on the test unit: on a 3-voice pool (a Source + two POLY tracks) with 0/1/2/3 notes held, 3/3 trigs sound at every count (a round robin without free-first gives 3/3, 2/3, 1/3 and 0/3) |
| owner latch write `0x40037796..0x4003779e` (8 B) | `movel %a2@(8),%d0 ; moveb %d0,%a0@(-8,%d2:l)` ⇒ `ownerTrack[voice] = event[2]`. The allocator returns here (`d2` = voice, `a0` = `groupSource`); then 20 B of `nop` filler fall through into `ident`. ⚠️ That filler is reachable only by fall-through, so anything placed there must itself fall through into `ident` | ✅ confirmed on the test unit |
| **note-off region scan** `0x400b2214` (64 B) | Called for every note event that is neither a lock nor a note start. It scans `v = 7..0` for **`ownerTrack[v] == track && priority[v] == event[3] && held[v] == event[6]`**. It returns the matching voice in `d2`, else leaves the track unchanged, and it writes nothing. **Owner latch:** two same-length edits. `0x400b221e` `10302800` → `20024e71` (`movel %d2,%d0 ; nop`) makes `d0` the track itself rather than `groupSource[track]`. At `0x400b222a` (the loop head, a branch target kept at the same address and length), `b0301800` → `b03018f8` (`cmpb %a0@(-8,%d1:l),%d0`). `ownerTrack[8]` sits at `0x439902e8` = `groupSource − 8`, inside a 24 B gap (`0x439902d8..0x439902f0`) that no instruction in the image references. Both per-voice arrays are reached through one pointer: `a1` = the priority base `0x4395ddf4`, `lea %a1@(0,%d1:l*4),%a4`, then `movel %a4@(300),%d2` = `held[v]` (`0x4395df20 − 0x4395ddf4 = 0x12c` fits a d16 displacement). **Why the track and not the Source:** a note-on and its note-off carry the same `event[2]`, so the latch stays valid whatever the pool map does between them, for example across a pattern switch that changes the pool layout. ⛔ Latching the pool Source instead leaves a live note held on a POLY track droning after a switch that makes that track standalone. ⛔ When widening a match over a set of voices, use the **whole** original predicate: a scan that matches `held` alone can return a voice that a sequencer trig holds on the same note; the ISR's own priority test then refuses the release, and the voice that holds the live note keeps sounding (⚠️ reasoned from the code). A stale latch is harmless, because every note-on that sets `priority[v]` passes through the allocator and rewrites the latch, and an idle voice fails the priority test first. ⚠️ Still open: with two voices holding the same (track, note), the scan releases the first in `v = 7..0`, not necessarily the one that note-on took | machine code + decompile; ✅ confirmed on the test unit: a live or Loopback note held on a pool voice across a pattern switch releases |
| **the mute gate** (around lines 220–239) | The mute test sits inside `if (event[3] == 1)`, so only **priority-1** events (sequencer trigs) are mute-checked; the gate is the `bnew` at `0x400774c6` when priority != 1. Priority-2 notes (live, external MIDI, Loopback) never reach the test, so they sound on a muted track. This build keeps that stock behaviour. The two tests (in the decompile, `uVar25 = local_54 >> uVar8 & 1`, plus `_DAT_4196032c`): `0x400774ca` `movel %fp@(-80),%d1` loads the live mute mask from a local, then `0x400774ce` `asrl %d2,%d1`. After `0x400774d6` `mvzw 0x4196032c,%d0` (the pattern/kit mute mask, 16-bit global) comes `0x400774dc` `btst %d2,%d0`. **Both index `%d2`, the voice the POLY pad returned.** Without a fix, a Source's mute catches only the trigs that happen to land on the Source's own voice. **Mute by origin:** two 12 B detours in the STL span re-read the track from `%a2@(8)`. `mute_live` at `0x4015cbd0` (`movel %fp@(-80),%d1 ; movel %a2@(8),%d0 ; asrl %d0,%d1 ; rts`) is spliced from `0x400774ca`. `mute_kit` at `0x4015cbdc` (`movel %a2@(8),%d0 ; btst %d0,0x4196032d ; rts`) is spliced from `0x400774d6` as `nop` then `jsr`, so the `rts` lands directly on the `beqs`; the CCR is the return value, and `rts` leaves it alone. No register is spare for carrying the track: `%d3`–`%d7` are OR-accumulators built across the tick (`orl %d0,%dN` at `0x4007777a` / `0x400775ac` / `0x40077596` / `0x4007793a` / `0x40077940`), and `%d1` carries the first result to `tstl %d1` at `0x400774f8`. `%d0` is dead at both sites. A whole-section branch-target scan finds no branch into either replaced run. `0x4007747e bnes 0x400774a2` is a forward branch to the pool-remap splice at the head of the note handler, ahead of both runs, and carries no value into the mute tests. On a pattern with no POLY track the map is identity, so the mute tests drop the same trigs as stock (the allocator runs before them: [voice allocation](features/voice_allocation.md)) | machine code + decompile; ✅ confirmed on the test unit |
| STL span `FUN_4015cb8a` (144 B) · `FUN_4015cc1a` (64 B) · `FUN_4015cc5a` (30 B), `0x4015cb8a..0x4015cc78` (238 B) | **Unused `std::list` template instantiations**: splice, splice-range (the 3-argument form; it saves and restores `a2-a5` with `moveml`) and reverse. Each has a `linkw` prologue and ends in `rts`, and none contains a `jsr`, `bsr` or `jmp`. They are pure leaves, and no reference to them was found (word, operand and PC-relative, and switch-table scans). They host the voice allocator (70 B at the head) and the two mute-by-origin detours; the unused space in the span holds pad fill ([landing pads](landing_pads.md)). ⚠️ Their deadness test fails **quietly**: a live member would mean a list splice that silently does not happen, not a fault. `linkw`-prologue leaves with no callees in the high `0x401xxxxx` C++ region are a good class to hunt for when looking for dead space | decompiled + reference scans; ✅ fill test on the test unit: no observable difference (⚠️ the sound pool path was not exercised) |



### The MIDI active-note tracker (stock; MIDI tracks)

| address | what it does | evidence |
|---|---|---|
| `FUN_40028c44(obj, key, chan, note, …)` (398 B) | **The tracker's insert**, called from `KeyboardView::vfunc_2`. It **sounds the note unconditionally but records it only once**. (1) `FUN_40028a5a(...)` triggers the note, with no guard. (2) It walks the record vector (`obj+0x94` begin, `+0x98` end, `+0x9c` capacity; 0x14 B records compared by `FUN_4002825c`). (3) On a match it re-stamps `rec[4] = 1` and returns, without appending. (4) Only when there is no match does it append `{[0]=1 in use, [1]=key, [2]=note, [3]=chan, [4]=1 state}`. ⇒ Two overlapping note-ons of the same (key, chan, note) share one record, the release path sends one note-off, and the second voice is stranded. This is a **stock defect**, keyed by (channel, note); it is not saturation and not the voice steal. ✅ It matches the test unit: hanging MIDI notes need a retrigger while the previous instance is still sounding, and occur on MIDI tracks only. Audio note events do not strand voices in this way, because each note-on takes its own voice slot (✅ confirmed on the test unit, at LEN = 64 with deep pool overlap) | decompiled |
| `FUN_400285cc(obj, key)` (378 B) | **The tracker's release-all.** For every record with `rec[1] == key && rec[4] == 1 && rec[0] == 1`, it calls `FUN_40028546(obj, rec[3], rec[2])`, clears the slot (`[0]=0, [4]=0, [3]=[2]=[1]=−1`) and compacts the vector. ⚠️ The release is gated on `rec[0] == 1 && rec[4] == 1` and silently skips anything else. Callers: `FUN_40028746` / `FUN_4002875a`, thin wrappers that take the key from an object field | decompiled |
| `FUN_40028546(obj, a, note)` (134 B) | Returns early when `a > 0xf`. Otherwise it calls `FUN_400c578e(a, note, 0x40)` (the per-track note-off sender), `FUN_4002851e` and `FUN_400c6b10(a)`. Its only caller is the release-all above | decompiled |

### Cancelling queued events

| address | what it does | evidence |
|---|---|---|
| `LAB_4006e300` / `LAB_4006e320` | **The event-queue cancel visitors.** Ghidra defines no function here, so they were read with objdump. Each takes `(event, arg)` on the stack and proceeds only if the type (`%a0@`) is **0 or 3**. It then writes `moveq #-1,%d1 ; movel %d1,%a0@(12)` ⇒ `event[3] = −1`, which the ISR treats as cancelled. `LAB_4006e300` also matches the track (`movel %a0@(8),%d0 ; cmpl %sp@(8),%d0`); `LAB_4006e320` cancels every type-0/3 event. They are passed as function pointers to **`FUN_400ddbde(time, visitor, arg)`**. For example, `FUN_4007011c` calls `FUN_400ddbde(..., &LAB_4006e300, track)` after an evaluator returns an event, so a new trig supersedes that track's pending events | objdump |
| ⛔ other writes of −1 to offset 12 | A whole-image scan finds six: `0x40028618`, `0x4006e31a`, `0x4006e330`, `0x400848b0`, `0x400f474a` and `0x400f4788`. `0x4006e31a` belongs to `LAB_4006e300`. `0x40028618` is `FUN_400285cc` clearing a 20 B note record, where offset 12 is `rec[3]`. None of the others writes to an event | binary scan |
| `FUN_40073f5e` (1228 B) / `FUN_40073ed8` | ⛔ Not on the parameter path. EMAC (ACC0/ACC1) coefficient computation with a 64-entry table at `0x40175a5c`, indexed by a byte at `+0xc`, and six scalar state longs at `0x4395dd94..0x4395ddb0`. They sit next to the dirty mask in memory but are unrelated to it | decompiled |

## Utility (MAIN OS)

| address | what it does | evidence |
|---|---|---|
| `FUN_400d7e64(dst, src, n)` | **memcpy** | call sites throughout |
| `FUN_400d7890(dst, n)` | **memset / bzero** | boot-time table clears |
| `FUN_400c408c(n)` | **Allocator** (`thunk_FUN_400c408c`) | call sites |
| `FUN_400c4292(p)` | ⚠️ **free**; paired with the allocator in `FUN_4006753e` | call pattern |
| `FUN_400c3496(n, p)` | ⚠️ Large-buffer clear/init, called with `0x200000` and `0x1e0000` | call sites |
| `FUN_4011667c` (1,890 B) | ⚠️ The only function that references `.bss` `0x40218518` | reference query |

## Whole-image classification (candidates)

These rows come from a classification pass over the whole image (RTTI, call graph, memory signature). They are candidates, not traced functions. The subsystem view is in [architecture](architecture.md).

| address | candidate | evidence |
|---|---|---|
| `FUN_400f1378` (6.8 KB) | ⚠️ Audio engine / voice code: 16 shared-SRAM references | memory signature |
| `FUN_4010f040` (4 KB) | ⚠️ Audio engine / voice code: 13 SRAM + DDR references | memory signature |
| `FUN_4006d492` (3.6 KB) | ⚠️ Engine state / table manager: 103 distinct DDR addresses, 61 callees, next to the engine | memory signature |
| `FUN_400eaca2`, `FUN_400ec798`, `FUN_400ee006` (~6–7 KB each) | ⚠️ DSP-effect code: fixed-point arithmetic, few callees | memory signature |
| the audio engine | ⚠️ It has **no vtables** and lives in the unnamed low region `0x40000400`–`0x400dffff`; the UI and application code are the RTTI-named region `0x40120000`–`0x4015ffff` | classification |

## Negative results

Negative results about a function that has its own row elsewhere are kept in that row: `FUN_400f5c2c`
(neither audio code nor graphics data), `FUN_40076ee8` (a plain copy that does not interpret the SLICE value),
`FUN_40078248` (the stop/flush path, not note-on) and `FUN_4006eaf4` (not a note producer).

| address | what it is **not** | evidence |
|---|---|---|
| `FUN_40076350` | ⛔ Not the machine-type dispatch. It is a **17-case switch on `_DAT_4193dfa4`** (a UI/state selector), although it is the only switch in the voice cluster | decompiled |
| `FUN_401099ae` (12.5 KB) | ⛔ Not audio: C++ runtime support (ABI/exception/RTTI strings) | strings |
| `FUN_4006ca1c` | ⛔ Not audio: filesystem and sample-file loading | strings |
| section 2 as a whole | ⛔ Does not read `0x8000196c` / `0x8000198c`. Mute is a MAIN OS trigger gate (above) | reference scan |
| section 2 as a whole | ⛔ **Not the resident audio renderer** (below) | two checks: the address conflict, and no section-2 reads of the parameter array (below) |

## Section 4 ("updater"): the factory bring-up and flash programmer

Section 4 has 165 functions, is 77 % code and imports with 0 errors. Its 19 strings describe a **text command protocol** over a serial/debug link: a `#HELLO` handshake, `#STATUS` / `VERSION`, `PLATFORM` (reporting a `CPU4101` board id, the Digitakt main board), `FLASH`, DRAM initialisation and memory-test messages, and `WRITE` / `CLEARING` / `DONE` / `FAIL` / `WRONG DEVICE TYPE`. It identifies the board, initialises DRAM, memory-tests it, then erases and writes flash.

⛔ It is **not** the SysEx OS-update receiver; that path is in MAIN OS (the `OsUpgrade` RPC commands above). So a stalled update receive never reaches this code. See [flash recovery](../../../notes/flash_recovery.md) and [update moat](update_moat.md).

## Section 2: not the audio renderer

Section 2 has base `0x80000ec0` and 132 functions, none named. Its load span is `0x80000ec0`–`0x800076d6`. Full map: [section 2 map](section2_map.md).

**It is not the resident audio renderer. Two checks carry this:**
1. **Address conflict.** MAIN OS writes the 16.16 parameter array to `0x80002B50`–`0x800032C4` and the u16 mirror to `0x80001502`+. Section 2's **code** occupies both ranges, and those byte ranges in the blob are 84–88 % non-zero. Both cannot be resident at once.
2. **It never reads the array.** Only six MAIN OS functions (the `0x40074xxx` cluster) reference `0x80002B50`; no section-2 function does.

⛔ Do not argue from "section 2 references only 9 DDR addresses" (`0x40000000`–`0x4000001c` plus `0x4021000e`). Section 2 moves audio by DMA descriptors written to peripheral registers, not by CPU loads, so a scan of CPU address references cannot show what it can reach.

**What it is:** a boot-time hardware bring-up / self-test / test-mode payload. It drives:
- the **SSI audio codec** (`0xFC0B8000`, 48 kHz);
- **eDMA** and the **DMA crossbar**;
- a **FlexBus display** (`0xEC07xxxx`) with its own drawing primitives;
- **encoder input** (its only strings are `ENCODER_F/G`);
- **SPI (DSPI)** and FlexBus communication.

It also runs a **64 KB memory check with a progress bar**, carries version strings, and hands off with **`0xB0B0DADA`** and status codes to `0x8000765e`.

Its SRAM overlaps MAIN OS's working RAM, so the two are mutually exclusive. The updater (`0x80000400` + 32 KB) overlaps it too. **The audio renderer is in MAIN OS**, running from DDR with hot data in SRAM: the audio ISR `FUN_40077120`, its pipeline `FUN_40074e84` / `FUN_400754fe` and the DSP stages it calls, traced in [render path](render_path.md). No feature in this build touches section 2; the build checks that it stays identical to stock.

| address | what it does | evidence |
|---|---|---|
| `FUN_800065d2` | Sets the section-2 stack: `move.l #0x80007644,sp` | byte-pattern hunt |
| `FUN_80000ec2`, `FUN_80000f18`, `FUN_8000116e`, `FUN_800011c0`, `FUN_800012f6`, `FUN_800013e0`, `FUN_800014da`, `FUN_80001476`, `FUN_80001608`, `FUN_800016a6`, `FUN_800017b0` | ⚠️ Reference addresses at `0x800014f2`+ / `0x80001780`+; their role is unknown | grep of the section-2 decompile |
| `FUN_80002b72`, `FUN_80002b9e`, `FUN_8000277a`, `FUN_80002902`, `FUN_80003f1e`, `FUN_80004d00`, `FUN_80004e4c`, `FUN_80004e6e`, `FUN_800053aa`, `FUN_800053d2` | ⚠️ Contain a switch or a small-bound dispatch. `FUN_80002b9e`'s job is unknown; ⛔ no readable strings support calling it a menu or test UI | grep + `strings` |
| `FUN_80003c26`, `FUN_80003c5e`, `FUN_8000424e`, `FUN_80004c88`, `FUN_80005820`, `FUN_8000578c`, `FUN_800058f8` | ⚠️ Own the scalars at `0x80008086`–`0x800082f6` | reference query |

## Open questions (stock firmware)

These also feed [open questions](open_questions.md), except the feature-level ones, which stay in their feature notes.

- **Who fills the ISR's shared-SRAM message block** (`0x80001f60` / `0x80002060`, fetched by `FUN_40003664` and consumed by `FUN_400dd3a8`)? Does one message carry one note record or a batch (`FUN_400dd3a8` walks up to `msg[1] & 0xf` records)?
- **Which producers besides trigless lock trigs emit the param-only events** (`(event[9] & 0x81) == 1`) that take the ISR branch at `0x400775d4`? Trigless lock trigs take it (see "lock trigs on a pool"); ⚠️ parameter slides are a candidate. It is not the live knob path.
- **Can `event[2]` reach the audio ISR's note handler with a value ≥ 8?** The POLY pad does not bounds-check it.
- **Why the tick-wipe fix also released a six-voice pattern-switch drone** that the `2N − P` model does not predict. ⚠️ Candidate only: the second pattern made the track a pool of one.
- **Release pairing:** with two voices holding the same (track, note), which one should a note-off release? The note-off scan currently takes the first in `v = 7..0`.
- **Which physical interface is SysEx port index 2, and which is 4,** in `FUN_400c4862`?
- **Who builds the machine layout records** at `0x4193edb4` (`.bss`, 4 × 44 B, built at runtime)?
- **Which USB configuration, if any, serves the two-cable descriptor set** (`bNumEmbMIDIJack = 2`)? The device shows one MIDI port in every USB CONFIG mode.
