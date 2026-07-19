package com.pocketge.tracker;

import com.google.gson.Gson;
import com.google.inject.Provides;
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
import net.runelite.api.events.GrandExchangeOfferChanged;
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

	private final FlipTracker tracker = new FlipTracker();
	private LocalBridgeServer bridge;
	private PocketGeTrackerPanel panel;
	private NavigationButton navButton;

	private AdvisorPanel advisorPanel;
	private NavigationButton advisorButton;
	private ScheduledFuture<?> advisorTask;
	/** Coins are item id 995 in every container. */
	private static final int COINS_ID = 995;
	/** Session-only skips (item ids); cleared on logout via reset. */
	private final Set<Integer> skipped = new HashSet<>();
	/** Last bank snapshot (item id -> qty), refreshed whenever the bank opens. */
	private final Map<Integer, Integer> lastBank = new HashMap<>();
	private volatile Map<Integer, Advisor.Quote> lastQuotes = new HashMap<>();
	private volatile Map<Integer, Long> lastVolumes = new HashMap<>();

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
		panel = new PocketGeTrackerPanel(() ->
		{
			/* "Reset session" zeroes the session counter only — lifetime
			   P/L and flip history survive (full wipe lives in config). */
			tracker.resetSession();
			refreshPanel();
			saveState();
		});
		final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "icon.png");
		navButton = NavigationButton.builder()
			.tooltip("PocketGE Flip Tracker")
			.icon(icon)
			.priority(6)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		advisorPanel = new AdvisorPanel(new AdvisorPanel.Actions()
		{
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
		});
		advisorButton = NavigationButton.builder()
			.tooltip("PocketGE Flip Advisor")
			.icon(icon)
			.priority(7)
			.panel(advisorPanel)
			.build();
		clientToolbar.addNavigation(advisorButton);

		refreshPanel();
		syncBridge();
		syncAdvisor();
	}

	@Override
	protected void shutDown()
	{
		saveState();
		clientToolbar.removeNavigation(navButton);
		clientToolbar.removeNavigation(advisorButton);
		if (advisorTask != null)
		{
			advisorTask.cancel(false);
			advisorTask = null;
		}
		if (bridge != null)
		{
			bridge.stop();
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
		if (advisorPanel == null)
		{
			return;
		}
		if (!config.advisor())
		{
			SwingUtilities.invokeLater(() ->
			{
				advisorPanel.setStatus("Advisor off — enable it in settings");
				advisorPanel.update(new ArrayList<>(), Blocklist.parse(config.blocklist()));
			});
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
		}
		catch (Exception e)
		{
			log.warn("PocketGE advisor: price fetch failed", e);
			SwingUtilities.invokeLater(() -> advisorPanel.setStatus("Couldn't reach the price API — will retry"));
			return;
		}
		recomputeAdvice();
	}

	/** Assemble the player situation on the client thread, run the pure
	 *  advisor, and push results to the panel. */
	private void recomputeAdvice()
	{
		if (advisorPanel == null || !config.advisor())
		{
			return;
		}
		clientThread.invokeLater(() ->
		{
			final long nowSec = System.currentTimeMillis() / 1000L;
			final Map<Integer, Advisor.Quote> quotes = lastQuotes;
			final Map<Integer, Long> volumes = lastVolumes;

			final long cash = countInventory(COINS_ID);
			final Map<Integer, Integer> holdings = currentHoldings();
			final List<Advisor.OfferView> offers = currentOffers();

			// Pre-filter with cheap map data, then resolve name+limit only for survivors.
			final Map<Integer, Advisor.ItemMeta> meta = new HashMap<>();
			final long minVol = config.riskLevel().minVolume();
			for (Map.Entry<Integer, Advisor.Quote> e : quotes.entrySet())
			{
				final int id = e.getKey();
				final Advisor.Quote q = e.getValue();
				final long vol = volumes.getOrDefault(id, 0L);
				final boolean candidate =
					(q.high > q.low && q.low > 0 && q.low <= cash && vol >= minVol) // buy candidate
					|| holdings.containsKey(id)                                     // sell candidate
					|| isActiveOfferItem(offers, id);                              // adjust candidate
				if (!candidate)
				{
					continue;
				}
				meta.put(id, metaFor(id, vol));
			}

			final Set<Integer> blockedIds = blockedIds(meta, quotes);
			final List<Advisor.Suggestion> suggestions = Advisor.advise(
				nowSec, quotes, meta, cash, holdings, offers,
				skipped, blockedIds, minVol, 0.01, 4);

			SwingUtilities.invokeLater(() ->
			{
				advisorPanel.setStatus("Cash " + net.runelite.client.util.QuantityFormatter.quantityToStackSize(cash)
					+ " gp · risk " + config.riskLevel() + " · every " + config.adjustInterval());
				advisorPanel.update(suggestions, Blocklist.parse(config.blocklist()));
			});
		});
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
				bridge.start(config.bridgePort(), () -> LocalBridgeServer.payload(
					tracker.getSessionProfit(), tracker.getLifetimeProfit(), tracker.getFlips(), tracker.getFills()));
				log.info("PocketGE local bridge listening on 127.0.0.1:{}", config.bridgePort());
			}
			catch (IOException e)
			{
				log.warn("PocketGE local bridge failed to start", e);
			}
		}
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

	private void refreshPanel()
	{
		if (panel == null)
		{
			return;
		}
		final long profit = tracker.getSessionProfit();
		final long lifetime = tracker.getLifetimeProfit();
		final java.util.List<Flip> flips = tracker.getFlips();
		final int max = config.maxFlips();
		SwingUtilities.invokeLater(() -> panel.update(profit, lifetime, flips, max));
	}
}
