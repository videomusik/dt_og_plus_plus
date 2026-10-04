# Start-up hooks: OS 1.54

Why start-up code matters, and the recovery ladder: [flash_recovery.md](../../../notes/flash_recovery.md).
The STARTUP menu's own OS upgrade is section 2 code, which a build does not change
([update_moat.md](update_moat.md#the-startup-menus-os-upgrade)).

## This build runs code at startup

⛔ Ruled out: "the patches cannot affect boot". These hooks run on the startup path:

| Hook | Pad | Feature | Why it runs at startup | Checked by |
|---|---|---|---|---|
| `0x4002b68e` | `0x400bed74` | POLY voice pool: a follower reports its Source's machine | in the machine-type query `FUN_4002b5d4`, which runs as the screen first draws | `EmuTrackAlias` (pad B): the pad re-pushes the classifier's argument |
| `0x400308da` | `0x400bed3a` | POLY voice pool: parameter pages read the Source's values | the tail jump of `MachineParameterPageView::vfunc_41`, reached from the value read of every parameter-page draw | `EmuReadAlias` |
| `0x4003ab12` | `0x400bed74` | POLY voice pool: SRC page 1 uses the Source's layout | in `SamplePageView::vfunc_39`, part of the page draw | `EmuTrackAlias` (pad B) |
| `0x400d49e0` | `0x4001567e` | MIDI Loopback: the private MIDI lane's dispatch | in the MIDI input task `FUN_400d486a`, which starts at boot | `EmuMidiLane` |

✅ A patched instruction also runs at every boot (objdump and the table walk): the `lea` at
`0x401524f2` in the parameter-record builder `FUN_40152280`, entry 56 of the 130 C++ static
initialisers. It only stores the address of SLICE round robin's value formatter `0x400c1398`, which runs
when a Slice Select value is drawn.

✅ These ran on the test unit with OS 1.54: the build that holds them (section 3 `5a7eb2a4…`) started
and ran ([README.md](README.md#the-test-unit)). The harnesses are in
[scripts/emu/README.md](../scripts/emu/README.md).

Also possibly at startup:

- ⚠️ **Draw-path hooks**, which run at the first paint if the unit restores that page: MIDI Loopback's
  CHAN/TRK labels (`0x40030daa`, `0x40032d36`, the value formatter at `0x40065896`; `EmuChanLabel`),
  the pool cursors (`0x400bd5b0`, `0x400bd9ee`; `EmuCursorCore`), the TRK number drawer
  (`0x40063222`), and SLICE round robin's Slice Select cell drawer (`0x400654a8` → `0x400bee28`) and
  value formatter (`0x400c1398`).
- ⚠️ **Project-load code**, since the Digitakt loads a project when it starts: the POLY pool-map build
  on kit load (`0x4007735a` → `0x400bf16c`), the pool-map refresh in the audio ISR's set-active-kit
  handler (`0x400776e6` → `0x40037a3c`) and the per-sound deserializer's machine bound at `0x4007a2d0`.

Chain Recording ([features/chain_record.md](features/chain_record.md)):

- ✅ None of its engine hooks runs at startup (objdump). The per-block routine `FUN_40076650` runs
  for every audio block from the start, but the threshold hook (`0x400767fc`) is in its state-1
  branch and the stop hook (`0x4007687a`) in its state-2 branch; start-up clears the state, which is
  in `.bss`, to 0. ARM and REC (`0x400768c2`, `0x400768fa`, `0x40076900`) run only on a key press.
- ⚠️ Its view hooks (`0x400a7f40`, `0x400a8e7c`, `0x400a8f48`, `0x400a9026`) run only while the
  recorder page is shown, and so at the first paint only if the unit restores that page. The NO-key
  hook (`0x400a9878`) runs only on a key press on that page.
