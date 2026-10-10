// midi_loopback: step the three CHAN/TRK display pads at 0x40015616 and check that each one is a no-op
// for every stock value and produces the new label only for the new (negative) range.
//
// THREE PADS, all entered by `jsr` from code that replaced a 6-byte call or instruction:
//   addend    @pad+0    <- the CHAN value formatter's first instruction (0x40065896). Must return the
//                          ADDEND: +1.0 (0x100) for stock values, +9.0 (0x900) for the new negatives.
//                          ⚠️ Three other parameters share that formatter body (Slice Length, Bank,
//                          Program); they have min 0, so the pad must return exactly 0x100 for them.
//   shortname @pad+22   <- `jsr 0x4000fe8a` at 0x40030daa (grid-cell short name). id at sp@(8), the
//                          raw value at sp@(100). Returns "TRK" only for id 140 AND value < 0,
//                          otherwise it must TAIL-JUMP to the stock accessor with the frame untouched.
//   popupname @pad+54   <- `jsr 0x4000feac` at 0x40032d36 (encoder popup long name). The raw value is
//                          NOT live there, so the pad replays `(**(*a2+0x54))(a2, d2, -1)`. Returns
//                          "Track" only for id 140 AND value < 0, else tail-jumps to the stock accessor.
//
// ASSERTED PER CASE: return value (or which tail-jump was taken), SP balance, the caller's frame
// untouched, callee-saved registers d2-d7/a2-a6 intact, and for popupname that the replayed vtable
// call saw exactly (this, id, -1) in that order and that its 3 arguments were popped.
//
// Arguments: <MAIN OS of a DT OG++ build | raw pad file> [addend shortname popupname]
//   The pad bytes (104 B) are read at 0x40015616 from the build's decompressed MAIN OS
//   (./scripts/extract.sh 1.54:out/1.54/<build>.syx, then work/dt_1.54-<build>/section_3_MAIN_OS.bin),
//   or taken whole from a raw pad file assembled for that address.
//   The three entry points default to the reference build's (0x40015616 0x4001562c 0x4001564c), whose
//   bytes the build pins by hash. If you assemble the pads yourself, pass the entry points from your
//   own listing: a wrong entry makes the harness execute mid-instruction, which looks like a firmware
//   bug but is a harness bug.
//   ./scripts/ghidra_emu.sh 1.54 EmuChanLabel work/dt_1.54-<build>/section_3_MAIN_OS.bin
// Runs in Ghidra's emulator only; nothing touches a device. @category dt_og_plus_plus
import ghidra.app.script.GhidraScript;
import ghidra.app.emulator.EmulatorHelper;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Arrays;

public class EmuChanLabel extends GhidraScript {
  static final long PAD = 0x40015616L;
  static final int  PAD_LEN = 104;
  static long ADDEND = 0x40015616L, SHORT = 0x4001562cL, POPUP = 0x4001564cL;
  static final long STOCK_SHORT = 0x4000fe8aL, STOCK_LONG = 0x4000feacL;
  static final long TRK = 0x401d0d44L, TRACK = 0x401cca3fL;
  static final long RET = 0x40030db4L;          // any address that is not inside the pad
  static final long SP0 = 0x40258600L;
  static final long PAGE = 0x439d3000L, VPTR = 0x439d3200L, GETTER = 0x400a9900L;
  // A build may route the pads' fall-through through the CFO oscillator's rename routines (in the pad
  // FUN_400f77da) before the stock accessor. Given a whole MAIN OS, that pad is loaded too and may be
  // passed through; for the ids tested here it must reach the stock accessor without asking the page
  // for its machine (FUN_4002b5d4 is only for ONESHOT's SRC ids 108..115).
  static final long CFO_PAD = 0x400f77daL, QUERY = 0x4002b5d4L;
  static final int  CFO_LEN = 2372;
  byte[] cfoBytes = null;

  static final int    MAIN_SIZE = 2479680;
  static final long   MAIN_BASE = 0x40000400L;
  static final String STOCK_MAIN_SHA256 = "5c58bf9e3949ef09977c5fc007a61e8d026931f67f1621238379dfb8ee4d31a2";
  static final String REF_MAIN_SHA256   = "3b88fa95268a05cba3181c8765b3379a46237b00def0820d1c8c4da499506f37";

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
    int coff = (int) (CFO_PAD - MAIN_BASE);
    cfoBytes = Arrays.copyOfRange(f, coff, coff + CFO_LEN);
    return Arrays.copyOfRange(f, off, off + len);
  }

  EmulatorHelper emu;
  void wr(long a, long v, int n) throws Exception {
    byte[] b = new byte[n]; for (int i = 0; i < n; i++) b[n - 1 - i] = (byte) ((v >> (8 * i)) & 0xff);
    emu.writeMemory(toAddr(a), b);
  }
  long rd(String r) throws Exception { return emu.readRegister(r).longValue() & 0xffffffffL; }
  long rd32(long a) throws Exception { byte[] b = emu.readMemory(toAddr(a), 4); long v = 0; for (int i = 0; i < 4; i++) v = (v << 8) | (b[i] & 0xff); return v; }

  static final String[] SAVED = {"D2","D3","D4","D5","D6","D7","A2","A3","A4","A5","A6"};

  /** returns "" on success, else the failure text */
  String run(long entry, long id, long value, boolean valueOnStack, boolean useGetter,
             long wantRet, long wantTail) throws Exception {
    emu = new EmulatorHelper(currentProgram);
    emu.writeMemory(toAddr(SP0 - 0x600), new byte[0x1000]);
    emu.writeMemory(toAddr(PAD), padBytes);
    if (cfoBytes != null) emu.writeMemory(toAddr(CFO_PAD), cfoBytes);
    emu.writeMemory(toAddr(PAGE), new byte[0x400]);
    wr(PAGE, VPTR, 4);
    wr(VPTR + 0x54, GETTER, 4);

    long sp = SP0;
    wr(sp, RET, 4);
    wr(sp + 4, 0x11111111L, 4);                 // arg0 ParameterSet* (the stock accessor ignores it)
    wr(sp + 8, id, 4);                          // arg1 id
    if (entry == ADDEND) wr(sp + 12, value, 4); // the formatter's own value arg
    if (valueOnStack) wr(sp + 100, value, 4);   // the grid-cell raw value
    long[] canary = new long[6];
    for (int i = 0; i < 6; i++) { canary[i] = 0xCA0000L + i; wr(sp + 16 + 4 * i, canary[i], 4); }

    emu.writeRegister("SP", sp);
    emu.writeRegister("PC", entry);
    emu.writeRegister("D2", id);                // callers hold the id in d2
    emu.writeRegister("A2", PAGE);              // and `this` in a2
    long[] sent = new long[SAVED.length];
    for (int i = 0; i < SAVED.length; i++) {
      sent[i] = (SAVED[i].equals("D2")) ? id : (SAVED[i].equals("A2") ? PAGE : 0x5A5A0000L + i);
      emu.writeRegister(SAVED[i], sent[i]);
    }

    StringBuilder bad = new StringBuilder();
    boolean getterHit = false;
    long getterSp = 0;
    for (int steps = 0; steps < 500; steps++) {
      long pc = emu.getExecutionAddress().getOffset();
      if (pc == GETTER) {
        getterHit = true;
        long s = rd("SP");
        getterSp = s;
        if (rd32(s + 4) != PAGE) bad.append(String.format(" [getter arg1=0x%08x want this]", rd32(s + 4)));
        if (rd32(s + 8) != id) bad.append(String.format(" [getter arg2=%d want id %d]", rd32(s + 8), id));
        if (rd32(s + 12) != 0xffffffffL) bad.append(" [getter arg3 != -1]");
        emu.writeRegister("D0", value);
        emu.writeRegister("SP", s + 4);
        emu.writeRegister("PC", rd32(s));
        continue;
      }
      if (pc == STOCK_SHORT || pc == STOCK_LONG) {
        if (wantTail == 0) bad.append(" [tail-jumped when it should have returned a label]");
        else if (pc != wantTail) bad.append(String.format(" [tail-jumped to 0x%08x want 0x%08x]", pc, wantTail));
        if (rd("SP") != sp) bad.append(" [SP not restored before the tail jump]");
        if (rd32(sp + 4) != 0x11111111L || rd32(sp + 8) != id) bad.append(" [callee frame disturbed]");
        break;
      }
      if (pc == RET) {
        if (wantTail != 0) bad.append(" [returned a label when it should have tail-jumped]");
        long d0 = rd("D0");
        if (d0 != wantRet) bad.append(String.format(" [d0=0x%08x want 0x%08x]", d0, wantRet));
        if (rd("SP") != sp + 4) bad.append(String.format(" [SP=0x%08x want 0x%08x]", rd("SP"), sp + 4));
        break;
      }
      if (pc == QUERY) { bad.append(" [the CFOO rename asked for the page's machine]"); break; }
      boolean inCfo = cfoBytes != null && pc >= CFO_PAD && pc < CFO_PAD + CFO_LEN;
      if ((pc < PAD || pc >= PAD + PAD_LEN) && !inCfo) { bad.append(" [escaped the pad at 0x" + Long.toHexString(pc) + "]"); break; }
      if (!emu.step(monitor)) { bad.append(" [FAULT: " + emu.getLastError() + "]"); break; }
    }
    for (int i = 0; i < 6; i++) if (rd32(sp + 16 + 4 * i) != canary[i]) bad.append(" [caller frame clobbered]");
    for (int i = 0; i < SAVED.length; i++)
      if (rd(SAVED[i]) != sent[i]) bad.append(" [" + SAVED[i] + " CLOBBERED]");
    if (useGetter && !getterHit) bad.append(" [the value getter was never called]");
    if (!useGetter && getterHit) bad.append(" [an unexpected getter call]");
    emu.dispose();
    return bad.toString();
  }

  void one(String label, long entry, long id, long value, boolean onStack, boolean useGetter,
           long wantRet, long wantTail) throws Exception {
    String bad = run(entry, id, value, onStack, useGetter, wantRet, wantTail);
    println(String.format("  %-56s %s%s", label, bad.isEmpty() ? "OK" : "**FAIL**", bad));
    if (!bad.isEmpty()) fails++;
  }
  int fails = 0;

  public void run() throws Exception {
    String[] args = getScriptArgs();
    if (args.length < 1) { printerr("usage: EmuChanLabel.java <MAIN OS of a build | raw pad file> [addend shortname popupname]"); return; }
    padBytes = loadPad(args[0], PAD, PAD_LEN);
    if (args.length >= 4) { ADDEND = Long.decode(args[1]); SHORT = Long.decode(args[2]); POPUP = Long.decode(args[3]); }

    println("=== midi_loopback: the three CHAN/TRK display pads @0x40015616 ===");
    println(String.format("    entry points: addend 0x%08x  shortname 0x%08x  popupname 0x%08x",
                          ADDEND, SHORT, POPUP));
    if (ADDEND != PAD) { println("    ** the addend entry must be the pad base 0x40015616; aborting **"); return; }

    println("  -- addend: the formatter's +1.0 / +9.0 (⚠️ shared by 3 other parameters) --");
    one("value 0    (CHAN ch1)            -> +1.0", ADDEND, 0, 0x00000000L, false, false, 0x100, 0);
    one("value 15.0 (CHAN ch16)           -> +1.0", ADDEND, 0, 0x00000f00L, false, false, 0x100, 0);
    one("value 63.0 (a Slice Length)      -> +1.0", ADDEND, 0, 0x00003f00L, false, false, 0x100, 0);
    one("value 127.0 (a Bank/Program)     -> +1.0", ADDEND, 0, 0x00007f00L, false, false, 0x100, 0);
    one("value -1.0  (the new TRK8)       -> +9.0", ADDEND, 0, 0xffffff00L, false, false, 0x900, 0);
    one("value -8.0  (the new TRK1)       -> +9.0", ADDEND, 0, 0xfffff800L, false, false, 0x900, 0);

    println("  -- shortname: \"TRK\" only for CHAN below zero --");
    one("id 140 value -1.0  -> \"TRK\"", SHORT, 140, 0xffffff00L, true, false, TRK, 0);
    one("id 140 value -8.0  -> \"TRK\"", SHORT, 140, 0xfffff800L, true, false, TRK, 0);
    one("id 140 value 0     -> stock", SHORT, 140, 0x00000000L, true, false, 0, STOCK_SHORT);
    one("id 140 value 15.0  -> stock", SHORT, 140, 0x00000f00L, true, false, 0, STOCK_SHORT);
    one("id 141 (Bank)  value -1.0 -> stock", SHORT, 141, 0xffffff00L, true, false, 0, STOCK_SHORT);
    one("id 137 (SliceLen) value -1.0 -> stock", SHORT, 137, 0xffffff00L, true, false, 0, STOCK_SHORT);
    one("id 0   value -1.0 -> stock", SHORT, 0, 0xffffff00L, true, false, 0, STOCK_SHORT);

    println("  -- popupname: \"Track\" only for CHAN below zero; replays the value getter --");
    one("id 140 value -1.0  -> \"Track\"", POPUP, 140, 0xffffff00L, false, true, TRACK, 0);
    one("id 140 value -8.0  -> \"Track\"", POPUP, 140, 0xfffff800L, false, true, TRACK, 0);
    one("id 140 value 0     -> stock", POPUP, 140, 0x00000000L, false, true, 0, STOCK_LONG);
    one("id 140 value 15.0  -> stock", POPUP, 140, 0x00000f00L, false, true, 0, STOCK_LONG);
    one("id 141 (Bank) -> stock, no getter call", POPUP, 141, 0xffffff00L, false, false, 0, STOCK_LONG);
    one("id 163 (last record) -> stock", POPUP, 163, 0xffffff00L, false, false, 0, STOCK_LONG);

    println(fails == 0
        ? "=== ALL CASES PASS: stock values untouched, new labels only below zero, frames and registers intact ==="
        : "=== " + fails + " FAILURE(S) ABOVE: DO NOT FLASH ===");
  }
}
