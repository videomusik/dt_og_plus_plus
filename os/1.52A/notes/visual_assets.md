# Visual assets

## What this is

This build draws two new 1-bit images: a robin for the SLICE values RRBN and RRND, and a small
keyboard for POLY in the machine menu. This note gives the stock `Bitmap` format of OS 1.52A that
they are encoded in. It also records where each image lives in the firmware image, and the bit order
of rows, which is easy to get upside down. Both pixel grids, their master images and the house
style are in [icon_artwork.md](../../../notes/icon_artwork.md).

The masters are in [assets/icons/](../../../assets/icons/). In each, a white pixel is a `#` in the
grid, a set bit in the colour plane. The grids remain the source of truth, and
[build/patch.json](../build/patch.json) holds the encoded bytes. Each master decodes back to its
grid pixel for pixel, and each grid encodes to exactly the colour-plane bytes in `os/1.52A/build/patch.json`.
Neither image has a mask plane of its own: each points plane B at a fully opaque stock mask
([shared masks](#static-bitmaps-and-shared-masks)), so the master is the whole image.

## The `Bitmap` format

A stock `Bitmap` is a 28 B object in `.bss`. It is built at boot by the UI bitmap resource
constructor `FUN_400f5c2c` (67.9 KB), which calls the constructor `0x400b1f4e` as
`ctor(this, width, height, planeA, planeB)`. The objects include `0x421b9b60..0x421b9c58` and the
machine-menu icons at `0x421b957c..0x421b991c`, and a second pass at `0x4010651c` walks the same list.
Only the pixel planes are in the image. ⚠️ Many stock planes lie around `0x4020e000..0x4020f800` (not
surveyed); some, such as `0x401feec8` and `0x401ff5a0`, lie lower.

| Offset | Field |
|---|---|
| `+0x00` | vptr (`0x4019a194`, set by the constructor) |
| `+0x04` | width, px |
| `+0x08` | height, px |
| `+0x0c` | stride = words per column = `(height + 31) >> 5` |
| `+0x10` | plane A: the colour bits |
| `+0x14` | plane B: the mask (all ones = an opaque tile, not a sprite) |
| `+0x18` | a byte, 0 |

**Pixel data is column-major.** A plane is `width` columns, each `stride` big-endian 32-bit words.
A bitmap up to 32 px tall therefore costs exactly 4 bytes per column. In stock bitmaps, plane B sits
immediately below plane A in memory, so the pointer difference (`width × stride × 4`) cross-checks
the dimensions.

**Row order: row `j` is bit `(32 − height + j)`. The bottom row sits at bit 31.**

| Height | Rows occupy | Row 0 at |
|---|---|---|
| 17 | bits 15–31 | bit 15 |
| 7 | bits 25–31 | bit 25 |

- **Three independent checks.**
  - The 46 × 31 stock badge with plane A at `0x4020f1e4` reads "TRK" only under this order (decoded
    from the file).
  - The 46 × 31 stock badge with plane A at `0x401feec8` starts with a letter F only under this order
    (decoded from the file).
  - ✅ The asymmetric keyboard below, encoded with this rule, renders right way up on the test unit.
    So does the robin.
- ⛔ **Ruled out: "the most significant bit is the top row".** It can be derived from the blit's shift
  direction and is self-consistent, but it renders every image upside down.
- ⛔ **Ruled out: row-major planes.** They render every glyph transposed.
- ⚠️ **Text is the reliable orientation control**, and each new asset is checked by encoding it from
  its grid and decoding the bytes back: see
  [Checking an encoding](../../../notes/icon_artwork.md#checking-an-encoding). All four stock
  machine-menu icons are vertically symmetric, so they cannot reveal a flip at all. The rule itself
  is settled.

**The blit** is `FUN_400b3b80` (376 B), `(canvas, bitmap, x, y, centre)`.

- It has 58 callers and **no callees**.
- It reads the bitmap only as fields (`+0x04`, `+0x08`, `+0x0c`, `+0x10`, `+0x14`) and never touches
  `+0x00`.
- A non-zero `centre` subtracts `w/2` and `h/2`.
- It clips on both axes.
- It composites `dst ^= (dst ^ A) & B`, so plane B selects which pixels are written.
- Ghidra inlines it into some callers (for example `entry_40065234`), which makes those decompiles
  hard to read.
- The canvas has the same field layout, which is why the blit indexes canvas storage by x.

## Static bitmaps and shared masks

**A `Bitmap` can be static constant data.** The blit never reads `+0x00`, so there is no virtual
dispatch on a bitmap. A 28 B struct holding the constructor's field values works without a
constructor call. That matters because a call cannot be inserted into `FUN_400f5c2c`: insertion
shifts everything after it. Both new images are such structs.

**Masks can be shared.** Every stock bitmap stores its own mask, but opaque masks are identical
all-ones blocks. A fully opaque 17 × 17 mask (68 B: 17 column words, each with bits 15–31 set)
occurs 105 times in the image. Each new image points plane B at an existing stock mask and stores
only its colour plane:

| Image | Colour plane | Struct | Total | Shared mask |
|---|---|---|---|---|
| 17 × 17 tile | 68 B | 28 B | 96 B | the NOTE tile's mask, `0x4020f6f4` |
| 11 × 7 icon | 44 B | 28 B | 72 B | machine-menu icon 1's mask, `0x401ff5a0` (fully opaque: 11 column words, each with bits 25–31 set) |

## Two asset classes

- **Parameter-cell graphics are 17 × 17.** These are the picture in a parameter's cell: the stock
  NOTE bitmap `0x421b9c3c`, the stock slice-ruler bitmap `0x421b9b60`, and the robin.
  - Each parameter's drawer is a `std::function` at `+0x24` of its runtime record. The record table
    is at `0x4193f1a8`: 164 records × 0x54, filled by `FUN_4013b6a2`.
  - `ParameterSet::vfunc_23` (`0x4000edd4`) dispatches the drawer.
  - See [parameters.md](parameters.md). The Slice Select drawer is described in
    [features/slice_round_robin.md](features/slice_round_robin.md#cell-icon).
- **Machine-menu icons are 11 × 7.** The constructor immediates are `pea 0x7` / `pea 0xb` at
  `0x400f81d2`, `0x400f82c2`, `0x400f8902` and `0x400f89d2`. The menu's row pitch is 12 px
  (`MachineListView::vfunc_4`, `0x40029b6e`), so a 17 px tile could not fit.
- **The stock NOTE tile is the style reference.**
  - It is a 17 × 17 opaque tile showing a piano keyboard, black keys at the top.
  - Its artwork is 11 × 11, centred.
  - Its planes are `0x4020f738` (colour) and `0x4020f6f4` (mask), 68 B each; it is built at
    `0x400f5e62`.
  - The house style it shows is in [icon_artwork.md](../../../notes/icon_artwork.md#house-style).

## The robin (RRBN and RRND)

The tile is 17 × 17 and strictly 1-bit, with 97 ink pixels. Its grid is in
[icon_artwork.md](../../../notes/icon_artwork.md#the-robin), and its master image is
[assets/icons/robin_17x17.png](../../../assets/icons/robin_17x17.png).

**Plane A**, one word per column (0–16), under the row-order rule above:

```
00000000 00080000 03e80000 047e0000 483f0000 783f0000 10ff0000 11ff0000 13fe0000
53f00000 7be00000 1bc00000 0f800000 0e000000 1c000000 18000000 00000000
```

Worked check, column 4: ink in rows 1–6, 12 and 15 gives bits 16–21, 27 and 30, which is
`0x483f0000`.

| What | Address | Contents |
|---|---|---|
| Colour plane | `0x40213b50` | 68 B, the words above |
| `Bitmap` struct | `0x40213b94` | `4019a194 00000011 00000011 00000001 40213b50 4020f6f4 00000000` |

The Slice Select cell drawer shows the robin for any negative value, so both RRBN and RRND show it.
The selector code is in [features/slice_round_robin.md](features/slice_round_robin.md#cell-icon).
✅ upright on the test unit

## The keyboard (POLY in the machine menu)

The icon is 11 × 7 and 1-bit, with 47 ink pixels, and deliberately vertically asymmetric, so a flip
would show. Its grid is in [icon_artwork.md](../../../notes/icon_artwork.md#the-keyboard), and its
master image is [assets/icons/poly_keyboard_11x7.png](../../../assets/icons/poly_keyboard_11x7.png).

**Plane A**, one word per column (0–10):

```
fe000000 fe000000 e0000000 00000000 e0000000 fe000000 e0000000 00000000 e0000000 fe000000 fe000000
```

Only columns 2, 4, 6 and 8 depend on the row order. Under the wrong order they would read
`0e000000` instead of `e0000000`.

| What | Address | Contents |
|---|---|---|
| Colour plane | `0x40213bb0` | 44 B, the words above |
| `Bitmap` struct | `0x40213bdc` | `4019a194 0000000b 00000007 00000001 40213bb0 401ff5a0 00000000` |
| Selector table | `0x40213bf8` | `{ 0x421b957c, 0x40213bdc }`: index 0 = icon id 4 (SLICE's stock bitmap), index 1 = id 5 (POLY) |

**How the machine menu picks an icon.**

- `FUN_40029804(type)` (26 B) maps a machine type to an icon id.
  - Stock uses a bounds-checked four-entry table at `DAT_401a5133`, `01 02 03 04`, so id = type + 1.
    It returns 0 for a type of 4 or more.
  - The build rewrites the function in place to compute `type + 1` for types 0–4, which covers POLY
    (type 4 → id 5). The new code is 18 B; the remaining 8 B are `rts` fill.
  - ⛔ **Ruled out: extending the table.** The next byte, `0x401a5137`, is the `%` of the `"%.15s"`
    format that `FUN_40029a76` uses to draw every machine's name.
- The mapper has a second caller, `MachineListView::vfunc_4` (`0x40029b6e`, 732 B). It uses the id
  only to detect a change between consecutive rows (and then draws a separator with `FUN_400b5134`),
  never as an index. Widening the range is therefore safe there.
- `FUN_40029820` (206 B) is the icon draw callback.
  - Stock accepts ids 1–4 and returns silently on anything else, which leaves a blank column.
  - Each case adds 2 to x, subtracts 1 from y, clears the centre flag, loads its bitmap and
    tail-jumps (`jmp`) to the blit.
  - The stock bitmaps are id 1 `0x421b991c`, id 2 `0x421b983c`, id 3 `0x421b9640` and id 4
    `0x421b957c`, all 11 × 7. The constructor calls take each Bitmap address as an immediate at
    `0x400f81dc`, `0x400f82cc`, `0x400f890c` and `0x400f89dc` (inside the `pea` at `0x400f81da`,
    `0x400f82ca`, `0x400f890a` and `0x400f89da`).

The build changes the id-4 case with two exact-length edits and one pad:

| Where | Size | Now |
|---|---|---|
| `0x4002987a` | 8 B | `subql #4,%d0 ; moveq #1,%d1 ; cmpl %d0,%d1 ; bcss 0x400298e4`. This is a range test: id 4 → 0, id 5 → 1, and any id of 6 or more fails the unsigned compare and is rejected as before. `%d0` is dead after this point on this path. |
| `0x4002988a` | 6 B | `movel #0x421b957c,%d1` becomes `jmp 0x400aff50` |
| `0x400aff50` | 16 B | `lea 0x40213bf8,%a0 ; movel %a0@(0,%d0:l:4),%d1 ; jmp 0x40029890`. `%a0` is scratch here, because the prologue saves only `%d2–%d4`. |

✅ upright on the test unit. The POLY machine's code sites are in the ledger
([machine-picker icons](function_ledger.md#machine-picker-icons),
[machine assignment and the pool map](function_ledger.md#machine-assignment-the-pool-map-and-the-audio-isr)),
and its byte runs in [docs/patch_listing.md](../docs/patch_listing.md#poly-icon).

## Where the new data lives

The artwork sits in 1200 B of linker padding at `0x40213b50..0x40214000`.

- The padding follows the C++ static-initialiser array at `0x40213980`: 116 entries, whose last
  entry is `0x40213b4c`.
- That array's walker at `0x40068d76` is count-bounded, so it stops exactly where the padding
  starts.
- The range lies below `0x40214000`, so it is not zeroed at boot.
- ✅ Safe on the test unit. The data was written with nothing pointing at it, and the
  unit behaved exactly as before.

| Address | Size | Contents |
|---|---|---|
| `0x40213b50` | 68 B | robin colour plane |
| `0x40213b94` | 28 B | robin `Bitmap` struct |
| `0x40213bb0` | 44 B | keyboard colour plane |
| `0x40213bdc` | 28 B | keyboard `Bitmap` struct |
| `0x40213bf8` | 8 B | machine-menu bitmap selector table |
| `0x40213c00` | 1024 B | free |

- ⛔ **Ruled out: other uniformly filled ranges that look free but are referenced.**
  - `0x4020fe16..0x4020ffa2` (00-filled): two pointers land inside it.
  - `0x4020ffae..0x402100e8` (00-filled): 44 pointers go to `0x40210000`, a shared blank resource.
  - `0x401d35c0..0x401d37c0` (ff-filled): `0x400d59f4` holds a pointer to its base.
- ⚠️ A dead-space scan alone is not enough. A scan of `0x40000400..0x40214000` found four uniformly
  filled candidates, and three of them were in use. Scan the whole image for words pointing into a
  candidate range before using it.

See [landing_pads.md](landing_pads.md) and [memory_map.md](memory_map.md). The method for surveying
`.rodata` is in [landing_pad_method.md](../../../notes/landing_pad_method.md#surveying-rodata).

## Other display changes

- The RRBN / RRND value text: [features/slice_round_robin.md](features/slice_round_robin.md#value-text).
- The TRK number in the MIDI CHAN cell: [features/midi_loopback.md](features/midi_loopback.md).
