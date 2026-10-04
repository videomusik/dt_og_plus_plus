# Emulator harnesses

Ghidra scripts that run pieces of this build's pad code in Ghidra's p-code emulator (`EmulatorHelper`)
against the OS 1.54 project, and check what they did. They answer questions like "does this pad keep
the stack balanced?" before anything goes near hardware. Nothing here builds firmware, and nothing
talks to a device. The method is in [notes/emulator.md](../../../../notes/emulator.md).

These harnesses are for OS 1.54 only. Each is the code of a harness written for an earlier OS
version, with every address, hash and size taken from this version's own image and build
([notes/function_ledger.md](../../notes/function_ledger.md)).

## What you need

- Ghidra 12.1.3 and OpenJDK 21 ([docs/toolchain.md](../../../../docs/toolchain.md) section 4), the
  ColdFire EMAC language extension, and the stock MAIN OS imported with it:

      ./scripts/extract.sh 1.54
      ./scripts/ghidra_lang_ext.sh
      GHIDRA_LANG_VARIANT=emac ./scripts/ghidra_analyze.sh 1.54 main     # -> work/ghidra/dt_1.54_emac

- For the harnesses that read their pad from a build: your own DT OG++ build, extracted.
  `./scripts/extract.sh 1.54:out/1.54/<build>.syx` writes `work/dt_1.54-<build>/section_3_MAIN_OS.bin`.

Run one with `./scripts/ghidra_emu.sh 1.54 <Harness> [argument]`.

## The harnesses

| Harness | Feature | Pad | Argument | What it proves |
|---|---|---|---|---|
| `EmuCursorCore` | `pool_cursors` | `0x40015558`, 190 B | build MAIN OS or raw pad | The extra cursors for the other voices of a POLY pool: the right set at the right x, the canvas taken from the register each draw keeps it in (`%d2` for the marker draw, `%d4` for the grid draw), dirty bytes right, SP balanced, `d7`/`a6` untouched, callee argument at `4(sp)`. |
| `EmuMidiLane` | `midi_loopback` (private lane) | `0x4001567e`, 50 B | build MAIN OS or raw pad | The dispatch arm receives only from its own queue `0x421a9d3c`, dispatches by status nibble, tags messages `0x20`, and returns to the input task's loop `0x400d48e6` with its registers intact. |
| `EmuSliceLatch` | `slice_round_robin` | `0x400c1338` | build MAIN OS or raw pad | The latch, one sequence per pool, the mask arithmetic for grid 4 to 64, no stray writes in the state block, registers preserved, exit only via `jmp 0x40074e62`. |
| `EmuChanLabel` | `midi_loopback` (CHAN/TRK display) | `0x40015616`, 104 B | build MAIN OS or raw pad | The CHAN formatter's addend (+1.0 / +9.0), the `TRK` and `Track` labels only below zero, tail-jumps to the stock accessors otherwise. |
| `EmuReadAlias` | `poly_ui` | `0x400bed3a`, 58 B | none (the reference build's bytes are built in) | The in-place track rewrite at the tail of `MachineParameterPageView::vfunc_41`: pool remap for "current track", explicit tracks untouched with no calls, frame and SP intact. |
| `EmuTrackAlias` | `poly_ui` | `0x400bee02` (38 B), `0x400bed74` (34 B) | none (bytes built in) | Both track-remap pads re-push the callee's argument and apply their out-of-range rules (−1, unchanged). |
| `EmuMachineList` | `poly_engine` | edits at `0x40022f7e`, `0x40022fae`, `0x40022fe6` | none | The machine-list constructor adds 4 machines with the stock bytes and 5 with the edit. |

## Results on the reference build

On the reference build (section 3 `5a7eb2a4…`), every harness ends with its pass line
(`=== ALL CASES PASS ... ===`, or for `EmuMachineList` "as-is added 4 machines, edited added 5").
`EmuCursorCore` on an image whose marker stub reads the canvas from `%d3` fails both marker cases that
draw a line, with the canvas `0x11111111`: the harness tells the two apart.
