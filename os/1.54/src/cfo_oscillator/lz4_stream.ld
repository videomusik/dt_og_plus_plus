/* CFO oscillator placement: all code in FUN_400f77da (LZ4's streaming compressor, 2,372 B), the data in
   the .rodata padding below the icons. FUN_400f77da is not a leaf, so this placement needs a pad the
   vetting recipe's step 1 does not admit: see notes/features/cfo_oscillator.md. */
SECTIONS
{
  .hook_fill  0x40077fc8 : { *(.hook_fill) }
  .hook_layout 0x400657e6 : { *(.hook_layout) }
  .hook_slots 0x40078f72 : { *(.hook_slots) }
  .hook_popup 0x40032d16 : { *(.hook_popup) }
  .hook_pic   0x4000f2bc : { *(.hook_pic) }
  .hook_ctext 0x4000f324 : { *(.hook_ctext) }
  .hook_encobj 0x40032b74 : { *(.hook_encobj) }
  .hook_samp  0x4003b5a0 : { *(.hook_samp) }
  .cfo_code   0x400f77da : { *(.cfo_main) *(.cfo_track) *(.cfo_pitch) *(.cfo_wave) *(.cfo_gains) *(.cfo_osc) *(.cfo_mix) *(.cfo_layout) *(.cfo_rename) *(.cfo_slots) *(.cfo_range) *(.cfo_display) }
  /* the names and tables follow the code; from S20 on make_cfo.py places them in the .rodata padding
     after the icon instead (ld --section-start), and the pad holds code only */
  .cfo_names  ALIGN(4) : { *(.cfo_names) }
  .cfo_data   0x40252724 : { *(.cfo_data) }
  .cfo_icon   0x40252c2c : { *(.cfo_icon) }
  /DISCARD/ : { *(.text) *(.data) *(.bss) }
}
