# PocketGE Trading Terminal

**Watchlists, flip ideas and trade tracking for Old School RuneScape, in your
RuneLite sidebar.**

- Free. No account. Works in free-to-play.
- Live charts one click away on [pocketge.com](https://pocketge.com).

[![Tip the developer](https://img.shields.io/badge/%E2%99%A5%20Tip%20the%20developer-ff981f?style=for-the-badge)](https://buy.stripe.com/aFafZg9ehaaxaVaf3e00000)

![RuneLite with the Grand Exchange open and the PocketGE sidebar beside it. The sidebar's card reads Ruby necklace, SELL from your bank, PAID @ 1,141 against SELL @ 1,030, -2.46M gp profit, and "At a loss — Hold to keep it". In the Exchange window the one live sell offer is ringed red, and the same offer shows red in the sidebar's slot grid.](./docs/screenshots/in-game.png)

![The PocketGE sidebar on its own: a card reading Ruby necklace, SELL from your bank, PAID @ 1,141 beside SELL @ 1,030, Paid on all 18,787, -2.46M gp profit, QUANTITY 18,787 and VALUE 18.9M gp, "At a loss — Hold to keep it" with the 15/15 pager, and the chart, watchlist and hold buttons. Below it the eight slot squares with three offers — one outlined red, two green — the item search, the Favorites list with five items showing and "13 more", and the Find Opportunities header.](./docs/screenshots/sidebar-live.png)

---

## Features

### Watchlists

![The eight Grand Exchange slot squares with fill bars, one outlined red and two green, above a watchlist showing live prices with a 5-day-low badge on Sapphire necklace and a 5-day-high badge on Nature rune.](./docs/screenshots/part-watchlist.png)

- Star any item into named lists, each in a colour you pick.
- Each row shows the live price and how far it is from its usual 24-hour price.
- ▲ / ▼ badges mark items at a 5-day high or low.
- Optional alerts when an item moves 10%, 15%, 20% or 30%.

### Flip ideas

![The flip card: Sapphire necklace with BUY and "from the Exchange" under it, a flip score of 79 marked Strong Flip with a meter, BUY @ 1,120 and SELL @ 1,169 boxed side by side, +296K gp profit in large green, QUANTITY 18,000 beside CAPITAL NEEDED 20,160,000 gp, and "Sized to the cash you have free" on the last line with the 1/2 pager and the chart, watchlist and paging buttons under it.](./docs/screenshots/part-card.png)

- One idea at a time: buy or sell, at what price, how many, and the profit
  after tax.
- Sized to your cash, the 4-hour buy limit and what the item really trades.
- A flip score out of 100, the same one pocketge.com shows.
- The item gets a white ring in your bank, inventory or a free GE slot.
- Arrows page through the ideas. **Hold**, **Block** or **star** any of them.
- For items you own, profit is against what you actually paid.

### On the offer screen

- A ring marks what to press next: the price, then the quantity.
- A chip shows the exact number. **Press E** to fill it, or click to copy it.
- In the item search, the suggested item is the first result. Press E to pick it.
- E can be any key except Enter, Escape or Backspace (**Fill key** setting).
- The item's price chart sits over the chat. Scroll to zoom, drag to pan,
  drag the top edge to resize.
- Over the price or quantity box, the **Chart** button swaps the chart in.
- Nothing is ever confirmed for you.

### Offer slots

![RuneLite with the Grand Exchange open: two live sell offers, Uncut ruby and Emerald, each outlined green with a fill bar under it, and the same two squares outlined green in the sidebar's slot grid to the right. The sidebar's card reads Diamond, SELL from your bank, Held before PocketGE, 18,608 worth 30.2M gp, and "Nets 1,623 gp each after tax".](./docs/screenshots/ge-slots-live.png)

![The sidebar's eight slot squares: three holding items with coloured outlines and partial fill bars, five empty.](./docs/screenshots/part-slots.png)

![Hovering a slot square in the sidebar. The tooltip reads: Uncut ruby. Priced fine — leave it. Sold 8,006 of 17,303 (46%). click: inspect · right-click: price it yourself.](./docs/screenshots/slot-tooltip.png)

- Your 8 GE slots in the sidebar, each with a fill bar.
- **Grey**: working. **Yellow**: needs a new price. **Green**: ready to collect.
- The same colours outline your offers in the GE window.
- Hover a slot for its progress, what's waiting to collect, and the price to
  move to.
- Right-click a slot to stop price advice for it.
- The ▾ in the corner folds the grid away.

### Bank

![The bank open beside the sidebar. The Diamond stack, 18,608, is outlined white and its hover reads: Your current suggestion, 30.2M gp after tax, 18,608 at 1,656 gp each. A second stack is outlined in the sell colour. The sidebar's card reads Diamond, SELL from your bank, Held before PocketGE, and "At a 5-day low — Hold to keep it".](./docs/screenshots/bank-marks.png)

- Stacks worth selling are outlined in your sell colour, the card's pick in white.
- Hover a stack for what it sells for after tax, and what you paid for it.

### Stats and history

![The stats header: Profit +14.8M gp for the session, with unrealized profit, flips made, ROI, hourly profit, portfolio value and session time listed beneath.](./docs/screenshots/part-stats.png)

![The flip history section: five flips recorded, with the last five listed newest first — Sapphire necklace +641.2K, Adamantite bar +390.4K, Uncut diamond -397.8K in red, Nature rune +91.8K, Emerald necklace +273.6K — and a link out to the full history.](./docs/screenshots/part-history.png)

- Profit after tax for the session, 1h, 4h, 12h, 1d, 1w, 1m or all time.
- Plus unrealized profit, ROI, gp/hour and your whole portfolio's value.
- Recent flips below. Hover one for its prices, tax and hold time.
- **Flip history** opens every flip on pocketge.com (needs the local bridge).
- Real GE tax: 2%, none under 50 gp, capped at 5M, bonds and a few tools
  exempt.
- Every flip is saved to a file on your computer.

### Find Opportunities

- Five lists: High Vol Margins, Low Vol Margins, Biggest Losers (24H),
  At 5D Highs and At 5D Lows.
- Click a row to inspect it. Right-click to chart it or add it to a watchlist.

### Free-to-play

- Hides members items and plans around 3 GE slots.

---

## Your data stays yours

- No account, no login, no telemetry.
- The only thing it contacts is the public OSRS Wiki prices API. Turn the
  advisor off and it contacts nothing; tracking keeps working.
- Wiki requests name the plugin, and pause if the Wiki asks it to slow down.
- The **local bridge** lets pocketge.com in your browser show your flips. Off
  by default, on 127.0.0.1 only, and other websites are refused.
- Flips are saved in `.runelite/pocketge-flip-tracker/flips.jsonl`.
- Watchlists, blocked items and recent flips live in RuneLite's own settings,
  so RuneLite syncs them if you're signed in to a RuneLite account.

### Honest tracking

- Reads each offer's own record of what filled, so offers it meets mid-way,
  and fills while you're logged out, still count.
- Never counts the same offer twice.
- A stack it never saw you buy shows what it sells for, never a made-up profit.

---

## Install

- RuneLite → wrench icon → **Plugin Hub** → search **PocketGE** → Install.
- Works straight away. The local bridge stays off until you turn it on.

## Settings

All in the **gear menu** at the top of the panel, and in RuneLite's wrench menu.

- **Flip advisor**: ideas and live prices. The only part that goes online.
- **Re-check every**: 5m, 30m, 2h or 8h.
- **Min. profit per suggestion**: hide small ideas.
- **Never-recommend list**, and **Ask before blocking an item**.
- **Buy / sell colours**: 8 pairs, High contrast by default, all colour-blind
  friendly.
- **Score line on cards**.
- **Watchlist badges** and **Watchlist glow borders**.
- **Alert on big price moves**: off, 10%, 15%, 20% or 30%.
- **Mark bank stacks worth selling**, **Profit on hover over stacks you
  bought** and **Show a key beside the bank**.
- **Price chart over the chat**.
- **Fill key**: E by default.
- **Chart clicks reuse your open tab** and **Open tab follows the GE offer
  screen**.
- **Local website bridge**: off by default.

## Support

- [Tip the developer](https://buy.stripe.com/aFafZg9ehaaxaVaf3e00000) through
  Stripe. Any amount helps.
- Star this repository.
- Report a problem or an idea in
  [Issues](https://github.com/grant9008/pocketge-flip-tracker/issues), or with
  the links at the bottom of the panel. A card's right-click **Copy PocketGE
  report** helps.
- In game, the only mention is a small **Support the dev** link at the bottom
  of the panel.

## Building

- Gradle needs JDK 17; the plugin targets Java 11.
- `./gradlew build` (`gradlew.bat build` on Windows).
- `PocketGeTrackerPluginTest.main()` runs a client with the plugin loaded.
- No access to `repo.runelite.net`? `./tools/typecheck/check.sh` compiles
  everything and runs the tests.

## License

[BSD 2-Clause](LICENSE)
