# The update path: code that is never patched

## What this is

A Digitakt can always be returned to stock firmware as long as the code that receives an OS update
and writes it to flash still works. This note lists that code in OS 1.54 as a set of address ranges,
says how each range was fixed in this image, and shows how this build follows the two rules every
patch follows. The rules, the method, why the set exists and the design constraints are in
[update_moat_method.md](../../../notes/update_moat_method.md). `PROTECTED` in
`os/1.54/build/build.py` holds these ranges and enforces the address rule on every build;
[docs/reference.md](../docs/reference.md#protected-ranges) lists the same ranges. This note,
`PROTECTED` and that table change together.

⚠️ The set is read from the code of the stock OS 1.54 image. No OS update has yet been sent to a
unit running a DT OG++ build of OS 1.54.

## The protected set

These are the `[start, end)` load-address ranges in `PROTECTED`. The classes are those of
[update_moat_method.md](../../../notes/update_moat_method.md#the-protected-set-what-goes-in).

| Class | Range | Contents |
|---|---|---|
| A | `0x400e8c68..0x400ea596` | The raw NOR/DSPI flash driver module: 34 functions in Ghidra's table, from the DMA-timer ISR `FUN_400e8c68` and the timer wait `FUN_400e8c98` to the driver getters `FUN_400ea562` (page size), `FUN_400ea576` (sector size) and `FUN_400ea588` (driver mutex test). |
| A | `0x40068e54..0x40068e86` | DSPI init `FUN_40068e54` (50 B). |
| B | `0x40066b26..0x4006735e` | Mid-level flash operations: 14 functions, `FUN_40066b26` to `FUN_400672da`. |
| C | `0x40067782..0x40068d8a` | `FUN_40067782`, the OS-update transfer task, to its last instruction (`braw` at `0x40068d86`). |
| D | `0x40081770..0x40081794` | The `DigitaktSysex` destructor (36 B). |
| D | `0x40059810..0x40059830`, `0x4005ac40..0x4005b1ac`, `0x4005b1ac..0x4005b2cc`, `0x40151454..0x40151504`, `0x40151504..0x40151544` | The `SysexReceiveMenuView` functions with their entry thunks. |
| E | `0x4008d6c2..0x4008dbce` | `FUN_4008d6c2` (1,292 B), the storage bulk transfer. |
| F | sections 2, 4, 5 and 8 | Must stay byte-identical to stock. Only section 3 (MAIN OS) is modified. |

**Class G, code the update path also uses:**

| Range | Contents |
|---|---|
| `0x4006685c..0x40066b26` | The task's LED callback (at `0x4006685c`) and small helpers, with the CRC-32 `FUN_40066910` |
| `0x400673be..0x40067782` | The sample-verification step `FUN_400673be`, which calls class B's writer `FUN_40067016`, and the task's other helpers to `FUN_400674e2` |
| `0x40068d8a..0x40068dd4` | `FUN_40068d8a`, the launcher that creates the transfer task |
| `0x40068e06..0x40068e36` | The root task (its entry `0x40068e06` is passed to the task creator at `0x40069350`) |
| `0x40068e86..0x40068ea8` | `FUN_40068e86` |
| `0x400692d2..0x400692fe` | The update-mode branch of the init task `FUN_40068eb6`, with its call to the launcher |
| `0x40069636..0x40069b04` | `FUN_40069636`, called only by the sample-verification step (at `0x400673c6`) |
| `0x40069d88..0x4006aada` | The helper block: result views, erase job `FUN_4006a00c` (calls class B's erase), sample verification, flash write-back, test views |
| `0x40081794..0x400817ae` | The `DigitaktSysex` deleting destructor |
| `0x4008d4c4..0x4008d6c2` | Class E's private helpers and the reset hook registered next to it (`0x4008d4dc`) |

## How the ranges were fixed

✅ Read directly in the code of the stock OS 1.54 image (`dt_1.54_emac` and an objdump listing of the
whole section), range by range:

1. **Ghidra's function table** tiles every range that starts and ends at function boundaries; the
   function counts are in the tables above.
2. **objdump.** Every range ends with `rts`, `braw`, `bras` or `jmp`; where Ghidra's table has a
   function at the range's end, it starts right there. The two ranges that lie inside a function (the
   init task's update-mode branch and the root task) end on a branch.

How each range was first located, by comparing the two OS versions' images, is in the shared
[version comparison](../../../notes/version_comparison_1.52A_1.54.md#the-protected-set). The extents
here rest on the three checks above.
3. **A reference scan.** Every listing operand outside a range that names an address inside it, and
   every 2-byte-aligned word of the image that points inside it, was collected. All of them land on a
   function start, except these, each accounted for:
   - `0x4006685c` (from `0x40067484` and `0x40067492`): the LED callback, registered and unregistered
     by those two functions;
   - `0x40068e06` (from `0x40069350`): the root task's entry, passed to the task creator;
   - `0x40069fa2` (from `0x40157782`, an instruction and its operand word): a `jmp` into the helper
     block at the start of a routine Ghidra does not list;
   - `0x4008d4dc` (from `0x4008df60`): the reset hook;
   - `0x40151520` (a word at `0x4018cb4c`): one of the SysEx menu's entry thunks;
   - `0x40067203` (a word at `0x400f5c5a`): an odd address, so not a code pointer.

The transfer task is 5,640 B to its last instruction. How it handles section 8 has not been traced.

## The STARTUP menu's OS upgrade

✅ The OS upgrade of the STARTUP menu is section 2 code (its menu, progress and error strings are
referenced from section 2; [stock_image.md](stock_image.md#section-2s-run-base)). A build leaves
section 2 byte-identical, so that route does not run any code a build changes. ⚠️ Whether it calls
into MAIN OS at any point (for example the flash driver) has not been traced.

## Routes the set does not cover

These read or reach the flash and lie outside `PROTECTED`. No run of this build touches any of them
(a check of every run, 534 in all, against every function below, 140 functions in all):

- **The MIDI RPC OS upgrade.** `OsUpgradeMenuView` (with `performFlashInBackground`), `OsUpgradeState`,
  the `elektron::MidiRpcOsUpgrade{Start,Write,End}{Request,Response}` classes and the
  `os_upgrade_view_*` functions (RTTI). Elektron Transfer's in-OS upgrade most likely uses this route.
  How it reaches the flash writers (directly, through a message to the transfer task, or both) has not
  been traced.
- **The Outbox 8 firmware sender** `FUN_4008e696` ([stock_image.md](stock_image.md#section-8)). It
  only reads the NOR flash.
- **Other callers of the flash driver**: `FUN_4007895c`, `FUN_4008dd4e`, `FUN_4008de94`,
  `FUN_400cbad0`, `FUN_400e0090`, `FUN_400e21c6`, `FUN_400e8b7c`, `FUN_400e8be6` and `FUN_400ee6ac`,
  and the init task `FUN_40068eb6` outside its update-mode branch. ⚠️ Which of them write the flash has
  not been worked out.

⚠️ Whether to add these to the set is open. Protecting more can only refuse runs; none of this
build's runs would be refused.

## The two rules applied to this build

1. **Address rule.** Every patched byte lies inside section 3 and outside every protected range.
   `os/1.54/build/build.py` refuses a run that touches a protected range, refuses overlapping runs,
   and after packing re-extracts the file and requires sections 2, 4, 5 and 8 to equal stock. The
   reference build passes all seven of its checks.
2. **Call rule.** The disassembly of every patched code range in
   [docs/patch_listing.md](../docs/patch_listing.md) (1,797 instructions, 313 distinct direct branch
   and call targets) has no target in a protected range. Calls and jumps through a register (nine:
   from v0.2.2 also FILTER page 2's two calls of the start-up build's callable copier) are outside that
   check.

Each landing pad of this build is checked against the ranges ([landing_pads.md](landing_pads.md)).

## Related notes

- [update_moat_method.md](../../../notes/update_moat_method.md): the flow, the rules, the method.
- [docs/reference.md](../docs/reference.md#protected-ranges): the same ranges as `PROTECTED` holds
  them.
- [stock_image.md](stock_image.md): the sections, section 2 (the bootstrap) and section 8.
- [landing_pads.md](landing_pads.md): where new code goes instead.
- [flash_recovery.md](../../../notes/flash_recovery.md): recovering from a bad flash.
