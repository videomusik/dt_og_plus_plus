# Compatibility with stock firmware

## What this is

What happens to projects moved between this build and stock OS 1.52A, in both directions, and the
loader rules that decide it. Every new value this build stores lies outside the range stock firmware
uses, and the build changes no stored format and no data version. A project saved with RRBN or RRND
plays on stock with those tracks as NOTE. POLY tracks are the lossy case: ⚠️ most likely they come
back as ONESHOT.

## At a glance

| Saved on this build | Loaded on stock 1.52A | Basis |
|---|---|---|
| SLICE Select = RRBN (−1) or RRND (−2) | The track plays, with Select as NOTE. The rest of the project is intact, and nothing crashes | ✅ confirmed on the test unit |
| A track set to the POLY machine | ⚠️ Most likely loads as ONESHOT (machine 0): the stock kit loader clamps machine 4 to 0 | ⚠️ inferred from code; not tested on stock |
| A MIDI track with CHAN = TRK1–TRK8 | ⚠️ The value is kept, and that MIDI track sends nothing | ⚠️ inferred from code; not tested on stock |
| Anything else | Unaffected: the other features change code, constants and RAM, not what a project stores | ⚠️ read from `os/1.52A/build/patch.json` |

Loading a project that uses the POLY machine or a TRK value on stock firmware is still to be tested
on a unit. The two ⚠️ rows for them are what the code predicts.

A project made on stock firmware loads unchanged on this build, because it contains none of the new
values. The [tick-wipe fix](features/tick_wipe_fix.md) removes a stock defect that such a project can
meet.

**The device still reports OS 1.52A.** The build does not rewrite the version string, and
`os/1.52A/build/build.py` requires the packed file to report version 1.52A. The version display does not tell
this build from stock. A build's file name carries the first 8 hex digits of its SHA-256, and section
3's SHA-256 identifies the firmware ([stock_image.md](stock_image.md)).

## Three kinds of change

| Kind | What changes | What stock firmware does with it |
|---|---|---|
| A: reinterpret a value | Nothing in the bytes; only what an existing value means | Loads and plays it with the stock meaning. Safe in both directions, but old content that uses that value behaves differently on the new firmware |
| B: a new value, same version | A field takes a value stock never writes; the stored footprint and the data version stay the same | The version gate passes, so stock loads it and meets the value in its loader or at run time. Safe only where that code is bounded |
| C: a footprint or version change | A struct grows, so a data version is bumped | Stock's exact version gate fails, and it resets that object to its default. Clean, but lossy |

The three features that store something new (SLICE round robin, the POLY machine, MIDI Loopback) are
all kind B. None is kind A: stock values keep their stock meaning (Select 0 is NOTE, machines 0–3, CHAN
1–16). None is kind C.

## Kind B: what bounds a new value on stock

The stored footprints are fixed: a pattern is 31,613 B, a kit `0xa00`, a sound in the kit `0xa2`, with
the machine byte at `sound + 0x7e`. A new value in an existing field therefore keeps the object
loadable, and safety depends on the code that meets the value.

**SLICE Select.** The render `FUN_40074af2` reads Select at `paramblock + 8` as a signed byte, applies
`max(Select, 0)`, then limits it to the slice count; 0 means NOTE. Grid is limited to 4, so the count
(`4 << grid`) is at most 64. A negative Select therefore plays as NOTE on stock, which is what the test
unit showed ([slice_round_robin.md](features/slice_round_robin.md)).

**The machine byte (POLY = 4).** Stock stops a machine value of 4 when the kit is loaded, before any
render code sees it:

- The sound serializer `FUN_40079fa6` copies the machine byte (`sound + 0x7e` to stored `+0x7c`) without
  a check, so POLY is saved in the project.
- The per-sound kit deserializer `FUN_4007a902` (reached from `FUN_4007aaba` ← `FUN_4007abfa` ←
  `FUN_4001b3e8`, loading a kit from the eMMC) keeps the stored machine only if `machine + 1 < 5`, and
  writes 0 otherwise. The bound comes from `moveq #5,%d2` at `0x4007a994`. That keeps 0–3 and the −1
  (`0xFF`) "no sound" sentinel, and turns 4 into 0, ONESHOT.
- This build changes that one immediate to 6 (the byte at `0x4007a995`), so it keeps 0–4 and still
  rejects 5 and above. On stock the clamp is intact, so ⚠️ a POLY track most likely comes back as
  ONESHOT. This is read from the code and has not been tried on stock firmware.
- Behind the loader, the stock paths that dispatch on the machine are bounded too. The voice setup
  `FUN_40074e84` is an if/else chain over machines 0–3 with no jump table, and gives an unknown
  machine a null (silent) voice. The parameter resolver `FUN_40078c2c` returns a parameter id only for
  machine < 4 and 0 otherwise, and the stock name accessor `FUN_40078df4` is limited to type < 4 as
  well. ⚠️ Other machine-indexed tables have not been audited.

**CHAN = TRK1–TRK8.** The TRK values are stored as negative CHAN values (−8 to −1). The kit
deserializer `FUN_4007aaba` clamps only track levels, and the storage round trip
(`FUN_40079de4` / `FUN_40079e44`) carries a negative 8.8 value intact, so ⚠️ the value survives on
stock. Stock's channel lookup then returns a negative channel, which every MIDI emitter treats as "do
not send" ([midi_loopback.md](features/midi_loopback.md)). Inferred from the code, not tested on stock.

## Kind C: the version gates

Every deserializer checks an exact version and initialises a default on a mismatch:

- **Kit:** `FUN_4007abfa` requires version 9. Otherwise the loader initialises a cleared kit, with all
  eight sounds at their defaults.
- **The `0x70` kit-tail block:** `FUN_40079de4` requires `stored[0] == 1`. Otherwise
  `FUN_40083858` supplies the default block.
- **Sound:** the stored sound carries the magic `0xbeefbace` and version 2.
- **Pattern and project** storage carry their own version numbers.

Bumping one of them would make stock reset that object rather than load it. This build bumps none:
`os/1.52A/build/patch.json` touches no serializer and no version gate, and its one loader edit is the machine
bound at `0x4007a995`.

## Related notes

- [features/slice_round_robin.md](features/slice_round_robin.md): RRBN and RRND.
- The POLY machine: its code sites in
  [function_ledger.md](function_ledger.md#machine-assignment-the-pool-map-and-the-audio-isr), its byte
  runs in [docs/patch_listing.md](../docs/patch_listing.md#poly-engine).
- [features/midi_loopback.md](features/midi_loopback.md): the TRK values of CHAN.
- [pattern_layout.md](pattern_layout.md): the stored layouts.
- [parameters.md](parameters.md): the parameter descriptors and their ranges.
- [stock_image.md](stock_image.md): the version string and the build's identity.
