# grog-alpaca

An **MCP server** (over stdio) exposing **Alpaca** — market data and trading
(stocks + options) — to an ECA-driven agent loop.

Credentials live in the **OS keyring** (service `grog`); account settings live in
`~/.config/grog/alpaca.edn`. Read-only by default — order placement is off unless
you turn it on.

## Tools

| Tool | Type | What it does |
|---|---|---|
| `alpaca_clock` | read | market open/closed + next open/close |
| `alpaca_account` | read | status, buying power, cash, options level |
| `alpaca_positions` | read | open positions (optionally one symbol) |
| `alpaca_orders` | read | recent orders (open/closed/all) |
| `alpaca_order` | read | one order by id |
| `alpaca_quote` | read | latest quote/trade snapshot(s) for stocks |
| `alpaca_bars` | read | historical stock bars |
| `alpaca_option_chain` | read | option chain (quote + greeks) for an underlying |
| `alpaca_assets` | read | asset lookup / list |
| `alpaca_calendar` | read | market calendar (days + hours) |
| `alpaca_place_order` | **gated** | place a stock/option order |
| `alpaca_cancel_order` | **gated** | cancel an order |

## Configuration — `~/.config/grog/alpaca.edn`

```clojure
{:paper true              ;; true = paper endpoints (default); false = LIVE
 :allow-orders false      ;; read-only unless true
 :stock-feed "iex"        ;; stocks: iex (free) | sip | delayed_sip
 :options-feed "indicative" ;; options: indicative (free) | opra (paid)
 :timeout-ms 20000}
```

## Credentials (OS keyring)

```
/secret set ALPACA_API_KEY <key>
/secret set ALPACA_SECRET_KEY <secret>
```

(Get them from the Alpaca dashboard → API Keys. Paper and live accounts have
separate key pairs.)

## Endpoints

| | Base URL |
|---|---|
| Trading — paper | `https://paper-api.alpaca.markets` |
| Trading — live | `https://api.alpaca.markets` |
| Market data | `https://data.alpaca.markets` |

Auth headers: `APCA-API-KEY-ID` / `APCA-API-SECRET-KEY`.

## Data notes

- **Stocks**: free plan = IEX feed + historical bars (15-min-delayed SIP via REST).
- **Options**: free = **indicative** feed — real-time but *randomized*; real
  **OPRA** options need the paid data plan (`:options-feed "opra"`). Historical
  options only go back to **Feb 2024**.
- **Market hours**: options trade **9:30–4:00 ET**; stocks extended **4:00–20:00 ET**.

## Safety

- `:allow-orders` defaults **false** — the two order tools refuse until you set it.
- With `:paper false`, `alpaca_place_order` sends **real** orders.
- Keep ECA's tool approval on for the order tools (don't YOLO them).

## Run

```bash
clojure -M:mcp -m grog-alpaca.main
```

## Build & share a standalone jar

For a teammate who should **not** have to install the Clojure CLI (just a JRE 17+):

```bash
./build-uberjar.sh          # Linux/macOS  (build-uberjar.bat on Windows)
```

This produces a self-contained **`target/grog-alpaca.jar`** (all deps bundled). The
build machine needs the Clojure CLI; the *runtime* needs only a JRE 17+.

Run it directly:

```bash
java -cp target/grog-alpaca.jar clojure.main -m grog-alpaca.main
```

Wire into ECA:

```json
{ "mcpServers": {
    "grog-alpaca": {
      "command": "bash",
      "args": ["-lc", "cd '<root>/grog-alpaca' && clojure -M:mcp -m grog-alpaca.main"]
    }
} }
```
