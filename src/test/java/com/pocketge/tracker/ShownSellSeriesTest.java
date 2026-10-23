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
	private final List<Integer> repriced = new ArrayList<>();

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
		/* As recomputeAdvice does: the cards priced from everything in hand
		   as of now — a moment after, so a fetch in the same millisecond
		   reads as already in them. */
		plugin.rebuildCards = () ->
		{
			rebuilt++;
			try
			{
				set("cardsPricedAt", System.currentTimeMillis() + 1);
			}
			catch (Exception e)
			{
				throw new IllegalStateException(e);
			}
		};
		plugin.repriceOffer = repriced::add;
	}

	private void cache(int itemId) throws Exception
	{
		final Method m = PocketGeTrackerPlugin.class.getDeclaredMethod("cacheOnDemandSeries", int.class,
			TradeEngine.Series.class);
		m.setAccessible(true);
		m.invoke(plugin, itemId, series());
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

	/** Next to the ice sack, Back before its series lands, Next again: it
	 *  landed while another card was up, so nothing was rebuilt then — and
	 *  showing it again rebuilds from it, without asking twice. */
	@Test
	public void aSeriesThatLandedWhileAwayIsUsedWhenShownAgain() throws Exception
	{
		plugin.rebuildCards.run(); // the cards as built before any of this
		rebuilt = 0;
		Thread.sleep(3);
		plugin.noteShownCard(ICE_SACK, true);
		plugin.noteShownCard(OTHER, false);
		runQueued();
		Assert.assertEquals(0, rebuilt);
		Thread.sleep(2);
		plugin.noteShownCard(ICE_SACK, true);
		runQueued();
		Assert.assertEquals("not fetched again", List.of(ICE_SACK), fetched);
		Assert.assertEquals("but the cards rebuilt from it", 1, rebuilt);
		plugin.noteShownCard(OTHER, false);
		plugin.noteShownCard(ICE_SACK, true);
		Assert.assertEquals("once: the cards have it now", 1, rebuilt);
	}

	/** Opened on the offer screen first — its fetch prices the screen, not
	 *  the cards — then paged to: the card takes it up. */
	@Test
	public void aSeriesTheOfferScreenFetchedReachesTheCard() throws Exception
	{
		plugin.rebuildCards.run();
		rebuilt = 0;
		Thread.sleep(3);
		cache(ICE_SACK);
		plugin.noteShownCard(ICE_SACK, true);
		runQueued();
		Assert.assertTrue(fetched.isEmpty());
		Assert.assertEquals(1, rebuilt);
	}

	/** Paging through a whole list of sells with no series: every card it
	 *  priced keeps its price, and the watchlist item being inspected keeps
	 *  its series. The cache used to start again at eight. */
	@Test
	public void pagingThroughTwentySellsKeepsEveryOnesSeries() throws Exception
	{
		set("selectedFavoriteItemId", 999);
		cache(999);
		for (int id = 1; id <= 20; id++)
		{
			plugin.noteShownCard(id, true);
			runQueued();
		}
		for (int id = 1; id <= 20; id++)
		{
			Assert.assertTrue("card " + id, hasSeries(id));
		}
		Assert.assertTrue("the inspected item", hasSeries(999));
		Assert.assertEquals(20, fetched.size());
	}

	/** Past the cap the oldest go — never what is on screen now. */
	@Test
	public void pastTheCapTheOldestGoNotWhatIsOnScreen() throws Exception
	{
		set("selectedFavoriteItemId", 999);
		set("geContextItemId", 998);
		cache(999);
		Thread.sleep(2);
		cache(998);
		Thread.sleep(2);
		set("shownSellItemId", 997);
		cache(997);
		/* Fetched in the opposite order to the ids, so the oldest is not
		   whichever the map happens to list first. */
		for (int id = 40; id >= 1; id--)
		{
			Thread.sleep(1);
			cache(id);
		}
		Assert.assertTrue(hasSeries(999));
		Assert.assertTrue(hasSeries(998));
		Assert.assertTrue(hasSeries(997));
		for (int id = 1; id <= 21; id++)
		{
			Assert.assertTrue("among the newest: " + id, hasSeries(id));
		}
		Assert.assertFalse("the oldest", hasSeries(40));
		@SuppressWarnings("unchecked")
		final Map<Integer, TradeEngine.Series> held = (Map<Integer, TradeEngine.Series>) get("selectedSeries");
		Assert.assertTrue("bounded", held.size() <= 24);
	}

	/** The card's fetch holds the offer screen's own off for a minute, so
	 *  when it lands it re-prices an offer screen open for the item — the
	 *  screen a sell card leads to. And the chart's fetch still does. */
	@Test
	public void aCardsFetchRepricesTheOfferScreenItLeadsTo() throws Exception
	{
		plugin.noteShownCard(ICE_SACK, true);
		plugin.noteShownCard(null, false); // the offer screen takes the box
		runQueued();
		Assert.assertEquals(List.of(ICE_SACK), repriced);
		Assert.assertEquals("no card to rebuild for", 0, rebuilt);

		final Method m = PocketGeTrackerPlugin.class.getDeclaredMethod("requestSharedSeries", int.class);
		m.setAccessible(true);
		m.invoke(plugin, OTHER);
		runQueued();
		Assert.assertEquals(List.of(ICE_SACK, OTHER), repriced);
	}

	/** Already on screen when another caller's fetch brings its series —
	 *  the offer screen's, the chart's, a watchlist click's, or one that
	 *  held the attempt clock so the card's own was refused: taken up as it
	 *  lands. */
	@Test
	public void aCardAlreadyUpTakesUpASeriesAnotherCallerFetched() throws Exception
	{
		final Method req = PocketGeTrackerPlugin.class.getDeclaredMethod("requestSharedSeries", int.class);
		req.setAccessible(true);
		req.invoke(plugin, ICE_SACK); // the offer screen's chart, say
		plugin.noteShownCard(ICE_SACK, true); // refused: the clock is held
		Assert.assertEquals("one fetch queued, not two", 1, queued.size());
		runQueued();
		Assert.assertEquals(List.of(ICE_SACK), fetched);
		Assert.assertEquals("rebuilt as it landed", 1, rebuilt);

		cache(OTHER); // a watchlist click's, for a card not on screen
		Assert.assertEquals(1, rebuilt);
	}

	/** A sell paged past long ago is not priced from its old series: an old
	 *  day's range would set its ask. Back on the live print until shown
	 *  again. */
	@Test
	public void anOldSeriesNoLongerPricesTheCards()
	{
		final long now = 10_000_000L;
		final Map<Integer, TradeEngine.Series> held = new java.util.HashMap<>();
		final Map<Integer, Long> at = new java.util.HashMap<>();
		held.put(1, series());
		at.put(1, now - PocketGeTrackerPlugin.ON_DEMAND_SERIES_MAX_AGE_MS);
		held.put(2, series());
		at.put(2, now - PocketGeTrackerPlugin.ON_DEMAND_SERIES_MAX_AGE_MS - 1);
		held.put(3, series()); // no time on record
		Assert.assertEquals(java.util.Set.of(1), PocketGeTrackerPlugin.recentSeries(held, at, now, List.of()).keySet());
	}

	/** But the card still on screen keeps its series, however long it has
	 *  been up — it is not "shown again" to ask afresh. Nor the open offer
	 *  or the inspected item. */
	@Test
	public void whatIsOnScreenKeepsItsSeriesPastTheBound()
	{
		final long now = 10_000_000L;
		final Map<Integer, TradeEngine.Series> held = new java.util.HashMap<>();
		final Map<Integer, Long> at = new java.util.HashMap<>();
		for (int id = 1; id <= 3; id++)
		{
			held.put(id, series());
			at.put(id, now - 60 * 60 * 1000L);
		}
		Assert.assertEquals(java.util.Set.of(1, 3),
			PocketGeTrackerPlugin.recentSeries(held, at, now, List.of(1, 3)).keySet());
	}

	/** And it is what the cards are built from. Read from the source:
	 *  recomputeAdvice needs a whole running client. */
	@Test
	public void theCardsAreBuiltFromTheRecentOnesOnly() throws Exception
	{
		final String src = java.nio.file.Files.readString(
			java.nio.file.Path.of("src/main/java/com/pocketge/tracker/PocketGeTrackerPlugin.java"))
			.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
		final int at = src.indexOf("Map<Integer, TradeEngine.Series> cycleSeries =");
		Assert.assertTrue(at > 0);
		Assert.assertTrue(src.substring(at, at + 160).contains(
			"recentSeries(selectedSeries, selectedSeriesFetchedAt, cardsPricedAt, onScreen)"));
		final int list = src.lastIndexOf("final List<Integer> onScreen", at);
		Assert.assertTrue("built just before", list > 0 && at - list < 400);
		final String built = src.substring(list, at);
		for (String id : new String[]{"shownSellItemId", "geContextItemId", "selectedFavoriteItemId"})
		{
			Assert.assertTrue("on screen: " + id, built.contains(id));
		}
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
