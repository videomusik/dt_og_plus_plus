# Hardware

## What this is

What is inside a Digitakt (original model), read from public teardown photographs of the main board
`CPU4101E` and the UI board `UI4102D`, and from the firmware image itself
(OS 1.52A: [stock_image.md](../os/1.52A/notes/stock_image.md#image-evidence-for-the-hardware-notes)).
A single ColdFire processor runs everything, including all audio; there is no DSP chip. Each part
marking below was transcribed several times independently, and confidence is noted where it is not
high.

## In short

- **One chip runs everything.** A Freescale/NXP ColdFire MCF54415 at 250 MHz reads the keys and
  encoders, drives the screen, runs the sequencer, handles MIDI, USB and storage, and renders all the
  sound. The front panel has no processor of its own.
- **One program to change.** Behaviour lives in the MAIN OS section of the OS image, next to smaller
  sections and the updater (OS 1.52A: section 3, 2.2 MB, next to a small section 2 and the updater;
  [firmware_image.md](firmware_image.md)).
- **The program is readable.** It is C++ compiled with GCC, and many class names survive in it.
- **Nothing is cryptographically locked.** The update file carries only checksums.
  ✅ The device accepts a tool-built image (confirmed on the test unit, OS 1.52A).
- **There is a debug header on the board** (J1, below).
- ⚠️ **Processor time is the likely limit.** One 250 MHz core renders eight voices and does everything
  else. How much time is left over has not been measured.
- **Fixed by the hardware:** the number of audio inputs and outputs, the amount of memory and storage,
  the screen, and the number of keys and encoders.

## Main board `CPU4101E` (©2017)

One board carries the CPU, memory, power, audio, MIDI, USB and all rear connectors. There is no
separate I/O board.

| Ref | Part (marking) | Role | Confidence |
|---|---|---|---|
| U1 | Freescale ColdFire **MCF54415CMJ250**, mask `0N51E` | The only processor: ColdFire V4e core at 250 MHz, 256-MAPBGA, big-endian | high |
| U3 | Nanya **NT5TU128M8HE-AC** | 1 Gbit DDR2-800 ×8 = 128 MB main RAM. MAIN OS loads here (OS 1.52A) | high |
| U8 | Toshiba **THGBMDG5D1LBAIT** | 4 GB eMMC: the +Drive (samples, projects) | high |
| U5 | Spansion `FL128SAIFR1`, S25FL128S family, SO-16W | 128 Mbit (16 MB) SPI NOR boot flash, reached through the DSPI controller at `0xfc05c000` | high on one sharp photo; exact ordering number unread |
| U14 | AKM **AK4621EF** | Stereo codec, 2 ADC + 2 DAC, 24-bit / 192 kHz. The firmware runs it at 48 kHz on SSI0 (read from OS 1.52A section 2: [section2_map.md](../os/1.52A/notes/section2_map.md)) | high |
| U15 | SMSC **3300-EZK** (USB3300) with a FOX 24.000 MHz crystal | ULPI USB 2.0 Hi-Speed PHY for the processor's USB port | high |
| U16 | Fairchild **6N137** | MIDI-in optocoupler | high |
| U17 | TI `AD241` ≈ SN74AHC241PW | Octal buffer: MIDI out/thru (and sync) drivers | medium |
| U100 + T1 | Linear **LT3575FE** + Coilcraft VPH2-0066 | Isolated flyback converter for the ±12 V analog rails | high |
| two SO-16, no ref visible | Silicon Labs **Si8660BA** | 6-channel digital isolators. The audio/codec side is galvanically isolated from the digital side; I²S and control cross here | medium |
| U18 | IDT XLH536-series oscillator, 5 × 3.2 mm | Audio master clock beside the codec. Frequency unreadable; ⚠️ 12.288 MHz is inferred | medium |
| Y1 | metal-lid crystal above U1 | The processor's reference clock. Frequency unreadable | — |
| U13 | SOIC-8 at the `0.9V` test point | ⚠️ DDR2 VTT/VREF regulator (inferred) | unread |
| U101 / U104 / U105 | SOT-223 at the `5V` / `1.8V` / `1.2V` test points | ⚠️ LDOs: 5 V, 1.8 V for the DDR2, 1.2 V core (inferred from placement) | unread |
| QFN + 3 shielded inductors | power corner | Buck converter(s) for the 3.3 V rails | unread |
| U80, U81 + 3 MSOP-8 | Diodes Inc. logo, `GG80` / `604` / `J04` | ⚠️ Analog I/O stage (op-amps or mute switches) | unresolved |
| SOT-223 `GH27G`, `GH15B` | Diodes Inc. logo | Analog-side LDOs | unresolved |
| CM1, D5, R205 | `1206SFF250F/32-2` on R205 | DC input: common-mode choke, reverse/TVS diode, Littelfuse 2.5 A 1206 fuse | high |
| — | PowerStor "Aerogel" supercapacitor, glued flat beside its footprint | ⚠️ Hold-up energy for saving state on power loss, or the clock (role inferred); value unread | high (identity) |
| J1 | 26-pin 2 × 13 header, populated | The standard ColdFire BDM/JTAG debug connector | high |
| J2, J3 | red 2 × 10 IDC | Two 20-way ribbons to the UI board | high |
| K1, K2, K3 | — | DC jack, power switch (ALPS), USB-B | high |
| K7–K11 | ¼" jacks | Main L/R, inputs L/R, headphones | high |

Test points name these rails: `V_IN`, `5V`, `3.3V` (two), `1.8V`, `1.2V`, `0.9V`, `+12V`, `-12V`,
`0V/GND`.

## UI board `UI4102D` (©2016)

- The OLED connects on the 20-pin `CON1`.
- 10 encoders (Alpha, metal D-shaft); the top-left one is a push encoder.
- Kailh mechanical key switches with a centre light pipe, and MiniMELF matrix diodes.
- About seventeen 16-pin TSSOPs and three 20-pin TSSOPs. The one legible 20-pin part is an NXP
  **74HC373** octal latch. ⚠️ The 16-pin parts are 74HC595/165-class shift registers, judged by their
  role.
- No MCU, no FPGA and no crystal on either side. The panel is a latch and shift-register matrix that
  the ColdFire scans over J2/J3.
- ⚠️ `J1` (2 × 3) on this board is probably a test header.

## No DSP chip

No photograph shows a DSP. Section 2 of the OS image, which the firmware tool calls "DSP" (a generic
name for section id 2), is ColdFire machine code, as its first instructions, its inner header and the
memory it references show
(OS 1.52A: [stock_image.md](../os/1.52A/notes/stock_image.md#image-evidence-for-the-hardware-notes)).
What it does in OS 1.52A is in [section2_map.md](../os/1.52A/notes/section2_map.md).

## Address spaces

The chip and the board give the firmware these address windows. They belong to the hardware, not to
one OS version:

- **DDR2 SDRAM**, 128 MB, at `0x40000000..0x48000000`: the main RAM, one chip (U3).
- **On-chip SRAM**, 64 KB in two 32 KB banks, at `0x80000000..0x80010000`.
- **Peripherals**, in the `0xFC0x_xxxx` and `0xEC0x_xxxx` windows.

The ColdFire stack is full-descending (SP = A7, pre-decrement). The firmware uses the standard linker
sections: `.text` (code), `.rodata` (constants, including every string literal), `.data` (initialised
read-write data) and `.bss` (zero-filled read-write data). Where each section of an OS image loads
and runs, and how that version lays out DDR and SRAM, is in its OS folder's memory map
(OS 1.52A: [memory_map.md](../os/1.52A/notes/memory_map.md)).

## Firmware and hardware addresses

| Address | What | Evidence |
|---|---|---|
| `0x4000_0000` | DDR2 SDRAM, 128 MB | see the OS 1.52A image evidence: [stock_image.md](../os/1.52A/notes/stock_image.md#image-evidence-for-the-hardware-notes) |
| `0x8000_0000` | On-chip SRAM, 64 KB in two 32 KB banks (`0x80000000..0x80010000`) | see the OS 1.52A image evidence: [stock_image.md](../os/1.52A/notes/stock_image.md#image-evidence-for-the-hardware-notes) |
| `0xFC00_0000`, `0xEC00_0000` | Peripheral space | see the OS 1.52A image evidence: [stock_image.md](../os/1.52A/notes/stock_image.md#image-evidence-for-the-hardware-notes) |

Peripherals named elsewhere in the notes: DSPI at `0xfc05c000` (the boot NOR flash), eSDHC at
`0xfc0cc000` (the eMMC), SSI0 at `0xFC0B8000` (the codec), and the eDMA channels that stream audio
(OS 1.52A: [render_path.md](../os/1.52A/notes/render_path.md), [section2_map.md](../os/1.52A/notes/section2_map.md)).

Everything is big-endian: all container fields and all code. In the OS 1.52A image both entry-point
headers (MAIN OS and updater) are 16 B, `[entry address][0][0][0]`, with code at +0x10.

**Bus clock.** ⚠️ The firmware assumes an internal bus clock of 132 MHz. The literal `0x07DE2900`
(132,000,000) sits at `0x40002758` in the UART init `FUN_400026f2` (OS 1.52A code),
which computes the divider as `132e6 / (32 × baud)`;
the same literal also appears at `0x40002b5e` and `0x40068c12` (OS 1.52A code).
The RTOS timer and the UI timer both come out at exact rates with it (100 Hz and 30 Hz, in OS 1.52A).
Section 3 of OS 1.52A does not program the PLL, so the value is inferred from how it is used, and it
has not been timed on the device.

## The software

MAIN OS is C++ built with GCC (`__gnu_cxx`, libstdc++ `_Sp_counted_ptr_inplace`; read from OS 1.52A).
Itanium-mangled names survive, for example `Digitakt::trackID_enum`, `machineType_t`, `synthParams_t`,
`MachineListView`, `SoundManager`, `SampleManager`, `MidiRpcFs*` (by its name a MIDI RPC file system,
the way the computer side talks to the unit), `MmcStreamWriter` and `saveProjectToMmc`.

The ColdFire instruction set differs from classic 68k: it has fewer addressing modes and adds the
MAC/EMAC unit with four accumulators. Always use a ColdFire-aware decoder. This project uses:

- **Ghidra** 12.1.3 with the ColdFire language `68000:BE:32:Coldfire`, each section based at the
  address its code runs from
  (OS 1.52A: MAIN OS at `0x40000400`, the updater at `0x80000400` and section 2 at `0x80000ec0`).
  The stock language cannot decode `movclr.l ACCx,Rx`, which ends most EMAC audio loops; the repo's
  language extension (`68000:BE:32:ColdfireEMAC`, in `scripts/ghidra_ext/`) adds it and fixes three
  `mac.l` / `msac.l` forms.
- **`m68k-elf-objdump`** (binutils 2.47) with `-m m68k:cfv4e`, which decodes ColdFire EMAC completely and
  serves as the independent check on anything load-bearing.

See [analysis_method.md](analysis_method.md).

## Debug header J1

J1 is a populated 26-pin BDM/JTAG header. With a BDM probe (for example P&E USB-ML-CF, or a
ColdFire adapter that OpenOCD supports) one can halt the CPU, read and write RAM, dump the SPI boot
flash, and recover from a bad OS flash without the bootloader. ⚠️ This project has not used it. The
recovery routes that have been used are in [flash_recovery.md](flash_recovery.md).

## Unresolved

- The exact ordering number of U5.
- The frequencies of Y1 and U18.
- The small parts next to U13 and U15.
- The `GG80` MSOP-8 parts and the `GH15B` / `GH27G` SOT-223 codes.
- What the section-2 dst `0x03000900` of the OS 1.52A image means.
