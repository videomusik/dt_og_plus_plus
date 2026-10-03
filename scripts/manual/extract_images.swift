// DIAGNOSTIC PROBE, not part of the pipeline (crop_figures.swift is what the pipeline uses).
// Extract every embedded image XObject from a PDF to PNG files, pixel-perfect (no rasterising the
// whole page). Handles JPEG/JPEG2000 (via ImageIO) and raw gray/RGB samples (DeviceGray/RGB,
// CalGray/RGB, ICCBased with N = 1 or 3). On the reference Digitakt manual it recovers only a small
// fraction of the figures (most are 1-bit line art or indexed-palette images), which is why the
// pipeline crops figures from the page renders instead.
// macOS system frameworks only.  extract_images <pdf> <imgdir>
// Writes its manifest to <imgdir>/../extracted_images.json, so it never overwrites the pipeline's
// figures.json. Compile with: swiftc -O scripts/manual/extract_images.swift -o work/bin/extract_images
import Foundation
import CoreGraphics
import ImageIO

let args = CommandLine.arguments
guard args.count >= 3, let doc = CGPDFDocument(URL(fileURLWithPath: args[1]) as CFURL) else {
    FileHandle.standardError.write("usage: extract_images <pdf> <imgdir>\n".data(using:.utf8)!); exit(1)
}
let imgDir = args[2]
try? FileManager.default.createDirectory(atPath: imgDir, withIntermediateDirectories: true)

func writePNG(_ img: CGImage, _ path: String) -> Bool {
    guard let dest = CGImageDestinationCreateWithURL(URL(fileURLWithPath: path) as CFURL,
                                                     "public.png" as CFString, 1, nil) else { return false }
    CGImageDestinationAddImage(dest, img, nil)
    return CGImageDestinationFinalize(dest)
}

// components for a raw image's ColorSpace object
func components(_ sdict: CGPDFDictionaryRef) -> Int {
    var obj: CGPDFObjectRef?
    guard CGPDFDictionaryGetObject(sdict, "ColorSpace", &obj), let o = obj else { return 0 }
    var name: UnsafePointer<CChar>?
    if CGPDFObjectGetValue(o, .name, &name), let n = name {
        switch String(cString: n) {
        case "DeviceRGB", "CalRGB": return 3
        case "DeviceGray", "CalGray": return 1
        case "DeviceCMYK": return 4
        default: return 0
        }
    }
    var arr: CGPDFArrayRef?
    if CGPDFObjectGetValue(o, .array, &arr), let a = arr, CGPDFArrayGetCount(a) >= 2 {
        var head: UnsafePointer<CChar>?
        if CGPDFArrayGetName(a, 0, &head), let h = head, String(cString: h) == "ICCBased" {
            var st: CGPDFStreamRef?
            if CGPDFArrayGetStream(a, 1, &st), let s = st, let d = CGPDFStreamGetDictionary(s) {
                var nComp = 0; CGPDFDictionaryGetInteger(d, "N", &nComp); return nComp
            }
        }
    }
    return 0
}

var current = 0, ok = 0, skip = 0
var manifest: [[String: Any]] = []

let cb: CGPDFDictionaryApplierFunction = { (key, obj, _) in
    var stream: CGPDFStreamRef?
    guard CGPDFObjectGetValue(obj, .stream, &stream), let st = stream,
          let sdict = CGPDFStreamGetDictionary(st) else { return }
    var subtype: UnsafePointer<CChar>?
    guard CGPDFDictionaryGetName(sdict, "Subtype", &subtype), let sp = subtype,
          String(cString: sp) == "Image" else { return }
    var w = 0, h = 0, bpc = 8
    CGPDFDictionaryGetInteger(sdict, "Width", &w)
    CGPDFDictionaryGetInteger(sdict, "Height", &h)
    CGPDFDictionaryGetInteger(sdict, "BitsPerComponent", &bpc)
    let keyStr = String(cString: key)
    let name = String(format: "p%03d_%@", current, keyStr)
    let path = "\(imgDir)/\(name).png"

    var fmt = CGPDFDataFormat.raw
    guard let cf = CGPDFStreamCopyData(st, &fmt) else { skip += 1; return }
    let data = cf as Data
    var made: CGImage? = nil

    if fmt == .jpegEncoded || fmt == .JPEG2000 {
        if let src = CGImageSourceCreateWithData(data as CFData, nil) {
            made = CGImageSourceCreateImageAtIndex(src, 0, nil)
        }
    } else { // raw samples
        let comp = components(sdict)
        if comp == 1 || comp == 3 {
            let cs = comp == 1 ? CGColorSpaceCreateDeviceGray() : CGColorSpaceCreateDeviceRGB()
            let bpp = comp * bpc
            let bpr = (w * bpp + 7) / 8
            if data.count >= bpr * h, let prov = CGDataProvider(data: cf) {
                made = CGImage(width: w, height: h, bitsPerComponent: bpc, bitsPerPixel: bpp,
                               bytesPerRow: bpr, space: cs,
                               bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.none.rawValue),
                               provider: prov, decode: nil, shouldInterpolate: false,
                               intent: .defaultIntent)
            }
        }
    }
    if let m = made, writePNG(m, path) {
        ok += 1
        manifest.append(["page": current, "key": keyStr, "file": "images/\(name).png",
                         "w": m.width, "h": m.height])
    } else {
        skip += 1
        FileHandle.standardError.write("skip p\(current) \(keyStr) \(w)x\(h) fmt=\(fmt.rawValue)\n".data(using:.utf8)!)
    }
}

for i in 1...doc.numberOfPages {
    guard let page = doc.page(at: i), let pdict = page.dictionary else { continue }
    current = i
    var res: CGPDFDictionaryRef?
    if CGPDFDictionaryGetDictionary(pdict, "Resources", &res), let r = res {
        var xo: CGPDFDictionaryRef?
        if CGPDFDictionaryGetDictionary(r, "XObject", &xo), let x = xo {
            CGPDFDictionaryApplyFunction(x, cb, nil)
        }
    }
}
// write manifest grouped per page
var byPage: [String: [[String: Any]]] = [:]
for m in manifest { byPage[String(m["page"] as! Int), default: []].append(m) }
if let jd = try? JSONSerialization.data(withJSONObject: ["images": manifest], options: [.prettyPrinted, .sortedKeys]) {
    try? jd.write(to: URL(fileURLWithPath: "\(imgDir)/../extracted_images.json"))
}
print("extracted \(ok) images, skipped \(skip) -> \(imgDir) (manifest: extracted_images.json)")
