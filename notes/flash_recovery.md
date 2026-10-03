# Flash recovery

## What this is

What to do when a Digitakt will not take an OS image, will not start, or hangs after an update, in
the order to try things. A faulty build can leave the unit unable to start.
✅ Steps 1–4 below are confirmed (test unit, OS 1.52A), but treat recovery as a real risk, not a
formality. Leaving the boot loader and updater sections of the image (OS 1.52A: sections 2 and 4), and
the protected ranges of the MAIN OS section, byte-identical to stock is meant to keep both recovery
routes open ([update_moat_method.md](update_moat_method.md)).
The OS 1.52A ranges: [update_moat.md](../os/1.52A/notes/update_moat.md).

## Before you flash

- **Have ready:** your stock OS file (the one your build was made from), the last image that ran on
  your unit, a DIN MIDI interface for the startup-menu route (step 3 below), and time: that route
  takes 5–10 minutes.
- **Start with an unmodified round trip.** The first image a unit ever receives from this toolchain
  should be your stock file taken apart and put back together (`./scripts/roundtrip.sh <os>`).
  ✅ The Digitakt accepts a tool-rebuilt OS (test unit, OS 1.52A): an unmodified round-tripped image
  flashed and ran on the test unit.
- **The OS 1.52A image carries no HMAC trailer** ([stock_image.md](../os/1.52A/notes/stock_image.md#integrity)).
  Integrity is checksums only, and the firmware tool recomputes them
  ([firmware_image.md](firmware_image.md)). Each OS folder records the trailer status of its own
  image in `os/<os>/profile.sh` (`OS_SIGNATURE_TRAILER`).
- **The OS folder's `build.py` checks the moat for you** (`os/<os>/build/build.py`). It re-extracts
  the finished file and refuses to name it unless every section other than the MAIN OS section
  (OS 1.52A: 2, 4 and 5) equals stock, and the MAIN OS hash matches the published one.
- **Know which hooks run at startup** (next section). The OS 1.52A build has some.

## Code that runs at startup

A fault in code that runs only once the UI is up (parameter edits, menus, MIDI output) leaves a unit
that still starts, and the normal USB upload can replace the image. A fault in code that runs during
startup or at the first screen paint can stop the unit before the normal upload is possible. Then
only the startup-menu route is left.

Each OS folder lists its start-up hooks
(OS 1.52A: [startup_hooks.md](../os/1.52A/notes/startup_hooks.md#this-build-runs-code-at-startup)).

For anyone extending the build:

- Prefer hook sites reached only by user action.
- When a hook must run at startup, say so before flashing, flash an inert pass-through first, step the
  calling contract in the emulator ([emulator.md](emulator.md)), and have the startup-menu route ready.

## The recovery ladder

### 1. A transfer stalls or will not start

- **A stalled receive is not a brick.**
  ✅ A transfer that stalled at about 15–20 % left the unit intact (test unit, OS 1.52A), and stock
  then flashed normally after a power-cycle. ⚠️ That a stalled receive writes nothing, so the unit
  keeps its previous OS, is inferred from this; the update code has not been traced.
- **Stock firmware wedges too.**
  ✅ The stock file can also refuse to upload until a restart (test unit, OS 1.52A). A wedged
  transfer is a problem in the transfer channel (the host, USB-MIDI, or the unit's receive state), not
  evidence that the image is bad.
- **What to do:** restart the host side, the unit, or both, then retry. A wedge can also leave the unit
  refusing to start until power is removed: unplug it for about a minute, power on normally, and upload
  over USB as usual. ✅ confirmed (test unit, OS 1.52A); the startup menu is not needed for this case.
- ⚠️ Why transfers wedge is not known (OS 1.52A: [open_questions.md](../os/1.52A/notes/open_questions.md)).
- The build's firmware tool caps the compression window at 1 MB, as Elektron's own OS 1.52A image
  does ([firmware_image.md](firmware_image.md)). ⚠️ That the cap prevents a stall is not proven.

### 2. The unit will not start

- **Do not read "it will not start" as a brick.** First unplug the power supply, wait about a minute,
  power on normally, and retry the normal upload.
- ✅ **A boot-path fault can need a longer wait** (test unit, OS 1.52A). A boot-path fault can
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
- ✅ This route is confirmed (test unit, OS 1.52A). After a boot-path fault it can open only once the
  unit has stayed unplugged for a while (step 2).
- Every build leaves the boot loader and updater sections (OS 1.52A: sections 2 and 4), and the
  protected ranges of the MAIN OS section, unchanged to keep this route open
  ([update_moat_method.md](update_moat_method.md)). ⚠️ Where the startup menu's upgrade code lives
  has not been traced (OS 1.52A: [update_moat.md](../os/1.52A/notes/update_moat.md)).

### 4. A hang on "SUCCESS! REBOOTING"

- After a completed install the unit can hang on the reboot screen instead of restarting.
  ✅ A power-cycle cleared it, and re-flashing the same file booted cleanly (test unit, OS 1.52A).
  That points away from the image, but one clean re-flash cannot exclude an intermittent fault. The
  same hang has also occasionally been seen with stock firmware.
- **What to do:** power-cycle (unplug, wait, replug), then re-flash the same image or a known-good one.
  Use the startup-menu route only if the hang persists. If the same image hangs on reboot a second
  time, stop and investigate before flashing it again.

### 5. The BDM header

- The CPU board has a populated 26-pin BDM/JTAG header, J1 ([hardware.md](hardware.md)). With a ColdFire
  BDM probe one can halt the CPU, read and write RAM, and reflash the boot flash directly, whether or
  not the bootloader still runs.
- ⚠️ Untested here: no probe has been used on the test unit.
