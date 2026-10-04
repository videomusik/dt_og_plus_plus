// chain_record: run the recorder engine of a DT OG++ build with the Chain Recording hooks through whole
// chains, and the view pads through their cases, in Ghidra's p-code emulator.
//
// The engine code runs as built: ARM 0x400768a0, REC 0x400768ce, the STOP key 0x40076918, ABORT
// 0x4007693c and the per-block routine 0x40076650 (source copy, metering, threshold, write, stop), with
// the hooks into len_pad, arm_pad and stop_pad. Stubs stand in for the stock routines outside it:
//   0x40076616  slot length          -> returns SLOT samples (a stock call reads the tempo)
//   0x40076540  end of recording     -> counts the call and sets state 3, as stock does
//   0x400e8ae8  memcpy               -> copies (source 9 copies the block from the 4th argument)
//   0x400e8a38  threshold level      -> returns THR
//   0x400c0816  encoder accumulator  -> returns the turn the case sets, in 1/256 steps
//   0x400c33cc  key flag test        -> returns a marker value
//   0x400c9a3a  View::invalidate     -> counts the redraw
//
// ASSERTED:
//   engine  - chain off, RLEN MAX and N = 0 behave as stock: the write position starts at 0 and is not
//             cut back at the stop, which goes to the stock end of recording
//           - in a chain each arm keeps the write position, each slot starts at k * SLOT and ends at
//             exactly (k + 1) * SLOT, the recorder goes back to idle (manual arming) or re-arms itself
//             (auto re-arm, N stored negative) with k + 1 stored, and slot N goes to the stock end of
//             recording at exactly N * SLOT with k cleared
//           - the buffer holds each slot's samples from its own start, the last block's overshoot
//             overwritten by the next slot
//           - REC records the next slot at once; the STOP key, ABORT, the 33 s cap and a moved write
//             position end the chain, and the next arm starts a new one
//           - ARM returns 1 and every call returns with SP balanced
//   view    - encoder D, only while idle, calls the accumulator as encoder G does (the page's state
//             block, the event, G's speed table) and steps the setting once per whole step it returns,
//             through AUTO 64..4, off, 4..64, held at the ends; it clears k, sets the magic and
//             redraws once. Less than a whole step does nothing. Encoder H and other encoders take the
//             stock path
//           - the idle prompt and the ARMED line push the stock string, or "YES: ARM %d/%d" /
//             "YES: AUTO %d/%d" / "ARMED %d/%d" with (k + 1, N), and each draw's cleanup leaves SP
//             where stock leaves it
//           - the MEM pad returns SLOT, or N * SLOT in a chain
//           - with the recorder idle, a fresh FUNC+NO press while a chain is in progress drops the
//             chain (write position 0, k 0), redraws and leaves to the stock code after ARM; any other
//             NO event replays the stock test and returns after it, with SP and the event as stock
//
// Argument: the decompressed MAIN OS of a Chain Recording build (./scripts/extract.sh
// 1.54:out/1.54/<build>.syx, then work/dt_1.54-<build>/section_3_MAIN_OS.bin).
//   ./scripts/ghidra_emu.sh 1.54 EmuChainRecord work/dt_1.54-<build>/section_3_MAIN_OS.bin
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
  static final long ARMED_TXT = 0x401ddbffL, ARMED_FMT = 0x40252c0fL, AUTO_FMT = 0x40252c1bL;
  static final long ENC_HOOK = 0x400a7f40L, ENC_H = 0x400a7f46L, ENC_DONE = 0x400a80c8L;
  static final long ENC_ACC = 0x400c0816L, ENC_SPEED = 0x4208db80L;
  static final long MEM_HOOK = 0x400a8e7cL, MEM_BACK = 0x400a8e82L, FMT_HOOK = 0x400a8f48L, FMT_BACK = 0x400a8f64L;
  static final long ARMED_HOOK = 0x400a9026L, ARMED_BACK = 0x400a9048L;
  static final long NO_HOOK = 0x400a9878L, NO_BACK = 0x400a9880L, ARM_TAIL = 0x400a984eL, KEY_FLAG4 = 0x400c33ccL;
  static final long RET = 0x40001000L;               // return address the harness stops at
  static final long SRCBUF = 0x439d3000L, EVENT = 0x439d3100L, VIEW = 0x439d3200L;
  static final long SP0 = 0x40258600L;
  static final long SLOT = 200, THR = 0x00800000L;   // 200 samples: slots do not end on a block edge
  static final int  HIT = 5;                         // the first loud sample of a hit block

  static final int    MAIN_SIZE = 2479680;
  static final long   MAIN_BASE = 0x40000400L;
  static final String STOCK_MAIN_SHA256 = "5c58bf9e3949ef09977c5fc007a61e8d026931f67f1621238379dfb8ee4d31a2";
  static final String FORMATS = "YES: ARM %d/%d\0ARMED %d/%d\0YES: AUTO %d/%d\0";
  // the build's code: engine with its hooks, vfunc_17, vfunc_4, the NO hook in vfunc_2, the pads and strings
  static final long[][] REGIONS = {
    {0x40076540L, 0x40076b22L}, {0x400a7e38L, 0x400a80d4L}, {0x400a8c94L, 0x400a9784L},
    {0x400a9878L, 0x400a9880L},
    {0x400bf1ccL, 0x400bf1e8L}, {0x400c1062L, 0x400c1080L}, {0x40124a6cL, 0x40124b32L},
    {0x40128244L, 0x401282e0L}, {0x40177104L, 0x40177194L},
    {0x40252c00L, 0x40252c30L}};

  byte[] image;
  boolean auto = false;                              // the mode of the chain under test
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

  /** The chain word with N slots (negative: auto re-arm) and k slots done. */
  static long word(long n, long k) { return MAGIC | ((n & 0xff) << 8) | k; }
  void setChain(long n, long k) throws Exception { wr(CHAIN, word(n, k), 4); }
  /** A chain of n slots in the mode under test. */
  void setMode(long n, long k) throws Exception { setChain(auto ? -n : n, k); }
  long k() throws Exception { return rd32(CHAIN) & 0xff; }
  long st() throws Exception { return rd32(STATE); }
  long pos() throws Exception { return rd32(POS); }
  String mode() { return auto ? " (auto re-arm)" : ""; }

  void expect(StringBuilder bad, String what, long got, long want) {
    if (got != want) bad.append(String.format(" [%s %d, want %d]", what, got, want));
  }

  void report(String label, StringBuilder bad) {
    println(String.format("  %-66s %s%s", label, bad.length() == 0 ? "OK" : "**FAIL**", bad));
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
    setMode(n, 0); wr(RLEN, 16, 4); wr(STATE, 0, 4); wr(POS, 12345, 4); wr(LEN, 777, 4);
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
    report(String.format("chain of %d%s: slots at k*%d, %s between, stock stop at %d", n, mode(), SLOT,
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

  /** One chain slot done (k = 1, POS = LEN = SLOT, idle or re-armed), with N = 4. */
  void oneSlotDone(StringBuilder bad) throws Exception {
    fresh();
    setMode(4, 0); wr(RLEN, 16, 4); wr(STATE, 0, 4); wr(POS, 0, 4);
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
    report("REC in a chain records the next slot at once" + mode(), bad);

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
    report("the STOP key ends the chain; the next arm starts a new one" + mode(), bad);

    bad = new StringBuilder();
    oneSlotDone(bad);
    armIfIdle(bad);
    call(ABORT, bad);
    expect(bad, "ABORT: state", st(), 0);
    expect(bad, "ABORT: position", pos(), 0);
    call(ARM, bad);
    expect(bad, "next arm: position", pos(), 0);
    expect(bad, "next arm: k", k(), 0);
    report("ABORT while armed ends the chain; the next arm starts a new one" + mode(), bad);

    bad = new StringBuilder();
    fresh();
    setMode(4, 1); wr(RLEN, 16, 4); wr(STATE, 0, 4); wr(POS, CAP - 40, 4); wr(LEN, CAP - 40, 4);
    call(ARM, bad);
    expect(bad, "armed position", pos(), CAP - 40);
    block(2, 0, 0, bad);
    expect(bad, "LEN past the cap", rd32(LEN), CAP - 40 + SLOT);
    int bn = 1;
    while (st() == 2 && bn < 10 && bad.length() == 0) block(2, bn++, 0, bad);
    expect(bad, "cap: position", pos(), CAP);
    expect(bad, "cap: stock stops", stops, 1);
    expect(bad, "cap: k", k(), 0);
    report("the 33 s cap cuts a slot short: stock stop, chain ended" + mode(), bad);

    bad = new StringBuilder();
    fresh();
    setMode(8, 2); wr(RLEN, 16, 4); wr(STATE, 0, 4); wr(POS, 500, 4); wr(LEN, 600, 4);
    call(ARM, bad);
    expect(bad, "position", pos(), 0);
    expect(bad, "k", k(), 0);
    report("k > 0 but the position moved: the arm starts a new chain" + mode(), bad);

    bad = new StringBuilder();
    fresh();
    setMode(8, 3); wr(RLEN, 16, 4); wr(STATE, 0, 4); wr(POS, 0, 4); wr(LEN, 0, 4);
    call(ARM, bad);
    expect(bad, "k", k(), 0);
    block(1, 0, HIT, bad);
    expect(bad, "LEN", rd32(LEN), SLOT);
    report("k > 0 after a restart (position and LEN 0): a new chain" + mode(), bad);
  }

  // ---- view pads

  /** vfunc_17 from the hook after the H test; the accumulator stub returns acc. */
  void enc(String label, long state, long word, long evId, long acc, boolean hTest,
           long wantWord, int wantRedraws, long wantExit, int wantAccCalls) throws Exception {
    StringBuilder bad = new StringBuilder();
    fresh();
    wr(STATE, state, 4); wr(CHAIN, word, 4); wr(RLEN, 16, 4);
    wr(EVENT + 12, evId, 4); wr(EVENT + 16, 0x5555L, 4);
    long sp = SP0 - 16;
    reg("SP", sp); reg("PC", ENC_HOOK);
    reg("D0", hTest ? 1 : 0); reg("D2", EVENT); reg("A2", VIEW); reg("A3", 0x400c03e6L);
    long exit = -1;
    int accCalls = 0;
    for (int i = 0; i < 600; i++) {
      long pc = emu.getExecutionAddress().getOffset();
      if (pc == ENC_DONE || pc == ENC_H) { exit = pc; break; }
      long s = rd("SP");
      if (pc == REDRAW || pc == ENC_ACC) {
        if (pc == REDRAW) { redraws++; redrawArg = rd32(s + 4); }
        if (pc == ENC_ACC) {
          accCalls++;
          if (rd32(s + 4) != VIEW + 148 || rd32(s + 8) != EVENT || rd32(s + 12) != ENC_SPEED)
            bad.append(String.format(" [accumulator called with 0x%08x, 0x%08x, 0x%08x]", rd32(s + 4), rd32(s + 8), rd32(s + 12)));
          reg("D0", acc);
        }
        reg("PC", rd32(s)); reg("SP", s + 4);
        continue;
      }
      boolean inside = false;
      for (long[] r : REGIONS) if (pc >= r[0] && pc < r[1]) inside = true;
      if (!inside) { bad.append(String.format(" [escaped to 0x%08x]", pc)); break; }
      if (!emu.step(monitor)) { bad.append(" [FAULT " + emu.getLastError() + "]"); break; }
    }
    if (exit != wantExit) bad.append(String.format(" [left to 0x%08x, want 0x%08x]", exit, wantExit));
    if (rd("SP") != sp) bad.append(" [SP moved]");
    if (rd("D2") != EVENT || rd("A2") != VIEW) bad.append(" [d2/a2 changed]");
    if (rd32(CHAIN) != wantWord) bad.append(String.format(" [chain word 0x%08x, want 0x%08x]", rd32(CHAIN), wantWord));
    if (redraws != wantRedraws) bad.append(String.format(" [%d redraws, want %d]", redraws, wantRedraws));
    if (redraws > 0 && redrawArg != VIEW) bad.append(" [redraw of the wrong view]");
    if (accCalls != wantAccCalls) bad.append(String.format(" [%d accumulator calls, want %d]", accCalls, wantAccCalls));
    report("encoder: " + label, bad);
  }

  /** Encoder D turned with the recorder idle: one accumulator call. */
  void encD(String label, long word, long acc, long wantWord, int wantRedraws) throws Exception {
    enc(label, 0, word, 4, acc, false, wantWord, wantRedraws, ENC_DONE, 1);
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
      if ((wantFmt == ARM_FMT || wantFmt == ARMED_FMT || wantFmt == AUTO_FMT) && (drawArgs[6] != wantK1 || drawArgs[7] != wantN))
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

  /** vfunc_2, recorder idle, the NO key, from the hook; flags = the event's flags word. */
  void no(String label, long word, long rlen, long pos, long len, long flags, boolean wantDrop) throws Exception {
    StringBuilder bad = new StringBuilder();
    fresh();
    wr(CHAIN, word, 4); wr(RLEN, rlen, 4); wr(POS, pos, 4); wr(LEN, len, 4); wr(STATE, 0, 4);
    wr(EVENT + 12, 13, 4); wr(EVENT + 16, flags, 4);
    long sp = SP0 - 100;
    reg("SP", sp); reg("PC", NO_HOOK);
    reg("D2", EVENT); reg("A2", VIEW); reg("A3", 0x400c33a4L);
    long exit = -1;
    int keyCalls = 0;
    for (int i = 0; i < 400; i++) {
      long pc = emu.getExecutionAddress().getOffset();
      if (pc == NO_BACK || pc == ARM_TAIL) { exit = pc; break; }
      long s = rd("SP");
      if (pc == REDRAW || pc == KEY_FLAG4) {
        if (pc == REDRAW) { redraws++; redrawArg = rd32(s + 4); }
        if (pc == KEY_FLAG4) {
          keyCalls++;
          if (rd32(s + 4) != EVENT) bad.append(" [the key test got the wrong event]");
          reg("D0", 0x5a);
        }
        reg("PC", rd32(s)); reg("SP", s + 4);
        continue;
      }
      boolean inside = false;
      for (long[] r : REGIONS) if (pc >= r[0] && pc < r[1]) inside = true;
      if (!inside) { bad.append(String.format(" [escaped to 0x%08x]", pc)); break; }
      if (!emu.step(monitor)) { bad.append(" [FAULT " + emu.getLastError() + "]"); break; }
    }
    if (rd("D2") != EVENT || rd("A2") != VIEW) bad.append(" [d2/a2 changed]");
    if (wantDrop) {
      if (exit != ARM_TAIL) bad.append(String.format(" [left to 0x%08x, want 0x%08x]", exit, ARM_TAIL));
      if (rd("SP") != sp) bad.append(" [SP moved]");
      expect(bad, "position", pos(), 0);
      expect(bad, "k", k(), 0);
      if ((rd32(CHAIN) & 0xffffff00L) != (word & 0xffffff00L)) bad.append(" [N or the magic changed]");
      expect(bad, "redraws", redraws, 1);
      if (redraws > 0 && redrawArg != VIEW) bad.append(" [redraw of the wrong view]");
      expect(bad, "key tests", keyCalls, 0);
    } else {
      if (exit != NO_BACK) bad.append(String.format(" [left to 0x%08x, want 0x%08x]", exit, NO_BACK));
      if (rd("SP") != sp - 4 || rd32(sp - 4) != EVENT) bad.append(" [the stock stack differs]");
      expect(bad, "key test result", rd("D0"), 0x5a);
      expect(bad, "key tests", keyCalls, 1);
      expect(bad, "position", pos(), pos);
      if (rd32(CHAIN) != word) bad.append(" [the chain word changed]");
      expect(bad, "redraws", redraws, 0);
    }
    report("FUNC+NO: " + label, bad);
  }

  public void run() throws Exception {
    String[] args = getScriptArgs();
    if (args.length < 1) { printerr("usage: EmuChainRecord.java <MAIN OS of a Chain Recording build>"); return; }
    image = Files.readAllBytes(Paths.get(args[0]));
    if (image.length != MAIN_SIZE) { printerr(args[0] + ": not a MAIN OS image (" + image.length + " B)"); return; }
    StringBuilder h = new StringBuilder();
    for (byte b : MessageDigest.getInstance("SHA-256").digest(image)) h.append(String.format("%02x", b & 0xff));
    if (h.toString().equals(STOCK_MAIN_SHA256)) { printerr(args[0] + " is the STOCK MAIN OS"); return; }
    println("=== chain_record on MAIN OS " + h.substring(0, 16) + "...: engine with hooks, view pads ===");
    String fs = new String(image, (int) (ARM_FMT - MAIN_BASE), FORMATS.length(), "US-ASCII");
    if (!fs.equals(FORMATS)) {
      println("  the line formats at 0x40252c00 are missing: **FAIL**"); fails++;
    }

    stockLike("chain word not valid: stock", 0x12345678L, 16);
    stockLike("RLEN MAX with a chain of 4: stock", word(4, 0), 0);
    stockLike("RLEN MAX with an auto chain of 4: stock", word(-4, 0), 0);
    stockLike("N = 0 (chain off): stock", MAGIC, 16);
    for (boolean a : new boolean[] {false, true}) {
      auto = a;
      chainOf(4);
      chainOf(8);
      engineEdges();
    }
    auto = false;

    long W = MAGIC;
    enc("H turned: stock path, nothing changed", 0, word(8, 3), 8, 256, true, word(8, 3), 0, ENC_H, 0);
    encD("D up from an invalid word: 4", 0x12345678L, 256, word(4, 0), 1);
    encD("D up from off: 4", W, 256, word(4, 0), 1);
    encD("D up from 4 (k = 2): 8, k = 0", word(4, 2), 256, word(8, 0), 1);
    encD("D up from 32: 64", word(32, 0), 256, word(64, 0), 1);
    encD("D up from 64: stays 64", word(64, 0), 256, word(64, 0), 1);
    encD("D down from 64: 32", word(64, 0), -256, word(32, 0), 1);
    encD("D down from 4: off", word(4, 0), -256, W, 1);
    encD("D down from off: AUTO 4", W, -256, word(-4, 0), 1);
    encD("D down from AUTO 4 (k = 1): AUTO 8, k = 0", word(-4, 1), -256, word(-8, 0), 1);
    encD("D down from AUTO 32: AUTO 64", word(-32, 0), -256, word(-64, 0), 1);
    encD("D down from AUTO 64: stays AUTO 64", word(-64, 0), -256, word(-64, 0), 1);
    encD("D up from AUTO 64: AUTO 32", word(-64, 0), 256, word(-32, 0), 1);
    encD("D up from AUTO 8: AUTO 4", word(-8, 0), 256, word(-4, 0), 1);
    encD("D up from AUTO 4: off", word(-4, 0), 256, W, 1);
    encD("D down from an invalid word: AUTO 4", 0x12345678L, -256, word(-4, 0), 1);
    encD("D with two whole steps up from 16: one step, 32", word(16, 0), 512, word(32, 0), 1);
    encD("D with less than a whole step up: nothing", word(16, 1), 255, word(16, 1), 0);
    encD("D with less than a whole step down: nothing", word(16, 1), -255, word(16, 1), 0);
    encD("D with no turn: nothing", word(16, 1), 0, word(16, 1), 0);
    enc("D while armed: nothing", 1, word(16, 1), 4, 256, false, word(16, 1), 0, ENC_DONE, 0);
    enc("encoder A (id 1): nothing", 0, word(16, 0), 1, 256, false, word(16, 0), 0, ENC_DONE, 0);

    fmt("chain word not valid: stock string", 0x12345678L, 16, 0, 0, ARM_TXT, 0, 0);
    fmt("N = 8, no slot yet: 1/8", word(8, 0), 16, 0, 0, ARM_FMT, 1, 8);
    fmt("N = 8, 3 slots done, position at their end: 4/8", word(8, 3), 16, 600, 600, ARM_FMT, 4, 8);
    fmt("N = 8, k = 3 but the position moved: 1/8", word(8, 3), 16, 650, 600, ARM_FMT, 1, 8);
    fmt("N = 8, k = 3 after a restart (position = LEN = 0): 1/8", word(8, 3), 16, 0, 0, ARM_FMT, 1, 8);
    fmt("AUTO 8, no slot yet: AUTO 1/8", word(-8, 0), 16, 0, 0, AUTO_FMT, 1, 8);
    fmt("AUTO 64, 2 slots done: AUTO 3/64", word(-64, 2), 16, 400, 400, AUTO_FMT, 3, 64);
    fmt("RLEN MAX: stock string", word(8, 0), 0, 0, 0, ARM_TXT, 0, 0);
    fmt("RLEN MAX, AUTO 8: stock string", word(-8, 0), 0, 0, 0, ARM_TXT, 0, 0);
    fmt("N = 0: stock string", W, 16, 0, 0, ARM_TXT, 0, 0);

    armed("chain word not valid: stock string", 0x12345678L, 16, 0, 0, ARMED_TXT, 0, 0);
    armed("N = 16, first slot: 1/16", word(16, 0), 16, 0, 0, ARMED_FMT, 1, 16);
    armed("N = 16, 5 slots done: 6/16", word(16, 5), 16, 1000, 1000, ARMED_FMT, 6, 16);
    armed("AUTO 16, 5 slots done: 6/16", word(-16, 5), 16, 1000, 1000, ARMED_FMT, 6, 16);
    armed("RLEN MAX: stock string", word(16, 5), 0, 1000, 1000, ARMED_TXT, 0, 0);

    mem("chain word not valid: SLOT", 0x12345678L, 16, SLOT);
    mem("N = 16: 16 * SLOT", word(16, 5), 16, 16 * SLOT);
    mem("AUTO 16: 16 * SLOT", word(-16, 5), 16, 16 * SLOT);
    mem("RLEN MAX: SLOT", word(16, 0), 0, SLOT);
    mem("N = 0: SLOT", W, 16, SLOT);

    no("fresh press, chain in progress (k = 2): dropped", word(8, 2), 16, 400, 400, 0x3, true);
    no("fresh press with bit 4 set, chain in progress: dropped", word(4, 1), 16, 200, 200, 0x13, true);
    no("fresh press, no slot recorded yet: stock", word(8, 0), 16, 0, 0, 0x3, false);
    no("fresh press, k = 2 but the position moved: stock", word(8, 2), 16, 450, 400, 0x3, false);
    no("fresh press, chain off: stock", W, 16, 400, 400, 0x3, false);
    no("fresh press, RLEN MAX: stock", word(8, 2), 0, 400, 400, 0x3, false);
    no("NO without FUNC, chain in progress: stock", word(8, 2), 16, 400, 400, 0x11, false);
    no("FUNC+NO repeat, chain in progress: stock", word(8, 2), 16, 400, 400, 0xb, false);
    no("FUNC+NO release, chain in progress: stock", word(8, 2), 16, 400, 400, 0x12, false);

    if (emu != null) emu.dispose();
    println(fails == 0
        ? "=== ALL CASES PASS: stock without a chain; slots at k*SLOT, exact ends, idle or re-armed between, stock stop"
          + " after N; encoder D, prompts, MEM and FUNC+NO right, SP balanced ==="
        : "=== " + fails + " FAILURE(S) ABOVE: DO NOT FLASH ===");
  }
}
