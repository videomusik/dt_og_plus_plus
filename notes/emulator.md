# Emulator

## What this is

Pieces of the Digitakt MAIN OS, and every piece of this build's inserted code, can be run on a
computer in Ghidra's p-code emulator (`EmulatorHelper`), headless, on the EMAC project. The emulator
has two jobs here: stepping inserted code against its calling contract before it is flashed, and
probing stock code that static reading cannot settle. This note covers the method, what a run can and
cannot show, and the assembler rules for hand-written ColdFire patch code. The harnesses themselves
are listed in [scripts/emu/README.md](../scripts/emu/README.md).

## Why this emulator

- **The same CPU model as the analysis.** The emulator executes Ghidra's SLEIGH description of the
  CPU, with this repo's EMAC language extension, so it decodes exactly what the disassembly shows
  ([analysis_method.md](analysis_method.md)).
- **No peripherals to model.** The harness writes the memory and registers the code under test needs.
  The program's own bytes back the rest of memory, and harness writes override them.
- **No device.** Nothing is flashed and nothing talks to a unit.
- ⛔ **Ruled out: instrumenting a running unit through the stock SysEx RPC protocol**
  ([architecture.md](architecture.md)). That layer
  sits beside the SysEx OS-update route, and a mistake there could close the normal recovery path
  ([update_moat.md](update_moat.md), [flash_recovery.md](flash_recovery.md)). The stock command set also
  has no command for reading or writing arbitrary memory.

What has been checked:

- ✅ Single-stepping `FUN_40074af2` from its entry to the join at `0x40074b62` executes the prologue
  (`lea`, `moveq`, `movem.l`), the stack-argument reads, the ISA-C byte extends `mvz.b` / `mvs.b`,
  `muls.l`, `lsl.l`, the `not.l` / `subx.l` clamp and the NOTE-mode arithmetic `(note − 12) & mask`,
  each with the expected result.
- ✅ `EmuAf2` runs `FUN_40074af2` to its return. With Select 3, grid 2 (16 slices) and sample slot 5
  it returns start `0x1520` and end `0x1540`, the seeded slice-table entries for slice index 2
  (Select − 1).
- ✅ `EmuBuild` runs the engine-object builder `FUN_4007489e` for 3,712 steps, including its
  MAC-accumulator loop, without a fault. The EMAC arithmetic is approximate (see
  [Limits](#what-a-run-can-and-cannot-show)).

## Running a harness

    ./scripts/ghidra_emu.sh <Harness> [arguments]

- It needs the EMAC project: `GHIDRA_LANG_VARIANT=emac ./scripts/ghidra_analyze.sh dt main`.
- It opens the project read-only, runs `scripts/emu/<Harness>.java` as a post-script, keeps the full
  log in `work/ghidra/out/dt_1.52A_emac/emu/<Harness>.log`, and prints the verdict lines.
- Underneath it is `analyzeHeadless <project dir> <project name> -process section_3_MAIN_OS.bin
  -noanalysis -readOnly -scriptPath scripts/emu -postScript <Harness>.java`.
  - `ghidra_query.sh` searches only `scripts/ghidra`, so an emulator harness needs its own
    `-scriptPath`.
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
callee calls through (`FUN_4001ccc4` does this, and has 83 callers), the result is a jump through
garbage and a vector-4 illegal-instruction exception. At a hook site on the startup draw path, such
as `0x4002b012`, that fault stops the unit at boot ([flash_recovery.md](flash_recovery.md)). Two
shapes avoid it:

- **Re-push the argument** in the pad: `movel %sp@(4),%sp@-` before the inner call, `addql #4,%sp`
  after it. The hook at `0x4002b012` in this build does this (pad `0x400afe80`).
- **Use a shape with no frame change**, such as a tail trampoline (below).

⛔ **Ruled out: checking only the register contract** (`d0`/`d1`/`a0`/`a1` caller-saved, `d2`–`d7` and
`a2`–`a6` preserved). It says nothing about the stack.

Example: `EmuTrackAlias` steps the two track-remap pads, `0x400aff0e` and `0x400afe80`, with their
different out-of-range rules, over tracks 0–7, 8, 9, 15, 16 and −1; 26 cases pass over the two pads.

⚠️ **Diagnose a red result before believing or dismissing it.** A harness that compares an unsigned
32-bit register read against a signed expectation reports a false FAIL for track −1. The fault is
then in the test, not in the pad.

## Seeding stock code

- **Stack arguments and the callee's prologue.** On entry SP points at the return address, and the
  arguments sit at SP+4, +8, +0xc and +0x10. A prologue moves them: `FUN_40074af2` begins with
  `lea (-0x18,SP),SP` and then reads its arguments at `(0x1c,SP)`, `(0x20,SP)` and `(0x28,SP)`. Seed
  at the caller's SP and let the prologue run, or seed at the post-prologue offsets.
- **Start mid-function** to skip a heavy prologue: seed the frame pointer and SP yourself, and null
  any callback slots a loop tests so it skips them.
- **Stubbing by PC intercept.** When the PC reaches a stub target, read the return address from
  `(SP)`, add 4 to SP, set the PC to the return address, and put the stub's result in `D0` (a scratch
  address, for an allocator). `EmuMachineList` counts the real iterations of the machine-list
  constructor's add loop (`0x4002a282..0x4002a368`) this way, without building the C++ object graph:
  4 machines with the stock bytes, 5 with the machine-enum edit in `FUN_4002295c`.
- **Hardware waits spin forever.** Code that polls a ready bit, for example bit `0x80` at offset `0x1e`
  of an eDMA TCD, never sees it set. The harness sets the bit at the live address whenever a read
  there spins (`EmuISR5`).
- **Uninitialised reads are warnings, not faults**, so a run keeps going. The warned addresses are the
  list of what to seed next.
- **The audio ISR `FUN_40077120`:**
  - Seed the machine-type array `DAT_41960316[0..7]` directly. The ISR copies it from `0x800018bc`
    only for tracks whose bit is set in the pass's trig mask, which is empty when idle
    ([render_path.md](render_path.md)).
  - An all-zero event queue has a null head, so the ISR skips note handling and reaches the render.
  - To inject a note-on, seed the bucket head `_DAT_421b7940`, one bucket and one event
    (`event[1]` = 1 note-on, `event[2]` = track, `event[3]` = priority, `event[6]` = note). Map
    `0x42180000..0x421c0000` (the node and bucket free lists) and scratch memory at `0x41a00000`.
  - Seed the free lists `_DAT_421b7948`, `_DAT_421850b0` and `_DAT_421b794c`: `FUN_400dd9ac` spins when
    the bucket free list is empty.
  - `_DAT_800019b0 = 0x530` skips the call to `FUN_4007699e`.
  - `EmuISR5`, `EmuISR6` and `EmuISR8` carry these recipes.

## What a run can and cannot show

- ⛔ **Ruled out: "it did not happen in the emulator, so it does not happen".** A hand-seeded run
  executes only the paths its seeding reaches. ISR runs with no voice genuinely started show the idle
  defaults, never the behaviour of a playing voice; on the device all eight tracks play SLICE at once.
  To probe runtime behaviour, first drive a genuinely active voice (seed the full voice state, or start
  from a memory image of a running unit), and check every emulator conclusion against the device.
- **A run that faults stops counting there.** ⚠️ ISR runs made before the EMAC language's mode-5 fix
  faulted at `0x40075cfa` in `FUN_400754fe`, a `mac.l` with `(d16,An)` addressing that the stock
  language decoded 2 bytes short, after two calls of the SLICE window function. The pipeline calls the
  window function once per track per pass ([render_path.md](render_path.md)), so call counts from such
  a run are truncated. This rests on the two facts above; no run with the fixed language that reaches
  all eight calls is recorded.
- **An instruction the emulator cannot execute:** `nbcd` at `0x400721ce` in `FUN_40072178` decodes, but
  the p-code emulator does not implement its `bcdAdjust` operation. A harness must step over it.
- **EMAC arithmetic is approximate:** no MACSR flags, no saturation, 32-bit accumulators
  ([scripts/ghidra_ext/README.md](../scripts/ghidra_ext/README.md)). Pads stepped as plain integer code
  are unaffected.
- **No peripherals and no DMA.** eDMA transfers never happen, so, for example, the sample fetch buffer
  at `0x800013a0` never receives sample data.
- **C++ state built at construction is out of practical reach.** The machine list's cursor-navigation
  clamp is a `std::function` stored in the view (at `+0x1d4`) when the view is constructed. Emulating it
  needs the whole constructor and its object graph, so `EmuMachineList` proves the item count but not
  the navigation.
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
  the pad's own entries, strings or tables is repointed ([landing_pads.md](landing_pads.md)).
  Disassemble it at the new address and check that every branch still lands inside the pad.

### Not available on ColdFire

| Missing | Use instead |
|---|---|
| `movem.l` with `-(An)` or `(An)+` (control modes only) | `lea %sp@(-n),%sp` then `moveml ...,%sp@`, as in MIDI Loopback's code at `0x40014d14`; or single `move.l`s |
| `mulu.l #imm,Dn` (gas: "operands mismatch") | a shift-add chain. `S*0xa2` = `movel d0,d1 ; lsl #2 ; add d0 ; lsl #4 ; add d0 ; lsl #1` (S×4 → ×5 → ×80 → ×81 → ×162), as in the POLY pool's sound-follow code at `0x40037776` |
| the full-format `(d16,An,Xn)` indexed mode | the brief `(d8,An,Xn*scale)` form only; for an array further away, compute one pointer and use `(d16,An)` off it (below) |
| `DBcc` (`dbra`, `dbf`) | explicit `addq` / `cmp` / `bcc`, or a `subql #1,Dn ; bpls` countdown |
| `andi` / `ori` / `eori` in `.b` or `.w` (gas: "invalid instruction for this architecture; needs 68000 or higher") | the `.l` forms, for example `andil #imm,Dn` (`0280 xxxxxxxx`, 6 B) |

**Two parallel per-voice arrays through one computed pointer.** The brief indexed mode's 8-bit
displacement cannot reach a second array a few hundred bytes away, but `(d16,An)` can. The pool's
note-off scan at `0x400b2214` reads `priority[v]` and `held[v]` this way; `held[]` at `0x4395df20` is
`0x12c` above `priority[]` at `0x4395ddf4`:

```
lea    0x4395ddf4,%a1          | priority[], stride 4
lea    %a1@(0,%d1:l:4),%a4     | a4 = &priority[v]
movel  %a4@,%d2                | priority[v]
movel  %a4@(300),%d2           | held[v]
```

Two arrays cost one base register and one `lea` per candidate. This works for any pair within ±32 KB.
The brief form's `*4` scale is supported (`49f1 1c00`).

### Forms used and verified in this build

`mvz.b` / `mvs.b`; `cmpi.b` and `cmpi.l`; `cmp.b (d8,An,Xn),Dn`; `clr.l -(sp)`; `move.l An,(sp)`;
`lea (d8,An,Xn.l),An`; absolute-long `jsr` / `jmp` (`4eb9` / `4ef9`, 6 B);
`tst.l (d16,An)` (`4aaa 0028`); `andi.l #imm,Dn` (`0280 xxxxxxxx`); `move.l (d16,An),Dn`
(`242c 012c`); `cmp.l (d16,An),Dn` (`b4aa 000c`).

A `subql #1,Dn ; bpls` countdown ends a loop in 4 B, against 8 B for `addq` / `cmpi` / `blt`. It scans
in reverse order, which matters when the first match wins.

### Reading objdump output

- In the brief indexed mode the displacement prints with no `0x` and reads like decimal: `49f4 1820`
  prints as `%a4@(20,%d1:l)` and means +0x20 = 32. Check the extension word's low byte.
- A negative 8-bit displacement prints as a 64-bit hex value: `%a1@(ffffffffffffffff,%d0:l)` is −1.
- `remsl Dx,Dx,Dx` with the same register is a signed divide
  ([analysis_method.md](analysis_method.md)).

### Hook shapes that keep the change small

The full list of hooks and pads is in [landing_pads.md](landing_pads.md) and
[docs/patch_listing.md](../docs/patch_listing.md).

- **Insert before the branches.** New code placed ahead of every branch and its target changes no
  displacement, so the rest of the pad stays byte-identical and the equivalence argument stays at the
  byte level.
- **Entry trampoline.** `jmp pad ; nop` over the first 8 B of a stock function (whole instructions);
  the pad's stub re-executes those 8 B verbatim and jumps to +8. The displaced bytes must end on an
  instruction boundary and contain nothing PC-relative, and nothing may enter the function mid-body.
  Used at `0x40076ee8` (POLY voice pool) and `0x400c4bda` (MIDI Loopback's record filter).
- **Tail trampoline.** Where a stock function ends in `jmp target`, replace it with `jmp pad`. The pad
  inherits the identical frame, can rewrite an argument in place, and ends with `jmp target`; nothing
  is pushed or popped. Used at `0x40030d44` → `0x400afe46` → `0x40018cec` (POLY voice pool).
- **Wrapped call with the argument re-pushed**, as above. Used at `0x4002b012` and `0x40039f16`, which
  share the pad `0x400afe80` because the frame shape is the same at both sites (`2f00` immediately
  before the `jsr`).
