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
browser on the same machine**, can show the session you are playing. It is the
most unusual thing in this codebase, so, stated plainly:

- **Off by default.** `localBridge()` defaults to `false`
  (`PocketGeTrackerConfig`). Nothing listens unless the user turns it on.
- **Loopback only.** `HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0)`
  — `LocalBridgeServer`. It is not reachable from the network.
- **It accepts writes, not only reads.** `GET /flips`, `/history`, `/status`,
  `/nav`; `POST /favorites` and `/favoriteLists`. The POSTs change the plugin's
  own watchlists — item ids and list names — so the website and the in-game
  panel manage one list rather than two. They do not touch the game: nothing on
  this path places an offer, moves an item, or sends anything to Jagex.
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
  directly. The Private-Network-Access preflight is answered so Chromium will
  allow the https → localhost fetch at all.
- **Nothing leaves the machine on this path.** The data served is the session
  the player is looking at anyway.

### Writing into the Grand Exchange

Clicking a price on the plugin's card types that number into the GE's price
prompt (`fillGePrice`). Worth being explicit about the limits, since this is the
area the third-party client guidelines care about:

- it only fires from a click the user makes, never on a timer or an event;
- it only acts while the game is **already** showing its own "set a price"
  chatbox prompt, and does nothing otherwise;
- it types a number into a prompt that is open and waiting. It does not place,
  confirm or cancel an offer — every actual trade action is still the player's
  click.
- the price is also put on the clipboard, so the same thing is achievable by
  hand.

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
  that needs prices, so the bulk round runs no more often than every 15
  minutes (or the chosen interval, if that is longer) and the background
  per-item calls stop (an item the player clicks is still fetched).
- **Per-item calls are cached per item:** 5-minute series for one 5-minute
  bucket; 5-day extremes for an hour plus up to ten random minutes, at most 15
  requests a cycle on the short intervals (on the longer ones, just enough per
  cycle to cover the list once an hour), favourites capped at 60; 30-day ranges
  for half an hour, only for the capital plan's own positions (at most 8),
  4 requests a pass. A failed background item waits 5–10 minutes before it is
  asked for again; the offer screen retries its one item after a minute.
- **The advisor switch is the network switch.** With it off the plugin makes
  no requests at all, including the on-demand ones from the offer screen and
  the watchlist.
- No request is made on the client thread, and none after the plugin is
  disabled: every task the plugin queues on RuneLite's shared executor checks
  that it is still running, and the pending retry is cancelled in `shutDown`
  (`ShutdownQuietTest`).

### What changed since 0.7.0

A maintenance release, mostly about the above:

- **Config writes no longer restart the advisor.** `onConfigChanged` restarted
  the advisor (an immediate round of requests) and the bridge on every write
  to the group — including the plugin's own data writes, such as the bank
  snapshot on every bank change. It now acts on the one setting that changed
  (`ConfigChangeTest`); only the advisor's own switch and interval reschedule
  it.
- The back-off, caching and logged-out behaviour above. A failed bulk fetch no
  longer clears the prices already held, and an offer-screen fetch that came
  back empty no longer re-ran itself in a loop.
- The 30-day range lookups ran blocking HTTP on the client thread; they are on
  the plugin's executor now.
- The bridge's origin check is enforced server-side, as described above. The
  0.7.0 description said other origins were refused; that was only true of
  reading the replies, not of the requests themselves — another website could
  still edit the player's lists. Other websites are refused outright now.
- The key drawn beside the bank no longer covers the bank's own controls,
  and can be switched off in the settings.

### Size

Larger than a typical hub plugin — roughly 23000 lines across 36 files,
plus 323 unit tests. The bulk is the sidebar UI (`AdvisorPanel`,
`FavoritesPanel`) and the pricing engine (`TradeEngine`, a port of the
website's own target-price maths so the two agree).

Happy to answer anything or split work out if that would make review easier.
