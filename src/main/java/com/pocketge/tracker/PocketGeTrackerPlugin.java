package com.pocketge.tracker;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.ItemComposition;
import net.runelite.api.MenuAction;
import net.runelite.api.ScriptID;
import net.runelite.api.WorldType;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@PluginDescriptor(
	name = "PocketGE Flip Tracker",
	description = "Tracks your GE flips after tax, and (optional) suggests buys/sells sized to your cash & bank with PocketGE charts",
	tags = {"flipping", "grand exchange", "merchant", "profit", "ge", "advisor"}
)
public class PocketGeTrackerPlugin extends Plugin
{
	private static final Logger log = LoggerFactory.getLogger(PocketGeTrackerPlugin.class);

	@Inject
	private Client client;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ItemManager itemManager;

	@Inject
	private PocketGeTrackerConfig config;

	@Inject
	private Gson gson;

	@Inject
	private MarketClient marketClient;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private GeOfferOverlay geOverlay;

	@Inject
	private BankHighlightOverlay bankOverlay;

	private final FlipTracker tracker = new FlipTracker();
	private LocalBridgeServer bridge;
	private MainPanel mainPanel;
	private NavigationButton navButton;

	private ScheduledFuture<?> advisorTask;
	/** Coins are item id 995 in every container. */
	private static final int COINS_ID = 995;
	/** Preferred daily-volume floor for a buy candidate — used to be a
	 *  user-facing Low/Med/High risk-level dial; users didn't know what to
	 *  do with it, so it's now just a sane fixed default (was Risk Level's
	 *  MED tier). Advisor.advise() falls back to ignoring it (see
	 *  MIN_PREFILTER_VOLUME below) rather than ever showing nothing. */
	private static final long DEFAULT_MIN_VOLUME = 250_000;
	/** The candidate gate below still needs SOME floor — without one,
	 *  every one of the ~4000 tradeable items would get an ItemComposition +
	 *  ItemStats lookup on every recompute just to immediately get thrown
	 *  away by Advisor.advise(). This only screens out effectively-dead
	 *  markets; Advisor.advise() does the real (and now always-non-empty)
	 *  ranking against DEFAULT_MIN_VOLUME first, falling back below it. */
	private static final long MIN_PREFILTER_VOLUME = 1_000;
	/** Session-only skips (item ids); cleared on logout via reset. */
	private final Set<Integer> skipped = new HashSet<>();
	/** Last bank snapshot (item id -> qty), refreshed whenever the bank opens. */
	private final Map<Integer, Integer> lastBank = new HashMap<>();
	private volatile Map<Integer, Advisor.Quote> lastQuotes = new HashMap<>();
	private volatile Map<Integer, Long> lastVolumes = new HashMap<>();
	private volatile Map<Integer, AnalystRating.Average> lastAverages = new HashMap<>();
	/** Recent price history for items with an active GE offer, feeding
	 *  TradeEngine so ADJUST_BUY/ADJUST_SELL suggestions reprice to the same
	 *  target pocketge.com would show, not just the raw live quote (see
	 *  refreshOfferSeries). Keyed by item id, at most one entry per GE slot. */
	private volatile Map<Integer, TradeEngine.Series> lastOfferSeries = new HashMap<>();
	/** Item ids with an active offer as of the last recomputeAdvice() —
	 *  offer state needs the client thread, so refreshOfferSeries() (a
	 *  background fetch) works off this rather than reading offers itself;
	 *  a newly-placed offer's reprice target uses the raw live quote for
	 *  one advisor cycle before this catches up. */
	private volatile Set<Integer> lastActiveOfferItemIds = new HashSet<>();
	/** 5-day high/low per favorited item, powering the Favorites panel's
	 *  flashing "at a 5-day high/low" glow — same signal as the website's
	 *  ▲/▼ 5D badge. Only ever holds entries for CURRENTLY favorited items
	 *  (see refreshDayExtremes) since it's a per-item network call. */
	private final Map<Integer, MarketClient.DayExtremes> dayExtremes = new java.util.concurrent.ConcurrentHashMap<>();
	/** Re-fetch every favorite's 5-day extremes at most this often — the
	 *  5-day window barely moves faster than this, so there's no value in
	 *  re-fetching on every advisor tick (which can be as often as 60s). New
	 *  favorites still get fetched immediately regardless of this timer. */
	private static final long DAY_EXTREMES_TTL_MS = 30 * 60 * 1000L;
	private volatile long dayExtremesRefreshedAt = 0;
	/** Latest values the local bridge serves to pocketge.com — refreshed
	 *  alongside the panel itself (refreshStatsAndFavorites / recomputeAdvice)
	 *  so a browser polling the bridge always sees what the panel shows. */
	private volatile long lastPortfolioValue = 0;
	private volatile Advisor.Suggestion lastTopRecommendation = null;
	/** Whatever item is currently sitting in an OPEN GE offer setup screen
	 *  (regardless of whether it's the advisor's own top pick) — set from
	 *  ScriptID.GE_OFFERS_SETUP_BUILD firing, cleared once the setup screen
	 *  is no longer visible. Lets the panel show a price for whatever the
	 *  player is actually doing right now, not just our own suggestion. */
	private volatile Integer geContextItemId = null;
	private volatile String geContextName = "";
	private volatile boolean geContextIsBuy = true;
	private volatile long geContextPrice = 0;
	/** Which stats window the panel's dropdown currently shows — not
	 *  persisted; every RuneLite launch starts back on Session, same as the
	 *  panel itself starting fresh each login. */
	private FlipStats.Range currentRange = FlipStats.Range.SESSION;

	@Provides
	PocketGeTrackerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(PocketGeTrackerConfig.class);
	}

	@Override
	protected void startUp()
	{
		bridge = new LocalBridgeServer(gson);
		loadState();
		mainPanel = new MainPanel(itemManager, new MainPanel.Actions()
		{
			@Override
			public void onRangeChanged(FlipStats.Range range)
			{
				currentRange = range;
				refreshStatsAndFavorites();
			}

			@Override
			public void onResetSession()
			{
				/* Zeroes the session counter + session start time only —
				   lifetime P/L and flip history survive (full wipe lives in
				   config). */
				tracker.resetSession();
				refreshPanel();
				saveState();
			}

			@Override
			public void skip(int itemId)
			{
				skipped.add(itemId);
				recomputeAdvice();
			}

			@Override
			public void block(String itemName)
			{
				config.setBlocklist(Blocklist.add(config.blocklist(), itemName));
				recomputeAdvice();
			}

			@Override
			public void unblock(String itemName)
			{
				config.setBlocklist(Blocklist.remove(config.blocklist(), itemName));
				recomputeAdvice();
			}

			@Override
			public void toggleFavorite(int itemId, String name)
			{
				PocketGeTrackerPlugin.this.toggleFavorite(itemId, name);
			}

			@Override
			public void removeFavorite(int itemId)
			{
				List<FavoriteLists.FavoriteList> lists = loadFavoriteLists();
				FavoriteLists.FavoriteList active = activeFavoriteList(lists);
				if (active != null)
				{
					FavoriteLists.removeItem(active, itemId);
					saveFavoriteLists(lists);
				}
				refreshStatsAndFavorites();
				recomputeAdvice();
			}

			@Override
			public void reorderFavorite(int itemId, int delta)
			{
				List<FavoriteLists.FavoriteList> lists = loadFavoriteLists();
				FavoriteLists.FavoriteList active = activeFavoriteList(lists);
				if (active != null)
				{
					FavoriteLists.moveItem(active, itemId, delta);
					saveFavoriteLists(lists);
				}
				refreshStatsAndFavorites();
			}

			@Override
			public void selectFavoriteList(String listId)
			{
				config.setActiveFavoriteList(listId);
				refreshStatsAndFavorites();
				recomputeAdvice(); // star state on cards should reflect the new active list
			}

			@Override
			public void createFavoriteList(String name)
			{
				createFavoriteListInternal(name);
			}

			@Override
			public void renameFavoriteList(String listId, String name)
			{
				renameFavoriteListInternal(listId, name);
			}

			@Override
			public void recolorFavoriteList(String listId, String color)
			{
				List<FavoriteLists.FavoriteList> lists = loadFavoriteLists();
				FavoriteLists.FavoriteList l = FavoriteLists.findList(lists, listId);
				if (l != null)
				{
					l.color = color;
					saveFavoriteLists(lists);
					refreshStatsAndFavorites();
				}
			}

			@Override
			public void deleteFavoriteList(String listId)
			{
				deleteFavoriteListInternal(listId);
			}

			@Override
			public void setAdjustInterval(PocketGeTrackerConfig.AdjustInterval v)
			{
				/* Fires ConfigChanged -> onConfigChanged() below re-syncs the
				   fetch schedule; the recompute here is just for instant
				   button-highlight feedback using whatever's already cached. */
				config.setAdjustInterval(v);
				recomputeAdvice();
			}

			@Override
			public void setAdvisorEnabled(boolean on)
			{
				/* Fires ConfigChanged -> onConfigChanged() -> syncAdvisor(),
				   which starts/stops the fetch schedule. */
				config.setAdvisor(on);
			}

			@Override
			public void setLocalBridge(boolean on)
			{
				// ConfigChanged -> syncBridge() actually starts/stops the server.
				config.setLocalBridge(on);
			}

			@Override
			public void setBridgePort(int port)
			{
				config.setBridgePort(port);
			}

			@Override
			public void setMaxFlips(int n)
			{
				config.setMaxFlips(n);
				refreshPanel(); // re-cap the history list against the new limit immediately
			}

			@Override
			public void fillGePrice(long price)
			{
				PocketGeTrackerPlugin.this.fillGePrice(price);
			}
		});
		mainPanel.setSelectedRangeQuietly(currentRange);

		final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "icon.png");
		navButton = NavigationButton.builder()
			.tooltip("PocketGE Flip Tracker")
			.icon(icon)
			.priority(6)
			.panel(mainPanel)
			.build();
		clientToolbar.addNavigation(navButton);
		overlayManager.add(geOverlay);
		overlayManager.add(bankOverlay);

		refreshPanel();
		syncBridge();
		syncAdvisor();
	}

	@Override
	protected void shutDown()
	{
		saveState();
		clientToolbar.removeNavigation(navButton);
		overlayManager.remove(geOverlay);
		overlayManager.remove(bankOverlay);
		if (advisorTask != null)
		{
			advisorTask.cancel(false);
			advisorTask = null;
		}
		if (bridge != null)
		{
			bridge.stop();
		}
		if (mainPanel != null)
		{
			mainPanel.stopFavoritesGlow();
		}
	}

	@Inject
	private ConfigManager configManager;

	private static final String STATE_KEY = "state";

	private void loadState()
	{
		try
		{
			final String json = configManager.getConfiguration(PocketGeTrackerConfig.GROUP, STATE_KEY);
			if (json != null && !json.isEmpty())
			{
				tracker.restore(gson.fromJson(json, FlipTracker.State.class));
			}
		}
		catch (Exception e)
		{
			log.warn("Could not restore flip history", e);
		}
	}

	private void saveState()
	{
		try
		{
			configManager.setConfiguration(PocketGeTrackerConfig.GROUP, STATE_KEY, gson.toJson(tracker.snapshot()));
		}
		catch (Exception e)
		{
			log.warn("Could not save flip history", e);
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (PocketGeTrackerConfig.GROUP.equals(event.getGroup()))
		{
			syncBridge();
			syncAdvisor();
			if ("blocklist".equals(event.getKey()))
			{
				recomputeAdvice(); // reflect manual edits to the never-recommend box
			}
			if ("maxFlips".equals(event.getKey()))
			{
				refreshPanel(); // re-cap the history list against the new limit immediately
			}
		}
	}

	/* ── Advisor ─────────────────────────────────────────────────────── */

	private void syncAdvisor()
	{
		if (advisorTask != null)
		{
			advisorTask.cancel(false);
			advisorTask = null;
		}
		if (mainPanel == null)
		{
			return;
		}
		if (!config.advisor())
		{
			SwingUtilities.invokeLater(() ->
			{
				mainPanel.setAdvisorStatus("Advisor off — enable it in settings");
				mainPanel.updateSuggestions(new ArrayList<>(), new HashMap<>(), favoriteIdSet(), buildSettings());
			});
			bankOverlay.setSuggestions(new HashMap<>());
			geOverlay.setSuggestion(null);
			lastTopRecommendation = null;
			refreshStatsAndFavorites(); // portfolio/favorites still work fully offline (cash + whatever's cached)
			return;
		}
		final int period = Math.max(60, config.adjustInterval().seconds());
		advisorTask = executor.scheduleWithFixedDelay(this::refreshPrices, 0, period, TimeUnit.SECONDS);
	}

	/** Background thread: fetch live prices, then recompute on the client thread. */
	private void refreshPrices()
	{
		if (!config.advisor())
		{
			return;
		}
		try
		{
			lastQuotes = marketClient.fetchLatest();
			lastVolumes = marketClient.fetchVolumes();
			lastAverages = marketClient.fetch24hAverages();
		}
		catch (Exception e)
		{
			log.warn("PocketGE advisor: price fetch failed", e);
			SwingUtilities.invokeLater(() -> mainPanel.setAdvisorStatus("Couldn't reach the price API — will retry"));
			return;
		}
		refreshDayExtremes();
		refreshOfferSeries();
		recomputeAdvice();
	}

	/** One /timeseries call per item with an active GE offer as of the last
	 *  recomputeAdvice() cycle — bounded to at most 8 (the GE slot count),
	 *  the same "small, bounded" shape as refreshDayExtremes above. Feeds
	 *  TradeEngine so ADJUST_BUY/ADJUST_SELL can reprice to pocketge.com's
	 *  own target instead of the raw live quote (see Advisor.advise). No
	 *  staleness TTL needed — the set is already tiny, so it's cheap to
	 *  refetch every advisor cycle rather than track a separate timer. */
	private void refreshOfferSeries()
	{
		final Set<Integer> ids = lastActiveOfferItemIds;
		final Map<Integer, TradeEngine.Series> out = new HashMap<>();
		for (Integer id : ids)
		{
			try
			{
				final TradeEngine.Series series = marketClient.fetchTimeseries5m(id);
				if (series != null)
				{
					out.put(id, series);
				}
			}
			catch (Exception e)
			{
				log.warn("PocketGE advisor: timeseries fetch failed for item {}", id, e);
			}
		}
		lastOfferSeries = out;
	}

	/** One /timeseries call per currently-favorited item, only for items not
	 *  already cached (or all of them once every DAY_EXTREMES_TTL_MS) — a
	 *  handful of extra requests at most, unlike the bulk endpoints above
	 *  which cover every tradeable item. A single item's fetch failing just
	 *  leaves that entry stale rather than aborting the refresh. */
	private void refreshDayExtremes()
	{
		final Set<Integer> favIds = favoriteIdSet();
		dayExtremes.keySet().retainAll(favIds); // drop unfavorited items
		final boolean stale = System.currentTimeMillis() - dayExtremesRefreshedAt > DAY_EXTREMES_TTL_MS;
		if (stale)
		{
			dayExtremesRefreshedAt = System.currentTimeMillis();
		}
		for (Integer id : favIds)
		{
			if (stale || !dayExtremes.containsKey(id))
			{
				try
				{
					dayExtremes.put(id, marketClient.fetchDayExtremes5d(id));
				}
				catch (Exception e)
				{
					log.warn("PocketGE advisor: 5-day extremes fetch failed for item {}", id, e);
				}
			}
		}
	}

	/** Assemble the player situation on the client thread, run the pure
	 *  advisor, and push results to the panel + overlay. */
	private void recomputeAdvice()
	{
		if (mainPanel == null || !config.advisor())
		{
			return;
		}
		clientThread.invokeLater(() ->
		{
			final long nowSec = System.currentTimeMillis() / 1000L;
			final Map<Integer, Advisor.Quote> quotes = lastQuotes;
			final Map<Integer, Long> volumes = lastVolumes;
			final Map<Integer, AnalystRating.Average> averages = lastAverages;

			final long cash = countInventory(COINS_ID);
			final Map<Integer, Integer> holdings = currentHoldings();
			final List<Advisor.OfferView> offers = currentOffers();
			// Offer state needs the client thread (we're on it right here), but
			// refreshOfferSeries() runs on the background executor — hand it
			// this cycle's active-offer item ids for its NEXT fetch.
			final Set<Integer> activeOfferIds = new HashSet<>();
			for (Advisor.OfferView o : offers)
			{
				if (o.active)
				{
					activeOfferIds.add(o.itemId);
				}
			}
			lastActiveOfferItemIds = activeOfferIds;

			// Pre-filter with cheap map data, then resolve name+limit only for survivors.
			final Map<Integer, Advisor.ItemMeta> meta = new HashMap<>();
			final long minVol = DEFAULT_MIN_VOLUME;
			final boolean membersWorld = client.getWorldType().contains(WorldType.MEMBERS);
			for (Map.Entry<Integer, Advisor.Quote> e : quotes.entrySet())
			{
				final int id = e.getKey();
				final Advisor.Quote q = e.getValue();
				final long vol = volumes.getOrDefault(id, 0L);
				final boolean candidate =
					(q.high > q.low && q.low > 0 && q.low <= cash && vol >= MIN_PREFILTER_VOLUME) // buy candidate
					|| holdings.containsKey(id)                                     // sell candidate
					|| isActiveOfferItem(offers, id);                              // adjust candidate
				if (!candidate)
				{
					continue;
				}
				if (!membersWorld)
				{
					final ItemComposition comp = itemManager.getItemComposition(id);
					if (comp != null && comp.isMembers())
					{
						continue; // f2p world: never suggest a members-only item
					}
				}
				meta.put(id, metaFor(id, vol));
			}

			final Set<Integer> blockedIds = blockedIds(meta, quotes);
			final List<Advisor.Suggestion> suggestions = Advisor.advise(
				nowSec, quotes, meta, cash, holdings, offers,
				skipped, blockedIds, minVol, 0.01, 4, tracker.getOpenBuyTotals(), lastOfferSeries);

			// Analyst Rating badge per suggestion — same rating language as pocketge.com.
			final Map<Integer, AnalystRating.Grade> ratings = new HashMap<>();
			for (Advisor.Suggestion s : suggestions)
			{
				ratings.put(s.itemId, AnalystRating.grade(quotes.get(s.itemId), averages.get(s.itemId)));
			}
			// Prefer a fresh BUY for the overlay (matches the panel's
			// Recommended Flip card); fall back to whatever else is there
			// (an adjust nudge, say) if there's no buy candidate right now.
			final Advisor.Suggestion topSuggestion = suggestions.stream()
				.filter(s -> s.type == Advisor.Suggestion.Type.BUY)
				.findFirst()
				.orElse(suggestions.isEmpty() ? null : suggestions.get(0));
			geOverlay.setSuggestion(topSuggestion);
			lastTopRecommendation = topSuggestion;

			// Bank/inventory highlight: keyed by item id so BankHighlightOverlay
			// can look up the right suggestion (and thus color/profit) for
			// whatever item slot it's currently drawing over.
			final Map<Integer, Advisor.Suggestion> suggestionsByItem = new HashMap<>();
			for (Advisor.Suggestion s : suggestions)
			{
				suggestionsByItem.put(s.itemId, s);
			}
			bankOverlay.setSuggestions(suggestionsByItem);

			final Set<Integer> favIds = favoriteIdSet();
			final PocketGeTrackerConfig.AdjustInterval currentInterval = config.adjustInterval();
			final AdvisorPanel.Settings currentSettings = buildSettings();
			SwingUtilities.invokeLater(() ->
			{
				mainPanel.setAdvisorStatus("Cash " + net.runelite.client.util.QuantityFormatter.quantityToStackSize(cash)
					+ " gp · every " + currentInterval);
				mainPanel.updateSuggestions(suggestions, ratings, favIds, currentSettings);
			});
		});
		refreshStatsAndFavorites();
	}

	private Advisor.ItemMeta metaFor(int id, long vol)
	{
		final Advisor.ItemMeta m = new Advisor.ItemMeta();
		m.id = id;
		final ItemComposition comp = itemManager.getItemComposition(id);
		m.name = comp != null ? comp.getName() : ("Item " + id);
		final ItemStats stats = itemManager.getItemStats(id);
		m.limit = stats != null ? stats.getGeLimit() : 0;
		m.dailyVolume = vol;
		return m;
	}

	/** Resolve blocked NAMES (config) to ids present in the current meta. */
	private Set<Integer> blockedIds(Map<Integer, Advisor.ItemMeta> meta, Map<Integer, Advisor.Quote> quotes)
	{
		final Set<Integer> ids = new HashSet<>();
		final String csv = config.blocklist();
		if (csv == null || csv.isEmpty())
		{
			return ids;
		}
		for (Map.Entry<Integer, Advisor.ItemMeta> e : meta.entrySet())
		{
			if (Blocklist.contains(csv, e.getValue().name))
			{
				ids.add(e.getKey());
			}
		}
		return ids;
	}

	private long countInventory(int itemId)
	{
		long total = 0;
		total += countIn(client.getItemContainer(InventoryID.INVENTORY), itemId);
		return total;
	}

	private long countIn(ItemContainer c, int itemId)
	{
		if (c == null)
		{
			return 0;
		}
		long n = 0;
		for (Item it : c.getItems())
		{
			if (it.getId() == itemId)
			{
				n += it.getQuantity();
			}
		}
		return n;
	}

	/** Bank (last snapshot) + inventory, minus coins. Canonicalised ids. */
	private Map<Integer, Integer> currentHoldings()
	{
		final Map<Integer, Integer> h = new HashMap<>(lastBank);
		final ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
		if (inv != null)
		{
			for (Item it : inv.getItems())
			{
				if (it.getId() == COINS_ID || it.getId() <= 0)
				{
					continue;
				}
				final int canon = itemManager.canonicalize(it.getId());
				h.merge(canon, it.getQuantity(), Integer::sum);
			}
		}
		h.remove(COINS_ID);
		return h;
	}

	/** Worn/equipped items, canonicalised — the other bucket of "stuff you
	 *  own" for portfolio value, alongside bank+inventory. */
	private Map<Integer, Integer> currentEquipped()
	{
		final Map<Integer, Integer> eq = new HashMap<>();
		final ItemContainer worn = client.getItemContainer(InventoryID.EQUIPMENT);
		if (worn != null)
		{
			for (Item it : worn.getItems())
			{
				if (it.getId() <= 0)
				{
					continue;
				}
				final int canon = itemManager.canonicalize(it.getId());
				eq.merge(canon, it.getQuantity(), Integer::sum);
			}
		}
		return eq;
	}

	private List<Advisor.OfferView> currentOffers()
	{
		final List<Advisor.OfferView> out = new ArrayList<>();
		final GrandExchangeOffer[] slots = client.getGrandExchangeOffers();
		if (slots == null)
		{
			return out;
		}
		for (int i = 0; i < slots.length; i++)
		{
			final GrandExchangeOffer o = slots[i];
			if (o == null || o.getItemId() <= 0)
			{
				continue;
			}
			final GrandExchangeOfferState st = o.getState();
			final boolean active = st == GrandExchangeOfferState.BUYING || st == GrandExchangeOfferState.SELLING;
			if (!active)
			{
				continue;
			}
			final Advisor.OfferView v = new Advisor.OfferView();
			v.slot = i;
			v.itemId = itemManager.canonicalize(o.getItemId());
			final ItemComposition comp = itemManager.getItemComposition(o.getItemId());
			v.itemName = comp != null ? comp.getName() : ("Item " + o.getItemId());
			v.buy = st == GrandExchangeOfferState.BUYING;
			v.price = o.getPrice();
			v.totalQuantity = o.getTotalQuantity();
			v.quantitySold = o.getQuantitySold();
			v.active = true;
			out.add(v);
		}
		return out;
	}

	private static boolean isActiveOfferItem(List<Advisor.OfferView> offers, int itemId)
	{
		for (Advisor.OfferView o : offers)
		{
			if (o.active && o.itemId == itemId)
			{
				return true;
			}
		}
		return false;
	}

	/** The gear-icon-adjacent "fill price" button's live half. Always copies
	 *  to the clipboard first (the proven fallback), then — ONLY if the
	 *  client thread can confirm from the actual on-screen chat prompt text
	 *  that a "...price..." entry prompt is genuinely open — writes the
	 *  number into it via the same client-state APIs RuneLite's own bundled
	 *  plugins use to fill chat prompts (BankSearch, ChatHistory, FairyRing):
	 *  set the chat input line's backing var, then ask the game to redraw
	 *  it. This never simulates a keypress or mouse click, and it never
	 *  presses Enter for you — confirming the offer is still your call. If
	 *  the prompt text can't be confirmed, nothing more happens; the
	 *  clipboard copy is the only effect, exactly like before this button
	 *  could also live-fill. */
	private void fillGePrice(long price)
	{
		final String priceStr = String.valueOf(price);
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(priceStr), null);
		clientThread.invokeLater(() ->
		{
			final Widget offerSetup = client.getWidget(InterfaceID.GeOffers.SETUP);
			if (offerSetup == null || offerSetup.isHidden())
			{
				return; // GE offer screen isn't open — clipboard copy is all we can do
			}
			final Widget mesText = client.getWidget(InterfaceID.Chatbox.MES_TEXT);
			final Widget mesText2 = client.getWidget(InterfaceID.Chatbox.MES_TEXT2);
			final String prompt = ((mesText != null ? mesText.getText() : "")
				+ " " + (mesText2 != null ? mesText2.getText() : "")).toLowerCase();
			if (!prompt.contains("price"))
			{
				return; // no price-entry prompt currently open — don't touch chat state we can't confirm
			}
			client.setVarcStrValue(VarClientID.MESLAYERINPUT, priceStr);
			client.runScript(ScriptID.CHAT_TEXT_INPUT_REBUILD, "");
		});
	}

	private void syncBridge()
	{
		if (bridge == null)
		{
			return;
		}
		bridge.stop();
		if (config.localBridge())
		{
			try
			{
				bridge.start(config.bridgePort(), () -> {
					List<FavoriteLists.FavoriteList> lists = loadFavoriteLists();
					FavoriteLists.FavoriteList active = activeFavoriteList(lists);
					return LocalBridgeServer.payload(
						tracker.getSessionProfit(), tracker.getLifetimeProfit(), tracker.getFlips(), tracker.getFills(),
						lastPortfolioValue, lists, active != null ? active.id : null, lastTopRecommendation);
				},
					this::setFavoriteFromBridge,
					new LocalBridgeServer.ListWriter()
					{
						@Override
						public String create(String name) { return createFavoriteListInternal(name); }

						@Override
						public void rename(String listId, String name) { renameFavoriteListInternal(listId, name); }

						@Override
						public void delete(String listId) { deleteFavoriteListInternal(listId); }
					});
				log.info("PocketGE local bridge listening on 127.0.0.1:{}", config.bridgePort());
			}
			catch (IOException e)
			{
				log.warn("PocketGE local bridge failed to start", e);
			}
		}
	}

	/** POST /favorites lands here, on the bridge's own HTTP thread — not the
	 *  Swing EDT or client thread. config writes are safe from any thread;
	 *  refreshStatsAndFavorites()/recomputeAdvice() marshal onto the client
	 *  thread themselves via clientThread.invokeLater(), which is likewise
	 *  safe to call from any thread, so no extra hop is needed here. listId
	 *  is null when the website didn't specify one (falls back to whichever
	 *  list is active in-game); if it named a list that no longer exists,
	 *  that's also treated as "use the active list" rather than silently
	 *  dropping the write. */
	private void setFavoriteFromBridge(String listId, int itemId, String name, boolean remove)
	{
		final List<FavoriteLists.FavoriteList> lists = loadFavoriteLists();
		FavoriteLists.FavoriteList target = listId != null ? FavoriteLists.findList(lists, listId) : null;
		if (target == null)
		{
			target = activeFavoriteList(lists);
		}
		if (target != null)
		{
			if (remove)
			{
				FavoriteLists.removeItem(target, itemId);
			}
			else
			{
				FavoriteLists.addItem(target, itemId, name);
			}
			saveFavoriteLists(lists);
		}
		refreshStatsAndFavorites();
		recomputeAdvice();
	}

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
	{
		final GrandExchangeOffer offer = event.getOffer();
		final GrandExchangeOfferState state = offer.getState();
		final boolean emptied = state == GrandExchangeOfferState.EMPTY;
		final boolean buy = state == GrandExchangeOfferState.BUYING
			|| state == GrandExchangeOfferState.BOUGHT
			|| state == GrandExchangeOfferState.CANCELLED_BUY;
		/* Resolve the item name now — we're on the client thread here. */
		final String name = emptied ? "" : itemManager.getItemComposition(offer.getItemId()).getName();
		final TradeFill fill = tracker.onOffer(
			System.currentTimeMillis(),
			event.getSlot(),
			offer.getItemId(),
			name,
			buy,
			offer.getQuantitySold(),
			offer.getSpent(),
			emptied
		);
		if (fill != null)
		{
			refreshPanel();
			saveState();
		}
		/* An offer just changed — re-check whether any active offer drifted
		   off the market, so "adjust" nudges are timely without waiting for
		   the next price poll. */
		if (config.advisor() && !lastQuotes.isEmpty())
		{
			recomputeAdvice();
		}
	}

	@Subscribe
	public void onItemContainerChanged(net.runelite.api.events.ItemContainerChanged event)
	{
		final ItemContainer c = event.getItemContainer();
		if (c == null || client.getItemContainer(InventoryID.BANK) != c)
		{
			return;
		}
		/* Snapshot the bank whenever it's open so "sell what you hold"
		   suggestions know your stacks even after the bank closes. */
		lastBank.clear();
		for (Item it : c.getItems())
		{
			if (it.getId() <= 0 || it.getId() == COINS_ID)
			{
				continue;
			}
			final int canon = itemManager.canonicalize(it.getId());
			lastBank.merge(canon, it.getQuantity(), Integer::sum);
		}
	}

	@Subscribe
	public void onGameStateChanged(net.runelite.api.events.GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			skipped.clear(); // session skips reset on logout
		}
	}

	/** Fires whenever the GE offer setup screen (re)builds — same hook
	 *  RuneLite's own bundled GE plugin uses for its "actively traded price"
	 *  hint. Lets the panel show a price for whatever item the player
	 *  actually has open right now, not just the advisor's own top pick. */
	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() != ScriptID.GE_OFFERS_SETUP_BUILD)
		{
			return;
		}
		final int itemId = client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH);
		final Advisor.Quote q = itemId > 0 ? lastQuotes.get(itemId) : null;
		final boolean isBuy = client.getVarbitValue(VarbitID.GE_NEWOFFER_TYPE) == 0;
		final long price = q == null ? 0 : (isBuy ? q.low : q.high);
		if (itemId <= 0 || price <= 0)
		{
			clearGeContext();
			return;
		}
		final ItemComposition comp = itemManager.getItemComposition(itemId);
		geContextItemId = itemId;
		geContextIsBuy = isBuy;
		geContextPrice = price;
		geContextName = comp != null ? comp.getName() : ("Item " + itemId);
		pushGeContext();
	}

	private void clearGeContext()
	{
		if (geContextItemId == null)
		{
			return;
		}
		geContextItemId = null;
		pushGeContext();
	}

	private void pushGeContext()
	{
		if (mainPanel == null)
		{
			return;
		}
		final Integer id = geContextItemId;
		final String name = geContextName;
		final boolean isBuy = geContextIsBuy;
		final long price = geContextPrice;
		SwingUtilities.invokeLater(() -> mainPanel.setGeContext(id, name, isBuy, price));
	}

	/** Loads the favorite lists, migrating the old flat CSV list into a
	 *  single default list on first run (once) and guaranteeing at least one
	 *  list always exists so callers never have to null-check an empty
	 *  collection. Persists whatever it just migrated/created. */
	private List<FavoriteLists.FavoriteList> loadFavoriteLists()
	{
		List<FavoriteLists.FavoriteList> lists = FavoriteLists.parse(gson, config.favoriteLists());
		if (lists.isEmpty())
		{
			lists = FavoriteLists.migrateFromCsv(config.favorites());
			if (lists.isEmpty())
			{
				lists.add(new FavoriteLists.FavoriteList(FavoriteLists.newListId(), "Favorites", FavoriteLists.PALETTE[0]));
			}
			saveFavoriteLists(lists);
		}
		return lists;
	}

	private void saveFavoriteLists(List<FavoriteLists.FavoriteList> lists)
	{
		config.setFavoriteLists(FavoriteLists.toJson(gson, lists));
	}

	/** The list the star button on suggestions/flips adds to. Falls back to
	 *  (and persists) the first list if the saved active id doesn't match
	 *  any current list — first run, or the active list got deleted. */
	private FavoriteLists.FavoriteList activeFavoriteList(List<FavoriteLists.FavoriteList> lists)
	{
		FavoriteLists.FavoriteList l = FavoriteLists.findList(lists, config.activeFavoriteList());
		if (l == null && !lists.isEmpty())
		{
			l = lists.get(0);
			config.setActiveFavoriteList(l.id);
		}
		return l;
	}

	/** Shared by the panel's "+" chip and the website (via the bridge's
	 *  POST /favoriteLists) — both create a list the same way. Returns the
	 *  new list's id. */
	private String createFavoriteListInternal(String name)
	{
		List<FavoriteLists.FavoriteList> lists = loadFavoriteLists();
		String color = FavoriteLists.PALETTE[lists.size() % FavoriteLists.PALETTE.length];
		FavoriteLists.FavoriteList l = new FavoriteLists.FavoriteList(FavoriteLists.newListId(), name, color);
		lists.add(l);
		saveFavoriteLists(lists);
		config.setActiveFavoriteList(l.id);
		refreshStatsAndFavorites();
		recomputeAdvice();
		return l.id;
	}

	/** Shared by the panel's chip-menu "Rename list" and the website (via
	 *  the bridge). No-op if listId doesn't match any current list. */
	private void renameFavoriteListInternal(String listId, String name)
	{
		List<FavoriteLists.FavoriteList> lists = loadFavoriteLists();
		FavoriteLists.FavoriteList l = FavoriteLists.findList(lists, listId);
		if (l != null)
		{
			l.name = name;
			saveFavoriteLists(lists);
			refreshStatsAndFavorites();
		}
	}

	/** Shared by the panel's chip-menu "Delete list" and the website (via
	 *  the bridge). Always keeps at least one list, same as the in-game
	 *  guard — the star button and the website both need somewhere to add
	 *  to. No-op if listId doesn't match any current list. */
	private void deleteFavoriteListInternal(String listId)
	{
		List<FavoriteLists.FavoriteList> lists = loadFavoriteLists();
		if (lists.size() <= 1 || FavoriteLists.findList(lists, listId) == null)
		{
			return;
		}
		lists.removeIf(l -> listId.equals(l.id));
		saveFavoriteLists(lists);
		if (listId.equals(config.activeFavoriteList()))
		{
			config.setActiveFavoriteList(lists.get(0).id);
		}
		refreshStatsAndFavorites();
		recomputeAdvice();
	}

	private Set<Integer> favoriteIdSet()
	{
		final Set<Integer> ids = new HashSet<>();
		final List<FavoriteLists.FavoriteList> lists = loadFavoriteLists();
		final FavoriteLists.FavoriteList active = activeFavoriteList(lists);
		if (active != null)
		{
			for (Favorites.Fav f : active.items)
			{
				ids.add(f.id);
			}
		}
		return ids;
	}

	/** Shared by the panel's star buttons and the bank/inventory right-click
	 *  menu entry below, so both paths flip the same list membership the
	 *  same way — always against whichever list is currently active. */
	private void toggleFavorite(int itemId, String name)
	{
		final List<FavoriteLists.FavoriteList> lists = loadFavoriteLists();
		final FavoriteLists.FavoriteList active = activeFavoriteList(lists);
		if (active == null)
		{
			return;
		}
		if (FavoriteLists.contains(active, itemId))
		{
			FavoriteLists.removeItem(active, itemId);
		}
		else
		{
			FavoriteLists.addItem(active, itemId, name);
		}
		saveFavoriteLists(lists);
		refreshStatsAndFavorites();
		recomputeAdvice(); // suggestion cards' star state also needs to flip
	}

	/** Adds a "PocketGE Favorites" right-click option to bank/inventory/
	 *  equipment item slots, piggybacking on the "Examine" entry the same way
	 *  RuneLite's own inventory-tags/menu-entry-swapper plugins do, so it's
	 *  injected exactly once per hover instead of once per existing option. */
	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		if (!"Examine".equals(event.getOption()) || event.getItemId() <= 0)
		{
			return;
		}
		final int groupId = WidgetUtil.componentToInterface(event.getActionParam1());
		if (groupId != InterfaceID.BANKMAIN && groupId != InterfaceID.BANKSIDE
			&& groupId != InterfaceID.INVENTORY && groupId != InterfaceID.WORNITEMS)
		{
			return;
		}
		final int itemId = itemManager.canonicalize(event.getItemId());
		final ItemComposition comp = itemManager.getItemComposition(itemId);
		final String name = comp != null ? comp.getName() : ("Item " + itemId);
		final boolean fav = favoriteIdSet().contains(itemId);

		client.createMenuEntry(-1)
			.setOption(fav ? "Remove PocketGE favorite" : "Add PocketGE favorite")
			.setTarget(event.getTarget())
			.setType(MenuAction.RUNELITE)
			.onClick(e -> toggleFavorite(itemId, name));
	}

	/** Snapshot of every setting the gear-icon popup shows, straight from
	 *  config — so the popup never needs a trip to RuneLite's own plugin
	 *  config screen to stay current. */
	private AdvisorPanel.Settings buildSettings()
	{
		final AdvisorPanel.Settings s = new AdvisorPanel.Settings();
		s.advisorOn = config.advisor();
		s.interval = config.adjustInterval();
		s.blocked = Blocklist.parse(config.blocklist());
		s.bridgeOn = config.localBridge();
		s.bridgePort = config.bridgePort();
		s.maxFlips = config.maxFlips();
		return s;
	}

	private void refreshPanel()
	{
		if (mainPanel == null)
		{
			return;
		}
		final int max = config.maxFlips();
		final java.util.List<Flip> all = tracker.getFlips();
		final java.util.List<Flip> capped = all.size() > max ? all.subList(all.size() - max, all.size()) : all;
		SwingUtilities.invokeLater(() -> mainPanel.updateHistory(new ArrayList<>(capped)));
		refreshStatsAndFavorites();
	}

	/** Portfolio value + time-window stats + the Favorites section, all of
	 *  which need the CURRENT holdings/offers (client thread) combined with
	 *  whatever prices are cached (empty if the advisor has never fetched —
	 *  everything still degrades gracefully to "cash only", never crashes).
	 *  Runs on its own client-thread hop so it can be called from anywhere
	 *  (a completed flip, a price refresh, the range dropdown, favoriting a
	 *  row) without assuming the caller is already on that thread. */
	private void refreshStatsAndFavorites()
	{
		if (mainPanel == null)
		{
			return;
		}
		clientThread.invokeLater(() ->
		{
			if (geContextItemId != null)
			{
				final Widget offerSetup = client.getWidget(InterfaceID.GeOffers.SETUP);
				if (offerSetup == null || offerSetup.isHidden())
				{
					clearGeContext(); // the offer screen closed since we last saw it
				}
			}

			final Map<Integer, Advisor.Quote> quotes = lastQuotes;
			final Map<Integer, AnalystRating.Average> averages = lastAverages;

			final long cash = countInventory(COINS_ID);
			final Map<Integer, Integer> holdings = currentHoldings();
			final Map<Integer, Integer> equipped = currentEquipped();
			final List<Advisor.OfferView> offers = currentOffers();
			final PortfolioValuer.Result portfolio = PortfolioValuer.value(cash, holdings, equipped, offers, quotes);

			long unrealized = 0;
			for (Map.Entry<Integer, long[]> e : tracker.getOpenBuyTotals().entrySet())
			{
				final Advisor.Quote q = quotes.get(e.getKey());
				if (q == null || q.low <= 0)
				{
					continue;
				}
				final long qty = e.getValue()[0];
				final long spent = e.getValue()[1];
				unrealized += qty * q.low - spent;
			}

			final FlipStats.Range range = currentRange;
			final java.util.List<Flip> flips = tracker.getFlips();
			final long firstFlipMillis = flips.isEmpty() ? System.currentTimeMillis() : flips.get(0).closedAt;
			final FlipStats.Stats stats = FlipStats.compute(
				flips, range, System.currentTimeMillis(), tracker.getSessionStartMillis(), firstFlipMillis, unrealized);

			final List<FavoriteLists.FavoriteList> favLists = loadFavoriteLists();
			final FavoriteLists.FavoriteList activeList = activeFavoriteList(favLists);
			final List<FavoritesPanel.Row> favRows = new ArrayList<>();
			for (Favorites.Fav f : activeList != null ? activeList.items : List.<Favorites.Fav>of())
			{
				final FavoritesPanel.Row row = new FavoritesPanel.Row();
				row.id = f.id;
				row.name = f.name;
				final Advisor.Quote q = quotes.get(f.id);
				final AnalystRating.Average avg = averages.get(f.id);
				if (q != null && q.low > 0)
				{
					row.price = q.low;
					if (avg != null && avg.avgHighPrice > 0 && avg.avgLowPrice > 0)
					{
						final double typical = (avg.avgHighPrice + avg.avgLowPrice) / 2.0;
						row.changePct = ((q.low - typical) / typical) * 100.0;
					}
				}
				/* Detail-view fields — same buy-low/sell-high convention as
				   Advisor's own suggestion pricing (q.low to buy, q.high to
				   sell), so "target" here always matches what a Recommended
				   Flip card would show for the same item. */
				if (q != null && q.low > 0 && q.high > q.low)
				{
					row.targetBuy = q.low;
					row.targetSell = q.high;
					final ItemStats itemStats = itemManager.getItemStats(f.id);
					row.limit = itemStats != null ? itemStats.getGeLimit() : 0;
					if (row.limit > 0)
					{
						final long edge = q.high - q.low - FlipTracker.taxPerItem(q.high, f.id);
						row.potentialProfit = edge * row.limit;
					}
				}
				row.rating = AnalystRating.grade(q, avg);
				final MarketClient.DayExtremes ex = dayExtremes.get(f.id);
				/* Same "near the extreme" definition as the website's ▲/▼ 5D
				   badge: within 8% of the 5-day range from the high or low —
				   only meaningful once that range is at least 3% of the low
				   (a near-flat item shouldn't flash on noise). */
				if (ex != null && ex.hi5d > 0 && ex.lo5d > 0 && ex.hi5d - ex.lo5d >= ex.lo5d * 0.03)
				{
					final double range5d = ex.hi5d - ex.lo5d;
					if (q != null && q.high > 0 && (ex.hi5d - q.high) / range5d <= 0.08)
					{
						row.atHigh5d = true;
					}
					else if (q != null && q.low > 0 && (q.low - ex.lo5d) / range5d <= 0.08)
					{
						row.atLow5d = true;
					}
				}
				favRows.add(row);
			}
			// 5-day high/low flagged items are the ones worth acting on right
			// now — float them to the top instead of making the player scroll
			// to notice them, on top of whatever manual order they've set.
			favRows.sort(java.util.Comparator.comparing((FavoritesPanel.Row r) -> !(r.atHigh5d || r.atLow5d)));
			lastPortfolioValue = portfolio.total;

			final List<FavoritesPanel.ListMeta> listMetas = new ArrayList<>();
			for (FavoriteLists.FavoriteList l : favLists)
			{
				final FavoritesPanel.ListMeta lm = new FavoritesPanel.ListMeta();
				lm.id = l.id;
				lm.name = l.name;
				lm.color = l.color;
				listMetas.add(lm);
			}
			final String activeListId = activeList != null ? activeList.id : null;

			SwingUtilities.invokeLater(() ->
			{
				mainPanel.updateStats(stats, portfolio);
				mainPanel.updateFavoriteLists(listMetas, activeListId);
				mainPanel.updateFavorites(favRows);
			});
		});
	}
}
