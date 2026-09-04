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
 */
public class MarketClient
{
	private static final Logger log = LoggerFactory.getLogger(MarketClient.class);
	private static final String BASE = "https://prices.runescape.wiki/api/v1/osrs";
	private static final String UA = "PocketGE Flip Tracker RuneLite plugin - contact via github.com/grant9008/pocketge-flip-tracker";

	private final OkHttpClient http;
	private final Gson gson;

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
		JsonObject root = getJson(BASE + "/latest");
		if (root == null)
		{
			return out;
		}
		JsonObject data = root.getAsJsonObject("data");
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
		JsonObject root = getJson(BASE + "/24h");
		if (root == null)
		{
			return out;
		}
		JsonObject data = root.getAsJsonObject("data");
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
		JsonObject root = getJson(BASE + "/volumes");
		if (root == null)
		{
			return out;
		}
		JsonObject data = root.getAsJsonObject("data");
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

	/** The 1-day AND 5-day high/low for ONE item, same source as
	 *  pocketge.com's ▲/▼ badges: 1h candles go back ~15 days, so a single
	 *  fetch covers both windows — the day tier costs no extra request. This
	 *  is a per-item call, so callers must keep it to a bounded set (e.g.
	 *  favorites), never the whole item universe. */
	public PriceExtremes fetchRecentExtremes(int itemId) throws IOException
	{
		final PriceExtremes ex = new PriceExtremes();
		final long now = System.currentTimeMillis() / 1000L;
		scanExtremes(ex, "1h", itemId, now - 86400L, now - 5 * 86400L);
		return ex;
	}

	/**
	 * Fills in the 30-day window, which is the one thing the 1h series
	 * cannot reach — 365 hourly buckets is about 15 days, half of what a
	 * monthly extreme needs. 6h buckets reach ~91 days, which is also the
	 * series pocketge.com's own 1-month chart view reads first.
	 *
	 * This is a SECOND request per item on top of {@link
	 * #fetchRecentExtremes}, so it is worth spending only where the badge is
	 * actually shown. Switching the existing call to 6h and taking 30 days
	 * for free was the tempting alternative and is a bad trade: bucket
	 * averages smooth as they widen, so the 5-day high would sag and the low
	 * would rise, silently weakening a tier that already works.
	 */
	public void fill30dExtremes(PriceExtremes ex, int itemId) throws IOException
	{
		final long cut = System.currentTimeMillis() / 1000L - 30 * 86400L;
		scanExtremes(ex, "6h", itemId, Long.MAX_VALUE, cut);
	}

	/**
	 * One /timeseries fetch, swept into at most two nested windows.
	 *
	 * @param shortCut  unix seconds; buckets at or after this set hi1d/lo1d.
	 *                  Long.MAX_VALUE to skip the short window entirely.
	 * @param longCut   unix seconds; buckets at or after this set the long
	 *                  window — hi5d/lo5d for the 1h series, hi30d/lo30d for
	 *                  the 6h one, keyed off which timestep was asked for.
	 */
	private void scanExtremes(PriceExtremes ex, String timestep, int itemId, long shortCut, long longCut)
		throws IOException
	{
		final JsonObject root = getJson(BASE + "/timeseries?timestep=" + timestep + "&id=" + itemId);
		if (root == null)
		{
			return;
		}
		final JsonArray data = root.getAsJsonArray("data");
		if (data == null)
		{
			return;
		}
		long hiShort = 0, hiLong = 0;
		long loShort = Long.MAX_VALUE, loLong = Long.MAX_VALUE;
		for (com.google.gson.JsonElement el : data)
		{
			final JsonObject o = el.getAsJsonObject();
			final long ts = o.has("timestamp") && !o.get("timestamp").isJsonNull() ? o.get("timestamp").getAsLong() : 0;
			if (ts < longCut)
			{
				continue;
			}
			final long h = o.has("avgHighPrice") && !o.get("avgHighPrice").isJsonNull()
				? o.get("avgHighPrice").getAsLong() : 0;
			final long l = o.has("avgLowPrice") && !o.get("avgLowPrice").isJsonNull()
				? o.get("avgLowPrice").getAsLong() : 0;
			if (h > hiLong)
			{
				hiLong = h;
			}
			if (l > 0 && l < loLong)
			{
				loLong = l;
			}
			if (ts >= shortCut)
			{
				if (h > hiShort)
				{
					hiShort = h;
				}
				if (l > 0 && l < loShort)
				{
					loShort = l;
				}
			}
		}
		if ("6h".equals(timestep))
		{
			ex.hi30d = hiLong;
			ex.lo30d = loLong < Long.MAX_VALUE ? loLong : 0;
			return;
		}
		ex.hi1d = hiShort;
		ex.lo1d = loShort < Long.MAX_VALUE ? loShort : 0;
		ex.hi5d = hiLong;
		ex.lo5d = loLong < Long.MAX_VALUE ? loLong : 0;
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
		final JsonObject root = getJson(BASE + "/timeseries?timestep=5m&id=" + itemId);
		if (root == null)
		{
			return null;
		}
		final JsonArray data = root.getAsJsonArray("data");
		if (data == null)
		{
			return null;
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

	private JsonObject getJson(String url) throws IOException
	{
		Request req = new Request.Builder()
			.url(HttpUrl.get(url))
			.header("User-Agent", UA)
			.build();
		try (Response res = http.newCall(req).execute())
		{
			if (!res.isSuccessful())
			{
				log.warn("PocketGE advisor: {} returned {}", url, res.code());
				return null;
			}
			ResponseBody body = res.body();
			if (body == null)
			{
				return null;
			}
			return gson.fromJson(body.string(), JsonObject.class);
		}
	}
}
