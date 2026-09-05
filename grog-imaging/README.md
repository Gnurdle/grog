# grog-imaging

An **MCP server** (over stdio) exposing grog's heavyweight **document & imaging**
tools so an ECA-driven agent loop (or any MCP client) can call them.

These are tools ECA does **not** provide natively: PDF text extraction, PDF OCR,
PDF vector/line-drawing analysis, Office-document reading, image crop/write, and
raster-image geometry/text extraction.

## Language / runtime

JVM **Clojure** — required because the suite is built on heavy JVM imaging libs
(boofcv). Dependencies via `deps.edn`.

## Running

```bash
clojure -M:server -m grog-imaging.main
```

It speaks MCP over **stdio** (newline-delimited JSON-RPC), so it is trivial to
wire into ECA:

```json
{ "mcpServers": {
    "grog-imaging": {
      "command": "clojure",
      "args": ["-M:server", "-m", "grog-imaging.main"]
    }
} }
```

ECA spawns it once per session and keeps it warm.

## Tools

| Tool | Purpose |
|------|---------|
| `read_pdf_document` | extract text from a PDF |
| `ocr_pdf_document` | OCR raster pages of a PDF |
| `analyze_pdf_line_drawings` | describe vector line drawings in a PDF |
| `read_office_document` | extract text from .docx/.xlsx/.pptx |
| `write_workspace_png` | write a PNG image |
| `crop_workspace_image` | crop an image region and return the result |
| `read_png_image` | decode a PNG/JPG: metadata + optional dominant colors |
| `ocr_image` | Tesseract OCR of a PNG/JPG, optional per-word boxes |
| `analyze_image_shapes` | BoofCV geometry on a PNG/JPG: lines, rectangles, ellipses, blobs, arrow candidates |
| `draw_overlay_png` | draw shapes/text boxes onto a raster and write an annotated PNG |

## Raster-image tools

These operate on PNG/JPG rasters (none of the PDF machinery):

- **`read_png_image`** — `path`, optional `color_stats`, `max_colors`. Returns
  dimensions, alpha/component info, bits-per-pixel, byte size, and (optionally)
  quantized dominant colors.
- **`ocr_image`** — `path`, optional `dpi`, `language`, `page_seg_mode`,
  `preprocess`, `with_boxes`. Returns recognized text; with `with_boxes` also
  per-word `{text, confidence, x, y, width, height}`. Requires Tesseract
  `*.traineddata` (see `ocr_pdf_document` hint / `TESSDATA_PREFIX`).
- **`analyze_image_shapes`** — `path` plus capping knobs (`region_size`,
  `max_lines`, `max_rectangles`, `max_ellipses`, `max_blobs`, `max_arrows`,
  `min_blob_area`). Returns line segments (RANSAC), rectangles (polygon
  detector), ellipses, blobs (threshold → contours), and heuristic arrow
  candidates — all in source-image pixel coordinates (x right, y down).
- **`draw_overlay_png`** — `source_path`, `out_path`, `overlays` map with
  `rectangles` / `lines` / `ellipses` / `text_boxes` / `blobs` / `arrows`.
  Writes an annotated PNG with color-coded, labeled geometry.

## Status

Handlers are real implementations living in `src/grog_imaging/tools.clj`
(reused by `main.clj`), backed by the self-contained engine
`src/grog_imaging/image.clj`. The `:fn` bodies in `main.clj` are thin adapters.
Tesseract-dependent tools need traineddata installed to return text rather than
an explanatory error.
