# grog-office

An **MCP server** (over stdio) exposing **Apache POI** `.docx` manipulation
tools for the pixel-perfect OOXML pipeline: import, block-model listing,
run-aware find/replace (formatting-preserving), table-row deletion, save, and
render.

## Run

```bash
clojure -M:mcp -m grog-office.main
```

Wire into ECA (already done in `grog/src/grog/eca_config.clj` `grog-mcp-servers`):

```json
{ "mcpServers": {
    "grog-office": {
      "command": "clojure",
      "args": ["-M:mcp", "-m", "grog-office.main"]
    }
} }
```

## Tools

Every call is keyed by a server-side **handle** from `import_document`; blocks
are addressed by a stable **`block_id`** (`para.N` / `table.M`) that maps 1:1 to
the `.map.edn` calibration rows.

| Tool | Purpose |
|------|---------|
| `import_document(path)` | open a .docx, return a document handle |
| `list_blocks(handle, include_runs?)` | ordered paragraph/table block model |
| `get_text(handle, block_id, cell?)` | logical (run-concatenated) text; optional `r,c` cell |
| `find_text(handle, query, limit?)` | run-aware occurrences with block/cell/offset |
| `replace_text(handle, match, replacement, block_id?, all?)` | in-place, preserves the first matched run's rPr; reports `layout_risk` |
| `delete_table_row(handle, block_id, row)` | remove a visible row (ghost rows skipped) |
| `render(handle, format?, pages?, dpi?)` | PDF / per-page PNG via headless LibreOffice |
| `save(handle, out_path)` | flush edits to disk |
| `list_handles / close_document` | manage open documents |

## Notes

- **Format:** only `.docx` (POI). Convert `.doc`/`.odt` first.
- **Replace** only changes character data; the replacement is placed entirely in
  the first matched run so its `rPr` (the pixel-critical style) is preserved.
- **Render** shells out to headless `soffice`/`libreoffice` (set `GROG_OFFICE_BIN`),
  and PNG pages via `pdftoppm` (set `GROG_PDFTOPM`). POI has no layout engine, so
  this is the pragmatic ground-truth proxy — authoritative-but-approximate.
- Row addresses and cell addresses are **0-based**.
