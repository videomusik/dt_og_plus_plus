// cfo_oscillator: run the CFO oscillator's code in Ghidra's p-code emulator over many audio ticks and
// compare every output sample with a model of the same integer arithmetic, written here from the
// oscillator's description (three phase accumulators, FM into OSC1, wave morph, mix, level).
//
// The code runs from cfo_pad, called as the audio ISR calls the stock level stage FUN_40072478:
// (a18, engine) on the stack. A stub at FUN_40072478 ends each tick. Per tick the harness checks:
//   - tracks that are not ONESHOT, or have a sample, keep their a18 block exactly;
//   - a synth track's 32 samples equal the model's, tick after tick (the phases persist);
//   - FUN_40072478 is reached with the stack as cfo_pad found it, and %d2-%d7/%a2-%a6 intact.
// And over a run of ticks: a pure SIN on OSC1 at note 60 has the expected period, and its peak level.
// The level: each synth track calls the stock FUN_40074c60(x, LEV) once, with its own x (the word at
// 0x80001f18 + 2 x track) and its LEV (engine +0x42); the call is stubbed and returns the case's level,
// because the emulator's EMAC has no fractional mode. The voice's own level (+0x10) holds a wrong value
// throughout, as after the lanes' fade at a sample's end, and must not be used. A voice that is on
// (+0x28) with a trig next tick (bit in 0x8000122c) gets level 0, the lanes' de-click.
//
// The model reads the stock pitch table (0x4019b4c0) and the wavetables from the emulator's memory, so
// a wrong table address shows as a wrong pitch or shape, not as agreement.
//
// Arguments: a load file, one line per section ('<hex addr> <hex bytes>') and 'sym <name> <hex addr>'
// lines (the code may sit anywhere the emulator can write), and 'machine5' for a build whose synth
// plays on machine 5 (CFOO) instead of a ONESHOT track with SAMP OFF. In that mode each case's synth
// tracks are set to machine 5, and one more case checks that ONESHOT with SAMP OFF is left alone.
// With 'names' as well (a load file from the NAMES variant, which carries the build's two label pads),
// the label pads are called for parameter ids 100..120 on pages showing machines 0, 3, 4 and 5, with the
// page's machine query (FUN_4002b5d4) stubbed: CFOO's names must come back only for ids 108..115 on a
// machine-5 page, the stock names (through the real stock accessors) everywhere else, and MIDI
// Loopback's TRK label must still work.
// With 'icon' as well (a load file from the ICON variant, which carries the build's group mapper, the
// stock icon routine and the icon pad as that stage has them), the MACHINE menu's icon routine
// (0x40029e9c) runs for machines 0..6 with the list item's machine stubbed: the draw must get SLICE's
// icon for 3, POLY's for 4, CFOO's for 5, a stock icon for 0..2, and no draw for 6.
// With 'slots' as well, or a load carrying the SLOTS hook (sym slot_machine), FUN_40078f44 runs for the
// SRC slots on machines 0..7 with its run-time table seeded: machine 5 must get ONESHOT's ids.
// A load carrying set_machine (S19), or the argument 'realsets', runs the range, display and edit cases
// on a sound's parameter set as the firmware lays it out (stock vtable, holder, sound), with the stock
// FUN_4002200a and parameter-set code unstubbed; only the value write and its change notice are stubbed.
//   ./scripts/ghidra_emu.sh 1.54 EmuCfoOscillator <file.load> [machine5 [names [icon [slots]]]] [realsets]
// Runs in Ghidra's emulator only; nothing touches a device. @category dt_og_plus_plus
import ghidra.app.script.GhidraScript;
import ghidra.app.emulator.EmulatorHelper;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class EmuCfoOscillator extends GhidraScript {
  static final long FILL = 0x40072478L, MACH = 0x4199f466L, NOTES = 0x80001f28L, VOICES = 0x8000edc4L;
  static final long PITCH = 0x4019b4c0L, PHASES = 0x439d1100L, WAVES = 0x40252724L, MIXPTS = 0x40252b24L;
  static final long A18 = 0x80001a18L, ENGINE = 0x80002760L, RET = 0x40001000L, SP0 = 0x40258600L;
  // the level: the stock FUN_40074c60(x, LEV) is stubbed (the emulator's EMAC has no fractional mode),
  // x per track at VELS, the voice-on flag at VOICES + 0x28, the lanes' next-tick trig mask at TRIGS
  static final long LEVEL = 0x40074c60L, VELS = 0x80001f18L, TRIGS = 0x8000122cL;
  static final int X0 = 0x6400, LEVW = 0x5a00, STALE = 0x01234567;
  static final long LEVELS = 0x439d1160L;	// the level each track ended its last tick on (8 longs)

  List<long[]> secAddr = new ArrayList<>();
  List<byte[]> secData = new ArrayList<>();
  Map<String, Long> sym = new HashMap<>();
  EmulatorHelper emu;
  int fails = 0;

  void wr(long a, long v, int n) throws Exception {
    byte[] b = new byte[n]; for (int i = 0; i < n; i++) b[n - 1 - i] = (byte) ((v >> (8 * i)) & 0xff);
    emu.writeMemory(toAddr(a), b);
  }
  long rdn(long a, int n) throws Exception {
    byte[] b = emu.readMemory(toAddr(a), n); long v = 0; for (int i = 0; i < n; i++) v = (v << 8) | (b[i] & 0xff); return v;
  }
  int rd32s(long a) throws Exception { return (int) rdn(a, 4); }
  long rd(String r) throws Exception { return emu.readRegister(r).longValue() & 0xffffffffL; }

  // ---- the model
  int[] pitchTab = new int[14849];
  byte[] waves = new byte[1024];
  short[] mix = new short[12];
  int[][] ph = new int[8][3];

  int pitch(int ns) {
    if (ns < 0) ns = 0;
    if (ns > 0x570000) ns = 0x570000;
    int idx = ns / 384;
    return (pitchTab[idx] >>> 13) * 357;
  }
  // S20 (sym segfrac): the four waves and the four mixes exactly at 0, 42, 85, 127, linear between, the
  // fraction rounded to 1/256: segment s, round((v - P[s]) x 256 / (P[s+1] - P[s])). Before: 3v / 128.
  boolean pure = false;
  static final int[] PURE_PTS = {0, 42, 85, 127};
  static int[] segfrac(int v) {
    int s = v >= 85 ? 2 : v >= 42 ? 1 : 0;
    return new int[] {s, (int) Math.floor((v - PURE_PTS[s]) * 256.0 / (PURE_PTS[s + 1] - PURE_PTS[s]) + 0.5)};
  }
  int[] wave(int w) {
    if (pure) { int[] sf = segfrac(w); return new int[] {sf[0] * 256, (sf[0] + 1) * 256, sf[1]}; }
    int p = 3 * w; return new int[] {(p >> 7) * 256, ((p >> 7) + 1) * 256, (p & 127) * 2};
  }
  int samp(int[] wv, int acc) { int i = acc >>> 24; int a = waves[wv[0] + i], b = waves[wv[1] + i]; return a + (((b - a) * wv[2]) >> 8); }
  int[] gains(int v) {
    int p = 3 * v, s = p >> 7, fr = (p & 127) * 2; int[] g = new int[3];
    if (pure) { int[] sf = segfrac(v); s = sf[0]; fr = sf[1]; }
    for (int k = 0; k < 3; k++) { int a = mix[s * 3 + k], b = mix[s * 3 + 3 + k]; g[k] = a + (((b - a) * fr) >> 8); }
    return g;
  }
  // block fields: tune (8.8), play, br, strt, len, loop (integer parts); note: MIDI note; level Q31
  int[] model(int t, int note, int tune, int play, int br, int strt, int len, int loop, int level) {
    int ns = (note << 16) + ((tune - 0x4000) << 8) + 0x30000;
    int st1 = pitch(ns), st2 = pitch(ns - 0xc0000), st3 = pitch(ns + 0x130000);
    int fms = (st1 >>> 13) * len;
    int m2 = (Integer.compareUnsigned(play, 2) < 0) ? -1 : 0, m3 = (Integer.compareUnsigned(play - 1, 2) < 0) ? -1 : 0;
    int[] g = gains(br);
    int now = level >>> 16, last = mprev[t];	// the level ramps from last tick's to this tick's
    if (Integer.compareUnsigned(last, 0x7fff) > 0) last = now;	// not a level: no ramp
    mprev[t] = now;
    int cur = last << 5, step = now - last;
    int[] w1 = wave(strt), w23 = wave(loop);
    int[] s2 = new int[32], s3 = new int[32], out = new int[32];
    for (int i = 0; i < 32; i++) { ph[t][1] += st2; s2[i] = samp(w23, ph[t][1]); }
    for (int i = 0; i < 32; i++) { ph[t][2] += st3; s3[i] = samp(w23, ph[t][2]); }
    for (int i = 0; i < 32; i++) {
      int fm = (s2[i] & m2) + (s3[i] & m3);
      ph[t][0] += st1 + fm * fms;
      int s1 = samp(w1, ph[t][0]);
      cur += step;
      out[i] = (s1 * g[0] + s2[i] * g[1] + s3[i] * g[2]) * (cur >> 5);
    }
    return out;
  }

  // knobs mode (S16): A OSC1 wave (4..88), B FM source (0 off, 1 OSC2, 2 OSC2+3, 3 OSC3), C OSC2 wave,
  // D OSC3 wave, E mix (0..120), F FM amount, G OSC2 detune (-48..-1 st, 48 unison, +1..+50 cents),
  // H OSC3 detune (-50..-1 cents, 50 unison, +1..+48 st); written from the knob table, not the code.
  static int detLo(int g) { g = Math.min(g, 98); int d = g - 48; return d < 0 ? d << 16 : d * 655; }
  static int detHi(int h) { h = Math.min(h, 98); int d = h - 50; return d < 0 ? d * 655 : d << 16; }
  int[] modelK(int t, int note, int[] k, int level) {
    int ns = (note << 16) + 0x30000;
    int st1 = pitch(ns), st2 = pitch(ns + detLo(k[6])), st3 = pitch(ns + detHi(k[7]));
    int fms = (st1 >>> 13) * k[5];
    int m2 = Integer.compareUnsigned(k[1] - 1, 2) < 0 ? -1 : 0, m3 = Integer.compareUnsigned(k[1] - 2, 2) < 0 ? -1 : 0;
    int e = Math.min(k[4] + (k[4] >> 4), 127);
    int[] g = gains(e);
    int now = level >>> 16, last = mprev[t];
    if (Integer.compareUnsigned(last, 0x7fff) > 0) last = now;
    mprev[t] = now;
    int cur = last << 5, step = now - last;
    int[] w1 = wave((Math.max(k[0] - 4, 0) * 3) >> 1), w2 = wave(k[2]), w3 = wave(k[3]);
    int[] s2 = new int[32], s3 = new int[32], out = new int[32];
    for (int i = 0; i < 32; i++) { ph[t][1] += st2; s2[i] = samp(w2, ph[t][1]); }
    for (int i = 0; i < 32; i++) { ph[t][2] += st3; s3[i] = samp(w3, ph[t][2]); }
    for (int i = 0; i < 32; i++) {
      int fm = (s2[i] & m2) + (s3[i] & m3);
      ph[t][0] += st1 + fm * fms;
      int s1 = samp(w1, ph[t][0]);
      cur += step;
      out[i] = (s1 * g[0] + s2[i] * g[1] + s3[i] * g[2]) * (cur >> 5);
    }
    return out;
  }

  // knobs mode, S18 (sym cfo_encobj): K holds the raw 8.8 slot values. A, C, D the waves 0..127; B the
  // FM source 0 OSC2, 1 OSC2+3, 2 OSC3 (above 2 as 2); E the mix 0..127; F the FM amount 0..127; G, H the
  // detunes, 40.0..88.0 = -24..+24 semitones around 64.0, fraction included; above the stock pitch
  // table's top (note sum 0x570000) the pitch is taken down by octaves and the step doubled back, at
  // most 0x7fffffff. Written from the agreed knob table, not from the code.
  boolean v2 = false;
  int pitch2(int ns) {
    if (ns < 0) ns = 0;
    int k = 0;
    while (ns > 0x570000) { ns -= 0xc0000; k++; }
    long st = ((long) (pitchTab[ns / 384] >>> 13)) * 357;
    for (; k > 0; k--) st = st >= 0x40000000L ? 0x7fffffffL : st * 2;
    return (int) st;
  }
  static int det24(int v) { v = Math.max(0x2800, Math.min(v & 0xffff, 0x5800)); return (v - 0x4000) << 8; }
  static int byte8(int v) { return (v >> 8) & 0xff; }
  int[] modelK2(int t, int note, int[] k, int level) {
    int ns = (note << 16) + 0x30000;
    int st1 = pitch2(ns), st2 = pitch2(ns + det24(k[6])), st3 = pitch2(ns + det24(k[7]));
    int fms = (st1 >>> 13) * byte8(k[5]);
    int b = Math.min(byte8(k[1]), 2);
    int m2 = b <= 1 ? -1 : 0, m3 = b >= 1 ? -1 : 0;
    int[] g = gains(Math.min(byte8(k[4]), 127));
    int now = level >>> 16, last = mprev[t];
    if (Integer.compareUnsigned(last, 0x7fff) > 0) last = now;
    mprev[t] = now;
    int cur = last << 5, step = now - last;
    int[] w1 = wave(Math.min(byte8(k[0]), 127)), w2 = wave(Math.min(byte8(k[2]), 127)), w3 = wave(Math.min(byte8(k[3]), 127));
    int[] s2 = new int[32], s3 = new int[32], out = new int[32];
    for (int i = 0; i < 32; i++) { ph[t][1] += st2; s2[i] = samp(w2, ph[t][1]); }
    for (int i = 0; i < 32; i++) { ph[t][2] += st3; s3[i] = samp(w3, ph[t][2]); }
    for (int i = 0; i < 32; i++) {
      int fm = (s2[i] & m2) + (s3[i] & m3);
      ph[t][0] += st1 + fm * fms;
      int s1 = samp(w1, ph[t][0]);
      cur += step;
      out[i] = (s1 * g[0] + s2[i] * g[1] + s3[i] * g[2]) * (cur >> 5);
    }
    return out;
  }
  void knobCases2(int[] none, int[] noSlot, int[] zero, int[] c4, int[] notes, int MAX) throws Exception {
    int U = 0x4000;
    int[] def = {0, 0, 0, 0, 0, 0, U, U};
    runK("S18 knobs: no synth track, every block untouched", 3, none, noSlot, c4, def);
    runK("S18 knobs: defaults (OSC1 SIN alone), all 8 tracks", 40, zero, zero, notes, def);
    runK("S18 knobs: A 42, C 64, D 127 (waves)", 20, zero, zero, notes, new int[] {0x2a00, 0, 0x4000, 0x7f00, 0x5500, 0, U, U});
    runK("S18 knobs: A 127, mix E 127 (OSC2+3)", 20, zero, zero, notes, new int[] {0x7f00, 0, 0x2000, 0x6000, 0x7f00, 0, U, U});
    runK("S18 knobs: mix E 42, 85", 20, zero, zero, notes, new int[] {0, 0, 0x5000, 0x2000, 0x2a00, 0, U, U});
    runK("S18 knobs: FM B 0 (OSC2), F 60", 30, zero, zero, notes, new int[] {0, 0, 0, 0, 0, 0x3c00, 0x3400, U});
    runK("S18 knobs: FM B 1 (OSC2+3), F 127", 30, zero, zero, notes, new int[] {0x2000, 0x100, 0x1400, 0x4600, 0x3c00, 0x7f00, 0x3800, 0x4700});
    runK("S18 knobs: FM B 2 (OSC3), F 90", 30, zero, zero, notes, new int[] {0, 0x200, 0, 0, 0, 0x5a00, U, 0x4c00});
    runK("S18 knobs: FM B 3 (above the range: as 2)", 20, zero, zero, notes, new int[] {0, 0x300, 0, 0, 0, 0x5a00, U, 0x4c00});
    runK("S18 knobs: G -24 st, H +24 st", 20, zero, zero, notes, new int[] {0, 0, 0, 0, 0x5500, 0, 0x2800, 0x5800});
    runK("S18 knobs: G -0.5 st, H +7.25 st (fractions)", 20, zero, zero, notes, new int[] {0, 0, 0, 0, 0x5500, 0, 0x3f80, 0x4740});
    runK("S18 knobs: G, H out of range (clamp to -24, +24)", 20, zero, zero, notes, new int[] {0, 0, 0, 0, 0x5500, 0, 0x1000, 0x7000});
    runK("S18 knobs: A, C, D, E, F above 127 (clamp)", 10, zero, zero, notes, new int[] {0xc000, 0x200, 0xff00, 0x9000, 0xa000, 0x7f00, U, U});
    runK("S18 knobs: high notes, +24 st (pitch above the table's top)", 20, zero, zero,
         new int[] {84, 96, 108, 120, 127, 100, 90, 85}, new int[] {0, 0x100, 0x4000, 0x7f00, 0x5500, 0x7f00, 0x5800, 0x5800});
    runK("S18 knobs: extreme notes", 10, zero, zero, new int[] {0, 127, 0, 127, 0, 127, 0, 127}, new int[] {0x7f00, 0x200, 0x7f00, 0x7f00, 0x3c00, 0x7f00, 0x2800, 0x5800});
    K = def;
    activeMask = 0xff; trigMask = 0x0a;
    run("S18 knobs: de-click", 5, zero, zero, notes, 0, 0, 0, 0, 0, 0, MAX);
    activeMask = 0; trigMask = 0;
    seedLevels = 0xdeadbeefL;
    run("S18 knobs: level RAM not set at boot", 3, zero, zero, notes, 0, 0, 0, 0, 0, 0, MAX);
    seedLevels = 0;
    run("S18 knobs: half level", 10, zero, zero, notes, 0, 0, 0, 0, 0, 0, 0x40000000);
    // the pitch extension alone: pitch() against pitch2() for note sums across the whole range
    StringBuilder bad = new StringBuilder();
    for (int ns = -0x10000; ns <= 0xa00000 && bad.length() < 300; ns += 0x1357) {
      long sp = SP0 - 4;
      wr(sp, RET, 4);
      emu.writeRegister("SP", sp); emu.writeRegister("PC", sym.get("pitch")); emu.writeRegister("D0", ns & 0xffffffffL);
      boolean done = false;
      for (int s = 0; s < 200; s++) {
        if (emu.getExecutionAddress().getOffset() == RET) { done = true; break; }
        if (!emu.step(monitor)) break;
      }
      if (!done || (int) rd("D0") != pitch2(ns)) bad.append(String.format(" [ns 0x%x: 0x%x, want 0x%x]", ns, (int) rd("D0"), pitch2(ns)));
    }
    println(String.format("  %-64s %s%s", "S18 knobs: pitch above the table's top, note sums 0..0xa00000", bad.length() == 0 ? "OK" : "**FAIL**", bad));
    if (bad.length() != 0) fails++;
  }

  // ---- one case: several ticks with fixed settings
  static final String[] KEEP = {"D2","D3","D4","D5","D6","D7","A2","A3","A4","A5","A6"};

  int[][] lastOut = new int[8][];
  boolean m5 = false, remap = true;
  int maxSteps = 0;
  boolean slots = false;			// run slotCases even without the hook (a control)
  int[] K = null;			// knobs mode (S16): A..H as written to the SRC slots, or null
  int activeMask = 0, trigMask = 0;	// voices on, and voices with a trig next tick
  int[] mprev = new int[8];		// the model's last level per track
  long seedLevels = 0;			// what fresh() puts in the level RAM

  void fresh() throws Exception {
    if (emu != null) emu.dispose();
    emu = new EmulatorHelper(currentProgram);
    for (int k = 0; k < secAddr.size(); k++) emu.writeMemory(toAddr(secAddr.get(k)[0]), secData.get(k));
    emu.writeMemory(toAddr(SP0 - 0x800), new byte[0x1000]);
    emu.writeMemory(toAddr(ENGINE), new byte[8 * 0x6a]);
    emu.writeMemory(toAddr(PHASES), new byte[96]);
    for (int t = 0; t < 8; t++) for (int k = 0; k < 3; k++) ph[t][k] = 0;
    for (int t = 0; t < 8; t++) { wr(LEVELS + 4 * t, seedLevels, 4); mprev[t] = (int) seedLevels; }
  }

  /** machine/sample per track, and the synth settings (same for every synth track, note per track). */
  boolean tick(String label, int[] machine, int[] slot, int[] note, int tune, int play, int br, int strt,
               int len, int loop, int level, boolean report) throws Exception {
    StringBuilder bad = new StringBuilder();
    int[] eff = new int[8];
    for (int t = 0; t < 8; t++) eff[t] = (m5 && remap && machine[t] == 0 && slot[t] == 0) ? 5 : machine[t];
    for (int t = 0; t < 8; t++) {
      wr(MACH + t, eff[t], 1);
      long e = ENGINE + t * 0x6a;
      wr(e + 0x34, tune, 2); wr(e + 0x36, play << 8, 2); wr(e + 0x38, br << 8, 2); wr(e + 0x3a, slot[t] << 8, 2);
      wr(e + 0x3c, strt << 8, 2); wr(e + 0x3e, len << 8, 2); wr(e + 0x40, loop << 8, 2);
      if (K != null) for (int j = 0; j < 8; j++) wr(e + 0x34 + 2 * j, v2 ? K[j] & 0xffff : (K[j] & 0xff) << 8, 2);
      wr(NOTES + 4 * t, (long) note[t] << 16, 4);
      if (K == null) wr(e + 0x42, LEVW, 2);
      wr(VELS + 2 * t, X0 + t, 2);
      wr(VOICES + t * 0x5e + 0x10, STALE, 4);	// a level the lanes faded: the synth must not read it
      wr(VOICES + t * 0x5e + 0x28, (activeMask >> t) & 1, 1);
      for (int i = 0; i < 32; i++) wr(A18 + t * 0x80 + 4 * i, 0x11110000L + t * 0x100 + i, 4);
    }
    wr(TRIGS, trigMask, 4);
    long sp = SP0;
    sp -= 4; wr(sp, ENGINE, 4); sp -= 4; wr(sp, A18, 4); sp -= 4; wr(sp, RET, 4);
    emu.writeRegister("SP", sp); emu.writeRegister("PC", sym.get("cfo_pad"));
    long[] sent = new long[KEEP.length];
    for (int i = 0; i < KEEP.length; i++) { sent[i] = 0x5a5a0000L + i; emu.writeRegister(KEEP[i], sent[i]); }
    boolean done = false;
    int[] levelCalls = new int[8];
    for (int s = 0; s < 200000; s++) {
      long pc = emu.getExecutionAddress().getOffset();
      if (pc == FILL) { done = true; maxSteps = Math.max(maxSteps, s); break; }
      if (pc == LEVEL) {	// the stub: check (x, LEV), return the case's level, clobber %d1 as the real one
        long q = rd("SP");
        int x = (int) rdn(q + 6, 2), lev = (int) rdn(q + 10, 2), t = x - X0;
        if (t < 0 || t > 7) bad.append(String.format(" [level x 0x%04x is no track's]", x));
        else levelCalls[t]++;
        int levWant = K != null ? 0x7f00 : LEVW;
        if (lev != levWant) bad.append(String.format(" [level LEV 0x%04x, want 0x%04x]", lev, levWant));
        emu.writeRegister("D0", level & 0xffffffffL); emu.writeRegister("D1", 0xdead0001L);
        emu.writeRegister("PC", rdn(q, 4)); emu.writeRegister("SP", q + 4);
        continue;
      }
      if (!emu.step(monitor)) { bad.append(" [FAULT at 0x" + Long.toHexString(pc) + ": " + emu.getLastError() + "]"); break; }
    }
    if (!done && bad.length() == 0) bad.append(" [never reached FUN_40072478]");
    if (done) {
      if (rd("SP") != sp) bad.append(String.format(" [SP 0x%08x at FUN_40072478, want 0x%08x]", rd("SP"), sp));
      if (rdn(sp + 4, 4) != A18 || rdn(sp + 8, 4) != ENGINE) bad.append(" [the stock call's arguments changed]");
      for (int i = 0; i < KEEP.length; i++)
        if (rd(KEEP[i]) != sent[i]) bad.append(" [" + KEEP[i] + " not restored]");
    }
    for (int t = 0; t < 8; t++) {
      boolean synth = m5 ? eff[t] == 5 : (machine[t] == 0 && slot[t] == 0);
      if (done && levelCalls[t] != (synth ? 1 : 0))
        bad.append(String.format(" [track %d: %d level calls, want %d]", t, levelCalls[t], synth ? 1 : 0));
      int lv = ((activeMask & trigMask) >> t & 1) != 0 ? 0 : level;	// the de-click
      int[] want = !synth ? null : K != null ? (v2 ? modelK2(t, note[t], K, lv) : modelK(t, note[t], K, lv))
          : model(t, note[t], tune, play, br, strt, len, loop, lv);
      for (int i = 0; i < 32; i++) {
        int got = rd32s(A18 + t * 0x80 + 4 * i);
        int exp = synth ? want[i] : (int) (0x11110000L + t * 0x100 + i);
        if (got != exp) {
          bad.append(String.format(" [track %d sample %d: 0x%08x, want 0x%08x%s]", t, i, got, exp, synth ? "" : " (untouched)"));
          break;
        }
      }
      lastOut[t] = want;
    }
    if (report || bad.length() != 0) {
      println(String.format("  %-64s %s%s", label, bad.length() == 0 ? "OK" : "**FAIL**", bad));
      if (bad.length() != 0) fails++;
    }
    return bad.length() == 0;
  }

  String cstr(long a) throws Exception {
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < 40; i++) { int c = (int) rdn(a + i, 1); if (c == 0) break; b.append((char) c); }
    return b.toString();
  }

  static final long PAGE = 0x439d3400L, QUERY = 0x4002b5d4L, SHORT_PAD = 0x4001562cL, LONG_PAD = 0x4001564cL;
  static final long DESC = 0x401aa09cL;
  String[] CFOO_SHORT = {"TUNE", "FMSR", "MIX", "SAMP", "WAV1", "FM", "WAV2", "LEV"};
  String[] CFOO_LONG = {"Tune", "FM Source", "Osc Mix", "Sample Slot", "OSC1 Wave", "FM Amount", "OSC2+3 Wave", "Level"};

  /** One label-pad call: returns the name it gives, or null on a fault; checks SP, registers, the query's argument. */
  String label(boolean shortLabel, int id, int machine, long frame100, StringBuilder bad) throws Exception {
    long sp = SP0;
    sp -= 4; wr(sp, id, 4); sp -= 4; wr(sp, 0x12345678L, 4); sp -= 4; wr(sp, RET, 4);
    wr(sp + 100, frame100, 4);                 // the caller's frame word the short pad tests for TRK
    emu.writeRegister("SP", sp);
    emu.writeRegister("PC", shortLabel ? SHORT_PAD : LONG_PAD);
    long[] sent = new long[KEEP.length];
    for (int i = 0; i < KEEP.length; i++) { sent[i] = 0x5a5a0000L + i; emu.writeRegister(KEEP[i], sent[i]); }
    emu.writeRegister(shortLabel ? "A4" : "A2", PAGE);
    if (!shortLabel) emu.writeRegister("D2", id);
    for (int i = 0; i < 2000; i++) {
      long pc = emu.getExecutionAddress().getOffset();
      if (pc == RET) {
        if (rd("SP") != sp + 4) bad.append(String.format(" [id %d: SP]", id));
        for (int k = 0; k < KEEP.length; k++) {
          long want = KEEP[k].equals(shortLabel ? "A4" : "A2") ? PAGE : KEEP[k].equals("D2") && !shortLabel ? id : sent[k];
          if (rd(KEEP[k]) != want) bad.append(" [id " + id + ": " + KEEP[k] + " changed]");
        }
        return cstr(rd("D0"));
      }
      if (pc == QUERY) {                        // the page's machine, stubbed
        long qsp = rd("SP");
        if (rdn(qsp + 4, 4) != PAGE) bad.append(String.format(" [id %d: the query got 0x%08x, not the page]", id, rdn(qsp + 4, 4)));
        emu.writeRegister("D0", machine);
        emu.writeRegister("PC", rdn(qsp, 4)); emu.writeRegister("SP", qsp + 4);
        continue;
      }
      if (!emu.step(monitor)) { bad.append(" [id " + id + ": FAULT " + emu.getLastError() + "]"); return null; }
    }
    bad.append(" [id " + id + ": no return]");
    return null;
  }

  void nameCases() throws Exception {
    fresh();
    for (int machine : new int[] {0, 3, 4, 5}) {
      StringBuilder bad = new StringBuilder();
      for (int id = 100; id <= 120; id++) {
        long rec = DESC + 0x34L * id;
        boolean ours = machine == 5 && id >= 108 && id <= 115;
        String ws = ours ? CFOO_SHORT[id - 108] : cstr(rdn(rec + 0x30, 4));
        String wl = ours ? CFOO_LONG[id - 108] : cstr(rdn(rec + 0x28, 4));
        String gs = label(true, id, machine, 1, bad), gl = label(false, id, machine, 1, bad);
        if (gs != null && !gs.equals(ws)) bad.append(String.format(" [id %d short %s, want %s]", id, gs, ws));
        if (gl != null && !gl.equals(wl)) bad.append(String.format(" [id %d long %s, want %s]", id, gl, wl));
      }
      println(String.format("  %-64s %s%s", "names on a machine-" + machine + " page, ids 100..120",
                            bad.length() == 0 ? "OK" : "**FAIL**", bad));
      if (bad.length() != 0) fails++;
    }
    StringBuilder bad = new StringBuilder();
    String trk = label(true, 140, 5, -1, bad), chan = label(true, 140, 5, 1, bad);
    String want = cstr(rdn(DESC + 0x34L * 140 + 0x30, 4));
    if (trk == null || !trk.equals("TRK")) bad.append(" [CHAN below zero: " + trk + ", want TRK]");
    if (chan == null || !chan.equals(want)) bad.append(" [CHAN at a channel: " + chan + ", want " + want + "]");
    println(String.format("  %-64s %s%s", "MIDI Loopback's TRK label still works", bad.length() == 0 ? "OK" : "**FAIL**", bad));
    if (bad.length() != 0) fails++;
  }

  static final long ICON_FN = 0x40029e9cL, ITEM_MACHINE = 0x400c41e0L, DRAW = 0x400c2b88L;

  void iconCases() throws Exception {
    fresh();
    StringBuilder bad = new StringBuilder();
    StringBuilder seen = new StringBuilder();
    for (int m = 0; m <= 6; m++) {
      long sp = SP0;
      for (int k = 5; k >= 1; k--) { sp -= 4; wr(sp, 0x11110000L + k, 4); }
      sp -= 4; wr(sp, RET, 4);
      emu.writeRegister("SP", sp); emu.writeRegister("PC", ICON_FN);
      long bitmap = -1; boolean returned = false;
      for (int i = 0; i < 400; i++) {
        long pc = emu.getExecutionAddress().getOffset();
        if (pc == ITEM_MACHINE) {
          long q = rd("SP");
          emu.writeRegister("D0", m); emu.writeRegister("PC", rdn(q, 4)); emu.writeRegister("SP", q + 4);
          continue;
        }
        if (pc == DRAW) { bitmap = rdn(rd("SP") + 8, 4); break; }
        if (pc == RET) { returned = true; break; }
        if (!emu.step(monitor)) { bad.append(" [machine " + m + ": FAULT " + emu.getLastError() + "]"); break; }
      }
      seen.append(String.format(" %d:%s", m, bitmap < 0 ? (returned ? "none" : "?") : String.format("0x%08x", bitmap)));
      long want = m == 3 ? 0x421fa35cL : m == 4 ? 0x40252bdcL : m == 5 ? sym.get("cfoo_icon") : -2;
      if (want >= 0 && bitmap != want) bad.append(String.format(" [machine %d: 0x%08x, want 0x%08x]", m, bitmap, want));
      if (m <= 2 && (bitmap < 0 || bitmap == sym.get("cfoo_icon"))) bad.append(" [machine " + m + ": no stock icon]");
      if (m == 6 && !returned) bad.append(" [machine 6: drew something]");
    }
    println(String.format("  %-64s %s%s", "icon: SLICE 3, POLY 4, CFOO 5, stock 0-2, none for 6", bad.length() == 0 ? "OK" : "**FAIL**", bad));
    println("    bitmaps:" + seen);
    if (bad.length() != 0) fails++;
  }

  /** machine 5 mode: a POLY voice track given a CFOO sound. A grouped trig carries its source's sound,
   *  and the ISR applies a trig's sound to the voice track with the stock FUN_40077282(sound, track):
   *  the track must end up with machine 5 (0x800018bc + track), CFOO's 106 B of values
   *  (0x80001502 + 106 x track) and that sound as its current one (0x800019b4 + 4 x track). */
  static final long APPLY = 0x40077282L, APPLY_TAIL = 0x400749ccL, SOUND = 0x439d3800L;
  void polyVoiceCase() throws Exception {
    fresh();
    StringBuilder bad = new StringBuilder();
    int t = 3;
    for (int i = 0; i < 106; i++) wr(SOUND + 20 + i, 0x40 + i, 1);	// values at +20, the machine at +0x7e
    wr(SOUND + 0x7e, 5, 1);
    wr(0x800018bcL + t, 4, 1);						// the track's own machine: POLY
    long sp = SP0;
    sp -= 4; wr(sp, t, 4); sp -= 4; wr(sp, SOUND, 4); sp -= 4; wr(sp, RET, 4);
    emu.writeRegister("SP", sp); emu.writeRegister("PC", APPLY);
    boolean done = false;
    for (int s = 0; s < 20000; s++) {
      long pc = emu.getExecutionAddress().getOffset();
      if (pc == APPLY_TAIL) { done = true; break; }		// the rest of the voice setup is not needed
      if (pc == RET) break;
      if (!emu.step(monitor)) { bad.append(" [FAULT " + emu.getLastError() + "]"); break; }
    }
    if (!done && bad.length() == 0) bad.append(" [never reached FUN_400749cc]");
    if (rdn(0x800018bcL + t, 1) != 5) bad.append(String.format(" [machine %d, want 5]", rdn(0x800018bcL + t, 1)));
    for (int i = 0; i < 106; i++)
      if (rdn(0x80001502L + 106 * t + i, 1) != 0x40 + i) { bad.append(" [value byte " + i + " not copied]"); break; }
    if (rdn(0x800019b4L + 4 * t, 4) != SOUND) bad.append(" [current sound not recorded]");
    println(String.format("  %-64s %s%s", "a POLY voice given a CFOO sound: machine 5, CFOO's values", bad.length() == 0 ? "OK" : "**FAIL**", bad));
    if (bad.length() != 0) fails++;
  }

  /** machine 5 mode, a load with the SLOTS hook (sym slot_machine): FUN_40078f44(slot, machine) for the
   *  SRC slots 17..24 and machines 0..7. Its table 0x4199f9c4 (8 ids per machine 0..3) is built at run
   *  time, so it is seeded with 1000 + index: machines 0..3 must get their own ids, machine 5 machine
   *  0's, every other machine id 0. */
  void slotCases() throws Exception {
    fresh();
    StringBuilder bad = new StringBuilder();
    for (int i = 0; i < 32; i++) wr(0x4199f9c4L + 4 * i, 1000 + i, 4);
    for (int m = 0; m < 8; m++)
      for (int slot = 17; slot <= 24; slot++) {
        long sp = SP0;
        sp -= 4; wr(sp, m, 4); sp -= 4; wr(sp, slot, 4); sp -= 4; wr(sp, RET, 4);
        emu.writeRegister("SP", sp); emu.writeRegister("PC", 0x40078f44L);
        boolean done = false;
        for (int s = 0; s < 200; s++) {
          if (emu.getExecutionAddress().getOffset() == RET) { done = true; break; }
          if (!emu.step(monitor)) { bad.append(" [FAULT]"); break; }
        }
        int mm = m == 5 ? 0 : m;
        long want = mm < 4 ? 1000 + 8 * mm + slot - 17 : 0;
        if (!done) bad.append(String.format(" [machine %d slot %d: no return]", m, slot));
        else if (rd("D0") != want) bad.append(String.format(" [machine %d slot %d: %d, want %d]", m, slot, rd("D0"), want));
        if (done && rd("SP") != sp + 4) bad.append(" [SP]");
        if (bad.length() > 200) break;
      }
    println(String.format("  %-64s %s%s", "SRC slot ids: 0-3 own, 5 as ONESHOT (0), 4/6/7 none", bad.length() == 0 ? "OK" : "**FAIL**", bad));
    if (bad.length() != 0) fails++;
  }

  /** knobs mode (S16): the synth cases over CFOO's own knob map, against modelK. */
  void runK(String label, int ticks, int[] machine, int[] slot, int[] note, int[] k) throws Exception {
    K = k;
    run(label, ticks, machine, slot, note, 0, 0, 0, 0, 0, 0, 0x7fffffff);
  }
  void knobCases(int[] none, int[] noSlot, int[] zero, int[] c4, int[] notes, int MAX) throws Exception {
    int[] def = {4, 0, 0, 0, 0, 0, 48, 50};
    runK("knobs: no synth track, every block untouched", 3, none, noSlot, c4, def);
    runK("knobs: defaults (OSC1 SIN alone), all 8 tracks", 40, zero, zero, notes, def);
    runK("knobs: A 46 (TRI..SAW), A 88 (SQR)", 20, zero, zero, notes, new int[] {46, 0, 0, 0, 0, 0, 48, 50});
    runK("knobs: A 88", 20, zero, zero, notes, new int[] {88, 0, 0, 0, 0, 0, 48, 50});
    runK("knobs: A 2 (below range, clamps to SIN)", 10, zero, zero, notes, new int[] {2, 0, 0, 0, 0, 0, 48, 50});
    runK("knobs: mix E 40 / waves C 40, D 90", 20, zero, zero, notes, new int[] {4, 0, 40, 90, 40, 0, 48, 50});
    runK("knobs: mix E 80", 20, zero, zero, notes, new int[] {4, 0, 40, 90, 80, 0, 48, 50});
    runK("knobs: mix E 120 (OSC2+3)", 20, zero, zero, notes, new int[] {4, 0, 40, 90, 120, 0, 48, 50});
    runK("knobs: FM B 1 (OSC2), F 60", 30, zero, zero, notes, new int[] {4, 1, 0, 0, 0, 60, 36, 50});
    runK("knobs: FM B 2 (OSC2+3), F 120", 30, zero, zero, notes, new int[] {30, 2, 20, 70, 60, 120, 40, 70});
    runK("knobs: FM B 3 (OSC3), F 90", 30, zero, zero, notes, new int[] {4, 3, 0, 0, 0, 90, 48, 62});
    runK("knobs: FM B 0 (off), F 120", 20, zero, zero, notes, new int[] {4, 0, 0, 0, 0, 120, 48, 50});
    runK("knobs: G 0 (-48 st), H 98 (+48 st)", 20, zero, zero, notes, new int[] {4, 0, 0, 0, 85, 0, 0, 98});
    runK("knobs: G 47 (-1 st), H 49 (-1 c)", 20, zero, zero, notes, new int[] {4, 0, 0, 0, 85, 0, 47, 49});
    runK("knobs: G 49 (+1 c), H 51 (+1 st)", 20, zero, zero, notes, new int[] {4, 0, 0, 0, 85, 0, 49, 51});
    runK("knobs: G 98 (+50 c), H 0 (-50 c)", 20, zero, zero, notes, new int[] {4, 0, 0, 0, 85, 0, 98, 0});
    runK("knobs: G 120, H 127 (above range, clamp to 98)", 20, zero, zero, notes, new int[] {4, 0, 0, 0, 85, 0, 120, 127});
    runK("knobs: extreme notes with +48 st", 10, zero, zero, new int[] {0, 127, 0, 127, 0, 127, 0, 127}, new int[] {88, 2, 127, 127, 60, 120, 0, 98});
    K = new int[] {4, 0, 0, 0, 0, 0, 48, 50};
    activeMask = 0xff; trigMask = 0x0a;
    run("knobs: de-click", 5, zero, zero, notes, 0, 0, 0, 0, 0, 0, MAX);
    activeMask = 0; trigMask = 0;
    seedLevels = 0xdeadbeefL;
    run("knobs: level RAM not set at boot", 3, zero, zero, notes, 0, 0, 0, 0, 0, 0, MAX);
    seedLevels = 0;
    run("knobs: half level", 10, zero, zero, notes, 0, 0, 0, 0, 0, 0, 0x40000000);
  }

  /** knobs mode (S16): cfo_range against a fake parameter set whose sound carries machine 5 or 0, and
   *  the five call sites pointing at it. */
  static final long SET = 0x439d3a00L, VT = 0x439d3a40L, VFN = 0x439d3b00L, SND = 0x439d3c00L, DEST = 0x439d3d00L;
  static final long[] RANGE_SITES = {0x4000f536L, 0x4000ff22L, 0x400100c6L, 0x40010156L, 0x4002213aL};
  static final long[][] CFOO_RANGES2 = {{0, 0x7f00, 0}, {0, 0x200, 0}, {0, 0x7f00, 0}, {0, 0x7f00, 0},
      {0, 0x7f00, 0}, {0, 0x7f00, 0}, {0x2800, 0x5800, 0x4000}, {0x2800, 0x5800, 0x4000}};
  static final long[][] CFOO_RANGES = {{0x400, 0x5800, 0x400}, {0, 0x300, 0}, {0, 0x7f00, 0}, {0, 0x7f00, 0},
      {0, 0x7800, 0}, {0, 0x7800, 0}, {0, 0x6200, 0x3000}, {0, 0x6200, 0x3200}};
  void rangeCases() throws Exception {
    fresh();
    StringBuilder bad = new StringBuilder();
    long cr = sym.get("cfo_range");
    for (long a : RANGE_SITES) if (rdn(a, 4) != cr) bad.append(String.format(" [0x%08x does not point at cfo_range]", a));
    wr(SET, VT, 4);
    wr(VT + 0x28, VFN, 4);
    wr(VFN, 0x203cL, 2); wr(VFN + 2, SND, 4); wr(VFN + 6, 0x4e75L, 2);	// movel #SND,%d0 ; rts
    for (int mach : new int[] {5, 0, 4}) {
      wr(SND + 0x7e, mach, 1);
      for (int id = 106; id <= 117; id++) {
        wr(DEST, 0x5a5a5a5aL, 4); wr(DEST + 4, 0x5a5a5a5aL, 4); wr(DEST + 8, 0x5a5a5a5aL, 4);
        long sp = SP0;
        sp -= 4; wr(sp, id, 4); sp -= 4; wr(sp, RET, 4);
        emu.writeRegister("SP", sp); emu.writeRegister("PC", cr);
        emu.writeRegister("A0", DEST); emu.writeRegister("A2", SET); emu.writeRegister("D2", 0x2222L);
        boolean done = false;
        for (int s = 0; s < 400; s++) {
          if (emu.getExecutionAddress().getOffset() == RET) { done = true; break; }
          if (!emu.step(monitor)) { bad.append(" [FAULT " + emu.getLastError() + "]"); break; }
        }
        boolean ours = mach == 5 && id >= 108 && id <= 115;
        long rec = 0x401aa09cL + 0x34L * id + 8;
        for (int f = 0; f < 3; f++) {
          long want = ours ? (v2 ? CFOO_RANGES2 : CFOO_RANGES)[id - 108][f] : rdn(rec + 4 * f, 4);
          if (done && rdn(DEST + 4 * f, 4) != want)
            bad.append(String.format(" [machine %d id %d field %d: 0x%x, want 0x%x]", mach, id, f, rdn(DEST + 4 * f, 4), want));
        }
        if (!done) bad.append(" [machine " + mach + " id " + id + ": no return]");
        else {
          if (rd("SP") != sp + 4) bad.append(" [SP]");
          if (rd("A2") != SET || rd("D2") != 0x2222L) bad.append(" [%a2/%d2 not kept]");
          if (rd("D0") != DEST) bad.append(" [%d0 is not the destination]");
        }
        if (bad.length() > 300) break;
      }
    }
    println(String.format("  %-64s %s%s", "ranges: CFOO's own for ids 108-115 on machine 5, stock otherwise", bad.length() == 0 ? "OK" : "**FAIL**", bad));
    if (bad.length() != 0) fails++;
  }

  /** displays mode (S17): CFOO's own value displays. The model is the agreed interface, written from the
   *  knob table, not from the code: A, C, D a wave on one 0..127 scale (A: (A - 4) x 1.5); B OFF, OSC2,
   *  2+3, OSC3 (above 3: OFF, as the synth takes no source); E the mix E + E/16, at most 127; F the FM
   *  amount; G -48st..-1st, 0, +1ct..+50ct and H -50ct..-1ct, 0, +1st..+48st over 0..98, above 98 as 98.
   *  Each knob's own range, for the picture: A 4..88, B 0..3, C and D 0..127, E and F 0..120, G and H
   *  0..98. */
  static final long DSET = 0x439d3e00L, DPAGE = 0x439d3e40L, DBUF = 0x439d3e80L, POPBUF = 0x4197de98L;
  static final long MACHQ = 0x4002200aL, PAGEQ = 0x4002b5d4L, FMT = 0x400657eeL;
  static final int[][] KRANGE = {{4, 84}, {0, 3}, {0, 127}, {0, 127}, {0, 120}, {0, 120}, {0, 98}, {0, 98}};
  static String agreedText(int k, int n) {
    switch (k) {
      case 0: return String.valueOf(Math.max(n - 4, 0) * 3 / 2);
      case 1: return n <= 3 ? new String[] {"OFF", "OSC2", "2+3", "OSC3"}[n] : "OFF";
      case 4: return String.valueOf(Math.min(127, n + n / 16));
      case 6: { int d = Math.min(n, 98) - 48; return d < 0 ? d + "st" : d == 0 ? "0" : "+" + d + "ct"; }
      case 7: { int d = Math.min(n, 98) - 50; return d < 0 ? d + "ct" : d == 0 ? "0" : "+" + d + "st"; }
      default: return String.valueOf(n);
    }
  }
  /** the note-sum offset a G or H text means: a semitone is 0x10000, a cent 655 */
  static int textOffset(String t) {
    if (t.equals("0")) return 0;
    int v = Integer.parseInt(t.substring(0, t.length() - 2).replace("+", ""));
    return t.endsWith("st") ? v * 0x10000 : v * 655;
  }
  /** call a routine with the stack given (top first), stubbing the machine queries; null if it ran away.
   *  With realSets (S19, or the realsets argument) the set's machine is not stubbed: DSET is a sound's
   *  parameter set as the firmware builds it (realSets()), and the stock FUN_4002200a runs. */
  long machine = 5;
  boolean callTo(long pc, long[] stack, long stopAt, StringBuilder bad, String what) throws Exception {
    long sp = SP0 - 4L * stack.length;
    for (int i = 0; i < stack.length; i++) wr(sp + 4L * i, stack[i], 4);
    if (realSets) { realSets(machine); wr(DSET, SOUNDSET_VT, 4); wr(DSET + 0x10, HHOLD, 4); }
    emu.writeRegister("SP", sp); emu.writeRegister("PC", pc);
    for (int i = 0; i < KEEP.length; i++) emu.writeRegister(KEEP[i], 0x5a5a0000L + i);
    emu.writeRegister("A2", DPAGE);
    if (initD0 >= 0) emu.writeRegister("D0", initD0);
    for (int s = 0; s < 3000; s++) {
      long p = emu.getExecutionAddress().getOffset();
      if (p == RET || p == stopAt) return true;
      if ((p == MACHQ && !realSets) || p == PAGEQ) {
        long q = rd("SP");
        long arg = rdn(q + 4, 4);
        if (arg != (p == MACHQ ? DSET : DPAGE)) bad.append(" [" + what + ": machine query got 0x" + Long.toHexString(arg) + "]");
        emu.writeRegister("D0", machine); emu.writeRegister("D1", 0xdead0001L);
        emu.writeRegister("PC", rdn(q, 4)); emu.writeRegister("SP", q + 4);
        continue;
      }
      if (!emu.step(monitor)) { bad.append(" [" + what + ": FAULT " + emu.getLastError() + "]"); return false; }
    }
    bad.append(" [" + what + ": ran away]");
    return false;
  }
  boolean regsKept(StringBuilder bad, String what) throws Exception {
    for (int i = 0; i < KEEP.length; i++) {
      long want = KEEP[i].equals("A2") ? DPAGE : 0x5a5a0000L + i;
      if (rd(KEEP[i]) != want) { bad.append(" [" + what + ": " + KEEP[i] + " not kept]"); return false; }
    }
    return true;
  }
  void displayCases() throws Exception {
    fresh();
    long popup = sym.get("cfo_popup");
    StringBuilder hooks = new StringBuilder();
    if (rdn(0x40032d16L, 2) != 0x4eb9L || rdn(0x40032d18L, 4) != popup) hooks.append(" [0x40032d16 is not jsr cfo_popup]");
    if (rdn(0x4000f2bcL, 2) != 0x4ef9L || rdn(0x4000f2beL, 4) != sym.get("cfo_pic")) hooks.append(" [0x4000f2bc is not jmp cfo_pic]");
    if (rdn(0x4000f324L, 2) != 0x4ef9L || rdn(0x4000f326L, 4) != sym.get("cfo_ctext")) hooks.append(" [0x4000f324 is not jmp cfo_ctext]");
    println(String.format("  %-64s %s%s", "displays: the three hooks in place", hooks.length() == 0 ? "OK" : "**FAIL**", hooks));
    if (hooks.length() != 0) fails++;

    // 1. the texts, through the popup and the cell, every knob and value, against the agreed table
    StringBuilder bad = new StringBuilder();
    int n_checked = 0;
    for (long mach : new long[] {5, 0, 4}) {
      machine = mach;
      for (int id = 106; id <= 117; id++) {
        boolean ours = mach == 5 && id >= 108 && id <= 115;
        for (int n = 0; n < 256 && bad.length() < 400; n++) {
          if (!ours && n > 2) break;
          long value = ((long) n << 8) | 0x80;		// a fraction the display must ignore
          String want = ours ? agreedText(id - 108, n) : null;
          // the popup: cfo_popup(id, value), the page in %a2
          wr(POPBUF, 0x5a5a5a5a5a5a5a5aL, 8);
          if (callTo(popup, new long[] {RET, id, value}, FMT, bad, "popup id " + id + " n " + n)) {
            long pc = emu.getExecutionAddress().getOffset();
            if (ours) {
              String got = cstr(POPBUF);
              if (pc != RET) bad.append(" [popup id " + id + ": went to the stock formatter]");
              else if (!got.equals(want)) bad.append(" [popup id " + id + " n " + n + ": '" + got + "', want '" + want + "']");
              else if (rd("D0") != POPBUF) bad.append(" [popup: %d0 is not the buffer]");
              else if (rd("SP") != SP0 - 8) bad.append(" [popup: SP]");
              else regsKept(bad, "popup");
            } else if (pc != FMT) bad.append(" [popup machine " + mach + " id " + id + ": did not go to the stock formatter]");
            else if (rd("SP") != SP0 - 12 || rdn(SP0 - 8, 4) != id || rdn(SP0 - 4, 4) != value) bad.append(" [popup: stock call's stack changed]");
          }
          // the cell's text: ParameterSet::vfunc_22(set, id, value, buffer), entered at its hooked start
          wr(DBUF, 0x5a5a5a5a5a5a5a5aL, 8);
          if (callTo(0x4000f324L, new long[] {RET, DSET, id, value, DBUF}, 0x4000f32cL, bad, "cell text id " + id)) {
            long pc = emu.getExecutionAddress().getOffset();
            if (ours) {
              String got = cstr(DBUF);
              if (pc != RET) bad.append(" [cell text id " + id + ": went on into vfunc_22]");
              else if (!got.equals(want)) bad.append(" [cell text id " + id + " n " + n + ": '" + got + "', want '" + want + "']");
              else if (rd("SP") != SP0 - 16) bad.append(" [cell text: SP]");
              else regsKept(bad, "cell text");
            } else if (pc != 0x4000f32cL) bad.append(" [cell text machine " + mach + " id " + id + ": did not go on into vfunc_22]");
            else {
              long q = rd("SP");
              if (q != SP0 - 20 - 20) bad.append(" [cell text: the replayed frame is wrong]");
              String[] saved = {"D2", "D3", "D4", "A2", "A3"};
              for (int i = 0; i < 5; i++) {
                long want2 = saved[i].equals("A2") ? DPAGE : 0x5a5a0000L + java.util.Arrays.asList(KEEP).indexOf(saved[i]);
                if (rdn(q + 4L * i, 4) != want2) { bad.append(" [cell text: " + saved[i] + " not saved as vfunc_22 saves it]"); break; }
              }
              if (rdn(q + 24, 4) != DSET || rdn(q + 28, 4) != id || rdn(q + 32, 4) != value) bad.append(" [cell text: stock call's arguments changed]");
            }
          }
          n_checked++;
        }
      }
    }
    machine = 5;
    println(String.format("  %-64s %s%s", "displays: popup and cell text, " + n_checked + " values, agreed table", bad.length() == 0 ? "OK" : "**FAIL**", bad));
    if (bad.length() != 0) fails++;

    // 2. the picture: ParameterSet::vfunc_23(set, id, value, ...) draws STRT's knob, the value rescaled
    bad = new StringBuilder();
    for (long mach : new long[] {5, 0, 4}) {
      machine = mach;
      for (int id = 106; id <= 117; id++) {
        boolean ours = mach == 5 && id >= 108 && id <= 115;
        for (int n = 0; n < 256 && bad.length() < 400; n++) {
          if (!ours && n > 2) break;
          long value = ((long) n << 8) | 0x80;
          if (!callTo(0x4000f2bcL, new long[] {RET, DSET, id, value, 0x11, 0x22, 0x33, 0x44}, 0x4000f2c4L, bad, "picture id " + id)) continue;
          if (emu.getExecutionAddress().getOffset() != 0x4000f2c4L) { bad.append(" [picture id " + id + ": did not go on into vfunc_23]"); continue; }
          long q = rd("SP");
          if (q != SP0 - 32 - 20) { bad.append(" [picture: the replayed frame is wrong]"); continue; }
          String[] saved = {"D2", "D3", "D4", "D5", "D6"};
          for (int i = 0; i < 5; i++)
            if (rdn(q + 4L * i, 4) != 0x5a5a0000L + java.util.Arrays.asList(KEEP).indexOf(saved[i])) { bad.append(" [picture: " + saved[i] + " not saved]"); break; }
          long gotId = rdn(q + 28, 4), gotVal = rdn(q + 32, 4);
          long wantId = id, wantVal = value;
          if (ours) {
            int[] r = KRANGE[id - 108];
            long rel = Math.min(Math.max(n - r[0], 0), r[1]);
            wantId = 112; wantVal = rel * 0x7800 / r[1];
          }
          if (gotId != wantId || gotVal != wantVal)
            bad.append(String.format(" [picture machine %d id %d n %d: id %d value 0x%x, want id %d value 0x%x]", mach, id, n, gotId, gotVal, wantId, wantVal));
          if (rdn(q + 24, 4) != DSET || rdn(q + 36, 4) != 0x11 || rdn(q + 48, 4) != 0x44) bad.append(" [picture: other arguments changed]");
        }
      }
    }
    machine = 5;
    println(String.format("  %-64s %s%s", "displays: picture = STRT's knob over each knob's own range", bad.length() == 0 ? "OK" : "**FAIL**", bad));
    if (bad.length() != 0) fails++;

    // 3. the synth agrees with the text: detune_lo (G) and detune_hi (H), every value
    bad = new StringBuilder();
    String[] rout = {"detune_lo", "detune_hi"};
    for (int g = 0; g < 2; g++) {
      for (int n = 0; n < 256 && bad.length() < 300; n++) {
        long sp = SP0 - 4;
        wr(sp, RET, 4);
        emu.writeRegister("SP", sp); emu.writeRegister("PC", sym.get(rout[g])); emu.writeRegister("D0", n);
        boolean done = false;
        for (int s = 0; s < 100; s++) {
          if (emu.getExecutionAddress().getOffset() == RET) { done = true; break; }
          if (!emu.step(monitor)) break;
        }
        int want = textOffset(agreedText(6 + g, n));
        if (!done) bad.append(" [" + rout[g] + " " + n + ": no return]");
        else if ((int) rd("D0") != want)
          bad.append(String.format(" [%s %d: 0x%x, want 0x%x for '%s']", rout[g], n, (int) rd("D0"), want, agreedText(6 + g, n)));
      }
    }
    println(String.format("  %-64s %s%s", "displays: G and H texts = the synth's detune, values 0-255", bad.length() == 0 ? "OK" : "**FAIL**", bad));
    if (bad.length() != 0) fails++;
  }

  /** S18: the agreed texts. A, C, D, E, F the number, at most 127; B OSC2, 2+3, OSC3 (above 2 as 2);
   *  G, H the semitones from unison, two decimals (a hundredth a cent, rounded), -24.00..+24.00, 0.00 at
   *  unison. And each knob's range for the picture, {min, span} in 8.8. S24 (sym wave_cnames): A, C, D
   *  at 0, 42, 85, 127 (the value as the synth reads it, at most 127) read SIN, TRI, SAW, SQR, and E
   *  OSC1, 1+2, 123, 2+3. */
  boolean corners = false;
  String agreedText2(int k, int raw) {
    int n = (raw >> 8) & 0xff;
    if (k == 1) return new String[] {"OSC2", "2+3", "OSC3"}[Math.min(n, 2)];
    if (k >= 6) {
      int v = Math.max(0x2800, Math.min(raw & 0xffff, 0x5800)) - 0x4000;
      if (v == 0) return "0.00";
      int c = (Math.abs(v) * 100 + 128) >> 8;
      return (v < 0 ? "-" : "+") + (c / 100) + "." + String.format("%02d", c % 100);
    }
    int m = Math.min(n, 127);
    int corner = m == 0 ? 0 : m == 42 ? 1 : m == 85 ? 2 : m == 127 ? 3 : -1;
    if (corners && corner >= 0 && k != 5)
      return (k == 4 ? new String[] {"OSC1", "1+2", "123", "2+3"} : new String[] {"SIN", "TRI", "SAW", "SQR"})[corner];
    return String.valueOf(m);
  }
  static final int[][] KRANGE2 = {{0, 0x7f00}, {0, 0x200}, {0, 0x7f00}, {0, 0x7f00}, {0, 0x7f00}, {0, 0x7f00}, {0x2800, 0x3000}, {0x2800, 0x3000}};
  static final int[] STEP_IDS = {110, 109, 110, 110, 110, 110, 108, 108};
  long initD0 = -1;
  List<Integer> values2(int k) {
    List<Integer> v = new ArrayList<>();
    if (k < 6) for (int n = 0; n < 256; n++) v.add((n << 8) | 0x80);
    else {
      for (int r = 0x2000; r <= 0x6000; r += 0x0d) v.add(r);
      for (int r : new int[] {0x2800, 0x27ff, 0x2801, 0x3fff, 0x4000, 0x4001, 0x3f80, 0x4740, 0x57ff, 0x5800, 0x5801, 0x0000, 0xffff}) v.add(r);
    }
    return v;
  }
  void displayCases2() throws Exception {
    fresh();
    long popup = sym.get("cfo_popup");
    // 1. texts, popup and cell, against the agreed table
    StringBuilder bad = new StringBuilder();
    int checked = 0;
    for (long mach : new long[] {5, 0, 4}) {
      machine = mach;
      for (int id = 106; id <= 117; id++) {
        boolean ours = mach == 5 && id >= 108 && id <= 115;
        List<Integer> vals = ours ? values2(id - 108) : java.util.Arrays.asList(0x0080, 0x4000, 0x7f00);
        for (int raw : vals) {
          if (bad.length() > 400) break;
          long value = raw & 0xffffL;
          String want = ours ? agreedText2(id - 108, raw) : null;
          wr(POPBUF, 0x5a5a5a5a5a5a5a5aL, 8); wr(POPBUF + 8, 0x5a5a5a5aL, 4);
          if (callTo(popup, new long[] {RET, id, value}, FMT, bad, "popup id " + id)) {
            long pc = emu.getExecutionAddress().getOffset();
            if (ours) {
              String got = cstr(POPBUF);
              if (pc != RET) bad.append(" [popup id " + id + ": went to the stock formatter]");
              else if (!got.equals(want)) bad.append(String.format(" [popup id %d value 0x%x: '%s', want '%s']", id, raw, got, want));
              else if (rd("D0") != POPBUF || rd("SP") != SP0 - 8) bad.append(" [popup: %d0 or SP]");
              else regsKept(bad, "popup");
            } else if (pc != FMT || rdn(SP0 - 8, 4) != id || rdn(SP0 - 4, 4) != value) bad.append(" [popup machine " + mach + " id " + id + ": stock call changed]");
          }
          wr(DBUF, 0x5a5a5a5a5a5a5a5aL, 8); wr(DBUF + 8, 0x5a5a5a5aL, 4);
          if (callTo(0x4000f324L, new long[] {RET, DSET, id, value, DBUF}, 0x4000f32cL, bad, "cell text id " + id)) {
            long pc = emu.getExecutionAddress().getOffset();
            if (ours) {
              String got = cstr(DBUF);
              if (pc != RET || !got.equals(want)) bad.append(String.format(" [cell text id %d value 0x%x: '%s', want '%s']", id, raw, got, want));
              else regsKept(bad, "cell text");
            } else if (pc != 0x4000f32cL || rdn(rd("SP") + 28, 4) != id) bad.append(" [cell text machine " + mach + " id " + id + ": stock call changed]");
          }
          checked++;
        }
      }
    }
    machine = 5;
    println(String.format("  %-64s %s%s", "S18 displays: popup and cell text, " + checked + " values", bad.length() == 0 ? "OK" : "**FAIL**", bad));
    if (bad.length() != 0) fails++;

    // 2. the picture: STRT's knob, the 8.8 value rescaled from the knob's range onto 0..120.0
    bad = new StringBuilder();
    for (long mach : new long[] {5, 0, 4}) {
      machine = mach;
      for (int id = 106; id <= 117; id++) {
        boolean ours = mach == 5 && id >= 108 && id <= 115;
        List<Integer> vals = ours ? values2(id - 108) : java.util.Arrays.asList(0x0080, 0x4000);
        for (int raw : vals) {
          if (bad.length() > 400) break;
          long value = raw & 0xffffL;
          if (!callTo(0x4000f2bcL, new long[] {RET, DSET, id, value, 0x11, 0x22, 0x33, 0x44}, 0x4000f2c4L, bad, "picture id " + id)) continue;
          long q = rd("SP");
          long wantId = id, wantVal = value;
          if (ours) {
            int[] r = KRANGE2[id - 108];
            long rel = Math.min(Math.max(raw - r[0], 0), r[1]);
            wantId = 112; wantVal = rel * 0x7800 / r[1];
          }
          if (emu.getExecutionAddress().getOffset() != 0x4000f2c4L || q != SP0 - 52) bad.append(" [picture id " + id + ": frame]");
          else if (rdn(q + 28, 4) != wantId || rdn(q + 32, 4) != wantVal)
            bad.append(String.format(" [picture machine %d id %d value 0x%x: id %d 0x%x, want id %d 0x%x]", mach, id, raw, rdn(q + 28, 4), rdn(q + 32, 4), wantId, wantVal));
        }
      }
    }
    machine = 5;
    println(String.format("  %-64s %s%s", "S18 displays: picture = STRT's knob over each knob's range", bad.length() == 0 ? "OK" : "**FAIL**", bad));
    if (bad.length() != 0) fails++;

    // 3. G and H: the synth's detune24 against det24, and the text against the offset (half a cent)
    bad = new StringBuilder();
    for (int raw : values2(6)) {
      if (bad.length() > 300) break;
      long sp = SP0 - 4;
      wr(sp, RET, 4);
      emu.writeRegister("SP", sp); emu.writeRegister("PC", sym.get("detune24")); emu.writeRegister("D0", raw & 0xffffL);
      boolean done = false;
      for (int s = 0; s < 100; s++) {
        if (emu.getExecutionAddress().getOffset() == RET) { done = true; break; }
        if (!emu.step(monitor)) break;
      }
      int got = (int) rd("D0");
      String t = agreedText2(6, raw);
      String[] p = t.replace("+", "").split("\\.");
      int cents = Integer.parseInt(p[0].replace("-", "")) * 100 + Integer.parseInt(p[1]);
      if (t.startsWith("-")) cents = -cents;
      long implied = Math.round(cents * 65536.0 / 100.0);
      if (!done || got != det24(raw)) bad.append(String.format(" [detune24 0x%x: 0x%x, want 0x%x]", raw, got, det24(raw)));
      else if (Math.abs(implied - got) > 330) bad.append(String.format(" [0x%x: text '%s' is %d, the synth %d]", raw, t, implied, got));
    }
    println(String.format("  %-64s %s%s", "S18 displays: G, H text = the synth's detune to half a cent", bad.length() == 0 ? "OK" : "**FAIL**", bad));
    if (bad.length() != 0) fails++;

    // 4. the encoder's display object: the step of BR, PLAY or TUNE for CFOO, the knob's own otherwise
    bad = new StringBuilder();
    for (long mach : new long[] {5, 0, 4}) {
      machine = mach;
      for (int id = 104; id <= 120; id++) {
        if (!callTo(sym.get("cfo_encobj"), new long[] {RET, id}, 0x40065794L, bad, "encoder id " + id)) continue;
        long want = mach == 5 && id >= 108 && id <= 115 ? STEP_IDS[id - 108] : id;
        if (emu.getExecutionAddress().getOffset() != 0x40065794L) bad.append(" [encoder id " + id + ": not at FUN_40065794]");
        else if (rd("SP") != SP0 - 8 || rdn(SP0 - 8, 4) != RET) bad.append(" [encoder: SP]");
        else if (rdn(SP0 - 4, 4) != want) bad.append(String.format(" [encoder machine %d id %d: %d, want %d]", mach, id, rdn(SP0 - 4, 4), want));
        else regsKept(bad, "encoder");
      }
    }
    machine = 5;
    if (rdn(0x40032b74L, 2) != 0x4eb9L || rdn(0x40032b76L, 4) != sym.get("cfo_encobj")) bad.append(" [0x40032b74 is not jsr cfo_encobj]");
    println(String.format("  %-64s %s%s", "S18 encoder: BR's step for A, C-F, PLAY's for B, TUNE's for G, H", bad.length() == 0 ? "OK" : "**FAIL**", bad));
    if (bad.length() != 0) fails++;

    // 5. the Sample Slot test: for id 111 on a CFOO page it fails; everything else as stock
    bad = new StringBuilder();
    for (long mach : new long[] {5, 0, 4}) {
      machine = mach;
      for (int id : new int[] {108, 110, 111, 112, 115, 119, 127, 135}) {
        initD0 = id;
        boolean ok = callTo(sym.get("cfo_samptest"), new long[] {RET}, -1, bad, "sample test id " + id);
        initD0 = -1;
        if (!ok) continue;
        long wantD0 = mach == 5 && id == 111 ? 0 : 0xffffffefL;
        if (rd("D0") != wantD0 || rd("D1") != 111 || rd("D2") != id || rd("SP") != SP0)
          bad.append(String.format(" [machine %d id %d: d0 0x%x d1 %d d2 %d]", mach, id, rd("D0"), rd("D1"), rd("D2")));
        for (int i = 1; i < KEEP.length; i++) {
          long want2 = KEEP[i].equals("A2") ? DPAGE : 0x5a5a0000L + i;
          if (rd(KEEP[i]) != want2) { bad.append(" [sample test: " + KEEP[i] + " not kept]"); break; }
        }
      }
    }
    machine = 5;
    if (rdn(0x4003b5a0L, 2) != 0x4eb9L || rdn(0x4003b5a2L, 4) != sym.get("cfo_samptest")) bad.append(" [0x4003b5a0 is not jsr cfo_samptest]");
    println(String.format("  %-64s %s%s", "S18 sample picker: off for D on CFOO, stock otherwise", bad.length() == 0 ? "OK" : "**FAIL**", bad));
    if (bad.length() != 0) fails++;
  }

  /** S20: wave and gains for every value 0..127 against the model (segfrac above): the two tables and
   *  the fraction, the three gains; and at 0, 42, 85, 127 one wave or one mix alone. */
  void pureCases() throws Exception {
    fresh();
    StringBuilder bad = new StringBuilder();
    long frame = 0x439d4600L;
    for (int v = 0; v <= 127 && bad.length() < 400; v++) {
      int[] wm = wave(v);
      if (!runReal(sym.get("wave"), new long[] {RET}, new String[] {"D0"}, new long[] {v}, new long[] {}, bad, "wave " + v)) continue;
      if (rd("A0") != WAVES + wm[0] || rd("A1") != WAVES + wm[1] || rd("D5") != wm[2])
        bad.append(String.format(" [wave %d: a0 +%d a1 +%d d5 %d, want +%d +%d %d]", v, rd("A0") - WAVES, rd("A1") - WAVES, rd("D5"), wm[0], wm[1], wm[2]));
      for (int i = 0; i < KEEP.length; i++)
        if (!KEEP[i].equals("D5") && rd(KEEP[i]) != 0x5a5a0000L + i) { bad.append(" [wave: " + KEEP[i] + " not kept]"); break; }
      int[] gm = gains(v);
      if (!runReal(sym.get("gains"), new long[] {RET}, new String[] {"D0", "A6"}, new long[] {v, frame}, new long[] {}, bad, "gains " + v)) continue;
      for (int k = 0; k < 3; k++)
        if ((short) rdn(frame + 128 + 2 * k, 2) != gm[k]) bad.append(String.format(" [gains %d G%d: %d, want %d]", v, k + 1, (short) rdn(frame + 128 + 2 * k, 2), gm[k]));
    }
    // the corners: one wave, one mix
    int[][] corner = {{0, 0, 0}, {42, 1, 0}, {85, 2, 0}, {127, 2, 256}};
    for (int[] c : corner) {
      int[] wm = wave(c[0]);
      if (wm[0] != c[1] * 256 || wm[2] != c[2]) bad.append(" [model: " + c[0] + " is not a pure wave]");
      int[] gm = gains(c[0]);
      int[] want = c[0] == 0 ? new int[] {256, 0, 0} : c[0] == 42 ? new int[] {128, 128, 0} : c[0] == 85 ? new int[] {85, 85, 86} : new int[] {0, 128, 128};
      if (!Arrays.equals(gm, want)) bad.append(" [model: " + c[0] + " is not a corner mix]");
    }
    println(String.format("  %-64s %s%s", "S20 pure points: wave and gains, 0..127; corners 0, 42, 85, 127", bad.length() == 0 ? "OK" : "**FAIL**", bad));
    if (bad.length() != 0) fails++;
  }

  /** S21 (sym cfo_fobj): [FUNC] + knob. The agreed points: A, C, D (the pure waves) and E (the four
   *  mixes) 0, 42, 85, 127; G, H -24, -17, -12, -5, 0, +7, +12, +19, +24 semitones; a turn up goes to the
   *  first point above the value, down to the last below it, and past the last point it stays. B and F
   *  keep their own. */
  static final int[] SNAP_W = {0, 0x2a00, 0x5500, 0x7f00};
  static final int[] SNAP_D = {0x2800, 0x2f00, 0x3400, 0x3b00, 0x4000, 0x4700, 0x4c00, 0x5300, 0x5800};
  static long snapTo(int[] pts, long v, long d) {
    long r = v;
    if (d > 0) { for (int p : pts) if (p > v) { r = p; break; } }
    else if (d < 0) { for (int p : pts) if (p < v) r = p; }
    return r;
  }
  void snapCases() throws Exception {
    fresh();
    StringBuilder bad = new StringBuilder();
    if (rdn(0x40010054L, 4) != sym.get("cfo_fobj")) bad.append(" [0x40010052 does not lea cfo_fobj]");
    // 1. the display object cfo_fobj gives, by machine and id
    for (long mach : new long[] {5, 0, 4}) {
      realSets(mach);
      for (int id = 104; id <= 120; id++) {
        if (!runReal(sym.get("cfo_fobj"), new long[] {RET, id}, new String[] {"A2"}, new long[] {HSET}, new long[] {0x40065794L}, bad, "fobj " + id)) continue;
        int k = id - 108;
        boolean ours = mach == 5 && k >= 0 && k <= 7 && k != 1 && k != 5;
        long[] stock = lastCall(0x40065794L);
        if (ours) {
          long want = (k >= 6 ? sym.get("snap_dets") : sym.get("snap_waves")) - 0x44;
          if (stock != null || rd("D0") != want) bad.append(String.format(" [fobj machine %d id %d: 0x%x, want 0x%x]", mach, id, rd("D0"), want));
        } else if (stock == null || stock[1] != id) bad.append(String.format(" [fobj machine %d id %d: not the stock object]", mach, id));
        for (int i = 0; i < KEEP.length; i++) {
          long w = KEEP[i].equals("A2") ? HSET : 0x5a5a0000L + i;
          if (rd(KEEP[i]) != w) { bad.append(" [fobj: " + KEEP[i] + " not kept]"); break; }
        }
      }
    }
    // 2. end to end: ParameterSet::vfunc_11(set, id, delta, track, FUNC, out, 1, 1) on a CFOO sound
    long OUT = 0x439d4700L;
    int n = 0;
    realSets(5);
    for (int id = 108; id <= 115; id++) {
      int k = id - 108;
      if (k == 1 || k == 5) continue;
      int[] pts = k >= 6 ? SNAP_D : SNAP_W;
      long idx = rdn(0x401aa09cL + 0x34L * id + 4, 4);
      long[] curs = k >= 6 ? new long[] {0x2800, 0x2801, 0x2f00, 0x3e80, 0x4000, 0x4001, 0x5300, 0x57ff, 0x5800, 0x0000, 0x7f00}
                           : new long[] {0, 0x100, 0x2900, 0x2a00, 0x2b00, 0x5480, 0x5500, 0x7e00, 0x7f00};
      for (long cur : curs) for (long d : new long[] {1, 0x100, -1, -0x100}) {
        if (bad.length() > 600) break;
        wr(HSND + 0x14 + 2 * idx, cur, 2); wr(OUT, 0x5a, 1);
        String what = String.format("FUNC id %d from 0x%x by %d", id, cur, d);
        if (!runReal(0x40010026L, new long[] {RET, HSET, id, d & 0xffffffffL, 0, 1, OUT, 1, 1}, new String[] {}, new long[] {},
                     new long[] {WRITE, NOTICE}, bad, what)) continue;
        long want = snapTo(pts, cur, d);
        long[] r = rangeOf(5, id);
        want = Math.max(r[0], Math.min(r[1], want));		// the setter clamps (only a value left outside)
        long[] w = lastCall(WRITE);
        if (w == null) bad.append(" [" + what + ": no write]");
        else if (w[3] != want) bad.append(String.format(" [%s: wrote 0x%x, want 0x%x]", what, w[3], want));
        if (rdn(OUT, 1) != 1) bad.append(" [" + what + ": the special-action flag not set]");
        n++;
      }
    }
    println(String.format("  %-64s %s%s", "S21 FUNC + knob: objects by machine and id, " + n + " snaps end to end", bad.length() == 0 ? "OK" : "**FAIL**", bad));
    if (bad.length() != 0) fails++;
  }

  /** S22 (sym cfo_trkpop): the [TRK] popup. FUN_40093ab0(popup, "%s: %.16s", name, sample) with the
   *  machine in %d5: for POLY (4) and CFOO (5) the format becomes "%s", for every other machine (and -1,
   *  no sound) it stays; the other arguments, the stack and the kept registers unchanged. */
  void trkCases() throws Exception {
    fresh();
    StringBuilder bad = new StringBuilder();
    long pop = 0x40093ab0L, fmt = 0x401c41c0L;
    if (rdn(0x4003bd6aL, 2) != 0x4eb9L || rdn(0x4003bd6cL, 4) != sym.get("cfo_trkpop")) bad.append(" [0x4003bd6a is not jsr cfo_trkpop]");
    if (!cstr(sym.get("s_name_only")).equals("%s")) bad.append(" [the format is not %s]");
    if (!cstr(fmt).equals("%s: %.16s")) bad.append(" [0x401c41c0 is not the stock format]");
    for (long m : new long[] {-1, 0, 1, 2, 3, 4, 5, 6}) {
      long[] stack = {RET, 0x439d4800L, fmt, 0x401c6f07L, 0x439d4900L, m & 0xffffffffL, 0x2222, 0x3333};
      if (!runReal(sym.get("cfo_trkpop"), stack, new String[] {"D5"}, new long[] {m & 0xffffffffL}, new long[] {pop}, bad, "trk machine " + m)) continue;
      long[] c = lastCall(pop);
      long want = m == 4 || m == 5 ? sym.get("s_name_only") : fmt;
      if (c == null) { bad.append(" [machine " + m + ": FUN_40093ab0 not reached]"); continue; }
      if (c[1] != 0x439d4800L || c[2] != want || c[3] != 0x401c6f07L || c[4] != 0x439d4900L)
        bad.append(String.format(" [machine %d: args 0x%x 0x%x 0x%x 0x%x]", m, c[1], c[2], c[3], c[4]));
      if (rdn(spAt + 20, 4) != (m & 0xffffffffL) || rdn(spAt + 24, 4) != 0x2222) bad.append(" [machine " + m + ": the caller's stack changed]");
      for (int i = 0; i < KEEP.length; i++) {
        long w = KEEP[i].equals("D5") ? m & 0xffffffffL : 0x5a5a0000L + i;
        if (rd(KEEP[i]) != w) { bad.append(" [machine " + m + ": " + KEEP[i] + " not kept]"); break; }
      }
    }
    println(String.format("  %-64s %s%s", "S22 TRK popup: machine name alone for POLY and CFOO", bad.length() == 0 ? "OK" : "**FAIL**", bad));
    if (bad.length() != 0) fails++;
  }

  /** S23 (sym cfo_lfocell): CFOO's names as LFO destinations. cur_machine with the project lookups
   *  stubbed and the stock FUN_4000d9c8 / FUN_4002200a run on a holder at the kit's +0x60 + 200 x track;
   *  then each of the four routines with cur_machine stubbed to 5 and to 0, for ids 106..117 (and 0):
   *  "CFOO" and CFOO's short or long name only for 108..115 on 5, the descriptor's otherwise; and the
   *  four sites in place. */
  void lfoCases() throws Exception {
    fresh();
    StringBuilder bad = new StringBuilder();
    long CM = sym.get("cur_machine"), DESC = 0x401aa09cL;
    long PROJ = 0x439d4a00L, XO = 0x439d4a40L, KIT = 0x439d5000L;
    long cs = sym.get("cfoo_short"), csn = sym.get("cfoo_short_names"), cln = sym.get("cfoo_long_names");
    // the sites
    if (rdn(0x40065deeL, 4) != sym.get("cfo_lfogrp")) bad.append(" [0x40065dec does not call cfo_lfogrp]");
    if (rdn(0x40065e5eL, 2) != 0x4eb9L || rdn(0x40065e60L, 4) != sym.get("cfo_lfocell") || rdn(0x40065e64L, 4) != 0x2f004e71L) bad.append(" [0x40065e5e]");
    if (rdn(0x400a44d0L, 2) != 0x4eb9L || rdn(0x400a44d2L, 4) != sym.get("cfo_lfolist")) bad.append(" [0x400a44d0]");
    if (rdn(0x400a454cL, 2) != 0x4eb9L || rdn(0x400a454eL, 4) != sym.get("cfo_lfolist2")) bad.append(" [0x400a454c]");
    if (!cstr(cs).equals("CFOO")) bad.append(" [cfoo_short is not CFOO]");
    // 1. cur_machine
    long[] projStubs = {0x40138882L, 0x40014d86L, 0x4001d24eL, 0x40014d92L};
    for (long m : new long[] {5, 0}) for (long t : new long[] {0, 3, 7, 8}) {
      realSets(m);
      stubRet.clear();
      stubRet.put(0x40138882L, PROJ); stubRet.put(0x40014d86L, XO); stubRet.put(0x4001d24eL, t); stubRet.put(0x40014d92L, KIT);
      if (t <= 7) wr(KIT + 0x60 + 200 * t, HVT, 4);
      if (!runReal(CM, new long[] {RET}, new String[] {}, new long[] {}, projStubs, bad, "cur_machine track " + t)) continue;
      long want = t <= 7 ? m : 0xffffffffL;
      if (rd("D0") != want) bad.append(String.format(" [cur_machine machine %d track %d: 0x%x]", m, t, rd("D0")));
      long[] a = lastCall(0x40014d86L), b = lastCall(0x4001d24eL), c = lastCall(0x40014d92L);
      if (a == null || a[1] != PROJ || b == null || b[1] != XO || c == null || c[1] != PROJ) bad.append(" [cur_machine: the lookups' arguments]");
      if (t <= 7 && !machArgs.equals(Collections.singletonList(KIT + 0x60 + 200 * t))) bad.append(" [cur_machine: FUN_4002200a got " + machArgs + "]");
      for (int i = 0; i < KEEP.length; i++) if (rd(KEEP[i]) != 0x5a5a0000L + i) { bad.append(" [cur_machine: " + KEEP[i] + " not kept]"); break; }
    }
    stubRet.clear();
    // 2. the four routines, cur_machine stubbed
    int n = 0;
    for (long m : new long[] {5, 0}) {
      stubRet.put(CM, m);
      for (int id = 106; id <= 118 && bad.length() < 600; id++) {
        int idc = id == 118 ? 0 : id;			// 118 stands for an id clamped to 0
        boolean ours = m == 5 && idc >= 108 && idc <= 115;
        int k = idc - 108;
        long sname = ours ? rdn(csn + 4L * k, 4) : rdn(DESC + 52L * idc + 0x30, 4);
        long lname = ours ? rdn(cln + 4L * k, 4) : rdn(DESC + 52L * idc + 0x28, 4);
        String w = "machine " + m + " id " + idc;
        // the picture's group
        if (runReal(sym.get("cfo_lfogrp"), new long[] {RET, 0x439d4b00L, 0x401c6e7eL, 0x439d4b40L}, new String[] {"D3"}, new long[] {idc},
                    new long[] {CM, 0x4017af20L}, bad, "grp " + w)) {
          long[] c = lastCall(0x4017af20L);
          if (c == null || c[1] != 0x439d4b00L || c[2] != (ours ? cs : 0x401c6e7eL) || c[3] != 0x439d4b40L) bad.append(" [grp " + w + ": the string's arguments]");
          if (rd("D3") != idc) bad.append(" [grp: d3 not kept]");
        }
        // the picture's name
        if (runReal(sym.get("cfo_lfocell"), new long[] {RET}, new String[] {"D3", "A0"}, new long[] {idc, DESC}, new long[] {CM}, bad, "cell " + w)) {
          if (rd("D0") != sname) bad.append(String.format(" [cell %s: 0x%x, want 0x%x]", w, rd("D0"), sname));
          for (int i = 0; i < KEEP.length; i++) {
            long want = KEEP[i].equals("D3") ? idc : 0x5a5a0000L + i;
            if (rd(KEEP[i]) != want) { bad.append(" [cell: " + KEEP[i] + " not kept]"); break; }
          }
        }
        // the list: long name, then the short-name fallback
        for (int which = 0; which < 2; which++) {
          String r = which == 0 ? "cfo_lfolist" : "cfo_lfolist2";
          String[] rn = which == 0 ? new String[] {"D0", "D2", "A3", "D6", "A0"} : new String[] {"D2", "A3", "D6", "A0"};
          long[] rv = which == 0 ? new long[] {52L * idc, idc, DESC, 0x401c6f07L, 0x439d4c00L} : new long[] {52L * idc, DESC, 0x401c6f07L, 0x439d4c00L};
          if (!runReal(sym.get(r), new long[] {RET}, rn, rv, new long[] {CM}, bad, r + " " + w)) continue;
          long q = rd("SP");
          long wantName = which == 0 ? lname : sname;
          if (q != spAt - 4 || rdn(q, 4) != (ours ? cs : 0x401c6f07L) || rdn(q + 4, 4) != wantName)
            bad.append(String.format(" [%s %s: sp %d, prefix 0x%x name 0x%x, want 0x%x]", r, w, q - spAt, rdn(q, 4), rdn(q + 4, 4), wantName));
          if (rd("A0") != 0x439d4c00L) bad.append(" [" + r + ": a0 not kept]");
          for (int i = 0; i < KEEP.length; i++) {
            String kk = KEEP[i];
            long want = kk.equals("D2") ? (which == 0 ? idc : 52L * idc) : kk.equals("A3") ? DESC : kk.equals("D6") ? 0x401c6f07L : 0x5a5a0000L + i;
            if (rd(kk) != want) { bad.append(" [" + r + ": " + kk + " not kept]"); break; }
          }
        }
        n++;
      }
    }
    stubRet.clear();
    println(String.format("  %-64s %s%s", "S23 LFO destinations: cur_machine, picture and list, " + n + " ids", bad.length() == 0 ? "OK" : "**FAIL**", bad));
    if (bad.length() != 0) fails++;
  }

  /** S25 (sym cfo_srcfmt): the SRC page's title (FUN_4003a638, the machine at %sp@(12)) and popup
   *  (SamplePageView::vfunc_2, the machine in %d4) push "%s" for POLY (4) and CFOO (5) and the stock
   *  "%s: %.16s" otherwise, where the pea they replace put it; the rest of the stack and the kept
   *  registers unchanged; and the two sites in place. */
  void srcCases() throws Exception {
    fresh();
    StringBuilder bad = new StringBuilder();
    long fmt = 0x401c41c0L, only = sym.get("s_name_only");
    if (rdn(0x4003a6ceL, 2) != 0x4eb9L || rdn(0x4003a6d0L, 4) != sym.get("cfo_srctitle")) bad.append(" [0x4003a6ce]");
    if (rdn(0x4003b512L, 2) != 0x4eb9L || rdn(0x4003b514L, 4) != sym.get("cfo_srcfmt")) bad.append(" [0x4003b512]");
    for (String r : new String[] {"cfo_srctitle", "cfo_srcfmt"}) {
      for (long m : new long[] {-1, 0, 1, 2, 3, 4, 5, 6}) {
        long mm = m & 0xffffffffL;
        long[] stack = {RET, 0x401c6f07L, 0x439d4900L, r.equals("cfo_srctitle") ? mm : 0x3333, 0x439d4800L};
        String[] rn = r.equals("cfo_srcfmt") ? new String[] {"D4"} : new String[] {};
        long[] rv = r.equals("cfo_srcfmt") ? new long[] {mm} : new long[] {};
        if (!runReal(sym.get(r), stack, rn, rv, new long[] {}, bad, r + " machine " + m)) continue;
        long want = m == 4 || m == 5 ? only : fmt;
        if (rd("SP") != spAt || rdn(spAt, 4) != want) bad.append(String.format(" [%s machine %d: sp %d, format 0x%x]", r, m, rd("SP") - spAt, rdn(spAt, 4)));
        for (int i = 1; i < stack.length; i++) if (rdn(spAt + 4L * i, 4) != stack[i]) { bad.append(" [" + r + ": the stack changed]"); break; }
        for (int i = 0; i < KEEP.length; i++) {
          long w = KEEP[i].equals("D4") && r.equals("cfo_srcfmt") ? mm : 0x5a5a0000L + i;
          if (rd(KEEP[i]) != w) { bad.append(" [" + r + ": " + KEEP[i] + " not kept]"); break; }
        }
      }
    }
    println(String.format("  %-64s %s%s", "S25 SRC page title and popup: machine name alone for POLY, CFOO", bad.length() == 0 ? "OK" : "**FAIL**", bad));
    if (bad.length() != 0) fails++;
  }

  /** S19: the objects behind a sound's parameter set, laid out as the firmware has them. A
   *  SoundParameterSet (the stock vtable 0x4017ee58) keeps its sound holder at +0x10; the holder's method
   *  at +0x28 returns the sound (here a stub, movel #HSND,%d0 ; rts); the sound holds its values (16 bit)
   *  at +0x14 + 2 x the descriptor's index and its machine at +0x7e. FUN_4002200a, SoundParameterSet's
   *  methods and the stock range code then run unstubbed. OSET is another parameter set (Trig's vtable)
   *  with the same holder, NSET a SoundParameterSet without one. Before S19 every case here stubbed
   *  FUN_4002200a and took the set for a holder, which the firmware's sets are not. */
  static final long SOUNDSET_VT = 0x4017ee58L, TRIGSET_VT = 0x4017ef58L;
  static final long HSET = 0x439d4100L, HHOLD = 0x439d4140L, HVT = 0x439d4180L, HSTUB = 0x439d4200L,
      HSND = 0x439d4300L, OSET = 0x439d4400L, NSET = 0x439d4440L, RDEST = 0x439d4480L;
  static final long WRITE = 0x40010690L, NOTICE = 0x400104f8L;	// SoundParameterSet::vfunc_29, ParameterSet::vfunc_24
  boolean realSets = false;
  void realSets(long mach) throws Exception {
    wr(HSET, SOUNDSET_VT, 4); wr(HSET + 0x10, HHOLD, 4);
    wr(HHOLD, HVT, 4); wr(HVT + 0x28, HSTUB, 4);
    wr(HSTUB, 0x203cL, 2); wr(HSTUB + 2, HSND, 4); wr(HSTUB + 6, 0x4e75L, 2);
    wr(HSND + 0x7e, mach, 1);
    wr(OSET, TRIGSET_VT, 4); wr(OSET + 0x10, HHOLD, 4);
    wr(NSET, SOUNDSET_VT, 4); wr(NSET + 0x10, 0, 4);
  }
  /** {min, max, default} for an id on a machine: CFOO's table for 108..115 on machine 5, else the
   *  descriptor's (machine -1: no machine) */
  long[] rangeOf(long mach, int id) throws Exception {
    if (mach == 5 && id >= 108 && id <= 115) return CFOO_RANGES2[id - 108];
    long rec = 0x401aa09cL + 0x34L * id + 8;
    return new long[] {rdn(rec, 4), rdn(rec + 4, 4), rdn(rec + 8, 4)};
  }
  /** run from pc with the stack given (top first) and the registers named, until RET; the PCs in stubs
   *  return at once with %d0 = 0, their first four arguments kept in calls. false if it faulted or ran
   *  away. KEEP registers start as 0x5a5a0000 + i unless named. */
  List<long[]> calls = new ArrayList<>();
  List<Long> machArgs = new ArrayList<>();	// FUN_4002200a's argument at each entry (not stubbed)
  Map<Long, Long> stubRet = new HashMap<>();	// what a stubbed PC returns in %d0 (0 if not listed)
  long spAt = 0;
  boolean runReal(long pc, long[] stack, String[] rn, long[] rv, long[] stubs, StringBuilder bad, String what) throws Exception {
    long sp = SP0 - 4L * stack.length;
    for (int i = 0; i < stack.length; i++) wr(sp + 4L * i, stack[i], 4);
    spAt = sp;
    emu.writeRegister("SP", sp); emu.writeRegister("PC", pc);
    for (int i = 0; i < KEEP.length; i++) emu.writeRegister(KEEP[i], 0x5a5a0000L + i);
    for (int i = 0; i < rn.length; i++) emu.writeRegister(rn[i], rv[i]);
    calls.clear(); machArgs.clear();
    for (int s = 0; s < 20000; s++) {
      long p = emu.getExecutionAddress().getOffset();
      if (p == RET) return true;
      if (p == MACHQ) machArgs.add(rdn(rd("SP") + 4, 4));
      boolean stub = false;
      for (long st : stubs) if (p == st) stub = true;
      if (stub) {
        long q = rd("SP");
        calls.add(new long[] {p, rdn(q + 4, 4), rdn(q + 8, 4), rdn(q + 12, 4), rdn(q + 16, 4)});
        emu.writeRegister("D0", stubRet.getOrDefault(p, 0L)); emu.writeRegister("PC", rdn(q, 4)); emu.writeRegister("SP", q + 4);
        continue;
      }
      if (!emu.step(monitor)) { bad.append(" [" + what + ": FAULT " + emu.getLastError() + " at 0x" + Long.toHexString(p) + "]"); return false; }
    }
    bad.append(" [" + what + ": ran away]");
    return false;
  }
  long[] lastCall(long pc) {
    long[] w = null;
    for (long[] c : calls) if (c[0] == pc) w = c;
    return w;
  }

  /** S19: cfo_range by each entry on the objects above: a sound's set (the machine through its holder),
   *  FUN_400220fc's holder itself (in %a2), another parameter set and a set without a holder (stock),
   *  for machines 5, 0, 4 and ids 106..117; and the nine call sites pointing at their entries. */
  void rangeCases3() throws Exception {
    fresh();
    StringBuilder bad = new StringBuilder();
    String[][] sites = {{"4000f536", "cfo_range"}, {"4000ff22", "cfo_range"}, {"400100c6", "cfo_range"},
        {"40010156", "cfo_range"}, {"4002213a", "cfo_range"}, {"4000f5fe", "cfo_range9"},
        {"4000ffae", "cfo_range4"}, {"40010d52", "cfo_range"}, {"400326ac", "cfo_range_a3"}};
    for (String[] s : sites) {
      long a = Long.parseLong(s[0], 16);
      if (!sym.containsKey(s[1])) { bad.append(" [no " + s[1] + "]"); continue; }
      if (rdn(a, 4) != sym.get(s[1])) bad.append(String.format(" [0x%08x does not point at %s]", a, s[1]));
    }
    int n = 0;
    for (String ent : new String[] {"cfo_range", "cfo_range9", "cfo_range4", "cfo_range_a3"}) {
      if (!sym.containsKey(ent)) continue;
      for (long[] ob : new long[][] {{HSET, 1}, {HHOLD, 1}, {OSET, 0}, {NSET, 0}}) {
        if (ob[0] == HHOLD && !ent.equals("cfo_range")) continue;	// only FUN_400220fc passes a holder
        for (long mach : new long[] {5, 0, 4}) {
          realSets(mach);
          for (int id = 106; id <= 117 && bad.length() < 500; id++) {
            wr(RDEST, 0x5a5a5a5aL, 4); wr(RDEST + 4, 0x5a5a5a5aL, 4); wr(RDEST + 8, 0x5a5a5a5aL, 4);
            int len = ent.equals("cfo_range9") ? 10 : ent.equals("cfo_range4") ? 8 : 2;
            long[] stack = new long[len];
            for (int i = 0; i < len; i++) stack[i] = 0x11110000L + i;
            stack[0] = RET; stack[1] = id;
            if (len > 2) stack[len - 1] = ob[0];		// %sp@(36) or %sp@(28) at the entry
            long a2 = ent.equals("cfo_range") ? ob[0] : ent.equals("cfo_range9") ? sym.get(ent) : DPAGE;
            long a3 = ent.equals("cfo_range_a3") ? ob[0] : 0x5a5a0007L;
            String what = String.format("%s object 0x%x machine %d id %d", ent, ob[0], mach, id);
            if (!runReal(sym.get(ent), stack, new String[] {"A0", "A2", "A3"}, new long[] {RDEST, a2, a3}, new long[] {}, bad, what)) continue;
            long[] want = rangeOf(ob[1] == 1 ? mach : -1, id);
            for (int f = 0; f < 3; f++)
              if (rdn(RDEST + 4 * f, 4) != want[f]) bad.append(String.format(" [%s field %d: 0x%x, want 0x%x]", what, f, rdn(RDEST + 4 * f, 4), want[f]));
            if (rd("D0") != RDEST || rd("SP") != spAt + 4) bad.append(" [" + what + ": %d0 or SP]");
            for (int i = 0; i < KEEP.length; i++) {
              long w = KEEP[i].equals("A2") ? a2 : KEEP[i].equals("A3") ? a3 : 0x5a5a0000L + i;
              if (rd(KEEP[i]) != w) { bad.append(" [" + what + ": " + KEEP[i] + " not kept]"); break; }
            }
            for (int i = 1; i < len; i++) if (rdn(spAt + 4L * i, 4) != stack[i]) { bad.append(" [" + what + ": caller's stack changed]"); break; }
            // FUN_4002200a gets the holder, once, for an SRC id on a sound's set or the holder; never otherwise
            boolean src = id >= 108 && id <= 115;
            List<Long> wantArgs = src && ob[1] == 1 ? Collections.singletonList(HHOLD) : Collections.<Long>emptyList();
            if (!machArgs.equals(wantArgs)) bad.append(" [" + what + ": FUN_4002200a got " + machArgs + "]");
            n++;
          }
        }
      }
    }
    println(String.format("  %-64s %s%s", "S19 ranges: " + n + " calls, each entry, real sets and holder", bad.length() == 0 ? "OK" : "**FAIL**", bad));
    if (bad.length() != 0) fails++;
  }

  /** S19, end to end through the stock code on a sound's set, machines 5 and 0: the validity test
   *  (SoundParameterSet::vfunc_9), the encoder's edit (ParameterSet::vfunc_11 -> vfunc_8 ->
   *  FUN_4000fef6) and the reset to the default (ParameterSet::vfunc_4). Only the value write
   *  (SoundParameterSet::vfunc_29) and the change notice after it (ParameterSet::vfunc_24) are stubbed;
   *  the value written is checked against the machine's range. */
  void editCases3() throws Exception {
    fresh();
    StringBuilder bad = new StringBuilder();
    int nv = 0, ne = 0, nr = 0;
    for (long mach : new long[] {5, 0}) {
      realSets(mach);
      for (int id = 107; id <= 116; id++) {
        long[] r = rangeOf(mach, id);
        long idx = rdn(0x401aa09cL + 0x34L * id + 4, 4);
        String m = "machine " + mach + " id " + id;
        for (long v : new long[] {r[0] - 1, r[0], r[1], r[1] + 1, 0, 0x300, 0x400, 0x2800, 0x4000, 0x5800, 0x5801, 0x7800, 0x7f00, 0x7f01}) {
          if (v < 0 || bad.length() > 500) continue;
          if (!runReal(0x40011576L, new long[] {RET, HSET, id, v}, new String[] {}, new long[] {}, new long[] {}, bad, "validity " + m)) continue;
          boolean valid = (rd("D0") & 0xff) != 0, want = v >= r[0] && v <= r[1];
          if (valid != want) bad.append(String.format(" [validity %s value 0x%x: %s]", m, v, valid ? "valid" : "invalid"));
          nv++;
        }
        for (long cur : new long[] {r[0], r[1], 0x0300, 0x2800, 0x4000, 0x5800}) {
          for (long d : new long[] {-0x100, 0x100, -1, 1, 0x1900, 0x7f00, -0x7f00}) {
            if (bad.length() > 500) break;
            wr(HSND + 0x14 + 2 * idx, cur, 2);
            String what = String.format("edit %s from 0x%x by %d", m, cur, d);
            if (!runReal(0x40010026L, new long[] {RET, HSET, id, d & 0xffffffffL, 0, 0, 0, 1, 1}, new String[] {}, new long[] {},
                         new long[] {WRITE, NOTICE}, bad, what)) continue;
            long want = Math.max(r[0], Math.min(r[1], cur + d));
            long[] w = lastCall(WRITE);
            if (w == null) bad.append(" [" + what + ": no write]");
            else if (w[1] != HSET || w[2] != id || w[3] != want) bad.append(String.format(" [%s: wrote 0x%x, want 0x%x]", what, w[3], want));
            ne++;
          }
        }
        if (runReal(0x4000ff9aL, new long[] {RET, HSET, id}, new String[] {}, new long[] {}, new long[] {WRITE, NOTICE}, bad, "reset " + m)) {
          long[] w = lastCall(WRITE);
          if (w == null) bad.append(" [reset " + m + ": no write]");
          else if (w[2] != id || w[3] != r[2]) bad.append(String.format(" [reset %s: wrote 0x%x, want 0x%x]", m, w[3], r[2]));
          nr++;
        }
      }
    }
    println(String.format("  %-64s %s%s", "S19 edits: " + nv + " validity, " + ne + " encoder, " + nr + " reset, stock code", bad.length() == 0 ? "OK" : "**FAIL**", bad));
    if (bad.length() != 0) fails++;
  }

  /** machine 5 mode: FUN_400657cc (with the layout hook from the load file) for machines 0..7. */
  void layoutCases() throws Exception {
    StringBuilder bad = new StringBuilder();
    fresh();
    long base = 0x4197ded8L, slice = 0x4197df5cL;
    for (int m = 0; m < 8; m++) {
      long sp = SP0;
      sp -= 4; wr(sp, m, 4); sp -= 4; wr(sp, RET, 4);
      emu.writeRegister("SP", sp); emu.writeRegister("PC", 0x400657ccL);
      boolean done = false;
      for (int i = 0; i < 200; i++) {
        if (emu.getExecutionAddress().getOffset() == RET) { done = true; break; }
        if (!emu.step(monitor)) { bad.append(" [FAULT]"); break; }
      }
      long want = m < 4 ? base + 0x2cL * m : m == 5 ? base : slice;
      long got = rd("D0");
      if (!done) bad.append(" [machine " + m + ": no return]");
      else if (got != want) bad.append(String.format(" [machine %d: 0x%08x, want 0x%08x]", m, got, want));
      if (done && rd("SP") != sp + 4) bad.append(" [machine " + m + ": SP]");
    }
    println(String.format("  %-64s %s%s", "layout: 0-3 stock, 4/6/7 SLICE's record, 5 ONESHOT's", bad.length() == 0 ? "OK" : "**FAIL**", bad));
    if (bad.length() != 0) fails++;
  }

  void run(String label, int ticks, int[] machine, int[] slot, int[] note, int tune, int play, int br,
           int strt, int len, int loop, int level) throws Exception {
    fresh();
    for (int k = 0; k < ticks; k++)
      if (!tick(label + " (tick " + k + ")", machine, slot, note, tune, play, br, strt, len, loop, level, false)) return;
    println(String.format("  %-64s OK", label + ", " + ticks + " ticks"));
  }

  public void run() throws Exception {
    String[] args = getScriptArgs();
    if (args.length < 1) { printerr("usage: EmuCfoOscillator.java <file.load> [machine5]"); return; }
    m5 = args.length > 1 && args[1].equals("machine5");
    boolean names = m5 && args.length > 2 && args[2].equals("names");
    boolean icon = names && args.length > 3 && args[3].equals("icon");
    slots = icon && args.length > 4 && args[4].equals("slots");
    for (String line : Files.readAllLines(Paths.get(args[0]))) {
      String[] p = line.trim().split("\\s+");
      if (p.length == 3 && p[0].equals("sym")) sym.put(p[1], Long.parseLong(p[2], 16));
      else if (p.length == 2) {
        byte[] b = new byte[p[1].length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(p[1].substring(2 * i, 2 * i + 2), 16);
        secAddr.add(new long[] {Long.parseLong(p[0], 16)}); secData.add(b);
      }
    }
    boolean knobs = m5 && sym.containsKey("cfo_range");
    v2 = knobs && sym.containsKey("cfo_encobj");
    realSets = knobs && (sym.containsKey("set_machine") || Arrays.asList(args).contains("realsets"));
    pure = sym.containsKey("segfrac");
    corners = sym.containsKey("wave_cnames");
    if (knobs) {
      CFOO_SHORT = new String[] {"WAV1", "FMSR", "WAV2", "WAV3", "MIX", "FM", "DET2", "DET3"};
      CFOO_LONG = new String[] {"OSC1 Wave", "FM Source", "OSC2 Wave", "OSC3 Wave", "Osc Mix", "FM Amount", "OSC2 Detune", "OSC3 Detune"};
    }
    fresh();
    for (int i = 0; i < pitchTab.length; i++) pitchTab[i] = rd32s(PITCH + 4L * i);
    emu.readMemory(toAddr(WAVES), 1024);
    waves = emu.readMemory(toAddr(WAVES), 1024);
    for (int i = 0; i < 12; i++) mix[i] = (short) rdn(MIXPTS + 2L * i, 2);
    println("=== cfo_oscillator: the oscillator code against the model" + (m5 ? ", synth on machine 5" : "") + " ===");
    if (pitchTab[10752] != 0x20000000) { println("  pitch table at 0x4019b4c0 is not 2^29 at note 60: **FAIL**"); fails++; }

    int[] none = {1, 2, 3, 1, 2, 3, 1, 2}, noSlot = {5, 5, 5, 5, 5, 5, 5, 5};
    int[] zero = new int[8], c4 = {60, 60, 60, 60, 60, 60, 60, 60};
    int[] oneSynth = {0, 1, 0, 2, 3, 0, 1, 0}, oneSlot = {0, 0, 7, 0, 0, 9, 0, 3};
    int[] notes = {60, 48, 72, 36, 84, 60, 67, 30};
    int MAX = 0x7fffffff;

    if (v2) knobCases2(none, noSlot, zero, c4, notes, MAX);
    else if (knobs) knobCases(none, noSlot, zero, c4, notes, MAX);
    else {
    run("no synth track: every block untouched", 3, none, noSlot, c4, 0x4000, 0, 0, 0, 0, 0, MAX);
    run("ONESHOT with a sample: untouched", 3, zero, noSlot, c4, 0x4000, 0, 0, 0, 0, 0, MAX);
    run("tracks 0, 1, 6 synth (ONESHOT, SAMP OFF), others not", 40, oneSynth, oneSlot, notes, 0x4000, 3, 0, 0, 0, 0, MAX);
    run("all 8 synth, OSC1 only, SIN, no FM", 60, zero, zero, notes, 0x4000, 3, 0, 0, 0, 0, MAX);
    run("TUNE +7 and -12 semitones", 30, zero, zero, notes, 0x4000 + 7 * 256, 3, 0, 0, 0, 0, MAX);
    run("TUNE -12", 30, zero, zero, notes, 0x4000 - 12 * 256, 3, 0, 0, 0, 0, MAX);
    run("morph: STRT 40 (TRI), 80 (SAW), 120 (SQR)", 20, zero, zero, notes, 0x4000, 3, 0, 40, 0, 0, MAX);
    run("morph STRT 80", 20, zero, zero, notes, 0x4000, 3, 0, 80, 0, 0, MAX);
    run("morph STRT 120", 20, zero, zero, notes, 0x4000, 3, 0, 120, 0, 0, MAX);
    run("mix BR 42 (1+2), 85 (1+2+3), 127 (2+3)", 20, zero, zero, notes, 0x4000, 3, 42, 0, 0, 30, MAX);
    run("mix BR 85", 20, zero, zero, notes, 0x4000, 3, 85, 0, 0, 30, MAX);
    run("mix BR 127", 20, zero, zero, notes, 0x4000, 3, 127, 0, 0, 30, MAX);
    run("FM from OSC2 (PLAY 0), LEN 60", 40, zero, zero, notes, 0x4000, 0, 0, 0, 60, 0, MAX);
    run("FM from OSC2+3 (PLAY 1), LEN 120, waves morphed", 40, zero, zero, notes, 0x4000, 1, 60, 70, 120, 100, MAX);
    run("FM from OSC3 (PLAY 2), LEN 120", 40, zero, zero, notes, 0x4000, 2, 0, 0, 120, 0, MAX);
    run("level: half (velocity/LEV)", 10, zero, zero, notes, 0x4000, 3, 85, 60, 30, 60, 0x40000000);
    run("level 0", 5, zero, zero, notes, 0x4000, 3, 85, 60, 30, 60, 0);
    activeMask = 0xff; trigMask = 0x0a;
    run("de-click: voices on, tracks 1 and 3 trig next tick (level 0)", 5, zero, zero, notes, 0x4000, 3, 85, 60, 30, 60, MAX);
    activeMask = 0x0f; trigMask = 0xf0;
    run("no de-click for a voice that is off", 5, zero, zero, notes, 0x4000, 3, 85, 60, 30, 60, MAX);
    activeMask = 0; trigMask = 0;
    {	// the ramp: full level, then a quarter, then the de-click's 0, then full again
      fresh();
      int[] lv = {MAX, MAX, MAX, 0x20000000, 0x20000000, MAX, MAX};
      int[] tm = {0, 0, 0, 0, 0x02, 0, 0};
      boolean ok = true;
      activeMask = 0xff;
      for (int k = 0; k < lv.length && ok; k++) {
        trigMask = tm[k];
        ok = tick("level ramps between ticks (tick " + k + ")", zero, zero, notes, 0x4000, 3, 85, 60, 30, 60, lv[k], false);
      }
      activeMask = 0; trigMask = 0;
      if (ok) println(String.format("  %-64s OK", "level ramps between ticks, " + lv.length + " ticks"));
    }
    seedLevels = 0xdeadbeefL;
    run("level RAM not set at boot: no ramp on the first tick", 3, zero, zero, notes, 0x4000, 3, 85, 60, 30, 60, MAX);
    seedLevels = 0;
    }
    if (names) nameCases();
    if (icon) iconCases();
    if (m5) {
      layoutCases();
      polyVoiceCase();
      if (sym.containsKey("slot_machine") || slots) slotCases();
      if (realSets) rangeCases3();
      else if (knobs) rangeCases();
      if (v2) displayCases2();
      else if (knobs && sym.containsKey("cfo_text")) displayCases();
      if (realSets) editCases3();
      if (pure) pureCases();
      if (sym.containsKey("cfo_fobj")) snapCases();
      if (sym.containsKey("cfo_trkpop")) trkCases();
      if (sym.containsKey("cfo_lfocell")) lfoCases();
      if (sym.containsKey("cfo_srcfmt")) srcCases();
      remap = false;
      run("machine 5: ONESHOT with SAMP OFF is left alone", 3, zero, zero, c4, 0x4000, 3, 0, 0, 0, 0, MAX);
      remap = true;
    }
    if (v2) K = new int[] {0, 0, 0, 0, 0, 0, 0x4000, 0x4000};	// S18's defaults: OSC1 SIN alone
    else if (knobs) K = new int[] {4, 0, 0, 0, 0, 0, 48, 50};	// CFOO's defaults: OSC1 SIN alone
    run("extreme pitch: note 0 and 127 clamp", 10, zero, zero, new int[] {0, 127, 0, 127, 0, 127, 0, 127}, 0x4000, 1, 100, 120, 120, 120, MAX);

    // absolute pitch and level: OSC1 SIN, note 60, track 0, 200 ticks
    fresh();
    int[] only0 = {0, 1, 1, 1, 1, 1, 1, 1};
    List<Integer> sig = new ArrayList<>();
    boolean ok = true;
    for (int k = 0; k < 200 && ok; k++) {
      ok = tick("pitch run", only0, zero, c4, 0x4000, 3, 0, 0, 0, 0, MAX, false);
      for (int i = 0; i < 32; i++) sig.add(rd32s(A18 + 4 * i));
    }
    int ups = 0, first = -1, last = -1, peak = 0;
    for (int i = 1; i < sig.size(); i++) {
      if (sig.get(i - 1) < 0 && sig.get(i) >= 0) { ups++; if (first < 0) first = i; last = i; }
      peak = Math.max(peak, Math.abs(sig.get(i)));
    }
    double hz = (ups - 1) * 48000.0 / (last - first);
    boolean pitchOk = ok && Math.abs(hz - 261.4) < 0.5 && peak > 1000000000 && peak < 1073741824;
    println(String.format("  %-64s %s", String.format("OSC1 SIN at note 60: %.2f Hz (C4 = 261.63), peak %d", hz, peak),
                          pitchOk ? "OK" : "**FAIL**"));
    if (!pitchOk) fails++;

    println(String.format("  instructions per tick, at most (all 8 tracks synth in the worst case run): %d", maxSteps));
    if (emu != null) emu.dispose();
    println(fails == 0
        ? "=== ALL CASES PASS: untouched tracks kept, synth samples equal the model, pitch and level right, stack and registers kept ==="
        : "=== " + fails + " FAILURE(S) ABOVE ===");
  }
}
