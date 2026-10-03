# Inspecting, extracting and round-tripping the Digitakt OS image

Everything here works on files: no device is connected, nothing is flashed. All output lands in
`work/` (gitignored). The commands live in `scripts/`; they find the repo root themselves, so you can
run them from anywhere, but paths you pass are easiest to give relative to the repo root.

Prerequisites: your stock file at `sysex/Digitakt_OS1.52A.syx`, and the firmware tool built with
`bash build/build_tool.sh` from your clone of elektron-firmware-tool ([`toolchain.md`](toolchain.md)
sections 1 and 2).

## Which image

Every script takes an image argument:

- `dt` (the default): your stock Digitakt OS 1.52A. The scripts check its SHA-256
  (`01315133…fa56a4`) and refuse any other file. Output folder: `work/dt_1.52A/`.
- a path to a `.syx`, for example a build output: `out/dt_og_plus_plus_v0.1_<hash8>.syx`. Output folder:
  `work/<file name without .syx>/`. No hash is enforced; the file's SHA-256 is printed.

The stock file's location can be changed with `STOCK_SYX=...`, and the tool's with
`FIRMWARE_TOOL=...`.

## The steps

| Step | Script | What it does, and what to expect |
|---|---|---|
| 1 | `./scripts/inspect.sh [-v] [image]` | Prints the summary; writes nothing. Expect the lines below and `checksums : ok`. `-v` prints the full report: transport statistics, the container header and the section table. |
| 2 | `./scripts/extract.sh [image]` | Extracts every section to `work/<image>/section_<id>_<NAME>.bin` (`.raw` when a section is stored uncompressed) and saves the `-v` report next to them as `report.txt`; then prints the section table and, for `dt`, checks the MAIN OS hash. |
| 3 | `./scripts/roundtrip.sh [image]` | Rebuilds the image from its *unmodified* MAIN OS section, verifies the rebuilt `.syx`, re-extracts it and `cmp`s every section. Expect `checksums : ok`, four `identical` lines and `round-trip OK`. **Passing this is the precondition for any modification.** |

`inspect.sh` on the stock file prints:

```
device    : Digitakt (0x0a)
version   : 1.52A
container : ELE3, 917072 B
sections  :
  id 5   meta            15 B (raw)
  id 2   DSP          26670 B
  id 3   MAIN OS    2221632 B
  id 4   updater      32776 B (raw)
checksums : ok
```

Digitakt OS 1.52A has no signature trailer; its integrity is the transport, container and section
checksums, all of which the tool recomputes on a rebuild. `report.txt` therefore holds no key
material, but it still describes Elektron's image: keep it in `work/`.

## The sections of the stock image

| id | File (pinned tool) | Size | `dst` in the container | Loads / runs at | SHA-256 |
|---|---|---:|---|---|---|
| 5 | `section_5_meta.raw` | 15 B | `0x00000000` | (metadata, stored raw) | `c30150cad153fcf94898d60e58b3995e9fd07370d3c3e80e5b03d35334856dba` |
| 2 | `section_2_DSP.bin` | 26,670 B | `0x03000900` | `0x80000ec0`, after stripping its 24-byte inner header | `de57e37e4ac803b0f3a2eef1348747684b61e06fc5e00add9126ab4c543bfced` |
| 3 | `section_3_MAIN_OS.bin` | 2,221,632 B | `0x40000400` | `0x40000400` (entry `0x400004e8`) | `59278368fbe86c9877fad68a578987050e21fc4b418a289cfa1d1351d8e864ee` |
| 4 | `section_4_updater.raw` | 32,776 B | `0x80000400` | `0x80000400` (stored raw) | `21a27be7c0b3d3fc0049c6919f3264215f20c757ba3fb9d992e9f1bc0cfa29dc` |

`dst` is the load address the container declares, which is the number disassembly needs, with one
exception: section 2 runs from SRAM at `0x80000ec0` (the base at which its calls resolve), not at its
`dst`; ⚠️ `0x03000900` is read as a staging address. Its run base is word 2 (big-endian) of its
24-byte inner header; see `section_meta()` in `scripts/common.sh` for how each analysis base is
derived.

**MAIN OS addresses:** everything in this repo uses load addresses. For section 3,
file offset = load address − `0x40000400`.

**Match sections by id, not by name.** Newer upstream versions of the tool call section 2
"bootstrap" and so write `section_2_bootstrap.bin`. The scripts look for `section_2_*`,
`section_3_*` and so on, and so should anything you write.

## The round trip

`roundtrip.sh` writes `work/<image>/roundtrip/rt.syx` and its re-extract. On the stock file with the
pinned, capped tool:

- the container shrinks from 917,072 to 864,080 bytes and `rt.syx` is 1,095,200 bytes, because the
  tool's compressor packs tighter than Elektron's; the `.syx` files differ, so only the
  decompressed sections are compared;
- all four sections come back byte-identical.

A derived file next to the sections, such as the `section_2_DSP.from24.bin` that the Ghidra wrapper
creates, is skipped by the comparison.

## Checking a build

```sh
python3 build/verify.py out/dt_og_plus_plus_v0.1_<hash8>.syx --tool tool/bin/elektron-firmware-tool-capped
```

classifies the file (stock, DT OG++ reference build, or unknown); see [`building.md`](building.md).
To look inside a build with the scripts here:

```sh
./scripts/extract.sh out/dt_og_plus_plus_v0.1_<hash8>.syx      # -> work/dt_og_plus_plus_v0.1_<hash8>/
./scripts/roundtrip.sh out/dt_og_plus_plus_v0.1_<hash8>.syx
```

In the extract of a correct build, section 3 has the SHA-256 recorded in `build/patch.json`
(`result.section3_sha256`) whichever tool packed it, and sections 2, 4 and 5 are byte-identical to the
stock ones (`cmp` them against `work/dt_1.52A/`). The `.syx` hash recorded there holds only for the
pinned tool. The extracted section 3 is also what the
emulator harnesses read their pad bytes from (`scripts/emu/README.md`) and what
`./scripts/disasm.sh … main work/<build>/section_3_MAIN_OS.bin` disassembles.

## Housekeeping

`work/` is gitignored and can be deleted and regenerated at will. `sysex/` holds your only local copy
of the original: never delete or overwrite it.
