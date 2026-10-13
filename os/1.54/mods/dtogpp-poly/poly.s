| SPDX-License-Identifier: CC0-1.0
| dtogpp-poly: the POLY machine and SLICE's round robin for Digitakt OS 1.54.
|
| POLY turns an audio track into an extra voice for the audio track before it: a POLY track belongs to
| its source track, the nearest audio track before it that is not POLY; together they form a voice pool.
| Every note on the source track plays on a voice of the pool with the source track's Sound (a free voice
| first, from the one after the voice used last; when all are busy, that next voice is taken), each voice
| with its own envelopes, LFO and playback position. The SRC, FLTR, AMP and LFO pages of a POLY track show
| and edit its source track's parameters, and SRC page 2 shows a playhead per voice. Mute follows the track
| a trig came from, and a held note on a pool voice is released even across a pattern switch that
| arranges the pools differently.
|
| RRBN: SLICE's Slice Select gains RRBN (and RRND, for now the same) below NOTE: each note trig plays the
| next slice of the GRID, in a separate order per track (per pool: the pool's source track's), each voice
| keeping its slice until its note ends; the parameter shows a robin icon where NOTE shows a keyboard.
|
| The machine is registered through core 3.0's machine slots (POLY_ID, the descriptor at the end); the
| rest is the build's code (os/1.54/docs/patch_listing.md: poly_engine, poly_ui, pool_cursors,
| voice_allocation, mute_by_origin, owner_latch, slice_round_robin), written out as source: each .text.*
| and .rodata.* section is one of the build's blocks, in its order, so check_against_build.py can place
| it where the build has it and compare the bytes; the SDK puts them into .run. What differs from the
| build, and why, is listed in mod.json's dtogpp_check.

	.set	POLY_ID, 40		| the machine's id (claimed as machine:40); the build's POLY was machine 4
	.set	KIT,	0x800019ac	| stock: a pointer to the UI kit (track t's sound at +0x20 + 0xa2 t)
	.set	TRIGSTART, 0x80001228	| stock: bit t, track t starts a voice this block
	.set	VOICES,	0x4399eb54	| stock: per-voice note state, 8 longs (+300: a second long per voice)

| ---- RAM, in .bss (zero at start-up). The layout is the build's (0x439d1004..0x439d1060): the code reaches
| the latched slices and the owner latch by displacement from the counters and from group_source.
	.section .bss
	.balign	4
rr_prev:	.skip	12	| +0x00 RRBN: prev[8], the last trig bit seen per track
rr_counter:	.skip	32	| +0x0c RRBN: counter[8] (u32), by pool source
rr_slice:	.skip	8	| +0x2c RRBN: slice[8], the latched slice per voice (rr_counter + 0x20)
		.skip	16	| +0x34 (in the build: Chain Recording's word and spare RAM)
owner_track:	.skip	8	| +0x44 ownerTrack[8], the track a held note came from (group_source - 8)
group_source:	.skip	8	| +0x4c groupSource[8], each track's pool source
group_cursor:	.skip	8	| +0x54 groupCursor[8], each pool's voice used last

| ---- RRBN: the slice choice. 0x40074e38: SLICE's slice-select code takes a negative SLICE value here
| (`tstl %d4 ; bpls`, then this jump); a trig starting a voice steps the track's (pool source's) counter,
| and the voice latches it; the slice is the latched value modulo the GRID (4 << GRID), back at 0x40074e62.
	.section .text.rr,"ax"		| the build: 0x400c1338
rr_main:
	movel	%sp@(40),%d1
	movel	TRIGSTART,%d4
	lsrl	%d1,%d4
	andil	#1,%d4
	lea	rr_prev,%a2
	mvzb	%a2@(0x0,%d1:l),%d3
	moveb	%d4,%a2@(0x0,%d1:l)
	moveal	%d1,%a3
	lea	group_source,%a2
	moveb	%a2@(0x0,%d1:l),%d1
	lea	rr_counter,%a2
	tstl	%d3
	bnes	1f
	tstl	%d4
	beqs	1f
	addql	#1,%a2@(0x0,%d1:l:4)
	movel	%a2@(0x0,%d1:l:4),%d3
	moveb	%d3,%a2@(0x20,%a3:l)
1:	mvzb	%a2@(0x20,%a3:l),%d1
	moveq	#4,%d3
	lsll	%d5,%d3
	subql	#1,%d3
	andl	%d3,%d1
	movel	%d1,%d2
	jmp	0x40074e62

| ---- RRBN: SLICE's value text (0x401524f2, the start-up display build's text source): -2 RRND, -1 RRBN,
| 0 the stock "NOTE" text, else the stock number text; through sprintf (0x40000e82).
	.section .text.rrfmt,"ax"	| the build: 0x400c1398
rr_fmt:
	movel	%sp@(8),%d0
	asrl	#8,%d0
	movel	%sp@(12),%d1
	cmpil	#-2,%d0
	beqs	2f
	cmpil	#-1,%d0
	beqs	3f
	tstl	%d0
	beqs	4f
	movel	%d0,%sp@(12)
	movel	#0x401d031b,%d0
	bras	5f
2:	movel	#rr_rrnd,%d0
	bras	5f
3:	movel	#rr_rrbn,%d0
	bras	5f
4:	movel	#0x401d69a1,%d0
5:	movel	%d0,%sp@(8)
	movel	%d1,%sp@(4)
	jmp	0x40000e82

	.section .rodata.rrtxt,"a"	| the build: 0x400c13e6
rr_rrnd: .asciz	"RRND"
rr_rrbn: .asciz	"RRBN"

| ---- the note-off region scan (0x40037abe): a note-off for a held note finds the pool voice that holds it
| (the same note and owner); with the owner latch the latched owner is compared, not the pool source.
	.section .text.scan,"ax"	| the build: 0x400c119c
poly_scan:
	movel	%d2,%sp@-
	lea	group_source,%a0
	clrl	%d0
	movel	%d2,%d0
	nop
	moveq	#7,%d1
	lea	VOICES,%a1
1:	cmpb	%a0@(-0x8,%d1:l),%d0
	bnes	2f
	lea	%a1@(0x0,%d1:l:4),%a4
	movel	%a4@,%d2
	cmpl	%a2@(12),%d2
	bnes	2f
	movel	%a4@(300),%d2
	cmpl	%a2@(24),%d2
	bnes	2f
	movel	%d1,%d2
	addql	#4,%sp
	rts
2:	subql	#1,%d1
	bpls	1b
	movel	%sp@+,%d2
	rts

| ---- kit load (0x4007735a, in place of `lea %sp@(28),%sp ; braw`): rebuild the pool map, then go on.
| poly_map: reset each pool's voice cursor, then poly_mapsrc: each track's pool source, from the kit's
| sounds' machines (a POLY track takes the source of the track before it).
	.section .text.kit,"ax"		| the build: 0x400bf16c
poly_kit:
	lea	%sp@(28),%sp
	jsr	poly_map:l		| absolute, as the build (gas would make it PC-relative here)
	jmp	0x40076c9e
poly_map:
	lea	group_cursor,%a1
	moveq	#7,%d0
1:	moveb	%d0,%a1@(0x0,%d0:l)
	subql	#1,%d0
	bpls	1b
poly_mapsrc:
	moveal	KIT,%a0
	lea	%a0@(158),%a0
	lea	group_source,%a1
	moveq	#0,%d0
2:	moveb	%a0@,%d1
	cmpib	#POLY_ID,%d1
	bnew	3f
	tstl	%d0
	beqw	3f
	moveb	%a1@(-0x1,%d0:l),%d1
	braw	4f
3:	movel	%d0,%d1
4:	moveb	%d1,%a1@(0x0,%d0:l)
	lea	%a0@(162),%a0
	addql	#1,%d0
	moveq	#8,%d1
	cmpl	%d1,%d0
	bltw	2b
	rts

| ---- poly_assign (0x4002a608, the MACHINE menu's commit): the stock call (0x4000d9fc) returns here,
| then the pool map is rebuilt. poly_switch (0x400776e6, the audio ISR's pattern switch): the stock call,
| then the pool sources again. poly_route (0x400777a2, the ISR's note routing): a note for a track is
| played on a voice of its pool (voice allocation), the owner latched; a trigless lock changes the voice
| triggered last.
	.section .text.assign,"ax"	| the build: 0x40037a24
poly_assign:
	lea	%pc@(1f),%a0
	movel	%a0,%sp@
	jmp	0x4000d9fc
1:	jsr	poly_map
	jmp	0x4002a60e
poly_switch:
	jsr	0x400e0cb0
	movel	%d0,%sp@-
	jsr	poly_mapsrc
	movel	%sp@+,%d0
	rts
poly_route:
	movel	%a2@(8),%d2
	lea	group_source,%a0
	clrl	%d0
	moveb	%a0@(0x0,%d2:l),%d0
	movel	%a2@(36),%d1
	andil	#129,%d1
	subql	#1,%d1
	beqs	6f
	movel	%a2@(4),%d1
	subql	#1,%d1
	bnes	5f
	cmpl	%d0,%d2
	bnes	2f
	tstl	%a2@(40)
	bnes	3f
2:	movel	%d0,%d1
	lsll	#2,%d1
	addl	%d0,%d1
	lsll	#4,%d1
	addl	%d0,%d1
	lsll	#1,%d1
	moveal	KIT,%a4
	lea	%a4@(0x20,%d1:l),%a4
	movel	%a4,%a2@(40)
3:	jsr	va_alloc
	movel	%a2@(8),%d0
	moveb	%d0,%a0@(-0x8,%d2:l)
	nop
	nop
	nop
	nop
	nop
	nop
	nop
	nop
	nop
	nop
4:	movel	%d2,%d0
	rts
5:	jsr	poly_scan
	bras	4b
6:	lea	group_cursor,%a4
	clrl	%d1
	moveb	%a4@(0x0,%d0:l),%d1
	movel	%d1,%d2
	bras	4b

| ---- page aliasing: poly_rd_alias (0x400308da, a parameter read: for "this track" (-1) a POLY track's
| pool source is read instead, then 0x400191fe), poly_trk_alias (0x4002b68e, 0x4003ab12: the track a page
| works on, mapped to its pool source); poly_follow (0x400771e8, the live parameter push: a change on a
| pool source goes to every voice of its pool, then the stock routine).
	.section .text.alias1,"ax"	| the build: 0x400bed3a
poly_rd_alias:
	movel	%sp@(12),%d0
	addql	#1,%d0
	bnes	1f
	movel	%sp@(4),%sp@-
	jsr	0x40014d86
	movel	%d0,%sp@
	jsr	0x4001d24e
	addql	#4,%sp
	tstl	%d0
	blts	1f
	moveq	#7,%d1
	cmpl	%d0,%d1
	blts	1f
	lea	group_source,%a0
	moveb	%a0@(0x0,%d0:l),%d0
	movel	%d0,%sp@(12)
1:	jmp	0x400191fe
poly_trk_alias:
	movel	%sp@(4),%sp@-
	jsr	0x4001d24e
	addql	#4,%sp
	tstl	%d0
	blts	2f
	moveq	#7,%d1
	cmpl	%d0,%d1
	blts	2f
	lea	group_source,%a0
	moveb	%a0@(0x0,%d0:l),%d0
2:	rts
	rts
	rts
	rts
poly_follow:
	movel	%sp@(8),%d0
	moveq	#8,%d1
	cmpl	%d1,%d0
	bges	4f
	lea	group_source,%a0
	clrl	%d1
	moveb	%a0@(0x0,%d0:l),%d1
	cmpl	%d1,%d0
	bnes	4f
	movel	%d3,%sp@-
	movel	%d4,%sp@-
	movel	%d1,%d4
	moveq	#7,%d3
3:	cmpb	%a0@(0x0,%d3:l),%d4
	bnes	5f
	movel	%sp@(20),%sp@-
	movel	%d3,%sp@-
	movel	%sp@(20),%sp@-
	bsrs	4f
	lea	%sp@(12),%sp
	lea	group_source,%a0
5:	subql	#1,%d3
	bpls	3b
	movel	%sp@+,%d4
	movel	%sp@+,%d3
	rts
4:	movel	%d2,%sp@-
	moveq	#16,%d2
	movel	%sp@(8),%d1
	jmp	0x400771f0

| ---- poly_wr_alias (0x400309d0, with 0x400309e4 and 0x40030a2a): a parameter write's track, mapped to its
| pool source, or -1. rr_sel (0x400654a8): SLICE's knob picture, the robin for a negative SLICE value.
	.section .text.alias2,"ax"	| the build: 0x400bee02
poly_wr_alias:
	movel	%sp@(4),%sp@-
	jsr	0x4001d24e
	addql	#4,%sp
	tstl	%d0
	blts	1f
	moveq	#7,%d1
	cmpl	%d0,%d1
	blts	1f
	lea	group_source,%a0
	moveb	%a0@(0x0,%d0:l),%d0
	rts
1:	moveq	#-1,%d0
	rts
rr_sel:
	moveal	#0x421faa04,%a1
	tstl	%d0
	bnes	2f
	moveal	#rr_robin,%a1
2:	movel	%sp@(20),%d1
	andl	%d0,%d2
	jmp	0x400654ae

| ---- pool cursors on SRC page 2 (0x400bd5b0, 0x400bd9ee: the waveform view's two playhead draws): for a
| track in a pool, a playhead for every other voice of the pool that plays, through 0x400c1268.
	.section .text.cursors,"ax"	| the build: 0x40015558
cur_draw1:
	moveal	%a2,%a0
	lea	%a2@(49),%a1
	movel	%d2,%d0
	bras	1f
cur_draw2:
	lea	%a2@(-4),%a0
	lea	%a2@(-3),%a1
	movel	%d4,%d0
1:	moveal	%a0,%a3
	moveal	%a1,%a4
	movel	%d0,%d3
	clrl	%d4
	jsr	0x40138882
	addil	#48,%d0
	movel	%d0,%sp@-
	jsr	0x4001d24e
	addql	#4,%sp
	moveq	#7,%d1
	cmpl	%d0,%d1
	bcsw	5f
	moveal	%d0,%a5
	lea	group_source,%a2
	moveb	%a2@(0x0,%d0:l),%d2
	movel	%a3@(32),%d5
	movel	%d5,%d6
	addl	%a3@(40),%d6
	subql	#2,%d6
	addql	#1,%d5
	pea	-1:w
	movel	%d6,%sp@-
	movel	%d5,%sp@-
	clrl	%sp@-
	movel	%d3,%sp@-
	movel	%a3@(36),%d5
	movel	%a3@(28),%d6
	clrl	%d3
2:	moveb	%a2@(0x0,%d3:l),%d0
	cmpb	%d0,%d2
	bnew	3f
	cmpal	%d3,%a5
	beqw	3f
	movel	%d3,%sp@-
	jsr	0x40076258
	addql	#4,%sp
	tstl	%d0
	beqw	3f
	mulsl	%d5,%d0
	asrl	#8,%d0
	addl	%d6,%d0
	movel	%d0,%sp@(4)
	jsr	0x400c1268
	moveq	#1,%d4
3:	addql	#1,%d3
	moveq	#8,%d0
	cmpl	%d3,%d0
	bnew	2b
	lea	%sp@(20),%sp
5:	clrb	%d0
	moveb	%d0,%a3@(49)
	tstl	%d4
	beqw	6f
	moveq	#1,%d0
	moveb	%d0,%a4@
6:	rts

| ---- voice allocation (from poly_route): in %d0 the pool source, %a0 the pool map: the next free voice of
| the pool after the one used last, else that next voice (its note state cleared). mute_shift (0x400777ca)
| and mute_test (0x400777d6): the ISR's mute tests take the track a note came from.
	.section .text.alloc,"ax"	| the build: 0x401770a6
va_alloc:
	movel	%a1,%sp@-
	lea	group_cursor,%a4
	lea	VOICES,%a1
	clrl	%d1
	moveb	%a4@(0x0,%d0:l),%d1
	bsrs	9f
	movel	%d1,%d2
1:	tstl	%a1@(0x0,%d1:l:4)
	beqs	2f
	bsrs	9f
	cmpl	%d2,%d1
	bnes	1b
	clrl	%a1@(0x0,%d1:l:4)
2:	moveb	%d1,%a4@(0x0,%d0:l)
	movel	%d1,%d2
	movel	%d2,%d0
	moveal	%sp@+,%a1
	rts
9:	addql	#1,%d1
	cmpib	#8,%d1
	beqs	3f
	cmpb	%a0@(0x0,%d1:l),%d0
	beqs	4f
3:	movel	%d0,%d1
4:	rts
mute_shift:
	movel	%fp@(-80),%d1
	movel	%a2@(8),%d0
	asrl	%d0,%d1
	rts
mute_test:
	movel	%a2@(8),%d0
	btst	%d0,0x4199f47d
	rts

| ---- the robin icon (17 x 17, a long per column) and its Bitmap: vtable, width, height, 1, the plane, a
| stock mask, 0
	.section .rodata.robin,"a"	| the build: 0x40252b50
rr_robin_px:
	.long	0x00000000, 0x00080000, 0x03e80000, 0x047e0000, 0x483f0000, 0x783f0000, 0x10ff0000
	.long	0x11ff0000, 0x13fe0000, 0x53f00000, 0x7be00000, 0x1bc00000, 0x0f800000, 0x0e000000
	.long	0x1c000000, 0x18000000, 0x00000000
rr_robin:
	.long	0x401b7734, 17, 17, 1, rr_robin_px, 0x4024de68, 0

| ---- POLY's menu icon (11 x 7) and its Bitmap, with a stock mask: for the machine's descriptor
	.section .rodata.icon,"a"	| the build: 0x40252bb0
poly_icon_px:
	.long	0xfe000000, 0xfe000000, 0xe0000000, 0x00000000, 0xe0000000, 0xfe000000, 0xe0000000
	.long	0x00000000, 0xe0000000, 0xfe000000, 0xfe000000
poly_icon:
	.long	0x401b7734, 11, 7, 1, poly_icon_px, 0x4023e0a0, 0

| ---- the machine, for core's core_machines: id, name, short name, icon, params (the stock machine whose
| eight parameters it takes: SLICE's, whose SRC page the firmware gives any added machine), render (its
| own id: an empty voice window; a voice of a pool plays with its source's Sound and machine), and no page
| of its own (0 where core 3.0's tail would be: the pages show the source's)
	.section .rodata.machine,"a"
	.balign	4
	.globl	dtogpp_poly_machine
dtogpp_poly_machine:
	.long	POLY_ID, poly_name, poly_name, poly_icon, 3, POLY_ID, 0, 0
poly_name: .asciz "POLY"
	.balign	2
