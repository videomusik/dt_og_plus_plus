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

## MIDI

| Function | What it does | Evidence |
|---|---|---|
| `FUN_400d486a` (428 B) | The MIDI input lane drain: pops each lane's queue through `%a4` (`pea <lane> ; jsr %a4@`) and dispatches a message through the handler table in `%a2` (`0x401b8dd4`). The lane at `0x421a9d3c` is popped and discarded (`move.l %d0,%sp@(48)`). The other lanes test the message's data pointer for NULL before reading it (`beqs` after `movea.l %a1@(4),%a0`, at `0x400d491e` and `0x400d4988`). **MIDI Loopback:** the 6 B hook at `0x400d49e0` takes the `0x421a9d3c` lane instead into the private-lane pad, which uses `%a4`, `%a2` and the frame slot `%sp@(48)` as this function does and returns into it at `0x400d48e6`. ⚠️ The pad dispatches without a NULL test of the data pointer; only this build's own tap fills that lane | ✅ objdump; port |
| hosts of the other MIDI Loopback hooks | 6 B hooks at `0x40063222` (in `FUN_40063072`), `0x40065896` (in `FUN_400657ee`), `0x400e0a00` (in `FUN_400e09d8`) and `0x400e1002`; an 8 B edit at `0x400d4dec`; the 3 B call-target edits at `0x40030dad` and `0x40032d39` (the `shortname` and `popupname` hooks above); a 3 B data edit at `0x401abd14`. Which hook plays which role is described for the features as a whole, not re-traced site by site in this image | ✅ objdump for the sites; port |

## Landing pads (stock content)

| Function | What it does | Evidence |
|---|---|---|
| `FUN_400c1338`, `FUN_400c1138`, `FUN_400c1034`, `0x400bf16c`, `0x40037a24`, `FUN_400bed3a`, `FUN_400bed9c`, `FUN_400bee02`, the spans at `0x40015558` and `0x400151ac`, `FUN_401770a6`, `FUN_40177136`, `FUN_40177176` | ✅ dead in this image by the raw scan, the listing scan and the controls ([landing_pads.md](landing_pads.md)); overwritten by this build | ✅ raw and listing scans |
