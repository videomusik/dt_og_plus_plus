#!/usr/bin/env bash
# Run one emulator harness from os/<os>/scripts/emu/ headless against that OS's analysed MAIN OS
# project, and show its report. The harnesses use Ghidra's p-code emulator (EmulatorHelper). Nothing
# leaves the computer, and the project is opened read-only, so nothing in it changes.
#
#   ./scripts/ghidra_emu.sh <os> <Harness> [harness args...]
#   ./scripts/ghidra_emu.sh <os> <Harness>                                          # pad bytes built in
#   ./scripts/ghidra_emu.sh <os> <Harness> work/dt_<os>-<build>/section_3_MAIN_OS.bin  # bytes from a build
#
# The first argument is an OS id, never a file: a harness steps that OS's stock project, and a build's
# bytes come in as a harness argument (its extracted section file, ./scripts/extract.sh
# <os>:out/<os>/<build>.syx first). A harness argument that names an existing file under work/ must lie
# in a work folder of the same OS (work/dt_<os>/ or work/dt_<os>-<build>/).
#
# Default project: the EMAC one (GHIDRA_LANG_VARIANT=emac ./scripts/ghidra_analyze.sh <os> main first).
# Set GHIDRA_PROJECT to use another project of the same image, e.g. GHIDRA_PROJECT=dt_<os>_seed. What
# each harness checks, and which arguments it takes, is in os/<os>/scripts/emu/README.md.
# The full headless log is kept in work/ghidra/out/<project>/emu/<Harness>.log.
# Close the Ghidra GUI on this project first: a project is locked while it is open.

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

usage="usage: $0 <os> <Harness> [harness args...]  (see os/<os>/scripts/emu/README.md)"
if [ $# -lt 2 ]; then echo "$usage" >&2; os_usage_list; exit 2; fi
case "$1" in
    *:*|*/*|*.syx)
        echo "error: ghidra_emu.sh takes an OS id; a harness steps that OS's stock project; pass a build's section file as a harness argument" >&2
        exit 2 ;;
esac
image_parse "$1"
harness="$2"; shift 2
case "$harness" in
    ""|*[!${ID_CHARS}_]*) echo "error: no harness $harness for OS $OS_ID" >&2; exit 2 ;;
esac
[ -f "$REPO_ROOT/os/$OS_ID/scripts/emu/$harness.java" ] \
    || { echo "error: no os/$OS_ID/scripts/emu/$harness.java" >&2; exit 2; }

: "${GHIDRA_LANG_VARIANT=emac}"      # default to the EMAC project; an explicit empty value picks stock
export GHIDRA_LANG_VARIANT
ghidra_check_project

# Harness arguments that name files are made absolute, because Ghidra resolves a relative path from
# its own working directory, not from the repo root. One that names an existing path must pass
# work_path_guard.
args=()
for a in "$@"; do
    case "$a" in
        /*) p="$a" ;;
        *)  if [ -e "$a" ]; then p="$REPO_ROOT/$a"
            elif [ -e "$CALLER_PWD/$a" ]; then p="$CALLER_PWD/$a"
            else p="$a"; fi ;;
    esac
    if [ -e "$p" ]; then work_path_guard "$p"; fi
    args+=( "$p" )
done

ghidra_env
dir="$IMG_DIR"
bin="$(section_progname "$dir" "$IMG_ARG" main)" || exit 1
proj_dir="$(ghidra_projdir "$dir" main)" || exit 1
proj_name="$(ghidra_projname "$dir" main)" || exit 1
out_dir="$(ghidra_outdir "$dir" main)/emu" || exit 1
[ -f "$proj_dir/$proj_name.gpr" ] || { echo "error: no Ghidra project at $proj_dir; run GHIDRA_LANG_VARIANT=emac ./scripts/ghidra_analyze.sh $OS_ID main" >&2; exit 1; }
mkdir -p "$out_dir"
log="$out_dir/$harness.log"

banner "$harness on $proj_dir/$proj_name: $*"
# ${args[@]+...} keeps bash 3.2 from failing on an empty array under set -u.
"$HEADLESS" "$proj_dir" "$proj_name" -process "$bin" -noanalysis -readOnly \
    -scriptPath "$REPO_ROOT/os/$OS_ID/scripts/emu" \
    -postScript "$harness.java" ${args[@]+"${args[@]}"} 2>&1 | tee "$log"

banner "verdict (full log: $log)"
grep -E "ALL CASES PASS|FAILURE|FAIL\*\*|DO NOT FLASH|RESULT|RETURNED|FAULT|STUCK|steps=" "$log" || echo "(no verdict line; read the log)"
