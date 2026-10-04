| Chain Recording for DT OG++ on Digitakt OS 1.54: the recorder fills a sample chain one slot at a
| time. Encoder D (idle only) sets the slot count N: off, 4, 8, 16, 32, 64. Each arm records one slot
| of RLEN steps, started by the threshold (or at once with the REC key); after a slot the recorder goes
| back to idle with the buffer kept, and the next arm writes the next slot right after it. After slot N
| the stock stop runs: normalise once, trim, save. MAX switches chain mode off. The idle prompt and the
| ARMED line show the slot to record next, k/N.
|
| Assemble with chain_record.ld; the sections are placed at the hook sites and in the landing pads.
| With --defsym INERT=1 every pad only replays what its hook displaced (the first-code stage of a new
| hook), and the chain word is never written. With --defsym AUTOARM=1 the recorder re-arms itself after
| each slot but the last, instead of going back to idle.

	.set	RLEN,	0x4199f0fc	| RLEN steps, 0 = MAX
	.set	LEN,	0x4199f100	| where the recording stops: an absolute write position
	.set	POS,	0x4199f104	| the write position, in samples
	.set	STATE,	0x4199f114	| 0 idle, 1 armed, 2 recording, 3 stopped, 4 trim
	.set	CHAIN,	0x439d1038	| this build's chain word: MAGIC | N << 8 | k (slots done)
	.set	MAGIC,	0xc4a10000	| RAM above .bss is not cleared at boot
	.set	SLOTLEN, 0x40076616	| stock: the recording length in samples, from RLEN and tempo
	.set	STOP,	0x40076540	| stock: end of recording (state 3, the save message)
	.set	REDRAW,	0x400c9a3a	| stock: View::invalidate
	.set	ARM_TXT, 0x401d0696	| stock: the idle prompt string
	.set	ARMED_TXT, 0x401ddbff	| stock: the armed line's string
	.set	ENC_H,	0x400a7f46	| stock: encoder H's handling in SamplerView::vfunc_17
	.set	ENC_DONE, 0x400a80c8	| stock: vfunc_17's exit (restores d2-d3/a2-a3, returns 1)

| ---- hook sites: each is exactly the length of the stock code it replaces

	.section .hook_thr,"ax"		| 0x400767fc, threshold crossing: jsr SLOTLEN ; movel %d0,LEN
	jsr	len_pad
	nop
	nop

	.section .hook_stop,"ax"	| 0x4007687a, the block routine's stop: lea 16(sp),sp ; bra STOP
	jmp	stop_pad
	nop

	.section .hook_arm,"ax"		| 0x400768c2, ARM: clrl POS
	jsr	arm_pad

	.section .hook_rec_pos,"ax"	| 0x400768fa, REC: clrl POS
	jsr	arm_pad

	.section .hook_rec_len,"ax"	| 0x40076900, REC: jsr SLOTLEN ; movel %d0,LEN
	jsr	len_pad
	nop
	nop

	.section .hook_enc,"ax"		| 0x400a7f40, vfunc_17 after the encoder H test: tstb %d0 ; beqw ENC_DONE
	jmp	enc_pad

	.section .hook_mem,"ax"		| 0x400a8e7c, idle draw, the MEM line's length: jsr SLOTLEN
	jsr	mem_pad

	.section .hook_fmt,"ax"		| 0x400a8f48, idle draw, the prompt: pea ARM_TXT ; pea 2
	jsr	fmt_arm
	nop
	nop

	.section .hook_fmt_pop,"ax"	| 0x400a8f60, that draw's cleanup: lea 32(sp),sp
.ifdef INERT
	lea	%sp@(32),%sp
.else
	lea	%sp@(40),%sp		| two more arguments, pushed by fmt_arm
.endif

	.section .hook_armed,"ax"	| 0x400a9026, armed draw, its first line: pea ARMED_TXT ; pea 2
	jsr	fmt_armed
	nop
	nop

	.section .hook_armed_pop,"ax"	| 0x400a9044, that draw's cleanup: lea 28(sp),sp
.ifdef INERT
	lea	%sp@(28),%sp
.else
	lea	%sp@(36),%sp		| two more arguments, pushed by fmt_armed
.endif

| ---- the STL span's free tail, 0x40177104..0x40177194

	.section .pad_stl,"ax"
.ifndef INERT

| chain: the chain state for the arm and the prompt lines. Out: %d0 = N (0: chain mode off), %d1 = k
| when a chain is in progress, else 0. In progress = k > 0 and the write position still at the end of
| slot k, where stop_pad left it (POS == LEN > 0); a stop, an abort or a new recording moves it, and
| start-up clears both (they are in .bss, the chain word is not). Clobbers %a0.
chain:
	bsrs	chain_raw
	beqs	1f
	moveal	LEN,%a0
	cmpal	POS,%a0
	bnes	0f
	tstl	%a0
	bgts	1f
0:	moveq	#0,%d1
1:	rts

| chain_raw: %d0 = N, %d1 = k as stored, flags from k; both 0 when chain mode is off (no valid chain
| word, N = 0, or RLEN at MAX). chain_any: the same whatever RLEN is.
chain_raw:
	tstl	RLEN
	bles	2f
chain_any:
	movel	CHAIN,%d1
	movel	%d1,%d0
	clrw	%d0
	cmpil	#MAGIC,%d0
	bnes	2f
	movel	%d1,%d0
	lsrl	#8,%d0
	mvzb	%d0,%d0
	mvzb	%d1,%d1
	rts
2:	moveq	#0,%d0
	moveq	#0,%d1
	rts

| stop_pad: the block routine stops when the write position reaches LEN or the 33 s cap. %d2-%d4/%a2
| are already restored; %d0, %d1, %a0, %a1 are free (audio ISR).
stop_pad:
	lea	%sp@(16),%sp
	bsrs	chain_raw
	tstl	%d0
	beqs	3f			| chain mode off: the stock stop
	lea	LEN,%a0
	moveal	%a0@,%a1		| the end of this slot
	cmpal	%a0@(4),%a1		| LEN - POS
	bgts	3f			| the cap cut the slot short: the stock stop
	movel	%a1,%a0@(4)		| POS = LEN: drop what the last block wrote past the slot's end
	addql	#1,%d1
	cmpl	%d0,%d1
	bges	3f			| that was slot N: the stock stop and save
	moveb	%d1,CHAIN+3		| one more slot done
.ifdef AUTOARM
	moveq	#1,%d0
	movel	%d0,%a0@(STATE-LEN)	| armed again, the buffer kept: the next hit records the next slot
.else
	clrl	%a0@(STATE-LEN)		| idle, the buffer kept: the user arms the next slot
.endif
	rts
3:	clrb	CHAIN+3			| k = 0: the next arm starts a new chain
	jmp	STOP

.else	| INERT: replay the displaced code
stop_pad:
	lea	%sp@(16),%sp
	jmp	STOP
.endif

| ---- the free tail of the POLY kit-load pad, 0x400bf1cc..0x400bf1e8

	.section .pad_len,"ax"
.ifndef INERT

| len_pad: LEN is an absolute position, so a slot ends one slot length after where it starts. Stock:
| POS is 0 here, so LEN is unchanged.
len_pad:
	jsr	SLOTLEN
	addl	POS,%d0
	movel	%d0,LEN
	rts

.else
len_pad:
	jsr	SLOTLEN
	movel	%d0,LEN
	rts
.endif

| ---- the free tail of the POLY machine-name pad, 0x400c1062..0x400c1080

	.section .pad_mem,"ax"
.ifndef INERT

| mem_pad: the idle draw's MEM line shows the recording length and a mark when it does not fit. In a
| chain: the whole chain, N slots.
mem_pad:
	jsr	SLOTLEN
	moveal	%d0,%a1
	jsr	chain_raw
	tstl	%d0
	bnes	1f
	moveq	#1,%d0
1:	movel	%a1,%d1
	mulsl	%d1,%d0
	rts

.else
mem_pad:
	jmp	SLOTLEN
.endif

| ---- the new pad, 0x40124a6c..0x40124b32

	.section .pad_new,"ax"
.ifndef INERT

| enc_pad: vfunc_17 tests encoder H last; when that test fails it lands here. %d2 = the event,
| %a2 = the view. Encoder D (event id 4) steps N while the recorder is idle.
enc_pad:
	tstb	%d0
	beqs	1f
	jmp	ENC_H			| encoder H: stock
1:	moveal	%d2,%a1
	moveq	#4,%d0
	cmpl	%a1@(12),%d0
	bnes	9f			| not encoder D: stock (nothing)
	tstl	STATE
	bnes	9f			| only while idle
	jsr	chain_any		| %d0 = N
	tstl	%a1@(16)		| the turn's direction
	bmis	3f
	beqs	9f
	addl	%d0,%d0			| up: double
	bnes	2f
	moveq	#4,%d0			| from off: 4
2:	moveq	#64,%d1
	cmpl	%d1,%d0
	bles	5f
	movel	%d1,%d0			| at most 64
	bras	5f
3:	lsrl	#1,%d0			| down: halve
	moveq	#3,%d1
	cmpl	%d1,%d0
	bhis	5f
	moveq	#0,%d0			| below 4: off
5:	lsll	#8,%d0			| N in bits 15..8, k = 0
	oril	#MAGIC,%d0
	movel	%d0,CHAIN
	movel	%a2,%sp@-
	jsr	REDRAW
	addql	#4,%sp
9:	jmp	ENC_DONE

| fmt_arm, fmt_armed: push a line's format and alignment for the draw that follows, plus two arguments
| below them: in a chain the line reads "YES: ARM k/N" (idle) or "ARMED k/N", with k the slot to
| record next, from 1. A stock string ignores the two arguments.
fmt_arm:
	pea	arm_fmt
	pea	ARM_TXT
	bras	fmt_common
fmt_armed:
	pea	armed_fmt
	pea	ARMED_TXT
fmt_common:				| stack: stock string, chain string, return address
	jsr	chain			| %d0 = N, %d1 = k
	moveal	%sp@+,%a0
	tstl	%d0
	beqs	1f
	moveal	%sp@,%a0
1:	addql	#4,%sp
	moveal	%sp@+,%a1		| the return address
	movel	%d0,%sp@-
	addql	#1,%d1
	movel	%d1,%sp@-
	movel	%a0,%sp@-
	pea	2:w
	jmp	%a1@

| arm_pad: ARM and REC clear the write position, with interrupts masked. In a chain it is kept, so the
| next slot is written after the last. ARM still needs %d0 (its result) and %d1 (the saved SR).
arm_pad:
	movel	%d0,%sp@-
	movel	%d1,%sp@-
	jsr	chain
	tstl	%d1
	bnes	4f
	clrl	POS			| stock: from the start
	clrb	CHAIN+3			| and no chain in progress
4:	movel	%sp@+,%d1
	movel	%sp@+,%d0
	rts

.else
enc_pad:
	tstb	%d0
	beqs	1f
	jmp	ENC_H
1:	jmp	ENC_DONE
fmt_arm:
	moveal	%sp@+,%a1
	pea	ARM_TXT
	pea	2:w
	jmp	%a1@
fmt_armed:
	moveal	%sp@+,%a1
	pea	ARMED_TXT
	pea	2:w
	jmp	%a1@
arm_pad:
	clrl	POS
	rts
.endif

| ---- .rodata padding

	.section .rodata_fmt,"a"
.ifndef INERT
arm_fmt:
	.asciz	"YES: ARM %d/%d"
armed_fmt:
	.asciz	"ARMED %d/%d"
.endif
