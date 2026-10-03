# NOTICE: the ColdFire EMAC language extension

The files in this folder are derived from Ghidra's Motorola 68000 processor module, which is
distributed under the Apache License, Version 2.0. A copy of that licence is in
[`LICENSE-Apache-2.0.txt`](LICENSE-Apache-2.0.txt), copied unchanged from the licence file shipped
with Ghidra 12.1.3 (`licenses/Apache_License_2.0.txt` in the install). Ghidra's
`Ghidra/Processors/68000/LICENSE.txt` states that the module is released under Apache 2.0 and lists
no third-party files. Ghidra is developed by the National Security Agency
(https://github.com/NationalSecurityAgency/ghidra).

This folder, including our changes, is therefore distributed under the Apache License 2.0. The CC0
1.0 dedication that covers the rest of this repository ([`LICENSE`](../../LICENSE)) does not apply
to it.

## Which files are derived, and from what

| File here | Derived from (Ghidra 12.1.3) | What was changed |
|---|---|---|
| `coldfire_emac.patch` | `Ghidra/Processors/68000/data/languages/68000.sinc` | A four-hunk diff. Its context and removed lines are Ghidra's text. It adds a `movclr.l ACCx,Rx` constructor; adds a `mac.l`-with-load constructor for an address-register `Rw` (`op6=1`); adds dedicated `(d16,An)` (mode 5) constructors for `mac.l` and `msac.l` that read `d16` from the third word; and restricts the existing `mac.l`/`msac.l`-with-load constructors to modes 2, 3 and 4 so the new ones take precedence. Comments explain each hunk. |
| `coldfire_emac.slaspec` | `Ghidra/Processors/68000/data/languages/coldfire.slaspec` | Only the comment lines. The `@define`/`@include` lines are unchanged. |
| `coldfire_emac.ldefs` | the ColdFire `<language>` entry of `Ghidra/Processors/68000/data/languages/68000.ldefs` | New variant and id (`ColdfireEMAC`, `68000:BE:32:ColdfireEMAC`), version 1.0, own `slafile` and description; the `manualindexfile` and the IDA/QEMU `external_name` entries were dropped; a comment was added. |
| `README.md`, `NOTICE.md` | none | Our own documentation. |

`68000.sinc` itself, and the `68000.pspec`, `68000.cspec`, `68000_register.cspec` and `68000.dwarf`
files the built extension needs, are **not** in this repository. `scripts/ghidra_lang_ext.sh` copies
them out of your local Ghidra install at build time, patches the copy, and installs the result into
your own Ghidra user folder.

The Ghidra Java scripts in `scripts/ghidra/` and `scripts/emu/` call Ghidra's scripting API but
contain no Ghidra source code; they are not covered by this notice.
