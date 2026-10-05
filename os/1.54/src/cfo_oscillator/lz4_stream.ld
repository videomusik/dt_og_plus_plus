/* CFO oscillator placement: all code in FUN_400f77da (LZ4's streaming compressor, 2,372 B), the data in
   the .rodata padding below the icons. FUN_400f77da is not a leaf, so this placement needs a pad the
   vetting recipe's step 1 does not admit: see notes/features/cfo_oscillator.md. */
SECTIONS
{
  .hook_fill  0x40077fc8 : { *(.hook_fill) }
  .cfo_code   0x400f77da : { *(.cfo_main) *(.cfo_track) *(.cfo_pitch) *(.cfo_wave) *(.cfo_gains) *(.cfo_osc) *(.cfo_mix) }
  .cfo_data   0x40252724 : { *(.cfo_data) }
  /DISCARD/ : { *(.text) *(.data) *(.bss) }
}
