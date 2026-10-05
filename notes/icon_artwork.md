# Icon artwork

## What this is

DT OG++ draws two new 1-bit images: a robin for the SLICE values RRBN and RRND, and a small
keyboard for POLY in the machine menu. The CFO oscillator's test images add a third, a placeholder
for the machine CFOO. They are the project's own artwork and do not depend on the OS version. This
note gives the pixel grids, the house style they follow, and how to check an encoding of them.

Each grid has a 1-bit master image in [assets/icons/](../assets/icons/), generated from the grid at
its native size. Each is a greyscale PNG with one bit per pixel, where a white pixel is a `#` in the
grid: [assets/icons/robin_17x17.png](../assets/icons/robin_17x17.png),
[assets/icons/poly_keyboard_11x7.png](../assets/icons/poly_keyboard_11x7.png) and
[assets/icons/cfoo_saw_11x7.png](../assets/icons/cfoo_saw_11x7.png). The grids remain the
source of truth, and each master decodes back to its grid pixel for pixel. The images are opaque
tiles, as the house style asks: every pixel is drawn, ink or not, so the master is the whole image.

How they are encoded and placed in an OS image: that OS folder's note
`os/<os>/notes/visual_assets.md` (OS 1.52A: [os/1.52A/notes/visual_assets.md](../os/1.52A/notes/visual_assets.md)).

## The robin

RRBN is short for round robin, hence a robin. The tile is 17 × 17 and strictly 1-bit, with 97 ink
pixels. The artwork fills columns 1–15 and rows 1–15, a 15 × 15 area, and the 1 px border ring is
clear on all four sides. `#` is ink:

```
    col 0         1
        01234567890123456
row  0  .................
     1  ....####.........
     2  ...######........
     3  ...######........
     4  .########........    beak, pointing left
     5  ...#######.......
     6  ..#########......
     7  ..##..######.....
     8  ..#...#######....
     9  ..#....######....
    10  ..#.....######...
    11  ...#........###..    tail begins
    12  ....##....######.
    13  .....#######..##.
    14  .....#....#......    legs
    15  ....##...##......    feet
    16  .................
```

Master image: [assets/icons/robin_17x17.png](../assets/icons/robin_17x17.png) (17 × 17, 1-bit).

## The keyboard

The icon is 11 × 7 and 1-bit, with 47 ink pixels: three white keys, with two black keys carved into
the top four rows. It uses the same motif as the stock NOTE tile, since POLY means many notes at
once. It is deliberately vertically asymmetric, so a flip would show.

```
    col 0         1
        01234567890
row  0  ##...#...##
     1  ##...#...##
     2  ##...#...##
     3  ##...#...##
     4  ###.###.###
     5  ###.###.###
     6  ###.###.###
```

Master image: [assets/icons/poly_keyboard_11x7.png](../assets/icons/poly_keyboard_11x7.png)
(11 × 7, 1-bit).

## The CFOO placeholder

A stand-in for CFOO, the CFO oscillator machine, until it has its own artwork. It is 11 × 7 and 1-bit,
with 52 ink pixels: two filled sawtooth ramps, the waveform of an oscillator, rising to the right. It
is asymmetric both ways, so a flip would show. Its single-pixel tips at the top fall short of the
house style's "no 1-pixel detail".

```
    col 0         1
        01234567890
row  0  ....#.....#
     1  ...##....##
     2  ..###...###
     3  .####..####
     4  #####.#####
     5  ###########
     6  ###########
```

Master image: [assets/icons/cfoo_saw_11x7.png](../assets/icons/cfoo_saw_11x7.png) (11 × 7, 1-bit).

## House style

The stock NOTE tile, a picture the OS draws in a parameter's cell, is the style reference. In OS 1.52A it
is a 17 × 17 opaque tile showing a piano keyboard, black keys at the top, and its artwork is 11 × 11,
centred.

The house style it shows: solid filled forms, a margin of about 3 px, no 1-pixel detail, and an
opaque tile.

The size of each image is set by where the OS draws it.
OS 1.52A: 17 × 17 for a parameter cell, 11 × 7 for a machine-menu icon ([Two asset classes](../os/1.52A/notes/visual_assets.md#two-asset-classes)).

## Checking an encoding

The bit order of rows is easy to get upside down, and a picture alone rarely shows it.

- ⚠️ **Encode each new asset from its grid, then decode the bytes back and compare.** Encode the grid
  under the format's row-order rule, decode the bytes with the same rule, and compare the result with
  the grid pixel for pixel. Decode the master PNG too and compare it with the grid. The ink counts
  (97 for the robin, 47 for the keyboard, 52 for the CFOO placeholder) are a quick cross-check.
- ⚠️ **Text is the reliable orientation control.** A picture exposes a flip only if you have pinned
  beforehand which way up it must be; a bird or a keyboard looks plausible either way. A vertically
  symmetric image cannot reveal a flip at all; the keyboard is deliberately asymmetric for this
  reason. To settle a format's row order, decode stock images that contain text: they read
  correctly only under the right order.

The row-order rule, the checks that settle it and the encoded words of both images are in the same
note, `os/<os>/notes/visual_assets.md`
(OS 1.52A: [os/1.52A/notes/visual_assets.md](../os/1.52A/notes/visual_assets.md#the-bitmap-format)).
