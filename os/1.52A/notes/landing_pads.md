# Landing pads

## What this is

All new code in this build lives in landing pads: stock functions that nothing in the firmware
calls, overwritten in place. This note gives the pad table (what occupies each pad and what is still
free), the evidence that each pad is dead, the vetting recipe for a new pad, the functions that look
unused but are live, and the separate budget for constant data in `.rodata`. Every extent is derived
from the stock `rts` (or tail `jmp`) in an objdump of the stock section, never by adding a size to an
address. Every used range was checked against `build/patch.json`.

## The pads

All pads are in section 3 and outside the protected ranges of [update_moat.md](update_moat.md).

| Pad | Extent | Occupied by | Free | Evidence of deadness |
|---|---|---|---|---|
| `FUN_400b23b0` | `0x400b23b0..0x400b246a`, 186 B (stock `rts` at `0x400b2468`) | [SLICE round robin](features/slice_round_robin.md) engine `0x400b23b0..0x400b240a` (90 B, including the POLY pool-source lookup and the slice latch) · SLICE value formatter with its strings `RRND\0` and `RRBN\0`, `0x400b2410..0x400b2468` (88 B) | 6 B `0x400b240a..0x400b2410` (stock bytes), plus the 2 B stock `rts` at `0x400b2468` | ✅ in use on the test unit |
| `FUN_400b21b0` | `0x400b21b0..0x400b2254`, 164 B (`rts` at `0x400b2252`) | POLY voice pool machine-picker navigation `0x400b21b0..0x400b2214` (100 B; [ledger](function_ledger.md#machine-menu-func--src), [patch listing](../docs/patch_listing.md#poly-engine)) · the note-off region scan `0x400b2214..0x400b2254` (64 B), which also carries the [owner latch](features/owner_latch.md)'s two same-length edits | 0 B | ✅ in use on the test unit |
| `FUN_400b20ac` | `0x400b20ac..0x400b20f8`, 76 B (`rts` at `0x400b20f6`) | POLY machine-name pointer table `0x400b20ac..0x400b20d4` (10 pointers, 40 B) · `"POLY\0"` `0x400b20d4..0x400b20d9` (5 B) | 30 B `0x400b20da..0x400b20f8` (31 B raw; the start is even for code) | ✅ in use on the test unit |
| `FUN_400b026e` | `0x400b026e..0x400b02ea`, 124 B (ends in a tail `jmp` at `0x400b02e4`, no `rts`) | POLY kit-load detour `0x400b026e..0x400b027e` (16 B, entered from `0x4007705a` in `FUN_40076fe6`) · the shared pool-map build routine `0x400b027e..0x400b02ce` (80 B), with two entries: `0x400b027e` (resets the cursors, then falls through) and `0x400b028e` (sources only, ends in `rts`) | 28 B `0x400b02ce..0x400b02ea` (stock bytes) | ✅ in use on the test unit |
| `FUN_4003771c` | `0x4003771c..0x400377d6`, 186 B (`rts` at `0x400377d4`) | POLY machine-assign hook `0x4003771c..0x40037734` (24 B, called from the commit at `0x40029f8c`) · POLY pattern-switch map refresh `0x40037734..0x40037746` (18 B, called from `0x400773e6`) · the POLY note routing pad of the audio ISR `0x40037746..0x400377ce` (136 B, called from `0x400774a2`), details below | 8 B `0x400377ce..0x400377d6` (stock bytes), plus a 2 B stock alignment filler `0x0000` at `0x400377d6` | ✅ in use on the test unit |
| `FUN_400afe46` | `0x400afe46..0x400afea8`, 98 B (`rts` at `0x400afea6`) | POLY parameter-read alias `0x400afe46..0x400afe80` (58 B; the tail `jmp` of `MachineParameterPageView::vfunc_41` at `0x40030d44` lands here) · POLY machine alias `0x400afe80..0x400afea2` (34 B, called from `0x4002b012` and `0x40039f16`) | 6 B `0x400afea2..0x400afea8` (`rts` fill) | ✅ fill test on the test unit |
| `FUN_400afea8` | `0x400afea8..0x400aff0e`, 102 B (`rts` at `0x400aff0c`) | POLY knob-follow loop plus the entry-trampoline stub for `FUN_40076ee8` (whose first 8 B are `jmp 0x400afea8 ; nop`), `0x400afea8..0x400afefe` (86 B) | 16 B `0x400afefe..0x400aff0e` (stock bytes) | ✅ in use on the test unit |
| `FUN_400aff0e` | `0x400aff0e..0x400aff76`, 104 B (`rts` at `0x400aff74`) | POLY parameter-write alias `0x400aff0e..0x400aff34` (38 B, called from `0x40030e5a`; see below) · SLICE robin selector `0x400aff34..0x400aff50` (28 B) · POLY picker-icon lookup `0x400aff50..0x400aff60` (16 B) · [MIDI Loopback](features/midi_loopback.md) dial-number pad `0x400aff60..0x400aff70` (16 B) | 6 B `0x400aff70..0x400aff76` (`rts` fill) | ✅ fill test on the test unit |
| span at `0x40015060` | `0x40015060..0x400151ec`, 396 B, eight adjacent leaves (`rts` at `0x400151ea`) | [pool cursors](features/pool_cursors.md) `0x40015060..0x4001511e` (190 B) · MIDI Loopback display pads `0x4001511e..0x40015186` (104 B) · MIDI Loopback private-lane arm `0x40015186..0x400151b8` (50 B) · MIDI Loopback record filter `0x400151b8..0x400151e2` (42 B) | 10 B `0x400151e2..0x400151ec` (`clrl %d0 ; rts` fill) | ✅ fill test on the test unit |
| span at `0x40014cb4` | `0x40014cb4..0x40014dd8`, 292 B, six adjacent leaves (`rts` at `0x40014dd6`) | MIDI Loopback channel hook `0x40014cb4..0x40014ce0` (44 B) · MIDI Loopback tap `0x40014ce0..0x40014dc8` (232 B) | 16 B `0x40014dc8..0x40014dd8` (`clrl %d0 ; rts` fill) | ✅ fill test on the test unit |
| the STL span | `0x4015cb8a..0x4015cc78`, 238 B, three adjacent leaves (`rts` at `0x4015cc76`) | [voice allocation](features/voice_allocation.md) `0x4015cb8a..0x4015cbd0` (70 B) · [mute by origin](features/mute_by_origin.md) detours `0x4015cbd0..0x4015cbe8` (24 B, entries `0x4015cbd0` and `0x4015cbdc`) | 144 B `0x4015cbe8..0x4015cc78` (`clrl %d0 ; rts` fill) | ✅ fill test on the test unit |

**Free code space: 270 B in ten blocks. The largest is 144 B contiguous, the STL span's tail.**

The free blocks, largest first: 144 B at `0x4015cbe8` · 30 B at `0x400b20da` · 28 B at `0x400b02ce` ·
16 B at `0x40014dc8` · 16 B at `0x400afefe` · 10 B at `0x400151e2` · 8 B at `0x400377ce` · 6 B at
`0x400b240a` · 6 B at `0x400afea2` · 6 B at `0x400aff70`. The two 2 B slivers (`0x400b2468` and
`0x400377d6`) cannot usefully be allocated.

**What the free bytes hold.** In five pads (`FUN_400b23b0`, `FUN_400b20ac`, `FUN_400b026e`,
`FUN_4003771c`, `FUN_400afea8`) the free bytes are still the stock bytes of the dead function. In the
other five they are the fill written for the fill test, which `build/patch.json` lists as the pad
fill: `clrl %d0 ; rts` in the three spans, a bare `rts` (`4e75`) in the tails of `FUN_400afe46` and
`FUN_400aff0e`. A build check on one of those tails must assert "still the fill", not "still stock".
The pad fill's runs in `build/patch.json` also hold three live `rts`, which equal the fill byte for
byte: the exit of the POLY machine alias at `0x400afea0` and the two exits of the parameter-write
alias at `0x400aff2e` and `0x400aff32`.

### Inside the note routing pad (`FUN_4003771c`)

- `0x40037790`: `jsr 0x4015cb8a` (6 B), the call into voice allocation.
- `0x40037796..0x4003779e`: the owner latch's write (8 B), `movel %a2@(8),%d0 ; moveb %d0,%a0@(-8,%d2:l)`.
- `0x4003779e..0x400377b2`: 20 B of `nop` filler.
- `0x400377b2`: the identity exit, a branch target shared by several paths. It must never move.

⚠️ The filler is reachable only by falling through from the owner latch's write, so anything placed
there must itself fall through into `0x400377b2`. It is not general free space and is not counted
above.

### The parameter-write alias stays

The 38 B at `0x400aff0e` make a POLY track's knob write land on its Source. ⚠️ For the parameter-set
lookup they look redundant, because the parameter-read alias at `0x400afe46` covers both directions:
`vfunc_21` (read) and `vfunc_22` (write) both reach the parameter-set resolver `FUN_40018cec` through
`MachineParameterPageView::vfunc_41` (`0x40030d22`), whose tail jump the read alias rewrites. For that
lookup the two mechanisms give the same answer:

- `vfunc_22` passes `vfunc_41` an explicit track 0–7 (the Source) for an audio track, or −1 for a MIDI
  track or master.
- The read alias passes any explicit track through untouched. It substitutes only on −1, and then
  only when the current track is an audio track.
- So an audio track resolves to its Source by either route, and MIDI and master keep −1 by either
  route: the same parameter set.

The pad's result also reaches `vfunc_22`'s track-level branch for `paramId == 10` (guarded at
`0x40030e6e`), which passes it to `FUN_4001f7a8` and `FUN_4001f822`, so on a POLY track that branch
acts on the Source. `vfunc_21`'s `paramId == 10` read calls `FUN_4001f7a8` with the unmapped current
track, not through `vfunc_41` (objdump of the patched section). Not traced: whether a POLY track's
pages reach that branch, so the redundancy is not shown for that parameter.

The write alias is three coupled edits:

1. The pad (38 B, called at `0x40030e5a`) returns the Source for tracks 0–7 and −1 for anything else.
   Its result goes to `%d2` at `0x40030e62`.
2. At `0x40030eb8` the stock `pea 0xffffffff` (4 B) becomes `movel %d2,%sp@- ; nop`, so `vfunc_41`
   receives the pad's result as its track argument.
3. The `paramId == 10` guard at `0x40030e6e` changes from a signed to an unsigned compare (`blts` →
   `blos`, `6d46` → `6546`). The guard reads "skip unless track < 8"; signed, a −1 would enter the
   branch, unsigned it skips. For every stock value (0..0x10) the two compares agree.

⛔ Never revert the guard edit (3) while the pad can still return −1: without it, −1 enters the
track-level branch.

## Why each pad is dead

**The three `0x400af` pads** (`FUN_400afe46`, `FUN_400afea8`, `FUN_400aff0e`) form one 304 B span,
`0x400afe46..0x400aff76`. All three are the same helper in three argument forms, "add to each of three
fields and clamp each to 0..15": one scalar applied to all three fields, three scalars, or a second
struct (`a1@`, `a1@(4)`, `a1@(8)`). That is an unused C++ `+=` operator-overload family, emitted from a
header and never called. Evidence: all three are pure leaves with no `jsr` or `jmp`, no peripheral
literals and no EMAC; a raw pointer scan of the whole 2,221,632 B section finds 0 words pointing into
the span (the positive controls find 4 and 6, see step 3 below); `DumpRefsInRange` and
`FindAddressLiterals` at the full extent both return the same 9 hits, the internal `bges` targets (3
per function). In the fill test the differing runs were 96 B and 102 B rather than 98 and 104, because
the last two bytes of each pad already are the stock `rts` at `0x400afea6` and `0x400aff74`. That is an
independent check of the objdump extents.

**The span at `0x40015060`** is eight array-element address calculators: each bounds-checks an index
and returns `base + idx*stride + offset`. They come in two parallel groups of four, with strides 160
(idx < 128), 2560 (idx < 128), 25088 (idx < 128) and 3072 (idx < 16) and offsets `+0x360204`,
`+0x310200`, `+0x200` and `+0x366a00`. The groups differ only in the failure convention (`clrb` or
`clrl %d0`) and one branch polarity: the const and non-const overloads of one accessor. The live twin
is `FUN_40014e4a` (322 B, 6 absolute `jsr` callers), a project-storage loader and validator that
computes the same record addresses inline (127 × 2560 B records at `+0x310200`, the 160 B array at
`+0x360200`, the 25088 B records at `+0x200`). The compiler inlined the accessor wherever it was used;
the out-of-line copies were emitted from a header and never called.
Members: `0x40015060` (44 B), `0x4001508c` (54), `0x400150c2` (46), `0x400150f0` (56), `0x40015128`
(40), `0x40015150` (54), `0x40015186` (46), `0x400151b4` (56).
Evidence: zero callees; a raw scan at every offset finds 0 (controls 4 and 6); `DumpRefsInRange` 21 =
`FindAddressLiterals` 21 distinct targets / 26 hits, every one an internal jump; a scan of the full
objdump listing (about 740 k instructions) for any instruction outside the span naming an address
inside it finds 0; the function before ends in `rts` at `0x4001505e`, and `0x400151ec` opens a fresh
function (a track classifier). In the fill test, sections 2, 4 and 5 were byte-identical to stock and
section 3 differed only inside the span.

**The span at `0x40014cb4`** is six more members of the same family, with strides 3072 (idx < 16) at
`+0x366a00`, 2560 (idx < 128) at `+0x310200` and 25088 (idx < 128) at `+0x200`, again as const and
non-const pairs. All six already return `clrl %d0` for an out-of-range index, so the fill is exactly
their own failure path.
Members: `0x40014cb4` (52 B), `0x40014ce8` (50), `0x40014d1a` (44), `0x40014d46` (52), `0x40014d7a`
(50), `0x40014dac` (44).
Evidence: raw scan 0; `DumpRefsInRange` 12 = `FindAddressLiterals` 12 distinct / 18 hits, all
internal; the listing scan finds 0 over 740,780 decoded instructions; 17 of the image's switch tables
are based within ±32 KB of the span and no entry resolves into it; a raw-byte scan for PC-relative
encodings at every even offset (independent of the disassembly) finds 0 from outside and reproduces
the 18-hit / 12-target internal figure; `rts` at `0x40014cb2` before the span, its own `rts` at
`0x40014dd6`, and `0x40014dd8` opens a fresh function.

**The STL span** is three unused `std::list` template instantiations: splice at `0x4015cb8a` (144 B),
splice-range at `0x4015cc1a` (64 B) and reverse at `0x4015cc5a` (30 B). Each has a proper `linkw`
prologue, ends in `rts`, and contains no `jsr`, `bsr` or `jmp`. An uncalled template instantiation needs
no live twin to explain it: emitting it is the compiler's default.
Evidence: word scan 0 (controls 4 and 6); an operand and PC-relative scan of the full listing (744,650
lines, hex and decimal immediates) finds 0, with the setter control at 5 and the `0x40015060` span as a
negative control; 90 switch tables in the image, 1 within ±32 KB, 0 entries inside; `rts` before and a
fresh `linkw` after. These scans use objdump rather than the Ghidra query pair, with equivalent
coverage and controls in both directions.
⚠️ These functions return `void` and relink lists through pointer arguments, so a live member would
mean a splice that silently does not happen, not a fault. The fill test therefore exercised list-shaped
UI: the sample, project and pattern lists, the machine picker, and a saved-project reload, along with
the POLY pages, cursors, mute and MIDI Loopback. ✅ No observable difference on the test unit. ⚠️ The sound
pool path was not exercised.

**`FUN_400b23b0`, `FUN_400b21b0`, `FUN_400b20ac`, `FUN_400b026e`, `FUN_4003771c`** came from the
dead-leaf candidate list and run feature code on the test unit; that is what they are trusted on, as
the vetting recipe below is not recorded for them in full. What has been checked: a raw scan of the
stock section, every byte offset read as a big-endian word, finds 0 values pointing into any of the
five extents (controls 4 and 6); objdump shows no I/O literals and no EMAC in any of them; and
`FindDeadFunctions` (below) lists `FUN_400b23b0` as a dead leaf. Two of them are not leaves in the
objdump listing: `FUN_400b026e` and `FUN_4003771c` call other functions through address registers
(`lea` of an absolute address, then `jsr %aN@`), and `FUN_400b026e` also makes one virtual call and
ends in a tail `jmp`.

## Where candidates come from

`scripts/ghidra/FindDeadFunctions.java`, run through `scripts/ghidra_query.sh` on the seeded project
(below), writes its candidate list to `work/ghidra/out/dt_1.52A_seed/query/FindDeadFunctions.txt`. A
function is a candidate only if three independent evidence sources all find nothing pointing at it; no
single one is enough:

| Evidence | Catches | Misses |
|---|---|---|
| Ghidra references (any type, including `DATA`) | PC-relative address-taking (`lea (d16,PC),aN`), which encodes a displacement and is invisible to any byte scan | some handler installs |
| Pointer words (4-byte-aligned image scan) | vtable slots, jump and handler tables | PC-relative; unaligned words |
| Operand literals (`FindAddressLiterals`) | `move.l #FUN_x,dN`-style installs | PC-relative |

⛔ Two traps: filtering the references to calls and jumps drops the `DATA` references, which are
exactly the "address taken and stored in a callback slot" case; the sequencer dispatcher
`FUN_4007011c` is then flagged dead, although its address never appears in the raw image because the
reference is PC-relative. Conversely the audio ISR `FUN_40077120` has no Ghidra reference but is a
pointer word. Use the union.

The script validates itself against ten functions known to be live (`0x40077120`, `0x4007011c`,
`0x4006f1be`, `0x40074e84`, `0x4006753e`, `0x400d0578`, `0x4006f546`, `0x40076b5a`, `0x40074af2`,
`0x4006f882`) and passes 10 of 10. On the fully seeded project (`GHIDRA_PROJECT=dt_1.52A_seed`) it
reports 980 dead candidates, 81,858 B, of which 204 functions, 10,186 B, are leaves (no callees).

⛔ **A dead function on this list is a candidate, never a budget.** A function reached through a fully
computed address (base plus a runtime offset, no literal anywhere) is invisible to all three sources,
and this firmware does that often. The large "dead" functions are especially unsafe: of those of
256 B or more, 42 have callees (for example `0x400f1378`, 6,798 B, calls 5 functions; `0x40017dfc`,
1,534 B, calls 24, including vtable methods and the allocator) and only 1 is a leaf. Only a pad that
has passed a fill test or run feature code on a device is trusted.

## Vetting a new pad

1. **Candidate.** A row of that list with `leafPad=PAD`, `ghRefs=0`, `ptrWord=-`, `opLit=-`.
2. **objdump it.** Reject it if it shows peripheral literals (`0xec…`, `0xfc…`) or EMAC/MAC
   instructions (`msac`, `mac.l`): that is a live render lane. Derive the extent from its `rts`.
3. **Raw pointer scan** of the whole section: every 4-byte big-endian word that points into the range
   (absolute pointer, `jsr` / `jmp` / `lea` operand, vtable entry). Expect 0. First prove the scanner
   is not blind with positive controls: the setter `FUN_40021fce` must show 4 hits and the voice build
   `FUN_40076fe6` 6.
4. **Listing scan for PC-relative references.** Scan the full `objdump -D` listing for any instruction
   outside the range whose operand resolves inside it (`bsr`, `jsr %pc@(…)`, `lea %pc@(…)`, `bra`).
   Expect 0. objdump prints PC-relative operands as absolute addresses, so one token scan of the
   operand field covers branches, absolute calls and PC-relative forms; immediates print in decimal,
   so scan those too. Here the setter shows 5, not 4: the extra one is `lea %pc@(0x40021fce),%a0` at
   `0x40022ad8`, which is PC-relative and therefore invisible to step 3. Both counts are right; they
   measure different reference classes. Also run a negative control: a region already confirmed dead
   must come back 0.
5. **Switch tables.** The image dispatches with `movew %pc@(base,%dN:l),%dN` plus
   `jmp %pc@(base,%dN:l)`, whose tables hold signed 16-bit offsets from `base`. No pointer to the target
   exists anywhere, so steps 3 and 4 are both blind to it. Enumerate the tables based within ±32 KB of
   the candidate and walk their entries.
6. **Live-twin search.** Search for the candidate's distinctive immediates (strides, offsets)
   elsewhere. A referenced inline twin turns "no references found" into "the compiler inlined it".
7. **Fall-through.** The function before must end in `rts`, `jmp` or `bra`, and the byte after the
   range must open a fresh function.
8. **Merge.** When no single candidate is big enough, merge exactly adjacent candidates into a span and
   vet the span as one.
9. **Fill test on the device.** Overwrite the candidate with `clrl %d0 ; rts` (`4280 4e75`) at every
   member entry, change nothing else, and check that sections 2, 4 and 5 still equal stock. A member
   whose length is 2 mod 4 gets one trailing `4e75`, so the next entry still starts on `4280` and every
   2-byte word is a complete harmless instruction. Pass criterion: no change at all.
   Why not a bare `rts`: it returns whatever the caller left in `d0`. If that is a stale but valid
   pointer, a live function makes its caller read the wrong record silently, and the test reports
   "dead" for a live function. NULL is deterministic: a caller that checks it degrades visibly, one that
   does not faults visibly, because nothing is mapped at 0 (the boot NOR is behind DSPI, SRAM is at
   `0x8000xxxx`, DDR at `0x4xxxxxxx`). For a candidate that returns `void` the fill is a weaker
   instrument; exercise the data structures the code would manipulate.
10. **Moat check.** The pad must lie outside the protected ranges, and the code put into it must never
    call a flash erase or write ([update_moat.md](update_moat.md)).
11. **First code.** Before any feature logic, the first code through a new hook is an inert
    pass-through that only replays what the hook displaced.

Two heuristics that have held so far: when a candidate with no references has sibling overloads that
also have none, the whole family is dead; and unused template instantiations (leaves with a
`linkw` prologue, pure pointer bodies and no callees, in the high `0x401xxxxx` C++ region) are the
cleanest candidates. Avoid `0x4007xxxx` (audio MAC code reached through dispatch tables), `0x40001xxx`
(I/O) and the `0x40210xxx` island.

## False positives: candidates that are live or unsafe

- `FUN_40001fb2` (382 B): live FlexBus encoder/display I/O (`0xec070004`, `0xec07000c`), reached through
  a vector or dispatch table.
- `FUN_40210e4a` (200 B) and the rest of the `0x40210xxx` cluster: the island at foreign addresses,
  not normal section-3 code.
- `FUN_40071716` (146 B) and `FUN_400717a8` (136 B): live EMAC render lanes (`msac.l`, `mac.l`). In the
  `0x4007xxxx` audio region, no references means reached by dispatch, not dead.
- `FUN_40020eea` (132 B) and `FUN_40023ae4` (116 B): object methods that call `obj->vfunc(+0x28)`, one
  of which installs a callback pointer. No references, but shaped like dispatch targets; not verified
  dead, so avoided.
- The reverse case, `FUN_400afea8` (102 B): it looks live (the add-and-clamp helper) but is dead, and it
  is in use as a pad.

## Rules for code in a pad

- **Position independence.** Pad code uses PC-relative branches inside the pad; calls, data and
  string references use absolute-long addresses, including a few to the pad's own entries, strings and
  tables (`jsr 0x400b027e` at `0x400b0272`, the `RRND` / `RRBN` pointers at `0x400b243a` and
  `0x400b2442`, the POLY name table's pointers to `0x400b20d4`). So moving a pad is a byte copy plus
  repointing every absolute reference into it, from outside and from inside, with no reassembly.
  Objdump it at the new address and confirm that every branch still lands inside the pad and that no
  operand or data word still points at the old place.
- **Entry trampolines.** Some hooks replace the first instructions of a stock function with
  `jmp pad ; nop`. At `FUN_40076ee8` that covers 8 B, three whole instructions; a stub in the pad
  re-executes those 8 B verbatim and jumps back to `FUN_40076ee8 + 8`. The displaced bytes must end on
  an instruction boundary, must not be PC-relative, and nothing else may enter the function in the
  middle of them.
- **Two arrays from one pointer.** When two parallel per-voice arrays lie within ±32 KB, one computed
  pointer reaches both: `lea %a1@(0,%d1:l*4),%a4` gives `&priority[v]` (base `0x4395ddf4`), and
  `movel %a4@(300),%d2` reads `held[v]`, which is `0x12c` further on. The note-off region scan fits in
  64 B this way. The same idea places this build's RAM arrays next to each other
  ([memory_map.md](memory_map.md)).

## `.rodata`: the constant-data budget

This is a separate budget from the code pads; do not add the two figures. Code pads are overwritten
dead functions. This is unreferenced constant-data space in the same stable region
(`0x40000400..0x40214000`, never zeroed at boot). Bitmap planes, tables and strings go here; executable
code does not.

**`0x40213b50..0x40214000`, 1200 B.** ✅ Confirmed on the test unit: 96 B written there with nothing
pointing at them showed no change of any kind, and the robin icon draws from that space.

| Address | Size | Contents |
|---|---|---|
| `0x40213b50` | 68 B | the robin's colour plane |
| `0x40213b94` | 28 B | the robin's `Bitmap` struct |
| `0x40213bb0` | 44 B | the POLY keyboard icon's colour plane |
| `0x40213bdc` | 28 B | the POLY icon's `Bitmap` struct |
| `0x40213bf8` | 8 B | the machine-picker bitmap selector table |
| `0x40213c00` | 1024 B | free |

**Why the range is safe.** It follows the C++ static-initialiser table (`.init_array`) at `0x40213980`:
116 entries, of which the parameter-record builder `FUN_4013b6a2` is entry 53. Its walker at
`0x40068d76` is bounded by a count: it loads the count (116) from `0x4021397c`, walks the table from
`0x40213980`, halts the CPU on a NULL entry, and calls each entry. The last entry is at `0x40213b4c`,
so the loop stops exactly where the free range starts. There is one reference each to the count word
and the table base, so there is no second walker. The range is the linker's padding from the end of
`.data` to the 16 KB-aligned `.bss` boundary. An overrun would halt the CPU loudly rather than corrupt
anything, and it cannot happen: the count is a link-time constant.

**How it was vetted,** the method for the next `.rodata` survey:

1. Uniform `0x00`, 1200 B, inside the image and below the `.bss` boundary `0x40214000`, so not zeroed at
   boot and not part of the two SRAM-init images (those start at `0x40214000`).
2. A whole-image word scan, every byte offset read as a big-endian 32-bit value: 0 values land in the
   range. This superset check catches pointers held in data tables (invisible to `FindAddressLiterals`)
   and `pea` / `lea #imm` operands (invisible to reference queries). Positive control: the same scan
   finds NOTE's colour-plane pointer at `0x400f5e6a`, its mask pointer at `0x400f5e64`, and 3
   references to the NOTE `Bitmap` object.
3. `FindAddressLiterals 0x40213b00 0x40214000`: 0 hits.
4. No near-miss pointers in the 64 B below the range.
5. The `.init_array` bound above.

**Look-alikes that are occupied.** Three other uniform ranges sit next to the UI bitmaps and look
exactly like free space:

| Range | Size | Fill | Verdict |
|---|---|---|---|
| `0x401d35c0..0x401d37c0` | 512 B | `ff` | ⛔ a pointer at its base, from `0x400d59f4`; also near the flash driver |
| `0x4020fe16..0x4020ffa2` | 396 B | `00` | ⛔ two pointers land inside it (`0x4020ff10`, `0x4020ff15`) |
| `0x4020ffae..0x402100e8` | 314 B | `00` | ⛔ 44 pointers to `0x40210000`: a shared blank resource |

⛔ `FindDeadSpace 0x40000400 0x40214000 68` reports 213 ranges, 95,608 B. Only 4 of them have a uniform
fill, and 3 of those 4 are occupied. Treat its output as a candidate list only.

**Bitmaps need no constructor call.** The blit `FUN_400b3b80` has no callees and never reads `+0x00`,
so a `Bitmap` can be static constant data; the field values are copied from what the real constructor
`0x400b1f4e` would write. A constructor call could not be added anyway: inserting one into the 67.9 KB
initialiser `FUN_400f5c2c` would shift everything after it. The mask plane can be shared: the UI's
17 × 17 masks are identical all-ones blocks (the pattern occurs 105 times in the image), so a new
17 × 17 tile can point its mask at NOTE's mask at `0x4020f6f4` and pay only for its 68 B colour plane.
The formats are in [visual_assets.md](visual_assets.md).

## Related notes

- [update_moat.md](update_moat.md): the ranges no pad may touch.
- [memory_map.md](memory_map.md): the RAM this build uses and the routes ruled out for new code.
- [visual_assets.md](visual_assets.md): the icons stored in `.rodata`.
- [analysis_method.md](analysis_method.md): the disassembly the scans run on.
- [docs/patch_listing.md](../docs/patch_listing.md): every patched byte run, by feature.
- [function_ledger.md](function_ledger.md): the per-function ledger. Not every function named here has
  a row of its own.
