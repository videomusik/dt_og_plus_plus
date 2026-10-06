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
| `EmuChanLabel` | `midi_loopback` (CHAN/TRK display) | `0x40015616`, 104 B | build MAIN OS or raw pad | The CHAN formatter's addend (+1.0 / +9.0), the `TRK` and `Track` labels only below zero, tail-jumps to the stock accessors otherwise. Given a whole MAIN OS, it also loads the CFO oscillator's pad and follows the fall-through through it, which must reach the stock accessor without a machine query. |
| `EmuReadAlias` | `poly_ui` | `0x400bed3a`, 58 B | none (the reference build's bytes are built in) | The in-place track rewrite at the tail of `MachineParameterPageView::vfunc_41`: pool remap for "current track", explicit tracks untouched with no calls, frame and SP intact. |
| `EmuTrackAlias` | `poly_ui` | `0x400bee02` (38 B), `0x400bed74` (34 B) | none (bytes built in) | Both track-remap pads re-push the callee's argument and apply their out-of-range rules (−1, unchanged). |
| `EmuMachineList` | `poly_engine` | edits at `0x40022f7e`, `0x40022fae`, `0x40022fe6` | none, or a build's MAIN OS for a third pass with its own bytes | The machine-list constructor adds 4 machines with the stock bytes and 5 with the edit. |
| `EmuCfoOscillator` | CFO oscillator (not in the build) | `cfo_pad` and its routines, at any address | an emulator load file from `src/cfo_oscillator/make_cfo.py` (`work/dt_1.54-cfo/<placement>.load`, or `<placement>_m5.load` with `machine5`) | Runs the oscillator code over 3–200 ticks against a model of the same integer arithmetic. The model reads the stock pitch table and the wavetables from memory. Every output sample equals the model's; tracks that are not synth tracks keep their buffers; the phases carry over; `FUN_40072478` is reached with the stack and `%d2-%d7/%a2-%a6` intact. The level: `FUN_40074c60` is stubbed (the emulator's EMAC has no fractional mode); each synth track must call it once with its own `x` (`0x80001f18 + 2 × track`) and its LEV and use what it returns, never the voice's own level (`+0x10`, wrong throughout); a voice that is on (`+0x28`) with a trig next tick (`0x8000122c`) must get level 0; the level must ramp across each tick from the one the track ended its last tick on (`0x439d1160 + 4 × track`), and a start-up value there above `0x7fff` must give no ramp. A pure SIN at note 60 measures 261.47 Hz. Controls: a variant with the pitch table one entry off and OSC3's mix gain swapped fails every case except the two with no synth track and level 0; code that reads the voice's level fails every synth case; code without the ramp fails every synth case except level 0 and the start-up case. With `machine5` the synth tracks are machine 5, a ONESHOT track with SAMP OFF must stay untouched, and the layout lookup must give machine 5 ONESHOT's record; the ONESHOT build fails those in that mode. In that mode it also runs the stock `FUN_40077282` with a machine-5 sound on a track whose machine is 4 (POLY): the track must get machine 5, the sound's 106 B of values and the sound as its current one. With `machine5 names` (a `<placement>_m5n.load`, which carries the build's two label pads) it calls both label pads for ids 100–120 on pages showing machines 0, 3, 4 and 5 with the page's machine query stubbed: CFOO's names only for ids 108–115 on a machine-5 page, the stock names otherwise, MIDI Loopback's TRK label intact. Control: with the pads' jumps as the build has them, only the machine-5 page fails. With `machine5 names icon` (a `<placement>_m5i.load`, which also carries the group mapper and the icon pad) it runs the machine-picker icon lookup from `0x40029e9c` for machines 0–6, with the item's machine query stubbed, and reads the `Bitmap` the draw call receives: CFOO's for machine 5, POLY's for 4, SLICE's for 3, a stock icon for 0–2, none for 6. Control: S13's bytes give machine 5 no icon and fail only there. With `machine5 names icon slots` (or a `<placement>_m5s.load`, which carries the S15 hook at `0x40078f72`) it runs `FUN_40078f44` for the SRC slots on machines 0–7 with its run-time table seeded: machine 5 must get machine 0's ids, 4, 6 and 7 none. Control: S14's code fails only for machine 5. A `<placement>_m5k.load` (S16, sym `cfo_range`) switches to knobs mode: 21 synth cases against a model of CFOO's own knob table (waves on A, C, D; FM source on B; mix on E; FM on F; the two detune tables on G and H; level from velocity), the new names, and `cfo_range` against a fake parameter set with machine 5, 0 and 4: CFOO's record only for ids 108–115 on machine 5. Control: OSC2's detune through OSC3's table fails exactly the cases in which OSC2 is heard |
| `EmuChainRecord` | `chain_record` | the recorder engine `0x40076540..0x40076b22` with its hooks, the encoder, prompt, ARMED, MEM and NO-key hooks, and the pads at `0x40177104`, `0x40124a6c`, `0x40128244`, `0x400c1062`, `0x400bf1cc` | build MAIN OS | Runs the engine as built (ARM, REC, the STOP key, ABORT and the per-block routine with its threshold, write and stop), stubbing only the slot length, the end of recording, `memcpy` and the threshold level. With no chain, at RLEN MAX and with the chain off the recorder behaves as stock. In a chain, with manual arming and with auto re-arm, each slot starts at k × slot and ends at exactly (k + 1) × slot, the buffer holds each slot from its own start, the recorder goes idle (manual) or re-arms (auto) between slots, and slot N goes to the stock end of recording at N × slot. REC, the STOP key, ABORT, the 33 s cap, a moved write position and a restart each end the chain. Encoder D, only while idle, calls the accumulator as encoder G does (stubbed) and steps the setting once per whole step through AUTO 64..4, off, 4..64; the prompt, ARMED and MEM lines show `YES: ARM k/N`, `YES: AUTO k/N`, `ARMED k/N` and N × slot, with SP where stock leaves it. A fresh FUNC+NO press while idle with a chain in progress drops it and leaves through the code after ARM; every other NO event takes the stock path. |

## Results on the reference build

On the reference build (section 3 `efc90606…`), every harness ends with its pass line
(`=== ALL CASES PASS ... ===`, or for `EmuMachineList` "as-is added 4 machines, edited added 5").
`EmuCursorCore` on an image whose marker stub reads the canvas from `%d3` fails both marker cases that
draw a line, with the canvas `0x11111111`: the harness tells the two apart.

`EmuChainRecord`'s controls:

- On the S7 stage image (section 3 `aaedd690…`), where every Chain Recording hook leads to a pad that
  only replays the stock code it displaced, every stock case passes and every chain, encoder D,
  prompt, ARMED, MEM and FUNC+NO drop case fails (49).
- On a build whose `chain_any` branched on flags from `mvs.b`, the 15 cases that need auto re-arm
  failed and the others passed (in the emulator a branch after `mvs.b` sees the flags of the
  instruction before it).
- On an earlier build whose in-progress test did not require LEN > 0, the harness as it was before
  auto re-arm and the FUNC+NO cases were added failed only its two restart cases.

Stage images: [features/chain_record.md](../../notes/features/chain_record.md#testing-on-the-unit).
