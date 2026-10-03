# Analysis method

## What this is

How the notes in this repo were produced, and the rules that keep them reproducible. Analysis never
changes the firmware; a count that differs between projects names the Ghidra project it came from;
anything load-bearing is decoded twice, by Ghidra and by GNU objdump. The recommended reading order of
the notes is kept in one place, [README.md](README.md#reading-order).

## Three kinds of file

| Kind | Where | In git |
|---|---|---|
| Tooling and instructions: shell and Ghidra scripts, the EMAC language patch, the build data, these notes | `scripts/`, `build/`, `docs/`, `notes/` | yes |
| Third-party inputs: your own stock OS `.syx`, your own manual | `sysex/`, `manuals/` | no. You supply them; they are never redistributed |
| Derived output: the built firmware tool, extracted sections, Ghidra projects, decompiled text, build outputs | `tool/`, `work/`, `out/` | no. Regenerable from the two rows above |

Third-party content stays out of git even when it is a build input. The EMAC language extension is the
clearest case: the repo holds only the project's patch, and Ghidra's own 164 KB `68000.sinc` is copied
out of your local Ghidra install when the extension is built.

## The firmware is never modified by analysis

- `sysex/*.syx` and the extracted `work/<image>/section_*.bin` are read-only for every analysis
  script.
- What analysis does change is derived and can be deleted and regenerated:
  - **The Ghidra project**: RTTI naming, functions created by `mkfunc:`, seeded code.
    `ghidra_decompile.sh` saves the functions its `mkfunc:` selector creates, so a project that has
    served decompile runs is no longer a pure fresh import. `ghidra_emu.sh` opens the project
    read-only; the query scripts only read it; the curation scripts (`SeedCodeGaps`, `CurateDsp`,
    `FixDspResidual`) change it and belong on a copy.
  - **The text output** under `work/ghidra/out/`.
- Patching is a separate, deliberate step on a copy: `build/build.py` applies
  [build/patch.json](../build/patch.json) to your stock section 3 and checks the SHA-256 of the input and
  of the result (section 3 stock `59278368…d8e864ee`, patched `34765cdf…4888e864`). The byte runs are
  listed in [docs/patch_listing.md](../docs/patch_listing.md).
- Decompiled C is derived from Elektron's firmware. It stays in `work/`; the notes describe it in words
  and give addresses.

## Reproducing the analysis

1. `./scripts/extract.sh` and `./scripts/roundtrip.sh` on your stock `sysex/Digitakt_OS1.52A.syx`.
2. Install Ghidra 12.1.3 with OpenJDK 21, and GNU binutils for m68k.
3. `./scripts/ghidra_lang_ext.sh` builds and installs the EMAC language.
4. `./scripts/ghidra_analyze.sh dt main` (stock language) and
   `GHIDRA_LANG_VARIANT=emac ./scripts/ghidra_analyze.sh dt main` (EMAC language).
5. Then `ghidra_decompile.sh`, `ghidra_query.sh`, `disasm.sh` and `ghidra_emu.sh` as the notes use
   them.

- ✅ **The base analysis is deterministic.** A fresh import reproduced the identical function address
  set (11,053 functions), the same 1,786 typeinfos, the same 3,469 renamed functions and the same 47
  error sites (stock project).
- **Ad-hoc queries are not part of the base analysis.** Functions created by `mkfunc:` or found by
  hand live only in the local project, so a fresh import lacks them. Every note therefore records
  concrete addresses; re-issue the same `addr:` or `mkfunc:` query to check one.
- **Decompile line numbers are locators only.** A note that cites "line 314" of a function's decompile
  means the project's own decompile output. Line numbers shift with the project state and the Ghidra
  version; the address is the reference.
- Addresses are load addresses of the decompressed sections. MAIN OS loads at `0x40000400`, so the file
  offset for a byte edit is `load − 0x40000400`.

## Say which project a number came from

Counts differ between projects by design. A count without its project and its measure cannot be
compared with another one.

| Project (under `work/ghidra/`) | Made by | Language | Used for |
|---|---|---|---|
| `dt_1.52A` | `ghidra_analyze.sh dt main` | `68000:BE:32:Coldfire` (stock) | the control |
| `dt_1.52A_emac` | the same with `GHIDRA_LANG_VARIANT=emac` | `68000:BE:32:ColdfireEMAC` | audio code, the emulator |
| `dt_1.52A_seed` | a fresh EMAC import plus `SeedCodeGaps.java` | `68000:BE:32:ColdfireEMAC` | map completeness, dead-function scans (`GHIDRA_PROJECT=dt_1.52A_seed`) |
| `dt_1.52A_sram` | `scripts/build_sram_image.py`, imported at `0x80000000` | `68000:BE:32:ColdfireEMAC` | section 2 and the updater in their shared SRAM address space ([section2_map.md](section2_map.md)) |

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

## Decode twice

The second decoder is GNU objdump, which shares no state with Ghidra:

    m68k-elf-objdump -D -b binary -m m68k:cfv4e --adjust-vma=0x40000400 \
        --start-address=<start> --stop-address=<stop> section_3_MAIN_OS.bin

`./scripts/disasm.sh <start> <stop> [section] [file]` wraps it.

- `-m m68k:cfv4e` (the MCF5441x variant) decodes ColdFire EMAC in full; `m68k:isa-a:emac` works too;
  plain `m68k:cfv4` does not. `objdump -i` lists the m68k variants uninformatively, so pass `-m` and
  see whether it errors. For the `(d16,An)` MAC forms, `-M isac,emac` also decodes them.
- **Ghidra agreeing with itself is not evidence.** Check every new SLEIGH constructor against objdump.
  ✅ objdump confirms the `movclr.l` decoding, and it catches a constructor with the wrong length:
  `0x40072f68` is a 6-byte instruction, and a constructor with the wrong length can decode it as 4
  bytes.
- **Start from a known instruction boundary.** A linear sweep is right only from an address Ghidra
  reached or a function entry. A mis-sized instruction in either decoder puts the next fetch
  mid-instruction; branches to odd addresses are the usual symptom.
- **Reading tip:** objdump prints `remsl Dx,Dx,Dx` (the same register twice) for what is a signed
  32-bit divide. `rems.l` needs two different registers, so that encoding (for example `4c40 2802`) is
  `divs.l`; the whole MAIN OS dump contains no `divsl` or `divul` mnemonic at all. The UART
  initialisation `FUN_400026f2` confirms it: the same encoding computes a baud divisor into an 8-bit
  register, where a remainder would make no sense.
- For the other objdump display quirks, see [emulator.md](emulator.md).

## The EMAC language extension

Ghidra 12.1.3's ColdFire specification does implement the EMAC unit (`mac`, `msac`, `ACC0`–`ACC3`,
`MACSR`, `MASK`, `ACCext01/23`), but it had four narrow gaps. Each one truncated or derailed functions
in the MAC-heavy audio code:

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

How it is built, and why:

- **Additive only.** The patch adds constructors and narrows the modes of two existing ones; it never
  widens an existing pattern. ⛔ **Ruled out: simply deleting the `op6=0` pin.** It makes the stock
  constructor match `(d16,An)` forms it cannot size, and a mis-sized instruction is worse than the clean
  error it replaces. All 80 MAC instructions in the stock project already matched objdump's lengths, so
  that latent problem never fired on this binary; the deletion would have been its first instance.
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
the binary and the model disagree, the model is incomplete. Example: `0x8000f010` is zero in every
section, yet it is a read primitive with 7 callers, loaded from the SPI NOR flash at run time by
`FUN_8000767c` ([section2_map.md](section2_map.md)).

- **Reference queries see memory references; driver code builds addresses from immediates.**
  `addi.l #0x8000ba00,d0` and `adda.l #0x80008800,a0` produce scalar operands, which
  `DumpRefsInRange`, `RefDensityMap` and `FindDeadSpace` cannot see. Run `FindAddressLiterals`, which
  scans every instruction's operands, before calling a region unused. SRAM bank 2
  (`0x80008300`–`0x8000c000`) looks reference-free and holds the USB/DMA descriptor rings.
- **Deadness needs three sources, used as a union:**

  | Evidence | Catches | Misses |
  |---|---|---|
  | Ghidra references of every type, `DATA` included | PC-relative address taking (`lea (d16,PC),An`), invisible to any byte scan | some handler installs |
  | aligned 4-byte pointer words in the image | vtable slots, jump and handler tables | PC-relative references, unaligned words |
  | operand literals (`FindAddressLiterals`) | `move.l #FUN_x,Dn`-style installs | PC-relative references |

  ⛔ **Ruled out: filtering references to calls and jumps.** It drops the `DATA` references that store
  an address in a callback slot. The sequencer dispatcher `FUN_4007011c` looks dead that way; its
  address appears nowhere in the image because the reference is PC-relative. The audio ISR
  `FUN_40077120` has no Ghidra reference at all. Its address is in the image only as operand literals
  whose words happen to be 4-byte aligned: the `movel #0x40077120,%d0` at `0x40076baa` with which
  `FUN_40076b5a` fills the vector slot `0x400002fc` at run time, and a compare at `0x400770e6`
  (objdump). The vector slot itself lies below section 3, outside the image.
- **A byte scan for callers must include PC-relative calls** (`jsr %pc@(…)`, `4eba`), which
  absolute-address searches miss.
- **Validate every scanner with a positive control** first: known-referenced functions must show their
  references (`FUN_40021fce`: 4 `jsr`; `FUN_40076fe6`: 6), or a zero from the scanner means nothing.
  `FindDeadFunctions.java` has a built-in check against ten functions known to be live.
- **Raw big-endian word scans are a superset check only.** On code, opcode words such as `41f9` read as
  `0x4xxxxxxx` values, and unaligned offsets invent hits. Use them to generate candidates, then confirm
  in Ghidra or objdump.
- **A static verdict of dead is a candidate list, not a safety signal.** Code reached through a
  fully computed address is invisible to all three sources. A landing pad is trusted only after it has
  been run on a unit ([landing_pads.md](landing_pads.md)).
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
- First import: `-import <file> -loader BinaryLoader -loader-baseAddr 0x40000400`. Re-runs on an
  existing project use `-process <file> -noanalysis`, not `-import`.
- The post-scripts are Java, which Ghidra compiles itself, so headless runs need no PyGhidra. The
  bundled PyGhidra 3.1.0's JPype wheel for Python 3.9 on macOS is x86_64-only; on Apple Silicon,
  PyGhidra needs Python 3.10 or later.
- GNU binutils for m68k: Homebrew's `m68k-elf-binutils` (2.47), or Debian's
  `binutils-m68k-linux-gnu` with `M68K_PREFIX=m68k-linux-gnu-`.
