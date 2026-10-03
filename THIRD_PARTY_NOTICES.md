# Third-party notices

This repository builds on two third-party projects and on Elektron's own files. This page says what
comes from where and under which terms. The repository's own content is dedicated to the public
domain under CC0 1.0 Universal ([LICENSE](LICENSE)); the parts named below keep their own terms.

## elektron-firmware-tool (MIT License)

- **Upstream:** https://github.com/mischa85/elektron-firmware-tool
- **How it is used:** you clone the tool yourself, next to this repository, and check out commit
  `065d18f4195793e61891e387813488ee59f6d1ca`. `build/build_tool.sh` copies its source files into a
  build folder under `tool/`, checks their SHA-256, applies `build/tool_patches/cap_window_1mb.patch`
  there and compiles the tool on your computer. It never changes your clone. The tool is not bundled
  with this repository, and nothing in this repository downloads it.
- **What this repository contains of it:** `build/tool_patches/cap_window_1mb.patch` contains context
  lines from the tool's source. Those lines are covered by the tool's licence, not by CC0. The licence
  follows exactly as in the tool's `LICENSE` file.
- The tool's README asks its users not to redistribute original or modified Elektron firmware images.

```
MIT License

Copyright (c) 2026 Marcel Bierling

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Ghidra (Apache License 2.0)

- **Upstream:** Ghidra, developed by the National Security Agency,
  https://github.com/NationalSecurityAgency/ghidra
- **What this repository contains of it:** `scripts/ghidra_ext/` is derived from the SLEIGH files of
  Ghidra 12.1.3's Motorola 68000 processor module (`68000.sinc`, `coldfire.slaspec` and
  `68000.ldefs`), which is distributed under the Apache License, Version 2.0. That folder is
  distributed under the Apache License 2.0, not under the CC0 dedication that covers the rest of this
  repository.
- Which file is derived from which, and what was changed:
  [scripts/ghidra_ext/NOTICE.md](scripts/ghidra_ext/NOTICE.md). The licence text:
  [scripts/ghidra_ext/LICENSE-Apache-2.0.txt](scripts/ghidra_ext/LICENSE-Apache-2.0.txt).
- Ghidra itself is not included. `scripts/ghidra_lang_ext.sh` copies the other files the extension
  needs out of your own Ghidra install at build time. The Ghidra scripts in `scripts/ghidra/` and
  `scripts/emu/` call Ghidra's scripting API and contain no Ghidra source code.

## Elektron

- The Digitakt firmware and the Digitakt manual are Elektron's. Neither is included in this
  repository: no firmware file, no extracted section, no manual text and no manual figure. You bring
  your own copy of each, the stock OS 1.52A update file for the build and the manual PDF for the
  manual pipeline. Your copies, and what the tools make from them, stay in the git-ignored
  folders `sysex/`, `manuals/`, `out/` and `work/`, and must not be shared.

## Tools used but not included

Python, a C compiler, `patch`, git, GNU binutils for ColdFire (optional), Ghidra and OpenJDK
(analysis only), and Swift with the macOS PDF frameworks (manual pipeline only) are used as installed
on your computer. None of them is part of this repository.
