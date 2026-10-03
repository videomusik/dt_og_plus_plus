# The update path: rules and method

## What this is

A Digitakt can always be returned to stock firmware as long as the code that receives an OS update
and writes it to flash still works. This note describes that update flow in stages, says which kinds
of code make up the protected set and how its extents are fixed, and states the two rules every patch
follows, why the set exists and the design constraints that come with it.

Each OS folder's ranges: `os/<os>/notes/update_moat.md`, enforced by `PROTECTED` in
`os/<os>/build/build.py`.
OS 1.52A: [update_moat.md](../os/1.52A/notes/update_moat.md), [build.py](../os/1.52A/build/build.py).

A new OS version is analysed from its own stock file, and its OS folder gets a build only once its
protected set is vetted ([os/README.md](../os/README.md#starting-a-new-os-folder)).

## The OS-update flow, in stages

The update path as read in OS 1.52A, in five stages and in general terms. Each OS folder's
`update_moat.md` gives the same flow at address level. A new version is traced again in its own
stock file; the stages say what to look for.

1. **Transport.** An OS `.syx` ([firmware_image.md](firmware_image.md)) arrives over MIDI or USB MIDI.
2. **SysEx front door.** The functions of the SysEx receive menu view take the messages in; the
   destructor of the SysEx object is protected with them (RTTI names in OS 1.52A:
   `SysexReceiveMenuView`, `DigitaktSysex`). ⚠️ Data dumps (kits, sounds, patterns) also arrive over
   SysEx, most likely through a separate receiver that is not part of the OS update.
3. **Receive and stage.** A host-communication task, the handler that answers `#HELLO` / `CPU4101`,
   runs the transfer and stages the image. It is the only caller of the core DSPI transfer primitive.
4. **Program the flash.** Mid-level flash operations and a raw DSPI driver erase and write the 16 MB
   S25FL128S boot NOR flash. The flash sits behind the DSPI controller at `0xfc05c000` (registers MCR,
   CTAR, SR, PUSHR for commands, POPR; [hardware.md](hardware.md)). A small init function sets up the
   DSPI.
5. **The other sections.** ⚠️ The updater section is read as the flash programmer stub, invoked by the
   receive and program path above; it is not the SysEx receiver, which is MAIN OS code. The boot
   loader section contains a loader that reads code modules from the NOR flash over DSPI. The two
   share a block of low-SRAM routines.
   OS 1.52A: the updater is section 4 and the boot loader section 2 ([section2_map.md](../os/1.52A/notes/section2_map.md)).

## The protected set: what goes in

The protected set is the code an OS update needs, as load-address ranges in the MAIN OS section,
together with every other section of the image. It holds these kinds of code (the OS 1.52A note calls
them classes A to G):

| Kind | What it covers | OS 1.52A class |
|---|---|---|
| Flash driver | The raw NOR/DSPI flash driver module, with the timer wait and its interrupt that the erase primitive polls through, and the driver's small getters (page size, sector size, driver mutex test) | A |
| Flash init | The function that initialises the DSPI | A |
| Mid-level flash operations | The command wrappers and the functions that call the raw driver to read, erase and write the NOR | B |
| Transfer task | The OS-update transfer task, to its last instruction | C |
| SysEx receive path | The functions of the SysEx receive menu view with their entry thunks, and the destructor of the SysEx object | D |
| Storage bulk transfer | The bulk transfer between the eMMC and DDR; it also touches the DSPI and the NOR, so it is protected too | E |
| Code the update path also uses | The code reached from the transfer task, its launcher or the storage transfer, found by tracing their callers and callees: the task's callbacks and small helpers (a CRC among them), the launcher that creates the task, the root task, the update-mode branch of the init task (not the rest of the init task, the general OS start-up), the upgrade result views, the erase job, sample verification and flash write-back, the deleting destructor of the SysEx object, and the storage transfer's private helpers | G |

Every section other than the MAIN OS section stays byte-identical to stock; only the MAIN OS section
is modified (OS 1.52A: class F, sections 2, 4 and 5).

Some of the code the update path also uses is shared with normal operation; protecting shared code is
harmless, since no change needs it.

## Fixing the extents

An extent is fixed when three independent checks of the stock MAIN OS section agree on every
boundary:

1. **objdump function boundaries.** Each function is traced to its last `rts` or `braw`, with objdump
   restarted at every boundary so it cannot drift.
2. **Ghidra's function tables.**
3. **A reference scan.** Every branch, `jsr`, `jmp`, `lea` and `pea` operand, and every 2-byte-aligned
   word, that points into or next to a range.

From outside, every reference must land on a function start or on one of the listed entry thunks. The
code the update path also uses is found by tracing callers and callees the same way, and objdump and
Ghidra's tables must agree on every extent. No single method is trusted alone
([analysis_method.md](analysis_method.md#never-call-anything-unused-on-one-method)). No run in a build
may touch the ranges (the address rule below).

OS 1.52A: the extents fixed this way, ✅ read directly in the code: [update_moat.md](../os/1.52A/notes/update_moat.md#the-protected-set).

## Two rules for every patch

1. **Address rule.** Every patched byte lies inside the MAIN OS section and outside every protected
   range. The OS folder's `build.py` refuses a run that touches a protected range. It also refuses
   overlapping runs, and after packing it re-extracts the file and requires every other section to
   equal stock.
2. **Call rule.** Inserted code never calls a flash erase or write, which means anything in the flash
   driver or the mid-level flash operations. Reading the NOR is what boot does and is fine; writing it
   stays the update path's job alone. The build does not check this rule. It is checked on the
   disassembly of every patched code range in the OS folder's patch listing
   (`os/<os>/docs/patch_listing.md`, written by `os/<os>/build/make_listing.py`): no direct branch or
   call target may lie in the flash driver or the mid-level flash operations. Calls through a register
   are outside that check. OS 1.52A: no target lies in any protected range
   ([update_moat.md](../os/1.52A/notes/update_moat.md#the-two-rules-applied-to-this-build)).

Landing pads are dead leaf functions, so by construction they are not the live driver code above. Each
pad is still checked against the ranges ([landing_pad_method.md](landing_pad_method.md#vetting-a-new-pad)).

⛔ Ruled out: protecting everything the update transfer task can reach. The task calls the firmware's
shared utilities (memory copy, allocation, streams), so its transitive callees are a large part of
MAIN OS (about half in OS 1.52A), and the rule would forbid nearly every patch. The flash-write
endpoints all sit in the flash driver and the mid-level flash operations, which the address set
covers.

OS 1.52A: the call-rule figures for this build are in [update_moat.md](../os/1.52A/notes/update_moat.md#the-two-rules-applied-to-this-build).

## Why the set exists

There are two ways back to stock firmware: an ordinary OS update sent to the running firmware (it
runs through the flash driver and its init, the mid-level flash operations, the transfer task, the
SysEx receive path and the code around the transfer task, including its launcher), and the OS upgrade
mode of the startup menu ([flash_recovery.md](flash_recovery.md#the-recovery-ladder)). Keeping the
protected ranges and the boot loader and updater sections (sections 2 and 4 in OS 1.52A) unchanged is
meant to keep both routes working. Each OS folder's `update_moat.md` says how far the startup menu's
upgrade code has been traced in that version.

## Design constraints

These routes are closed by choice, not for lack of knowledge.

- **The boot loader and updater sections (sections 2 and 4 in OS 1.52A) are never modified.** They
  stay byte-identical to stock, which keeps the startup-menu recovery stock
  ([why the set exists](#why-the-set-exists)). It also rules out adding a new image section that loads
  above `.bss`, so all new code goes into vetted landing pads in the MAIN OS section
  ([landing_pad_method.md](landing_pad_method.md)). In OS 1.52A such a section was the only route to
  contiguous new space. Reading the updater to understand it remains open.
- **MAIN OS is not relocated or relinked.** Recovering relocations for a vtable-heavy C++ image of
  about 2.2 MB in OS 1.52A would take months and never be fully trustworthy. Every stock address
  therefore stays where it is, and every patch is an in-place edit or a jump to a pad.
- **A running unit is not instrumented over the SysEx RPC protocol.** That layer sits beside the OS
  update route. Offline emulation ([emulator.md](emulator.md)) and flash-and-observe on the test unit
  are used instead, so open questions that need a RAM image of a running unit
  (OS 1.52A: [open_questions.md](../os/1.52A/notes/open_questions.md)) stay open until one can be
  taken another way (for example over the BDM header, [hardware.md](hardware.md)).

## Related notes

- [firmware_image.md](firmware_image.md): the file format, its sections and the firmware tool.
- [landing_pad_method.md](landing_pad_method.md): where new code goes instead, and how a pad is vetted.
- [flash_recovery.md](flash_recovery.md): recovering from a bad flash.
- [hardware.md](hardware.md): the boot NOR flash, the DSPI controller and the BDM header.
- [analysis_method.md](analysis_method.md): why no single reference method is trusted alone.
- OS 1.52A: [update_moat.md](../os/1.52A/notes/update_moat.md): the ranges (classes A to G) and the flow at address level.
- OS 1.52A: [docs/reference.md](../os/1.52A/docs/reference.md#protected-ranges): the ranges as `PROTECTED` in [build.py](../os/1.52A/build/build.py) holds them.
- OS 1.52A: [section2_map.md](../os/1.52A/notes/section2_map.md): section 2 and the NOR loader.
- OS 1.52A: [function_ledger.md](../os/1.52A/notes/function_ledger.md): rows for the functions of the update path.
