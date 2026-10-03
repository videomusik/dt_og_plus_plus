# Start-up hooks: OS 1.52A

Why start-up code matters, and the recovery ladder: [flash_recovery.md](../../../notes/flash_recovery.md).

## This build runs code at startup

⛔ **Ruled out: "the patches cannot affect boot".** These hooks in this build run on the startup path:

| Hook | Pad | Feature | Why it runs at startup |
|---|---|---|---|
| `0x4002b012` | `0x400afe80` | POLY voice pool ([ledger](function_ledger.md#src-page-1-layout-and-the-machine-a-page-shows), [patch listing](../docs/patch_listing.md#poly-ui)): a follower reports its Source's machine | in the machine-type query `FUN_4002af58`, which runs as the screen first draws |
| `0x40030d44` | `0x400afe46` | POLY voice pool: parameter pages read the Source's values | the tail jump of the parameter-set lookup at `0x40030d22`, reached from the value read of every parameter-page draw |
| `0x40039f16` | `0x400afe80` | POLY voice pool: SRC page 1 uses the Source's layout | in the SRC page's layout read, part of the page draw |
| `0x400c47ce` | `0x40015186` | [MIDI Loopback](features/midi_loopback.md): the private MIDI lane's dispatch | in the MIDI-input task `FUN_400c465e`, which starts at boot |

✅ The hook sites at `0x40030d44` and `0x4002b012` each ran on the test unit as an inert
pass-through. `0x40039f16` reuses the pad proven at `0x4002b012`, with the same frame shape at both
sites. ✅ Each of the four has started normally on the test unit.

✅ A patched instruction also runs at every boot (read directly in the code, objdump): the `lea` at
`0x4013b914` in the parameter-record builder `FUN_4013b6a2`, a C++ static initialiser that the init
task's `.init_array` walker at `0x40068d76` calls. It only stores the address of SLICE round robin's
value formatter `0x400b2410`, which runs when a Slice Select value is drawn.

Also possibly at startup:

- ⚠️ **Draw-path hooks**, which run at the first paint if the unit restores that page:
  - MIDI Loopback's CHAN/TRK labels: the grid-cell short name at `0x40030466`, in the parameter-grid
    draw, which runs at first paint, and the encoder popup's long name at `0x40032816`; also the value
    formatter at `0x40065652`;
  - the [pool cursors](features/pool_cursors.md) at `0x400ae7f4` and `0x400aeafa`, at the end of the SRC
    page-2 waveform draw;
  - the TRK number drawer at `0x40062fde`, on a MIDI track's SRC page;
  - [SLICE round robin](features/slice_round_robin.md)'s Slice Select cell drawer at `0x40065264` (→
    the robin selector at `0x400aff34`) and its value formatter at `0x400b2410`, on a SLICE track's SRC
    page.
- ⚠️ **Project-load code.** The Digitakt loads a project when it starts, so this runs then too
  (inference from that): the POLY pool-map build on kit load (`0x4007705a` → `0x400b026e`), the POLY
  pool-map refresh in the audio ISR's set-active-kit handler (`0x400773e6` → `0x40037734`) and the kit
  loader's machine-range check at `0x4007a994`.
