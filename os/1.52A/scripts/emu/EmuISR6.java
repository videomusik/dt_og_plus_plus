// Audio-ISR probe 6: inject a TRACK-7 note-on event into the ISR's event queue and run FUN_40077120.
// Watch whether FUN_40074af2 (slice select) is EVER called for track 7's block (0x80002a7a) or stays
// on blocks 0/1, and watch the OTHER 8-track loop (the FUN_40072544 family).
//   ./scripts/ghidra_emu.sh 1.52A EmuISR6
// Runs in Ghidra's emulator only; nothing touches a device. @category dt_og_plus_plus
import ghidra.app.script.GhidraScript;
import ghidra.app.emulator.EmulatorHelper;
import ghidra.program.model.address.Address;
import java.util.*;
public class EmuISR6 extends GhidraScript {
  EmulatorHelper emu;
  void wr(long a,long v,int n) throws Exception { byte[] b=new byte[n]; for(int i=0;i<n;i++) b[n-1-i]=(byte)((v>>(8*i))&0xff); emu.writeMemory(toAddr(a),b); }
  long rd(long a,int n) throws Exception { byte[] b=emu.readMemory(toAddr(a),n); long v=0; for(int i=0;i<n;i++) v=(v<<8)|(b[i]&0xff); return v; }
  long reg(String r) throws Exception { return emu.readRegister(r).longValue()&0xffffffffL; }
  void map(long a,int n) throws Exception { emu.writeMemory(toAddr(a),new byte[n]); }
  public void run() throws Exception {
    emu=new EmulatorHelper(currentProgram);
    long ENTRY=0x40077120L, SP=0x4021d800L, RET=0x00000002L, AF2=0x40074af2L;
    // ---- memory map (EmuISR5's set + queue region + node freelists) ----
    map(SP-0x800,0x1000); map(0x80000000L,0x10000); map(0x40225000L,0x4000);
    map(0x41930000L,0x40000); map(0x402d0000L,0x10000); map(0x402bb000L,0x9000); map(0x4395c000L,0x36000);
    map(0x42180000L,0x40000);       // node freelist 0x421850b0, queue head 0x421b7940, bucket/event free 0x421b7948/4c
    map(0x41a00000L,0x1000);        // scratch: our bucket + event
    // ---- machine types = SLICE(3) direct ----
    for (int t=0;t<8;t++) wr(0x41960316L+t,3,1);
    // ---- timing so the bucket is "due" ----
    wr(0x401d3820L,0x40,4);         // DAT_401d3820 time base (elapsed)
    wr(0x80001f4cL,0,4);            // word at 0x80001f4c = 0 -> due time = 0 + 0x40*2 = 0x80
    // ---- seed the event queue: one bucket, one track-7 note-on event ----
    long BUCKET=0x41a00000L, EVT=0x41a00100L;
    wr(0x421b7940L,BUCKET,4);       // queue head -> our bucket
    wr(BUCKET+0x00,0,4);            // [0] always-due flag = 0
    wr(BUCKET+0x04,0,4);            // [1] timestamp = 0  (0 - 0x80 < 0 => due)
    wr(BUCKET+0x08,EVT,4);          // [2] event-list head -> our event
    wr(BUCKET+0x10,0,4);            // [4] next bucket = null
    wr(EVT+0x00,0,4);              // [0] type: note/voice handler (else-branch, not 2/3/4/5)
    wr(EVT+0x04,1,4);              // [1] note-on
    wr(EVT+0x08,7,4);              // [2] TRACK = 7
    wr(EVT+0x0c,0x40,4);           // [3] priority (> the track's current priority at 0x4395ddf4[7] = 0)
    wr(EVT+0x18,0x3c,4);           // [6] note = 60
    wr(EVT+0x24,0x10080,4);        // [9] flags: 0x80 new-note/gain path (sets bit 7 of the ISR's per-event mask) + 0x10000 note-array; 0x8000 CLEAR skips voice allocator; bit0 CLEAR
    wr(EVT+0x48,0,4);              // [0x12] next event = null
    // skip the FUN_4007699e call: the ISR makes it unless word[0x800019b0] == word[0x800019ac] + 0x530
    wr(0x800019b0L,0x530,4);
    // ---- run ----
    emu.writeRegister("SP",SP); emu.writeRegister("PC",ENTRY); wr(SP+0,RET,4);
    // functions to optionally STUB (force immediate return) if they fault on bogus pointers:
    Map<Long,String> stub=new HashMap<>();
    // FUN_400754fe (render slot 1) falls through to the undecodable data-in-code tail 0x40075cfa in
    // the emulator (missing peripheral state). Stub it so we get PAST it to the 8-track loop.
    // Stubbing it loses nothing this probe needs: the FUN_40074af2 call for block 1 happens before it.
    stub.put(0x400754feL,"F754fe");
    long F76f82=0x40076f82L, F747d4=0x400747d4L, F72544=0x40072544L, F73004=0x40073004L, F489e=0x4007489e;
    int af2=0, mmf=0, m544=0; StringBuilder sb=new StringBuilder(); int i; int waitHits=0;
    long prevpc=-1; int samePc=0; boolean noteBranch=false;
    for (i=0;i<2000000;i++){
      long pc=emu.getExecutionAddress().getOffset();
      if (pc==RET){ sb.append("RETURNED @"+i+"\n"); break; }
      String insn=getInstructionAt(toAddr(pc))!=null?getInstructionAt(toAddr(pc)).toString():"";
      // codec-ready spin-breaker
      if (insn.contains("(0x1e,A")) {
        for (String ar: new String[]{"A0","A1","A2","A3"}) if (insn.contains(","+ar+")")) { wr(reg(ar)+0x1e,0x80,2); waitHits++; }
      }
      // stub: pop return addr, jump to it
      if (stub.containsKey(pc)) { long ra=rd(reg("SP"),4); emu.writeRegister("SP",reg("SP")+4); emu.writeRegister("PC",ra); emu.writeRegister("D0",0); continue; }
      if (pc==AF2){ af2++; long spv=reg("SP");
        sb.append(String.format("  AF2 #%d @%d: block=%08x note=%08x lane=%08x%n",af2,i,rd(spv+4,4),rd(spv+8,4),rd(spv+0x10,4))); }
      if (pc==F76f82){ mmf++; long spv=reg("SP"); sb.append(String.format("  F76f82(build mirror) #%d @%d: soundPtr=%08x track=%08x%n",mmf,i,rd(spv+4,4),rd(spv+8,4))); }
      if (pc==F72544){ m544++; long spv=reg("SP"); if(m544<=8) sb.append(String.format("  F72544(8trk) #%d @%d: block=%08x activeMask=%08x trk=%08x%n",m544,i,rd(spv+4,4),rd(spv+0xc,4),rd(spv+0x10,4))); }
      if (pc==0x40077946L||pc==0x40077950L) noteBranch=true; // rough marker in note-on region (info only)
      if (pc==prevpc){ if(++samePc>50000){ sb.append("STUCK at "+Long.toHexString(pc)+" insn="+insn+"\n"); break; } } else samePc=0;
      prevpc=pc;
      if (!emu.step(monitor)){ sb.append("FAULT @"+i+" pc="+Long.toHexString(pc)+" insn="+insn+"\n"); break; }
    }
    sb.append(String.format("steps=%d  AF2=%d  F76f82=%d  F72544=%d  waitWrites=%d%n",i,af2,mmf,m544,waitHits));
    print(sb.toString());
    emu.dispose();
  }
}
