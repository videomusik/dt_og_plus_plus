#!/usr/bin/env bash
# Disassemble an address range with GNU binutils, the independent second decoder next to Ghidra.
#
#   ./scripts/disasm.sh <image> <start> <stop> [section=main] [file]
#   ./scripts/disasm.sh <os> <start> <stop>                         # the OS folder's stock MAIN OS
#   ./scripts/disasm.sh <os> <start> <stop> dsp                     # the DSP section, at its run address
#   ./scripts/disasm.sh <os>:out/<os>/<build>.syx <start> <stop>    # a build (./scripts/extract.sh it first)
#   ./scripts/disasm.sh <os> <start> <stop> main work/dt_<os>-<build>/section_3_MAIN_OS.bin
#
# Worked examples with real addresses: os/<os>/notes/analysis_reference.md
#
# Addresses are load addresses. The file defaults to the section extracted from the image
# (./scripts/extract.sh <image> first); the load base and header strip come from os_section_meta in
# os/<os>/profile.sh (section_meta in common.sh). A file you name must lie outside work/ or in a work
# folder of the same OS (work/dt_<os>/ or work/dt_<os>-<build>/).
# Uses $M68K_PREFIX (m68k-elf- by default; m68k-linux-gnu- on Debian/Ubuntu).
#
# Disassembly of a raw section is linear, so it is only right from a known instruction boundary: start
# at an address Ghidra already reached, or at a function entry.
# -m m68k:cfv4e is the MCF5441x variant and decodes ColdFire EMAC in full; plain m68k:cfv4 does not.
# Reading tip: objdump prints `remsl Dx,Dx,Dx` (same register twice) for what is really a signed 32-bit
# divide (divs.l); the dump never shows a divsl mnemonic at all.

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

usage="usage: $0 <image> <start> <stop> [section=main] [file]   (<image> = <os> or <os>:<file.syx>)"
if [ $# -lt 3 ] || [ $# -gt 5 ]; then echo "$usage" >&2; os_usage_list; exit 2; fi
image_parse "$1"
start="$2"; stop="$3"
section="${4:-main}"
meta="$(section_meta "$IMG_ARG" "$section")" || exit 1
read -r src base strip entry <<<"$meta"
if [ -n "${5:-}" ]; then
    work_path_guard "$5"
    file="$5"
    case "$file" in /*) ;; *) [ -f "$file" ] || file="$CALLER_PWD/$file" ;; esac
else
    file="$(section_path "$IMG_DIR" "$IMG_ARG" "$section")" || exit 1
fi
[ -f "$file" ] || { echo "error: $file not found" >&2; exit 1; }
command -v "$M68K_OBJDUMP" >/dev/null 2>&1 \
    || { echo "error: $M68K_OBJDUMP not found; install m68k binutils or set M68K_PREFIX (docs/toolchain.md section 5)" >&2; exit 1; }

# File offset 0 of an unstripped section sits <strip> bytes BEFORE the run base.
vma=$(( base - strip ))
"$M68K_OBJDUMP" -D -b binary -m m68k:cfv4e --adjust-vma="$vma" \
    --start-address="$start" --stop-address="$stop" "$file"
