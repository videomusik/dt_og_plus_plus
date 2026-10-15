/* POLY's pools on every kit change: the end of the kit pointer's setter (the routine at 0x4007739a) and
   16 B of POLY's pad span FUN_400bed3a..0x400bee6a (vetted in notes/landing_pads.md), free in the build.
   make_polykit.py checks both against the stock image and the build. */
SECTIONS
{
  .kit_set 0x400773be : { *(.kit_set) }
  .pk_pad  0x400bedf2 : { *(.pk_pad) }
  /DISCARD/ : { *(.text) *(.data) *(.bss) }
}
