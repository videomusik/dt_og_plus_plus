# Emulator

## What this is

Pieces of the Digitakt MAIN OS, and every piece of this build's inserted code, can be run on a
computer in Ghidra's p-code emulator (`EmulatorHelper`), headless, on the EMAC project. The emulator
has two jobs here: stepping inserted code against its calling contract before it is flashed, and
probing stock code that static reading cannot settle. This note covers the method, what a run can and
cannot show, and the assembler rules for hand-written ColdFire patch code. The harnesses themselves
are listed per OS folder (OS 1.52A: [os/1.52A/scripts/emu/README.md](../os/1.52A/scripts/emu/README.md)).

## Why this emulator

- **The same CPU model as the analysis.** The emulator executes Ghidra's SLEIGH description of the
  CPU, with this repo's EMAC language extension, so it decodes exactly what the disassembly shows
  ([analysis_method.md](analysis_method.md)).
- **No peripherals to model.** The harness writes the memory and registers the code under test needs.
  The program's own bytes back the rest of memory, and harness writes override them.
- **No device.** Nothing is flashed and nothing talks to a unit.
- ⛔ **Ruled out: instrumenting a running unit through the stock SysEx RPC protocol**
  (OS 1.52A: [os/1.52A/notes/architecture.md](../os/1.52A/notes/architecture.md)). That layer
  sits beside the SysEx OS-update route, and a mistake there could close the normal recovery path
  ([update_moat_method.md](update_moat_method.md), [flash_recovery.md](flash_recovery.md)). The
  OS 1.52A command set also has no command for reading or writing arbitrary memory.

What has been checked on OS 1.52A (a function single-stepped to a join, the same function run to its
return, a long builder run): [os/1.52A/notes/emulator_runs.md](../os/1.52A/notes/emulator_runs.md#what-has-been-checked).

## Running a harness

    ./scripts/ghidra_emu.sh <os> <Harness> [arguments]

- `<os>` names the OS folder (OS 1.52A: `./scripts/ghidra_emu.sh 1.52A EmuTrackAlias`).
  Harnesses live in `os/<os>/scripts/emu/`, one set per OS folder.
- It needs the EMAC project: `GHIDRA_LANG_VARIANT=emac ./scripts/ghidra_analyze.sh <os> main`.
- It opens the project read-only, runs `os/<os>/scripts/emu/<Harness>.java` as a post-script, keeps the
  full log in `work/ghidra/out/dt_<os>_emac/emu/<Harness>.log`, and prints the verdict lines.
- `GHIDRA_PROJECT` picks another project. It must belong to that OS's stock image: its name is
  `dt_<os>` or starts with `dt_<os>_` (OS 1.52A: `GHIDRA_PROJECT=dt_1.52A_seed`).
- The first argument is an OS id, never a file: a harness steps that OS's stock project. A harness that
  reads its pad from a build takes the build's extracted MAIN OS section as an argument, from
  `work/dt_<os>-<build>/` (made by `./scripts/extract.sh <os>:out/<os>/<build>.syx`). A file under
  `work/` but outside `work/dt_<os>/` and `work/dt_<os>-*/` is refused.
- Underneath it is `analyzeHeadless <project dir> <project name> -process <MAIN OS section file>
  -noanalysis -readOnly -scriptPath os/<os>/scripts/emu -postScript <Harness>.java` (OS 1.52A: the
  MAIN OS section file is `section_3_MAIN_OS.bin`).
  - `ghidra_query.sh` searches only `scripts/ghidra` and `os/<os>/scripts/ghidra`, so an emulator
    harness needs its own `-scriptPath`.
  - Harness output goes through Ghidra's INFO channel. Grep for the script name or the verdict lines;
    do not filter INFO out.
- Close the Ghidra GUI on the project first: a project is locked while it is open.

A harness is an ordinary GhidraScript in Java. The calls it is built from:

```java
EmulatorHelper emu = new EmulatorHelper(currentProgram);
emu.writeRegister("PC", entry);          // likewise "SP", "D0", ...
emu.writeStackValue(offset, 4, value);   // seed a stack argument
emu.step(monitor);                       // one instruction
long d0 = emu.readRegister("D0").longValue();
Address pc = emu.getExecutionAddress();
```

## The pre-flash gate: step the calling contract

The rule for inserted code is to step it before it is flashed. The recipe:

1. **Stub the callees.** Intercept the PC at each callee's entry (see
   [Stubbing by PC intercept](#seeding-stock-code)).
2. **Seed the caller's frame by hand**: the return address and the stack arguments the hook site
   passes.
3. **Step the pad's real bytes**, read from a build's MAIN OS or built into the harness.
4. **Assert the contract, not only the arithmetic.** For every case:
   - the callee sees the caller's stack argument, so the frame is not shifted;
   - the return value is the intended one, inside the range and outside it;
   - SP is exactly balanced at the `rts`;
   - the callee-saved registers are unchanged;
   - control returns to the caller and nowhere else.

**Why the stack matters.** ColdFire C code passes arguments on the stack. A pad that wraps a call
(the hook does `jsr pad`, the pad does `jsr original`) pushes one more return address, so the original
reads the pad's return address as its first argument. When that argument is an object pointer the
callee calls through, the result is a jump through garbage and a vector-4 illegal-instruction
exception. At a hook site on the startup draw path, that fault stops the unit at boot
([flash_recovery.md](flash_recovery.md)). Two shapes avoid it:

- **Re-push the argument** in the pad: `movel %sp@(4),%sp@-` before the inner call, `addql #4,%sp`
  after it.
- **Use a shape with no frame change**, such as a tail trampoline (below).

⛔ **Ruled out: checking only the register contract** (`d0`/`d1`/`a0`/`a1` caller-saved, `d2`–`d7` and
`a2`–`a6` preserved). It says nothing about the stack.

Examples from OS 1.52A (a callee that calls through its first argument, a startup hook site that
re-pushes it, and a harness that steps two track-remap pads):
[os/1.52A/notes/emulator_runs.md](../os/1.52A/notes/emulator_runs.md#examples-from-this-build).

⚠️ **Diagnose a red result before believing or dismissing it.** A harness that compares an unsigned
32-bit register read against a signed expectation reports a false FAIL for track −1. The fault is
then in the test, not in the pad.

## Seeding stock code

- **Stack arguments and the callee's prologue.** On entry SP points at the return address, and the
  arguments sit at SP+4, +8, +0xc and +0x10. A prologue moves them: after `lea (-n,SP),SP` the first
  argument sits at `(n+4,SP)`. Seed at the caller's SP and let the prologue run, or seed at the
  post-prologue offsets (OS 1.52A example:
  [os/1.52A/notes/emulator_runs.md](../os/1.52A/notes/emulator_runs.md#seeding-stock-code)).
- **Start mid-function** to skip a heavy prologue: seed the frame pointer and SP yourself, and null
  any callback slots a loop tests so it skips them.
- **Stubbing by PC intercept.** When the PC reaches a stub target, read the return address from
  `(SP)`, add 4 to SP, set the PC to the return address, and put the stub's result in `D0` (a scratch
  address, for an allocator). OS 1.52A example, counting a constructor's loop this way:
  [os/1.52A/notes/emulator_runs.md](../os/1.52A/notes/emulator_runs.md#seeding-stock-code).
- **Hardware waits spin forever.** Code that polls a ready bit, for example bit `0x80` at offset `0x1e`
  of an eDMA TCD, never sees it set. The harness sets the bit at the live address whenever a read
  there spins (OS 1.52A: `EmuISR5`).
- **Uninitialised reads are warnings, not faults**, so a run keeps going. The warned addresses are the
  list of what to seed next.
- **The audio ISR** (OS 1.52A): the seeding recipe is in
  [os/1.52A/notes/emulator_runs.md](../os/1.52A/notes/emulator_runs.md#seeding-stock-code-the-audio-isr).

## What a run can and cannot show

- ⛔ **Ruled out: "it did not happen in the emulator, so it does not happen".** A hand-seeded run
  executes only the paths its seeding reaches. ISR runs with no voice genuinely started show the idle
  defaults, never the behaviour of a playing voice; on the device (OS 1.52A) all eight tracks play
  SLICE at once. To probe runtime behaviour, first drive a genuinely active voice (seed the full voice
  state, or start from a memory image of a running unit), and check every emulator conclusion against
  the device.
- **A run that faults stops counting there.** Nothing after the fault is executed, so counts taken
  from such a run (calls, iterations) are truncated. OS 1.52A example:
  [os/1.52A/notes/emulator_runs.md](../os/1.52A/notes/emulator_runs.md#what-a-run-can-and-cannot-show).
- **An instruction the emulator cannot execute:** `nbcd` (OS 1.52A: at `0x400721ce` in `FUN_40072178`)
  decodes, but the p-code emulator does not implement its `bcdAdjust` operation. A harness must step
  over it.
- **EMAC arithmetic is approximate:** no MACSR flags, no saturation, 32-bit accumulators
  ([scripts/ghidra_ext/README.md](../scripts/ghidra_ext/README.md)). Pads stepped as plain integer code
  are unaffected.
- **`mvs`, `mvz` and `mov3q` leave the condition codes alone in the emulator.** Ghidra's 68000 spec
  writes the register and nothing else, while on ColdFire `mvs`/`mvz` set N and Z and clear V and C. A
  branch on their flags takes the previous instruction's in the emulator. Patch code follows them with
  `tst` before a branch, which is right on both.
- **No peripherals and no DMA.** eDMA transfers never happen, so, for example, the sample fetch buffer
  at `0x800013a0` (OS 1.52A) never receives sample data.
- **C++ state built at construction is out of practical reach.** The machine list's cursor-navigation
  clamp is a `std::function` stored in the view (at `+0x1d4` in OS 1.52A) when the view is constructed.
  Emulating it needs the whole constructor and its object graph, so `EmuMachineList` (OS 1.52A) proves
  the item count but not the navigation.
- **A passing harness is not a device test.** It shows that the code does what the harness asserts, in
  the state the harness sets up.

## Writing ColdFire patch code

The assembler is GNU `as` with `-mcpu=54455`; the disassembler is `objdump -m m68k:cfv4e`.

### Rules

- ⛔ **Never hand-predict an encoding; assemble it.** Write the `.s`, run
  `m68k-elf-as -mcpu=54455`, then `m68k-elf-objcopy -O binary -j .text`, then
  `m68k-elf-objdump -D -b binary -m m68k:cfv4e --adjust-vma=<load address>`, and read it back.
  Hand-written hex belongs in build data only after it has been assembled and disassembled once.
  Example: `movel %a4@(300),%d2` is `242c 012c`; `2434 012c` is a plausible wrong guess.
- ⛔ **Never hand-count byte offsets; derive them.** In a build script, find landmarks with
  `bytes.index(...)`, compute a branch target as `pos + 2 + disp`, and take a function's end from its
  `rts` in the disassembly.
- **Position independence.** A pad that uses only PC-relative branches and absolute-long data
  references can be moved to another free slot without reassembly, once every absolute reference to
  the pad's own entries, strings or tables is repointed ([landing_pad_method.md](landing_pad_method.md)).
  Disassemble it at the new address and check that every branch still lands inside the pad.

### Not available on ColdFire

| Missing | Use instead |
|---|---|
| `movem.l` with `-(An)` or `(An)+` (control modes only) | `lea %sp@(-n),%sp` then `moveml ...,%sp@`, as in MIDI Loopback's code at `0x40014d14` (OS 1.52A); or single `move.l`s |
| `mulu.l #imm,Dn` (gas: "operands mismatch") | a shift-add chain. `S*0xa2` = `movel d0,d1 ; lsl #2 ; add d0 ; lsl #4 ; add d0 ; lsl #1` (S×4 → ×5 → ×80 → ×81 → ×162), as in the POLY pool's sound-follow code at `0x40037776` (OS 1.52A) |
| the full-format `(d16,An,Xn)` indexed mode | the brief `(d8,An,Xn*scale)` form only; for an array further away, compute one pointer and use `(d16,An)` off it (below) |
| `DBcc` (`dbra`, `dbf`) | explicit `addq` / `cmp` / `bcc`, or a `subql #1,Dn ; bpls` countdown |
| `andi` / `ori` / `eori` in `.b` or `.w` (gas: "invalid instruction for this architecture; needs 68000 or higher") | the `.l` forms, for example `andil #imm,Dn` (`0280 xxxxxxxx`, 6 B) |

**Two parallel per-voice arrays through one computed pointer.** The brief indexed mode's 8-bit
displacement cannot reach a second array a few hundred bytes away, but `(d16,An)` can. Form a pointer
to the element of the first array with the brief indexed mode, then read the second array at a fixed
`(d16,An)` offset from that pointer:

```
lea    <first[]>,%a1           | first[], stride 4
lea    %a1@(0,%d1:l:4),%a4     | a4 = &first[v]
movel  %a4@,%d2                | first[v]
movel  %a4@(<d16>),%d2         | second[v]; <d16> = &second[0] - &first[0]
```

Two arrays cost one base register and one `lea` per candidate. This works for any pair within ±32 KB.
The brief form's `*4` scale is supported (`49f1 1c00`). OS 1.52A example, the POLY pool's note-off
scan: [os/1.52A/notes/emulator_runs.md](../os/1.52A/notes/emulator_runs.md#writing-coldfire-patch-code).

### Forms used and verified in this build

The forms used and verified are recorded per OS folder (OS 1.52A:
[os/1.52A/notes/emulator_runs.md](../os/1.52A/notes/emulator_runs.md#forms-used-and-verified-in-this-build)).

### Reading objdump output

- In the brief indexed mode the displacement prints with no `0x` and reads like decimal: `49f4 1820`
  prints as `%a4@(20,%d1:l)` and means +0x20 = 32. Check the extension word's low byte.
- A negative 8-bit displacement prints as a 64-bit hex value: `%a1@(ffffffffffffffff,%d0:l)` is −1.
- `remsl Dx,Dx,Dx` with the same register is a signed divide
  ([analysis_method.md](analysis_method.md)).

### Hook shapes that keep the change small

The full list of hooks and pads is in each OS folder's notes and patch listing (OS 1.52A:
[os/1.52A/notes/landing_pads.md](../os/1.52A/notes/landing_pads.md) and
[os/1.52A/docs/patch_listing.md](../os/1.52A/docs/patch_listing.md)). The sites where each shape below
is used are listed per OS folder (OS 1.52A:
[os/1.52A/notes/emulator_runs.md](../os/1.52A/notes/emulator_runs.md#hook-use-sites)).

- **Insert before the branches.** New code placed ahead of every branch and its target changes no
  displacement, so the rest of the pad stays byte-identical and the equivalence argument stays at the
  byte level.
- **Entry trampoline.** `jmp pad ; nop` over the first 8 B of a stock function (whole instructions);
  the pad's stub re-executes those 8 B verbatim and jumps to +8. The displaced bytes must end on an
  instruction boundary and contain nothing PC-relative, and nothing may enter the function mid-body.
- **Tail trampoline.** Where a stock function ends in `jmp target`, replace it with `jmp pad`. The pad
  inherits the identical frame, can rewrite an argument in place, and ends with `jmp target`; nothing
  is pushed or popped.
- **Wrapped call with the argument re-pushed**, as above.
