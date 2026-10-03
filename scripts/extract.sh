#!/usr/bin/env bash
# Extract every section of an OS image into work/<image>/, save the full verbose report next to the
# sections as report.txt, then list the load addresses.
#
#   ./scripts/extract.sh                    # your stock file -> work/dt_1.52A/
#   ./scripts/extract.sh out/<build>.syx    # any .syx, e.g. a build output -> work/<build>/
#
# Output (gitignored work/<image>/):
#   section_<id>_<NAME>.bin   a decompressed section (.raw when the section was stored uncompressed)
#   report.txt                the -v report: transport stats, section table (off/clen/dst), checksum
#                             verdicts
#
# The stock file is checked against its published SHA-256 first; every address in this repo is for
# that exact image. Digitakt OS 1.52A carries no signature trailer, so the report holds no key
# material. It still describes Elektron's image: keep it in work/, never publish it.

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
require_tool

images="${*:-$ALL_IMAGES}"

for key in $images; do
    syx="$(image_syx "$key")"
    dir="$(image_dir "$key")"
    check_image "$key" "$syx"
    mkdir -p "$dir"
    banner "extract $syx -> $dir"
    "$TOOL_BIN" -v -i "$syx" -o "$dir" | tee "$dir/report.txt"
done

banner "extracted files"
for key in $images; do
    ls -l "$(image_dir "$key")"
done

banner "section table (dst = load address on the device; the DSP's dst is only a staging address)"
for key in $images; do
    echo "--- $key"
    grep -h '  id=' "$(image_dir "$key")/report.txt" || true
done

for key in $images; do
    [ "$key" = dt ] || continue
    main="$(section_file "$(image_dir "$key")" 3)"
    h="$(sha256_of "$main")"
    if [ "$h" = "$STOCK_MAIN_SHA256" ]; then
        echo "MAIN OS ok: $main matches the stock section 3 hash"
    else
        echo "error: $main sha256 $h, want $STOCK_MAIN_SHA256" >&2
        exit 1
    fi
done
