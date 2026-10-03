# Analysis method

## What this is

How the notes in this repo were produced, and the rules that keep them reproducible. Analysis never
changes the firmware; a count that differs between projects names the Ghidra project it came from;
anything load-bearing is decoded twice, by Ghidra and by GNU objdump. The recommended reading order of
the notes is kept in one place, [README.md](README.md#reading-order).

## Three kinds of file

| Kind | Where | In git |
|---|---|---|
| Tooling and instructions: shell and Ghidra scripts, the EMAC language patch, the build data, these notes | `scripts/`, `build/`, `docs/`, `notes/`, and in each OS folder `os/<os>/build/`, `os/<os>/docs/`, `os/<os>/notes/`, `os/<os>/scripts/` | yes |
| Third-party inputs: your own stock OS `.syx`, your own manual | `sysex/`, `manuals/` | no. You supply them; they are never redistributed |
| Derived output: the built firmware tool, extracted sections, Ghidra projects, decompiled text, build outputs | `tool/`, `work/`, `out/` | no. Regenerable from the two rows above |

Third-party content stays out of git even when it is a build input. The EMAC language extension is the
clearest case: the repo holds only the project's patch, and Ghidra's own 164 KB `68000.sinc` is copied
out of your local Ghidra install when the extension is built.

## The firmware is never modified by analysis

- `sysex/*.syx` and the sections extracted into `work/dt_<os>/` or `work/dt_<os>-<build>/`
  (`section_*.bin`) are read-only for every analysis script.
- What analysis does change is derived and can be deleted and regenerated:
  - **The Ghidra project**: RTTI naming, functions created by `mkfunc:`, seeded code.
    `ghidra_decompile.sh` saves the functions its `mkfunc:` selector creates, so a project that has
    served decompile runs is no longer a pure fresh import. `ghidra_emu.sh` opens the project
    read-only; the query scripts only read it; the curation scripts (`SeedCodeGaps`, `CurateDsp`,
    `FixDspResidual`) change it and belong on a copy.
  - **The text output** under `work/ghidra/out/`.
- Patching is a separate, deliberate step on a copy: each OS folder's `os/<os>/build/build.py` applies
  that folder's `os/<os>/build/patch.json` to your stock section 3 and checks the SHA-256 of the input
  and of the result. The section-3 hashes of each OS are in its notes
  (OS 1.52A: [analysis_reference.md](../os/1.52A/notes/analysis_reference.md#the-section-3-hashes)).
  The byte runs are listed in each OS folder's `os/<os>/docs/patch_listing.md`
  (OS 1.52A: [patch_listing.md](../os/1.52A/docs/patch_listing.md)).
- Decompiled C is derived from Elektron's firmware. It stays in `work/`; the notes describe it in words
  and give addresses.

## Reproducing the analysis

`<os>` is the name of an OS folder, `os/<os>/`, and every command names it (OS 1.52A: `1.52A`).

1. `./scripts/extract.sh <os>` and `./scripts/roundtrip.sh <os>` on your stock OS file.
2. Install Ghidra 12.1.3 with OpenJDK 21, and GNU binutils for m68k.
3. `./scripts/ghidra_lang_ext.sh` builds and installs the EMAC language.
4. `./scripts/ghidra_analyze.sh <os> main` (stock language) and
   `GHIDRA_LANG_VARIANT=emac ./scripts/ghidra_analyze.sh <os> main` (EMAC language).
5. Then `ghidra_decompile.sh`, `ghidra_query.sh`, `disasm.sh` and `ghidra_emu.sh` as the notes use
   them.

- ✅ **The base analysis is deterministic** (measured on OS 1.52A): a fresh import reproduced the
  identical function address set and the same counts.
  OS 1.52A figures: [analysis_reference.md](../os/1.52A/notes/analysis_reference.md#determinism).
- **Ad-hoc queries are not part of the base analysis.** Functions created by `mkfunc:` or found by
  hand live only in the local project, so a fresh import lacks them. Every note therefore records
  concrete addresses; re-issue the same `addr:` or `mkfunc:` query to check one.
- **Decompile line numbers are locators only.** A note that cites "line 314" of a function's decompile
  means the project's own decompile output. Line numbers shift with the project state and the Ghidra
  version; the address is the reference.
- Addresses are load addresses of the decompressed sections. The MAIN OS load base comes from the OS
  folder's `profile.sh` (OS 1.52A: `0x40000400`), so the file offset for a byte edit is
  `load − <load base>`.

## Say which project a number came from

Counts differ between projects by design. A count without its project and its measure cannot be
compared with another one.

Projects live in `work/ghidra/<project>/` and are named after the image's work folder: `dt_<os>` for an
OS folder's stock file, `dt_<os>-<build>` for another file of that OS such as a build, then
`_<section>` for any section other than `main` and `_<variant>` for another language (`_emac`).
Hand-made projects such as `dt_<os>_seed` and `dt_<os>_sram` are reached with `GHIDRA_PROJECT`. How
each is made: [docs/toolchain.md](../docs/toolchain.md#4d-the-projects-and-how-to-make-them).

Each OS folder lists its projects and the counts measured in each, by project and measure
(OS 1.52A: [analysis_reference.md](../os/1.52A/notes/analysis_reference.md#the-ghidra-projects)).

## Decode twice

The second decoder is GNU objdump, which shares no state with Ghidra:

    m68k-elf-objdump -D -b binary -m m68k:cfv4e --adjust-vma=<load base> \
        --start-address=<start> --stop-address=<stop> section_3_MAIN_OS.bin

`<load base>` is the section's load address from the OS folder's `profile.sh` (OS 1.52A MAIN OS: `0x40000400`).
`./scripts/disasm.sh <os> <start> <stop> [section] [file]` wraps it; `<os>:<file.syx>` in place of
`<os>` reads another file of that OS, such as a build.

- `-m m68k:cfv4e` (the MCF5441x variant) decodes ColdFire EMAC in full; `m68k:isa-a:emac` works too;
  plain `m68k:cfv4` does not. `objdump -i` lists the m68k variants uninformatively, so pass `-m` and
  see whether it errors. For the `(d16,An)` MAC forms, `-M isac,emac` also decodes them.
- **Ghidra agreeing with itself is not evidence.** Check every new SLEIGH constructor against objdump.
  objdump catches a constructor with the wrong length, which can decode a 6-byte instruction as 4
  bytes.
- **Start from a known instruction boundary.** A linear sweep is right only from an address Ghidra
  reached or a function entry. A mis-sized instruction in either decoder puts the next fetch
  mid-instruction; branches to odd addresses are the usual symptom.
- **Reading tip:** objdump prints `remsl Dx,Dx,Dx` (the same register twice) for what is a signed
  32-bit divide. `rems.l` needs two different registers, so that encoding (for example `4c40 2802`) is
  `divs.l`.
- For the other objdump display quirks, see [emulator.md](emulator.md).
- Worked examples (the `movclr.l` check, a 6-byte instruction, the divide encoding in a UART set-up),
  OS 1.52A: [analysis_reference.md](../os/1.52A/notes/analysis_reference.md#worked-examples).

## The EMAC language extension

Ghidra 12.1.3's ColdFire specification does implement the EMAC unit (`mac`, `msac`, `ACC0`–`ACC3`,
`MACSR`, `MASK`, `ACCext01/23`), but it had four narrow gaps. Each one truncated or derailed functions
in the MAC-heavy audio code:

| Gap | Effect |
|---|---|
| `movclr.l ACCx,Rx` missing (`op47=0b1100`, one bit from the defined plain read) | `a1c0 a3c1 a5c2 a7c3` drains all four accumulators at the end of many audio loops, so every function containing one was cut short |
| `mac.l` with a load into an address register: the `.l` constructor pinned `op6=0`, contradicting its own `macrw` operand (the `.w` constructor has no such pin) | the form could never match |
| `mac.l Ry,Rx,(d16,An),Rw` (mode 5): the shared EA table read `d16` from the MAC extension word | a 6-byte instruction decoded as 4, and everything after it was junk |
| `msac.l Ry,Rx,(d16,An),Rw` (mode 5), the same fault in the multiply-subtract sibling | the same; visible only once the `mac.l` fix kept the stream aligned |

The sites of each gap, with examples, and how the error bookmarks and the decoded MAC instructions
changed with each fix, OS 1.52A: [analysis_reference.md](../os/1.52A/notes/analysis_reference.md#the-emac-language-extension-on-this-image).

How it is built, and why:

- **Additive only.** The patch adds constructors and narrows the modes of two existing ones; it never
  widens an existing pattern. ⛔ **Ruled out: simply deleting the `op6=0` pin.** It makes the stock
  constructor match `(d16,An)` forms it cannot size, and a mis-sized instruction is worse than the clean
  error it replaces.
- **Ghidra re-validates a `.sla` against its `.slaspec` when it loads a language** and refuses the
  language if they disagree, so the source (`coldfire_emac.slaspec` plus the patched `68000.sinc`) is
  installed next to the compiled file.
- **It installs into Ghidra's user extension folder**, whose path carries the Ghidra version: re-run
  `ghidra_lang_ext.sh` after a Ghidra upgrade. If Ghidra changed `68000.sinc` upstream, the patch fails
  to apply instead of producing a stale language.
- **A program is bound to the language it was imported with**, so the EMAC language needs a fresh
  import, into its own project; the stock project stays as the control.
- **Fidelity is deliberately capped** at what Ghidra's own `mac.l` does: `movclr.l` is
  `Rx = ACCx; ACCx = 0`, with no MACSR flags, no saturation and 32-bit accumulators. Instruction
  boundaries, control flow and references are exact; the decompiler cannot resolve a branch on `MACSR`
  after a MAC, and accumulator arithmetic is approximate.
- ⛔ **Ruled out: the MACSR moves (`0xa980`, `0xa93c`, `0xa908`) are a SLEIGH gap.** The stock
  `68000.sinc` defines `move.l MACSR,Rx` and `move.l <ea>,MACSR`, and also `move.l MASK,Dn` and
  `move.l #imm,MACSR`. Read the specification before claiming a gap.

The full description of the patch is in [scripts/ghidra_ext/README.md](../scripts/ghidra_ext/README.md).

## Never call anything unused on one method

A region that is zero or makes no sense statically is usually filled at run time: a DMA target, a
table built at boot, code loaded from flash. Treat it as occupied until it is proven otherwise. When
the binary and the model disagree, the model is incomplete.
OS 1.52A example, a read primitive that is zero in every section: [analysis_reference.md](../os/1.52A/notes/analysis_reference.md#worked-examples).

- **Reference queries see memory references; driver code builds addresses from immediates.** A Ghidra
  reference query reports memory *references*, but an `addi.l #<address>,d0` or
  `adda.l #<address>,a0` produces a scalar operand, not a reference, which `DumpRefsInRange`,
  `RefDensityMap` and `FindDeadSpace` cannot see. A region owned by fixed-address DMA rings is
  therefore invisible to them, and a reference query over it returns zero.
  OS 1.52A example, SRAM bank 2 and its DMA rings: [memory_map.md](../os/1.52A/notes/memory_map.md#the-rule-reference-queries-are-not-enough).
- ⛔ **Never call a region unused on reference queries alone.** Run `FindAddressLiterals`, which scans
  every instruction's operand scalars, first, and read a zero from it as "no literal in this window",
  not as proof. Zeros in the image and a plausible explanation (such as alignment padding before a
  16 KB-aligned table) are not evidence either: a region built at run time looks exactly like that.
- **Deadness needs three sources, used as a union:**

  | Evidence | Catches | Misses |
  |---|---|---|
  | Ghidra references of every type, `DATA` included | PC-relative address taking (`lea (d16,PC),An`), invisible to any byte scan | some handler installs |
  | aligned 4-byte pointer words in the image | vtable slots, jump and handler tables | PC-relative references, unaligned words |
  | operand literals (`FindAddressLiterals`) | `move.l #FUN_x,Dn`-style installs | PC-relative references |

  ⛔ **Ruled out: filtering references to calls and jumps.** It drops the `DATA` references that store
  an address in a callback slot. A function whose address is only taken PC-relative has its address
  nowhere in the image, and one installed at run time through an operand literal can have no Ghidra
  reference at all.
  OS 1.52A examples, the sequencer dispatcher and the audio ISR: [analysis_reference.md](../os/1.52A/notes/analysis_reference.md#worked-examples).
- **A byte scan for callers must include PC-relative calls** (`jsr %pc@(…)`, `4eba`), which
  absolute-address searches miss.
- **Validate every scanner with a positive control** first: functions known to be referenced must
  show their references, or a zero from the scanner means nothing. Use the OS folder's positive
  controls; its `os/<os>/scripts/ghidra/FindDeadFunctions.java` has a built-in check against functions
  known to be live in that OS.
  OS 1.52A: [analysis_reference.md](../os/1.52A/notes/analysis_reference.md#positive-controls).
- **Raw big-endian word scans are a superset check only.** On code, opcode words such as `41f9` or
  `46fc` read as `0x4xxxxxxx` values, and unaligned offsets invent hits. Use them to generate
  candidates, then confirm in Ghidra or with `m68k-elf-objdump -m m68k:cfv4e`.
- **A static verdict of dead is a candidate list, not a safety signal.** Code reached through a
  fully computed address is invisible to all three sources. A landing pad is trusted only after it has
  been run on a unit with that OS version ([landing_pad_method.md](landing_pad_method.md#vetting-a-new-pad)).
- **Vtable slots:** the vtable address in the RTTI output is the start of the structure (offset-to-top,
  typeinfo pointer, then the slots). The pointer an object stores is that address + 8, and Ghidra's
  `Class::vfunc_N` is at `vptr + 4N`. Reading slots off the structure address is off by two. Check
  against a constructor's `movel #vtbl,%aN@` store.

## Toolchain notes

The install steps, with a check for each, are in [docs/toolchain.md](../docs/toolchain.md). The points
that matter most for the analysis:

- Ghidra is the Homebrew formula `ghidra`, not a cask. Homebrew links `ghidraRun` and `pyghidraRun`
  but not `analyzeHeadless`; call `support/analyzeHeadless` by its full path. Without `JAVA_HOME`,
  headless Ghidra fails when there is no terminal to ask for a JDK.
- ColdFire is its own language in Ghidra (`68000:BE:32:Coldfire`, `coldfire.sla`), not a flag on the
  68000 language.
- First import: `-import <file> -loader BinaryLoader -loader-baseAddr <load base>`, the section's load
  base from the OS folder's `profile.sh` (OS 1.52A MAIN OS: `-loader-baseAddr 0x40000400`). Re-runs on
  an existing project use `-process <file> -noanalysis`, not `-import`.
- The post-scripts are Java, which Ghidra compiles itself, so headless runs need no PyGhidra. The
  bundled PyGhidra 3.1.0's JPype wheel for Python 3.9 on macOS is x86_64-only; on Apple Silicon,
  PyGhidra needs Python 3.10 or later.
- GNU binutils for m68k: Homebrew's `m68k-elf-binutils` (2.47), or Debian's
  `binutils-m68k-linux-gnu` with `M68K_PREFIX=m68k-linux-gnu-`.
