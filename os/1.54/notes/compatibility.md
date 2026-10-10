# Compatibility with stock firmware

## What this is

What happens to projects moved between this build and stock OS 1.54, in both directions, and the
loader rules that decide it. The build stores these new values: a negative SLICE Select (RRBN, RRND),
machine 4 (POLY) and machine 5 (CFOO), negative CHAN values (TRK1–TRK8), and PORT and LEG, in two
spare words of the stored sound and as p-lock records with the stored indices 46 and 47. It changes no
data version, and the stored records keep their size and layout.

One observation on the test unit, in the order it ran ([README.md](README.md#the-test-unit)):

- A project with POLY tracks was active while stock OS 1.54 and the images with the stock MAIN OS ran.
- On the DT OG++ build that followed, its first pattern did not play the POLY tracks.
- Reloading the project from +Drive, where it had been saved by a DT OG++ build of another OS version,
  brought them back.

⚠️ This fits the stock loader dropping machine 4 from the active project (below). What stock put in
its place was not recorded. Nothing else here has been tried on a unit.

## At a glance

| Saved on this build | Loaded on stock 1.54 | Basis |
|---|---|---|
| SLICE Select = RRBN (−1) or RRND (−2) | ⚠️ The track plays, with Select as NOTE | ⚠️ read from the code (below); not tested |
| A track set to the POLY machine | ⚠️ Most likely loads as ONESHOT (machine 0): the stock kit loader clamps machine 4 to 0 | ⚠️ read from the code (below); not tested |
| A track set to the CFOO machine | ⚠️ Most likely loads as ONESHOT, which reads CFOO's SRC values as its own: the stock loader clamps machine 5 to 0 as well | ⚠️ read from the code (below); not tested |
| PORT and LEG, and their p-locks | ⚠️ Ignored: the stock reader does not read the spare words, and maps a lock with stored index 46 or 47 to slot 0, which no parameter uses | ⚠️ read from the code ([features/portamento.md](features/portamento.md#saving-port-and-leg-s33)); not tested |
| A MIDI track with CHAN = TRK1–TRK8 | ⚠️ The value is kept, and that MIDI track sends nothing | ⚠️ inferred; the emitters were not re-read in OS 1.54 |
| A recording made with Chain Recording | An ordinary sample: the chain is one recording, N slot lengths long | ✅ the stock end of recording saves it ([features/chain_record.md](features/chain_record.md)) |
| Anything else | Unaffected: the other features change code, constants and RAM, not what a project stores. Chain Recording keeps its slot count and arming mode in RAM only | ⚠️ read from `os/1.54/build/patch.json` |

A project made on stock OS 1.54 loads unchanged on this build, because it contains none of the new
values. The stock writer clears the spare words of a stored sound, so its PORT and LEG load as OFF.

**The device still reports OS 1.54.** The build does not rewrite the version string, and
`os/1.54/build/build.py` requires the packed file to report version 1.54. A build's file name carries
the first 8 hex digits of its SHA-256, and section 3's SHA-256 identifies the firmware
([stock_image.md](stock_image.md)).

## What bounds each new value on stock

**SLICE Select.** The render `FUN_40074df2` reads Select at `paramblock + 8` as a signed byte
(`mvsb %a0@(8),%d4` at `0x40074e30`) and applies `max(Select, 0)` with the `not / add / subx / and`
idiom that follows. A negative Select therefore plays as NOTE (objdump).

**The machine byte (POLY = 4).**

- The kit gate at `0x4007a4ce` accepts a stored kit only if its version word is 10
  (`moveq #10,%d1 ; cmpl %a0@,%d1`) and then branches to the kit loader at `0x4007a386` (objdump).
- The per-sound deserializer `FUN_4007a236` keeps the stored machine only if `machine + 1 < 5`; the
  bound is `moveq #5,%d2` at `0x4007a2d0`. That keeps 0–3 and the −1 "no sound" sentinel and turns 4
  into 0, ONESHOT (objdump). This build changes that one immediate to 7, which keeps machines 4
  (POLY) and 5 (CFOO).
- ⚠️ So on stock OS 1.54 a POLY or CFOO track most likely comes back as ONESHOT. Not tested.

**CHAN = TRK1–TRK8.** ⚠️ The TRK values are stored as negative CHAN values (−8 to −1). The 8.8 storage
round trip `FUN_4007a0c2` is byte-identical in shape to the code that carries such a value intact, and
the MIDI emitters that this build hooks are unchanged around their hook sites
([function_ledger.md](function_ledger.md)). That the value survives on stock and makes the track send
nothing is inferred from that, not read through every emitter in OS 1.54.

## Data versions

OS 1.54 stores a kit with version 10 (the gate above), and its RTTI names carry the storage versions
`kitStorage_v10_t`, `patternStorage_v10_t`, `projectStorage_v16_t` and `soundStorage_v3_t`
(strings in `dt_1.54_emac`). These are Elektron's; this build bumps none of them and touches no
version gate. Its edits to the loader and the writers:
- the machine bound at `0x4007a2d0` (POLY, CFOO);
- portamento's ([features/portamento.md](features/portamento.md#saving-port-and-leg-s33)): the sound
  writer stores 48 words instead of 46, into two spare words inside the area it already clears; the
  p-lock writers take their slot → stored index table from this build's 48-entry copy; the two index
  lookups map 46 and 47 to themselves; and the sound reader loads the spare words, after checking
  them, before its own value loop.

## Related notes

- [function_ledger.md](function_ledger.md): the hook sites and the functions named here.
- [stock_image.md](stock_image.md): the version string and the build's identity.
