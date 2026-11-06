# Probe: OSRS Wiki prices API, v1 vs v2

Probed 2026-11-06, 15:44:08–15:44:44 UTC (all 31 requests in one ~40 s run, 1 s apart).
Every request was `curl -sS -H "User-Agent: pocketge-audit"`, with no other custom headers.
Bodies were fetched uncompressed (no `--compressed`), so the byte counts below are raw JSON sizes.
This is research only. No source code was changed.

Base URLs:
- v1: `https://prices.runescape.wiki/api/v1/osrs`
- v2: `https://prices.runescape.wiki/api/v2/osrs`

Endpoints the plugin calls today (`src/main/java/com/pocketge/tracker/MarketClient.java`, base
`api/v1/osrs`): `/latest`, `/24h`, `/volumes`, `/timeseries?timestep=1h&id=`,
`/timeseries?timestep=6h&id=`, `/timeseries?timestep=<var>&id=`.

## TL;DR

- **Every v1 path probed also works on v2 and returns 200**, including `/24h` and `/volumes`,
  which the v2 docs leave out.
- **No deprecation signal in any header.** No `Deprecation`, `Sunset`, `Warning`, `Link`,
  `Retry-After` or `X-RateLimit-*`/`RateLimit-*` header appeared on any v1 or v2 response.
- `/latest`, `/mapping` and `/volumes` **return the same bytes** on v1 and v2 (same ETag).
  `/mapping` and `/volumes` differ only in `Cache-Control`.
- `/5m`, `/1h` and `/24h` have the **same shape and the same item set**, but in v2
  `avgHighPrice`/`avgLowPrice` **can be decimals (up to 2 places)**. In v1 they are always whole numbers.
  Each v1 value is the v2 value rounded (no difference larger than 0.5 in any entry).
- `/timeseries` **changed its query and its envelope**:
  - v1 takes `timestep=5m|1h|6h|24h` and always returns up to 365 points.
  - v2 takes `lookback=6h|24h|7d|30d|6m|1y`. The server picks the timestep, and the
    number of points depends on the window.
  - v2 adds `startTimestamp`, `endTimestamp` and `timestep` (in seconds) to the top-level object.
  - v2 **returns 400** for `timestep=5m`. It does not ignore it.
  - v2 point prices are fractional from 2026-09-30 onwards. Older daily points are still
    whole numbers. That date matches the docs' note that data before 30 September 2026
    came from RuneLite only, and data since then comes from Jagex.
- Unknown item id: both versions return **200 with `data: []`**, not 404. v2 also echoes the
  window and timestep.

## Common response headers

These 15 headers appeared on **every** response, v1 and v2 alike:

```
date: <request time>
content-type: application/json; charset=utf-8
server: cloudflare
x-dns-prefetch-control: off
x-frame-options: SAMEORIGIN
strict-transport-security: max-age=31536000; includeSubDomains; preload
x-download-options: noopen
x-content-type-options: nosniff
x-xss-protection: 1; mode=block
access-control-allow-origin: *
etag: <weak etag, see table>
cf-cache-status: <see table>
vary: accept-encoding   (written "Accept-Encoding" on streamed bodies, "accept-encoding" on small ones)
cf-ray: <id>-IAD
alt-svc: h3=":443"; ma=86400
```

(The proxy's leading `HTTP/1.1 200 Connection Established` line is left out. All responses were HTTP/2.)

Only five header names vary between responses: `content-length`, `cache-control`, `expires`,
`age` and `accept-ranges`. A dash means the header was absent. Responses with no
`content-length` were sent chunked/streamed.

| request | status | bytes | content-length | cache-control | expires | age | accept-ranges | cf-cache-status | etag |
|---|---|---|---|---|---|---|---|---|---|
| v1 latest?id=4151 | 200 | 89 | 89 | public, must-revalidate, max-age=60 | — | 8 | bytes | HIT | `W/"59-5k7lvBOykoif34jGKid3IQnasdA"` |
| v2 latest?id=4151 | 200 | 89 | 89 | public, must-revalidate, max-age=60 | — | — | bytes | EXPIRED | `W/"59-B7XcfTbfy5hWQEboR2Zsruc4ccM"` |
| v1 latest | 200 | 343793 | — | public, must-revalidate, max-age=60 | — | 12 | — | HIT | `W/"53ef1-nzoFmj23nGmHtM32+c+czf5qhHM"` |
| v2 latest | 200 | 343793 | — | public, must-revalidate, max-age=60 | — | 24 | — | HIT | `W/"53ef1-nzoFmj23nGmHtM32+c+czf5qhHM"` |
| v1 mapping | 200 | 866865 | — | public, must-revalidate, max-age=3600 | — | 2607 | — | HIT | `W/"d3a31-V+YCFdoHksDS0SuornuCAi+8G+M"` |
| v2 mapping | 200 | 866865 | — | public, max-age=60, s-maxage=300, stale-while-revalidate=5 | — | 45 | — | HIT | `W/"d3a31-V+YCFdoHksDS0SuornuCAi+8G+M"` |
| v1 5m | 200 | 159285 | — | — | Fri, 06 Nov 2026 15:45:10 GMT | 242 | — | HIT | `W/"26e35-baz9emZR8NedNiHGEOaZ7N9Qp5U"` |
| v2 5m | 200 | 160711 | — | — | Fri, 06 Nov 2026 15:45:10 GMT | 251 | — | HIT | `W/"273c7-6WSMKsrvUnL79VuVwHlBu63gKlU"` |
| v1 1h | 200 | 280886 | — | — | Fri, 06 Nov 2026 16:00:10 GMT | 2643 | — | HIT | `W/"44936-ZpAakf2pjt3CblZfQ0sHEfxuQ+c"` |
| v2 1h | 200 | 287820 | — | — | Fri, 06 Nov 2026 16:00:10 GMT | 2652 | — | HIT | `W/"4644c-z5BhC3IxpikEvPP27o1mKgJjvDA"` |
| v1 24h | 200 | 387766 | — | — | Sat, 07 Nov 2026 00:00:10 GMT | 56645 | — | HIT | `W/"5eab6-DLnMX7NkpkJDRgz/XnQcvCz+zQo"` |
| v2 24h | 200 | 406841 | — | — | Sat, 07 Nov 2026 00:00:10 GMT | 56652 | — | HIT | `W/"63539-a83psJT8vj2Px1Ju80guZkwV+1U"` |
| v1 volumes | 200 | 48230 | — | public, must-revalidate, max-age=3600 | — | 3457 | — | HIT | `W/"bc66-7Pqt5s1tHJiW7oTmZx4g4NZCxq0"` |
| v2 volumes | 200 | 48230 | — | public, max-age=60, s-maxage=300, stale-while-revalidate=5 | — | 154 | — | HIT | `W/"bc66-7Pqt5s1tHJiW7oTmZx4g4NZCxq0"` |
| v1 ts timestep=5m 4151 | 200 | 39206 | — | — | Fri, 06 Nov 2026 15:45:10 GMT | — | — | HIT | `W/"9926-/tU2pjwFMRrnyCgTeq18QmTuyco"` |
| v1 ts timestep=1h 4151 | 200 | 40221 | — | — | Fri, 06 Nov 2026 16:00:10 GMT | 2646 | — | HIT | `W/"9d1d-HIU+3UjxlLzqa3k8T10crO6T0Lo"` |
| v1 ts timestep=6h 4151 | 200 | 40591 | — | — | Fri, 06 Nov 2026 18:00:10 GMT | 13154 | — | HIT | `W/"9e8f-NixgV37JfjNS0GQ0o0gygI/FEeU"` |
| v1 ts timestep=24h 4151 | 200 | 41769 | — | — | Sat, 07 Nov 2026 00:00:10 GMT | 56494 | — | HIT | `W/"a329-Y54nc69TVqVpK/4GwxVlR97mHqE"` |
| v2 ts lookback=6h 4151 | 200 | 8018 | — | — | Fri, 06 Nov 2026 15:45:10 GMT | — | — | HIT | `W/"1f52-erDT2HKyyTsjMBls81x4ejiFqBQ"` |
| v2 ts lookback=24h 4151 | 200 | 31907 | — | — | Fri, 06 Nov 2026 15:45:10 GMT | 258 | — | HIT | `W/"7ca3-Sjnh+xznPXR/WPTRJAy37C5YbLQ"` |
| v2 ts lookback=7d 4151 | 200 | 19440 | — | — | Fri, 06 Nov 2026 16:00:10 GMT | 2644 | — | HIT | `W/"4bf0-UfuHST1tjolG1K++c1fOmdk8cgs"` |
| v2 ts lookback=30d 4151 | 200 | 14139 | — | — | Fri, 06 Nov 2026 18:00:10 GMT | 13408 | — | HIT | `W/"373b-MzhygZcREO4b0bWYzjdHkfd7/y0"` |
| v2 ts lookback=6m 4151 | 200 | 20778 | — | — | Sat, 07 Nov 2026 00:00:10 GMT | 56408 | — | HIT | `W/"512a-qeJRaoEMn/bXkXk0tCroZTFCN9M"` |
| v2 ts lookback=1y 4151 | 200 | 42053 | — | — | Sat, 07 Nov 2026 00:00:10 GMT | 56162 | — | HIT | `W/"a445-o5STb9Sb6eugrzfVZ3CcsZO8TCU"` |
| v2 ts timestep=5m 4151 | **400** | 79 | 79 | — | — | — | — | BYPASS | `W/"4f-cIBe8lv3blmZYE1uIvqxnrlMWjQ"` |
| v1 ts timestep=5m 561 | 200 | 39959 | — | — | Fri, 06 Nov 2026 15:45:10 GMT | 246 | — | HIT | `W/"9c17-h5NKde+qTGRoQoXcxEbfpEXP7o4"` |
| v2 ts lookback=24h 561 | 200 | 33002 | — | — | Fri, 06 Nov 2026 15:45:10 GMT | 261 | — | HIT | `W/"80ea-ruQCKlJOGtJq10ftIJoPdJzCevY"` |
| v2 ts lookback=24h id=99999999 | 200 | 98 | 98 | — | Fri, 06 Nov 2026 15:45:10 GMT | — | bytes | MISS | `W/"62-Vzp+u6wvWeOGrv51tchCAOt7o3w"` |
| v1 ts timestep=5m id=99999999 | 200 | 29 | 29 | — | Fri, 06 Nov 2026 15:45:10 GMT | — | bytes | MISS | `W/"1d-iuHLmQ4ZO4EpIajFsyiPUq2O2/g"` |
| v2 24h?timestamp=0 | 200 | 25 | 25 | no-cache | — | — | bytes | MISS | `W/"19-MqW1HiKeX/MFAHInncxXoD+qKvE"` |

Header notes:
- Aggregate endpoints (`/5m`, `/1h`, `/24h`, `/timeseries`) use `Expires` set to the next bucket
  boundary plus 10 s, with no `Cache-Control`. This is the same on v1 and v2.
- `/mapping`: v1 has `max-age=3600`. v2 has `max-age=60, s-maxage=300, stale-while-revalidate=5`.
  For browsers and clients, v2 asks for a refresh **60× more often**, although the body is identical.
- `/volumes`: same change (v1 `max-age=3600`, v2 `max-age=60, s-maxage=300, stale-while-revalidate=5`).
- No rate-limit headers on either version, so the API does not report any limits.

## 1. `/latest`

### `?id=4151`
- `https://prices.runescape.wiki/api/v1/osrs/latest?id=4151` → 200, 89 B
  `{"data":{"4151":{"high":674494,"highTime":1793979761,"low":655400,"lowTime":1793979768}}}`
- `https://prices.runescape.wiki/api/v2/osrs/latest?id=4151` → 200, 89 B
  `{"data":{"4151":{"high":673844,"highTime":1793979836,"low":655400,"lowTime":1793979815}}}`
- Same shape. The values differ only because v2 was fetched 9 s later (cache `EXPIRED`, a new trade had come in).
  Top-level keys: `data`. Entry fields: `high`, `highTime`, `low`, `lowTime` (all integers).

### whole book
- `https://prices.runescape.wiki/api/v1/osrs/latest` → 200, 343,793 B, 4,552 items
- `https://prices.runescape.wiki/api/v2/osrs/latest` → 200, 343,793 B, 4,552 items
- **Byte-identical** (`cmp` equal, same ETag `W/"53ef1-…"`). Top-level keys: `data`.
- 4151: `{"high":674494,"highTime":1793979761,"low":655400,"lowTime":1793979768}`
- 561: `{"high":160,"highTime":1793979777,"low":155,"lowTime":1793979763}`

## 2. `/mapping`

- `https://prices.runescape.wiki/api/v1/osrs/mapping` → 200, 866,865 B, a JSON **array** of 4,675 entries
- `https://prices.runescape.wiki/api/v2/osrs/mapping` → 200, 866,865 B, a JSON **array** of 4,675 entries
- **Byte-identical** (same ETag `W/"d3a31-…"`). Only `Cache-Control` differs (see above).
- Every field name seen across all entries (same on both versions):
  `examine`, `highalch`, `icon`, `id`, `limit`, `lowalch`, `members`, `name`, `value`
  (present in all 4,675: `examine`, `icon`, `id`, `members`, `name`; optional: `value` 4,655, `lowalch`/`highalch` 4,574, `limit` 4,161).
- Full entry for 4151 (same on both):
  ```json
  {"examine":"A weapon from the Abyss.","id":4151,"members":true,"lowalch":48000,"limit":70,"value":120001,"highalch":72000,"icon":"Abyssal whip.png","name":"Abyssal whip"}
  ```

## 3. `/5m`, `/1h`, `/24h`

Top-level keys on both versions: `data`, `timestamp`. Entry fields on both:
`avgHighPrice`, `highPriceVolume`, `avgLowPrice`, `lowPriceVolume`. Prices may be `null`.
Volumes are always integers.

| endpoint | version | URL | status | bytes | items | `timestamp` | entries with fractional avgHigh / avgLow / either |
|---|---|---|---|---|---|---|---|
| 5m | v1 | `https://prices.runescape.wiki/api/v1/osrs/5m` | 200 | 159,285 | 1,797 | 1793979300 (2026-11-06 15:35 UTC) | 0 / 0 / 0 |
| 5m | v2 | `https://prices.runescape.wiki/api/v2/osrs/5m` | 200 | 160,711 | 1,797 | 1793979300 | 273 / 233 / **414** |
| 1h | v1 | `https://prices.runescape.wiki/api/v1/osrs/1h` | 200 | 280,886 | 3,133 | 1793970000 (2026-11-06 13:00 UTC) | 0 / 0 / 0 |
| 1h | v2 | `https://prices.runescape.wiki/api/v2/osrs/1h` | 200 | 287,820 | 3,133 | 1793970000 | 1,300 / 1,158 / **1,623** |
| 24h | v1 | `https://prices.runescape.wiki/api/v1/osrs/24h` | 200 | 387,766 | 4,227 | 1793750400 (2026-11-04 00:00 UTC) | 0 / 0 / 0 |
| 24h | v2 | `https://prices.runescape.wiki/api/v2/osrs/24h` | 200 | 406,841 | 4,227 | 1793750400 | 3,494 / 3,117 / **3,706** |

JSON types seen for `avgHighPrice`/`avgLowPrice`: v1 = `int | null`, v2 = `int | float | null`.
In v2 a whole-number average is written without a decimal point (no `.0` literals at all).
The longest fraction is 2 decimal places.

Comparing v1 and v2 item by item for the same bucket: both have the same item ids (0 only-in-v1,
0 only-in-v2). Volumes are equal. Every price that differs (414 / 1,623 / 3,706 entries) differs by less than 1,
which is consistent with v1 = v2 rounded to the nearest whole number.

Sample entries:

| endpoint | item | v1 | v2 |
|---|---|---|---|
| 5m | 4151 | `{"avgHighPrice":670317,"highPriceVolume":2,"avgLowPrice":655353,"lowPriceVolume":5}` | identical |
| 5m | 561 | `{"avgHighPrice":159,"highPriceVolume":44147,"avgLowPrice":155,"lowPriceVolume":57355}` | `{"avgHighPrice":158.97,"highPriceVolume":44147,"avgLowPrice":154.72,"lowPriceVolume":57355}` |
| 1h | 4151 | `{"avgHighPrice":680194,"highPriceVolume":75,"avgLowPrice":666226,"lowPriceVolume":71}` | `{"avgHighPrice":680193.6,"highPriceVolume":75,"avgLowPrice":666226.08,"lowPriceVolume":71}` |
| 1h | 561 | `{"avgHighPrice":159,"highPriceVolume":923623,"avgLowPrice":157,"lowPriceVolume":371161}` | `{"avgHighPrice":159.25,"highPriceVolume":923623,"avgLowPrice":156.5,"lowPriceVolume":371161}` |
| 24h | 4151 | `{"avgHighPrice":683907,"highPriceVolume":2629,"avgLowPrice":668754,"lowPriceVolume":1976}` | `{"avgHighPrice":683907.39,"highPriceVolume":2629,"avgLowPrice":668753.82,"lowPriceVolume":1976}` |
| 24h | 561 | `{"avgHighPrice":154,"highPriceVolume":29799993,"avgLowPrice":151,"lowPriceVolume":16521793}` | `{"avgHighPrice":153.9,"highPriceVolume":29799993,"avgLowPrice":150.96,"lowPriceVolume":16521793}` |

**`/24h` on v2:** not in the v2 docs, but served (200), with the same shape as v1 apart from fractional prices.

`/24h?timestamp=0` on v2: `https://prices.runescape.wiki/api/v2/osrs/24h?timestamp=0` → **200**,
`{"data":{},"timestamp":0}`, `cache-control: no-cache`. An empty bucket is a successful
empty result, not an error.

## 4. `/volumes`

- `https://prices.runescape.wiki/api/v1/osrs/volumes` → 200, 48,230 B, 4,576 items
- `https://prices.runescape.wiki/api/v2/osrs/volumes` → 200, 48,230 B, 4,576 items
- **Byte-identical** (same ETag `W/"bc66-…"`). Only `Cache-Control` differs.
- Top-level keys: `timestamp`, `data` (in that order; `timestamp` = 1793970813 = 2026-11-06 13:13:33 UTC,
  not aligned to a bucket boundary). `data` maps item id → integer volume.
- 4151: `199`. 561: `2245894`.
- Not in the v2 docs, but served on v2.

## 5. `/timeseries`

Point fields on both versions: `timestamp`, `avgHighPrice`, `avgLowPrice`, `highPriceVolume`,
`lowPriceVolume`. Key order differs: v1 is `timestamp, avgHighPrice, highPriceVolume, avgLowPrice,
lowPriceVolume`, and v2 is `timestamp, avgHighPrice, avgLowPrice, highPriceVolume, lowPriceVolume`.
This does not matter to a JSON parser. Prices may be `null` on both versions.
Volumes were never fractional.

Top-level keys:
- v1: `data`, `itemId`
- v2: `data`, `itemId`, `startTimestamp`, `endTimestamp`, `timestep` (seconds)

### Item 4151

| version | URL | status | top-level extras | points | first ts | last ts | spacing min/median/max (s) | fractional points* |
|---|---|---|---|---|---|---|---|---|
| v1 | `…/api/v1/osrs/timeseries?timestep=5m&id=4151` | 200 | — | 365 | 1793870100 (11-05 09:15) | 1793979300 (11-06 15:35) | 300/300/300 | 0 |
| v1 | `…/api/v1/osrs/timeseries?timestep=1h&id=4151` | 200 | — | 365 | 1792659600 (10-22 09:00) | 1793970000 (11-06 13:00) | 3600/3600/3600 | 0 |
| v1 | `…/api/v1/osrs/timeseries?timestep=6h&id=4151` | 200 | — | 365 | 1786082400 (08-07 06:00) | 1793944800 (11-06 06:00) | 21600/21600/21600 | 0 |
| v1 | `…/api/v1/osrs/timeseries?timestep=24h&id=4151` | 200 | — | 365 | 1762387200 (2025-11-06) | 1793836800 (2026-11-05) | 86400/86400/86400 | 0 |
| v2 | `…/api/v2/osrs/timeseries?lookback=6h&id=4151` | 200 | start 1793958000 (11-06 09:40), end 1793979600 (15:40), timestep 300 | 72 | 1793958000 | 1793979300 | 300/300/300 | high 46, low 34 |
| v2 | `…/api/v2/osrs/timeseries?lookback=24h&id=4151` | 200 | start 1793893200 (11-05 15:40), end 1793979600, timestep 300 | 288 | 1793893200 | 1793979300 | 300/300/300 | high 183, low 142 |
| v2 | `…/api/v2/osrs/timeseries?lookback=7d&id=4151` | 200 | start 1793372400 (10-30 15:00), end 1793977200 (11-06 15:00), timestep 3600 | 167 | 1793372400 | 1793970000 (11-06 13:00) | 3600/3600/3600 | high 166, low 164 |
| v2 | `…/api/v2/osrs/timeseries?lookback=30d&id=4151` | 200 | start 1791374400 (10-07 12:00), end 1793966400 (11-06 12:00), timestep 21600 | 120 | 1791374400 | 1793944800 (11-06 06:00) | 21600/21600/21600 | high 120, low 120 |
| v2 | `…/api/v2/osrs/timeseries?lookback=6m&id=4151` | 200 | start 1778371200 (2026-05-10), end 1793923200 (11-06 00:00), timestep 86400 | 180 | 1778371200 | 1793836800 (11-05) | 86400/86400/86400 | high 37, low 37 |
| v2 | `…/api/v2/osrs/timeseries?lookback=1y&id=4151` | 200 | start 1762387200 (2025-11-06), end 1793923200, timestep 86400 | 365 | 1762387200 | 1793836800 | 86400/86400/86400 | high 37, low 37 |
| v2 | `…/api/v2/osrs/timeseries?timestep=5m&id=4151` | **400** | `{"error":"invalid query parameter. read the docs: https://osrs.wiki/RS:PRICES"}` | — | — | — | — | — |

\* Number of points whose price field has a fractional part (no point had a fractional volume).
Dates are UTC and in 2026 unless shown.

Observations:
- Mapping between the versions: `lookback=6h` and `24h` → 5-minute steps, `7d` → 1-hour,
  `30d` → 6-hour, `6m` and `1y` → 24-hour. v2 has **no way to get 365 points at 5m, 1h or 6h**.
  The longest v2 window at each step is 288 × 5m, 167 × 1h and 120 × 6h, against v1's 365 of each.
- `timestep=5m` on v2 is **rejected with 400**, not ignored and not honoured.
- `endTimestamp` is exclusive, and the newest bucket is the last *completed* one
  (e.g. 7d ends at 15:00 but the last point is 13:00, the same as v1 `/1h`'s `timestamp`).
- Matching v1 and v2 points by timestamp: 4151 5m vs 24h overlap 288, 1h vs 7d overlap 167,
  6h vs 30d overlap 120, 24h vs 1y overlap 365. **No value differs by more than 0.5.**
  v2 has no timestamps that v1 lacks.
- In the v2 `6m`/`1y` series, fractional values start at 1790726400 (2026-09-30 00:00 UTC).
  The 328 earlier daily points are whole numbers. The first `1y` point,
  `{"timestamp":1762387200,"avgHighPrice":1366106,"avgLowPrice":1340167,"highPriceVolume":2532,"lowPriceVolume":2876}`,
  is identical to v1's.
- Nulls are still present in v2: the v2 `24h` series for 4151 has 2 null `avgHighPrice` and
  3 null `avgLowPrice`. The v1 `5m` series has 5 and 8.
- Sample last point, 4151, v1 `timestep=1h`:
  `{"timestamp":1793970000,"avgHighPrice":680194,"highPriceVolume":75,"avgLowPrice":666226,"lowPriceVolume":71}`.
  The same point in v2 `lookback=7d`:
  `{"timestamp":1793970000,"avgHighPrice":680193.6,"avgLowPrice":666226.08,"highPriceVolume":75,"lowPriceVolume":71}`.

### Item 561

| version | URL | status | top-level extras | points | first ts | last ts | spacing min/median/max (s) | fractional points |
|---|---|---|---|---|---|---|---|---|
| v1 | `…/api/v1/osrs/timeseries?timestep=5m&id=561` | 200 | — | 365 | 1793870100 | 1793979300 | 300/300/300 | 0 |
| v2 | `…/api/v2/osrs/timeseries?lookback=24h&id=561` | 200 | start 1793893200, end 1793979600, timestep 300 | 288 | 1793893200 | 1793979300 | 300/300/300 | high 213, low 262 |

Overlap is 288 timestamps, with no difference greater than 0.5. Last point in v1:
`{"timestamp":1793979300,"avgHighPrice":159,"highPriceVolume":44147,"avgLowPrice":155,"lowPriceVolume":57355}`.
The same point in v2: `{"timestamp":1793979300,"avgHighPrice":158.97,"avgLowPrice":154.72,"highPriceVolume":44147,"lowPriceVolume":57355}`.

### Error shapes

| URL | status | body |
|---|---|---|
| `https://prices.runescape.wiki/api/v1/osrs/timeseries?timestep=5m&id=99999999` | 200 | `{"data":[],"itemId":99999999}` |
| `https://prices.runescape.wiki/api/v2/osrs/timeseries?lookback=24h&id=99999999` | 200 | `{"data":[],"itemId":99999999,"startTimestamp":1793893200,"endTimestamp":1793979600,"timestep":300}` |
| `https://prices.runescape.wiki/api/v2/osrs/timeseries?timestep=5m&id=4151` | 400 | `{"error":"invalid query parameter. read the docs: https://osrs.wiki/RS:PRICES"}` |
| `https://prices.runescape.wiki/api/v2/osrs/24h?timestamp=0` | 200 | `{"data":{},"timestamp":0}` |

An unknown item id is not an error on either version: it is 200 with an empty array.
A bad parameter on v2 is 400 with a body of the form `{"error": "<message>"}`.

## Summary

| endpoint | v1 status | v2 status | same shape | differences |
|---|---|---|---|---|
| `/latest?id=4151` | 200 | 200 | yes | none (values differ only because of fetch time) |
| `/latest` | 200 | 200 | yes | none: byte-identical, same ETag |
| `/mapping` | 200 | 200 | yes | body byte-identical; `Cache-Control` max-age 3600 → 60 (+ `s-maxage=300, stale-while-revalidate=5`) |
| `/5m` | 200 | 200 | yes (keys) | v2 avg prices can be decimals (≤2 dp; 414/1,797 entries); v1 = rounded |
| `/1h` | 200 | 200 | yes (keys) | as above (1,623/3,133 entries fractional) |
| `/24h` | 200 | 200 (undocumented in v2) | yes (keys) | as above (3,706/4,227 entries fractional) |
| `/volumes` | 200 | 200 (undocumented in v2) | yes | body byte-identical; `Cache-Control` max-age 3600 → 60 (+ `s-maxage=300, stale-while-revalidate=5`) |
| `/timeseries` | 200 (`timestep=`) | 200 (`lookback=`); 400 for `timestep=` | **no** | query param `timestep`→`lookback` (server picks step); v2 adds `startTimestamp`/`endTimestamp`/`timestep`; point count depends on window (72–365, not always 365); fractional prices since 2026-09-30; point key order changed |
| unknown id (timeseries) | 200 `data:[]` | 200 `data:[]` + window | no (extra keys) | v2 echoes window/timestep |
| `/24h?timestamp=0` | not probed | 200 `{"data":{},"timestamp":0}` | — | empty object; `cache-control: no-cache` |

No response on either version carried `Deprecation`, `Sunset`, `Warning`, `Link` or rate-limit headers.
