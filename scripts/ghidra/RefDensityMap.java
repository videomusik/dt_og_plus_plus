// Coarse map of WHERE a large data region is referenced: a histogram of reference destinations per
// bucket, and the largest stretches with no destination at all. The big gaps are the interiors of
// large buffers (reached via a base pointer, so invisible to static references); the clusters are
// where the scalars live. Use it before DumpRefsInRange to decide which stretch to zoom into.
// Run headless as a -postScript (or via scripts/ghidra_query.sh):
//
//   analyzeHeadless ... -postScript RefDensityMap.java <outfile> <loHex> <hiHex> [bucketHex=0x10000] [gaps=25]
//
// Output ('#'-prefixed, human-readable): destination count and highest address; non-empty buckets
// with their counts; the <gaps> largest gaps as  <last referenced> -> <next referenced>  size.
// ⚠️ A gap is "no static reference", not "unused".
//
// @category dt_og_plus_plus

import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.AddressIterator;
import ghidra.program.model.address.AddressSet;
import ghidra.program.model.symbol.ReferenceManager;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;

public class RefDensityMap extends GhidraScript {

    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length < 3) {
            printerr("usage: RefDensityMap.java <outfile> <loHex> <hiHex> [bucketHex=0x10000] [gaps=25]");
            return;
        }
        long lo = Long.decode(args[1]);
        long hi = Long.decode(args[2]);             // exclusive
        long bucket = args.length > 3 ? Long.decode(args[3]) : 0x10000;
        int gapsToShow = args.length > 4 ? Integer.decode(args[4]) : 25;

        ReferenceManager refs = currentProgram.getReferenceManager();
        AddressIterator it = refs.getReferenceDestinationIterator(new AddressSet(toAddr(lo), toAddr(hi - 1)), true);
        ArrayList<Long> dests = new ArrayList<>();
        TreeMap<Long, Integer> perBucket = new TreeMap<>();
        while (it.hasNext()) {
            long d = it.next().getOffset();
            dests.add(d);
            perBucket.merge(d / bucket * bucket, 1, Integer::sum);
        }

        // Gaps between consecutive destinations, plus the run-out to the end of the window.
        ArrayList<long[]> gaps = new ArrayList<>();
        for (int i = 0; i + 1 < dests.size(); i++)
            gaps.add(new long[] { dests.get(i), dests.get(i + 1), dests.get(i + 1) - dests.get(i) });
        if (!dests.isEmpty()) {
            long last = dests.get(dests.size() - 1);
            gaps.add(new long[] { last, hi, hi - last });
        }
        gaps.sort((a, b) -> Long.compare(b[2], a[2]));

        try (PrintWriter out = new PrintWriter(new FileWriter(args[0]))) {
            out.printf("# [%08x,%08x): %d referenced destinations, highest=%08x%n", lo, hi, dests.size(),
                    dests.isEmpty() ? 0 : dests.get(dests.size() - 1));
            out.printf("# non-empty %#x-byte buckets (bucket: destinations):%n", bucket);
            for (Map.Entry<Long, Integer> e : perBucket.entrySet()) out.printf("#   %08x: %d%n", e.getKey(), e.getValue());
            out.printf("# %d largest gaps between referenced destinations (last -> next, size):%n", gapsToShow);
            for (int i = 0; i < Math.min(gapsToShow, gaps.size()); i++) {
                long[] g = gaps.get(i);
                out.printf("#   %08x -> %08x  %d B (%.1f KB)%n", g[0], g[1], g[2], g[2] / 1024.0);
            }
        }
        println("RefDensityMap: destinations=" + dests.size() + " -> " + args[0]);
    }
}
