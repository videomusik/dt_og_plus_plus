// Dump every reference DESTINATION inside an address window — i.e. which addresses in a data region
// (a .bss stretch, an SRAM bank, a peripheral window) the program actually touches — with the
// referencing sites, reference types and owning functions. This is how a "hole" in memory is told
// apart from the interior of a buffer: scalars show up as individual READ/WRITE destinations, a
// buffer shows up as one DATA (address-taken) reference to its base and silence after it.
// Run headless as a -postScript (or via scripts/ghidra_query.sh):
//
//   analyzeHeadless ... -postScript DumpRefsInRange.java <outfile> <loHex> <hiHex> [maxSources=4] [bucketHex=0x1000]
//
// Output: one line per destination —  <addr>  nrefs=N  <from>[TYPE]{function} ...  (first maxSources
// sources) — then '#' summary lines: distinct destinations, lowest/highest, and a per-bucket count.
// ⚠️ Static view only: base+offset accesses through a pointer never appear as destinations.
//
// @category dt_og_plus_plus

import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceManager;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.Map;
import java.util.TreeMap;

public class DumpRefsInRange extends GhidraScript {

    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length < 3) {
            printerr("usage: DumpRefsInRange.java <outfile> <loHex> <hiHex> [maxSources=4] [bucketHex=0x1000]");
            return;
        }
        long lo = Long.decode(args[1]);
        long hi = Long.decode(args[2]);             // exclusive
        int maxSources = args.length > 3 ? Integer.decode(args[3]) : 4;
        long bucket = args.length > 4 ? Long.decode(args[4]) : 0x1000;

        ReferenceManager refs = currentProgram.getReferenceManager();
        Listing listing = currentProgram.getListing();
        AddressIterator dests = refs.getReferenceDestinationIterator(new AddressSet(toAddr(lo), toAddr(hi - 1)), true);

        int count = 0;
        long lowest = -1, highest = -1;
        TreeMap<Long, Integer> perBucket = new TreeMap<>();
        try (PrintWriter out = new PrintWriter(new FileWriter(args[0]))) {
            out.printf("# reference destinations in [%08x,%08x)%n", lo, hi);
            while (dests.hasNext()) {
                Address d = dests.next();
                count++;
                if (lowest < 0) lowest = d.getOffset();
                highest = d.getOffset();
                perBucket.merge(d.getOffset() / bucket * bucket, 1, Integer::sum);

                StringBuilder sources = new StringBuilder();
                int n = 0;
                for (Reference r : refs.getReferencesTo(d)) {
                    if (++n > maxSources) continue;    // keep counting, stop listing
                    Function f = listing.getFunctionContaining(r.getFromAddress());
                    sources.append(' ').append(r.getFromAddress())
                           .append('[').append(r.getReferenceType().getName()).append(']');
                    if (f != null) sources.append('{').append(f.getName()).append('}');
                }
                out.printf("%s nrefs=%d%s%n", d, n, sources);
            }
            out.printf("# distinct destinations=%d lowest=%08x highest=%08x%n", count, lowest, highest);
            out.printf("# destinations per %#x-byte bucket:%n", bucket);
            for (Map.Entry<Long, Integer> e : perBucket.entrySet()) out.printf("#   %08x: %d%n", e.getKey(), e.getValue());
        }
        println("DumpRefsInRange: destinations=" + count + " -> " + args[0]);
    }
}
