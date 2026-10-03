// Curate the unified-SRAM DSP project toward a clean, readable disassembly. The DSP is dense
// hand-written boot assembly with heavy indirect dispatch; Ghidra's jump-table/pointer recovery
// creates phantom flows that land mid-instruction (inside a real insn whose operand is an absolute
// 0x8000xxxx / 0xfc0xxxxx address) or in the zero-filled SRAM, leaving "conflicting instruction" /
// "repeated byte" bookmarks even though the PRIMARY instruction is correct (objdump-verified).
//
// This script, iterated to a fixpoint:
//   1. marks everything OUTSIDE the code windows as data (image1 low-SRAM, section 2's own data, DSP
//      stack/scalars, the DMA rings, image2 fast-data, and the zero gaps) — so nothing there is
//      disassembled as code;
//   2. for each Bad-Instruction error, deletes the phantom references landing at the error address
//      and clears the conflicting code, then re-disassembles from the containing function's entry so
//      the correct primary instruction is re-formed;
//   3. removes the now-stale error bookmarks.
//
//   analyzeHeadless <proj> <name> -process <bin> -noanalysis -scriptPath scripts/ghidra \
//      -postScript CurateDsp.java <outfile> [maxPasses=6]
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

public class CurateDsp extends GhidraScript {

    // Code windows, [lo, hi); everything else in [0x80000000,0x80010000) is data.
    //  - the updater's low-SRAM stub, whose routines section 2 calls (0x8000049a, 0x800006c2), up to
    //    the routine at 0x80000eaa: that one runs on past 0x80000ec0 into bytes section 2 overwrites;
    //  - section 2's code, which ends with an rts at 0x800066b4;
    //  - section 2's last two routines (the SPI read and the loader). Between them and the code above
    //    lies section 2's data: tables, bitmaps, strings and variables, [0x800066b6, 0x8000758c).
    static final long[][] CODE = {
        { 0x80000400L, 0x80000eaaL },
        { 0x80000ec0L, 0x800066b6L },
        { 0x8000758cL, 0x800076d6L },
    };
    static final long SRAM_LO = 0x80000000L, SRAM_HI = 0x80010000L;

    private AddressSet codeSet() {
        AddressSet s = new AddressSet();
        for (long[] w : CODE) s.add(toAddr(w[0]), toAddr(w[1] - 1));
        return s;
    }

    private boolean inCode(Address a) {
        return codeSet().contains(a);
    }

    private Set<Address> errorAddrs() {
        Set<Address> s = new HashSet<>();
        Iterator<Bookmark> it = currentProgram.getBookmarkManager().getBookmarksIterator(BookmarkType.ERROR);
        while (it.hasNext()) s.add(it.next().getAddress());
        return s;
    }

    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        String outfile = args.length > 0 ? args[0] : "curate_dsp.txt";
        int maxPasses = args.length > 1 ? Integer.decode(args[1]) : 6;

        Listing listing = currentProgram.getListing();
        ReferenceManager rm = currentProgram.getReferenceManager();
        BookmarkManager bm = currentProgram.getBookmarkManager();

        int err0 = errorAddrs().size();

        // 1. Clear the instructions outside the code windows (data regions) so nothing there is code.
        //    Defined data there stays (section 2's strings, for one).
        AddressSet data = new AddressSet(toAddr(SRAM_LO), toAddr(SRAM_HI - 1)).subtract(codeSet());
        List<Instruction> stray = new ArrayList<>();
        InstructionIterator ii = listing.getInstructions(data, true);
        while (ii.hasNext()) stray.add(ii.next());
        for (Instruction ins : stray) clearListing(ins.getMinAddress(), ins.getMaxAddress());
        // kill references pointing FROM the data regions into anywhere (phantom flows from mis-typed data)
        removeRefsFrom(rm, data);

        try (PrintWriter out = new PrintWriter(new FileWriter(outfile))) {
            out.printf("# CurateDsp: code windows %s, errors before=%d%n", codeSet(), err0);
            out.println("# pass\terrorsAtStart\tphantomRefsRemoved\tclearedSites\terrorsAtEnd");

            for (int pass = 1; pass <= maxPasses; pass++) {
                Set<Address> errs = errorAddrs();
                int startErr = errs.size();
                // (a) remove every MID-INSTRUCTION reference in the code window — a real reference
                // always targets an instruction start; a ref landing inside a defined instruction is
                // the phantom (jump-table/pointer mis-recovery) that spawns the conflict.
                int refsRemoved = removeMidInstructionRefs(rm, listing);
                // (b) for each error site, clear the conflicting unit and note the real instruction
                // start (the containing code unit's start, or the function entry) to re-disassemble.
                Set<Address> reDisasm = new TreeSet<>();
                for (Address e : errs) {
                    CodeUnit cu = listing.getCodeUnitContaining(e);
                    if (cu != null && !cu.getMinAddress().equals(e)) reDisasm.add(cu.getMinAddress());
                    else reDisasm.add(e);
                    Function f = getFunctionContaining(e);
                    if (f != null && inCode(f.getEntryPoint())) reDisasm.add(f.getEntryPoint());
                    clearListing(e, e.add(3));
                    bm.removeBookmarks(new AddressSet(e, e), BookmarkType.ERROR, monitor);
                }
                // (c) re-disassemble the real instruction starts.
                for (Address a : reDisasm) {
                    if (!inCode(a)) continue;
                    new DisassembleCommand(a, null, true).applyTo(currentProgram, monitor);
                }
                int errEnd = errorAddrs().size();
                out.printf("%d\t%d\t%d\t%d\t%d%n", pass, startErr, refsRemoved, errs.size(), errEnd);
                if (errEnd == 0 || errEnd >= startErr) break;
            }

            int errFinal = errorAddrs().size();
            out.printf("# done: errors %d -> %d%n", err0, errFinal);
            // list any residual with the containing instruction, for annotation
            for (Address e : new TreeSet<>(errorAddrs())) {
                CodeUnit cu = listing.getCodeUnitContaining(e);
                out.printf("# residual %s  in %s%n", e, cu == null ? "(none)" :
                        (cu.getMinAddress() + " " + cu));
            }
            println("CurateDsp: errors " + err0 + " -> " + errFinal + " -> " + outfile);
        }
    }

    // Remove references whose destination lands strictly inside a defined instruction (start != dest)
    // within the code windows — these are the phantom flows behind the conflict/constructor errors.
    private int removeMidInstructionRefs(ReferenceManager rm, Listing listing) {
        List<Reference> phantom = new ArrayList<>();
        AddressIterator dests = rm.getReferenceDestinationIterator(codeSet(), true);
        while (dests.hasNext()) {
            Address d = dests.next();
            Instruction ins = listing.getInstructionContaining(d);
            if (ins != null && !ins.getMinAddress().equals(d)) {
                for (Reference r : rm.getReferencesTo(d)) phantom.add(r);
            }
        }
        for (Reference r : phantom) rm.delete(r);
        return phantom.size();
    }

    private void removeRefsFrom(ReferenceManager rm, AddressSetView set) {
        AddressIterator it = rm.getReferenceSourceIterator(set, true);
        List<Address> froms = new ArrayList<>();
        while (it.hasNext()) froms.add(it.next());
        for (Address a : froms) for (Reference r : rm.getReferencesFrom(a)) rm.delete(r);
    }
}
