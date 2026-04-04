# Grog

AI generated drek to make an agentic loop for ollama, with some extra beans
grog.edn has the knobs for setting it up it.

I used a local qwen3.5:4b model locally on a crappy gpu and managed to get things
done.

It supports projects, see /project.  

It tries to keep stuff it figures out in the the project to sustain the adventure

## Quick Start

cd grog
clojure -M:run chat
chat> /help

good luck, we're all counting on you...
