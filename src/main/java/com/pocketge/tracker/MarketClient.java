package com.pocketge.tracker;

import com.google.gson.Gson;
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
	private static final String UA = "PocketGE Flip Tracker RuneLite plugin - contact via github.com/lazyblob/pocketge-flip-tracker";

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
