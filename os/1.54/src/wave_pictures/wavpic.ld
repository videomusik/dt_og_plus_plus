/* CFOO's wave pictures placement: the new pad FUN_401044b6 (xxHash's 64-bit family, vetted in
   notes/landing_pads.md). make_wavpic.py checks the section against its pad and the bytes of the build. */
SECTIONS
{
  .wav_pad 0x401044b6 : { *(.wav_pad) }
  /DISCARD/ : { *(.text) *(.data) *(.bss) }
}
