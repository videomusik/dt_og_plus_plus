# Landing pads: method

## What a landing pad is

A landing pad is a stock function that nothing in the firmware calls, overwritten in place. All new
code in a DT OG++ build lives in landing pads. This note gives the method: where candidates come from,
the vetting recipe for a new pad, the heuristics that have held so far, the rules for code in a pad,
and how to survey `.rodata` for the separate budget for constant data. Every extent is derived from the
stock `rts` (or tail `jmp`) in an objdump of the stock section, never by adding a size to an address,
and every used range is checked against the OS folder's `os/<os>/build/patch.json`.

Each OS folder lists its own pads in `os/<os>/notes/landing_pads.md`: what occupies each pad and what
is still free, the evidence that each pad is dead, the functions that look unused but are live, and its
`.rodata` budget (OS 1.52A: [landing_pads.md](../os/1.52A/notes/landing_pads.md)). A pad, a candidate
list or a control count from one OS version says nothing about another: vet every pad in the stock file
of the OS version it is used in.

## Where candidates come from

`FindDeadFunctions.java`, kept in the OS folder's `os/<os>/scripts/ghidra/` (its list of functions
known to be live belongs to that OS version), run through `scripts/ghidra_query.sh` on that OS's seeded
project, writes its candidate list to `work/ghidra/out/dt_<os>_seed/query/FindDeadFunctions.txt`:

```
GHIDRA_PROJECT=dt_<os>_seed ./scripts/ghidra_query.sh <os> main FindDeadFunctions
```

A function is a candidate only if three independent evidence sources all find nothing pointing at it;
no single one is enough:

| Evidence | Catches | Misses |
|---|---|---|
| Ghidra references (any type, including `DATA`) | PC-relative address-taking (`lea (d16,PC),aN`), which encodes a displacement and is invisible to any byte scan | some handler installs |
| Pointer words (4-byte-aligned image scan) | vtable slots, jump and handler tables | PC-relative; unaligned words |
| Operand literals (`FindAddressLiterals`) | `move.l #FUN_x,dN`-style installs | PC-relative |

⛔ Two traps: filtering the references to calls and jumps drops the `DATA` references, which are
exactly the "address taken and stored in a callback slot" case; a function whose address is taken
PC-relatively is then flagged dead, although its address never appears in the raw image. Conversely a
function with no Ghidra reference at all can still be a pointer word. Use the union. Both traps occur
in OS 1.52A: [landing_pads.md](../os/1.52A/notes/landing_pads.md#the-candidate-list-on-this-image).

The list ends with a validation line for the functions known to be live that the OS folder's copy of
the script lists (its `KNOWN_LIVE` table, filled from that version's own analysis). Use the list only
when that line reads `validation PASSED`.

⛔ **A dead function on this list is a candidate, never a budget.** A function reached through a fully
computed address (base plus a runtime offset, no literal anywhere) is invisible to all three sources,
and the firmware does that often (seen throughout OS 1.52A). The large "dead" functions are especially
unsafe: in OS 1.52A nearly all of those of 256 B or more have callees, and only one is a leaf
(OS 1.52A: [landing_pads.md](../os/1.52A/notes/landing_pads.md#the-candidate-list-on-this-image)).
Only a pad that has passed a fill test or run feature code on a device is trusted.

## Vetting a new pad

1. **Candidate.** A row of that list with `leafPad=PAD`, `ghRefs=0`, `ptrWord=-`, `opLit=-`.
   One exception admits a row that is not a leaf (`ghRefs=0`, `ptrWord=-`, `opLit=-`, callees > 0).
   All four conditions must hold:
   - it is a function of a library linked whole into the image, identified by its own constants
     (for example a library's documented limits and its state struct's offsets);
   - its callees are that library's own routines or the C runtime's;
   - nothing in the image takes its address (steps 3–5 find nothing);
   - it passes every later step, the fill test included.

   The library explanation is what makes "unreferenced" plausible for code with callees: an
   object file linked whole brings in API functions the firmware never calls. The OS folder's
   `landing_pads.md` names the library and the constants.
2. **objdump it.** Reject it if it shows peripheral literals (`0xec…`, `0xfc…`) or EMAC/MAC
   instructions (`msac`, `mac.l`): that is a live render lane. Derive the extent from its `rts`.
3. **Raw pointer scan** of the whole section: every 4-byte big-endian word that points into the range
   (absolute pointer, `jsr` / `jmp` / `lea` operand, vtable entry). Expect 0. First prove the scanner
   is not blind with the OS folder's positive controls
   (OS 1.52A: [landing_pads.md](../os/1.52A/notes/landing_pads.md#vetting-controls-on-this-image)):
   functions known to be live must show their known number of hits.
4. **Listing scan for PC-relative references.** Scan the full `objdump -D` listing for any instruction
   outside the range whose operand resolves inside it (`bsr`, `jsr %pc@(…)`, `lea %pc@(…)`, `bra`).
   Expect 0. objdump prints PC-relative operands as absolute addresses, so one token scan of the
   operand field covers branches, absolute calls and PC-relative forms; immediates print in decimal,
   so scan those too. Run the OS folder's positive controls here as well
   (OS 1.52A: [landing_pads.md](../os/1.52A/notes/landing_pads.md#vetting-controls-on-this-image)).
   A control can show more hits here than in step 3: a PC-relative `lea` of its address is invisible
   to step 3 but counts here. Both counts are right; they measure different reference classes. Also
   run a negative control: a region already confirmed dead must come back 0.
5. **Switch tables.** The firmware dispatches with `movew %pc@(base,%dN:l),%dN` plus
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
   member entry, change nothing else, and check that every section other than the MAIN OS section
   still equals stock. A member whose length is 2 mod 4 gets one trailing `4e75`, so the next entry
   still starts on `4280` and every 2-byte word is a complete harmless instruction. Pass criterion: no
   change at all.
   Why not a bare `rts`: it returns whatever the caller left in `d0`. If that is a stale but valid
   pointer, a live function makes its caller read the wrong record silently, and the test reports
   "dead" for a live function. NULL is deterministic: a caller that checks it degrades visibly, one that
   does not faults visibly, because nothing is mapped at 0 (the boot NOR is behind DSPI, SRAM is at
   `0x8000xxxx`, DDR at `0x4xxxxxxx`; [hardware.md](hardware.md)). For a candidate that returns `void`
   the fill is a weaker instrument; exercise the data structures the code would manipulate.
10. **Moat check.** The pad must lie outside the OS version's protected ranges, and the code put into
    it must never call a flash erase or write
    ([update_moat_method.md](update_moat_method.md#two-rules-for-every-patch)).
11. **First code.** Before any feature logic, the first code through a new hook is an inert
    pass-through that only replays what the hook displaced.

## Heuristics

Two heuristics that have held so far (in OS 1.52A): when a candidate with no references has sibling
overloads that also have none, the whole family is dead; and unused template instantiations (leaves
with a `linkw` prologue, pure pointer bodies and no callees, in the C++ part of the code) are the
cleanest candidates. Avoid audio MAC code reached through dispatch tables, I/O code, and islands of
code at foreign addresses. Where these regions lie is a property of each image
(OS 1.52A: [landing_pads.md](../os/1.52A/notes/landing_pads.md#the-candidate-list-on-this-image)).

## Rules for code in a pad

- **Position independence.** Pad code uses PC-relative branches inside the pad; calls, data and
  string references use absolute-long addresses, including references to the pad's own entries,
  strings and tables. So moving a pad is a byte copy plus repointing every absolute reference into it,
  from outside and from inside, with no reassembly. Objdump it at the new address and confirm that
  every branch still lands inside the pad and that no operand or data word still points at the old
  place.
- **Entry trampolines.** Some hooks replace the first instructions of a stock function with
  `jmp pad ; nop`. A stub in the pad re-executes the displaced bytes verbatim and jumps back to the
  first instruction after them. The displaced bytes must end on an instruction boundary, must not be
  PC-relative, and nothing else may enter the function in the middle of them.
- **Two arrays from one pointer.** When two parallel per-voice arrays lie within ±32 KB, one computed
  pointer reaches both: a scaled-index `lea` gives the address of an element of the first array, and a
  read at a fixed displacement from that pointer reaches the same element of the second. The same idea
  places a build's RAM arrays next to each other (each OS folder's `notes/memory_map.md`).

OS 1.52A examples, with their addresses: [landing_pads.md](../os/1.52A/notes/landing_pads.md#code-in-the-pads).

## Surveying `.rodata`

Constant data has a budget of its own, separate from the code pads; do not add the two figures. Code
pads are overwritten dead functions. The `.rodata` budget is unreferenced constant-data space in the
part of the image that is loaded and never zeroed at boot. Bitmap planes, tables and strings go there;
executable code does not.

The survey for a candidate range:

1. **Uniform fill, inside the loaded image.** The range holds one repeated byte and lies inside the
   image and below the `.bss` boundary, so it is not zeroed at boot and not part of the SRAM-init
   images.
2. **A whole-image word scan**, every byte offset read as a big-endian 32-bit value: 0 values may land
   in the range. This superset check catches pointers held in data tables (invisible to
   `FindAddressLiterals`) and `pea` / `lea #imm` operands (invisible to reference queries). Positive
   control: the same scan must find the known pointers to an existing bitmap and its planes
   (OS 1.52A: [landing_pads.md](../os/1.52A/notes/landing_pads.md#rodata-the-constant-data-budget)).
3. **`FindAddressLiterals`** over the range, starting a little below it: 0 hits.
4. **No near-miss pointers** in the 64 B below the range.
5. **A bound on what comes before.** Whatever ends just below the range must provably stop there, for
   example a table walked with a link-time count that ends where the range starts.

⛔ Uniform ranges next to existing UI data can look exactly like free space and still be occupied: by
a pointer at the range's base, by pointers that land inside it, or as a shared blank resource that many
pointers name. Treat the output of `FindDeadSpace` as a candidate list only; most of its ranges have no
uniform fill, and uniform ones are often occupied
(OS 1.52A: [landing_pads.md](../os/1.52A/notes/landing_pads.md#rodata-the-constant-data-budget)).

Static constant data gets no constructor call. Before placing an object there, check that the code
that uses it never reads a field that only a constructor sets at run time, and copy the field values
from what the real constructor would write. A constructor call cannot simply be added instead:
inserting one into an existing initialiser shifts everything after it.

## Related notes

- [update_moat_method.md](update_moat_method.md): the protected set no pad may touch, and the two rules
  every patch follows.
- [analysis_method.md](analysis_method.md): the disassembly the scans run on, and why no single method
  shows code or data unused
  ([never call anything unused on one method](analysis_method.md#never-call-anything-unused-on-one-method)).
- [hardware.md](hardware.md): the chip's address spaces.
- [icon_artwork.md](icon_artwork.md): the icon artwork that goes into the `.rodata` budget.
- OS 1.52A: [landing_pads.md](../os/1.52A/notes/landing_pads.md): the pads, their evidence and the
  `.rodata` budget.
- OS 1.52A: [memory_map.md](../os/1.52A/notes/memory_map.md): the RAM the build uses and the routes
  ruled out for new code.
- OS 1.52A: [patch_listing.md](../os/1.52A/docs/patch_listing.md): every patched byte run, by feature.
