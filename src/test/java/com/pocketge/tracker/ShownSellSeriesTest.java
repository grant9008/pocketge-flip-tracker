package com.pocketge.tracker;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * "website has a better sell price here ... id try to price at the website
 * price first for sure here not for 298": the sell card on screen fetches
 * its own price series, so it is priced as the website prices it wherever
 * it sits in the list.
 */
public class ShownSellSeriesTest
{
	private static final int ICE_SACK = 24607;
	private static final int OTHER = 4151;

	private PocketGeTrackerPlugin plugin;
	private boolean advisorOn = true;
	/** Executor work held until run(), as RuneLite's would hold it. */
	private final List<Runnable> queued = new ArrayList<>();
	private final List<Integer> fetched = new ArrayList<>();
	private int rebuilt;

	private void set(String field, Object value) throws Exception
	{
		final Field f = PocketGeTrackerPlugin.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(plugin, value);
	}

	private Object get(String field) throws Exception
	{
		final Field f = PocketGeTrackerPlugin.class.getDeclaredField(field);
		f.setAccessible(true);
		return f.get(plugin);
	}

	private void runQueued()
	{
		while (!queued.isEmpty())
		{
			queued.remove(0).run();
		}
	}

	private static TradeEngine.Series series()
	{
		final TradeEngine.Series s = new TradeEngine.Series();
		s.labels = new long[]{1_000, 1_300};
		s.low = new double[]{286, 288};
		s.high = new double[]{305, 310};
		s.lowVol = new double[]{10, 10};
		s.highVol = new double[]{10, 10};
		return s;
	}

	private boolean hasSeries(int itemId) throws Exception
	{
		final Method m = PocketGeTrackerPlugin.class.getDeclaredMethod("seriesFor", int.class);
		m.setAccessible(true);
		return m.invoke(plugin, itemId) != null;
	}

	@Before
	public void wire() throws Exception
	{
		plugin = new PocketGeTrackerPlugin();
		set("running", true);
		set("config", Proxy.newProxyInstance(PocketGeTrackerConfig.class.getClassLoader(),
			new Class<?>[]{PocketGeTrackerConfig.class}, (proxy, m, args) ->
			{
				if ("advisor".equals(m.getName()))
				{
					return advisorOn;
				}
				final Class<?> r = m.getReturnType();
				return r == boolean.class ? false : r.isPrimitive() ? 0 : null;
			}));
		set("executor", Proxy.newProxyInstance(ScheduledExecutorService.class.getClassLoader(),
			new Class<?>[]{ScheduledExecutorService.class}, (proxy, m, args) ->
			{
				if ("execute".equals(m.getName()))
				{
					queued.add((Runnable) args[0]);
				}
				final Class<?> r = m.getReturnType();
				return r == boolean.class ? false : r.isPrimitive() ? 0 : null;
			}));
		set("marketClient", new MarketClient(null, null)
		{
			@Override
			public TradeEngine.Series fetchTimeseries5m(int itemId)
			{
				fetched.add(itemId);
				return series();
			}
		});
		plugin.rebuildCards = () -> rebuilt++;
	}

	/** Card 20 of 20, no series: fetched as it is shown, and the cards
	 *  rebuilt from it — once. */
	@Test
	public void theSellCardOnScreenFetchesItsOwnSeries() throws Exception
	{
		Assert.assertFalse(hasSeries(ICE_SACK));
		plugin.noteShownCard(ICE_SACK, true);
		runQueued();
		Assert.assertEquals(List.of(ICE_SACK), fetched);
		Assert.assertTrue(hasSeries(ICE_SACK));
		Assert.assertEquals("the cards re-priced from it", 1, rebuilt);

		plugin.noteShownCard(OTHER, true);
		runQueued();
		plugin.noteShownCard(ICE_SACK, true);
		runQueued();
		Assert.assertEquals("back to it: already in hand, not asked again", List.of(ICE_SACK, OTHER), fetched);
		Assert.assertEquals(2, rebuilt);
	}

	/** A buy card is left to the advisor's own fetch: the engine can turn a
	 *  flip idea down and take its card away under you. */
	@Test
	public void notForABuyCard() throws Exception
	{
		plugin.noteShownCard(ICE_SACK, true);
		plugin.noteShownCard(OTHER, false);
		Assert.assertNull("a buy is not the sell on screen", get("shownSellItemId"));
		queued.clear();
		plugin.noteShownCard(OTHER, false);
		plugin.noteShownCard(null, false);
		runQueued();
		Assert.assertTrue(fetched.isEmpty());
		final Method m = PocketGeTrackerPlugin.class.getDeclaredMethod("refreshOfferSeries");
		m.setAccessible(true);
		m.invoke(plugin);
		Assert.assertTrue("nor kept current by the round", fetched.isEmpty());
	}

	/** Paged on before it landed: kept for when it is shown again, but the
	 *  cards are not rebuilt for a card no longer there. */
	@Test
	public void pagedPastBeforeItLandsRebuildsNothing() throws Exception
	{
		plugin.noteShownCard(ICE_SACK, true);
		plugin.noteShownCard(OTHER, false);
		runQueued();
		Assert.assertEquals(List.of(ICE_SACK), fetched);
		Assert.assertTrue(hasSeries(ICE_SACK));
		Assert.assertEquals(0, rebuilt);
	}

	@Test
	public void theAdvisorOffAsksNothing() throws Exception
	{
		advisorOn = false;
		plugin.noteShownCard(ICE_SACK, true);
		runQueued();
		Assert.assertTrue(fetched.isEmpty());
	}

	/** Failed or backing off: not asked again on every page back and forth —
	 *  the offer screen's attempt clock holds it for a minute. */
	@Test
	public void aFailedFetchIsNotRetriedAtOnce() throws Exception
	{
		set("marketClient", new MarketClient(null, null)
		{
			@Override
			public TradeEngine.Series fetchTimeseries5m(int itemId) throws java.io.IOException
			{
				fetched.add(itemId);
				throw new java.io.IOException("Wiki said no");
			}
		});
		plugin.noteShownCard(ICE_SACK, true);
		runQueued();
		plugin.noteShownCard(OTHER, false);
		plugin.noteShownCard(ICE_SACK, true);
		runQueued();
		Assert.assertEquals(List.of(ICE_SACK), fetched);
		Assert.assertEquals(0, rebuilt);
	}

	/** Kept current by the advisor's own round while the card stays up. */
	@Test
	public void theAdvisorsRoundKeepsItCurrent() throws Exception
	{
		set("shownSellItemId", ICE_SACK);
		final Method m = PocketGeTrackerPlugin.class.getDeclaredMethod("refreshOfferSeries");
		m.setAccessible(true);
		m.invoke(plugin);
		Assert.assertEquals(List.of(ICE_SACK), fetched);
		@SuppressWarnings("unchecked")
		final Map<Integer, TradeEngine.Series> offerSeries = (Map<Integer, TradeEngine.Series>) get("lastOfferSeries");
		Assert.assertTrue(offerSeries.containsKey(ICE_SACK));
	}
}
