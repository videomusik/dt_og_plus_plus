# Notes

## What these notes are for

These notes describe how the Digitakt firmware works, as far as it matters for DT OG++, and what each
OS version's build changes. They are the reference for three jobs: understanding a
part of the firmware, checking a claim in this repo against the firmware yourself, and designing a
new change safely. They are not a user manual.

The notes in `notes/` hold what is true for every OS version: the conventions on this page, the
method, the device, recovery, the file format and the icon artwork. Everything measured in one OS
version's stock file is in that OS folder's notes
(OS 1.52A: [os/1.52A/notes/README.md](../os/1.52A/notes/README.md)).

Every fact carries its evidence and a confidence mark, and a count that differs between Ghidra projects
names the project it came from. Anything read from the code can be re-checked without a Digitakt: an
address, a stock `.syx` of your own and the toolchain in [docs/toolchain.md](../docs/toolchain.md) are
enough. The notes contain no manual text. They describe stock code in words and give addresses, with a
short expression or a single instruction as a locator; where a few lines of decompiled code explain a
site better than words, a note quotes a short, labelled excerpt
([Decompiler excerpts](#decompiler-excerpts)). The instruction listings in the notes are of the code
a DT OG++ build adds. Building the image is described in
[docs/building.md](../docs/building.md); a searchable copy of your own Digitakt manual, to keep beside
the notes, is made with [scripts/manual/README.md](../scripts/manual/README.md).

## How to read a note

Each note opens with a short "What this is". Facts about code are mostly in tables of the form
*address · what it does · evidence*. Search by the bare hex digits of an address (for example
`40076ee8`, an OS 1.52A address), which finds both the `FUN_40076ee8` name and the `0x40076ee8` form;
a few lists shorten a run of nearby addresses to their last digits. Search inside one OS folder
(`grep -rn <hex> os/<os>/notes`): the same hex digits name different code in another OS version.
Each OS folder keeps its own per-function ledger, `os/<os>/notes/function_ledger.md`
(OS 1.52A: [function_ledger.md](../os/1.52A/notes/function_ledger.md)); the other notes explain a
subsystem or a feature and point into it.

"The test unit" is one Digitakt (the original model). A result confirmed on it holds only for the OS
version it ran: inside an OS folder, that folder's version, which its notes/README.md states; in a
shared note, every unit result names its OS version. It says nothing about other units.

### Confidence marks

| Mark | Meaning |
|---|---|
| ✅ | Confirmed. The note always says which kind: **confirmed on the test unit** (with the OS version it ran), or **read directly in the code**, with the method in the evidence (decompiled, objdump, hexdump, reference or literal scan, emulator run) |
| ⚠️ | Inference: plausible but not confirmed. The note says what it rests on |
| ⛔ | Checked and ruled out, or a thing never to do. Kept on purpose, so the next reader does not walk the same path |

A statement with no mark describes what the code does, read by the method named in its evidence, with
no further claim. An inference stays marked ⚠️ until a check settles it; it never becomes a fact by
being repeated.

### Addresses

Every firmware address belongs to exactly one OS version's stock file. In an OS folder an address is a
load address in that OS's image, as the folder's notes/README.md defines it; in a shared note an
address is an example and names its OS on the same line. The address spaces and load bases of
OS 1.52A: [os/1.52A/notes/README.md](../os/1.52A/notes/README.md#addresses).

### Names

- **Ghidra auto-names** are addresses with no known name: `FUN_` (a function), `DAT_` and `_DAT_` (data;
  the underscore marks a global that overlaps smaller symbols), `LAB_` (a code label), `switchD_` (a
  switch dispatch) and `entry_` (a function), plus `PTR_` (a pointer) and `s_` (a string) for labelled
  data. A fresh import may not start a function at exactly the same address.
- **`Class::vfunc_N`** names come from the RTTI walk (`NameFromRtti.java`), which recovers the C++
  classes and vtables and names each virtual function after its class and slot. Slot *N* is at
  `vptr + 4N`. The vtable address in an RTTI listing is the start of the vtable structure, and the
  pointer an object stores is that address + 8, so reading slots from the listed address is off by two.
  One implementation often serves several classes; it carries one class's name, and the decompiler's
  "also …" footer lists the rest. A few real member names come from lambda type-info.

### Decompiler excerpts

Some notes quote short excerpts of Ghidra's decompiler output. That output is Ghidra's reconstruction
of Elektron's stock firmware code, not Elektron's source: names such as `uVar8`, `param_4` or
`local_54` are Ghidra's auto-names, and the types and casts are its guesses.

- **Fenced excerpts** are labelled on the line above with what they are and the function they come
  from, for example "Ghidra decompiler output (excerpt), `FUN_40077120`:" (an OS 1.52A function).
  "Variables renamed" in the
  label means some auto-named variables were given readable names; "condensed" means statements were
  shortened (for example a virtual call written as a method call). `…` marks code left out, and the
  comments are the notes' own.
- **Inline excerpts** are single expressions or statements in backticks, taken from the decompile
  (sometimes with variables renamed); Ghidra's auto-names, or the text, say so. Other inline code is
  the notes' own notation for what the code computes.
- An excerpt is a few lines that explain one site, never a whole function. The full decompile is made
  with the toolchain ([docs/toolchain.md](../docs/toolchain.md)) and is not part of this repository.

### Which Ghidra project a number came from

The notes cite Ghidra projects by their folder names under `work/ghidra/`. Each OS folder has its own
set, named after its OS; the section names are those of the folder's `os/<os>/profile.sh`.

| Project | What it is |
|---|---|
| `dt_<os>` | MAIN OS on Ghidra's stock ColdFire language: the control |
| `dt_<os>_emac` | MAIN OS on the ColdFire EMAC language: use it for anything touching audio code, and for the emulator |
| `dt_<os>_seed` | a copy of `_emac` with the code gaps seeded: the most complete MAIN OS map and the landing-pad candidate list |
| `dt_<os>_sram` | the 64 KB on-chip SRAM as the code that runs from it sees it, with those sections in their real address space (OS 1.52A: the `dsp` and `updater` sections) |
| `dt_<os>_dsp` | the `dsp` section alone |
| `dt_<os>_updater` | the `updater` section alone |
| `dt_<os>-<build>` | a build's image, given to the wrappers as `<os>:out/<os>/<build>.syx`; section and language suffixes follow as above |

How to make them is in [docs/toolchain.md](../docs/toolchain.md#4d-the-projects-and-how-to-make-them)
(section 4d); why counts differ between them is in [analysis_method.md](analysis_method.md). Each OS
folder lists its own projects and points to their reference numbers
(OS 1.52A: [os/1.52A/notes/README.md](../os/1.52A/notes/README.md#which-ghidra-project-a-number-came-from)).

Decompile line numbers ("line 314") are locators in that project's own decompile of the named
function. They shift with the Ghidra version and the project state; the address is the reference.

## Reading order

This is the reading order for the shared notes. Each OS folder's `os/<os>/notes/README.md` continues it
for that version (OS 1.52A: [os/1.52A/notes/README.md](../os/1.52A/notes/README.md#reading-order)).

**To understand the firmware:**

1. This page: what the notes are for, and the conventions every note follows.
2. [hardware.md](hardware.md): what is inside a Digitakt, and the chip's address spaces.
3. [firmware_image.md](firmware_image.md): the OS update file and the firmware tool.
4. [analysis_method.md](analysis_method.md): how the notes were produced, and how to re-check any of
   them.
5. [emulator.md](emulator.md): running stock code and inserted code in Ghidra's p-code emulator.
6. [flash_recovery.md](flash_recovery.md): the recovery routes.
7. The OS folder's own notes README, `os/<os>/notes/README.md`, and its reading order
   (OS 1.52A: [os/1.52A/notes/README.md](../os/1.52A/notes/README.md#reading-order)).

**Before writing a patch** (the update path first):

1. [update_moat_method.md](update_moat_method.md): why the update path must stay stock, how the
   protected set is found, and the two rules for every patch.
2. [landing_pad_method.md](landing_pad_method.md): where new code can go, and how a new pad is vetted.
3. [flash_recovery.md](flash_recovery.md): the recovery routes, and why code that runs at startup needs
   care.
4. [emulator.md](emulator.md): stepping inserted code before it is flashed.
5. [analysis_method.md](analysis_method.md): decode twice, and never call anything unused on one
   method.
6. Then the OS folder's own notes:
   - `os/<os>/notes/update_moat.md`: the code and image sections that must never change, so the unit
     can always be re-flashed (OS 1.52A: [update_moat.md](../os/1.52A/notes/update_moat.md));
   - `os/<os>/notes/memory_map.md`: where new data can go in RAM, and the routes for new code that
     are closed (OS 1.52A: [memory_map.md](../os/1.52A/notes/memory_map.md));
   - `os/<os>/notes/landing_pads.md`: the vetted pads where new code can go
     (OS 1.52A: [landing_pads.md](../os/1.52A/notes/landing_pads.md));
   - `os/<os>/notes/startup_hooks.md`: which hooks run at startup
     (OS 1.52A: [startup_hooks.md](../os/1.52A/notes/startup_hooks.md));
   - `os/<os>/notes/compatibility.md`: how new values behave on stock firmware and back
     (OS 1.52A: [compatibility.md](../os/1.52A/notes/compatibility.md)).

## Index

- [README.md](README.md): this page: what the notes are for, how to read a note, the confidence marks,
  the conventions for addresses, names, decompiler excerpts and Ghidra projects, and the reading order.
- [analysis_method.md](analysis_method.md): how the analysis was produced and kept reproducible: the
  Ghidra projects, decoding twice with objdump, the EMAC language extension, the deadness rules.
- [emulator.md](emulator.md): running stock code and a build's inserted code in Ghidra's p-code
  emulator, and the assembler rules for hand-written patch code.
- [firmware_image.md](firmware_image.md): the OS update file: SysEx transport, the container, the
  sections and the firmware tool.
- [flash_recovery.md](flash_recovery.md): what to do when a unit will not take an image, will not start
  or hangs, in the order to try things.
- [hardware.md](hardware.md): what is inside a Digitakt, from public teardown photographs and the
  image, and the chip's address spaces.
- [icon_artwork.md](icon_artwork.md): the robin and keyboard icon masters: their grids, the house
  style, and how to check an encoding.
- [landing_pad_method.md](landing_pad_method.md): what a landing pad is, where candidates come from,
  how a new pad is vetted, and the rules for code in a pad.
- [update_moat_method.md](update_moat_method.md): the OS-update flow, what goes into the protected set
  and how its extents are fixed, the two rules for every patch, and the design constraints.

### OS folders

Each OS folder's notes are indexed in its own `os/<os>/notes/README.md`; [os/README.md](../os/README.md)
lists the OS folders and what each holds.

- OS 1.52A: [os/1.52A/notes/README.md](../os/1.52A/notes/README.md)
