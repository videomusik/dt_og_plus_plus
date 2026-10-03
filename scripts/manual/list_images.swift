// DIAGNOSTIC PROBE, not part of the pipeline.
// List embedded raster image XObjects per page (name, pixel size). Tells whether a manual's figures
// are extractable embedded rasters or vector diagrams. macOS CoreGraphics only.
//   list_images <pdf>
// Compile with: swiftc -O scripts/manual/list_images.swift -o work/bin/list_images
import Foundation
import CoreGraphics

var current = 0
var rows: [(Int, String, Int, Int)] = []

let cb: CGPDFDictionaryApplierFunction = { (key, obj, _) in
    var stream: CGPDFStreamRef?
    guard CGPDFObjectGetValue(obj, .stream, &stream), let st = stream,
          let sdict = CGPDFStreamGetDictionary(st) else { return }
    var subtype: UnsafePointer<CChar>?
    guard CGPDFDictionaryGetName(sdict, "Subtype", &subtype), let sp = subtype,
          String(cString: sp) == "Image" else { return }
    var w = 0, h = 0
    CGPDFDictionaryGetInteger(sdict, "Width", &w)
    CGPDFDictionaryGetInteger(sdict, "Height", &h)
    rows.append((current, String(cString: key), w, h))
}

let args = CommandLine.arguments
guard args.count >= 2, let doc = CGPDFDocument(URL(fileURLWithPath: args[1]) as CFURL) else {
    FileHandle.standardError.write("usage: list_images <pdf>\n".data(using: .utf8)!); exit(1)
}
for i in 1...doc.numberOfPages {
    guard let page = doc.page(at: i), let pdict = page.dictionary else { continue }
    current = i
    var res: CGPDFDictionaryRef?
    if CGPDFDictionaryGetDictionary(pdict, "Resources", &res), let r = res {
        var xobj: CGPDFDictionaryRef?
        if CGPDFDictionaryGetDictionary(r, "XObject", &xobj), let x = xobj {
            CGPDFDictionaryApplyFunction(x, cb, nil)
        }
    }
}
var perPage: [Int: Int] = [:]
for r in rows { perPage[r.0, default: 0] += 1 }
print("pages with embedded images: \(perPage.count) of \(doc.numberOfPages); total images: \(rows.count)")
print("per-page count (page:images):", perPage.sorted{ $0.key < $1.key }.map{ "\($0.key):\($0.value)" }.joined(separator: " "))
print("\nsample (first 40 images — page, name, WxH px):")
for r in rows.prefix(40) { print("  p\(r.0)  \(r.1)  \(r.2)x\(r.3)") }
