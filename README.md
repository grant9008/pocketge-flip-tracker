# PocketGE Flip Tracker

A RuneLite plugin that tracks your Grand Exchange flips **as they fill** —
buys, sells, and profit after the 2% GE tax — with one-click live price
charts on [pocketge.com](https://pocketge.com).

## Features

- Listens to RuneLite's `GrandExchangeOfferChanged` events (all 8 slots) and
  records every **incremental fill** (partial fills included).
- Matches sells against earlier buys **FIFO per item** and books completed
  flips with profit **after tax** (2% = `floor(price/50)` per item, nothing
  under 50 gp, 5M cap, bond + classic tools exempt).
- Sidebar panel: session profit, **lifetime profit**, recent flips with
  per-flip P/L, and a click-through to the live PocketGE chart for any
  flipped item.
- **Persistent history**: lifetime P/L, flip history, and open buy lots
  are saved through RuneLite's config, so a buy today still books its
  flip correctly when you sell tomorrow. "Reset session" only zeroes the
  session counter.
- **Flip advisor (optional, OFF by default):** suggests buys sized to your
  cash stack, sells for stacks you already hold in bank/inventory, and
  "adjust your offer" nudges when your listed price drifts off the market.
  You control how often it re-checks (5m / 30m / 2h / 8h) and a risk level
  (how thin a market it will suggest). **Skip** hides a suggestion for the
  session; **Block** adds the item to an editable never-recommend list.
  This is the plugin's ONLY networked feature — it fetches live prices from
  the public OSRS Wiki price API (the same source pocketge.com uses) and
  nothing else. Every other feature is fully offline.
- **Local website bridge (opt-in, OFF by default):** serves your session's
  flips as JSON on `127.0.0.1` only, so pocketge.com open in *your* browser
  can display your live trades in its Bank of Gielinor panel. Nothing ever
  leaves your machine; CORS is locked to the PocketGE origins. The plugin
  makes **zero outbound network requests**.

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
