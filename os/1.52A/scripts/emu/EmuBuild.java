// Run FUN_4007489e (builds the engine object) with a tagged voice mirror, and check that each engine
// block picks up its own track's fields. Doubles as an EMAC probe (its first loop uses the MAC
// accumulators).
//   ./scripts/ghidra_emu.sh 1.52A EmuBuild
// Runs in Ghidra's emulator only; nothing touches a device. @category dt_og_plus_plus
import ghidra.app.script.GhidraScript;
import ghidra.app.emulator.EmulatorHelper;
import ghidra.program.model.address.Address;

public class EmuBuild extends GhidraScript {
  EmulatorHelper emu;
  void wr(long a,long v,int n) throws Exception { byte[] b=new byte[n]; for(int i=0;i<n;i++) b[n-1-i]=(byte)((v>>(8*i))&0xff); emu.writeMemory(toAddr(a),b); }
  long rd(long a,int n) throws Exception { byte[] b=emu.readMemory(toAddr(a),n); long v=0; for(int i=0;i<n;i++) v=(v<<8)|(b[i]&0xff); return v; }
  void map(long a,int n) throws Exception { emu.writeMemory(toAddr(a),new byte[n]); }
  public void run() throws Exception {
    emu=new EmulatorHelper(currentProgram);
    long ENTRY=0x4007489eL, SP=0x4021d800L, RET=0x00000002L, MIRROR=0x800014f0L;
    map(SP-0x400,0x800);
    map(0x80001400,0x600);     // voice mirror region
    map(0x80002700,0x1200);    // engine object 0x80002760 + blocks
    map(0x80002b00,0x1200);    // first-loop target 0x80002b2c
    map(0x80008000,0x400);     // DAT_8000275c etc (first loop writes 0x8000275c)
    // tag each mirror track: track N's +0x1a = 0xAA00+N, +0x2a = 0xBB00+N
    for (int t=0;t<8;t++){ wr(MIRROR + t*0x6aL + 0x1a, 0xAA00+t, 2); wr(MIRROR + t*0x6aL + 0x2a, 0xBB00+t, 2); }
    emu.writeRegister("SP",SP); emu.writeRegister("PC",ENTRY);
    wr(SP+0,RET,4); wr(SP+4,MIRROR,4);
    boolean faulted=false; Address faultAt=null; String err=null;
    int steps=0;
    for (int i=0;i<200000;i++){
      Address at=emu.getExecutionAddress();
      if (at.getOffset()==RET){ steps=i; break; }
      if (!emu.step(monitor)){ faulted=true; faultAt=at; err=emu.getLastError();
        println("FAULT at "+at+"  insn="+(getInstructionAt(at)!=null?getInstructionAt(at).toString():"?")+"  err="+err); break; }
      steps=i;
    }
    long ret=emu.readRegister("D0").longValue()&0xffffffffL;
    println("finished steps="+steps+"  return D0="+String.format("%08x",ret)+"  (expect 0x80002760)");
    if(!faulted){
      // engineObj built at 0x8000277a + N*0x6a; block N field[0] (from mirror +0x1a) and [8] (word*8 = +0x10)
      println("=== engine blocks (0x8000277a + N*0x6a): field[+0]=word, field[+0x10]=word ===");
      for (int t=0;t<8;t++){
        long f0 = rd(0x8000277aL + t*0x6aL, 2);
        long f10 = rd(0x8000277aL + t*0x6aL + 0x10, 2);
        println(String.format("  block %d: +0=%04x (expect AA0%d)  +0x10=%04x (expect BB0%d)", t, f0, t, f10, t));
      }
    }
    emu.dispose();
  }
}
