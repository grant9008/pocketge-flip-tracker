# PocketGE Flip Tracker

A TradingView-style trading terminal for OSRS, in your RuneLite sidebar:
**watchlists**, **price-move alerts** and **ranked flip ideas**, so the flip
finds you instead of the other way round. Every flip is tracked **as it
fills** — buys, sells, and profit after the 2% GE tax — and the live chart
for anything you're looking at is one click away on
[pocketge.com](https://pocketge.com).

## Features

- Listens to RuneLite's `GrandExchangeOfferChanged` events (all 8 slots) and
  records every **incremental fill** (partial fills included).
- Matches sells against earlier buys **FIFO per item** and books completed
  flips with profit **after tax** (2% = `floor(price/50)` per item, nothing
  under 50 gp, 5M cap, bond + classic tools exempt).
- **One unified panel** (Flipping Copilot-style layout, PocketGE theme):
  a stats header, live suggestions, a Favorites watchlist, and paginated
  flip history all in one tab.
  - **Stats header:** profit for a selectable window (Session / 1h / 4h /
    12h / 1d / 1w / 1m / All time), **unrealized profit** (your open buy
    lots marked to the current market), **ROI%**, **hourly profit rate**,
    and **portfolio value** — cash + bank + inventory + equipped items +
    what's tied up in open GE offers, all priced live.
  - **Persistent history**: lifetime P/L, the recent flip window, and open
    buy lots are saved through RuneLite's config, so a buy today still books
    its flip correctly when you sell tomorrow. Every closed flip is ALSO
    appended to a permanent ledger at
    `.runelite/pocketge-flip-tracker/flips.jsonl`, which is never trimmed —
    that is the lifetime record the website's history page reads.
    "Reset session" only zeroes the session counter and its start time.
- **Flip advisor (optional, OFF by default):** suggests buys sized to your
  cash stack, sells for stacks you already hold in bank/inventory (with
  **real profit vs. your tracked buy price** when the tracker knows the
  cost basis, not just "here's what it's worth"), and "adjust your offer"
  nudges when your listed price drifts off the market. The headline
  **Recommended Flip** is a single-glance card — icon, name, the price and
  quantity to offer, the capital it ties up and the profit if it fills — and
  a **⚙ settings popup** tucks away how often it re-checks (5m / 30m / 2h /
  8h), the risk level (how thin a market it will suggest) and the
  never-recommend list, so the panel leads with numbers instead of knobs.
  Each suggestion also carries a **⧉ copy price** button (clipboard, ready
  to paste straight into the GE offer's price box). **Skip** hides a
  suggestion for the session; **Block** (in the settings popup) adds the
  item to an editable never-recommend list; the **star** adds it to
  Favorites. This is the
  plugin's ONLY networked feature — it fetches live prices from the public
  OSRS Wiki price API (the same source pocketge.com uses) and nothing else.
  Every other feature is fully offline.
- **Bank/inventory highlighting:** items you're holding or bank stock the
  advisor currently has a live suggestion on get a colored border (gold =
  buy candidate, teal = sell candidate) plus a small profit badge, drawn
  right on the item slot — the same at-a-glance affordance Flipping Copilot
  uses, so you don't have to cross-reference the panel while digging
  through your bank.
- **Favorites:** a local watchlist (independent of any account — nothing
  synced) showing live price + change vs. today's typical for whatever
  you've starred from a suggestion or a flip-history row.
- **In-world overlay:** a small draggable panel showing the advisor's top
  live suggestion, PocketGE-themed. Unlike some flip tools it isn't gated to
  the GE screen being open — it shows whenever the advisor has something to
  suggest, so you see it before you've even opened the Exchange.
- **Local website bridge (opt-in, OFF by default):** serves your session's
  flips as JSON on `127.0.0.1` only, so pocketge.com open in *your* browser
  can display your live trades in its Bank of Gielinor panel. Nothing ever
  leaves your machine; CORS is locked to the PocketGE origins. Outside of
  the advisor above, the plugin makes **zero outbound network requests**.

## Honest tracking

The first snapshot the tracker sees of an offer (e.g. the login replay of
GE slot state) sets a *baseline* without counting fills — only growth the
tracker actually witnesses is booked. No double counting, no guessing about
what happened while you were logged out.

## Building

Requires JDK 17 to run Gradle (the plugin itself targets Java 11, matching
RuneLite):

```
gradle build
```

CI does this automatically on every push. To run a full client with the
plugin loaded, run `PocketGeTrackerPluginTest.main()` from your IDE.

## License

[BSD 2-Clause](LICENSE)
