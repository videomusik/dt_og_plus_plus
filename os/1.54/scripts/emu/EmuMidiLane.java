// midi_loopback: step the private-lane dispatch arm at 0x4001567e (USB-MIDI cable 1, input queue
// 0x421a9d3c) and check that it behaves exactly like the live DIN arm it mirrors, but on its own queue
// and with its own origin tag.
//
// The Digitakt's MIDI input task services three queues. The DIN and USB ones are dispatched; the third
// (cable 1) is initialised and wired to the USB packet handler, but stock only discards what it
// receives. midi_loopback posts its internal notes to that queue, and this arm dispatches them with the
// origin tag 0x20, so the rest of the input path can tell internal notes from real MIDI.
//
// The arm is reached by `jmp` from 0x400d49e0, i.e. from INSIDE the MIDI input task FUN_400d486a, so
// the task's registers are live and the arm reuses them:  %a4 = the queue receive routine,
// %a2 = the dispatch table 0x401b8dd4.  It must end by jumping back to the loop top at 0x400d48e6.
//
// ASSERTED PER CASE:
//   1. it receives from queue 0x421a9d3c and NOT from the DIN (0x421a9d7c) or USB (0x421a9d5c) queue
//   2. it indexes the dispatch table with status>>4 (so 0x9n -> note-on, 0x8n -> note-off, ...)
//   3. the handler's three arguments are (bytes, length, 0x20) in that order; 0x20 is the private
//      origin tag
//   4. SP is balanced on exit and the task's own registers (a2-a6, d2-d7) survive
//   5. control leaves only via `jmp 0x400d48e6`
//
// Argument: the decompressed MAIN OS of a DT OG++ build (./scripts/extract.sh 1.54:out/1.54/<build>.syx,
// then work/dt_1.54-<build>/section_3_MAIN_OS.bin), from which the 50-byte arm is read at 0x4001567e;
// or a raw file holding just the arm, assembled for that address.
//   ./scripts/ghidra_emu.sh 1.54 EmuMidiLane work/dt_1.54-<build>/section_3_MAIN_OS.bin
// Runs in Ghidra's emulator only; nothing touches a device. @category dt_og_plus_plus
import ghidra.app.script.GhidraScript;
import ghidra.app.emulator.EmulatorHelper;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Arrays;

public class EmuMidiLane extends GhidraScript {
  static final long PAD = 0x4001567eL;
  static final int  PAD_LEN = 50;
  static final long LOOPTOP = 0x400d48e6L;
  static final long Q2 = 0x421a9d3cL, QDIN = 0x421a9d7cL, QUSB = 0x421a9d5cL;
  static final long RECV = 0x400a9100L;            // stub standing in for the queue receive routine
  static final long TABLE = 0x439d2200L;           // fake dispatch table
  static final long HANDLER0 = 0x400a9200L;        // handler[i] = HANDLER0 + i*0x10
  static final long DESC = 0x439d2000L, MSG = 0x439d2100L;
  static final long SP0 = 0x40258600L;

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

  static final String[] TASKREGS = {"D2","D3","D4","D5","D6","D7","A2","A3","A4","A5","A6"};
  int fails = 0;

  void one(String label, int status, int b1, int b2, int len, int wantIndex) throws Exception {
    emu = new EmulatorHelper(currentProgram);
    emu.writeMemory(toAddr(SP0 - 0x600), new byte[0x1000]);
    emu.writeMemory(toAddr(DESC), new byte[0x400]);
    emu.writeMemory(toAddr(PAD), padBytes);
    wr(DESC, len, 4); wr(DESC + 4, MSG, 4);
    wr(MSG, status, 1); wr(MSG + 1, b1, 1); wr(MSG + 2, b2, 1);
    for (int i = 0; i < 16; i++) wr(TABLE + 4 * i, HANDLER0 + i * 0x10, 4);

    long sp = SP0;
    emu.writeRegister("SP", sp);
    emu.writeRegister("PC", PAD);
    long[] sent = new long[TASKREGS.length];
    for (int i = 0; i < TASKREGS.length; i++) {
      long v = TASKREGS[i].equals("A2") ? TABLE : TASKREGS[i].equals("A4") ? RECV : 0x5A5A0000L + i;
      sent[i] = v; emu.writeRegister(TASKREGS[i], v);
    }

    StringBuilder bad = new StringBuilder();
    boolean recvHit = false, handlerHit = false;
    int gotIndex = -1;
    for (int steps = 0; steps < 600; steps++) {
      long pc = emu.getExecutionAddress().getOffset();
      if (pc == RECV) {
        recvHit = true;
        long s = rd("SP");
        long q = rd32(s + 4);
        if (q != Q2) bad.append(String.format(" [received from 0x%08x, want the cable-1 queue]", q));
        if (q == QDIN || q == QUSB) bad.append(" [** IT TOUCHED A PHYSICAL PORT'S QUEUE **]");
        emu.writeRegister("D0", DESC);
        emu.writeRegister("SP", s + 4);
        emu.writeRegister("PC", rd32(s));
        continue;
      }
      if (pc >= HANDLER0 && pc < HANDLER0 + 16 * 0x10) {
        handlerHit = true;
        gotIndex = (int) ((pc - HANDLER0) / 0x10);
        long s = rd("SP");
        if (rd32(s + 4) != MSG) bad.append(String.format(" [arg1=0x%08x want the message bytes]", rd32(s + 4)));
        if (rd32(s + 8) != len) bad.append(String.format(" [arg2=%d want length %d]", rd32(s + 8), len));
        if (rd32(s + 12) != 0x20) bad.append(String.format(" [arg3=0x%02x want the 0x20 origin tag]", rd32(s + 12)));
        emu.writeRegister("SP", s + 4);
        emu.writeRegister("PC", rd32(s));
        continue;
      }
      if (pc == LOOPTOP) {
        if (rd("SP") != sp) bad.append(String.format(" [SP=0x%08x want 0x%08x]", rd("SP"), sp));
        break;
      }
      if (pc < PAD || pc >= PAD + 64) { bad.append(" [escaped to 0x" + Long.toHexString(pc) + "]"); break; }
      if (!emu.step(monitor)) { bad.append(" [FAULT: " + emu.getLastError() + "]"); break; }
    }
    if (!recvHit) bad.append(" [never received from the queue]");
    if (!handlerHit) bad.append(" [no handler was called]");
    else if (gotIndex != wantIndex) bad.append(String.format(" [dispatched index %d want %d]", gotIndex, wantIndex));
    for (int i = 0; i < TASKREGS.length; i++)
      if (rd(TASKREGS[i]) != sent[i]) bad.append(" [" + TASKREGS[i] + " CLOBBERED; the task loop needs it]");
    println(String.format("  %-52s -> handler[%2d] %s%s", label, gotIndex,
                          bad.length() == 0 ? "OK" : "**FAIL**", bad));
    if (bad.length() != 0) fails++;
    emu.dispose();
  }

  public void run() throws Exception {
    String[] args = getScriptArgs();
    if (args.length < 1) { printerr("usage: EmuMidiLane.java <MAIN OS of a build | raw pad file>"); return; }
    padBytes = loadPad(args[0], PAD, PAD_LEN);

    println("=== midi_loopback: the private-lane (USB-MIDI cable 1) dispatch arm @0x4001567e ===");
    println("    queue 0x421a9d3c, origin tag 0x20, loop-back to 0x400d48e6");
    one("note ON  0x90 ch1  note 60 vel 100", 0x90, 60, 100, 3, 9);
    one("note ON  0x95 ch6  note 36 vel 127", 0x95, 36, 127, 3, 9);
    one("note OFF 0x80 ch1  note 60 vel 0",   0x80, 60, 0, 3, 8);
    one("note OFF 0x9f ch16 note 72 vel 0",   0x9f, 72, 0, 3, 9);
    one("CC       0xb0 cc74 val 64",          0xb0, 74, 64, 3, 11);
    one("program  0xc3 prog 5",               0xc3, 5, 0, 2, 12);
    one("sysex    0xf0 (no INPUT-FROM gate)", 0xf0, 0x7e, 0, 8, 15);
    one("pitchbend 0xe2 lsb 0 msb 64",        0xe2, 0, 64, 3, 14);
    println(fails == 0
        ? "=== ALL CASES PASS: right queue, right dispatch index, tag 0x20, SP balanced, task registers intact ==="
        : "=== " + fails + " FAILURE(S) ABOVE: DO NOT FLASH ===");
  }
}
