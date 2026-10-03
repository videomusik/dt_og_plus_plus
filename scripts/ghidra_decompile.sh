#!/usr/bin/env bash
# Decompile selected functions of an analysed section to text
# (work/ghidra/out/<image>[_<section>][_<variant>]/decomp/).
#
#   ./scripts/ghidra_decompile.sh <image> <selector>...
#   ./scripts/ghidra_decompile.sh <os> class:<Namespace> 'str:<regex>' addr:<hex>
#   SECTION=dsp ./scripts/ghidra_decompile.sh <os> addr:<hex>             # in the DSP section
#   GHIDRA_LANG_VARIANT=emac ./scripts/ghidra_decompile.sh <os> 're:.*'   # everything, EMAC project
#
# Worked examples with real addresses: os/<os>/notes/analysis_reference.md
#
# Selectors (see scripts/ghidra/DumpDecompiled.java):
#   class:<Namespace>   re:<regex>   str:<regex>   addr:<hex>   xref:<hex>   callers:<hex>
#   mkfunc:<hex>        create a function at <hex> first (e.g. a raw image's entry point); it is saved
#                       to the project, so decompile runs do change the project
#
# Which section: the SECTION environment variable (default main); it must match an earlier
# ./scripts/ghidra_analyze.sh <image> <section> run. GHIDRA_LANG_VARIANT and GHIDRA_PROJECT select the
# project exactly as they do for ghidra_analyze.sh.
# The decompiled C is derived from Elektron's firmware: it stays in work/, never in a commit or a note.
# Close the Ghidra GUI on this project first: a project is locked while it is open.

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

usage="usage: $0 <image> <selector>...   (<image> = <os> or <os>:<file.syx>; SECTION=<section>, default main)"
if [ $# -lt 1 ]; then echo "$usage" >&2; os_usage_list; exit 2; fi
image_parse "$1"; shift
[ $# -ge 1 ] || { echo "error: at least one selector required" >&2; exit 1; }
ghidra_check_project
ghidra_find_script DumpDecompiled
script_path="$(ghidra_script_path)" || exit 1
ghidra_env

key="$IMG_ARG"
section="${SECTION:-main}"
dir="$IMG_DIR"
bin="$(section_progname "$dir" "$key" "$section")" || exit 1
proj_dir="$(ghidra_projdir "$dir" "$section")" || exit 1
proj_name="$(ghidra_projname "$dir" "$section")" || exit 1
out_dir="$(ghidra_outdir "$dir" "$section")" || exit 1
[ -f "$proj_dir/$proj_name.gpr" ] || { echo "error: no Ghidra project at $proj_dir; run ./scripts/ghidra_analyze.sh $key $section" >&2; exit 1; }
mkdir -p "$out_dir/decomp"

banner "decompile in $proj_dir/$proj_name ($key $section): $*"
"$HEADLESS" "$proj_dir" "$proj_name" -process "$bin" -noanalysis \
    -scriptPath "$script_path" \
    -postScript DumpDecompiled.java "$REPO_ROOT/$out_dir" "$@" 2>&1 | grep -E 'DumpDecompiled|ERROR|Exception|error:' || true

banner "index (this run was appended to $out_dir/decomp/index.tsv)"
tail -n 40 "$out_dir/decomp/index.tsv" 2>/dev/null | cut -f1-4
