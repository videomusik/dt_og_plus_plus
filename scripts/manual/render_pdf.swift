// Render a PDF to per-page PNGs + per-page text-layer files + an outline/meta JSON, using only
// macOS system frameworks (PDFKit + CoreGraphics + ImageIO): no third-party tools, no network.
// Compiled and invoked by scripts/manual_extract.sh.
//   render_pdf <pdf> <outdir> [dpi]
// Output: <outdir>/pages/page-NNN.png, <outdir>/text/page-NNN.txt, <outdir>/meta.json
// meta.json records the page count, the dpi, the outline (PDF bookmarks), and the pixel size of every
// page render ("pageSizes", one [width, height] per page; "pagePixels" = page 1's), so the phase-2
// instructions never need to assume a paper size.
import Foundation
import PDFKit
import CoreGraphics
import ImageIO

func die(_ m: String) -> Never {
    FileHandle.standardError.write((m + "\n").data(using: .utf8)!); exit(1)
}
func log(_ m: String) {
    FileHandle.standardError.write((m + "\n").data(using: .utf8)!)
}

let args = CommandLine.arguments
guard args.count >= 3 else { die("usage: render_pdf <pdf> <outdir> [dpi]") }
let pdfPath = args[1], outDir = args[2]
let dpi = args.count >= 4 ? (Double(args[3]) ?? 170.0) : 170.0
guard let doc = PDFDocument(url: URL(fileURLWithPath: pdfPath)) else { die("cannot open PDF: \(pdfPath)") }

let n = doc.pageCount
let fm = FileManager.default
let imgDir = outDir + "/pages", txtDir = outDir + "/text"   // full-page renders = the visual source
try? fm.createDirectory(atPath: imgDir, withIntermediateDirectories: true)
try? fm.createDirectory(atPath: txtDir, withIntermediateDirectories: true)

let scale = CGFloat(dpi / 72.0)
func pad(_ i: Int) -> String { String(format: "%03d", i) }
var pageSizes: [[Int]] = []

for i in 0..<n {
    guard let page = doc.page(at: i) else { pageSizes.append([0, 0]); continue }
    let text = page.string ?? ""
    try? text.write(toFile: txtDir + "/page-\(pad(i+1)).txt", atomically: true, encoding: .utf8)

    guard let cgPage = page.pageRef else { log("page \(i+1): no pageRef"); pageSizes.append([0, 0]); continue }
    let box = cgPage.getBoxRect(.mediaBox)
    let w = Int(box.width * scale), h = Int(box.height * scale)
    pageSizes.append([w, h])
    guard w > 0, h > 0, let cs = CGColorSpace(name: CGColorSpace.sRGB),
          let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8,
                              bytesPerRow: 0, space: cs,
                              bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else {
        log("page \(i+1): no context"); continue
    }
    ctx.setFillColor(gray: 1, alpha: 1)
    ctx.fill(CGRect(x: 0, y: 0, width: w, height: h))
    ctx.scaleBy(x: scale, y: scale)
    ctx.translateBy(x: -box.origin.x, y: -box.origin.y)
    ctx.drawPDFPage(cgPage)
    guard let img = ctx.makeImage() else { log("page \(i+1): no image"); continue }
    let outURL = URL(fileURLWithPath: imgDir + "/page-\(pad(i+1)).png") as CFURL
    if let dest = CGImageDestinationCreateWithURL(outURL, "public.png" as CFString, 1, nil) {
        CGImageDestinationAddImage(dest, img, nil)
        CGImageDestinationFinalize(dest)
    }
    if (i+1) % 10 == 0 || i+1 == n { log("rendered \(i+1)/\(n)") }
}

// Outline (PDF bookmarks) -> flat list with 1-based page numbers.
var outline: [[String: Any]] = []
func walk(_ node: PDFOutline, _ depth: Int) {
    for j in 0..<node.numberOfChildren {
        guard let c = node.child(at: j) else { continue }
        var pg = -1
        if let p = c.destination?.page { pg = doc.index(for: p) + 1 }
        outline.append(["label": c.label ?? "", "page": pg, "depth": depth])
        walk(c, depth + 1)
    }
}
if let root = doc.outlineRoot { walk(root, 0) }
let first = pageSizes.first ?? [0, 0]
let meta: [String: Any] = ["pageCount": n, "dpi": dpi, "source": (pdfPath as NSString).lastPathComponent,
                           "outline": outline,
                           "pagePixels": ["width": first[0], "height": first[1]],
                           "pageSizes": pageSizes]
if let jd = try? JSONSerialization.data(withJSONObject: meta, options: [.prettyPrinted, .sortedKeys]) {
    try? jd.write(to: URL(fileURLWithPath: outDir + "/meta.json"))
}
print("done: \(n) pages (page 1 = \(first[0])x\(first[1]) px), \(outline.count) outline entries -> \(outDir)")
