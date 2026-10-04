# Function ledger

One row per function worked out in OS 1.54, address first. Every row gives its evidence and a
confidence mark ([marks](../../../notes/README.md#confidence-marks)). "Port" in the evidence means the
site was located by comparing the two images and then checked in this image as the
[version comparison](../../../notes/version_comparison_1.52A_1.54.md) describes; the row's facts are
read in this image.

## Startup and memory

| Function | What it does | Evidence |
|---|---|---|
| `FUN_400004b2` (54 B) | Zeroes `.bss`, `0x40253000..0x439d1000`, in 16 B steps | ✅ objdump |
| `0x40068fea` (in the init code) | Walks the C++ static-initialiser table: count 130 from `0x40252518`, table from `0x4025251c`; halts on a NULL entry | ✅ objdump |

## The update path and the flash

| Function | What it does | Evidence |
|---|---|---|
| `FUN_40067782` (5,622 B in Ghidra's table; 5,640 B to its last `braw` at `0x40068d86`) | The OS-update transfer task. Protected (class C) | ✅ objdump, Ghidra; [update_moat.md](update_moat.md) |
| `FUN_400e8c68`..`FUN_400ea588` | The NOR/DSPI flash driver module, 34 functions. Protected (class A) | ✅ Ghidra table, objdump; [update_moat.md](update_moat.md) |
| `FUN_40066b26`..`FUN_400672da` | Mid-level flash operations, 14 functions. Protected (class B) | ✅ Ghidra table, objdump |
| `FUN_40069636` (1,202 B) | Called only by the sample-verification step `FUN_400673be` (at `0x400673c6`). Protected (class G) | ✅ listing scan |
| `FUN_4008e696` (500 B) | ⚠️ The Outbox 8 firmware sender: reads an 8 B header and a blob from the NOR flash through `FUN_400e8be6` and `FUN_400e971a`, checksums it, and sends it as SysEx in 101-byte chunks with the header `F0 00 20 3C 17 00 7E`. Only caller: `0x4008e93e`. Reads the flash; writes nothing there | ✅ objdump for what it does; ⚠️ "Outbox 8" from the device id and the strings ([stock_image.md](stock_image.md#section-8)) |

## The kit loader

| Function | What it does | Evidence |
|---|---|---|
| `0x4007a4ce` (28 B) | The kit gate: accepts a stored kit only if its version word is 10, then branches to the kit loader `0x4007a386` | ✅ objdump |
| `FUN_4007a236` (336 B) | The per-sound deserializer. Keeps the stored machine byte only if `machine + 1 < 5` (`moveq #5,%d2` at `0x4007a2d0`). **POLY:** this build changes the immediate to 6, so machine 4 survives a project reload | ✅ objdump; port |

## Views and parameter pages

| Function | What it does | Evidence |
|---|---|---|
| `MachineParameterPageView::vfunc_41` @`0x400308d0` (16 B) | Replaces its first argument with `this@104` (the project object) and tail-jumps to the parameter-set resolver `FUN_400191fe(project, track, x)`. **POLY:** the tail `jmp` at `0x400308da` goes to the parameter-read alias pad `0x400bed3a`, which substitutes the Source for a −1 track on an audio track and then jumps to `FUN_400191fe` | ✅ objdump; port |
| `MachineParameterPageView::vfunc_22` @`0x400309b0` (180 B) | Parameter write. Classifies the page's track with `FUN_4001d24e(this@116)`. **POLY:** that call (at `0x400309d0`) goes to the parameter-write alias pad `0x400bee02`; the `paramId == 10` guard at `0x400309e4` becomes unsigned (`6d42` → `6542`); the `pea -1` at `0x40030a2a` becomes `move.l %d2,-(%sp) ; nop`, so `vfunc_41` gets the pad's result | ✅ objdump; port |
| `MachineParameterPageView::vfunc_37` @`0x40030c0c` (434 B) | **MIDI Loopback:** its call to `FUN_4000fe8a` goes to the `shortname` display pad, which reads the caller's frame at `sp@(100)`; the frame layout is that of the code the pad was written for (same prologue, same size) | ✅ objdump; port |
| `ParameterPageView::vfunc_17` @`0x40032a78` (788 B) | Composes the encoder popup (`"%s=%s"`). **MIDI Loopback:** its `jsr FUN_4000feac` at `0x40032d36` goes to the `popupname` pad, which makes the virtual call `%a2@(84)` with `(%a2, %d2, −1)`; the caller makes the same call six instructions earlier, so `%a2` (this) and `%d2` (the parameter) are what the pad expects | ✅ objdump; port |
| `SamplePageView::vfunc_39` @`0x4003aae4` (124 B) | Slot → paramId for SRC page 1. Current-track branch: `FUN_4001d24e(this@116)` at `0x4003ab12`. **POLY:** that call goes to the machine-alias pad (the pad re-pushes its argument before calling `FUN_4001d24e`) | ✅ objdump; port |
| `SamplePageView::vfunc_11` @`0x40039ec0` (260 B) | The SRC page's tick. Pushes the selected voice's position into the marker widget at `page + 0x1d8` (`FUN_400bd1c8`) and the grid widget at `page + 0x20c` (`FUN_400bd6b2`); the two widgets are `0x34` apart | ✅ objdump |
| `FUN_400bd254` (876 B) | The marker widget's draw (ONESHOT and REPITCH): `%a2` = widget, **`%d2` = canvas** (`move.l %sp@(60),%d2`; every line call pushes `%d2`). Every path reaches the dirty-byte clear at `0x400bd5b0` (`clr.b %d0 ; move.b %d0,%a2@(49)`, 6 B), followed by the register restore. Besides the waveform it draws marker lines when `%a2@(50)` is set (`0x400bd442`, `0x400bd4bc`). **Pool cursors:** that clear becomes `jsr` to the marker stub `0x40015558`, whose first instructions take the widget from `%a2` and the canvas from `%d2` (`move.l %d2,%d0` at `0x4001555e`) | ✅ objdump; port, with the canvas register adapted to this draw ([version comparison](../../../notes/version_comparison_1.52A_1.54.md#caller-registers)) |
| `FUN_400bd732` (716 B) | The slice-grid widget's draw: `%a2` = widget, `%d4` = canvas, both kept to the end. Every path reaches the dirty-byte clear at `0x400bd9ee` (`clr.b %d7 ; move.b %d7,%a2@(45)`, 6 B), followed by the register restore. **Pool cursors:** that clear becomes `jsr` to the grid stub `0x40015562` | ✅ objdump; port |

## The audio ISR and the engine

| Function | What it does | Evidence |
|---|---|---|
| `FUN_40077420` (4,280 B) | The audio ISR. **POLY, mute by origin, tick-wipe fix:** five hook sites (`0x400776e6`, `0x400777a2`, `0x400777ca`, `0x400777d6`, `0x40077d72`); the code around each is unchanged in shape. A per-track array copied out at `0x40078214` has a stride of 80 B | ✅ objdump; port |
| `FUN_400772e6` (124 B) | The voice build. **POLY:** the kit-load detour is entered from `0x4007735a` | ✅ objdump; port |
| `FUN_400771e8` (94 B) | **POLY:** its first 8 B become `jmp 0x400bed9c ; nop`, and the pad re-executes them | ✅ objdump; port |
| `FUN_40074df2` (204 B) | The SLICE render: reads Select as a signed byte at `paramblock + 8` (`0x40074e30`) and applies `max(Select, 0)`. **SLICE round robin:** the hook at `0x40074e38` | ✅ objdump; port |
| `FUN_40076258` (66 B) | The cursor's data source: the playing position as an 8.8 fraction, 0 when silent | ✅ objdump |
| `FUN_400c1268` (208 B) | The vertical-line primitive the pool cursors draw with | ✅ objdump |

## The recorder

The engine state and how Chain Recording hooks it: [features/chain_record.md](features/chain_record.md).

| Function | What it does | Evidence |
|---|---|---|
| `FUN_40076540` (32 B) | End of recording: state 3 at `0x4199f114`, then a message (`0x401f8ce0`) through `FUN_40001b7a`. **Chain Recording:** `stop_pad` continues here after the last slot, a slot cut short by the cap, and whenever chain mode is off | ✅ objdump |
| `FUN_400765d8` (8 B) | Returns the recorder state | ✅ objdump |
| `FUN_400765e0` (54 B) | Sets RLEN (`0x4199f0fc`) from an index: `1 << index`, or 0 (MAX) for an index above 7; only in states 0 and 4 | ✅ objdump |
| `FUN_40076616` (58 B) | The recording length in samples from RLEN and the tempo (`FUN_400770c8`); 1,584,000 (33 s) at MAX. **Chain Recording:** its calls at `0x400767fc` and `0x40076900` go through `len_pad`, the one at `0x400a8e7c` through `mem_pad` | ✅ objdump |
| `FUN_40076650` (550 B) | The recorder's per-block routine, called by the audio ISR for every 32-sample block: source copy into `0x800032c4`, level meter, threshold test (state 1), write (state 2: upper 16 bits at `0x4237ef90 + 2 × position`, bits 15..8 at `0x421fc410 + position`), stop at LEN or at the 1,584,000-sample cap, by a tail call of `FUN_40076540`. After a threshold hit at sample j it writes the first 32 − j samples of that block. **Chain Recording:** hooks at `0x400767fc` (threshold hit) and `0x4007687a` (stop) | ✅ objdump, decompile |
| `FUN_40076898` (8 B) | Returns 1,584,000, the sample memory | ✅ objdump |
| `FUN_400768a0` (46 B) | ARM: from state 0 or 4 to state 1, write position 0, with interrupts masked; returns 1 if it armed. **Chain Recording:** the position clear at `0x400768c2` goes through `arm_pad` | ✅ objdump |
| `FUN_400768ce` (74 B) | REC: from state 0, 1 or 4 to state 2, write position 0, LEN = `FUN_40076616()`, with interrupts masked. **Chain Recording:** `0x400768fa` through `arm_pad`, `0x40076900` through `len_pad` | ✅ objdump |
| `FUN_40076918` (36 B) | The STOP key: in state 2, `FUN_40076540` | ✅ objdump |
| `FUN_4007693c` (82 B) | ABORT: from state 1, 2 or 4 to state 0, write position 0 | ✅ objdump |
| `FUN_4007699e` (102 B) | The lowest and highest sample of a range of the recording | ✅ objdump |
| `FUN_40076a04` (254 B) | The normaliser after state 3: pads the recording to at least 144 samples, finds its peak, returns to state 0 if it is silent, else scales the whole recording to full level and sets state 4 | ✅ objdump |
| `FUN_40076b02` (32 B) | Returns the state and the write position together, with interrupts masked | ✅ objdump |
| `SamplerView::vfunc_2` @`0x400a9784` (1,244 B) | The recorder page's keys: in state 0 YES arms and FUNC+YES records, both clearing the view's waveform cache; FUNC+NO aborts in state 1; YES stops in state 2; trim, save and preview in state 4; NO in state 0 closes the page | ✅ decompile |
| `SamplerView::vfunc_4` @`0x400a8c94` (2,800 B) | The recorder page's draw. **Chain Recording:** the MEM line's length (`0x400a8e7c`), the YES prompt (`0x400a8f48`, cleanup `0x400a8f60`) and the ARMED line (`0x400a9026`, cleanup `0x400a9044`) | ✅ objdump, decompile |
| `SamplerView::vfunc_11` @`0x400a7b68` (226 B) | The page's update: reads state and position (`FUN_40076b02`), extends the waveform cache (126 columns over 33 s) up to the position, invalidates the view | ✅ decompile |
| `SamplerView::vfunc_17` @`0x400a7e38` (668 B) | The page's encoders: in states 0 and 1, E sets RLEN (`FUN_400a786c`), F THR, G the source, H MON, tested in that order; in state 4, A–D move the trim points. **Chain Recording:** the exit taken when the H test fails (`0x400a7f40`) goes through `enc_pad`, which handles encoder D | ✅ objdump, decompile |
| `SamplerLedView::vfunc_2` @`0x40036548` (324 B) | Another key handler that calls the recorder's ARM, REC, STOP and ABORT by state | ✅ decompile |
| `FUN_400c03e6` (40 B) | Encoder event test: true when the event's id (`event + 12`) is index + 1; index −3 accepts ids 1–8. Encoder D is id 4 | ✅ objdump |
| `FUN_400c047e` (30 B) | An encoder event's step, `event + 16`, times its second argument, or its third when the fast flag `event + 20` is set | ✅ objdump |
| `FUN_400c0816` (80 B) | The encoder step accumulator the page's setters use | ✅ objdump |
| `FUN_400c9a3a` (34 B) | `View::invalidate`: sets the view's dirty byte (`+0x14`) unless `+0x17` is set, and passes the call to the parent (`+0x2c`) | ✅ objdump |
| `FUN_400c27e8` (442 B) | Draws formatted text: (canvas, font, x, y, alignment, format, values…). The recorder page passes alignment 2 with the x of a column's centre | ✅ objdump of its callers; ⚠️ alignment 2 = centred, from the coordinates |

## MIDI

| Function | What it does | Evidence |
|---|---|---|
| `FUN_400d486a` (428 B) | The MIDI input lane drain: pops each lane's queue through `%a4` (`pea <lane> ; jsr %a4@`) and dispatches a message through the handler table in `%a2` (`0x401b8dd4`). The lane at `0x421a9d3c` is popped and discarded (`move.l %d0,%sp@(48)`). The other lanes test the message's data pointer for NULL before reading it (`beqs` after `movea.l %a1@(4),%a0`, at `0x400d491e` and `0x400d4988`). **MIDI Loopback:** the 6 B hook at `0x400d49e0` takes the `0x421a9d3c` lane instead into the private-lane pad, which uses `%a4`, `%a2` and the frame slot `%sp@(48)` as this function does and returns into it at `0x400d48e6`. ⚠️ The pad dispatches without a NULL test of the data pointer; only this build's own tap fills that lane | ✅ objdump; port |
| hosts of the other MIDI Loopback hooks | 6 B hooks at `0x40063222` (in `FUN_40063072`), `0x40065896` (in `FUN_400657ee`), `0x400e0a00` (in `FUN_400e09d8`) and `0x400e1002`; an 8 B edit at `0x400d4dec`; the 3 B call-target edits at `0x40030dad` and `0x40032d39` (the `shortname` and `popupname` hooks above); a 3 B data edit at `0x401abd14`. Which hook plays which role is described for the features as a whole, not re-traced site by site in this image | ✅ objdump for the sites; port |

## Landing pads (stock content)

| Function | What it does | Evidence |
|---|---|---|
| `FUN_400c1338`, `FUN_400c1138`, `FUN_400c1034`, `0x400bf16c`, `0x40037a24`, `FUN_400bed3a`, `FUN_400bed9c`, `FUN_400bee02`, the spans at `0x40015558` and `0x400151ac`, `FUN_401770a6`, `FUN_40177136`, `FUN_40177176` | ✅ dead in this image by the raw scan, the listing scan and the controls ([landing_pads.md](landing_pads.md)); overwritten by this build | ✅ raw and listing scans |
| `FUN_40124a6c` (88 B), `FUN_40124ac4` (110 B) | Test two single- (two double-) precision values for a NaN pattern and return 1 if either is one. ✅ Dead in this image by the full vetting recipe ([landing_pads.md](landing_pads.md#the-new-pad-fun_40124a6c--fun_40124ac4)); overwritten by Chain Recording. ⚠️ Unused soft-float helpers, by what they do; not yet fill-tested on a unit | ✅ objdump, raw and listing scans, switch tables |
