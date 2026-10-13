| SPDX-License-Identifier: CC0-1.0
| dtogpp-filter-page2: VED (velocity to envelope depth) on knob C and KEY (keytracking to cutoff) on knob G
| of every audio track's second FILTER page, Digitakt OS 1.54, both sound parameters.
|
|   VED  0..100 % (0..0x6400), default 0: the filter envelope's depth is multiplied by
|        1 - VED / 100 x (100 - velocity) / 127. At 0 % it is ENV's for every note, as stock; a note of
|        velocity 100, the trigs' default, keeps it at any VED; a softer note gets less, a harder one
|        more (at 100 %: x 1.21 at velocity 127, x 0.21 at 0), saturated at the depth's range.
|   KEY  1..127 with 64 as no effect, shown -394..394 % in steps of 6.25 %: the cutoff moves by
|        (KEY - 64) / 16 x 0.875 FREQ steps per semitone of the note's distance from C4 (note 60). 100 %
|        (KEY 80) is very nearly one semitone of cutoff per semitone. The note is the trig's note sum
|        (0x80001f28), which the NoteOn writes and a note change without a trig (a trigless NOTE lock)
|        writes too: with dtogpp-portamento the cutoff moves at once while the pitch glides.
|
| One hook in the filter stage FUN_40072844, at its call of the envelope level getter FUN_40073412
| (0x400728a2, `jsr`): the caller has FREQ << 16 at its %sp@(68), the envelope depth (ENV - 0x4000) << 17
| in %d5 and its engine block (FREQ at +2) at its %sp@(104). After the call it multiplies %d5 by the
| level, keeps the product per track, adds it to FREQ << 16, saturates, and clamps the sum to the cutoff
| range. VED changes %d5 as a different ENV would; KEY adds to the caller's FREQ << 16.
| VED and KEY are the sound's value slots 48 and 49 (descriptor rows 1 and 2, mod.json), saved by
| dtogpp-params; they are read, as FREQ and ENV are, from the engine's smoothed copy of the values.

	.set	LEVEL,	0x40073412	| stock: FUN_40073412(track) -> the filter envelope's level
	.set	VELW,	0x80001f18	| stock: each track's velocity, a word 0..0x7f00 (8.8), set at a note-on
	.set	NOTES,	0x80001f28	| stock: the trig's note sum per track (note << 16), 8 longs
	.set	ENGA,	112		| the caller's engine block pointer (its %sp@(104)), seen from the hook
	.set	VEDE,	2 * 48 - 0x32	| VED's slot from that pointer (slot s at + 2s - 0x32; FREQ, 26, at +2)
	.set	KEYE,	2 * 49 - 0x32	| KEY's slot
	.set	FREQ,	76		| the caller's FREQ << 16 (its %sp@(68)), seen from the hook
	.set	C4SUM,	60 << 16	| the note sum of C4, KEY's centre

	.section .run,"ax"

| ---- the filter stage's hook. In: %sp@(4) = the track (the getter's argument), %d5 = the depth. Out: as
| the getter, %d0 = the level; %d5 and the caller's FREQ << 16 as above. Uses %d0, %d1, %a0 and %a1, which
| the getter may use too.
| VED: no divide; the two divisors are one reciprocal constant, 2^32 / (100 x 127 x 16) = 21136, and the
| share of the depth to take off is formed in steps of 1/4096. At VED 0 or velocity 100 it is the stock
| depth exactly; a velocity above 100 makes the share negative: the depth grows, saturated.
filt_hook:
	moveal	%sp@(ENGA),%a1		| the engine block
	movel	%sp@(4),%d0		| the track
	lea	VELW,%a0
	mvzw	%a0@(0,%d0:l:2),%d1	| velocity, 0..0x7f00
	subil	#0x6400,%d1		| -(100 - velocity) << 8, -100..27
	mulsw	%a1@(VEDE),%d1		| x VED: -(100 - velocity) x VED % << 16
	swap	%d1			| -(100 - velocity) x VED % (-10000..2700)
	mulsw	#21136,%d1		| x 2^32 / 203200: minus the share in 1/4096, in the high word
	swap	%d1			| in the low word (-4096: the whole depth)
	swap	%d5			| the depth >> 16 in the low word (its low 17 bits are 0)
	mulsw	%d5,%d1			| x minus the share
	swap	%d5
	asll	#4,%d1			| << 16 / 4096: minus the depth x the share (no overflow: |share| < 3227)
	addl	%d1,%d5			| the depth less its share
	satsl	%d5
| KEY
	mvsw	%a1@(KEYE),%d1
	tstl	%d1			| (the emulator's mvs sets no flags)
	beqs	9f			| 0: no effect
	subil	#0x4000,%d1		| -0x3f00..0x3f00, 0 = no effect
	beqs	9f
	lea	NOTES,%a0
	movel	%a0@(0,%d0:l:4),%d0	| the trig's note sum: set at the NoteOn and by a trigless NOTE lock
	subil	#C4SUM,%d0
	asrl	#8,%d0			| semitones x 256, within a word
	mulsw	%d1,%d0			| x KEY x 0x4000
	movel	%d0,%d1
	lsll	#3,%d0
	subl	%d1,%d0			| x 7
	addl	%d0,%d0			| x 14: 0.875 FREQ steps per semitone at 16 steps of KEY
	satsl	%d0
	movel	%sp@(FREQ),%d1
	addl	%d0,%d1
	satsl	%d1
	movel	%d1,%sp@(FREQ)
9:	jmp	LEVEL

| ---- the start-up display build's hook (FUN_40152280 at 0x401533c0, replacing `clrl 0x4197e3ec`, id 2's
| [FUNC] callable's manager, in id 3's block): VED's and KEY's display objects (ids 1 and 2) get, at +0x44,
| the [FUNC] callable of the shared object 0x4197d1dc (invoker 0x4005f830, GAIN's and Stereo Width's): [FUNC]
| + knob steps them to the next of their minimum, middle and maximum. %a4 holds the build's callable copier
| 0x40151f6c there (loaded at 0x401522b2, next written at 0x401545a4), which copies (dest, src): the
| manager and the invoker, then the manager's clone (it clears dest's manager first, as the replaced clrl
| did for id 2). %d0, %d1, %a0, %a1 are free: the build only pushes constants and calls through %a2..%a4
| after it.
filt_spc:
	pea	0x4197d1dc		| the source: GAIN's and Stereo Width's [FUNC] callable
	pea	0x4197e390		| id 1 (VED) + 0x44
	jsr	%a4@
	movel	#0x4197e3e4,%sp@	| id 2 (KEY) + 0x44
	jsr	%a4@
	addql	#8,%sp
	rts

| ---- KEY's text: the knob's -63..63 as keytracking in percent, 6.25 % a step (100 % at 16), in Trig
| Probability's format. The invoker of KEY's text object below. In: %sp@(4) the callable's storage (not
| used), %sp@(8) the value (0x0100..0x7f00), %sp@(12) the text buffer. (value x 25 - 0x4000 x 25 + 0x200)
| / 4 is the percentage x 256 plus a half; Trig Probability's invoker 0x4005fd14 prints its whole part
| (asr #8, rounded down) with "%d%%": the percentage rounded half up (12.5 shows 13, -12.5 -12).
key_txt:
	movel	%sp@(8),%d0		| the value
	mulsw	#25,%d0
	subil	#0x4000 * 25 - 0x200,%d0
	asrl	#2,%d0			| (KEY - 64) x 6.25 x 256 + 0x80
	movel	%d0,%sp@(8)
	jmp	0x4005fd14		| stock: Trig Probability's text, "%d%%" of the whole part

| KEY's text source for the display build: a callable's storage (ENV's text manager 0x40060d88 never reads
| it: its clone allocates an empty functor), manager and invoker.
	.balign	4
key_tobj:
	.long	0, 0, 0x40060d88, key_txt

s_ved:	.asciz	"VED"
s_vedl:	.asciz	"Vel to Env Depth"
s_key:	.asciz	"KEY"
s_keyl:	.asciz	"Keytracking"
	.balign	2
