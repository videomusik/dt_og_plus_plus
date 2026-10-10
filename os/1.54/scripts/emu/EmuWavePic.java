// wave_pictures: run CFOO's wave pictures of a stage image in Ghidra's p-code emulator, from the entry of
// ParameterSet::vfunc_23 (0x4000f2bc, whose first 8 B jump to the CFO oscillator's picture hook cfo_pic)
// to its return or to the point where the stock code takes over again, and compare with a model written
// here from the description.
//
// The call: vfunc_23(set, id, value, flag, -, canvas, x, y), the set's machine from cfo_pic's query
// (0x400f7c0c, stubbed to return the machine of the case), the stock bitmap drawer 0x400c2b88 stubbed to
// record its arguments and copy the Bitmap it gets (28 B, the plane and the mask). Cases: machines 5
// (CFOO) and 0, ids 106..117, and values 0..255 with a fraction for A, C and D on CFOO.
// - Without the pictures (S54) every CFOO knob reaches the stock code at 0x4000f2c4 as cfo_pic sends it
//   there: id 112 (STRT's knob), and the drawer is not called.
// - With them (S55) A, C and D (ids 108, 110, 111) on CFOO call the drawer once with (canvas, bitmap, x,
//   y, 0) and return to the caller, the stack as before, %d2-%d7 and %a2-%a6 kept. The Bitmap: the
//   vtable 0x401b7734, 17 x 17, one word a column, the plane, the mask, 0. The model: the value's whole
//   part, at most 127; the CFO oscillator's segment and fraction (pure at 0, 42, 85, 127; the fraction
//   (d x 512 + L) / 2L in its segment of length L = 42, 43, 42); the two tables read from the image at
//   0x40252724; each of 17 columns the sample a + ((b - a) x fraction >> 8) at index 16 x column mod 256,
//   its row ((127 - sample) x 4112 + 0x8000) >> 16, the column's ink from the row before (column 0: its
//   own) to its own, row r as bit 15 + r; every mask word 0xffff8000. The rows are also held against
//   (127 - sample) x 16 / 255 within 1/2 for every sample -128..127. Every other knob and machine 0
//   reach 0x4000f2c4 as cfo_pic sends them.
//   ./scripts/ghidra_emu.sh 1.54 EmuWavePic work/dt_1.54-wavpic/<stage>.load
// Runs in Ghidra's emulator only; nothing touches a device. @category dt_og_plus_plus
import ghidra.app.script.GhidraScript;
import ghidra.app.emulator.EmulatorHelper;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

public class EmuWavePic extends GhidraScript {
  static final long ENTRY = 0x4000f2bcL, STOCK = 0x4000f2c4L, MACHINE = 0x400f7c0cL, DRAW = 0x400c2b88L;
  static final long WAVES = 0x40252724L, BITMAP = 0x401b7734L;
  static final long SP0 = 0x40258600L, RET = 0x40001000L, CANVAS = 0x42380000L;
  static final String[] KEEP = {"D2","D3","D4","D5","D6","D7","A2","A3","A4","A5","A6"};

  List<Long> secAddr = new ArrayList<>();
  List<byte[]> secData = new ArrayList<>();
  Map<String, Long> sym = new HashMap<>();
  EmulatorHelper emu;
  int fails = 0;
  int[] waves = new int[1024];

  void wr(long a, long v, int n) throws Exception {
    byte[] b = new byte[n]; for (int i = 0; i < n; i++) b[n - 1 - i] = (byte) ((v >> (8 * i)) & 0xff);
    emu.writeMemory(toAddr(a), b);
  }
  long rdn(long a, int n) throws Exception {
    byte[] b = emu.readMemory(toAddr(a), n); long v = 0; for (int i = 0; i < n; i++) v = (v << 8) | (b[i] & 0xff); return v;
  }
  long rd(String r) throws Exception { return emu.readRegister(r).longValue() & 0xffffffffL; }
  void fresh() throws Exception {
    if (emu != null) emu.dispose();
    emu = new EmulatorHelper(currentProgram);
    for (int k = 0; k < secAddr.size(); k++) emu.writeMemory(toAddr(secAddr.get(k)), secData.get(k));
    emu.writeMemory(toAddr(SP0 - 0x800), new byte[0x1000]);
  }
  void verdict(String label, StringBuilder bad) {
    println(String.format("  %-78s %s%s", label, bad.length() == 0 ? "OK" : "**FAIL**", bad.length() > 600 ? bad.substring(0, 600) + " ..." : bad));
    if (bad.length() != 0) fails++;
  }

  // ---- the model
  static int[] segfrac(int v) {
    if (v < 42) return new int[] {0, (v * 512 + 42) / 84};
    if (v < 85) return new int[] {1, ((v - 42) * 512 + 43) / 86};
    return new int[] {2, ((v - 85) * 512 + 42) / 84};
  }
  static int row(int s) { return ((127 - s) * 4112 + 0x8000) >> 16; }
  long[] modelPlane(int value) {
    int v = (value & 0xff00) >> 8; if (v > 127) v = 127;
    int[] sf = segfrac(v);
    long[] plane = new long[17];
    int prev = 0;
    for (int c = 0; c < 17; c++) {
      int i = (16 * c) & 0xff;
      int a = waves[256 * sf[0] + i], b = waves[256 * sf[0] + 256 + i];
      int s = a + (((b - a) * sf[1]) >> 8);
      int r = row(s);
      if (c == 0) prev = r;
      int lo = Math.min(prev, r), hi = Math.max(prev, r);
      long w = 0;
      for (int y = lo; y <= hi; y++) w |= 1L << (15 + y);
      plane[c] = w;
      prev = r;
    }
    return plane;
  }
  static String picture(long[] plane) {
    StringBuilder s = new StringBuilder();
    for (int y = 0; y < 17; y++) {
      for (int c = 0; c < 17; c++) s.append(((plane[c] >> (15 + y)) & 1) != 0 ? '#' : '.');
      s.append('\n');
    }
    return s.toString();
  }

  // ---- one call
  long[] drawArgs; long[] drawPlane, drawMask; long[] drawBmp; int draws;
  /** vfunc_23 from its entry: returns "ret" (returned to the caller), "stock" (reached 0x4000f2c4) or a fault */
  String call(long machine, long id, long value, StringBuilder bad) throws Exception {
    long sp = SP0 - 0x200;
    wr(sp, RET, 4); wr(sp + 4, 0x42300000L, 4); wr(sp + 8, id, 4); wr(sp + 12, value, 4); wr(sp + 16, 1, 4);
    wr(sp + 20, 0x55555555L, 4); wr(sp + 24, CANVAS, 4); wr(sp + 28, 37, 4); wr(sp + 32, 9, 4);
    long[] keep = {0x22222222L, 0x33333333L, 0x44444444L, 0x55555555L, 0x66666666L, 0x77777777L, 0xa2a2a2a2L, 0xa3a3a3a3L, 0xa4a4a4a4L, 0xa5a5a5a5L, 0xa6a6a6a6L};
    for (int i = 0; i < KEEP.length; i++) emu.writeRegister(KEEP[i], keep[i]);
    emu.writeRegister("SP", sp); emu.writeRegister("PC", ENTRY);
    draws = 0;
    for (int s = 0; s < 20000; s++) {
      long pc = emu.getExecutionAddress().getOffset();
      if (pc == RET) {
        StringBuilder b = new StringBuilder();
        if (rd("SP") != sp + 4) b.append(String.format(" sp %x", rd("SP")));
        for (int i = 0; i < KEEP.length; i++) if (rd(KEEP[i]) != keep[i]) b.append(" " + KEEP[i]);
        if (b.length() > 0 && bad.length() < 300) bad.append(String.format(" [id %d value %x:%s]", id, value, b));
        return "ret";
      }
      if (pc == STOCK) {
        StringBuilder b = new StringBuilder();
        if (rd("SP") != sp - 20) b.append(String.format(" sp %x", rd("SP")));
        if (b.length() > 0 && bad.length() < 300) bad.append(String.format(" [id %d value %x at the stock code:%s]", id, value, b));
        return "stock";
      }
      if (pc == MACHINE) {
        long q = rd("SP");
        emu.writeRegister("D0", machine); emu.writeRegister("PC", rdn(q, 4)); emu.writeRegister("SP", q + 4);
        continue;
      }
      if (pc == DRAW) {
        long q = rd("SP");
        drawArgs = new long[5];
        for (int i = 0; i < 5; i++) drawArgs[i] = rdn(q + 4 + 4 * i, 4);
        long bm = drawArgs[1];
        drawBmp = new long[7];
        for (int i = 0; i < 7; i++) drawBmp[i] = rdn(bm + 4 * i, 4);
        drawPlane = new long[17]; drawMask = new long[17];
        for (int c = 0; c < 17; c++) { drawPlane[c] = rdn(drawBmp[4] + 4 * c, 4); drawMask[c] = rdn(drawBmp[5] + 4 * c, 4); }
        draws++;
        emu.writeRegister("D0", 0xdead0000L); emu.writeRegister("D1", 0xdead0001L);
        emu.writeRegister("A0", 0xdead00a0L); emu.writeRegister("A1", 0xdead00a1L);
        emu.writeRegister("PC", rdn(q, 4)); emu.writeRegister("SP", q + 4);
        continue;
      }
      if (!emu.step(monitor)) return "fault " + emu.getLastError() + " at " + Long.toHexString(pc);
    }
    return "ran away";
  }

  public void run() throws Exception {
    String[] args = getScriptArgs();
    if (args.length < 1) { printerr("usage: EmuWavePic.java <stage.load>"); return; }
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
    for (int i = 0; i < 1024; i++) waves[i] = (byte) rdn(WAVES + i, 1);
    boolean pic = sym.containsKey("wav_pic");
    boolean hook = sym.containsKey("wav_sel") && rdn(0x400f7c5cL, 2) == 0x4ef9 && rdn(0x400f7c5eL, 4) == sym.get("wav_sel");
    println(String.format("EmuWavePic %s: hook %s, pictures %s", args[0], hook ? "present" : "absent", pic ? "present" : "absent"));
    // the rows against the exact scale
    StringBuilder bad = new StringBuilder();
    double worst = 0;
    for (int s = -128; s <= 127; s++) {
      double e = Math.abs(row(s) - (127 - s) * 16.0 / 255);
      worst = Math.max(worst, e);
      if (e > 0.5) bad.append(String.format(" [%d: row %d, %.3f]", s, row(s), (127 - s) * 16.0 / 255));
    }
    verdict(String.format("the model's rows: (127 - sample) x 16 / 255 within 1/2 for -128..127 (worst %.4f)", worst), bad);
    // every knob: where it goes
    bad = new StringBuilder();
    int n = 0, toStock = 0, toRet = 0;
    for (long machine : new long[] {5, 0}) for (long id = 106; id <= 117; id++) for (long value : new long[] {0, 0x2a00, 0x5580, 0x7f00, 0xff00}) {
      fresh();
      String r = call(machine, id, value, bad);
      n++;
      boolean wave = pic && machine == 5 && (id == 108 || id == 110 || id == 111);
      String want = wave ? "ret" : "stock";
      if (!r.equals(want)) { if (bad.length() < 400) bad.append(String.format(" [machine %d id %d value %x: %s, want %s]", machine, id, value, r, want)); continue; }
      if (r.equals("stock")) {
        toStock++;
        long sid = rdn(rd("SP") + 20 + 8, 4);
        long wantId = machine == 5 && id >= 108 && id <= 115 ? 112 : id;
        if (sid != wantId) bad.append(String.format(" [machine %d id %d: stock code gets id %d, want %d]", machine, id, sid, wantId));
        if (draws != 0) bad.append(String.format(" [machine %d id %d: drawn]", machine, id));
      } else {
        toRet++;
        if (draws != 1) bad.append(String.format(" [id %d: %d draws]", id, draws));
      }
    }
    verdict(String.format("%s: %d calls, %d to the stock code, %d drawn here", pic ? "A, C, D on CFOO drawn here, every other knob and machine to the stock code"
        : "every knob to the stock code as cfo_pic sends it (STRT's id for CFOO's)", n, toStock, toRet), bad);
    if (!pic) { println(fails == 0 ? "=== ALL CASES PASS ===" : "=== " + fails + " CASE(S) FAIL ==="); emu.dispose(); return; }
    // the pictures against the model
    bad = new StringBuilder();
    StringBuilder argBad = new StringBuilder();
    int pics = 0;
    for (long id : new long[] {108, 110, 111}) for (int v = 0; v < 256; v++) {
      long value = ((long) v << 8) | (v * 37 & 0xff);
      fresh();
      String r = call(5, id, value, bad);
      if (!r.equals("ret") || draws != 1) { if (bad.length() < 400) bad.append(String.format(" [id %d v %d: %s, %d draws]", id, v, r, draws)); continue; }
      pics++;
      if (drawArgs[0] != CANVAS || drawArgs[2] != 37 || drawArgs[3] != 9 || drawArgs[4] != 0) argBad.append(String.format(" [id %d v %d: args %s]", id, v, Arrays.toString(drawArgs)));
      if (drawBmp[0] != BITMAP || drawBmp[1] != 17 || drawBmp[2] != 17 || drawBmp[3] != 1 || drawBmp[6] != 0) argBad.append(String.format(" [id %d v %d: bitmap %s]", id, v, Arrays.toString(drawBmp)));
      long[] want = modelPlane((int) value);
      for (int c = 0; c < 17; c++) {
        if (drawMask[c] != 0xffff8000L) { argBad.append(String.format(" [mask %d: %x]", c, drawMask[c])); break; }
        if (drawPlane[c] != want[c]) { if (bad.length() < 400) bad.append(String.format(" [id %d v %d column %d: %08x, want %08x]", id, v, c, drawPlane[c], want[c])); break; }
      }
    }
    verdict(String.format("drawer called with (canvas, Bitmap 17 x 17 a word a column, x, y, 0), every mask word 0xffff8000 (%d pictures)", pics), argBad);
    verdict(String.format("the pictures: the model's 17 columns for every value 0..255 on A, C, D (%d pictures)", pics), bad);
    for (int v : new int[] {0, 42, 85, 127}) {
      fresh(); call(5, 108, (long) v << 8, new StringBuilder());
      println(String.format("  WAV1 %d:\n%s", v, picture(drawPlane)));
    }
    println(fails == 0 ? "=== ALL CASES PASS ===" : "=== " + fails + " CASE(S) FAIL ===");
    emu.dispose();
  }
}
