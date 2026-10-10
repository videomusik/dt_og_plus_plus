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

The CFO oscillator and portamento ([features/cfo_oscillator.md](features/cfo_oscillator.md),
[features/portamento.md](features/portamento.md)):

- ✅ Three hooks in the audio ISR `FUN_40077420` run on every audio tick (objdump): the synth's render
  hook (`0x40077fc8` → `cfo_pad` in `0x400f77da`, which renders the tracks whose machine is 5 and then
  jumps on to `FUN_40072478`), portamento's glide at the top of the rate loop (`0x40075690` →
  `port_glide`, every track) and the amp envelope's mask store (`0x40078070` → `amp_hook`). What they
  keep in RAM is harmless at power-up: any phase is a valid start, a level above `0x7fff` gives no
  ramp, and portamento takes no note sum as valid until its marker is set
  ([memory_map.md](memory_map.md)). `EmuCfoOscillator` and `EmuPortamento` run them, power-up
  contents included. Portamento's note-on hook (`0x400779f6` → `port_on`) runs only at a note-on.
- ✅ The display-object builder `FUN_40152280`, which runs once at every boot on a zeroed `.bss`, holds
  portamento's edits: ids 4 and 5's text, step profile, picture and flag word, and the TRIG layout's
  knobs G and H (`0x40153410` to `0x40156612`). They store constants and call nothing new;
  `EmuPortamento` runs them.
- ⚠️ **Project-load code**, since the Digitakt loads a project when it starts: the machine bound at
  `0x4007a2d0`, now 7; portamento's reader hook (`0x4007a2aa` → `rd_hook`); and the two stored-index
  lookups rewritten in place (`FUN_40079738`, `FUN_40079772`).
- ⚠️ **Draw-path hooks**, which run at the first paint if the unit restores that page: CFOO's layout
  (`0x400657e6`), its cell picture and text (`0x4000f2bc`, `0x4000f324`), the encoder popup
  (`0x40032d16`), the label pads' fall-through into the rename routines, the machine icon, the SRC
  page's title and the LFO DEST cell; PORT's text object.

✅ These ran on the test unit with OS 1.54: the build that holds them (`.syx` `9df62a0b…`, the
portamento stage S36) started and ran ([README.md](README.md#the-test-unit)).

FILTER page 2 (test images only, not in `patch.json`; [features/filter_page2.md](features/filter_page2.md)):

- ✅ The filter stage's hook (`0x400728a2` → `filt_hook` in `0x400d266e`) runs for every track on every
  audio tick (the filter stage is called per track at `0x400780c4`; objdump). It reads the track's
  smoothed values, its velocity word and portamento's note sums, and keeps no state of its own, so
  nothing it reads at power-up can be stale for longer than the tick's own values. `EmuFilter` runs it.
- ✅ The display-object builder `FUN_40152280`, which runs once at every boot, holds its edits from S39:
  ids 1 and 2's text and picture operands and FILTER page 2's knobs C and G (`0x40153354` to
  `0x401568e4`); from S47 id 1's operands point at Trig Probability's objects, from S49 id 1's flags
  store is `addql #4` (`0x4015334a`), from S51 id 2's text operand points at a constant object in the
  `.rodata` padding. These store constants. From S45 a call at `0x401533c0` (`filt_spc`, in place of a
  `clrl`): S45 only replays the `clrl`; from S46 it calls the build's own callable copier twice, as the
  builder does for every other id. `EmuFilter` runs the whole builder.
- ⚠️ **Project-load code**, since the Digitakt loads a project when it starts: from S42 the sound reader's
  hook (`0x4007a2aa` → `rd_hook2`) and the two stored-index lookups' jumps (`0x4007974c`, `0x40079786`).
- ⚠️ **Draw-path code**, at the first paint if the unit restores FILTER page 2: VED's and KEY's cells,
  through ENV's stock text and picture (VED from S47 through Trig Probability's; KEY's text from S52
  through `key_txt`), and from S40 the page's own draw routine `FUN_40037564` with its five changed
  words.
- ✅ The test images up to S52 started and ran on the test unit with OS 1.54, as reported.
