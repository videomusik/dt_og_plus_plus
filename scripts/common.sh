#!/usr/bin/env bash
# Shared definitions for the scripts in this folder. Source it; do not run it.
# Every script here works on files only. None of them talks to a device.
#
# Settings you can override from the environment (defaults in brackets):
#   STOCK_SYX            override for the selected OS's stock file; must hash to that OS's stock
#                        SHA-256 (os/<os>/profile.sh)
#   FIRMWARE_TOOL        the firmware tool binary        [tool/bin/elektron-firmware-tool-capped]
#   M68K_PREFIX          m68k binutils command prefix    [m68k-elf-]  (Debian/Ubuntu: m68k-linux-gnu-)
#   GHIDRA_INSTALL_DIR   Ghidra install                  [/opt/homebrew/opt/ghidra/libexec]
#   JAVA_HOME            JDK 21 for Ghidra               [/opt/homebrew/opt/openjdk@21]
#   GHIDRA_LANG_VARIANT  alternate Ghidra language       [unset = stock ColdFire; emac = ColdFire+EMAC]
#   GHIDRA_PROJECT       use this folder under work/ghidra/ instead of the derived name [unset]; it
#                        must be the image's project name, or start with it plus _
# No setting selects an OS. Every image argument names its OS folder (see "images" below).
#
# bash 3.2 (the macOS default) runs these scripts: no associative arrays, no ${x,,}, no mapfile.
# The functions that set variables for their caller or exit on an error (os_load, image_parse,
# check_image, ghidra_check_project, ghidra_find_script, work_path_guard) run in the main shell, never
# inside $( ). A function used inside $( ) only echoes, and its caller adds '|| exit 1'.

set -euo pipefail

# Repo root = the parent of this scripts/ folder. Every path below is relative to it.
CALLER_PWD="$(pwd)"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

# A run starts with no OS loaded: nothing from the caller's environment can stand in for a profile.
unset OS_ID OS_LABEL OS_REPORTED_VERSION OS_STOCK_SYX_DEFAULT OS_STOCK_SYX_SHA256 OS_STOCK_SYX_SIZE OS_CONTAINER_SECTION_IDS OS_MAIN_ID OS_MAIN_BASE OS_STOCK_MAIN_SHA256 OS_STOCK_MAIN_SIZE OS_ANALYSIS_SECTIONS OS_SIGNATURE_TRAILER IMG_ARG IMG_KIND IMG_SYX IMG_BASE IMG_DIR
unset -f os_section_meta 2>/dev/null || true

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

# ---- OS folders ---------------------------------------------------------------------------------
# Everything that belongs to one firmware version lives in its OS folder, os/<os>/ (os/README.md).
# Its profile, os/<os>/profile.sh, holds the stock file's identity and the section layout: it defines
# the variables below and the function os_section_meta, and runs nothing. One run loads one OS.
PROFILE_VARS="OS_ID OS_LABEL OS_REPORTED_VERSION OS_STOCK_SYX_DEFAULT OS_STOCK_SYX_SHA256 OS_STOCK_SYX_SIZE OS_CONTAINER_SECTION_IDS OS_MAIN_ID OS_MAIN_BASE OS_STOCK_MAIN_SHA256 OS_STOCK_MAIN_SIZE OS_ANALYSIS_SECTIONS OS_SIGNATURE_TRAILER"
# Characters allowed in an OS id, a Ghidra script name and a project name, spelled out because a range
# such as A-Z in a pattern depends on the locale in bash 3.2.
ID_CHARS="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

os_list() {   # -> echoes one OS id per line, for each os/<id>/profile.sh (folders starting with . or _ skipped)
    local p d
    for p in os/*/profile.sh; do
        [ -f "$p" ] || continue
        d="${p#os/}"; d="${d%/profile.sh}"
        case "$d" in .*|_*) continue ;; esac
        echo "$d"
    done
    return 0
}

os_usage_list() {   # prints 'OS folders: <ids>' to stderr, for usage and error messages
    local ids
    ids="$(os_list | tr '\n' ' ')" || exit 1
    ids="${ids% }"
    echo "OS folders: ${ids:-(none)}" >&2
}

os_load() {   # args: <os id>. Sources os/<id>/profile.sh, once per run; exits on any problem.
    local id="${1:-}" v
    case "$id" in
        ""|.*|*[!${ID_CHARS}.]*)
            echo "error: '$id' is not an OS id (letters, digits and '.', not starting with '.')" >&2
            os_usage_list; exit 2 ;;
    esac
    if [ ! -f "os/$id/profile.sh" ]; then
        echo "error: no OS folder os/$id/" >&2
        os_usage_list; exit 2
    fi
    if [ -n "${OS_ID:-}" ]; then
        [ "$OS_ID" = "$id" ] && return 0
        echo "error: one OS per run: this run already uses $OS_ID" >&2
        exit 2
    fi
    # shellcheck source=/dev/null
    . "os/$id/profile.sh"
    if [ "${OS_ID:-}" != "$id" ]; then
        echo "error: os/$id/profile.sh sets OS_ID '${OS_ID:-}', not '$id'" >&2; exit 2
    fi
    for v in $PROFILE_VARS; do
        [ -n "${!v:-}" ] || { echo "error: os/$id/profile.sh does not set $v" >&2; exit 2; }
    done
    declare -F os_section_meta >/dev/null \
        || { echo "error: os/$id/profile.sh does not define os_section_meta" >&2; exit 2; }
}

# ---- images -------------------------------------------------------------------------------------
# An image argument names its OS folder, in one of two forms:
#   <os>             that OS's stock file: $STOCK_SYX, default OS_STOCK_SYX_DEFAULT from its profile.
#                    Hash-checked. Work folder work/dt_<os>/.
#   <os>:<file.syx>  any other file of that OS, for example a build output in out/<os>/. Work folder
#                    work/dt_<os>-<file name without .syx>/. Refused if it is that OS's stock file, and
#                    unless the tool reports the OS's version for it.
# The argument is split at the first ':', so the path may itself contain ':'. A relative path is taken
# from the repo root when it exists there, else from the folder the script was started in. The old key
# dt and a bare path without <os>: are refused. See os/README.md, Choosing the OS on the command line.
image_parse() {   # args: <image>. Loads its OS and sets IMG_ARG IMG_KIND IMG_SYX IMG_BASE IMG_DIR; opens no file.
    local arg="${1:-}" os path name
    IMG_ARG="$arg"
    case "$arg" in
        dt)
            echo "error: the image key dt is gone; name the OS folder, e.g. 1.52A" >&2
            os_usage_list; exit 2 ;;
        *:*)
            os="${arg%%:*}"; path="${arg#*:}"
            os_load "$os"
            name="${path##*/}"
            case "$name" in
                ?*.syx) name="${name%.syx}" ;;
                *) echo "error: '$path' is not a .syx file (use <os>:<path>.syx)" >&2; exit 2 ;;
            esac
            case "$path" in
                /*) IMG_SYX="$path" ;;
                *)  if [ -f "$path" ]; then IMG_SYX="$path"; else IMG_SYX="$CALLER_PWD/$path"; fi ;;
            esac
            IMG_KIND=file
            IMG_BASE="dt_${OS_ID}-$name" ;;
        *.syx|*/*)
            echo "error: say which OS this file belongs to: <os>:$arg" >&2
            os_usage_list; exit 2 ;;
        *)
            os_load "$arg"
            IMG_KIND=stock
            IMG_SYX="${STOCK_SYX:-$OS_STOCK_SYX_DEFAULT}"
            IMG_BASE="dt_${OS_ID}" ;;
    esac
    IMG_DIR="work/$IMG_BASE"
}

# ---- section files ------------------------------------------------------------------------------
# The pinned tool writes one file per section, section_<id>_<NAME>.bin or .raw (for the OS 1.52A image:
# section_2_DSP.bin, section_3_MAIN_OS.bin, section_4_updater.raw and section_5_meta.raw). Match them by
# id prefix (section_<id>_*), never by the full name: a newer tool that renames a section (upstream now
# calls the OS 1.52A section 2 "bootstrap") must not break anything here. Derived copies named
# *.from<N>.bin (an inner header stripped for Ghidra) are skipped.
section_file() {   # args: <dir> <id>  -> echoes the one extracted section_<id>_* file in <dir>
    local f found=""
    for f in "$1"/section_"$2"_*; do
        [ -f "$f" ] || continue
        case "$f" in *.from*.bin) continue ;; esac
        if [ -n "$found" ]; then echo "error: more than one section_$2_* file in $1" >&2; return 1; fi
        found="$f"
    done
    if [ -z "$found" ]; then echo "error: no section_$2_* file in $1 (run ./scripts/extract.sh ${IMG_ARG:-<image>} first)" >&2; return 1; fi
    echo "$found"
}

# ---- Ghidra section metadata --------------------------------------------------------------------
# The sections load at different addresses. The DSP section (in OS 1.52A) also carries a 24-byte inner
# header that must be stripped, and it runs at an address that is NOT its container `dst` (read as a
# staging address, not settled). So analysis is keyed by section, each resolving to four fields:
#   <section id, or a file name>  <load base>  <strip bytes>  <entry, or ->
#
# How to derive a row from generated data, without guessing:
#   base  = the section's dst= in work/dt_<os>/report.txt (the extract.sh -v output). For a blob that is
#           copied elsewhere to run, dst is read as a staging address, and the real run base is the one
#           its own header names (OS 1.52A: the DSP, section 2, word 2 (big-endian) of its 24-byte inner
#           header:  xxd -l 24 work/dt_1.52A/section_2_*.bin).
#   strip = the size of such an inner header (OS 1.52A: 24 B for the DSP); 0 when the section runs where
#           it loads.
#   entry = the entry word of the section's header where it has one (OS 1.52A: the first 32-bit word,
#           xxd -l 4 ..., for main and updater); '-' when there is none.
# Each OS folder's rows are os_section_meta in os/<os>/profile.sh. The layout comes from the OS that
# the image names; the image argument is kept so the call sites read naturally.
section_meta() {   # args: <image> <section>  -> echoes: src base strip entry
    declare -F os_section_meta >/dev/null || { echo "error: no OS loaded (image_parse first)" >&2; return 1; }
    os_section_meta "$2"
}

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
# which is how the copied and specially built projects (dt_<os>_seed, dt_<os>_sram) are addressed;
# ghidra_check_project ties it to the image first.
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

# GHIDRA_PROJECT, when set, must belong to the parsed image: the image's project name (dt_<os>, or
# dt_<os>-<build> for a file image) or that name followed by _ and a suffix, so a run on one OS or one
# build never opens another's project.
ghidra_check_project() {   # no args; uses IMG_ARG and IMG_BASE. Exits 2 on a mismatch.
    [ -n "${GHIDRA_PROJECT:-}" ] || return 0
    case "$GHIDRA_PROJECT" in
        *[!${ID_CHARS}._-]*) ;;
        "$IMG_BASE"|"$IMG_BASE"_*) return 0 ;;
    esac
    echo "error: GHIDRA_PROJECT=$GHIDRA_PROJECT does not belong to image $IMG_ARG; use $IMG_BASE or ${IMG_BASE}_<suffix>" >&2
    exit 2
}

# Ghidra scripts: the shared ones in scripts/ghidra/, and an OS folder's own (scripts that carry one
# OS's tables) in os/<os>/scripts/ghidra/. Both folders go on -scriptPath, absolute and separated by
# ';' (analyzeHeadless's separator); a name in both folders, or in neither, is refused before Ghidra
# starts.
ghidra_script_path() {   # -> echoes the -scriptPath value for the loaded OS
    local p="$REPO_ROOT/scripts/ghidra"
    if [ -d "$REPO_ROOT/os/$OS_ID/scripts/ghidra" ]; then p="$p;$REPO_ROOT/os/$OS_ID/scripts/ghidra"; fi
    echo "$p"
}

ghidra_find_script() {   # args: <Script name, without .java>. Exits 2 unless exactly one folder has it.
    local name="${1:-}" shared own
    case "$name" in
        ""|*[!${ID_CHARS}_]*) echo "error: no script $name for OS $OS_ID" >&2; exit 2 ;;
    esac
    shared="scripts/ghidra/$name.java"
    own="os/$OS_ID/scripts/ghidra/$name.java"
    if [ -f "$shared" ] && [ -f "$own" ]; then
        echo "error: $name exists in scripts/ghidra/ and os/$OS_ID/scripts/ghidra/; refusing" >&2; exit 2
    fi
    if [ ! -f "$shared" ] && [ ! -f "$own" ]; then
        echo "error: no script $name for OS $OS_ID" >&2; exit 2
    fi
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
        if [ "${IMG_KIND:-}" = file ]; then
            echo "error: $1 not found" >&2
        else
            echo "error: $1 not found. Put your own stock file at $OS_STOCK_SYX_DEFAULT (os/$OS_ID/docs/reference.md, The stock file)" >&2
        fi
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

# For a stock image (<os>): refuse anything that is not byte-for-byte that OS's stock file.
# For a file image (<os>:<file.syx>): refuse the stock file itself (use the key <os>), and refuse a file
# whose version, as the tool reports it, is not the OS's. Build outputs are classified by
# os/<os>/build/verify.py; here the file's hash is reported.
check_image() {   # no args; checks the image image_parse set up. Exits on any problem.
    local h info ver
    require_syx "$IMG_SYX"
    h="$(sha256_of "$IMG_SYX")" || exit 1
    case "${IMG_KIND:-}" in
        stock)
            if [ "$h" != "$OS_STOCK_SYX_SHA256" ]; then
                echo "error: $IMG_SYX is not the stock $OS_LABEL file" >&2
                echo "       sha256 $h ($(size_of "$IMG_SYX") bytes)" >&2
                echo "       want   $OS_STOCK_SYX_SHA256 ($OS_STOCK_SYX_SIZE bytes)" >&2
                exit 1
            fi
            echo "stock file ok: $IMG_SYX (sha256 ${h:0:16}...)" ;;
        file)
            if [ "$h" = "$OS_STOCK_SYX_SHA256" ]; then
                echo "error: $IMG_SYX: this is the stock $OS_LABEL file; use the key $OS_ID" >&2
                exit 1
            fi
            require_tool
            info="$("$TOOL_BIN" -i "$IMG_SYX" 2>&1)" \
                || { echo "error: the tool cannot read $IMG_SYX:" >&2; printf '%s\n' "$info" >&2; exit 1; }
            ver="$(printf '%s\n' "$info" | grep -E '^[[:space:]]*version[[:space:]]*:' | sed -e 's/^[^:]*:[[:space:]]*//' -e 's/[[:space:]]*$//')" || ver=""
            if [ "$ver" != "$OS_REPORTED_VERSION" ]; then
                echo "error: the tool reports version ${ver:-(none)}, not $OS_REPORTED_VERSION; this file does not belong to os/$OS_ID/" >&2
                exit 1
            fi
            echo "file: $IMG_SYX (sha256 $h), OS $OS_ID" ;;
        *)
            echo "error: check_image: no image parsed" >&2; exit 1 ;;
    esac
}

# A file argument under work/ must lie in a work folder of the loaded OS (work/dt_<os>/ or
# work/dt_<os>-<build>/), so a run on one OS never reads another's extract. The path is made absolute
# as ghidra_emu.sh does for harness arguments (from the repo root when it exists there, else from the
# folder the script was started in); a path that exists in neither place is checked both ways. This
# runs before any existence check.
path_norm() {   # args: <absolute path>  -> echoes it without '.', '..' and repeated '/' (touches no file)
    local rest="$1" part out=""
    while [ -n "$rest" ]; do
        part="${rest%%/*}"
        if [ "$part" = "$rest" ]; then rest=""; else rest="${rest#*/}"; fi
        case "$part" in
            ""|.) ;;
            ..)   out="${out%/*}" ;;
            *)    out="$out/$part" ;;
        esac
    done
    echo "${out:-/}"
}

work_path_guard() {   # args: <path>. Exits 2 when it lies under work/ but outside the loaded OS's folders.
    local p c n cands=()
    case "$1" in
        /*) cands=( "$1" ) ;;
        *)  if [ -e "$1" ]; then cands=( "$REPO_ROOT/$1" )
            elif [ -e "$CALLER_PWD/$1" ]; then cands=( "$CALLER_PWD/$1" )
            else cands=( "$REPO_ROOT/$1" "$CALLER_PWD/$1" ); fi ;;
    esac
    for p in "${cands[@]}"; do
        n="$(path_norm "$p")" || exit 1
        case "$n" in
            "$REPO_ROOT"/work/*)
                c=${n#"$REPO_ROOT"/work/}; c="${c%%/*}"
                case "$c" in
                    "dt_$OS_ID"|"dt_$OS_ID"-*) ;;
                    *) echo "error: refusing $1: work/$c/ is not a work folder of OS $OS_ID" >&2; exit 2 ;;
                esac ;;
        esac
    done
}

banner() { printf '\n==== %s ====\n' "$*"; }
