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
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Tiny opt-in HTTP listener bound to 127.0.0.1 ONLY. pocketge.com open in
 * the local browser polls GET /flips to show this session's trades, your
 * plugin-side Favorite lists, portfolio value, and current top
 * recommendation — a same-machine link between the plugin and the site with
 * no accounts and no server: data never leaves the machine. It also accepts
 * POST /favorites (add/remove an item) and POST /favoriteLists
 * (create/rename/delete a list) so the site can drive the same TradingView-
 * style watchlists the in-game panel manages — the link works both
 * directions, not just plugin -> website. CORS is restricted to the
 * PocketGE origins, and the Private-Network-Access preflight header is
 * answered so Chromium allows the https page -> localhost fetch.
 */
public class LocalBridgeServer
{
	private static final String[] ALLOWED_ORIGINS = {
		"https://pocketge.com",
		"https://www.pocketge.com",
		"http://localhost:8901" // local dev of the site
	};

	/** Called with (listId, itemId, name, remove) when the website POSTs a
	 *  favorite change — remove=true means unfavorite rather than add.
	 *  listId is null when the website didn't specify one, meaning
	 *  "whichever list is currently active in-game". */
	public interface FavoriteWriter
	{
		void write(String listId, int itemId, String name, boolean remove);
	}

	/** Called when the website POSTs a list-management action. create()
	 *  returns the new list's id (never null/blank name is rejected before
	 *  this is called). rename()/delete() are no-ops if listId doesn't match
	 *  any current list — same as the in-game chip menu's own guards. */
	public interface ListWriter
	{
		String create(String name);
		void rename(String listId, String name);
		void delete(String listId);
		/** Two-way order sync: the site sends the order it is showing and the
		 *  plugin's list is rewritten to match. Membership is never changed
		 *  by this — see FavoriteLists.reorderTo for why that has to be true
		 *  when both ends can edit at once. */
		void reorder(String listId, List<Integer> itemIds);
	}

	/**
	 * "Show this item" pushed from the plugin to a PocketGE tab that is
	 * already open, instead of launching a browser.
	 *
	 * Opening a new tab per chart click is the wrong default when the site
	 * is already up: depending on the desktop's URL handler it can land on
	 * whatever tab happens to be focused, which in practice means it takes
	 * over something else you were watching. If a tab is already polling us
	 * it can just navigate itself.
	 *
	 * {@code seq} is monotonic for the plugin's lifetime, so a poller can
	 * tell a NEW request from one it already acted on by remembering the
	 * last seq it handled — polling is at-least-once and the same payload
	 * will be seen repeatedly.
	 */
	public static class NavRequest
	{
		public final long seq;
		public final String query;
		public final int itemId;
		public final long at;

		public NavRequest(long seq, String query, int itemId, long at)
		{
			this.seq = seq;
			this.query = query;
			this.itemId = itemId;
			this.at = at;
		}
	}

	/* Injected from the client via the plugin — the hub's verification
	   (correctly) rejects fresh Gson instances. */
	private final Gson gson;
	private HttpServer server;
	/** When a PocketGE page last polled us, epoch millis, 0 for never.
	   Only same-origin-allowed GETs count, so a stray curl can't make the
	   plugin believe a tab is open. Written on the bridge's HTTP threads,
	   read from the client thread — hence volatile. */
	private volatile long lastPollAt;

	/** How many requests the bridge will serve at once. Eight covers the
	 *  ordinary traffic (one /flips poll, the odd write) plus a long-poll
	 *  from each of a few tabs, with room to spare on a loopback server that
	 *  never sees real concurrency. */
	private static final int BRIDGE_THREADS = 8;
	/** How long a /nav request parks before answering "nothing yet". Short
	 *  enough to stay inside every proxy and browser idle timeout, long
	 *  enough that the reconnect cost is negligible. */
	private static final long NAV_WAIT_MS = 25_000;
	private ThreadPoolExecutor navExecutor;
	/** The most recent "show this item" request, or null. Guarded by
	 *  {@link #navLock}, which is also what a parked /nav waits on. */
	private NavRequest nav;
	private final Object navLock = new Object();
	/** Set on stop() so parked requests return instead of waiting out their
	 *  full 25 seconds while the plugin is trying to shut down. */
	private volatile boolean stopping;

	/**
	 * Hand the bridge a chart click to deliver.
	 *
	 * Wakes every parked /nav immediately, which is the entire point: the
	 * site used to learn about this on its next 5-second poll, and a hidden
	 * background tab's timers are throttled well past that. A request that is
	 * already open is not a timer, so it is answered the moment this is
	 * called.
	 */
	public void publishNav(NavRequest request)
	{
		synchronized (navLock)
		{
			nav = request;
			navLock.notifyAll();
		}
	}

	public LocalBridgeServer(Gson gson)
	{
		this.gson = gson;
	}

	public void start(int port, Supplier<Map<String, Object>> payload, FavoriteWriter favoriteWriter, ListWriter listWriter) throws IOException
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
		server.createContext("/favoriteLists", ex -> handleFavoriteListsPost(ex, listWriter));
		server.createContext("/nav", this::handleNavLongPoll);
		/* A REAL pool, not setExecutor(null).
		 *
		 * The default runs every handler on the single thread start() created,
		 * which was fine while every response was immediate — and is fatal now
		 * that /nav parks for up to 25 seconds waiting for a chart click. On
		 * the default executor one parked request would stall /flips, the
		 * favourites writes and the list reorders behind it.
		 *
		 * Capped, because these threads are only ever unblocked by a person
		 * clicking a chart button or a timeout: an unbounded pool would let a
		 * page with a reconnect bug quietly accumulate them. Past the cap a
		 * long-poll is answered immediately instead of queueing, so the site
		 * falls back to its ordinary 5-second poll rather than hanging.
		 *
		 * Daemon threads: this lives inside the RuneLite client and must never
		 * be the reason the JVM stays up. */
		navExecutor = new ThreadPoolExecutor(0, BRIDGE_THREADS, 30L, TimeUnit.SECONDS,
			new SynchronousQueue<>(), r ->
			{
				final Thread t = new Thread(r, "pocketge-bridge");
				t.setDaemon(true);
				return t;
			}, new ThreadPoolExecutor.CallerRunsPolicy());
		server.setExecutor(navExecutor);
		server.start();
	}

	private void handleGet(HttpExchange ex, Supplier<Map<String, Object>> payload) throws IOException
	{
		if (corsPreflight(ex, "GET, OPTIONS"))
		{
			return;
		}
		if (isAllowedOrigin(ex.getRequestHeaders().getFirst("Origin")))
		{
			lastPollAt = System.currentTimeMillis();
		}
		byte[] body = gson.toJson(payload.get()).getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		ex.sendResponseHeaders(200, body.length);
		try (OutputStream os = ex.getResponseBody())
		{
			os.write(body);
		}
	}

	/**
	 * GET /nav?since=N — "tell me the moment a chart is clicked in game".
	 *
	 * Parks until there is a NavRequest newer than {@code since}, or for
	 * {@link #NAV_WAIT_MS}, whichever comes first, then answers
	 * {"navRequest": …} or {"navRequest": null}. The page reconnects
	 * immediately either way.
	 *
	 * Why not just poll faster: the browser tab is behind the game, and a
	 * hidden tab's setInterval is throttled — to once a second at best, and
	 * to once a MINUTE once Chrome's intensive throttling kicks in. Shortening
	 * the interval cannot fix something the browser is deliberately slowing
	 * down. An already-open request is not a timer, so its response is
	 * delivered on the network task source and arrives promptly regardless.
	 *
	 * {@code since} of 0 (or absent) answers with whatever is current, which
	 * is how a freshly loaded page learns the seq it should dedupe from.
	 */
	private void handleNavLongPoll(HttpExchange ex) throws IOException
	{
		if (corsPreflight(ex, "GET, OPTIONS"))
		{
			return;
		}
		final boolean allowed = isAllowedOrigin(ex.getRequestHeaders().getFirst("Origin"));
		if (allowed)
		{
			// A parked long-poll is still a tab being open — in fact it is
			// better evidence than a 5s tick, since it is continuous.
			lastPollAt = System.currentTimeMillis();
		}
		long since = 0;
		try
		{
			final String query = ex.getRequestURI().getQuery();
			if (query != null)
			{
				for (String part : query.split("&"))
				{
					if (part.startsWith("since="))
					{
						since = Long.parseLong(part.substring(6));
					}
				}
			}
		}
		catch (RuntimeException ignore)
		{
			since = 0; // unparseable: treat as "tell me what's current"
		}

		NavRequest found = null;
		final long deadline = System.currentTimeMillis() + NAV_WAIT_MS;
		synchronized (navLock)
		{
			while (!stopping)
			{
				if (nav != null && nav.seq > since)
				{
					found = nav;
					break;
				}
				final long remaining = deadline - System.currentTimeMillis();
				if (remaining <= 0)
				{
					break;
				}
				try
				{
					navLock.wait(remaining);
				}
				catch (InterruptedException e)
				{
					Thread.currentThread().interrupt();
					break;
				}
			}
		}
		// Keep polling alive as evidence of a tab even across a long park.
		if (allowed)
		{
			lastPollAt = System.currentTimeMillis();
		}
		final Map<String, Object> body = new HashMap<>();
		body.put("navRequest", found);
		respondJson(ex, body);
	}

	private void respondJson(HttpExchange ex, Map<String, Object> body) throws IOException
	{
		final byte[] bytes = gson.toJson(body).getBytes(StandardCharsets.UTF_8);
		ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		ex.sendResponseHeaders(200, bytes.length);
		try (OutputStream os = ex.getResponseBody())
		{
			os.write(bytes);
		}
	}

	/** POST { "id": 123, "name": "Diamond", "remove": false, "listId": "…" }
	 *  — adds (or removes) a favorite the same way the in-game star button
	 *  does. listId is optional: omit it to target whichever list is
	 *  currently active in-game. A malformed body is answered with 400
	 *  rather than silently ignored, so a website-side bug is visible
	 *  instead of just "didn't sync". */
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
			final JsonObject o = readJsonBody(ex);
			final int id = o.get("id").getAsInt();
			final String name = o.has("name") && !o.get("name").isJsonNull() ? o.get("name").getAsString() : ("Item " + id);
			final boolean remove = o.has("remove") && o.get("remove").getAsBoolean();
			final String listId = o.has("listId") && !o.get("listId").isJsonNull() ? o.get("listId").getAsString() : null;
			favoriteWriter.write(listId, id, name, remove);
			respondOk(ex);
		}
		catch (Exception e)
		{
			ex.sendResponseHeaders(400, -1);
			ex.close();
		}
	}

	/** POST { "action": "create"|"rename"|"delete"|"reorder", "listId": "…",
	 *  "name": "…", "itemIds": [1601, 561, …] }
	 *  — drives the same list chip-menu actions (new list / rename / delete)
	 *  the in-game panel offers, from the website. "create" only needs
	 *  name; "rename" needs both; "delete" only needs listId. Responds with
	 *  { "ok": true, "listId": "…" } — for "create" that's the new list's id,
	 *  so the site can switch straight to it without a round trip. */
	private void handleFavoriteListsPost(HttpExchange ex, ListWriter listWriter) throws IOException
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
			final JsonObject o = readJsonBody(ex);
			final String action = o.get("action").getAsString();
			String resultListId = null;
			switch (action)
			{
				case "create":
				{
					final String name = o.get("name").getAsString();
					if (name == null || name.trim().isEmpty())
					{
						throw new IllegalArgumentException("blank list name");
					}
					resultListId = listWriter.create(name.trim());
					break;
				}
				case "rename":
				{
					final String name = o.get("name").getAsString();
					if (name == null || name.trim().isEmpty())
					{
						throw new IllegalArgumentException("blank list name");
					}
					listWriter.rename(o.get("listId").getAsString(), name.trim());
					break;
				}
				case "delete":
					listWriter.delete(o.get("listId").getAsString());
					break;
				case "reorder":
				{
					final com.google.gson.JsonArray arr = o.getAsJsonArray("itemIds");
					final List<Integer> ids = new java.util.ArrayList<>();
					for (int i = 0; i < arr.size(); i++)
					{
						ids.add(arr.get(i).getAsInt());
					}
					if (ids.isEmpty())
					{
						throw new IllegalArgumentException("empty itemIds");
					}
					listWriter.reorder(o.has("listId") && !o.get("listId").isJsonNull()
						? o.get("listId").getAsString() : null, ids);
					break;
				}
				default:
					throw new IllegalArgumentException("unknown action: " + action);
			}
			ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
			final JsonObject resp = new JsonObject();
			resp.addProperty("ok", true);
			if (resultListId != null)
			{
				resp.addProperty("listId", resultListId);
			}
			final byte[] body = gson.toJson(resp).getBytes(StandardCharsets.UTF_8);
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

	private JsonObject readJsonBody(HttpExchange ex) throws IOException
	{
		final ByteArrayOutputStream buf = new ByteArrayOutputStream();
		try (InputStream is = ex.getRequestBody())
		{
			is.transferTo(buf);
		}
		return gson.fromJson(buf.toString(StandardCharsets.UTF_8), JsonObject.class);
	}

	private void respondOk(HttpExchange ex) throws IOException
	{
		ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		final byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
		ex.sendResponseHeaders(200, body.length);
		try (OutputStream os = ex.getResponseBody())
		{
			os.write(body);
		}
	}

	/** Shared Origin-check + preflight handling. Returns true if the
	 *  exchange was fully handled here (an OPTIONS preflight) and the
	 *  caller should stop. */
	private static boolean isAllowedOrigin(String origin)
	{
		if (origin == null)
		{
			return false;
		}
		for (String o : ALLOWED_ORIGINS)
		{
			if (o.equals(origin))
			{
				return true;
			}
		}
		return false;
	}

	private boolean corsPreflight(HttpExchange ex, String allowedMethods) throws IOException
	{
		String origin = ex.getRequestHeaders().getFirst("Origin");
		String allow = isAllowedOrigin(origin) ? origin : null;
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

	/** Epoch millis of the last poll from a PocketGE page, 0 for never. */
	public long lastPollAt()
	{
		return lastPollAt;
	}

	/** Best available answer to "is a PocketGE tab open right now". It is a
	 *  proxy, not a fact: the page polls on a timer, so this stays true for
	 *  up to one poll interval after the tab is closed. Callers must degrade
	 *  gracefully when a handoff turns out to have no one listening. */
	public boolean hasRecentClient(long withinMs)
	{
		final long seen = lastPollAt;
		return seen > 0 && System.currentTimeMillis() - seen <= withinMs;
	}

	public void stop()
	{
		/* Wake the parked long-polls FIRST. server.stop(0) waits for handlers
		   to finish, and a /nav sitting on a 25-second timer would hold the
		   plugin's shutdown for as long as it had left to run. */
		stopping = true;
		synchronized (navLock)
		{
			navLock.notifyAll();
		}
		if (server != null)
		{
			server.stop(0);
			server = null;
		}
		if (navExecutor != null)
		{
			navExecutor.shutdownNow();
			navExecutor = null;
		}
		// Re-arm: start() can be called again when the setting is toggled back on.
		stopping = false;
		nav = null;
	}

	/**
	 * One stack sitting in the bank, priced. The bridge used to publish a
	 * single portfolioValue total, so the website could show what you were
	 * worth but never what you were HOLDING — which is the half you act on.
	 * Cash is excluded (it arrives as its own `cash` field); an unpriced item
	 * carries value 0 rather than being dropped, so the composition stays
	 * honest about what it couldn't value.
	 */
	public static class BankStack
	{
		public final int id;
		public final String name;
		public final int quantity;
		public final long value; // quantity x current insta-sell, 0 if unpriced

		public BankStack(int id, String name, int quantity, long value)
		{
			this.id = id;
			this.name = name;
			this.quantity = quantity;
			this.value = value;
		}
	}

	/** Build the /flips payload from tracker + panel state. Static so the
	 *  plugin can also reuse it for future export features.
	 *  {@code favoriteLists} / {@code topRecommendation} are whatever was
	 *  most recently loaded/rendered — null/empty degrades cleanly, matching
	 *  how the website already treats "advisor off" or "no favorites yet".
	 *  favoriteLists carries every list's raw id/name/color/items (not
	 *  resolved prices — the website already fetches live prices itself and
	 *  only needs to know which items belong to which list). */
	public static Map<String, Object> payload(long sessionProfit, long lifetimeProfit, List<Flip> flips, List<TradeFill> fills,
		long portfolioValue, boolean bankSeen, long bankSeenAt, long cash, List<BankStack> bankStacks,
		List<FavoriteLists.FavoriteList> favoriteLists, String activeFavoriteListId, Advisor.Suggestion topRecommendation)
	{
		Map<String, Object> m = new HashMap<>();
		m.put("sessionProfit", sessionProfit);
		m.put("lifetimeProfit", lifetimeProfit);
		m.put("flips", flips);
		m.put("fills", fills);
		m.put("portfolioValue", portfolioValue);
		/* False until the player has opened their bank at least once this
		   session — RuneLite can't read bank contents any other way, so
		   portfolioValue silently excludes it until then. The website uses
		   this to say so instead of just showing a too-low total.
		   bankSeenAt (epoch millis, 0 for never) says HOW STALE that snapshot
		   is: "seen" alone can't distinguish a bank read ten seconds ago from
		   one read three hours and forty trades ago, and the site was
		   presenting both as equally current. */
		m.put("bankSeen", bankSeen);
		m.put("bankSeenAt", bankSeenAt);
		m.put("cash", cash);
		m.put("bankStacks", bankStacks);
		m.put("favoriteLists", favoriteLists);
		m.put("activeFavoriteListId", activeFavoriteListId);
		m.put("topRecommendation", topRecommendation);
		m.put("generatedAt", System.currentTimeMillis());
		return m;
	}
}
