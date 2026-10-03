// Audio-ISR probe 1 (gauge): run the audio ISR FUN_40077120 on zero-mapped state, log every call to
// FUN_40074af2 (block pointer, note, lane), and report the first fault.
//   ./scripts/ghidra_emu.sh EmuISR
// Runs in Ghidra's emulator only; nothing touches a device. @category dt_og_plus_plus
import ghidra.app.script.GhidraScript;
import ghidra.app.emulator.EmulatorHelper;
import ghidra.program.model.address.Address;

public class EmuISR extends GhidraScript {
  EmulatorHelper emu;
  void wr(long a,long v,int n) throws Exception { byte[] b=new byte[n]; for(int i=0;i<n;i++) b[n-1-i]=(byte)((v>>(8*i))&0xff); emu.writeMemory(toAddr(a),b); }
  long rd(long a,int n) throws Exception { byte[] b=emu.readMemory(toAddr(a),n); long v=0; for(int i=0;i<n;i++) v=(v<<8)|(b[i]&0xff); return v; }
  void map(long a,int n) throws Exception { emu.writeMemory(toAddr(a),new byte[n]); }
  public void run() throws Exception {
    emu=new EmulatorHelper(currentProgram);
    long ENTRY=0x40077120L, SP=0x4021d800L, RET=0x00000002L, AF2=0x40074af2L;
    // broad zero-mapped state (SRAM + high .bss engine regions)
    map(SP-0x800,0x1000);
    map(0x80000000L,0x10000);              // on-chip SRAM (64KB)
    map(0x40225000L,0x4000);               // some low .bss the allocator/globals use
    map(0x41930000L,0x40000);              // 0x4193xxxx / 0x41960xxx globals
    map(0x402d0000L,0x10000);              // 0x402dbxxx DMA window params + 0x402db3d0 tables
    map(0x402bb000L,0x9000);               // slice table
    map(0x4395c000L,0x36000);              // per-track structs 0x4395ddf4/df20/df48/df4c + .bss to end
    emu.writeRegister("SP",SP); emu.writeRegister("PC",ENTRY);
    wr(SP+0,RET,4);
    int af2calls=0; long lastFault=0; String err=null; int steps=0;
    StringBuilder log=new StringBuilder();
    for (int i=0;i<300000;i++){
      Address at=emu.getExecutionAddress(); long pc=at.getOffset();
      if (pc==RET){ steps=i; println("ISR RETURNED after "+i+" steps"); break; }
      if (pc==AF2){ af2calls++;
        long spv=emu.readRegister("SP").longValue()&0xffffffffL;
        long blk=rd(spv+4,4); long note=rd(spv+8,4); long lane=rd(spv+0x10,4);
        log.append(String.format("  AF2 call #%d @step %d: block=%08x note=%08x lane=%08x%n",af2calls,i,blk,note,lane));
      }
      if (!emu.step(monitor)){ lastFault=pc; err=emu.getLastError(); steps=i;
        println("FAULT after "+i+" steps at "+at+"  insn="+(getInstructionAt(at)!=null?getInstructionAt(at).toString():"?"));
        println("  err="+err); break; }
      steps=i;
    }
    println("FUN_40074af2 calls observed: "+af2calls);
    print(log.toString());
    emu.dispose();
  }
}
