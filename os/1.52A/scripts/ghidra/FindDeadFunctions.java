// Landing-pad map: classify every function as LIVE or DEAD, where DEAD = nothing anywhere points at
// its entry by ANY of three independent evidence sources (no single one suffices: references miss
// pointer-word installs such as the audio ISR, and byte scans miss PC-relative address-taking):
//   1. Ghidra references to the entry (any type, incl. DATA + PC-relative lea (d16,PC),aN)
//   2. a 4-byte-aligned pointer word in the image equal to the entry (vtable slots, jump/handler tables)
//   3. an instruction operand literal equal to the entry (move.l #FUN,dN style installs)
// A function unreferenced by all three is a landing-pad CANDIDATE — still a lower bound on "live"
// (a fully-computed address, base+runtime-offset with no literal, is invisible to all three), so a
// big "dead" function especially deserves a read before trusting it.
//
//   analyzeHeadless <proj> <name> -process <bin> -noanalysis \
//      -scriptPath "scripts/ghidra;os/1.52A/scripts/ghidra" \
//      -postScript FindDeadFunctions.java <outfile> [minLenDead=16]
//   GHIDRA_PROJECT=dt_1.52A_seed ./scripts/ghidra_query.sh 1.52A main FindDeadFunctions   # via the wrapper
//
// Output: one TSV row per DEAD candidate (entry, size, ghRefs, ptrWord, opLit, callees, leafPad, name;
// ptrWord and opLit are '-'/'Y' flags),
// largest first, then '#' summary + a validation line for ten functions known to be live.
// ⛔ "Dead" here is a CANDIDATE list to vet, never free space to spend: code reached through a fully
// computed address is invisible to all three sources. Read a candidate before using it, prefer small
// leaf functions, and treat only a pad that has run on hardware as proven.
//
// @category dt_og_plus_plus

import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.mem.*;
import ghidra.program.model.scalar.Scalar;
import ghidra.program.model.symbol.ReferenceManager;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.*;

public class FindDeadFunctions extends GhidraScript {

    // Functions we independently KNOW are live — the classifier must mark all of them not-dead.
    static final long[] KNOWN_LIVE = {
        0x40077120L, 0x4007011cL, 0x4006f1beL, 0x40074e84L, 0x4006753eL,
        0x400d0578L, 0x4006f546L, 0x40076b5aL, 0x40074af2L, 0x4006f882L
    };

    @Override
    public void run() throws Exception {
        // Fail closed: an empty KNOWN_LIVE would make the validation below pass on nothing.
        if (KNOWN_LIVE.length == 0) {
            printerr("FindDeadFunctions: KNOWN_LIVE is empty; fill it from this OS folder's own analysis");
            return;
        }
        String[] args = getScriptArgs();
        String outfile = args.length > 0 ? args[0] : "dead_functions.tsv";
        int minLenDead = args.length > 1 ? Integer.decode(args[1]) : 16;

        Listing listing = currentProgram.getListing();
        ReferenceManager rm = currentProgram.getReferenceManager();
        Memory mem = currentProgram.getMemory();
        FunctionManager fm = currentProgram.getFunctionManager();

        // Set of all function entry offsets, for O(1) membership from the two byte/operand scans.
        Set<Long> entries = new HashSet<>();
        for (Function f : fm.getFunctions(true)) entries.add(f.getEntryPoint().getOffset());

        // Source 2: 4-byte-aligned pointer words anywhere in initialized memory that equal an entry.
        Set<Long> byPtrWord = new HashSet<>();
        for (MemoryBlock b : mem.getBlocks()) {
            if (!b.isInitialized()) continue;
            Address a = b.getStart();
            long start = a.getOffset();
            long end = b.getEnd().getOffset();
            long p = (start + 3) & ~3L;             // first 4-aligned addr in block
            for (long off = p; off + 3 <= end; off += 4) {
                Address at = toAddr(off);
                int w = mem.getInt(at);              // big-endian per the language
                long v = ((long) w) & 0xffffffffL;
                if (entries.contains(v)) byPtrWord.add(v);
            }
        }

        // Source 3: instruction operand scalars that equal an entry (immediate address installs).
        Set<Long> byOperand = new HashSet<>();
        for (Instruction ins : listing.getInstructions(true)) {
            int n = ins.getNumOperands();
            for (int i = 0; i < n; i++) {
                for (Object o : ins.getOpObjects(i)) {
                    if (o instanceof Scalar) {
                        long v = ((Scalar) o).getUnsignedValue() & 0xffffffffL;
                        if (entries.contains(v)) byOperand.add(v);
                    }
                }
            }
        }

        // Classify.
        List<Function> dead = new ArrayList<>();
        long deadBytes = 0; int total = 0;
        for (Function f : fm.getFunctions(true)) {
            total++;
            long e = f.getEntryPoint().getOffset();
            int gh = rm.getReferenceCountTo(f.getEntryPoint());
            boolean pw = byPtrWord.contains(e);
            boolean op = byOperand.contains(e);
            if (gh == 0 && !pw && !op) {
                dead.add(f);
                deadBytes += f.getBody().getNumAddresses();
            }
        }
        dead.sort((x, y) -> Long.compare(y.getBody().getNumAddresses(), x.getBody().getNumAddresses()));

        // A landing pad must be safe: dead AND a leaf (no callees) — a dead function with a rich
        // callee tree is almost always live code reached by dispatch the three sources can't see
        // (spot-checked: the two biggest "dead" call vtable methods + the allocator). So report
        // callee count and flag the trustworthy subset = dead + leaf.
        int deadBig = 0; long deadBigBytes = 0;
        int leafPads = 0; long leafPadBytes = 0;
        try (PrintWriter out = new PrintWriter(new FileWriter(outfile))) {
            out.println("# entry\tsize\tghRefs\tptrWord\topLit\tcallees\tleafPad\tname");
            for (Function f : dead) {
                long size = f.getBody().getNumAddresses();
                if (size >= minLenDead) { deadBig++; deadBigBytes += size; }
                int callees = f.getCalledFunctions(monitor).size();
                boolean leaf = callees == 0;
                boolean pad = leaf && size >= minLenDead;
                if (pad) { leafPads++; leafPadBytes += size; }
                out.printf("%s\t%d\t%d\t%s\t%s\t%d\t%s\t%s%n", f.getEntryPoint(), size,
                        rm.getReferenceCountTo(f.getEntryPoint()),
                        byPtrWord.contains(f.getEntryPoint().getOffset()) ? "Y" : "-",
                        byOperand.contains(f.getEntryPoint().getOffset()) ? "Y" : "-",
                        callees, pad ? "PAD" : "-", f.getName());
            }
            out.printf("# totals: functions=%d dead=%d deadBytes=%d  dead>=%d: count=%d bytes=%d%n",
                    total, dead.size(), deadBytes, minLenDead, deadBig, deadBigBytes);
            out.printf("# safe leaf pads (dead + no callees + >=%dB): count=%d bytes=%d%n",
                    minLenDead, leafPads, leafPadBytes);

            // Validation: none of the known-live may be classified dead, and each must be a function
            // entry in this program; an address that is not (a table made for another OS) fails too.
            Set<Long> deadSet = new HashSet<>();
            for (Function f : dead) deadSet.add(f.getEntryPoint().getOffset());
            StringBuilder v = new StringBuilder("# validate known-live: ");
            boolean ok = true;
            for (long k : KNOWN_LIVE) {
                String verdict;
                if (!entries.contains(k)) verdict = "NOT-A-FUNCTION!";
                else if (deadSet.contains(k)) verdict = "DEAD!";
                else verdict = "live";
                if (!verdict.equals("live")) ok = false;
                v.append(String.format("%08x=%s ", k, verdict));
            }
            out.println(v.toString());
            out.println("# validation " + (ok ? "PASSED (all known-live are live)" : "FAILED"));
            println("FindDeadFunctions: dead=" + dead.size() + " (>=" + minLenDead + "B: " + deadBig
                    + ", " + deadBigBytes + " B) of " + total + " functions; known-live validation "
                    + (ok ? "PASSED" : "FAILED") + " -> " + outfile);
        }
    }
}
