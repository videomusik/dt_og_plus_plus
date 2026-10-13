| SPDX-License-Identifier: CC0-1.0
| dtogpp-portamento: PORT and LEG on the TRIG page of every audio track, Digitakt OS 1.54. A track's note
| glides from the note it plays toward the trig's note instead of jumping. PORT sets the glide time (0 =
| off). With LEG on, only a legato note glides (one whose NoteOn comes while the last note's LEN still
| runs, or in the tick it runs out), and its amp envelope is not restarted; every other note starts on
| its own pitch.
|
| Three hooks in the audio ISR:
|   port_on    0x400779f6, the note-on's `lea NOTES,%a1`: marks the track's note as new, and as legato
|              when the last note's LEN countdown still runs
|   port_glide 0x40075690, the rate loop's `lea NOTES,%a0`: writes the glided note sum to the track's CUR
|              and returns %a0 = CUR, so the loop's own `movel %a0@(0,%fp:l:4),%d6` that follows reads it
|   amp_hook   0x40078070, the store of the amp envelope's restart masks: leaves out the tracks whose note
|              is legato with LEG on
| PORT and LEG are the sound's value slots 46 and 47 (descriptor rows 4 and 5, mod.json), saved by
| dtogpp-params; the ISR's per-track copy of the sound's values carries them, p-locks included.
|
| Exports dtogpp_port_notes: a long holding the address of CUR, the note sum each track plays (8 longs,
| note << 16, as the stock NOTES), for a mod whose own oscillator should play the glided note.

	.set	NOTES,	0x80001f28	| stock: the trig's note sum per track (note << 16), 8 longs
	.set	LENCD,	0x8000196c	| stock: the amp's LEN countdown per track (long), loaded at a NoteOn, 0 for INF
	.set	RELNOW,	-73		| stock: low byte of the ISR frame's %fp@(-76), this tick's countdowns run out
| PORT and LEG from the rate loop's %a2, the track's TUNE slot (17) in the engine's smoothed copy
| (0x80002794 + 106 x track): the integer byte of slot 46 and 47 in the ISR's own copy of the sound's
| values (0x80001502 + 106 x track + 2 x slot), which p-locks write and the smoothing does not delay.
	.set	PORTD,	0x8000155e - 0x80002794
	.set	LEGD,	0x80001560 - 0x80002794
| The state, in .bss (zero at boot):
	.set	CUR,	0		|   8 longs: the note sum each track plays
	.set	MAGIC,	32		|   long: MAGICV once the bytes below are initialised
	.set	NEWB,	36		|   byte: bit t, a note-on on track t not yet seen by the glide
	.set	LEGB,	37		|   byte: bit t, that note-on is legato
	.set	VALB,	38		|   byte: bit t, CUR[t] holds a note sum
	.set	HOLDB,	39		|   byte: bit t, this tick's note is legato with LEG on
	.set	MAGICV,	0x504f5254	| 'PORT'

	.section .bss
	.balign	4
state:	.skip	40

	.section .run,"ax"
	.balign	4
	.globl	dtogpp_port_notes
dtogpp_port_notes:
	.long	state + CUR

| ---- the note-on hook. In: %d2 = the track. Out: %a1 = NOTES, as the replaced lea left it. Keeps every
| other register; %a4 is loaded by the next instruction, and the condition codes are set again before any
| test. The NoteOn comes before the stock store loads this note's countdown (0x40077a72), and after this
| tick's countdown pass (0x400774dc): the countdown is the last note's.
port_on:
	bset	%d2,state+NEWB		| a new note on this track
	lea	LENCD,%a1
	tstl	%a1@(0,%d2:l:4)		| the last note's LEN still running?
	bgts	1f
	btst	%d2,%fp@(RELNOW)	| or run out in this very tick: a tie
	beqs	2f
1:	bset	%d2,state+LEGB		| legato
2:	lea	%a1@(NOTES-LENCD),%a1
	rts

| ---- the rate-loop hook. In: %fp = the track, %a2 its TUNE slot in the engine copy. Out: %a0 = CUR, with
| CUR[track] the note sum to play. Uses %d0, %d4, %d5 and %d6, which the loop sets again before it reads
| them; keeps the rest. The site's `moveq #3,%d1` and `movel %a0@(0,%fp:l:4),%d6` follow the call.
port_glide:
	lea	NOTES,%a0
	movel	%fp,%d5
	movel	%a0@(0,%d5:l:4),%d6	| the trig's note sum: the target
	lea	state,%a0
	movel	%a0@(MAGIC),%d0
	cmpil	#MAGICV,%d0
	beqs	1f
	movel	#MAGICV,%d0		| first call since power-up: no track's CUR is valid yet
	movel	%d0,%a0@(MAGIC)
	clrl	%a0@(NEWB)		| and no new note, legato bit or amp envelope held either
1:	bset	%d5,%a0@(VALB)
	beqs	snap			| CUR not valid yet: start on the target
	mvzb	%a2@(PORTD),%d0		| PORT, 0..127
	bclr	%d5,%a0@(NEWB)
	beqs	glide			| no new note: keep gliding toward the target
	bclr	%d5,%a0@(LEGB)		| take the note's legato bit
	sne	%d4
	tstb	%a2@(LEGD)		| LEG off: every new note glides
	beqs	glide
	tstb	%d4			| LEG on: a detached note starts on its pitch,
	beqs	snap
	bset	%d5,%a0@(HOLDB)		| a legato note glides, its amp envelope not restarted
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
	rts

| ---- the amp envelope's restart mask: FUN_40077420 at 0x40078070, replacing `moveb %d3,%fp@(-36) ;
| moveb %d2,%fp@(-35)`. FUN_400716c0 restarts a track's amp envelope for its bit in the byte at
| %fp@(-36), the note-on mask %d3; the tracks port_glide marked this tick (a legato note with LEG on) are
| left out. %d3 itself is kept for the code after.
amp_hook:
	mvzb	state+HOLDB,%d0
	notl	%d0
	andl	%d3,%d0
	moveb	%d0,%fp@(-36)
	moveb	%d2,%fp@(-35)
	clrb	state+HOLDB
	rts

| ---- PORT's value text: a display callable's invoker, (storage, value 8.8, buffer). For 0 the stock
| routine of the sends' text (0x400658d0), whose branch for 0 prints OFF; else the stock %d text.
port_text:
	tstl	%sp@(8)
	beqs	1f
	jmp	0x4005f8ce
1:	jmp	0x400658d0

| PORT's text source for the display build: a callable's storage (unused), manager and invoker. The
| manager is the stock %d text's (0x40060c92), which on a copy only allocates a fresh storage byte.
	.balign	4
port_tobj:
	.long	0, 0, 0x40060c92, port_text

s_port:	.asciz	"PORT"
s_portl: .asciz	"Portamento"
s_leg:	.asciz	"LEG"
s_legl:	.asciz	"Legato"
	.balign	2
