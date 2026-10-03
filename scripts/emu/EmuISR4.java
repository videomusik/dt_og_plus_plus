// Audio-ISR probe 4: as probe 3, but with the machine type written straight into the ISR's own
// per-track table (0x41960316) and a PC histogram of what remains hot.
//   ./scripts/ghidra_emu.sh EmuISR4
// Runs in Ghidra's emulator only; nothing touches a device. @category dt_og_plus_plus
import ghidra.app.script.GhidraScript;
import ghidra.app.emulator.EmulatorHelper;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import java.util.*;
public class EmuISR4 extends GhidraScript {
  EmulatorHelper emu;
  void wr(long a,long v,int n) throws Exception { byte[] b=new byte[n]; for(int i=0;i<n;i++) b[n-1-i]=(byte)((v>>(8*i))&0xff); emu.writeMemory(toAddr(a),b); }
  long rd(long a,int n) throws Exception { byte[] b=emu.readMemory(toAddr(a),n); long v=0; for(int i=0;i<n;i++) v=(v<<8)|(b[i]&0xff); return v; }
  void map(long a,int n) throws Exception { emu.writeMemory(toAddr(a),new byte[n]); }
  public void run() throws Exception {
    emu=new EmulatorHelper(currentProgram);
    long ENTRY=0x40077120L, SP=0x4021d800L, RET=0x00000002L, AF2=0x40074af2L;
    map(SP-0x800,0x1000); map(0x80000000L,0x10000); map(0x40225000L,0x4000);
    map(0x41930000L,0x40000); map(0x402d0000L,0x10000); map(0x402bb000L,0x9000); map(0x4395c000L,0x36000);
    for (int t=0;t<8;t++) wr(0x41960316L+t,3,1);          // DAT_41960316[t] = 3 (SLICE) DIRECT
    wr(0x80001200L,0x80003000L,4); wr(0x8000301eL,0x0080,2);
    wr(0x80001204L,0x80003100L,4); wr(0x8000311eL,0x0080,2);
    emu.writeRegister("SP",SP); emu.writeRegister("PC",ENTRY); wr(SP+0,RET,4);
    HashMap<Long,Integer> hist=new HashMap<>();
    int af2=0; StringBuilder sb=new StringBuilder(); int i;
    for (i=0;i<200000;i++){
      long pc=emu.getExecutionAddress().getOffset();
      if (pc==RET){ println("RETURNED @"+i); break; }
      hist.merge(pc,1,Integer::sum);
      if (pc==AF2){ af2++; long spv=emu.readRegister("SP").longValue()&0xffffffffL;
        sb.append(String.format("  AF2 #%d @%d: block=%08x note=%08x lane=%08x%n",af2,i,rd(spv+4,4),rd(spv+8,4),rd(spv+0x10,4))); }
      if (!emu.step(monitor)){ println("FAULT @"+i+" pc="+Long.toHexString(pc)); break; }
    }
    println("steps="+i+"  FUN_40074af2 calls="+af2); print(sb.toString());
    List<Map.Entry<Long,Integer>> es=new ArrayList<>(hist.entrySet());
    es.sort((a,b)->b.getValue()-a.getValue());
    println("=== hottest PCs ===");
    for (int k=0;k<Math.min(8,es.size());k++){ long pc=es.get(k).getKey();
      Function f=getFunctionContaining(toAddr(pc));
      println(String.format("  %6d x %08x %-16s %s",es.get(k).getValue(),pc,f!=null?f.getName():"?",
        getInstructionAt(toAddr(pc))!=null?getInstructionAt(toAddr(pc)).toString():"?")); }
    emu.dispose();
  }
}
