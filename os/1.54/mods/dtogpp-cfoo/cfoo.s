| SPDX-License-Identifier: CC0-1.0
| dtogpp-cfoo: the CFO oscillator for Digitakt OS 1.54, machine 41 (CFOO). A 3-oscillator 8-bit FM synth,
| rendered into a track's block where the sampler would put its samples; everything after that point (the
| level stage, SRR, the filters and their envelope, the amp envelope, the mix and the effects) runs
| unchanged on it. Its SRC page: WAV1 FMSR WAV2 WAV3 / MIX FM DET2 DET3 (knobs A-H), with the waves drawn on
| WAV1-3. The build's CFO oscillator (src/cfo_oscillator/cfo.s, its last stage, and
| src/wave_pictures/wavpic.s), placed in elekloader's RAM, with the machine and its page described to
| elekloader's core 3.0 and machine-pages 1.1 instead of hooked:
|   - machine 41 through core's machine slots: its names, its icon, the menu, ONESHOT's parameters (params
|     0: the SRC slots' ids 108-115), and its own render machine (an empty voice window it fills);
|   - its SRC page (ONESHOT's, page_from 0), the knobs' names, ranges, defaults, value texts and pictures,
|     and the LFO page's names, through machine-pages' page;
|   - the render as machine-pages' ev_render_voices handler, at the same point of the audio render as the
|     build's hook (before the level stage).
| Its own sites are the ones neither of them owns: five range lookups machine-pages leaves stock, the
| encoder's step, the [FUNC] + knob snap points, the three machine-and-sample texts (the [TRK] pop-up, the
| SRC page's title and pop-up: CFOO alone, a Poly track as POLY and its Source's machine) and knob D's
| sample test.
|
| OSC1 plays dtogpp-portamento's glided note when that mod is in the build (its export dtogpp_port_notes,
| imported weakly), else the trig's note.

	.include "digitakt-mk1/core3.inc"

	.set	CFOO_ID, 41		| this machine
	.set	POLY_ID, 40		| dtogpp-poly's machine, for the texts
| stock OS 1.54
	.set	MACH,	0x4199f466	| the machine each track renders as, this tick (from 0x800018bc)
	.set	ENGINE,	0x80002760	| the render's engine block, 0x6a bytes a track (SRC slots 17-24 at +0x34)
	.set	NOTES,	0x80001f28	| note per track, MIDI note << 16
	.set	VOICES,	0x8000edc4	| per-voice render state, stride 0x5e; +0x28 = voice on
	.set	LEVEL,	0x40074c60	| (x, LEV) -> (LEV/127)^2 x (x/127), Q31, on the EMAC
	.set	VELS,	0x80001f18	| the level's x per track, word (very probably the velocity)
	.set	TRIGS,	0x8000122c	| the lanes' trig mask for the next tick, bit = track
	.set	PITCH,	0x4019b4c0	| pitch ratios, 2^(24 + n/12) at index (note sum) / 384
	.set	PRANGE,	0x40078f0c	| a parameter's {min, max, default} (id), %a0 the result
	.set	UI_REC,	0x40065794	| a parameter's UI record (id) (machine-pages' site)
	.set	UI_REC_ON, 0x4006579e	| ... after its first 10 bytes
	.set	M_TRACK, 0x4002200a	| the machine of a sound holder
	.set	M_PAGE,	0x4002b5d4	| the machine a page shows
	.set	M_SHORT, 0x4007912c	| a machine's short name
	.set	FORMAT,	0x40093ab0	| a popup's formatter
	.set	SPRINTF, 0x40000e82
	.set	PAGE_TRACK, 0x4001d24e	| a page's track
	.set	PROJECT, 0x40138882
	.set	PROJ_KIT, 0x40014d92
	.set	KIT_MACHINE, 0x4000d9c8	| (kit, track) -> its machine
	.set	SNDSET_VT, 0x4017ee58	| SoundParameterSet's vtable
	.set	FMT_MS,	0x401c41c0	| "%s: %.16s"
	.set	DRAW,	0x400c2b88	| draw a Bitmap (canvas, bitmap, x, y, centred)
	.set	BITMAP,	0x401b7734	| the Bitmap vtable
	.set	ICON_MASK, 0x4023e0a0	| the stock mask POLY's icon uses

| The frame cfo_render builds; every routine of the synth reaches it through %a6.
	.set	S2,	0		| 32 words: OSC2's samples
	.set	S3,	64		| 32 words: OSC3's samples
	.set	G1,	128		| word: mix gain of OSC1 (0..256; the three sum to 256)
	.set	G2,	130
	.set	G3,	132
	.set	M2,	136		| long: FM mask for OSC2 (0 or -1)
	.set	M3,	140		| long: FM mask for OSC3
	.set	FMS,	144		| long: FM scale, OSC1's step / 8192 x F
	.set	LCUR,	148		| long: the level, Q31 >> 16, << 5, ramping over the tick
	.set	TRK,	152		| long: the track
	.set	NSUM,	156		| long: OSC1's note sum (note << 16, the table offset)
	.set	STEP1,	160		| long: OSC1's phase step
	.set	LSTEP,	164		| long: LCUR's step per sample
	.set	REGS,	168		| saved %d2-%d7/%a2-%a6, 44 B
	.set	FRAME,	212
	.set	A18,	FRAME+4		| the handler's argument: the tracks' blocks (fw_track_blocks)

	.section .bss
	.balign	4
PHASES:	.skip	96		| phase accumulators, 8 tracks x 3 oscillators
LEVELS:	.skip	32		| the level each track ended its last tick on (8 longs)
cfo_notes: .skip 4		| the note sums OSC1 plays: dtogpp-portamento's, or NOTES

| ---- the render: machine-pages' ev_render_voices(blocks), after playback has written every track's block
| and before the level stage. Each track that renders as CFOO this tick gets the synth.

	.section .text.cforender,"ax"
cfo_render:
	lea	%sp@(-FRAME),%sp
	moveml	%d2-%d7/%a2-%a6,%sp@(REGS)
	movea.l	%sp,%a6
	movel	dtogpp_port_notes,%d0	| the glided notes, or 0 without dtogpp-portamento
	bnes	1f
	movel	#NOTES,%d0
1:	movel	%d0,cfo_notes
	clrl	%a6@(TRK)
2:	movel	%a6@(TRK),%d0
	lea	MACH,%a0
	mvzb	%a0@(0,%d0:l),%d1	| the machine this track plays this tick
	cmpil	#CFOO_ID,%d1
	bnes	3f
	lea	ENGINE,%a5
	mulu.w	#0x6a,%d0
	adda.l	%d0,%a5			| this track's engine block
	jsr	track:l
3:	addql	#1,%a6@(TRK)
	moveq	#8,%d0
	cmpl	%a6@(TRK),%d0
	bnes	2b
	moveml	%sp@(REGS),%d2-%d7/%a2-%a6
	lea	%sp@(FRAME),%sp
	rts

| ---- the synth: the build's code, byte for byte but the note read (check_against_build.py places this
| section where the build has it). One track: %a5 = its engine block, SRC slots 17..24 at +0x34..+0x42 (8.8):
| A +0x34 OSC1 wave, B +0x36 FM source (0 OSC2, 1 OSC2+3, 2 OSC3), C +0x38 OSC2 wave, D +0x3a OSC3 wave, E
| +0x3c the mix, F +0x3e FM amount (0..127), G +0x40 OSC2 detune, H +0x42 OSC3 detune (40.0..88.0). Values
| outside the ranges can still arrive (MIDI, LFOs), so each is clamped here.

	.section .text.cfo,"ax"
track:
	movel	%a6@(TRK),%d0		| its three phases
	movel	%d0,%d1
	addl	%d0,%d0
	addl	%d1,%d0
	lsll	#2,%d0
	lea	PHASES,%a4
	adda.l	%d0,%a4
	movel	%a6@(TRK),%d0		| OSC1's note sum: the note + the table offset (no TUNE)
	moveal	cfo_notes,%a0		| (the build: lea of the notes; here their address, set each tick)
	movel	%a0@(0,%d0:l:4),%d2
	addil	#0x30000,%d2
	movel	%d2,%a6@(NSUM)
	movel	%d2,%d0
	bsrw	pitch
	movel	%d0,%a6@(STEP1)
	moveq	#13,%d1			| FM scale: step / 8192 x F
	lsrl	%d1,%d0
	mvzb	%a5@(0x3e),%d1
	mulsl	%d1,%d0
	movel	%d0,%a6@(FMS)
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
	bsrw	clamp127
	bsrw	gains
	movel	#0x7f00,%sp@-		| the level, Q31 >> 16: LEVEL(x, 127), velocity only
	movel	%a6@(TRK),%d0
	lea	VELS,%a0
	mvzw	%a0@(0,%d0:l:2),%d0
	movel	%d0,%sp@-
	jsr	LEVEL
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
	cmpil	#0x7fff,%d1		| not a level: no ramp
	blss	2f
	movel	%d0,%d1
2:	movel	%d0,%d2
	subl	%d1,%d2
	movel	%d2,%a6@(LSTEP)		| 32 steps of (new - last) / 32 end exactly on the new level
	lsll	#5,%d1
	movel	%d1,%a6@(LCUR)
	mvzw	%a5@(0x40),%d0		| OSC2: G, 40.0..88.0 = -24..+24 semitones (8.8); wave C
	bsrw	detune24
	addl	%a6@(NSUM),%d0
	bsrw	pitch
	movel	%d0,%d6
	mvzb	%a5@(0x38),%d0
	bsrw	clamp127
	bsrw	wave
	lea	%a4@(4),%a2
	lea	%a6@(S2),%a3
	bsrw	osc32
	mvzw	%a5@(0x42),%d0		| OSC3: H, the same; wave D
	bsrw	detune24
	addl	%a6@(NSUM),%d0
	bsrw	pitch
	movel	%d0,%d6
	mvzb	%a5@(0x3a),%d0
	bsrw	clamp127
	bsrw	wave
	lea	%a4@(8),%a2
	lea	%a6@(S3),%a3
	bsrw	osc32
	mvzb	%a5@(0x34),%d0		| OSC1: A, the wave 0..127, with FM, into the buffer
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

| pitch: %d0 = note sum -> %d0 = phase step per sample (2^32 = one cycle, 48 kHz). A note sum above the
| table's top (0x570000, note 84) is taken down by octaves first, and the step doubled back up for each,
| at most 0x7fffffff. Clobbers %d1, %a0, %a1.
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
2:	movel	#384,%d1
	divul	%d1,%d0
	lea	PITCH,%a0
	movel	%a0@(0,%d0:l:4),%d0
	moveq	#13,%d1
	lsrl	%d1,%d0
	movel	#357,%d1
	mulsl	%d1,%d0
4:	movel	%a1,%d1			| an octave up, each
	beqs	5f
	cmpil	#0x40000000,%d0
	bcss	6f
	movel	#0x7fffffff,%d0
	rts
6:	addl	%d0,%d0
	subql	#1,%a1
	bras	4b
5:	rts

| wave: %d0 = 0..127 -> %a0, %a1 the two tables, %d5 the fraction of %a1, 0..256. The waves are pure at 0
| (SIN), 42 (TRI), 85 (SAW) and 127 (SQR), linear between them. Clobbers %d0, %d1.
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

| gains: %d0 = 0..127 -> G1, G2, G3: OSC1 -> OSC1+2 -> OSC1+2+3 -> OSC2+3, crossfaded, the four mixes
| exactly at 0, 42, 85 and 127. Clobbers %d0-%d3, %a0, %a1.
gains:
	bsrw	segfrac
	mulu.w	#6,%d1
	lea	mixpts,%a0
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

| osc32: 32 samples of one oscillator into a word buffer.
| In: %a0/%a1/%d5 the wave (wave), %d6 the step, %a2 its phase, %a3 the buffer. Clobbers %d0-%d4, %d7, %a3.
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

| osc1_mix: OSC1 with FM from OSC2/OSC3, the mix of the three, the level, into the track's block.
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

| ---- the page's callbacks (struct cm_knob, machine-pages) and the mod's own sites

	.section .text.cfoui,"ax"

| fmt(buf, value, ctx, machine), each knob's value text: what the synth makes of the value, the same in
| the cell (ctx 0) and the pop-up (ctx 1), at most 6 characters.
fmt_a:	moveq	#0,%d0
	bras	fmt_any
fmt_b:	moveq	#1,%d0
	bras	fmt_any
fmt_c:	moveq	#2,%d0
	bras	fmt_any
fmt_d:	moveq	#3,%d0
	bras	fmt_any
fmt_e:	moveq	#4,%d0
	bras	fmt_any
fmt_f:	moveq	#5,%d0
	bras	fmt_any
fmt_g:	moveq	#6,%d0
	bras	fmt_any
fmt_h:	moveq	#7,%d0
fmt_any:
	movel	%sp@(8),%d1		| the value (8.8)
	moveal	%sp@(4),%a0		| the buffer
	bsrw	cfo_text
	moveq	#1,%d0
	rts

| cfo_text: %d0 = knob 0..7 (A..H), %d1 = its value (8.8), %a0 = a buffer. Writes what the synth makes of
| the value, NUL-terminated: A, C, D the wave, E the mix (SIN TRI SAW SQR, OSC1 1+2 123 2+3 at 0, 42, 85,
| 127, the number between them); B OSC2, 2+3, OSC3; F the FM amount; G, H semitones from unison with two
| decimals. At most 6 B. Clobbers %d0, %d1, %a0, %a1.
cfo_text:
	andil	#0xffff,%d1
	cmpil	#6,%d0			| G and H: semitones and cents, from the 8.8 value
	bccw	t2_det
	lsrl	#8,%d1
	cmpil	#1,%d0
	beqw	t2_b
	movel	%d0,%a1			| the knob
	moveq	#127,%d0		| A, C, D, E, F: at most 127, as the synth
	cmpl	%d1,%d0
	bccs	1f
	movel	%d0,%d1
1:	movel	%a1,%d0
	subql	#5,%d0
	beqw	t_num			| F: the number
	moveq	#0,%d0			| A, C, D, E at 0, 42, 85, 127: the pure wave's or the mix's name
	tstl	%d1
	beqs	2f
	moveq	#4,%d0
	cmpil	#42,%d1
	beqs	2f
	moveq	#8,%d0
	cmpil	#85,%d1
	beqs	2f
	moveq	#12,%d0
	cmpil	#127,%d1
	bnew	t_num			| between them: the number
2:	movel	%a1,%d1
	lea	wave_cnames,%a1
	subql	#4,%d1
	bnes	3f
	lea	mix_cnames,%a1		| E
3:	moveal	%a1@(0,%d0:l),%a1
	braw	t_copy
t_num:	bsrw	t_putnum
	clrb	%a0@
	rts
t_copy:	moveb	%a1@+,%a0@+
	bnes	t_copy
	rts
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
| B: OSC2, 2+3, OSC3; above 2 as 2, as the synth.
t2_b:	moveq	#2,%d0
	cmpl	%d1,%d0
	bccs	1f
	movel	%d0,%d1
1:	lea	fmsr2_names,%a1
	moveal	%a1@(0,%d1:l:4),%a1
	braw	t_copy
| G, H: the 8.8 value, 40.0..88.0 (clamped, as the synth), as semitones from unison with two decimals, a
| hundredth being a cent: -24.00 .. 0.00 .. +24.00.
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
	bsrw	t_putnum
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

| draw(bmp, x, y, value, flag), knobs A, C and D (CM_DRAW): the wave the oscillator plays at the knob's
| value, 17 x 17, as a line. The two neighbouring tables are blended as the synth blends them (segfrac,
| then a + ((b - a) x fraction >> 8)), sampled at 17 points over one cycle (index 16 x column; column 16
| is index 0 again and closes the cycle), each point's row (127 - sample) x 16 / 255, rounded: 127 at the
| top, -128 at the bottom. Each column is drawn from the row of the point before it to its own row, so a
| jump (SAW's, SQR's) is a vertical line. The Bitmap is built on the stack (one word a column, row r from
| the top at bit 15 + r, the mask every row) and drawn with the stock drawer, as the plain knob's picture
| draws its frame.
	.set	N,	17		| the picture: N x N
	.set	FR,	196		| the frame: the saved registers, the Bitmap, the plane, the mask
	.set	BMP,	32		| the Bitmap, 28 B
	.set	PLN,	60		| the plane, N words
	.set	MSK,	128		| the mask, N words
draw_wave:
	lea	%sp@(-FR),%sp
	moveml	%d2-%d7/%a2-%a3,%sp@
	movel	%sp@(FR+16),%d0		| the value: its whole part, at most 127, as the synth takes it
	andil	#0xff00,%d0
	lsrl	#8,%d0
	moveq	#127,%d1
	cmpl	%d0,%d1
	bccs	1f
	movel	%d1,%d0
1:	jsr	segfrac:l
	movel	%d0,%d3			| the fraction towards the next table, 0..256
	lsll	#8,%d1
	lea	waves,%a2
	adda.l	%d1,%a2			| the table
	lea	%a2@(256),%a3		| the next table
	lea	%sp@(PLN),%a0		| the plane
	lea	%sp@(MSK),%a1		| the mask
	moveq	#0,%d2			| the column
2:	movel	%d2,%d4
	lsll	#4,%d4
	andil	#0xff,%d4		| the index: 16 x column, column 16 at 0 again
	mvsb	%a2@(0,%d4:l),%d5
	mvsb	%a3@(0,%d4:l),%d6
	subl	%d5,%d6
	mulsw	%d3,%d6
	asrl	#8,%d6
	addl	%d6,%d5			| the sample, -128..127, as the synth makes it
	moveq	#127,%d6
	subl	%d5,%d6			| 0..255 from the top
	mulsw	#4112,%d6		| x 16 x 257: x 16 / 255 in 16.16
	addil	#0x8000,%d6
	swap	%d6
	extl	%d6			| the row, 0..16, rounded
	tstl	%d2
	bnes	3f
	movel	%d6,%d7			| column 0: from its own row
3:	movel	%d6,%d0			| hi
	movel	%d7,%d1			| lo
	cmpl	%d1,%d0
	bges	4f
	movel	%d7,%d0
	movel	%d6,%d1
4:	subl	%d1,%d0			| hi - lo, 0..16
	moveq	#2,%d4
	lsll	%d0,%d4
	subql	#1,%d4			| hi - lo + 1 ones
	moveq	#15,%d0
	addl	%d0,%d1
	lsll	%d1,%d4			| at the rows lo..hi: bits 15 + lo .. 15 + hi
	movel	%d4,%a0@+		| the plane's column
	movel	#0xffff8000,%a1@+	| the mask's: all 17 rows
	movel	%d6,%d7			| the row before the next column's
	addql	#1,%d2
	moveq	#N,%d0
	cmpl	%d2,%d0
	bgts	2b
	lea	%sp@(BMP),%a0		| the Bitmap
	movel	#BITMAP,%a0@
	moveq	#N,%d0
	movel	%d0,%a0@(4)		| width
	movel	%d0,%a0@(8)		| height
	moveq	#1,%d0
	movel	%d0,%a0@(12)		| one word a column
	lea	%sp@(PLN),%a1
	movel	%a1,%a0@(16)
	lea	%sp@(MSK),%a1
	movel	%a1,%a0@(20)
	clrl	%a0@(24)
	clrl	%sp@-			| not centred
	movel	%sp@(FR+4+12),%sp@-	| y
	movel	%sp@(FR+8+8),%sp@-	| x
	pea	%a0@			| the Bitmap
	movel	%sp@(FR+16+4),%sp@-	| the page's Bitmap
	jsr	DRAW
	lea	%sp@(20),%sp
	moveml	%sp@,%d2-%d7/%a2-%a3
	lea	%sp@(FR),%sp
	moveq	#1,%d0
	rts

| draw(bmp, x, y, value, flag), knobs B, E, F, G and H (CM_DRAW): STRT's plain knob (id 112, whose picture
| scales by 120.0), its value rescaled from the knob's own range, so every knob turns over its whole range
| and the detunes' unison sits in the middle: the stock picture of id 112, drawn as the knob-graphic
| function draws a parameter's (its UI record's callable at +36, invoker +48, with the callable's storage,
| the value, the Bitmap, x, y and the flag's byte).
draw_b:	moveq	#1,%d0
	bras	draw_knob
draw_e:	moveq	#4,%d0
	bras	draw_knob
draw_f:	moveq	#5,%d0
	bras	draw_knob
draw_g:	moveq	#6,%d0
	bras	draw_knob
draw_h:	moveq	#7,%d0
draw_knob:
	movel	%d2,%sp@-
	mulu.w	#CM_KNOB_SIZE,%d0
	lea	cfoo_page+16+20,%a0	| knob A's min, max
	adda.l	%d0,%a0
	movel	%sp@(20),%d1		| the value (8.8) - min, clamped to 0..span
	andil	#0xffff,%d1
	subl	%a0@,%d1
	bpls	1f
	moveq	#0,%d1
1:	movel	%a0@(4),%d0
	subl	%a0@,%d0		| span, 8.8
	cmpl	%d1,%d0
	bccs	2f
	movel	%d0,%d1
2:	movel	%d0,%d2
	movel	#0x7800,%d0		| x 120.0 / span
	mulsl	%d0,%d1
	divul	%d2,%d1
	movel	%d1,%d2			| the value STRT's knob shows
	pea	112:w
	bsrw	ui_rec
	addql	#4,%sp
	moveal	%d0,%a0
	tstl	%a0@(44)
	beqs	9f			| no picture, as the stock function
	lea	%sp@(8),%a1		| the arguments: bmp, x, y, value, flag
	mvzb	%a1@(19),%d0
	movel	%d0,%sp@-		| the flag's byte
	movel	%a1@(8),%sp@-		| y
	movel	%a1@(4),%sp@-		| x
	movel	%a1@,%sp@-		| the page's Bitmap
	movel	%d2,%sp@-		| the value
	pea	%a0@(36)		| the callable's storage
	moveal	%a0@(48),%a1
	jsr	%a1@
	lea	%sp@(24),%sp
9:	movel	%sp@+,%d2
	moveq	#1,%d0
	rts

| ui_rec(id) -> %d0: a parameter's stock UI record, as FUN_40065794 gives it without machine-pages' look:
| its first 10 bytes, then on in it.
ui_rec:
	movel	%sp@(4),%d1
	cmpil	#164,%d1
	jmp	UI_REC_ON

| ---- ranges: the five lookups of a parameter's {min, max, default} (PRANGE) that machine-pages leaves
| stock, each with the parameter set, or the sound holder, where its caller keeps it: ParameterSet::
| vfunc_25 (%a2), the machine-change reset FUN_400220fc (%a2, a holder), ParameterSet::vfunc_4, the reset
| to the default (its first argument), vfunc_26, a MIDI CC (%a2), and the edit of one parameter on all
| eight tracks, MachineParameterPageView::vfunc_23 (%a3). For ids 108..115 on a CFOO sound they give the
| knob's range and default from the page; anything else goes to PRANGE untouched.
cfo_range4:				| ParameterSet::vfunc_4: its set at %sp@(28)
	moveal	%sp@(28),%a1
	bras	1f
cfo_range_a3:				| the all-tracks edit: the set in %a3
	moveal	%a3,%a1
	bras	1f
cfo_range:
	moveal	%a2,%a1			| the set or the holder in %a2
1:	movel	%sp@(4),%d0
	subil	#108,%d0
	moveq	#7,%d1
	cmpl	%d0,%d1
	bcss	9f			| not an SRC parameter
	movel	%a0,%sp@-
	movel	%d0,%sp@-
	movel	%a1,%sp@-
	bsrw	set_machine
	addql	#4,%sp
	movel	%sp@+,%d1
	moveal	%sp@+,%a0
	cmpil	#CFOO_ID,%d0
	bnes	9f
	mulu.w	#CM_KNOB_SIZE,%d1	| the knob's min, max and default
	lea	cfoo_page+16+20,%a1
	adda.l	%d1,%a1
	movel	%a1@+,%a0@
	movel	%a1@+,%a0@(4)
	movel	%a1@,%a0@(8)
	movel	%a0,%d0
	rts
9:	jmp	PRANGE

| set_machine(object) -> %d0 = the machine of its sound, -1 for none. M_TRACK takes a sound holder (its
| method at +0x28 gives the sound, the machine at the sound's +0x7e). A SoundParameterSet keeps its holder
| at +0x10. The other four parameter sets (vtables 0x80 B apart around it) have no sound. Anything else is
| taken for a holder. Clobbers %d0, %d1, %a0, %a1 and the caller's argument slot, which the caller drops.
set_machine:
	moveal	%sp@(4),%a0
	movel	%a0@,%d0
	subil	#SNDSET_VT,%d0
	beqs	1f
	addil	#0x80,%d0		| from ParameterSet's
	cmpil	#0x280,%d0
	bcss	2f			| another parameter set
	bras	3f			| a holder
1:	movel	%a0@(16),%d0		| the set's holder
	beqs	2f
	movel	%d0,%sp@(4)
3:	jmp	M_TRACK
2:	moveq	#-1,%d0
	rts

| ---- knob D is no sample slot: at 0x4003b5a0 in SamplePageView::vfunc_17, %d0 = the knob's id, the page
| in %a2. Replays 'moveq #111,%d1 ; movel %d0,%d2 ; moveq #-17,%d0', the start of its test for a Sample
| Slot parameter (which opens the sample list), except that for id 111 on a CFOO page %d0 is 0, so the
| test fails. (machine-pages' CM_NOT_SAMPLE answers SLICE's id, 135, which this page does not have.)
cfo_samptest:
	movel	%d0,%d2
	moveq	#111,%d1
	cmpl	%d2,%d1
	bnes	8f
	movel	%a2,%sp@-
	jsr	M_PAGE			| the machine the page shows
	addql	#4,%sp
	cmpil	#CFOO_ID,%d0
	bnes	8f
	moveq	#0,%d0			| (0 & id) is no Sample Slot id
	moveq	#111,%d1
	rts
8:	moveq	#-17,%d0
	moveq	#111,%d1
	rts

| ---- how a turn moves a knob. cfo_encobj(id), the page in %a2: the encoder handler's display object
| (ParameterPageView::vfunc_17's call of UI_REC at 0x40032b74), whose record sets the step: for a CFOO
| page, BR's for the knobs with whole values (A, C, D, E, F), PLAY's for B (a selector), TUNE's for G and
| H (fine steps). The pushed id is not used again. Only this call borrows them, as in the build: every
| other use of a knob's UI record gets its own id's (machine-pages' look would give it to all of them).
cfo_encobj:
	movel	%sp@(4),%d0
	subil	#108,%d0
	moveq	#7,%d1
	cmpl	%d0,%d1
	bcss	9f
	movel	%a2,%sp@-
	jsr	M_PAGE			| the machine the page shows
	addql	#4,%sp
	cmpil	#CFOO_ID,%d0
	bnes	9f
	movel	%sp@(4),%d0
	subil	#108,%d0
	lea	step_ids,%a0
	movel	%a0@(0,%d0:l:4),%d0
	movel	%d0,%sp@(4)
9:	jmp	UI_REC

| ---- [FUNC] + knob. cfo_fobj(id), %a2 = the parameter set: with [FUNC] held, ParameterSet::vfunc_11 calls
| the +0x44 callable of the knob's display object (UI_REC's result), which it takes through %a4 (lea at
| 0x40010052) with (value, delta, min, max, default), and stores what it returns. For A, C, D, E, G and H
| on a CFOO sound it gets a display object of this mod whose callable steps to the next point in the
| turn's direction; everything else UI_REC's. Only +0x44..+0x53 of the returned object are read.
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
	bsrw	set_machine
	addql	#4,%sp
	movel	%sp@+,%d1
	cmpil	#CFOO_ID,%d0
	bnes	9f
	lea	snap_waves-0x44,%a0	| A, C, D: the pure waves; E: the four mixes
	moveq	#6,%d0
	cmpl	%d0,%d1
	bcss	1f
	lea	snap_dets-0x44,%a0	| G, H: the detune points
1:	movel	%a0,%d0
	rts
9:	jmp	UI_REC

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
	tstl	%d1
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

| ---- a track's machine and sample in three texts: the [TRK] pop-up (FUN_4003bbfe), the SRC page's title
| (FUN_4003a638) and a pop-up on it (SamplePageView::vfunc_2). Each formats "%s: %.16s" of the machine's
| short name and the sample name, the format first, then the two names, in the call of its formatter
| (FORMAT, or SPRINTF for the title); that call is pointed here.
|
| trk_machine(track) -> %d0 = its machine, -1 above track 7. Clobbers %d1, %a0, %a1.
trk_machine:
	jsr	PROJECT			| the project
	movel	%d0,%sp@-
	jsr	PROJ_KIT		| its kit
	movel	%sp@(8),%sp@		| the track
	movel	%d0,%sp@-
	jsr	KIT_MACHINE
	addql	#8,%sp
	rts
| src_fmt: %a1 = the format's slot (the name's, then the sample's after it), %d0 = the track's own
| machine, %d1 = the track. CFOO: "%s", its name alone. POLY: "%s: %s" of POLY and the short name of its
| Source's machine (the nearest track before it that is not POLY, as dtogpp-poly's pool map is built), no
| sample; POLY alone without a Source. Anything else unchanged. Clobbers %d0, %d1, %a0.
src_fmt:
	cmpil	#POLY_ID,%d0
	beqs	2f
	cmpil	#CFOO_ID,%d0
	bnes	9f			| not CFOO: unchanged
1:	lea	s_name_only,%a0		| the name alone
	movel	%a0,%a1@
9:	rts
2:	moveq	#7,%d0
	cmpl	%d1,%d0
	bcss	1b			| not a track 0..7
	movel	%a1,%sp@-
	movel	%d1,%sp@-
3:	subql	#1,%sp@			| the track before
	bmis	4f			| none: no Source
	bsrw	trk_machine
	cmpil	#POLY_ID,%d0
	beqs	3b			| POLY: further back
	movel	%d0,%sp@
	jsr	M_SHORT			| its short name
	addql	#4,%sp
	moveal	%sp@+,%a1
	movel	%d0,%a1@(8)		| in place of the sample's
	lea	poly_name,%a0
	movel	%a0,%a1@(4)		| POLY
	lea	s_fmt_poly,%a0
	movel	%a0,%a1@		| "%s: %s"
	rts
4:	addql	#4,%sp
	moveal	%sp@+,%a1
	bras	1b

| cfo_trkpop2: the [TRK] pop-up's FORMAT(popup, fmt, name, sample) at 0x4003bd6a: the track's own machine
| in %d5, the track FUN_4003bbfe's argument (%fp@(8)).
cfo_trkpop2:
	lea	%sp@(8),%a1
	movel	%d5,%d0
	movel	%fp@(8),%d1
	bsrw	src_fmt
	jmp	FORMAT
| cfo_srcpop: SamplePageView::vfunc_2's FORMAT(popup, fmt, ...) at 0x4003b51c, shared with its "Sound: %d
| %s": for the machine-and-sample format only, the track's own machine in %d4 and, for a machine above 3,
| the track in %a3.
cfo_srcpop:
	movel	%sp@(8),%d0
	cmpil	#FMT_MS,%d0
	bnes	1f
	lea	%sp@(8),%a1
	movel	%d4,%d0
	movel	%a3,%d1
	bsrw	src_fmt
1:	jmp	FORMAT
| cfo_srctitle2: the title's SPRINTF(page + 0x1b0, fmt, name, sample) at 0x4003a6d8, the page in %a2: the
| track PAGE_TRACK(page +0x74) and its own machine.
cfo_srctitle2:
	movel	%a2@(116),%sp@-
	jsr	PAGE_TRACK		| the page's track
	movel	%d0,%sp@
	bsrw	trk_machine		| its own machine
	movel	%sp@+,%d1
	lea	%sp@(8),%a1
	bsrw	src_fmt
	jmp	SPRINTF

| ---- constant data: the build's tables (check_against_build.py places this section where the build has it)

	.section .rodata.cfodata,"a"
waves:					| SIN, TRI, SAW, SQR: 256 signed bytes each (make_waves.py)
	.include "waves.inc"
mixpts:					| the mix crossfade's four points: G1, G2, G3
	.word	256, 0, 0
	.word	128, 128, 0
	.word	85, 85, 86
	.word	0, 128, 128

| ---- the machine and its page

	.section .rodata.cfoo,"a"
	.balign	4
	.globl	dtogpp_cfoo_machine
dtogpp_cfoo_machine:			| ONESHOT's parameters (ids 108-115), its own render machine
	CM_MACHINE CFOO_ID, cfoo_long, cfoo_short, cfoo_icon, 0, CFOO_ID, cfoo_page

| The page: ONESHOT's SRC page, its LFO destinations in the group CFOO. Each knob: its names, its range and
| default, its value text and its picture (CM_DRAW). No knob borrows another's UI record (look): only the
| encoder's step does, through cfo_encobj.
	.set	KF,	CM_RANGE + CM_DEFAULT + CM_DRAW
	.balign	4
cfoo_page:
	CM_UI	0, group=cfoo_short, abi=cm_ui_v31
	CM_KNOB	name=n_wav1, lname=n_osc1_wave, flags=KF, max=0x7f00, fmt=fmt_a, draw=draw_wave
	CM_KNOB	name=n_fmsr, lname=n_fm_source, flags=KF, max=0x0200, fmt=fmt_b, draw=draw_b
	CM_KNOB	name=n_wav2, lname=n_osc2_wave, flags=KF, max=0x7f00, fmt=fmt_c, draw=draw_wave
	CM_KNOB	name=n_wav3, lname=n_osc3_wave, flags=KF, max=0x7f00, fmt=fmt_d, draw=draw_wave
	CM_KNOB	name=n_mix, lname=n_osc_mix, flags=KF, max=0x7f00, fmt=fmt_e, draw=draw_e
	CM_KNOB	name=n_fm, lname=n_fm_amount, flags=KF, max=0x7f00, fmt=fmt_f, draw=draw_f
	CM_KNOB	name=n_det2, lname=n_osc2_det, flags=KF, min=0x2800, max=0x5800, def=0x4000, fmt=fmt_g, draw=draw_g
	CM_KNOB	name=n_det3, lname=n_osc3_det, flags=KF, min=0x2800, max=0x5800, def=0x4000, fmt=fmt_h, draw=draw_h

| The menu's icon: two filled sawtooth ramps, 11 x 7, 1-bit, a Bitmap as the stock icons. In a column word
| the top bit is the icon's bottom row.
|    col 0         1
|        01234567890
| row 0  ....#.....#
|     1  ...##....##
|     2  ..###...###
|     3  .####..####
|     4  #####.#####
|     5  ###########
|     6  ###########
	.balign	4
cfoo_icon:
	.long	BITMAP
	.long	11, 7, 1
	.long	cfoo_plane
	.long	ICON_MASK
	.long	0
cfoo_plane:
	.long	0xe0000000, 0xf0000000, 0xf8000000, 0xfc000000, 0xfe000000, 0xc0000000
	.long	0xe0000000, 0xf0000000, 0xf8000000, 0xfc000000, 0xfe000000

| [FUNC] + knob: a display object's +0x44 callable (storage 8 B, manager, invoker), and the points
snap_waves:
	.long	snap_wpts, 0, snap_mgr, snap_inv
snap_dets:
	.long	snap_dpts, 0, snap_mgr, snap_inv
snap_wpts:				| 0 SIN / OSC1, 42 TRI / 1+2, 85 SAW / 1+2+3, 127 SQR / 2+3
	.word	0x0000, 0x2a00, 0x5500, 0x7f00, -1
snap_dpts:				| -24, -17, -12, -5, 0, +7, +12, +19, +24 semitones
	.word	0x2800, 0x2f00, 0x3400, 0x3b00, 0x4000, 0x4700, 0x4c00, 0x5300, 0x5800, -1

	.balign	4
step_ids:				| the parameter whose step each knob takes, A..H
	.long	110, 109, 110, 110, 110, 110, 108, 108
fmsr2_names:
	.long	s_osc2, s_osc23, s_osc3
wave_cnames:				| the texts at 0, 42, 85, 127: A, C, D
	.long	s_sin, s_tri, s_saw, s_sqr
mix_cnames:				| and E
	.long	s_mosc1, s_m12, s_m123, s_osc23
cfoo_long:	.asciz	"CFO OSCILLATOR"
cfoo_short:	.asciz	"CFOO"
poly_name:	.asciz	"POLY"
n_wav1:		.asciz	"WAV1"
n_fmsr:		.asciz	"FMSR"
n_wav2:		.asciz	"WAV2"
n_wav3:		.asciz	"WAV3"
n_mix:		.asciz	"MIX"
n_fm:		.asciz	"FM"
n_det2:		.asciz	"DET2"
n_det3:		.asciz	"DET3"
n_osc1_wave:	.asciz	"OSC1 Wave"
n_fm_source:	.asciz	"FM Source"
n_osc2_wave:	.asciz	"OSC2 Wave"
n_osc3_wave:	.asciz	"OSC3 Wave"
n_osc_mix:	.asciz	"Osc Mix"
n_fm_amount:	.asciz	"FM Amount"
n_osc2_det:	.asciz	"OSC2 Detune"
n_osc3_det:	.asciz	"OSC3 Detune"
s_osc2:		.asciz	"OSC2"
s_osc23:	.asciz	"2+3"
s_osc3:		.asciz	"OSC3"
s_zero2:	.asciz	"0.00"
s_sin:		.asciz	"SIN"
s_tri:		.asciz	"TRI"
s_saw:		.asciz	"SAW"
s_sqr:		.asciz	"SQR"
s_mosc1:	.asciz	"OSC1"
s_m12:		.asciz	"1+2"
s_m123:		.asciz	"123"
s_name_only:	.asciz	"%s"
s_fmt_poly:	.asciz	"%s: %s"
