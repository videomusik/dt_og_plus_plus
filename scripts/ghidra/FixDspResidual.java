// Last-resort cleanup for the handful of DSP residual errors where a correct instruction and a
// phantom (at start+2/+4) oscillate: clear a generous window, strip every reference into it, then
// re-form ONLY the correct instruction at the error address with a flow-restricted disassemble so
// no cascade re-creates the phantom. The error address is the true instruction start (verified
// with objdump on the OS 1.52A _sram project; check it for any other project first).
//
//   analyzeHeadless <proj> <name> -process <bin> -noanalysis -scriptPath scripts/ghidra \
//      -postScript FixDspResidual.java <outfile>
//
// @category dt_og_plus_plus

import ghidra.app.script.GhidraScript;
import ghidra.app.cmd.disassemble.DisassembleCommand;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.*;

public class FixDspResidual extends GhidraScript {

    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        String outfile = args.length > 0 ? args[0] : "fix_residual.txt";
        ReferenceManager rm = currentProgram.getReferenceManager();
        BookmarkManager bm = currentProgram.getBookmarkManager();

        List<Address> errs = new ArrayList<>();
        Iterator<Bookmark> it = bm.getBookmarksIterator(BookmarkType.ERROR);
        while (it.hasNext()) errs.add(it.next().getAddress());

        try (PrintWriter out = new PrintWriter(new FileWriter(outfile))) {
            out.printf("# FixDspResidual: %d residual errors%n", errs.size());
            for (Address e : errs) {
                Address lo = e.subtract(4), hi = e.add(8);
                // strip every reference whose destination lands anywhere in the window
                for (Address a = lo; a.compareTo(hi) <= 0; a = a.add(1))
                    for (Reference r : rm.getReferencesTo(a)) rm.delete(r);
                clearListing(lo, hi);
                bm.removeBookmarks(new AddressSet(e, e), BookmarkType.ERROR, monitor);
                // re-form ONLY the instruction at e (flow-restricted to the single insn window)
                new DisassembleCommand(e, new AddressSet(e, e.add(7)), false).applyTo(currentProgram, monitor);
                Instruction ins = getInstructionAt(e);
                out.printf("# %s -> %s%n", e, ins == null ? "(still undefined)" : ins.toString());
            }
            int remaining = 0;
            Iterator<Bookmark> it2 = bm.getBookmarksIterator(BookmarkType.ERROR);
            while (it2.hasNext()) { it2.next(); remaining++; }
            out.printf("# remaining error bookmarks: %d%n", remaining);
            println("FixDspResidual: remaining errors = " + remaining + " -> " + outfile);
        }
    }
}
