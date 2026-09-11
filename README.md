# PocketGE Flip Tracker

**A TradingView-style trading terminal for OSRS, in your RuneLite sidebar.**

Watchlists, price-move alerts and ranked flip ideas — so the flip finds you
instead of you going looking for it. Every trade is tracked as it fills, with
profit after the 2% tax, and the live chart for anything you're looking at is
one click away on [pocketge.com](https://pocketge.com).

Free. No account. Works in free-to-play.

---

## Why another flipping plugin

Most of them answer "what did I make?". This one is built around "what should
I be watching?" — the question you're actually asking between trades.

You build watchlists the way you would on a trading platform: named,
colour-flagged, as many as you like. The plugin watches them while you're off
doing something else and tells you when one of them moves. When you do want an
idea, it has one ranked and waiting, sized to the cash you actually have and
the slots you actually have free. And when you're on the offer screen, the
numbers you need are on that screen, not in a panel you have to look away to.

Nothing here places a trade for you. It shows you numbers and types them into
the offer screen — every offer is still yours to confirm.

---

## What it does

### Watchlists that tell you when something moves

Star anything — from a suggestion, a flip, a search, or the opportunity
finder — into named, colour-flagged lists. Each row shows the live price and
how far it has drifted from its own 24-hour typical, with ▲/▼ chips for
anything sitting at a 5-day high or low.

Set **price alerts** at 10%, 15%, 20% or 30% and RuneLite notifies you when
something swings that far, whether or not the panel is open. If the badges are
too busy for you, there's a switch to turn them all off.

### Flip ideas, ranked and actually affordable

The headline card shows one idea at a time: what to buy or sell, at what
price, how many, **the capital it ties up**, and the profit if it fills, after
tax. Buys are sized against three real limits at once — the cash you're
holding, the 4-hour buy limit, and a slice of what the item genuinely trades
in a day, so it never proposes moving more than the market will absorb.

**Next** pages through the queue; **Back** returns to one you passed, if it's
still on offer. **Hold** parks an idea for the session, **Block** kills it for
good (with an editable never-recommend list in settings), and the **star**
watchlists it.

When you already hold something worth selling, it says so — and when the
plugin watched you buy it, it shows the real P&L against what you paid rather
than just what the stack is worth.

### Help where the offer actually gets placed

Open a Grand Exchange offer and the plugin marks the control to press next. It
rings the price box first, and a chip on the chatbox offers the exact price —
click it and it's filled in. Once the price is set, the ring **moves to the
quantity box** and the chip offers the quantity: the 4-hour limit for a buy,
the stack you hold for a sell.

Prices are printed in full, with separators. A number you're about to type is
the one place an abbreviation can't be afforded.

### Offers you can read at a glance

Every active offer gets a coloured border, in-game and in the sidebar's slot
strip: green while it's still competitively priced, red once the market has
moved past it. Hover a red one and it tells you **which price to move to and
which price you're at** — plus whether there's still a margin worth chasing,
or whether you'd be better off taking a different flip entirely.

Pricing one deliberately high? Right-click the slot and tell the plugin to
leave that one alone.

### What you actually made

A stats header across a window you choose — session, 1h, 4h, 12h, 1d, 1w, 1m
or all time — with profit after tax, **unrealized** P&L on positions still
open, ROI%, gp/hour, session time, and a portfolio value that counts cash,
bank, inventory, worn gear and everything tied up in open offers.

Flips are matched FIFO per item and booked with the real tax rule (2%, nothing
under 50 gp, capped at 5M, bonds and the classic tools exempt). Partial fills
count. Every closed flip is appended to a permanent on-disk ledger that is
never trimmed, so your history is yours for as long as you keep the file.

### Something to trade when you have nothing

A built-in finder with five angles on the market: **High Vol Margins**, **Low
Vol Margins**, **Biggest Losers (24H)**, **At 5D Highs** and **At 5D Lows**.
Click a row to inspect it, right-click to open its chart or add it to a
watchlist.

### Free-to-play aware

On a free world it hides members-only items and knows you have three offer
slots, not eight — and once those three are busy it stops proposing new flips
you have nowhere to put, and talks about the offers you already have out
instead.

---

## Your data stays yours

- **No account, no login, no telemetry.** Nothing is uploaded, ever.
- The **only** outbound request the plugin makes is to the public OSRS Wiki
  prices API — the same source pocketge.com uses. That happens out of the box,
  because live prices are what the suggestions and watchlists are made of.
  Turning the advisor off stops every outbound request the plugin makes;
  tracking, history and stats keep working offline.
- The optional **local bridge** serves your own flips over `127.0.0.1` so
  pocketge.com *in your browser* can show them. It is off by default, binds to
  loopback only, and never leaves the machine.
- Your watchlists, blocked items and flip history are stored locally.

### Honest tracking

The first thing the tracker sees of an offer — the login replay of your GE
slots, or an offer that predates the plugin — sets a *baseline* and books
nothing. Only growth it actually witnesses is counted. No double counting, and
no guessing about what happened while you were logged out.

For the same reason, a stack the plugin never watched you buy is reported as
what it will *sell for*, never as profit. It cannot know what you paid, so it
doesn't pretend to.

---

## Installing

RuneLite → the wrench icon → **Plugin Hub** → search **PocketGE** → Install.

It works out of the box. The local bridge is off until you turn it on; the
advisor is on, which is what fetches live prices — see below.

## Settings worth knowing

| Setting | What it's for |
| --- | --- |
| Flip advisor | Master switch for suggestions, and the only thing that makes outbound requests. On by default; one click turns it off. |
| Re-check every | How often it re-thinks: 5m / 30m / 2h / 8h. |
| Min. profit per suggestion | Hides ideas below your bar. Auto adapts to your cash. |
| Never-recommend list | Items it will never propose. Editable, or use Block. |
| Ask before blocking an item | Confirmation before Block. Turn it off once you're sure. |
| Flips to keep | How many completed flips the panel lists. |
| Watchlist badges | Turn the chips, percentages and pulsing borders off. |
| Alert on big price moves | Off / 10% / 15% / 20% / 30%. |
| Mark bank stacks worth selling | Outlines sellable stacks in bank and inventory. |
| Send charts to an open PocketGE tab | Reuse the tab you already have open. |
| Local website bridge | Off by default. Loopback only. |

## Building

Requires JDK 17 to run Gradle (the plugin itself targets Java 11, matching
RuneLite):

```
gradle build
```

CI does this on every push. To run a full client with the plugin loaded, run
`PocketGeTrackerPluginTest.main()` from your IDE.

## License

[BSD 2-Clause](LICENSE)
