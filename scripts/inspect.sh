#!/usr/bin/env bash
# Print the one-glance summary of an OS image. Reads only; writes nothing.
#
#   ./scripts/inspect.sh [-v] <image>...
#   ./scripts/inspect.sh <os>                          # your stock file of that OS folder
#   ./scripts/inspect.sh <os>:out/<os>/<build>.syx     # any .syx of that OS, e.g. a build output
#   ./scripts/inspect.sh -v <os>                       # the full verbose report
#
# <image> is <os> (the OS folder's stock file, hash-checked) or <os>:<file.syx>; see scripts/common.sh.
# The expected summary of each OS folder's stock file is in os/<os>/docs/reference.md (What inspect.sh
# prints). Expect "checksums : ok"; anything else: stop and look before extracting.

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

usage="usage: $0 [-v] <image>...   (<image> = <os> or <os>:<file.syx>)"
verbose=""
if [ "${1:-}" = "-v" ]; then verbose="-v"; shift; fi
if [ $# -eq 0 ]; then echo "$usage" >&2; os_usage_list; exit 2; fi

# Parse every image first, so a bad argument stops the run before anything is read.
img_arg=(); img_kind=(); img_syx=(); img_base=(); img_dir=()
for a in "$@"; do
    image_parse "$a"
    img_arg+=( "$IMG_ARG" ); img_kind+=( "$IMG_KIND" ); img_syx+=( "$IMG_SYX" )
    img_base+=( "$IMG_BASE" ); img_dir+=( "$IMG_DIR" )
done
require_tool

i=0
while [ "$i" -lt "${#img_arg[@]}" ]; do
    IMG_ARG="${img_arg[$i]}"; IMG_KIND="${img_kind[$i]}"; IMG_SYX="${img_syx[$i]}"
    IMG_BASE="${img_base[$i]}"; IMG_DIR="${img_dir[$i]}"
    check_image
    banner "$IMG_SYX"
    "$TOOL_BIN" $verbose -i "$IMG_SYX"
    i=$((i + 1))
done
