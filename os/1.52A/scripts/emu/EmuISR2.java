// Audio-ISR probe 2: find where the ISR spins. Histogram the PCs of a FUN_40077120 run and report the
// hottest ones (the spin body is a hardware-ready wait that never completes in the emulator).
//   ./scripts/ghidra_emu.sh EmuISR2
// Runs in Ghidra's emulator only; nothing touches a device. @category dt_og_plus_plus
import ghidra.app.script.GhidraScript;
import ghidra.app.emulator.EmulatorHelper;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import java.util.*;

public class EmuISR2 extends GhidraScript {
  EmulatorHelper emu;
  void wr(long a,long v,int n) throws Exception { byte[] b=new byte[n]; for(int i=0;i<n;i++) b[n-1-i]=(byte)((v>>(8*i))&0xff); emu.writeMemory(toAddr(a),b); }
  void map(long a,int n) throws Exception { emu.writeMemory(toAddr(a),new byte[n]); }
  public void run() throws Exception {
    emu=new EmulatorHelper(currentProgram);
    long ENTRY=0x40077120L, SP=0x4021d800L, RET=0x00000002L;
    map(SP-0x800,0x1000); map(0x80000000L,0x10000); map(0x40225000L,0x4000);
    map(0x41930000L,0x40000); map(0x402d0000L,0x10000); map(0x402bb000L,0x9000); map(0x4395c000L,0x36000);
    emu.writeRegister("SP",SP); emu.writeRegister("PC",ENTRY); wr(SP+0,RET,4);
    HashMap<Long,Integer> hist=new HashMap<>();
    long firstDivergePC=0; int i;
    for (i=0;i<60000;i++){
      long pc=emu.getExecutionAddress().getOffset();
      if (pc==RET){ println("RETURNED @"+i); break; }
      hist.merge(pc,1,Integer::sum);
      if (!emu.step(monitor)){ println("FAULT @"+i+" pc="+Long.toHexString(pc)); break; }
    }
    println("steps="+i);
    // top PCs
    List<Map.Entry<Long,Integer>> es=new ArrayList<>(hist.entrySet());
    es.sort((a,b)->b.getValue()-a.getValue());
    println("=== hottest PCs (the spin body) ===");
    for (int k=0;k<Math.min(18,es.size());k++){
      long pc=es.get(k).getKey();
      Function f=getFunctionContaining(toAddr(pc));
      String fn=f!=null?f.getName():"?";
      String insn=getInstructionAt(toAddr(pc))!=null?getInstructionAt(toAddr(pc)).toString():"?";
      println(String.format("  %6d x  %08x  %-22s %s", es.get(k).getValue(), pc, fn, insn));
    }
    emu.dispose();
  }
}
