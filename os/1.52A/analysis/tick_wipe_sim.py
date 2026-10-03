#!/usr/bin/env python3
"""Simulate the Digitakt's pooled-voice bookkeeping for a MIDI-track chord that retriggers through MIDI Loopback,
using ONLY mechanisms read off the code (the voice-allocation pad, the owner-latch note-off scan, the audio ISR's
tick and its post-loop wipe). See notes/features/tick_wipe_fix.md.

It models the STOCK post-loop wipe, i.e. the defect the tick-wipe fix removes, so it reproduces the drone counts
measured on the test unit on an image without that fix: drones = max(0, 2N - P) for N notes on a pool of P voices.

Usage:  python3 analysis/tick_wipe_sim.py [src|end] [trig|old]

Model (each item cites where it was read; addresses are MAIN OS load addresses, "ISR" = FUN_40077120):
- Region = voices S..S+P-1 (groupSource contiguous). All notes come from one track, event[2] = S (a MIDI track
  with CHAN = TRK3, so S = 2 in 0-based numbering).
- ALLOCATOR (voice allocation, free-first + steal, 0x4015cb8a): d1 = cursor[S]; adv; first = d1; walk the cycle
  taking the first voice with priority == 0; if the walk returns to `first`, steal it (priority[d1] = 0).
  cursor[S] = chosen voice.
- NOTE-ON COMMIT (ISR decompile lines 262/296/315): priority[v] = 2, held[v] = note, ownerTrack[v] = S (owner latch
  write @0x40037796); trigger mask uVar2 |= v.
- NOTE-OFF (owner-latch scan 0x400b2214, v = 7..0): first v with ownerTrack[v]==S && priority[v]==2 && held[v]==note.
  No match -> returns the TRACK; the ISR's own test (line 368) then releases voice S only if it matches.
  Release: priority = 0, held = -1, release mask uVar16 |= v (line 373).
- POST-LOOP WIPE, stock (0x40077a72..0x40077aac, machine-code verified): for every v in uVar16 -> priority = 0,
  held = -1, WITH NO EXCLUSION of voices also in uVar2 (only the LEN countdown term is masked with ~uVar2).
- ENGINE: a voice in uVar2 sounds its newly triggered note; a voice only in uVar16 stops. A voice in BOTH keeps
  sounding (the drone heard on the device says the trigger wins). A voice sounding while booked free is a ZOMBIE:
  no note-off can match it; it stops only when a note-on re-triggers it, or on All Sound Off.
- A retrigger burst arrives as Off X1, On X1, Off X2, On X2, ... (wire order, as logged by a MIDI monitor) and,
  through the zero-spacing internal Loopback, is processed within ONE ISR tick. [STOP] sends Off for every note,
  one tick.
- After [STOP] + Start the first note lands on the Source (observed on the test unit: allocation starts
  deterministically from the Source voice). That is START = "end" below; the default START = "src" (first note
  on S+1) is the state after a project load. The drone counts are the same in both modes.
"""
S = 2


import sys
# START = "src": cursor = S, so the first note lands on S+1 (what the pool cursor gives after a project load:
#                groupCursor[S] holds the LAST voice used and is seeded to S)
# START = "end": cursor = the region's last voice, so the first note lands ON the Source (what is heard on the
#                test unit after [STOP])
START = sys.argv[1] if len(sys.argv) > 1 else "src"
# ENGINE rule for a voice that is BOTH released and triggered in one tick (in uVar16 AND uVar2):
#   "trig" = the new note sounds;  "old" = the voice keeps sounding its previous note (the new one never starts)
RULE = sys.argv[2] if len(sys.argv) > 2 else "trig"


class Dev:
    def __init__(self, P):
        self.P = P
        self.region = list(range(S, S + P))
        self.prio = [0] * 8
        self.held = [-1] * 8
        self.owner = [None] * 8
        self.sound = [None] * 8          # engine: note sounding on each voice
        self.cursor = S if START == "src" else S + P - 1

    def adv(self, v):
        v += 1
        return S if (v == 8 or v not in self.region) else v

    def alloc(self):
        d1 = self.adv(self.cursor)
        first = d1
        while True:
            if self.prio[d1] == 0:
                break
            d1 = self.adv(d1)
            if d1 == first:
                self.prio[d1] = 0        # the steal (clrl priority[d1])
                break
        self.cursor = d1
        return d1

    def scan(self, note):
        for v in range(7, -1, -1):
            if self.owner[v] == S and self.prio[v] == 2 and self.held[v] == note:
                return v
        return S                          # fallback: the track

    def tick(self, events):
        trig, rel = {}, set()
        for kind, note in events:
            if kind == "on":
                v = self.alloc()
                self.prio[v], self.held[v], self.owner[v] = 2, note, S
                trig[v] = note
            else:
                v = self.scan(note)
                if self.prio[v] == 2 and self.held[v] == note:   # ISR line 368
                    self.prio[v], self.held[v] = 0, -1
                    rel.add(v)
        for v in rel:                     # stock post-loop wipe, no ~uVar2 exclusion
            self.prio[v], self.held[v] = 0, -1
        for v in rel:
            if v not in trig:
                self.sound[v] = None
        for v, n in trig.items():
            if v in rel and RULE == "old" and self.sound[v] is not None:
                continue                  # released + triggered in one tick: keeps its previous note
            self.sound[v] = n

    def sounding(self):
        return {v: n for v, n in enumerate(self.sound) if n is not None}

    def zombies(self):
        return {v: n for v, n in enumerate(self.sound) if n is not None and self.prio[v] == 0}


def retrig_burst(notes):
    ev = []
    for n in notes:
        ev += [("off", n), ("on", n)]
    return ev


def run(notes, P, k):
    d = Dev(P)
    d.tick([("on", n) for n in notes])
    history = []
    for _ in range(k):
        d.tick(retrig_burst(notes))
        s = d.sounding()
        doubled = sorted({n for n in s.values() if list(s.values()).count(n) > 1})
        history.append((dict(s), dict(d.zombies()), doubled))
    d.tick([("off", n) for n in notes])            # [STOP]
    return d, history


NAMES = {60: "C3", 63: "D#3", 67: "G3"}
def nm(n): return NAMES.get(n, str(n))

for notes in ([60, 63], [60, 63, 67]):
    N = len(notes)
    print(f"\n==== {N} notes {[nm(n) for n in notes]} ====")
    print(" drones after ONE retrigger + [STOP]  (device table: max(0, 2N-P))")
    for P in range(N, 7):
        d, _ = run(notes, P, 1)
        left = d.sounding()
        print(f"   P={P}: {len(left)} voice(s) left sounding {sorted((v, nm(n)) for v, n in left.items())}"
              f"   predicted-by-formula {max(0, 2 * N - P)}")

    for P in range(N + 1, 2 * N):
        print(f"\n  -- P={P} (N < P < 2N): per-retrigger state, cursor from the Source --")
        for k in range(1, 7):
            d, hist = run(notes, P, k)
            s, z, dbl = hist[-1]
            left = sorted(nm(n) for n in d.sounding().values())
            print(f"   after {k} retrig: sounding {sorted((v, nm(n)) for v, n in s.items())}"
                  f"  zombies {sorted((v, nm(n)) for v, n in z.items())}"
                  f"  doubled(phasing) {[nm(n) for n in dbl]}   [STOP] leaves {left}")
