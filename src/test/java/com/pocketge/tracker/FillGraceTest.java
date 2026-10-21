package com.pocketge.tracker;

import java.util.HashMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

/**
 * "Filling, so priced fine" only for fills the plugin watched happen.
 *
 * A 317 bid that filled overnight, under a market that had since moved to
 * 341, read "Priced fine — leave it" for the first ten minutes after login:
 * units found already bought were taken as "filling now".
 */
public class FillGraceTest
{
	private static final long MIN = 60_000L;

	private static Object get(Object o, String field) throws Exception
	{
		final java.lang.reflect.Field f = o.getClass().getDeclaredField(field);
		f.setAccessible(true);
		return f.get(o);
	}

	@Test
	public void unitsFoundAlreadyBoughtAreNotAFillNow()
	{
		final Map<Integer, long[]> progress = new HashMap<>();
		Assert.assertFalse("first look, 7,950 already bought",
			PocketGeTrackerPlugin.filledRecently(progress, 0, 7_950, 1_000 * MIN));
		Assert.assertFalse("and still nothing new a minute later",
			PocketGeTrackerPlugin.filledRecently(progress, 0, 7_950, 1_001 * MIN));
	}

	@Test
	public void aFillWatchedHappeningQuietsTheNagForTenMinutes()
	{
		final Map<Integer, long[]> progress = new HashMap<>();
		PocketGeTrackerPlugin.filledRecently(progress, 0, 7_950, 1_000 * MIN);
		PocketGeTrackerPlugin.noteFill(progress, 0, 8_100, 1_002 * MIN); // the GE's fill event
		Assert.assertTrue(PocketGeTrackerPlugin.filledRecently(progress, 0, 8_100, 1_002 * MIN));
		Assert.assertTrue(PocketGeTrackerPlugin.filledRecently(progress, 0, 8_100, 1_011 * MIN));
		Assert.assertFalse("stalled since", PocketGeTrackerPlugin.filledRecently(progress, 0, 8_100, 1_013 * MIN));
	}

	/**
	 * Growth a pass finds with no fill event behind it is not dated. With
	 * the advisor off for 45 minutes, a sell that sold on and then stalled
	 * under the market read "Priced fine" for ten minutes after it came back
	 * on: the pass took the whole gap's fills as "now".
	 */
	@Test
	public void growthFoundAfterAGapIsNotAFillNow()
	{
		final Map<Integer, long[]> progress = new HashMap<>();
		PocketGeTrackerPlugin.filledRecently(progress, 3, 2_500, 1_000 * MIN);
		Assert.assertFalse(PocketGeTrackerPlugin.filledRecently(progress, 3, 4_000, 1_045 * MIN));
	}

	/** A first event is only recorded: a collect fires one too. */
	@Test
	public void aFirstEventIsOnlyRecorded()
	{
		final Map<Integer, long[]> progress = new HashMap<>();
		PocketGeTrackerPlugin.noteFill(progress, 0, 7_950, 1_000 * MIN);
		Assert.assertFalse(PocketGeTrackerPlugin.filledRecently(progress, 0, 7_950, 1_000 * MIN));
	}

	/**
	 * Fills watched while the box was grey count. They were only sampled
	 * once a slot was flagged, so the first flag on a sell filling steadily
	 * found no record and told it to re-list lower.
	 */
	@Test
	@SuppressWarnings("unchecked")
	public void fillsWatchedWhileGreyQuietTheFirstFlag() throws Exception
	{
		final PocketGeTrackerPlugin plugin = new PocketGeTrackerPlugin();
		final java.lang.reflect.Method build = PocketGeTrackerPlugin.class.getDeclaredMethod("buildSlotViews",
			java.util.List.class, Map.class, Map.class, Map.class, Map.class);
		build.setAccessible(true);
		final Advisor.OfferView o = new Advisor.OfferView();
		o.slot = 3;
		o.itemId = 1601;
		o.itemName = "Diamond";
		o.buy = false;
		o.price = 972;
		o.totalQuantity = 11_000;
		o.active = true;
		final Map<Integer, Boolean> grey = new HashMap<>();
		grey.put(3, true);
		final Map<Integer, long[]> progress = (Map<Integer, long[]>) get(plugin, "slotProgress");
		long t = System.currentTimeMillis() - 3 * MIN;
		for (int sold : new int[]{2_500, 2_600, 2_767})
		{
			o.quantitySold = sold;
			PocketGeTrackerPlugin.noteFill(progress, 3, sold, t += MIN); // each fill's GE event
			build.invoke(plugin, java.util.List.of(o), grey, new HashMap<>(), new HashMap<>(), new HashMap<>());
		}
		final Map<Integer, Boolean> flagged = new HashMap<>();
		flagged.put(3, false);
		final Advisor.Suggestion adj = new Advisor.Suggestion(Advisor.Suggestion.Type.ADJUST_SELL, 1601, "Diamond", 955, 8_233, 0, "");
		adj.slot = 3;
		final Map<Integer, Advisor.Suggestion> bySlot = new HashMap<>();
		bySlot.put(3, adj);
		final Map<Integer, GeOfferGridOverlay.SlotView> views = (Map<Integer, GeOfferGridOverlay.SlotView>)
			build.invoke(plugin, java.util.List.of(o), flagged, new HashMap<>(), bySlot, new HashMap<>());
		Assert.assertFalse("it was seen filling a moment ago: leave it", views.get(3).needsAdjust);
	}

	/** A Leagues world's offers are not the tracker's to book, but a fill
	 *  there is still a fill: the grace is dated on every world. */
	@Test
	public void fillsAreDatedOnSeparateExchangeWorldsToo() throws Exception
	{
		final PocketGeTrackerPlugin plugin = new PocketGeTrackerPlugin();
		final int[] sold = {2_500};
		final java.lang.reflect.Field c = PocketGeTrackerPlugin.class.getDeclaredField("client");
		c.setAccessible(true);
		c.set(plugin, java.lang.reflect.Proxy.newProxyInstance(net.runelite.api.Client.class.getClassLoader(),
			new Class<?>[]{net.runelite.api.Client.class}, (p, m, a) ->
			{
				switch (m.getName())
				{
					case "getGameState": return net.runelite.api.GameState.LOGGED_IN;
					case "getAccountHash": return 123L;
					case "getTickCount": return 100;
					case "getWorldType": return java.util.EnumSet.of(net.runelite.api.WorldType.SEASONAL);
					default:
						final Class<?> r = m.getReturnType();
						return r == boolean.class ? (Object) false : r.isPrimitive() ? (r == long.class ? (Object) 0L : (Object) 0) : null;
				}
			}));
		final java.lang.reflect.Field settled = PocketGeTrackerPlugin.class.getDeclaredField("geSettledTick");
		settled.setAccessible(true);
		settled.set(plugin, 0);
		final net.runelite.api.GrandExchangeOffer offer = (net.runelite.api.GrandExchangeOffer) java.lang.reflect.Proxy.newProxyInstance(
			net.runelite.api.GrandExchangeOffer.class.getClassLoader(), new Class<?>[]{net.runelite.api.GrandExchangeOffer.class},
			(p, m, a) -> m.getName().equals("getState") ? net.runelite.api.GrandExchangeOfferState.SELLING
				: m.getName().equals("getQuantitySold") ? (Object) sold[0]
				: m.getReturnType() == long.class ? (Object) 0L : m.getReturnType().isPrimitive() ? (Object) 0 : null);
		final net.runelite.api.events.GrandExchangeOfferChanged e = new net.runelite.api.events.GrandExchangeOfferChanged();
		e.setSlot(3);
		e.setOffer(offer);
		plugin.onGrandExchangeOfferChanged(e);
		sold[0] = 2_600;
		plugin.onGrandExchangeOfferChanged(e);
		@SuppressWarnings("unchecked")
		final Map<Integer, long[]> progress = (Map<Integer, long[]>) get(plugin, "slotProgress");
		Assert.assertTrue("the second event dated a fill", progress.get(3)[1] > 0);
	}

	/** Logging out forgets the clock, so the next login is a first look. */
	@Test
	public void aLogoutForgetsTheClock() throws Exception
	{
		final PocketGeTrackerPlugin plugin = new PocketGeTrackerPlugin();
		final java.lang.reflect.Field c = PocketGeTrackerPlugin.class.getDeclaredField("client");
		c.setAccessible(true);
		c.set(plugin, java.lang.reflect.Proxy.newProxyInstance(net.runelite.api.Client.class.getClassLoader(),
			new Class<?>[]{net.runelite.api.Client.class}, (p, m, a) ->
			{
				final Class<?> r = m.getReturnType();
				return m.getName().equals("getGameState") ? net.runelite.api.GameState.LOGIN_SCREEN
					: r == boolean.class ? (Object) false : r.isPrimitive() ? (r == long.class ? (Object) 0L : (Object) 0) : null;
			}));
		final java.lang.reflect.Field f = PocketGeTrackerPlugin.class.getDeclaredField("slotProgress");
		f.setAccessible(true);
		@SuppressWarnings("unchecked")
		final Map<Integer, long[]> progress = (Map<Integer, long[]>) f.get(plugin);
		progress.put(0, new long[]{100, System.currentTimeMillis()});
		final net.runelite.api.events.GameStateChanged logout = new net.runelite.api.events.GameStateChanged();
		logout.setGameState(net.runelite.api.GameState.LOGIN_SCREEN);
		plugin.onGameStateChanged(logout);
		Assert.assertTrue(progress.isEmpty());
	}
}
