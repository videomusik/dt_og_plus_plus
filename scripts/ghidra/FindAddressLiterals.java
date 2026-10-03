// Find every INSTRUCTION whose operands contain a literal value inside an address window — the
// addresses that reference queries CANNOT see. Code that does `addi.l #0x8000ba00,d0` or
// `adda.l #0x80008800,a0` builds an address in a register from an immediate: Ghidra records a scalar
// operand, not a memory reference, so DumpRefsInRange / RefDensityMap are blind to it. Driver code
// that owns fixed DMA rings and buffers looks completely unreferenced to them.
// ⭐ Run this before concluding that any region is unused.
// Run headless as a -postScript (or via scripts/ghidra_query.sh):
//
//   analyzeHeadless ... -postScript FindAddressLiterals.java <outfile> <loHex> <hiHex> [bucketHex=0x400]
//
// Output: one line per hit — <instr addr>  <value>  <mnemonic>  <function> — then '#' summary lines
// with the distinct values, the per-bucket histogram, and the min/max value seen.
// ⚠️ Still static: a base address arriving from memory at runtime, or built by arithmetic across
// several instructions, shows up here only if some literal in the chain lands in the window. Widen
// the window (a ring's base is often below the range you care about) rather than trusting a zero.
//
// @category dt_og_plus_plus

import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.lang.Register;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.listing.InstructionIterator;
import ghidra.program.model.scalar.Scalar;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

public class FindAddressLiterals extends GhidraScript {

    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length < 3) {
            printerr("usage: FindAddressLiterals.java <outfile> <loHex> <hiHex> [bucketHex=0x400]");
            return;
        }
        long lo = Long.decode(args[1]);
        long hi = Long.decode(args[2]);             // exclusive
        long bucket = args.length > 3 ? Long.decode(args[3]) : 0x400;

        TreeSet<Long> values = new TreeSet<>();
        TreeMap<Long, Integer> perBucket = new TreeMap<>();
        int hits = 0;

        try (PrintWriter out = new PrintWriter(new FileWriter(args[0]))) {
            out.printf("# instruction operand literals in [%08x,%08x)%n", lo, hi);
            out.println("# instr\tvalue\tmnemonic\tfunction");
            InstructionIterator it = currentProgram.getListing().getInstructions(true);
            while (it.hasNext() && !monitor.isCancelled()) {
                Instruction insn = it.next();
                for (int op = 0; op < insn.getNumOperands(); op++) {
                    for (Object o : insn.getOpObjects(op)) {
                        long v;
                        if (o instanceof Scalar) v = ((Scalar) o).getUnsignedValue();
                        else if (o instanceof Address) v = ((Address) o).getOffset();
                        else continue;              // registers and the like carry no literal
                        if (v < lo || v >= hi) continue;

                        Function f = getFunctionContaining(insn.getAddress());
                        out.printf("%s\t%08x\t%s\t%s%n", insn.getAddress(), v,
                                insn.toString(), f == null ? "-" : f.getName());
                        values.add(v);
                        perBucket.merge(v / bucket * bucket, 1, Integer::sum);
                        hits++;
                    }
                }
            }
            out.printf("# hits=%d distinct values=%d%n", hits, values.size());
            if (!values.isEmpty()) out.printf("# lowest=%08x highest=%08x%n", values.first(), values.last());
            out.printf("# hits per %#x-byte bucket:%n", bucket);
            for (Map.Entry<Long, Integer> e : perBucket.entrySet()) out.printf("#   %08x: %d%n", e.getKey(), e.getValue());
        }
        println("FindAddressLiterals: hits=" + hits + " distinct=" + values.size() + " -> " + args[0]);
    }
}
