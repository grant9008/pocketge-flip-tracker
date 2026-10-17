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
