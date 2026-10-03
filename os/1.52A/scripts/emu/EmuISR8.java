// Audio-ISR probe 8: voice-allocator capture over several ticks. Note-on for track 7 WITH the
// voice-start bit 0x8000 set (the allocation path FUN_400ddb12/FUN_400ddc98 that schedules a voice for
// a later tick). The post-slot DSP chain is stubbed so each tick completes. Watch BOTH render slots'
// Select and sample-slot bytes every step and log any writer PC; log every FUN_40074af2 call.
// Over 14 ticks: does track 7's data (marker 0x77 / sample slot 0x37) ever land in a slot?
//   ./scripts/ghidra_emu.sh EmuISR8
// Runs in Ghidra's emulator only; nothing touches a device. @category dt_og_plus_plus
import ghidra.app.script.GhidraScript;
import ghidra.app.emulator.EmulatorHelper;
import ghidra.program.model.address.Address;
import java.util.*;
public class EmuISR8 extends GhidraScript {
  EmulatorHelper emu;
  void wr(long a,long v,int n) throws Exception { byte[] b=new byte[n]; for(int i=0;i<n;i++) b[n-1-i]=(byte)((v>>(8*i))&0xff); emu.writeMemory(toAddr(a),b); }
  long rd(long a,int n) throws Exception { byte[] b=emu.readMemory(toAddr(a),n); long v=0; for(int i=0;i<n;i++) v=(v<<8)|(b[i]&0xff); return v; }
  long reg(String r) throws Exception { return emu.readRegister(r).longValue()&0xffffffffL; }
  void map(long a,int n) throws Exception { emu.writeMemory(toAddr(a),new byte[n]); }
  public void run() throws Exception {
    emu=new EmulatorHelper(currentProgram);
    long ENTRY=0x40077120L, SP=0x4021d800L, RET=0x00000002L, AF2=0x40074af2L, ENG=0x80002760L;
    map(SP-0x800,0x1000); map(0x80000000L,0x10000); map(0x40225000L,0x4000);
    map(0x41930000L,0x40000); map(0x402d0000L,0x10000); map(0x402bb000L,0x9000); map(0x4395c000L,0x36000);
    map(0x42180000L,0x40000); map(0x41a00000L,0x20000); map(0x41938000L,0x8000);
    // --- seed the alloc free-lists so the voice-start path doesn't spin (FUN_400dd9ac spins if empty) ---
    long bkt=0x41a08000L; wr(0x421b7948L,bkt,4);           // bucket free-list head; link at +0x10
    for(int j=0;j<48;j++) wr(bkt+j*0x40+0x10, (j<47)?bkt+(j+1)*0x40:0, 4);
    long nod=0x41a0c000L; wr(0x421850b0L,nod,4);           // node free-list (FUN_400d0044); link at +0x2c (word 0xb)
    for(int j=0;j<48;j++) wr(nod+j*0x50+0x2c, (j<47)?nod+(j+1)*0x50:0, 4);
    long evf=0x41a14000L; wr(0x421b794cL,evf,4);           // event free-list (FUN_400ddb48); link at +0x48 (word 0x12)
    for(int j=0;j<48;j++) wr(evf+j*0x50+0x48, (j<47)?evf+(j+1)*0x50:0, 4);
    for (int t=0;t<8;t++) wr(0x41960316L+t,3,1);
    for (int t=0;t<8;t++){ int mk=0x11*(t+1)&0x7f;
      wr(0x80001502L+t*0x6a+0x2a, mk, 2); wr(0x80002b50L+t*0xd4+0x54, ((long)mk)<<8, 4); wr(0x8000ee20L+t*0x5e, 0x30+t, 1); }
    // queue: track-7 note-on WITH voice-start bit 0x8000
    long BUCKET=0x41a00000L, EVT=0x41a00100L;
    wr(0x421b7940L,BUCKET,4); wr(BUCKET+0,0,4); wr(BUCKET+4,0,4); wr(BUCKET+8,EVT,4); wr(BUCKET+0x10,0,4);
    wr(EVT+0,0,4); wr(EVT+4,1,4); wr(EVT+8,7,4); wr(EVT+0xc,0x40,4); wr(EVT+0x18,0x3c,4);
    wr(EVT+0x24,0x18080,4);                 // 0x8000 voice-start + 0x80 new-note + 0x10000 note-array
    wr(EVT+0x34,7,4); wr(EVT+0x38,0,4); wr(EVT+0x3c,7,4);  // event[0xd]=length-idx, [0xe]=0, [0xf]=7 (used by alloc path)
    wr(EVT+0x48,0,4); wr(0x800019b0L,0x530,4);
    // watched slot addresses (both slots' Select @block+8 and lane sample-slot)
    long[] W = { ENG+0x34+8, ENG+0x9e+8, 0x8000ee20L, 0x8000ee20L+0x5e };
    String[] WN = {"slot0.Sel","slot1.Sel","lane0.samp","lane1.samp"};
    long[] stubs = {0x400754feL,0x40072178L,0x40072e68L,0x40073004L,0x40072544L,0x40071920L,0x400713c0L,0x4007239cL,0x40072178L};
    Set<Long> stub=new HashSet<>(); for(long s:stubs) stub.add(s);
    StringBuilder sb=new StringBuilder();
    long[] prevW=new long[W.length]; for(int k=0;k<W.length;k++) prevW[k]=rd(W[k],1);
    for (int tick=0; tick<14; tick++){
      emu.writeRegister("SP",SP); emu.writeRegister("PC",ENTRY); wr(SP+0,RET,4);
      int af2=0; int i; long prevpc=-1; int samePc=0;
      for (i=0;i<400000;i++){
        long pc=emu.getExecutionAddress().getOffset();
        if (pc==RET) break;
        for(int k=0;k<W.length;k++){ long c=rd(W[k],1); if(c!=prevW[k]){ sb.append(String.format("  T%d %s %02x->%02x @pc=%x%s%n",tick,WN[k],prevW[k],c,prevpc,(c==0x77||c==0x37)?"  <== TRACK7!":"")); prevW[k]=c; } }
        String insn=getInstructionAt(toAddr(pc))!=null?getInstructionAt(toAddr(pc)).toString():"";
        if (insn.contains("(0x1e,A")) for(String ar:new String[]{"A0","A1","A2","A3"}) if(insn.contains(","+ar+")")) wr(reg(ar)+0x1e,0x80,2);
        if (stub.contains(pc)){ long ra=rd(reg("SP"),4); emu.writeRegister("SP",reg("SP")+4); emu.writeRegister("PC",ra); emu.writeRegister("D0",0); continue; }
        if (pc==AF2){ af2++; long spv=reg("SP"); long blk=rd(spv+4,4); long ln=rd(spv+0x10,4);
          sb.append(String.format("  T%d AF2#%d blk=%08x Sel=%02x lane=%x samp=%02x%s%n",tick,af2,blk,rd(blk+8,1),ln,rd(0x8000ee20L+ln*0x5e,1),(rd(blk+8,1)==0x77)?" <==TRACK7":"")); }
        if (pc==prevpc){ if(++samePc>40000){ sb.append(String.format("  T%d STUCK @%x%n",tick,pc)); break; } } else samePc=0;
        prevpc=pc;
        if (!emu.step(monitor)){ sb.append(String.format("  T%d FAULT @%x%n",tick,pc)); break; }
      }
    }
    sb.append("final slot state: ");
    for(int k=0;k<W.length;k++) sb.append(String.format("%s=%02x ",WN[k],rd(W[k],1)));
    print(sb.toString());
    emu.dispose();
  }
}
