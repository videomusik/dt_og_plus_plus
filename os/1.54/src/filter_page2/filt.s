| FILTER page 2 for DT OG++ on Digitakt OS 1.54: VED (velocity to envelope depth) on knob C and KEY
| (keytracking to cutoff) on knob G of every audio track's second FILTER page, both sound parameters.
| What the last stage (S52) builds:
|
|   VED  0..100 % (0..0x6400), default 0: the filter envelope's depth is multiplied by
|        1 - VED / 100 x (100 - velocity) / 127. At 0 % it is ENV's for every note, as stock; a note of
|        velocity 100, the trigs' default, keeps it at any VED; a softer note gets less, a harder one
|        more (at 100 %: x 1.21 at velocity 127, x 0.21 at 0), saturated at the depth's range.
|   KEY  1..127 with 64 (or 0, which no knob sets) as no effect, shown -394..394 % in steps of 6.25 %:
|        the cutoff moves by (KEY - 64) / 16 x 0.875 FREQ steps per semitone of the note's distance from
|        C4 (note 60). 100 % (KEY 80) is very nearly one semitone of cutoff per semitone (the stock cutoff
|        law is about 1.136 semitones per FREQ step, so exact tracking is 0.880). The note is the one the
|        track plays: portamento's glided note sum, which its rate-loop hook writes for every track each
|        tick before the filter runs.
|
| The options below build the stages before it: VED was bipolar like KEY until VEDPCT (it added
| (VED - 64) x velocity / 128 steps of ENV to the depth, saturated), and KEY moved the cutoff by a
| quarter of the above until KEY2 and by half until KEY4X.
|
| One hook in the filter stage FUN_40072844, at its call of the envelope level getter FUN_40073412
| (0x400728a2, `jsr`): the caller has FREQ << 16 at its %sp@(68), the envelope depth (ENV - 0x4000) << 17
| in %d5 and its engine block (FREQ at +2) at its %sp@(104). After the call it multiplies %d5 by the
| level, keeps the product per track (0x4399ead4 + 4 x track), adds it to FREQ << 16, saturates, and
| clamps the sum to the cutoff range. VED changes %d5 as a different ENV would; KEY adds to the caller's
| FREQ << 16. At no effect (VED 0 %, KEY 64 or 0) each leaves the depth and the frame as stock.
|
| VED and KEY are sound parameters in the sound's free value slots 48 and 49 (descriptor rows 1 and 2,
| make_filt.py); they are read, as FREQ and ENV are, from the engine's smoothed copy of the values.
|
| Build options, one per stage:
|   INERT    the filter hook only replays the call it replaced (the first code through a new hook)
|   ACTIVE   VED and KEY act
|   SAVEMOVE the sound reader's hook and the stored-index lookups' extensions move into this build's
|            pads, doing what they do now: PORT and LEG only (the first code through the new pads)
|   SAVE     (with SAVEMOVE) VED and KEY saved with the sound, at the stored indices 50 and 51 (the
|            record's words +0x80 and +0x82), and their p-locks with the pattern
|   KEY2     KEY's effect doubled, over the same range
|   SNAPINERT (with SNAP) the start-up hook only replays the clrl it replaced (the first code through it)
|   SNAP     [FUNC] + knob steps VED and KEY to the next of -63, 0 and 63: their display objects get the
|            stock callable GAIN and Stereo Width use for it (the next of minimum, middle and maximum in
|            the turn's direction), copied in by a hook in the start-up display build
|   VEDPCT   (with SAVE) VED becomes 0..100 %, default 0, the share of the depth the velocity decides
|            (the pivot 127 until VED100): the hook's VED part and the reader's VED test change; its row
|            and display objects change in make_filt.py
|   VED100   (with VEDPCT) the velocity that leaves the depth as it is becomes 100, the trigs' default:
|            the depth is multiplied by 1 - VED / 100 x (100 - velocity) / 127, so a velocity above 100
|            deepens it (up to 1 + 27 / 127 at VED 100 % and velocity 127, saturated at the depth's range)
|   KEY4X    (with VEDPCT) KEY's effect doubled again: 16 steps of KEY are one FREQ step's 0.875 per
|            semitone, about 100 % keytracking, 6.25 % a step, -63..63 about -394..394 %. The KEY
|            part's first two instructions move to the end of the VED part to make room
|   VEDLABEL VED's knob keeps its name under the picture (data only, make_filt.py; a marker here)
|   KEYOBJ   KEY's text callable from a constant object in .rodata (data only; a marker here)
|   KEYTXT   (with KEYOBJ) KEY's text in percent (key_txt, in two leftovers of Chain Recording's pads)
| Assemble with filt.ld; make_filt.py does it.

	.set	LEVEL,	0x40073412	| stock: FUN_40073412(track) -> the filter envelope's level
	.set	VELW,	0x80001f18	| stock: each track's velocity, a word 0..0x7f00 (8.8), set at a note-on
	.set	PSTATE,	0x439d1180	| this build: portamento's state, CUR[8], the note sum each track plays
	.set	ENGA,	112		| the caller's engine block pointer (its %sp@(104)), seen from the hook
	.set	VEDE,	2 * 48 - 0x32	| VED's slot from that pointer (slot s at + 2s - 0x32; FREQ, 26, at +2)
	.set	KEYE,	2 * 49 - 0x32	| KEY's slot
	.set	FREQ,	76		| the caller's FREQ << 16 (its %sp@(68)), seen from the hook
	.set	C4SUM,	60 << 16	| the note sum of C4, KEY's centre
.ifdef VEDLABEL
	.set	ved_label, 1		| (a marker: make_filt.py sets VED's flags; the harness checks them)
.endif
.ifdef KEYOBJ
	.set	key_obj, 1		| (a marker: KEY's text from the constant object, make_filt.py)
.endif

| ---- the filter stage's hook. In: %sp@(4) = the track (the getter's argument), %d5 = the depth. Out: as
| the getter, %d0 = the level; %d5 and the caller's FREQ << 16 as above. Uses %d0, %d1, %a0 and %a1, which
| the getter may use too.
	.section .filt_ved,"ax"
filt_hook:
.ifdef VEDPCT
| VED 0..100 % (0..0x6400) is how much of the depth the velocity decides: the depth is multiplied by
| 1 - VED / 100 x (127 - velocity) / 127. At 0 % every note gets the whole depth; at 100 % a note's depth
| goes with its velocity, from none at 0 to the whole at 127; at 50 % from half to the whole. No divide:
| the two divisors are one reciprocal constant, 2^32 / (100 x 127 x 16) = 21136, and the share of the
| depth to take off is formed in steps of 1/4096, rounded up. The depth only shrinks, so nothing
| saturates; at VED 0 or velocity 127 it is the stock depth exactly. With VED100, 100 takes the place of
| 127 as the velocity that leaves the depth as it is (127 stays the divisor), and a velocity above it
| makes the share negative: the depth grows, saturated.
ved_pct:
	moveal	%sp@(ENGA),%a1		| the engine block
	movel	%sp@(4),%d0		| the track
	lea	VELW,%a0
	mvzw	%a0@(0,%d0:l:2),%d1	| velocity, 0..0x7f00
.ifndef VED100
	subil	#0x7f00,%d1		| -(127 - velocity) << 8
.else
ved_100:
	subil	#0x6400,%d1		| -(100 - velocity) << 8, -100..27
.endif
	mulsw	%a1@(VEDE),%d1		| x VED: -(pivot - velocity) x VED % << 16
	swap	%d1			| -(pivot - velocity) x VED % (-12700..0; VED100 -10000..2700)
	mulsw	#21136,%d1		| x 2^32 / 203200: minus the share in 1/4096, in the high word
	swap	%d1			| in the low word (-4096: the whole depth)
	swap	%d5			| the depth >> 16 in the low word (its low 17 bits are 0)
	mulsw	%d5,%d1			| x minus the share
	swap	%d5
	asll	#4,%d1			| << 16 / 4096: minus the depth x the share (no overflow: |share| < 3227)
	addl	%d1,%d5			| the depth less its share
.ifdef VED100
	satsl	%d5			| (a negative share, a velocity above 100, deepens it)
.endif
.ifdef KEY4X
	mvsw	%a1@(KEYE),%d1		| KEY: the KEY part's first instructions, which go on with its
	tstl	%d1			| beqs on these flags (jmp leaves them)
.endif
	jmp	filt_key
.else
.ifdef ACTIVE
	moveal	%sp@(ENGA),%a1		| the engine block
	movel	%sp@(4),%d0		| the track
	mvsw	%a1@(VEDE),%d1		| VED
	tstl	%d1			| (the emulator's mvs sets no flags)
	beqs	1f			| 0: no effect
	subil	#0x4000,%d1		| -0x3f00..0x3f00, 0 = no effect
	beqs	1f
	lea	VELW,%a0
	mulsw	%a0@(0,%d0:l:2),%d1	| x velocity, 0..0x7f00
	asll	#2,%d1			| / 0x8000, << 17: as the depth
	addl	%d1,%d5
	satsl	%d5
1:	jmp	filt_key
.else
	jmp	LEVEL
.endif
.endif

.ifdef ACTIVE
	.section .filt_key,"ax"
filt_key:
.ifndef KEY4X
	mvsw	%a1@(KEYE),%d1		| KEY
	tstl	%d1
.endif
	beqs	9f			| 0: no effect
	subil	#0x4000,%d1		| -0x3f00..0x3f00, 0 = no effect
	beqs	9f
	lea	PSTATE,%a0
	movel	%a0@(0,%d0:l:4),%d0	| the note sum the track plays
	subil	#C4SUM,%d0
	asrl	#8,%d0			| semitones x 256, within a word
	mulsw	%d1,%d0			| x KEY x 0x4000
	movel	%d0,%d1
	lsll	#3,%d0
	subl	%d1,%d0			| x 7: 1.75 FREQ steps per semitone, in FREQ << 24 units
.ifndef KEY2
	asrl	#1,%d0			| x 3.5: 0.875 FREQ steps per semitone
.else
key_x2:
.endif
.ifdef KEY4X
key_x4:
	addl	%d0,%d0			| x 14: 0.875 FREQ steps per semitone at 16 steps of KEY
	satsl	%d0
.endif
	movel	%sp@(FREQ),%d1
	addl	%d0,%d1
	satsl	%d1
	movel	%d1,%sp@(FREQ)
9:	jmp	LEVEL
.ifdef KEY4X
	nop				| (never run) keeps filt_spc where the start-up build's call names it
.endif

.ifdef SNAP
| ---- the start-up display build's hook (FUN_40152280 at 0x401533c0, replacing `clrl 0x4197e3ec`, id 2's
| [FUNC] callable's manager, in id 3's block): VED's and KEY's display objects (ids 1 and 2) get, at +0x44,
| the [FUNC] callable of the shared object 0x4197d1dc (invoker 0x4005f830, GAIN's and Stereo Width's).
| %a4 holds the build's callable copier 0x40151f6c there (loaded at 0x401522b2, next written at
| 0x401545a4), which copies (dest, src): the manager and the invoker, then the manager's clone (it
| clears dest's manager first, as the replaced clrl did for id 2). %d0, %d1, %a0, %a1 are free: the
| build only pushes constants and calls through %a2..%a4 after it.
filt_spc:
.ifdef SNAPINERT
	clrl	0x4197e3ec		| only what the hook replaced (the first code through it)
	rts
.else
spc_copy:
	pea	0x4197d1dc		| the source: GAIN's and Stereo Width's [FUNC] callable
	pea	0x4197e390		| id 1 (VED) + 0x44
	jsr	%a4@
	movel	#0x4197e3e4,%sp@	| id 2 (KEY) + 0x44
	jsr	%a4@
	addql	#8,%sp
	rts
.endif
.endif
.endif

.ifdef KEYTXT
| ---- KEY's text: the knob's -63..63 as keytracking in percent, 6.25 % a step (100 % at 16), in Trig
| Probability's format. Its display object's text callable is copied at start-up from a constant object
| in the .rodata padding (make_filt.py) whose invoker this is. In: %sp@(4) the callable's storage (not
| used), %sp@(8) the value (0x0100..0x7f00), %sp@(12) the text buffer. (value x 25 - 0x4000 x 25 +
| 0x200) / 4 is the percentage x 256 plus a half; Trig Probability's invoker 0x4005fd14 prints its whole
| part (asr #8, rounded down) with "%d%%": the percentage rounded half up (12.5 shows 13, -12.5 -12).
| In two leftovers of Chain Recording's pads, 14 B and 18 B.
	.section .filt_txt1,"ax"
key_txt:
	movel	%sp@(8),%d0		| the value
	mulsw	#25,%d0
	braw	key_txt2
	.section .filt_txt2,"ax"
key_txt2:
	subil	#0x4000 * 25 - 0x200,%d0
	asrl	#2,%d0			| (KEY - 64) x 6.25 x 256 + 0x80
	movel	%d0,%sp@(8)
	jmp	0x4005fd14		| stock: Trig Probability's text, "%d%%" of the whole part
.endif

.ifdef SAVEMOVE
| ---- the sound reader's hook: FUN_4007a236 at 0x4007a2aa, the `jsr` that portamento's rd_hook takes,
| replacing `lea 0x401ac58c,%a0` between the clearing of the sound's values and its value loop. %a2 = the
| sound, %a3 = the stored record. PORT and LEG (slots 46, 47, the sound's +0x70) from the record's spare
| words +0x78 and +0x7a, as rd_hook: anything but a whole PORT of 0..127 and a LEG of 0 or 1 gives 0 for
| both. VED and KEY (slots 48, 49, the sound's +0x74) from the words +0x80 and +0x82, which the stock
| writer leaves as it finds them: anything but a whole 1..128 gives 64 (128, which no knob sets, slips
| through the test and acts as a VED or KEY of +64). With VEDPCT: VED up to 0x6400 (100 %) is kept, a
| fraction too, and anything above gives 0; KEY a whole 1..127, anything else 64. Uses %d0..%d2, %a0,
| %a1: the loop after sets them before it reads them, %d2 too.
	.section .filt_rd,"ax"
rd_hook2:
	movel	%a3@(0x78),%d0
	movel	%d0,%d1
	andil	#0x80fffeff,%d1
	beqs	1f
	clrl	%d0
1:	movel	%d0,%a2@(0x70)
.ifdef SAVE
.ifdef VEDPCT
	mvzw	%a3@(0x80),%d0		| VED: up to 100 % (0x6400) kept
	cmpil	#0x6400,%d0
	blss	2f
	clrl	%d0			| above it, 0
2:	movew	%d0,%a2@(0x74)
	mvsw	%a3@(0x82),%d0		| KEY: a whole 1..127 kept
	tstl	%d0			| (the emulator's mvs sets no flags)
	bles	3f			| 0, or 0x8000 and above
	tstb	%d0
	beqs	4f
3:	movew	#0x4000,%d0		| else 64
4:	movew	%d0,%a2@(0x76)
.else
	lea	%a3@(0x80),%a0
	lea	%a2@(0x74),%a1
	moveq	#1,%d2
2:	mvzw	%a0@+,%d0
	movel	%d0,%d1
	subil	#0x100,%d1
	andil	#0xffff80ff,%d1
	beqs	3f			| 0x100..0x8000, whole
	moveq	#64,%d0
	lsll	#8,%d0			| else 64
3:	movew	%d0,%a1@+
	subql	#1,%d2
	bpls	2b
.endif
.endif
	lea	0x401ac58c,%a0
	rts

| ---- the stored-index lookups' extensions. Portamento rewrote FUN_40079738 (stored index -> slot) and
| FUN_40079772 (slot -> stored index) in place; their test for 46 and 47 (`moveq #47,%d1 ; cmpl %d0,%d1 ;
| bccs`, 6 B) becomes a jump here, with %d0 the index or slot (above 45, for a sound's kind below 16).
	.section .filt_ext,"ax"
fwd_ext:
	moveq	#47,%d1
	cmpl	%d0,%d1
	bccs	8f			| 46, 47: themselves
.ifdef SAVE
	subql	#2,%d0
	moveq	#47,%d1
	cmpl	%d0,%d1
	bccs	9f			| 48, 49: none
	moveq	#49,%d1
	cmpl	%d0,%d1
	bccs	8f			| 50, 51: slots 48, 49
.endif
9:	clrl	%d0
8:	rts
inv_ext:
	moveq	#47,%d1
	cmpl	%d0,%d1
	bccs	8f			| 46, 47: themselves
.ifdef SAVE
	moveq	#49,%d1
	cmpl	%d0,%d1
	bcss	9f
	addql	#2,%d0			| 48, 49: stored indices 50, 51
	rts
.endif
9:	clrl	%d0
8:	rts
.endif

| ---- the names, in the .rodata padding
	.section .filt_names,"a"
s_ved:	.asciz	"VED"
s_vedl:	.asciz	"Vel to Env Depth"
s_key:	.asciz	"KEY"
s_keyl:	.asciz	"Keytracking"
