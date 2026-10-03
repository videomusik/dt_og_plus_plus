// Audio-ISR probe 5: a full ISR run that defeats the codec-ready waits dynamically (sets the ready bit
// whenever a `(0x1e,An)` poll comes up) and logs every FUN_40074af2 call's block.
//   ./scripts/ghidra_emu.sh 1.52A EmuISR5
// Runs in Ghidra's emulator only; nothing touches a device. @category dt_og_plus_plus
import ghidra.app.script.GhidraScript;
import ghidra.app.emulator.EmulatorHelper;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import java.util.*;
public class EmuISR5 extends GhidraScript {
  EmulatorHelper emu;
  void wr(long a,long v,int n) throws Exception { byte[] b=new byte[n]; for(int i=0;i<n;i++) b[n-1-i]=(byte)((v>>(8*i))&0xff); emu.writeMemory(toAddr(a),b); }
  long rd(long a,int n) throws Exception { byte[] b=emu.readMemory(toAddr(a),n); long v=0; for(int i=0;i<n;i++) v=(v<<8)|(b[i]&0xff); return v; }
  long reg(String r) throws Exception { return emu.readRegister(r).longValue()&0xffffffffL; }
  void map(long a,int n) throws Exception { emu.writeMemory(toAddr(a),new byte[n]); }
  public void run() throws Exception {
    emu=new EmulatorHelper(currentProgram);
    long ENTRY=0x40077120L, SP=0x4021d800L, RET=0x00000002L, AF2=0x40074af2L;
    map(SP-0x800,0x1000); map(0x80000000L,0x10000); map(0x40225000L,0x4000);
    map(0x41930000L,0x40000); map(0x402d0000L,0x10000); map(0x402bb000L,0x9000); map(0x4395c000L,0x36000);
    for (int t=0;t<8;t++) wr(0x41960316L+t,3,1);
    emu.writeRegister("SP",SP); emu.writeRegister("PC",ENTRY); wr(SP+0,RET,4);
    int af2=0; StringBuilder sb=new StringBuilder(); int i; int waitHits=0;
    Set<Long> waitReads=new HashSet<>();  // detected "move.w (0x1e,Ax),D0 ; andi 0x80 ; beq self" loops
    long prevpc=-1; int samePc=0;
    for (i=0;i<600000;i++){
      long pc=emu.getExecutionAddress().getOffset();
      if (pc==RET){ println("RETURNED @"+i); break; }
      // generic spin-breaker: if we read *(Ax+0x1e)&0x80 in a tight backward-branch loop, set the bit
      String insn=getInstructionAt(toAddr(pc))!=null?getInstructionAt(toAddr(pc)).toString():"";
      if (insn.contains("(0x1e,A")) {
        // figure out which A reg and set its +0x1e ready bit
        for (String ar: new String[]{"A0","A1","A2","A3"}) if (insn.contains(","+ar+")")) { wr(reg(ar)+0x1e,0x80,2); waitHits++; }
      }
      if (pc==AF2){ af2++; long spv=reg("SP");
        sb.append(String.format("  AF2 #%d @%d: block=%08x note=%08x lane=%08x%n",af2,i,rd(spv+4,4),rd(spv+8,4),rd(spv+0x10,4))); }
      // hard stop if truly stuck (same pc 5000x)
      if (pc==prevpc){ if(++samePc>20000){ println("STUCK at "+Long.toHexString(pc)+" insn="+insn); break; } } else samePc=0;
      prevpc=pc;
      if (!emu.step(monitor)){ println("FAULT @"+i+" pc="+Long.toHexString(pc)+" insn="+insn); break; }
    }
    println("steps="+i+"  AF2 calls="+af2+"  waitBitWrites="+waitHits);
    print(sb.toString());
    emu.dispose();
  }
}
