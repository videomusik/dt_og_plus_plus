#!/usr/bin/env bash
# Run one emulator harness from scripts/emu/ headless against an analysed MAIN OS project, and show
# its report. The harnesses use Ghidra's p-code emulator (EmulatorHelper). Nothing leaves the
# computer, and the project is opened read-only, so nothing in it changes.
#
#   ./scripts/ghidra_emu.sh <Harness> [harness args...]
#   ./scripts/ghidra_emu.sh EmuTrackAlias                                    # pad bytes built in
#   ./scripts/ghidra_emu.sh EmuSliceLatch work/<build>/section_3_MAIN_OS.bin # pad bytes read from a build
#
# Default project: the EMAC one (GHIDRA_LANG_VARIANT=emac ./scripts/ghidra_analyze.sh dt main first).
# Set GHIDRA_PROJECT to use another, e.g. GHIDRA_PROJECT=dt_1.52A_seed. What each harness checks, and
# which arguments it takes, is in scripts/emu/README.md.
# The full headless log is kept in work/ghidra/out/<project>/emu/<Harness>.log.
# Close the Ghidra GUI on this project first: a project is locked while it is open.

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
ghidra_env

harness="${1:?usage: $0 <Harness> [harness args...]  (see scripts/emu/README.md)}"; shift
[ -f "$REPO_ROOT/scripts/emu/$harness.java" ] || { echo "error: no scripts/emu/$harness.java" >&2; exit 1; }

: "${GHIDRA_LANG_VARIANT=emac}"      # default to the EMAC project; an explicit empty value picks stock
export GHIDRA_LANG_VARIANT
dir="$(image_dir dt)"
bin="$(section_progname "$dir" dt main)" || exit 1
proj_dir="$(ghidra_projdir "$dir" main)"
proj_name="$(ghidra_projname "$dir" main)"
out_dir="$(ghidra_outdir "$dir" main)/emu"
[ -f "$proj_dir/$proj_name.gpr" ] || { echo "error: no Ghidra project at $proj_dir; run GHIDRA_LANG_VARIANT=emac ./scripts/ghidra_analyze.sh dt main" >&2; exit 1; }
mkdir -p "$out_dir"
log="$out_dir/$harness.log"

# Harness arguments that name files are made absolute, because Ghidra resolves a relative path from
# its own working directory, not from the repo root.
args=()
for a in "$@"; do
    case "$a" in
        /*) args+=( "$a" ) ;;
        *)  if [ -e "$a" ]; then args+=( "$REPO_ROOT/$a" )
            elif [ -e "$CALLER_PWD/$a" ]; then args+=( "$CALLER_PWD/$a" )
            else args+=( "$a" ); fi ;;
    esac
done

banner "$harness on $proj_dir/$proj_name: $*"
# ${args[@]+...} keeps bash 3.2 from failing on an empty array under set -u.
"$HEADLESS" "$proj_dir" "$proj_name" -process "$bin" -noanalysis -readOnly \
    -scriptPath "$REPO_ROOT/scripts/emu" \
    -postScript "$harness.java" ${args[@]+"${args[@]}"} 2>&1 | tee "$log"

banner "verdict (full log: $log)"
grep -E "ALL CASES PASS|FAILURE|FAIL\*\*|DO NOT FLASH|RESULT|RETURNED|FAULT|STUCK|steps=" "$log" || echo "(no verdict line; read the log)"
