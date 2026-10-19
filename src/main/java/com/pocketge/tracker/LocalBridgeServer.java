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
import java.util.concurrent.atomic.AtomicInteger;
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
 * directions, not just plugin -> website.
 *
 * Every request must carry a PocketGE Origin and this loopback address as
 * its Host, or it is refused with 403 before it reaches a handler — see
 * {@link #refusal}. That is what keeps OTHER WEBSITES out: a browser will
 * not let a page fake its Origin, and the Host check stops a page that
 * re-points its own hostname at 127.0.0.1. It is not a lock against
 * programs already running on the computer, which can send any header they
 * like; those already run as the player and could read RuneLite's own
 * files, so the bridge does not pretend to stop them. The
 * Private-Network-Access preflight header is answered for the Chromium
 * versions that send one; current Chrome asks the player instead, with its
 * own Local Network Access prompt, before the https page may reach
 * localhost at all.
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
	 * {@code seq} keeps rising across plugin restarts (it is seeded from the
	 * clock), so a poller can tell a NEW request from one it already acted
	 * on by remembering the last seq it handled — polling is at-least-once
	 * and the same payload will be seen repeatedly. A tab left open through
	 * a restart remembers the old run's seq, so per-run numbering would put
	 * every new request below it.
	 */
	public static class NavRequest
	{
		public final long seq;
		public final String query;
		public final int itemId;
		public final long at;
		/** The plugin is waiting for the tab that takes this to say so (GET
		 *  /tab/ack), and opens a new tab itself if none does in time. A page
		 *  that sees it must act straight away or not at all — acting after
		 *  the plugin gave up would put the item up twice. */
		public final boolean ack;

		public NavRequest(long seq, String query, int itemId, long at)
		{
			this(seq, query, itemId, at, false);
		}

		public NavRequest(long seq, String query, int itemId, long at, boolean ack)
		{
			this.seq = seq;
			this.query = query;
			this.itemId = itemId;
			this.at = at;
			this.ack = ack;
		}
	}

	/* Injected from the client via the plugin — the hub's verification
	   (correctly) rejects fresh Gson instances. */
	private final Gson gson;
	private HttpServer server;
	/** The port actually bound, which is what a request's Host header has to
	 *  name. Read off the server rather than the setting so port 0 (any free
	 *  port, used by the tests) still has something to check against. */
	private volatile int boundPort;
	/** When a PocketGE page last polled us, epoch millis, 0 for never.
	   Only requests that pass {@link #refusal} reach a handler at all, so a
	   stray curl can't make the plugin believe a tab is open. Written on the
	   bridge's HTTP threads, read from the client thread — hence volatile. */
	private volatile long lastPollAt;

	/** How many requests the bridge will serve at once. Eight covers the
	 *  ordinary traffic (one /flips poll, the odd write) plus a long-poll
	 *  from each of a few tabs, with room to spare on a loopback server that
	 *  never sees real concurrency. */
	private static final int BRIDGE_THREADS = 8;
	/** How long a /nav request parks before answering "nothing yet". Short
	 *  enough to stay inside every proxy and browser idle timeout, long
	 *  enough that the reconnect cost is negligible. It is also how long a
	 *  closed tab's park goes on looking like a listener when its goodbye
	 *  never arrives — which on https://pocketge.com is usual, see
	 *  {@link #handleTabClosed} — so it was cut from 25 seconds. */
	private static final long NAV_WAIT_MS = 10_000;
	private ThreadPoolExecutor navExecutor;
	/** The most recent "show this item" request, or null. Guarded by
	 *  {@link #navLock}, which is also what a parked /nav waits on. */
	private NavRequest nav;
	private final Object navLock = new Object();
	/** Set on stop() so parked requests return instead of waiting out their
	 *  full wait while the plugin is trying to shut down. */
	private volatile boolean stopping;
	/** How many /nav requests are parked right now. */
	private final AtomicInteger parkedNavs = new AtomicInteger();
	/**
	 * Never park more than this many at once — deliberately below
	 * BRIDGE_THREADS.
	 *
	 * The pool rejects with CallerRunsPolicy, and the caller here is the
	 * HttpServer's own dispatcher thread. So a saturated pool would hand a
	 * 25-second park straight to the thread that accepts connections, stalling
	 * every endpoint — precisely the failure the pool was introduced to
	 * prevent, just reached from the other side. Capping the parkers below the
	 * pool size means the pool can never saturate on long-polls, so that
	 * hand-off cannot happen.
	 *
	 * Past the cap a /nav is answered immediately instead of waiting, and the
	 * page asks again a moment later.
	 */
	private static final int MAX_PARKED_NAVS = 4;

	/**
	 * Tabs with a /nav parked right now, by the id each page sends, and tabs
	 * that have said they are closing. Both guarded by {@link #navLock}.
	 *
	 * A closed tab's parked request is invisible from here: the browser drops
	 * the connection, but the handler is asleep on navLock and nothing tells
	 * it. It went on counting as a listener for the rest of its 25 seconds,
	 * so the first chart click after closing the tab was handed to nobody —
	 * and the answer written to that dead connection then counted as a fresh
	 * poll, which covered the next few clicks too. Reported as "it took 4
	 * times doing that for it to finally open a new tab". The page now says
	 * goodbye on its way out (GET /tab/closed), and that wakes its park.
	 */
	private final java.util.Set<String> parkedTabs = new java.util.HashSet<>();
	/** Tab id -> when it said goodbye. Kept for {@link #CLOSED_TAB_MS} after,
	 *  because the browser can send the closed tab's /nav once more on its
	 *  way out, and that request must not park and bring the tab back. */
	private final Map<String, Long> closedTabs = new java.util.LinkedHashMap<>();
	private static final int MAX_CLOSED_TABS = 32;
	private static final long CLOSED_TAB_MS = 10_000;

	/** When a page that can act on a handoff — one running the /nav loop —
	 *  last asked /nav or confirmed a click. {@link #lastPollAt} is any
	 *  PocketGE page at all, and the Flip History page polls /flips without
	 *  ever taking a chart click: counted as a tab, every click with only
	 *  that page open was "sent to your open tab" and went nowhere. */
	private volatile long lastNavPollAt;
	/** When a page last asked /nav saying it confirms what it takes (ack=1). */
	private volatile long confirmingPollAt;
	/** When a page last asked /nav WITHOUT ack=1 — an app.js from before the
	 *  ack, still open in some tab. */
	private volatile long legacyNavPollAt;
	/** Parked /nav requests right now that do, and do not, confirm. A parked
	 *  confirming tab is better evidence than the time it last asked, and it
	 *  cannot be wiped by a timed-out handoff for some other tab. */
	private final AtomicInteger confirmingParks = new AtomicInteger();
	private final AtomicInteger legacyParks = new AtomicInteger();
	/** Told the seq of each request a tab confirms, on the HTTP thread. */
	private volatile java.util.function.LongConsumer ackListener;

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

	public void start(int port, Supplier<Map<String, Object>> payload, Supplier<Map<String, Object>> history,
		FavoriteWriter favoriteWriter, ListWriter listWriter) throws IOException
	{
		stop();
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
		boundPort = server.getAddress().getPort();
		server.createContext("/flips", ex -> handleGet(ex, payload));
		/* Separate from /flips on purpose. /flips is polled every few seconds
		 * forever and carries the recent window; this is the entire ledger,
		 * which is years of trades and megabytes of JSON, and is fetched only
		 * when someone opens the history page. Serving it on the poll would
		 * mean re-serializing the whole history several times a minute to
		 * render eight rows. `flipCount` in /flips is how the page knows when
		 * to come back here. */
		server.createContext("/history", ex -> handleGet(ex, history));
		server.createContext("/status", ex -> handleGet(ex, () -> {
			Map<String, Object> m = new HashMap<>();
			m.put("ok", true);
			m.put("plugin", "pocketge-flip-tracker");
			return m;
		}));
		server.createContext("/favorites", ex -> handleFavoritePost(ex, favoriteWriter));
		server.createContext("/favoriteLists", ex -> handleFavoriteListsPost(ex, listWriter));
		server.createContext("/nav", this::handleNavLongPoll);
		/* Not under /nav: contexts match by prefix, so an older plugin would
		   route "/nav/…" to the long poll and park a closing tab's goodbye
		   for 25 seconds — the very phantom listener this exists to remove.
		   Under /tab an older plugin answers 404 at once. */
		server.createContext("/tab/ack", this::handleTabAck);
		server.createContext("/tab/closed", this::handleTabClosed);
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
		if (refuse(ex) || corsPreflight(ex, "GET, OPTIONS"))
		{
			return;
		}
		lastPollAt = System.currentTimeMillis();
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
		if (refuse(ex) || corsPreflight(ex, "GET, OPTIONS"))
		{
			return;
		}
		final long now = System.currentTimeMillis();
		final String query = ex.getRequestURI().getQuery();
		long since = 0;
		try
		{
			final String s = param(query, "since");
			since = s != null ? Long.parseLong(s) : 0;
		}
		catch (RuntimeException ignore)
		{
			since = 0; // unparseable: treat as "tell me what's current"
		}
		/* Both optional, so a page from before them still long-polls. */
		final String tab = tabId(param(query, "tab"));
		final boolean confirms = "1".equals(param(query, "ack"));
		if (tab != null)
		{
			synchronized (navLock)
			{
				final Long goneAt = closedTabs.get(tab);
				if (goneAt != null && now - goneAt < CLOSED_TAB_MS)
				{
					/* The closed tab, once more. Chromium resends a GET whose
					   connection closed before any answer, and a parked request
					   woken by the goodbye was exactly that; parking it again
					   undid the goodbye and the next click waited out the ack
					   timer. Not counted as a poll, either. */
					endQuietly(ex);
					return;
				}
				// Long after: a page the browser kept (back/forward cache) and
				// brought back. Asking again means open again.
				closedTabs.remove(tab);
			}
		}
		// A parked long-poll is still a tab being open — in fact it is
		// better evidence than a 5s tick, since it is continuous.
		lastPollAt = now;
		lastNavPollAt = now;
		if (confirms)
		{
			confirmingPollAt = now;
		}
		else
		{
			legacyNavPollAt = now;
		}

		NavRequest found = null;
		boolean closed = false;
		final long deadline = now + NAV_WAIT_MS;
		final boolean mayPark = parkedNavs.incrementAndGet() <= MAX_PARKED_NAVS;
		final AtomicInteger kind = confirms ? confirmingParks : legacyParks;
		if (mayPark)
		{
			kind.incrementAndGet();
		}
		int left;
		try
		{
		synchronized (navLock)
		{
			if (tab != null && mayPark)
			{
				parkedTabs.add(tab);
			}
			while (!stopping)
			{
				if (tab != null && closedTabs.containsKey(tab))
				{
					closed = true;
					break;
				}
				if (nav != null && nav.seq > since)
				{
					found = nav;
					break;
				}
				if (!mayPark)
				{
					// Too many already waiting — see MAX_PARKED_NAVS. Answer
					// with what we have rather than holding another thread.
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
					/* Stop waiting and answer with whatever we have. The
					   interrupt flag is deliberately NOT re-asserted: nothing
					   below this runs long enough to need to observe it — the
					   handler writes one small JSON body and returns — and the
					   plugin hub does not permit a plugin to interrupt threads
					   at all, which includes handing the flag back. */
					break;
				}
			}
			if (tab != null && mayPark)
			{
				parkedTabs.remove(tab);
			}
		}
		}
		finally
		{
			if (mayPark)
			{
				kind.decrementAndGet();
			}
			left = parkedNavs.decrementAndGet();
		}
		if (closed)
		{
			/* If that was the last tab, stop believing one is open: the
			   evidence is this tab's own last request, and it would otherwise
			   keep "a tab asked recently" true for a few more clicks sent
			   nowhere. */
			if (left == 0)
			{
				forgetClients(Long.MAX_VALUE);
			}
			endQuietly(ex);
			return;
		}
		/* No lastPollAt here. It used to be refreshed as the park ended, "to
		   keep polling alive as evidence of a tab" — but a park ends the same
		   way whether the tab is there or was closed twenty seconds ago, so
		   this was the plugin vouching for a connection nobody was on. A live
		   tab asks again the moment it reads this, and THAT request is the
		   evidence. */
		final Map<String, Object> body = new HashMap<>();
		body.put("navRequest", found);
		respondJson(ex, body);
	}

	/**
	 * An empty answer, and the connection closed.
	 *
	 * An answer, not just a close: HttpServer keeps an exchange it was never
	 * given a response for in its connection set for good, and Chromium
	 * resends a GET whose reused connection closes before any reply — the
	 * resend is what brought a closed tab straight back.
	 */
	private static void endQuietly(HttpExchange ex)
	{
		try
		{
			ex.getResponseHeaders().set("Connection", "close");
			ex.sendResponseHeaders(204, -1);
		}
		catch (IOException ignore)
		{
			// the other end has gone; nothing to tell it
		}
		ex.close();
	}

	/**
	 * GET /tab/ack?seq=N — "I took request N and I'm showing it".
	 *
	 * What lets the plugin stop guessing. Every signal it has of an open tab
	 * is a proxy — a recent poll, a parked request — and each one can outlive
	 * the tab it stands for: closed, frozen by the browser to save power, put
	 * to sleep. Without this, a handoff to any of those was a chart click that
	 * did nothing. With it, the plugin waits a moment for the word and opens a
	 * new tab when none comes.
	 *
	 * A GET, so the browser sends it without a preflight round trip — it is on
	 * the clock. It changes nothing a foreign page could want: the same
	 * Origin and Host rules apply, and all it can do is stop a tab opening.
	 */
	private void handleTabAck(HttpExchange ex) throws IOException
	{
		if (refuse(ex) || corsPreflight(ex, "GET, OPTIONS"))
		{
			return;
		}
		// A page answering is a page that is there, and one that confirms.
		final long now = System.currentTimeMillis();
		lastPollAt = now;
		lastNavPollAt = now;
		confirmingPollAt = now;
		long seq = -1;
		try
		{
			final String s = param(ex.getRequestURI().getQuery(), "seq");
			seq = s != null ? Long.parseLong(s) : -1;
		}
		catch (RuntimeException ignore)
		{
			seq = -1;
		}
		final java.util.function.LongConsumer listener = ackListener;
		if (seq > 0 && listener != null)
		{
			listener.accept(seq);
		}
		ex.sendResponseHeaders(204, -1);
		ex.close();
	}

	/**
	 * GET /tab/closed?tab=ID — sent by a page as it goes away.
	 *
	 * See {@link #parkedTabs}: a closed tab's parked /nav cannot be seen from
	 * this side, and it went on counting as an open tab until its wait ran
	 * out. This wakes it so it stops counting.
	 *
	 * Best effort, and on the live site usually not delivered: the page sends
	 * it from pagehide as a keepalive fetch, and Chrome's Local Network Access
	 * check does not let one from https://pocketge.com reach 127.0.0.1 once
	 * the page is unloading. A browser that is killed sends nothing either.
	 * The ack wait is what actually guarantees a click is not lost; this only
	 * saves it the wait when it does arrive.
	 */
	private void handleTabClosed(HttpExchange ex) throws IOException
	{
		if (refuse(ex) || corsPreflight(ex, "GET, OPTIONS"))
		{
			return;
		}
		final String tab = tabId(param(ex.getRequestURI().getQuery(), "tab"));
		if (tab != null)
		{
			final boolean hadPark;
			synchronized (navLock)
			{
				hadPark = parkedTabs.contains(tab);
				// Kept even with nothing parked: its /nav may still be on the way.
				closedTabs.put(tab, System.currentTimeMillis());
				while (closedTabs.size() > MAX_CLOSED_TABS)
				{
					final java.util.Iterator<String> oldest = closedTabs.keySet().iterator();
					oldest.next();
					oldest.remove();
				}
				if (hadPark)
				{
					navLock.notifyAll();
				}
			}
			// Between two of its polls, with nothing parked: if nothing else
			// is parked either, this was the last tab. (With a park, the woken
			// handler makes this call once it has let go.)
			if (!hadPark && parkedNavs.get() == 0)
			{
				forgetClients(Long.MAX_VALUE);
			}
		}
		ex.sendResponseHeaders(204, -1);
		ex.close();
	}

	/** One query parameter's raw value, or null. */
	static String param(String query, String name)
	{
		if (query == null)
		{
			return null;
		}
		final String key = name + "=";
		for (String part : query.split("&"))
		{
			if (part.startsWith(key))
			{
				return part.substring(key.length());
			}
		}
		return null;
	}

	/** A tab id as the page mints it — digits and dashes — or null. Anything
	 *  else is not one of ours and is not kept. */
	static String tabId(String raw)
	{
		return raw != null && !raw.isEmpty() && raw.length() <= 64 && raw.matches("[0-9-]+") ? raw : null;
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
		if (refuse(ex) || corsPreflight(ex, "GET, POST, OPTIONS"))
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
		if (refuse(ex) || corsPreflight(ex, "GET, POST, OPTIONS"))
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

	/**
	 * Why a request may not be served, or null when it may.
	 *
	 * This is the bridge's whole access check, and it runs before every
	 * handler. It used to be advisory: a request from any other origin, or a
	 * plain curl with no Origin at all, was answered in full — CORS only
	 * stopped a foreign page READING the reply, so a POST from any site open
	 * in the same browser still landed and edited your lists. Three rules:
	 *
	 * <ul>
	 * <li><b>Origin</b> must be a PocketGE page. A browser always sends it on
	 * the site's cross-origin fetches, so its absence means the request did
	 * not come from the site.</li>
	 * <li><b>Host</b> must be this loopback address and port. A page that
	 * re-points its own hostname at 127.0.0.1 (DNS rebinding) sends its own
	 * hostname here, and is refused on that even before its Origin.</li>
	 * <li><b>POST bodies</b> must be declared as JSON. The site always does,
	 * and a JSON content type is what forces the browser to ask permission
	 * (the preflight) before sending a write at all.</li>
	 * </ul>
	 *
	 * Static and pure so every rule can be tested without a socket.
	 */
	static String refusal(String method, String origin, String host, String contentType, int port)
	{
		if (!isAllowedHost(host, port))
		{
			return "host";
		}
		if (!isAllowedOrigin(origin))
		{
			return "origin";
		}
		if ("POST".equalsIgnoreCase(method)
			&& (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")))
		{
			return "content-type";
		}
		return null;
	}

	/** Answer a refused request and report whether it was one. 403 for a
	 *  foreign host or origin, 415 for a write that is not JSON. No CORS
	 *  headers either way, so a foreign page cannot read the reason. */
	private boolean refuse(HttpExchange ex) throws IOException
	{
		final String why = refusal(ex.getRequestMethod(),
			ex.getRequestHeaders().getFirst("Origin"),
			ex.getRequestHeaders().getFirst("Host"),
			ex.getRequestHeaders().getFirst("Content-Type"),
			boundPort);
		if (why == null)
		{
			return false;
		}
		ex.sendResponseHeaders("content-type".equals(why) ? 415 : 403, -1);
		ex.close();
		return true;
	}

	private static boolean isAllowedHost(String host, int port)
	{
		if (host == null)
		{
			return false;
		}
		final String h = host.trim().toLowerCase(java.util.Locale.ROOT);
		return h.equals("127.0.0.1:" + port) || h.equals("localhost:" + port) || h.equals("[::1]:" + port);
	}

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

	/** CORS headers for an origin {@link #refusal} has already accepted, and
	 *  the whole answer to an OPTIONS preflight. Returns true if the exchange
	 *  was fully handled here and the caller should stop. */
	private boolean corsPreflight(HttpExchange ex, String allowedMethods) throws IOException
	{
		ex.getResponseHeaders().set("Access-Control-Allow-Origin", ex.getRequestHeaders().getFirst("Origin"));
		ex.getResponseHeaders().set("Vary", "Origin");
		if ("OPTIONS".equalsIgnoreCase(ex.getRequestMethod()))
		{
			ex.getResponseHeaders().set("Access-Control-Allow-Methods", allowedMethods);
			/* Named rather than "*": Content-Type is the only header the
			   site sends, and it is the one the JSON rule above depends on. */
			ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
			/* Chromium Private Network Access: an https page fetching a
			   private address must be explicitly allowed. */
			ex.getResponseHeaders().set("Access-Control-Allow-Private-Network", "true");
			ex.sendResponseHeaders(204, -1);
			ex.close();
			return true;
		}
		return false;
	}

	/** The port the server is listening on, or 0 when it is not running. */
	int port()
	{
		return server != null ? boundPort : 0;
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

	/**
	 * Whether a page is parked on /nav RIGHT NOW, waiting to be handed an
	 * item.
	 *
	 * Much stronger evidence of a usable tab than {@link #hasRecentClient}:
	 * that one says a page polled at some point in the last N seconds, which
	 * stays true for a tab closed moments ago and for one the browser has
	 * since frozen in the background.
	 *
	 * Stronger, not proof. A parked request looks exactly the same from here
	 * after its tab is closed, because the browser hanging up does not wake
	 * it — hence the page's goodbye (/tab/closed) and, for a tab that could
	 * not send one, the plugin waiting for an ack before trusting a handoff.
	 */
	public boolean hasParkedNavListener()
	{
		return parkedNavs.get() > 0;
	}

	/**
	 * Whether a page that can take a handoff — one running the /nav loop —
	 * is there: one is parked right now, or one asked within {@code withinMs}.
	 * Polls of /flips alone do not count; see {@link #lastNavPollAt}.
	 */
	public boolean hasNavListener(long withinMs)
	{
		if (parkedNavs.get() > 0)
		{
			return true;
		}
		final long seen = lastNavPollAt;
		return seen > 0 && System.currentTimeMillis() - seen <= withinMs;
	}

	/**
	 * Whether the plugin should wait for a tab to confirm a click.
	 *
	 * Yes when a confirming page is parked now, or asked within one
	 * long-poll cycle. No while any page that CANNOT confirm — an app.js from
	 * before the ack, still open in a tab — is listening: if that one took
	 * the click it would show the item and say nothing, the wait would run
	 * out, and the item would open a second time in a new tab.
	 */
	public boolean hasConfirmingListener()
	{
		final long now = System.currentTimeMillis();
		final long window = NAV_WAIT_MS + 5_000;
		final long legacy = legacyNavPollAt;
		if (legacyParks.get() > 0 || (legacy > 0 && now - legacy <= window))
		{
			return false;
		}
		if (confirmingParks.get() > 0)
		{
			return true;
		}
		final long at = confirmingPollAt;
		return at > 0 && now - at <= window;
	}

	/** Who to tell when a tab confirms a request. Called on the bridge's HTTP
	 *  thread. */
	public void setAckListener(java.util.function.LongConsumer listener)
	{
		this.ackListener = listener;
	}

	/**
	 * Stop believing a tab is open, until one asks again — going only on
	 * what was seen up to {@code before}.
	 *
	 * For when the evidence has just been shown wrong: the last tab said it
	 * was closing, or a handoff went unconfirmed. A tab that IS still there
	 * asks again within one poll and is believed again from then on, so the
	 * cost of being wrong here is one click opening a new tab — which is
	 * visible — instead of one going nowhere.
	 */
	public void forgetClients(long before)
	{
		/* Only evidence from before {@code before}. A timed-out handoff used to
		   wipe everything, including a tab that had asked again after the
		   click — and the next click, seeing no confirming tab, skipped the
		   wait and could be sent nowhere. */
		if (lastPollAt <= before)
		{
			lastPollAt = 0;
		}
		if (lastNavPollAt <= before)
		{
			lastNavPollAt = 0;
		}
		if (confirmingPollAt <= before)
		{
			confirmingPollAt = 0;
		}
		if (legacyNavPollAt <= before)
		{
			legacyNavPollAt = 0;
		}
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
			/* shutdown(), never shutdownNow(): the latter interrupts the pool's
			   threads, which a hub plugin may not do. It is also unnecessary
			   here — the notifyAll above, paired with the stopping flag the
			   wait loop tests, has already released every parked request, so
			   there is nothing left to interrupt. shutdown() does not block. */
			navExecutor.shutdown();
			navExecutor = null;
		}
		// Re-arm: start() can be called again when the setting is toggled back on.
		stopping = false;
		nav = null;
		synchronized (navLock)
		{
			parkedTabs.clear();
			closedTabs.clear();
		}
		/* All of it, lastPollAt included: with the server down, a click within
		   a few seconds of the last poll was still "sent to your open tab". */
		forgetClients(Long.MAX_VALUE);
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
