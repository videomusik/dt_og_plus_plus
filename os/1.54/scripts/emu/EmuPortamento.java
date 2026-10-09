// portamento: run the portamento hooks of a stage image in Ghidra's p-code emulator, at their sites in
// the audio ISR, and compare what the rate loop gets with a model of the glide written here from its
// description.
//
// The note-on hook runs from its site (0x400779f6) through the stock store of the trig's note
// (to 0x40077a0a): the note must land in NOTES[track], %a1 and %a4 must hold what the stock code leaves,
// and every other register must be kept. The rate-loop hook runs from its site (0x40075690) to the
// loop's next instruction (0x4007569c) for each of the 8 tracks, every tick: %d6 must be the model's
// note sum, %d1 3, and %d2, %d3, %d7, %a1-%a6 and the stack kept.
// The model: per track the note sum it plays (CUR). A note-on marks the track's note new and records
// whether its gate was open (legato). Each tick: a track whose CUR is not valid yet (power-up) starts
// on the target; a new note with PORT 0, or with LEG on and not legato, starts on the target; otherwise
// CUR moves by (target - CUR) / (1 + PORT^2 / 8) (truncated), and arrives when that step is 0. PORT and
// LEG are the integer bytes of the track's value slots 46 and 47 (0x80001502 + 106 x track + 92, + 94).
// A load whose hooks are inert (S29, S30) is checked to replay the stock instructions exactly and to
// leave the portamento state alone.
// With the TRIG page's data (S30, S31) the harness also runs, on the load's bytes: the stock CC-table
// build FUN_40078b20, whose tables must equal those from the stock rows except the sound slot map's
// entries for slots 46 and 47 (ids 4 and 5); the TRIG layout's start-up stores (knobs G and H = 4, 5);
// and the display-object build for ids 4 and 5 with its copy routines stubbed (template, text and
// picture sources); and it reads the two rows and their names.
//   ./scripts/ghidra_emu.sh 1.54 EmuPortamento work/dt_1.54-port/<stage>.load
// Runs in Ghidra's emulator only; nothing touches a device. @category dt_og_plus_plus
import ghidra.app.script.GhidraScript;
import ghidra.app.emulator.EmulatorHelper;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class EmuPortamento extends GhidraScript {
  static final long NOTES = 0x80001f28L, GATES = 0x800019f4L, COPY = 0x80001502L, MIRROR_TUNE = 0x80002794L;
  static final long STATE = 0x439d1180L, MAGICV = 0x504f5254L;
  static final long ON_SITE = 0x400779f6L, ON_END = 0x40077a0aL, HELD = 0x4399ec80L;
  static final long RATE_SITE = 0x40075690L, RATE_END = 0x4007569cL;
  static final long SP0 = 0x40258600L, RET = 0x40001000L;
  static final long DESC = 0x401aa09cL;

  List<Long> secAddr = new ArrayList<>();
  List<byte[]> secData = new ArrayList<>();
  Map<String, Long> sym = new HashMap<>();
  EmulatorHelper emu;
  int fails = 0;
  boolean feature;

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
    emu.writeMemory(toAddr(STATE), new byte[64]);
    emu.writeMemory(toAddr(NOTES), new byte[32]);
    emu.writeMemory(toAddr(COPY - 0x20), new byte[8 * 106 + 0x40]);
    wr(GATES, 0, 4);
  }
  void verdict(String label, StringBuilder bad) {
    println(String.format("  %-70s %s%s", label, bad.length() == 0 ? "OK" : "**FAIL**", bad.length() > 600 ? bad.substring(0, 600) + " ..." : bad));
    if (bad.length() != 0) fails++;
  }

  // ---- the model
  long[] cur = new long[8];
  boolean[] valid = new boolean[8];
  int newBits, legBits;
  boolean magic;
  int[] port = new int[8], leg = new int[8];
  long[] target = new long[8];

  void modelFromMemory() throws Exception {
    for (int t = 0; t < 8; t++) cur[t] = rdn(STATE + 4 * t, 4);
    magic = rdn(STATE + 32, 4) == MAGICV;
    newBits = (int) rdn(STATE + 36, 1); legBits = (int) rdn(STATE + 37, 1);
    int v = (int) rdn(STATE + 38, 1);
    for (int t = 0; t < 8; t++) valid[t] = ((v >> t) & 1) != 0;
  }
  long modelTick(int t) {
    long T = target[t];
    if (!magic) { magic = true; for (int i = 0; i < 8; i++) valid[i] = false; }
    if (!valid[t]) { valid[t] = true; cur[t] = T; return T; }
    boolean isNew = ((newBits >> t) & 1) != 0;
    newBits &= ~(1 << t);
    boolean glide = port[t] != 0;
    if (isNew && glide && leg[t] != 0 && ((legBits >> t) & 1) == 0) glide = false;
    if (!glide) { cur[t] = T; return T; }
    long k = 1 + ((long) port[t] * port[t] >> 3);
    long step = ((long) (int) (T - cur[t])) / k;	// truncated toward zero, as divs.l
    if (step == 0) cur[t] = T; else cur[t] = (int) (cur[t] + step) & 0xffffffffL;
    return cur[t];
  }

  // ---- the hooks at their sites
  static final String[] KEEP_ON = {"D0","D2","D3","D4","D5","D6","D7","A0","A2","A3","A5","A6"};
  static final String[] KEEP_RATE = {"D2","D3","D7","A1","A2","A3","A4","A5","A6"};

  /** a note-on of note on track t; gateOpen: the track's gate as the last note left it */
  void noteOn(int t, int note, boolean gateOpen, StringBuilder bad) throws Exception {
    long g = rdn(GATES, 4);
    g = gateOpen ? g | (1L << t) : g & ~(1L << t);
    wr(GATES, g, 4);
    long sp = SP0 - 0x100;
    emu.writeRegister("SP", sp); emu.writeRegister("PC", ON_SITE);
    long[] sent = new long[KEEP_ON.length];
    for (int i = 0; i < KEEP_ON.length; i++) { sent[i] = 0x5a5a0000L + i; emu.writeRegister(KEEP_ON[i], sent[i]); }
    emu.writeRegister("D2", t); sent[1] = t;
    emu.writeRegister("D1", note); emu.writeRegister("A1", 0xdead0001L); emu.writeRegister("A4", 0xdead0004L);
    boolean done = false;
    for (int s = 0; s < 200; s++) {
      if (emu.getExecutionAddress().getOffset() == ON_END) { done = true; break; }
      if (!emu.step(monitor)) { bad.append(" [note-on fault " + emu.getLastError() + "]"); return; }
    }
    if (!done) { bad.append(" [note-on ran away]"); return; }
    if (rdn(NOTES + 4 * t, 4) != ((long) note << 16)) bad.append(String.format(" [t%d note 0x%x not stored]", t, note));
    if (rd("A1") != NOTES) bad.append(String.format(" [a1 0x%x]", rd("A1")));
    if (rd("A4") != HELD) bad.append(String.format(" [a4 0x%x]", rd("A4")));
    if (rd("SP") != sp) bad.append(" [note-on stack]");
    for (int i = 0; i < KEEP_ON.length; i++) if (rd(KEEP_ON[i]) != sent[i]) bad.append(" [note-on " + KEEP_ON[i] + "]");
    target[t] = (long) note << 16;
    newBits |= 1 << t;
    legBits = gateOpen ? legBits | (1 << t) : legBits & ~(1 << t);
    wr(GATES, rdn(GATES, 4) | (1L << t), 4);	// the trig opens the gate (0x40077b0c)
  }
  void gateOff(int t) throws Exception { wr(GATES, rdn(GATES, 4) & ~(1L << t), 4); }
  /** a note change without a trig (the ISR's other note store, 0x4007787e) */
  void noteOnly(int t, int note) throws Exception { wr(NOTES + 4 * t, (long) note << 16, 4); target[t] = (long) note << 16; }
  void setParams(int t, int p, int l) throws Exception {
    port[t] = p; leg[t] = l;
    wr(COPY + 106 * t + 92, (long) p << 8, 2); wr(COPY + 106 * t + 94, (long) l << 8, 2);
  }
  /** one tick of the rate loop's hook for all 8 tracks; returns the note sums it gave */
  long[] tick(StringBuilder bad, boolean check) throws Exception {
    long[] got = new long[8];
    for (int t = 0; t < 8; t++) {
      long sp = SP0 - 0x100;
      emu.writeRegister("SP", sp); emu.writeRegister("PC", RATE_SITE);
      long[] sent = new long[KEEP_RATE.length];
      for (int i = 0; i < KEEP_RATE.length; i++) { sent[i] = 0x5a5a0000L + i; emu.writeRegister(KEEP_RATE[i], sent[i]); }
      emu.writeRegister("A6", t); sent[8] = t;
      long a2 = MIRROR_TUNE + 106L * t; emu.writeRegister("A2", a2); sent[4] = a2;
      for (String r : new String[] {"D0","D1","D4","D5","D6","A0"}) emu.writeRegister(r, 0x77777777L);
      boolean done = false;
      for (int s = 0; s < 300; s++) {
        if (emu.getExecutionAddress().getOffset() == RATE_END) { done = true; break; }
        if (!emu.step(monitor)) { bad.append(" [rate fault " + emu.getLastError() + "]"); return got; }
      }
      if (!done) { bad.append(" [rate ran away]"); return got; }
      got[t] = rd("D6");
      long want = feature ? modelTick(t) : rdn(NOTES + 4 * t, 4);
      if (check && got[t] != want && bad.length() < 500) bad.append(String.format(" [t%d d6 0x%x, want 0x%x]", t, got[t], want));
      if (rd("D1") != 3) bad.append(" [d1 " + rd("D1") + "]");
      if (rd("SP") != sp) bad.append(" [rate stack]");
      for (int i = 0; i < KEEP_RATE.length; i++) if (rd(KEEP_RATE[i]) != sent[i]) bad.append(" [rate " + KEEP_RATE[i] + "]");
    }
    return got;
  }
  long[] ticks(int n, StringBuilder bad) throws Exception { long[] g = null; for (int i = 0; i < n; i++) g = tick(bad, true); return g; }
  void begin() throws Exception {
    fresh(); modelFromMemory();
    for (int t = 0; t < 8; t++) { setParams(t, 0, 0); target[t] = 0; }
  }

  // ---- glide cases (S31)
  void glideCases() throws Exception {
    StringBuilder bad;
    // PORT 0: every note at once, LEG off and on
    begin(); bad = new StringBuilder();
    int[] seq = {60, 67, 48, 72, 36};
    for (int l = 0; l < 2; l++) for (int n : seq) { setParams(0, 0, l); noteOn(0, n, l == 1, bad); long[] g = ticks(1, bad); if (g[0] != (long) n << 16) bad.append(" [PORT 0 not at once]"); }
    verdict("PORT 0: every note plays its own pitch at once (LEG off and on)", bad);
    // PORT 40, LEG off: a detached note glides from the last; it moves toward the target every tick
    begin(); bad = new StringBuilder();
    setParams(0, 40, 0); noteOn(0, 48, false, bad); ticks(5, bad); gateOff(0);
    noteOn(0, 60, false, bad);
    long prev = 48L << 16; int steps = 0;
    for (int i = 0; i < 40; i++) { long[] g = tick(bad, true); if (g[0] <= prev || g[0] > 60L << 16) bad.append(" [not rising toward 60]"); prev = g[0]; steps++; }
    if (prev == 60L << 16) bad.append(" [arrived too soon for PORT 40]");
    verdict("PORT 40, LEG off: a detached note glides up from the last note", bad);
    // arrival: PORT 16 reaches the target exactly, then stays
    begin(); bad = new StringBuilder();
    setParams(0, 16, 0); noteOn(0, 48, false, bad); ticks(1, bad); noteOn(0, 60, true, bad);
    int at = -1;
    for (int i = 0; i < 1000 && at < 0; i++) { long[] g = tick(bad, true); if (g[0] == 60L << 16) at = i; }
    if (at < 0) bad.append(" [never arrived]");
    long[] g2 = ticks(20, bad); if (g2[0] != 60L << 16) bad.append(" [left the target]");
    verdict("PORT 16: the glide arrives exactly (after " + at + " ticks) and stays", bad);
    // glide down
    begin(); bad = new StringBuilder();
    setParams(0, 24, 0); noteOn(0, 72, false, bad); ticks(1, bad); noteOn(0, 36, false, bad);
    prev = 72L << 16;
    for (int i = 0; i < 30; i++) { long[] g = tick(bad, true); if (g[0] >= prev) bad.append(" [not falling]"); prev = g[0]; }
    verdict("PORT 24: a lower note glides down", bad);
    // LEG on: legato glides, detached starts on its pitch
    begin(); bad = new StringBuilder();
    setParams(0, 40, 1); noteOn(0, 48, false, bad); ticks(3, bad);
    noteOn(0, 60, true, bad); long[] g = ticks(1, bad);
    if (g[0] == 60L << 16 || g[0] == 48L << 16) bad.append(" [legato note did not glide]");
    ticks(10, bad); gateOff(0);
    noteOn(0, 55, false, bad); g = ticks(1, bad);
    if (g[0] != 55L << 16) bad.append(" [detached note glided]");
    noteOn(0, 67, true, bad); g = ticks(1, bad);
    if (g[0] == 67L << 16) bad.append(" [second legato note did not glide]");
    verdict("LEG on: a legato note glides, a detached note starts on its pitch", bad);
    // LEG on, the same note again, detached, while gliding: starts on it
    begin(); bad = new StringBuilder();
    setParams(0, 60, 1); noteOn(0, 40, false, bad); ticks(1, bad); noteOn(0, 64, true, bad); ticks(5, bad); gateOff(0);
    noteOn(0, 64, false, bad); g = ticks(1, bad);
    if (g[0] != 64L << 16) bad.append(" [mid-glide retrig did not start on its pitch]");
    verdict("LEG on: the target note again, detached, mid-glide starts on its pitch", bad);
    // a note change without a trig glides
    begin(); bad = new StringBuilder();
    setParams(0, 40, 1); noteOn(0, 50, false, bad); ticks(2, bad);
    noteOnly(0, 62); g = ticks(1, bad);
    if (g[0] == 62L << 16 || g[0] == 50L << 16) bad.append(" [no glide]");
    ticks(10, bad);
    verdict("a note change without a trig (lock trig) glides", bad);
    // PORT locked to 0 mid-glide: jumps to the target
    begin(); bad = new StringBuilder();
    setParams(0, 50, 0); noteOn(0, 40, false, bad); ticks(1, bad); noteOn(0, 70, false, bad); ticks(5, bad);
    setParams(0, 0, 0); g = ticks(1, bad);
    if (g[0] != 70L << 16) bad.append(" [no jump]");
    verdict("PORT set to 0 mid-glide: the note jumps to the target", bad);
    // all eight tracks at once, each its own PORT, LEG, notes and gates
    begin(); bad = new StringBuilder();
    Random r = new Random(54);
    for (int t = 0; t < 8; t++) setParams(t, new int[] {0, 8, 20, 40, 64, 90, 127, 3}[t], t & 1);
    for (int round = 0; round < 12; round++) {
      for (int t = 0; t < 8; t++) if (r.nextInt(3) == 0) noteOn(t, 24 + r.nextInt(60), r.nextBoolean(), bad);
      if (round % 4 == 3) setParams(r.nextInt(8), r.nextInt(128), r.nextInt(2));
      ticks(1 + r.nextInt(15), bad);
    }
    verdict("eight tracks at once: own PORT, LEG, notes and gates, every tick as the model", bad);
    // power-up: the state holds garbage; each track's first note starts on its pitch
    begin(); bad = new StringBuilder();
    byte[] junk = new byte[40]; new Random(7).nextBytes(junk); emu.writeMemory(toAddr(STATE), junk);
    modelFromMemory();
    for (int t = 0; t < 8; t++) setParams(t, 64, 0);
    for (int t = 0; t < 8; t++) noteOn(t, 40 + t, false, bad);
    g = ticks(1, bad);
    for (int t = 0; t < 8; t++) if (g[t] != (long) (40 + t) << 16) bad.append(String.format(" [t%d first note 0x%x]", t, g[t]));
    if (rdn(STATE + 32, 4) != MAGICV) bad.append(" [marker not set]");
    for (int t = 0; t < 8; t++) noteOn(t, 70 - t, false, bad);
    ticks(10, bad);
    verdict("power-up garbage: every track's first note starts on its pitch, then glides", bad);
  }

  // ---- inert hooks (S29, S30): replay only
  void inertCases() throws Exception {
    begin(); StringBuilder bad = new StringBuilder();
    for (int t = 0; t < 8; t++) setParams(t, 100, 1);
    for (int t = 0; t < 8; t++) noteOn(t, 30 + 5 * t, (t & 1) == 0, bad);
    ticks(3, bad);
    noteOnly(3, 90); ticks(2, bad);
    byte[] st = emu.readMemory(toAddr(STATE), 40);
    for (byte x : st) if (x != 0) { bad.append(" [state written]"); break; }
    verdict("inert hooks: the trig's note stored and returned as stock, nothing else", bad);
  }

  // ---- the TRIG page's data (S30, S31)
  void dataCases() throws Exception {
    StringBuilder bad = new StringBuilder();
    fresh();
    for (int i = 4; i <= 5; i++) {
      long r = DESC + 0x34L * i;
      long page = rdn(r, 4), slot = rdn(r + 4, 4), mx = rdn(r + 12, 4), def = rdn(r + 16, 4), cc = rdn(r + 0x18, 4), nrpn = rdn(r + 0x1c, 4), fl = rdn(r + 0x24, 4);
      String ln = cstr(rdn(r + 0x28, 4)), sn = cstr(rdn(r + 0x30, 4));
      String want = i == 4 ? "5 46 0x7f00 0 PORT Portamento" : "5 47 0x100 0 LEG Legato";
      String gotS = String.format("%d %d 0x%x %d %s %s", page, slot, mx, def, sn, ln);
      if (!gotS.equals(want) || cc != 0xffffffffL || nrpn != 0xffffffffL || fl != 0) bad.append(" [row " + i + ": " + gotS + String.format(" cc %x nrpn %x flags %x]", cc, nrpn, fl));
    }
    verdict("rows 4, 5: PORT 0..127 and LEG 0..1, sound page 5, slots 46, 47, no CC, names", bad);
    // the CC-table build: the stock rows against the load's rows
    long lo = 0x4199f700L, hi = 0x419a1700L;
    byte[][] tab = new byte[2][];
    for (int pass = 0; pass < 2; pass++) {
      fresh();
      if (pass == 0) for (int i = 4; i <= 5; i++) {
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
    // the slot map's entries 46 and 47: whatever the builder's clear leaves in stock, then 4 and 5
    long map = 0x4199f8f0L;
    String wantDiff = String.format(" 0x%x: %x -> 4; 0x%x: %x -> 5;", map + 184, word(tab[0], (int) (map + 184 - lo)),
        map + 188, word(tab[0], (int) (map + 188 - lo)));
    if (!diff.toString().equals(wantDiff)) bad.append(" [tables differ:" + diff + " want" + wantDiff + "]");
    verdict("CC tables as stock (CC 7 VOL, CC 10 PAN); the slot map gives 46 -> 4, 47 -> 5", bad);
    // the TRIG layout's stores
    bad = new StringBuilder();
    fresh();
    emu.writeMemory(toAddr(0x4197dfb4L), new byte[44]);
    emu.writeRegister("SP", SP0 - 0x100); emu.writeRegister("PC", 0x401565e0L);
    for (int s = 0; s < 100 && emu.getExecutionAddress().getOffset() != 0x4015662eL; s++) if (!emu.step(monitor)) break;
    long[] ids = new long[9];
    for (int k = 0; k < 9; k++) ids[k] = rdn(0x4197dfbcL + 4 * k, 4);
    if (!Arrays.equals(ids, new long[] {18, 19, 20, 29, 23, 24, 4, 5, 10})) bad.append(" [TRIG record " + Arrays.toString(ids) + "]");
    verdict("TRIG page: NOTE VEL LEN PROB FLT.T LFO.T PORT LEG, level on the 9th", bad);
    // the display objects of ids 4 and 5: their copy routines stubbed, the sources recorded
    bad = new StringBuilder();
    fresh();
    long S2 = 0x40001100L, S3 = 0x40001200L, S4 = 0x40001300L;
    emu.writeRegister("SP", SP0 - 0x200); emu.writeRegister("PC", 0x401533eeL);
    emu.writeRegister("A2", S2); emu.writeRegister("A3", S3); emu.writeRegister("A4", S4);
    emu.writeRegister("D2", 0x4018e18cL); emu.writeRegister("D3", 0x4018e1acL);
    wr(0x4197e448L, 0x1111, 4); wr(0x4197e49cL, 0x2222, 4);
    List<String> calls = new ArrayList<>();
    for (int s = 0; s < 400 && emu.getExecutionAddress().getOffset() != 0x4015346eL; s++) {
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
    String wantC = "tmpl 4197e44c<-4018e18c, text 4197e45c<-4197d7fc, pic 4197e46c<-4197d58c, "
        + "tmpl 4197e4a0<-4018e1ac, text 4197e4b0<-4197d6ac, pic 4197e4c0<-4197d37c";
    if (!gotC.equals(wantC)) bad.append(" [" + gotC + "]");
    if (rdn(0x4197e448L, 4) != 0 || rdn(0x4197e49cL, 4) != 0) bad.append(" [+0 words not cleared]");
    verdict("display objects: PORT '%d' knob, LEG OFF/ON switch with the selector step", bad);
  }

  public void run() throws Exception {
    String[] args = getScriptArgs();
    if (args.length < 1) { printerr("usage: EmuPortamento.java <stage.load>"); return; }
    for (String line : Files.readAllLines(Paths.get(args[0]))) {
      String[] p = line.trim().split("\\s+");
      if (p.length == 3 && p[0].equals("sym")) sym.put(p[1], Long.parseLong(p[2], 16));
      else if (p.length == 2) {
        byte[] b = new byte[p[1].length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(p[1].substring(2 * i, 2 * i + 2), 16);
        secAddr.add(Long.parseLong(p[0], 16)); secData.add(b);
      }
    }
    feature = sym.containsKey("snap");
    fresh();
    boolean hooks = rdn(ON_SITE, 2) == 0x4eb9 && rdn(RATE_SITE, 2) == 0x4eb9;
    boolean data = rdn(DESC + 0x34 * 4, 4) == 5;
    println(String.format("EmuPortamento %s: hooks %s, %s, TRIG data %s", args[0], hooks ? "present" : "absent",
        feature ? "glide" : "inert", data ? "present" : "absent"));
    if (sym.containsKey("cfo_note")) {
      long op = rdn(sym.get("cfo_note"), 4), want = feature ? STATE : NOTES;
      StringBuilder bad = new StringBuilder();
      if (op != want) bad.append(String.format(" [0x%x]", op));
      verdict("the CFO oscillator reads its note from " + (feature ? "the glided notes" : "the trig's notes"), bad);
    }
    if (hooks) { if (feature) glideCases(); else inertCases(); }
    if (data) dataCases();
    println(fails == 0 ? "=== ALL CASES PASS ===" : "=== " + fails + " CASE(S) FAIL ===");
    emu.dispose();
  }
}
