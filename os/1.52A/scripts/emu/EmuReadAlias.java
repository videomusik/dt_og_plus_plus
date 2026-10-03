// poly_ui: step the read/write alias pad at 0x400afe46, which sits at the tail of
// MachineParameterPageView::vfunc_41 and rewrites vfunc_41's TRACK argument in place.
//
// The pad is entered by `jmp`, so the frame is the one FUN_40018cec expects:
//     (sp)=caller's return address, +4=project, +8=paramId, +12=track
// It must substitute groupSource[currentTrack] when the caller asked for "current" (-1), leave an
// explicit track untouched, and leave -1 in place for MIDI/master/negative (stock substitutes those).
//
// ASSERTED PER CASE:
//   1. the TRACK slot (+12) ends up with the intended value
//   2. the OTHER slots are untouched: (sp)=ret, +4=project, +8=paramId   <-- in-place rewrite safety
//   3. SP is exactly back to entry (the internal push/pop leaks nothing)
//   4. callee-saved d2-d7 / a2-a6 unclobbered
//   5. the nested calls receive the RIGHT arguments (project -> FUN_4001488e -> FUN_4001ccc4)
//   6. control reaches FUN_40018cec and nowhere else
//   7. for an EXPLICIT track, no call is made at all (the fast path really is a fast path)
//
// The pad bytes are built in (our own code, as the assembler produced it, checked against objdump at
// 0x400afe46 and identical to the reference build). No arguments.
//   ./scripts/ghidra_emu.sh 1.52A EmuReadAlias
// Runs in Ghidra's emulator only; nothing touches a device. @category dt_og_plus_plus
import ghidra.app.script.GhidraScript;
import ghidra.app.emulator.EmulatorHelper;

public class EmuReadAlias extends GhidraScript {
  EmulatorHelper emu;

  // exactly as m68k-elf-as produced it, objdump-verified at 0x400afe46
  static final String PAD_HEX =
      "202f000c5280662c2f2f00044eb94001488e2e804eb94001ccc4588f4a80"
    + "6d147207b2806d0e41f9439902f0103008002f40000c4ef940018cec";

  static final long PAD=0x400afe46L, SEL=0x4001488eL, CUR=0x4001ccc4L, ONWARD=0x40018cecL;
  static final long GROUPSRC=0x439902f0L, STACKTOP=0x4021c800L;
  static final long RET=0x40030d4aL, PROJECT=0x421b9d30L, PARAMID=0x77L, SELOBJ=0x421ba044L;
  static final int[] MAP={0,0,0,3,3,3,6,7};

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

  /** incomingTrack = what vfunc_41 was asked for; currentTrack = what FUN_4001ccc4 would answer. */
  boolean one(long incomingTrack, long currentTrack, String label) throws Exception {
    emu=new EmulatorHelper(currentProgram);
    emu.writeMemory(toAddr(0x4021c000L),new byte[0x1000]);
    emu.writeMemory(toAddr(GROUPSRC),new byte[16]);
    emu.writeMemory(toAddr(PAD),hexToBytes(PAD_HEX));
    for(int i=0;i<8;i++) wr(GROUPSRC+i,MAP[i],1);

    long sp=STACKTOP-16;
    wr(sp,RET,4); wr(sp+4,PROJECT,4); wr(sp+8,PARAMID,4); wr(sp+12,incomingTrack,4);
    emu.writeRegister("SP",sp);
    emu.writeRegister("PC",PAD);
    String[] saved={"D2","D3","D4","D5","D6","D7","A2","A3","A4","A5","A6"};
    long[] sent=new long[saved.length];
    for(int i=0;i<saved.length;i++){ sent[i]=0x5A5A0000L+i; emu.writeRegister(saved[i],sent[i]); }

    boolean selOk=true, curOk=true; int calls=0;
    for(int steps=0;steps<800;steps++){
      long pc=emu.getExecutionAddress().getOffset();
      if(pc==SEL){                                  // stub FUN_4001488e(project)
        calls++;
        long s=rd("SP"); if(rdMem(s+4)!=PROJECT) selOk=false;
        emu.writeRegister("D0",SELOBJ);
        emu.writeRegister("SP",s+4); emu.writeRegister("PC",rdMem(s));
        continue;
      }
      if(pc==CUR){                                  // stub FUN_4001ccc4(selObj)
        calls++;
        long s=rd("SP"); if(rdMem(s+4)!=SELOBJ) curOk=false;
        emu.writeRegister("D0",currentTrack);
        emu.writeRegister("SP",s+4); emu.writeRegister("PC",rdMem(s));
        continue;
      }
      if(pc==ONWARD){                               // the endpoint: inspect the frame we hand over
        long fsp=rd("SP");
        long wantTrack;
        if(incomingTrack!=-1L) wantTrack=incomingTrack&0xffffffffL;               // explicit: untouched
        else if(currentTrack>=0 && currentTrack<=7) wantTrack=MAP[(int)currentTrack];// remapped
        else wantTrack=0xffffffffL;                                               // stays -1
        boolean tOk = rdMem(fsp+12)==wantTrack;
        boolean keep= rdMem(fsp)==RET && rdMem(fsp+4)==PROJECT && rdMem(fsp+8)==PARAMID;
        boolean spOk= fsp==sp;
        boolean regOk=true; StringBuilder bad=new StringBuilder();
        for(int i=0;i<saved.length;i++)
          if(rd(saved[i])!=sent[i]){ regOk=false; bad.append(" ").append(saved[i]); }
        boolean fastOk = (incomingTrack!=-1L) ? (calls==0) : true;
        boolean ok = tOk&&keep&&spOk&&regOk&&selOk&&curOk&&fastOk;
        println(String.format(
          "  %-28s track slot 0x%08x (want 0x%08x) %s | other slots %s | SP %s | regs %s | args %s | calls %d %s",
          label, rdMem(fsp+12), wantTrack, tOk?"OK":"**WRONG**", keep?"intact":"**CLOBBERED**",
          spOk?"balanced":"**LEAK**", regOk?"preserved":"**CLOBBERED:"+bad+"**",
          (selOk&&curOk)?"OK":"**WRONG ARG**", calls, ok?"":"  <== FAIL"));
        emu.dispose(); return ok;
      }
      if(!emu.step(monitor)){
        println("  "+label+" -> **FAULT** at "+emu.getExecutionAddress()+" : "+emu.getLastError());
        emu.dispose(); return false;
      }
    }
    println("  "+label+" -> **never reached FUN_40018cec**");
    emu.dispose(); return false;
  }

  public void run() throws Exception {
    println("=== poly_ui: read/write alias pad @0x400afe46 (vfunc_41 tail) -- step-through ===");
    println("    groupSource = [0,0,0,3,3,3,6,7]");
    boolean all=true;
    for(long t=0;t<=7;t++) all &= one(-1L,t,"asked=current, cur="+t);
    all &= one(-1L, 8L,"asked=current, cur=8 (MIDI)");
    all &= one(-1L,15L,"asked=current, cur=15 (MIDI)");
    all &= one(-1L,16L,"asked=current, cur=16 (master)");
    all &= one(-1L,-1L,"asked=current, cur=-1");
    // explicit tracks must pass through untouched, with NO calls made
    all &= one( 3L, 5L,"asked=3 (explicit)");
    all &= one( 0L, 5L,"asked=0 (explicit)");
    all &= one(16L, 5L,"asked=16 (explicit master)");
    println(all ? "=== ALL CASES PASS: in-place rewrite correct, frame + SP + regs intact ==="
                : "=== FAILURES ABOVE: DO NOT FLASH ===");
  }
}
