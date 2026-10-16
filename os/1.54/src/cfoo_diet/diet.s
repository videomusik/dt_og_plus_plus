| The CFO oscillator's render, lighter, for DT OG++ on Digitakt OS 1.54. The same synth as the build's
| (cfo_oscillator's S27, with portamento's note read): the same arithmetic, sample for sample, in less
| time. It replaces the render's code in the CFO oscillator's pad, 0x400f77da..0x400f7b18, but for the
| CFO oscillator's segfrac at 0x400f7a1e, which the wave pictures call too and which stays as it is.
|
| Always (the code the options are added to):
|   - pitch divides the note sum by 384 with a multiply: (n >> 7) x 43691 >> 17 equals n / 384 for every
|     n it can see (0..0x570000);
|   - OSC2 and OSC3 go through one routine (osc_n) with their fields 2 B apart, the oscillator loop keeps
|     its shift count in a register (one instruction less a sample), and the track's number stays in a
|     register for the routine's first part.
| Options, one per stage:
|   IDLE  a CFOO track whose amp envelope is closed renders nothing: its block stays as the lanes left
|         it. Closed: the envelope (FUN_400716c0's state, 0x4199ed64 + 8 x track) decays (state 0) and its
|         level is within 2^16 of 0 (its high word 0 or -1; full is 2^31). The envelope multiplies the block
|         by that level after this hook, and it takes a new note's restart only after the tick the note
|         comes in, so the track's output is at most 2^-15 of a full block either way (below -90 dB). The
|         track's last level gets the no-ramp mark, so the first tick it sounds again starts on its level,
|         as before. Its phases stand still meanwhile.
|   LEAN  OSC2 (or OSC3) is not rendered when nothing hears it: its mix gain is 0 and it is not an FM
|         source, or the FM amount is 0. Its samples are then multiplied by 0 or masked out, so the output
|         is the same; its phase stands still meanwhile.
| HNOTE (the emulator harness's load only): OSC1's note from the trig's note sum, as the harness and S27,
| not portamento's glided one.
|
| Assemble with diet.ld; make_diet.py does it.

	.set	FILL,	0x40072478	| stock: the per-track level stage, (a18, engine), first after the lanes
	.set	MACH,	0x4199f466	| stock: machine type per track, this tick
.ifdef HNOTE
	.set	CURN,	0x80001f28	| stock: the trig's note sum per track
.else
	.set	CURN,	0x439d1180	| portamento: the note sum each track plays, as the build's render reads it
.endif
	.set	VOICES,	0x8000edc4	| stock: per-voice render state, stride 0x5e; +0x28 = voice on
	.set	LEVEL,	0x40074c60	| stock: (x, LEV) -> (LEV/127)^2 x (x/127), Q31, on the EMAC
	.set	VELS,	0x80001f18	| stock: the level's x per track, word
	.set	TRIGS,	0x8000122c	| stock: the lanes' trig mask for the next tick, bit = track
	.set	PITCH,	0x4019b4c0	| stock: pitch ratios, 2^(24 + n/12) at index (note sum) / 384
	.set	AMPST,	0x4199ed64	| stock: the amp envelope per track, 8 B: state (2 attack, 1 hold, 0 decay), level
	.set	PHASES,	0x439d1100	| this build: phase accumulators, 8 tracks x 3 oscillators (96 B)
	.set	LEVELS,	0x439d1160	| this build: the level each track ended its last tick on (8 longs)
	.set	WAVES,	0x40252724	| cfo_oscillator: the four waveform tables, 256 B each
	.set	MIXPTS,	0x40252b24	| cfo_oscillator: the mix crossfade's four points, G1 G2 G3 words
	.set	CFOO,	5		| the machine number of the CFO oscillator
| segfrac (0x400f7a1e, cfo_oscillator's: %d0 0..127 -> %d1 the segment, %d0 the fraction 0..256) comes
| from diet.ld, so that the bsr.w to it is the linker's.
.ifdef IDLE
	.set	idle_skip, 1		| (markers for the emulator harness: which options the code has)
.endif
.ifdef LEAN
	.set	lean_osc, 1
.endif

| The frame cfo_pad builds, as the build's: every routine reaches it through %a6.
	.set	S2,	0		| 32 words: OSC2's samples
	.set	S3,	64		| 32 words: OSC3's samples
	.set	G1,	128		| word: mix gain of OSC1 (0..256; the three sum to 256)
	.set	G2,	130
	.set	G3,	132
	.set	M2,	136		| long: FM mask for OSC2 (0 or -1)
	.set	M3,	140		| long: FM mask for OSC3
	.set	FMS,	144		| long: FM scale, OSC1's step / 8192 x the FM amount
	.set	LCUR,	148		| long: the level, Q31 >> 16, << 5, ramping over the tick
	.set	TRK,	152		| long: the track
	.set	NSUM,	156		| long: OSC1's note sum
	.set	STEP1,	160		| long: OSC1's phase step
	.set	LSTEP,	164		| long: LCUR's step per sample
	.set	REGS,	168		| saved %d2-%d7/%a2-%a6, 44 B
	.set	FRAME,	212
	.set	A18,	FRAME+4		| the stock call's arguments: the audio buffers
	.set	ENG,	FRAME+8		| and the engine object

| ---- the dispatcher: every audio tick, before the level stage (the build's hook at 0x40077fc8 calls it)

	.section .diet_a,"ax"		| 0x400f77da
cfo_pad:
	lea	%sp@(-FRAME),%sp
	moveml	%d2-%d7/%a2-%a6,%sp@(REGS)
	movea.l	%sp,%a6
	clrl	%a6@(TRK)
1:	movel	%a6@(TRK),%d7
	lea	MACH,%a0
	mvzb	%a0@(0,%d7:l),%d1	| the machine this track plays this tick
	subql	#CFOO,%d1
	bnes	2f
.ifdef IDLE
	movel	%d7,%d1			| its amp envelope
	lsll	#3,%d1
	lea	AMPST,%a0
	tstl	%a0@(0,%d1:l)		| decaying,
	bnes	3f
	mvsw	%a0@(4,%d1:l),%d1	| its level within 2^16 of 0: the high word 0 or -1
	addql	#1,%d1
	lsrl	#1,%d1
	bnes	3f
	lea	LEVELS,%a0		| closed: the last level gets the no-ramp mark, and the block
	moveq	#-1,%d1			| stays as it is (the envelope's level makes it silent)
	movel	%d1,%a0@(0,%d7:l:4)
	bras	2f
3:
.endif
	movea.l	%a6@(ENG),%a5
	movel	%d7,%d0
	mulu.w	#0x6a,%d0
	adda.l	%d0,%a5			| this track's engine block
	bsrw	track
2:	addql	#1,%a6@(TRK)
	moveq	#8,%d0
	cmpl	%a6@(TRK),%d0
	bnes	1b
	moveml	%sp@(REGS),%d2-%d7/%a2-%a6
	lea	%sp@(FRAME),%sp
	jmp	FILL

| ---- one track: %a5 = its engine block, %d7 = the track. CFOO's knobs, by SRC slot (8.8): A +0x34 OSC1
| wave, B +0x36 FM source (0 OSC2, 1 OSC2+3, 2 OSC3), C +0x38 OSC2 wave, D +0x3a OSC3 wave, E +0x3c the
| mix, F +0x3e FM amount, G +0x40 OSC2 detune, H +0x42 OSC3 detune. Out of range values are clamped, as the
| build does. %d7 holds the track until osc_n.
track:
	movel	%d7,%d0			| its three phases
	mulu.w	#12,%d0
	lea	PHASES,%a4
	adda.l	%d0,%a4
	lea	CURN,%a0		| OSC1's note sum: the note portamento plays + the table offset
	movel	%a0@(0,%d7:l:4),%d0
	addil	#0x30000,%d0
	movel	%d0,%a6@(NSUM)
	bsrw	pitch
	movel	%d0,%a6@(STEP1)
	moveq	#13,%d1			| FM scale: step / 8192 x F
	lsrl	%d1,%d0
	mvzb	%a5@(0x3e),%d1
	mulsl	%d1,%d0
	movel	%d0,%a6@(FMS)
	mvzb	%a5@(0x36),%d0		| B: FM from OSC2 for 0 and 1, from OSC3 for 1 and up (the build clamps
	moveq	#2,%d1			| B to 2 first, which gives the same)
	cmpl	%d1,%d0
	scs	%d1
	extbl	%d1
	movel	%d1,%a6@(M2)
	tstl	%d0
	sne	%d1
	extbl	%d1
	movel	%d1,%a6@(M3)
	mvzb	%a5@(0x3c),%d0		| E: the mix
	bsrw	clamp127
	bsrw	gains
	pea	0x7f00:w		| the level, Q31 >> 16: LEVEL(x, 127), velocity only
	lea	VELS,%a0
	mvzw	%a0@(0,%d7:l:2),%d0
	movel	%d0,%sp@-
	jsr	LEVEL			| keeps %d2-%d7/%a0-%a6
	addql	#8,%sp
	movel	%d7,%d1			| the lanes' de-click
	mulu.w	#0x5e,%d1
	lea	VOICES+0x28,%a0
	tstb	%a0@(0,%d1:l)
	beqs	1f
	btst	%d7,TRIGS+3		| (the mask's low byte: tracks 0..7)
	beqs	1f
	clrl	%d0
1:	clrw	%d0
	swap	%d0
	lea	LEVELS,%a0		| the ramp from the last tick's level
	lea	%a0@(0,%d7:l:4),%a0
	movel	%a0@,%d1
	movel	%d0,%a0@
	cmpil	#0x7fff,%d1		| not a level (or the no-ramp mark): no ramp
	blss	2f
	movel	%d0,%d1
2:	movel	%d0,%d2
	subl	%d1,%d2
	movel	%d2,%a6@(LSTEP)
	lsll	#5,%d1
	movel	%d1,%a6@(LCUR)
	movew	%a6@(G2),%d2		| OSC2: G, wave C, phase 1, its gain and mask
	movel	%a6@(M2),%d3
	lea	%a4@(4),%a2
	movea.l	%a6,%a3			| S2, at the frame's start
	bsrw	osc_n
	addql	#2,%a5			| OSC3: H, wave D, phase 2: the same fields 2 B on
	movew	%a6@(G3),%d2
	movel	%a6@(M3),%d3
	lea	%a4@(8),%a2
	lea	%a6@(S3),%a3
	bsrw	osc_n
	subql	#2,%a5
	mvzb	%a5@(0x34),%d0		| OSC1: A, with FM, into the track's block
	bsrw	clamp127
	bsrw	wave
	movel	%a6@(STEP1),%d6
	movel	%a6@(TRK),%d0
	lsll	#7,%d0
	movea.l	%a6@(A18),%a3
	adda.l	%d0,%a3
	movea.l	%a4,%a2
	braw	osc1_mix

| detune24: %d0 = G or H (8.8, 0x2800..0x5800, clamped to it) -> %d0 = the note-sum offset, (v - 64.0)
| x 256. %d0 holds a word, so the word compares are the build's long ones.
detune24:
	cmpiw	#0x2800,%d0
	bccs	1f
	movew	#0x2800,%d0
1:	cmpiw	#0x5800,%d0
	blss	2f
	movew	#0x5800,%d0
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

| pitch: %d0 = note sum -> %d0 = phase step per sample. Above the table's top (0x570000) the sum is taken
| down by octaves first and the step doubled back up for each, at most 0x7fffffff. Clobbers %d1, %a0, %a1.
pitch:
	tstl	%d0
	bpls	1f
	moveq	#0,%d0
1:	suba.l	%a1,%a1			| octaves above the table's top
3:	cmpil	#0x570000,%d0
	bles	2f
	subil	#0xc0000,%d0
	addql	#1,%a1
	bras	3b
2:	lsrl	#7,%d0			| / 384: (n >> 7) x 43691 >> 17, exact for 0..0x570000
	mulu.w	#0xaaab,%d0
	moveq	#17,%d1
	lsrl	%d1,%d0
	lea	PITCH,%a0
	movel	%a0@(0,%d0:l:4),%d0
	moveq	#13,%d1
	lsrl	%d1,%d0
	movel	#357,%d1
	mulsl	%d1,%d0
4:	movel	%a1,%d1			| an octave up, each
	beqs	5f
	addl	%d0,%d0
	bvss	6f			| past 0x7fffffff (the step is below 2^31 before)
	subql	#1,%a1
	bras	4b
6:	moveq	#-1,%d0
	lsrl	#1,%d0
5:	rts

| wave: %d0 = 0..127 -> %a0 = table, %a1 = the next table, %d5 = how far towards it, 0..256.
| Clobbers %d0, %d1.
wave:
	bsrw	segfrac
	movel	%d0,%d5
	lsll	#8,%d1
	lea	WAVES,%a0
	adda.l	%d1,%a0
	lea	%a0@(256),%a1
	rts

| gains: %d0 = 0..127 -> G1, G2, G3: OSC1 -> OSC1+2 -> OSC1+2+3 -> OSC2+3, crossfaded. Clobbers %d0-%d3,
| %a0, %a1.
gains:
	bsrw	segfrac
	mulu.w	#6,%d1
	lea	MIXPTS,%a0
	adda.l	%d1,%a0
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

	.section .diet_b,"ax"		| 0x400f7a5c, after segfrac

| ---- osc_n: OSC2 or OSC3, then osc32. In: %a5 the engine block (OSC3: + 2), %a2 its phase, %a3 its
| sample buffer, %d2 its mix gain (word), %d3 its FM mask. With LEAN, nothing when nothing hears it.
osc_n:
.ifdef LEAN
	tstw	%d2			| mixed in,
	bnes	1f
	tstl	%d3			| or an FM source
	beqs	2f
	tstl	%a6@(FMS)		| with FM
	beqs	2f
1:
.endif
	mvzw	%a5@(0x40),%d0		| G or H: 40.0..88.0 = -24..+24 semitones (8.8)
	bsrw	detune24
	addl	%a6@(NSUM),%d0
	bsrw	pitch
	movel	%d0,%d6
	mvzb	%a5@(0x38),%d0		| C or D: the wave
	bsrw	clamp127
	bsrw	wave

| ---- osc32: 32 samples of one oscillator into a word buffer.
| In: %a0/%a1/%d5 the wave (wave), %d6 the step, %a2 its phase, %a3 the buffer. Clobbers %d0-%d4, %d7, %a3.
osc32:
	movel	%a2@,%d4
	moveq	#31,%d7
	moveq	#24,%d3
1:	addl	%d6,%d4
	movel	%d4,%d0
	lsrl	%d3,%d0
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
2:	rts

| ---- osc1_mix: OSC1 with FM from OSC2/OSC3, the mix of the three, the level, into the track's buffer.
| In: %a0/%a1/%d5 OSC1's wave, %d6 its step, %a2 its phase, %a3 the track's 32 longs. Clobbers %d0-%d4,
| %d7, %a3-%a5. Output: +-32512 x level >> 16, at most +-2^30.
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
