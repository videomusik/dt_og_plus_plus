// slice_round_robin: step the slice-window LATCH pad at 0x400c1338 and check the latch, the
// arithmetic, and the calling contract.
//
// The pad is ENTERED BY JMP from the render at 0x40074e3c and JUMPS BACK to 0x40074e62 with d2 = the
// slice index. SP is the host function FUN_40074af2's, so %sp@(40) is still its `track` argument.
//
// WHAT IS ASSERTED, per case:
//   1. THE LATCH      a voice's slice is LATCHED at its own trig and does NOT move when the pool's
//                     SHARED counter is bumped afterwards (a new trig on the pool does exactly that
//                     bump; without the latch the playing voice's slice window would hop).
//   2. ARITHMETIC     d2 == latched & (grid-1), grid = 4 << d5.
//   3. ⭐ ADDRESSES   the latch really lands at 0x439d1030+track (counters+32) and NOT inside the
//                     counter array. objdump prints the indexed displacement in HEX, so the listing
//                     reads "(20,%a3:l)" for 32; if that were 20 decimal the latch would corrupt
//                     counter[5]/counter[6]. Every byte of 0x439d1000..0x439d1060 is snapshotted and
//                     compared, so any stray write anywhere in the scratch region fails the case.
//   4. ONE SEQUENCE PER POOL  on an edge the SHARED counter[S] still advances.
//   5. REGISTERS      d0, d5, a0, a1 are LIVE across the pad in the host function (d0 is the
//                     sample-slot base; clobbering it would corrupt the render) and must be intact.
//   6. CONTROL FLOW   execution leaves the pad only via `jmp 0x40074e62`.
//
// Argument: the decompressed MAIN OS of a DT OG++ build (./scripts/extract.sh 1.54:out/1.54/<build>.syx,
// then work/dt_1.54-<build>/section_3_MAIN_OS.bin), from which the pad bytes are read at 0x400c1338;
// or a raw file holding just the pad, assembled for that address.
//   ./scripts/ghidra_emu.sh 1.54 EmuSliceLatch work/dt_1.54-<build>/section_3_MAIN_OS.bin
// Runs in Ghidra's emulator only; nothing touches a device. @category dt_og_plus_plus
import ghidra.app.script.GhidraScript;
import ghidra.app.emulator.EmulatorHelper;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Arrays;

public class EmuSliceLatch extends GhidraScript {
  static final long PADE = 0x400c1338L, RETURN = 0x40074e62L;
  static final int  PADE_LEN = 90;
  static final long TRIGMASK = 0x80001228L;
  static final long PREV = 0x439d1004L, CTR = 0x439d1010L, SLICE = 0x439d1030L;
  static final long SCRATCH_LO = 0x439d1000L, SCRATCH_HI = 0x439d1060L;
  static final long SP = 0x40258700L;

  // A whole decompressed MAIN OS section: its size, its load base, and the two hashes worth knowing.
  static final int    MAIN_SIZE = 2479680;
  static final long   MAIN_BASE = 0x40000400L;
  static final String STOCK_MAIN_SHA256 = "5c58bf9e3949ef09977c5fc007a61e8d026931f67f1621238379dfb8ee4d31a2";
  static final String REF_MAIN_SHA256   = "efc90606b8d0d1637f41d6eac9cc19652e2c78800178bb9b6bcac3524bdef29e";

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
  int  rd8(long a) throws Exception { return emu.readMemory(toAddr(a), 1)[0] & 0xff; }
  long rd32(long a) throws Exception { byte[] b = emu.readMemory(toAddr(a), 4); long v = 0; for (int i = 0; i < 4; i++) v = (v << 8) | (b[i] & 0xff); return v; }

  /** one tick of the pad. trig=the engine's new-trig bit for this track; prev=prev[track] going in. */
  boolean tick(String label, int track, int[] map, int trig, int prev, long[] ctr, int[] slice,
               int gridShift, long expectD2, boolean expectBump, int expectLatch) throws Exception {
    emu = new EmulatorHelper(currentProgram);
    emu.writeMemory(toAddr(SP - 0x700), new byte[0x1000]);
    emu.writeMemory(toAddr(PADE), padBytes);
    emu.writeMemory(toAddr(SCRATCH_LO), new byte[(int) (SCRATCH_HI - SCRATCH_LO)]);
    emu.writeMemory(toAddr(0x80001200L), new byte[0x80]);
    for (int i = 0; i < 8; i++) {
      wr(PREV + i, (i == track) ? prev : 0xAA, 1);          // other tracks get a sentinel
      wr(CTR + 4 * i, ctr[i], 4);
      wr(SLICE + i, slice[i], 1);
      wr(0x439d1050L + i, map[i], 1);                       // groupSource
    }
    wr(TRIGMASK, (long) trig << track, 4);

    byte[] before = emu.readMemory(toAddr(SCRATCH_LO), (int) (SCRATCH_HI - SCRATCH_LO));

    wr(SP + 40, track, 4);
    emu.writeRegister("SP", SP);
    emu.writeRegister("D5", gridShift);
    long d0 = 0xD0D0D0D0L, a0 = 0xA0A0A0A0L, a1 = 0xA1A1A1A1L;
    emu.writeRegister("D0", d0); emu.writeRegister("A0", a0); emu.writeRegister("A1", a1);
    emu.writeRegister("PC", PADE);

    for (int steps = 0; steps < 400; steps++) {
      long pc = emu.getExecutionAddress().getOffset();
      if (pc == RETURN) {
        StringBuilder bad = new StringBuilder();
        long d2 = rd("D2");
        if (d2 != expectD2) bad.append(String.format(" [d2=%d want %d]", d2, expectD2));
        if (rd("D0") != d0) bad.append(" [d0 CLOBBERED -- would corrupt the sample base]");
        if (rd("D5") != gridShift) bad.append(" [d5 CLOBBERED]");
        if (rd("A0") != a0) bad.append(" [a0 CLOBBERED]");
        if (rd("A1") != a1) bad.append(" [a1 CLOBBERED]");
        int S = map[track];
        long wantCtr = ctr[S] + (expectBump ? 1 : 0);
        if (rd32(CTR + 4 * S) != wantCtr) bad.append(String.format(" [counter[%d]=%d want %d]", S, rd32(CTR + 4 * S), wantCtr));
        if (rd8(SLICE + track) != expectLatch) bad.append(String.format(" [slice[%d]=%d want %d]", track, rd8(SLICE + track), expectLatch));
        if (rd8(PREV + track) != trig) bad.append(" [prev not updated]");
        // ⭐ no stray writes anywhere in the scratch region
        byte[] after = emu.readMemory(toAddr(SCRATCH_LO), (int) (SCRATCH_HI - SCRATCH_LO));
        for (int i = 0; i < before.length; i++) {
          long addr = SCRATCH_LO + i;
          boolean allowed = (addr == PREV + track) || (addr == SLICE + track)
                         || (expectBump && addr >= CTR + 4 * S && addr < CTR + 4 * S + 4);
          if (before[i] != after[i] && !allowed)
            bad.append(String.format(" [STRAY WRITE 0x%08x: %02x->%02x]", addr, before[i] & 0xff, after[i] & 0xff));
        }
        boolean ok = bad.length() == 0;
        println(String.format("  %-58s d2=%-2d %s%s", label, d2, ok ? "OK" : "**FAIL**", bad));
        emu.dispose(); return ok;
      }
      if (pc < PADE || pc >= PADE + 96) { println("  " + label + " -> **LEFT THE PAD** at " + emu.getExecutionAddress()); emu.dispose(); return false; }
      if (!emu.step(monitor)) { println("  " + label + " -> **FAULT** at " + emu.getExecutionAddress() + " : " + emu.getLastError()); emu.dispose(); return false; }
    }
    println("  " + label + " -> **step cap**"); emu.dispose(); return false;
  }

  public void run() throws Exception {
    String[] args = getScriptArgs();
    if (args.length < 1) { printerr("usage: EmuSliceLatch.java <MAIN OS of a build | raw pad file>"); return; }
    padBytes = loadPad(args[0], PADE, PADE_LEN);

    println("=== slice_round_robin: slice-window LATCH pad @0x400c1338 -- step-through ===");
    int[] pool = {0, 0, 0, 3, 3, 3, 6, 7};   // tracks 0-2 pool on 0 ; 3-5 on 3 ; 6,7 alone
    int[] ident = {0, 1, 2, 3, 4, 5, 6, 7};  // no POLY
    int g3 = 3;                              // grid = 4 << 3 = 32
    boolean all = true;

    println("  -- the edge: bump the SHARED counter and latch it for THIS voice --");
    all &= tick("POOL src t0: trig, counter 2->3, latch 3", 0, pool, 1, 0,
                new long[]{2,0,0,9,0,0,0,0}, new int[]{0,0,0,0,0,0,0,0}, g3, 3, true, 3);
    all &= tick("POOL follower t1: trig, SAME counter[0] 3->4, latch 4", 1, pool, 1, 0,
                new long[]{3,0,0,9,0,0,0,0}, new int[]{3,0,0,0,0,0,0,0}, g3, 4, true, 4);

    println("  -- ⭐ THE LATCH: a later pool trig must NOT move a playing voice's slice --");
    // t0 latched 3; meanwhile t1's trig advanced the shared counter to 7. t0 keeps ticking, no trig.
    all &= tick("t0 still playing, shared counter now 7 -> MUST stay 3", 0, pool, 0, 1,
                new long[]{7,0,0,9,0,0,0,0}, new int[]{3,4,0,0,0,0,0,0}, g3, 3, false, 3);
    all &= tick("t1 still playing, latched 4, counter 7 -> MUST stay 4", 1, pool, 0, 1,
                new long[]{7,0,0,9,0,0,0,0}, new int[]{3,4,0,0,0,0,0,0}, g3, 4, false, 4);
    println("     (without the latch both would return 7 & (grid-1): the slice window would hop)");

    println("  -- the wrap case (the 'accent'): latched 31 stays 31 while the counter rolls to 32 --");
    all &= tick("t0 latched 31, counter wraps to 32 -> MUST stay 31", 0, pool, 0, 1,
                new long[]{32,0,0,9,0,0,0,0}, new int[]{31,0,0,0,0,0,0,0}, g3, 31, false, 31);

    println("  -- one sequence per pool; and non-POLY is unchanged --");
    all &= tick("POOL t2 trig: advances the SAME counter[0] 4->5", 2, pool, 1, 0,
                new long[]{4,0,0,9,0,0,0,0}, new int[]{3,4,0,0,0,0,0,0}, g3, 5, true, 5);
    all &= tick("NON-POLY t5 trig: own counter[5] 6->7", 5, ident, 1, 0,
                new long[]{0,0,0,0,0,6,0,0}, new int[]{0,0,0,0,0,0,0,0}, g3, 7, true, 7);
    all &= tick("NON-POLY t5 non-edge: latched 7 held", 5, ident, 1, 1,
                new long[]{0,0,0,0,0,7,0,0}, new int[]{0,0,0,0,0,7,0,0}, g3, 7, false, 7);

    println("  -- masking: grid 4 (d5=0) and grid 64 (d5=4); and the byte latch vs a big counter --");
    all &= tick("grid 4: latched 7 -> 7 & 3 = 3", 0, pool, 0, 1,
                new long[]{99,0,0,9,0,0,0,0}, new int[]{7,0,0,0,0,0,0,0}, 0, 3, false, 7);
    all &= tick("grid 64: latched 70 -> 70 & 63 = 6", 0, pool, 0, 1,
                new long[]{99,0,0,9,0,0,0,0}, new int[]{70,0,0,0,0,0,0,0}, 4, 6, false, 70);
    all &= tick("edge with counter 254->255: byte latch exact", 0, pool, 1, 0,
                new long[]{254,0,0,9,0,0,0,0}, new int[]{0,0,0,0,0,0,0,0}, g3, 255 & 31, true, 255);

    println("  -- the edge detect itself: a held trig bit must NOT re-bump --");
    all &= tick("trig bit still 1 but prev 1 -> no bump, latch held", 0, pool, 1, 1,
                new long[]{5,0,0,9,0,0,0,0}, new int[]{3,0,0,0,0,0,0,0}, g3, 3, false, 3);
    all &= tick("no trig, prev 0 -> no bump", 0, pool, 0, 0,
                new long[]{5,0,0,9,0,0,0,0}, new int[]{3,0,0,0,0,0,0,0}, g3, 3, false, 3);

    println(all ? "=== ALL CASES PASS: latch holds against pool bumps, one sequence per pool, addresses right, d0/d5/a0/a1 preserved ==="
                : "=== FAILURES ABOVE: DO NOT FLASH ===");
  }
}
