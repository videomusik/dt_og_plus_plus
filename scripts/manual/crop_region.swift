// Crop a pixel rectangle out of a PNG (used to extract vector/balloon diagrams from a full-page
// render). macOS system frameworks only.  crop_region <in.png> <x> <y> <w> <h> <out.png>
import Foundation
import CoreGraphics
import ImageIO

let a = CommandLine.arguments
guard a.count >= 7,
      let x = Int(a[2]), let y = Int(a[3]), let w = Int(a[4]), let h = Int(a[5]),
      let src = CGImageSourceCreateWithURL(URL(fileURLWithPath: a[1]) as CFURL, nil),
      let img = CGImageSourceCreateImageAtIndex(src, 0, nil) else {
    FileHandle.standardError.write("usage: crop_region <in.png> <x> <y> <w> <h> <out.png>\n".data(using:.utf8)!); exit(1)
}
let W = img.width, H = img.height
// clamp the rect to the image bounds
let cx = max(0, min(x, W-1)), cy = max(0, min(y, H-1))
let cw = max(1, min(w, W-cx)), ch = max(1, min(h, H-cy))
guard let crop = img.cropping(to: CGRect(x: cx, y: cy, width: cw, height: ch)),
      let dest = CGImageDestinationCreateWithURL(URL(fileURLWithPath: a[6]) as CFURL, "public.png" as CFString, 1, nil) else {
    FileHandle.standardError.write("crop failed\n".data(using:.utf8)!); exit(1)
}
CGImageDestinationAddImage(dest, crop, nil)
CGImageDestinationFinalize(dest)
print("cropped \(cw)x\(ch) @(\(cx),\(cy)) -> \(a[6])")
