# Key scripts for the digiemu check

Key scripts that drive a DT OG++ build for OS 1.54 in digiemu, the Digitakt emulator, and the results
they gave. Run one with the shared runner ([docs/digiemu.md](../../../../docs/digiemu.md)):

    python3 scripts/digiemu_check.py 1.54 out/1.54/<build>.syx --digiemu <digiemu checkout> --script pages
    python3 scripts/digiemu_check.py 1.54 out/1.54/<build>.syx --digiemu <digiemu checkout> --script cfoo4 --timing

The same keys drive the stock build, and every screen and the audio are compared. Nothing here talks to a
device: a result below is what the emulator did, never what a unit does.

## The scripts

| Script | What it does | What should differ from stock |
|---|---|---|
| `pages.txt` | Every parameter page of track 1, FILTER page 2 included; a trig on track 1; the pattern played and stopped | The TRIG page: PORT and LEG where stock has two empty boxes ([portamento](../../notes/features/portamento.md)). FILTER page 2: VED and KEY where stock has two empty boxes ([filter_page2](../../notes/features/filter_page2.md)). Nothing else |
| `cfoo4.txt` | Picks CFOO for tracks 1–4 in the MACHINE menu (FUNC + SRC, DOWN five times, YES, NO), holds the four trigs together, plays the pattern | Each track's SRC page is CFOO's; the audio is CFOO's ([cfo_oscillator](../../notes/features/cfo_oscillator.md)). On stock the same keys pick SLICE, the last machine there, and the tracks stay silent (the emulated +Drive has no factory samples) |
| `cfoo8.txt` | The same for all eight tracks | As `cfoo4.txt` |

The key words (`wait`, `tap`, `press`, `release`, `turn`, `snap`) are digiemu's
([docs/digiemu.md](../../../../docs/digiemu.md#key-scripts)).

## Results on the reference build

The reference build (`.syx` `eca7e91e…`, MAIN OS `459d7595…`, v0.2.6) against stock 1.54 (`f78ba80f…`),
digiemu at commit `f57c2f2` (release 0.4.1) with its six Unicorn patches:

- ✅ emulator run, `pages` (`scripts/digiemu_check.py`, untimed): PASS, every stage of the build passes.
  Every screen equals stock's but the TRIG page (222 pixels in (76, 40)–(119, 63)) and FILTER page 2
  (188 pixels in (75, 13)–(95, 63)), where the table above says; the LFO page equals stock's, as the
  modulation matrix shows only when opened. The audio differs in one 100 ms window, the recording's
  last; ⚠️ very probably the recording's length rather than this build: builds whose screens all equal
  stock's differ in that same window. The builds v0.2.3 (`31b428ea…`) and v0.2.5 (`b8eef30e…`) gave the
  same.
- ✅ emulator run on the build v0.2.3 (`.syx` `31b428ea…`), before KEY on the trig's note and the
  modulation matrix, not repeated since: `cfoo4 --timing` and `cfoo8 --timing`, the audio render
  (vector 191, period 166,685 cycles at the assumed 250 MHz):

  | Build, script | mean | p99 | worst | margin | late passes |
  |---|---|---|---|---|---|
  | stock, `cfoo4` (four SLICE tracks, silent) | 100,569 | 101,487 | 104,323 | 37.4 % | 0 |
  | DT OG++, `cfoo4` (four CFOO tracks) | 106,364 | 115,706 | 119,557 | 28.3 % | 0 |
  | DT OG++, `cfoo8` (eight CFOO tracks) | 108,001 | 129,677 | 133,269 | 20.1 % | 0 |

  The same timing (digiemu's `emu.fwcheck`, without the stock run) for `cfoo4` on v0.2.5 and on v0.2.6,
  whose CFO oscillator renders lighter:

  | Build, script | mean | p99 | worst | margin | late passes |
  |---|---|---|---|---|---|
  | v0.2.5, `cfoo4` | 106,716 | 116,077 | 119,942 | 28.0 % | 0 |
  | v0.2.6, `cfoo4` | 103,959 | 110,342 | 114,537 | 31.3 % | 0 |

  Two runs of `cfoo4` gave the same numbers to the cycle. ⚠️ The cycles are digiemu's estimate from the
  manual's tables (zero-wait memory, no caches): compare builds with them, do not read them as the unit's.
- The `cfoo4` and `cfoo8` runs report two strict-mode violations, "an opcode that is not ColdFire ISA_C" at
  `0x400f787e` and `0x400f788c` (from v0.2.6 at `0x400f7894` and `0x400f789e`). Both are `extb.l %d1` (`0x49c1`) in the CFO oscillator's render. They are
  digiemu's decoder's known false positive ([docs/digiemu.md](../../../../docs/digiemu.md#known-false-positives)),
  and `scripts/digiemu_check.py` reports them as such.
- Stock's boot stage has one violation of its own, a 1-byte read of address `0x00000000`; digiemu marks it
  `in_stock`, so it does not count against a build.

## Limits found with this OS

- The RECORDER page (SAMPLE) does not react to the encoders in digiemu, on stock too: RLEN stays MAX, and
  Chain Recording's knob D cannot be set from a key script.
- The emulated +Drive has no factory samples: the stock kits play silence, so audio comparisons only say
  something for sounds this build makes itself (CFOO) or samples put on the card first (docs/digiemu.md).
