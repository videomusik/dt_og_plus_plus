// DIAGNOSTIC PROBE, not part of the pipeline.
// Diagnose embedded image encodings, to see why native extraction (extract_images) skips them.
// Per image: page, key, WxH, data-format (raw/jpeg/jpx), colorspace, bpc, hasSMask; then a tally.
//   diag_images <pdf>
// Compile with: swiftc -O scripts/manual/diag_images.swift -o work/bin/diag_images
import Foundation
import CoreGraphics

let args = CommandLine.arguments
guard args.count >= 2, let doc = CGPDFDocument(URL(fileURLWithPath: args[1]) as CFURL) else { exit(1) }
var current = 0
var tally: [String: Int] = [:]
var shown = 0

func csDesc(_ sdict: CGPDFDictionaryRef) -> String {
    var obj: CGPDFObjectRef?
    guard CGPDFDictionaryGetObject(sdict, "ColorSpace", &obj), let o = obj else { return "none" }
    var name: UnsafePointer<CChar>?
    if CGPDFObjectGetValue(o, .name, &name), let n = name { return String(cString: n) }
    var arr: CGPDFArrayRef?
    if CGPDFObjectGetValue(o, .array, &arr), let a = arr, CGPDFArrayGetCount(a) >= 1 {
        var head: UnsafePointer<CChar>?
        if CGPDFArrayGetName(a, 0, &head), let h = head { return "[" + String(cString: h) + " …]" }
        return "[array]"
    }
    return "other"
}

let cb: CGPDFDictionaryApplierFunction = { (key, obj, _) in
    var stream: CGPDFStreamRef?
    guard CGPDFObjectGetValue(obj, .stream, &stream), let st = stream,
          let sdict = CGPDFStreamGetDictionary(st) else { return }
    var subtype: UnsafePointer<CChar>?
    guard CGPDFDictionaryGetName(sdict, "Subtype", &subtype), let sp = subtype,
          String(cString: sp) == "Image" else { return }
    var w = 0, h = 0, bpc = 8
    CGPDFDictionaryGetInteger(sdict, "Width", &w); CGPDFDictionaryGetInteger(sdict, "Height", &h)
    CGPDFDictionaryGetInteger(sdict, "BitsPerComponent", &bpc)
    var fmt = CGPDFDataFormat.raw
    _ = CGPDFStreamCopyData(st, &fmt)
    let fmtS = fmt == .jpegEncoded ? "jpeg" : (fmt == .JPEG2000 ? "jpx" : "raw")
    let cs = csDesc(sdict)
    var sm: CGPDFStreamRef?
    let hasMask = CGPDFDictionaryGetStream(sdict, "SMask", &sm)
    tally["\(fmtS) | \(cs) | bpc\(bpc) | smask=\(hasMask)", default: 0] += 1
    if shown < 30 { FileHandle.standardError.write("p\(current) \(String(cString:key)) \(w)x\(h) \(fmtS) \(cs) bpc\(bpc) smask=\(hasMask)\n".data(using:.utf8)!); shown += 1 }
}
for i in 1...doc.numberOfPages {
    guard let page = doc.page(at: i), let pd = page.dictionary else { continue }
    current = i
    var res: CGPDFDictionaryRef?
    if CGPDFDictionaryGetDictionary(pd, "Resources", &res), let r = res {
        var xo: CGPDFDictionaryRef?
        if CGPDFDictionaryGetDictionary(r, "XObject", &xo), let x = xo { CGPDFDictionaryApplyFunction(x, cb, nil) }
    }
}
print("\n=== encoding tally (count | format | colorspace | bpc | smask) ===")
for (k, v) in tally.sorted(by: { $0.value > $1.value }) { print("  \(v)  \(k)") }
