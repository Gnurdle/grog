# Grog

AI generated drek to make an agentic loop for ollama, with some extra beans
grog.edn has the knobs for setting it up it.

I used a local qwen3.5:4b model locally on a crappy gpu and managed to get things
done.

It supports projects, see /project.  Projects are managed as sandboxes in the agent.

Memory is maintained as flat-files in resources/...

It tries to keep stuff it figures out in the the project to sustain the adventure...

## Quick Start

- procure a gpu and ollama endpoint

- procure a brave search api key

- hack grog.edn with these tidbits

```
$ cd grog
$ clojure -M:run chat
chat> /help
(go bannanas)
```

## tools presented to llm:

      brave_web_search
      read_workspace_file
      write_workspace_file
      read_office_document
      read_pdf_document
      ocr_pdf_document
      analyze_pdf_line_drawings
      memory_save
      memory_load
      memory_list_keys
      memory_create_namespace
      memory_delete



good luck, we're all counting on you...
