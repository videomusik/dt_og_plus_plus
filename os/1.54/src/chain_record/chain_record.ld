/* Placement of chain_record.s: hook sites and landing pads in OS 1.54 MAIN OS (load addresses). */
SECTIONS
{
  .hook_thr     0x400767fc : { *(.hook_thr) }
  .hook_stop    0x4007687a : { *(.hook_stop) }
  .hook_arm     0x400768c2 : { *(.hook_arm) }
  .hook_rec_pos 0x400768fa : { *(.hook_rec_pos) }
  .hook_rec_len 0x40076900 : { *(.hook_rec_len) }
  .hook_enc     0x400a7f40 : { *(.hook_enc) }
  .hook_mem     0x400a8e7c : { *(.hook_mem) }
  .hook_fmt     0x400a8f48 : { *(.hook_fmt) }
  .hook_fmt_pop 0x400a8f60 : { *(.hook_fmt_pop) }
  .hook_armed   0x400a9026 : { *(.hook_armed) }
  .hook_armed_pop 0x400a9044 : { *(.hook_armed_pop) }
  .pad_len      0x400bf1cc : { *(.pad_len) }
  .pad_mem      0x400c1062 : { *(.pad_mem) }
  .pad_new      0x40124a6c : { *(.pad_new) }
  .pad_stl      0x40177104 : { *(.pad_stl) }
  .rodata_fmt   0x40252c00 : { *(.rodata_fmt) }
  /DISCARD/ : { *(.text) *(.data) *(.bss) }
}
