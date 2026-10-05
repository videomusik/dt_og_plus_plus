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
	.set	VOICES,	0x8000edc4	| stock: per-voice render state, stride 0x5e; +0x10 = level, Q31
	.set	PITCH,	0x4019b4c0	| stock: pitch ratios, 2^(24 + n/12) at index (note sum) / 384
	.set	PHASES,	0x439d1100	| this build: phase accumulators, 8 tracks x 3 oscillators (96 B)
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
	.set	LVL,	148		| long: the voice level, Q31 >> 16
	.set	TRK,	152		| long: the track
	.set	NSUM,	156		| long: OSC1's note sum (note << 16, TUNE, the table offset)
	.set	STEP1,	160		| long: OSC1's phase step
	.set	REGS,	164		| saved %d2-%d7/%a2-%a6, 44 B
	.set	FRAME,	208
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
	movel	%a6@(TRK),%d0		| the level: the voice's Q31 level >> 16
	mulu.w	#0x5e,%d0
	lea	VOICES+0x10,%a0
	movel	%a0@(0,%d0:l),%d0
	clrw	%d0
	swap	%d0
	movel	%d0,%a6@(LVL)
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

| ---- pitch: %d0 = note sum -> %d0 = phase step per sample (2^32 = one cycle, 48 kHz).
| The stock table gives 2^29 at note 60; (2^29 >> 13) x 357 = 23,396,352, 261.4 Hz (C4 -1 cent).
| Clobbers %d1, %a0.

	.section .cfo_pitch,"ax"
pitch:
	tstl	%d0
	bpls	1f
	moveq	#0,%d0
1:	cmpil	#0x570000,%d0
	bles	2f
	movel	#0x570000,%d0
2:	movel	#384,%d1
	divul	%d1,%d0
	lea	PITCH,%a0
	movel	%a0@(0,%d0:l:4),%d0
	moveq	#13,%d1
	lsrl	%d1,%d0
	movel	#357,%d1
	mulsl	%d1,%d0
	rts

| ---- wave: %d0 = 0..127 -> %a0 = table, %a1 = the next table, %d5 = how far towards it, 0..254.
| Clobbers %d0, %d1.

	.section .cfo_wave,"ax"
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

| ---- gains: %d0 = 0..127 -> G1, G2, G3: OSC1 -> OSC1+2 -> OSC1+2+3 -> OSC2+3, crossfaded.
| Clobbers %d0-%d3, %a0, %a1.

	.section .cfo_gains,"ax"
gains:
	movel	%d0,%d1
	addl	%d0,%d0
	addl	%d1,%d0
	movel	%d0,%d1
	lsrl	#7,%d1
	mulu.w	#6,%d1
	lea	mixpts,%a0
	adda.l	%d1,%a0
	moveq	#127,%d1
	andl	%d1,%d0
	addl	%d0,%d0
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
	mulsl	%a6@(LVL),%d0		| x the level
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
cfoo_short_names:			| ids 108..115: TUNE PLAY BR SAMP STRT LEN LOOP LEV
	.long	0x401d9fff, n_fmsr, n_mix, 0x401c6f07, n_wav1, n_fm, n_wav2, 0x401cca34
cfoo_long_names:
	.long	0x401cce1e, n_fm_source, n_osc_mix, 0x401cce3e, n_osc1_wave, n_fm_amount, n_osc23_wave, n_level
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
