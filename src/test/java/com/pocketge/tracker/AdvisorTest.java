package com.pocketge.tracker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class AdvisorTest
{
	private static final long NOW = 1_000_000L;

	private static Map<Integer, Advisor.Quote> quotes(long high, long low)
	{
		Advisor.Quote q = new Advisor.Quote();
		q.high = high;
		q.low = low;
		q.highTime = NOW;
		q.lowTime = NOW;
		Map<Integer, Advisor.Quote> m = new HashMap<>();
		m.put(1601, q);
		return m;
	}

	private static Map<Integer, Advisor.ItemMeta> meta()
	{
		Advisor.ItemMeta m = new Advisor.ItemMeta();
		m.id = 1601;
		m.name = "Diamond";
		m.limit = 100;
		m.dailyVolume = 1_000_000L;
		Map<Integer, Advisor.ItemMeta> out = new HashMap<>();
		out.put(1601, m);
		return out;
	}

	private Advisor.Suggestion sellSuggestion(Map<Integer, long[]> costBasis)
	{
		return sellSuggestion(costBasis, new HashMap<>());
	}

	private Advisor.Suggestion sellSuggestion(Map<Integer, long[]> costBasis, Map<Integer, TradeEngine.Series> seriesByItem)
	{
		Map<Integer, Integer> holdings = new HashMap<>();
		holdings.put(1601, 100);
		List<Advisor.Suggestion> out = Advisor.advise(NOW, quotes(2000, 1900), meta(), 0, holdings, new ArrayList<>(),
			new HashSet<>(), new HashSet<>(), 0, 0.01, 4, costBasis, seriesByItem, 0);
		return out.stream().filter(s -> s.type == Advisor.Suggestion.Type.SELL).findFirst().orElse(null);
	}

	/** Deterministic synthetic price history, oldest-first — enough buckets
	 *  and volume for TradeEngine to consider viable (see TradeEngineTest,
	 *  which uses the same shape directly against the engine). */
	private static TradeEngine.Series syntheticSeries(long now, int n, double baseLow, double baseHigh, long seed)
	{
		java.util.Random rnd = new java.util.Random(seed);
		TradeEngine.Series s = new TradeEngine.Series();
		s.labels = new long[n];
		s.low = new double[n];
		s.high = new double[n];
		s.lowVol = new double[n];
		s.highVol = new double[n];
		double low = baseLow, high = baseHigh;
		for (int i = 0; i < n; i++)
		{
			low += rnd.nextInt(3) - 1;
			high += rnd.nextInt(3) - 1;
			if (high < low + 40)
			{
				high = low + 40; // keep a real spread — 2% tax must stay clearable
			}
			s.labels[i] = now - (long) (n - 1 - i) * 300L;
			s.low[i] = low;
			s.high[i] = high;
			s.lowVol[i] = 50 + rnd.nextInt(200);
			s.highVol[i] = 50 + rnd.nextInt(200);
		}
		return s;
	}

	private static Advisor.OfferView sellOffer(int itemId, String name, long price)
	{
		Advisor.OfferView o = new Advisor.OfferView();
		o.slot = 0;
		o.itemId = itemId;
		o.itemName = name;
		o.buy = false;
		o.price = price;
		o.totalQuantity = 10;
		o.quantitySold = 0;
		o.active = true;
		return o;
	}

	/** The sell box lists several stacks, advise() shows only the best one —
	 *  both read the same ranking, so the top of the list must be exactly
	 *  what advise() picks. */
	@Test
	public void sellCandidatesRankBestFirstAndMatchAdvise()
	{
		Map<Integer, Advisor.Quote> q = quotes(2000, 1900);
		Advisor.Quote cheap = new Advisor.Quote();
		cheap.high = 500;
		cheap.low = 450;
		cheap.highTime = NOW;
		cheap.lowTime = NOW;
		q.put(1602, cheap);

		Map<Integer, Advisor.ItemMeta> m = meta();
		Advisor.ItemMeta m2 = new Advisor.ItemMeta();
		m2.id = 1602;
		m2.name = "Ruby";
		m2.limit = 100;
		m2.dailyVolume = 1_000_000L;
		m.put(1602, m2);

		Map<Integer, Integer> holdings = new HashMap<>();
		holdings.put(1601, 100); // 100 x (2000-40) = 196,000
		holdings.put(1602, 500); // 500 x (500-10)  = 245,000 -> should rank first

		List<Advisor.Suggestion> sells = Advisor.sellCandidates(NOW, q, m, holdings, new ArrayList<>(),
			new HashSet<>(), new HashSet<>(), null);

		Assert.assertEquals(2, sells.size());
		Assert.assertEquals(1602, sells.get(0).itemId);
		Assert.assertTrue(sells.get(0).expectedProfit >= sells.get(1).expectedProfit);
		// grossValue is the stack's after-tax sale value, independent of cost basis.
		Assert.assertEquals((500 - 10) * 500L, sells.get(0).grossValue);

		List<Advisor.Suggestion> advised = Advisor.advise(NOW, q, m, 0, holdings, new ArrayList<>(),
			new HashSet<>(), new HashSet<>(), 0, 0.01, 4, null, new HashMap<>(), 0);
		Advisor.Suggestion best = advised.stream()
			.filter(s -> s.type == Advisor.Suggestion.Type.SELL).findFirst().orElse(null);
		Assert.assertNotNull(best);
		Assert.assertEquals(sells.get(0).itemId, best.itemId);
	}

	/** The sell row shows "at X gp ea · bought Y" — Y comes from the tracked
	 *  lot's average, and must be absent (0) when nothing was tracked rather
	 *  than guessed at. */
	@Test
	public void sellCandidatesCarryAverageBuyPrice()
	{
		Map<Integer, Integer> holdings = new HashMap<>();
		holdings.put(1601, 100);
		Map<Integer, long[]> costBasis = new HashMap<>();
		costBasis.put(1601, new long[]{100, 150_000L}); // 100 bought for 150k -> 1,500 each

		Advisor.Suggestion s = Advisor.sellCandidates(NOW, quotes(2000, 1900), meta(), holdings,
			new ArrayList<>(), new HashSet<>(), new HashSet<>(), costBasis).get(0);
		Assert.assertEquals(1500, s.unitCost);
		Assert.assertTrue(s.hasTrackedCost);

		Advisor.Suggestion untracked = Advisor.sellCandidates(NOW, quotes(2000, 1900), meta(), holdings,
			new ArrayList<>(), new HashSet<>(), new HashSet<>(), null).get(0);
		Assert.assertEquals(0, untracked.unitCost);
		Assert.assertFalse(untracked.hasTrackedCost);
	}

	@Test
	public void sellCandidatesSkipStacksNotWorthASlot()
	{
		Map<Integer, Integer> holdings = new HashMap<>();
		holdings.put(1601, 1); // 1 x 1,960 gp — well under the 50k floor
		List<Advisor.Suggestion> sells = Advisor.sellCandidates(NOW, quotes(2000, 1900), meta(), holdings,
			new ArrayList<>(), new HashSet<>(), new HashSet<>(), null);
		Assert.assertTrue(sells.isEmpty());
	}

	@Test
	public void sellShowsRealProfitWhenFullyTracked()
	{
		Map<Integer, long[]> costBasis = new HashMap<>();
		costBasis.put(1601, new long[]{100, 150_000L}); // bought 100 @ 1,500 each

		Advisor.Suggestion sell = sellSuggestion(costBasis);
		Assert.assertNotNull(sell);
		long expectedProfit = (2000 - 40) * 100L - 150_000L; // (price - tax) * qty - cost
		Assert.assertEquals(expectedProfit, sell.expectedProfit);
		Assert.assertTrue(sell.reason.contains("profit vs your tracked buy price"));
	}

	@Test
	public void sellSplitsTrackedAndUntrackedPortions()
	{
		Map<Integer, long[]> costBasis = new HashMap<>();
		costBasis.put(1601, new long[]{60, 90_000L}); // only 60 of the 100 held are tracked

		Advisor.Suggestion sell = sellSuggestion(costBasis);
		long trackedProfit = (2000 - 40) * 60L - 90_000L;
		long untrackedValue = (2000 - 40) * 40L;
		Assert.assertEquals(trackedProfit + untrackedValue, sell.expectedProfit);
		Assert.assertTrue(sell.reason.contains("untracked units"));
	}

	@Test
	public void sellFallsBackToValueLanguageWithNoCostBasis()
	{
		Advisor.Suggestion sell = sellSuggestion(null);
		Assert.assertNotNull(sell);
		Assert.assertEquals((2000 - 40) * 100L, sell.expectedProfit);
		Assert.assertTrue(sell.reason.contains("worth ~"));
	}

	@Test
	public void sellUsesEngineTargetWhenSeriesAvailable()
	{
		TradeEngine.Series series = syntheticSeries(NOW, 200, 1900, 2000, 11);
		TradeEngine.Result expected = TradeEngine.compute(1900, 2000, NOW, NOW, series, 1601);
		Assert.assertTrue("synthetic series should be viable for this test to be meaningful", expected.viable);

		Map<Integer, TradeEngine.Series> seriesByItem = new HashMap<>();
		seriesByItem.put(1601, series);
		Advisor.Suggestion sell = sellSuggestion(null, seriesByItem);
		Assert.assertNotNull(sell);
		/* Repriced to the engine target, not raw q.high — but clamped so it
		   can never land UNDER the standing bid. This series makes the engine
		   shave a gp off 2000 to fill faster, which is exactly the shave that
		   had the plugin quoting 111 for Yew logs against a 115 bid: pointless
		   (the GE fills you at the resting offer's price anyway) and it reads
		   as the item being worth less than it is. */
		final long target = TradeEngine.sellTarget(expected.sell, 2000);
		Assert.assertEquals(target, sell.price);
		Assert.assertTrue("a sell must never be quoted under the bid", sell.price >= 2000);
		Assert.assertTrue(sell.reason.contains(target + " gp"));
	}

	@Test
	public void sellFallsBackToRawQuoteWithoutSeries()
	{
		Advisor.Suggestion sell = sellSuggestion(null, new HashMap<>());
		Assert.assertNotNull(sell);
		Assert.assertEquals(2000, sell.price); // raw q.high, no engine series supplied
	}

	@Test
	public void adjustSellUsesEngineTargetWhenSeriesAvailable()
	{
		Map<Integer, Advisor.Quote> q = quotes(2000, 1900); // high=2000, low=1900
		TradeEngine.Series series = syntheticSeries(NOW, 200, 1900, 2000, 11);
		Map<Integer, TradeEngine.Series> seriesByItem = new HashMap<>();
		seriesByItem.put(1601, series);
		List<Advisor.OfferView> offers = new ArrayList<>();
		offers.add(sellOffer(1601, "Diamond", 2500)); // way above market -> triggers ADJUST_SELL

		TradeEngine.Result expected = TradeEngine.compute(1900, 2000, NOW, NOW, series, 1601);
		Assert.assertTrue("synthetic series should be viable for this test to be meaningful", expected.viable);

		List<Advisor.Suggestion> out = Advisor.advise(NOW, q, meta(), 0, new HashMap<>(), offers,
			new HashSet<>(), new HashSet<>(), 0, 0.01, 4, null, seriesByItem, 0);
		Advisor.Suggestion adjust = out.stream().filter(s -> s.type == Advisor.Suggestion.Type.ADJUST_SELL).findFirst().orElse(null);
		Assert.assertNotNull(adjust);
		Assert.assertEquals(TradeEngine.sellTarget(expected.sell, 2000), adjust.price);
		Assert.assertTrue("a sell must never be quoted under the bid", adjust.price >= 2000);
	}

	@Test
	public void adjustSellKeepsAnEngineTargetThatSitsAboveTheBid()
	{
		/* The companion to the clamp: when the engine's target is ABOVE the
		   standing bid it must pass through untouched. Without this, a clamp
		   bug that simply returned q.high every time would still pass the
		   tests above, since there the two happen to coincide. */
		Map<Integer, Advisor.Quote> q = quotes(1990, 1900); // bid below the series' own range
		TradeEngine.Series series = syntheticSeries(NOW, 200, 1900, 2000, 11);
		Map<Integer, TradeEngine.Series> seriesByItem = new HashMap<>();
		seriesByItem.put(1601, series);
		List<Advisor.OfferView> offers = new ArrayList<>();
		offers.add(sellOffer(1601, "Diamond", 2500));

		TradeEngine.Result expected = TradeEngine.compute(1900, 1990, NOW, NOW, series, 1601);
		Assert.assertTrue("synthetic series should be viable", expected.viable);
		Assert.assertTrue("engine target must exceed the bid for this test to mean anything",
			expected.sell > 1990);

		List<Advisor.Suggestion> out = Advisor.advise(NOW, q, meta(), 0, new HashMap<>(), offers,
			new HashSet<>(), new HashSet<>(), 0, 0.01, 4, null, seriesByItem, 0);
		Advisor.Suggestion adjust = out.stream().filter(s -> s.type == Advisor.Suggestion.Type.ADJUST_SELL).findFirst().orElse(null);
		Assert.assertNotNull(adjust);
		Assert.assertEquals("the engine's own target survives when it is above the bid",
			expected.sell, adjust.price);
	}

	@Test
	public void adjustSellFallsBackToRawQuoteWithoutSeries()
	{
		Map<Integer, Advisor.Quote> q = quotes(2000, 1900);
		List<Advisor.OfferView> offers = new ArrayList<>();
		offers.add(sellOffer(1601, "Diamond", 2500));

		List<Advisor.Suggestion> out = Advisor.advise(NOW, q, meta(), 0, new HashMap<>(), offers,
			new HashSet<>(), new HashSet<>(), 0, 0.01, 4, null, new HashMap<>(), 0);
		Advisor.Suggestion adjust = out.stream().filter(s -> s.type == Advisor.Suggestion.Type.ADJUST_SELL).findFirst().orElse(null);
		Assert.assertNotNull(adjust);
		Assert.assertEquals(2000, adjust.price); // raw q.high, no engine series supplied
	}

	/* ── Buy ordering ────────────────────────────────────────────────────── */

	private static void put(Map<Integer, Advisor.Quote> q, Map<Integer, Advisor.ItemMeta> m,
		int id, String name, long high, long low)
	{
		final Advisor.Quote quote = new Advisor.Quote();
		quote.high = high;
		quote.low = low;
		quote.highTime = NOW;
		quote.lowTime = NOW;
		q.put(id, quote);
		final Advisor.ItemMeta meta = new Advisor.ItemMeta();
		meta.id = id;
		meta.name = name;
		meta.limit = 100;
		meta.dailyVolume = 1_000_000L;
		m.put(id, meta);
	}

	/**
	 * A fresh idea outranks a bigger one you are already holding.
	 *
	 * The held item here makes strictly MORE money, so profit alone would put
	 * it first. It goes last anyway: you may have spent the 4-hour limit
	 * acquiring the stack already, and buying more of what you carry is a
	 * different bet from the new one underneath it.
	 */
	@Test
	public void buyIdeasForThingsYouAlreadyOwnSortLast()
	{
		final Map<Integer, Advisor.Quote> q = new HashMap<>();
		final Map<Integer, Advisor.ItemMeta> m = new HashMap<>();
		put(q, m, 1601, "Fresh idea", 2000, 1900);   // smaller edge
		put(q, m, 1602, "Already held", 4000, 3800); // bigger edge

		final Map<Integer, Integer> holdings = new HashMap<>();
		holdings.put(1602, 50);

		final List<Advisor.Suggestion> buys = Advisor.advise(NOW, q, m, 10_000_000L, holdings,
			new ArrayList<>(), new HashSet<>(), new HashSet<>(), 0, 0.01, 4, null, new HashMap<>(), 0)
			.stream().filter(s -> s.type == Advisor.Suggestion.Type.BUY)
			.collect(java.util.stream.Collectors.toList());

		Assert.assertEquals("both items are still offered — this reorders, it never drops",
			2, buys.size());
		Assert.assertEquals("the item you do not own comes first", 1601, buys.get(0).itemId);
		Assert.assertEquals("the one in your bank comes last, despite the bigger margin",
			1602, buys.get(1).itemId);
		Assert.assertTrue("...and it really was the more profitable of the two",
			buys.get(1).expectedProfit > buys.get(0).expectedProfit);
	}

	/** Within a group, nothing changed: profit still decides. */
	@Test
	public void amongUnheldIdeasProfitStillRanks()
	{
		final Map<Integer, Advisor.Quote> q = new HashMap<>();
		final Map<Integer, Advisor.ItemMeta> m = new HashMap<>();
		put(q, m, 1601, "Thin", 2000, 1900);
		put(q, m, 1602, "Fat", 4000, 3800);

		final List<Advisor.Suggestion> buys = Advisor.advise(NOW, q, m, 10_000_000L, new HashMap<>(),
			new ArrayList<>(), new HashSet<>(), new HashSet<>(), 0, 0.01, 4, null, new HashMap<>(), 0)
			.stream().filter(s -> s.type == Advisor.Suggestion.Type.BUY)
			.collect(java.util.stream.Collectors.toList());

		Assert.assertEquals(1602, buys.get(0).itemId);
		Assert.assertEquals(1601, buys.get(1).itemId);
	}

	/* ── Min. profit floor ───────────────────────────────────────────────── */

	private static List<Advisor.Suggestion> buysWithFloor(long floor)
	{
		return Advisor.advise(NOW, quotes(2000, 1900), meta(), 10_000_000L, new HashMap<>(), new ArrayList<>(),
			new HashSet<>(), new HashSet<>(), 0, 0.01, 4, null, new HashMap<>(), floor);
	}

	private static long buyProfit(List<Advisor.Suggestion> out)
	{
		return out.stream().filter(s -> s.type == Advisor.Suggestion.Type.BUY)
			.mapToLong(s -> s.expectedProfit).max().orElse(-1);
	}

	/** The only buy on offer clears ~6k, so it survives Auto and dies under a
	 *  20k floor. Both halves matter: the floor has to actually bind, and
	 *  passing 0 has to leave the old behaviour untouched. */
	@Test
	public void aProfitFloorRemovesIdeasThatDoNotClearIt()
	{
		Assert.assertTrue("the sample flip clears the advisor's own default floor",
			buyProfit(buysWithFloor(0)) > 0);
		Assert.assertEquals("nothing survives a floor above what the flip makes",
			-1, buyProfit(buysWithFloor(20_000)));
	}

	/**
	 * The relax-and-retry that keeps the panel from ever being empty must not
	 * relax past a floor the player set by hand.
	 *
	 * This is the whole bug the setting would otherwise have: advise() drops
	 * its volume and profit bars to nothing when the first pass finds no buys,
	 * which is right for "the market is thin" and exactly wrong for "don't
	 * show me anything under 500k" — the second pass would hand back the very
	 * idea the first pass just rejected.
	 */
	@Test
	public void theEmptyListFallbackStillRespectsAFloorTheUserSet()
	{
		final List<Advisor.Suggestion> out = buysWithFloor(20_000);
		Assert.assertTrue("the fallback pass must not resurrect a sub-floor idea",
			out.stream().noneMatch(s -> s.type == Advisor.Suggestion.Type.BUY));
	}
}
