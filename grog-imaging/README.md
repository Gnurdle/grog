# grog-imaging

An **MCP server** (over stdio) exposing grog's heavyweight **document & imaging**
tools so an ECA-driven agent loop (or any MCP client) can call them.

These are tools ECA does **not** provide natively: PDF text extraction, PDF OCR,
PDF vector/line-drawing analysis, Office-document reading, and image crop/write.

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

## Status

Handlers are **stubs** (they echo their args) so the server is runnable and
discoverable. Real implementations are being moved in from grog's
`boofcv_pdf.clj` / `image.clj` — the `:fn` bodies in `main.clj` are the seams.
