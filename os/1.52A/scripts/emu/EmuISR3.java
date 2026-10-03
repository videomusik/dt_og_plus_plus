// Audio-ISR probe 3: run the ISR with every track's machine type set to SLICE (via the ISR's copy
// source) and the hardware-ready waits defeated; log each FUN_40074af2 call's block/note/lane.
//   ./scripts/ghidra_emu.sh 1.52A EmuISR3
// Runs in Ghidra's emulator only; nothing touches a device. @category dt_og_plus_plus
import ghidra.app.script.GhidraScript;
import ghidra.app.emulator.EmulatorHelper;
import ghidra.program.model.address.Address;

public class EmuISR3 extends GhidraScript {
  EmulatorHelper emu;
  void wr(long a,long v,int n) throws Exception { byte[] b=new byte[n]; for(int i=0;i<n;i++) b[n-1-i]=(byte)((v>>(8*i))&0xff); emu.writeMemory(toAddr(a),b); }
  long rd(long a,int n) throws Exception { byte[] b=emu.readMemory(toAddr(a),n); long v=0; for(int i=0;i<n;i++) v=(v<<8)|(b[i]&0xff); return v; }
  void map(long a,int n) throws Exception { emu.writeMemory(toAddr(a),new byte[n]); }
  public void run() throws Exception {
    emu=new EmulatorHelper(currentProgram);
    long ENTRY=0x40077120L, SP=0x4021d800L, RET=0x00000002L, AF2=0x40074af2L;
    map(SP-0x800,0x1000); map(0x80000000L,0x10000); map(0x40225000L,0x4000);
    map(0x41930000L,0x40000); map(0x402d0000L,0x10000); map(0x402bb000L,0x9000); map(0x4395c000L,0x36000);
    // machine type = 3 (SLICE) for all 8 tracks (source 0x800018bc that the ISR copies to DAT_41960316)
    for (int t=0;t<8;t++) wr(0x800018bcL+t,3,1);
    // defeat FUN_400754fe / FUN_40074e84 hardware-ready waits: point the buffer descriptors at scratch
    // and set the ready bit (0x80) at +0x1e
    wr(0x80001200L,0x80003000L,4); wr(0x8000301eL,0x0080,2);   // _DAT_80001200 -> buf, ready
    wr(0x80001204L,0x80003100L,4); wr(0x8000311eL,0x0080,2);   // _DAT_80001204 -> buf, ready
    // also the DAT_402db3b1/b2/b3 "wait armed" flags -> 0 so those waits are skipped
    wr(0x402db3b0L,0,4);
    emu.writeRegister("SP",SP); emu.writeRegister("PC",ENTRY); wr(SP+0,RET,4);
    int af2=0; StringBuilder sb=new StringBuilder(); int i;
    for (i=0;i<400000;i++){
      long pc=emu.getExecutionAddress().getOffset();
      if (pc==RET){ println("RETURNED @"+i); break; }
      if (pc==AF2){ af2++; long spv=emu.readRegister("SP").longValue()&0xffffffffL;
        sb.append(String.format("  AF2 #%d @%d: block=%08x note=%08x lane=%08x%n",af2,i,rd(spv+4,4),rd(spv+8,4),rd(spv+0x10,4))); }
      if (!emu.step(monitor)){ println("FAULT @"+i+" pc="+Long.toHexString(pc)+" insn="+(getInstructionAt(toAddr(pc))!=null?getInstructionAt(toAddr(pc)).toString():"?")); break; }
    }
    println("steps="+i+"  FUN_40074af2 calls="+af2);
    print(sb.toString());
    emu.dispose();
  }
}
