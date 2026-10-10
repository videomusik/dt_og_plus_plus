// pool_cursors: step the multi-cursor draw core at 0x40015558 with its four callees STUBBED, and
// assert the contract that the two draw-tail hook sites impose on it.
//
// Context: the core runs at the END of the waveform-widget draw, entered by `jsr` from the tail, just
// before the draw reloads d2-d6/a2-a5 from its frame and returns. It may therefore clobber those, but
// it must (1) leave SP exactly where it found it, (2) never write into the draw's saved-register block
// or anything above its own return address, (3) not touch d7/a6, (4) pass FUN_4001ccc4 its argument
// at 4(sp) (a pad that gets this wrong hands the callee a return address instead of an object, and the
// callee's vtable call then faults at boot), and (5) draw exactly the right set of extra cursors at
// exactly the right x, then clear this widget's dirty byte and set the marker widget's dirty byte iff
// it drew.
//
// The four callees are stubbed at their entry addresses (the emulator never executes them):
//   FUN_40138882 ()          -> d0 = FAKEOBJ
//   FUN_4001d24e (obj)       -> asserts 4(sp) == FAKEOBJ+0x30 ; d0 = the case's track
//   FUN_40076258 (voice)     -> d0 = pos[voice]  (8.8 fraction; 0 = silent)
//   FUN_400c1268 (canvas,x,y1,y2,mode) -> records the call
//
// Memory is laid out like the real page: marker widget = PAGE+0x1d8, grid widget = PAGE+0x20c, so that
// the grid stub's "&marker.dirty = grid-3" really lands on PAGE+0x1d8+0x31.
//
// The canvas register is the one each draw keeps it in at its hook: %d2 in the marker draw
// FUN_400bd254, %d4 in the grid draw FUN_400bd732. The other data register holds junk, so a stub that
// read the wrong one would hand the line primitive a wrong canvas, which the canvas check catches.
//
// Argument: the decompressed MAIN OS of a DT OG++ build (./scripts/extract.sh 1.54:out/1.54/<build>.syx,
// then work/dt_1.54-<build>/section_3_MAIN_OS.bin), from which the 190-byte core is read at 0x40015558;
// or a raw file holding just the core, assembled for that address.
//   ./scripts/ghidra_emu.sh 1.54 EmuCursorCore work/dt_1.54-<build>/section_3_MAIN_OS.bin
// Runs in Ghidra's emulator only; nothing touches a device. @category dt_og_plus_plus
import ghidra.app.script.GhidraScript;
import ghidra.app.emulator.EmulatorHelper;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class EmuCursorCore extends GhidraScript {
  static final long PAD        = 0x40015558L;
  static final int  PAD_LEN    = 190;
  static final long STUB_MARKER= PAD, STUB_GRID = PAD + 10;
  static final long GETPROJ    = 0x40138882L, CURTRACK = 0x4001d24eL, POSFRAC = 0x40076258L, VLINE = 0x400c1268L;
  static final long GROUPSRC   = 0x439d1050L;
  static final long FAKEOBJ    = 0x421f1d00L;
  static final long PAGE       = 0x439d1400L;            // fake page object
  static final long MARKER_W   = PAGE + 0x1d8, GRID_W = PAGE + 0x20c;
  static final long STACKTOP   = 0x40258800L;
  static final long CALLER_RET = 0x400bd5b6L;            // the marker tail's return point (any value works)
  static final long CANVAS     = 0x44100000L;
  static final String CANVAS_REG_MARKER = "D2", CANVAS_REG_GRID = "D4";
  static final int  XORIG = 4, YORIG = 10, WIDTH = 120, HEIGHT = 40;

  static final int    MAIN_SIZE = 2479680;
  static final long   MAIN_BASE = 0x40000400L;
  static final String STOCK_MAIN_SHA256 = "5c58bf9e3949ef09977c5fc007a61e8d026931f67f1621238379dfb8ee4d31a2";
  static final String REF_MAIN_SHA256   = "d6fac1a35cf565c37fc43ae51bd0c76f1ee7e23c0668ff1d245bb2839e51509c";

  byte[] padBytes;

  /** Pad bytes from a raw pad file, or sliced out of a whole MAIN OS image (file offset = addr - base). */
  byte[] loadPad(String path, long addr, int len) throws Exception {
    byte[] f = Files.readAllBytes(Paths.get(path));
    if (f.length != MAIN_SIZE) return f;
    StringBuilder h = new StringBuilder();
    for (byte b : MessageDigest.getInstance("SHA-256").digest(f)) h.append(String.format("%02x", b & 0xff));
    if (h.toString().equals(STOCK_MAIN_SHA256))
      throw new IllegalArgumentException(path + " is the STOCK MAIN OS; give the MAIN OS of a DT OG++ build");
    if (!h.toString().equals(REF_MAIN_SHA256))
      println("  note: not the reference build's MAIN OS; stepping the pad bytes this image holds");
    int off = (int) (addr - MAIN_BASE);
    return Arrays.copyOfRange(f, off, off + len);
  }

  EmulatorHelper emu;
  void wr(long a, long v, int n) throws Exception {
    byte[] b = new byte[n]; for (int i = 0; i < n; i++) b[n - 1 - i] = (byte) ((v >> (8 * i)) & 0xff);
    emu.writeMemory(toAddr(a), b);
  }
  long rd(String r) throws Exception { return emu.readRegister(r).longValue() & 0xffffffffL; }
  long rd32(long a) throws Exception { byte[] b = emu.readMemory(toAddr(a), 4); long v = 0; for (int i = 0; i < 4; i++) v = (v << 8) | (b[i] & 0xff); return v; }
  int  rd8(long a)  throws Exception { return emu.readMemory(toAddr(a), 1)[0] & 0xff; }

  static class Line { long canvas, x, y1, y2, mode; Line(long c,long x,long y1,long y2,long m){canvas=c;this.x=x;this.y1=y1;this.y2=y2;mode=m;} }

  // expected x for a voice: xOrigin + ((width * pos) >> 8)  (the stock setter's arithmetic)
  static long xFor(int pos) { return XORIG + ((WIDTH * pos) >> 8); }

  boolean one(String label, boolean grid, long track, int[] map, int[] pos) throws Exception {
    emu = new EmulatorHelper(currentProgram);
    emu.writeMemory(toAddr(PAD), padBytes);
    emu.writeMemory(toAddr(STACKTOP - 0x800), new byte[0x1000]);
    emu.writeMemory(toAddr(GROUPSRC), new byte[16]);
    for (int i = 0; i < 8; i++) wr(GROUPSRC + i, map[i], 1);
    emu.writeMemory(toAddr(PAGE), new byte[0x400]);
    // marker widget fields (marker layout) and grid widget fields (marker layout - 4)
    wr(MARKER_W + 0x1c, XORIG, 4); wr(MARKER_W + 0x20, YORIG, 4); wr(MARKER_W + 0x24, WIDTH, 4); wr(MARKER_W + 0x28, HEIGHT, 4);
    wr(MARKER_W + 0x31, 0x55, 1);                               // marker.dirty sentinel
    wr(GRID_W + 0x18, XORIG, 4);   wr(GRID_W + 0x1c, YORIG, 4);   wr(GRID_W + 0x20, WIDTH, 4);   wr(GRID_W + 0x24, HEIGHT, 4);
    wr(GRID_W + 0x2d, 0x77, 1);                                 // grid.dirty sentinel
    if (!grid) wr(MARKER_W + 0x31, 0x77, 1);                    // marker case: its own dirty is the one displaced

    // stack: [sp] = our return address; above it the draw's saved-register block (36 B) + its return +
    // its two args, filled with a canary we check afterwards
    long sp = STACKTOP - 0x100;
    wr(sp, CALLER_RET, 4);
    long[] canary = new long[12];
    for (int i = 0; i < 12; i++) { canary[i] = 0xC0DE0000L + i; wr(sp + 4 + 4 * i, canary[i], 4); }
    emu.writeRegister("SP", sp);
    emu.writeRegister("A2", grid ? GRID_W : MARKER_W);
    // the canvas in the register this draw keeps it in; junk in the registers the other draw uses
    for (String r : new String[]{"D2", "D3", "D4"}) emu.writeRegister(r, 0x11111111L);
    emu.writeRegister(grid ? CANVAS_REG_GRID : CANVAS_REG_MARKER, CANVAS);
    long d7 = 0x77777777L, a6 = 0x66666666L;
    emu.writeRegister("D7", d7); emu.writeRegister("A6", a6);
    emu.writeRegister("PC", grid ? STUB_GRID : STUB_MARKER);

    List<Line> lines = new ArrayList<>();
    List<Long> posCalls = new ArrayList<>();
    boolean curtrackArgOk = false, curtrackHit = false, getprojHit = false;
    StringBuilder log = new StringBuilder();

    for (int steps = 0; steps < 20000; steps++) {
      long pc = emu.getExecutionAddress().getOffset();
      if (pc == GETPROJ) { getprojHit = true; long s = rd("SP"); emu.writeRegister("D0", FAKEOBJ); emu.writeRegister("SP", s + 4); emu.writeRegister("PC", rd32(s)); continue; }
      if (pc == CURTRACK) { curtrackHit = true; long s = rd("SP"); long arg = rd32(s + 4); curtrackArgOk = (arg == FAKEOBJ + 0x30);
        if (!curtrackArgOk) log.append(String.format(" [CURTRACK saw arg 0x%08x, want 0x%08x]", arg, FAKEOBJ + 0x30));
        emu.writeRegister("D0", track & 0xffffffffL); emu.writeRegister("SP", s + 4); emu.writeRegister("PC", rd32(s)); continue; }
      if (pc == POSFRAC) { long s = rd("SP"); long v = rd32(s + 4); posCalls.add(v);
        long p = (v >= 0 && v < 8) ? pos[(int) v] : 0xDEAD;
        emu.writeRegister("D0", p); emu.writeRegister("SP", s + 4); emu.writeRegister("PC", rd32(s)); continue; }
      if (pc == VLINE) { long s = rd("SP"); lines.add(new Line(rd32(s + 4), rd32(s + 8), rd32(s + 12), rd32(s + 16), rd32(s + 20)));
        emu.writeRegister("SP", s + 4); emu.writeRegister("PC", rd32(s)); continue; }
      if (pc == CALLER_RET) {
        // ---- assertions ----
        boolean ok = true;
        long fsp = rd("SP");
        if (fsp != sp + 4) { ok = false; log.append(String.format(" [SP UNBALANCED: 0x%08x want 0x%08x]", fsp, sp + 4)); }
        for (int i = 0; i < 12; i++) if (rd32(sp + 4 + 4 * i) != canary[i]) { ok = false; log.append(" [CANARY " + i + " CLOBBERED]"); }
        if (rd("D7") != d7 || rd("A6") != a6) { ok = false; log.append(" [d7/a6 TOUCHED]"); }
        if (!getprojHit) { ok = false; log.append(" [FUN_40138882 never called]"); }
        if (!curtrackHit) { ok = false; log.append(" [FUN_4001d24e never called]"); }
        if (curtrackHit && !curtrackArgOk) ok = false;
        // expected drawn set
        boolean inRange = (track >= 0 && track <= 7);
        List<Long> wantX = new ArrayList<>();
        if (inRange) { int S = map[(int) track];
          for (int v = 0; v < 8; v++) if (map[v] == S && v != track && pos[v] != 0) wantX.add(xFor(pos[v])); }
        if (lines.size() != wantX.size()) { ok = false; log.append(" [LINES " + lines.size() + " want " + wantX.size() + "]"); }
        for (int i = 0; i < Math.min(lines.size(), wantX.size()); i++) {
          Line L = lines.get(i);
          if (L.x != wantX.get(i)) { ok = false; log.append(String.format(" [line%d x=%d want %d]", i, L.x, wantX.get(i))); }
          if (L.canvas != CANVAS) { ok = false; log.append(String.format(" [line%d canvas 0x%08x]", i, L.canvas)); }
          if (L.y1 != YORIG + 1 || L.y2 != YORIG + HEIGHT - 2) { ok = false; log.append(String.format(" [line%d y %d..%d want %d..%d]", i, L.y1, L.y2, YORIG + 1, YORIG + HEIGHT - 2)); }
          if (L.mode != 0xffffffffL) { ok = false; log.append(String.format(" [line%d mode 0x%08x]", i, L.mode)); }
        }
        // the position accessor must have been asked only for pool siblings (never the selected voice)
        for (long v : posCalls) if (!inRange || map[(int) v] != map[(int) track] || v == track) { ok = false; log.append(" [POSFRAC asked for voice " + v + "]"); }
        // dirty bytes: this widget's cleared; marker's set iff drew (for the marker case they are the same byte)
        int drew = lines.isEmpty() ? 0 : 1;
        if (grid) {
          int gd = rd8(GRID_W + 0x2d), md = rd8(MARKER_W + 0x31);
          if (gd != 0) { ok = false; log.append(" [grid.dirty=" + gd + " want 0]"); }
          int wantMd = drew == 1 ? 1 : 0x55;
          if (md != wantMd) { ok = false; log.append(" [marker.dirty=" + md + " want " + wantMd + "]"); }
        } else {
          int md = rd8(MARKER_W + 0x31);
          if (md != drew) { ok = false; log.append(" [marker.dirty=" + md + " want " + drew + "]"); }
          if (rd8(GRID_W + 0x2d) != 0x77) { ok = false; log.append(" [grid.dirty touched in the marker case]"); }
        }
        StringBuilder xs = new StringBuilder();
        for (Line L : lines) xs.append(" ").append(L.x);
        println(String.format("  %-52s track %-3d -> %d line(s) at x=[%s ] %s%s", label, track, lines.size(), xs, ok ? "OK" : "**FAIL**", log));
        emu.dispose(); return ok;
      }
      if (!emu.step(monitor)) { println("  " + label + " -> **FAULT** at " + emu.getExecutionAddress() + " : " + emu.getLastError()); emu.dispose(); return false; }
    }
    println("  " + label + " -> **step cap / never returned**"); emu.dispose(); return false;
  }

  public void run() throws Exception {
    String[] args = getScriptArgs();
    if (args.length < 1) { printerr("usage: EmuCursorCore.java <MAIN OS of a build | raw pad file>"); return; }
    padBytes = loadPad(args[0], PAD, PAD_LEN);

    println("=== pool_cursors: multi-cursor core @0x40015558 -- step-through with stubbed callees ===");
    int[] pool3 = {0, 0, 0, 3, 3, 3, 6, 7};      // 0-2 pool on 0 ; 3-5 pool on 3 ; 6,7 own source
    int[] pool8 = {0, 0, 0, 0, 0, 0, 0, 0};      // one 8-voice pool
    int[] ident = {0, 1, 2, 3, 4, 5, 6, 7};      // no POLY anywhere
    int[] posA  = {0x80, 0x40, 0x00, 0x100, 0x20, 0x00, 0x00, 0x00};
    int[] posB  = {0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70, 0x80};
    int[] posAll= {0x100, 0x100, 0x100, 0x100, 0x100, 0x100, 0x100, 0x100};
    int[] silent= {0, 0, 0, 0, 0, 0, 0, 0};
    boolean all = true;
    all &= one("MARKER  source 0, siblings 1 playing / 2 silent",  false, 0,  pool3, posA);
    all &= one("GRID    follower 4 of 3, sibling 3 playing",        true,  4,  pool3, posA);
    all &= one("MARKER  track 6, region of one (no extras)",        false, 6,  pool3, posA);
    all &= one("GRID    track 16 (master) -> skip",                  true,  16, pool3, posAll);
    all &= one("MARKER  track -1 -> skip",                           false, -1, pool3, posAll);
    all &= one("MARKER  source 0, both siblings playing",            false, 0,  pool3, posB);
    all &= one("GRID    8-voice pool, selected 3, all playing (7)",  true,  3,  pool8, posAll);
    all &= one("MARKER  8-voice pool, selected 0, all silent (0)",   false, 0,  pool8, silent);
    all &= one("GRID    no POLY anywhere, track 5 (identity map)",   true,  5,  ident, posAll);
    all &= one("GRID    source 3, only itself playing (skip self)",  true,  3,  pool3, new int[]{0,0,0,0x80,0,0,0,0});
    println(all ? "=== ALL CASES PASS: SP balanced, frame + d7/a6 intact, callee arg discipline OK, exact cursor set + x, dirty bytes right ==="
                : "=== FAILURES ABOVE: DO NOT FLASH ===");
  }
}
