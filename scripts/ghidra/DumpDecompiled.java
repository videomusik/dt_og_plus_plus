// Decompile selected functions to text files, with callers/callees, so code can be read and grepped
// outside the GUI.
//
// Run headless as a -postScript: first argument = output directory, then one or more selectors:
//   class:<Namespace>      every function whose parent namespace path contains <Namespace>
//   addr:<hex>             the function containing that address
//   re:<regex>             functions whose full name (namespace::name) matches
//   str:<regex>            functions that reference a defined string matching <regex>
//   callers:<hex>          functions that call the function at <hex>
//   xref:<hex>             functions containing any reference (data or code) to <hex>
//   mkfunc:<hex>           disassemble at <hex> and create a function there if there is none
//
// Output: <outdir>/decomp/index.tsv (address, size, name, selector, file; appended to) and one .c
// file per function: header comment (name, address, size, selector, callers, callees, referenced
// strings) followed by Ghidra's decompiled C.
//
// @category dt_og_plus_plus

import ghidra.app.decompiler.DecompInterface;
import ghidra.app.decompiler.DecompileOptions;
import ghidra.app.decompiler.DecompileResults;
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.ReferenceIterator;
import ghidra.program.model.symbol.ReferenceManager;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.*;
import java.util.regex.Pattern;

public class DumpDecompiled extends GhidraScript {

    private static String sane(String s) {
        String r = s.replaceAll("[^A-Za-z0-9_.:<>-]", "_").replace("::", ".").replaceAll("_+", "_");
        return r.length() > 120 ? r.substring(0, 120) : r;
    }

    private Function funcContaining(Address a) {
        return currentProgram.getFunctionManager().getFunctionContaining(a);
    }

    private String fullName(Function f) { return f.getName(true); }

    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length < 2) { printerr("usage: DumpDecompiled.java <outdir> <selector>..."); return; }
        File out = new File(args[0], "decomp");
        out.mkdirs();

        FunctionManager fm = currentProgram.getFunctionManager();
        Listing listing = currentProgram.getListing();
        ReferenceManager rm = currentProgram.getReferenceManager();

        // ---- select
        LinkedHashMap<Function, String> selected = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            String sel = args[i];
            int colon = sel.indexOf(':');
            if (colon < 0) { printerr("bad selector: " + sel); continue; }
            String kind = sel.substring(0, colon), val = sel.substring(colon + 1);
            switch (kind) {
                case "class": {
                    FunctionIterator it = fm.getFunctions(true);
                    while (it.hasNext()) {
                        Function f = it.next();
                        String ns = f.getParentNamespace().getName(true);
                        if (ns.equals(val) || ns.endsWith("::" + val) || ns.contains(val + "::") || ns.contains("::" + val + "::"))
                            selected.putIfAbsent(f, sel);
                    }
                    break;
                }
                case "addr": {
                    Function f = funcContaining(toAddr(Long.parseLong(val.replaceFirst("^0[xX]", ""), 16)));
                    if (f != null) selected.putIfAbsent(f, sel); else printerr("no function at " + val);
                    break;
                }
                case "re": {
                    Pattern p = Pattern.compile(val);
                    FunctionIterator it = fm.getFunctions(true);
                    while (it.hasNext()) { Function f = it.next(); if (p.matcher(fullName(f)).find()) selected.putIfAbsent(f, sel); }
                    break;
                }
                case "str": {
                    Pattern p = Pattern.compile(val);
                    DataIterator di = listing.getDefinedData(true);
                    while (di.hasNext()) {
                        Data d = di.next();
                        if (!d.hasStringValue()) continue;
                        Object v = d.getValue();
                        if (v == null || !p.matcher(v.toString()).find()) continue;
                        ReferenceIterator ri = rm.getReferencesTo(d.getAddress());
                        while (ri.hasNext()) {
                            Function f = funcContaining(ri.next().getFromAddress());
                            if (f != null) selected.putIfAbsent(f, sel + " [\"" + v + "\" @" + d.getAddress() + "]");
                        }
                    }
                    break;
                }
                case "mkfunc": {   // disassemble at the address, create a function there if none, select it
                    Address a = toAddr(Long.parseLong(val.replaceFirst("^0[xX]", ""), 16));
                    Function f = fm.getFunctionAt(a);
                    if (f == null) {
                        disassemble(a);
                        f = createFunction(a, "entry_" + a);
                    }
                    if (f != null) selected.putIfAbsent(f, sel); else printerr("could not create function at " + val);
                    break;
                }
                case "xref": {   // functions containing any reference to the given address (data or code)
                    Address a = toAddr(Long.parseLong(val.replaceFirst("^0[xX]", ""), 16));
                    ReferenceIterator ri = rm.getReferencesTo(a);
                    int cnt = 0;
                    while (ri.hasNext()) {
                        Function f = funcContaining(ri.next().getFromAddress());
                        if (f != null) { selected.putIfAbsent(f, sel); cnt++; }
                    }
                    if (cnt == 0) printerr("no references to " + val);
                    break;
                }
                case "callers": {
                    Address a = toAddr(Long.parseLong(val.replaceFirst("^0[xX]", ""), 16));
                    Function target = fm.getFunctionAt(a);
                    if (target == null) { printerr("no function at " + val); break; }
                    for (Function f : target.getCallingFunctions(monitor)) selected.putIfAbsent(f, sel);
                    break;
                }
                default: printerr("unknown selector kind: " + kind);
            }
        }
        println("DumpDecompiled: " + selected.size() + " functions selected");

        // ---- decompile
        DecompInterface ifc = new DecompInterface();
        ifc.setOptions(new DecompileOptions());
        ifc.toggleCCode(true);
        ifc.toggleSyntaxTree(false);
        ifc.setSimplificationStyle("decompile");
        if (!ifc.openProgram(currentProgram)) { printerr("decompiler failed to open program: " + ifc.getLastMessage()); return; }

        int ok = 0, failed = 0;
        try (PrintWriter idx = new PrintWriter(new FileWriter(new File(out, "index.tsv"), true))) {
            for (Map.Entry<Function, String> e : selected.entrySet()) {
                if (monitor.isCancelled()) break;
                Function f = e.getKey();
                String name = fullName(f);
                String fname = sane(name) + "_" + f.getEntryPoint() + ".c";

                StringBuilder hdr = new StringBuilder();
                hdr.append("// ").append(name).append("  @ ").append(f.getEntryPoint())
                   .append("  size ").append(f.getBody().getNumAddresses()).append(" B\n");
                hdr.append("// selected by: ").append(e.getValue()).append("\n");
                Set<Function> callers = f.getCallingFunctions(monitor), callees = f.getCalledFunctions(monitor);
                hdr.append("// callers (").append(callers.size()).append("): ");
                int n = 0; for (Function c : callers) { if (n++ >= 40) { hdr.append("..."); break; } hdr.append(fullName(c)).append('@').append(c.getEntryPoint()).append(' '); }
                hdr.append("\n// callees (").append(callees.size()).append("): ");
                n = 0; for (Function c : callees) { if (n++ >= 60) { hdr.append("..."); break; } hdr.append(fullName(c)).append('@').append(c.getEntryPoint()).append(' '); }
                hdr.append("\n// strings referenced: ");
                for (Address a : f.getBody().getAddresses(true)) {
                    Reference[] refs = rm.getReferencesFrom(a);
                    for (Reference r : refs) {
                        Data d = listing.getDefinedDataAt(r.getToAddress());
                        if (d != null && d.hasStringValue()) hdr.append('"').append(String.valueOf(d.getValue()).replace("\n", "\\n")).append("\" ");
                    }
                }
                hdr.append("\n\n");

                String body;
                DecompileResults res = ifc.decompileFunction(f, 120, monitor);
                if (res != null && res.decompileCompleted() && res.getDecompiledFunction() != null) {
                    body = res.getDecompiledFunction().getC(); ok++;
                } else {
                    body = "// DECOMPILE FAILED: " + (res == null ? "null" : res.getErrorMessage()) + "\n"; failed++;
                }
                try (PrintWriter w = new PrintWriter(new FileWriter(new File(out, fname)))) { w.print(hdr); w.print(body); }
                idx.println(f.getEntryPoint() + "\t" + f.getBody().getNumAddresses() + "\t" + name + "\t" + e.getValue() + "\t" + fname);
            }
        }
        ifc.dispose();
        println("DumpDecompiled: " + ok + " decompiled, " + failed + " failed -> " + out);
    }
}
