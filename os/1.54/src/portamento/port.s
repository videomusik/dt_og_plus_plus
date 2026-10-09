| Portamento for DT OG++ on Digitakt OS 1.54: PORT and LEG on the TRIG page of every audio track. A
| track's note glides from the note it plays toward the trig's note instead of jumping, for samples (the
| sampler's pitch) and for the CFO oscillator (its OSC1 note). PORT sets the glide time (0 = off); with
| LEG on, only a legato note (one whose trig comes while the last note still sounds) glides, and every
| other note starts on its own pitch.
|
| Two hooks in the audio ISR:
|   port_on    at the note-on's store of the trig's note (0x400779f6, replacing `lea NOTES,%a1`): marks
|              the track's note as new, and whether its gate was still open (legato).
|   port_glide in the per-track rate loop (0x40075690, replacing `lea NOTES,%a0` and the `movel` of the
|              note sum into %d6): returns the glided note sum in %d6 instead of the trig's.
| PORT and LEG are sound parameters in the sound's free value slots 46 and 47 (descriptor rows 4 and 5,
| make_port.py); the ISR's per-track copy of the sound's values carries them, p-locks included.
|
| With --defsym INERT=1 each hook only replays the instructions it replaced (the first code through a
| new hook). Assemble with port.ld; make_port.py does it.

	.set	NOTES,	0x80001f28	| stock: the trig's note sum per track (note << 16), 8 longs
	.set	GATEB,	0x800019f7	| stock: low byte of the gate mask 0x800019f4, bit = track
	.set	STATE,	0x439d1180	| this build: the portamento state (40 B, above .bss)
	.set	CUR,	0		|   8 longs: the note sum each track plays
	.set	MAGIC,	32		|   long: MAGICV once the block below is initialised
	.set	NEWB,	36		|   byte: bit t, a note-on on track t not yet seen by the glide
	.set	LEGB,	37		|   byte: bit t, that note-on came with track t's gate open
	.set	VALB,	38		|   byte: bit t, CUR[t] holds a note sum
	.set	MAGICV,	0x504f5254	| 'PORT'
| PORT and LEG from the rate loop's %a2, the track's TUNE slot (17) in the engine's smoothed copy
| (0x80002794 + 106 x track): the integer byte of slot 46 and 47 in the ISR's own copy of the sound's
| values (0x80001502 + 106 x track + 2 x slot), which p-locks write and the smoothing does not delay.
	.set	PORTD,	0x8000155e - 0x80002794
	.set	LEGD,	0x80001560 - 0x80002794

| ---- the note-on hook. In: %d2 = the track. Out: %a1 = NOTES, as the replaced lea left it. Keeps every
| other register; %a4 is loaded by the next instruction, and the condition codes are set again before
| any test.
	.section .port_on,"ax"
port_on:
.ifndef INERT
	lea	STATE,%a1
	bset	%d2,%a1@(NEWB)		| a new note on this track
	btst	%d2,GATEB		| its gate, as the last note left it
	beqs	1f
	bset	%d2,%a1@(LEGB)		| still open: legato
	bras	2f
1:	bclr	%d2,%a1@(LEGB)
2:
.endif
	lea	NOTES,%a1
	rts

| ---- the rate-loop hook. In: %fp = the track, %a2 its TUNE slot in the engine copy. Out: %d6 = the note
| sum to play. Uses %d0, %d4, %d5 and %a0, which the loop sets again before it reads them; keeps the
| rest. The site's `moveq #3,%d1` follows the call.
	.section .port_glide,"ax"
port_glide:
	lea	NOTES,%a0
.ifdef INERT
	movel	%a0@(0,%fp:l:4),%d6
.else
	movel	%fp,%d5
	movel	%a0@(0,%d5:l:4),%d6	| the trig's note sum: the target
	lea	STATE,%a0
	movel	%a0@(MAGIC),%d0
	cmpil	#MAGICV,%d0
	beqs	1f
	movel	#MAGICV,%d0		| first call since power-up: no track's CUR is valid yet
	movel	%d0,%a0@(MAGIC)
	clrb	%a0@(VALB)
1:	bset	%d5,%a0@(VALB)
	beqs	snap			| CUR not valid yet: start on the target
	mvzb	%a2@(PORTD),%d0		| PORT, 0..127
	bclr	%d5,%a0@(NEWB)
	beqs	glide			| no new note: keep gliding toward the target
	tstl	%d0
	beqs	snap
	tstb	%a2@(LEGD)		| LEG off: every new note glides
	beqs	glide
	btst	%d5,%a0@(LEGB)		| LEG on: only a legato note glides
	beqs	snap
glide:
	tstl	%d0			| PORT 0: no glide
	beqs	snap
	muluw	%d0,%d0			| k = 1 + PORT^2 / 8: a step of 1/k of the distance per tick
	lsrl	#3,%d0
	addql	#1,%d0
	movel	%d6,%d4
	subl	%a0@(CUR,%d5:l:4),%d4
	divsl	%d0,%d4
	tstl	%d4
	beqs	snap			| less than one step left: arrive
	addl	%a0@(CUR,%d5:l:4),%d4
	movel	%d4,%d6
snap:
	movel	%d6,%a0@(CUR,%d5:l:4)
.endif
	rts

| ---- the names, in the .rodata padding
	.section .port_names,"a"
s_port:	.asciz	"PORT"
s_portl: .asciz	"Portamento"
s_leg:	.asciz	"LEG"
s_legl:	.asciz	"Legato"
