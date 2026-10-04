// chain_record: run the recorder engine of a DT OG++ build with the Chain Recording hooks through whole
// chains, and the three view pads through their cases, in Ghidra's p-code emulator.
//
// The engine code runs as built: ARM 0x400768a0, REC 0x400768ce, the STOP key 0x40076918, ABORT
// 0x4007693c and the per-block routine 0x40076650 (source copy, metering, threshold, write, stop), with
// the hooks into len_pad, arm_pad and stop_pad. Stubs stand in for four stock routines outside it:
//   0x40076616  slot length          -> returns SLOT samples (a stock call reads the tempo)
//   0x40076540  end of recording     -> counts the call and sets state 3, as stock does
//   0x400e8ae8  memcpy               -> copies (source 9 copies the block from the 4th argument)
//   0x400e8a38  threshold level      -> returns THR
//
// ASSERTED:
//   engine  - chain off, RLEN MAX and N = 0 behave as stock: the write position starts at 0 and is not
//             cut back at the stop, which goes to the stock end of recording
//           - in a chain each arm keeps the write position, each slot starts at k * SLOT and ends at
//             exactly (k + 1) * SLOT, the recorder goes back to idle with k + 1 stored, and slot N goes
//             to the stock end of recording at exactly N * SLOT with k cleared
//           - the buffer holds each slot's samples from its own start, the last block's overshoot
//             overwritten by the next slot
//           - REC records the next slot at once; the STOP key, ABORT, the 33 s cap and a moved write
//             position end the chain, and the next arm starts a new one
//           - ARM returns 1 and every call returns with SP balanced
//   view    - encoder D steps N off/4/8/16/32/64 only while idle, clears k, sets the magic and redraws
//             once; encoder H and other encoders take the stock path
//           - the idle prompt and the ARMED line push the stock string, or "YES: ARM %d/%d" /
//             "ARMED %d/%d" with (k + 1, N), and each draw's cleanup leaves SP where stock leaves it
//           - the MEM pad returns SLOT, or N * SLOT in a chain
//
// Arguments: the decompressed MAIN OS of a Chain Recording build (./scripts/extract.sh
// 1.54:out/1.54/<build>.syx, then work/dt_1.54-<build>/section_3_MAIN_OS.bin), and "auto" for a build
// with auto re-arm, where the recorder re-arms itself after each slot but the last instead of going idle.
//   ./scripts/ghidra_emu.sh 1.54 EmuChainRecord work/dt_1.54-<build>/section_3_MAIN_OS.bin [auto]
// Runs in Ghidra's emulator only; nothing touches a device. @category dt_og_plus_plus
import ghidra.app.script.GhidraScript;
import ghidra.app.emulator.EmulatorHelper;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;

public class EmuChainRecord extends GhidraScript {
  static final long RLEN = 0x4199f0fcL, LEN = 0x4199f100L, POS = 0x4199f104L, STATE = 0x4199f114L;
  static final long SRC = 0x4197cf98L, CHAIN = 0x439d1038L, MAGIC = 0xc4a10000L;
  static final long BUF16 = 0x4237ef90L, CAP = 0x182b80L;
  static final long SLOTLEN = 0x40076616L, STOP = 0x40076540L, MEMCPY = 0x400e8ae8L, THRGET = 0x400e8a38L;
  static final long ARM = 0x400768a0L, REC = 0x400768ceL, STOPKEY = 0x40076918L, ABORT = 0x4007693cL;
  static final long BLOCKFN = 0x40076650L;
  static final long REDRAW = 0x400c9a3aL, DRAWF = 0x400c27e8L, ARM_TXT = 0x401d0696L, ARM_FMT = 0x40252c00L;
  static final long ARMED_TXT = 0x401ddbffL, ARMED_FMT = 0x40252c0fL;
  static final long ENC_HOOK = 0x400a7f40L, ENC_H = 0x400a7f46L, ENC_DONE = 0x400a80c8L;
  static final long MEM_HOOK = 0x400a8e7cL, MEM_BACK = 0x400a8e82L, FMT_HOOK = 0x400a8f48L, FMT_BACK = 0x400a8f64L;
  static final long ARMED_HOOK = 0x400a9026L, ARMED_BACK = 0x400a9048L;
  static final long RET = 0x40001000L;               // return address the harness stops at
  static final long SRCBUF = 0x439d3000L, EVENT = 0x439d3100L, VIEW = 0x439d3200L;
  static final long SP0 = 0x40258600L;
  static final long SLOT = 200, THR = 0x00800000L;   // 200 samples: slots do not end on a block edge
  static final int  HIT = 5;                         // the first loud sample of a hit block

  static final int    MAIN_SIZE = 2479680;
  static final long   MAIN_BASE = 0x40000400L;
  static final String STOCK_MAIN_SHA256 = "5c58bf9e3949ef09977c5fc007a61e8d026931f67f1621238379dfb8ee4d31a2";
  // the build's code: engine with its hooks, vfunc_17, vfunc_4, and the pads and string
  static final long[][] REGIONS = {
    {0x40076540L, 0x40076b22L}, {0x400a7e38L, 0x400a80d4L}, {0x400a8c94L, 0x400a9784L},
    {0x400bf1ccL, 0x400bf1e8L}, {0x400c1062L, 0x400c1080L}, {0x40124a6cL, 0x40124b32L},
    {0x40177104L, 0x40177194L},
    {0x40252c00L, 0x40252c20L}};

  byte[] image;
  boolean auto = false;
  long idle() { return auto ? 1 : 0; }             // the state after a slot that is not the last
  EmulatorHelper emu;
  int fails = 0, stops = 0, redraws = 0;
  long redrawArg = 0;
  long[] drawArgs = null;

  void wr(long a, long v, int n) throws Exception {
    byte[] b = new byte[n]; for (int i = 0; i < n; i++) b[n - 1 - i] = (byte) ((v >> (8 * i)) & 0xff);
    emu.writeMemory(toAddr(a), b);
  }
  long rdn(long a, int n) throws Exception {
    byte[] b = emu.readMemory(toAddr(a), n); long v = 0; for (int i = 0; i < n; i++) v = (v << 8) | (b[i] & 0xff); return v;
  }
  long rd32(long a) throws Exception { return rdn(a, 4); }
  long rd(String r) throws Exception { return emu.readRegister(r).longValue() & 0xffffffffL; }
  void reg(String r, long v) throws Exception { emu.writeRegister(r, v); }

  void fresh() throws Exception {
    if (emu != null) emu.dispose();
    emu = new EmulatorHelper(currentProgram);
    for (long[] r : REGIONS) {
      int lo = (int) (r[0] - MAIN_BASE), hi = (int) (r[1] - MAIN_BASE);
      byte[] b = new byte[hi - lo]; System.arraycopy(image, lo, b, 0, hi - lo);
      emu.writeMemory(toAddr(r[0]), b);
    }
    emu.writeMemory(toAddr(SP0 - 0x800), new byte[0x1000]);
    emu.writeMemory(toAddr(0x4199f0f0L), new byte[0x40]);
    emu.writeMemory(toAddr(0x439d1000L), new byte[0x80]);
    wr(SRC, 9, 4);                                   // source 9: the block is copied from the 4th argument
    stops = 0; redraws = 0;
  }

  /** Step from pc until it reaches stopAt, handling the stubs. Returns false on an escape or fault. */
  boolean runTo(long stopAt, StringBuilder bad) throws Exception {
    for (int steps = 0; steps < 20000; steps++) {
      long pc = emu.getExecutionAddress().getOffset();
      if (pc == stopAt) return true;
      long sp = rd("SP");
      if (pc == SLOTLEN || pc == STOP || pc == MEMCPY || pc == THRGET || pc == REDRAW || pc == DRAWF) {
        if (pc == SLOTLEN) reg("D0", SLOT);
        if (pc == THRGET) reg("D0", THR);
        if (pc == STOP) { stops++; wr(STATE, 3, 4); }
        if (pc == MEMCPY) {
          long dst = rd32(sp + 4), src = rd32(sp + 8), n = rd32(sp + 12);
          emu.writeMemory(toAddr(dst), emu.readMemory(toAddr(src), (int) n));
          reg("D0", dst);
        }
        if (pc == REDRAW) { redraws++; redrawArg = rd32(sp + 4); }
        if (pc == DRAWF) { drawArgs = new long[8]; for (int i = 0; i < 8; i++) drawArgs[i] = rd32(sp + 4 + 4 * i); }
        reg("PC", rd32(sp)); reg("SP", sp + 4);
        continue;
      }
      boolean inside = false;
      for (long[] r : REGIONS) if (pc >= r[0] && pc < r[1]) inside = true;
      if (!inside) { bad.append(String.format(" [escaped to 0x%08x]", pc)); return false; }
      if (!emu.step(monitor)) { bad.append(" [FAULT at 0x" + Long.toHexString(pc) + ": " + emu.getLastError() + "]"); return false; }
    }
    bad.append(" [no end after 20000 steps]");
    return false;
  }

  /** Call fn(args...) as C does; returns D0. */
  long call(long fn, StringBuilder bad, long... args) throws Exception {
    long sp = SP0;
    for (int i = args.length - 1; i >= 0; i--) { sp -= 4; wr(sp, args[i], 4); }
    sp -= 4; wr(sp, RET, 4);
    reg("SP", sp); reg("PC", fn);
    if (runTo(RET, bad) && rd("SP") != sp + 4)
      bad.append(String.format(" [0x%08x: SP 0x%08x after return, want 0x%08x]", fn, rd("SP"), sp + 4));
    return rd("D0");
  }

  void block(int tag, int blockNo, int firstLoud, StringBuilder bad) throws Exception {
    for (int i = 0; i < 32; i++) {
      long s = i < firstLoud ? 0 : ((long) tag << 24) | ((long) (blockNo & 0xff) << 16) | ((long) i << 8);
      wr(SRCBUF + 4 * i, s, 4);
    }
    call(BLOCKFN, bad, 0, 0, 0, SRCBUF);
  }

  void setChain(long n, long k) throws Exception { wr(CHAIN, MAGIC | (n << 8) | k, 4); }
  long k() throws Exception { return rd32(CHAIN) & 0xff; }
  long st() throws Exception { return rd32(STATE); }
  long pos() throws Exception { return rd32(POS); }

  void expect(StringBuilder bad, String what, long got, long want) {
    if (got != want) bad.append(String.format(" [%s %d, want %d]", what, got, want));
  }

  void report(String label, StringBuilder bad) {
    println(String.format("  %-62s %s%s", label, bad.length() == 0 ? "OK" : "**FAIL**", bad));
    if (bad.length() != 0) fails++;
  }

  /** Arm (or REC), one quiet block, a hit block, loud blocks until the recorder leaves state 2. */
  int recordSlot(int tag, boolean rec, StringBuilder bad) throws Exception {
    int b = 0;
    if (!rec) {
      armIfIdle(bad);
      expect(bad, "state after ARM", st(), 1);
      block(tag, b++, 32, bad);
      expect(bad, "state after a quiet block", st(), 1);
      block(tag, b++, HIT, bad);
    } else {
      long r = call(REC, bad);
      expect(bad, "REC result", r, 1);
    }
    expect(bad, "state after the hit", st(), 2);
    while (st() == 2 && b < 200 && bad.length() == 0) block(tag, b++, 0, bad);
    return b;
  }

  /** ARM from idle; with auto re-arm a chain in progress is already armed. */
  void armIfIdle(StringBuilder bad) throws Exception {
    if (auto && st() == 1) return;
    long r = call(ARM, bad);
    expect(bad, "ARM result", r, 1);
  }

  void chainOf(int n) throws Exception {
    StringBuilder bad = new StringBuilder();
    fresh();
    setChain(n, 0); wr(RLEN, 16, 4); wr(STATE, 0, 4); wr(POS, 12345, 4); wr(LEN, 777, 4);
    for (int j = 0; j < n && bad.length() == 0; j++) {
      long start = j * SLOT;
      if (auto && j > 0) expect(bad, "slot " + j + " re-armed", st(), 1);
      else expect(bad, "slot " + j + " ARM result", call(ARM, bad), 1);
      expect(bad, "slot " + j + " write position at arm", pos(), start);
      expect(bad, "slot " + j + " k at arm", k(), j);
      block(j + 1, 0, 32, bad);
      block(j + 1, 1, HIT, bad);
      expect(bad, "slot " + j + " LEN", rd32(LEN), start + SLOT);
      int bn = 2;
      while (st() == 2 && bn < 200 && bad.length() == 0) block(j + 1, bn++, 0, bad);
      expect(bad, "slot " + j + " end position", pos(), start + SLOT);
      if (j < n - 1) {
        expect(bad, "slot " + j + " state after", st(), idle());
        expect(bad, "slot " + j + " k after", k(), j + 1);
        expect(bad, "slot " + j + " stock stops", stops, 0);
      } else {
        expect(bad, "last slot stock stops", stops, 1);
        expect(bad, "last slot k after", k(), 0);
        expect(bad, "last slot state after", st(), 3);
      }
    }
    for (int j = 0; j < n && bad.length() == 0; j++)
      for (long p = j * SLOT; p < (j + 1) * SLOT; p++) {
        long hi = rdn(BUF16 + 2 * p, 2) >> 8;
        long want = p - j * SLOT < HIT ? 0 : j + 1;
        if (hi != want) { bad.append(String.format(" [buffer at %d holds slot tag %d, want %d]", p, hi, want)); break; }
      }
    report(String.format("chain of %d: slots at k*%d, %s between, stock stop at %d", n, SLOT,
                         auto ? "re-armed" : "idle", n * SLOT), bad);
  }

  void stockLike(String label, long chainWord, long rlen) throws Exception {
    StringBuilder bad = new StringBuilder();
    fresh();
    wr(CHAIN, chainWord, 4); wr(RLEN, rlen, 4); wr(STATE, 0, 4); wr(POS, 999, 4);
    long r = call(ARM, bad);
    expect(bad, "ARM result", r, 1);
    expect(bad, "write position at arm", pos(), 0);
    block(1, 0, 32, bad);
    block(1, 1, HIT, bad);
    expect(bad, "LEN", rd32(LEN), SLOT);
    int bn = 2;
    while (st() == 2 && bn < 200 && bad.length() == 0) block(1, bn++, 0, bad);
    long want = 32 - HIT; while (want < SLOT) want += 32;   // stock: whole blocks, not cut back
    expect(bad, "end position (stock)", pos(), want);
    expect(bad, "stock stops", stops, 1);
    expect(bad, "state", st(), 3);
    report(label, bad);
  }

  /** One chain slot done (k = 1, POS = LEN = SLOT, idle), with N = 4. */
  void oneSlotDone(StringBuilder bad) throws Exception {
    fresh();
    setChain(4, 0); wr(RLEN, 16, 4); wr(STATE, 0, 4); wr(POS, 0, 4);
    recordSlot(1, false, bad);
    expect(bad, "setup: k", k(), 1);
    expect(bad, "setup: state", st(), idle());
    expect(bad, "setup: position", pos(), SLOT);
  }

  void engineEdges() throws Exception {
    StringBuilder bad = new StringBuilder();
    oneSlotDone(bad);
    recordSlot(2, true, bad);
    expect(bad, "REC: end position", pos(), 2 * SLOT);
    expect(bad, "REC: k", k(), 2);
    expect(bad, "REC: state", st(), idle());
    expect(bad, "REC: stock stops", stops, 0);
    report("REC in a chain records the next slot at once", bad);

    bad = new StringBuilder();
    oneSlotDone(bad);
    armIfIdle(bad);
    expect(bad, "armed position", pos(), SLOT);
    block(2, 0, HIT, bad); block(2, 1, 0, bad);
    expect(bad, "recording", st(), 2);
    call(STOPKEY, bad);
    expect(bad, "STOP key: stock stops", stops, 1);
    wr(STATE, 4, 4);                                 // trim, as after the save prompt
    call(ARM, bad);
    expect(bad, "next arm: position", pos(), 0);
    expect(bad, "next arm: k", k(), 0);
    report("the STOP key ends the chain; the next arm starts a new one", bad);

    bad = new StringBuilder();
    oneSlotDone(bad);
    armIfIdle(bad);
    call(ABORT, bad);
    expect(bad, "ABORT: state", st(), 0);
    expect(bad, "ABORT: position", pos(), 0);
    call(ARM, bad);
    expect(bad, "next arm: position", pos(), 0);
    expect(bad, "next arm: k", k(), 0);
    report("ABORT while armed ends the chain; the next arm starts a new one", bad);

    bad = new StringBuilder();
    fresh();
    setChain(4, 1); wr(RLEN, 16, 4); wr(STATE, 0, 4); wr(POS, CAP - 40, 4); wr(LEN, CAP - 40, 4);
    call(ARM, bad);
    expect(bad, "armed position", pos(), CAP - 40);
    block(2, 0, 0, bad);
    expect(bad, "LEN past the cap", rd32(LEN), CAP - 40 + SLOT);
    int bn = 1;
    while (st() == 2 && bn < 10 && bad.length() == 0) block(2, bn++, 0, bad);
    expect(bad, "cap: position", pos(), CAP);
    expect(bad, "cap: stock stops", stops, 1);
    expect(bad, "cap: k", k(), 0);
    report("the 33 s cap cuts a slot short: stock stop, chain ended", bad);

    bad = new StringBuilder();
    fresh();
    setChain(8, 2); wr(RLEN, 16, 4); wr(STATE, 0, 4); wr(POS, 500, 4); wr(LEN, 600, 4);
    call(ARM, bad);
    expect(bad, "position", pos(), 0);
    expect(bad, "k", k(), 0);
    report("k > 0 but the position moved: the arm starts a new chain", bad);

    bad = new StringBuilder();
    fresh();
    setChain(8, 3); wr(RLEN, 16, 4); wr(STATE, 0, 4); wr(POS, 0, 4); wr(LEN, 0, 4);
    call(ARM, bad);
    expect(bad, "k", k(), 0);
    block(1, 0, HIT, bad);
    expect(bad, "LEN", rd32(LEN), SLOT);
    report("k > 0 after a restart (position and LEN 0): a new chain", bad);
  }

  // ---- view pads

  void enc(String label, long state, long word, long evId, long delta, boolean hTest,
           long wantWord, int wantRedraws, long wantExit) throws Exception {
    StringBuilder bad = new StringBuilder();
    fresh();
    wr(STATE, state, 4); wr(CHAIN, word, 4); wr(RLEN, 16, 4);
    wr(EVENT + 12, evId, 4); wr(EVENT + 16, delta, 4);
    long sp = SP0 - 16;
    reg("SP", sp); reg("PC", ENC_HOOK);
    reg("D0", hTest ? 1 : 0); reg("D2", EVENT); reg("A2", VIEW); reg("D3", 0x33333333L); reg("A3", 0x400c03e6L);
    long exit = -1;
    for (int i = 0; i < 400; i++) {
      long pc = emu.getExecutionAddress().getOffset();
      if (pc == ENC_DONE || pc == ENC_H) { exit = pc; break; }
      if (pc == REDRAW) { redraws++; redrawArg = rd32(rd("SP") + 4); reg("PC", rd32(rd("SP"))); reg("SP", rd("SP") + 4); continue; }
      if (!emu.step(monitor)) { bad.append(" [FAULT " + emu.getLastError() + "]"); break; }
    }
    if (exit != wantExit) bad.append(String.format(" [left to 0x%08x, want 0x%08x]", exit, wantExit));
    if (rd("SP") != sp) bad.append(" [SP moved]");
    if (rd("D2") != EVENT || rd("A2") != VIEW || rd("D3") != 0x33333333L) bad.append(" [d2/d3/a2 changed]");
    if (rd32(CHAIN) != wantWord) bad.append(String.format(" [chain word 0x%08x, want 0x%08x]", rd32(CHAIN), wantWord));
    if (redraws != wantRedraws) bad.append(String.format(" [%d redraws, want %d]", redraws, wantRedraws));
    if (redraws > 0 && redrawArg != VIEW) bad.append(" [redraw of the wrong view]");
    report("encoder: " + label, bad);
  }

  void fmt(String label, long word, long rlen, long pos, long len, long wantFmt, long wantK1, long wantN) throws Exception {
    line(true, label, word, rlen, pos, len, wantFmt, wantK1, wantN);
  }
  void armed(String label, long word, long rlen, long pos, long len, long wantFmt, long wantK1, long wantN) throws Exception {
    line(false, label, word, rlen, pos, len, wantFmt, wantK1, wantN);
  }
  /** The idle prompt (0x400a8f48..0x400a8f64) or the ARMED line (0x400a9026..0x400a9048). */
  void line(boolean idleLine, String label, long word, long rlen, long pos, long len, long wantFmt, long wantK1, long wantN) throws Exception {
    StringBuilder bad = new StringBuilder();
    fresh();
    wr(CHAIN, word, 4); wr(RLEN, rlen, 4); wr(POS, pos, 4); wr(LEN, len, 4);
    long sp = SP0 - 8;                               // the font set-up's 8 B are still on the stack
    reg("SP", sp); reg("PC", idleLine ? FMT_HOOK : ARMED_HOOK);
    reg("D6", 0x11110000L); reg("D2", 0x22220000L); reg("A4", DRAWF);
    drawArgs = null;
    runTo(idleLine ? FMT_BACK : ARMED_BACK, bad);
    long wantSp = idleLine ? SP0 : SP0 - 4;          // the armed draw keeps 4 B for the next call
    if (rd("SP") != wantSp) bad.append(String.format(" [SP 0x%08x after the cleanup, want 0x%08x]", rd("SP"), wantSp));
    if (drawArgs == null) bad.append(" [no draw]");
    else {
      long[] want = {0x11110000L, 0x22220000L, 0x40, idleLine ? 0x16 : 0x1d, 2, wantFmt};
      for (int i = 0; i < 6; i++)
        if (drawArgs[i] != want[i]) bad.append(String.format(" [draw arg %d = 0x%x, want 0x%x]", i, drawArgs[i], want[i]));
      if ((wantFmt == ARM_FMT || wantFmt == ARMED_FMT) && (drawArgs[6] != wantK1 || drawArgs[7] != wantN))
        bad.append(String.format(" [prompt %d/%d, want %d/%d]", drawArgs[6], drawArgs[7], wantK1, wantN));
    }
    report((idleLine ? "prompt: " : "ARMED line: ") + label, bad);
  }

  void mem(String label, long word, long rlen, long want) throws Exception {
    StringBuilder bad = new StringBuilder();
    fresh();
    wr(CHAIN, word, 4); wr(RLEN, rlen, 4);
    reg("SP", SP0); reg("PC", MEM_HOOK);
    runTo(MEM_BACK, bad);
    expect(bad, "length", rd("D0"), want);
    if (rd("SP") != SP0) bad.append(" [SP moved]");
    report("MEM line: " + label, bad);
  }

  public void run() throws Exception {
    String[] args = getScriptArgs();
    if (args.length < 1) { printerr("usage: EmuChainRecord.java <MAIN OS of a Chain Recording build>"); return; }
    image = Files.readAllBytes(Paths.get(args[0]));
    auto = args.length > 1 && args[1].equals("auto");
    if (image.length != MAIN_SIZE) { printerr(args[0] + ": not a MAIN OS image (" + image.length + " B)"); return; }
    StringBuilder h = new StringBuilder();
    for (byte b : MessageDigest.getInstance("SHA-256").digest(image)) h.append(String.format("%02x", b & 0xff));
    if (h.toString().equals(STOCK_MAIN_SHA256)) { printerr(args[0] + " is the STOCK MAIN OS"); return; }
    println("=== chain_record on MAIN OS " + h.substring(0, 16) + "...: engine with hooks, view pads"
            + (auto ? ", auto re-arm" : "") + " ===");
    String fs = new String(image, (int) (ARM_FMT - MAIN_BASE), 27, "US-ASCII");
    if (!fs.equals("YES: ARM %d/%d\0ARMED %d/%d\0")) {
      println("  the line formats at 0x40252c00 are missing: **FAIL**"); fails++;
    }

    stockLike("chain word not valid: stock", 0x12345678L, 16);
    stockLike("RLEN MAX with a chain of 4: stock", MAGIC | (4 << 8), 0);
    stockLike("N = 0 (chain off): stock", MAGIC, 16);
    chainOf(4);
    chainOf(8);
    engineEdges();

    long W = MAGIC;
    enc("H turned: stock path, nothing changed", 0, W | (8 << 8) | 3, 8, 1, true, W | (8 << 8) | 3, 0, ENC_H);
    enc("D up from an invalid word: 4", 0, 0x12345678L, 4, 1, false, W | (4 << 8), 1, ENC_DONE);
    enc("D up from 4 (k = 2): 8, k = 0", 0, W | (4 << 8) | 2, 4, 1, false, W | (8 << 8), 1, ENC_DONE);
    enc("D up from 64: stays 64", 0, W | (64 << 8), 4, 1, false, W | (64 << 8), 1, ENC_DONE);
    enc("D up from off: 4", 0, W, 4, 1, false, W | (4 << 8), 1, ENC_DONE);
    enc("D down from 64: 32", 0, W | (64 << 8), 4, -1, false, W | (32 << 8), 1, ENC_DONE);
    enc("D down from 4: off", 0, W | (4 << 8), 4, -1, false, W, 1, ENC_DONE);
    enc("D down from off: off", 0, W, 4, -1, false, W, 1, ENC_DONE);
    enc("D three detents down from 16: one step, 8", 0, W | (16 << 8), 4, -3, false, W | (8 << 8), 1, ENC_DONE);
    enc("D with no movement: nothing", 0, W | (16 << 8) | 1, 4, 0, false, W | (16 << 8) | 1, 0, ENC_DONE);
    enc("D while armed: nothing", 1, W | (16 << 8) | 1, 4, 1, false, W | (16 << 8) | 1, 0, ENC_DONE);
    enc("encoder A (id 1): nothing", 0, W | (16 << 8), 1, 1, false, W | (16 << 8), 0, ENC_DONE);

    fmt("chain word not valid: stock string", 0x12345678L, 16, 0, 0, ARM_TXT, 0, 0);
    fmt("N = 8, no slot yet: 1/8", W | (8 << 8), 16, 0, 0, ARM_FMT, 1, 8);
    fmt("N = 8, 3 slots done, position at their end: 4/8", W | (8 << 8) | 3, 16, 600, 600, ARM_FMT, 4, 8);
    fmt("N = 8, k = 3 but the position moved: 1/8", W | (8 << 8) | 3, 16, 650, 600, ARM_FMT, 1, 8);
    fmt("N = 8, k = 3 after a restart (position = LEN = 0): 1/8", W | (8 << 8) | 3, 16, 0, 0, ARM_FMT, 1, 8);
    fmt("RLEN MAX: stock string", W | (8 << 8), 0, 0, 0, ARM_TXT, 0, 0);
    fmt("N = 0: stock string", W, 16, 0, 0, ARM_TXT, 0, 0);

    armed("chain word not valid: stock string", 0x12345678L, 16, 0, 0, ARMED_TXT, 0, 0);
    armed("N = 16, first slot: 1/16", W | (16 << 8), 16, 0, 0, ARMED_FMT, 1, 16);
    armed("N = 16, 5 slots done: 6/16", W | (16 << 8) | 5, 16, 1000, 1000, ARMED_FMT, 6, 16);
    armed("RLEN MAX: stock string", W | (16 << 8) | 5, 0, 1000, 1000, ARMED_TXT, 0, 0);

    mem("chain word not valid: SLOT", 0x12345678L, 16, SLOT);
    mem("N = 16: 16 * SLOT", W | (16 << 8) | 5, 16, 16 * SLOT);
    mem("RLEN MAX: SLOT", W | (16 << 8), 0, SLOT);
    mem("N = 0: SLOT", W, 16, SLOT);

    if (emu != null) emu.dispose();
    println(fails == 0
        ? "=== ALL CASES PASS: stock without a chain; slots at k*SLOT, exact ends, " + (auto ? "re-armed" : "idle")
          + " between, stock stop after N; view pads right, SP balanced ==="
        : "=== " + fails + " FAILURE(S) ABOVE: DO NOT FLASH ===");
  }
}
