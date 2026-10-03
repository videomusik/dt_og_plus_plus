#!/usr/bin/env bash
# Shared definitions for the scripts in this folder. Source it; do not run it.
# Every script here works on files only. None of them talks to a device.
#
# Settings you can override from the environment (defaults in brackets):
#   STOCK_SYX            your stock OS file              [sysex/Digitakt_OS1.52A.syx]
#   FIRMWARE_TOOL        the firmware tool binary        [tool/bin/elektron-firmware-tool-capped]
#   M68K_PREFIX          m68k binutils command prefix    [m68k-elf-]  (Debian/Ubuntu: m68k-linux-gnu-)
#   GHIDRA_INSTALL_DIR   Ghidra install                  [/opt/homebrew/opt/ghidra/libexec]
#   JAVA_HOME            JDK 21 for Ghidra               [/opt/homebrew/opt/openjdk@21]
#   GHIDRA_LANG_VARIANT  alternate Ghidra language       [unset = stock ColdFire; emac = ColdFire+EMAC]
#   GHIDRA_PROJECT       use this folder under work/ghidra/ instead of the derived name [unset]

set -euo pipefail

# Repo root = the parent of this scripts/ folder. Every path below is relative to it.
CALLER_PWD="$(pwd)"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

# ---- the stock image ----------------------------------------------------------------------------
# Your own copy of Elektron's OS file. It is never committed (sysex/ is gitignored) and never shared.
# Every address in this repo is for exactly this file, so the scripts check its hash.
STOCK_SYX="${STOCK_SYX:-sysex/Digitakt_OS1.52A.syx}"
STOCK_SYX_SHA256="01315133041dcdb8b432146190cc74fc8695c47d8466b0f31bd78cef96fa56a4"
STOCK_SYX_SIZE=1162400
STOCK_MAIN_SHA256="59278368fbe86c9877fad68a578987050e21fc4b418a289cfa1d1351d8e864ee"  # section 3 (MAIN OS), decompressed
STOCK_MAIN_SIZE=2221632

# MAIN OS (section 3) load address. File offset = load address - 0x40000400.
MAIN_BASE=0x40000400

# ---- tools --------------------------------------------------------------------------------------
# The firmware tool, built by `bash build/build_tool.sh`: the pinned upstream commit plus the 1 MB
# compression-window patch (see docs/toolchain.md section 2).
TOOL_BIN="${FIRMWARE_TOOL:-tool/bin/elektron-firmware-tool-capped}"

# m68k binutils. Homebrew's m68k-elf-binutils names the commands m68k-elf-as, m68k-elf-objdump, ...;
# Debian/Ubuntu's binutils-m68k-linux-gnu names them m68k-linux-gnu-as, ... Any script that runs
# binutils must use these three variables, never a hard-coded command name.
M68K_PREFIX="${M68K_PREFIX:-m68k-elf-}"
M68K_AS="${M68K_PREFIX}as"
M68K_OBJCOPY="${M68K_PREFIX}objcopy"
M68K_OBJDUMP="${M68K_PREFIX}objdump"

# ---- images -------------------------------------------------------------------------------------
# An image argument is either the key `dt` (your stock Digitakt OS 1.52A) or a path to a .syx file,
# for example a build output in out/. A relative path is taken from the repo root when it exists
# there, else from the folder the script was started in. bash 3.2 (the macOS default) has no
# associative arrays, hence case.
image_syx() {
    case "$1" in
        dt)    echo "$STOCK_SYX" ;;
        /*.syx) echo "$1" ;;
        *.syx) if [ -f "$1" ]; then echo "$1"; else echo "$CALLER_PWD/$1"; fi ;;
        *)     echo "error: unknown image '$1' (use dt, or a path to a .syx file)" >&2; return 1 ;;
    esac
}
image_dir() {
    case "$1" in
        dt)    echo "work/dt_1.52A" ;;
        *.syx) echo "work/$(basename "$1" .syx)" ;;
        *)     echo "error: unknown image '$1' (use dt, or a path to a .syx file)" >&2; return 1 ;;
    esac
}
ALL_IMAGES="dt"

# ---- section files ------------------------------------------------------------------------------
# The pinned tool writes section_2_DSP.bin, section_3_MAIN_OS.bin, section_4_updater.raw and
# section_5_meta.raw. Match them by id prefix (section_3_*), never by the full name: a newer tool that
# renames a section (upstream now calls section 2 "bootstrap") must not break anything here.
# Derived copies such as section_2_DSP.from24.bin (header stripped for Ghidra) are skipped.
section_file() {   # args: <dir> <id>  -> echoes the one extracted section_<id>_* file in <dir>
    local f found=""
    for f in "$1"/section_"$2"_*; do
        [ -f "$f" ] || continue
        case "$f" in *.from*.bin) continue ;; esac
        if [ -n "$found" ]; then echo "error: more than one section_$2_* file in $1" >&2; return 1; fi
        found="$f"
    done
    if [ -z "$found" ]; then echo "error: no section_$2_* file in $1 (run ./scripts/extract.sh first)" >&2; return 1; fi
    echo "$found"
}

# ---- Ghidra section metadata --------------------------------------------------------------------
# The sections load at different addresses. The DSP section also carries a 24-byte inner header that
# must be stripped, and it runs at an address that is NOT its container `dst` (read as a staging
# address, not settled). So analysis is keyed by section, each resolving to four fields:
#   <section id, or a file name>  <load base>  <strip bytes>  <entry, or ->
#
# Where the numbers come from (Digitakt OS 1.52A):
#   main    : container dst 0x40000400; entry = first header word 0x400004e8; no strip.
#   dsp     : run base = word 2 of the section's 24-byte inner header = 0x80000ec0 (NOT the container
#             dst 0x03000900); strip the 24-byte header; no simple entry (it starts mid-stream).
#   updater : container dst 0x80000400; entry 0x80000492; no strip.
#   sram    : not a section but the assembled 64 KB on-chip SRAM image written by
#             scripts/build_sram_image.py; base 0x80000000; entry = the DSP's run base 0x80000ec0.
#
# How to derive a row from generated data, without guessing:
#   base  = the section's dst= in work/<image>/report.txt (the extract.sh -v output). For a blob that is
#           copied elsewhere to run (the DSP), dst is read as a staging address; the real run base is
#           word 2 (big-endian) of its inner header:  xxd -l 24 work/dt_1.52A/section_2_*.bin
#   strip = the size of that inner header (24 B for the DSP); 0 when the section runs where it loads.
#   entry = the section's first 32-bit word (xxd -l 4 ...) for main and updater; '-' when there is none.
# All images here are Digitakt OS 1.52A or a build made from it, so the image argument does not change
# the layout; it is kept so the call sites read naturally.
section_meta() {   # args: <image> <section>  -> echoes: src base strip entry
    case "$2" in
        main)    echo "3 0x40000400 0 0x400004e8" ;;
        dsp)     echo "2 0x80000ec0 24 -" ;;
        updater) echo "4 0x80000400 0 0x80000492" ;;
        sram)    echo "sram_unified.bin 0x80000000 0 0x80000ec0" ;;
        *) echo "error: no analysis metadata for section '$2' (use main, dsp, updater or sram)" >&2; return 1 ;;
    esac
}
ALL_SECTIONS="main dsp updater"

section_path() {   # args: <image dir> <image> <section>  -> the extracted file that section comes from
    local meta src b s e
    meta="$(section_meta "$2" "$3")" || return 1
    read -r src b s e <<<"$meta"
    case "$src" in
        [0-9]) section_file "$1" "$src" ;;
        *)     echo "$1/$src" ;;
    esac
}

# The Ghidra program name (= basename of the imported file). A stripped section is imported as
# <name>.from<N>.bin, so decompile's and query's -process must use that same name.
section_progname() {   # args: <image dir> <image> <section>
    local meta src b s e p f
    meta="$(section_meta "$2" "$3")" || return 1
    read -r src b s e <<<"$meta"
    p="$(section_path "$1" "$2" "$3")" || return 1
    f="$(basename "$p")"
    if [ "$s" -gt 0 ]; then echo "${f%.bin}.from${s}.bin"; else echo "$f"; fi
}

# Ghidra project/output suffix for a section: 'main' keeps the bare image name, other sections append
# _<section>, and GHIDRA_LANG_VARIANT appends a further _<variant>, so an alternate-language analysis
# lands in its own project and output folder and the stock one stays intact for comparison.
ghidra_tag() {
    local t=""; [ "$1" = main ] || t="_$1"
    echo "${t}${GHIDRA_LANG_VARIANT:+_$GHIDRA_LANG_VARIANT}"
}

# The folder name under work/ghidra/ (and work/ghidra/out/). GHIDRA_PROJECT overrides the derived name,
# which is how the copied and specially built projects (dt_1.52A_seed, dt_1.52A_sram) are addressed.
ghidra_projkey() {   # $1 = image dir, $2 = section
    if [ -n "${GHIDRA_PROJECT:-}" ]; then echo "$GHIDRA_PROJECT"; else echo "$(basename "$1")$(ghidra_tag "$2")"; fi
}
ghidra_projdir() { echo "work/ghidra/$(ghidra_projkey "$1" "$2")"; }
ghidra_outdir()  { echo "work/ghidra/out/$(ghidra_projkey "$1" "$2")"; }
# The project name = the .gpr file inside the folder when it exists (a copied project keeps the name it
# was created with), else the folder name with every character outside [A-Za-z0-9_] turned into '_'.
ghidra_projname() {
    local d g n
    d="$(ghidra_projdir "$1" "$2")"
    for g in "$d"/*.gpr; do
        if [ -f "$g" ]; then basename "$g" .gpr; return 0; fi
    done
    n="$(ghidra_projkey "$1" "$2")"
    echo "${n//[^A-Za-z0-9_]/_}"
}

# Which Ghidra processor language to import with. Default = Ghidra's stock ColdFire.
# GHIDRA_LANG_VARIANT=emac selects the patched language from scripts/ghidra_ext/ (install it first with
# ./scripts/ghidra_lang_ext.sh), which decodes `movclr.l ACCx,Rx`, the mac.l-with-load form whose Rw
# is an address register, and the (d16,An) forms of mac.l/msac.l; without them most MAC audio loops
# are cut short.
# A program is bound to the language it was imported with, so switching variants means a fresh import;
# ghidra_tag above keeps the two analyses side by side instead of overwriting one with the other.
ghidra_langid() {
    case "${GHIDRA_LANG_VARIANT:-}" in
        "")   echo "68000:BE:32:Coldfire" ;;
        emac) echo "68000:BE:32:ColdfireEMAC" ;;
        *)    echo "error: unknown GHIDRA_LANG_VARIANT '$GHIDRA_LANG_VARIANT' (use 'emac', or leave it unset)" >&2; return 1 ;;
    esac
}

# Locate headless Ghidra and its JDK. Sets HEADLESS.
ghidra_env() {
    : "${GHIDRA_INSTALL_DIR:=/opt/homebrew/opt/ghidra/libexec}"
    : "${JAVA_HOME:=/opt/homebrew/opt/openjdk@21}"
    export GHIDRA_INSTALL_DIR JAVA_HOME
    HEADLESS="$GHIDRA_INSTALL_DIR/support/analyzeHeadless"
    [ -x "$HEADLESS" ]  || { echo "error: $HEADLESS not found; set GHIDRA_INSTALL_DIR (docs/toolchain.md section 4)" >&2; exit 1; }
    [ -d "$JAVA_HOME" ] || { echo "error: JAVA_HOME=$JAVA_HOME not found; set it to a JDK 21 (docs/toolchain.md section 4)" >&2; exit 1; }
}

# ---- checks -------------------------------------------------------------------------------------
require_tool() {
    if [ ! -x "$TOOL_BIN" ]; then
        echo "error: $TOOL_BIN not found; build it with: bash build/build_tool.sh (or set FIRMWARE_TOOL)" >&2
        exit 1
    fi
}

require_syx() {
    if [ ! -f "$1" ]; then
        echo "error: $1 not found. Put your own stock file at $STOCK_SYX (see docs/toolchain.md section 1)" >&2
        exit 1
    fi
}

sha256_of() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | cut -d' ' -f1
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | cut -d' ' -f1
    else
        python3 -c 'import hashlib, sys; print(hashlib.sha256(open(sys.argv[1], "rb").read()).hexdigest())' "$1"
    fi
}

size_of() { wc -c < "$1" | tr -d ' '; }

# For the key `dt`: refuse anything that is not byte-for-byte the stock Digitakt OS 1.52A file.
# For a path: just report its hash (build outputs and other files are checked by the build itself).
check_image() {   # args: <image> <syx path>
    local h
    require_syx "$2"
    h="$(sha256_of "$2")"
    if [ "$1" = dt ]; then
        if [ "$h" != "$STOCK_SYX_SHA256" ]; then
            echo "error: $2 is not the stock Digitakt OS 1.52A file" >&2
            echo "       sha256 $h ($(size_of "$2") bytes)" >&2
            echo "       want   $STOCK_SYX_SHA256 ($STOCK_SYX_SIZE bytes)" >&2
            exit 1
        fi
        echo "stock file ok: $2 (sha256 ${h:0:16}...)"
    else
        echo "file: $2 (sha256 $h)"
    fi
}

banner() { printf '\n==== %s ====\n' "$*"; }
