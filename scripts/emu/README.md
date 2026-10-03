# Emulator harnesses

These are Ghidra scripts that run pieces of the Digitakt MAIN OS, and pieces of the DT OG++ patch
code, in Ghidra's p-code emulator (`EmulatorHelper`). Each one sets up just enough memory and
registers, runs the code, and checks what it did. They answer questions like "does this pad keep the
stack balanced?" before anything goes near hardware. Nothing here builds firmware, and nothing talks
to a device.

They are analysis tooling only: the build does not need them, and neither QEMU nor Unicorn is
involved.

## What you need

- Ghidra 12.1.3 and OpenJDK 21 ([`docs/toolchain.md`](../../docs/toolchain.md) section 4).
- The ColdFire EMAC language extension (`./scripts/ghidra_lang_ext.sh`) and a MAIN OS project
  imported with it:

      ./scripts/extract.sh
      ./scripts/ghidra_lang_ext.sh
      GHIDRA_LANG_VARIANT=emac ./scripts/ghidra_analyze.sh dt main     # -> work/ghidra/dt_1.52A_emac

  Import the **stock** MAIN OS. The pad harnesses write the pad bytes into the emulator's memory
  themselves, and `EmuMachineList` compares the project's own bytes with the edited ones.
- For the pad harnesses that read their bytes from a build: your own DT OG++ build, extracted.
  `./scripts/extract.sh out/<build>.syx` writes `work/<build>/section_3_MAIN_OS.bin`.

## Running one

    ./scripts/ghidra_emu.sh <Harness> [arguments]

`ghidra_emu.sh` opens the EMAC project read-only, runs the harness as a post-script, keeps the full
log in `work/ghidra/out/dt_1.52A_emac/emu/<Harness>.log`, and prints the verdict lines. Set
`GHIDRA_PROJECT` to run against another project (for example `GHIDRA_PROJECT=dt_1.52A_seed`).
Close the Ghidra GUI on the project first: a project is locked while it is open.

A pad harness prints one line per case (`OK` or `**FAIL**` with the reason; `EmuReadAlias` and
`EmuTrackAlias` instead mark the failing field, such as `**WRONG**`, and end the line with
`<== FAIL`) and ends with `=== ALL CASES PASS ... ===` or `=== ... ABOVE: DO NOT FLASH ===`.
`EmuMachineList` compares two runs and ends with a `=== RESULT: ... ===` line. The verdict lines
`ghidra_emu.sh` prints leave out the `<== FAIL` case lines; the full log has them.

## The pad harnesses (DT OG++ code)

The ones that take an argument read their pad from the build's decompressed MAIN OS at the pad's
load address (file offset = address - `0x40000400`). They refuse the stock MAIN OS, and print a
note if the image is not the reference build (MAIN OS SHA-256 `34765cdf…e864`). You can also pass a
raw file holding only the pad, assembled for that address. In the reference build, every pad these
harnesses step is byte-identical to the bytes they were written against.

| Harness | Feature | Pad | Argument | What it proves |
|---|---|---|---|---|
| `EmuSliceLatch` | `slice_round_robin` | `0x400b23b0`, 90 B | build MAIN OS or raw pad | A playing voice's slice window is latched at its own trig and does not move when a later trig bumps the pool's shared counter; the mask arithmetic for grid 4 to 64; no stray writes in the state block; `d0`/`d5`/`a0`/`a1` preserved; exit only via `jmp 0x40074b62`. |
| `EmuChanLabel` | `midi_loopback` (CHAN/TRK display) | `0x4001511e`, 104 B | build MAIN OS or raw pad, then optionally the three entry points | The CHAN formatter's addend (+1.0 for stock values, +9.0 for the new TRK values), "TRK" and "Track" labels only for CHAN below zero, tail-jumps to the stock accessors otherwise, frames and callee-saved registers intact. |
| `EmuCursorCore` | `pool_cursors` | `0x40015060`, 190 B | build MAIN OS or raw pad | The extra cursors drawn for the other voices of a POLY pool: exactly the right set at the right x, dirty bytes right, SP balanced, `d7`/`a6` untouched, callee argument at `4(sp)`. |
| `EmuMidiLane` | `midi_loopback` (private lane) | `0x40015186`, 50 B | build MAIN OS or raw pad | The USB-MIDI cable-1 dispatch arm receives only from its own queue `0x4216a074`, dispatches by status nibble, tags messages `0x20`, and returns to the input task's loop with its registers intact. |
| `EmuReadAlias` | `poly_ui` | `0x400afe46`, 58 B | none (bytes built in) | The in-place TRACK-argument rewrite at the tail of the parameter page's read/write path: pool remap for "current track", explicit tracks untouched with no calls, frame and SP intact. |
| `EmuTrackAlias` | `poly_ui` | `0x400aff0e` (38 B), `0x400afe80` (34 B) | none (bytes built in) | Both track-remap pads re-push the callee's argument (stack discipline), apply their different out-of-range rules (-1 vs unchanged), and preserve SP and registers. |
| `EmuMachineList` | `poly_engine` | edits at `0x40022970`, `0x400229a0`, `0x400229d8` | none | Counts the machines the machine-list constructor adds, with the project's own bytes and with the machine-enum edit. |

## The audio-ISR probes (stock code)

These explore the stock audio path; they check no DT OG++ code. None takes an argument.

| Harness | What it does |
|---|---|
| `EmuTest` | Smoke test: single-steps the slice-window function `FUN_40074af2` for 40 steps. |
| `TestMac5` | Decodes four `mac.l` sites and prints their lengths (6 bytes with the EMAC extension). |
| `EmuAf2` | Runs `FUN_40074af2` to its return with seeded memory and computes one slice. |
| `EmuBuild` | Runs the engine-object builder `FUN_4007489e` with a tagged voice mirror. |
| `EmuISR` | Runs the audio ISR `FUN_40077120` on zeroed state and logs each slice-window call. |
| `EmuISR2` | Histograms the ISR's PCs to find where it spins. |
| `EmuISR3`, `EmuISR4` | SLICE machine on every track, hardware-ready waits defeated. |
| `EmuISR5` | Defeats the codec-ready waits dynamically. |
| `EmuISR6`, `EmuISR7` | Inject a track-7 note-on into the ISR's event queue; probe 7 also marks every voice struct. |
| `EmuISR8` | Voice-allocator capture over 14 ticks. |
| `EmuISR9` | Logs which PCs write the per-track audio buffers at `0x80001a18`. |
| `EmuISR10`, `EmuISR11` | Synthetic active voices: is the buffer fill CPU code or DMA? |

## Limits

- The p-code model of the EMAC unit has no MACSR flags, no saturation and a 32-bit accumulator (see
  [`../ghidra_ext/README.md`](../ghidra_ext/README.md)). The pad harnesses step plain integer code,
  so this does not affect them. The ISR probes stub or skip the MAC-heavy render functions and watch
  only memory writes and control flow.
- The emulator has no peripherals. Code that waits on hardware spins forever unless the harness sets
  the ready bit, and code that reads peripheral state reads zeros.
- A passing harness shows that the code does what the harness asserts, in the state the harness sets
  up. It is not a device test.

## Python helpers

| Script | Reads | Prints |
|---|---|---|
| `mainos_classes.py [functions.tsv]` | the EMAC project's `functions.tsv` | functions and bytes per class name |
| `dspmap.py [decomp folder]` | the DSP section's decompiled functions | a per-function table of peripheral modules touched |
| `f2_heap.py [section_3 file]` | the stock MAIN OS | the allocator's pool-control words, and the room below the patches' state block just above the end of `.bss` (from a built-in highest `.bss` reference, not read from the file) |
| `audit.py [notes folder] [address ...]` | markdown notes | every mention of a set of key addresses, to spot contradictions |

Their outputs are tables of addresses, sizes and names; the decompiled C they read stays in `work/`.
