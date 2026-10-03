// Ghidra p-code emulator smoke test: single-step FUN_40074af2 (the slice-window function: integer
// code, MVS and a memory read, no EMAC) for 40 steps and print the registers.
// Checks that the emulator executes a program imported with the ColdFire-EMAC language.
//   ./scripts/ghidra_emu.sh EmuTest
// Runs in Ghidra's emulator only; nothing touches a device. @category dt_og_plus_plus
import ghidra.app.script.GhidraScript;
import ghidra.app.emulator.EmulatorHelper;
import ghidra.program.model.address.Address;
import java.math.BigInteger;

public class EmuTest extends GhidraScript {
  public void run() throws Exception {
    EmulatorHelper emu = new EmulatorHelper(currentProgram);
    long ENTRY = 0x40074af2L;
    long SP    = 0x4021d000L;   // scratch stack inside the loaded block
    Address entry = toAddr(ENTRY);
    String pc = emu.getPCRegister().getName();
    String sp = emu.getStackPointerRegister().getName();
    emu.writeRegister(pc, ENTRY);
    emu.writeRegister(sp, SP);
    // intended as plausible values for the 4 args. NOTE: SP+28,32,36,40 is where the function reads its
    // args only AFTER its prologue (lea -0x18,SP); at the entry, where this starts, callers leave them
    // at SP+4..+0x10 (notes/emulator.md, "Seeding stock code"). So the function does not get these
    // values as its args. Harmless for this smoke test, which only checks that the emulator steps it.
    // arg 1 = a param block (points into .data scratch), arg 2 = note<<16, arg 3, arg 4 = lane 0
    emu.writeStackValue(28, 4, 0x40190000L);   // param_1 (block)
    emu.writeStackValue(32, 4, 0x003c0000L);   // param_2 (note<<16)
    emu.writeStackValue(36, 4, 0x00001000L);   // param_3
    emu.writeStackValue(40, 4, 0x00000000L);   // param_4 (lane 0)
    println("PC reg="+pc+"  SP reg="+sp+"  entry="+entry);
    for (int i=0; i<40; i++) {
      Address at = emu.getExecutionAddress();
      String insn = getInstructionAt(at)!=null ? getInstructionAt(at).toString() : "<none>";
      println(String.format("%2d  %s  %-28s d0=%08x d1=%08x d2=%08x d3=%08x", i,
        at, insn,
        emu.readRegister("D0").longValue()&0xffffffffL,
        emu.readRegister("D1").longValue()&0xffffffffL,
        emu.readRegister("D2").longValue()&0xffffffffL,
        emu.readRegister("D3").longValue()&0xffffffffL));
      boolean ok = emu.step(monitor);
      if (!ok) { println("STEP FAILED at "+at+" : "+emu.getLastError()); break; }
    }
    println("done. final PC="+emu.getExecutionAddress());
    emu.dispose();
  }
}
