#!/usr/bin/env bash
# Extract every section of an OS image into work/<image>/, save the full verbose report next to the
# sections as report.txt, then list the load addresses.
#
#   ./scripts/extract.sh <image>...
#   ./scripts/extract.sh <os>                         # your stock file -> work/dt_<os>/
#   ./scripts/extract.sh <os>:out/<os>/<build>.syx    # a build of that OS -> work/dt_<os>-<build>/
#
# Output (gitignored work/<image>/):
#   section_<id>_<NAME>.bin   a decompressed section (.raw when the section was stored uncompressed)
#   report.txt                the -v report: transport stats, section table (off/clen/dst), checksum
#                             verdicts
#
# A stock image is checked against its OS folder's SHA-256 first. Whether an OS image carries a
# signature trailer is recorded in os/<os>/profile.sh (OS_SIGNATURE_TRAILER); report.txt describes
# Elektron's image either way: keep it in work/, never publish it.

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

usage="usage: $0 <image>...   (<image> = <os> or <os>:<file.syx>)"
if [ $# -eq 0 ]; then echo "$usage" >&2; os_usage_list; exit 2; fi

# Parse every image first, and check every one before extracting any, so a bad argument or a wrong
# file stops the run before anything is written.
img_arg=(); img_kind=(); img_syx=(); img_base=(); img_dir=()
for a in "$@"; do
    image_parse "$a"
    img_arg+=( "$IMG_ARG" ); img_kind+=( "$IMG_KIND" ); img_syx+=( "$IMG_SYX" )
    img_base+=( "$IMG_BASE" ); img_dir+=( "$IMG_DIR" )
done
n="${#img_arg[@]}"
require_tool

use_image() {   # args: <index>. Selects one parsed image (sets IMG_*).
    IMG_ARG="${img_arg[$1]}"; IMG_KIND="${img_kind[$1]}"; IMG_SYX="${img_syx[$1]}"
    IMG_BASE="${img_base[$1]}"; IMG_DIR="${img_dir[$1]}"
}

i=0; while [ "$i" -lt "$n" ]; do
    use_image "$i"
    check_image
    i=$((i + 1))
done

i=0; while [ "$i" -lt "$n" ]; do
    use_image "$i"
    mkdir -p "$IMG_DIR"
    banner "extract $IMG_SYX -> $IMG_DIR"
    "$TOOL_BIN" -v -i "$IMG_SYX" -o "$IMG_DIR" | tee "$IMG_DIR/report.txt"
    i=$((i + 1))
done

if [ "$OS_SIGNATURE_TRAILER" != none ]; then
    echo "note: OS $OS_ID signature trailer: $OS_SIGNATURE_TRAILER; report.txt may hold it"
fi

banner "extracted files"
i=0; while [ "$i" -lt "$n" ]; do
    ls -l "${img_dir[$i]}"
    i=$((i + 1))
done

banner "section table (dst = load address the container declares; where each section runs: os/$OS_ID/docs/reference.md)"
i=0; while [ "$i" -lt "$n" ]; do
    echo "--- ${img_arg[$i]}"
    grep -h '  id=' "${img_dir[$i]}/report.txt" || true
    i=$((i + 1))
done

i=0; while [ "$i" -lt "$n" ]; do
    use_image "$i"
    if [ "$IMG_KIND" = stock ]; then
        main="$(section_file "$IMG_DIR" "$OS_MAIN_ID")" || exit 1
        h="$(sha256_of "$main")" || exit 1
        if [ "$h" = "$OS_STOCK_MAIN_SHA256" ]; then
            echo "MAIN OS ok: $main matches the stock section $OS_MAIN_ID hash"
        else
            echo "error: $main sha256 $h, want $OS_STOCK_MAIN_SHA256" >&2
            exit 1
        fi
    fi
    i=$((i + 1))
done
