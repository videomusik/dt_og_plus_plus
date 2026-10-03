#!/usr/bin/env bash
# Phase 1 of the manual -> markdown pipeline: render every PDF page to PNG, pull the text layer, dump
# the outline and page sizes, crop the embedded figures, and write the chapter manifest. macOS only:
# it uses Swift with the system frameworks PDFKit, CoreGraphics and ImageIO (swiftc comes with the
# Xcode Command Line Tools). Nothing is installed, nothing goes on the network. Safe to re-run.
#
#   ./scripts/manual_extract.sh <pdf> <outdir> [dpi=170]
#   ./scripts/manual_extract.sh manuals/Digitakt_User_Manual_ENG_OS1.50_230301.pdf work/manuals/Digitakt_OS1.50
#
# Output in <outdir>: pages/page-NNN.png, text/page-NNN.txt, meta.json (page count, dpi, outline,
# page pixel sizes), images/pNNN_*.png + figures.txt + figures.json (the embedded raster figures),
# chapters.json, and a copy of scripts/manual/CONVENTIONS.md. Then phase 2 (one agent per chapter)
# follows scripts/manual/README.md. Tool sources: scripts/manual/*.swift, compiled into work/bin/.
set -euo pipefail
usage="usage: manual_extract.sh <pdf> <outdir> [dpi]"
PDF="${1:?$usage}"
OUT="${2:?$usage}"
DPI="${3:-170}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$ROOT/scripts/manual/render_pdf.swift"
BIN="$ROOT/work/bin/render_pdf"
XSRC="$ROOT/scripts/manual/crop_figures.swift"
XBIN="$ROOT/work/bin/crop_figures"

[ "$(uname -s)" = Darwin ] || { echo "error: the manual pipeline is macOS-only (Swift + PDFKit); see scripts/manual/README.md" >&2; exit 1; }
command -v swiftc >/dev/null 2>&1 || { echo "error: swiftc not found; install the Xcode Command Line Tools (xcode-select --install)" >&2; exit 1; }
[ -f "$PDF" ] || { echo "error: PDF not found: $PDF" >&2; exit 1; }
# The re-run clean-up below deletes <outdir>/pages and <outdir>/images: refuse obviously wrong targets.
case "$(cd "$(dirname "$OUT")" 2>/dev/null && pwd)/$(basename "$OUT")" in
    /|/.|"$ROOT"|"$ROOT/."|"$HOME"|"$HOME/.") echo "error: refusing outdir '$OUT'; use a folder of its own, e.g. work/manuals/<name>" >&2; exit 1 ;;
esac

START=$(date +%s); echo "phase-1 start: $(date '+%H:%M:%S')"
mkdir -p "$ROOT/work/bin" "$OUT"
# Clean earlier renders and crops so a re-run starts fresh. This happens inside the script, so an
# agent running it needs no separate permission for a delete. text/ uses fixed page-NNN names and is
# simply overwritten.
rm -rf "$OUT/pages" "$OUT/images"
cp "$ROOT/scripts/manual/CONVENTIONS.md" "$OUT/CONVENTIONS.md"   # the phase-2 extraction spec

if [ ! -x "$BIN" ] || [ "$SRC" -nt "$BIN" ]; then
  echo "compiling render_pdf (swiftc)..."; swiftc -O "$SRC" -o "$BIN"
fi
if [ ! -x "$XBIN" ] || [ "$XSRC" -nt "$XBIN" ]; then
  echo "compiling crop_figures (swiftc)..."; swiftc -O "$XSRC" -o "$XBIN"
fi

echo "[1/3] rendering full pages -> $OUT/pages @ ${DPI}dpi"
"$BIN" "$PDF" "$OUT" "$DPI"
echo "[2/3] cropping embedded figures -> $OUT/images (from the page renders)"
"$XBIN" "$PDF" "$OUT" "$DPI"
echo "[3/3] chapter manifest"
python3 "$ROOT/scripts/manual/make_chapters.py" "$OUT"
echo "pages: $(ls "$OUT/pages" | wc -l | tr -d ' ') | figures: $(ls "$OUT/images" 2>/dev/null | wc -l | tr -d ' ') | text: $(ls "$OUT/text" | wc -l | tr -d ' ')"
echo "phase-1 done: $(date '+%H:%M:%S')  (elapsed $(( $(date +%s) - START ))s)"
