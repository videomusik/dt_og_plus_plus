#!/usr/bin/env bash
# Decompile selected functions of an analysed section to text
# (work/ghidra/out/<image>[_<section>][_<variant>]/decomp/).
#
#   ./scripts/ghidra_decompile.sh <image> <selector>...
#   ./scripts/ghidra_decompile.sh dt class:MachineListView 'str:Slice Select' addr:0x4000b564
#   SECTION=dsp ./scripts/ghidra_decompile.sh dt addr:0x80001780      # in the DSP section
#   GHIDRA_LANG_VARIANT=emac ./scripts/ghidra_decompile.sh dt 're:.*'  # everything, EMAC project
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
ghidra_env

key="${1:?usage: $0 <image> <selector>...  (SECTION=main|dsp|updater|sram)}"; shift
[ $# -ge 1 ] || { echo "error: at least one selector required" >&2; exit 1; }
section="${SECTION:-main}"
dir="$(image_dir "$key")"
bin="$(section_progname "$dir" "$key" "$section")" || exit 1
proj_dir="$(ghidra_projdir "$dir" "$section")"
proj_name="$(ghidra_projname "$dir" "$section")"
out_dir="$(ghidra_outdir "$dir" "$section")"
[ -f "$proj_dir/$proj_name.gpr" ] || { echo "error: no Ghidra project at $proj_dir; run ./scripts/ghidra_analyze.sh $key $section" >&2; exit 1; }
mkdir -p "$out_dir/decomp"

banner "decompile in $proj_dir/$proj_name ($key $section): $*"
"$HEADLESS" "$proj_dir" "$proj_name" -process "$bin" -noanalysis \
    -scriptPath "$REPO_ROOT/scripts/ghidra" \
    -postScript DumpDecompiled.java "$REPO_ROOT/$out_dir" "$@" 2>&1 | grep -E 'DumpDecompiled|ERROR|Exception|error:' || true

banner "index (this run was appended to $out_dir/decomp/index.tsv)"
tail -n 40 "$out_dir/decomp/index.tsv" 2>/dev/null | cut -f1-4
