// Decode check for the mac.l (d16,An) "mode 5" form: disassemble four known MAC sites in the MAIN OS
// and print each instruction with its length. On a project imported with the ColdFire-EMAC language
// (scripts/ghidra_ext/) 0x40075cfa and 0x400721c6 decode as 6-byte mac.l; on the stock language they
// come out 4 bytes long or fail. Nothing is saved (ghidra_emu.sh opens the project read-only).
//   ./scripts/ghidra_emu.sh 1.52A TestMac5
// @category dt_og_plus_plus
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Instruction;
public class TestMac5 extends GhidraScript {
  public void run() throws Exception {
    long[] addrs = {0x40075cfaL, 0x40075d04L, 0x40075d1eL, 0x400721c6L};
    for (long a: addrs) {
      Address addr = toAddr(a);
      try { disassemble(addr); } catch (Throwable t) { println("disasm err @"+Long.toHexString(a)+": "+t); }
      Instruction ins = getInstructionAt(addr);
      println(String.format("%08x: %s", a, ins!=null ? (ins.toString()+"   [len "+ins.getLength()+"]") : "NULL / bad-instruction"));
    }
  }
}
