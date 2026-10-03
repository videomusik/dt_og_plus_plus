# The update path: code that is never patched

## What this is

A Digitakt can always be returned to stock firmware as long as the code that receives an OS update
and writes it to flash still works. This note lists that code as a set of address ranges, describes
the update flow they implement, and states the two rules every patch in this build follows.
`build/build.py` enforces the address rule on every build.

## The OS-update flow

1. **Transport.** An OS `.syx` ([firmware_image.md](firmware_image.md)) arrives over MIDI or USB MIDI.
2. **SysEx front door.** The functions of `SysexReceiveMenuView` take the messages in; the protected
   code at `0x40080868` is the `DigitaktSysex` destructor (its vtable at `0x40192c54` has two slots,
   the destructor and the deleting destructor at `0x4008088c`). ⚠️ The RTTI strings also name
   `Brain::processSysexDumpReceivedMessage`: it is most likely `FUN_40009182` (`0x40009182..0x40009710`),
   which builds that lambda's `std::function` and prints the "Received KIT/SOUND/PATTERN" strings — so it
   receives data dumps, not the OS update (the lambda could also have been inlined elsewhere).
3. **Receive and stage.** The host-communication task `FUN_4006753e` (5,592 B to its last instruction,
   the handler that answers `#HELLO` / `CPU4101`) runs the transfer and stages the image. It is the only
   caller of `FUN_400d85b0`, the core DSPI transfer primitive.
4. **Program the flash.** The mid-level flash operations at `0x400668e2..0x4006711a` and the raw DSPI
   driver at `0x400d7fc8..0x400d98f6` erase and write the 16 MB S25FL128S boot NOR flash. The flash sits
   behind the DSPI controller at `0xfc05c000` (registers MCR, CTAR, SR, PUSHR for commands, POPR).
   `FUN_40068be0` initialises the DSPI.
5. **The other sections.** ⚠️ Section 4 (the updater, SRAM `0x80000400`, 32 KB) is read as the flash
   programmer stub, invoked by the receive and program path above; it is not the SysEx receiver, which
   is MAIN OS code. Section 2 (`0x80000ec0`) contains a loader that reads code modules from the NOR
   flash over DSPI ([section2_map.md](section2_map.md)). The two share the low-SRAM routines at
   `0x80000400..0x80000ec0`.

## The protected set

These are the `[start, end)` load-address ranges in `PROTECTED` in `build/build.py`.

| Class | Range | Contents |
|---|---|---|
| A | `0x400d7fc8..0x400d98f6` | The raw NOR/DSPI flash driver module, `FUN_400d80a0` to `FUN_400d9858`, plus: the DMA-timer wait `FUN_400d7ff8` and its ISR `FUN_400d7fc8` (the erase primitive `FUN_400d8f1c` polls through it), three functions in `0x400d870a..0x400d8a7a` that Ghidra does not list (DSPI commands 0x48, 0x42, 0x44), and the three driver getters after `FUN_400d9858`: `FUN_400d98c2` (page size), `FUN_400d98d6` (sector size), `FUN_400d98e8` (driver mutex test). |
| A | `0x40068be0..0x40068c12` | DSPI init `FUN_40068be0` (50 B). |
| B | `0x400668e2..0x4006711a` | Mid-level flash operations: `FUN_400668e2` (reads 0x18 B of NOR at `0x380000`) and the 13 functions after it, to `FUN_40067096`. |
| C | `0x4006753e..0x40068b16` | `FUN_4006753e`, the OS-update transfer task, to its last instruction (`braw` at `0x40068b12`). |
| D | `0x40080868..0x4008088c` | The `DigitaktSysex` destructor (36 B). |
| D | `0x40058b48..0x40058b68`, `0x40059f6a..0x4005a4d6`, `0x4005a4d6..0x4005a5f6`, `0x4013a77a..0x4013a82a`, `0x4013a82a..0x4013a86a` | The six `SysexReceiveMenuView` functions — `0x40058b48` (32 B), `0x40059f6a` (914 B), `0x4005a2fc` (474 B, to its epilogue), `0x4005a4d6` (280 B), `0x4013a77a` (120 B), `0x4013a82a` (28 B) — with their entry thunks: `0x4005a5ee` (8 B, a secondary-vtable entry into `0x4005a4d6`), five at `0x4013a7f2..0x4013a82a` (adjust the object pointer, `jmp 0x4013a77a`) and five at `0x4013a846..0x4013a86a` (`bras 0x4013a82a`). |
| E | `0x4008b722..0x4008bc2e` | `FUN_4008b722` (1,292 B), the storage bulk transfer: eMMC (eSDHC at `0xfc0cc000`) to and from DDR. It also touches the DSPI / NOR, so it is protected too. |
| F | sections 2, 4 and 5 | Must stay byte-identical to stock. Only section 3 (MAIN OS) is modified. |

✅ **How the extents were fixed** (read directly in the code): three independent checks of the stock
section 3 — objdump function boundaries (each function traced to its last `rts`/`braw`, with objdump
restarted at every boundary so it cannot drift), Ghidra's function tables, and a scan of every
branch/`jsr`/`jmp`/`lea`/`pea` operand and every 2-byte-aligned word that points into or next to a range —
agree on every boundary above. From outside, every reference lands on a function start or on one of the
listed entry thunks. No run in this build touches these ranges, and no instruction the build adds
branches into them.

**Class G — code the update path also uses.** Also in `PROTECTED`: the code reached from the transfer task,
its launcher or the storage transfer, found by tracing their callers and callees (objdump and Ghidra tables
agree on every extent). Some of it is shared with normal operation; protecting shared code is harmless,
since no change needs it.

| Range | Contents | Reached from |
|---|---|---|
| `0x40066618..0x400668e2` | The task's LED callback and small helpers, the CRC-32 `FUN_400666cc` (reflected polynomial `0xEDB88320`), `FUN_40066744`, `FUN_4006677e` | the task; the CRC is shared with normal operation |
| `0x4006717a..0x4006753e` | `FUN_4006717a` (the sample-verification step that calls class B's writer `FUN_40066dd2`), `FUN_400671a2`, `FUN_400671f4` (a name lookup), `FUN_4006723c` / `FUN_4006724e` (register / unregister the LED callback), `FUN_4006725c` (chunked send), `FUN_4006729e` (the unit status report) | the task, and the init task's factory branch |
| `0x40068b16..0x40068b60` | `FUN_40068b16`, the launcher that creates the transfer task | the init task's update-mode branch only |
| `0x40068b92..0x40068bc2` | The root task: it creates the init task, then idles | every boot (shared) |
| `0x40068c12..0x40068c34` | `FUN_40068c12` | the task |
| `0x40069040..0x4006906c` | The update-mode branch of the init task `FUN_40068c42` (boot-flag bit `0x20` → `jsr` to the launcher at `0x4006905c`); the rest of the init task, the general OS start-up, is not protected | every boot passes it |
| `0x400693a0..0x4006981c` | `FUN_400693a0`, called only by `FUN_4006717a` | the sample-verification step |
| `0x40069aa0..0x4006a7f2` | The helper block: its lambdas and managers, the "OS UPGRADE COMPLETE" / "OS UPGRADE FAILED" views, the erase job `FUN_40069d24` (calls class B's erase), sample verification, flash write-back, the test views and their interrupt handlers, and `FUN_4006a78e` | the task and the init task's factory branch |
| `0x4008088c..0x400808a6` | The `DigitaktSysex` deleting destructor (vtable slot 1 at `0x40192c58`) | virtual call (shared) |
| `0x4008b524..0x4008b722` | Class E's private helpers: `FUN_4008b524` (pin-mux write), a reset hook registered next to E (soft reset), and `FUN_4008b600`, the storage-controller block writer with DMA | E, and the power/reset interrupt path |


**Class A in detail.** The principal functions that touch the DSPI at `0xfc05c000` are
`FUN_400d80f4`, `FUN_400d8136`, `FUN_400d81d0`, `FUN_400d8224`, `FUN_400d829a`, `FUN_400d8362`,
`FUN_400d84aa`, `FUN_400d85b0` (the core, with 13 PUSHR writes), `FUN_400d8a7a`, `FUN_400d8ba2`,
`FUN_400d8ce4`, `FUN_400d8fbe`, `FUN_400d9060`, `FUN_400d926e`, `FUN_400d9372`, `FUN_400d9494`,
`FUN_400d9548` and `FUN_400d964c`, plus the helper `FUN_400d8496`. The others in the module
(`FUN_400d80a0`, `FUN_400d8266`, `FUN_400d8e38`, `FUN_400d8e9a`, `FUN_400d8f1c`, `FUN_400d9148`,
`FUN_400d9772`, `FUN_400d97ee` and `thunk_FUN_400d8a7a`) are CRC, buffer and command helpers.

**Class B in detail.** Five 64 B command wrappers at `0x40066922`, `0x40066962`, `0x400669a2`,
`0x400669e2` and `0x40066a22`; seven mid-level functions that call the raw driver, `FUN_40066a62`,
`FUN_40066b3e`, `FUN_40066c1a`, `FUN_40066cf6`, `FUN_40066dd2`, `FUN_40066ea6` and `FUN_40066f82`; and
`0x40067096`.

## Two rules for every patch

1. **Address rule.** Every patched byte lies inside section 3 and outside every protected range
   (classes A to E and G). `build/build.py` refuses a run that touches a protected range. It also
   refuses overlapping runs, and after packing it re-extracts the file and requires sections 2, 4 and
   5 to equal stock.
2. **Call rule.** Inserted code never calls a flash erase or write, which means anything in A or B.
   Reading the NOR is what boot does and is fine; writing it stays the update path's job alone. The
   build does not check this rule. The disassembly of every patched code range in
   [docs/patch_listing.md](../docs/patch_listing.md) (662 instructions, 106 distinct direct branch and
   call targets) has no target in a protected range. Calls through a register (six `jsr`) are outside
   that check.

The landing pads are dead leaf functions, so by construction they are not the live driver code above.
Each pad is still checked against the ranges ([landing_pads.md](landing_pads.md)).

⛔ Ruled out: protecting everything `FUN_4006753e` can reach. The task calls the firmware's shared
utilities (memory copy, allocation, streams), so its transitive callees are about half of MAIN OS, and
the rule would forbid nearly every patch. The flash-write endpoints all sit in A and B, which the
address set covers.

## Why the set exists

There are two ways back to stock firmware: an ordinary OS update sent to the running firmware (it
runs through classes A to D and the class-G code of the transfer task, including its launcher), and
the OS upgrade mode of the startup menu ([flash_recovery.md](flash_recovery.md)). Keeping the
protected ranges and sections 2 and 4 unchanged is meant to keep both routes working. ⚠️ Where the
startup menu's upgrade code lives has not been traced.

## Related notes

- [firmware_image.md](firmware_image.md): the sections and their load addresses.
- [landing_pads.md](landing_pads.md): where new code goes instead.
- [section2_map.md](section2_map.md): section 2 and the NOR loader.
- [function_ledger.md](function_ledger.md): rows for some of the functions named here (the class-A
  driver module as one group row).
- [flash_recovery.md](flash_recovery.md): recovering from a bad flash.
