# Values found again in this version

Values that also appear in another OS folder's code or data, each found in this version's own stock
file ([os/README.md](../README.md#values-found-again-in-another-version)).

| Value | What it is in this version | Evidence |
|---|---|---|
| `0x40000400` | MAIN OS (section 3) load address, the container's `dst` | [notes/stock_image.md](notes/stock_image.md#layer-2-the-ele3-container) |
| `0x400004e8` | MAIN OS entry, the first word of its header | [notes/stock_image.md](notes/stock_image.md#layer-2-the-ele3-container) |
| `0x400004b2` | The first function of MAIN OS (`FUN_400004b2`, the `.bss` clear), where the code window starts | [notes/analysis_reference.md](notes/analysis_reference.md#reference-numbers) |
| `0x80000400` | Section 4 (updater) load address, the container's `dst` | [notes/stock_image.md](notes/stock_image.md#layer-2-the-ele3-container) |
| `0x80000492` | Section 4 entry, the first word of its header | [notes/stock_image.md](notes/stock_image.md#layer-2-the-ele3-container) |
| `0x80000eaa` | Word 2 of section 2's inner header: a function start in section 2's code, not its base | [notes/stock_image.md](notes/stock_image.md#section-2s-run-base) |
| `0x400151ac` | The start of the landing-pad span at `0x400151ac` (MIDI Loopback channel hook and tap) | [notes/landing_pads.md](notes/landing_pads.md#the-pads) |
| `0x48780014` | Not an address: the encoding of `pea 0x14`, which the build writes over the stock `pea 0x10` at `0x40022f7e` (the machine-list constructor's vector size) | [docs/patch_listing.md](docs/patch_listing.md#poly-engine) |
| `0x80001228` | The SRAM word the SLICE round robin engine reads the trig bits from | [notes/memory_map.md](notes/memory_map.md#on-chip-sram) |
| `0x80001200` | The start of the 128 B of SRAM around `0x80001228` that the `EmuSliceLatch` harness clears before each case | [notes/memory_map.md](notes/memory_map.md#on-chip-sram) |
| `0x80001a18` | The per-track audio buffers, 32 longs × 8, that `FUN_400757fe` writes and the audio ISR passes to `FUN_40072478` at `0x40077fc2` | [notes/features/cfo_oscillator.md](notes/features/cfo_oscillator.md#where-a-tracks-samples-come-from) |
| `0x80002760` | The engine object, 8 per-track blocks of `0x6a` B, the render stages' first argument | [notes/features/cfo_oscillator.md](notes/features/cfo_oscillator.md#the-values-the-synth-reads) |
| `0x80001f28` | The per-track note, MIDI note × 65536, read by `FUN_40075184`'s rate loop | [notes/features/cfo_oscillator.md](notes/features/cfo_oscillator.md#the-values-the-synth-reads) |
| `0x8000edc4` | The per-voice render state, stride `0x5e`, whose `+0x10` holds the voice level | [notes/features/cfo_oscillator.md](notes/features/cfo_oscillator.md#the-values-the-synth-reads) |
