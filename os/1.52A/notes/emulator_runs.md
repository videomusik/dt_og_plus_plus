# Emulator runs: OS 1.52A

The OS 1.52A runs, examples and recipes for [notes/emulator.md](../../../notes/emulator.md); the harnesses are in [scripts/emu/](../scripts/emu/README.md).

## What has been checked

- ✅ Single-stepping `FUN_40074af2` from its entry to the join at `0x40074b62` executes the prologue
  (`lea`, `moveq`, `movem.l`), the stack-argument reads, the ISA-C byte extends `mvz.b` / `mvs.b`,
  `muls.l`, `lsl.l`, the `not.l` / `subx.l` clamp and the NOTE-mode arithmetic `(note − 12) & mask`,
  each with the expected result.
- ✅ `EmuAf2` runs `FUN_40074af2` to its return. With Select 3, grid 2 (16 slices) and sample slot 5
  it returns start `0x1520` and end `0x1540`, the seeded slice-table entries for slice index 2
  (Select − 1).
- ✅ `EmuBuild` runs the engine-object builder `FUN_4007489e` for 3,712 steps, including its
  MAC-accumulator loop, without a fault. The EMAC arithmetic is approximate (see
  [Limits](../../../notes/emulator.md#what-a-run-can-and-cannot-show)).

## Examples from this build

The examples for the method in [notes/emulator.md](../../../notes/emulator.md), under the headings
they belong to there.

### The pre-flash gate

From [The pre-flash gate](../../../notes/emulator.md#the-pre-flash-gate-step-the-calling-contract):

- **Why the stack matters.** A pad that wraps a call makes the original read the pad's return address
  as its first argument. When that argument is an object pointer the callee calls through
  (`FUN_4001ccc4` does this, and has 83 callers), the result is a jump through garbage and a vector-4
  illegal-instruction exception. At a hook site on the startup draw path, such as `0x4002b012`, that
  fault stops the unit at boot ([startup_hooks.md](startup_hooks.md)).
- **Re-push the argument.** The hook at `0x4002b012` in this build does this (pad `0x400afe80`).
- Example: `EmuTrackAlias` steps the two track-remap pads, `0x400aff0e` and `0x400afe80`, with their
  different out-of-range rules, over tracks 0–7, 8, 9, 15, 16 and −1; 26 cases pass over the two pads.

### Seeding stock code

From [Seeding stock code](../../../notes/emulator.md#seeding-stock-code):

- **Stack arguments and the callee's prologue.** `FUN_40074af2` begins with `lea (-0x18,SP),SP` and
  then reads its arguments at `(0x1c,SP)`, `(0x20,SP)` and `(0x28,SP)`.
- **Stubbing by PC intercept.** `EmuMachineList` counts the real iterations of the machine-list
  constructor's add loop (`0x4002a282..0x4002a368`) this way, without building the C++ object graph:
  4 machines with the stock bytes, 5 with the machine-enum edit in `FUN_4002295c`.

### What a run can and cannot show

From [What a run can and cannot show](../../../notes/emulator.md#what-a-run-can-and-cannot-show):

- **A run that faults stops counting there.** ⚠️ ISR runs made before the EMAC language's mode-5 fix
  faulted at `0x40075cfa` in `FUN_400754fe`, a `mac.l` with `(d16,An)` addressing that the stock
  language decoded 2 bytes short, after two calls of the SLICE window function. The pipeline calls the
  window function once per track per pass ([render_path.md](render_path.md)), so call counts from such
  a run are truncated. This rests on the two facts above; no run with the fixed language that reaches
  all eight calls is recorded.

### Writing ColdFire patch code

From [Not available on ColdFire](../../../notes/emulator.md#not-available-on-coldfire): **two parallel
per-voice arrays through one computed pointer.** The pool's note-off scan at `0x400b2214` reads
`priority[v]` and `held[v]` this way; `held[]` at `0x4395df20` is `0x12c` above `priority[]` at
`0x4395ddf4`:

```
lea    0x4395ddf4,%a1          | priority[], stride 4
lea    %a1@(0,%d1:l:4),%a4     | a4 = &priority[v]
movel  %a4@,%d2                | priority[v]
movel  %a4@(300),%d2           | held[v]
```

## Seeding stock code: the audio ISR

The [seeding techniques](../../../notes/emulator.md#seeding-stock-code) applied to the audio ISR
`FUN_40077120`:

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

## Forms used and verified in this build

`mvz.b` / `mvs.b`; `cmpi.b` and `cmpi.l`; `cmp.b (d8,An,Xn),Dn`; `clr.l -(sp)`; `move.l An,(sp)`;
`lea (d8,An,Xn.l),An`; absolute-long `jsr` / `jmp` (`4eb9` / `4ef9`, 6 B);
`tst.l (d16,An)` (`4aaa 0028`); `andi.l #imm,Dn` (`0280 xxxxxxxx`); `move.l (d16,An),Dn`
(`242c 012c`); `cmp.l (d16,An),Dn` (`b4aa 000c`).

A `subql #1,Dn ; bpls` countdown ends a loop in 4 B, against 8 B for `addq` / `cmpi` / `blt`. It scans
in reverse order, which matters when the first match wins.

## Hook use sites

Where the [hook shapes](../../../notes/emulator.md#hook-shapes-that-keep-the-change-small) are used.
The full list of hooks and pads is in [landing_pads.md](landing_pads.md) and
[docs/patch_listing.md](../docs/patch_listing.md).

- **Entry trampoline.** Used at `0x40076ee8` (POLY voice pool) and `0x400c4bda` (MIDI Loopback's
  record filter).
- **Tail trampoline.** Used at `0x40030d44` → `0x400afe46` → `0x40018cec` (POLY voice pool).
- **Wrapped call with the argument re-pushed.** Used at `0x4002b012` and `0x40039f16`, which
  share the pad `0x400afe80` because the frame shape is the same at both sites (`2f00` immediately
  before the `jsr`).
