package com.pocketge.tracker;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Tiny opt-in HTTP listener bound to 127.0.0.1 ONLY. pocketge.com open in
 * the local browser polls GET /flips to show this session's trades, your
 * plugin-side Favorites, portfolio value, and current top recommendation —
 * a same-machine link between the plugin and the site with no accounts and
 * no server: data never leaves the machine. It also accepts POST /favorites
 * so favoriting an item ON THE SITE while linked adds it in-game too — the
 * link works both directions, not just plugin -> website. CORS is
 * restricted to the PocketGE origins, and the Private-Network-Access
 * preflight header is answered so Chromium allows the https page -> localhost
 * fetch.
 */
public class LocalBridgeServer
{
	private static final String[] ALLOWED_ORIGINS = {
		"https://pocketge.com",
		"https://www.pocketge.com",
		"http://localhost:8901" // local dev of the site
	};

	/** Called with (itemId, name, remove) when the website POSTs a favorite
	 *  change — remove=true means unfavorite rather than add. */
	public interface FavoriteWriter
	{
		void write(int itemId, String name, boolean remove);
	}

	/* Injected from the client via the plugin — the hub's verification
	   (correctly) rejects fresh Gson instances. */
	private final Gson gson;
	private HttpServer server;

	public LocalBridgeServer(Gson gson)
	{
		this.gson = gson;
	}

	public void start(int port, Supplier<Map<String, Object>> payload, FavoriteWriter favoriteWriter) throws IOException
	{
		stop();
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
		server.createContext("/flips", ex -> handleGet(ex, payload));
		server.createContext("/status", ex -> handleGet(ex, () -> {
			Map<String, Object> m = new HashMap<>();
			m.put("ok", true);
			m.put("plugin", "pocketge-flip-tracker");
			return m;
		}));
		server.createContext("/favorites", ex -> handleFavoritePost(ex, favoriteWriter));
		server.setExecutor(null);
		server.start();
	}

	private void handleGet(HttpExchange ex, Supplier<Map<String, Object>> payload) throws IOException
	{
		if (corsPreflight(ex, "GET, OPTIONS"))
		{
			return;
		}
		byte[] body = gson.toJson(payload.get()).getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		ex.sendResponseHeaders(200, body.length);
		try (OutputStream os = ex.getResponseBody())
		{
			os.write(body);
		}
	}

	/** POST { "id": 123, "name": "Diamond", "remove": false } — adds (or
	 *  removes) a favorite the same way the in-game star button does. A
	 *  malformed body is answered with 400 rather than silently ignored, so
	 *  a website-side bug is visible instead of just "didn't sync". */
	private void handleFavoritePost(HttpExchange ex, FavoriteWriter favoriteWriter) throws IOException
	{
		if (corsPreflight(ex, "GET, POST, OPTIONS"))
		{
			return;
		}
		if (!"POST".equalsIgnoreCase(ex.getRequestMethod()))
		{
			ex.sendResponseHeaders(405, -1);
			ex.close();
			return;
		}
		try
		{
			final ByteArrayOutputStream buf = new ByteArrayOutputStream();
			try (InputStream is = ex.getRequestBody())
			{
				is.transferTo(buf);
			}
			final JsonObject o = gson.fromJson(buf.toString(StandardCharsets.UTF_8), JsonObject.class);
			final int id = o.get("id").getAsInt();
			final String name = o.has("name") && !o.get("name").isJsonNull() ? o.get("name").getAsString() : ("Item " + id);
			final boolean remove = o.has("remove") && o.get("remove").getAsBoolean();
			favoriteWriter.write(id, name, remove);
			ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
			final byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
			ex.sendResponseHeaders(200, body.length);
			try (OutputStream os = ex.getResponseBody())
			{
				os.write(body);
			}
		}
		catch (Exception e)
		{
			ex.sendResponseHeaders(400, -1);
			ex.close();
		}
	}

	/** Shared Origin-check + preflight handling. Returns true if the
	 *  exchange was fully handled here (an OPTIONS preflight) and the
	 *  caller should stop. */
	private boolean corsPreflight(HttpExchange ex, String allowedMethods) throws IOException
	{
		String origin = ex.getRequestHeaders().getFirst("Origin");
		String allow = null;
		if (origin != null)
		{
			for (String o : ALLOWED_ORIGINS)
			{
				if (o.equals(origin))
				{
					allow = o;
					break;
				}
			}
		}
		if (allow != null)
		{
			ex.getResponseHeaders().set("Access-Control-Allow-Origin", allow);
			ex.getResponseHeaders().set("Vary", "Origin");
		}
		if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod()))
		{
			ex.getResponseHeaders().set("Access-Control-Allow-Methods", allowedMethods);
			ex.getResponseHeaders().set("Access-Control-Allow-Headers", "*");
			/* Chromium Private Network Access: an https page fetching a
			   private address must be explicitly allowed. */
			ex.getResponseHeaders().set("Access-Control-Allow-Private-Network", "true");
			ex.sendResponseHeaders(204, -1);
			ex.close();
			return true;
		}
		return false;
	}

	public void stop()
	{
		if (server != null)
		{
			server.stop(0);
			server = null;
		}
	}

	/** Build the /flips payload from tracker + panel state. Static so the
	 *  plugin can also reuse it for future export features.
	 *  {@code favorites} / {@code topRecommendation} are whatever the panel
	 *  most recently rendered — null/empty degrades cleanly, matching how
	 *  the website already treats "advisor off" or "no favorites yet". */
	public static Map<String, Object> payload(long sessionProfit, long lifetimeProfit, List<Flip> flips, List<TradeFill> fills,
		long portfolioValue, List<FavoritesPanel.Row> favorites, Advisor.Suggestion topRecommendation)
	{
		Map<String, Object> m = new HashMap<>();
		m.put("sessionProfit", sessionProfit);
		m.put("lifetimeProfit", lifetimeProfit);
		m.put("flips", flips);
		m.put("fills", fills);
		m.put("portfolioValue", portfolioValue);
		m.put("favorites", favorites);
		m.put("topRecommendation", topRecommendation);
		m.put("generatedAt", System.currentTimeMillis());
		return m;
	}
}
