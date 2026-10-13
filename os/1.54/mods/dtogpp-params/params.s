| SPDX-License-Identifier: CC0-1.0
| dtogpp-params: four more sound parameters for Digitakt OS 1.54, saved with the sound and lockable per
| trig, for the mods that put a knob on them (dtogpp-portamento, dtogpp-filter-page2).
|
| A sound holds 53 value slots (the sound's +0x14 + 2 x slot, 8.8); the stock OS saves slots 0..45 to
| the stored record's words (+0x1c + 2 x index) through two tables, and slots 46..52 are free. This mod
| makes slots 46..49 persistent, at the record's spare words 46, 47, 50 and 51 (+0x78, +0x7a, +0x80,
| +0x82; 48 and 49 are the machine bytes):
|   the sound reader   FUN_4007a236: a jsr at 0x4007a2aa, in place of `lea 0x401ac58c,%a0`, between its
|                      clearing of the sound's slots and its value loop (slots 0..45)
|   the sound writer   FUN_4007a5a0: its slot -> stored index table (the lea at 0x4007a5f8) and its loop
|                      count (0x4007a614: 46 words -> 50)
|   the p-lock writers FUN_4007adb2, FUN_4007aefa: the same table (the leas at 0x4007adea, 0x4007af32)
|   the two lookups    FUN_40079738 (stored index -> slot) and FUN_40079772 (slot -> stored index): a jmp
|                      at each entry; kinds below 16 with an index or slot above 45 are answered here,
|                      everything else goes on in the stock function
|
| The reader takes the four words back as they are stored, then checks each slot that a mod describes in
| the table dtogpp_params (12 B an entry, sorted by the linker, ending in a zero entry):
|   +0 slot (46..49), +2 flags (bit 0: whole values only), +4 min, +6 max, +8 default (8.8), +10 0
| A value outside min..max, or with a fraction where only whole ones are allowed, gives the default. A
| slot no mod describes keeps its stored word unchecked, so a project keeps it through a save.

	.set	SNDTAB,	0x401ac58c	| stock: stored index -> slot, 46 entries (what the reader's lea loads)
	.set	FWD_ON,	0x40079740	| stock: FUN_40079738 after the 8 B its jmp replaces
	.set	INV_ON,	0x4007977a	| stock: FUN_40079772 after the 8 B its jmp replaces

	.section .run,"ax"

| ---- the sound reader's hook. In: %a2 = the sound, %a3 = the stored record. Out: %a0 = SNDTAB, as the
| replaced lea left it (the value loop and the code after it use it through %a1). Uses %d0..%d2 and %a1,
| which the code after sets before it reads them.
	.globl	dtogpp_params_load
dtogpp_params_load:
	movel	%a3@(0x78),%a2@(0x70)	| stored 46, 47 -> slots 46, 47
	movel	%a3@(0x80),%a2@(0x74)	| stored 50, 51 -> slots 48, 49
	lea	dtogpp_params,%a0
1:	mvzw	%a0@,%d0		| the entry's slot; 0 ends the table
	beqs	9f
	lea	%a2@(0x14,%d0:l:2),%a1	| the slot in the sound
	mvzw	%a1@,%d1
	btst	#0,%a0@(3)		| whole values only?
	beqs	2f
	tstb	%d1
	bnes	3f
2:	mvzw	%a0@(4),%d2		| min
	cmpl	%d2,%d1
	bcss	3f
	mvzw	%a0@(6),%d2		| max
	cmpl	%d1,%d2
	bccs	4f
3:	movew	%a0@(8),%a1@		| the default
4:	lea	%a0@(12),%a0
	bras	1b
9:	lea	SNDTAB,%a0
	rts

| ---- FUN_40079738(kind, index) -> slot: for a sound's kind (below 16), the stored indices 46 and 47 give
| slots 46 and 47, 50 and 51 give 48 and 49, 48 and 49 (the machine bytes) and above 51 none (0).
	.globl	dtogpp_params_fwd
dtogpp_params_fwd:
	moveq	#15,%d0
	cmpl	%sp@(4),%d0		| the kind
	bcss	8f			| 16 and above: the stock function
	movel	%sp@(8),%d0		| the stored index
	moveq	#45,%d1
	cmpl	%d0,%d1
	bccs	8f			| 0..45: the stock function
	moveq	#47,%d1
	cmpl	%d0,%d1
	bccs	9f			| 46, 47: themselves
	subql	#2,%d0
	moveq	#47,%d1
	cmpl	%d0,%d1
	bccs	7f			| 48, 49: none
	moveq	#49,%d1
	cmpl	%d0,%d1
	bccs	9f			| 50, 51: slots 48, 49
7:	clrl	%d0
9:	rts
8:	movel	%d2,%sp@-		| the stock function, from the instructions the jmp took
	moveq	#16,%d2
	movel	%sp@(8),%d1
	jmp	FWD_ON

| ---- FUN_40079772(kind, slot) -> stored index: for a sound's kind, slots 46 and 47 give 46 and 47, 48 and
| 49 give 50 and 51, above 49 none (0).
	.globl	dtogpp_params_inv
dtogpp_params_inv:
	moveq	#15,%d0
	cmpl	%sp@(4),%d0		| the kind
	bcss	8f
	movel	%sp@(8),%d0		| the slot
	moveq	#45,%d1
	cmpl	%d0,%d1
	bccs	8f
	moveq	#47,%d1
	cmpl	%d0,%d1
	bccs	9f			| 46, 47: themselves
	moveq	#49,%d1
	cmpl	%d0,%d1
	bcss	7f			| above 49: none
	addql	#2,%d0			| 48, 49: stored 50, 51
	rts
7:	clrl	%d0
9:	rts
8:	movel	%d2,%sp@-
	moveq	#16,%d2
	movel	%sp@(8),%d1
	jmp	INV_ON

| ---- slot -> stored index for a sound, slots 0..49: the stock table 0x401ac4d4's 46 entries (the build
| stores them as a copy from the user's own image), then 46, 47, 50, 51. The writer and the p-lock writers
| read it in place of the stock table; the writer also keeps its base in %a1 for two lookups after its loop.
	.balign	4
	.globl	dtogpp_params_slots
dtogpp_params_slots:
	.long	0, 1, 3, 5, 7, 9, 11, 13, 15, 2, 4, 6, 8, 10, 12, 14
	.long	16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31
	.long	32, 33, 34, 35, 36, 45, 37, 38, 39, 40, 41, 42, 43, 44
	.long	46, 47, 50, 51
