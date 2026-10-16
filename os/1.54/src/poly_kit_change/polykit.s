| POLY's voice pools follow every change of the engine's kit, on Digitakt OS 1.54, for DT OG++.
|
| The engine plays the kit its kit pointer (0x800019ac) names; each of the project's 128 kits has its own
| place, so a pattern change moves the pointer to the new pattern's kit. POLY's pool map (groupSource,
| 0x439d1050) is built from the machines of that kit, and the build rebuilds it where the pointer is set:
| at a kit load (the voice build), on a machine change, and in the audio interrupt's set-active-kit event,
| which a pattern change made while playing goes through. A pattern change made while the sequencer is
| stopped sets the pointer through the routine at 0x4007739a instead (from the sequencer's set-pattern
| 0x4006fe8e; the live kit's change observer 0x40015d0e and a project load call it too), which rebuilds
| nothing: the map stayed the last pattern's until a set-active-kit event came, at a pattern end. So after
| a project load, a pattern chosen while stopped played the loaded pattern's pools.
|
| The change: the routine's last two instructions, which store the kit back as the argument and branch
| to the kit tail's reader 0x40077380, become a jump to pk_follow, which does the same with a call of the
| build's map rebuild (the source half, 0x400bf18c; poly_engine's) between them. The round-robin cursors
| are not touched, as in the set-active-kit event. The rebuild uses %d0, %d1, %a0 and %a1, which the
| routine's callers do not keep across the call, and reads the kit pointer the routine has just set.
|
| Assemble with polykit.ld; make_polykit.py does it.

	.set	KIT_TAIL, 0x40077380	| stock: read the kit's tail words (kit)
	.set	MAP_SRC, 0x400bf18c	| poly_engine: rebuild groupSource from the engine's kit; the cursors kept

	.section .kit_set,"ax"		| 0x400773be, in the routine at 0x4007739a
	jmp	pk_follow

	.section .pk_pad,"ax"		| 0x400bedf2, 16 B of POLY's pad span
pk_follow:
	movel	%d0,%sp@(4)		| the kit as the argument, as the routine did
	jsr	MAP_SRC
	jmp	KIT_TAIL
