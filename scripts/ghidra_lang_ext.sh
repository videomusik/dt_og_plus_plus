#!/usr/bin/env bash
# Build and install the ColdFire+EMAC Ghidra language extension (see scripts/ghidra_ext/README.md).
#
#   ./scripts/ghidra_lang_ext.sh            # build + install
#   ./scripts/ghidra_lang_ext.sh --check    # report what is installed, build nothing
#
# Ghidra's stock ColdFire spec cannot decode `movclr.l ACCx,Rx`, which ends most MAC audio loops,
# cannot match the mac.l-with-load form whose Rw is an address register, and mis-sizes the (d16,An)
# forms of mac.l/msac.l, so disassembly stops or derails there and the rest of those functions is lost.
# This applies our four-hunk patch to a COPY of Ghidra's 68000.sinc, compiles it, and installs the
# result as a separate language (68000:BE:32:ColdfireEMAC) in Ghidra's USER extension folder, never
# in the Ghidra install tree, so upgrading Ghidra cannot silently revert it.
#
# Re-run after a Ghidra upgrade: the user extension path carries the version. If Ghidra changed
# 68000.sinc upstream, the patch fails loudly here instead of yielding a stale language.
#
# Ghidra's own files are not kept in this repo; the build folder is under work/ (gitignored).
# The patch and the two small spec files are derived from Ghidra's Apache-2.0 sources; see
# scripts/ghidra_ext/NOTICE.md.

source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
ghidra_env

EXT_NAME="ColdfireEMAC"
LANG_ID="68000:BE:32:ColdfireEMAC"
src_dir="$REPO_ROOT/scripts/ghidra_ext"
stock="$GHIDRA_INSTALL_DIR/Ghidra/Processors/68000/data/languages"
build="work/ghidra_ext/build"

[ -d "$stock" ] || { echo "error: stock 68000 language folder not found at $stock (docs/toolchain.md section 4)" >&2; exit 1; }
[ -x "$GHIDRA_INSTALL_DIR/support/sleigh" ] || { echo "error: no sleigh compiler at $GHIDRA_INSTALL_DIR/support/sleigh" >&2; exit 1; }

ver="$(sed -n 's/^application\.version=//p' "$GHIDRA_INSTALL_DIR/Ghidra/application.properties")"
[ -n "$ver" ] || { echo "error: could not read application.version from the Ghidra install" >&2; exit 1; }

# Ghidra's user settings root is platform-specific; the Extensions folder lives inside it.
# ⚠️ The non-Darwin path is not tested: Ghidra 11.1 and later probably use
# ${XDG_CONFIG_HOME:-$HOME/.config}/ghidra/ghidra_<ver>_PUBLIC on Linux instead.
case "$(uname -s)" in
    Darwin) user_root="$HOME/Library/ghidra/ghidra_${ver}_PUBLIC" ;;
    *)      user_root="$HOME/.ghidra/.ghidra_${ver}_PUBLIC" ;;
esac
ext_dir="$user_root/Extensions/$EXT_NAME"

if [ "${1:-}" = "--check" ]; then
    banner "installed state (Ghidra $ver)"
    if [ -f "$ext_dir/data/languages/coldfire_emac.sla" ]; then
        echo "installed : $ext_dir"
        echo "language  : $LANG_ID"
        ls -l "$ext_dir/data/languages/"
    else
        echo "NOT installed for Ghidra $ver (expected $ext_dir)"
        echo "run ./scripts/ghidra_lang_ext.sh to build and install"
    fi
    exit 0
fi

banner "patch a copy of Ghidra's 68000.sinc (Ghidra $ver)"
rm -rf "$build" && mkdir -p "$build"
cp "$stock/68000.sinc" "$build/68000.sinc"
cp "$src_dir/coldfire_emac.slaspec" "$build/"
# --forward so a re-run over an already-patched tree fails loudly instead of reverse-applying.
patch --forward --strip=1 --directory="$build" < "$src_dir/coldfire_emac.patch" \
    || { echo "error: the patch did not apply; Ghidra $ver may have changed 68000.sinc (rebase scripts/ghidra_ext/coldfire_emac.patch)" >&2; exit 1; }

banner "compile with sleigh"
( cd "$build" && "$GHIDRA_INSTALL_DIR/support/sleigh" coldfire_emac.slaspec coldfire_emac.sla ) \
    > "$build/sleigh.log" 2>&1 || { echo "error: sleigh failed; see $build/sleigh.log" >&2; exit 1; }
if grep -qE 'ERROR|Exception' "$build/sleigh.log"; then
    echo "error: sleigh reported errors; see $build/sleigh.log" >&2; grep -E 'ERROR|Exception' "$build/sleigh.log" >&2; exit 1
fi
[ -f "$build/coldfire_emac.sla" ] || { echo "error: sleigh produced no .sla" >&2; exit 1; }
grep -E 'WARN' "$build/sleigh.log" | sed 's/^/  /' || true

banner "assemble the extension"
rm -rf "$build/$EXT_NAME"
mkdir -p "$build/$EXT_NAME/data/languages"
: > "$build/$EXT_NAME/Module.manifest"          # empty, as Ghidra's own processor modules are
cat > "$build/$EXT_NAME/extension.properties" <<EOF
name=$EXT_NAME
description=Motorola ColdFire with the EMAC movclr.l and mac.l/msac.l-with-load decoding gaps fixed. Built from scripts/ghidra_ext/ in the DT OG++ repo.
author=DT OG++ contributors
createdOn=
version=$ver
EOF
# Ghidra re-validates the .sla against its .slaspec when it loads a language and recompiles if they
# disagree; shipping the compiled .sla alone makes it refuse the language outright. So the SOURCE
# (slaspec + the patched 68000.sinc) must be installed too. That is a build output in the user's
# Ghidra folder, not repo content: this repo still tracks only our patch.
cp "$build/coldfire_emac.sla" "$build/coldfire_emac.slaspec" "$build/68000.sinc" \
   "$src_dir/coldfire_emac.ldefs" "$build/$EXT_NAME/data/languages/"
# At run time Ghidra also needs the compiler/processor specs; they are Ghidra's, copied from the local
# install.
for f in 68000.pspec 68000.cspec 68000_register.cspec 68000.dwarf; do
    cp "$stock/$f" "$build/$EXT_NAME/data/languages/"
done

banner "install to the user extension folder"
mkdir -p "$user_root/Extensions"
rm -rf "$ext_dir"
cp -R "$build/$EXT_NAME" "$ext_dir"
echo "installed : $ext_dir"
echo "language  : $LANG_ID"
echo
echo "A program is bound to the language it was imported with, so use this by importing into a NEW"
echo "project (GHIDRA_LANG_VARIANT=emac ./scripts/ghidra_analyze.sh dt main). The stock"
echo "68000:BE:32:Coldfire projects are untouched and stay available to compare."
