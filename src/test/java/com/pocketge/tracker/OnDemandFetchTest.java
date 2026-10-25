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
 * The requests the sidebar makes on its own, between the advisor's rounds:
 * one per pick, an old series on the offer screen asked for afresh, and a
 * favourite's hourly series as soon as its row finds it missing.
 */
public class OnDemandFetchTest
{
	private PocketGeTrackerPlugin plugin;
	private boolean advisorOn = true;
	private final List<Runnable> queued = new ArrayList<>();
	private final List<Integer> fetched = new ArrayList<>();
	private final List<Integer> failing = new ArrayList<>();
	private boolean backOff;
	private int asked;

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

	private static String source() throws Exception
	{
		return java.nio.file.Files.readString(
			java.nio.file.Path.of("src/main/java/com/pocketge/tracker/PocketGeTrackerPlugin.java"))
			.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
	}

	@Before
	public void wire() throws Exception
	{
		plugin = new PocketGeTrackerPlugin();
		set("running", true);
		set("loggedIn", true);
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
				return new TradeEngine.Series();
			}

			@Override
			public PriceExtremes fetchRecentExtremes(int itemId) throws java.io.IOException
			{
				asked++;
				if (backOff)
				{
					throw new MarketClient.BackingOff(60_000);
				}
				fetched.add(itemId);
				if (failing.contains(itemId))
				{
					throw new java.io.IOException("Wiki said no");
				}
				return new PriceExtremes();
			}
		});
		plugin.rebuildCards = () -> { };
		plugin.repriceOffer = id -> { };
	}

	/* ── One request per pick ─────────────────────────────────────────── */

	/** A pick from search, the finder or the GE slots calls
	 *  onSelectedItemChanged twice — inspectItem, then setSelectedItem — and
	 *  each sent its own request. It asks through the shared attempt clock
	 *  now, and asks for nothing else. */
	@Test
	public void aPickAsksThroughTheSharedClock() throws Exception
	{
		final String src = source();
		final int at = src.indexOf("public void onSelectedItemChanged(Integer itemId)");
		Assert.assertTrue(at > 0);
		final String body = src.substring(at, src.indexOf("@Override", at));
		Assert.assertTrue(body, body.contains("requestSharedSeries(itemId);"));
		Assert.assertFalse(body, body.contains("fetchTimeseries5m"));
		Assert.assertFalse(body, body.contains("execute("));
	}

	@Test
	public void twoAsksForOnePickSendOneRequest() throws Exception
	{
		final Method ask = PocketGeTrackerPlugin.class.getDeclaredMethod("requestSharedSeries", int.class);
		ask.setAccessible(true);
		ask.invoke(plugin, 4151);
		ask.invoke(plugin, 4151);
		runQueued();
		Assert.assertEquals(List.of(4151), fetched);
	}

	/* ── The offer screen ─────────────────────────────────────────────── */

	/** An old series is asked for afresh on the offer screen, as on the
	 *  cards: it went only when there was none, and one from forty minutes
	 *  back priced the screen while the cards had dropped it. */
	@Test
	public void theOfferScreenRefetchesAnOldSeries() throws Exception
	{
		final String src = source();
		final int at = src.indexOf("private void updateGeContextFromScreen(boolean fetch)");
		Assert.assertTrue(at > 0);
		final String body = src.substring(at, src.indexOf("\n\t}\n", at));
		Assert.assertTrue(body, body.contains("final boolean wantSeries = wantsSeries(itemId)"));
		Assert.assertFalse(body, body.contains("seriesFor(itemId) == null"));
	}

	/** Its queued fetch looks again when it runs: the advisor's round,
	 *  queued ahead, may have refetched the open offer's series. */
	@Test
	public void theOfferScreensFetchLooksAgainWhenItRuns() throws Exception
	{
		final String src = source();
		final int at = src.indexOf("private void updateGeContextFromScreen(boolean fetch)");
		final String body = src.substring(at, src.indexOf("\n\t}\n", at));
		final int task = body.indexOf("execute(");
		Assert.assertTrue(task > 0);
		Assert.assertTrue(body.substring(task), body.substring(task)
			.contains("if (wantSeries && running && config.advisor() && wantsSeries(itemId))"));
	}

	private TradeEngine.Series screenSeries(int itemId) throws Exception
	{
		final Method m = PocketGeTrackerPlugin.class.getDeclaredMethod("screenSeries", int.class);
		m.setAccessible(true);
		return (TradeEngine.Series) m.invoke(plugin, itemId);
	}

	@SuppressWarnings("unchecked")
	private void held(int itemId, TradeEngine.Series s, long ageMs) throws Exception
	{
		final Map<Integer, TradeEngine.Series> m = new java.util.HashMap<>();
		m.put(itemId, s);
		set("selectedSeries", m);
		((Map<Integer, Long>) get("selectedSeriesFetchedAt")).put(itemId, System.currentTimeMillis() - ageMs);
	}

	private void carded(int itemId, TradeEngine.Series s) throws Exception
	{
		final Map<Integer, TradeEngine.Series> m = new java.util.HashMap<>();
		if (s != null)
		{
			m.put(itemId, s);
		}
		set("cardsSeries", m);
	}

	/** The offer screen prices from what the cards priced from once what it
	 *  holds is past the cards' age bound: a refused refetch left it quoting
	 *  a forty-minute-old series beside cards at the live print. */
	@Test
	public void theOfferScreenPricesAsTheCardsDo() throws Exception
	{
		final TradeEngine.Series old = new TradeEngine.Series();
		final TradeEngine.Series round = new TradeEngine.Series();
		final long bound = PocketGeTrackerPlugin.ON_DEMAND_SERIES_MAX_AGE_MS;
		held(4151, old, 40 * 60_000L);
		carded(4151, null);
		Assert.assertNull("the cards had none: the live print, as theirs", screenSeries(4151));
		carded(4151, round);
		Assert.assertSame("the cards' own", round, screenSeries(4151));
		carded(4151, old);
		Assert.assertSame("the same one the cards took up", old, screenSeries(4151));
		held(4151, old, bound - 60_000L);
		carded(4151, null);
		Assert.assertSame("inside the bound: what is held", old, screenSeries(4151));
		set("selectedSeries", new java.util.HashMap<>());
		Assert.assertNull(screenSeries(4151));
	}

	/* ── A favourite's hourly series ──────────────────────────────────── */

	@SuppressWarnings("unchecked")
	private Map<Integer, PriceExtremes> dayExtremes() throws Exception
	{
		return (Map<Integer, PriceExtremes>) get("dayExtremes");
	}

	@SuppressWarnings("unchecked")
	private Map<Integer, Long> dueAt() throws Exception
	{
		return (Map<Integer, Long>) get("dayExtremesDueAt");
	}

	private void askDay(Integer... ids) throws Exception
	{
		final Method m = PocketGeTrackerPlugin.class.getDeclaredMethod("requestDayExtremes", List.class);
		m.setAccessible(true);
		m.invoke(plugin, List.of(ids));
	}

	/** A favourite just added has its series asked for at once, a few at a
	 *  time, and the rest wait at least a minute — or the round. */
	@Test
	public void missingFavouritesAreAskedForAFewAtATime() throws Exception
	{
		askDay(1, 2, 3, 4, 5, 6, 7);
		Assert.assertEquals(1, queued.size());
		askDay(1, 2, 3, 4, 5, 6, 7);
		Assert.assertEquals("not again inside the minute", 1, queued.size());
		final Object sentAt = get("dayExtremesOnDemandAt");
		set("dayExtremesOnDemandAt", System.currentTimeMillis() - 61_000L);
		askDay(1, 2, 3, 4, 5, 6, 7);
		Assert.assertEquals("one batch at a time, however long it waits", 1, queued.size());
		set("dayExtremesOnDemandAt", sentAt);
		runQueued();
		Assert.assertEquals(List.of(1, 2, 3, 4, 5), fetched);
		Assert.assertTrue(dayExtremes().keySet().containsAll(List.of(1, 2, 3, 4, 5)));
		Assert.assertTrue("on the round's hourly clock", dueAt().get(1) > System.currentTimeMillis() + 50 * 60_000L);
		askDay(6, 7);
		runQueued();
		Assert.assertEquals("not again inside the minute", 5, fetched.size());
		set("dayExtremesOnDemandAt", System.currentTimeMillis() - 61_000L);
		askDay(6, 7);
		runQueued();
		Assert.assertEquals(List.of(1, 2, 3, 4, 5, 6, 7), fetched);
	}

	/** One that failed waits for the round's retry clock; one the round
	 *  brought while the batch was queued is not asked for twice. */
	@Test
	public void failedOrAlreadyBroughtAreNotAskedAgain() throws Exception
	{
		failing.add(1);
		askDay(1, 2);
		dayExtremes().put(2, new PriceExtremes()); // the round, queued ahead
		runQueued();
		Assert.assertEquals(List.of(1), fetched);
		Assert.assertFalse(dayExtremes().containsKey(1));
		set("dayExtremesOnDemandAt", 0L);
		askDay(1);
		Assert.assertTrue("on the retry clock", queued.isEmpty());
	}

	/** Picked, then failed by the round queued ahead of it: on the retry
	 *  clock, not asked for again seconds later. */
	@Test
	public void oneTheRoundFailedMeanwhileWaits() throws Exception
	{
		askDay(1, 2);
		dueAt().put(1, System.currentTimeMillis() + 10 * 60_000L);
		runQueued();
		Assert.assertEquals(List.of(2), fetched);
	}

	/** Backing off: the rest of the batch is not sent. */
	@Test
	public void backingOffStopsTheBatch() throws Exception
	{
		backOff = true;
		askDay(1, 2, 3);
		runQueued();
		Assert.assertEquals("asked once, then stopped", 1, asked);
		Assert.assertTrue(dayExtremes().isEmpty());
		Assert.assertTrue("not a failure: no retry clock", dueAt().isEmpty());
		Assert.assertFalse("free for the next", ((java.util.concurrent.atomic.AtomicBoolean) get("dayExtremesOnDemandBusy")).get());
	}

	/** As the round: nothing logged out, nothing with the advisor off. */
	@Test
	public void onlyLoggedInWithTheAdvisorOn() throws Exception
	{
		set("loggedIn", false);
		askDay(1);
		advisorOn = false;
		set("loggedIn", true);
		askDay(1);
		Assert.assertTrue(queued.isEmpty());
		advisorOn = true;
		askDay();
		Assert.assertTrue("nothing missing, nothing sent", queued.isEmpty());
	}
}
