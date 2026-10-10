| CFOO's wave pictures for DT OG++ on Digitakt OS 1.54: the pictures of WAV1, WAV2 and WAV3 (knobs A, C and
| D of CFOO's SRC page: OSC1's, OSC2's and OSC3's wave) show the wave the oscillator plays at the knob's
| value, 17 x 17, as a line. The two neighbouring waveform tables (SIN, TRI, SAW, SQR) are blended as the
| synth blends them (the CFO oscillator's segfrac, then a + ((b - a) x fraction >> 8)), sampled at 17
| points over one cycle (index 16 x column; column 16 is index 0 again and closes the cycle), and each
| point's row is (127 - sample) x 16 / 255, rounded: 127 at the top, -128 at the bottom, 0 in the middle
| row. Each column is drawn from the row of the point before it to its own row, so a jump (SAW's, SQR's)
| is a vertical line.
|
| The hook: the CFO oscillator's picture hook cfo_pic (ParameterSet::vfunc_23's first 8 B jump to it),
| once it has found a CFOO knob (ids 108..115 on a sound of machine 5), takes the knob's record at
| 0x400f7c5c: `movel %sp@(8),%d0 ; subil #108,%d0` (10 B), which becomes a jump to wav_sel and two nops.
| wav_sel replays them and goes on at 0x400f7c66 for every other knob. For A, C and D it draws the picture
| itself and returns from vfunc_23: cfo_pic is jumped to from vfunc_23's entry and has left the stack as
| it was, so %sp@(4) is the parameter set, %sp@(8) the id, %sp@(12) the value (8.8, in the low word),
| %sp@(16) a flag, %sp@(24) the canvas, %sp@(28) x and %sp@(32) y, the arguments vfunc_23 passes on to a
| picture's invoker. The plain knob's invoker (STRT's, 0x400600a4, which cfo_pic gives every CFOO knob)
| draws its 17 x 17 frame with the stock bitmap drawer 0x400c2b88(canvas, bitmap, x, y, 0) and ignores
| the flag; so does this.
|
| The bitmap is built on the stack: the Bitmap vtable 0x401b7734, width 17, height 17, one word a column,
| the plane, the mask, 0 (as CFOO's icon object). In a column word, row r from the top is bit 15 + r: the
| rule of the icons this drawer draws (bit 31 is the bottom row). The mask has every row of every column,
| so the picture replaces the 17 x 17 square as the knob's frame does.
|
| Build options, one per stage:
|   PIC    the pictures; without it wav_sel only replays what the hook replaced (the first code through it)
| Assemble with wavpic.ld; make_wavpic.py does it.

	.set	BACK,	0x400f7c66	| cfo_pic after the replaced instructions: lsll #2,%d0 (the knob's record)
	.set	SEGFRAC, 0x400f7a1e	| the CFO oscillator: %d0 0..127 -> %d1 the segment, %d0 the fraction 0..256
	.set	WAVES,	0x40252724	| the CFO oscillator's tables, 256 signed bytes each: SIN, TRI, SAW, SQR
	.set	DRAW,	0x400c2b88	| stock: draw a Bitmap (canvas, bitmap, x, y, centred)
	.set	BITMAP,	0x401b7734	| stock: the Bitmap vtable
	.set	N,	17		| the picture: N x N
	.set	FR,	196		| wav_pic's frame: the saved registers, the Bitmap, the plane, the mask
	.set	BMP,	32		| the Bitmap, 28 B
	.set	PLN,	60		| the plane, N words
	.set	MSK,	128		| the mask, N words

	.section .wav_pad,"ax"
| ---- the hook's target. In: the stack as at vfunc_23's entry. Out: as cfo_pic's replaced instructions
| (%d0 the knob, 0..7) at BACK, or vfunc_23's return after drawing. Uses %d0, %d1 (cfo_pic's own).
wav_sel:
	movel	%sp@(8),%d0		| the replaced instructions: the knob, 0..7
	subil	#108,%d0
.ifdef PIC
	beqs	wav_pic			| A: OSC1's wave
	moveq	#2,%d1
	cmpl	%d0,%d1
	beqs	wav_pic			| C: OSC2's
	moveq	#3,%d1
	cmpl	%d0,%d1
	beqs	wav_pic			| D: OSC3's
.endif
	jmp	BACK

.ifdef PIC
| ---- the picture. Keeps %d2-%d7, %a2-%a6; uses %d0, %d1, %a0, %a1, as the invoker it stands for may.
wav_pic:
	lea	%sp@(-FR),%sp
	moveml	%d2-%d7/%a2-%a3,%sp@
	movel	%sp@(FR+12),%d0		| the value: its whole part, at most 127, as the synth takes it
	andil	#0xff00,%d0
	lsrl	#8,%d0
	moveq	#127,%d1
	cmpl	%d0,%d1
	bccs	1f
	movel	%d1,%d0
1:	jsr	SEGFRAC
	movel	%d0,%d3			| the fraction towards the next table, 0..256
	lsll	#8,%d1
	lea	WAVES,%a2
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
	movel	%sp@(FR+4+32),%sp@-	| y
	movel	%sp@(FR+8+28),%sp@-	| x
	pea	%a0@			| the Bitmap
	movel	%sp@(FR+16+24),%sp@-	| the canvas
	jsr	DRAW
	lea	%sp@(20),%sp
	moveml	%sp@,%d2-%d7/%a2-%a3
	lea	%sp@(FR),%sp
	rts
.endif
