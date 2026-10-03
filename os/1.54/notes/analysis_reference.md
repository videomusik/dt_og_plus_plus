# Analysis reference

## What this is

The Ghidra project this OS folder's figures come from, how it is made, and its reference numbers, so
that any figure in these notes can be re-checked.

## The Ghidra projects

| Project | Made by |
|---|---|
| `dt_1.54_emac` | `GHIDRA_LANG_VARIANT=emac ./scripts/ghidra_analyze.sh 1.54 main` |

The recipe is that of [docs/toolchain.md](../../../docs/toolchain.md#4d-the-projects-and-how-to-make-them)
(section 4d). The seeded, `_dsp`, `_sram` and `_updater` projects have not been made for this image.

## Reference numbers

`dt_1.54_emac`, as `summary.txt` reports it straight after the import (Ghidra 12.1.3, the EMAC
language extension):

| | |
|---|---|
| memory block | `0x40000400 - 0x4025da3f`, 2,479,680 B |
| instructions | 461,676 |
| defined data items | 39,726 |
| functions | 11,745 |
| bytes in functions | 1,454,392 (58.7 % of the image) |
| defined strings | 11,131 |
| error bookmarks | 1 |

**Code windows**, from the function table (runs of functions separated by more than 64 KB):
`0x400004b2..0x4017cc64` and `0x4024f5be..0x40250666`. They are `CODE_WINDOWS` in
[make_listing.py](../build/make_listing.py).

**The objdump listing** of the whole section (`m68k:cfv4e`, load addresses) has 844,437 lines; the
reference scans in [update_moat.md](update_moat.md) and [landing_pads.md](landing_pads.md) run on it.
