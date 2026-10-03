// Dump the analysed program to plain text: a summary, every function, every defined string.
// Run headless as a -postScript with one argument: the output directory.
//
//   analyzeHeadless ... -scriptPath scripts/ghidra -postScript DumpFunctions.java work/ghidra/out/<image>
//
// Output files (tab-separated where tabular):
//   summary.txt     counts: bytes, instructions, functions, strings, undefined bytes in code ranges
//   functions.tsv   address  size_bytes  name  (Ghidra's name; FUN_xxxxxxxx where nothing better is known)
//   strings.tsv     address  length  string (escaped)
//
// @category dt_og_plus_plus

import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressSetView;
import ghidra.program.model.listing.*;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.symbol.SymbolTable;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;

public class DumpFunctions extends GhidraScript {

    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length < 1) {
            printerr("usage: DumpFunctions.java <outdir>");
            return;
        }
        File out = new File(args[0]);
        out.mkdirs();

        Listing listing = currentProgram.getListing();
        FunctionManager fm = currentProgram.getFunctionManager();
        SymbolTable st = currentProgram.getSymbolTable();

        // ---- functions ----
        int nFunc = 0;
        long funcBytes = 0;
        try (PrintWriter w = new PrintWriter(new FileWriter(new File(out, "functions.tsv")))) {
            w.println("address\tsize\tname");
            FunctionIterator it = fm.getFunctions(true);
            while (it.hasNext() && !monitor.isCancelled()) {
                Function f = it.next();
                long size = f.getBody().getNumAddresses();
                w.println(f.getEntryPoint() + "\t" + size + "\t" + f.getName(true));
                nFunc++;
                funcBytes += size;
            }
        }

        // ---- strings ----
        int nStr = 0;
        try (PrintWriter w = new PrintWriter(new FileWriter(new File(out, "strings.tsv")))) {
            w.println("address\tlength\tstring");
            DataIterator di = listing.getDefinedData(true);
            while (di.hasNext() && !monitor.isCancelled()) {
                Data d = di.next();
                if (!d.hasStringValue()) continue;
                Object v = d.getValue();
                if (v == null) continue;
                String s = v.toString().replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r");
                w.println(d.getAddress() + "\t" + d.getLength() + "\t" + s);
                nStr++;
            }
        }

        // ---- summary ----
        long totalBytes = 0;
        StringBuilder blocks = new StringBuilder();
        for (MemoryBlock b : currentProgram.getMemory().getBlocks()) {
            totalBytes += b.getSize();
            blocks.append(String.format("  block %-12s %s - %s  %d bytes%n", b.getName(), b.getStart(), b.getEnd(), b.getSize()));
        }
        long nInstr = listing.getNumInstructions();
        long nDefData = listing.getNumDefinedData();

        // bytes inside function bodies that hold neither an instruction nor defined data
        long undefinedInFuncs = 0;
        FunctionIterator it2 = fm.getFunctions(true);
        while (it2.hasNext() && !monitor.isCancelled()) {
            AddressSetView body = it2.next().getBody();
            AddressSetView undef = listing.getUndefinedRanges(body, false, monitor);
            undefinedInFuncs += undef.getNumAddresses();
        }

        // disassembly errors ("Bad Instruction" and friends are Error-type bookmarks)
        int nErr = 0;
        try (PrintWriter w = new PrintWriter(new FileWriter(new File(out, "errors.tsv")))) {
            w.println("address\tcategory\tcomment");
            java.util.Iterator<ghidra.program.model.listing.Bookmark> bi =
                currentProgram.getBookmarkManager().getBookmarksIterator("Error");
            while (bi.hasNext() && !monitor.isCancelled()) {
                ghidra.program.model.listing.Bookmark bm = bi.next();
                w.println(bm.getAddress() + "\t" + bm.getCategory() + "\t" + bm.getComment());
                nErr++;
            }
        }

        try (PrintWriter w = new PrintWriter(new FileWriter(new File(out, "summary.txt")))) {
            w.println("program            : " + currentProgram.getName());
            w.println("language           : " + currentProgram.getLanguageID() + " / " + currentProgram.getCompilerSpec().getCompilerSpecID());
            w.println("image base         : " + currentProgram.getImageBase());
            w.println("memory blocks      :");
            w.print(blocks);
            w.println("total bytes        : " + totalBytes);
            w.println("instructions       : " + nInstr);
            w.println("defined data items : " + nDefData);
            w.println("functions          : " + nFunc);
            w.println("bytes in functions : " + funcBytes + String.format("  (%.1f%% of image)", 100.0 * funcBytes / Math.max(totalBytes, 1)));
            w.println("undefined bytes inside function bodies : " + undefinedInFuncs);
            w.println("defined strings    : " + nStr);
            w.println("symbols (all)      : " + st.getNumSymbols());
            w.println("error bookmarks    : " + nErr + "  (Bad Instruction etc.; see errors.tsv)");
        }

        println("DumpFunctions: " + nFunc + " functions, " + nStr + " strings, " + nInstr + " instructions -> " + out);
    }
}
