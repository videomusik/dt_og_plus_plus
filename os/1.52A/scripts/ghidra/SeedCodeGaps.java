// Fill in indirect-reached code that linear + direct-reference analysis missed: seed disassembly at
// the start of every undefined range INSIDE the code windows, create a function, and — the essential
// step — VALIDATE it, undoing the whole function if its body contains a Bad Instruction (a seed that
// decoded plausibly but is really data). Iterate to a fixpoint.
//
//   analyzeHeadless <proj> <name> -process <bin> -noanalysis \
//      -scriptPath "scripts/ghidra;os/1.52A/scripts/ghidra" \
//      -postScript SeedCodeGaps.java <outfile> [minLen=2] [maxPasses=10]
//
// Via the wrapper (on a copied project, see docs/toolchain.md section 4d and
// os/1.52A/notes/analysis_reference.md):
//   GHIDRA_PROJECT=dt_1.52A_seed ./scripts/ghidra_query.sh 1.52A main SeedCodeGaps
//
// ⚠️ Seeds are restricted to the two real code windows of the Digitakt OS 1.52A MAIN OS (main
// 0x400004b2-0x40162748, island 0x40210e4a-0x40211ef2). Seeding into .rodata/.data disassembles
// strings and tables as code: seeding from pointer words across the whole image instead raised the
// bad-instruction bookmarks from 12 to about 1,000 (measured on the language with only the first two
// EMAC fixes, which leaves 12).
// ⚠️ Run on a COPY of the project; validation undoes bad seeds, but a copy makes any surprise cheap.
//
// Output (tab-separated) records the per-pass created/rejected counts and the bad-bookmark delta;
// a clean run keeps the delta near zero (rejected seeds are data, cleared back to undefined).
//
// @category dt_og_plus_plus

import ghidra.app.script.GhidraScript;
import ghidra.app.cmd.disassemble.DisassembleCommand;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.*;

public class SeedCodeGaps extends GhidraScript {

    static final long[][] WINDOWS = {
        {0x400004b2L, 0x40162748L},   // main code block
        {0x40210e4aL, 0x40211ef2L},   // small code island
    };

    private boolean inWindow(Address a) {
        long v = a.getOffset();
        for (long[] w : WINDOWS) if (v >= w[0] && v < w[1]) return true;
        return false;
    }

    private Set<Address> badBookmarkAddrs() {
        Set<Address> s = new HashSet<>();
        BookmarkManager bm = currentProgram.getBookmarkManager();
        Iterator<Bookmark> it = bm.getBookmarksIterator(BookmarkType.ERROR);
        while (it.hasNext()) s.add(it.next().getAddress());
        return s;
    }

    @Override
    public void run() throws Exception {
        // Fail closed: WINDOWS belongs to this OS folder; an empty table is never a default.
        if (WINDOWS.length == 0) {
            printerr("SeedCodeGaps: WINDOWS is empty; fill it from this OS folder's own analysis");
            return;
        }
        String[] args = getScriptArgs();
        String outfile = args.length > 0 ? args[0] : "seed_out.txt";
        int minLen = args.length > 1 ? Integer.decode(args[1]) : 2;
        int maxPasses = args.length > 2 ? Integer.decode(args[2]) : 10;

        Listing listing = currentProgram.getListing();

        int fnBefore = currentProgram.getFunctionManager().getFunctionCount();
        Set<Address> bad0 = badBookmarkAddrs();
        long undefBefore = undefinedInWindows(listing);

        Set<Long> rejected = new HashSet<>();   // seeds proven to be data — never re-seed them
        int totalCreated = 0, totalRejected = 0;

        try (PrintWriter out = new PrintWriter(new FileWriter(outfile))) {
            out.printf("# SeedCodeGaps: minLen=%d maxPasses=%d%n", minLen, maxPasses);
            out.printf("# before: functions=%d undefinedInWindows=%d badBookmarks=%d%n",
                    fnBefore, undefBefore, bad0.size());
            out.println("# pass\tseeds\tcreated\trejected\tnewBad");

            for (int pass = 1; pass <= maxPasses; pass++) {
                List<Address> seeds = new ArrayList<>();
                for (long[] w : WINDOWS) {
                    AddressSet win = new AddressSet(toAddr(w[0]), toAddr(w[1] - 1));
                    AddressSetView undef = listing.getUndefinedRanges(win, true, monitor);
                    for (AddressRange r : undef.getAddressRanges()) {
                        if (r.getLength() >= minLen && !rejected.contains(r.getMinAddress().getOffset()))
                            seeds.add(r.getMinAddress());
                    }
                }
                if (seeds.isEmpty()) break;

                Set<Address> badBeforePass = badBookmarkAddrs();
                int created = 0;
                List<Address> madeThisPass = new ArrayList<>();
                for (Address seed : seeds) {
                    if (!inWindow(seed)) continue;
                    if (listing.getInstructionAt(seed) != null) continue;
                    if (listing.getDefinedDataAt(seed) != null) continue;
                    DisassembleCommand cmd = new DisassembleCommand(seed, null, true);
                    cmd.applyTo(currentProgram, monitor);
                    if (listing.getInstructionAt(seed) == null) { rejected.add(seed.getOffset()); continue; }
                    Function f = createFunction(seed, null);
                    if (f != null) { created++; madeThisPass.add(seed); }
                }

                // validate: any function created this pass whose body now carries an error bookmark is data
                Set<Address> badAfter = badBookmarkAddrs();
                Set<Address> newBad = new HashSet<>(badAfter); newBad.removeAll(badBeforePass);
                int rejectedThisPass = 0;
                for (Address entry : madeThisPass) {
                    Function f = getFunctionAt(entry);
                    if (f == null) continue;
                    AddressSetView body = f.getBody();
                    boolean hasBad = false;
                    for (Address b : newBad) if (body.contains(b)) { hasBad = true; break; }
                    if (hasBad) {
                        removeFunction(f);
                        clearListing(body);
                        rejected.add(entry.getOffset());
                        rejectedThisPass++;
                        created--;
                    }
                }
                // clear any straggler new bad bookmarks left after undo, and mark their ranges rejected
                for (Address b : badBookmarkAddrs()) {
                    if (!bad0.contains(b) && inWindow(b)) {
                        currentProgram.getBookmarkManager().removeBookmarks(new AddressSet(b, b),
                                BookmarkType.ERROR, monitor);
                    }
                }

                totalCreated += created; totalRejected += rejectedThisPass;
                out.printf("%d\t%d\t%d\t%d\t%d%n", pass, seeds.size(), created, rejectedThisPass, newBad.size());
                if (created <= 0) break;
            }

            int fnAfter = currentProgram.getFunctionManager().getFunctionCount();
            long undefAfter = undefinedInWindows(listing);
            int badAfter = badBookmarkAddrs().size();
            out.printf("# after: functions=%d undefinedInWindows=%d badBookmarks=%d%n",
                    fnAfter, undefAfter, badAfter);
            out.printf("# delta: functions=+%d undefined=-%d badBookmarks=%+d totalCreated=%d totalRejected=%d%n",
                    fnAfter - fnBefore, undefBefore - undefAfter, badAfter - bad0.size(), totalCreated, totalRejected);
            println("SeedCodeGaps: +" + (fnAfter - fnBefore) + " functions, undefined -"
                    + (undefBefore - undefAfter) + " B, badBookmarks " + bad0.size() + "->" + badAfter
                    + " (created=" + totalCreated + " rejected=" + totalRejected + ") -> " + outfile);
        }
    }

    private long undefinedInWindows(Listing listing) throws Exception {
        long total = 0;
        for (long[] w : WINDOWS) {
            AddressSet win = new AddressSet(toAddr(w[0]), toAddr(w[1] - 1));
            for (AddressRange r : listing.getUndefinedRanges(win, true, monitor).getAddressRanges())
                total += r.getLength();
        }
        return total;
    }
}
