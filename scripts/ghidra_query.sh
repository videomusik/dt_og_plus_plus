#!/usr/bin/env bash
# Run a Ghidra script from scripts/ghidra/ or os/<os>/scripts/ghidra/ against an analysed section and
# show its summary.
#
#   ./scripts/ghidra_query.sh <image> <section> <Script> [script args...]
#   ./scripts/ghidra_query.sh <os> main RefDensityMap       <start> <stop>   # where is .bss referenced?
#   ./scripts/ghidra_query.sh <os> main DumpRefsInRange     <start> <stop>   # zoom into a stretch
#   ./scripts/ghidra_query.sh <os> dsp  DumpRefsInRange     <start> <stop>   # the same, in the DSP
#   ./scripts/ghidra_query.sh <os> main FindDeadSpace       <start> <stop> 16
#   ./scripts/ghidra_query.sh <os> main FindAddressLiterals <start> <stop>   # immediates, not refs
#   GHIDRA_PROJECT=dt_<os>_seed ./scripts/ghidra_query.sh <os> main FindDeadFunctions
#
# Worked examples with real addresses: os/<os>/notes/analysis_reference.md
#
# Every script here takes its output file as its FIRST argument; this wrapper supplies it, as
# work/ghidra/out/<project>/query/<Script>_<args>.txt, and passes the rest through. The '#'-prefixed
# summary lines are echoed; the full listing stays in the file.
# The shared scripts live in scripts/ghidra/. SeedCodeGaps, CurateDsp and FindDeadFunctions carry one
# OS's tables and live in os/<os>/scripts/ghidra/. Both folders are on the script path; a name found
# in both, or in neither, is refused before Ghidra starts.
# The query scripts (RefDensityMap, DumpRefsInRange, FindDeadSpace, FindAddressLiterals,
# FindDeadFunctions) only read the project. The curation scripts (SeedCodeGaps, CurateDsp,
# FixDspResidual) CHANGE it: run those on a copy (docs/toolchain.md section 4d).
# GHIDRA_LANG_VARIANT and GHIDRA_PROJECT select the project exactly as they do for ghidra_analyze.sh.
# Close the Ghidra GUI on this project first: a project is locked while it is open.

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

usage="usage: $0 <image> <section> <Script> [script args...]   (<image> = <os> or <os>:<file.syx>)"
if [ $# -lt 3 ]; then echo "$usage" >&2; os_usage_list; exit 2; fi
image_parse "$1"
section="$2"; script="$3"; shift 3
ghidra_check_project
ghidra_find_script "$script"
script_path="$(ghidra_script_path)" || exit 1
ghidra_env

key="$IMG_ARG"
dir="$IMG_DIR"
bin="$(section_progname "$dir" "$key" "$section")" || exit 1
proj_dir="$(ghidra_projdir "$dir" "$section")" || exit 1
proj_name="$(ghidra_projname "$dir" "$section")" || exit 1
out_dir="$(ghidra_outdir "$dir" "$section")/query" || exit 1
[ -f "$proj_dir/$proj_name.gpr" ] || { echo "error: no Ghidra project at $proj_dir; run ./scripts/ghidra_analyze.sh $key $section" >&2; exit 1; }
mkdir -p "$out_dir"

tag="$*"; tag="${tag//[^A-Za-z0-9_]/_}"          # e.g. <start> <stop> -> <start>_<stop>
out="$out_dir/${script}${tag:+_$tag}.txt"

banner "$script on $proj_dir/$proj_name ($key $section): $*"
"$HEADLESS" "$proj_dir" "$proj_name" -process "$bin" -noanalysis \
    -scriptPath "$script_path" \
    -postScript "$script.java" "$REPO_ROOT/$out" "$@" 2>&1 | grep -E "$script|ERROR|Exception|error:" || true

banner "summary (full listing: $out)"
grep '^#' "$out" 2>/dev/null || echo "(no output file; see the Ghidra log lines above)"
