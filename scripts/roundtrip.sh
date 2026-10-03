#!/usr/bin/env bash
# Round-trip sanity check: rebuild an image from its UNMODIFIED extracted MAIN OS section, verify the
# rebuilt .syx, re-extract it, and compare every decompressed section byte for byte.
# Passing this is the precondition for trusting the tool with any real modification.
#
#   ./scripts/roundtrip.sh                  # your stock file (needs ./scripts/extract.sh first)
#   ./scripts/roundtrip.sh out/<build>.syx  # any .syx that ./scripts/extract.sh has extracted
#
# Expected: the rebuilt rt.syx reports "checksums : ok", and every compared section prints
# "identical". rt.syx itself will NOT match the original .syx: the tool's compressor emits a different
# (valid) stream, so only decompressed sections are compared, never the containers.

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
require_tool

key="${1:-dt}"
syx="$(image_syx "$key")"
dir="$(image_dir "$key")"
rt="$dir/roundtrip"
check_image "$key" "$syx"

MAIN_ID=3                                   # MAIN OS is section id 3 in the Digitakt's ELE3 container
main_file="$(section_file "$dir" "$MAIN_ID")"

mkdir -p "$rt"

banner "rebuild $syx with unmodified section $MAIN_ID -> $rt/rt.syx"
"$TOOL_BIN" -i "$syx" -c "$MAIN_ID" "$main_file" -o "$rt/rt.syx"

banner "verify rebuilt image"
"$TOOL_BIN" -i "$rt/rt.syx"

banner "re-extract rebuilt image -> $rt"
"$TOOL_BIN" -i "$rt/rt.syx" -o "$rt"

banner "compare decompressed sections (original extract vs re-extract)"
status=0
found=0
for f in "$dir"/section_*; do
    [ -f "$f" ] || continue
    case "$f" in *.from*.bin) continue ;; esac     # derived copies made for Ghidra, not tool output
    found=$((found + 1))
    name="$(basename "$f")"
    if [ ! -f "$rt/$name" ]; then
        echo "MISSING   $name (not produced by the re-extract)"; status=1; continue
    fi
    if cmp -s "$f" "$rt/$name"; then
        echo "identical $name"
    else
        echo "DIFFERS   $name"; status=1
    fi
done
for f in "$rt"/section_*; do
    [ -f "$f" ] || continue
    name="$(basename "$f")"
    [ -f "$dir/$name" ] || { echo "EXTRA     $name (only in the re-extract)"; status=1; }
done
[ "$found" -gt 0 ] || { echo "error: no section files in $dir (run ./scripts/extract.sh first)" >&2; status=1; }

if [ $status -eq 0 ]; then
    banner "round-trip OK for $key"
else
    banner "round-trip FAILED for $key: do not modify firmware with this toolchain until this is understood"
fi
exit $status
