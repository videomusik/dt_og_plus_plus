# Flash recovery

## What this is

What to do when a Digitakt will not take an OS image, will not start, or hangs after an update, in
the order to try things. A faulty build can leave the unit unable to start. ✅ Steps 1–4 below are
confirmed on the test unit, but treat recovery as a real risk, not a formality. Leaving sections 2
and 4 of the image, and the protected ranges of section 3, byte-identical to stock is meant to keep
both recovery routes open ([update_moat.md](update_moat.md)).

## Before you flash

- **Have ready:** your stock `Digitakt_OS1.52A.syx`, the last image that ran on your unit, a DIN MIDI
  interface for the startup-menu route (step 3 below), and time: that route takes 5–10 minutes.
- **Start with an unmodified round trip.** The first image a unit ever receives from this toolchain
  should be your stock file taken apart and put back together (`./scripts/roundtrip.sh`). ✅ The
  Digitakt accepts a tool-rebuilt OS: an unmodified round-tripped image flashed and ran on the test
  unit.
- **The image carries no HMAC trailer.** Integrity is checksums only, and the firmware tool recomputes
  them ([firmware_image.md](firmware_image.md)).
- **`build/build.py` checks the moat for you.** It re-extracts the finished file and refuses to name it
  unless sections 2, 4 and 5 are byte-identical to stock and section 3 matches the published hash.
- **Know which hooks run at startup** (next section). This build has some.

## This build runs code at startup

A fault in code that runs only once the UI is up (parameter edits, menus, MIDI output) leaves a unit
that still starts, and the normal USB upload can replace the image. A fault in code that runs during
startup or at the first screen paint can stop the unit before the normal upload is possible. Then
only the startup-menu route is left.

⛔ **Ruled out: "the patches cannot affect boot".** These hooks in this build run on the startup path:

| Hook | Pad | Feature | Why it runs at startup |
|---|---|---|---|
| `0x4002b012` | `0x400afe80` | POLY voice pool ([ledger](function_ledger.md#src-page-1-layout-and-the-machine-a-page-shows), [patch listing](../docs/patch_listing.md#poly-ui)): a follower reports its Source's machine | in the machine-type query `FUN_4002af58`, which runs as the screen first draws |
| `0x40030d44` | `0x400afe46` | POLY voice pool: parameter pages read the Source's values | the tail jump of the parameter-set lookup at `0x40030d22`, reached from the value read of every parameter-page draw |
| `0x40039f16` | `0x400afe80` | POLY voice pool: SRC page 1 uses the Source's layout | in the SRC page's layout read, part of the page draw |
| `0x400c47ce` | `0x40015186` | [MIDI Loopback](features/midi_loopback.md): the private MIDI lane's dispatch | in the MIDI-input task `FUN_400c465e`, which starts at boot |

✅ The hook sites at `0x40030d44` and `0x4002b012` each ran on the test unit as an inert
pass-through. `0x40039f16` reuses the pad proven at `0x4002b012`, with the same frame shape at both
sites. ✅ Each of the four has started normally on the test unit.

✅ A patched instruction also runs at every boot (read directly in the code, objdump): the `lea` at
`0x4013b914` in the parameter-record builder `FUN_4013b6a2`, a C++ static initialiser that the init
task's `.init_array` walker at `0x40068d76` calls. It only stores the address of SLICE round robin's
value formatter `0x400b2410`, which runs when a Slice Select value is drawn.

Also possibly at startup:

- ⚠️ **Draw-path hooks**, which run at the first paint if the unit restores that page:
  - MIDI Loopback's CHAN/TRK labels: the grid-cell short name at `0x40030466`, in the parameter-grid
    draw, which runs at first paint, and the encoder popup's long name at `0x40032816`; also the value
    formatter at `0x40065652`;
  - the [pool cursors](features/pool_cursors.md) at `0x400ae7f4` and `0x400aeafa`, at the end of the SRC
    page-2 waveform draw;
  - the TRK number drawer at `0x40062fde`, on a MIDI track's SRC page;
  - [SLICE round robin](features/slice_round_robin.md)'s Slice Select cell drawer at `0x40065264` (→
    the robin selector at `0x400aff34`) and its value formatter at `0x400b2410`, on a SLICE track's SRC
    page.
- ⚠️ **Project-load code.** The Digitakt loads a project when it starts, so this runs then too
  (inference from that): the POLY pool-map build on kit load (`0x4007705a` → `0x400b026e`), the POLY
  pool-map refresh in the audio ISR's set-active-kit handler (`0x400773e6` → `0x40037734`) and the kit
  loader's machine-range check at `0x4007a994`.

For anyone extending the build:

- Prefer hook sites reached only by user action.
- When a hook must run at startup, say so before flashing, flash an inert pass-through first, step the
  calling contract in the emulator ([emulator.md](emulator.md)), and have the startup-menu route ready.

## The recovery ladder

### 1. A transfer stalls or will not start

- **A stalled receive is not a brick.** ✅ On the test unit, a transfer that stalled at about 15–20 %
  left the unit intact, and stock then flashed normally after a power-cycle. ⚠️ That a stalled receive
  writes nothing, so the unit keeps its previous OS, is inferred from this; the update code has not
  been traced.
- **Stock firmware wedges too.** ✅ The stock file can also refuse to upload until a restart
  (confirmed on the test unit). A wedged transfer is a problem in the transfer channel (the host,
  USB-MIDI, or the unit's receive state), not evidence that the image is bad.
- **What to do:** restart the host side, the unit, or both, then retry. A wedge can also leave the unit
  refusing to start until power is removed: unplug it for about a minute, power on normally, and upload
  over USB as usual. ✅ confirmed on the test unit; the startup menu is not needed for this case.
- ⚠️ Why transfers wedge is not known ([open_questions.md](open_questions.md)).
- The build's firmware tool caps the compression window at 1 MB, as Elektron's own image does
  ([firmware_image.md](firmware_image.md)). ⚠️ That the cap prevents a stall is not proven.

### 2. The unit will not start

- **Do not read "it will not start" as a brick.** First unplug the power supply, wait about a minute,
  power on normally, and retry the normal upload.
- ✅ **A boot-path fault can need a longer wait** (confirmed on the test unit). A boot-path fault can
  show an exception screen and then leave the unit refusing to turn on. Turning it on twice in a row
  may not be enough; it may have to stay unplugged for a while before the startup menu comes up.
- **Reading the exception screen.** Write all of it down before doing anything else. It begins
  `EXCEPTION`; the codes and addresses that follow point to the exception vector, the fault PC and
  return addresses from the stack, which together name the failing call chain. ⚠️ The screen's full
  field layout is not decoded.

### 3. The startup menu: [FUNC] + power, then [TRIG 4]

- Hold [FUNC] while powering on to reach the STARTUP menu, then press [TRIG 4] for OS UPGRADE mode,
  and send the OS file as Elektron's OS readme describes for this route.
- This route needs the Digitakt's DIN MIDI port; it does not work over USB. Connect the unit through a
  DIN MIDI interface.
- The transfer takes 5–10 minutes and reports its progress in thirds. Do not interrupt it.
- ✅ This route is confirmed on the test unit. After a boot-path fault it can open only once the unit
  has stayed unplugged for a while (step 2).
- This build leaves sections 2 and 4, and the protected ranges of section 3, unchanged to keep this
  route open. ⚠️ Where the startup menu's upgrade code lives has not been traced
  ([update_moat.md](update_moat.md)).

### 4. A hang on "SUCCESS! REBOOTING"

- After a completed install the unit can hang on the reboot screen instead of restarting.
  ✅ On the test unit a power-cycle cleared it, and re-flashing the same file booted cleanly. That
  points away from the image, but one clean re-flash cannot exclude an intermittent fault. The same
  hang has also occasionally been seen with stock firmware.
- **What to do:** power-cycle (unplug, wait, replug), then re-flash the same image or a known-good one.
  Use the startup-menu route only if the hang persists. If the same image hangs on reboot a second
  time, stop and investigate before flashing it again.

### 5. The BDM header

- The CPU board has a populated 26-pin BDM/JTAG header, J1 ([hardware.md](hardware.md)). With a ColdFire
  BDM probe one can halt the CPU, read and write RAM, and reflash the boot flash directly, whether or
  not the bootloader still runs.
- ⚠️ Untested here: no probe has been used on the test unit.
