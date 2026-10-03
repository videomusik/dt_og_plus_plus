# shellcheck shell=bash
# OS folder profile for Digitakt OS 1.52A. Sourced by scripts/common.sh (os_load); defines variables
# and os_section_meta, runs nothing. Every value is measured from the stock file named here.
#
# Format: one plain NAME=value assignment per line, no export, no declare, no expansion and no comment
# on the same line. scripts/check_os_folders.py and scripts/identify.py read these lines with a regular
# expression instead of sourcing the file.
#
# The stock hash is kept here as an anchor independent of os/1.52A/build/build.py and patch.json;
# scripts/check_os_folders.py checks that the three agree.

# ---- identity -----------------------------------------------------------------------------------
OS_ID=1.52A
OS_LABEL="Digitakt OS 1.52A"
# The version line the firmware tool prints for any file of this OS (-i).
OS_REPORTED_VERSION=1.52A

# ---- the stock image ----------------------------------------------------------------------------
# Your own copy of Elektron's OS file. It is never committed (sysex/ is gitignored) and never shared.
# Every address in this OS folder is for exactly this file, so the scripts check its hash.
OS_STOCK_SYX_DEFAULT=sysex/Digitakt_OS1.52A.syx
OS_STOCK_SYX_SHA256="01315133041dcdb8b432146190cc74fc8695c47d8466b0f31bd78cef96fa56a4"
OS_STOCK_SYX_SIZE=1162400

# ---- the container ------------------------------------------------------------------------------
# The section ids of the ELE3 container (inspect.sh lists 5 meta, 2 DSP, 3 MAIN OS, 4 updater).
OS_CONTAINER_SECTION_IDS="2 3 4 5"
# MAIN OS is section id 3 in the Digitakt's ELE3 container.
OS_MAIN_ID=3
# MAIN OS (section 3) load address. File offset = load address - 0x40000400.
OS_MAIN_BASE=0x40000400
# Section 3 (MAIN OS), decompressed.
OS_STOCK_MAIN_SHA256="59278368fbe86c9877fad68a578987050e21fc4b418a289cfa1d1351d8e864ee"
OS_STOCK_MAIN_SIZE=2221632
# The sections os_section_meta describes; sram is not a container section, so it is not listed.
OS_ANALYSIS_SECTIONS="main dsp updater"
# Digitakt OS 1.52A carries no signature trailer, so the extract.sh report (report.txt) holds no key
# material.
OS_SIGNATURE_TRAILER=none

# ---- Ghidra section metadata --------------------------------------------------------------------
# One row per analysis section: <section id, or a file name>  <load base>  <strip bytes>  <entry, or ->
# (the format and the recipe for deriving a row are in scripts/common.sh, above section_meta).
#
# Where the numbers come from (Digitakt OS 1.52A):
#   main    : container dst 0x40000400; entry = first header word 0x400004e8; no strip.
#   dsp     : run base = word 2 of the section's 24-byte inner header = 0x80000ec0 (NOT the container
#             dst 0x03000900); strip the 24-byte header; no simple entry (it starts mid-stream).
#   updater : container dst 0x80000400; entry 0x80000492; no strip.
#   sram    : not a section but the assembled 64 KB on-chip SRAM image written by
#             os/1.52A/scripts/build_sram_image.py; base 0x80000000; entry = the DSP's run base
#             0x80000ec0.
os_section_meta() {   # args: <section>  -> echoes: src base strip entry
    case "$1" in
        main)    echo "3 0x40000400 0 0x400004e8" ;;
        dsp)     echo "2 0x80000ec0 24 -" ;;
        updater) echo "4 0x80000400 0 0x80000492" ;;
        sram)    echo "sram_unified.bin 0x80000000 0 0x80000ec0" ;;
        *) echo "error: no analysis metadata for section '$1' (use main, dsp, updater or sram)" >&2; return 1 ;;
    esac
}
