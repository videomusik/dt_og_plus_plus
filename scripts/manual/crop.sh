#!/usr/bin/env bash
# Crop a pixel rectangle from a page render into images/ (for vector and balloon diagrams, which are
# not embedded rasters). Compiles scripts/manual/crop_region.swift into work/bin/ on first use.
# One bare command, so a single agent allow-rule covers it (see scripts/manual/README.md).
#   ./scripts/manual/crop.sh <in.png> <x> <y> <w> <h> <out.png>
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SRC="$ROOT/scripts/manual/crop_region.swift"; BIN="$ROOT/work/bin/crop_region"
mkdir -p "$ROOT/work/bin"
if [ ! -x "$BIN" ] || [ "$SRC" -nt "$BIN" ]; then swiftc -O "$SRC" -o "$BIN" >/dev/null; fi
"$BIN" "$@"
