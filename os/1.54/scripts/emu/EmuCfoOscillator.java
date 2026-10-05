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
//   ./scripts/ghidra_emu.sh 1.54 EmuCfoOscillator <file.load> [machine5 [names [icon]]]
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
  int[] wave(int w) { int p = 3 * w; return new int[] {(p >> 7) * 256, ((p >> 7) + 1) * 256, (p & 127) * 2}; }
  int samp(int[] wv, int acc) { int i = acc >>> 24; int a = waves[wv[0] + i], b = waves[wv[1] + i]; return a + (((b - a) * wv[2]) >> 8); }
  int[] gains(int v) {
    int p = 3 * v, s = p >> 7, fr = (p & 127) * 2; int[] g = new int[3];
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

  // ---- one case: several ticks with fixed settings
  static final String[] KEEP = {"D2","D3","D4","D5","D6","D7","A2","A3","A4","A5","A6"};

  int[][] lastOut = new int[8][];
  boolean m5 = false, remap = true;
  int maxSteps = 0;
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
      wr(NOTES + 4 * t, (long) note[t] << 16, 4);
      wr(e + 0x42, LEVW, 2);
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
        if (lev != LEVW) bad.append(String.format(" [level LEV 0x%04x, want 0x%04x]", lev, LEVW));
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
      int[] want = synth ? model(t, note[t], tune, play, br, strt, len, loop, lv) : null;
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
  static final String[] CFOO_SHORT = {"TUNE", "FMSR", "MIX", "SAMP", "WAV1", "FM", "WAV2", "LEV"};
  static final String[] CFOO_LONG = {"Tune", "FM Source", "Osc Mix", "Sample Slot", "OSC1 Wave", "FM Amount", "OSC2+3 Wave", "Level"};

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
    for (String line : Files.readAllLines(Paths.get(args[0]))) {
      String[] p = line.trim().split("\\s+");
      if (p.length == 3 && p[0].equals("sym")) sym.put(p[1], Long.parseLong(p[2], 16));
      else if (p.length == 2) {
        byte[] b = new byte[p[1].length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(p[1].substring(2 * i, 2 * i + 2), 16);
        secAddr.add(new long[] {Long.parseLong(p[0], 16)}); secData.add(b);
      }
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
    if (names) nameCases();
    if (icon) iconCases();
    if (m5) {
      layoutCases();
      remap = false;
      run("machine 5: ONESHOT with SAMP OFF is left alone", 3, zero, zero, c4, 0x4000, 3, 0, 0, 0, 0, MAX);
      remap = true;
    }
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
