/* Portamento placement: the note-on hook in FUN_400e6d1c (108 B), the rate-loop hook in FUN_400ee05e
   (122 B), the names in the .rodata padding after the CFO oscillator's tables. Both pads are vetted in
   notes/landing_pads.md; make_port.py checks every section against its pad and the stock bytes. */
SECTIONS
{
  .port_on    0x400e6d1c : { *(.port_on) }
  .port_glide 0x400ee05e : { *(.port_glide) }
  .port_names 0x40252f00 : { *(.port_names) }
  /DISCARD/ : { *(.text) *(.data) *(.bss) }
}
