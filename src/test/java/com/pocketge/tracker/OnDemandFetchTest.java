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
	private boolean aheadOn = true;
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
				if ("priceNextCard".equals(m.getName()))
				{
					return aheadOn;
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

	/** The cards ahead of the one on screen are asked for when the cards
	 *  have no series for them — and not when they do, or with the setting
	 *  off. */
	@Test
	public void theCardsAheadAreFetchedWhenTheCardsHaveNone() throws Exception
	{
		carded(4151, new TradeEngine.Series());
		plugin.noteCardsAhead(List.of(4151, 11802));
		runQueued();
		Assert.assertEquals(List.of(11802), fetched);
		Assert.assertTrue(((Map<?, ?>) get("selectedSeries")).containsKey(11802));
		aheadOn = false;
		plugin.noteShownCard(1, false); // a new card: a new budget
		plugin.noteCardsAhead(List.of(1513));
		plugin.noteCardsAhead(null);
		Assert.assertTrue("off: nothing asked", queued.isEmpty());
	}

	/** Two requests for each card shown, however the list changes under it:
	 *  ideas ahead turned down one after another asked again landing after
	 *  landing. A new card, a new two. */
	@Test
	public void twoForEachCardShown() throws Exception
	{
		plugin.noteShownCard(1, false);
		plugin.noteCardsAhead(List.of(11, 12));
		plugin.noteCardsAhead(List.of(13, 14)); // both turned down
		plugin.noteCardsAhead(List.of(16, 17)); // and those
		runQueued();
		Assert.assertEquals(List.of(11, 12), fetched);
		plugin.noteShownCard(13, false);
		plugin.noteCardsAhead(List.of(14, 15));
		runQueued();
		Assert.assertEquals(List.of(11, 12, 14, 15), fetched);
	}

	/** The round keeps them current after that; switching the setting on
	 *  asks for the ones ahead now. */
	@Test
	public void theRoundKeepsThemAndTheSwitchAsks() throws Exception
	{
		final String src = source();
		final int at = src.indexOf("final Integer shownSell = shownSellItemId;");
		Assert.assertTrue(src.substring(at, at + 900).matches("(?s).*if \\(config\\.priceNextCard\\(\\)\\)\\s*\\{\\s*ids\\.addAll\\(cardsAheadIds\\);"
			+ "\\s*final Integer shownCard = shownCardItemId;\\s*if \\(shownCard != null\\)\\s*\\{\\s*ids\\.add\\(shownCard\\);.*"));
		Assert.assertTrue(src.contains("case \"priceNextCard\":"));
		plugin.noteShownCard(1, false);
		aheadOn = false;
		plugin.noteCardsAhead(List.of(11, 12));
		aheadOn = true;
		final java.lang.reflect.Field budget = PocketGeTrackerPlugin.class.getDeclaredField("aheadBudgetCard");
		budget.setAccessible(true);
		budget.set(plugin, null);
		plugin.noteCardsAhead((List<Integer>) get("cardsAheadIds"));
		runQueued();
		Assert.assertEquals(List.of(11, 12), fetched);
	}

	/** The round, run for real: the card on screen and the two ahead of it
	 *  are among what it fetches with the setting on — a card ahead the
	 *  round priced went raw once it was paged to — and not with it off. */
	@Test
	public void theRoundFetchesTheCardOnScreenAndTheCardsAhead() throws Exception
	{
		final java.lang.reflect.Method round = PocketGeTrackerPlugin.class.getDeclaredMethod("refreshOfferSeries");
		round.setAccessible(true);
		plugin.noteShownCard(20011, false);
		plugin.noteCardsAhead(List.of(20012, 20013));
		queued.clear(); // their own fetches not landed: the round has nothing to reuse
		round.invoke(plugin);
		runQueued();
		Assert.assertEquals(new java.util.HashSet<>(List.of(20011, 20012, 20013)), new java.util.HashSet<>(fetched));
		fetched.clear();
		((Map<?, ?>) get("lastOfferSeries")).clear();
		((Map<?, ?>) get("offerSeriesFetchedAt")).clear();
		((Map<?, ?>) get("selectedSeries")).clear();
		set("selectedSeries", new java.util.HashMap<>());
		aheadOn = false;
		round.invoke(plugin);
		runQueued();
		Assert.assertTrue(fetched.toString(), fetched.isEmpty());
	}

	/** While a card is ahead it keeps the series it is priced from: read one
	 *  card for a quarter of an hour and the next went back to the last two
	 *  prints. Not one the cards had already dropped; and not once it is no
	 *  longer ahead. Nor is it evicted meanwhile. */
	@Test
	public void aCardAheadKeepsItsSeries() throws Exception
	{
		final Method pricing = PocketGeTrackerPlugin.class.getDeclaredMethod("pricingSeries", long.class);
		pricing.setAccessible(true);
		final TradeEngine.Series ahead = new TradeEngine.Series();
		held(11802, ahead, 20 * 60_000L);
		carded(11802, ahead);
		plugin.noteCardsAhead(List.of(11802));
		Assert.assertSame(ahead, ((Map<?, ?>) pricing.invoke(plugin, System.currentTimeMillis())).get(11802));
		carded(11802, null);
		Assert.assertNull("dropped before: not brought back",
			((Map<?, ?>) pricing.invoke(plugin, System.currentTimeMillis())).get(11802));
		carded(11802, ahead);
		plugin.noteCardsAhead(List.of());
		Assert.assertNull("no longer ahead",
			((Map<?, ?>) pricing.invoke(plugin, System.currentTimeMillis())).get(11802));
		final Method cache = PocketGeTrackerPlugin.class.getDeclaredMethod("cacheOnDemandSeries", int.class,
			TradeEngine.Series.class);
		cache.setAccessible(true);
		plugin.noteCardsAhead(List.of(997));
		cache.invoke(plugin, 997, new TradeEngine.Series());
		for (int id = 1; id <= 40; id++)
		{
			Thread.sleep(1);
			cache.invoke(plugin, id, new TradeEngine.Series());
		}
		Assert.assertTrue("not evicted while ahead", ((Map<?, ?>) get("selectedSeries")).containsKey(997));
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

	/** As recomputeAdvice does: the cards priced from pricingSeries now. */
	private int rebuilds;

	private void rebuildLikeTheCards() throws Exception
	{
		final Method pricing = PocketGeTrackerPlugin.class.getDeclaredMethod("pricingSeries", long.class);
		pricing.setAccessible(true);
		plugin.rebuildCards = () ->
		{
			rebuilds++;
			try
			{
				set("cardsSeries", pricing.invoke(plugin, System.currentTimeMillis()));
			}
			catch (Exception e)
			{
				throw new IllegalStateException(e);
			}
		};
	}

	/** An offer screen opened on a series the cards had dropped — forty
	 *  minutes old, from a sell card paged past — rebuilds the cards once,
	 *  and with the screen's item on screen they take that same series, so a
	 *  refused refetch leaves one number rather than 1998 beside 1990. */
	@Test
	public void theCardsTakeUpTheOfferScreensSeries() throws Exception
	{
		rebuildLikeTheCards();
		final TradeEngine.Series old = new TradeEngine.Series();
		held(4151, old, 40 * 60_000L);
		carded(4151, null);
		plugin.syncCardsTo(4151);
		Assert.assertEquals("off screen: dropped, as before", 1, rebuilds);
		Assert.assertNull(((Map<?, ?>) get("cardsSeries")).get(4151));
		set("geContextItemId", 4151);
		plugin.syncCardsTo(4151);
		Assert.assertEquals("the cards were rebuilt since: asked again", 2, rebuilds);
		Assert.assertSame("the screen's own series", old, ((Map<?, ?>) get("cardsSeries")).get(4151));
		plugin.syncCardsTo(4151);
		Assert.assertEquals("in step: nothing more", 2, rebuilds);
		/* Backed out; the cards rebuilt without it; opened again on the same
		   old series. */
		set("geContextItemId", null);
		plugin.rebuildCards.run();
		Assert.assertNull(((Map<?, ?>) get("cardsSeries")).get(4151));
		set("geContextItemId", 4151);
		plugin.syncCardsTo(4151);
		Assert.assertEquals(4, rebuilds);
		Assert.assertSame("synced again on the second visit", old, ((Map<?, ?>) get("cardsSeries")).get(4151));
	}

	/** With the advisor off nothing rebuilds the cards, and the rows and
	 *  header go on reading their map — so the screen reads it too, and
	 *  nothing is asked of a rebuild that would not run. */
	@Test
	public void withTheAdvisorOffTheScreenReadsTheCardsMap() throws Exception
	{
		plugin.rebuildCards = () -> rebuilds++;
		final TradeEngine.Series old = new TradeEngine.Series();
		final TradeEngine.Series round = new TradeEngine.Series();
		held(4151, old, 40 * 60_000L);
		carded(4151, round);
		Assert.assertSame("on: the newest", old, plugin.screenSeries(4151));
		advisorOn = false;
		Assert.assertSame("off: the cards'", round, plugin.screenSeries(4151));
		carded(4151, null);
		Assert.assertNull(plugin.screenSeries(4151));
		plugin.syncCardsTo(4151);
		Assert.assertEquals(0, rebuilds);
		advisorOn = true;
		plugin.syncCardsTo(4151);
		Assert.assertEquals("not held against it once back on", 1, rebuilds);
	}

	/** An active offer's item counts as on screen: its slot prices from
	 *  seriesFor at any age, so the cards' "losing bid" verdict must too. */
	@Test
	public void anActiveOffersSeriesStaysInTheCards() throws Exception
	{
		final Method pricing = PocketGeTrackerPlugin.class.getDeclaredMethod("pricingSeries", long.class);
		pricing.setAccessible(true);
		final TradeEngine.Series topUp = new TradeEngine.Series();
		held(4151, topUp, 20 * 60_000L);
		Assert.assertNull("past the bound, off screen: dropped",
			((Map<?, ?>) pricing.invoke(plugin, System.currentTimeMillis())).get(4151));
		set("lastActiveOfferItemIds", new java.util.HashSet<>(List.of(4151)));
		Assert.assertSame("in an active offer: kept", topUp,
			((Map<?, ?>) pricing.invoke(plugin, System.currentTimeMillis())).get(4151));
	}

	/** A day around 950 / 1050 up to now: one the engine prices well away
	 *  from a 990 / 995 book. */
	private static TradeEngine.Series priced()
	{
		final int n = 200;
		final long now = System.currentTimeMillis() / 1000L;
		final TradeEngine.Series s = new TradeEngine.Series();
		s.labels = new long[n];
		s.low = new double[n];
		s.high = new double[n];
		s.lowVol = new double[n];
		s.highVol = new double[n];
		for (int i = 0; i < n; i++)
		{
			s.labels[i] = now - (n - 1 - i) * 300L;
			s.low[i] = 950 + i % 7;
			s.high[i] = 1050 + i % 5;
			s.lowVol[i] = 50;
			s.highVol[i] = 50;
		}
		return s;
	}

	/** The buy side prices from the cards' map, as every other surface does:
	 *  an on-demand series the cards dropped is no opinion here either, and
	 *  one they hold is used whatever seriesFor would say. */
	@Test
	public void theBuySideReadsTheCardsMap() throws Exception
	{
		final Method verdict = PocketGeTrackerPlugin.class.getDeclaredMethod("engineVerdict", int.class,
			Advisor.Quote.class, Map.class);
		verdict.setAccessible(true);
		final Advisor.Quote q = new Advisor.Quote();
		q.low = 990;
		q.high = 995;
		q.lowTime = q.highTime = System.currentTimeMillis() / 1000L;
		held(4151, priced(), 40 * 60_000L);
		carded(4151, null);
		Assert.assertNull("dropped by the cards: no opinion",
			verdict.invoke(plugin, 4151, q, new java.util.HashMap<>()));
		set("selectedSeries", new java.util.HashMap<>());
		carded(4151, priced());
		Assert.assertNotNull("held by the cards: theirs",
			verdict.invoke(plugin, 4151, q, new java.util.HashMap<>()));
	}

	/** Switching the advisor back on with an offer screen up reads it
	 *  afresh after the rebuild — known to the plugin or blanked for an old
	 *  book — asking for nothing, as the first tick fetches the book; and
	 *  every tick re-reads a Set up screen that is up, so one blanked for
	 *  want of any book comes back with the first. */
	@Test
	public void theOfferScreenIsReadAfreshWhenItCanBePriced() throws Exception
	{
		final String src = source();
		final int at = src.indexOf("private void syncAdvisor()");
		final String sync = src.substring(at, src.indexOf("\n\t}\n", at));
		Assert.assertTrue(sync, sync.matches("(?s).*recomputeAdvice\\(\\);\\s*readOfferScreenIfUp\\(false\\);.*"));
		final int tick = src.indexOf("private void refreshPrices(long bookMaxAgeMs)");
		final String round = src.substring(tick, src.indexOf("\n\t}\n", tick));
		Assert.assertTrue(round, round.matches("(?s).*recomputeAdvice\\(\\);\\s*readOfferScreenIfUp\\(true\\);\\s*$"));
		Assert.assertFalse("the tick no longer needs a screen it knows", round.contains("repriceOpenOffer(0)"));
		final int helper = src.indexOf("private void readOfferScreenIfUp(boolean reviving)");
		final String body = src.substring(helper, src.indexOf("\n\t}\n", helper));
		Assert.assertTrue(body, body.matches("(?s).*final Widget setup = client\\.getWidget\\(InterfaceID\\.GeOffers\\.SETUP\\);\\s*"
			+ "if \\(setup != null && !setup\\.isHidden\\(\\)\\)\\s*\\{\\s*updateGeContextFromScreen\\(reviving && geContextItemId == null\\);.*"));
	}

	/** A BUY card being read keeps the series it is priced from in the
	 *  cards' map, as a sell card does — without asking for one; and one the
	 *  cards had dropped before it was paged back to is not brought back,
	 *  which repriced the card minutes into reading it. */
	@Test
	public void theBuyCardOnScreenKeepsItsSeries() throws Exception
	{
		final Method pricing = PocketGeTrackerPlugin.class.getDeclaredMethod("pricingSeries", long.class);
		pricing.setAccessible(true);
		final TradeEngine.Series topUp = new TradeEngine.Series();
		held(4151, topUp, 20 * 60_000L);
		carded(4151, topUp);
		plugin.noteShownCard(4151, false);
		Assert.assertSame("priced from it: kept", topUp,
			((Map<?, ?>) pricing.invoke(plugin, System.currentTimeMillis())).get(4151));
		Assert.assertTrue("nothing asked for a buy", queued.isEmpty());
		plugin.noteShownCard(11802, false);
		Assert.assertNull("shown away: dropped",
			((Map<?, ?>) pricing.invoke(plugin, System.currentTimeMillis())).get(4151));
		carded(4151, null);
		plugin.noteShownCard(4151, false);
		Assert.assertNull("paged back to after it was dropped: not brought back",
			((Map<?, ?>) pricing.invoke(plugin, System.currentTimeMillis())).get(4151));
		carded(4151, new TradeEngine.Series());
		Assert.assertNull("priced from another: not this one",
			((Map<?, ?>) pricing.invoke(plugin, System.currentTimeMillis())).get(4151));
	}

	/** Nor is it evicted past the cache's cap while it is up. */
	@Test
	public void theBuyCardOnScreenIsNotEvicted() throws Exception
	{
		final Method cache = PocketGeTrackerPlugin.class.getDeclaredMethod("cacheOnDemandSeries", int.class,
			TradeEngine.Series.class);
		cache.setAccessible(true);
		plugin.noteShownCard(997, false);
		cache.invoke(plugin, 997, new TradeEngine.Series());
		for (int id = 1; id <= 40; id++)
		{
			Thread.sleep(1);
			cache.invoke(plugin, id, new TradeEngine.Series());
		}
		Assert.assertTrue(((Map<?, ?>) get("selectedSeries")).containsKey(997));
		Assert.assertFalse("the oldest others went", ((Map<?, ?>) get("selectedSeries")).containsKey(1));
	}

	/** The chart's Target line off the Set up screen — over a finished
	 *  offer's status panel — reads the cards' map, as every card does. */
	@Test
	@SuppressWarnings("unchecked")
	public void theChartsTargetOffTheSetUpScreenReadsTheCardsMap() throws Exception
	{
		final Method target = PocketGeTrackerPlugin.class.getDeclaredMethod("chartTarget", int.class, boolean.class);
		target.setAccessible(true);
		final Advisor.Quote q = new Advisor.Quote();
		q.low = 990;
		q.high = 995;
		q.lowTime = q.highTime = System.currentTimeMillis() / 1000L;
		final Map<Integer, Advisor.Quote> book = new java.util.HashMap<>();
		book.put(4151, q);
		set("lastQuotes", book);
		set("lastQuotesAt", System.currentTimeMillis());
		final TradeEngine.Series topUp = priced();
		held(4151, topUp, 40 * 60_000L);
		carded(4151, null);
		Assert.assertEquals("the cards dropped it: the live print, as they price it",
			PocketGeTrackerPlugin.offerScreenPrice(q, null, false), (long) (Long) target.invoke(plugin, 4151, false));
		carded(4151, topUp);
		Assert.assertEquals("the cards hold it: theirs",
			PocketGeTrackerPlugin.offerScreenPrice(q, TradeEngine.viewTargets(q.low, q.high, q.lowTime, q.highTime, topUp, 4151), false),
			(long) (Long) target.invoke(plugin, 4151, false));
		Assert.assertNotEquals("and the two differ, or this proves nothing",
			PocketGeTrackerPlugin.offerScreenPrice(q, null, false),
			PocketGeTrackerPlugin.offerScreenPrice(q, TradeEngine.viewTargets(q.low, q.high, q.lowTime, q.highTime, topUp, 4151), false));
	}

	/** Per item: two items the cards still price from series no longer
	 *  held each get their rebuild. */
	@Test
	public void eachItemItsOwn() throws Exception
	{
		plugin.rebuildCards = () -> rebuilds++;
		final Map<Integer, TradeEngine.Series> cards = new java.util.HashMap<>();
		cards.put(4151, new TradeEngine.Series());
		cards.put(11802, new TradeEngine.Series());
		set("cardsSeries", cards);
		plugin.syncCardsTo(4151);
		plugin.syncCardsTo(11802);
		Assert.assertEquals(2, rebuilds);
	}

	/** The item picked in the sidebar the same: its inspect card and the
	 *  buy card it came from read one series. */
	@Test
	public void aPickedItemIsSyncedToo() throws Exception
	{
		rebuildLikeTheCards();
		final TradeEngine.Series old = new TradeEngine.Series();
		held(4151, old, 40 * 60_000L);
		carded(4151, null);
		set("selectedFavoriteItemId", 4151);
		plugin.syncCardsTo(4151);
		Assert.assertSame(old, ((Map<?, ?>) get("cardsSeries")).get(4151));
		final String src = source();
		final int at = src.indexOf("public void onSelectedItemChanged(Integer itemId)");
		final String body = src.substring(at, src.indexOf("@Override", at));
		Assert.assertTrue(body, body.matches("(?s).*selectedFavoriteItemId = itemId;\\s*if \\(itemId != null\\)\\s*\\{\\s*syncCardsTo\\(itemId\\);.*"));
	}

	/** Once per series: cards that cannot take it up (the advisor off, say)
	 *  are not rebuilt on every re-read; a new series is a new try. */
	@Test
	@SuppressWarnings("unchecked")
	public void onceForEachSeries() throws Exception
	{
		final TradeEngine.Series old = new TradeEngine.Series();
		plugin.rebuildCards = () -> rebuilds++;
		held(4151, old, 40 * 60_000L);
		carded(4151, null);
		plugin.syncCardsTo(4151);
		plugin.syncCardsTo(4151);
		Assert.assertEquals(1, rebuilds);
		held(4151, new TradeEngine.Series(), 60_000L);
		plugin.syncCardsTo(4151);
		Assert.assertEquals(2, rebuilds);
		/* The round's copy, newer: the one seriesFor gives the screen. */
		final Map<Integer, TradeEngine.Series> round = new java.util.HashMap<>();
		round.put(4151, new TradeEngine.Series());
		set("lastOfferSeries", round);
		((Map<Integer, Long>) get("offerSeriesFetchedAt")).put(4151, System.currentTimeMillis());
		plugin.syncCardsTo(4151);
		Assert.assertEquals("the screen's, from either cache", 3, rebuilds);
		set("lastOfferSeries", new java.util.HashMap<>());
		set("selectedSeries", new java.util.HashMap<>());
		carded(4151, null);
		plugin.syncCardsTo(4151);
		Assert.assertEquals("neither has one: in step", 3, rebuilds);
	}

	/** The screen prices from seriesFor, as the buy cards do, and brings the
	 *  cards onto it once it knows its item. */
	@Test
	public void theOfferScreenSyncsOnceItKnowsItsItem() throws Exception
	{
		final String src = source();
		final int at = src.indexOf("private void updateGeContextFromScreen(boolean fetch)");
		final String body = src.substring(at, src.indexOf("\n\t}\n", at));
		Assert.assertTrue(body.contains("final TradeEngine.Series series = screenSeries(itemId);"));
		final int target = src.indexOf("private long chartTarget(int itemId, boolean buy)");
		Assert.assertTrue(src.substring(target, src.indexOf("\n\t}\n", target)).contains("cardsSeries.get(itemId)"));
		Assert.assertTrue(body, body.matches("(?s).*geContextItemId = itemId;\\s*syncCardsTo\\(itemId\\);.*"));
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
