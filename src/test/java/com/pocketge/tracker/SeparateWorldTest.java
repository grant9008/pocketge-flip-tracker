package com.pocketge.tracker;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.ItemContainer;
import net.runelite.api.WorldType;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuEntryAdded;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Worlds with a Grand Exchange of their own — Hardcore (November 2026),
 * Leagues, Deadman. Hardcore was tracked as the main game: its trades went
 * into the main game's profit and history, and every card, chip and mark on
 * it quoted the main game's market, E typing that price into its offers.
 * On any of them now nothing is tracked or priced, and the panel says so.
 */
public class SeparateWorldTest
{
	private static final long MAIN = 111_111_111L;
	private static final long HARDCORE = 222_222_222L;
	private static final int RUNE_DART = 811;
	private static final int MITHRIL_BAR = 2359;

	private static final Set<WorldType> HARDCORE_WORLD = EnumSet.of(WorldType.HARDCORE_MAIN, WorldType.MEMBERS);
	private static final Set<WorldType> MAIN_WORLD = EnumSet.of(WorldType.MEMBERS);

	private PocketGeTrackerPlugin plugin;
	private BankHighlightOverlay bank;
	private GeOfferGridOverlay grid;
	private GeOfferPriceOverlay chip;
	/** Every client method the plugin called, by name. */
	private final List<String> calls = new ArrayList<>();
	private final Map<String, Object> answers = new HashMap<>();

	private static Object get(Object o, String field) throws Exception
	{
		final Field f = o.getClass().getDeclaredField(field);
		f.setAccessible(true);
		return f.get(o);
	}

	private static void set(Object o, String field, Object v) throws Exception
	{
		final Field f = o.getClass().getDeclaredField(field);
		f.setAccessible(true);
		f.set(o, v);
	}

	private static <T> T stub(Class<T> type, Map<String, Object> answers, List<String> calls)
	{
		return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
			(p, m, a) ->
			{
				if (calls != null)
				{
					calls.add(m.getName());
				}
				if (answers.containsKey(m.getName()))
				{
					return answers.get(m.getName());
				}
				final Class<?> r = m.getReturnType();
				if (r == boolean.class)
				{
					return false;
				}
				return r.isPrimitive() ? (r == long.class ? (Object) 0L : (Object) 0) : null;
			}));
	}

	private static <T> T overlay(Class<T> type, Class<?>... params) throws Exception
	{
		final Constructor<T> ctor = type.getDeclaredConstructor(params);
		ctor.setAccessible(true);
		return ctor.newInstance(new Object[params.length]);
	}

	private void onWorld(GameState state, Set<WorldType> world, long account)
	{
		answers.put("getGameState", state);
		answers.put("getWorldType", world == null ? null : EnumSet.copyOf(world));
		answers.put("getAccountHash", account);
	}

	private void fire(GameState state) throws Exception
	{
		answers.put("getGameState", state);
		final GameStateChanged e = new GameStateChanged();
		e.setGameState(state);
		plugin.onGameStateChanged(e);
	}

	private static Advisor.Suggestion dartSale()
	{
		return new Advisor.Suggestion(Advisor.Suggestion.Type.SELL, RUNE_DART, "Rune dart", 72, 1_000, 0, "");
	}

	@Before
	public void wire() throws Exception
	{
		plugin = new PocketGeTrackerPlugin();
		bank = overlay(BankHighlightOverlay.class);
		grid = overlay(GeOfferGridOverlay.class, Client.class, net.runelite.client.ui.overlay.tooltip.TooltipManager.class);
		chip = overlay(GeOfferPriceOverlay.class, Client.class, net.runelite.client.game.ItemManager.class);
		set(plugin, "bankOverlay", bank);
		set(plugin, "geGridOverlay", grid);
		set(plugin, "gePriceOverlay", chip);
		set(plugin, "client", stub(Client.class, answers, calls));
		onWorld(GameState.LOGGED_IN, MAIN_WORLD, MAIN);
		/* What a main-game session left on the screens. */
		bank.setSuggestions(new HashMap<>(Map.of(RUNE_DART, dartSale())));
		bank.setHoldings(new HashMap<>(Map.of(RUNE_DART, new long[]{1_000, 72_000})));
		bank.setRecommended(RUNE_DART);
		final Map<Integer, GeOfferGridOverlay.SlotView> slots = new HashMap<>();
		slots.put(0, null);
		grid.setSlots(slots);
		grid.setActionPrompt(RUNE_DART, true);
		chip.setSearchSuggestion(RUNE_DART, "Rune dart");
		set(plugin, "lastTopRecommendation", dartSale());
		/* And the main character's own records. */
		set(plugin, "loggedIn", true);
		set(plugin, "portfolioAccount", MAIN);
		set(plugin, "bankSeen", true);
		set(plugin, "bankAccount", MAIN);
		@SuppressWarnings("unchecked")
		final Map<Integer, Integer> lastBank = (Map<Integer, Integer>) get(plugin, "lastBank");
		lastBank.put(RUNE_DART, 1_000);
		((FlipTracker) get(plugin, "tracker")).setAccountHash(MAIN);
	}

	@Test
	public void hardcoreHasAnExchangeOfItsOwn()
	{
		Assert.assertTrue(PocketGeTrackerPlugin.separateExchange(HARDCORE_WORLD));
		Assert.assertEquals("Hardcore", PocketGeTrackerPlugin.separateWorldName(HARDCORE_WORLD));
		Assert.assertEquals("Leagues", PocketGeTrackerPlugin.separateWorldName(EnumSet.of(WorldType.SEASONAL)));
		Assert.assertEquals("seasonal Deadman is Deadman", "Deadman",
			PocketGeTrackerPlugin.separateWorldName(EnumSet.of(WorldType.SEASONAL, WorldType.DEADMAN)));
		Assert.assertEquals("Fresh Start", PocketGeTrackerPlugin.separateWorldName(EnumSet.of(WorldType.FRESH_START_WORLD)));
		Assert.assertEquals("beta", PocketGeTrackerPlugin.separateWorldName(EnumSet.of(WorldType.NOSAVE_MODE)));
		Assert.assertNull(PocketGeTrackerPlugin.separateWorldName(MAIN_WORLD));
		Assert.assertNull("a high-risk or PvP world trades on the main Exchange",
			PocketGeTrackerPlugin.separateWorldName(EnumSet.of(WorldType.PVP, WorldType.HIGH_RISK, WorldType.MEMBERS)));
		Assert.assertNull(PocketGeTrackerPlugin.separateWorldName(null));
	}

	@Test
	public void theLoginScreenIsNoWorldYet()
	{
		Assert.assertFalse("a Hardcore world picked on the login screen is not played yet",
			PocketGeTrackerPlugin.onSeparateWorld(GameState.LOGIN_SCREEN, HARDCORE_WORLD));
		Assert.assertFalse(PocketGeTrackerPlugin.onSeparateWorld(GameState.LOGIN_SCREEN_AUTHENTICATOR, HARDCORE_WORLD));
		Assert.assertTrue("from the login on: the slots and inventory can come before LOGGED_IN",
			PocketGeTrackerPlugin.onSeparateWorld(GameState.LOGGING_IN, HARDCORE_WORLD));
		Assert.assertTrue(PocketGeTrackerPlugin.onSeparateWorld(GameState.LOADING, HARDCORE_WORLD));
		Assert.assertTrue(PocketGeTrackerPlugin.onSeparateWorld(GameState.LOGGED_IN, HARDCORE_WORLD));
		Assert.assertTrue(PocketGeTrackerPlugin.onSeparateWorld(GameState.HOPPING, HARDCORE_WORLD));
		Assert.assertFalse(PocketGeTrackerPlugin.onSeparateWorld(GameState.LOGGED_IN, MAIN_WORLD));
	}

	/** Its slots go nowhere: an empty one leaves the main game's record,
	 *  and a fill books nothing. */
	@Test
	public void aHardcoreWorldsSlotsAreNotTheMainGames() throws Exception
	{
		final FlipTracker tracker = (FlipTracker) get(plugin, "tracker");
		tracker.onOffer(1L, 2, MITHRIL_BAR, "Mithril bar", true, 1_000, 876_000L, 876, 10_000, false);
		onWorld(GameState.LOGGED_IN, HARDCORE_WORLD, MAIN); // the same hash, at worst
		final GrandExchangeOffer empty = stub(GrandExchangeOffer.class, Map.of("getState", GrandExchangeOfferState.EMPTY), null);
		final GrandExchangeOffer[] raw = new GrandExchangeOffer[8];
		java.util.Arrays.fill(raw, empty);
		answers.put("getGrandExchangeOffers", raw);
		set(plugin, "geSettledTick", 0);
		plugin.onGameTick(new net.runelite.api.events.GameTick());
		final GrandExchangeOfferChanged collected = new GrandExchangeOfferChanged();
		collected.setSlot(2);
		collected.setOffer(empty);
		plugin.onGrandExchangeOfferChanged(collected);
		Assert.assertTrue("the main game's slot 2 is untouched",
			tracker.snapshot().slotsByAccount.get(MAIN).containsKey(2));
	}

	@Test
	public void loggingInToHardcoreTakesTheMainGamesPricesOff() throws Exception
	{
		fire(GameState.LOGIN_SCREEN);
		onWorld(GameState.LOGGING_IN, HARDCORE_WORLD, HARDCORE);
		fire(GameState.LOGGING_IN);
		Assert.assertTrue((Boolean) get(plugin, "separateWorld"));

		Assert.assertTrue("no stack marked to sell", ((Map<?, ?>) get(bank, "suggestionsByItem")).isEmpty());
		Assert.assertTrue("no stack's hover priced", ((Map<?, ?>) get(bank, "holdings")).isEmpty());
		Assert.assertNull("no card's stack ringed", get(bank, "recommendedItemId"));
		Assert.assertTrue("no slot coloured", ((Map<?, ?>) get(grid, "slots")).isEmpty());
		Assert.assertNull("no slot pointed at", get(grid, "actPromptItemId"));
		Assert.assertEquals("nothing suggested in the search", 0, chip.searchItemId());
		Assert.assertNull(get(plugin, "lastTopRecommendation"));

		fire(GameState.LOGGED_IN);
		Assert.assertFalse("the panel stays on its off message", (Boolean) get(plugin, "loggedIn"));
		Assert.assertEquals("pocketge.com keeps the main character's chart", MAIN, get(plugin, "portfolioAccount"));
		Assert.assertEquals("the main game's bank is kept, not swapped for this one's", MAIN, get(plugin, "bankAccount"));
		Assert.assertEquals(1_000, ((Map<?, ?>) get(plugin, "lastBank")).get(RUNE_DART));
	}

	@Test
	public void nothingOnItIsReadOrPriced() throws Exception
	{
		onWorld(GameState.LOGGED_IN, HARDCORE_WORLD, HARDCORE);
		fire(GameState.LOGGED_IN);
		Assert.assertFalse("hopped in from a main-game session, the panel goes off too",
			(Boolean) get(plugin, "loggedIn"));
		calls.clear();

		/* Its bank. */
		final ItemContainerChanged opened = new ItemContainerChanged(95, stub(ItemContainer.class, Map.of(), null));
		plugin.onItemContainerChanged(opened);
		Assert.assertEquals(MAIN, get(plugin, "bankAccount"));
		Assert.assertTrue("not even read", calls.isEmpty());

		/* The offer screen: nothing for the chip, nothing for E to type. */
		final Method screen = PocketGeTrackerPlugin.class.getDeclaredMethod("updateGeContextFromScreen", boolean.class);
		screen.setAccessible(true);
		screen.invoke(plugin, true);
		Assert.assertNull(get(plugin, "geContextItemId"));
		Assert.assertNull(chip.armed());
		Assert.assertFalse("the screen's item is not even looked up", calls.contains("getVarpValue"));

		/* The search: the card's item is not offered. */
		final Method suggest = PocketGeTrackerPlugin.class.getDeclaredMethod("suggestInSearch", int.class, String.class);
		suggest.setAccessible(true);
		suggest.invoke(plugin, RUNE_DART, "Rune dart");
		Assert.assertEquals(0, chip.searchItemId());

		/* The right-click entries: a chart, a card, the website, all priced
		   on the main game. */
		final MenuEntryAdded examine = new MenuEntryAdded(
			stub(net.runelite.api.MenuEntry.class, Map.of("getOption", "Examine", "getItemId", RUNE_DART), null));
		plugin.onMenuEntryAdded(examine);
		Assert.assertFalse(calls.contains("createMenuEntry"));

		/* The chart over the chat. */
		final PriceChartOverlay.Feed feed = (PriceChartOverlay.Feed) get(plugin, "chartFeed");
		Assert.assertFalse(feed.marketHere());

		/* And no book fetched for it. */
		set(plugin, "running", true);
		set(plugin, "config", stub(PocketGeTrackerConfig.class, Map.of("advisor", true), null));
		set(plugin, "marketClient", new MarketClient(null, null)
		{
			@Override
			public Map<Integer, Advisor.Quote> fetchLatest()
			{
				throw new AssertionError("no prices fetched on a Hardcore world");
			}
		});
		final Method refresh = PocketGeTrackerPlugin.class.getDeclaredMethod("refreshPrices", long.class);
		refresh.setAccessible(true);
		refresh.invoke(plugin, 0L);
	}

	/** Work asked for before the login to it — a price round's recompute,
	 *  the bridge's refresh — runs after it, and finds the world changed. */
	@Test
	public void workQueuedBeforeTheLoginStandsDown() throws Exception
	{
		final List<Runnable> queued = new ArrayList<>();
		set(plugin, "clientThread", new net.runelite.client.callback.ClientThread()
		{
			@Override
			public void invokeLater(Runnable r)
			{
				queued.add(r);
			}
		});
		set(plugin, "running", true);
		set(plugin, "config", stub(PocketGeTrackerConfig.class, Map.of("advisor", true), null));
		final MainPanel.Actions actions = (MainPanel.Actions) Proxy.newProxyInstance(
			MainPanel.Actions.class.getClassLoader(), new Class<?>[]{MainPanel.Actions.class},
			(p, m, a) -> m.getReturnType() == boolean.class ? (Object) false
				: m.getReturnType().isPrimitive() ? (Object) 0 : null);
		set(plugin, "mainPanel", new MainPanel(null, actions));
		final Method recompute = PocketGeTrackerPlugin.class.getDeclaredMethod("recomputeAdvice");
		recompute.setAccessible(true);
		recompute.invoke(plugin);
		final Method stats = PocketGeTrackerPlugin.class.getDeclaredMethod("refreshStatsAndFavorites");
		stats.setAccessible(true);
		stats.invoke(plugin);
		Assert.assertTrue(queued.size() >= 2);
		set(plugin, "mainPanel", null); // the login's own panel updates are not under test

		onWorld(GameState.LOGGING_IN, HARDCORE_WORLD, HARDCORE);
		fire(GameState.LOGGING_IN);
		calls.clear();
		set(plugin, "lastPortfolioValue", 9_000_000L);
		for (Runnable r : queued)
		{
			r.run();
		}
		Assert.assertTrue("neither read this character's cash or slots: " + calls, calls.isEmpty());
		Assert.assertEquals("pocketge.com keeps the main game's last value", 9_000_000L, get(plugin, "lastPortfolioValue"));
		Assert.assertTrue("no stack marked", ((Map<?, ?>) get(bank, "suggestionsByItem")).isEmpty());
	}

	/** Back to the login screen, and the next main-game login is a login. */
	@Test
	public void backToTheMainGame() throws Exception
	{
		onWorld(GameState.LOGGED_IN, HARDCORE_WORLD, HARDCORE);
		fire(GameState.LOGGED_IN);
		onWorld(GameState.LOGIN_SCREEN, HARDCORE_WORLD, HARDCORE);
		fire(GameState.LOGIN_SCREEN);
		Assert.assertFalse("the login screen is no world", (Boolean) get(plugin, "separateWorld"));

		/* And a hop straight out of one, which never passed the login screen. */
		onWorld(GameState.LOGGED_IN, HARDCORE_WORLD, HARDCORE);
		fire(GameState.LOGGED_IN);
		onWorld(GameState.HOPPING, MAIN_WORLD, MAIN);
		fire(GameState.HOPPING);
		Assert.assertFalse((Boolean) get(plugin, "separateWorld"));
		Assert.assertFalse("so LOGGED_IN after it is a login: a fresh book, this character's bank",
			(Boolean) get(plugin, "loggedIn"));
	}

	/**
	 * Back on the main game, the same card puts its rings and the search's
	 * suggestion back. clearMarket empties them behind the panel's back, and
	 * the panel, remembering it had announced that card, said nothing again:
	 * no ring on the stack, none on the slot, nothing in the search, until
	 * the card changed.
	 */
	@Test
	public void theSameCardIsAnnouncedAgainOnTheWayBack() throws Exception
	{
		final List<String> heard = new ArrayList<>();
		final AdvisorPanel.Actions actions = (AdvisorPanel.Actions) Proxy.newProxyInstance(
			AdvisorPanel.Actions.class.getClassLoader(), new Class<?>[]{AdvisorPanel.Actions.class},
			(p, m, a) ->
			{
				if ("onRecommendationShown".equals(m.getName()) || "onSearchItemShown".equals(m.getName()))
				{
					heard.add(m.getName() + " " + a[0]);
				}
				final Class<?> t = m.getReturnType();
				return t == boolean.class ? (Object) false : t.isPrimitive() ? (Object) 0 : null;
			});
		final AdvisorPanel.Rec dart = new AdvisorPanel.Rec();
		dart.sell = true;
		dart.itemId = RUNE_DART;
		dart.name = "Rune dart";
		dart.quantity = 1_000;
		dart.unitPrice = 72;
		final AdvisorPanel.Rec bars = new AdvisorPanel.Rec();
		bars.itemId = MITHRIL_BAR;
		bars.name = "Mithril bar";
		bars.quantity = 1_000;
		bars.unitPrice = 876;
		bars.profit = 20_000;
		javax.swing.SwingUtilities.invokeAndWait(() ->
		{
			final AdvisorPanel panel = new AdvisorPanel(null, actions);
			panel.setRecommendations(new ArrayList<>(List.of(dart, bars)));
			Assert.assertEquals(List.of("onRecommendationShown 811", "onSearchItemShown 2359"), heard);
			heard.clear();

			panel.setOffWorld("Hardcore");
			panel.setLoggedIn(false);
			/* A round on the login screen on the way back posts the same
			   cards to the logged-out panel. */
			panel.setRecommendations(new ArrayList<>(List.of(dart, bars)));
			panel.setOffWorld(null);
			panel.setLoggedIn(true);
			Assert.assertEquals(List.of("onRecommendationShown 811", "onSearchItemShown 2359"), heard);
			heard.clear();

			/* And an unchanged card on an ordinary rebuild is still not news. */
			panel.setRecommendations(new ArrayList<>(List.of(dart, bars)));
			Assert.assertEquals(List.of(), heard);
		});
	}

	/** The panel's message is in short lines, as the login one is: wrapped
	 *  HTML labels in that column drew no text in the client. */
	@Test
	public void theOffMessageFitsTheCard()
	{
		for (String world : new String[] {"Hardcore", "Leagues", "Deadman", "Fresh Start", "tournament", "speedrun", "beta"})
		{
			final String[] lines = AdvisorPanel.offWorldText(world);
			for (int i = 0; i < lines.length; i++)
			{
				final javax.swing.JLabel l = new javax.swing.JLabel(lines[i]);
				l.setFont(l.getFont().deriveFont(i == 0 ? java.awt.Font.BOLD : java.awt.Font.PLAIN, i == 0 ? 12f : 11f));
				Assert.assertTrue(lines[i] + " is " + l.getPreferredSize().width + "px",
					l.getPreferredSize().width <= AdvisorPanel.CARD_INNER);
			}
		}
		Assert.assertEquals("Off on Hardcore worlds", AdvisorPanel.offWorldText("Hardcore")[0]);
	}
}
