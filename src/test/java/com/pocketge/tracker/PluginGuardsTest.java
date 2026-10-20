package com.pocketge.tracker;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GrandExchangeOfferChanged;
import org.junit.Assert;
import org.junit.Test;

/**
 * The plugin's own handling of the GE events and the figures it hands the
 * cards — the layer above FlipTracker that GeReplayTest cannot reach.
 */
public class PluginGuardsTest
{
	private static final int MITHRIL_BAR = 2359;

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

	private static <T> T stub(Class<T> type, Map<String, Object> answers)
	{
		return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
			(p, m, a) ->
			{
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

	/** The client's logout clear goes nowhere: the tracker keeps the
	 *  baseline, and the offer replayed after login books nothing again. */
	@Test
	public void theLoginClearNeverReachesTheTracker() throws Exception
	{
		final PocketGeTrackerPlugin plugin = new PocketGeTrackerPlugin();
		set(plugin, "client", stub(Client.class, Map.of("getGameState", GameState.LOGIN_SCREEN)));
		final FlipTracker tracker = (FlipTracker) get(plugin, "tracker");
		tracker.onOffer(1L, 0, MITHRIL_BAR, "Mithril bar", true, 0, 0L, 876, 10_000, false);
		tracker.onOffer(2L, 0, MITHRIL_BAR, "Mithril bar", true, 3_738, 3_738L * 876, 876, 10_000, false);

		final GrandExchangeOfferChanged clear = new GrandExchangeOfferChanged();
		clear.setSlot(0);
		clear.setOffer(stub(GrandExchangeOffer.class, Map.of("getState", GrandExchangeOfferState.EMPTY)));
		plugin.onGrandExchangeOfferChanged(clear);

		Assert.assertNull("the replay is the same offer: nothing to book",
			tracker.onOffer(3L, 0, MITHRIL_BAR, "Mithril bar", true, 3_738, 3_738L * 876, 876, 10_000, false));
		Assert.assertArrayEquals(new long[]{3_738, 3_738L * 876}, tracker.getOpenBuyTotals().get(MITHRIL_BAR));
	}

	/**
	 * A slot collected elsewhere is cleared on the next login even if the
	 * sweep last saw it empty for another character. The sweep's memory of
	 * what it reported used to survive the logout, so it never reported the
	 * empty slot, and the stale row outlived the login.
	 */
	@Test
	public void theFirstSweepAfterALoginReportsEmptySlots() throws Exception
	{
		final long account = 123_456_789L;
		final GrandExchangeOffer empty = stub(GrandExchangeOffer.class, Map.of("getState", GrandExchangeOfferState.EMPTY));
		final GrandExchangeOffer[] slots = new GrandExchangeOffer[8];
		java.util.Arrays.fill(slots, empty);
		final PocketGeTrackerPlugin plugin = new PocketGeTrackerPlugin();
		set(plugin, "client", stub(Client.class, Map.of("getGameState", GameState.LOGGED_IN,
			"getAccountHash", account, "getGrandExchangeOffers", slots)));
		final FlipTracker tracker = (FlipTracker) get(plugin, "tracker");
		tracker.setAccountHash(account);
		tracker.onOffer(1L, 3, MITHRIL_BAR, "Mithril bar", true, 400, 350_400L, 876, 1_000, false);
		((int[]) get(plugin, "sweptItem"))[3] = 0; // last reported empty, for someone else

		final GameStateChanged logout = new GameStateChanged();
		logout.setGameState(GameState.LOGIN_SCREEN);
		plugin.onGameStateChanged(logout);
		set(plugin, "geSettledTick", 0); // the login burst is over
		plugin.onGameTick(new net.runelite.api.events.GameTick());

		final Map<Integer, long[]> rows = tracker.snapshot().slotsByAccount.get(account);
		Assert.assertTrue("slot 3's stale row is gone", rows == null || !rows.containsKey(3));
	}

	/** An offer seen before the client says whose it is goes nowhere: it
	 *  would be measured against the last character's baselines. */
	@Test
	public void noOfferIsReadBeforeTheAccountIsKnown() throws Exception
	{
		final PocketGeTrackerPlugin plugin = new PocketGeTrackerPlugin();
		set(plugin, "client", stub(Client.class, Map.of("getGameState", GameState.LOGGED_IN, "getAccountHash", -1L)));
		final FlipTracker tracker = (FlipTracker) get(plugin, "tracker");
		tracker.onOffer(1L, 0, MITHRIL_BAR, "Mithril bar", true, 0, 0L, 876, 10_000, false);

		final GrandExchangeOfferChanged grown = new GrandExchangeOfferChanged();
		grown.setSlot(0);
		grown.setOffer(stub(GrandExchangeOffer.class, Map.of("getState", GrandExchangeOfferState.BUYING,
			"getItemId", MITHRIL_BAR, "getQuantitySold", 3_738, "getSpent", 3_738 * 876,
			"getPrice", 876, "getTotalQuantity", 10_000)));
		plugin.onGrandExchangeOfferChanged(grown); // would NPE on the missing itemManager if it got through
		Assert.assertNull("nothing booked", tracker.getOpenBuyTotals().get(MITHRIL_BAR));
		Assert.assertTrue(PocketGeTrackerPlugin.noAccount(0));
		Assert.assertFalse("a negative hash is still an account", PocketGeTrackerPlugin.noAccount(-5_000_000_000L));
	}

	/** A "price it yourself" opt-out does not survive a logout to silence
	 *  the next character's offer in that slot. */
	@Test
	@SuppressWarnings("unchecked")
	public void aLogoutDropsThePricingOptOuts() throws Exception
	{
		final PocketGeTrackerPlugin plugin = new PocketGeTrackerPlugin();
		set(plugin, "client", stub(Client.class, Map.of("getGameState", GameState.LOGIN_SCREEN)));
		final Set<Integer> skipped = (Set<Integer>) get(plugin, "adviceSkippedSlots");
		skipped.add(2);
		final GameStateChanged logout = new GameStateChanged();
		logout.setGameState(GameState.LOGIN_SCREEN);
		plugin.onGameStateChanged(logout);
		Assert.assertTrue(skipped.isEmpty());
	}

	/** The watchlist row gets the tracked units' own cost — the field the
	 *  inspected card's PAID @ is now computed from. */
	@Test
	public void theHeldPositionCarriesItsCost() throws Exception
	{
		final Method m = PocketGeTrackerPlugin.class.getDeclaredMethod("fillHeldPosition",
			FavoritesPanel.Row.class, Advisor.Quote.class, Map.class, Map.class);
		m.setAccessible(true);
		final FavoritesPanel.Row row = new FavoritesPanel.Row();
		row.id = MITHRIL_BAR;
		final Advisor.Quote q = new Advisor.Quote();
		q.low = 870;
		q.high = 888;
		final Map<Integer, Integer> holdings = new HashMap<>();
		holdings.put(MITHRIL_BAR, 4_377);
		final Map<Integer, long[]> lots = new HashMap<>();
		lots.put(MITHRIL_BAR, new long[]{3_738, 3_738L * 876});
		m.invoke(null, row, q, holdings, lots);
		Assert.assertEquals(3_738, row.pricedQty);
		Assert.assertEquals(3_738L * 876, row.pricedCost);
	}

	/** The row's held figures are priced where the card is: the listing
	 *  when there is one, else the card's SELL @ — never the insta-buy.
	 *  Two prices for one stack read -550K on the row beside -175K on the
	 *  card. */
	@Test
	public void theHeldFiguresArePricedAtTheCardsPrice() throws Exception
	{
		final Method m = PocketGeTrackerPlugin.class.getDeclaredMethod("fillHeldPosition",
			FavoritesPanel.Row.class, Advisor.Quote.class, Map.class, Map.class);
		m.setAccessible(true);
		final Advisor.Quote q = new Advisor.Quote();
		q.low = 340;
		q.high = 345;
		final Map<Integer, long[]> lots = new HashMap<>();
		lots.put(MITHRIL_BAR, new long[]{25_000, 25_000L * 359});

		final FavoritesPanel.Row listed = new FavoritesPanel.Row();
		listed.id = MITHRIL_BAR;
		listed.price = 345;
		listed.targetSell = 350;
		listed.listedQty = 25_000;
		listed.listedPrice = 374;
		m.invoke(null, listed, q, new HashMap<Integer, Integer>(), lots);
		final long net374 = 374 - FlipTracker.taxPerItem(374, MITHRIL_BAR);
		Assert.assertEquals(25_000L * net374, listed.sellValue);
		Assert.assertEquals(25_000L * net374 - 25_000L * 359, listed.heldProfit);

		final FavoritesPanel.Row held = new FavoritesPanel.Row();
		held.id = MITHRIL_BAR;
		held.price = 345;
		held.targetSell = 350;
		final Map<Integer, Integer> bank = new HashMap<>();
		bank.put(MITHRIL_BAR, 25_000);
		m.invoke(null, held, q, bank, lots);
		final long net350 = 350 - FlipTracker.taxPerItem(350, MITHRIL_BAR);
		Assert.assertEquals(25_000L * net350 - 25_000L * 359, held.heldProfit);
	}

	/** The paid-percent footnote never reads "Up -N%". */
	@Test
	public void thePaidFootnoteNeverSaysUpNegative() throws Exception
	{
		final AdvisorPanel panel = new AdvisorPanel(null, stub(AdvisorPanel.Actions.class, Map.of()));
		final Method cardFor = AdvisorPanel.class.getDeclaredMethod("cardFor", AdvisorPanel.Rec.class);
		cardFor.setAccessible(true);
		for (long cost : new long[]{700, 879, 878, 880})
		{
			final AdvisorPanel.Rec r = new AdvisorPanel.Rec();
			r.sell = true;
			r.itemId = MITHRIL_BAR;
			r.name = "Mithril bar";
			r.quantity = 100;
			r.unitPrice = 896;
			r.unitCost = cost;
			r.hasTrackedCost = true;
			r.profit = 100L * ((896 - 17) - cost);
			final Object card = cardFor.invoke(panel, r);
			final String foot = (String) get(card, "footnote");
			Assert.assertFalse("cost " + cost + ": " + foot, foot != null && foot.startsWith("Up -"));
		}
	}
}
