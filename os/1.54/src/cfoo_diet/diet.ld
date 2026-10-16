/* The CFO oscillator's render, lighter: in the CFO oscillator's own pad FUN_400f77da, where its render
   is, around its segfrac (0x400f7a1e..0x400f7a5c), which stays. make_diet.py checks both parts against
   the build and fills what they leave with the pad's fill. */
segfrac = 0x400f7a1e;	/* cfo_oscillator's: %d0 0..127 -> %d1 the segment, %d0 the fraction */
SECTIONS
{
  .diet_a 0x400f77da : { *(.diet_a) }
  .diet_b 0x400f7a5c : { *(.diet_b) }
  /DISCARD/ : { *(.text) *(.data) *(.bss) }
}
