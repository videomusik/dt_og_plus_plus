/* The modulation matrix's placement: the code in the free part of the wave pictures' pad FUN_401044b6
   (xxHash's 64-bit family, vetted in notes/landing_pads.md), from the end of the wave pictures' code; the
   key dispatcher's call; words of LfoPageView's and KeyboardView's vtables; the audio tick's call of the
   LFO tick; the kit writer's and loader's hooks; the knob cell's call of a parameter's name. Each stage
   has those of its options. make_mm.py checks each against the build. */
SECTIONS
{
  .hook_key 0x4000b770 : { *(.hook_key) }
  .vt_draw  0x401af868 : { *(.vt_draw) }
  .vt_enc   0x401af89c : { *(.vt_enc) }
  .vt_title 0x401af8a4 : { *(.vt_title) }
  .vt_enc4  0x401af938 : { *(.vt_enc4) }
  .hook_pass 0x40077eac : { *(.hook_pass) }
  .vt_knob  0x401af8f4 : { *(.vt_knob) }
  .vt_set   0x401af8fc : { *(.vt_set) }
  .vt_kbkey 0x40182458 : { *(.vt_kbkey) }
  .vt_pget  0x401af8ac : { *(.vt_pget) }
  .hook_kwrite 0x4007a76e : { *(.hook_kwrite) }
  .hook_kload 0x4007a4ce : { *(.hook_kload) }
  .call_name 0x40030daa : { *(.call_name) }
  .vt_tick  0x401af884 : { *(.vt_tick) }
  .mm_pad   0x401045c0 : { *(.mm_pad) }
  /DISCARD/ : { *(.text) *(.data) *(.bss) }
}
