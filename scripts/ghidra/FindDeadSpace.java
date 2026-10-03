// List the undefined ranges of an address window that nothing references — candidate padding /
// dead space — with what fills them and what borders them, so the user can tell alignment padding
// from data the analyser merely failed to type.
// Run headless as a -postScript (or via scripts/ghidra_query.sh):
//
//   analyzeHeadless ... -postScript FindDeadSpace.java <outfile> <startHex> <endHex> [minLen=16]
//
// Output (tab-separated), one row per undefined range >= minLen bytes, plus '#' summary lines:
//   start  end  len  fill  uniform|mixed  free|REFD  before=<kind>@<addr>  after=<kind>@<addr>  [firstRefInto=<addr>]
// 'free' = no reference lands anywhere inside the range; 'REFD' = at least one does. ⚠️ 'free' is a
// STATIC verdict: code reached only through vtables / jump tables is undefined+unreferenced here yet
// very much alive — a mixed-fill range between two instructions is almost always such code.
//
// @category dt_og_plus_plus

import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.symbol.ReferenceManager;
import java.io.FileWriter;
import java.io.PrintWriter;

public class FindDeadSpace extends GhidraScript {

    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length < 3) {
            printerr("usage: FindDeadSpace.java <outfile> <startHex> <endHex> [minLen=16]");
            return;
        }
        long start = Long.decode(args[1]);
        long end = Long.decode(args[2]);            // exclusive
        int minLen = args.length > 3 ? Integer.decode(args[3]) : 16;

        Listing listing = currentProgram.getListing();
        ReferenceManager refs = currentProgram.getReferenceManager();
        Memory mem = currentProgram.getMemory();
        AddressSet window = new AddressSet(toAddr(start), toAddr(end - 1));
        AddressSetView undefined = listing.getUndefinedRanges(window, true, monitor);

        long undefinedBytes = 0, freeBytes = 0;
        int freeRanges = 0;
        try (PrintWriter out = new PrintWriter(new FileWriter(args[0]))) {
            out.printf("# undefined ranges in [%08x,%08x), minLen=%d%n", start, end, minLen);
            out.println("# start\tend\tlen\tfill\tuniform\trefs\tbefore\tafter");
            for (AddressRange r : undefined.getAddressRanges()) {
                long len = r.getLength();
                undefinedBytes += len;
                if (len < minLen) continue;
                Address lo = r.getMinAddress(), hi = r.getMaxAddress();

                AddressIterator into = refs.getReferenceDestinationIterator(new AddressSet(lo, hi), true);
                Address firstRef = into.hasNext() ? into.next() : null;

                byte[] bytes = new byte[(int) len];
                mem.getBytes(lo, bytes);
                boolean uniform = true;
                for (byte b : bytes) if (b != bytes[0]) { uniform = false; break; }

                out.printf("%s\t%s\t%d\t%02x\t%s\t%s\tbefore=%s\tafter=%s%s%n",
                        lo, hi, len, bytes[0] & 0xff, uniform ? "uniform" : "mixed",
                        firstRef == null ? "free" : "REFD",
                        describe(listing.getCodeUnitBefore(lo)), describe(listing.getCodeUnitAfter(hi)),
                        firstRef == null ? "" : "\tfirstRefInto=" + firstRef);
                if (firstRef == null) { freeRanges++; freeBytes += len; }
            }
            out.printf("# totals: undefinedBytes=%d freeRanges=%d freeBytes=%d%n", undefinedBytes, freeRanges, freeBytes);
        }
        println("FindDeadSpace: undefinedBytes=" + undefinedBytes + " freeRanges=" + freeRanges
                + " freeBytes=" + freeBytes + " -> " + args[0]);
    }

    // What sits next to a range: code (with mnemonic), typed data, untyped data, or nothing.
    private static String describe(CodeUnit cu) {
        if (cu == null) return "none";
        String at = "@" + cu.getMinAddress();
        if (cu instanceof Instruction) return "code:" + ((Instruction) cu).getMnemonicString() + at;
        if (cu instanceof Data) {
            Data d = (Data) cu;
            return (d.isDefined() ? "data:" + d.getDataType().getName() : "undefined") + at;
        }
        return "?" + at;
    }
}
