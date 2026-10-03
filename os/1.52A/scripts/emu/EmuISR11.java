// Audio-ISR probe 11: does a CPU function copy the eDMA fetch buffer (0x800013a0) into the per-track
// buffers at 0x80001a18 ("a18")? Seed synthetic active voices and fill 0x800013a0 with a recognisable
// pattern (0xBB..). Run the render, SKIPPING the three op kinds the emulator cannot execute
// (undecodable words, which on the stock language include the mac.l (d16,An) form; nbcd and the other
// BCD ops, whose bcdAdjust p-code op is not implemented; trap), so control flow and memory copies
// survive. The accumulator math is wrong after a skip, but only memory writes are watched. If any a18
// word becomes 0xBB.., a CPU function read the fetch buffer into a18 -> log its PC.
//   ./scripts/ghidra_emu.sh 1.52A EmuISR11
// Runs in Ghidra's emulator only; nothing touches a device. @category dt_og_plus_plus
import ghidra.app.script.GhidraScript;
import ghidra.app.emulator.EmulatorHelper;
import ghidra.program.model.address.Address;
import java.util.*;
public class EmuISR11 extends GhidraScript {
  EmulatorHelper emu;
  void wr(long a,long v,int n) throws Exception { byte[] b=new byte[n]; for(int i=0;i<n;i++) b[n-1-i]=(byte)((v>>(8*i))&0xff); emu.writeMemory(toAddr(a),b); }
  long rd(long a,int n) throws Exception { byte[] b=emu.readMemory(toAddr(a),n); long v=0; for(int i=0;i<n;i++) v=(v<<8)|(b[i]&0xff); return v; }
  long reg(String r) throws Exception { return emu.readRegister(r).longValue()&0xffffffffL; }
  void map(long a,int n) throws Exception { emu.writeMemory(toAddr(a),new byte[n]); }
  // instruction length for a bad op we must skip
  int badLen(long pc) throws Exception {
    int w=(int)rd(pc,2);
    if ((w&0xf000)==0xa000 && ((w>>3)&7)==5) return 6;   // mac.l (d16,An) mode-5: opcode+ext+disp16
    if ((w&0xffc0)==0x4800) return 2;                    // nbcd
    return 2;                                            // trap / data-in-code: skip a word
  }
  public void run() throws Exception {
    emu=new EmulatorHelper(currentProgram);
    long ENTRY=0x40077120L, SP=0x4021d800L, RET=0x00000002L, A18=0x80001a18L, VB=0x8000edc4L, FETCH=0x800013a0L;
    map(SP-0x800,0x1000); map(0x80000000L,0x10000); map(0x40225000L,0x4000);
    map(0x41930000L,0x40000); map(0x402d0000L,0x10000); map(0x402bb000L,0x9000); map(0x4395c000L,0x36000);
    map(0x42180000L,0x40000); map(0x41a00000L,0x2000); map(0x40800000L,0x40000);
    for (int t=0;t<8;t++) wr(0x41960316L+t,3,1);
    for (int t: new int[]{2,3,7}){
      long base=0x40800000L+t*0x8000; long s=VB+t*0x5e;
      wr(s+0x00,base,4); wr(s+0x04,0,4); wr(s+0x14,0x2000,4); wr(s+0x18,base,4); wr(s+0x28,1,1);
      wr(0x8000ee20L+t*0x5e,0x10+t,1); wr(0x80001f28L+t*4,0x3c<<16,4);
    }
    // fill the eDMA fetch buffer with a recognizable pattern (the "fetched samples")
    for (long a=FETCH; a<FETCH+0x3000; a+=4) wr(a, 0xBB000000L | ((a-FETCH)&0xffff), 4);
    emu.writeRegister("SP",SP); emu.writeRegister("PC",ENTRY); wr(SP+0,RET,4);
    int nwords=8*0x20; long[] snap=new long[nwords]; for(int k=0;k<nwords;k++) snap[k]=rd(A18+k*4,4);
    LinkedHashMap<Long,String> hits=new LinkedHashMap<>();
    int i; long prevpc=-1; int samePc=0, skips=0; StringBuilder sb=new StringBuilder();
    for (i=0;i<1200000;i++){
      long pc=emu.getExecutionAddress().getOffset();
      if (pc==RET){ sb.append("RETURNED @"+i+"\n"); break; }
      for(int k=0;k<nwords;k++){ long c=rd(A18+k*4,4); if(c!=snap[k]){
        boolean smp=(c&0xFF000000L)==0xBB000000L;
        if(smp && !hits.containsKey(prevpc)) hits.put(prevpc, String.format("FETCH->a18+0x%x(trk%d)=%08x @i=%d",k*4,(k*4)/0x80,c,i));
        snap[k]=c; } }
      ghidra.program.model.listing.Instruction ins=getInstructionAt(toAddr(pc));
      String insn=ins!=null?ins.toString():"";
      if (insn.contains("(0x1e,A")) for(String ar:new String[]{"A0","A1","A2","A3"}) if(insn.contains(","+ar+")")) wr(reg(ar)+0x1e,0x80,2);
      // PRE-EMPT the bad ops (skip WITHOUT stepping, so emu.step never faults):
      String mn=ins!=null?ins.getMnemonicString().toLowerCase():"";
      boolean badCallother = mn.contains("nbcd")||mn.contains("abcd")||mn.contains("sbcd")||mn.contains("trap")||mn.contains("bcd");
      if (ins==null){ int L=badLen(pc); emu.writeRegister("PC",pc+L); skips++; if(skips<12) sb.append("  skip undecodable @"+Long.toHexString(pc)+" len"+L+" bytes="+Long.toHexString(rd(pc,2))+"\n"); prevpc=pc; continue; }
      if (badCallother){ emu.writeRegister("PC",pc+ins.getLength()); skips++; if(skips<12) sb.append("  skip callother "+mn+" @"+Long.toHexString(pc)+"\n"); prevpc=pc; continue; }
      if (pc==prevpc){ if(++samePc>60000){ sb.append("STUCK @"+Long.toHexString(pc)+"\n"); break; } } else samePc=0;
      prevpc=pc;
      if (!emu.step(monitor)){ sb.append("stop @"+Long.toHexString(pc)+"\n"); break; }
    }
    sb.append("steps="+i+" skips="+skips+" FETCH->a18 hits="+hits.size()+"\n");
    for (Map.Entry<Long,String> e: hits.entrySet()) sb.append(String.format("  PC=%08x  %s%n",e.getKey(),e.getValue()));
    print(sb.toString());
    emu.dispose();
  }
}
