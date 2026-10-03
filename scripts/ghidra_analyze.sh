#!/usr/bin/env bash
# Import an extracted firmware SECTION into a Ghidra project (first run), or re-run the dump scripts
# on the existing project (later runs), headless. Output is plain text under work/ghidra/out/.
#
#   ./scripts/ghidra_analyze.sh                 # stock image (dt), MAIN OS section
#   ./scripts/ghidra_analyze.sh dt main         # the same, explicit
#   ./scripts/ghidra_analyze.sh dt dsp          # the DSP (audio hardware / boot loader) section
#   ./scripts/ghidra_analyze.sh dt updater      # the flash-updater section
#   GHIDRA_LANG_VARIANT=emac ./scripts/ghidra_analyze.sh dt main          # ColdFire+EMAC language
#   GHIDRA_LANG_VARIANT=emac GHIDRA_PROJECT=dt_1.52A_sram ./scripts/ghidra_analyze.sh dt sram
#                                               # the assembled SRAM image (build_sram_image.py first)
#   ./scripts/ghidra_analyze.sh out/<build>.syx main   # a build output (./scripts/extract.sh it first)
#
# GHIDRA_LANG_VARIANT selects an alternate processor language (ghidra_langid in common.sh,
# scripts/ghidra_ext/README.md) and puts the analysis in its own project and output folder, so the
# stock analysis is kept for comparison. GHIDRA_PROJECT names the folder under work/ghidra/ outright.
# The project recipes (stock, _emac, _dsp, _seed, _sram) are in docs/toolchain.md section 4d.
#
# Section metadata (source file, load base, header strip, entry) lives in section_meta() in common.sh.
# Section 'main' keeps the bare project name work/ghidra/<image>/; others go to
# work/ghidra/<image>_<section>/.
#
# First run: creates the project, imports the section as ColdFire at its base, runs full
# auto-analysis (minutes for MAIN OS, seconds for the small sections), then RTTI naming and the dumps.
# Later runs: -process with -noanalysis, re-run the dumps. Delete the project folder to start over.
#
# Needs ./scripts/extract.sh to have run, and Ghidra + JDK 21 (docs/toolchain.md section 4).
# Close the Ghidra GUI on this project first: a project is locked while it is open.

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
ghidra_env

key="${1:-dt}"
section="${2:-main}"
dir="$(image_dir "$key")"
meta="$(section_meta "$key" "$section")" || exit 1
read -r src BASE STRIP ENTRY <<<"$meta"
LANG_ID="$(ghidra_langid)" || exit 1

srcbin="$(section_path "$dir" "$key" "$section")" || exit 1
if [ ! -f "$srcbin" ]; then
    if [ "$section" = sram ]; then
        echo "error: $srcbin missing; run python3 scripts/build_sram_image.py first" >&2
    else
        echo "error: $srcbin missing; run ./scripts/extract.sh $key first" >&2
    fi
    exit 1
fi

# Strip an inner header when the section declares one (the DSP's 24-byte header) and import the
# stripped copy, so that file offset 0 is the load base.
if [ "$STRIP" -gt 0 ]; then
    bin="${srcbin%.bin}.from${STRIP}.bin"
    tail -c "+$((STRIP + 1))" "$srcbin" > "$bin"
else
    bin="$srcbin"
fi
[ "$ENTRY" = "-" ] && ENTRY=""   # sections with no simple entry point (the DSP)

proj_dir="$(ghidra_projdir "$dir" "$section")"
proj_name="$(ghidra_projname "$dir" "$section")"
out_dir="$(ghidra_outdir "$dir" "$section")"
mkdir -p "$proj_dir" "$out_dir"
log="$out_dir/headless.log"

# Post-analysis scripts, in order: recover class/vtable structure from RTTI (names functions), create
# the entry function when the section has one, then dump functions/strings/errors to text.
common=( "$proj_dir" "$proj_name" -scriptPath "$REPO_ROOT/scripts/ghidra"
         -postScript NameFromRtti.java "$REPO_ROOT/$out_dir" )
[ -n "$ENTRY" ] && common+=( -postScript DumpDecompiled.java "$REPO_ROOT/$out_dir" "mkfunc:$ENTRY" )
common+=( -postScript DumpFunctions.java "$REPO_ROOT/$out_dir" )

if [ -f "$proj_dir/$proj_name.gpr" ]; then
    banner "re-run scripts on existing project $proj_dir/$proj_name (no re-analysis)"
    "$HEADLESS" "${common[@]}" -process "$(basename "$bin")" -noanalysis 2>&1 | tee "$log"
else
    banner "import $bin as $LANG_ID @ $BASE into $proj_dir/$proj_name and analyse"
    "$HEADLESS" "${common[@]}" -import "$bin" \
        -processor "$LANG_ID" -loader BinaryLoader -loader-baseAddr "$BASE" \
        -analysisTimeoutPerFile 7200 2>&1 | tee "$log"
fi

banner "summary ($key $section)"
cat "$out_dir/summary.txt" 2>/dev/null || echo "(no summary written; see $log)"
