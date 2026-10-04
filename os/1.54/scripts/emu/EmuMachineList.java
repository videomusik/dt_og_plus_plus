// poly_engine: emulate the machine-list build+add loop of the MachineListView constructor
// (FUN_4002a736, 0x4002a8fe..0x4002a9e4). Runs the REAL vector builder FUN_40022f6a and the add loop,
// STUBS the C++ calls, and COUNTS how many items get added (jsr 0x400c3c9a).
// A/B run: the project's own bytes (stock when the project was imported from the stock MAIN OS) vs
// the poly_engine machine-enum edit, written into the emulator's memory only:
//   0x40022f7e  pea 0x10     -> pea 0x14
//   0x40022fae  addi.l #16   -> addi.l #20
//   0x40022fe6  moveq #4     -> moveq #5
// (these are the build's edits at the same three sites; four machines become five).
// Run it on a project imported from the stock MAIN OS (e.g. dt_1.54_emac). No arguments.
//   ./scripts/ghidra_emu.sh 1.54 EmuMachineList
// Runs in Ghidra's emulator only; nothing touches a device. @category dt_og_plus_plus
import ghidra.app.script.GhidraScript;
import ghidra.app.emulator.EmulatorHelper;
import ghidra.program.model.address.Address;

public class EmuMachineList extends GhidraScript {
  EmulatorHelper emu;
  void wr(long addr,long val,int size) throws Exception {
    byte[] b=new byte[size];
    for(int i=0;i<size;i++) b[size-1-i]=(byte)((val>>(8*i))&0xff);
    emu.writeMemory(toAddr(addr),b);
  }
  void map(long addr,int n) throws Exception { emu.writeMemory(toAddr(addr),new byte[n]); }
  long rd(String r) throws Exception { return emu.readRegister(r).longValue()&0xffffffffL; }

  int runOnce(boolean edited) throws Exception {
    emu=new EmulatorHelper(currentProgram);
    long FRAME=0x40258800L, SP=0x40258780L, RET=0x00000002L;
    map(FRAME-0x800,0x1000);              // stack + frame scratch
    map(0x41b00000L,0x2000);              // bump-alloc scratch (vector backing + items)
    // the enum-grow edit, applied to the emulator's memory only (the project keeps its own bytes)
    if(edited){
      wr(0x40022f7eL,0x48780014L,4);      // pea 0x10 -> pea 0x14
      wr(0x40022faeL,0x06820000L,4); wr(0x40022fb2L,0x0014L,2);  // addil #16 -> #20
      wr(0x40022fe6L,0x7005L,2);          // moveq #4 -> moveq #5
    }
    // frame slots the loop reads (before our start point they were set by the earlier ctor body):
    wr(FRAME-108,0,4); wr(FRAME-104,0,4); wr(FRAME-100,0,4);  // the out-vector struct (begin=0 => skip free)
    wr(FRAME-88,0,4);   // name-callback ptr = 0  -> loop skips it
    wr(FRAME-72,0,4);   // icon-callback ptr = 0  -> loop skips it
    // registers
    emu.writeRegister("A6",FRAME);        // fp
    emu.writeRegister("A5",0x400d43a8L);  // item allocator (stubbed)
    emu.writeRegister("D4",0);            // arg to FUN_40022f6a (ignored by it)
    emu.writeRegister("SP",SP);
    emu.writeRegister("PC",0x4002a8feL);  // lea fp@(-108),a0 ; then jsr FUN_40022f6a ; then the loop
    wr(SP,RET,4);

    long bump=0x41b00000L;
    int adds=0; StringBuilder seq=new StringBuilder();
    int steps=0;
    String tag = edited ? "EDITED" : "AS-IS ";
    for(;steps<20000;steps++){
      long pc=emu.getExecutionAddress().getOffset();
      if(pc==0x4002a9e4L){ println(tag+": loop EXIT after "+steps+" steps"); break; }
      // log the machine type just after `moveal %a1@+,%a3`
      if(pc==0x4002a93cL){ seq.append(rd("A3")&0xffffffffL).append(" "); }
      // --- stubs: fake the call's return (pop ret, D0=scratch for allocs) ---
      if(pc==0x400d43a8L || pc==0x400d43b4L || pc==0x40142a18L || pc==0x40142a4eL
         || pc==0x400c4464L || pc==0x400c3c9aL || pc==0x4013779eL){
        long sp=rd("SP"); long ret=0;
        byte[] rb=emu.readMemory(toAddr(sp),4);
        for(int i=0;i<4;i++) ret=(ret<<8)|(rb[i]&0xff);
        if(pc==0x400c3c9aL){ adds++; }                 // <-- an item is ADDED to the list
        if(pc==0x400d43a8L){ emu.writeRegister("D0",bump); bump+=0x100; }  // alloc -> scratch
        emu.writeRegister("SP",sp+4);
        emu.writeRegister("PC",ret);
        continue;
      }
      if(!emu.step(monitor)){ println(tag+": FAULT at "+emu.getExecutionAddress()+" : "+emu.getLastError()); break; }
    }
    if(steps>=20000) println(tag+": step cap hit");
    println(tag+": items ADDED = "+adds+"   machine seq = ["+seq.toString().trim()+"]");
    emu.dispose();
    return adds;
  }

  public void run() throws Exception {
    println("=== poly_engine: MachineListView build+add loop, project bytes vs the enum-grow edit ===");
    int s=runOnce(false);
    int e=runOnce(true);
    println("=== RESULT: as-is added "+s+" machines, edited added "+e+" machines ===");
    println(e>s ? "=> the edit adds a machine in this constructor"
                : "=> the edit does not change the count here: the list comes from elsewhere, or is capped elsewhere");
  }
}
