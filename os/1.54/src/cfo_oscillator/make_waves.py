#!/usr/bin/env python3
"""The CFO oscillator's four wavetables, 256 signed 8-bit entries each, in morph order SIN, TRI, SAW, SQR,
written as an assembler include (`.byte` lines) to the path given, or to stdout.

One phase convention for all four, so that a morph between neighbours blends shapes rather than
cancelling them:
- index 0 is a zero crossing, rising (SIN, TRI, SAW) or the start of the high half (SQR);
- SIN and TRI peak together at index 64 and bottom out together at 192;
- SAW and SQR have their jumps at index 128 (and SQR at 0), the middle of the table;
- every table has a mean of 0 or -0.5 (SAW's -128..127 range).

usage: make_waves.py [out.inc]"""
import math, sys


def tables():
    sin = [round(127 * math.sin(2 * math.pi * i / 256)) for i in range(256)]
    tri = []
    for i in range(256):
        if i < 64:
            v = 127 * i / 64
        elif i < 192:
            v = 127 - 254 * (i - 64) / 128
        else:
            v = -127 + 127 * (i - 192) / 64
        tri.append(round(v))
    saw = [i if i < 128 else i - 256 for i in range(256)]
    sqr = [127 if i < 128 else -127 for i in range(256)]
    return [("SIN", sin), ("TRI", tri), ("SAW", saw), ("SQR", sqr)]


def main():
    out = []
    for name, t in tables():
        assert len(t) == 256 and all(-128 <= v <= 127 for v in t)
        out.append(f"| {name}")
        for i in range(0, 256, 16):
            out.append("\t.byte\t" + ",".join(str(v) for v in t[i:i + 16]))
    text = "\n".join(out) + "\n"
    if len(sys.argv) > 1:
        open(sys.argv[1], "w").write(text)
    else:
        sys.stdout.write(text)


if __name__ == "__main__":
    main()
