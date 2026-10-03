#!/usr/bin/env bash
# Import an extracted firmware SECTION into a Ghidra project (first run), or re-run the dump scripts
# on the existing project (later runs), headless. Output is plain text under work/ghidra/out/.
#
#   ./scripts/ghidra_analyze.sh <image> [section=main]
#   ./scripts/ghidra_analyze.sh <os>                 # the OS folder's stock image, MAIN OS section
#   ./scripts/ghidra_analyze.sh <os> main            # the same, explicit
#   ./scripts/ghidra_analyze.sh <os> dsp             # the dsp section (OS 1.52A: the audio hardware / boot loader)
#   ./scripts/ghidra_analyze.sh <os> updater         # the flash-updater section
#   GHIDRA_LANG_VARIANT=emac ./scripts/ghidra_analyze.sh <os> main          # ColdFire+EMAC language
#   GHIDRA_LANG_VARIANT=emac GHIDRA_PROJECT=dt_<os>_sram ./scripts/ghidra_analyze.sh <os> sram
#                         # the assembled SRAM image (python3 os/<os>/scripts/build_sram_image.py first)
#   ./scripts/ghidra_analyze.sh <os>:out/<os>/<build>.syx main   # a build (./scripts/extract.sh it first)
#
# GHIDRA_LANG_VARIANT selects an alternate processor language (ghidra_langid in common.sh,
# scripts/ghidra_ext/README.md) and puts the analysis in its own project and output folder, so the
# stock analysis is kept for comparison. GHIDRA_PROJECT names the folder under work/ghidra/ outright;
# it must be the image's project name or start with it plus _ (ghidra_check_project in common.sh).
# The project recipes are in docs/toolchain.md section 4d; each OS folder's projects and reference
# numbers are in os/<os>/notes/analysis_reference.md.
#
# Section metadata (source file, load base, header strip, entry) lives in os_section_meta in
# os/<os>/profile.sh, read through section_meta in common.sh. Section 'main' keeps the bare project
# name work/ghidra/<image>/ (dt_<os>, or dt_<os>-<build> for a file image); others go to
# work/ghidra/<image>_<section>/.
# The post-scripts come from scripts/ghidra/ and, when it exists, os/<os>/scripts/ghidra/.
#
# First run: creates the project, imports the section as ColdFire at its base, runs full
# auto-analysis (minutes for MAIN OS, seconds for the small sections), then RTTI naming and the dumps.
# Later runs: -process with -noanalysis, re-run the dumps. Delete the project folder to start over.
#
# Needs ./scripts/extract.sh to have run, and Ghidra + JDK 21 (docs/toolchain.md section 4).
# Close the Ghidra GUI on this project first: a project is locked while it is open.

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

usage="usage: $0 <image> [section=main]   (<image> = <os> or <os>:<file.syx>)"
if [ $# -lt 1 ] || [ $# -gt 2 ]; then echo "$usage" >&2; os_usage_list; exit 2; fi
image_parse "$1"
ghidra_check_project
section="${2:-main}"
key="$IMG_ARG"
dir="$IMG_DIR"
meta="$(section_meta "$key" "$section")" || exit 1
read -r src BASE STRIP ENTRY <<<"$meta"
LANG_ID="$(ghidra_langid)" || exit 1
ghidra_find_script NameFromRtti
ghidra_find_script DumpDecompiled
ghidra_find_script DumpFunctions
script_path="$(ghidra_script_path)" || exit 1
ghidra_env

srcbin="$(section_path "$dir" "$key" "$section")" || exit 1
if [ ! -f "$srcbin" ]; then
    if [ "$section" = sram ]; then
        if [ -f "os/$OS_ID/scripts/build_sram_image.py" ]; then
            echo "error: $srcbin missing; run python3 os/$OS_ID/scripts/build_sram_image.py $IMG_BASE first" >&2
        else
            echo "error: $srcbin missing; os/$OS_ID/ has no SRAM image builder" >&2
        fi
    else
        echo "error: $srcbin missing; run ./scripts/extract.sh $key first" >&2
    fi
    exit 1
fi

# Strip an inner header when the section declares one (OS 1.52A: the DSP's 24-byte header) and import the
# stripped copy, so that file offset 0 is the load base.
if [ "$STRIP" -gt 0 ]; then
    bin="${srcbin%.bin}.from${STRIP}.bin"
    tail -c "+$((STRIP + 1))" "$srcbin" > "$bin"
else
    bin="$srcbin"
fi
[ "$ENTRY" = "-" ] && ENTRY=""   # sections with no simple entry point (OS 1.52A: the DSP)

proj_dir="$(ghidra_projdir "$dir" "$section")" || exit 1
proj_name="$(ghidra_projname "$dir" "$section")" || exit 1
out_dir="$(ghidra_outdir "$dir" "$section")" || exit 1
mkdir -p "$proj_dir" "$out_dir"
log="$out_dir/headless.log"

# Post-analysis scripts, in order: recover class/vtable structure from RTTI (names functions), create
# the entry function when the section has one, then dump functions/strings/errors to text.
common=( "$proj_dir" "$proj_name" -scriptPath "$script_path"
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
