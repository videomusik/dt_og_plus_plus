// filter_page2: run the FILTER page 2 hook (VED, KEY) of a stage image in Ghidra's p-code emulator at its
// site in the filter stage, and compare what the stage gets with a model written here from the
// description; check the page's data and the save path on the image's own bytes.
//
// The hook: the filter stage FUN_40072844 calls the envelope level getter FUN_40073412 at 0x400728a2
// with the track pushed, its FREQ << 16 at its %sp@(68), its engine block (FREQ at +2) at %sp@(104) and
// the envelope depth (ENV - 0x4000) << 17 in %d5. The harness builds that frame and runs from the call
// to its return (0x400728a8) for tracks 0, 3 and 7: %d0 must be the track's level (0x4199ef58 + 12 x
// track), the stack as before, %d2-%d4, %d6, %d7 and %a2-%a6 kept, and %d5 and the frame's FREQ << 16
// the model's. The model: VED and KEY are the words of the track's value slots 48 and 49 in the engine
// block (+0x12 + 2 x slot from the block's start); 0 or 0x4000 is no effect. Otherwise the depth gains
// ((VED - 0x4000) x velocity) << 2, velocity the track's word at 0x80001f18 + 2 x track, saturated;
// and FREQ << 16 gains (((note sum - 60 << 16) >> 8) x (KEY - 0x4000) x 7) >> 1, the note sum
// portamento's CUR[track] (0x439d1180 + 4 x track), saturated. Two scale checks tie the model to the
// meaning: VED +63 at velocity 127 gives 62.5 steps of ENV; KEY +63 an octave up 0.875 x 12 x 63 / 64
// FREQ steps. An inert hook (S38, S39) must leave %d5 and the frame as they were.
// VED in % (S47 on, the load's symbol ved_pct): VED 0..0x6400 and the depth becomes d5 - d5 x share, the
// share (127 - velocity) x VED % / 12700 in steps of 1/4096 rounded up, formed without a divide (the
// model below, to the bit); every run is also held to the exact law d5 x (1 - VED / 100 x (127 -
// velocity) / 127) within 1/3000 of the depth, and scale checks give 100 % at VED 0 or velocity 127, 0 at
// VED 100 % and velocity 0, about half at VED 100 % and velocity 64 and at VED 50 % and velocity 1.
// With the pivot at 100 (S48 on, symbol ved_100): (100 - velocity) in place of (127 - velocity), the
// sum saturated (a velocity above 100 deepens the depth); the scale points at 100, 127, 0 and 64.
// S49 on: VED's display object's flags 4 (the cell draws its name, not its value, when turned).
// S50 on (symbol key_x4): KEY's x 7 doubled, saturated, before FREQ's sum. S51 on: KEY's text callable
// from the constant object at 0x40252fec, run with sprintf (0x40000e82) stubbed against ENV's own on
// -63..63; S52 on (symbol key_txt): "%d%%" of the keytracking in percent, 6.25 a step, rounded half up.
// The emulator's sats.l gives 0x7fffffff below -2^31 too (the probe); where a run goes below -2^31 the
// model takes the emulator's value, and the count is printed.
// With FILTER page 2's data (S39 on): rows 1 and 2 and their names; the stock CC-table build
// FUN_40078b20 run on the stock rows and on the load's, whose tables may differ only in the sound slot
// map's entries 48 and 49 (ids 1, 2); FILTER page 2's layout stores (DEL - VED SRR BASE WDTH KEY ROUT);
// the display-object build for ids 1 and 2 with its copy routines stubbed (template, text, picture).
// With the save path (S41: PORT and LEG only, S42: VED and KEY too): both stored-index lookups against a
// copy of the stock code; a sound through the stock writer and reader (their callees stubbed) and the
// reader on malformed stored words; p-locks through the stock lock writer and reader.
//   ./scripts/ghidra_emu.sh 1.54 EmuFilter work/dt_1.54-filt/<stage>.load
// Runs in Ghidra's emulator only; nothing touches a device. @category dt_og_plus_plus
import ghidra.app.script.GhidraScript;
import ghidra.app.emulator.EmulatorHelper;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class EmuFilter extends GhidraScript {
  static final long SITE = 0x400728a2L, SITE_END = 0x400728a8L, LEVELS = 0x4199ef58L;
  static final long ENGINE = 0x80002760L, VELW = 0x80001f18L, PSTATE = 0x439d1180L;
  static final long SP0 = 0x40258600L, RET = 0x40001000L, DESC = 0x401aa09cL;

  List<Long> secAddr = new ArrayList<>();
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
  long rd(String r) throws Exception { return emu.readRegister(r).longValue() & 0xffffffffL; }
  String cstr(long a) throws Exception {
    StringBuilder s = new StringBuilder();
    for (int i = 0; i < 40; i++) { int c = (int) rdn(a + i, 1); if (c == 0) break; s.append((char) c); }
    return s.toString();
  }
  static long word(byte[] b, int o) { return (b[o] & 0xffL) << 24 | (b[o + 1] & 0xffL) << 16 | (b[o + 2] & 0xffL) << 8 | (b[o + 3] & 0xffL); }

  void fresh() throws Exception {
    if (emu != null) emu.dispose();
    emu = new EmulatorHelper(currentProgram);
    for (int k = 0; k < secAddr.size(); k++) emu.writeMemory(toAddr(secAddr.get(k)), secData.get(k));
    emu.writeMemory(toAddr(SP0 - 0x800), new byte[0x1000]);
    emu.writeMemory(toAddr(ENGINE), new byte[8 * 106 + 0x40]);
    emu.writeMemory(toAddr(PSTATE), new byte[64]);
    emu.writeMemory(toAddr(VELW), new byte[16]);
  }
  void verdict(String label, StringBuilder bad) {
    println(String.format("  %-78s %s%s", label, bad.length() == 0 ? "OK" : "**FAIL**", bad.length() > 600 ? bad.substring(0, 600) + " ..." : bad));
    if (bad.length() != 0) fails++;
  }

  // ---- the model
  static long sat32(long s) { return s > 0x7fffffffL ? 0x7fffffffL : s < -0x80000000L ? -0x80000000L : s; }
  static int modelDepth(int d5, int ved, int vel) {
    if ((short) ved == 0 || ved == 0x4000) return d5;
    int prod = (ved - 0x4000) * vel;
    return (int) sat32((long) d5 + ((long) prod << 2));
  }
  static boolean vedPct;	// S47 on: VED 0..100 %, the share of the depth the velocity decides (symbol ved_pct)
  static int vedPivot = 0x7f00;	// the velocity that leaves the depth as it is: 127, from S48 100 (ved_100)
  static long satsNeg = -0x80000000L;	// what sats.l gives below -2^31: the device's, or the emulator's (probe)
  static long satE(long s) { return s > 0x7fffffffL ? 0x7fffffffL : s < -0x80000000L ? (int) satsNeg : s; }
  static int modelDepthPct(int d5, int ved, int vel) {
    int u = ((vel - vedPivot) * ved) >> 16;		// -(pivot - velocity) x VED %, rounded down
    int share = (u * 21136) >> 16;			// minus the share in 1/4096 (2^32 / 203200 = 21136)
    long s = (long) d5 + (((d5 >> 16) * share) << 4);	// the depth less depth x share / 4096
    return (int) (vedPivot == 0x7f00 ? s : satE(s));	// (with the pivot at 127 it only shrinks)
  }
  static double lawDepthPct(int d5, int ved, int vel) {
    double s = d5 * (1.0 - (ved / 25600.0) * ((vedPivot - vel) / 32512.0));
    return Math.max(-2147483648.0, Math.min(2147483647.0, s));
  }
  static boolean keyX2;	// S44 on: KEY's effect doubled (the load's symbol key_x2)
  static boolean keyX4;	// S50 on: doubled again, saturated (key_x4)
  static boolean label, keyObj, keyTxt;	// S49 on: VED's flags 4; S51 on: KEY's text object; S52 on: key_txt
  static int modelFreq(int freq, int key, int ns) {
    if ((short) key == 0 || key == 0x4000) return freq;
    int semis256 = (ns - (60 << 16)) >> 8;
    int prod = semis256 * (key - 0x4000);
    long off = keyX2 ? prod * 7 : (prod * 7) >> 1;
    if (keyX4) off = satE(2 * off);
    return (int) satE((long) freq + off);
  }

  // ---- the hook at its site
  static final String[] KEEP = {"D2","D3","D4","D6","D7","A2","A3","A4","A5","A6"};
  int runs = 0;
  /** one call through the site: returns {d5, freq} after, or null on a fault */
  int[] site(int t, int ved, int vel, int key, int ns, int freq, int d5, long level, StringBuilder bad) throws Exception {
    long eng = ENGINE + 106L * t;
    wr(eng + 0x12 + 2 * 48, ved & 0xffff, 2); wr(eng + 0x12 + 2 * 49, key & 0xffff, 2);
    wr(VELW + 2 * t, vel & 0xffff, 2); wr(PSTATE + 4 * t, ns & 0xffffffffL, 4);
    wr(LEVELS + 12L * t, level, 4);
    long x = SP0 - 0x200;				// the caller's %sp
    wr(x + 68, freq & 0xffffffffL, 4); wr(x + 104, eng + 0x44, 4); wr(x - 4, t, 4);
    long[] keep = {0x22222222L, 0x33333333L, 0x44444444L, 0x66666666L, 0x77777777L, 0xa2a2a2a2L, 0xa3a3a3a3L, 0xa4a4a4a4L, 0xa5a5a5a5L, 0xa6a6a6a6L};
    for (int i = 0; i < KEEP.length; i++) emu.writeRegister(KEEP[i], keep[i]);
    emu.writeRegister("D5", d5 & 0xffffffffL); emu.writeRegister("D0", 0x0d0d0d0dL); emu.writeRegister("D1", 0x01010101L);
    emu.writeRegister("SP", x - 4); emu.writeRegister("PC", SITE);
    boolean done = false;
    for (int s = 0; s < 200; s++) {
      if (emu.getExecutionAddress().getOffset() == SITE_END) { done = true; break; }
      if (!emu.step(monitor)) { bad.append(" [fault " + emu.getLastError() + "]"); return null; }
    }
    runs++;
    if (!done) { bad.append(" [did not return]"); return null; }
    StringBuilder b = new StringBuilder();
    if (rd("SP") != x - 4) b.append(String.format(" sp %x", rd("SP")));
    if (rd("D0") != level) b.append(String.format(" d0 %x", rd("D0")));
    for (int i = 0; i < KEEP.length; i++) if (rd(KEEP[i]) != keep[i]) b.append(" " + KEEP[i]);
    if (rdn(x + 104, 4) != eng + 0x44 || rdn(x - 4, 4) != t) b.append(" frame");
    if (b.length() > 0 && bad.length() < 300) bad.append(String.format(" [t%d:%s]", t, b));
    return new int[] {(int) rd("D5"), (int) rdn(x + 68, 4)};
  }

  /** one site call (track 0, VED 1, velocity 63, KEY 1, an octave up), every step printed */
  void trace() throws Exception {
    fresh();
    long eng = ENGINE;
    wr(eng + 0x12 + 2 * 48, 0x100, 2); wr(eng + 0x12 + 2 * 49, 0x100, 2);
    wr(VELW, 0x3f00, 2); wr(PSTATE, 0x480000, 4); wr(LEVELS, 0x40000000L, 4);
    long x = SP0 - 0x200;
    wr(x + 68, 0x40000000L, 4); wr(x + 104, eng + 0x44, 4); wr(x - 4, 0, 4);
    emu.writeRegister("D5", 0); emu.writeRegister("SP", x - 4); emu.writeRegister("PC", SITE);
    for (int s = 0; s < 60 && emu.getExecutionAddress().getOffset() != SITE_END; s++) {
      long p = emu.getExecutionAddress().getOffset();
      println(String.format("%08x  %-36s d0 %08x d1 %08x d5 %08x a1 %08x", p, getInstructionAt(toAddr(p)) == null ? "?" : getInstructionAt(toAddr(p)).toString(),
          rd("D0"), rd("D1"), rd("D5"), rd("A1")));
      if (!emu.step(monitor)) { println("fault " + emu.getLastError()); break; }
    }
    println(String.format("end d5 %08x freq %08x", rd("D5"), rdn(x + 68, 4)));
  }

  void inertCases() throws Exception {
    StringBuilder bad = new StringBuilder();
    fresh();
    for (int t : new int[] {0, 3, 7}) {
      int[] r = site(t, 0x7f00, 0x7f00, 0x7f00, 0x480000, 0x40000000, 0x10000000, 0x80000000L, bad);
      if (r != null && (r[0] != 0x10000000 || r[1] != 0x40000000)) bad.append(String.format(" [t%d: d5 %x freq %x]", t, r[0], r[1]));
    }
    verdict("inert hook: the level getter's result, depth and FREQ as before, registers and stack kept", bad);
  }

  /** the emulator's `add.l %d1,%d0 ; sats.l %d0` for a sum: {result, V after the add} */
  long[] satsProbe(long a, long b) throws Exception {
    long code = 0x40001700L;
    wr(code, 0xd0814c80L, 4); wr(code + 4, 0x4e75, 2);
    emu.writeRegister("D0", a & 0xffffffffL); emu.writeRegister("D1", b & 0xffffffffL);
    long sp = SP0 - 0x10; wr(sp, RET, 4); emu.writeRegister("SP", sp); emu.writeRegister("PC", code);
    emu.step(monitor);
    long v = rd("VF") & 1;
    for (int s = 0; s < 4 && emu.getExecutionAddress().getOffset() != RET; s++) emu.step(monitor);
    return new long[] {rd("D0"), v};
  }

  void activeCases() throws Exception {
    fresh();
    long[] pos = satsProbe(0x7e000000L, 0x7d040000L), neg = satsProbe(0x80000000L, -0x3e040000L);
    satsNeg = neg[0];
    StringBuilder pb = new StringBuilder();
    if (pos[0] != 0x7fffffffL || pos[1] != 1 || neg[1] != 1) pb.append(String.format(" [%08x V%d, %08x V%d]", pos[0], pos[1], neg[0], neg[1]));
    verdict(String.format("the emulator's add.l + sats.l: V on both overflows; above 2^31 -> %08x, below -2^31 -> %08x"
        + " (the device: 80000000)", pos[0], neg[0]), pb);
    int[] tracks = {0, 3, 7};
    StringBuilder bad = new StringBuilder();
    int n = 0;
    if (vedPct) { vedPctCases(tracks); bad = new StringBuilder(); }
    else {
    // VED: depth against the model, KEY at no effect
    int[] veds = {0, 0x4000, 0x0100, 0x7f00, 0x6000, 0x2100, 0x4100};
    int[] vels = {0, 0x3f00, 0x7f00, 0x6400, 0x0100};
    int[] d5s = {0, 0x7e000000, 0x80000000, 0x10000000, -0x10000000};
    int under = 0;
    for (int t : tracks) for (int v : veds) for (int vel : vels) for (int d5 : d5s) {
      int[] r = site(t, v, vel, 0x4000, 0x480000, 0x30000000, d5, 0x80000000L, bad);
      if (r == null) continue;
      int wd = modelDepth(d5, v, vel);
      boolean below = (short) v != 0 && v != 0x4000 && (long) d5 + ((long) ((v - 0x4000) * vel) << 2) < -0x80000000L;
      if (below) { wd = (int) satsNeg; under++; }		// the emulator's sats, not the device's 0x80000000
      if ((r[0] != wd || r[1] != 0x30000000) && bad.length() < 400)
        bad.append(String.format(" [t%d ved %x vel %x d5 %x: d5 %x want %x, freq %x]", t, v, vel, d5, r[0], wd, r[1]));
      n++;
    }
    verdict(String.format("VED: the depth gains ((VED - 64) x velocity) << 2, saturated; 0 and 64 none (%d runs, %d below -2^31)", n, under), bad);
    }
    // KEY: FREQ << 16 against the model, VED at no effect
    bad = new StringBuilder(); n = 0;
    int[] keys = {0, 0x4000, 0x0100, 0x7f00, 0x5000, 0x3f00};
    int[] nss = {0x3c0000, 0x480000, 0x300000, 0, 0x7f0000, 0x3c8000, 0x543210, 0x2fffff};
    int[] freqs = {0, 0x40000000, 0x7f000000, 0x12340000, 0x7fff0000};
    int kUnder = 0;
    for (int t : tracks) for (int k : keys) for (int ns : nss) for (int f : freqs) {
      int[] r = site(t, 0, 0x7f00, k, ns, f, 0x10000000, 0x40000000L, bad);
      if (r == null) continue;
      int wf = modelFreq(f, k, ns);
      if (keyX4 && (short) k != 0 && k != 0x4000 && 2L * (((ns - (60 << 16)) >> 8) * (k - 0x4000) * 7) < -0x80000000L) kUnder++;
      if ((r[1] != wf || r[0] != 0x10000000) && bad.length() < 400)
        bad.append(String.format(" [t%d key %x ns %x freq %x: %x want %x, d5 %x]", t, k, ns, f, r[1], wf, r[0]));
      n++;
    }
    verdict(String.format("KEY: FREQ << 16 gains the note's distance from C4 x KEY x %s, saturated (%d runs%s)", keyX4 ? "14" : keyX2 ? "7" : "3.5", n,
        keyX4 ? ", " + kUnder + " below -2^31" : ""), bad);
    // both together, and the scale checks
    bad = new StringBuilder();
    if (vedPct) {
      int[] r = site(3, 0x6400, 0x4000, 0x7f00, 0x480000, 0x40000000, 0x7e000000, 0x40000000L, bad);
      if (r != null) {
        double share = (double) r[0] / 0x7e000000, freq = (double) (r[1] - 0x40000000) / (1 << 24);
        if (r[0] != modelDepthPct(0x7e000000, 0x6400, 0x4000) || r[1] != modelFreq(0x40000000, 0x7f00, 0x480000)) bad.append(" [both: not the model]");
        if (Math.abs(share - (vedPivot == 0x7f00 ? 64.0 / 127 : 91.0 / 127)) > 1.0 / 4096) bad.append(String.format(" [VED 100 %% at velocity 64: %.4f of the depth]", share));
        if (Math.abs(freq - (keyX4 ? 4 : keyX2 ? 2 : 1) * 0.875 * 12 * 63 / 64) > 0.01) bad.append(String.format(" [KEY +63, an octave up: %.3f FREQ steps]", freq));
        verdict(String.format("both: VED 100 %% at velocity 64: %.4f of ENV +63's depth; KEY +63 an octave up: +%.3f FREQ steps", share, freq), bad);
      }
      return;
    }
    int[] r = site(3, 0x7f00, 0x7f00, 0x7f00, 0x480000, 0x40000000, 0, 0x40000000L, bad);
    if (r != null) {
      double env = (double) r[0] / (1 << 25), freq = (double) (r[1] - 0x40000000) / (1 << 24);
      if (r[0] != modelDepth(0, 0x7f00, 0x7f00) || r[1] != modelFreq(0x40000000, 0x7f00, 0x480000)) bad.append(" [both: not the model]");
      if (Math.abs(env - 62.5) > 0.01) bad.append(String.format(" [VED +63 at velocity 127: %.3f ENV steps]", env));
      if (Math.abs(freq - (keyX2 ? 2 : 1) * 0.875 * 12 * 63 / 64) > 0.01) bad.append(String.format(" [KEY +63, an octave up: %.3f FREQ steps]", freq));
      verdict(String.format("VED +63 at velocity 127: +%.2f ENV steps; KEY +63 an octave up: +%.3f FREQ steps", env, freq), bad);
    }
  }

  /** VED in %: the depth against the model to the bit and against the exact law within 1/3000 of the
   *  depth, KEY at no effect; then the scale points. */
  void vedPctCases(int[] tracks) throws Exception {
    StringBuilder bad = new StringBuilder(), lawBad = new StringBuilder();
    int n = 0;
    double worst = 0;
    int[] veds = {0, 0x6400, 0x3200, 0x0100, 0x1980, 0x6380, 0x4000, 0x0080};
    int[] vels = {0, 0x0100, 0x3f00, 0x4000, 0x7f00, 0x6400, 0x7e00};
    int[] d5s = {0, 0x7e000000, 0x80000000, 0x10000000, -0x10000000, 0x00020000, -0x00020000, 0x00fe0000};
    int under = 0;
    for (int t : tracks) for (int v : veds) for (int vel : vels) for (int d5 : d5s) {
      int[] r = site(t, v, vel, 0x4000, 0x480000, 0x30000000, d5, 0x80000000L, bad);
      if (r == null) continue;
      int wd = modelDepthPct(d5, v, vel);
      if ((r[0] != wd || r[1] != 0x30000000) && bad.length() < 400)
        bad.append(String.format(" [t%d ved %x vel %x d5 %x: d5 %x want %x, freq %x]", t, v, vel, d5, r[0], wd, r[1]));
      double law = lawDepthPct(d5, v, vel);
      if (law <= -2147483648.0 && vedPivot != 0x7f00 && satsNeg != -0x80000000L) { under++; n++; continue; }	// the emulator's sats
      double err = Math.abs(r[0] - law) / Math.max(1.0, Math.abs((double) d5));
      worst = Math.max(worst, err);
      if (err > 1.0 / 3000 && lawBad.length() < 300) lawBad.append(String.format(" [ved %x vel %x d5 %x: %x, law %.0f]", v, vel, d5, r[0], law));
      n++;
    }
    String piv = vedPivot == 0x7f00 ? "127" : "100";
    verdict(String.format("VED %%: the depth less depth x (%s - velocity) x VED %% / 12700, in 1/4096%s (%d runs, %d below -2^31)",
        piv, vedPivot == 0x7f00 ? "" : ", saturated", n, under), bad);
    verdict(String.format("VED %%: every run within 1/3000 of the depth of the exact law (worst %.6f)", worst), lawBad);
    bad = new StringBuilder();
    int full = vedPivot == 0x7f00 ? 0x7e000000 : 0x40000000;	// ENV +63; with the pivot at 100 ENV +32, room to grow
    int[][] pts = vedPivot == 0x7f00
        ? new int[][] {{0, 0x0100}, {0x6400, 0x7f00}, {0x6400, 0}, {0x6400, 0x4000}, {0x3200, 0x0100}, {0x3200, 0x7f00}}
        : new int[][] {{0, 0x0100}, {0x6400, 0x6400}, {0x3200, 0x6400}, {0x6400, 0x7f00}, {0x6400, 0}, {0x3200, 0}, {0x6400, 0x4000}, {0, 0x7f00}};
    double[] want = vedPivot == 0x7f00 ? new double[] {1, 1, 0, 64.0 / 127, 0.5 + 0.5 / 127, 1}
        : new double[] {1, 1, 1, 1 + 27.0 / 127, 1 - 100.0 / 127, 1 - 50.0 / 127, 1 - 36.0 / 127, 1};
    StringBuilder got = new StringBuilder();
    for (int k = 0; k < pts.length; k++) {
      int[] r = site(0, pts[k][0], pts[k][1], 0x4000, 0x480000, 0x30000000, full, 0x80000000L, bad);
      if (r == null) continue;
      double s = (double) r[0] / full;
      got.append(String.format(" %d%%/v%d:%.4f", pts[k][0] >> 8, pts[k][1] >> 8, s));
      if (Math.abs(s - want[k]) > 1.0 / 4096) bad.append(String.format(" [%x %x: %.5f, want %.5f]", pts[k][0], pts[k][1], s, want[k]));
      if ((want[k] == 1 && r[0] != full) || (want[k] == 0 && r[0] != 0)) bad.append(String.format(" [%x %x: %x, want exactly %x]", pts[k][0], pts[k][1], r[0], want[k] == 1 ? full : 0));
    }
    if (vedPivot != 0x7f00) {		// at ENV +63 the deepest note saturates at the depth's top
      int[] r = site(0, 0x6400, 0x7f00, 0x4000, 0x480000, 0x30000000, 0x7e000000, 0x80000000L, bad);
      if (r != null && r[0] != 0x7fffffff) bad.append(String.format(" [ENV +63, 100 %%, v127: %x, want 7fffffff]", r[0]));
      if (r != null) got.append(String.format("; at ENV +63 100%%/v127: %08x", r[0]));
    }
    verdict("VED % at ENV " + (vedPivot == 0x7f00 ? "+63" : "+32") + " (VED%/velocity:share):" + got, bad);
  }

  // ---- FILTER page 2's data
  void dataCases() throws Exception {
    StringBuilder bad = new StringBuilder();
    fresh();
    for (int i = 1; i <= 2; i++) {
      long r = DESC + 0x34L * i;
      long page = rdn(r, 4), slot = rdn(r + 4, 4), mn = rdn(r + 8, 4), mx = rdn(r + 12, 4), def = rdn(r + 16, 4);
      long cc = rdn(r + 0x18, 4), nrpn = rdn(r + 0x1c, 4), fl = rdn(r + 0x24, 4), grp = rdn(r + 0x2c, 4);
      String ln = cstr(rdn(r + 0x28, 4)), sn = cstr(rdn(r + 0x30, 4));
      String want = i == 1 ? (vedPct ? "6 48 0x0 0x6400 0x0 VED Vel to Env Depth" : "6 48 0x100 0x7f00 0x4000 VED Vel to Env Depth")
                           : "6 49 0x100 0x7f00 0x4000 KEY Keytracking";
      String gotS = String.format("%d %d 0x%x 0x%x 0x%x %s %s", page, slot, mn, mx, def, sn, ln);
      if (!gotS.equals(want) || cc != 0xffffffffL || nrpn != 0xffffffffL || fl != 0 || grp != 0x401c699dL)
        bad.append(" [row " + i + ": " + gotS + String.format(" cc %x nrpn %x flags %x group %x]", cc, nrpn, fl, grp));
    }
    verdict(vedPct ? "rows 1, 2: VED 0..100 % (default 0), KEY 1..127 (64 none), filter page 6, slots 48, 49, no CC, names"
                   : "rows 1, 2: VED and KEY 1..127 (64 none), filter page 6, slots 48, 49, no CC, names", bad);
    long lo = 0x4199f700L, hi = 0x419a1700L;
    byte[][] tab = new byte[2][];
    bad = new StringBuilder();
    for (int pass = 0; pass < 2; pass++) {
      fresh();
      if (pass == 0) for (int i = 1; i <= 2; i++) {
        long r = DESC + 0x34L * i;
        byte[] b = new byte[0x34]; currentProgram.getMemory().getBytes(toAddr(r), b); emu.writeMemory(toAddr(r), b);
      }
      byte[] fillb = new byte[(int) (hi - lo)]; Arrays.fill(fillb, (byte) 0xa5); emu.writeMemory(toAddr(lo), fillb);
      long sp = SP0 - 4; wr(sp, RET, 4);
      emu.writeRegister("SP", sp); emu.writeRegister("PC", 0x40078b20L);
      boolean done = false;
      for (int s = 0; s < 400000; s++) {
        if (emu.getExecutionAddress().getOffset() == RET) { done = true; break; }
        if (!emu.step(monitor)) { bad.append(" [builder fault " + emu.getLastError() + "]"); break; }
      }
      if (!done) bad.append(" [builder did not return]");
      tab[pass] = emu.readMemory(toAddr(lo), (int) (hi - lo));
    }
    StringBuilder diff = new StringBuilder();
    for (int o = 0; o < tab[0].length; o += 4) {
      long a = word(tab[0], o), b = word(tab[1], o);
      if (a != b) diff.append(String.format(" 0x%x: %x -> %x;", lo + o, a, b));
    }
    long map = 0x4199f8f0L;
    String wantDiff = String.format(" 0x%x: %x -> 1; 0x%x: %x -> 2;", map + 192, word(tab[0], (int) (map + 192 - lo)),
        map + 196, word(tab[0], (int) (map + 196 - lo)));
    if (!diff.toString().equals(wantDiff)) bad.append(" [tables differ:" + diff + " want" + wantDiff + "]");
    verdict("CC tables as stock (CC 1, 2 still nothing); the slot map gives 48 -> 1, 49 -> 2", bad);
    // FILTER page 2's layout stores
    bad = new StringBuilder();
    fresh();
    emu.writeMemory(toAddr(0x4197e0bcL), new byte[44]);
    emu.writeRegister("SP", SP0 - 0x100); emu.writeRegister("PC", 0x401568acL);
    for (int s = 0; s < 100 && emu.getExecutionAddress().getOffset() != 0x401568f6L; s++) if (!emu.step(monitor)) break;
    long[] ids = new long[9];
    for (int k = 0; k < 9; k++) ids[k] = rdn(0x4197e0c4L + 4 * k, 4);
    if (!Arrays.equals(ids, new long[] {39, 0, 1, 42, 40, 41, 2, 43, 10})) bad.append(" [record " + Arrays.toString(ids) + "]");
    verdict("FILTER page 2: DEL, (B empty), VED, SRR, BASE, WDTH, KEY, ROUT, level on the 9th", bad);
    // the display objects of ids 1 and 2
    bad = new StringBuilder();
    fresh();
    long S2 = 0x40001100L, S3 = 0x40001200L, S4 = 0x40001300L;
    emu.writeRegister("SP", SP0 - 0x200); emu.writeRegister("PC", 0x40153332L);
    emu.writeRegister("A2", S2); emu.writeRegister("A3", S3); emu.writeRegister("A4", S4);
    emu.writeRegister("D2", 0x4018e18cL);
    List<String> calls = new ArrayList<>();
    for (int s = 0; s < 400 && emu.getExecutionAddress().getOffset() != 0x401533aeL; s++) {
      long p = emu.getExecutionAddress().getOffset();
      if (p == S2 || p == S3 || p == S4) {
        long q = rd("SP");
        calls.add(String.format("%s %x<-%x", p == S3 ? "tmpl" : p == S4 ? "text" : "pic", rdn(q + 4, 4), rdn(q + 8, 4)));
        emu.writeRegister("PC", rdn(q, 4)); emu.writeRegister("SP", q + 4);
        continue;
      }
      if (!emu.step(monitor)) { bad.append(" [dobj fault]"); break; }
    }
    String gotC = String.join(", ", calls);
    String wantC = (vedPct ? "tmpl 4197e350<-4018e18c, text 4197e360<-4197d59c, pic 4197e370<-4197d3cc, "
                           : "tmpl 4197e350<-4018e18c, text 4197e360<-4197d7cc, pic 4197e370<-4197d4dc, ")
        + "tmpl 4197e3a4<-4018e18c, text 4197e3b4<-" + (keyObj ? "40252fec" : "4197d7cc") + ", pic 4197e3c4<-4197d4dc";
    if (!gotC.equals(wantC)) bad.append(" [" + gotC + "]");
    verdict(vedPct ? "display objects: whole steps; VED Trig Probability's text and picture, KEY ENV's" + (keyObj ? " picture, text from 0x40252fec" : "")
                   : "display objects of VED and KEY: whole steps, ENV's text and picture", bad);
  }

  /** FILTER page 2's draw routine FUN_40037564, from its per-knob decision (0x400376b0, the knob's id in
   *  %d3) to its call of the page's cell drawer (vtable +0x94, stubbed): the drawer's last argument, 1 for
   *  "no picture". Its two helpers on the page's +148 are stubbed. */
  long pictureArg(long id, StringBuilder bad) throws Exception {
    long page = 0x42300000L, vt = 0x42310000L, drawer = 0x40001800L;
    emu.writeMemory(toAddr(page), new byte[0x200]); emu.writeMemory(toAddr(vt), new byte[0x200]);
    wr(page, vt, 4); wr(vt + 0x94, drawer, 4);
    long sp = SP0 - 0x300;
    emu.writeRegister("SP", sp); emu.writeRegister("PC", 0x400376b0L);
    emu.writeRegister("D3", id); emu.writeRegister("D2", 8); emu.writeRegister("A2", page);
    emu.writeRegister("A3", 0x4000); emu.writeRegister("A4", page + 148);
    for (int s = 0; s < 200; s++) {
      long p = emu.getExecutionAddress().getOffset();
      if (p == drawer) {
        long q = rd("SP");
        if (rdn(q + 20, 4) != id) bad.append(String.format(" [id %d: drawer got id %d]", id, rdn(q + 20, 4)));
        return rdn(q + 40, 4);
      }
      if (p == 0x400c0898L || p == 0x400c0686L) {
        long q = rd("SP"); emu.writeRegister("D0", 0); emu.writeRegister("PC", rdn(q, 4)); emu.writeRegister("SP", q + 4);
        continue;
      }
      if (!emu.step(monitor)) { bad.append(String.format(" [id %d: fault at %x]", id, p)); return -1; }
    }
    bad.append(" [id " + id + ": no drawer call]");
    return -1;
  }

  void pictureCases(boolean fixed) throws Exception {
    StringBuilder bad = new StringBuilder();
    fresh();
    long[][] want = {{39, 0}, {40, 1}, {41, 1}, {42, 0}, {43, 0}, {1, fixed ? 0 : 1}, {2, fixed ? 0 : 1}};
    StringBuilder got = new StringBuilder();
    for (long[] w : want) {
      long a = pictureArg(w[0], bad);
      got.append(String.format(" %d:%d", w[0], a));
      if (a != w[1]) bad.append(String.format(" [id %d: %d, want %d]", w[0], a, w[1]));
    }
    verdict((fixed ? "FILTER page 2's draw routine: pictures for DEL, SRR, ROUT, VED, KEY; none for BASE, WDTH"
                   : "FILTER page 2's draw routine (stock): pictures for DEL, SRR, ROUT only; VED, KEY none")
        + " (" + got.toString().trim() + ")", bad);
  }

  /** The whole start-up display build FUN_40152280 on the load's bytes, a bump allocator in place of
   *  0x400d43a8 (and the malloc it calls): VED's and KEY's display objects (ids 1, 2) against ENV's (38)
   *  text and picture callables and, with snap, GAIN's (31) [FUNC] callable at +0x44; then that callable
   *  of ids 1 and 2 run on values and directions. */
  void buildCases(boolean snap) throws Exception {
    StringBuilder bad = new StringBuilder();
    fresh();
    emu.writeMemory(toAddr(0x4197d000L), new byte[0x4000]);
    long heap = 0x42400000L;
    emu.writeMemory(toAddr(heap), new byte[0x10000]);
    long sp = SP0 - 4; wr(sp, RET, 4);
    emu.writeRegister("SP", sp); emu.writeRegister("PC", 0x40152280L);
    boolean done = false;
    for (int s = 0; s < 3000000; s++) {
      long pc = emu.getExecutionAddress().getOffset();
      if (pc == RET) { done = true; break; }
      if (pc == 0x400d43a8L || pc == 0x400d412cL) {
        long q = rd("SP"); long n = rdn(q + 4, 4);
        emu.writeRegister("D0", heap); emu.writeRegister("A0", heap); heap += (n + 15) & ~15L;
        emu.writeRegister("PC", rdn(q, 4)); emu.writeRegister("SP", q + 4);
        continue;
      }
      if (!emu.step(monitor)) { bad.append(" [builder fault " + emu.getLastError() + " at " + Long.toHexString(pc) + "]"); break; }
    }
    if (!done) bad.append(" [builder did not return]");
    long o1 = 0x4197e2f8L + 0x54, o2 = 0x4197e2f8L + 0x54 * 2, env = 0x4197e2f8L + 0x54 * 38, gain = 0x4197e2f8L + 0x54 * 31;
    long prob = 0x4197e2f8L + 0x54 * 29;
    for (long o : new long[] {o1, o2}) {
      long wantFlags = label && o == o1 ? 4 : 0;
      if (rdn(o, 4) != wantFlags) bad.append(String.format(" [%x flags %x, want %x]", o, rdn(o, 4), wantFlags));
      long src = vedPct && o == o1 ? prob : env;
      for (int f : new int[] {0x1c, 0x20, 0x2c, 0x30}) {			// text and picture: manager, invoker
        long want = keyTxt && o == o2 && f == 0x20 ? sym.get("key_txt") : rdn(src + f, 4);
        if (rdn(o + f, 4) != want || want == 0) bad.append(String.format(" [%x+%x: %x, want %x]", o, f, rdn(o + f, 4), want));
      }
      for (int f : new int[] {0x4c, 0x50}) {			// [FUNC]: manager, invoker
        long want = snap ? rdn(gain + f, 4) : 0;
        if (rdn(o + f, 4) != want) bad.append(String.format(" [%x+%x: %x, want %x]", o, f, rdn(o + f, 4), want));
      }
    }
    verdict((vedPct ? "start-up build: VED with Trig Probability's text and picture" + (label ? ", flags 4" : "")
                      + (keyTxt ? ", KEY with ENV's picture and key_txt" : ", KEY with ENV's")
                    : "start-up build: VED and KEY with ENV's text and picture")
        + (snap ? ", GAIN's [FUNC] callable (0x4005f830)" : ", no [FUNC] callable"), bad);
    if (!snap) return;
    bad = new StringBuilder();
    long[][] steps = {{0x2000, 1, 0x4000}, {0x4000, 1, 0x7f00}, {0x7f00, 1, 0x7f00}, {0x6000, -1, 0x4000},
                      {0x4000, -1, 0x0100}, {0x0100, -1, 0x0100}, {0x3f00, 1, 0x4000}, {0x4100, -1, 0x4000}};
    long[][] stepsPct = {{0x1000, 1, 0x3200}, {0x3200, 1, 0x6400}, {0x6400, 1, 0x6400}, {0x5000, -1, 0x3200},
                         {0x3200, -1, 0}, {0, -1, 0}, {0x3100, 1, 0x3200}, {0x3300, -1, 0x3200}};
    StringBuilder got = new StringBuilder();
    for (long o : new long[] {o1, o2}) {
      boolean pct = vedPct && o == o1;
      long r0 = DESC + 0x34L * (o == o1 ? 1 : 2), mn = rdn(r0 + 8, 4), mx = rdn(r0 + 12, 4);	// the row's range, as ParameterSet::vfunc_11 passes it
      for (long[] s : pct ? stepsPct : steps) {
        long q = SP0 - 0x100;
        wr(q, RET, 4); wr(q + 4, o + 0x44, 4); wr(q + 8, s[0], 4); wr(q + 12, s[1] & 0xffffffffL, 4); wr(q + 16, mn, 4); wr(q + 20, mx, 4);
        emu.writeRegister("SP", q); emu.writeRegister("PC", rdn(o + 0x50, 4));
        for (int k = 0; k < 100 && emu.getExecutionAddress().getOffset() != RET; k++) if (!emu.step(monitor)) break;
        long r = rd("D0");
        if (pct) got.append(String.format(" %d%s:%d", s[0] >> 8, s[1] > 0 ? "+" : "-", (r >> 8) & 0xff));
        else if (o == o1) got.append(String.format(" %d%s:%d", (s[0] >> 8) - 64, s[1] > 0 ? "+" : "-", ((r >> 8) & 0xff) - 64));
        if (r != s[2]) bad.append(String.format(" [%x from %x dir %d: %x, want %x]", o, s[0], s[1], r, s[2]));
      }
    }
    verdict(vedPct ? "[FUNC] + knob: VED to the next of 0, 50, 100 %, KEY of -63, 0, 63 (VED" + got + ")"
                   : "[FUNC] + knob on VED and KEY: the next of -63, 0, 63 in the turn's direction (" + got.toString().trim() + ")", bad);
    if (keyObj) textCases(o2, env);
  }

  /** The text callable at c (storage, manager +8, invoker +12) on a value, sprintf (0x40000e82) stubbed:
   *  its first five arguments (buffer, format, ...), or null if it returned without one. */
  long[] runText(long c, long value) throws Exception {
    long q = SP0 - 0x180, buf = 0x40258f00L;
    wr(q, RET, 4); wr(q + 4, c, 4); wr(q + 8, value, 4); wr(q + 12, buf, 4);
    emu.writeRegister("SP", q); emu.writeRegister("PC", rdn(c + 12, 4));
    for (int k = 0; k < 400; k++) {
      long p = emu.getExecutionAddress().getOffset();
      if (p == RET) return null;
      if (p == 0x40000e82L) {
        long s = rd("SP"); long[] a = new long[5];
        for (int i = 0; i < 5; i++) a[i] = rdn(s + 4 + 4 * i, 4);
        return a;
      }
      if (!emu.step(monitor)) return null;
    }
    return null;
  }

  /** KEY's text on -63..63: S51 the same sprintf call as ENV's own text; S52 "%d%%" (0x401c690e) of the
   *  keytracking in percent, 6.25 a step, rounded half up. */
  void textCases(long o2, long env) throws Exception {
    StringBuilder bad = new StringBuilder(), got = new StringBuilder();
    for (int k = -63; k <= 63; k++) {
      long v = 0x4000 + 256L * k;
      long[] a = runText(o2 + 0x14, v);
      if (a == null) { bad.append(" [k " + k + ": no sprintf]"); continue; }
      if (keyTxt) {
        long want = (long) Math.floor(k * 6.25 + 0.5);
        if (a[1] != 0x401c690eL || (int) a[2] != want || a[0] != 0x40258f00L) bad.append(String.format(" [k %d: fmt %x %d, want %d]", k, a[1], (int) a[2], want));
        if (k % 16 == 0 || k == 63 || k == -63 || k == 2 || k == -2) got.append(String.format(" %d:%d%%", k, (int) a[2]));
      } else {
        long[] e = runText(env + 0x14, v);
        if (e == null || !Arrays.equals(a, e)) bad.append(String.format(" [k %d: %s, ENV's %s]", k, Arrays.toString(a), Arrays.toString(e)));
      }
    }
    verdict(keyTxt ? "KEY's text: \"%d%%\" of KEY x 6.25, rounded half up (" + got.toString().trim() + ")"
                   : "KEY's text from the constant object: ENV's sprintf call on -63..63", bad);
  }

  // ---- the save path
  Map<Long, Long> stubRet = new HashMap<>();
  boolean call(long pc, long[] args, long[] stubs, int limit, StringBuilder bad, String what) throws Exception {
    long sp = SP0 - 0x40 - 4L * args.length;
    wr(sp, RET, 4);
    for (int i = 0; i < args.length; i++) wr(sp + 4 + 4L * i, args[i], 4);
    emu.writeRegister("SP", sp); emu.writeRegister("PC", pc);
    for (int s = 0; s < limit; s++) {
      long p = emu.getExecutionAddress().getOffset();
      if (p == RET) return true;
      boolean stub = false;
      for (long st : stubs) if (p == st) stub = true;
      if (stub) {
        long q = rd("SP");
        emu.writeRegister("D0", stubRet.getOrDefault(p, 0L));
        emu.writeRegister("PC", rdn(q, 4)); emu.writeRegister("SP", q + 4);
        continue;
      }
      if (!emu.step(monitor)) { bad.append(" [" + what + ": fault " + emu.getLastError() + " at 0x" + Long.toHexString(p) + "]"); return false; }
    }
    bad.append(" [" + what + ": ran away]");
    return false;
  }

  void saveCases(boolean save) throws Exception {
    for (int f = 0; f < 2; f++) {
      StringBuilder bad = new StringBuilder();
      fresh();
      long fn = f == 0 ? 0x40079738L : 0x40079772L, copy = 0x40001600L;
      byte[] st = new byte[58]; currentProgram.getMemory().getBytes(toAddr(fn), st); emu.writeMemory(toAddr(copy), st);
      int n = 0, diff = 0;
      long[] kinds = {0xffffffffL, 0, 1, 7, 8, 15, 16, 17, 100};
      for (long kind : kinds) for (long idx = -2; idx <= 60; idx++) {
        long ix = idx & 0xffffffffL;
        call(fn, new long[] {kind, ix}, new long[0], 200, bad, "new"); long a = rd("D0");
        call(copy, new long[] {kind, ix}, new long[0], 200, bad, "stock"); long b = rd("D0");
        long want = b;
        if (kind < 16) {
          if (ix == 46 || ix == 47) want = ix;
          if (save && f == 0 && (ix == 50 || ix == 51)) want = ix - 2;
          if (save && f == 1 && (ix == 48 || ix == 49)) want = ix + 2;
        }
        if (a != want && bad.length() < 400) bad.append(String.format(" [kind %d %d: 0x%x, want 0x%x]", (int) kind, idx, a, want));
        if (a != b) diff++;
        n++;
      }
      verdict(String.format("%s: as stock for %d inputs, but for kinds below 16 %s (%d differ)",
          f == 0 ? "FUN_40079738 (index -> slot)" : "FUN_40079772 (slot -> index)", n,
          save ? (f == 0 ? "46, 47 -> 46, 47 and 50, 51 -> 48, 49" : "46, 47 -> 46, 47 and 48, 49 -> 50, 51")
               : "46 and 47 to themselves", diff), bad);
    }
    long SND = 0x40258800L, SND2 = 0x40258a00L, REC = 0x40258c00L;
    long[] wstubs = {0x4007a552L, 0x40079dbcL, 0x40084ea4L};
    long[] rstubs = {0x40106b96L, 0x40000e82L, 0x4007a1dcL, 0x40084ec2L, 0x400797acL, 0x40084ea4L};
    StringBuilder bad = new StringBuilder();
    fresh();
    stubRet.clear(); stubRet.put(0x4007a552L, 1L); stubRet.put(0x40106b96L, 1L); stubRet.put(0x4007a1dcL, 1L); stubRet.put(0x400797acL, 1L);
    emu.writeMemory(toAddr(SND), new byte[0x100]); emu.writeMemory(toAddr(SND2), new byte[0x100]);
    byte[] junk = new byte[0x100]; Arrays.fill(junk, (byte) 0x5a); emu.writeMemory(toAddr(REC), junk);
    int[] v = new int[53];
    for (int s = 0; s < 53; s++) v[s] = ((s * 37 + 5) & 0x7f) << 8 | (s * 11 & 0xff);
    v[4] = 26 << 8; v[12] = 30 << 8; v[20] = 0;
    v[46] = 80 << 8; v[47] = 1 << 8; v[48] = 0x6000; v[49] = 0x2100;
    for (int s = 50; s < 53; s++) v[s] = 0;
    for (int s = 0; s < 53; s++) wr(SND + 0x14 + 2 * s, v[s], 2);
    call(0x4007a5a0L, new long[] {REC, SND, 0}, wstubs, 200000, bad, "writer");
    if (rdn(REC + 0x78, 2) != v[46] || rdn(REC + 0x7a, 2) != v[47]) bad.append(String.format(" [record +0x78: %08x]", rdn(REC + 0x78, 4)));
    if (save && (rdn(REC + 0x80, 2) != v[48] || rdn(REC + 0x82, 2) != v[49])) bad.append(String.format(" [record +0x80: %08x]", rdn(REC + 0x80, 4)));
    if (rdn(REC + 0x7c, 4) == 0x5a5a5a5aL) bad.append(" [machine bytes not written]");
    call(0x4007a236L, new long[] {SND2, REC}, rstubs, 200000, bad, "reader");
    int last = save ? 50 : 48;
    for (int s = 0; s < last; s++) if (rdn(SND2 + 0x14 + 2 * s, 2) != v[s] && bad.length() < 400)
      bad.append(String.format(" [slot %d: 0x%x, wrote 0x%x]", s, rdn(SND2 + 0x14 + 2 * s, 2), v[s]));
    verdict(save ? "a sound written and read back: slots 0..49, VED and KEY in +0x80, +0x82"
                 : "a sound written and read back: slots 0..47, PORT and LEG in +0x78, +0x7a", bad);
    bad = new StringBuilder();
    long[][] spare = {{0, 0, 0}, {0x12345678L, 0, 0}, {0x7f000100L, 0x7f00, 0x100}, {0x80000000L, 0, 0}, {0x00010000L, 0, 0}, {0x40000200L, 0, 0}};
    for (long[] c : spare) {
      wr(REC + 0x78, c[0], 4);
      call(0x4007a236L, new long[] {SND2, REC}, rstubs, 200000, bad, "reader");
      if (rdn(SND2 + 0x70, 2) != c[1] || rdn(SND2 + 0x72, 2) != c[2]) bad.append(String.format(" [spare %08x: %04x %04x]", c[0], rdn(SND2 + 0x70, 2), rdn(SND2 + 0x72, 2)));
    }
    verdict("the reader: PORT and LEG from a stock record's zeros, a valid pair and four malformed (0)", bad);
    if (save) {
      bad = new StringBuilder();
      long[][] vk = {{0, 0x4000, 0x4000}, {0x00000000L, 0x4000, 0x4000}, {0x01007f00L, 0x0100, 0x7f00}, {0x40004000L, 0x4000, 0x4000},
                     {0x5a5a5a5aL, 0x4000, 0x4000}, {0xffff0150L, 0x4000, 0x4000}, {0x80002000L, 0x8000, 0x2000}, {0x00ff3f01L, 0x4000, 0x4000}};
      if (vedPct) vk = new long[][] {{0, 0, 0x4000}, {0x01007f00L, 0x0100, 0x7f00}, {0x64000100L, 0x6400, 0x0100}, {0x64010150L, 0, 0x4000},
                     {0x40004000L, 0x4000, 0x4000}, {0x5a5a5a5aL, 0x5a5a, 0x4000}, {0xffff8000L, 0, 0x4000}, {0x00ff7fffL, 0x00ff, 0x4000},
                     {0x32000000L, 0x3200, 0x4000}, {0x80003f00L, 0, 0x3f00}};
      for (long[] c : vk) {
        wr(REC + 0x80, c[0], 4);
        call(0x4007a236L, new long[] {SND2, REC}, rstubs, 200000, bad, "reader");
        if (rdn(SND2 + 0x74, 2) != c[1] || rdn(SND2 + 0x76, 2) != c[2]) bad.append(String.format(" [+0x80 %08x: %04x %04x]", c[0], rdn(SND2 + 0x74, 2), rdn(SND2 + 0x76, 2)));
      }
      verdict(vedPct ? "the reader: VED up to 100 % kept, above it 0; KEY a whole 1..127 kept, else 64"
                     : "the reader: VED and KEY 64 from zeros and malformed words, whole 1..127 kept (128 slips)", bad);
    }
    bad = new StringBuilder();
    fresh();
    long LK = 0x42000000L, LK2 = 0x42040000L, RECS = 0x42080000L, TS = 0x1b35;
    byte[] ff = new byte[0x1b350]; Arrays.fill(ff, (byte) 0xff); emu.writeMemory(toAddr(LK), ff);
    for (int tr = 0; tr < 16; tr++) emu.writeMemory(toAddr(LK + tr * TS + 0x1b00), new byte[0x35]);
    List<long[]> locks = new ArrayList<>(Arrays.asList(new long[][] {{0, 46, 0, 0x2000}, {2, 47, 3, 0x0100}, {1, 30, 7, 0x1234}, {3, 4, 9, 0x1a00}}));
    if (save) { locks.add(new long[] {0, 48, 5, 0x7f00}); locks.add(new long[] {4, 49, 2, 0x0100}); }
    for (long[] l : locks) { wr(LK + l[0] * TS + l[2] * 0x6c + l[1] * 2, l[3], 2); wr(LK + l[0] * TS + 0x1b00 + l[1], 1, 1); }
    emu.writeMemory(toAddr(RECS), new byte[0x28a0]);
    call(0x4007adb2L, new long[] {RECS, LK}, new long[0], 2000000, bad, "lock writer");
    StringBuilder idx = new StringBuilder();
    for (int r = 0; r < locks.size(); r++) idx.append(String.format(" %d/%d", (byte) rdn(RECS + r * 0x82, 1), (byte) rdn(RECS + r * 0x82 + 1, 1)));
    call(0x4007abb2L, new long[] {LK2, RECS}, new long[0], 4000000, bad, "lock reader");
    int flags = 0;
    for (int tr = 0; tr < 16; tr++) for (int s = 0; s < 53; s++) if (rdn(LK2 + tr * TS + 0x1b00 + s, 1) != 0) flags++;
    for (long[] l : locks) {
      if (rdn(LK2 + l[0] * TS + 0x1b00 + l[1], 1) == 0) bad.append(String.format(" [track %d slot %d not locked]", l[0], l[1]));
      if (rdn(LK2 + l[0] * TS + l[2] * 0x6c + l[1] * 2, 2) != l[3]) bad.append(String.format(" [track %d slot %d step %d: 0x%x]", l[0], l[1], l[2], rdn(LK2 + l[0] * TS + l[2] * 0x6c + l[1] * 2, 2)));
    }
    if (flags != locks.size()) bad.append(" [" + flags + " locked slots, want " + locks.size() + "]");
    verdict("p-locks written and read back" + (save ? ", VED and KEY as indices 50, 51" : "") + " (records" + idx + ")", bad);
  }

  public void run() throws Exception {
    String[] args = getScriptArgs();
    if (args.length < 1) { printerr("usage: EmuFilter.java <stage.load>"); return; }
    for (String line : Files.readAllLines(Paths.get(args[0]))) {
      String[] p = line.trim().split("\\s+");
      if (p.length == 3 && p[0].equals("sym")) sym.put(p[1], Long.parseLong(p[2], 16));
      else if (p.length == 2) {
        byte[] b = new byte[p[1].length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(p[1].substring(2 * i, 2 * i + 2), 16);
        secAddr.add(Long.parseLong(p[0], 16)); secData.add(b);
      }
    }
    fresh();
    boolean hook = rdn(SITE, 2) == 0x4eb9 && rdn(SITE + 2, 4) == sym.getOrDefault("filt_hook", -1L);
    boolean active = sym.containsKey("filt_key");
    boolean data = rdn(DESC + 0x34, 4) == 6;
    boolean move = sym.containsKey("rd_hook2");
    boolean save = move && rdn(0x40252fecL, 4) == 50;
    println(String.format("EmuFilter %s: hook %s, %s, data %s, save path %s", args[0], hook ? "present" : "absent",
        active ? "active" : "inert", data ? "present" : "absent", save ? "VED and KEY" : move ? "PORT and LEG only" : "stock"));
    if (args.length > 1 && args[1].equals("trace")) { trace(); emu.dispose(); return; }
    keyX2 = sym.containsKey("key_x2");
    keyX4 = sym.containsKey("key_x4");
    vedPct = sym.containsKey("ved_pct");
    vedPivot = sym.containsKey("ved_100") ? 0x6400 : 0x7f00;
    label = sym.containsKey("ved_label");
    keyObj = sym.containsKey("key_obj");
    keyTxt = sym.containsKey("key_txt");
    if (hook) { if (active) activeCases(); else inertCases(); }
    if (data) { dataCases(); pictureCases(rdn(0x400376b2L, 2) == 0x7205); buildCases(sym.containsKey("spc_copy")); }
    if (move) saveCases(save);
    println(fails == 0 ? "=== ALL CASES PASS ===" : "=== " + fails + " CASE(S) FAIL ===");
    emu.dispose();
  }
}
