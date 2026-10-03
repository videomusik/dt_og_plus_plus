# Analysis reference: OS 1.52A

The OS 1.52A hashes, Ghidra projects, reference numbers and worked examples for the method in
[notes/analysis_method.md](../../../notes/analysis_method.md).

## The section-3 hashes

Patching is a separate, deliberate step on a copy: `os/1.52A/build/build.py` applies
[patch.json](../build/patch.json) to your stock section 3 and checks the SHA-256 of the input and of the
result: section 3 stock `59278368…d8e864ee`, patched `34765cdf…4888e864`. The byte runs are listed in
[patch_listing.md](../docs/patch_listing.md). The full hashes and the expected build result are in
[reference.md](../docs/reference.md#expected-result).

## Determinism

- ✅ **The base analysis is deterministic.** A fresh import reproduced the identical function address
  set (11,053 functions), the same 1,786 typeinfos, the same 3,469 renamed functions and the same 47
  error sites (stock project, `dt_1.52A`).
- Functions created by `mkfunc:` or found by hand are not part of the base analysis
  ([analysis_method.md](../../../notes/analysis_method.md#reproducing-the-analysis)).

## The Ghidra projects

Counts differ between projects by design. A count without its project and its measure cannot be
compared with another one ([analysis_method.md](../../../notes/analysis_method.md#say-which-project-a-number-came-from)).
All projects live in `work/ghidra/<project>/` and their text output in `work/ghidra/out/<project>/`;
the naming scheme and what `ghidra_analyze.sh` does are in
[docs/toolchain.md](../../../docs/toolchain.md#4d-the-projects-and-how-to-make-them). The OS 1.52A
projects:

| Project (under `work/ghidra/`) | What it is | Language | Made by | Used for |
|---|---|---|---|---|
| `dt_1.52A` | stock MAIN OS, stock ColdFire language | `68000:BE:32:Coldfire` (stock) | `./scripts/ghidra_analyze.sh 1.52A main` | the control |
| `dt_1.52A_emac` | stock MAIN OS, ColdFire+EMAC language: **use it for anything touching audio code** | `68000:BE:32:ColdfireEMAC` | `GHIDRA_LANG_VARIANT=emac ./scripts/ghidra_analyze.sh 1.52A main` | audio code, the emulator |
| `dt_1.52A_seed` | a copy of `_emac` with the code gaps seeded: the most complete MAIN OS map, and the landing-pad candidate list | `68000:BE:32:ColdfireEMAC` | a fresh EMAC import plus `SeedCodeGaps.java` (recipe below) | map completeness, dead-function scans (`GHIDRA_PROJECT=dt_1.52A_seed`) |
| `dt_1.52A_dsp` (`_dsp_emac`) | section 2 alone, header stripped, at its run base `0x80000ec0` | stock; `68000:BE:32:ColdfireEMAC` for `_dsp_emac` | `./scripts/ghidra_analyze.sh 1.52A dsp` (prefix `GHIDRA_LANG_VARIANT=emac` for `_dsp_emac`) | |
| `dt_1.52A_sram` | the 64 KB on-chip SRAM as the section-2 code sees it (both crt0 init images, the updater, the DSP code) | `68000:BE:32:ColdfireEMAC` | `os/1.52A/scripts/build_sram_image.py`, imported at `0x80000000` (recipe below) | section 2 and the updater in their shared SRAM address space ([section2_map.md](section2_map.md)) |
| `dt_1.52A_updater` | section 4 at `0x80000400` | stock | `./scripts/ghidra_analyze.sh 1.52A updater` | |

`GHIDRA_PROJECT=<folder>` makes any wrapper use `work/ghidra/<folder>/` directly; that is how the two
projects below are addressed. The wrappers accept `dt_1.52A_seed` and `dt_1.52A_sram` for the image
key `1.52A`, because each name starts with that image's work-folder name `dt_1.52A` followed by `_`. A
copied project keeps its original project file name inside the folder (`dt_1.52A_seed` keeps
`dt_1_52A_emac.gpr`), and the wrappers find it.

**`dt_1.52A_seed`**: seed disassembly at the start of every undefined range inside the two code
windows, validate each new function and undo it if it contains a bad instruction, repeat to a fixed
point. [SeedCodeGaps.java](../scripts/ghidra/SeedCodeGaps.java) changes the project, so it runs on a
copy:

```sh
GHIDRA_LANG_VARIANT=emac ./scripts/ghidra_analyze.sh 1.52A main          # the _emac project, if not made yet
cp -R work/ghidra/dt_1.52A_emac work/ghidra/dt_1.52A_seed
GHIDRA_PROJECT=dt_1.52A_seed ./scripts/ghidra_query.sh 1.52A main SeedCodeGaps
GHIDRA_PROJECT=dt_1.52A_seed ./scripts/ghidra_query.sh 1.52A main FindDeadFunctions
GHIDRA_PROJECT=dt_1.52A_seed ./scripts/ghidra_analyze.sh 1.52A main      # optional: re-dump functions.tsv / summary.txt
```

**`dt_1.52A_sram`**: assemble the SRAM image, import it on the EMAC language at `0x80000000` with
entry `0x80000ec0`, then curate it (both curation scripts change the project):

```sh
./scripts/extract.sh 1.52A
python3 os/1.52A/scripts/build_sram_image.py                           # -> work/dt_1.52A/sram_unified.bin
GHIDRA_LANG_VARIANT=emac GHIDRA_PROJECT=dt_1.52A_sram ./scripts/ghidra_analyze.sh 1.52A sram
GHIDRA_PROJECT=dt_1.52A_sram ./scripts/ghidra_query.sh 1.52A sram CurateDsp
GHIDRA_PROJECT=dt_1.52A_sram ./scripts/ghidra_query.sh 1.52A sram FixDspResidual
GHIDRA_PROJECT=dt_1.52A_sram ./scripts/ghidra_analyze.sh 1.52A sram      # re-dump: error bookmarks should now be 0
```

[CurateDsp.java](../scripts/ghidra/CurateDsp.java) clears every instruction outside its code windows
and removes the phantom mid-instruction references that jump-table recovery creates. The windows are
the updater stub that section 2 shares, section 2's code, and section 2's last two routines; section
2's own data lies between the last two ([section2_map.md](section2_map.md#the-unified-sram-view)).
[FixDspResidual.java](../../../scripts/ghidra/FixDspResidual.java) re-forms the last few conflicting
instructions one at a time. `SeedCodeGaps.java`, `CurateDsp.java` and `FindDeadFunctions.java` live in
this OS folder's [scripts/ghidra/](../scripts/ghidra/) because their tables belong to OS 1.52A; the
shared scripts are in [scripts/ghidra/](../../../scripts/ghidra/).

## Reference numbers

Compare your own run against these (from `summary.txt` and the query outputs). They count the saved
project, as a later `ghidra_analyze.sh` run on it re-dumps them. For the two MAIN OS imports the
summary of the first run counts a little less: `dt_1.52A` 11,054 functions and 426,631 instructions,
`dt_1.52A_emac` 11,063 and 431,212.

| Project | Functions | Instructions | In functions | Error bookmarks |
|---|---:|---:|---:|---:|
| `dt_1.52A` | 11,064 | 426,654 | 60.6 % | 47 |
| `dt_1.52A_emac` | 11,073 | 431,235 | 61.3 % | 1 |
| `dt_1.52A_seed` | 11,905 | | code windows 99.9 % disassembled | 1 |
| `dt_1.52A_dsp` | 132 | 5,557 | 61.7 % | 27 |
| `dt_1.52A_sram` | 150 | 6,486 | 29.2 % | 26 → 0 after curation |

- `dt_1.52A`: the RTTI walk finds 1,786 classes and 1,591 vtables; 46 of the 47 errors come from the
  EMAC gaps. The 47th, an unrelated `jsr`/`0x0000` data-in-code boundary at `0x40115fe6`, is the one
  error left on `_emac`.
- `dt_1.52A_seed`: `SeedCodeGaps` adds 832 functions and cuts the undefined bytes in the code windows
  (`0x400004b2`–`0x40162748` and `0x40210e4a`–`0x40211ef2`) from 47,238 to 1,234, rejecting one seed
  as data and adding no error bookmarks. `FindDeadFunctions` then lists 980 unreferenced functions
  (81,858 bytes), of which 204 are leaf functions of 16 bytes or more (10,186 bytes), and its check
  that ten known-live functions are classified live passes. That list is a set of candidates to read,
  never free space to use: code reached through a computed address is invisible to all three of its
  evidence sources.
- `dt_1.52A_dsp`: the 27 errors are not decoding gaps. They are calls into SRAM routines that the
  section alone does not contain (some live in the updater's low-SRAM stub, which the `_sram` image
  includes), and phantom mid-instruction references from jump-table recovery, which the curation
  scripts remove. That is why the `_sram` project exists.
- `dt_1.52A_sram`: straight after import it has 149 functions, 6,533 instructions and 26 error
  bookmarks, and the import's log says it could not create the entry function at `0x80000ec0`; the
  re-dump after curation creates it. Five of the 150 functions lie outside the code windows and have
  no code: the updater routine at `0x80000eaa`, three that analysis made in section 2's data, and
  `0x8000f010`, which is loaded from flash at run time.

Examples of numbers that differ between projects or measures:

- **Error bookmarks:** 47 with the stock language, 1 with the EMAC language. The one left is a
  `jsr %pc@(…)` / `0x0000` data-in-code boundary at `0x40115fe6`.
- **Functions:** 8,257 after auto-analysis and 11,053 after the RTTI pass (stock); 11,063 in the EMAC
  project with the first two fixes; 11,905 in the seeded project, whose fresh EMAC import had 11,073
  before `SeedCodeGaps` added 832 and cut the undefined bytes inside the code windows from 47,238 to
  1,234 (99.91 % of the code region disassembled).
- **Named functions:** 3,469 functions renamed `Class::vfunc_N` by the RTTI pass (stock project);
  3,495 functions with any namespaced name in both the stock and EMAC projects, and 3,474 named
  `vfunc`, in a later count; 1,786 or 1,787 typeinfos depending on the project. These differ by measure
  and project state, not by error.

## The EMAC language extension on this image

What the four gaps of [the EMAC language extension](../../../notes/analysis_method.md#the-emac-language-extension)
cost on this image, and what the fixes recover:

| Gap | Sites | Effect |
|---|---|---|
| `movclr.l ACCx,Rx` missing (`op47=0b1100`, one bit from the defined plain read) | 39 | `a1c0 a3c1 a5c2 a7c3` drains all four accumulators at the end of many audio loops, so every function containing one was cut short |
| `mac.l` with a load into an address register: the `.l` constructor pinned `op6=0`, contradicting its own `macrw` operand (the `.w` constructor has no such pin) | 1 | the form could never match |
| `mac.l Ry,Rx,(d16,An),Rw` (mode 5): the shared EA table read `d16` from the MAC extension word | 4 | a 6-byte instruction decoded as 4, and everything after it was junk (for example `0x40075cfa` in `FUN_400754fe`) |
| `msac.l Ry,Rx,(d16,An),Rw` (mode 5), the same fault in the multiply-subtract sibling | 7 | the same; visible only once the `mac.l` fix kept the stream aligned (for example `0x40073b6e` in `FUN_40073900`) |

Error bookmarks fall 47 → 12 (the first two fixes) → 8 (`mac.l` mode 5) → 1 (`msac.l` mode 5). After the
first two fixes, Ghidra decoded 404 MAC-family instructions (80 before), all matching objdump's lengths.
After all four, the render functions `FUN_40071830`, `FUN_40071886`, `FUN_40073900` and `FUN_400754fe`
decompile with no `halt_baddata`.

Result on the MAIN OS: the error bookmarks fall from 47 (stock language) to 1, and the one left is an
unrelated `jsr`/`0x0000` data-in-code boundary at `0x40115fe6`. After the first two fixes, all 404
MAC-family instructions matched objdump's instruction lengths; the mode-5 sites fixed later were each
checked against objdump, but the whole-image comparison was not re-run with all four fixes.

⛔ Simply deleting the `op6=0` pin is ruled out
([analysis_method.md](../../../notes/analysis_method.md#the-emac-language-extension)): it makes the
stock constructor match `(d16,An)` forms it cannot size. All 80 MAC instructions in the stock project
already matched objdump's lengths, so that latent problem never fired on this binary; the deletion
would have been its first instance.

## Worked examples

### Decoding twice

The rules are in [Decode twice](../../../notes/analysis_method.md#decode-twice).

- ✅ objdump confirms the `movclr.l` decoding, and it catches a constructor with the wrong length:
  `0x40072f68` is a 6-byte instruction, and a constructor with the wrong length can decode it as 4
  bytes.
- objdump prints `remsl Dx,Dx,Dx` (the same register twice) for what is really the signed 32-bit
  divide `divs.l`: ColdFire's divide and remainder share an encoding, and the remainder form requires
  two different registers. The dump of the whole MAIN OS contains no `divsl` or `divul` mnemonic at
  all. The UART initialisation `FUN_400026f2` confirms it: the same encoding computes a baud divisor
  into an 8-bit register, where a remainder would make no sense.

### Regions that reference queries miss

The rules are in [Never call anything unused on one method](../../../notes/analysis_method.md#never-call-anything-unused-on-one-method).

- `0x8000f010` is zero in every section, yet it is a read primitive with 7 callers, loaded from the
  SPI NOR flash at run time by `FUN_8000767c` ([section2_map.md](section2_map.md)).
- Driver code builds addresses from immediates: `addi.l #0x8000ba00,d0` and `adda.l #0x80008800,a0`
  produce scalar operands, which Ghidra records as such, not as references, so `DumpRefsInRange`,
  `RefDensityMap` and `FindDeadSpace` cannot see them. SRAM bank 2
  (`0x80008300`–`0x8000c000`) looks reference-free and holds the USB/DMA descriptor rings
  ([memory_map.md](memory_map.md#the-rule-reference-queries-are-not-enough)).

### Functions that look dead

- ⛔ Filtering references to calls and jumps drops the `DATA` references that store an address in a
  callback slot. The sequencer dispatcher `FUN_4007011c` looks dead that way; its address appears
  nowhere in the image because the reference is PC-relative.
- The audio ISR `FUN_40077120` has no Ghidra reference at all. Its address is in the image only as
  operand literals whose words happen to be 4-byte aligned: the `movel #0x40077120,%d0` at
  `0x40076baa` with which `FUN_40076b5a` fills the vector slot `0x400002fc` at run time, and a compare
  at `0x400770e6` (objdump). The vector slot itself lies below section 3, outside the image.

### Commands with real addresses

Disassembly with objdump:

```sh
./scripts/disasm.sh 1.52A 0x4006a570 0x4006a590                  # stock MAIN OS, load addresses
./scripts/disasm.sh 1.52A 0x80000ec0 0x80000f00 dsp              # the DSP section at its run address
```

The first is the same as

```sh
m68k-elf-objdump -D -b binary -m m68k:cfv4e --adjust-vma=0x40000400 \
    --start-address=0x4006a570 --stop-address=0x4006a590 \
    work/dt_1.52A/section_3_MAIN_OS.bin
```

For a build of this OS, name the file: `./scripts/disasm.sh 1.52A:out/1.52A/<build>.syx <start> <stop>`.

Decompiling and querying (selectors and output folders:
[docs/toolchain.md](../../../docs/toolchain.md#4e-decompiling-and-querying)):

```sh
./scripts/ghidra_decompile.sh 1.52A class:MachineListView 'str:Slice Select' addr:0x4000b564
GHIDRA_LANG_VARIANT=emac ./scripts/ghidra_decompile.sh 1.52A 're:.*'        # every function, EMAC project
SECTION=dsp ./scripts/ghidra_decompile.sh 1.52A 're:.*'                     # every DSP function
```

```sh
./scripts/ghidra_query.sh 1.52A main RefDensityMap       0x40214000 0x439902a0   # where .bss is referenced
./scripts/ghidra_query.sh 1.52A main DumpRefsInRange     0x40214000 0x40240000   # zoom into a stretch
./scripts/ghidra_query.sh 1.52A main FindDeadSpace       0x40000400 0x40214000 16
./scripts/ghidra_query.sh 1.52A main FindAddressLiterals 0x8000edc8 0x8000f0b8   # immediates in a window
```

## Positive controls

Validate every scanner with a positive control first
([the rule](../../../notes/analysis_method.md#never-call-anything-unused-on-one-method)):
known-referenced functions must show their references, or a zero from the scanner means nothing.

- `FUN_40021fce`: 4 `jsr`.
- `FUN_40076fe6`: 6.

[FindDeadFunctions.java](../scripts/ghidra/FindDeadFunctions.java) has a built-in check (its
`KNOWN_LIVE` table) against ten functions known to be live; on `dt_1.52A_seed` it passes
([reference numbers](#reference-numbers)). The controls used to vet a landing pad are in
[landing_pads.md](landing_pads.md#vetting-controls-on-this-image).
