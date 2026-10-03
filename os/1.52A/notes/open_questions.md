# Open questions

## What this is

What is still not known about the stock Digitakt firmware, where it matters for understanding this
build or extending it. Each entry says what is unknown, why it matters, and what would answer it.
Questions that belong to a single feature are listed at the end of that feature's note; this note
collects the ones about stock code and about behaviour whose cause is not found. The last section
records what is settled, so it is not re-opened.
Design constraints that hold for every version: [notes/update_moat_method.md](../../../notes/update_moat_method.md#design-constraints).

## Behaviour seen on the test unit, cause not found

### Shifted timing for 1–4 bars after a pattern reload

- **Seen.** After a pattern is reloaded and a pattern with no POLY tracks plays, the first 1–4 bars
  sound as if at double tempo, or with every note shifted together; then it corrects itself on a beat.
- **Unknown.** The cause, and whether stock firmware does the same.
- **Why it matters.** Everything shifts together and it self-corrects on a beat, which points at
  sequencer step or phase state shared by all tracks. Candidates: the per-track step/phase bytes
  `DAT_4195fbbb[track]` and `DAT_4195fbcb[track]` that `FUN_4006f882` writes before dispatch; the
  timing counter `_DAT_4195fb14`; and the POLY pool-map refresh this build adds at `0x400773e6`, on the
  pattern-switch path ([POLY voice pool](function_ledger.md#the-pool-map-build-and-refresh);
  [patch listing](../docs/patch_listing.md#poly-engine)). A pattern without POLY tracks
  does not rule the build out: its hooks are global.
- **What would answer it**, cheapest first:
  1. Reproduce on stock firmware.
  2. If stock is clean, reproduce on this build with the hook at `0x400773e6` put back to stock
     (`jsr 0x400d0044`).
  3. During the window, watch SRC page 2 of a track that is audibly sounding. A cursor is drawn only
     when `FUN_40075f58(voice)` is non-zero (the render's status block at `0x8000edc8 + voice*0x5e`
     has the playing flag set and a non-zero length, [render_path.md](render_path.md)), so a missing
     cursor on a sounding track means wrong engine state; on a silent voice it is correct reporting.

### The SLICE waveform picture lags a pattern change

- **Seen.** On SRC page 2 of a SLICE track, a pattern change updates the sound, the grid count and the
  cursor at once, but the waveform picture only after several seconds. Any button press updates the
  picture immediately.
- ⚠️ **Mechanism, read from the code and consistent with the button-press observation.** The
  per-machine feed `FUN_40039684` writes the sample id, grid count, selected slice and span into the
  waveform widget. It has three callers, `FUN_40039942`, the SRC page's input handler and
  `FUN_40039e1e`, and nothing on the pattern-switch path calls it. The widget keeps the sample id it was
  last fed and draws that id's 256-byte min/max record (`id*0x100 + *(factory+4)`, from the
  waveform-cache singleton `FUN_4012396e`; `DAT_401d13b4` is the "no waveform yet" sentinel, drawn as a
  flat line).
- **Unknown.**
  - Which slower stock event eventually reaches the feed after several seconds: is something periodic
    calling `FUN_40039942` or `FUN_40039e1e`?
  - Why the grid count updates at once, when it comes from the same feed as the sample id. If the grid
    really is immediate, the feed does run and only the sample-id resolution is stale, which would move
    the diagnosis.
- **Why it matters.** This build does not touch the feed, the cache or the sample id, so it is almost
  certainly stock (⚠️ not yet reproduced on stock).
- **What would answer it.** Reproduce on stock; find what triggers the other two callers; check
  whether the grid count really updates before the picture.

## Audio engine and events

### What the per-voice record at `DAT_4395df48 + voice*0x14` drives

- **Known.** A per-voice record with stride `0x14`. Its `+4` word (`DAT_4395df4c`) is bumped at note-on
  only for flag-`0x8000` (MIDI/chord) events. `+0xc` is an accumulator that advances by
  `(elapsed * rate) >> 2`, with the rate at `+0x10`, and saturates; a fresh flag-`0x8000` note-on
  zeroes it. On a flag-`0x8000` note-on the ISR reads it back at `0x40077720`, multiplies it by
  `event[0xf] << 8` (a fractional `mac.l`: the ISR sets MACSR to `0x20` at `0x4007718a`), adds the
  event's 16-bit value at `+0x14`, clamps the sum to 0..`0x7f00` and stores it in the per-voice u16
  array at `0x80001f18`, which the ISR passes to both render functions (objdump).
- **Unknown.** What that per-voice value is, and so what the ramp does.
- **Why it matters.** It is engine state in the ISR's note-on path, which the POLY pool, voice
  allocation and MIDI Loopback all feed. It is indexed after the pool remap, so it follows the voice
  rather than the originating track (objdump: the record's address is built from the voice that the
  hook at `0x400774a2` returns in `%d2`).
- **What would answer it.** Read how `FUN_40074e84` and `FUN_400754fe` use `0x80001f18`, their fifth
  argument.

### Where the sample memory starts

- **Known.** DDR is one 128 MB chip. `.bss` ends at `0x439902a0` and the main stack starts at `0x48000000`, so
  about 70 MB lie between them. The Digitakt's specified 64 MB of sample memory cannot fit in `.bss`, so it very
  probably lies in that region ([memory_map.md](memory_map.md)). A literal sweep of the region finds no base
  address, only the stack top and numeric constants.
- **Unknown.** Where the sample memory starts and ends.
- **Why it matters.** This build keeps its RAM state at `0x439902a4..0x43990381`, at the bottom of that region.
  ⚠️ That it does not overlap the sample memory rests on the sweep and on no problem seen on the test unit.
- **What would answer it.** The per-voice sample base pointers the render reads (the per-voice state from
  `0x8000edc4`, stride `0x5e`), and the code that fills them, or the sample allocator's own base computation.

### Which events take the ISR's parameter-only branch

- **Known.** The ISR tests `(event[9] & 0x81) == 1` before anything else and sends such events down a
  parameter-only branch, which calls `FUN_4007480a(event[2], event[0x11], 0x800014f0)` at `0x400775d4`
  to apply a parameter object to that one voice. Trigless lock trigs take this branch. ✅ It is not the
  live knob path: replicating that call over a pool changed nothing for SRC, FLTR or AMP knob turns;
  live knob turns reach the voice through `FUN_40076ee8`.
- **Unknown.** Which of the event producers (the callers of the event allocator `FUN_400ddb12`) emit
  events in this branch besides lock trigs, for example parameter slides.
- **Why it matters.** The POLY voice pool routes this branch specially
  ([POLY voice pool](function_ledger.md#audio-isr-event-dispatch-and-the-note-gate);
  [patch listing](../docs/patch_listing.md#poly-engine)), so every producer that uses it is affected.
- **What would answer it.** Trace the producers for the `0x81` flag pattern; test a parameter slide on
  a pool.

### The ISR's shared-SRAM message handoff

- **Known.** `FUN_40003664` fetches one message per tick from a double-buffered block at `0x80001f60` /
  `0x80002060` (chosen by `_DAT_4195ffe4 ^ 1`) and hands it to `FUN_400dd3a8`, which turns its 4-byte
  note records into events with priority 2 (`event[3] = 2`). A message that is not yet due is parked in
  a single-slot stash (`_DAT_41960324`, its time in `_DAT_41960320`).
- ⚠️ **A loss path in the code.** If a message is already stashed and the newly fetched one is due, the
  stashed message is overwritten and never processed. The stashed time is relative and is not re-based
  when it is consumed a tick later.
- **Unknown.**
  - What produces these messages. External MIDI note input does not come this way: it runs through
    `FUN_4007683c` → `FUN_400ddd72`.
  - Whether a message carries one record or a batch (`FUN_400dd3a8` walks up to `msg[1] & 0xf`
    records). Batching would change how much the loss path can drop.
- **Why it matters.** It is a second source of note events into the ISR, alongside the ones
  [voice allocation](features/voice_allocation.md) and [MIDI Loopback](features/midi_loopback.md) are
  built around.
- **What would answer it.** Find the writers of the two buffers (run `FindAddressLiterals` as well as a
  reference query: they may be filled by DMA), then read the producer.

### Can an event with `event[2]` ≥ 8 reach the note-on handling?

- **Known.** The parameter system indexes tracks 0–15, with 16 for the master track; the voices are
  0–7. `FUN_400dd3a8` sets `event[2]` to the MIDI channel, meaning a MIDI track 8–15, for records that
  are not for an audio track.
- **Why it matters.** The POLY pool's note-on code at `0x40037746` reads `groupSource[event[2]]` from the
  8-byte table at `0x439902f0` with no bounds check, and `groupCursor[8]` follows at `0x439902f8`. An
  `event[2]` of 8 or more would read a cursor byte as a source. ⚠️ Not seen to misfire on the test
  unit.
- **Unknown.** Whether events from MIDI tracks or the master track are filtered out before the ISR's
  note-on handling at `0x400774a2`.
- **What would answer it.** Trace the ISR from the event pop to `0x400774a2` for an `event[2]` of 8 or
  more. A guard in the pad costs one compare and one branch.

### What `FUN_40076938` and the counter at `0x4395de14` are

- **Known.** The audio init `FUN_40076b5a` registers `FUN_40076938` through `FUN_4000116a`.
  `FUN_40076938` and `FUN_400bca1c` increment `0x4395de14`, which `FUN_400032fc`, `FUN_40003664` and
  `FUN_400060d2` read. ⚠️ Probably an audio tick or block counter.
- **Unknown.** What `FUN_4000116a` registers a function for (a timer, an interrupt or a task hook), and
  what the counter counts.
- **Why it matters.** `FUN_40003664` is the ISR's message fetch above, so the counter may pace it.
- **What would answer it.** Decompile `FUN_4000116a` and `FUN_40076938`.

### What the ISR uses `FUN_40075f9a` for

- **Known.** It returns `(position << 7) / (length >> 8)`, the play position as a fraction at 15-bit
  scale, from the same per-voice status block the pool cursors draw from. Its caller is the ISR.
- **Unknown.** What the ISR does with the value.
- **What would answer it.** Read its call site in `FUN_40077120`.

## Data model and storage

### When an edit reaches the eMMC

- **Known.** The active project persists through the data model's storage objects, an LZ4 stream
  compressor and a crash-safe eMMC stream writer (vtable `0x40193dc0`), to the eSDHC controller at
  `0xfc0cc000` and the eMMC (the +Drive). There is an eMMC stream writer but no NOR one, and every NOR
  path traced (driver cluster `0x400d8xxx`) leads to boot, firmware or transfer code, so ⚠️ the SPI NOR
  plays no part. The project cache is an observer of the data model (`0x40146b50`), so persistence is
  driven by changes.
- **Unknown.**
  - Whether each change is flushed at once or batched by a debounce or periodic timer.
  - Where the live active project sits in the eMMC layout.
  - How samples are loaded by hash over the eSDHC.
- **Why it matters.** The values this build adds (the new SLICE Select values, the POLY machine byte,
  the TRK1–TRK8 CHAN values) are saved through this path. The flush policy decides how soon after
  an edit power can be cut.
- **What would answer it.** A device timing test: edit, cut power after N ms, check whether the edit
  survived, and sweep N. Or dynamic analysis.

### Negative values, and the eMMC form of the p-lock pool

- **Known.** In a SysEx pattern dump the p-lock pool at `0x38f4` is 80 lanes × 130 B: an empty lane
  starts `ff ff`; an occupied one is `[code][track]` followed by 64 × u16, `0xffff` for an unlocked step
  and the 8.8 value otherwise ([sysex_dump.md](sysex_dump.md), [pattern_layout.md](pattern_layout.md)).
- **Unknown.**
  - How a negative value is stored in a lock (Pan or Pitchbend below zero). Two's-complement 8.8 is
    expected, but no dump with a negative lock has been examined; nor has any rounding in the display.
  - The on-disk layout of the pattern's own p-lock storage on the eMMC, which is a separate path from
    the SysEx dump, and whether it matches the `ff ff` layout.
  - The exact offset and stride of the pattern array inside the project data. `_DAT_4195fae8` points
    into it; the value flows through stack structures across the callers of `FUN_4006fb8e`.
- **Why it matters.** This build stores negative values: the new SLICE Select values sit below NOTE
  (RRBN is −1), and TRK1–TRK8 are −8 to −1 on CHAN
  ([SLICE round robin](features/slice_round_robin.md), [MIDI Loopback](features/midi_loopback.md),
  [compatibility.md](compatibility.md)). How they appear in locks and dumps is
  what an external editor, or another firmware version, will see.
- **What would answer it.** One controlled device diff: lock a negative Pan, dump the pattern, compare.

### Who builds the parameter-page layout records

- **Known.** Four 44-byte records at `0x4193edb4` (stride `0x2c`: two string-like name fields, then 9
  parameter ids, where 0 means none), chosen by `FUN_40065588(machine)`. Machine 4 and above falls back
  to `0x4193ee38`, which is the SLICE record. The records lie above the image end, so they are built at
  run time, and there is no slack before the generic table at `0x4193ee64`.
- **Unknown.** The code that builds them.
- **Why it matters.** Any machine beyond the four stock ones gets the SLICE page layout. POLY avoids
  this by reporting its Source's machine
  ([POLY voice pool](function_ledger.md#src-page-1-layout-and-the-machine-a-page-shows);
  [patch listing](../docs/patch_listing.md#poly-ui)); adding a
  layout of its own would need the builder.
- **What would answer it.** `DumpRefsInRange` and `FindAddressLiterals` over `0x4193edb4..0x4193ee64`
  (both: references alone miss immediate-built addresses), then decompile the writer.

### Two Sound containers

- **The 200-byte Sound object.** The kit-embedded Sound (`kit + 0x20`, stride `0xa2`) and a separate
  200-byte Sound object (`FUN_4000d2d6` = `base + 0x60 + track*200`) share their fields up to `+0x7e`.
  Where the 200-byte array lives is unknown; it cannot sit at `kit + 0xd4` without overlapping the
  `0xa2` array. It matters because the POLY pages' remapped track is resolved through `FUN_4000d2d6`.
- **The kit's eight tail blocks.** Eight 0x70-byte blocks at `kit + 0x59a + n*0x70` (serialised by
  `FUN_40079e44`, loaded by `FUN_40079de4`) hold a 41-parameter subset of a Sound with near-default
  values. What reads them is unidentified: the access is pointer plus offset, invisible to reference
  queries.
- **What would answer it.** A manual hunt for the consumers, or a device observation.

## Boot and the other sections

### What starts section 2, and what it hands off to

- **Known.** Section 2 (run base `0x80000ec0`) is boot-time bring-up and self-test code and a boot
  loader: `FUN_8000767c` loads a module descriptor at `0x8000f000` with `FUN_80005820(4, &0x8000f000)`
  and reads the module from `flash + 0x80000` with the DSPI read `FUN_8000758c(flash_addr, len, dst)`.
  Its init `FUN_800014da` writes a boot status to `0x8000765e` and checks `0xB0B0DADA` at
  `0x48000000`. Sections 2 and 4 share a low-SRAM library at `0x80000400..0x80000ec0`
  ([section2_map.md](section2_map.md)).
- **Unknown.**
  - What triggers it: the normal power-on path, a held-key test or startup mode, or an early boot
    stage.
  - Which code implements the [FUNC] + power STARTUP menu and its OS UPGRADE receiver.
  - Where `FUN_800014da` goes at the end (Ghidra shows `halt_unimplemented`).
  - The bodies of `FUN_80002b9e` (572 B), `FUN_80002902` (564 B), `FUN_80004946` (628 B) and
    `FUN_80004754` (498 B), and about 50 leaf helpers.
- **Why it matters.** The startup-menu recovery route lives in this code
  ([flash_recovery.md](../../../notes/flash_recovery.md)). This build leaves sections 2 and 4 byte-identical, so the
  route is stock either way; knowing it would say exactly what the recovery depends on.
- **What would answer it.** Trace `FUN_800014da`'s final jump; find what reads the boot status.

### Which UI code runs before the first usable screen

- **Known.** The machine-type query `FUN_4002af58` runs as the screen first draws, and the
  parameter-grid draw runs at first paint ([startup_hooks.md](startup_hooks.md)).
- **Unknown.** Whether the SRC page's 30 Hz tick (which invalidates the page every 12th tick and feeds
  the playhead cursor) runs before the UI is up, and which page the unit restores at startup, hence
  which page draws run then.
- **Why it matters.** It decides which hook sites count as startup-path code, the class of fault that
  needs the startup-menu recovery.
- **What would answer it.** Trace startup from the UI controller's construction to the first paint.
  Until then, treat every draw-path hook as startup-path.

### Small items

- **The first 8 longwords of DDR.** What section 2 does with `0x40000000..0x4000001c`: vectors 0–7, or
  a mailbox.
- **A pointer inside a cleared buffer.** `0x402506c0` is referenced inside the 512 KB buffer that
  `FUN_4006753e` (the host-communication task, on the update path) zeroes from `0x40225e70`: a
  sub-object, or is the buffer smaller than it looks?
- **The meta section's 15 bytes**, assumed to be a build stamp and never read
  ([stock_image.md](stock_image.md)).
- **`0x4000b564`**, a 2,270-byte function that is the target of a slot in hundreds of vtables: a shared
  base or not-implemented handler? Decompile it and see whether it returns or throws. Knowing it would
  remove a lot of noise from vtable reading.
- **The SysEx entry hook's ports.** `FUN_400c4862`, registered 128 times in the table at `0x401d1ab4`,
  ignores every port index except 2 and 4. Which physical interface is 2 and which is 4 is untested.

## Coverage and the transfer channel

### Which code is reachable at all

- **Known.** The code region is 99.91 % disassembled in the seeded project, but "dead" is still argued
  from missing references, and the only proof a landing pad is free is a run on a unit
  ([landing_pad_method.md](../../../notes/landing_pad_method.md),
  [analysis_method.md](../../../notes/analysis_method.md)).
- **Why it matters.** Landing-pad vetting from references alone can be wrong in both directions, and every new feature needs
  free space.
- **What would answer it.** A reachability closure over the seeded project. Roots: the reset vector →
  crt0 → the application entry, the 16 spawned tasks, every interrupt vector, every vtable slot, every
  `switchD_*` case, every function pointer installed at boot, and a `FindAddressLiterals` sweep for
  code addresses built from immediates. Close over direct and resolved indirect edges; anything outside
  becomes a positively argued dead candidate. Annotating the peripheral registers from the MCF54415
  reference manual would harden this and make the driver decompiles readable.

### Why OS transfers sometimes wedge

- **Known.** A transfer sometimes stalls or will not start, with stock firmware as well as with built
  images, and a restart clears it ([flash_recovery.md](../../../notes/flash_recovery.md)).
- **Unknown.** Where the fault is: the host, the USB-MIDI link, or the unit's receive state.
- **What would answer it.** Whether restarting before every transfer makes it reliable; if it recurs,
  look on the host side (the transfer application, the MIDI port) rather than at the image.

## Settled, not open

- ⛔ **Ruled out as a defect: a SLICE sample chain playing lower with Select ≠ NOTE.** Stock
  `FUN_40074e84` plays NOTE mode at the fixed note 60 (`0x3c0000`) and every other Select value, the
  round-robin values included, at the trig's note. A trig at note 52 plays 8 semitones below NOTE mode; set the
  trig note to 60 for the same pitch ([SLICE round robin](features/slice_round_robin.md)).
