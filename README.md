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
- **One unified panel** (Flipping Copilot-style layout, PocketGE theme):
  a stats header, live suggestions, a Favorites watchlist, and paginated
  flip history all in one tab.
  - **Stats header:** profit for a selectable window (Session / 1h / 4h /
    12h / 1d / 1w / 1m / All time), **unrealized profit** (your open buy
    lots marked to the current market), **ROI%**, **hourly profit rate**,
    and **portfolio value** — cash + bank + inventory + equipped items +
    what's tied up in open GE offers, all priced live.
  - **Persistent history**: lifetime P/L, flip history, and open buy lots
    are saved through RuneLite's config, so a buy today still books its
    flip correctly when you sell tomorrow. "Reset session" only zeroes the
    session counter and its start time.
- **Flip advisor (optional, OFF by default):** suggests buys sized to your
  cash stack, sells for stacks you already hold in bank/inventory, and
  "adjust your offer" nudges when your listed price drifts off the market.
  You control how often it re-checks (5m / 30m / 2h / 8h) and a risk level
  (how thin a market it will suggest). Each suggestion carries an **Analyst
  Rating** badge (Strong Buy → Strong Sell), a simplified proxy of the
  website's rating scoped to what the plugin fetches (live price vs. today's
  24h average). **Skip** hides a suggestion for the session; **Block** adds
  the item to an editable never-recommend list; the **star** adds it to
  Favorites. This is the plugin's ONLY networked feature — it fetches live
  prices from the public OSRS Wiki price API (the same source pocketge.com
  uses) and nothing else. Every other feature is fully offline.
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
