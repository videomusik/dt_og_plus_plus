// Extract each embedded figure by CROPPING its rectangle from the already-rendered full page.
// Robust for every image encoding (1-bit / indexed / masked / jpeg) because it uses the composited
// render, not the raw stream. Placement comes from scanning the page content stream's CTM at each
// image `Do`. macOS system frameworks only.
//   crop_figures <pdf> <outdir> <dpi>   (dpi MUST match the pages/ render)
import Foundation
import CoreGraphics
import ImageIO

let args = CommandLine.arguments
guard args.count >= 4, let doc = CGPDFDocument(URL(fileURLWithPath: args[1]) as CFURL) else {
    FileHandle.standardError.write("usage: crop_figures <pdf> <outdir> <dpi>\n".data(using:.utf8)!); exit(1)
}
let outDir = args[2]
let dpi = Double(args[3]) ?? 170.0
let scale = CGFloat(dpi/72.0)
let imgDir = outDir + "/images"
try? FileManager.default.createDirectory(atPath: imgDir, withIntermediateDirectories: true)

final class State {
    var ctm = CGAffineTransform.identity
    var stack: [CGAffineTransform] = []
    var imageNames: Set<String> = []      // XObject names that are images on this page
    var placements: [(String, CGRect)] = [] // name, user-space bbox
}

func num(_ s: CGPDFScannerRef) -> CGFloat { var v: CGPDFReal = 0; CGPDFScannerPopNumber(s, &v); return CGFloat(v) }

let cb_q: CGPDFOperatorCallback = { s, info in
    let st = Unmanaged<State>.fromOpaque(info!).takeUnretainedValue(); st.stack.append(st.ctm)
}
let cb_Q: CGPDFOperatorCallback = { s, info in
    let st = Unmanaged<State>.fromOpaque(info!).takeUnretainedValue(); if let t = st.stack.popLast() { st.ctm = t }
}
let cb_cm: CGPDFOperatorCallback = { s, info in
    let st = Unmanaged<State>.fromOpaque(info!).takeUnretainedValue()
    let f = num(s), e = num(s), d = num(s), c = num(s), b = num(s), a = num(s)  // popped reverse
    st.ctm = CGAffineTransform(a: a, b: b, c: c, d: d, tx: e, ty: f).concatenating(st.ctm)
}
let cb_Do: CGPDFOperatorCallback = { s, info in
    let st = Unmanaged<State>.fromOpaque(info!).takeUnretainedValue()
    var namePtr: UnsafePointer<CChar>? = nil
    guard CGPDFScannerPopName(s, &namePtr), let np = namePtr else { return }
    let name = String(cString: np)
    guard st.imageNames.contains(name) else { return }
    let m = st.ctm
    let pts = [CGPoint(x:0,y:0), CGPoint(x:1,y:0), CGPoint(x:1,y:1), CGPoint(x:0,y:1)].map { $0.applying(m) }
    let xs = pts.map{$0.x}, ys = pts.map{$0.y}
    st.placements.append((name, CGRect(x: xs.min()!, y: ys.min()!,
                                       width: xs.max()!-xs.min()!, height: ys.max()!-ys.min()!)))
}

let table = CGPDFOperatorTableCreate()!
CGPDFOperatorTableSetCallback(table, "q", cb_q)
CGPDFOperatorTableSetCallback(table, "Q", cb_Q)
CGPDFOperatorTableSetCallback(table, "cm", cb_cm)
CGPDFOperatorTableSetCallback(table, "Do", cb_Do)

func loadPNG(_ path: String) -> CGImage? {
    guard let src = CGImageSourceCreateWithURL(URL(fileURLWithPath: path) as CFURL, nil) else { return nil }
    return CGImageSourceCreateImageAtIndex(src, 0, nil)
}
func writePNG(_ img: CGImage, _ path: String) -> Bool {
    guard let d = CGImageDestinationCreateWithURL(URL(fileURLWithPath: path) as CFURL, "public.png" as CFString, 1, nil) else { return false }
    CGImageDestinationAddImage(d, img, nil); return CGImageDestinationFinalize(d)
}

var manifest: [[String: Any]] = []
var ok = 0, miss = 0
let PAD: CGFloat = 6  // px padding around each crop

for i in 1...doc.numberOfPages {
    guard let page = doc.page(at: i), let pd = page.dictionary else { continue }
    let st = State()
    // collect image XObject names
    var res: CGPDFDictionaryRef?
    if CGPDFDictionaryGetDictionary(pd, "Resources", &res), let r = res {
        var xo: CGPDFDictionaryRef?
        if CGPDFDictionaryGetDictionary(r, "XObject", &xo), let x = xo {
            let namesBox = st
            let collect: CGPDFDictionaryApplierFunction = { key, obj, inf in
                let s2 = Unmanaged<State>.fromOpaque(inf!).takeUnretainedValue()
                var stream: CGPDFStreamRef?
                guard CGPDFObjectGetValue(obj, .stream, &stream), let str = stream,
                      let sd = CGPDFStreamGetDictionary(str) else { return }
                var sub: UnsafePointer<CChar>?
                if CGPDFDictionaryGetName(sd, "Subtype", &sub), let sp = sub, String(cString: sp) == "Image" {
                    s2.imageNames.insert(String(cString: key))
                }
            }
            CGPDFDictionaryApplyFunction(x, collect, Unmanaged.passUnretained(namesBox).toOpaque())
        }
    }
    if st.imageNames.isEmpty { continue }
    // scan content stream for placements
    let cs = CGPDFContentStreamCreateWithPage(page)
    let scanner = CGPDFScannerCreate(cs, table, Unmanaged.passUnretained(st).toOpaque())
    CGPDFScannerScan(scanner)
    CGPDFScannerRelease(scanner); CGPDFContentStreamRelease(cs)
    if st.placements.isEmpty { continue }

    let box = page.getBoxRect(.mediaBox)
    guard let pageImg = loadPNG(String(format: "%@/pages/page-%03d.png", outDir, i)) else { miss += st.placements.count; continue }
    let W = CGFloat(pageImg.width), H = CGFloat(pageImg.height)
    var k = 0
    for (name, urect) in st.placements {
        k += 1
        // user-space -> page pixels (top-left origin), matching render_pdf's transform + flip
        let x0 = (urect.minX - box.minX) * scale
        let x1 = (urect.maxX - box.minX) * scale
        let yTop = H - (urect.maxY - box.minY) * scale
        let yBot = H - (urect.minY - box.minY) * scale
        var px = CGRect(x: x0 - PAD, y: yTop - PAD, width: (x1-x0) + 2*PAD, height: (yBot-yTop) + 2*PAD)
        px = px.intersection(CGRect(x: 0, y: 0, width: W, height: H))
        if px.width < 8 || px.height < 8 { continue }
        guard let crop = pageImg.cropping(to: px) else { continue }
        let fname = String(format: "p%03d_%@_%d.png", i, name, k)
        if writePNG(crop, "\(imgDir)/\(fname)") {
            ok += 1
            manifest.append(["page": i, "file": "images/\(fname)", "w": crop.width, "h": crop.height,
                             "x": Int(px.minX), "y": Int(px.minY)])
        }
    }
    FileHandle.standardError.write("p\(i): \(st.placements.count) figures\n".data(using:.utf8)!)
}
// manifest grouped for the subagents
var byPage: [String: [[String: Any]]] = [:]
for m in manifest { byPage[String(format: "%03d", m["page"] as! Int), default: []].append(m) }
if let jd = try? JSONSerialization.data(withJSONObject: byPage, options: [.prettyPrinted, .sortedKeys]) {
    try? jd.write(to: URL(fileURLWithPath: outDir + "/figures.json"))
}
// Plain-text sibling so subagents can `grep '^NNN '` for a page's boxes — no JSON parsing, no python.
let lines = manifest
    .sorted { (($0["page"] as! Int), ($0["file"] as! String)) < (($1["page"] as! Int), ($1["file"] as! String)) }
    .map { String(format: "%03d %@ %d %d %d %d",
                  $0["page"] as! Int, $0["file"] as! String,
                  $0["x"] as! Int, $0["y"] as! Int, $0["w"] as! Int, $0["h"] as! Int) }
try? ("# page file x y w h  (embedded raster crops; vector diagrams are NOT listed here)\n"
      + lines.joined(separator: "\n") + "\n").write(toFile: outDir + "/figures.txt", atomically: true, encoding: .utf8)
print("cropped \(ok) figures (missed \(miss)) -> \(imgDir); manifests figures.json + figures.txt")
