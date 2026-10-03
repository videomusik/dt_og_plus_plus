#!/usr/bin/env python3
"""Turn the PDF outline (meta.json) into a chapter manifest: one entry per top-level bookmark with
its page range and a file-name slug. Writes <outdir>/chapters.json; reads nothing else.

    python3 scripts/manual/make_chapters.py <outdir>

Top-level bookmarks that have a page are sorted by page; each chapter ends one page before the next
starts. Pages before the first bookmark become a "00 FRONT MATTER" chapter. A PDF with no bookmarks
therefore gives a single front-matter chapter covering every page: split that one by hand.
"""
import json
import os
import re
import sys

if len(sys.argv) != 2:
    sys.exit("usage: make_chapters.py <outdir>")
outdir = sys.argv[1]
m = json.load(open(os.path.join(outdir, "meta.json")))
n = m["pageCount"]
tops = [e for e in m["outline"] if e["depth"] == 0 and e["page"] > 0]
tops.sort(key=lambda e: e["page"])


def slug(label):
    s = label.strip().lower()
    s = re.sub(r"[^a-z0-9]+", "_", s).strip("_")
    return s[:48]


chapters = []
first = tops[0]["page"] if tops else n + 1
if first > 1:
    chapters.append({"idx": "00", "title": "FRONT MATTER",
                     "slug": "front_matter", "start": 1, "end": first - 1})
for i, e in enumerate(tops):
    start = e["page"]
    end = (tops[i + 1]["page"] - 1) if i + 1 < len(tops) else n
    if end < start:
        end = start  # shared-page chapters (e.g. two headings on one page)
    chapters.append({"idx": f"{i+1:02d}", "title": e["label"].strip(),
                     "slug": slug(e["label"]), "start": start, "end": end})

json.dump({"pageCount": n, "chapters": chapters},
          open(os.path.join(outdir, "chapters.json"), "w"), indent=2)
print(f"{len(chapters)} chapters -> {outdir}/chapters.json")
for c in chapters:
    print(f"  {c['idx']}  p{c['start']:>3}-{c['end']:<3} ({c['end']-c['start']+1:>2}pp)  {c['title']}")
