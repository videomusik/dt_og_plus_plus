// Full run of FUN_40074af2 (the slice-window function) with seeded memory: proves argument seeding
// and memory mapping, and computes one slice (select=3, grid 16 -> index 2).
//   ./scripts/ghidra_emu.sh EmuAf2
// Runs in Ghidra's emulator only; nothing touches a device. @category dt_og_plus_plus
import ghidra.app.script.GhidraScript;
import ghidra.app.emulator.EmulatorHelper;
import ghidra.program.model.address.Address;
import java.math.BigInteger;

public class EmuAf2 extends GhidraScript {
  EmulatorHelper emu;
  void wr(long addr, long val, int size) throws Exception {   // big-endian write
    byte[] b = new byte[size];
    for (int i=0;i<size;i++) b[size-1-i] = (byte)((val>>(8*i))&0xff);
    emu.writeMemory(toAddr(addr), b);
  }
  void map(long addr, int n) throws Exception { emu.writeMemory(toAddr(addr), new byte[n]); } // zero-fill/map

  public void run() throws Exception {
    emu = new EmulatorHelper(currentProgram);
    long ENTRY=0x40074af2L, SP=0x4021d800L, RET=0x00000002L;  // RET sentinel (odd->never matches code)
    long BLOCK=0x4021c000L;   // param_1 scratch block
    // --- map scratch regions the function touches ---
    map(SP-0x400, 0x800);            // stack scratch
    map(BLOCK, 0x100);               // param block
    map(0x8000ee00, 0x100);          // DAT_8000ee20 per-voice state (sample-slot byte)
    map(0x402bb000, 0x8000);         // slice-point table 0x402bb3b0 + headroom
    // seed the slice table with a recognizable pattern: entry[i] = 0x1000+i (so start/end are legible)
    for (int i=0;i<0x2000;i++) wr(0x402bb3b0 + i*4L, 0x1000+i, 4);
    // --- seed param block: reverse(+2)=0, select(+8)=3, length(+0xa)=1, grid(+0xc)=2 (count=16) ---
    wr(BLOCK+2,0,1); wr(BLOCK+8,3,1); wr(BLOCK+0xa,1,1); wr(BLOCK+0xc,2,1);
    // sample-slot byte at DAT_8000ee20[lane*0x5e], lane=0 -> 0x8000ee20 = 5 (must be <0x80)
    wr(0x8000ee20,5,1);
    // --- registers + stack args (SP->RET, args at +4/+8/+c/+10) ---
    emu.writeRegister("SP", SP);
    emu.writeRegister("PC", ENTRY);
    wr(SP+0x0, RET, 4);
    wr(SP+0x4, BLOCK, 4);            // param_1 = block
    wr(SP+0x8, 60L<<16, 4);          // param_2 = note<<16 (note 60)
    wr(SP+0xc, 0x00001234, 4);       // param_3 = defaultEnd sentinel
    wr(SP+0x10, 0, 4);               // param_4 = lane 0
    println("select=3 grid=2(count16) slot=5  => expect idx=min(3,16)-1=2");
    // --- run to the sentinel return ---
    for (int i=0;i<400;i++){
      Address at=emu.getExecutionAddress();
      if (at.getOffset()==RET){ println("RETURNED after "+i+" steps"); break; }
      if (!emu.step(monitor)){ println("FAULT at "+at+" : "+emu.getLastError()); break; }
    }
    println(String.format("result: D0(end)=%08x D1=%08x D2(slice-ish)=%08x  A0=%08x A1=%08x",
      emu.readRegister("D0").longValue()&0xffffffffL,
      emu.readRegister("D1").longValue()&0xffffffffL,
      emu.readRegister("D2").longValue()&0xffffffffL,
      emu.readRegister("A0").longValue()&0xffffffffL,
      emu.readRegister("A1").longValue()&0xffffffffL));
    emu.dispose();
  }
}
