// poly_ui: step the two track-remap pads and check the stack discipline and the remap arithmetic.
//
// Why the stack check matters: a pad that wraps `jsr FUN_4001d24e` must push the argument again
// first. If it does not, its own jsr puts a return address where the argument should be;
// FUN_4001d24e then dereferences that return address, calls through a garbage vtable, and the device
// faults (vector 4) during boot. Both pads below re-push the argument; this harness proves it, plus
// the remap arithmetic.
//
// TWO PADS, WITH DIFFERENT OUT-OF-RANGE RULES ON PURPOSE:
//   A) @0x400bee02 (38 B) feeds vfunc_41's TRACK ARGUMENT. Out of range it must return -1, because -1
//      is stock's "use the current track" sentinel, and that is what keeps MIDI/master/FX on the stock
//      path.
//   B) @0x400bed74 (34 B) feeds FUN_4000d7be(base, track) inside getMachineTypeToShow. Out of range it
//      must return the track UNCHANGED, because that consumer wants a real track index (it clamps
//      0..7 itself); handing it -1 would silently resolve the wrong sound.
//
// ASSERTED PER CASE:
//   1. STACK DISCIPLINE  at FUN_4001d24e's entry, 4(sp) == the argument the caller pushed
//   2. RETURN VALUE      per the pad's out-of-range rule above
//   3. STACK BALANCE     on rts, SP is exactly back to the caller's frame
//   4. CALLEE-SAVED REGS d2-d7 / a2-a6 untouched (both call sites hold live values in them)
//   5. CONTROL FLOW      the pad returns to the caller's return address and nowhere else
//
// The pad bytes are built in (our own code, as the assembler produced it, checked against objdump at
// their load addresses and identical to the reference build). No arguments.
//   ./scripts/ghidra_emu.sh 1.54 EmuTrackAlias
// Runs in Ghidra's emulator only; nothing touches a device. @category dt_og_plus_plus
import ghidra.app.script.GhidraScript;
import ghidra.app.emulator.EmulatorHelper;

public class EmuTrackAlias extends GhidraScript {
  EmulatorHelper emu;

  // exactly as m68k-elf-as produced them, objdump-verified at their load addresses
  static final long   PAD_A     = 0x400bee02L;
  static final String PAD_A_HEX =
      "2f2f00044eb94001d24e588f4a806d127207b2806d0c41f9439d1050103008004e7570ff4e75";
  static final long   PAD_B     = 0x400bed74L;
  static final String PAD_B_HEX =
      "2f2f00044eb94001d24e588f4a806d107207b2806d0a41f9439d1050103008004e75";

  static final long CURTRACK  = 0x4001d24eL;   // stubbed
  static final long GROUPSRC  = 0x439d1050L;
  static final long STACKTOP  = 0x40258800L;
  static final long CALLER_RET= 0x400309d6L;
  static final long ARG       = 0x421f1d30L;
  static final int[] MAP = {0,0,0,3,3,3,6,7};

  void wr(long a,long v,int n) throws Exception {
    byte[] b=new byte[n];
    for(int i=0;i<n;i++) b[n-1-i]=(byte)((v>>(8*i))&0xff);
    emu.writeMemory(toAddr(a),b);
  }
  long rd(String r) throws Exception { return emu.readRegister(r).longValue()&0xffffffffL; }
  long rdMem(long a) throws Exception {
    byte[] b=emu.readMemory(toAddr(a),4); long v=0;
    for(int i=0;i<4;i++) v=(v<<8)|(b[i]&0xff);
    return v;
  }
  byte[] hexToBytes(String h){
    byte[] b=new byte[h.length()/2];
    for(int i=0;i<b.length;i++) b[i]=(byte)Integer.parseInt(h.substring(2*i,2*i+2),16);
    return b;
  }

  boolean one(long track, long padAddr, String padHex, boolean identityOOR) throws Exception {
    emu=new EmulatorHelper(currentProgram);
    emu.writeMemory(toAddr(STACKTOP-0x800),new byte[0x1000]);
    emu.writeMemory(toAddr(GROUPSRC),new byte[16]);
    emu.writeMemory(toAddr(padAddr), hexToBytes(padHex));
    for(int i=0;i<8;i++) wr(GROUPSRC+i, MAP[i], 1);

    long sp = STACKTOP - 8;
    wr(sp+4, ARG, 4);
    wr(sp,   CALLER_RET, 4);
    emu.writeRegister("SP", sp);
    emu.writeRegister("PC", padAddr);
    String[] saved = {"D2","D3","D4","D5","D6","D7","A2","A3","A4","A5","A6"};
    long[] sent = new long[saved.length];
    for(int i=0;i<saved.length;i++){ sent[i]=0x5A5A0000L+i; emu.writeRegister(saved[i],sent[i]); }

    boolean stubHit=false, argOk=false; long seenArg=-1;
    for(int steps=0;steps<500;steps++){
      long pc=emu.getExecutionAddress().getOffset();
      if(pc==CURTRACK){
        stubHit=true;
        long ssp=rd("SP");
        seenArg=rdMem(ssp+4); argOk=(seenArg==ARG);
        long ret=rdMem(ssp);
        emu.writeRegister("D0", track);
        emu.writeRegister("SP", ssp+4);
        emu.writeRegister("PC", ret);
        continue;
      }
      if(pc==CALLER_RET){
        long fsp=rd("SP"), d0=rd("D0");
        long want = (track>=0 && track<=7) ? MAP[(int)track]
                                           : (identityOOR ? track : -1L);
        want &= 0xffffffffL;
        boolean d0Ok=(d0==want), spOk=(fsp==sp+4), regOk=true;
        StringBuilder bad=new StringBuilder();
        for(int i=0;i<saved.length;i++)
          if(rd(saved[i])!=sent[i]){ regOk=false; bad.append(" ").append(saved[i]); }
        boolean ok = stubHit && argOk && d0Ok && spOk && regOk;
        println(String.format(
            "    track %-3d -> d0=0x%08x (want 0x%08x) %s | callee saw arg 0x%08x %s | SP %s | regs %s%s",
            track, d0, want, d0Ok?"OK":"**WRONG**", seenArg, argOk?"OK":"**FRAME SHIFTED**",
            spOk?"balanced":"**UNBALANCED**", regOk?"preserved":"**CLOBBERED:"+bad+"**",
            ok?"":"   <== FAIL"));
        emu.dispose(); return ok;
      }
      if(!emu.step(monitor)){
        println("    track "+track+" -> **FAULT** at "+emu.getExecutionAddress()+" : "+emu.getLastError());
        emu.dispose(); return false;
      }
    }
    println("    track "+track+" -> **step cap / never returned**");
    emu.dispose(); return false;
  }

  boolean suite(String label, long pad, String hex, boolean identityOOR) throws Exception {
    println("  --- "+label+" (out of range => "+(identityOOR?"UNCHANGED":"-1")+") ---");
    boolean all=true;
    for(long t : new long[]{0,1,2,3,4,5,6,7,8,9,15,16,-1}) all &= one(t,pad,hex,identityOOR);
    return all;
  }

  public void run() throws Exception {
    println("=== poly_ui: track-remap pads -- step-through double-check ===");
    println("    groupSource = [0,0,0,3,3,3,6,7]  (tracks 0-2 pool on 0; 3-5 pool on 3; 6,7 own source)");
    boolean all=true;
    all &= suite("PAD A @0x400bee02 -> vfunc_41's track argument", PAD_A, PAD_A_HEX, false);
    all &= suite("PAD B @0x400bed74 -> FUN_4000d7be in getMachineTypeToShow", PAD_B, PAD_B_HEX, true);
    println(all ? "=== ALL CASES PASS: frames preserved, both remap rules correct, SP + regs intact ==="
                : "=== FAILURES ABOVE: DO NOT FLASH ===");
  }
}
