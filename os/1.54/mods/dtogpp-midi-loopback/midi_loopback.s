| SPDX-License-Identifier: CC0-1.0
| dtogpp-midi-loopback: Virtual MIDI Loopback for Digitakt OS 1.54. CHAN on a MIDI track's SRC page gains
| eight values below 1, TRK8..TRK1 (stored -1..-8). A MIDI track set to TRKn plays audio track n with no
| cable: its notes reach audio track n as they would through a cable from MIDI OUT to MIDI IN, on the
| channel audio track n listens to; they also go out of MIDI OUT and USB on that channel. While CHAN is
| on a TRK value it reads TRK with the track number, and the popup reads "Track".
|
| The code is the build's (os/1.54/docs/patch_listing.md, midi_loopback), written out as source: each
| .text.* section is one of the build's blocks, in its order, so check_against_build.py can place it
| where the build has it and compare the bytes; the SDK puts them into .run. One difference: the two
| label routines end in a jump to the stock label lookups (0x4000fe8a, 0x4000feac), as the build did
| before the CFO oscillator pointed those jumps at its own names.
|
| The sites (mod.json), and what each routine does, read from the code:
|   0x400e0a00  lb_chan   in place of `mvsb %a0@(0x46,%d0:l),%d0 ; bras`: a track's CHAN byte; -8..-1 give
|                         the channel of audio track 1..8 (the long at 0x4197c700 + 4 x track), or -1 when
|                         that is above 15
|   0x400e1002  lb_out    in place of `mvzb 0x421b6620,%d1`, for a call whose first argument is 3: a
|                         message whose first byte is 0x8n or 0x9n, while the count at 0x421a9d40 is 64 or
|                         less, on a channel that one of the eight MIDI tracks reaches through a TRK value:
|                         its three bytes go into the next ring slot, and a 3 with the slot's address is
|                         posted to the queue at 0x421a9d3c; then the replaced instruction
|   0x40063222  lb_disp   in place of `movea.l %d0,%a0 ; pea %a0@(1)`: pushes the value + 1, a negative
|                         value + 9
|   0x40065896  lb_range  in place of `movel #256,%d0`: 256, or 2304 when its third argument is negative
|   0x40030daa  lb_short  the call of the short label lookup: for id 140 (CHAN) with a negative value the
|                         string at 0x401d0d44, else the lookup
|   0x40032d36  lb_long   the call of the long label lookup: for id 140 when the value it gets through
|                         the object's +84 is negative, the string at 0x401cca3f, else the lookup
|   0x400d49e0  lb_pop    in place of `pea 0x421a9d3c`: takes the queue's next message through %a4 and
|                         calls the handler the first byte's high nibble picks from %a2's table with
|                         that byte's address, the message's first long and 32; continues at 0x400d48e6
|   0x400d4dec  lb_msg    in place of `movel %d2,%sp@- ; movel 0x4219cc1c,%d2`: returns at once for a
|                         message whose first byte is 12 and whose long at +12 is 32, else goes on as stock
|   0x401abd14            CHAN's minimum: -8.0 (data)

	.set	LAB_SHORT, 0x4000fe8a	| stock: a parameter's short label
	.set	LAB_LONG, 0x4000feac	| stock: a parameter's long label
	.set	MIDIQ,	0x421a9d3c	| stock: the MIDI input's queue

	.section .bss
	.balign	4
lb_ring: .skip	128		| 32 slots x 4 B: the queued messages
lb_idx:	.skip	1		| the ring index, masked to 0..31

	.section .text.a,"ax"	| the build: 0x400151ac

lb_chan:
	mvsb	%a0@(0x46,%d0:l),%d0
	tstl	%d0
	bpls	1f
	addql	#8,%d0
	cmpil	#7,%d0
	bhis	2f
	lea	0x4197c700,%a0
	movel	%a0@(0,%d0:l:4),%d0
	cmpil	#15,%d0
	blss	1f
2:	moveq	#-1,%d0
1:	jmp	0x400e0a08

lb_out:
	movel	%sp@(4),%d0
	subql	#3,%d0
	bnew	9f
	moveal	%sp@(8),%a0
	mvzb	%a0@,%d0
	movel	%d0,%d1
	lsrl	#4,%d1
	subql	#8,%d1
	cmpil	#1,%d1
	bhiw	9f
	movel	0x421a9d40,%d1
	cmpil	#64,%d1
	bhiw	9f
	moveq	#15,%d1
	andl	%d1,%d0
	lea	%sp@(-12),%sp
	moveml	%d2-%d3/%a2,%sp@
	moveq	#7,%d1
3:	lea	0x421c9564,%a2
	movel	%a2@(0,%d1:l:4),%d2
	btst	#0,%d2
	beqs	4f
	moveq	#106,%d3
	movel	%d1,%d2
	mulsl	%d3,%d2
	lea	0x421c91aa,%a2
	mvsb	%a2@(0,%d2:l),%d2
	tstl	%d2
	bpls	4f
	addql	#8,%d2
	moveq	#7,%d3
	cmpl	%d3,%d2
	bhis	4f
	lea	0x4197c700,%a2
	movel	%a2@(0,%d2:l:4),%d2
	cmpl	%d0,%d2
	beqs	5f
4:	subql	#1,%d1
	bpls	3b
	bras	8f
5:	lea	lb_idx,%a2
	mvzb	%a2@,%d2
	addql	#1,%d2
	moveq	#31,%d3
	andl	%d3,%d2
	moveb	%d2,%a2@
	lsll	#2,%d2
	lea	lb_ring,%a2
	addal	%d2,%a2
	movel	%a2,%d3
	moveal	%sp@(20),%a0
	moveb	%a0@+,%a2@+
	moveb	%a0@+,%a2@+
	moveb	%a0@+,%a2@+
	jsr	0x400d2cf8
	moveal	%d0,%a1
	moveq	#3,%d2
	movel	%d2,%a1@
	movel	%d3,%a1@(4)
	movel	%a1,%sp@-
	pea	MIDIQ
	jsr	0x40001b7a
	addql	#8,%sp
	pea	0x421a9d34
	jsr	0x40001840
	addql	#4,%sp
8:	moveml	%sp@,%d2-%d3/%a2
	lea	%sp@(12),%sp
9:	mvzb	0x421b6620,%d1
	jmp	0x400e1008

	.section .text.b,"ax"	| the build: 0x40015616

lb_range:
	movel	%sp@(12),%d0
	bmis	1f
	movel	#256,%d0
	rts
1:	movel	#2304,%d0
	rts

lb_short:
	movel	%sp@(8),%d1
	cmpil	#140,%d1
	bnes	1f
	tstl	%sp@(100)
	bpls	1f
	movel	#0x401d0d44,%d0
	rts
1:	jmp	LAB_SHORT

lb_long:
	movel	%sp@(8),%d1
	cmpil	#140,%d1
	bnes	1f
	pea	-1:w
	movel	%d2,%sp@-
	movel	%a2,%sp@-
	moveal	%a2@,%a0
	moveal	%a0@(84),%a0
	jsr	%a0@
	lea	%sp@(12),%sp
	tstl	%d0
	bpls	1f
	movel	#0x401cca3f,%d0
	rts
1:	jmp	LAB_LONG

lb_pop:
	pea	MIDIQ
	jsr	%a4@
	moveal	%d0,%a1
	moveal	%a1@(4),%a0
	movel	%a0,%sp@(48)
	moveb	%a0@,%d1
	addql	#4,%sp
	mvzb	%d1,%d0
	lsrl	#4,%d0
	pea	0x20:w
	movel	%a1@,%sp@-
	movel	%a0,%sp@-
	moveal	%a2@(0,%d0:l:4),%a0
	jsr	%a0@
	lea	%sp@(12),%sp
	jmp	0x400d48e6

lb_msg:
	moveal	%sp@(4),%a0
	mvzb	%a0@,%d0
	cmpil	#12,%d0
	bnes	1f
	movel	%a0@(12),%d0
	cmpil	#32,%d0
	beqs	2f
1:	movel	%d2,%sp@-
	movel	0x4219cc1c,%d2
	jmp	0x400d4df4
2:	rts

	.section .text.c,"ax"	| the build: 0x400bee54

lb_disp:
	tstl	%d0
	bges	1f
	addql	#8,%d0
1:	addql	#1,%d0
	movel	%d0,%sp@-
	jmp	0x40063228
