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
| Build options, one per stage after the first glide:
|   INERT    each hook only replays the instructions it replaced (the first code through a new hook)
|   OFFTEXT  PORT's value text: OFF for 0
|   SAVE     PORT and LEG saved with the sound, and their p-locks with the pattern
|   HOLDAMP  with LEG on, a legato note does not restart the amp envelope
| Assemble with port.ld; make_port.py does it.

	.set	NOTES,	0x80001f28	| stock: the trig's note sum per track (note << 16), 8 longs
	.set	GATEB,	0x800019f7	| stock: low byte of the gate mask 0x800019f4, bit = track
	.set	STATE,	0x439d1180	| this build: the portamento state (40 B, above .bss)
	.set	CUR,	0		|   8 longs: the note sum each track plays
	.set	MAGIC,	32		|   long: MAGICV once the block below is initialised
	.set	NEWB,	36		|   byte: bit t, a note-on on track t not yet seen by the glide
	.set	LEGB,	37		|   byte: bit t, that note-on came with track t's gate open
	.set	VALB,	38		|   byte: bit t, CUR[t] holds a note sum
	.set	HOLDB,	39		|   byte: bit t, this tick's note is legato with LEG on (HOLDAMP)
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

.ifdef OFFTEXT
| ---- PORT's value text: a display callable's invoker, (storage, value 8.8, buffer). For 0 the stock
| routine of the sends' text (0x400658d0), whose branch for 0 prints OFF; else the stock %d text.
port_text:
	tstl	%sp@(8)
	beqs	1f
	jmp	0x4005f8ce
1:	jmp	0x400658d0
.endif

.ifdef SAVE
| ---- the sound reader's hook: FUN_4007a236 at 0x4007a2aa, replacing `lea 0x401ac58c,%a0` between the
| clearing of the sound's values and its value loop, which writes slots 0..45 only. %a2 = the sound,
| %a3 = the stored record. PORT and LEG (slots 46, 47, the sound's +0x70) come from the record's spare
| words +0x78 and +0x7a, which the writer fills and the stock writer zeroes. Anything but a whole PORT
| of 0..127 and a LEG of 0 or 1 (a record converted from an older format may hold leftovers there)
| gives 0 for both.
rd_hook:
	movel	%a3@(0x78),%d0
	movel	%d0,%d1
	andil	#0x80fffeff,%d1
	beqs	1f
	clrl	%d0
1:	movel	%d0,%a2@(0x70)
	lea	0x401ac58c,%a0
	rts
.endif

.ifdef HOLDAMP
| ---- the amp envelope's restart mask: FUN_40077420 at 0x40078070, replacing `moveb %d3,%fp@(-36) ;
| moveb %d2,%fp@(-35)` (then a nop). FUN_400716c0 restarts a track's amp envelope for its bit in the
| byte at %fp@(-36), the note-on mask %d3; the tracks port_glide marked this tick (a legato note with
| LEG on) are left out. %d3 itself is kept for the code after.
amp_hook:
	mvzb	STATE+HOLDB,%d0
	notl	%d0
	andl	%d3,%d0
	moveb	%d0,%fp@(-36)
	moveb	%d2,%fp@(-35)
	clrb	STATE+HOLDB
	rts
.endif

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
.ifdef HOLDAMP
	clrw	%a0@(VALB)		| and no amp envelope held (HOLDB follows VALB)
.else
	clrb	%a0@(VALB)
.endif
1:	bset	%d5,%a0@(VALB)
	beqs	snap			| CUR not valid yet: start on the target
	mvzb	%a2@(PORTD),%d0		| PORT, 0..127
	bclr	%d5,%a0@(NEWB)
	beqs	glide			| no new note: keep gliding toward the target
.ifdef HOLDAMP
	tstb	%a2@(LEGD)		| LEG off: every new note glides
	beqs	glide
	btst	%d5,%a0@(LEGB)		| LEG on: a detached note starts on its pitch,
	beqs	snap
	bset	%d5,%a0@(HOLDB)		| a legato note glides, its amp envelope not restarted
.else
	tstl	%d0
	beqs	snap
	tstb	%a2@(LEGD)		| LEG off: every new note glides
	beqs	glide
	btst	%d5,%a0@(LEGB)		| LEG on: only a legato note glides
	beqs	snap
.endif
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

.ifdef SAVE
| ---- FUN_40079738(kind, index) -> value slot, rewritten in place (58 B): as stock (kind 16 the MIDI
| table 0x401ac444 for 0..35, kinds below 16 the sound table 0x401ac58c for 0..45, else 0), and for a
| kind below 16 the stored indices 46 and 47 give slots 46 and 47 (PORT, LEG). The pattern's p-lock
| reader FUN_4007abb2 maps a stored lock's index through it.
	.section .port_fwd,"ax"
port_fwd:
	movel	%sp@(8),%d0		| the index
	moveq	#16,%d1
	cmpl	%sp@(4),%d1		| the kind
	bcss	9f			| above 16: 0
	beqs	3f			| 16: MIDI
	moveq	#45,%d1
	cmpl	%d0,%d1
	bccs	4f			| 0..45: the table
	moveq	#47,%d1
	cmpl	%d0,%d1
	bccs	8f			| 46, 47: themselves
9:	clrl	%d0
8:	rts
3:	moveq	#35,%d1
	cmpl	%d0,%d1
	bcss	9b
	lea	0x401ac444,%a0
	bras	5f
4:	lea	0x401ac58c,%a0
5:	movel	%a0@(0,%d0:l:4),%d0
	rts

| ---- FUN_40079772(kind, slot) -> stored index, rewritten in place (58 B): as stock (kind 16 the MIDI
| table 0x401ac374 for 0..51, kinds below 16 the sound table 0x401ac4d4 for 0..45, else 0), and for a
| kind below 16 the slots 46 and 47 give the indices 46 and 47. The sound's stored mirror
| (Sound::vfunc_17) places a changed value through it.
	.section .port_inv,"ax"
port_inv:
	movel	%sp@(8),%d0		| the slot
	moveq	#16,%d1
	cmpl	%sp@(4),%d1		| the kind
	bcss	9f
	beqs	3f
	moveq	#45,%d1
	cmpl	%d0,%d1
	bccs	4f
	moveq	#47,%d1
	cmpl	%d0,%d1
	bccs	8f
9:	clrl	%d0
8:	rts
3:	moveq	#51,%d1
	cmpl	%d0,%d1
	bcss	9b
	lea	0x401ac374,%a0
	bras	5f
4:	lea	0x401ac4d4,%a0
5:	movel	%a0@(0,%d0:l:4),%d0
	rts
.endif

| ---- the names and tables, in the .rodata padding
	.section .port_names,"a"
s_port:	.asciz	"PORT"
s_portl: .asciz	"Portamento"
s_leg:	.asciz	"LEG"
s_legl:	.asciz	"Legato"
.ifdef OFFTEXT
	.balign	4
| PORT's text source for the display build: a callable's storage (unused), manager and invoker. The
| manager is the stock %d text's (0x40060c92), which on a copy only allocates a fresh storage byte.
port_tobj:
	.long	0, 0, 0x40060c92, port_text
.endif
.ifdef SAVE
	.balign	4
| Value slot -> stored index for a sound, 48 entries: the stock table 0x401ac4d4 (slots 0..45), then
| slots 46 and 47 at indices 46 and 47, the stored record's spare words +0x78 and +0x7a. The sound
| writer FUN_4007a5a0 (48 words instead of 46) and the p-lock writers FUN_4007adb2 and FUN_4007aefa
| read it in place of the stock table.
inv_slots:
	.long	0, 1, 3, 5, 7, 9, 11, 13, 15, 2, 4, 6, 8, 10, 12, 14
	.long	16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31
	.long	32, 33, 34, 35, 36, 45, 37, 38, 39, 40, 41, 42, 43, 44, 46, 47
.endif
