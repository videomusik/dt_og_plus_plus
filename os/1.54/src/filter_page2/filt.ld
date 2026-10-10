/* FILTER page 2 placement: four new pads, vetted in notes/landing_pads.md, the names in the .rodata
   padding before portamento's names, and KEY's text in two leftovers of Chain Recording's pads. make_filt.py checks every section against its pad and the bytes
   of the build. */
SECTIONS
{
  .filt_ved   0x400d266e : { *(.filt_ved) }
  .filt_key   0x40178f20 : { *(.filt_key) }
  .filt_rd    0x40178e02 : { *(.filt_rd) }
  .filt_ext   0x401778a4 : { *(.filt_ext) }
  .filt_names 0x40252ed9 : { *(.filt_names) }
  .filt_txt1  0x40124b24 : { *(.filt_txt1) }
  .filt_txt2  0x401282ce : { *(.filt_txt2) }
  /DISCARD/ : { *(.text) *(.data) *(.bss) }
}
