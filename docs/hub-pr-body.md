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

None. `build.gradle` has `compileOnly net.runelite:client` and `junit` for
tests, and nothing else — so there is no `verification-metadata` to review.

### Network

| Host | Direction | What for |
|---|---|---|
| `prices.runescape.wiki` | outbound GET | prices and 5m/1h timeseries |
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
- **Origin-restricted.** `ALLOWED_ORIGINS` is the two PocketGE origins plus
  `http://localhost:8901` for local development of the site. Requests from any
  other origin are refused, and the Private-Network-Access preflight is answered
  so Chromium will allow the https → localhost fetch at all.
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

### What changed since the released 0.6.3

86 commits, and almost all of it is the sidebar: a rewritten recommendation
card, a watchlist with 5-day high/low badges, bank and Grand Exchange slot
marks, and a colour theme the player picks. The parts this PR description is
really about are, deliberately, the parts that did not move:

- **The local bridge has no new surface.** Same endpoints, same
  `ALLOWED_ORIGINS`, same loopback bind, still off by default. The only change
  to `LocalBridgeServer` is one read-only accessor reporting whether a page is
  currently parked on `/nav`.
- **The Grand Exchange write path is unchanged in kind.** It now also fills the
  quantity control after the price, under the same rules: only from a click,
  only while the game's own prompt is already open, and never placing,
  confirming or cancelling an offer.

### Size

Larger than a typical hub plugin — roughly 22172 lines across 36 files,
plus 275 unit tests. The bulk is the sidebar UI (`AdvisorPanel`,
`FavoritesPanel`) and the pricing engine (`TradeEngine`, a port of the
website's own target-price maths so the two agree).

Happy to answer anything or split work out if that would make review easier.
