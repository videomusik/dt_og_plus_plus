# SysEx pattern and project dumps

## What this is

The format of the Digitakt's own over-USB SysEx dump of a project or a pattern: the message
envelope, the 8-in-7 encoding, the two message types, and the firmware code that builds and reads
them. It was worked out by decoding real dumps and cross-checking them against the builder
functions in the firmware. The byte layout of the decoded pattern and kit is in
[pattern_layout.md](pattern_layout.md).

## A project dump

A whole-project dump is **129 separate `F0 … F7` messages**: 128 pattern messages and one
project-meta message. Each message is self-contained; the dump is not block-streamed the way an OS
transfer is.

- 128 pattern messages, type `0x50`, each exactly **31,613 B**, indexed 0–127.
- 1 project-meta message, type `0x54`, **2,941 B**.
- A whole-project capture is therefore 4,049,405 B.
- A capture that keeps only the first message holds only pattern A01 (index 0). Capture all 129
  messages to get the project.

## Message envelope

```
F0  00 20 3C  0A   00   <type>  01 01   <index>  <data ...>  F7
    manuf.    dev  ?    50 / 54  ?      pattern
```

| Bytes | Meaning |
|---|---|
| `00 20 3C` | Elektron's manufacturer id |
| `0A` | The Digitakt |
| `00` | ⚠️ Not decoded |
| `<type>` | `0x50` pattern, `0x54` project meta |
| `01 01` | ⚠️ Not decoded; read as a version |
| `<index>` | The pattern index, `0x00`–`0x7f` across the 128 pattern messages. It is message byte 9 (byte 8 counting from after the `F0`) |

Everything between `F0` and `F7` is 7-bit. The stock MidiRpc protocol uses a different header,
`F0 00 20 3C 10 00` ([architecture.md](architecture.md)).

## 8-in-7 encoding

The data is standard MIDI 8-in-7: each group of 8 sent bytes is one byte of high bits followed by 7
data bytes.

- **The bit order is MSB-first.** Bit 6 of the high-bits byte is the top bit of the first data byte;
  in general bit `6 − i` belongs to data byte `i`.
- **Start the stream at message byte 10**, after the index byte. With that start the version word
  lands at payload `0x0` as `00 00 00 09`, the pattern name at `0x6194` and the kit name at `0x6204`.
  Starting at byte 9 shifts everything by one byte.
- A pattern message decodes to **27,651 B** (`0x6c03`): the `0x6c00` (27,648 B) struct body plus
  3 B of decode remainder.
- The firmware encodes dumps with `FUN_400f4e22` (plain 8-in-7). The MidiRpc encoder
  `FUN_400f4ef0` follows the same MSB-first rule, and a partial final group of `n` bytes is sent as
  `1 + n` bytes.
- ⛔ **Ruled out: LSB-first unpacking.** It corrupts only bit 7 of each byte. The trig bit `0x0200`
  and the ASCII names are unaffected, so the decode still produces the 16 × `0x38f` track structure
  and looks right, while every high byte is wrong. It invents a `7f 7f` form of the p-lock pool. The
  tell is the version word decoding as `00 80 00 09` instead of `00 00 00 09`.

## Pattern message (type `0x50`)

- The size is the same for all 128 messages, so the payload is an **uncompressed, fixed-layout
  struct** (not LZ4), 7-bit packed.
- The body is `0x6c00` B: the version, 16 track blocks of `0x38f` (8 audio, then 8 MIDI), the
  80-lane p-lock pool at `0x38f4`, the name trailer at `0x6194`, and the kit (`0xa00`) at `0x6200`.
  The full map: [pattern_layout.md](pattern_layout.md).
- The decoded bytes are the storage struct's memory image, so the same map is the template for the
  pattern in RAM.
- ✅ The decode was validated on the test unit, against a pattern written on the device
  ([pattern_layout.md](pattern_layout.md#validated-against-a-known-pattern)).

## Project-meta message (type `0x54`)

One message, 2,941 B. Its head is
`00 20 3c 0a 00 54 01 01 00 00 00 00 00 07 38 40 00 00 00 03 …`.

⚠️ Not decoded. It is small and project-level, so it is the main candidate for the sample pool
(the slot → sample-hash table) and the project settings ([open_questions.md](open_questions.md)).

## The firmware side

The device's own dump builder produced these bytes, so the code is the authority for every field.

| Address | Role |
|---|---|
| `DigitaktSysex` at `0x40080868` | The `DigitaktSysex` destructor, not the SysEx front door: the OS-update messages are taken in by the `SysexReceiveMenuView` functions. It is in the update path's protected set, so this build never patches it ([update_moat.md](update_moat.md)) |
| `SysexSendMenuView::vfunc_4` at `0x4005bea0` | The send / build path |
| `Brain::processSysexDumpReceivedMessage` | The receive / decode side. ⚠️ Most likely `FUN_40009182`, the data-dump receiver ([update_moat.md](update_moat.md)); it is not on the OS-update path and not in the protected set |
| `FUN_400811be` | Builds the dump body: `[pattern 0x6200]` + `[kit 0xa00]` |
| `FUN_4007a61a`, `FUN_4007a30e`, `FUN_4007a55e` | The pattern, one track block, and the p-lock pool |
| `FUN_4007a0ac`, `FUN_40079fa6` | The kit and one sound |
| `FUN_400f4e22` | The 8-in-7 encoder for the dump |

The builders feed off the `ValueWithMirror` / `*Storage_vN_t` serializers. The sound serializer
`FUN_40079fa6` is also called by `MmcFs::vfunc_13`, so a sound is the same blob in a dump and on the
eMMC. ⚠️ Whether every other part of the eMMC form matches the dump is not confirmed; the p-lock
pool has a separate eMMC storage class
([pattern_layout.md](pattern_layout.md#two-serialization-paths)).

## Other tools

- **elektron-firmware-tool**, which this build uses for the OS image ([firmware_image.md](../../../notes/firmware_image.md)),
  decodes the OS transport (`F0 … F7`, 8-in-7, checksums) and the firmware containers (ELE3). It does
  not decode data dumps: given one, it reports "not a recognizable Elektron OS .syx". Its transport
  constants are still a useful reference (manufacturer `00 20 3C`, device DIGITAKT `0x0a`).
