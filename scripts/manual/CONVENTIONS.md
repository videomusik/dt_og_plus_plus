# Agent conventions: PDF manual → greppable markdown (text-driven, 3 passes)

You convert ONE chapter of a PDF manual into a structured, greppable markdown file. Phase 1 already
ran, so for each page N you have, in this folder:

- `text/page-NNN.txt`: the PDF text layer (exact wording of labels, ranges, menu paths, cross-refs).
- `pages/page-NNN.png`: the full-page render, the visual source. Origin top-left, y down. Its pixel
  size is in `meta.json`: `pageSizes[N-1]` is `[width, height]` for page N, and `pagePixels` is page
  1's. (The reference Digitakt manual renders at 1405×1987 px at the default 170 dpi, which is A4.)
- `figures.txt` / `figures.json`: the embedded RASTER images, already located, each with a pixel box
  `{x,y,w,h}` in page-render pixels. `figures.txt` has one line per figure (`page file x y w h`) for
  `grep`; `figures.json` is the same, keyed by zero-padded page. Vector diagrams and tables are NOT in
  either: they exist only on the page render.

Your chapter's index, title, page range and output file name (`NN_<slug>.md`, in this folder) are
given in the task. Do the THREE passes below **in order** for your chapter.
⛔ **Never embed a full-page render `pages/page-NNN.png`.**

## Pass A: transcribe the text and drop image placeholders

Read `text/page-NNN.txt` (exact wording) and `pages/page-NNN.png` (structure) for every page in your
range. Write the chapter markdown in this structure:

    # <chapter title>
    > Source: <manual file name>, pages START–END. Auto-extracted.

Then, in reading order, transcribe each section as dense markdown: **your own words** for
descriptions and narrative, quoting **short** items exactly (parameter names, value ranges, menu
paths, key-combo names, defined terms). Prefer tables and lists to prose; use numbered lists for
procedures; keep cross-references; put a `<!-- p.NN -->` marker before each page's content; cover
every page.

**Wherever a picture belongs in the flow** (device screen, diagram, panel layout, connection setup,
a table you also want kept as an image), put a placeholder **on its own line**, keyed by the section
it sits in:

    <!-- IMAGE: 4.2.1 | page 12 | what it is: a signal-flow diagram -->

Use the section number (or, if there is none, a short slug). This pass must miss NOTHING visual:
every CONTENT picture gets a placeholder, raster or vector. Transcribe the picture's information as
text near the placeholder too (a screen's labels and values, a table's cells, a diagram's blocks and
callouts): the text is the main greppable value.

⛔ **Exception: do NOT place a picture for a decorative or boilerplate callout icon**: the tip "hand"
icon, the warning/caution "triangle" icon, or any small repeated note/tip/caution marker. Render tips
and cautions as markdown blockquotes (`> **Tip:** …` / `> **Caution:** …`) with NO image. Crop only
CONTENT figures: device screens, signal-flow / panel / architecture diagrams (with their balloon
numbers), connection setups, and reference tables you want kept as an image. A crop must carry
information the text alone does not give.

## Pass B: crop each placeholder's picture, name it by section, swap in the reference

⛔⛔ **One bare command per shell call. This is the most important rule.** Agent harnesses that
allow commands by rule match ONE BARE command, usually by its first word. So every shell call MUST
be a single bare command and nothing else:

- ⛔ NO `python3 -c`, `python3 -`, or `<<` heredocs (every distinct inline script is a new, unmatched
  command).
- ⛔ NO compound commands: no leading `cd`, no `VAR=…` assignment, no `for`/`while` loop, no `;` or
  `&&` chaining, no pipeline. A command whose first word is `cd` / `for` / `VAR=` matches NOTHING,
  **even though the `grep` / `crop.sh` inside it would be allowed on its own.** Batching many crops or
  greps into one `cd …; D=…; crop.sh …; crop.sh …` block is the most common way to break this.
- ✅ Run exactly ONE `grep` per call and exactly ONE `./scripts/manual/crop.sh` per call. Do NOT batch
  to "save calls": many bare calls cost nothing, one compound call stalls the whole run.

Look files up with your file-read tool or a single bare `grep`, never with scripting.

For every `<!-- IMAGE: <sec> | page N | … -->` placeholder, get a crop box and crop it:

- **Device screen / photo / icon**: its box is already located. Get it with
  `grep '^0NN ' <thisdir>/figures.txt` (columns: `page file x y w h`), or by reading
  `<thisdir>/figures.json`. Do NOT parse either with python.
- **Vector diagram / table** (not in figures.txt/json): estimate the box by eye from
  `pages/page-NNN.png`, **including balloon numbers, arrows and axis labels** (they map numbers to
  parts) but excluding body paragraphs.

Crop with exactly this form, run from the repo root (your default working folder), as a bare
`./scripts/...` command: no `cd`, no absolute script path, **ONE crop per call**, and the full
relative path written out in each call:

    ./scripts/manual/crop.sh <thisdir>/pages/page-0NN.png <x> <y> <w> <h> <thisdir>/images/section_<sec>.png

Name the file by section: `4.2.1` → `images/section_4_2_1.png` (if two pictures share a section,
add `_a`, `_b`). Then **read the crop and adjust** the box until nothing is clipped (a little margin
is fine). Replace the placeholder line with a markdown image reference to the crop, leaving the
alt-text empty for now (Pass C fills it):

    ![](images/section_4_2_1.png)

After the swap, check that the `<!-- IMAGE: … -->` line is gone: an image reference and its old
placeholder must never both remain.

## Pass C: describe each image INTO the alt-text

For every `![](images/…)` you just added, read the crop and write a **complete, greppable
description** of what it shows as the markdown **alt-text**, so a search finds the image's content
through this text: name every callout, label and value on a screen, list a table's rows, describe a
diagram's blocks and arrows and each balloon number → part:

    ![p.12 — signal flow: Input → Filter → Amp → Output](images/section_4_2_1.png)

The alt-text is the descriptor: it stays on the reference's own line, is greppable, and shows if the
image ever fails to load. Keep the in-text transcription from Pass A as well.

## Notes

- Digitakt manual: track PARAMETER screens (audio and MIDI) have a far-left strip (`SMP`/`LEV` on
  audio tracks, `MID`/track on MIDI tracks) that is a status strip, not one of the 8 `DATA ENTRY`
  knobs. Note it once; treat the 8 knobs as positions 1–8 = A–H.
- This is a faithful structured extraction, never a verbatim copy of the manual's prose.
