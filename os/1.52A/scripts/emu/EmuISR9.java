// Audio-ISR probe 9: WHO FILLS the 8 per-track audio buffers at 0x80001a18 (0x80 per track)? Run the
// ISR (idle), watch every write into that region and log the distinct writer PCs. A positive
// (pc, addr) pair is direct evidence of who touches the audio buffers.
//   ./scripts/ghidra_emu.sh 1.52A EmuISR9
// Runs in Ghidra's emulator only; nothing touches a device. @category dt_og_plus_plus
import ghidra.app.script.GhidraScript;
import ghidra.app.emulator.EmulatorHelper;
import ghidra.program.model.address.Address;
import java.util.*;
public class EmuISR9 extends GhidraScript {
  EmulatorHelper emu;
  void wr(long a,long v,int n) throws Exception { byte[] b=new byte[n]; for(int i=0;i<n;i++) b[n-1-i]=(byte)((v>>(8*i))&0xff); emu.writeMemory(toAddr(a),b); }
  long rd(long a,int n) throws Exception { byte[] b=emu.readMemory(toAddr(a),n); long v=0; for(int i=0;i<n;i++) v=(v<<8)|(b[i]&0xff); return v; }
  long reg(String r) throws Exception { return emu.readRegister(r).longValue()&0xffffffffL; }
  void map(long a,int n) throws Exception { emu.writeMemory(toAddr(a),new byte[n]); }
  public void run() throws Exception {
    emu=new EmulatorHelper(currentProgram);
    long ENTRY=0x40077120L, SP=0x4021d800L, RET=0x00000002L;
    long A18=0x80001a18L, A18END=A18+8*0x80;   // 8 per-track buffers
    map(SP-0x800,0x1000); map(0x80000000L,0x10000); map(0x40225000L,0x4000);
    map(0x41930000L,0x40000); map(0x402d0000L,0x10000); map(0x402bb000L,0x9000); map(0x4395c000L,0x36000);
    map(0x42180000L,0x40000); map(0x41a00000L,0x2000);
    for (int t=0;t<8;t++) wr(0x41960316L+t,3,1);
    // sentinel-fill a18 so any real write is detectable as a change
    for (long a=A18; a<A18END; a+=4) wr(a, 0x5a5a5a5aL, 4);
    long[] snap = new long[(int)((A18END-A18)/4)];
    for (int k=0;k<snap.length;k++) snap[k]=rd(A18+k*4,4);
    emu.writeRegister("SP",SP); emu.writeRegister("PC",ENTRY); wr(SP+0,RET,4);
    // stub ONLY the functions that fault/undecodable, so we reach the a18-writing DSP family:
    Set<Long> stub=new HashSet<>(Arrays.asList(0x400754feL,0x40072178L,0x40072e68L));
    // (name, first-seen pc) for each distinct writer PC that changes an a18 word
    LinkedHashMap<Long,String> writers=new LinkedHashMap<>();
    StringBuilder sb=new StringBuilder(); int i; long prevpc=-1; int samePc=0; int changes=0;
    for (i=0;i<600000;i++){
      long pc=emu.getExecutionAddress().getOffset();
      if (pc==RET){ sb.append("RETURNED @"+i+"\n"); break; }
      // detect a18 changes attributable to the PREVIOUS instruction
      for (int k=0;k<snap.length;k++){ long c=rd(A18+k*4,4); if(c!=snap[k]){
        changes++;
        if(!writers.containsKey(prevpc)){ writers.put(prevpc, String.format("first a18 write: addr=%08x (=a18+0x%x, track %d) val=%08x->%08x @i=%d",A18+k*4,k*4,(k*4)/0x80,snap[k],c,i)); }
        snap[k]=c; } }
      String insn=getInstructionAt(toAddr(pc))!=null?getInstructionAt(toAddr(pc)).toString():"";
      if (insn.contains("(0x1e,A")) for(String ar:new String[]{"A0","A1","A2","A3"}) if(insn.contains(","+ar+")")) wr(reg(ar)+0x1e,0x80,2);
      if (stub.contains(pc)){ long ra=rd(reg("SP"),4); emu.writeRegister("SP",reg("SP")+4); emu.writeRegister("PC",ra); emu.writeRegister("D0",0); continue; }
      if (pc==prevpc){ if(++samePc>40000){ sb.append("STUCK @"+Long.toHexString(pc)+"\n"); break; } } else samePc=0;
      prevpc=pc;
      if (!emu.step(monitor)){ sb.append("FAULT @"+Long.toHexString(pc)+" i="+i+"\n"); break; }
    }
    sb.append("total a18 word-changes="+changes+"  distinct writer PCs="+writers.size()+"\n");
    for (Map.Entry<Long,String> e: writers.entrySet())
      sb.append(String.format("  writer PC=%08x  %s%n", e.getKey(), e.getValue()));
    print(sb.toString());
    emu.dispose();
  }
}
