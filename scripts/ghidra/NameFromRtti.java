// Recover C++ class structure from GCC/Itanium RTTI in a raw big-endian image and name things.
//
// Chain used (Itanium C++ ABI, as emitted by GCC):
//   typeinfo name string  <-  std::type_info object  {vptr, name_ptr[, base info...]}
//                          <-  vtable {offset_to_top, typeinfo_ptr, vfunc0, vfunc1, ...}
// For every class found: label the typeinfo object and vtable, put the class in a namespace, and
// name each virtual function slot's target "vfunc_<i>" inside that namespace (only functions that
// still carry a default FUN_ name are renamed; shared targets get a comment instead).
//
// Run headless as a -postScript with one argument: the output directory.
//   analyzeHeadless ... -postScript NameFromRtti.java work/ghidra/out/<image>
// Output: rtti_classes.tsv  (typeinfo, vtable(s), vfunc count, class name, base classes)
//
// @category dt_og_plus_plus

import ghidra.app.script.GhidraScript;
import ghidra.app.util.demangler.DemangledObject;
import ghidra.app.util.demangler.DemanglerUtil;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.symbol.Namespace;
import ghidra.program.model.symbol.SourceType;
import ghidra.program.model.symbol.SymbolTable;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.*;

public class NameFromRtti extends GhidraScript {

    private long base;
    private int len;
    private byte[] img;

    private long be32(int off) {
        return ((img[off] & 0xffL) << 24) | ((img[off + 1] & 0xffL) << 16) | ((img[off + 2] & 0xffL) << 8) | (img[off + 3] & 0xffL);
    }
    private boolean inImage(long a) { return a >= base && a < base + len; }
    private int off(long a) { return (int) (a - base); }
    private Address addr(long a) { return currentProgram.getAddressFactory().getDefaultAddressSpace().getAddress(a); }

    /** C string at image offset, or null if not a plausible mangled type name. */
    private String nameAt(int o) {
        StringBuilder sb = new StringBuilder();
        for (int i = o; i < len && i - o < 400; i++) {
            int c = img[i] & 0xff;
            if (c == 0) break;
            if (c < 0x20 || c > 0x7e) return null;
            sb.append((char) c);
        }
        String s = sb.toString();
        if (s.length() < 2) return null;
        // GCC typeinfo names: "*" prefix for non-unique names; then a <type> encoding
        String m = s.startsWith("*") ? s.substring(1) : s;
        if (!m.matches("^(Z|N|St|[0-9]+[A-Za-z_]).*")) return null;
        return s;
    }

    /** Best-effort demangle of a typeinfo name string to a readable class name. */
    private String readable(String tiName) {
        String m = tiName.startsWith("*") ? tiName.substring(1) : tiName;
        // Simple forms first (no demangler dependency): "15Foo" and "N8Ns3FooE"
        try {
            if (m.matches("^[0-9]+[A-Za-z_][A-Za-z0-9_]*$")) {
                int n = Integer.parseInt(m.replaceAll("^([0-9]+).*$", "$1"));
                String rest = m.replaceAll("^[0-9]+", "");
                if (rest.length() == n) return rest;
            }
            if (m.matches("^N([0-9]+[A-Za-z_][A-Za-z0-9_]*)+E$")) {
                String body = m.substring(1, m.length() - 1);
                StringBuilder out = new StringBuilder();
                int i = 0;
                while (i < body.length()) {
                    int j = i;
                    while (j < body.length() && Character.isDigit(body.charAt(j))) j++;
                    int n = Integer.parseInt(body.substring(i, j));
                    if (out.length() > 0) out.append("::");
                    out.append(body, j, j + n);
                    i = j + n;
                }
                return out.toString();
            }
        } catch (Exception e) { /* fall through */ }
        // General case: hand it to Ghidra's GNU demangler as a symbol name
        String mangled = m.startsWith("Z") ? "_" + m : "_Z" + m;
        try {
            List<DemangledObject> r = DemanglerUtil.demangle(currentProgram, mangled, null);
            if (r != null && !r.isEmpty()) {
                DemangledObject d = r.get(0);
                String ns = d.getNamespaceString();
                String nm = d.getName();
                // A bare type name demangled as a symbol comes back "constructor-like":
                // namespace == the class, name == the class again. Keep one copy.
                String full;
                if (ns == null || ns.isEmpty()) full = nm;
                else if (ns.endsWith(nm) || ns.endsWith("::" + nm)) full = ns;
                else full = ns + "::" + nm;
                if (full != null && !full.isEmpty()) return full;
            }
        } catch (Throwable t) { /* fall through */ }
        return m;
    }

    private static String sane(String part) {
        String s = part.replaceAll("[\\s]", "_").replaceAll("[<>,*&()\\[\\]{}#']", "_").replaceAll("_+", "_");
        if (s.isEmpty()) s = "_";
        return s;
    }

    private Namespace namespaceFor(String readableName) throws Exception {
        SymbolTable st = currentProgram.getSymbolTable();
        Namespace ns = currentProgram.getGlobalNamespace();
        for (String part : readableName.split("::")) {
            ns = st.getOrCreateNameSpace(ns, sane(part), SourceType.ANALYSIS);
        }
        return ns;
    }

    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length < 1) { printerr("usage: NameFromRtti.java <outdir>"); return; }
        File out = new File(args[0]);
        out.mkdirs();

        MemoryBlock blk = currentProgram.getMemory().getBlocks()[0];
        base = blk.getStart().getOffset();
        len = (int) blk.getSize();
        img = new byte[len];
        blk.getBytes(blk.getStart(), img);

        Listing listing = currentProgram.getListing();
        SymbolTable st = currentProgram.getSymbolTable();

        // ---- 1. candidate type_info objects: {vptr in image, name_ptr -> mangled name string}
        Map<Long, Integer> vptrCount = new HashMap<>();
        List<int[]> cands = new ArrayList<>();          // [offset]
        for (int o = 0; o + 8 <= len; o += 2) {
            long vptr = be32(o), namep = be32(o + 4);
            if (!inImage(vptr) || !inImage(namep)) continue;
            if (nameAt(off(namep)) == null) continue;
            cands.add(new int[]{o});
            vptrCount.merge(vptr, 1, Integer::sum);
        }
        // genuine type_info objects share a handful of vptrs (__class_type_info & friends)
        Set<Long> tiVptrs = new HashSet<>();
        for (Map.Entry<Long, Integer> e : vptrCount.entrySet()) if (e.getValue() >= 5) tiVptrs.add(e.getKey());
        println("type_info vptr candidates (count>=5): " + tiVptrs.size() + " of " + vptrCount.size());

        Map<Long, String> tiName = new LinkedHashMap<>();   // typeinfo addr -> mangled name
        for (int[] c : cands) {
            int o = c[0];
            if (!tiVptrs.contains(be32(o))) continue;
            tiName.put(base + o, nameAt(off(be32(o + 4))));
        }
        println("type_info objects: " + tiName.size());

        // ---- 2. index every 4-byte value that equals a typeinfo address -> vtable candidates
        Map<Long, List<Integer>> refsToTi = new HashMap<>();
        for (int o = 0; o + 4 <= len; o += 2) {
            long v = be32(o);
            if (tiName.containsKey(v)) refsToTi.computeIfAbsent(v, k -> new ArrayList<>()).add(o);
        }

        int nVtables = 0, nRenamed = 0, nShared = 0, nCreated = 0;
        try (PrintWriter w = new PrintWriter(new FileWriter(new File(out, "rtti_classes.tsv")))) {
            w.println("typeinfo\tclass\tmangled\tvtables\tvfuncs_primary\tbases");
            for (Map.Entry<Long, String> e : tiName.entrySet()) {
                long ti = e.getKey();
                String mangled = e.getValue();
                String cls = readable(mangled);
                Namespace ns = namespaceFor(cls);
                try { st.createLabel(addr(ti), "typeinfo", ns, SourceType.ANALYSIS); } catch (Exception ex) { /* ignore */ }

                // base classes: __si_class_type_info has base at +8; __vmi has count at +12, bases at +16 step 8
                List<String> bases = new ArrayList<>();
                int to = off(ti);
                if (to + 12 <= len && tiName.containsKey(be32(to + 8))) {
                    bases.add(readable(tiName.get(be32(to + 8))));
                } else if (to + 16 <= len) {
                    long cnt = be32(to + 12);
                    if (cnt >= 1 && cnt <= 8) {
                        for (int k = 0; k < cnt && to + 16 + 8 * k + 4 <= len; k++) {
                            long b = be32(to + 16 + 8 * k);
                            if (tiName.containsKey(b)) bases.add(readable(tiName.get(b)));
                        }
                    }
                }

                // vtables: typeinfo pointer at P, offset_to_top at P-4, vfuncs from P+4
                List<Integer> refs = refsToTi.getOrDefault(ti, Collections.emptyList());
                StringBuilder vtl = new StringBuilder();
                int primaryVf = 0, sec = 0;
                for (int p : refs) {
                    if (p < 4) continue;
                    long ott = be32(p - 4);
                    boolean primary = (ott == 0);
                    boolean secondary = ((ott & 0xff000000L) == 0xff000000L);   // small negative
                    if (!primary && !secondary) continue;                        // e.g. the typeinfo's own base pointer
                    // first slot must look like code
                    if (p + 8 > len) continue;
                    long f0 = be32(p + 4);
                    if (!inImage(f0) || (f0 & 1) != 0) continue;
                    if (listing.getInstructionAt(addr(f0)) == null && listing.getFunctionAt(addr(f0)) == null) continue;

                    nVtables++;
                    String vtLabel = primary ? "vtable" : ("vtable_sec" + (++sec));
                    try { st.createLabel(addr(base + p - 4), vtLabel, ns, SourceType.ANALYSIS); } catch (Exception ex) { /* ignore */ }
                    if (vtl.length() > 0) vtl.append(' ');
                    vtl.append(String.format("%08x%s", base + p - 4, primary ? "" : "(sec)"));

                    // walk slots
                    int i = 0;
                    for (int q = p + 4; q + 4 <= len && i < 512; q += 4, i++) {
                        long f = be32(q);
                        if (f == 0) break;                                   // next vtable's offset_to_top
                        if (tiName.containsKey(be32(q + 4 <= len - 4 ? q + 4 : q)) && f == 0) break;
                        if (!inImage(f) || (f & 1) != 0) break;
                        Address fa = addr(f);
                        Function fn = listing.getFunctionAt(fa);
                        if (fn == null) {
                            if (listing.getInstructionAt(fa) == null) break;   // not code: end of table
                            fn = createFunction(fa, null);
                            if (fn == null) break;
                            nCreated++;
                        }
                        if (!primary) continue;                              // only name from primary vtables
                        String cur = fn.getName();
                        if (cur.startsWith("FUN_") || cur.startsWith("thunk_FUN_")) {
                            try {
                                fn.setParentNamespace(ns);
                                fn.setName("vfunc_" + i, SourceType.ANALYSIS);
                                nRenamed++;
                            } catch (Exception ex) { /* duplicate name etc. */ }
                        } else if (!fn.getParentNamespace().equals(ns)) {
                            String c = listing.getComment(ghidra.program.model.listing.CodeUnit.PLATE_COMMENT, fa);
                            String add = "also " + cls + "::vfunc_" + i;
                            if (c == null || !c.contains(add)) {
                                listing.setComment(fa, ghidra.program.model.listing.CodeUnit.PLATE_COMMENT, (c == null ? "" : c + "\n") + add);
                            }
                            nShared++;
                        }
                    }
                    if (primary) primaryVf = Math.max(primaryVf, i);
                }
                w.println(String.format("%08x\t%s\t%s\t%s\t%d\t%s", ti, cls, mangled, vtl, primaryVf, String.join(" | ", bases)));
            }
        }
        println(String.format("NameFromRtti: %d classes, %d vtables, %d functions renamed, %d created, %d shared-slot comments -> %s",
                tiName.size(), nVtables, nRenamed, nCreated, nShared, out));
    }
}
