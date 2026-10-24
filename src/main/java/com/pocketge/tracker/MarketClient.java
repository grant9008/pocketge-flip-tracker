package com.pocketge.tracker;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fetches live prices from the public OSRS Wiki real-time prices API using
 * the client's injected OkHttpClient (the same endpoint pocketge.com uses).
 * This is the plugin's ONLY outbound network access, and only runs when the
 * advisor feature is enabled. A descriptive User-Agent is sent per the
 * Wiki's API etiquette.
 *
 * Every call goes through {@link #getJson}, which backs off for the whole
 * client the moment the API answers "too many requests", "forbidden" or a
 * server error — see {@link #backoffMs}. Every fetch throws on failure
 * rather than returning an empty result, so a caller can never mistake "the
 * request failed" for "there is no data".
 */
public class MarketClient
{
	private static final Logger log = LoggerFactory.getLogger(MarketClient.class);
	private static final String BASE = "https://prices.runescape.wiki/api/v1/osrs";
	private static final String UA = "PocketGE Trading Terminal RuneLite plugin - contact via github.com/grant9008/pocketge-flip-tracker";

	private final OkHttpClient http;
	private final Gson gson;

	/** Thrown without touching the network while a back-off is in force.
	 *  Callers looping over items stop at the first one. */
	public static class BackingOff extends IOException
	{
		BackingOff(long remainingMs)
		{
			super("backing off the price API for another " + Math.max(1, remainingMs / 1000) + "s");
		}
	}

	/** First wait after a refusal, doubled for each refusal in a row. */
	static final long BACKOFF_BASE_MS = 60_000;
	/** Longest wait the doubling reaches. */
	static final long BACKOFF_MAX_MS = 30 * 60_000;
	/** Longest Retry-After honoured as given; anything longer is capped. */
	static final long RETRY_AFTER_MAX_MS = 60 * 60_000;

	/** No request is sent before this, epoch millis. Shared by every caller. */
	private volatile long blockedUntil;
	/** Refusals in a row; reset by any successful response. */
	private int strikes;

	@Inject
	public MarketClient(OkHttpClient http, Gson gson)
	{
		this.http = http;
		this.gson = gson;
	}

	/** GET /latest -> itemId -> Quote (high/low + times). */
	public Map<Integer, Advisor.Quote> fetchLatest() throws IOException
	{
		Map<Integer, Advisor.Quote> out = new HashMap<>();
		JsonObject data = requireData(BASE + "/latest");
		for (Map.Entry<String, com.google.gson.JsonElement> e : data.entrySet())
		{
			try
			{
				JsonObject o = e.getValue().getAsJsonObject();
				Advisor.Quote q = new Advisor.Quote();
				q.high = o.has("high") && !o.get("high").isJsonNull() ? o.get("high").getAsLong() : 0;
				q.low = o.has("low") && !o.get("low").isJsonNull() ? o.get("low").getAsLong() : 0;
				q.highTime = o.has("highTime") && !o.get("highTime").isJsonNull() ? o.get("highTime").getAsLong() : 0;
				q.lowTime = o.has("lowTime") && !o.get("lowTime").isJsonNull() ? o.get("lowTime").getAsLong() : 0;
				out.put(Integer.parseInt(e.getKey()), q);
			}
			catch (Exception ignore)
			{
				// skip malformed entries
			}
		}
		return out;
	}

	/** GET /24h -> itemId -> AnalystRating.Average (today's typical price),
	 *  the reference the Analyst Rating grades the live quote against. Same
	 *  one-call-for-every-item shape as /latest. */
	public Map<Integer, AnalystRating.Average> fetch24hAverages() throws IOException
	{
		Map<Integer, AnalystRating.Average> out = new HashMap<>();
		JsonObject data = requireData(BASE + "/24h");
		for (Map.Entry<String, com.google.gson.JsonElement> e : data.entrySet())
		{
			try
			{
				JsonObject o = e.getValue().getAsJsonObject();
				AnalystRating.Average a = new AnalystRating.Average();
				a.avgHighPrice = o.has("avgHighPrice") && !o.get("avgHighPrice").isJsonNull() ? o.get("avgHighPrice").getAsLong() : 0;
				a.avgLowPrice = o.has("avgLowPrice") && !o.get("avgLowPrice").isJsonNull() ? o.get("avgLowPrice").getAsLong() : 0;
				a.highPriceVolume = o.has("highPriceVolume") && !o.get("highPriceVolume").isJsonNull() ? o.get("highPriceVolume").getAsLong() : 0;
				a.lowPriceVolume = o.has("lowPriceVolume") && !o.get("lowPriceVolume").isJsonNull() ? o.get("lowPriceVolume").getAsLong() : 0;
				out.put(Integer.parseInt(e.getKey()), a);
			}
			catch (Exception ignore)
			{
				// skip malformed entries
			}
		}
		return out;
	}

	/** GET /volumes -> itemId -> daily volume. */
	public Map<Integer, Long> fetchVolumes() throws IOException
	{
		Map<Integer, Long> out = new HashMap<>();
		JsonObject data = requireData(BASE + "/volumes");
		for (Map.Entry<String, com.google.gson.JsonElement> e : data.entrySet())
		{
			try
			{
				out.put(Integer.parseInt(e.getKey()), e.getValue().getAsLong());
			}
			catch (Exception ignore)
			{
				// skip
			}
		}
		return out;
	}

	/**
	 * The 1-day AND 5-day high/low for ONE item, same source as
	 * pocketge.com's ▲/▼ badges.
	 *
	 * One fetch, two nested windows. The wiki's 1h series reaches back about
	 * 15 days, so the last 24 hours of it and the last 5 days of it come out
	 * of the same response — the day tier costs no extra request.
	 *
	 * This is a per-item call, so callers must keep it to a bounded set (e.g.
	 * favourites), never the whole item universe.
	 */
	public PriceExtremes fetchRecentExtremes(int itemId) throws IOException
	{
		final JsonObject root = getJson(BASE + "/timeseries?timestep=1h&id=" + itemId);
		if (root == null)
		{
			/* THROW, do not return zeros.
			 *
			 * getJson answers null for any non-2xx and for a missing body — a
			 * rate-limit, a 5xx, a dropped connection. Returning an all-zero
			 * PriceExtremes for that was indistinguishable from "this item has
			 * genuinely never traded", and the caller cached it: see
			 * PocketGeTrackerPlugin.refreshDayExtremes, which only re-fetches
			 * when the entry is null or the whole pass has gone stale. So one
			 * failed request left that item with no range badge for a full
			 * thirty minutes while every item around it had one — which is
			 * exactly how a single watchlist row ends up silently missing the
			 * badge the website shows for it.
			 *
			 * An exception is the honest signal: the caller already catches it
			 * and leaves the entry absent, so the next cycle retries. */
			throw new IOException("no response for recent extremes of item " + itemId);
		}
		final JsonArray data = root.getAsJsonArray("data");
		if (data == null)
		{
			// Well-formed HTTP, malformed payload. Same reasoning as above:
			// not evidence about the item, so do not cache it as if it were.
			// (An EMPTY data array is different and falls through — an item
			// with no prints in the window really does have no extremes.)
			throw new IOException("malformed recent-extremes payload for item " + itemId);
		}
		return extremesFrom(data, System.currentTimeMillis() / 1000L);
	}

	/** One item's 1h series, as fetchRecentExtremes reads it, at {@code now}
	 *  (epoch seconds). */
	static PriceExtremes extremesFrom(JsonArray data, long now)
	{
		final PriceExtremes ex = new PriceExtremes();
		final long cut1d = now - 86400L;
		final long cut5d = now - 5 * 86400L;
		long hi1d = 0, hi5d = 0;
		long lo1d = Long.MAX_VALUE, lo5d = Long.MAX_VALUE;
		long firstTs = Long.MAX_VALUE;
		double firstMid = 0;
		for (com.google.gson.JsonElement el : data)
		{
			final JsonObject o = el.getAsJsonObject();
			final long ts = o.has("timestamp") && !o.get("timestamp").isJsonNull() ? o.get("timestamp").getAsLong() : 0;
			if (ts < cut5d)
			{
				continue;
			}
			final long h = o.has("avgHighPrice") && !o.get("avgHighPrice").isJsonNull()
				? o.get("avgHighPrice").getAsLong() : 0;
			final long l = o.has("avgLowPrice") && !o.get("avgLowPrice").isJsonNull()
				? o.get("avgLowPrice").getAsLong() : 0;
			if (h > hi5d)
			{
				hi5d = h;
			}
			if (l > 0 && l < lo5d)
			{
				lo5d = l;
			}
			if (ts >= cut1d)
			{
				if (h > hi1d)
				{
					hi1d = h;
				}
				if (l > 0 && l < lo1d)
				{
					lo1d = l;
				}
				/* The first priced hour of the day — app.js pctChange's
				   "first": the mid of the two sides, or the one there is. */
				if ((h > 0 || l > 0) && ts < firstTs)
				{
					firstTs = ts;
					firstMid = ((l > 0 ? l : h) + (h > 0 ? h : l)) / 2.0;
				}
			}
		}
		ex.mid1dAgo = firstMid;
		ex.hi1d = hi1d;
		ex.lo1d = lo1d < Long.MAX_VALUE ? lo1d : 0;
		ex.hi5d = hi5d;
		ex.lo5d = lo5d < Long.MAX_VALUE ? lo5d : 0;
		return ex;
	}

	/**
	 * GET /timeseries?timestep=6h&id=X — the 30-day trading range for ONE
	 * item, the same window pocketge.com's 30-DAY RANGE meter shows.
	 *
	 * A separate call from {@link #fetchRecentExtremes}, and it has to be:
	 * that one is timestep=1h, which the 1-day and 5-day tiers need at four
	 * buckets a day, and which only reaches back about fifteen days. Six-hour
	 * buckets reach roughly ninety, so thirty days of range is comfortably
	 * inside one response.
	 *
	 * Per-item like the others, so callers MUST keep this to a small bounded
	 * set. There is no bulk history endpoint on the wiki — /latest, /24h and
	 * /volumes cover every item in one request but carry no history beyond a
	 * single 24-hour average, which is a number and not a range.
	 *
	 * Throws rather than returning an empty result on a failed request, for
	 * the reason spelled out in fetchRecentExtremes: a cached "this item has
	 * no range" that is really "the request failed" is invisible and sticks
	 * for the whole TTL.
	 */
	public RangePosition fetchRange30(int itemId) throws IOException
	{
		final JsonObject root = getJson(BASE + "/timeseries?timestep=6h&id=" + itemId);
		if (root == null)
		{
			throw new IOException("no response for 30-day range of item " + itemId);
		}
		final JsonArray data = root.getAsJsonArray("data");
		if (data == null)
		{
			throw new IOException("malformed 30-day range payload for item " + itemId);
		}
		final int n = data.size();
		final long[] ts = new long[n];
		final long[] hi = new long[n];
		final long[] lo = new long[n];
		int i = 0;
		for (com.google.gson.JsonElement el : data)
		{
			final JsonObject o = el.getAsJsonObject();
			ts[i] = o.has("timestamp") && !o.get("timestamp").isJsonNull() ? o.get("timestamp").getAsLong() : 0;
			hi[i] = o.has("avgHighPrice") && !o.get("avgHighPrice").isJsonNull() ? o.get("avgHighPrice").getAsLong() : 0;
			lo[i] = o.has("avgLowPrice") && !o.get("avgLowPrice").isJsonNull() ? o.get("avgLowPrice").getAsLong() : 0;
			i++;
		}
		// The arithmetic lives in RangePosition so it can be tested without a
		// network; this method is only the parse.
		return RangePosition.fromBuckets(ts, hi, lo, System.currentTimeMillis() / 1000L - 30 * 86400L);
	}

	/** GET /timeseries?timestep=5m&id=X for ONE item — the trade engine's
	 *  input (see TradeEngine). Per-item like fetchRecentExtremes above, so
	 *  callers must keep this bounded (active GE offers only — at most 8
	 *  slots, never the whole item universe). 5-minute buckets cover the
	 *  last ~24h, which is exactly the engine's own window. The API returns
	 *  buckets oldest-first already — same assumption pocketge.com's own
	 *  loadTS() makes, no re-sorting here. */
	public TradeEngine.Series fetchTimeseries5m(int itemId) throws IOException
	{
		return fetchTimeseries(itemId, "5m");
	}

	/** The timesteps the Wiki serves, each up to 365 buckets: 5m (~30h),
	 *  1h (~15 days), 6h (~91 days), 24h (a year). */
	static final java.util.Set<String> TIMESTEPS = java.util.Set.of("5m", "1h", "6h", "24h");

	/** GET /timeseries for one item at any of the Wiki's timesteps — the
	 *  offer-screen chart's other ranges, fetched one at a time on demand.
	 *  Same parse as the 5m series, same shared back-off. */
	public TradeEngine.Series fetchTimeseries(int itemId, String timestep) throws IOException
	{
		if (!TIMESTEPS.contains(timestep))
		{
			throw new IllegalArgumentException("not a Wiki timestep: " + timestep);
		}
		final JsonObject root = getJson(BASE + "/timeseries?timestep=" + timestep + "&id=" + itemId);
		if (root == null)
		{
			/* Throws, like the other per-item fetches. Returning null here let
			   the offer screen's on-demand fetch re-run itself in a loop. */
			throw new IOException("no response for " + timestep + " series of item " + itemId);
		}
		final JsonArray data = root.getAsJsonArray("data");
		if (data == null)
		{
			throw new IOException("malformed " + timestep + " series payload for item " + itemId);
		}
		final int n = data.size();
		final TradeEngine.Series series = new TradeEngine.Series();
		series.labels = new long[n];
		series.low = new double[n];
		series.high = new double[n];
		series.lowVol = new double[n];
		series.highVol = new double[n];
		int i = 0;
		for (com.google.gson.JsonElement el : data)
		{
			final JsonObject o = el.getAsJsonObject();
			series.labels[i] = o.has("timestamp") && !o.get("timestamp").isJsonNull() ? o.get("timestamp").getAsLong() : 0;
			series.low[i] = o.has("avgLowPrice") && !o.get("avgLowPrice").isJsonNull() ? o.get("avgLowPrice").getAsDouble() : 0;
			series.high[i] = o.has("avgHighPrice") && !o.get("avgHighPrice").isJsonNull() ? o.get("avgHighPrice").getAsDouble() : 0;
			series.lowVol[i] = o.has("lowPriceVolume") && !o.get("lowPriceVolume").isJsonNull() ? o.get("lowPriceVolume").getAsDouble() : 0;
			series.highVol[i] = o.has("highPriceVolume") && !o.get("highPriceVolume").isJsonNull() ? o.get("highPriceVolume").getAsDouble() : 0;
			i++;
		}
		return series;
	}

	/** How long until requests are allowed again, 0 when they are now. */
	public long backoffRemainingMs()
	{
		return Math.max(0, blockedUntil - System.currentTimeMillis());
	}

	/** A bulk endpoint's {"data": {...}} object, or an exception. */
	private JsonObject requireData(String url) throws IOException
	{
		final JsonObject root = getJson(url);
		final JsonObject data = root != null && root.has("data") && root.get("data").isJsonObject()
			? root.getAsJsonObject("data") : null;
		if (data == null)
		{
			throw new IOException("no data from " + url);
		}
		return data;
	}

	private JsonObject getJson(String url) throws IOException
	{
		final long wait = blockedUntil - System.currentTimeMillis();
		if (wait > 0)
		{
			throw new BackingOff(wait);
		}
		Request req = new Request.Builder()
			.url(HttpUrl.get(url))
			.header("User-Agent", UA)
			.build();
		try (Response res = http.newCall(req).execute())
		{
			if (!res.isSuccessful())
			{
				final int code = res.code();
				if (code == 429 || code == 403 || code >= 500)
				{
					final long ms;
					synchronized (this)
					{
						ms = backoffMs(strikes++, res.header("Retry-After"),
							java.util.concurrent.ThreadLocalRandom.current().nextDouble());
						blockedUntil = System.currentTimeMillis() + ms;
					}
					log.warn("PocketGE advisor: {} returned {}; pausing all price requests for {}s",
						url, code, ms / 1000);
				}
				else
				{
					log.warn("PocketGE advisor: {} returned {}", url, code);
				}
				return null;
			}
			synchronized (this)
			{
				strikes = 0;
			}
			ResponseBody body = res.body();
			if (body == null)
			{
				return null;
			}
			return gson.fromJson(body.string(), JsonObject.class);
		}
	}

	/**
	 * How long every request waits after a refusal.
	 *
	 * The wiki asks clients to back off when it is under strain, and a 429,
	 * a 403 (how it blocks a client it does not want) or a 5xx is it saying
	 * so. Before this, a refusal changed nothing: the next cycle asked again
	 * on schedule, and every per-item loop carried on through its whole list,
	 * each request refused in turn.
	 *
	 * A Retry-After in seconds is honoured (capped at an hour). Otherwise a
	 * minute, doubling with each refusal in a row up to half an hour. Either
	 * way up to a fifth more is added at random, so clients refused together
	 * do not all come back together.
	 *
	 * @param strikes refusals in a row before this one
	 * @param retryAfter the response's Retry-After header, or null
	 * @param rand a uniform random number in [0, 1)
	 */
	static long backoffMs(int strikes, String retryAfter, double rand)
	{
		long base = -1;
		if (retryAfter != null)
		{
			try
			{
				base = Math.min(RETRY_AFTER_MAX_MS, Long.parseLong(retryAfter.trim()) * 1000L);
			}
			catch (NumberFormatException ignore)
			{
				// An HTTP-date, or garbage: fall back to the doubling.
			}
		}
		if (base <= 0)
		{
			base = BACKOFF_BASE_MS << Math.min(Math.max(strikes, 0), 5);
			base = Math.min(base, BACKOFF_MAX_MS);
		}
		return base + (long) (base * 0.2 * Math.max(0, Math.min(rand, 1)));
	}
}
