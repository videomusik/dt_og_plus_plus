| CFO oscillator for DT OG++ on Digitakt OS 1.54: a 3-oscillator 8-bit wavetable synth with FM, rendered
| into a track's audio buffer where the sampler would put its samples. Everything after that point (the
| level stage, SRR, the filters and their envelope, the amp envelope, the mix and the effects) runs
| unchanged on it.
|
| PROTOTYPE STAGE: the synth plays on any ONESHOT track whose SAMP is OFF (sample slot 0), and reads
| its controls from that track's SRC page. With --defsym MACHINE5=1 it plays on a track set to machine 5
| (CFOO) instead, and the controls are the same eight SRC parameters:
|   TUNE   pitch, as for a sample            PLAY   FM source: 0 OSC2, 1 OSC2+3, 2 OSC3, 3 none
|   BR     oscillator mix: OSC1, 1+2, 1+2+3, 2+3 (crossfaded)
|   STRT   OSC1 wave       LEN    FM amount       LOOP   OSC2 and OSC3 wave
|   LEV    level, as for a sample (with velocity)
| OSC2 sits an octave below OSC1, OSC3 an octave and a fifth above. A wave value morphs SIN -> TRI ->
| SAW -> SQR. The pitch follows the trig's note and TUNE through the stock pitch table.
|
| Assemble with cfo.ld after generating waves.inc (make_waves.py); make_cfo.py does both.

	.set	FILL,	0x40072478	| stock: the per-track level stage, (a18, engine), first after the lanes
	.set	MACH,	0x4199f466	| stock: machine type per track, this tick (0 = ONESHOT)
	.set	NOTES,	0x80001f28	| stock: note per track, MIDI note << 16
	.set	VOICES,	0x8000edc4	| stock: per-voice render state, stride 0x5e; +0x28 = voice on
	.set	LEVEL,	0x40074c60	| stock: (x, LEV) -> (LEV/127)^2 x (x/127), Q31, on the EMAC
	.set	VELS,	0x80001f18	| stock: the level's x per track, word (very probably the velocity)
	.set	TRIGS,	0x8000122c	| stock: the lanes' trig mask for the next tick, bit = track
	.set	PITCH,	0x4019b4c0	| stock: pitch ratios, 2^(24 + n/12) at index (note sum) / 384
	.set	PHASES,	0x439d1100	| this build: phase accumulators, 8 tracks x 3 oscillators (96 B)
	.set	LEVELS,	0x439d1160	| this build: the level each track ended its last tick on (8 longs)
	.set	CFOO,	5		| the machine number of the CFO oscillator

| The frame cfo_pad builds; every routine reaches it through %a6.
	.set	S2,	0		| 32 words: OSC2's samples
	.set	S3,	64		| 32 words: OSC3's samples
	.set	G1,	128		| word: mix gain of OSC1 (0..256; the three sum to 256)
	.set	G2,	130
	.set	G3,	132
	.set	M2,	136		| long: FM mask for OSC2 (0 or -1)
	.set	M3,	140		| long: FM mask for OSC3
	.set	FMS,	144		| long: FM scale, OSC1's step / 8192 x LEN
	.set	LCUR,	148		| long: the level, Q31 >> 16, << 5, ramping over the tick
	.set	TRK,	152		| long: the track
	.set	NSUM,	156		| long: OSC1's note sum (note << 16, TUNE, the table offset)
	.set	STEP1,	160		| long: OSC1's phase step
	.set	LSTEP,	164		| long: LCUR's step per sample
	.set	REGS,	168		| saved %d2-%d7/%a2-%a6, 44 B
	.set	FRAME,	212
	.set	A18,	FRAME+4		| the stock call's arguments: the audio buffers
	.set	ENG,	FRAME+8		| and the engine object

| ---- the hook: the audio ISR's call of FILL after the two lanes

	.section .hook_fill,"ax"	| 0x40077fc8: jsr FILL
	jsr	cfo_pad

| ---- the dispatcher: every audio tick, before the level stage

	.section .cfo_main,"ax"
cfo_pad:
	lea	%sp@(-FRAME),%sp
	moveml	%d2-%d7/%a2-%a6,%sp@(REGS)
	movea.l	%sp,%a6
	clrl	%a6@(TRK)
1:	movel	%a6@(TRK),%d0
	lea	MACH,%a0
.ifdef MACHINE5
	mvzb	%a0@(0,%d0:l),%d1	| the machine this track plays this tick
	subql	#CFOO,%d1
	bnes	2f
	movea.l	%a6@(ENG),%a5
	mulu.w	#0x6a,%d0
	adda.l	%d0,%a5			| this track's engine block
.else
	tstb	%a0@(0,%d0:l)		| ONESHOT
	bnes	2f
	movea.l	%a6@(ENG),%a5
	mulu.w	#0x6a,%d0
	adda.l	%d0,%a5			| this track's engine block
	tstb	%a5@(0x3a)		| SAMP: no sample
	bnes	2f
.endif
	jsr	track
2:	addql	#1,%a6@(TRK)
	moveq	#8,%d0
	cmpl	%a6@(TRK),%d0
	bnes	1b
	moveml	%sp@(REGS),%d2-%d7/%a2-%a6
	lea	%sp@(FRAME),%sp
	jmp	FILL

| ---- one track: %a5 = its engine block (SRC slots 17..24 at +0x34..+0x42, 8.8)

	.section .cfo_track,"ax"
.ifdef KNOBS
| CFOO's own knobs (S16). The SRC slots, by knob: A +0x34 OSC1 wave (4..88), B +0x36 FM source (0 off,
| 1 OSC2, 2 OSC2+3, 3 OSC3), C +0x38 OSC2 wave, D +0x3a OSC3 wave (0..127), E +0x3c the mix (0..120),
| F +0x3e FM amount (0..120), G +0x40 OSC2 detune, H +0x42 OSC3 detune (0..98). Values outside these
| ranges can still arrive (MIDI, LFOs scale to ONESHOT's wider ranges), so each is clamped here.
track:
	movel	%a6@(TRK),%d0		| its three phases
	movel	%d0,%d1
	addl	%d0,%d0
	addl	%d1,%d0
	lsll	#2,%d0
	lea	PHASES,%a4
	adda.l	%d0,%a4
	movel	%a6@(TRK),%d0		| OSC1's note sum: the note + the table offset (no TUNE)
	lea	NOTES,%a0
	movel	%a0@(0,%d0:l:4),%d2
	addil	#0x30000,%d2
	movel	%d2,%a6@(NSUM)
	movel	%d2,%d0
	jsr	pitch
	movel	%d0,%a6@(STEP1)
	moveq	#13,%d1			| FM scale: step / 8192 x F
	lsrl	%d1,%d0
	mvzb	%a5@(0x3e),%d1
	mulsl	%d1,%d0
	movel	%d0,%a6@(FMS)
.ifdef KNOBS2
	mvzb	%a5@(0x36),%d0		| B: 0 OSC2, 1 OSC2+3, 2 OSC3 (above 2 as 2)
	moveq	#2,%d1
	cmpl	%d0,%d1
	bccs	1f
	movel	%d1,%d0
1:	moveq	#2,%d1			| FM from OSC2 for 0 and 1
	cmpl	%d1,%d0
	scs	%d1
	extbl	%d1
	movel	%d1,%a6@(M2)
	subql	#1,%d0			| from OSC3 for 1 and 2
	moveq	#2,%d1
	cmpl	%d1,%d0
	scs	%d1
	extbl	%d1
	movel	%d1,%a6@(M3)
	mvzb	%a5@(0x3c),%d0		| E: the mix, 0..127
	jsr	clamp127
	jsr	gains
.else
	mvzb	%a5@(0x36),%d0		| B: FM from OSC2 for 1 and 2, from OSC3 for 2 and 3
	subql	#1,%d0
	moveq	#2,%d1
	cmpl	%d1,%d0
	scs	%d1
	extbl	%d1
	movel	%d1,%a6@(M2)
	subql	#1,%d0
	moveq	#2,%d1
	cmpl	%d1,%d0
	scs	%d1
	extbl	%d1
	movel	%d1,%a6@(M3)
	mvzb	%a5@(0x3c),%d0		| E: the mix, 0..120 stretched to 0..127
	movel	%d0,%d1
	lsrl	#4,%d1
	addl	%d1,%d0
	moveq	#127,%d1
	cmpl	%d0,%d1
	bccs	1f
	movel	%d1,%d0
1:	jsr	gains
.endif
	movel	#0x7f00,%sp@-		| the level, Q31 >> 16: LEVEL(x, 127), velocity only
	movel	%a6@(TRK),%d0
	lea	VELS,%a0
	mvzw	%a0@(0,%d0:l:2),%d0
	movel	%d0,%sp@-
	jsr	LEVEL
	addql	#8,%sp
	movel	%a6@(TRK),%d1		| the lanes' de-click
	movel	%d1,%d2
	mulu.w	#0x5e,%d1
	lea	VOICES+0x28,%a0
	tstb	%a0@(0,%d1:l)
	beqs	1f
	movel	TRIGS,%d1
	btst	%d2,%d1
	beqs	1f
	clrl	%d0
1:	clrw	%d0
	swap	%d0
	movel	%a6@(TRK),%d1		| the ramp from the last tick's level
	lea	LEVELS,%a0
	lea	%a0@(0,%d1:l:4),%a0
	movel	%a0@,%d1
	movel	%d0,%a0@
	cmpil	#0x7fff,%d1
	blss	2f
	movel	%d0,%d1
2:	movel	%d0,%d2
	subl	%d1,%d2
	movel	%d2,%a6@(LSTEP)
	lsll	#5,%d1
	movel	%d1,%a6@(LCUR)
.ifdef KNOBS2
	mvzw	%a5@(0x40),%d0		| OSC2: G, 40.0..88.0 = -24..+24 semitones (8.8); wave C
	jsr	detune24
	addl	%a6@(NSUM),%d0
	jsr	pitch
	movel	%d0,%d6
	mvzb	%a5@(0x38),%d0
	jsr	clamp127
	jsr	wave
	lea	%a4@(4),%a2
	lea	%a6@(S2),%a3
	jsr	osc32
	mvzw	%a5@(0x42),%d0		| OSC3: H, the same; wave D
	jsr	detune24
	addl	%a6@(NSUM),%d0
	jsr	pitch
	movel	%d0,%d6
	mvzb	%a5@(0x3a),%d0
	jsr	clamp127
	jsr	wave
	lea	%a4@(8),%a2
	lea	%a6@(S3),%a3
	jsr	osc32
	mvzb	%a5@(0x34),%d0		| OSC1: A, the wave 0..127, with FM, into the buffer
	jsr	clamp127
	jsr	wave
.else
	mvzb	%a5@(0x40),%d0		| OSC2: G, -48..-1 st, unison at 48, +1..+50 cents; wave C
	jsr	detune_lo
	addl	%a6@(NSUM),%d0
	jsr	pitch
	movel	%d0,%d6
	mvzb	%a5@(0x38),%d0
	jsr	wave
	lea	%a4@(4),%a2
	lea	%a6@(S2),%a3
	jsr	osc32
	mvzb	%a5@(0x42),%d0		| OSC3: H, -50..-1 cents, unison at 50, +1..+48 st; wave D
	jsr	detune_hi
	addl	%a6@(NSUM),%d0
	jsr	pitch
	movel	%d0,%d6
	mvzb	%a5@(0x3a),%d0
	jsr	wave
	lea	%a4@(8),%a2
	lea	%a6@(S3),%a3
	jsr	osc32
	mvzb	%a5@(0x34),%d0		| OSC1: A, 4..88 -> wave (A - 4) x 1.5, with FM, into the buffer
	subql	#4,%d0
	bpls	1f
	moveq	#0,%d0
1:	movel	%d0,%d1
	addl	%d0,%d0
	addl	%d1,%d0
	lsrl	#1,%d0
	jsr	wave
.endif
	movel	%a6@(STEP1),%d6
	movel	%a6@(TRK),%d0
	lsll	#7,%d0
	movea.l	%a6@(A18),%a3
	adda.l	%d0,%a3
	movea.l	%a4,%a2
	jmp	osc1_mix

.ifndef SETS
| detune_lo: %d0 = G (clamped to 0..98) -> %d0 = note-sum offset: below 48 semitones (0x10000 each),
| above 48 cents (655 each). detune_hi: %d0 = H (0..98): below 50 cents, above 50 semitones.
| Clobber %d1. (With SETS, which comes with KNOBS2, they are left out: KNOBS2 does not use them.)
detune_lo:
	bsrs	clamp98
	subil	#48,%d0
	bmis	semis
	bras	cents
detune_hi:
	bsrs	clamp98
	subil	#50,%d0
	bmis	cents
semis:	swap	%d0			| (d0 << 16): exact, also for negative d0
	clrw	%d0
	rts
cents:	mulsw	#655,%d0			| -50..50 cents, a 16-bit product
	rts
clamp98:
	moveq	#98,%d1
	cmpl	%d0,%d1
	bccs	1f
	movel	%d1,%d0
1:	rts
.endif
.ifdef KNOBS2
| detune24: %d0 = G or H (8.8, 0x2800..0x5800, clamped to it) -> %d0 = the note-sum offset, (v - 64.0)
| x 256: a semitone is 0x10000, the fraction gives 1/256 semitone. Clobbers %d1.
detune24:
	cmpil	#0x2800,%d0
	bges	1f
	movel	#0x2800,%d0
1:	cmpil	#0x5800,%d0
	bles	2f
	movel	#0x5800,%d0
2:	subil	#0x4000,%d0
	lsll	#8,%d0
	rts
| clamp127: %d0 = at most 127. Clobbers %d1.
clamp127:
	moveq	#127,%d1
	cmpl	%d0,%d1
	bccs	1f
	movel	%d1,%d0
1:	rts
.endif
.else
track:
	movel	%a6@(TRK),%d0		| its three phases
	movel	%d0,%d1
	addl	%d0,%d0
	addl	%d1,%d0
	lsll	#2,%d0
	lea	PHASES,%a4
	adda.l	%d0,%a4
	movel	%a6@(TRK),%d0		| OSC1's note sum: note + (TUNE - 64 st) + the table offset
	lea	NOTES,%a0
	movel	%a0@(0,%d0:l:4),%d2
	mvzw	%a5@(0x34),%d0
	subil	#0x4000,%d0
	lsll	#8,%d0
	addl	%d0,%d2
	addil	#0x30000,%d2
	movel	%d2,%a6@(NSUM)
	movel	%d2,%d0
	jsr	pitch
	movel	%d0,%a6@(STEP1)
	moveq	#13,%d1			| FM scale: step / 8192 x LEN
	lsrl	%d1,%d0
	mvzb	%a5@(0x3e),%d1
	mulsl	%d1,%d0
	movel	%d0,%a6@(FMS)
	mvzb	%a5@(0x36),%d0		| FM source from PLAY: OSC2 for 0 and 1, OSC3 for 1 and 2
	moveq	#2,%d1
	cmpl	%d1,%d0
	scs	%d1
	extbl	%d1
	movel	%d1,%a6@(M2)
	subql	#1,%d0
	moveq	#2,%d1
	cmpl	%d1,%d0
	scs	%d1
	extbl	%d1
	movel	%d1,%a6@(M3)
	mvzb	%a5@(0x38),%d0		| the mix from BR
	jsr	gains
	mvzw	%a5@(0x42),%d0		| the level, Q31 >> 16: LEVEL(x, LEV), as the lanes compute it each
	movel	%d0,%sp@-		| tick, but not read from the voice (+0x10): the lanes fade that once
	movel	%a6@(TRK),%d0		| a voice's sample has ended, and a synth voice has none
	lea	VELS,%a0
	mvzw	%a0@(0,%d0:l:2),%d0
	movel	%d0,%sp@-
	jsr	LEVEL			| keeps %d2-%d7/%a0-%a6
	addql	#8,%sp
	movel	%a6@(TRK),%d1		| the lanes' de-click: no level on the tick before a voice's next trig
	movel	%d1,%d2
	mulu.w	#0x5e,%d1
	lea	VOICES+0x28,%a0
	tstb	%a0@(0,%d1:l)
	beqs	1f
	movel	TRIGS,%d1
	btst	%d2,%d1
	beqs	1f
	clrl	%d0
1:	clrw	%d0
	swap	%d0			| this tick's level, 0..0x7fff
	movel	%a6@(TRK),%d1		| ramp to it from the last tick's, as the lanes' store loop does
	lea	LEVELS,%a0
	lea	%a0@(0,%d1:l:4),%a0
	movel	%a0@,%d1
	movel	%d0,%a0@
	cmpil	#0x7fff,%d1		| not a level (the RAM is not set at boot): no ramp
	blss	2f
	movel	%d0,%d1
2:	movel	%d0,%d2
	subl	%d1,%d2
	movel	%d2,%a6@(LSTEP)		| 32 steps of (new - last) / 32 end exactly on the new level
	lsll	#5,%d1
	movel	%d1,%a6@(LCUR)
	movel	%a6@(NSUM),%d0		| OSC2: an octave below, wave LOOP
	subil	#0xc0000,%d0
	jsr	pitch
	movel	%d0,%d6
	mvzb	%a5@(0x40),%d0
	jsr	wave
	lea	%a4@(4),%a2
	lea	%a6@(S2),%a3
	jsr	osc32
	movel	%a6@(NSUM),%d0		| OSC3: an octave and a fifth above, wave LOOP
	addil	#0x130000,%d0
	jsr	pitch
	movel	%d0,%d6
	mvzb	%a5@(0x40),%d0
	jsr	wave
	lea	%a4@(8),%a2
	lea	%a6@(S3),%a3
	jsr	osc32
	mvzb	%a5@(0x3c),%d0		| OSC1: wave STRT, with FM, mixed into the track's buffer
	jsr	wave
	movel	%a6@(STEP1),%d6
	movel	%a6@(TRK),%d0
	lsll	#7,%d0
	movea.l	%a6@(A18),%a3
	adda.l	%d0,%a3
	movea.l	%a4,%a2
	jmp	osc1_mix
.endif

| ---- pitch: %d0 = note sum -> %d0 = phase step per sample (2^32 = one cycle, 48 kHz).
| The stock table gives 2^29 at note 60; (2^29 >> 13) x 357 = 23,396,352, 261.4 Hz (C4 -1 cent).
| Clobbers %d1, %a0.

	.section .cfo_pitch,"ax"
| With KNOBS2 a note sum above the table's top (0x570000, note 84, about 1 kHz, where the table
| saturates) is taken down by octaves first, and the step doubled back up for each, at most 0x7fffffff
| (half the sample rate). Then it clobbers %a1 as well.
pitch:
	tstl	%d0
	bpls	1f
	moveq	#0,%d0
1:
.ifdef KNOBS2
	suba.l	%a1,%a1			| octaves above the table's top
3:	cmpil	#0x570000,%d0
	bles	2f
	subil	#0xc0000,%d0
	addql	#1,%a1
	bras	3b
.else
	cmpil	#0x570000,%d0
	bles	2f
	movel	#0x570000,%d0
.endif
2:	movel	#384,%d1
	divul	%d1,%d0
	lea	PITCH,%a0
	movel	%a0@(0,%d0:l:4),%d0
	moveq	#13,%d1
	lsrl	%d1,%d0
	movel	#357,%d1
	mulsl	%d1,%d0
.ifdef KNOBS2
4:	movel	%a1,%d1			| an octave up, each
	beqs	5f
	cmpil	#0x40000000,%d0
	bcss	6f
	movel	#0x7fffffff,%d0
	rts
6:	addl	%d0,%d0
	subql	#1,%a1
	bras	4b
5:
.endif
	rts

| ---- wave: %d0 = 0..127 -> %a0 = table, %a1 = the next table, %d5 = how far towards it, 0..254.
| Clobbers %d0, %d1.

	.section .cfo_wave,"ax"
.ifdef PURE
| (S20) The waves are pure at 0 (SIN), 42 (TRI), 85 (SAW) and 127 (SQR), linear between them; before,
| 3w/128 put TRI and SAW between two knob values and 127 at 98 % SQR. Out: %a0, %a1 the two tables,
| %d5 the fraction of %a1, 0..256. Clobbers %d0, %d1.
wave:
	bsrw	segfrac
	movel	%d0,%d5
	lsll	#8,%d1
	lea	waves,%a0
	adda.l	%d1,%a0
	lea	%a0@(256),%a1
	rts
| segfrac: %d0 = 0..127 -> %d1 = the segment (0 from 0, 1 from 42, 2 from 85), %d0 = how far into it,
| 0..256, rounded: (d x 512 + L) / 2L for d = %d0 - its start and L = 42, 43, 42.
segfrac:
	moveq	#0,%d1
	cmpil	#42,%d0
	bcss	2f			| 0..41
	moveq	#2,%d1
	subil	#85,%d0
	bccs	2f			| 85..127
	addil	#43,%d0			| 42..84: d = %d0 - 42
	moveq	#1,%d1
	lsll	#8,%d0
	addl	%d0,%d0
	addil	#43,%d0
	divuw	#86,%d0
	bras	3f
2:	lsll	#8,%d0
	addl	%d0,%d0
	addil	#42,%d0
	divuw	#84,%d0
3:	mvzw	%d0,%d0
	rts
.else
wave:
	movel	%d0,%d1
	addl	%d0,%d0
	addl	%d1,%d0
	movel	%d0,%d1
	lsrl	#7,%d1
	lsll	#8,%d1
	lea	waves,%a0
	adda.l	%d1,%a0
	lea	%a0@(256),%a1
	moveq	#127,%d5
	andl	%d0,%d5
	addl	%d5,%d5
	rts
.endif

| ---- gains: %d0 = 0..127 -> G1, G2, G3: OSC1 -> OSC1+2 -> OSC1+2+3 -> OSC2+3, crossfaded.
| (S20: the four mixes exactly at 0, 42, 85 and 127, as the pure waves.) Clobbers %d0-%d3, %a0, %a1.

	.section .cfo_gains,"ax"
gains:
.ifdef PURE
	bsrw	segfrac
.else
	movel	%d0,%d1
	addl	%d0,%d0
	addl	%d1,%d0
	movel	%d0,%d1
	lsrl	#7,%d1
.endif
	mulu.w	#6,%d1
	lea	mixpts,%a0
	adda.l	%d1,%a0
.ifndef PURE
	moveq	#127,%d1
	andl	%d1,%d0
	addl	%d0,%d0
.endif
	lea	%a6@(G1),%a1
	moveq	#2,%d3
1:	mvsw	%a0@,%d1
	mvsw	%a0@(6),%d2
	subl	%d1,%d2
	mulsw	%d0,%d2
	asrl	#8,%d2
	addl	%d1,%d2
	movew	%d2,%a1@+
	addql	#2,%a0
	subql	#1,%d3
	bpls	1b
	rts

| ---- osc32: 32 samples of one oscillator into a word buffer.
| In: %a0/%a1/%d5 the wave (wave), %d6 the step, %a2 its phase, %a3 the buffer. Clobbers %d0-%d4, %d7, %a3.

	.section .cfo_osc,"ax"
osc32:
	movel	%a2@,%d4
	moveq	#31,%d7
1:	addl	%d6,%d4
	movel	%d4,%d0
	moveq	#24,%d1
	lsrl	%d1,%d0
	mvsb	%a0@(0,%d0:l),%d1
	mvsb	%a1@(0,%d0:l),%d2
	subl	%d1,%d2
	mulsw	%d5,%d2
	asrl	#8,%d2
	addl	%d1,%d2
	movew	%d2,%a3@+
	subql	#1,%d7
	bpls	1b
	movel	%d4,%a2@
	rts

| ---- osc1_mix: OSC1 with FM from OSC2/OSC3, the mix of the three, the level, into the track's buffer.
| In: %a0/%a1/%d5 OSC1's wave, %d6 its step, %a2 its phase, %a3 the track's 32 longs. Clobbers %d0-%d4,
| %d7, %a3-%a5. Output: +-32512 x level >> 16, at most +-2^30.

	.section .cfo_mix,"ax"
osc1_mix:
	lea	%a6@(S2),%a4
	lea	%a6@(S3),%a5
	movel	%a2@,%d4
	moveq	#31,%d7
1:	mvsw	%a4@+,%d0		| OSC2
	mvsw	%a5@+,%d1		| OSC3
	movel	%d0,%d2			| the FM input
	andl	%a6@(M2),%d2
	movel	%d1,%d3
	andl	%a6@(M3),%d3
	addl	%d3,%d2
	mulsl	%a6@(FMS),%d2
	addl	%d6,%d2
	addl	%d2,%d4			| OSC1's phase: step + FM
	mulsw	%a6@(G2),%d0
	mulsw	%a6@(G3),%d1
	addl	%d1,%d0
	movel	%d4,%d1			| OSC1
	moveq	#24,%d2
	lsrl	%d2,%d1
	mvsb	%a0@(0,%d1:l),%d2
	mvsb	%a1@(0,%d1:l),%d3
	subl	%d2,%d3
	mulsw	%d5,%d3
	asrl	#8,%d3
	addl	%d3,%d2
	mulsw	%a6@(G1),%d2
	addl	%d2,%d0			| the mix
	movel	%a6@(LCUR),%d1		| x the level, one ramp step on
	addl	%a6@(LSTEP),%d1
	movel	%d1,%a6@(LCUR)
	asrl	#5,%d1
	mulsl	%d1,%d0
	movel	%d0,%a3@+
	subql	#1,%d7
	bpls	1b
	movel	%d4,%a2@
	rts

| ---- the SRC page layout for machine 5. FUN_400657cc(machine) returns 0x4197ded8 + 44 x machine for
| machines 0..3 and, past that, SLICE's record (0x4197df5c). Its fallback (8 B at 0x400657e6, reached
| with the machine still in %d0) jumps here instead: CFOO gets ONESHOT's record, so its SRC page shows
| ONESHOT's eight parameters and their ranges; every other machine above 3 still gets SLICE's.

.ifdef MACHINE5
	.section .hook_layout,"ax"	| 0x400657e6: movel #0x4197df5c,%d0 ; rts
	jmp	layout_hi
	nop

	.section .cfo_layout,"ax"
layout_hi:
	moveq	#CFOO,%d1
	cmpl	%d0,%d1
	bnes	1f
	movel	#0x4197ded8,%d0		| ONESHOT's record
	rts
1:	movel	#0x4197df5c,%d0		| SLICE's record, as stock
	rts
.endif

| ---- CFOO's own parameter names (--defsym NAMES=1, with MACHINE5). MIDI Loopback's two label pads end
| in a jump to the stock accessor for every parameter that is not theirs: the short label (pad at
| 0x4001562c, reached from MachineParameterPageView::vfunc_37, the page in %a4) and the popup's long
| name (pad at 0x4001564c, reached from ParameterPageView::vfunc_17, the page in %a2). Those two jumps
| come here instead. For ONESHOT's SRC parameter ids 108..115 on a page that shows machine 5, the name
| comes from this build's tables; anything else goes on to the stock accessor, its arguments untouched.

.ifdef NAMES
	.section .cfo_rename,"ax"
cfo_short:
	movel	%sp@(8),%d0		| the parameter id
	subil	#108,%d0
	moveq	#7,%d1
	cmpl	%d0,%d1
	bcss	1f			| not one of ONESHOT's SRC parameters
	movel	%d0,%sp@-
	movel	%a4,%sp@-		| the page
	jsr	0x4002b5d4		| the machine it shows (POLY followers: their source's)
	addql	#4,%sp
	movel	%sp@+,%d1
	subql	#CFOO,%d0
	bnes	1f
	lea	cfoo_short_names,%a0
	movel	%a0@(0,%d1:l:4),%d0
	rts
1:	jmp	0x4000fe8a		| the stock short-label accessor

cfo_long:
	movel	%sp@(8),%d0
	subil	#108,%d0
	moveq	#7,%d1
	cmpl	%d0,%d1
	bcss	1f
	movel	%d0,%sp@-
	movel	%a2,%sp@-		| the page
	jsr	0x4002b5d4
	addql	#4,%sp
	movel	%sp@+,%d1
	subql	#CFOO,%d0
	bnes	1f
	lea	cfoo_long_names,%a0
	movel	%a0@(0,%d1:l:4),%d0
	rts
1:	jmp	0x4000feac		| the stock long-name accessor

	.section .cfo_names,"a"
.ifdef KNOBS
cfoo_short_names:			| ids 108..115, knobs A..H
	.long	n_wav1, n_fmsr, n_wav2, n_wav3, n_mix, n_fm, n_det2, n_det3
cfoo_long_names:
	.long	n_osc1_wave, n_fm_source, n_osc2_wave, n_osc3_wave, n_osc_mix, n_fm_amount, n_osc2_det, n_osc3_det
n_wav3:	.asciz	"WAV3"
n_det2:	.asciz	"DET2"
n_det3:	.asciz	"DET3"
n_osc2_wave:	.asciz	"OSC2 Wave"
n_osc3_wave:	.asciz	"OSC3 Wave"
n_osc2_det:	.asciz	"OSC2 Detune"
n_osc3_det:	.asciz	"OSC3 Detune"
.else
cfoo_short_names:			| ids 108..115: TUNE PLAY BR SAMP STRT LEN LOOP LEV
	.long	0x401d9fff, n_fmsr, n_mix, 0x401c6f07, n_wav1, n_fm, n_wav2, 0x401cca34
cfoo_long_names:
	.long	0x401cce1e, n_fm_source, n_osc_mix, 0x401cce3e, n_osc1_wave, n_fm_amount, n_osc23_wave, n_level
.endif
n_fmsr:	.asciz	"FMSR"
n_mix:	.asciz	"MIX"
n_wav1:	.asciz	"WAV1"
n_fm:	.asciz	"FM"
n_wav2:	.asciz	"WAV2"
n_fm_source:	.asciz	"FM Source"
n_osc_mix:	.asciz	"Osc Mix"
n_osc1_wave:	.asciz	"OSC1 Wave"
n_fm_amount:	.asciz	"FM Amount"
n_osc23_wave:	.asciz	"OSC2+3 Wave"
n_level:	.asciz	"Level"
	.balign	4
.endif

| ---- CFOO's picker icon (--defsym ICON=1, with MACHINE5). The build's group mapper gives machine m the
| code m + 1 up to its bound; codes 4 and 5 jump to the build's icon pad (0x400bee44), which takes the
| bitmap from a selector table (code - 4) and continues into the stock bitmap draw. S14 raises the
| mapper's bound and the icon range by one and points the pad at a three-entry table here: SLICE's stock
| icon, POLY's, CFOO's. The icon is a PLACEHOLDER: two filled sawtooth ramps, 11 x 7, 1-bit. In a
| column word the top bit is the icon's BOTTOM row (pinned on POLY's icon in this image).
|    col 0         1
|        01234567890
| row 0  ....#.....#
|     1  ...##....##
|     2  ..###...###
|     3  .####..####
|     4  #####.#####
|     5  ###########
|     6  ###########

.ifdef ICON
	.section .cfo_icon,"a"
icon_table:
	.long	0x421fa35c		| code 4: SLICE's stock icon (a run-time bitmap object)
	.long	0x40252bdc		| code 5: POLY's bitmap object
	.long	cfoo_icon		| code 6: CFOO
cfoo_icon:				| a Bitmap object, as POLY's at 0x40252bdc
	.long	0x401b7734		| the Bitmap vtable
	.long	11, 7, 1
	.long	cfoo_plane
	.long	0x4023e0a0		| the stock mask POLY's icon uses
	.long	0
cfoo_plane:
	.long	0xe0000000, 0xf0000000, 0xf8000000, 0xfc000000, 0xfe000000, 0xc0000000
	.long	0xe0000000, 0xf0000000, 0xf8000000, 0xfc000000, 0xfe000000
.endif

| ---- CFOO's SRC slots are ONESHOT's parameters (--defsym SLOTS=1, with MACHINE5). FUN_40078f44(slot,
| machine) gives an SRC slot's parameter id for machines 0..3 and id 0 above; on a machine change,
| FUN_400220fc skips every slot whose id is 0, so a change to machine 5 reset no slot and posted no
| change at all. Its test (6 B at 0x40078f72, a branch target, the machine in %d0, %d2 saved by the
| function) comes here: machine 5 is looked up as machine 0, every other machine as before.

.ifdef SLOTS
	.section .hook_slots,"ax"	| 0x40078f72: moveq #3,%d2 ; cmpl %d0,%d2 ; bcss 0x40078f54
	jmp	slot_machine

	.section .cfo_slots,"ax"
slot_machine:
	moveq	#CFOO,%d2
	cmpl	%d0,%d2
	bnes	1f
	moveq	#0,%d0			| CFOO: ONESHOT's ids 108..115
1:	moveq	#3,%d2
	cmpl	%d0,%d2
	bcss	2f
	jmp	0x40078f78		| the table lookup
2:	jmp	0x40078f54		| id 0
.endif

| ---- CFOO's own ranges and defaults (--defsym KNOBS=1, with SLOTS). FUN_40078f0c(id) copies a
| parameter's {min, max, default} (12 B from descriptor +0x08) to %a0. At five call sites %a2 holds the
| parameter set (ParameterSet::vfunc_8, vfunc_11, vfunc_25, the value setter FUN_4000fef6, and the
| machine-change reset FUN_400220fc); those calls come here. For ids 108..115 on a set whose machine
| (FUN_4002200a) is 5, CFOO's record is copied; anything else goes to FUN_40078f0c untouched. Each of
| CFOO's ranges lies inside ONESHOT's for the same slot, so the readers that are not hooked (they clamp
| to ONESHOT's ranges) cannot push a CFOO value out of the slot's stored range.
|
| FUN_4002200a takes a sound holder, not a parameter set: only FUN_400220fc's %a2 is one. Given a set,
| it calls the set's value getter as if it were the holder's sound accessor, so the test above fails on
| the unit. With SETS (S19) the machine comes from set_machine, which finds the holder behind a sound's
| parameter set, and four more readers come here, each by the entry for where it keeps the set:
| ParameterSet::vfunc_9 (the validity test that FUN_4000fef6 asks before it writes a value; it calls
| through %a2, the set is its first argument), ParameterSet::vfunc_4 (reset to the default; first
| argument), ParameterSet::vfunc_26 (a MIDI CC; %a2) and the edit of one parameter on all eight tracks
| (MachineParameterPageView::vfunc_23; %a3). CFOO's ranges no longer need to lie inside ONESHOT's.

.ifdef KNOBS
	.section .cfo_range,"ax"
.ifdef SETS
cfo_range9:				| ParameterSet::vfunc_9: its set at %sp@(36)
	moveal	%sp@(36),%a1
	bras	1f
cfo_range4:				| ParameterSet::vfunc_4: its set at %sp@(28)
	moveal	%sp@(28),%a1
	bras	1f
cfo_range_a3:				| the all-tracks edit: the set in %a3
	moveal	%a3,%a1
	bras	1f
.endif
cfo_range:
.ifdef SETS
	moveal	%a2,%a1			| the set in %a2 (FUN_400220fc: a sound holder)
1:
.endif
	movel	%sp@(4),%d0
	subil	#108,%d0
	moveq	#7,%d1
	cmpl	%d0,%d1
	bcss	9f			| not an SRC parameter
	movel	%a0,%sp@-
	movel	%d0,%sp@-
.ifdef SETS
	movel	%a1,%sp@-
	jsr	set_machine
.else
	movel	%a2,%sp@-
	jsr	0x4002200a		| the set's machine; keeps %a2
.endif
	addql	#4,%sp
	movel	%sp@+,%d1
	moveal	%sp@+,%a0
	subql	#CFOO,%d0
	bnes	9f
	movel	%d1,%d0			| 12 B per record
	lsll	#2,%d0
	movel	%d0,%d1
	addl	%d0,%d0
	addl	%d1,%d0
	lea	cfoo_ranges,%a1
	adda.l	%d0,%a1
	movel	%a1@+,%a0@
	movel	%a1@+,%a0@(4)
	movel	%a1@,%a0@(8)
	movel	%a0,%d0
	rts
9:	jmp	0x40078f0c

.ifdef SETS
| set_machine(object) -> %d0 = the machine of its sound, -1 for none. FUN_4002200a takes a sound holder
| (its method at +0x28 gives the sound, the machine at the sound's +0x7e), as FUN_4000d7be returns it
| per track. A SoundParameterSet (vtable 0x4017ee58) keeps its holder at +0x10 and reaches its sound
| through it the same way (SoundParameterSet::vfunc_20). The other four parameter sets (vtables
| 0x4017edd8, 0x4017eed8, 0x4017ef58, 0x4017efd8, 0x80 B apart) have no sound. Anything else is taken
| for a holder. Clobbers %d0, %d1, %a0, %a1 and the caller's argument slot, which the caller drops.
set_machine:
	moveal	%sp@(4),%a0
	movel	%a0@,%d0
	subil	#0x4017ee58,%d0		| SoundParameterSet's vtable
	beqs	1f
	addil	#0x80,%d0		| from ParameterSet's
	cmpil	#0x280,%d0
	bcss	2f			| another parameter set
	bras	3f			| a holder
1:	movel	%a0@(16),%d0		| the set's holder
	beqs	2f
	movel	%d0,%sp@(4)
3:	jmp	0x4002200a
2:	moveq	#-1,%d0
	rts
.endif

	.section .cfo_names,"a"
	.balign	4
.ifdef KNOBS2
cfoo_ranges:				| {min, max, default}, 8.8, knobs A..H (S18)
	.long	0x0000, 0x7f00, 0x0000	| A OSC1 wave: SIN
	.long	0x0000, 0x0200, 0x0000	| B FM source: 0 OSC2, 1 OSC2+3, 2 OSC3
	.long	0x0000, 0x7f00, 0x0000	| C OSC2 wave
	.long	0x0000, 0x7f00, 0x0000	| D OSC3 wave
	.long	0x0000, 0x7f00, 0x0000	| E mix: OSC1 alone
	.long	0x0000, 0x7f00, 0x0000	| F FM amount: 0
	.long	0x2800, 0x5800, 0x4000	| G OSC2 detune: -24.00..+24.00 semitones, unison at 64.0
	.long	0x2800, 0x5800, 0x4000	| H OSC3 detune: the same
.else
cfoo_ranges:				| {min, max, default}, 8.8, knobs A..H (ONESHOT's slot range)
	.long	0x0400, 0x5800, 0x0400	| A OSC1 wave: SIN..SQR in 85 steps    (TUNE 4..88)
	.long	0x0000, 0x0300, 0x0000	| B FM source: off                      (PLAY 0..3)
	.long	0x0000, 0x7f00, 0x0000	| C OSC2 wave: SIN                      (BR 0..127)
	.long	0x0000, 0x7f00, 0x0000	| D OSC3 wave: SIN                      (SAMP 0..127)
	.long	0x0000, 0x7800, 0x0000	| E mix: OSC1 alone                     (STRT 0..120)
	.long	0x0000, 0x7800, 0x0000	| F FM amount: 0                        (LEN 0..120)
	.long	0x0000, 0x6200, 0x3000	| G OSC2 detune 0..98: unison at 48     (LOOP 0..120)
	.long	0x0000, 0x6200, 0x3200	| H OSC3 detune 0..98: unison at 50     (LEV 0..127)
.endif
.endif

| ---- CFOO's own value displays (--defsym DISPLAYS=1, with KNOBS). A parameter's value shows in two
| places: the cell on the SRC page (ParameterSet::vfunc_23 draws its picture, vfunc_22 writes its text)
| and the encoder popup (ParameterPageView::vfunc_17 calls FUN_400657ee at 0x40032d16, the page in
| %a2). For ids 108..115 on a CFOO sound:
| - the cell's picture is STRT's plain knob (id 112, 0..120), its value rescaled from the knob's own
|   range in cfoo_ranges, so every knob turns over its whole range and the detunes' unison sits at the
|   top;
| - the cell's text and the popup's value are this build's: what the synth makes of the value.
| The two cell methods are hooked at their first 8 B (replayed here); the popup at its call operand.

.ifdef DISPLAYS
	.section .hook_popup,"ax"	| 0x40032d16: jsr FUN_400657ee
	jsr	cfo_popup
	.section .hook_pic,"ax"		| 0x4000f2bc: lea %sp@(-20),%sp ; moveml %d2-%d6,%sp@
	jmp	cfo_pic
	nop
	.section .hook_ctext,"ax"	| 0x4000f324: lea %sp@(-20),%sp ; moveml %d2-%d4/%a2-%a3,%sp@
	jmp	cfo_ctext
	nop

	.section .cfo_display,"ax"
| cfo_pic(set, id, value, ...): ParameterSet::vfunc_23, the cell's picture.
cfo_pic:
	movel	%sp@(8),%d0		| the id
	subil	#108,%d0
	moveq	#7,%d1
	cmpl	%d0,%d1
	bcss	9f			| not an SRC parameter
	movel	%sp@(4),%sp@-		| the parameter set
.ifdef SETS
	jsr	set_machine		| its sound's machine
.else
	jsr	0x4002200a		| its sound's machine
.endif
	addql	#4,%sp
	subql	#CFOO,%d0
	bnes	9f
	movel	%sp@(8),%d0		| the knob's record in cfoo_ranges, 12 B each
	subil	#108,%d0
	lsll	#2,%d0
	movel	%d0,%d1
	addl	%d0,%d0
	addl	%d1,%d0
	lea	cfoo_ranges,%a0
	adda.l	%d0,%a0
.ifdef KNOBS2
	movel	%sp@(12),%d1		| the value (8.8) - min, clamped to 0..span
	andil	#0xffff,%d1
	subl	%a0@,%d1
	bpls	1f
	moveq	#0,%d1
1:	movel	%a0@(4),%d0
	subl	%a0@,%d0		| span, 8.8
	cmpl	%d1,%d0
	bccs	2f
	movel	%d0,%d1
.else
	movel	%sp@(12),%d1		| the value's integer part, as the synth reads it
	andil	#0xffff,%d1
	lsrl	#8,%d1
	movel	%a0@,%d0		| - min, clamped to 0..span
	lsrl	#8,%d0
	subl	%d0,%d1
	bpls	1f
	moveq	#0,%d1
1:	movel	%a0@(4),%d0
	subl	%a0@,%d0
	lsrl	#8,%d0			| span
	cmpl	%d1,%d0
	bccs	2f
	movel	%d0,%d1
.endif
2:	moveal	%d0,%a1
	movel	#0x7800,%d0		| x 120.0 / span: STRT's knob, whose drawer scales by 120.0
	mulsl	%d0,%d1
	movel	%a1,%d0
	divul	%d0,%d1
	movel	%d1,%sp@(12)
	moveq	#112,%d0
	movel	%d0,%sp@(8)
9:	lea	%sp@(-20),%sp		| the 8 B the hook replaced
	moveml	%d2-%d6,%sp@
	jmp	0x4000f2c4

| cfo_ctext(set, id, value, buffer): ParameterSet::vfunc_22, the cell's text.
cfo_ctext:
	movel	%sp@(8),%d0
	subil	#108,%d0
	moveq	#7,%d1
	cmpl	%d0,%d1
	bcss	9f
	movel	%sp@(4),%sp@-
.ifdef SETS
	jsr	set_machine
.else
	jsr	0x4002200a
.endif
	addql	#4,%sp
	subql	#CFOO,%d0
	bnes	9f
	movel	%sp@(8),%d0
	subil	#108,%d0
	movel	%sp@(12),%d1
	moveal	%sp@(16),%a0
	jmp	cfo_text		| returns to vfunc_22's caller
9:	lea	%sp@(-20),%sp		| the 8 B the hook replaced
	moveml	%d2-%d4/%a2-%a3,%sp@
	jmp	0x4000f32c

| cfo_popup(id, value), the page in %a2: the encoder popup's value text.
cfo_popup:
	movel	%sp@(4),%d0
	subil	#108,%d0
	moveq	#7,%d1
	cmpl	%d0,%d1
	bcss	9f
	movel	%a2,%sp@-		| the page
	jsr	0x4002b5d4		| the machine it shows (a POLY follower: its source's)
	addql	#4,%sp
	subql	#CFOO,%d0
	bnes	9f
	movel	%sp@(4),%d0
	subil	#108,%d0
	movel	%sp@(8),%d1
	lea	0x4197de98,%a0		| FUN_400657ee's own buffer, which it returns
	jsr	cfo_text
	movel	#0x4197de98,%d0
	rts
9:	jmp	0x400657ee

| cfo_text: %d0 = knob 0..7 (A..H), %d1 = its value (8.8), %a0 = a buffer. Writes what the synth makes
| of the value, NUL-terminated: A, C, D the wave on one 0..127 scale; B OFF, OSC2, 2+3, OSC3; E the mix
| 0..127; F the FM amount; G -48st..-1st, 0, +1ct..+50ct; H -50ct..-1ct, 0, +1st..+48st. At most 6 B.
| Clobbers %d0, %d1, %a0, %a1.
cfo_text:
	andil	#0xffff,%d1
.ifdef KNOBS2
	cmpil	#6,%d0			| (S18) G and H: semitones and cents, from the 8.8 value
	bccw	t2_det
	lsrl	#8,%d1
	cmpil	#1,%d0
	beqw	t2_b
	moveq	#127,%d0		| A, C, D, E, F: the number, at most 127, as the synth
	cmpl	%d1,%d0
	bccw	t_num
	movel	%d0,%d1
	braw	t_num
.endif
.ifndef SETS				| (S16, S17; with SETS, which comes with KNOBS2, left out)
	lsrl	#8,%d1			| the integer part, as the synth reads it
	tstl	%d0
	beqs	t_a
	subql	#1,%d0
	beqw	t_b
	subql	#1,%d0
	beqs	t_num			| C
	subql	#1,%d0
	beqs	t_num			| D
	subql	#1,%d0
	beqs	t_e
	subql	#1,%d0
	beqs	t_num			| F
	subql	#1,%d0
	beqs	t_g
	jsr	t_cap98			| H
	subil	#50,%d1
	beqs	t_zero
	bmis	t_neg_ct
	bras	t_pos_st
t_g:	jsr	t_cap98
	subil	#48,%d1
	beqs	t_zero
	bmis	t_neg_st
t_pos_ct:
	moveb	#43,%a0@+		| '+'
t_neg_ct:
	jsr	t_putnum
	lea	s_ct,%a1
	bras	t_copy
t_pos_st:
	moveb	#43,%a0@+
t_neg_st:
	jsr	t_putnum
	lea	s_st,%a1
	bras	t_copy
t_zero:	moveb	#48,%a0@+		| '0'
	clrb	%a0@
	rts
t_a:	subql	#4,%d1			| OSC1's wave: (A - 4) x 1.5, as the synth
	bpls	1f
	moveq	#0,%d1
1:	movel	%d1,%d0
	addl	%d1,%d1
	addl	%d0,%d1
	lsrl	#1,%d1
	bras	t_num
t_e:	movel	%d1,%d0			| the mix: E + E/16, at most 127, as the synth
	lsrl	#4,%d0
	addl	%d0,%d1
	moveq	#127,%d0
	cmpl	%d1,%d0
	bccs	t_num
	movel	%d0,%d1
.endif
t_num:	jsr	t_putnum
	clrb	%a0@
	rts
.ifndef SETS
t_b:	moveq	#3,%d0			| above 3 the synth takes no FM source
	cmpl	%d1,%d0
	bccs	1f
	moveq	#0,%d1
1:	lea	fmsr_names,%a1
	moveal	%a1@(0,%d1:l:4),%a1
.endif
t_copy:	moveb	%a1@+,%a0@+
	bnes	t_copy
	rts
.ifndef SETS
t_cap98:
	moveq	#98,%d0
	cmpl	%d1,%d0
	bccs	1f
	movel	%d0,%d1
1:	rts
.endif
| t_putnum: %d1 = -999..999 in decimal at %a0, which it advances. Clobbers %d0, %d1.
t_putnum:
	tstl	%d1
	bpls	1f
	moveb	#45,%a0@+		| '-'
	negl	%d1
1:	moveq	#100,%d0
	cmpl	%d0,%d1
	bcss	2f
	moveq	#47,%d0			| the hundreds: '0' - 1, counted up
5:	addql	#1,%d0
	subil	#100,%d1
	bpls	5b
	addil	#100,%d1
	moveb	%d0,%a0@+
	bras	3f			| the tens even when 0
2:	moveq	#10,%d0
	cmpl	%d0,%d1
	bcss	4f
3:	moveq	#47,%d0			| the tens
6:	addql	#1,%d0
	subil	#10,%d1
	bpls	6b
	addil	#10,%d1
	moveb	%d0,%a0@+
4:	addil	#48,%d1
	moveb	%d1,%a0@+
	rts

.ifdef KNOBS2
| (S18) B: OSC2, 2+3, OSC3; above 2 as 2, as the synth.
t2_b:	moveq	#2,%d0
	cmpl	%d1,%d0
	bccs	1f
	movel	%d0,%d1
1:	lea	fmsr2_names,%a1
	moveal	%a1@(0,%d1:l:4),%a1
	braw	t_copy
| (S18) G, H: the 8.8 value, 40.0..88.0 (clamped, as the synth), as semitones from unison with two
| decimals, a hundredth being a cent: -24.00 .. 0.00 .. +24.00.
t2_det:	cmpil	#0x2800,%d1
	bges	1f
	movel	#0x2800,%d1
1:	cmpil	#0x5800,%d1
	bles	2f
	movel	#0x5800,%d1
2:	subil	#0x4000,%d1		| semitones, 8.8, signed
	bnes	3f
	lea	s_zero2,%a1		| unison
	braw	t_copy
3:	bpls	4f
	moveb	#45,%a0@+		| '-'
	negl	%d1
	bras	5f
4:	moveb	#43,%a0@+		| '+'
5:	moveq	#100,%d0		| in cents, rounded: (v x 100 + 128) / 256
	mulsl	%d0,%d1
	addil	#128,%d1
	lsrl	#8,%d1
	moveq	#0,%d0			| whole semitones, and the cents left
6:	cmpil	#100,%d1
	bcss	7f
	subil	#100,%d1
	addql	#1,%d0
	bras	6b
7:	moveal	%d1,%a1
	movel	%d0,%d1
	jsr	t_putnum
	moveb	#46,%a0@+		| '.'
	movel	%a1,%d1
	moveq	#47,%d0			| two digits of cents
8:	addql	#1,%d0
	subil	#10,%d1
	bpls	8b
	addil	#10,%d1
	moveb	%d0,%a0@+
	addil	#48,%d1
	moveb	%d1,%a0@+
	clrb	%a0@
	rts

| cfo_encobj(id), the page in %a2: the encoder handler's display object (ParameterPageView::vfunc_17
| at 0x40032b74), whose template sets the step: for a CFOO page, BR's for the integer knobs (whole
| steps), PLAY's for B (a selector), TUNE's for G and H (fine steps). The pushed id is not used again.
cfo_encobj:
	movel	%sp@(4),%d0
	subil	#108,%d0
	moveq	#7,%d1
	cmpl	%d0,%d1
	bcss	9f
	movel	%a2,%sp@-
	jsr	0x4002b5d4		| the machine the page shows
	addql	#4,%sp
	subql	#CFOO,%d0
	bnes	9f
	movel	%sp@(4),%d0
	subil	#108,%d0
	lea	step_ids,%a0
	movel	%a0@(0,%d0:l:4),%d0
	movel	%d0,%sp@(4)
9:	jmp	0x40065794

| cfo_samptest: at 0x4003b5a0 in SamplePageView::vfunc_17, %d0 = the knob's id, the page in %a2. Replays
| 'moveq #111,%d1 ; movel %d0,%d2 ; moveq #-17,%d0', the start of its test for a Sample Slot parameter
| (which opens the sample picker), except that for id 111 on a CFOO page %d0 is 0, so the test fails.
cfo_samptest:
	movel	%d0,%d2
	moveq	#111,%d1
	cmpl	%d2,%d1
	bnes	8f
	movel	%a2,%sp@-
	jsr	0x4002b5d4
	addql	#4,%sp
	subql	#CFOO,%d0
	bnes	8f
	moveq	#0,%d0			| (0 & id) is no Sample Slot id
	moveq	#111,%d1
	rts
8:	moveq	#-17,%d0
	moveq	#111,%d1
	rts

.ifdef SNAP
| cfo_fobj(id), %a2 = the parameter set (S21): with [FUNC] held, ParameterSet::vfunc_11 calls the
| +0x44 callable of the knob's display object, which it takes through %a4 (lea at 0x40010052, two
| calls) with (value, delta, min, max, default) and stores what it returns. For A, C, D, E, G and H on
| a CFOO sound it gets a display object of this build whose callable steps to the next point in the
| turn's direction; B, F and everything else get FUN_40065794's object as before. Only +0x44..+0x53 of
| the returned object are read: the callable's storage, manager and invoker.
cfo_fobj:
	movel	%sp@(4),%d0
	subil	#108,%d0
	moveq	#7,%d1
	cmpl	%d0,%d1
	bcss	9f			| not an SRC parameter
	moveq	#-35,%d1		| 0xdd: A, C, D, E, G, H
	btst	%d0,%d1
	beqs	9f
	movel	%d0,%sp@-
	movel	%a2,%sp@-
	jsr	set_machine
	addql	#4,%sp
	movel	%sp@+,%d1
	subql	#CFOO,%d0
	bnes	9f
	lea	snap_waves-0x44,%a0	| A, C, D: the pure waves; E: the four mixes
	moveq	#6,%d0
	cmpl	%d0,%d1
	bcss	1f
	lea	snap_dets-0x44,%a0	| G, H: the detune points
1:	movel	%a0,%d0
	rts
9:	jmp	0x40065794

| snap_inv(storage, value, delta, min, max, default) -> the value [FUNC] + knob goes to: the first point
| above the value for a turn up, the last below it for a turn down, the value itself past the last
| point or for no turn. The storage holds the points: 8.8, ascending, -1 after the last.
snap_inv:
	moveal	%sp@(4),%a0
	moveal	%a0@,%a0
	movel	%sp@(8),%d0
	tstl	%sp@(12)
	beqs	9f
	bmis	4f
1:	mvsw	%a0@+,%d1		| up
	tstl	%d1			| (not mvs's own flags: Ghidra's emulator does not set them)
	bmis	9f
	cmpl	%d0,%d1
	bles	1b
	movel	%d1,%d0
9:	rts
4:	moveal	%d0,%a1			| down
5:	mvsw	%a0@+,%d1
	tstl	%d1
	bmis	9b
	cmpl	%a1,%d1
	bges	9b
	movel	%d1,%d0
	bras	5b
| snap_mgr: the callable's manager, which nothing here calls; a function, so not 0
snap_mgr:
	moveq	#0,%d0
	rts

	.section .cfo_names,"a"
	.balign	4
snap_waves:				| a display object's +0x44 callable: storage (8 B), manager, invoker
	.long	snap_wpts, 0, snap_mgr, snap_inv
snap_dets:
	.long	snap_dpts, 0, snap_mgr, snap_inv
snap_wpts:				| 0 SIN / OSC1, 42 TRI / 1+2, 85 SAW / 1+2+3, 127 SQR / 2+3
	.word	0x0000, 0x2a00, 0x5500, 0x7f00, -1
snap_dpts:				| -24, -17, -12, -5, 0, +7, +12, +19, +24 semitones
	.word	0x2800, 0x2f00, 0x3400, 0x3b00, 0x4000, 0x4700, 0x4c00, 0x5300, 0x5800, -1
	.section .cfo_display,"ax"
.endif

.ifdef TRKPOP
| cfo_trkpop (S22): the [TRK] popup, FUN_4003bbfe, shows FUN_40093ab0(popup, "%s: %.16s", the machine's
| short name, the sample name of SAMP's slot) at 0x4003bd6a, with the track's machine in %d5
| (FUN_4002200a, -1 for none) and %d0, %d1 dead. POLY (4) and CFOO (5) play no sample of their own: for
| them the format is "%s", the machine's name alone. Everything else goes on unchanged.
cfo_trkpop:
	movel	%d5,%d0
	subql	#4,%d0
	moveq	#1,%d1
	cmpl	%d0,%d1
	bcss	1f			| not 4 or 5
	lea	s_name_only,%a0
	movel	%a0,%sp@(8)		| the format
1:	jmp	0x40093ab0

	.section .cfo_names,"a"
s_name_only: .asciz "%s"
	.section .cfo_display,"ax"
.endif

.ifdef LFONAMES
| (S23) CFOO's names as LFO destinations. A CFOO track's SRC destinations are ONESHOT's ids 108..115
| (FUN_40078f44, S15), and two places read their names from the descriptors: the DEST knob's picture
| (0x40065d3e: the group "Sample" at +0x2c, uppercased to 4 letters, over the short name at +0x30) and
| the destination list's labels (the lambda at 0x400a4448: "%.16s:%.32s" of the page's prefix,
| FUN_4007914c, and the long name at +0x28, or the short name at +0x30 when that is too wide). Both
| show the current track's destinations: the picture finds its set from the current track, the list is
| opened from its LFO page. For ids 108..115 on a CFOO track they get "CFOO" and CFOO's names.
|
| cur_machine: -> %d0 = the current track's machine, -1 above track 7: FUN_4000d9c8(the project's kit,
| FUN_4001d24e(FUN_40014d86(project))), as the picture finds the track and the stock layout lookup
| (MachineParameterPageView::vfunc_23) its machine. Clobbers %d0, %d1, %a0, %a1.
cur_machine:
	movel	%d2,%sp@-
	jsr	0x40138882		| the project
	movel	%d0,%sp@-
	jsr	0x40014d86
	movel	%d0,%sp@
	jsr	0x4001d24e		| the current track
	movel	%d0,%d2
	jsr	0x40138882
	movel	%d0,%sp@
	jsr	0x40014d92		| the kit
	movel	%d2,%sp@-
	movel	%d0,%sp@-
	jsr	0x4000d9c8		| the track's machine
	lea	%sp@(12),%sp
	movel	%sp@+,%d2
	rts
| lfo_src: %d0 = an id -> %d0 = the knob 0..7 for ids 108..115 on a CFOO track, else -1. Clobbers %d1,
| %a0, %a1.
lfo_src:
	subil	#108,%d0
	moveq	#7,%d1
	cmpl	%d0,%d1
	bcss	8f
	movel	%d0,%sp@-
	bsrw	cur_machine
	subql	#CFOO,%d0
	bnes	7f
	movel	%sp@+,%d0
	rts
7:	addql	#4,%sp
8:	moveq	#-1,%d0
	rts

| cfo_lfogrp: the picture's std::string(this, group, alloc) at 0x40065dec, the destination's id in %d3.
cfo_lfogrp:
	movel	%d3,%d0
	bsrw	lfo_src
	tstl	%d0
	bmis	1f
	lea	cfoo_short,%a0		| "CFOO" for "SAMP"
	movel	%a0,%sp@(8)
1:	jmp	0x4017af20

| cfo_lfocell: 0x40065e5e, in place of 'moveq #52,%d0 ; mulsl %d0,%d3 ; movel %a0@(0x30,%d3:l),%sp@-'
| (followed by 'movel %d0,%sp@- ; nop'): %d3 = the id (0 above 163) -> %d0 = its short name. %d3 is not
| read again.
cfo_lfocell:
	movel	%d3,%d0
	bsrw	lfo_src
	tstl	%d0
	bmis	1f
	lea	cfoo_short_names,%a0
	movel	%a0@(0,%d0:l:4),%d0
	rts
1:	movel	%d3,%d0
	moveq	#52,%d1
	mulsl	%d1,%d0
	lea	0x401aa09c,%a0
	movel	%a0@(0x30,%d0:l),%d0
	rts

| cfo_lfolist: 0x400a44d0, in place of 'movel %a3@(0x28,%d0:l),%sp@- ; movel %d6,%sp@-' (the long name and
| the prefix for the label): %d0 = 52 x the id, %d2 = the id. cfo_lfolist2: 0x400a454c, in place of
| 'movel %a3@(0x30,%d2:l),%sp@- ; movel %d6,%sp@-' (the short name, when the long one is too wide): %d2 =
| 52 x the id, %a0 = the result string, which the formatter after it takes and which is kept here.
cfo_lfolist:
	moveal	%sp@+,%a1
	movel	%a3@(0x28,%d0:l),%sp@-	| as stock
	movel	%d6,%sp@-
	movel	%a1,%sp@-
	movel	%a0,%sp@-
	movel	%d2,%d0
	lea	cfoo_long_names,%a0
	bras	1f
cfo_lfolist2:
	moveal	%sp@+,%a1
	movel	%a3@(0x30,%d2:l),%sp@-	| as stock
	movel	%d6,%sp@-
	movel	%a1,%sp@-
	movel	%a0,%sp@-
	movel	%d2,%d0
	divuw	#52,%d0
	mvzw	%d0,%d0
	lea	cfoo_short_names,%a0
1:	movel	%a0,%sp@-		| the stack: CFOO's names, %a0, the return, the prefix, the name
	bsrw	lfo_src
	moveal	%sp@+,%a0
	tstl	%d0
	bmis	2f
	movel	%a0@(0,%d0:l:4),%d0
	movel	%d0,%sp@(12)		| CFOO's name
	lea	cfoo_short,%a0
	movel	%a0,%sp@(8)		| the prefix "CFOO"
2:	moveal	%sp@+,%a0
	rts
.endif

	.section .hook_encobj,"ax"	| 0x40032b74: jsr FUN_40065794
	jsr	cfo_encobj
	.section .hook_samp,"ax"	| 0x4003b5a0: moveq #111,%d1 ; movel %d0,%d2 ; moveq #-17,%d0
	jsr	cfo_samptest
.endif

	.section .cfo_names,"a"
	.balign	4
.ifdef KNOBS2
fmsr2_names:
	.long	s_osc2, s_osc23, s_osc3
step_ids:				| the parameter whose step each knob takes, A..H
	.long	110, 109, 110, 110, 110, 110, 108, 108
s_zero2: .asciz	"0.00"
	.balign	4
.endif
fmsr_names:
	.long	s_off, s_osc2, s_osc23, s_osc3
s_off:	.asciz	"OFF"
s_osc2:	.asciz	"OSC2"
s_osc23: .asciz	"2+3"
s_osc3:	.asciz	"OSC3"
s_st:	.asciz	"st"
s_ct:	.asciz	"ct"
.endif

| ---- the machine name table: (long, short) per machine, 8 B each, as the stock table at 0x401a9d40.
| The two readers 0x4007910c (long) and 0x4007912c (short) are pointed here and their bound raised.

.ifdef MACHINE5
	.section .cfo_names,"a"
machnames:
	.long	0x401cc9f2, 0x401c6f07	| 0 ONESHOT / SAMP      (stock strings)
	.long	0x401c6975, 0x401c6975	| 1 WERP / WERP
	.long	0x401c6f0c, 0x401cc9fa	| 2 REPITCH / PTCH
	.long	0x401cc9ff, 0x401c6f1f	| 3 SLICE / SLIC
	.long	poly_name, poly_name	| 4 POLY / POLY
	.long	cfoo_long, cfoo_short	| 5 CFO OSCILLATOR / CFOO
poly_name:
	.asciz	"POLY"
cfoo_long:
	.asciz	"CFO OSCILLATOR"
cfoo_short:
	.asciz	"CFOO"
.endif

| ---- constant data

	.section .cfo_data,"a"
waves:
	.include "waves.inc"
mixpts:					| the mix crossfade's four points: G1, G2, G3
	.word	256, 0, 0
	.word	128, 128, 0
	.word	85, 85, 86
	.word	0, 128, 128
