package com.pocketge.tracker;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.api.ChatMessageType;
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
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
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
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
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
import net.runelite.client.util.LinkBrowser;
import net.runelite.client.util.QuantityFormatter;
import net.runelite.client.util.Text;
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
	private BankHighlightOverlay bankOverlay;

	@Inject
	private GeOfferGridOverlay geGridOverlay;

	@Inject
	private GeOfferPriceOverlay gePriceOverlay;

	@Inject
	private net.runelite.client.input.MouseManager mouseManager;

	@Inject
	private net.runelite.client.Notifier notifier;

	/** Turns a click on the in-game price panel into a fill, so the number
	 *  doesn't have to be typed or chased from the sidebar. Registered only
	 *  while the plugin is on; the overlay itself decides what (if anything)
	 *  is under the cursor, so this consumes a click ONLY when it is
	 *  genuinely over our own drawing. */
	private final net.runelite.client.input.MouseAdapter priceClickListener = new net.runelite.client.input.MouseAdapter()
	{
		@Override
		public java.awt.event.MouseEvent mousePressed(java.awt.event.MouseEvent e)
		{
			if (!javax.swing.SwingUtilities.isLeftMouseButton(e))
			{
				return e;
			}
			if (gePriceOverlay.isOverSearchChip(e.getPoint()))
			{
				final String name = gePriceOverlay.searchToFill();
				if (name != null && !name.isEmpty())
				{
					fillGeSearch(name);
					e.consume();
				}
				return e;
			}
			if (!gePriceOverlay.isOverPrice(e.getPoint()))
			{
				return e;
			}
			final long price = gePriceOverlay.priceToFill();
			if (price <= 0)
			{
				return e;
			}
			fillGePrice(price);
			e.consume(); // don't also click whatever is behind the panel
			return e;
		}
	};

	@Inject
	private ChatMessageManager chatMessageManager;

	private final FlipTracker tracker = new FlipTracker();
	private LocalBridgeServer bridge;
	private MainPanel mainPanel;
	private NavigationButton navButton;

	private ScheduledFuture<?> advisorTask;
	/** Keeps portfolio value + favorites fresh on the bridge even when the
	 *  Advisor is off — see syncBridge(). */
	private ScheduledFuture<?> bridgeRefreshTask;
	/** Coins are item id 995 in every container. */
	private static final int COINS_ID = 995;
	private static final Color FINDER_POSITIVE = new Color(0x1F, 0xB8, 0x5C);
	private static final Color FINDER_NEGATIVE = new Color(0xEF, 0x53, 0x50);
	// Same colors as FavoritesPanel's own ▲/▼ 5D badge (HIGH5D/LOW5D) —
	// duplicated here rather than shared since that field is private to a
	// Swing panel and this file has no UI dependency of its own.
	private static final Color FINDER_HIGH5D = new Color(0x00, 0xFF, 0x7A);
	private static final Color FINDER_LOW5D = new Color(0xFF, 0xB3, 0x00);
	private static final int FINDER_LIST_CAP = 10;
	/** Grand Exchange slot counts — 3 on a free world, 8 with membership.
	 *  The whole point of the capital plan is fitting a bank into these. */
	/** How many BUY ideas Advisor ranks for the recommendation stream. The
	 *  capital plan only ever proposes one per free GE slot (3 or 8), which
	 *  made the whole "N of M" list short — these fill it out with the next
	 *  best ideas you could act on once a slot frees up. */
	private static final int MAX_BUY_IDEAS = 15;
	/** Hard ceiling on the stream so paging through it stays finite. */
	private static final int MAX_RECOMMENDATIONS = 20;
	private static final int F2P_GE_SLOTS = 3;
	private static final int MEMBERS_GE_SLOTS = 8;
	/** Bound on the At 5D Highs/Lows candidate pool (on top of whatever's
	 *  favorited) — each id costs one extra /timeseries call inside
	 *  refreshDayExtremes, at most once per DAY_EXTREMES_TTL_MS, so this
	 *  caps that burst to a manageable size instead of scanning the whole
	 *  tradeable item universe like pocketge.com's own server-side scan
	 *  can afford to. */
	private static final int EXTREME_CANDIDATE_POOL_SIZE = 40;
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
	/** itemId -> when a price-move alert last fired for it. Written from the
	 *  advisor's background thread — see maybeAlertOnMove. */
	private final Map<Integer, Long> lastAlertMillis = new java.util.concurrent.ConcurrentHashMap<>();
	/** How long one item stays quiet after alerting. An hour: long enough that
	 *  a stubborn dip doesn't nag every refresh, short enough that a move
	 *  lasting all evening still gets mentioned more than once. */
	private static final long ALERT_COOLDOWN_MS = 60 * 60 * 1000L;
	/** Last bank snapshot (item id -> qty), refreshed whenever the bank opens. */
	private final Map<Integer, Integer> lastBank = new HashMap<>();
	/** Coins seen sitting IN the bank on the last snapshot — tracked
	 *  separately from lastBank (which deliberately excludes coins, since
	 *  everything else in it gets valued via a live quote lookup and coins
	 *  don't need one). Most players keep their gp banked rather than
	 *  carried, so "cash" for BUY suggestions and portfolio value both need
	 *  this added to inventory coins, or they'd only ever see whatever's
	 *  loose in the inventory — near-zero for anyone with real wealth. */
	private volatile long lastBankCoins = 0;
	/** Platinum tokens seen in the bank on the last snapshot, kept separate
	 *  from lastBank for the same reason coins are — they are cash at an
	 *  exact 1:1000, not stock to be valued from a quote. Anyone flipping at
	 *  size keeps most of their gp this way, so leaving them out made the
	 *  advisor believe a rich player was broke. See
	 *  PortfolioValuer.PLATINUM_TOKEN_ID. */
	private volatile long lastBankPlatinum = 0;
	/** RuneLite can't read bank contents until the player has opened the bank
	 *  at least once this session — until then, portfolio value silently
	 *  excludes it. Tracked so the bridge/panel can say so instead of just
	 *  showing a quietly-too-low total. */
	private volatile boolean bankSeen = false;
	/** Epoch millis of the last bank snapshot, 0 for never. "Seen" alone
	 *  can't tell a bank read ten seconds ago from one read three hours and
	 *  forty trades back, and everything derived from lastBank — portfolio
	 *  value, sell suggestions, the watchlist profit tags — is exactly that
	 *  stale. Published so the website can say so. */
	private volatile long bankSeenAt = 0;
	/** Most recent chat line per sender — lets the "Search PocketGE for X"
	 *  right-click option (see onMenuEntryAdded) know what a right-clicked
	 *  chat line actually said, since MenuEntryAdded only exposes the
	 *  target (the sender's name), never the message text. Bounded LRU so
	 *  a busy world's chat can't grow this without limit. */
	private final Map<String, String> lastChatMessageBySender = new LinkedHashMap<String, String>(16, 0.75f, false)
	{
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, String> eldest)
		{
			return size() > 40;
		}
	};
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
	/** Whichever item Advisor.advise() picked as the "sell what you hold"
	 *  suggestion this cycle, if any — folded into refreshOfferSeries()'s
	 *  fetch set (like lastActiveOfferItemIds) so that suggestion's price
	 *  can reprice through TradeEngine too, same as ADJUST_BUY/ADJUST_SELL. */
	private volatile Integer lastSellCandidateItemId = null;
	/** 5-day high/low per favorited item, powering the Favorites panel's
	 *  flashing "at a 5-day high/low" glow — same signal as the website's
	 *  ▲/▼ 5D badge. Only ever holds entries for CURRENTLY favorited items
	 *  (see refreshDayExtremes) since it's a per-item network call. */
	private final Map<Integer, PriceExtremes> dayExtremes = new java.util.concurrent.ConcurrentHashMap<>();
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
	/** Spendable gp as of the last refresh — published on the bridge so the
	 *  website can show the same liquid figure the advisor sizes buys
	 *  against, rather than inferring it from a net-worth total that also
	 *  includes stock it can't sell instantly. */
	private volatile long lastCash = 0;
	/** The priced bank composition served on the bridge. Recomputed on the
	 *  same cadence as portfolio value rather than per request: the website
	 *  polls several times a minute and this walks the whole bank doing an
	 *  itemComposition lookup per stack, which has no business running on
	 *  the HTTP thread once per poll. */
	private volatile List<LocalBridgeServer.BankStack> lastBankStacks = java.util.Collections.emptyList();
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
			public void reorderFavoriteTo(int itemId, int newIndex)
			{
				List<FavoriteLists.FavoriteList> lists = loadFavoriteLists();
				FavoriteLists.FavoriteList active = activeFavoriteList(lists);
				if (active != null)
				{
					FavoriteLists.moveItemToIndex(active, itemId, newIndex);
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
			public void searchItems(String query, java.util.function.Consumer<List<FavoritesPanel.SearchResult>> callback)
			{
				/* ItemManager.search() (the same lookup the in-game GE search
				   box uses) needs the client thread; the panel needs its
				   results back on the EDT — hop both ways here so neither side
				   has to know about the other's thread. */
				clientThread.invokeLater(() ->
				{
					final List<FavoritesPanel.SearchResult> out = new ArrayList<>();
					try
					{
						int count = 0;
						for (net.runelite.http.api.item.ItemPrice ip : itemManager.search(query))
						{
							if (count++ >= 8)
							{
								break; // keep the dropdown short — this is a picker, not a full results page
							}
							final FavoritesPanel.SearchResult r = new FavoritesPanel.SearchResult();
							r.id = ip.getId();
							r.name = ip.getName();
							out.add(r);
						}
					}
					catch (Exception e)
					{
						log.warn("PocketGE: item search failed for '{}'", query, e);
					}
					SwingUtilities.invokeLater(() -> callback.accept(out));
				});
			}

			@Override
			public void addFavorite(int itemId, String name)
			{
				final List<FavoriteLists.FavoriteList> lists = loadFavoriteLists();
				final FavoriteLists.FavoriteList active = activeFavoriteList(lists);
				if (active == null || FavoriteLists.contains(active, itemId))
				{
					return; // already in the active list — search-to-add only ever adds, never removes
				}
				FavoriteLists.addItem(active, itemId, name);
				saveFavoriteLists(lists);
				refreshStatsAndFavorites();
				recomputeAdvice();
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

			@Override
			public void fillGeQuantity(long qty)
			{
				PocketGeTrackerPlugin.this.fillGeQuantity(qty);
			}

			@Override
			public void openChart(String itemName)
			{
				openPocketGeSearch(itemName);
			}

			@Override
			public void setSlotAdviceSkipped(int slot, boolean skipped)
			{
				/* Same set the in-game right-click drives, so the two entry
				   points cannot disagree about which offers you are pricing
				   yourself. Recompute immediately: the whole point is that
				   the red flag goes away now, not on the next tick. */
				if (skipped)
				{
					adviceSkippedSlots.add(slot);
				}
				else
				{
					adviceSkippedSlots.remove(slot);
				}
				scheduleAdviceNow();
			}

			@Override
			public void refreshSuggestions()
			{
				/* Off the Swing thread: this is the full advisor cycle, live
				   price fetches and all, and running it on the EDT would
				   freeze the client for as long as the network takes. The
				   panel repaints itself when the cycle publishes. */
				scheduleAdviceNow();
			}
		});
		mainPanel.setSelectedRangeQuietly(currentRange);
		/* Seed from config rather than trusting the panel's own default, so
		   badges stay off across a restart for anyone who turned them off. */
		mainPanel.setBadgesEnabled(config.showBadges());

		final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "icon.png");
		navButton = NavigationButton.builder()
			.tooltip("PocketGE Flip Tracker")
			.icon(icon)
			.priority(6)
			.panel(mainPanel)
			.build();
		clientToolbar.addNavigation(navButton);
		// Registered either way; the overlay itself no-ops when off, so a
		// toggle never has to add/remove a live overlay mid-frame.
		bankOverlay.setEnabled(config.bankHighlights());
		overlayManager.add(bankOverlay);
		overlayManager.add(geGridOverlay);
		overlayManager.add(gePriceOverlay);
		mouseManager.registerMouseListener(priceClickListener);

		// Seed the panel's login state from the CURRENT game state rather than
		// waiting on a GameStateChanged event: enabling the plugin while
		// already sitting on the login screen fires no such event, which left
		// the sidebar showing empty advisor boxes instead of "log in to start".
		setPanelLoggedIn(client.getGameState() == GameState.LOGGED_IN);
		refreshPanel();
		syncBridge();
		syncAdvisor();
	}

	@Override
	protected void shutDown()
	{
		saveState();
		clientToolbar.removeNavigation(navButton);
		overlayManager.remove(bankOverlay);
		overlayManager.remove(geGridOverlay);
		overlayManager.remove(gePriceOverlay);
		mouseManager.unregisterMouseListener(priceClickListener);
		if (advisorTask != null)
		{
			advisorTask.cancel(false);
			advisorTask = null;
		}
		if (bridgeRefreshTask != null)
		{
			bridgeRefreshTask.cancel(false);
			bridgeRefreshTask = null;
		}
		if (bridge != null)
		{
			bridge.stop();
		}
		if (mainPanel != null)
		{
			mainPanel.stopFavoritesGlow();
			mainPanel.dispose();
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
			bankOverlay.setEnabled(config.bankHighlights());
			SwingUtilities.invokeLater(() -> mainPanel.setBadgesEnabled(config.showBadges()));
			if ("blocklist".equals(event.getKey()))
			{
				recomputeAdvice(); // reflect manual edits to the never-recommend box
			}
			if ("minProfit".equals(event.getKey()))
			{
				/* The floor is applied inside Advisor, so the currently shown
				   suggestions were built against the old one — re-rank now
				   rather than leaving a sub-floor idea on screen until the
				   next cycle, which is exactly the thing just switched off. */
				recomputeAdvice();
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
				mainPanel.updateRecommendations(new ArrayList<>());
				mainPanel.updateGeSlots(null);
				mainPanel.updateFinder(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
			});
			bankOverlay.setSuggestions(new HashMap<>());
			geGridOverlay.setSlots(null);
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

	/**
	 * Turns this cycle's offers into what the GE-slot overlay draws: the
	 * border colour it already had, plus fill progress and a profit figure
	 * for its hover tooltip.
	 *
	 * Profit is measured against the buy lots the plugin actually watched
	 * you make, and is left NULL when there are none — the same rule the
	 * recommendation card follows. A stack the plugin never saw you buy did
	 * not cost zero; it cost an unknown amount, and "Profit: 5.3M" computed
	 * from a zero cost is a lie the tooltip is not allowed to tell.
	 *
	 * Buys are the mirror image: what a buy offer "makes" is what it would
	 * make on the way back out, so it is priced against the live insta-buy
	 * rather than a cost basis, and labelled "if it flips" to say so.
	 */
	private Map<Integer, GeOfferGridOverlay.SlotView> buildSlotViews(
		List<Advisor.OfferView> offers, Map<Integer, Boolean> slotStatus, Map<Integer, long[]> openBuys)
	{
		final Map<Integer, GeOfferGridOverlay.SlotView> out = new HashMap<>();
		for (Advisor.OfferView o : offers)
		{
			if (!o.active || o.slot < 0)
			{
				continue;
			}
			final GeOfferGridOverlay.SlotView v = new GeOfferGridOverlay.SlotView();
			v.itemName = o.itemName;
			v.buy = o.buy;
			v.filled = o.quantitySold;
			v.total = o.totalQuantity;
			v.needsAdjust = Boolean.FALSE.equals(slotStatus.get(o.slot));
			v.adviceSkipped = adviceSkippedSlots.contains(o.slot);
			if (v.adviceSkipped)
			{
				v.needsAdjust = false;
			}

			final long unitNet = o.price - FlipTracker.taxPerItem(o.price, o.itemId);
			if (!o.buy)
			{
				final long[] lot = openBuys.get(o.itemId);
				if (lot != null && lot.length >= 2 && lot[0] > 0 && lot[1] > 0)
				{
					final long unitCost = (long) Math.ceil(lot[1] / (double) lot[0]);
					v.projectedProfit = (long) o.totalQuantity * (unitNet - unitCost);
					v.filledProfit = (long) o.quantitySold * (unitNet - unitCost);
				}
			}
			else
			{
				/* What this buy is worth once flipped, at today's bid. No
				   cost basis needed — you are establishing it right now. */
				final Advisor.Quote q = lastQuotes.get(o.itemId);
				if (q != null && q.high > 0)
				{
					final long exitNet = q.high - FlipTracker.taxPerItem(q.high, o.itemId);
					v.projectedProfit = (long) o.totalQuantity * (exitNet - o.price);
					v.filledProfit = (long) o.quantitySold * (exitNet - o.price);
				}
			}
			out.put(o.slot, v);
		}
		return out;
	}

	/**
	 * Re-run the advisor off the Swing thread, right now.
	 *
	 * Called when Next walks off the end of the suggestion list. That list
	 * used to wrap silently and hand back the same ring of ideas forever, so
	 * running out is now a reason to go and find more. Never on the EDT: this
	 * is the whole cycle, live price fetches included, and running it there
	 * would freeze the client for as long as the network takes.
	 *
	 * A price fetch only happens when nothing is cached; the usual case just
	 * re-runs the maths over prices already in hand, which is enough to pick
	 * up whatever you have bought, sold, held or blocked since the list was
	 * built. Same reasoning as the post-login path.
	 */
	private void scheduleAdviceNow()
	{
		final ScheduledExecutorService ex = executor;
		if (ex == null || !config.advisor())
		{
			return;
		}
		ex.submit(lastQuotes.isEmpty() ? this::refreshPrices : this::recomputeAdvice);
	}

	/** One /timeseries call per item with an active GE offer as of the last
	 *  recomputeAdvice() cycle, plus the current SELL-suggestion candidate
	 *  and whatever item is sitting in an open GE offer screen — bounded to
	 *  at most 8 + 2 (the GE slot count plus two singletons), the same
	 *  "small, bounded" shape as refreshDayExtremes above. Feeds TradeEngine
	 *  so ADJUST_BUY/ADJUST_SELL/SELL and the GE-context price can all
	 *  reprice to pocketge.com's own target instead of the raw live quote
	 *  (see Advisor.advise, onScriptPostFired). No staleness TTL needed —
	 *  the set is already tiny, so it's cheap to refetch every advisor cycle
	 *  rather than track a separate timer. */
	private void refreshOfferSeries()
	{
		final Set<Integer> ids = new HashSet<>(lastActiveOfferItemIds);
		final Integer sellCandidate = lastSellCandidateItemId;
		if (sellCandidate != null)
		{
			ids.add(sellCandidate);
		}
		final Integer geItem = geContextItemId;
		if (geItem != null)
		{
			ids.add(geItem);
		}
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

	/** One /timeseries call per tracked item, only for items not already
	 *  cached (or all of them once every DAY_EXTREMES_TTL_MS) — bounded to
	 *  favorites plus a capped top-volume pool (see
	 *  EXTREME_CANDIDATE_POOL_SIZE), unlike the bulk endpoints above which
	 *  cover every tradeable item in one call. A single item's fetch
	 *  failing just leaves that entry stale rather than aborting the
	 *  refresh. Feeds both the Favorites list's own ▲/▼ 5D badge (favorites
	 *  only) and the Find Opportunities At 5D Highs/Lows scanner (favorites
	 *  + the pool). */
	private void refreshDayExtremes()
	{
		final Set<Integer> favIds = favoriteIdSet();
		final Set<Integer> trackedIds = new HashSet<>(favIds);
		trackedIds.addAll(extremeCandidatePool());
		dayExtremes.keySet().retainAll(trackedIds); // drop ids no longer tracked
		final boolean stale = System.currentTimeMillis() - dayExtremesRefreshedAt > DAY_EXTREMES_TTL_MS;
		if (stale)
		{
			dayExtremesRefreshedAt = System.currentTimeMillis();
		}
		for (Integer id : trackedIds)
		{
			PriceExtremes ex = dayExtremes.get(id);
			if (stale || ex == null)
			{
				try
				{
					ex = marketClient.fetchRecentExtremes(id);
					dayExtremes.put(id, ex);
				}
				catch (Exception e)
				{
					log.warn("PocketGE advisor: recent extremes fetch failed for item {}", id, e);
					continue;
				}
			}
		}
	}

	/** The top EXTREME_CANDIDATE_POOL_SIZE ids by 24h volume from the last
	 *  price fetch — a cheap, already-fetched signal for "actually worth
	 *  scanning" that keeps the At 5D Highs/Lows pool small without a
	 *  second network call just to pick candidates. */
	private Set<Integer> extremeCandidatePool()
	{
		return lastVolumes.entrySet().stream()
			.sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
			.limit(EXTREME_CANDIDATE_POOL_SIZE)
			.map(Map.Entry::getKey)
			.collect(java.util.stream.Collectors.toSet());
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

			final long cash = totalCash();
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
					/* Deliberately NOT gated on cash. Affordability belongs
					   downstream, where it already is (Advisor.buildBuys checks
					   q.low > cash, CapitalPlanner.pool checks unitBuy > cash).
					   Gating here meant cash==0 — which is every login before
					   you've opened a bank, since bank coins are the bulk of it
					   — emptied this map entirely, starving the SELL path and
					   the finder rows too, not just buys. That's what left the
					   panel with nothing to say on login. */
					(q.high > q.low && q.low > 0 && vol >= MIN_PREFILTER_VOLUME) // buy candidate
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
				skipped, blockedIds, minVol, 0.01, MAX_BUY_IDEAS, tracker.getOpenBuyTotals(), lastOfferSeries,
				config.minProfit().gp());
			/* Drop reprice advice for any slot you have said you are pricing
			   yourself. Filtered here rather than inside Advisor because it
			   is a preference about one offer, not a fact about the market —
			   Advisor stays a pure function of prices and holdings. Doing it
			   once, here, also means everything downstream reads the same
			   filtered list: the red border, the sidebar's adjust card and
			   the GE-slot panel cannot end up disagreeing about whether you
			   were asked to reprice. */
			if (!adviceSkippedSlots.isEmpty())
			{
				suggestions.removeIf(s ->
					(s.type == Advisor.Suggestion.Type.ADJUST_BUY || s.type == Advisor.Suggestion.Type.ADJUST_SELL)
						&& adviceSkippedSlots.contains(s.slot));
			}

			// Capital plan — "here's how to actually deploy your bank across
			// the slots you have free". Separate from the suggestions above
			// on purpose: each of those is sized against the FULL cash pile
			// independently, so four of them can collectively cost several
			// times what you hold. This is the one affordable portfolio.
			final int freeSlots = freeGeSlots();
			final List<CapitalPlanner.Candidate> planCandidates = new ArrayList<>();
			for (Map.Entry<Integer, Advisor.ItemMeta> e : meta.entrySet())
			{
				final int id = e.getKey();
				final Advisor.ItemMeta m = e.getValue();
				final Advisor.Quote q = quotes.get(id);
				if (q == null || blockedIds.contains(id) || skipped.contains(id) || isActiveOfferItem(offers, id))
				{
					continue;
				}
				if (q.low <= 0 || q.high <= q.low || m.dailyVolume < minVol)
				{
					continue;
				}
				final CapitalPlanner.Candidate c = new CapitalPlanner.Candidate();
				c.id = id;
				c.name = m.name;
				c.unitBuy = q.low;
				c.unitEdge = q.high - q.low - FlipTracker.taxPerItem(q.high, id);
				c.limit = m.limit;
				c.dailyVolume = m.dailyVolume;
				planCandidates.add(c);
			}
			final CapitalPlanner.Plan capitalPlan = CapitalPlanner.plan(cash, freeSlots, planCandidates);
			// Same scoring advise() uses for its single best sell — the box
			// just shows several of them instead of one.
			final List<Advisor.Suggestion> sellRows = Advisor.sellCandidates(
				nowSec, quotes, meta, holdings, offers, skipped, blockedIds, tracker.getOpenBuyTotals());

			/* One stream, sells first. Both answer "what's the best use of a
			   slot right now", but a sell needs no capital and frees some, so
			   it outranks a buy of equal size. The capital plan still sizes
			   the buys against liquid cash and free slots — that just isn't a
			   separate thing the player has to look at any more. */
			final List<AdvisorPanel.Rec> recommendations = new ArrayList<>();
			for (Advisor.Suggestion sell : sellRows)
			{
				if (recommendations.size() >= MAX_RECOMMENDATIONS)
				{
					break; // sellCandidates is unbounded — it walks every holding
				}
				final AdvisorPanel.Rec rec = new AdvisorPanel.Rec();
				rec.sell = true;
				rec.itemId = sell.itemId;
				rec.name = sell.name;
				rec.quantity = sell.quantity;
				rec.unitPrice = sell.price;
				rec.unitCost = sell.unitCost;
				rec.profit = sell.expectedProfit;
				rec.hasTrackedCost = sell.hasTrackedCost;
				/* For a stack with no tracked purchase this is the only
				   honest per-unit figure the card can show, since today's
				   spread needs no knowledge of what you paid. */
				final Advisor.Quote sq = quotes.get(sell.itemId);
				if (sq != null && sq.high > 0 && sq.low > 0)
				{
					rec.unitMargin = sq.high - sq.low - FlipTracker.taxPerItem(sq.high, sell.itemId);
				}
				rec.note = sell.reason;
				recommendations.add(rec);
			}
			final Set<Integer> recommendedIds = new HashSet<>();
			for (AdvisorPanel.Rec r : recommendations)
			{
				recommendedIds.add(r.itemId);
			}
			for (CapitalPlanner.Position pos : capitalPlan.positions)
			{
				if (recommendations.size() >= MAX_RECOMMENDATIONS)
				{
					break;
				}
				final AdvisorPanel.Rec rec = new AdvisorPanel.Rec();
				rec.sell = false;
				rec.itemId = pos.id;
				rec.name = pos.name;
				rec.quantity = pos.quantity;
				rec.unitPrice = pos.unitBuy;
				rec.profit = pos.expectedProfit;
				rec.capital = pos.spend;
				/* The sell price the profit above assumes. Safe to read off
				   the quote here rather than threading it through the planner:
				   the candidate's unitEdge was computed a few lines up as
				   q.high - q.low - tax(q.high) from THIS same map in THIS same
				   cycle, so q.high is exactly the exit it priced. */
				rec.exitPrice = exitPriceFor(quotes, pos.id);
				rec.note = pos.boundBy == CapitalPlanner.Bound.CASH
					? "sized to the cash you have free"
					: pos.boundBy == CapitalPlanner.Bound.GE_LIMIT
						? "capped by the 4h buy limit"
						: pos.boundBy == CapitalPlanner.Bound.DAILY_VOLUME
							? "capped by how much actually trades in a day"
							: "sized conservatively — no confirmed buy limit for this item";
				recommendations.add(rec);
				recommendedIds.add(pos.id);
			}
			/* Then the rest of Advisor's ranked buys. The plan stops at one
			   per free slot, so on its own the list ran out after a handful —
			   these are the ideas worth having queued for when a slot frees,
			   which is most of what you page through. */
			for (Advisor.Suggestion buy : suggestions)
			{
				if (recommendations.size() >= MAX_RECOMMENDATIONS)
				{
					break;
				}
				if (buy.type != Advisor.Suggestion.Type.BUY || !recommendedIds.add(buy.itemId))
				{
					continue;
				}
				final AdvisorPanel.Rec rec = new AdvisorPanel.Rec();
				rec.sell = false;
				rec.itemId = buy.itemId;
				rec.name = buy.name;
				rec.quantity = buy.quantity;
				rec.unitPrice = buy.price;
				rec.profit = buy.expectedProfit;
				/* Same identity as above, from Advisor.buildBuys' own edge. */
				rec.exitPrice = exitPriceFor(quotes, buy.itemId);
				rec.note = buy.reason;
				recommendations.add(rec);
			}

			// Green/red border on each GE offer box: every active offer starts
			// green (priced fine), then any slot Advisor.advise() flagged with
			// an ADJUST_BUY/ADJUST_SELL — genuinely drifted off the market,
			// same check the sidebar's adjust suggestions already make — flips
			// to red. Slots with no active offer are left out entirely, so the
			// overlay draws nothing over them.
			final Map<Integer, Boolean> slotStatus = new HashMap<>();
			for (Advisor.OfferView o : offers)
			{
				if (o.active)
				{
					slotStatus.put(o.slot, true);
				}
			}
			for (Advisor.Suggestion s : suggestions)
			{
				if ((s.type == Advisor.Suggestion.Type.ADJUST_BUY || s.type == Advisor.Suggestion.Type.ADJUST_SELL) && s.slot >= 0)
				{
					slotStatus.put(s.slot, false);
				}
			}
			geGridOverlay.setSlots(buildSlotViews(offers, slotStatus, tracker.getOpenBuyTotals()));

			// Whatever "sell what you hold" picked this cycle — hand it to
			// refreshOfferSeries()'s NEXT fetch (same one-cycle-lag pattern as
			// activeOfferIds above) so its suggested price can reprice through
			// TradeEngine too, not just the raw live quote.
			Integer sellCandidateId = null;
			for (Advisor.Suggestion s : suggestions)
			{
				if (s.type == Advisor.Suggestion.Type.SELL)
				{
					sellCandidateId = s.itemId;
					break;
				}
			}
			lastSellCandidateItemId = sellCandidateId;

			// Analyst Rating badge per suggestion — same rating language as pocketge.com.
			final Map<Integer, AnalystRating.Grade> ratings = new HashMap<>();
			for (Advisor.Suggestion s : suggestions)
			{
				ratings.put(s.itemId, AnalystRating.grade(quotes.get(s.itemId), averages.get(s.itemId)));
			}
			// Prefer a fresh BUY for the overlay (matches the panel's Top
			// Suggestion card defaulting to index 0 of this same ranked
			// list); fall back to whatever else is there (an adjust nudge,
			// say) if there's no buy candidate right now.
			final Advisor.Suggestion topSuggestion = suggestions.stream()
				.filter(s -> s.type == Advisor.Suggestion.Type.BUY)
				.findFirst()
				.orElse(suggestions.isEmpty() ? null : suggestions.get(0));
			lastTopRecommendation = topSuggestion;

			// Bank/inventory highlight: keyed by item id so BankHighlightOverlay
			// can look up the right suggestion (and thus color/profit) for
			// whatever item slot it's currently drawing over. Advisor.advise()
			// only ever names ONE best SELL candidate (it's picking what to
			// actively recommend, not auditing every stack) — that left every
			// other bank item you're clearly merchanting with no border at
			// all. This fills in the rest: any held stack that is both worth
			// enough after tax to matter (the same 50k bar advise() uses for
			// its top pick) and priced to sell right now earns a border of
			// its own. Advisor.advise()'s own suggestions are added AFTER
			// and win any collision, since they carry a real live-repriced
			// target and reason text instead of this coarser synthetic one.
			// The map may end up holding BUY entries from advise(); the
			// overlay ignores those — see BankHighlightOverlay.
			final Map<Integer, Advisor.Suggestion> suggestionsByItem = new HashMap<>();
			for (Map.Entry<Integer, Integer> h : holdings.entrySet())
			{
				final int id = h.getKey();
				final int qty = h.getValue();
				if (qty <= 0 || blockedIds.contains(id) || activeOfferIds.contains(id))
				{
					continue;
				}
				final Advisor.Quote q = quotes.get(id);
				if (q == null)
				{
					continue;
				}
				final ItemComposition idComp = itemManager.getItemComposition(id);
				if (idComp == null)
				{
					continue; // can't resolve a name for it — skip rather than crash
				}
				final String name = idComp.getName();
				// Holding a lot of something isn't itself a reason to sell it —
				// almost everything in a large bank clears any reasonable value
				// bar regardless of whether now is actually a good time. Require
				// the live price to actually look sell-worthy (same Analyst
				// Rating signal the "buy more" side already uses) before a big
				// stack earns a SELL border, not just its raw size.
				final AnalystRating.Grade grade = AnalystRating.grade(q, averages.get(id));
				if (q.high > 0 && (grade.label == AnalystRating.Label.SELL || grade.label == AnalystRating.Label.STRONG_SELL))
				{
					final long net = q.high - FlipTracker.taxPerItem(q.high, id);
					final long value = net * qty;
					if (value >= 50_000)
					{
						final Advisor.Suggestion sellSuggestion = new Advisor.Suggestion(Advisor.Suggestion.Type.SELL, id, name, q.high, qty, value,
							"you hold " + qty + " — worth ~" + value + " gp after tax, and the price looks good to sell right now (" + grade.label.text + ")");
						sellSuggestion.hasTrackedCost = false; // this is the stack's full value, not a tracked gain
						suggestionsByItem.put(id, sellSuggestion);
						continue;
					}
				}
			}
			for (Advisor.Suggestion s : suggestions)
			{
				/* A held stack can rank as both a SELL and a BUY — buildBuys
				   never excludes what you already own. advise() emits the
				   SELL first, so a plain put() let the BUY overwrite it and
				   the bank slot drew "good time to buy more" while the card
				   said sell. Selling what you hold is the more specific
				   advice, so it wins. */
				final Advisor.Suggestion existing = suggestionsByItem.get(s.itemId);
				if (existing != null && existing.type == Advisor.Suggestion.Type.SELL
					&& s.type == Advisor.Suggestion.Type.BUY)
				{
					continue;
				}
				suggestionsByItem.put(s.itemId, s);
			}
			bankOverlay.setSuggestions(suggestionsByItem);
			/* The chip offers whatever we would have you buy next, so opening
			   the search on an empty slot puts it one click away. */
			AdvisorPanel.Rec topBuy = null;
			for (AdvisorPanel.Rec r : recommendations)
			{
				if (!r.sell)
				{
					topBuy = r;
					break;
				}
			}
			gePriceOverlay.setSearchSuggestion(topBuy != null ? topBuy.itemId : 0,
				topBuy != null ? topBuy.name : null);

			// Find Opportunities — the live categories pocketge.com's sidebar
			// shows that this cycle's already-fetched data can afford (see
			// FinderEngine's doc comment for why Reliable 14D Margins isn't
			// here). At 5D Highs/Lows reuses whatever refreshDayExtremes()
			// already fetched for favorites + the bounded top-volume pool —
			// no extra network call of its own.
			final boolean membersWorldForFinder = client.getWorldType().contains(WorldType.MEMBERS);
			final List<FinderPanel.Row> highVolRows = toFinderRows(
				FinderEngine.marginRows(quotes, averages, volumes, false), FinderRowKind.MARGIN, membersWorldForFinder);
			final List<FinderPanel.Row> lowVolRows = toFinderRows(
				FinderEngine.marginRows(quotes, averages, volumes, true), FinderRowKind.MARGIN, membersWorldForFinder);
			final List<FinderPanel.Row> loserRows = toFinderRows(
				FinderEngine.loserRows(quotes, averages, volumes), FinderRowKind.MOVER, membersWorldForFinder);
			/* Passed straight through — FinderEngine reads the same
			   PriceExtremes the badges do, so there is no copy to drift. */
			final List<FinderPanel.Row> at5dHighRows = toFinderRows(
				FinderEngine.extremeHighRows(quotes, dayExtremes, volumes), FinderRowKind.HIGH_5D, membersWorldForFinder);
			final List<FinderPanel.Row> at5dLowRows = toFinderRows(
				FinderEngine.extremeLowRows(quotes, dayExtremes, volumes), FinderRowKind.LOW_5D, membersWorldForFinder);

			// 8-square sidebar status strip — the same green/red read as the
			// GE-box overlay above, plus the two states that overlay doesn't
			// need to care about (empty, and bought/sold/cancelled-with-
			// something-to-collect) since this one has to describe every
			// slot, not just active ones.
			final GrandExchangeOffer[] rawSlots = client.getGrandExchangeOffers();
			final GeSlotsPanel.SlotInfo[] slotInfos = new GeSlotsPanel.SlotInfo[8];
			for (int i = 0; i < slotInfos.length; i++)
			{
				final GeSlotsPanel.SlotInfo info = new GeSlotsPanel.SlotInfo();
				final GrandExchangeOffer o = (rawSlots != null && i < rawSlots.length) ? rawSlots[i] : null;
				if (o != null && o.getItemId() > 0)
				{
					final GrandExchangeOfferState st = o.getState();
					final ItemComposition slotComp = itemManager.getItemComposition(o.getItemId());
					info.itemName = slotComp != null ? slotComp.getName() : null;
					info.itemId = itemManager.canonicalize(o.getItemId());
					info.quantityFilled = o.getQuantitySold();
					info.quantityTotal = o.getTotalQuantity();
					info.buy = st == GrandExchangeOfferState.BUYING || st == GrandExchangeOfferState.BOUGHT
						|| st == GrandExchangeOfferState.CANCELLED_BUY;
					info.adviceSkipped = adviceSkippedSlots.contains(i);
					if (st == GrandExchangeOfferState.BUYING || st == GrandExchangeOfferState.SELLING)
					{
						info.state = Boolean.FALSE.equals(slotStatus.get(i))
							? GeSlotsPanel.SlotState.ACTIVE_ADJUST : GeSlotsPanel.SlotState.ACTIVE_OK;
					}
					else if (st == GrandExchangeOfferState.BOUGHT || st == GrandExchangeOfferState.SOLD
						|| st == GrandExchangeOfferState.CANCELLED_BUY || st == GrandExchangeOfferState.CANCELLED_SELL)
					{
						info.state = GeSlotsPanel.SlotState.READY_COLLECT;
					}
				}
				slotInfos[i] = info;
			}

			final Set<Integer> favIds = favoriteIdSet();
			final AdvisorPanel.Settings currentSettings = buildSettings();
			SwingUtilities.invokeLater(() ->
			{
				// No routine status text here — cash/interval were just
				// clutter above the always-visible top card; the settings
				// popup (gear icon) still shows the re-check interval.
				mainPanel.setAdvisorStatus("");
				mainPanel.updateSuggestions(suggestions, ratings, favIds, currentSettings);
				mainPanel.updateRecommendations(recommendations);
				mainPanel.updateGeSlots(slotInfos);
				/* Same 40s window the settings popup and the chart-tab reuse
				   already treat as "a page is live" \u2014 one definition of
				   linked, three places that show it. */
				mainPanel.setWebsiteLinked(currentSettings.bridgeClientAgeSec >= 0
					&& currentSettings.bridgeClientAgeSec <= 40);
				mainPanel.updateFinder(highVolRows, lowVolRows, loserRows, at5dHighRows, at5dLowRows);
			});
		});
		refreshStatsAndFavorites();
	}

	/** Which FinderEngine.Row field toFinderRows should format and how —
	 *  MARGIN/MOVER read src.margin/src.pct as their own values, HIGH_5D/
	 *  LOW_5D both read src.pct but as "how close to the extreme" instead
	 *  (0 = sitting exactly on it), so they need their own badge text. */
	private enum FinderRowKind { MARGIN, MOVER, HIGH_5D, LOW_5D }

	/** FinderEngine.Row (id + raw margin/pct, no display concerns) ->
	 *  FinderPanel.Row (resolved name, formatted metric text/color, capped
	 *  to the top FINDER_LIST_CAP) — mirrors the F2P/members filter
	 *  recomputeAdvice() already applies to suggestion candidates. */
	private List<FinderPanel.Row> toFinderRows(List<FinderEngine.Row> in, FinderRowKind kind, boolean membersWorld)
	{
		final List<FinderPanel.Row> out = new ArrayList<>(FINDER_LIST_CAP);
		for (FinderEngine.Row src : in)
		{
			if (out.size() >= FINDER_LIST_CAP)
			{
				break;
			}
			if (!membersWorld)
			{
				final ItemComposition comp = itemManager.getItemComposition(src.id);
				if (comp != null && comp.isMembers())
				{
					continue;
				}
			}
			final ItemComposition comp = itemManager.getItemComposition(src.id);
			final String name = comp != null ? comp.getName() : null;
			if (name == null)
			{
				continue;
			}
			final FinderPanel.Row r = new FinderPanel.Row();
			r.id = src.id;
			r.name = name;
			r.vol = src.vol;
			switch (kind)
			{
				case MOVER:
					r.metricText = String.format("%.1f%%", src.pct);
					r.metricColor = FINDER_NEGATIVE;
					break;
				case HIGH_5D:
					r.metricText = "▲ 5D HIGH";
					r.metricColor = FINDER_HIGH5D;
					break;
				case LOW_5D:
					r.metricText = "▼ 5D LOW";
					r.metricColor = FINDER_LOW5D;
					break;
				case MARGIN:
				default:
					r.metricText = "+" + QuantityFormatter.quantityToStackSize(src.margin) + " gp";
					r.metricColor = FINDER_POSITIVE;
					break;
			}
			out.add(r);
		}
		return out;
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

	/** Inventory coins + whatever coins were sitting in the bank on the last
	 *  snapshot. Most players keep their real wealth banked, not carried —
	 *  using inventory coins alone as "cash" left BUY suggestions (and
	 *  portfolio value) starved for anyone who doesn't walk around with
	 *  their whole stack loose. */
	/** Every gp you could spend right now, wherever it is sitting: loose
	 *  coins, coins in the bank, and platinum tokens in either — tokens
	 *  redeem 1:1000 with no spread and no tax, so they are cash by any
	 *  definition the advisor cares about. This is the number every BUY
	 *  recommendation is sized against, so anything missing here shows up
	 *  directly as under-sized suggestions. */
	private long totalCash()
	{
		return countInventory(COINS_ID)
			+ countInventory(PortfolioValuer.PLATINUM_TOKEN_ID) * PortfolioValuer.PLATINUM_VALUE
			+ lastBankCoins
			+ lastBankPlatinum * PortfolioValuer.PLATINUM_VALUE;
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



	/** Copies the "you're holding this right now" numbers onto a watchlist
	 *  row. All the actual reasoning — and every way the two quantities
	 *  involved can disagree — lives in {@link PortfolioValuer#heldPosition},
	 *  where it is unit-testable without a running client. */
	private static void fillHeldPosition(FavoritesPanel.Row row, Advisor.Quote q,
		Map<Integer, Integer> holdings, Map<Integer, long[]> openBuys)
	{
		final PortfolioValuer.HeldPosition p = PortfolioValuer.heldPosition(
			row.id, holdings.getOrDefault(row.id, 0), q, openBuys.get(row.id));
		row.heldQty = p.heldQty;
		row.sellValue = p.sellValue;
		row.hasCostBasis = p.hasCostBasis;
		row.pricedQty = p.pricedQty;
		row.heldProfit = p.heldProfit;
	}

	/**
	 * The bank snapshot as a priced, value-ordered list for the website.
	 *
	 * Built on demand from lastBank rather than cached, so it always reflects
	 * the newest quotes; the bank itself only changes when the player opens
	 * it. Cash is deliberately absent — it goes over as its own `cash` field,
	 * and mixing it in would make "biggest stack" meaningless for anyone
	 * holding platinum.
	 *
	 * Called from refreshStatsAndFavorites on the client thread, and the
	 * result cached in lastBankStacks for the bridge to serve — see that
	 * field for why this must not run per request.
	 */
	private List<LocalBridgeServer.BankStack> bankStacks()
	{
		final Map<Integer, Advisor.Quote> quotes = lastQuotes;
		final List<LocalBridgeServer.BankStack> out = new ArrayList<>();
		for (Map.Entry<Integer, Integer> e : new HashMap<>(lastBank).entrySet())
		{
			final int id = e.getKey();
			final int qty = e.getValue() != null ? e.getValue() : 0;
			if (qty <= 0 || PortfolioValuer.isCash(id))
			{
				continue;
			}
			final Advisor.Quote q = quotes.get(id);
			final long value = (q != null && q.low > 0) ? (long) qty * q.low : 0;
			String name = "Item " + id;
			try
			{
				final ItemComposition comp = itemManager.getItemComposition(id);
				if (comp != null && comp.getName() != null)
				{
					name = comp.getName();
				}
			}
			catch (RuntimeException ignore)
			{
				/* getItemComposition has thrown for individual ids before (see
				   the bank highlight and favorites-refresh fixes) — one bad id
				   must not take the whole bridge response down with it. */
			}
			out.add(new LocalBridgeServer.BankStack(id, name, qty, value));
		}
		out.sort((a, b) -> Long.compare(b.value, a.value));
		return out;
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
				if (it.getId() <= 0 || PortfolioValuer.isCash(it.getId()))
				{
					continue;
				}
				final int canon = itemManager.canonicalize(it.getId());
				h.merge(canon, it.getQuantity(), Integer::sum);
			}
		}
		/* Cash is never a holding. Beyond the double counting in portfolio
		   value, leaving platinum tokens in here made them eligible for a
		   SELL suggestion — and "sell your platinum tokens" is not a flip,
		   it is just breaking a note. */
		h.remove(COINS_ID);
		h.remove(PortfolioValuer.PLATINUM_TOKEN_ID);
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

	/** Slots you could open a NEW offer in right now.
	 *
	 *  Deliberately does NOT reuse currentOffers(): that filters to
	 *  BUYING/SELLING because those are the only ones needing price advice,
	 *  but a BOUGHT/SOLD/CANCELLED offer still physically holds its slot
	 *  until you collect it. Planning against that list would hand a player
	 *  with three uncollected buys a three-slot plan they can't act on —
	 *  and "collect your finished offers" is a common enough real mistake
	 *  that silently over-reporting free slots would be actively harmful.
	 *
	 *  Only the first `total` indices are counted: on a free world an offer
	 *  parked in a members-only slot is frozen and unusable, and must not
	 *  phantom-block one of the three slots that ARE usable. */
	/** Panel updates must happen on the EDT; game state events arrive on the
	 *  client thread. */
	private void setPanelLoggedIn(boolean loggedIn)
	{
		if (mainPanel == null)
		{
			return;
		}
		SwingUtilities.invokeLater(() -> mainPanel.setLoggedIn(loggedIn));
	}

	private int freeGeSlots()
	{
		final int total = client.getWorldType().contains(WorldType.MEMBERS) ? MEMBERS_GE_SLOTS : F2P_GE_SLOTS;
		final GrandExchangeOffer[] raw = client.getGrandExchangeOffers();
		if (raw == null)
		{
			return total;
		}
		int used = 0;
		for (int i = 0; i < Math.min(total, raw.length); i++)
		{
			final GrandExchangeOffer o = raw[i];
			if (o != null && o.getItemId() > 0 && o.getState() != GrandExchangeOfferState.EMPTY)
			{
				used++;
			}
		}
		return Math.max(0, total - used);
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
				/* Nothing to type into yet. Say so rather than swallowing the
				   click: a click that silently does nothing reads as a broken
				   feature, and this is a two-step flow (open the price box,
				   then fill it) that is not obvious the first time. The price
				   is on the clipboard either way. */
				announceCannotFill();
				return;
			}
			if (autoFillInFlight)
			{
				return; // an auto-fill is already driving the same input box
			}
			autoFillInFlight = true;
			try
			{
				client.setVarcStrValue(VarClientID.MESLAYERINPUT, priceStr);
				client.runScript(ScriptID.CHAT_TEXT_INPUT_REBUILD, "");
				announcePriceFilled(price);
			}
			finally
			{
				autoFillInFlight = false;
			}
		});
	}

	/** Same live-fill mechanism as fillGePrice, just confirming a
	 *  "...how many.../...quantity..." prompt instead of a "...price..."
	 *  one — the GE offer flow asks for quantity before price, so this
	 *  covers the earlier step. No clipboard fallback here (unlike price,
	 *  quantity isn't useful to have sitting on the clipboard on its own);
	 *  fillGePrice's own clipboard copy already covers "paste it somewhere
	 *  if live-fill didn't apply". */
	private void fillGeQuantity(long qty)
	{
		final String qtyStr = String.valueOf(qty);
		clientThread.invokeLater(() ->
		{
			final Widget offerSetup = client.getWidget(InterfaceID.GeOffers.SETUP);
			if (offerSetup == null || offerSetup.isHidden())
			{
				return;
			}
			final Widget mesText = client.getWidget(InterfaceID.Chatbox.MES_TEXT);
			final Widget mesText2 = client.getWidget(InterfaceID.Chatbox.MES_TEXT2);
			final String prompt = ((mesText != null ? mesText.getText() : "")
				+ " " + (mesText2 != null ? mesText2.getText() : "")).toLowerCase();
			if (!prompt.contains("how many") && !prompt.contains("quantity"))
			{
				return; // no quantity-entry prompt currently open
			}
			if (autoFillInFlight)
			{
				return;
			}
			autoFillInFlight = true;
			try
			{
				client.setVarcStrValue(VarClientID.MESLAYERINPUT, qtyStr);
				client.runScript(ScriptID.CHAT_TEXT_INPUT_REBUILD, "");
			}
			finally
			{
				autoFillInFlight = false;
			}
		});
	}

	/** Auto-fills the GE price prompt the instant it opens — see
	 *  onScriptPostFired's CHAT_PROMPT_INIT branch. Same "confirm the
	 *  actual on-screen prompt text first" safety check as fillGePrice,
	 *  just triggered by the prompt appearing rather than requiring the
	 *  panel's ⧉ button to be clicked at exactly the right moment (which in
	 *  practice meant it usually wasn't — by the time attention shifted
	 *  from the game back to the sidebar, the prompt had often already been
	 *  confirmed or dismissed). CHAT_PROMPT_INIT fires for lots of unrelated
	 *  chatbox prompts throughout the game, not just this one — the offer
	 *  screen + "price" text guards below are what keep this from firing
	 *  anywhere else. Already running on the client thread (that's where
	 *  ScriptPostFired delivers), so no clientThread.invokeLater needed. */

	/**
	 * GE slots (0-7) you have right-clicked and told the plugin to stop
	 * pricing for you — "I am selling this one high on purpose."
	 *
	 * Per SLOT rather than per item: the same item can sit in two slots for
	 * two different reasons, and the thing you are opting out of is this
	 * offer, not this item forever (that is what Hold and the block list are
	 * for). Cleared the moment the slot empties, so it can never quietly
	 * silence advice on a completely unrelated future offer.
	 */
	private final Set<Integer> adviceSkippedSlots = java.util.concurrent.ConcurrentHashMap.newKeySet();

	/** Guards the auto-fill against re-entering itself. See
	 *  autoFillGePricePrompt — without this the client hard-crashes. */
	private boolean autoFillInFlight = false;

	/**
	 * Types the recommended item into the GE "What would you like to buy?"
	 * search, from a click on the overlay chip.
	 *
	 * MESLAYERINPUT is genuinely the search field, not just the price box:
	 * RuneLite's own GrandExchangePlugin reads that same var inside its
	 * GE_ITEM_SEARCH handler to underline fuzzy matches. So this is the
	 * mechanism already proven by fillGePrice rather than a new one — which
	 * matters, because the alternative (runScript on GE_ITEM_SEARCH, whose
	 * three integer arguments are undocumented) is exactly the shape of call
	 * that crashed the client here once already.
	 *
	 * Deferred and guarded for that same reason; see autoFillGePricePrompt.
	 */
	private void fillGeSearch(String itemName)
	{
		clientThread.invokeLater(() ->
		{
			if (autoFillInFlight)
			{
				return;
			}
			final Widget mes = client.getWidget(InterfaceID.Chatbox.MES_TEXT);
			final String prompt = mes != null && mes.getText() != null ? mes.getText().toLowerCase() : "";
			if (!prompt.contains("what would you like to"))
			{
				return; // the search closed while we waited a tick
			}
			autoFillInFlight = true;
			try
			{
				client.setVarcStrValue(VarClientID.MESLAYERINPUT, itemName);
				client.runScript(ScriptID.CHAT_TEXT_INPUT_REBUILD, "");
			}
			finally
			{
				autoFillInFlight = false;
			}
		});
	}

	/**
	 * Fills the price box the moment the "Set a price" prompt opens.
	 *
	 * This is called from onScriptPostFired, i.e. from INSIDE the script
	 * VM's own callback, and it used to call client.runScript() straight
	 * from there. That re-enters the VM: CHAT_TEXT_INPUT_REBUILD fires
	 * script events of its own, those re-enter onScriptPostFired, which
	 * calls this again, which runs the script again — unbounded recursion
	 * ending in a StackOverflowError and a dead client. Reported as "click
	 * enter on 1870 and boom crash", reproducible every time, because the
	 * "price" guard below stays TRUE throughout the recursion and so stops
	 * nothing.
	 *
	 * Two fixes, both needed. The work is deferred onto the next client tick
	 * so it runs outside the script callback rather than nested in it, and a
	 * re-entrancy flag plus a per-prompt latch make a second pass a no-op
	 * even if the deferral ever lands inside one.
	 */
	private void autoFillGePricePrompt()
	{
		if (autoFillInFlight)
		{
			return;
		}
		final Widget offerSetup = client.getWidget(InterfaceID.GeOffers.SETUP);
		if (offerSetup == null || offerSetup.isHidden())
		{
			return;
		}
		final Integer itemId = geContextItemId;
		if (itemId == null || geContextPrice <= 0)
		{
			return;
		}
		final Widget mesText = client.getWidget(InterfaceID.Chatbox.MES_TEXT);
		final Widget mesText2 = client.getWidget(InterfaceID.Chatbox.MES_TEXT2);
		final String prompt = ((mesText != null ? mesText.getText() : "")
			+ " " + (mesText2 != null ? mesText2.getText() : "")).toLowerCase();
		if (!prompt.contains("price"))
		{
			return;
		}
		/* No "have I already filled this?" latch here, deliberately. There
		   was one, keyed on the prompt text plus the price, and it was a bug:
		   every price prompt says "Set a price for each item:", so the second
		   time you priced the same item at the same suggestion the stamp
		   matched and the fill was silently skipped. It filled once and then
		   appeared broken forever.
		   Nothing is needed in its place. This method is reached only from
		   CHAT_PROMPT_INIT, which fires exactly once per opening of the box,
		   and re-entrancy - the thing that actually crashed the client - is
		   handled by the deferral plus autoFillInFlight below. */
		final long price = geContextPrice;
		/* Next tick, NOT now — see the method comment. invokeLater runs on
		   the client thread but outside the script callback we are currently
		   nested in, which is the whole point. */
		clientThread.invokeLater(() ->
		{
			if (autoFillInFlight)
			{
				return;
			}
			autoFillInFlight = true;
			try
			{
				final Widget stillOpen = client.getWidget(InterfaceID.GeOffers.SETUP);
				if (stillOpen == null || stillOpen.isHidden())
				{
					return; // the screen closed while we waited a tick
				}
				client.setVarcStrValue(VarClientID.MESLAYERINPUT, String.valueOf(price));
				client.runScript(ScriptID.CHAT_TEXT_INPUT_REBUILD, "");
				announcePriceFilled(price);
			}
			finally
			{
				autoFillInFlight = false;
			}
		});
	}

	/** Prints a one-line chat message confirming the price was filled — the
	 *  same visible-in-chat feedback other GE-assist plugins give (a
	 *  "Copilot price: N gp" style line), which PocketGE's own fill was
	 *  otherwise entirely silent about: the price box just changed with no
	 *  indication PocketGE did it. Already on the client thread whenever
	 *  this is called (both callers invoke it right after the live-fill
	 *  itself, inside their own clientThread.invokeLater). */
	/** Why a click on the price panel did nothing. The panel itself names
	 *  the missing step and the offer screen rings the button in gold, but
	 *  neither helps if you have already clicked and are waiting for
	 *  something to happen. */
	private void announceCannotFill()
	{
		final String message = new ChatMessageBuilder()
			.append(Color.decode("#E5C158"), "PocketGE")
			.append(Color.WHITE, " open the price box first (the gold-ringed button), then click again. ")
			.append(Color.decode("#8A8274"), "Price copied to clipboard.")
			.build();
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.runeLiteFormattedMessage(message)
			.build());
	}

	private void announcePriceFilled(long price)
	{
		final String message = new ChatMessageBuilder()
			.append(Color.decode("#E5C158"), "PocketGE")
			.append(Color.WHITE, " filled the price: ")
			.append(Color.decode("#1FB85C"), QuantityFormatter.quantityToStackSize(price) + " gp")
			.build();
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.runeLiteFormattedMessage(message)
			.build());
	}

	private void syncBridge()
	{
		if (bridge == null)
		{
			return;
		}
		bridge.stop();
		if (bridgeRefreshTask != null)
		{
			bridgeRefreshTask.cancel(false);
			bridgeRefreshTask = null;
		}
		if (config.localBridge())
		{
			try
			{
				bridge.start(config.bridgePort(), () -> {
					List<FavoriteLists.FavoriteList> lists = loadFavoriteLists();
					FavoriteLists.FavoriteList active = activeFavoriteList(lists);
					final Map<String, Object> m = LocalBridgeServer.payload(
						tracker.getSessionProfit(), tracker.getLifetimeProfit(), tracker.getFlips(), tracker.getFills(),
						lastPortfolioValue, bankSeen, bankSeenAt, lastCash, lastBankStacks,
						lists, active != null ? active.id : null, lastTopRecommendation);
					/* Left in the payload rather than cleared once served: the
					   page may miss a poll or be reloaded, and it dedupes on
					   seq anyway. Clearing here would make a dropped poll lose
					   the click entirely. */
					m.put("navRequest", pendingNav);
					return m;
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

						@Override
						public void reorder(String listId, List<Integer> itemIds)
						{
							reorderFavoritesFromBridge(listId, itemIds);
						}
					});
				log.info("PocketGE local bridge listening on 127.0.0.1:{}", config.bridgePort());
				/* Portfolio value/favorites otherwise only recompute on reactive
				   events (a flip, a bank change, a favorite edit) or on the
				   Advisor's price-fetch cycle — if the Advisor is off, the
				   bridge would otherwise go stale and just sit there looking
				   "connected" but never updating. Keep it independently fresh
				   at roughly the same cadence the website polls at. */
				bridgeRefreshTask = executor.scheduleWithFixedDelay(this::refreshStatsAndFavorites, 15, 15, TimeUnit.SECONDS);
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

	/** Tracks each sender's latest line so the "Search PocketGE for X"
	 *  right-click option (see onMenuEntryAdded) has something to parse —
	 *  it fires off the game's own "Report" entry, which only carries the
	 *  sender's name, not their message. */
	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		final ChatMessageType type = event.getType();
		if (type != ChatMessageType.PUBLICCHAT && type != ChatMessageType.PRIVATECHAT
			&& type != ChatMessageType.FRIENDSCHAT && type != ChatMessageType.CLAN_CHAT)
		{
			return;
		}
		final String sender = Text.removeTags(event.getName());
		if (sender == null || sender.isEmpty())
		{
			return;
		}
		lastChatMessageBySender.put(sender, event.getMessage());
		log.debug("PocketGE chat cache: [{}] '{}' -> '{}'", type, sender, event.getMessage());
	}

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
	{
		final GrandExchangeOffer offer = event.getOffer();
		final GrandExchangeOfferState state = offer.getState();
		final boolean emptied = state == GrandExchangeOfferState.EMPTY;
		if (emptied)
		{
			/* The offer this opt-out referred to is gone. Leaving the flag
			   set would silence advice on whatever you put in the slot
			   next, which you never asked for. */
			adviceSkippedSlots.remove(event.getSlot());
		}
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
		if (c == null)
		{
			return;
		}
		/* The inventory (and the coins in it) populate a tick or two AFTER
		   the LOGGED_IN event, and this used to early-return for every
		   container except the bank — so that arrival refreshed nothing and
		   the panel sat on its empty login result until the next scheduled
		   advisor tick, up to five minutes later. Recompute on inventory too;
		   only the bank needs the snapshot below. */
		if (client.getItemContainer(InventoryID.BANK) != c)
		{
			if (config.advisor() && !lastQuotes.isEmpty())
			{
				recomputeAdvice();
			}
			else
			{
				refreshStatsAndFavorites();
			}
			return;
		}
		/* Snapshot the bank whenever it's open so "sell what you hold"
		   suggestions know your stacks even after the bank closes. */
		bankSeen = true;
		bankSeenAt = System.currentTimeMillis();
		lastBank.clear();
		long bankCoins = 0;
		long bankPlatinum = 0;
		for (Item it : c.getItems())
		{
			if (it.getId() <= 0)
			{
				continue;
			}
			// Cash is tallied per denomination and kept out of lastBank —
			// everything left in there gets valued from a live quote, which
			// coins don't need and platinum shouldn't have (it redeems at an
			// exact 1:1000, and its own market price is beside the point).
			if (it.getId() == COINS_ID)
			{
				bankCoins += it.getQuantity();
				continue;
			}
			if (it.getId() == PortfolioValuer.PLATINUM_TOKEN_ID)
			{
				bankPlatinum += it.getQuantity();
				continue;
			}
			final int canon = itemManager.canonicalize(it.getId());
			lastBank.merge(canon, it.getQuantity(), Integer::sum);
		}
		lastBankCoins = bankCoins;
		lastBankPlatinum = bankPlatinum;
		// New/changed stacks can change what's worth selling — reflect that
		// the moment the bank updates instead of waiting for the next
		// scheduled price poll.
		if (config.advisor() && !lastQuotes.isEmpty())
		{
			recomputeAdvice();
		}
		// Portfolio value needs to pick up the bank the moment it's seen too —
		// recomputeAdvice() above only runs with the Advisor on, but the
		// bridge/portfolio total is a separate feature from the Advisor.
		refreshStatsAndFavorites();
	}

	@Subscribe
	public void onGameStateChanged(net.runelite.api.events.GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			skipped.clear(); // session skips reset on logout
			/* Same reasoning: a move you were told about before logging out is
			   news again when you come back to it. */
			lastAlertMillis.clear();
			setPanelLoggedIn(false);
		}
		else if (event.getGameState() == GameState.LOGGED_IN)
		{
			setPanelLoggedIn(true);
			/* syncAdvisor()'s one "immediate" refreshPrices() tick (0 initial
			   delay) almost always lands before login finishes — cash,
			   holdings, and offers are all still empty at that point, so
			   recomputeAdvice() has nothing to suggest and that empty result
			   just sits in the panel until the NEXT scheduled tick, up to the
			   full re-check interval (5 min default) later. Recompute right
			   away now that real state exists; if prices haven't come back
			   yet either (a slow network, or the plugin was only just
			   enabled), kick an immediate fetch instead of waiting on the
			   schedule too. */
			if (lastQuotes.isEmpty())
			{
				executor.submit(this::refreshPrices);
			}
			else
			{
				recomputeAdvice();
			}
		}
	}

	/** Fires whenever the GE offer setup screen (re)builds — same hook
	 *  RuneLite's own bundled GE plugin uses for its "actively traded price"
	 *  hint. Lets the panel show a price for whatever item the player
	 *  actually has open right now, not just the advisor's own top pick. */
	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() == ScriptID.CHAT_PROMPT_INIT)
		{
			// Fires the instant the "How many..."/"Set a price..." chatbox
			// prompt is built — auto-filling the price right here (rather
			// than requiring the panel's ⧉ button to be clicked at exactly
			// the right moment) is what actually made it "not suggesting a
			// price" in practice: by the time someone switched their
			// attention from the game to the sidebar and back, the prompt
			// had usually already been confirmed or was about to be.
			autoFillGePricePrompt();
			return;
		}
		if (event.getScriptId() != ScriptID.GE_OFFERS_SETUP_BUILD)
		{
			return;
		}
		final int itemId = client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH);
		final Advisor.Quote q = itemId > 0 ? lastQuotes.get(itemId) : null;
		final boolean isBuy = client.getVarbitValue(VarbitID.GE_NEWOFFER_TYPE) == 0;
		long price = q == null ? 0 : (isBuy ? q.low : q.high);
		final long wikiPrice = price; // the raw live print, before any repricing
		// Reprice through TradeEngine when we have series data for this item,
		// same target pocketge.com would show — same pattern as
		// ADJUST_BUY/ADJUST_SELL and the SELL suggestion (see Advisor.advise).
		if (q != null && price > 0)
		{
			final TradeEngine.Series series = lastOfferSeries.get(itemId);
			if (series != null)
			{
				final TradeEngine.Result engine = TradeEngine.compute(q.low, q.high, q.lowTime, q.highTime, series, itemId);
				if (engine != null && engine.viable)
				{
					/* Clamped to the live book. You have already committed to
					   this side of this item by opening the offer screen, so a
					   sell must never be quoted under the standing bid and a
					   buy never over the standing ask — see
					   TradeEngine.sellTarget for the Yew logs case that made
					   this obvious. */
					price = isBuy
						? TradeEngine.buyTarget(engine.buy, q.low)
						: TradeEngine.sellTarget(engine.sell, q.high);
				}
			}
		}
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
		/* Same number the sidebar shows, drawn on the screen you're actually
		   looking at — see GeOfferPriceOverlay for why that round trip
		   mattered. Margin is only meaningful when both sides are known. */
		final long margin = (q != null && q.high > q.low)
			? q.high - q.low - FlipTracker.taxPerItem(q.high, itemId) : 0;
		gePriceOverlay.setContext(geContextName, isBuy, price, wikiPrice, margin);
		pushGeContext();
	}

	private void clearGeContext()
	{
		if (geContextItemId == null)
		{
			return;
		}
		geContextItemId = null;
		gePriceOverlay.clear();
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

	/**
	 * Drops the offer-screen takeover the moment that screen goes away.
	 *
	 * This check used to live only inside the advisor's refresh cycle, which
	 * runs on the re-check interval \u2014 so closing the Grand Exchange left the
	 * sidebar showing "YOUR OFFER", and refusing to hand the box to anything
	 * you clicked, for up to a full interval afterwards. Fifteen seconds of a
	 * panel insisting on an offer screen that is not on screen.
	 *
	 * A game tick is 600ms and this is one widget lookup, guarded on there
	 * being a context at all, so in the overwhelmingly common case (no offer
	 * screen open) it costs a null check.
	 */
	@Subscribe
	public void onGameTick(GameTick event)
	{
		if (geContextItemId == null)
		{
			return;
		}
		final Widget offerSetup = client.getWidget(InterfaceID.GeOffers.SETUP);
		if (offerSetup == null || offerSetup.isHidden())
		{
			clearGeContext();
		}
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		if ("Examine".equals(event.getOption()) && event.getItemId() > 0)
		{
			addBankFavoriteEntry(event);
		}
		else if ("Report".equals(event.getOption()))
		{
			addChatSearchEntry(event);
		}
		else if ("Abort offer".equals(event.getOption()))
		{
			addSkipAdviceEntry(event);
		}
	}

	/**
	 * Adds "Stop PocketGE pricing this offer" to a Grand Exchange slot's
	 * right-click menu.
	 *
	 * For when you are deliberately listing above what the advisor thinks
	 * the market will bear. Without it the slot just sits there red and the
	 * sidebar keeps telling you to reprice something you priced on purpose,
	 * which trains you to ignore the one signal that is supposed to mean
	 * "act now".
	 *
	 * Piggybacks on "Abort offer", which the game puts on an active offer
	 * exactly once — the same trick addBankFavoriteEntry plays with
	 * "Examine" — so the entry is injected once per hover rather than once
	 * per option already there.
	 */
	private void addSkipAdviceEntry(MenuEntryAdded event)
	{
		final int slot = slotOfWidget(event.getActionParam1());
		if (slot < 0)
		{
			return;
		}
		final boolean skipped = adviceSkippedSlots.contains(slot);
		client.createMenuEntry(-1)
			.setOption(skipped ? "Resume PocketGE pricing" : "Stop PocketGE pricing this offer")
			.setTarget(event.getTarget())
			.setType(MenuAction.RUNELITE)
			.onClick(e ->
			{
				if (skipped)
				{
					adviceSkippedSlots.remove(slot);
				}
				else
				{
					adviceSkippedSlots.add(slot);
				}
				recomputeAdvice();
			});
	}

	/** True while the Grand Exchange offer screen (the 8-slot one, or a
	 *  set-up-offer screen opened from it) is on screen. Gates the graph
	 *  entry so it never appears on an ordinary inventory right-click. */
	private boolean geWindowOpen()
	{
		final Widget offers = client.getWidget(InterfaceID.GeOffers.UNIVERSE);
		if (offers != null && !offers.isHidden())
		{
			return true;
		}
		final Widget setup = client.getWidget(InterfaceID.GeOffers.SETUP);
		return setup != null && !setup.isHidden();
	}

	/** Which GE slot a right-clicked widget belongs to, or -1.
	 *
	 *  Walks up the parent chain rather than comparing the clicked id
	 *  directly: the menu fires on whichever child of the offer box happened
	 *  to be under the cursor, and that is not the box itself. */
	private int slotOfWidget(int componentId)
	{
		Widget w = client.getWidget(componentId);
		for (int depth = 0; w != null && depth < 8; depth++)
		{
			for (int i = 0; i < GE_SLOT_WIDGETS.length; i++)
			{
				if (w.getId() == GE_SLOT_WIDGETS[i])
				{
					return i;
				}
			}
			w = w.getParent();
		}
		return -1;
	}

	/** The 8 offer boxes, in slot order. Mirrors GeOfferGridOverlay's own
	 *  list — kept here too so the menu code does not have to reach into an
	 *  overlay for a constant. */
	private static final int[] GE_SLOT_WIDGETS = {
		InterfaceID.GeOffers.INDEX_0, InterfaceID.GeOffers.INDEX_1, InterfaceID.GeOffers.INDEX_2,
		InterfaceID.GeOffers.INDEX_3, InterfaceID.GeOffers.INDEX_4, InterfaceID.GeOffers.INDEX_5,
		InterfaceID.GeOffers.INDEX_6, InterfaceID.GeOffers.INDEX_7,
	};

	/** Adds a "PocketGE Favorites" right-click option to bank/inventory/
	 *  equipment item slots, piggybacking on the "Examine" entry the same way
	 *  RuneLite's own inventory-tags/menu-entry-swapper plugins do, so it's
	 *  injected exactly once per hover instead of once per existing option. */
	private void addBankFavoriteEntry(MenuEntryAdded event)
	{
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

		/* "PocketGE graph", but ONLY while the Grand Exchange is open — the
		   same gate Flipping Copilot puts on its own graph entry, and for the
		   same reason: right-clicking your inventory is something you do
		   constantly, and an extra option on every item everywhere is clutter
		   the other 99% of the time. At the GE it is exactly what you want,
		   because the question there is always "what has this been doing". */
		if (geWindowOpen())
		{
			client.createMenuEntry(-1)
				.setOption("PocketGE graph")
				.setTarget(event.getTarget())
				.setType(MenuAction.RUNELITE)
				.onClick(e -> openPocketGeSearch(name, itemId));
		}

		client.createMenuEntry(-1)
			.setOption(fav ? "Remove PocketGE favorite" : "Add PocketGE favorite")
			.setTarget(event.getTarget())
			.setType(MenuAction.RUNELITE)
			.onClick(e -> toggleFavorite(itemId, name));

		// Same toggle as the sidebar's "Hold" button on a SELL suggestion, or
		// its right-click "Never recommend" — reachable straight from the
		// bank slot the muted dashed border (BankHighlightOverlay) marks, so
		// changing your mind doesn't mean going and finding the suggestion
		// card again.
		final boolean held = Blocklist.contains(config.blocklist(), name);
		client.createMenuEntry(-1)
			.setOption(held ? "Resume PocketGE recommendations" : "Hold — stop PocketGE recommending this")
			.setTarget(event.getTarget())
			.setType(MenuAction.RUNELITE)
			.onClick(e ->
			{
				config.setBlocklist(held ? Blocklist.remove(config.blocklist(), name) : Blocklist.add(config.blocklist(), name));
				recomputeAdvice();
			});
	}

	/** Adds a "Search PocketGE for X" right-click option to chat lines that
	 *  look like a trade listing ("buying venator bow 68m") — piggybacks on
	 *  the game's own "Report" entry (present on essentially every player-
	 *  authored chat line) the same way addBankFavoriteEntry piggybacks on
	 *  "Examine". "Report"'s target is the sender's name; the message text
	 *  itself isn't exposed here, so it's looked up from what onChatMessage
	 *  cached for that sender. */
	private void addChatSearchEntry(MenuEntryAdded event)
	{
		final String sender = Text.removeTags(event.getTarget());
		log.debug("PocketGE chat search: Report target raw='{}' cleaned='{}' knownSenders={}",
			event.getTarget(), sender, lastChatMessageBySender.keySet());
		if (sender == null || sender.isEmpty())
		{
			return;
		}
		final String message = lastChatMessageBySender.get(sender);
		if (message == null)
		{
			log.debug("PocketGE chat search: no cached message for sender '{}'", sender);
			return;
		}
		final String itemName = ChatTradeParser.extractItemName(message);
		log.debug("PocketGE chat search: message='{}' parsedItemName='{}'", message, itemName);
		if (itemName == null)
		{
			return;
		}
		client.createMenuEntry(-1)
			.setOption("Search PocketGE for " + itemName)
			.setTarget(event.getTarget())
			.setType(MenuAction.RUNELITE)
			.onClick(e -> openPocketGeSearch(itemName));
	}

	/** How long after a poll we still believe a PocketGE tab is open. The
	 *  page polls on a timer, so this has to comfortably exceed its
	 *  interval; 40s covers the ~15s cadence the bridge refresh assumes with
	 *  room for a slow tick. */
	private static final long TAB_LIVE_MS = 40_000;

	/** Monotonic per plugin run. The website polls at-least-once and will
	 *  see the same request repeatedly, so it needs a token to tell a NEW
	 *  request from one it already acted on. */
	private final java.util.concurrent.atomic.AtomicLong navSeq = new java.util.concurrent.atomic.AtomicLong();
	private volatile LocalBridgeServer.NavRequest pendingNav;

	/**
	 * Opens an item on PocketGE — in the tab you already have open when
	 * there is one, otherwise by launching a browser.
	 *
	 * Asking the system to open a link is not "open a new tab" on every
	 * desktop; on some setups it navigates whatever tab currently has focus,
	 * which means a chart click can take over something else entirely. When
	 * a PocketGE page is already polling the bridge, handing it the item and
	 * letting it navigate itself avoids the question completely — and lands
	 * you on the tab you were already using for this, which is where you
	 * wanted to be anyway.
	 *
	 * Deliberately no timed fallback to opening a browser. Liveness here is
	 * a proxy (see LocalBridgeServer.hasRecentClient) so a handoff can
	 * occasionally reach a tab closed moments ago, but a fallback timer
	 * would have to outlast the page's poll interval, and "nothing happens
	 * for fifteen seconds, then a tab opens" is worse than either outcome on
	 * its own. The setting is opt-in and self-heals within one liveness
	 * window; the chat line means the click is never silent.
	 */
	private void openPocketGeSearch(String itemName)
	{
		openPocketGeSearch(itemName, 0);
	}

	private void openPocketGeSearch(String itemName, int itemId)
	{
		if (itemName == null || itemName.trim().isEmpty())
		{
			return;
		}
		if (config.reuseBrowserTab() && bridge != null && bridge.hasRecentClient(TAB_LIVE_MS))
		{
			pendingNav = new LocalBridgeServer.NavRequest(
				navSeq.incrementAndGet(), itemName, itemId, System.currentTimeMillis());
			announceSentToTab(itemName);
			return;
		}
		final String encoded = URLEncoder.encode(itemName, StandardCharsets.UTF_8).replace("+", "%20");
		LinkBrowser.browse("https://pocketge.com/?q=" + encoded);
	}

	/**
	 * The insta-buy price, which is the sell side of every buy idea the
	 * plugin makes — both CapitalPlanner.Candidate and Advisor.buildBuys
	 * derive their per-unit edge as {@code q.high - q.low - tax(q.high)}.
	 *
	 * Returns 0 rather than guessing when the quote is missing or does not
	 * describe a spread at all, and the card simply omits the "@ price" in
	 * that case. A made-up exit price next to a real profit figure would
	 * make the arithmetic look wrong to anyone who checked it.
	 */
	private static long exitPriceFor(Map<Integer, Advisor.Quote> quotes, int itemId)
	{
		final Advisor.Quote q = quotes != null ? quotes.get(itemId) : null;
		return q != null && q.high > q.low && q.low > 0 ? q.high : 0;
	}

	/** So a handoff is never a click that appears to do nothing — if your
	 *  PocketGE tab is behind the client you would otherwise have no idea
	 *  anything happened. */
	private void announceSentToTab(String itemName)
	{
		final String message = new ChatMessageBuilder()
			.append(Color.decode("#E5C158"), "PocketGE")
			.append(Color.WHITE, " opened ")
			.append(Color.decode("#1FB85C"), itemName)
			.append(Color.WHITE, " in your browser tab")
			.build();
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.runeLiteFormattedMessage(message)
			.build());
	}

	/**
	 * POST /favoriteLists {"action":"reorder"} lands here, on the bridge's
	 * HTTP thread. Same threading contract as setFavoriteFromBridge: config
	 * writes are thread-safe and refreshStatsAndFavorites() marshals itself
	 * onto the client thread.
	 *
	 * A null/unknown listId means "the list active in-game", matching how
	 * the favorites POST already resolves it — the site shouldn't have to
	 * track which list the player switched to a moment ago just to reorder
	 * the one it is looking at.
	 */
	private void reorderFavoritesFromBridge(String listId, List<Integer> itemIds)
	{
		final List<FavoriteLists.FavoriteList> lists = loadFavoriteLists();
		FavoriteLists.FavoriteList target = listId != null ? FavoriteLists.findList(lists, listId) : null;
		if (target == null)
		{
			target = activeFavoriteList(lists);
		}
		if (target == null)
		{
			return;
		}
		FavoriteLists.reorderTo(target, itemIds);
		saveFavoriteLists(lists);
		refreshStatsAndFavorites();
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
		final long polledAt = bridge != null ? bridge.lastPollAt() : 0;
		s.bridgeClientAgeSec = polledAt > 0 ? (System.currentTimeMillis() - polledAt) / 1000 : -1;
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
			/* The "did the offer screen close" check moved to onGameTick \u2014
			   here it only ever ran on the re-check interval, which is where
			   the stale "YOUR OFFER" card came from. */

			final Map<Integer, Advisor.Quote> quotes = lastQuotes;
			final Map<Integer, AnalystRating.Average> averages = lastAverages;
			final Map<Integer, Long> favVolumes = lastVolumes;

			final long cash = totalCash();
			final Map<Integer, Integer> holdings = currentHoldings();
			final Map<Integer, Integer> equipped = currentEquipped();
			final List<Advisor.OfferView> offers = currentOffers();
			final PortfolioValuer.Result portfolio = PortfolioValuer.value(cash, holdings, equipped, offers, quotes);

			final Map<Integer, long[]> openBuys = tracker.getOpenBuyTotals();

			long unrealized = 0;
			for (Map.Entry<Integer, long[]> e : openBuys.entrySet())
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
					/* Mid against mid. This compared the live INSTA-SELL against
					   a 24h MIDPOINT, which is not a like-for-like difference:
					   it subtracts the middle of the book from the bottom of
					   it, so the result sat low by half the spread on every
					   item, always. Wide-spread favourites were the worst hit,
					   and since this number now decides the ±15% spike badge,
					   the error moved from cosmetic to "the badge fires on the
					   wrong items".

					   Mid is also what the rest of the plugin already means by
					   this: FinderEngine's movers and AnalystRating's deviation
					   both compute (liveMid - typicalMid) / typicalMid, as does
					   the website's own mover1dPct. This was the only place
					   that did something else. */
					if (q.high > 0 && avg != null && avg.avgHighPrice > 0 && avg.avgLowPrice > 0)
					{
						final double typicalMid = (avg.avgHighPrice + avg.avgLowPrice) / 2.0;
						final double liveMid = (q.high + q.low) / 2.0;
						row.changePct = ((liveMid - typicalMid) / typicalMid) * 100.0;
						maybeAlertOnMove(row);
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
					// itemManager.getItemStats() throwing for one favorited item (seen
					// elsewhere in this file with getItemComposition(), see the bank
					// highlight fix) used to abort this whole per-favorite loop before
					// row.rating ever got set below — leaving that item's card blank
					// AND silently freezing stats/favorites/bank refresh for every
					// OTHER favorite too, since the Swing update at the end of this
					// method never ran. Row.limit is already documented as "0 if
					// unknown", so falling back to that is the existing contract.
					ItemStats itemStats = null;
					try
					{
						itemStats = itemManager.getItemStats(f.id);
					}
					catch (RuntimeException ignore) { /* fall back to limit unknown */ }
					row.limit = itemStats != null ? itemStats.getGeLimit() : 0;
					if (row.limit > 0)
					{
						final long edge = q.high - q.low - FlipTracker.taxPerItem(q.high, f.id);
						row.potentialProfit = edge * row.limit;
					}
				}
				row.rating = AnalystRating.grade(q, avg);
				row.dailyVolume = favVolumes.getOrDefault(f.id, 0L);
				fillHeldPosition(row, q, holdings, openBuys);
				/* Day or 5-day, decided in one place and by the website's own
				   rules — see PriceExtremes.tier. A big intraday move outranks
				   both, and that is decided in the panel off changePct. */
				final PriceExtremes ex = dayExtremes.get(f.id);
				row.tier = ex != null && q != null
					? ex.tier(q.high, q.low) : PriceExtremes.Tier.NONE;
				favRows.add(row);
			}
			// Used to float 5D-high/low items to the top of the list on every
			// refresh — the pulsing border already flags them without
			// reshuffling the list out from under a manually-arranged order
			// every time a flag flips on/off (worse now that the bridge and
			// advisor cycles refresh every 15-60s, not just occasionally).
			// Order stays exactly as saved; the glow alone is the signal.
			lastPortfolioValue = portfolio.total;
			lastCash = cash;
			lastBankStacks = bankStacks();

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

	/**
	 * Ping the player when something they watch has moved far enough to be
	 * worth leaving the bank for — Flipping Copilot's "dump alerts", built on
	 * the only evidence this plugin has.
	 *
	 * That evidence is a percentage, not a trade feed: the live midpoint
	 * against the wiki's 24-hour typical, the same number the spike badge
	 * shows. It cannot tell a genuine dump from a slow drift that happened to
	 * reach the same depth, and the setting's own description says so rather
	 * than implying a volume signal that isn't there.
	 *
	 * Runs on the advisor's background thread (the favourites refresh), which
	 * is why the cooldown map is concurrent. Notifier itself is safe to call
	 * from anywhere.
	 */
	private void maybeAlertOnMove(FavoritesPanel.Row row)
	{
		final int threshold = config.priceAlerts().pct();
		if (threshold <= 0 || row.name == null)
		{
			return;
		}
		final double move = Math.abs(row.changePct);
		if (move < threshold)
		{
			/* Back inside the band, so re-arm this item. An hour of silence is
			   the right answer to a price that STAYS 20% down; it is the wrong
			   answer to one that crosses, retreats and crosses again, which is
			   two separate events and the second is the tradeable one. */
			lastAlertMillis.remove(row.id);
			return;
		}
		final long now = System.currentTimeMillis();
		final Long last = lastAlertMillis.get(row.id);
		if (last != null && now - last < ALERT_COOLDOWN_MS)
		{
			return;
		}
		lastAlertMillis.put(row.id, now);
		notifier.notify(String.format("%s is %s %.0f%% on its 24-hour typical price.",
			row.name, row.changePct >= 0 ? "up" : "down", move));
	}
}
