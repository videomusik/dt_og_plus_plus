# shellcheck shell=bash
# OS folder profile for Digitakt OS 1.54. Sourced by scripts/common.sh (os_load); defines variables
# and os_section_meta, runs nothing. Every value is measured from the stock file named here.
#
# Format: one plain NAME=value assignment per line, no export, no declare, no expansion and no comment
# on the same line. scripts/check_os_folders.py and scripts/identify.py read these lines with a regular
# expression instead of sourcing the file.

# ---- identity -----------------------------------------------------------------------------------
OS_ID=1.54
OS_LABEL="Digitakt OS 1.54"
# The version line the firmware tool prints for any file of this OS (-i).
OS_REPORTED_VERSION=1.54

# ---- the stock image ----------------------------------------------------------------------------
# Your own copy of Elektron's OS file. It is never committed (sysex/ is gitignored) and never shared.
# Every address in this OS folder is for exactly this file, so the scripts check its hash.
OS_STOCK_SYX_DEFAULT=sysex/Digitakt_OS1.54.syx
OS_STOCK_SYX_SHA256="f78ba80fa7b1da5fb0e1ff61ad61e9e71aafe79f4364fc49679f3651353e3cf6"
OS_STOCK_SYX_SIZE=1423776

# ---- the container ------------------------------------------------------------------------------
# The section ids of the ELE3 container (inspect.sh lists 5 meta, 2 DSP, 3 MAIN OS, 4 updater, 8 "?").
OS_CONTAINER_SECTION_IDS="2 3 4 5 8"
# MAIN OS is section id 3 in the Digitakt's ELE3 container.
OS_MAIN_ID=3
# MAIN OS (section 3) load address. File offset = load address - 0x40000400.
OS_MAIN_BASE=0x40000400
# Section 3 (MAIN OS), decompressed.
OS_STOCK_MAIN_SHA256="5c58bf9e3949ef09977c5fc007a61e8d026931f67f1621238379dfb8ee4d31a2"
OS_STOCK_MAIN_SIZE=2479680
# The sections os_section_meta describes. Section 8 is not ColdFire code (notes/stock_image.md), so the
# ColdFire analysis scripts do not take it.
OS_ANALYSIS_SECTIONS="main dsp updater"
# Digitakt OS 1.54 carries no signature trailer, so the extract.sh report (report.txt) holds no key
# material.
OS_SIGNATURE_TRAILER=none

# ---- Ghidra section metadata --------------------------------------------------------------------
# One row per analysis section: <section id, or a file name>  <load base>  <strip bytes>  <entry, or ->
# (the format and the recipe for deriving a row are in scripts/common.sh, above section_meta).
#
# Where the numbers come from (Digitakt OS 1.54):
#   main    : container dst 0x40000400; entry = first header word 0x400004e8; no strip.
#   dsp     : run base 0x80000414 for the code after the 24-byte inner header, measured from the code:
#             absolute jsr targets and string references (notes/stock_image.md, Section 2's run base).
#             Neither the container dst 0x03000900 nor header word 2 (0x80000eaa) is the base. No
#             simple entry.
#   updater : container dst 0x80000400; entry 0x80000492; no strip.
os_section_meta() {   # args: <section>  -> echoes: src base strip entry
    case "$1" in
        main)    echo "3 0x40000400 0 0x400004e8" ;;
        dsp)     echo "2 0x80000414 24 -" ;;
        updater) echo "4 0x80000400 0 0x80000492" ;;
        *) echo "error: no analysis metadata for section '$1' (use main, dsp or updater)" >&2; return 1 ;;
    esac
}
