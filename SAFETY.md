# Safety

Read this before you flash DT OG++ to a Digitakt. The full recovery notes, with addresses, are in
[notes/flash_recovery.md](notes/flash_recovery.md).

## Who this is for

- DT OG++ is a modified OS 1.52A for the Digitakt (the original model), and for no other device.
  `build/build.py` builds only from the unmodified Elektron OS 1.52A update file (SHA-256
  `01315133041dcdb8b432146190cc74fc8695c47d8466b0f31bd78cef96fa56a4`, 1,162,400 bytes) and refuses
  any other input, including other OS versions. There is no override.
- Building never talks to the Digitakt. Flashing is a separate step, your own decision and your own
  risk.
- Build your own file from your own stock file. The built file contains Elektron's firmware, so do
  not share it, and do not flash a file that you did not build yourself.

## The risk

- DT OG++ changes the code the Digitakt runs. A faulty build, in particular one with a fault on the
  start-up path, can leave the Digitakt unable to start. The way back is the recovery ladder below,
  and in the worst case the STARTUP menu route (step 3).
- The recovery ladder is no guarantee that a unit can be recovered.
- Flashing a modified OS may affect your warranty. This is an inference; Elektron's terms have not
  been checked.

## What to have ready

- **Your stock `Digitakt_OS1.52A.syx`.** It is the way back.
- **The last OS file that ran on your unit**, if it is not the stock one.
- **Elektron Transfer**, to send the file to the Digitakt.
- **A DIN MIDI interface.** The STARTUP menu route (step 3 below) needs the Digitakt's DIN MIDI IN
  port; it does not work through USB.
- **Backups of your projects and samples.** Projects saved with the new features change when they are
  loaded on stock firmware; see "Going back to stock" in [README.md](README.md#going-back-to-stock).
- **Time.** The STARTUP menu route takes 5–10 minutes.

**Tip:** Before your first DT OG++ flash, you can flash your stock file after the same tool has taken
it apart and put it back together unmodified: run `./scripts/extract.sh`, then
`./scripts/roundtrip.sh`, and flash `work/dt_1.52A/roundtrip/rt.syx` once it prints `round-trip OK`
([docs/extract_sections.md](docs/extract_sections.md)). That tests the tool and your transfer set-up
without any modified code.

## Flash only a file that passed every check

`build/build.py` gives the output file its final name only after every check has passed:

1. The input is the unmodified stock file.
2. Its MAIN OS section (section 3) is the stock one.
3. Every changed byte lies inside section 3 and outside the protected ranges (see
   [For contributors](#for-contributors)), and the result matches the published hash.
4. The firmware tool reports `checksums : ok` for the packed file, for a Digitakt, version 1.52A.
5. Extracted again, the packed file gives back exactly the patched section 3, and sections 2, 4 and 5
   byte-identical to stock.

If any check fails, `build.py` prints `build FAILED; no output file was written.` and leaves no file
with the final name behind. So:

- Flash only the file that `build.py` names at `[7/7] wrote`, in `out/`. Apart from your stock file,
  the one other file worth flashing is the unmodified round trip from the Tip above.
- Never flash anything from a build that failed or was interrupted, or a file someone else sent you.
- Run `python3 build/verify.py <file> --tool tool/bin/elektron-firmware-tool-capped` on the file
  before you flash it. For a build it must say `DT OG++ MAIN OS` (and, with the pinned tool,
  `DT OG++ (reference build)`); for the round trip it must say `stock MAIN OS`. Never flash a file
  for which it reports `unknown MAIN OS` or cannot extract section 3.

## Some patched code runs at start-up

A fault in code that runs only once the Digitakt is up leaves a unit that still starts, and the normal
OS update can replace the build. A fault in code that runs while the Digitakt starts, or when it
first draws the screen, can stop it before the normal update is possible. Then only the STARTUP menu
route (step 3) is left.

Which patched code runs at start-up, and which possibly does, is listed in
[notes/flash_recovery.md](notes/flash_recovery.md#this-build-runs-code-at-startup). Every Digitakt
starts with its own last project, so code that runs when a project loads very probably runs at
start-up too.

## The recovery ladder

Try these in order.

### 1. A transfer stalls or will not start

- A stalled transfer is not a dead unit. That a stalled transfer writes nothing, so the Digitakt
  keeps its previous OS, is inferred; the update code has not been traced.
- The stock file, too, can refuse to upload until a restart. A stalled transfer is a problem of the
  transfer (the computer, USB MIDI, or the Digitakt's receive state), not a sign that the file is
  bad. Why transfers stall is not known.
- Restart the computer side, the Digitakt, or both, and try again. If the Digitakt then does not
  start, unplug its power supply for about a minute, turn it on as usual and send the file over USB
  again.

### 2. The Digitakt does not start

- Do not take this as a dead unit. Unplug the power supply, wait about a minute, turn the Digitakt on
  as usual and try the normal update again.
- After a fault on the start-up path, the unit can need a longer wait: turning it on twice in a row
  may not be enough, and it may have to stay unplugged for a while before the STARTUP menu opens.
- If the screen shows an "EXCEPTION" message, write all of it down before you do anything else. Its
  codes and addresses point to the fault and where it happened. The full layout of that screen is not
  decoded.

### 3. The STARTUP menu: [FUNC] + power, then [TRIG 4]

1. Run a DIN MIDI cable from your MIDI interface's MIDI OUT to the Digitakt's MIDI IN.
2. Hold **[FUNC]** while you turn the Digitakt on. The STARTUP menu opens.
3. Press **[TRIG 4]** to enter OS UPGRADE mode.
4. Send your stock `Digitakt_OS1.52A.syx` with Elektron Transfer, as Elektron's OS readme describes
   for this route.

- The transfer takes 5–10 minutes and shows its progress in thirds. Do not interrupt it.
- After a fault on the start-up path this route can open only once the unit has stayed unplugged for
  a while (step 2).
- `build.py` keeps sections 2 and 4 of the OS file byte-identical to stock on every build, to keep
  this route open ([notes/update_moat.md](notes/update_moat.md)). Where the STARTUP menu's upgrade code
  lives has not been traced.

### 4. A hang on "SUCCESS! REBOOTING"

- After a completed update the Digitakt can stay on the reboot screen instead of restarting. Unplug
  it, wait, plug it in again, then flash the same file or a known-good one. The same hang can happen
  with stock firmware, so it is not by itself a sign of a bad file.
- Use the STARTUP menu only if the hang persists. If the same file hangs on reboot a second time,
  stop and find out why before you flash it again.

### 5. Last resort: the BDM header

- The CPU board has a 26-pin BDM/JTAG header, J1. With a ColdFire BDM probe you can halt the CPU and
  write the boot flash directly, whether or not the boot loader still runs
  ([notes/hardware.md](notes/hardware.md)).
- This route is described from the hardware; it has not been tried.

## Before you contact Elektron support

Flash your stock file first, and check that the problem is still there. A Digitakt running DT OG++
still reports OS 1.52A, so report to Elektron only a problem that stock firmware shows too. DT OG++
is not an Elektron product; do not ask Elektron to support it.

## For contributors

The way back to stock depends on the code that receives an OS update and writes it to flash. Every
change follows two rules. The reasons, the update flow and the function lists are in
[notes/update_moat.md](notes/update_moat.md).

1. **Address rule.** Change only section 3 (MAIN OS), and never a byte in these load-address ranges:

   | range | what it is |
   |---|---|
   | `0x400d7fc8`–`0x400d98f6` | NOR flash driver (DSPI), with its timer wait/ISR and driver getters |
   | `0x40068be0`–`0x40068c12` | DSPI initialization |
   | `0x400668e2`–`0x4006711a` | mid-level flash operations |
   | `0x4006753e`–`0x40068b16` | OS-update transfer task |
   | `0x40080868`–`0x4008088c` | `DigitaktSysex` destructor |
   | `0x40058b48`–`0x40058b68`, `0x40059f6a`–`0x4005a4d6`, `0x4005a4d6`–`0x4005a5f6`, `0x4013a77a`–`0x4013a82a`, `0x4013a82a`–`0x4013a86a` | SysEx receive menu, with its entry thunks |
   | `0x4008b722`–`0x4008bc2e` | storage bulk transfer |
   | `0x40066618`–`0x400668e2`, `0x4006717a`–`0x4006753e`, `0x40068b16`–`0x40068b60`, `0x40068b92`–`0x40068bc2`, `0x40068c12`–`0x40068c34`, `0x40069040`–`0x4006906c`, `0x400693a0`–`0x4006981c`, `0x40069aa0`–`0x4006a7f2`, `0x4008088c`–`0x400808a6`, `0x4008b524`–`0x4008b722` | code the update path also uses: the transfer task's launcher and helpers, the update-mode branch of the init task, the result views and erase job, storage-transfer helpers |

   Sections 2, 4 and 5 stay byte-identical to stock. `build.py` refuses a change that touches a
   protected range, and checks the other sections after packing.
2. **Call rule.** New code never calls a flash erase or write: nothing in the NOR flash driver or the
   mid-level flash operations. `build.py` does not check this rule, so check the disassembly of your
   change ([docs/patch_listing.md](docs/patch_listing.md), `build/make_listing.py`).

Also:

- Prefer hook sites that run only on a user action. If a hook must run at start-up, say so before
  anyone flashes it, flash a version of the hook that does nothing first, step it in the emulator
  ([notes/emulator.md](notes/emulator.md)), and have the STARTUP menu route ready.
- Never weaken, skip or work around a check in `build.py`.
- Never commit Elektron firmware, extracted sections, built files or manual text. The
  [.gitignore](.gitignore) keeps the usual file kinds out.
