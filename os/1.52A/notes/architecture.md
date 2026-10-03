# MAIN OS architecture

## What this is

The subsystem-level map of the Digitakt OS 1.52A MAIN OS (section 3, load base `0x40000400`,
about 11,000 functions). It covers how the OS boots and runs (RTOS, tasks, clocks, the audio ISR),
where each kind of code lives in the image, which C++ classes own what, and how the names were
recovered. It is the index to drill down from; per-function detail is in
[function_ledger.md](function_ledger.md), and the audio render in [render_path.md](render_path.md).

## Boot

The CPU is a ColdFire MCF54415 ([hardware.md](../../../notes/hardware.md)). The chain from reset to the scheduler:

1. `entry_400004e8`, the reset entry (`move.w #$2700,SR`).
2. crt0:
   - `FUN_40001c76` sets up the vectors (VBR = `0x40000000`);
   - `FUN_40001ecc` sets up peripherals and a second stack at `0x405d07f4`;
   - `FUN_4000045c` copies the SRAM init images into on-chip SRAM (see [memory_map.md](memory_map.md));
   - `FUN_400004b2` clears `.bss`.
3. `FUN_40120b5a`, the application entry. It creates the idle/bootstrap task, installs the scheduler
   and context switch at `0x40001566` (TRAP #0; vectors `0x0`, `0x60`, `0x100`, `0x200`), starts the
   RTOS tick, then executes `trap #0` to hand control to the scheduler.

## The RTOS

The OS runs a small preemptive priority RTOS of its own; ⛔ it is not FreeRTOS (none of its strings
are in the image).

| Function | Role |
|---|---|
| `FUN_400015ac(tcb, entry, prio, stack, stacksize)` | Task create. Builds an initial stack frame (the entry, initial SR `0x407c2000`, and the task-exit return `LAB_40001628`) so the scheduler can switch into it. |
| `FUN_400015f8(tcb)` | Task start / make ready. |
| `FUN_40001b18` | A second create variant (a queue, or a smaller object). |
| `FUN_401218cc`, `FUN_4015bec0` | Task-object dispatch wrappers: take a reference, then call the object's virtual method. Many tasks are C++ worker objects. |
| `FUN_40001574` | The scheduler tick (below). |

### The task table

`FUN_400015ac` has 16 call sites, one per task: 15 in functions and one in the bootstrap code
`LAB_40068b92` (`jsr` at `0x40068baa`). A lower priority number is a higher priority.

| Prio | Entry | Stack | Role |
|---|---|---|---|
| 0 | `LAB_40068b92` | `0x4000` | Bootstrap: spawns the main task `FUN_40068c42`, then `bra .` |
| 1 | `FUN_40068c42` | `0x4000` | Main application init (about 56 callees): initialises every subsystem and spawns the other tasks |
| 2 | `FUN_4006753e` | `0x4000` | Host communication, factory test and sample transfer: the `#HELLO`, `#SAMPLE_UPLOAD`, `#DUMP_AUDIO`, `#RECEIVE_AUDIO`, `#PLAY_STEREO` and `#RECORD_START` commands, the CODEC/MMC/DRAM/SUPERCAP self-tests, and the `CPU4101` protocol (the same one the section-4 updater speaks). Allocates the 2 MB buffers. It is on the firmware-update path: see [update_moat.md](update_moat.md) |
| 2 | `FUN_4008c3c0` | `0x4000` | Worker object (`0x4008c` cluster) |
| 3 | `FUN_400c61e2` | `0x8000` | Storage / `MmcFs` worker (`0x400c` cluster) |
| 4 | `FUN_400d700a`, `DAT_400d7130` | `0x8000` | Storage / stream workers (`0x400d` cluster) |
| 5 | `DAT_400d6eb6`, `FUN_400dab52` | `0x8000` / `0x4000` | Storage and communication workers |
| 6 | `FUN_4000ae46` | `0x28000` | Brain's message pump (queue `0x405d1c58`). `case 5` at `0x4000b3aa` runs `TimerManager::tick`; `case 0xc` hands live-recorded notes to `FUN_40008a42`. It also holds the "MAINTENANCE MODE", "Factory reset" and "Update MMC Caches" strings |
| 6 | `FUN_400d52b2` | `0x4000` | UI / service worker (started through `FUN_4015bec0`) |
| 7 | `FUN_4006bfc6` | `0x4000` | Large worker (2.3 KB) that calls parameter and view code (`0x4011xxxx`, `0x40140xxx`). ⚠️ UI or sequencer; not traced |
| 7 | `FUN_400c465e` | `0x8000` | The MIDI input task: binds the DIN port and both USB MIDI cables on one semaphore and dispatches each message through the table `0x4019b750` by status. MIDI Loopback hooks it at `0x400c47ce`. See [function_ledger.md](function_ledger.md#midi-tracks-the-send-chain-the-input-task-and-the-chan-display-paths) and [features/midi_loopback.md](features/midi_loopback.md) |
| 8 | `FUN_400d0578` | `0x4000` | The MIDI-out task: blocks on queue `0x42175468` and emits MIDI to DIN or USB. Part of the `0x400d0xxx` event-queue cluster. See [features/midi_loopback.md](features/midi_loopback.md) |
| 9 | `FUN_40001184` | `0x1000` | Small RTOS worker (timer or watchdog) |
| 10 | `DAT_40002cfa` | `0x800` | The idle task |

### Clocks

| Clock | Source | Rate |
|---|---|---|
| Bus | The literal `0x07DE2900` (132,000,000) at `0x40002758`, used by the UART init `FUN_400026f2` as `divider = 132e6 / (32 · baud)`; also at `0x40002b5e` and `0x40068c12` | 132 MHz. ⚠️ Inferred from use: no PLL programming exists in section 3, and the rate was not timed on the device. Both timers below landing on exact round rates supports it |
| RTOS tick | `FUN_40001574`: PIT0 at `0xfc080000`, PMR = `0xa121` (41,250 counts), PCSR = `0x053f` (÷32, EN/RLD/PIE); vector `0x40000410`, the context switcher | 10.000 ms = 100 Hz. The scheduler tick, not the UI clock |
| UI frame | The DTIM3 ISR `0x4005ef0c` (vector 99), set up at `0x4005ef3a`: DTRR3 = 275000, DTMR3 = `0x1D` (bus/16, restart, reference interrupt). The ISR posts a message to Brain's pump, whose `case 5` runs `TimerManager::tick` | 275000 / (132 MHz / 16) = 33.333 ms = 30.000 Hz |

The UI clock fans out through `Timer` objects:

- `Brain::Brain` (`0x4000989a`) registers five `Timer::addCallback` callbacks at `0x40009e3a`:
  `0x4000a45a` at 30 Hz, `0x40007b80` at 30 Hz, `0x400097d4` at 30 Hz, `0x40008f3c` at 15 Hz and
  `0x40008f4c` at 30 Hz.
- `0x40007b80` is the only reference to `ViewController::tick` (`0x400bbb30`), which calls every
  registered View's `vfunc_11` (vptr `+0x2c`). The `ViewController` object is at `Brain+64`.
- `Timer::addCallback` (`0x400d9d46`) sets `divisor = 30 / hz`; a timer object fires when
  `++counter >= divisor` (`Timer::Object::tick` `0x400d9984`, `Timer::tick` `0x400d9b6a`,
  `TimerManager::tick` `0x400d9bf0`).

## The audio ISR is not a task

`FUN_40077120` is the audio interrupt handler (vector `0x400002fc`, installed by the engine init
`FUN_40076b5a`). It runs above every task. The tasks feed it: the sequencer schedules events for it,
and the host-communication task moves sample data. It also feeds a task back: it pops sequencer MIDI
events (at `0x4007741a`) and posts them to the MIDI-out task's queue `0x42175468`. The whole render
pass is described in [render_path.md](render_path.md).

## Where the code lives

Function counts by address region (the regions that hold functions, plus the data between them;
the full address map is in [memory_map.md](memory_map.md)):

| Region | Named | Unnamed | What lives there |
|---|---|---|---|
| `0x40000000`–`0x400dffff` | ~600 | ~4700 | Low-level code and the audio/voice engine, with no vtables: crt0, the USB/DMA drivers, the sequencer, the audio ISR, the render and mixer, allocators, utilities |
| `0x40120000`–`0x4015ffff` | ~2640 | ~2280 | The C++ application (RTTI classes): UI views and menus, the data model, the parameter system, storage, MIDI, sample management |
| `0x40160000`–`0x4016ffff` | ~63 | ~64 | RTTI typeinfos, vtables, rodata |
| `0x40170000`–`0x401effff` | — | — | `.rodata` / `.data`: tables, strings, the parameter-descriptor table `0x4018ff88` ([parameters.md](parameters.md)) |
| `0x40210000`–`0x40211ef2` | 0 | 23 | ⚠️ A foreign island: its references point at `0x00800xxx` / `0xffff8xxx`, outside this image's address space |

The split is clean. The sound engine has no C++ vtables, so none of it is named by the RTTI walk and
it has to be traced by behaviour; the UI and application are RTTI-named almost throughout.

### The low-level and engine region

Mapped in [render_path.md](render_path.md) and [function_ledger.md](function_ledger.md): crt0 and
startup, the USB/DMA descriptor-ring drivers, the parameter push path (`0x40076xxx`–`0x40078xxx`),
the sequencer (`FUN_4007011c` → `FUN_4006f1be` / `FUN_4006f546`), the audio ISR `FUN_40077120`, the
render pipeline (`FUN_40074e84` / `FUN_400754fe` → the window functions), `FUN_4007442a`
(oscillator and eDMA preparation), and the heap (`FUN_400c408c` allocates).

The largest functions, classified by the memory they touch and the strings they reference:

| Function | Size | Classification |
|---|---|---|
| `FUN_400f5c2c` | 67,938 B | Graphics, not audio: constructs the UI's `Bitmap` objects (for example the `NOTE` bitmap, at `0x400f5e62`; see [visual_assets.md](visual_assets.md)) |
| `FUN_4013b6a2` | 19,842 B | The parameter-record builder: fills all 164 runtime parameter records ([parameters.md](parameters.md)) |
| `FUN_401099ae` | 12,530 B | C++ runtime support (ABI / exception / RTTI; references the string "hidden alias for") |
| `FUN_400de0dc` | 10,678 B | The MidiRpc request factory (below) |
| `FUN_400cc914` | 9,758 B | ⚠️ RPC sample assign: references "rpc sample assign" and "vector::reserve", touches MMC, heavy fixed-point |
| `FUN_400ee006` | 7.6 KB | ⚠️ DSP-effect candidate (few callees, some fixed-point) |
| `FUN_400e4f58` | 6.9 KB | ⚠️ Unclear (3 callees, one MMC reference) |
| `FUN_400eaca2` | 6.9 KB | ⚠️ DSP-effect candidate (math, 4 callees) |
| `FUN_400f1378` | 6.8 KB | ⚠️ Engine or voice candidate: 16 references into the shared SRAM voice area |
| `FUN_400ec798` | 6.3 KB | ⚠️ DSP-effect candidate (math, 5 callees) |
| `FUN_4010f040` | 4.0 KB | ⚠️ Engine or voice candidate: 13 SRAM and DDR references |
| `FUN_4006d492` | 3.6 KB | ⚠️ Engine state/table manager: 103 distinct DDR addresses, 61 callees |
| `FUN_4006ca1c` | 2.7 KB | File system: sample and file loading (references "ERROR - File Not Found") |
| `FUN_4006bfc6` | 2.3 KB | ⚠️ Unclear (27 callees, 11 DDR references); a task entry (above) |

The ⚠️ rows are classifications, not traces. The candidates are large and register-heavy.

## The C++ application, by subsystem

### Core and framework

- **`Brain`**: the central application singleton (`StaticSingleton<Brain>`, with `Observer` and
  `Timer` bases). It owns `processMidiEvent`, `startUI` and `processSysexDumpReceivedMessage`.
- `AutomationState`, `Clipboard` / `ClipboardData`, `MessageStackController` (the popup and message
  stack), the `Observer` / `Timer` / `noncopyable` mixins, and the `UIEvent` hierarchy.
- Other singletons: `StaticSingleton<Project>`, and `StaticSingleton<SMVoiceStates>`, an
  `Observable` that holds voice states for the UI to observe.

### UI framework and views (about half of the named code)

- Framework: `View`, `ViewController`, `Menu` / `MenuItem`, `PopupWindow`, `ConfirmWindow`,
  `MessageWindow`, `MultiChoiceWindow`, `BlockerWindow`, `Font`, `Bitmap`, `CompactVerticalMenuView`,
  `MenuWithPopoutSideBarsView`, `HelpBubbleView`, `TimeoutView`; the `LedHandler` mixin.
- Pages and screens: `MainScreenView` (8.8 KB), `PatternGridView` (7 KB), `SamplerView`,
  `SoundBrowser` (9.2 KB), `SamplePageView`, `SongEditView` (6 KB), `MachineParameterPageView`
  (5.3 KB), `KeyboardView`, `MasterPageView`, `FxPageView`, `LfoPageView`, `FilterPageView`,
  `AmpPageView`, `SoundPageView`, `MidiParameterPageView`, `NameView`, `TrackNoteMenuView`,
  `MachineListView`, `SongBrowserView`, `ParametersSeqNoteView`, and dozens of `*MenuView` settings
  screens, many with numbered `rebuild()` lambdas.
- `ParameterPageView` (52 virtual slots) is the base of `MachineParameterPageView`; `MachineListView`
  has 18 (`View | Menu | Observer`).
- A built-in test suite: `UITestView`, `EncoderTestView`, `AudioTestMenuView`, `HighScoreView`,
  `TestCompletedView` (compare section 2's test role, [section2_map.md](section2_map.md)).
- ⛔ `SoundManager` and `SampleManager` are not the voice engine. They are the Sound and Sample
  browser views (`MenuWithPopoutSideBarsView | Observer | LedHandler`, 46 and 45 virtual slots).

### Parameters and values

- `ValueWithMirror_Digitakt` (118 functions), `ArrayValueWithMirror_Digitakt`, `Value_Digitakt` (86),
  `AbstractValue`: the value objects.
- `ParameterSet` and its four subclasses `SoundParameterSet`, `FxParameterSet`, `MidiParameterSet`,
  `TrigParameterSet`: the per-domain parameter containers, which push values to the engine.
- `Velocity`, and the Observer notifications (`*ParamChangedInfo`, `DataChangeInfo`).
- The descriptor table, the encoder write path and the value arrays are in
  [parameters.md](parameters.md).

### Data model

- `Kit` / `KitActive`, `Pattern`, `Project` / `ProjectManager`, `Sound*` / `SoundLibrary` /
  `SoundPool` / `SoundModConf`, `ModConfig` / `ModulationCopy`, `MidiPreset`, `FxSetup`: the song,
  pattern, kit and sound hierarchy the engine renders from.
- Each entity is a `ValueWithMirror<T, StorageT>` that pairs the live struct with a versioned storage
  struct: `Kit : ValueWithMirror<Digitakt::kit_t, Digitakt::kitStorage_v9_t>`, and likewise
  `Pattern` / `patternStorage_v9_t`, `Project` / `projectStorage_v14_t`, `Sound` /
  `soundStorage_v2_t`, `Track` / `trackStorage_v5_t`, plus `Value<Digitakt::modTarget_t[4]>` and
  others. `updateMirror(...)`, `updateArrayMirror` and `loadProjectFromMemory(projectStorageContainer_t&)`
  lambdas convert between the forms.
- Storage-struct typeinfos present in the image: `projectStorage_v7_t`, `_v8`, `_v9`, `_v14`,
  `projectSettingsStorage_v7_t`, `kitStorage_v9_t`, `patternStorage_v9_t`,
  `patternSettingsStorage_v1_t`, `trackStorage_v5_t`, `soundStorage_v1_t`, `_v2`,
  `soundPoolStorage_v2_t`, `songStorage_v1_t`, `fxSetupStorage_v3_t`, `midiSetupStorage_v1_t`.
  ⚠️ Several versions of one struct side by side are read as the migration path from older project
  formats.
- The byte layout of a pattern and its kit, as the SysEx dump carries them, is in
  [pattern_layout.md](pattern_layout.md).

### Sample management

`SamplePicker` (6.4 KB), `SamplePool`, `SamplePoolDirectory`, `SamplePageView`, `SamplerView`, and
`SysexSds` (MIDI Sample Dump Standard): the +RAM sample store and browser.

### Storage and file system

`MmcFs` (the SD/MMC file system) with `MmcStreamReader` / `MmcStreamWriter`, `File`, `Directory`,
`FileSystemDirectory`, `FileOutputStream`, `BufferedStreamReader` / `Writer`,
`MemoryStreamReader` / `Writer`, `Lz4StreamCompressor` / `Decompressor` (project and sample
compression), and `BackupFileExportAdapter` / `ImportAdapter`.

### Input and hardware handlers

`EncoderHandler` / `EncoderBehavior` / `EncoderFilter`, `KeyHandler` / `KeyEvent`, `NoteHandler`,
`AnalogHandler` / `AnalogEvent`, `LedManager` / `LedHandler`: the panel's encoders, keys and LEDs.

### OS update

`OsUpgradeMenuView`, `OsUpgradeState`, `OsUpgradeDelegate`, `DigitaktSysexRpc`: the MAIN OS side of
the SysEx OS update. The flash programmer itself is section 4. None of this may be patched; see
[update_moat.md](update_moat.md) and [flash_recovery.md](../../../notes/flash_recovery.md).

### MIDI and the stock MidiRpc SysEx command set

- `MidiHandler`, `MidiEvent`, `MidiAsyncWorker`, `MidiOutputStream`, `MidiParameterSet`,
  `MidiPreset`, and the note classes `NOT1`…`NOT4`, `NOTE`, `NoteEvent`.
- `DigitaktSysex` / `DigitaktSysexDump`: the pattern and project dumps
  ([sysex_dump.md](sysex_dump.md)).
- `ObPluginCommunication` / `ObPluginCommunicationBase`: Overbridge.
- **MidiRpc** is a stock SysEx request/response protocol: `MidiRpcDispatcher` (vtable `0x4019bc74`),
  `elektron::MidiRpcMessage`, `elektron::MidiRpcDeviceUIDRequest`, the `MidiRpcFs*` classes and one
  `Request` / `Response` class pair per command.

The MidiRpc command set can be listed from the RTTI names alone (the section-3 strings that contain
`MidiRpc`). There are about 61 request classes, each with a matching `Response`:

| Group | Commands |
|---|---|
| Identity and status | `Ping`, `DeviceUID`, `SoftwareVersion`, `StorageSpace` |
| Tempo | `TempoRead`, `TempoWrite` |
| Data store | `Data{List,Copy,Move,Swap,Clear,Rename}`, `Data{Read,Write}{Open,Partial,Close}` |
| Generic files | `ReadFile`, `WriteFile`, `EnumerateFiles` |
| Raw file system | `FsRaw*`: directories, file info, open, read, write, rename, delete |
| Sample file system | the same set as `FsSample*`, plus `FsSampleAssign`, `FsSampleListRam`, `FsSampleClearRam`, `FsSampleMemoryCompaction` |
| Firmware | `OsUpgrade{Start,Write,End}` |

- The surface is the support API for a companion application: move samples and projects, report
  identity and free space, push firmware. Most of it is file-system plumbing.
- The frame is `F0 00 20 3C 10 00 <seven-bit body> F7`; the body is `msgId:u16 · replyTo:u16 ·
  cmd:u8 · payload`, and a response's command id is the request's `| 0x80`. `Ping` is `0x01`.
- Dispatch: `FUN_400de0dc` builds the request object (`switch (cmd − 1)`), and
  `MidiRpcDispatcher::handleMessageAndCreateResponse` (`FUN_400c8d42`) tests the request's class with
  a chain of dynamic casts. The shared request/response handlers cluster in `0x4014d6a2`–`0x4014d9fc`.
- Identify a command by its id, not by an address: several addresses near a class are its
  constructor, destructor and `make_shared` wrapper.
- Wire format and every address: [function_ledger.md](function_ledger.md).
- ⛔ There is no command for reading or writing arbitrary memory.
- ⛔ There is no command for keys, buttons, encoders, trigs, pads or LEDs.
- ⛔ `MidiRpcDispatcher::vfunc_0` (`0x400c8c64`) is not the command entry. It is the complete-object
  destructor (its twin `vfunc_1` at `0x400c8d28` is the deleting destructor) and tears down the file
  system members (`FileSystemDirectory` at `+0x19`, `FsRequestHandler::FileWriter` at `+4`). The
  vtable repeats with period 7 (multiple inheritance: offset-to-top, typeinfo, five handlers).

## How the names were recovered

### Analysis passes and counts

| | Pass 1 (auto-analysis) | Pass 2 (RTTI walk) | EMAC extension, first two fixes (`movclr.l`, `mac.l` address-register load) |
|---|---|---|---|
| Functions | 8,257 | 11,053 | 11,063 |
| Instructions | 426,467 | | 430,399 |
| Bytes in functions | 1,286,444 (57.9 %) | 1,346,568 (60.6 %) | 1,359,060 (61.2 %) |
| Error bookmarks | 47 | 47 | 12 |

With all four EMAC fixes the error bookmarks are 1 (the `mac.l` mode-5 fix takes 12 to 8, the
`msac.l` mode-5 fix 8 to 1). The other counts in that column were not re-measured with all four.

- The memory block is `ram` `0x40000400`–`0x4021ea3f`, 2,221,632 B, imported as
  `68000:BE:32:Coldfire` with the raw binary loader.
- Pass 1 found 3,628 defined strings, 21,976 defined data items and 43,836 symbols. Its only 36 named
  functions are Ghidra's own `switchD_*` / `thunk_*` labels: the binary carries **no symbol table**.
- Pass 2 found 1,786 classes and 1,591 vtables (804 primary; 749 classes with base-class
  information). It renamed 3,469 functions `Class::vfunc_N` and created 2,796 functions that
  auto-analysis had missed.
- Counting every namespaced function name gives 3,495, in the stock and the EMAC project alike.
  Re-counts in a project that has since been worked in give 1,787 typeinfos and 3,474 `vfunc` names;
  the differences come from what is counted and from project state, not from errors.
- The base analysis is deterministic: a fresh import reproduces the same 11,053 function addresses,
  1,786 typeinfos, 3,469 renamed functions and 47 error sites.
- The EMAC extension fixes four gaps in Ghidra's ColdFire language (`movclr.l`, and three `mac.l` /
  `msac.l` forms). With the first two fixes (`movclr.l` and the `mac.l` address-register load form),
  MAC-family instructions decoded rise from 80 to 404, with no length mismatch against
  `m68k-elf-objdump`. The one error left after all four fixes is an unrelated `jsr %pc@(…)` / `0x0000`
  boundary at `0x40115fe6`. The EMAC sites span `0x4006a570`–`0x40115fe6`. Details:
  [../../../scripts/ghidra_ext/README.md](../../../scripts/ghidra_ext/README.md). Use the EMAC project for anything
  that touches audio code.
- ⛔ Ruled out: data-in-code at the branches that land on odd addresses. They were the symptom of a
  6-byte MAC instruction decoded as 4 bytes, which puts the next fetch mid-instruction.

The scripts are `scripts/ghidra_analyze.sh` (import, auto-analysis, RTTI naming, dumps),
`scripts/ghidra/DumpFunctions.java`, `scripts/ghidra/NameFromRtti.java` and
`scripts/ghidra_decompile.sh` (decompiled text per function, by `class:`, `addr:`, `re:`, `str:`,
`xref:`, `callers:` or `mkfunc:` selector). Raw binaries declare no entry point, so the analysis
creates the entry function at `0x400004e8` itself. The method is in
[analysis_method.md](../../../notes/analysis_method.md).

### The RTTI walk

The class names are GCC-mangled **RTTI typeinfo name strings**, not a symbol table. The chain is:
typeinfo name string ← `std::type_info` object `{vptr, name_ptr[, bases]}` ← vtable
`{offset_to_top, typeinfo_ptr, vfunc0…}` ← the class's virtual functions.

`NameFromRtti.java`:

1. finds `type_info` objects structurally: pairs of in-image pointers whose second target is a
   mangled name, where genuine ones share one of about 12 `__class_type_info` vptrs;
2. finds every vtable that points at each, and labels `Class::typeinfo` and `Class::vtable`
   (`vtable_secN` for secondary tables);
3. creates functions for slot targets Ghidra had missed;
4. renames primary-vtable slot targets `Class::vfunc_N`; a target shared by several classes gets a
   plate comment instead.

Its output table `rtti_classes.tsv` lists typeinfo, class, mangled name, vtables, primary slot count
and bases.

- ⛔ **Vtable offsets.** The vtable address in `rtti_classes.tsv` is the start of the vtable
  structure. An object stores that address + 8, and `Class::vfunc_N` is at vptr + 4·N. Reading slots
  from the structure address shifts every slot by two. Check against a constructor's
  `movel #vtbl,%a?@` store: `View`'s constructor at `0x400ba298` stores `0x4019b064` =
  `0x4019b05c + 8`.
- A 2,270 B function at `0x4000b564` (Ghidra label `switchD_4000b2de::caseD_c`) fills a slot in
  hundreds of vtables. ⚠️ Read as the shared not-implemented / base handler; not confirmed.
- ⚠️ The call graph misses PC-relative calls (`jsr %pc@`, opcode `0x4eba`) and computed or indirect
  calls. Cross-check any "no callers" claim with a byte scan.

## Machines in the image

The firmware has four sample machines, types 0–3: ONESHOT, WERP, REPITCH and SLICE. Ghidra's string
pass misses the packed name table, so search the raw section: `ONESHOT` occurs once, `WERP` once,
`REPITCH` once and `SLICE` three times.

- The machine-type name table is at `0x4018fc2c` (`ONESHOT`/`SAMP`, `WERP`/`WERP`, `REPITCH`/`PTCH`,
  `SLICE`/`SLIC`); see [parameters.md](parameters.md).
- A page and parameter name run sits at about `0x401aee00`, each machine page followed by its
  parameters (… Loop Pos, Sample Level, Werp, Segment Size, SEG, Segment Mode, Repitch, Slice, Slice
  Select, Slice Length, Slice Grid, Channel, CHAN, Program …).
- Other strings: `Machine Type` (`0x401ae9c9`), `MACHINE`, `MachineListView` (`0x401a514a`),
  `SLICED SMP`, `Slice Page: %d/4`, `RANDOM` (`0x401a4c2a`), `Randomize Page %s`, and the play-mode
  list `REV · L.FWD · … · WERP · NOTE` at about `0x401a8937`.
- This build adds a fifth machine, POLY. Its code sites are in the ledger, under
  [machine assignment, the pool map and the audio ISR](function_ledger.md#machine-assignment-the-pool-map-and-the-audio-isr)
  and [MACHINE menu](function_ledger.md#machine-menu-func--src), and its byte runs in
  [docs/patch_listing.md](../docs/patch_listing.md#poly-engine).
