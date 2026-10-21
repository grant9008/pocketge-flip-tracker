package com.pocketge.tracker;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
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
import net.runelite.client.RuneLite;
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
	name = "PocketGE Trading Terminal",
	description = "TradingView-style watchlists, price-move alerts and ranked flip ideas, with every flip's profit tracked after the 2% GE tax",
	/* RuneLite's own plugin search matches these the same way the hub's does,
	   so they follow the same rule: anything a flipper would type that the
	   name and the description above do not already contain. The description
	   covers watchlists, alerts, flip, profit and tax; these cover the rest. */
	tags = {"flipping", "flip", "grand exchange", "ge", "merchant", "merching",
		"money making", "margins", "profit", "advisor", "market", "osrs",
		"buy limit", "sell", "suggestions", "graph"}
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
	private BankLegendOverlay bankLegendOverlay;

	@Inject
	private GeOfferGridOverlay geGridOverlay;

	@Inject
	private GeOfferPriceOverlay gePriceOverlay;

	@Inject
	private PriceChartOverlay priceChartOverlay;

	@Inject
	private net.runelite.client.input.MouseManager mouseManager;

	@Inject
	private net.runelite.client.Notifier notifier;

	@Inject
	private net.runelite.client.input.KeyManager keyManager;

	/** A click on the price or quantity chip copies its number — the chip
	 *  itself says to press E to fill it. Registered only while the plugin
	 *  is on; the overlay decides what (if anything) is under the cursor, so
	 *  this consumes a click ONLY when it is genuinely over our own drawing. */
	private final net.runelite.client.input.MouseAdapter priceClickListener = new net.runelite.client.input.MouseAdapter()
	{
		@Override
		public java.awt.event.MouseEvent mousePressed(java.awt.event.MouseEvent e)
		{
			if (!javax.swing.SwingUtilities.isLeftMouseButton(e))
			{
				return e;
			}
			if (gePriceOverlay.isOverQuantity(e.getPoint()))
			{
				final long qty = gePriceOverlay.quantityToFill();
				if (qty > 0)
				{
					fillGeQuantity(qty);
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

	/**
	 * E, pressed in the game's own price or quantity box, puts the plugin's
	 * number in it.
	 *
	 * The only way the plugin puts text into the chatbox. RuneLite rejects
	 * plugins that "programmatically insert text into the user's chatbox
	 * input for any reason ... The only way text can get into the chatbox is
	 * if the user is pressing keys themselves." This used to fill the price
	 * box the moment it opened, and on a click in the sidebar or on a chip —
	 * neither of them a key the player pressed. Now nothing happens until
	 * the player presses E in a box that is open and waiting for a number,
	 * the same shape as the fill key other hub plugins offer.
	 *
	 * E is safe to take there: the box accepts digits and the k/m/b
	 * shorthands and nothing else, so the key has no meaning of its own in
	 * it. It is taken ONLY while the overlay is showing the chip that names
	 * it (GeOfferPriceOverlay.Armed) — every other E, in chat, in a search,
	 * anywhere, passes through untouched. A modifier (Ctrl, Alt, Meta) also
	 * passes it through.
	 */
	private final net.runelite.client.input.KeyListener fillKeyListener = new net.runelite.client.input.KeyListener()
	{
		private boolean ours(java.awt.event.KeyEvent e)
		{
			/* Shift too: "E with any modifier passes through" is what the
			   chips and the PR promise, and Shift+E means nothing in a
			   number box anyway. */
			return !e.isControlDown() && !e.isAltDown() && !e.isMetaDown() && !e.isShiftDown()
				&& gePriceOverlay.armed() != null;
		}

		@Override
		public void keyTyped(java.awt.event.KeyEvent e)
		{
			if (Character.toUpperCase(e.getKeyChar()) == GeOfferPriceOverlay.FILL_KEY_NAME.charAt(0) && ours(e))
			{
				e.consume(); // the press below already did the work
			}
		}

		@Override
		public void keyPressed(java.awt.event.KeyEvent e)
		{
			if (e.getKeyCode() != GeOfferPriceOverlay.FILL_KEY_CODE || !ours(e))
			{
				return;
			}
			final GeOfferPriceOverlay.Armed a = gePriceOverlay.armed();
			if (a == null || a.value <= 0)
			{
				return;
			}
			e.consume();
			fillGePrompt(a.quantity, a.value);
		}

		@Override
		public void keyReleased(java.awt.event.KeyEvent e)
		{
			if (e.getKeyCode() == GeOfferPriceOverlay.FILL_KEY_CODE && ours(e))
			{
				e.consume();
			}
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

	/**
	 * Each GE slot's collection box as last seen: {gp, units of the offer's
	 * item, that item's id}. Filled from the game's own containers for those
	 * boxes, which arrive whenever their contents change.
	 */
	private final Map<Integer, long[]> collectBox = new java.util.concurrent.ConcurrentHashMap<>();
	/** quantitySold on each slot when its box was last seen empty — the
	 *  baseline "sold since you last collected" counts from. */
	private final Map<Integer, Integer> soldAtCollect = new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * What the hover over a stack you bought needs: itemId -> {tracked
	 * units, what they cost, the price a sell card would ask}. Only items the
	 * plugin has a cost for — a stack it never saw you buy has no "profit".
	 * Priced the way Advisor.sellCandidates prices a sell card, so the hover
	 * and the card cannot disagree.
	 */
	static Map<Integer, long[]> holdingTipsFor(Map<Integer, long[]> openLots, Map<Integer, Advisor.Quote> quotes,
		Map<Integer, TradeEngine.Series> series)
	{
		final Map<Integer, long[]> out = new HashMap<>();
		if (openLots == null || quotes == null)
		{
			return out;
		}
		for (Map.Entry<Integer, long[]> e : openLots.entrySet())
		{
			final long[] lot = e.getValue();
			final Advisor.Quote q = quotes.get(e.getKey());
			if (lot == null || lot.length < 2 || lot[0] <= 0 || lot[1] <= 0 || q == null || q.high <= 0)
			{
				continue;
			}
			long price = q.high;
			final TradeEngine.Series s = series != null ? series.get(e.getKey()) : null;
			if (s != null)
			{
				final TradeEngine.Result eng = TradeEngine.viewTargets(q.low, q.high, q.lowTime, q.highTime, s, e.getKey());
				if (eng != null && eng.viable && eng.sell > 0)
				{
					price = TradeEngine.sellTarget(eng.sell, q.high);
				}
			}
			out.put(e.getKey(), new long[]{lot[0], lot[1], price});
		}
		return out;
	}

	/**
	 * Which GE slot a collection-box container belongs to, or -1.
	 *
	 * The ids are the game's: slots 1-6 kept the original "tradingpost_sell"
	 * inventories (518-523) and the two slots added later got their own
	 * (539, 540), which is why they are not one run. Nothing here trusts the
	 * mapping blindly — see collectContents.
	 */
	static int collectSlot(int containerId)
	{
		if (containerId >= 518 && containerId <= 523)
		{
			return containerId - 518;
		}
		return containerId == 539 ? 6 : containerId == 540 ? 7 : -1;
	}

	/**
	 * {gp, units of the offer's item} in a slot's collection box — or null if
	 * it holds anything else, in which case it is not the box we think it is
	 * and nothing is shown rather than a wrong number.
	 */
	static long[] collectContents(Item[] items, int offerItemId, java.util.function.IntUnaryOperator canonical)
	{
		long coins = 0;
		long units = 0;
		if (items != null)
		{
			for (Item it : items)
			{
				if (it == null || it.getId() <= 0 || it.getQuantity() <= 0)
				{
					continue;
				}
				if (it.getId() == COINS_ID)
				{
					coins += it.getQuantity();
				}
				else if (canonical.applyAsInt(it.getId()) == offerItemId)
				{
					units += it.getQuantity();
				}
				else
				{
					return null;
				}
			}
		}
		return new long[]{coins, units};
	}

	/** A slot's box changed: remember what is in it, and when it empties,
	 *  that everything filled so far has been collected. */
	/** Units of a box's item left it: see collectedAt. */
	private void noteCollected(long[] before, long unitsNow)
	{
		if (before != null && before.length >= 3 && before[1] > unitsNow)
		{
			collectedAt.put((int) before[2], System.currentTimeMillis());
		}
	}

	private void noteCollectBox(int slot, ItemContainer box)
	{
		final GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		final GrandExchangeOffer o = offers != null && slot < offers.length ? offers[slot] : null;
		final long[] before = collectBox.get(slot);
		if (o == null || o.getItemId() <= 0)
		{
			noteCollected(before, 0);
			collectBox.remove(slot);
			soldAtCollect.remove(slot);
			return;
		}
		final int offerItem = itemManager.canonicalize(o.getItemId());
		final long[] in = collectContents(box.getItems(), offerItem, itemManager::canonicalize);
		if (in == null)
		{
			collectBox.remove(slot);
			return;
		}
		noteCollected(before, in[1]);
		collectBox.put(slot, new long[]{in[0], in[1], offerItem});
		if (in[0] == 0 && in[1] == 0)
		{
			soldAtCollect.put(slot, o.getQuantitySold());
		}
	}
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
	static final int F2P_GE_SLOTS = 3;
	static final int MEMBERS_GE_SLOTS = 8;

	/**
	 * How many Grand Exchange slots this world actually lets you use.
	 *
	 * Static and shared, because the answer was known in exactly one place
	 * and needed in two. The advice text used it ("all 3 of your slots are
	 * busy"); the slot-grid overlay did not, and walked all eight slot
	 * widgets looking for a free one. On a free world the client reports
	 * slots 4-8 as EMPTY — they exist in the interface, just locked — so
	 * the ring that says "start your offer here" landed on a slot the
	 * player cannot open. Reported as "its trying to tell me to use a ge
	 * slot i dont have".
	 */
	static int usableGeSlots(Client client)
	{
		final java.util.EnumSet<WorldType> world = client == null ? null : client.getWorldType();
		return world != null && world.contains(WorldType.MEMBERS) ? MEMBERS_GE_SLOTS : F2P_GE_SLOTS;
	}
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
	/** When the bank was last read in THIS login — bankSeenAt is restored
	 *  from disk with the snapshot. 0 until the bank is opened. */
	private volatile long bankReadThisLoginAt = 0;
	/** itemId -> when units of it last left a GE slot (collected, to the
	 *  inventory or straight to the bank). Collect-to-bank moves them into
	 *  the bank without the bank being open, so the snapshot no longer
	 *  says what is held until it is opened again. */
	private final Map<Integer, Long> collectedAt = new java.util.concurrent.ConcurrentHashMap<>();

	/** Whether the held count for an item can be trusted to say what is NOT
	 *  held: the bank was read this login, after the item last left a slot.
	 *  Only then are older buys called "not held" or offered to forget. */
	private boolean heldCountCurrent(int itemId)
	{
		final long read = bankReadThisLoginAt;
		return read > 0 && read >= collectedAt.getOrDefault(itemId, 0L);
	}

	/** olderBuys for the items whose held count is current — see heldCountCurrent. */
	private Map<Integer, long[]> currentOlderBuys(Map<Integer, long[]> heldCost)
	{
		final Map<Integer, long[]> older = olderBuys(heldCost);
		older.keySet().removeIf(id -> !heldCountCurrent(id));
		return older;
	}
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
	/** When {@link #lastQuotes} was last fetched. Not the same thing as the
	 *  quote's own highTime/lowTime, which say when the market last traded —
	 *  this says how old OUR copy is. */
	private volatile long lastQuotesAt;
	/** How stale the book may be at the moment you open an offer screen.
	 *
	 *  The advisor cycle refreshes prices every 5 minutes by default, and for
	 *  every other purpose that is fine. It is not fine here: this is the one
	 *  screen where a number is about to be committed to, and on a item that
	 *  moves it is the difference between the plugin, the website and the
	 *  market disagreeing by tens of gp an item. Reported as the plugin
	 *  quoting 942 for a sell while pocketge.com said 957 and the book said
	 *  953 — the engine was right, its copy of the book was five minutes old.
	 *
	 *  /latest is a single request for every item, so refreshing it costs the
	 *  same one call the cycle already makes. */
	private static final long GE_QUOTE_MAX_AGE_MS = 30_000;
	/** With the advisor off the book is never refreshed; past this age the
	 *  offer screen stops quoting from it. */
	private static final long ADVISOR_OFF_BOOK_MAX_MS = 10 * 60 * 1000L;
	/** When the offer screen last asked for the book, success or not. */
	private volatile long lastGeQuoteAttemptAt;
	/** When the offer screen last asked for each item's series, success or
	 *  not. See updateGeContextFromScreen for the loop this exists to stop. */
	private final Map<Integer, Long> geSeriesAttemptAt = new java.util.concurrent.ConcurrentHashMap<>();
	private static final long GE_SERIES_RETRY_MS = 60_000;
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
	/** Items the capital plan recommended last cycle, so their price series
	 *  get fetched and the headline BUY card can be priced by the engine
	 *  rather than off the raw spread. See refreshOfferSeries. */
	private volatile Set<Integer> lastPlanItemIds = new HashSet<>();
	/**
	 * The items the sidebar is currently offering to sell — folded into
	 * refreshOfferSeries()'s fetch set (like lastActiveOfferItemIds) so their
	 * asking prices come from TradeEngine rather than the raw live quote.
	 *
	 * This was ONE item: whichever SELL Advisor.advise() ranked first. But
	 * the sidebar does not show one sell, it shows the whole ranked stack and
	 * lets you page through it — so card 1 was priced by the engine and cards
	 * 2..n were priced off the last insta-buy print. That is the same "quote
	 * the actively-traded price and call it a target" mistake the GE-context
	 * path had to be talked out of, and it left two cards in one list built by
	 * two different methods with nothing on either saying which you got.
	 *
	 * Bounded like lastPlanItemIds — a GE board's worth, taken in rank order,
	 * because the cost is one /timeseries call per item per advisor cycle.
	 * Past that the cards fall back to the raw print as before.
	 */
	private volatile Set<Integer> lastSellItemIds = new HashSet<>();
	/**
	 * The first few flip ideas on the list — the Advisor buys queued behind
	 * the capital plan — so the engine can confirm or re-price them the way
	 * it does the plan's own cards. Their comment always said they were
	 * "engine-confirmed on the same terms", but nothing ever fetched their
	 * series, so every one showed the raw last-print spread. That mattered
	 * less while they sat behind twenty sells; with flips first they are
	 * pages two to five.
	 */
	private volatile Set<Integer> lastIdeaItemIds = new HashSet<>();
	/** How many ideas get that. One /timeseries per item, cached like the rest. */
	static final int IDEA_SERIES_CAP = 4;
	/** How many of the best sells keep a place at the end of the list when
	 *  flips lead — enough that the stacks worth selling are still a few
	 *  clicks away, few enough that they cannot crowd the flips out. */
	static final int SELLS_KEPT_BEHIND_FLIPS = 5;

	/**
	 * Put the buy and sell cards in the order you page through them, after
	 * whatever {@code out} already holds (the adjust nudges on a full board).
	 *
	 * New flips lead whenever there is a real one: a buy that clears the
	 * profit floor — yours, or the planner's own 2,000 gp. The best few sells
	 * keep places at the end.
	 *
	 * It used to compare the gold the best buy would DEPLOY with the gold the
	 * best sell would RAISE and put the bigger first. That was there for a
	 * real report — down to 344 gp, "Buy 40 Lobster pot for 1 gp ea" sat in
	 * front of a bank worth millions — but it meant anyone holding stock
	 * worth more than their cash was told to sell first, every time, however
	 * much they had free to flip with. 2,910 Mithril bars kept for use put
	 * twenty "sell from your bank" cards ahead of every flip: "i keep finding
	 * myself clicking 15 pages back just to find 5 recommended flips". The
	 * floor still handles the Lobster pot: a 344 gp purse cannot clear it,
	 * so with no real flip on offer the sells lead as before.
	 */
	static void orderRecommendations(List<AdvisorPanel.Rec> out, List<AdvisorPanel.Rec> buys,
		List<AdvisorPanel.Rec> sells, long leadFloor, int max)
	{
		boolean realFlip = false;
		for (AdvisorPanel.Rec b : buys)
		{
			if (b.profit >= leadFloor)
			{
				realFlip = true;
				break;
			}
		}
		final List<AdvisorPanel.Rec> first = realFlip ? buys : sells;
		/* Behind the flips, the sells for stock the plugin watched you buy
		   come first. The ranking puts a stack it knows nothing about at its
		   whole sale value but a tracked one at only its profit, so five big
		   stacks held from before could take every kept place and push out
		   the second half of a flip you are actually in. Rank order holds
		   within each group. */
		final List<AdvisorPanel.Rec> second;
		if (realFlip)
		{
			second = new ArrayList<>(sells.size());
			for (AdvisorPanel.Rec r : sells)
			{
				if (r.hasTrackedCost)
				{
					second.add(r);
				}
			}
			for (AdvisorPanel.Rec r : sells)
			{
				if (!r.hasTrackedCost)
				{
					second.add(r);
				}
			}
		}
		else
		{
			second = buys;
		}
		final int room = Math.max(0, max - out.size());
		/* Places held back for the sells only. With no real flip the buys on
		   offer are the Lobster-pot kind, and they get whatever room is left. */
		final int kept = realFlip ? Math.min(second.size(), SELLS_KEPT_BEHIND_FLIPS) : 0;
		final int firstCount = Math.min(first.size(), Math.max(0, room - kept));
		out.addAll(first.subList(0, firstCount));
		for (AdvisorPanel.Rec r : second)
		{
			if (out.size() >= max)
			{
				break;
			}
			out.add(r);
		}
	}

	/** How many of the ranked sell cards get an engine-priced ask. See above. */
	static final int SELL_SERIES_CAP = 8;

	/**
	 * Which of the sell cards get a price series fetched for the next cycle.
	 *
	 * Rank order and capped, because sellRecs runs to MAX_RECOMMENDATIONS and
	 * every id here is one more /timeseries call per advisor cycle. The cards
	 * past the cap keep the old behaviour — priced off the raw print — which
	 * is a worse answer but not a wrong one, and they are the ones you have
	 * to page a long way to reach.
	 */
	static Set<Integer> sellSeriesIds(List<AdvisorPanel.Rec> sellRecs)
	{
		final Set<Integer> ids = new LinkedHashSet<>();
		for (AdvisorPanel.Rec r : sellRecs)
		{
			if (ids.size() >= SELL_SERIES_CAP)
			{
				break;
			}
			ids.add(r.itemId);
		}
		return ids;
	}
	/** The watchlist item the inspection card is currently showing, or null.
	 *  Its price series is fetched on demand — see onSelectedItemChanged. */
	private volatile Integer selectedFavoriteItemId = null;
	/** Name of {@link #selectedFavoriteItemId}, needed to build its Row when
	 *  the item is not in any favourites list (a Find Opportunities pick). */
	private volatile String selectedFavoriteName = null;
	/**
	 * Price series fetched ON DEMAND rather than on the advisor cycle: the
	 * watchlist item being inspected, and whatever item a GE offer screen was
	 * just opened for.
	 *
	 * Kept apart from lastOfferSeries because that map is rebuilt wholesale
	 * every cycle, which would drop an on-demand fetch moments after it
	 * landed.
	 *
	 * Bounded. Only a handful of items can be inspected or opened before the
	 * next cycle folds them into lastOfferSeries anyway, so past the cap it
	 * starts fresh rather than growing for the life of the session — the cost
	 * of being wrong is one re-fetch, and these are already the items being
	 * looked at.
	 */
	private volatile Map<Integer, TradeEngine.Series> selectedSeries = new HashMap<>();
	private static final int ON_DEMAND_SERIES_CAP = 8;

	/** When each on-demand series was fetched. Executor thread writes. */
	private final Map<Integer, Long> selectedSeriesFetchedAt = new java.util.concurrent.ConcurrentHashMap<>();

	/** Add one on-demand series without letting the map grow unbounded. */
	private void cacheOnDemandSeries(int itemId, TradeEngine.Series series)
	{
		final Map<Integer, TradeEngine.Series> m =
			selectedSeries.size() >= ON_DEMAND_SERIES_CAP ? new HashMap<>() : new HashMap<>(selectedSeries);
		m.put(itemId, series);
		selectedSeriesFetchedAt.put(itemId, System.currentTimeMillis());
		selectedSeries = m;
		selectedSeriesFetchedAt.keySet().retainAll(m.keySet());
	}

	/** The price history for one item, from either bounded cache — the
	 *  newer when both hold it, since the offer-screen chart refreshes an
	 *  open offer's series between advisor cycles. Null when neither has
	 *  it, in which case callers fall back to the raw live quote. */
	private TradeEngine.Series seriesFor(int itemId)
	{
		final TradeEngine.Series offer = lastOfferSeries.get(itemId);
		final TradeEngine.Series picked = selectedSeries.get(itemId);
		if (offer == null || picked == null)
		{
			return offer != null ? offer : picked;
		}
		final Long offerAt = offerSeriesFetchedAt.get(itemId);
		final Long pickedAt = selectedSeriesFetchedAt.get(itemId);
		return pickedAt != null && (offerAt == null || pickedAt > offerAt) ? picked : offer;
	}

	/** When the series seriesFor returns was fetched; 0 if unknown. */
	private long seriesFetchedAt(int itemId)
	{
		final Long offerAt = lastOfferSeries.containsKey(itemId) ? offerSeriesFetchedAt.get(itemId) : null;
		final Long pickedAt = selectedSeries.containsKey(itemId) ? selectedSeriesFetchedAt.get(itemId) : null;
		return Math.max(offerAt != null ? offerAt : 0L, pickedAt != null ? pickedAt : 0L);
	}

	/* ── The offer-screen chart's data ─────────────────────────────────── */

	/** At most this many chart series held: an item's few ranges, for the
	 *  handful of items an evening's offers go through. */
	private static final int CHART_CACHE_CAP = 24;
	/** A failed chart fetch is not asked for again for this long. */
	private static final long CHART_RETRY_MS = 60_000L;

	/** "itemId/step" -> {Series, fetchedAtMillis}, least recently drawn first. */
	private final Map<String, Object[]> chartCache = java.util.Collections.synchronizedMap(
		new LinkedHashMap<String, Object[]>(32, 0.75f, true)
		{
			@Override
			protected boolean removeEldestEntry(Map.Entry<String, Object[]> eldest)
			{
				return size() > CHART_CACHE_CAP;
			}
		});
	private final java.util.Set<String> chartInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet();
	private final Map<String, Long> chartTriedAt = new java.util.concurrent.ConcurrentHashMap<>();

	/** How long a chart series stays fresh: one bucket for 5m, and for the
	 *  longer steps a fraction of one (half, a third, a quarter), so the
	 *  newest bucket is never long behind. */
	static long chartTtlMs(String step)
	{
		switch (step)
		{
			case "5m":
				return 5 * 60_000L;
			case "1h":
				return 30 * 60_000L;
			case "6h":
				return 2 * 3_600_000L;
			default:
				return 6 * 3_600_000L;
		}
	}

	/**
	 * The series the chart draws for an item and range, picked as the site
	 * picks (seriesForView): the first of the range's timesteps with five
	 * prints, else the first with any. Null while one it would need is
	 * still coming; an empty series when every step came back empty.
	 * Asks for a missing or stale one; never waits for it. Client thread,
	 * every frame: map lookups and at most a queued task per step.
	 */
	private TradeEngine.Series chartSeries(int itemId, PriceChart.Range range)
	{
		final long now = System.currentTimeMillis();
		TradeEngine.Series fallback = null;
		TradeEngine.Series empty = null;
		for (String step : range.steps)
		{
			final TradeEngine.Series s;
			if ("5m".equals(step))
			{
				/* The 5-minute series is the one the offer screen fetches
				   and the advisor refreshes: drawn from there, and topped up
				   there when it is older than a bucket. */
				s = seriesFor(itemId);
				if (s == null || now - seriesFetchedAt(itemId) > chartTtlMs(step))
				{
					requestSharedSeries(itemId);
				}
			}
			else
			{
				final Object[] held = chartCache.get(itemId + "/" + step);
				s = held != null ? (TradeEngine.Series) held[0] : null;
				if (held == null || now - (long) held[1] > chartTtlMs(step))
				{
					requestChartSeries(itemId, step);
				}
			}
			if (s == null)
			{
				return fallback; // still coming: draw what there is, or "loading"
			}
			if (PriceChart.usable(s))
			{
				return s;
			}
			if (fallback == null && PriceChart.prints(s) > 0)
			{
				fallback = s;
			}
			if (empty == null)
			{
				empty = s;
			}
		}
		return fallback != null ? fallback : empty;
	}

	/**
	 * Fetch an item's 5-minute series into the same on-demand cache, on the
	 * same attempt clock, as the offer screen's own fetch — so the two never
	 * ask twice — then let the offer screen re-read it as that fetch does.
	 */
	private void requestSharedSeries(int itemId)
	{
		final ScheduledExecutorService ex = executor;
		final long now = System.currentTimeMillis();
		final Long tried = geSeriesAttemptAt.get(itemId);
		if (!running || ex == null || !config.advisor() || (tried != null && now - tried < GE_SERIES_RETRY_MS))
		{
			return;
		}
		if (geSeriesAttemptAt.size() > ON_DEMAND_SERIES_CAP * 4)
		{
			geSeriesAttemptAt.clear(); // bounded; worst case is one early retry
		}
		geSeriesAttemptAt.put(itemId, now);
		ex.execute(() ->
		{
			if (!running || !config.advisor())
			{
				return;
			}
			/* A fetch queued ahead of this one, the offer screen's or the
			   advisor's, may already have brought it. */
			if (seriesFor(itemId) != null && System.currentTimeMillis() - seriesFetchedAt(itemId) <= chartTtlMs("5m"))
			{
				return;
			}
			try
			{
				cacheOnDemandSeries(itemId, marketClient.fetchTimeseries5m(itemId));
			}
			catch (Exception e)
			{
				return; // backing off, or the Wiki said no: tried again after GE_SERIES_RETRY_MS
			}
			clientThread.invokeLater(() ->
			{
				final Integer stillOpen = geContextItemId;
				if (stillOpen != null && stillOpen == itemId)
				{
					updateGeContextFromScreen();
				}
			});
		});
	}

	private void requestChartSeries(int itemId, String step)
	{
		final ScheduledExecutorService ex = executor;
		final String key = itemId + "/" + step;
		final long now = System.currentTimeMillis();
		final Long tried = chartTriedAt.get(key);
		if (!running || ex == null || !config.advisor() || chartInFlight.contains(key)
			|| (tried != null && now - tried < CHART_RETRY_MS))
		{
			return;
		}
		if (chartTriedAt.size() > 4 * CHART_CACHE_CAP)
		{
			chartTriedAt.clear();
		}
		chartTriedAt.put(key, now);
		chartInFlight.add(key);
		ex.execute(() ->
		{
			try
			{
				if (!running || !config.advisor())
				{
					return;
				}
				final TradeEngine.Series s = marketClient.fetchTimeseries(itemId, step);
				chartCache.put(key, new Object[]{s, System.currentTimeMillis()});
				chartTriedAt.remove(key);
			}
			catch (IOException e)
			{
				// backing off, or the Wiki said no: tried again after CHART_RETRY_MS
			}
			finally
			{
				chartInFlight.remove(key);
			}
		});
	}

	/**
	 * The book's quote for an item as the offer screen would use it: none
	 * with the advisor off and the book past ADVISOR_OFF_BOOK_MAX_MS, the
	 * rule that blanks the offer screen's card, so the chart does not name
	 * as the market a price the card will not.
	 */
	private Advisor.Quote chartQuote(int itemId)
	{
		if (!config.advisor() && System.currentTimeMillis() - lastQuotesAt > ADVISOR_OFF_BOOK_MAX_MS)
		{
			return null;
		}
		return lastQuotes.get(itemId);
	}

	/** The target line's number: the offer screen's own, through the same helper. */
	private long chartTarget(int itemId, boolean buy)
	{
		final Advisor.Quote q = chartQuote(itemId);
		final TradeEngine.Series series = seriesFor(itemId);
		final TradeEngine.Result engine = q != null && series != null
			? TradeEngine.viewTargets(q.low, q.high, q.lowTime, q.highTime, series, itemId) : null;
		return offerScreenPrice(q, engine, buy);
	}

	/** What the chart overlay asks the plugin for. */
	private final PriceChartOverlay.Feed chartFeed = new PriceChartOverlay.Feed()
	{
		@Override
		public TradeEngine.Series series(int itemId, PriceChart.Range range)
		{
			return chartSeries(itemId, range);
		}

		@Override
		public boolean pricesOn()
		{
			return config.advisor();
		}

		@Override
		public Advisor.Quote quote(int itemId)
		{
			return chartQuote(itemId);
		}

		@Override
		public long target(int itemId, boolean buy)
		{
			return chartTarget(itemId, buy);
		}

		@Override
		public String name(int itemId)
		{
			final ItemComposition c = itemManager != null ? itemManager.getItemComposition(itemId) : null;
			return c != null ? c.getName() : null;
		}
	};
	/** 5-day high/low per favorited item, powering the Favorites panel's
	 *  flashing "at a 5-day high/low" glow — same signal as the website's
	 *  ▲/▼ 5D badge. Only ever holds entries for CURRENTLY favorited items
	 *  (see refreshDayExtremes) since it's a per-item network call. */
	private final Map<Integer, PriceExtremes> dayExtremes = new java.util.concurrent.ConcurrentHashMap<>();
	/** Re-fetch an item's 5-day extremes at most this often. They are read
	 *  from 1-hour candles, so nothing shorter than an hour can change them.
	 *  New favorites are still fetched on the next cycle regardless. */
	private static final long DAY_EXTREMES_TTL_MS = 60 * 60 * 1000L;
	/** Up to this much added to each item's hour, at random, so the items a
	 *  client fetched together drift apart rather than coming due together. */
	private static final long DAY_EXTREMES_JITTER_MS = 10 * 60 * 1000L;
	/** How long a failed item waits before it is asked for again. */
	private static final long DAY_EXTREMES_RETRY_MS = 10 * 60 * 1000L;
	/** At most this many extremes requests in one advisor cycle, on the
	 *  intervals short enough for that to cover the list within the hour. */
	private static final int MAX_EXTREME_FETCHES_PER_CYCLE = 15;

	/**
	 * How many extremes requests one cycle may send.
	 *
	 * Fifteen on the short intervals, where the hour holds plenty of cycles
	 * to work through the list. On the long ones (30 minutes, 2 hours, 8
	 * hours) fifteen a cycle could never get round a full list once an hour,
	 * so the cap rises to whatever share of the list each cycle has to cover —
	 * the whole list, at most, which is what the old single-timer pass sent
	 * every time.
	 */
	static int extremesPerCycle(int tracked, long periodMs)
	{
		final long share = (tracked * periodMs + DAY_EXTREMES_TTL_MS - 1) / DAY_EXTREMES_TTL_MS;
		return (int) Math.min(Math.max(tracked, 0), Math.max(MAX_EXTREME_FETCHES_PER_CYCLE, share));
	}
	/** Favorites beyond this many (in list order) get no 5D badge. */
	static final int MAX_EXTREME_FAVORITES = 60;
	/** When each tracked item's extremes are next due, epoch millis. */
	private final Map<Integer, Long> dayExtremesDueAt = new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * 30-day trading range per item, for the buy card's footnote.
	 *
	 * Fetched ONLY for the handful of positions the capital plan actually
	 * recommends — at most one per free GE slot, so at most 8, usually fewer.
	 * Not for the paged Advisor.buildBuys ideas and emphatically not for the
	 * planner's 150-candidate pool: this costs one request per item and there
	 * is no bulk history endpoint to fall back on.
	 *
	 * That bound is also why the signal is display-only and takes no part in
	 * ranking. Ranking 150 candidates on a number affordable for 8 of them
	 * would not be a risk-adjusted ranking; it would be a ranking that
	 * quietly penalised whichever items happened to have been looked at.
	 */
	private final Map<Integer, RangePosition> range30 = new java.util.concurrent.ConcurrentHashMap<>();
	private final Map<Integer, Long> range30FetchedAt = new java.util.concurrent.ConcurrentHashMap<>();
	/** Same half-hour as the other range data — a 30-day window does not move
	 *  fast enough to be worth re-reading more often. */
	private static final long RANGE30_TTL_MS = 30 * 60 * 1000L;
	/** Hard ceiling on NEW range fetches in one cycle, so a bank change that
	 *  rebuilds the whole plan cannot turn into a burst. Anything skipped is
	 *  picked up next cycle; the card simply has no footnote until then. */
	private static final int MAX_RANGE30_FETCHES_PER_CYCLE = 4;
	/** Stop the two maps growing for the life of the session. Comfortably
	 *  above the 8 the plan can hold, so a warm entry is never evicted by
	 *  ordinary churn. */
	private static final int RANGE30_CACHE_CAP = 64;
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
	private volatile long geContextPrice = 0;
	/** The same offer as a card — see the tail of updateGeContextFromScreen. */
	private volatile AdvisorPanel.Rec geContextRec;
	/** Which stats window the panel's dropdown currently shows — not
	 *  persisted; every RuneLite launch starts back on Session, same as the
	 *  panel itself starting fresh each login. */
	private FlipStats.Range currentRange = FlipStats.Range.SESSION;
	/** When the oldest flip in a rolling range ages out of it, or 0. */
	private volatile long rangeExpiresAt;

	@Provides
	PocketGeTrackerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(PocketGeTrackerConfig.class);
	}

	@Override
	protected void startUp()
	{
		running = true;
		handoffTimer = new ScheduledThreadPoolExecutor(1, r ->
		{
			final Thread t = new Thread(r, "pocketge-handoff");
			t.setDaemon(true);
			return t;
		});
		// A timer still pending when the plugin stops has nothing left to do.
		handoffTimer.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
		handoffTimer.setRemoveOnCancelPolicy(true);
		bridge = new LocalBridgeServer(gson);
		bridge.setAckListener(this::onHandoffAcked);
		loadState();
		openLedger();
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
			public void setConfirmBlock(boolean on)
			{
				config.setConfirmBlock(on);
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
			public void addFavoriteToList(String listId, int itemId, String name)
			{
				final List<FavoriteLists.FavoriteList> lists = loadFavoriteLists();
				/* A null listId means the active one, so a single-list setup
				   and the submenu both land here. Same guards as addFavorite:
				   never a duplicate, never a removal. */
				FavoriteLists.FavoriteList target = null;
				if (listId != null)
				{
					for (FavoriteLists.FavoriteList l : lists)
					{
						if (listId.equals(l.id))
						{
							target = l;
							break;
						}
					}
				}
				if (target == null)
				{
					target = activeFavoriteList(lists);
				}
				if (target == null || FavoriteLists.contains(target, itemId))
				{
					return;
				}
				FavoriteLists.addItem(target, itemId, name);
				saveFavoriteLists(lists);
				refreshStatsAndFavorites();
				recomputeAdvice();
			}

			@Override
			public void inspectItem(int itemId, String name)
			{
				/* Show it at once, then let the refresh fill in the numbers.
				   The card is driven by a Row, and building a full one needs
				   the client thread (buy limits) plus the cached quotes — so
				   the immediate Row carries the identity and the next cycle,
				   which is already building rows for this id (see the
				   inspected-item branch in refreshStatsAndFavorites), replaces
				   it with the priced one. */
				selectedFavoriteItemId = itemId;
				selectedFavoriteName = name;
				final FavoritesPanel.Row stub = new FavoritesPanel.Row();
				stub.id = itemId;
				stub.name = name;
				SwingUtilities.invokeLater(() -> mainPanel.showInspected(stub));
				/* Fetch the price series if it is not cached, and rebuild
				   regardless — onSelectedItemChanged returns early without a
				   refresh when the series is already in hand, which would
				   leave the stub above unpriced. */
				onSelectedItemChanged(itemId);
				refreshStatsAndFavorites();
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
			public void setMinProfit(PocketGeTrackerConfig.MinProfit v)
			{
				/* Same shape as the interval above: the config write fires
				   ConfigChanged, and the recompute is so the list re-ranks
				   against the new floor while the popup is still open rather
				   than at the next scheduled tick. */
				config.setMinProfit(v);
				recomputeAdvice();
			}

			@Override
			public void setColourTheme(PocketGeTrackerConfig.ColourTheme v)
			{
				/* No recompute: this changes nothing about WHICH flips are
				   worth taking, only which two colours say buy and sell.
				
				   But the panel has to be HANDED the new settings, which is
				   what this used to miss. refreshPanel updates history and
				   stats and never calls buildSettings, so the colours did not
				   arrive until the next advisor cycle — five minutes, or never
				   with the advisor paused. The comment here claimed otherwise
				   and was wrong. */
				config.setColourTheme(v);
				pushSettings();
				/* The bank squares are painted in the same two colours, so
				   they have to move with the panel or the two surfaces
				   disagree until the next restart. */
				pushBankTheme();
				refreshPanel();
			}

			@Override
			public void setShowFlipScore(boolean on)
			{
				/* Same as the colours: a drawing preference, not an input to
				   the ranking, and it has to be pushed for the same reason. */
				config.setShowFlipScore(on);
				pushSettings();
				refreshPanel();
			}

			@Override
			public void setBankLegend(boolean on)
			{
				/* ConfigChanged -> onConfigChanged() turns the overlay on or
				   off and pushes the popup's copy, the same path a change in
				   RuneLite's own settings takes, so the two cannot disagree. */
				config.setBankLegend(on);
			}

			/* The four below take the same path: the config write fires
			   ConfigChanged, and onConfigChanged applies it and pushes the
			   popup's copy, exactly as a change in RuneLite's own settings. */
			@Override
			public void setBankHighlights(boolean on)
			{
				config.setBankHighlights(on);
			}

			@Override
			public void setHoldingTips(boolean on)
			{
				config.setHoldingTips(on);
			}

			@Override
			public void setPriceChart(boolean on)
			{
				config.setPriceChart(on); // the overlay reads it every frame
			}

			@Override
			public void setFollowGeOffer(boolean on)
			{
				config.setFollowGeOffer(on);
			}

			@Override
			public void setShowBadges(boolean on)
			{
				config.setShowBadges(on);
			}

			@Override
			public void setPriceAlerts(PocketGeTrackerConfig.PriceAlert v)
			{
				config.setPriceAlerts(v);
			}

			@Override
			public void setReuseBrowserTab(boolean on)
			{
				config.setReuseBrowserTab(on);
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
				/*
				 * While the game is asking WHICH item to trade, clicking the
				 * item on the card means that item — not "show me a chart".
				 *
				 * You have an empty offer open and a card naming what to put
				 * in it; the chatbox is waiting for a name. Sending the
				 * browser a chart at that moment answers a question nobody
				 * asked, and typing the name is the only reason to be
				 * clicking. The chip on the chatbox does the same thing from
				 * the other side — this is the same fill, reachable from
				 * where you are already looking.
				 *
				 * The chart is still one right-click away.
				 */
				if (GeOfferPriceOverlay.isItemSearchPrompt(client))
				{
					fillGeSearch(itemName);
					return;
				}
				/*
				 * A plain click OPENS A TAB. Every time.
				 *
				 * It used to try the handoff first whenever a page looked
				 * like it was listening, and the trouble with that is the
				 * failure mode: when the guess was wrong nothing happened at
				 * all — no tab moved, no browser opened, no message. Three
				 * separate reports of "the chart button does nothing unless I
				 * right-click", and each time the cause was a different link
				 * in a chain that could only ever fail silently.
				 *
				 * Opening a tab cannot fail silently. The handoff is the
				 * better behaviour when it works, so it keeps its place — as
				 * the explicit right-click, and on the left click for anyone
				 * who turns reuseBrowserTab on.
				 */
				if (config.reuseBrowserTab())
				{
					openPocketGeSearch(itemName);
					return;
				}
				settlePendingHandoff(itemName);
				browsePocketGe(itemName);
			}

			@Override
			public void openChartTab(String itemName)
			{
				/* No condition, by design — see Actions.openChartTab. The
				   button says "chart", so it opens the chart. Whether the
				   Exchange happens to be asking for an item name is a fact
				   about the game, not about what this button is for; the
				   click on the item's NAME still handles that case. */
				settlePendingHandoff(itemName);
				browsePocketGe(itemName);
			}

			@Override
			public void sendChartToOpenTab(String itemName)
			{
				/* Change the page you already have open, rather than adding
				   another. Deliberately ignores reuseBrowserTab: picking this
				   item off the menu IS the opt-in, and a menu entry that did
				   nothing because of a setting elsewhere would be the same
				   silent failure this whole arrangement exists to end.
				   openPocketGeSearch still falls back to opening a tab when
				   nothing is actually listening, so this cannot no-op
				   either. */
				openPocketGeSearch(itemName, 0, true);
			}

			@Override
			public void copyReport(int itemId, String itemName, String card)
			{
				/* Built on the client thread: the offers, the inventory and
				   the collection boxes are the client's to read. */
				clientThread.invoke(() ->
				{
					final String report = buildReport(itemId, itemName, card);
					Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(report), null);
					final String message = new ChatMessageBuilder()
						.append(Color.decode("#E5C158"), "PocketGE")
						.append(Color.WHITE, ": report for ")
						.append(Color.decode("#1FB85C"), itemName)
						.append(Color.WHITE, " copied — paste it to whoever is helping you")
						.build();
					chatMessageManager.queue(QueuedMessage.builder()
						.type(ChatMessageType.GAMEMESSAGE)
						.runeLiteFormattedMessage(message)
						.build());
				});
			}

			@Override
			public long[] paidRecord(int itemId)
			{
				return tracker.getOpenBuyRecord(itemId);
			}

			@Override
			public void forgetPaid(int itemId, String itemName, long upTo)
			{
				clientThread.invoke(() ->
				{
					final long[] had = tracker.forgetOpenBuys(itemId, upTo);
					if (had == null)
					{
						return;
					}
					log.info("PocketGE: forgot {} open buy lots ({} for {} gp) at the player's request", itemName, had[0], had[1]);
					saveState();
					/* The offer-screen card holds its own copy of PAID @, built
					   when the screen opened; rebuild it, or it goes on showing
					   the cost just forgotten. */
					final Integer onScreen = geContextItemId;
					if (onScreen != null && onScreen == itemId)
					{
						updateGeContextFromScreen();
					}
					refreshPanel();
					if (config.advisor() && !lastQuotes.isEmpty())
					{
						recomputeAdvice();
					}
					chatMessageManager.queue(QueuedMessage.builder()
						.type(ChatMessageType.GAMEMESSAGE)
						.runeLiteFormattedMessage(new ChatMessageBuilder()
							.append(Color.decode("#E5C158"), "PocketGE")
							.append(Color.WHITE, ": forgot what you paid for ")
							.append(Color.decode("#1FB85C"), itemName)
							.append(Color.WHITE, String.format(" (%,d bought)", had[0]))
							.build())
						.build());
				});
			}

			@Override
			public void forgetOlder(int itemId, String itemName, long shown)
			{
				/* On the client thread, against what is held NOW — the same
				   count the card's note was made from, read fresh — and never
				   more than the prompt showed. */
				clientThread.invoke(() ->
				{
					final long held = heldForCost(currentHoldings(), currentOffers()).getOrDefault(itemId, 0L);
					if (held <= 0 || !heldCountCurrent(itemId))
					{
						/* The bank has not been read since some of it may have
						   gone in: forgetting now could drop buys still held. */
						chatMessageManager.queue(QueuedMessage.builder()
							.type(ChatMessageType.GAMEMESSAGE)
							.runeLiteFormattedMessage(new ChatMessageBuilder()
								.append(Color.decode("#E5C158"), "PocketGE")
								.append(Color.WHITE, ": open your bank first, so PocketGE can see what you hold, then try again")
								.build())
							.build());
						return;
					}
					final long[] total = tracker.getOpenBuyTotal(itemId);
					if (total == null)
					{
						return;
					}
					final long[] dropped = tracker.forgetOlderThan(itemId, Math.max(held, total[0] - shown));
					if (dropped == null)
					{
						return;
					}
					log.info("PocketGE: forgot {} older buys of {} ({} gp) at the player's request", dropped[0], itemName, dropped[1]);
					saveState();
					final Integer onScreen = geContextItemId;
					if (onScreen != null && onScreen == itemId)
					{
						updateGeContextFromScreen();
					}
					refreshPanel();
					if (config.advisor() && !lastQuotes.isEmpty())
					{
						recomputeAdvice();
					}
					chatMessageManager.queue(QueuedMessage.builder()
						.type(ChatMessageType.GAMEMESSAGE)
						.runeLiteFormattedMessage(new ChatMessageBuilder()
							.append(Color.decode("#E5C158"), "PocketGE")
							.append(Color.WHITE, String.format(": forgot %,d older buys of ", dropped[0]))
							.append(Color.decode("#1FB85C"), itemName)
							.build())
						.build());
				});
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
			public void setWatchlistRows(int rows)
			{
				/* Stored without a @ConfigItem: it is the panel's own state,
				   set by dragging the list, not a preference anyone would go
				   looking for in the wrench menu. Restored at startup where
				   the badge switch is. */
				configManager.setConfiguration(PocketGeTrackerConfig.GROUP, WATCHLIST_ROWS_KEY, rows);
			}

			@Override
			public void onRecommendationShown(Integer itemId, String name, boolean sell)
			{
				/*
				 * The one-click chip on "What would you like to buy?" follows
				 * the card you are LOOKING at.
				 *
				 * It used to be set only inside the advisor recompute, from
				 * the first buy in the ranked list — so pressing Next to page
				 * to another idea left the chip offering the one you had moved
				 * off, and any card reached without a recompute since login
				 * left it offering nothing at all. The item on screen is the
				 * item you are about to search for.
				 *
				 * Direction is deliberately ignored: the same chatbox asks
				 * "what would you like to sell?" and the chip answers the
				 * question of WHICH item either way.
				 */
				gePriceOverlay.setSearchSuggestion(itemId != null ? itemId : 0, name);
				/* Straight through to the two overlays that point at things.
				   A sell names a stack you own, so the bank/inventory mark
				   knows which slot to ring; a buy names something you do not
				   have yet, so the Exchange ring points at where buying
				   starts instead. Never both, which is what keeps the gold
				   ring meaning one thing. */
				bankOverlay.setRecommended(sell ? itemId : null);
				/* Either side now: a sell begins in a free slot the same way
				   a buy does, and pointing at nothing on half the cards was
				   the asymmetry reported. */
				geGridOverlay.setActionPrompt(itemId, sell);
			}

			@Override
			public void onSelectedItemChanged(Integer itemId)
			{
				selectedFavoriteItemId = itemId;
				if (itemId == null || seriesFor(itemId) != null)
				{
					return; // nothing to fetch; the next refresh already has it
				}
				/* Fetch NOW rather than at the next advisor tick. That tick is
				   five minutes away by default, and a card that says "no margin"
				   for five minutes after you click it is indistinguishable from
				   a card that is wrong. One request, one item — the same
				   bounded shape as refreshOfferSeries. */
				if (!config.advisor())
				{
					/* "Turn the advisor off" means no requests. The card
					   shows what is already held. */
					refreshStatsAndFavorites();
					return;
				}
				executor.execute(() ->
				{
					if (!running)
					{
						return;
					}
					try
					{
						final TradeEngine.Series s = marketClient.fetchTimeseries5m(itemId);
						if (itemId.equals(selectedFavoriteItemId))
						{
							/* The re-check guards against a slow fetch landing
							   after you have already clicked something else. */
							cacheOnDemandSeries(itemId, s);
						}
					}
					catch (Exception e)
					{
						log.warn("PocketGE: timeseries fetch failed for inspected item {}", itemId, e);
					}
					refreshStatsAndFavorites();
				});
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
		mainPanel.setWatchlistRows(readWatchlistRows());

		final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "icon.png");
		navButton = NavigationButton.builder()
			.tooltip("PocketGE Trading Terminal")
			.icon(icon)
			.priority(6)
			.panel(mainPanel)
			.build();
		clientToolbar.addNavigation(navButton);
		// Registered either way; the overlay itself no-ops when off, so a
		// toggle never has to add/remove a live overlay mid-frame.
		bankOverlay.setEnabled(config.bankHighlights());
		bankOverlay.setHoldingTips(config.holdingTips());
		pushBankTheme();
		overlayManager.add(bankOverlay);
		overlayManager.add(bankLegendOverlay);
		overlayManager.add(geGridOverlay);
		overlayManager.add(gePriceOverlay);
		priceChartOverlay.setFeed(chartFeed);
		overlayManager.add(priceChartOverlay);
		mouseManager.registerMouseListener(priceClickListener);
		mouseManager.registerMouseListener(priceChartOverlay.mouse);
		keyManager.registerKeyListener(fillKeyListener);

		// Seed the panel's login state from the CURRENT game state rather than
		// waiting on a GameStateChanged event: enabling the plugin while
		// already sitting on the login screen fires no such event, which left
		// the sidebar showing empty advisor boxes instead of "log in to start".
		loggedIn = client.getGameState() == GameState.LOGGED_IN;
		/* Enabled while already in game: the slots are settled now, so the
		   sweep may believe an empty one from the next couple of ticks. */
		geSettledTick = loggedIn ? client.getTickCount() + GE_LOGIN_BURST_TICKS : -1;
		/* The instance outlives a disable, and so does the sweep's memory of
		   the slots — possibly another character's, from before a logout this
		   plugin never saw. Forget it, as a logout does. */
		java.util.Arrays.fill(sweptItem, -1);
		slotProgress.clear();
		bankReadThisLoginAt = 0;
		collectedAt.clear();
		setPanelLoggedIn(loggedIn);
		bankLegendOverlay.setEnabled(config.bankLegend());
		/* The gear menu's copy of the settings, now — not at the first
		   advisor cycle. Until then it showed the built-in defaults rather
		   than what was saved, and with the price API unreachable that cycle
		   never came. */
		pushSettings();
		refreshPanel();
		syncBridge();
		syncAdvisor();
	}

	@Override
	protected void shutDown()
	{
		/* First, so anything already queued on the shared executor — a
		   price retry, a range top-up, an on-demand fetch — finds the plugin
		   off and returns without touching the network. The executor is
		   RuneLite's, not ours, and outlives this plugin being disabled. */
		running = false;
		if (handoffTimer != null)
		{
			handoffTimer.shutdown(); // not shutdownNow(): a hub plugin does not interrupt threads
			handoffTimer = null;
		}
		handoff = null;
		final java.util.concurrent.Future<?> retry = bulkRetryTask;
		if (retry != null)
		{
			retry.cancel(false);
			bulkRetryTask = null;
		}
		bulkRetryPending.set(false);
		saveState();
		/* Before anything else is torn down. The plugin instance survives a
		   disable, so a sink left pointing at this ledger would keep writing
		   from a plugin the user has switched off. */
		tracker.setFlipSink(null);
		clientToolbar.removeNavigation(navButton);
		overlayManager.remove(bankOverlay);
		overlayManager.remove(bankLegendOverlay);
		overlayManager.remove(geGridOverlay);
		overlayManager.remove(gePriceOverlay);
		overlayManager.remove(priceChartOverlay);
		mouseManager.unregisterMouseListener(priceClickListener);
		mouseManager.unregisterMouseListener(priceChartOverlay.mouse);
		keyManager.unregisterKeyListener(fillKeyListener);
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
	private static final String BANK_KEY = "bankMemory";

	/**
	 * What was in your bank the last time you opened it, kept across logins.
	 *
	 * The client only hands a plugin the bank's contents while the bank
	 * interface is actually open — there is no "read my bank" call. So a
	 * fresh login knew about your loose inventory and nothing else: cash was
	 * whatever coins you happened to be carrying, holdings were empty, and
	 * the capital planner, which sizes every buy against cash, had nothing to
	 * work with. The panel sat on "Looking for flips…" until you walked to a
	 * bank, and then produced twenty-nine ideas at once off the same prices
	 * it had had the whole time.
	 *
	 * Remembering the last snapshot fixes that. It is a snapshot, not a fact:
	 * bankSeenAt says how old it is, and the card and the bridge both already
	 * carry that age.
	 */
	private static class BankMemory
	{
		long seenAt;
		long coins;
		long platinum;
		Map<Integer, Integer> items = new HashMap<>();
	}

	/**
	 * Per account, because restoring one character's bank onto another would
	 * be worse than knowing nothing — it would size buys against money that
	 * is not there and offer to sell stacks this character has never held.
	 *
	 * Keyed by account hash and capped: alts are common, and a single slot
	 * would mean two accounts evicting each other every login, which is the
	 * reported bug again for anyone who plays more than one.
	 */
	private static class BankMemories
	{
		Map<String, BankMemory> byAccount = new HashMap<>();
	}

	private static final int BANK_MEMORY_ACCOUNTS = 4;
	/** The JSON last written, so an open bank does not rewrite an identical
	 *  blob on every item that moves in or out of it. */
	private volatile String lastSavedBankJson;

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

	/** Every flip ever closed, on disk. See FlipLedger for why this is a file
	 *  and not part of the config blob above. */
	private volatile FlipLedger ledger;
	/** How many rows the ledger holds, so the website can tell whether its
	 *  copy of the history is stale without re-downloading it. Kept in step
	 *  with successful appends rather than re-counted, which would mean
	 *  re-reading the whole file on a poll. */
	private final java.util.concurrent.atomic.AtomicInteger ledgerCount =
		new java.util.concurrent.atomic.AtomicInteger();

	/**
	 * Open the permanent ledger, seeding it from the config blob the first
	 * time.
	 *
	 * The seed is what stops the upgrade from looking like a wipe: your last
	 * 500 flips already exist in config, and without this the history page
	 * would open empty on a tracker that has been running for months. It runs
	 * only while the ledger is empty, so it happens once and cannot duplicate
	 * — a flip booked from here on is appended live, and is already in the
	 * file by the next startup.
	 *
	 * What it cannot recover is anything the 500-cap already discarded. The
	 * ledger is complete from this moment on, and as complete as the config
	 * blob was before it.
	 */
	/** Every flip in the ledger, oldest first, or null until it has been
	 *  read (or when there is no ledger). Guarded by ledgerFlipsLock. */
	private List<Flip> ledgerFlips;
	private final Object ledgerFlipsLock = new Object();
	/** Flips whose append has not succeeded yet, oldest first. These three
	 *  are touched only by tasks on the (single-threaded) executor — see
	 *  openLedger. */
	private final List<Flip> pendingAppends = new ArrayList<>();
	/** flipKeys known to be in the ledger file. */
	private final java.util.Set<String> onDisk = new java.util.HashSet<>();
	/** flipKeys in ledgerFlips: on disk, or pending. */
	private final java.util.Set<String> inCache = new java.util.HashSet<>();

	/** The flips the stats and the list are worked out from: the whole
	 *  ledger once it is read, the tracker's recent window until then. */
	private List<Flip> flipsForRanges()
	{
		final List<Flip> cached;
		synchronized (ledgerFlipsLock)
		{
			cached = ledgerFlips != null ? new ArrayList<>(ledgerFlips) : null;
		}
		return cached != null ? mergeFlips(cached, tracker.getFlips()) : tracker.getFlips();
	}

	/**
	 * The ledger plus any of the tracker's recent flips it does not have —
	 * a write that failed, or flips booked on another computer whose saved
	 * state synced here while the ledger file did not. Never knows less than
	 * the tracker. Oldest first.
	 */
	static List<Flip> mergeFlips(List<Flip> ledger, List<Flip> recent)
	{
		if (recent == null || recent.isEmpty())
		{
			return ledger;
		}
		long since = Long.MAX_VALUE;
		for (Flip f : recent)
		{
			since = Math.min(since, f.closedAt);
		}
		final java.util.Set<String> have = new java.util.HashSet<>();
		for (int i = ledger.size() - 1; i >= 0 && ledger.get(i).closedAt >= since; i--)
		{
			have.add(flipKey(ledger.get(i)));
		}
		boolean added = false;
		final List<Flip> out = new ArrayList<>(ledger);
		for (Flip f : recent)
		{
			if (have.add(flipKey(f)))
			{
				out.add(f);
				added = true;
			}
		}
		if (added)
		{
			out.sort(java.util.Comparator.comparingLong(f -> f.closedAt));
		}
		return out;
	}

	static String flipKey(Flip f)
	{
		return f.offerId + "/" + f.closedAt + "/" + f.itemId + "/" + f.quantity + "/" + f.sellGross;
	}

	private void openLedger()
	{
		openLedger(new File(new File(RuneLite.RUNELITE_DIR, "pocketge-flip-tracker"), "flips.jsonl"));
	}

	/** Package-private for the tests, which give it a file of their own. */
	void openLedger(File file)
	{
		try
		{
			final FlipLedger open = new FlipLedger(file, gson);
			ledgerCount.set(open.count());
			ledger = open;
			/*
			 * Everything that writes the file, or decides whether a flip is
			 * in it, runs on the shared executor — single-threaded, so these
			 * tasks run one at a time in the order queued — and consults one
			 * record of what is on disk, onDisk. Three writers each deciding
			 * for themselves wrote flips twice: the startup rescue and a
			 * flip's own queued append, a retry and the rescue across a
			 * disable/enable, the seed and an old session's queued append.
			 *
			 * This task is queued before the sink below is installed, and
			 * after any append an earlier session left queued, so it sees
			 * the file as those left it.
			 */
			executor.execute(() ->
			{
				/* An empty file is seeded from the saved state, here rather
				   than on the startup thread so it is ordered after any
				   append still queued from before a disable. */
				if (open.isEmpty())
				{
					final int seeded = open.appendAll(tracker.getFlips());
					if (seeded > 0)
					{
						log.debug("PocketGE: seeded the flip ledger with {} flips from saved state", seeded);
					}
				}
				final List<Flip> read = open.readAllOrNull();
				final List<Flip> all = read != null ? new ArrayList<>(read) : open.readAll();
				onDisk.clear();
				for (Flip f : all)
				{
					onDisk.add(flipKey(f));
				}
				/* The tracker's flips the file lacks — an append that failed,
				   or flips booked on another computer whose state synced here
				   — written in now, so they stay in All time once 500 newer
				   flips push them out of the tracker. Only after a clean read:
				   a partial one makes every flip past the failure look missing.
				   A flip booked since the sink went in is caught here too, and
				   its own queued append then finds it in onDisk and skips. */
				if (read != null)
				{
					boolean added = false;
					for (Flip f : tracker.getFlips())
					{
						if (!onDisk.contains(flipKey(f)) && open.append(f))
						{
							onDisk.add(flipKey(f));
							all.add(f);
							added = true;
						}
					}
					if (added)
					{
						all.sort(java.util.Comparator.comparingLong(f -> f.closedAt));
					}
				}
				/* A retry the rescue has just written is done. */
				pendingAppends.removeIf(f -> onDisk.contains(flipKey(f)));
				inCache.clear();
				for (Flip f : all)
				{
					inCache.add(flipKey(f));
				}
				for (Flip f : pendingAppends)
				{
					if (inCache.add(flipKey(f)))
					{
						all.add(f);
					}
				}
				ledgerCount.set(open.count());
				synchronized (ledgerFlipsLock)
				{
					ledgerFlips = all;
				}
				refreshStatsAndFavorites();
			});
			/* Appended off the client thread: booking happens inside a GE
			   event, and that thread renders the game. One line of I/O is
			   small, but it is still I/O on the frame loop, and the ledger is
			   never on the critical path of anything.

			   The lambda closes over `open` rather than the field, so an
			   append queued on the executor goes to the ledger that was live
			   when the sink was installed, never to whatever the field
			   happens to hold by the time the task runs. */
			tracker.setFlipSink(flip -> executor.execute(() ->
			{
				/* Already in hand — the startup rescue wrote it while this
				   task waited behind it. */
				if (inCache.add(flipKey(flip)))
				{
					/* Cached whether or not the file takes it: the sidebar
					   keeps counting when the ledger cannot be written. */
					synchronized (ledgerFlipsLock)
					{
						if (ledgerFlips != null)
						{
							ledgerFlips.add(flip);
						}
					}
					pendingAppends.add(flip);
				}
				/* A write that failed is tried again with the next flip, so
				   it reaches the file rather than living only here. */
				for (java.util.Iterator<Flip> it = pendingAppends.iterator(); it.hasNext(); )
				{
					final Flip p = it.next();
					if (onDisk.contains(flipKey(p)))
					{
						it.remove();
						continue;
					}
					if (!open.append(p))
					{
						break;
					}
					onDisk.add(flipKey(p));
					it.remove();
					ledgerCount.incrementAndGet();
				}
				/* The refresh the fill itself asked for may have run before
				   this landed; one more, so the new flip is in the list. */
				refreshStatsAndFavorites();
			}));
		}
		catch (Exception e)
		{
			/* A plugin that will not load because a directory is read-only is
			   worse than one with no history page. Everything else — the
			   sidebar, the config blob, the 500-flip window — is untouched by
			   this failing. */
			log.warn("PocketGE: could not open the flip ledger; history will not be recorded", e);
			ledger = null;
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

	/** Write the bank snapshot for this character, keeping the other
	 *  characters' snapshots alongside it. */
	private void saveBank()
	{
		final long account = client.getAccountHash();
		if (account == -1)
		{
			return; // not logged in to anything; nothing to file it under
		}
		try
		{
			final BankMemories all = readBankMemories();
			final BankMemory mine = new BankMemory();
			mine.seenAt = bankSeenAt;
			mine.coins = lastBankCoins;
			mine.platinum = lastBankPlatinum;
			mine.items = new HashMap<>(lastBank);
			all.byAccount.put(String.valueOf(account), mine);
			/* Oldest-first eviction, so the account you actually play keeps
			   its snapshot and a character you logged into once loses it. */
			while (all.byAccount.size() > BANK_MEMORY_ACCOUNTS)
			{
				String oldest = null;
				long oldestAt = Long.MAX_VALUE;
				for (Map.Entry<String, BankMemory> e : all.byAccount.entrySet())
				{
					if (e.getValue() != null && e.getValue().seenAt < oldestAt)
					{
						oldestAt = e.getValue().seenAt;
						oldest = e.getKey();
					}
				}
				if (oldest == null)
				{
					break;
				}
				all.byAccount.remove(oldest);
			}
			final String json = gson.toJson(all);
			/* An open bank fires a container change for every item that
			   moves. Most of those leave the snapshot identical — and the
			   ones that do not are still one small blob, so the guard is
			   about write churn rather than size. */
			if (json.equals(lastSavedBankJson))
			{
				return;
			}
			configManager.setConfiguration(PocketGeTrackerConfig.GROUP, BANK_KEY, json);
			lastSavedBankJson = json;
		}
		catch (Exception e)
		{
			log.warn("PocketGE: could not remember your bank", e);
		}
	}

	private BankMemories readBankMemories()
	{
		try
		{
			final String json = configManager.getConfiguration(PocketGeTrackerConfig.GROUP, BANK_KEY);
			if (json != null && !json.isEmpty())
			{
				final BankMemories all = gson.fromJson(json, BankMemories.class);
				if (all != null && all.byAccount != null)
				{
					return all;
				}
			}
		}
		catch (Exception e)
		{
			log.warn("PocketGE: could not read the remembered bank", e);
		}
		return new BankMemories();
	}

	/**
	 * Put last session's bank back, so there is something to advise on before
	 * you have walked to a banker.
	 *
	 * Deliberately does nothing once the real bank has been read this
	 * session: a live container always outranks a remembered one, and this
	 * runs on LOGGED_IN, which can fire again (hopping worlds) long after you
	 * have banked.
	 */
	private void restoreBank()
	{
		if (bankSeen)
		{
			return;
		}
		final long account = client.getAccountHash();
		if (account == -1)
		{
			return;
		}
		final BankMemory mine = readBankMemories().byAccount.get(String.valueOf(account));
		if (mine == null || mine.seenAt <= 0)
		{
			return;
		}
		lastBank.clear();
		if (mine.items != null)
		{
			lastBank.putAll(mine.items);
		}
		lastBankCoins = mine.coins;
		lastBankPlatinum = mine.platinum;
		bankSeenAt = mine.seenAt;
		/* TRUE: there is bank data to reason about. How old it is lives in
		   bankSeenAt, which the card and the bridge already carry — this flag
		   only ever meant "do we know anything", and now we do. */
		bankSeen = true;
	}

	/**
	 * React to the ONE setting that changed, and only to settings.
	 *
	 * This used to restart the bridge and the advisor on every write to the
	 * group, whatever the key — and most writes to the group are not
	 * settings. The plugin stores its own data here too: the trade state on
	 * every offer update, the bank snapshot every time the bank changes, the
	 * favourite lists every time the website stars an item. Each of those
	 * restarted the advisor schedule with no initial delay, which is an
	 * immediate fetch of /latest, /volumes and /24h plus a round of per-item
	 * history calls — dozens of requests to the wiki for a bank deposit. And
	 * each one stopped and restarted the bridge's HTTP server, including from
	 * inside the bridge's own request handler when the website wrote a
	 * favourite.
	 */
	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!PocketGeTrackerConfig.GROUP.equals(event.getGroup()) || event.getKey() == null)
		{
			return;
		}
		switch (event.getKey())
		{
			case "localBridge":
			case "bridgePort":
				syncBridge();
				break;
			case "advisor":
			case "adjustInterval":
				syncAdvisor();
				break;
			case "bankHighlights":
				bankOverlay.setEnabled(config.bankHighlights());
				break;
			case "holdingTips":
				bankOverlay.setHoldingTips(config.holdingTips());
				break;
			case "bankLegend":
				bankLegendOverlay.setEnabled(config.bankLegend());
				break;
			case "colourTheme":
			case "showFlipScore":
				/* Drawing preferences, not inputs to the ranking. The panel's
				   own buttons already push these; this is for the same change
				   made from the wrench menu. (The settings themselves are
				   pushed below.) */
				pushBankTheme();
				refreshPanel();
				break;
			case "showBadges":
				SwingUtilities.invokeLater(() -> mainPanel.setBadgesEnabled(config.showBadges()));
				break;
			case "blocklist":
			case "minProfit":
				/* The floor and the blocklist are applied inside Advisor, so
				   the suggestions on screen were built against the old ones —
				   re-rank now from the prices already held, rather than
				   leaving a sub-floor or blocked idea up until the next cycle. */
				recomputeAdvice();
				break;
			case "favoriteLists":
			case "activeFavoriteList":
				/* Usually written by the plugin itself, which has already
				   refreshed — but both are also editable in the wrench menu.
				   No network: this redraws from what is held. */
				refreshStatsAndFavorites();
				/* The stars on the suggestion cards are drawn from the active
				   list, and only a recompute hands the cards a new one. No
				   price round: at most a 30-day range top-up for a plan
				   position whose cached range has expired. */
				recomputeAdvice();
				break;
			default:
				/* The plugin's own data (state, bankMemory, watchlistRows),
				   the legacy favorites CSV, and settings read fresh wherever
				   they are used (priceAlerts, reuseBrowserTab). */
				break;
		}
		/* The panel keeps its own copy of these for its settings popup and
		   its block-confirm dialog. Every config write used to restart the
		   advisor, which re-pushed that copy as a side effect; now that it
		   does not, a change made in the wrench menu is pushed here. */
		if (PANEL_SETTING_KEYS.contains(event.getKey()))
		{
			pushSettings();
		}
	}

	/** The settings AdvisorPanel.Settings carries — see buildSettings. */
	private static final Set<String> PANEL_SETTING_KEYS = Set.of(
		"advisor", "adjustInterval", "blocklist", "localBridge", "bridgePort",
		"minProfit", "colourTheme", "showFlipScore", "confirmBlock", "bankLegend",
		"bankHighlights", "holdingTips", "priceChart", "showBadges", "priceAlerts", "reuseBrowserTab", "followGeOffer");

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
				mainPanel.updateSuggestions(new ArrayList<>(), favoriteIdSet(), buildSettings());
				mainPanel.updateRecommendations(new ArrayList<>());
				mainPanel.updateGeSlots(null);
				mainPanel.updateFinder(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
			});
			bankOverlay.setSuggestions(new HashMap<>());
			bankOverlay.setHoldings(new HashMap<>());
			geGridOverlay.setSlots(null);
			lastTopRecommendation = null;
			refreshStatsAndFavorites(); // portfolio/favorites still work fully offline (cash + whatever's cached)
			return;
		}
		perItemOwed = true;
		if (!lastQuotes.isEmpty())
		{
			/* Redraw from the prices already held. Switching the advisor off
			   cleared the cards, and the first tick below may be most of a
			   minute away — the panel should not sit saying "Advisor off"
			   for that long when nothing it needs is missing. */
			recomputeAdvice();
		}
		final int period = Math.max(60, config.adjustInterval().seconds());
		/* The first tick waits out whatever is left of a minute since the last
		   fetch, rather than firing at once. Clicking through the interval
		   buttons, or flicking the advisor off and on, restarts this schedule
		   each time — and each restart used to be a fresh round of requests. */
		final long sinceLast = System.currentTimeMillis() - lastQuotesAt;
		final long firstMs = lastQuotesAt == 0 ? 0 : Math.max(0, Math.min(period * 1000L, BULK_MIN_GAP_MS - sinceLast));
		advisorTask = executor.scheduleWithFixedDelay(this::refreshPrices, firstMs, period * 1000L, TimeUnit.MILLISECONDS);
	}

	/** No two rounds of the bulk price calls closer together than this, from
	 *  any caller. The wiki's /latest moves every few seconds, but nothing in
	 *  the plugin acts on a difference that small. */
	private static final long BULK_MIN_GAP_MS = 60_000;
	/** When logged out the sidebar shows "log in to start", and the only
	 *  thing still reading prices is the bridge's portfolio total — so the
	 *  book is refreshed this rarely, and the per-item calls not at all. */
	private static final long LOGGED_OUT_REFRESH_MS = 15 * 60 * 1000L;
	/** Mirrors the panel's logged-in state: set on LOGGED_IN, cleared on the
	 *  login screen, and left alone through loading and world hops. Read from
	 *  the advisor's background thread, so a field rather than a client call. */
	private volatile boolean loggedIn;
	/** When the bulk calls were last ATTEMPTED, success or not, so a failing
	 *  API is not retried by every caller that notices the book is stale. */
	private volatile long lastBulkAttemptAt;

	/** A per-item round (5D extremes, engine series) is due regardless of the
	 *  book's age: set at startup, on logout and when the advisor is switched
	 *  on, and after every fresh book, and cleared when a round runs logged
	 *  in. Without it a launch on the login screen — RuneLite's ordinary
	 *  start — fetched the book logged out, skipped the per-item calls, and
	 *  then had nothing prompting them at login until the next scheduled
	 *  tick, up to eight hours away on the longest interval. */
	private volatile boolean perItemOwed = true;
	/** One pending retry at a time; see scheduleBulkRetry. */
	private final java.util.concurrent.atomic.AtomicBoolean bulkRetryPending = new java.util.concurrent.atomic.AtomicBoolean();
	/** The pending retry itself, so shutDown can cancel it. */
	private volatile java.util.concurrent.Future<?> bulkRetryTask;
	/**
	 * True between startUp and shutDown.
	 *
	 * Every task this plugin puts on RuneLite's shared executor checks it
	 * before going to the network. The executor is not the plugin's to shut
	 * down, so a task queued a moment before the plugin was disabled would
	 * otherwise still run — and the bulk retry reschedules itself, so one
	 * queued while the Wiki was unreachable kept a disabled plugin asking
	 * for prices indefinitely.
	 */
	private volatile boolean running;

	/** Background thread: the scheduled tick. */
	private void refreshPrices()
	{
		refreshPrices(0);
	}

	/**
	 * Background thread: fetch live prices if they are due, run the per-item
	 * round if it is owed, then recompute on the client thread.
	 *
	 * @param bookMaxAgeMs reuse the held book if it is younger than this; 0
	 *                     (the scheduled tick) always wants a fresh one. The
	 *                     one-minute floor between rounds applies either way.
	 */
	private void refreshPrices(long bookMaxAgeMs)
	{
		if (!running || !config.advisor())
		{
			return;
		}
		final long now = System.currentTimeMillis();
		final boolean inGame = loggedIn;
		/* Logged out the sidebar shows "log in to start" and nothing on it
		   needs a fresh price; the bridge's portfolio total can live with a
		   quarter-hour-old book. */
		final long maxAge = inGame ? bookMaxAgeMs : LOGGED_OUT_REFRESH_MS;
		final boolean bookWanted = lastQuotes.isEmpty() || now - lastQuotesAt >= maxAge;
		boolean fetched = false;
		if (bookWanted && now - lastBulkAttemptAt >= BULK_MIN_GAP_MS)
		{
			lastBulkAttemptAt = now;
			try
			{
				/* Assigned only once all three are in hand. A failure part-way
				   used to replace the book with an empty map, which cleared
				   every suggestion on screen and made scheduleAdviceNow fetch
				   again on the next click. Now a failed round leaves the last
				   good prices standing. */
				final Map<Integer, Advisor.Quote> quotes = marketClient.fetchLatest();
				stopIfSwitchedOff();
				final Map<Integer, Long> volumes = marketClient.fetchVolumes();
				stopIfSwitchedOff();
				final Map<Integer, AnalystRating.Average> averages = marketClient.fetch24hAverages();
				lastQuotes = quotes;
				lastQuotesAt = System.currentTimeMillis();
				lastVolumes = volumes;
				lastAverages = averages;
				fetched = true;
				perItemOwed = true;
			}
			catch (SwitchedOff off)
			{
				return; // switched off mid-round: nothing to report, nothing to retry
			}
			catch (Exception e)
			{
				if (e instanceof MarketClient.BackingOff)
				{
					log.debug("PocketGE advisor: {}", e.getMessage());
				}
				else
				{
					log.warn("PocketGE advisor: price fetch failed", e);
				}
				SwingUtilities.invokeLater(() -> mainPanel.setAdvisorStatus("Couldn't reach the price API — will retry"));
				/* No recompute here, even with prices held: a recompute
				   clears the status line, and this one has to stay up to say
				   the prices on the cards are no longer being refreshed.
				   syncAdvisor already redraws from held prices when the
				   advisor is switched back on. */
				scheduleBulkRetry();
				return;
			}
		}
		else if (lastQuotes.isEmpty())
		{
			/* Asked inside the minute after a failed round — a login straight
			   after the startup fetch failed, say. Declining outright left
			   "will retry" on screen until the next scheduled tick, which can
			   be hours away; this is that retry, at the earliest allowed. */
			scheduleBulkRetry();
			return;
		}
		/* The per-item history calls are the expensive ones, and everything
		   they feed — the badges, the engine's targets, the offer screen — is
		   on a screen you can only see logged in. */
		if (inGame && perItemOwed)
		{
			perItemOwed = false;
			refreshDayExtremes();
			refreshOfferSeries();
		}
		else if (!inGame && !fetched)
		{
			return; // logged out and nothing new: nothing to recompute
		}
		recomputeAdvice();
	}

	/** Between the bulk calls: a round under way when the plugin or the
	 *  advisor is switched off sends nothing more. */
	private void stopIfSwitchedOff() throws SwitchedOff
	{
		if (!running || !config.advisor())
		{
			throw new SwitchedOff();
		}
	}

	/** See stopIfSwitchedOff: not a failure, so no "couldn't reach" and no
	 *  retry. */
	private static final class SwitchedOff extends java.io.IOException
	{
	}

	/**
	 * Try the book again once the minute floor and any back-off have passed —
	 * but only while there is no book at all. With prices held, the ordinary
	 * schedule is the retry, and an extra one would just be extra traffic.
	 */
	private void scheduleBulkRetry()
	{
		final ScheduledExecutorService ex = executor;
		if (!running || ex == null || !lastQuotes.isEmpty() || !bulkRetryPending.compareAndSet(false, true))
		{
			return;
		}
		final long wait = Math.max(1_000L, Math.max(
			BULK_MIN_GAP_MS - (System.currentTimeMillis() - lastBulkAttemptAt),
			marketClient.backoffRemainingMs()));
		try
		{
			bulkRetryTask = ex.schedule(() ->
			{
				bulkRetryPending.set(false);
				bulkRetryTask = null;
				refreshPrices();
			}, wait, TimeUnit.MILLISECONDS);
		}
		catch (RuntimeException e)
		{
			bulkRetryPending.set(false); // executor shut down under us
		}
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
	 * make on the way back out, so it is priced against the sell the card and
	 * pocketge.com would quote rather than a cost basis, and labelled "if it
	 * flips" to say so.
	 */
	/** slot -> {units filled when we last looked, when that last changed}.
	 *  Plugin-local and deliberately not persisted: it answers "is this
	 *  offer moving right now", which is a question about this sitting, and a
	 *  stale answer restored from disk would silence a nag it should not. */
	private final Map<Integer, long[]> slotProgress = new HashMap<>();
	/** How long a fill buys an offer out of the repricing nag. Long enough to
	 *  cover the gaps between trickling fills on a slow item, short enough
	 *  that a genuinely stalled offer is flagged within one re-check. */
	private static final long FILL_GRACE_MS = 10 * 60_000L;

	/**
	 * Whether this slot has sold or bought anything lately.
	 *
	 * Measured by watching the filled count move rather than by reading a
	 * timestamp off the offer, because the game does not give one — the
	 * quantity going up IS the event, and the first time we see a new number
	 * is the closest thing to when it happened.
	 */
	private boolean filledRecently(int slot, int filledNow)
	{
		return filledRecently(slotProgress, slot, filledNow, System.currentTimeMillis());
	}

	static boolean filledRecently(Map<Integer, long[]> slotProgress, int slot, int filledNow, long now)
	{
		final long[] prev = slotProgress.get(slot);
		if (prev == null || filledNow < prev[0])
		{
			/* New offer in this slot, the slot reused, or the first look since
			   logging in — start the clock WITHOUT claiming it just filled.
			   It did claim it: whatever the offer had already bought, it said
			   "filling now". So an offer that filled overnight at 317 and then
			   sat under a market that had moved to 341 read "Priced fine —
			   leave it" for the first ten minutes of the morning, exactly when
			   you look. Units found already bought say nothing about when. */
			slotProgress.put(slot, new long[]{filledNow, 0});
			return false;
		}
		if (filledNow > prev[0])
		{
			/* More than last looked, but this pass cannot say WHEN — it
			   only runs with the advisor, and there can be an hour between
			   passes (advisor off, no prices). The fill event dates fills;
			   see noteFill. Recorded, keeping the last dated fill. */
			slotProgress.put(slot, new long[]{filledNow, prev[1]});
		}
		return prev[1] > 0 && now - prev[1] < FILL_GRACE_MS;
	}

	/**
	 * A fill, dated: the GE's own event for an offer's count going up,
	 * which fires as the fill happens — advisor on or off. Not during the
	 * login replay, whose fills happened while logged out.
	 */
	static void noteFill(Map<Integer, long[]> slotProgress, int slot, int filledNow, long now)
	{
		final long[] prev = slotProgress.get(slot);
		if (prev == null || filledNow < prev[0])
		{
			slotProgress.put(slot, new long[]{filledNow, 0});
		}
		else if (filledNow > prev[0])
		{
			slotProgress.put(slot, new long[]{filledNow, now});
		}
	}

	private Map<Integer, GeOfferGridOverlay.SlotView> buildSlotViews(
		List<Advisor.OfferView> offers, Map<Integer, Boolean> slotStatus, Map<Integer, long[]> openBuys,
		Map<Integer, Advisor.Suggestion> adjustBySlot, Map<Integer, TradeEngine.Result> engineCache)
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
			/*
			 * An offer that is FILLING is not mispriced, whatever the book
			 * says.
			 *
			 * The drift check compares your price against the live quote, and
			 * on that test a sell listed above the current bid looks stranded.
			 * But a seller asking 972 with 2,767 of 11,000 already gone is
			 * being reached by the market — the evidence of the fills beats
			 * the inference from the spread, and telling them to abort a
			 * working offer and re-list LOWER is advice that costs real gold
			 * to follow.
			 *
			 * So progress silences the nag while it lasts. Not forever: an
			 * offer that filled an hour ago and has sat still since genuinely
			 * has drifted, and the border comes back once the fills stop.
			 */
			final Advisor.Suggestion adj = adjustBySlot.get(o.slot);
			/* Except a buy that fills at a LOSS: filling is the problem there,
			   so fills are no reason to go quiet about it. */
			final boolean losing = adj != null && adj.losing;
			/* Sampled for every working offer, every pass — not only once it
			   is flagged. Asked only of flagged slots, fills watched while the
			   box was grey were never recorded, so the first flag on an offer
			   filling steadily found no record and nagged it to re-list. */
			final boolean filling = filledRecently(o.slot, o.quantitySold);
			if (v.needsAdjust && !losing && filling)
			{
				v.needsAdjust = false;
			}
			/* The numbers behind the red border, so the tooltip can say what
			   to do rather than only that something is wrong. Taken from the
			   very suggestion the border is derived from, so the colour and
			   the explanation cannot drift apart. */
			if (adj != null && v.needsAdjust)
			{
				v.offerPrice = o.price;
				v.targetPrice = adj.price;
				v.losing = losing;
				v.breakEven = adj.breakEven;
				final Advisor.Quote adjQ = lastQuotes.get(o.itemId);
				if (!losing && adjQ != null && adjQ.high > 0 && adjQ.low > 0 && adj.price > 0)
				{
					/* A buy's margin against the same exit its "if it flips"
					   line uses, or one slot could say "No margin left" and
					   "+70K if it flips" at once. */
					final long exit = o.buy ? flipExit(o.itemId, adjQ, engineCache) : 0;
					final long edge = o.buy
						? exit - FlipTracker.taxPerItem(exit, o.itemId) - adj.price
						: adj.price - FlipTracker.taxPerItem(adj.price, o.itemId) - adjQ.low;
					v.noMargin = edge <= 0;
				}
			}

			final long unitNet = o.price - FlipTracker.taxPerItem(o.price, o.itemId);
			if (!o.buy)
			{
				/*
				 * What the whole offer fetches, at the price the tooltip is
				 * talking about: the target when the advisor wants one, the
				 * listed price otherwise. Quoting the listed price under a
				 * line that says "lower your ask to 1,030" would price a
				 * listing the player is being told to abandon.
				 *
				 * Computed here rather than in the overlay because the tax
				 * table is here — taxPerItem needs the item id, and exempt
				 * items would otherwise be quoted 2% light.
				 */
				final long quoted = v.targetPrice > 0 ? v.targetPrice : o.price;
				v.grossValue = (long) o.totalQuantity
					* (quoted - FlipTracker.taxPerItem(quoted, o.itemId));
			}
			if (!o.buy)
			{
				final long[] lot = openBuys.get(o.itemId);
				if (lot != null && lot.length >= 2 && lot[0] > 0 && lot[1] > 0)
				{
					/* Only over the units the plugin watched being bought — the
					   same rule as every sell card. A partly tracked offer
					   claimed profit on all of it. */
					final long unitCost = (long) Math.ceil(lot[1] / (double) lot[0]);
					v.projectedProfit = Math.min((long) o.totalQuantity, lot[0]) * (unitNet - unitCost);
					v.filledProfit = Math.min((long) o.quantitySold, lot[0]) * (unitNet - unitCost);
				}
			}
			else
			{
				/*
				 * What this buy is worth once flipped. No cost basis needed —
				 * you are establishing it right now.
				 *
				 * Sold at the engine's target, the SELL @ the card shows and
				 * pocketge.com's target sell, falling back to the last
				 * insta-buy only when there is no series to price from. It was
				 * always the insta-buy, which on a Mithril bar bought at 876
				 * meant 900 here against the site's 896: "+60K if it flips" in
				 * the Exchange beside "+30K" on the website for the same
				 * offer, the difference being 4 gp an item on 10,000.
				 */
				final Advisor.Quote q = lastQuotes.get(o.itemId);
				final long exit = flipExit(o.itemId, q, engineCache);
				if (exit > 0)
				{
					final long exitNet = exit - FlipTracker.taxPerItem(exit, o.itemId);
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
	/** Config key for the watchlist's dragged height. Not a @ConfigItem —
	 *  it is panel state, not a preference to go hunting for in the wrench
	 *  menu. */
	private static final String WATCHLIST_ROWS_KEY = "watchlistRows";

	/**
	 * The remembered watchlist height, or 0 ("show all them all") for a fresh
	 * install or a stored value that cannot be parsed.
	 *
	 * Never throws. A preference that got corrupted must not be able to stop
	 * the panel from being built — the worst it can cost is a watchlist that
	 * opens at full height.
	 */
	private int readWatchlistRows()
	{
		try
		{
			final Integer n = configManager.getConfiguration(
				PocketGeTrackerConfig.GROUP, WATCHLIST_ROWS_KEY, Integer.class);
			return n != null && n > 0 ? n : 0;
		}
		catch (Exception e)
		{
			return 0;
		}
	}

	private void scheduleAdviceNow()
	{
		final ScheduledExecutorService ex = executor;
		if (ex == null || !config.advisor())
		{
			return;
		}
		/* A Runnable, spelled out: refreshPrices is overloaded, so a bare
		   method reference here is ambiguous between the two submits. */
		final Runnable task = lastQuotes.isEmpty() ? () -> refreshPrices() : this::recomputeAdvice;
		ex.submit(task);
	}

	/** One /timeseries call per item with an active GE offer as of the last
	 *  recomputeAdvice() cycle, plus the sell cards the sidebar is offering
	 *  and whatever item is sitting in an open GE offer screen — bounded to
	 *  at most 8 + 8 + 8 + 4 + 2 (the GE slot count, the plan, the sell cap,
	 *  the first flip ideas and two singletons), with heavy overlap between
	 *  them; the same
	 *  "small, bounded" shape as refreshDayExtremes above. Feeds TradeEngine
	 *  so ADJUST_BUY/ADJUST_SELL/SELL and the GE-context price can all
	 *  reprice to pocketge.com's own target instead of the raw live quote
	 *  (see Advisor.advise, onScriptPostFired). No staleness TTL needed —
	 *  the set is already tiny, so it's cheap to refetch every advisor cycle
	 *  rather than track a separate timer. */
	private void refreshOfferSeries()
	{
		final Set<Integer> ids = new HashSet<>(lastActiveOfferItemIds);
		ids.addAll(lastSellItemIds);
		final Integer geItem = geContextItemId;
		if (geItem != null)
		{
			ids.add(geItem);
		}
		/* The watchlist item you are inspecting. Fetched on demand the instant
		   you click it (see onSelectedItemChanged); re-fetched here so a card
		   left open keeps up with the market instead of holding the series it
		   was opened with. One more singleton — the bound is 8 + 3. */
		final Integer inspected = selectedFavoriteItemId;
		if (inspected != null)
		{
			ids.add(inspected);
		}
		/*
		 * The items the capital plan is actually recommending.
		 *
		 * Without these the headline BUY card was the last thing in the plugin
		 * still priced straight off the raw /latest spread — buy at the last
		 * insta-sell, sell at the last insta-buy — which assumes you are the
		 * marginal fill on both sides at once. The engine exists precisely
		 * because that is optimistic, and every other surface already asked
		 * it: the adjust nudges, the offer-screen chip, the inspection card.
		 *
		 * Bounded by free slots, so at most 8 and usually two or three; they
		 * also overlap heavily with the active-offer ids already in this set.
		 * Like lastActiveOfferItemIds, this is what the PREVIOUS cycle
		 * planned, because plan-building needs the client thread and this runs
		 * on the executor — so a brand-new idea shows raw numbers for one
		 * cycle before the engine confirms or drops it.
		 */
		ids.addAll(lastPlanItemIds);
		ids.addAll(lastIdeaItemIds);
		final Map<Integer, TradeEngine.Series> previous = lastOfferSeries;
		final Map<Integer, TradeEngine.Series> out = new HashMap<>();
		final long now = System.currentTimeMillis();
		boolean backingOff = false;
		for (Integer id : ids)
		{
			/* Reused while it is younger than one 5-minute bucket: the series
			   cannot have gained a point yet, so asking again returns the same
			   bytes. On a one-minute advisor interval this was every item,
			   every minute. */
			final TradeEngine.Series held = previous.get(id);
			final Long at = offerSeriesFetchedAt.get(id);
			if (held != null && at != null && now - at < OFFER_SERIES_TTL_MS)
			{
				out.put(id, held);
				continue;
			}
			if (!running || !config.advisor())
			{
				backingOff = true; // switched off mid-round: send nothing more
			}
			if (!backingOff)
			{
				try
				{
					out.put(id, marketClient.fetchTimeseries5m(id));
					offerSeriesFetchedAt.put(id, now);
					continue;
				}
				catch (MarketClient.BackingOff b)
				{
					backingOff = true; // the API asked us to wait: no more calls this round
				}
				catch (Exception e)
				{
					log.warn("PocketGE advisor: timeseries fetch failed for item {}", id, e);
				}
			}
			if (held != null)
			{
				out.put(id, held); // an old series beats none: it only steers a target
			}
		}
		offerSeriesFetchedAt.keySet().retainAll(out.keySet());
		lastOfferSeries = out;
	}

	/** One 5-minute bucket, less a little so a 5-minute advisor cycle that
	 *  lands a moment early still refreshes on every tick. */
	private static final long OFFER_SERIES_TTL_MS = 4 * 60 * 1000L + 30_000L;
	private final Map<Integer, Long> offerSeriesFetchedAt = new java.util.concurrent.ConcurrentHashMap<>();

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
		/* Capped, in the list's own order. A watchlist can grow without
		   limit and every entry here is one request an hour, for as long as
		   the client is open. */
		final Set<Integer> favIds = new LinkedHashSet<>();
		for (Integer id : favoriteIdSet())
		{
			if (favIds.size() >= MAX_EXTREME_FAVORITES)
			{
				break;
			}
			favIds.add(id);
		}
		final Set<Integer> trackedIds = new HashSet<>(favIds);
		trackedIds.addAll(extremeCandidatePool());
		dayExtremes.keySet().retainAll(trackedIds); // drop ids no longer tracked
		dayExtremesDueAt.keySet().retainAll(trackedIds);
		final long now = System.currentTimeMillis();
		/* Your own watchlist first, the volume pool after.
		 *
		 * trackedIds is a HashSet, so this used to walk favourites and pool
		 * items interleaved in hash order. Every one of these is a separate
		 * request, and if the run is going to be cut short — a rate limit, a
		 * network blip, the client shutting down — the half that gets served
		 * should be the half you are looking at. The pool only feeds the
		 * finder lists; the favourites are the rows with the badges on them. */
		final List<Integer> ordered = new ArrayList<>(favIds);
		for (Integer id : trackedIds)
		{
			if (!favIds.contains(id))
			{
				ordered.add(id);
			}
		}
		/*
		 * Each item on its own clock, a few per cycle.
		 *
		 * This was one timer for the whole set: every half hour the pass went
		 * stale and re-fetched all of it at once — up to forty pool items plus
		 * every favourite in one burst, from every client, on the same tick
		 * of their schedule. Now an item is due an hour (plus a random few
		 * minutes) after ITS last fetch, so a client's requests spread out
		 * over the hour instead of arriving together, and no single cycle
		 * sends more than MAX_EXTREME_FETCHES_PER_CYCLE.
		 */
		/*
		 * Never-fetched first (favourites before pool), then the most overdue.
		 * Walking the list from the top every cycle starved its tail: on a
		 * two-hour interval the first fifteen items were due again every
		 * cycle and took every slot, so item sixteen onward was never asked
		 * for at all.
		 */
		/* Half an interval of slack. An item comes due an hour plus a few
		   random minutes after its fetch, but the ticks are an interval
		   apart, so on the 30-minute interval the tick at the hour always
		   fell a few minutes short and the item waited for the next one —
		   ninety minutes, not sixty. Taking it at the tick nearest its due
		   time keeps the hour. */
		final long periodMs = Math.max(60, config.adjustInterval().seconds()) * 1000L;
		/* Capped at a quarter of the TTL. Uncapped, half of an 8-hour
		   interval is four hours, so a per-item round run between ticks — a
		   relog, the advisor toggled, the interval changed — found every item
		   "due" straight after its fetch and asked for the whole list again.
		   The long intervals need no slack anyway: their ticks are already
		   more than an hour apart. */
		final long slack = Math.min(periodMs / 2, DAY_EXTREMES_TTL_MS / 4);
		final List<Integer> due = new ArrayList<>();
		for (Integer id : ordered)
		{
			final Long at = dayExtremesDueAt.get(id);
			if (at == null || now + slack >= at)
			{
				due.add(id);
			}
		}
		due.sort(java.util.Comparator.comparingLong(id ->
		{
			final Long at = dayExtremesDueAt.get(id);
			return at == null ? Long.MIN_VALUE : at;
		})); // stable: ties keep favourites ahead of the pool
		final int cap = extremesPerCycle(ordered.size(), periodMs);
		int sent = 0;
		for (Integer id : due)
		{
			if (sent >= cap)
			{
				break; // the rest are picked up on the next cycle
			}
			if (!running || !config.advisor())
			{
				break; // switched off mid-round: stop here, not after the list
			}
			sent++;
			try
			{
				/* Only a SUCCESSFUL fetch is cached. fetchRecentExtremes used
				   to answer a failed request with an all-zero result, which
				   landed here as a perfectly good-looking entry, and that item
				   wore no badge for the rest of the TTL. It throws now, so a
				   failure leaves the slot as it was. */
				dayExtremes.put(id, marketClient.fetchRecentExtremes(id));
				dayExtremesDueAt.put(id, now + DAY_EXTREMES_TTL_MS
					+ java.util.concurrent.ThreadLocalRandom.current().nextLong(DAY_EXTREMES_JITTER_MS));
			}
			catch (MarketClient.BackingOff b)
			{
				break; // the API asked us to wait: no more calls this round
			}
			catch (Exception e)
			{
				/* Not retried on the very next cycle — which on a one-minute
				   interval was once a minute, for good, for an item the API
				   keeps refusing. */
				dayExtremesDueAt.put(id, now + DAY_EXTREMES_RETRY_MS);
				log.warn("PocketGE advisor: recent extremes fetch failed for item {}", id, e);
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
		if (!running || mainPanel == null || !config.advisor())
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
			/* One read of the open lots for the whole cycle. Three places
			   below need them — the advisor, the sell rows and the re-list
			   cards — and the tracker copies the map out on every call. */
			final Map<Integer, long[]> openLots = tracker.getHeldCost(heldForCost(holdings, offers));
			/* Every series this cycle can see, from both bounded caches, so
			   the sidebar's sell list and the headline card cannot price the
			   same stack differently. Offer series win: they are refetched
			   every cycle, the on-demand ones can be several minutes old.

			   Built here rather than beside sellCandidates below because
			   advise() was being handed lastOfferSeries alone — so the two
			   paths could, and did, disagree about an item the inspection
			   card had fetched on demand. One map, both callers. */
			final Map<Integer, TradeEngine.Series> cycleSeries = new HashMap<>(selectedSeries);
			cycleSeries.putAll(lastOfferSeries);
			final List<Advisor.Suggestion> suggestions = Advisor.advise(
				nowSec, quotes, meta, cash, holdings, offers,
				skipped, blockedIds, minVol, 0.01, MAX_BUY_IDEAS, openLots, cycleSeries,
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
			/* The player's profit floor applies to the planned buys too. It
			   used to reach only Advisor.advise's ideas, so with the floor at
			   1M the headline BUY cards — which come from this plan — still
			   offered positions worth a few thousand. Auto (0) leaves the
			   planner's own 2,000 gp floor as the only one. */
			final long profitFloor = config.minProfit().gp();
			final List<CapitalPlanner.Position> planPositions = new ArrayList<>();
			for (CapitalPlanner.Position pos : capitalPlan.positions)
			{
				if (profitFloor <= 0 || pos.expectedProfit >= profitFloor)
				{
					planPositions.add(pos);
				}
			}
			/* Hand the NEXT background fetch the items this plan names, so
			   their price series are in hand and the engine can confirm or
			   reject them — see refreshOfferSeries. */
			final Set<Integer> planIds = new HashSet<>();
			for (CapitalPlanner.Position pos : planPositions)
			{
				planIds.add(pos.id);
			}
			lastPlanItemIds = planIds;
			/* One engine verdict per item per cycle, shared by the plan
			   positions and the queued ideas below. */
			final Map<Integer, TradeEngine.Result> engineCache = new HashMap<>();
			// Same scoring advise() uses for its single best sell — the box
			// just shows several of them instead of one.
			final List<Advisor.Suggestion> sellRows = Advisor.sellCandidates(
				nowSec, quotes, meta, holdings, offers, skipped, blockedIds, openLots,
				cycleSeries);

			/* One stream, sells first. Both answer "what's the best use of a
			   slot right now", but a sell needs no capital and frees some, so
			   it outranks a buy of equal size. The capital plan still sizes
			   the buys against liquid cash and free slots — that just isn't a
			   separate thing the player has to look at any more. */
			final List<AdvisorPanel.Rec> recommendations = new ArrayList<>();
			/*
			 * With every slot busy, a new flip is not advice — it is a thing
			 * you cannot do.
			 *
			 * Both a buy and a sell need a free slot, so on a full board the
			 * only moves left are on the offers already out there: re-list
			 * the ones that have drifted off the market, collect the ones
			 * that are done. Leading with a fresh buy in that state is how a
			 * free-to-play account with three slots gets told to place a
			 * fourth offer.
			 *
			 * The queued buys still follow, because paging through what is
			 * worth having ready when a slot frees is most of what the Next
			 * button is for — they just stop being the headline.
			 */
			if (freeSlots <= 0)
			{
				for (Advisor.Suggestion adj : suggestions)
				{
					if (recommendations.size() >= MAX_RECOMMENDATIONS)
					{
						break;
					}
					if (adj.type != Advisor.Suggestion.Type.ADJUST_BUY
						&& adj.type != Advisor.Suggestion.Type.ADJUST_SELL)
					{
						continue;
					}
					final AdvisorPanel.Rec rec = new AdvisorPanel.Rec();
					rec.sell = adj.type == Advisor.Suggestion.Type.ADJUST_SELL;
					rec.itemId = adj.itemId;
					rec.name = adj.name;
					rec.quantity = adj.quantity;
					rec.unitPrice = adj.price;
					/* What re-listing at the new price is actually worth, on
					   the part of the offer that has not filled — the same
					   after-tax edge every other card is ranked on. A card
					   that said "+0 gp profit" would be the plugin admitting
					   it does not know why it is asking. */
					final Advisor.Quote aq = quotes.get(adj.itemId);
					if (aq != null && aq.high > 0 && aq.low > 0)
					{
						if (!rec.sell)
						{
							/* Sold back where the slot's own "if it flips"
							   line sells: the engine's target when it has one.
							   A losing buy was found against that number, and
							   costing its fix against another would put a
							   different profit on the card than in the box. */
							final long exit = flipExit(adj.itemId, aq, engineCache);
							final long unitEdge = exit - FlipTracker.taxPerItem(exit, adj.itemId) - adj.price;
							rec.profit = unitEdge * adj.quantity;
							rec.exitPrice = exit;
						}
						else
						{
							/*
							 * A re-listed SELL is the back half of a flip you
							 * are already in, so it is measured against what
							 * you PAID — not against today's insta-sell.
							 *
							 * It used to be adj.price - tax - aq.low: the
							 * margin you would make if you bought the stack
							 * again right now at today's low and immediately
							 * re-sold it. That is a fact about the current
							 * spread and has nothing to do with these units,
							 * which you already own at a price the plugin
							 * mostly knows. It was labelled "profit".
							 */
							final long net = adj.price - FlipTracker.taxPerItem(adj.price, adj.itemId);
							final long[] lot = openLots.get(adj.itemId);
							final long paid = lot != null && lot.length >= 2 && lot[0] > 0 && lot[1] > 0
								? Math.round(lot[1] / (double) lot[0]) : 0;
							rec.exitPrice = 0;
							if (paid > 0)
							{
								rec.unitCost = paid;
								rec.profit = (net - paid) * adj.quantity;
							}
							else
							{
								/* Nothing on record about what these cost, so
								   there is no profit to claim — only what the
								   remaining units bring in. The card already
								   knows how to say that: "sale value", never
								   "profit". */
								rec.hasTrackedCost = false;
								rec.profit = net * adj.quantity;
							}
						}
					}
					rec.note = adj.losing
						? "this offer loses money at its price — " + adj.reason
						: "all " + geSlotCount() + " of your GE slots are busy — this one is priced "
							+ "off the market, so it is the move worth making";
					recommendations.add(rec);
				}
			}
			/*
			 * Sells are built here, before the buys, so recommendedIds can be
			 * seeded with their item ids — which is what stops a buy being
			 * proposed for something already queued as a sell. Which of the
			 * two goes first on screen is orderRecommendations' call.
			 */
			final List<AdvisorPanel.Rec> sellRecs = new ArrayList<>();
			for (Advisor.Suggestion sell : sellRows)
			{
				/* Its own cap, not a share of one the buys then had to fit
				   into. Counted against the same total, a bank of thirty stacks
				   took every place before a single buy was built, and the flips
				   were whatever was left — "clicking 15 pages back just to find
				   5 recommended flips". orderRecommendations decides who gets
				   the places. */
				if (sellRecs.size() >= MAX_RECOMMENDATIONS)
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
				/* Bank, inventory, or both — read now rather than assumed. */
				rec.heldWhere = heldWhere(sell.itemId);
				/* Only meaningful alongside a tracked cost: when there is no
				   cost at all, profit already IS the whole stack's proceeds
				   and repeating them as an "and also" would double-count in
				   the reader's head. */
				if (sell.hasTrackedCost)
				{
					rec.untrackedQty = sell.quantity - sell.trackedQty;
					rec.untrackedValue = sell.untrackedValue;
				}
				rec.quoteAgeSec = sell.quoteAgeSec;
				{
					/* Day / 5-day position, when the series is cached — favourites
					   and the top-40 pool; NONE for everything else. Same derivation
					   the watchlist badge uses, so the two cannot disagree. */
					final PriceExtremes tierEx = dayExtremes.get(sell.itemId);
					final Advisor.Quote tierQ = quotes.get(sell.itemId);
					rec.tier = tierEx != null && tierQ != null
						? tierEx.tier(tierQ.high, tierQ.low) : PriceExtremes.Tier.NONE;
				}
				/* The stack against what the item actually trades, for the
				   row a buy card gives its flip score. Both volumes ride on
				   the /24h response this cycle already fetched, so the line
				   costs nothing — see Clearance, which returns null rather
				   than quoting a ratio off too few prints. */
				final AnalystRating.Average avg = averages.get(sell.itemId);
				rec.clearance = avg == null ? null
					: Clearance.of(sell.quantity, avg.highPriceVolume, avg.lowPriceVolume);
				rec.note = sell.reason;
				sellRecs.add(rec);
			}
			final Set<Integer> recommendedIds = new HashSet<>();
			for (AdvisorPanel.Rec r : recommendations)
			{
				recommendedIds.add(r.itemId);
			}
			for (AdvisorPanel.Rec r : sellRecs)
			{
				recommendedIds.add(r.itemId);
			}
			/* Top up the 30-day ranges for what the plan is recommending.
			   Bounded to the plan's own positions and to a few new requests a
			   cycle — see refreshRange30.

			   Handed to the executor, not run here. This is the client
			   thread, and these are blocking HTTP calls: run inline, a slow
			   response froze the game for as long as it took. The cards below
			   use whatever ranges are already held, and scheduleRange30
			   recomputes once more whenever a pass fetched anything new —
			   which tops up the next few positions, so a fresh eight-item
			   plan is covered in two passes. */
			scheduleRange30(planPositions);
			final List<AdvisorPanel.Rec> buyRecs = new ArrayList<>();
			final List<AdvisorPanel.Rec> ideaRecs = new ArrayList<>();
			for (CapitalPlanner.Position pos : planPositions)
			{
				if (recommendations.size() + buyRecs.size() >= MAX_RECOMMENDATIONS)
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
				{
					/* Day / 5-day position, when the series is cached — favourites
					   and the top-40 pool; NONE for everything else. Same derivation
					   the watchlist badge uses, so the two cannot disagree. */
					final PriceExtremes tierEx = dayExtremes.get(pos.id);
					final Advisor.Quote tierQ = quotes.get(pos.id);
					rec.tier = tierEx != null && tierQ != null
						? tierEx.tier(tierQ.high, tierQ.low) : PriceExtremes.Tier.NONE;
				}
				/* Where this price sits in the item's own 30-day range, when
				   it is near enough an edge to be worth saying. Measured
				   against the EXIT price the profit is staked on, not the bid
				   — that is the number you have to get back out at. */
				final RangePosition range = range30.get(pos.id);
				if (range != null && rec.exitPrice > 0)
				{
					rec.rangeNote = range.footnote(rec.exitPrice);
				}
				/* What sized it, and how much the item actually trades.
				   Two cards with the same edge and the same profit are not
				   the same proposition when one moves 20M units a day and
				   the other 250K — the first fills while you watch, the
				   second may sit there — and nothing on the card said so. */
				rec.note = (pos.boundBy == CapitalPlanner.Bound.CASH
					? "sized to the cash you have free"
					: pos.boundBy == CapitalPlanner.Bound.GE_LIMIT
						? "capped by the 4h buy limit"
						: pos.boundBy == CapitalPlanner.Bound.DAILY_VOLUME
							? "capped by how much actually trades in a day"
							: "sized conservatively — no confirmed buy limit for this item")
					+ (pos.dailyVolume > 0
						? " · " + QuantityFormatter.quantityToStackSize(pos.dailyVolume) + " traded a day"
						: "");
				/* Last word goes to the engine: it re-prices this card to a
				   pair it can show is reachable, or rejects the idea. Done
				   after rangeNote so a dropped card costs nothing already
				   computed, and after note so the sizing reason survives. */
				if (!applyEngineToBuy(rec, engineVerdict(pos.id, quotes.get(pos.id), engineCache), cash, pos.dailyVolume))
				{
					continue;
				}
				if (rec.exitPrice > 0)
				{
					final RangePosition r2 = range30.get(pos.id);
					rec.rangeNote = r2 != null ? r2.footnote(rec.exitPrice) : null;
				}
				buyRecs.add(rec);
				recommendedIds.add(pos.id);
			}
			/* Then the rest of Advisor's ranked buys. The plan stops at one
			   per free slot, so on its own the list ran out after a handful —
			   these are the ideas worth having queued for when a slot frees,
			   which is most of what you page through. */
			for (Advisor.Suggestion buy : suggestions)
			{
				if (recommendations.size() + buyRecs.size() >= MAX_RECOMMENDATIONS)
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
				/* The gp this ties up. A planner Position carries `spend`, so
				   the cards above got this for free; a Suggestion does not,
				   and it was simply never filled in here — which meant every
				   card past the first few showed a profit with no stake
				   beside it. "+91K gp profit" is a different proposition at
				   546K down than at 5M, and the card was not saying which. */
				rec.capital = (long) buy.quantity * buy.price;
				/* Same identity as above, from Advisor.buildBuys' own edge. */
				rec.exitPrice = exitPriceFor(quotes, buy.itemId);
				{
					/* Day / 5-day position, when the series is cached — favourites
					   and the top-40 pool; NONE for everything else. Same derivation
					   the watchlist badge uses, so the two cannot disagree. */
					final PriceExtremes tierEx = dayExtremes.get(buy.itemId);
					final Advisor.Quote tierQ = quotes.get(buy.itemId);
					rec.tier = tierEx != null && tierQ != null
						? tierEx.tier(tierQ.high, tierQ.low) : PriceExtremes.Tier.NONE;
				}
				rec.note = buy.reason;
				/* Each idea is sized against ALL your free cash on its own — an
				   alternative to the plan's cards, not another one on top. The
				   tooltip said otherwise ("the slots you have spare"), and
				   fourteen of these at ~400K each, on a 400K purse, read as
				   5.6M of advice. */
				rec.capitalBasis = "Sized on its own against all the cash you have free — "
					+ "an alternative to the cards before it, not one more on top of them.";
				/* Engine-confirmed on the same terms as the plan's own cards
				   above. These are the ideas you page through with Next, and
				   an idea that turns out not to hold up should not be sitting
				   in the queue waiting to be reached. */
				final Advisor.ItemMeta buyMeta = meta.get(buy.itemId);
				if (!applyEngineToBuy(rec, engineVerdict(buy.itemId, quotes.get(buy.itemId), engineCache), cash,
					buyMeta != null ? buyMeta.dailyVolume : 0L))
				{
					continue;
				}
				buyRecs.add(rec);
				ideaRecs.add(rec);
			}

			orderRecommendations(recommendations, buyRecs, sellRecs,
				Math.max(profitFloor, CapitalPlanner.MIN_POSITION_PROFIT), MAX_RECOMMENDATIONS);
			/* Hand the NEXT background fetch the items these cards name, so
			   their asking prices come from the engine rather than the last
			   insta-buy print — the sell-side twin of lastPlanItemIds above,
			   and the same one-cycle lag. Only the sells that made the list:
			   one that is not on screen does not need a better price. */
			final List<AdvisorPanel.Rec> shownSells = new ArrayList<>();
			for (AdvisorPanel.Rec r : sellRecs)
			{
				if (recommendations.contains(r))
				{
					shownSells.add(r);
				}
			}
			lastSellItemIds = sellSeriesIds(shownSells);
			final Set<Integer> ideaIds = new LinkedHashSet<>();
			for (AdvisorPanel.Rec r : ideaRecs)
			{
				if (ideaIds.size() >= IDEA_SERIES_CAP)
				{
					break;
				}
				if (recommendations.contains(r))
				{
					ideaIds.add(r.itemId);
				}
			}
			lastIdeaItemIds = ideaIds;

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
			/* The same pass keeps the suggestion itself, not just the fact
			   that there was one. A red border with no number behind it is
			   half an instruction — it names a problem and leaves you to
			   find the fix — and the natural reading of a red box is "cancel
			   this", which is the slowest fix and, on a partly-filled offer,
			   throws away your queue position. Keeping the suggestion here
			   means the colour and the explanation come from one place. */
			final Map<Integer, Advisor.Suggestion> adjustBySlot = new HashMap<>();
			for (Advisor.Suggestion s : suggestions)
			{
				if ((s.type == Advisor.Suggestion.Type.ADJUST_BUY || s.type == Advisor.Suggestion.Type.ADJUST_SELL) && s.slot >= 0)
				{
					slotStatus.put(s.slot, false);
					adjustBySlot.put(s.slot, s);
				}
			}
			final Map<Integer, GeOfferGridOverlay.SlotView> slotViews =
				buildSlotViews(offers, slotStatus, openLots, adjustBySlot, engineCache);
			/* Finished offers too, for the green "done" border in the game —
			   the sidebar square has always shown them; the box did not. Added
			   to a copy, because the sidebar strip below reads slotViews for
			   the WORKING offers' verdict. */
			final Map<Integer, GeOfferGridOverlay.SlotView> withDone = new HashMap<>(slotViews);
			final GrandExchangeOffer[] rawForGrid = client.getGrandExchangeOffers();
			for (int i = 0; rawForGrid != null && i < rawForGrid.length; i++)
			{
				final GrandExchangeOffer o = rawForGrid[i];
				if (o == null || o.getItemId() <= 0 || withDone.containsKey(i))
				{
					continue;
				}
				final GrandExchangeOfferState st = o.getState();
				if (st == GrandExchangeOfferState.BOUGHT || st == GrandExchangeOfferState.SOLD
					|| st == GrandExchangeOfferState.CANCELLED_BUY || st == GrandExchangeOfferState.CANCELLED_SELL)
				{
					final GeOfferGridOverlay.SlotView done = new GeOfferGridOverlay.SlotView();
					final ItemComposition comp = itemManager.getItemComposition(o.getItemId());
					done.itemName = comp != null ? comp.getName() : null;
					done.buy = st == GrandExchangeOfferState.BOUGHT || st == GrandExchangeOfferState.CANCELLED_BUY;
					done.filled = o.getQuantitySold();
					done.total = o.getTotalQuantity();
					done.ready = true;
					withDone.put(i, done);
				}
			}
			geGridOverlay.setSlots(withDone);

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
						/* The bank hover reads grossValue, NOT the constructor's
						   expectedProfit — deliberately, because "worth selling:
						   X" is a claim about what the stack fetches. This path
						   never set it, so every slot marked by the rating
						   signal rather than by the ranked sell list hovered
						   "Worth selling: 0 gp after tax" over 26,000 emeralds.
						   Same figure, both fields. */
						sellSuggestion.grossValue = value;
						/* This mark exists BECAUSE of the rating, so the
						   rating is the honest answer to "why now". */
						sellSuggestion.whyNow = "rated " + grade.label.text.toLowerCase() + " right now";
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
			bankOverlay.setHoldings(holdingTipsFor(openLots, quotes, cycleSeries));
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
					/* What the offer is listed at, on every slot that has one:
					   "this should say what i have it priced at ... because i
					   cant remember". */
					info.listedPrice = o.getPrice();
					/* What is waiting in the slot's collection box, when the
					   box last seen was this offer's. */
					final long[] box = collectBox.get(i);
					if (box != null && box[2] == info.itemId)
					{
						info.collectCoins = box[0];
						info.collectItems = (int) box[1];
						final Integer base = soldAtCollect.get(i);
						info.sinceCollect = base != null && info.quantityFilled >= base
							? info.quantityFilled - base : -1;
					}
					if (st == GrandExchangeOfferState.BUYING || st == GrandExchangeOfferState.SELLING)
					{
						/*
						 * The in-game box's own verdict, not a second one.
						 *
						 * This read the raw adjust suggestion, while the box
						 * applies the rules on top of it in buildSlotViews —
						 * above all that an offer which is filling is being
						 * reached by the market and is left alone. So a
						 * Mithril platebody sell with 163 of 255 gone was
						 * green in the game and red here, "lower your ask
						 * to 2,800": "isnt saying that in the grand exchange
						 * actual square is still green". Its margin check
						 * also priced a buy's exit off the raw insta-buy
						 * where the box uses the engine's. Reading the
						 * box's view makes them one answer.
						 */
						final GeOfferGridOverlay.SlotView view = slotViews.get(i);
						final boolean flagged = view != null && view.needsAdjust;
						info.state = !flagged ? GeSlotsPanel.SlotState.ACTIVE_OK
							: view.losing ? GeSlotsPanel.SlotState.ACTIVE_LOSING
							: GeSlotsPanel.SlotState.ACTIVE_ADJUST;
						if (flagged)
						{
							info.offerPrice = o.getPrice();
							info.targetPrice = view.targetPrice;
							info.breakEven = view.breakEven;
							info.noMargin = view.noMargin;
						}
					}
					else if (st == GrandExchangeOfferState.BOUGHT || st == GrandExchangeOfferState.SOLD
						|| st == GrandExchangeOfferState.CANCELLED_BUY || st == GrandExchangeOfferState.CANCELLED_SELL)
					{
						info.state = GeSlotsPanel.SlotState.READY_COLLECT;
					}
				}
				/* The same profit line the in-game box shows, from the same
				   computation, so the two cannot disagree about one offer. */
				final GeOfferGridOverlay.SlotView sv = slotViews.get(i);
				if (sv != null && info.state != GeSlotsPanel.SlotState.EMPTY)
				{
					info.projectedProfit = sv.projectedProfit;
				}
				slotInfos[i] = info;
			}

			final Set<Integer> favIds = favoriteIdSet();
			final AdvisorPanel.Settings currentSettings = buildSettings();
			final Map<Integer, long[]> olderBuys = currentOlderBuys(openLots);
			SwingUtilities.invokeLater(() ->
			{
				// No routine status text here — cash/interval were just
				// clutter above the always-visible top card; the settings
				// popup (gear icon) still shows the re-check interval.
				mainPanel.setAdvisorStatus("");
				mainPanel.setOlderBuys(olderBuys);
				mainPanel.updateSuggestions(suggestions, favIds, currentSettings);
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



	/**
	 * A time-less lot equal to a run of the lots just before it: what a
	 * build before 0.7.2 left when it re-booked a live offer after a hop.
	 * The re-book was the offer's whole receipt, so it matches the SUM of
	 * the fills it had already booked, and it was always booked offline.
	 * Timed lots are never flagged — an offer filling in equal chunks at
	 * its bid leaves identical lots honestly. An offline refill at the same
	 * bid also matches, so the report states the match without a verdict.
	 */
	static boolean repeatsEarlierLots(List<long[]> lots, int i)
	{
		final long[] lot = lots.get(i);
		if (lot.length > 2 && lot[2] > 0)
		{
			return false;
		}
		long qty = 0;
		long spent = 0;
		for (int j = i - 1; j >= 0 && qty < lot[0]; j--)
		{
			qty += lots.get(j)[0];
			spent += lots.get(j)[1];
			if (qty == lot[0] && spent == lot[1])
			{
				return true;
			}
		}
		return false;
	}

	/** Units of an item in your GE slots that are still yours: unsold in a
	 *  sell offer (or handed back by a cancelled one), or bought and maybe
	 *  not yet collected. Generous on purpose — it is the "you hold" side of
	 *  a warning, and a false warning sends people to Forget. Client thread. */
	private long inGeSlots(int itemId)
	{
		final GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		long n = 0;
		for (int i = 0; offers != null && i < offers.length; i++)
		{
			final GrandExchangeOffer o = offers[i];
			if (o == null || o.getItemId() <= 0
				|| (itemManager != null ? itemManager.canonicalize(o.getItemId()) : o.getItemId()) != itemId)
			{
				continue;
			}
			final GrandExchangeOfferState st = o.getState();
			if (st == GrandExchangeOfferState.SELLING || st == GrandExchangeOfferState.CANCELLED_SELL)
			{
				n += Math.max(0, o.getTotalQuantity() - o.getQuantitySold());
			}
			else if (st == GrandExchangeOfferState.BUYING || st == GrandExchangeOfferState.BOUGHT
				|| st == GrandExchangeOfferState.CANCELLED_BUY)
			{
				n += o.getQuantitySold();
			}
		}
		return n;
	}

	/**
	 * Your live offers for a watchlist row's item: what is listed for sale
	 * and at what, what you are bidding for. The ask or bid shown is the
	 * biggest offer's, when there are several.
	 */
	static void fillOfferPosition(FavoritesPanel.Row row, List<Advisor.OfferView> offers)
	{
		if (offers == null)
		{
			return;
		}
		int biggestListing = 0;
		int biggestBid = 0;
		for (Advisor.OfferView o : offers)
		{
			if (o == null || !o.active || o.itemId != row.id)
			{
				continue;
			}
			if (o.buy)
			{
				row.bidQty += o.totalQuantity;
				row.bidFilled += o.quantitySold;
				if (o.totalQuantity > biggestBid)
				{
					biggestBid = o.totalQuantity;
					row.bidPrice = o.price;
				}
				continue;
			}
			final int left = Math.max(0, o.totalQuantity - o.quantitySold);
			row.listedQty += left;
			row.listedSold += o.quantitySold;
			row.listedTotal += o.totalQuantity;
			if (left > biggestListing)
			{
				biggestListing = left;
				row.listedPrice = o.price;
			}
		}
	}

	/** The card subtitle for a row's stack: the Exchange first when some of
	 *  it is listed, since that is where the action is. */
	static String positionWhere(int listedQty, String bankOrInventory)
	{
		if (listedQty <= 0)
		{
			return bankOrInventory;
		}
		if (bankOrInventory == null)
		{
			return "listed in the Exchange";
		}
		return "listed in the Exchange, more " + bankOrInventory.replaceFirst("^from ", "in ");
	}

	/** Copies the "you're holding this right now" numbers onto a watchlist
	 *  row. All the actual reasoning — and every way the two quantities
	 *  involved can disagree — lives in {@link PortfolioValuer#heldPosition},
	 *  where it is unit-testable without a running client. */
	private static void fillHeldPosition(FavoritesPanel.Row row, Advisor.Quote q,
		Map<Integer, Integer> holdings, Map<Integer, long[]> openBuys)
	{
		/* Units still listed for sale count: they are yours until they sell,
		   and the tracked cost covers them — run fillOfferPosition first. */
		final PortfolioValuer.HeldPosition p = PortfolioValuer.heldPosition(
			row.id, holdings.getOrDefault(row.id, 0) + row.listedQty, q, openBuys.get(row.id));
		row.heldQty = p.heldQty;
		row.sellValue = p.sellValue;
		row.hasCostBasis = p.hasCostBasis;
		row.pricedQty = p.pricedQty;
		row.pricedCost = p.costOfPriced;
		row.heldProfit = p.heldProfit;
		/* At the price the inspect card shows, not the insta-buy. The row's
		   hover was "-550K less than you paid" beside a card saying -175K for
		   the same 25,000 Aether catalysts — two prices for one stack. The
		   card's SELL @ is what you are being told to list at, so every figure
		   about selling the stack is measured there. Same expression as
		   AdvisorPanel.recFor; run after the targets and offers are filled. */
		final long cardPrice = row.listedQty > 0 && row.listedPrice > 0 ? row.listedPrice
			: row.targetSell > 0 ? row.targetSell : row.price;
		if (p.heldQty > 0 && cardPrice > 0)
		{
			final long net = Math.max(0, cardPrice - FlipTracker.taxPerItem(cardPrice, row.id));
			row.sellValue = (long) p.heldQty * net;
			row.heldProfit = p.hasCostBasis ? (long) p.pricedQty * net - p.costOfPriced : 0;
		}
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
	/**
	 * Where a stack you hold actually is, for the sell card's own words.
	 *
	 * The card said "from your bank" on every sell, because that string was
	 * a constant. Reported with rubies sitting in the inventory: the panel
	 * names the stack, points at the wrong container, and the one thing the
	 * line is for is telling you where to go and get it.
	 *
	 * Null when the item is in neither, which is possible — a sell can be
	 * built from a cycle that ran before the last bank read.
	 */
	private String heldWhere(int itemId)
	{
		final int inBank = lastBank.getOrDefault(itemId, 0);
		int inInventory = 0;
		final ItemContainer inv = client.getItemContainer(InventoryID.INVENTORY);
		if (inv != null)
		{
			for (Item it : inv.getItems())
			{
				if (it.getId() > 0 && itemManager.canonicalize(it.getId()) == itemId)
				{
					inInventory += it.getQuantity();
				}
			}
		}
		if (inBank > 0 && inInventory > 0)
		{
			return "from your bank and inventory";
		}
		if (inInventory > 0)
		{
			return "from your inventory";
		}
		return inBank > 0 ? "from your bank" : null;
	}

	/**
	 * How many of each item this character holds, for pricing it from the
	 * newest buys — see FlipTracker.getHeldCost. The bank and inventory, worn
	 * items, what is still listed for sale, and units waiting in a collection
	 * box. Client thread.
	 */
	private Map<Integer, Long> heldForCost(Map<Integer, Integer> holdings, List<Advisor.OfferView> offers)
	{
		final Map<Integer, Long> held = new HashMap<>();
		holdings.forEach((id, q) -> held.merge(id, (long) q, Long::sum));
		currentEquipped().forEach((id, q) -> held.merge(id, (long) q, Long::sum));
		for (Advisor.OfferView o : offers)
		{
			if (o.active && !o.buy)
			{
				held.merge(o.itemId, (long) Math.max(0, o.totalQuantity - o.quantitySold), Long::sum);
			}
		}
		for (long[] box : collectBox.values())
		{
			if (box != null && box.length >= 3 && box[1] > 0)
			{
				held.merge((int) box[2], box[1], Long::sum);
			}
		}
		return held;
	}

	/** itemId -> {olderQty, olderSpent}, for the items whose lots run past
	 *  what is held: the card's note and its "forget" entry. */
	static Map<Integer, long[]> olderBuys(Map<Integer, long[]> heldCost)
	{
		final Map<Integer, long[]> out = new HashMap<>();
		heldCost.forEach((id, c) ->
		{
			if (c.length >= 4 && c[2] > 0)
			{
				out.put(id, new long[]{c[2], c[3]});
			}
		});
		return out;
	}

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

	/** How many offer slots this account has at all — three on a free world,
	 *  eight on a members one. Named separately from freeGeSlots because the
	 *  advice has to be able to SAY the number ("all 3 of your slots are
	 *  busy"), and a free-to-play player told about eight slots they do not
	 *  have would rightly stop believing the rest of it. */
	private int geSlotCount()
	{
		return usableGeSlots(client);
	}

	private int freeGeSlots()
	{
		final int total = geSlotCount();
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

	/** A price clicked on the card or the chip: copied to the clipboard,
	 *  and the chat line says how to put it in the box. Nothing is typed —
	 *  see fillKeyListener for why only a key press may do that. */
	private void fillGePrice(long price)
	{
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(String.valueOf(price)), null);
		announceCopied("price", price, " gp");
	}

	/** The quantity chip's click: the same as the price's. */
	private void fillGeQuantity(long qty)
	{
		Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(String.valueOf(qty)), null);
		announceCopied("quantity", qty, "");
	}

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

	/** Guards the fill against re-entering itself. Writing the input and
	 *  rebuilding it fires script events of its own; a fill started from
	 *  inside one of those once recursed until the client died ("click enter
	 *  on 1870 and boom crash"). The write is always deferred to its own
	 *  client tick, and this makes a second pass a no-op. */
	private boolean autoFillInFlight = false;

	/**
	 * The card's item clicked while the game asks which item to trade: say
	 * what to type. It used to type it — see GeOfferPriceOverlay's search
	 * note for why a plugin may not.
	 */
	private void fillGeSearch(String itemName)
	{
		final String message = new ChatMessageBuilder()
			.append(Color.decode("#E5C158"), "PocketGE")
			.append(Color.WHITE, " type ")
			.append(Color.decode("#E5C158"), itemName)
			.append(Color.WHITE, " in the search.")
			.build();
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.runeLiteFormattedMessage(message)
			.build());
	}

	/**
	 * Put the armed number in the open box — from fillKeyListener only, so
	 * always the answer to the player's own key press.
	 *
	 * Re-checks on the client thread that the box the key was pressed for is
	 * still the one open (the overlay armed it a frame ago, from the AWT
	 * thread's point of view), then writes the input the way RuneLite's own
	 * bundled plugins do and asks the game to redraw it. Never presses Enter:
	 * confirming the offer is still the player's click.
	 */
	private void fillGePrompt(boolean quantity, long value)
	{
		clientThread.invokeLater(() ->
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
			/* The identical test the overlay armed the key with — see
			   GeOfferPriceOverlay.openNumberBox for why it reads the
			   question and never the typed line. */
			final GeOfferPriceOverlay.NumberBox box = GeOfferPriceOverlay.openNumberBox(client);
			if (box != (quantity ? GeOfferPriceOverlay.NumberBox.QUANTITY : GeOfferPriceOverlay.NumberBox.PRICE))
			{
				return; // the box closed, or changed, between the key and this tick
			}
			autoFillInFlight = true;
			try
			{
				client.setVarcStrValue(VarClientID.MESLAYERINPUT, String.valueOf(value));
				client.runScript(ScriptID.CHAT_TEXT_INPUT_REBUILD, "");
			}
			finally
			{
				autoFillInFlight = false;
			}
		});
	}

	/** A clicked price or quantity: copied, and how to put it in. */
	private void announceCopied(String what, long value, String unit)
	{
		final String message = new ChatMessageBuilder()
			.append(Color.decode("#E5C158"), "PocketGE")
			.append(Color.WHITE, " copied the " + what + ": ")
			.append(Color.decode("#E5C158"), String.format("%,d", value) + unit)
			.append(Color.WHITE, ". In the game's " + what + " box, "
				+ GeOfferPriceOverlay.FILL_KEY_NAME + " fills the suggested " + what
				+ " shown on the chip.")
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
					/* The ledger's total, not this payload's. `flips` above is
					   the recent window; this says how much history exists
					   behind it, so the page can tell whether the copy it
					   downloaded from /history is still current. */
					m.put("flipCount", ledgerCount.get());
					return m;
				},
					() ->
					{
						/* Read fresh on every request rather than cached: this
						   is asked for when a page opens, not on a timer, and a
						   cache here would be one more thing to invalidate for
						   no measurable gain on a local file. */
						final Map<String, Object> h = new HashMap<>();
						final List<Flip> all = ledger != null ? ledger.readAll() : List.<Flip>of();
						h.put("flips", all);
						h.put("count", all.size());
						h.put("generatedAt", System.currentTimeMillis());
						return h;
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

	/** The client's "not logged in to anything": -1, or 0 before the login
	 *  response has set it. Only those — a hash is a 64-bit value and the
	 *  sign of a real one is not something to bet a character's offers on. */
	static boolean noAccount(long hash)
	{
		return hash == -1 || hash == 0;
	}

	/**
	 * Worlds whose Grand Exchange is not the main game's: Leagues, Deadman,
	 * beta and the other separate-save worlds. They log in with the same
	 * account hash, so their slots would be filed as the main game's — an
	 * empty Leagues slot deleted the main game's record of an offer, and the
	 * offer was booked a second time on the way back. Not tracked at all,
	 * which also keeps Leagues trades out of the main game's cost basis.
	 */
	static boolean separateExchange(java.util.Set<WorldType> world)
	{
		if (world == null)
		{
			return false;
		}
		for (WorldType t : world)
		{
			switch (t)
			{
				case SEASONAL:
				case DEADMAN:
				case BETA_WORLD:
				case NOSAVE_MODE:
				case TOURNAMENT_WORLD:
				case FRESH_START_WORLD:
				case QUEST_SPEEDRUNNING:
					return true;
				default:
					break;
			}
		}
		return false;
	}

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged event)
	{
		/* Here as well as on LOGGED_IN, because the login replay of your GE
		   slots is not guaranteed to arrive after that event — and the
		   baselines this is about to measure against have to be known to
		   belong to the character being measured. Cheap and idempotent: it
		   returns immediately unless the character actually changed. */
		final GrandExchangeOffer offer = event.getOffer();
		final GrandExchangeOfferState state = offer.getState();
		if (isLoginClear(state, client.getGameState()))
		{
			return; // see isLoginClear: the client clearing slots, not you
		}
		/* Date the fill for the "filling, so leave it" grace — live only,
		   not the login replay of fills made while logged out. A slot seen
		   for the first time is only recorded: an event also fires for a
		   collect, and a count with no earlier one to compare says nothing
		   about when it was reached. Ahead of the account and world checks
		   below: those protect the tracker's per-character records, and
		   this is the sitting's own, so a Leagues offer filling every minute
		   is still left alone. */
		if ((state == GrandExchangeOfferState.BUYING || state == GrandExchangeOfferState.SELLING)
			&& geSettledTick >= 0 && client.getTickCount() >= geSettledTick)
		{
			noteFill(slotProgress, event.getSlot(), offer.getQuantitySold(), System.currentTimeMillis());
		}
		/* Not until the client says whose offers these are. With no account
		   the tracker keeps the LAST character's slot baselines, and an alt's
		   offer would be measured against its main's. The per-tick sweep
		   reads the slot again once the account is known. */
		if (noAccount(client.getAccountHash()) || separateExchange(client.getWorldType()))
		{
			return;
		}
		tracker.setAccountHash(client.getAccountHash());
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
			/* Terms, for recognising this offer again after a restart — the
			   tracker never does arithmetic with them. */
			offer.getPrice(),
			offer.getTotalQuantity(),
			emptied
		);
		/* Read unconditionally, before the branch: this clears the flag, and
		   short-circuiting past it on a fill would leave a stale one set. */
		final boolean baselineMoved = tracker.takeSlotsDirty();
		if (fill != null)
		{
			refreshPanel();
		}
		/* Saved even with no fill to show. Placing or collecting an offer only
		   moves a baseline, and a baseline that never reaches disk is re-taken
		   from scratch next start — which is exactly how a fill that happened
		   while you were logged out goes uncounted. */
		if (fill != null || baselineMoved)
		{
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
		final int collectFor = collectSlot(event.getContainerId());
		if (collectFor >= 0)
		{
			noteCollectBox(collectFor, c);
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
		bankReadThisLoginAt = bankSeenAt;
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
		/* Keep it for next login. The client only hands over the bank while
		   it is open, so this is the only moment the snapshot exists. */
		saveBank();
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
		final GameState gs = event.getGameState();
		if (gs == GameState.LOGIN_SCREEN || gs == GameState.LOGGING_IN
			|| gs == GameState.HOPPING || gs == GameState.CONNECTION_LOST)
		{
			geSettledTick = -1; // the slots are about to be cleared and replayed
		}
		else if (gs == GameState.LOGGED_IN && geSettledTick < 0)
		{
			geSettledTick = client.getTickCount() + GE_LOGIN_BURST_TICKS;
		}
		if (event.getGameState() == GameState.LOGIN_SCREEN)
		{
			skipped.clear(); // session skips reset on logout
			/* Same reasoning: a move you were told about before logging out is
			   news again when you come back to it. */
			lastAlertMillis.clear();
			/* The logout clear no longer reaches the event path (isLoginClear),
			   which is where these used to be dropped. Kept across a hop —
			   the same offers come back — but not across a logout, where the
			   next offer in the slot may be another character's. */
			adviceSkippedSlots.clear();
			/* And every slot can change hands before anyone logs in again —
			   collected and re-placed on mobile — so no baseline is to be
			   trusted as the same offer without question. */
			tracker.markSlotsUnwatched();
			/* And the sweep forgets what it last reported, so the first settled
			   sweep after the next login reports all eight slots — the empty
			   ones too, which is what clears a record of an offer collected
			   elsewhere. Kept, it skipped any slot the LAST character also had
			   empty, and stale rows outlived the login. The client has already
			   emptied every slot by now (see isLoginClear). */
			java.util.Arrays.fill(sweptItem, -1);
			/* And fills found at the next login happened while logged out, at
			   no moment anyone saw: not "filling now". See filledRecently. */
			slotProgress.clear();
			/* The next character's bank is not read until it is opened. */
			bankReadThisLoginAt = 0;
			collectedAt.clear();
			// Same reason: the next character's boxes are not this one's.
			collectBox.clear();
			soldAtCollect.clear();
			loggedIn = false;
			perItemOwed = true; // whoever logs in next starts with a per-item round
			setPanelLoggedIn(false);
		}
		else if (event.getGameState() == GameState.LOGGED_IN)
		{
			/* LOGGED_IN also follows every loading screen and world hop, not
			   just a login — only the real one, out of the login screen, is
			   a reason to go to the network. */
			final boolean justLoggedIn = !loggedIn;
			loggedIn = true;
			setPanelLoggedIn(true);
			/* Before the client replays this character's GE slots, so the
			   baselines those replays are measured against are known to
			   belong to this character and not the last one. */
			tracker.setAccountHash(client.getAccountHash());
			/* Before the first recompute below, so the very first card of the
			   session is sized against the cash you actually have rather than
			   the loose coins in your pocket. */
			restoreBank();
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
			/* While logged out the book is only refreshed every fifteen
			   minutes and the per-item history not at all, so a login after a
			   while away fetches once now rather than waiting out the
			   schedule. refreshPrices itself declines if a round went out in
			   the last minute. */
			recomputeAdvice();
			if (justLoggedIn || lastQuotes.isEmpty())
			{
				/* A fresh book if the one held is older than the advisor's
				   own interval, and the per-item round that was owed while
				   logged out either way. refreshPrices declines anything
				   inside the one-minute floor and caches per item, so this
				   costs nothing when there is nothing to do. */
				final long periodMs = Math.max(60, config.adjustInterval().seconds()) * 1000L;
				executor.submit(() ->
				{
					refreshPrices(periodMs);
					refreshStatsAndFavorites();
				});
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
		/* No CHAT_PROMPT_INIT branch. The price box used to be filled the
		   instant it opened, from here; that is a plugin typing into the
		   chatbox with no key pressed, which RuneLite does not allow. The
		   player presses E instead — see fillKeyListener. */
		if (event.getScriptId() != ScriptID.GE_OFFERS_SETUP_BUILD)
		{
			return;
		}
		updateGeContextFromScreen();
	}

	/**
	 * Read the open GE offer screen and work out what to suggest on it.
	 *
	 * Split out of the script handler so it can be run a SECOND time, once a
	 * price series has been fetched for the item — see the tail of this
	 * method. Reads widgets and varbits, so it must stay on the client thread.
	 */
	/** The item the open PocketGE tab was last moved to by the offer
	 *  screen; -1 once the screen closes, so opening it again follows again. */
	private volatile int followedOfferItem = -1;

	/**
	 * Move the PocketGE tab you already have open to the item the offer
	 * screen is on: "every time i list a new item in the ge, if i already
	 * have a pocket ge window open ... can it automatically change the window
	 * from sapphire necklaces to ruby necklaces".
	 *
	 * Only a tab that is open and linked right now, and never a new one: this
	 * fires without a click, so it must never be the thing that opens a
	 * browser. No ack wait and no chat line for the same reason — it is the
	 * page keeping up, not an action you took. Once per item per visit to
	 * the screen; the screen updates far more often than that.
	 */
	void followOfferScreen(int itemId, String name)
	{
		if (!config.followGeOffer() || itemId == followedOfferItem || name == null || name.isEmpty())
		{
			return;
		}
		final LocalBridgeServer b = bridge;
		if (b == null || b.port() <= 0 || !b.hasNavListener(TAB_POLL_MS))
		{
			return;
		}
		followedOfferItem = itemId;
		final LocalBridgeServer.NavRequest request = new LocalBridgeServer.NavRequest(
			navSeq.incrementAndGet(), name, itemId, System.currentTimeMillis(), false);
		pendingNav = request;
		b.publishNav(request);
	}

	/**
	 * The price the offer screen quotes for one side: the engine's target
	 * clamped to the live book — you have already committed to this side of
	 * this item by opening the screen, so a sell is never quoted under the
	 * standing bid nor a buy over the standing ask (see TradeEngine.sellTarget
	 * for the Yew logs case) — or the live print when the engine has nothing.
	 * The offer screen's card and the chart's target line both, so they
	 * cannot name different numbers.
	 */
	static long offerScreenPrice(Advisor.Quote q, TradeEngine.Result engine, boolean isBuy)
	{
		if (q == null)
		{
			return 0;
		}
		final long raw = isBuy ? q.low : q.high;
		if (raw > 0 && engine != null && engine.viable)
		{
			return isBuy ? TradeEngine.buyTarget(engine.buy, q.low) : TradeEngine.sellTarget(engine.sell, q.high);
		}
		return raw;
	}

	private void updateGeContextFromScreen()
	{
		if (!config.advisor() && System.currentTimeMillis() - lastQuotesAt > ADVISOR_OFF_BOOK_MAX_MS)
		{
			/* With the advisor off nothing refreshes the book, so the prices
			   held could be hours old — and this screen is where a price gets
			   typed in. Say nothing rather than pass an old print off as the
			   market. */
			clearGeContext();
			return;
		}
		final int itemId = client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH);
		if (itemId > 0)
		{
			final ItemComposition named = itemManager.getItemComposition(itemId);
			followOfferScreen(itemId, named != null ? named.getName() : null);
		}
		else
		{
			followedOfferItem = -1; // screen closed: the next one follows again
		}
		final Advisor.Quote q = itemId > 0 ? lastQuotes.get(itemId) : null;
		final boolean isBuy = client.getVarbitValue(VarbitID.GE_NEWOFFER_TYPE) == 0;
		final long wikiPrice = q == null ? 0 : (isBuy ? q.low : q.high); // the raw live print, before any repricing
		// Reprice through TradeEngine when we have series data for this item,
		// same target pocketge.com would show — same pattern as
		// ADJUST_BUY/ADJUST_SELL and the SELL suggestion (see Advisor.advise).
		TradeEngine.Result engine = null;
		if (q != null && wikiPrice > 0)
		{
			/* seriesFor, NOT lastOfferSeries.get.
			   The fetch at the tail of this method lands the series in the
			   on-demand cache and re-runs this method so the engine can price
			   it — and this line then looked in the OTHER cache, found
			   nothing, and fell through to the raw print again. Every fresh
			   offer screen was quoted at the wiki's last insta-buy while the
			   website, with the same series through the same engine, said
			   something else: Ruby at 779 here against 793 there. */
			final TradeEngine.Series series = seriesFor(itemId);
			if (series != null)
			{
				engine = TradeEngine.viewTargets(q.low, q.high, q.lowTime, q.highTime, series, itemId);
			}
		}
		final long price = offerScreenPrice(q, engine, isBuy);
		if (itemId <= 0 || price <= 0)
		{
			clearGeContext();
			return;
		}
		final ItemComposition comp = itemManager.getItemComposition(itemId);
		geContextItemId = itemId;
		geContextPrice = price;
		geContextName = comp != null ? comp.getName() : ("Item " + itemId);
		/* Same number the sidebar shows, drawn on the screen you're actually
		   looking at — see GeOfferPriceOverlay for why that round trip
		   mattered. Margin is only meaningful when both sides are known. */
		final long margin = (q != null && q.high > q.low)
			? q.high - q.low - FlipTracker.taxPerItem(q.high, itemId) : 0;
		/* The second half of the same job. The game asks for a price and then
		   a quantity, and answering only the first left the overlay pointing
		   at a step you had already taken — see GeOfferPriceOverlay.render.
		   Buys are sized exactly the way the sidebar cards size them, so the
		   two numbers agree; sells are just the stack you hold. */
		final long quantity = isBuy
			? suggestedBuyQuantity(itemId, price)
			: currentHoldings().getOrDefault(itemId, 0);
		gePriceOverlay.setContext(geContextName, isBuy, price, wikiPrice, margin, quantity);

		/* The whole trade for the sidebar card, not just the price.
		   The takeover used to say "Sell at 779 gp each" and nothing else,
		   which left the one moment you are actually committing gold with
		   the least information on screen. It is the same shape as a
		   recommendation now — what you paid, what to ask, what that makes,
		   how many — built from the same figures the ranked cards use. */
		final AdvisorPanel.Rec rec = new AdvisorPanel.Rec();
		rec.sell = !isBuy;
		rec.itemId = itemId;
		rec.name = geContextName;
		rec.quantity = (int) Math.min(Integer.MAX_VALUE, quantity);
		rec.unitPrice = price;
		if (isBuy)
		{
			final boolean viable = engine != null && engine.viable;
			/* The same exit as the slot's "if it flips" line, the sidebar
			   square and the ranked buy card: the engine's sell, unclamped —
			   pocketge.com's target. Clamped up to the insta-buy here, this
			   card said +60K for an offer the box beside it said +30K for. */
			final long exit = viable && engine.sell > 0 ? engine.sell : (q != null ? q.high : 0);
			rec.exitPrice = exit;
			final long unitEdge = exit > 0 ? exit - FlipTracker.taxPerItem(exit, itemId) - price : 0;
			rec.profit = unitEdge * quantity;
			rec.capital = price * quantity;
			if (viable)
			{
				rec.score = TradeEngine.FlipScore.of((double) engine.edge / Math.max(1, engine.buy),
					lastVolumes.getOrDefault(itemId, 0L), engine.lowConf);
			}
		}
		else
		{
			/* Same split PortfolioValuer.heldPosition makes: profit is claimed
			   only over the units the plugin watched you buy; the rest of the
			   stack is proceeds, and the card says so. Costed at the price on
			   THIS card, not the raw print, so the green number is true at
			   the number you are about to type. */
			final long net = price - FlipTracker.taxPerItem(price, itemId);
			final long[] tracked = tracker.getHeldCost(heldForCost(currentHoldings(), currentOffers())).get(itemId);
			if (tracked != null && tracked.length >= 2 && tracked[0] > 0 && tracked[1] > 0)
			{
				final long priced = Math.min(quantity, tracked[0]);
				final long costOfPriced = (long) Math.ceil(tracked[1] * (priced / (double) tracked[0]));
				rec.hasTrackedCost = true;
				rec.unitCost = Math.round(tracked[1] / (double) tracked[0]);
				rec.profit = priced * net - costOfPriced;
				rec.untrackedQty = quantity - priced;
				rec.untrackedValue = rec.untrackedQty * net;
			}
			else
			{
				rec.hasTrackedCost = false;
				rec.profit = quantity * net;
			}
		}
		if (!isBuy)
		{
			/* The same line the sidebar's sell card carries, off the same
			   figures: `quantity` above is currentHoldings() on a sell, i.e.
			   the whole stack, so the two surfaces cannot disagree. */
			final AnalystRating.Average avg = lastAverages.get(itemId);
			rec.clearance = avg == null ? null
				: Clearance.of(quantity, avg.highPriceVolume, avg.lowPriceVolume);
		}
		{
			final PriceExtremes tierEx = dayExtremes.get(itemId);
			rec.tier = tierEx != null && q != null
				? tierEx.tier(q.high, q.low) : PriceExtremes.Tier.NONE;
		}
		geContextRec = rec;
		pushGeContext();

		/* Fetch the price series NOW if this item has none.
		
		   Without it the block above falls through to the raw live quote —
		   for a buy that is the insta-sell, i.e. "pay whatever sellers are
		   asking". That is a fill, but it is not a flip: it crosses the spread
		   and hands the whole margin to the other side. The engine's target is
		   a resting bid below it, which is what the sidebar card and the
		   website both show for the same item.
		
		   And the gap hit exactly when it mattered. The series set is rebuilt
		   once per advisor cycle (up to five minutes) from the active offers,
		   the sell candidate and whatever screen is already open — so opening
		   a FRESH offer for an item you are not already trading was guaranteed
		   to have nothing cached, every time. The one moment you need the
		   number is the one moment it was missing.
		
		   Same on-demand shape as the inspected watchlist item: one request,
		   one item, and it re-runs this method so the panel and the overlay
		   pick up the engine price rather than waiting for the next cycle. */
		/* The QUOTE as well as the series, and for the same reason.
		
		   The series fetch below was added because opening a fresh offer
		   screen was guaranteed to have no series cached. The book had the
		   identical problem and was missed: lastQuotes is only replaced on the
		   advisor cycle, so the insta-buy this target is clamped to could be
		   five minutes old at the moment you are about to type a price. On a
		   item that moves, that is the plugin disagreeing with its own
		   website about what the market is. */
		/*
		 * At most one try per item, and one for the book, per
		 * GE_QUOTE_MAX_AGE_MS — whether or not it worked.
		 *
		 * The fetch below ends by re-running this method, and this method
		 * fetches whenever the series is missing. A request that came back
		 * empty or failed therefore re-ran itself back to back for as long as
		 * the offer screen stayed open: a tight loop of requests to the wiki
		 * from a player who was only reading a price.
		 */
		if (!config.advisor())
		{
			return; // the advisor switch is the network switch: show what is held
		}
		final long now = System.currentTimeMillis();
		final boolean quoteStale = now - lastQuotesAt > GE_QUOTE_MAX_AGE_MS
			&& now - lastGeQuoteAttemptAt > GE_QUOTE_MAX_AGE_MS;
		final Long triedSeries = geSeriesAttemptAt.get(itemId);
		final boolean wantSeries = seriesFor(itemId) == null
			&& (triedSeries == null || now - triedSeries > GE_SERIES_RETRY_MS);
		if (wantSeries || quoteStale)
		{
			if (quoteStale)
			{
				lastGeQuoteAttemptAt = now;
			}
			if (wantSeries)
			{
				if (geSeriesAttemptAt.size() > ON_DEMAND_SERIES_CAP * 4)
				{
					geSeriesAttemptAt.clear(); // bounded; worst case is one early retry
				}
				geSeriesAttemptAt.put(itemId, now);
			}
			executor.execute(() ->
			{
				if (!running)
				{
					return; // queued just before the plugin was disabled
				}
				try
				{
					if (quoteStale)
					{
						/* One request for the whole book — the same call the
						   cycle makes, so this costs nothing extra per item. */
						lastQuotes = marketClient.fetchLatest();
						lastQuotesAt = System.currentTimeMillis();
					}
					if (wantSeries)
					{
						cacheOnDemandSeries(itemId, marketClient.fetchTimeseries5m(itemId));
					}
				}
				catch (MarketClient.BackingOff b)
				{
					return; // the API asked us to wait; the screen keeps what it has
				}
				catch (Exception e)
				{
					log.warn("PocketGE: refresh failed for the open offer screen, item {}", itemId, e);
					return;
				}
				/* Back to the client thread to re-read the screen. Guarded on
				   the item still being the one on screen, so a slow fetch that
				   lands after you have backed out cannot repaint a stale
				   suggestion over a different offer. */
				clientThread.invokeLater(() ->
				{
					final Integer stillOpen = geContextItemId;
					if (stillOpen != null && stillOpen == itemId)
					{
						updateGeContextFromScreen();
					}
				});
			});
		}
	}

	/**
	 * How many of {@code itemId} to offer for at {@code unitPrice}.
	 *
	 * Deliberately the same three caps Advisor.advise applies to a BUY — the
	 * cash you actually have, the 4-hour limit, and a slice of a day's volume
	 * so the offer isn't asking the market to move for you. Reproduced here
	 * rather than reached for through a Suggestion because the offer screen
	 * can be open on an item the advisor never suggested (a fresh search, a
	 * blocked item, the advisor switched off entirely), and the number should
	 * still be right in all of those.
	 *
	 * 0 when there is nothing sensible to say, which hides the step rather
	 * than guessing.
	 */
	private long suggestedBuyQuantity(int itemId, long unitPrice)
	{
		if (unitPrice <= 0)
		{
			return 0;
		}
		final ItemStats stats = itemManager.getItemStats(itemId);
		final int limit = stats != null ? stats.getGeLimit() : 0;
		final long byCash = totalCash() / unitPrice;
		final long byLimit = limit > 0 ? limit : byCash;
		final Long vol = lastVolumes.get(itemId);
		final long byVolume = vol != null && vol > 0 ? Math.max(1, vol / 12) : byLimit;
		return Math.max(0, Math.min(byCash, Math.min(byLimit, byVolume)));
	}

	private void clearGeContext()
	{
		if (geContextItemId == null)
		{
			return;
		}
		geContextItemId = null;
		geContextRec = null;
		gePriceOverlay.clear();
		pushGeContext();
	}

	private void pushGeContext()
	{
		if (mainPanel == null)
		{
			return;
		}
		final AdvisorPanel.Rec rec = geContextItemId == null ? null : geContextRec;
		SwingUtilities.invokeLater(() -> mainPanel.setGeContext(rec));
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

	/** The active list's item ids, in the list's own order. */
	private Set<Integer> favoriteIdSet()
	{
		final Set<Integer> ids = new LinkedHashSet<>();
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
		sweepGeSlots();
		/* A rolling range drops its oldest flip when it ages out, whether or
		   not anything else is happening — the list and the totals said "in
		   the last hour" about a flip from 90 minutes ago otherwise. */
		final long expires = rangeExpiresAt;
		if (expires > 0 && System.currentTimeMillis() >= expires)
		{
			rangeExpiresAt = 0;
			refreshStatsAndFavorites();
		}
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

	/** What the sweep last handed the tracker, per slot, so a slot that has
	 *  not moved costs three comparisons instead of an item lookup. -1 so the
	 *  first sweep after startup — and after every logout — reports every
	 *  slot including the empty ones, which is what clears a baseline left by
	 *  an offer collected while the plugin was not watching. */
	private final int[] sweptItem = new int[]{-1, -1, -1, -1, -1, -1, -1, -1};
	private final int[] sweptQty = new int[8];
	private final long[] sweptSpent = new long[8];

	/**
	 * Read the eight slots straight off the client and hand them to the
	 * tracker, once a tick.
	 *
	 * GrandExchangeOfferChanged is the primary source and this changes nothing
	 * it already reports — onOffer takes cumulative snapshots, so telling it
	 * the same numbers twice produces nothing. What this catches is the offer
	 * that never fires an event at all: enable the plugin mid-session on top
	 * of a buy that has already finished, and the next thing that happens to
	 * it is you collecting it, which arrives as EMPTY with no item and no
	 * spend. Everything that offer knew about what you paid is gone at that
	 * point. Looking at the slot while it is still sitting there is the only
	 * moment the receipt exists.
	 */
	/**
	 * Whether an EMPTY slot event is the client clearing every slot on its
	 * way through a logout, world hop or login, rather than an offer
	 * actually going away.
	 *
	 * The client fires EMPTY for all eight slots then, and forwarding them
	 * deleted the current character's baselines; the real offers replayed
	 * after login then met no record, took the first-sighting path, and
	 * booked everything they had already filled a second time. A hop with
	 * 3,738 Mithril bars bought became 7,476 tracked. RuneLite's own
	 * GrandExchangePlugin ignores the same events for the same reason.
	 */
	static boolean isLoginClear(GrandExchangeOfferState state, GameState gameState)
	{
		return state == GrandExchangeOfferState.EMPTY && gameState != GameState.LOGGED_IN;
	}

	/**
	 * The first tick on which the sweep may believe an EMPTY slot, or -1
	 * while logged out.
	 *
	 * The login replay of the GE slots lands a tick or two after LOGGED_IN,
	 * so for those ticks the client's array still shows the slots it cleared.
	 * A sweep reading them then does exactly what isLoginClear stops the
	 * event path doing. Two ticks: the window RuneLite's GrandExchangePlugin
	 * uses for the same login burst.
	 */
	private int geSettledTick = -1;
	private static final int GE_LOGIN_BURST_TICKS = 2;

	private void sweepGeSlots()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		final GrandExchangeOffer[] raw = client.getGrandExchangeOffers();
		if (raw == null || noAccount(client.getAccountHash()) || separateExchange(client.getWorldType()))
		{
			return; // no account yet, or not this account's Exchange: see those
		}
		tracker.setAccountHash(client.getAccountHash());
		boolean filled = false;
		for (int slot = 0; slot < raw.length && slot < sweptItem.length; slot++)
		{
			final GrandExchangeOffer o = raw[slot];
			if (o == null)
			{
				continue;
			}
			final boolean emptied = o.getState() == GrandExchangeOfferState.EMPTY
				|| o.getItemId() <= 0;
			if (emptied && (geSettledTick < 0 || client.getTickCount() < geSettledTick))
			{
				continue; // still the login clear, not a collected offer — see geSettledTick
			}
			if (emptied)
			{
				adviceSkippedSlots.remove(slot); // same promise as the event path
				/* What was in it has been collected — perhaps straight to the
				   bank. sweptItem still holds the item it last reported. */
				if (sweptItem[slot] > 0)
				{
					collectedAt.put(itemManager != null ? itemManager.canonicalize(sweptItem[slot]) : sweptItem[slot],
						System.currentTimeMillis());
				}
			}
			final int itemId = emptied ? 0 : o.getItemId();
			final int qty = emptied ? 0 : o.getQuantitySold();
			final long spent = emptied ? 0 : o.getSpent();
			if (sweptItem[slot] == itemId && sweptQty[slot] == qty && sweptSpent[slot] == spent)
			{
				continue;
			}
			sweptItem[slot] = itemId;
			sweptQty[slot] = qty;
			sweptSpent[slot] = spent;
			final boolean buy = o.getState() == GrandExchangeOfferState.BUYING
				|| o.getState() == GrandExchangeOfferState.BOUGHT
				|| o.getState() == GrandExchangeOfferState.CANCELLED_BUY;
			/* On the client thread here, same as the event handler. */
			final String name = emptied ? ""
				: itemManager.getItemComposition(itemId).getName();
			filled |= tracker.onOffer(System.currentTimeMillis(), slot, itemId, name, buy,
				qty, spent, o.getPrice(), o.getTotalQuantity(), emptied) != null;
		}
		final boolean baselineMoved = tracker.takeSlotsDirty();
		if (filled)
		{
			refreshPanel();
		}
		if (filled || baselineMoved)
		{
			saveState();
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
		/*
		 * "PocketGE chart" on the slot itself.
		 *
		 * The chart was reachable from the sidebar card and from a watchlist
		 * row, which covers deciding what to trade — and not at all from the
		 * offer you are actually staring at when the question comes up, which
		 * is "is this one still going anywhere?". Every other flipping plugin
		 * puts a graph entry here; this is where the hand already is.
		 *
		 * Added FIRST so it ends up above "Stop PocketGE pricing this offer"
		 * in the menu: it is the one you will press, and the other is a
		 * setting you touch once.
		 *
		 * Reads the item off the live offer rather than off the menu event —
		 * the entry is attached to whichever child widget the cursor happened
		 * to be over, which may be the progress bar or the label, and none of
		 * them carry the item id.
		 */
		final GrandExchangeOffer[] raw = client.getGrandExchangeOffers();
		final GrandExchangeOffer offer = raw != null && slot < raw.length ? raw[slot] : null;
		if (offer != null && offer.getItemId() > 0)
		{
			final ItemComposition comp = itemManager.getItemComposition(offer.getItemId());
			final String slotItemName = comp != null ? comp.getName() : null;
			if (slotItemName != null && !slotItemName.isEmpty())
			{
				final int canon = itemManager.canonicalize(offer.getItemId());
				client.createMenuEntry(-1)
					.setOption("PocketGE chart")
					.setTarget(event.getTarget())
					.setType(MenuAction.RUNELITE)
					.onClick(e -> openPocketGeSearch(slotItemName, canon));
				/* And the card, from the offer itself: "add pocket ge inspect
				   to this right click here". */
				addInspectEntry(event.getTarget(), canon, slotItemName);
			}
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
	/** The Exchange's own inventory panel, which is a different interface
	 *  from the normal one — same id and same reason as
	 *  BankHighlightOverlay's copy. */
	private static final int GE_INVENTORY_GROUP = 467;

	/**
	 * Put an item on the inspect card, from the in-game right-click.
	 *
	 * The same two steps the sidebar's own inspectItem takes: name the item
	 * now so the card appears immediately, and let the next advisor cycle —
	 * which already builds a Row for whatever is being inspected — replace
	 * the stub with the priced one. Kept apart from the Actions
	 * implementation so the menu path does not depend on the panel's
	 * interface staying shaped the way it is.
	 */
	private void inspectItemFromMenu(int itemId, String name)
	{
		selectedFavoriteItemId = itemId;
		selectedFavoriteName = name;
		final FavoritesPanel.Row stub = new FavoritesPanel.Row();
		stub.id = itemId;
		stub.name = name;
		SwingUtilities.invokeLater(() -> mainPanel.showInspected(stub));
		/* So the card is priced rather than a name on its own — without this
		   it waits out the rest of the cycle, which is up to five minutes. */
		scheduleAdviceNow();
	}

	/**
	 * "PocketGE inspect": this item's card in the sidebar. With the card
	 * already up and some of it held, the entry swaps the card between your
	 * stack and a new trade instead — "right click a stack of items at any
	 * time to swap to see the other view". One helper for every menu it is
	 * on, so the bank, the inventory and a GE slot cannot drift apart.
	 */
	private void addInspectEntry(String target, int itemId, String name)
	{
		final Boolean swapTo = mainPanel != null ? mainPanel.inspectSwapTarget(itemId) : null;
		client.createMenuEntry(-1)
			.setOption(swapTo == null ? "PocketGE inspect" : swapTo ? "PocketGE trade idea" : "PocketGE my stack")
			.setTarget(target)
			.setType(MenuAction.RUNELITE)
			.onClick(e ->
			{
				if (swapTo == null)
				{
					inspectItemFromMenu(itemId, name);
				}
				/* openPanel selects the PocketGE tab and forces the sidebar
				   open, and asserts it is on the EDT. */
				SwingUtilities.invokeLater(() ->
				{
					if (swapTo != null)
					{
						mainPanel.setInspectView(swapTo);
					}
					if (navButton != null)
					{
						clientToolbar.openPanel(navButton);
					}
				});
			});
	}

	private void addBankFavoriteEntry(MenuEntryAdded event)
	{
		final int groupId = WidgetUtil.componentToInterface(event.getActionParam1());
		/* 467 is the Grand Exchange's OWN inventory panel, drawn beside the
		   offer screen as a separate interface from the normal inventory.
		   Without it the entries were missing at the one place the graph
		   option is gated to appear: right-clicking a potion with the
		   Exchange open offered "Offer / Examine / Cancel" and nothing else.
		   Exactly the trap the bank mark hit — see
		   BankHighlightOverlay.GE_INVENTORY_GROUP, which is the same id for
		   the same reason. */
		if (groupId != InterfaceID.BANKMAIN && groupId != InterfaceID.BANKSIDE
			&& groupId != InterfaceID.INVENTORY && groupId != InterfaceID.WORNITEMS
			&& groupId != GE_INVENTORY_GROUP)
		{
			return;
		}
		final int itemId = itemManager.canonicalize(event.getItemId());
		final ItemComposition comp = itemManager.getItemComposition(itemId);
		final String name = comp != null ? comp.getName() : ("Item " + itemId);
		final boolean fav = favoriteIdSet().contains(itemId);

		/*
		 * Straight to this item's card in the sidebar.
		 *
		 * Asked for as "I should be able to right click them and click
		 * pocketge suggestion and it pull it up to that inspect box
		 * suggestion area". Without it the only route to an item's card was
		 * to add it to the watchlist and click the row — which is how an
		 * account holding 4,811 amulets ended up on a card telling it to buy
		 * ten thousand more.
		 *
		 * openPanel both selects the PocketGE tab and forces the sidebar
		 * open, and it asserts it is on the EDT, hence invokeLater. It is the
		 * one call here the plugin has never made before, so it was checked
		 * against the real client source at the version the build pins rather
		 * than against the offline stub — the stub would have compiled either
		 * way, which is exactly how a wrong constant shipped once already.
		 */
		addInspectEntry(event.getTarget(), itemId, name);

		/*
		 * "PocketGE graph", everywhere — asked for as "I should be able to
		 * right click and open up a pocketge chart".
		 *
		 * It used to be gated on the Exchange being open, on the argument
		 * that right-clicking your inventory is something you do constantly
		 * and an extra entry everywhere is clutter the other 99% of the time.
		 * That argument is real, and it is why these sit at the bottom: the
		 * entries render in reverse, so the earliest-added are furthest from
		 * the option you were reaching for. "PocketGE inspect" is added
		 * before this one and therefore sits below it — the two safest slots
		 * go to the two entries that are on every item.
		 *
		 * The gate is one condition if it turns out to be in the way.
		 */
		client.createMenuEntry(-1)
			.setOption("PocketGE graph")
			.setTarget(event.getTarget())
			.setType(MenuAction.RUNELITE)
			.onClick(e -> openPocketGeSearch(name, itemId));

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
	/** One page poll interval plus slack: how recently a page running the
	 *  /nav loop must have asked, when none is parked at that instant — see
	 *  openPocketGeSearch for why 40 seconds of "polled recently" was the
	 *  wrong question to ask. */
	private static final long TAB_POLL_MS = 8_000;

	/** Keeps rising across plugin runs, not just within one. The website
	 *  polls at-least-once and will see the same request repeatedly, so it
	 *  needs a token to tell a NEW request from one it already acted on.
	 *
	 *  This started at 0 every run. The open tab outlives RuneLite and still
	 *  remembered the last run's seq, so after a restart every request
	 *  numbered below it: the /nav long-poll (since = that old seq) never
	 *  answered, and the page treated the copy in /flips as already handled.
	 *  The chat said the item was sent and the tab did nothing. Reported as
	 *  "right clicking in bank to open graph doesn't work ... it works if you
	 *  click the graph button on the inspect" — that button opens a new tab
	 *  instead of handing off. Seeding from the clock puts every run above
	 *  the last. */
	private final java.util.concurrent.atomic.AtomicLong navSeq =
		new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
	private volatile LocalBridgeServer.NavRequest pendingNav;

	/** How long a handoff waits for the tab to confirm before opening a new
	 *  tab instead. A tab that is there answers in milliseconds — the
	 *  request reaches it on an already-open connection, which background
	 *  throttling does not delay — so this only has to cover a busy machine.
	 *  The page stops acting on a request well before this (see
	 *  RL_NAV_ACK_WINDOW_MS in the site's app.js), so a slow tab and the new
	 *  one cannot both put the item up. */
	private static final long HANDOFF_ACK_MS = 2_500;

	/** One handed-off request, and whether it has been settled — confirmed by
	 *  a tab, or given up on and opened in a new one. Exactly one of those
	 *  happens: the ack and the timer race for {@code settled}. */
	private static final class Handoff
	{
		final LocalBridgeServer.NavRequest request;
		final java.util.concurrent.atomic.AtomicBoolean settled = new java.util.concurrent.atomic.AtomicBoolean();

		Handoff(LocalBridgeServer.NavRequest request)
		{
			this.request = request;
		}
	}

	/** The latest handoff waiting on its tab, or null. */
	private volatile Handoff handoff;

	/**
	 * The handoff's own timer, not RuneLite's shared executor.
	 *
	 * That executor is a single thread, and this plugin runs blocking price
	 * downloads on it; a timer queued behind one fired when the download
	 * finished, not after HANDOFF_ACK_MS — a dead tab's click opened after
	 * six or eight seconds instead of two and a half. One daemon thread,
	 * idle unless a handoff is waiting, shut down (never interrupted) with
	 * the plugin.
	 */
	private ScheduledThreadPoolExecutor handoffTimer;

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
	 * Liveness here is a proxy (see LocalBridgeServer.hasRecentClient), and
	 * it was wrong for up to half a minute after the tab was closed: every
	 * click in that window was "sent to your open tab" and went nowhere. So
	 * a page that can confirm (every current one) is waited on for
	 * HANDOFF_ACK_MS, and if it does not, the item opens in a new tab. This
	 * used to be ruled out because the only fallback timer available had to
	 * outlast the page's 5-second poll; the confirmation travels on the
	 * long poll's own connection and comes back in milliseconds, so the wait
	 * is short. An older page that cannot confirm keeps the old behaviour.
	 */
	private void openPocketGeSearch(String itemName)
	{
		openPocketGeSearch(itemName, 0);
	}

	private void openPocketGeSearch(String itemName, int itemId)
	{
		openPocketGeSearch(itemName, itemId, false);
	}

	/**
	 * @param force ask for the handoff whatever the setting says. The
	 *              right-click menu item IS the opt-in — see
	 *              sendChartToOpenTab, whose comment has claimed this was
	 *              true since before it was.
	 */
	private void openPocketGeSearch(String itemName, int itemId, boolean force)
	{
		if (itemName == null || itemName.trim().isEmpty())
		{
			return;
		}
		/*
		 * Hand off only when something is actually listening.
		 *
		 * This used to ask hasRecentClient(40s), and 40 seconds of "a page
		 * polled at some point recently" is not the same claim as "a page will
		 * receive this". It stays true for a tab you closed thirty seconds ago,
		 * and for one the browser froze behind the game — and in both cases the
		 * click did nothing at all, silently, while the chat line said it had
		 * been sent. Reported as "the chart button doesn't always work, but it
		 * works every time once I've opened a new tab": opening one creates a
		 * genuinely connected page, and from then on the handoff has somewhere
		 * to land.
		 *
		 * A parked /nav request is far better evidence, and the short window
		 * covers the moment between two of a page's requests. Neither is
		 * proof — a closed tab's park looks the same until it times out — so
		 * a page that can confirm is waited on (awaitHandoff), and anything
		 * else launches a browser, which always does something visible.
		 */
		settlePendingHandoff(itemName);
		/* Only a page running the /nav loop can take this, so only that counts
		   — the Flip History page polls /flips too and never takes a click.
		   And not a bridge that has been switched off since its last poll. */
		final LocalBridgeServer b = bridge;
		final boolean listening = b != null && b.port() > 0 && b.hasNavListener(TAB_POLL_MS);
		/*
		 * `force` is the whole reason the right-click exists.
		 *
		 * This read `config.reuseBrowserTab() && listening`, and that setting
		 * defaults to OFF — it was flipped so a plain click would open a tab
		 * rather than attempt a handoff that could fail silently. Which meant
		 * the menu item literally labelled "Send to my open PocketGE tab"
		 * asked for the handoff, was refused by a setting the user had never
		 * touched, and opened a new tab instead. Reported as "click to send to
		 * a new tab works but still not right-clicking to send to open tab".
		 *
		 * sendChartToOpenTab's own comment already said it "deliberately
		 * ignores reuseBrowserTab: picking this item off the menu IS the
		 * opt-in". The comment was right and the code did not do it.
		 */
		if ((force || config.reuseBrowserTab()) && listening)
		{
			final ScheduledThreadPoolExecutor timer = handoffTimer;
			final boolean confirm = timer != null && b.hasConfirmingListener();
			final LocalBridgeServer.NavRequest request = new LocalBridgeServer.NavRequest(
				navSeq.incrementAndGet(), itemName, itemId, System.currentTimeMillis(), confirm);
			pendingNav = request;
			if (confirm)
			{
				// The chat line waits for the tab's word — see onHandoffAcked.
				final Handoff h = new Handoff(request);
				handoff = h;
				b.publishNav(request);
				awaitHandoff(timer, h);
				return;
			}
			/* Two delivery routes for one request, on purpose.
			 *
			 * pendingNav rides along in the /flips payload as before, so a page
			 * that has not reloaded since this change still works. publishNav
			 * wakes any parked /nav long-poll, which is the fast one: the poll
			 * route costs up to a full 5-second interval before the page even
			 * asks, and much longer once the browser throttles the timers of a
			 * tab sitting behind the game. Both carry the same seq, so whichever
			 * arrives second is deduped and does nothing. */
			b.publishNav(request);
			announceSentToTab(itemName);
			return;
		}
		if (force)
		{
			/* Asked for explicitly and not possible. Without this the menu
			   item opens a tab, which is exactly what a plain left-click
			   does — so "Send to my open PocketGE tab" would look like it had
			   been ignored rather than like there was nothing to send to. */
			announceNoTabListening(itemName);
		}
		browsePocketGe(itemName);
	}

	/**
	 * Give the tab HANDOFF_ACK_MS to confirm, then open the item in a new tab
	 * if it has not. A closed tab, one the browser has frozen or put to sleep,
	 * and one too old to confirm all look like this from here, and in every
	 * case a new tab is what the click needed.
	 */
	private void awaitHandoff(ScheduledThreadPoolExecutor timer, Handoff h)
	{
		final Runnable giveUp = () ->
		{
			if (!running || !h.settled.compareAndSet(false, true))
			{
				return; // confirmed in time, clicked past, or the plugin is going away
			}
			if (handoff == h)
			{
				handoff = null;
			}
			/* Whatever made a tab look open was wrong, so stop believing it
			   until a tab asks again — otherwise the next click waits out the
			   same silence. Only what was seen up to the click: a tab that
			   asked again since is evidence this did not disprove. */
			final LocalBridgeServer b = bridge;
			if (b != null)
			{
				b.forgetClients(h.request.at);
			}
			announceTabDidNotAnswer(h.request.query);
			browsePocketGe(h.request.query);
		};
		try
		{
			timer.schedule(giveUp, HANDOFF_ACK_MS, TimeUnit.MILLISECONDS);
		}
		catch (java.util.concurrent.RejectedExecutionException shuttingDown)
		{
			// Nothing will confirm it now either.
		}
	}

	/** How long a handoff goes unconfirmed before its tab is taken to be
	 *  gone. A tab that is there confirms in tens of milliseconds, or about
	 *  half a second when it first yields to the tab you used last. */
	private static final long HANDOFF_PRESUMED_LOST_MS = 700;

	/**
	 * Another chart click while an earlier one is still waiting on its tab.
	 *
	 * It used to be opened 2.5 seconds later anyway — on top of the tab the
	 * new click had just opened — or, when the new click was also handed
	 * off, dropped AND the wait restarted, so clicking once a second at a
	 * tab that had gone opened nothing at all until you stopped.
	 *
	 * Now the earlier click is settled here. If it has gone unanswered long
	 * enough that its tab is gone, and it was for a different item, it opens
	 * now — before the new one, so the new one lands on top — rather than
	 * being lost. A re-click of the same item needs nothing extra, and one
	 * made within moments of the first leaves the tab its chance to answer.
	 *
	 * A tab that is there answers in milliseconds and asks /nav again the
	 * moment it is handed something, so if the earlier click is still
	 * unanswered, nothing seen BEFORE it can be trusted: forgetting that
	 * evidence lets this click open a new tab at once, while a live tab's
	 * newer request keeps it believed.
	 */
	private void settlePendingHandoff(String nextItem)
	{
		final Handoff h = handoff;
		if (h == null)
		{
			return;
		}
		handoff = null;
		if (!h.settled.compareAndSet(false, true))
		{
			return;
		}
		/* Too soon to call it lost: a tab that is there may not have had the
		   moment it needs to answer, or to ask /nav again. Forgetting the
		   evidence now sent a quick second click to a new tab while the open
		   tab was still showing the first. So the earlier click is simply
		   superseded, and this one is handed to the same tab. */
		if (System.currentTimeMillis() - h.request.at < HANDOFF_PRESUMED_LOST_MS)
		{
			return;
		}
		final LocalBridgeServer b = bridge;
		if (b != null)
		{
			b.forgetClients(h.request.at);
		}
		final String earlier = h.request.query;
		if (earlier != null && !earlier.equalsIgnoreCase(nextItem))
		{
			announceTabDidNotAnswer(earlier);
			browsePocketGe(earlier);
		}
	}

	/** A tab confirmed it took request {@code seq}. On the bridge's HTTP
	 *  thread; ChatMessageManager.queue is safe to call from any thread. */
	private void onHandoffAcked(long seq)
	{
		final Handoff h = handoff;
		if (h != null && h.request.seq == seq && h.settled.compareAndSet(false, true))
		{
			announceSentToTab(h.request.query);
		}
	}

	/** Hand the item to the system browser. The one place that builds the
	 *  URL, so the handoff's fallback and the explicit "new tab" right-click
	 *  cannot drift into opening different pages for the same item. */
	private void browsePocketGe(String itemName)
	{
		if (itemName == null || itemName.trim().isEmpty())
		{
			return;
		}
		final String encoded = URLEncoder.encode(itemName, StandardCharsets.UTF_8).replace("+", "%20");
		LinkBrowser.browse(PocketGeLinks.item(encoded, "chart"));
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
	/**
	 * Top up the 30-day ranges for the items the plan is actually
	 * recommending. Runs on the background executor (see scheduleRange30),
	 * after the plan exists, and is bounded three ways: only plan positions, only ones whose
	 * cache entry is missing or stale, and at most
	 * MAX_RANGE30_FETCHES_PER_CYCLE new requests.
	 */
	private void scheduleRange30(List<CapitalPlanner.Position> positions)
	{
		final ScheduledExecutorService ex = executor;
		/* Not logged out: the cards these footnote are behind "log in to
		   start", and a client left on the login screen overnight was
		   topping the plan's ranges up every half hour. */
		if (!running || !loggedIn || ex == null || positions == null || positions.isEmpty()
			|| !range30Running.compareAndSet(false, true))
		{
			return;
		}
		final List<CapitalPlanner.Position> copy = new ArrayList<>(positions);
		try
		{
			ex.execute(() ->
			{
				try
				{
					if (running && refreshRange30(copy) > 0)
					{
						recomputeAdvice(); // put the new footnotes on the cards now
					}
				}
				finally
				{
					range30Running.set(false);
				}
			});
		}
		catch (RuntimeException e)
		{
			range30Running.set(false); // executor shut down under us
		}
	}

	/** One top-up at a time, however often the advice is recomputed. */
	private final java.util.concurrent.atomic.AtomicBoolean range30Running = new java.util.concurrent.atomic.AtomicBoolean();
	/** How long a failed range waits before it is asked for again. */
	private static final long RANGE30_RETRY_MS = 5 * 60 * 1000L;

	/** Background thread only. Returns how many ranges it fetched. */
	private int refreshRange30(List<CapitalPlanner.Position> positions)
	{
		if (positions == null || positions.isEmpty())
		{
			return 0;
		}
		final long now = System.currentTimeMillis();
		int fetched = 0;
		int sent = 0;
		for (CapitalPlanner.Position pos : positions)
		{
			/* Requests, not successes: counting only the ones that worked let
			   a pass with failing requests try every position at once. */
			if (sent >= MAX_RANGE30_FETCHES_PER_CYCLE || !running || !config.advisor())
			{
				break;
			}
			final Long at = range30FetchedAt.get(pos.id);
			if (at != null && now - at < RANGE30_TTL_MS)
			{
				continue;
			}
			sent++;
			try
			{
				final RangePosition r = marketClient.fetchRange30(pos.id);
				range30.put(pos.id, r);
				/* Stamped only on success, so a failed request retries next
				   cycle rather than being cached as "this item has no range"
				   for half an hour — the same trap refreshDayExtremes fell
				   into. */
				range30FetchedAt.put(pos.id, now);
				fetched++;
			}
			catch (MarketClient.BackingOff b)
			{
				break; // the API asked us to wait: no more calls this round
			}
			catch (Exception e)
			{
				/* Stamped so it is not asked for again on every recompute —
				   which happens on every click in the panel — but retried
				   well before a successful entry would be. */
				range30FetchedAt.put(pos.id, now - RANGE30_TTL_MS + RANGE30_RETRY_MS);
				log.warn("PocketGE advisor: 30-day range fetch failed for item {}", pos.id, e);
			}
		}
		if (range30.size() > RANGE30_CACHE_CAP)
		{
			/* Coarse but sufficient: these only ever hold plan positions, so
			   the map is tiny and a full clear costs at most one refetch of
			   the current handful. */
			range30.clear();
			range30FetchedAt.clear();
		}
		return fetched;
	}

	/**
	 * The engine's verdict on one item, computed at most once per advisor
	 * cycle. Null when there is no price series cached for it, which means
	 * "no opinion" rather than "no good".
	 *
	 * Memoised because the plan positions and the queued buy ideas overlap,
	 * and compute() is the most expensive pure function in the plugin — its
	 * widen loop is bounded at 20,000 iterations over a few hundred buckets.
	 * This all runs on the client thread, so calling it twice for the same
	 * item is a frame budget spent on an answer already in hand.
	 */
	/**
	 * Where a buy is projected to sell back, for every "if it flips" figure:
	 * the engine's sell — pocketge.com's target — unclamped, or the last
	 * insta-buy when there is no series. One helper so the slot, the sidebar
	 * square, the margin check and the cards cannot each pick their own.
	 *
	 * Deliberately not TradeEngine.sellTarget's clamp. That raises an ASK to
	 * the standing insta-buy when the market is paying more right now, which
	 * is right for the price you are about to type; a projection made before
	 * the units are even bought is the conservative engine figure, and is
	 * the one the website shows.
	 */
	private long flipExit(int itemId, Advisor.Quote q, Map<Integer, TradeEngine.Result> cache)
	{
		final TradeEngine.Result eng = engineVerdict(itemId, q, cache);
		return eng != null && eng.viable && eng.sell > 0 ? eng.sell : (q != null ? q.high : 0);
	}

	private TradeEngine.Result engineVerdict(int itemId, Advisor.Quote q,
		Map<Integer, TradeEngine.Result> cache)
	{
		if (q == null || q.low <= 0 || q.high <= 0)
		{
			return null;
		}
		if (cache.containsKey(itemId))
		{
			return cache.get(itemId);
		}
		final TradeEngine.Series series = seriesFor(itemId);
		TradeEngine.Result r = null;
		if (series != null)
		{
			try
			{
				r = TradeEngine.viewTargets(q.low, q.high, q.lowTime, q.highTime, series, itemId);
			}
			catch (RuntimeException e)
			{
				/* One item's bad series must never take down the whole advice
				   cycle — the rest of the cards are still good. */
				log.warn("PocketGE advisor: trade engine failed for item {}", itemId, e);
			}
		}
		cache.put(itemId, r);
		return r;
	}

	/**
	 * Re-price one BUY card at the engine's targets, or reject it.
	 *
	 * Returns false when the idea should be dropped: either the engine
	 * examined it and could not certify a fillable pair that clears the tax,
	 * or its own edge turns out to be nothing. A raw spread says what the
	 * last two prints were; it does not say that both sides are reachable
	 * together, and the gap between those two claims is where the card's
	 * optimism lived.
	 *
	 * With no series cached the card keeps its raw numbers — no opinion is
	 * not a rejection, and refusing to show anything until a fetch lands
	 * would blank the panel on every fresh idea.
	 */
	private boolean applyEngineToBuy(AdvisorPanel.Rec rec, TradeEngine.Result eng, long cash, long dailyVolume)
	{
		if (eng == null)
		{
			return true; // nothing fetched for this item yet
		}
		if (!eng.viable || eng.buy <= 0 || eng.sell <= eng.buy)
		{
			return false;
		}
		final long unitEdge = eng.sell - FlipTracker.taxPerItem(eng.sell, rec.itemId) - eng.buy;
		if (unitEdge <= 0)
		{
			return false;
		}
		/* The planner sized this against the RAW price. At the engine's buy
		   the same gold goes further or less far, so the quantity is re-capped
		   against cash — the limit and volume ceilings the planner applied are
		   already baked into rec.quantity and only ever shrink it here. */
		int qty = rec.quantity;
		if ((long) qty * eng.buy > cash && cash > 0)
		{
			qty = (int) Math.min(Integer.MAX_VALUE, cash / eng.buy);
		}
		if (qty <= 0)
		{
			return false;
		}
		rec.quantity = qty;
		rec.unitPrice = eng.buy;
		rec.exitPrice = eng.sell;
		rec.capital = (long) qty * eng.buy;
		rec.profit = unitEdge * qty;
		/* Scored the way the site scores the same pair: the engine's own edge
		   over its buy, the day's volume, and its thin-tape flag. Only here,
		   after the engine has spoken — a raw-spread card has no score,
		   because the number would be rating a pair nobody certified. */
		rec.score = TradeEngine.FlipScore.of((double) eng.edge / Math.max(1, eng.buy), dailyVolume, eng.lowConf);
		return true;
	}

	private static long exitPriceFor(Map<Integer, Advisor.Quote> quotes, int itemId)
	{
		final Advisor.Quote q = quotes != null ? quotes.get(itemId) : null;
		return q != null && q.high > q.low && q.low > 0 ? q.high : 0;
	}

	/**
	 * Everything the plugin knows about one item, as plain text for the
	 * player to paste when something on a card looks wrong.
	 *
	 * Asked for after two cards that could not be explained from pictures:
	 * a stack shown at -550K under a SELL @ that made it -175K, and a PAID @
	 * of 964 on necklaces bought at 453-559. Both turn on numbers no screen
	 * shows — the buy lots behind PAID @, the fills that made them, what each
	 * slot was last seen at — so the report is those, raw.
	 *
	 * Nothing that identifies the player: no name, no account hash (the
	 * slot baselines are keyed by it and printed without it). Nothing is sent
	 * anywhere; it goes to the clipboard and the player decides.
	 */
	String buildReport(int itemId, String itemName, String card)
	{
		final StringBuilder sb = new StringBuilder();
		final long now = System.currentTimeMillis();
		sb.append("PocketGE report (format 1) — ").append(itemName).append(" (").append(itemId).append(")\n");
		sb.append("made ").append(java.time.Instant.ofEpochMilli(now)).append(" by PocketGE ").append(BuildInfo.label()).append("\n");
		sb.append("settings: advisor ").append(config.advisor())
			.append(", minProfit ").append(config.minProfit())
			.append(", reuseTab ").append(config.reuseBrowserTab())
			.append(", bridge ").append(config.localBridge()).append("\n");

		sb.append("\n== the card ==\n").append(card == null ? "(none)\n" : card);

		sb.append("\n== held ==\n");
		sb.append("  bank ").append(lastBank.getOrDefault(itemId, 0))
			.append(" (bank last read ").append(bankSeenAt > 0 ? ((now - bankSeenAt) / 60_000) + " min ago" : "never").append(")\n");
		final Map<Integer, Integer> holdings = currentHoldings();
		sb.append("  inventory ").append(holdings.getOrDefault(itemId, 0) - lastBank.getOrDefault(itemId, 0))
			.append(" (noted included)\n");

		final FlipTracker.State st = tracker.snapshot();
		sb.append("\n== open buy lots (what PAID @ is made of) ==\n");
		final List<long[]> lots = st.openBuys != null ? st.openBuys.get(itemId) : null;
		long lotQty = 0;
		long lotSpent = 0;
		if (lots == null || lots.isEmpty())
		{
			sb.append("  none\n");
		}
		else
		{
			for (long[] lot : lots)
			{
				lotQty += lot[0];
				lotSpent += lot[1];
			}
			final long held = holdings.getOrDefault(itemId, 0) + inGeSlots(itemId);
			for (int i = 0; i < lots.size(); i++)
			{
				final long[] lot = lots.get(i);
				sb.append("  ").append(lot[0]).append(" for ").append(lot[1])
					.append(" (").append(lot[0] > 0 ? String.format("%.1f", lot[1] / (double) lot[0]) : "-").append(" each)")
					.append(lot.length > 2 && lot[2] > 0 ? " at " + java.time.Instant.ofEpochMilli(lot[2]) : " (no time)")
					/* A fact, not a verdict: an honest refill at the same bid
					   while the client was closed looks the same. Whoever
					   reads the report weighs it against what is held. */
					.append(repeatsEarlierLots(lots, i)
						? "  <- same total as the lots just before it (an old build's hop re-book, or a same-bid refill while closed)"
						: "")
					.append("\n");
			}
			sb.append("  total ").append(lotQty).append(" for ").append(lotSpent)
				.append(" (").append(lotQty > 0 ? String.format("%.1f", lotSpent / (double) lotQty) : "-").append(" each)\n");
			if (lotQty > held)
			{
				sb.append("  more than this character holds (bank + inventory + GE slots = ").append(held)
					.append("): lots are shared by all your characters; or counted twice, or the bank not read since\n");
			}
		}

		sb.append("\n== recent fills of this item (oldest first, up to 40) ==\n");
		final List<TradeFill> fills = new ArrayList<>();
		for (TradeFill f : tracker.getFills())
		{
			if (f.itemId == itemId)
			{
				fills.add(f);
			}
		}
		if (fills.isEmpty())
		{
			sb.append("  none in the recent window\n");
		}
		for (TradeFill f : fills.subList(Math.max(0, fills.size() - 40), fills.size()))
		{
			sb.append("  ").append(java.time.Instant.ofEpochMilli(f.time)).append(f.buy ? " BUY  " : " SELL ")
				.append(f.quantity).append(" for ").append(f.spent)
				.append(" (").append(f.quantity > 0 ? f.spent / f.quantity : 0).append(" each)\n");
		}

		sb.append("\n== GE slots ==\n");
		final GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		final Map<Integer, long[]> seen = st.slotsByAccount != null ? st.slotsByAccount.get(st.accountHash) : null;
		for (int i = 0; offers != null && i < offers.length; i++)
		{
			final GrandExchangeOffer o = offers[i];
			sb.append("  slot ").append(i + 1).append(": ");
			if (o == null || o.getItemId() <= 0)
			{
				sb.append("empty");
			}
			else
			{
				final int id = itemManager != null ? itemManager.canonicalize(o.getItemId()) : o.getItemId();
				sb.append(o.getState()).append(" ").append(id == itemId ? itemName : "item " + id)
					.append(" @ ").append(o.getPrice())
					.append(", ").append(o.getQuantitySold()).append("/").append(o.getTotalQuantity())
					.append(", spent ").append(o.getSpent());
			}
			final long[] base = seen != null ? seen.get(i) : null;
			if (base != null && base.length >= 6)
			{
				sb.append(" | tracker saw item ").append(base[0]).append(base[1] == 1 ? " buy" : " sell")
					.append(" ").append(base[2]).append("/").append(base[5])
					.append(", spent ").append(base[3]).append(" @ ").append(base[4]);
			}
			final long[] box = collectBox.get(i);
			if (box != null)
			{
				sb.append(" | box ").append(box[0]).append(" gp + ").append(box[1]);
			}
			sb.append("\n");
		}

		sb.append("\n== market ==\n");
		final Advisor.Quote q = lastQuotes.get(itemId);
		final long nowSec = now / 1000;
		if (q == null)
		{
			sb.append("  no quote\n");
		}
		else
		{
			sb.append("  insta-sell (low) ").append(q.low).append(q.lowTime > 0 ? " " + (nowSec - q.lowTime) + "s ago" : "")
				.append(", insta-buy (high) ").append(q.high).append(q.highTime > 0 ? " " + (nowSec - q.highTime) + "s ago" : "")
				.append("\n");
			final TradeEngine.Series series = seriesFor(itemId);
			final TradeEngine.Result eng = series == null ? null
				: TradeEngine.viewTargets(q.low, q.high, q.lowTime, q.highTime, series, itemId);
			sb.append("  engine ").append(eng == null ? "no series" : eng.buy + " / " + eng.sell
				+ (eng.viable ? "" : " (not viable)")).append("\n");
		}
		return sb.toString();
	}

	/** The other half of the same promise: say when the handoff could NOT
	 *  happen, because falling back to a new tab looks identical to a plain
	 *  left-click and would read as the menu item doing nothing. */
	private void announceNoTabListening(String itemName)
	{
		final String message = new ChatMessageBuilder()
			.append(Color.decode("#E5C158"), "PocketGE")
			.append(Color.WHITE, " has no open tab listening — opened ")
			.append(Color.decode("#1FB85C"), itemName)
			.append(Color.WHITE, " in a new one")
			.build();
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.runeLiteFormattedMessage(message)
			.build());
	}

	/** The tab a click was handed to never said it took it, so the item
	 *  opened in a new tab instead. Said, because the new tab arriving a
	 *  couple of seconds late otherwise looks like a slow left-click, and
	 *  after "Send to my open PocketGE tab" like the menu item was ignored. */
	private void announceTabDidNotAnswer(String itemName)
	{
		final String message = new ChatMessageBuilder()
			.append(Color.decode("#E5C158"), "PocketGE")
			.append(Color.WHITE, ": your open tab didn't answer — opened ")
			.append(Color.decode("#1FB85C"), itemName)
			.append(Color.WHITE, " in a new one")
			.build();
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.runeLiteFormattedMessage(message)
			.build());
	}

	/** So a handoff is never a click that appears to do nothing — if your
	 *  PocketGE tab is behind the client you would otherwise have no idea
	 *  anything happened. */
	private void announceSentToTab(String itemName)
	{
		/*
		 * "opened X in your browser tab" was a promise the handoff cannot
		 * keep. The tab really does navigate — that is what this path did —
		 * but nothing brings the browser forward, because a page cannot raise
		 * its own window without a user gesture and no browser will let one
		 * try. So the window stays behind the client and the click reads as
		 * having done nothing at all.
		 *
		 * Says what actually happened, and what to do about it. The
		 * alternative behaviour is one setting away — "Send charts to an open
		 * PocketGE tab", off — which opens a fresh tab instead, and opening a
		 * link DOES raise the browser.
		 */
		final String message = new ChatMessageBuilder()
			.append(Color.decode("#E5C158"), "PocketGE")
			.append(Color.WHITE, " sent ")
			.append(Color.decode("#1FB85C"), itemName)
			.append(Color.WHITE, " to your open tab — alt-tab to see it")
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
	/** Hand the panel the current settings now, for preferences that change
	 *  how it draws. Without this they wait for the next advisor cycle. */
	/** Hand the bank overlays the theme's SELL colour — everything a bank
	 *  square marks is something to sell, so that is the only hue involved;
	 *  the live suggestion is picked out in white. Cheap enough to call on
	 *  any path that touches the overlays. */
	private void pushBankTheme()
	{
		final PocketGeTrackerConfig.ColourTheme t = config.colourTheme();
		if (t != null)
		{
			BankHighlightOverlay.setTheme(t.sell());
			/* The watchlist's 5-day badges speak the same two colours: a
			   5-day high is something to sell, a 5-day low something to
			   buy. */
			FavoritesPanel.setTheme(t.buy(), t.sell());
		}
	}

	private void pushSettings()
	{
		if (mainPanel == null)
		{
			return;
		}
		final AdvisorPanel.Settings s = buildSettings();
		SwingUtilities.invokeLater(() -> mainPanel.applySettings(s));
	}

	private AdvisorPanel.Settings buildSettings()
	{
		final AdvisorPanel.Settings s = new AdvisorPanel.Settings();
		s.advisorOn = config.advisor();
		s.interval = config.adjustInterval();
		s.blocked = Blocklist.parse(config.blocklist());
		s.bridgeOn = config.localBridge();
		s.bridgePort = config.bridgePort();
		s.minProfit = config.minProfit();
		s.theme = config.colourTheme();
		s.showFlipScore = config.showFlipScore();
		s.confirmBlock = config.confirmBlock();
		s.bankLegend = config.bankLegend();
		s.bankHighlights = config.bankHighlights();
		s.holdingTips = config.holdingTips();
		s.priceChart = config.priceChart();
		s.followGeOffer = config.followGeOffer();
		s.showBadges = config.showBadges();
		s.priceAlert = config.priceAlerts();
		s.reuseBrowserTab = config.reuseBrowserTab();
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
		/* The flip list rides with the stats now — it shows the range they
		   are for, so both are worked out from one read of the flips. */
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

			final Map<Integer, long[]> openBuys = tracker.getHeldCost(heldForCost(holdings, offers));
			final Map<Integer, long[]> olderBuys = currentOlderBuys(openBuys);

			/*
			 * Marked at the price the plugin would actually tell you to sell
			 * at, after tax — see PortfolioValuer.netExit.
			 *
			 * This was qty * q.low - spent: the wrong side of the book AND no
			 * tax, which is not conservative, just inconsistent. It put the
			 * header a whole spread plus 2% away from the sell card for the
			 * same stack, far enough to flip the sign — "unrealized -5.2K"
			 * above a card offering +12K on the very same necklaces.
			 *
			 * It also has to agree with how the flip is eventually BOOKED, or
			 * the number jumps the moment you take the advice. A closed flip
			 * is sellGross - tax - buySpent, so an open one is the same thing
			 * at today's exit.
			 */
			long unrealized = 0;
			for (Map.Entry<Integer, long[]> e : openBuys.entrySet())
			{
				final long net = PortfolioValuer.netExit(quotes.get(e.getKey()), e.getKey());
				if (net <= 0)
				{
					continue;
				}
				final long qty = e.getValue()[0];
				final long spent = e.getValue()[1];
				unrealized += qty * net - spent;
			}

			final FlipStats.Range range = currentRange;
			final java.util.List<Flip> flips = flipsForRanges();
			final long firstFlipMillis = flips.isEmpty() ? System.currentTimeMillis() : flips.get(0).closedAt;
			final long statsNow = System.currentTimeMillis();
			final FlipStats.Stats stats = FlipStats.compute(
				flips, range, statsNow, tracker.getSessionStartMillis(), firstFlipMillis, unrealized);
			/* The list under the stats shows the same range they add up: "if
			   you have 1h youd see the flips you made the last 1h. if you have
			   all time, then youd see all the flips youve ever made". It used
			   to show the latest 50 whatever the range said. */
			final java.util.List<Flip> flipsInRange = FlipStats.inRange(flips, range, statsNow, tracker.getSessionStartMillis());
			rangeExpiresAt = FlipStats.nextExpiry(flipsInRange, range);

			final List<FavoriteLists.FavoriteList> favLists = loadFavoriteLists();
			final FavoriteLists.FavoriteList activeList = activeFavoriteList(favLists);
			final List<FavoritesPanel.Row> favRows = new ArrayList<>();
			/* Favourites, plus whatever is being inspected if it is not one of
			   them. A Find Opportunities row is not a favourite, so nothing
			   here would otherwise ever build it a Row — and the inspection
			   card is driven entirely by Rows. Built through the same loop as
			   the favourites rather than a second code path, so an inspected
			   item cannot end up with different numbers from the same item
			   sitting in the watchlist. It is kept OUT of favRows below, so it
			   never appears in the list itself. */
			final List<Favorites.Fav> toBuild = new ArrayList<>(
				activeList != null ? activeList.items : List.<Favorites.Fav>of());
			final Integer inspectedId = selectedFavoriteItemId;
			final String inspectedName = selectedFavoriteName;
			final boolean inspectedIsFavourite = inspectedId != null
				&& toBuild.stream().anyMatch(f -> f.id == inspectedId);
			if (inspectedId != null && !inspectedIsFavourite && inspectedName != null)
			{
				toBuild.add(new Favorites.Fav(inspectedId, inspectedName));
			}
			final List<FavoritesPanel.Row> allRows = new ArrayList<>();
			for (Favorites.Fav f : toBuild)
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
				/* Detail-view targets.
				 *
				 * The raw book first, then the trade engine when this item has
				 * a price series — the same order of preference the GE offer
				 * screen and the ADJUST suggestions already use.
				 *
				 * This used to be the raw book ALONE, gated on q.high > q.low,
				 * and that is why the card could flatly contradict the website.
				 * Emerald necklace printed an insta-buy and an insta-sell of
				 * 709 within ten minutes of each other: a zero spread, so the
				 * gate failed, no targets were set, and the card said "no
				 * margin after tax right now" — while pocketge.com, looking at
				 * the same item through the same engine this plugin already
				 * carries a port of, showed buy 709 / sell 746 and +414K over a
				 * full 4-hour limit. The engine's whole job is to find a
				 * reachable price the last two prints do not happen to show,
				 * and the card was the one place still refusing to ask it.
				 *
				 * NOT clamped to the live book, deliberately — unlike the offer
				 * screen (see onScriptPostFired, TradeEngine.sellTarget). There
				 * you have already committed to a side and must not be quoted
				 * through the standing bid; here you are still deciding, which
				 * is exactly what the website's own TARGET BUY / TARGET SELL
				 * boxes show, and parity with those is the point of this view.
				 *
				 * The reason given here used to be that clamping "would
				 * reproduce the bug: sell clamped up to a 709 bid is a zero
				 * spread again". That is not so — sellTarget only ever raises
				 * the ask and buyTarget only ever lowers the bid, so the pair
				 * can only widen and a clamp cannot collapse it. The real
				 * cost of clamping here is the one above: it would stop this
				 * card agreeing with the website. Worth writing down
				 * correctly, because a wrong reason is what stops the next
				 * person re-examining the decision. */
				if (q != null && q.low > 0)
				{
					final TradeEngine.Series series = seriesFor(f.id);
					if (series != null)
					{
						final TradeEngine.Result engine =
							TradeEngine.viewTargets(q.low, q.high, q.lowTime, q.highTime, series, f.id);
						if (engine != null && engine.viable && engine.buy > 0 && engine.sell > engine.buy)
						{
							row.targetBuy = engine.buy;
							row.targetSell = engine.sell;
						}
					}
					if (row.targetBuy <= 0 && q.high > q.low)
					{
						row.targetBuy = q.low;
						row.targetSell = q.high;
					}
				}
				if (row.targetBuy > 0 && row.targetSell > row.targetBuy)
				{
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
						/* Tax on the price you actually SELL at, which is now
						   the target rather than the raw high — otherwise the
						   plugin's net would differ from the website's by the
						   tax on the difference between the two. */
						final long edge = row.targetSell - row.targetBuy
							- FlipTracker.taxPerItem(row.targetSell, f.id);
						row.potentialProfit = edge * row.limit;
					}
				}
				row.dailyVolume = favVolumes.getOrDefault(f.id, 0L);
				fillOfferPosition(row, offers);
				fillHeldPosition(row, q, holdings, openBuys);
				row.heldWhere = positionWhere(row.listedQty, heldWhere(f.id));
				/* Off the same /24h response the ranked sell cards use, so
				   the inspect card and the sidebar cannot quote different
				   figures for the same stack. Needs heldQty, so it goes
				   after fillHeldPosition. */
				final AnalystRating.Average favAvg = averages.get(f.id);
				row.clearance = favAvg == null ? null
					: Clearance.of(row.heldQty, favAvg.highPriceVolume, favAvg.lowPriceVolume);
				/* Day or 5-day, decided in one place and by the website's own
				   rules — see PriceExtremes.tier. A big intraday move outranks
				   both, and that is decided in the panel off changePct. */
				final PriceExtremes ex = dayExtremes.get(f.id);
				row.tier = ex != null && q != null
					? ex.tier(q.high, q.low) : PriceExtremes.Tier.NONE;
				allRows.add(row);
				if (inspectedIsFavourite || inspectedId == null || f.id != inspectedId)
				{
					favRows.add(row);
				}
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
				mainPanel.updateStats(stats, portfolio, tracker.getSessionStartMillis());
				mainPanel.updateHistory(flipsInRange, range);
				mainPanel.updateFavoriteLists(listMetas, activeListId);
				/* The watchlist shows favourites only; the inspection card may
				   be pointed at something that is not one, so it gets the
				   wider list to re-read its own item from. */
				mainPanel.setOlderBuys(olderBuys);
				mainPanel.updateFavorites(favRows, allRows);
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
