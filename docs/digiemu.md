# Checking a build in digiemu

[digiemu](https://github.com/irpina/digiemu) is an emulator for the Digitakt (mk1): an emulated ColdFire
runs the firmware's own code, from the bootstrap to the live screen, with the keys, the encoders, the
+Drive and the audio. Its firmware check, `emu.fwcheck`, is written for custom builds: it checks the file
as the unit receives it, runs the build's own bootstrap, boots it under a strict model of the chip, drives
it with a key script and compares every screen and the audio with the stock build. This page says how to
set it up and how to run that check on a DT OG++ build with [`scripts/digiemu_check.py`](../scripts/digiemu_check.py).

A pass is a fact about the file and about what the firmware's code does in the emulator. It says nothing
for certain about a unit: the emulator has no caches, approximates the interrupt controller and some
peripherals, and does not run the panel's processor. What it can and cannot see is in digiemu's own
`docs/FIRMWARE-CHECK.md`. It is a check before a test flash, not instead of one.

## Set up digiemu

digiemu is not part of this repository and nothing here downloads it. It needs Python 3.12 through
[uv](https://docs.astral.sh/uv/), a C toolchain, cmake and pkg-config, to build the patched Unicorn
engine it runs on. On Linux:

1. Install uv, cmake (a 3.x release: the Unicorn version digiemu pins does not configure with cmake 4) and
   pkg-config. Without root, uv can install the last two for your user:
   `uv tool install "cmake<4"` and `uv tool install pkgconf`.
2. Clone it next to this repository, `git clone https://github.com/irpina/digiemu`, and in it run
   `uv sync`, then `tools/install-patched-unicorn.sh`. The script builds Unicorn with digiemu's six patches
   and installs it into digiemu's `.venv`. Without pkg-config the build stops in `osdep.c` with
   `PROT_READ undeclared`.
3. Check it, from the digiemu folder: `.venv/bin/python -m emu.unicorn_compat` prints
   `"compatible": true`.

The OS folder's key scripts say which digiemu version their results came from
(OS 1.54: [os/1.54/scripts/digiemu/README.md](../os/1.54/scripts/digiemu/README.md)).

## Run the check

From the repository root, with `<os>` the build's OS folder:

    python3 scripts/digiemu_check.py <os> out/<os>/<build>.syx --digiemu ../digiemu --script pages
    python3 scripts/digiemu_check.py <os> out/<os>/<build>.syx --digiemu ../digiemu --script <name> --timing

- `--script` names a key script in `os/<os>/scripts/digiemu/` (or is a file). Each OS folder's README there
  says what each script does and what should differ from stock.
- `--timing` adds digiemu's cycle clock: the audio render's worst, 99th-percentile and mean cost against
  its deadline, for the build and for stock. It makes the run several times slower (tens of minutes).
- The stock file is the OS folder's own (`os/<os>/profile.sh`), checked by its hash; `--stock` names
  another copy.
- The report goes to `out/<os>/digiemu/<build>-<script>/`: digiemu's `report.json` and log, the screens
  that differ from stock as PNGs (stock, build, difference), and `verdict.txt`.

The script exits 0 when every stage of the build passes, with two exceptions it reports but does not count:
violations the stock build also makes (digiemu marks them `in_stock`), and the known false positives
below. Screens and audio that differ from stock are listed, not judged: check that they differ only where
the build is meant to.

## Key scripts

One step a line, `#` for comments (digiemu's syntax, from its `emu/fwcompare.py`):

    wait 1000              run 1000 emulated ms
    tap PLAY [HOLD] [AFTER] press and release a key (defaults 100 ms, 300 ms)
    press FUNC [AFTER]     hold a key down
    release FUNC [AFTER]   let it go
    turn A +3 [AFTER]      turn an encoder by detents (default 150 ms after)
    snap playing           name this moment: its screen is compared and saved

Key names are digiemu's (`PLAY`, `STOP`, `TRIG`, `SRC`, `FLTR`, `AMP`, `LFO`, `FUNC`, `TRK`, `YES`, `NO`,
`UP`, `DOWN`, `LEFT`, `RIGHT`, the trig keys `1`..`16`); encoders are `A`..`H`. A key script belongs to
an OS folder: menus and pages differ between OS versions.

## Known false positives

- **EXTB.L.** digiemu's instruction decoder (`emu/cfisa.py`) tests its LEA rule before its EXTB rule, and
  the opcodes of `extb.l %d0`..`%d7` (`0x49c0`..`0x49c7`) share their bit pattern with an LEA whose source
  is a data register, which does not exist. So it decodes every EXTB.L as an illegal instruction, and
  strict mode reports it as "an opcode that is not ColdFire ISA_C". EXTB.L is part of the base ColdFire
  instruction set: GNU as assembles `extb.l %d1` to `0x49c1` for `-mcpu=54455` (the Digitakt's ColdFire)
  and for `-mcpu=5206` (a core with only the base set), and digiemu's own emulated CPU runs it.
  `scripts/digiemu_check.py` reads the build's MAIN OS at each such violation and, when the instruction is
  EXTB.L, reports it as this false positive instead of a failure.
