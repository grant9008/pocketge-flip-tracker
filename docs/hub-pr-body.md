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
| `prices.runescape.wiki` | outbound GET | prices and 5m/1h/6h timeseries — see *Load on the Wiki* below |
| `pocketge.com` | outbound, browser only | `LinkBrowser.browse` when you click a chart button. The plugin itself does not POST anything to it. |
| `reddit.com` | outbound, browser only | two `LinkBrowser.browse` links to r/osrsflipping and r/GrandExchangeBets, from a click in the panel. Nothing is fetched or posted. |
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
  value, the watchlists, and the current top suggestion. Nothing about other
  players, no account identifiers, nothing from outside the GE and bank.
- **It accepts writes, not only reads.** `GET /flips`, `/history`, `/status`,
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
**E** puts the plugin's number in it (`fillKeyListener` → `fillGePrompt`). This
is the only way the plugin writes to the chatbox input, written with the rule
that "the only way text can get into the chatbox is if the user is pressing
keys themselves" in mind:

- it acts only on the player's own key press — never on a timer, an event, or
  a click;
- it takes E only while the plugin's chip naming that key is on screen over a
  box that is open and waiting for a number (the box accepts digits and the
  k/m/b shorthands, so E has no meaning of its own there); every other E —
  in chat, in the item search, anywhere — passes through, as does E with any
  modifier;
- it fills a number into an open box. It never presses Enter, and never
  places, confirms or cancels an offer — every trade action is still the
  player's click;
- clicking a chip, or a price on the plugin's card, only copies the number to
  the clipboard.

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
  after it, the top 8 sell cards on the list, an open offer screen and the
  watchlist row being inspected (about 24 at most, usually far fewer); 5-day extremes, for up to 60 favourites plus the 40
  highest-volume items, for an hour plus up to ten random minutes, at most 15
  requests a cycle on the short intervals (on the longer ones, just enough per
  cycle to cover the list once an hour), favourites capped at 60; 30-day ranges
  for half an hour, only for the capital plan's own positions (at most 8),
  4 requests a pass. A failed background item waits 5–10 minutes before it is
  asked for again; the offer screen retries its one item after a minute.
- **The advisor switch is the network switch.** With it off the plugin makes
  no requests at all, including the on-demand ones from the offer screen and
  the watchlist.
- No request is made on the client thread, and none is started after the
  plugin is disabled: every task the plugin queues on RuneLite's shared
  executor, and every request inside a round, checks that it is still
  running, and the pending retry is cancelled in `shutDown`
  (`ShutdownQuietTest`).

### Stored data

The full flip ledger is a file, `RUNELITE_DIR/pocketge-flip-tracker/flips.jsonl`.
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
  loss at the going sell price (a setting). GE slot borders are grey while
  working, yellow when the price needs moving, green when ready to collect.
- An open PocketGE tab can follow the item on the GE offer screen (a setting;
  the same bridge `/nav` the chart button uses).
- The version and build time are shown at the foot of the gear menu and in
  the report.

### Size

Larger than a typical hub plugin — roughly 26000 lines across 37 files,
plus 431 unit tests. The bulk is the sidebar UI (`AdvisorPanel`,
`FavoritesPanel`) and the pricing engine (`TradeEngine`, a port of the
website's own target-price maths so the two agree).

Happy to answer anything or split work out if that would make review easier.
