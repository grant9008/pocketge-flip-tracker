# Probe: does the OSRS Wiki prices API publish Hardcore Worlds prices?

Probed 2026-11-06 ~14:40 UTC. Every request used curl with only `User-Agent: pocketge-audit`
(no contact details). Read-only; no source code changed.

## TL;DR

- **No Hardcore Worlds price route exists.** All 9 candidate prefixes returned 404 on v1
  (`/latest` and `/mapping`), and `v2/hardcore` returned 404 too.
- The prices site front-end (`prices.runescape.wiki`) only knows three games:
  `osrs`, `dmm`, `rs`. Its bundle has no `hardcore`, `fsw`, `league` or `seasonal` strings.
- The docs page does not mention Hardcore Worlds, Deadman/DMM, FSW, leagues or seasonal
  at all. It now documents **v2** (`/api/v2/osrs`) as the main API; v1 "is still available
  but may be removed in the future".
- Data source changed: "Data retrieved from this API before 30 September 2026 came from
  RuneLite *only*. All new data comes from Jagex, which includes all game clients."
  The docs do not say whether Jagex data from Hardcore Worlds (a separate economy)
  is left out of the `osrs` feed or mixed into it. **Not answered by the API or docs.**
- DMM: `v1/dmm/latest?id=561` still returns 200, but its last trade is from 2026-02-28.
  `v2/dmm/*` returns 404, even though the front-end bundle builds `/api/v2/${game}/...`
  URLs for `dmm`.
- Hardcore Worlds wiki page: separate servers and "a separate economy from the main game",
  but players "can still trade with other players, use the Grand Exchange". It says
  nothing about price data or the wiki's prices for these worlds.

## 1. Baseline

| URL | Status | Body |
|---|---|---|
| `https://prices.runescape.wiki/api/v1/osrs/latest?id=561` | 200 | `{"data":{"561":{"high":160,"highTime":1793975992,"low":156,"lowTime":1793975985}}}` |
| `https://prices.runescape.wiki/api/v1/dmm/latest?id=561` | 200 | `{"data":{"561":{"high":1,"highTime":1772300461,"low":54,"lowTime":1772298225}}}` |

Timestamps: osrs highTime 1793975992 = 2026-11-06 14:39:52 UTC (live).
dmm highTime 1772300461 = 2026-02-28 17:41:01 UTC; lowTime 1772298225 = 2026-02-28 17:03:45 UTC
(stale, more than 8 months old; high=1 / low=54 looks like end-of-season junk).

## 2. Candidate routes

Every 404 body has this form (Express default), e.g. for `hardcore/latest`:

```
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>Error</title>
</head>
<body>
<pre>Cannot GET /v1/hardcore/latest</pre>
</body>
</html>
```

(The query string is not echoed; the path is `/v1/<P>/<endpoint>`.)

| P | `/api/v1/$P/latest?id=561` | `/api/v1/$P/mapping` |
|---|---|---|
| hardcore | 404 `Cannot GET /v1/hardcore/latest` | 404 `Cannot GET /v1/hardcore/mapping` |
| hc | 404 `Cannot GET /v1/hc/latest` | 404 `Cannot GET /v1/hc/mapping` |
| hcw | 404 `Cannot GET /v1/hcw/latest` | 404 `Cannot GET /v1/hcw/mapping` |
| hardcore-worlds | 404 `Cannot GET /v1/hardcore-worlds/latest` | 404 `Cannot GET /v1/hardcore-worlds/mapping` |
| hardcoreworlds | 404 `Cannot GET /v1/hardcoreworlds/latest` | 404 `Cannot GET /v1/hardcoreworlds/mapping` |
| hcm | 404 `Cannot GET /v1/hcm/latest` | 404 `Cannot GET /v1/hcm/mapping` |
| fsw | 404 `Cannot GET /v1/fsw/latest` | 404 `Cannot GET /v1/fsw/mapping` |
| seasonal | 404 `Cannot GET /v1/seasonal/latest` | 404 `Cannot GET /v1/seasonal/mapping` |
| osrs-hardcore | 404 `Cannot GET /v1/osrs-hardcore/latest` | 404 `Cannot GET /v1/osrs-hardcore/mapping` |

v2:

| URL | Status | Body (first 300 bytes) |
|---|---|---|
| `https://prices.runescape.wiki/api/v2/osrs/latest?id=561` | 200 | `{"data":{"561":{"high":160,"highTime":1793975992,"low":156,"lowTime":1793975985}}}` |

No P worked in v1, so the "v2 + each working P" step had nothing to run. Extra checks
(4 small requests, added for context):

| URL | Status | Body |
|---|---|---|
| `https://prices.runescape.wiki/api/v2/hardcore/latest?id=561` | 404 | `Cannot GET /v2/hardcore/latest` |
| `https://prices.runescape.wiki/api/v2/dmm/latest?id=561` | 404 | `Cannot GET /v2/dmm/latest` |
| `https://prices.runescape.wiki/api/v2/dmm/latest` | 404 | `Cannot GET /v2/dmm/latest` |
| `https://prices.runescape.wiki/api/v2/dmm/mapping` | 404 | `Cannot GET /v2/dmm/mapping` |

## 3. Docs: `https://oldschool.runescape.wiki/w/RuneScape:Real-time_Prices?action=raw`

HTTP 200, 7460 bytes. Lines quoted verbatim.

There are **no** lines that mention hardcore, deadman, DMM, fresh start, FSW, leagues,
seasonal or telemetry. The only route documented is `/api/v2/osrs`; neither `/dmm` nor
`/24h` is documented any more.

Data source / Jagex / RuneLite:

```
We've partnered with Jagex to bring '''real-time Grand Exchange prices''' to the wiki. You can check an item's current prices by clicking the '''View Real-time Prices''' on any item article.
Data retrieved from this API before 30 September 2026 came from [https://runelite.net/ RuneLite] ''only''. All new data comes from Jagex, which includes all game clients.
```

Routes / base URL:

```
==Routes==
* API endpoint: <code>prices.runescape.wiki/api/v2/osrs</code>
* <code>[https://prices.runescape.wiki/api/v2/osrs/latest '''/latest''']</code>
* <code>id</code> - ''(optional)'' Item ID. If provided, will only display the latest price for this item. Example: [https://prices.runescape.wiki/api/v2/osrs/latest?id=4151 Abyssal whip]
* <code>[https://prices.runescape.wiki/api/v2/osrs/mapping '''/mapping''']</code>
* <code>[https://prices.runescape.wiki/api/v2/osrs/5m '''/5m''']</code>
* <code>timestamp</code> - ''(optional)'' Timestep to return prices for. If provided, will display 5-minute averages for all items we have data on for this time. The <code>timestamp</code> field represents the beginning of the 5-minute period being averaged. Example: [https://prices.runescape.wiki/api/v2/osrs/5m?timestamp=1615733400]
* <code>[https://prices.runescape.wiki/api/v2/osrs/1h '''/1h''']</code>
* <code>timestamp</code> - ''(optional)'' Timestep to return prices for. If provided, will display 1-hour averages for all items we have data on for this time. The <code>timestamp</code> field represents the beginning of the 1-hour period being averaged. Example: [https://prices.runescape.wiki/api/v2/osrs/1h?timestamp=1615734000]
* <code>[https://prices.runescape.wiki/api/v2/osrs/timeseries?lookback=6h&id=4151 '''/timeseries''']</code>
* <code>lookback</code> - ''(required)'' Lookback for the time-series. Valid options are <code>'6h', '24h', '7d', '30d', '6m', '1y'</code>.
Example: [https://prices.runescape.wiki/api/v2/osrs/timeseries?lookback=6h&id=4151]
```

v2 / migration:

```
==Migrating from v1 to v2==
The v1 API is still available but may be removed in the future. If you are using this API, you should look at switching to v2.
The changes in the v2 API are:
* <code>avgLowPrice</code> and <code>avgHighPrice</code> in the aggregation endpoints (5m and 1h, etc) can return floats with up to two decimal places.
* The timeseries endpoint has been updated and now uses a <code>lookback</code> parameter instead of a timestep. Setting the lookback to "24h", "7d", "6m", or "1y" is roughly equivalent to the timesteps "5m", "1h", "6h", and "24h". 
The v1 and v2 APIs can both return prices that are over the signed 32-bit integer limit (2,147,483,647).
```

Also relevant (red banner under Routes):

```
{{Colour|Red|'''All prices returned by the API may exceed what can be supported by 32 bit integers. Average prices may include decimal points.'''}}
```

## 4. Front-end: `https://prices.runescape.wiki/`

HTTP 200, 717 bytes, a Vite SPA shell (`<title>Grand Exchange</title>`). Loads one script and
one stylesheet:

```
<script type="module" crossorigin src="/assets/index-3k9pwPiC.js"></script>
<link rel="stylesheet" crossorigin href="/assets/index-Bru3vpzy.css">
```

`https://prices.runescape.wiki/assets/index-3k9pwPiC.js`: HTTP 200, 1,554,522 bytes. No
dynamic `import()` chunks or other `/assets/*.js` references were found.

Grep results (case-insensitive):

- `hardcore`: **0 matches**. `fsw`, `league`, `seasonal`, `api/v1`: **0 matches**.
- The game/economy list is hard-coded to three values, with `osrs` as the default:
  ```
  var Jo=[`osrs`,`dmm`,`rs`],Yo=`osrs`,Xo=e=>e!=null&&Jo.includes(e)
  ```
- Labels: ``case`osrs`:t=`OSRS`;break;case`dmm`:t=`OSRS Deadman`;break;case`rs`:t=`RuneScape`;break}return`${t} - Grand Exchange` ``
- Favourites storage keys: `wgl-os-favs`, `wgl-dmm-favs`, `wgl-rs-favs`.
- The GE tax logic treats `osrs` and `dmm` alike: ``case`osrs`:case`dmm`:return ns.has(t)?0:Math.min(Math.floor(n*Zo),Qo)`` with `Zo=.02`, `Qo=5e6` (2% tax, 5M cap). `rs` has no cap.
- API calls all use v2, parameterised by game:
  ```
  fetch(`/api/v2/${e}/mapping`)
  fetch(`/api/v2/${e}/latest`)
  fetch(`/api/v2/${e}/timeseries?lookback=${n}&id=${t}`)
  fetch(`/api/v2/${e}/volumes`)
  ```
  (Note: a `/volumes` endpoint exists in the front-end but is not in the docs. Not probed.)
- Each of `osrs`, `dmm`, `rs` appears in exactly 7 `case` branches; there is no fourth case.

## 5. Wiki: `https://oldschool.runescape.wiki/w/Hardcore_Worlds?action=raw`

HTTP 200, 9397 bytes, no redirect. Relevant text, verbatim:

Economy / Grand Exchange / trading / account relation (lead paragraph):

```
'''Hardcore Worlds''' are [[members]]-only [[game mode]] in which players have only one life. They run on a separate set of [[server]]s than the standard worlds. These worlds have no [[experience]] or [[drop rate]] boosts and have a separate economy from the main game. Additionally, they come with separate Hiscores under the Game Modes section. Players on Hardcore Worlds are still [[main account]]s, therefore they can still [[trade]] with other players, use the [[Grand Exchange]], or participate in group [[Monster killing|PvM]], unlike in other modes such as [[Ironman Mode|ironman]].

It was released on [[4 November]] [[2026]] at 11:00 AM {{Wp|UTC}}. The game mode was not preceded by any polls for its addition.
```

Separate character (fresh start):

```
Players begin as a fresh new character on [[Tutorial Island]], where they can speak to [[Death's Delegate]] near the [[Magic Instructor]]'s house. Doing so grants players access to [[Death's Domain]] where they can then speak to [[Death (NPC)|Death]] to accept his challenge. 
```

Items moving to the main game (one way, on death only):

```
===Reset===
This option will reset '''ALL''' progress on the player's character, including [[quest]]s, [[skill]]s, items, and the 3-4 items that were protected upon death. The player may then continue playing on Hardcore Worlds as a freshly wiped character. Resetting can be done an infinite amount of times. After each reset, there is a 3-day cooldown on receiving GP from the [[Stronghold of Security]].

===Transfer===
This option will remove the player's character from Hardcore Worlds, with no option to return. Doing so will allow players to keep their protected items, as well as any items in their bank and other storage facilities.

'''NOTE: Regardless of choice, any items that would usually be sent to the player's [[gravestone]] will be permanently deleted.'''
```

(The page does not say where "Transfer" sends the character; it says only "remove the
player's character from Hardcore Worlds, with no option to return" and that bank items are
kept. The page also does not say whether login is shared with the player's normal account.)

World list (32 worlds; `mems=hardcore`):

```
634–648  United States (east)   (635 GotR, 636 Wintertodt, 637 Barbarian Assault, 638 & 647 Group PvM, 645 Forestry, 646 Pest Control)
649      United States
650–659  United States (west)   (653 Blast Furnace, 654 Barbarian Assault, 655 & 659 Group PvM, 658 Tempoross)
669–672  United Kingdom         (669 Wintertodt, 672 Group PvM)
673      Brazil
674      Singapore
```

(No worlds 660–668.) Hiscores templates use `mode=hcmain`.

Price data: **the page does not mention price data, the real-time prices tool or GE prices
for these worlds.**

## 6. Hardcore route endpoint survey

Skipped: no hardcore route worked, so `/latest`, `/mapping`, `/5m`, `/1h`, `/24h` and
`/timeseries` were not probed for one.

## Open questions (not answerable from these sources)

1. Since 2026-09-30 the `osrs` feed comes from Jagex, "all game clients". Are Hardcore Worlds
   GE trades (worlds 634–674, separate economy) left out of it or mixed in? It's worth
   asking in the wiki's #api-discussion Discord channel, which the docs point to.
2. Is `v1/dmm` just an old leftover? (Its last data is from Feb 2026; it isn't in v2 or the docs.)
