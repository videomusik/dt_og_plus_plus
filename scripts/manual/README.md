# Runbook: PDF manual → greppable markdown, with your own agent

This turns a PDF manual you own into a per-chapter markdown corpus you can `grep` and read: every
parameter, value range, menu path, procedure and key combination, plus every content figure cropped
to its own PNG and described in its alt-text. It gives you a searchable Digitakt manual to keep beside
the firmware notes ([`notes/README.md`](../../notes/README.md)), which contain no manual text. Phase 1
is one script; phase 2 needs an agent harness that can run parallel sub-agents and read images. The
toolchain overview is [`docs/toolchain.md`](../../docs/toolchain.md), section 6.

**Bring your own manual.** Download the PDF from Elektron's support page and put it in `manuals/`.
The output is derived from Elektron's manual: it lands under `work/manuals/<name>/`, both folders are
gitignored, and it must never be committed or shared. Only this procedure is part of the repo.

## Requirements

- **macOS only.** The phase-1 tools are Swift programs using the system frameworks PDFKit,
  CoreGraphics and ImageIO. `swiftc` comes with the Xcode Command Line Tools
  (`xcode-select --install`), the same package that provides `cc`. Linux and Windows are not
  supported: PDFKit exists only on macOS.
- `python3` (standard library only) and bash.
- For phase 2: an agent harness with sub-agents, a model that can read images, file read/write/edit
  tools, and a shell tool. The Claude Code allow-rules are in
  [`docs/claude_settings.example.json`](../../docs/claude_settings.example.json); other harnesses
  need the equivalent (see [Permissions](#permissions)).

### Reference manual edition

Page and chapter numbers in this runbook and its reference run hold only for this edition, so check
yours:

| | |
|---|---|
| Title | Digitakt User Manual, OS1.50 edition (date code 230301) |
| File | `Digitakt_User_Manual_ENG_OS1.50_230301.pdf` |
| Size | 4,551,748 bytes, 96 pages |
| SHA-256 | `8d085bac9be47d3c9fc639e43f54b1a2a413d0f9b1b73e9557f579b6f933a48e` |

Each OS folder records which manual edition its notes were checked against; this OS1.50 manual
edition is the reference. With a different edition the pipeline still works; only the page and
chapter numbers differ, so look things up by section title instead.

## Phase 1: mechanical (one command, about 10–40 s)

From the repo root:

    ./scripts/manual_extract.sh manuals/<manual>.pdf work/manuals/<name> [dpi=170]

for example

    ./scripts/manual_extract.sh manuals/Digitakt_User_Manual_ENG_OS1.50_230301.pdf work/manuals/Digitakt_OS1.50

It compiles the Swift tools into `work/bin/` on first use, prints start and end times, and writes to
`work/manuals/<name>/`:

| Output | What it is |
|---|---|
| `pages/page-NNN.png` | full-page renders: the visual source for transcription and for cropping vector diagrams |
| `text/page-NNN.txt` | the PDF text layer: exact wording |
| `meta.json` | page count, dpi, the PDF outline (bookmarks), and each page render's pixel size (`pageSizes`, `pagePixels`) |
| `images/pNNN_*.png`, `figures.txt`, `figures.json` | the embedded RASTER figures, already cropped from the page renders, and their boxes (`page file x y w h`) |
| `chapters.json` | chapter → page-range manifest built from the top-level bookmarks (`scripts/manual/make_chapters.py`) |
| `CONVENTIONS.md` | the phase-2 extraction spec, copied from `scripts/manual/CONVENTIONS.md` |

Re-running it deletes and regenerates `pages/` and `images/`; `text/` is overwritten.
A PDF without bookmarks gives a single "FRONT MATTER" chapter covering every page; split that one by
hand in `chapters.json` before phase 2.

## Phase 2: one agent per chapter

The orchestrating agent reads `work/manuals/<name>/chapters.json` and, for **each** chapter, starts
one chapter agent with this task:

> Extract chapter `<idx>` "`<title>`", pages `<start>`–`<end>`, of the manual in
> `work/manuals/<name>/`, following `work/manuals/<name>/CONVENTIONS.md`. Write the result to
> `work/manuals/<name>/<idx>_<slug>.md`. Run every shell command from the repo root as one bare
> command.

Each chapter agent runs the three passes in `CONVENTIONS.md`:

- **A.** transcribe the text and drop a section-named `<!-- IMAGE: … -->` placeholder wherever a
  picture belongs;
- **B.** crop each placeholder's picture (raster boxes from `figures.txt`/`figures.json`, vector
  boxes by eye from the page render) with `./scripts/manual/crop.sh`, and swap the placeholder for
  the image reference;
- **C.** describe each image in its markdown alt-text.

What a chapter agent needs:

- **read** access to `work/manuals/<name>/` (text, page renders, figures, the spec);
- **write** access to create its `NN_<slug>.md` (a new file, so an edit-only tool is not enough), and
  **edit** access to revise it;
- the shell commands `grep` and `./scripts/manual/crop.sh`, each run as **one bare command per call**
  (no `cd`, no `VAR=…`, no loops, no `;`/`&&`, no inline `python3 -c` or heredocs; the reason is in
  `CONVENTIONS.md`).

**Run one pilot chapter first** and check it before fanning out the rest. Harnesses cap parallel
sub-agents (Claude Code: 20, set by `CLAUDE_CODE_MAX_CONCURRENT_SUBAGENTS`); the Digitakt manual has
26 chapters, so it runs in two waves. `./scripts/manual/now.sh` prints a timestamp if you want to time
the run.

## Phase 3: assemble

Write `work/manuals/<name>/INDEX.md`, linking every `NN_<slug>.md` with its page range and a one-line
summary.

## Check the result

- `grep -rl 'IMAGE:' work/manuals/<name>/[0-9]*.md` must print nothing. A file it lists still holds a
  placeholder next to (or instead of) its image; fix that chapter.
- Nothing may reference `pages/page-NNN.png`: `grep -l 'pages/page-' work/manuals/<name>/*.md` must
  print nothing.
- The transcription is faithful and structurally complete, but it is written by language models, so a
  misread value is possible. The weakest spots are tables rebuilt from a scrambled text layer (on the
  Digitakt manual: the CC/NRPN map). To localise errors, run phase 2 twice into two folders and `diff`
  the chapter texts: where the runs agree, confidence is high; where they differ, check the PDF.
  Agreement is not proof: both runs can repeat the same misread.

A reference run on the OS1.50 Digitakt manual produced 26 chapter files (about 4,300 lines) and 95
figure crops in about 18 minutes.

## What to keep

The corpus is `NN_<slug>.md`, `images/section_*.png` and `INDEX.md` (about 7 MB for the Digitakt
manual). Everything else is scaffolding that phase 1 regenerates in seconds: `pages/` (by far the
largest, about 40 MB), `text/`, `figures.txt`/`figures.json`, `chapters.json`, `meta.json`, the copied
`CONVENTIONS.md`, and the phase-1 `images/pNNN_*.png` crops, which the chapters do not reference.
Keep `pages/` while you might still add or re-crop a figure.

## Permissions

The procedure touches only these:

| What | Why |
|---|---|
| `./scripts/manual_extract.sh …` | phase 1; it runs `swiftc`, `python3`, `cp` and `rm` inside itself |
| `python3 scripts/manual/make_chapters.py …` | only if you rebuild `chapters.json` by hand |
| `./scripts/manual/crop.sh …` | pass B, once per figure |
| `./scripts/manual/now.sh` | optional timing |
| `grep …` | pass B box look-ups |
| read `manuals/`, `scripts/manual/`, `work/manuals/` | inputs and the spec |
| write and edit `work/manuals/` | the chapter files and crops |

For **Claude Code**, copy [`docs/claude_settings.example.json`](../../docs/claude_settings.example.json)
to `.claude/settings.local.json` in the repo root (gitignored) or merge its `allow` list into yours.
Every path in it is relative to the repo root, so start Claude Code there. In Claude Code, `Edit`
rules also cover the Write tool. The file also denies edits to `sysex/` and `manuals/`, the two
folders that hold your originals.

For **other harnesses**, allow the same commands as prefix or first-word rules, and give the
chapter agents read, create and edit access to `work/manuals/`. Whatever the harness, the
one-bare-command rule is what keeps a run from stopping for approvals.

## Files

| File | Role |
|---|---|
| `scripts/manual_extract.sh` | phase-1 driver |
| `scripts/manual/render_pdf.swift` | page renders, text layer, outline and page sizes (`meta.json`) |
| `scripts/manual/crop_figures.swift` | embedded-raster crops, placed by scanning each page's content stream |
| `scripts/manual/crop_region.swift`, `crop.sh` | region crop for vector diagrams |
| `scripts/manual/make_chapters.py` | chapter manifest |
| `scripts/manual/now.sh` | timestamp |
| `scripts/manual/CONVENTIONS.md` | the phase-2 spec (copied into each output folder) |
| `scripts/manual/list_images.swift`, `diag_images.swift`, `extract_images.swift` | diagnostic probes for a new PDF (what is raster, which encodings); the pipeline does not use them |

Why figures are cropped from the page render rather than decoded from the PDF: the render already
composites every image encoding (1-bit, indexed, masked, JPEG) and any vector overlay exactly as
printed, while decoding the raw image streams recovers only a small fraction of a typical manual's
figures (see `extract_images.swift`).
