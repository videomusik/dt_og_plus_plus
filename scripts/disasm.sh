#!/usr/bin/env bash
# Disassemble an address range with GNU binutils, the independent second decoder next to Ghidra.
#
#   ./scripts/disasm.sh <start> <stop> [section=main] [file]
#   ./scripts/disasm.sh 0x4006a570 0x4006a590                   # stock MAIN OS
#   ./scripts/disasm.sh 0x80001780 0x800017c0 dsp               # the DSP section, at its run address
#   ./scripts/disasm.sh 0x400b23b0 0x400b2410 main work/<build>/section_3_MAIN_OS.bin   # a build output
#
# Addresses are load addresses. The file defaults to the section extracted from your stock image
# (./scripts/extract.sh first); the load base and header strip come from section_meta() in common.sh.
# Uses $M68K_PREFIX (m68k-elf- by default; m68k-linux-gnu- on Debian/Ubuntu).
#
# Disassembly of a raw section is linear, so it is only right from a known instruction boundary: start
# at an address Ghidra already reached, or at a function entry.
# -m m68k:cfv4e is the MCF5441x variant and decodes ColdFire EMAC in full; plain m68k:cfv4 does not.
# Reading tip: objdump prints `remsl Dx,Dx,Dx` (same register twice) for what is really a signed 32-bit
# divide (divs.l); the dump never shows a divsl mnemonic at all.

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

usage="usage: $0 <start> <stop> [section=main|dsp|updater|sram] [file]"
start="${1:?$usage}"; stop="${2:?$usage}"
section="${3:-main}"
meta="$(section_meta dt "$section")" || exit 1
read -r src base strip entry <<<"$meta"
if [ -n "${4:-}" ]; then
    file="$4"
    case "$file" in /*) ;; *) [ -f "$file" ] || file="$CALLER_PWD/$file" ;; esac
else
    file="$(section_path "$(image_dir dt)" dt "$section")" || exit 1
fi
[ -f "$file" ] || { echo "error: $file not found" >&2; exit 1; }
command -v "$M68K_OBJDUMP" >/dev/null 2>&1 \
    || { echo "error: $M68K_OBJDUMP not found; install m68k binutils or set M68K_PREFIX (docs/toolchain.md section 5)" >&2; exit 1; }

# File offset 0 of an unstripped section sits <strip> bytes BEFORE the run base.
vma=$(( base - strip ))
"$M68K_OBJDUMP" -D -b binary -m m68k:cfv4e --adjust-vma="$vma" \
    --start-address="$start" --stop-address="$stop" "$file"
