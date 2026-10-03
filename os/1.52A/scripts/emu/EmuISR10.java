// Audio-ISR probe 10: SYNTHETIC ACTIVE VOICES. Does a CPU function read sample RAM into the per-track
// buffers at 0x80001a18 ("a18"), or is the fill done by DMA?
// Seed tracks 2, 3, 7 as ACTIVE voices whose per-voice struct points at seeded "sample RAM" filled
// with a recognisable per-track pattern (0xAA0002xx / 03 / 07). Run the render (stub only the
// functions that fault). Watch a18: if any word becomes the sample pattern, a CPU reader filled it ->
// log the PC. If a18 only ever clears despite active voices, the fill is eDMA (hardware), so the
// sample read is NOT CPU code.
//   ./scripts/ghidra_emu.sh EmuISR10
// Runs in Ghidra's emulator only; nothing touches a device. @category dt_og_plus_plus
import ghidra.app.script.GhidraScript;
import ghidra.app.emulator.EmulatorHelper;
import ghidra.program.model.address.Address;
import java.util.*;
public class EmuISR10 extends GhidraScript {
  EmulatorHelper emu;
  void wr(long a,long v,int n) throws Exception { byte[] b=new byte[n]; for(int i=0;i<n;i++) b[n-1-i]=(byte)((v>>(8*i))&0xff); emu.writeMemory(toAddr(a),b); }
  long rd(long a,int n) throws Exception { byte[] b=emu.readMemory(toAddr(a),n); long v=0; for(int i=0;i<n;i++) v=(v<<8)|(b[i]&0xff); return v; }
  long reg(String r) throws Exception { return emu.readRegister(r).longValue()&0xffffffffL; }
  void map(long a,int n) throws Exception { emu.writeMemory(toAddr(a),new byte[n]); }
  public void run() throws Exception {
    emu=new EmulatorHelper(currentProgram);
    long ENTRY=0x40077120L, SP=0x4021d800L, RET=0x00000002L, A18=0x80001a18L, VB=0x8000edc4L;
    map(SP-0x800,0x1000); map(0x80000000L,0x10000); map(0x40225000L,0x4000);
    map(0x41930000L,0x40000); map(0x402d0000L,0x10000); map(0x402bb000L,0x9000); map(0x4395c000L,0x36000);
    map(0x42180000L,0x40000); map(0x41a00000L,0x2000);
    map(0x40800000L,0x40000);   // seeded "sample RAM"
    for (int t=0;t<8;t++) wr(0x41960316L+t,3,1);
    int[] tracks={2,3,7};
    for (int t: tracks){
      long base=0x40800000L + t*0x8000;
      for (long a=base; a<base+0x4000; a+=4) wr(a, 0xAA000000L | (t<<8) | ((a-base)&0xff), 4); // recognizable, encodes track
      long s=VB + t*0x5e;
      wr(s+0x00, base, 4);        // edc4 sample base
      wr(s+0x04, 0, 4);           // edc8 position
      wr(s+0x14, 0x2000, 4);      // edd8 length
      wr(s+0x18, base, 4);        // eddc sample ptr
      wr(s+0x28, 1, 1);           // edec active flag
      wr(0x8000ee20L + t*0x5e, 0x10+t, 1);   // sample slot marker (<0x80)
      wr(0x80001f28L + t*4, 0x3c<<16, 4);    // note
    }
    emu.writeRegister("SP",SP); emu.writeRegister("PC",ENTRY); wr(SP+0,RET,4);
    Set<Long> stub=new HashSet<>(Arrays.asList(0x400754feL,0x40072178L,0x40072e68L));
    int nwords=8*0x20; long[] snap=new long[nwords];
    for(int k=0;k<nwords;k++) snap[k]=rd(A18+k*4,4);
    LinkedHashMap<Long,String> hits=new LinkedHashMap<>();
    StringBuilder sb=new StringBuilder(); int i; long prevpc=-1; int samePc=0; int changes=0;
    for (i=0;i<600000;i++){
      long pc=emu.getExecutionAddress().getOffset();
      if (pc==RET){ sb.append("RETURNED @"+i+"\n"); break; }
      for(int k=0;k<nwords;k++){ long c=rd(A18+k*4,4); if(c!=snap[k]){ changes++;
        boolean sample = (c & 0xFF000000L)==0xAA000000L;      // our sample pattern reached a18!
        String key = (sample?"S":"w")+Long.toHexString(prevpc);
        if(!hits.containsKey(prevpc) || sample){ hits.put(prevpc, String.format("%s a18+0x%x(trk%d) =%08x @i=%d%s",
          sample?"SAMPLE->":"write",k*4,(k*4)/0x80,c,i, sample?"  <=== CPU READ SAMPLE RAM":"")); }
        snap[k]=c; } }
      String insn=getInstructionAt(toAddr(pc))!=null?getInstructionAt(toAddr(pc)).toString():"";
      if (insn.contains("(0x1e,A")) for(String ar:new String[]{"A0","A1","A2","A3"}) if(insn.contains(","+ar+")")) wr(reg(ar)+0x1e,0x80,2);
      if (stub.contains(pc)){ long ra=rd(reg("SP"),4); emu.writeRegister("SP",reg("SP")+4); emu.writeRegister("PC",ra); emu.writeRegister("D0",0); continue; }
      if (pc==prevpc){ if(++samePc>40000){ sb.append("STUCK @"+Long.toHexString(pc)+"\n"); break; } } else samePc=0;
      prevpc=pc;
      if (!emu.step(monitor)){ sb.append("FAULT @"+Long.toHexString(pc)+" i="+i+"\n"); break; }
    }
    sb.append("a18 changes="+changes+"  distinct-writer entries="+hits.size()+"\n");
    for (Map.Entry<Long,String> e: hits.entrySet()) sb.append(String.format("  PC=%08x  %s%n", e.getKey(), e.getValue()));
    print(sb.toString());
    emu.dispose();
  }
}
