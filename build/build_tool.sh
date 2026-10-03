#!/usr/bin/env bash
# Build the firmware tool that DT OG++ is pinned to, from your own clone of it.
#
#   upstream  https://github.com/mischa85/elektron-firmware-tool   (MIT License)
#   commit    065d18f4195793e61891e387813488ee59f6d1ca
#   source    ../elektron-firmware-tool, a clone next to this repository that you make once
#   patch     build/tool_patches/cap_window_1mb.patch             (1 MB back-reference window)
#   output    tool/bin/elektron-firmware-tool-capped
#
# Usage:
#   bash build/build_tool.sh                 build from the clone in ../elektron-firmware-tool
#   bash build/build_tool.sh --src DIR       build from a clone in DIR instead
#   bash build/build_tool.sh --bin-dir DIR   write the binary to DIR instead of tool/bin
#
# Make the clone once, from the repository root:
#   git clone https://github.com/mischa85/elektron-firmware-tool ../elektron-firmware-tool
#   git -C ../elektron-firmware-tool checkout 065d18f
#
# This script never clones, fetches or changes the clone. It copies the clone's *.c and *.h
# files into a build folder under tool/, checks every copy against the SHA-256 list below
# (that is how it knows the clone is at the pinned commit), patches the copies there, checks
# the result again, and compiles it with the system C compiler (set CC to use another one).
# Needs: bash, cc, patch, and sha256sum or shasum.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
UPSTREAM="https://github.com/mischa85/elektron-firmware-tool"
PIN="065d18f4195793e61891e387813488ee59f6d1ca"
PIN_SHORT="065d18f"
DEFAULT_SRC="../elektron-firmware-tool"   # relative to the repository root
PATCH_FILE="$ROOT/build/tool_patches/cap_window_1mb.patch"
NAME="elektron-firmware-tool-capped"
BUILD_PARENT="$ROOT/tool"
SRC=""
BIN_DIR="$ROOT/tool/bin"

# Every C source file of the pinned commit (the complete set), and compress.c after the patch.
PINNED_SOURCES="
f7af548480e9e49f571ffe4cf4d5b6957cde9d5eaaef123c73c2e951abf7a223  compress.c
9f2c1f3f7889544d34bfbe48731cc2d9d9024ce74a809b8b7a9ae374a8b930d0  decompress.c
09d5aba7a585478bf592ab56330a4c5723b590cce0820b8e7fdfb748c35159e4  format.h
89d1d117331c63f499eef46bc4129079b894da8a38884ea5d9c0eb7c95d1c99f  integrity.c
e4697d2167017ecc52914b40a8fe68d7cf9cec2ecb3b2e632f1faf07e18627cf  main.c
"
PATCHED_COMPRESS_C="109df20e94a5cb606d74bf378c03a32af0d01d7511ca40ba6ae4d58e9d92001b"

die() { printf 'error: %s\n' "$1" >&2; exit 1; }

usage() {
    sed -n '10,17p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

if command -v sha256sum >/dev/null 2>&1; then
    sha256() { sha256sum "$1" | cut -d' ' -f1; }
elif command -v shasum >/dev/null 2>&1; then
    sha256() { shasum -a 256 "$1" | cut -d' ' -f1; }
else
    die "need sha256sum or shasum"
fi

while [ $# -gt 0 ]; do
    case "$1" in
        --src)     [ $# -ge 2 ] || die "--src needs a folder"; SRC="$2"; shift 2 ;;
        --bin-dir) [ $# -ge 2 ] || die "--bin-dir needs a folder"; BIN_DIR="$2"; shift 2 ;;
        -h|--help) usage; exit 0 ;;
        *)         usage >&2; die "unknown argument: $1" ;;
    esac
done

# The clone to build from, and how to name it in messages.
if [ -z "$SRC" ]; then
    SRC="$ROOT/$DEFAULT_SRC"
    SHOWN="$DEFAULT_SRC"
    WHERE="from the repository root, run"
else
    SHOWN="$SRC"
    WHERE="run"
fi

# The steps that make the clone, for the error messages below.
how_to_get_it() {
    cat >&2 <<EOF

To get the firmware tool's source at the pinned commit, $WHERE:

    git clone $UPSTREAM $SHOWN
    git -C $SHOWN checkout $PIN_SHORT

If the clone already exists, the second command is enough, and 'git -C $SHOWN status'
must show no changed files. Then run build/build_tool.sh again.
EOF
}

[ -f "$PATCH_FILE" ] || die "missing $PATCH_FILE"
command -v "${CC:-cc}" >/dev/null 2>&1 || die "no C compiler (${CC:-cc}) found"
command -v patch >/dev/null 2>&1 || die "the 'patch' program is needed"

# ---- 1. the clone must exist; it is only ever read
if [ ! -d "$SRC" ]; then
    printf 'error: no elektron-firmware-tool source at %s\n' "$SHOWN" >&2
    how_to_get_it
    exit 1
fi
SRC="$(cd "$SRC" && pwd)"

# ---- 2. copy its sources into a build folder under tool/
mkdir -p "$BUILD_PARENT"
WORK="$(mktemp -d "$BUILD_PARENT/build.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
mkdir "$WORK/src"
for f in "$SRC"/*.c "$SRC"/*.h; do
    [ -f "$f" ] || continue
    cp "$f" "$WORK/src/"
done

# ---- 3. the copies must be exactly the sources of the pinned commit
echo "== checking the sources from $SRC"
bad=0
while read -r want file; do
    [ -n "$want" ] || continue
    if [ ! -f "$WORK/src/$file" ]; then
        echo "   missing $file" >&2; bad=1; continue
    fi
    got="$(sha256 "$WORK/src/$file")"
    if [ "$got" != "$want" ]; then
        echo "   $file: SHA-256 $got, expected $want" >&2; bad=1
    fi
done <<EOF
$PINNED_SOURCES
EOF
for f in "$WORK/src"/*; do
    [ -e "$f" ] || continue
    name="$(basename "$f")"
    known=0
    while read -r _sum file; do
        if [ "$file" = "$name" ]; then known=1; fi
    done <<EOF
$PINNED_SOURCES
EOF
    [ "$known" -eq 1 ] || { echo "   unexpected source file $name" >&2; bad=1; }
done
if [ "$bad" -ne 0 ]; then
    printf 'error: %s is not an unmodified checkout of commit %s\n' "$SHOWN" "$PIN" >&2
    echo "       (git's line-ending conversion on checkout, core.autocrlf, also changes the files)" >&2
    how_to_get_it
    exit 1
fi

# ---- 4. patch the copies and compile them
echo "== applying $(basename "$PATCH_FILE")"
patch --forward --strip=1 --directory="$WORK/src" < "$PATCH_FILE" > "$WORK/patch.log" 2>&1 \
    || { cat "$WORK/patch.log" >&2; die "the patch did not apply"; }
[ "$(sha256 "$WORK/src/compress.c")" = "$PATCHED_COMPRESS_C" ] || die "patched compress.c is not the expected result"
echo "== compiling with ${CC:-cc}"
"${CC:-cc}" -O2 -Wall -Wextra -o "$WORK/$NAME" "$WORK/src"/*.c
mkdir -p "$BIN_DIR"
BIN_DIR="$(cd "$BIN_DIR" && pwd)"
mv -f "$WORK/$NAME" "$BIN_DIR/$NAME"
echo "== built $BIN_DIR/$NAME"
