| The modulation matrix for DT OG++ on Digitakt OS 1.54. [LFO] + [UP] or [DOWN] turns an audio track's LFO
| page into the matrix page of a source, MOD WHEEL or BREATH CTRL: the source last shown, then [UP]/[DOWN]
| step to the other. A tap of [LFO] goes back to the LFO page as it was; another page key leaves the matrix
| too. Each source has four slots per track, a destination (A..D) and a depth (E..H), which MOD WHEEL (CC
| 1/33) and BREATH CTRL (CC 2/34) apply every tick; the slots belong to the pattern's kit and are saved with
| the project. The stages build it up one change at a time (make_mm.py lists them): S57 the page alone,
| eight empty knobs under the source's name; then the options below.
|
| The changes to the build:
|   - the UI task's call of the key dispatcher (0x4000b770, jsr 0x400084fe) calls mm_key, which sees every
|     key event first and takes the ones above;
|   - three words of LfoPageView's vtable: the page's drawing (+0x10), encoder handler (+0x44) and title
|     (+0x4c) go to mm_draw, mm_enc and mm_title, which do the stock thing unless the matrix is showing;
|   - from S58 (option ENC4), the encoder interface's vtable entry too (0x401af938): the unit's knobs reach
|     the page that way, not through +0x44, so in S57 they still turned the LFO's values under the matrix;
|   - from S60 (option PASS), the audio tick's call of the LFO tick (0x40077eac, jsr 0x400ed766) calls
|     mm_pass, which adds each track's MOD WHEEL and BREATH CTRL to the engine's values first;
|   - from S62 (option EDIT), two more words of the vtable: the knob's parameter (+0x9c) and the parameter
|     set that holds a parameter (+0xa4) go to mm_knob and mm_set;
|   - from S63 (option FRONT), while the matrix shows, [UP]/[DOWN] are its own only while nothing is open
|     over the screens (a menu, a list, a held key's view): before, they were taken whenever the matrix
|     was showing, so a menu opened from the matrix page could not use them. (S63d, option DIAG, a test
|     image: the title also shows the top view's vtable, to read what the unit has there.)
|   - from S64 (option KITS), the kit writer's last copy (0x4007a76e) calls mm_kwrite, and the kit loader's
|     gate (0x4007a4ce) jumps to mm_kload;
|   - from S65 (option VIEWKEYS), KeyboardView's key handler (its vtable +8, 0x40182458) goes to mm_kbkey,
|     which takes [UP]/[DOWN] for the matrix, in place of S63's test of the top view;
|   - from S67 (option LEVTEST), the page's get (vtable +0x54) goes to mm_pget, for the level fader;
|   - from S68 (option LEVNAME), the knob cell's call of a parameter's short name (0x40030daa), which the
|     build's midi_loopback already sends to its own (NAME_NEXT), calls mm_name, which goes on to it;
|   - from S69 (option LEVTICK), the page's tick (vtable +0x2c) goes to mm_tick.
|
| The kits (S64): a pattern's kit carries its slots. The matrix keeps the slots of each of the 128 kits in
| RAM (KSLOTS) and uses the live kit's (the project's live kit, its index at +0x44). The stored kit (0xa00 B
| in a project file) has 268 B after the last field stock writes (+0x8f4): the writer puts the kit's slots
| there under a marker and a sum, the loader takes them back, and a tail without both (a project saved by
| stock) gives the kit no slots. The writer and loader are the ones every project save and load, and the
| kit's own save and reload, go through.
|
| The slots (S62): each audio track has, per source, four slots of a destination and a depth, in RAM. The
| matrix page shows them as the LFO's DEST (knobs A..D, slots 1..4) and DEPTH (E..H): mm_knob answers LFO1's
| DEST or DEP for a knob (from S70, option LFO2LIST, LFO2's DEST, so the destination list also offers LFO1's
| own parameters), and mm_set hands out, for those two, a stand-in for the track's parameter set: a
| copy of its fields under a copy of its vtable in which the get (+0x28) and the store (+0x74) read and
| write the slot instead of the sound, and the notify (+0x60), which would send the change as MIDI, does
| nothing. So the stock encoder handler, its steps, popups and DEST picker edit the slots, and nothing of
| the sound, the pattern or MIDI out changes. A turn or a knob's key with [TRK] held (the all-tracks edit)
| or a trig held (a parameter lock) is taken and does nothing. A destination is the engine slot it adds to,
| in the high byte (0 none), as LFO DEST holds it; a depth is on LFO DEP's scale, 0x4000 none, and at the
| source's top moves the parameter as far as an LFO of that depth at its peak.
| The vtable is shared by the audio and the MIDI tracks' LFO pages; the matrix is entered on an audio track
| only, and [TRK] leaves it (from S59, TRKSTAY: it stays, shown on audio tracks only; from S61, MIDIEXIT:
| a MIDI track ends it).
|
| The sources (S60): stock keeps the last CC 1 and CC 33 (MOD WHEEL) and CC 2 and CC 34 (BREATH CTRL) each
| track received, as an MSB and an LSB per track, and hands the value to an empty routine; mm_pass reads
| those pairs. They are in .bss, so zero from start-up. A POLY track takes its Source's (the track before it,
| as POLY's pools are built), so every voice of a pool follows the Source's controllers.
|
| A key event (the dispatcher's second argument): +12 the key's number, +16 its flags: bit 0 pressed, bit 4
| a short tap's release (0x01 on a press, 0x10 on a tap's release, 0x00 on a release after a hold or with
| another key). An encoder event: +12 the encoder, 1..8 knobs A..H, 9 LEVEL/DATA.
|
| Assemble with mm.ld; make_mm.py does it.

	.set	DISPATCH, 0x400084fe	| stock: the key dispatcher (brain, event)
	.set	LFO_DRAW, 0x4009743c	| stock: LfoPageView's drawing (page, bitmap)
	.set	LFO_ENC, 0x40032a78	| stock: its encoder handler (page, event) -> 1 when it took the turn
	.set	LFO_TITLE, 0x40030930	| stock: its title (page), the std::string's place in %a0, returned in %d0
	.set	REDRAW, 0x400c9a3a	| stock: a view's redraw request (view)
	.set	SHOW_TITLE, 0x40093a56	| stock: the header's title pop-up (header, const std::string *)
	.set	UNSTRING, 0x40179470	| stock: ~std::string (string)
	.set	HEADER, 108		| a page's header, which shows its title when the page changes
	.set	STRING, 0x4017af20	| stock: std::string(string, const char *, const allocator &)
	.set	DRAW, 0x400c2b88	| stock: draw a Bitmap (canvas, bitmap, x, y, centred)
	.set	EMPTY, 0x421f8f60	| stock: the empty knob's Bitmap, as the page draws a knob with no parameter
	.set	PROJECT, 0x40138882	| stock: the project
	.set	PROJ_SEQ, 0x40014d86
	.set	CUR_TRACK, 0x4001d24e	| stock: the current track (0..15; 8..15 the MIDI tracks)

	.set	K_TRK, 0x02		| key numbers
	.set	K_UP, 0x0e
	.set	K_DOWN, 0x0f
	.set	K_TRIG, 0x13		| TRIG, SRC, FLTR, AMP: 0x13..0x16
	.set	K_LFO, 0x17

	.set	STATE, 0x439d11a8	| this build's RAM after portamento's state (not cleared at start-up):
	.set	MAGIC, 0		|   long: MAGICV once the bytes below are set
	.set	PAGE, 4			|   long: the LFO page last drawn
	.set	ACTIVE, 8		|   byte: the matrix is showing
	.set	SOURCE, 9		|   byte: its source, 0 MOD WHEEL, 1 BREATH CTRL
	.set	HELD, 10		|   byte: [LFO] is held
	.set	CHORD, 11		|   byte: [UP]/[DOWN] came during this hold of [LFO]
	.set	CURK, 12		|   byte (S62): the knob whose parameter the page asked for last
	.set	LASTV, 14		|   word (S69): the shown source's pair at the page's last tick
.ifdef FRONT
.ifdef KITS
	.set	MAGICV, mm_vt + 0x0d3e0000	| (KITS: another RAM layout, another marker)
.else
	.set	MAGICV, mm_vt + 0x0d3d0000	| (from S63) the marker follows this build's layout: the RAM is not
.endif
					| cleared at start-up and outlives an OS update, so a build whose
					| code moved must not take the state another build left (S63's first
					| image did, and its stand-ins called S62's vtable two entries off)
.else
.ifdef EDIT
	.set	MAGICV, 0x4d4d5832	| 'MMX2': the state, the slots and the stand-ins below
.else
	.set	MAGICV, 0x4d4d5831	| 'MMX1'
.endif
.endif
	.set	NSRC, 2
	.set	SLOTS, 0x439d11c0	| (S62) per track and source (16 B each, track x 2 + source), four slots of
					|   a destination word and a depth word: 256 B
	.set	PROXIES, 0x439d12c0	| (S62) eight stand-in parameter sets, one per knob, 24 B each: the vtable,
					|   the real set's four words, the knob: 192 B, to 0x439d1380
	.set	PROXYB, 24
.ifdef LFO2LIST
	.set	DEST_ID, 64		| (S70) LFO2 Destination: shown for knobs A..D. Its list (0x40011348, mask
					|   0x0600) is LFO1 Destination's (mask 0x0e00) and LFO1's own parameters
					|   but its DEST; both store a destination as its engine slot
.else
	.set	DEST_ID, 55		| LFO1 Destination: shown for knobs A..D
.endif
	.set	DEPTH_ID, 59		| LFO1 Depth: shown for knobs E..H
	.set	NONE, 0			| a slot's destination word: none
	.set	ZERO, 0x4000		| a slot's depth word: none
	.set	SLOT0, (NONE << 16) + ZERO	| a slot with neither
	.set	KNOB_ID, 0x400308fe	| stock: the page's knob -> parameter (page, knob), vtable +0x9c
	.set	SET_OF, 0x400308d0	| stock: the page's parameter -> its set (page, id, lock), vtable +0xa4
	.set	ENC_TOUCH, 0x400c0686	| stock: the page's encoder state: a knob is being turned (state, knob)
	.set	ENC_MARK, 0x400c0898	|   and its mark (state, knob), as the page's drawing passes them on
	.set	KEY_HELD, 0x400d399c	| stock: a key held (key); 2 [TRK]
	.set	TRIG_VIEW, 0x40093470	| stock: the header shows a held trig's values (header)
	.set	TRIG_LOCK, 0x40093bb6	| stock: a held trig takes the page's edits as locks (header)
	.set	SET_VT, 0x4017ee58	| stock: SoundParameterSet's vtable, which mm_vt copies
	.set	EXPIRED, 0x401470de	| stock: a weak pointer has expired (weak_ptr), as the page tests its list
	.set	HELP_VT, 0x401af570	| stock: HelpBubbleView's vtable, as its objects hold it (its type 0x401af538)
	.set	DIAGBUF, 0x439d1390	| (S63d) 32 B for the diagnostic title
	.set	KB_KEY, 0x40029876	| stock: KeyboardView's key handler (view, event), its vtable +8 (0x40182458)
	.set	PAGE_GET, 0x40030a64	| stock: a page's get (page, parameter, lock), LfoPageView's vtable +0x54
	.set	POPUP, 0x40093ab0	| stock: the header's popup (header, format, ...), as "%s=%s" is shown
	.set	KSLOTS, 0x439d3400	| (S64) the slots of each of the 128 kits, 256 B each (as SLOTS): 32 KB,
					|   to 0x439db400 (above 0x439d32fb, a value in a stock table of numbers)
	.set	NKITS, 128
	.set	PROJ_PTR, 0x421fab90	| stock: the project object, once made
	.set	KIT_INDEX, 0x130	|   its live kit (+0xec), the kit's index (+0x44): the pattern's
	.set	RAMKITS, 0x4191f018	| stock: the project's 128 kits (the project data 0x409bbbfc + 0xf6341c)
	.set	KITB, 2334		|   each
	.set	LOADER, 0x4007a386	| stock: the kit loader (kit, stored), behind its gate
	.set	TAIL, 0x8f4		| a stored kit's first byte that stock neither writes nor reads (to 0xa00)
	.set	TAILMAGIC, 0x4d4d4b31	| 'MMK1': the tail holds the kit's slots, 256 B, then their sum
	.set	NAME_NEXT, 0x4001562c	| the build's: midi_loopback's short name of a parameter (set, parameter),
					|   which goes on to cfo_oscillator's and then stock's (0x4000fe8a)
	.set	PAGE_TICK, 0x40030ee0	| stock: LfoPageView's tick (page), its vtable +0x2c

	.set	LFO_TICK, 0x400ed766	| stock: the LFO tick (engine, ...), which adds the LFOs to the engine's values
	.set	MW_PAIRS, 0x421b20a4	| stock: per track (2 B) the last CC 33 (+0, LSB) and CC 1 (+1, MSB)
	.set	BC_PAIRS, 0x421b2084	| stock: the same for CC 34 and CC 2
	.set	MACHINES, 0x800019ac	| stock: the engine's copy of the tracks' machines (+0x9e, 0xa2 B a track)
	.set	POLY, 4			| the POLY machine
	.set	ENGINE, 18		| the engine object's values: track 0's slot 0
	.set	TRACKB, 106		| a track's values: 53 slots of 16 bits, a parameter's value << 8
	.set	TOP, 0x7f00		| the highest value, as the LFOs clamp

| ---- the hook

	.section .hook_key,"ax"		| 0x4000b770: jsr DISPATCH
	jsr	mm_key

	.section .vt_draw,"a"		| LfoPageView's vtable +0x10
	.long	mm_draw
	.section .vt_enc,"a"		| +0x44
	.long	mm_enc
	.section .vt_title,"a"		| +0x4c
	.long	mm_title
.ifdef ENC4
	.section .vt_enc4,"a"		| the encoder interface's vtable (the base at +4), its handler
	.long	mm_enc4
.endif
.ifdef PASS
	.section .hook_pass,"ax"	| 0x40077eac: jsr LFO_TICK
	jsr	mm_pass
.endif
.ifdef EDIT
	.section .vt_knob,"a"		| +0x9c
	.long	mm_knob
	.section .vt_set,"a"		| +0xa4
	.long	mm_set
.endif
.ifdef VIEWKEYS
	.section .vt_kbkey,"a"		| KeyboardView's vtable +8
	.long	mm_kbkey
.endif
.ifdef LEVTEST
	.section .vt_pget,"a"		| LfoPageView's vtable +0x54
	.long	mm_pget
.endif
.ifdef KITS
	.section .hook_kwrite,"ax"	| 0x4007a76e in the kit writer: movew %a3@(2332),%a2@(2290)
	jsr	mm_kwrite
	.section .hook_kload,"ax"	| 0x4007a4ce: the kit loader's gate
	jmp	mm_kload
.endif
.ifdef LEVNAME
	.section .call_name,"ax"	| 0x40030daa in the knob cell: jsr NAME_NEXT (stock: jsr 0x4000fe8a)
	jsr	mm_name
.endif
.ifdef LEVTICK
	.section .vt_tick,"a"		| LfoPageView's vtable +0x2c
	.long	mm_tick
.endif

	.section .mm_pad,"ax"

| ---- mm_key(brain, event): every key event, before the views. Takes [UP]/[DOWN] with [LFO] held or while
| the matrix shows, and the [LFO] tap that leaves it; passes everything else on to the dispatcher, the stack
| as it came. Uses %d0, %d1, %a0, %a1.
mm_key:
	bsrw	mm_init
.ifdef MIDIEXIT
	bsrw	mm_on			| (MIDIEXIT: any key on a MIDI track ends the matrix, through mm_on)
.endif
	moveal	%sp@(8),%a0		| the event
	movel	%a0@(12),%d0		| its key
	movel	%a0@(16),%d1		| its flags
	lea	STATE,%a1
	cmpil	#K_LFO,%d0
	beqs	k_lfo
.ifndef VIEWKEYS
	cmpil	#K_UP,%d0
	beqw	k_updown
	cmpil	#K_DOWN,%d0
	beqw	k_updown
.endif
.ifdef EDIT
	cmpil	#0x28,%d0		| (EDIT: a knob's own key, 0x28..0x2f, with [TRK] or a trig held while
	bcss	1f			| the matrix shows: taken, so it opens no picker that would lock or
	cmpil	#0x2f,%d0		| edit every track)
	bhis	1f
	bsrw	mm_on
	beqs	k_pass
	bsrw	mm_held
	beqs	k_pass
	rts
1:
.endif
	tstb	%a1@(ACTIVE)		| another key: a page key or [TRK] pressed leaves the matrix
	beqs	k_pass			| (from S59, TRKSTAY: a page key only, the matrix stays through
	btst	#0,%d1			| track changes)
	beqs	k_pass
.ifndef TRKSTAY
	cmpil	#K_TRK,%d0
	beqs	k_leave
.endif
	subil	#K_TRIG,%d0
	moveq	#3,%d1
	cmpl	%d0,%d1
	bcss	k_pass			| not TRIG, SRC, FLTR or AMP
k_leave:
	clrb	%a1@(ACTIVE)
	bsrw	mm_redraw
k_pass:
	jmp	DISPATCH

| [LFO]: the LFO page sees every press and release (it times a hold by them), but a release that ends a
| chord with [UP]/[DOWN], or a tap that leaves the matrix, reaches it without the tap bit: as stock sends the
| release after a chord, which ends the hold and keeps the LFO page's own page.
k_lfo:	btst	#0,%d1
	beqs	1f
	moveb	#1,%a1@(HELD)		| pressed
	clrb	%a1@(CHORD)
	bras	k_pass
1:	clrb	%a1@(HELD)		| released
	tstb	%a1@(CHORD)
	beqs	2f
	clrb	%a1@(CHORD)		| the end of [LFO] + [UP]/[DOWN]
	bclr	#4,%a0@(19)
	bras	k_pass
.ifdef TRKSTAY
2:	bsrw	mm_on			| (TRKSTAY: showing, on an audio track)
.else
2:	tstb	%a1@(ACTIVE)
.endif
	beqs	k_pass
	btst	#4,%d1
	beqs	k_pass			| a long hold: the LFO page does nothing with it
	bclr	#4,%a0@(19)		| a tap: back to the LFO page as it was
	clrb	%a1@(ACTIVE)
	bsrw	mm_update
	bras	k_pass

.ifndef VIEWKEYS
k_updown:
.ifdef TRKSTAY
	movel	%d1,%sp@-		| (TRKSTAY: the matrix's own only while it shows, on an audio track)
	movel	%d0,%sp@-
	bsrw	mm_on
	moveml	%sp@,%d0-%d1		| (the condition codes stay)
	lea	%sp@(8),%sp
.else
	tstb	%a1@(ACTIVE)
.endif
	bnes	3f
	tstb	%a1@(HELD)
.ifdef FRONT
	beqw	k_pass			| neither: as stock
	btst	#0,%d1
	beqw	k_pass
.else
	beqs	k_pass			| neither: as stock
	btst	#0,%d1
	beqs	k_pass
.endif
	bsrw	mm_track		| [LFO] + [UP]/[DOWN] pressed: on an audio track, the matrix
	moveq	#7,%d1
	cmpl	%d0,%d1
.ifdef FRONT
	bcsw	k_pass			| a MIDI track: as stock
.else
	bcss	k_pass			| a MIDI track: as stock
.endif
	lea	STATE,%a1
	moveb	#1,%a1@(ACTIVE)
	moveb	#1,%a1@(CHORD)
	bsrw	mm_update
	rts
3:
.ifdef FRONT
	movel	%d1,%sp@-		| (FRONT: while the matrix shows, [UP]/[DOWN] are its own only with nothing
	movel	%d0,%sp@-		| open over the screens; a menu, a list or a held key's view has them as stock.
	bsrw	mm_front		| Holding [LFO] to enter it is at the LFO page: the entry is not tested)
	moveml	%sp@,%d0-%d1
	lea	%sp@(8),%sp
	beqw	k_pass
	lea	STATE,%a1
.endif
.ifdef EDIT
.ifndef FRONT
	movel	%d1,%sp@-		| (EDIT: while the page's DEST list is open, [UP]/[DOWN] are the page's,
	movel	%d0,%sp@-		| as stock; from S63, FRONT covers it)
	bsrw	mm_list
	moveml	%sp@,%d0-%d1
	lea	%sp@(8),%sp
	bnew	k_pass
	lea	STATE,%a1
.endif
.endif
	btst	#0,%d1			| the matrix is showing: [UP]/[DOWN] are its own, releases taken too
	beqs	9f
	tstb	%a1@(HELD)
	beqs	4f
	moveb	#1,%a1@(CHORD)
4:	mvzb	%a1@(SOURCE),%d1
	cmpil	#K_UP,%d0
	bnes	5f
	tstl	%d1			| [UP]: the source before, if any
	beqs	9f
	subql	#1,%d1
	bras	6f
5:	addql	#1,%d1			| [DOWN]: the source after, if any
	moveq	#NSRC,%d0
	cmpl	%d1,%d0
	bles	9f
6:	moveb	%d1,%a1@(SOURCE)
	bsrw	mm_update
9:	rts
.endif

| mm_init: set the state the first time (the RAM is not cleared at start-up). Keeps every register.
mm_init:
	movel	%a0,%sp@-
	movel	%d0,%sp@-
	lea	STATE,%a0
	movel	%a0@(MAGIC),%d0
	cmpil	#MAGICV,%d0
	beqs	1f
	clrl	%a0@(PAGE)
	clrl	%a0@(ACTIVE)		| ACTIVE, SOURCE, HELD, CHORD
.ifdef EDIT
	clrl	%a0@(CURK)
	movel	%d1,%sp@-		| (EDIT: every slot none; each stand-in's vtable and knob)
.ifdef KITS
	lea	KSLOTS,%a0		| (KITS: every kit's)
	movel	#NKITS*64,%d1
.else
	lea	SLOTS,%a0
	moveq	#64,%d1
.endif
2:	movel	#SLOT0,%a0@+
	subql	#1,%d1
	bnes	2b
	lea	PROXIES,%a0
	moveq	#0,%d1
3:	movel	#mm_vt,%a0@
	movel	%d1,%a0@(20)
	lea	%a0@(PROXYB),%a0
	addql	#1,%d1
	moveq	#8,%d0
	cmpl	%d1,%d0
	bnes	3b
	movel	%sp@+,%d1
	lea	STATE,%a0
.endif
	movel	#MAGICV,%d0
	movel	%d0,%a0@(MAGIC)
1:	movel	%sp@+,%d0
	moveal	%sp@+,%a0
	rts

| mm_on: Z clear when the matrix is showing (the state set and ACTIVE; from S59, TRKSTAY, and the current
| track an audio track: on a MIDI track the LFO page is its own, the matrix kept for the next audio track;
| from S61, MIDIEXIT, a MIDI track ends it, so the next audio track shows its LFO page); %a1 = STATE. Uses
| %d0, %a1.
mm_on:
	lea	STATE,%a1
	movel	%a1@(MAGIC),%d0
	cmpil	#MAGICV,%d0
	bnes	1f
	tstb	%a1@(ACTIVE)
.ifdef TRKSTAY
	beqs	9f			| not active: Z set
	movel	%a0,%sp@-
	movel	%d1,%sp@-
	bsrw	mm_track		| %d0 = the current track
	movel	%sp@+,%d1
	moveal	%sp@+,%a0
	lea	STATE,%a1
	cmpil	#7,%d0
	bhis	8f			| a MIDI track
	tstb	%a1@(ACTIVE)		| Z clear: showing
	rts
8:
.ifdef MIDIEXIT
	clrb	%a1@(ACTIVE)		| (MIDIEXIT: ended)
.endif
	cmpl	%a1,%a1			| Z set: not showing here
9:	rts
.else
	rts
.endif
1:	cmpl	%a1,%a1			| Z set: not showing
	rts

| mm_redraw: ask the LFO page last drawn to redraw (its parent with it). Uses %d0, %d1, %a0, %a1.
mm_redraw:
	movel	STATE+PAGE,%d0
	beqs	1f
	movel	%d0,%sp@-
	jsr	REDRAW
	addql	#4,%sp
1:	rts

| mm_update: the redraw, then the page's title in its header, as the page does when it turns to its other
| page: its title (vtable +0x4c, mm_title) into a string on the stack, SHOW_TITLE, the string freed. Uses
| %d0, %d1, %a0, %a1.
mm_update:
	bsrs	mm_redraw
	movel	STATE+PAGE,%d0
	beqs	1f
	subql	#4,%sp			| the string
	moveal	%sp,%a0			| its place, for the title
	movel	%d0,%sp@-
	moveal	%d0,%a1
	moveal	%a1@,%a1
	moveal	%a1@(0x4c),%a1
	jsr	%a1@
	addql	#4,%sp
	pea	%sp@			| (header, &string)
	moveal	STATE+PAGE,%a1
	movel	%a1@(HEADER),%sp@-
	jsr	SHOW_TITLE
	addql	#8,%sp
	pea	%sp@
	jsr	UNSTRING
	addql	#8,%sp			| the argument and the string's place
1:	rts

| mm_track -> %d0 = the current track (0..15). Uses %d1, %a0, %a1.
mm_track:
	jsr	PROJECT
	movel	%d0,%sp@-
	jsr	PROJ_SEQ
	movel	%d0,%sp@
	jsr	CUR_TRACK
	addql	#4,%sp
	rts

| ---- mm_draw(page, bitmap): LfoPageView's drawing. Notes the page; with the matrix showing, its eight knobs
| as the page draws a knob with no parameter (A..D at y 34, E..H at y 7, x 25 + 26 per column), then the
| page's own drawing after the knobs (vtable +0x98), and not the LFO's waveform.
mm_draw:
	movel	%sp@(4),%d0
	movel	%d0,STATE+PAGE
	bsrw	mm_on
	bnes	1f
	jmp	LFO_DRAW
.ifdef EDIT
| (EDIT) each knob as the page draws its parameters: its cell (vtable +0x94) with (page, bitmap, x, y, the
| parameter, the slot's word, the knob being turned, not locked, its mark, 0); x 24 + 26 per column, y 27
| for A..D and 0 for E..H.
1:	lea	%sp@(-24),%sp
	moveml	%d2-%d5/%a2-%a3,%sp@
	moveal	%sp@(28),%a2		| the page
	moveal	%sp@(32),%a3		| the bitmap
	moveq	#0,%d2			| the knob, 0..7
2:	movel	%d2,%d0
	bsrw	mm_word
	mvsw	%a0@,%d3		| its word
	movel	%d2,%sp@-
	pea	%a2@(0x94)		| the page's encoder state
	jsr	ENC_MARK
	addql	#8,%sp
	moveq	#0,%d4
	moveb	%d0,%d4			| (a byte)
	movel	%d2,%sp@-
	pea	%a2@(0x94)
	jsr	ENC_TOUCH
	addql	#8,%sp
	clrl	%sp@-			| 0
	movel	%d4,%sp@-		| the mark
	clrl	%sp@-			| not locked
	movel	%d0,%sp@-		| being turned
	movel	%d3,%sp@-		| the word
	moveq	#DEST_ID,%d0
	moveq	#27,%d1
	moveq	#4,%d5
	cmpl	%d5,%d2
	blts	3f
	moveq	#DEPTH_ID,%d0
	moveq	#0,%d1
3:	movel	%d0,%sp@-		| the parameter
	movel	%d1,%sp@-		| y
	moveq	#3,%d0
	andl	%d2,%d0
	moveq	#26,%d1
	mulsl	%d1,%d0
	addil	#24,%d0
	movel	%d0,%sp@-		| x
	movel	%a3,%sp@-
	movel	%a2,%sp@-
	moveal	%a2@,%a0
	moveal	%a0@(0x94),%a0
	jsr	%a0@
	lea	%sp@(40),%sp
	addql	#1,%d2
	moveq	#8,%d0
	cmpl	%d2,%d0
	bnes	2b
	clrl	%sp@-			| the page's own drawing after the knobs: vtable +0x98 (page, bitmap, 0)
	movel	%a3,%sp@-
	movel	%a2,%sp@-
	moveal	%a2@,%a0
	moveal	%a0@(0x98),%a0
	jsr	%a0@
	lea	%sp@(12),%sp
	moveml	%sp@,%d2-%d5/%a2-%a3
	lea	%sp@(24),%sp
	rts
.else
1:	movel	%d2,%sp@-
	movel	%d3,%sp@-
	moveq	#0,%d2			| the knob, 0..7
2:	clrl	%sp@-			| not centred
	moveq	#34,%d0			| y: A..D at 34, E..H at 7
	moveq	#4,%d1
	cmpl	%d1,%d2
	blts	3f
	moveq	#7,%d0
3:	movel	%d0,%sp@-
	moveq	#3,%d1			| x: 25 + 26 x the column
	andl	%d2,%d1
	moveq	#26,%d3
	mulsl	%d3,%d1
	addil	#25,%d1
	movel	%d1,%sp@-
	pea	EMPTY
	movel	%sp@(32),%sp@-		| the bitmap
	jsr	DRAW
	lea	%sp@(20),%sp
	addql	#1,%d2
	moveq	#8,%d0
	cmpl	%d2,%d0
	bnes	2b
	moveal	%sp@(12),%a0		| the page's own drawing after the knobs: vtable +0x98 (page, bitmap, 0)
	moveal	%a0@,%a1
	clrl	%sp@-
	movel	%sp@(20),%sp@-
	movel	%a0,%sp@-
	moveal	%a1@(0x98),%a1
	jsr	%a1@
	lea	%sp@(12),%sp
	movel	%sp@+,%d3
	movel	%sp@+,%d2
	rts
.endif

| ---- mm_enc(page, event): LfoPageView's encoder handler. With the matrix showing, a turn of A..H is taken
| and changes nothing (S57); LEVEL/DATA, and everything without the matrix, as stock. (EDIT: a turn of A..H
| goes to the stock handler, which edits the slots through mm_knob and mm_set, unless [TRK] or a trig is
| held: then it is taken and changes nothing.)
mm_enc:
	bsrw	mm_on
	beqs	1f
	moveal	%sp@(8),%a0
	movel	%a0@(12),%d0		| the encoder
	subql	#1,%d0
.ifdef LEVTEST
	moveq	#8,%d1			| (LEVTEST: LEVEL/DATA sets the source by hand)
	cmpl	%d0,%d1
	beqw	mm_lev
.endif
	moveq	#7,%d1
	cmpl	%d0,%d1
	bcss	1f			| not A..H
.ifdef EDIT
	bsrw	mm_held
	beqs	1f
.endif
	moveq	#1,%d0			| taken
	rts
1:	jmp	LFO_ENC

| ---- mm_title(page), the result's place in %a0: LfoPageView's title. With the matrix showing, the source's
| name, as a std::string built in that place; returned in %d0 (and %a0), as the stock title does.
mm_title:
	movel	%a0,%sp@-		| the result's place (mm_on uses %a1 only)
	bsrw	mm_on
	bnes	1f
	moveal	%sp@+,%a0
	jmp	LFO_TITLE
1:	mvzb	%a1@(SOURCE),%d0
	lea	mm_names,%a1
	movel	%a1@(0,%d0:l:4),%d0
.ifdef DIAG
	bsrw	mm_diag			| (DIAG: the name and the top view's vtable)
.endif
	subql	#4,%sp			| room for the allocator (an empty object)
	pea	%sp@
	movel	%d0,%sp@-		| the name
	movel	%sp@(12),%sp@-		| the result's place
	jsr	STRING
	lea	%sp@(16),%sp
	movel	%sp@+,%d0
	moveal	%d0,%a0
	rts

	.balign	4
mm_names:
	.long	n_mw, n_bc
n_mw:	.asciz	"MOD WHEEL"
n_bc:	.asciz	"BREATH CTRL"
	.balign	2

.ifdef ENC4
| ---- mm_enc4(base, event) (S58): the knobs reach the page through its encoder interface, the base at
| +4, whose vtable entry (0x40032d8c in stock) takes 4 from the object's address and goes on to LFO_ENC
| without the vtable's +0x44. This does the same and goes on to mm_enc, so the matrix takes those turns.
mm_enc4:
	subql	#4,%sp@(4)
	braw	mm_enc
.endif

.ifdef PASS
| ---- mm_pass(engine, ...) (S60): the audio tick's call of the LFO tick. The engine's values were just made
| from the sound for this tick; to each audio track's, it adds its sources' modulation, then goes on to the
| LFO tick with the stack as it came, so the LFOs add theirs on top and nothing carries over to the next
| tick. A source at 0 (as at start-up) changes nothing and costs a read.
mm_pass:
.ifdef KITS
	lea	%sp@(-28),%sp		| (KITS: %a4 too, the live kit's slots)
	moveml	%d2-%d5/%a2-%a4,%sp@
	movel	STATE+MAGIC,%d0
	cmpil	#MAGICV,%d0
	bnew	9f
	bsrw	mm_kit
	moveal	%a0,%a4
	moveal	%sp@(32),%a2		| the engine
.else
	lea	%sp@(-24),%sp
	moveml	%d2-%d5/%a2-%a3,%sp@
.ifdef EDIT
	movel	STATE+MAGIC,%d0		| (EDIT: the slots only once they are set)
	cmpil	#MAGICV,%d0
	bnew	9f
.endif
	moveal	%sp@(28),%a2		| the engine
.endif
	lea	%a2@(ENGINE),%a2	| the track's slot 0
	moveal	MACHINES,%a1
	lea	%a1@(0x9e),%a1		| the track's machine
	moveq	#0,%d2			| the track
1:	moveb	%a1@,%d0		| its Source: the track itself, or for a POLY track after track 0 the
	cmpib	#POLY,%d0		| Source of the track before it (%d3 still holds it)
	bnes	2f
	tstl	%d2
	bnes	3f
2:	movel	%d2,%d3
3:	lea	MW_PAIRS,%a0
.ifdef EDIT
	bsrw	mm_value
	beqs	4f
	movel	%d3,%d1			| (EDIT: the Source's slots of the source)
	lsll	#5,%d1
.ifdef KITS
	lea	%a4@(0,%d1:l),%a3
.else
	lea	SLOTS,%a3
	addal	%d1,%a3
.endif
	bsrw	mm_apply
4:	lea	BC_PAIRS,%a0
	bsrw	mm_value
	beqs	5f
	movel	%d3,%d1
	lsll	#5,%d1
.ifdef KITS
	lea	%a4@(16,%d1:l),%a3
.else
	lea	SLOTS+16,%a3
	addal	%d1,%a3
.endif
	bsrw	mm_apply
.else
	bsrs	mm_value
	beqs	4f
	lea	mm_fixed,%a3
	bsrs	mm_apply
4:	lea	BC_PAIRS,%a0
	bsrs	mm_value
	beqs	5f
	lea	mm_fixed+16,%a3
	bsrs	mm_apply
.endif
5:	lea	%a2@(TRACKB),%a2
	lea	%a1@(0xa2),%a1
	addql	#1,%d2
	moveq	#8,%d0
	cmpl	%d2,%d0
	bnes	1b
.ifdef KITS
9:	moveml	%sp@,%d2-%d5/%a2-%a4
	lea	%sp@(28),%sp
.else
9:	moveml	%sp@,%d2-%d5/%a2-%a3
	lea	%sp@(24),%sp
.endif
	jmp	LFO_TICK

| mm_value: %a0 = a pair table, %d3 = the track -> %d0 = its controller, 0..16384, and Z set at 0. The
| 14-bit value (MSB << 7 | LSB) scaled by 128/127, so that the MSB alone at 127 reaches the top. Uses %d0, %d1.
mm_value:
	mvzw	%a0@(0,%d3:l:2),%d0	| both bytes: 0 most of the time
	beqs	1f
	mvzb	%a0@(1,%d3:l:2),%d0	| the MSB
	lsll	#7,%d0
	mvzb	%a0@(0,%d3:l:2),%d1	| the LSB
	orl	%d1,%d0
	movel	%d0,%d1
	lsrl	#7,%d1
	addl	%d1,%d0
	cmpil	#16384,%d0
	bles	1f
	movel	#16384,%d0
1:	tstl	%d0
	rts

.ifdef EDIT
| mm_apply (EDIT): %d0 = a source's value (1..16384), %a3 = its four slots (a DEST word, the engine slot in
| its high byte, 0 none; a DEPTH word, 0x4000 none), %a2 = the track's slot 0. Adds value x (depth - 0x4000)
| / 8192, rounded, to each destination: at the top, (depth - 0x4000) x 2, as far as an LFO of that depth
| moves it at its peak; clamped to 0..TOP as the LFOs clamp. Uses %d0, %d1, %d4, %d5, %a0, %a3.
mm_apply:
	movel	%d0,%d4
	moveq	#4,%d5
1:	mvzb	%a3@,%d1		| the destination
	mvzw	%a3@(2),%d0		| the depth
	addql	#4,%a3
	tstl	%d1
	beqs	3f			| none
	cmpil	#52,%d1
	bhis	3f
	lea	%a2@(0,%d1:l:2),%a0
	subil	#ZERO,%d0
	mulsl	%d4,%d0
	addil	#4096,%d0
	moveq	#13,%d1
	asrl	%d1,%d0
.else
| mm_apply: %d0 = a source's value (1..16384), %a3 = its four slots (a destination, and the change at the
| source's top in the engine's units; a destination above 52 is none), %a2 = the track's slot 0. Adds
| value x change / 16384, rounded, to each destination, clamped to 0..TOP as the LFOs clamp. Uses %d0,
| %d1, %d4, %d5, %a0, %a3.
mm_apply:
	movel	%d0,%d4
	moveq	#4,%d5
1:	mvzw	%a3@+,%d1		| the destination
	mvsw	%a3@+,%d0		| the change at the top
	cmpil	#52,%d1
	bhis	3f
	lea	%a2@(0,%d1:l:2),%a0
	mulsl	%d4,%d0
	addil	#8192,%d0
	moveq	#14,%d1
	asrl	%d1,%d0
.endif
	mvsw	%a0@,%d1
	addl	%d1,%d0
	bpls	2f
	moveq	#0,%d0
2:	cmpil	#TOP,%d0
	bles	4f
	movel	#TOP,%d0
4:	movew	%d0,%a0@
3:	subql	#1,%d5
	bnes	1b
	rts

.ifndef EDIT
| S60's assignment, the same on every audio track, for the test: MOD WHEEL closes FILTER FREQ (slot 26)
| over its whole range, BREATH CTRL raises TUNE (slot 17) by up to 12 semitones (0x100 a semitone).
	.balign	2
mm_fixed:
	.word	26, -TOP, -1, 0, -1, 0, -1, 0	| MOD WHEEL
	.word	17, 0x0c00, -1, 0, -1, 0, -1, 0	| BREATH CTRL
.endif
.endif

.ifdef EDIT
| ---- mm_knob(page, knob) (S62): LfoPageView's knob -> parameter (vtable +0x9c). With the matrix showing,
| knobs A..D (0..3) are LFO1's DEST and E..H (4..7) its DEP, and the knob is noted for mm_set; otherwise,
| and for any other knob, as stock. (From S70, LFO2LIST: A..D are LFO2's DEST, whose list also offers
| LFO1's parameters.)
mm_knob:
	bsrw	mm_on
	beqs	8f
	movel	%sp@(8),%d0
	moveq	#7,%d1
	cmpl	%d0,%d1
	bcss	8f
	moveb	%d0,STATE+CURK
	moveq	#DEST_ID,%d1
	subql	#4,%d0
	bmis	1f
	moveq	#DEPTH_ID,%d1
1:	movel	%d1,%d0
	rts
8:	jmp	KNOB_ID

| ---- mm_set(page, parameter, lock) (S62): LfoPageView's parameter -> its set (vtable +0xa4). With the matrix
| showing, for LFO1's DEST and DEP: the stand-in of the knob last asked for, its four words copied from the
| set stock gives; returned in %d0 and %a0. Otherwise as stock.
mm_set:
	bsrw	mm_on
	beqs	8f
	movel	%sp@(8),%d0
	cmpil	#DEST_ID,%d0
	beqs	1f
	cmpil	#DEPTH_ID,%d0
	bnes	8f
1:	movel	%sp@(12),%sp@-		| the real set: (page, parameter, lock)
	movel	%sp@(12),%sp@-
	movel	%sp@(12),%sp@-
	jsr	SET_OF
	lea	%sp@(12),%sp
	moveal	%d0,%a0
	mvzb	STATE+CURK,%d0
	moveq	#PROXYB,%d1
	mulsl	%d1,%d0
	lea	PROXIES,%a1
	addal	%d0,%a1
.ifdef FRONT
	movel	#mm_vt,%a1@		| (from S63: its vtable and knob too, whatever the RAM held)
	mvzb	STATE+CURK,%d0
	movel	%d0,%a1@(20)
.endif
	movel	%a0@(4),%a1@(4)
	movel	%a0@(8),%a1@(8)
	movel	%a0@(12),%a1@(12)
	movel	%a0@(16),%a1@(16)
	movel	%a1,%d0
	moveal	%a1,%a0
	rts
8:	jmp	SET_OF

| mm_word: %d0 = a knob (0..7) -> %a0 = its word in SLOTS (KITS: the live kit's slots): the slot of the
| current track's Source and the source shown, its DEST word for A..D, its DEPTH word for E..H. Uses %d0,
| %d1, %a0, %a1.
mm_word:
	movel	%d0,%sp@-
	bsrw	mm_track
	moveq	#7,%d1
	andl	%d1,%d0			| (an audio track: the matrix shows on those only)
	bsrs	mm_srcof
	lsll	#1,%d0
	mvzb	STATE+SOURCE,%d1
	addl	%d1,%d0
	lsll	#4,%d0
.ifdef KITS
	movel	%d0,%sp@-		| (KITS: in the live kit's slots)
	bsrw	mm_kit
	addal	%sp@+,%a0
.else
	lea	SLOTS,%a0
	addal	%d0,%a0
.endif
	movel	%sp@+,%d1		| the knob
	moveq	#3,%d0
	andl	%d1,%d0
	lsll	#2,%d0			| its slot's 4 B
	lsrl	#2,%d1
	lsll	#1,%d1			| + 2 for the DEPTH word
	addl	%d1,%d0
	addal	%d0,%a0
	rts

| mm_srcof: %d0 = an audio track -> its Source, as mm_pass and POLY's pools take it: the track itself, or
| for a POLY track after track 0 the Source of the track before it. Uses %d0, %d1, %a1.
mm_srcof:
	movel	%d2,%sp@-
	movel	%d3,%sp@-
	movel	%d0,%d2			| the track asked for
	moveal	MACHINES,%a1
	lea	%a1@(0x9e),%a1
	moveq	#0,%d1			| the track walked
1:	moveb	%a1@,%d3
	cmpib	#POLY,%d3
	bnes	2f
	tstl	%d1
	bnes	3f
2:	movel	%d1,%d0			| its Source
3:	cmpl	%d1,%d2
	beqs	4f
	addql	#1,%d1
	lea	%a1@(0xa2),%a1
	bras	1b
4:	movel	%sp@+,%d3
	movel	%sp@+,%d2
	rts

| mm_held: Z clear when [TRK] or a trig is held. Uses %d0, %d1, %a0, %a1.
mm_held:
	pea	K_TRK
	jsr	KEY_HELD
	addql	#4,%sp
	tstl	%d0
	bnes	9f
	movel	STATE+PAGE,%d0		| (no page drawn yet: Z set, nothing held there)
	beqs	9f
	moveal	%d0,%a0
	movel	%a0@(HEADER),%sp@-
	jsr	TRIG_VIEW
	tstb	%d0
	bnes	8f
	jsr	TRIG_LOCK
	tstb	%d0
8:	addql	#4,%sp			| (the flags stay)
9:	rts

.ifndef VIEWKEYS
.ifdef FRONT
| mm_front (S63): Z clear when nothing is open over the screens: the top view of the page's view controller
| (page +0x2c; its views in a list at +0x14, the last node at +0x18, a node's view at +8, as the controller
| hands a key to its views from the last) is the help bubble view, which stays above the screens; a menu, a
| list or the view a held [TRK] shows goes above it. Uses %d0, %a0.
mm_front:
	movel	STATE+PAGE,%d0
	beqs	9f
	moveal	%d0,%a0
	moveal	%a0@(0x2c),%a0
	moveal	%a0@(0x18),%a0
	moveal	%a0@(8),%a0
	movel	%a0@,%d0
	cmpil	#HELP_VT,%d0
	seq	%d0
	tstb	%d0
9:	rts
.endif
.endif

.ifdef VIEWKEYS
| ---- mm_kbkey(view, event) (S65): KeyboardView's key handler (vtable +8). The view controller offers a key
| to its views from the top until one takes it; [UP]/[DOWN] reach this one, where stock shifts the
| keyboard's octave, only when no view above took them (a menu, a list, a pattern selector in use). So
| here, and only here, the matrix takes them: [LFO] held + [UP]/[DOWN] opens it, and while it shows they
| step its source (mm_ud). Everything else as stock.
mm_kbkey:
	moveal	%sp@(8),%a0
	movel	%a0@(12),%d0
	cmpil	#K_UP,%d0
	beqs	1f
	cmpil	#K_DOWN,%d0
	bnes	8f
1:	movel	%a0@(16),%d1
	lea	STATE,%a1
	bsrs	mm_ud
	beqs	8f
	moveq	#1,%d0			| taken
	rts
8:	jmp	KB_KEY

| mm_ud: %d0 = [UP] or [DOWN], %d1 = its flags, %a1 = STATE -> Z clear when the matrix took it: [LFO] held
| and pressed on an audio track opens the matrix at its last source; while it shows, a press steps the
| source and a release is taken too. Uses %d0, %d1, %a0, %a1.
mm_ud:
	movel	%d1,%sp@-
	movel	%d0,%sp@-
	bsrw	mm_on
	moveml	%sp@,%d0-%d1		| (the flags stay)
	lea	%sp@(8),%sp
	bnes	3f
	tstb	%a1@(HELD)
	beqs	9f			| neither: not the matrix's (Z set)
	btst	#0,%d1
	beqs	9f
	bsrw	mm_track
	moveq	#7,%d1
	cmpl	%d0,%d1
	bcss	8f			| a MIDI track: not the matrix's
	lea	STATE,%a1
	moveb	#1,%a1@(ACTIVE)
	moveb	#1,%a1@(CHORD)
	bsrw	mm_update
	bras	7f
3:	btst	#0,%d1			| showing: a release is taken too
	beqs	7f
	tstb	%a1@(HELD)
	beqs	4f
	moveb	#1,%a1@(CHORD)
4:	mvzb	%a1@(SOURCE),%d1
	cmpil	#K_UP,%d0
	bnes	5f
	tstl	%d1
	beqs	7f
	subql	#1,%d1
	bras	6f
5:	addql	#1,%d1
	moveq	#NSRC,%d0
	cmpl	%d1,%d0
	bles	7f
6:	moveb	%d1,%a1@(SOURCE)
	bsrw	mm_update
7:	moveq	#1,%d0			| Z clear
	rts
8:	cmpl	%d0,%d0			| Z set
9:	rts
.endif

.ifdef LEVTEST
| ---- (S67, LEVTEST) The source by hand, as DT II's matrix page has it on LEVEL/DATA: the page's level fader
| shows the shown source's value for the current track (its Source on a POLY track), and LEVEL/DATA turns
| it, written where a CC 1/33 or CC 2/34 lands (stock's pairs), so the pass hears it as a controller.
|
| mm_pget(page, parameter, lock): LfoPageView's get (vtable +0x54). With the matrix showing, the level
| (parameter 10, which the fader shows) is the source's value, MSB << 8 | LSB << 1, at most TOP.
mm_pget:
	movel	%sp@(8),%d0
	cmpil	#10,%d0
	bnes	8f
	bsrw	mm_on
	beqs	8f
	bsrs	mm_mine
	mvzb	%a0@(1),%d0
	lsll	#8,%d0
	mvzb	%a0@,%d1
	addl	%d1,%d1
	addl	%d1,%d0
	cmpil	#TOP,%d0
	bles	1f
	movel	#TOP,%d0
1:	rts
8:	jmp	PAGE_GET

| mm_mine -> %a0 = the pair (+0 LSB, +1 MSB) of the shown source for the current track's Source. Uses
| %d0, %d1, %a0, %a1.
mm_mine:
	bsrw	mm_track
	moveq	#7,%d1
	andl	%d1,%d0
	bsrw	mm_srcof
	lea	MW_PAIRS,%a0
	tstb	STATE+SOURCE
	beqs	1f
	lea	BC_PAIRS,%a0
1:	addal	%d0,%a0
	addal	%d0,%a0
	rts

| mm_lev(page, event), from mm_enc: LEVEL/DATA with the matrix showing. The MSB moves by the turn (detents
| at event +16), 0..127, the LSB 0; the popup says it, the page redraws. Taken: 1.
mm_lev:
	moveal	%sp@(8),%a0
	movel	%a0@(16),%sp@-		| the turn
	bsrs	mm_mine
	movel	%sp@+,%d1
	mvzb	%a0@(1),%d0
	addl	%d1,%d0
	bpls	1f
	moveq	#0,%d0
1:	moveq	#127,%d1
	cmpl	%d0,%d1
	bges	2f
	moveq	#127,%d0
2:	moveb	%d0,%a0@(1)
	clrb	%a0@
	movel	%d0,%sp@-		| the popup: (header, "%s=%d", name, value)
	mvzb	STATE+SOURCE,%d0
	lea	mm_names,%a1
	movel	%a1@(0,%d0:l:4),%sp@-
	pea	mm_levfmt
	moveal	%sp@(16),%a0		| the page
	movel	%a0@(HEADER),%sp@-
	jsr	POPUP
	lea	%sp@(16),%sp
	bsrw	mm_redraw
	moveq	#1,%d0
	rts
mm_levfmt:
	.asciz	"%s=%d"
	.balign	2

.ifdef LEVNAME
| ---- mm_name(set, parameter) (S68, LEVNAME): the knob cell's call of a parameter's short name, which it
| draws under a knob that is not being turned. With the matrix showing, the level fader's (parameter 10)
| is the source's, "MW" or "BC"; everything else goes on to the build's next (NAME_NEXT), the stack as it
| came (midi_loopback's reads the cell's frame through it). Uses %d0, %d1, %a0, %a1.
mm_name:
	moveq	#10,%d0
	cmpl	%sp@(8),%d0
	bnes	8f
	bsrw	mm_on
	beqs	8f
	lea	%pc@(mm_short),%a0
	tstb	%a1@(SOURCE)
	beqs	1f
	addql	#3,%a0			| "BC"
1:	movel	%a0,%d0
	rts
8:	jmp	NAME_NEXT
mm_short:
	.asciz	"MW"
	.asciz	"BC"
.endif

.ifdef LEVTICK
| ---- mm_tick(page) (S69, LEVTICK): LfoPageView's tick (vtable +0x2c), which the view controller calls for
| each view in its list on its own tick (0x400cb250). A CC changes the source's pair but asks for no
| redraw, so the fader moved only when something else redrew the page. With the matrix showing, a change
| of the shown source's pair since the last tick redraws the page; then the stock tick. Uses %d0, %d1,
| %a0, %a1.
mm_tick:
	bsrw	mm_on
	beqs	8f
	bsrw	mm_mine
	movew	%a0@,%d0		| the pair, both bytes
	lea	STATE+LASTV,%a1
	cmpw	%a1@,%d0
	beqs	8f
	movew	%d0,%a1@
	bsrw	mm_redraw
8:	jmp	PAGE_TICK
.endif
.endif

.ifdef DIAG
| mm_diag (S63d, a test image): %d0 = a C string -> %d0 = DIAGBUF: the string, a space and the vtable of the
| top view of the page's controller in hex (as mm_front finds it), or 8 '-' with no page yet. Uses %d0,
| %d1, %a0, %a1.
mm_diag:
	moveal	%d0,%a0
	lea	DIAGBUF,%a1
1:	moveb	%a0@+,%d1
	beqs	2f
	moveb	%d1,%a1@+
	bras	1b
2:	moveb	#32,%a1@+
	movel	STATE+PAGE,%d0
	beqs	5f
	moveal	%d0,%a0
	moveal	%a0@(0x2c),%a0
	moveal	%a0@(0x18),%a0
	moveal	%a0@(8),%a0
	movel	%a0@,%d0
	movel	%d2,%sp@-
	movel	%d3,%sp@-
	moveq	#8,%d2
	moveq	#28,%d3
3:	movel	%d0,%d1
	lsrl	%d3,%d1
	lsll	#4,%d0
	addil	#48,%d1
	cmpil	#57,%d1
	bles	4f
	addql	#7,%d1
4:	moveb	%d1,%a1@+
	subql	#1,%d2
	bnes	3b
	movel	%sp@+,%d3
	movel	%sp@+,%d2
	bras	6f
5:	moveq	#8,%d1
7:	moveb	#45,%a1@+
	subql	#1,%d1
	bnes	7b
6:	clrb	%a1@
	movel	#DIAGBUF,%d0
	rts
.endif

.ifndef FRONT
| mm_list: Z clear when the page's DEST list is open: its weak pointer (page +0x194) is not expired. Uses
| %d0, %d1, %a0, %a1.
mm_list:
	movel	STATE+PAGE,%d0
	beqs	9f
	addil	#0x194,%d0
	movel	%d0,%sp@-
	jsr	EXPIRED
	addql	#4,%sp
	tstb	%d0
	seq	%d0
	tstb	%d0
9:	rts
.endif

| ---- The stand-in's own entries: the get (+0x28, (set, parameter) -> the word), the store (+0x74, (set,
| parameter, value, track, ...)) and the notify (+0x60, nothing). The stand-in holds its knob at +20.
mm_get:
	moveal	%sp@(4),%a0
	movel	%a0@(20),%d0
	bsrw	mm_word
	mvsw	%a0@,%d0
	rts

mm_store:
	moveal	%sp@(4),%a0
	movel	%a0@(20),%d0
	bsrw	mm_word
	movel	%sp@(12),%d0
	movew	%d0,%a0@
	bsrw	mm_redraw
	rts

mm_nop:
	moveq	#0,%d0
	rts

| mm_vt: the stand-in's vtable, SoundParameterSet's (SET_VT) with three entries of its own; the two words
| before it as SoundParameterSet's (the offset to the top and the type).
	.balign	4
	.long	0, 0x4017ed2c
mm_vt:
	.long	0x4013d5a0, 0x4013d5c2, 0x4000f0ac, 0x4000f0e4, 0x4000ff9a, 0x4000f11c, 0x4000f154, 0x400113f2
	.long	0x400116ee, 0x40011576, mm_get, 0x40010026, 0x4000ffda, 0x40011090, 0x4013d3fc, 0x40011bd8
	.long	0x4001076e, 0x400107d2, 0x4001083e, 0x4000f18c, 0x4000f3b4, 0x4000f628, 0x4000f324, 0x4000f2bc
	.long	mm_nop, 0x400101ca, 0x40010e3a, 0x40010624, 0x4000f1e2, mm_store
.endif

.ifdef KITS
| ---- mm_kit (S64) -> %a0 = the live kit's slots in KSLOTS (kit 0's while there is no project yet or its
| index is out of range). Uses %d1, %a0.
mm_kit:
	movel	PROJ_PTR,%d1
	beqs	8f
	moveal	%d1,%a0
	movel	%a0@(KIT_INDEX),%d1
	cmpil	#NKITS-1,%d1
	bhis	8f
	lsll	#8,%d1
	lea	KSLOTS,%a0
	addal	%d1,%a0
	rts
8:	lea	KSLOTS,%a0
	rts

| mm_kidx: %a0 = a kit -> %d0 = its index among the project's 128 (RAMKITS), or -1 for any other. Uses
| %d0, %d1, %a1.
mm_kidx:
	movel	%a0,%d0
	subil	#RAMKITS,%d0
	bmis	8f
	cmpil	#NKITS*KITB,%d0
	bccs	8f
	moveal	%d0,%a1			| the offset
	movel	#KITB,%d1
	divul	%d1,%d0
	mulsl	%d0,%d1
	cmpl	%a1,%d1
	bnes	8f			| not a kit's start
	rts
8:	moveq	#-1,%d0
	rts

| ---- mm_kwrite: in the kit writer (stored, kit, ...), at 0x4007a76e: %a2 = the stored kit, %a3 = the kit.
| Does the writer's copy the hook replaced, then, for one of the project's kits, writes its slots into the
| tail: the marker, the 256 B, their sum. Keeps every register but %d1, %a0, %a1 (the writer uses %d0 and
| %a2 after it).
mm_kwrite:
	movew	%a3@(2332),%a2@(2290)
	movel	%d0,%sp@-
	movel	%d2,%sp@-
	moveal	%a3,%a0
	bsrs	mm_kidx
	tstl	%d0
	bmis	9f			| not one of the project's kits: its tail as it is
	bsrw	mm_init
	lsll	#8,%d0
	lea	KSLOTS,%a0
	addal	%d0,%a0			| its slots
	lea	%a2@(TAIL),%a1
	movel	#TAILMAGIC,%a1@+
	moveq	#0,%d0			| the sum
	moveq	#64,%d1
1:	movel	%a0@+,%d2
	movel	%d2,%a1@+
	addl	%d2,%d0
	subql	#1,%d1
	bnes	1b
	movel	%d0,%a1@
9:	movel	%sp@+,%d2
	movel	%sp@+,%d0
	rts

| ---- mm_kload(kit, stored): the kit loader's gate, as stock (a kit and a stored kit of version 10 go to the
| loader; else 0 in the low byte), then, when the loader took it, the kit's slots from its tail (mm_tailin).
mm_kload:
	movel	%sp@(4),%d0		| the kit
	moveal	%sp@(8),%a0		| the stored kit
	beqs	8f
	tstl	%a0
	beqs	8f
	moveq	#10,%d1
	cmpl	%a0@,%d1
	bnes	8f
	movel	%a0,%sp@-
	movel	%d0,%sp@-
	jsr	LOADER
	addql	#8,%sp
	tstb	%d0
	beqs	9f
	movel	%d0,%sp@-
	moveal	%sp@(8),%a0
	moveal	%sp@(12),%a1
	bsrs	mm_tailin
	movel	%sp@+,%d0
9:	rts
8:	clrb	%d0
	rts

| mm_tailin: %a0 = a kit just loaded, %a1 = its stored kit. For one of the project's kits, its slots: the
| tail's when it holds the marker and the right sum, else none. Uses %d0, %d1, %a0, %a1.
mm_tailin:
	movel	%a1,%sp@-
	bsrw	mm_kidx
	moveal	%sp@+,%a1
	tstl	%d0
	bmis	9f
	bsrw	mm_init
	lsll	#8,%d0
	lea	KSLOTS,%a0
	addal	%d0,%a0			| its slots
	lea	%a1@(TAIL),%a1
	movel	%a1@+,%d0
	cmpil	#TAILMAGIC,%d0
	bnes	5f
	movel	%a1,%sp@-
	moveq	#0,%d0
	moveq	#64,%d1
1:	addl	%a1@+,%d0
	subql	#1,%d1
	bnes	1b
	cmpl	%a1@,%d0
	moveal	%sp@+,%a1		| (the flags stay)
	bnes	5f
	moveq	#64,%d1
2:	movel	%a1@+,%a0@+
	subql	#1,%d1
	bnes	2b
	rts
5:	moveq	#64,%d1			| none
6:	movel	#SLOT0,%a0@+
	subql	#1,%d1
	bnes	6b
9:	rts
.endif
