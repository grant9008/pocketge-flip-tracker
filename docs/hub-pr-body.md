# PocketGE Flip Tracker — hub PR description

Paste this as the body when opening the plugin-hub PR. It exists so the two
things a reviewer would otherwise have to go and find — the loopback HTTP
listener and the Grand Exchange price fill — are the first things they read
rather than a discovery.

---

Update to **pocketge-flip-tracker**.

A Grand Exchange flipping assistant: it tracks your buys and sells, values what
you are holding, and suggests what to trade next from Wiki price data.

### Third-party dependencies

None at runtime. `build.gradle` has `compileOnly net.runelite:client`; for
tests only, `junit`, the client itself and the LWJGL natives it needs to boot.
`build=standard`, so there is no `verification-metadata` to review.

### Network

| Host | Direction | What for |
|---|---|---|
| `prices.runescape.wiki` | outbound GET | prices and 5m/1h/6h/24h timeseries — see *Load on the Wiki* below |
| `pocketge.com` | outbound, browser only | `LinkBrowser.browse` when you click a chart button. The plugin itself does not POST anything to it. |
| `reddit.com` | outbound, browser only | two `LinkBrowser.browse` links to r/osrsflipping and r/GrandExchangeBets, from a click in the panel. Nothing is fetched or posted. |
| `github.com` | outbound, browser only | three `LinkBrowser.browse` links at the foot of the panel, all to the plugin's own repository: report a problem and suggest an idea (its Issues), and support the developer (its README). Nothing is fetched or posted. |
| `127.0.0.1` | **inbound, opt-in** | the local bridge, below |

### The local bridge — please read this bit

The plugin can run a small HTTP listener so that pocketge.com, **open in the
browser on the same machine**, can show the player's own trading data. This is
player information served over HTTP — the thing the rejected-features list
names — so, stated plainly, here is exactly what it is and what bounds it:

- **Off by default.** `localBridge()` defaults to `false`
  (`PocketGeTrackerConfig`). Nothing listens unless the user turns it on.
- **Loopback only.** `HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0)`
  — `LocalBridgeServer`. It is not reachable from the network.
- **What it serves** (`LocalBridgeServer.payload`, `/history`): the player's
  recent flips and fills, session and lifetime profit, the full flip ledger
  (`/history`, the same file the plugin keeps on disk), the bank stacks from
  the last bank snapshot (item, quantity, value) with cash and portfolio
  value, the portfolio's value over time (`/portfolio`, time and value pairs
  for the last character played), the watchlists, and the current top
  suggestion. Nothing about other players, no account identifiers, nothing
  from outside the GE and bank.
- **It accepts writes, not only reads.** `GET /flips`, `/history`, `/portfolio`, `/status`,
  `/nav`, `/tab/ack`, `/tab/closed`; `POST /favorites` and `/favoriteLists`.
  The POSTs change the plugin's own watchlists — item ids and list names — so
  the website and the in-game panel manage one list rather than two. The two
  `/tab` GETs carry only a request number or the page's own random tab id: a
  page confirming it took a chart click (the plugin waits 2.5 seconds for that,
  then opens the item in a new tab itself), and a best-effort "this tab is
  closing" that saves the wait when the browser lets it through.
  None of it touches the game: nothing on this path places an offer, moves an
  item, or sends anything to Jagex.
- **Origin-restricted, enforced server-side.** `ALLOWED_ORIGINS` is the two
  PocketGE origins plus `http://localhost:8901` for local development of the
  site. Every request — GET, POST and preflight — goes through
  `LocalBridgeServer.refusal` before any handler runs, and is answered 403 if
  its `Origin` is anything else or missing, or if its `Host` is not
  `127.0.0.1`/`localhost` on the bridge's own port (DNS rebinding). POST
  bodies must be `application/json`, which forces the browser's preflight.
  Refusals carry no CORS headers. `BridgeAccessTest` checks all of this over a
  real socket. What this protects against is **other websites** in the
  player's browser, which cannot forge an `Origin`; it is not a defence
  against a program already running on the machine, which can send any
  header — such a program runs as the player and could read RuneLite's files
  directly. The Private-Network-Access preflight is answered for the Chromium
  versions that send one; current Chrome instead asks the player once, with
  its own Local Network Access prompt, before pocketge.com may reach
  `127.0.0.1` at all.
- **Where it goes.** The plugin never sends any of it off the machine. The
  page that reads it is pocketge.com, loaded from the web into the player's own
  browser: a static site with no backend, which displays the data and stores
  the watchlists in the browser. Its analytics record that a bridge connected,
  never any of the data read from it.

### Writing into the Grand Exchange

With the game's own price or quantity box open on the offer screen, pressing
the **Fill key** (a keybind setting, E by default) puts the plugin's number in
it (`fillKeyListener` → `fillGePrompt`). This is the only way the plugin
writes to the chatbox input, written with the rule that "the only way text can
get into the chatbox is if the user is pressing keys themselves" in mind:

- it acts only on the player's own key press — never on a timer, an event, or
  a click;
- it takes the key only while the plugin's chip naming that key is on screen
  over a box that is open and waiting for a number (the box accepts digits
  and the k/m/b shorthands, so E has no meaning of its own there); every other
  press — in chat, mid-search, anywhere — passes through, as does the key with
  a modifier the setting does not include;
- it fills a number into an open box. It never presses Enter, and never
  places, confirms or cancels an offer — every trade action is still the
  player's click;
- clicking a chip, or a price on the plugin's card, only copies the number to
  the clipboard.

On "What would you like to buy?" the plugin puts the item it suggests in the
search's own result row — the "Previous search" row the game builds, built
the same way and labelled "PocketGE item:" (`fillSearchRow`), as Flipping
Copilot's suggestion row is. The game picks it on the player's own click or
Enter: the row carries the game's `meslayer_mode14_select` (754) as its op and
key listener, so the game runs it and sends the pick itself. The plugin never
runs a script there and never types. Until anything is typed, the fill key is
handed on to the game AS Enter (its key code changed, its character blocked —
how RuneLite's own Key Remapping hands on keys); after the first letter it is
a letter. Typing that runs straight on from a pick is swallowed until a pause
or an Enter, so a player who meant to type "earth rune" does not send "arth
rune" to public chat when the search closes. Enter, Escape and Backspace
cannot be the fill key: the boxes need them.

**Changed in this release:** 0.7.0 and 0.7.1 also filled the price box
automatically the moment it opened, and typed a price, a quantity or an item
name into the chatbox when the player clicked a chip or the card. That was a
plugin writing to the chatbox without a key press, and the earlier
description of it ("only fires from a click") was also not accurate. Both are removed: the key press
above is the whole mechanism now, and the item-search chip only names the
item to type.

### Load on the Wiki

Every request carries a descriptive User-Agent with a contact. The bulk
endpoints (`/latest`, `/volumes`, `/24h`) are one request each for every item;
the per-item `/timeseries` calls are the expensive ones and are bounded:

- **Shared back-off.** A 429, 403 or 5xx pauses *every* price request from the
  client — honouring `Retry-After`, otherwise a minute doubling to half an hour,
  plus up to 20% random so clients refused together don't return together.
  Loops over items stop at the first refusal (`MarketClient.backoffMs`,
  `MarketBackoffTest`).
- **The advisor's bulk round runs at most once a minute**, on the player's
  chosen interval (default 5 minutes). Separately, opening a GE offer screen
  re-reads `/latest` alone if the held copy is over 30 seconds old, so the
  price being typed matches the market. Logged out, the sidebar shows nothing
  that needs prices, so once a book is held the bulk round runs no more often
  than every 15 minutes (or the chosen interval, if that is longer), and the
  background per-item calls stop (an item the player clicks is still fetched).
  With no book yet — offline at launch, say — it retries once a minute, or on
  the back-off schedule if refused.
- **Per-item calls are cached per item:** 5-minute series for one 5-minute
  bucket, for the items in an active offer, the plan, the first 4 flip ideas
  after it, the top 8 sell cards on the list (plus up to 4 more that had a
  series and are still listed, so a stack cannot flip between two prices
  as its rank moves), an open offer screen, the watchlist row being
  inspected and the sell card on screen (about 29 at most, usually far
  fewer); between rounds, a sell card the player pages to, an item picked in
  the sidebar and an open offer screen fetch their own when it is missing or
  over a bucket old, as they are shown, each item at most once a minute on
  one shared retry clock (so one pick is one request), into a cache of at
  most 24, so they are priced as the website prices them rather than off
  the last insta-buy until the next cycle; 5-day extremes, for up to 60 favourites plus the 40
  highest-volume items, for an hour plus up to ten random minutes, at most 15
  requests a cycle on the short intervals (on the longer ones, just enough per
  cycle to cover the list once an hour), favourites capped at 60, and a
  favourite whose row finds none yet (just added, or a list switched to) is
  asked for at once, at most 5 a minute, logged in only (`OnDemandFetchTest`); 30-day ranges
  for half an hour, only for the capital plan's own positions (at most 8),
  4 requests a pass. A failed background item waits 5–10 minutes before it is
  asked for again; the offer screen retries its one item after a minute.
- **The offer-screen chart** picks its series as the site does: the range's
  timesteps in the site's order (1D: 5m, 1h, 6h, 24h; 5D: 1h, 5m, 6h, 24h;
  1M: 6h, 24h, 1h; 6M: 24h, 6h; 1Y: 24h), stopping at the first with five
  prints. A liquid item costs one series per range; a thin one walks the
  chain and can fetch up to four. The 5-minute step is one cache shared by
  the offer screen, the advisor and the chart, on one retry clock: whichever
  fetched it last, the others reuse it. While the chart is open it is topped
  up once it is older than 5 minutes, and the advisor's next cycle reuses
  that copy rather than asking again; the top-up never asks for the book.
  The others are fetched for the item on screen and kept for
  30 minutes (1h), 2 hours (6h) or 6 hours (24h), at most 24 series in all;
  a failed one is not asked for again for a minute. The range picked is
  remembered for later offers. The same back-off and the same advisor switch
  govern it.
- **The advisor switch is the network switch.** With it off the plugin makes
  no requests at all, including the on-demand ones from the offer screen and
  the watchlist.
- No request is made on the client thread, and none is started after the
  plugin is disabled: every task the plugin queues on RuneLite's shared
  executor, and every request inside a round, checks that it is still
  running, and the pending retry is cancelled in `shutDown`
  (`ShutdownQuietTest`).

### Stored data

The full flip ledger is a file, `RUNELITE_DIR/pocketge-flip-tracker/flips.jsonl`,
appended to as flips close. The one rewrite is a player's "Correct what I
paid…" on a trade: the file is written to a temporary copy beside it and
moved over it (atomically where the filesystem allows), changing only that
trade's rows (`CorrectPaidTest`). Appends and that rewrite take a lock on
`flips.jsonl.lock` beside it, so two RuneLite clients on one computer cannot
lose each other's rows.
Beside it, `portfolio-<account hash, hex>.csv`: one `time,value` line per
character every 15 minutes while logged in with the bank known and the
advisor's live prices in (no fetch is made for it), thinned to
hourly after 30 days and daily after a year (`PortfolioHistoryTest`). Written
whether or not the bridge is on, off the client thread.
The rest — the recent-flip window and open lots, the last bank snapshot per
account, the watchlists and the block list — is kept in the plugin's own config
group. That means it syncs with a RuneLite account like any plugin's settings;
the recent-flip state is rewritten when a GE offer fills. Apart from the
block list, none of it appears in the config panel.

### What changed since 0.7.1

0.7.1 (`5dfc177`, on the hub now) already had the Wiki back-off, the
server-side origin check and the config-write fix described above. New here:

- **The GE price fill is a key press now** — see *Writing into the Grand
  Exchange* above. Nothing is written to the chatbox when a prompt opens or
  on a click (`ChatboxInputTest`).
- **A GE purchase is never counted twice.** The client's EMPTY-every-slot
  burst while not logged in is ignored, as RuneLite's own GE plugin does, and
  the per-tick sweep waits out the login replay. Leagues, Deadman and beta
  worlds share the account hash but not the Exchange, so their offers are not
  tracked at all. Every character keeps its own slot records, with a one-time
  migration for saves written by 0.7.1 (`GeReplayTest`, `PluginGuardsTest`).
- **Nothing runs once the plugin is off.** Every task queued on the shared
  executor and every request in a round checks that the plugin is still
  running, and the pending retry is cancelled in `shutDown`
  (`ShutdownQuietTest`).
- Every setting is in the panel's own gear menu; RuneLite's config panel is
  grouped into sections and no longer shows the plugin's stored data (the
  favourite lists as JSON).
- Chart clicks: the bridge gained the two `/tab` GETs described above, so a
  click opens a new tab when no PocketGE tab confirms it, rather than going
  nowhere.
- Cards: a sell card prices a stack at what the plugin saw paid and at the
  card's own sell price everywhere; a buy that would fill at a loss after tax
  is flagged with a bid that breaks even (`LosingBuyTest`); new flips lead the
  suggestions ahead of selling the bank.
- A card's right-click adds **Copy PocketGE report** — a plain-text summary of
  what the plugin knows about one item, put on the system clipboard on that
  click, with no account names or hashes and nothing sent anywhere
  (`ReportTest`) — and **Forget what I paid**, which clears the plugin's own
  record of an item's purchases after a confirmation.
- Hovering a bank or inventory stack the plugin saw bought shows its profit or
  loss at the going sell price (a setting), noted stacks included. A stack
  that would sell at a loss is not marked worth selling. GE slots are boxed
  only when they need you: white (the plugin's "act here") when the price
  needs moving, green when ready to collect; each slot's bar fills in its buy
  or sell colour.
- An open PocketGE tab can follow the item on the GE offer screen (a setting;
  the same bridge `/nav` the chart button uses).
- The version and build time are shown at the foot of the gear menu and in
  the report.
- **A price chart over the chat while an offer is open** (a setting, on by
  default): pocketge.com's own chart, ported to Java2D — each side's prints as
  dots, period and recent highs and lows, the trend, the target and the price
  in the offer, and volume-by-price — with range buttons, on both the Set up
  offer and the Offer status screens. The wheel zooms time as the site's
  does, a drag pans, a double-click (or Reset zoom) shows everything. Its top
  edge drags to make it taller (up over the game) or shorter; the height is
  remembered, and a double-click on the edge puts it back to the chat's. It
  stands aside for the item search and the price and quantity prompts; over
  a price or quantity box a Chart button above the plugin's chip brings it
  back, and the same button, then reading Price, puts the box back. It takes
  only the clicks and wheel turns that land on it while logged in, on the
  game's own screens (not the welcome screen), with no menu open.
- Watchlist glow borders can be muted (a setting, and on a row's right-click
  menu) while the ▲/▼ and swing-% badges stay, in colour. The watchlist is
  no longer rebuilt when nothing on it changed, which made every highlighted
  row flash on each inventory change; its resize handle's dots are larger
  and lighter.
- The fill key is a keybind setting (E by default). On the item search the
  suggestion goes in the search's own result row, and the key picks it before
  anything is typed (see Writing into the Grand Exchange).
- High contrast is the default colour theme, as on pocketge.com. A Terminal
  saved while it was the default is moved over once
  (`themeDefaultMoved`); choosing Terminal afterwards keeps it.
- The gear menu's sections (Advisor, Display, Never recommend, Website link,
  the colour key) open and shut from their headers; Advisor alone is open to
  begin with, and the rest stay as left for the session.
- The sidebar's GE slot grid folds away from a ▾ at its top right, like the
  flip-ideas card, to one line that still counts the slots ready to collect
  and the ones to reprice (`GeSlotsFoldTest`). Open again for the session.
- A watchlist's colour is picked from a row of dots in the list switcher
  (twelve, all readable on the panel; `ListColourTest`). It used to be only
  on the dropdown's right-click, as hex codes; that submenu now has named
  dots too. The resize handle stays lit for the whole drag.
- Buys count against the item's 4-hour buy limit: what was bought since the
  window's first purchase is taken off every buy idea, the offer screen's
  quantity and the watchlist card, and an item whose limit is used up is
  not suggested until it resets (the card says when). Per character, kept
  through a restart, and seeded on upgrade from the last four hours of
  flips and open lots (`BuyLimitWindowTest`).
- The chart's price axis drags up and down to squash or stretch the
  prices, the dates drag to zoom time, and a drag on the chart moves it
  both ways, all as on pocketge.com; a double-click puts each back.
- Right-click any list in the switcher for its own menu (rename, colour,
  delete); deleting asks first. The watchlist handle's dots are larger and
  in the main text colour.
- The chart also shows when the chat has been shut with a tab, standing on
  the tab bar at the chat's height. The watchlist's day-tier arrows take the
  theme's buy and sell colours, as the 5-day badges already did.
- Find Opportunities' At 5D Highs / Lows rows take the theme's colours too
  (they were a fixed green and amber), and repaint when the theme changes.
  The gear menu grows down from the gear and scrolls rather than moving up
  the screen as sections open, and keeps one width. The chart's Chart /
  Price button stays in the chat's corner both ways (over a chart dragged
  taller than the chat, Price is in the chart's header), never over the
  game, with the header buttons and drag edge making room for it.
- The gear menu's colour theme, profit floor and alert level are rows of
  buttons. They were dropdowns, and a dropdown inside a popup menu closes the
  menu as it opens and then ignores the click — so none of the three changed
  anything.
- The flip list under the stats shows the range picked above them (1h, 4h,
  …, all time) rather than the latest 50 whatever the range: the latest five,
  the rest of the range one click away. Both it and the stats read the flip
  ledger file (once, off the client thread) so All time is every flip, not
  the newest 500. The "Flips to keep" setting that capped it is gone.
- The foot of the panel has quiet links to report a problem, suggest an idea
  and support the developer, all on the plugin's GitHub, and the version.
  The version moves on one patch with each update to this PR (0.7.27 now),
  so a screenshot or a report says exactly which build it came from.
- A sale is charged to this character's newest buys from before its offer
  was listed, leaving out units still waiting in a buy's GE box, instead of
  the oldest buys on record. Oldest-first charged a sale of 13,418 Aether
  catalyst bought at 277 against 24,500 left on record at 359 from a stack
  sold unseen, booking -1.12M on a sale that lost 26.8K; the card's PAID @
  already said 277. Lots now carry their character, the buy offer that made
  them and a saved sequence number; a sell records what was in each box when
  it was listed. The slot hover and the booking use one rule
  (`SaleCostTest`, `SaleProvenanceTest`). A booked trade can be re-priced
  from its row's right-click, "Correct what I paid…", which also moves the
  bridge's `historyRevision` so pocketge.com re-reads the corrected ledger.
  A stack's hover lists the batches it was bought in, one per buy offer,
  from the same lots its "Paid" line uses (`HeldBatchesTest`).
- A sell that goes through floats what it booked up off its own slot on the
  Exchange's slot screen, "+2,000 gp" in green or "-1,250 gp" in red, for
  three seconds. One not seen as it happened (Exchange shut, or sold while
  logged out and booked at login) plays the first time its slot is on
  screen, everything sold since added up. What has been shown is kept on
  the slot's saved record beside what it booked (two more numbers in the
  per-character slot rows), so this survives a restart, belongs to that
  character and that offer, and an upgrade pops nothing old. Drawn only
  over that slot's box; "Profit pops on GE slots" turns it off
  (`ProfitPopsTest`, `UnseenSaleTest`). Units a sale sold that no buy on
  record paid for (cooked, looted, bought before the plugin) are never
  booked as profit; their coins after tax are kept beside the figures on
  the same record (four more numbers, after the boxed pairs) and pop in
  gold with no sign, profit taking the square when both wait at once
  (`CoinPopsTest`). With the Exchange shut and the sidebar's slot squares
  on screen, the figure plays there instead, once (`SidebarPopsTest`). A
  finished slot keeps its bar full in the buy or sell colour inside its
  green box, so the two differ; the green box is drawn heavier than the
  white one.
- A stack you hold is priced to sell at the website's Target Sell on every
  surface: the sell cards, the bank marks and stack hovers (which now state
  the profit on a marked stack too, rating marks included), the offer
  screen, the inspect card and watchlist row, the header's Unrealized and
  the portfolio total, all from one series map, so one stack reads one way
  (`OneSellPriceTest`). Only the nudge to move a
  sell that is already working keeps the rule of never asking under what
  buyers are paying.
- A watchlist row's change badge and the price alert measure the day's
  change, as the website's 1D button does: the live mid against the mid 24
  hours ago, from the hourly series the range badges already fetch. They
  used the Wiki's /24h average, which is the last complete day's and after
  a fall still holds the day before (`DayChangeTest`). "A day ago" is
  looked up in the day's hours held, so it moves with the clock and needs
  no extra request between the hourly fetches. Which measure a row uses is
  fixed by its place in the list: the day's for the first 60, the /24h
  average (labelled as such) past them, so a row never swaps between the
  two; alerts are for watchlist rows only.

### Size

Larger than a typical hub plugin — roughly 35000 lines across 41 files,
plus 695 unit tests. The bulk is the sidebar UI (`AdvisorPanel`,
`FavoritesPanel`) and the pricing engine (`TradeEngine`, a port of the
website's own target-price maths so the two agree).

Happy to answer anything or split work out if that would make review easier.
