# ColdFire EMAC language extension for Ghidra

Ghidra's stock ColdFire processor definition has four gaps that stop or derail disassembly in the
Digitakt's MAC-heavy audio code. This folder holds **our patch and two small spec files adapted from
Ghidra's** (see [`NOTICE.md`](NOTICE.md)); the 164 KB `68000.sinc` itself is Ghidra's file and is
copied out of your local install at build time. Build and install with:

    ./scripts/ghidra_lang_ext.sh            # build + install
    ./scripts/ghidra_lang_ext.sh --check    # report what is installed

Licence: this folder is derived from Ghidra's Apache-2.0 sources and stays under Apache-2.0, not
under the CC0 dedication of the rest of the repository; see [`NOTICE.md`](NOTICE.md).

## What the patch changes

All four were verified against `m68k-elf-objdump -m m68k:cfv4e` (or `-M isac,emac`), which decodes
ColdFire EMAC fully (`./scripts/disasm.sh`, and `docs/toolchain.md` section 5). Keep it as an
independent second opinion whenever a decoding looks wrong.

1. **`movclr.l ACCx,Rx` was missing.** It reads an accumulator and clears it, and differs from the
   already-defined plain read by one bit (`op47=0b1100` vs `0b1000`). A run like
   `a1c0 a3c1 a5c2 a7c3` (drain all four accumulators) ends many audio loops, so the omission
   truncated every function that used one.
2. **The `mac.l`-with-load constructor pins `& op6=0`**, which contradicts its own `macrw` operand
   (`macrw` already selects a data register at `op6=0` or an address register at `op6=1`), so the
   address-register form could never match. The `.w` constructor just above it has no such pin, so
   the `.l` one is a typo. The patch adds the `op6=1` form (modes 2/3/4).
3. **`mac.l Ry,Rx,(d16,An),Rw` (mode 5, `(d16,An)` addressing) was mis-decoded.** The shared `m_eal`
   EA table reads `d16` from the word right after the opcode, but for a MAC that word is the
   *extension word*, so a 6-byte instruction decoded as 4 and derailed everything after it, for
   example at `0x40075cfa` in `FUN_400754fe` and `0x400721c6` in `FUN_40072178` (OS 1.52A). The patch
   adds a dedicated constructor that puts `d16` in **word 3** (a third `;`), one constructor for both
   `op6=0` (data-register `Rw`) and `op6=1` (address-register `Rw`), since `macrw` picks the register
   by `op6`, and it excludes `mode=5` from the two op6-specific constructors so the new one wins.
   After a rebuild and re-import, Ghidra decodes both sites as `[len 6]`, matching objdump.
4. **`msac.l Ry,Rx,(d16,An),Rw` (mode 5) was mis-decoded** the same way: it is the
   multiply-*subtract* sibling of gap 3. The stock `msac.l`-with-load constructor has no `op6` pin, but
   it shares `m_eal`, so its `(d16,An)` form was also sized 6 → 4. It shows up only once gap 3 is fixed,
   because the corrected `mac.l` decode keeps the stream aligned far enough to reach the `msac.l`
   sites, for example `0x40073b6e` in `FUN_40073900` and `0x40075d24` in `FUN_400754fe` (OS 1.52A).
   The patch adds the dedicated word-3-`d16` constructor (`accreg = accreg - tmp`) and excludes
   `mode=5` from the stock `msac.l`-with-load form.

The two comment lines in `coldfire_emac.patch` that name addresses cite OS 1.52A sites where the
decoding was checked against objdump; the patch itself applies to any ColdFire code.

Result on the OS 1.52A MAIN OS: [os/1.52A/notes/analysis_reference.md](../../os/1.52A/notes/analysis_reference.md#the-emac-language-extension-on-this-image).

The patch is **additive**: it adds constructors and narrows the modes of two existing ones, and never
widens an existing pattern. Simply deleting the stray `& op6=0` pin would make the stock constructor
match `(d16,An)` forms it cannot size correctly, and a mis-sized instruction is worse than the clean
error it replaces.

## Fidelity: deliberately matching the stock EMAC support, not exceeding it

`movclr.l` is modelled as `Rx = ACCx; ACCx = 0`. It does **not** model MACSR flag updates,
accumulator saturation/rounding, or the `ACCext01/23` extension bytes, which are exactly the
simplifications Ghidra's own `mac.l` already makes (it is plain `accreg = accreg + tmp`, and `ACC0-3`
are declared `size=4` although the hardware accumulators are 48-bit).

What this costs:

- **Nothing** for instruction boundaries, function extents, control flow, the call graph, or
  **absolute references**, which is what this is for.
- Decompiler output degrades in functions that read `MACSR` after a MAC or `movclr`
  (`move.l %macsr,%ccr` then a conditional branch): the flags were never written, so the condition
  cannot be resolved. Read the assembly there. This already happens with the stock `mac.l`.
- Numeric data flow through the accumulators is approximate (a 32-bit model of a 48-bit accumulator,
  no saturation), so "what does this filter compute" stays a read-the-algorithm job.
- The emulator harnesses in each OS folder's `os/<os>/scripts/emu/` run Ghidra's p-code, so their
  verdicts hold only for code that does no accumulator arithmetic. The pad harnesses step plain
  integer code; the audio-ISR probes stub or skip the MAC-heavy render functions and watch only
  memory writes and control flow.

Modelling the flags later is an incremental edit to the same constructors, not a redesign.

## Layout

| file | what |
|---|---|
| `coldfire_emac.patch` | the four-hunk diff against Ghidra's `68000.sinc` |
| `coldfire_emac.slaspec` | our spec; includes the patched `68000.sinc` |
| `coldfire_emac.ldefs` | declares the language id `68000:BE:32:ColdfireEMAC` |
| `NOTICE.md`, `LICENSE-Apache-2.0.txt` | where these files come from, and their licence |

`Module.manifest` and `extension.properties` are generated by the build script (the latter carries
the Ghidra version, which must match the install).

## Install location, and why

The extension installs into Ghidra's **user** extension folder, never into the Ghidra install tree,
so upgrading Ghidra cannot silently revert it:

- macOS: `$HOME/Library/ghidra/ghidra_<version>_PUBLIC/Extensions/`
- Linux (and WSL): `$HOME/.ghidra/.ghidra_<version>_PUBLIC/Extensions/`. ⚠️ Not tested: Ghidra 11.1
  and later probably look in `${XDG_CONFIG_HOME:-$HOME/.config}/ghidra/ghidra_<version>_PUBLIC/Extensions/`
  on Linux instead.

That path carries the version, so **after a Ghidra upgrade, re-run the build script.** If Ghidra
changed `68000.sinc` upstream, the patch fails to apply loudly instead of quietly producing a stale
language. The patch context matches Ghidra 12.1.3.

Ghidra re-validates a `.sla` against its `.slaspec` when it loads a language and recompiles if they
disagree, so the build installs the source (`coldfire_emac.slaspec` plus the patched `68000.sinc`)
next to the compiled `.sla`. With the `.sla` alone, Ghidra refuses the language.

## Using it

`ghidra_langid()` in `scripts/common.sh` picks the import language: the stock
`68000:BE:32:Coldfire` by default, `68000:BE:32:ColdfireEMAC` when `GHIDRA_LANG_VARIANT=emac` is set.
A program is bound to the language it was imported with, so moving an existing project onto this one
needs a fresh import. The variant also gives the project its own folder, so the stock analysis stays
as a control:

    GHIDRA_LANG_VARIANT=emac ./scripts/ghidra_analyze.sh <os> main     # -> work/ghidra/dt_<os>_emac

To measure what the fix recovers, compare `error bookmarks`, `instructions` and `functions` in the two
`summary.txt` files under `work/ghidra/out/`.
