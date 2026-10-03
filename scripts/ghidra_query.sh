#!/usr/bin/env bash
# Run a script from scripts/ghidra/ against an analysed section and show its summary.
#
#   ./scripts/ghidra_query.sh <image> <section> <Script> [script args...]
#   ./scripts/ghidra_query.sh dt main RefDensityMap       0x40214000 0x439902a0   # where is .bss referenced?
#   ./scripts/ghidra_query.sh dt main DumpRefsInRange     0x40214000 0x40240000   # zoom into a stretch
#   ./scripts/ghidra_query.sh dt dsp  DumpRefsInRange     0x80008000 0x80010000   # the same, in the DSP
#   ./scripts/ghidra_query.sh dt main FindDeadSpace       0x40000400 0x40214000 16
#   ./scripts/ghidra_query.sh dt main FindAddressLiterals 0x8000edc8 0x8000f0b8   # immediates, not refs
#   GHIDRA_PROJECT=dt_1.52A_seed ./scripts/ghidra_query.sh dt main FindDeadFunctions
#
# Every script here takes its output file as its FIRST argument; this wrapper supplies it, as
# work/ghidra/out/<project>/query/<Script>_<args>.txt, and passes the rest through. The '#'-prefixed
# summary lines are echoed; the full listing stays in the file.
# The query scripts (RefDensityMap, DumpRefsInRange, FindDeadSpace, FindAddressLiterals,
# FindDeadFunctions) only read the project. The curation scripts (SeedCodeGaps, CurateDsp,
# FixDspResidual) CHANGE it: run those on a copy (docs/toolchain.md section 4d).
# GHIDRA_LANG_VARIANT and GHIDRA_PROJECT select the project exactly as they do for ghidra_analyze.sh.
# Close the Ghidra GUI on this project first: a project is locked while it is open.

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
ghidra_env

usage="usage: $0 <image> <section> <Script> [script args...]   (section: main|dsp|updater|sram)"
key="${1:?$usage}"; section="${2:?$usage}"; script="${3:?$usage}"; shift 3
[ -f "$REPO_ROOT/scripts/ghidra/$script.java" ] || { echo "error: no scripts/ghidra/$script.java" >&2; exit 1; }

dir="$(image_dir "$key")"
bin="$(section_progname "$dir" "$key" "$section")" || exit 1
proj_dir="$(ghidra_projdir "$dir" "$section")"
proj_name="$(ghidra_projname "$dir" "$section")"
out_dir="$(ghidra_outdir "$dir" "$section")/query"
[ -f "$proj_dir/$proj_name.gpr" ] || { echo "error: no Ghidra project at $proj_dir; run ./scripts/ghidra_analyze.sh $key $section" >&2; exit 1; }
mkdir -p "$out_dir"

tag="$*"; tag="${tag//[^A-Za-z0-9_]/_}"          # e.g. 0x40214000 0x40240000 -> 0x40214000_0x40240000
out="$out_dir/${script}${tag:+_$tag}.txt"

banner "$script on $proj_dir/$proj_name ($key $section): $*"
"$HEADLESS" "$proj_dir" "$proj_name" -process "$bin" -noanalysis \
    -scriptPath "$REPO_ROOT/scripts/ghidra" \
    -postScript "$script.java" "$REPO_ROOT/$out" "$@" 2>&1 | grep -E "$script|ERROR|Exception|error:" || true

banner "summary (full listing: $out)"
grep '^#' "$out" 2>/dev/null || echo "(no output file; see the Ghidra log lines above)"
