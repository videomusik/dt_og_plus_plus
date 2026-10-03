#!/usr/bin/env bash
# Print the one-glance summary of an OS image. Reads only; writes nothing.
#
#   ./scripts/inspect.sh                    # your stock file (sysex/Digitakt_OS1.52A.syx)
#   ./scripts/inspect.sh out/<build>.syx    # any .syx, e.g. a build output
#   ./scripts/inspect.sh -v dt              # the full verbose report
#
# Expected for the stock file: device Digitakt (0x0a), version 1.52A, container ELE3, sections
# 5 meta, 2 DSP, 3 MAIN OS, 4 updater, and "checksums : ok". Anything else: stop and look before
# extracting. Section ids: 2 = DSP, 3 = MAIN OS, 4 = updater, 5 = meta.

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
require_tool

verbose=""
if [ "${1:-}" = "-v" ]; then verbose="-v"; shift; fi
images="${*:-$ALL_IMAGES}"

for key in $images; do
    syx="$(image_syx "$key")"
    check_image "$key" "$syx"
    banner "$syx"
    "$TOOL_BIN" $verbose -i "$syx"
done
